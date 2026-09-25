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
        // Your flight plan, which is what proves the file on disk is the mission being flown. It comes from the
        // DTC you saved; in 3D, BMS also puts your steerpoints in shared memory, so a pilot who never presses SAVE
        // still gets the tracks once airborne.
        val own = dtc?.steerpoints.orEmpty().filter { it.x != 0.0 || it.y != 0.0 }.map { it.x to it.y }
            .ifEmpty {
                snapshot().live.navPoints.filter { it.type == "WP" && (it.x != 0.0 || it.y != 0.0) }.map { it.x to it.y }
            }
        if (own.isEmpty()) return emptyList()
        val routes = PlannedRoutes.current(install, install.theater, own)
        if (routes.isEmpty()) return emptyList()

        // Your own flight is in there too — it is what let the file be trusted in the first place — and its first
        // and last times are the window you are airborne for. A tanker that lands before you push, or takes off
        // after you are home, is not your tanker and is not drawn.
        val mine = routes.maxByOrNull { r -> own.count { (x, y) -> r.points.any { hypot(it.x - x, it.y - y) < 1.5 * 6076.12 } } }
        // Only points the campaign has put a time on. A route ends with an untimed point (an alternate landing, at
        // 00:00), and counting it opened your window at midnight — every support flight of the day then "overlapped".
        val timed = mine?.points?.filter { it.arriveMs > 0 }.orEmpty()
        val from = timed.minOfOrNull { it.arriveMs } ?: 0L
        val to = timed.maxOfOrNull { maxOf(it.arriveMs, it.departMs) } ?: Long.MAX_VALUE

        // the tanker and the AWACS your flight was given, as the sim itself names them
        val voice = snapshot().live.voice
        val assigned = listOfNotNull(voice?.tanker, voice?.awacs).map { it.trim().lowercase() }

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
        discovery.start({ PcServerPort.current }, AppInfo.version)
        tickTask = scheduler.scheduleWithFixedDelay({ tick() }, 0, 1, TimeUnit.SECONDS)
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

    /** Saves and applies changed settings. */
    fun update(change: (BridgeSettings) -> BridgeSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        next.save()
        BridgeLog.info("Settings changed")
        if (running) apply()
    }

    private fun apply() {
        val s = _settings.value
        install.refresh(s.BmsDirOverride)
        if (s.EzBoardsDir.isNullOrBlank()) install.defaultEzBoardsDir()?.let { auto -> _settings.value = s.copy(EzBoardsDir = auto).also { it.save() } }
        if (s.TacviewEnabled) tacview.start(s.TacviewHost, s.TacviewPort, s.TacviewPassword) else tacview.stop()
        synchronized(filesLock) { briefing = null; dtc = null; briefingMtime = 0; dtcMtime = 0; boardKey = 0 }
        _ticks.value++
    }

    // ------------------------------------------------------------------ data

    private var tickCount = 0

    private fun tick() {
        runCatching {
            if (++tickCount % 10 == 0) install.refresh(_settings.value.BmsDirOverride)
            val printed = refreshFiles()
            val s = _settings.value
            if (printed && s.AutoEzBoardsOnPrint && EzBoardsRunner.isValidDir(s.EzBoardsDir)) {
                BridgeLog.info("Briefing printed - running EZBoards automatically")
                Thread({ ez.generate(s.EzBoardsDir, auto = true) }, "ezboards-auto").apply { isDaemon = true }.start()
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
    private var board: Board? = null
    private var boardKey = 0L

    /** The field, runway and spot the Taxi page is on, for the VR boards to follow. */
    @Volatile private var taxiSelection: com.bmscompanion.app.data.mission.TaxiSelection? = null
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
                    if (!firstLoad) printed = true
                }.onFailure { BridgeLog.warn("Briefing read failed: ${it.message}"); briefingMtime = 0 }
            }
        }
        val ini = callsignIni
        val im = ini?.let(::File)?.takeIf { it.isFile }?.lastModified() ?: 0L
        if (im != dtcMtime) {
            dtcMtime = im
            dtc = null
            if (im > 0) runCatching { dtc = DtcParser.parse(File(ini!!)) }.onFailure { BridgeLog.warn("DTC read failed: ${it.message}"); dtcMtime = 0 }
        }
        printed
    }

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
            ),
            briefing = BriefingStatus(available = briefing != null, modified = briefingMtime, generated = briefing?.generated, dtcModified = dtcMtime),
            media = shots.info(picturesDir),
            acmi = acmi.info(acmiDir),
            kneeboard = kneeboard.status(s, install, briefingMtime),
            ezBoards = EzStatus(
                configured = EzBoardsRunner.isValidDir(s.EzBoardsDir), path = s.EzBoardsDir, autoOnPrint = s.AutoEzBoardsOnPrint,
                running = ez.running, lastRun = ez.lastRun,
            ),
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

    /** The mission API (GET /api/info, /api/live, … ; see docs/PROTOCOL.md). */
    fun handle(req: ApiRequest): ApiResponse {
        if (!running) return ApiResponse.json("""{"error":"Falcon BMS is not read on this PC"}""", 503)
        return when (req.method to req.path.trimEnd('/')) {
            "GET" to "/api/info" -> encode(BridgeInfo.serializer(), info())
            "GET" to "/api/live" ->
                snapshot().let { sm -> encode(Live.serializer(), if (sm.available) sm.live else Live(t = System.currentTimeMillis())) }
            "GET" to "/api/contacts" ->
                snapshot().let { sm ->
                    encode(com.bmscompanion.app.data.mission.Contacts.serializer(), tacview.snapshot(if (sm.flying) sm.live.x else null, if (sm.flying) sm.live.y else null))
                }
            "GET" to "/api/mission" -> {
                refreshFiles()
                val b = boardNow()
                encode(MissionData.serializer(), MissionData("$briefingMtime-$dtcMtime-${b?.time ?: 0}", briefingMtime, briefing, dtc, b, supportTracks(dtc)))
            }
            "POST" to "/api/ezboards/generate" -> {
                ez.generate(_settings.value.EzBoardsDir, auto = false).let { r ->
                    ApiResponse.json(json.encodeToString(EzRun.serializer(), r), if (r.ok) 200 else 409)
                }
            }
            // What each VR board shows. The boards themselves only read it; the VR board page on the PC writes it.
            // What the Taxi page is showing. A VR board is a browser tab of its own, so it cannot see the app's
            // state; it asks the PC, and the PC remembers whatever the page last published. Nothing is stored on
            // disk: it means nothing once the flight is over.
            "GET" to "/api/taxi" -> encode(
                com.bmscompanion.app.data.mission.TaxiSelection.serializer(),
                taxiSelection ?: com.bmscompanion.app.data.mission.TaxiSelection(),
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
                    taxiSelection = sel
                    encode(com.bmscompanion.app.data.mission.TaxiSelection.serializer(), sel)
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
            "POST" to "/api/kneeboard/open" -> {
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
