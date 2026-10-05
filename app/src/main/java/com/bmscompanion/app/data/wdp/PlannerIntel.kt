package com.bmscompanion.app.data.wdp

import com.bmscompanion.app.data.mission.CampMapIntel
import com.bmscompanion.app.data.mission.CampMapJstar
import com.bmscompanion.app.data.mission.CampMapUnit
import com.bmscompanion.app.data.mission.CampSupport
import com.bmscompanion.app.data.wdp.DtcFromMission.LineOption
import com.bmscompanion.app.data.wdp.DtcFromMission.Pt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * The Planner's **Map** page, the part of WDP's MAP tab that is arithmetic (`fclsMain.cs`: `Recon`, `SamIntel`,
 * `DrawMapGrid`, `DrawMapTankerTracks`, `AutoFillPPT`, the leg labels of `DrawMapStpt`), worked out on the app's data
 * and pure, so the checks run it as the page does. The page (`ui/screens/wdp/WdpMapPage.kt`) draws what this returns.
 *
 * - **Which units are drawn** ([threats], [noRadar], [ownSams]; never what the side has not seen): WDP's intel rules on the PC's
 *   [CampMapIntel] — a hostile unit counts as seen when the flight's side spotted it, saw it recently (WDP's recon-loss
 *   time by move type, `recent`) or has it inside the 200 nm of a JSTARS on station — and the types ticked in the
 *   page's Types block, which lists the systems the save holds rather than WDP's twenty fixed boxes (D-row "types").
 * - **The tanker's station box** ([orbitBox]): WDP's 30,000 ft either side of the station leg, the box its MAP tab
 *   draws and copies into a line.
 * - **The graticule** ([graticule]): whole degrees, sampled every 5' so the projection's curve shows, with 10' ticks,
 *   or 10' lines once the map is close enough in (WDP's Fine Grid, made automatic).
 * - **Leg labels** ([legLabels]) and **Auto PPT** ([autoPpt]).
 */
object PlannerIntel {

    /** A grid kilometre in theater feet, as the campaign's cells are. */
    const val FT_PER_KM = 3279.98

    /** WDP's half-width of a tanker's station box (`DrawMapTankerTracks`, the DataCard's `stationBox`). */
    const val ORBIT_HALF_FT = 30000.0

    /** WDP's search-radar ring (`DrawSearchRadars`): BMS publishes no range for a search radar; ×3 with S Large. */
    const val SEARCH_NM = 25.0

    /** WDP's `MaxJstarRange`, nm. */
    const val JSTAR_NM = 200.0

    /** The waypoint actions that are a task (WDP's IP is the point before the first of these): CAS CAP to Airdrop. */
    val TASK_ACTIONS = 9..25
    const val TAKEOFF = 1
    const val REFUEL = 4
    const val LAND = 7

    // ---------------------------------------------------------------- units

    /** A unit's threat type: the SHORAD vehicle a unit of another kind carries, else its own system. */
    fun threatType(u: CampMapUnit): String = u.shorad?.takeIf { it.isNotBlank() } ?: u.system

    /** A unit that is air defence: its kind, or a SHORAD vehicle among its vehicles. */
    fun airDefence(u: CampMapUnit): Boolean = u.kind == "airdefence" || !u.shorad.isNullOrBlank()

    /**
     * The systems that carry their own radar on every launcher (SA-8, -11, -13, -15, -17, -19, Chaparral, Crotale,
     * Roland and their 4.38.1 names): with no radar left they have nothing left, so they never show under No radar.
     */
    fun selfContained(system: String): Boolean {
        val s = system.lowercase().filter { it.isLetterOrDigit() }
        return SELF.any { s.contains(it) }
    }

    private val SELF = listOf(
        "sa8", "sa11", "sa13", "sa15", "sa17", "sa19", "9k33", "9k37", "9k35", "9k330", "9k331", "9k332", "2s6", "osa", "strela10",
        "tor", "buk", "tunguska", "chaparral", "crotale", "roland", "chunma", "ksam", "avenger", "gecko", "gadfly", "gopher", "gauntlet", "grizzly", "grison",
    )

    /** How the flight's side knows of [u]: "spotted", "seen 40 min ago", "in Sentry6's area", or null for not seen. */
    fun seenHow(u: CampMapUnit, clock: Long, jstars: List<CampMapJstar>): String? {
        if (u.side == "friendly") return "own side"
        if (u.spotted) return "spotted"
        if (u.recent) {
            val s = u.seen ?: return "seen recently"
            val min = ((clock - s) / 60000L).coerceAtLeast(0)
            return if (min <= 1) "seen a moment ago" else "seen $min min ago"
        }
        if (u.jstar) {
            val j = jstars.filter { it.active }.minByOrNull { hypot(it.x - u.x, it.y - u.y) }
            return "in ${j?.callsign ?: "the JSTARS"}'s area"
        }
        return null
    }

    /** Seen by the flight's side ([seenHow]): spotted, seen recently or inside an active JSTARS's area. */
    fun seen(u: CampMapUnit): Boolean = u.side == "friendly" || u.spotted || u.recent || u.jstar

    /**
     * WDP's Threat layer (`Recon`, `SamIntel`): hostile air-defence units, not destroyed, whose type is ticked
     * ([off] holds the types switched off), whose radar works or who have none (guns and MANPADS), and that are seen.
     */
    fun threats(i: CampMapIntel, off: Set<String>): List<CampMapUnit> = i.units.filter { u ->
        u.side == "hostile" && !u.dead && airDefence(u) && threatType(u) !in off &&
            (u.radar == "alive" || u.radar == "none" || u.radar == "unknown") && seen(u)
    }

    /** WDP's No Radar layer: seen hostile air defence whose radar vehicles are gone (not guns), except the self-contained. */
    fun noRadar(i: CampMapIntel, off: Set<String>): List<CampMapUnit> = i.units.filter { u ->
        u.side == "hostile" && !u.dead && airDefence(u) && threatType(u) !in off && u.radar == "dead" && seen(u) && !selfContained(threatType(u))
    }

    /** WDP's own-side layer: friendly air defence, not destroyed, radar working or none, its type ticked. */
    fun ownSams(i: CampMapIntel, off: Set<String>): List<CampMapUnit> = i.units.filter { u ->
        u.side == "friendly" && !u.dead && airDefence(u) && threatType(u) !in off && u.radar != "dead"
    }

    // (WDP's "Cheat SAM" layers — what the side has not seen — are not ported: no cheating, and the PC sends no such unit.)

    fun moving(i: CampMapIntel, off: Set<String>): List<CampMapUnit> = i.units.filter { u ->
        u.side == "hostile" && !u.dead && u.moving && (!airDefence(u) || threatType(u) !in off)
    }

    fun destroyed(i: CampMapIntel, off: Set<String>): List<CampMapUnit> = i.units.filter { u ->
        u.dead && (!airDefence(u) || threatType(u) !in off)
    }

    /** One system of the Types block: its name, how many units of it, how many seen, and its ring (feet, null unknown). */
    data class TypeCount(val system: String, val count: Int, val seen: Int, val ringFt: Double?)

    /**
     * The air-defence systems the save holds on [side] ("hostile" or "friendly"), largest ring first, then by count:
     * the chips of the Types block. [ringOf] is the ring of a system (the theater's `Ppt.ini`, else the threat reference).
     */
    fun types(i: CampMapIntel, side: String, ringOf: (String) -> Double?): List<TypeCount> =
        i.units.filter { it.side == side && !it.dead && airDefence(it) }.groupBy { threatType(it) }
            .map { (s, us) -> TypeCount(s, us.size, us.count { seen(it) }, ringOf(s)) }
            .sortedWith(compareByDescending<TypeCount> { it.ringFt ?: -1.0 }.thenByDescending { it.count }.thenBy { it.system })

    // ---------------------------------------------------------------- tankers and AWACS

    /** A support aircraft's station: the leg WDP draws (from its first tanker or ELINT waypoint to the next) and its times. */
    data class Station(val support: CampSupport, val a: Pt, val b: Pt, val from: Long, val to: Long) {
        val mid: Pt get() = Pt((a.north + b.north) / 2, (a.east + b.east) / 2)
    }

    /** [s]'s station ([CampSupport.leg], else the first station point), or null when its route has none. */
    fun station(s: CampSupport): Station? {
        val pts = s.track
        val i = s.leg.takeIf { it in pts.indices } ?: pts.indexOfFirst { it.station }.takeIf { it >= 0 } ?: return null
        val a = pts[i]
        val b = pts.getOrNull(i + 1) ?: a
        if (a.x == 0.0 && a.y == 0.0) return null
        return Station(s, Pt(a.x, a.y), Pt(b.x, b.y), a.arriveMs, b.departMs)
    }

    /** Whether [st] is on station while the flight is up ([from]..[to]; WDP's `Time1 < arrival && Time2 > departure`). */
    fun onStationDuring(st: Station, from: Long?, to: Long?): Boolean {
        if (from == null || to == null || to <= from) return true
        return st.from < to && st.to > from
    }

    /**
     * WDP's station box: [halfFt] either side of the leg from [a] to [b], as four corners and the first again (so a
     * line of five points draws it closed). A leg of no length stands north-south, as WDP's track of 0 does.
     */
    fun orbitBox(a: Pt, b: Pt, halfFt: Double = ORBIT_HALF_FT): List<Pt> {
        val dn = b.north - a.north
        val de = b.east - a.east
        val len = hypot(dn, de).takeIf { it > 0.0 }
        val pn = if (len == null) 0.0 else -de / len * halfFt
        val pe = if (len == null) halfFt else dn / len * halfFt
        val c1 = Pt(a.north + pn, a.east + pe)
        val c2 = Pt(a.north - pn, a.east - pe)
        val c3 = Pt(b.north - pn, b.east - pe)
        val c4 = Pt(b.north + pn, b.east + pe)
        return listOf(c1, c2, c3, c4, c1)
    }

    /** The station box of [st] as a line the DTC can hold (WDP's MAP tab's "tanker track into Line n"). */
    fun orbitLine(st: Station): LineOption {
        val role = st.support.role
        val kind = when {
            role.contains("tank", true) -> DtcFromMission.LineKind.TANKER
            role.contains("jstar", true) -> DtcFromMission.LineKind.JSTARS
            else -> DtcFromMission.LineKind.AWACS
        }
        val word = when (kind) {
            DtcFromMission.LineKind.TANKER -> "tanker"
            DtcFromMission.LineKind.JSTARS -> "JSTARS"
            else -> "AWACS"
        }
        // named as the DTC page's From mission… names the same box (DtcFromMission.lineOptions)
        return LineOption(
            "${st.support.callsign} $word track", "${st.support.callsign} track".take(16), kind, orbitBox(st.a, st.b),
            "its station leg, boxed 30,000 ft either side as WDP lays a tanker track",
        )
    }

    /** "10:20": the time of day of a campaign time. */
    fun hhmm(ms: Long): String {
        val m = ((ms / 60000L) % 1440L + 1440L) % 1440L
        return (m / 60).toString().padStart(2, '0') + ":" + (m % 60).toString().padStart(2, '0')
    }

    // ---------------------------------------------------------------- the graticule

    /** A line of the graticule: its points (theater feet), whether it is a whole degree, what it is (degrees), and which way. */
    data class GridLine(val points: List<Pt>, val major: Boolean, val deg: Double, val latitude: Boolean)

    /** A tick on a degree line: where, a point one minute along the other way (for its direction), and whether it is the 30'. */
    data class Tick(val at: Pt, val toward: Pt, val half: Boolean)

    data class Graticule(val lines: List<GridLine>, val ticks: List<Tick>, val fine: Boolean)

    /**
     * The lat/long grid over the visible box ([n0]..[n1] north, [e0]..[e1] east, theater feet). [toLatLon] and
     * [toSim] are the theater's projection (north, east) ↔ (lat, lon). With [fine], a line every 10' and no ticks;
     * otherwise whole degrees with a tick every 10'. Lines are sampled every 5' (2' when fine) so they bend as the
     * projection does. Null when the projection cannot be read at the box's corners.
     */
    fun graticule(
        n0: Double, e0: Double, n1: Double, e1: Double,
        fine: Boolean,
        toLatLon: (Double, Double) -> Pair<Double, Double>?,
        toSim: (Double, Double) -> Pair<Double, Double>?,
    ): Graticule? {
        val corners = listOf(n0 to e0, n0 to e1, n1 to e0, n1 to e1, (n0 + n1) / 2 to e0, (n0 + n1) / 2 to e1, n0 to (e0 + e1) / 2, n1 to (e0 + e1) / 2)
            .mapNotNull { (n, e) -> toLatLon(n, e) }
        if (corners.size < 4) return null
        val lat0 = corners.minOf { it.first }
        val lat1 = corners.maxOf { it.first }
        val lon0 = corners.minOf { it.second }
        val lon1 = corners.maxOf { it.second }
        if (!(lat1 > lat0) || !(lon1 > lon0) || lat1 - lat0 > 40 || lon1 - lon0 > 60) return null
        val step = if (fine) 10.0 / 60.0 else 1.0
        val sample = if (fine) 2.0 / 60.0 else 5.0 / 60.0
        val lines = ArrayList<GridLine>()
        val ticks = ArrayList<Tick>()
        fun sim(lat: Double, lon: Double): Pt? = toSim(lat, lon)?.let { (n, e) -> Pt(n, e) }
        fun count(a: Double, b: Double) = ((b - a) / sample).toInt().coerceIn(1, 2000)
        // latitudes, drawn across the longitudes visible
        var lat = floor(lat0 / step) * step
        var guard = 0
        while (lat <= lat1 + 1e-9 && guard++ < 400) {
            val n = count(lon0, lon1)
            val pts = (0..n).mapNotNull { k -> sim(lat, lon0 - sample + (lon1 - lon0 + 2 * sample) * k / n) }
            val whole = abs(lat - round(lat)) < 1e-6
            if (pts.size >= 2) lines += GridLine(pts, whole, lat, latitude = true)
            if (!fine && whole) {
                var m = ceil(lon0 * 6.0) / 6.0
                while (m <= lon1) {
                    val half = abs(m * 2 - round(m * 2)) < 1e-6
                    val at = sim(lat, m)
                    val to = sim(lat + 1.0 / 60.0, m)
                    if (at != null && to != null && abs(m - round(m)) > 1e-6) ticks += Tick(at, to, half)
                    m += 1.0 / 6.0
                }
            }
            lat += step
        }
        var lon = floor(lon0 / step) * step
        guard = 0
        while (lon <= lon1 + 1e-9 && guard++ < 400) {
            val n = count(lat0, lat1)
            val pts = (0..n).mapNotNull { k -> sim(lat0 - sample + (lat1 - lat0 + 2 * sample) * k / n, lon) }
            val whole = abs(lon - round(lon)) < 1e-6
            if (pts.size >= 2) lines += GridLine(pts, whole, lon, latitude = false)
            if (!fine && whole) {
                var m = ceil(lat0 * 6.0) / 6.0
                while (m <= lat1) {
                    val half = abs(m * 2 - round(m * 2)) < 1e-6
                    val at = sim(m, lon)
                    val to = sim(m, lon + 1.0 / 60.0)
                    if (at != null && to != null && abs(m - round(m)) > 1e-6) ticks += Tick(at, to, half)
                    m += 1.0 / 6.0
                }
            }
            lon += step
        }
        return Graticule(lines, ticks, fine)
    }

    /** "37°N", "127°30'E": a graticule line's label. */
    fun degLabel(deg: Double, latitude: Boolean): String {
        val a = abs(deg)
        var d = floor(a + 1e-9).toInt()
        var m = round((a - d) * 60).toInt()
        if (m == 60) { d++; m = 0 }
        val h = if (latitude) (if (deg < 0) "S" else "N") else (if (deg < 0) "W" else "E")
        return if (m == 0) "$d°$h" else "$d°${m.toString().padStart(2, '0')}'$h"
    }

    // ---------------------------------------------------------------- leg labels

    /** A leg's label: its middle, its ends, its true track and its length in nm. */
    data class LegLabel(val from: Int, val to: Int, val a: Pt, val b: Pt, val trackDeg: Double, val nm: Double) {
        val mid: Pt get() = Pt((a.north + b.north) / 2, (a.east + b.east) / 2)
        val text: String get() = trackText(trackDeg) + "\n" + DataCardNet.fmt(nm, "0.0")
    }

    /** "045°": a true track, whole degrees, 360 for north as a compass card writes it. */
    fun trackText(deg: Double): String {
        var d = round(deg).toInt() % 360
        if (d <= 0) d += 360
        return d.toString().padStart(3, '0') + "°"
    }

    /**
     * WDP's Trk/Dist: every leg of [route] (steerpoint, position, action; in order) longer than [minNm], legs to or
     * from a landing left out, as the DataCard measures them (true, flat grid).
     */
    fun legLabels(route: List<Triple<Int, Pt, Int>>, minNm: Double): List<LegLabel> {
        val out = ArrayList<LegLabel>()
        for (i in 1 until route.size) {
            val (na, a, aa) = route[i - 1]
            val (nb, b, ab) = route[i]
            if (aa == LAND || ab == LAND) continue
            val br = AttackGeometry.bearingRange(a.north, a.east, b.north, b.east)
            val nm = br.rangeFt / DtcFromMission.NM
            if (nm < max(minNm, 0.1)) continue
            out += LegLabel(na, nb, a, b, br.bearingDeg, nm)
        }
        return out
    }

    /** The steerpoint WDP marks IP: the one before the first tasked action, when there is one before it. */
    fun ipOf(route: List<Pair<Int, Int>>): Int? {
        val i = route.indexOfFirst { it.second in TASK_ACTIONS }
        return if (i >= 1) route[i - 1].first else null
    }

    // ---------------------------------------------------------------- Auto PPT

    /**
     * WDP's Auto PPT (`AutoFillPPT`), on the threat rings the map draws ([threats], each already typed by the PPT
     * table): with fifteen or fewer, all of them; with more, the rings of 30,000 ft or more that come within 1.5 times
     * their range of a waypoint of [route], nearest first, the first fifteen. WDP measured to the waypoint cell's
     * corner; here to its middle, as every waypoint is placed.
     */
    fun autoPpt(threats: List<DtcFromMission.PptOption>, route: List<Pt>): List<DtcFromMission.PptOption> {
        val rings = threats.filter { !it.marker }
        if (rings.size <= 15) return rings
        if (route.isEmpty()) return rings.sortedByDescending { it.rangeFt }.take(15)
        fun near(o: DtcFromMission.PptOption) = route.minOf { it.dist(o.at) }
        return rings.filter { it.rangeFt >= 30000.0 && near(it) <= 1.5 * it.rangeFt }.sortedBy { near(it) }.take(15)
    }

    // ---------------------------------------------------------------- measure

    /** The measure line from [a] to [b]: its true track, the magnetic one where [variation] is known, and its length. */
    fun measure(a: Pt, b: Pt, variation: Double?): Triple<Double, Double?, Double> {
        val br = AttackGeometry.bearingRange(a.north, a.east, b.north, b.east)
        val mag = variation?.let { ((br.bearingDeg - it) % 360 + 360) % 360 }
        return Triple(br.bearingDeg, mag, br.rangeFt / DtcFromMission.NM)
    }

    /** "8.60 W": a variation as a compass prints it (negative = west). */
    fun varText(deg: Double): String = DataCardNet.fmt(abs(deg), "0.00") + if (deg < 0) " W" else if (deg > 0) " E" else ""

    /** The box around [pts] with [marginFt] on every side; null for none. */
    fun extent(pts: List<Pt>, marginFt: Double = 0.0): DoubleArray? {
        if (pts.isEmpty()) return null
        return doubleArrayOf(
            pts.minOf { it.north } - marginFt, pts.minOf { it.east } - marginFt,
            pts.maxOf { it.north } + marginFt, pts.maxOf { it.east } + marginFt,
        )
    }

    /** The smaller of two nullable numbers. */
    internal fun minOrNull(a: Double?, b: Double?): Double? = if (a == null) b else if (b == null) a else min(a, b)
}
