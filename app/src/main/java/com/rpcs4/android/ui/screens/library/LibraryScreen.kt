package com.rpcs4.android.ui.screens.library

import android.graphics.ImageBitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rpcs4.android.data.GameIconCache
import com.rpcs4.android.data.GameInfo

/**
 * Game library - the Android counterpart of the desktop Qt game list/grid.
 * Tap a card to boot; the folder picker (SAF) drives an initial import scan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBootGame: (String) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val games by viewModel.games.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val progress by viewModel.importProgress.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.onFolderPicked(uri)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Library") },
            actions = {
                IconButton(onClick = { picker.launch(null) }) {
                    Icon(Icons.Default.Folder, contentDescription = "Pick games folder")
                }
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            },
        )

        if (progress.active) {
            LinearProgressIndicator(
                progress = {
                    if (progress.total > 0) progress.current.toFloat() / progress.total else 0f
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Text(
                text = "Importing ${progress.label}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        error?.let { message ->
            AlertDialog(
                onDismissRequest = { viewModel.clearError() },
                confirmButton = {
                    Button(onClick = { viewModel.clearError() }) { Text("OK") }
                },
                title = { Text("Library error") },
                text = { Text(message) },
            )
        }

        when {
            loading && games.isEmpty() -> FullCenter {
                CircularProgressIndicator()
            }

            !loading && games.isEmpty() -> EmptyState(
                onPickFolder = { picker.launch(null) },
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(games.size, key = { games[it].titleId }) { index ->
                    GameCard(
                        game = games[index],
                        icon = GameIconCache.iconFor(games[index]),
                        onClick = { onBootGame(games[index].titleId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GameCard(game: GameInfo, icon: ImageBitmap?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = icon,
                    contentDescription = game.titleId,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.SportsEsports,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(56.dp),
                )
            }
        }

        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = game.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text(game.titleId, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(24.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatSize(game.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onPickFolder: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No games yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose a folder containing PS4 game directories. Each one needs eboot.bin plus sce_sys/param.sfo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onPickFolder) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Pick games folder")
        }
    }
}

@Composable
private fun FullCenter(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1048576.0)
    bytes > 0 -> "$bytes B"
    else -> ""
}
