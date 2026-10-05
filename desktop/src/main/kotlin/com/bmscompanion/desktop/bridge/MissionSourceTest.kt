package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.AttackOverlay
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.CampAto
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.EzRun
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.mission.MissionSourceInfo
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.data.mission.PlanSource
import com.bmscompanion.app.data.mission.PopulateSend
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * `--missiontest <copy of a BMS folder> <scratch APPDATA> out.txt`: the Mission section's two modes ([MissionSource]),
 * in-process against the copy, with the snapshot kept in the scratch APPDATA.
 *
 * 1. **Settings**: a file from 1.3.7 (layout 1) and one from before the number existed are brought forward to EZBoards
 *    mode, each step once (the first keeps a pilot's kneeboards-on-PRINT choice, the second turns it on).
 * 2. **EZBoards mode**: `/api/info` says so and words the line; `/api/mission` is the printed briefing and nothing laid
 *    over it — byte for byte the Planner's `?source=bms`; Populate is refused; the VR boards' attack is empty, an attack
 *    an older Planner posts included; the Taxi page's choice is served for its mission and not after a new PRINT.
 * 3. **The switch** to WDP mode: instant, `planModified` moves (so every client refetches); before the first Populate
 *    `/api/mission` holds nothing but the mode; `?source=bms` still gives the printed briefing (the Planner's); GENERATE
 *    NOW and Run HTML Briefing are refused with the reason (both write the Planner's cockpit pages), an exported HTML
 *    Briefing page still served; a wrong mode, a missing flight and a wrong seat are refused in words.
 * 4. **Populate from Planner** with the flight the printed briefing is for: the save's briefing, the cartridge as saved,
 *    the flight's route placing its steerpoints (through [PlanMerge], as every view merges it), the tracks and the
 *    attack; a cartridge text sent along (unsaved edits) is not taken. `/api/mission` is exactly the kept snapshot, and
 *    the taxi choice of EZBoards mode is not served for it.
 *    The save's own weather file (`Auto Save.twx`) is the briefing's weather block, its take-off column the file's
 *    table of the type it is in, and the plan's flight carries the same block (every view shows it). Made a map-model
 *    save for a moment (its `.twx` model set to 3 beside an `Auto Save.fmap` made here), the three columns are the
 *    map's cells under the take-off field, the target and the landing field; the file is put back byte for byte.
 * 5. **Changed since**: touching the cartridge is noticed and nothing is re-read; a Populate again takes the new one.
 *    Touching the weather file is noticed as "weather file", a briefing printed after the Populate as "printed
 *    briefing" (both times are put back).
 * 6. **A restart** keeps the snapshot; **switching back** to EZBoards mode serves the printed briefing and keeps the
 *    snapshot for the return — `/api/mission` is then byte for byte BMS's own files with the same briefing and route as
 *    before the switch, nothing of the snapshot merged in, and the snapshot's taxi choice not served; a snapshot that
 *    cannot be written is refused and the old one stays.
 * 7. **Old and new clients**: a 1.3.7 `MissionData`/`BridgeInfo` decodes as EZBoards mode; this PC's answers decode
 *    with the clients' settings.
 * 8. **A new mission clears by itself** ([LeftoversTest]): Save to DTC for flight A, nothing drawn as a leftover, a
 *    PRINT of flight B clears exactly A's keys (the VIP among them; one BMS changed meanwhile left alone) with the note
 *    and its Undo, Undo, and the same flight printed again clearing nothing.
 * 9. **A switch clears every leftover** ([SwitchResetTest]): flight A planned in WDP mode, B printed, a switch to
 *    EZBoards mode clears every key the Planner wrote (A's and B's; BMS's left alone) and puts BMS's pages back over
 *    Upd Kneeboard's, Undo, the switch back discarding A's snapshot, a same-flight switch clearing B's own Planner key
 *    (its page kept), and another flight opened in the Planner in WDP mode clearing A's keys with no page touched.
 * 10. **One mission picture** ([MissionPictureTest]): EZBoards mode's `/api/mission` carries the printed flight's ground
 *    picture from its save (sites, units near the route and targets, package routes), and WDP mode's snapshot of the
 *    same flight the same picture.
 * 12. **The same mission in both modes** ([SameBriefingTest]): the printed flight populated in WDP mode carries the
 *    printed briefing — section by section the same as EZBoards mode's, merged the same; the weather rule; another
 *    flight of the save the save's own briefing. `--samebrieftest` runs this part alone.
 *
 * The copy's files are hashed before and after: the cartridge, the briefing's time and the theater's kneeboard pages
 * are touched, and each is put back byte for byte with its time. Refuses a folder [DevGuard] does not call a copy, and an APPDATA under `AppData\Roaming`.
 */
internal object MissionSourceTest {
    private val client = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    /** [only] "same": part 12 alone (`--samebrieftest`), with the same setting up and putting back. */
    fun run(inputs: List<String>, only: String? = null): String {
        val out = StringBuilder()
        fun line(s: String) { out.append(s).append('\n') }
        fun ok(pass: Boolean, what: String) = line((if (pass) "PASS: " else "FAIL: ") + what)
        fun done(): String {
            val fails = out.lines().count { it.startsWith("FAIL") }
            line("")
            line(if (fails == 0) "ALL PASS" else "$fails FAIL")
            return out.toString()
        }
        val root = inputs.getOrNull(0)?.let(::File)
        val appdata = inputs.getOrNull(1)?.let(::File)
        if (root == null || !root.isDirectory || appdata == null) return "FAIL: usage: --missiontest <copy of a BMS folder> <scratch APPDATA> out.txt\n"
        DevGuard.why(root)?.let { return "FAIL: --missiontest touches the copy's cartridge (and puts it back), and ${root.path} is not a copy: $it\n" }
        if (Regex("(?i)[\\\\/]AppData[\\\\/]Roaming([\\\\/]|$)").containsMatchIn(appdata.absolutePath)) return "FAIL: ${appdata.path} looks like a real APPDATA: give a scratch folder\n"
        val dir = File(appdata, "BMS Companion")
        line("--missiontest: EZBoards mode and WDP mode against ${root.path}; snapshot in ${dir.path}")
        line("")

        if (only == null) settingsPart(::ok, ::line)

        val saved = Bridge.settings.value
        val sha0 = shaTree(root)
        line("")
        line("   ${sha0.size} files of the copy hashed (SHA-256) before")
        var cart: File? = null
        var cartBytes: ByteArray? = null
        var cartTime = 0L
        var pageDir: File? = null
        val pagesKept = File(appdata, "kb-pages-kept")
        try {
            dir.mkdirs()
            File(dir, MissionSource.FILE_NAME).delete()
            MissionSource.folderOverride = dir
            MissionSource.forgetForCheck()
            Bridge.update {
                it.copy(
                    BmsDirOverride = root.path, EzBoardsDir = null, AutoEzBoardsOnPrint = false, TacviewEnabled = false,
                    MissionSource = MissionMode.EZBOARDS,
                )
            }
            Bridge.startForCheck()
            val base = Bridge.install.baseDir?.let(::File)
            if (base == null || Theaters.canonical(base) != Theaters.canonical(root)) {
                ok(false, "the bridge reads the copy (it reads ${base?.path ?: "no folder"})")
                return done()
            }
            cart = Bridge.callsignIni?.let(::File)?.takeIf { it.isFile }
            cartBytes = cart?.readBytes()
            cartTime = cart?.lastModified() ?: 0L
            ok(cart != null, "the copy has the selected pilot's cartridge")
            // a switch of mode puts BMS's own kneeboard pages back over the other mode's (ModeSwitchReset): the
            // theater's page files are kept aside here and put back with their times at the end
            pageDir = runCatching { KneeboardPrint.place(root, Bridge.install.theater).dir }.getOrNull()
            pageDir?.listFiles { f -> f.isFile && f.name.endsWith(".dds", ignoreCase = true) }?.forEach { f ->
                pagesKept.mkdirs()
                f.copyTo(File(pagesKept, f.name), overwrite = true)
                File(pagesKept, f.name).setLastModified(f.lastModified())
            }
            if (only == null) {
                lifecycle(root, dir, cart, ::ok, ::line)
                // 8. what the Planner saved for another flight (LeftoversTest), back in EZBoards mode
                LeftoversTest.run(root, cart, ::ok, ::line)
                // 9. what a switch of mode resets by itself (SwitchResetTest)
                SwitchResetTest.run(root, cart, ::ok, ::line)
                // 10. one mission picture for every map (MissionPictureTest)
                MissionPictureTest.run(::ok, ::line)
                // 11. what each device starts afresh at a new mission and a switch of mode (MissionEpochTest)
                MissionEpochTest.run(::ok, ::line)
            }
            // 12. the same flight shows the same mission in both modes (SameBriefingTest)
            SameBriefingTest.run(::ok, ::line)
        } catch (e: Throwable) {
            ok(false, "the check threw: $e")
        } finally {
            runCatching {
                val d = pageDir
                if (d != null) pagesKept.listFiles()?.forEach { k ->
                    val f = File(d, k.name)
                    if (!f.isFile || !f.readBytes().contentEquals(k.readBytes())) k.copyTo(f, overwrite = true)
                    f.setLastModified(k.lastModified())
                }
                pagesKept.deleteRecursively()
            }
            runCatching { val c = cart; val b = cartBytes; if (c != null && b != null) { if (!c.readBytes().contentEquals(b)) c.writeBytes(b); c.setLastModified(cartTime) } }
            runCatching { Bridge.stop() }
            runCatching { Bridge.update { saved } }
            MissionSource.folderOverride = null
            MissionSource.forgetForCheck()
            MissionDtcFile.forget()
        }
        val sha1 = shaTree(root)
        // the Planner's own ledger (User\BMS Companion Planner\Ledger) is meant to change: it records what the run cleared
        val changed = (sha0.keys + sha1.keys).filter { sha0[it] != sha1[it] && !it.replace('\\', '/').contains("/BMS Companion Planner/Ledger/", ignoreCase = true) }
        line("")
        ok(changed.isEmpty(), "every file of the copy has the SHA-256 it had before (${sha1.size} files)" + (if (changed.isEmpty()) "" else ": changed ${changed.take(8)}"))
        ok(cart == null || cart.lastModified() == cartTime, "the cartridge's file time is put back")
        return done()
    }

    // ------------------------------------------------------------------------------------------------ 1. settings

    private fun settingsPart(ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        line("== 1. Settings brought forward")
        val file = File(Repo.settingsFolder, "bridge-settings.json")
        val before = if (file.isFile) file.readBytes() else null
        try {
            file.parentFile.mkdirs()
            // a 1.3.7 file: layout 1, kneeboards on PRINT turned off by the pilot, no source
            file.writeText("""{"Port":47474,"AutoEzBoardsOnPrint":false,"SettingsVersion":1}""")
            val a = BridgeSettings.load()
            ok(a.SettingsVersion == BridgeSettings.CURRENT && a.MissionSource == MissionMode.EZBOARDS && !a.AutoEzBoardsOnPrint,
                "a 1.3.7 file comes forward to layout ${a.SettingsVersion} in EZBoards mode, the pilot's kneeboards-on-PRINT off kept")
            val again = BridgeSettings.load()
            ok(again == a, "loading it again changes nothing (each step once)")
            // a file from before the number: step 1 runs too
            file.writeText("""{"Port":47474,"AutoEzBoardsOnPrint":false}""")
            val b = BridgeSettings.load()
            ok(b.SettingsVersion == BridgeSettings.CURRENT && b.AutoEzBoardsOnPrint && b.MissionSource == MissionMode.EZBOARDS,
                "a file with no SettingsVersion counts as layout 0: kneeboards on PRINT turned on once, EZBoards mode")
            // a file this build wrote in WDP mode stays in WDP mode
            file.writeText("""{"Port":47474,"MissionSource":"wdp","MissionSourceSince":5,"SettingsVersion":${BridgeSettings.CURRENT}}""")
            val c = BridgeSettings.load()
            ok(c.MissionSource == MissionMode.WDP && c.MissionSourceSince == 5L, "a current file keeps WDP mode as the pilot left it")
        } catch (e: Throwable) {
            ok(false, "the settings part threw: $e")
        } finally {
            runCatching { if (before != null) file.writeBytes(before) else file.delete() }
        }
    }

    // ------------------------------------------------------------------------------------------------ 2-6

    private fun lifecycle(root: File, dir: File, cart: File?, ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        fun req(method: String, path: String, query: Map<String, String> = emptyMap(), body: String = ""): Pair<Int, String> {
            val r = Bridge.handle(ApiRequest(method, path, query, body.toByteArray(Charsets.UTF_8)))
            return r.status to r.body.toString(Charsets.UTF_8)
        }
        fun info() = client.decodeFromString(BridgeInfo.serializer(), req("GET", "/api/info").second)
        fun mission(q: Map<String, String> = emptyMap()) = client.decodeFromString(MissionData.serializer(), req("GET", "/api/mission", q).second)
        fun source(r: Pair<Int, String>) = PcAnswer.read(r.first, r.second) { client.decodeFromString(MissionSourceInfo.serializer(), it) }

        // ---- 2. EZBoards mode
        line("")
        line("== 2. EZBoards mode")
        val i0 = info()
        ok(i0.mission.mode == MissionMode.EZBOARDS && !i0.ezBoards.suspended && i0.mission.line?.startsWith("From BMS briefing · ") == true,
            "/api/info: EZBoards mode, EZBoards not suspended, line \"${i0.mission.line}\"")
        val m0 = mission()
        ok(m0.mode == MissionMode.EZBOARDS && m0.briefing != null && m0.briefing?.origin == null && m0.plan == null && m0.populated == null,
            "/api/mission: the printed briefing (${m0.briefing?.overview?.flight}, package ${m0.briefing?.overview?.packageId}), nothing laid over it")
        val flightName = m0.briefing?.overview?.flight?.trim().orEmpty()
        val refusedEz = source(req("POST", "/api/mission/populate", body = "{}"))
        ok(refusedEz.status == 409 && refusedEz.error?.contains("WDP mode") == true, "Populate in EZBoards mode is refused: \"${refusedEz.error}\"")
        val att0 = client.decodeFromString(AttackOverlay.serializer(), req("GET", "/api/attack").second)
        // 1.3.8: EZBoards mode draws the cartridge's own attack (its NAV OFFSETS on BMS's route), the same as PlanMerge
        val cartAttack = PlanMerge.cartridgeAttack(m0)
        ok(att0.cues.isEmpty() == (cartAttack == null) && (cartAttack == null || com.bmscompanion.app.data.mission.AttackDrawing.same(att0, cartAttack)),
            "the VR boards' attack in EZBoards mode is the cartridge's own (${att0.cues.size} cues, ${att0.mode.ifEmpty { "none" }}), as the Mission map's")
        // EZBoards mode is BMS's own files and nothing else: the same answer, byte for byte, as the Planner's ?source=bms
        val ez0Text = req("GET", "/api/mission").second
        ok(ez0Text == req("GET", "/api/mission", mapOf("source" to "bms")).second && !m0.version.startsWith("wdp-") && m0.missionKey == "ez-${m0.briefingModified}",
            "/api/mission in EZBoards mode is exactly BMS's own files (?source=bms), key ${m0.missionKey}")
        // an attack an older Planner posts by itself is taken and never drawn
        req("POST", "/api/attack", body = """{"cues":[{"label":"T","north":1000.0,"east":1000.0,"kind":"TARGET"}]}""")
        ok(client.decodeFromString(AttackOverlay.serializer(), req("GET", "/api/attack").second).cues.none { it.label == "T" }, "an attack posted by an older Planner is not drawn in EZBoards mode")
        // the Taxi page's choice belongs to one mission: served for it, forgotten when BMS prints another
        fun taxi() = client.decodeFromString(com.bmscompanion.app.data.mission.TaxiSelection.serializer(), req("GET", "/api/taxi").second)
        req("POST", "/api/taxi", body = """{"airportId":7,"runway":"36","outbound":true,"spot":12,"at":1}""")
        ok(taxi().runway == "36" && taxi().spot == 12, "the Taxi page's choice is served for the mission it was made in")
        val brief = Bridge.briefingPath?.let(::File)?.takeIf { it.isFile }
        if (brief != null) {
            val bt = brief.lastModified()
            try {
                brief.setLastModified(bt + 60_000)
                ok(taxi().runway.isEmpty(), "a new PRINT (briefing.txt written again): the last mission's taxi choice is not served")
            } finally {
                brief.setLastModified(bt)
            }
            ok(taxi().runway == "36", "the same printed mission again: its choice is served again")
        }

        // ---- 3. the switch
        line("")
        line("== 3. Switch to WDP mode")
        val bad = source(req("POST", "/api/mission/source", mapOf("mode" to "sideways")))
        ok(bad.status == 400 && bad.error != null, "an unknown mode is refused: \"${bad.error}\"")
        val onPrint = Bridge.settings.value.AutoEzBoardsOnPrint
        val t0 = System.nanoTime()
        val sw = source(req("POST", "/api/mission/source", mapOf("mode" to "wdp")))
        val ms = (System.nanoTime() - t0) / 1_000_000
        ok(sw.ok && sw.value?.mode == MissionMode.WDP && sw.value?.populated == null && sw.value?.line == "From the Planner · not populated yet",
            "POST /api/mission/source?mode=wdp: WDP mode in $ms ms, \"${sw.value?.line}\"")
        ok(Bridge.settings.value.MissionSource == MissionMode.WDP, "the mode is kept in the PC's settings")
        val i1 = info()
        ok(i1.mission.wdp && i1.ezBoards.suspended && i1.ezBoards.autoOnPrint == onPrint && Bridge.settings.value.AutoEzBoardsOnPrint == onPrint,
            "/api/info: WDP mode, EZBoards suspended, the stored on-PRINT setting untouched (${i1.ezBoards.autoOnPrint})")
        ok(i1.briefing.planModified != i0.briefing.planModified, "briefing.planModified moved (${i0.briefing.planModified} -> ${i1.briefing.planModified}): an older client refetches")
        val m1 = mission()
        ok(m1.mode == MissionMode.WDP && m1.awaitingPopulate && m1.briefing == null && m1.dtc == null && m1.plan == null && m1.tracks.isEmpty() && m1.route == null,
            "/api/mission before Populate: the mode and nothing else (${MissionMode.NOT_POPULATED})")
        val merged1 = PlanMerge.merge(m1)
        ok(merged1.briefing == null && merged1.banner == null && merged1.steerpoints.isEmpty(), "merged, it is empty and has no banner")
        val raw = mission(mapOf("source" to "bms"))
        ok(raw.mode == MissionMode.EZBOARDS && raw.briefing?.overview?.flight == m0.briefing?.overview?.flight && raw.dtc != null,
            "/api/mission?source=bms: BMS's own files for the Planner (printed briefing, cartridge) in WDP mode")
        val gen = req("POST", "/api/ezboards/generate")
        val run = runCatching { client.decodeFromString(EzRun.serializer(), gen.second) }.getOrNull()
        ok(gen.first == 409 && run?.ok == false && run.message.startsWith(MissionMode.EZ_SUSPENDED), "GENERATE NOW is refused in WDP mode: \"${run?.message}\"")
        // html_brief's export writes cockpit pages 1-3 over the Planner's: not started in WDP mode, its pages still read
        val hb = req("POST", "/api/kneeboard/open")
        ok(hb.first == 409 && hb.second.contains(MissionMode.HTML_BRIEF_SUSPENDED) && info().kneeboard.runSuspended,
            "Run HTML Briefing is refused in WDP mode (${hb.first}) and /api/info says so (kneeboard.runSuspended)")
        val hbPage = req("GET", "/api/kneeboard/page", mapOf("i" to "0", "max" to "320"))
        ok(hbPage.first == 200 || hbPage.first == 404, "an exported HTML Briefing page is still served in WDP mode (${hbPage.first}: 404 = none exported in this copy)")
        val noFlight = source(req("POST", "/api/mission/populate", body = "{}"))
        ok(noFlight.status == 409 && noFlight.error?.contains("Open your flight in the Planner") == true, "Populate with no flight open: \"${noFlight.error}\"")

        // the flight the printed briefing is for, as the Planner's flight picker offers it
        val ts = Theaters.at(root)
        val korea = ts.byName("Korea KTO")
        val atoR = req("GET", "/api/campaign/ato", mapOf("theater" to "Korea KTO", "file" to "Auto Save.cam"))
        val ato = runCatching { client.decodeFromString(CampAto.serializer(), atoR.second) }.getOrNull()
        val row = ato?.packages?.flatMap { it.flights }?.let { rows -> rows.firstOrNull { it.briefed } ?: rows.firstOrNull { it.callsign.equals(flightName, true) } }
        ok(korea != null && row != null, "the Planner's flight: ${row?.callsign} (${row?.id}) in Korea KTO / Auto Save.cam")
        if (row == null) return
        val ref = CampRef("Korea KTO", "Auto Save.cam", row.id)
        val badSeat = source(req("POST", "/api/mission/populate", body = Bridge.json.encodeToString(PopulateSend.serializer(), PopulateSend(ref, seat = 7))))
        ok(badSeat.status == 400 && badSeat.error != null, "a seat of 7 is refused: \"${badSeat.error}\"")

        // ---- 4. Populate from Planner
        line("")
        line("== 4. Populate from Planner")
        // what an older or careless page might send along: unsaved edits as a cartridge text. Only the saved one counts.
        val body = """{"ref":{"theater":"Korea KTO","file":"Auto Save.cam","flight":"${row.id}"},"seat":0,"from":"PC","cartridge":"[STPT]\ntarget_0=1,2,3,0,X\n"}"""
        val t1 = System.nanoTime()
        val pop = source(req("POST", "/api/mission/populate", body = body))
        val popMs = (System.nanoTime() - t1) / 1_000_000
        val p = pop.value?.populated
        ok(pop.ok && p != null && p.callsign == row.callsign && p.save == "Auto Save.cam" && p.theater == "Korea KTO" && p.seat == 0 && p.from == "PC" && p.changed.isEmpty(),
            "populated in $popMs ms: ${p?.callsign}, ${p?.save}, cartridge ${if (p?.cartridge != null) "(the pilot's)" else "none"}, mission file ${p?.missionFile}, attack ${p?.attack}; line \"${pop.value?.line}\"")
        p?.notes?.forEach { line("   note: $it") }
        val m2 = mission()
        val disk = cart?.let { DtcParser.parse(it) }
        ok(m2.mode == MissionMode.WDP && m2.populated?.at == p?.at && m2.version == "wdp-${p?.at}", "/api/mission is the snapshot (${m2.version})")
        // the printed briefing's own flight: the snapshot carries that printed briefing (part 12 compares it section by
        // section); any other flight the save's own wording
        val printedCase = row.briefed && m0.briefing != null
        val wantOrigin = if (printedCase) PlanMerge.ORIGIN_PRINTED else PlanMerge.ORIGIN_SAVE
        ok(m2.briefing?.origin == wantOrigin && p?.briefingFrom == wantOrigin && m2.briefing?.overview?.flight?.trim().equals(row.callsign, true) && m2.briefingModified == 0L,
            "its briefing is ${if (printedCase) "BMS's printed one (the flight is the print's)" else "the save's own"} (${m2.briefing?.overview?.flight}, ${m2.briefing?.steerpoints?.size} steerpoint rows, situation ${m2.briefing?.situation?.length ?: 0} chars)")
        ok(disk != null && m2.dtc != null && m2.dtc?.steerpoints?.size == disk.steerpoints.size && m2.dtc?.uhf == disk.uhf && m2.dtc?.modified == cart.lastModified() &&
            m2.dtc?.steerpoints?.none { it.name == "X" } == true,
            "its cartridge is the one saved on disk (${m2.dtc?.steerpoints?.size} steerpoints, ${m2.dtc?.uhf?.size} UHF), not the text sent along")
        val plan = m2.plan
        ok(plan != null && plan.source == PlanSource.POPULATED && plan.applied && plan.ref == CampRef("Korea KTO", "Auto Save.cam", row.id) && plan.seat == 0 &&
            plan.flight?.route?.isNotEmpty() == true && plan.dtc.steerpoints.isEmpty() && plan.dtc.modified == 0L,
            "its plan carries the flight (${plan?.flight?.route?.size} waypoints), ref and seat, and no cartridge of its own")
        ok(m2.route?.save == "Auto Save.cam" && m2.route?.kind?.isNotEmpty() == true,
            "its route: ${m2.route?.file} (${m2.route?.steerpoints?.size} steerpoints believed for this flight), kind ${m2.route?.kind}, bullseye ${m2.route?.bullseyeX?.let { "set" } ?: "none"}")
        line("   tracks: " + m2.tracks.joinToString { "${it.role} ${it.callsign}${if (it.yours) " (yours)" else ""} ${it.points.size} pts" }.ifEmpty { "none" })
        val merged = PlanMerge.merge(m2)
        ok(merged.briefing != null && merged.fromSave == !printedCase && (merged.printed != null) == printedCase && merged.banner == null && merged.bannerKind == null,
            if (printedCase) "merged: the printed briefing, as EZBoards mode merges it, no banner" else "merged: the save's briefing, nothing printed under it, no banner")
        ok(merged.routeLine.size >= 2 && merged.allSteerpoints.none { it.source == com.bmscompanion.app.data.mission.PlanItemSource.PLAN },
            "merged: ${merged.routeLine.size} steerpoints placed (${merged.routeLine.groupBy { it.source }.mapValues { it.value.size }}), none marked as the plan's")
        ok(merged.presets.none { it.changed } && merged.settings.none { it.source == com.bmscompanion.app.data.mission.PlanItemSource.PLAN },
            "merged: presets and settings are the cartridge's (${merged.presets.size} presets)")
        val att = client.decodeFromString(AttackOverlay.serializer(), req("GET", "/api/attack").second)
        ok(att.cues.size == (m2.plan?.attack?.cues?.size ?: 0), "/api/attack is the snapshot's (${att.cues.size} cues)")
        // WDP mode serves the snapshot and nothing else: the answer is the kept file's mission, element for element
        val keptMission = runCatching {
            Json.parseToJsonElement(File(dir, MissionSource.FILE_NAME).readText(Charsets.UTF_8)).let { (it as kotlinx.serialization.json.JsonObject)["mission"] }
        }.getOrNull()
        ok(keptMission != null && keptMission == Json.parseToJsonElement(req("GET", "/api/mission").second) && m2.board == null &&
            m2.missionKey == "wdp-${p?.at}",
            "/api/mission in WDP mode is exactly the snapshot kept in ${MissionSource.FILE_NAME}: no EZBoards board")
        ok(taxi().runway.isEmpty(), "the taxi choice made in EZBoards mode is not served in WDP mode")
        req("POST", "/api/taxi", body = """{"airportId":9,"runway":"18","outbound":false,"spot":3,"at":2}""")
        ok(taxi().runway == "18", "a taxi choice made for the snapshot is served for it")
        val size = req("GET", "/api/mission").second.length
        line("   /api/mission in WDP mode: ${size / 1024} KB")
        val raw2 = mission(mapOf("source" to "bms"))
        ok(raw2.mode == MissionMode.EZBOARDS && raw2.briefing?.origin == null && raw2.plan == null, "?source=bms still gives BMS's own files, the snapshot not in them")
        // the save's own weather file, read as the Planner's card reads it, as the briefing's weather block (SaveWeather)
        val twxFile = korea?.let { k -> ts.campaignDir(k) }?.let { File(it, "Auto Save.twx") }?.takeIf { it.isFile }
        val w2 = m2.briefing?.weather
        if (twxFile != null && (p?.weatherFile != null || p?.briefingFrom == PlanMerge.ORIGIN_PRINTED)) {
            val twx = (Twx.read(twxFile.readBytes()) as? Twx.Read.Ok)?.twx
            val type = twx?.takeIf { it.model != 3 && it.version >= 8 }?.let { listOf("Sunny", "Fair", "Poor", "Inclement").getOrNull(it.condition - 1) }
            val table = twx?.types?.getOrNull((twx.condition - 1).coerceIn(0, 3))
            val take = w2?.rows?.associate { it.label to it.values.firstOrNull() }.orEmpty()
            if (p?.weatherFile != null) {
                ok(w2 != null && w2.columns == SaveWeather.COLUMNS && w2.rows.isNotEmpty() && w2.rows.all { it.values.size == 3 } &&
                    p?.weatherFile == twxFile.name && p?.weatherFileModified == twxFile.lastModified() && w2.source?.startsWith("From ${twxFile.name}") == true,
                    "its weather is the save's own ${twxFile.name}: ${w2?.rows?.joinToString { "${it.label} ${it.values.firstOrNull()}" }}")
                line("   source: ${w2?.source}")
                if (type != null && table != null) ok(take["Situation"] == type && take["Temp"] == "${table.tempC.last()}deg C." && take["Wind"]?.contains("${table.windKt.last()}kts") == true,
                    "the take-off column is the file's own table of the type it is in ($type, ${table.windKt.last()} kt, ${table.tempC.last()} °C)")
            } else ok(w2 != null && w2 == m0.briefing?.weather, "its weather is the printed forecast (${twxFile.name} saved no other weather after the print; part 12 checks the rule)")
            ok(m2.plan?.flight?.briefing?.weather == w2 && merged.briefing?.weather == w2, "the plan's flight carries the same weather, and the merged briefing (every view) shows it")
            // a map-model save: its own <save>.fmap read at each place's own cell — a map made here, Fair everywhere but
            // the three places (take-off Sunny, target Poor, landing Inclement); the .twx, its time and the folder put back
            val mapFile = File(twxFile.parentFile, "Auto Save.fmap")
            val twxBytes = twxFile.readBytes()
            val twxTime = twxFile.lastModified()
            if (!mapFile.exists() && twxBytes.size >= 728 && korea != null) {
                try {
                    val save = CampaignArchive.cached(File(twxFile.parentFile, "Auto Save.cam"), CampaignArchive.names(ts, korea))
                    val at = m2.plan?.flight?.let { SaveWeather.places(it, save, row.id) }
                    val size = SaveWeather.sizeFt(korea.appId)
                    val map = Fmap.blank()
                    fun cell(q: Pair<Double, Double>?) = q?.takeIf { size > 0 }?.let { (n, e) ->
                        (map.rows - 1 - kotlin.math.floor(n / size * map.rows).toInt()).coerceIn(0, map.rows - 1) * map.cols +
                            kotlin.math.floor(e / size * map.cols).toInt().coerceIn(0, map.cols - 1)
                    }
                    val cells = listOf(cell(at?.takeoff), cell(at?.target), cell(at?.landing))
                    map.fillInt(Fmap.Field.TYPE, 2)
                    listOf(3 to cells[1], 4 to cells[2], 1 to cells[0]).forEach { (type, c) -> if (c != null) map.setInt(Fmap.Field.TYPE, c, type) }
                    val want = cells.map { c -> when (c) { null -> null; cells[0] -> "Sunny"; cells[2] -> "Inclement"; cells[1] -> "Poor"; else -> "Fair" } }
                    mapFile.writeBytes(map.toBytes())
                    val twx3 = twxBytes.copyOf()
                    java.nio.ByteBuffer.wrap(twx3).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(212, 3).putInt(216, 0)
                    twxFile.writeBytes(twx3)
                    val pm = source(req("POST", "/api/mission/populate", body = body))
                    val wm = mission().briefing?.weather
                    val got = wm?.rows?.firstOrNull { it.label == "Situation" }?.values
                    ok(pm.ok && at?.takeoff != null && at.target != null && got == want && wm.source?.contains("(the map ${mapFile.name})") == true,
                        "a map-model save reads its own map at each place's cell: $got for cells ${cells} — \"${wm?.source}\"")
                } finally {
                    twxFile.writeBytes(twxBytes)
                    twxFile.setLastModified(twxTime)
                    mapFile.delete()
                }
                val back = source(req("POST", "/api/mission/populate", body = body))
                ok(back.ok && back.value?.populated?.weatherFile == p?.weatherFile && back.value?.populated?.weatherFileModified == twxTime &&
                    mission().briefing?.weather == w2,
                    "the .twx put back, Populate again reads its own tables again")
            }
        } else if (twxFile != null) {
            val why = p?.notes?.firstOrNull { it.startsWith(MissionMode.NO_WEATHER) }
            ok(why?.contains("was written") == true && w2 == null, "no weather came with it, as the .twx is another save's, and a note says so: \"$why\"")
        } else line("   (the copy has no Auto Save.twx: no weather to check)")

        // ---- 5. changed since
        line("")
        line("== 5. Changed since, and Populate again")
        if (cart != null) {
            val t = cart.lastModified()
            cart.setLastModified(t + 60_000)
            Thread.sleep(2200)
            val i3 = info()
            ok(i3.mission.populated?.changed == listOf("cartridge") && i3.mission.populated?.stale == true && mission().populated?.changed == listOf("cartridge"),
                "a cartridge saved since is noticed: changed ${i3.mission.populated?.changed}")
            ok(mission().dtc?.modified == m2.dtc?.modified, "and nothing is re-read by itself (the snapshot's cartridge is still the one populated)")
            // a real change: one preset's frequency, as a Save to DTC would write it
            val text = cart.readText(Charsets.ISO_8859_1)
            val m = Regex("(?im)^(\\s*UHF_1\\s*=\\s*)([0-9]+)").find(text)
            if (m != null) cart.writeText(text.replaceRange(m.groups[2]!!.range, "305550"), Charsets.ISO_8859_1)
            cart.setLastModified(t + 120_000)
            val again = source(req("POST", "/api/mission/populate", body = body))
            val m3 = mission()
            ok(again.ok && again.value?.populated?.changed?.isEmpty() == true && (again.value?.populated?.at ?: 0) > (p?.at ?: 0) &&
                m3.dtc?.modified == cart.lastModified(),
                "Populate again takes the cartridge as saved now (changed ${again.value?.populated?.changed})")
            if (m != null) ok(m3.dtc?.uhf?.any { it.freq.startsWith("305.55") } == true || m3.dtc?.vhf?.any { it.freq.startsWith("305.55") } == true,
                "the preset changed on disk is in the snapshot")
            else line("   (no preset line found to change in the cartridge; the file time alone was moved)")
        }
        // the weather saved again in BMS (SAVE WTH rewrites a TE's .twx alone): noticed, never re-read by itself
        val twxNow = korea?.let { k -> ts.campaignDir(k) }?.let { File(it, "Auto Save.twx") }?.takeIf { it.isFile }
        if (twxNow != null && mission().populated?.weatherFile != null) {
            val t = twxNow.lastModified()
            try {
                twxNow.setLastModified(t + 60_000)
                Thread.sleep(2200)
                val i4 = info()
                ok(i4.mission.populated?.changed == listOf("weather file"), "a weather file saved since is noticed: changed ${i4.mission.populated?.changed}")
            } finally {
                twxNow.setLastModified(t)
            }
            Thread.sleep(2200)
            ok(info().mission.populated?.changed?.isEmpty() == true, "its time put back, nothing is marked changed")
        }
        // BMS printing a briefing after the Populate (the next mission being set up) is noticed too, never acted on
        val printedFile = Bridge.briefingPath?.let(::File)?.takeIf { it.isFile }
        if (printedFile != null) {
            val t = printedFile.lastModified()
            val atNow = mission().populated?.at ?: 0L
            try {
                printedFile.setLastModified(maxOf(t, atNow) + 60_000)
                Thread.sleep(2200)
                val i5 = info()
                ok(i5.mission.populated?.changed == listOf("printed briefing") && mission().populated?.at == atNow,
                    "a briefing printed after the Populate is noticed: changed ${i5.mission.populated?.changed}, the snapshot kept")
            } finally {
                printedFile.setLastModified(t)
            }
            Thread.sleep(2200)
            ok(info().mission.populated?.changed?.isEmpty() == true, "the briefing's time put back, nothing is marked changed")
        }

        // ---- 6. restart, switch back, a failed write
        line("")
        line("== 6. A restart, switching back, a write that fails")
        val before = mission()
        MissionSource.forgetForCheck()
        val after = mission()
        ok(after.populated?.at == before.populated?.at && after.briefing?.overview?.flight == before.briefing?.overview?.flight && after.dtc == before.dtc,
            "a restart reads the snapshot back (${File(dir, MissionSource.FILE_NAME).length() / 1024} KB on disk)")
        val back = source(req("POST", "/api/mission/source", mapOf("mode" to "ezboards")))
        val m4 = mission()
        ok(back.ok && back.value?.mode == MissionMode.EZBOARDS && back.value?.populated?.at == before.populated?.at &&
            back.value?.line == i0.mission.line && m4.mode == MissionMode.EZBOARDS && m4.briefing?.origin == null && m4.plan == null &&
            !info().ezBoards.suspended && !info().kneeboard.runSuspended,
            "back in EZBoards mode: the printed briefing, EZBoards and HTML Briefing no longer suspended, the snapshot kept (\"${back.value?.line}\")")
        val attEz = client.decodeFromString(AttackOverlay.serializer(), req("GET", "/api/attack").second)
        ok(attEz.cues.isEmpty() == att0.cues.isEmpty() && (att0.cues.isEmpty() || com.bmscompanion.app.data.mission.AttackDrawing.same(attEz, att0)),
            "the VR boards' attack is the cartridge's own again in EZBoards mode (${attEz.cues.size} cues)")
        // EZBoards -> WDP -> EZBoards: exactly BMS's files again, nothing of the snapshot in them
        val ezText = req("GET", "/api/mission").second
        val merged4 = PlanMerge.merge(m4)
        ok(ezText == req("GET", "/api/mission", mapOf("source" to "bms")).second && m4.populated == null && m4.plan == null &&
            !m4.version.startsWith("wdp-") && m4.briefing == m0.briefing && m4.route == m0.route && m4.missionKey == m0.missionKey &&
            m4.briefing?.origin == null && !ezText.contains("\"populated\""),
            "back in EZBoards mode, /api/mission is BMS's own files exactly as before the switch (the same briefing and route), nothing of the snapshot")
        ok(!merged4.fromSave && merged4.plan == null && merged4.attack == null && merged4.allSteerpoints.none { it.source == com.bmscompanion.app.data.mission.PlanItemSource.PLAN },
            "merged, it has no save briefing, no plan, no attack and nothing marked as the plan's")
        ok(taxi().runway != "18", "the taxi choice made for the snapshot is not served in EZBoards mode")
        val wdpAgain = source(req("POST", "/api/mission/source", mapOf("mode" to "wdp")))
        ok(wdpAgain.value?.populated?.at == before.populated?.at && mission().populated?.at == before.populated?.at, "and in WDP mode again, the same snapshot")
        val blocked = File(dir.parentFile, "not-a-folder.txt").apply { writeText("x") }
        try {
            MissionSource.folderOverride = blocked
            val refused = source(req("POST", "/api/mission/populate", body = body))
            ok(refused.status == 409 && refused.error?.contains("could not be populated") == true, "a snapshot that cannot be written is refused: \"${refused.error}\"")
            ok(mission().populated?.at == before.populated?.at, "and the snapshot served is still the one before")
        } finally {
            MissionSource.folderOverride = dir
            blocked.delete()
        }

        // ---- 7. old and new clients
        line("")
        line("== 7. Old and new clients")
        val old = client.decodeFromString(MissionData.serializer(), """{"version":"1-2-0","briefingModified":1,"briefing":{"overview":{"flight":"Cyborg6"}}}""")
        ok(old.mode == MissionMode.EZBOARDS && old.populated == null && !old.awaitingPopulate, "a 1.3.7 MissionData reads as EZBoards mode")
        val oldInfo = client.decodeFromString(BridgeInfo.serializer(), """{"app":"BMS Companion","version":"1.3.7","ezBoards":{"configured":true}}""")
        ok(oldInfo.mission.mode == MissionMode.EZBOARDS && !oldInfo.ezBoards.suspended, "a 1.3.7 BridgeInfo reads as EZBoards mode, nothing suspended")
        val infoText = req("GET", "/api/info").second
        ok(infoText.contains("\"mission\"") && infoText.contains("\"suspended\"") && runCatching { Repo.json.decodeFromString(BridgeInfo.serializer(), infoText) }.isSuccess,
            "this PC's /api/info decodes with the app's own settings")
        ok(runCatching { Repo.json.decodeFromString(MissionData.serializer(), req("GET", "/api/mission").second) }.getOrNull()?.populated != null,
            "this PC's /api/mission (WDP mode) decodes with the app's own settings")
        source(req("POST", "/api/mission/source", mapOf("mode" to "ezboards")))
    }

    private fun shaTree(root: File): Map<String, String> {
        val md = MessageDigest.getInstance("SHA-256")
        return root.walkTopDown().filter { it.isFile }.associate { f ->
            md.reset()
            f.inputStream().use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            f.relativeTo(root).invariantSeparatorsPath to md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
