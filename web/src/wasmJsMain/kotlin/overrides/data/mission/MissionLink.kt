package com.bmscompanion.app.data.mission

import com.bmscompanion.app.data.Repo
import com.bmscompanion.web.HttpException
import com.bmscompanion.web.encodeUriComponent
import com.bmscompanion.web.httpBytes
import com.bmscompanion.web.httpText
import com.bmscompanion.web.nowMillis
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString

/** Connection state shown in the Mission header. */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data class Online(val host: String) : LinkState
    data class Offline(val host: String, val reason: String) : LinkState
}

data class FoundBridge(val host: String, val port: Int, val name: String, val version: String)

/**
 * Browser version of the Android MissionLink (same API and polling). The page always talks to the PC program that served
 * it: `/api/...` is answered from Falcon BMS on that PC, or forwarded to the BMS PC when that PC is a client.
 */
object MissionLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
    private val _ez = MutableStateFlow(EzUi())
    val ez: StateFlow<EzUi> = _ez

    data class EzUi(val running: Boolean = false, val result: EzRun? = null)

    /** The PC this page came from. */
    val host: String? get() = window.location.hostname.ifEmpty { "PC" }
    val port: Int get() = window.location.port.toIntOrNull() ?: 80
    /** API paths are served by the same PC program as the page. */
    private const val BASE = ""

    fun setBridge(host: String, port: Int) { restart() }
    fun forget() { stop(); _state.value = LinkState.Idle }

    private var fastUsers = 0

    /** The AWACS page wants contacts twice a second instead of once. */
    fun fastContacts(on: Boolean) { fastUsers = (fastUsers + if (on) 1 else -1).coerceAtLeast(0) }

    fun acquire() { users++; if (job == null) restart() }
    fun release() { users = (users - 1).coerceAtLeast(0); if (users == 0) stop() }

    private fun stop() { job?.cancel(); job = null }

    private fun restart() {
        job?.cancel()
        job = null
        if (users == 0) return
        job = scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        val h = host ?: "PC"
        if (_info.value == null) _state.value = LinkState.Connecting
        var tick = 0L
        var failures = 0
        var missionVersion = ""
        var bmsVersion = ""
        while (scope.isActive) {
            val info = runCatching { get<BridgeInfo>("/api/info") }
            if (info.isFailure) {
                failures++
                if (failures >= 2 || _state.value is LinkState.Connecting) _state.value = LinkState.Offline(h, reasonOf(info.exceptionOrNull()))
                delay(if (failures < 5) 1500 else 3000)
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
            // ~4 Hz live data, 1 Hz contacts (2 Hz on the AWACS page), info every ~2 s (the info call above doubles as the heartbeat)
            repeat(8) {
                runCatching { get<Live>("/api/live") }.onSuccess { _live.value = it }
                if (tick % (if (fastUsers > 0) 2 else 4) == 0L) runCatching { get<Contacts>("/api/contacts") }.onSuccess { _contacts.value = HostileContacts.filter(it, HostileContacts.on(_info.value)) }
                tick++
                delay(250)
            }
        }
    }

    private fun reasonOf(e: Throwable?): String = when {
        e is HttpException && e.status == -1 -> "can't reach the PC: is BMS Companion still running there?"
        e is HttpException && e.status == -2 -> "the PC is not answering (timed out)"
        e is HttpException && e.status == 502 -> e.message ?: "the PC has no mission data source"
        else -> e?.message ?: "error"
    }

    private suspend inline fun <reified T> get(path: String): T =
        Repo.json.decodeFromString<T>(httpText(BASE + path, timeoutMs = 3000))

    /**
     * What each VR board shows. Read by the boards themselves and by the VR board page on the PC; written only by
     * that page. Null means the PC could not be reached — an empty configuration is a real answer, not a failure.
     */
    /**
     * Opens the kneeboard exporter on the BMS PC and answers with what it said.
     *
     * A shortcut, not an export: html_brief has no headless mode, so this puts its window up where the pilot can
     * press export. From a tablet it is the same window, on the PC across the room.
     */
    suspend fun openKneeboardExporter(): String {
        val said = Regex("\"message\"\\s*:\\s*\"(.*?)\"")
        return runCatching {
            val text = httpText("$BASE/api/kneeboard/open", "POST", timeoutMs = 10_000)
            said.find(text)?.groupValues?.get(1)?.replace("\\\\", "\\")
                ?: "The exporter was asked to open on the BMS PC."
        }.getOrElse { e ->
            // a refusal (WDP mode: 409) carries its sentence in the body
            (e as? HttpException)?.body?.let { said.find(it)?.groupValues?.get(1) } ?: "Could not reach the PC."
        }
    }

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
            TaxiSelection(airportId, runway, outbound, spot, nowMillis().toLong()),
        )
        scope.launch { runCatching { httpText("$BASE/api/taxi", "POST", body, 4000) } }
    }

    /** The attack the Planner last worked out, as the PC holds it for the VR boards; null when it cannot be reached. */
    suspend fun attack(): AttackOverlay? = runCatching { get<AttackOverlay>("/api/attack") }.getOrNull()

    /**
     * The Planner's attack, to the PC, where a VR board asks for it — the board is a browser tab of its own and cannot
     * see the Planner. Answers whether the PC took it, so a caller that found it unreachable can try again later.
     */
    suspend fun publishAttack(attack: AttackOverlay): Boolean {
        val body = Repo.json.encodeToString(AttackOverlay.serializer(), attack)
        return runCatching { httpText("$BASE/api/attack", "POST", body, 4000) }.isSuccess
    }

    suspend fun boards(): BoardConfig? = runCatching { get<BoardConfig>("/api/boards") }.getOrNull()

    /** Saves it, and answers with what the PC stored. */
    suspend fun saveBoards(config: BoardConfig): BoardConfig? = runCatching {
        val body = Repo.json.encodeToString(BoardConfig.serializer(), config)
        Repo.json.decodeFromString<BoardConfig>(httpText("$BASE/api/boards", "POST", body, 8000))
    }.getOrNull()

    /** Asks the bridge to run EZBoards; the result also refreshes the board data. */
    /**
     * Turn "generate the kneeboards when the briefing is printed" on or off.
     *
     * The setting lives on the BMS PC, but the page that explains it is read on a tablet as often as on the PC, so
     * it can be set from either.
     */
    fun setBoardsOnPrint(on: Boolean) {
        scope.launch {
            runCatching { httpText("$BASE/api/ezboards/auto?on=" + (if (on) "1" else "0"), "POST", timeoutMs = 8_000) }
            runCatching { get<BridgeInfo>("/api/info") }.onSuccess { _info.value = it }
        }
    }

    /** Hostile contacts from the live feed on or off, on the PC for every device ([HostileContacts]). */
    fun setHostiles(on: Boolean) {
        scope.launch {
            runCatching { httpText("$BASE/api/contacts/hostiles?on=" + (if (on) "1" else "0"), "POST", timeoutMs = 8_000) }
            runCatching { get<BridgeInfo>("/api/info") }.onSuccess { _info.value = it }
            _contacts.value?.let { c -> _contacts.value = HostileContacts.filter(c, HostileContacts.on(_info.value)) }
        }
    }

    fun generateBoards() {
        if (_ez.value.running) return
        _ez.value = EzUi(running = true, result = _ez.value.result)
        scope.launch {
            val r = runCatching {
                Repo.json.decodeFromString<EzRun>(httpText("$BASE/api/ezboards/generate", "POST", timeoutMs = 95_000))
            }.getOrElse { e ->
                // a failed run answers 409 with the result as its body
                (e as? HttpException)?.body?.let { runCatching { Repo.json.decodeFromString<EzRun>(it) }.getOrNull() }
                    ?: EzRun(time = nowMillis().toLong(), ok = false, message = "Could not reach the BMS PC: ${reasonOf(e)}")
            }
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

    /** The Planner here opened [mission]: in WDP mode another flight is a new mission (`POST /api/mission/opened`). */
    suspend fun missionOpened(mission: LedgerMission): PcAnswer<MissionSourceInfo> =
        answer(
            "POST", "/api/mission/opened?for=" + enc(Repo.json.encodeToString(LedgerMission.serializer(), mission)),
            MissionSourceInfo.serializer(), timeoutMs = 30_000,
        ).alsoRefreshAll()

    private suspend fun postCartridge(path: String, body: String?): CartridgeState? =
        runCatching { Repo.json.decodeFromString<CartridgeState>(httpText(BASE + path, "POST", body, 15_000)) }.getOrNull()

    // ---- the Planner's integration: campaign files, Upd Kneeboard (docs/PROTOCOL.md) ----
    //
    // (Send to Mission and its /api/plan… calls are gone since 1.3.8: WDP mode's Populate from Planner fills the Mission
    // section instead. The PC still answers those routes for older devices.)
    //
    // Every one of these needs the PC, and each answers a PcAnswer: the value, or the sentence the PC refused with
    // (or why it could not be reached), for the page to show.

    /** Which kind of device this is, as a plan names the one that sent it. Never a name. */
    val platform: String get() = "Browser"

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


    /** The browser's fetch keeps the status and the body of a refusal in [HttpException]; -1 and -2 are "never reached". */
    private suspend fun <T> answer(method: String, path: String, serializer: KSerializer<T>, body: String? = null, timeoutMs: Int = 20_000): PcAnswer<T> =
        try {
            PcAnswer.read(200, httpText(BASE + path, method, body, timeoutMs)) { Repo.json.decodeFromString(serializer, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.status <= 0) PcAnswer.unreachable("Could not reach the BMS PC: " + reasonOf(e))
            else PcAnswer.read(e.status, e.body) { Repo.json.decodeFromString(serializer, it) }
        } catch (e: Throwable) {
            PcAnswer.unreachable("Could not reach the BMS PC: " + reasonOf(e))
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
    suspend fun pressOsb(side: Char, n: Int): String = runCatching {
        val text = httpText("$BASE/api/mfd/osb?side=$side&n=$n", "POST", timeoutMs = 4_000)
        messageOf(text) ?: "Pressed."
    }.getOrElse { "Could not reach the PC." }
    /** Whether Falcon BMS is exporting the cockpit displays, and which ones this cockpit has. */
    suspend fun rttState(): RttState? = runCatching { get<RttState>("/api/rtt") }.getOrNull()

    /** What the MFD rockers are bound to in the pilot's key file; null from a PC that does not say. */
    suspend fun mfdBindings(): MfdBindings? = runCatching { get<MfdBindings>("/api/mfd/keys") }.getOrNull()

    /** Switches the display export on in Falcon BMS's own config, through the backed-up Config path. */
    suspend fun setRtt(on: Boolean): CfgState? = postState("/api/rtt/enable?on=" + (if (on) "1" else "0"))

    /** Where one display's current picture is fetched from. [width] 0 leaves it at the size BMS exports. */
    fun rttImagePath(display: String, width: Int = 0) = "/api/rtt/img?d=" + display + (if (width > 0) "&w=" + width else "")

    /** One display's next picture, or `RttFrame(since, null)` when it has not changed (see [rttFrameOverHttp]). */
    suspend fun rttFrame(display: String, width: Int, since: Long): RttFrame? =
        rttFrameOverHttp(display, width, since) { fetchBytes(it, timeoutMs = 4000) }

    /** Rocks one of an MFD's corner switches (`brt` or `gain`); SYM and CON have no key in Falcon BMS. */
    suspend fun pressRocker(side: Char, which: String, up: Boolean): String = runCatching {
        val text = httpText("$BASE/api/mfd/rocker?side=$side&which=$which&dir=" + (if (up) "up" else "down"), "POST", timeoutMs = 4_000)
        messageOf(text) ?: "Pressed."
    }.getOrElse { "Could not reach the PC." }

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

    private suspend fun postState(path: String): CfgState? =
        runCatching { Repo.json.decodeFromString<CfgState>(httpText(BASE + path, "POST", timeoutMs = 10_000)) }.getOrNull()

    /** A weather call: the state comes back whatever happens, so the page always has something to show. */
    private fun enc(s: String) = encodeUriComponent(s)

    private suspend fun postWeatherMap(path: String, grid: WxMap): WeatherState? {
        val body = Repo.json.encodeToString(WxMap.serializer(), grid)
        return runCatching { Repo.json.decodeFromString<WeatherState>(httpText(BASE + path, "POST", body, 20_000)) }.getOrNull()
    }

    private suspend fun postWeatherParams(path: String, p: com.bmscompanion.app.data.weather.WxGenParams): WeatherState? {
        val body = Repo.json.encodeToString(com.bmscompanion.app.data.weather.WxGenParams.serializer(), p)
        return runCatching { Repo.json.decodeFromString<WeatherState>(httpText(BASE + path, "POST", body, 120_000)) }.getOrNull()
    }

    private suspend fun postWeather(path: String, wx: Wx?): WeatherState? {
        val body = wx?.let { Repo.json.encodeToString(Wx.serializer(), it) }
        return runCatching { Repo.json.decodeFromString<WeatherState>(httpText(BASE + path, "POST", body, 15_000)) }.getOrNull()
    }

    private suspend fun postFile(path: String, body: String? = null): CfgFile? =
        runCatching { Repo.json.decodeFromString<CfgFile>(httpText(BASE + path, "POST", body, 10_000)) }.getOrNull()
    // ---------- screenshots on the BMS PC ----------

    suspend fun mediaList(): MediaList? = runCatching { get<MediaList>("/api/media") }.getOrNull()

    /** Raw bytes of a bridge resource (screenshot thumbnail, preview or original), or null. */
    suspend fun fetchBytes(path: String, timeoutMs: Int = 15_000): ByteArray? = httpBytes(BASE + path, timeoutMs)

    /** Moves screenshots on the BMS PC to its Recycle Bin. Returns how many were deleted, or null when the bridge is unreachable. */
    suspend fun deleteMedia(names: List<String>): Int? = runCatching {
        val body = Repo.json.encodeToString(ListSerializer(String.serializer()), names)
        val text = httpText("$BASE/api/media/delete", "POST", body, 15_000)
        Regex("\"deleted\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
    }.getOrNull()

    fun mediaPath(kind: String, name: String, max: Int? = null) =
        "/api/media/$kind?name=" + encodeUriComponent(name) + (max?.let { "&max=$it" } ?: "")

    /** Same-origin URL of an original screenshot, for downloads and sharing. */
    fun mediaFileUrl(name: String) = BASE + mediaPath("file", name)

    suspend fun probe(host: String, port: Int): Result<BridgeInfo> = runCatching { get<BridgeInfo>("/api/info") }

    /** Browsers can't search the network; the PC that served the page is the bridge. */
    suspend fun discover(timeoutMs: Int = 1800): List<FoundBridge> = emptyList()
}
