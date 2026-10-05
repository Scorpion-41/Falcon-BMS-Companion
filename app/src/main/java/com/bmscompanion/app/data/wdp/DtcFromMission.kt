package com.bmscompanion.app.data.wdp

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The cartridge filled from what the app knows of the mission: the DTC page's **From mission…** on each tab, and the
 * Planner's Map page, which shows what the HSD will show and puts it there with the same calls.
 *
 * - **Lines** (L1-L4, six points each): a tanker's or an AWACS's planned track (the box about the leg the campaign has
 *   it hold on, closed in five points as WDP lays a tanker track),
 *   the flight's CAP station, a stretch of the flight plan.
 * - **PPTs** (56-70): the known enemy air-defence sites, the rings that reach the route first, each with the theater's own PPT type
 *   and range (`Campaign/Ppt.ini`); the tanker, the AWACS and the CAP as the markers BMS's PPT tables have for them.
 * - **Steerpoints**: the flight plan into the slots the cartridge leaves empty; one steerpoint set to an airbase, a
 *   target or a point of the mission.
 * - **Targets, STPT 81-99**: the mission's targets, the flight's designated ones, the air defences, the airbases.
 * - **HARM tables, laser codes, TACAN**: the systems the briefing names, the save's per-jet codes, the tanker's
 *   air-to-air channel.
 *
 * Pure. The Planner gathers the [Facts] (`ui/screens/wdp/DtcMissionFacts.kt`: the briefing, the save's flight, the
 * cartridge, the campaign's planned tracks, the live feed); the options here say what could go where, and the
 * writers put a pick into the [DtcModel] exactly as the DTC page's own Change windows do. The page then relabels
 * itself, and the change counts as an unsaved edit that Save to DTC writes.
 *
 * Positions are the sim's feet, north and east ([Pt]); the model holds them as falconY (north) and falconX (east).
 */
object DtcFromMission {
    const val NM = 6076.1157

    /** A position in the sim's feet. */
    data class Pt(val north: Double, val east: Double) {
        fun dist(o: Pt): Double = hypot(north - o.north, east - o.east)
        val placed: Boolean get() = north != 0.0 || east != 0.0
    }

    // ---------------------------------------------------------------- what the app knows

    /** One steerpoint of the flight plan as the Planner uses it; [from] says which source placed it ("BMS's route"). */
    data class RoutePoint(val n: Int, val at: Pt, val altFt: Double, val action: Int, val name: String?, val from: String)

    /** One point of a planned track; [station] where the aircraft holds (a tanker's racetrack legs). */
    data class TrackPoint(val at: Pt, val station: Boolean)

    /**
     * A tanker, an AWACS or a JSTARS: its planned [track] (the campaign's, empty when there is none), where it will be
     * ([station], and the words for where that came from), its TACAN and the channel a receiver ties on with.
     */
    data class Support(
        val role: String,
        val callsign: String,
        val track: List<TrackPoint> = emptyList(),
        val station: Pt? = null,
        val stationFrom: String? = null,
        val tacan: String? = null,
        val tieOn: String? = null,
        /** the one your flight is tied to */
        val yours: Boolean = false,
    )

    /**
     * A known enemy air-defence site. [names] are what it may be called, best first: the system the threat reference
     * makes of it ("SA-6"), its own name ("SA-6 Gainful TEL", "2K12 Kub"), the reference's aliases. [category] is
     * the reference's ("SAM", "AAA", "MANPADS"), when it knows the system.
     */
    data class Site(val names: List<String>, val at: Pt, val source: String, val category: String? = null, val elevFt: Double = 0.0) {
        val name: String get() = names.firstOrNull { it.isNotBlank() } ?: "Air defence"
    }

    /** Something a point can be set to: a target, an airbase, a steerpoint, a station. [elevFt] null: not known. */
    data class Place(val name: String, val at: Pt, val elevFt: Double?, val group: String)

    /**
     * A TACAN the mission offers: a field's (T/R) or a tanker's (air to air, the tie-on channel). [band] 0 X, 1 Y;
     * [domain] 0 T/R, 1 A/A TR, as the cartridge's `[COMMS]` holds them. [short] fits a button.
     */
    data class TacanChoice(val label: String, val short: String, val channel: Int, val band: Int, val domain: Int)

    /** An ILS the mission offers: a runway end with one, its frequency (MHz × 100) and course. [short] fits a button. */
    data class IlsChoice(val label: String, val short: String, val freq100: Int, val course: Int)

    data class Facts(
        /** the flight plan, placed points only, in steerpoint order (not the precision targets past it) */
        val route: List<RoutePoint> = emptyList(),
        val support: List<Support> = emptyList(),
        val sites: List<Site> = emptyList(),
        val places: List<Place> = emptyList(),
        /** the flight's targets for the open steerpoints: its designated targets and flight-plan targets, best first */
        val targets: List<Place> = emptyList(),
        /** hostile ships the feed reports, for the Harpoon steerpoints */
        val ships: List<Place> = emptyList(),
        /** the flight's laser codes, one per jet (a save's flight), and the seat planned (0 lead) */
        val laser: List<Int> = emptyList(),
        val seat: Int = 0,
        /** the air-defence systems the briefing's threat section names, as the threat reference names them */
        val briefedSystems: List<String> = emptyList(),
        val tacans: List<TacanChoice> = emptyList(),
        val ils: List<IlsChoice> = emptyList(),
        /** what is not known, for the pilot: "No known air-defence sites: …" */
        val notes: List<String> = emptyList(),
    )

    // ---------------------------------------------------------------- the DTC as it stands (for the Map page)

    /** A PPT as the cartridge holds it; a range under 100 ft is a marker (no ring), as BMS draws it. */
    data class PptShown(val slot: Int, val at: Pt, val code: String, val name: String, val rangeFt: Double) {
        val marker: Boolean get() = rangeFt < 100.0
    }

    /** What the HSD will show from this cartridge: the steerpoints, the four lines, the PPTs. */
    data class Picture(val stpts: List<Pair<Int, Pt>>, val lines: List<List<Pt>>, val ppts: List<PptShown>)

    fun picture(m: DtcModel): Picture = Picture(
        stpts = (0 until 24).mapNotNull { i -> m.stpt[i].let { s -> Pt(s.falconY.toDouble(), s.falconX.toDouble()).takeIf { it.placed }?.let { i + 1 to it } } } +
            (0 until 9).mapNotNull { i -> m.open[i].let { s -> Pt(s.falconY.toDouble(), s.falconX.toDouble()).takeIf { it.placed }?.let { 81 + i to it } } } +
            (0 until 10).mapNotNull { i -> m.hpn[i].let { s -> Pt(s.falconY.toDouble(), s.falconX.toDouble()).takeIf { it.placed }?.let { 90 + i to it } } },
        lines = (1..4).map { linePoints(m, it) },
        ppts = (0 until 15).mapNotNull { i ->
            val p = m.ppt[i]
            val at = Pt(p.falconY.toDouble(), p.falconX.toDouble())
            if (!at.placed) null else PptShown(56 + i, at, p.code.orEmpty(), p.name.orEmpty().ifBlank { p.code.orEmpty() }, p.falconRng.toDouble())
        },
    )

    // ---------------------------------------------------------------- geometry

    /** How far [p] is from the nearest leg of [route], in feet; null with no route. */
    fun distanceToRoute(p: Pt, route: List<Pt>): Double? {
        if (route.isEmpty()) return null
        if (route.size == 1) return p.dist(route[0])
        var best = Double.MAX_VALUE
        for (i in 0 until route.size - 1) best = min(best, segment(p, route[i], route[i + 1]))
        return best
    }

    private fun segment(p: Pt, a: Pt, b: Pt): Double {
        val dn = b.north - a.north
        val de = b.east - a.east
        val l2 = dn * dn + de * de
        if (l2 == 0.0) return p.dist(a)
        val t = (((p.north - a.north) * dn + (p.east - a.east) * de) / l2).coerceIn(0.0, 1.0)
        return hypot(p.north - (a.north + t * dn), p.east - (a.east + t * de))
    }

    /** At most [n] of [pts], the first and last always among them, evenly picked between. */
    fun thin(pts: List<Pt>, n: Int): List<Pt> {
        if (pts.size <= n) return pts
        return (0 until n).map { k -> pts[((pts.size - 1) * k.toDouble() / (n - 1)).roundToInt()] }
    }

    /** "3.2 nm" */
    fun nm(ft: Double): String = DataCardNet.fmt(ft / NM, "0.0") + " nm"

    // ---------------------------------------------------------------- lines

    enum class LineKind { TANKER, AWACS, JSTARS, CAP, ROUTE }

    data class LineOption(
        /** what the pilot reads: "Copper2 tanker track" */
        val label: String,
        /** what fits the Change Area list: "Copper2 track" */
        val short: String,
        val kind: LineKind,
        /** 2 to 6 points, in the order the line joins them */
        val points: List<Pt>,
        /** where it comes from, for the question and the tab */
        val from: String,
    )

    private fun roleKind(role: String): LineKind = when {
        role.contains("tank", true) -> LineKind.TANKER
        role.contains("jstar", true) -> LineKind.JSTARS
        else -> LineKind.AWACS
    }

    private fun roleWord(role: String): String = when (roleKind(role)) {
        LineKind.TANKER -> "tanker"
        LineKind.JSTARS -> "JSTARS"
        else -> "AWACS"
    }

    /** The flight plan's CAP stations: runs of consecutive CAP steerpoints ("CAP", action 12; CASCAP 9). */
    fun capRuns(route: List<RoutePoint>): List<List<RoutePoint>> {
        val out = ArrayList<List<RoutePoint>>()
        var run = ArrayList<RoutePoint>()
        fun isCap(p: RoutePoint) = p.action == 12 || p.action == 9 || p.name?.trim()?.let { CAP_WORD.matches(it) } == true
        for (p in route.sortedBy { it.n }) {
            if (isCap(p) && (run.isEmpty() || p.n == run.last().n + 1)) run += p
            else {
                if (run.isNotEmpty()) out += run
                run = if (isCap(p)) arrayListOf(p) else ArrayList()
            }
        }
        if (run.isNotEmpty()) out += run
        return out
    }

    private val CAP_WORD = Regex("^(CAP|CASCAP|BARCAP|TARCAP|HAVCAP|RESCAP)\\b.*", RegexOption.IGNORE_CASE)

    private fun stptRange(a: Int, b: Int) = if (a == b) "STPT $a" else "STPT $a–$b"

    /**
     * The leg [s] holds on: the first and the last of the points the campaign marks as its station (WDP's station leg,
     * the tanker's first refuelling waypoint and the next), or null when it marks none or they stand on one spot.
     */
    fun stationLeg(s: Support): Pair<Pt, Pt>? {
        val on = s.track.filter { it.station && it.at.placed }.map { it.at }
        if (on.size < 2 || on.first().dist(on.last()) < 100.0) return null
        return on.first() to on.last()
    }

    /**
     * What the four lines can be laid along: each tanker's, AWACS's and JSTARS's planned track — its station leg as
     * WDP lays a tanker track into a line (`fclsMain.lsbTanker_Click`, `fclsChangeLine.FillTanker`): the box 30,000 ft
     * either side of the leg, four corners and the first again, so the HSD draws it closed, the box the maps draw
     * ([PlannerIntel.orbitBox]); its route where the campaign marks no station —, the flight's CAP stations, and the
     * flight plan in stretches of six steerpoints, each sharing its first point with the last of the one before so they
     * join on the HSD.
     */
    fun lineOptions(f: Facts): List<LineOption> {
        val out = ArrayList<LineOption>()
        for (s in f.support) {
            val leg = stationLeg(s)
            if (leg != null) {
                out += LineOption(
                    "${s.callsign} ${roleWord(s.role)} track", "${s.callsign} track".take(16), roleKind(s.role),
                    PlannerIntel.orbitBox(leg.first, leg.second),
                    "its station leg (the campaign's), boxed 30,000 ft either side as WDP lays a tanker track",
                )
                continue
            }
            val pts = thin(s.track.map { it.at }.filter { it.placed }, 6)
            if (pts.size < 2 || pts.all { it.dist(pts[0]) < 100.0 }) continue
            out += LineOption(
                "${s.callsign} ${roleWord(s.role)} track", "${s.callsign} track".take(16), roleKind(s.role), pts,
                "its planned route (the campaign's)",
            )
        }
        for (run in capRuns(f.route)) {
            if (run.size < 2) continue
            val a = run.first().n
            val b = run.last().n
            out += LineOption("CAP station, ${stptRange(a, b)}", "CAP $a-$b", LineKind.CAP, thin(run.map { it.at }, 6), "your flight plan's CAP steerpoints")
        }
        val r = f.route.sortedBy { it.n }
        if (r.size >= 2) {
            var i = 0
            while (true) {
                val w = r.subList(i, min(i + 6, r.size))
                out += LineOption("Route, ${stptRange(w.first().n, w.last().n)}", "Route ${w.first().n}-${w.last().n}", LineKind.ROUTE, w.map { it.at }, "your flight plan")
                if (i + 6 >= r.size) break
                i += 5
            }
        }
        // unique short names, as the list shows them
        val seen = HashMap<String, Int>()
        return out.map { o ->
            val k = (seen[o.short] ?: 0) + 1
            seen[o.short] = k
            if (k == 1) o else o.copy(short = "${o.short} ($k)")
        }
    }

    /** The points of line [line] (1-4) as the cartridge holds them, up to its last placed point. */
    fun linePoints(m: DtcModel, line: Int): List<Pt> {
        val pts = (0 until 6).map { k -> m.line[(line - 1) * 6 + k].let { Pt(it.falconY.toDouble(), it.falconX.toDouble()) } }
        val last = pts.indexOfLast { it.placed }
        return if (last < 0) emptyList() else pts.subList(0, last + 1)
    }

    fun lineEmpty(m: DtcModel, line: Int): Boolean = linePoints(m, line).isEmpty()

    /** The lines that hold nothing, 1-4. */
    fun freeLines(m: DtcModel): List<Int> = (1..4).filter { lineEmpty(m, it) }

    /** The option line [line] matches (every point within 50 ft), for the tab's "Line 1: Copper2 tanker track". */
    fun recognise(m: DtcModel, line: Int, options: List<LineOption>): LineOption? {
        val have = linePoints(m, line)
        if (have.isEmpty()) return null
        return options.firstOrNull { o -> o.points.size == have.size && o.points.indices.all { o.points[it].dist(have[it]) < 50.0 } }
    }

    /** Line [line] (1-4) laid along [pts] (the first six), as Change Line STPT sets a point: elevation 0. */
    fun writeLine(m: DtcModel, line: Int, pts: List<Pt>) {
        require(line in 1..4) { "line $line" }
        for (k in 0 until 6) {
            val l = m.line[(line - 1) * 6 + k]
            val p = pts.getOrNull(k)
            l.falconY = p?.north?.toFloat() ?: 0f
            l.falconX = p?.east?.toFloat() ?: 0f
            l.falconZ = 0f
        }
    }

    /**
     * The lines an option would go to: the free ones in order, one per option; with [useAll], the occupied lines
     * after them (named in the question first). Pairs of line number and option; what does not fit is left out.
     */
    fun planLines(m: DtcModel, options: List<LineOption>, useAll: Boolean = false): List<Pair<Int, LineOption>> {
        val free = freeLines(m)
        val slots = if (useAll) free + (1..4).filter { it !in free } else free
        return options.take(slots.size).mapIndexed { i, o -> slots[i] to o }
    }

    // ---------------------------------------------------------------- PPTs

    enum class PptKind { THREAT, TANKER, AWACS, JSTARS, CAP }

    data class PptOption(
        /** what the pilot reads: "SA-6", "Copper2 (tanker)" */
        val label: String,
        val kind: PptKind,
        /** the PPT type: its code, its name and range (feet) in the table */
        val code: String,
        val typeName: String,
        val rangeFt: Double,
        val at: Pt,
        val elevFt: Double,
        /** where it comes from: "the save's known sites", "the Tacview feed", "its planned track (the campaign's)" */
        val from: String,
        /** how far from the flight plan, feet (threats) */
        val fromRouteFt: Double? = null,
    ) {
        val marker: Boolean get() = rangeFt < 100.0

        /** How far the ring's edge is from the flight plan, feet; 0 or less where the route crosses the ring. */
        val edgeFt: Double? get() = fromRouteFt?.let { it - rangeFt }
    }

    data class PptOptions(
        val options: List<PptOption>,
        /** sites whose system the PPT table has no type for, by name */
        val untyped: List<String>,
        /** markers the table has no type for ("TANKER") */
        val noMarker: List<String>,
        /** options left out because the cartridge already has that PPT, by slot */
        val already: List<Int>,
    )

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun words(s: String): List<String> = s.split(Regex("[\\s(),/]+")).map(::norm).filter { it.isNotEmpty() }

    /**
     * The PPT type of a site in [table] (code, name, range), by what the site may be called: a name equal to a type's
     * ("SA-6" is "SA-6"); the first word the same, when it has a digit or four letters or more ("SA-6 Gainful" is
     * "SA-6", "Patriot PAC-2" is "Patriot", never "SA-2" for "SA-20"); any word with a digit the same ("2S6
     * Tunguska" is "SA-19 (2S6)"); and a site the reference calls AAA or MANPADS is the table's AAA or ManPads. Null
     * when the table has no such type: an invented ring is worse than none.
     */
    fun threatType(table: List<Triple<String, String, Double>>, site: Site): Triple<String, String, Double>? {
        val types = table.filter { it.first.isNotBlank() && !it.first.startsWith("-") && it.second.isNotBlank() && it.third >= 100.0 }
        val names = site.names.filter { it.isNotBlank() }
        for (n in names) types.firstOrNull { norm(it.second) == norm(n) }?.let { return it }
        for (n in names) {
            val w = words(n).firstOrNull() ?: continue
            if (!(w.any { it.isDigit() } || w.length >= 4)) continue
            types.firstOrNull { t -> words(t.second).firstOrNull() == w }?.let { return it }
        }
        for (n in names) {
            val ws = words(n).filter { w -> w.any { it.isDigit() } && w.any { it.isLetter() } }
            if (ws.isEmpty()) continue
            types.firstOrNull { t -> words(t.second).any { it in ws } }?.let { return it }
        }
        return when (site.category?.uppercase()) {
            "AAA" -> types.firstOrNull { norm(it.second) == "aaa" || norm(it.first) == "aaa" }
            "MANPADS" -> types.firstOrNull { norm(it.second).startsWith("manpad") || norm(it.first) == "mnp" }
            else -> null
        }
    }

    /**
     * The table's marker for a kind: BMS's PPT tables name them ("TANKER" TNK, "AWACS" AWC, "J-STARS" JST,
     * "Combat Air Patrol" CAP), each with a range of a tenth of a foot, which the HSD draws as a point with no ring.
     */
    fun markerType(table: List<Triple<String, String, Double>>, kind: PptKind): Triple<String, String, Double>? {
        val (codes, names) = when (kind) {
            PptKind.TANKER -> listOf("tnk") to listOf("tanker")
            PptKind.AWACS -> listOf("awc") to listOf("awacs")
            PptKind.JSTARS -> listOf("jst") to listOf("jstars")
            PptKind.CAP -> listOf("cap") to listOf("combatairpatrol", "cap")
            PptKind.THREAT -> return null
        }
        return table.firstOrNull { norm(it.second) in names } ?: table.firstOrNull { norm(it.first) in codes }
    }

    private fun pptKind(role: String): PptKind = when (roleKind(role)) {
        LineKind.TANKER -> PptKind.TANKER
        LineKind.JSTARS -> PptKind.JSTARS
        else -> PptKind.AWACS
    }

    /**
     * What the PPTs can be: the support stations and the flight's CAP as markers first (a few, and a pilot wants
     * them), then every known air-defence site the table has a type for, the rings that reach the flight plan first
     * ([PptOption.edgeFt]: one the route crosses, then by how far the ring's edge is from it). A site the
     * cartridge already has (the same type within 3,000 ft) and a marker already placed are left out, so pressing
     * twice places nothing twice. [table] is the PPT tab's table (the theater's own `Ppt.ini` unless the pilot chose
     * another); [model] the cartridge on the page, or null.
     */
    fun pptOptions(f: Facts, table: List<Triple<String, String, Double>>, model: DtcModel?): PptOptions {
        val out = ArrayList<PptOption>()
        val noMarker = LinkedHashSet<String>()
        for (s in f.support) {
            val at = s.station ?: continue
            val kind = pptKind(s.role)
            val t = markerType(table, kind)
            if (t == null) { noMarker += kind.name; continue }
            out += PptOption("${s.callsign} (${roleWord(s.role)})", kind, t.first, t.second, t.third, at, 0.0, s.stationFrom ?: "its station")
        }
        for (run in capRuns(f.route)) {
            val t = markerType(table, PptKind.CAP)
            if (t == null) { noMarker += "CAP"; break }
            val at = Pt(run.map { it.at.north }.average(), run.map { it.at.east }.average())
            out += PptOption("Your CAP station (${stptRange(run.first().n, run.last().n)})", PptKind.CAP, t.first, t.second, t.third, at, 0.0, "your flight plan")
        }
        val route = f.route.sortedBy { it.n }.map { it.at }
        val untyped = LinkedHashSet<String>()
        val threats = ArrayList<PptOption>()
        for (site in f.sites) {
            val t = threatType(table, site)
            if (t == null) { untyped += site.name; continue }
            // one ring per site: launchers of one battery the feed or the save lists apart are one PPT
            if (threats.any { it.code == t.first && it.at.dist(site.at) < 3000.0 }) continue
            threats += PptOption(t.second, PptKind.THREAT, t.first, t.second, t.third, site.at, site.elevFt, site.source, distanceToRoute(site.at, route))
        }
        // the rings that reach the route first: a ring the route crosses, then by how far its edge is from the route
        // (an SA-5 80 nm off with a 54 nm ring before an SA-2 60 nm off with an 18 nm one)
        out += threats.sortedBy { t -> t.edgeFt ?: Double.MAX_VALUE }
        val already = ArrayList<Int>()
        val kept = out.filter { o ->
            val slot = model?.let { m ->
                (0 until 15).firstOrNull { i ->
                    val p = m.ppt[i]
                    val at = Pt(p.falconY.toDouble(), p.falconX.toDouble())
                    at.placed && p.code.orEmpty().trim().equals(o.code, true) && at.dist(o.at) < 3000.0
                }
            }
            if (slot != null) already += 56 + slot
            slot == null
        }
        return PptOptions(kept, untyped.toList(), noMarker.toList(), already.distinct().sorted())
    }

    fun pptEmpty(m: DtcModel, slot: Int): Boolean = m.ppt[slot - 56].let { it.falconX == 0f && it.falconY == 0f }

    fun freePpts(m: DtcModel): List<Int> = (56..70).filter { pptEmpty(m, it) }

    /** Where each option would go, and which placed PPTs that replaces. */
    data class PptPlan(val placements: List<Pair<Int, PptOption>>, val replaced: List<Int>, val left: List<PptOption>)

    /**
     * The options into the free PPTs, in order; with [useAll], into occupied ones too once the free ones are used —
     * from PPT 70 down, so the pilot's own low numbers are the last to go. What does not fit is [PptPlan.left].
     */
    /**
     * [options] with one threat per site: a second ring of the same type within 3,000 ft of one already kept is left
     * out, as [pptOptions] does. Several sites of one battery (the save's units, the feed's launchers, the briefing's
     * words) all point at the same option, and the Map page's "Add the rings your route crosses" once put one SA-19
     * into three PPTs. Markers are kept as they are.
     */
    fun onePerSite(options: List<PptOption>): List<PptOption> {
        val out = ArrayList<PptOption>()
        for (o in options) {
            if (!o.marker && out.any { !it.marker && it.code.equals(o.code, true) && it.at.dist(o.at) < 3000.0 }) continue
            out += o
        }
        return out
    }

    fun planPpts(m: DtcModel, options: List<PptOption>, useAll: Boolean = false): PptPlan {
        val free = freePpts(m)
        val slots = if (useAll) free + (70 downTo 56).filter { it !in free } else free
        val n = min(slots.size, options.size)
        val placements = (0 until n).map { slots[it] to options[it] }.sortedBy { it.first }
        return PptPlan(placements, placements.map { it.first }.filter { it !in free }.sorted(), options.drop(n))
    }

    /** PPT [slot] (56-70) set to [o], as Change PPT sets one: position, elevation, the type's name, code and range. */
    fun writePpt(m: DtcModel, slot: Int, o: PptOption) {
        require(slot in 56..70) { "PPT $slot" }
        val p = m.ppt[slot - 56]
        p.falconY = o.at.north.toFloat()
        p.falconX = o.at.east.toFloat()
        p.falconZ = -abs(o.elevFt.toFloat())
        p.name = o.typeName
        p.code = o.code
        p.falconRng = o.rangeFt.toFloat()
    }

    // ---------------------------------------------------------------- steerpoints, targets, open steerpoints

    fun stptEmpty(s: DtcStpt): Boolean = s.falconX == 0f && s.falconY == 0f

    /**
     * The flight plan's steerpoints whose slot (1-24) the cartridge leaves empty. Falcon BMS 4.38.1 keeps the route in
     * the mission file and writes the cartridge's route slots as 0,0,0, so the DTC page's STPT tab is empty for a
     * route the jet flies; these fill it. A slot the cartridge places (BMS's Recon, the pilot's own edit) is never
     * among them.
     */
    fun routeFill(m: DtcModel, route: List<RoutePoint>): List<RoutePoint> =
        route.filter { it.n in 1..24 && it.at.placed && stptEmpty(m.stpt[it.n - 1]) }.sortedBy { it.n }

    /**
     * A steerpoint set, as the Change STPT window sets one: position, elevation ([elevFt]; the route's altitude for a
     * route point), action ([action]: -1 is Precision, which BMS takes over its own; a flight-plan action only shows
     * the route here) and name.
     */
    fun writeStpt(s: DtcStpt, at: Pt, elevFt: Double, action: Int, name: String?) {
        s.falconY = at.north.toFloat()
        s.falconX = at.east.toFloat()
        s.falconZ = -abs(elevFt.toFloat())
        s.action = action
        s.target = name?.trim()?.take(60)?.ifEmpty { null } ?: s.target
    }

    /** The first empty slots of [table] (from its first), one per place not already in the table (within 300 ft). */
    fun openFill(table: Array<DtcStpt>, places: List<Place>): List<Pair<Int, Place>> {
        val have = table.filter { !stptEmpty(it) }.map { Pt(it.falconY.toDouble(), it.falconX.toDouble()) }
        val todo = places.filter { p -> p.at.placed && have.none { it.dist(p.at) < 300.0 } }.distinctBy { (it.at.north / 300).roundToInt() to (it.at.east / 300).roundToInt() }
        val free = table.indices.filter { stptEmpty(table[it]) }
        return todo.take(free.size).mapIndexed { i, p -> free[i] to p }
    }

    fun targetEmpty(t: DtcTgt): Boolean = t.falconX == 0f && t.falconY == 0f

    /** The target list's empty rows (0-based) for each place not already in the list (within 300 ft), in order. */
    fun targetFill(m: DtcModel, places: List<Place>): List<Pair<Int, Place>> {
        val have = (0 until 100).map { m.tgt[it] }.filter { !targetEmpty(it) }.map { Pt(it.falconY.toDouble(), it.falconX.toDouble()) }
        val todo = places.filter { p -> p.at.placed && have.none { it.dist(p.at) < 300.0 } }.distinctBy { (it.at.north / 300).roundToInt() to (it.at.east / 300).roundToInt() }
        val free = (0 until 100).filter { targetEmpty(m.tgt[it]) }
        return todo.take(free.size).mapIndexed { i, p -> free[i] to p }
    }

    /**
     * A target of the list set, as Select target sets one: position, elevation and the name (60 characters). The
     * elevation goes in as Falcon BMS writes a `wpntarget_`'s z, pointing down (a target 1913 ft up is `-1913.301758`),
     * as [writeStpt] writes a steerpoint's.
     */
    fun writeTarget(t: DtcTgt, p: Place) {
        t.falconY = p.at.north.toFloat()
        t.falconX = p.at.east.toFloat()
        t.falconZ = -abs((p.elevFt ?: 0.0).toFloat())
        t.target = p.name.take(60)
    }

    // ---------------------------------------------------------------- HARM, laser, TACAN

    /**
     * The HARM systems for a table (five at most): each system the mission names — the briefing's, then the known
     * sites' nearest the route first — as the HARM list names the whole system ("SA-6"), in that order, once each.
     */
    fun harmPicks(f: Facts, codes: List<DtcHarmCode>): List<DtcHarmCode> {
        val route = f.route.sortedBy { it.n }.map { it.at }
        val names = f.briefedSystems + f.sites.sortedBy { distanceToRoute(it.at, route) ?: Double.MAX_VALUE }.flatMap { it.names.take(1) }
        val out = ArrayList<DtcHarmCode>()
        for (n in names) {
            val k = norm(n)
            val w = words(n).firstOrNull()
            val hit = codes.firstOrNull { norm(it.label) == k } ?: w?.takeIf { it.any { c -> c.isDigit() } || it.length >= 4 }?.let { first ->
                codes.firstOrNull { c -> norm(c.label) == first }
            }
            if (hit != null && out.none { it.code == hit.code }) out += hit
            if (out.size == 5) break
        }
        return out
    }

    /**
     * The laser codes for the seat planned: its own jet's for the targeting pod, and for the laser spot tracker the
     * other jet of its pair's (#1 and #2, #3 and #4), whose spot it looks for in a buddy lase. Nulls where the flight
     * gives none.
     */
    fun laserPair(f: Facts): Pair<Int?, Int?> {
        val own = f.laser.getOrNull(f.seat)?.takeIf { it in 1111..2888 }
        val mate = f.laser.getOrNull(f.seat xor 1)?.takeIf { it in 1111..2888 }
        return own to mate
    }

    /** The air-to-air channel a receiver sets to meet a tanker on [channel]: 63 apart, as BMS pairs them. */
    fun tieOn(channel: Int): Int = if (channel > 63) channel - 63 else channel + 63
}
