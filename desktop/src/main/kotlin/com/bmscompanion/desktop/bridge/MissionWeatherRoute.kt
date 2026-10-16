package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.BmsSky
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.mission.MissionWeather
import com.bmscompanion.app.data.mission.MissionWxFrame
import com.bmscompanion.app.data.mission.MissionWxMap
import com.bmscompanion.app.data.mission.MissionWxPlace
import com.bmscompanion.app.data.mission.PcWeather
import com.bmscompanion.app.data.weather.CampaignTime
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.roundToInt

/**
 * The Weather tab's **Mission weather** page, PC side (1.4.0): `GET /api/weather/mission` ([MissionWeather]) and
 * `GET /api/weather/mission/map?frame=<id>` ([MissionWxMap]). Read only — nothing here writes, and nothing throws.
 *
 * **Which save.** The save BMS flies: in WDP mode the one Populate took (its theater, file and flight); in EZBoards mode
 * the save [MissionDtcFile] names for the printed briefing (the newest settled save of the theater), its flight found
 * from the briefing ([MissionDtcFile.briefedFlight]) when that save holds it — without a flight the weather is still
 * the save's, with no take-off, target or landing marked. Its weather file is read through `GET /api/files/weather`
 * with `save=` and `clock=`, as the Planner's card and Populate read it ([SaveWeather]), so another save's `.twx` (the
 * shared `Auto Save.twx`) is refused in the same sentence.
 *
 * **Which maps** (Map Model only; [frames]): with MAPS AUTO UPDATE off, the save's own `<save>.fmap` (BMS's copy of
 * the map picked, version 8). With it on, the theater's `WeatherMapsUpdates/DHHMM.fmap`: the one in force at the save's
 * clock (the newest named at or before it, as [PcFileRoutes] picks it; the save's own map when none is), and every later
 * one up to [AFTER_MIN] past the flight's landing (or the clock), at most [MAX_FRAMES]. BMS also drifts each map by the
 * heading and speed in its header between updates; that drift is not applied (its units and sense are not tested in
 * the sim, docs/WEATHER.md), and the page says so.
 */
internal object MissionWeatherRoute {
    /** How far past the landing (or the save's clock) the update maps are offered, minutes. */
    const val AFTER_MIN = 6 * 60L
    /** The most update maps one answer lists (Korea ships 741 hourly ones). */
    const val MAX_FRAMES = 36

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The last answer and the frame files it named, so `map?frame=` is resolved against what the page was told. */
    private class Last(val at: Long, val answer: MissionWeather, val files: Map<String, File>)
    @Volatile private var last: Last? = null
    private val mapCache = object : LinkedHashMap<String, MissionWxMap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MissionWxMap>?): Boolean = size > 6
    }

    fun handle(req: ApiRequest): ApiResponse = try {
        when (req.path.trimEnd('/')) {
            "/api/weather/mission" -> ApiResponse.json(Bridge.json.encodeToString(MissionWeather.serializer(), current()))
            "/api/weather/mission/map" -> {
                val id = req.query["frame"]?.trim().orEmpty()
                val file = files()[id]
                val m = file?.let { mapOf(id, it) }
                if (m == null) ApiResponse.json(
                    """{"error":${Bridge.json.encodeToString(String.serializer(), if (file == null) "That map is not one of the mission's: read the mission's weather again." else "${file.name} could not be read as a weather map.")}}""",
                    if (file == null) 404 else 409,
                ) else ApiResponse.json(Bridge.json.encodeToString(MissionWxMap.serializer(), m))
            }
            else -> ApiResponse.notFound()
        }
    } catch (e: Throwable) {
        ApiResponse.json("""{"error":"The mission's weather could not be read."}""", 500)
    }

    /** The mission's weather now; worked out again at most every few seconds. */
    @Synchronized
    fun current(): MissionWeather {
        val now = System.currentTimeMillis()
        last?.let { if (now - it.at < 3_000L) return it.answer }
        val (answer, files) = safe(MissionWeather(why = "The mission's weather could not be read on the PC.") to emptyMap<String, File>()) { compute() }
        last = Last(now, answer, files)
        return answer
    }

    /** The frame files of the last answer (worked out now if there is none). */
    private fun files(): Map<String, File> {
        if (last == null) current()
        return last?.files.orEmpty()
    }

    /** Forgets the cached answer (a check that changes the files between two looks). */
    @Synchronized
    fun forget() { last = null; synchronized(mapCache) { mapCache.clear() } }

    private fun compute(): Pair<MissionWeather, Map<String, File>> {
        val install = Bridge.install
        if (install.baseDir == null) return MissionWeather(why = "Falcon BMS was not found on this PC.") to emptyMap()
        val set = Theaters.of(install) ?: return MissionWeather(why = "Falcon BMS's theater definitions could not be read.") to emptyMap()
        val wdp = MissionSource.wdp
        val mode = if (wdp) MissionMode.WDP else MissionMode.EZBOARDS
        if (wdp) {
            val p = MissionSource.populated()
                ?: return MissionWeather(mode = mode, why = "WDP mode: nothing is populated yet. Open the mission in the Planner and press Populate from Planner.") to emptyMap()
            val t = set.all.firstOrNull { it.name.equals(p.theater.trim(), ignoreCase = true) } ?: set.byName(p.theater)
                ?: return MissionWeather(mode = mode, why = "The populated theater ${p.theater} is not in this Falcon BMS.") to emptyMap()
            val saveFile = safe(null) { set.saves(t).firstOrNull { it.name.equals(p.save.trim(), ignoreCase = true) } }
                ?: return MissionWeather(mode = mode, theater = t.name, why = "${p.save} is no longer in the campaign folder of ${t.name}.") to emptyMap()
            val flight = (CampaignFiles.flight(CampaignFiles.context(), t.name, saveFile.name, p.flight) as? CampaignFiles.Answer.Ok)?.value
            val archive = safe(null) { CampaignArchive.cached(saveFile, CampaignArchive.names(set, t)) }
            return of(mode, t, saveFile, flight, p.flight, archive)
        }
        // EZBoards mode: the save BMS flies for the printed briefing, and the printed flight in it when it is there
        val v = MissionDtcFile.verdict()
        // the theater BMS is set to; with none named (a fresh install), the base theater, as BMS starts in it
        val t = v.theater?.let { set.byName(it) } ?: set.current(install.theater) ?: set.all.firstOrNull()
            ?: return MissionWeather(mode = mode, why = "Falcon BMS names no theater on this PC.") to emptyMap()
        val saveFile = v.save ?: safe(null) { set.newestSave(t) }
            ?: return MissionWeather(mode = mode, theater = t.name, why = "There is no campaign or TE save in ${t.name} yet: save the mission in BMS, then look again.") to emptyMap()
        val b = Bridge.currentBriefing()
        val archive = safe(null) { CampaignArchive.cached(saveFile, CampaignArchive.names(set, t)) }
        val f = if (b != null && archive?.uni != null) safe(null) { MissionDtcFile.briefedFlight(archive, b) } else null
        val flightId = f?.id?.toString()
        val flight = flightId?.let { id -> (CampaignFiles.flight(CampaignFiles.context(), t.name, saveFile.name, id) as? CampaignFiles.Answer.Ok)?.value }
        val (w, files) = of(mode, t, saveFile, flight, flightId, archive)
        val extra = when {
            b == null -> "No briefing is printed: this is the weather of the newest save, ${saveFile.name}."
            flight == null -> "The printed flight is not in ${saveFile.name}, the newest save: its weather is shown without the flight's take-off, target and landing."
            else -> null
        }
        return (if (extra != null && w.ok) w.copy(notes = listOf(extra) + w.notes) else w) to files
    }

    /**
     * The weather of [saveFile] (theater [t]) for [flight] ([flightId] "num/creator"; both null when the flight is not
     * known), as the page shows it, and the frame files by id. Public for `--wxviewrender`, which names its saves.
     */
    fun of(
        mode: String, t: Theaters.Theater, saveFile: File, flight: CampFlight?, flightId: String?, archive: CampaignArchive.Save?,
    ): Pair<MissionWeather, Map<String, File>> {
        val dir = saveFile.absoluteFile.parentFile
        val twxFile = File(dir, saveFile.name.substringBeforeLast('.') + ".twx")
        val clock = flight?.clock ?: archive?.header?.currentTime
        val base = MissionWeather(
            mode = mode, theater = t.name, appTheater = t.appId, save = saveFile.name, flight = flight?.row?.callsign?.takeIf { it.isNotBlank() },
            twxFile = twxFile.name, clock = clock,
        )
        val query = buildMap {
            put("path", twxFile.path)
            clock?.let { put("clock", it.toString()) }
            put("save", saveFile.name)
        }
        val res = safe(null) { PcFileRoutes.handle(ApiRequest("GET", "/api/files/weather", query)) }
            ?: return base.copy(why = "${twxFile.name} could not be read on the PC.") to emptyMap()
        val text = res.body.toString(Charsets.UTF_8)
        if (res.status != 200) {
            val said = safe(null) { lenient.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }
            val why = if (res.status == 404) "${saveFile.name} has no weather file beside it (${twxFile.name}): save the mission in BMS (a TE: SAVE WTH, then save)."
            else said ?: "${twxFile.name} could not be read (${res.status})."
            return base.copy(why = why) to emptyMap()
        }
        val w = safe(null) { lenient.decodeFromString(PcWeather.serializer(), text) }
            ?: return base.copy(why = "${twxFile.name} could not be read: the PC's answer did not decode.") to emptyMap()
        w.otherSave?.let { return base.copy(why = it, twxModified = w.modified) to emptyMap() }
        val twx = w.twx ?: return base.copy(why = "${twxFile.name} could not be read as BMS's weather settings.") to emptyMap()
        val clockNow = twx.clock ?: clock
        val places = safe(emptyList()) { places(flight, archive, flightId) }
        val notes = ArrayList<String>()
        val files = LinkedHashMap<String, File>()
        val frames = ArrayList<MissionWxFrame>()
        var start = 0
        if (twx.model == MissionWeather.MAP_MODEL) {
            val own = File(dir, saveFile.name.substringBeforeLast('.') + ".fmap")
            val minuteNow = clockNow?.let { it / 60_000L }
            val updates = if (twx.mapUpdates > 0) updateMaps(File(dir, "WeatherMapsUpdates")) else emptyList()
            val inForce = minuteNow?.let { m -> updates.lastOrNull { it.first <= m } }
            if (inForce == null && own.isFile) {
                files["save"] = own
                frames += MissionWxFrame(id = "save", file = own.name, minute = minuteNow, kind = "save")
            }
            if (updates.isNotEmpty() && minuteNow != null) {
                val lastMs = places.maxOfOrNull { it.ms }?.takeIf { it > 0 } ?: clockNow
                val end = maxOf(minuteNow, lastMs / 60_000L) + AFTER_MIN
                val from = inForce?.first ?: minuteNow
                updates.filter { it.first in from..end }.take(MAX_FRAMES).forEach { (minute, f) ->
                    val id = "u" + f.name.substringBeforeLast('.')
                    files[id] = f
                    frames += MissionWxFrame(id = id, file = f.name, minute = minute, kind = "update")
                }
                if (updates.none { it.first > minuteNow }) notes += "MAPS AUTO UPDATE is on, and the theater's update folder has no map after the save's clock: the last map stays in force."
            } else if (twx.mapUpdates > 0 && updates.isEmpty()) {
                notes += "MAPS AUTO UPDATE is on, and the theater's WeatherMapsUpdates folder holds no update maps: the save's own map is shown."
            }
            start = 0
            if (frames.isEmpty()) notes += "${saveFile.name} flies a weather map, and ${own.name} is not beside it to read: the type BMS's own table gives is shown instead."
        }
        // the Probabilistic and Deterministic models are worded on the page itself, beside their tables
        return base.copy(
            ok = true, twxModified = w.modified, model = twx.model, condition = twx.condition, clock = clockNow,
            mapUpdates = twx.mapUpdates > 0, twx = twx, frames = frames, start = start, places = places,
            notes = notes + listOfNotNull(w.note?.takeIf { frames.isEmpty() && twx.model == MissionWeather.MAP_MODEL }),
            sky = safe(null) { sky(t) },
        ) to files
    }

    /**
     * The theater's `<terraindir>\Weather\weather.ini` (BMS's `Weather::Setup`: `TimeZone`, `Latitude`, `Longitude`,
     * whole numbers), or null when it is not there or says nothing. Read only.
     */
    fun sky(t: Theaters.Theater): BmsSky? {
        // the Data folder: the theater definition's file less the path theater.lst names it by
        var data: File? = t.tdf.absoluteFile
        repeat(t.listed.replace('/', '\\').split('\\').count { it.isNotBlank() }) { data = data?.parentFile }
        val dir = data ?: return null
        val terrain = Theaters.resolveDir(dir, t.terrainDir?.trim()?.trim('"')?.takeIf { it.isNotEmpty() } ?: "TerrData\\Korea") ?: return null
        val ini = Theaters.resolveFile(terrain, "Weather\\weather.ini") ?: return null
        if (ini.length() > 64 * 1024) return null
        val keys = HashMap<String, Int>()
        ini.readLines(Charsets.ISO_8859_1).forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) parts[1].toIntOrNull()?.let { keys.putIfAbsent(parts[0].lowercase(), it) }
        }
        val tz = keys["timezone"] ?: return null
        val lat = keys["latitude"] ?: return null
        val lon = keys["longitude"] ?: return null
        if (tz !in -14..14 || lat !in -90..90 || lon !in -180..180) return null
        return BmsSky(tz, lat, lon)
    }

    /** The flight's take-off, target and landing ([SaveWeather.places]), each named where the save names it. */
    private fun places(flight: CampFlight?, archive: CampaignArchive.Save?, flightId: String?): List<MissionWxPlace> {
        flight ?: return emptyList()
        val p = SaveWeather.places(flight, archive, flightId)
        // the target's own name, from the waypoint the target place is (never a take-off's or landing's airbase)
        val target = p.target?.let { (x, y) ->
            flight.route.firstOrNull { wp ->
                wp.action != 1 && wp.action != 7 && (wp.target?.x == x && wp.target.y == y || wp.x == x && wp.y == y)
            }?.target?.name
        }
        return listOfNotNull(
            p.takeoff?.let { MissionWxPlace(SaveWeather.COLUMNS[0], flight.home?.name, it.first, it.second, p.takeoffMs) },
            p.target?.let { MissionWxPlace(SaveWeather.COLUMNS[1], target, it.first, it.second, p.targetMs) },
            p.landing?.let { MissionWxPlace(SaveWeather.COLUMNS[2], flight.landing?.name, it.first, it.second, p.landingMs) },
        )
    }

    /** Every update map of [dir] with the campaign minute its name gives, in time order. */
    private fun updateMaps(dir: File): List<Pair<Long, File>> = safe(emptyList()) {
        if (!dir.isDirectory) return@safe emptyList()
        dir.listFiles { x -> x.isFile && x.name.endsWith(".fmap", ignoreCase = true) }.orEmpty()
            .mapNotNull { x -> CampaignTime.ofFmapName(x.name)?.let { it.minutes.toLong() to x } }
            .sortedBy { it.first }
    }

    /** One frame's cells, cached by file, size and time. */
    private fun mapOf(id: String, f: File): MissionWxMap? {
        val key = "${f.path}|${safe(0L) { f.length() }}|${safe(0L) { f.lastModified() }}"
        synchronized(mapCache) { mapCache[key]?.let { return it.copy(id = id) } }
        val m = Fmap.read(f) ?: return null
        val out = cells(m, id, f.name)
        synchronized(mapCache) { mapCache[key] = out }
        return out
    }

    /** A map's cells in the page's units ([MissionWxMap]). */
    fun cells(m: Fmap, id: String, name: String): MissionWxMap {
        val n = m.cells
        val levels = Fmap.Field.LEVELS
        fun r1(v: Float): Float = if (v.isFinite()) (v * 10f).roundToInt() / 10f else 0f
        fun i(v: Float): Int = if (v.isFinite()) v.roundToInt() else 0
        return MissionWxMap(
            id = id, file = name, version = m.version, cols = m.cols, rows = m.rows,
            moveHeading = m.moveHeading, moveKt = r1(m.moveSpeed), stratusFt = m.stratus, contrailFt = m.contrails,
            type = List(n) { m.int(Fmap.Field.TYPE, it) },
            pressureMb = List(n) { r1(m.float(Fmap.Field.PRESSURE, it)) },
            tempC = List(n) { r1(m.float(Fmap.Field.TEMPERATURE, it)) },
            cloudBaseFt = List(n) { i(m.float(Fmap.Field.CLOUD_BASE, it)) },
            cover = List(n) { m.int(Fmap.Field.COVER, it) },
            towering = List(n) { m.int(Fmap.Field.TOWERING, it) },
            // a version 8 map's array 27 is the shower flag; a version 5 map has none
            shower = if (m.version == Fmap.VERSION_8) List(n) { m.int(Fmap.Field.VISIBILITY, it) } else emptyList(),
            visKm = List(n) { r1(m.visibilityKm(it)) },
            windKt = List(n * levels) { k -> i((m.windSpeed(k / levels, k % levels) / Fmap.KMH_PER_KT).toFloat()) },
            windDeg = List(n * levels) { k -> i(m.windDir(k / levels, k % levels)).mod(360) },
        )
    }

    private inline fun <T> safe(fallback: T, body: () -> T): T = try { body() } catch (_: Throwable) { fallback }
}
