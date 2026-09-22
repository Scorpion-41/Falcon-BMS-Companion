package com.bmscompanion.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One set of colours, whatever the app is being drawn on.
 *
 * The app itself is a dark HUD, which is right on a phone in a dark room and beside a monitor. A kneeboard is not a
 * screen you look at, it is a page you glance down at with a helmet on, so it gets ink on paper instead: no saturated
 * colour to catch the eye, no glow, and enough contrast to read at an angle. [Paper] is for daylight, [PaperNight] for
 * a night mission, where a white page in the cockpit would blind you.
 */
@Immutable
data class Skin(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val outline: Color,
    val green: Color,
    val amber: Color,
    val cyan: Color,
    val red: Color,
    val blue: Color,
    val magenta: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val light: Boolean,
)

private val HudDark = Skin(
    bg = Color(0xFF0A0F14), surface = Color(0xFF111820), surface2 = Color(0xFF16202A), surface3 = Color(0xFF1D2935),
    outline = Color(0xFF2A3947),
    green = Color(0xFF5BE38A), amber = Color(0xFFFFB547), cyan = Color(0xFF56C8F5),
    red = Color(0xFFFF5A5F), blue = Color(0xFF4F8DFF), magenta = Color(0xFFE07BFF),
    text = Color(0xFFE6EDF3), textDim = Color(0xFF93A4B4), textFaint = Color(0xFF5E7082),
    light = false,
)

/**
 * Daylight: an old sheet of paper with the page printed on it.
 *
 * Aged stock rather than white — warm, slightly brown, the colour of a chart that has been in a flight suit pocket —
 * and one family of inks over it: sepia, olive, slate, brick, all mixed down so that nothing on the board is brighter
 * than anything else. A kneeboard is read at a glance in a lit cockpit, and a colour that jumps is a colour that
 * pulls the eye off the instruments.
 */
private val Paper = Skin(
    bg = Color(0xFFE5DAC0), surface = Color(0xFFEDE3CD), surface2 = Color(0xFFDCD0B2), surface3 = Color(0xFFCFC2A1),
    outline = Color(0xFFB0A183),
    green = Color(0xFF4A6146), amber = Color(0xFF7A5A24), cyan = Color(0xFF3E5A68),
    red = Color(0xFF8A3A2E), blue = Color(0xFF3B5470), magenta = Color(0xFF6A4A62),
    text = Color(0xFF2A2318), textDim = Color(0xFF5E5442), textFaint = Color(0xFF8C8168),
    light = true,
)

/** Night: the same sheet under a dimmed floodlight. Nothing bright enough to leave an after-image. */
private val PaperNight = Skin(
    bg = Color(0xFF15120B), surface = Color(0xFF1D1A12), surface2 = Color(0xFF262218), surface3 = Color(0xFF30291E),
    outline = Color(0xFF46402F),
    green = Color(0xFF7A9578), amber = Color(0xFFA8823F), cyan = Color(0xFF6E8C9C),
    red = Color(0xFFA96A5F), blue = Color(0xFF7286A3), magenta = Color(0xFF98849C),
    text = Color(0xFFD3CBB6), textDim = Color(0xFF948B79), textFaint = Color(0xFF6A6252),
    light = false,
)

/**
 * Inks for a map drawn on a light ground — the chart style. The screen palette is made for a dark background and
 * disappears on white: pale cyan contacts, a white label with a black blur behind it that reads as a smudge. These
 * are the same hues taken down to where they hold against paper-white terrain.
 */
private val HudOnLight = Skin(
    bg = Color(0xFFF3F1EA), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFE8E5DC), surface3 = Color(0xFFD9D5CA),
    outline = Color(0xFF8A8678),
    green = Color(0xFF1B7A33), amber = Color(0xFFA85600), cyan = Color(0xFF0B5FA8),
    red = Color(0xFFBE1B1B), blue = Color(0xFF123FA8), magenta = Color(0xFF7D25A8),
    text = Color(0xFF12151A), textDim = Color(0xFF3E444E), textFaint = Color(0xFF69707A),
    light = true,
)

/** Which skin is in use. Only kneeboard mode changes it; the app and the website are always the HUD. */
object HudSkin {
    var board by mutableStateOf(false)
    var night by mutableStateOf(false)

    /**
     * True for the length of one map drawing pass ([inMapInks]), and true with it when that map is a light one.
     * Deliberately not Compose state: both are set and cleared inside a single draw, so nothing observes them and
     * nothing recomposes because of them.
     */
    var mapPass = false
    var mapLight = false

    val current: Skin get() = when {
        mapPass -> if (mapLight) HudOnLight else HudDark
        !board -> HudDark
        night -> PaperNight
        else -> Paper
    }
}

/**
 * Draws [block] in the screen's inks, even on a kneeboard.
 *
 * A board's map is a picture of the ground, not a sheet of paper. The paper inks — sepia, slate, brick, mixed down on
 * purpose so that nothing on a page jumps — sank into the relief: route labels, friendlies, hostiles and your own jet
 * could hardly be read in the headset, while the tanker and AWACS tracks, which have fixed bright colours, read fine.
 * So the map's symbols and labels are drawn as the app draws them; the page around the map keeps its paper.
 */
inline fun <T> inMapInks(onLightMap: Boolean, block: () -> T): T {
    val wasPass = HudSkin.mapPass
    val wasLight = HudSkin.mapLight
    HudSkin.mapPass = true
    HudSkin.mapLight = onLightMap
    try {
        return block()
    } finally {
        HudSkin.mapPass = wasPass
        HudSkin.mapLight = wasLight
    }
}

/**
 * The app's colours. Every screen reads these, so switching skin re-draws the lot; nothing may copy one into a
 * top-level `val`, which would freeze it on whatever the skin was at class-load.
 */
object Hud {
    val Bg: Color get() = HudSkin.current.bg
    val Surface: Color get() = HudSkin.current.surface
    val Surface2: Color get() = HudSkin.current.surface2
    val Surface3: Color get() = HudSkin.current.surface3
    val Outline: Color get() = HudSkin.current.outline
    val Green: Color get() = HudSkin.current.green
    val Amber: Color get() = HudSkin.current.amber
    val Cyan: Color get() = HudSkin.current.cyan
    val Red: Color get() = HudSkin.current.red
    val Blue: Color get() = HudSkin.current.blue
    val Magenta: Color get() = HudSkin.current.magenta
    val Text: Color get() = HudSkin.current.text
    val TextDim: Color get() = HudSkin.current.textDim
    val TextFaint: Color get() = HudSkin.current.textFaint

    /** True while the app is drawing on paper rather than on a screen. */
    val onPaper: Boolean get() = HudSkin.board

    /** On paper and drawing in paper inks — false inside a map, which is drawn in inks that suit the ground. */
    val paperInks: Boolean get() = HudSkin.board && !HudSkin.mapPass

    /** Drawing on a map whose ground is light (the chart style): symbols and labels go dark, haloed in white. */
    val onLightMap: Boolean get() = HudSkin.mapPass && HudSkin.mapLight
}

private fun schemeFor(s: Skin) = if (s.light) {
    lightColorScheme(
        primary = s.amber, onPrimary = Color(0xFFFFFFFF), primaryContainer = s.surface3, onPrimaryContainer = s.text,
        secondary = s.green, onSecondary = Color(0xFFFFFFFF), secondaryContainer = s.surface3, onSecondaryContainer = s.text,
        tertiary = s.cyan, onTertiary = Color(0xFFFFFFFF), tertiaryContainer = s.surface3, onTertiaryContainer = s.text,
        error = s.red,
        background = s.bg, onBackground = s.text,
        surface = s.surface, onSurface = s.text,
        surfaceVariant = s.surface2, onSurfaceVariant = s.textDim,
        surfaceContainerLowest = s.bg, surfaceContainerLow = s.surface, surfaceContainer = s.surface2,
        surfaceContainerHigh = s.surface3, surfaceContainerHighest = s.surface3,
        outline = s.outline, outlineVariant = s.outline,
        inverseSurface = s.text, inverseOnSurface = s.bg,
        scrim = Color(0x66000000),
    )
} else {
    darkColorScheme(
        primary = s.amber, onPrimary = Color(0xFF231500), primaryContainer = s.surface3, onPrimaryContainer = s.text,
        secondary = s.green, onSecondary = Color(0xFF00210C), secondaryContainer = s.surface3, onSecondaryContainer = s.text,
        tertiary = s.cyan, onTertiary = Color(0xFF002233), tertiaryContainer = s.surface3, onTertiaryContainer = s.text,
        error = s.red,
        background = s.bg, onBackground = s.text,
        surface = s.surface, onSurface = s.text,
        surfaceVariant = s.surface2, onSurfaceVariant = s.textDim,
        surfaceContainerLowest = s.bg, surfaceContainerLow = s.surface, surfaceContainer = s.surface2,
        surfaceContainerHigh = s.surface3, surfaceContainerHighest = s.surface3,
        outline = s.outline, outlineVariant = s.outline,
        inverseSurface = s.text, inverseOnSurface = s.bg,
        scrim = Color(0xCC000000),
    )
}

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

private val defaultExtra = ExtraStyles(
    mono = TextStyle(fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium),
    monoSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp),
    overline = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp),
)

val LocalExtra = staticCompositionLocalOf { defaultExtra }

/** The board's own typography: a serif face, the way a printed chart is set. Everywhere else keeps the HUD's sans. */
private val paperTypography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.Serif),
    bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.Serif),
    bodySmall = base.bodySmall.copy(fontFamily = FontFamily.Serif),
    labelLarge = base.labelLarge.copy(fontFamily = FontFamily.Serif),
    labelMedium = base.labelMedium.copy(fontFamily = FontFamily.Serif),
    labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Serif, letterSpacing = 0.8.sp),
)

/** The headings on the board are set in the same serif; the figures stay monospaced, which is what they are for. */
private val paperExtra = ExtraStyles(
    mono = TextStyle(fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium),
    monoSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp),
    overline = TextStyle(fontFamily = FontFamily.Serif, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
)

@Composable
fun BmsTheme(content: @Composable () -> Unit) {
    val skin = HudSkin.current
    val scheme = remember(skin) { schemeFor(skin) }
    val board = HudSkin.board
    CompositionLocalProvider(LocalExtra provides if (board) paperExtra else defaultExtra) {
        MaterialTheme(colorScheme = scheme, typography = if (board) paperTypography else typography, content = content)
    }
}
