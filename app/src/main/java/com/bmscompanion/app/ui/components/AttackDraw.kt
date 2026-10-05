package com.bmscompanion.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.bmscompanion.app.data.mission.AttackCue
import com.bmscompanion.app.data.mission.AttackOverlay

/**
 * The inks of the one attack drawing ([drawAttackModel]): [SCREEN] on the dark and satellite maps, [PAPER] on a white
 * map, a light map style and a kneeboard page. The path is white on dark, black on white (WDP's look); the cues keep
 * their colours: target red, VIP/VRP blue, pull-up magenta, offsets green.
 */
class AttackInks(
    val path: Color,
    val halo: Color,
    val target: Color,
    val ref: Color,
    val pup: Color,
    val oa: Color,
) {
    companion object {
        val SCREEN = AttackInks(Color.White, Color.Black.copy(alpha = 0.6f), Color(0xFFFF2020), Color(0xFF1E90FF), Color(0xFFFF00FF), Color(0xFF32CD32))
        val PAPER = AttackInks(Color.Black, Color.White.copy(alpha = 0.85f), Color(0xFFC00000), Color(0xFF1565C0), Color(0xFFA0009A), Color(0xFF1B7F1B))

        fun of(light: Boolean): AttackInks = if (light) PAPER else SCREEN
    }

    /** The ink of a cue. */
    fun of(kind: AttackCue.Kind): Color = when (kind) {
        AttackCue.Kind.TARGET -> target
        AttackCue.Kind.PUP -> pup
        AttackCue.Kind.OA -> oa
        else -> ref
    }
}

/**
 * **The attack, drawn one way on every map** (1.3.8): the attack pages' own map, the DataCard's, the Upd Kneeboard
 * attack page, the Mission map, a VR map board and the Planner's Map page all draw an [AttackOverlay]
 * ([com.bmscompanion.app.data.mission.AttackDrawing]) through this.
 *
 * In this order: the path (a halo, then a solid line), the dotted leg past the target, then the cues — OA2, OA1, the
 * pull-up point, the start (a **filled square** for a VIP, the HSD's IP symbol, Dash-34 p.88; a **filled circle** for
 * a VRP), the target's square on top — and the labels last, through [label] (each map has its own anti-overlap).
 * There is no separate IP mark and no line from an offset to the target. [at] places a point (north, east) in this
 * scope; [k] scales every size (1 = the screen's pixels, a kneeboard or WDP's 435-pixel map scales it); [notes] adds
 * the "4.2 nm to TGT" after the VIP or VRP.
 */
fun DrawScope.drawAttackModel(
    a: AttackOverlay,
    at: (Double, Double) -> Offset,
    inks: AttackInks,
    k: Float = 1f,
    notes: Boolean = false,
    visible: (Offset) -> Boolean = { true },
    label: ((text: String, anchor: Offset, color: Color) -> Unit)? = null,
) {
    // the path the jet flies
    val pts = a.runIn.map { at(it.first, it.second) }
    for (i in 1 until pts.size) drawLine(inks.halo, pts[i - 1], pts[i], 6f * k, StrokeCap.Round)
    for (i in 1 until pts.size) drawLine(inks.path, pts[i - 1], pts[i], 3f * k, StrokeCap.Round)
    // the leg past the target, dotted
    val past = a.beyond.map { at(it.first, it.second) }
    for (i in 1 until past.size) {
        drawDashedLine(inks.halo, past[i - 1], past[i], 5f * k, floatArrayOf(3f * k, 5f * k))
        drawDashedLine(inks.path, past[i - 1], past[i], 2.5f * k, floatArrayOf(3f * k, 5f * k))
    }
    // the cues, the target last (on top)
    val order = listOf(AttackCue.Kind.OA, AttackCue.Kind.PUP, AttackCue.Kind.VRP, AttackCue.Kind.VIP, AttackCue.Kind.TARGET)
    val cues = a.cues.filter { it.kind != AttackCue.Kind.IP }.sortedWith(compareBy({ order.indexOf(it.kind) }, { if (it.label == "OA2") 0 else 1 }))
    for (c in cues) {
        val p = at(c.north, c.east)
        if (!visible(p)) continue
        val ink = inks.of(c.kind)
        when (c.kind) {
            AttackCue.Kind.TARGET -> {
                val h = 9f * k
                drawRect(inks.halo, p - Offset(h, h), Size(2 * h, 2 * h), style = Stroke(6f * k))
                drawRect(ink, p - Offset(h, h), Size(2 * h, 2 * h), style = Stroke(3f * k))
            }
            AttackCue.Kind.VIP -> {
                val h = 7f * k
                drawRect(inks.halo, p - Offset(h + 1.5f * k, h + 1.5f * k), Size(2 * h + 3f * k, 2 * h + 3f * k))
                drawRect(ink, p - Offset(h, h), Size(2 * h, 2 * h))
            }
            AttackCue.Kind.VRP -> {
                drawCircle(inks.halo, 8.5f * k, p)
                drawCircle(ink, 7f * k, p)
            }
            AttackCue.Kind.PUP -> {
                drawCircle(inks.halo, 7f * k, p, style = Stroke(6f * k))
                drawCircle(ink, 7f * k, p, style = Stroke(3f * k))
            }
            else -> {
                // the HUD's offset aimpoint: a triangle, apex up (Dash-34 p.108), 16 x 14
                val tri = Path().apply { moveTo(p.x, p.y - 8f * k); lineTo(p.x + 8f * k, p.y + 6f * k); lineTo(p.x - 8f * k, p.y + 6f * k); close() }
                drawPath(tri, inks.halo, style = Stroke(4f * k))
                drawPath(tri, ink)
            }
        }
    }
    if (label != null) for (c in cues) {
        val p = at(c.north, c.east)
        if (!visible(p)) continue
        val text = if (notes && c.note.isNotEmpty() && (c.kind == AttackCue.Kind.VIP || c.kind == AttackCue.Kind.VRP)) "${c.label} ${c.note}" else c.label
        label(text, p + Offset(11f * k, -8f * k), inks.of(c.kind))
    }
}
