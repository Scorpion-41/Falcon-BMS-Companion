package com.bmscompanion.web

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle

/**
 * The Planner's type hinted onto whole pixels ([com.bmscompanion.app.ui.screens.wdp.WdpType]), as the PC's window sets
 * it (desktop `bridge/CrispType.kt`): Skia's full hinting, each glyph on a whole pixel, greyscale. Compose for Web's
 * default places glyphs at quarter pixels with only slight hinting, which smears the Planner's small stems.
 */
@OptIn(ExperimentalTextApi::class)
internal fun crispType(): PlatformTextStyle =
    PlatformTextStyle(null, PlatformParagraphStyle(FontRasterizationSettings(FontSmoothing.AntiAlias, FontHinting.Full, subpixelPositioning = false, autoHintingForced = false)))
