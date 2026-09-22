package com.bmscompanion.desktop.bridge

import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/** One point of a planned route, in theater feet, with the clock times the campaign gave it. */
data class RoutePoint(
    val x: Double,
    val y: Double,
    val altFt: Double,
    val arriveMs: Long,
    val departMs: Long,
    val action: Int,
)

/** A route the campaign has planned for one flight: what the flight is for, and where it goes. */
data class PlannedRoute(
    val mission: Int,
    val missionName: String?,
    /** "Texaco1": the word the campaign gave this flight and the number after it */
    val callsign: String?,
    val points: List<RoutePoint>,
)

/**
 * The routes Falcon BMS planned for the other flights of the ATO — the tanker's track, the AWACS's orbit.
 *
 * None of this reaches a live interface: shared memory carries your own aircraft, and the Tacview stream carries
 * where everyone is *now*. What a tanker is *going* to do exists only in the file BMS is flying — the campaign save
 * (`.cam`) or the tactical engagement (`.tac`) — which is an archive of packed records, rewritten as the campaign
 * runs. [MissionArchive] opens it; this reads the flights out of the units part.
 *
 * **Nothing here writes.** The file is opened for reading and read whole; the BMS folder is never touched. A file
 * BMS is in the middle of saving is left until the next time round rather than read half-written, and a read that
 * does not make sense is thrown away rather than shown.
 *
 * **Finding the flights without decoding everything.** The units part holds every unit in the theater, one after
 * another and each a different length: a battalion, a brigade, a package, a squadron, a flight. Rather than decode
 * all of those shapes to walk the list, flights are found by their own signature — a unit's type number is written
 * twice, ten bytes apart, and the type number says (through the theater's class table) that it is a flight. Every
 * position is checked for that, and a candidate is kept only when the route that follows reads sensibly.
 *
 * **Making sure it is *this* mission.** A pilot can leave a campaign, start another in a different theater, or fly
 * a training mission, and the newest file on disk is then not the one being flown. So the save is looked for under
 * the theater BMS says it is in, and what is read is checked against the steerpoints of your own aircraft before
 * any of it is believed: a file that does not contain your flight plan is not this mission, and nothing is shown.
 */
object PlannedRoutes {
    /** Campaign coordinates are kilometres of the theater grid; the app works in BMS theater feet. */
    private const val FEET_PER_GRID = 1000.0 * 3.27998

    /** Waypoint altitude is kept in tens of feet. */
    private const val FEET_PER_ALT = 10.0

    /** A flight has a handful of points; anything wilder is a misread. */
    private const val MOST_POINTS = 60

    /** Mission names live in the campaign's own string table, offset by this. */
    private const val MISSION_STRING_BASE = 300

    /** And the callsign words follow later in the same table, the list beginning at "Falcon". */
    private const val CALLSIGN_STRING_BASE = 2000

    /** A file BMS wrote this recently may still be being written. */
    private const val SETTLE_MS = 1500L

    /** How close a planned point has to be to one of your steerpoints to count as the same place. */
    private const val SAME_PLACE_FT = 1.5 * 6076.12

    /** How many of your steerpoints have to line up before the file is accepted as this mission. */
    private const val ENOUGH_MATCHES = 3

    /** The save being flown, and the theater folder it belongs to. */
    data class Source(val file: File, val theaterDir: File)

    private var cacheKey: String? = null
    private var cached: List<PlannedRoute> = emptyList()
    private val flightTypesByDir = HashMap<String, Set<Int>>()
    private val namesByDir = HashMap<String, Map<Int, String>>()

    /**
     * The mission file that belongs to [theater] — the newest campaign save or engagement under that theater's own
     * folder. With no theater named, every theater is considered and the check against your steerpoints decides.
     */
    fun sourceFor(install: BmsInstall, theater: String?): Source? {
        val base = install.baseDir?.let(::File) ?: return null
        val theaters = theaterDirs(base)
        val wanted = theaters.filter { matchesTheater(it, theater) }.ifEmpty { theaters }
        var best: Source? = null
        for (dir in wanted) {
            val places = listOf(File(dir, "Campaign"), File(base, "User\\Missions"))
            for (place in places) {
                if (!place.isDirectory) continue
                val newest = place.listFiles { f ->
                    f.isFile && (f.name.endsWith(".cam", true) || f.name.endsWith(".tac", true))
                }?.maxByOrNull { it.lastModified() } ?: continue
                if (best == null || newest.lastModified() > best!!.file.lastModified()) best = Source(newest, dir)
            }
        }
        return best
    }

    /**
     * The planned routes of the mission being flown, or nothing when the file on disk is not that mission.
     *
     * [ownRoute] is your own flight plan in theater feet (the steerpoints the DTC gives us). It is what makes this
     * safe across a change of campaign or theater: the file has to contain your flight for its other flights to be
     * believed. Pass nothing before a flight plan is known and nothing is returned.
     */
    @Synchronized
    fun current(install: BmsInstall, theater: String?, ownRoute: List<Pair<Double, Double>>): List<PlannedRoute> {
        if (ownRoute.size < ENOUGH_MATCHES) return emptyList()
        val source = sourceFor(install, theater) ?: return emptyList()
        val file = source.file
        // a file BMS is still writing is left alone until it has settled
        if (System.currentTimeMillis() - file.lastModified() < SETTLE_MS) return cached
        val key = "${file.path}|${file.lastModified()}|${ownRoute.size}"
        if (cacheKey == key) return cached
        val read = runCatching { read(source, install) }
            .onFailure { BridgeLog.warn("Planned routes could not be read from ${file.name}: ${it.message}") }
            .getOrDefault(emptyList())
        cached = if (read.any { flies(it, ownRoute) }) read else emptyList()
        if (read.isNotEmpty() && cached.isEmpty()) {
            BridgeLog.info("${file.name} holds ${read.size} flights but not yours — not the mission being flown")
        }
        cacheKey = key
        return cached
    }

    /** Whether [route] is your own flight: enough of its points sit on your steerpoints. */
    private fun flies(route: PlannedRoute, own: List<Pair<Double, Double>>): Boolean {
        var hits = 0
        for ((x, y) in own) {
            if (route.points.any { hypot(it.x - x, it.y - y) < SAME_PLACE_FT }) hits++
            if (hits >= ENOUGH_MATCHES) return true
        }
        return false
    }

    /** Reads one mission file. Public so the developer check can point at a file of its own. */
    fun read(source: Source, install: BmsInstall): List<PlannedRoute> {
        val blob = source.file.readBytes() // read-only, whole file, no lock kept
        val flights = flightTypes(source.theaterDir, install) ?: return emptyList()
        val names = missionNames(source.theaterDir, install)
        val routes = ArrayList<PlannedRoute>()
        for (part in MissionArchive.parts(blob)) {
            if (!part.name.endsWith(".uni", true)) continue
            val body = MissionArchive.contents(blob, part)?.bytes ?: continue
            routes += flightsIn(body, flights, names)
        }
        return routes
    }

    // ---------------------------------------------------------------- where a theater keeps its things

    /** The base folder (Korea) and every add-on theater beside it. */
    private fun theaterDirs(base: File): List<File> {
        val data = File(base, "Data")
        val dirs = ArrayList<File>()
        if (data.isDirectory) dirs += data
        data.listFiles { f -> f.isDirectory && f.name.startsWith("Add-On", true) }?.let { dirs += it }
        return dirs
    }

    /** Whether [dir] is the theater BMS says it is running — compared on words, because the names are written freely. */
    private fun matchesTheater(dir: File, theater: String?): Boolean {
        val want = theater?.lowercase()?.split(' ', '-', '_', '.')?.filter { it.length > 3 }.orEmpty()
        if (want.isEmpty()) return false
        val have = dir.name.lowercase()
        return want.any { have.contains(it) }
    }

    // ---------------------------------------------------------------- the units part

    private fun flightsIn(body: ByteArray, flights: Set<Int>, names: Map<Int, String>): List<PlannedRoute> {
        val found = ArrayList<PlannedRoute>()
        var at = 0
        while (at + 12 < body.size) {
            val type = MissionArchive.int16(body, at)
            // the signature: a type this class table calls a flight, written again after the unit's own id
            if (type in flights && MissionArchive.int16(body, at + 10) == type) {
                val route = routeAt(body, at, names)
                if (route != null) {
                    found += route
                    at += 12
                    continue
                }
            }
            at++
        }
        return found
    }

    /**
     * Reads the route of the flight whose record starts at [at], or null when what follows does not read as one.
     *
     * The fields before the route are fixed in size, so the route is found by counting past them.
     */
    private fun routeAt(body: ByteArray, at: Int, names: Map<Int, String>): PlannedRoute? {
        var p = at + 2 + 8 + 2 // the type, the unit's id, and the copy of the type
        p += 2 + 2 + 4 // where it is, and its height
        p += 4 + 2 + 2 + 1 + 2 + 4 // when it was last seen, its flags, whose it is, its campaign id, its last check
        p += 4 + 4 // what it is made of, and its own flags
        p += 2 + 2 // where it is going
        p += 8 + 8 // what it is aiming at, and what it is carrying
        p += 1 + 1 + 1 // whether it has moved, its losses, its tactic
        p += 2 + 2 + 2 // the point it is on, its name, its reinforcement
        if (p + 2 > body.size) return note("past the end before the route")
        val count = MissionArchive.int16(body, p)
        p += 2
        if (count <= 0 || count > MOST_POINTS) return note("route count $count")
        val points = ArrayList<RoutePoint>(count)
        repeat(count) {
            if (p + 16 > body.size) return null
            val haves = body[p].toInt() and 0xFF
            p++
            val gx = short(body, p); p += 2
            val gy = short(body, p); p += 2
            val gz = short(body, p); p += 2
            val arrive = (MissionArchive.int32(body, p).toLong() and 0xFFFFFFFFL); p += 4
            val action = body[p].toInt() and 0xFF; p += 1
            p += 1 + 1 // what it does on the way there, and the formation it flies
            p += 4 // its flags
            if (haves and 0x02 != 0) p += 8 + 1 + (8 + 1) * 4 // what it is aiming at, when it has a target
            var depart = arrive
            if (haves and 0x01 != 0) {
                if (p + 4 > body.size) return null
                depart = MissionArchive.int32(body, p).toLong() and 0xFFFFFFFFL
                p += 4
            }
            // a point outside the theater means this was never a route
            if (gx < 0 || gy < 0 || gx > 2048 || gy > 2048) return note("point outside the theater")
            // The campaign grid counts east first; the app counts north first, the way BMS reports a position. And a
            // waypoint is a *cell*, not a point in it: the aircraft flies to the middle of the cell, so half a cell is
            // added. Measured rather than assumed — the take-off point of 381 planned routes sat a mean 0.42 nm from
            // the airfield it leaves, biased +0.22 nm north and +0.27 nm east, against a half cell of 0.27 nm.
            points += RoutePoint(
                x = (gy + 0.5) * FEET_PER_GRID,
                y = (gx + 0.5) * FEET_PER_GRID,
                altFt = gz * FEET_PER_ALT,
                arriveMs = arrive,
                departMs = depart,
                action = action,
            )
        }
        // what the flight is for: past the fuel, the laser code and the loadouts, which are sized by a count
        p += 4 + 4 + 4 * 4 + 2 * 4 + 4 + 4
        if (p + 11 > body.size) return note("past the end after the route")
        p += 4 + 4 + 2 // when it is due on target, when it gives up, what it is aimed at
        val loadouts = body[p].toInt() and 0xFF
        p += 1 + loadouts * 48
        if (p + 2 > body.size) return note("past the end before the mission")
        val mission = body[p].toInt() and 0xFF
        if (mission == 0 || mission > 60) return note("mission $mission")
        // the callsign sits past the ids of the package, the squadron and who asked for the flight, and past the
        // four slots of pilots and aircraft: a word from the table, and the number the flight is known by
        val callsign = if (p + 50 > body.size) null else {
            val word = names[CALLSIGN_STRING_BASE + (body[p + 48].toInt() and 0xFF)]
            val number = body[p + 49].toInt() and 0xFF
            word?.let { if (number in 1..99) "$it$number" else it }
        }
        return PlannedRoute(mission, names[MISSION_STRING_BASE + mission], callsign, points)
    }

    /** Why candidates were turned down. Only filled while the developer check is running. */
    internal val why = HashMap<String, Int>()

    /** Set by the developer check; nothing is tallied during a flight. */
    internal var explain = false

    private fun note(reason: String): Nothing? {
        if (explain) why[reason] = (why[reason] ?: 0) + 1
        return null
    }

    private fun short(b: ByteArray, at: Int): Int = MissionArchive.int16(b, at).toShort().toInt()

    // ---------------------------------------------------------------- what BMS calls things

    /**
     * The unit type numbers that are flights, for one theater.
     *
     * Every unit record starts with a number that points into the theater's class table; the table says what the
     * thing is. A theater brings its own table, and it never changes while BMS is installed, so it is read once.
     */
    private fun flightTypes(theaterDir: File, install: BmsInstall): Set<Int>? {
        flightTypesByDir[theaterDir.path]?.let { return it }
        val table = listOfNotNull(
            File(theaterDir, "TerrData\\Objects\\Falcon4_CT.xml"),
            install.baseDir?.let { File(it, "Data\\TerrData\\Objects\\Falcon4_CT.xml") },
        ).firstOrNull { it.isFile } ?: return null
        val found = HashSet<Int>()
        runCatching {
            table.bufferedReader().use { reader ->
                var number = -1
                var domain = -1
                var type = -1
                reader.forEachLine { line ->
                    val l = line.trim()
                    when {
                        l.startsWith("<CT ") -> {
                            number = l.substringAfter("Num=\"", "").substringBefore('"').toIntOrNull() ?: -1
                            domain = -1; type = -1
                        }
                        l.startsWith("<Domain>") -> domain = l.removePrefix("<Domain>").substringBefore('<').toIntOrNull() ?: -1
                        l.startsWith("<Type>") -> type = l.removePrefix("<Type>").substringBefore('<').toIntOrNull() ?: -1
                        l.startsWith("</CT>") -> {
                            // air (domain 2), and the first type of it, is a flight
                            if (number >= 0 && domain == 2 && type == 1) found += number + 100
                            number = -1
                        }
                    }
                }
            }
        }.onFailure { BridgeLog.warn("The class table could not be read: ${it.message}") }
        if (found.isEmpty()) return null
        flightTypesByDir[theaterDir.path] = found
        return found
    }

    /** The campaign's string table: "AIR REFUEL", "CAP", "SEAD" and the rest, by number. */
    private fun missionNames(theaterDir: File, install: BmsInstall): Map<Int, String> {
        namesByDir[theaterDir.path]?.let { return it }
        val file = listOfNotNull(
            File(theaterDir, "Campaign\\Strings.txt"),
            install.baseDir?.let { File(it, "Data\\Campaign\\Strings.txt") },
        ).firstOrNull { it.isFile }
        val map = HashMap<Int, String>()
        if (file != null) runCatching {
            file.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val id = line.substring(0, tab).trim().toIntOrNull()
                    val text = line.substring(tab + 1).trim()
                    if (id != null && text.isNotEmpty()) map[id] = text
                }
            }
        }
        namesByDir[theaterDir.path] = map
        return map
    }

    /** Only used by the developer check: what the file holds and how far the reading got. */
    fun describe(source: Source, install: BmsInstall): String = buildString {
        explain = true
        val blob = source.file.readBytes()
        val types = flightTypes(source.theaterDir, install)
        appendLine("  class table: ${types?.size ?: 0} flight types")
        why.clear()
        for (part in MissionArchive.parts(blob)) {
            val c = MissionArchive.contents(blob, part)
            append("  part ${part.name}: ${part.length} packed")
            if (c == null) { appendLine(" -> could not be unpacked"); continue }
            appendLine(" -> ${c.bytes.size} bytes, ${c.records} records")
            if (part.name.endsWith(".uni", true) && types != null) {
                var hits = 0
                var kept = 0
                why.clear()
                var at = 0
                while (at + 12 < c.bytes.size) {
                    val t = MissionArchive.int16(c.bytes, at)
                    if (t in types && MissionArchive.int16(c.bytes, at + 10) == t) {
                        hits++
                        if (routeAt(c.bytes, at, emptyMap()) != null) kept++
                    }
                    at++
                }
                appendLine("    signature hits: $hits, routes accepted: $kept")
                appendLine("    rejections: " + why.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" })
            }
        }
    }

    /** Only used by the developer check: how far apart two points are, in nautical miles. */
    internal fun apart(a: RoutePoint, b: RoutePoint) = hypot(abs(a.x - b.x), abs(a.y - b.y)) / 6076.12
}
