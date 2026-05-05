package com.unianx.osmo.remotecontrol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val OsmoColorScheme = darkColorScheme(
    primary = OsmoAccent,
    onPrimary = OsmoInk,
    primaryContainer = OsmoSurface2,
    onPrimaryContainer = OsmoInk,
    secondary = OsmoInkMuted,
    onSecondary = OsmoCanvas,
    background = OsmoCanvas,
    onBackground = OsmoInk,
    surface = OsmoSurface1,
    onSurface = OsmoInk,
    surfaceVariant = OsmoSurface2,
    onSurfaceVariant = OsmoInkMuted,
    outline = OsmoHairline,
    outlineVariant = OsmoHairlineStrong,
    error = OsmoWarning,
)

@Composable
fun OsmoRemoteControlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = OsmoColorScheme,
        typography = OsmoTypography,
        content = content,
    )
}
