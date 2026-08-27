package com.rpcs4.android.ui.screens.emulator

import android.view.Surface
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rpcs4.android.MainActivity
import com.rpcs4.android.emu.PadStateMux
import com.rpcs4.android.native.NativeBridge
import kotlinx.coroutines.isActive

/**
 * Fullscreen emulation view:
 *
 *   SurfaceView -> Surface -> ANativeWindow -> VkSurfaceKHR (VK_KHR_android_surface)
 *
 * The core's GCN thread creates its own Vulkan window through the SDL-compat
 * shim; we only need to register our native window with it before booting.
 * On-screen DualShock controls overlay the render surface; system chrome is
 * hidden while emulation is active.
 */
@Composable
fun EmulatorScreen(
    titleId: String,
    onExit: () -> Unit,
    viewModel: EmulatorViewModel = viewModel(),
) {
    val phase by viewModel.phase.collectAsState()
    val paused by viewModel.paused.collectAsState()
    val message by viewModel.message.collectAsState()

    val context = LocalContext.current
    val overlayState = remember { OverlayState() }

    // Immersive mode + input pump + config push, bound to this screen's lifetime.
    DisposableEffect(titleId) {
        (context as? MainActivity)?.setImmersive(true)
        PadStateMux.start(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        viewModel.ensureStarted(titleId)
        onDispose {
            PadStateMux.stop()
            PadStateMux.clearAll()
            NativeBridge.nativeSetSurface(null)
            (context as? MainActivity)?.setImmersive(false)
            if (!viewModel.isError()) {
                viewModel.stopEmulation { }
            }
        }
    }

    // Push overlay pad state whenever it changes; mux also sends at 60 Hz.
    LaunchedEffect(overlayState) {
        while (isActive) {
            PadStateMux.pushOverlayState(overlayState.snapshot())
            kotlinx.coroutines.delay(16)
        }
    }

    // NOTE: intentionally NO opaque background on this Box - SurfaceView
    // relies on punch-through compositing against the window backdrop
    // (theme's #121318 windowBackground).
    Box(modifier = Modifier.fillMaxSize()) {

        // ------------------------------------------------------------- surface
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // Keep the game surface behind compose-drawn HUD controls.
                    setZOrderOnTop(false)
                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                            NativeBridge.nativeSetSurface(this@apply.holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: android.view.SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            // Renderer reads drawable size per flip via the shim,
                            // so nothing to re-push here.
                        }

                        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                            NativeBridge.nativeSetSurface(null)
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { },
        )

        // ------------------------------------------------------------ overlay UI
        when (phase) {
            EmuPhase.BOOTING -> BootOverlay("Booting $titleId…")

            EmuPhase.RUNNING -> {
                OnScreenOverlay(state = overlayState)

                // Pause affordance stays reachable even in immersive mode.
                TextButton(
                    onClick = {
                        viewModel.togglePause()
                        PadStateMux.setPaused(!paused)
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp),
                ) {
                    Text(if (paused) "\u25B6 Resume" else "\u23F8", color = Color.White.copy(alpha = 0.65f))
                }
            }

            EmuPhase.STOPPING -> BootOverlay("Stopping…")

            EmuPhase.ERROR -> AlertDialog(
                onDismissRequest = {},
                title = { Text("Emulation error") },
                text = { Text(message) },
                confirmButton = {
                    Button(onClick = onExit) { Text("Back to library") }
                },
            )
        }
    }
}

@Composable
private fun BootOverlay(label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(label, color = Color.White)
    }
}
