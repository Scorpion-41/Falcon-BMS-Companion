package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.AttackCue
import com.bmscompanion.app.data.mission.AttackOverlay
import com.bmscompanion.app.data.mission.BannerKind
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampFlightRow
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionRoute
import com.bmscompanion.app.data.mission.NavPoint
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.data.mission.PlanOverlay
import com.bmscompanion.app.data.mission.PlanSend
import com.bmscompanion.app.data.mission.PlanSource
import com.bmscompanion.app.data.mission.PlanState
import com.bmscompanion.app.data.wdp.AttackGeometry
import com.bmscompanion.app.data.wdp.DtcIni
import com.bmscompanion.app.data.wdp.DtcLoad
import com.bmscompanion.app.data.wdp.DtcModel
import com.bmscompanion.app.data.wdp.DtcSave
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.hypot

/**
 * `--plantest store <copy of a BMS folder> <scratch APPDATA> out.txt` (P2): the plan store ([PlanStore]) that Send to
 * Mission talks to.
 *
 * 1. **Not in the jet** ([PlanStore.notInJet], check P4) against the copy's own files: the plan equal to the disk is
 *    empty; formatting alone (BMS's leading spaces, positions rounded to the foot, CRLF, IFF codes without their zeros,
 *    the Planner's own writer with its extra WDP keys) is empty; one moved steerpoint, one PPT and one preset are
 *    exactly those three labels; under 50 ft is the same place; a cleared slot, a route filled from BMS's mission file,
 *    lines, nav offsets, 3D, TE against campaign PPTs, settings the cartridge does and does not hold.
 * 2. **Applied or parked** ([PlanStore.evaluate], the R3-PLAN A12 table) on worlds made here, and the banner the app
 *    then shows.
 * 3. **The lifecycle** (P5), in-process against the copy with the plan files in the scratch APPDATA: send, what
 *    `/api/mission`, `/api/info` and `/api/attack` then carry, a restart, a PRINT of the same flight, a Save to DTC
 *    (the plan's text put in the copy's cartridge, then the original put back), Clear, Undo and redo, the files on the
 *    PC (also after a script standing in for the real WDP wrote nav offsets and a line), a flight of a save, another
 *    flight parked by a later PRINT and applied again, a flight of another theater. The SHA-256 of every file of the
 *    copy is the same before and after (the store writes nothing there; what this check itself moved is put back).
 * 4. **Refusals** (P6): not a plan, not a cartridge, too large, a bad seat or save, a wrong method, nothing to clear or
 *    undo, no cartridge, a settings folder Windows will not let it write (a deny ACL), a read-only plan file — each
 *    a sentence, the plan in memory and on disk unchanged, no exception.
 *
 * Refuses a folder [DevGuard] does not call a copy, and an APPDATA under `AppData\Roaming` (a real one).
 */
internal object PlanStoreTest {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun run(inputs: List<String>): String {
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
        if (root == null || !root.isDirectory || appdata == null) return "FAIL: usage: --plantest store <copy of a BMS folder> <scratch APPDATA> out.txt\n"
        DevGuard.why(root)?.let { return "FAIL: --plantest store moves the copy's cartridge and briefing time (and puts them back), and ${root.path} is not a copy: $it\n" }
        if (Regex("(?i)[\\\\/]AppData[\\\\/]Roaming([\\\\/]|$)").containsMatchIn(appdata.absolutePath)) return "FAIL: ${appdata.path} looks like a real APPDATA: give a scratch folder\n"
        val planDir = File(appdata, "BMS Companion")
        line("--plantest store: the plan store (Send to Mission) against ${root.path}; plan files in ${planDir.path}")
        line("")

        val ts = Theaters.at(root)
        val korea = ts.byName("Korea KTO")
        val campaign = ts.campaignDir(korea)
        if (korea == null || campaign == null) { ok(false, "Korea KTO and its campaign folder"); return done() }
        val strings = ts.strings(korea)
        val cart = File(root, "User/Config").listFiles { f -> f.isFile && f.name.endsWith(".ini", true) && !f.name.contains("_Def", true) }
            ?.filter { runCatching { it.readText(Charsets.ISO_8859_1) }.getOrDefault("").let { t -> "[STPT]" in t && "[Radio]" in t } }
            ?.maxByOrNull { it.lastModified() }
        val briefFile = File(root, "User/Briefings/briefing.txt")
        val briefing = runCatching { BriefingParser.parse(briefFile.readText(Charsets.UTF_8)) }.getOrNull()
        val autoFile = File(campaign, "Auto Save.cam")
        val auto = CampaignArchive.read(autoFile, CampaignArchive.names(ts, korea))
        val autoIni = MissionDtcFile.iniFor(auto)
        val route = MissionDtcFile.check(auto, autoIni, briefing, strings, korea.name).believedRoute
        ok(cart != null && briefing != null && route != null,
            "the copy: a pilot's cartridge, the printed briefing (${briefing?.overview?.flight}, package ${briefing?.overview?.packageId}), BMS's route believed (${route?.file})")
        if (cart == null || briefing == null || route == null) return done()
        val text = cart.readText(Charsets.UTF_8)
        val disk = DtcParser.parseText(text)

        notInJetPart(text, disk, route, campaign, ::ok, ::line)
        parkPart(text, disk, route, briefing, ::ok, ::line)

        // 3 and 4 run against the copy through the bridge, in-process
        val saved = Bridge.settings.value
        val sha0 = shaTree(root)
        line("")
        line("   ${sha0.size} files of the copy hashed (SHA-256) before the lifecycle")
        val cartBytes = cart.readBytes()
        val cartTime = cart.lastModified()
        val briefTime = briefFile.lastModified()
        try {
            planDir.mkdirs()
            listOf(PlanStore.FILE_NAME, PlanStore.PREV_NAME).forEach { File(planDir, it).delete() }
            PlanStore.folderOverride = planDir
            PlanStore.forgetForCheck()
            Bridge.update { it.copy(BmsDirOverride = root.path, EzBoardsDir = null, AutoEzBoardsOnPrint = false, TacviewEnabled = false) }
            Bridge.startForCheck()
            val base = Bridge.install.baseDir?.let(::File)
            if (base == null || Theaters.canonical(base) != Theaters.canonical(root)) ok(false, "the bridge reads the copy (it reads ${base?.path ?: "no folder"})")
            else {
                lifecycle(Ctx(root, planDir, cart, cartBytes, cartTime, briefFile, briefTime, text, disk, route, auto), ::ok, ::line)
                refusals(Ctx(root, planDir, cart, cartBytes, cartTime, briefFile, briefTime, text, disk, route, auto), ::ok, ::line)
            }
        } catch (e: Throwable) {
            ok(false, "the lifecycle threw: $e")
        } finally {
            // put back what this check moved in the copy, whatever happened
            runCatching { if (!cart.readBytes().contentEquals(cartBytes)) cart.writeBytes(cartBytes); cart.setLastModified(cartTime) }
            runCatching { briefFile.setLastModified(briefTime) }
            runCatching { Bridge.stop() }
            runCatching { Bridge.update { saved } }
            PlanStore.folderOverride = null
            PlanStore.forgetForCheck()
            MissionDtcFile.forget()
        }
        val sha1 = shaTree(root)
        val changed = (sha0.keys + sha1.keys).filter { sha0[it] != sha1[it] }
        line("")
        ok(changed.isEmpty(), "every file of the copy has the SHA-256 it had before (${sha1.size} files)" + (if (changed.isEmpty()) "" else ": changed ${changed.take(8)}"))
        ok(cart.lastModified() == cartTime && briefFile.lastModified() == briefTime, "the cartridge's and the briefing's file times are put back")
        return done()
    }

    /** What the in-process parts share. */
    private class Ctx(
        val root: File, val planDir: File, val cart: File, val cartBytes: ByteArray, val cartTime: Long, val briefFile: File, val briefTime: Long,
        val text: String, val disk: Dtc, val route: MissionRoute, val auto: CampaignArchive.Save,
    )

    // ================================================================================================ 1. not in the jet

    private fun notInJetPart(text: String, disk: Dtc, route: MissionRoute, campaign: File, ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        line("1. Not in the jet (P4): the plan against the copy's own cartridge and BMS's route (before 3D unless said)")
        fun nij(plan: String, nav: List<NavPoint> = emptyList(), te: Boolean = false, r: MissionRoute? = route, d: Dtc? = disk) =
            PlanStore.notInJet(DtcParser.parseText(plan), null, d, r, emptyList(), te, nav)

        ok(nij(text).isEmpty(), "the plan equal to the cartridge on disk: nothing (${nij(text)})")
        val fmt = formattingOnly(text)
        ok(nij(fmt).isEmpty() && fmt != text, "formatting only (positions rounded to the foot, BMS's leading spaces doubled, CRLF, IFF codes without their zeros): nothing ${nij(fmt)}")
        val planner = plannerSave(text)
        val pd = planner?.let { DtcParser.parseText(it) }
        ok(planner != null && nij(planner).isEmpty(), "the Planner's own writer over the unchanged cartridge: nothing ${planner?.let { nij(it) }} " +
            "(it adds keys BMS's file lacks: laser ${pd?.laserTgp}, bingo ${pd?.bingoLbs}, ALOW ${pd?.alowFt}, nav offsets ${pd?.navOffsets?.mode}, EWS names ${pd?.ewsNames?.size})")

        val three = threeEdits(text)
        ok(nij(three) == listOf("STPT 16", "PPT 56", "UHF 3"), "one moved steerpoint, one PPT and one preset: exactly ${nij(three)} (want [STPT 16, PPT 56, UHF 3])")
        ok(nij(movePoint(text, "target_15", 30.0, 0.0)).isEmpty(), "STPT 16 moved 30 ft is the same place: ${nij(movePoint(text, "target_15", 30.0, 0.0))}")
        ok(nij(movePoint(text, "target_15", 60.0, 0.0)) == listOf("STPT 16"), "moved 60 ft it is not: ${nij(movePoint(text, "target_15", 60.0, 0.0))}")
        val cleared = DtcIni.write(text, "STPT", "target_15", "0.000000, 0.000000, 0.000000, -1, Not set")
        ok(nij(cleared) == listOf("STPT 16"), "STPT 16 cleared in the plan while the cartridge holds it: ${nij(cleared)}")

        // the route: the Planner fills STPT 1-8 from BMS's mission file (U1's per-slot rule)
        var filled = text
        for (p in DtcRoute.points(Dtc(steerpoints = route.steerpoints))) {
            filled = DtcIni.write(filled, "STPT", "target_${p.n - 1}", "%.6f, %.6f, %.6f, %d".format(Locale.ROOT, p.x, p.y, -p.altFt, p.action))
        }
        ok(nij(filled).isEmpty(), "STPT 1-8 filled from BMS's route: nothing (the route is what the jet loads) ${nij(filled)}")
        ok(nij(movePoint(filled, "target_2", 0.0, 500.0)) == listOf("STPT 3"), "STPT 3 of that route moved 500 ft: ${nij(movePoint(filled, "target_2", 0.0, 500.0))}")
        ok(nij(filled, r = null) == (1..8).map { "STPT $it" }, "without a believed route nothing on disk holds STPT 1-8: ${nij(filled, r = null)}")

        val lined = (0..2).fold(text) { t, i -> DtcIni.write(t, "STPT", "lineSTPT_$i", "%.6f, %.6f, 0.000000".format(Locale.ROOT, 1_500_000.0 + i * 9_000, 1_200_000.0 + i * 4_000)) }
        ok(nij(lined) == listOf("LINE 1"), "three points of line 1: ${nij(lined)}")

        val offs = navOffsets(text)
        ok(nij(offs) == listOf("OFFSET MODE", "VIP", "VIP PUP", "OA1 on 4"), "VIP mode with its pull-up and an OA1 the cartridge has none of: ${nij(offs)}")
        ok(nij(offs, d = DtcParser.parseText(offs)).isEmpty(), "the same offsets on disk: nothing")
        val offsMoved = DtcIni.write(offs, "NAV OFFSETS", "OA1-4", "37.3,34261,6748")
        ok(nij(offsMoved, d = DtcParser.parseText(offs)) == listOf("OA1 on 4"), "OA1 100 ft further: ${nij(offsMoved, d = DtcParser.parseText(offs))}")

        // presets and settings
        val comment = DtcIni.write(text, "Radio", "UHF_COMMENT_3", "Strike common")
        ok(nij(comment) == listOf("UHF 3"), "UHF 3's comment changed: ${nij(comment)}")
        val iff = DtcIni.write(text, "IFF", "Mode3A Code", "0555")
        ok(nij(iff) == listOf("IFF Mode3A"), "IFF Mode 3A 0554 -> 0555: ${nij(iff)}")
        val laserDisk = DtcParser.parseText(DtcIni.write(text, "Laser", "LaserTGP", "1511"))
        val laser1688 = DtcIni.write(text, "Laser", "LaserTGP", "1688")
        ok(nij(laser1688, d = laserDisk) == listOf("LASER TGP") && nij(laser1688).isEmpty(),
            "a laser code differs where the cartridge holds one (${nij(laser1688, d = laserDisk)}), and is no difference where BMS's file has none (${nij(laser1688)})")
        val tacan = DtcIni.write(DtcIni.write(DtcIni.write(text, "COMMS", "TACAN Channel", "12"), "COMMS", "TACAN Band", "1"), "COMMS", "TACAN Domain", "1")
        val tacanDisk = DtcParser.parseText(DtcIni.write(DtcIni.write(text, "COMMS", "TACAN Channel", "94"), "COMMS", "TACAN Band", "0"))
        ok(nij(tacan, d = tacanDisk) == listOf("TACAN") && nij(tacan).isEmpty(),
            "a TACAN differs where the cartridge holds one (${nij(tacan, d = tacanDisk)}), and is no difference where BMS's file has none (${nij(tacan)})")

        // 3D: the jet's own steerpoints, PPTs and lines
        val jet = DtcRoute.points(Dtc(steerpoints = route.steerpoints)).map { NavPoint(i = it.n, type = "WP", x = it.x, y = it.y) } +
            disk.steerpoints.filter { it.n in 1..24 }.map { NavPoint(i = it.n, type = "WP", x = it.x, y = it.y) }
        ok(nij(text, nav = jet).isEmpty() && nij(filled, nav = jet).isEmpty(), "in 3D, the jet holding the route and the targets: nothing for the cartridge, nothing for the filled route")
        ok(nij(three, nav = jet) == listOf("STPT 16", "PPT 56", "UHF 3"), "in 3D, the three edits: ${nij(three, nav = jet)} (PPT 56: the jet has no PT; UHF 3: held against the cartridge)")
        ok(nij(text, nav = jet.filterNot { it.i == 16 }) == listOf("STPT 16"), "in 3D, a target the jet does not have: ${nij(text, nav = jet.filterNot { it.i == 16 })}")
        ok(nij(cleared, nav = jet) == listOf("STPT 16") && nij(cleared, nav = jet.filterNot { it.i == 16 }).isEmpty(),
            "in 3D a cleared slot counts while the jet still has it, and not once it has not")
        val sa3 = DtcParser.parseText(three).ppts.first { it.n == 56 }
        val jetPt = jet + NavPoint(i = 56, type = "PT", x = sa3.x + 10, y = sa3.y, name = "SA3", rangeNm = sa3.rangeFt / DtcParser.FT_PER_NM)
        ok(nij(three, nav = jetPt) == listOf("STPT 16", "UHF 3"), "in 3D with the jet's PT 56 10 ft away: PPT 56 is in the jet ${nij(three, nav = jetPt)}")

        // a TE keeps its PPTs in its own mission file
        val teIni = File(campaign, "TE_BMS_03_F-16_DEAD.ini")
        if (teIni.isFile) {
            val te = DtcParser.parse(teIni)
            val teRoute = MissionRoute(file = teIni.name, kind = CampKind.TE, steerpoints = te.steerpoints, ppts = te.ppts, lines = te.lines)
            val tePptLines = teIni.readLines(Charsets.ISO_8859_1).filter { it.trim().startsWith("ppt_", ignoreCase = true) }
            val planTe = tePptLines.fold(text) { t, l -> DtcIni.write(t, "STPT", l.substringBefore('=').trim(), l.substringAfter('=').trim()) }
            val otherDisk = DtcParser.parseText(DtcIni.write(text, "STPT", "ppt_0", "1400000.000000, 1100000.000000, 0.000000, 72913.390625, SA3"))
            ok(nij(planTe, te = true, r = teRoute, d = otherDisk).isEmpty(), "a TE: the plan's PPTs equal the TE's mission file, where the jet takes them from: nothing")
            ok(nij(planTe, te = false, r = teRoute, d = otherDisk) == listOf("PPT 56"), "a campaign: the cartridge's own PPT 56 comes first: ${nij(planTe, te = false, r = teRoute, d = otherDisk)}")
        } else ok(false, "TE_BMS_03_F-16_DEAD.ini is in the campaign folder")
    }

    // ================================================================================================ 2. applied or parked

    private fun parkPart(text: String, disk: Dtc, route: MissionRoute, briefing: com.bmscompanion.app.data.mission.Briefing, ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        line("")
        line("2. Applied or parked (R3-PLAN A12), on worlds made here")
        val sent = 1_800_000_000_000L
        fun world(b: com.bmscompanion.app.data.mission.Briefing?, printed: Long, app: String? = "korea-kto", name: String? = "Korea KTO") =
            PlanStore.World(b, printed, disk, route, app, name, emptyList(), "test")
        val cyborg = PlanStore.Stored(plan = PlanOverlay(id = sent, theater = "korea-kto", callsign = "Cyborg6", packageId = "7288", dtc = disk), cartridge = text, theaterName = "Korea KTO")
        val jaguarFlight = CampFlight(row = CampFlightRow(callsign = "Jaguar2", number = 9001), packageNumber = 9000)
        val jaguar = PlanStore.Stored(
            plan = PlanOverlay(id = sent, theater = "korea-kto", callsign = "Jaguar2", packageId = "9000", flight = jaguarFlight, ref = CampRef("Korea KTO", "My Save.cam", "9001/0"), dtc = disk),
            cartridge = text, theaterName = "Korea KTO", saveModified = sent - 60_000,
        )
        fun eval(s: PlanStore.Stored, w: PlanStore.World) = PlanStore.evaluate(s, w)
        val a = eval(cyborg, world(briefing, sent + 60_000))
        ok(a.state == PlanState.APPLIED, "the same flight, the briefing printed again after the plan: ${a.state}")
        val b = eval(jaguar, world(null, 0))
        ok(b.state == PlanState.APPLIED, "no printed briefing, a flight from a save: ${b.state}")
        val c = eval(jaguar, world(briefing, sent - 120_000))
        ok(c.state == PlanState.APPLIED, "a briefing for another flight, older than the plan's save: ${c.state}")
        val d = eval(jaguar.copy(saveModified = 0), world(briefing, sent - 1_000))
        ok(d.state == PlanState.APPLIED, "a briefing for another flight printed before the plan was sent: ${d.state}")
        val e = eval(jaguar, world(briefing, sent + 60_000))
        ok(e.state == PlanState.PARKED && e.note?.contains("Cyborg6") == true && e.notInJet.isEmpty(),
            "a briefing for another flight printed after the plan (and after its save): ${e.state} — \"${e.note}\"")
        val f = eval(PlanStore.Stored(plan = PlanOverlay(id = sent, theater = "korea-kto", dtc = disk), cartridge = text, theaterName = "Korea KTO"), world(null, 0))
        ok(f.state == PlanState.APPLIED, "no briefing and no flight: ${f.state}")
        val g = eval(cyborg.copy(plan = cyborg.plan.copy(theater = "hellas"), theaterName = "Hellas"), world(briefing, sent - 1_000))
        ok(g.state == PlanState.PARKED && g.note?.contains("Hellas") == true && g.note?.contains("Korea KTO") == true, "made for another theater: ${g.state} — \"${g.note}\"")
        // a "files" plan's own mission file is what the jet loads, even with no believed route to hold it against
        val filesPlan = PlanStore.Stored(
            plan = PlanOverlay(id = sent, source = PlanSource.FILES, theater = "korea-kto", dtc = disk, route = route.copy(kind = CampKind.TE)),
            cartridge = text, theaterName = "Korea KTO",
        )
        val h = eval(filesPlan, PlanStore.World(null, 0, disk, null, "korea-kto", "Korea KTO", emptyList(), "test"))
        val h2 = eval(filesPlan.copy(plan = filesPlan.plan.copy(source = PlanSource.PLANNER)), PlanStore.World(null, 0, disk, null, "korea-kto", "Korea KTO", emptyList(), "test"))
        ok(h.state == PlanState.APPLIED && h.notInJet.isEmpty() && h2.notInJet.size == 8,
            "the files on the PC with no believed route: their own mission file is the reference ${h.notInJet}; the same route from the Planner is not on disk (${h2.notInJet.size} steerpoints)")
        // what the app's banner then says (PlanMerge, shared code)
        val md = MissionData(briefingModified = sent + 60_000, briefing = briefing, dtc = disk, route = route, plan = e)
        val merged = PlanMerge.merge(md)
        ok(merged.bannerKind == BannerKind.PARKED && merged.plan == null, "the app merges nothing of a parked plan: \"${merged.banner}\"")
        val older = PlanMerge.merge(MissionData(briefingModified = sent - 120_000, briefing = briefing, dtc = disk, route = route, plan = c))
        line("   applied for another flight, the briefing older than the save: ${older.bannerKind} — \"${older.banner}\"")
    }

    // ================================================================================================ 3. the lifecycle

    private fun lifecycle(x: Ctx, ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        line("")
        line("3. The lifecycle (P5), in-process against the copy (each request the Planner or a device makes is listed with '>')")
        fun req(method: String, path: String, body: String? = null, query: Map<String, String> = emptyMap()): Pair<Int, String> {
            val r = Bridge.handle(ApiRequest(method, path, query, body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)))
            if (method != "GET" || path == "/api/plan") {
                val q = query.entries.joinToString("&") { "${it.key}=${it.value}" }.let { if (it.isEmpty()) "" else "?$it" }
                line("   > $method $path$q" + (body?.let { " (${it.length} chars)" } ?: "") + " -> ${r.status}")
            }
            return r.status to r.body.toString(Charsets.UTF_8)
        }
        fun planOf(body: String) = runCatching { lenient.decodeFromString(PlanOverlay.serializer(), body) }.getOrNull()
        fun send(ps: PlanSend) = req("POST", "/api/plan", Bridge.json.encodeToString(PlanSend.serializer(), ps))
        fun mission() = runCatching { lenient.decodeFromString(MissionData.serializer(), req("GET", "/api/mission").second) }.getOrNull()
        fun info() = runCatching { lenient.decodeFromString(BridgeInfo.serializer(), req("GET", "/api/info").second) }.getOrNull()
        fun attack() = runCatching { lenient.decodeFromString(AttackOverlay.serializer(), req("GET", "/api/attack").second) }.getOrNull()
        // Since 1.3.8 /api/mission carries no sent plan (Send to Mission is gone; WDP mode's Populate replaced it), but the
        // PC still keeps and answers one for older devices: the merge rules are checked on the plan GET /api/plan holds.
        fun kept() = planOf(req("GET", "/api/plan").second)
        fun merged() = PlanMerge.merge(mission()?.copy(plan = kept()))
        val planFile = File(x.planDir, PlanStore.FILE_NAME)
        val prevFile = File(x.planDir, PlanStore.PREV_NAME)

        val (s0, b0) = req("GET", "/api/plan")
        ok(s0 == 200 && planOf(b0)?.present == false && planOf(b0)?.canUndo == false && mission()?.plan == null,
            "nothing sent yet: GET /api/plan answers no plan and no Undo, /api/mission has none")

        // send: the three edits, an attack, from a phone
        val tossAttack = AttackOverlay(page = "TOSS", cues = listOf(AttackCue("TGT", 2_070_031.0, 130_944.7, AttackCue.Kind.TARGET), AttackCue("IP", 2_010_000.0, 150_000.0, AttackCue.Kind.IP)), theater = "korea-kto")
        val (s1, b1) = send(PlanSend(cartridge = threeEdits(x.text), attack = tossAttack, from = "Android"))
        val a = planOf(b1)
        ok(s1 == 200 && a != null && a.present && a.state == PlanState.APPLIED && a.source == PlanSource.PLANNER && a.from == "Android",
            "POST /api/plan: ${a?.state}, source ${a?.source}, from ${a?.from}" + (if (s1 != 200) " ($s1 $b1)" else ""))
        if (a == null) return
        ok(a.callsign == "Cyborg6" && a.packageId == "7288" && a.briefing == x.let { BriefingParser.parse(it.briefFile.readText(Charsets.UTF_8)).generated } && a.theater == "korea-kto",
            "stamped: ${a.callsign}, package ${a.packageId}, briefing '${a.briefing}', theater ${a.theater}")
        ok(a.notInJet == listOf("STPT 16", "PPT 56", "UHF 3"), "not in the jet: ${a.notInJet}")
        val p56 = a.dtc.ppts.firstOrNull { it.n == 56 }
        ok(p56?.code == "SA3" && p56.name == "SA-3" && !p56.marker, "PPT 56 resolved through Korea's Ppt.ini: code ${p56?.code} -> '${p56?.name}', ${p56?.rangeFt} ft")
        ok(a.attack == tossAttack && attack()?.cues.isNullOrEmpty(), "the attack went with it; GET /api/attack answers none in EZBoards mode (1.3.8: only WDP mode's populated attack)")
        ok(planFile.isFile && !prevFile.exists(), "kept in ${planFile.name} (${planFile.length()} bytes), no Undo file yet")
        val stored = planFile.readText(Charsets.UTF_8)
        ok(!Regex("[A-Za-z]:\\\\").containsMatchIn(stored), "the stored plan holds no path")
        val m1 = mission()
        ok(m1 != null && m1.plan == null && kept()?.id == a.id && kept()?.notInJet == a.notInJet,
            "/api/mission does not carry it (1.3.8: nothing is laid over a printed briefing); GET /api/plan still holds it")

        // a restart of the program
        PlanStore.forgetForCheck()
        val back = planOf(req("GET", "/api/plan").second)
        ok(back == a, "after a restart the same plan is read back (id ${back?.id}, not in jet ${back?.notInJet})")

        // PRINT of the same flight
        x.briefFile.setLastModified(System.currentTimeMillis() + 60_000)
        val merged2 = merged()
        ok(kept()?.state == PlanState.APPLIED && merged2.reprinted && merged2.banner?.contains("re-printed") == true,
            "PRINT of the same flight: still ${kept()?.state}; the banner: \"${merged2.banner}\"")
        x.briefFile.setLastModified(x.briefTime)
        mission()

        // a Save to DTC: the plan's text becomes the cartridge, and the list empties by itself
        val before = PlanStore.modified
        writeFile(x.cart, threeEdits(x.text).toByteArray(Charsets.UTF_8))
        x.cart.setLastModified(System.currentTimeMillis() + 5_000)
        mission()
        val m3 = kept()
        ok(m3?.notInJet?.isEmpty() == true && PlanStore.modified > before, "after a Save to DTC (the copy's cartridge): nothing is left not in the jet ${m3?.notInJet}, the plan's time moved")
        writeFile(x.cart, x.cartBytes)
        x.cart.setLastModified(x.cartTime)
        mission()
        ok(kept()?.notInJet == listOf("STPT 16", "PPT 56", "UHF 3"), "the cartridge put back: the three again")

        // clear and undo
        val (s4, b4) = req("POST", "/api/plan/clear")
        ok(s4 == 200 && planOf(b4)?.present == false && planOf(b4)?.canUndo == true && mission()?.plan == null && attack()?.cues.isNullOrEmpty(),
            "Clear: no plan held, none on /api/attack, Undo offered")
        ok(!planFile.exists() && prevFile.isFile, "on disk: no plan, the cleared one kept for Undo")
        val (s4b, b4b) = req("POST", "/api/plan/clear")
        ok(s4b == 409 && errorOf(b4b) == "There is no plan to clear.", "Clear again: $s4b \"${errorOf(b4b)}\"")
        val (s5, b5) = req("POST", "/api/plan/undo")
        ok(s5 == 200 && planOf(b5)?.id == a.id && planOf(b5)?.canUndo == false && kept()?.id == a.id, "Undo brings the same plan back (id ${planOf(b5)?.id})")
        val (s5b, b5b) = req("POST", "/api/plan/undo")
        ok(s5b == 409 && errorOf(b5b) == "There is nothing to undo.", "Undo again: $s5b \"${errorOf(b5b)}\"")

        // send again (the PC takes the cartridge on disk), undo, redo
        val (s6, b6) = send(PlanSend(from = "Browser"))
        val bPlan = planOf(b6)
        ok(s6 == 200 && bPlan != null && bPlan.id != a.id && bPlan.notInJet.isEmpty() && bPlan.canUndo, "Send with no cartridge: the PC takes the one on disk, nothing not in the jet, Undo offered")
        val u1 = planOf(req("POST", "/api/plan/undo").second)
        val u2 = planOf(req("POST", "/api/plan/undo").second)
        ok(u1?.id == a.id && u1.canUndo && u2?.id == bPlan?.id, "Undo swaps: back to the first (${u1?.id}), again to the second (${u2?.id})")

        // the files on the PC
        val (s7, b7) = req("POST", "/api/plan/files", query = mapOf("from" to "Browser"))
        val fPlan = planOf(b7)
        val bull = MissionDtcFile.bullseye(x.auto.header)
        ok(s7 == 200 && fPlan?.source == PlanSource.FILES && fPlan.from == "Browser" && fPlan.route?.file == "Auto Save.ini" && fPlan.route?.steerpoints?.size == x.route.steerpoints.size &&
            fPlan.route?.bullseyeX == bull?.first && fPlan.route?.bullseyeY == bull?.second && fPlan.notInJet.isEmpty() && fPlan.attack == null,
            "POST /api/plan/files: the cartridge, ${fPlan?.route?.file} (${fPlan?.route?.steerpoints?.size} points) and the header bullseye ${fPlan?.route?.bullseyeX?.toLong()}, ${fPlan?.route?.bullseyeY?.toLong()}; not in jet ${fPlan?.notInJet}")
        val (_, b7b) = req("POST", "/api/plan/files", query = mapOf("from" to "Kitchen tablet"))
        ok(planOf(b7b)?.from == null, "a device name that is not PC, Android or Browser is dropped")

        // the files after the real WDP (a script) wrote nav offsets and a line into the cartridge
        val wdp = (0..2).fold(navOffsets(x.text)) { t, i -> DtcIni.write(t, "STPT", "lineSTPT_$i", "%.6f, %.6f, 0.000000".format(Locale.ROOT, 1_500_000.0 + i * 9_000, 1_200_000.0 + i * 4_000)) }
        writeFile(x.cart, wdp.toByteArray(Charsets.UTF_8))
        x.cart.setLastModified(System.currentTimeMillis() + 10_000)
        val w = planOf(req("POST", "/api/plan/files", query = mapOf("from" to "PC")).second)
        val s3 = x.route.steerpoints.first { it.n == 3 }
        val s4p = x.route.steerpoints.first { it.n == 4 }
        val tgt = AttackGeometry.lay(s3.x, s3.y, 20.0, 48610.0)
        val oa = AttackGeometry.lay(s4p.x, s4p.y, 37.3, 34161.0)
        val cues = w?.attack?.cues.orEmpty()
        fun at(label: String, p: Pair<Double, Double>) = cues.any { it.label == label && hypot(it.north - p.first, it.east - p.second) < 0.01 }
        ok(w?.attack?.page == "NAV OFFSETS" && at("VIP", s3.x to s3.y) && at("TGT", tgt) && at("OA1", oa) && cues.any { it.label == "PUP" } && w.notInJet.isEmpty(),
            "WDP's [NAV OFFSETS] laid out from BMS's route: " + cues.joinToString { "${it.label} %.0f,%.0f".format(it.north, it.east) } + "; not in jet ${w?.notInJet}")
        ok(w?.dtc?.lines?.count { it.line == 1 } == 3 && attack()?.cues.isNullOrEmpty(), "the line came along; the VR boards' map draws no sent plan's attack (1.3.8)")
        writeFile(x.cart, x.cartBytes)
        x.cart.setLastModified(x.cartTime)

        // a flight of a save
        val ctx = CampaignFiles.context()
        val ato = okValue(CampaignFiles.ato(ctx, "Korea KTO", "Auto Save.cam"))
        val flights = ato?.packages?.flatMap { p -> p.flights.map { p to it } }.orEmpty()
        val cy = flights.firstOrNull { it.second.callsign.filter(Char::isLetterOrDigit).equals("Cyborg6", ignoreCase = true) }
        val other = flights.firstOrNull { it.second.f16 && it.first.id != cy?.first?.id }
        ok(cy != null && other != null, "Auto Save.cam's ATO: Cyborg6 is ${cy?.second?.id}; another F-16 flight ${other?.second?.callsign} ${other?.second?.id} (package ${other?.first?.number})")
        if (cy != null) {
            val (s8, b8) = send(PlanSend(cartridge = x.text, ref = CampRef("korea kto", "auto save.CAM", cy.second.id), seat = 0, from = "PC"))
            val r = planOf(b8)
            ok(s8 == 200 && r?.flight?.row?.callsign == cy.second.callsign && r.callsign == cy.second.callsign && r.packageId == "7288" && r.seat == 0 &&
                r.flight?.briefing?.origin == "save" && r.state == PlanState.APPLIED && r.ref?.theater == "Korea KTO",
                "Cyborg6 from Auto Save.cam: the flight attached (${r?.flight?.route?.size} waypoints, its briefing from the save), ${r?.state}, ref ${r?.ref}" + (if (s8 != 200) " ($s8 ${errorOf(b8)})" else ""))
        }
        if (other != null) {
            val (s9, b9) = send(PlanSend(cartridge = x.text, ref = CampRef("Korea KTO", "Auto Save.cam", other.second.id), from = "PC"))
            val r = planOf(b9)
            val mm = merged()
            ok(s9 == 200 && r?.state == PlanState.APPLIED && mm.bannerKind == BannerKind.SAVE_OLDER,
                "${other.second.callsign}, another flight of the save the printed briefing is older than: ${r?.state}; \"${mm.banner}\"")
            x.briefFile.setLastModified(System.currentTimeMillis() + 60_000)
            mission()
            val parked = kept()
            val pm2 = merged()
            ok(parked?.state == PlanState.PARKED && parked.note?.contains("Cyborg6") == true && pm2.bannerKind == BannerKind.PARKED && attack()?.cues.isNullOrEmpty(),
                "a PRINT of Cyborg6 after it: ${parked?.state}; the banner: \"${pm2.banner}\"")
            x.briefFile.setLastModified(x.briefTime)
            mission()
            ok(kept()?.state == PlanState.APPLIED, "the briefing's time put back: applied again (worked out whenever asked)")
        }

        // a flight of another theater
        val files = CampaignFiles.list(ctx, false)
        val elsewhere = files.theaters.firstOrNull { t -> t.appTheater != "korea-kto" && t.name.contains("Hellas", ignoreCase = true) && t.files.any { !it.stock && (it.flights ?: 0) > 0 } }
            ?: files.theaters.firstOrNull { t -> t.appTheater != "korea-kto" && t.files.any { !it.stock && (it.flights ?: 0) > 0 } }
        val eFile = elsewhere?.files?.firstOrNull { !it.stock && (it.flights ?: 0) > 0 }
        val eAto = eFile?.let { okValue(CampaignFiles.ato(ctx, elsewhere.name, it.name)) }
        val eFlight = eAto?.packages?.flatMap { it.flights }?.firstOrNull()
        if (elsewhere != null && eFile != null && eFlight != null) {
            val (s10, b10) = send(PlanSend(cartridge = x.text, ref = CampRef(elsewhere.name, eFile.name, eFlight.id), from = "PC"))
            val r = planOf(b10)
            ok(s10 == 200 && r?.state == PlanState.PARKED && r.theater == elsewhere.appTheater && r.note?.contains(elsewhere.name.trim()) == true,
                "${eFlight.callsign} of ${elsewhere.name}'s ${eFile.name}: ${r?.state} — \"${r?.note}\"")
        } else ok(false, "a save with flights in another theater (${elsewhere?.name} ${eFile?.name})")

        // left applied at the end, as a pilot would leave it (the refusals below must not move it)
        val (s11, b11) = send(PlanSend(cartridge = threeEdits(x.text), attack = tossAttack, from = "Android"))
        ok(s11 == 200 && planOf(b11)?.state == PlanState.APPLIED && kept()?.notInJet == listOf("STPT 16", "PPT 56", "UHF 3") && mission()?.plan == null,
            "the three edits sent again: applied, not in the jet ${kept()?.notInJet}, and still not on /api/mission")
    }

    // ================================================================================================ 4. refusals

    private fun refusals(x: Ctx, ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        line("")
        line("4. Refusals (P6): each a sentence, the plan unchanged")
        fun req(method: String, path: String, body: String? = null, query: Map<String, String> = emptyMap()): Pair<Int, String> {
            val r = Bridge.handle(ApiRequest(method, path, query, body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)))
            return r.status to r.body.toString(Charsets.UTF_8)
        }
        fun send(ps: PlanSend) = req("POST", "/api/plan", Bridge.json.encodeToString(PlanSend.serializer(), ps))
        fun held() = runCatching { lenient.decodeFromString(PlanOverlay.serializer(), req("GET", "/api/plan").second) }.getOrNull()
        val planFile = File(x.planDir, PlanStore.FILE_NAME)
        val keep = held()
        val keepBytes = planFile.takeIf { it.isFile }?.readBytes()
        fun same(what: String, status: Int, body: String, want: Int) {
            val said = errorOf(body)
            val still = held()?.id == keep?.id && (keepBytes == null || planFile.readBytes().contentEquals(keepBytes))
            ok(status == want && !said.isNullOrBlank() && still, "$what: $status \"$said\"" + if (still) "" else " — the plan changed")
        }
        same("not a plan (\"hello\")", req("POST", "/api/plan", "hello").first, req("POST", "/api/plan", "hello").second, 400)
        send(PlanSend(cartridge = "[Radio]\nUHF_1=251000\n")).let { same("a cartridge with no [STPT]", it.first, it.second, 400) }
        val big = x.text + "\n; " + "x".repeat(300 * 1024) + "\n"
        send(PlanSend(cartridge = big)).let { same("a 300 KB cartridge", it.first, it.second, 400) }
        req("POST", "/api/plan", "{\"cartridge\":\"" + "y".repeat(600 * 1024) + "\"}").let { same("a 600 KB body", it.first, it.second, 400) }
        send(PlanSend(cartridge = x.text, seat = 7)).let { same("seat 7", it.first, it.second, 400) }
        send(PlanSend(cartridge = x.text, ref = CampRef("Korea KTO", "Nope.cam", "1/0"))).let { same("a save that is not there", it.first, it.second, 400) }
        send(PlanSend(cartridge = x.text, ref = CampRef("Korea KTO", "..\\Auto Save.cam", "1/0"))).let { same("a path for a save", it.first, it.second, 400) }
        send(PlanSend(cartridge = x.text, ref = CampRef("Atlantis", "Auto Save.cam", "1/0"))).let { same("a theater that is not there", it.first, it.second, 400) }
        req("GET", "/api/plan/clear").let { same("GET /api/plan/clear", it.first, it.second, 405) }
        req("DELETE", "/api/plan").let { same("DELETE /api/plan", it.first, it.second, 405) }
        ok(req("GET", "/api/plan/nothing").first == 404, "an unknown /api/plan/… route is a 404")

        // no cartridge on disk: the copy's is moved aside for a moment
        val aside = File(x.cart.parentFile, x.cart.name + ".plantest-aside")
        val moved = runCatching { Files.move(x.cart.toPath(), aside.toPath(), StandardCopyOption.ATOMIC_MOVE) }.isSuccess
        try {
            if (moved) {
                req("POST", "/api/plan/files").let { same("the files with no cartridge on disk", it.first, it.second, 409) }
                send(PlanSend()).let { same("Send with no cartridge from the Planner and none on disk", it.first, it.second, 409) }
            } else ok(false, "the copy's cartridge moved aside")
        } finally {
            if (moved) runCatching { Files.move(aside.toPath(), x.cart.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            x.cart.setLastModified(x.cartTime)
        }
        ok(x.cart.isFile && x.cart.readBytes().contentEquals(x.cartBytes), "the cartridge is back")

        // a settings folder Windows will not let the program write
        val denied = File(x.planDir.parentFile, "denied-" + System.nanoTime() + "/BMS Companion").also { it.mkdirs() }
        val acl = icacls(denied, "/deny", "*S-1-1-0:(OI)(CI)(W)")
        try {
            PlanStore.folderOverride = denied
            val (st, body) = send(PlanSend(cartridge = x.text, from = "PC"))
            val said = errorOf(body)
            PlanStore.folderOverride = x.planDir
            ok(acl == 0 && st == 409 && said?.startsWith("The plan could not be kept on the PC") == true && held()?.id == keep?.id && (denied.listFiles()?.isEmpty() ?: true),
                "a settings folder with writes denied (icacls exit $acl): $st \"$said\"; the plan held is unchanged")
        } finally {
            PlanStore.folderOverride = x.planDir
            icacls(denied, "/remove:d", "*S-1-1-0")
            runCatching { denied.parentFile.deleteRecursively() }
        }

        // the plan file itself read-only
        if (planFile.isFile) {
            planFile.setReadOnly()
            try {
                val (st, body) = send(PlanSend(cartridge = x.text, from = "PC"))
                ok(st == 409 && errorOf(body)?.startsWith("The plan could not be kept on the PC") == true && held()?.id == keep?.id && planFile.readBytes().contentEquals(keepBytes ?: ByteArray(0)),
                    "${planFile.name} read-only: $st \"${errorOf(body)}\"; the plan and its file unchanged")
            } finally {
                planFile.setWritable(true)
            }
        } else ok(false, "a plan file to make read-only")
        PlanStore.forgetForCheck()
        ok(held()?.id == keep?.id, "read back from disk after all that, the same plan (id ${held()?.id})")
    }

    // ================================================================================================ helpers

    /** The fixture cartridge with STPT 16 moved 1,000 ft north, an SA-3 as PPT 56 and UHF 3 on 251.000. */
    private fun threeEdits(text: String): String {
        var t = movePoint(text, "target_15", 1000.0, 0.0)
        t = DtcIni.write(t, "STPT", "ppt_0", "1520374.500000, 1119576.750000, 0.000000, 72913.390625, SA3")
        return DtcIni.write(t, "Radio", "UHF_3", "251000")
    }

    /** VIP mode on STPT 3 (a route point, empty in the cartridge) with its pull-up point, and an OA1 on STPT 4, as WDP writes them. */
    private fun navOffsets(text: String): String {
        var t = DtcIni.write(text, "NAV OFFSETS", "Modesel", "vip")
        t = DtcIni.write(t, "NAV OFFSETS", "VIP", "3,20.0,48610,100")
        t = DtcIni.write(t, "NAV OFFSETS", "VIPPUP", "3,56.0,43705,100")
        return DtcIni.write(t, "NAV OFFSETS", "OA1-4", "37.3,34161,6748")
    }

    /** [key]'s point moved by [dn] north and [de] east, the rest of the line as it was. */
    private fun movePoint(text: String, key: String, dn: Double, de: Double): String {
        val l = text.lines().firstOrNull { it.trim().startsWith("$key=", ignoreCase = true) } ?: return text
        val f = l.substringAfter('=').split(',').toMutableList()
        f[0] = " %.6f".format(Locale.ROOT, (f[0].trim().toDoubleOrNull() ?: 0.0) + dn).trim()
        f[1] = " %.6f".format(Locale.ROOT, (f[1].trim().toDoubleOrNull() ?: 0.0) + de)
        return DtcIni.write(text, "STPT", key, f.joinToString(","))
    }

    /**
     * The same cartridge written differently: every position rounded to the foot, BMS's leading spaces before the
     * names doubled, CRLF line ends, IFF codes without their leading zeros, a trailing space after the frequencies.
     */
    private fun formattingOnly(text: String): String = text.lines().joinToString("\r\n") { l ->
        val m = Regex("^(target_\\d+|ppt_\\d+|lineSTPT_\\d+|wpntarget_\\d+)=(.*)$", RegexOption.IGNORE_CASE).find(l.trim())
        when {
            m != null -> {
                val f = m.groupValues[2].split(',')
                val g = f.mapIndexed { i, v ->
                    when {
                        i < 2 -> v.trim().toDoubleOrNull()?.let { "%.0f".format(Locale.ROOT, it) } ?: v
                        i >= 4 && v.startsWith(" ") -> "  $v"
                        else -> v
                    }
                }
                "${m.groupValues[1]}=${g.joinToString(",")}"
            }
            Regex("^Mode(1|2|3A) Code=0\\d+$").matches(l.trim()) -> l.trim().substringBefore('=') + "=" + l.trim().substringAfter('=').trimStart('0').ifEmpty { "0" }
            Regex("^(UHF|VHF)_\\d+=\\d+$").matches(l.trim()) -> l.trim() + " "
            else -> l
        }
    }

    /** The cartridge loaded into the Planner's model and written back by its writer, unchanged. */
    private fun plannerSave(text: String): String? = runCatching {
        val m = DtcModel()
        DtcLoad.loadCallsign(text, m)
        val r = DtcSave.saveCallsign(text, m)
        if (r.threw) null else r.text
    }.getOrNull()

    private fun <T> okValue(a: CampaignFiles.Answer<T>): T? = (a as? CampaignFiles.Answer.Ok<T>)?.value

    private fun errorOf(body: String): String? =
        runCatching { ((lenient.parseToJsonElement(body) as JsonObject)["error"] as? JsonPrimitive)?.content }.getOrNull()

    /** Writes the copy's file the way BMS or WDP would replace it: beside it, then moved over it. */
    private fun writeFile(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".plantest-new")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun icacls(dir: File, vararg args: String): Int = runCatching {
        ProcessBuilder(listOf("icacls", dir.path) + args).redirectErrorStream(true).start().let { p ->
            p.inputStream.readBytes()
            p.waitFor()
        }
    }.getOrDefault(-1)

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
