package com.bmscompanion.app.ui.screens.wdp

import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.AirportSet
import com.bmscompanion.app.data.GeoLayers
import com.bmscompanion.app.data.Threat
import com.bmscompanion.app.data.mission.Contact
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.SupportTrack
import com.bmscompanion.app.data.wdp.DtcFromMission
import com.bmscompanion.app.data.wdp.DtcFromMission.Place
import com.bmscompanion.app.data.wdp.DtcFromMission.Pt
import com.bmscompanion.app.ui.screens.mission.briefedSystems
import com.bmscompanion.app.ui.screens.mission.samSites
import com.bmscompanion.app.ui.screens.mission.stationFromNotes
import com.bmscompanion.app.ui.screens.mission.supportAssets
import com.bmscompanion.app.ui.screens.mission.supportRole
import com.bmscompanion.app.ui.screens.mission.threatGuideEntry
import com.bmscompanion.app.ui.screens.mission.trackStation
import kotlin.math.roundToInt

/**
 * What the app knows of the mission the Planner plans, gathered for the DTC's **From mission…** ([DtcFromMission]):
 * the same flight plan, tracks, stations, threats and airbases the Mission views and the other Planner pages use, so a
 * line or a PPT put into the cartridge from here is where the map and the Support card put it.
 *
 * - **The flight plan**: the Planner's steerpoints ([WdpMission.slots]: the cartridge's, BMS's route, the save's),
 *   without the precision targets past the route (BMS's Recon puts those in the cartridge's later slots).
 * - **Tankers and AWACS**: the campaign's planned tracks — the mission BMS is flying ([MissionData.tracks]) and the
 *   package of a flight opened from a save ([com.bmscompanion.app.data.mission.CampFlight.support]) — and where each
 *   will be: the middle of the legs it holds on, else your route's Refuel steerpoint for the one tanker, else the
 *   briefing's sentence ("21 nm southwest of Yongin-si City"), as the map places them. TACANs as the Support card has them.
 * - **Air defences**: the save's known sites ([saveSites]) and, while BMS is flying, the hostile sites the Tacview
 *   feed reports whose system the briefing names (the map's rule: what the briefing did not name is not the pilot's).
 *   Each is named and given a category by the threat reference ([siteOf]), which is how the PPT table types it.
 * - **Places** a point can be set to: the flight's targets, the steerpoints, the air defences, the stations, the
 *   airbases (the flight's own first).
 * - **The printed briefing** stands in for what a save's own briefing lacks — the threat section, the weather — when
 *   the flight opened from the save is the one BMS printed ([live]'s briefing), as it does for the comm ladder
 *   (`DtcWiring.commLadder`).
 */
internal object DtcMissionFacts {

    fun gather(
        mission: WdpMission,
        /** what the PC serves for the mission BMS is flying: its planned tracks (null where there is none) */
        live: MissionData?,
        /** the Tacview feed's contacts, when BMS is flying */
        contacts: List<Contact>,
        airports: List<Airport>,
        airportSet: AirportSet?,
        geo: GeoLayers?,
        /** the app's threat reference (`Repo.threats()`) */
        reference: List<Threat>,
    ): DtcFromMission.Facts {
        val notes = ArrayList<String>()
        val rows = mission.briefing?.steerpoints.orEmpty().associateBy { it.n }

        // ---- the flight plan
        val routed = mission.slots.filter { it.point.placed() && it.source != StptSource.DTC }.maxOfOrNull { it.point.n } ?: 0
        val route = mission.slots.filter { s ->
            val p = s.point
            p.placed() && p.n in 1..24 && (s.source != StptSource.DTC || p.action != -1 || p.n <= routed)
        }.map { s ->
            val p = s.point
            DtcFromMission.RoutePoint(p.n, Pt(p.x, p.y), p.altFt, p.action, rows[p.n]?.desc?.takeIf { it.isNotBlank() && it != "--" } ?: p.name, s.source.from)
        }

        // ---- tankers, AWACS, JSTARS
        // the station is WDP's leg where the save gives it (the first tanker or ELINT waypoint and the next), so the
        // line laid from it is the box the Map page draws about the same leg
        val saved = mission.flight?.support.orEmpty().filter { it.callsign.isNotBlank() }
            .map { s ->
                val pts = if (s.leg >= 0 && s.leg + 1 < s.track.size) s.track.mapIndexed { i, p -> p.copy(station = i == s.leg || i == s.leg + 1) } else s.track
                SupportTrack(role = s.role, callsign = s.callsign, yours = true, points = pts)
            }
        // the flight a save opened plans with its own package's tracks first; the mission BMS is flying with its own
        val flying = live?.tracks.orEmpty().takeIf { mission.flight == null || mission.flight.row.briefed }.orEmpty()
        // the channel the save gives a support flight (the tanker's own, "122Y"), for a flight opened from a save: it is
        // what BMS flies with, where the briefing rule (the first tanker 92Y) is only BMS's usual allocation. BMS's own
        // briefing prints the receiver's side of it ("Texaco4 (TCN: 059Y)"), as WDP's card does (59Y).
        val saveTacan = (mission.flight?.support.orEmpty() + mission.flight?.sideSupport.orEmpty())
            .filter { !it.tacan.isNullOrBlank() }.associate { norm(it.callsign) to it.tacan!! }
        fun tacanOf(call: String, a: com.bmscompanion.app.ui.screens.mission.SupportAsset?): Pair<String?, String?> {
            val own = saveTacan[norm(call)]
            val m = own?.let { TACAN.find(it) } ?: return a?.tacan to a?.tieOn
            val ch = m.groupValues[1].toInt()
            return "$ch${m.groupValues[2]}" to "${DtcFromMission.tieOn(ch)}${m.groupValues[2]}"
        }
        val tracks = (if (mission.flight != null) saved + flying else flying + saved).distinctBy { norm(it.callsign) }
        val b = mission.briefing
        // a save's own briefing has no threat section and no weather (only PRINT words them); for the flight BMS printed,
        // the printed briefing's are this flight's
        val printed = live?.briefing?.takeIf { mission.flight?.row?.briefed == true }
        val threatText = b?.takeIf { it.threats.isNotEmpty() } ?: printed ?: b
        val weather = if (CardWeather.column(b, 0) != null) b else printed ?: b
        fun place(name: String): Pair<Double, Double>? {
            fun one(n0: String): Pair<Double, Double>? {
                val n = n0.trim().lowercase()
                if (n.length < 3) return null
                airportSet?.airports?.firstOrNull { it.name.lowercase().startsWith(n) || it.icao?.lowercase() == n }?.let { return it.x to it.y }
                geo?.places?.firstOrNull { it.n.lowercase() == n }?.let { return it.x to it.y }
                return geo?.places?.firstOrNull { it.n.lowercase().startsWith(n) }?.let { it.x to it.y }
            }
            return one(name) ?: name.trim().substringBefore(' ').takeIf { it.length >= 3 }?.let { one(it) }
        }
        val refuel = route.firstOrNull { it.action == 4 }
        val entries = b?.support.orEmpty().mapNotNull { e ->
            val role = roleOf(e.role) ?: supportRole(e.aircraft) ?: return@mapNotNull null
            Triple(role, e.callsign.trim(), e.notes)
        }
        val tankers = entries.count { it.first == "Tanker" }
        val assets = runCatching { supportAssets(b, null, emptyList(), emptyList(), null, tracks = tracks) }.getOrDefault(emptyList())
        val support = ArrayList<DtcFromMission.Support>()
        for ((role, call, text) in entries) {
            val t = tracks.firstOrNull { norm(it.callsign) == norm(call) }
            val st = t?.let(::trackStation)
            val at: Pair<Pt?, String?> = when {
                st != null -> Pt(st.x, st.y) to "its planned track (the campaign's)"
                role == "Tanker" && tankers == 1 && refuel != null -> refuel.at to "your route's Refuel steerpoint ${refuel.n}"
                else -> stationFromNotes(text) { place(it) }?.let { (p, words) -> Pt(p.first, p.second) to "the briefing: $words" } ?: (null to null)
            }
            val a = assets.firstOrNull { norm(it.callsign) == norm(call) }
            support += DtcFromMission.Support(
                role, call, t?.points.orEmpty().map { DtcFromMission.TrackPoint(Pt(it.x, it.y), it.station) },
                at.first, at.second, tacanOf(call, a).first, tacanOf(call, a).second, yours = t?.yours == true,
            )
        }
        for (t in tracks) {
            val call = t.callsign ?: continue
            if (support.any { norm(it.callsign) == norm(call) }) continue
            val role = roleOf(t.role) ?: supportRole(t.role) ?: t.role.ifBlank { "Support" }
            val st = trackStation(t)
            val a = assets.firstOrNull { norm(it.callsign) == norm(call) }
            support += DtcFromMission.Support(
                role, call, t.points.map { DtcFromMission.TrackPoint(Pt(it.x, it.y), it.station) },
                st?.let { Pt(it.x, it.y) }, st?.let { "its planned track (the campaign's)" }, tacanOf(call, a).first, tacanOf(call, a).second, t.yours,
            )
        }
        support.sortBy { SUPPORT_ORDER.indexOf(it.role).let { i -> if (i < 0) 9 else i } }

        // ---- air defences
        val sites = ArrayList<DtcFromMission.Site>()
        for (s in saveSites(mission, reference)) sites += s
        val hostile = contacts.filter { it.hostile }
        if (hostile.any { it.kind == "sam" }) {
            val briefed = briefedSystems(MissionData(briefing = threatText), reference)
            for (s in samSites(hostile, reference)) {
                if (s.threatId == null || s.threatId !in briefed) continue
                val ref = reference.firstOrNull { it.id == s.threatId }
                if (sites.any { it.at.dist(Pt(s.x, s.y)) < 3000.0 }) continue
                sites += DtcFromMission.Site(listOfNotNull(ref?.name, s.label) + ref?.aliases.orEmpty(), Pt(s.x, s.y), "the Tacview feed", ref?.category)
            }
        }
        if (sites.isEmpty()) notes += NO_SITES
        val briefedNames = if (threatText == null) emptyList() else {
            val ids = briefedSystems(MissionData(briefing = threatText), reference)
            reference.filter { it.id in ids && it.category in AIR_DEFENCE }.map { it.name }
        }

        // ---- places
        val targets = ArrayList<Place>()
        val f = mission.flight
        if (f != null) {
            for ((i, w) in f.route.withIndex()) {
                val n = if (w.n > 0) w.n else i + 1
                // a waypoint's own target is one only where the flight attacks there: a take-off's or a landing's is its
                // airbase, a refuelling's the tanker, a CAP's none
                val mine = w.designated.getOrNull(mission.seat) ?: w.target?.takeIf { w.action in ATTACK_ACTIONS }
                if (mine?.x != null && mine.y != null) targets += Place(mine.name ?: "STPT $n target", Pt(mine.x, mine.y), null, YOUR_TARGETS)
                for ((seat, d) in w.designated.withIndex()) {
                    if (seat == mission.seat || d?.x == null || d.y == null) continue
                    targets += Place((d.name ?: "STPT $n target") + " (#${seat + 1})", Pt(d.x, d.y), null, YOUR_TARGETS)
                }
            }
        }
        // a flight-plan target's third figure is the ground's only where the cartridge placed it (BMS's Recon, the
        // pilot); from BMS's route or the save it is the altitude the flight plan flies there, not the target's
        for (c in mission.choices) {
            val ground = c.key.startsWith("WPN ") || mission.source(c.waypoint) == StptSource.DTC
            targets += Place(c.label, Pt(c.target.first, c.target.second), if (ground) kotlin.math.abs(c.target.third) else null, "Mission targets")
        }
        val places = ArrayList<Place>()
        places += targets
        for (p in route) places += Place("STPT ${p.n}" + (p.name?.let { " · $it" } ?: ""), p.at, null, "Steerpoints")
        for (s in sites) places += Place(s.name, s.at, s.elevFt.takeIf { it != 0.0 }, AIR_DEFENCES)
        for (s in support) s.station?.let { places += Place("${s.callsign} (${s.role}) station", it, null, STATIONS) }
        val bases = runCatching { missionBases(b, f) }.getOrNull()
        val own = listOfNotNull(
            bases?.departureIn(airportSet)?.let { it to "departure" },
            bases?.arrivalIn(airportSet)?.let { it to "arrival" },
            bases?.alternateIn(airportSet)?.let { it to "alternate" },
        ).distinctBy { it.first.id }
        fun onMap(a: Airport) = a.x != 0.0 || a.y != 0.0
        for ((a, what) in own) if (onMap(a)) places += Place("${a.name} ($what)", Pt(a.x, a.y), (a.elevationFt ?: 0).toDouble(), YOUR_BASES)
        for (a in airports.sortedBy { it.name }) if (onMap(a) && own.none { it.first.id == a.id }) places += Place(a.name, Pt(a.x, a.y), (a.elevationFt ?: 0).toDouble(), "Airbases")

        // ---- ships, for the Harpoon steerpoints
        // (the feed's while BMS is flying, which move; the save's where it gives them, as they were when it was saved)
        val live = contacts.filter { it.hostile && it.kind == "ship" }.map { Place(it.name ?: "Ship", Pt(it.x, it.y), 0.0, SHIPS) }
        val ships = live + saveShips(mission).filter { s -> live.none { it.at.dist(s.at) < 3 * DtcFromMission.NM } }

        // ---- TACAN and ILS
        // the departure field's first, then the tankers' (air to air, the channel a receiver sets), then the others'
        val tacans = ArrayList<DtcFromMission.TacanChoice>()
        val fields = own.mapNotNull { (a, what) ->
            a.tacan?.let { t ->
                DtcFromMission.TacanChoice(
                    "${a.name} ${t.channel}${t.band.uppercase()} ($what)", "${a.name.take(14)} ${t.channel}${t.band.uppercase()}",
                    t.channel, if (t.band.equals("Y", true)) 1 else 0, 0,
                )
            }
        }
        tacans += fields.take(1)
        for (s in support) {
            val m = TACAN.find(s.tacan.orEmpty()) ?: continue
            val ch = m.groupValues[1].toInt()
            if (ch !in 1..126) continue
            val tie = DtcFromMission.tieOn(ch)
            tacans += DtcFromMission.TacanChoice(
                "${s.callsign} (${s.role.lowercase()}) ${ch}${m.groupValues[2]}: set $tie${m.groupValues[2]} A/A TR", "${s.callsign.take(12)} $tie${m.groupValues[2]} A/A",
                tie, if (m.groupValues[2] == "Y") 1 else 0, 1,
            )
        }
        tacans += fields.drop(1)
        val ils = ArrayList<DtcFromMission.IlsChoice>()
        for ((a, what) in own) {
            val ends = a.runways.flatMap { it.ends }
            val wind = CardWeather.column(weather, if (what == "departure") 0 else 2)?.windDir
            val into = ends.getOrNull(CardWeather.intoWind(ends.map { it.designator to it.headingTrue }, wind))
            val withIls = ends.filter { it.ils?.trim()?.toDoubleOrNull() != null }.sortedBy { if (it == into) 0 else 1 }
            for (e in withIls) {
                val freq = e.ils?.trim() ?: continue
                val crs = ((e.headingTrue.roundToInt() % 360) + 360) % 360
                ils += DtcFromMission.IlsChoice(
                    "${a.name} RWY ${e.designator} $freq, course $crs ($what${if (e == into && wind != null) ", into the wind" else ""})",
                    "${a.name.take(12)} ${e.designator}", (freq.toDouble() * 100).roundToInt(), crs,
                )
            }
        }

        return DtcFromMission.Facts(
            route = route, support = support, sites = sites, places = places, targets = targets.distinctBy { it.at },
            ships = ships, laser = f?.laser.orEmpty(), seat = mission.seat, briefedSystems = briefedNames,
            tacans = tacans, ils = ils, notes = notes,
        )
    }

    /**
     * The save's known enemy air-defence sites, for a flight opened from a save: the hostile air-defence battalions the
     * PC serves with the flight ([com.bmscompanion.app.data.mission.CampFlight.airDefences]) that the flight's side has
     * spotted. Empty from a PC older than 1.3.8.
     */
    fun saveSites(mission: WdpMission, reference: List<Threat>): List<DtcFromMission.Site> =
        mission.flight?.airDefences.orEmpty().filter { it.spotted && it.system.isNotBlank() }
            .map { siteOf(it.system, it.x, it.y, reference, SAVE_SITES) }

    /**
     * A site of [system] (the save's vehicle name: "SA-2 (S-75)", "ZSU-23-4") at [north]/[east]: named as the threat
     * reference names the system first, then as the save does, with the reference's aliases; its category (SAM, AAA,
     * MANPADS) the reference's, which is what types an AAA site the PPT table has no name for ("S-60" is its AAA).
     */
    fun siteOf(system: String, north: Double, east: Double, reference: List<Threat>, source: String): DtcFromMission.Site {
        val ref = threatGuideEntry(system, reference)
        return DtcFromMission.Site(listOfNotNull(ref?.name, system) + ref?.aliases.orEmpty(), Pt(north, east), source, ref?.category)
    }

    const val SAVE_SITES = "the save's known sites"

    /**
     * The save's known enemy ships (hostile naval task forces the flight's side has spotted), for the Harpoon steerpoints
     * (STPT 90-99), as the PC serves them with the flight ([com.bmscompanion.app.data.mission.CampFlight.ships]).
     */
    fun saveShips(mission: WdpMission): List<Place> =
        mission.flight?.ships.orEmpty().filter { it.spotted }.map { Place(it.name ?: it.system, Pt(it.x, it.y), 0.0, SHIPS) }

    const val SHIPS = "Hostile ships"

    private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    private fun roleOf(text: String?): String? = when {
        text == null -> null
        Regex("tanker|refuel|aar", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Tanker"
        Regex("awacs|aew|early warning", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "AWACS"
        Regex("jstar", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "JSTARS"
        else -> null
    }

    /** The places' groups, as the pickers list them. */
    const val YOUR_BASES = "Your airbases"
    const val YOUR_TARGETS = "Your targets"
    const val AIR_DEFENCES = "Air defences"
    const val STATIONS = "Support"

    private val SUPPORT_ORDER = listOf("Tanker", "AWACS", "JSTARS")

    /**
     * The waypoint actions whose target the flight attacks (`DataCardPlan.actionString`): ground and naval strike,
     * search and destroy, strike, bomb, SEAD, ELINT, recon, ASW, airdrop, FAC.
     */
    private val ATTACK_ACTIONS = setOf(14, 15, 16, 17, 18, 19, 20, 21, 23, 25, 30)
    private val AIR_DEFENCE = setOf("SAM", "AAA", "MANPADS", "SAM_RADAR")
    private val TACAN = Regex("\\b(\\d{1,3})\\s?([XY])\\b")

    const val NO_SITES = "No air-defence site is known yet: the briefing gives none with a position. While Falcon BMS is flying, the " +
        "Tacview feed (ACMI recording, F) reports the sites of the systems the briefing names."
}
