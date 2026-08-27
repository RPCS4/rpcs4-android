package com.rpcs4.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * RPCS4 follows a fixed dark identity (matching the desktop emulator's QSS
 * theme); dynamic color is only applied when the user's system offers it and
 * on sufficiently new builds.
 */
private val DarkScheme = darkColorScheme(
    primary = IndigoPrimary,
    onPrimary = IndigoOnPrimary,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = OnIndigoContainer,
    background = SurfaceDark,
    onBackground = OnSurface,
    surface = SurfaceDark,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariant,
)

private val LightScheme = lightColorScheme()

@Composable
fun Rpcs4Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = if (darkTheme) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(context).copy(
                background = SurfaceDark,
                surface = SurfaceDark,
            )
        } else {
            DarkScheme
        }
    } else {
        LightScheme
    }

    MaterialTheme(colorScheme = scheme, content = content)
}
