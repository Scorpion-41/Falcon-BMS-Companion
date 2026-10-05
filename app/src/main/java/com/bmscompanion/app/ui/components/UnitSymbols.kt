package com.bmscompanion.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.max

// Ground units and ships on a map, drawn as symbols in the spirit of NATO's APP-6 (and of the unit symbols WDP wrote
// and never called: DrawSquadron, DrawArmor, DrawAirDefence…). Plain Compose drawing, no platform API, so the Android
// app, the PC window and the browser draw the same thing. The kinds and sides are the PC's (`CampMapUnit.kind`,
// `CampMapUnit.side`): a land unit is a frame whose shape says the side — friendly a rectangle, hostile a diamond,
// neutral a square, unknown a quatrefoil — with its arm drawn inside; a ship is a small hull seen from above.

/** The inks a side is drawn in, from the app's skin (so a kneeboard's paper and a light chart map get their own). */
object UnitInks {
    val friendly: Color get() = Hud.Blue
    val hostile: Color get() = Hud.Red
    val neutral: Color get() = Hud.Green
    val unknown: Color get() = Hud.Amber

    /** The ink of [side] ("friendly", "hostile", "neutral", anything else unknown). */
    fun of(side: String): Color = when (side) {
        "friendly" -> friendly
        "hostile" -> hostile
        "neutral" -> neutral
        else -> unknown
    }

    /** What is drawn on a fill of [fill]: near-black on a light one, near-white on a dark one. */
    fun on(fill: Color): Color = if (fill.luminance() > 0.35f) Color(0xFF11161C) else Color(0xFFF2F5F8)
}

/** A kind as a card names it ("Air defence", "Destroyer"); [kind] is a `CampMapUnit.kind`. */
fun unitKindLabel(kind: String): String = when (kind) {
    "airdefence" -> "Air defence"
    "armor" -> "Armour"
    "cavalry" -> "Cavalry"
    "infantry" -> "Infantry"
    "mechanized" -> "Mechanised infantry"
    "marine" -> "Marines"
    "airmobile" -> "Airmobile infantry"
    "engineer" -> "Engineers"
    "artillery" -> "Self-propelled artillery"
    "towed" -> "Towed artillery"
    "rocket" -> "Rocket artillery"
    "missile" -> "Surface-to-surface missiles"
    "supply" -> "Supply"
    "hq" -> "Headquarters"
    "recon" -> "Reconnaissance"
    "carrier" -> "Aircraft carrier"
    "cruiser" -> "Cruiser"
    "destroyer" -> "Destroyer"
    "frigate" -> "Frigate"
    "patrol" -> "Patrol boat"
    "tanker" -> "Tanker"
    "ship" -> "Ship"
    else -> "Ground unit"
}

/** The sea kinds, drawn as hulls rather than frames. */
val SEA_UNIT_KINDS: Set<String> = setOf("carrier", "cruiser", "destroyer", "frigate", "patrol", "tanker", "ship")

/**
 * Draws a unit's symbol centred on [at]. [sizePx] is the frame's height (a ship's hull is about one and a half times
 * as long). [moving] adds a small arrow at the top right, pointing along [heading] (degrees true, 0 = up) when it is
 * known and to the right otherwise; [dim] draws it faded (not seen, or destroyed).
 */
fun DrawScope.drawUnitSymbol(
    kind: String, side: String, at: Offset, sizePx: Float, moving: Boolean = false, dim: Boolean = false, heading: Float? = null,
) {
    val alpha = if (dim) 0.45f else 1f
    val fill = UnitInks.of(side).copy(alpha = alpha)
    val ink = UnitInks.on(UnitInks.of(side)).copy(alpha = alpha)
    val w = max(1f, sizePx * 0.08f)
    if (kind in SEA_UNIT_KINDS) {
        drawHull(kind, at, sizePx, fill, ink, w)
        if (moving) drawMoveArrow(Offset(at.x + sizePx * 0.45f, at.y - sizePx * 0.7f), sizePx, ink, fill, w, heading)
        return
    }
    val box = drawFrame(side, at, sizePx, fill, ink, w)
    drawArm(kind, box, ink, w)
    if (kind == "hq") {
        // a headquarters' staff: down from the frame's lower left, in the side's ink since it stands on the map
        val foot = staffFoot(side, at, sizePx)
        drawLine(fill, foot, Offset(foot.x, at.y + sizePx * 1.15f), w * 1.3f, StrokeCap.Round)
    }
    if (moving) drawMoveArrow(Offset(at.x + sizePx * 0.75f, at.y - sizePx * 0.62f), sizePx, ink, fill, w, heading)
}

/** Where a headquarters' staff leaves the frame: its lower left corner, or its left point. */
private fun staffFoot(side: String, at: Offset, s: Float): Offset = when (side) {
    "friendly" -> Offset(at.x - s * 0.75f, at.y + s * 0.5f)
    "hostile" -> Offset(at.x - s * 0.72f, at.y)
    "neutral" -> Offset(at.x - s * 0.5f, at.y + s * 0.5f)
    else -> Offset(at.x - s * 0.6f, at.y)
}

/** The frame of [side], filled and outlined; answers the box the arm is drawn in. */
private fun DrawScope.drawFrame(side: String, at: Offset, s: Float, fill: Color, ink: Color, w: Float): Rect {
    val edge = Stroke(width = w, join = StrokeJoin.Round)
    val outline = UnitInks.on(fill.copy(alpha = 1f)).copy(alpha = fill.alpha)
    when (side) {
        "friendly" -> {
            val r = Rect(at.x - s * 0.75f, at.y - s * 0.5f, at.x + s * 0.75f, at.y + s * 0.5f)
            drawRect(fill, r.topLeft, r.size, style = Fill)
            drawRect(outline, r.topLeft, r.size, style = edge)
            return r
        }
        "hostile" -> {
            val d = s * 0.72f
            val p = Path().apply {
                moveTo(at.x, at.y - d); lineTo(at.x + d, at.y); lineTo(at.x, at.y + d); lineTo(at.x - d, at.y); close()
            }
            drawPath(p, fill, style = Fill)
            drawPath(p, outline, style = edge)
            return Rect(at.x - d * 0.5f, at.y - d * 0.5f, at.x + d * 0.5f, at.y + d * 0.5f)
        }
        "neutral" -> {
            val r = Rect(at.x - s * 0.5f, at.y - s * 0.5f, at.x + s * 0.5f, at.y + s * 0.5f)
            drawRect(fill, r.topLeft, r.size, style = Fill)
            drawRect(outline, r.topLeft, r.size, style = edge)
            return r
        }
        else -> {
            // a quatrefoil: four overlapping circles round a square
            val q = s * 0.3f
            val p = Path()
            for ((dx, dy) in listOf(0f to -q, q to 0f, 0f to q, -q to 0f)) {
                p.addOval(Rect(Offset(at.x + dx, at.y + dy), q))
            }
            p.addRect(Rect(at.x - q, at.y - q, at.x + q, at.y + q))
            drawPath(p, fill, style = Fill)
            for ((dx, dy) in listOf(0f to -q, q to 0f, 0f to q, -q to 0f)) {
                drawCircle(outline, q, Offset(at.x + dx, at.y + dy), style = edge)
            }
            drawRect(fill, Offset(at.x - q * 0.95f, at.y - q * 0.95f), Size(q * 1.9f, q * 1.9f), style = Fill)
            return Rect(at.x - q, at.y - q, at.x + q, at.y + q)
        }
    }
}

/** The unit's arm inside [b]. */
private fun DrawScope.drawArm(kind: String, b: Rect, ink: Color, w: Float) {
    val c = b.center
    val line = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun cross() {
        drawLine(ink, b.topLeft, b.bottomRight, w, StrokeCap.Round)
        drawLine(ink, Offset(b.left, b.bottom), Offset(b.right, b.top), w, StrokeCap.Round)
    }
    fun track() {
        val rw = b.width * 0.62f
        val rh = b.height * 0.42f
        drawOval(ink, Offset(c.x - rw / 2, c.y - rh / 2), Size(rw, rh), style = line)
    }
    fun dot(r: Float = b.height * 0.13f, at: Offset = c) = drawCircle(ink, r, at)
    when (kind) {
        "armor" -> track()
        "infantry" -> cross()
        "mechanized" -> { cross(); track() }
        "cavalry", "recon" -> drawLine(ink, Offset(b.left, b.bottom), Offset(b.right, b.top), w, StrokeCap.Round)
        "airmobile" -> {
            cross()
            // the wings: two shallow arcs meeting under the cross
            val aw = b.width * 0.26f
            val ah = b.height * 0.22f
            drawArc(ink, 200f, 140f, false, Offset(c.x - aw * 2, b.bottom - ah * 1.4f), Size(aw * 2, ah * 2), style = line)
            drawArc(ink, 200f, 140f, false, Offset(c.x, b.bottom - ah * 1.4f), Size(aw * 2, ah * 2), style = line)
        }
        "engineer" -> {
            // a bar with three legs
            val y0 = c.y - b.height * 0.14f
            val y1 = c.y + b.height * 0.18f
            val x0 = c.x - b.width * 0.3f
            val x1 = c.x + b.width * 0.3f
            drawLine(ink, Offset(x0, y0), Offset(x1, y0), w, StrokeCap.Round)
            for (x in listOf(x0, c.x, x1)) drawLine(ink, Offset(x, y0), Offset(x, y1), w, StrokeCap.Round)
        }
        "artillery" -> dot()
        "towed" -> {
            dot(at = Offset(c.x, c.y - b.height * 0.08f))
            val wr = b.height * 0.08f
            drawCircle(ink, wr, Offset(c.x - b.width * 0.2f, b.bottom - wr * 1.8f), style = line)
            drawCircle(ink, wr, Offset(c.x + b.width * 0.2f, b.bottom - wr * 1.8f), style = line)
        }
        "rocket" -> {
            dot(at = Offset(c.x - b.width * 0.12f, c.y + b.height * 0.1f))
            // the launch rails, rising to the right
            for (k in listOf(-1f, 1f)) {
                val o = k * b.height * 0.09f
                drawLine(ink, Offset(c.x - b.width * 0.02f, c.y + o), Offset(c.x + b.width * 0.3f, c.y - b.height * 0.28f + o), w, StrokeCap.Round)
            }
        }
        "missile" -> {
            // an upward missile
            val top = Offset(c.x, b.top + b.height * 0.12f)
            val bottom = Offset(c.x, b.bottom - b.height * 0.12f)
            drawLine(ink, top, bottom, w, StrokeCap.Round)
            val hw = b.width * 0.12f
            drawLine(ink, top, Offset(c.x - hw, top.y + b.height * 0.2f), w, StrokeCap.Round)
            drawLine(ink, top, Offset(c.x + hw, top.y + b.height * 0.2f), w, StrokeCap.Round)
            drawLine(ink, Offset(c.x - hw, bottom.y), Offset(c.x + hw, bottom.y), w, StrokeCap.Round)
        }
        "supply" -> {
            val y = b.bottom - b.height * 0.28f
            drawLine(ink, Offset(b.left, y), Offset(b.right, y), w, StrokeCap.Round)
        }
        "airdefence" -> {
            // the dome: a half circle standing on the frame's lower edge
            val r = b.width * 0.3f
            drawArc(ink, 180f, 180f, false, Offset(c.x - r, b.bottom - r * 0.95f), Size(r * 2, r * 2), style = line)
        }
        "marine" -> {
            // an anchor
            val top = c.y - b.height * 0.3f
            val bottom = c.y + b.height * 0.28f
            drawLine(ink, Offset(c.x, top), Offset(c.x, bottom), w, StrokeCap.Round)
            drawLine(ink, Offset(c.x - b.width * 0.12f, top + b.height * 0.1f), Offset(c.x + b.width * 0.12f, top + b.height * 0.1f), w, StrokeCap.Round)
            val r = b.width * 0.2f
            drawArc(ink, 20f, 140f, false, Offset(c.x - r, bottom - r * 1.3f), Size(r * 2, r * 1.3f), style = line)
        }
        "hq" -> Unit // the staff is drawn outside the frame
        else -> Unit
    }
}

/** A ship seen from above, bow up: its hull filled in the side's ink, and what stands on its deck. */
private fun DrawScope.drawHull(kind: String, at: Offset, s: Float, fill: Color, ink: Color, w: Float) {
    val (lengthK, beamK) = when (kind) {
        "carrier" -> 1.7f to 0.5f
        "cruiser" -> 1.55f to 0.32f
        "destroyer" -> 1.4f to 0.28f
        "frigate" -> 1.2f to 0.26f
        "patrol" -> 0.9f to 0.26f
        "tanker" -> 1.55f to 0.38f
        else -> 1.3f to 0.34f
    }
    val len = s * lengthK
    val beam = s * beamK
    val top = at.y - len / 2
    val bottom = at.y + len / 2
    val hx = beam / 2
    val edge = Stroke(width = w * 0.8f, join = StrokeJoin.Round)
    val outline = UnitInks.on(fill.copy(alpha = 1f)).copy(alpha = fill.alpha)
    val hull = Path()
    if (kind == "carrier") {
        // a flight deck: square stern, a short bow, the angled deck bulging to port
        hull.moveTo(at.x - hx * 0.6f, top)
        hull.lineTo(at.x + hx * 0.7f, top)
        hull.lineTo(at.x + hx, top + len * 0.18f)
        hull.lineTo(at.x + hx, bottom)
        hull.lineTo(at.x - hx * 0.7f, bottom)
        hull.lineTo(at.x - hx * 1.15f, at.y + len * 0.05f)
        hull.lineTo(at.x - hx * 0.9f, top + len * 0.2f)
        hull.close()
    } else {
        val bowLen = len * (if (kind == "tanker") 0.14f else 0.3f)
        hull.moveTo(at.x, top)
        hull.quadraticTo(at.x + hx, top + bowLen * 0.45f, at.x + hx, top + bowLen)
        hull.lineTo(at.x + hx, bottom - len * 0.06f)
        hull.lineTo(at.x + hx * 0.7f, bottom)
        hull.lineTo(at.x - hx * 0.7f, bottom)
        hull.lineTo(at.x - hx, bottom - len * 0.06f)
        hull.lineTo(at.x - hx, top + bowLen)
        hull.quadraticTo(at.x - hx, top + bowLen * 0.45f, at.x, top)
        hull.close()
    }
    drawPath(hull, fill, style = Fill)
    drawPath(hull, outline, style = edge)
    when (kind) {
        "carrier" -> {
            // the island to starboard and the landing area's centre line
            drawRect(ink, Offset(at.x + hx * 0.45f, at.y - len * 0.02f), Size(hx * 0.4f, len * 0.2f))
            drawLine(ink, Offset(at.x - hx * 0.85f, at.y + len * 0.02f), Offset(at.x + hx * 0.1f, bottom - len * 0.05f), w * 0.6f)
        }
        "tanker" -> {
            // the pipe run along the deck and the bridge aft
            drawLine(ink, Offset(at.x, top + len * 0.18f), Offset(at.x, bottom - len * 0.25f), w * 0.6f)
            drawRect(ink, Offset(at.x - hx * 0.7f, bottom - len * 0.2f), Size(hx * 1.4f, len * 0.1f))
        }
        "patrol" -> drawRect(ink, Offset(at.x - hx * 0.45f, at.y - len * 0.05f), Size(hx * 0.9f, len * 0.22f))
        "ship" -> drawRect(ink, Offset(at.x - hx * 0.55f, bottom - len * 0.28f), Size(hx * 1.1f, len * 0.16f))
        else -> {
            // a warship: superstructure amidships, a gun forward
            drawRect(ink, Offset(at.x - hx * 0.5f, at.y - len * 0.08f), Size(hx, len * 0.26f))
            drawCircle(ink, hx * 0.32f, Offset(at.x, top + len * 0.26f))
        }
    }
}

/** A small arrow: the unit is on the move. */
private fun DrawScope.drawMoveArrow(from: Offset, s: Float, ink: Color, fill: Color, w: Float, heading: Float?) {
    val len = s * 0.55f
    val rad = ((heading ?: 90f) * kotlin.math.PI / 180.0).toFloat()
    val dx = kotlin.math.sin(rad) * len
    val dy = -kotlin.math.cos(rad) * len
    val tip = Offset(from.x + dx, from.y + dy)
    val halo = w * 2.2f
    drawLine(ink.copy(alpha = fill.alpha * 0.6f), from, tip, halo, StrokeCap.Round)
    drawLine(fill, from, tip, w * 1.2f, StrokeCap.Round)
    val back = 0.4f
    val side = 0.22f
    val bx = tip.x - dx * back
    val by = tip.y - dy * back
    val px = -dy * side
    val py = dx * side
    val head = Path().apply { moveTo(tip.x, tip.y); lineTo(bx + px, by + py); lineTo(bx - px, by - py); close() }
    drawPath(head, fill, style = Fill)
    drawPath(head, ink.copy(alpha = fill.alpha * 0.6f), style = Stroke(width = w * 0.6f))
}
