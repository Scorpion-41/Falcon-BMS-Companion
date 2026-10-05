package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CampAtoTarget
import com.bmscompanion.app.data.mission.CampAtoTargets

/**
 * WDP's **ATO Target List** of a save (`fclsAtoTargetList`, opened from its Options menu and its flight selection):
 * every flight of the pilot's side with the target it is tasked to attack, in two lists — targets that are units of
 * the save, and targets that are objectives of its campaign start — as `GET /api/campaign/atotargets` serves it
 * ([CampaignFiles.atoTargets], docs/PROTOCOL.md). WDP's rules, from `FillUnits`, `FillObjectives` and
 * `GetTargetName`:
 *
 * - **Which flights**: those `CheckOwnSideFltToTeam` counts on the side — the team controlling the side's team (in
 *   Korea ROK controls the U.S. team) is allied or friendly toward the flight's team. The side's own team is always
 *   counted, as [CampaignFiles] counts it for the DataCard's support tables: a team may be neutral toward itself
 *   (LHTO), which dropped the pilot's own flights from WDP's list.
 * - **Which target**: the first waypoint after the first whose target id is not 0, other than a take-off (action 1)
 *   or a landing (7). A flight with none (a CAP, a tanker) is not listed. The target is an objective when the start
 *   file holds an objective of that number (WDP compares the numbers only), else a unit of the save.
 * - **Nr** is the target's place in WDP's own table: the save's units in the order the file holds them, the start
 *   file's objectives in theirs.
 * - **Names**: an objective by its own name; a unit as "<number><st|nd|rd|th> <first vehicle> <unit class> <what it
 *   is>" ("500th SA-2 AD Battalion": "Air Defense" is written "AD"); a carrier by its ship. The squadron is its number
 *   with its ordinal ("36th"), the airbase the squadron's own field.
 * - **Times and place**: TOT is the target waypoint's arrival and TakeOff the first waypoint's departure; the target
 *   stands where the save puts it (a unit at its cell's middle, an objective at its own position, as WDP reads it).
 *
 * What WDP got wrong is not repeated (docs/WDP-PORT.md, "ATO Target List"): its Lat/Long columns are always
 * 00,00.000 (the window converts with a coordinate object it never gives the theater), so the positions go out in
 * feet and the window prints them with the Planner's projection; the first objective of the start file lost its
 * name; a target neither list holds was listed with the previous row's position. Read only; nothing here throws past
 * [CampaignFiles.atoTargets].
 */
object CampaignAtoTargets {
    private const val ACT_TAKEOFF = 1
    private const val ACT_LAND = 7

    fun of(
        save: CampaignArchive.Save, names: CampaignArchive.Names, objs: CampaignArchive.ObjWalk?, side: Int?,
        theater: String, file: String,
    ): CampAtoTargets {
        val notes = ArrayList<String>()
        val clock = save.header?.currentTime ?: 0L
        if (side == null) {
            notes += "Nothing in $file tells which side you are on (no briefed flight, no player flight, no player squadron): plan one of its flights first."
            return CampAtoTargets(theater = theater, file = file, clock = clock, notes = notes)
        }
        val teams = save.teams
        val controller = teams?.firstOrNull { it.n == side }?.controller ?: side
        val stance = teams?.firstOrNull { it.n == controller }?.stance
        if (stance == null) notes += "The save's team table could not be read, so only your own team's flights are listed."
        fun ours(owner: Int) = owner == side || stance?.getOrNull(owner).let { it == TeamRelations.ALLIED || it == TeamRelations.FRIENDLY }
        val sideTeams = (0 until 8).filter { ours(it) }.mapNotNull { n ->
            (teams?.firstOrNull { it.n == n }?.name ?: save.header?.team(n)?.name)?.trim()?.takeIf { it.isNotEmpty() }
        }

        // WDP's tables: every unit of the save in its order (UnitTable), the start file's objectives (ObjectiveTable)
        val unitIndex = HashMap<CampaignArchive.VuId, Int>()
        save.units.forEachIndexed { i, u -> unitIndex.putIfAbsent(u.id, i) }
        val objIndex = HashMap<Long, Int>()
        objs?.objectives?.forEachIndexed { i, o -> objIndex.putIfAbsent(o.id.num, i) }

        val units = ArrayList<CampAtoTarget>()
        val objectives = ArrayList<CampAtoTarget>()
        var unknown = 0
        for (f in save.flights) {
            if (!ours(f.owner)) continue
            val w = f.waypoints.drop(1).firstOrNull { it.target != null && it.target.num != 0L && it.action != ACT_TAKEOFF && it.action != ACT_LAND }
                ?: continue
            val id = w.target ?: continue
            val row = flightRow(save, names, objs, f, w)
            val oi = objIndex[id.num]
            val o = oi?.let { objs?.objectives?.getOrNull(it) }
            if (oi != null && o != null) {
                objectives += row.copy(nr = oi, target = o.name.trim().ifEmpty { "Objective ${o.campId}" }, x = o.north, y = o.east)
                continue
            }
            val ui = unitIndex[id]
            val u = ui?.let { save.units.getOrNull(it) }
            if (ui == null || u == null) {
                unknown++
                units += row.copy(nr = -1, target = "Unknown target (${id.num})")
            } else {
                units += row.copy(nr = ui, target = unitName(names, u), x = u.north, y = u.east)
            }
        }
        if (unknown > 0 && objs == null) notes += "The campaign start (" + (save.header?.scenario?.trim()?.takeIf { it.isNotEmpty() } ?: "?") +
            ") is not beside $file, so $unknown target${if (unknown == 1) "" else "s"} that may be objectives could not be named."
        val byTarget = compareBy<CampAtoTarget, String>(String.CASE_INSENSITIVE_ORDER) { it.target }
        return CampAtoTargets(
            theater = theater, file = file, clock = clock, side = side, sideTeams = sideTeams,
            units = units.sortedWith(byTarget), objectives = objectives.sortedWith(byTarget), notes = notes,
        )
    }

    /** Everything of a row but the target: the flight, its package, squadron, airbase and times. */
    private fun flightRow(
        save: CampaignArchive.Save, names: CampaignArchive.Names, objs: CampaignArchive.ObjWalk?,
        f: CampaignArchive.Flight, w: CampaignArchive.Waypoint,
    ): CampAtoTarget {
        val sq = save.squadronOf(f)
        val n = sq?.core?.nameId
        return CampAtoTarget(
            tot = w.arrive,
            packageNumber = save.packageOf(f)?.campId,
            flight = names.callsign(f) ?: "Flight ${f.campId}",
            flightId = f.id.toString(),
            aircraft = names.aircraft(f.type)?.trim() ?: names.unitClass(f.type)?.trim() ?: "",
            mission = names.mission(f.mission)?.trim() ?: "",
            squadron = if (n != null) "$n" + CampaignArchive.ordinal(names, n) else "",
            airbase = sq?.let { airbase(save, names, objs, it) } ?: "",
            takeoff = f.waypoints.firstOrNull()?.depart ?: 0L,
            team = f.owner,
        )
    }

    /**
     * WDP's `GetAirbaseName` of the squadron's field: the objective of that number by its name, a carrier (a unit) by
     * its ship; else the name the save's header gives the squadron's field.
     */
    private fun airbase(save: CampaignArchive.Save, names: CampaignArchive.Names, objs: CampaignArchive.ObjWalk?, sq: CampaignArchive.Squadron): String {
        val id = sq.airbase
        val header = save.header?.squadron(sq.id)?.airbase?.trim()?.takeIf { it.isNotEmpty() }
        if (id.isNone) return header ?: ""
        objs?.objectives?.firstOrNull { it.id.num == id.num }?.let { o ->
            val carrier = names.ct(o.type)?.specific == 7
            if (!carrier) o.name.trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        save.unit(id)?.let { u -> (names.aircraft(u.type) ?: names.unitClass(u.type))?.trim()?.takeIf { it.isNotEmpty() }?.let { return it } }
        return header ?: ""
    }

    /** WDP's `GetTargetName` of a unit. */
    fun unitName(names: CampaignArchive.Names, u: CampaignArchive.CampUnit): String {
        val ct = names.ct(u.type)
        // a carrier: WDP names the carrier's objective as an airbase, which is the ship
        if (ct != null && ct.subType == 7 && ct.specific == 7) names.aircraft(u.type)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val n = u.core.nameId
        val number = if (n > 0) "$n" + CampaignArchive.ordinal(names, n) else null
        val vehicle = names.aircraft(u.type)?.trim()
        val cls = names.unitClass(u.type)?.trim()?.let { if (it == "Air Defense") "AD" else it }
        return listOf(number, vehicle, cls, ct?.let { typeWord(it) }).filter { !it.isNullOrEmpty() }.joinToString(" ")
            .ifEmpty { "Unit ${u.campId}" }
    }

    /** BMSUtils' `TypeToString(domain, class, type)` for a unit: Flight, Package, Squadron, Battalion, Brigade, TaskForce. */
    private fun typeWord(ct: CampaignArchive.Ct): String = when {
        ct.domain == 2 && ct.cls == 6 -> when (ct.type) { 1 -> "Flight"; 2 -> "Package"; 3 -> "Squadron"; else -> "" }
        ct.domain == 3 && ct.cls == 6 -> when (ct.type) { 1 -> "Battalion"; 2 -> "Brigade"; else -> "" }
        ct.domain == 4 && ct.cls == 6 -> if (ct.type == 1) "TaskForce" else ""
        else -> ct.type.toString()
    }
}
