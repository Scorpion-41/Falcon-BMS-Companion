package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.BmsStatus
import com.bmscompanion.app.data.mission.Board
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.SupportTrack
import com.bmscompanion.app.data.mission.TrackPoint
import com.bmscompanion.app.data.mission.BriefingStatus
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.EzRun
import com.bmscompanion.app.data.mission.EzStatus
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.TacviewStatus
import com.bmscompanion.desktop.AppInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/** One API call, from the network (Android app, browsers, client PCs) or from this PC's own app window. */
class ApiRequest(val method: String, val path: String, val query: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0), val remote: String = "127.0.0.1")

class ApiResponse(val status: Int, val contentType: String, val body: ByteArray) {
    companion object {
        fun json(text: String, status: Int = 200) = ApiResponse(status, "application/json; charset=utf-8", text.toByteArray(Charsets.UTF_8))
        fun notFound() = json("""{"error":"not found"}""", 404)
    }
}

/**
 * Reads Falcon BMS on this PC (shared memory, briefing, DTC, Tacview stream, EZBoards, screenshots) and answers the
 * mission API documented in docs/PROTOCOL.md. Runs inside BMS Companion whenever Falcon BMS is on this PC.
 */
object Bridge {
    val install = BmsInstall()

    /**
     * The planned tanker and AWACS tracks of the mission being flown, or nothing when they cannot be had.
     *
     * The routes come out of the file BMS is flying ([PlannedRoutes]), and they are only believed when that file
     * also contains your own flight plan — which is what keeps a save from another campaign, another theater or
     * last week's training mission off the map. Ground units and the other hundreds of flights are left alone:
     * what is wanted here is the two a pilot has to find.
     */
    fun supportTracks(dtc: Dtc?): List<SupportTrack> {
        // Your flight plan, which is what proves the file on disk is the mission being flown. In order:
        // - BMS's own mission file beside the save, once it is proved to be the briefed flight's route
        //   ([MissionDtcFile], R3-PLAN A11). In 4.38.1 this is the only place the route is before 3D: the pilot's
        //   cartridge keeps its route slots empty, so without it the tracks only appeared once airborne;
        //   when that file is not believed, the printed flight's own flight plan in the save, once the save provably
        //   holds the printed flight (callsign, package and flight number, the table's words and times;
        //   [MissionDtcFile.fromSave]) — the save then holds your route by construction, as WDP mode's snapshot does;
        // - the route in the DTC you saved (STPT 1-24 that are not precision targets: a recon target bank is not a
        //   route, and taking it for one picked whichever strike flight passed those targets, with its times);
        // - in 3D, your steerpoints in shared memory, so a pilot who never presses SAVE still gets the tracks.
        fun List<com.bmscompanion.app.data.mission.DtcPoint>.places() = filter { it.x != 0.0 || it.y != 0.0 }.map { it.x to it.y }
        val own = runCatching { MissionDtcFile.shown() }.getOrNull()?.let { DtcRoute.points(Dtc(steerpoints = it.steerpoints)).places() }.orEmpty()
            .ifEmpty { dtc?.let { DtcRoute.points(it).places() }.orEmpty() }
            .ifEmpty {
                snapshot().live.navPoints.filter { it.type == "WP" && (it.x != 0.0 || it.y != 0.0) }.map { it.x to it.y }
            }
        if (own.isEmpty()) return emptyList()
        val routes = PlannedRoutes.current(install, install.theater, own)
        if (routes.isEmpty()) return emptyList()
        // the tanker and the AWACS your flight was given, as the sim itself names them; before 3D, the package's own
        // from the save that provably holds the printed flight (MissionGrounds), as the Planner's Map page has them
        val voice = snapshot().live.voice
        val assigned = listOfNotNull(voice?.tanker, voice?.awacs).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .ifEmpty { runCatching { MissionGrounds.assigned() }.getOrDefault(emptyList()) }
        return tracksFrom(routes, own, assigned)
    }

    /**
     * The tanker and AWACS tracks among [routes] (every flight of one save) that overlap your own flight's time: the
     * one of [routes] that flies [own] (your route, theater feet) gives the window, and a track is "yours" when its
     * callsign is one of [assigned] (lower case). Shared by the printed mission ([supportTracks]) and WDP mode's
     * snapshot ([MissionSource]), which gives the package's own tanker and AWACS as assigned.
     */
    internal fun tracksFrom(routes: List<PlannedRoute>, own: List<Pair<Double, Double>>, assigned: List<String>): List<SupportTrack> {
        if (routes.isEmpty() || own.isEmpty()) return emptyList()
        // Your own flight is in there too — it is what let the file be trusted in the first place — and its first
        // and last times are the window you are airborne for. A tanker that lands before you push, or takes off
        // after you are home, is not your tanker and is not drawn.
        val mine = routes.maxByOrNull { r -> own.count { (x, y) -> r.points.any { hypot(it.x - x, it.y - y) < 1.5 * 6076.12 } } }
        // Only points the campaign has put a time on. A route ends with an untimed point (an alternate landing, at
        // 00:00), and counting it opened your window at midnight — every support flight of the day then "overlapped".
        val timed = mine?.points?.filter { it.arriveMs > 0 }.orEmpty()
        val from = timed.minOfOrNull { it.arriveMs } ?: 0L
        val to = timed.maxOfOrNull { maxOf(it.arriveMs, it.departMs) } ?: Long.MAX_VALUE

        return routes.mapNotNull { route ->
            val name = route.missionName.orEmpty()
            val role = when {
                name.contains("REFUEL", true) -> "Tanker"
                name.contains("AWACS", true) || name.contains("AEW", true) || name.contains("ABCCC", true) -> "AWACS"
                else -> return@mapNotNull null
            }
            // The track itself is the leg the flight spends its time on: of all its points, the one it is planned
            // to sit at longest, and the point it flies in from. A tanker holds there for hours and the two
            // together are the racetrack a pilot looks for; the rest of the route is the transit to and from it.
            val longest = route.points.indices.maxByOrNull { route.points[it].departMs - route.points[it].arriveMs } ?: 0
            val station = route.points.getOrNull(longest)
            val onStationFrom = station?.arriveMs ?: route.points.firstOrNull()?.arriveMs ?: 0L
            val onStationTo = station?.departMs ?: route.points.lastOrNull()?.arriveMs ?: 0L
            // no overlap with your flight, no track
            if (onStationTo < from || onStationFrom > to) return@mapNotNull null
            val legs = setOf(longest, (longest - 1).coerceAtLeast(0))
            SupportTrack(
                role = role,
                mission = route.missionName,
                callsign = route.callsign,
                yours = route.callsign != null && route.callsign.lowercase() in assigned,
                points = route.points.mapIndexed { i, p ->
                    TrackPoint(
                        x = p.x,
                        y = p.y,
                        altFt = p.altFt,
                        station = i in legs && (station?.departMs ?: 0L) > (station?.arriveMs ?: 0L),
                        arriveMs = p.arriveMs,
                        departMs = p.departMs,
                    )
                },
            )
        }
    }


    /** The picture's sides come from the campaign's own alliances — see [TeamRelations] for why the feed cannot say. */
    val tacview = TacviewClient().apply {
        relation = { from, toward -> TeamRelations.stance(TeamRelations.current(install, install.theater), from, toward) }
    }
    val ez = EzBoardsRunner()
    val shots = ScreenshotStore()
    val acmi = AcmiStore()
    val kneeboard = ExportedKneeboard()
    val cfg = BmsConfig(install)

    private var rttConfigAt = 0L
    private var rttConfigLast: com.bmscompanion.app.data.mission.RttConfig? = null

    /**
     * The display export as BMS's config files have it, read at most every few seconds: the MFD card asks for the
     * state every couple of seconds on every device, and the answer only changes when a pilot edits a file.
     */
    private fun rttConfig(): com.bmscompanion.app.data.mission.RttConfig? = synchronized(this) {
        val now = System.currentTimeMillis()
        if (now - rttConfigAt > 5000) { rttConfigLast = cfg.rttConfig(); rttConfigAt = now }
        rttConfigLast
    }
    val weather = WeatherStore(install)
    val cartridge = CartridgeStore(install)
    private val discovery = Discovery()

    private val _settings = MutableStateFlow(BridgeSettings.load())
    val settings: StateFlow<BridgeSettings> = _settings

    @Volatile var running = false; private set

    /** Changes whenever the status shown on screen may have changed (about once a second while running). */
    private val _ticks = MutableStateFlow(0L)
    val ticks: StateFlow<Long> = _ticks

    val json = Json { encodeDefaults = true; explicitNulls = false }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bridge-tick").apply { isDaemon = true } }
    private var tickTask: ScheduledFuture<*>? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        BridgeLog.info("Reading Falcon BMS on this PC (BMS Companion ${AppInfo.version})")
        apply()
        // the plan the pilot last sent with the Planner's Send to Mission, kept in %APPDATA% (PlanStore): still read
        // for /api/plan, but since the two modes (1.3.8) no longer laid over the Mission section
        runCatching { PlanStore.load() }.onFailure { BridgeLog.warn("The sent plan could not be read back: ${it.message}") }
        // WDP mode's snapshot, kept in %APPDATA% (MissionSource), and which mode the Mission section is in
        runCatching { MissionSource.load() }.onFailure { BridgeLog.warn("WDP mode's snapshot could not be read back: ${it.message}") }
        BridgeLog.info("Mission section: " + if (MissionSource.wdp) "WDP mode (the Planner's snapshot)" else "EZBoards mode (the BMS briefing)")
        // a briefing printed while the program was closed is a new mission too: looked at once, on its own thread
        if (!MissionSource.wdp) Thread({ ModeSwitchReset.printed() }, "new-mission-check").apply { isDaemon = true }.start()
        discovery.start({ PcServerPort.current }, AppInfo.version)
        // BMS's old debug logs to the Recycle Bin, when the pilot turned that on (RadioLog; off by default)
        RadioLog.startup(install)
        tickTask = scheduler.scheduleWithFixedDelay({ tick() }, 0, 1, TimeUnit.SECONDS)
    }

    /**
     * For a developer check that drives [handle] in-process: the settings applied and [handle] answering, without the
     * discovery replies, the once-a-second tick or the Tacview client a pilot's program runs. [stop] undoes it.
     */
    @Synchronized
    fun startForCheck() {
        if (running) return
        running = true
        apply()
        tacview.stop()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        tickTask?.cancel(false)
        tickTask = null
        discovery.stop()
        tacview.stop()
        BridgeLog.info("Stopped reading Falcon BMS on this PC")
        _ticks.value++
    }

    /**
     * Saves and applies changed settings. [reapply] false only saves them: for a setting nothing has to be read again
     * for (the Mission section's mode), where [apply] would forget the briefing and the cartridge until the next read.
     */
    fun update(reapply: Boolean = true, change: (BridgeSettings) -> BridgeSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        next.save()
        BridgeLog.info("Settings changed")
        if (running && reapply) apply() else _ticks.value++
    }

    private fun apply() {
        val s = _settings.value
        install.refresh(s.BmsDirOverride)
        if (s.EzBoardsDir.isNullOrBlank()) install.defaultEzBoardsDir()?.let { auto -> _settings.value = s.copy(EzBoardsDir = auto).also { it.save() } }
        if (s.TacviewEnabled) tacview.start(s.TacviewHost, s.TacviewPort, s.TacviewPassword) else tacview.stop()
        synchronized(filesLock) { briefing = null; dtc = null; briefingMtime = 0; dtcMtime = 0; boardKey = 0; printedPending = false }
        MissionDtcFile.forget()
        _ticks.value++
    }

    // ------------------------------------------------------------------ data

    private var tickCount = 0

    private fun tick() {
        runCatching {
            if (++tickCount % 10 == 0) install.refresh(_settings.value.BmsDirOverride)
            refreshFiles()
            // the radio subtitles in BMS's debug log, while BMS runs (nothing at all without one: RadioLog)
            RadioLog.tick(install, runCatching { snapshot().flying }.getOrDefault(false))
            // Taken from the flag rather than from this call's answer: /api/info, /api/mission, the mission file's
            // check and the plan store all refresh the files too, and whichever came first used to swallow the PRINT
            // (EZBoards then did not run by itself).
            val printed = synchronized(filesLock) { printedPending.also { printedPending = false } }
            // a PRINT of another flight is a new mission in EZBoards mode: what the Planner saved into the cartridge
            // for an earlier flight is cleared by itself (ModeSwitchReset.newMission; never the printed flight's)
            if (printed && !MissionSource.wdp) ModeSwitchReset.printed()
            val s = _settings.value
            if (printed && s.AutoEzBoardsOnPrint && EzBoardsRunner.isValidDir(s.EzBoardsDir)) {
                val refused = DevGuard.refusal(install.baseDir, s.EzBoardsDir)
                // WDP mode: the cockpit kneeboards come from the Planner's Upd Kneeboard; the setting is kept
                if (MissionSource.suspended) BridgeLog.info("Briefing printed - EZBoards not run: WDP mode (suspended until EZBoards mode)")
                else if (refused != null) BridgeLog.warn("Briefing printed - EZBoards not run: $refused")
                else {
                    BridgeLog.info("Briefing printed - running EZBoards automatically")
                    Thread({ ez.generate(s.EzBoardsDir, auto = true) }, "ezboards-auto").apply { isDaemon = true }.start()
                }
            }
            _ticks.value++
        }.onFailure { BridgeLog.warn("Tick: ${it.message}") }
    }

    private val snapLock = Any()
    private var snap = BmsSnapshot()
    private var snapTime = 0L

    fun snapshot(): BmsSnapshot = synchronized(snapLock) {
        if (System.currentTimeMillis() - snapTime > 150) {
            snap = SharedMemoryReader.read()
            snapTime = System.currentTimeMillis()
        }
        snap
    }

    val briefingPath: String?
        get() = install.briefingsDir(snapshot().strings[StringId.BmsBriefingsDirectory])?.let { File(it, "briefing.txt").path }

    /** BMS screenshot folder (setting, shared memory, g_sPicturesDirectory, or User\Pictures). */
    /** Where BMS writes its ACMI recordings: what it reports while running, else User\Acmi in the install. */
    val acmiDir: java.io.File?
        get() = acmi.dir(snapshot().strings[StringId.BmsAcmiDirectory], install)

    val picturesDir: String?
        get() = _settings.value.PicturesDirOverride?.takeIf { it.isNotBlank() }
            ?: install.picturesDir(snapshot().strings[StringId.BmsPictureDirectory])

    val callsignIni: String?
        get() {
            val dir = install.configDir ?: return null
            val cs = install.callsign?.takeIf { it.isNotBlank() } ?: return null
            return File(dir, "$cs.ini").path
        }

    private val filesLock = Any()
    private var briefing: Briefing? = null
    private var briefingMtime = 0L
    private var briefingFile: String? = null
    private var dtc: Dtc? = null
    private var dtcMtime = 0L

    /** The pilot's cartridge as last read (its PPTs are what [KnownSams] lets through besides the briefed sites). */
    internal fun cartridgeNow(): Dtc? = synchronized(filesLock) { dtc }
    private var board: Board? = null
    private var boardKey = 0L
    /** A new briefing was printed and the tick has not acted on it yet (EZBoards on PRINT). Under [filesLock]. */
    private var printedPending = false

    /** The field, runway and spot the Taxi page is on, for the VR boards to follow. */
    @Volatile private var taxiSelection: com.bmscompanion.app.data.mission.TaxiSelection? = null
    /**
     * The mission [taxiSelection] was made for ([missionKeyNow]): a choice of another mission — the last one flown from
     * the same field, or the other mode's — is not served, or the live taxi board took its runway and spot over the jet's.
     */
    @Volatile private var taxiFor: String? = null
    /** when (this PC's clock) [taxiSelection] came: a radio call heard later wins over it on the live taxi board */
    @Volatile private var taxiReceivedAt = 0L

    /** Which mission `/api/mission` serves now, as `MissionData.missionKey` words it on every device. */
    internal fun missionKeyNow(): String =
        if (MissionSource.wdp) "wdp-${runCatching { MissionSource.populated()?.at }.getOrNull() ?: 0}"
        else "ez-" + synchronized(filesLock) { refreshFiles(); briefingMtime }
    /** The attack an older Planner (before 1.3.8) last posted by itself, and when, for the VR boards' map to draw. */
    @Volatile private var attack: com.bmscompanion.app.data.mission.AttackOverlay? = null
    @Volatile private var attackAt = 0L

    /** Forgets what an older Planner posted ([attack]): a switch of mode or a new mission (ModeSwitchReset). */
    internal fun forgetPostedAttack() { attack = null; attackAt = 0L }

    /**
     * The attack the VR boards' map draws (`GET /api/attack`), which follows the Mission section's mode: in WDP mode
     * the attack of the snapshot Populate from Planner took (none before the first Populate, or when it had none); in
     * EZBoards mode the cartridge's own (its `[NAV OFFSETS]` laid out on BMS's route, `AttackDrawing.fromCartridge`),
     * so both modes draw the attack the jet will load the same way — nothing of the Planner is laid over a printed
     * briefing. What an older Planner posts by itself (`POST /api/attack`) is still taken, and never drawn.
     */
    fun boardAttack(): com.bmscompanion.app.data.mission.AttackOverlay? = runCatching {
        if (MissionSource.wdp) MissionSource.attack()
        else {
            val d = synchronized(filesLock) { refreshFiles(); dtc } ?: return@runCatching null
            val route = runCatching { MissionDtcFile.shown()?.steerpoints }.getOrNull().orEmpty()
            val theater = runCatching { Theaters.of(install)?.current(install.theater)?.appId }.getOrNull()
                ?: install.theater?.let(Theaters::slug).orEmpty()
            PlanStore.attackFrom(d, route, theater)
        }
    }.getOrNull()

    /**
     * `GET /api/mission` as Falcon BMS's own files have it — the printed briefing, the cartridge, EZBoards' board, the
     * planned tracks, BMS's believed route: EZBoards mode's data, whichever mode the Mission section is in. The Planner
     * plans from this (`?source=bms`), in WDP mode too. Since the clearing became automatic (1.3.8, [ModeSwitchReset.
     * newMission]) nothing is served as a leftover: `MissionData.leftovers` stays null.
     */

    /** A `for=` query: the mission a device says it is working on, as LedgerMission JSON; null when absent or unreadable. */
    private fun ledgerMission(raw: String?): com.bmscompanion.app.data.mission.LedgerMission? =
        raw?.takeIf { it.isNotBlank() }?.let { s ->
            runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(com.bmscompanion.app.data.mission.LedgerMission.serializer(), s) }.getOrNull()
        }

    /** The mission the Mission section shows now: the populated flight in WDP mode, the printed briefing's otherwise. */
    private fun missionNow(): com.bmscompanion.app.data.mission.LedgerMission? = runCatching {
        if (MissionSource.wdp) com.bmscompanion.app.data.mission.LedgerMission.ofPopulated(MissionSource.populated())
        else com.bmscompanion.app.data.mission.LedgerMission.ofBriefing(currentBriefing(), briefingModified, install.theater)
    }.getOrNull()

    private fun bmsMission(): MissionData {
        refreshFiles()
        val b = boardNow()
        val routeModified = MissionDtcFile.modified
        // the save's ground picture of the printed flight, when the save provably holds it (MissionGrounds)
        val ground = runCatching { MissionGrounds.forBriefing() }.getOrNull()
        return MissionData(
            "$briefingMtime-$dtcMtime-${b?.time ?: 0}" + (if (routeModified != 0L) "-$routeModified" else "") +
                (ground?.let { "-g" + (it.save + it.flight + it.threatsFrom + it.airDefences.size + it.ships.size).hashCode() } ?: ""),
            briefingMtime, briefing, dtc, b, supportTracks(dtc),
            route = runCatching { MissionDtcFile.shown() }.getOrNull(),
            plan = null,
            mode = com.bmscompanion.app.data.mission.MissionMode.EZBOARDS,
            ground = ground,
        )
    }
    private val boardLock = Any()

    init {
        ez.onCompleted = { boardKey = 0; _ticks.value++ }
    }

    /** Reloads briefing.txt and <callsign>.ini when their timestamps change. Returns true when a new briefing was printed. */
    private fun refreshFiles(): Boolean = synchronized(filesLock) {
        var printed = false
        val bp = briefingPath
        val bm = bp?.let(::File)?.takeIf { it.isFile }?.lastModified() ?: 0L
        if (bm != briefingMtime || bp != briefingFile) {
            val firstLoad = briefingMtime == 0L || bp != briefingFile
            briefingFile = bp
            briefingMtime = bm
            briefing = null
            if (bm > 0) {
                runCatching {
                    briefing = BriefingParser.parse(readShared(File(bp!!)))
                    BridgeLog.info("Briefing loaded (${briefing?.generated})")
                    if (!firstLoad) { printed = true; printedPending = true }
                }.onFailure { BridgeLog.warn("Briefing read failed: ${it.message}"); briefingMtime = 0 }
            }
        }
        val ini = callsignIni
        val im = ini?.let(::File)?.takeIf { it.isFile }?.lastModified() ?: 0L
        if (im != dtcMtime) {
            dtcMtime = im
            dtc = null
            if (im > 0) runCatching { dtc = withPptNames(DtcParser.parse(File(ini!!))) }.onFailure { BridgeLog.warn("DTC read failed: ${it.message}"); dtcMtime = 0 }
        }
        printed
    }

    /**
     * The theater's own `Ppt.ini` names on the cartridge's PPTs ("SA-10", "AWACS"), the key kept in `code`, as
     * PlanStore names a plan's: every device then shows the theater's names, and falls back on the app's stock table
     * only where the name is still the code (no `Ppt.ini` found). The file is the campaign folder of the theater BMS
     * is on (its theater definition), then `Data\Campaign`. Read once per change of the cartridge; never throws.
     */
    private fun withPptNames(d: Dtc): Dtc = runCatching {
        if (d.ppts.isEmpty()) return@runCatching d
        val set = Theaters.of(install)
        val table = PptTable.read(set?.let { it.campaignDir(it.current(install.theater)) })?.takeIf { it.isNotEmpty() }
            ?: install.baseDir?.let { PptTable.read(File(File(it, "Data"), "Campaign")) }.orEmpty()
        if (table.isEmpty()) d else d.copy(ppts = d.ppts.map { p -> PptTable.resolve(table, p.code)?.let { p.copy(name = it.name) } ?: p })
    }.getOrDefault(d)

    /** The printed briefing as last read (null when there is none), and its file time. For PlanStore and MissionDtcFile. */
    fun currentBriefing(): Briefing? = synchronized(filesLock) { refreshFiles(); briefing }
    val briefingModified: Long get() = briefingMtime
    /** When the briefing BMS printed was written (its file time), read now; 0 when there is none. */
    fun printedAt(): Long = synchronized(filesLock) { refreshFiles(); if (briefing != null) briefingMtime else 0L }
    /** The pilot's cartridge as last read, and its file time. */
    fun currentDtc(): Dtc? = synchronized(filesLock) { refreshFiles(); dtc }
    val dtcModified: Long get() = dtcMtime

    /** Reads a file BMS may still be writing. */
    private fun readShared(f: File): String {
        repeat(5) { runCatching { return f.readBytes().toString(Charsets.UTF_8).removePrefix("﻿") }; Thread.sleep(150) }
        return f.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")
    }

    private fun boardNow(): Board? {
        val s = _settings.value
        val key = briefingMtime xor (dtcMtime shl 1) xor (s.EzBoardsDir?.hashCode()?.toLong() ?: 0L)
        synchronized(boardLock) {
            if (boardKey == key && board != null) return board
            board = briefingFile?.let { EzBoardsRunner.readBoard(s.EzBoardsDir, it, callsignIni) }
            boardKey = key
            return board
        }
    }

    /** `GET /api/planner/settings`: the Planner's settings the PC keeps because it acts on them. */
    private fun plannerSettings() = com.bmscompanion.app.data.mission.PlannerPcSettings(cleanOpened = _settings.value.CleanOpenedMission)

    /**
     * What `/api/contacts` serves of one snapshot of the feed: a hostile air defence only at a site the mission knows
     * ([KnownSams]), and no other hostile contact unless the pilot turned them on (`ShowHostiles`, [HostileContacts]).
     */
    internal fun contactsFor(c: com.bmscompanion.app.data.mission.Contacts): com.bmscompanion.app.data.mission.Contacts =
        com.bmscompanion.app.data.mission.HostileContacts.filter(KnownSams.filter(c), _settings.value.ShowHostiles)

    /** A made-up feed in place of the Tacview client's, for `--hostiletest` only (HostileTest); null in the pilot's program. */
    @Volatile internal var feedForCheck: (() -> com.bmscompanion.app.data.mission.Contacts)? = null

    fun info(): BridgeInfo {
        val sm = snapshot()
        val s = _settings.value
        return BridgeInfo(
            app = "BMS Companion",
            version = AppInfo.version,
            api = 1,
            host = System.getenv("COMPUTERNAME") ?: "PC",
            bms = BmsStatus(
                installed = install.baseDir != null,
                baseDir = install.baseDir,
                registryVersion = install.registryVersion,
                version = sm.version,
                running = sm.available,
                flying = sm.flying,
                theater = sm.strings[StringId.ThrName] ?: install.theater,
                callsign = install.callsign,
                aircraft = sm.strings[StringId.AcName],
            ),
            tacview = TacviewStatus(
                enabled = s.TacviewEnabled,
                connected = tacview.connected,
                state = tacview.state,
                objects = tacview.objectCount,
                hostiles = s.ShowHostiles,
            ),
            // planModified moves with what /api/mission carries as `plan` — since 1.3.8 WDP mode's snapshot, and a
            // switch of mode — which is what makes every client, an older one too, fetch the mission again
            briefing = BriefingStatus(
                available = briefing != null, modified = briefingMtime, generated = briefing?.generated, dtcModified = dtcMtime,
                planModified = MissionSource.stamp, routeModified = MissionDtcFile.modified,
            ),
            media = shots.info(picturesDir),
            acmi = acmi.info(acmiDir),
            kneeboard = kneeboard.status(s, install, briefingMtime).copy(runSuspended = MissionSource.suspended),
            ezBoards = EzStatus(
                configured = EzBoardsRunner.isValidDir(s.EzBoardsDir), path = s.EzBoardsDir, autoOnPrint = s.AutoEzBoardsOnPrint,
                running = ez.running, lastRun = ez.lastRun, suspended = MissionSource.suspended,
            ),
            mission = MissionSource.info(if (briefing != null) briefingMtime else 0L),
            radio = RadioLog.status(install),
        )
    }

    /**
     * The boards a pilot gets before changing anything: the map, the briefing, the HARM table, and the two sets of
     * charts for wherever this mission takes off from. Five tabs is a sensible OpenKneeboard setup on its own.
     */
    fun defaultBoards() = com.bmscompanion.app.data.mission.BoardConfig(
        slots = listOf(
            com.bmscompanion.app.data.mission.BoardSlot(1, "map"),
            com.bmscompanion.app.data.mission.BoardSlot(2, "briefing"),
            com.bmscompanion.app.data.mission.BoardSlot(3, "harm"),
            com.bmscompanion.app.data.mission.BoardSlot(4, "runways"),
            com.bmscompanion.app.data.mission.BoardSlot(5, "plates"),
        ),
    )

    private fun <T> encode(serializer: KSerializer<T>, value: T) = ApiResponse.json(json.encodeToString(serializer, value))

    /**
     * A developer run's refusal of a route that writes into a BMS folder, or null (see [DevGuard]: off in the pilot's
     * program). Every such route is here: the cartridge (and a TE's mission file), the weather maps, the config files
     * (and the display export switch, which is one), EZBoards, deleting screenshots and recordings, and the kneeboard
     * pages. The answer is 409 with the sentence as `error`, which every state these routes answer carries.
     */
    private fun guardWrite(req: ApiRequest): ApiResponse? {
        if (req.method != "POST" || !DevGuard.on) return null
        val p = req.path.trimEnd('/')
        val targets: List<String?> = when {
            p.startsWith("/api/cartridge/") || p.startsWith("/api/weather/") || p.startsWith("/api/cfg/") ||
                p == "/api/rtt/enable" || p == "/api/kbprint/file" || p == "/api/kbprint/shipped" ||
                p == "/api/mission/source/undo" -> listOf(install.baseDir)
            p == "/api/ezboards/generate" -> listOf(install.baseDir, _settings.value.EzBoardsDir)
            p == "/api/media/delete" -> listOf(install.baseDir, picturesDir)
            p == "/api/acmi/clear" -> listOf(install.baseDir, acmiDir?.path)
            // BMS's old debug logs (RadioLog.cleanup), in its logs folder wherever g_sLogsDirectory puts it
            p == "/api/radio/cleanup/now" -> listOf(install.baseDir, RadioLog.logsDir(install)?.path)
            else -> return null
        }
        val why = DevGuard.refusal(*targets.toTypedArray()) ?: return null
        BridgeLog.warn("${req.method} $p refused: $why")
        val said = json.encodeToString(String.serializer(), why)
        val extra = when (p) {
            "/api/ezboards/generate" -> ""","ok":false,"message":$said"""
            "/api/media/delete" -> ""","deleted":0"""
            "/api/acmi/clear" -> ""","deleted":0,"bytes":0"""
            else -> ""
        }
        return ApiResponse.json("""{"error":$said$extra}""", 409)
    }

    /** The mission API (GET /api/info, /api/live, … ; see docs/PROTOCOL.md). */
    fun handle(req: ApiRequest): ApiResponse {
        if (!running) return ApiResponse.json("""{"error":"Falcon BMS is not read on this PC"}""", 503)
        // a developer check never writes the real install (DevGuard); in the pilot's program this is one boolean
        guardWrite(req)?.let { return it }
        // the Planner's routes (docs/PROTOCOL.md, "Planner integration"), each answered by the file that owns it
        val path = req.path.trimEnd('/')
        when {
            path.startsWith("/api/campaign/") -> return CampaignRoutes.handle(req)
            path == "/api/plan" || path.startsWith("/api/plan/") -> return PlanStore.handle(req)
            path.startsWith("/api/kbprint/") -> return KneeboardPrint.handle(req)
            // EZBoards mode or WDP mode, and WDP mode's Populate from Planner (MissionSource)
            path == "/api/mission/source" || path == "/api/mission/source/undo" || path == "/api/mission/populate" ||
                path == "/api/mission/opened" -> return MissionSource.handle(req)
            // the PC's own disks, for the Planner's file windows on any device (PcFileRoutes)
            path.startsWith("/api/files/") -> return PcFileRoutes.handle(req)
            // the radio subtitles in BMS's debug log, and deleting its old logs (RadioLog)
            path == "/api/radio" || path.startsWith("/api/radio/") -> return RadioLog.handle(req)
        }
        return when (req.method to path) {
            "GET" to "/api/info" -> encode(BridgeInfo.serializer(), info())
            "GET" to "/api/live" ->
                snapshot().let { sm -> encode(Live.serializer(), if (sm.available) sm.live else Live(t = System.currentTimeMillis())) }
            "GET" to "/api/contacts" ->
                snapshot().let { sm ->
                    // a hostile air defence leaves only when it is a site the mission knows (KnownSams): the feed streams them all;
                    // and no other hostile contact leaves at all unless the pilot turned them on (ShowHostiles, HostileContacts)
                    encode(
                        com.bmscompanion.app.data.mission.Contacts.serializer(),
                        contactsFor(feedForCheck?.invoke() ?: tacview.snapshot(if (sm.flying) sm.live.x else null, if (sm.flying) sm.live.y else null)),
                    )
                }
            // Hostile contacts from the live feed on or off, from any device's Setup page (off by default; HostileContacts)
            "POST" to "/api/contacts/hostiles" -> {
                val on = req.query["on"]?.let { it == "1" || it.equals("true", true) }
                    ?: req.body.decodeToString().trim().let { it == "1" || it.equals("true", true) }
                update(reapply = false) { it.copy(ShowHostiles = on) }
                BridgeLog.info(if (on) "Hostile contacts from the live feed are on" else "Hostile contacts from the live feed are off")
                encode(com.bmscompanion.app.data.mission.TacviewStatus.serializer(), info().tacview)
            }
            // The Planner's settings the PC acts on (1.3.9: Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints, ModeSwitchReset), from the
            // Planner's Settings window on any device
            "GET" to "/api/planner/settings" -> encode(com.bmscompanion.app.data.mission.PlannerPcSettings.serializer(), plannerSettings())
            "POST" to "/api/planner/settings" -> {
                val on = req.query["cleanOpened"]?.let { it == "1" || it.equals("true", true) }
                if (on != null) {
                    update(reapply = false) { it.copy(CleanOpenedMission = on) }
                    BridgeLog.info(if (on) "Each opened mission starts with clean lines, PPTs and Open 1/2 steerpoints" else "Lines and PPTs are kept when the Planner opens a mission")
                }
                encode(com.bmscompanion.app.data.mission.PlannerPcSettings.serializer(), plannerSettings())
            }
            // The Mission section's data, in the mode it is in (MissionSource): EZBoards mode is Falcon BMS's own files
            // as they are now; WDP mode is the snapshot Populate from Planner took (or, before one, only the mode).
            // `source=bms` asks for BMS's own files whatever the mode: the Planner plans from them.
            "GET" to "/api/mission" -> encode(
                MissionData.serializer(),
                if (req.query["source"] == "bms" || !MissionSource.wdp) bmsMission() else MissionSource.mission(),
            )
            "POST" to "/api/ezboards/generate" -> {
                // WDP mode: the button is greyed out on every device, and an older one that still presses it is told why
                val r = if (MissionSource.suspended) EzRun(time = System.currentTimeMillis(), ok = false, message = com.bmscompanion.app.data.mission.MissionMode.EZ_SUSPENDED)
                else ez.generate(_settings.value.EzBoardsDir, auto = false)
                ApiResponse.json(json.encodeToString(EzRun.serializer(), r), if (r.ok) 200 else 409)
            }
            // What each VR board shows. The boards themselves only read it; the VR board page on the PC writes it.
            // What the Taxi page is showing. A VR board is a browser tab of its own, so it cannot see the app's
            // state; it asks the PC, and the PC remembers whatever the page last published. Nothing is stored on
            // disk: it means nothing once the flight is over.
            "GET" to "/api/taxi" -> encode(
                com.bmscompanion.app.data.mission.TaxiSelection.serializer(),
                (taxiSelection?.takeIf { taxiFor == missionKeyNow() } ?: com.bmscompanion.app.data.mission.TaxiSelection())
                    // the controller's last call to the pilot's flight (BMS's debug log), when it came after the page's choice
                    .copy(radio = RadioLog.taxiNow()?.takeIf { it.at > taxiReceivedAt }),
            )
            /**
             * Turn "generate the kneeboards when the briefing is printed" on or off, from wherever the pilot is.
             *
             * The setting lived only on the PC, and the Kneeboards page — on a tablet, in the browser — told every
             * reader that pressing PRINT generates them by itself. With the setting off that was simply untrue, and
             * a pilot who followed it got nothing and no reason why. Now the page can say which it is and turn it
             * on, without walking back to the PC.
             */
            "POST" to "/api/ezboards/auto" -> {
                val on = req.query["on"]?.let { it == "1" || it.equals("true", true) }
                    ?: req.body.decodeToString().trim().let { it == "1" || it.equals("true", true) }
                update { it.copy(AutoEzBoardsOnPrint = on) }
                encode(EzStatus.serializer(), info().ezBoards)
            }
            "POST" to "/api/taxi" -> {
                val sel = runCatching {
                    json.decodeFromString(com.bmscompanion.app.data.mission.TaxiSelection.serializer(), req.body.decodeToString())
                }.getOrNull()
                if (sel == null) ApiResponse.json("""{"error":"that is not a taxi selection"}""", 400)
                else {
                    taxiSelection = sel.copy(radio = null)
                    taxiFor = missionKeyNow()
                    taxiReceivedAt = System.currentTimeMillis()
                    encode(com.bmscompanion.app.data.mission.TaxiSelection.serializer(), sel)
                }
            }
            // The attack the VR boards' map draws: the one the pilot sent with Send to Mission (boardAttack). POST is
            // what a Planner before 1.3.8 did by itself whenever its figures settled; kept in memory only, for them.
            "GET" to "/api/attack" -> encode(
                com.bmscompanion.app.data.mission.AttackOverlay.serializer(),
                boardAttack() ?: com.bmscompanion.app.data.mission.AttackOverlay(),
            )
            "POST" to "/api/attack" -> {
                val a = runCatching {
                    json.decodeFromString(com.bmscompanion.app.data.mission.AttackOverlay.serializer(), req.body.decodeToString())
                }.getOrNull()
                if (a == null) ApiResponse.json("""{"error":"that is not an attack"}""", 400)
                else {
                    attack = a.takeIf { it.cues.isNotEmpty() }
                    attackAt = System.currentTimeMillis()
                    encode(com.bmscompanion.app.data.mission.AttackOverlay.serializer(), a)
                }
            }
            "GET" to "/api/boards" -> encode(
                com.bmscompanion.app.data.mission.BoardConfig.serializer(),
                _settings.value.Boards ?: defaultBoards(),
            )
            "POST" to "/api/boards" -> {
                val cfg = runCatching {
                    json.decodeFromString(com.bmscompanion.app.data.mission.BoardConfig.serializer(), req.body.decodeToString())
                }.getOrNull()
                if (cfg == null) ApiResponse.json("""{"error":"that is not a board configuration"}""", 400)
                else {
                    update { it.copy(Boards = cfg) }
                    encode(com.bmscompanion.app.data.mission.BoardConfig.serializer(), cfg)
                }
            }
            "GET" to "/api/ezboards/status" -> encode(EzStatus.serializer(), info().ezBoards)
            // The kneeboard html_brief exported, a page at a time: rendered here so a tablet, a browser and a VR
            // board all get an image they can simply show.
            // Opens the exporter's own window on the BMS PC. It has no headless export — its switches are only
            // about ports and the tray — so this is a shortcut to the tool, not a way of driving it.
            // WDP mode: html_brief's export writes cockpit pages 1-3 over the Planner's Upd Kneeboard pages, so it is not
            // started from here (the button is greyed out on every device; an older one that still presses it is told
            // why). The pages it already exported are still served below.
            "POST" to "/api/kneeboard/open" -> if (MissionSource.suspended) {
                val said = json.encodeToString(String.serializer(), com.bmscompanion.app.data.mission.MissionMode.HTML_BRIEF_SUSPENDED)
                BridgeLog.info("HTML Briefing not started: WDP mode")
                ApiResponse.json("""{"error":$said,"message":$said}""", 409)
            } else {
                val root = kneeboard.root(_settings.value, install)
                val message = root?.let { kneeboard.open(it) } ?: "No kneeboard exporter folder is set on the BMS PC."
                ApiResponse.json(json.encodeToString(String.serializer(), message).let { """{"message":$it}""" })
            }
            "GET" to "/api/kneeboard/page" -> {
                val root = kneeboard.root(_settings.value, install)
                val i = req.query["i"]?.toIntOrNull() ?: 0
                val max = (req.query["max"]?.toIntOrNull() ?: 1400).coerceIn(320, 3000)
                val bytes = root?.let { kneeboard.page(it, i, max) }
                if (bytes == null) ApiResponse.notFound() else ApiResponse(200, "image/jpeg", bytes)
            }
            // ---- Falcon BMS's own weather maps (see WeatherStore) ----
            // "map=1" asks for the painted grid as well, which is a few kilobytes and only the Editor wants it
            "GET" to "/api/weather" -> encode(
                com.bmscompanion.app.data.mission.WeatherState.serializer(),
                weather.state(detail = req.query["theater"], withMap = req.query["map"] == "1"),
            )
            "POST" to "/api/weather/backup" -> encode(
                com.bmscompanion.app.data.mission.WeatherState.serializer(),
                weather.backUp(req.query["theater"]),
            )
            // The weather itself comes in the body: a dozen numbers are a JSON object, not a query string.
            "POST" to "/api/weather/set" -> {
                val wx = runCatching {
                    json.decodeFromString(com.bmscompanion.app.data.mission.Wx.serializer(), req.body.decodeToString())
                }.getOrNull()
                encode(
                    com.bmscompanion.app.data.mission.WeatherState.serializer(),
                    if (wx == null) weather.state("The weather sent to the PC could not be read.")
                    else weather.set(req.query["theater"], req.query["model"], wx),
                )
            }
            // A whole painted map: the palette and the cell index, in the body.
            "POST" to "/api/weather/setmap" -> {
                val grid = runCatching {
                    json.decodeFromString(com.bmscompanion.app.data.mission.WxMap.serializer(), req.body.decodeToString())
                }.getOrNull()
                encode(
                    com.bmscompanion.app.data.mission.WeatherState.serializer(),
                    if (grid == null) weather.state("The map sent to the PC could not be read.")
                    else weather.setMap(req.query["theater"], req.query["model"], grid),
                )
            }
            "POST" to "/api/weather/restore" -> encode(
                com.bmscompanion.app.data.mission.WeatherState.serializer(),
                weather.restore(req.query["theater"], req.query["model"]),
            )
            // a ready-made map that differs from its copy although this program did not write it (a BMS update):
            // the copy is taken again, the earlier one kept in the backup's "Older copies"
            "POST" to "/api/weather/refreshbackup" -> encode(
                com.bmscompanion.app.data.mission.WeatherState.serializer(),
                weather.refreshBackup(req.query["theater"], req.query["model"]),
            )
            // Generated weather: the client sends the generator's parameters (a few hundred bytes), never the grid,
            // and the PC runs the same shared model to build the file. Times are DHHMM, as BMS names update maps.
            "POST" to "/api/weather/generate", "POST" to "/api/weather/series" -> {
                val params = runCatching {
                    json.decodeFromString(com.bmscompanion.app.data.weather.WxGenParams.serializer(), req.body.decodeToString())
                }.getOrNull()
                val time = { k: String -> req.query[k]?.let { com.bmscompanion.app.data.weather.CampaignTime.ofFmapName(it) } }
                encode(
                    com.bmscompanion.app.data.mission.WeatherState.serializer(),
                    when {
                        params == null -> weather.state("The weather sent to the PC could not be read.")
                        req.path.trimEnd('/').endsWith("/generate") -> weather.writeGenerated(req.query["theater"], req.query["name"], params)
                        else -> weather.writeSeries(
                            req.query["theater"], req.query["name"], params,
                            time("from"), time("to"), req.query["step"]?.toIntOrNull(),
                        )
                    },
                )
            }
            // what this program wrote outside the four ready-made maps: each theater's `added` and `replaced`
            "GET" to "/api/weather/generated" -> encode(
                com.bmscompanion.app.data.mission.WeatherState.serializer(),
                weather.state(detail = req.query["theater"]),
            )
            "POST" to "/api/weather/remove" -> encode(
                com.bmscompanion.app.data.mission.WeatherState.serializer(),
                weather.removeGenerated(req.query["theater"], req.query["name"]),
            )
            "POST" to "/api/weather/restoreseries" -> encode(
                com.bmscompanion.app.data.mission.WeatherState.serializer(),
                weather.restoreSeries(req.query["theater"]),
            )
            // ---- the pilot's data cartridge, for the Planner's DTC page (see CartridgeStore) ----
            "GET" to "/api/cartridge" -> encode(
                com.bmscompanion.app.data.mission.CartridgeState.serializer(),
                cartridge.state(req.query["callsign"]),
            )
            // Written directly, as WDP writes it (no backup, nothing to switch on). The edits come in the body: the keys
            // the pilot changed, each with its new value (null removes it).
            "POST" to "/api/cartridge/save" -> {
                val edits = runCatching {
                    json.decodeFromString(ListSerializer(com.bmscompanion.app.data.mission.CartridgeEdit.serializer()), req.body.decodeToString())
                }.getOrNull()
                // the save named as well (te=<theater>|<file>, split on the first '|'; a TE, training or, since 1.3.8's
                // fix, a campaign): BMS's DTC window loads targets, lines and PPTs from that save's own mission file, so a
                // save has to reach both (CartridgeStore.saveTe)
                val te = req.query["te"]?.takeIf { it.isNotBlank() }?.let { v ->
                    com.bmscompanion.app.data.mission.CampRef(theater = v.substringBefore('|'), file = v.substringAfter('|', ""))
                }
                // which mission the save is for (`for=`, the LedgerMission as JSON; 1.3.8): kept in the cartridge's ledger
                val forMission = ledgerMission(req.query["for"])
                encode(
                    com.bmscompanion.app.data.mission.CartridgeState.serializer(),
                    when {
                        edits == null -> cartridge.state(req.query["callsign"], "The changes sent to the PC could not be read.")
                        te != null -> cartridge.saveTe(req.query["callsign"], edits, te, forMission)
                        else -> cartridge.save(req.query["callsign"], edits, forMission)
                    },
                )
            }
            // What the Planner saved into the cartridge for another flight than `for=` and is still there: cleared
            // (`do=clear`, each key back to BMS's empty value, the same safe write as Save to DTC) or kept for this
            // mission (`do=keep`). Only keys still holding the value the Planner wrote are touched (CartridgeStore).
            "POST" to "/api/cartridge/leftovers" -> encode(
                com.bmscompanion.app.data.mission.CartridgeState.serializer(),
                cartridge.settleLeftovers(
                    req.query["callsign"],
                    ledgerMission(req.query["for"]) ?: missionNow(),
                    clear = req.query["do"]?.equals("keep", ignoreCase = true) != true,
                ),
            )
            // ---- the cockpit displays themselves (see RttTextures) ----
            // The pilot's side of the answer comes from here: whether they are in the cockpit (the same flags the rest
            // of the app uses) and what BMS's config files say, which is what tells "not in 3D" from "switched off".
            "GET" to "/api/rtt" -> encode(
                com.bmscompanion.app.data.mission.RttState.serializer(),
                RttTextures.state(inCockpit = snapshot().flying, config = rttConfig()),
            )
            // One display as a picture. `since` is the fingerprint of the picture the client already has
            // (`frameHash`), and when that is still the picture the answer is 204 and nothing is sent — an MFD on a
            // page that is not moving costs a request and no image. No caching headers on purpose: this is a moving
            // picture, and the client asks again when it wants the next frame.
            "GET" to "/api/rtt/img" -> {
                val d = RttTextures.Display.of(req.query["d"])
                val w = req.query["w"]?.toIntOrNull()?.coerceIn(64, 2048) ?: 0
                val e = d?.let { RttTextures.encoded(it, w, req.query["since"]?.toLongOrNull()) }
                when {
                    e == null -> ApiResponse.notFound()
                    e === RttTextures.UNCHANGED -> ApiResponse(204, "image/jpeg", ByteArray(0))
                    else -> ApiResponse(200, "image/jpeg", e.bytes)
                }
            }
            // ---- Weapon Delivery Planner, running on this PC and drawn on whatever asked (see WdpRemote) ----
            "GET" to "/api/wdp" -> encode(
                com.bmscompanion.app.data.mission.WdpState.serializer(),
                WdpRemote.state(_settings.value, install),
            )
            "POST" to "/api/wdp/open" -> {
                val root = WdpRemote.root(_settings.value, install)
                val message = root?.let { WdpRemote.open(it, req.query["hidden"] != "0") }
                    ?: "Weapon Delivery Planner has not been found on this PC. Set its folder in Settings."
                ApiResponse.json("""{"message":${json.encodeToString(String.serializer(), message)}}""")
            }
            "POST" to "/api/wdp/show" -> ApiResponse.json(
                """{"message":${json.encodeToString(String.serializer(), WdpRemote.setHidden(req.query["on"] == "0"))}}""",
            )
            "POST" to "/api/wdp/close" -> ApiResponse.json(
                """{"message":${json.encodeToString(String.serializer(), WdpRemote.close())}}""",
            )
            // One frame of its window. Like the display export, this is a moving picture: no caching headers, the
            // client asks again when it wants the next one.
            "GET" to "/api/wdp/frame" -> {
                val w = req.query["w"]?.toIntOrNull()?.coerceIn(320, 3000) ?: 1200
                val img = WdpRemote.frame(w)
                if (img == null) ApiResponse.notFound()
                else ApiResponse(200, "image/jpeg", WdpRemote.jpeg(img, (req.query["q"]?.toFloatOrNull() ?: 0.72f).coerceIn(0.3f, 0.95f)))
            }
            // A tap, drag, wheel turn or key, in the coordinates of the frame the client is showing.
            "POST" to "/api/wdp/input" -> {
                val message = WdpRemote.input(
                    req.query["kind"] ?: "click",
                    req.query["x"]?.toIntOrNull() ?: 0,
                    req.query["y"]?.toIntOrNull() ?: 0,
                    req.query["w"]?.toIntOrNull() ?: 0,
                    req.query["d"]?.toIntOrNull() ?: 0,
                )
                ApiResponse.json("""{"message":${json.encodeToString(String.serializer(), message)}}""")
            }
            // Presses one MFD button in the cockpit, so the displays can be worked from a tablet. Injected at the
            // system level (BMS reads DirectInput, which never sees a posted message) and only while BMS is the
            // window in front, which is what stops a stray Ctrl+Alt+1 landing in somebody's browser.
            "POST" to "/api/mfd/osb" -> {
                val side = req.query["side"]?.firstOrNull() ?: 'L'
                val n = req.query["n"]?.toIntOrNull() ?: 0
                val message = BmsKeys.pressOsb(install, side, n)
                ApiResponse.json("""{"message":${json.encodeToString(String.serializer(), message)}}""")
            }
            // Rocks one of an MFD's corner switches, the same way: BRT and GAIN have keys in Falcon BMS, SYM and CON
            // are not implemented in BMS and are refused with a sentence rather than a silent nothing.
            "POST" to "/api/mfd/rocker" -> {
                val side = req.query["side"]?.firstOrNull() ?: 'L'
                val message = BmsKeys.pressRocker(install, side, req.query["which"].orEmpty(), req.query["dir"] != "down")
                ApiResponse.json("""{"message":${json.encodeToString(String.serializer(), message)}}""")
            }
            // What the twenty buttons of each display are bound to, so the card can say so rather than a tap
            // quietly doing nothing when a pilot has rebound them.
            "GET" to "/api/mfd/keys" -> {
                val keys = listOf('L', 'R').flatMap { s -> (1..20).map { n -> s to n } }
                    .joinToString(",") { (s, n) ->
                        val b = BmsKeys.osb(install, s, n)
                        """{"side":"$s","n":$n,"key":${json.encodeToString(String.serializer(), b?.text ?: "")}}"""
                    }
                // and the eight rocker halves of each, with the callback each works (`MfdRockerKey`): the card dims a
                // half whose callback the key file leaves unbound and names the callback to bind
                val rockers = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(com.bmscompanion.app.data.mission.MfdRockerKey.serializer()),
                    BmsKeys.rockerBindings(install),
                )
                ApiResponse.json(
                    """{"inFront":${BmsKeys.bmsInFront()},""" +
                        // why Windows would drop the presses (BMS elevated, this program not), "" when nothing does
                        """"blocked":${json.encodeToString(String.serializer(), BmsKeys.bmsBlocked().orEmpty())},""" +
                        """"fromKeyFile":${BmsKeys.fromPilotsKeyFile(install)},"keys":[$keys],"rockers":$rockers}""",
                )
            }
            // Switches the export on in Falcon BMS's own config, through the same backed-up path the Config page
            // uses — so it is as reversible as every other setting there, and refuses until the backup exists.
            "POST" to "/api/rtt/enable" -> encode(
                com.bmscompanion.app.data.mission.CfgState.serializer(),
                cfg.enableRtt(req.query["on"] != "0").also { rttConfigAt = 0L },
            )
            // Clearing the recordings is the one thing this program removes from the BMS folder, and only when a
            // pilot asks: the files go to the Recycle Bin, where a flight somebody did want is still recoverable.
            "POST" to "/api/acmi/clear" -> {
                val (gone, bytes) = acmi.clear(acmiDir)
                ApiResponse.json("""{"deleted":$gone,"bytes":$bytes}""")
            }
            // ---- Falcon BMS's own config files ----
            // The one part of this program that writes into the BMS folder, and only after the pilot has pressed
            // the button that takes a copy first. On a PC with no BMS install `available` is false and the page
            // says so.
            "GET" to "/api/cfg" -> encode(com.bmscompanion.app.data.mission.CfgState.serializer(), cfg.state())
            "GET" to "/api/cfg/lines" -> encode(
                com.bmscompanion.app.data.mission.CfgFile.serializer(),
                cfg.read(BmsConfig.Kind.of(req.query["kind"]), req.query["profile"]?.toIntOrNull() ?: 1),
            )
            // BMS's own cartridge defaults (User/Config/*_Def.ini), for the Planner's Default buttons. Read only:
            // nothing on this route opens a file for writing.
            "GET" to "/api/cfg/defaults" -> encode(
                com.bmscompanion.app.data.BmsDefaults.serializer(),
                BmsDefaultsFiles.read(install.configDir?.let(::File)),
            )
            "POST" to "/api/cfg/backup" -> encode(com.bmscompanion.app.data.mission.CfgState.serializer(), cfg.backUp())
            "POST" to "/api/cfg/select" -> encode(
                com.bmscompanion.app.data.mission.CfgState.serializer(),
                cfg.select(BmsConfig.Kind.of(req.query["kind"]), req.query["profile"]?.toIntOrNull() ?: 1),
            )
            // The value comes in the body, not the query: some of these are quoted strings and colours.
            // No body at all means "clear it", which is how a setting goes back to BMS's default.
            "POST" to "/api/cfg/set" -> {
                val key = req.query["key"].orEmpty()
                val value = req.body.decodeToString().takeIf { it.isNotBlank() }
                encode(
                    com.bmscompanion.app.data.mission.CfgFile.serializer(),
                    cfg.set(BmsConfig.Kind.of(req.query["kind"]), req.query["profile"]?.toIntOrNull() ?: 1, key, value),
                )
            }
            "POST" to "/api/cfg/copy" -> encode(
                com.bmscompanion.app.data.mission.CfgFile.serializer(),
                cfg.copy(
                    BmsConfig.Kind.of(req.query["kind"]),
                    req.query["from"]?.toIntOrNull() ?: 1,
                    req.query["to"]?.toIntOrNull() ?: 1,
                ),
            )
            "POST" to "/api/cfg/restore" -> encode(
                com.bmscompanion.app.data.mission.CfgFile.serializer(),
                cfg.restore(BmsConfig.Kind.of(req.query["kind"]), req.query["profile"]?.toIntOrNull() ?: 1),
            )
            "GET" to "/api/media" -> encode(com.bmscompanion.app.data.mission.MediaList.serializer(), shots.list(picturesDir))
            "GET" to "/api/media/thumb", "GET" to "/api/media/view" -> {
                val file = ScreenshotStore.resolve(picturesDir, req.query["name"]) ?: return ApiResponse.notFound()
                val max = if (req.path.endsWith("thumb")) 360 else (req.query["max"]?.toIntOrNull() ?: 2400).coerceIn(320, 4096)
                shots.scaled(file, max)?.let { ApiResponse(200, "image/jpeg", it) } ?: ApiResponse.notFound()
            }
            "GET" to "/api/media/file" -> {
                val file = ScreenshotStore.resolve(picturesDir, req.query["name"]) ?: return ApiResponse.notFound()
                val type = when (file.extension.lowercase()) { "png" -> "image/png"; "bmp" -> "image/bmp"; else -> "image/jpeg" }
                ApiResponse(200, type, file.readBytes())
            }
            "POST" to "/api/media/delete" -> {
                val names = ArrayList<String>()
                if (req.body.isNotEmpty()) runCatching { names += json.decodeFromString(ListSerializer(String.serializer()), req.body.toString(Charsets.UTF_8)) }
                req.query["name"]?.let { names += it }
                ApiResponse.json("""{"deleted":${shots.delete(picturesDir, names)}}""")
            }
            else -> ApiResponse.notFound()
        }
    }
}

/** The port BMS Companion's HTTP server listens on (discovery replies with it). */
object PcServerPort {
    @Volatile var current = 47474
}
