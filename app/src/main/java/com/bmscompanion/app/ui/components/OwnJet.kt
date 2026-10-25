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
 * How big your own jet is drawn on every map that shows it (the Mission map and its Dashboard cards, the VR map board,
 * the Taxi page and the live taxi board): a per-device choice, Small / Medium / Large (pref `own_jet_size`, Medium by
 * default). A pilot could not find the old symbol: it was the route's amber and half the size of a steerpoint ring.
 * Now it is white with a black outline and a cased heading line — no other symbol on any map is white — and Medium is
 * about half again as large as it was on a PC and twice on a tablet.
 */
object OwnJetLook {
    private const val KEY = "own_jet_size"
    val labels = listOf("S", "M", "L")
    val names = listOf("Small", "Medium", "Large")

    /** 0 small, 1 medium, 2 large. A VR board sets it from its own option without saving it ([board]). */
    var size by com.bmscompanion.app.data.mission.prefIntState(KEY) { Repo.getInt(KEY, 1).coerceIn(0, 2) }

    fun choose(i: Int) { size = i.coerceIn(0, 2); Repo.putInt(KEY, size) }
    fun cycle() = choose((size + 1) % 3)

    /** A board's own option ("jet" = 0/1/2): applied for that page, the device's choice left as it is on disk. */
    fun board(option: String?) { option?.toIntOrNull()?.let { size = it.coerceIn(0, 2) } }

    /** nose to tail, in dp and at least in px (a PC at 100 % has 1 px a dp, which the dp alone kept too small) */
    private val lenDp = floatArrayOf(26f, 36f, 50f)
    private val lenPx = floatArrayOf(30f, 44f, 60f)

    fun lengthPx(d: DrawScope): Float { val i = size; return max(with(d) { lenDp[i].dp.toPx() }, lenPx[i]) }

    /** the factor a chart's own minimum jet size is multiplied by */
    val chartFactor: Float get() = when (size) { 0 -> 1f; 1 -> 1.5f; else -> 2.1f }

    val Fill = Color.White
    val Edge = Color(0xFF0B0E10)
}

/**
 * Your jet, at [p] heading [hdg] (true degrees, up the screen = north): a white aircraft outlined in black over a soft
 * dark disc, and a heading line [lineLen] px long (white on a black casing). Readable on every style — relief,
 * satellite and dark under it, and on Chart and Topographic the outline and the disc carry it.
 */
fun DrawScope.drawOwnJet(p: Offset, hdg: Double, lineLen: Float, light: Boolean) {
    val len = OwnJetLook.lengthPx(this)
    val u = len / 31f
    val rad = hdg * PI / 180.0
    val tip = p + Offset((sin(rad) * lineLen).toFloat(), (-cos(rad) * lineLen).toFloat())
    drawCircle(OwnJetLook.Edge.copy(alpha = if (light) 0.16f else 0.34f), len * 0.72f, p)
    drawLine(OwnJetLook.Edge, p, tip, 3.2f * u.coerceAtLeast(1f) + 2f, cap = StrokeCap.Round)
    drawLine(OwnJetLook.Fill, p, tip, 1.6f * u.coerceAtLeast(1f) + 0.6f, cap = StrokeCap.Round)
    rotate(hdg.toFloat(), p) {
        val jet = Path().apply {
            fun to(x: Float, y: Float, first: Boolean = false) = if (first) moveTo(p.x + x * u, p.y + y * u) else lineTo(p.x + x * u, p.y + y * u)
            to(0f, -16f, true); to(3f, -4f); to(13f, 4f); to(13f, 7f); to(3f, 5f)
            to(3f, 11f); to(7f, 15f); to(-7f, 15f); to(-3f, 11f); to(-3f, 5f)
            to(-13f, 7f); to(-13f, 4f); to(-3f, -4f); close()
        }
        drawPath(jet, OwnJetLook.Edge, style = Stroke(width = 3.6f * u + 1.5f, join = StrokeJoin.Round))
        drawPath(jet, OwnJetLook.Fill)
    }
}
