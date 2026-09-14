package com.bmscompanion.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Hud {
    val Bg = Color(0xFF0A0F14)
    val Surface = Color(0xFF111820)
    val Surface2 = Color(0xFF16202A)
    val Surface3 = Color(0xFF1D2935)
    val Outline = Color(0xFF2A3947)
    val Green = Color(0xFF5BE38A)
    val Amber = Color(0xFFFFB547)
    val Cyan = Color(0xFF56C8F5)
    val Red = Color(0xFFFF5A5F)
    val Blue = Color(0xFF4F8DFF)
    val Magenta = Color(0xFFE07BFF)
    val Text = Color(0xFFE6EDF3)
    val TextDim = Color(0xFF93A4B4)
    val TextFaint = Color(0xFF5E7082)
}

private val scheme = darkColorScheme(
    primary = Hud.Amber,
    onPrimary = Color(0xFF231500),
    primaryContainer = Color(0xFF3A2A0E),
    onPrimaryContainer = Color(0xFFFFDDAA),
    secondary = Hud.Green,
    onSecondary = Color(0xFF00210C),
    secondaryContainer = Color(0xFF123524),
    onSecondaryContainer = Color(0xFFB9F6CD),
    tertiary = Hud.Cyan,
    onTertiary = Color(0xFF002233),
    tertiaryContainer = Color(0xFF0E3345),
    onTertiaryContainer = Color(0xFFBDE9FF),
    error = Hud.Red,
    background = Hud.Bg,
    onBackground = Hud.Text,
    surface = Hud.Surface,
    onSurface = Hud.Text,
    surfaceVariant = Hud.Surface2,
    onSurfaceVariant = Hud.TextDim,
    surfaceContainerLowest = Hud.Bg,
    surfaceContainerLow = Hud.Surface,
    surfaceContainer = Hud.Surface2,
    surfaceContainerHigh = Hud.Surface3,
    surfaceContainerHighest = Color(0xFF243240),
    outline = Hud.Outline,
    outlineVariant = Color(0xFF223040),
    inverseSurface = Hud.Text,
    inverseOnSurface = Hud.Bg,
    scrim = Color(0xCC000000),
)

val Mono = FontFamily.Monospace

private val base = Typography()
private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
)

@Immutable
data class ExtraStyles(val mono: TextStyle, val monoSmall: TextStyle, val overline: TextStyle)

val LocalExtra = staticCompositionLocalOf {
    ExtraStyles(
        mono = TextStyle(fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium),
        monoSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp),
        overline = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp),
    )
}

@Composable
fun BmsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
