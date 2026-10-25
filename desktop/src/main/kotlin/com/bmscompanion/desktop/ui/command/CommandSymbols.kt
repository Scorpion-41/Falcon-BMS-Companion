package com.bmscompanion.desktop.ui.command

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.bmscompanion.app.ui.components.UnitInks
import com.bmscompanion.app.ui.components.drawUnitSymbol
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// The Command map's own symbols (the app's look, not BMS's icons): every objective type has its own silhouette — the
// shape tells the kind from afar, a glyph inside tells it close up — filled in its owner's team ink and outlined; every
// air role has its own aircraft (a flight is the bare aircraft turned to its heading, a squadron the same aircraft on a
// plate); ground units keep the app's NATO-like frames (UnitSymbols) with an echelon mark over them (II battalion,
// X brigade, XX division). The legend in the layers panel draws with the same functions.

/** The glyph ink on a fill of [fill]. */
internal fun glyphOn(fill: Color): Color = UnitInks.on(fill.copy(alpha = 1f)).copy(alpha = fill.alpha)

/**
 * Draws objective type [type]'s symbol on [c] at size [s] (about half its width, in px) in [fill], outlined in [edge];
 * [radar] marks a site with a radar. Answers its pick radius.
 */
internal fun DrawScope.drawObjectiveSymbol(type: Int, radar: Boolean, c: Offset, s: Float, fill: Color, edge: Color): Float {
    val g = glyphOn(fill)
    val gw = max(1f, s * 0.26f)
    val ow = max(1.4f, s * 0.38f)
    val detail = s >= 3.2f
    fun outlinePath(p: Path) { drawPath(p, edge, style = Stroke(ow * 2f, join = StrokeJoin.Round)); drawPath(p, fill) }
    fun circle(r: Float, at: Offset = c) { drawCircle(edge, r + ow, at); drawCircle(fill, r, at) }
    fun rect(w: Float, h: Float, at: Offset = c) {
        drawRect(edge, Offset(at.x - w - ow, at.y - h - ow), Size(2 * w + 2 * ow, 2 * h + 2 * ow))
        drawRect(fill, Offset(at.x - w, at.y - h), Size(2 * w, 2 * h))
    }
    fun line(a: Offset, b: Offset, ink: Color = g, w: Float = gw) = drawLine(ink, a, b, w, StrokeCap.Round)
    fun poly(vararg xy: Float): Path = Path().apply { moveTo(c.x + xy[0] * s, c.y + xy[1] * s); for (i in 2 until xy.size step 2) lineTo(c.x + xy[i] * s, c.y + xy[i + 1] * s); close() }
    fun at(dx: Float, dy: Float) = Offset(c.x + dx * s, c.y + dy * s)
    fun hexagon(r: Float): Path = Path().apply {
        for (i in 0 until 6) { val a = PI / 3 * i; val p = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat()); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }
    when (type) {
        1 -> { // airbase: a disc crossed by its runway
            circle(s * 1.5f)
            line(at(-1.05f, 0.75f), at(1.05f, -0.75f), w = max(1.4f, s * 0.42f))
            return s * 1.6f
        }
        2 -> { // airstrip, highway strip: a capsule with its centre line
            val p = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(c.x - s * 1.6f, c.y - s * 0.65f, c.x + s * 1.6f, c.y + s * 0.65f), androidx.compose.ui.geometry.CornerRadius(s * 0.65f))) }
            outlinePath(p)
            if (detail) line(at(-1.0f, 0f), at(1.0f, 0f))
            return s * 1.5f
        }
        3 -> { // army base: a square with a flag
            rect(s, s)
            if (detail) { line(at(-0.45f, 0.65f), at(-0.45f, -0.7f)); drawPath(poly(-0.45f, -0.7f, 0.6f, -0.4f, -0.45f, -0.1f), g) }
            return s * 1.1f
        }
        13 -> { // fortification: a square with battlements
            val p = poly(-1f, 1f, -1f, -1.25f, -0.55f, -1.25f, -0.55f, -0.75f, -0.2f, -0.75f, -0.2f, -1.25f, 0.2f, -1.25f,
                0.2f, -0.75f, 0.55f, -0.75f, 0.55f, -1.25f, 1f, -1.25f, 1f, 1f)
            outlinePath(p)
            return s * 1.2f
        }
        30 -> { // HARTS: a square with an H
            rect(s, s)
            if (detail) { line(at(-0.5f, -0.55f), at(-0.5f, 0.55f)); line(at(0.5f, -0.55f), at(0.5f, 0.55f)); line(at(-0.5f, 0f), at(0.5f, 0f)) }
            return s * 1.1f
        }
        31 -> { // SAM site: a triangle with a missile rising in it
            outlinePath(poly(0f, -1.45f, 1.35f, 1f, -1.35f, 1f))
            if (detail) { line(at(0f, -0.55f), at(0f, 0.7f)); line(at(-0.25f, 0.7f), at(0.25f, 0.7f)) }
            if (radar) drawCircle(edge, max(1.2f, s * 0.22f), at(0.75f, -0.9f))
            return s * 1.45f
        }
        21 -> { // radar: a dish on a mast
            val dish = Path().apply {
                moveTo(c.x - s * 1.35f, c.y - s * 0.55f)
                arcTo(Rect(c.x - s * 1.35f, c.y - s * 1.85f, c.x + s * 1.35f, c.y + s * 0.75f), 180f, -180f, false)
                close()
            }
            outlinePath(dish)
            line(at(0f, 0.7f), at(0f, 1.25f), edge, max(1.6f, s * 0.5f)); line(at(-0.6f, 1.25f), at(0.6f, 1.25f), edge, max(1.6f, s * 0.5f))
            if (detail) line(at(0f, -0.55f), at(0.55f, -1.2f))
            return s * 1.35f
        }
        11 -> { // factory: a saw-tooth roof and a chimney
            outlinePath(poly(-1.25f, 1f, -1.25f, -0.2f, -0.55f, -0.75f, -0.55f, -0.2f, 0.15f, -0.75f, 0.15f, -0.2f,
                0.6f, -0.2f, 0.6f, -1.35f, 1.05f, -1.35f, 1.05f, -0.2f, 1.25f, -0.2f, 1.25f, 1f))
            return s * 1.3f
        }
        7 -> { // chemical plant: a flask
            outlinePath(poly(-0.35f, -1.35f, 0.35f, -1.35f, 0.35f, -0.4f, 1.25f, 1.05f, -1.25f, 1.05f, -0.35f, -0.4f))
            if (detail) line(at(-0.7f, 0.45f), at(0.7f, 0.45f))
            return s * 1.3f
        }
        25 -> { // refinery: a drop of oil
            val p = Path().apply {
                moveTo(c.x, c.y - s * 1.5f)
                cubicTo(c.x + s * 0.3f, c.y - s * 0.9f, c.x + s * 1.15f, c.y - s * 0.1f, c.x + s * 1.1f, c.y + s * 0.4f)
                cubicTo(c.x + s * 1.05f, c.y + s * 1.25f, c.x - s * 1.05f, c.y + s * 1.25f, c.x - s * 1.1f, c.y + s * 0.4f)
                cubicTo(c.x - s * 1.15f, c.y - s * 0.1f, c.x - s * 0.3f, c.y - s * 0.9f, c.x, c.y - s * 1.5f)
                close()
            }
            outlinePath(p)
            return s * 1.3f
        }
        20 -> { // power plant: a hexagon with a lightning bolt
            outlinePath(hexagon(s * 1.35f))
            if (detail) drawPath(poly(0.2f, -1f, -0.5f, 0.15f, 0f, 0.15f, -0.2f, 1f, 0.5f, -0.15f, 0f, -0.15f), g)
            return s * 1.35f
        }
        17 -> { // nuclear plant: a hexagon with the trefoil
            outlinePath(hexagon(s * 1.35f))
            if (detail) {
                for (i in 0 until 3) {
                    val a = -PI / 2 + i * 2 * PI / 3
                    drawCircle(g, s * 0.3f, Offset(c.x + (s * 0.6f * cos(a)).toFloat(), c.y + (s * 0.6f * sin(a)).toFloat()))
                }
                drawCircle(g, s * 0.16f, c)
            }
            return s * 1.35f
        }
        8 -> { // city: a large disc with a ring in it
            circle(s * 1.3f)
            if (detail) drawCircle(g, s * 0.6f, c, style = Stroke(gw))
            return s * 1.3f
        }
        28 -> { circle(s * 0.95f); return s * 0.95f } // town
        29 -> { circle(s * 0.65f); return s * 0.7f } // village
        10 -> { // depot: a warehouse (a house)
            outlinePath(poly(-1.2f, 1f, -1.2f, -0.3f, 0f, -1.3f, 1.2f, -0.3f, 1.2f, 1f))
            if (detail) line(at(-1.2f, 0.35f), at(1.2f, 0.35f))
            return s * 1.25f
        }
        23 -> { // rail terminal: a long box with a track in it
            rect(s * 1.45f, s * 0.8f)
            if (detail) { line(at(-1.2f, 0f), at(1.2f, 0f)); for (x in listOf(-0.8f, -0.25f, 0.3f, 0.85f)) line(at(x, -0.4f), at(x, 0.4f), w = gw * 0.8f) }
            return s * 1.4f
        }
        9 -> { // headquarters, communications centre: a square with a cross and its staff
            rect(s, s)
            line(at(-1f, 1f), at(-1f, 2.1f), edge, max(1.6f, s * 0.45f))
            if (detail) { line(at(-0.6f, -0.6f), at(0.6f, 0.6f)); line(at(-0.6f, 0.6f), at(0.6f, -0.6f)) }
            return s * 1.2f
        }
        22 -> { // radio tower: a tall mast with its waves
            outlinePath(poly(0f, -1.6f, 0.55f, 1.2f, -0.55f, 1.2f))
            if (detail) {
                drawArc(edge, -60f, 120f, false, Offset(c.x - s * 0.4f, c.y - s * 2.0f), Size(s * 1.8f, s * 1.0f), style = Stroke(max(1f, s * 0.22f)))
                drawArc(edge, 120f, 120f, false, Offset(c.x - s * 1.4f, c.y - s * 2.0f), Size(s * 1.8f, s * 1.0f), style = Stroke(max(1f, s * 0.22f)))
            }
            return s * 1.4f
        }
        6 -> { // bridge: a deck with its wings
            rect(s * 1.0f, s * 0.45f)
            val w = max(1.3f, s * 0.32f)
            for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) line(at(sx * 1.0f, sy * 0.55f), at(sx * 1.45f, sy * 1.05f), edge, w)
            return s * 1.2f
        }
        12 -> { // ford: a wave over the road
            val w = max(1.6f, s * 0.42f)
            val p = Path().apply { moveTo(c.x - s * 1.2f, c.y); cubicTo(c.x - s * 0.6f, c.y - s * 0.9f, c.x, c.y + s * 0.9f, c.x + s * 0.6f, c.y); quadraticTo(c.x + s * 0.9f, c.y - s * 0.45f, c.x + s * 1.2f, c.y) }
            drawPath(p, edge, style = Stroke(w + ow * 1.6f, cap = StrokeCap.Round)); drawPath(p, fill, style = Stroke(w, cap = StrokeCap.Round))
            return s * 1.2f
        }
        18 -> { // pass: two slopes facing each other
            val w = max(1.6f, s * 0.42f)
            for (sx in listOf(-1f, 1f)) {
                val p = Path().apply { moveTo(c.x + sx * s * 1.2f, c.y - s); quadraticTo(c.x + sx * s * 0.25f, c.y, c.x + sx * s * 1.2f, c.y + s) }
                drawPath(p, edge, style = Stroke(w + ow * 1.6f, cap = StrokeCap.Round)); drawPath(p, fill, style = Stroke(w, cap = StrokeCap.Round))
            }
            return s * 1.2f
        }
        15, 26, 24 -> { // road junction, road, railroad: a small diamond (a railroad's crossed by its track)
            outlinePath(poly(0f, -0.95f, 0.95f, 0f, 0f, 0.95f, -0.95f, 0f))
            if (type == 24 && detail) line(at(-0.5f, 0f), at(0.5f, 0f))
            if (type == 26 && detail) drawCircle(g, s * 0.2f, c)
            return s
        }
        19 -> { // port: an anchor on a rounded plate
            val p = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(c.x - s * 1.2f, c.y - s * 1.2f, c.x + s * 1.2f, c.y + s * 1.2f), androidx.compose.ui.geometry.CornerRadius(s * 0.45f))) }
            outlinePath(p)
            if (detail) {
                line(at(0f, -0.75f), at(0f, 0.7f)); line(at(-0.4f, -0.4f), at(0.4f, -0.4f))
                drawArc(g, 20f, 140f, false, Offset(c.x - s * 0.7f, c.y - s * 0.05f), Size(s * 1.4f, s * 0.85f), style = Stroke(gw, cap = StrokeCap.Round))
            }
            return s * 1.25f
        }
        4 -> { // beach: a dome over the water line
            val p = Path().apply { moveTo(c.x - s * 1.3f, c.y + s * 0.35f); arcTo(Rect(c.x - s * 1.3f, c.y - s * 0.95f, c.x + s * 1.3f, c.y + s * 1.65f), 180f, 180f, false); close() }
            outlinePath(p)
            line(at(-1.3f, 0.95f), at(1.3f, 0.95f), edge, max(1.4f, s * 0.35f))
            return s * 1.3f
        }
        27 -> { // sea: a ring
            drawCircle(edge, s * 1.1f, c, style = Stroke(max(3f, s * 0.75f)))
            drawCircle(fill, s * 1.1f, c, style = Stroke(max(1.6f, s * 0.4f)))
            return s * 1.2f
        }
        14 -> { // hill top: an open peak
            val p = Path().apply { moveTo(c.x - s * 1.2f, c.y + s * 0.8f); lineTo(c.x, c.y - s * 1f); lineTo(c.x + s * 1.2f, c.y + s * 0.8f) }
            drawPath(p, edge, style = Stroke(max(3f, s * 0.8f), join = StrokeJoin.Round, cap = StrokeCap.Round))
            drawPath(p, fill, style = Stroke(max(1.6f, s * 0.4f), join = StrokeJoin.Round, cap = StrokeCap.Round))
            return s * 1.1f
        }
        5 -> { // border: a small cross
            for ((a, b) in listOf(at(-0.8f, -0.8f) to at(0.8f, 0.8f), at(-0.8f, 0.8f) to at(0.8f, -0.8f))) { line(a, b, edge, max(3f, s * 0.8f)); line(a, b, fill, max(1.6f, s * 0.4f)) }
            return s
        }
        16 -> { circle(s * 0.45f); return s * 0.6f } // lowlands
        else -> { // a navigation beacon and anything else: a small open hexagon
            drawPath(hexagon(s * 0.95f), edge, style = Stroke(max(3f, s * 0.75f)))
            drawPath(hexagon(s * 0.95f), fill, style = Stroke(max(1.5f, s * 0.38f)))
            return s
        }
    }
}

/** The aircraft each air role is drawn as (nose up; [s] about half its length). */
private fun aircraftOutline(role: AirRole, s: Float): List<Pair<Float, Float>> = when (role) {
    // (forward, right) pairs of the right half, nose first; mirrored for the left
    AirRole.FIGHTER, AirRole.OTHER -> listOf(1f to 0f, -0.1f to 0.95f, -0.45f to 0.2f, -1f to 0.45f, -0.85f to 0f)
    AirRole.ATTACK -> listOf(1f to 0f, 0.65f to 0.12f, 0.2f to 0.12f, 0.15f to 1f, -0.12f to 1f, -0.1f to 0.15f, -0.7f to 0.15f, -0.75f to 0.55f, -1f to 0.55f, -1f to 0f)
    AirRole.BOMBER -> listOf(1f to 0f, 0.5f to 0.12f, -0.35f to 1.15f, -0.55f to 1.15f, -0.35f to 0.15f, -0.85f to 0.15f, -1f to 0.45f, -1.05f to 0f)
    AirRole.SUPPORT, AirRole.TANKER, AirRole.TRANSPORT -> listOf(1f to 0f, 0.8f to 0.14f, 0.2f to 0.14f, -0.15f to 1.05f, -0.35f to 1.05f, -0.2f to 0.14f, -0.75f to 0.14f, -0.95f to 0.5f, -1.05f to 0.5f, -1f to 0f)
    AirRole.RECON -> listOf(1f to 0f, 0.75f to 0.1f, 0.15f to 0.1f, 0.1f to 1.25f, -0.05f to 1.25f, -0.1f to 0.1f, -0.8f to 0.1f, -0.95f to 0.4f, -1f to 0f)
    AirRole.HELI -> listOf(0.55f to 0f, 0.4f to 0.3f, -0.1f to 0.3f, -0.25f to 0.1f, -1.1f to 0.08f, -1.15f to 0f)
}

/**
 * Draws an aircraft of [role] on [c], nose along [headingDeg], [s] about half its length, in [fill] outlined in [edge]
 * (or, [ink] given, in that ink alone: the aircraft on a squadron's plate). Answers its pick radius.
 */
internal fun DrawScope.drawAircraft(role: AirRole, c: Offset, headingDeg: Double, s: Float, fill: Color, edge: Color, ink: Color? = null): Float {
    val a = Math.toRadians(headingDeg)
    val k = if (role == AirRole.BOMBER) 1.15f else 1f
    fun pt(fwd: Float, right: Float) = Offset(c.x + ((fwd * sin(a) + right * cos(a)) * s * k).toFloat(), c.y - ((fwd * cos(a) - right * sin(a)) * s * k).toFloat())
    val half = aircraftOutline(role, s)
    val body = Path().apply {
        val all = half + half.reversed().drop(1).map { (f, r) -> f to -r }
        all.forEachIndexed { i, (f, r) -> val p = pt(f, r); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }
    val bodyInk = ink ?: fill
    if (ink == null) drawPath(body, edge, style = Stroke(max(2.4f, s * 0.42f), join = StrokeJoin.Round))
    drawPath(body, bodyInk)
    val line = ink ?: edge
    when (role) {
        AirRole.SUPPORT -> { // the radar disc over the fuselage
            val d = pt(-0.2f, 0f)
            drawCircle(line, s * 0.42f * k + if (ink == null) 1.2f else 0f, d); drawCircle(if (ink == null) fill else glyphOn(ink), s * 0.3f * k, d)
        }
        AirRole.TANKER -> { // the boom trailing behind
            drawLine(line, pt(-1f, 0f), pt(-1.65f, 0f), max(1.4f, s * 0.2f), StrokeCap.Round)
            drawCircle(line, max(1.4f, s * 0.2f), pt(-1.7f, 0f))
        }
        AirRole.HELI -> { // the rotor
            drawCircle(line, s * 0.95f * k, pt(0.1f, 0f), style = Stroke(max(1.2f, s * 0.16f)))
            drawLine(line, pt(-1.15f, -0.3f), pt(-1.15f, 0.3f), max(1.2f, s * 0.16f))
        }
        else -> Unit
    }
    return s * k
}

/** A squadron: its role's aircraft, nose up, on a rounded plate in [fill]. Answers its pick radius. */
internal fun DrawScope.drawSquadronSymbol(role: AirRole, c: Offset, s: Float, fill: Color, edge: Color): Float {
    val h = s * 1.25f
    val r = androidx.compose.ui.geometry.RoundRect(Rect(c.x - h, c.y - h, c.x + h, c.y + h), androidx.compose.ui.geometry.CornerRadius(s * 0.35f))
    val ow = max(1.3f, s * 0.22f)
    drawPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(c.x - h - ow, c.y - h - ow, c.x + h + ow, c.y + h + ow), androidx.compose.ui.geometry.CornerRadius(s * 0.35f + ow))) }, edge)
    drawPath(Path().apply { addRoundRect(r) }, fill)
    drawAircraft(role, c, 0.0, s * 0.82f, fill, edge, ink = glyphOn(fill))
    return h
}

/** NATO's echelon marks over a unit's frame, centred on [top]: 2 = II (battalion), 3 = X (brigade), 4 = XX (division). */
internal fun DrawScope.drawEchelon(level: Int, top: Offset, s: Float, ink: Color, edge: Color) {
    val h = max(3f, s * 0.32f)
    val w = max(1.2f, s * 0.09f)
    fun mark(x: Float, cross: Boolean) {
        if (cross) {
            for ((a, b) in listOf(Offset(x - h * 0.45f, top.y - h) to Offset(x + h * 0.45f, top.y), Offset(x - h * 0.45f, top.y) to Offset(x + h * 0.45f, top.y - h))) {
                drawLine(edge, a, b, w + 2f, StrokeCap.Round); drawLine(ink, a, b, w, StrokeCap.Round)
            }
        } else { drawLine(edge, Offset(x, top.y - h), Offset(x, top.y), w + 2f, StrokeCap.Round); drawLine(ink, Offset(x, top.y - h), Offset(x, top.y), w, StrokeCap.Round) }
    }
    when (level) {
        2 -> { mark(top.x - h * 0.3f, false); mark(top.x + h * 0.3f, false) }
        3 -> mark(top.x, true)
        else -> { mark(top.x - h * 0.5f, true); mark(top.x + h * 0.5f, true) }
    }
}

/** A ground unit at a distance: a small mark of its side's frame (a square for a friend, a diamond for a foe…) in its team's [fill]. */
internal fun DrawScope.drawUnitDot(side: String, c: Offset, r: Float, fill: Color, edge: Color) {
    when (side) {
        "hostile" -> {
            val p = Path().apply { moveTo(c.x, c.y - r * 1.3f); lineTo(c.x + r * 1.3f, c.y); lineTo(c.x, c.y + r * 1.3f); lineTo(c.x - r * 1.3f, c.y); close() }
            drawPath(p, edge, style = Stroke(2.2f)); drawPath(p, fill)
        }
        "friendly" -> { drawRect(edge, c - Offset(r * 1.25f + 1f, r * 0.85f + 1f), Size(r * 2.5f + 2f, r * 1.7f + 2f)); drawRect(fill, c - Offset(r * 1.25f, r * 0.85f), Size(r * 2.5f, r * 1.7f)) }
        else -> { drawRect(edge, c - Offset(r + 1f, r + 1f), Size(2 * r + 2f, 2 * r + 2f)); drawRect(fill, c - Offset(r, r), Size(2 * r, 2 * r)) }
    }
}

// ------------------------------------------------------------------ the legend (the layers panel)

/** One picture of the legend: what it is ([tip], under the mouse) and how it is drawn in its little square. */
internal class Legend(val tip: String, val draw: DrawScope.() -> Unit)

/**
 * The legend's pictures for the open file ([doc]): with [bms] BMS's own icon of an example of that kind in the file
 * (in its owner's team colour), else — or where the file has no example or BMS no icon — the app's symbol, in a
 * neutral grey (the shape is the legend; the map's colour is the team's).
 */
internal class LegendSource(private val doc: CmdDocument?, bms: Boolean) {
    private val set = doc?.takeIf { bms }?.let { d -> runCatching { com.bmscompanion.desktop.bridge.command.CampMapIcons.forTheater(d.set, d.theater) }.getOrNull() }
    private val cache = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()
    private val world get() = doc?.world
    private fun colourOf(owner: Int) = world?.team(owner)?.colour ?: owner

    private fun bmsPicture(tip: String, key: String, load: (com.bmscompanion.desktop.bridge.command.CampMapIcons.IconSet) -> com.bmscompanion.desktop.bridge.command.CampMapIcons.Icon?): Legend? {
        val s = set ?: return null
        val (img, _) = runCatching { iconFor(cache, key) { load(s) } }.getOrNull() ?: return null
        return Legend(tip) {
            val sc = (size.minDimension / max(img.width, img.height)).coerceAtMost(1f)
            drawIcon(img, Offset(img.width / 2f, img.height / 2f), center, sc)
        }
    }

    fun objective(type: Int): Legend {
        val ex = world?.objectives?.firstOrNull { it.type == type }
        val tip = ex?.typeName?.takeIf { it.isNotBlank() } ?: OBJECTIVE_TYPE_NAMES[type] ?: "Objective"
        ex?.let { o -> bmsPicture(tip, "o${o.ocd}|${colourOf(o.owner)}") { it.objective(o.ocd, colourOf(o.owner)) } }?.let { return it }
        return Legend(tip) { drawObjectiveSymbol(type, false, center, size.minDimension / 3.3f, LegendFill, LegendEdge) }
    }

    fun land(kind: String, tip: String, unitKind: CmdKind = CmdKind.BATTALION): Legend {
        val ex = world?.units?.firstOrNull { it.kind == unitKind && (unitKind != CmdKind.BATTALION || it.symbol == kind) }
        ex?.let { u -> bmsPicture(tip, "u${u.ucd}|${colourOf(u.owner)}") { it.unit(u.ucd, colourOf(u.owner), air = false) } }?.let { return it }
        return Legend(tip) {
            val px = size.height * 0.5f
            val c = Offset(center.x, center.y + size.height * 0.12f)
            drawUnitSymbol(kind, "friendly", c, px)
            if (unitKind != CmdKind.TASKFORCE) drawEchelon(if (unitKind == CmdKind.BRIGADE) 3 else 2, Offset(c.x, c.y - px * 0.5f - 1f), px, Color(0xFFE8ECF0), LegendEdge)
        }
    }

    fun division(): Legend = Legend("Division (made of its brigades and battalions)") {
        val s = size.height * 0.36f
        val c = Offset(center.x, center.y + size.height * 0.12f)
        drawRect(LegendEdge, c - Offset(s * 1.1f, s * 0.75f), Size(s * 2.2f, s * 1.5f))
        drawRect(LegendFill, c - Offset(s * 0.95f, s * 0.6f), Size(s * 1.9f, s * 1.2f))
        drawEchelon(4, Offset(c.x, c.y - s * 0.75f - 1f), size.height * 0.5f, Color(0xFFE8ECF0), LegendEdge)
    }

    fun squadron(role: AirRole): Legend {
        val ex = world?.units?.firstOrNull { it.kind == CmdKind.SQUADRON && it.role == role }
        val tip = "Squadron (${role.label.lowercase()})"
        ex?.let { u -> bmsPicture(tip, "a${u.ucd}|${colourOf(u.owner)}") { it.unit(u.ucd, colourOf(u.owner), air = true) } }?.let { return it }
        return Legend(tip) { drawSquadronSymbol(role, center, size.minDimension / 2.9f, LegendFill, LegendEdge) }
    }

    fun flight(role: AirRole): Legend {
        val ex = world?.units?.firstOrNull { it.kind == CmdKind.FLIGHT && it.role == role }
        val tip = "Flight: ${role.label}"
        ex?.let { u -> bmsPicture(tip, "f${u.ucd}|${colourOf(u.owner)}|nn") { it.unit(u.ucd, colourOf(u.owner), air = true, heading = "nn") } }?.let { return it }
        return Legend(tip) { drawAircraft(role, center, 0.0, size.minDimension / 2.6f, LegendFill, LegendEdge) }
    }

    companion object {
        /** a line (links, parents) in [ink], dashed or not */
        fun line(tip: String, ink: Color, dashed: Boolean) = Legend(tip) {
            val a = Offset(size.width * 0.1f, size.height * 0.75f)
            val b = Offset(size.width * 0.9f, size.height * 0.25f)
            drawLine(LegendEdge, a, b, 3f)
            drawLine(ink, a, b, 1.6f, pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 3f)) else null)
        }
        /** an air-defence ring */
        fun ring(tip: String) = Legend(tip) {
            drawCircle(Color(0xFFE03A30).copy(alpha = 0.12f), size.minDimension * 0.42f, center)
            drawCircle(Color(0xFFE03A30), size.minDimension * 0.42f, center, style = Stroke(1.4f))
        }
    }
}

private val LegendFill = Color(0xFFC6CED7)
private val LegendEdge = Color(0xE0101418)

/** The legend's pictures in a row, each 16 dp, its name under the mouse. */
@androidx.compose.runtime.Composable
internal fun LegendIcons(items: List<Legend>) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        items.forEach { l ->
            Tip(l.tip) {
                androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.padding(start = 2.dp).size(16.dp)) { runCatching { l.draw(this) } }
            }
        }
    }
}

/** A tick box of the layers panel with the legend of what it draws at its right. */
@androidx.compose.runtime.Composable
internal fun LegendCheck(label: String, checked: Boolean, legend: List<Legend>, indent: Boolean = false, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
            // a long name is cut by the legend beside it: the whole name under the mouse
            if (label.length > 22) Tip(label) { CheckLine(label, checked, indent = indent, onChange = onChange) }
            else CheckLine(label, checked, indent = indent, onChange = onChange)
        }
        LegendIcons(legend)
    }
}

/** English names of the objective types (BMS's own, Strings 500 + type, are used when the file has one of that type). */
internal val OBJECTIVE_TYPE_NAMES: Map<Int, String> = mapOf(
    0 to "Navigation beacon", 1 to "Airbase", 2 to "Airstrip", 3 to "Army base", 4 to "Beach", 5 to "Border", 6 to "Bridge",
    7 to "Chemical plant", 8 to "City", 9 to "Communications centre", 10 to "Depot", 11 to "Factory", 12 to "Ford",
    13 to "Fortification", 14 to "Hill top", 15 to "Junction", 16 to "Lowlands", 17 to "Nuclear plant", 18 to "Pass",
    19 to "Port", 20 to "Power plant", 21 to "Radar", 22 to "Radio tower", 23 to "Rail terminal", 24 to "Railroad",
    25 to "Refinery", 26 to "Road", 27 to "Sea", 28 to "Town", 29 to "Village", 30 to "HARTS", 31 to "SAM site",
)

/** Objective types by group, the order the legend lists them. */
internal val OBJECTIVE_TYPES_SHOWN: Map<ObjGroup, List<Int>> = mapOf(
    ObjGroup.AIRPORTS to listOf(1, 2),
    ObjGroup.ARMY to listOf(3, 13, 30),
    ObjGroup.AIR_DEFENCE to listOf(31, 21),
    ObjGroup.INDUSTRY to listOf(11, 7, 25),
    ObjGroup.POWER to listOf(20, 17),
    ObjGroup.CITIES to listOf(8, 28, 29),
    ObjGroup.LOGISTICS to listOf(10, 23),
    ObjGroup.COMMAND to listOf(9, 22),
    ObjGroup.INFRASTRUCTURE to listOf(6, 15, 24, 18, 12),
    ObjGroup.NAVAL to listOf(19, 4, 27),
    ObjGroup.GEO to listOf(14, 5, 16, 0),
)
