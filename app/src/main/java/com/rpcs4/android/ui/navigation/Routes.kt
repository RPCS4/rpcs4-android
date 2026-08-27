package com.rpcs4.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** App destinations. Library hosts tabs for games/logs; emulator is chrome-less fullscreen. */
sealed class Routes(val route: String) {
    data object Library : Routes("library")
    data object Logs : Routes("logs")
    data object Settings : Routes("settings")
    data object Emulator : Routes("emulator/{titleId}") {
        const val PATTERN = "emulator/{titleId}"
        fun build(titleId: String) = "emulator/$titleId"
    }
}

/** Bottom bar entries (shown only outside emulation). */
data class BottomEntry(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val bottomEntries = listOf(
    BottomEntry(Routes.Library.route, "Library", Icons.AutoMirrored.Filled.ListAlt),
    BottomEntry(Routes.Logs.route, "Logs", Icons.AutoMirrored.Filled.List),
    BottomEntry(Routes.Settings.route, "Settings", Icons.Filled.Settings),
)
