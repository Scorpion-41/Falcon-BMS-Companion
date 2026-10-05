package com.bmscompanion.app.ui.screens.wdp

import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampPlace
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.DtcPoint
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionRoute
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.data.wdp.WdpCoords

/**
 * The mission, in the terms the Weapon Delivery Planner pages want it.
 *
 * WDP reads the campaign and the printed briefing itself. Inside the app there is no need: the app already has the
 * briefing the moment Falcon BMS prints it, and the data cartridge the moment it changes, parsed and on every
 * device. So the ported pages take the mission from here rather than reading files of their own — the same
 * callsign, airbases, flight, steerpoints and targets the Briefing and Dashboard pages show.
 *
 * **Two sources, one Planner** (R3-PLAN A1). By default the mission is the printed briefing and the cartridge
 * ([briefing], [dtc]), with BMS's own mission file route ([route], `MissionData.route`, only ever the briefed
 * flight's). **Open mission…** plans one flight of a save instead: [flight] is that flight as the PC read it, [ref]
 * which save and flight it is, [seat] the seat in it, and [briefing] the save made into a briefing on the PC.
 *
 * **Where each steerpoint's position comes from is WDP's own question** ([precision], D45), asked when a save's flight
 * is planned: "Did you save Precision STPT in the DTC for THIS flight in BMS?". Yes: the cartridge's slot when it is
 * not zero (precise: BMS's Recon or the pilot's edit), else the mission file (BMS's route beside the save, else the
 * save's waypoint at its cell's middle) — where WDP took the grid cell for every route point. No (the default): the
 * save's waypoints only, as WDP's No. With the printed briefing nothing is asked: the cartridge, else BMS's route.
 *
 * **Each attack page picks its own target** with its TGT STPT box, as in WDP: the target is that steerpoint and the IP
 * the one before it, unless the pilot picks another in the page's IP STPT box (D87). There is no target bar. On a new mission or flight ([key]) the three pages are set once to
 * [defaultStpt]; after that each keeps its own.
 *
 * Coordinates are the sim's own feet, **x north and y east**, as WDP's steerpoint tables hold them (FalconY north,
 * FalconX east). Where WDP prints latitude and longitude it needs the theater's projection: [theater] is the one
 * Falcon BMS is on (or the opened save's), and [coords] what WDP's `SetCoordData` would give a page for it (null while
 * it is unknown).
 */
data class WdpMission(
    val briefing: Briefing? = null,
    val dtc: Dtc? = null,
    /** The theater BMS is running (or last ran), from the app's own theater list; null when it cannot be told. */
    val theater: Theater? = null,
    /** BMS's own mission file beside the save, when the PC believes it is the briefed flight's (A11); else null. */
    val route: MissionRoute? = null,
    /** The flight of a save the Planner plans (Open mission…), with its waypoints; null for the printed briefing. */
    val flight: CampFlight? = null,
    /** Which save and which flight [flight] is, as the PC names them. */
    val ref: CampRef? = null,
    /** The seat in the flight, 0-3 (lead, wing, element lead, element wing): whose designated targets the card shows. */
    val seat: Int = 0,
    /**
     * The answer to the Precision STPT question for [flight] ([PlannerMissionState.precision]): true = Yes (the
     * cartridge's positions, the mission file's where a slot is empty), false = No (the save's waypoints only). Null =
     * not asked: the printed briefing, or a check that sets the Planner's state itself (planned as Yes).
     */
    val precision: Boolean? = null,
    /**
     * Steerpoints the pilot placed or edited on the DTC page in this session (`DtcWiring.placedThisSession`), handed to
     * the three attack pages only: such a slot wins over the route whatever the Precision answer, because the jet will
     * fly it and it is no stale cartridge point (1.3.8, "IP STPT at the VRP"). Their source is the DTC.
     */
    val placed: Map<Int, DtcPoint> = emptyMap(),
) {
    /** This mission with [p] as the steerpoints placed this session ([placed]); the same mission when nothing changes. */
    fun withPlaced(p: Map<Int, DtcPoint>): WdpMission = if (p == placed) this else copy(placed = p)

    /** The theater's coordinate data as WDP sets it up (`WdpCoords`), or null for an unknown theater. */
    val coords: PopupCoords.CoordData? by lazy { WdpCoords.coordData(theater) }

    /**
     * Every steerpoint slot, each with the position the Planner uses and where it came from: the cartridge's slot
     * when it holds a position, else BMS's mission file route, else the save's waypoint; a slot no source places keeps
     * the cartridge's empty entry (its name stays, "Not set" or a Recon target not yet placed). When the pilot answered
     * **No** to the Precision STPT question ([precision] false), the save's waypoints only, each at its cell's middle,
     * as WDP's No plans them (`cntDataCard.CreateFlightplan`). In slot order.
     */
    val slots: List<WdpStpt> by lazy {
        val fromDtc = dtc?.steerpoints.orEmpty().associateBy { it.n }
        val fromRoute = route?.steerpoints.orEmpty().associateBy { it.n }
        val fromSave = flight?.route.orEmpty().withIndex().associate { (i, w) ->
            val n = if (w.n > 0) w.n else i + 1
            n to DtcPoint(n = n, x = w.x, y = w.y, altFt = w.altFt, action = w.action, isTarget = false, name = w.target?.name ?: w.desc)
        }
        val mine = placed.filterValues { it.placed() }
        if (flight != null && precision == false) return@lazy (fromSave.keys + mine.keys).distinct().sorted().map { n ->
            mine[n]?.let { WdpStpt(it.copy(n = n), StptSource.DTC) } ?: WdpStpt(fromSave.getValue(n), StptSource.SAVE)
        }
        (fromDtc.keys + fromRoute.keys + fromSave.keys + mine.keys).distinct().sorted().mapNotNull { n ->
            val d = fromDtc[n]
            val r = fromRoute[n]
            val s = fromSave[n]
            val p = mine[n]
            when {
                p != null -> WdpStpt(p.copy(n = n), StptSource.DTC)
                d != null && d.placed() -> WdpStpt(d, StptSource.DTC)
                // a route made of the printed flight's waypoints in the save (MissionRoute.fromSave) is the save's
                r != null && r.placed() -> WdpStpt(r.copy(n = n), if (route?.fromSave == true) StptSource.SAVE else StptSource.ROUTE)
                s != null && s.placed() -> WdpStpt(s, StptSource.SAVE)
                d != null -> WdpStpt(d, StptSource.DTC)
                else -> null
            }
        }
    }

    /** The steerpoints the pages plan from, one per slot ([slots]), in steerpoint order. */
    val steerpoints: List<DtcPoint> by lazy { slots.map { it.point } }

    /** The steerpoints the cartridge flags as targets, in steerpoint order. */
    val targets: List<DtcPoint>
        get() = steerpoints.filter { it.isTarget }

    /** A steerpoint by its number, or null. */
    fun steerpoint(n: Int): DtcPoint? = steerpoints.firstOrNull { it.n == n }

    /** Where steerpoint [n]'s position comes from, or null when no source places it. */
    fun source(n: Int): StptSource? = slots.firstOrNull { it.point.n == n && it.point.placed() }?.source

    /** How many steerpoints each source placed: "7 from your DTC, 1 from the save". Empty when none is placed. */
    val sourceCounts: String
        get() {
            val placed = slots.filter { it.point.placed() }
            return StptSource.entries.mapNotNull { s -> placed.count { it.source == s }.takeIf { it > 0 }?.let { "$it from ${s.from}" } }.joinToString(", ")
        }

    /**
     * The flight's steerpoints the pilot said the cartridge holds that it does not: with [precision] Yes, the slots of
     * the save's route whose position came from the mission file because the cartridge's slot is empty.
     */
    val emptyInDtc: List<Int>
        get() {
            if (flight == null || precision != true) return emptyList()
            val route = flight.route.withIndex().map { (i, w) -> if (w.n > 0) w.n else i + 1 }.toSet()
            return slots.filter { it.point.n in route && it.point.placed() && it.source != StptSource.DTC }.map { it.point.n }
        }

    /**
     * Where the steerpoints came from, as the flight picker and the identity strip say it: "STPTs: 3 from your DTC, 5
     * from BMS's route"; for a save's flight led by the answer to the Precision STPT question and, after a Yes, the
     * slots the cartridge left empty: "Precision STPT: Yes · STPTs: 7 from your DTC, 1 from the save (STPT 8 empty in
     * your DTC)". Empty when nothing is placed and nothing was asked.
     */
    val sourceLine: String
        get() {
            val counts = sourceCounts
            val base = if (counts.isEmpty()) "" else "STPTs: $counts"
            val answer = precision?.takeIf { flight != null } ?: return base
            val empty = emptyInDtc
            return "Precision STPT: " + (if (answer) "Yes" else "No") + (if (base.isEmpty()) "" else " · $base") +
                (if (empty.isEmpty()) "" else " (" + stptRange(empty) + " empty in your DTC)")
        }

    /**
     * Everything in the mission a delivery can be planned against, in the order a pilot reads it. The DTC page's
     * Select target and the DataCard's lists show it; the attack pages take their target from their own TGT STPT.
     *
     * **Flight-plan targets** first: steerpoints the cartridge marks as targets, strike steerpoints (action 17, WDP's
     * own rule), or whose briefing row says Attack, Target, Strike or Recon — the same rule the map and the Briefing
     * page use, so the three agree. For these WDP's own convention holds exactly: the target is that steerpoint and
     * the IP is the one before it.
     *
     * **Weapon target points** next (the cartridge's `wpntarget_N`, for SPICE: "not related to target steerpoints",
     * UM p.56). They are not flight-plan steerpoints, so WDP has no "steerpoint before" for them; the IP given is the
     * one before the flight's first target steerpoint.
     */
    val choices: List<WdpTarget>
        get() {
            val rows = briefing?.steerpoints.orEmpty().associateBy { it.n }
            val pts = steerpoints.filter { it.placed() }
            fun feet(p: DtcPoint) = Triple(p.x, p.y, p.altFt)
            // a save's strike steerpoints count whichever source placed them (BMS's route may carry another action)
            val strikes = strikeSteerpoints.toSet()
            val planTargets = pts.filter { p ->
                val desc = rows[p.n]?.desc
                p.isTarget || p.action == STRIKE || p.n in strikes || TARGET_WORDS.any { desc?.contains(it, true) == true }
            }
            val out = ArrayList<WdpTarget>()
            for (p in planTargets) {
                val name = rows[p.n]?.desc?.takeIf { it.isNotBlank() } ?: p.name
                out += WdpTarget(
                    key = "STPT ${p.n}",
                    label = "STPT ${p.n}" + (name?.let { " · $it" } ?: ""),
                    waypoint = p.n,
                    target = feet(p),
                    ip = steerpoint(p.n - 1)?.takeIf { it.placed() }?.let(::feet),
                )
            }
            val first = planTargets.firstOrNull()
            val ip = first?.let { steerpoint(it.n - 1) }?.takeIf { it.placed() }?.let(::feet)
            dtc?.weaponTargets.orEmpty().filter { it.placed() }.forEachIndexed { i, t ->
                out += WdpTarget(
                    key = "WPN ${t.n}",
                    label = "WPN ${i + 1}" + (t.name?.let { " · $it" } ?: ""),
                    waypoint = first?.n ?: t.n,
                    target = feet(t),
                    ip = ip,
                )
            }
            return out
        }

    /**
     * The flight's strike steerpoints (action 17), in order, when a save's flight is planned: what WDP's
     * `CreateFlightplan` walks for the attack pages and the card's Primary and Secondary.
     */
    val strikeSteerpoints: List<Int>
        get() = flight?.route.orEmpty().withIndex().filter { it.value.action == STRIKE }.map { (i, w) -> if (w.n > 0) w.n else i + 1 }

    /**
     * The TGT STPT the three attack pages are set to, once, on a new mission or flight (A3).
     *
     * With a save's flight: its **first** strike steerpoint. WDP sets all three pages to every strike steerpoint in
     * turn and so ends on the last, while its card names the first as the Primary target: with two strikes the card
     * and the Delivery block disagreed (fix D40). Otherwise, and for a flight with no strike: the first flight-plan
     * target of [choices] (a cartridge target, a strike steerpoint, a briefing row saying Attack, Target, Strike or
     * Recon). Null when the mission has none; the pages then keep what they show.
     */
    val defaultStpt: Int?
        // the mission's own: steerpoints placed this session ([placed]) are positions to plan from, never its targets
        get() = if (placed.isNotEmpty()) copy(placed = emptyMap()).defaultStpt
        else strikeSteerpoints.firstOrNull() ?: choices.firstOrNull { it.key.startsWith("STPT ") }?.waypoint

    /**
     * Which mission this is, for "set once": a save's flight by its file and flight id; the printed briefing by its
     * flight, package and mission (a PRINT of the same briefing again, or a cartridge saved, is the same mission).
     */
    val key: String
        get() = if (flight != null) "save|" + (ref?.let { "${it.theater}|${it.file}|${it.flight}" } ?: "${flight.row.id}|${flight.row.callsign}")
        else "brief|" + briefing?.overview?.let { "${it.flight}|${it.packageId}|${it.mission}" }

    /**
     * The DataCard's Primary and Secondary targets (`FillPriTarget`/`FillSecTarget`), by WDP's rule rather than a pick.
     *
     * With a save's flight: the first and second strike steerpoints, each named by the target designated to [seat]
     * (from save version 104 on; before that, the waypoint's own target), at that target's position where the save
     * gives one, else at the steerpoint. Without one: the first two flight-plan targets of [choices]. A card with
     * fewer strike steerpoints has fewer targets, as WDP's has.
     */
    fun cardTargets(seat: Int = this.seat): Pair<WdpTarget?, WdpTarget?> {
        if (flight == null) {
            val plan = choices.filter { it.key.startsWith("STPT ") }
            return plan.getOrNull(0) to plan.getOrNull(1)
        }
        val wps = flight.route.withIndex().filter { it.value.action == STRIKE }.take(2)
        val out = wps.map { (i, w) ->
            val n = if (w.n > 0) w.n else i + 1
            val place: CampPlace? = w.designated.getOrNull(seat) ?: w.target
            val at = steerpoint(n)
            val ground = if (source(n) == StptSource.DTC) at?.altFt ?: 0.0 else 0.0
            val x = place?.x?.takeIf { place.y != null } ?: at?.x ?: w.x
            val y = place?.y?.takeIf { place.x != null } ?: at?.y ?: w.y
            WdpTarget(
                key = "STPT $n",
                label = place?.name?.takeIf { it.isNotBlank() } ?: ("STPT $n" + (w.desc?.let { " · $it" } ?: "")),
                waypoint = n,
                target = Triple(x, y, ground),
                ip = steerpoint(n - 1)?.takeIf { it.placed() }?.let { Triple(it.x, it.y, it.altFt) },
            )
        }
        return out.getOrNull(0) to out.getOrNull(1)
    }

    companion object {
        /** BMS's waypoint action for a strike (Strings 350 + 17 = "Strike"): WDP's rule for the attack pages' target. */
        const val STRIKE = 17
        private val TARGET_WORDS = listOf("Attack", "Target", "Strike", "Recon")

        fun of(data: MissionData?): WdpMission = WdpMission(data?.briefing, data?.dtc, route = data?.route)
    }
}

/** A cartridge point that has a position (BMS writes an empty slot as 0,0,0). */
internal fun DtcPoint.placed(): Boolean = x != 0.0 || y != 0.0

/** Steerpoint numbers as a pilot reads them: "STPT 8", "STPT 7-8", "STPT 3, 7-8". */
internal fun stptRange(ns: List<Int>): String {
    val runs = ArrayList<String>()
    val s = ns.distinct().sorted()
    var i = 0
    while (i < s.size) {
        var j = i
        while (j + 1 < s.size && s[j + 1] == s[j] + 1) j++
        runs += if (j == i) "${s[i]}" else "${s[i]}-${s[j]}"
        i = j + 1
    }
    return "STPT " + runs.joinToString(", ")
}

/** Where a steerpoint's position came from (A4), with the words the Planner shows for it. */
enum class StptSource(val label: String, val from: String) {
    /** the pilot's cartridge (`<callsign>.ini`): precise, BMS's Recon or the pilot's own edit */
    DTC("DTC", "your DTC"),
    /** BMS's own mission file beside the save (`MissionData.route`), believed only when it is the briefed flight's */
    ROUTE("BMS route", "BMS's route"),
    /** the opened save's waypoint, at its grid cell's middle */
    SAVE("save", "the save"),
}

/** One steerpoint slot as the Planner uses it: the point, and where its position came from. */
data class WdpStpt(val point: DtcPoint, val source: StptSource)

/**
 * One thing to plan a delivery against: where it is, where the run-in starts, and which steerpoint number the
 * pages should show for it. Positions are sim feet (north, east, altitude).
 */
data class WdpTarget(
    val key: String,
    val label: String,
    val waypoint: Int,
    val target: Triple<Double, Double, Double>,
    val ip: Triple<Double, Double, Double>?,
)
