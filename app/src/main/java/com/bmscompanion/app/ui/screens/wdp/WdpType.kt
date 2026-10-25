package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * The face the Planner's WDP pages and windows are set in, and how it is put on the pixel grid.
 *
 * WDP is set in Arial 8.25 pt by GDI: every glyph starts on a whole pixel and Arial's hinting puts each stem on a
 * whole pixel column, so an 11 px figure has one-pixel stems. Drawn in the device's own face with Compose's default
 * rasterisation — on the PC Segoe UI, which has no hinting across, each glyph started at a quarter pixel — every
 * upright of the Planner's 11-20 px type was two grey columns: the pages' small type looked soft beside WDP's
 * (`--wdpsharp <folder> specimen` shows each knob alone, `--wdpsharp <folder> …` the pages before and after).
 *
 * The PC's entry point sets [face] to Windows' Arial and [platform] to full hinting with glyphs on whole pixels, the
 * browser's entry point [platform] alone (it has no Arial); WdpForm.kt's `style`, and the text style every form and
 * panel gives what a wiring draws in it, take both. Android sets neither: its type is hinted and on whole pixels
 * already (TextMotion.Static), on screens of twice the density. Nothing moves: the controls keep their places, and
 * every box measures its text in the same style, so it is still fitted to its box ([fit]); Arial is WDP's own face,
 * so a value that had to be set smaller in the wider Segoe UI now often fits at its own size, as in WDP.
 *
 * The rest of the small type is Falcas's artwork (the DataCard's captions are a JPEG), resampled as [pictures] says.
 */
object WdpType {
    /** the face of WDP's pages and windows; null = the platform's sans-serif */
    var face: FontFamily? = null
    /** the platform's own text style for the Planner's type; null = the platform's default rasterisation */
    var platform: PlatformTextStyle? = null
    /** how a designer picture (the DataCard's printed captions are one) is resampled to the page's scale (WdpForm.kt's
     *  `PICTURE_FILTER`, which says why High); `--wdpsharp` sets Low for its "before" */
    var pictures: FilterQuality = FilterQuality.High
}
