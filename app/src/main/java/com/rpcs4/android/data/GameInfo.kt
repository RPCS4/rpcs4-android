package com.rpcs4.android.data

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * One entry of the game library.
 *
 * [bootFsPath] is the path handed to the native core via PS4::loadAndRun().
 * It is always a real POSIX-visible path: either inside the app-private import
 * root or a direct storage path in direct-path mode.
 */
data class GameInfo(
    val titleId: String,
    val title: String,
    val version: String,
    val bootFsPath: String,
    /** PNG bytes extracted from sce_sys/icon0.png (null when missing/malformed). */
    val iconPng: ByteArray?,
    val sizeBytes: Long,
    /** Human readable source, e.g. "imported" or "/sdcard/Games/GTAV". */
    val sourceLabel: String,
) {
    val displayName: String get() = title.ifBlank { titleId }

    override fun equals(other: Any?): Boolean =
        other is GameInfo && other.bootFsPath == bootFsPath

    override fun hashCode(): Int = bootFsPath.hashCode()
}

/** Decodes and caches library icons so scrolling never re-reads from disk. */
object GameIconCache {

    private val cache = LinkedHashMap<String, ImageBitmap>()
    private const val MAX_ENTRIES = 96

    @Synchronized
    fun iconFor(game: GameInfo): ImageBitmap? {
        cache[game.titleId]?.let { return it }
        val png = game.iconPng ?: return null
        val bmp = runCatching { BitmapFactory.decodeByteArray(png, 0, png.size) }
            .getOrNull()
            ?.asImageBitmap()
            ?: return null

        if (cache.size >= MAX_ENTRIES) {
            cache.remove(cache.keys.first())
        }
        cache[game.titleId] = bmp
        return bmp
    }
}
