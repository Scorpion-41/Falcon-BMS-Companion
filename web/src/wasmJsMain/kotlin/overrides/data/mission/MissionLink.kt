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
import kotlinx.serialization.builtins.ListSerializer
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
            val want = "${i.briefing.modified}-${i.briefing.dtcModified}-${i.ezBoards.lastRun?.time ?: 0}"
            if (want != missionVersion || _mission.value == null) {
                runCatching { get<MissionData>("/api/mission") }.onSuccess { _mission.value = it; missionVersion = want }
            }
            // ~4 Hz live data, 1 Hz contacts (2 Hz on the AWACS page), info every ~2 s (the info call above doubles as the heartbeat)
            repeat(8) {
                runCatching { get<Live>("/api/live") }.onSuccess { _live.value = it }
                if (tick % (if (fastUsers > 0) 2 else 4) == 0L) runCatching { get<Contacts>("/api/contacts") }.onSuccess { _contacts.value = it }
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
    suspend fun openKneeboardExporter(): String = runCatching {
        val text = httpText("$BASE/api/kneeboard/open", "POST", timeoutMs = 10_000)
        Regex("\"message\"\\s*:\\s*\"(.*?)\"").find(text)?.groupValues?.get(1)?.replace("\\\\", "\\")
            ?: "The exporter was asked to open on the BMS PC."
    }.getOrElse { "Could not reach the PC." }

    suspend fun taxiSelection(): TaxiSelection? = runCatching { get<TaxiSelection>("/api/taxi") }.getOrNull()

    /**
     * What the Taxi page is showing, so a VR board can show the same field, runway and spot.
     *
     * The page and the board are different programs, so the choice is kept on the PC and the board asks for it.
     * Nothing is sent unless it has actually changed — the page publishes on every recomposition otherwise.
     */
    private var lastTaxi: String? = null

    fun publishTaxi(airportId: Int, runway: String, outbound: Boolean, spot: Int?) {
        val key = "$airportId/$runway/$outbound/$spot"
        if (key == lastTaxi) return
        lastTaxi = key
        val body = Repo.json.encodeToString(
            TaxiSelection.serializer(),
            TaxiSelection(airportId, runway, outbound, spot, nowMillis().toLong()),
        )
        scope.launch { runCatching { httpText("$BASE/api/taxi", "POST", body, 4000) } }
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
