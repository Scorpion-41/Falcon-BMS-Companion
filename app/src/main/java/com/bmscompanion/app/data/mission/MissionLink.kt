package com.bmscompanion.app.data.mission

import kotlinx.serialization.builtins.ListSerializer
import android.content.Context
import android.net.wifi.WifiManager
import com.bmscompanion.app.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL

/** Connection state shown in the Mission header. */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data class Online(val host: String) : LinkState
    data class Offline(val host: String, val reason: String) : LinkState
}

data class FoundBridge(val host: String, val port: Int, val name: String, val version: String)

/**
 * Talks to the BMS Companion Bridge running next to Falcon BMS on the PC (plain HTTP + JSON on the LAN).
 * Polling only runs while a Mission screen is visible ([acquire]/[release]) so the app is idle otherwise.
 */
object MissionLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var users = 0

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state
    private val _info = MutableStateFlow<BridgeInfo?>(null)
    val info: StateFlow<BridgeInfo?> = _info
    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live
    private val _contacts = MutableStateFlow<Contacts?>(null)
    val contacts: StateFlow<Contacts?> = _contacts
    private val _mission = MutableStateFlow<MissionData?>(null)
    val mission: StateFlow<MissionData?> = _mission
    private val _bmsFiles = MutableStateFlow<MissionData?>(null)
    /**
     * Falcon BMS's own files as they are now (the printed briefing, the cartridge, EZBoards' board, BMS's believed route),
     * whatever mode the Mission section is in: what the **Planner** plans from. In EZBoards mode it is [mission] itself;
     * in WDP mode, where [mission] is the snapshot Populate from Planner took, it is fetched on its own
     * (`/api/mission?source=bms`) whenever those files change.
     */
    val bmsFiles: StateFlow<MissionData?> = _bmsFiles
    private val _ez = MutableStateFlow<EzUi>(EzUi())
    val ez: StateFlow<EzUi> = _ez

    data class EzUi(val running: Boolean = false, val result: EzRun? = null)

    val host: String? get() = Repo.getString("bridge_host")
    val port: Int get() = Repo.getInt("bridge_port", 47474)
    private fun base() = host?.let { "http://$it:$port" }

    fun setBridge(host: String, port: Int) {
        Repo.putString("bridge_host", host.trim())
        Repo.putInt("bridge_port", port)
        _mission.value = null
        _bmsFiles.value = null
        _info.value = null
        _live.value = null
        _contacts.value = null
        restart()
    }

    fun forget() {
        Repo.putString("bridge_host", null)
        stop()
        _state.value = LinkState.Idle
        _info.value = null; _live.value = null; _contacts.value = null; _mission.value = null; _bmsFiles.value = null
    }

    @Volatile private var fastUsers = 0

    /** The AWACS page wants contacts twice a second instead of once. */
    @Synchronized fun fastContacts(on: Boolean) { fastUsers = (fastUsers + if (on) 1 else -1).coerceAtLeast(0) }

    @Synchronized fun acquire() { users++; if (job == null) restart() }
    @Synchronized fun release() { users = (users - 1).coerceAtLeast(0); if (users == 0) stop() }

    @Synchronized private fun stop() { job?.cancel(); job = null }

    @Synchronized private fun restart() {
        job?.cancel()
        job = null
        if (users == 0 || host == null) return
        job = scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        val h = host ?: return
        _state.value = LinkState.Connecting
        var tick = 0L
        var failures = 0
        var missionVersion = ""
        var bmsVersion = ""
        while (scope.isActive) {
            val info = runCatching { get<BridgeInfo>("/api/info") }
            if (info.isFailure) {
                failures++
                if (failures >= 2 || _state.value is LinkState.Connecting) _state.value = LinkState.Offline(h, reasonOf(info.exceptionOrNull()))
                delay(if (failures < 5) 1500 else 4000)
                continue
            }
            failures = 0
            val i = info.getOrThrow()
            _info.value = i
            _state.value = LinkState.Online(h)
            // the plan and BMS's mission-file route are part of the mission too (MissionData.plan / .route), and so is
            // the mode the Mission section is in (1.3.8): a switch, a Populate or "changed since" fetches it again
            val files = "${i.briefing.modified}-${i.briefing.dtcModified}-${i.ezBoards.lastRun?.time ?: 0}-${i.briefing.routeModified}"
            val want = "$files-${i.briefing.planModified}-${modeKey(i)}"
            // a mission of the other mode (a fetch that crossed a switch, or one that failed after it) is never kept
            if (want != missionVersion || _mission.value == null || (_mission.value?.mode == MissionMode.WDP) != i.mission.wdp) {
                runCatching { get<MissionData>("/api/mission") }.onSuccess { _mission.value = it; missionVersion = want }
            }
            // what the Planner plans from: BMS's own files, whatever the mode (in EZBoards mode that is the mission itself,
            // but never a WDP-mode snapshot still held from before a switch: docs/DATA-STORES.md, "Switching modes")
            if (!i.mission.wdp) {
                val m = _mission.value
                if (m != null && m.mode != MissionMode.WDP && _bmsFiles.value !== m) _bmsFiles.value = m
                bmsVersion = ""
            } else if (files != bmsVersion || _bmsFiles.value == null) {
                runCatching { get<MissionData>("/api/mission?source=bms") }.onSuccess { _bmsFiles.value = it; bmsVersion = files }
            }
            // ~4 Hz live data, 1 Hz contacts (2 Hz on the AWACS page), info every ~2 s (the info call above doubles
            // as the heartbeat). Perf.easy quarters the live rate for a machine whose graphics cannot keep up.
            repeat(com.bmscompanion.app.data.Perf.livePerRound) {
                runCatching { get<Live>("/api/live") }.onSuccess { _live.value = it }
                // one contact reading to every four live ones at full rate, every other one when the machine is being spared
                if (tick % (if (fastUsers > 0) 2L else if (com.bmscompanion.app.data.Perf.easy) 2L else 4L) == 0L) runCatching { get<Contacts>("/api/contacts") }.onSuccess { _contacts.value = HostileContacts.filter(it, HostileContacts.on(_info.value)) }
                tick++
                delay(com.bmscompanion.app.data.Perf.liveMs)
            }
        }
    }

    private fun reasonOf(e: Throwable?): String = when (e) {
        is SocketTimeoutException -> "timed out: is BMS Companion running on the PC and allowed through the Windows firewall?"
        is java.net.ConnectException -> "connection refused: start BMS Companion on the PC"
        is java.net.UnknownHostException -> "unknown host"
        is IOException -> e.message ?: "network error"
        else -> e?.message ?: "error"
    }

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        val text = request("GET", path, timeoutMs = 2500)
        Repo.json.decodeFromString<T>(text)
    }

    private fun request(method: String, path: String, timeoutMs: Int, body: String? = null): String {
        val b = base() ?: throw IOException("no BMS PC set")
        val c = URL(b + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            if (method == "POST") {
                val bytes = body?.encodeToByteArray() ?: ByteArray(0)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream ?: throw IOException("HTTP $code")
            // Reading to the end and closing (without disconnect) lets HttpURLConnection reuse the keep-alive socket.
            return stream.use { it.readBytes().decodeToString() }
        } catch (e: IOException) {
            c.disconnect()
            throw e
        }
    }

    /**
     * What each VR board shows. Read by the boards themselves and by the VR board page on the PC; written only by
     * that page. Null means the PC could not be reached — an empty configuration is a real answer, not a failure.
     */
    suspend fun taxiSelection(): TaxiSelection? = runCatching { get<TaxiSelection>("/api/taxi") }.getOrNull()

    /** The radio calls BMS's debug log holds after [since] (`GET /api/radio`); null when the PC cannot be reached. */
    suspend fun radio(since: Long): RadioLog? = runCatching { get<RadioLog>("/api/radio?since=$since") }.getOrNull()

    /** Deleting BMS's old debug logs: the settings (`query` "?on=1&keep=5&days=0"), or `now` to run it (`POST /api/radio/cleanup…`). */
    suspend fun radioCleanup(query: String, now: Boolean = false): PcAnswer<RadioLogStatus> =
        answer("POST", "/api/radio/cleanup" + (if (now) "/now" else query), RadioLogStatus.serializer(), timeoutMs = 30_000)

    /**
     * What the Taxi page is showing, so a VR board can show the same field, runway and spot.
     *
     * The page and the board are different programs, so the choice is kept on the PC and the board asks for it.
     * Nothing is sent unless it has actually changed — the page publishes on every recomposition otherwise.
     */
    private var lastTaxi: String? = null

    fun publishTaxi(airportId: Int, runway: String, outbound: Boolean, spot: Int?) {
        // per mission: the PC forgets a choice made for another mission (Bridge, /api/taxi), so the same one is sent again
        val key = "${_mission.value?.missionKey}/$airportId/$runway/$outbound/$spot"
        if (key == lastTaxi) return
        lastTaxi = key
        val body = Repo.json.encodeToString(
            TaxiSelection.serializer(),
            TaxiSelection(airportId, runway, outbound, spot, System.currentTimeMillis()),
        )
        scope.launch { withContext(Dispatchers.IO) { runCatching { request("POST", "/api/taxi", timeoutMs = 4000, body = body) } } }
    }

    /** The attack the Planner last worked out, as the PC holds it for the VR boards; null when it cannot be reached. */
    suspend fun attack(): AttackOverlay? = runCatching { get<AttackOverlay>("/api/attack") }.getOrNull()

    /**
     * The Planner's attack, to the PC, where a VR board asks for it — the board is a browser tab of its own and cannot
     * see the Planner. Answers whether the PC took it, so a caller that found it unreachable can try again later.
     */
    suspend fun publishAttack(attack: AttackOverlay): Boolean = withContext(Dispatchers.IO) {
        val body = Repo.json.encodeToString(AttackOverlay.serializer(), attack)
        runCatching { request("POST", "/api/attack", timeoutMs = 4000, body = body) }.isSuccess
    }

    suspend fun boards(): BoardConfig? = runCatching { get<BoardConfig>("/api/boards") }.getOrNull()

    /** Saves it, and answers with what the PC stored. */
    suspend fun saveBoards(config: BoardConfig): BoardConfig? = withContext(Dispatchers.IO) {
        runCatching {
            val body = Repo.json.encodeToString(BoardConfig.serializer(), config)
            Repo.json.decodeFromString<BoardConfig>(request("POST", "/api/boards", timeoutMs = 8000, body = body))
        }.getOrNull()
    }

    /**
     * Opens UOAF's kneeboard exporter on the BMS PC and answers with what it said.
     *
     * A shortcut, not an export: that tool has no headless mode, so this puts its window up on the PC where the
     * pilot can press export. From a tablet it is the same window, on the PC across the room.
     */
    suspend fun openKneeboardExporter(): String = withContext(Dispatchers.IO) {
        runCatching {
            val text = request("POST", "/api/kneeboard/open", timeoutMs = 10_000)
            Regex("\"message\"\\s*:\\s*\"(.*?)\"").find(text)?.groupValues?.get(1)?.replace("\\\\", "\\")
                ?: "The exporter was asked to open on the BMS PC."
        }.getOrElse { "Could not reach the PC: ${reasonOf(it)}" }
    }

    /**
     * Turn "generate the kneeboards when the briefing is printed" on or off.
     *
     * The setting lives on the BMS PC, but the page that explains it is read on a tablet as often as on the PC, so
     * it can be set from either.
     */
    fun setBoardsOnPrint(on: Boolean) {
        scope.launch {
            runCatching { request("POST", "/api/ezboards/auto?on=" + (if (on) "1" else "0"), timeoutMs = 8_000) }
            runCatching { get<BridgeInfo>("/api/info") }.onSuccess { _info.value = it }
        }
    }

    /** Hostile contacts from the live feed on or off, on the PC for every device ([HostileContacts]). */
    fun setHostiles(on: Boolean) {
        scope.launch {
            runCatching { request("POST", "/api/contacts/hostiles?on=" + (if (on) "1" else "0"), timeoutMs = 8_000) }
            runCatching { get<BridgeInfo>("/api/info") }.onSuccess { _info.value = it }
            _contacts.value?.let { c -> _contacts.value = HostileContacts.filter(c, HostileContacts.on(_info.value)) }
        }
    }

    /** Asks the bridge to run EZBoards; the result also refreshes the board data. */
    fun generateBoards() {
        if (_ez.value.running) return
        _ez.value = EzUi(running = true, result = _ez.value.result)
        scope.launch {
            val r = runCatching {
                val text = request("POST", "/api/ezboards/generate", timeoutMs = 95_000)
                Repo.json.decodeFromString<EzRun>(text)
            }.getOrElse { EzRun(time = System.currentTimeMillis(), ok = false, message = "Could not reach the PC: ${reasonOf(it)}") }
            _ez.value = EzUi(running = false, result = r)
            runCatching { get<MissionData>("/api/mission") }.onSuccess { _mission.value = it }
        }
    }


    // ---------- Falcon BMS's own config files ----------

    /**
     * The Config section, which edits Falcon BMS's settings files on the PC.
     *
     * Everything here is read and written on the BMS PC, so a setting can be changed from a tablet for the flight
     * about to start. Nothing is offered until the backup button has been pressed; null means the PC could not be
     * reached, which the page says rather than showing an empty list.
     */

    // ---- Falcon BMS's own weather maps ----

    /**
     * Which theaters have weather this program can read, and — for [theater] only — what each of its four maps
     * currently holds. Only one theater's maps are read, because reading all of them is tens of megabytes.
     */
    suspend fun weatherState(theater: String? = null, withMap: Boolean = false): WeatherState? {
        val q = listOfNotNull(
            theater?.let { "theater=" + it },
            if (withMap) "map=1" else null,
        ).joinToString("&")
        return runCatching { get<WeatherState>("/api/weather" + (if (q.isEmpty()) "" else "?" + q)) }.getOrNull()
    }

    /** Writes a whole painted map — the palette and which entry each cell of the theater holds. */
    suspend fun weatherSetMap(theater: String, model: String, grid: WxMap): WeatherState? =
        postWeatherMap("/api/weather/setmap?theater=" + theater + "&model=" + model, grid)

    /** Copies one theater's four shipped maps aside, once. Nothing may be written until this has been done. */
    suspend fun weatherBackUp(theater: String): WeatherState? = postWeather("/api/weather/backup?theater=" + theater, null)

    /** Writes one weather into one of BMS's four ready-made maps, starting from the untouched original every time (the route `set`, no longer called). */
    suspend fun weatherSet(theater: String, model: String, wx: Wx): WeatherState? =
        postWeather("/api/weather/set?theater=" + theater + "&model=" + model, wx)

    /** Puts one model — or, with an empty model, all four — back exactly as Falcon BMS shipped them. */
    suspend fun weatherRestore(theater: String, model: String = ""): WeatherState? =
        postWeather("/api/weather/restore?theater=" + theater + (if (model.isEmpty()) "" else "&model=" + model), null)

    /**
     * Takes a new copy of the ready-made maps that differ from their copy although this program did not write them (a
     * BMS update), the earlier copy kept in the backup's `Older copies`; with an empty [model], every such map.
     */
    suspend fun weatherRefreshBackup(theater: String, model: String = ""): WeatherState? =
        postWeather("/api/weather/refreshbackup?theater=" + enc(theater) + (if (model.isEmpty()) "" else "&model=" + enc(model)), null)

    // Generated weather: the PC is sent the generator's parameters, never the grid, and runs the same shared model
    // to build the files. Times go as BMS names its update maps, DHHMM.

    /** One generated weather as a map of its own, `Campaign/BMSC <name>.fmap`. */
    suspend fun weatherGenerate(theater: String, name: String, params: com.bmscompanion.app.data.weather.WxGenParams): WeatherState? =
        postWeatherParams("/api/weather/generate?theater=" + enc(theater) + "&name=" + enc(name), params)

    /** A series for Maps Auto Update: the first map as `BMSC <name>.fmap`, then one update map every [stepMin] minutes. */
    suspend fun weatherSeries(
        theater: String,
        name: String,
        params: com.bmscompanion.app.data.weather.WxGenParams,
        from: com.bmscompanion.app.data.weather.CampaignTime,
        to: com.bmscompanion.app.data.weather.CampaignTime,
        stepMin: Int,
    ): WeatherState? = postWeatherParams(
        "/api/weather/series?theater=" + enc(theater) + "&name=" + enc(name) +
            "&from=" + from.fmapName.removeSuffix(".fmap") + "&to=" + to.fmapName.removeSuffix(".fmap") + "&step=" + stepMin,
        params,
    )

    /** Deletes a generated map this program added. */
    suspend fun weatherRemoveGenerated(theater: String, name: String): WeatherState? =
        postWeather("/api/weather/remove?theater=" + enc(theater) + "&name=" + enc(name), null)

    /** Puts back every update map a series wrote over, and deletes the ones that had no original. */
    suspend fun weatherRestoreSeries(theater: String): WeatherState? =
        postWeather("/api/weather/restoreseries?theater=" + enc(theater), null)

    // ---- the pilot's data cartridge, for the Planner's DTC page ----

    /** The cartridge of [callsign] (or of the pilot BMS has selected), whole. */
    suspend fun cartridge(callsign: String? = null): CartridgeState? =
        runCatching { get<CartridgeState>("/api/cartridge" + (callsign?.let { "?callsign=" + enc(it) } ?: "")) }.getOrNull()


    /**
     * Writes the keys the pilot changed into the cartridge as it is on disk now. With [te] (a Tactical Engagement the
     * Planner has open), the steerpoint keys among them also go into that TE's own mission file, which BMS loads over
     * the cartridge in a TE; [CartridgeState.mission] says what happened to it.
     */
    suspend fun cartridgeSave(callsign: String?, edits: List<CartridgeEdit>, te: CampRef? = null, mission: LedgerMission? = null): CartridgeState? =
        postCartridge(
            "/api/cartridge/save" + query(
                callsign?.let { "callsign=" + enc(it) }, te?.let { "te=" + enc(it.theater + "|" + it.file) },
                mission?.let { "for=" + enc(Repo.json.encodeToString(LedgerMission.serializer(), it)) },
            ),
            Repo.json.encodeToString(ListSerializer(CartridgeEdit.serializer()), edits),
        )

    /**
     * The Planner on this device opened [mission] (Open mission… or Pick a flight): in WDP mode another flight than the
     * last one opened is a new mission, and the PC clears what the Planner saved for an earlier flight by itself
     * (`POST /api/mission/opened`). The answer is the source with what was done (`reset`), shown on every device.
     */
    suspend fun missionOpened(mission: LedgerMission, clean: Boolean = false): PcAnswer<MissionSourceInfo> =
        answer(
            "POST", "/api/mission/opened?for=" + enc(Repo.json.encodeToString(LedgerMission.serializer(), mission)) + (if (clean) "&clean=1" else ""),
            MissionSourceInfo.serializer(), timeoutMs = 30_000,
        ).alsoRefreshAll()

    private suspend fun postCartridge(path: String, body: String?): CartridgeState? = withContext(Dispatchers.IO) {
        runCatching { Repo.json.decodeFromString<CartridgeState>(request("POST", path, timeoutMs = 15_000, body = body)) }.getOrNull()
    }

    // ---- the Planner's integration: campaign files, Upd Kneeboard (docs/PROTOCOL.md) ----
    //
    // (Send to Mission and its /api/plan… calls are gone since 1.3.8: WDP mode's Populate from Planner fills the Mission
    // section instead. The PC still answers those routes for older devices.)
    //
    // Every one of these needs the PC, and each answers a PcAnswer: the value, or the sentence the PC refused with
    // (or why it could not be reached), for the page to show.

    /** Which kind of device this is, as a plan names the one that sent it. Never a name. */
    val platform: String get() = "Android"

    /** Every theater's campaign folder and its saves; [all] also lists the campaign starts and templates. */
    suspend fun campaignFiles(all: Boolean = false): PcAnswer<CampFiles> =
        answer("GET", "/api/campaign/files" + (if (all) "?all=1" else ""), CampFiles.serializer(), timeoutMs = 30_000)

    /** One save's whole air tasking order, for the flight picker. [theater] is a [CampTheater.name], [file] a [CampFile.name]. */
    suspend fun campaignAto(theater: String, file: String): PcAnswer<CampAto> =
        answer("GET", "/api/campaign/ato?theater=" + enc(theater) + "&file=" + enc(file), CampAto.serializer(), timeoutMs = 30_000)

    /**
     * WDP's ATO Target List of a save: what the flights of [team]'s side are tasked to attack ([CampAtoTargets]);
     * [team] null = the pilot's own team as the PC takes it.
     */
    suspend fun campaignAtoTargets(theater: String, file: String, team: Int? = null): PcAnswer<CampAtoTargets> =
        answer(
            "GET", "/api/campaign/atotargets?theater=" + enc(theater) + "&file=" + enc(file) + (team?.let { "&team=$it" } ?: ""),
            CampAtoTargets.serializer(), timeoutMs = 30_000,
        )

    /** One flight of a save, with its route, loadout and the [Briefing] the PC made of it. [flight] is "num/creator". */
    suspend fun campaignFlight(theater: String, file: String, flight: String): PcAnswer<CampFlight> =
        answer(
            "GET", "/api/campaign/flight?theater=" + enc(theater) + "&file=" + enc(file) + "&flight=" + enc(flight),
            CampFlight.serializer(), timeoutMs = 30_000,
        )

    suspend fun campaignFlight(ref: CampRef): PcAnswer<CampFlight> = campaignFlight(ref.theater, ref.file, ref.flight)

    /** A save's objectives (the start file's), for the DataCard's target list: WDP's Target Selection window. */
    suspend fun campaignObjectives(theater: String, file: String): PcAnswer<CampObjectives> =
        answer("GET", "/api/campaign/objectives?theater=" + enc(theater) + "&file=" + enc(file), CampObjectives.serializer(), timeoutMs = 30_000)

    /** One objective's buildings ([CampObjective.id]), with where each is and the terrain there. */
    suspend fun campaignFeatures(theater: String, file: String, objective: String): PcAnswer<CampFeatures> =
        answer(
            "GET", "/api/campaign/features?theater=" + enc(theater) + "&file=" + enc(file) + "&objective=" + enc(objective),
            CampFeatures.serializer(), timeoutMs = 30_000,
        )

    /**
     * The ground under [points] (north, east feet) in [theater] — a theater-definition name or the app's id; null for the
     * one BMS is set to — from BMS's own height map: the attack pages' target elevation, as WDP's Pop-up reads it.
     */
    suspend fun campaignGround(theater: String?, points: List<Pair<Double, Double>>): PcAnswer<CampGround> {
        // positions to the thousandth of a foot, written out by hand: the browser's number printing is not the JVM's
        fun ft(v: Double): String {
            val m = kotlin.math.round(kotlin.math.abs(v) * 1000.0).toLong()
            return (if (v < 0) "-" else "") + (m / 1000) + "." + (m % 1000).toString().padStart(3, '0')
        }
        val at = points.joinToString(";") { ft(it.first) + "," + ft(it.second) }
        return answer("GET", "/api/campaign/ground?" + (theater?.let { "theater=" + enc(it) + "&" } ?: "") + "at=" + enc(at), CampGround.serializer(), timeoutMs = 15_000)
    }

    /**
     * The Planner Map page's intelligence of a save ([CampMapIntel]: units, search radars, airfield owners, JSTARS), said
     * from [flight]'s side ([team] overrides it). [file] null = the save and flight BMS briefed, in [theater] (else the
     * theater BMS is set to). A 404 is an older PC, or no such save: the page falls back on [CampFlight.airDefences].
     */
    suspend fun campaignMapIntel(theater: String?, file: String?, flight: String?, team: Int? = null): PcAnswer<CampMapIntel> {
        val q = listOfNotNull(
            theater?.let { "theater=" + enc(it) }, file?.let { "file=" + enc(it) }, flight?.let { "flight=" + enc(it) }, team?.let { "team=$it" },
        ).joinToString("&")
        return answer("GET", "/api/campaign/mapintel" + (if (q.isEmpty()) "" else "?$q"), CampMapIntel.serializer(), timeoutMs = 30_000)
    }

    /** Falcon BMS's magnetic variation map of [theater] (null = the one BMS is set to); 404 when the theater has none. */
    suspend fun campaignMagVar(theater: String?): PcAnswer<CampMagVar> =
        answer("GET", "/api/campaign/magvar" + (theater?.let { "?theater=" + enc(it) } ?: ""), CampMagVar.serializer(), timeoutMs = 15_000)

    // ---- the Mission section's source: EZBoards mode or WDP mode (docs/PROTOCOL.md, "Mission source") ----

    /** The mode and WDP mode's snapshot as the PC has them now; [info] carries the same (`BridgeInfo.mission`) on every poll. */
    suspend fun missionSource(): PcAnswer<MissionSourceInfo> =
        answer("GET", "/api/mission/source", MissionSourceInfo.serializer(), timeoutMs = 8_000)

    /**
     * Switches the Mission section to [mode] ([MissionMode.EZBOARDS] or [MissionMode.WDP]) on every device at once. It
     * asks nothing; the PC resets what the other mode left from an earlier flight than the current one ([planner]: the
     * flight this device's Planner has open, if any) and says what in `reset`. [info] and [mission] follow at once.
     */
    suspend fun setMissionMode(mode: String, planner: LedgerMission? = null): PcAnswer<MissionSourceInfo> =
        answer(
            "POST", "/api/mission/source" + query("mode=" + enc(mode), planner?.let { "for=" + enc(Repo.json.encodeToString(LedgerMission.serializer(), it)) }),
            MissionSourceInfo.serializer(), timeoutMs = 30_000,
        ).alsoRefreshAll()

    /** Undo of the cartridge keys the switch at [at] cleared (`SwitchReset.at`); the summary comes back updated. */
    suspend fun undoSwitchReset(at: Long): PcAnswer<MissionSourceInfo> =
        answer("POST", "/api/mission/source/undo?at=$at", MissionSourceInfo.serializer(), timeoutMs = 15_000).alsoRefreshAll()

    /**
     * **Populate from Planner** (WDP mode): the flight the Planner has open ([PopulateSend.ref] and seat) and its attack;
     * the PC takes the cartridge as **saved** — a page with unsaved edits asks first. [PopulateSend.from] is filled in with
     * [platform] when empty. Refused in words ([PcAnswer.error]) in EZBoards mode, with no flight open, or when the save
     * cannot be read; the snapshot on the PC is then as it was. [info] and [mission] follow at once.
     */
    suspend fun populateFromPlanner(send: PopulateSend): PcAnswer<MissionSourceInfo> {
        val body = Repo.json.encodeToString(PopulateSend.serializer(), if (send.from == null) send.copy(from = platform) else send)
        return answer("POST", "/api/mission/populate", MissionSourceInfo.serializer(), body, timeoutMs = 60_000).alsoRefreshAll()
    }

    /** The part of the poll's key that moves with the mode and WDP mode's snapshot. */
    private fun modeKey(i: BridgeInfo): String =
        "${i.mission.mode}-${i.mission.populated?.at ?: 0}-${i.mission.populated?.changed?.joinToString(",").orEmpty()}"

    /** The mode or the snapshot changed: the info and the mission now, rather than at the next poll. */
    private suspend fun <T> PcAnswer<T>.alsoRefreshAll(): PcAnswer<T> {
        if (reached) {
            runCatching { get<BridgeInfo>("/api/info") }.onSuccess { _info.value = it }
            runCatching { get<MissionData>("/api/mission") }.onSuccess { _mission.value = it }
        }
        return this
    }

    /** The kneeboard pages of the theater BMS is set to, and who made each half now. */
    suspend fun kbState(): PcAnswer<KbPrintState> = answer("GET", "/api/kbprint/state", KbPrintState.serializer(), timeoutMs = 20_000)

    /** Where a picture of what one half page holds now is fetched from ([fetchBytes]); [side] `L` or `R`, [width] 0 for the PC's default. */
    fun kbThumbPath(n: Int, side: Char, width: Int = 0) = "/api/kbprint/thumb?n=$n&side=$side" + if (width > 0) "&w=$width" else ""

    /** Prints one page file: each half drawn on this device as a 1024x1536 PNG, or left as it is when null. */
    suspend fun kbPrintFile(send: KbFileSend): PcAnswer<KbFileResult> =
        answer("POST", "/api/kbprint/file", KbFileResult.serializer(), Repo.json.encodeToString(KbFileSend.serializer(), send), timeoutMs = 90_000)

    /** Puts BMS's own shipped page back into page file [n], or into every page when [n] is null. */
    suspend fun kbShipped(n: Int? = null): PcAnswer<List<KbFileResult>> =
        answer("POST", "/api/kbprint/shipped?n=" + (n?.toString() ?: "all"), ListSerializer(KbFileResult.serializer()), timeoutMs = 90_000)
    // ---- files on the BMS PC, for the Planner's file windows (docs/PROTOCOL.md, "Files on the BMS PC") ----

    /** The Planner's folders on the PC that exist there (the BMS folder, User\Config, the campaign folders, WDP's…) and its drives. */
    suspend fun filesPlaces(): PcAnswer<PcPlaces> = answer("GET", "/api/files/places", PcPlaces.serializer(), timeoutMs = 15_000)

    /**
     * One folder of the PC: its folders, and its files of [types] (extensions without the dot, lower case; every file
     * when empty). [path] is a Windows path or a place (`@bms`, `@config`, `@campaign`, `@datacampaign`, `@wdp`,
     * `@documents`, `@desktop`, `@downloads`, optionally followed by `\sub\folder`); a folder that does not exist is
     * answered with the nearest one above it, and [PcFolder.note] says so.
     */
    suspend fun filesList(path: String, types: List<String> = emptyList()): PcAnswer<PcFolder> =
        answer(
            "GET", "/api/files/list" + query("path=" + enc(path), types.takeIf { it.isNotEmpty() }?.let { "ext=" + enc(it.joinToString(",")) }),
            PcFolder.serializer(), timeoutMs = 25_000,
        )

    /** One file of the PC as it is now, or [PcFileEntry.exists] false for a name that is not there yet. */
    suspend fun filesStat(path: String): PcAnswer<PcFileEntry> =
        answer("GET", "/api/files/stat?path=" + enc(path), PcFileEntry.serializer(), timeoutMs = 15_000)

    /** One file's bytes (base64 in [PcFileData.data]); refused in words for a type the Planner does not use. */
    suspend fun filesRead(path: String): PcAnswer<PcFileData> =
        answer("GET", "/api/files/read?path=" + enc(path), PcFileData.serializer(), timeoutMs = 60_000)

    /**
     * Writes one file on the PC ([base64] its new content): through a temporary file beside it and one move, so a
     * failed write leaves the old file as it was. With [overwrite] false a file that is there already is refused (409).
     */
    suspend fun filesWrite(path: String, base64: String, overwrite: Boolean): PcAnswer<PcFileEntry> =
        answer(
            "POST", "/api/files/write?path=" + enc(path) + "&overwrite=" + (if (overwrite) "1" else "0"), PcFileEntry.serializer(),
            Repo.json.encodeToString(PcFileWrite.serializer(), PcFileWrite(base64)), timeoutMs = 60_000,
        )

    /**
     * Makes a folder on the PC, and any folder above it that is missing, as WDP makes its `DataCards\…` and `Files\…`
     * folders before it opens a file window there. A folder that is there already is answered as it is.
     */
    suspend fun filesMkdir(path: String): PcAnswer<PcFileEntry> =
        answer("POST", "/api/files/mkdir?path=" + enc(path), PcFileEntry.serializer(), timeoutMs = 15_000)

    /**
     * A weather file (an `.fmap` or a `.twx`) read for the Planner's Reload WX: a map's cells, or a campaign's weather
     * settings and the map it flies with at [clock] (campaign milliseconds), see [PcWeather]. [save] names the save
     * beside a `.twx` whose own weather it is asked as (the Planner's card): the PC then says when it is another's.
     */
    suspend fun filesWeather(path: String, clock: Long? = null, save: String? = null): PcAnswer<PcWeather> =
        answer(
            "GET", "/api/files/weather?path=" + enc(path) + (clock?.let { "&clock=$it" } ?: "") + (save?.let { "&save=" + enc(it) } ?: ""),
            PcWeather.serializer(), timeoutMs = 30_000,
        )

    /**
     * A picture file on the PC (`.jpg`, `.png`, `.bmp` or `.dds`) as a PNG this device can decode, scaled down to fit
     * [max] pixels a side: Upd Kneeboard's Browse picture… ([PcPicture]). Refused in words for a file that is not
     * there, not a picture, or a picture the PC cannot read.
     */
    suspend fun filesPicture(path: String, max: Int = PcPicture.MAX): PcAnswer<PcPicture> =
        answer("GET", "/api/files/picture?path=" + enc(path) + "&max=$max", PcPicture.serializer(), timeoutMs = 60_000)

    /**
     * The Planner's own folders on the PC ([PcPlannerFolders]): its folder in the BMS install
     * (`User\BMS Companion Planner`, where the DTC's backups, the saved maps and the DataCards go) and the DataCards
     * folder in use, with the default and whether the pilot chose another.
     */
    suspend fun filesPlanner(): PcAnswer<PcPlannerFolders> =
        answer("GET", "/api/files/planner", PcPlannerFolders.serializer(), timeoutMs = 15_000)

    /**
     * Sets the DataCards folder, as WDP's Settings sets its DataCard directory: [path] a full path on the PC (or a
     * place such as `@documents\Cards`), null or blank for the default. Refused in words for a path the PC will not take.
     */
    suspend fun filesSetDataCards(path: String?): PcAnswer<PcPlannerFolders> =
        answer("POST", "/api/files/planner?datacards=" + enc(path?.trim().orEmpty()), PcPlannerFolders.serializer(), timeoutMs = 15_000)

    /** The Planner's settings the PC keeps because it acts on them (`GET /api/planner/settings`; an older PC: 404). */
    suspend fun plannerSettings(): PcAnswer<PlannerPcSettings> =
        answer("GET", "/api/planner/settings", PlannerPcSettings.serializer(), timeoutMs = 8_000)

    /** Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints on or off (`POST /api/planner/settings?cleanOpened=1|0`). */
    suspend fun setCleanOpened(on: Boolean): PcAnswer<PlannerPcSettings> =
        answer("POST", "/api/planner/settings?cleanOpened=" + (if (on) "1" else "0"), PlannerPcSettings.serializer(), timeoutMs = 8_000)


    private suspend fun <T> answer(method: String, path: String, serializer: KSerializer<T>, body: String? = null, timeoutMs: Int = 20_000): PcAnswer<T> =
        withContext(Dispatchers.IO) {
            runCatching { exchange(method, path, timeoutMs, body) }.fold(
                onSuccess = { (status, text) -> PcAnswer.read(status, text) { Repo.json.decodeFromString(serializer, it) } },
                onFailure = { PcAnswer.unreachable("Could not reach the PC: ${reasonOf(it)}") },
            )
        }

    /** One request whose status and body are both kept, whatever the status: the Planner's routes refuse in words. */
    private fun exchange(method: String, path: String, timeoutMs: Int, body: String? = null): Pair<Int, String> {
        val b = base() ?: throw IOException("no BMS PC set")
        val c = URL(b + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs.coerceAtMost(5_000)
            c.readTimeout = timeoutMs
            c.useCaches = false
            if (method == "POST") {
                val bytes = body?.encodeToByteArray() ?: ByteArray(0)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.use { it.readBytes().decodeToString() } ?: "")
        } catch (e: IOException) {
            c.disconnect()
            throw e
        }
    }

    /** "?a=1&b=2" from the parts that are there, or nothing. */
    private fun query(vararg parts: String?): String = parts.filterNotNull().joinToString("&").let { if (it.isEmpty()) "" else "?$it" }

    /** The one-line answer a POST route gives back: {"message":"..."}. */
    private fun messageOf(text: String): String? =
        Regex("\"message\"\\s*:\\s*\"(.*?)\"").find(text)?.groupValues?.get(1)

    /**
     * Presses one multi-function display button in the cockpit: side `L` or `R`, button 1-20.
     *
     * The press is injected on the BMS PC and only while Falcon BMS is the window in front, so the answer is
     * worth showing: a tap that did nothing because BMS was behind a browser has to say so, or a pilot will go
     * on tapping a dead button.
     */
    suspend fun pressOsb(side: Char, n: Int): String = withContext(Dispatchers.IO) {
        runCatching {
            val text = request("POST", "/api/mfd/osb?side=$side&n=$n", timeoutMs = 4_000)
            messageOf(text) ?: "Pressed."
        }.getOrElse { "Could not reach the PC: ${reasonOf(it)}" }
    }
    /** Whether Falcon BMS is exporting the cockpit displays, and which ones this cockpit has. */
    suspend fun rttState(): RttState? = runCatching { get<RttState>("/api/rtt") }.getOrNull()

    /** What the MFD rockers are bound to in the pilot's key file; null from a PC that does not say. */
    suspend fun mfdBindings(): MfdBindings? = runCatching { get<MfdBindings>("/api/mfd/keys") }.getOrNull()

    /** Switches the display export on in Falcon BMS's own config, through the backed-up Config path. */
    suspend fun setRtt(on: Boolean): CfgState? = postState("/api/rtt/enable?on=${if (on) 1 else 0}")

    /** Where one display's current picture is fetched from. [width] 0 leaves it at the size BMS exports. */
    fun rttImagePath(display: String, width: Int = 0) = "/api/rtt/img?d=$display" + if (width > 0) "&w=$width" else ""

    /**
     * One display's next picture, or `RttFrame(since, null)` when it has not changed since [since] (see
     * [rttFrameOverHttp]). Null when the PC has no picture to give.
     */
    suspend fun rttFrame(display: String, width: Int, since: Long): RttFrame? =
        rttFrameOverHttp(display, width, since) { fetchBytes(it, timeoutMs = 4000) }

    /**
     * Rocks one of an MFD's corner switches: [which] is `brt` or `gain`, [up] which half. SYM and CON have no key
     * in Falcon BMS (the Dash-34 lists them as not implemented), so the card draws them and never sends them.
     */
    suspend fun pressRocker(side: Char, which: String, up: Boolean): String = withContext(Dispatchers.IO) {
        runCatching {
            val text = request("POST", "/api/mfd/rocker?side=$side&which=$which&dir=${if (up) "up" else "down"}", timeoutMs = 4_000)
            messageOf(text) ?: "Pressed."
        }.getOrElse { "Could not reach the PC: ${reasonOf(it)}" }
    }

    suspend fun cfgState(): CfgState? = runCatching { get<CfgState>("/api/cfg") }.getOrNull()

    /** Every line a profile actually holds. These are the settings the page lists first. */
    suspend fun cfgLines(kind: String, profile: Int): CfgFile? =
        runCatching { get<CfgFile>("/api/cfg/lines?kind=$kind&profile=$profile") }.getOrNull()

    /** Takes the copy that unlocks the page, and lays down the three profiles. */
    suspend fun cfgBackUp(): CfgState? = postState("/api/cfg/backup")

    /** Makes a profile the one BMS reads. */
    suspend fun cfgSelect(kind: String, profile: Int): CfgState? = postState("/api/cfg/select?kind=$kind&profile=$profile")

    /** Sets one setting, or clears it back to BMS's default with a null [value]. */
    suspend fun cfgSet(kind: String, profile: Int, key: String, value: String?): CfgFile? =
        postFile("/api/cfg/set?kind=$kind&profile=$profile&key=$key", value)

    /** Copies one profile's settings onto another, as a starting point rather than a blank sheet. */
    suspend fun cfgCopy(kind: String, from: Int, to: Int): CfgFile? = postFile("/api/cfg/copy?kind=$kind&from=$from&to=$to")

    /** Puts a profile back to the file that was there before any of this. */
    suspend fun cfgRestore(kind: String, profile: Int): CfgFile? = postFile("/api/cfg/restore?kind=$kind&profile=$profile")

    private suspend fun postState(path: String): CfgState? = withContext(Dispatchers.IO) {
        runCatching { Repo.json.decodeFromString<CfgState>(request("POST", path, timeoutMs = 10_000)) }.getOrNull()
    }

    /** A weather call: the state comes back whatever happens, so the page always has something to show. */
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private suspend fun postWeatherMap(path: String, grid: WxMap): WeatherState? = withContext(Dispatchers.IO) {
        val body = Repo.json.encodeToString(WxMap.serializer(), grid)
        runCatching { Repo.json.decodeFromString<WeatherState>(request("POST", path, timeoutMs = 20_000, body = body)) }.getOrNull()
    }

    private suspend fun postWeatherParams(path: String, p: com.bmscompanion.app.data.weather.WxGenParams): WeatherState? = withContext(Dispatchers.IO) {
        val body = Repo.json.encodeToString(com.bmscompanion.app.data.weather.WxGenParams.serializer(), p)
        runCatching { Repo.json.decodeFromString<WeatherState>(request("POST", path, timeoutMs = 120_000, body = body)) }.getOrNull()
    }

    private suspend fun postWeather(path: String, wx: Wx?): WeatherState? = withContext(Dispatchers.IO) {
        val body = wx?.let { Repo.json.encodeToString(Wx.serializer(), it) }
        runCatching { Repo.json.decodeFromString<WeatherState>(request("POST", path, timeoutMs = 15_000, body = body)) }.getOrNull()
    }

    private suspend fun postFile(path: String, body: String? = null): CfgFile? = withContext(Dispatchers.IO) {
        runCatching { Repo.json.decodeFromString<CfgFile>(request("POST", path, timeoutMs = 10_000, body = body)) }.getOrNull()
    }
    // ---------- screenshots on the BMS PC ----------

    suspend fun mediaList(): MediaList? = withContext(Dispatchers.IO) { runCatching { get<MediaList>("/api/media") }.getOrNull() }

    /** Raw bytes of a bridge resource (screenshot thumbnail, preview or original), or null. */
    suspend fun fetchBytes(path: String, timeoutMs: Int = 15_000): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val b = base() ?: throw IOException("no BMS PC set")
            val c = URL(b + path).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 2500; c.readTimeout = timeoutMs
                if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }.getOrNull()
    }

    /** Moves screenshots on the BMS PC to its Recycle Bin. Returns how many were deleted, or null when the bridge is unreachable. */
    suspend fun deleteMedia(names: List<String>): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val b = base() ?: throw IOException("no BMS PC set")
            val c = URL("$b/api/media/delete").openConnection() as HttpURLConnection
            try {
                val body = Repo.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(String.serializer()), names).toByteArray()
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 2500; c.readTimeout = 15_000
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
                val text = c.inputStream.use { it.readBytes().decodeToString() }
                Regex("\"deleted\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
            } finally { c.disconnect() }
        }.getOrNull()
    }

    fun mediaPath(kind: String, name: String, max: Int? = null) =
        "/api/media/$kind?name=" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20") + (max?.let { "&max=$it" } ?: "")

    /** One-off connection test used by the setup screen. */
    suspend fun probe(host: String, port: Int): Result<BridgeInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("http://$host:$port/api/info").openConnection() as HttpURLConnection
            c.connectTimeout = 2500; c.readTimeout = 2500
            try { Repo.json.decodeFromString<BridgeInfo>(c.inputStream.use { it.readBytes().decodeToString() }) } finally { c.disconnect() }
        }
    }

    /** Broadcasts a discovery probe on the local network and collects bridge replies for [timeoutMs]. */
    suspend fun discover(context: Context, timeoutMs: Int = 1800): List<FoundBridge> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("bms-companion-discovery")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val found = LinkedHashMap<String, FoundBridge>()
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 300
                val probe = "BMSC_DISCOVER".toByteArray()
                val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                val end = System.currentTimeMillis() + timeoutMs
                var nextSend = 0L
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() >= nextSend) {
                        targets.forEach { t -> runCatching { sock.send(DatagramPacket(probe, probe.size, t, 47475)) } }
                        nextSend = System.currentTimeMillis() + 600
                    }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val reply = runCatching { Repo.json.decodeFromString<DiscoveryReply>(String(p.data, 0, p.length)) }.getOrNull()
                        if (reply?.service == "bms-companion") {
                            val h = p.address.hostAddress ?: continue
                            found[h] = FoundBridge(h, reply.port, reply.name, reply.version)
                        }
                    } catch (_: SocketTimeoutException) { }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { lock?.release() }
        }
        found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }.filter { it.address is Inet4Address }.mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())
}
