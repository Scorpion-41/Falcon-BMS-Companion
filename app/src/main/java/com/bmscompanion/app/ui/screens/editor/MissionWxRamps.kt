package com.bmscompanion.app.ui.screens.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.MissionWxMap
import com.bmscompanion.app.data.weather.WxCover
import com.bmscompanion.app.data.weather.WxType
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.roundToInt

/*
 * The Mission weather page's colour scales (1.4.1), after Weather Commander and Windy: every fill is one ramp over a
 * value, drawn a pixel a cell and smoothed between the cell centres by the image filter when **Smooth** is on (the
 * ramps change gently from cell to cell, so blending the colours is blending the values), with a labelled scale bar
 * in the legend. Only one fill at a time; the station circles, barbs, isobars and figures are overlays on top.
 */

/** A colour ramp over a value: stops in increasing value, colours (alpha included) blended between them. */
internal class WxRamp(val stops: List<Pair<Float, Color>>) {
    fun at(v: Float): Color {
        if (v.isNaN()) return Color.Transparent
        if (v <= stops.first().first) return stops.first().second
        if (v >= stops.last().first) return stops.last().second
        val i = stops.indexOfLast { it.first <= v }.coerceIn(0, stops.size - 2)
        val (a, ca) = stops[i]
        val (b, cb) = stops[i + 1]
        val f = if (b == a) 0f else (v - a) / (b - a)
        return Color(
            ca.red + (cb.red - ca.red) * f, ca.green + (cb.green - ca.green) * f, ca.blue + (cb.blue - ca.blue) * f,
            ca.alpha + (cb.alpha - ca.alpha) * f,
        )
    }

    /** The bar's brush over [from]..[to], opaque enough to read on the legend's dark ground. */
    fun brush(from: Float, to: Float): Brush = Brush.horizontalGradient(
        (0..24).map { k -> at(from + (to - from) * k / 24f).let { c -> c.copy(alpha = (c.alpha * 1.45f).coerceIn(0.18f, 1f)) } },
    )
}

internal object WxRamps {
    /** Sky cover as a fraction: none 0, FEW ¼, SCT ½, BKN ¾, OVC 1. */
    fun coverFraction(code: Int): Float = when (WxCover.ofCode(code)) {
        WxCover.NONE -> 0f
        WxCover.FEW -> 0.25f
        WxCover.SCATTERED -> 0.5f
        WxCover.BROKEN -> 0.75f
        WxCover.OVERCAST -> 1f
    }

    /** Clear (nothing) → few (a pale veil) → scattered → broken → overcast (a dark slate): luminance carries it. */
    val CLOUD = WxRamp(
        listOf(
            0f to Color(0x00E8EEF4),
            0.25f to Color(0xFFE3E9F0).copy(alpha = 0.34f),
            0.5f to Color(0xFFAEB8C5).copy(alpha = 0.52f),
            0.75f to Color(0xFF7A8594).copy(alpha = 0.66f),
            1f to Color(0xFF454E5B).copy(alpha = 0.80f),
        ),
    )

    /** Rain like a radar's light rain, showers a distinct teal (hatched on the map), snow white-lilac (outlined). */
    val RAIN = Color(0xFF2FBF4A)
    val SHOWER = Color(0xFF13BFC9)
    val SNOW = Color(0xFFEDE6FF)
    val SNOW_EDGE = Color(0xFFA78BFA)
    fun rainInk() = RAIN.copy(alpha = 0.55f)
    fun showerInk() = SHOWER.copy(alpha = 0.42f)
    fun snowInk() = SNOW.copy(alpha = 0.72f)

    /** Low pressure blue, standard pale, high orange-red, over the map's own range (see [pressureRange]). */
    val PRESSURE = WxRamp(
        listOf(
            0f to Color(0xFF2B59C3).copy(alpha = 0.58f),
            0.25f to Color(0xFF5AA0E6).copy(alpha = 0.48f),
            0.5f to Color(0xFFE6ECF0).copy(alpha = 0.30f),
            0.75f to Color(0xFFF5A04A).copy(alpha = 0.48f),
            1f to Color(0xFFD7301F).copy(alpha = 0.58f),
        ),
    )

    /** The pressure fill's range: the map's own, at least 12 mb wide about its middle (a flat map reads pale). */
    fun pressureRange(m: MissionWxMap): Pair<Float, Float> {
        val lo = m.pressureMb.minOrNull() ?: 1013f
        val hi = m.pressureMb.maxOrNull() ?: 1013f
        val mid = (lo + hi) / 2f
        val half = maxOf((hi - lo) / 2f, 6f)
        return (mid - half) to (mid + half)
    }

    /** Wind speed, knots, a fixed scale as Windy's: calm faint, then blue, green, yellow, orange, red, magenta, violet. */
    val WIND = WxRamp(
        listOf(
            0f to Color(0xFF6A8CAF).copy(alpha = 0.08f),
            10f to Color(0xFF4FA3E0).copy(alpha = 0.40f),
            20f to Color(0xFF3CC47C).copy(alpha = 0.48f),
            35f to Color(0xFFE8D33F).copy(alpha = 0.52f),
            50f to Color(0xFFF08A24).copy(alpha = 0.56f),
            70f to Color(0xFFE0342C).copy(alpha = 0.60f),
            100f to Color(0xFFB0278F).copy(alpha = 0.62f),
            140f to Color(0xFF5E1B8C).copy(alpha = 0.66f),
        ),
    )

    /** Temperature at the surface, °C, a fixed scale. */
    val TEMPERATURE = WxRamp(
        listOf(
            -30f to Color(0xFF6A3D9A).copy(alpha = 0.55f),
            -15f to Color(0xFF3F6FD8).copy(alpha = 0.52f),
            0f to Color(0xFF5EC4E8).copy(alpha = 0.50f),
            10f to Color(0xFF6CCB72).copy(alpha = 0.48f),
            20f to Color(0xFFF2D24B).copy(alpha = 0.50f),
            30f to Color(0xFFF08A3C).copy(alpha = 0.54f),
            40f to Color(0xFFD7301F).copy(alpha = 0.58f),
        ),
    )

    /** Visibility, km: red under a mile, orange, amber, yellow to 8 km, green fading out past 20. */
    val VISIBILITY = WxRamp(
        listOf(
            0f to Color(0xFFD32F2F).copy(alpha = 0.66f),
            1.6f to Color(0xFFFF5722).copy(alpha = 0.60f),
            5f to Color(0xFFFFA726).copy(alpha = 0.52f),
            8f to Color(0xFFFFEB3B).copy(alpha = 0.42f),
            15f to Color(0xFF9CCC65).copy(alpha = 0.26f),
            30f to Color(0xFF8BC34A).copy(alpha = 0.06f),
        ),
    )

    /** BMS rains only in inclement weather (snow at or below freezing); a version 8 map's shower flag adds showers. */
    fun precipKind(m: MissionWxMap, i: Int): Int = when {
        m.type[i] == WxType.INCLEMENT.code -> if (m.tempC[i] <= 0f) 3 else 2
        m.shower.getOrNull(i) == 1 -> 1
        else -> 0
    }

    fun precipInk(m: MissionWxMap, i: Int): Color = when (precipKind(m, i)) {
        3 -> snowInk()
        2 -> rainInk()
        1 -> showerInk()
        else -> Color.Transparent
    }

    /** The colour of cell [i] of [m] for [fill] at wind [level]. */
    fun ink(m: MissionWxMap, i: Int, fill: WxFill, level: Int, pressure: Pair<Float, Float>): Color = when (fill) {
        WxFill.CLOUDS -> CLOUD.at(coverFraction(m.cover[i]))
        WxFill.PRECIP -> precipInk(m, i)
        WxFill.VISIBILITY -> VISIBILITY.at(m.visKm[i])
        WxFill.TEMPERATURE -> TEMPERATURE.at(m.tempC[i])
        WxFill.PRESSURE -> PRESSURE.at((m.pressureMb[i] - pressure.first) / (pressure.second - pressure.first))
        WxFill.WIND -> WIND.at(m.windKt.getOrNull(i * MissionWxMap.LEVELS + level)?.toFloat() ?: 0f)
        WxFill.NONE -> Color.Transparent
    }
}

/**
 * Showers hatched and snow outlined, over the precipitation fill: the hatch is one set of diagonal lines across the
 * screen, clipped to each shower cell in view, so it runs on unbroken from cell to cell; the snow's edge is drawn
 * between a snow cell and each neighbour that is not.
 */
internal fun DrawScope.precipMarks(m: MissionWxMap, left: Float, top: Float, cw: Float, ch: Float, c0: Int, c1: Int, r0: Int, r1: Int, spacing: Float) {
    val hatch = WxRamps.SHOWER.copy(alpha = 0.85f)
    val edge = WxRamps.SNOW_EDGE
    fun kind(c: Int, r: Int): Int = if (c < 0 || r < 0 || c >= m.cols || r >= m.rows) 0 else WxRamps.precipKind(m, r * m.cols + c)
    for (r in r0..r1) for (c in c0..c1) {
        val k = kind(c, r)
        if (k == 0) continue
        val x0 = left + c * cw
        val y0 = top + r * ch
        if (k == 1) {
            clipRect(x0, y0, x0 + cw, y0 + ch) {
                // lines x + y = n · spacing that cross this cell
                val first = kotlin.math.floor((x0 + y0) / spacing).toInt()
                val last = kotlin.math.ceil((x0 + cw + y0 + ch) / spacing).toInt()
                for (n in first..last) {
                    val s = n * spacing
                    drawLine(hatch, Offset(s - y0, y0), Offset(s - y0 - ch, y0 + ch), 1.3f)
                }
            }
        } else if (k == 3) {
            if (kind(c, r - 1) != 3) drawLine(edge, Offset(x0, y0), Offset(x0 + cw, y0), 1.6f)
            if (kind(c, r + 1) != 3) drawLine(edge, Offset(x0, y0 + ch), Offset(x0 + cw, y0 + ch), 1.6f)
            if (kind(c - 1, r) != 3) drawLine(edge, Offset(x0, y0), Offset(x0, y0 + ch), 1.6f)
            if (kind(c + 1, r) != 3) drawLine(edge, Offset(x0 + cw, y0), Offset(x0 + cw, y0 + ch), 1.6f)
        }
    }
}

/** A labelled scale bar: the ramp over [from]..[to] and [labels] spread evenly under it (the first at [from], the last at [to]). */
@Composable
internal fun ScaleBar(ramp: WxRamp, from: Float, to: Float, labels: List<String>, width: Dp = 210.dp) {
    Column(Modifier.width(width)) {
        Box(
            Modifier.width(width).height(9.dp).clip(RoundedCornerShape(2.dp)).background(Hud.Surface)
                .background(ramp.brush(from, to)).border(0.5.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
        )
        Row(Modifier.width(width), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach { Text(it, color = Hud.TextDim, fontSize = 9.5.sp, maxLines = 1, lineHeight = 12.sp) }
        }
    }
}

/** The cloud legend's four station circles, each with its name: the key to the circles drawn on the map. */
@Composable
internal fun CoverKey() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(WxCover.FEW to "FEW", WxCover.SCATTERED to "SCT", WxCover.BROKEN to "BKN", WxCover.OVERCAST to "OVC").forEach { (c, name) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // the fill's colour behind the circle the map draws for it
                Canvas(Modifier.size(18.dp).clip(RoundedCornerShape(2.dp))) {
                    drawRect(WxRamps.CLOUD.at(WxRamps.coverFraction(c.code)).let { it.copy(alpha = (it.alpha * 1.3f).coerceAtMost(1f)) })
                    coverGlyph(Offset(size.width / 2, size.height / 2), c, false, size.minDimension * 0.3f, Color.White, Color.Black)
                }
                Spacer(Modifier.width(3.dp))
                Text(name, color = Hud.Text, fontSize = 10.5.sp, maxLines = 1)
            }
        }
    }
}

/** Swatches with names (precipitation's three kinds); [hatched] draws the shower hatch, [outlined] the snow edge. */
@Composable
internal fun PrecipKey(showers: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        PrecipSwatch("Rain", WxRamps.rainInk())
        if (showers) PrecipSwatch("Showers", WxRamps.showerInk(), hatched = true)
        PrecipSwatch("Snow", WxRamps.snowInk(), outlined = true)
    }
}

@Composable
private fun PrecipSwatch(name: String, c: Color, hatched: Boolean = false, outlined: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(14.dp).clip(RoundedCornerShape(1.dp))) {
            drawRect(c.copy(alpha = (c.alpha * 1.3f).coerceAtMost(1f)))
            if (hatched) precipHatch(this)
            drawRect(if (outlined) WxRamps.SNOW_EDGE else Hud.Outline, Offset.Zero, Size(size.width, size.height), style = Stroke(if (outlined) 2f else 1f))
        }
        Spacer(Modifier.width(4.dp))
        Text(name, color = Hud.Text, fontSize = 10.5.sp, maxLines = 1)
    }
}

private fun precipHatch(d: DrawScope) = with(d) {
    val s = size.width / 3f
    for (n in -3..6) drawLine(WxRamps.SHOWER.copy(alpha = 0.95f), Offset(n * s, 0f), Offset(n * s - size.height, size.height), 1.2f)
}

/** The pressure bar's three figures: its two ends and the middle, whole millibars. */
internal fun pressureLabels(lo: Float, hi: Float): List<String> =
    listOf(lo.roundToInt().toString(), ((lo + hi) / 2).roundToInt().toString(), "${hi.roundToInt()} mb")
