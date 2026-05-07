package com.unianx.osmo.remotecontrol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private fun osmoColorScheme(colors: OsmoColors, darkTheme: Boolean): ColorScheme {
    return if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.ink,
            primaryContainer = colors.surface2,
            onPrimaryContainer = colors.ink,
            secondary = colors.inkMuted,
            onSecondary = colors.canvas,
            background = colors.canvas,
            onBackground = colors.ink,
            surface = colors.surface1,
            onSurface = colors.ink,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.inkMuted,
            outline = colors.hairline,
            outlineVariant = colors.hairlineStrong,
            error = colors.warning,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.surface1,
            primaryContainer = colors.surface3,
            onPrimaryContainer = colors.ink,
            secondary = colors.inkMuted,
            onSecondary = colors.surface1,
            background = colors.canvas,
            onBackground = colors.ink,
            surface = colors.surface1,
            onSurface = colors.ink,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.inkMuted,
            outline = colors.hairline,
            outlineVariant = colors.hairlineStrong,
            error = colors.warning,
        )
    }
}

@Composable
fun OsmoRemoteControlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkOsmoColors else LightOsmoColors
    val colorScheme = osmoColorScheme(colors = colors, darkTheme = darkTheme)

    CompositionLocalProvider(LocalOsmoColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = OsmoTypography,
            content = content,
        )
    }
}
