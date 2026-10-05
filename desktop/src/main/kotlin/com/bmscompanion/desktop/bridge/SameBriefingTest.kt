package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.CampAto
import com.bmscompanion.app.data.mission.CampFiles
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.mission.MissionSourceInfo
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.data.mission.PopulateSend
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.hypot

/**
 * Part 12 of `--missiontest` (and `--samebrieftest <copy> <scratch APPDATA> out.txt` alone): **the same flight shows
 * the same mission in both modes** ([MissionSource]: a Populate of the flight BMS's printed briefing is for carries that
 * printed briefing). Against the copy, writing only the scratch APPDATA (the weather file's time is moved and put back):
 *
 * 1. EZBoards mode's `/api/mission` for the printed briefing; the save holding its flight (the Planner's own match,
 *    `briefed`), populated in WDP mode.
 * 2. The two briefings **section by section** — overview, situation, roster, package, threats, steerpoints, comms,
 *    ordnance, weather, support, ROE, emergency, alternate, raw sections, the time it was generated — all equal. The
 *    only differences allowed are the documented Planner-owned ones: `origin` ("printed" in the snapshot) and the
 *    weather when the save's `.twx` was saved after the print with other weather (`Populated.weatherFile` set).
 * 3. Merged as every view merges them ([PlanMerge]): the same briefing, neither "from the save", the same steerpoint
 *    rows (number, words, time), the same positions where both place one (the cartridge on disk in both), the same
 *    presets; the cartridge itself; the threat picture (the sites the Threat Analysis names). The tracks are reported.
 * 4. The weather rule: the `.twx` made older than the print keeps the printed weather; made newer, the save's wins only
 *    when it says other weather at take-off.
 * 5. Another flight of the same save (no print of it): the save's own briefing, as before.
 */
internal object SameBriefingTest {
    private val client = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    fun run(ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        line("")
        line("== 12. The same flight shows the same mission in both modes")
        fun req(method: String, path: String, query: Map<String, String> = emptyMap(), body: String = ""): Pair<Int, String> {
            val r = Bridge.handle(ApiRequest(method, path, query, body.toByteArray(Charsets.UTF_8)))
            return r.status to r.body.toString(Charsets.UTF_8)
        }
        fun mission() = client.decodeFromString(MissionData.serializer(), req("GET", "/api/mission").second)
        fun source(r: Pair<Int, String>) = PcAnswer.read(r.first, r.second) { client.decodeFromString(MissionSourceInfo.serializer(), it) }
        var twx: File? = null
        var twxTime = 0L
        try {
            ModeSwitchReset.forget()
            MissionGrounds.forget()
            source(req("POST", "/api/mission/source", mapOf("mode" to "ezboards")))
            // ---- 1. EZBoards mode, and the save holding the printed flight
            val ez = mission()
            val printed = ez.briefing
            if (ez.mode != MissionMode.EZBOARDS || printed == null) { line("   (no printed briefing in this copy: nothing to compare)"); return }
            line("   printed: ${printed.overview.flight}, package ${printed.overview.packageId}, generated ${printed.generated}")
            val files = runCatching { client.decodeFromString(CampFiles.serializer(), req("GET", "/api/campaign/files").second) }.getOrNull()
            val theater = files?.theaters?.firstOrNull { it.current }
            val file = theater?.files?.firstOrNull { it.briefed }
            ok(theater != null && file != null, "the save holding the printed flight: ${theater?.name} / ${file?.name}")
            if (theater == null || file == null) return
            val ato = runCatching {
                client.decodeFromString(CampAto.serializer(), req("GET", "/api/campaign/ato", mapOf("theater" to theater.name, "file" to file.name)).second)
            }.getOrNull()
            val rows = ato?.packages?.flatMap { it.flights }.orEmpty()
            val row = rows.firstOrNull { it.briefed }
            ok(row != null && row.callsign.trim().equals(printed.overview.flight?.trim(), ignoreCase = true), "its flight: ${row?.callsign} (${row?.id})")
            if (row == null) return
            val ref = CampRef(theater.name, file.name, row.id)
            val body = Bridge.json.encodeToString(PopulateSend.serializer(), PopulateSend(ref, seat = 0, from = "PC"))
            val sw = source(req("POST", "/api/mission/source", mapOf("mode" to "wdp")))
            val pop = source(req("POST", "/api/mission/populate", body = body))
            val wdp = mission()
            val p = wdp.populated
            ok(sw.ok && pop.ok && p != null && p.briefingFrom == PlanMerge.ORIGIN_PRINTED && p.briefingPrinted == ez.briefingModified,
                "populated in WDP mode: the snapshot carries the printed briefing (briefingFrom ${p?.briefingFrom}, printed ${p?.briefingPrinted})" + (pop.error?.let { " — $it" } ?: ""))
            p?.notes?.forEach { line("   note: $it") }
            val wb = wdp.briefing
            if (wb == null) { ok(false, "the snapshot has a briefing"); return }

            // ---- 2. section by section
            val wxOverlay = p?.weatherFile != null
            compare(printed, wb, wxOverlay, ok, line)
            ok(wb.origin == PlanMerge.ORIGIN_PRINTED && wdp.plan?.flight?.briefing == wb, "origin \"printed\" (the only marker), and the plan's flight carries the same briefing")

            // ---- 3. merged, as every view draws it
            val me = PlanMerge.merge(ez)
            val mw = PlanMerge.merge(wdp)
            fun strip(b: Briefing?) = b?.copy(origin = null, weather = if (wxOverlay) null else b.weather)
            ok(strip(me.briefing) == strip(mw.briefing) && !me.fromSave && !mw.fromSave && mw.printed != null,
                "merged: the same briefing in both modes, neither \"from the save\" (WDP printed ${mw.printed != null})")
            val rowsE = me.allSteerpoints.map { Triple(it.n, it.desc, it.time) }
            val rowsW = mw.allSteerpoints.filter { s -> s.desc != null || me.allSteerpoints.any { it.n == s.n } }.map { Triple(it.n, it.desc, it.time) }
            ok(rowsE == rowsW, "merged steerpoint rows: ${rowsE.size} in EZBoards mode, ${rowsW.size} in WDP mode, the same numbers, words and times" +
                (if (rowsE != rowsW) " — differ: ${(rowsE - rowsW.toSet()).take(4)} / ${(rowsW - rowsE.toSet()).take(4)}" else ""))
            val placedBoth = me.allSteerpoints.filter { it.hasPos }.mapNotNull { e -> mw.allSteerpoints.firstOrNull { it.n == e.n && it.hasPos }?.let { e to it } }
            val moved = placedBoth.filter { (e, w) -> hypot(e.x - w.x, e.y - w.y) > PlanMerge.SAME_FT }
            val onlyWdp = mw.allSteerpoints.filter { w -> w.hasPos && me.allSteerpoints.none { it.n == w.n && it.hasPos } }.map { it.n }
            val onlyEz = me.allSteerpoints.filter { e -> e.hasPos && mw.allSteerpoints.none { it.n == e.n && it.hasPos } }.map { it.n }
            // EZBoards mode places the printed flight from BMS's mission file, else from the save's own flight plan
            // (MissionData.route, fromSave), so both modes place the same steerpoints before 3D
            ok(moved.isEmpty() && onlyWdp.isEmpty() && onlyEz.isEmpty() && placedBoth.isNotEmpty(),
                "merged positions: ${placedBoth.size} steerpoints placed in both modes, the same place (EZBoards from ${ez.route?.let { if (it.fromSave) "the save's flight plan in ${it.save}" else "the mission file ${it.file}" } ?: "no route"}; " +
                    "sources EZBoards ${me.allSteerpoints.filter { it.hasPos }.map { it.source }.distinct()}, WDP ${mw.allSteerpoints.filter { it.hasPos }.map { it.source }.distinct()})" +
                    (if (moved.isNotEmpty()) " — moved: ${moved.take(4).map { (e, w) -> "STPT ${e.n} ${e.source}->${w.source}" }}" else "") +
                    (if (onlyWdp.isNotEmpty()) " — placed in WDP mode only: STPT $onlyWdp" else "") +
                    (if (onlyEz.isNotEmpty()) " — placed in EZBoards mode only: STPT $onlyEz" else ""))
            ok(sameLine(me, mw),
                "the route line on the map: STPT ${me.routeLine.map { it.n }} in EZBoards mode, ${mw.routeLine.map { it.n }} in WDP mode, the same points")
            ok(me.presets.map { Triple(it.band, it.ch, it.freq) } == mw.presets.map { Triple(it.band, it.ch, it.freq) }, "merged presets: the same ${me.presets.size}")
            val de = ez.dtc
            val dw = wdp.dtc
            ok(de == null && dw == null || de != null && dw != null && de.steerpoints.map { it.n to (it.x to it.y) } == dw.steerpoints.map { it.n to (it.x to it.y) } && de.uhf == dw.uhf && de.vhf == dw.vhf,
                "the cartridge: the same file in both (${de?.steerpoints?.size} steerpoints, ${de?.uhf?.size} UHF)")
            val ge = ez.ground
            val gw = wdp.ground
            if (ge != null) ok(gw != null && ge.threatsFrom == gw.threatsFrom && ge.airDefences.map { it.system to (it.x to it.y) }.toSet() == gw.airDefences.map { it.system to (it.x to it.y) }.toSet(),
                "the threat picture: ${ge.airDefences.size} sites from ${ge.threatsFrom} in EZBoards mode, ${gw?.airDefences?.size} from ${gw?.threatsFrom} in WDP mode")
            else line("   (EZBoards mode has no threat picture for this print)")
            // the tanker and AWACS tracks: EZBoards mode believes the save through the printed flight's route (the
            // mission file's, else the save's own), WDP mode through the populated flight's — the same save, the same flight
            fun tracks(d: MissionData) = d.tracks.map { t -> listOf(t.role, t.callsign, t.yours, t.points.map { it.x to it.y }) }.sortedBy { it.toString() }
            ok(tracks(ez) == tracks(wdp) && ez.tracks.isNotEmpty() == wdp.tracks.isNotEmpty(),
                "tracks: EZBoards ${ez.tracks.map { "${it.role} ${it.callsign}" + (if (it.yours) " (yours)" else "") }}, WDP ${wdp.tracks.map { "${it.role} ${it.callsign}" + (if (it.yours) " (yours)" else "") }}, the same tracks and points")
            fallback(theater.name, file.name, printed, mw, wdp, ok, line) {
                client.decodeFromString(MissionData.serializer(), req("GET", "/api/mission", mapOf("source" to "bms")).second)
            }

            // ---- 4. the weather rule
            twx = File(Theaters.of(Bridge.install)?.let { s -> s.byName(theater.name)?.let { s.campaignDir(it) } } ?: File("."), file.name.substringBeforeLast('.') + ".twx").takeIf { it.isFile }
            val tw = twx
            val printedAt = ez.briefingModified
            if (tw != null && printedAt > 0 && printed.weather?.rows?.isNotEmpty() == true) {
                twxTime = tw.lastModified()
                tw.setLastModified(printedAt - 60_000)
                val older = source(req("POST", "/api/mission/populate", body = body))
                val wo = mission()
                ok(older.ok && wo.populated?.weatherFile == null && wo.briefing?.weather == printed.weather,
                    "the save's weather file older than the print: the printed weather is kept")
                tw.setLastModified(printedAt + 60_000)
                val newer = source(req("POST", "/api/mission/populate", body = body))
                val wn = mission()
                val read = SaveWeather.read(File(tw.parentFile, file.name), wdp.plan?.flight ?: com.bmscompanion.app.data.mission.CampFlight(), theater.appTheater, null, row.id)
                val pc = com.bmscompanion.app.ui.screens.wdp.CardWeather.column(printed, 0)
                val fc = read.table?.let { com.bmscompanion.app.ui.screens.wdp.CardWeather.column(printed.copy(weather = it), 0) }
                val differs = pc != null && fc != null && !com.bmscompanion.app.ui.screens.wdp.CardWeather.sameWeather(fc, pc)
                ok(newer.ok && if (differs) wn.populated?.weatherFile == tw.name && wn.briefing?.weather?.source?.startsWith("From ${tw.name}") == true &&
                    wn.populated?.notes?.any { it.contains("after the briefing was printed") } == true
                else wn.populated?.weatherFile == null && wn.briefing?.weather == printed.weather,
                    "the save's weather file newer than the print: " + (if (differs) "it says other weather, so the briefing shows the save's (${wn.briefing?.weather?.source})" else "the same weather at take-off, so BMS's forecast is kept"))
                tw.setLastModified(twxTime)
                twx = null
            } else line("   (no weather file beside ${file.name}, or a print with no weather: the weather rule not checked)")

            // ---- 5. another flight of the same save: no print of it, the save's own briefing
            val other = rows.firstOrNull { !it.briefed && it.f16 && it.callsign.isNotBlank() } ?: rows.firstOrNull { !it.briefed }
            if (other != null) {
                val po = source(req("POST", "/api/mission/populate", body = Bridge.json.encodeToString(PopulateSend.serializer(), PopulateSend(CampRef(theater.name, file.name, other.id), seat = 0))))
                val mo = mission()
                val merged = PlanMerge.merge(mo)
                ok(po.ok && mo.populated?.briefingFrom == PlanMerge.ORIGIN_SAVE && mo.briefing?.origin == PlanMerge.ORIGIN_SAVE && merged.fromSave && merged.printed == null,
                    "another flight of the save (${other.callsign}, not printed): the save's own briefing, nothing printed under it" + (po.error?.let { " — $it" } ?: ""))
            }
        } catch (e: Throwable) {
            ok(false, "part 12 threw: $e")
        } finally {
            twx?.let { f -> runCatching { f.setLastModified(twxTime) } }
            runCatching { source(req("POST", "/api/mission/source", mapOf("mode" to "ezboards"))) }
            ModeSwitchReset.forget()
        }
    }

    /**
     * Part 12, 3b: EZBoards mode with BMS's mission file **not believed** falls back on the printed flight's own flight
     * plan in the save ([MissionDtcFile.fromSave]) — the populated flight's waypoints, the same route line and the same
     * tracks as WDP mode ([wdp], merged [mw]) — and only when the save provably holds the printed flight (four
     * briefings that must not use it). The copy's mission file is renamed for a moment and put back.
     */
    private fun fallback(
        theaterName: String, saveName: String, printed: Briefing, mw: com.bmscompanion.app.data.mission.MergedMission, wdp: MissionData,
        ok: (Boolean, String) -> Unit, line: (String) -> Unit, bms: () -> MissionData,
    ) {
        line("   -- BMS's mission file not believed: EZBoards mode places the printed flight from the save's own flight plan")
        val set = Theaters.of(Bridge.install)
        val t = set?.byName(theaterName)
        val dir = t?.let { set.campaignDir(it) }
        if (set == null || t == null || dir == null) { ok(false, "the theater and its campaign folder ($theaterName)"); return }
        val names = CampaignArchive.names(set, t)
        val save = CampaignArchive.cached(File(dir, saveName), names)
        val flight = wdp.plan?.flight?.route.orEmpty()
        fun same(r: com.bmscompanion.app.data.mission.MissionRoute?) = r != null && r.steerpoints.size == minOf(flight.size, 24) &&
            r.steerpoints.all { p -> flight.getOrNull(p.n - 1)?.let { hypot(it.x - p.x, it.y - p.y) <= MissionDtcFile.SAME_POINT_FT } == true }
        val v = MissionDtcFile.check(save, null, printed, names.strings, t.name)
        ok(!v.believed && v.saveRoute?.fromSave == true && same(v.saveRoute),
            "no mission file: the save's flight plan, ${v.saveRoute?.steerpoints?.size} waypoints at the populated flight's places — ${v.saveReason}")
        // what must not use the save
        val cs = printed.overview.flight?.trim().orEmpty()
        val noFlight = printed.copy(`package` = printed.`package`.map { if (it.callsign.trim().equals(cs, ignoreCase = true) || it.primary) it.copy(flightId = "99999") else it })
        val noPackage = printed.copy(overview = printed.overview.copy(packageId = null))
        val timed = printed.steerpoints.firstOrNull { it.n == 2 && it.time != null }
        val retimed = printed.copy(steerpoints = printed.steerpoints.map { if (it.n == 2) it.copy(time = "00:00:01z") else it })
        val shorter = printed.copy(steerpoints = printed.steerpoints.dropLast(1))
        for ((what, b) in listOf("another flight number" to noFlight, "no package number" to noPackage, "a row fewer" to shorter) +
            (if (timed != null) listOf("STPT 2 at another time" to retimed) else emptyList())) {
            val n = MissionDtcFile.check(save, null, b, names.strings, t.name)
            ok(n.saveRoute == null, "a briefing with $what: no route from the save (${n.saveReason})")
        }
        // end to end: the bridge's own look with the copy's mission file out of the way
        val root = Bridge.install.baseDir?.let(::File)
        val guard = root?.let { DevGuard.why(it) }
        if (root == null || guard != null) { line("   (the end-to-end half renames the copy's mission file, and ${root?.path} is not a copy: $guard)"); return }
        val ini = MissionDtcFile.iniFor(save)
        val hidden = ini?.let { File(it.parentFile, it.name + ".bmsc-hidden") }
        try {
            if (ini != null && hidden != null && !ini.renameTo(hidden)) { ok(false, "the copy's ${ini.name} could be moved aside"); return }
            MissionDtcFile.forget()
            MissionGrounds.forget()
            val e = bms()
            val me = PlanMerge.merge(e)
            ok(e.route?.fromSave == true && same(e.route) && me.allSteerpoints.filter { it.hasPos }.all { it.source == com.bmscompanion.app.data.mission.PlanItemSource.SAVE || it.source == com.bmscompanion.app.data.mission.PlanItemSource.CARTRIDGE },
                "/api/mission?source=bms with ${ini?.name ?: "no mission file"} aside: route from the save (${e.route?.save}, ${e.route?.steerpoints?.size} waypoints), marked ${me.allSteerpoints.filter { it.hasPos }.map { it.source }.distinct()}")
            ok(sameLine(me, mw),
                "its route line: STPT ${me.routeLine.map { it.n }}, WDP mode's ${mw.routeLine.map { it.n }}, the same points")
            fun tracks(d: MissionData) = d.tracks.map { t -> listOf(t.role, t.callsign, t.yours, t.points.map { it.x to it.y }) }.sortedBy { it.toString() }
            ok(tracks(e) == tracks(wdp), "its tracks: ${e.tracks.map { "${it.role} ${it.callsign}" }}, the same as WDP mode's ${wdp.tracks.size}")
        } finally {
            if (ini != null && hidden != null && hidden.isFile && !ini.exists()) hidden.renameTo(ini)
            ok(ini == null || ini.isFile, "the copy's ${ini?.name ?: "mission file"} is put back")
            MissionDtcFile.forget()
            MissionGrounds.forget()
        }
    }

    /**
     * The same route line: the same steerpoint numbers, each within [MissionDtcFile.SAME_POINT_FT] (BMS's mission file
     * writes a cell's middle as a float, the save's flight plan has it exact).
     */
    private fun sameLine(a: com.bmscompanion.app.data.mission.MergedMission, b: com.bmscompanion.app.data.mission.MergedMission): Boolean =
        a.routeLine.isNotEmpty() && a.routeLine.map { it.n } == b.routeLine.map { it.n } &&
            a.routeLine.zip(b.routeLine).all { (p, q) -> hypot(p.x - q.x, p.y - q.y) <= MissionDtcFile.SAME_POINT_FT }

    /** The two briefings section by section: one PASS/FAIL line each. */
    private fun compare(e: Briefing, w: Briefing, wxOverlay: Boolean, ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        val sections = listOf<Triple<String, Any?, Any?>>(
            Triple("generated", e.generated, w.generated),
            Triple("overview", e.overview, w.overview),
            Triple("situation", e.situation, w.situation),
            Triple("roster", e.roster, w.roster),
            Triple("package", e.`package`, w.`package`),
            Triple("threats", e.threats, w.threats),
            Triple("steerpoints", e.steerpoints, w.steerpoints),
            Triple("comms", e.comms, w.comms),
            Triple("ordnance", e.ordnance, w.ordnance),
            Triple("support", e.support, w.support),
            Triple("roe", e.roe, w.roe),
            Triple("emergency", e.emergency, w.emergency),
            Triple("alternate", e.alternate, w.alternate),
            Triple("sections", e.sections, w.sections),
        )
        val differ = sections.filter { it.second != it.third }.map { it.first }
        ok(differ.isEmpty(), "briefing sections: ${sections.size - differ.size} of ${sections.size} equal" +
            (if (differ.isEmpty()) " (overview, situation, roster, package, threats, ${e.steerpoints.size} steerpoint rows, ${e.comms.size} comm rows, ordnance, support, ROE, emergency, alternate, ${e.sections.size} raw sections)" else " — differ: $differ"))
        if (wxOverlay) ok(w.weather != null && w.weather?.source != null, "weather: the save's (a documented overlay: saved after the print with other weather) — ${w.weather?.source}")
        else ok(e.weather == w.weather, "weather: the printed forecast in both (${e.weather?.rows?.size ?: 0} rows)")
    }
}
