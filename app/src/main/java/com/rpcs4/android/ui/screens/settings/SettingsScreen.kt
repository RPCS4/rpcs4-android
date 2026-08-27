package com.rpcs4.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Maps PS4::Configuration.hpp onto Android storage.
 *
 * Every toggle below corresponds to one desktop command-line switch; changes
 * take effect at the next game boot (nativeStart re-pushes all globals).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val config by viewModel.config.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {

            // ------------------------------------------------------------- GPU
            SectionHeader("GPU")

            Text(
                text = "Resolution scale: %.2fx".format(config.resolutionScale),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = config.resolutionScale,
                onValueChange = { viewModel.setResolutionScale((it * 100).toInt() / 100f) },
                valueRange = 0.5f..3.0f,
                steps = 9,
            )
            Text(
                text = "Experimental upstream feature - most games ignore it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ToggleRow(
                title = "Copy GPU command buffers",
                subtitle = "--copy-command-buffers",
                checked = config.copyCommandBuffers,
                onChange = { viewModel.setToggle("copyCommandBuffers", it) },
            )
            ToggleRow(
                title = "Skip async compute dispatches",
                subtitle = "--skip-async-compute-dispatches",
                checked = config.skipAsyncComputeDispatches,
                onChange = { viewModel.setToggle("skipAsyncComputeDispatches", it) },
            )
            ToggleRow(
                title = "Skip WaitRegMem packets",
                subtitle = "--skip-waitregmem",
                checked = config.skipWaitRegMem,
                onChange = { viewModel.setToggle("skipWaitRegMem", it) },
            )
            ToggleRow(
                title = "Disable GNM detiler texture-size hack",
                subtitle = "--disable-gnmdetiler-texture-size",
                checked = config.disableGnmDetilerTextureSize,
                onChange = { viewModel.setToggle("disableGnmDetilerTextureSize", it) },
            )
            ToggleRow(
                title = "Disable SGPR init hack",
                subtitle = "--disable-sgpr-init-hack",
                checked = config.disableSgprInitHack,
                onChange = { viewModel.setToggle("disableSgprInitHack", it) },
            )
            ToggleRow(
                title = "Clamp GPU buffers to mapped memory",
                subtitle = "--clamp-gpu-buffers",
                checked = config.clampGpuBuffers,
                onChange = { viewModel.setToggle("clampGpuBuffers", it) },
            )
            ToggleRow(
                title = "Skip bindless GPU buffers",
                subtitle = "--skip-bindless-buffers",
                checked = config.skipBindlessBuffers,
                onChange = { viewModel.setToggle("skipBindlessBuffers", it) },
            )

            Spacer(Modifier.height(8.dp))

            // -------------------------------------------------------- OS / HLE
            SectionHeader("OS / HLE")

            ToggleRow(
                title = "Force-initialize libSceComposite",
                subtitle = "--force-init-sce-compositor (needed by some homebrew)",
                checked = config.forceInitSceCompositor,
                onChange = { viewModel.setToggle("forceInitSceCompositor", it) },
            )
            ToggleRow(
                title = "LLE libSceSsl",
                subtitle = "--lle-ssl (requires decrypted system files; rarely useful)",
                checked = config.lleSsl,
                onChange = { viewModel.setToggle("lleSsl", it) },
            )

            Spacer(Modifier.height(8.dp))

            SectionHeader("Paths")
            Text(
                text = "System dirs are provided by the app:\n\n" +
                    "/data/data/com.rpcs4.android/files/system  ->  PS4::Configuration::system_dir_path\n" +
                    "/data/data/com.rpcs4.android/files/system_ex -> system_ex_dir_path\n" +
                    "/data/data/com.rpcs4.android/files/home    ->  SDL_GetPrefPath()/user data",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
