package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.DtcPoint
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.mission.MissionRoute
import com.bmscompanion.app.data.mission.MissionSourceInfo
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.data.mission.PlanOverlay
import com.bmscompanion.app.data.mission.PlanSource
import com.bmscompanion.app.data.mission.PlanState
import com.bmscompanion.app.data.mission.PopulateSend
import com.bmscompanion.app.data.mission.Populated
import com.bmscompanion.app.data.mission.SupportTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where the Mission section's data comes from, on every device (docs/PROTOCOL.md, "Mission source"; the words are in
 * `MissionMode`):
 *
 * - **EZBoards mode** (the default, [BridgeSettings.MissionSource] `"ezboards"`): Falcon BMS's printed briefing and the
 *   pilot's cartridge, read again whenever BMS writes them — `/api/mission` exactly as it always was, except that
 *   nothing is laid over it any more (the Planner's Send to Mission is gone, and a plan it once sent is not served).
 * - **WDP mode** (`"wdp"`): a **snapshot** the pilot takes with **Populate from Planner** (`POST /api/mission/populate`):
 *   the flight the Planner has open in a save (its route, package, loadout, support, intel, and its briefing: **BMS's
 *   printed briefing when it is that flight's** — exactly what EZBoards mode shows, so the same flight shows the same
 *   mission in both modes (`Briefing.origin` "printed", `Populated.briefingFrom`) — else the one [CampaignFiles.flight]
 *   words out of the save), the pilot's cartridge **as saved** on disk (parsed as `MissionData.dtc` always is, the
 *   theater's PPT names on it), the mission file beside the save where it is that flight's (and a TE's PPTs and lines),
 *   the tanker and AWACS tracks of that save ([PlannedRoutes], the same rules as [Bridge.supportTracks]), the save's
 *   own weather (`<save>.twx` read as the Planner's card reads it, into the briefing's weather block: [SaveWeather];
 *   over a printed briefing only when saved after the print with other weather, the card's rule) and
 *   the attack the Planner worked out (or the one the cartridge's `[NAV OFFSETS]` lay out). It is served by
 *   `/api/mission` for as long as the mode is WDP, and **never taken again by itself**: when the save, the cartridge,
 *   the mission file or the weather file changes on disk, [Populated.changed] says so and the page offers Populate
 *   again. Meanwhile EZBoards' own
 *   generation is suspended ([suspended]) without its setting being touched.
 *
 * Switching is instant, asks nothing and shows nothing. Since 1.3.8 it clears every leftover ([ModeSwitchReset]: every
 * key the Planner wrote that the cartridge or a TE's file still holds; the other mode's cockpit pages from an earlier
 * flight, given BMS's own back; a snapshot that is not the current flight's). The snapshot survives a switch to
 * EZBoards mode and back (for the same flight), and a restart: it is kept in `%APPDATA%\BMS Companion\wdp-mission.json` (**never in the BMS
 * folder**), written through a temporary file and an atomic move, and read back at start. A write that fails refuses
 * the Populate with the reason and leaves the snapshot in memory as it was, so what the devices see is what is on disk.
 *
 * Nothing here throws: a route answers `{"error": "<sentence>"}` with 400 (a request the PC cannot take), 405 (a wrong
 * method) or 409 (the PC's present state). What a switch resets in the BMS folder is guarded inside
 * [ModeSwitchReset] (a developer run writes only into a copy), and the Undo route by `Bridge.guardWrite`.
 */
object MissionSource {
    /** The routes this object answers. */
    val ROUTES = listOf("GET /api/mission/source", "POST /api/mission/source", "POST /api/mission/source/undo", "POST /api/mission/populate", "POST /api/mission/opened")

    const val FILE_NAME = "wdp-mission.json"

    /** A Populate request is a flight reference and an attack: a few kilobytes. */
    const val MAX_BODY_BYTES = 512 * 1024

    /** How often the files behind "changed since" are looked at again, at most (every device asks every two seconds). */
    private const val LOOK_EVERY_MS = 2000L

    /**
     * What is kept: the snapshot as served, what it was made of, and where its four files were — only for telling
     * "changed since"; the paths stay on this PC and are never served.
     */
    @Serializable
    internal data class Stored(
        val version: Int = 1,
        val populated: Populated = Populated(),
        val mission: MissionData = MissionData(),
        val savePath: String? = null,
        val cartridgePath: String? = null,
        val missionPath: String? = null,
        /** the save's weather file (`<save>.twx`), named even when it was missing or another save's; null in an older snapshot */
        val weatherPath: String? = null,
    )

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }

    /** Guards [cur] and the "changed since" cache. */
    private val lock = Any()

    /** Held for a whole Populate (build, write, swap), so two never interleave their files. */
    private val changeLock = Any()

    @Volatile private var loaded = false
    private var cur: Stored? = null
    private var changedKey: String? = null
    private var changedAt = 0L
    private var changedList: List<String> = emptyList()

    /** A developer check points the snapshot at a scratch folder; null = `%APPDATA%\BMS Companion`. */
    @Volatile internal var folderOverride: File? = null

    /** Where the snapshot is kept. */
    val folder: File get() = folderOverride ?: Repo.settingsFolder

    // ================================================================================================ for the bridge

    /** [MissionMode.EZBOARDS] or [MissionMode.WDP], as the settings have it (anything unreadable is EZBoards mode). */
    fun mode(): String = MissionMode.of(Bridge.settings.value.MissionSource) ?: MissionMode.EZBOARDS

    /** WDP mode: `/api/mission` serves the snapshot. */
    val wdp: Boolean get() = mode() == MissionMode.WDP

    /** EZBoards' generation (the button and the run on PRINT) is suspended: WDP mode. */
    val suspended: Boolean get() = wdp

    /** When the mode was last switched (ms); 0 = never. */
    val since: Long get() = Bridge.settings.value.MissionSourceSince

    /**
     * Moves whenever what `/api/mission` carries as `plan` may have: the mode switched, a Populate. Folded into
     * `BriefingStatus.planModified`, which every client — an older one too — already refetches the mission on.
     */
    val stamp: Long get() = if (wdp) maxOf(since, safe(0L) { snapshot()?.populated?.at ?: 0L }) else since

    /** What `/api/info` says of the source ([MissionSourceInfo]); [printedAt] is the printed briefing's file time, 0 = none. */
    fun info(printedAt: Long): MissionSourceInfo = safe(MissionSourceInfo()) {
        val m = mode()
        val p = populated()
        MissionSourceInfo(mode = m, switched = since, populated = p, line = line(m, p, printedAt), reset = ModeSwitchReset.shown(m))
    }

    /**
     * `/api/mission` in WDP mode: the snapshot with "changed since" worked out now, or — before the first Populate —
     * nothing but the mode, which every view shows as [MissionMode.NOT_POPULATED].
     */
    fun mission(): MissionData = safe(MissionData(version = "wdp-none", mode = MissionMode.WDP)) {
        val s = snapshot() ?: return@safe MissionData(version = "wdp-none", mode = MissionMode.WDP)
        s.mission.copy(mode = MissionMode.WDP, populated = s.populated.copy(changed = changed(s)))
    }

    /** The snapshot's description with "changed since" worked out now; null before the first Populate. */
    fun populated(): Populated? = snapshot()?.let { it.populated.copy(changed = changed(it)) }

    /** The attack of the snapshot, for the VR boards' map (`/api/attack`); null in EZBoards mode (the cartridge's is `Bridge.boardAttack`'s) and before a Populate. */
    fun attack(): com.bmscompanion.app.data.mission.AttackOverlay? =
        if (!wdp) null else snapshot()?.mission?.plan?.attack?.takeIf { it.cues.isNotEmpty() }

    /** Reads the snapshot back (`Bridge.start`). Nothing throws; a file that does not read is logged and left. */
    fun load() {
        synchronized(changeLock) {
            val s = readStored(File(folder, FILE_NAME))
            synchronized(lock) { cur = s; changedKey = null; loaded = true }
            if (s != null) BridgeLog.info("WDP mode's snapshot (${describe(s.populated)}, populated ${clock(s.populated.at)}) was read back")
        }
    }

    /** Forgets what is held in memory, as a restart of the program does (a developer check; the file stays). */
    internal fun forgetForCheck() {
        synchronized(changeLock) { synchronized(lock) { cur = null; changedKey = null; loaded = false } }
    }

    fun handle(req: ApiRequest): ApiResponse {
        val path = req.path.trimEnd('/')
        return try {
            when {
                path == "/api/mission/source" && req.method == "GET" -> answer(info(Bridge.printedAt()))
                path == "/api/mission/source" && req.method == "POST" -> switch(req)
                path == "/api/mission/source" -> error(405, "Only GET and POST are answered on /api/mission/source.")
                path == "/api/mission/source/undo" && req.method == "POST" -> undo(req)
                path == "/api/mission/source/undo" -> error(405, "Only POST is answered on /api/mission/source/undo.")
                path == "/api/mission/populate" && req.method == "POST" -> populate(req)
                path == "/api/mission/populate" -> error(405, "Only POST is answered on /api/mission/populate.")
                path == "/api/mission/opened" && req.method == "POST" -> opened(req)
                path == "/api/mission/opened" -> error(405, "Only POST is answered on /api/mission/opened.")
                else -> ApiResponse.notFound()
            }
        } catch (e: Throwable) {
            BridgeLog.warn("${req.method} $path: ${e.message}")
            error(409, "The Mission section's source could not be handled on the PC: ${why(e)}.")
        }
    }

    // ================================================================================================ the routes

    /**
     * `POST /api/mission/source?mode=ezboards|wdp[&for=<LedgerMission JSON>]`: instant and asks nothing. Since 1.3.8 a
     * switch also clears every leftover ([ModeSwitchReset]: the Planner's keys in the cartridge and the TEs' files, the
     * other mode's cockpit pages from an earlier flight, a snapshot that is not the current flight's); `for=` is the
     * flight the switching device's Planner has open. The answer carries the summary (`reset`), which no device draws.
     */
    private fun switch(req: ApiRequest): ApiResponse {
        val said = req.query["mode"] ?: req.body.toString(Charsets.UTF_8).trim().trim('"')
        val want = MissionMode.of(said) ?: return error(400, "Say which mode: mode=ezboards (the BMS briefing) or mode=wdp (the Planner).")
        if (want != mode()) {
            val now = maxOf(System.currentTimeMillis(), since + 1)
            val planner = req.query["for"]?.takeIf { it.isNotBlank() }?.let { s ->
                safe(null) { lenient.decodeFromString(com.bmscompanion.app.data.mission.LedgerMission.serializer(), s) }
            }
            // every leftover, before the mode changes (never throws)
            synchronized(changeLock) { ModeSwitchReset.run(want, now, planner) }
            Bridge.update(reapply = false) { it.copy(MissionSource = want, MissionSourceSince = now) }
            val p = populated()
            BridgeLog.info(
                if (want == MissionMode.WDP) "Mission section: WDP mode — " +
                    (p?.let { "the Planner's snapshot (${describe(it)}, populated ${clock(it.at)})" } ?: "not populated yet") +
                    "; EZBoards on PRINT suspended"
                else "Mission section: EZBoards mode — the BMS briefing",
            )
        }
        return answer(info(Bridge.printedAt()))
    }

    /**
     * `POST /api/mission/source/undo?at=<the switch's time>`: the cartridge keys that switch cleared written back, where
     * each still holds the empty value it wrote ([ModeSwitchReset.undo]). Pages are not undone: they are BMS's own.
     */
    private fun undo(req: ApiRequest): ApiResponse {
        val at = req.query["at"]?.toLongOrNull() ?: return error(400, "Say which switch: at=<its time>.")
        ModeSwitchReset.undo(at) ?: return error(409, "That switch is no longer the last one, so it cannot be undone from here.")
        return answer(info(Bridge.printedAt()))
    }

    /**
     * `POST /api/mission/opened?for=<LedgerMission JSON>`: the Planner on a device opened that flight (Open mission… or
     * Pick a flight). In WDP mode another flight than the last one opened is a **new mission**: what the Planner saved
     * for any other flight is cleared from the cartridge and the TEs' files, and a snapshot of another flight discarded
     * ([ModeSwitchReset.opened]); the answer is the source with its summary (`reset`). In EZBoards mode nothing is done.
     * `clean=1` (1.3.9, in either mode): the Planner planned another flight than it had, and with **Start each opened
     * mission with clean lines, PPTs and Open 1/2 steerpoints** on, the cartridge and the campaign mission file LOAD reads for that flight are
     * cleaned of lines and PPTs ([ModeSwitchReset.cleanOpened]); `reset` then says so (kind `mission`).
     */
    private fun opened(req: ApiRequest): ApiResponse {
        val planner = req.query["for"]?.takeIf { it.isNotBlank() }?.let { s ->
            safe(null) { lenient.decodeFromString(com.bmscompanion.app.data.mission.LedgerMission.serializer(), s) }
        } ?: return error(400, "Say which flight was opened: for=<the flight, as the Planner names it>.")
        val clean = req.query["clean"].let { it == "1" || it.equals("true", true) }
        synchronized(changeLock) {
            ModeSwitchReset.opened(planner)
            if (clean) ModeSwitchReset.cleanOpened(planner)
        }
        return answer(info(Bridge.printedAt()))
    }

    /**
     * Discards WDP mode's snapshot (a switch to WDP mode or a new mission found it another flight's, [ModeSwitchReset]): the file moved
     * aside to `wdp-mission.discarded.json` in the same folder, never into the BMS folder; the Mission section then says
     * "not populated yet". True when it is gone.
     */
    internal fun discardSnapshot(): Boolean = synchronized(lock) {
        try {
            val f = File(folder, FILE_NAME)
            if (f.isFile) Files.move(f.toPath(), File(folder, "wdp-mission.discarded.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
            cur = null
            changedKey = null
            loaded = true
            true
        } catch (e: Throwable) {
            BridgeLog.warn("WDP mode's snapshot could not be discarded: ${e.message}")
            false
        }
    }

    /**
     * `POST /api/mission/populate`: the Planner's flight, the cartridge as saved, the attack — kept as WDP mode's
     * snapshot. One at a time: a second press waits for the first (a Populate reads for a tenth of a second or so).
     */
    private fun populate(req: ApiRequest): ApiResponse = synchronized(changeLock) { populateNow(req) }

    private fun populateNow(req: ApiRequest): ApiResponse {
        if (!wdp) return error(409, "Populate from Planner works in WDP mode: switch the Mission section to WDP mode first.")
        if (req.body.size > MAX_BODY_BYTES) return error(400, "That is too large to be a Populate request (${req.body.size / 1024} KB).")
        val sent = try {
            lenient.decodeFromString(PopulateSend.serializer(), req.body.toString(Charsets.UTF_8).ifBlank { "{}" })
        } catch (_: Throwable) {
            return error(400, "That is not a Populate request: the PC expected the Planner's flight (ref and seat) and its attack.")
        }
        val install = Bridge.install
        if (install.baseDir == null) return error(409, NO_BMS)
        val ref = sent.ref?.takeIf { it.theater.isNotBlank() && it.file.isNotBlank() && it.flight.isNotBlank() }
            ?: return error(409, NO_FLIGHT)
        val seat = sent.seat ?: 0
        if (seat !in 0..3) return error(400, "The seat must be 0-3 (lead, 2, 3 or 4); $seat is not one.")
        val set = Theaters.of(install) ?: return error(409, NO_BMS)
        val t = set.all.firstOrNull { it.name.equals(ref.theater.trim(), ignoreCase = true) } ?: set.byName(ref.theater)
            ?: return error(400, "There is no theater \"${ref.theater}\" in this Falcon BMS.")

        // the flight, as the Planner sees it (the same answer /api/campaign/flight gives)
        val flight: CampFlight = when (val a = CampaignFiles.flight(CampaignFiles.context(), t.name, ref.file, ref.flight)) {
            is CampaignFiles.Answer.Ok -> a.value
            is CampaignFiles.Answer.Refused -> return error(a.status, a.sentence)
        }
        val saveFile = safe(null) { set.saves(t).firstOrNull { it.name.equals(ref.file.trim(), ignoreCase = true) } }
            ?: return error(409, "${ref.file} is no longer in the campaign folder of ${t.name}. Open the mission again in the Planner.")
        val saveModified = safe(0L) { saveFile.lastModified() }
        val save = safe(null) { CampaignArchive.cached(saveFile, CampaignArchive.names(set, t)) }
        val kind = save?.kind?.takeIf { it.isNotEmpty() } ?: flight.kind?.takeIf { it.isNotEmpty() } ?: kindOf(saveFile.name)
        val te = kind == CampKind.TE || kind == CampKind.TRAINING
        val table = safe(null) { PptTable.read(set.campaignDir(t)) }?.takeIf { it.isNotEmpty() }
            ?: safe(null) { install.baseDir?.let { PptTable.read(File(File(it, "Data"), "Campaign")) } }.orEmpty()
        val notes = ArrayList<String>()

        // another flight than the last one opened or populated is a new mission: what the Planner saved for an earlier
        // flight is cleared from the cartridge before it is read (ModeSwitchReset; the snapshot is replaced below)
        runCatching {
            ModeSwitchReset.opened(
                com.bmscompanion.app.data.mission.LedgerMission.ofFlight(t.name, saveFile.name, flight, seat, System.currentTimeMillis()),
                discardSnapshot = false,
            )
        }

        // the cartridge, as saved: the pilot BMS has selected, or the one the Planner names
        val cartFile = cartridgeFile(sent.callsign)
        var dtc: Dtc? = cartFile?.takeIf { it.isFile }?.let { f ->
            safe(null) { PlanStore.resolve(DtcParser.parse(f), table) }
                ?: return error(409, "The cartridge ${f.name} could not be read. Save the DTC again, then Populate again.")
        }
        if (dtc == null) {
            val cs = sent.callsign?.trim()?.takeIf { it.isNotEmpty() } ?: install.callsign?.trim()?.takeIf { it.isNotEmpty() }
            notes += if (cs == null) "Falcon BMS names no pilot on this PC, so no cartridge came with it: only the save's flight is shown."
            else "There is no cartridge for $cs yet (User\\Config\\$cs.ini): only the save's flight is shown. Save to DTC in the Planner, then Populate again."
        }
        // a save of another theater than the one BMS is set to: the cartridge's positions are that other theater's
        // (the Planner's own rule, PlannerMissionState.missionData); its presets, codes and programs still apply
        val bmsTheater = safe(null) { set.current(install.theater) }
        if (dtc != null && bmsTheater != null && !bmsTheater.name.equals(t.name, ignoreCase = true)) {
            dtc = dtc.copy(steerpoints = emptyList(), open = emptyList(), weaponTargets = emptyList())
            notes += "The save is ${t.name} and Falcon BMS is set to ${bmsTheater.name}: the cartridge's steerpoints and targets are left out."
        }

        // BMS's mission file beside the save: its route only where it is this flight's; a TE's PPTs and lines too
        val dir = saveFile.absoluteFile.parentFile
        val checked = flight.missionIni?.file?.takeIf { it.isNotBlank() }?.let { n -> safe(null) { File(dir, n).takeIf { it.isFile } } }
        val iniFile = checked ?: save?.let { s -> safe(null) { MissionDtcFile.iniFor(s) } }
        val matches = checked != null && flight.missionIni?.matches == true
        val iniDtc = iniFile?.let { f -> safe(null) { PlanStore.resolve(DtcParser.parse(f), table) } }
        val iniModified = iniFile?.let { f -> safe(0L) { f.lastModified() } } ?: 0L
        val route = MissionRoute(
            file = iniFile?.name.orEmpty(),
            save = saveFile.name,
            kind = kind.orEmpty(),
            modified = iniModified,
            steerpoints = if (matches) iniDtc?.steerpoints.orEmpty() else emptyList(),
            ppts = if (matches || te) iniDtc?.ppts.orEmpty() else emptyList(),
            lines = if (matches || te) iniDtc?.lines.orEmpty() else emptyList(),
            weaponTargets = if (matches || te) iniDtc?.weaponTargets.orEmpty() else emptyList(),
            bullseyeX = flight.bullseyeX,
            bullseyeY = flight.bullseyeY,
        )
        flight.missionIni?.reason?.let { if (iniFile != null && !matches) notes += it }

        // the tankers and the AWACS of that save, by the rules of the printed mission's (Bridge.supportTracks)
        val tracks = tracksOf(install, set, t, saveFile, flight)

        // BMS's printed briefing, when it is this flight's: the snapshot then carries it exactly as EZBoards mode shows
        // it, so the same flight shows the same mission in both modes; the save's own wording only where no print is
        val printed = printedFor(flight, t, bmsTheater)

        // the save's own weather file, read as the Planner's card reads it, into the briefing's weather block: the
        // save's briefing has none of its own (only PRINT words one); over a printed briefing, by the card's rule
        // (DataCardWiring.weather): the file wins only when it was saved after the print and says other weather
        val wx = safe(SaveWeather.Read(null, null, 0L, null)) { SaveWeather.read(saveFile, flight, t.appId, save, ref.flight) }
        val wxUsed = wx.table != null && (printed == null || saveWeatherWins(printed.first, printed.second, wx))
        if (wx.table == null && printed?.first?.weather?.rows.isNullOrEmpty()) wx.why?.let { notes += MissionMode.NO_WEATHER + it.trimEnd('.') + "." }
        if (wxUsed && printed != null && !printed.first.weather?.rows.isNullOrEmpty())
            notes += "${wx.file?.name ?: "The save's weather file"} was saved after the briefing was printed (${clock(wx.modified)} vs ${clock(printed.second)}) " +
                "with other weather: the briefing shows the save's. PRINT again in BMS for its forecast."

        // the attack: the Planner's, else what the cartridge's [NAV OFFSETS] lay out on the route
        val routePoints = flight.route.map { DtcPoint(n = it.n, x = it.x, y = it.y) } + route.steerpoints
        // (the Planner's current attack, AttackFocus); [saved] says whether the cartridge as saved holds it, which the
        // Mission map's caption words as "not in the cartridge — Save to DTC"
        val diskAttack = dtc?.let { d -> safe(null) { PlanStore.attackFrom(d, routePoints, t.appId) } }
        val attack = sent.attack?.takeIf { it.cues.isNotEmpty() }
            ?.let { a -> a.copy(saved = com.bmscompanion.app.data.mission.AttackDrawing.same(a, diskAttack)) }
            ?: diskAttack

        val at = maxOf(System.currentTimeMillis(), (safe(null) { snapshot() }?.populated?.at ?: 0L) + 1)
        val from = sent.from?.takeIf { it in PlanStore.DEVICES }
        val callsign = flight.row.callsign.trim().takeIf { it.isNotEmpty() }
        val packageId = flight.packageNumber.takeIf { it > 0 }?.toString()
        val cleanRef = CampRef(theater = t.name, file = saveFile.name, flight = ref.flight.trim())
        val briefing = (printed?.first?.copy(origin = PlanMerge.ORIGIN_PRINTED)
            ?: flight.briefing?.let { if (it.origin == null) it.copy(origin = PlanMerge.ORIGIN_SAVE) else it })
            ?.let { b -> wx.table?.takeIf { wxUsed }?.let { b.copy(weather = it) } ?: b }
        val populated = Populated(
            at = at, from = from, theater = t.name, save = saveFile.name, flight = cleanRef.flight, callsign = callsign, seat = seat,
            packageId = packageId, kind = kind, saveModified = saveModified,
            cartridge = cartFile?.takeIf { dtc != null }?.name, cartridgeModified = dtc?.modified ?: 0L,
            missionFile = iniFile?.name, missionFileModified = iniModified,
            attack = attack != null, notes = notes,
            weatherFile = wx.file?.takeIf { wxUsed }?.name, weatherFileModified = wx.modified,
            briefingFrom = if (printed != null) PlanMerge.ORIGIN_PRINTED else if (briefing != null) PlanMerge.ORIGIN_SAVE else null,
            briefingPrinted = printed?.second ?: 0L,
        )
        val mission = MissionData(
            version = "wdp-$at",
            briefingModified = 0L,
            briefing = briefing,
            dtc = dtc,
            board = null,
            tracks = tracks,
            route = route,
            plan = PlanOverlay(
                id = at,
                source = PlanSource.POPULATED,
                from = from,
                theater = t.appId,
                // the Mission views take the plan's flight's briefing first (PlanMerge): the one with the weather
                flight = if (briefing != null) flight.copy(briefing = briefing) else flight,
                ref = cleanRef,
                seat = seat,
                callsign = callsign,
                packageId = packageId,
                state = PlanState.APPLIED,
                // the cartridge is MissionData.dtc: an empty one here marks nothing as the plan's
                dtc = Dtc(),
                route = MissionRoute(file = "", save = saveFile.name, kind = kind.orEmpty(), modified = saveModified, bullseyeX = flight.bullseyeX, bullseyeY = flight.bullseyeY),
                attack = attack,
            ),
            mode = MissionMode.WDP,
            populated = populated,
            // the flight's ground picture from its save, which every map draws by default (MissionGrounds): with the
            // printed briefing, the sites its Threat Analysis names, as EZBoards mode places them
            ground = MissionGrounds.forFlight(set, t, saveFile, cleanRef.flight, flight, printed?.first, save, wdp = true),
        )
        val stored = Stored(
            populated = populated,
            mission = mission,
            savePath = saveFile.absolutePath,
            cartridgePath = cartFile?.absolutePath,
            missionPath = iniFile?.absolutePath,
            weatherPath = wx.file?.absolutePath,
        )
        synchronized(changeLock) {
            persist(stored)?.let { return error(409, it) }
            synchronized(lock) { cur = stored; changedKey = null }
        }
        BridgeLog.info(
            "Populated from the Planner: ${describe(populated)}" +
                (populated.cartridge?.let { ", cartridge $it" } ?: ", no cartridge") +
                (if (matches) ", route from ${iniFile?.name}" else "") +
                (if (printed != null) ", the printed briefing (printed ${clock(printed.second)})" else ", the save's briefing") +
                (populated.weatherFile?.let { ", weather from $it" } ?: if (printed != null) ", the printed weather" else ", no weather") +
                ", ${tracks.size} support track(s)" + (if (attack != null) ", attack" else ""),
        )
        return answer(info(Bridge.printedAt()))
    }

    // ================================================================================================ the parts

    /**
     * BMS's printed briefing and its file time, when it is [flight]'s: the Planner's own match ([CampaignFiles.flight]
     * marks the flight `briefed` when the printed briefing's callsign, package number and flight number are its own in
     * that save — `CampaignFiles.briefKey`), the briefing's callsign the flight's, and the save's theater the one BMS is
     * set to (BMS prints the briefing of the theater it is on). Null otherwise: the snapshot keeps the save's wording.
     */
    private fun printedFor(flight: CampFlight, t: Theaters.Theater, bmsTheater: Theaters.Theater?): Pair<com.bmscompanion.app.data.mission.Briefing, Long>? = safe(null) {
        if (!flight.row.briefed) return@safe null
        if (bmsTheater != null && !bmsTheater.name.equals(t.name, ignoreCase = true)) return@safe null
        val b = Bridge.currentBriefing()?.takeIf { it.origin == null } ?: return@safe null
        if (!b.overview.flight?.trim().equals(flight.row.callsign.trim(), ignoreCase = true)) return@safe null
        b to Bridge.printedAt()
    }

    /**
     * Whether the save's weather file wins over the printed briefing's weather — the Planner card's rule
     * (`DataCardWiring.weather`): the print has no take-off weather, or the file was saved after the print and says
     * other weather at take-off (`CardWeather.sameWeather`: the type, the wind's speed, the temperature). A save of the
     * same weather keeps BMS's own forecast, with the wind direction it picked.
     */
    private fun saveWeatherWins(printed: com.bmscompanion.app.data.mission.Briefing, printedAt: Long, wx: SaveWeather.Read): Boolean = safe(false) {
        val table = wx.table ?: return@safe false
        val print = com.bmscompanion.app.ui.screens.wdp.CardWeather.column(printed, 0) ?: return@safe true
        val newer = wx.modified > 0 && printedAt > 0 && wx.modified > printedAt
        if (!newer) return@safe false
        val file = com.bmscompanion.app.ui.screens.wdp.CardWeather.column(printed.copy(weather = table), 0) ?: return@safe false
        !com.bmscompanion.app.ui.screens.wdp.CardWeather.sameWeather(file, print)
    }

    /**
     * The tanker and AWACS tracks for [flight] of [saveFile]: every flight of the save read by [PlannedRoutes], held
     * against the flight's own route as the printed mission's are ([Bridge.tracksFrom]; the package's own tanker and
     * AWACS are "yours"). Falls back on the package's own support flights ([CampFlight.support]) when the save's
     * routes cannot be read.
     */
    private fun tracksOf(install: BmsInstall, set: Theaters.TheaterSet, t: Theaters.Theater, saveFile: File, flight: CampFlight): List<SupportTrack> {
        val own = flight.route.filter { it.x != 0.0 || it.y != 0.0 }.map { it.x to it.y }
        val assigned = flight.support.filter { it.role == "Tanker" || it.role == "AWACS" }.map { it.callsign.trim().lowercase() }
        val routes = safe(emptyList<PlannedRoute>()) {
            val theaterDir = set.campaignDir(t)?.parentFile ?: saveFile.absoluteFile.parentFile?.parentFile ?: saveFile
            PlannedRoutes.read(PlannedRoutes.Source(saveFile, theaterDir, t), install)
        }
        val read = if (own.isEmpty() || routes.isEmpty()) emptyList() else safe(emptyList()) { Bridge.tracksFrom(routes, own, assigned) }
        if (read.isNotEmpty()) return read
        return flight.support.filter { (it.role == "Tanker" || it.role == "AWACS") && it.track.isNotEmpty() }.map { s ->
            SupportTrack(role = s.role, callsign = s.callsign.takeIf { it.isNotBlank() }, yours = true, points = s.track)
        }
    }

    /**
     * The cartridge file of [callsign], or of the pilot Falcon BMS has selected: a name only ever becomes a file in
     * `User\Config` (anything with a path in it is refused), as the Planner's DTC page names it.
     */
    private fun cartridgeFile(callsign: String?): File? {
        val dir = Bridge.install.configDir?.let(::File) ?: return null
        val name = callsign?.trim()?.takeIf { it.isNotEmpty() } ?: Bridge.install.callsign?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (name.any { it == '/' || it == '\\' || it == ':' } || name.contains("..")) return null
        return File(dir, "$name.ini")
    }

    /**
     * What changed on disk since [s] was taken: "save", "cartridge", "mission file", "weather file", "printed briefing" (a file that is
     * gone counts as changed; a cartridge or a weather file that appeared where there was none too — a TE's weather
     * saved with SAVE WTH changes the `.twx` alone). Looked at again at most every [LOOK_EVERY_MS].
     */
    private fun changed(s: Stored): List<String> {
        val now = System.currentTimeMillis()
        val key = "${s.populated.at}"
        synchronized(lock) { if (changedKey == key && now - changedAt in 0 until LOOK_EVERY_MS) return changedList }
        val out = ArrayList<String>()
        fun time(path: String?): Long = path?.let { p -> safe(0L) { File(p).takeIf { it.isFile }?.lastModified() ?: 0L } } ?: 0L
        val p = s.populated
        if (s.savePath != null && time(s.savePath) != p.saveModified) out += "save"
        when {
            s.cartridgePath == null -> Unit
            p.cartridge == null -> if (time(s.cartridgePath) != 0L) out += "cartridge"
            time(s.cartridgePath) != p.cartridgeModified -> out += "cartridge"
        }
        if (s.missionPath != null && time(s.missionPath) != p.missionFileModified) out += "mission file"
        if (s.weatherPath != null && time(s.weatherPath) != p.weatherFileModified) out += "weather file"
        // BMS printed a briefing after the Populate: the next mission is being set up (PRINT comes before Open mission…
        // and Populate in WDP mode's order), which a save under a new name alone would never show
        if (safe(0L) { Bridge.printedAt() } > p.at) out += "printed briefing"
        synchronized(lock) { changedKey = key; changedAt = now; changedList = out }
        return out
    }

    /** The line under the switch. */
    private fun line(mode: String, p: Populated?, printedAt: Long): String =
        if (mode == MissionMode.WDP) {
            if (p == null) "From the Planner · not populated yet"
            else (listOf("From the Planner", "populated ${clock(p.at)}", p.save) + listOfNotNull(p.callsign)).joinToString(" · ")
        } else {
            "From BMS briefing · " + if (printedAt > 0) "printed ${clock(printedAt)}" else "not printed yet"
        }

    private fun snapshot(): Stored? {
        if (!loaded) load()
        return synchronized(lock) { cur }
    }

    // ================================================================================================ files

    /** Writes [s] as the snapshot: a temporary file beside it, then an atomic move. Null when it is on disk, else the sentence. */
    private fun persist(s: Stored): String? {
        val dir = folder
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return cannotKeep(dir, "the folder could not be made")
            val target = File(dir, FILE_NAME)
            val tmp = File(dir, "$FILE_NAME.tmp")
            try {
                tmp.writeText(Bridge.json.encodeToString(Stored.serializer(), s), Charsets.UTF_8)
                try {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                runCatching { if (tmp.exists()) tmp.delete() }
            }
            null
        } catch (e: Throwable) {
            BridgeLog.warn("WDP mode's snapshot could not be kept in ${dir.path}: ${e.message}")
            cannotKeep(dir, why(e))
        }
    }

    private fun cannotKeep(dir: File, reason: String) =
        "The Mission section could not be populated: $reason, in the settings folder (${dir.name}). Nothing was changed. " +
            "Check that Windows lets BMS Companion write its settings folder, then try again."

    private fun readStored(f: File): Stored? {
        if (!f.isFile) return null
        return try {
            lenient.decodeFromString(Stored.serializer(), f.readText(Charsets.UTF_8)).takeIf { it.populated.at != 0L }
        } catch (e: Throwable) {
            BridgeLog.warn("${f.name} could not be read, and is left as it is: ${e.message}")
            null
        }
    }

    // ================================================================================================ small things

    private const val NO_BMS = "No Falcon BMS folder is known on this PC. Set it in BMS Companion's settings on the PC."
    private const val NO_FLIGHT = "Open your flight in the Planner first (Open mission…, then pick the flight), then press Populate from Planner."

    private fun kindOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "cam" -> CampKind.CAMPAIGN
        "tac" -> CampKind.TE
        "trn" -> CampKind.TRAINING
        else -> null
    }

    private fun answer(i: MissionSourceInfo) = ApiResponse.json(Bridge.json.encodeToString(MissionSourceInfo.serializer(), i))

    private fun error(status: Int, sentence: String) =
        ApiResponse.json("""{"error":${Bridge.json.encodeToString(String.serializer(), sentence)}}""", status)

    private fun describe(p: Populated): String =
        listOfNotNull(p.callsign, p.packageId?.let { "package $it" }, p.save.takeIf { it.isNotEmpty() }, p.theater.takeIf { it.isNotEmpty() }).joinToString(", ")
            .ifEmpty { "no flight named" }

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val dayHhmm = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.US)

    /** "22:51" today, "28 Sep 22:51" on another day, on the PC's clock. */
    private fun clock(ms: Long): String = safe("?") {
        val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        if (z.toLocalDate() == LocalDate.now(ZoneId.systemDefault())) hhmm.format(z) else dayHhmm.format(z)
    }

    private fun why(e: Throwable): String = when (e) {
        is java.nio.file.AccessDeniedException -> "Windows refused access to ${e.file?.let { File(it).name } ?: "the file"}"
        is java.nio.file.FileSystemException -> (e.reason ?: e.javaClass.simpleName) + (e.file?.let { " (${File(it).name})" } ?: "")
        is java.io.FileNotFoundException -> e.message.orEmpty().let { m ->
            val reason = Regex("\\(([^()]*)\\)\\s*$").find(m)?.groupValues?.get(1)
            val name = m.substringBefore(" (").substringAfterLast('\\').substringAfterLast('/').ifBlank { "the file" }
            "$name could not be written" + (reason?.let { " ($it)" } ?: "")
        }
        else -> (e.message ?: e.javaClass.simpleName).trimEnd('.')
    }

    private inline fun <T> safe(fallback: T, body: () -> T): T = try { body() } catch (_: Throwable) { fallback }
}
