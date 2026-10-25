package com.bmscompanion.desktop.ui.command

import com.bmscompanion.desktop.bridge.Bridge
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.Theaters
import com.bmscompanion.desktop.bridge.command.CampMap2D
import com.bmscompanion.desktop.bridge.command.CmdBattalion
import com.bmscompanion.desktop.bridge.command.CmdBrigade
import com.bmscompanion.desktop.bridge.command.CmdFlight
import com.bmscompanion.desktop.bridge.command.CmdPackage
import com.bmscompanion.desktop.bridge.command.CmdSquadron
import com.bmscompanion.desktop.bridge.command.CmdTaskForce
import com.bmscompanion.desktop.bridge.command.CmdUnit
import com.bmscompanion.desktop.bridge.command.CmdWaypoint
import com.bmscompanion.desktop.bridge.command.CommandBackup
import com.bmscompanion.desktop.bridge.command.CommandSave
import com.bmscompanion.desktop.bridge.command.ShippedSaves
import com.bmscompanion.desktop.bridge.command.UnitNames
import java.io.File
import kotlin.math.hypot

// What the map, the built-in tables and the hover box show of an open file: a plain picture of every objective, unit
// and team (the View… classes), made from the data layer's model of it (bridge/command's CommandSave) and made again
// after every edit. Every side is in it (the Command section is the PC's own window only). Editors work on the model
// itself (CmdDocument.core); these classes are only what is drawn and listed.

/** A route point: north/east feet, the altitude to fly, the waypoint's action (Strings 350 + action). */
class ViewPoint(val north: Double, val east: Double, val altFt: Double, val action: Int, val arrive: Long)

/** A team as the header names it: [colour] is BMS's team colour index (white, green, blue, brown, orange, yellow, red, grey). */
class ViewTeam(val n: Int, val name: String, val colour: Int, val inUse: Boolean)

/** Objective type groups, for the layers panel (type = the class table's Type, Strings 500 + type). */
enum class ObjGroup(val label: String, val types: Set<Int>) {
    AIRPORTS("Airbases and strips", setOf(1, 2)),
    ARMY("Army bases, forts, HARTS", setOf(3, 13, 30)),
    AIR_DEFENCE("SAM sites and radars", setOf(31, 21)),
    INDUSTRY("Factories, chemical, refineries", setOf(7, 11, 25)),
    POWER("Power and nuclear plants", setOf(17, 20)),
    CITIES("Cities, towns, villages", setOf(8, 28, 29)),
    LOGISTICS("Depots and rail terminals", setOf(10, 23)),
    COMMAND("Headquarters and radio towers", setOf(9, 22)),
    INFRASTRUCTURE("Bridges, roads, rail, passes", setOf(6, 12, 15, 18, 24, 26)),
    NAVAL("Ports, beaches, sea", setOf(4, 19, 27)),
    GEO("Borders, hill tops, nav beacons", setOf(0, 5, 14, 16)),
    ;
    companion object {
        fun of(type: Int): ObjGroup = entries.firstOrNull { type in it.types } ?: GEO
    }
}

/** Air units by role (the unit's class sub-type, Strings 540 + sub-type). */
enum class AirRole(val label: String, val subs: Set<Int>) {
    FIGHTER("Fighters", setOf(8, 9)),
    ATTACK("Attack", setOf(3)),
    BOMBER("Bombers", setOf(6)),
    SUPPORT("AWACS, JSTARS, ECM, SIGINT", setOf(5, 7, 10, 15)),
    TANKER("Tankers", setOf(13)),
    TRANSPORT("Transport", setOf(1)),
    RECON("Recon", setOf(11)),
    HELI("Helicopters", setOf(4, 12, 14)),
    OTHER("Other (ASW …)", setOf(2)),
    ;
    companion object {
        fun of(sub: Int): AirRole = entries.firstOrNull { sub in it.subs } ?: OTHER
    }
}

class ViewObjective(
    val ref: CmdRef, val id: CampaignArchive.VuId, val campId: Int, val name: String,
    /** class-table Type ([ObjGroup]) and its name */
    val type: Int, val typeName: String,
    /** the class table's EntityIdx: the objective class (OCD) */
    val ocd: Int,
    /** the owner now (a save's delta over the start), the first owner, the start's (or the record's) owner */
    val owner: Int, val firstOwner: Int, val startOwner: Int,
    val north: Double, val east: Double,
    val flags: Long,
    /** the objectives it links to */
    val links: List<CampaignArchive.VuId>?,
    val priority: Int?,
    val parent: CampaignArchive.VuId,
    val hasDelta: Boolean,
) {
    val group: ObjGroup get() = ObjGroup.of(type)
    val frontLine: Int get() = when {
        flags and 1L != 0L -> 1
        flags and 2L != 0L -> 2
        flags and 4L != 0L -> 3
        else -> 0
    }
    val samCapable: Boolean get() = flags and 0x400L != 0L
    val radar: Boolean get() = flags and 0x10000L != 0L
}

class ViewUnit(
    val ref: CmdRef, val id: CampaignArchive.VuId, val kind: CmdKind, val campId: Int, val owner: Int,
    /** "Falcon1", "36th FS (F-16C)", "500th SA-6 Air Defense Battalion" */
    val name: String,
    /** the unit's class ("F-16CM-40", "SA-6 Bn") and, for air units, the aircraft */
    val cls: String, val aircraft: String?,
    val north: Double, val east: Double,
    /** the class table's SubType (air: [AirRole]; land: the arm; sea: the ship) */
    val sub: Int,
    /** a land or sea unit's kind for [com.bmscompanion.app.ui.components.drawUnitSymbol] ("armor", "destroyer" …) */
    val symbol: String,
    /** the class table's EntityIdx: the unit class (UCD) */
    val ucd: Int,
    val route: List<ViewPoint>, val currentWp: Int,
    /** a flight's package, a battalion's brigade */
    val parent: String?,
    /** a flight's squadron */
    val squadron: String?,
    /** a package's flights, a brigade's battalions */
    val elements: List<String>,
    /** a squadron's base (an objective or a carrier's task force) */
    val airbase: String?,
    val orders: Int?, val division: Int?,
    /** the objective a ground unit is ordered to */
    val objective: String?,
    val unitFlags: Int,
    /** an air-defence unit's system ("SA-6") and its reach in feet, when known */
    val system: String?, var ringFt: Double? = null,
    /** a flight's mission (Strings 300 + mission) */
    val mission: Int? = null,
    val roster: Int = 0,
    /** the name without its type ("Falcon 3", "156th KFS"); [name] is the name with it ([UnitNames.full]) */
    val shortName: String = name,
    /** the type alone ("F-16CM-50 ×4", "SA-6 (2K12) · Air Defense", a package's mission) */
    val typeName: String = "",
) {
    val role: AirRole get() = AirRole.of(sub)
    val moving: Boolean get() = unitFlags and 0x400 != 0
    val dead: Boolean get() = unitFlags and 0x1 != 0
    val inactive: Boolean get() = unitFlags and 0x20000 != 0
    val air: Boolean get() = kind == CmdKind.FLIGHT || kind == CmdKind.SQUADRON || kind == CmdKind.PACKAGE
}

/** A division: the battalions and brigades of one team that share a division number (never saved; BMS derives it too). */
class ViewDivision(val ref: CmdRef, val owner: Int, val number: Int, val north: Double, val east: Double, val elements: List<String>)

/**
 * The picture of one file: [objectives] (the file's own in a campaign start, else its start's with the save's
 * deltas laid over), [units], [teams], [divisions]; [notes] say what could not be read.
 */
class CmdWorld(
    val objectives: List<ViewObjective>,
    val units: List<ViewUnit>,
    val divisions: List<ViewDivision>,
    val teams: List<ViewTeam>,
    val notes: List<String>,
    val sizeFt: Double,
) {
    private val objById = objectives.associateBy { it.ref.id }
    private val unitById = units.associateBy { it.ref.id }
    private val divById = divisions.associateBy { it.ref.id }
    fun objective(id: String?): ViewObjective? = id?.let { objById[it] }
    fun unit(id: String?): ViewUnit? = id?.let { unitById[it] }
    fun division(id: String?): ViewDivision? = id?.let { divById[it] }
    fun team(n: Int): ViewTeam? = teams.firstOrNull { it.n == n }

    /** Where [ref] is on the map (north, east), null when it has no place. */
    fun place(ref: CmdRef): Pair<Double, Double>? = when (ref.kind) {
        CmdKind.OBJECTIVE -> objective(ref.id)?.let { it.north to it.east }
        CmdKind.DIVISION -> division(ref.id)?.let { it.north to it.east }
        CmdKind.WAYPOINT -> unit(ref.id)?.route?.getOrNull(ref.sub)?.let { it.north to it.east }
        CmdKind.TEAM, CmdKind.PILOT, CmdKind.CHECK, CmdKind.OTHER -> null
        else -> unit(ref.id)?.let { it.north to it.east }
    }

    /** A short name for [ref], for menus and the status line. */
    fun nameOf(ref: CmdRef): String = when (ref.kind) {
        CmdKind.OBJECTIVE -> objective(ref.id)?.name
        CmdKind.DIVISION -> division(ref.id)?.let { "Division ${it.number} (${team(it.owner)?.name ?: "team ${it.owner}"})" }
        CmdKind.TEAM -> ref.id.toIntOrNull()?.let { team(it)?.name }
        CmdKind.WAYPOINT -> unit(ref.id)?.let { "${it.name} · waypoint ${ref.sub + 1}" }
        else -> unit(ref.id)?.name
    } ?: ref.kind.label
}

/**
 * An open file: where it is, its theater, the data layer's model of it ([core], the one every edit changes), its
 * picture ([world], made again after every change) and its editing half ([editing]: undo/redo, the saves).
 */
class CmdDocument(
    val file: File,
    val set: Theaters.TheaterSet,
    val theater: Theaters.Theater?,
    core: CommandSave,
) {
    var core: CommandSave = core
        private set
    val names: CampaignArchive.Names get() = core.names ?: CampaignArchive.names(set, theater)
    var world: CmdWorld = CommandWorldBuilder.build(this)
        private set
    var editing: CmdEditing? = null

    /** the app's theater id ("korea-kto") */
    val theaterId: String get() = theater?.appId ?: "unknown"
    val theaterName: String get() = theater?.name ?: core.cmp?.theaterName?.text?.trim()?.ifEmpty { null } ?: "Unknown theater"
    /** "campaign", "te" or "training" */
    val kind: String get() = core.missionKind
    val version: Int get() = core.version
    /** a campaign start: why, or null */
    val startWhy: String? get() = core.identity.takeIf { it.start }?.why
    /** one of BMS's own files (a start, a shipped TE or training): why; null for the pilot's own */
    val shippedWhy: String? get() = core.identity.takeIf { it.kind != ShippedSaves.Kind.PILOT }?.why
    /** the app's terrain map id ("korea") */
    val mapId: String? by lazy { theater?.let { CampMap2D.mapIdOf(set, it) } }

    /** The picture again from the model (after an edit). */
    fun refresh() {
        world = CommandWorldBuilder.build(this)
    }

    companion object {
        /** Opens [file]: the theater whose campaign folder holds it, else the theater BMS is set to. Never throws. */
        fun open(file: File, root: File? = null): Result<CmdDocument> = runCatching {
            val base = root ?: Bridge.install.baseDir?.let(::File) ?: error("Falcon BMS was not found on this PC.")
            val set = Theaters.at(base)
            val t = file.absoluteFile.parentFile?.let { set.owning(it) } ?: set.current(Bridge.install.theater)
            // the data layer's own rule: BMS's files of a theater's campaign folder are backed up once, when it is opened
            file.absoluteFile.parentFile?.let { dir -> runCatching { CommandBackup.ensureFolder(dir) } }
            val core = CommandSave.open(file, set)
            CmdDocument(file, set, t, core).also { d ->
                d.editing = runCatching { CommandSlots.editing?.invoke(d) }.getOrNull() ?: CoreEditing(d)
            }
        }
    }
}

/** Builds a [CmdWorld] from the data layer's model ([CommandSave]). */
object CommandWorldBuilder {
    private const val TYPE_STRINGS = 500
    private val LAND_KINDS = mapOf(
        1 to "airdefence", 2 to "airmobile", 3 to "armor", 4 to "cavalry", 5 to "engineer", 6 to "hq", 7 to "infantry",
        8 to "marine", 9 to "mechanized", 10 to "rocket", 11 to "artillery", 12 to "missile", 13 to "supply", 14 to "towed",
    )
    private val SEA_KINDS = mapOf(
        1 to "ship", 2 to "ship", 3 to "carrier", 4 to "cruiser", 5 to "destroyer", 6 to "frigate", 7 to "patrol", 8 to "ship", 9 to "tanker",
    )
    private const val CELL = CampaignArchive.FEET_PER_CELL

    fun build(d: CmdDocument): CmdWorld {
        val core = d.core
        val names = d.names
        val notes = ArrayList(core.problems)
        val h = core.cmp
        val sizeFt = ((h?.theaterSizeX ?: 1024).takeIf { it > 0 } ?: 1024) * CELL

        // ---- objectives: the file's own (a start), else its start's with the save's deltas
        val objectives = core.objectives.map { o ->
            val ct = names.ct(o.type)
            val type = ct?.type ?: 0
            val typeName = names.strings[TYPE_STRINGS + type]?.trim().orEmpty()
            val sn = o.simNorth
            val se = o.simEast
            val placed = sn.isFinite() && se.isFinite() && (sn != 0.0 || se != 0.0)
            ViewObjective(
                ref = CmdRef(CmdKind.OBJECTIVE, o.id.toString()), id = o.id, campId = o.campId,
                name = o.name.text.trim().ifEmpty { "$typeName ${o.campId}".trim() },
                type = type, typeName = typeName.ifEmpty { "Type $type" }, ocd = ct?.entityIdx ?: -1,
                owner = core.ownerNow(o), firstOwner = o.firstOwner, startOwner = o.owner,
                north = if (placed) sn else (o.y + 0.5) * CELL, east = if (placed) se else (o.x + 0.5) * CELL,
                flags = o.objFlags.toLong() and 0xFFFFFFFFL, links = o.links.map { it.id }.filter { !it.isNone },
                priority = o.priority, parent = o.parent, hasDelta = core.delta(o.id) != null,
            )
        }

        // ---- units
        val units = core.units.mapNotNull { u -> runCatching { unit(core, names, u) }.getOrNull() }

        // ---- divisions (derived)
        val divisions = units.filter { (it.kind == CmdKind.BATTALION || it.kind == CmdKind.BRIGADE) && (it.division ?: 0) > 0 }
            .groupBy { it.owner to it.division!! }
            .map { (k, us) ->
                ViewDivision(
                    CmdRef(CmdKind.DIVISION, "${k.first}:${k.second}"), k.first, k.second,
                    us.map { it.north }.average(), us.map { it.east }.average(), us.map { it.ref.id },
                )
            }

        // ---- teams (the header's colours, the team part's names)
        val used = units.map { it.owner }.toSet() + objectives.map { it.owner }.toSet()
        val teams = h?.teams.orEmpty().mapIndexed { n, tm ->
            val name = core.teamName(n).takeIf { it.isNotBlank() && !it.startsWith("Team ") } ?: tm.name.text.trim().ifEmpty { "Team $n" }
            ViewTeam(n, name, tm.color, n in used)
        }
        return CmdWorld(objectives, units, divisions, teams, notes, sizeFt)
    }

    private fun route(ws: List<CmdWaypoint>) =
        ws.map { ViewPoint((it.gridY + 0.5) * CELL, (it.gridX + 0.5) * CELL, it.gridZ * CampaignArchive.FEET_PER_ALT, it.action, it.arrive) }

    private fun unit(core: CommandSave, names: CampaignArchive.Names, u: CmdUnit): ViewUnit {
        val ct = names.ct(u.type)
        val sub = ct?.subType ?: -1
        // (a package's class is BMS's "Null")
        val cls = names.unitClass(u.type)?.takeIf { !it.equals("Null", ignoreCase = true) }.orEmpty()
        val north = (u.y + 0.5) * CELL
        val east = (u.x + 0.5) * CELL
        val ucd = ct?.entityIdx ?: -1
        val r = route(u.waypoints)
        fun id(v: CampaignArchive.VuId) = v.takeUnless { it.isNone }?.toString()
        // (the section's one way of naming a unit: callsign / squadron name / package number with the type)
        val full = UnitNames.full(core, u)
        val short = UnitNames.short(core, u)
        val type = UnitNames.type(core, u)
        return when (u) {
            is CmdFlight -> ViewUnit(
                CmdRef(CmdKind.FLIGHT, u.id.toString()), u.id, CmdKind.FLIGHT, u.campId, u.owner, full,
                cls, names.aircraft(u.type), north, east, sub, "air", ucd, r, u.currentWp,
                parent = id(u.packageId), squadron = id(u.squadronId), elements = emptyList(), airbase = null,
                orders = null, division = null, objective = null, unitFlags = u.unitFlags, system = null, mission = u.mission, roster = u.roster,
                shortName = short, typeName = type,
            )
            is CmdPackage -> ViewUnit(
                CmdRef(CmdKind.PACKAGE, u.id.toString()), u.id, CmdKind.PACKAGE, u.campId, u.owner, full, cls, null,
                north, east, sub, "air", ucd, r, u.currentWp,
                parent = null, squadron = null, elements = u.elements.filter { !it.isNone }.map { it.toString() }, airbase = null,
                orders = null, division = null, objective = null, unitFlags = u.unitFlags, system = null,
                mission = u.request.mission.takeIf { it > 0 } ?: core.flightsOf(u).firstOrNull()?.mission, shortName = short, typeName = type,
            )
            is CmdSquadron -> ViewUnit(
                CmdRef(CmdKind.SQUADRON, u.id.toString()), u.id, CmdKind.SQUADRON, u.campId, u.owner, full,
                cls, names.aircraft(u.type), north, east, sub, "air", ucd, r, u.currentWp,
                parent = null, squadron = null, elements = emptyList(), airbase = id(u.airbaseId),
                orders = null, division = null, objective = null, unitFlags = u.unitFlags, system = null, roster = u.roster,
                shortName = short, typeName = type,
            )
            is CmdBattalion -> ViewUnit(
                CmdRef(CmdKind.BATTALION, u.id.toString()), u.id, CmdKind.BATTALION, u.campId, u.owner, full, cls, null,
                north, east, sub, if (sub != 1 && cls.contains("Recon", true)) "recon" else LAND_KINDS[sub] ?: "other", ucd, r, u.currentWp,
                parent = id(u.parentId), squadron = null, elements = emptyList(), airbase = null,
                orders = u.orders, division = u.division, objective = id(u.aobj), unitFlags = u.unitFlags,
                system = if (sub == 1) names.aircraft(u.type) else null, roster = u.roster, shortName = short, typeName = type,
            )
            is CmdBrigade -> ViewUnit(
                CmdRef(CmdKind.BRIGADE, u.id.toString()), u.id, CmdKind.BRIGADE, u.campId, u.owner, full, cls, null,
                north, east, sub, LAND_KINDS[sub] ?: "hq", ucd, r, u.currentWp,
                parent = null, squadron = null, elements = u.elements.filter { !it.isNone }.map { it.toString() }, airbase = null,
                orders = u.orders, division = u.division, objective = id(u.aobj), unitFlags = u.unitFlags, system = null, roster = u.roster,
                shortName = short, typeName = type,
            )
            is CmdTaskForce -> ViewUnit(
                CmdRef(CmdKind.TASKFORCE, u.id.toString()), u.id, CmdKind.TASKFORCE, u.campId, u.owner, full, cls, null,
                north, east, sub, SEA_KINDS[sub] ?: "ship", ucd, r, u.currentWp,
                parent = null, squadron = null, elements = emptyList(), airbase = null, orders = u.orders, division = null, objective = null,
                unitFlags = u.unitFlags, system = null, roster = u.roster, shortName = short, typeName = type,
            )
        }
    }

    /** Ground orders by number (Strings has no list of them): BMS's GORD_ order. */
    val ORDERS = listOf("Reserve", "Capture", "Secure", "Assault", "Airborne", "Commando", "Defend", "Support", "Repair", "Air defence", "Recon", "Radar")

    fun distanceNm(aN: Double, aE: Double, bN: Double, bE: Double) = hypot(aN - bN, aE - bE) / 6076.12
}

/**
 * Who is on which side seen from team [team] (the frames of the unit symbols): its own team and the team that controls
 * it are friendly; else the stance of the team (or of its controller) toward the owner — allied and friendly
 * friendly, hostile and at war hostile, the rest neutral.
 */
class Sides(private val core: CommandSave, val team: Int) {
    private val rec = core.teams.getOrNull(team)?.team
    private val controller = rec?.cteam ?: team
    private val ctrl = core.teams.getOrNull(controller)?.team
    fun of(owner: Int): String {
        if (owner == team || owner == controller) return "friendly"
        val s = rec?.stance?.getOrNull(owner)?.takeIf { it != 0 } ?: ctrl?.stance?.getOrNull(owner) ?: 0
        return when (s) {
            1, 2 -> "friendly"
            4, 5 -> "hostile"
            0 -> "unknown"
            else -> "neutral"
        }
    }
}
