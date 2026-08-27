package com.rpcs4.android.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Scans a user-selected tree for PS4 game directories.
 *
 * A game directory is detected by the presence of:
 *     <dir>/eboot.bin            (or eboot.self)
 *     <dir>/sce_sys/param.sfo
 *     <dir>/sce_sys/icon0.png    (optional)
 *
 * Two source modes exist:
 *   - IMPORT (default): everything under the picked SAF tree is copied into
 *     app-private storage (filesDir/games/<TITLE_ID>), producing plain paths
 *     the C++ core can open with std::ifstream. Costs disk space but works on
 *     every device and matches how PS4 saves tend to be small relative to games.
 *   - DIRECT: user grants "All files access"; the raw /storage path is scanned
 *     with java.io.File and handed to the core unchanged. Zero-copy but needs
 *     MANAGE_EXTERNAL_STORAGE.
 */
class GameRepository(private val context: Context) {

    // ------------------------------------------------------------ direct mode

    fun scanDirect(rootDir: File): List<GameInfo> {
        require(rootDir.isDirectory) { "${rootDir.absolutePath} is not a directory" }
        return rootDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { child ->
            detectGame(child)?.copy(sourceLabel = child.absolutePath)
        }.orEmpty().sortedBy { it.displayName.lowercase() }
    }

    /** Returns null when [dir] does not look like a PS4 game directory. */
    fun detectGame(dir: File): GameInfo? {
        // Boot image must exist, whatever its exact flavor.
        if (!File(dir, "eboot.bin").isFile && !File(dir, "eboot.self").isFile) return null
        val sfoFile = File(dir, "sce_sys/param.sfo").takeIf { it.isFile } ?: return null
        val icon = runCatching { File(dir, "sce_sys/icon0.png").takeIf { it.isFile }?.readBytes() }
            .getOrNull()

        val fallback = GameInfo(
            titleId = dir.name,
            title = dir.name,
            version = "",
            bootFsPath = dir.absolutePath,
            iconPng = icon,
            sizeBytes = 0L,
            sourceLabel = dir.absolutePath,
        )

        val sfo = runCatching { SfoParser.parse(sfoFile.readBytes()) }.getOrNull() ?: return fallback
        val sizeBytes = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

        return GameInfo(
            titleId = sfo[SfoParser.KEY_TITLE_ID]?.trim().orEmpty().ifBlank { dir.name },
            title = sfo[SfoParser.KEY_TITLE]?.trim().orEmpty().ifBlank { dir.name },
            version = sfo[SfoParser.KEY_APP_VER] ?: sfo[SfoParser.KEY_VERSION].orEmpty(),
            bootFsPath = dir.absolutePath,
            iconPng = icon,
            sizeBytes = sizeBytes,
            sourceLabel = dir.absolutePath,
        )
    }

    // ------------------------------------------------------------- import mode

    /**
     * Copies every detected game directory below the picked SAF tree into the
     * private import root and returns the resulting [GameInfo]s.
     *
     * @param onProgress called after each file copy (0..total, currentTitle)
     */
    fun scanAndImport(
        treeUri: Uri,
        onProgress: (copied: Int, total: Int, currentTitle: String) -> Unit = { _, _, _ -> },
    ): List<GameInfo> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val resolver = context.contentResolver
        val importedRoot = importRoot(context)

        val gameDirs = childrenDocs(resolver, treeUri, rootDocId).filter { it.isDirectoryUri }

        var totalEstimate = 0
        val results = mutableListOf<GameInfo>()

        gameDirs.forEachIndexed { idx, dir ->
            val displayName = dir.name
            onProgress(idx, gameDirs.size, displayName)

            val targetName = sanitized(displayName)
            val destDir = File(importedRoot, targetName)
            if (destDir.exists()) {
                // Re-import overwrites; stale content would corrupt the library view.
                destDir.deleteRecursively()
            }
            destDir.mkdirs()

            var filesCopied = 0
            copyRecursive(resolver, dir.uri, destDir) {
                ++filesCopied
                onProgress(idx + 1, gameDirs.size + filesCopied, "${displayName}/${it?.name}")
            }

            val detected = detectGame(destDir)
            if (detected != null) {
                results += detected.copy(sourceLabel = "imported")
                totalEstimate += 1
            } else {
                // Not a game directory (e.g. stray folders); keep the copied data but skip listing.
                results += GameInfo(
                    titleId = destDir.name,
                    title = displayName,
                    version = "",
                    bootFsPath = destDir.absolutePath,
                    iconPng = null,
                    sizeBytes = 0L,
                    sourceLabel = "imported (unrecognized)",
                )
            }
        }

        return results.sortedBy { it.displayName.lowercase() }
    }

    fun importRoot(context: Context): File =
        File(context.filesDir, "games").apply { mkdirs() }

    private fun sanitized(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.\\- ]"), "_").ifBlank { "GAME_" }

    private fun copyRecursive(
        resolver: android.content.ContentResolver,
        dirUri: Uri,
        destDir: File,
        onFile: (File?) -> Unit,
    ) {
        val docId = DocumentsContract.getDocumentId(dirUri)
        val kids = childrenDocs(resolver, dirUri, docId)
        for (kid in kids) {
            if (kid.isDirectoryUri) {
                val sub = File(destDir, kid.name)
                sub.mkdirs()
                copyRecursive(resolver, kid.uri, sub, onFile)
            } else {
                val out = File(destDir, kid.name)
                runCatching {
                    resolver.openInputStream(kid.uri)?.use { input ->
                        out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    }
                    onFile(out)
                }.onFailure { out.delete(); onFile(null) }
            }
        }
    }

    private data class ChildDoc(val uri: Uri, val name: String, val isDirectoryUri: Boolean, val mime: String)

    private fun childrenDocs(
        resolver: android.content.ContentResolver,
        parentTreeOrDocUri: Uri,
        documentId: String,
    ): List<ChildDoc> {
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(parentTreeOrDocUri, documentId)
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }

        val result = mutableListOf<ChildDoc>()
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: ""
                val uri = DocumentsContract.buildDocumentUriUsingTree(parentTreeOrDocUri, id)
                result += ChildDoc(uri, name, mime == DocumentsContract.Document.MIME_TYPE_DIR, mime)
            }
        }
        return result
    }
}
