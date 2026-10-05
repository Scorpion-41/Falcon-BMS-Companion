package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.AttackCue
import com.bmscompanion.app.data.mission.AttackOverlay
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.CampWaypoint
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.DtcPoint
import com.bmscompanion.app.data.mission.DtcPpt
import com.bmscompanion.app.data.mission.MissionRoute
import com.bmscompanion.app.data.mission.NavOffset
import com.bmscompanion.app.data.mission.NavPoint
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.data.mission.PlanOverlay
import com.bmscompanion.app.data.mission.PlanSend
import com.bmscompanion.app.data.mission.PlanSource
import com.bmscompanion.app.data.mission.PlanState
import com.bmscompanion.app.data.mission.Preset
import com.bmscompanion.app.data.wdp.AttackGeometry
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The plan the pilot sent with the Planner's **Send to Mission** (`/api/plan…`, docs/PROTOCOL.md "Planner
 * integration"; R3-PLAN A10, A12): parsed, stamped with the mission it was made for, applied or parked, and kept in
 * `%APPDATA%\BMS Companion` — **never in the BMS folder**. Every device merges it into its views from `MissionData.plan`
 * (`PlanMerge`, shared code); the PC only parses, stores and serves.
 *
 * **What is sent.**
 * - `POST /api/plan` (`PlanSend`): the Planner's cartridge text as it stands, saved or not (null: the cartridge on
 *   disk), the attack the attack page worked out, the device kind, and — when a save is open in the Planner — the
 *   flight ([CampRef]) and the seat, so the PC attaches the flight itself ([CampaignFiles.flight]).
 * - `POST /api/plan/files`: a snapshot of the files on the PC — the pilot's cartridge, BMS's mission file beside the save
 *   being flown ([MissionDtcFile.snapshot], as it is: WDP's rewrite included) and the save header's bullseye. The path
 *   for a pilot who plans in Falcas's own WDP.
 *
 * **Parse and resolve.** The cartridge through [DtcParser.parseText] (the same parser `MissionData.dtc` comes from); the
 * PPT codes turned into the names a pilot knows through the theater's `Ppt.ini` ([PptTable]), the code kept in `code`.
 * An attack that was not sent is laid out from the cartridge's `[NAV OFFSETS]` ([attackFrom]): each line from its own
 * steerpoint by true bearing and range (Dash-34 p.423-425), which is where the jet will put it.
 *
 * **Applied or parked** (A12, worked out again whenever it is asked, so a PRINT or a theater change moves it): parked
 * when the plan was made for another theater than the one Falcon BMS is set to, or when a briefing for another flight
 * was printed **after** the plan was sent (and is not older than the plan's save). Everything else is applied: the same
 * flight, no printed briefing, or a briefing for another flight printed before the plan (the pilot's last act wins).
 * The flight test is the clients' own ([PlanMerge.sameFlight]), so the PC and the devices never disagree.
 *
 * **Not in the jet** ([notInJet]): what the jet will not have until the pilot saves and loads the DTC, as short labels
 * ("STPT 16", "WPN 3", "PPT 56", "LINE 1", "UHF 3", "OA1 on 6", "VIP", "TACAN" …). Positions within [SAME_FT] are the
 * same; frequencies and codes must match exactly; formatting never counts (values are compared parsed). Before 3D the
 * reference is the disk: the cartridge's slot, else BMS's believed mission-file route ([MissionDtcFile.current]); in 3D
 * the jet's own steerpoints, PPTs and lines (shared memory). Worked out again whenever the cartridge, the briefing, the
 * route or the jet's navpoints change — so a Save to DTC empties it by itself — and [modified] moves with it.
 *
 * **Kept** in `planner-plan.json` (the plan and the cartridge text it came from) and `planner-plan.prev.json` (the one
 * Undo brings back), written through a temporary file and an atomic move. A write that fails refuses the request with
 * the reason, and the plan in memory stays as it was, so what the devices see is always what is on disk. Read back at
 * start (and on first use, for a developer check that never called [load]).
 *
 * Nothing here throws: a route answers `{"error": "<sentence>"}` with 400 (a request the PC cannot take), 405 (a wrong
 * method) or 409 (the PC's present state). Nothing is written into the BMS folder, so the developer-run guard has
 * nothing to stop here.
 */
object PlanStore {
    /** The routes this object answers. */
    val ROUTES = listOf("GET /api/plan", "POST /api/plan", "POST /api/plan/files", "POST /api/plan/clear", "POST /api/plan/undo")

    /** A cartridge is 20-40 KB; anything over this is not one. */
    const val MAX_CARTRIDGE_BYTES = 256 * 1024

    /** The whole request: the cartridge, an attack and the flight's reference. */
    const val MAX_BODY_BYTES = 512 * 1024

    /** Two positions closer than this are the same place (the clients' rule, [PlanMerge.SAME_FT]). */
    const val SAME_FT = PlanMerge.SAME_FT

    const val FILE_NAME = "planner-plan.json"
    const val PREV_NAME = "planner-plan.prev.json"

    /** The device kinds a plan may name ([PlanOverlay.from]); anything else is dropped, so no name ever gets in. */
    val DEVICES = setOf("PC", "Android", "Browser")

    /**
     * What is kept of one send: the plan as sent (stamped, parsed, the flight attached; its state, note, not-in-jet
     * list and Undo flag are worked out when asked), the cartridge text it came from (so "not in jet" can be worked out
     * again after a restart), the name of the theater it was made for, and the file time of the save its flight came
     * from (for "the printed briefing is older than your save").
     */
    @Serializable
    internal data class Stored(
        val version: Int = 1,
        val plan: PlanOverlay = PlanOverlay(),
        val cartridge: String? = null,
        val theaterName: String? = null,
        val saveModified: Long = 0,
    )

    /**
     * What a plan is held against: the printed briefing and its file time, the pilot's cartridge on disk, BMS's believed
     * mission-file route, the theater Falcon BMS is set to (app id and name), and the jet's navpoints in 3D (empty
     * before). [key] changes whenever any of them does.
     */
    internal class World(
        val briefing: Briefing?,
        val briefingMtime: Long,
        val disk: Dtc?,
        val route: MissionRoute?,
        val theaterApp: String?,
        val theaterName: String?,
        val nav: List<NavPoint>,
        val key: String,
    )

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }
    private val stamp = DateTimeFormatter.ofPattern("HH:mm")

    /** Guards [cur], [prev] and the view cache. */
    private val lock = Any()

    /** Held for a whole change (work out, write, swap), so two sends never interleave their files. */
    private val changeLock = Any()

    @Volatile private var loaded = false
    private var cur: Stored? = null
    private var prev: Stored? = null
    private var view: PlanOverlay? = null
    private var viewKey: String? = null
    @Volatile private var changedAt = 0L
    private var lastId = 0L

    /** A developer check points the plan files at a scratch folder; null = `%APPDATA%\BMS Companion`. */
    @Volatile internal var folderOverride: File? = null

    /** Where the plan files are kept. */
    val folder: File get() = folderOverride ?: Repo.settingsFolder

    // ================================================================================================ for the bridge

    /** The plan `/api/mission` carries, or null when nothing was sent (or it was cleared). Cheap to call. */
    fun current(): PlanOverlay? = safe(null) { derived()?.takeIf { it.present } }

    /**
     * Changes whenever the plan does — sent, cleared, undone, parked or applied again, or its not-in-jet list moved;
     * 0 = never. `BriefingStatus.planModified`, and part of `/api/mission`'s version, so every device refetches.
     */
    val modified: Long get() = safe(changedAt) { derived(); changedAt }

    /** Reads the stored plan back (`Bridge.start`). Nothing throws; a file that does not read is logged and left. */
    fun load() {
        synchronized(changeLock) {
            val c = readStored(File(folder, FILE_NAME))
            val p = readStored(File(folder, PREV_NAME))
            synchronized(lock) {
                cur = c; prev = p; view = null; viewKey = null
                lastId = maxOf(lastId, c?.plan?.id ?: 0L, p?.plan?.id ?: 0L)
                if (c != null || p != null) changedAt = next()
                loaded = true
            }
            if (c != null) BridgeLog.info("The plan sent ${ago(c.plan.id)} (${describe(c.plan)}) was read back")
        }
    }

    /** Forgets what is held in memory, as a restart of the program does (a developer check; the files stay). */
    internal fun forgetForCheck() {
        synchronized(changeLock) {
            synchronized(lock) { cur = null; prev = null; view = null; viewKey = null; loaded = false }
        }
    }

    fun handle(req: ApiRequest): ApiResponse {
        val path = req.path.trimEnd('/')
        val known = path == "/api/plan" || path == "/api/plan/files" || path == "/api/plan/clear" || path == "/api/plan/undo"
        if (!known) return ApiResponse.notFound()
        return try {
            ensureLoaded()
            when {
                path == "/api/plan" && req.method == "GET" -> answer(derived() ?: PlanOverlay(canUndo = synchronized(lock) { prev != null }))
                req.method != "POST" -> error(405, if (path == "/api/plan") "Only GET and POST are answered on /api/plan." else "Only POST is answered on $path.")
                path == "/api/plan" -> send(req)
                path == "/api/plan/files" -> sendFiles(req)
                path == "/api/plan/clear" -> clear()
                else -> undo()
            }
        } catch (e: Throwable) {
            BridgeLog.warn("${req.method} $path: ${e.message}")
            error(409, "The plan could not be handled on the PC: ${why(e)}.")
        }
    }

    // ================================================================================================ the routes

    /** `POST /api/plan`: the Planner's cartridge (or the one on disk), its attack, and the flight of a save. */
    private fun send(req: ApiRequest): ApiResponse {
        if (req.body.size > MAX_BODY_BYTES) return error(400, "That is too large to be a plan (${req.body.size / 1024} KB); a cartridge is well under ${MAX_CARTRIDGE_BYTES / 1024} KB.")
        val body = req.body.toString(Charsets.UTF_8)
        val sent = try {
            lenient.decodeFromString(PlanSend.serializer(), body)
        } catch (_: Throwable) {
            return error(400, "That is not a plan: the PC expected the Planner's cartridge and attack.")
        }
        val install = Bridge.install
        if (install.baseDir == null) return error(409, NO_BMS)
        val text = sent.cartridge
        if (text != null) {
            val size = text.toByteArray(Charsets.UTF_8).size
            if (size > MAX_CARTRIDGE_BYTES) return error(400, "That is not a cartridge: it is ${size / 1024} KB, and a cartridge is well under ${MAX_CARTRIDGE_BYTES / 1024} KB.")
            if (!hasStpt(text)) return error(400, "That is not a cartridge: it has no [STPT] section.")
        }
        val seat = sent.seat
        if (seat != null && seat !in 0..3) return error(400, "The seat must be 0-3 (lead, 2, 3 or 4); $seat is not one.")

        // the cartridge: the Planner's text, or the file on the PC when the Planner sent none
        val disk = diskCartridge()
        val cartText = text ?: disk?.second ?: return error(409, noCartridge())

        // the theater, and the flight of the save when one was named
        val set = Theaters.of(install)
        val here = set?.current(install.theater)
        var theater = here
        var flight: CampFlight? = null
        var saveModified = 0L
        var saveName: String? = null
        var saveRoute: MissionRoute? = null
        val ref = sent.ref?.takeIf { it.theater.isNotBlank() || it.file.isNotBlank() || it.flight.isNotBlank() }
        if (ref != null) {
            val t = set?.byName(ref.theater) ?: return error(400, "There is no theater \"${ref.theater}\" in this Falcon BMS.")
            when (val a = CampaignFiles.flight(CampaignFiles.context(), ref.theater, ref.file, ref.flight)) {
                is CampaignFiles.Answer.Ok -> flight = a.value
                is CampaignFiles.Answer.Refused -> return error(a.status, a.sentence)
            }
            theater = t
            val saveFile = safe(null) { set.saves(t).firstOrNull { it.name.equals(ref.file.trim(), ignoreCase = true) } }
            saveModified = safe(0L) { saveFile?.lastModified() ?: 0L }
            saveName = saveFile?.name
            // the save's kind (a TE keeps its PPTs and lines in its own file) and its header's bullseye (R3-PLAN A23), so a
            // device shows BULLS figures before 3D with no printed briefing; no points: the flight carries its route
            saveRoute = safe(null) {
                saveFile?.let { f ->
                    val save = CampaignArchive.cached(f, CampaignArchive.names(set, t))
                    val bull = MissionDtcFile.bullseye(save.header)
                    MissionRoute(file = "", save = f.name, kind = save.kind, modified = saveModified, bullseyeX = bull?.first, bullseyeY = bull?.second)
                }
            }
        }
        val appId = theater?.appId ?: install.theater?.let(Theaters::slug).orEmpty()
        val table = safe(null) { PptTable.read(set?.campaignDir(theater)) }.orEmpty()

        val id = newId()
        val parsed = DtcParser.parseText(cartText)
        val dtc = resolve(parsed, table).copy(modified = if (text != null) id else disk?.first?.modified ?: id)
        val attack = sent.attack?.takeIf { it.cues.isNotEmpty() }
            ?: attackFrom(dtc, flight?.route?.map { DtcPoint(n = it.n, x = it.x, y = it.y) }.orEmpty() + currentRoutePoints(), appId)
        val b = safe(null) { Bridge.currentBriefing() }
        val stored = Stored(
            plan = PlanOverlay(
                id = id,
                source = PlanSource.PLANNER,
                from = sent.from?.takeIf { it in DEVICES },
                theater = appId,
                flight = flight,
                route = saveRoute,
                ref = ref?.let { CampRef(theater = theater?.name ?: it.theater, file = saveName ?: it.file, flight = it.flight) },
                seat = seat,
                callsign = flight?.row?.callsign?.takeIf { it.isNotBlank() } ?: b?.overview?.flight?.trim()?.takeIf { it.isNotEmpty() },
                packageId = flight?.packageNumber?.takeIf { it > 0 }?.toString() ?: if (flight == null) b?.overview?.packageId?.trim()?.takeIf { it.isNotEmpty() } else null,
                briefing = b?.generated,
                dtc = dtc,
                attack = attack,
            ),
            cartridge = cartText,
            theaterName = theater?.name ?: install.theater,
            saveModified = saveModified,
        )
        return keep(stored, "sent from ${stored.plan.from ?: "a device"}")
    }

    /** `POST /api/plan/files`: a snapshot of the cartridge, the mission file beside the save and its bullseye. */
    private fun sendFiles(req: ApiRequest): ApiResponse {
        val install = Bridge.install
        if (install.baseDir == null) return error(409, NO_BMS)
        val disk = diskCartridge() ?: return error(409, noCartridge())
        val set = Theaters.of(install)
        val theater = set?.current(install.theater)
        val appId = theater?.appId ?: install.theater?.let(Theaters::slug).orEmpty()
        val table = safe(null) { PptTable.read(set?.campaignDir(theater)) }.orEmpty()
        val snap = safe(null) { MissionDtcFile.snapshot() }?.let { r -> r.copy(ppts = r.ppts.map { resolvePpt(it, table) }) }
        val dtc = resolve(disk.first, table)
        val b = safe(null) { Bridge.currentBriefing() }
        val id = newId()
        val stored = Stored(
            plan = PlanOverlay(
                id = id,
                source = PlanSource.FILES,
                from = (req.query["from"] ?: "PC").takeIf { it in DEVICES },
                theater = appId,
                callsign = b?.overview?.flight?.trim()?.takeIf { it.isNotEmpty() },
                packageId = b?.overview?.packageId?.trim()?.takeIf { it.isNotEmpty() },
                briefing = b?.generated,
                dtc = dtc,
                route = snap,
                attack = attackFrom(dtc, snap?.steerpoints.orEmpty(), appId),
            ),
            cartridge = disk.second,
            theaterName = theater?.name ?: install.theater,
        )
        return keep(stored, "taken from the files on the PC" + (snap?.let { " (the cartridge and ${it.file})" } ?: " (the cartridge; no mission file beside a save)"))
    }

    /** `POST /api/plan/clear`: off every device; the plan stays for Undo. */
    private fun clear(): ApiResponse {
        synchronized(changeLock) {
            val c = synchronized(lock) { cur } ?: return error(409, "There is no plan to clear.")
            persist(null, c)?.let { return error(409, it) }
            synchronized(lock) { prev = c; cur = null; view = null; viewKey = null; changedAt = next() }
            BridgeLog.info("Plan cleared (${describe(c.plan)}); Undo brings it back")
        }
        return answer(PlanOverlay(canUndo = true))
    }

    /** `POST /api/plan/undo`: the previous plan and the current one change places (a second Undo is a redo). */
    private fun undo(): ApiResponse {
        synchronized(changeLock) {
            val (c, p) = synchronized(lock) { cur to prev }
            if (p == null) return error(409, "There is nothing to undo.")
            persist(p, c)?.let { return error(409, it) }
            synchronized(lock) { cur = p; prev = c; view = null; viewKey = null; changedAt = next() }
            BridgeLog.info("Plan brought back (${describe(p.plan)})")
        }
        return answer(derived() ?: PlanOverlay(canUndo = synchronized(lock) { prev != null }))
    }

    /** Writes [stored] as the plan and the one it replaces as the Undo, then answers it (or why it could not be kept). */
    private fun keep(stored: Stored, how: String): ApiResponse {
        synchronized(changeLock) {
            val before = synchronized(lock) { cur }
            persist(stored, before ?: synchronized(lock) { prev })?.let { return error(409, it) }
            synchronized(lock) {
                if (before != null) prev = before
                cur = stored; view = null; viewKey = null; changedAt = next()
            }
        }
        val v = derived()
        BridgeLog.info("Plan $how: ${describe(stored.plan)}, ${v?.state}" + (v?.notInJet?.takeIf { it.isNotEmpty() }?.let { ", not in the jet: ${it.joinToString()}" } ?: ""))
        return answer(v ?: stored.plan)
    }

    // ================================================================================================ the view

    /** The plan as the devices get it: its state and note, what is not in the jet, and whether Undo has something. */
    private fun derived(): PlanOverlay? {
        ensureLoaded()
        val (c, p) = synchronized(lock) { cur to prev }
        if (c == null) return null
        val w = world(c)
        synchronized(lock) { if (cur === c && viewKey == w.key + "#" + (p != null)) return view }
        val v = evaluate(c, w).copy(canUndo = p != null)
        synchronized(lock) {
            if (cur === c) {
                if (v != view) changedAt = next()
                view = v
                viewKey = w.key + "#" + (p != null)
            }
        }
        return v
    }

    private class TheaterLook(val key: String, val at: Long, val theater: Theaters.Theater?)
    @Volatile private var theaterLook: TheaterLook? = null

    /**
     * The theater Falcon BMS is set to, looked up again at most every two seconds: every device asks for the plan about
     * once a second, and the lookup reads the theater list's file times.
     */
    private fun currentTheater(install: BmsInstall): Theaters.Theater? {
        val key = "${install.baseDir}|${install.theater}"
        val now = System.currentTimeMillis()
        theaterLook?.let { if (it.key == key && now - it.at in 0 until 2000) return it.theater }
        val t = safe(null) { Theaters.current(install) }
        theaterLook = TheaterLook(key, now, t)
        return t
    }

    /** The PC as it is now, for [evaluate]. */
    private fun world(s: Stored): World {
        val install = Bridge.install
        val briefing = safe(null) { Bridge.currentBriefing() }
        val bm = if (briefing == null) 0L else Bridge.briefingModified
        val disk = safe(null) { Bridge.currentDtc() }
        val dm = Bridge.dtcModified
        val route = safe(null) { MissionDtcFile.current() }
        val rm = safe(0L) { MissionDtcFile.modified }
        val t = currentTheater(install)
        val app = t?.appId ?: install.theater?.let(Theaters::slug)
        val sm = safe(null) { Bridge.snapshot() }
        val nav = if (sm?.available == true) sm.live.navPoints.filter { it.x != 0.0 || it.y != 0.0 } else emptyList()
        val navKey = nav.joinToString("|") { "${it.type}${it.i}:${it.x.toLong()},${it.y.toLong()},${it.rangeNm}" }.hashCode()
        return World(briefing, bm, disk, route, app, t?.name ?: install.theater, nav, "${s.plan.id}#$bm#$dm#$rm#$app#$navKey#${nav.size}")
    }

    /**
     * [s]'s plan held against [w]: applied or parked (with the reason), and what the jet does not have. Pure: the
     * store's check calls it with worlds of its own making.
     */
    internal fun evaluate(s: Stored, w: World): PlanOverlay {
        val plan = s.plan
        val parked = parkedWhy(s, w)
        if (parked != null) return plan.copy(state = PlanState.PARKED, note = parked, notInJet = emptyList())
        val b = w.briefing
        // BMS's route is a reference only for the flight it was proved for: the printed briefing's
        val sameFlight = b == null || b.origin != null || PlanMerge.sameFlight(b, plan)
        // a "files" plan's mission file was taken off the disk: it is what the jet loads even when BMS's route is not
        // believed (no briefing printed, a TE's own file)
        val route = w.route.takeIf { sameFlight } ?: plan.route.takeIf { plan.source == PlanSource.FILES }
        val flightRoute = plan.flight?.takeIf { it.missionIni?.matches == true }?.route.orEmpty()
        val te = teMode(plan, w.route)
        return plan.copy(state = PlanState.APPLIED, note = null, notInJet = notInJet(plan.dtc, plan.route, w.disk, route, flightRoute, te, w.nav))
    }

    /** Why [s] is set aside in [w], as the clause the Mission banner ends with; null = applied. */
    internal fun parkedWhy(s: Stored, w: World): String? {
        val plan = s.plan
        if (plan.theater.isNotEmpty() && !w.theaterApp.isNullOrEmpty() && !plan.theater.equals(w.theaterApp, ignoreCase = true)) {
            return "it was made for ${s.theaterName ?: plan.theater}, and Falcon BMS is set to ${w.theaterName ?: w.theaterApp} now"
        }
        val b = w.briefing ?: return null
        if (b.origin != null || PlanMerge.sameFlight(b, plan)) return null
        // a briefing for another flight: kept aside only when it was printed after the plan was sent, and is not older
        // than the save the plan's flight came from (then the save is the newer mission, and the plan stands)
        val olderThanSave = s.saveModified > 0 && w.briefingMtime in 1 until s.saveModified
        if (olderThanSave || w.briefingMtime <= plan.id) return null
        val who = listOfNotNull(b.overview.flight?.trim()?.takeIf { it.isNotEmpty() }, b.overview.packageId?.trim()?.takeIf { it.isNotEmpty() }?.let { "(package $it)" })
            .joinToString(" ").ifEmpty { "another flight" }
        return "the briefing printed at ${clock(w.briefingMtime)} is for $who. Send the plan again from the Planner to use it with this briefing"
    }

    /** A Tactical Engagement (or a training) keeps its PPTs and lines in its own mission file; a campaign in the cartridge. */
    private fun teMode(plan: PlanOverlay, route: MissionRoute?): Boolean {
        val kind = plan.route?.kind?.takeIf { it.isNotEmpty() } ?: route?.kind?.takeIf { it.isNotEmpty() }
        if (kind != null) return kind == CampKind.TE || kind == CampKind.TRAINING
        val file = plan.ref?.file.orEmpty().lowercase()
        return file.endsWith(".tac") || file.endsWith(".trn")
    }

    // ================================================================================================ not in the jet

    /**
     * What the jet will not have of [plan] (the plan's cartridge; [planRoute] the mission file a "files" plan
     * snapshotted) until the pilot saves and loads the DTC, as short labels. [disk] is the pilot's cartridge on disk,
     * [route] BMS's believed mission-file route (null when it is not the plan's flight), [flightRoute] the plan's flight
     * in its save when the save's mission file is that flight's, [te] whether PPTs and lines live in the mission file,
     * and [nav] the jet's navpoints in 3D (empty before).
     *
     * - A steerpoint (1-99) or weapon target the plan places is not in the jet when the reference — the jet's own
     *   steerpoint in 3D, else the cartridge's slot, else BMS's route, else the save's waypoint — is missing or more
     *   than [SAME_FT] away. One the plan cleared is not in the jet while the cartridge still holds it (and, in 3D, the
     *   jet does). Weapon targets and 81-99 are held against the cartridge only: BMS does not send them in 3D.
     * - PPTs: position, and code and range before 3D (the file for the mode first: TE the mission file, campaign the
     *   cartridge); lines: each of the four as a whole.
     * - Presets: frequency and comment, against the cartridge (the jet does not publish them).
     * - `[NAV OFFSETS]`: each offset laid out and compared to [SAME_FT] (an offset exists only when the pilot set one).
     * - `Comm1`/`Comm2`, the TACAN and ILS, IFF codes, laser codes, bingo, ALOW, MSL floor and EWS names only where the
     *   cartridge holds them too: the Planner writes those keys whether or not the pilot touched them (WDP's 94X,
     *   109.00, 1688 when the cartridge had none), and BMS's own cartridge has none of the WDP ones, so a missing key
     *   is no difference.
     */
    internal fun notInJet(
        plan: Dtc, planRoute: MissionRoute?, disk: Dtc?, route: MissionRoute?, flightRoute: List<CampWaypoint>, te: Boolean, nav: List<NavPoint>,
    ): List<String> {
        val out = ArrayList<String>()
        fun DtcPoint.placed() = x != 0.0 || y != 0.0
        fun far(ax: Double, ay: Double, bx: Double, by: Double) = hypot(ax - bx, ay - by) > SAME_FT
        val inJet = nav.any { it.type == "WP" }

        // steerpoints 1-99
        val planStpt = plan.steerpoints.filter { it.placed() }.associateBy { it.n }
        val planFile = planRoute?.steerpoints.orEmpty().filter { it.placed() }.associateBy { it.n }
        val cart = disk?.steerpoints.orEmpty().filter { it.placed() }.associateBy { it.n }
        val bms = route?.steerpoints.orEmpty().filter { it.placed() }.associateBy { it.n }
        val save = flightRoute.filter { it.n in 1..24 && (it.x != 0.0 || it.y != 0.0) }.associateBy { it.n }
        val jetWp = nav.filter { it.type == "WP" }.associateBy { it.i }
        for (n in (planStpt.keys + planFile.keys + cart.keys).distinct().sorted()) {
            val p = planStpt[n] ?: planFile[n]
            val jetSays = inJet && n in 1..25
            if (p != null) {
                val ref: Pair<Double, Double>? = if (jetSays) jetWp[n]?.let { it.x to it.y }
                else (cart[n] ?: bms[n])?.let { it.x to it.y } ?: save[n]?.let { it.x to it.y }
                if (ref == null || far(p.x, p.y, ref.first, ref.second)) out += "STPT $n"
            } else if (cart[n] != null && (!jetSays || jetWp[n] != null)) out += "STPT $n"
        }

        // weapon targets (the cartridge only)
        val planWpn = plan.weaponTargets.filter { it.placed() }.associateBy { it.n }
        val cartWpn = disk?.weaponTargets.orEmpty().filter { it.placed() }.associateBy { it.n }
        for (n in (planWpn.keys + cartWpn.keys).distinct().sorted()) {
            val p = planWpn[n]
            val c = cartWpn[n]
            if (p == null || c == null || far(p.x, p.y, c.x, c.y)) out += "WPN $n"
        }

        // PPTs 56-70
        fun pptsOf(list: List<DtcPpt>?) = list.orEmpty().filter { it.x != 0.0 || it.y != 0.0 }.associateBy { it.n }
        val planPpt = pptsOf(plan.ppts).ifEmpty { pptsOf(planRoute?.ppts) }
        val cartPpt = pptsOf(disk?.ppts)
        val filePpt = pptsOf(route?.ppts)
        val jetPts = nav.filter { it.type == "PT" }
        val jetPpt = if (jetPts.all { it.i in 56..70 }) jetPts.associateBy { it.i } else jetPts.withIndex().associate { (k, v) -> 56 + k to v }
        for (n in (planPpt.keys + cartPpt.keys).distinct().sorted()) {
            val p = planPpt[n]
            if (p != null) {
                val differs = if (inJet) {
                    val j = jetPpt[n]
                    j == null || far(p.x, p.y, j.x, j.y) || abs((j.rangeNm ?: 0.0) * DtcParser.FT_PER_NM - (if (p.marker) 0.0 else p.rangeFt)) > maxOf(100.0, p.rangeFt * 0.01)
                } else {
                    val r = if (te) filePpt[n] ?: cartPpt[n] else cartPpt[n] ?: filePpt[n]
                    r == null || far(p.x, p.y, r.x, r.y) || !sameCode(p.code, r.code) || abs(p.rangeFt - r.rangeFt) > 1.0
                }
                if (differs) out += "PPT $n"
            } else if (!inJet || jetPpt[n] != null) out += "PPT $n"
        }

        // lines 1-4, each as a whole
        fun groups(points: List<DtcPoint>?) = points.orEmpty().filter { it.placed() }
            .groupBy { it.line ?: (it.n / 6 + 1) }.filterKeys { it in 1..4 }
            .mapValues { (_, v) -> v.sortedBy { it.n }.map { it.x to it.y } }
        val planLines = groups(plan.lines).ifEmpty { groups(planRoute?.lines) }
        val cartLines = groups(disk?.lines)
        val fileLines = groups(route?.lines)
        val jetLines = nav.filter { it.type.length == 2 && it.type[0] == 'L' && it.type[1] in '1'..'4' }
            .groupBy { it.type[1] - '0' }.mapValues { (_, v) -> v.sortedBy { it.i }.map { it.x to it.y } }
        fun lineDiffers(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>?) =
            b == null || a.size != b.size || a.indices.any { far(a[it].first, a[it].second, b[it].first, b[it].second) }
        for (g in 1..4) {
            val p = planLines[g]
            if (p != null) {
                val ref = if (inJet) jetLines[g] else if (te) fileLines[g] ?: cartLines[g] else cartLines[g] ?: fileLines[g]
                if (lineDiffers(p, ref)) out += "LINE $g"
            } else if (cartLines[g] != null && (!inJet || jetLines[g] != null)) out += "LINE $g"
        }

        // presets (the cartridge only: the jet does not publish them)
        fun presets(label: String, planned: List<Preset>, onDisk: List<Preset>?) {
            val pm = planned.associateBy { it.ch }
            val dm = onDisk.orEmpty().associateBy { it.ch }
            for (ch in (pm.keys + dm.keys).distinct().sorted()) {
                val a = pm[ch]
                val b = dm[ch]
                if (a == null || b == null || !sameFreq(a.freq, b.freq) || a.comment?.trim().orEmpty() != b.comment?.trim().orEmpty()) out += "$label $ch"
            }
        }
        presets("UHF", plan.uhf, disk?.uhf)
        presets("VHF", plan.vhf, disk?.vhf)

        // [NAV OFFSETS]: the reference points and the offset aimpoints, laid out and compared as places
        val pn = plan.navOffsets
        val dn = disk?.navOffsets
        fun offsetDiffers(a: NavOffset, b: NavOffset?): Boolean {
            if (b == null || a.stpt != b.stpt) return true
            val (an, ae) = AttackGeometry.lay(0.0, 0.0, a.bearing, a.rangeFt)
            val (bn, be) = AttackGeometry.lay(0.0, 0.0, b.bearing, b.rangeFt)
            return far(an, ae, bn, be) || abs(a.elevFt - b.elevFt) > SAME_FT
        }
        if (pn != null && pn.mode != "none" && pn.mode != (dn?.mode ?: "none")) out += "OFFSET MODE"
        for ((label, pick) in listOf<Pair<String, (com.bmscompanion.app.data.mission.NavOffsets) -> NavOffset?>>(
            "VIP" to { it.vip }, "VIP PUP" to { it.vipPup }, "VRP" to { it.vrp }, "VRP PUP" to { it.vrpPup },
        )) {
            val a = pn?.let(pick)
            val b = dn?.let(pick)
            if ((a != null && offsetDiffers(a, b)) || (a == null && b != null && pn != null)) out += label
        }
        val planOa = pn?.oa.orEmpty().associateBy { it.key.uppercase() }
        val diskOa = dn?.oa.orEmpty().associateBy { it.key.uppercase() }
        for (k in (planOa.keys + diskOa.keys).distinct().sortedWith(compareBy({ it.substringAfter('-').toIntOrNull() ?: 0 }, { it }))) {
            val a = planOa[k]
            val b = diskOa[k]
            val gone = a == null && pn != null
            if ((a != null && offsetDiffers(a, b)) || gone) out += k.substringBefore('-') + " on " + k.substringAfter('-')
        }

        // [COMMS]: what the plan sets, and what both sides hold
        val pc = plan.comm
        val dc = disk?.comm
        if (pc?.comm1 != null && dc?.comm1 != null && pc.comm1 != dc.comm1) out += "COMM1"
        if (pc?.comm2 != null && dc?.comm2 != null && pc.comm2 != dc.comm2) out += "COMM2"
        // the Planner writes a TACAN and an ILS whatever the pilot did (WDP's 94X and 109.00 when the cartridge had none)
        if (pc?.tacan != null && dc?.tacan != null && pc.tacan != dc.tacan) out += "TACAN"
        if (pc?.ils != null && dc?.ils != null && (pc.ils != dc.ils || (pc.ilsCrs != null && dc.ilsCrs != null && pc.ilsCrs != dc.ilsCrs))) out += "ILS"

        // IFF codes, laser codes, bingo, ALOW, the MSL floor and the EWS names: only where the cartridge holds them too
        for ((key, a) in plan.iff) {
            val b = disk?.iff?.get(key) ?: continue
            if (!sameCode(a, b)) out += "IFF " + key.substringBefore(' ')
        }
        fun both(label: String, a: Int?, b: Int?) { if (a != null && b != null && a != b) out += label }
        both("LASER TGP", plan.laserTgp, disk?.laserTgp)
        both("LASER LST", plan.laserLst, disk?.laserLst)
        both("LASER ST", plan.laserSt, disk?.laserSt)
        both("BINGO", plan.bingoLbs, disk?.bingoLbs)
        both("ALOW", plan.alowFt, disk?.alowFt)
        both("MSL FLOOR", plan.mslFloorFt, disk?.mslFloorFt)
        if (plan.ewsNames.isNotEmpty() && disk?.ewsNames.orEmpty().isNotEmpty() && plan.ewsNames.map { it.trim() } != disk?.ewsNames.orEmpty().map { it.trim() }) out += "EWS"
        return out
    }

    /** Two frequencies as the parser writes them ("251.000"), compared as numbers. */
    private fun sameFreq(a: String, b: String): Boolean {
        val x = a.trim().toDoubleOrNull()
        val y = b.trim().toDoubleOrNull()
        return if (x != null && y != null) abs(x - y) < 0.0005 else a.trim() == b.trim()
    }

    /** Two codes (a PPT type, an IFF code): as numbers when both are ("0012" is 12), else as text without case. */
    private fun sameCode(a: String?, b: String?): Boolean {
        val x = a?.trim().orEmpty()
        val y = b?.trim().orEmpty()
        val xi = x.toIntOrNull()
        val yi = y.toIntOrNull()
        return if (xi != null && yi != null) xi == yi else x.equals(y, ignoreCase = true)
    }

    // ================================================================================================ parse and lay out

    /** The theater's names for the PPT codes (`SA-10` for `10`), the code kept in `code`. */
    internal fun resolve(d: Dtc, table: List<PptTable.Type>): Dtc = if (table.isEmpty()) d else d.copy(ppts = d.ppts.map { resolvePpt(it, table) })

    private fun resolvePpt(p: DtcPpt, table: List<PptTable.Type>): DtcPpt =
        PptTable.resolve(table, p.code)?.let { p.copy(name = it.name) } ?: p

    /**
     * The attack a cartridge's `[NAV OFFSETS]` describe, where the jet will put it: each line laid out from its own
     * steerpoint by true bearing and range (Dash-34 p.423: an OA from its steerpoint; p.424: VIP-TO-TGT from the VIP;
     * p.425: TGT-TO-VRP from the target; the pull-up point from the same reference). Steerpoint positions from the
     * cartridge, else from [route] (BMS's mission file, the flight's save). Null when nothing can be laid out.
     */
    internal fun attackFrom(dtc: Dtc, route: List<DtcPoint>, theater: String, profile: String? = null): AttackOverlay? {
        val nav = dtc.navOffsets ?: return null
        fun at(n: Int): Pair<Double, Double>? =
            (dtc.steerpoints.firstOrNull { it.n == n && (it.x != 0.0 || it.y != 0.0) } ?: route.firstOrNull { it.n == n && (it.x != 0.0 || it.y != 0.0) })
                ?.let { it.x to it.y }
        // the one attack drawing's model, laid out as every map lays a cartridge's attack (1.3.8): only the OA pair of
        // the mode's steerpoint, OA2 on OA1 once, the path by the profile when it is known
        return com.bmscompanion.app.data.mission.AttackDrawing.fromCartridge(nav, ::at, profile, theater)
    }

    /** BMS's believed route (its steerpoints), for laying out an attack whose steerpoints the cartridge leaves empty. */
    private fun currentRoutePoints(): List<DtcPoint> = safe(null) { MissionDtcFile.current() }?.steerpoints.orEmpty()

    // ================================================================================================ files

    /** The pilot's cartridge on disk, parsed and as text; null when there is none. */
    private fun diskCartridge(): Pair<Dtc, String>? = safe(null) {
        val f = Bridge.callsignIni?.let(::File)?.takeIf { it.isFile } ?: return@safe null
        val text = f.readText(Charsets.UTF_8)
        DtcParser.parseText(text).copy(modified = f.lastModified()) to text
    }

    private fun noCartridge(): String {
        val cs = Bridge.install.callsign?.takeIf { it.isNotBlank() }
        return if (cs == null) "Falcon BMS names no pilot on this PC, so there is no cartridge to send. Start BMS once and pick your pilot."
        else "There is no cartridge for $cs on this PC (User\\Config\\$cs.ini). Save the DTC once in BMS (or in the Planner), then send again."
    }

    private fun hasStpt(text: String): Boolean = text.lineSequence().any { it.trim().removePrefix("﻿").trim().equals("[STPT]", ignoreCase = true) }

    /**
     * Writes the plan files: [c] as the plan (null removes it) and [p] as the Undo (null removes it). A temporary file
     * beside each, then an atomic move, so a half-written plan is never read back. Null when both are on disk, else the
     * sentence.
     */
    private fun persist(c: Stored?, p: Stored?): String? {
        val dir = folder
        val oldPrev = synchronized(lock) { prev }
        var prevWritten = false
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return cannotKeep(dir, "the folder could not be made")
            write(File(dir, PREV_NAME), p)
            prevWritten = true
            write(File(dir, FILE_NAME), c)
            null
        } catch (e: Throwable) {
            // the Undo file was already replaced: put the one memory still holds back, so disk and memory agree
            if (prevWritten) runCatching { write(File(dir, PREV_NAME), oldPrev) }
            BridgeLog.warn("The plan could not be kept in ${dir.path}: ${e.message}")
            cannotKeep(dir, why(e))
        }
    }

    private fun cannotKeep(dir: File, reason: String) =
        "The plan could not be kept on the PC: $reason, in the settings folder (${dir.name}). Nothing was changed. " +
            "Check that Windows lets BMS Companion write its settings folder, then try again."

    private fun write(target: File, s: Stored?) {
        if (s == null) {
            if (target.exists()) Files.delete(target.toPath())
            return
        }
        val tmp = File(target.parentFile, target.name + ".tmp")
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
    }

    private fun readStored(f: File): Stored? {
        if (!f.isFile) return null
        return try {
            lenient.decodeFromString(Stored.serializer(), f.readText(Charsets.UTF_8)).takeIf { it.plan.id != 0L }
        } catch (e: Throwable) {
            BridgeLog.warn("${f.name} could not be read, and is left as it is: ${e.message}")
            null
        }
    }

    private fun ensureLoaded() {
        if (!loaded) load()
    }

    // ================================================================================================ small things

    private const val NO_BMS = "No Falcon BMS folder is known on this PC. Set it in BMS Companion's settings on the PC."

    /** A plan id: when it was sent (ms), and never the same as the one before. */
    private fun newId(): Long = synchronized(lock) { maxOf(System.currentTimeMillis(), lastId + 1).also { lastId = it } }

    /** [changedAt]'s next value: now, and always later than the last. Under [lock] or [changeLock]. */
    private fun next(): Long = maxOf(System.currentTimeMillis(), changedAt + 1)

    private fun answer(p: PlanOverlay) = ApiResponse.json(Bridge.json.encodeToString(PlanOverlay.serializer(), p))

    private fun error(status: Int, sentence: String) =
        ApiResponse.json("""{"error":${Bridge.json.encodeToString(String.serializer(), sentence)}}""", status)

    private fun describe(p: PlanOverlay): String =
        listOfNotNull(p.callsign, p.packageId?.let { "package $it" }, p.ref?.file, p.source.takeIf { it == PlanSource.FILES }?.let { "files" }).joinToString(", ").ifEmpty { "no flight named" }

    private fun ago(ms: Long): String = if (ms <= 0) "earlier" else "at ${clock(ms)}"

    private fun clock(ms: Long): String = stamp.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private fun why(e: Throwable): String = when (e) {
        is java.nio.file.AccessDeniedException -> "Windows refused access to ${e.file?.let { File(it).name } ?: "the file"}"
        is java.nio.file.FileSystemException -> (e.reason ?: e.javaClass.simpleName) + (e.file?.let { " (${File(it).name})" } ?: "")
        // "C:\…\planner-plan.json.tmp (Access is denied)": the reason, and the file's name without its folder
        is java.io.FileNotFoundException -> e.message.orEmpty().let { m ->
            val reason = Regex("\\(([^()]*)\\)\\s*$").find(m)?.groupValues?.get(1)
            val name = m.substringBefore(" (").substringAfterLast('\\').substringAfterLast('/').ifBlank { "the file" }
            "$name could not be written" + (reason?.let { " ($it)" } ?: "")
        }
        else -> (e.message ?: e.javaClass.simpleName).trimEnd('.')
    }

    private inline fun <T> safe(fallback: T, body: () -> T): T = try { body() } catch (_: Throwable) { fallback }
}
