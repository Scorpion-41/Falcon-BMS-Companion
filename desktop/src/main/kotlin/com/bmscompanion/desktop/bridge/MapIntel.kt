package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CampBriefKey
import com.bmscompanion.app.data.mission.CampMapField
import com.bmscompanion.app.data.mission.CampMapIntel
import com.bmscompanion.app.data.mission.CampMapJstar
import com.bmscompanion.app.data.mission.CampMapRadar
import com.bmscompanion.app.data.mission.CampMapSquadron
import com.bmscompanion.app.data.mission.CampMapTeam
import com.bmscompanion.app.data.mission.CampMapUnit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot

/**
 * What the Planner's Map page draws of a save beyond the flight (`GET /api/campaign/mapintel`, [CampMapIntel]): WDP's
 * MAP tab intelligence (`fclsMain.SamIntel`, `ObjHasWorkingRadar`, `JSTAR`, `CheckOwnSide`), read from Falcon BMS's own
 * files and said from the side of the flight being planned.
 *
 * - **Units**: every ground battalion and naval task force of the save (`.uni`, [CampaignArchive.walkUnits]); brigades
 *   are left out, their battalions are listed. What each is comes from the class table's sub-type, checked against
 *   4.38.1's unit names (land 1 air defence, 2 airmobile, 3 armor, 4 cavalry, 5 engineer, 6 HQ, 7 infantry, 8 marine,
 *   9 mechanized, 10 rocket, 11 self-propelled guns, 12 missile, 13 supply, 14 towed guns; sea 1 amphibious, 3 carrier,
 *   4 battleship and cruiser, 5 destroyer, 6 frigate, 7 patrol boat, 8 merchant ship, 9 tanker). Its system is its
 *   first vehicle, as [CampaignBriefing.sites] names it; a unit that is not air defence but carries a short-range SAM
 *   (SA-8/13/15/17/19, Chun-ma, Avenger — WDP's list with 4.38.1's names) says so in `shorad`.
 * - **Seen**: the unit's spotted bit of the team that **controls** the flight's team or of the flight's own (as
 *   [CampaignBriefing.sites]: in Korea ROK controls the U.S. team, whose own bit is never set), and WDP's recon-loss
 *   rule on the unit's last spot time (else its last combat): 60 min for a unit that does not move, 20 on foot, 10
 *   tracked, naval or rail, 1 in the air, and never for a wheeled one (`ReconLossTime`, `moveType != 2`, as WDP).
 * - **Radar**: the unit table's `RadarVehicle` slot, its vehicles alive read from the roster **as a count**, two bits a
 *   slot (WDP reads the slot's low bit only). A self-contained system (SA-8, SA-11, SA-13, SA-15, SA-17, Chaparral,
 *   Crotale, Roland, Chun-ma) has a radar while any vehicle of its own type is left. `RadarVehicle` 255 is no radar.
 * - **Objectives** are the start file's (`.obj`), their owners and buildings' states the save's changes (`.obd`,
 *   [CampaignArchive.deltas]) where it has one — a played save keeps only the objectives that changed — else the start
 *   file's own. A **search radar** is an objective flagged for one (bits 16 or 22, or radar data: WDP's test) whose
 *   buildings include one: a building whose feature record (`Falcon4_FCD.xml`) is named "Radar …". WDP tests nine
 *   fixed feature indices, and 4.38.1 renumbered them: index 74 is now a wall, and the EW dome (198) and the P-37
 *   (595) are missing from WDP's list ([radarRule] reports it).
 * - **JSTARS**: every JSTARS flight (mission 27) of the flight's side, its station the middle of the leg its ELINT
 *   action (20) starts, cell middles (WDP takes the cells' corners), on station while the clock is between its arrival
 *   there and its departure from the next waypoint. A unit within 200 nm of **any** station on station counts as seen
 *   by it (WDP keeps only the last station it looked at).
 * - **Sides**: from the flight's team's stance toward each team in the save's team table (`.tea`), WDP's
 *   `CheckOwnSide`: its own team and the team controlling it, allied and friendly are friendly, hostile and at war
 *   hostile, neutral or no relation neutral. A stance the flight's team leaves at "none" is taken from the controlling
 *   team's record.
 *
 * Read only, nothing throws (what cannot be read becomes a sentence in [CampMapIntel.notes]), cached by the save's path,
 * size and time.
 */
object MapIntel {
    /** WDP's `MaxJstarRange`: a unit this close to a JSTARS on station counts as seen. */
    const val JSTAR_RANGE_NM = 200

    private const val FT_PER_NM = 6076.12
    private const val UNIT_MOVING = 0x400
    private const val UNIT_DEAD = 0x20000
    private const val MISSION_JSTARS = 27
    private const val ACT_ELINT = 20
    private const val NO_RADAR = 255
    private const val SLOTS = 16
    private const val MOVE_WHEELED = 2
    private const val OBJ_AIRBASE = 1
    private const val OBJ_AIRSTRIP = 2
    private const val TYPE_STRINGS = 500
    private const val FCD_FILE = "Falcon4_FCD.xml"
    /** Objective flags WDP's `ObjHasWorkingRadar` tests (bits 16 and 22). */
    private const val OBJ_RADAR_FLAGS = (1L shl 16) or (1L shl 22)

    /** WDP's `ReconLossTime`, by the unit table's MoveType (none, foot, wheeled, tracked, low air, air, naval, rail). */
    private val RECON_LOSS_MS = longArrayOf(60, 20, 10, 10, 1, 1, 10, 10).map { it * 60_000L }

    /** The feature records WDP's `ObjHasWorkingRadar` calls radars (`Falcon4_FCD.xml` numbers). */
    val WDP_RADAR_FCD = listOf(53, 54, 72, 73, 74, 75, 76, 77, 204)

    private val RADAR_NAME = Regex("\\bradar\\b", RegexOption.IGNORE_CASE)

    /** WDP's SHORAD vehicles (`SamIntel`: SA-8, -13, -15, -17, -19, 2S6), and 4.38.1's Chun-ma and Avenger. */
    private val SHORAD = Regex("\\bSA-(8|13|15|17|19)\\b|\\b2S6\\b|Chun-?ma|\\bAvenger\\b", RegexOption.IGNORE_CASE)

    /** The systems whose radar is on every launcher (`SamRadarAlive`), and 4.38.1's Chun-ma. */
    private val SELF_CONTAINED = Regex("\\bSA-(8|11|13|15|17)\\b|Chap+ar+al|Crotale|Roland|Chun-?ma", RegexOption.IGNORE_CASE)

    /** Team colours as the campaign header numbers them (WDP's `teamColor`: white, green, blue, brown, orange, yellow, red, grey). */
    private val TEAM_COLOURS = intArrayOf(0xFFFFFF, 0x008000, 0x0000FF, 0xA52A2A, 0xFFA500, 0xFFFF00, 0xFF0000, 0x808080)

    private val LAND_KINDS = mapOf(
        1 to "airdefence", 2 to "airmobile", 3 to "armor", 4 to "cavalry", 5 to "engineer", 6 to "hq", 7 to "infantry",
        8 to "marine", 9 to "mechanized", 10 to "rocket", 11 to "artillery", 12 to "missile", 13 to "supply", 14 to "towed",
    )
    private val SEA_KINDS = mapOf(
        1 to "ship", 2 to "ship", 3 to "carrier", 4 to "cruiser", 5 to "destroyer", 6 to "frigate", 7 to "patrol", 8 to "ship", 9 to "tanker",
    )

    // ================================================================ the route

    /**
     * The map intel of [file] in [theater] for [flight]'s side ([team] overrides it). With [file] left out, the save and
     * flight BMS briefed ([CampaignFiles.Context.key]), looked for in [theater], else in the theater BMS is set to.
     */
    fun answer(ctx: CampaignFiles.Context, theater: String?, file: String?, flight: String?, team: String?): CampaignFiles.Answer<CampMapIntel> {
        val set = ctx.set ?: return refused(409, CampaignFiles.NO_BMS)
        val teamN = team?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull()?.takeIf { n -> n in 0..7 } ?: return refused(400, "team is a team number, 0 to 7: \"$it\" is not one.")
        }
        val flightId = flight?.trim()?.takeIf { it.isNotEmpty() }?.let {
            CampaignArchive.VuId.parse(it) ?: return refused(400, "flight is the id /api/campaign/ato gave (num/creator): \"$it\" is not one.")
        }
        val want = theater?.trim()?.takeIf { it.isNotEmpty() }
        val t = if (want != null) set.all.firstOrNull { it.name.equals(want, ignoreCase = true) } ?: set.byName(want)
            ?: return refused(400, "There is no theater called \"$want\" in Falcon BMS's theater list.")
        else set.current(ctx.curTheater) ?: return refused(409, set.error ?: "Falcon BMS's theater list names no theaters.")
        set.campaignDir(t) ?: return refused(409, "The campaign folder of ${t.name} (Data\\${t.campaignDir ?: "?"}) is not there.")
        val name = file?.trim()?.takeIf { it.isNotEmpty() }
        val f: File
        var fid = flightId
        if (name != null) {
            if (name.contains('\\') || name.contains('/') || name.contains(':') || name.contains(".."))
                return refused(400, "A file name only, without a folder: \"$name\" is not one.")
            f = set.saves(t).firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: return refused(404, "There is no save called \"$name\" in the campaign folder of ${t.name}.")
            if (ctx.now - attempt(0L) { f.lastModified() } in 0 until Theaters.SETTLE_MS)
                return refused(409, "Falcon BMS is still writing ${f.name}; try again in a moment.")
        } else {
            val key = ctx.key ?: return refused(404, "No briefing has been printed, so there is no briefed save: say which file.")
            val names = CampaignArchive.names(set, t)
            val found = set.saves(t).asSequence()
                .filter { ctx.now - attempt(0L) { it.lastModified() } !in 0 until Theaters.SETTLE_MS && !Theaters.isStart(it) }
                .mapNotNull { s ->
                    val save = CampaignArchive.cached(s, names)
                    save.flights.firstOrNull { fl -> holds(key, names.callsign(fl), save.packageOf(fl)?.campId, fl.campId) }?.let { s to it }
                }.firstOrNull()
                ?: return refused(404, "No save in the campaign folder of ${t.name} holds the briefed flight ${key.callsign}: say which file.")
            f = found.first
            if (fid == null) fid = found.second.id
        }
        val dir = CampaignArchive.directoryOf(f)
        if (dir.error != null) return refused(409, "${f.name} is not a save BMS can read: ${dir.error}")
        // a campaign start is never opened, by the same rule as everywhere: a start's name, an objectives part, no flights
        val flights = attempt<Int?>(null) { CampaignArchive.cached(f, CampaignArchive.names(set, t)).takeIf { it.uni != null }?.flights?.size }
        CampaignFiles.stockWhy(f.nameWithoutExtension, dir.has("obj"), flights)?.let { return refused(409, it) }
        return attempt<CampaignFiles.Answer<CampMapIntel>>(refused(409, "Could not read ${f.name}.")) { build(set, t, f, fid, teamN, ctx.key) }
    }

    private fun holds(key: CampBriefKey?, callsign: String?, pkg: Int?, flt: Int?): Boolean =
        key != null && callsign != null && callsign.equals(key.callsign, ignoreCase = true) &&
            (key.packageId == null || key.packageId == pkg) && (key.flightId == null || key.flightId == flt)

    private fun refused(status: Int, sentence: String) = CampaignFiles.Answer.Refused(status, sentence)

    private val cache = object : LinkedHashMap<String, CampMapIntel>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CampMapIntel>?) = size > 8
    }

    // ================================================================ the answer

    /** The map intel of save [file] of theater [t], for flight [flightId]'s side ([team] overrides it). */
    fun build(
        set: Theaters.TheaterSet, t: Theaters.Theater, file: File, flightId: CampaignArchive.VuId?, team: Int?, key: CampBriefKey? = null,
    ): CampaignFiles.Answer<CampMapIntel> {
        val names = CampaignArchive.names(set, t)
        val save = CampaignArchive.cached(file, names)
        if (save.uni == null) return refused(409, save.error ?: "Could not read the units of ${file.name}.")
        val chosen = when {
            flightId != null -> save.flight(flightId) ?: return refused(400, "There is no flight $flightId in ${file.name}.")
            team != null -> null
            else -> save.flights.firstOrNull { holds(key, names.callsign(it), save.packageOf(it)?.campId, it.campId) }
                ?: save.playerFlights.firstOrNull()
        }
        val side = team ?: chosen?.owner ?: save.unit(save.header?.playerSquadron)?.owner ?: 0
        val cacheKey = listOf(CampaignArchive.key(file), names.key, chosen?.id?.toString() ?: "-", side.toString()).joinToString("|")
        synchronized(cache) { cache[cacheKey]?.let { return CampaignFiles.Answer.Ok(it) } }
        val value = make(set, t, save, names, chosen, side)
        synchronized(cache) { cache[cacheKey] = value }
        return CampaignFiles.Answer.Ok(value)
    }

    /** Who is on which side, from team [team]'s point of view. */
    class Sides(val team: Int, val controller: Int, private val own: List<Int>?, private val ctrl: List<Int>?) {
        fun of(owner: Int): String {
            if (team == 0) return "unknown"
            if (owner == team || owner == controller) return "friendly"
            if (own == null) return "unknown"
            val s = own.getOrNull(owner)?.takeIf { it != TeamRelations.NONE } ?: ctrl?.getOrNull(owner) ?: TeamRelations.NONE
            return when (s) {
                TeamRelations.ALLIED, TeamRelations.FRIENDLY -> "friendly"
                TeamRelations.HOSTILE, TeamRelations.WAR -> "hostile"
                else -> "neutral"
            }
        }
    }

    fun sides(save: CampaignArchive.Save, team: Int): Sides {
        val rec = save.teams?.firstOrNull { it.n == team }
        val controller = rec?.controller ?: team
        val ctrl = save.teams?.firstOrNull { it.n == controller }?.stance
        return Sides(team, if (team == 0) 0 else controller, rec?.stance, ctrl)
    }

    private fun make(
        set: Theaters.TheaterSet, t: Theaters.Theater, save: CampaignArchive.Save, names: CampaignArchive.Names,
        flight: CampaignArchive.Flight?, team: Int,
    ): CampMapIntel {
        val notes = ArrayList<String>()
        val h = save.header
        val clock = h?.currentTime ?: 0L
        val sides = sides(save, team)
        if (team == 0) notes += "No flight or team was given and the save has no player flight, so no side is known: every unit is \"unknown\"."
        else if (save.teams == null) notes += "The save's team table could not be read, so only the flight's own team is known as friendly."
        save.uni?.stop?.let { notes += "The units of ${save.file.name} could not all be read: $it." }
        val spotMask = if (team == 0) 0 else (1 shl (sides.controller and 7)) or (1 shl (team and 7))

        // ---- teams
        val used = save.units.map { it.owner }.toSet()
        val teams = h?.teams.orEmpty().filter { it.name.isNotBlank() || it.n in used }.map { tm ->
            CampMapTeam(n = tm.n, name = tm.name.trim().ifEmpty { "Team ${tm.n}" }, colour = TEAM_COLOURS.getOrElse(tm.colour) { 0x808080 }, side = sides.of(tm.n))
        }

        // ---- JSTARS of the flight's side
        val jstars = if (team == 0) emptyList() else save.flights.filter { it.mission == MISSION_JSTARS && sides.of(it.owner) == "friendly" }.mapNotNull { j ->
            val w = j.waypoints
            val leg = w.indexOfFirst { it.action == ACT_ELINT }
            if (leg < 0) return@mapNotNull null
            val a = w[leg]
            val b = w.getOrNull(leg + 1) ?: a
            val from = a.arrive
            val to = b.depart
            CampMapJstar(
                callsign = names.callsign(j) ?: "Flight ${j.campId}", aircraft = names.aircraft(j.type) ?: names.unitClass(j.type),
                x = (a.north + b.north) / 2, y = (a.east + b.east) / 2, from = from, to = to,
                active = clock in from..to, rangeNm = JSTAR_RANGE_NM,
            )
        }
        val onStation = jstars.filter { it.active }

        // ---- units
        val ucds = ucdTable(names)
        val vehicles = vehicleNames(names)
        val units = save.units.mapNotNull { u ->
            val domain = when (u) {
                is CampaignArchive.Battalion -> "land"
                is CampaignArchive.TaskForce -> "sea"
                else -> return@mapNotNull null
            }
            attempt(null) { unit(save, names, u, domain, ucds, vehicles, sides, spotMask, clock, onStation) }
        }
        if (ucds.isEmpty()) notes += "The unit table (${CampaignArchive.UCD_FILE}) could not be read: radars, movement and strength are unknown."

        // ---- objectives: fields and search radars
        val start = CampaignArchive.startFile(save)
        val objs = start?.let { CampaignArchive.objectives(it) }
        val fields = ArrayList<CampMapField>()
        val radars = ArrayList<CampMapRadar>()
        if (objs == null) {
            notes += "The start file the save was made from (${h?.scenario?.ifBlank { null } ?: "?"}) was not found beside it, so there are no airfields or search radars."
        } else {
            objs.stop?.let { notes += "The objectives of ${start?.name} could not all be read: $it." }
            val hasObd = CampaignArchive.directoryOf(save.file).has("obd")
            val deltas = if (hasObd) CampaignArchive.deltas(save.file) else null
            when {
                !hasObd -> notes += "The save keeps no objective changes (.obd): owners and damage are the start file's."
                deltas == null -> notes += "The save's objective changes (.obd) could not be read: owners and damage are the start file's."
                deltas.stop != null -> notes += "The save's objective changes (.obd) could not all be read (${deltas.stop}): the rest are the start file's."
            }
            val airports = CampaignFiles.airports(t.appId)
            val fcd = CampaignArchive.table(set.classFile(t, FCD_FILE), "FCD", setOf("Name"))
            if (fcd.isEmpty()) notes += "The feature table ($FCD_FILE) could not be read, so no search radar is known."
            val squadronsAt = save.squadrons.groupBy { it.airbase }
            for (o in objs.objectives) {
                val ct = names.ct(o.type) ?: continue
                val d = deltas?.get(o.id)
                val owner = d?.owner ?: o.owner
                val status = d?.status ?: o.status
                val typeName = names.strings[TYPE_STRINGS + ct.type]?.trim().orEmpty()
                val oname = o.name.trim().ifEmpty { "$typeName ${o.campId}".trim() }
                if (ct.type == OBJ_AIRBASE || ct.type == OBJ_AIRSTRIP) {
                    val sq = squadronsAt[o.id].orEmpty().map { s ->
                        val sSide = sides.of(s.owner)
                        CampMapSquadron(
                            aircraft = names.aircraft(s.type) ?: names.unitClass(s.type) ?: "?",
                            name = if (sSide == "friendly") h?.squadron(s.id)?.name?.trim()?.takeIf { it.isNotEmpty() } else null,
                            owner = s.owner, side = sSide, count = rosterCount(s.core.roster).takeIf { it > 0 },
                        )
                    }
                    fields += CampMapField(
                        id = o.id.toString(), campId = o.campId, name = oname,
                        type = typeName.ifEmpty { if (ct.type == OBJ_AIRBASE) "Airbase" else "Airstrip" },
                        airport = airports[o.campId]?.id?.toString(), x = o.north, y = o.east,
                        owner = owner, side = sides.of(owner), startOwner = o.owner, squadrons = sq,
                    )
                }
                if ((o.flags and OBJ_RADAR_FLAGS) != 0L || o.hasRadarData) {
                    val features = radarFeatures(set, t, names, ct.entityIdx, fcd)
                    if (features.isNotEmpty()) {
                        val intact = features.count { CampaignArchive.featureStatus(status, it) < 2 }
                        radars += CampMapRadar(
                            id = o.id.toString(), campId = o.campId, name = oname, type = typeName, x = o.north, y = o.east,
                            owner = owner, side = sides.of(owner), working = intact > 0, radars = features.size, intact = intact,
                        )
                    }
                }
            }
        }
        return CampMapIntel(
            theater = t.name, file = save.file.name, modified = save.modified, clock = clock,
            flight = flight?.id?.toString(), team = team, controller = sides.controller,
            teams = teams, units = units, radars = radars, fields = fields, jstars = jstars, notes = notes,
        )
    }

    private fun unit(
        save: CampaignArchive.Save, names: CampaignArchive.Names, u: CampaignArchive.CampUnit, domain: String,
        ucds: Map<Int, Ucd>, vehicles: Map<Int, String>, sides: Sides, spotMask: Int, clock: Long, onStation: List<CampMapJstar>,
    ): CampMapUnit {
        val ct = names.ct(u.type)
        val ucd = ct?.let { ucds[it.entityIdx] }
        val sub = ct?.subType ?: -1
        val cls = names.unitClass(u.type).orEmpty()
        val kind = if (domain == "land") {
            if (sub != 1 && cls.contains("Recon", ignoreCase = true)) "recon" else LAND_KINDS[sub] ?: "other"
        } else SEA_KINDS[sub] ?: "ship"
        val system = names.aircraft(u.type) ?: cls
        val side = sides.of(u.owner)
        val flags = u.core.unitFlags
        val moveType = ucd?.moveType
        // a short-range SAM carried by a unit that is not air defence (WDP's SamIntel, which also looks inside sub-type 1)
        val shorad = if (domain == "land" && sub != 1 && ucd != null) {
            (0 until SLOTS).firstNotNullOfOrNull { i ->
                if (ucd.counts[i] <= 0) null else vehicles[ucd.vehicles[i]]?.takeIf { SHORAD.containsMatchIn(it) }
            }
        } else null
        val spotTime = u.core.spotTime
        val lastCombat = (u as? CampaignArchive.Battalion)?.lastCombat ?: 0L
        val seen = when {
            spotTime != 0L -> spotTime
            lastCombat != 0L -> lastCombat
            else -> null
        }
        val recent = seen != null && moveType != null && moveType != MOVE_WHEELED && moveType in RECON_LOSS_MS.indices &&
            (seen > clock || clock - seen < RECON_LOSS_MS[moveType])
        val jstar = domain == "land" && onStation.any { j -> hypot(j.x - u.north, j.y - u.east) / FT_PER_NM <= j.rangeNm }
        return CampMapUnit(
            id = u.id.toString(), campId = u.campId, kind = kind, domain = domain, system = system, shorad = shorad,
            name = attempt(null) { CampaignArchive.unitName(save, u) }, owner = u.owner, side = side,
            x = u.north, y = u.east,
            spotted = side == "friendly" || (u.core.spotted and spotMask) != 0,
            seen = seen, recent = recent, jstar = jstar, moveType = moveType,
            radar = radarState(ucd, u.core.roster, system, vehicles),
            moving = flags and UNIT_MOVING != 0, dead = flags and UNIT_DEAD != 0,
            alive = if (ucd == null) null else rosterCount(u.core.roster),
            total = ucd?.counts?.sum(),
        )
    }

    /** The vehicles left in slot [slot] of a roster: two bits a slot, a count of 0 to 3. */
    fun slotCount(roster: Int, slot: Int): Int = (roster ushr (slot * 2)) and 3

    /** The vehicles left in a unit: the roster's slots added up. */
    fun rosterCount(roster: Int): Int = (0 until SLOTS).sumOf { slotCount(roster, it) }

    /** "alive", "dead", "none" or "unknown" (see [CampMapUnit.radar]). */
    private fun radarState(ucd: Ucd?, roster: Int, system: String, vehicles: Map<Int, String>): String {
        if (ucd == null) return "unknown"
        if (SELF_CONTAINED.containsMatchIn(system)) {
            val own = (0 until SLOTS).filter { ucd.counts[it] > 0 && vehicles[ucd.vehicles[it]] == system }
            if (own.isNotEmpty()) return if (own.any { slotCount(roster, it) > 0 }) "alive" else "dead"
        }
        val rv = ucd.radarVehicle ?: return "unknown"
        if (rv == NO_RADAR || rv !in 0 until SLOTS) return "none"
        return if (slotCount(roster, rv) > 0) "alive" else "dead"
    }

    // ================================================================ tables

    /** A unit table record: its radar slot (255 none), how it moves, and per slot its vehicle (a class-table number) and count. */
    class Ucd(val radarVehicle: Int?, val moveType: Int?, val vehicles: IntArray, val counts: IntArray)

    private val ucdCache = ConcurrentHashMap<String, Map<Int, Ucd>>()
    private val vehicleCache = ConcurrentHashMap<String, Map<Int, String>>()
    private val radarCache = ConcurrentHashMap<String, List<Int>>()

    private val UCD_FIELDS = setOf("RadarVehicle", "MoveType") + (0 until SLOTS).flatMap { listOf("VehicleCtIdx_$it", "ElementCount_$it") }

    /** The unit table's radar slot, move type, vehicles and counts, by record number (a unit's class entry's EntityIdx). */
    fun ucdTable(names: CampaignArchive.Names): Map<Int, Ucd> {
        val file = names.tables[CampaignArchive.UCD_FILE] ?: return emptyMap()
        val key = CampaignArchive.key(file)
        ucdCache[key]?.let { return it }
        val raw = CampaignArchive.table(file, "UCD", UCD_FIELDS)
        fun Map<String, String>.int(k: String): Int? = this[k]?.trim()?.toIntOrNull()
        val out = raw.mapValues { (_, f) ->
            Ucd(
                radarVehicle = f.int("RadarVehicle"), moveType = f.int("MoveType"),
                vehicles = IntArray(SLOTS) { f.int("VehicleCtIdx_$it") ?: -1 },
                counts = IntArray(SLOTS) { f.int("ElementCount_$it") ?: 0 },
            )
        }
        ucdCache[key] = out
        return out
    }

    /** Vehicle names by class-table number (the unit table's `VehicleCtIdx_n` → the vehicle table's name). */
    fun vehicleNames(names: CampaignArchive.Names): Map<Int, String> {
        val vcdFile = names.tables[CampaignArchive.VCD_FILE] ?: return emptyMap()
        val key = names.key
        vehicleCache[key]?.let { return it }
        val vcd = CampaignArchive.table(vcdFile, "VCD", setOf("Name"))
        val out = HashMap<Int, String>()
        for (n in 0 until names.ctCount + 16) {
            val c = names.ct(n + 100) ?: continue
            if (c.cls != 7) continue // a vehicle
            vcd[c.entityIdx]?.get("Name")?.trim()?.takeIf { it.isNotEmpty() }?.let { out[n] = it }
        }
        vehicleCache[key] = out
        return out
    }

    /**
     * The buildings of objective class [ocd] that are radars (their indices in the class's feature list, as the status
     * bytes number them): a feature whose feature record is named "Radar …".
     */
    private fun radarFeatures(set: Theaters.TheaterSet, t: Theaters.Theater, names: CampaignArchive.Names, ocd: Int, fcd: Map<Int, Map<String, String>>): List<Int> {
        if (ocd < 0 || fcd.isEmpty()) return emptyList()
        val key = names.key + "|" + ocd
        radarCache[key]?.let { return it }
        val fed = fedFile(set, t, ocd)?.let { CampaignArchive.table(it, "FED", setOf("FeatureCtIdx", "Value", "OffsetX", "OffsetY")) }.orEmpty()
        val out = fed.keys.sorted().mapIndexedNotNull { i, n ->
            val ct = fed.getValue(n)["FeatureCtIdx"]?.trim()?.toIntOrNull()?.let { names.ct(it + 100) } ?: return@mapIndexedNotNull null
            val name = fcd[ct.entityIdx]?.get("Name").orEmpty()
            if (RADAR_NAME.containsMatchIn(name)) i else null
        }
        radarCache[key] = out
        return out
    }

    /** `OCD_nnnnn/FED_nnnnn.XML`, where [CampaignTargets] looks for it: the 3D data folder, the object folder, then the default one. */
    private fun fedFile(set: Theaters.TheaterSet, t: Theaters.Theater, ocd: Int): File? {
        val id = ocd.toString().padStart(5, '0')
        val roots = listOfNotNull(set.threeDDataDir(t), set.objectDir(t), Theaters.resolveDir(set.data, "TerrData\\Objects"))
        for (r in roots) {
            val dir = Theaters.resolveDir(r, "ObjectiveRelatedData\\OCD_$id") ?: continue
            Theaters.resolveFile(dir, "FED_$id.XML")?.let { return it }
        }
        return null
    }

    /**
     * How WDP's nine radar feature indices fare in theater [t]'s feature table: those not named "Radar …" there, and the
     * feature records named so that WDP's list leaves out. For `--mapinteltest`.
     */
    fun radarRule(set: Theaters.TheaterSet, t: Theaters.Theater): Pair<List<String>, List<String>> {
        val fcd = CampaignArchive.table(set.classFile(t, FCD_FILE), "FCD", setOf("Name"))
        val wdpNot = WDP_RADAR_FCD.filter { !RADAR_NAME.containsMatchIn(fcd[it]?.get("Name").orEmpty()) }.map { "$it ${fcd[it]?.get("Name")?.trim() ?: "(none)"}" }
        val missed = fcd.keys.sorted().filter { it !in WDP_RADAR_FCD && RADAR_NAME.containsMatchIn(fcd.getValue(it)["Name"].orEmpty()) }
            .map { "$it ${fcd.getValue(it)["Name"]?.trim()}" }
        return wdpNot to missed
    }

    private inline fun <T> attempt(fallback: T, body: () -> T): T = try { body() } catch (_: Throwable) { fallback }
}
