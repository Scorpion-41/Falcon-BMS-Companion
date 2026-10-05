package com.bmscompanion.app.data.mission

import com.bmscompanion.app.data.wdp.PlannerIntel
import kotlinx.serialization.Serializable
import kotlin.math.hypot

// One mission picture for every map (docs/DATA-STORES.md, "One mission picture"): the Mission section's map, the VR
// map board and the Planner's Map page all draw, by default, the same things of the mission — its route, its tanker
// and AWACS boxes, its threats, its airfields and its bullseye. What differs is only where it comes from: EZBoards mode
// takes it from BMS's files after PRINT (the printed briefing, the cartridge, BMS's mission file and the save BMS is
// flying), WDP mode from the snapshot Populate from Planner took, and the Planner's Map page from the mission it has
// open. This file is the part every side shares: the save's threat picture of one mission ([MissionGround]) and the
// rules that make it.
//
// **Only the mission's threats, and only what the side knows.** A theater holds a hundred and more air-defence sites;
// the maps draw by default only those the mission's briefing names (BMS's Threat Analysis, "Known or suspected enemy
// air defenses along your flight path include: …", [briefedThreats]) — or, where there is no printed briefing of the
// flight (WDP mode: a save's briefing has no threat section), the spotted ones whose ring reaches the route
// ([alongRoute], the same idea) — and the cartridge's own PPTs. A site the flight's side has not spotted is never sent
// to a device at all, and a live air defence of the Tacview feed (which streams every one in the theater) is drawn only
// where it is one of those ([knownSite]). No ground units: they were drawn in the 1.3.8 test builds and buried the map.

/**
 * The threat picture of one mission, as the maps draw it by default.
 *
 * [airDefences] are the mission's air-defence sites: the ones the printed briefing's Threat Analysis names, each found
 * in the save where it can be ([threatsFrom] "briefing"), else the enemy's spotted sites whose ring comes within
 * [MissionPicture.ROUTE_MARGIN_NM] of the route ("route"). [ships] are the enemy's spotted task forces that near the
 * route. [units], [targets], [packageRoutes] and [packageFlights] are no longer filled (1.3.8 test builds drew them).
 * [save]/[flight] name what it was read from ("Auto Save.cam", "1234/1"; empty when the save does not provably hold the
 * printed flight and only the briefing's threats could be placed).
 */
@Serializable
data class MissionGround(
    val theater: String = "",
    val save: String = "",
    val flight: String = "",
    val callsign: String? = null,
    val airDefences: List<CampSite> = emptyList(),
    val ships: List<CampSite> = emptyList(),
    val units: List<CampMapUnit> = emptyList(),
    val targets: List<Int> = emptyList(),
    val packageRoutes: List<CampPackageRoute> = emptyList(),
    val packageFlights: List<CampFlightRow> = emptyList(),
    /**
     * Each air-defence system's reach in feet, as the Planner's Map page rings it: the theater's own `Ppt.ini` range
     * where it has the type, else the threat reference's (`siteRingFt`). Filled by the PC, which has the theater's table.
     */
    val rings: Map<String, Double> = emptyMap(),
    /** the save header's bullseye (north and east feet, as [CampFlight.bullseyeX]); null when it sets none */
    val bullseyeX: Double? = null,
    val bullseyeY: Double? = null,
    /** where [airDefences] come from: "briefing" (the printed Threat Analysis), "route" (spotted, near the route); null before 1.3.8's last builds */
    val threatsFrom: String? = null,
)

object MissionPicture {
    /**
     * How close a spotted site's ring must come to the route for it to be the mission's where no printed briefing says
     * which are ("along your flight path"); a site whose reach is not known counts from [UNKNOWN_RING_NM].
     */
    const val ROUTE_MARGIN_NM = 3.0
    const val UNKNOWN_RING_NM = 10.0

    /** How near a live air defence must be to a known site of its system to be that site ([knownSite]). */
    const val LIVE_MATCH_NM = 2.0

    private const val NM = 6076.12

    // ---------------------------------------------------------------- the printed briefing's Threat Analysis

    /**
     * One row of the printed briefing's surface-to-air threats (`Threat.b`: Strings 185, the vehicle's name, 235
     * " missile launchers" or 236 " anti-aircraft guns", then `SPECIFIC_LOCATION`): "SA-19 (2K22)missile launchers 2 nm
     * west of Buk-myeon" is [system] "SA-19 (2K22)", [where] "2 nm west of Buk-myeon" — [nm] 2, [compass] "west",
     * [place] "Buk-myeon"; "near Tirana" has no distance or compass.
     */
    data class BriefedThreat(
        val system: String, val guns: Boolean, val where: String, val nm: Int?, val compass: String?, val place: String?,
    )

    private val THREAT_ROW = Regex("""^(.+?)\s*(missile launchers|anti-aircraft guns)\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val NM_OF = Regex("""^(\d+)\s*nm\s+([A-Za-z\-]+)\s+of\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val NEAR = Regex("""^near\s+(.+)$""", RegexOption.IGNORE_CASE)

    /** The threat rows of [b]'s Threat Analysis, in the order BMS printed them; empty for none (or no such section). */
    fun briefedThreats(b: Briefing?): List<BriefedThreat> = b?.threats.orEmpty().flatMap { it.lines }.mapNotNull { line ->
        val m = THREAT_ROW.find(line.trim().removePrefix("--").trim()) ?: return@mapNotNull null
        val system = m.groupValues[1].trim()
        if (system.isEmpty()) return@mapNotNull null
        val where = m.groupValues[3].trim().trimEnd('.')
        val far = NM_OF.find(where)
        val near = if (far == null) NEAR.find(where) else null
        BriefedThreat(
            system = system, guns = m.groupValues[2].startsWith("anti", ignoreCase = true), where = where,
            nm = far?.groupValues?.get(1)?.toIntOrNull(), compass = far?.groupValues?.get(2)?.lowercase(),
            place = (far?.groupValues?.get(3) ?: near?.groupValues?.get(1))?.trim(),
        )
    }

    /** Whether [b] has a Threat Analysis at all (a print that says "There are no known enemy air defense assets…" has one, with no rows). */
    fun hasThreatAnalysis(b: Briefing?): Boolean = b?.threats?.isNotEmpty() == true

    /** The compass words of a briefing (Strings 30-37) as true bearings. */
    val COMPASS = mapOf(
        "north" to 0.0, "northeast" to 45.0, "east" to 90.0, "southeast" to 135.0,
        "south" to 180.0, "southwest" to 225.0, "west" to 270.0, "northwest" to 315.0,
    )

    // ---------------------------------------------------------------- which sites are the mission's

    /** "SA-19 (2K22)" → "sa19": a system's first word without its punctuation, what two names of one system share. */
    fun systemKey(name: String?): String = name.orEmpty().trim().substringBefore(' ').substringBefore('(').lowercase().filter { it.isLetterOrDigit() }

    /** Whether two names are one system: the same first word ("SA-19 (2K22)", "SA-19 Grison TEL"), or one empty. */
    fun sameSystem(a: String?, b: String?): Boolean {
        val ka = systemKey(a)
        val kb = systemKey(b)
        return ka.isEmpty() || kb.isEmpty() || ka == kb
    }

    /**
     * The sites of [spotted] whose ring ([ringOf], feet; [UNKNOWN_RING_NM] where not known) comes within [marginNm] of
     * the polyline [route] (feet): what is "along your flight path" where no printed briefing lists it.
     */
    fun alongRoute(spotted: List<CampSite>, route: List<Pair<Double, Double>>, ringOf: (String) -> Double?, marginNm: Double = ROUTE_MARGIN_NM): List<CampSite> {
        if (route.isEmpty()) return emptyList()
        return spotted.filter { s ->
            val ring = runCatching { ringOf(s.system) }.getOrNull() ?: (UNKNOWN_RING_NM * NM)
            distanceToRoute(s.x, s.y, route) - ring <= marginNm * NM
        }
    }

    /**
     * Whether a live air defence [name] at ([x], [y]) is one of the [known] sites (theirs and the cartridge's PPTs): the
     * same system within [nm]. Anything else the feed streams is not the pilot's to see.
     */
    fun knownSite(name: String?, x: Double, y: Double, known: List<CampSite>, nm: Double = LIVE_MATCH_NM): Boolean {
        val reach = nm * NM
        return known.any { k -> hypot(k.x - x, k.y - y) <= reach && sameSystem(k.system, name) }
    }

    /** The distance (ft) from (x, y) to the polyline [route]; MAX_VALUE for no route. */
    fun distanceToRoute(x: Double, y: Double, route: List<Pair<Double, Double>>): Double {
        if (route.isEmpty()) return Double.MAX_VALUE
        if (route.size == 1) return hypot(x - route[0].first, y - route[0].second)
        var best = Double.MAX_VALUE
        for (i in 1 until route.size) {
            val (ax, ay) = route[i - 1]
            val (bx, by) = route[i]
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(x - (ax + t * dx), y - (ay + t * dy))
            if (d < best) best = d
        }
        return best
    }

    /** [flight]'s route as the maps join it (feet, north and east), points with no position left out. */
    fun routeOf(flight: CampFlight?): List<Pair<Double, Double>> =
        flight?.route.orEmpty().filter { it.x != 0.0 || it.y != 0.0 }.map { it.x to it.y }

    /**
     * Every enemy air-defence site of the save the flight's side has seen: the map intel's threats
     * ([PlannerIntel.threats]: hostile, not destroyed, seen), else the flight's spotted sites. Never an unspotted one.
     */
    fun spotted(flight: CampFlight?, intel: CampMapIntel?): List<CampSite> =
        intel?.let { i ->
            PlannerIntel.threats(i, emptySet()).map { u -> CampSite(system = PlannerIntel.threatType(u), name = u.name, x = u.x, y = u.y, spotted = true) }
        } ?: flight?.airDefences.orEmpty().filter { it.spotted }

    /**
     * The threat picture of [flight] (read from [save] of [theater]) with the save's map intel [intel] (null when the
     * PC has none: the flight's spotted sites stand in). [briefed]: the sites the printed briefing names, already
     * placed (null where there is no printed briefing of this flight: the spotted sites near the route are taken).
     * [ringOf] a system's reach in feet.
     */
    fun ground(
        flight: CampFlight, intel: CampMapIntel?, theater: String, save: String, flightId: String,
        briefed: List<CampSite>?, ringOf: (String) -> Double?,
    ): MissionGround {
        val route = routeOf(flight)
        return MissionGround(
            theater = theater, save = save, flight = flightId, callsign = flight.row.callsign.trim().takeIf { it.isNotEmpty() },
            airDefences = briefed ?: alongRoute(spotted(flight, intel), route, ringOf),
            ships = alongRoute(flight.ships.filter { it.spotted }, route, ringOf),
            bullseyeX = flight.bullseyeX,
            bullseyeY = flight.bullseyeY,
            threatsFrom = if (briefed != null) "briefing" else "route",
        )
    }

    /** One air-defence site as a map draws it: its system's reach in feet ([ringFt], null when unknown). */
    data class Site(val system: String, val name: String?, val x: Double, val y: Double, val ringFt: Double?, val ship: Boolean = false)

    /** [g]'s air defences and ships as [Site]s, each system's reach from [ringOf] (feet). */
    fun sites(g: MissionGround?, ringOf: (String) -> Double?): List<Site> {
        if (g == null) return emptyList()
        val cache = HashMap<String, Double?>()
        fun ring(s: String) = cache.getOrPut(s) { runCatching { ringOf(s) }.getOrNull() }
        return g.airDefences.map { Site(it.system, it.name, it.x, it.y, ring(it.system)) } +
            g.ships.map { Site(it.system, it.name, it.x, it.y, ring(it.system), ship = true) }
    }
}
