package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.wdp.AttackGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The ground under the attack pages' targets, from Falcon BMS's own height map (D7).
 *
 * WDP's Pop-up page adds the ground under the target to every height it hands the jet, from the theater's height map
 * (`GetTerrainHeight`). The app ships no height map; the PC reads the same cell of BMS's for any point asked
 * ([MissionLink.campaignGround], `/api/campaign/ground`). The pages ask for every placed steerpoint of the mission when
 * it arrives, so whichever the pilot turns TGT STPT to is known, and plan again when the answer comes. A point the PC
 * could not answer is remembered as unknown for this session, and the page falls back to the cartridge's target point.
 */
internal object WdpGround {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** "theater|north|east" (the float positions the pages hold) -> feet above sea level; null = asked, not known */
    private val cache = HashMap<String, Int?>()
    private val asking = HashSet<String>()

    /** Bumped when an answer arrives, for whatever draws from [at]. */
    var version by mutableIntStateOf(0)
        private set

    private fun key(theater: String?, north: Double, east: Double) = "${theater.orEmpty()}|${north.toFloat()}|${east.toFloat()}"

    /** The ground at (north, east) as BMS's height map has it, or null while it is not known. */
    fun at(theater: String?, north: Double, east: Double): Int? = cache[key(theater, north, east)]

    /** Whether the PC has answered for (north, east), with a height or without one. */
    fun known(theater: String?, north: Double, east: Double): Boolean = key(theater, north, east) in cache

    /**
     * Asks the PC for the ground under [points] not asked for yet, and calls [done] on the main thread when it has
     * answered with at least one height. Nothing is asked twice; a PC that cannot answer leaves them unknown.
     */
    fun fetch(theater: String?, points: List<Pair<Double, Double>>, done: () -> Unit) {
        val want = points.filter { (n, e) -> (n != 0.0 || e != 0.0) }
            .distinctBy { (n, e) -> key(theater, n, e) }
            .filter { (n, e) -> key(theater, n, e).let { it !in cache && it !in asking } }
            .take(200)
        if (want.isEmpty()) return
        val keys = want.map { (n, e) -> key(theater, n, e) }
        asking += keys
        scope.launch {
            val answer = try { MissionLink.campaignGround(theater, want) } catch (e: Exception) { null }
            asking -= keys.toSet()
            val heights = answer?.value?.heights
            if (heights == null || heights.size != want.size) return@launch
            keys.forEachIndexed { i, k -> cache[k] = heights[i] }
            version++
            if (heights.any { it != null }) done()
        }
    }

    /** For the checks: forget every answer. */
    fun clear() { cache.clear(); asking.clear() }
}

/** The theater the PC is asked about for this mission's ground: the save's own, else the one BMS is set to. */
internal fun WdpMission.groundTheater(): String? = ref?.theater ?: theater?.id

/** Where the ground under a target came from, for the Coordinates box and the ELEV window. */
internal enum class GroundSource(val words: String) {
    TYPED("typed by you"), TERRAIN("BMS's terrain"), DTC("your DTC's target point"), NONE("not known: 0 is used"),
}

/**
 * The ELEVs the pilot can type, as the page draws them: a pencil and a dotted frame on the figure, a hand for the mouse,
 * brighter under it. [typed] draws the frame solid, for a figure the pilot typed. Drawn over the label (which keeps its
 * own text); a tap or click goes on to the label, which opens the ELEV window.
 */
@Composable
internal fun ElevMark(typed: Boolean) {
    val hover = remember { MutableInteractionSource() }
    val over by hover.collectIsHoveredAsState()
    Canvas(Modifier.fillMaxSize().hoverable(hover).pointerHoverIcon(PointerIcon.Hand)) {
        val ink = if (typed) TYPED_INK else DED_YELLOW
        val a = if (over) 1f else 0.7f
        val w = size.width
        val h = size.height
        val s = (h / 16f).coerceAtLeast(0.5f)
        // the frame: dotted for WDP's own figure, solid for a typed one
        drawRect(
            ink.copy(alpha = a * 0.9f), Offset(0.5f * s, 0.5f * s), Size(w - s, h - s),
            style = Stroke(width = s, pathEffect = if (typed) null else PathEffect.dashPathEffect(floatArrayOf(2f * s, 2f * s))),
        )
        pencil(Offset(3f * s, h / 2f), h * 0.62f, ink.copy(alpha = a))
        if (over) drawRect(ink.copy(alpha = 0.12f))
    }
}

/**
 * The lines under the DED blocks that say the ELEVs can be typed, drawn in the free band at the foot of the DED Data
 * panel (y 566-640 of its 475 x 670 design pixels). [typed] lists the lines the pilot has typed; [ground] says what
 * the ELEVs stand on, and a tap on it opens the target's ground ([onGround]) — the Coordinates box is on another panel.
 */
@Composable
internal fun DedElevHint(typed: List<String>, ground: String, onGround: (() -> Unit)? = null) {
    val tm = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val k = constraints.maxWidth / 475f
        if (onGround != null) {
            val d = LocalDensity.current
            Box(
                Modifier.offset(with(d) { (60f * k).toDp() }, with(d) { (601f * k).toDp() })
                    .size(with(d) { (400f * k).toDp() }, with(d) { (18f * k).toDp() })
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onGround() },
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val style = TextStyle(color = DED_YELLOW, fontSize = (12f * k / (density * fontScale)).sp)
            val left = 60f * k
            pencil(Offset(left, 578f * k), 14f * k, DED_YELLOW)
            drawText(tm.measure("Every ELEV can be typed: tap or click the figure", style), topLeft = Offset(left + 14f * k, 570f * k))
            drawText(tm.measure("feet above sea level · 0 = ground level · clear it for WDP's own", style.copy(color = Color(0xFFBFBFBF))), topLeft = Offset(left + 14f * k, 587f * k))
            drawText(tm.measure(ground, style.copy(color = Color(0xFFBFBFBF))), topLeft = Offset(left + 14f * k, 604f * k))
            if (typed.isNotEmpty()) {
                drawText(tm.measure("Typed: " + typed.joinToString(", "), style.copy(color = TYPED_INK)), topLeft = Offset(left + 14f * k, 621f * k))
            }
        }
    }
}

/** A pencil pointing down-left, its point at the left of [c], [len] long. */
private fun DrawScope.pencil(c: Offset, len: Float, ink: Color) {
    val w = len * 0.26f
    val body = Path().apply {
        // the shaft, from the point up and to the right
        moveTo(c.x, c.y + len * 0.32f)
        lineTo(c.x + w * 0.9f, c.y + len * 0.32f - w * 0.1f)
        lineTo(c.x + len * 0.72f, c.y - len * 0.38f)
        lineTo(c.x + len * 0.72f - w * 0.9f, c.y - len * 0.38f - w * 0.35f)
        lineTo(c.x - w * 0.1f, c.y + len * 0.32f - w * 0.9f)
        close()
    }
    drawPath(body, ink)
}

/** The DED's yellow figures, and the ink of a figure the pilot typed. */
internal val DED_YELLOW = Color(0xFFFFFF00)
internal val TYPED_INK = Color(0xFF00FFFF)
internal const val TYPED_INK_NAME = "#00FFFF"

/** The ELEV labels of each attack page's DED panel, and the nav-offset line each belongs to in [vip] mode or VRP mode. */
internal fun elevKey(label: String, vip: Boolean): String? = when (label) {
    "lblVIPelv", "lblVRPelv" -> if (vip) "VIP" else "VRP"
    "lblPUPelv", "lblVRPPUPelv" -> if (vip) "VIPPUP" else "VRPPUP"
    "lblOA1elv" -> if (vip) "OA1_1" else "OA1_2"
    "lblOA2elv" -> if (vip) "OA2_1" else "OA2_2"
    else -> null
}

/** Human names of the nav-offset lines, for the DED hint's "Typed:" list. */
internal fun elevLineName(key: String): String = when (key) {
    "VIP" -> "VIP-TO-TGT"; "VRP" -> "TGT-TO-VRP"; "VIPPUP" -> "VIP-TO-PUP"; "VRPPUP" -> "TGT-TO-PUP"
    "OA1_1", "OA1_2" -> "OA1"; "OA2_1", "OA2_2" -> "OA2"; AttackGeometry.TGT -> "target"; else -> key
}
