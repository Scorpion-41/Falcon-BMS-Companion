package com.bmscompanion.desktop.bridge

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont

/**
 * The Planner's type on the PC as GDI sets WDP's ([com.bmscompanion.app.ui.screens.wdp.WdpType]).
 *
 * Measured with `--wdpsharp <folder> specimen`: Skia reads a face through DirectWrite in greyscale, and DirectWrite
 * snaps a stem to the pixel columns only where the face's own hinting says so. Segoe UI (Compose's sans-serif on
 * Windows) has none across, so at the Planner's 11-20 px every upright of "D", "i", "n", "d" is two grey columns
 * wherever it falls; Arial, WDP's own face (2,444 of its controls name it), is hinted onto whole columns — one black
 * column a stem — once each glyph starts on a whole pixel. Compose's default starts glyphs at quarter pixels (and
 * hints Normal); the Planner's type turns that off, with full hinting. Greyscale, not subpixel-coloured: Compose's
 * window has no pixel geometry, so Skia would draw it greyscale anyway.
 */
@OptIn(ExperimentalTextApi::class)
fun crispType(kind: String = "full"): PlatformTextStyle {
    // other kinds only for --wdpsharp to compare against: normal, slight or no hinting, or subpixel-coloured (lcd)
    val hinting = when (kind) { "normal" -> FontHinting.Normal; "slight" -> FontHinting.Slight; "none" -> FontHinting.None; else -> FontHinting.Full }
    val smoothing = if (kind == "lcd") FontSmoothing.SubpixelAntiAlias else FontSmoothing.AntiAlias
    return PlatformTextStyle(null, PlatformParagraphStyle(FontRasterizationSettings(smoothing, hinting, subpixelPositioning = false, autoHintingForced = false)))
}

/** Windows' Arial with its own bold and italics (never Compose's made-up bold), or null where this PC has no Arial. */
@OptIn(ExperimentalTextApi::class)
fun wdpFace(name: String = "Arial"): FontFamily? = runCatching {
    if (org.jetbrains.skia.FontMgr.default.matchFamily(name).count() == 0) return null
    FontFamily(
        SystemFont(name, FontWeight.Normal, FontStyle.Normal), SystemFont(name, FontWeight.Bold, FontStyle.Normal),
        SystemFont(name, FontWeight.Normal, FontStyle.Italic), SystemFont(name, FontWeight.Bold, FontStyle.Italic),
    )
}.getOrNull()
