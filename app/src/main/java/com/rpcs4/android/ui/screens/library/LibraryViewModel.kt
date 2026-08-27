package com.rpcs4.android.ui.screens.library

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rpcs4.android.data.GameInfo
import com.rpcs4.android.data.GameRepository
import com.rpcs4.android.data.SettingsRepository
import com.rpcs4.android.data.SourceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

data class ImportProgress(
    val current: Int,
    val total: Int,
    val label: String,
    val active: Boolean,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = GameRepository(app)
    private val appContext = app

    private val _games = MutableStateFlow<List<GameInfo>>(emptyList())
    val games: StateFlow<List<GameInfo>> = _games.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _sourceMode = MutableStateFlow(SourceMode.IMPORT)
    val sourceMode: StateFlow<SourceMode> = _sourceMode.asStateFlow()

    private val _importProgress = MutableStateFlow(ImportProgress(0, 0, "", false))
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    init {
        viewModelScope.launch {
            SettingsRepository.sourceMode(appContext).collect { mode ->
                _sourceMode.value = mode
                refresh()
            }
        }
    }

    /** Persist the SAF tree grant and kick an import scan. */
    fun onFolderPicked(uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure {
            // Some pickers hand out non-persistable grants; scanning still works this session.
        }

        viewModelScope.launch {
            SettingsRepository.setGamesTreeUri(appContext, uri.toString())
        }

        viewModelScope.launch(Dispatchers.IO) {
            scanImportTree(uri)
        }
    }

    fun setDirectRoot(path: String) {
        val dir = File(path)
        if (!dir.isDirectory) {
            _error.value = "$path is not a readable directory"
            return
        }
        viewModelScope.launch {
            SettingsRepository.setDirectRoot(appContext, dir.absolutePath)
        }
    }

    fun setSourceMode(mode: SourceMode) {
        viewModelScope.launch {
            SettingsRepository.setSourceMode(appContext, mode)
        }
    }

    fun clearError() {
        _error.value = null
    }

    /** Re-run detection for whichever source mode is active. */
    fun refresh() {
        when (_sourceMode.value) {
            SourceMode.IMPORT -> refreshImported(initialImportIfEmpty = true)
            SourceMode.DIRECT -> viewModelScope.launch(Dispatchers.IO) {
                val root = SettingsRepository.directRoot(appContext).first()
                if (root.isBlank()) return@launch
                _loading.value = true
                runCatching { repo.scanDirect(File(root)) }
                    .onSuccess { _games.value = it; _error.value = null }
                    .onFailure { _error.value = it.message ?: "Scan failed" }
                _loading.value = false
            }
        }
    }

    /**
     * Refresh from the already-imported tree on disk. Games imported in a
     * previous session are still under filesDir/games and boot without a re-scan.
     */
    private fun refreshImported(initialImportIfEmpty: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            val importedRoot = repo.importRoot(appContext)
            val found = importedRoot.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { repo.detectGame(it) }
                .orEmpty()
                .sortedBy { it.displayName.lowercase() }

            _games.value = found
            _loading.value = false

            // Nothing cached yet but a SAF grant exists -> run the initial import once.
            if (initialImportIfEmpty && found.isEmpty()) {
                val treeUri = SettingsRepository.gamesTreeUri(appContext).first()
                if (treeUri.isNotBlank()) scanImportTree(Uri.parse(treeUri))
            }
        }
    }

    private suspend fun scanImportTree(treeUri: Uri) {
        _loading.value = true
        _importProgress.value = ImportProgress(0, 1, "Preparing…", active = true)

        runCatching {
            repo.scanAndImport(treeUri) { current, total, label ->
                _importProgress.value = ImportProgress(current, total, label, active = true)
            }
        }
            .onSuccess { list ->
                _games.value = list.sortedBy { it.displayName.lowercase() }
                _error.value = null
            }
            .onFailure { _error.value = it.message ?: "Import failed" }

        _importProgress.value = ImportProgress(0, 0, "", active = false)
        _loading.value = false
    }
}
