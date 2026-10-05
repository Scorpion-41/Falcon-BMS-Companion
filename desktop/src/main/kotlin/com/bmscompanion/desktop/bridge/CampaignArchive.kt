package com.bmscompanion.desktop.bridge

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * A Falcon BMS campaign, Tactical Engagement or training save (`.cam`, `.tac`, `.trn`) read **whole**: the campaign
 * header, every unit record (flights, packages, squadrons, battalions, brigades, task forces), the objectives of a start
 * file, and the pilot list — which is what WDP's File > Open reads, and what the Planner's Open mission window, flight
 * picker and Send to Mission are built on.
 *
 * **Where the layouts come from.** WDP's own reader (`BMSUtils.dll`, decompiled: `UniFile`, `Unit`, `Flight`,
 * `Package`, `Squadron`, `Battalion`, `Brigade`, `TaskForce`, `Waypoint`, `Objective`), ported through the research
 * reader `r3/camlib.mjs` and checked on every save of an installed Falcon BMS 4.38.1: 517 files in 19 theaters, format
 * versions 107 to 110, every unit part walked to its last byte with exactly the record count its header gives and every
 * record's copy of its type equal to its type (`--camtest`). [PlannedRoutes] finds flights by a signature instead; this
 * reader does not guess — a record it cannot read ends the walk, and the reason is kept ([UnitWalk.stop]).
 *
 * **The archive.** An `i32` at 0 is the offset of a directory: an `i32` count, then per part a length-prefixed name, an
 * offset and a length. A start can carry bytes after the directory, left over from an earlier, longer write (`Save1.cam`
 * 325, `Te_New_Nt.tac` 35,676): the directory is always used, never the file size. The parts are `.cmp` (the campaign
 * header), `.uni` (units), `.obj` (objectives — only a start or template has them), `.tea`, `.plt`, `.ver` and others;
 * `.cmp`, `.uni` and `.obj` are packed ([MissionArchive.unpack]).
 *
 * **Positions.** A waypoint or a unit sits in a grid **cell** (x east, y north, a kilometre each): the position is the
 * cell's middle, `(cell + 0.5) × 3279.98 ft`, north first as everywhere in the app. That is exactly what BMS writes into
 * the mission file beside the save (`Auto Save.ini`'s `target_n`). A waypoint's altitude is in tens of feet; its times
 * are campaign milliseconds (day 1 00:00 = 0). An objective (from version 103) carries its exact position in feet,
 * north then east.
 *
 * **Names** come from the theater's own tables, found through [Theaters] ([names]): the class table (`Falcon4_CT.xml`)
 * says what each unit type is, `Falcon4_UCD.xml` → `Falcon4_VCD.xml` gives the aircraft, `Falcon4_WCD.xml` the
 * weapons, and the campaign's `Strings.txt` the words: 300 + mission, 350 + waypoint action, 400 + task sentence,
 * 2000 + callsign.
 *
 * Read-only, whole-file (no lock is kept), and nothing throws: a file that cannot be read or parsed comes back with
 * [Save.errors] saying why, in a sentence. Parsed saves and walked objectives are cached by path, size and time.
 */
object CampaignArchive {
    /** One campaign grid cell in theater feet (a kilometre; the same constant as [PlannedRoutes]). */
    const val FEET_PER_CELL = 1000.0 * 3.27998

    /** A waypoint's altitude is kept in tens of feet. */
    const val FEET_PER_ALT = 10.0

    /** `Strings.txt` offsets: the mission name, the waypoint word, the task sentence, the callsign word. */
    const val MISSION_STRINGS = 300
    const val ACTION_STRINGS = 350
    const val TASK_STRINGS = 400
    const val CALLSIGN_STRINGS = 2000

    /** A player slot of a flight that no pilot has. */
    const val NO_PLAYER = 255

    /** The unit flag of a package whose planning is finished: it keeps only its request. */
    private const val PACKAGE_FINAL = 0x100000

    /** A route longer than this is not a route: the record is misread (WDP's own guard). */
    private const val MOST_WAYPOINTS = 500

    /** Text in the save (names, the scenario, the squadron names) is Windows' ANSI code page. */
    private val TEXT: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    // ================================================================ ids

    /** A campaign entity's id: a number and the machine that created it ("21289/0"). */
    data class VuId(val num: Long, val creator: Long) {
        val isNone: Boolean get() = num == 0L && creator == 0L
        override fun toString() = "$num/$creator"

        companion object {
            val NONE = VuId(0, 0)

            /** "num/creator" (what [toString] writes, and what `CampRef.flight` carries). */
            fun parse(s: String?): VuId? {
                val parts = s?.trim()?.split('/') ?: return null
                if (parts.size != 2) return null
                val a = parts[0].toLongOrNull() ?: return null
                val b = parts[1].toLongOrNull() ?: return null
                return VuId(a, b)
            }
        }
    }

    // ================================================================ the archive's directory

    /** One part of the archive: its name ("Auto Save.uni"), its extension in lower case, where it is and how long. */
    data class Part(val name: String, val ext: String, val at: Int, val length: Int)

    /**
     * The archive's directory. [at] is where it starts and [end] where it ends; [tail] counts the bytes after it (left
     * over from an earlier, longer write). [error] says why the directory could not be read, when it could not.
     */
    class Directory(val at: Int, val end: Int, val size: Int, val parts: List<Part>, val error: String?) {
        val tail: Int get() = if (error == null) size - end else 0
        fun part(ext: String): Part? = parts.firstOrNull { it.ext == ext }
        fun has(ext: String): Boolean = part(ext) != null
    }

    /** The directory of the whole file [blob]. */
    fun directory(blob: ByteArray): Directory = directory(blob, 0, blob.size)

    /**
     * The directory, reading only the file's first four bytes and what follows the directory — the cheap read a file
     * listing needs (kind, start or not). Read-only; the file is closed again at once.
     */
    fun directoryOf(file: File): Directory = try {
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            if (size < 8 || size > Int.MAX_VALUE) return Directory(0, 0, size.toInt(), emptyList(), "${file.name} is not a save ($size bytes).")
            val head = ByteArray(4)
            raf.readFully(head)
            val at = MissionArchive.int32(head, 0)
            if (at < 4 || at + 4 > size) return Directory(at, 0, size.toInt(), emptyList(), "The directory of ${file.name} lies outside the file.")
            val rest = ByteArray((size - at).toInt())
            raf.seek(at.toLong())
            raf.readFully(rest)
            directory(rest, at, size.toInt())
        }
    } catch (e: Throwable) {
        Directory(0, 0, 0, emptyList(), "Could not read ${file.name}: ${reason(e)}.")
    }

    /** The directory held in [b], where `b[0]` is file offset [base] (0 for the whole file). */
    private fun directory(b: ByteArray, base: Int, size: Int): Directory {
        if (size < 8) return Directory(0, 0, size, emptyList(), "The file is too short to be a save ($size bytes).")
        val at = if (base == 0) MissionArchive.int32(b, 0) else base
        if (at < 4 || at + 4 > size) return Directory(at, 0, size, emptyList(), "Its directory offset ($at) lies outside the file ($size bytes).")
        var p = at - base
        val count = MissionArchive.int32(b, p)
        if (count <= 0 || count > 64) return Directory(at, at + 4, size, emptyList(), "Its directory lists $count parts.")
        p += 4
        val parts = ArrayList<Part>(count)
        for (i in 0 until count) {
            if (p >= b.size) return Directory(at, p + base, size, parts, "Its directory runs past the end of the file (part ${i + 1} of $count).")
            val len = b[p].toInt() and 0xFF
            p++
            if (p + len + 8 > b.size) return Directory(at, p + base, size, parts, "Its directory runs past the end of the file (part ${i + 1} of $count).")
            val name = String(b, p, len, Charsets.ISO_8859_1)
            p += len
            val pat = MissionArchive.int32(b, p)
            val plen = MissionArchive.int32(b, p + 4)
            p += 8
            if (pat < 0 || plen < 0 || pat.toLong() + plen > size) {
                return Directory(at, p + base, size, parts, "Part $name ($pat + $plen bytes) lies outside the file ($size bytes).")
            }
            parts += Part(name, name.substringAfterLast('.', "").lowercase(), pat, plen)
        }
        return Directory(at, p + base, size, parts, null)
    }

    // ================================================================ the campaign header (.cmp)

    /**
     * One of the campaign's eight teams (the index is the team number units carry as their owner). [motto] is the
     * header's copy of the team's motto (200 bytes; WDP's `TeamBasicInfoTable`): a TE's author writes the mission's
     * text there, and WDP's Briefing page shows it as the situation when the team part's own copy is empty.
     */
    class Team(val n: Int, val flag: Int, val colour: Int, val name: String, val motto: String = "")

    /**
     * A squadron as the campaign header lists it: where it is based (feet, north and east), its id, and — from version
     * 102 — its airbase's name and its own name ("36th FS (F-16C)"). The unit part does not carry the name.
     */
    class CmpSquadron(
        val north: Double, val east: Double, val id: VuId, val descIdx: Int, val nameId: Int, val airbaseIcon: Int,
        val path: Int, val specialty: Int, val strength: Int, val country: Int,
        val airbase: String, val flags: Int, val campId: Int, val texSet: Int, val name: String,
    )

    /**
     * The campaign header: the clock, the eight teams, the bullseye (cells, x east and y north, as WDP reads it — see
     * R3-PLAN A23), the theater's own name for itself, the start file the save was made from ([scenario], "save1"),
     * the name BMS saved it under ([saveFile]), the campaign's name ([uiName], "???" when it has none), the player's
     * squadron, and every squadron with its name. [unread] is what is left after the last field this reader knows (4
     * bytes in most played saves; their meaning is not known).
     */
    class Header(
        val currentTime: Long, val startTime: Long, val timeLimit: Long,
        val teVictoryPoints: Int, val teType: Int, val teTeams: Int, val teTeam: Int, val teFlags: Int,
        val teams: List<Team>,
        val lastMajorEvent: Long, val timeStamp: Int, val group: Int, val brief: Int,
        val theaterSizeX: Int, val theaterSizeY: Int,
        val currentDay: Int, val activeTeam: Int, val dayZero: Int, val endGame: Int, val situation: Int,
        val bullseyeName: Int, val bullseyeX: Int, val bullseyeY: Int,
        val theaterName: String, val scenario: String, val saveFile: String, val uiName: String,
        val playerSquadron: VuId,
        val recentEvents: Int, val priorityEvents: Int, val lastIndexNum: Int,
        val squadronsAt: Int, val squadrons: List<CmpSquadron>,
        val tempo: Int?, val creationTime: Long?,
        val parsedTo: Int, val size: Int,
    ) {
        val unread: Int get() = size - parsedTo

        /** The campaign's own name, or null when it has none ("???"). */
        val title: String? get() = uiName.trim().takeIf { it.isNotEmpty() && it != "???" }

        private val byId by lazy { squadrons.associateBy { it.id } }
        fun squadron(id: VuId?): CmpSquadron? = id?.let { byId[it] }
        fun team(n: Int): Team? = teams.getOrNull(n)
    }

    private fun header(buf: ByteArray, ver: Int): Header {
        val r = Cursor(buf)
        val currentTime = r.u32()
        val startTime = r.u32()
        val timeLimit = r.u32()
        val teVictoryPoints = r.i32()
        val teType = r.i32()
        val teTeams = r.i32()
        r.skip(32 + 32) // per team: the TE's start counts and victory counts
        val teTeam = r.i32()
        r.skip(32) // per team: points
        val teFlags = r.i32()
        val teams = (0 until 8).map { n ->
            val flag = r.u8()
            val colour = r.u8()
            val name = r.str(20)
            val motto = r.str(200)
            Team(n, flag, colour, name, motto)
        }
        val lastMajorEvent = r.u32()
        r.skip(12)
        val timeStamp = r.i16()
        val group = r.i16()
        r.skip(8) // four ratios
        val brief = r.i16()
        val sizeX = r.i16()
        val sizeY = r.i16()
        val currentDay = r.u8()
        val activeTeam = r.u8()
        val dayZero = r.u8()
        val endGame = r.u8()
        val situation = r.u8()
        r.skip(2) // enemy air and air-defence experience
        val bullseyeName = r.u8()
        val bullseyeX = r.i16()
        val bullseyeY = r.i16()
        val theaterName = r.str(40)
        val scenario = r.str(40)
        val saveFile = r.str(40)
        val uiName = r.str(40)
        val playerSquadron = r.id()
        // the recent and the priority events: a fixed 20 bytes and a sized text each
        val eventCounts = IntArray(2)
        for (k in 0..1) {
            val n = r.i16()
            eventCounts[k] = n
            repeat(maxOf(0, n)) {
                r.skip(20)
                val len = r.u16()
                r.skip(len)
            }
        }
        val mapSize = r.u16()
        r.skip(mapSize) // the campaign map
        val lastIndexNum = r.i16()
        val squadronCount = r.i16()
        val squadronsAt = r.p
        val squadrons = ArrayList<CmpSquadron>(maxOf(0, squadronCount))
        repeat(maxOf(0, squadronCount)) {
            val north = r.f32().toDouble()
            val east = r.f32().toDouble()
            val id = r.id()
            val descIdx = r.i16()
            val nameId = r.i16()
            val airbaseIcon = r.i16()
            val path = r.i16()
            val specialty = r.u8()
            val strength = r.u8()
            val country = r.u8()
            if (ver >= 102) {
                val airbase = r.str(80)
                r.skip(1)
                val flags = r.i32()
                val campId = r.i16()
                val texSet = r.i16()
                val name = r.str(80)
                squadrons += CmpSquadron(north, east, id, descIdx, nameId, airbaseIcon, path, specialty, strength, country, airbase, flags, campId, texSet, name)
            } else {
                val airbase = r.str(40)
                r.skip(1)
                squadrons += CmpSquadron(north, east, id, descIdx, nameId, airbaseIcon, path, specialty, strength, country, airbase, 0, 0, 0, "")
            }
        }
        // the tail: tempo, the creator's address, the creation time and a random number
        var tempo: Int? = null
        var creationTime: Long? = null
        if (r.left >= 13) {
            tempo = r.u8()
            r.skip(4)
            creationTime = r.u32()
            r.skip(4)
        }
        return Header(
            currentTime, startTime, timeLimit, teVictoryPoints, teType, teTeams, teTeam, teFlags, teams,
            lastMajorEvent, timeStamp, group, brief, sizeX, sizeY, currentDay, activeTeam, dayZero, endGame, situation,
            bullseyeName, bullseyeX, bullseyeY, theaterName, scenario, saveFile, uiName, playerSquadron,
            eventCounts[0], eventCounts[1], lastIndexNum, squadronsAt, squadrons, tempo, creationTime, r.p, buf.size,
        )
    }

    // ================================================================ waypoints and units (.uni)

    /** One of the four seats' own target on a waypoint (save version 104 and later). */
    class Designated(val id: VuId, val building: Int)

    /**
     * One waypoint. [gx]/[gy] are the grid cell (x east, y north), [gz] the altitude in tens of feet; [north]/[east]
     * are the cell's middle in feet. [target] is what the waypoint is tasked against (an objective or a unit) and
     * [building] the feature of it (255 for the whole); [designated] is each seat's own target, from version 104.
     */
    class Waypoint(
        val at: Int, val haves: Int, val gx: Int, val gy: Int, val gz: Int, val arrive: Long,
        val action: Int, val routeAction: Int, val formation: Int, val flags: Long,
        val target: VuId?, val building: Int, val designated: List<Designated>?, val depart: Long,
    ) {
        val north: Double get() = (gy + 0.5) * FEET_PER_CELL
        val east: Double get() = (gx + 0.5) * FEET_PER_CELL
        val altFt: Double get() = gz * FEET_PER_ALT
    }

    private fun waypoint(r: Cursor, ver: Int): Waypoint {
        val at = r.p
        val haves = r.u8()
        val gx = r.i16()
        val gy = r.i16()
        val gz = r.i16()
        val arrive = r.u32()
        val action = r.u8()
        val routeAction = r.u8()
        val formation = r.u8()
        val flags = if (ver < 73) r.u16().toLong() else r.u32()
        var target: VuId? = null
        var building = 255
        var designated: List<Designated>? = null
        if (haves and 2 != 0) {
            target = r.id()
            building = r.u8()
            if (ver > 103) designated = List(4) { Designated(r.id(), r.u8()) }
        }
        val depart = if (haves and 1 != 0) r.u32() else arrive
        if (ver in 86..99) r.skip(4)
        return Waypoint(at, haves, gx, gy, gz, arrive, action, routeAction, formation, flags, target, building, designated, depart)
    }

    /** What a unit record is, as the theater's class table says (domain 2 air, 3 land, 4 sea). */
    enum class Kind { FLIGHT, PACKAGE, SQUADRON, BATTALION, BRIGADE, TASKFORCE }

    /**
     * The fields every unit record starts with. [x]/[y] are the unit's grid cell (east, north), [owner] its team,
     * [campId] the number the briefing prints ("Flt #", "Package #"); [currentWp] the waypoint it is flying to.
     */
    class UnitCore(
        val at: Int, val type: Int, val id: VuId, val entityType: Int, val x: Int, val y: Int, val z: Float,
        val spotTime: Long, val spotted: Int, val baseFlags: Int, val owner: Int, val campId: Int,
        val lastCheck: Long, val roster: Int, val unitFlags: Int, val destX: Int, val destY: Int,
        val target: VuId, val cargo: VuId?, val moved: Int, val losses: Int, val tactic: Int,
        val currentWp: Int, val nameId: Int, val reinforcement: Int, val waypoints: List<Waypoint>,
    )

    private fun core(r: Cursor, ver: Int, at: Int, type: Int): UnitCore {
        val id = r.id()
        val entityType = r.u16()
        val x = r.i16()
        val y = r.i16()
        val z = if (ver >= 70) r.f32() else 0f
        val spotTime = r.u32()
        val spotted = r.i16()
        val baseFlags = r.i16()
        val owner = r.u8()
        val campId = r.i16()
        val lastCheck = r.u32()
        val roster = r.i32()
        val unitFlags = r.i32()
        val destX = r.i16()
        val destY = r.i16()
        val target = r.id()
        val cargo = if (ver > 1) r.id() else null
        val moved = r.u8()
        val losses = r.u8()
        val tactic = r.u8()
        if (ver in 83..99) r.skip(4)
        val currentWp = if (ver >= 71) r.u16() else r.u8()
        val nameId = r.i16()
        val reinforcement = r.i16()
        val n = if (ver >= 71) r.u16() else r.u8()
        val waypoints = if (n > MOST_WAYPOINTS) emptyList() else List(n) { waypoint(r, ver) }
        return UnitCore(
            at, type, id, entityType, x, y, z, spotTime, spotted, baseFlags, owner, campId, lastCheck, roster,
            unitFlags, destX, destY, target, cargo, moved, losses, tactic, currentWp, nameId, reinforcement, waypoints,
        )
    }

    /** A unit record: the common fields ([core]), what kind it is, and where it ends in the unpacked part. */
    sealed class CampUnit(val core: UnitCore, val kind: Kind, val end: Int) {
        val id: VuId get() = core.id
        val type: Int get() = core.type
        val owner: Int get() = core.owner
        val campId: Int get() = core.campId
        val waypoints: List<Waypoint> get() = core.waypoints
        val north: Double get() = (core.y + 0.5) * FEET_PER_CELL
        val east: Double get() = (core.x + 0.5) * FEET_PER_CELL
    }

    /** One aircraft's loadout (or the whole flight's): 16 hardpoints, a weapon id (WCD) and a count on each. */
    class Loadout(val weapons: List<Int>, val counts: List<Int>) {
        /** (weapon id, count) for every loaded hardpoint, in hardpoint order. */
        val stores: List<Pair<Int, Int>> get() = weapons.indices.filter { weapons[it] != 0 && counts[it] != 0 }.map { weapons[it] to counts[it] }
    }

    /**
     * A flight: its route ([UnitCore.waypoints]), fuel and laser codes per aircraft, time on target, loadouts (1, 2 or
     * 4 — one per aircraft, or one for all), mission, package and squadron, the four seats (pilots, aircraft,
     * [playerSlots] — 255 for none), its callsign (word [callsignId] from the strings, and [callsignNum]) and, from
     * version 108, the TACAN it is given.
     */
    class Flight(
        core: UnitCore, end: Int,
        val fuelBurnt: Int, val fuelInitial: List<Int>, val laser: List<Int>,
        val lastMove: Long, val lastCombat: Long, val tot: Long, val missionOver: Long, val missionTarget: Int,
        val loadouts: List<Loadout>,
        val mission: Int, val oldMission: Int, val lastDirection: Int, val priority: Int, val missionId: Int,
        val evalFlags: Int, val missionContext: Int,
        val packageId: VuId, val squadronId: VuId, val requester: VuId?,
        val slots: List<Int>, val pilots: List<Int>, val planeStats: List<Int>, val playerSlots: List<Int>, val lastPlayerSlot: Int,
        val callsignId: Int, val callsignNum: Int, val refuel: Long,
        val tacanChannel: List<Int>, val tacanBand: List<Int>, val cft: List<Int>,
        val totAt: Int, val callsignAt: Int,
    ) : CampUnit(core, Kind.FLIGHT, end) {
        /** How many aircraft: the seats with an aircraft in them. */
        val aircraftCount: Int get() = planeStats.count { it != 0 }

        /** A pilot (a human) is in one of the seats. */
        val hasPlayer: Boolean get() = playerSlots.any { it != NO_PLAYER }

        /** The seats (0-3) a pilot is in. */
        val playerSeats: List<Int> get() = playerSlots.indices.filter { playerSlots[it] != NO_PLAYER }
    }

    private fun flight(r: Cursor, ver: Int, c: UnitCore): Flight {
        r.f32() // the flight's height, again
        val fuelBurnt = r.i32()
        var fuelInitial = emptyList<Int>()
        var laser = emptyList<Int>()
        if (ver == 74 || ver >= 100) {
            fuelInitial = List(4) { r.i32() }
            laser = List(4) { r.i16() }
        }
        val lastMove = r.u32()
        val lastCombat = r.u32()
        val totAt = r.p
        val tot = r.u32()
        val missionOver = r.u32()
        val missionTarget = r.i16()
        if (ver in 83..99) r.skip(6)
        val nl = r.u8()
        val loadouts = List(nl) {
            val ids = List(16) { if (ver >= 73) r.u16() else r.u8() }
            val counts = List(16) { r.u8() }
            Loadout(ids, counts)
        }
        val mission = r.u8()
        val oldMission = if (ver > 65) r.u8() else mission
        val lastDirection = r.u8()
        val priority = r.u8()
        val missionId = r.u8()
        if (ver < 14) r.skip(1)
        val evalFlags = r.u8()
        val missionContext = if (ver > 65) r.u8() else 0
        val packageId = r.id()
        val squadronId = r.id()
        val requester = if (ver > 65) r.id() else null
        val slots = List(4) { r.u8() }
        val pilots = List(4) { r.u8() }
        val planeStats = List(4) { r.u8() }
        val playerSlots = List(4) { r.u8() }
        val lastPlayerSlot = r.u8()
        val callsignAt = r.p
        val callsignId = r.u8()
        val callsignNum = r.u8()
        val refuel = r.u32()
        if (ver >= 105) r.skip(16) // the paint (texture set) per aircraft
        var tacanChannel = emptyList<Int>()
        var tacanBand = emptyList<Int>()
        if (ver >= 108) {
            tacanChannel = List(4) { r.u8() }
            tacanBand = List(4) { r.u8() }
        }
        val cft = if (ver >= 109) List(4) { r.u8() } else emptyList()
        if (ver in 83..99) r.skip(244)
        return Flight(
            c, r.p, fuelBurnt, fuelInitial, laser, lastMove, lastCombat, tot, missionOver, missionTarget, loadouts,
            mission, oldMission, lastDirection, priority, missionId, evalFlags, missionContext, packageId, squadronId,
            requester, slots, pilots, planeStats, playerSlots, lastPlayerSlot, callsignId, callsignNum, refuel,
            tacanChannel, tacanBand, cft, totAt, callsignAt,
        )
    }

    /** What a package was planned for: its mission, aircraft, target and time on target. */
    class PackageRequest(
        val mission: Int, val aircraft: Int, val context: Int, val roe: Int,
        val requester: VuId, val target: VuId, val secondary: VuId?, val tot: Long?,
        val tx: Int?, val ty: Int?, val priority: Int?, val actionType: Int?,
    )

    /**
     * A package: its flights ([elements]) and the support flights it is tied to — [interceptor], [awacs], [jstars],
     * [ecm] and [tanker] (none is [VuId.NONE]). A package whose planning is finished ([final]) keeps only its request;
     * one still being planned also carries its take-off time, ingress and egress.
     */
    class Package(
        core: UnitCore, end: Int,
        val elements: List<VuId>, val interceptor: VuId, val awacs: VuId?, val jstars: VuId?, val ecm: VuId?, val tanker: VuId?,
        val waitCycles: Int, val final: Boolean,
        val flightsPlanned: Int?, val waitFor: Int?, val takeoff: Long?, val tpTime: Long?, val packageFlags: Long?, val caps: Int?,
        val requests: Int, val responses: Int,
        val ingress: List<Waypoint>, val egress: List<Waypoint>, val request: PackageRequest,
    ) : CampUnit(core, Kind.PACKAGE, end)

    private fun pkg(r: Cursor, ver: Int, c: UnitCore): Package {
        val n = r.u8()
        val elements = List(n) { r.id() }
        val interceptor = r.id()
        var awacs: VuId? = null
        var jstars: VuId? = null
        var ecm: VuId? = null
        var tanker: VuId? = null
        if (ver >= 7) {
            awacs = r.id(); jstars = r.id(); ecm = r.id(); tanker = r.id()
        }
        val waitCycles = r.u8()
        val final = c.unitFlags and PACKAGE_FINAL != 0
        if (final && waitCycles == 0) {
            val requests = r.i16()
            if (ver < 35) r.skip(2)
            val responses = r.i16()
            val mission = r.u8()
            val aircraft = r.u8()
            val context = r.u8()
            val roe = r.u8()
            val requester = r.id()
            val target = r.id()
            val tot = if (ver >= 16) r.u32() else null
            if (ver >= 35) r.skip(1)
            if (ver >= 41) r.skip(2)
            val request = PackageRequest(mission, aircraft, context, roe, requester, target, null, tot, null, null, null, null)
            return Package(
                c, r.p, elements, interceptor, awacs, jstars, ecm, tanker, waitCycles, true,
                null, null, null, null, null, null, requests, responses, emptyList(), emptyList(), request,
            )
        }
        val flights = r.u8()
        val waitFor = r.i16()
        r.skip(16) // ingress, egress, break and turn points (x, y each)
        val takeoff = r.u32()
        val tpTime = r.u32()
        val packageFlags = r.u32()
        val caps = r.i16()
        val requests = r.i16()
        if (ver < 35) r.skip(2)
        val responses = r.i16()
        val ni = r.u8()
        val ingress = List(ni) { waypoint(r, ver) }
        val ne = r.u8()
        val egress = List(ne) { waypoint(r, ver) }
        val requester = r.id()
        val target = r.id()
        val secondary = r.id()
        r.id() // the package it was for
        r.u8(); r.u8() // who asked, and against whom
        r.skip(2)
        val tot = r.u32()
        val tx = r.i16()
        val ty = r.i16()
        r.u32() // flags
        r.i16() // capabilities
        r.i16() // target number
        r.i16() // speed
        r.i16() // match
        val priority = r.i16()
        r.u8() // time-on-target type
        val actionType = r.u8()
        val mission = r.u8()
        val aircraft = r.u8()
        val context = r.u8()
        val roe = r.u8()
        if (ver >= 35) r.skip(12)
        val request = PackageRequest(mission, aircraft, context, roe, requester, target, secondary, tot, tx, ty, priority, actionType)
        return Package(
            c, r.p, elements, interceptor, awacs, jstars, ecm, tanker, waitCycles, final,
            flights, waitFor, takeoff, tpTime, packageFlags, caps, requests, responses, ingress, egress, request,
        )
    }

    /** A squadron: its [airbase] (an objective id), fuel and specialty. Its name is in the header ([Header.squadron]). */
    class Squadron(
        core: UnitCore, end: Int,
        val fuel: Int, val specialty: Int, val airbase: VuId, val hotSpot: VuId, val patch: Int?,
        val retaskAt: Long?, val relocate: Int?, val storesAt: Int,
    ) : CampUnit(core, Kind.SQUADRON, end)

    private fun squadron(r: Cursor, ver: Int, c: UnitCore): Squadron {
        val fuel = r.i32()
        val specialty = r.u8()
        if (ver >= 101) r.skip(16) // ratings
        val stores = when {
            ver < 69 -> 200
            ver == 73 -> 600
            ver == 74 || ver >= 100 -> 1000
            ver >= 83 -> 600
            else -> 220
        }
        val storesAt = r.p
        r.skip(stores)
        r.skip(48 * (if (ver >= 47) 10 else 8)) // the pilots
        r.skip(64) // the schedule
        val airbase = r.id()
        val hotSpot = r.id()
        if (ver in 6..15) r.skip(8)
        r.skip(16) // ratings
        r.skip(12) // kills and missions
        r.skip(1)
        if (ver >= 9) r.skip(1)
        val patch = when {
            ver >= 100 -> r.u16()
            ver >= 45 -> r.u8()
            else -> null
        }
        if (ver in 83..99) r.skip(3)
        var retaskAt: Long? = null
        var relocate: Int? = null
        if (ver >= 102) {
            retaskAt = r.u32()
            relocate = r.u8()
        }
        if (ver >= 105) r.i32() // the paint
        return Squadron(c, r.p, fuel, specialty, airbase, hotSpot, patch, retaskAt, relocate, storesAt)
    }

    /** A ground battalion: its orders, division, the objective it is ordered to, and its parent brigade. */
    class Battalion(
        core: UnitCore, end: Int,
        val orders: Int, val division: Int, val objective: VuId,
        val lastMove: Long, val lastCombat: Long, val parent: VuId, val lastObjective: VuId, val position: Int,
    ) : CampUnit(core, Kind.BATTALION, end)

    /** A ground brigade: its orders, division, objective and battalions ([elements]). */
    class Brigade(
        core: UnitCore, end: Int,
        val orders: Int, val division: Int, val objective: VuId, val elements: List<VuId>,
    ) : CampUnit(core, Kind.BRIGADE, end)

    /** A naval task force (a carrier group among them): its orders and supply. */
    class TaskForce(core: UnitCore, end: Int, val orders: Int, val supply: Int) : CampUnit(core, Kind.TASKFORCE, end)

    private fun battalion(r: Cursor, ver: Int, c: UnitCore): Battalion {
        val orders = r.u8()
        val division = r.i16()
        val objective = r.id()
        val lastMove = r.u32()
        val lastCombat = r.u32()
        val parent = r.id()
        val lastObjective = r.id()
        r.skip(5)
        if (ver < 15) r.skip(1)
        val position = r.u8()
        if (ver in 83..99 && position > 0) r.skip(1)
        return Battalion(c, r.p, orders, division, objective, lastMove, lastCombat, parent, lastObjective, position)
    }

    private fun brigade(r: Cursor, c: UnitCore): Brigade {
        val orders = r.u8()
        val division = r.i16()
        val objective = r.id()
        val n = r.u8()
        val elements = List(n) { r.id() }
        return Brigade(c, r.p, orders, division, objective, elements)
    }

    private fun taskForce(r: Cursor, ver: Int, c: UnitCore): TaskForce {
        val orders = r.u8()
        val supply = r.u8()
        if (ver in 83..99) r.skip(9)
        return TaskForce(c, r.p, orders, supply)
    }

    /**
     * The walk of a unit part: how many records its header promises ([records]), how long it is unpacked
     * ([unpacked]), where the walk ended ([end]) and why it stopped early ([stop], null when it did not). [exact] is
     * the check that the layouts are right: the walk ends on the last byte with exactly the promised count.
     */
    class UnitWalk(val records: Int, val unpacked: Int, val end: Int, val units: List<CampUnit>, val stop: String?) {
        val exact: Boolean get() = stop == null && end == unpacked && units.size == records
    }

    /** Walks a `.uni` part ([part] = its bytes as stored, header and all), naming each record's kind with [names]. */
    fun walkUnits(part: ByteArray, ver: Int, names: Names): UnitWalk {
        if (part.size < 10) return UnitWalk(0, 0, 0, emptyList(), "The unit part is too short (${part.size} bytes).")
        val records = MissionArchive.int16(part, 4).toShort().toInt()
        val unpacked = MissionArchive.int32(part, 6)
        if (records == 0 && unpacked <= 0) return UnitWalk(0, 0, 0, emptyList(), null)
        val body = MissionArchive.unpack(part, 10, unpacked)
            ?: return UnitWalk(records, unpacked, 0, emptyList(), "The unit part could not be unpacked ($unpacked bytes promised).")
        val r = Cursor(body)
        val units = ArrayList<CampUnit>(maxOf(0, records))
        var stop: String? = null
        while (units.size < records && r.p < body.size) {
            val start = r.p
            val type = try { r.i16() } catch (e: Throwable) { stop = "ran out at byte $start"; break }
            val ct = names.ct(type)
            if (type < 100 || ct == null) { stop = "unit type $type at byte $start is not in the class table"; break }
            val kind = kindOf(ct)
            if (kind == null) { stop = "unit type $type at byte $start is class d${ct.domain}/t${ct.type}, which is not a unit"; break }
            val u: CampUnit = try {
                val c = core(r, ver, start, type)
                when (kind) {
                    Kind.FLIGHT -> flight(r, ver, c)
                    Kind.PACKAGE -> pkg(r, ver, c)
                    Kind.SQUADRON -> squadron(r, ver, c)
                    Kind.BATTALION -> battalion(r, ver, c)
                    Kind.BRIGADE -> brigade(r, c)
                    Kind.TASKFORCE -> taskForce(r, ver, c)
                }
            } catch (e: Throwable) {
                stop = "the ${kind.name.lowercase()} record at byte $start could not be read: ${reason(e)}"
                break
            }
            if (u.core.entityType != type) {
                stop = "the ${kind.name.lowercase()} record at byte $start carries type ${u.core.entityType}, not $type"
                break
            }
            units += u
        }
        return UnitWalk(records, unpacked, r.p, units, stop)
    }

    /** What a class-table entry makes a unit (`UniFile.Decode`): air flight/package/squadron, land battalion/brigade, sea task force. */
    fun kindOf(ct: Ct): Kind? = when {
        ct.domain == 2 && ct.type == 1 -> Kind.FLIGHT
        ct.domain == 2 && ct.type == 2 -> Kind.PACKAGE
        ct.domain == 2 && ct.type == 3 -> Kind.SQUADRON
        ct.domain == 3 && ct.type == 1 -> Kind.BATTALION
        ct.domain == 3 && ct.type == 2 -> Kind.BRIGADE
        ct.domain == 4 && ct.type == 1 -> Kind.TASKFORCE
        else -> null
    }

    // ================================================================ objectives (.obj, in a start file)

    /**
     * One objective of a start file: an airbase, a factory, a bridge… [x]/[y] are its grid cell (east, north); from
     * version 103 [simNorth]/[simEast] are its exact position in feet (north first — Osan AB is 1,163,530 N,
     * 1,540,725 E, the app's airport 1784) and from version 106 [name] its name ("Osan AB (RKSO)").
     */
    class Objective(
        val at: Int, val type: Int, val id: VuId, val entityType: Int, val x: Int, val y: Int, val z: Float,
        val owner: Int, val campId: Int, val flags: Long, val nameId: Int, val parent: VuId, val firstOwner: Int,
        val links: Int, val simNorth: Double?, val simEast: Double?, val heading: Float?, val name: String,
        /** its buildings' states as the start file has them: two bits each ([featureStatus]); the Map page's radars */
        val status: ByteArray = ByteArray(0),
        /** the record carries radar data (WDP's `hasRadarData`, one of the tests of `ObjHasWorkingRadar`) */
        val hasRadarData: Boolean = false,
    ) {
        val north: Double get() = simNorth ?: ((y + 0.5) * FEET_PER_CELL)
        val east: Double get() = simEast ?: ((x + 0.5) * FEET_PER_CELL)
    }

    /** The walk of an `.obj` part; [exact] as for [UnitWalk]. */
    class ObjWalk(val records: Int, val unpacked: Int, val end: Int, val objectives: List<Objective>, val stop: String?) {
        val exact: Boolean get() = stop == null && end == unpacked && objectives.size == records
        private val byId by lazy { objectives.associateBy { it.id } }
        operator fun get(id: VuId?): Objective? = id?.let { byId[it] }
    }

    /** Walks an `.obj` part: an `i16` count, the unpacked and packed lengths, then the packed records from byte 10. */
    fun walkObjectives(part: ByteArray, ver: Int): ObjWalk {
        if (part.size < 10) return ObjWalk(0, 0, 0, emptyList(), "The objective part is too short (${part.size} bytes).")
        val records = MissionArchive.int16(part, 0).toShort().toInt()
        val unpacked = MissionArchive.int32(part, 2)
        // Hellas's campaign starts carry an objective part that is only its header: no records, nothing packed
        if (records == 0 && unpacked == 0) return ObjWalk(0, 0, 0, emptyList(), null)
        val body = MissionArchive.unpack(part, 10, unpacked)
            ?: return ObjWalk(records, unpacked, 0, emptyList(), "The objective part could not be unpacked ($unpacked bytes promised).")
        val r = Cursor(body)
        val out = ArrayList<Objective>(maxOf(0, records))
        var stop: String? = null
        while (out.size < records && r.p < body.size) {
            val start = r.p
            try {
                val type = r.i16()
                val id = r.id()
                val entityType = r.u16()
                val x = r.i16()
                val y = r.i16()
                val z = r.f32()
                r.skip(4 + 2 + 2) // spotted: when, by whom; base flags
                val owner = r.u8()
                val campId = r.i16()
                r.skip(4) // last repair
                val flags = r.u32()
                r.skip(3) // supply, fuel, losses
                val statusBytes = r.u8()
                val status = r.bytes(statusBytes) // the features' states
                r.u8() // priority
                val nameId = r.i16()
                val parent = r.id()
                val firstOwner = r.u8()
                val links = r.u8()
                r.skip(links * 16)
                val hasRadarData = r.u8() != 0
                if (hasRadarData) r.skip(32) // radar data
                var north: Double? = null
                var east: Double? = null
                var heading: Float? = null
                if (ver >= 103) {
                    north = r.f64()
                    east = r.f64()
                    r.f64()
                    heading = r.f32()
                }
                val name = if (ver >= 106) r.str(80) else ""
                if (entityType != type) { stop = "the objective at byte $start carries type $entityType, not $type"; break }
                out += Objective(start, type, id, entityType, x, y, z, owner, campId, flags, nameId, parent, firstOwner, links, north, east, heading, name, status, hasRadarData)
            } catch (e: Throwable) {
                stop = "the objective at byte $start could not be read: ${reason(e)}"
                break
            }
        }
        return ObjWalk(records, unpacked, r.p, out, stop)
    }

    private val objCache = object : LinkedHashMap<String, ObjWalk?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ObjWalk?>?) = size > 24
    }

    /**
     * The objectives of [file] (a start: `Save1.cam`, `Te_New.tac`…), or null when it has no `.obj` part or cannot be
     * read. Cached by path, size and time: a start file does not change while BMS is installed.
     */
    fun objectives(file: File): ObjWalk? {
        val key = key(file)
        synchronized(objCache) { if (objCache.containsKey(key)) return objCache[key] }
        val walk = try {
            val blob = file.readBytes()
            val dir = directory(blob)
            val part = dir.part("obj")
            if (dir.error != null || part == null) null
            else walkObjectives(blob.copyOfRange(part.at, part.at + part.length), version(blob, dir) ?: 0)
        } catch (_: Throwable) {
            null
        }
        synchronized(objCache) { objCache[key] = walk }
        return walk
    }

    // ================================================================ the objectives' changes (.obd, in a save)

    /**
     * How one objective differs from its start file in a save (WDP's `BMSUtils.ObjectiveDeltas`): the team holding it
     * now, its supply, fuel and losses, and its buildings' states, two bits each ([featureStatus]).
     */
    class ObjDelta(
        val id: VuId, val lastRepair: Long, val owner: Int, val supply: Int, val fuel: Int, val losses: Int, val status: ByteArray,
    )

    /** The walk of an `.obd` part; [exact] as for [UnitWalk]. */
    class ObdWalk(val records: Int, val unpacked: Int, val end: Int, val deltas: List<ObjDelta>, val stop: String?) {
        val exact: Boolean get() = stop == null && end == unpacked && deltas.size == records
        private val byId by lazy { deltas.associateBy { it.id } }
        operator fun get(id: VuId?): ObjDelta? = id?.let { byId[it] }
    }

    /**
     * Walks an `.obd` part (WDP's `ObdFile`): an `i32`, an `i16` count, the unpacked length, then the packed records from
     * byte 10 — per objective its id, last repair (`u32`), owner, supply, fuel and losses (a byte each), a count of
     * status bytes and the bytes (one before version 64). A played save keeps only the objectives that changed: the
     * Korea test save holds 35. Versions 83-99 carry more, which the walk refuses rather than guesses.
     */
    fun walkDeltas(part: ByteArray, ver: Int): ObdWalk {
        if (part.size < 10) return ObdWalk(0, 0, 0, emptyList(), "The objective change part is too short (${part.size} bytes).")
        val records = MissionArchive.int16(part, 4).toShort().toInt()
        val unpacked = MissionArchive.int32(part, 6)
        if (records <= 0 || unpacked <= 0) return ObdWalk(maxOf(0, records), maxOf(0, unpacked), 0, emptyList(), null)
        if (ver in 83..99) return ObdWalk(records, unpacked, 0, emptyList(), "save version $ver keeps objective changes in a layout this reader does not know")
        val body = MissionArchive.unpack(part, 10, unpacked)
            ?: return ObdWalk(records, unpacked, 0, emptyList(), "The objective change part could not be unpacked ($unpacked bytes promised).")
        val r = Cursor(body)
        val out = ArrayList<ObjDelta>(records)
        var stop: String? = null
        while (out.size < records && r.p < body.size) {
            val start = r.p
            try {
                val id = r.id()
                val lastRepair = r.u32()
                val owner = r.u8()
                val supply = r.u8()
                val fuel = r.u8()
                val losses = r.u8()
                val n = r.u8()
                val status = if (ver < 64) r.bytes(minOf(n, 1)) else r.bytes(n)
                out += ObjDelta(id, lastRepair, owner, supply, fuel, losses, status)
            } catch (e: Throwable) {
                stop = "the objective change at byte $start could not be read: ${reason(e)}"
                break
            }
        }
        return ObdWalk(records, unpacked, r.p, out, stop)
    }

    /** The state of building [i] of an objective (0 intact … 3 destroyed; WDP's `GetFeatureStatus`), 0 past the end. */
    fun featureStatus(status: ByteArray, i: Int): Int {
        val b = status.getOrNull(i / 4)?.toInt() ?: return 0
        return ((b and 0xFF) shr ((i % 4) * 2)) and 3
    }

    private val obdCache = object : LinkedHashMap<String, ObdWalk?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ObdWalk?>?) = size > 24
    }

    /**
     * The objective changes of save [file], or null when it has no `.obd` part or cannot be read. Cached by path, size
     * and time.
     */
    fun deltas(file: File): ObdWalk? {
        val key = key(file)
        synchronized(obdCache) { if (obdCache.containsKey(key)) return obdCache[key] }
        val walk = try {
            val blob = file.readBytes()
            val dir = directory(blob)
            val part = dir.part("obd")
            if (dir.error != null || part == null) null
            else walkDeltas(blob.copyOfRange(part.at, part.at + part.length), version(blob, dir) ?: 0)
        } catch (_: Throwable) {
            null
        }
        synchronized(obdCache) { obdCache[key] = walk }
        return walk
    }

    // ================================================================ the pilot list (.plt)

    /**
     * The pilot list: [pilots] entries of four bytes, then [callsigns] one byte each. WDP reads one byte more before the
     * callsigns (fix D48); the part's own length says it is not there ([exact] compares the end with the length).
     */
    class Plt(val pilots: Int, val callsigns: Int, val end: Int, val size: Int) {
        val exact: Boolean get() = end == size
    }

    fun plt(part: ByteArray): Plt? = try {
        val r = Cursor(part)
        val pilots = r.i16()
        r.skip(pilots * 4)
        val callsigns = r.i16()
        r.skip(callsigns)
        Plt(pilots, callsigns, r.p, part.size)
    } catch (_: Throwable) {
        null
    }

    // ================================================================ the teams (.tea) and the primary objectives (.pol)

    /**
     * A team's ground posture (`TeamGndActionType`): from [time] (campaign ms) until [timeout], toward [objective], and
     * what kind — [type] 1 defensive, 2 consolidate, 3 minor offensive, 4 offensive. The situation BMS prints reads it
     * (`Situate.b`): 4 is "our ground forces will be making a major push towards …", 1 "a large enemy offensive is in
     * progress".
     */
    class GroundAction(val time: Long, val timeout: Long, val objective: VuId, val type: Int, val tempo: Int, val points: Int)

    /** A team's air operation (`TeamAirActionType`): [type] 1 DCA, 2 OCA, 3 interdiction, 4 attrition, 5 CAS (Strings 854 + type). */
    class AirAction(val start: Long, val stop: Long, val objective: VuId, val last: VuId, val type: Int)

    /**
     * One team of the team part: its number, the team that controls it, its stance toward each team (0 none, 1 allied,
     * 2 friendly, 3 neutral, 4 hostile, 5 war), its name and motto, and what it is doing on the ground and in the air.
     */
    class TeamRecord(
        val n: Int, val controller: Int, val stance: List<Int>, val name: String, val motto: String,
        val ground: GroundAction, val defensive: AirAction, val offensive: AirAction,
    )

    /** How long a team record is before its tasking managers, from save version 102 (WDP's `BMSUtils` `Team`). */
    private const val TEAM_FIXED = 748

    /**
     * The team part (`.tea`): an `i16` count, then per team a fixed record — from version 102: stances at +22, name at
     * +453, motto at +473, ground action at +673, defensive and offensive air actions at +692 and +720 (WDP's `Team`)
     * — followed by its tasking managers, whose length varies (LHTO's differ from Korea's). Records are found the way
     * [TeamRelations] finds them: each carries the team entity type the first one has, the numbers come in order, the
     * controlling team is a team and every stance is one of the six. The table is believed whole or not at all: null
     * when the count is not met, and for a version before 102, whose offsets differ.
     */
    fun teams(part: ByteArray, ver: Int): List<TeamRecord>? = try {
        val count = if (part.size >= 2) ((part[0].toInt() and 0xFF) or ((part[1].toInt() and 0xFF) shl 8)).toShort().toInt() else 0
        if (ver < 102 || count !in 1..8 || part.size < 2 + TEAM_FIXED) null else {
            fun u16(p: Int) = (part[p].toInt() and 0xFF) or ((part[p + 1].toInt() and 0xFF) shl 8)
            val type = u16(2 + 8)
            fun looksLikeTeam(p: Int, expected: Int): Boolean =
                u16(p + 8) == type && (part[p + 10].toInt() and 0xFF) == expected && (part[p + 11].toInt() and 0xFF) < 8 &&
                    (0 until 8).all { t -> u16(p + 22 + 2 * t) in 0..5 }
            val starts = ArrayList<Int>()
            var p = 2
            while (p + TEAM_FIXED <= part.size && starts.size < count) {
                if (looksLikeTeam(p, starts.size)) { starts += p; p += TEAM_FIXED } else p++
            }
            if (starts.size != count) null else starts.map { at ->
                val r = Cursor(part, at + 10)
                val n = r.u8()
                val controller = r.u8()
                r.p = at + 22
                val stance = List(8) { r.i16() }
                r.p = at + 453
                val name = r.str(20)
                val motto = r.str(200)
                val ground = GroundAction(r.u32(), r.u32(), r.id(), r.u8(), r.u8(), r.u8())
                r.p = at + 692
                val defensive = AirAction(r.u32(), r.u32(), r.id(), r.id(), r.u8())
                r.p = at + 720
                val offensive = AirAction(r.u32(), r.u32(), r.id(), r.id(), r.u8())
                TeamRecord(n, controller, stance, name, motto, ground, defensive, offensive)
            }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * The primary objectives (`.pol`, WDP's `PolFile`): a team mask, an `i16` count, then per objective its id and, for
     * each team in the mask, a priority (`i16`) and flags (`u8`). The briefing's general locations are said against
     * these ("17 nm northeast of Sejong City"). Null when the part cannot be read to its count.
     */
    fun primaryObjectives(part: ByteArray): List<VuId>? = try {
        val r = Cursor(part)
        val mask = r.u8()
        val n = r.i16()
        if (n < 0) null else List(n) {
            val id = r.id()
            for (t in 0 until 8) if (mask and (1 shl t) != 0) r.skip(3)
            id
        }
    } catch (_: Throwable) {
        null
    }

    // ================================================================ names

    /** One entry of the class table (`Falcon4_CT.xml`, numbered from 0; a unit's type is its number + 100). */
    class Ct(val num: Int, val domain: Int, val cls: Int, val type: Int, val subType: Int, val specific: Int, val entityType: Int, val entityIdx: Int)

    /**
     * A theater's names: its class table, unit, vehicle and weapon tables, and its campaign strings. [tables] says
     * which file each table was read from (the theater's `objectdir`, or `Data/TerrData/Objects`), [error] why the
     * class table is missing — without it no unit can be walked.
     */
    class Names(
        val theater: Theaters.Theater?,
        private val ctByNum: Map<Int, Ct>,
        private val ucdName: Map<Int, String>,
        private val ucdVehicle: Map<Int, Int>,
        private val vcdName: Map<Int, String>,
        private val wcdName: Map<Int, String>,
        val strings: Map<Int, String>,
        val tables: Map<String, File?>,
        val stringsFile: File?,
        val error: String?,
        internal val key: String,
    ) {
        val ctCount: Int get() = ctByNum.size

        /** The class-table entry of unit or objective type [type]. */
        fun ct(type: Int): Ct? = ctByNum[type - 100]

        fun kind(type: Int): Kind? = ct(type)?.let(::kindOf)

        /** The unit's class name ("F-16CM-40", "CSG"…) from the unit table. */
        fun unitClass(type: Int): String? = ct(type)?.let { ucdName[it.entityIdx] }?.takeIf { it.isNotBlank() }

        /** The aircraft of a flight or squadron type: unit → its first vehicle → the vehicle table's name. */
        fun aircraft(type: Int): String? {
            val c = ct(type) ?: return null
            val vehicleCt = ucdVehicle[c.entityIdx] ?: return null
            val v = ctByNum[vehicleCt] ?: return null
            return vcdName[v.entityIdx]?.takeIf { it.isNotBlank() }
        }

        /**
         * The class-table entry of a unit type's first vehicle (unit → the unit table's `VehicleCtIdx_0`): its
         * [Ct.subType] tells a SAM (2) from anti-aircraft guns (1), which is how the briefing words "missile launchers"
         * and "anti-aircraft guns" (Strings 235, 236).
         */
        fun vehicleCt(type: Int): Ct? {
            val c = ct(type) ?: return null
            return ucdVehicle[c.entityIdx]?.let { ctByNum[it] }
        }

        /** A weapon by its id (the loadout's numbers index the weapon table directly). */
        fun weapon(id: Int): String? = wcdName[id]?.takeIf { it.isNotBlank() }

        fun mission(n: Int): String? = strings[MISSION_STRINGS + n]
        fun task(n: Int): String? = strings[TASK_STRINGS + n]
        fun action(n: Int): String? = strings[ACTION_STRINGS + n]

        /** "Falcon1": the callsign word and the flight's number (the word alone for a number outside 1-99). */
        fun callsign(f: Flight): String? =
            strings[CALLSIGN_STRINGS + f.callsignId]?.let { if (f.callsignNum in 1..99) "$it${f.callsignNum}" else it }
    }

    private val namesCache = ConcurrentHashMap<String, Names>()
    private val tableCache = ConcurrentHashMap<String, Map<Int, Map<String, String>>>()

    const val CT_FILE = "Falcon4_CT.xml"
    const val UCD_FILE = "Falcon4_UCD.xml"
    const val VCD_FILE = "Falcon4_VCD.xml"
    const val WCD_FILE = "Falcon4_WCD.xml"

    /**
     * The names of theater [t] of [set]: each class table from its `objectdir`, else `Data/TerrData/Objects`, file by
     * file ([Theaters.TheaterSet.classFile]); the strings from its `campaigndir`, else `Data/Campaign`. Cached, and
     * read again when a file changes.
     */
    fun names(set: Theaters.TheaterSet, t: Theaters.Theater?): Names {
        val files = listOf(CT_FILE, UCD_FILE, VCD_FILE, WCD_FILE).associateWith { set.classFile(t, it) }
        val stringsFile = set.stringsFile(t)
        val key = (files.values + stringsFile).joinToString("|") { it?.let(::key) ?: "-" }
        namesCache[key]?.let { return it }
        val ct = table(files[CT_FILE], "CT", setOf("Domain", "Class", "Type", "SubType", "Specific", "EntityType", "EntityIdx"))
        val ucd = table(files[UCD_FILE], "UCD", setOf("Name", "VehicleCtIdx_0"))
        val vcd = table(files[VCD_FILE], "VCD", setOf("Name"))
        val wcd = table(files[WCD_FILE], "WCD", setOf("Name"))
        fun Map<String, String>.int(k: String) = this[k]?.trim()?.toIntOrNull() ?: -1
        val ctByNum = HashMap<Int, Ct>(ct.size * 2)
        ct.forEach { (num, f) -> ctByNum[num] = Ct(num, f.int("Domain"), f.int("Class"), f.int("Type"), f.int("SubType"), f.int("Specific"), f.int("EntityType"), f.int("EntityIdx")) }
        val ucdName = HashMap<Int, String>()
        val ucdVehicle = HashMap<Int, Int>()
        ucd.forEach { (num, f) ->
            f["Name"]?.trim()?.let { ucdName[num] = it }
            f.int("VehicleCtIdx_0").takeIf { it >= 0 }?.let { ucdVehicle[num] = it }
        }
        val vcdName = HashMap<Int, String>().also { m -> vcd.forEach { (num, f) -> f["Name"]?.trim()?.let { m[num] = it } } }
        val wcdName = HashMap<Int, String>().also { m -> wcd.forEach { (num, f) -> f["Name"]?.trim()?.let { m[num] = it } } }
        val error = when {
            files[CT_FILE] == null -> "No class table ($CT_FILE) was found for ${t?.name ?: "this theater"}."
            ctByNum.isEmpty() -> "The class table ${files[CT_FILE]?.path} could not be read."
            else -> null
        }
        val names = Names(t, ctByNum, ucdName, ucdVehicle, vcdName, wcdName, set.strings(t), files, stringsFile, error, key)
        namesCache[key] = names
        return names
    }

    /**
     * The records of an XML table (`<CT Num="n"> <Domain>2</Domain> … </CT>`): for each number, the fields asked for.
     * Scanned rather than parsed: the class table is 8 MB and only a handful of its fields are wanted.
     */
    internal fun table(file: File?, tag: String, fields: Set<String>): Map<Int, Map<String, String>> {
        if (file == null) return emptyMap()
        val key = key(file) + "|" + tag + "|" + fields.sorted().joinToString(",")
        tableCache[key]?.let { return it }
        val out = HashMap<Int, Map<String, String>>()
        try {
            val text = file.readText(Charsets.UTF_8)
            val open = "<$tag Num=\""
            val close = "</$tag>"
            var i = text.indexOf(open)
            while (i >= 0) {
                val numEnd = text.indexOf('"', i + open.length)
                if (numEnd < 0) break
                val num = text.substring(i + open.length, numEnd).trim().toIntOrNull()
                var recEnd = text.indexOf(close, numEnd)
                if (recEnd < 0) recEnd = text.length
                if (num != null) {
                    val rec = HashMap<String, String>()
                    var j = text.indexOf('<', numEnd)
                    while (j in 0 until recEnd) {
                        val gt = text.indexOf('>', j)
                        if (gt < 0 || gt > recEnd) break
                        if (j + 1 < gt && text[j + 1] != '/') {
                            val name = text.substring(j + 1, gt)
                            if (name in fields) {
                                val endTag = text.indexOf("</$name>", gt)
                                if (endTag in 0..recEnd) rec[name] = unescape(text.substring(gt + 1, endTag))
                            }
                        }
                        j = text.indexOf('<', gt)
                    }
                    out[num] = rec
                }
                i = text.indexOf(open, recEnd)
            }
        } catch (_: Throwable) {
            // an unreadable table is an empty one; [names] says so
        }
        tableCache[key] = out
        return out
    }

    private fun unescape(s: String): String =
        if ('&' !in s) s else s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    // ================================================================ the whole save

    /**
     * One save, read whole: its directory, format version ([version], the `.ver` part: 109, 110…), campaign header,
     * units and pilot list. [errors] lists what could not be read, in sentences; the rest is still filled as far as it
     * goes. [readMs] is how long reading and parsing took.
     */
    class Save(
        val file: File, val size: Long, val modified: Long,
        val directory: Directory?, val version: Int?, val header: Header?, val uni: UnitWalk?, val plt: Plt?,
        val names: Names?, val errors: List<String>, val readMs: Double,
        /** the team part ([teams]); null when it is missing or not believed */
        val teams: List<TeamRecord>? = null,
        /** the primary objectives ([primaryObjectives]); null when the save has none */
        val primaryObjectives: List<VuId>? = null,
    ) {
        val error: String? get() = errors.firstOrNull()
        val hasObj: Boolean get() = directory?.has("obj") == true
        val units: List<CampUnit> get() = uni?.units.orEmpty()
        val exact: Boolean get() = uni?.exact == true
        private val byId: Map<VuId, CampUnit> by lazy { units.associateBy { it.id } }
        val flights: List<Flight> by lazy { units.filterIsInstance<Flight>() }
        val packages: List<Package> by lazy { units.filterIsInstance<Package>() }
        val squadrons: List<Squadron> by lazy { units.filterIsInstance<Squadron>() }

        /** A start or template: it has objectives, or no flights. Nothing in it can be planned until BMS has run it. */
        val start: Boolean get() = hasObj || flights.isEmpty()

        /** The kind of save by its extension: "campaign", "te" or "training". */
        val kind: String get() = kindOfName(file.name)

        fun unit(id: VuId?): CampUnit? = id?.takeUnless { it.isNone }?.let { byId[it] }
        fun flight(id: VuId?): Flight? = unit(id) as? Flight
        fun packageOf(f: Flight): Package? = unit(f.packageId) as? Package
        fun squadronOf(f: Flight): Squadron? = unit(f.squadronId) as? Squadron

        /**
         * The unit a flight belongs to, whatever it is: nearly always a [Squadron], but not always — in Hellas WCP's
         * `SAT 001.tac` an E-2 flight's "squadron" is a battalion (a unit standing in for its carrier).
         */
        fun homeOf(f: Flight): CampUnit? = unit(f.squadronId)
        fun flightsOf(p: Package): List<Flight> = p.elements.mapNotNull { flight(it) }

        /** The flights a pilot sits in (a player slot is taken). */
        val playerFlights: List<Flight> get() = flights.filter { it.hasPlayer }
    }

    /** "campaign", "te" or "training", by the file's extension. */
    fun kindOfName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "tac" -> "te"
        "trn" -> "training"
        else -> "campaign"
    }

    /**
     * Reads and parses [file] (read-only, whole-file, no lock kept), naming its units with [names]. Never throws: what
     * could not be read is in [Save.errors].
     */
    fun read(file: File, names: Names?): Save {
        val t0 = System.nanoTime()
        val size = try { file.length() } catch (_: Throwable) { 0L }
        val modified = try { file.lastModified() } catch (_: Throwable) { 0L }
        val blob = try {
            file.readBytes()
        } catch (e: Throwable) {
            return Save(file, size, modified, null, null, null, null, null, names, listOf("Could not read ${file.name}: ${reason(e)}."), ms(t0))
        }
        return parse(file, blob, size, modified, names, t0)
    }

    /** [read], cached by path, size, time and the names it was read with. */
    fun cached(file: File, names: Names?): Save {
        val key = key(file) + "|" + (names?.key ?: "-")
        synchronized(saveCache) { saveCache[key]?.let { return it } }
        val save = read(file, names)
        synchronized(saveCache) { saveCache[key] = save }
        return save
    }

    private val saveCache = object : LinkedHashMap<String, Save>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Save>?) = size > 48
    }

    private fun parse(file: File, blob: ByteArray, size: Long, modified: Long, names: Names?, t0: Long): Save {
        val errors = ArrayList<String>()
        val dir = directory(blob)
        if (dir.error != null) {
            return Save(file, size, modified, dir, null, null, null, null, names, listOf("${file.name} is not a save BMS can read: ${dir.error}"), ms(t0))
        }
        val version = version(blob, dir)
        if (version == null) errors += "${file.name} has no readable version part."
        val ver = version ?: 0
        fun bytes(p: Part) = blob.copyOfRange(p.at, p.at + p.length)
        val header = dir.part("cmp")?.let { p ->
            try {
                val raw = bytes(p)
                val body = if (raw.size >= 8) MissionArchive.unpack(raw, 8, MissionArchive.int32(raw, 4)) else null
                if (body == null) { errors += "The campaign part of ${file.name} could not be unpacked."; null } else header(body, ver)
            } catch (e: Throwable) {
                errors += "The campaign header of ${file.name} could not be read: ${reason(e)}."
                null
            }
        } ?: run {
            if (!dir.has("cmp")) errors += "${file.name} has no campaign part."
            null
        }
        val uni = dir.part("uni")?.let { p ->
            when {
                names == null -> { errors += "No names were given to read the units of ${file.name}."; null }
                names.error != null -> { errors += names.error; null }
                else -> try {
                    walkUnits(bytes(p), ver, names).also { w -> w.stop?.let { errors += "The units of ${file.name} could not all be read: $it." } }
                } catch (e: Throwable) {
                    errors += "The units of ${file.name} could not be read: ${reason(e)}."
                    null
                }
            }
        } ?: run {
            if (!dir.has("uni")) errors += "${file.name} has no unit part."
            null
        }
        val plt = dir.part("plt")?.let { plt(bytes(it)) }
        val teams = dir.part("tea")?.let { teams(bytes(it), ver) }
        val pol = dir.part("pol")?.let { primaryObjectives(bytes(it)) }
        return Save(file, size, modified, dir, version, header, uni, plt, names, errors, ms(t0), teams, pol)
    }

    /** The `.ver` part: three ASCII digits ("109"). */
    fun version(blob: ByteArray, dir: Directory): Int? {
        val p = dir.part("ver") ?: return null
        return String(blob, p.at, p.length, Charsets.ISO_8859_1).trim { it <= ' ' || it == '\u0000' }.toIntOrNull()
    }

    // ================================================================ tying things together

    /**
     * The start file a save was made from: [Header.scenario] ("save1") in the save's own folder, with the save's
     * extension first (a training mission is made from a TE start), matched without case. Null when it is not there.
     */
    fun startFile(save: Save): File? {
        val scenario = save.header?.scenario?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val dir = save.file.absoluteFile.parentFile ?: return null
        val listing = try { dir.listFiles()?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
        val own = save.file.name.substringAfterLast('.', "").lowercase()
        val order = (listOf(own) + listOf("cam", "tac", "trn")).distinct().let { if (own == "trn") listOf("tac", "trn", "cam") else it }
        for (ext in order) listing.firstOrNull { it.isFile && it.name.equals("$scenario.$ext", ignoreCase = true) }?.let { return it }
        return null
    }

    /**
     * Something a waypoint, a squadron or a package points at: a unit of the save ([kind] "unit") or an objective of
     * its start file ("objective"). [north]/[east] in feet; [type] the class-table type.
     */
    class Place(val kind: String, val id: VuId, val type: Int, val campId: Int?, val name: String?, val north: Double, val east: Double, val building: Int? = null)

    /**
     * What [id] is in [save]: one of its units first (a carrier task force, a flight), then an objective of the start
     * file ([objectives], from [startFile]). Null for none, or an id that neither holds.
     */
    fun place(save: Save, objectives: ObjWalk?, id: VuId?, building: Int? = null): Place? {
        if (id == null || id.isNone) return null
        save.unit(id)?.let { u ->
            val name = when (u) {
                is Flight -> save.names?.callsign(u)
                is Squadron -> save.header?.squadron(u.id)?.name?.takeIf { it.isNotBlank() }
                else -> null
            } ?: save.names?.unitClass(u.type)
            return Place("unit", u.id, u.type, u.campId, name, u.north, u.east, building?.takeIf { it != 255 })
        }
        objectives?.get(id)?.let { o ->
            return Place("objective", o.id, o.type, o.campId, o.name.takeIf { it.isNotBlank() }, o.north, o.east, building?.takeIf { it != 255 })
        }
        return null
    }

    /**
     * A unit as the briefing names it: a flight by its callsign, a squadron by the header's name ("36th FS (F-16C)"),
     * and any other unit as its name number with its ordinal, its class from the unit table and what it is (Strings
     * 614-616) — "500th Air Defense Battalion", as BMS printed Hellas WCP's DEAD target. Null for a package.
     */
    fun unitName(save: Save, u: CampUnit): String? {
        val names = save.names ?: return null
        return when (u) {
            is Flight -> names.callsign(u)
            is Squadron -> save.header?.squadron(u.id)?.name?.takeIf { it.isNotBlank() } ?: names.unitClass(u.type)
            is Package -> null
            else -> {
                val cls = names.unitClass(u.type) ?: return null
                val word = names.strings[if (u is Brigade) 614 else if (u is TaskForce) 616 else 615]
                val n = u.core.nameId
                listOfNotNull(if (n > 0) "$n" + ordinal(names, n) else null, cls, word).joinToString(" ")
            }
        }
    }

    /** "st", "nd", "rd" or "th" for [n] (Strings 15-18): 11, 12 and 13 take "th". */
    fun ordinal(names: Names?, n: Int): String {
        val k = when {
            n % 100 in 11..13 -> 3
            n % 10 == 1 -> 0
            n % 10 == 2 -> 1
            n % 10 == 3 -> 2
            else -> 3
        }
        return names?.strings?.get(15 + k) ?: listOf("st", "nd", "rd", "th")[k]
    }

    // ================================================================ reading bytes

    /** A little-endian reader over [b]. Reading past the end throws [ShortRead], which the walks turn into a reason. */
    private class Cursor(val b: ByteArray, var p: Int = 0) {
        val left: Int get() = b.size - p
        private fun need(n: Int) { if (n < 0 || p + n > b.size) throw ShortRead("needed $n bytes at $p of ${b.size}") }
        fun skip(n: Int) { need(n); p += n }
        fun bytes(n: Int): ByteArray { need(n); val out = b.copyOfRange(p, p + n); p += n; return out }
        fun u8(): Int { need(1); return b[p++].toInt() and 0xFF }
        fun u16(): Int { need(2); val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8); p += 2; return v }
        fun i16(): Int = u16().toShort().toInt()
        fun i32(): Int { need(4); val v = MissionArchive.int32(b, p); p += 4; return v }
        fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
        fun f32(): Float = Float.fromBits(i32())
        fun f64(): Double { val lo = i32().toLong() and 0xFFFFFFFFL; val hi = i32().toLong(); return Double.fromBits((hi shl 32) or lo) }
        fun id(): VuId { val num = u32(); val creator = u32(); return VuId(num, creator) }
        fun str(n: Int): String {
            need(n)
            var len = 0
            while (len < n && b[p + len].toInt() != 0) len++
            val s = String(b, p, len, TEXT)
            p += n
            return s.trim()
        }
    }

    private class ShortRead(message: String) : RuntimeException(message)

    // ================================================================ small things

    /** A cache key that moves whenever the file does: path, size and time. */
    fun key(f: File): String = try { "${f.absolutePath}|${f.length()}|${f.lastModified()}" } catch (_: Throwable) { f.path }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    private fun reason(e: Throwable): String = (e.message ?: e.javaClass.simpleName).trim().trimEnd('.')

    /** Campaign milliseconds as "D1 04:21:00". */
    fun clock(ms: Long): String {
        val s = ms / 1000
        val d = s / 86400
        return "D${d + 1} %02d:%02d:%02d".format((s % 86400) / 3600, (s % 3600) / 60, s % 60)
    }
}
