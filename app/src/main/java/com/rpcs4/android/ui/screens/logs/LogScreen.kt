package com.rpcs4.android.ui.screens.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * In-app log window - the Android counterpart of the desktop LogWindow,
 * streaming everything the core prints through Logger.hpp (MAKE_LOG_FUNCTION).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(viewModel: LogViewModel = viewModel()) {
    val lines by viewModel.lines.collectAsState()

    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Logs") },
            actions = {
                TextButton(onClick = { viewModel.clear() }) {
                    Text("Clear")
                }
            },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(lines.size) { index ->
                Text(
                    text = lines[index],
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = when {
                        lines[index].contains("FATAL", ignoreCase = true) -> MaterialTheme.colorScheme.error
                        lines[index].startsWith("[Loader") -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
