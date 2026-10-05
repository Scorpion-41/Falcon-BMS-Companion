package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.AttackCue
import com.bmscompanion.app.data.mission.AttackOverlay
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.NavPoint
import com.bmscompanion.app.data.wdp.DtcLoad
import com.bmscompanion.app.data.wdp.DtcModel
import com.bmscompanion.app.data.wdp.DtcSave
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.math.hypot

/**
 * `--plantest <parse|route|store> <fixture copy> [<scratch APPDATA>] out.txt`: the cartridge parser, BMS's mission-file route, and the plan store. [inputs] are the arguments between the part and out.txt.
 *
 * The dispatch in [SelfTest] is fixed (SelfTest is frozen after B0b): keep this signature. A line of the report starting with "FAIL" makes the check exit 1.
 * Built by P1 (parse), P3 (route), P2 (store), in turns; each part is its own function below, except the store part, which is [PlanStoreTest].
 */
object PlanTest {
    fun run(part: String, inputs: List<String>): String = when (part) {
        "parse" -> parse(inputs)
        "route" -> route(inputs)
        "store" -> PlanStoreTest.run(inputs)
        else -> "FAIL: --plantest has no part '$part' (parse, route or store).\n"
    }

    // ------------------------------------------------------------------------------------------------ parse (P1)

    /**
     * `--plantest parse <copy of a BMS folder> [api] [more files or folders…] out.txt` — the DTC parser ([DtcParser],
     * [PptTable], [DtcRoute]) and the 3D navpoints ([SharedMemoryReader.parseNavPoint]), against files BMS wrote:
     * - the pilot's cartridge in `User/Config` (4.38.1: the route slots empty, the recon targets in STPT 15-22);
     * - BMS's mission file beside the Korea campaign save (`Auto Save.ini`): its route set against the printed briefing,
     *   row for row, in count and in action words; the targets 15-22 not on the route;
     * - a TE's mission file (`TE_BMS_03_F-16_DEAD.ini`): its PPT codes resolved through the theater's `Ppt.ini`, the
     *   markers (AWACS, tanker) without rings, the ranges in feet;
     * - the Planner's own cartridge text: the fixture cartridge loaded into the DTC page's model, given four lines with
     *   holes, PPTs, nav offsets, TACAN/ILS, laser codes, ICP and EWS names, written by [DtcSave] and parsed back;
     * - every DTC-shaped `.ini` of every campaign folder (and any extra files or folders given), parsed without an
     *   exception and encoded to JSON;
     * - BMS's navpoint strings: O1/O2 kept, L1-L4 and CB kept, a PPT marker with no ring.
     *
     * With `api`, the Planner's cartridge text is put in place of the copy's cartridge (the copy only: it refuses a folder
     * [DevGuard] does not call a copy, or settings that point elsewhere) and `/api/mission` is answered in-process, to show
     * what a client receives.
     */
    private fun parse(inputs: List<String>): String {
        val out = StringBuilder()
        fun line(s: String) { out.append(s).append('\n') }
        fun ok(pass: Boolean, what: String) = line((if (pass) "PASS: " else "FAIL: ") + what)
        val root = inputs.firstOrNull()?.let(::File)
        if (root == null || !root.isDirectory) return "FAIL: usage: --plantest parse <copy of a BMS folder> [api] [more…] out.txt (no folder '${inputs.firstOrNull()}')\n"
        val api = inputs.drop(1).any { it.equals("api", ignoreCase = true) }
        val extra = inputs.drop(1).filterNot { it.equals("api", ignoreCase = true) }.map(::File)
        line("--plantest parse: the DTC parser against ${root.path}")
        line("")

        val ts = Theaters.at(root)
        val korea = ts.byName("Korea KTO") ?: ts.theaters.firstOrNull()
        val campaign = ts.campaignDir(korea)
        val strings = ts.strings(korea)
        val ppt = PptTable.read(campaign).orEmpty()
        ok(campaign != null && ppt.isNotEmpty(), "Korea's campaign folder ${campaign?.name} and its Ppt.ini (${ppt.size} types, ${ppt.count { it.marker }} markers)")

        // 1. the pilot's cartridge, as BMS 4.38.1 wrote it
        line("")
        line("1. The pilot's cartridge (User/Config/<callsign>.ini)")
        val config = File(root, "User/Config")
        val cart = config.listFiles { f -> f.isFile && f.name.endsWith(".ini", true) && !f.name.contains("_Def", true) }
            ?.filter { runCatching { it.readText(Charsets.ISO_8859_1) }.getOrDefault("").let { t -> "[STPT]" in t && "[Radio]" in t } }
            ?.maxByOrNull { it.lastModified() }
        ok(cart != null, "a cartridge is in User/Config")
        var cartText: String? = null
        if (cart != null) {
            cartText = cart.readText(Charsets.UTF_8)
            val d = DtcParser.parse(cart)
            ok(d == DtcParser.parseText(cartText).copy(modified = cart.lastModified()), "parse(File) is parseText(its text) with the file's time")
            val targets = d.steerpoints.filter { it.n in 1..24 && it.isTarget }
            line("   steerpoints ${d.steerpoints.size} (targets ${targets.map { it.n }}), route points ${DtcRoute.points(d).size}, weapon targets ${d.weaponTargets.size}, PPTs ${d.ppts.size}, line points ${d.lines.size}, UHF ${d.uhf.size}, VHF ${d.vhf.size}, IFF ${d.iff.keys}")
            ok(targets.isNotEmpty() && targets.all { it.action == -1 && !it.name.isNullOrBlank() }, "the precision targets keep action -1 and BMS's names (e.g. '${targets.firstOrNull()?.name}')")
            ok(DtcRoute.points(d).none { it.isTarget }, "no precision target is a route point")
            val raw = { key: String -> Regex("(?m)^\\s*$key\\s*=\\s*(\\S+)").find(cartText)?.groupValues?.get(1)?.toIntOrNull() }
            ok(d.comm != null && d.comm?.comm1 == raw("Comm1") && d.comm?.comm2 == raw("Comm2"), "[COMMS] Comm1/Comm2 read (${d.comm?.comm1}/${d.comm?.comm2}); no TACAN or ILS there: ${d.comm?.tacan}/${d.comm?.ils}")
            ok(d.navOffsets == null && d.laserTgp == null && d.bingoLbs == null && d.ewsNames.isEmpty(), "BMS writes no [NAV OFFSETS], [Laser], [ICP] or EWS names: all absent, not zero")
            ok(d.open.all { it.n in 81..99 } && d.steerpoints.containsAll(d.open), "the 81-99 bank (${d.open.size} points) is in open and still in steerpoints")
        }

        // 2. BMS's mission file beside the campaign save, against the printed briefing
        line("")
        line("2. BMS's mission file beside the Korea campaign save (Auto Save.ini) and the printed briefing")
        val auto = campaign?.let { File(it, "Auto Save.ini") }
        val briefingFile = File(root, "User/Briefings/briefing.txt")
        val briefing = runCatching { BriefingParser.parse(briefingFile.readText(Charsets.UTF_8)) }.getOrNull()
        ok(briefing != null && briefing.steerpoints.isNotEmpty(), "the printed briefing reads: ${briefing?.overview?.let { "package ${it.packageId}" }}, ${briefing?.steerpoints?.size} steerpoint rows")
        if (auto != null && auto.isFile) {
            val d = DtcParser.parse(auto)
            val route = DtcRoute.points(d)
            ok(route.map { it.action } == listOf(1, 0, 12, 12, 0, 7, 4, 7), "the route is ${route.size} points, actions ${route.map { it.action }} (want [1, 0, 12, 12, 0, 7, 4, 7])")
            ok(route.map { it.n } == (1..8).toList(), "the route is STPT 1-8")
            val targets = d.steerpoints.filter { it.isTarget }.map { it.n }
            ok(targets == (15..22).toList(), "STPT 15-22 are precision targets: $targets")
            ok(route.none { it.n in 15..22 }, "and none of them is on the route line")
            if (briefing != null) {
                val why = DtcRoute.mismatch(route, briefing.steerpoints, strings)
                ok(why == null, "the route matches the briefing in count and words" + (why?.let { ": $it" } ?: ": " + route.joinToString(" ") { "${it.n}=${DtcRoute.word(it.action, strings)}" }))
                val refuel = route.firstOrNull { it.action == 4 }
                line("   the Refuel steerpoint (tanker station) is at ${refuel?.let { "%.0f, %.0f".format(it.x, it.y) }}; the briefing row: ${briefing.steerpoints.firstOrNull { it.n == refuel?.n }?.comments}")
            }
        } else ok(false, "Auto Save.ini is in the campaign folder")
        if (briefing != null && campaign != null) {
            // two files that must NOT match: another flight's route, and a TE
            listOf("Save-Day  1 04 11 35.ini", "TE_BMS_03_F-16_DEAD.ini").map { File(campaign, it) }.filter { it.isFile }.forEach { f ->
                val why = DtcRoute.mismatch(DtcRoute.points(DtcParser.parse(f)), briefing.steerpoints, strings)
                ok(why != null, "${f.name} is not the briefed flight: $why")
            }
        }

        // 3. a TE's mission file: PPT codes, markers, feet
        line("")
        line("3. A TE's mission file (TE_BMS_03_F-16_DEAD.ini): PPTs through the theater's Ppt.ini")
        val te = campaign?.let { File(it, "TE_BMS_03_F-16_DEAD.ini") }
        if (te != null && te.isFile) {
            val d = DtcParser.parse(te)
            val route = DtcRoute.points(d)
            ok(route.map { it.action } == listOf(1, 0, 8, 2, 0, 0, 14, 3, 0, 7), "the TE's route is ${route.size} points, actions ${route.map { it.action }}")
            ok(d.ppts.map { it.n } == listOf(56, 57, 58, 59, 60, 62), "PPTs ${d.ppts.map { it.n }} (ppt_5, all zero, skipped)")
            for (p in d.ppts) {
                val t = PptTable.resolve(ppt, p.code)
                line("   PPT ${p.n}: code '${p.code}' -> ${t?.name ?: "(not in Ppt.ini)"}; range ${p.rangeFt} ft = ${"%.2f".format(p.rangeNm)} nm; marker ${p.marker}; name (old meaning) '${p.name}'")
            }
            val names = d.ppts.filterNot { it.marker }.map { PptTable.resolve(ppt, it.code)?.name }
            ok(names == listOf("SA-10", "SA-3", "AAA", "Patriot"), "the threat codes resolve to $names (want SA-10, SA-3, AAA, Patriot)")
            val markers = d.ppts.filter { it.marker }
            ok(markers.map { PptTable.resolve(ppt, it.code)?.name } == listOf("AWACS", "TANKER") && markers.all { it.rangeNm == 0.0 && it.rangeFt < 1.0 },
                "AWC and TNK are markers with no ring: rangeNm ${markers.map { it.rangeNm }}, rangeFt ${markers.map { it.rangeFt }}")
            ok(d.ppts.filterNot { it.marker }.all { abs(it.rangeNm - it.rangeFt / DtcParser.FT_PER_NM) < 1e-9 && it.rangeFt > 1000 }, "every ring's range is in feet in the file and ft/6076.12 in nm")
            ok(d.ppts.all { it.name == it.code }, "name keeps its old meaning (the code) for older clients")
            ok(d.ppts.firstOrNull { it.code == "10" }?.let { abs(it.rangeNm - 50.0) < 0.01 } == true, "SA-10: 303805.78 ft is a 50.0 nm ring")
        } else ok(false, "TE_BMS_03_F-16_DEAD.ini is in the campaign folder")

        // 4. the Planner's own cartridge text, written by DtcSave and parsed back
        line("")
        line("4. The Planner's cartridge text (the DTC page's model written by DtcSave, parsed back)")
        val planner = cartText?.let { plannerCartridge(it) }
        if (planner == null) ok(false, "the Planner's cartridge could not be made (no cartridge, or DtcSave threw)")
        else {
            val d = DtcParser.parseText(planner)
            val base = DtcParser.parseText(cartText!!)
            val byLine = (1..4).map { l -> d.lines.filter { it.line == l } }
            line("   lines: " + byLine.mapIndexed { i, ps -> "line ${i + 1} = ${ps.map { it.n }}" }.joinToString("; "))
            ok(byLine.map { it.size } == listOf(3, 6, 2, 6), "four lines of up to six points, zero points skipped: ${byLine.map { it.size }} (want [3, 6, 2, 6])")
            ok(byLine[2].map { it.n } == listOf(12, 14), "line 3 keeps its own points around the hole (12, 14) and is never joined to line 2 or 4")
            ok(d.lines.all { p -> LINES[p.n]?.let { (n, e) -> p.x == n && p.y == e } == true }, "each line point is where the Planner put it (x north, y east)")
            ok(d.lines.map { it.n } == d.lines.map { it.n }.sorted(), "line points come in order")
            val pp = d.ppts.associateBy { it.n }
            ok(pp[56]?.let { it.code == "SA3" && !it.marker && abs(it.rangeFt - 72913.39) < 0.01 } == true, "PPT 56: SA3, 72913.39 ft, a ring of ${pp[56]?.rangeNm?.let { "%.2f".format(it) }} nm")
            ok(pp[57]?.let { it.code == "AWC" && it.marker && it.rangeNm == 0.0 } == true, "PPT 57: AWC, a marker")
            ok(pp[58]?.let { it.code == "10" && abs(it.rangeNm - 50.0) < 0.01 } == true, "PPT 58: code 10, SA-10's 50 nm")
            val nv = d.navOffsets
            line("   nav offsets: $nv")
            ok(nv?.mode == "vip", "Modesel vip")
            ok(nv?.vip?.let { it.key == "VIP" && it.stpt == 6 && it.bearing == 20.0 && it.rangeFt == 48610.0 && it.elevFt == 100.0 } == true, "VIP = STPT 6, 20.0°, 48610 ft, 100 ft")
            ok(nv?.vipPup?.let { it.stpt == 6 && it.bearing == 56.0 && it.rangeFt == 43705.0 } == true, "VIPPUP = STPT 6, 56.0°, 43705 ft")
            ok(nv?.vrp == null && nv?.vrpPup == null, "a VRP on steerpoint 0 (nobody set it) is absent")
            ok(nv?.oa?.map { it.key } == listOf("OA1-6", "OA2-6") && nv.oa[0].let { it.bearing == 37.3 && it.rangeFt == 34161.0 && it.elevFt == 6748.0 } && nv.oa[1].elevFt == 0.0,
                "OA1-6 = 37.3°, 34161 ft, 6748 ft and OA2-6 kept with elevation 0")
            ok(d.comm?.let { it.tacan == "12Y A/A" && it.ils == "109.30" && it.ilsCrs == 332 } == true, "[COMMS] TACAN ${d.comm?.tacan}, ILS ${d.comm?.ils} course ${d.comm?.ilsCrs} (want 12Y A/A, 109.30, 332)")
            ok(d.comm?.comm1 == base.comm?.comm1 && d.comm?.comm2 == base.comm?.comm2, "Comm1/Comm2 unchanged by the save (${d.comm?.comm1}/${d.comm?.comm2})")
            ok(d.laserSt == 8 && d.laserTgp == 1511 && d.laserLst == 1688, "[Laser] ST ${d.laserSt}, TGP ${d.laserTgp}, LST ${d.laserLst} (want 8, 1511, 1688)")
            ok(d.bingoLbs == 3200 && d.alowFt == 500 && d.mslFloorFt == 12000, "[ICP] bingo ${d.bingoLbs} lb, ALOW ${d.alowFt} ft, MSL floor ${d.mslFloorFt} ft (want 3200, 500, 12000)")
            ok(d.ewsNames == EWS_NAMES, "EWS program names ${d.ewsNames}")
            // DtcSave prints a float's exact value to six places rounding a half up, where BMS's %f gave 130944.664062 for
            // 130944.6640625: a millionth of a foot, so positions are compared to a thousandth
            fun near(a: List<com.bmscompanion.app.data.mission.DtcPoint>, b: List<com.bmscompanion.app.data.mission.DtcPoint>) = a.size == b.size &&
                a.zip(b).all { (p, q) -> p.copy(x = 0.0, y = 0.0, altFt = 0.0) == q.copy(x = 0.0, y = 0.0, altFt = 0.0) && abs(p.x - q.x) < 1e-3 && abs(p.y - q.y) < 1e-3 && abs(p.altFt - q.altFt) < 1e-3 }
            val exact = d.steerpoints.zip(base.steerpoints).count { (a, b) -> a != b }
            if (exact > 0) line("   ${exact} steerpoint(s) differ from BMS's text by formatting only (a millionth of a foot), e.g. " +
                d.steerpoints.zip(base.steerpoints).first { (a, b) -> a != b }.let { (a, b) -> "STPT ${a.n} y ${a.y} vs ${b.y}" })
            val changed = listOfNotNull(
                "steerpoints".takeIf { !near(d.steerpoints, base.steerpoints) }, "UHF".takeIf { d.uhf != base.uhf }, "VHF".takeIf { d.vhf != base.vhf },
                "IFF".takeIf { d.iff != base.iff }, "weapon targets".takeIf { d.weaponTargets != base.weaponTargets },
            )
            ok(changed.isEmpty(), "the steerpoints, presets, IFF and weapon targets are the cartridge's own, unchanged by the Planner's write" +
                if (changed.isEmpty()) "" else ": $changed differ")
            for (c in changed) when (c) {
                "steerpoints" -> d.steerpoints.zip(base.steerpoints).filter { (a, b) -> a != b }.take(3).forEach { (a, b) -> line("   after $a\n   before $b") }
                "UHF" -> d.uhf.zip(base.uhf).filter { (a, b) -> a != b }.take(3).forEach { (a, b) -> line("   after $a / before $b") }
                "VHF" -> d.vhf.zip(base.vhf).filter { (a, b) -> a != b }.take(3).forEach { (a, b) -> line("   after $a / before $b") }
                "IFF" -> line("   after ${d.iff} / before ${base.iff}")
                else -> line("   after ${d.weaponTargets.take(3)} / before ${base.weaponTargets.take(3)}")
            }
            ok(runCatching { Bridge.json.encodeToString(Dtc.serializer(), d) }.isSuccess, "it encodes to JSON")
        }

        // 5. every DTC file of every campaign folder, and anything else given
        line("")
        line("5. Every DTC-shaped .ini of every campaign folder" + if (extra.isNotEmpty()) ", and ${extra.size} more given" else "")
        val files = ArrayList<Pair<Theaters.Theater?, File>>()
        for (t in ts.theaters) ts.campaignDir(t)?.listFiles { f -> f.isFile && f.name.endsWith(".ini", true) }?.sortedBy { it.name }?.forEach { files += t to it }
        cart?.let { files += korea to it }
        fun walk(f: File) { if (f.isDirectory) f.listFiles()?.sortedBy { it.name }?.forEach(::walk) else if (f.name.endsWith(".ini", true)) files += null to f }
        extra.forEach(::walk)
        var dtcFiles = 0; var failed = 0; var routes = 0; var targets = 0; var ppts = 0; var markers = 0; var unresolved = 0; var lines = 0; var offsets = 0
        val seen = HashSet<String>()
        val unresolvedCodes = LinkedHashSet<String>()
        val pptBy = HashMap<String, List<PptTable.Type>>()
        for ((t, f) in files) {
            if (!seen.add(f.canonicalPath)) continue
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            if (!text.contains("[STPT]", ignoreCase = true)) continue
            dtcFiles++
            val r = runCatching {
                val d = DtcParser.parse(f)
                Bridge.json.encodeToString(Dtc.serializer(), d)
                check(d == DtcParser.parseText(text).copy(modified = f.lastModified())) { "parse(File) differs from parseText" }
                d
            }
            val d = r.getOrNull()
            if (d == null) { failed++; line("FAIL: ${f.name}: ${r.exceptionOrNull()}"); continue }
            routes += DtcRoute.points(d).size; targets += d.steerpoints.count { it.isTarget }; lines += d.lines.size
            ppts += d.ppts.size; markers += d.ppts.count { it.marker }; offsets += d.navOffsets?.oa?.size ?: 0
            val table = t?.let { th -> pptBy.getOrPut(th.name) { PptTable.read(ts.campaignDir(th)).orEmpty() } }
            if (table != null) d.ppts.filter { PptTable.resolve(table, it.code) == null }.forEach { unresolved++; unresolvedCodes += "${t.name}:${it.code}" }
        }
        ok(failed == 0 && dtcFiles > 0, "$dtcFiles DTC files parsed and encoded, $failed failed")
        line("   route points $routes, precision targets $targets, PPTs $ppts ($markers markers), line points $lines, offset aimpoints $offsets")
        line("   PPT codes not in their theater's Ppt.ini: $unresolved ${unresolvedCodes.take(12)}")

        // 6. BMS's navpoint strings
        line("")
        line("6. The 3D navpoints (shared memory's NavPoint strings)")
        fun np(s: String) = SharedMemoryReader.parseNavPoint(s)
        val wp = np("NP:6,WP,1513710.75,1169312.875,-76.7,0;O1:37.3,34161,6748;O2:37.3,34161,0;")
        ok(wp?.let { it.type == "WP" && it.oa1Brg == 37.3 && it.oa1RngFt == 34161.0 && it.oa1ElevFt == 6748.0 && it.oa2Brg == 37.3 && it.oa2RngFt == 34161.0 && it.oa2ElevFt == 0.0 } == true,
            "O1/O2 kept on the steerpoint: ${wp?.let { "${it.oa1Brg}/${it.oa1RngFt}/${it.oa1ElevFt}, ${it.oa2Brg}/${it.oa2RngFt}/${it.oa2ElevFt}" }}")
        ok(np("NP:7,WP,1,2,3,0;")?.oa1Brg == null, "a steerpoint without offsets has none")
        val kinds = listOf("NP:31,L1,1161314,2298948,0,0;", "NP:37,L2,1,2,0,0;", "NP:43,L3,1,2,0,0;", "NP:54,L4,1,2,0,0;", "NP:25,CB,1100000,1300000,0,0;").map { np(it)?.type }
        ok(kinds == listOf("L1", "L2", "L3", "L4", "CB"), "line and bullseye points are kept: $kinds")
        val awacs = np("NP:59,PT,974734,1548012.5,0,0;PT:\"AWACS\",0.1,0;")
        ok(awacs?.let { it.name == "AWACS" && it.rangeNm == 0.0 } == true, "a PPT marker (0.1 ft) has rangeNm 0, not a 0.1 nm ring: ${awacs?.rangeNm}")
        val sa5 = np("NP:56,PT,2910660.5,258538.0,0.0,52.1;PT:\"SA-5\",364567.0,0;")
        ok(sa5?.rangeNm == 364567.0 / 6076.12, "a ring keeps ft/6076.12 (the --selftest line): ${sa5?.rangeNm}")
        val sa3 = np("NP:57,PT,1520374.5,1119576.75,0,0;PT:\"SA-3\",72913.39,0;")
        ok(sa3?.rangeNm?.let { abs(it - 12.0) < 0.01 } == true, "SA-3 72913.39 ft = ${sa3?.rangeNm?.let { "%.2f".format(it) }} nm")
        val nan = np("NP:8,WP,NaN,Infinity,2,0;")
        ok(nan?.x == 0.0 && nan.y == 0.0 && runCatching { Bridge.json.encodeToString(NavPoint.serializer(), nan) }.isSuccess, "a NaN or infinite value reads as 0, so the answer still encodes")

        // 7. what a client receives (only with "api", only into a copy)
        if (api) {
            line("")
            line("7. /api/mission with the Planner's cartridge in the copy")
            apiStep(root, planner, ::ok, ::line)
        }

        val fails = out.lines().count { it.startsWith("FAIL") }
        line("")
        line(if (fails == 0) "ALL PASS" else "$fails FAIL")
        return out.toString()
    }

    /** STPT 31-54 as the synthetic cartridge sets them: index → (north, east). Holes: 3-5 (the end of line 1), 13 and 15-17 (line 3 is 12 and 14). */
    private val LINES: Map<Int, Pair<Double, Double>> = buildMap {
        for (i in 0..23) {
            if (i in 3..5 || i == 13 || i in 15..17) continue
            put(i, (1_100_000.0 + i * 4_000.25) to (1_300_000.0 + i * 2_500.5))
        }
    }
    private val EWS_NAMES = listOf("Slap", "Defensive", "", "SAM", "Flare only", "Last ditch")

    /**
     * The fixture cartridge through the Planner's own model and writer: loaded as the DTC page loads it, given lines
     * (with holes), PPTs, nav offsets, TACAN/ILS, laser codes, ICP and EWS names, and saved as the page saves it.
     */
    private fun plannerCartridge(text: String): String? = runCatching {
        val m = DtcModel()
        DtcLoad.loadCallsign(text, m)
        for (i in 0..23) {
            val p = LINES[i]
            // the model's falconY is north (the file's first field), falconX east
            m.line[i].falconY = p?.first?.toFloat() ?: 0f; m.line[i].falconX = p?.second?.toFloat() ?: 0f; m.line[i].falconZ = 0f
        }
        fun ppt(i: Int, n: Float, e: Float, rng: Float, code: String) { m.ppt[i].falconY = n; m.ppt[i].falconX = e; m.ppt[i].falconRng = rng; m.ppt[i].code = code }
        ppt(0, 1_520_374.5f, 1_119_576.75f, 72_913.39f, "SA3")
        ppt(1, 974_734f, 1_548_012.5f, 0.1f, "AWC")
        ppt(2, 1_513_902.125f, 1_168_736.375f, 303_805.78f, "10")
        m.nav.modesel = 1
        fun off(o: com.bmscompanion.app.data.wdp.DtcOffset, stpt: Int, brg: Float, rng: Int, elv: Int) { o.stpt = stpt; o.bearing = brg; o.range = rng; o.elv = elv }
        off(m.nav.vip, 6, 20f, 48610, 100); off(m.nav.vipPup, 6, 56f, 43705, 100)
        off(m.nav.vrp, 0, 0f, 0, 0); off(m.nav.vrpPup, 0, 0f, 0, 0)
        off(m.nav.oa1_1, 6, 37.3f, 34161, 6748); off(m.nav.oa2_1, 6, 37.3f, 34161, 0)
        off(m.nav.oa1_2, 0, 0f, 0, 0); off(m.nav.oa2_2, 0, 0f, 0, 0)
        m.comm.tacanChannel = 12; m.comm.tacanBand = 1; m.comm.tacanDomain = 1; m.comm.ilsFrequency = 10930; m.comm.ilsCrs = 332
        m.laser.laserSt = 8; m.laser.laserCode = 1511; m.laser.lstCode = 1688
        m.icp.alowAgl = 500f; m.icp.alowMsl = 12000; m.icp.bingoFuel = 3200f
        m.ews.program?.forEachIndexed { i, p -> p.comment = EWS_NAMES[i] }
        val r = DtcSave.saveCallsign(text, m)
        if (r.threw) null else r.text
    }.getOrNull()

    /** Puts [planner] in place of the copy's cartridge and answers `/api/mission` in-process ([Bridge.startForCheck]). */
    private fun apiStep(root: File, planner: String?, ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        val why = DevGuard.why(root)
        if (why != null) { ok(false, "the api step writes the copy's cartridge, and ${root.path} is not a copy: $why"); return }
        if (planner == null) { ok(false, "no Planner cartridge to put in place"); return }
        // the settings are applied first: the cartridge's path comes from them (BmsDirOverride) and the registry's pilot
        Bridge.startForCheck()
        try {
            val ini = Bridge.callsignIni?.let(::File)
            val inside = ini != null && Theaters.canonical(ini).startsWith(Theaters.canonical(root) + "\\")
            if (ini == null || !inside) { ok(false, "the settings must point at the copy (BmsDirOverride); the cartridge would be in ${ini?.parentFile?.path ?: "no folder"}"); return }
            val tmp = File(ini.parentFile, ini.name + ".plantest-new")
            val wrote = runCatching {
                tmp.writeText(planner, Charsets.UTF_8)
                Files.move(tmp.toPath(), ini.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            ok(wrote.isSuccess, "the Planner's cartridge text is in the copy's cartridge (User/Config/<callsign>.ini)" + (wrote.exceptionOrNull()?.let { ": $it" } ?: ""))
            if (wrote.isFailure) return
            val res = Bridge.handle(ApiRequest("GET", "/api/mission"))
            val body = res.body.toString(Charsets.UTF_8)
            val md = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(MissionData.serializer(), body) }.getOrNull()
            ok(res.status == 200 && md != null, "/api/mission answers ${res.status} and decodes as MissionData")
            val dtc = md?.dtc
            ok(dtc != null && dtc.ppts.isNotEmpty() && dtc.ppts.all { it.code != null && it.rangeFt > 0 }, "dtc.ppts carry code and rangeFt: " +
                dtc?.ppts?.joinToString { "${it.n} ${it.code} ${it.rangeFt} ft${if (it.marker) " marker" else " %.2f nm".format(it.rangeNm)}" })
            ok(dtc?.ppts?.filter { it.marker }?.map { it.code } == listOf("AWC"), "the marker is flagged (AWC), and has rangeNm ${dtc?.ppts?.firstOrNull { it.marker }?.rangeNm}")
            ok(dtc?.lines?.groupBy { it.line }?.mapValues { it.value.size } == mapOf(1 to 3, 2 to 6, 3 to 2, 4 to 6), "dtc.lines carry line 1-4: ${dtc?.lines?.groupBy { it.line }?.mapValues { it.value.size }}")
            ok(dtc?.navOffsets?.mode == "vip" && dtc.comm?.tacan == "12Y A/A" && dtc.laserTgp == 1511 && dtc.bingoLbs == 3200 && dtc.ewsNames.size == 6,
                "navOffsets, comm, laser, ICP and EWS names reach the client")
            // the raw JSON of the PPTs, as a client sees it
            Regex("\"ppts\":\\[[^\\]]*]").find(body)?.value?.let { line("   $it") }
        } finally {
            Bridge.stop()
        }
    }

    // ------------------------------------------------------------------------------------------------ route (P3)

    /**
     * `--plantest route <copy of a BMS folder> out.txt` — BMS's mission file beside the save being flown
     * ([MissionDtcFile], R3-PLAN A11):
     * - which file: the newest save of the theater, and the mission file its header names (`SaveFile`), not the one its
     *   `[MISSION] title` names;
     * - believed: `Auto Save.ini` against the printed briefing and Cyborg6's waypoints in `Auto Save.cam`;
     * - not believed, each with its sentence: `Save-Day  1 04 11 35.ini` (another flight's), a route WDP zeroed, a
     *   point moved 50 ft, no printed briefing, a briefing for another flight, a briefing with a row fewer;
     * - the header bullseye in both orders (X east, Y north, the cell's middle: settled by BMS's own ACMI, D62);
     * - in-process against the copy (the settings must point at it): `/api/mission.route`, `routeModified` in the
     *   version and in `/api/info`, the tanker and AWACS tracks before 3D (and what the cartridge alone gave),
     *   `GET /api/attack`, and a PRINT another caller saw first still waiting for the tick.
     *
     * The copy is only read, except the briefing's file time, which is moved and put back (and only in a copy
     * [DevGuard] accepts). The mutated mission files go to a temporary folder that is removed afterwards.
     */
    private fun route(inputs: List<String>): String {
        val out = StringBuilder()
        fun line(s: String) { out.append(s).append('\n') }
        fun ok(pass: Boolean, what: String) = line((if (pass) "PASS: " else "FAIL: ") + what)
        fun done(): String {
            val fails = out.lines().count { it.startsWith("FAIL") }
            line("")
            line(if (fails == 0) "ALL PASS" else "$fails FAIL")
            return out.toString()
        }
        val root = inputs.firstOrNull()?.let(::File)
        if (root == null || !root.isDirectory) return "FAIL: usage: --plantest route <copy of a BMS folder> out.txt (no folder '${inputs.firstOrNull()}')\n"
        line("--plantest route: BMS's mission file beside the save (R3-PLAN A11) against ${root.path}")
        line("")

        // 1. which save, which mission file
        line("1. Which save, and which mission file")
        val ts = Theaters.at(root)
        val korea = ts.byName("Korea KTO")
        val campaign = ts.campaignDir(korea)
        ok(korea != null && campaign != null, "Korea KTO and its campaign folder (${campaign?.name})")
        if (korea == null || campaign == null) return done()
        val names = CampaignArchive.names(ts, korea)
        val strings = ts.strings(korea)
        val newest = ts.newestSave(korea)
        ok(newest?.name == "Auto Save.cam", "the newest save of Korea KTO is ${newest?.name} (want Auto Save.cam)")
        val autoFile = File(campaign, "Auto Save.cam")
        val auto = CampaignArchive.read(autoFile, names)
        ok(auto.error == null && auto.exact, "Auto Save.cam reads whole: ${auto.flights.size} flights, ${auto.packages.size} packages" + (auto.error?.let { " ($it)" } ?: ""))
        val autoIni = MissionDtcFile.iniFor(auto)
        ok(auto.header?.saveFile == "Auto Save" && autoIni?.name == "Auto Save.ini", "its header's SaveFile '${auto.header?.saveFile}' names ${autoIni?.name}")
        val teFile = File(campaign, "TE_BMS_03_F-16_DEAD.tac")
        if (teFile.isFile) {
            val te = CampaignArchive.read(teFile, names)
            val teIni = MissionDtcFile.iniFor(te)
            val title = teIni?.let { f -> runCatching { f.readLines(Charsets.ISO_8859_1) }.getOrNull() }
                ?.firstOrNull { it.trim().startsWith("title=", ignoreCase = true) }?.substringAfter('=')?.trim()
            ok(te.header?.saveFile == "TE_BMS_03_F-16_DEAD" && teIni?.name == "TE_BMS_03_F-16_DEAD.ini" && title == "TE_BMS_03_F-16_SEAD",
                "a TE's mission file is found by the header's SaveFile '${te.header?.saveFile}' -> ${teIni?.name}, not by its [MISSION] title '$title'")
        } else ok(false, "TE_BMS_03_F-16_DEAD.tac is in the campaign folder")

        // 2. the briefed flight's own file is believed
        line("")
        line("2. Auto Save.ini against the printed briefing and the save")
        val briefing = runCatching { BriefingParser.parse(File(root, "User/Briefings/briefing.txt").readText(Charsets.UTF_8)) }.getOrNull()
        ok(briefing != null && briefing.steerpoints.size == 8, "the printed briefing: ${briefing?.overview?.flight}, package ${briefing?.overview?.packageId}, " +
            "flight #${briefing?.`package`?.firstOrNull { it.primary }?.flightId}, ${briefing?.steerpoints?.size} steerpoint rows")
        val flight = briefing?.let { MissionDtcFile.briefedFlight(auto, it) }
        val label = flight?.let { MissionDtcFile.label(auto, it) }
        ok(label == "Cyborg6 #7289, package 7288", "the briefed flight in Auto Save.cam: $label")
        val v = MissionDtcFile.check(auto, autoIni, briefing, strings, korea.name)
        ok(v.believed, "Auto Save.ini is believed: ${v.reason}")
        val r = v.believedRoute
        val pts = r?.let { DtcRoute.points(Dtc(steerpoints = it.steerpoints)) }.orEmpty()
        ok(pts.map { it.n } == (1..8).toList() && pts.map { it.action } == listOf(1, 0, 12, 12, 0, 7, 4, 7),
            "the route is STPT ${pts.map { it.n }}, actions ${pts.map { it.action }} = " + pts.joinToString(" ") { DtcRoute.word(it.action, strings) ?: "?" })
        ok(r?.steerpoints?.filter { it.isTarget }?.map { it.n } == (15..22).toList(),
            "the file's precision targets come along, off the route: ${r?.steerpoints?.filter { it.isTarget }?.map { it.n }} (${r?.steerpoints?.size} steerpoints in all)")
        if (flight != null) {
            val gaps = pts.map { p -> flight.waypoints.getOrNull(p.n - 1)?.let { hypot(p.x - it.north, p.y - it.east) } ?: Double.MAX_VALUE }
            ok(gaps.all { it <= MissionDtcFile.SAME_POINT_FT }, "each point against the save's cell + ½: " + gaps.joinToString(" ") { "%.3f".format(it) } + " ft")
        }
        ok(r != null && r.file == "Auto Save.ini" && r.save == "Auto Save.cam" && r.kind == CampKind.CAMPAIGN && autoIni != null && r.modified == autoIni.lastModified(),
            "MissionRoute: file ${r?.file}, save ${r?.save}, kind ${r?.kind}, modified ${r?.modified}, ${r?.ppts?.size} PPTs, ${r?.lines?.size} line points, ${r?.weaponTargets?.size} weapon targets")
        pts.firstOrNull { it.action == 4 }?.let { p -> line("   the Refuel steerpoint, where the tanker's station is: STPT ${p.n} at %.0f, %.0f".format(p.x, p.y)) }
        ok(pts.firstOrNull()?.let { hypot(it.x - 1_163_530.0, it.y - 1_540_725.0) < 1.0 * 6076.12 } == true,
            "STPT 1 is at Osan AB (the Taxi page's home field): ${pts.firstOrNull()?.let { "%.0f ft from the airport's point".format(hypot(it.x - 1_163_530.0, it.y - 1_540_725.0)) }}")

        // 3. the header bullseye, both orders
        line("")
        line("3. The bullseye in the save's header (X east, Y north, the cell's middle: BMS's ACMI puts Korea's 310/650 at 310.5/650.5 cells, D62)")
        val h = auto.header
        if (h != null) {
            val cell = CampaignArchive.FEET_PER_CELL
            line("   header BullseyeX ${h.bullseyeX}, BullseyeY ${h.bullseyeY} (cells; name ${h.bullseyeName})")
            line("   as BMS places it (X east, Y north, the cell's middle): north %.0f ft, east %.0f ft -> MissionRoute.bullseyeX/Y".format((h.bullseyeY + 0.5) * cell, (h.bullseyeX + 0.5) * cell))
            line("   as WDP reads it (the cell's corner): north %.0f ft, east %.0f ft".format(h.bullseyeY * cell, h.bullseyeX * cell))
            line("   the other order (X north, Y east):  north %.0f ft, east %.0f ft".format(h.bullseyeX * cell, h.bullseyeY * cell))
            ok(r != null && r.bullseyeX == (h.bullseyeY + 0.5) * cell && r.bullseyeY == (h.bullseyeX + 0.5) * cell,
                "MissionRoute carries X east, Y north, the cell's middle: bullseyeX (north) ${r?.bullseyeX}, bullseyeY (east) ${r?.bullseyeY}")
        } else ok(false, "Auto Save.cam's header reads")

        // 4. files and briefings that must not be believed
        line("")
        line("4. What is not believed")
        val sdFile = File(campaign, "Save-Day  1 04 11 35.cam")
        if (sdFile.isFile && briefing != null) {
            val sd = CampaignArchive.read(sdFile, names)
            val sdIni = MissionDtcFile.iniFor(sd)
            val sdPts = sdIni?.let { DtcRoute.points(DtcParser.parse(it)) }.orEmpty()
            line("   ${sdIni?.name}: ${sdPts.size} route points, actions ${sdPts.map { it.action }}; SaveFile '${sd.header?.saveFile}'; " +
                "file ${sdIni?.let { java.util.Date(it.lastModified()) }}, save ${java.util.Date(sd.modified)}")
            line("   whose route it is: in its own save ${MissionDtcFile.holderOf(sd, sdPts) ?: "no flight"}; in Auto Save.cam ${MissionDtcFile.holderOf(auto, sdPts) ?: "no flight"}")
            val v1 = MissionDtcFile.check(sd, sdIni, briefing, strings, korea.name)
            ok(!v1.believed, "${sdIni?.name} beside its own save is rejected: ${v1.reason}")
            val v2 = MissionDtcFile.check(auto, sdIni, briefing, strings, korea.name)
            ok(!v2.believed, "and put beside Auto Save.cam it is rejected too: ${v2.reason}")
        } else ok(false, "Save-Day  1 04 11 35.cam is in the campaign folder")

        val work = runCatching { Files.createTempDirectory("plantest-route").toFile() }.getOrNull()
        if (work == null || autoIni == null || briefing == null) ok(false, "a temporary folder for the altered copies of Auto Save.ini")
        else try {
            val text = autoIni.readText(Charsets.ISO_8859_1)
            fun altered(name: String, change: (Int, List<String>) -> String?): File {
                val f = File(work, name).also { it.mkdirs() }.let { File(it, "Auto Save.ini") }
                f.writeText(text.lines().joinToString("\r\n") { l ->
                    val m = Regex("^target_(\\d+)=(.*)$").find(l.trim())
                    val i = m?.groupValues?.get(1)?.toIntOrNull()
                    if (m == null || i == null) l else change(i, m.groupValues[2].split(',').map { it.trim() })?.let { "target_$i=$it" } ?: l
                }, Charsets.ISO_8859_1)
                return f
            }
            // what WDP's campaign save does: the cartridge's empty steerpoints copied over BMS's route
            val zero = altered("zeroed") { i, _ -> if (i in 0..7) "0.000000, 0.000000, 0.000000, -1, Not set" else null }
            val v3 = MissionDtcFile.check(auto, zero, briefing, strings, korea.name)
            ok(!v3.believed && v3.route != null, "a route WDP zeroed (target_0-7 = 0,0,0,-1) is rejected, while the file is still read for Send to Mission: ${v3.reason}")
            val sr = v3.saveRoute
            ok(sr != null && sr.fromSave && v3.shownRoute === sr && flight != null && sr.steerpoints.size == flight.waypoints.size &&
                sr.steerpoints.all { p -> flight.waypoints.getOrNull(p.n - 1)?.let { p.x == it.north && p.y == it.east && p.action == it.action } == true },
                "and the save's own flight plan of the printed flight takes its place (MissionDtcFile.fromSave): ${sr?.steerpoints?.size} waypoints — ${v3.saveReason}")
            val zeroKeep = altered("zeroed-actions") { i, f -> if (i in 0..7) "0.000000, 0.000000, 0.000000, ${f.getOrNull(3)}, Not set" else null }
            val v4 = MissionDtcFile.check(auto, zeroKeep, briefing, strings, korea.name)
            ok(!v4.believed, "zeroed positions with BMS's actions kept are rejected: ${v4.reason}")
            fun moved(ft: Double) = altered("moved-${ft.toInt()}") { i, f ->
                if (i != 2) null else "%.6f, %s, %s".format(java.util.Locale.ROOT, (f[0].toDoubleOrNull() ?: 0.0) + ft, f[1], f.drop(2).joinToString(", "))
            }
            val v5 = MissionDtcFile.check(auto, moved(50.0), briefing, strings, korea.name)
            ok(!v5.believed && v5.reason.contains("STPT 3"), "STPT 3 moved 50 ft north (a pilot's edit, a recon position) is rejected: ${v5.reason}")
            val v6 = MissionDtcFile.check(auto, moved(1.0), briefing, strings, korea.name)
            ok(v6.believed, "moved 1 ft (float noise) it is still believed: ${v6.reason}")
        } catch (e: Throwable) {
            ok(false, "the altered copies: $e")
        } finally {
            runCatching { work?.deleteRecursively() }
        }
        if (briefing != null && autoIni != null) {
            val v7 = MissionDtcFile.check(auto, autoIni, null, strings, korea.name)
            ok(!v7.believed && v7.route != null, "no printed briefing: not believed (${v7.reason})")
            val other = briefing.copy(overview = briefing.overview.copy(flight = "Jaguar2", packageId = "1"), `package` = emptyList())
            val v8 = MissionDtcFile.check(auto, autoIni, other, strings, korea.name)
            ok(!v8.believed && v8.saveRoute == null, "a briefing for another flight (same rows, Jaguar2 of package 1): ${v8.reason}; nor the save's flight plan: ${v8.saveReason}")
            val shorter = briefing.copy(steerpoints = briefing.steerpoints.dropLast(1))
            val v9 = MissionDtcFile.check(auto, autoIni, shorter, strings, korea.name)
            ok(!v9.believed, "a briefing with a row fewer: ${v9.reason}")
            val renamed = briefing.copy(steerpoints = briefing.steerpoints.map { if (it.n == 3) it.copy(desc = "Strike") else it })
            val v10 = MissionDtcFile.check(auto, autoIni, renamed, strings, korea.name)
            ok(!v10.believed && v10.saveRoute == null, "a briefing whose STPT 3 says Strike: ${v10.reason}; nor the save's flight plan: ${v10.saveReason}")
            val noIni = MissionDtcFile.check(auto, null, briefing, strings, korea.name)
            ok(!noIni.believed && noIni.route == null, "no mission file: ${noIni.reason}")
        }

        // 5. what the bridge serves, in-process
        line("")
        line("5. What the bridge serves (in-process, against the copy)")
        routeApi(root, autoFile, korea, ::ok, ::line)
        return done()
    }

    /** Step 5 of [route]: [Bridge.startForCheck] against [root], which the settings must point at. */
    private fun routeApi(root: File, autoFile: File, korea: Theaters.Theater, ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        val why = DevGuard.why(root)
        if (why != null) { ok(false, "step 5 moves the briefing's file time in the copy, and ${root.path} is not a copy: $why"); return }
        Bridge.startForCheck()
        val lenient = Json { ignoreUnknownKeys = true }
        try {
            val base = Bridge.install.baseDir?.let(::File)
            if (base == null || Theaters.canonical(base) != Theaters.canonical(root)) {
                ok(false, "the settings must point at the copy (BmsDirOverride); the bridge reads ${base?.path ?: "no folder"}"); return
            }
            ok(Bridge.install.theater.equals("Korea KTO", ignoreCase = true), "the theater BMS is set to: ${Bridge.install.theater}")
            MissionDtcFile.forget()
            var t0 = System.nanoTime()
            val v = MissionDtcFile.verdict()
            val cold = (System.nanoTime() - t0) / 1e6
            t0 = System.nanoTime()
            repeat(100) { MissionDtcFile.current(); MissionDtcFile.modified }
            val warm = (System.nanoTime() - t0) / 1e6 / 200
            ok(v.believed && v.ini?.name == "Auto Save.ini" && v.save?.name == "Auto Save.cam",
                "the bridge's own look: ${v.theater} -> ${v.save?.name} -> ${v.ini?.name}: ${v.reason} (first look %.0f ms, then %.4f ms a call)".format(cold, warm))

            val res = Bridge.handle(ApiRequest("GET", "/api/mission"))
            val body = res.body.toString(Charsets.UTF_8)
            val md = runCatching { lenient.decodeFromString(MissionData.serializer(), body) }.getOrNull()
            val route = md?.route
            val rpts = route?.let { DtcRoute.points(Dtc(steerpoints = it.steerpoints)) }.orEmpty()
            ok(res.status == 200 && route != null && route.file == "Auto Save.ini" && rpts.size == 8,
                "/api/mission.route: ${route?.file} beside ${route?.save}, ${rpts.size} route points + ${route?.steerpoints?.count { it.isTarget }} targets, bullseye ${route?.bullseyeX?.toLong()}, ${route?.bullseyeY?.toLong()}")
            // what every device does with that answer (M1's PlanMerge, shared code): the route line and the home field
            val merged = com.bmscompanion.app.data.mission.PlanMerge.merge(md)
            val lineN = merged.routeLine.map { it.n }
            ok(lineN == (1..8).toList() && merged.routeLine.all { it.source == com.bmscompanion.app.data.mission.PlanItemSource.ROUTE },
                "the app's merge of that answer: the route line is STPT $lineN, from BMS's route (" + merged.routeLine.map { it.source }.distinct().joinToString() + "), " +
                    "precision targets off the line: " + merged.allSteerpoints.filter { it.hasPos && !it.onRoute }.map { it.n })
            val home = merged.home
            ok(home?.n == 1 && hypot(home.x - 1_163_530.0, home.y - 1_540_725.0) < 1.0 * 6076.12,
                "the home field the Taxi page opens on is STPT ${home?.n}, %.0f ft from Osan AB's point".format(home?.let { hypot(it.x - 1_163_530.0, it.y - 1_540_725.0) } ?: -1.0))
            val rm = MissionDtcFile.modified
            // routeModified is a part of the version (the threat picture's "-g…" may follow it)
            ok(rm != 0L && md?.version?.split('-')?.contains(rm.toString()) == true, "the mission's version carries routeModified: ${md?.version}")
            val info = runCatching { lenient.decodeFromString(BridgeInfo.serializer(), Bridge.handle(ApiRequest("GET", "/api/info")).body.toString(Charsets.UTF_8)) }.getOrNull()
            ok(info?.briefing?.routeModified == rm, "/api/info briefing.routeModified = $rm (${info?.briefing?.routeModified})")

            // the tanker and AWACS tracks before 3D
            val tracks = md?.tracks.orEmpty()
            ok(tracks.any { it.role == "Tanker" } && tracks.any { it.role == "AWACS" },
                "tracks before 3D (BMS not running, no shared memory): " + tracks.joinToString { t -> "${t.role} ${t.callsign} (${t.points.size} points, ${t.points.count { it.station }} on station)" })
            // what the cartridge alone gave: its non-zero steerpoints are the recon targets 15-22, not a route
            val cart = Bridge.currentDtc()
            val routes = runCatching { PlannedRoutes.read(PlannedRoutes.Source(autoFile, autoFile.parentFile.parentFile, korea), Bridge.install) }.getOrDefault(emptyList())
            fun flying(own: List<Pair<Double, Double>>) = routes.filter { r ->
                own.count { (x, y) -> r.points.any { hypot(it.x - x, it.y - y) < 1.5 * 6076.12 } } >= 3
            }
            val oldOwn = cart?.steerpoints.orEmpty().filter { it.x != 0.0 || it.y != 0.0 }.map { it.x to it.y }
            val newOwn = rpts.map { it.x to it.y }
            val before = flying(oldOwn)
            val after = flying(newOwn)
            line("   the cartridge's own points (${oldOwn.size}: STPT ${cart?.steerpoints?.filter { it.x != 0.0 || it.y != 0.0 }?.map { it.n }}) fly with ${before.size} flights of ${routes.size}: ${before.mapNotNull { it.callsign }.take(6)}")
            ok(after.any { it.callsign.equals("Cyborg6", ignoreCase = true) }, "BMS's route flies with ${after.size} flight(s): ${after.mapNotNull { it.callsign }} (Cyborg6 is what lets the save be believed)")
            ok(Bridge.supportTracks(cart).map { it.callsign } == tracks.map { it.callsign }, "supportTracks(the cartridge) takes the believed route first: the same ${tracks.size} tracks")

            // GET /api/attack: the applied plan's attack, else what an older Planner posted
            fun attack() = runCatching { lenient.decodeFromString(AttackOverlay.serializer(), Bridge.handle(ApiRequest("GET", "/api/attack")).body.toString(Charsets.UTF_8)) }.getOrNull()
            val plan = runCatching { PlanStore.current() }.getOrNull()
            if (plan == null || !plan.applied) {
                ok(attack()?.cues?.isEmpty() == true, "no plan applied and nothing posted: GET /api/attack has no cues")
                val posted = AttackOverlay(page = "TOSS", cues = listOf(AttackCue("TGT", 1_136_513.0, 1_162_753.0, AttackCue.Kind.TARGET)), theater = "korea-kto")
                val p = Bridge.handle(ApiRequest("POST", "/api/attack", body = Bridge.json.encodeToString(AttackOverlay.serializer(), posted).toByteArray()))
                // since 1.3.8 (Bridge.boardAttack) a posted attack is taken and no longer drawn in EZBoards mode
                ok(p.status == 200 && attack()?.cues?.isEmpty() == true, "an attack an older Planner posts is taken (200) and not drawn: GET /api/attack has no cues in EZBoards mode")
                line("   (no plan applied here: PlanStore.current() is ${if (plan == null) "null" else "parked"}; the plan's attack taking over is checked once PlanStore holds one)")
                Bridge.handle(ApiRequest("POST", "/api/attack", body = Bridge.json.encodeToString(AttackOverlay.serializer(), AttackOverlay()).toByteArray()))
            } else {
                ok(attack() == (plan.attack?.takeIf { it.cues.isNotEmpty() } ?: AttackOverlay()), "a plan is applied: GET /api/attack answers its attack (${plan.attack?.page})")
            }

            // a PRINT that another caller saw first still reaches the tick (EZBoards on PRINT)
            val brief = File(root, "User/Briefings/briefing.txt")
            val was = brief.lastModified()
            val pending = runCatching { Bridge::class.java.getDeclaredField("printedPending").apply { isAccessible = true } }.getOrNull()
            if (pending == null || was == 0L) ok(false, "the briefing's file time and the bridge's pending flag")
            else try {
                pending.setBoolean(null, false)
                brief.setLastModified(was + 60_000)
                Bridge.handle(ApiRequest("GET", "/api/mission"))
                ok(pending.getBoolean(null), "a new briefing seen first by /api/mission is still pending for the tick (it used to be swallowed)")
                val again = MissionDtcFile.check(CampaignArchive.read(autoFile, CampaignArchive.names(Theaters.at(root), korea)),
                    MissionDtcFile.iniFor(CampaignArchive.read(autoFile, CampaignArchive.names(Theaters.at(root), korea))), Bridge.currentBriefing(), Theaters.at(root).strings(korea))
                ok(again.believed, "the re-read briefing still believes the route: ${again.reason}")
            } finally {
                brief.setLastModified(was)
                runCatching { pending.setBoolean(null, false) }
            }
            ok(brief.lastModified() == was, "the briefing's file time is put back")
        } finally {
            Bridge.stop()
            MissionDtcFile.forget()
        }
    }
}
