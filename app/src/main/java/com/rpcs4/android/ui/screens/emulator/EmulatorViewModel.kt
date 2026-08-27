package com.rpcs4.android.ui.screens.emulator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rpcs4.android.data.GameInfo
import com.rpcs4.android.data.GameRepository
import com.rpcs4.android.data.SettingsRepository
import com.rpcs4.android.data.SourceMode
import com.rpcs4.android.native.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class EmuPhase { BOOTING, RUNNING, STOPPING, ERROR }

class EmulatorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = GameRepository(app)
    private val appContext = app

    private val _phase = MutableStateFlow(EmuPhase.BOOTING)
    val phase: StateFlow<EmuPhase> = _phase.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private var runningPoller: Job? = null
    private var bootedOnce = false

    /**
     * Resolve the boot path for [titleId] across both source modes and start
     * the emulation thread. Safe to call multiple times - only the first call
     * performs the actual boot.
     */
    fun ensureStarted(titleId: String) {
        if (bootedOnce) return
        bootedOnce = true

        viewModelScope.launch(Dispatchers.IO) {
            val game = resolveGame(titleId)
            if (game == null) {
                _phase.value = EmuPhase.ERROR
                _message.value = "Game $titleId not found under the configured library root"
                return@launch
            }

            val config = SettingsRepository.snapshot(appContext)

            // Push Configuration.hpp globals before booting (mirrors CLI flags).
            NativeBridge.ensureLoaded()
            NativeBridge.nativeSetConfiguration(
                resolutionScale = config.resolutionScale,
                copyCommandBuffers = config.copyCommandBuffers,
                skipAsyncComputeDispatches = config.skipAsyncComputeDispatches,
                skipWaitRegMem = config.skipWaitRegMem,
                disableGnmDetilerTextureSize = config.disableGnmDetilerTextureSize,
                disableSgprInitHack = config.disableSgprInitHack,
                clampGpuBuffers = config.clampGpuBuffers,
                skipBindlessBuffers = config.skipBindlessBuffers,
                forceInitSceCompositor = config.forceInitSceCompositor,
                lleSsl = config.lleSsl,
            )

            val systemDir = getApplication<Application>().filesDir.resolve("system").absolutePath
            val systemExDir = getApplication<Application>().filesDir.resolve("system_ex").absolutePath
            val homeDir = getApplication<Application>().filesDir.resolve("home").absolutePath

            // The renderer creates its Vulkan window inside PS4::init(); if no
            // Surface has been registered by then the window handoff fails.
            // Wait generously - SurfaceView callbacks usually land within ms.
            var waitedMs = 0
            while (!NativeBridge.nativeIsSurfaceReady() && waitedMs < 8_000) {
                delay(50)
                waitedMs += 50
            }
            if (!NativeBridge.nativeIsSurfaceReady()) {
                _phase.value = EmuPhase.ERROR
                _message.value = "Presentation surface never became available"
                return@launch
            }

            val ok = NativeBridge.nativeStart(game.bootFsPath, systemDir, systemExDir, homeDir)
            if (!ok) {
                _phase.value = EmuPhase.ERROR
                _message.value = "nativeStart() rejected the request"
                return@launch
            }
            _phase.value = EmuPhase.RUNNING

            // Track lifecycle: renderer exits its pump when quit is injected.
            runningPoller = viewModelScope.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(400)
                    if (!NativeBridge.nativeIsRunning()) {
                        if (_phase.value == EmuPhase.RUNNING || _phase.value == EmuPhase.BOOTING) {
                            _phase.value = EmuPhase.STOPPING
                            break
                        }
                    }
                }
            }
        }
    }

    fun togglePause() {
        val next = !_paused.value
        _paused.value = next
        if (next) PadStateMux.clearAll()
    }

    fun resume() = run { _paused.value = false }

    fun isError(): Boolean = _phase.value == EmuPhase.ERROR

    /** Cooperative stop, then signal the UI to pop back once the pump drains. */
    fun stopEmulation(onStopped: () -> Unit) {
        _phase.value = EmuPhase.STOPPING
        viewModelScope.launch(Dispatchers.Default) {
            NativeBridge.nativeStop()
            // Wait up to 5 s for a clean exit before bailing out anyway.
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && NativeBridge.nativeIsRunning()) {
                delay(100)
            }
            PadStateMux.stop()
            launch(Dispatchers.Main) { onStopped() }
        }
    }

    override fun onCleared() {
        runningPoller?.cancel()
        super.onCleared()
    }

    private suspend fun resolveGame(titleId: String): GameInfo? {
        val importedRoot = repo.importRoot(appContext)
        importedRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { repo.detectGame(it) }
            ?.firstOrNull { it.titleId.equals(titleId, ignoreCase = true) }
            ?.let { return it }

        if (SettingsRepository.sourceMode(appContext).first() == SourceMode.DIRECT) {
            val root = SettingsRepository.directRoot(appContext).first()
            if (root.isNotBlank()) {
                repo.scanDirect(java.io.File(root))
                    .firstOrNull { it.titleId.equals(titleId, ignoreCase = true) }
                    ?.let { return it }
            }
        }
        return null
    }
}
