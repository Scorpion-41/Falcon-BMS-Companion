package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.PcWeather
import com.bmscompanion.app.data.mission.WeatherRow
import com.bmscompanion.app.data.mission.WeatherTable
import com.bmscompanion.app.ui.screens.wdp.CardWeather
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.roundToInt

/**
 * WDP mode's weather: **Populate from Planner** reads the save's own weather file, `<save>.twx` beside the save (and,
 * when its model is a weather map, the map BMS flies with it at the save's clock), into the snapshot's briefing as
 * BMS's printed briefing words its weather block — Take Off, Target Area and Landing columns, a row per figure — so
 * the Dashboard's WEATHER card, the Briefing page and the VR boards show it as they show a printed briefing's. The
 * briefing the PC makes of a save's flight ([CampFlight.briefing], `origin` "save") has no weather of its own: only
 * PRINT words one. When the snapshot carries BMS's printed briefing instead (it is the populated flight's), its printed
 * weather stays unless this file was saved after the print with other weather — the Planner card's rule
 * ([MissionSource], `saveWeatherWins`).
 *
 * **The same reading as the Planner's card.** The file is read by the PC's own `GET /api/files/weather` route
 * ([PcFileRoutes], called in-process with `save=`, so another save's file is refused — `Auto Save.cam` and
 * `Auto Save.tac` share one `.twx` — and `clock=` the flight's campaign time, which picks the update map in force), and
 * each place's weather is [CardWeather.column], the function the DataCard's ATIS and weather list use: the map's cell
 * the place lies in, or the table of the weather type the file is in. The places are the take-off field (the flight's
 * home), the target (the waypoint BMS marks as the target, at its target's position, else the first strike
 * steerpoint) and the landing field.
 *
 * What the reading does not give is left out rather than guessed: no contrail layer, and "VRB" for the wind's
 * direction when BMS picks it itself (the file then holds none; PRINT words the one it picked). BMS's printed briefing
 * prints no pressure; this block has a QNH row, the pressure the file gives. The weather is the file's as saved — BMS
 * may change it before take-off — and [WeatherTable.source] says which file and as of when.
 *
 * Read only; nothing here throws: a file that cannot be used comes back as the sentence saying why.
 */
internal object SaveWeather {
    /**
     * What Populate keeps: the [table] (null when the file gives none), the weather [file] beside the save (kept even
     * when it is missing or another save's, so "changed since" notices it being saved), its time [modified] (0 when
     * missing), and [why], the sentence saying why there is no table.
     */
    class Read(val table: WeatherTable?, val file: File?, val modified: Long, val why: String?)

    /** BMS's three columns, as its briefing prints them (`Weather.b`). */
    val COLUMNS = listOf("Take Off", "Target Area", "Landing")

    /** A waypoint BMS marks as the flight's target (`WPF_TARGET`, as [CampaignBriefing] finds its Target Area). */
    private const val WPF_TARGET = 0x1L
    private const val ACT_TAKEOFF = 1
    private const val ACT_LAND = 7
    private const val ACT_STRIKE = 17

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The weather of [saveFile] for [flight] (theater [appId]); [archive] is the save as [CampaignArchive] read it and
     * [flightId] the flight's "num/creator", for the waypoint BMS marks as the target (without them, the first strike).
     */
    fun read(saveFile: File, flight: CampFlight, appId: String?, archive: CampaignArchive.Save?, flightId: String?): Read {
        val twx = safe(null) { File(saveFile.absoluteFile.parentFile, saveFile.name.substringBeforeLast('.') + ".twx") }
            ?: return Read(null, null, 0L, "The save's weather file could not be named.")
        val modified = safe(0L) { if (twx.isFile) twx.lastModified() else 0L }
        val query = buildMap {
            put("path", twx.path)
            flight.clock?.let { put("clock", it.toString()) }
            put("save", saveFile.name)
        }
        val res = safe(null) { PcFileRoutes.handle(ApiRequest("GET", "/api/files/weather", query)) }
            ?: return Read(null, twx, modified, "${twx.name} could not be read on the PC.")
        val text = res.body.toString(Charsets.UTF_8)
        if (res.status != 200) {
            val said = safe(null) { lenient.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }
            return Read(null, twx, modified, said ?: "${twx.name} could not be read (${res.status}).")
        }
        val w = safe(null) { lenient.decodeFromString(PcWeather.serializer(), text) }
            ?: return Read(null, twx, modified, "${twx.name} could not be read: the PC's answer did not decode.")
        w.otherSave?.let { return Read(null, twx, modified, it) }
        val table = safe(null) { table(w, twx.name, flight, appId, archive, flightId) }
            ?: return Read(null, twx, modified, "${twx.name} gives no weather at the flight's take-off: the field could not be placed on the theater.")
        return Read(table, twx, modified, null)
    }

    /**
     * The three places and times the columns are read at: the take-off field (the flight's home, else its take-off
     * waypoint), the target (the waypoint BMS marks as the target, at its target's own position where the save gives
     * one, else the first strike steerpoint; null for a flight with neither) and the landing field (the flight's
     * landing, else its landing waypoint). Positions are theater feet, north then east; times campaign ms.
     */
    class Places(val takeoff: Pair<Double, Double>?, val target: Pair<Double, Double>?, val landing: Pair<Double, Double>?, val takeoffMs: Long, val targetMs: Long, val landingMs: Long)

    fun places(flight: CampFlight, archive: CampaignArchive.Save?, flightId: String?): Places {
        val route = flight.route
        val takeoffWp = route.firstOrNull { it.action == ACT_TAKEOFF } ?: route.firstOrNull()
        val landingWp = route.firstOrNull { it.action == ACT_LAND } ?: route.lastOrNull()
        val marked = safe(-1) {
            archive?.flight(CampaignArchive.VuId.parse(flightId))?.waypoints?.indexOfFirst { it.flags and WPF_TARGET != 0L } ?: -1
        }
        val targetWp = route.getOrNull(marked) ?: route.firstOrNull { it.action == ACT_STRIKE }
        fun spot(x: Double?, y: Double?) = if (x != null && y != null && (x != 0.0 || y != 0.0)) x to y else null
        val takeoffMs = takeoffWp?.let { if (it.departMs > 0) it.departMs else it.arriveMs } ?: 0L
        return Places(
            takeoff = spot(flight.home?.x, flight.home?.y) ?: takeoffWp?.let { spot(it.x, it.y) },
            target = targetWp?.let { spot(it.target?.x, it.target?.y) ?: spot(it.x, it.y) },
            landing = spot(flight.landing?.x, flight.landing?.y) ?: landingWp?.let { spot(it.x, it.y) },
            takeoffMs = takeoffMs, targetMs = targetWp?.arriveMs ?: takeoffMs, landingMs = landingWp?.arriveMs ?: takeoffMs,
        )
    }

    /** The theater's side in feet (the app's `index.json`), 0 when the app does not know the theater. */
    fun sizeFt(appId: String?): Double =
        appId?.let { id -> safe(null) { kotlinx.coroutines.runBlocking { Repo.index().theaters.firstOrNull { it.id == id }?.sizeFt } } } ?: 0.0

    /** The weather block of [w] for [flight]; null when not even the take-off has weather. */
    private fun table(w: PcWeather, name: String, flight: CampFlight, appId: String?, archive: CampaignArchive.Save?, flightId: String?): WeatherTable? {
        val size = sizeFt(appId)
        val at = places(flight, archive, flightId)
        fun read(p: Pair<Double, Double>?, time: Long): CardWeather.Column? =
            p?.let { (north, east) -> safe(null) { CardWeather.column(w, north, east, size, time) } }
        // a table of BMS's four types is the same everywhere, so a place that cannot be placed still has it; a map's is not
        val anywhere = if (w.cols > 0 && w.rows > 0 && size > 0) null else safe(null) { CardWeather.column(w, 0.0, 0.0, 0.0, at.takeoffMs) }
        val takeoff = read(at.takeoff, at.takeoffMs) ?: anywhere ?: return null
        // a flight with no target (a ferry, a CAP BMS marks nothing on) shows the take-off's, as the three columns read alike
        val target = read(at.target, at.targetMs) ?: anywhere ?: takeoff
        val landing = read(at.landing, at.landingMs) ?: anywhere ?: takeoff
        val cols = listOf(takeoff, target, landing)
        val rows = listOf(
            WeatherRow("Situation", cols.map { it.situation ?: "–" }),
            WeatherRow("Wind", cols.map(::wind)),
            WeatherRow("Visibility", cols.map { c -> c.visM?.let(::visibility) ?: "–" }),
            WeatherRow("Temp", cols.map { c -> c.tempC?.let { "${it}deg C." } ?: "–" }),
            WeatherRow("Cloud Base", cols.map(::cloud)),
            WeatherRow("QNH", cols.map { c -> c.qnhHpa?.let(::qnh) ?: "–" }),
        )
        val clock = w.twx?.clock ?: flight.clock
        val map = w.map?.let { File(it).name }?.takeIf { !it.equals(name, ignoreCase = true) }
        val source = "From $name" + (clock?.let { ", as saved at ${CampaignFiles.clockShort(it)}" } ?: "") +
            (map?.let { " (the map $it)" } ?: "") + "." +
            (if (cols.any { it.windDir == null && it.windKts != null }) " BMS picks the wind's direction itself, so the file holds none (VRB here); BMS's printed briefing names the one it picked." else "") +
            (w.note?.let { " $it" } ?: "") + " BMS may change the weather before take-off."
        return WeatherTable(COLUMNS, rows, source)
    }

    /** "80deg@ 15kts." as BMS prints it; "VRB@ 15kts." when the file holds no direction (BMS picks it). */
    private fun wind(c: CardWeather.Column): String {
        val kts = c.windKts ?: return "–"
        return (c.windDir?.let { "${it}deg" } ?: "VRB") + "@ ${kts}kts."
    }

    /** "115km" as BMS prints it (whole kilometres, cut), "4.5km" under ten. */
    private fun visibility(m: Int): String =
        if (m >= 10_000) "${m / 1000}km" else "${m / 1000}.${m / 100 % 10}km"

    /** "5,000 ft MSL base" as BMS prints it; "None" where a map's cell has no cloud. */
    private fun cloud(c: CardWeather.Column): String {
        if (c.cover == 0) return "None"
        val ft = c.cloudFt ?: return "–"
        val grouped = ft.toString().reversed().chunked(3).joinToString(",").reversed()
        return "$grouped ft MSL base"
    }

    /** "1013 hPa · 29.91 inHg". */
    private fun qnh(hPa: Double): String = "${hPa.roundToInt()} hPa · ${CardWeather.forceQnh(hPa, metric = false)} inHg"

    private inline fun <T> safe(fallback: T, body: () -> T): T = try { body() } catch (_: Throwable) { fallback }
}
