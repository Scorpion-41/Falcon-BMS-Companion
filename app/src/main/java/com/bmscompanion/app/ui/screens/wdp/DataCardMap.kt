package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.wdp.PopupPlan
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.ZoomMath
import com.bmscompanion.app.ui.components.rememberZoomGlide
import com.bmscompanion.app.ui.components.wheelZoom
import com.bmscompanion.app.ui.components.drawMapBase
import com.bmscompanion.app.ui.components.drawAttackModel
import com.bmscompanion.app.ui.components.rememberMapBase

/**
 * A support flight's station on the DataCard's map, as WDP's main map draws it (`fclsMain.DrawMapTankerTracks`,
 * `DrawMapJstarTracks`): a tanker's is the box 30,000 ft either side of its station leg, filled lightly and outlined
 * solid (WDP's is dash-dot), with "callsign/TACAN" and its on-station window at its middle; a JSTARS is a small mark at its middle.
 * [corners] are theater feet (north, east); [label] is two lines or empty.
 */
internal class CardTrack(val corners: List<Pair<Double, Double>>, val centre: Pair<Double, Double>, val label: String, val jstars: Boolean = false)

/**
 * How far the DataCard's map is zoomed and panned. Kept by the wiring, so a redraw after any change on the card keeps
 * the pilot's view; a new mission and **Fit** put it back.
 */
internal class CardMapView {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    val fitted: Boolean get() = zoom == 1f && pan == Offset.Zero
    fun fit() { zoom = 1f; pan = Offset.Zero }
}

/**
 * The DataCard's map (`picMap`): what FillMap puts there — the flight's stretch of the theater, or the chosen attack
 * page's plan — drawn over the app's theater map, or on WDP's white map (`chbWhiteMap`, which a click on the map
 * turns on and off: the page's own click reaches the wiring, [DataCardWiring]) as the app's chart style. Unlike WDP's
 * picture box it can be looked into: a wheel or a pinch zooms about the pointer, a drag pans, and **Fit** (shown once
 * the view has moved) puts the whole picture back. A drag or a pinch is taken here, so it never counts as the click
 * that switches the map.
 */
@Composable
internal fun CardMap(theater: Theater?, picture: PopupPlan.MapPicture?, white: Boolean, tracks: List<CardTrack>, view: CardMapView) {
    val base = rememberMapBase(theater?.map)
    // (its labels are measured on every drawing: the default cache of 8 laid them all out again each time)
    val tm = rememberTextMeasurer(cacheSize = 128)
    // the box's width, for the glide, which zooms between layouts
    val width = remember { FloatArray(1) }
    // a wheel glides (ZoomMath); a pinch or a drag follows the fingers and stops a glide in flight
    val glide = rememberZoomGlide { f, at -> zoomAt(view, view.zoom * f, at, Offset.Zero, width[0]) }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { width[0] = it.width.toFloat() }
                .pointerInput(view) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        glide.stop()
                        zoomAt(view, view.zoom * ZoomMath.pinch(zoom), centroid, pan, size.width.toFloat())
                    }
                }
                // a mouse wheel or a trackpad, which the gesture detector never sees: measured, not a step per event
                .wheelZoom(glide),
        ) {
            drawRect(if (white) Color.White else Color.Black)
            val pic = picture ?: return@Canvas
            val w = size.width
            val z = view.zoom
            val o = view.pan
            // the plan's own pixels (its size square) to the box, zoomed and panned
            val k = w / pic.size
            fun at(px: Float, py: Float) = Offset(px * k * z + o.x, py * k * z + o.y)
            if (theater != null && pic.spanFt > 0.0) {
                val sizeFt = theater.sizeFt
                val side = (w * sizeFt / pic.spanFt).toFloat()
                val left = (-pic.upperE / sizeFt * side).toFloat()
                val top = (-(1.0 - pic.upperN / sizeFt) * side).toFloat()
                drawMapBase(MapProjection(left * z + o.x, top * z + o.y, side * z, sizeFt, 1f), base, null, if (white) "chart" else null)
            }
            val type = (11f / (density * fontScale)).sp
            // theater feet to the plan's pixels
            val kp = if (pic.spanFt > 0.0) pic.size / pic.spanFt else 0.0
            fun ft(north: Double, east: Double) = at(((east - pic.upperE) * kp).toFloat(), ((pic.upperN - north) * kp).toFloat())
            if (kp > 0.0) for (t in tracks) drawTrack(t, ::ft, white, tm, type)
            // an attack page's picture: WDP's route and threats, then the one attack drawing in place of its own marks
            val attack = pic.attack
            val under = if (attack != null && pic.underlay >= 0) pic.underlay else pic.items.size
            for ((idx, it0) in pic.items.withIndex()) {
                if (idx >= under) break
                val it = if (attack != null && idx == pic.ipSquare && it0 is PopupPlan.MapItem.Rect) PopupPlan.MapItem.Ellipse(it0.x, it0.y, it0.w, it0.h, it0.color) else it0
                drawItem(it, ::at, k * z, white, tm, type)
            }
            if (attack != null && kp > 0.0) {
                val inks = com.bmscompanion.app.ui.components.AttackInks.of(white)
                val halo = androidx.compose.ui.graphics.Shadow(inks.halo, Offset.Zero, 3f)
                drawAttackModel(attack, ::ft, inks, (k * z).coerceIn(0.45f, 1.5f)) { text, p, color ->
                    drawText(tm.measure(text, TextStyle(color = color, fontSize = type, shadow = halo)), topLeft = p)
                }
            }
        }
        if (!view.fitted) Text(
            "Fit",
            Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.55f)).clickable { view.fit() }.padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White, fontSize = 11.sp,
        )
    }
}

/** Zoom to [next] (1 to 16 times) about [anchor], after moving by [pan]; the picture is kept over the whole box. Answers the factor applied. */
private fun zoomAt(view: CardMapView, next: Float, anchor: Offset, pan: Offset, w: Float): Float {
    val z = next.coerceIn(1f, 16f)
    val f = z / view.zoom
    val p = Offset(anchor.x - (anchor.x - (view.pan.x + pan.x)) * f, anchor.y - (anchor.y - (view.pan.y + pan.y)) * f)
    // never past the picture's edges: at zoom z the picture is w·z across, so its corner stays between w − w·z and 0
    val lo = w - w * z
    view.zoom = z
    view.pan = Offset(p.x.coerceIn(lo, 0f), p.y.coerceIn(lo, 0f))
    return f
}

private fun DrawScope.drawTrack(
    t: CardTrack, ft: (Double, Double) -> Offset, white: Boolean, tm: androidx.compose.ui.text.TextMeasurer, type: androidx.compose.ui.unit.TextUnit,
) {
    val c = ft(t.centre.first, t.centre.second)
    if (t.jstars) {
        // DrawMapJstarTracks: a magenta mark at the station's middle
        drawRect(Color.Black, Offset(c.x - 3f, c.y - 3f), Size(6f, 6f))
        drawRect(Color(0xFFFF00FF), Offset(c.x - 2f, c.y - 2f), Size(4f, 4f))
        return
    }
    // DrawMapTankerTracks: dark green on the map, blue on the white map, filled at WDP's default transparency
    val ink = if (white) Color(0xFF0000FF) else Color(0xFF006400)
    val path = Path()
    t.corners.forEachIndexed { i, (n, e) -> val p = ft(n, e); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    path.close()
    drawPath(path, ink.copy(alpha = 0.22f))
    // solid, as every map of the app draws a track (WDP outlines it dash-dot)
    drawPath(path, ink, style = Stroke(2f))
    if (t.label.isNotEmpty()) {
        val at = Offset(c.x - 10f, c.y - 10f)
        // dark green letters over the app's green relief (WDP's own map is paler): a pale edge round them keeps them
        // readable, and on the white map it is not seen
        val halo = tm.measure(t.label, TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = type))
        for (d in HALO) drawText(halo, topLeft = at + d)
        drawText(tm.measure(t.label, TextStyle(color = ink, fontSize = type)), topLeft = at)
    }
}

/** the four one-pixel steps a label's pale edge is drawn at */
private val HALO = listOf(Offset(-1f, 0f), Offset(1f, 0f), Offset(0f, -1f), Offset(0f, 1f))

private fun DrawScope.drawItem(
    it: PopupPlan.MapItem, at: (Float, Float) -> Offset, k: Float, white: Boolean,
    tm: androidx.compose.ui.text.TextMeasurer, type: androidx.compose.ui.unit.TextUnit,
) {
    when (it) {
        // WDP's dashed pen (6 on, 6 off) for a leg from the landing to the alternate
        is PopupPlan.MapItem.Line -> drawLine(
            ink(it.color, white), at(it.x1 + 0.5f, it.y1 + 0.5f), at(it.x2 + 0.5f, it.y2 + 0.5f), 1.2f,
            pathEffect = if (it.dash == "Solid") null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
        is PopupPlan.MapItem.Rect -> drawRect(ink(it.color, white), at(it.x + 0.5f, it.y + 0.5f), Size(it.w * k, it.h * k), style = Stroke(1.2f))
        // a red ellipse is a threat's ring (WDP's PPT red): drawn heavier, so it holds on every map style
        is PopupPlan.MapItem.Ellipse -> drawOval(ink(it.color, white), at(it.x + 0.5f, it.y + 0.5f), Size(it.w * k, it.h * k), style = Stroke(if (it.color == "Red") 2.2f else 1.2f))
        is PopupPlan.MapItem.Pie -> drawArc(
            ink(it.color, white), it.start.toFloat(), it.sweep.toFloat(), true, at(it.x + 0.5f, it.y + 0.5f), Size(it.w * k, it.h * k), style = Stroke(1.2f),
        )
        is PopupPlan.MapItem.Polygon -> if (it.points.isNotEmpty()) {
            val path = Path()
            it.points.forEachIndexed { i, p -> val q = at(p.first + 0.5f, p.second + 0.5f); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
            path.close()
            drawPath(path, ink(it.color, white), style = Stroke(1.2f))
        }
        // the type stays its size however far the map is zoomed: it is where the mark is that moves
        is PopupPlan.MapItem.Text -> if (it.text.isNotEmpty()) {
            drawText(tm.measure(it.text, TextStyle(color = ink(it.color, white), fontSize = type)), topLeft = at(it.x.toFloat(), it.y.toFloat()))
        }
    }
}

/** WDP's colour names; on the white map the white marks are drawn black and the yellow ones dark, as WDP's white map does. */
private fun ink(name: String, white: Boolean): Color = when (name) {
    "White" -> if (white) Color.Black else Color.White
    "Black" -> Color.Black
    "Blue" -> Color(0xFF0000FF)
    "Red" -> Color(0xFFFF0000)
    "LightBlue" -> if (white) Color(0xFF1E90FF) else Color(0xFFADD8E6)
    "Magenta" -> Color(0xFFFF00FF)
    "Lime" -> if (white) Color(0xFF008000) else Color(0xFF00FF00)
    "DodgerBlue" -> Color(0xFF1E90FF)
    "Yellow" -> if (white) Color(0xFFB8860B) else Color(0xFFFFFF00)
    else -> if (white) Color.Black else Color.White
}
