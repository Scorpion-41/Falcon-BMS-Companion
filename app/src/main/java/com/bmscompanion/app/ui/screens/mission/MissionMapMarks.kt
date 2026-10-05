package com.bmscompanion.app.ui.screens.mission

import com.bmscompanion.app.ui.components.drawDashedLine
import com.bmscompanion.app.ui.components.drawDashedCircle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.wdp.DtcFromMission
import com.bmscompanion.app.data.wdp.PlannerIntel
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.screens.wdp.NameSink
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// One drawing for every map of the mission. The Mission section's map, the VR map board (both LiveMap) and the
// Planner's Map page (WdpMapPage, away from its HSD preview) draw the airfields, the steerpoints and the route, the
// cartridge's lines, the PPTs, the air defences, the tanker and AWACS boxes and the labels with these, so the same
// mission looks the same on all three by default. The Map page adds its editing on top, and WDP's own look
// (WdpMapPrefs.wdpLook) only when the pilot asks for it. The HSD preview keeps the HSD's own colours.

/** Which labels win where two would overlap: the lower first — the flight plan before everything else. */
internal object NamePrio {
    const val STPT = 0
    const val PPT = 1
    const val TRAFFIC = 1
    const val LINE = 2
    const val BULL = 2
    const val SUPPORT = 3
    const val FIELD = 4
    const val SITE = 5
    const val AIRPORT = 6
    const val NAVAID = 7
    const val SEARCH = 8
    const val PACKAGE = 9
}

/**
 * The labels of one drawing pass, gathered while the map draws and placed at the end by [NamePrio] (in the order they
 * came within one priority): a label that would overlap one already placed is left out, so ninety SA-2s of a campaign
 * never push the steerpoint numbers off the map.
 */
internal class MapNames : NameSink {
    private class N(val prio: Int, val text: String, val at: Offset, val style: TextStyle)
    private val list = ArrayList<N>()

    override fun add(prio: Int, text: String, at: Offset, style: TextStyle) {
        if (text.isNotBlank()) list += N(prio, text, at, style)
    }

    fun place(scope: DrawScope, tm: TextMeasurer, placed: MutableList<Rect>) {
        with(scope) { for (n in list.sortedBy { it.prio }) placeText(tm, n.text, n.at, n.style, placed) }
    }
}

/** How strongly a ring is filled on every map: the Planner's Circle fill starts here (20 of WDP's 255). */
const val RING_FILL = 20 / 255f

/**
 * The red of a hostile air defence on every map — its ring, its fill, its square and its name: the briefing's sites,
 * the cartridge's PPTs, the Tacview feed's SAMs, the Planner's intel threats, the AWACS page and the kneeboard's route
 * map. One red, chosen per ground: [dark] for the relief, satellite and dark styles (bright enough to hold on
 * satellite's dark greens, under a dark halo on the lighter relief), [light] for the chart and a printed page's map,
 * [paperDay]/[paperNight] for a board's paper inks. Up to 1.3.8's test builds the sites were the Planner's yellow,
 * which sank into the relief; friendly SAMs keep the friendly blue and PPT markers (tanker, AWACS) are never red.
 * The HSD preview keeps the jet's own yellow PPTs.
 */
object SamInk {
    val dark = Color(0xFFFF3030)
    val light = Color(0xFFCC0A0A)
    val paperDay = Color(0xFFA8241A)
    val paperNight = Color(0xFFC85A4E)
}

/** [SamInk] for what is being drawn on now. */
val SamRed: Color get() = when {
    Hud.paperInks -> if (com.bmscompanion.app.ui.theme.HudSkin.night) SamInk.paperNight else SamInk.paperDay
    Hud.onLightMap -> SamInk.light
    else -> SamInk.dark
}

/** The dash of a SAM ring the cartridge does not hold (the Planner's "known, not in a PPT"). */
internal val SamDashOn = floatArrayOf(10f, 7f)

/**
 * A hostile air defence's reach: [r] px about [c] in [ink] (normally [SamRed]), filled at [fill], outlined 2.4 px on a
 * dark (on the chart, white) halo so it holds on every map style; [dashed] when the cartridge does not hold it; [fade]
 * one cleared or ghosted.
 */
internal fun DrawScope.drawSamRing(c: Offset, r: Float, ink: Color = SamRed, dashed: Boolean = false, fill: Float = RING_FILL, fade: Float = 1f) {
    if (r <= 2f) return
    if (fill > 0f) drawCircle(ink.copy(alpha = fill * fade), r, c)
    val halo = MapHalo.copy(alpha = MapHalo.alpha * 0.7f * fade)
    if (dashed) {
        drawDashedCircle(halo, r, c, 4.6f, SamDashOn)
        drawDashedCircle(ink.copy(alpha = 0.95f * fade), r, c, 2.4f, SamDashOn)
    } else {
        drawCircle(halo, r, c, style = Stroke(4.6f))
        drawCircle(ink.copy(alpha = 0.95f * fade), r, c, style = Stroke(2.4f))
    }
}

/** A SAM's launcher square of half side [h] in [ink], on a halo. */
internal fun DrawScope.drawSamSquare(c: Offset, ink: Color = SamRed, h: Float = 4.5f) {
    drawRect(MapHalo, Offset(c.x - h - 1.5f, c.y - h - 1.5f), androidx.compose.ui.geometry.Size(2 * h + 3f, 2 * h + 3f))
    drawRect(ink, Offset(c.x - h, c.y - h), androidx.compose.ui.geometry.Size(2 * h, 2 * h), style = Stroke(2f))
}

/** The label every map writes beside a symbol: white with a dark blur, dark with a white one on the light chart. */
internal fun mapLabelStyle(): TextStyle = TextStyle(
    color = if (Hud.onLightMap) Color(0xFF12151A) else Color.White,
    fontSize = 11.sp, fontWeight = FontWeight.Bold,
    shadow = if (Hud.onLightMap) MapGlow else MapShadow,
)

internal fun DrawScope.onMap(p: Offset, pad: Float = 60f) = p.x > -pad && p.y > -pad && p.x < size.width + pad && p.y < size.height + pad
internal fun DrawScope.ringOnMap(c: Offset, r: Float) = !(c.x + r < 0 || c.y + r < 0 || c.x - r > size.width || c.y - r > size.height)

/** A line the cartridge draws on the HSD: a pale ink on the dark maps, a slate one on the chart. */
internal val LineInk: Color get() = if (Hud.paperInks) Hud.Text else if (Hud.onLightMap) Color(0xFF37474F) else Color(0xFFE8ECEF)
internal val LineDash = PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f)
/** the same patterns as plain arrays, for dashes drawn as strokes (drawDashedLine, drawDashedCircle) */
internal val LineDashOn = floatArrayOf(14f, 8f)
internal val PlanDashOn = floatArrayOf(6f, 5f)
private val WarnDashOn = floatArrayOf(9f, 7f)
private val AlternateDashOn = floatArrayOf(14f, 10f)
/** What the plan put there and the jet does not have yet. */
internal val PlanDash = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)

// ==================================================================================================== airfields

internal enum class FieldMark { HOME, ALTERNATE, OTHER }

/**
 * Whether a field of the theater is drawn at all: not a carrier (a carrier moves, and the theater's list has it where
 * the campaign started it) and not without a position. The flight's own fields are drawn whatever they are.
 */
internal fun mapsAirfield(a: Airport): Boolean =
    (a.x != 0.0 || a.y != 0.0) && com.bmscompanion.app.ui.screens.airportKind(a) != "Carrier"

/** [a]'s runways as unit screen directions (north up), each heading once — parallel ones are one line at this size. */
internal fun airfieldRunways(a: Airport): List<Offset> =
    a.runways.mapNotNull { it.ends.firstOrNull()?.headingTrue }
        .map { ((it % 180) + 180) % 180 }
        .distinctBy { (it / 6).toInt() }
        .map { h -> val r = Math.toRadians(h); Offset(sin(r).toFloat(), -cos(r).toFloat()) }
        .ifEmpty { listOf(Offset(0.7071f, -0.7071f)) }

/** "HOME", "DEP", "ARR" or "ALT": what a field is to the flight; null for any other. */
internal fun fieldTag(dep: Boolean, arr: Boolean, alt: Boolean): String? = when {
    dep && arr -> "HOME"
    dep -> "DEP"
    arr -> "ARR"
    alt -> "ALT"
    else -> null
}

/** The symbol of a field that is [dep], [arr] or [alt] to the flight (an alternate alone is the open ring). */
internal fun fieldMark(dep: Boolean, arr: Boolean, alt: Boolean): FieldMark =
    if (dep || arr) FieldMark.HOME else if (alt) FieldMark.ALTERNATE else FieldMark.OTHER

/**
 * The label of the flight's field: "HOME RKSO", in the airfield green, below the right of the symbol — the take-off
 * and landing steerpoints sit on the field, and their numbers take the space above it.
 */
internal fun nameField(a: Airport, tag: String, p: Offset, names: NameSink) =
    names.add(NamePrio.FIELD, "$tag ${a.icao ?: a.name}", p + Offset(15f, 3f), mapLabelStyle().copy(color = Hud.Green))

/**
 * The chart symbol for an airfield: a ring and its runways, [runways] being unit screen directions. Home is a solid
 * disc with the runways cut dark through it; the alternate an open ring with the runways drawn across and past it;
 * any other field the same, small and faint. Each sits on a dark halo so it holds on relief and satellite alike.
 */
internal fun DrawScope.drawAirfield(p: Offset, runways: List<Offset>, mark: FieldMark) {
    val ink = Hud.Green
    val halo = MapHalo
    when (mark) {
        FieldMark.HOME -> {
            val r = 10f
            drawCircle(halo, r + 3f, p)
            drawCircle(ink, r, p)
            runways.forEach { v -> drawLine(MapHalo.copy(alpha = 0.85f), p - v * (r * 0.78f), p + v * (r * 0.78f), 3.5f, cap = StrokeCap.Round) }
        }
        FieldMark.ALTERNATE -> {
            val r = 9f
            drawCircle(halo, r, p, style = Stroke(6f))
            runways.forEach { v -> drawLine(halo, p - v * (r + 4f), p + v * (r + 4f), 7f, cap = StrokeCap.Round) }
            drawCircle(ink, r, p, style = Stroke(2.5f))
            runways.forEach { v -> drawLine(ink, p - v * (r + 4f), p + v * (r + 4f), 3f, cap = StrokeCap.Round) }
        }
        FieldMark.OTHER -> {
            val r = 5f
            val faint = ink.copy(alpha = 0.75f)
            drawCircle(halo, r, p, style = Stroke(4f))
            runways.forEach { v -> drawLine(halo, p - v * (r + 2.5f), p + v * (r + 2.5f), 4f, cap = StrokeCap.Round) }
            drawCircle(faint, r, p, style = Stroke(1.5f))
            runways.forEach { v -> drawLine(faint, p - v * (r + 2.5f), p + v * (r + 2.5f), 1.75f, cap = StrokeCap.Round) }
        }
    }
}

// ==================================================================================================== the flight plan

/** A briefing's landing row: the cartridge's action 7, else the briefing's word. */
internal val Stpt.isLand: Boolean
    get() = actionCode == PlannerIntel.LAND || listOf(action, desc).any { it?.trim()?.startsWith("Land", ignoreCase = true) == true }

/** A briefing's refuel row: the cartridge's action 4, else the briefing's word. */
internal val Stpt.isRefuel: Boolean
    get() = actionCode == PlannerIntel.REFUEL || listOf(action, desc).any { it?.contains("Refuel", ignoreCase = true) == true }

/** One point of a flight plan as every map joins it: where it is drawn, and whether it is a landing or a refuel. */
internal class LegPt(val at: Offset, val land: Boolean, val refuel: Boolean, val alternate: Boolean = false)

/**
 * The flight plan's legs: solid on a dark halo, in [ink]. No leg joins a refuel point to a landing either way (BMS
 * lists the tanker's point after the landing; WDP draws no such leg either), and every leg after the first landing —
 * on to the alternate — is drawn thin and dashed, as is a leg to a point the briefing calls the alternate. [dashed]
 * draws every leg dashed (the Planner's mission-file view).
 */
internal fun DrawScope.drawRouteLegs(pts: List<LegPt>, ink: Color, dashed: Boolean = false) {
    val land = pts.indexOfFirst { it.land }
    for (i in 1 until pts.size) {
        val a = pts[i - 1]
        val b = pts[i]
        if (a.refuel && b.land || a.land && b.refuel) continue
        if (!onMap(a.at) && !onMap(b.at)) continue
        if (b.alternate || land >= 0 && i - 1 >= land) {
            drawDashedLine(ink.copy(alpha = 0.7f * ink.alpha), a.at, b.at, 2f, AlternateDashOn)
        } else {
            drawLine(MapHalo, a.at, b.at, 6f)
            if (dashed) drawDashedLine(ink, a.at, b.at, 3f, WarnDashOn) else drawLine(ink, a.at, b.at, 3f)
        }
    }
}

/**
 * A steerpoint: a ring on a dark disc in [ink]; a target a diamond (in the hostile ink, unless [ink] says otherwise);
 * [hollow] the Planner's hollow, dashed ring of a point the jet does not have yet; [fade] a point cleared.
 */
internal fun DrawScope.drawSteerpoint(p: Offset, target: Boolean, ink: Color, hollow: Boolean = false, fade: Float = 1f) {
    if (target) {
        val path = Path().apply { moveTo(p.x, p.y - 11f); lineTo(p.x + 11f, p.y); lineTo(p.x, p.y + 11f); lineTo(p.x - 11f, p.y); close() }
        drawPath(path, MapHalo.copy(alpha = fade), style = Stroke(6f))
        drawPath(path, ink.copy(alpha = fade * ink.alpha), style = Stroke(3f, pathEffect = if (hollow) PlanDash else null))
    } else if (hollow) {
        drawCircle(MapHalo, 11f, p, style = Stroke(4.5f))
        drawCircle(ink, 10f, p, style = Stroke(2.5f, pathEffect = PlanDash))
    } else {
        drawCircle(MapHalo.copy(alpha = fade), 9f, p)
        drawCircle(ink.copy(alpha = fade * ink.alpha), 7f, p, style = Stroke(3f))
    }
}

/** A steerpoint's label: its number, and close in (with labels on) its name. */
internal fun stptText(n: Int, name: String?, labels: Boolean, pr: MapProjection): String =
    if (labels && pr.scale >= 2.5f) "$n ${name.orEmpty()}".trim() else "$n"

/** Where a steerpoint's label goes. */
internal fun stptLabelAt(p: Offset) = p + Offset(11f, -18f)

// ==================================================================================================== lines, PPTs, air defences

/**
 * One of the cartridge's lines, joined in its own order and never to another, dashed as the HSD draws it; [plan] a
 * line the jet does not have yet (thinner, in the Planner's ink, which [ink] then is); [fade] a line cleared.
 */
internal fun DrawScope.drawCartridgeLine(pts: List<Offset>, ink: Color, plan: Boolean = false, fade: Float = 1f) {
    for (i in 1 until pts.size) {
        drawLine(MapHalo.copy(alpha = 0.7f * fade), pts[i - 1], pts[i], if (plan) 4f else 5.5f)
        drawDashedLine(ink.copy(alpha = fade), pts[i - 1], pts[i], if (plan) 2f else 3f, LineDashOn)
    }
    pts.forEach { drawCircle(MapHalo, 4.5f, it); drawCircle(ink.copy(alpha = fade), 3f, it) }
}

/** Where a line's label goes: by its first point. */
internal fun lineLabelAt(first: Offset) = first + Offset(8f, 4f)

/**
 * A PPT's threat ring of [r] px: the SAM red ([SamRed]), filled at [fill]; [plan] the Planner's dashed ring with a
 * hollow centre for one the jet does not have yet; [fade] one cleared.
 */
internal fun DrawScope.drawPptRing(c: Offset, r: Float, fill: Float = RING_FILL, plan: Boolean = false, fade: Float = 1f) {
    if (plan) {
        drawCircle(SamRed.copy(alpha = 0.05f), r, c)
        drawDashedCircle(PlanInk.copy(alpha = 0.9f), r, c, 2.5f, PlanDashOn)
        drawCircle(MapHalo, 6f, c, style = Stroke(4f))
        drawCircle(PlanInk, 5f, c, style = Stroke(2f))
    } else {
        drawSamRing(c, r, SamRed, dashed = false, fill = fill, fade = fade)
        drawCircle(MapHalo.copy(alpha = MapHalo.alpha * fade), 6.5f, c)
        drawCircle(SamRed.copy(alpha = fade), 5f, c)
    }
}

/** A PPT that is a marker (AWACS, tanker, a friendly: no ring): a small flag, as the HSD shows it. */
internal fun DrawScope.drawPptMarker(c: Offset, ink: Color, dashed: Boolean = false, fade: Float = 1f) {
    val flag = Path().apply { moveTo(c.x, c.y + 8f); lineTo(c.x, c.y - 10f); lineTo(c.x + 10f, c.y - 6f); lineTo(c.x, c.y - 2f) }
    drawPath(flag, MapHalo, style = Stroke(5f))
    drawPath(flag, ink.copy(alpha = fade), style = Stroke(2.5f, pathEffect = if (dashed) PlanDash else null))
}

internal fun pptLabelAt(c: Offset) = c + Offset(8f, 2f)
internal fun markerLabelAt(c: Offset) = c + Offset(12f, -8f)

/**
 * An air defence the Tacview feed reports while BMS flies: a launcher's square in [ink] ([SamRed] for a hostile one,
 * the friendly blue for your own side's), ringed (dashed, filled) at its system's reach of [r] px. Drawn under
 * everything that moves.
 */
internal fun DrawScope.drawLiveSam(c: Offset, r: Float, ink: Color): Boolean {
    if (r > 2f) {
        if (!ringOnMap(c, r)) return false
        drawSamRing(c, r, ink, dashed = true, fill = RING_FILL * 0.8f)
    } else if (!onMap(c)) return false
    drawSamSquare(c, ink)
    return true
}

/**
 * Whether a site of the mission picture is named: near the route (its ring within 40 nm of it — [edgeFt], null when
 * not known) or once the map is close in. Far off and zoomed out, a theater of SAMs would bury the map in names.
 */
internal fun siteNamed(pr: MapProjection, edgeFt: Double?): Boolean =
    pr.pxPerNm > 2.5f || (edgeFt ?: Double.MAX_VALUE) < 40 * DtcFromMission.NM

// ==================================================================================================== tankers and AWACS

/** The ink of a tanker's or an AWACS's track. */
internal fun trackInk(role: String): Color = if (role.contains("tank", true)) TankerColor else AwacsColor

/**
 * A support flight's track: the box WDP draws about the leg it holds on ([legA] to [legB]: 30,000 ft either side, the
 * box the Planner lays into a DTC line), filled and drawn solid, and nothing else — its transit from and back to its
 * base is not drawn (the pilot's word: lines to a track's points only crowded the map). [mine] is the one the flight
 * was given — the one it will actually fly to — drawn the stronger. [label] goes at the leg's middle.
 */
internal fun DrawScope.drawSupportTrack(
    pr: MapProjection, legA: DtcFromMission.Pt?, legB: DtcFromMission.Pt?,
    ink: Color, mine: Boolean, label: String?, names: NameSink,
) {
    if (legA == null || legB == null) return
    val from = pr.toScreen(legA.north, legA.east)
    val to = pr.toScreen(legB.north, legB.east)
    if (hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble()) <= 1.0) return
    val corners = PlannerIntel.orbitBox(legA, legB).map { pr.toScreen(it.north, it.east) }
    if (corners.none { onMap(it, 200f) }) return
    val box = Path().apply {
        corners.forEachIndexed { i, c -> if (i == 0) moveTo(c.x, c.y) else lineTo(c.x, c.y) }
        close()
    }
    drawPath(box, ink.copy(alpha = if (mine) 0.16f else 0.07f))
    drawPath(box, ink.copy(alpha = if (mine) 0.9f else 0.45f), style = Stroke(if (mine) 3f else 2f))
    if (label != null) {
        val mid = Offset((from.x + to.x) / 2, (from.y + to.y) / 2)
        names.add(NamePrio.SUPPORT, label, mid + Offset(6f, -6f), mapLabelStyle().copy(color = ink, lineHeight = 13.sp))
    }
}

/** "Texaco2 · TCN 059Y · YOURS": what a track is called and what to tune, on every map. */
internal fun trackLabel(callsign: String, tacan: String?, mine: Boolean): String =
    listOfNotNull(callsign, tacan?.takeIf { it.isNotBlank() }?.let { "TCN $it" }, if (mine) "YOURS" else null).joinToString(" · ")

/**
 * The channel a receiver dials for a tanker's own TACAN [own] ("122Y" → "059Y", the Y band's tie-on), as the briefing
 * and the Support card give it; [own] itself when it is not a channel.
 */
internal fun receiverTacan(own: String?): String? {
    val t = own?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val ch = Regex("^(\\d{1,3})\\s*([XYxy])$").find(t) ?: return t
    val n = ch.groupValues[1].toIntOrNull() ?: return t
    return DtcFromMission.tieOn(n).toString().padStart(3, '0') + ch.groupValues[2].uppercase()
}

/**
 * Where the briefing (or the plan) puts a tanker or an AWACS with no track: an orbit of [r] px, solid as a track is,
 * around a marked point; [label] beside it.
 */
internal fun DrawScope.drawSupportStation(c: Offset, r: Float, ink: Color, label: String?, names: NameSink) {
    if (!ringOnMap(c, r)) return
    drawCircle(ink.copy(alpha = 0.06f), r, c)
    drawCircle(ink.copy(alpha = 0.55f), r, c, style = Stroke(2f))
    drawCircle(MapHalo, 6f, c)
    drawCircle(ink, 4f, c)
    if (label != null) names.add(NamePrio.SUPPORT, label, c + Offset(9f, -8f), mapLabelStyle().copy(color = ink))
}

/** How wide a station is drawn: BMS names a point, and an orbit is a few miles across whatever the point. */
internal const val STATION_NM = 12.0

/** The selection: a magenta ring, on every map. */
internal fun DrawScope.drawSelection(p: Offset) = drawCircle(Hud.Magenta, 22f, p, style = Stroke(3f))
