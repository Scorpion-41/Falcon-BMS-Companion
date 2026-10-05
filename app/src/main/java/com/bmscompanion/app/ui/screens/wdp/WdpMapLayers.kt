package com.bmscompanion.app.ui.screens.wdp

import com.bmscompanion.app.ui.components.drawDashedLine
import com.bmscompanion.app.ui.components.drawDashedCircle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.bmscompanion.app.data.Navaid
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampFlightRow
import com.bmscompanion.app.data.mission.CampMagVar
import com.bmscompanion.app.data.mission.CampMapField
import com.bmscompanion.app.data.mission.CampMapIntel
import com.bmscompanion.app.data.mission.CampMapRadar
import com.bmscompanion.app.data.mission.CampMapUnit
import com.bmscompanion.app.data.mission.CampWaypoint
import com.bmscompanion.app.data.wdp.DtcFromMission
import com.bmscompanion.app.data.wdp.DtcFromMission.Pt
import com.bmscompanion.app.data.wdp.PlannerIntel
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.SEA_UNIT_KINDS
import com.bmscompanion.app.ui.components.drawUnitSymbol
import com.bmscompanion.app.ui.components.safeText
import com.bmscompanion.app.ui.screens.airportKind
import com.bmscompanion.app.ui.screens.mission.Friendly
import com.bmscompanion.app.ui.screens.mission.Hostile
import com.bmscompanion.app.ui.screens.mission.MapGlow
import com.bmscompanion.app.ui.screens.mission.MapHalo
import com.bmscompanion.app.ui.screens.mission.MapShadow
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What the Map page draws beyond the cartridge and the flight's own support: WDP's MAP tab's layers (`fclsMain.cs`
 * `Draw()` and its `DrawMap…`), on the app's theater map — the lat/long grid, the bullseye's rings, every airfield in
 * the colour of the side holding it, the VORTACs and their range, the side's tankers and AWACS on station, the JSTARS
 * areas, ships, the intel rings (threats, search radars, no radar, own side — never what the side has not seen: WDP's
 * Cheat SAM layers are not offered, and the PC sends no unseen unit), the package's other routes and the measure line. [MapExtras] is what they are drawn from, worked out once per
 * change of the save or the mission ([mapExtras] in `WdpMapPage.kt`).
 */
internal class MapExtras(
    /** the PC's picture of the save (`/api/campaign/mapintel`); null before it answers, or from a PC older than 1.3.8 */
    val intel: CampMapIntel? = null,
    /** why there is no intel, in a sentence */
    val intelNote: String? = null,
    /** the theater's magnetic variation (`/api/campaign/magvar`); null where BMS ships none */
    val magVar: CampMagVar? = null,
    val coords: PopupCoords.CoordData? = null,
    /** the theater's airports and airstrips (carriers left out: they move) and its navaids */
    val airports: List<Airport> = emptyList(),
    val navaids: List<Navaid> = emptyList(),
    /** the save's field of each app airport id, for the side holding it now and its squadrons */
    val fieldOf: Map<Int, CampMapField> = emptyMap(),
    /** the save's flight: the one planned, or (briefing mode) the one BMS briefed as the PC resolved it */
    val flight: CampFlight? = null,
    /** the side's tankers, AWACS and JSTARS with a station (`CampFlight.sideSupport`) */
    val stations: List<PlannerIntel.Station> = emptyList(),
    /** the flight's time in the air: its take-off's departure to its landing's arrival */
    val window: Pair<Long, Long>? = null,
    val typesHostile: List<PlannerIntel.TypeCount> = emptyList(),
    val typesOwn: List<PlannerIntel.TypeCount> = emptyList(),
    /** the intel unit behind a threat site, by the site's position */
    val siteUnit: Map<Pt, CampMapUnit> = emptyMap(),
    /** the Types block's key: the theater's id */
    val theaterKey: String = "",
    /** a system's ring in feet: the theater's Ppt.ini, else the threat reference */
    val ringOf: (String) -> Double? = { null },
    /** the PPT code of a system in the theater's Ppt.ini */
    val codeOf: (String) -> String? = { null },
    /** the theater the ground is asked in ([WdpGround]) */
    val groundTheater: String? = null,
    /** the cartridge's TACAN: channel, band (0 X, 1 Y), domain (0 T/R, 1 A/A TR); null for none */
    val tacan: Triple<Int, Int, Int>? = null,
    /** the Tacview feed's hostile ships */
    val liveShips: List<DtcFromMission.Place> = emptyList(),
) {
    val units: Map<String, CampMapUnit> by lazy { intel?.units.orEmpty().associateBy { it.id } }
    val radars: Map<String, CampMapRadar> by lazy { intel?.radars.orEmpty().associateBy { it.id } }
    val clock: Long get() = intel?.clock ?: flight?.clock ?: 0L

    /** The package's routes by flight id, the flight's own left out. */
    val packageRoutes: Map<String, List<CampWaypoint>> by lazy { flight?.packageRoutes.orEmpty().associate { it.id to it.route } }
    val packageRows: Map<String, CampFlightRow> by lazy { flight?.packageFlights.orEmpty().associateBy { it.id } }
}

/** The Map page's intel inks: WDP's colours, a shade for the dark styles and one for the light Chart. */
internal object MapInk {
    /** a hostile air defence: the SAM red of every map (WDP drew its threats yellow, which sank into the relief) */
    val threat: Color get() = com.bmscompanion.app.ui.screens.mission.SamRed
    val search: Color get() = if (Hud.onLightMap) Color(0xFF6A1B9A) else Color(0xFFC08BFF)
    val pptInDtc: Color get() = com.bmscompanion.app.ui.screens.mission.SamRed
    val noRadar: Color get() = if (Hud.onLightMap) Color(0xFF2E7D32) else Color(0xFF6CE66C)
    val ownSam: Color get() = if (Hud.onLightMap) Color(0xFF1565C0) else Color(0xFF4FA8FF)
    val jstar: Color get() = if (Hud.onLightMap) Color(0xFFB0209A) else Color(0xFFFF5CE1)
    val unspotted: Color get() = if (Hud.onLightMap) Color(0xFFAD1457) else Color(0xFFFF7AD9)
    val moving: Color get() = if (Hud.onLightMap) Color(0xFF558B2F) else Color(0xFFB4F04A)
    val dead: Color get() = if (Hud.onLightMap) Color(0xFF757575) else Color(0xFFB8BEC4)
    /** WDP's ONC purple: the grid, the navaids and their range */
    val onc: Color get() = if (Hud.onLightMap) Color(0xFF4D367E) else Color(0xFFB9A8E8)
    val bull: Color get() = if (Hud.onLightMap) Color(0xFF0D3C78) else Hud.Cyan
    val pack: Color get() = if (Hud.onLightMap) Color(0xFF455A64) else Color(0xFF9FB3C6)
    val neutral: Color get() = if (Hud.onLightMap) Color(0xFF616161) else Color(0xFFA7AFB7)
    val measure: Color get() = if (Hud.onLightMap) Color(0xFF111111) else Color(0xFFFFFFFF)

    /** The ink of an airfield of [side]. */
    fun side(side: String?, plain: Color): Color = when (side) {
        "friendly" -> Friendly
        "hostile" -> Hostile
        "neutral" -> neutral
        else -> plain
    }
}

/** What each label is written in: the Label ink setting (WDP's Text color) for airfields and navaids. */
internal fun labelInk(ink: Int, light: Boolean): Color = when (ink) {
    1 -> Color.White
    2 -> Color(0xFF111418)
    3 -> Color(0xFF4D367E)
    else -> if (light) Color(0xFF12151A) else Color.White
}

/** A label to place later, collisions settled by priority (the cartridge's first). */
internal fun interface NameSink { fun add(prio: Int, text: String, at: Offset, style: TextStyle) }

/** the same patterns as plain arrays, for dashes drawn as strokes (drawDashedLine, drawDashedCircle) */
private val DashOn = floatArrayOf(9f, 7f)
private val DotsOn = floatArrayOf(2f, 5f)

private fun DrawScope.onScreen(p: Offset, pad: Float = 60f) = p.x > -pad && p.y > -pad && p.x < size.width + pad && p.y < size.height + pad
private fun DrawScope.ringOn(c: Offset, r: Float) = !(c.x + r < 0 || c.y + r < 0 || c.x - r > size.width || c.y - r > size.height)

// ==================================================================================================== the grid

private object GridCache {
    var key: String? = null
    var grid: PlannerIntel.Graticule? = null
}

/** WDP's lat/long grid (`DrawMapGrid`), over what the map shows: degrees with 10' ticks, or 10' lines close in. */
internal fun DrawScope.drawGraticule(pr: MapProjection, coords: PopupCoords.CoordData, tm: TextMeasurer) {
    val tl = pr.toTheater(Offset(0f, 0f))
    val br = pr.toTheater(Offset(size.width, size.height))
    val n0 = min(tl.first, br.first); val n1 = max(tl.first, br.first)
    val e0 = min(tl.second, br.second); val e1 = max(tl.second, br.second)
    val span = max(n1 - n0, e1 - e0).coerceAtLeast(1.0)
    // WDP's Fine Grid, automatic: 10' lines once ten minutes of latitude (ten miles) are some 40 px apart
    val fine = pr.pxPerNm * 10f > 40f
    val q = span / 20
    val key = "${(n0 / q).toLong()}|${(e0 / q).toLong()}|${(span / q * 1000).toLong()}|${q.toLong()}|$fine|${coords.hashCode()}"
    val g = if (GridCache.key == key) GridCache.grid else runCatching {
        val m = span * 0.05
        PlannerIntel.graticule(
            n0 - m, e0 - m, n1 + m, e1 + m, fine,
            toLatLon = { n, e -> runCatching { PopupCoords.convertSimXYToLatLon(coords.tm, n.toFloat(), e.toFloat()) }.getOrNull()?.takeIf { !it.first.isNaN() && !it.second.isNaN() } },
            toSim = { la, lo -> runCatching { PopupCoords.convertLatLonToSimXY(coords.tm, la.toFloat(), lo.toFloat()) }.getOrNull()?.let { (e, n) -> n.toDouble() to e.toDouble() } },
        )
    }.getOrNull().also { GridCache.key = key; GridCache.grid = it }
    g ?: return
    val ink = MapInk.onc
    val label = TextStyle(color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, shadow = if (Hud.onLightMap) MapGlow else MapShadow)
    // the edge labels, each left out where it would overlap one already written (the 10' lines crowd an edge zoomed out)
    val written = ArrayList<androidx.compose.ui.geometry.Rect>()
    fun edgeText(text: String, at: Offset) {
        val s = com.bmscompanion.app.ui.components.MapText.label(this, tm, text, label).layout.size
        val r = androidx.compose.ui.geometry.Rect(at.x - 3f, at.y, at.x + s.width + 3f, at.y + s.height)
        if (written.any { it.overlaps(r) }) return
        written += r
        safeText(tm, text, at, label)
    }
    for (l in g.lines.sortedByDescending { it.major }) {
        val pts = l.points.map { pr.toScreen(it.north, it.east) }
        val path = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        drawPath(path, ink.copy(alpha = if (l.major) 0.75f else 0.45f), style = Stroke(if (l.major) 1.4f else 0.9f))
        if (!l.major && !g.fine) continue
        // the degree at the four edges of what is shown
        val text = PlannerIntel.degLabel(l.deg, l.latitude)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            if (l.latitude) {
                for (edge in listOf(0f, size.width)) if ((a.x - edge) * (b.x - edge) <= 0f && a.x != b.x) {
                    val y = a.y + (b.y - a.y) * (edge - a.x) / (b.x - a.x)
                    if (y < 14f || y > size.height - 14f) continue
                    val w = com.bmscompanion.app.ui.components.MapText.label(this, tm, text, label).layout.size.width
                    edgeText(text, Offset(if (edge == 0f) 4f else size.width - w - 4f, y - 14f))
                }
            } else {
                for (edge in listOf(0f, size.height)) if ((a.y - edge) * (b.y - edge) <= 0f && a.y != b.y) {
                    val x = a.x + (b.x - a.x) * (edge - a.y) / (b.y - a.y)
                    if (x < 30f || x > size.width - 60f) continue
                    edgeText(text, Offset(x + 3f, if (edge == 0f) 4f else size.height - 18f))
                }
            }
        }
    }
    // the 10' ticks, the 30' longer
    for (t in g.ticks) {
        val p = pr.toScreen(t.at.north, t.at.east)
        if (!onScreen(p, 10f)) continue
        val q2 = pr.toScreen(t.toward.north, t.toward.east)
        val len = hypot(q2.x - p.x, q2.y - p.y).takeIf { it > 0.01f } ?: continue
        val u = Offset((q2.x - p.x) / len, (q2.y - p.y) / len)
        val h = if (t.half) 7f else 4f
        drawLine(ink.copy(alpha = 0.8f), p - u * h, p + u * h, 1.3f)
    }
}

// ==================================================================================================== the bullseye

/**
 * WDP's bullseye (`DrawMapBullseye`): rings every 30 nm to 150, labelled on the four axes, solid north-south and
 * east-west axes and spokes every 30° to 180 nm; [extra] adds dashed rings every 10 nm and dashed spokes at 10° and
 * 20° (WDP's 100 nm ring, drawn 200 nm tall, is a circle here).
 */
internal fun DrawScope.drawBullseyeRings(pr: MapProjection, c: Offset, extra: Boolean, tm: TextMeasurer) {
    val ink = MapInk.bull
    val nm = pr.pxPerNm
    if (!ringOn(c, 180 * nm)) return
    if (extra) {
        for (r in 10..150 step 10) if (r % 30 != 0) drawDashedCircle(ink.copy(alpha = 0.3f), r * nm, c, 0.9f, DashOn)
        for (a in 0 until 360 step 10) if (a % 30 != 0) {
            val v = Offset(sin(a * PI / 180).toFloat(), -cos(a * PI / 180).toFloat())
            drawDashedLine(ink.copy(alpha = 0.28f), c + v * (10 * nm), c + v * (180 * nm), 0.9f, DashOn)
        }
    }
    for (r in 30..150 step 30) drawCircle(ink.copy(alpha = 0.55f), r * nm, c, style = Stroke(1.3f))
    for (a in 0 until 360 step 30) {
        val v = Offset(sin(a * PI / 180).toFloat(), -cos(a * PI / 180).toFloat())
        drawLine(ink.copy(alpha = if (a % 90 == 0) 0.7f else 0.4f), c + v * (if (a % 90 == 0) 0f else 30 * nm), c + v * (180 * nm), if (a % 90 == 0) 1.4f else 1f)
    }
    if (nm * 30 < 26f) return
    val style = TextStyle(color = ink, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, shadow = if (Hud.onLightMap) MapGlow else MapShadow)
    for (r in 30..150 step 30) for (a in listOf(0, 90, 180, 270)) {
        val v = Offset(sin(a * PI / 180).toFloat(), -cos(a * PI / 180).toFloat())
        val p = c + v * (r * nm)
        if (onScreen(p, 0f)) safeText(tm, "${r}nm", p + Offset(3f, 1f), style)
    }
}

// ==================================================================================================== airfields and navaids

/** An airfield's symbol: a ring with its runways across it, filled with [fill] (the side holding it). */
internal fun DrawScope.drawFieldSymbol(p: Offset, runways: List<Offset>, fill: Color, ring: Color, r: Float) {
    drawCircle(MapHalo, r + 2.5f, p)
    drawCircle(fill, r, p)
    drawCircle(ring, r, p, style = Stroke(1.4f))
    runways.forEach { v -> drawLine(MapHalo.copy(alpha = 0.9f), p - v * (r + 2f), p + v * (r + 2f), 2.6f, cap = StrokeCap.Round) }
}

/** The runway directions of [a] as unit screen vectors, one per heading. */
internal fun runwayVectors(a: Airport): List<Offset> =
    a.runways.mapNotNull { it.ends.firstOrNull()?.headingTrue }.map { ((it % 180) + 180) % 180 }.distinctBy { (it / 6).toInt() }.map { h ->
        val r = h * PI / 180
        Offset(sin(r).toFloat(), -cos(r).toFloat())
    }

/** Whether [a] is drawn by the Airports or the Airstrips switch (carriers by neither: they belong to Ships). */
internal fun airfieldLayer(a: Airport): String? = when (airportKind(a)) {
    "Carrier" -> null
    "Airbase" -> "airport"
    else -> "airstrip"
}

/** "Osan AB", "1,234'  TCN 94X", "APP 308.800": an airfield's label as the Labels setting has it. */
internal fun airportLabel(a: Airport, mode: Int): String? = when (mode) {
    0 -> listOfNotNull(
        a.name,
        listOfNotNull(a.elevationFt?.let { "$it'" }, a.tacan?.let { "TCN ${it.channel}${it.band}" }).joinToString("  ").ifBlank { null },
        a.freqs?.approachUhf?.let { "APP $it" } ?: a.freqs?.towerUhf?.let { "TWR $it" },
    ).joinToString("\n")
    1 -> a.icao?.takeIf { it.isNotBlank() } ?: a.name
    else -> null
}

internal fun DrawScope.drawAirfields(
    pr: MapProjection, x: MapExtras, flightFields: Set<Int>, airports: Boolean, airstrips: Boolean, labels: Int, ink: Color, names: NameSink,
) {
    val small = pr.pxPerNm < 1.2f
    val style = TextStyle(color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 12.sp, shadow = if (ink.luminance() > 0.5f) MapShadow else MapGlow)
    for (a in x.airports) {
        if (a.id in flightFields) continue
        val layer = airfieldLayer(a) ?: continue
        if (layer == "airport" && !airports || layer == "airstrip" && !airstrips) continue
        val p = pr.toScreen(a.x, a.y)
        if (!onScreen(p)) continue
        val side = x.fieldOf[a.id]?.side
        val fill = MapInk.side(side, ink).copy(alpha = 0.9f)
        drawFieldSymbol(p, if (small) emptyList() else runwayVectors(a), fill, if (layer == "airport") ink.copy(alpha = 0.8f) else ink.copy(alpha = 0.5f), if (layer == "airport") 5.5f else 4f)
        airportLabel(a, labels)?.let { names.add(6, it, p + Offset(9f, -7f), style) }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** The VORTACs: WDP's symbol and "NAME ch band"; [range] their reach in ONC purple; [hover] the one whose ring fills. */
internal fun DrawScope.drawVortacs(pr: MapProjection, x: MapExtras, range: Boolean, hover: Int?, fill: Float, ink: Color, names: NameSink) {
    val onc = MapInk.onc
    val style = TextStyle(color = ink, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, shadow = if (ink.luminance() > 0.5f) MapShadow else MapGlow)
    x.navaids.forEachIndexed { i, n ->
        val p = pr.toScreen(n.x, n.y)
        val rNm = n.tacan?.rangeNm
        if (range && rNm != null && rNm > 0) {
            val r = rNm * pr.pxPerNm
            if (ringOn(p, r)) {
                if (i == hover) drawCircle(onc.copy(alpha = fill), r, p)
                drawCircle(onc.copy(alpha = 0.55f), r, p, style = Stroke(1.2f))
            }
        } else if (i == hover && rNm != null) drawCircle(onc.copy(alpha = fill), rNm * pr.pxPerNm, p)
        if (!onScreen(p)) return@forEachIndexed
        // a VORTAC: a hexagon with a dot, on a halo
        val hex = Path().apply { for (k in 0..5) { val a = PI / 3 * k; val q = p + Offset((cos(a) * 6).toFloat(), (sin(a) * 6).toFloat()); if (k == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }; close() }
        drawPath(hex, MapHalo, style = Stroke(4f))
        drawPath(hex, onc, style = Stroke(1.8f))
        drawCircle(onc, 1.6f, p)
        // some navaids carry their channel in their name already ("Yeosu VOR/DME (YSU) 104X")
        val ch = n.tacan?.let { "${it.channel}${it.band}" }?.takeIf { c -> !n.name.contains(c, ignoreCase = true) }
        names.add(7, listOfNotNull(n.name, ch).joinToString(" "), p + Offset(8f, 2f), style)
    }
}

/** The station the cartridge's TACAN is tuned to: a bold ring and a "TCN" tag. */
internal fun DrawScope.drawTunedTacan(p: Offset, tm: TextMeasurer) {
    val ink = MapInk.onc
    drawCircle(MapHalo, 13f, p, style = Stroke(5f))
    drawCircle(ink, 12f, p, style = Stroke(3f))
    val style = TextStyle(color = Hud.Bg, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    val l = com.bmscompanion.app.ui.components.MapText.label(this, tm, "TCN", style).layout
    val at = p + Offset(-l.size.width / 2f, -26f)
    drawRoundRect(ink, at - Offset(3f, 1f), Size(l.size.width + 6f, l.size.height + 2f), androidx.compose.ui.geometry.CornerRadius(4f))
    safeText(tm, "TCN", at, style)
}

// ==================================================================================================== support

/** WDP's station box around the station leg (solid, as every map's track is), filled at [fill], labelled "Texaco4/122Y" over "10:20 - 11:40". */
internal fun DrawScope.drawOrbitBox(pr: MapProjection, st: PlannerIntel.Station, ink: Color, fill: Float, label: String?, names: NameSink, prio: Int = 3) {
    val box = PlannerIntel.orbitBox(st.a, st.b).map { pr.toScreen(it.north, it.east) }
    if (box.none { onScreen(it, 200f) }) return
    val path = Path().apply { box.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close() }
    drawPath(path, ink.copy(alpha = fill))
    drawPath(path, MapHalo.copy(alpha = 0.5f), style = Stroke(3.5f))
    drawPath(path, ink.copy(alpha = 0.9f), style = Stroke(1.8f))
    if (label != null) {
        val m = pr.toScreen(st.mid.north, st.mid.east)
        names.add(prio, label, m + Offset(6f, -12f), TextStyle(color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 12.sp, shadow = if (Hud.onLightMap) MapGlow else MapShadow))
    }
}

/** "Texaco4/60Y\n10:20 - 11:40": a station's label, its TACAN as the receiver dials it (WDP's Y-band shift). */
internal fun stationLabel(st: PlannerIntel.Station): String {
    val s = st.support
    val t = s.tacan?.dropLast(1)?.toIntOrNull()?.let { DtcFromMission.tieOn(it).toString() + "Y" }
    return s.callsign + (t?.let { "/$it" } ?: "") + "\n" + PlannerIntel.hhmm(st.from) + " - " + PlannerIntel.hhmm(st.to)
}

internal fun DrawScope.drawJstar(pr: MapProjection, x: MapExtras, fill: Float) {
    val ink = MapInk.jstar
    for (j in x.intel?.jstars.orEmpty()) {
        if (!j.active) continue
        val c = pr.toScreen(j.x, j.y)
        val r = j.rangeNm * pr.pxPerNm
        if (ringOn(c, r)) {
            drawCircle(ink.copy(alpha = fill / 2), r, c)
            drawCircle(ink.copy(alpha = 0.8f), r, c, style = Stroke(1.6f))
        }
        if (onScreen(c)) { drawCircle(MapHalo, 6f, c); drawCircle(ink, 4.5f, c) }
    }
}

// ==================================================================================================== units

/**
 * Ships with their symbols (the Ships switch): the flight's side's, and the other side's it has seen — the PC sends no
 * others — never a destroyed one. More than [MAX_UNITS] on screen: none, and a note says to zoom in. (The 1.3.8 test
 * builds drew every ground unit too; that layer is gone.)
 */
internal fun DrawScope.drawShips(pr: MapProjection, x: MapExtras, tm: TextMeasurer) {
    val intel = x.intel
    val show = intel?.units.orEmpty().filter { u ->
        val sea = u.domain == "sea" || u.kind in SEA_UNIT_KINDS
        sea && !u.dead && (u.side == "friendly" || PlannerIntel.seen(u))
    }
    val onScreenUnits = show.map { it to pr.toScreen(it.x, it.y) }.filter { onScreen(it.second, 10f) }
    if (onScreenUnits.size > MAX_UNITS) {
        val style = TextStyle(color = Hud.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, shadow = MapShadow)
        safeText(tm, "Zoom in to see the ${onScreenUnits.size} units", Offset(12f, size.height - 26f), style)
    } else {
        val s = (pr.pxPerNm * 1.2f).coerceIn(9f, 16f)
        for ((u, p) in onScreenUnits) drawUnitSymbol(u.kind, u.side, p, s, moving = u.moving, dim = u.dead || (u.side == "hostile" && !PlannerIntel.seen(u)))
    }
    for (s in x.liveShips) {
        val p = pr.toScreen(s.at.north, s.at.east)
        if (onScreen(p)) drawUnitSymbol("ship", "hostile", p, 13f)
    }
}

const val MAX_UNITS = 300

/**
 * A ring of [u]'s reach ([rFt]) in [ink]: [dashed] for what the cartridge does not hold, filled at [fill] (0 none), and
 * its site marked; the name is placed only near the route or close in.
 */
internal fun DrawScope.drawUnitRing(pr: MapProjection, at: Pt, rFt: Double?, ink: Color, dashed: Boolean, fill: Float, dotted: Boolean = false, mark: Boolean = true) {
    val c = pr.toScreen(at.north, at.east)
    val r = ((rFt ?: 0.0) * pr.pxPerNm / DtcFromMission.NM).toFloat()
    if (r > 2f) {
        if (!ringOn(c, r)) return
        if (fill > 0f) drawCircle(ink.copy(alpha = fill), r, c)
        if (dotted || dashed) drawDashedCircle(ink.copy(alpha = 0.8f), r, c, 1.6f, if (dotted) DotsOn else DashOn) else drawCircle(ink.copy(alpha = 0.8f), r, c, style = Stroke(1.6f))
    } else if (!onScreen(c)) return
    if (!mark) return
    val h = 3.5f
    drawRect(MapHalo, Offset(c.x - h - 1.5f, c.y - h - 1.5f), Size(2 * h + 3f, 2 * h + 3f))
    drawRect(ink, Offset(c.x - h, c.y - h), Size(2 * h, 2 * h), style = Stroke(1.5f))
}

/** A search radar: WDP's "S" ring (25 nm, 75 with S Large), [dotted] for the own side's. */
internal fun DrawScope.drawSearch(pr: MapProjection, r: CampMapRadar, large: Boolean, dotted: Boolean, tm: TextMeasurer, names: NameSink) {
    val ink = MapInk.search
    val c = pr.toScreen(r.x, r.y)
    val rad = (PlannerIntel.SEARCH_NM * (if (large) 3 else 1) * pr.pxPerNm).toFloat()
    if (!ringOn(c, rad)) return
    if (dotted) drawDashedCircle(ink.copy(alpha = 0.75f), rad, c, 1.5f, DotsOn) else drawCircle(ink.copy(alpha = 0.75f), rad, c, style = Stroke(1.5f))
    if (!onScreen(c)) return
    val style = TextStyle(color = ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, shadow = if (Hud.onLightMap) MapGlow else MapShadow)
    val l = com.bmscompanion.app.ui.components.MapText.label(this, tm, "S", style).layout
    safeText(tm, "S", c - Offset(l.size.width / 2f, l.size.height / 2f), style)
    if (pr.pxPerNm > 5f) names.add(8, r.name, c + Offset(8f, 4f), style.copy(fontSize = 9.5.sp))
}

// ==================================================================================================== the package

/**
 * Another flight of the package: its route in the package's ink, its points as small rings (every map's steerpoint,
 * smaller), or with [wdpShapes] WDP's symbols, its numbers and its callsign.
 */
internal fun DrawScope.drawPackageRoute(pr: MapProjection, route: List<CampWaypoint>, callsign: String, names: NameSink, wdpShapes: Boolean = false) {
    val ink = MapInk.pack
    val pts = route.map { pr.toScreen(it.x, it.y) }
    for (i in 1 until pts.size) {
        if (!onScreen(pts[i - 1]) && !onScreen(pts[i])) continue
        drawLine(MapHalo.copy(alpha = 0.6f), pts[i - 1], pts[i], 4f)
        drawLine(ink, pts[i - 1], pts[i], 2f)
    }
    val style = TextStyle(color = ink, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, shadow = if (Hud.onLightMap) MapGlow else MapShadow)
    route.forEachIndexed { k, w ->
        val p = pts[k]
        if (!onScreen(p)) return@forEachIndexed
        val shape = if (!wdpShapes) Shape.CIRCLE else if (k == 0) Shape.SQUARE else if (w.action in PlannerIntel.TASK_ACTIONS) Shape.TRIANGLE else Shape.CIRCLE
        stptSymbol(p, shape, ink, 5f, 1.8f)
        names.add(9, "${if (w.n > 0) w.n else k + 1}", p + Offset(7f, -14f), style)
    }
    pts.firstOrNull()?.let { names.add(5, callsign, it + Offset(8f, 4f), style.copy(fontSize = 10.5.sp)) }
}

/** The shapes of a route's points: WDP's square (STPT 1, the IP), triangle (a tasked point), circle, diamond (a target). */
internal enum class Shape { CIRCLE, SQUARE, TRIANGLE, DIAMOND }

internal fun DrawScope.stptSymbol(p: Offset, shape: Shape, ink: Color, r: Float, w: Float, halo: Boolean = true, dashed: Boolean = false) {
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4f, 3f), 0f) else null
    val path = when (shape) {
        Shape.CIRCLE -> null
        Shape.SQUARE -> Path().apply { moveTo(p.x - r, p.y - r); lineTo(p.x + r, p.y - r); lineTo(p.x + r, p.y + r); lineTo(p.x - r, p.y + r); close() }
        Shape.TRIANGLE -> Path().apply { moveTo(p.x, p.y - r * 1.2f); lineTo(p.x + r * 1.1f, p.y + r * 0.8f); lineTo(p.x - r * 1.1f, p.y + r * 0.8f); close() }
        Shape.DIAMOND -> Path().apply { moveTo(p.x, p.y - r * 1.4f); lineTo(p.x + r * 1.4f, p.y); lineTo(p.x, p.y + r * 1.4f); lineTo(p.x - r * 1.4f, p.y); close() }
    }
    if (path == null) {
        if (halo) drawCircle(MapHalo, r + w, p, style = Stroke(w * 2))
        drawCircle(ink, r, p, style = Stroke(w, pathEffect = effect))
    } else {
        if (halo) drawPath(path, MapHalo, style = Stroke(w * 2.4f))
        drawPath(path, ink, style = Stroke(w, pathEffect = effect))
    }
}

// ==================================================================================================== leg labels and measure

/** WDP's Trk/Dist: "045°" over "12.3" at a leg's middle, set off to its left. */
internal fun DrawScope.drawLegLabels(pr: MapProjection, legs: List<PlannerIntel.LegLabel>, ink: Color, names: NameSink) {
    val style = TextStyle(color = ink, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, lineHeight = 11.sp, shadow = if (Hud.onLightMap) MapGlow else MapShadow)
    for (l in legs) {
        val a = pr.toScreen(l.a.north, l.a.east)
        val b = pr.toScreen(l.b.north, l.b.east)
        val len = hypot(b.x - a.x, b.y - a.y)
        if (len < 60f) continue
        val m = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
        if (!onScreen(m)) continue
        // the leg's left, as one looks along it
        val left = Offset((b.y - a.y) / len, -(b.x - a.x) / len)
        names.add(2, l.text, m + left * 16f - Offset(12f, 12f), style)
    }
}

/** The measure marks: an X at each point, the line between (or to the cursor), each point's lat/long beside it. */
internal fun DrawScope.drawMeasure(pr: MapProjection, a: Pt?, b: Pt?, cursor: Pt?, labels: List<String>, tm: TextMeasurer) {
    val ink = MapInk.measure
    fun x(p: Offset) {
        drawLine(MapHalo, p - Offset(8f, 8f), p + Offset(8f, 8f), 5f); drawLine(MapHalo, p - Offset(8f, -8f), p + Offset(8f, -8f), 5f)
        drawLine(ink, p - Offset(7f, 7f), p + Offset(7f, 7f), 2.2f); drawLine(ink, p - Offset(7f, -7f), p + Offset(7f, -7f), 2.2f)
    }
    val pa = a?.let { pr.toScreen(it.north, it.east) } ?: return
    val end = (b ?: cursor)?.let { pr.toScreen(it.north, it.east) }
    if (end != null) {
        drawLine(MapHalo, pa, end, 5f)
        if (b == null) drawDashedLine(ink, pa, end, 2f, DashOn) else drawLine(ink, pa, end, 2f)
    }
    x(pa)
    if (b != null && end != null) x(end)
    val style = TextStyle(color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, shadow = if (Hud.onLightMap) MapGlow else MapShadow)
    labels.getOrNull(0)?.let { safeText(tm, it, pa + Offset(10f, 6f), style) }
    if (b != null && end != null) labels.getOrNull(1)?.let { safeText(tm, it, end + Offset(10f, 6f), style) }
}
