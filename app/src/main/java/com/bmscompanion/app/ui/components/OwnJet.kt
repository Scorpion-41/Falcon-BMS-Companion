package com.bmscompanion.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.dp
import com.bmscompanion.app.data.Repo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * How your own jet is drawn on every map that shows it (the Mission map and its Dashboard cards, the VR map board,
 * the Taxi page and the live taxi board), chosen on each device (and carried to the pilot's others by MissionSync):
 * - **size**, a slider from a small arrow to a large jet ([lenDp], pref `own_jet_len`, nose to tail in dp, [MIN_DP] to
 *   [MAX_DP]; [DEFAULT_DP] is about the pre-1.4.2 symbol's 31 px on a PC — 1.4.2's Small / Medium / Large, pref
 *   `own_jet_size`, were too big to see a formation zoomed out and are no longer read);
 * - **shape** ([shape], `own_jet_shape`): the jet silhouette over a soft dark disc, an arrow (chevron), a dot with its
 *   heading line, or the classic silhouette as before 1.4.2 (no disc, a thinner edge);
 * - **colour** ([color], `own_jet_color`): white (the default; no other map symbol is white), yellow, magenta, cyan,
 *   green, or the route's amber (the classic look) — always with the black outline, which carries it on Chart and
 *   Topographic.
 * The VR map board sets its own from the board's options without saving them ([board]).
 */
object OwnJetLook {
    private const val KEY_LEN = "own_jet_len"
    private const val KEY_SHAPE = "own_jet_shape"
    private const val KEY_COLOR = "own_jet_color"
    const val MIN_DP = 8
    const val MAX_DP = 60
    const val DEFAULT_DP = 18

    /** the three sizes a VR board offers and the chip steps through: Small, Medium (= the default), Large */
    val presets = intArrayOf(12, DEFAULT_DP, 30)
    val labels = listOf("S", "M", "L")
    val names = listOf("Small", "Medium", "Large")

    val shapes = listOf("Jet", "Arrow", "Dot", "Classic")
    const val JET = 0
    const val ARROW = 1
    const val DOT = 2
    const val CLASSIC = 3

    val colorNames = listOf("White", "Yellow", "Magenta", "Cyan", "Green", "Amber")
    val colors = listOf(
        Color.White, Color(0xFFFFE14D), Color(0xFFFF4FD8), Color(0xFF3FE0FF), Color(0xFF5CFF7A), Color(0xFFFFB020),
    )

    var lenDp by com.bmscompanion.app.data.mission.prefIntState(KEY_LEN) { Repo.getInt(KEY_LEN, DEFAULT_DP).coerceIn(MIN_DP, MAX_DP) }
    var shape by com.bmscompanion.app.data.mission.prefIntState(KEY_SHAPE) { Repo.getInt(KEY_SHAPE, JET).coerceIn(0, shapes.size - 1) }
    var color by com.bmscompanion.app.data.mission.prefIntState(KEY_COLOR) { Repo.getInt(KEY_COLOR, 0).coerceIn(0, colors.size - 1) }

    fun chooseLen(dp: Int) { lenDp = dp.coerceIn(MIN_DP, MAX_DP); Repo.putInt(KEY_LEN, lenDp) }
    fun chooseShape(i: Int) { shape = i.coerceIn(0, shapes.size - 1); Repo.putInt(KEY_SHAPE, shape) }
    fun chooseColor(i: Int) { color = i.coerceIn(0, colors.size - 1); Repo.putInt(KEY_COLOR, color) }

    /** the preset nearest the size now (the chip's S / M / L) */
    val preset: Int get() = presets.indices.minByOrNull { kotlin.math.abs(presets[it] - lenDp) } ?: 1
    /** one press steps to the next preset: Small → Medium → Large → Small */
    fun cycle() = chooseLen(presets[(preset + 1) % presets.size])

    /**
     * A board's own options ("jet" = 0/1/2 Small/Medium/Large, or a size in dp from 8 up; "jetshape", "jetcolor"):
     * applied for that page, the device's choice left as it is on disk.
     */
    fun board(size: String?, shapeOption: String? = null, colorOption: String? = null) {
        size?.toIntOrNull()?.let { v -> lenDp = if (v in 0..2) presets[v] else v.coerceIn(MIN_DP, MAX_DP) }
        shapeOption?.toIntOrNull()?.let { shape = it.coerceIn(0, shapes.size - 1) }
        colorOption?.toIntOrNull()?.let { color = it.coerceIn(0, colors.size - 1) }
    }

    /**
     * Nose to tail in px: the dp, and at least 1.5 px a dp (a PC at 100 % has 1 px a dp, where the dp alone drew the
     * symbol smaller than on any other device).
     */
    fun lengthPx(d: DrawScope): Float { val dp = lenDp.toFloat(); return max(with(d) { dp.dp.toPx() }, dp * 1.5f) }

    /** the factor a chart's own minimum jet size is multiplied by (1 at the default size) */
    val chartFactor: Float get() = (lenDp / DEFAULT_DP.toFloat()).coerceIn(0.6f, 2.4f)

    val Fill: Color get() = colors.getOrElse(color) { Color.White }
    val Edge = Color(0xFF0B0E10)
}

/**
 * Your jet, at [p] heading [hdg] (true degrees, up the screen = north), in the device's shape, colour and size
 * ([OwnJetLook]), with a heading line [lineLen] px long (the colour on a black casing). The black outline keeps every
 * colour readable on every style — relief, satellite and dark under it, and on Chart and Topographic.
 */
fun DrawScope.drawOwnJet(p: Offset, hdg: Double, lineLen: Float, light: Boolean) {
    val len = OwnJetLook.lengthPx(this)
    val u = len / 31f
    val fill = OwnJetLook.Fill
    val edge = OwnJetLook.Edge
    val shape = OwnJetLook.shape
    val rad = hdg * PI / 180.0
    val tip = p + Offset((sin(rad) * lineLen).toFloat(), (-cos(rad) * lineLen).toFloat())
    val lw = u.coerceIn(0.5f, 1.6f)
    if (shape == OwnJetLook.JET) drawCircle(edge.copy(alpha = if (light) 0.16f else 0.34f), len * 0.66f, p)
    drawLine(edge, p, tip, 3.2f * lw + 1.6f, cap = StrokeCap.Round)
    drawLine(fill, p, tip, 1.6f * lw + 0.5f, cap = StrokeCap.Round)
    when (shape) {
        OwnJetLook.DOT -> {
            drawCircle(edge, len * 0.3f + 1.5f, p)
            drawCircle(fill, len * 0.3f, p)
        }
        else -> rotate(hdg.toFloat(), p) {
            val path = Path().apply {
                fun to(x: Float, y: Float, first: Boolean = false) = if (first) moveTo(p.x + x * u, p.y + y * u) else lineTo(p.x + x * u, p.y + y * u)
                if (shape == OwnJetLook.ARROW) {
                    // a chevron: the nose ahead of the centre, the two wings swept back, notched at the tail
                    to(0f, -15f, true); to(11f, 13f); to(0f, 6f); to(-11f, 13f); close()
                } else {
                    to(0f, -16f, true); to(3f, -4f); to(13f, 4f); to(13f, 7f); to(3f, 5f)
                    to(3f, 11f); to(7f, 15f); to(-7f, 15f); to(-3f, 11f); to(-3f, 5f)
                    to(-13f, 7f); to(-13f, 4f); to(-3f, -4f); close()
                }
            }
            // the classic symbol's thin edge as before 1.4.2; the others a heavier one
            val stroke = if (shape == OwnJetLook.CLASSIC) 2.4f * u + 1f else 3.2f * u + 1.4f
            drawPath(path, edge, style = Stroke(width = stroke, join = StrokeJoin.Round))
            drawPath(path, fill)
        }
    }
}
