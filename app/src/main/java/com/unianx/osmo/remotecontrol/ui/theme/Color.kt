package com.unianx.osmo.remotecontrol.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val OsmoAccentBase = Color(0xFF5E6AD2)
val OsmoAccentHover = Color(0xFF828FFF)
val OsmoAccentFocus = Color(0xFF5E69D1)
private val OsmoSuccessBase = Color(0xFF27A644)
private val OsmoWarningBase = Color(0xFFD2A45E)

@Immutable
data class OsmoColors(
    val canvas: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkSubtle: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
)

val DarkOsmoColors = OsmoColors(
    canvas = Color(0xFF010102),
    surface1 = Color(0xFF0F1011),
    surface2 = Color(0xFF141516),
    surface3 = Color(0xFF18191A),
    hairline = Color(0xFF23252A),
    hairlineStrong = Color(0xFF34343A),
    ink = Color(0xFFF7F8F8),
    inkMuted = Color(0xFFD0D6E0),
    inkSubtle = Color(0xFF8A8F98),
    accent = OsmoAccentBase,
    success = OsmoSuccessBase,
    warning = OsmoWarningBase,
)

val LightOsmoColors = OsmoColors(
    canvas = Color(0xFFF4F5F7),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF0F2F5),
    surface3 = Color(0xFFE7EBF0),
    hairline = Color(0xFFD7DCE3),
    hairlineStrong = Color(0xFFB7C0CC),
    ink = Color(0xFF13161A),
    inkMuted = Color(0xFF4E5968),
    inkSubtle = Color(0xFF707B8A),
    accent = OsmoAccentBase,
    success = OsmoSuccessBase,
    warning = OsmoWarningBase,
)

internal val LocalOsmoColors = staticCompositionLocalOf { DarkOsmoColors }

object OsmoTheme {
    val colors: OsmoColors
        @Composable
        get() = LocalOsmoColors.current
}

val OsmoCanvas: Color
    @Composable
    get() = OsmoTheme.colors.canvas

val OsmoSurface1: Color
    @Composable
    get() = OsmoTheme.colors.surface1

val OsmoSurface2: Color
    @Composable
    get() = OsmoTheme.colors.surface2

val OsmoSurface3: Color
    @Composable
    get() = OsmoTheme.colors.surface3

val OsmoHairline: Color
    @Composable
    get() = OsmoTheme.colors.hairline

val OsmoHairlineStrong: Color
    @Composable
    get() = OsmoTheme.colors.hairlineStrong

val OsmoInk: Color
    @Composable
    get() = OsmoTheme.colors.ink

val OsmoInkMuted: Color
    @Composable
    get() = OsmoTheme.colors.inkMuted

val OsmoInkSubtle: Color
    @Composable
    get() = OsmoTheme.colors.inkSubtle

val OsmoAccent: Color
    @Composable
    get() = OsmoTheme.colors.accent

val OsmoSuccess: Color
    @Composable
    get() = OsmoTheme.colors.success

val OsmoWarning: Color
    @Composable
    get() = OsmoTheme.colors.warning
