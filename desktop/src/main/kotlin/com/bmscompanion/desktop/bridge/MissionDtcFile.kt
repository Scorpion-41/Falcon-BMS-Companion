package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.DtcPoint
import com.bmscompanion.app.data.mission.MissionRoute
import java.io.File
import kotlin.math.hypot

/**
 * Falcon BMS's own mission file (`<campaigndir>/<SaveFile>.ini` beside the newest save), which holds the briefed
 * flight's route in 4.38.1 while the pilot's cartridge holds none. `MissionData.route` carries it only when it is
 * provably BMS's file for the briefed flight (R3-PLAN A11):
 * 1. its route rows match the printed briefing in count and in action words ([DtcRoute.mismatch]; a Nav point's word
 *    may be its waypoint's route action in the save, which BMS prints, [DtcRoute.printedWord]), **and**
 * 2. every route point equals the briefed flight's waypoint in that save — the grid cell + ½, which is exactly what BMS
 *    writes there — within [SAME_POINT_FT].
 *
 * Anything else — a file WDP rewrote (its campaign save copies the cartridge's empty steerpoints over BMS's route), a
 * file BMS rewrote under the same name for another flight (`Save-Day  1 04 11 35.ini` holds another flight's route
 * four days after its `.cam`), a mission file with no printed briefing to hold it against — is left out, and reaches
 * the app only through the Planner's Send to Mission ("Files on the PC"). In its place `MissionData.route` then carries
 * the printed flight's own flight plan in the save ([fromSave], [shown]) when the save provably holds that flight —
 * what WDP mode's snapshot places — so both modes show the same steerpoints and tracks before 3D.
 *
 * **Which file.** The save is [Theaters.TheaterSet.newestSave] of the theater BMS is set to (settled, never a start).
 * The mission file is named by that save's own campaign header (`SaveFile`, as WDP does: `campaigndir + SaveFile +
 * ".ini"`), not by the `[MISSION] title` inside it — `TE_BMS_03_F-16_DEAD.ini` is titled `TE_BMS_03_F-16_SEAD`.
 *
 * Read-only; nothing here throws. The files are looked at again at most every [LOOK_EVERY_MS] and the verdict is only
 * worked out again when one of them (the save, the mission file, the briefing, the theater's names) has changed, so
 * `/api/info` and `/api/mission` can ask on every call. Never call it while holding `Bridge`'s file lock: it asks
 * [Bridge.currentBriefing].
 */
object MissionDtcFile {
    /**
     * How far a mission-file point may sit from the flight's waypoint and still be BMS's own. BMS writes the cell's
     * middle as a float (good to an eighth of a foot at two million feet), so 2 ft is float noise with room to spare,
     * and far below the 1,640 ft a hand-placed or recon position would move it.
     */
    const val SAME_POINT_FT = 2.0

    /** The cartridge holds STPT 1-24; a longer flight's rows past 24 cannot be in the file. */
    private const val MOST_ROUTE_POINTS = 24

    /** How often the files are looked at again, at most. */
    private const val LOOK_EVERY_MS = 2000L

    /**
     * What was found and what was decided. [route] is what the mission file holds, checked or not (null when there is
     * no file); [believed] says whether it is BMS's own for the briefed flight, and [reason] why not — or, when it is,
     * what it was matched against. [flight] names the briefed flight as found in the save ("Cyborg6 #7289, package
     * 7288"); [holder] names the flight of the save whose route the file does hold, when that is another one.
     */
    class Verdict(
        val theater: String? = null,
        val save: File? = null,
        val saveFile: String? = null,
        val ini: File? = null,
        val dtc: Dtc? = null,
        val route: MissionRoute? = null,
        val flight: String? = null,
        val holder: String? = null,
        val believed: Boolean = false,
        val reason: String,
        /**
         * When the mission file is not believed: the printed flight's own flight plan in [save] ([fromSave]), or null
         * when the save does not provably hold it; [saveReason] says which, and why.
         */
        val saveRoute: MissionRoute? = null,
        val saveReason: String? = null,
    ) {
        /** The believed mission file's route, or null. */
        val believedRoute: MissionRoute? get() = route.takeIf { believed }

        /** The route `MissionData.route` carries: the believed mission file's, else the save's flight plan, else null. */
        val shownRoute: MissionRoute? get() = believedRoute ?: saveRoute

        /** `BriefingStatus.routeModified`: moves whenever the shown route may have; 0 when there is none. */
        val modified: Long
            get() = when {
                believed -> maxOf(safe(0L) { ini?.lastModified() ?: 0L }, safe(0L) { save?.lastModified() ?: 0L }, 1L)
                saveRoute != null -> maxOf(safe(0L) { save?.lastModified() ?: 0L }, 1L)
                else -> 0L
            }

        /** This verdict with the save's flight plan of the printed flight added ([fromSave]). */
        fun withSave(r: MissionRoute?, why: String): Verdict =
            Verdict(theater, save, saveFile, ini, dtc, route, flight, holder, believed, reason, r, why)
    }

    // ------------------------------------------------------------------------------------------------ for the bridge

    /** The believed mission file's route, or null. Cheap to call. */
    fun current(): MissionRoute? = look().believedRoute

    /**
     * What `MissionData.route` carries before 3D in EZBoards mode: the believed mission file's route, else the printed
     * flight's own flight plan in the save ([fromSave]), else null. Cheap to call: `/api/mission` asks on every fetch.
     */
    fun shown(): MissionRoute? = look().shownRoute

    /** Changes whenever [shown] does; 0 = none. `BriefingStatus.routeModified`. */
    val modified: Long get() = look().modified

    /** The last verdict, with its reason (for the log and the checks). */
    fun verdict(): Verdict = look()

    /**
     * The mission file beside the save being flown **as it is**, checked or not: what Send to Mission's "Files on the
     * PC" snapshots (a file WDP rewrote is exactly what a pilot planning in WDP wants to send). Null when there is no
     * save or no mission file beside it.
     */
    fun snapshot(): MissionRoute? = look().route

    private var last: Verdict? = null
    private var lastStamp: String? = null
    private var lookedAt = 0L

    /** Forgets the cached verdict (a check that changes the settings between two looks). */
    @Synchronized
    fun forget() {
        last = null; lastStamp = null; lookedAt = 0L
    }

    @Synchronized
    private fun look(): Verdict {
        val now = System.currentTimeMillis()
        last?.let { if (now - lookedAt < LOOK_EVERY_MS) return it }
        lookedAt = now
        val v = try {
            compute()
        } catch (e: Throwable) {
            Verdict(reason = "BMS's mission file could not be read: ${why(e)}.")
        }
        if (v !== last && (last == null || last?.believed != v.believed || last?.reason != v.reason || last?.saveReason != v.saveReason)) {
            if (v.believed) BridgeLog.info("Mission file ${v.ini?.name} is BMS's own route for ${v.flight}: steerpoints shown before 3D")
            else {
                if (v.ini != null) BridgeLog.info("Mission file ${v.ini.name} not used: ${v.reason}")
                v.saveReason?.let { BridgeLog.info(if (v.saveRoute != null) "Steerpoints before 3D from the save: $it" else "No steerpoints from the save either: $it") }
            }
        }
        last = v
        return v
    }

    /** Works the verdict out again only when a file it depends on has changed. */
    private fun compute(): Verdict {
        val install = Bridge.install
        val set = Theaters.of(install) ?: return Verdict(reason = "No Falcon BMS folder is known on this PC.")
        val t = set.current(install.theater)
            ?: return Verdict(reason = "The theater Falcon BMS is set to (${install.theater ?: "none"}) is not in its theater list.")
        val file = set.newestSave(t) ?: return Verdict(theater = t.name, reason = "${t.name} has no campaign or TE save yet.")
        val names = CampaignArchive.names(set, t)
        val save = CampaignArchive.cached(file, names)
        val ini = iniFor(save)
        val briefing = Bridge.currentBriefing()
        val stamp = listOf(t.name, CampaignArchive.key(file), ini?.let(CampaignArchive::key) ?: "-", Bridge.briefingModified.toString(), names.key).joinToString("#")
        last?.let { if (stamp == lastStamp) return it }
        lastStamp = stamp
        return check(save, ini, briefing, names.strings, t.name)
    }

    // ------------------------------------------------------------------------------------------------ the rules

    /**
     * The mission file BMS keeps beside [save]: `<SaveFile>.ini` in the save's own folder, `SaveFile` from the save's
     * campaign header (the file's own name when the header gives none, or gives something that is not a plain name),
     * matched without case. Null when there is no such file.
     */
    fun iniFor(save: CampaignArchive.Save): File? = safe(null) {
        val dir = save.file.absoluteFile.parentFile ?: return@safe null
        val name = saveFileName(save)
        val exact = File(dir, "$name.ini")
        if (exact.isFile) return@safe exact
        dir.listFiles()?.firstOrNull { it.isFile && it.name.equals("$name.ini", ignoreCase = true) }
    }

    /** The name the save's header gives its mission file (`SaveFile`), or the save file's own name. */
    fun saveFileName(save: CampaignArchive.Save): String {
        val own = save.file.name.substringBeforeLast('.')
        val said = save.header?.saveFile?.trim().orEmpty()
        return if (said.isEmpty() || said.any { it == '\\' || it == '/' || it == ':' } || said.contains("..")) own else said
    }

    /**
     * What the mission file [ini] holds, as `MissionData.route` carries it: every `target_n` as BMS wrote it (route and
     * precision targets both), the PPTs, lines and weapon targets, and the bullseye from [save]'s own header.
     */
    fun read(save: CampaignArchive.Save, ini: File, dtc: Dtc): MissionRoute {
        val bull = bullseye(save.header)
        return MissionRoute(
            file = ini.name,
            save = save.file.name,
            kind = save.kind,
            modified = safe(0L) { ini.lastModified() },
            steerpoints = dtc.steerpoints,
            ppts = dtc.ppts,
            lines = dtc.lines,
            weaponTargets = dtc.weaponTargets,
            bullseyeX = bull?.first,
            bullseyeY = bull?.second,
        )
    }

    /**
     * The campaign header's bullseye (`BullseyeX`/`BullseyeY`, whole grid cells): X is **east**, Y is **north**, and
     * the point is the **middle** of that cell, as every campaign position is (BMS's `GridToSim`). Returned as (north,
     * east) in theater feet, the protocol's x and y. Null with no header, or a header that never set one (0, 0).
     *
     * Checked against BMS 4.38.1's own ACMI, which records the bullseye object: Korea's header (X 310, Y 650) is
     * U 310418.62 m, V 650329.50 m there, which is (310.5, 650.5) cells at 3279.98 ft a cell and 3.28084 ft/m;
     * Israel's and Hellas's ACMI bullseyes sit on a cell's middle to the centimetre too. WDP reads the axes the
     * same way but adds no half cell (`cntDataCard.cs:44074-44076`, `fclsMain.cs:22489-22490`), so its bullseye row
     * lies half a cell (0.38 nm) south-west of the sim's (D62).
     */
    fun bullseye(header: CampaignArchive.Header?): Pair<Double, Double>? {
        if (header == null || (header.bullseyeX == 0 && header.bullseyeY == 0)) return null
        return (header.bullseyeY + 0.5) * CampaignArchive.FEET_PER_CELL to (header.bullseyeX + 0.5) * CampaignArchive.FEET_PER_CELL
    }

    /**
     * The A11 test of [ini] beside [save] against the printed [briefing], with the theater's `Strings.txt` for the
     * action words; when the file is not believed, the printed flight's own flight plan in [save] ([fromSave]) as the
     * fallback. Never throws; the verdict says why a file is not believed, and why the save is or is not used.
     */
    fun check(save: CampaignArchive.Save, ini: File?, briefing: Briefing?, strings: Map<Int, String>, theater: String? = null): Verdict {
        val v = checkIni(save, ini, briefing, strings, theater)
        if (v.believed) return v
        val (r, why) = try {
            fromSave(save, briefing, strings)
        } catch (e: Throwable) {
            null to "${save.file.name} could not be read for the flight plan: ${why(e)}."
        }
        return v.withSave(r, why)
    }

    private fun checkIni(save: CampaignArchive.Save, ini: File?, briefing: Briefing?, strings: Map<Int, String>, theater: String? = null): Verdict {
        val saveFile = saveFileName(save)
        fun no(reason: String, dtc: Dtc? = null, route: MissionRoute? = null, flight: String? = null, holder: String? = null) =
            Verdict(theater, save.file, saveFile, ini, dtc, route, flight, holder, false, reason)
        if (save.header == null && save.error != null) return no(save.error!!)
        if (ini == null) return no("There is no mission file $saveFile.ini beside ${save.file.name}.")
        val dtc = try {
            DtcParser.parse(ini)
        } catch (e: Throwable) {
            return no("${ini.name} could not be read: ${why(e)}.")
        }
        val route = read(save, ini, dtc)
        val points = DtcRoute.points(dtc)
        val holder = holderOf(save, points)
        val holds = holder?.let { " (it holds $it's route)" }.orEmpty()
        if (briefing == null || briefing.steerpoints.isEmpty()) {
            return no("There is no printed briefing to check ${ini.name} against: press PRINT in Falcon BMS.", dtc, route, holder = holder)
        }
        val flight = briefedFlight(save, briefing)
        // the briefing prints a Nav point's route action ("SEAD" either side of a SEAD point), which the file does not
        // carry: the save's waypoint gives it, and only for the waypoint whose action the file has (the points must
        // then equal the waypoints anyway, below)
        DtcRoute.mismatch(points, briefing.steerpoints, strings) { p ->
            flight?.waypoints?.getOrNull(p.n - 1)?.takeIf { it.action == p.action }?.let { DtcRoute.printedWord(it.action, it.routeAction, strings) }
        }?.let {
            return no("${ini.name} is not the briefed flight's route: $it$holds.", dtc, route, holder = holder)
        }
        val who = briefedName(briefing)
        if (flight == null) {
            return no("${save.file.name} does not hold the flight in the printed briefing ($who).", dtc, route, holder = holder)
        }
        val label = label(save, flight)
        val want = minOf(flight.waypoints.size, MOST_ROUTE_POINTS)
        if (points.size != want) {
            return no("${ini.name} has ${points.size} route points and $label has ${flight.waypoints.size} waypoints in ${save.file.name}$holds.", dtc, route, label, holder)
        }
        for (p in points) {
            val wp = flight.waypoints.getOrNull(p.n - 1)
                ?: return no("STPT ${p.n} of ${ini.name} has no waypoint of $label in ${save.file.name}$holds.", dtc, route, label, holder)
            val d = hypot(p.x - wp.north, p.y - wp.east)
            if (d > SAME_POINT_FT) {
                return no("STPT ${p.n} of ${ini.name} is ${feet(d)} from $label's waypoint in ${save.file.name}$holds.", dtc, route, label, holder)
            }
        }
        return Verdict(theater, save.file, saveFile, ini, dtc, route, label, holder, true,
            "${ini.name} is $label's route in ${save.file.name}: ${points.size} points, as briefed, each within ${SAME_POINT_FT.toInt()} ft of the save's waypoint")
    }

    /**
     * The fallback when the mission file is not believed: the printed flight's own flight plan in [save], as a
     * [MissionRoute] with `fromSave` — each waypoint at its cell's middle (what BMS writes into the mission file, and
     * what WDP mode's snapshot places), the action as the save has it, the header's bullseye. Only when [save]
     * **provably holds the printed flight**:
     * 1. the briefing names it three ways — callsign, package number and flight number (as [MissionGrounds] asks) —
     *    and [save] holds a flight with all three ([briefedFlight]);
     * 2. the briefing's steerpoint table is that flight's: as many rows as waypoints, each row's word the one BMS
     *    prints for the waypoint ([DtcRoute.printedWord]) and each time the waypoint's arrival, to the second.
     *
     * The answer's sentence says what was used, or why nothing was. Never throws.
     */
    fun fromSave(save: CampaignArchive.Save, briefing: Briefing?, strings: Map<Int, String>): Pair<MissionRoute?, String> {
        if (briefing == null || briefing.steerpoints.isEmpty()) return null to "there is no printed briefing to find the flight by."
        if (save.header == null && save.error != null) return null to save.error!!
        val key = CampaignFiles.briefKey(briefing.copy(origin = null), 0L)
        if (key?.packageId == null || key.flightId == null) {
            return null to "the printed briefing does not name its flight three ways (callsign, package and flight number: ${briefedName(briefing)})."
        }
        val f = briefedFlight(save, briefing)
            ?: return null to "${save.file.name} does not hold the flight in the printed briefing (${key.callsign}, package ${key.packageId}, flight ${key.flightId})."
        val label = label(save, f)
        val rows = briefing.steerpoints
        if (rows.size != f.waypoints.size) {
            return null to "the briefing has ${rows.size} steerpoints and $label has ${f.waypoints.size} waypoints in ${save.file.name}."
        }
        rows.forEachIndexed { i, r ->
            val w = f.waypoints[i]
            if (r.n != i + 1) return null to "row ${i + 1} of the briefing is STPT ${r.n}."
            val want = DtcRoute.printedWord(w.action, w.routeAction, strings)?.trim()
            val got = (r.desc ?: "--").trim()
            if (want == null || !want.equals(got, ignoreCase = true)) {
                return null to "STPT ${i + 1}: $label's waypoint in ${save.file.name} is '${want ?: "?"}' but the briefing says '$got'."
            }
            val printed = r.time?.trim()?.removeSuffix("z")?.trim()?.takeIf { it.isNotEmpty() && it != "--" }
            if (printed != null && w.arrive > 0 && printed != CampaignFiles.hms(w.arrive)) {
                return null to "STPT ${i + 1}: the briefing says ${printed}z and $label's waypoint in ${save.file.name} ${CampaignFiles.hms(w.arrive)}z."
            }
        }
        val bull = bullseye(save.header)
        val route = MissionRoute(
            file = "",
            save = save.file.name,
            kind = save.kind,
            modified = safe(0L) { save.file.lastModified() },
            steerpoints = f.waypoints.take(MOST_ROUTE_POINTS).mapIndexed { i, w ->
                DtcPoint(n = i + 1, x = w.north, y = w.east, altFt = w.altFt, action = w.action)
            },
            bullseyeX = bull?.first,
            bullseyeY = bull?.second,
            fromSave = true,
        )
        return route to "$label's flight plan in ${save.file.name}: ${route.steerpoints.size} waypoints, the briefing's table row for row (words and times)."
    }

    /**
     * The flight of [save] the printed [briefing] is for: its callsign (`Cyborg6`), package number (`7288`, the
     * package's campaign id) and flight number (`7289`, the flight's campaign id, from the Package Elements row), each
     * where the briefing gives it. Null when the save does not hold it.
     */
    fun briefedFlight(save: CampaignArchive.Save, briefing: Briefing): CampaignArchive.Flight? {
        val o = briefing.overview
        val callsign = o.flight?.trim()?.takeIf { it.isNotEmpty() }
        val pkg = o.packageId?.trim()?.toIntOrNull()
        val element = briefing.`package`.firstOrNull { callsign != null && it.callsign.trim().equals(callsign, ignoreCase = true) }
            ?: briefing.`package`.firstOrNull { it.primary }
        val number = element?.flightId?.trim()?.toIntOrNull()
        if (callsign == null && number == null) return null
        val found = save.flights.filter { f ->
            (number == null || f.campId == number) &&
                (pkg == null || save.packageOf(f)?.campId == pkg) &&
                (callsign == null || save.names?.callsign(f)?.equals(callsign, ignoreCase = true) != false)
        }
        return found.firstOrNull { it.hasPlayer } ?: found.firstOrNull()
    }

    /** The flight of [save] whose route [points] is, cell for cell: whose file it really is. Null for none. */
    fun holderOf(save: CampaignArchive.Save, points: List<DtcPoint>): String? {
        if (points.isEmpty()) return null
        val f = save.flights.firstOrNull { f ->
            f.waypoints.size == points.size && points.all { p ->
                f.waypoints.getOrNull(p.n - 1)?.let { hypot(p.x - it.north, p.y - it.east) <= SAME_POINT_FT } == true
            }
        } ?: return null
        return label(save, f)
    }

    /** "Cyborg6 #7289, package 7288" */
    fun label(save: CampaignArchive.Save, f: CampaignArchive.Flight): String =
        (save.names?.callsign(f) ?: "flight ${f.id}") + " #${f.campId}" + (save.packageOf(f)?.let { ", package ${it.campId}" } ?: "")

    private fun briefedName(b: Briefing): String {
        val o = b.overview
        return listOfNotNull(o.flight?.trim()?.takeIf { it.isNotEmpty() }, o.packageId?.trim()?.takeIf { it.isNotEmpty() }?.let { "package $it" })
            .joinToString(", ").ifEmpty { "no flight named" }
    }

    private fun feet(d: Double): String = if (d < 10_000) "%.0f ft".format(d) else "%.1f nm".format(d / 6076.12)

    private fun why(e: Throwable): String = (e.message ?: e.javaClass.simpleName).trim().trimEnd('.')

    private inline fun <T> safe(fallback: T, body: () -> T): T = try { body() } catch (_: Throwable) { fallback }
}
