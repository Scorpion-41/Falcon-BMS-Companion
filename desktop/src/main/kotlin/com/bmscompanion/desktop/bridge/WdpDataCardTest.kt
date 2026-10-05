package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.wdp.DataCardNet
import com.bmscompanion.app.data.wdp.DataCardPlan
import com.bmscompanion.app.ui.screens.wdp.CardWeather
import com.bmscompanion.app.ui.screens.wdp.DataCardWiring
import com.bmscompanion.app.ui.screens.wdp.WdpCartridge
import com.bmscompanion.app.ui.screens.wdp.WdpDialog
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpMission
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `--wdppagetest datacard <datacard.tsv> <out.txt>` — the ported DataCard page against the real one.
 *
 * The reference is written by `tools/wdpref page DataCard` (or the independent `page DataCardVerify`), which builds
 * WDP's own main form (unshown), puts a synthetic campaign into the fields the card reads, and runs the card the
 * way WDP does when a flight is picked — `ClearDatacard`, `CreateFlightplan`, the package and flight fills, the
 * airports, the flight plan, the tankers, then `OpenFile`'s tail (`FillDataCard`, `FillCommCardPackages`,
 * `FillCommCard`) — before pressing the page's own buttons and leaving its fields: Form/Swing, fuel typed into the
 * plan, the range-unit toggle, Mil/Civ, M/SM, the taxi time, the route box's spinners and Lat/Brg toggle, the tanker
 * lists, the weapon, laser, EWS, ALOW, MSL and bingo boxes, the TextChanged-only boxes and the TACAN Leave handlers,
 * and (`in_steps`) the attack-profile buttons, the pilot seats, the formation dialog's answer, the tanker lists'
 * own buttons, a route spinner moved without its Click, a box typed and never left. Every label and text box the
 * card fills is compared, character for character, plus the DTC page's weapon boxes the card writes back to, the
 * radio buttons' Enabled, the elevation labels' colour, the tanker lists' selection, and VB's own answers for odd
 * strings (`in_vbnum`), which [DataCardNet]'s conversions must give too.
 *
 * **Where WDP stopped.** A row on which WDP threw an unhandled exception is written as it stood when it stopped,
 * with the stage it stopped in (`in_threw`). The port must stop in the same stage — it throws where WDP throws —
 * and leave the card in the same state, which the next row then starts from: the harness uses one card for the
 * whole run, and so does this test.
 *
 * **Where WDP was wrong** (the plan's D19–D23 and its version branches), the port differs on purpose, and each
 * difference must be one `resources/wdp/expected-diffs/datacard.txt` names — by the D-number, the controls it
 * touches, and a fact of the cell that says the difference has that fix's shape ([cellFacts]: a time one second
 * later, a landing row, the tower's frequency, …). Anything else still fails. A row the port finished where WDP
 * could not (a D19 stop, or a D19 fix the plan reports in [DataCardPlan.fixesUsed]) is counted and not compared
 * cell by cell; and a cell whose two values are both the ones they had on the row before is a difference carried
 * over, counted once where it arose.
 *
 * `--wdppagetest datacard <folder> <out.txt>` — a folder instead of a `.tsv` — runs the 4.38.1 cases instead
 * ([datacardCases]): a mission's `briefing.txt` and cartridge (`dtc.ini`, a copy) through the whole card wiring.
 */
internal fun wdpDataCardTest(reference: File, out: File): String {
    if (!reference.name.endsWith(".tsv", ignoreCase = true)) return datacardCases(reference, out)
    val allow = AttackAllowList.load("datacard")
    val report = buildString {
        appendLine("Weapon Delivery Planner DataCard page — the port against the program")
        appendLine("reference: ${reference.path}")
        if (!reference.isFile) {
            appendLine()
            appendLine("FAIL — no reference. Run tools/wdpref 'page DataCard' from WDP's folder first.")
            return@buildString
        }
        val lines = reference.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }
        if (lines.size < 2) { appendLine("FAIL — empty reference"); return@buildString }
        val head = lines[0].split('\t')
        val index = head.withIndex().associate { it.value to it.index }
        val outCols = head.withIndex().filter { !it.value.startsWith("in_") }

        val plan = DataCardPlan()
        var rows = 0
        var bad = 0
        var cells = 0
        var stopped = 0
        var stopBad = 0
        val badRows = ArrayList<String>()
        var unjudged = 0
        var planRows = 0
        var planCells = 0
        var carried = 0
        var allowed = 0
        val fixRows = LinkedHashMap<String, Int>()
        // each column's values on the row before, WDP's and the port's (the carry rule)
        val prevWant = HashMap<String, String>()
        val prevGot = HashMap<String, String>()
        val mismatched = LinkedHashMap<String, Int>()
        val examples = StringBuilder()
        var cutChecks = 0
        var cutBad = 0
        var vbChecks = 0
        var vbBad = 0
        var geoChecks = 0
        var geoBad = 0
        val hasVb = head.contains("in_vbnum")
        for (line in lines.drop(1)) {
            val f = line.split('\t')
            if (f.size < head.size) continue
            fun opt(name: String): String? = index[name]?.let { f[it] }
            fun col(name: String): String = opt(name) ?: error("no column $name")
            rows++
            val rowNo = col("in_row")
            val wdpStop = opt("in_threw").orEmpty()
            if (wdpStop.isNotEmpty()) stopped++
            var rowBad = false
            val portStop = try {
                replay(plan, ::col, ::opt); ""
            } catch (e: Stop) {
                // what the harness does after WDP throws: the flight is no longer being selected, a campaign is open
                plan.blnSelectingFlight = false
                plan.blnLoaded = true
                if (wdpStop != e.stage && examples.length < 8000) examples.appendLine("     row $rowNo: the port stopped in ${e.stage} (${e.cause::class.simpleName}: ${e.cause.message})")
                e.stage
            } catch (e: Exception) {
                // the replay itself could not go on (a column the harness never wrote, because WDP had stopped)
                plan.blnSelectingFlight = false
                plan.blnLoaded = true
                "<replay: ${e::class.simpleName} ${e.message}>"
            }
            for (x in plan.fixesUsed) fixRows[x] = (fixRows[x] ?: 0) + 1
            val d19 = plan.fixesUsed.any { it.startsWith("D19") }
            // a row the port finishes where WDP stopped runs on into inputs the harness never wrote (WDP had stopped):
            // the replay itself then cannot go on, which is the same finish
            val portFinished = portStop.isEmpty() || (wdpStop.isNotEmpty() && portStop.startsWith("<replay:"))
            // WDP stopped and the port, by a fix, did not: nothing after WDP's stop can be compared, so the row is
            // counted and not compared
            val finishedByFix = wdpStop.isNotEmpty() && portFinished && (d19 || laserBoxTwo(::opt))
            // WDP did not stop but its CreateFlightplan gave up part way (its own catch) where the port goes on: every
            // cell the flight plan feeds differs, and every other cell is still compared
            val planByFix = !finishedByFix && d19
            if (portStop != wdpStop && !finishedByFix) {
                rowBad = true; stopBad++
                if (examples.length < 8000) examples.appendLine("     row $rowNo: WDP stopped in '${wdpStop}', the port in '${portStop}'")
            }
            if (finishedByFix) unjudged++
            if (planByFix) planRows++
            val facts = RowFacts(plan, ::opt)
            for ((i, name) in outCols) {
                val want = f[i]
                val got = valueOf(plan, name).replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
                cells++
                val was = prevWant[name]
                val gotWas = prevGot[name]
                prevWant[name] = want
                prevGot[name] = got
                if (want == got || finishedByFix) continue
                if (planByFix && FROM_THE_PLAN.matches(name)) { planCells++; continue }
                if (was == want && gotWas == got) { carried++; continue }
                if (allow.match(name, want, got, facts.of(name, want, got)) != null) { allowed++; continue }
                rowBad = true
                mismatched[name] = (mismatched[name] ?: 0) + 1
                if (examples.length < 8000) examples.appendLine("     row $rowNo $name: port '$got' vs WDP '$want'")
            }
            // CutSituation, called directly by the harness
            for (c in col("in_cut").split('|')) {
                val eq = c.lastIndexOf('=')
                if (eq < 0) continue
                cutChecks++
                val want = c.substring(eq + 1).toInt()
                val got = plan.cutSituation(c.substring(0, eq))
                if (want != got) {
                    cutBad++; rowBad = true
                    if (examples.length < 8000) examples.appendLine("     row $rowNo CutSituation('${c.substring(0, eq)}'): port $got vs WDP $want")
                }
            }
            // the card's Distance and Heading between two cells, called directly by the harness
            for (g in opt("in_geo").orEmpty().split('|')) {
                val v = g.split(',')
                if (v.size < 6) continue
                val (x1, y1, x2, y2) = v.take(4).map { it.toInt() }
                geoChecks++
                val d = plan.distance(x1, y1, x2, y2)
                val h = plan.heading(x1, y1, x2, y2).toFloat()
                if (d.toRawBits() != v[4].toFloat().toRawBits() || h.toRawBits() != v[5].toFloat().toRawBits()) {
                    geoBad++; rowBad = true
                    if (examples.length < 8000) examples.appendLine("     row $rowNo Distance/Heading($x1,$y1 → $x2,$y2): port $d / $h vs WDP ${v[4]} / ${v[5]}")
                }
            }
            // VB's IsNumeric / ToDouble / ToInteger / ToSingle / ToShort on odd strings, as the runtime answered
            if (hasVb) for (item in col("in_vbnum").split('|')) {
                val v = item.split('~')
                if (v.size < 6) continue
                val s = unescape(v[0])
                val fns = listOf<Pair<String, () -> Any>>(
                    "IsNumeric" to { DataCardNet.isNumeric(s) }, "ToDouble" to { DataCardNet.toDouble(s) }, "ToInteger" to { DataCardNet.toInteger(s) },
                    "ToSingle" to { DataCardNet.toSingle(s) }, "ToShort" to { DataCardNet.toShort(s) },
                )
                for ((i, fn) in fns.withIndex()) {
                    vbChecks++
                    if (!vbSame(v[i + 1], fn.second)) {
                        vbBad++; rowBad = true
                        val got = try { fn.second().toString() } catch (e: Exception) { "!" + e::class.simpleName + " " + e.message }
                        if (examples.length < 8000) examples.appendLine("     row $rowNo ${fn.first}('${v[0]}'): port $got vs VB ${v[i + 1]}")
                    }
                }
            }
            if (rowBad) { bad++; badRows += rowNo }
        }

        appendLine("rows compared: $rows (WDP stopped with an unhandled exception on $stopped of them), controls per row: ${outCols.size}, cells: $cells, CutSituation calls: $cutChecks, VB conversions: $vbChecks, Distance/Heading calls: $geoChecks")
        appendLine("expected differences: ${allow.describe()}")
        appendLine("     $allowed cells differ as a fix says, $carried carried over from the row before; $unjudged rows the port finished where WDP stopped (not compared cell by cell)")
        appendLine("     $planRows rows whose flight plan WDP gave up part way and the port built (D19): $planCells cells the plan feeds not compared, the rest compared")
        appendLine("     fixes the plan reports, by rows: " + fixRows.entries.joinToString(", ") { "${it.key}×${it.value}" }.ifEmpty { "none" })
        append(allow.report().lines().filter { it.isNotBlank() }.joinToString("") { "     $it\n" })
        if (bad == 0) {
            appendLine("ok   every control on every row is WDP's, or differs only as expected-diffs/datacard.txt says, and the port stops where WDP stops unless a fix lets it finish")
        } else {
            appendLine("FAIL $bad of $rows rows differ")
            if (stopBad > 0) appendLine("     where it stopped: $stopBad rows differ")
            if (cutBad > 0) appendLine("     CutSituation: $cutBad of $cutChecks differ")
            if (vbBad > 0) appendLine("     VB conversions: $vbBad of $vbChecks differ")
            if (geoBad > 0) appendLine("     Distance/Heading: $geoBad of $geoChecks differ")
            appendLine("     by control: " + mismatched.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}×${it.value}" })
            appendLine("     rows: " + badRows.joinToString(" "))
            append(examples)
        }
        // The wiring on the sample briefing: not a comparison (WDP never saw this mission), a check that the app's
        // own briefing and a cartridge fill the card without throwing, and a look at what they fill.
        appendLine()
        appendLine("the wiring on the sample briefing (resources/bridge/sample_briefing.txt):")
        val smoke = try {
            val text = DataCardPlan::class.java.getResourceAsStream("/bridge/sample_briefing.txt")?.readBytes()?.toString(Charsets.UTF_8)
            if (text == null) "     (no fixture on the classpath)" else {
                val b = BriefingParser.parse(text)
                // a cartridge along a line, 30 nm a leg, climbing — the fixture has no DTC of its own
                val pts = b.steerpoints.map { s -> com.bmscompanion.app.data.mission.DtcPoint(n = s.n, x = 1_500_000.0 + s.n * 182_000.0, y = 900_000.0 + s.n * 60_000.0, altFt = if (s.n == 1) 250.0 else 20_000.0) }
                val w = com.bmscompanion.app.ui.screens.wdp.DataCardWiring()
                w.onMission(com.bmscompanion.app.ui.screens.wdp.WdpMission(b, com.bmscompanion.app.data.mission.Dtc(steerpoints = pts)))
                w.onClick("lblFormation"); w.onClick("lblFormation")
                w.onClick("btnKmSm"); w.onClick("lblLat"); w.onClick("lblCommName3"); w.onValue("numRouteStpt3", "7")
                w.onValue("txtFuel5", "4000"); w.onValue("txtFuel5.leave", "")
                w.onClick("btnSetTanker1"); w.onClick("cboTanker1"); w.onClick("btnTanker1")
                w.onClick("txtWpn_AttackType"); w.onClick("btnTOSS")
                // another flight of the package and back (SelectCallsign), then the Lead seat
                w.onClick("rbnCallsign2"); w.onClick("rbnCallsign1"); w.onClick("rbnLead")
                val v = w.values(emptyList())
                buildString {
                    for (r in 1..11) appendLine("     " + listOf("lblAction", "lblTOS", "lblHdg", "lblDist", "txtKias", "lblAlt", "txtFuel", "txtFormation").joinToString(" | ") { v["$it$r"] ?: "" })
                    for (n in listOf("lblMission", "lblPackage1", "lblCallsign1", "rbnCallsign1.text", "rbnCallsign2.enabled", "rbnCallsign3.enabled", "lblAC1", "txtC1_UHF", "txtC1_VHF", "txtLead_IDM", "txtLead_TCN",
                        "rbnLead", "rbnWing.enabled", "rbnElmLead.enabled",
                        "lblDepName", "lblDEP_UHF", "lblDEP_VHF", "txtExtra2", "txtExtra3", "txtTanker1", "txtTanker1_UHF", "txtAWACS", "txtAWACS_UHF",
                        "lblCommCallsign1", "lblCommTO1", "lblCommTaxi1", "lblCommPushTime1", "lblCommTot1", "lblCommName3", "lblCommLat3", "txtTransitLvl", "txtStdQnh",
                        "txtWpn_AttackType", "lblVipVrp", "lblVIPbrg", "txtTGT_Pri", "txtBrfSituation1", "txtBrfIntel1")) appendLine("     $n = '${v[n] ?: ""}'")
                }
            }
        } catch (e: Exception) { "FAIL the wiring threw ${e::class.simpleName}: ${e.message}" }
        append(smoke)
        val smokeOk = !smoke.startsWith("FAIL")
        appendLine()
        appendLine(if (bad == 0 && smokeOk) "PASS — the DataCard page answers as Weapon Delivery Planner does, except where it fixes WDP (expected-diffs/datacard.txt)" else "FAIL — the DataCard port and Weapon Delivery Planner disagree")
    }
    out.writeText(report)
    return report
}

/**
 * The 4.38.1 cases: what the card prints for a real mission, checked against what the mission says, and the fixes
 * that no WDP reference can show. [folder] holds a `briefing.txt` and a **copy** of a cartridge (`dtc.ini`); nothing
 * is written anywhere but this test's own settings (a DataCard copy under a test name). The theater is
 * `BMSC_WDP_THEATER`, else Korea KTO. The Save DTC and Get DTC File hooks are stand-ins that record what the card
 * hands them, so no cartridge is ever saved.
 */
internal fun datacardCases(folder: File, out: File): String {
    val c = GeoChecks()
    val text = StringBuilder()
    fun head(s: String) { c.text.appendLine(); c.text.appendLine("---- $s") }
    try {
        // ---- D20: GetTime to the nearest second
        head("D20 GetTime")
        for ((ms, want) in listOf(
            14_293_000L to "03:58:13", 3_599_999L to "01:00:00", 3_599_499L to "00:59:59", 0L to "00:00:00",
            86_401_000L to "00:00:01", 15_915_000L to "04:25:15", -1L to "",
        )) c.check("GetTime($ms)", DataCardPlan.getTime(ms) == want, "'${DataCardPlan.getTime(ms)}', want '$want'")

        // ---- D25: visibility as WDP caps it
        head("D25 visibility")
        for ((m, metric, want) in listOf(Triple(115_000, false, "7SM "), Triple(115_000, true, "9999 "), Triple(4_300, true, "4000 "),
            Triple(1_300, false, "7/8SM "), Triple(50, true, "100 "))) c.check("visibility($m, metric=$metric)", CardWeather.visibility(m, metric) == want, "'${CardWeather.visibility(m, metric)}', want '$want'")

        val theaterName = System.getenv("BMSC_WDP_THEATER") ?: "korea-kto"
        val theater = runBlocking { Repo.index().theaters.let { t -> t.firstOrNull { it.id == theaterName } ?: com.bmscompanion.app.ui.screens.mission.resolveTheater(t, theaterName) } }
        c.check("theater", theater != null, "$theaterName → ${theater?.id}")

        // ---- D24: a field with no TACAN of its own takes the nearest within 6 nm, starred
        head("D24 TACAN within 6 nm")
        val theaters = runBlocking { Repo.index().theaters }
        val balkans = theaters.firstOrNull { it.id == "balkans" }
        val bset = balkans?.let { runBlocking { Repo.airportSet(it.airportSet) } }
        val bsrc = com.bmscompanion.app.ui.screens.wdp.DataCardSources(balkans, bset)
        val ghedi = bset?.airports?.firstOrNull { it.name.startsWith("Ghedi") }
        c.check("Ghedi (no TACAN of its own)", ghedi != null && bsrc.tacanOf(ghedi) == "46 X*", "'${bsrc.tacanOf(ghedi)}' (Brescia's 46X, 2.7 nm)")
        val kset = theater?.let { runBlocking { Repo.airportSet(it.airportSet) } }
        val ksrc = com.bmscompanion.app.ui.screens.wdp.DataCardSources(theater, kset)
        val osan = kset?.airports?.firstOrNull { it.name == "Osan AB" }
        c.check("Osan (its own)", ksrc.tacanOf(osan) == "116 X", "'${ksrc.tacanOf(osan)}'")

        // ---- the fields as every Mission page finds them: BMS names a carrier "USS"; a guess would be the wrong ship
        head("departure on a carrier (airbases, not the name-only match)")
        fun ladder(uhf: String?) = Briefing(comms = listOf(com.bmscompanion.app.data.mission.CommEntry(agency = "Dep Tower", callsign = "USS Tower", uhf = uhf)))
        val vinson = ksrc.bases(ladder("270.200"))[0]
        c.check("\"USS\" with the Vinson's tower frequency", vinson?.name == "USS Carl Vinson", "${vinson?.name}")
        val none = ksrc.bases(ladder(null))[0]
        c.check("\"USS\" with nothing else to go on", none == null, "${none?.name ?: "unknown, not guessed"}")

        // ---- D19: a training mission that starts minutes before take-off (TR_BMS_06, forum #2301/#2343)
        head("D19 take-off 3 minutes after the mission's midnight")
        val sample = DataCardPlan::class.java.getResourceAsStream("/bridge/sample_briefing.txt")?.readBytes()?.toString(Charsets.UTF_8)
        if (sample == null) c.check("sample briefing", false, "not on the classpath") else {
            // every clock time 3 h 40 min earlier: the take-off 03:43:00 becomes 00:03:00
            val shifted = Regex("(\\d{2}):(\\d{2}):(\\d{2})z").replace(sample) { m ->
                val s = (m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt() - 13_200).mod(86_400)
                "%02d:%02d:%02dz".format(s / 3600, s / 60 % 60, s % 60)
            }
            val b = BriefingParser.parse(shifted)
            val pts = b.steerpoints.map { s -> com.bmscompanion.app.data.mission.DtcPoint(n = s.n, x = 1_500_000.0 + s.n * 182_000.0, y = 900_000.0 + s.n * 60_000.0, altFt = if (s.n == 1) 250.0 else 20_000.0) }
            val w = DataCardWiring()
            w.onMission(WdpMission(b, com.bmscompanion.app.data.mission.Dtc(steerpoints = pts), theater = theater))
            val v = w.values(emptyList())
            c.check("take-off", v["lblCommTO1"] == "00:03:00", "lblCommTO1 '${v["lblCommTO1"]}'")
            c.check("taxi time blank, not a stop", v["lblCommTaxi1"] == "" && "D19-taxi" in w.plan.fixesUsed, "lblCommTaxi1 '${v["lblCommTaxi1"]}', fixes ${w.plan.fixesUsed}")
            val rows = b.steerpoints.size
            val filled = (1..rows).count { !v["lblAction$it"].isNullOrEmpty() }
            c.check("the whole flight plan", filled == rows, "$filled of $rows rows have an action")
            c.check("the card after the Coordination Card", v["lblCommTot1"] == "00:30:00" && v["txtTransitLvl"] == "FL140",
                "lblCommTot1 '${v["lblCommTot1"]}', txtTransitLvl '${v["txtTransitLvl"]}'")
            c.check("the hold window (D21: no 'm', mm:ss in the same hour)", v["lblTOS2"] == "00:10:49-16:49", "lblTOS2 '${v["lblTOS2"]}'")
        }

        // ---- the real mission
        val bf = File(folder, "briefing.txt")
        val cf = File(folder, "dtc.ini")
        head("the mission in ${folder.name}: ${bf.name} + ${cf.name}")
        if (!bf.isFile || !cf.isFile) { c.check("mission files", false, "need briefing.txt and dtc.ini (a copy) in $folder") } else {
            val b = BriefingParser.parse(bf.readText(Charsets.UTF_8))
            val cartText = cf.readText(Charsets.UTF_8)
            val dtc = DtcParser.parse(cf)
            val w = DataCardWiring()
            // the checks below read WDP's Military ATIS in inches; the card starts on the pilot's own choice (Civil and
            // metric on a first start, as WDP's Setup.ini default)
            w.plan.blnAtis = false; w.plan.blnKmSm = false
            val mission = WdpMission(b, dtc, theater = theater)
            runBlocking { w.prepare(mission) { cartText } }
            var v = w.values(emptyList())
            fun show(vararg n: String) = n.joinToString(", ") { "$it '${v[it]}'" }
            // departure / arrival / alternate as every Mission page finds them, tower frequencies in the columns (D23)
            c.check("departure", v["lblDepName"] == "Osan AB" && v["lblDepTCN"] == "116 X" && v["lblDEP_UHF"] == "308.800" && v["lblDEP_VHF"] == "122.100",
                show("lblDepName", "lblDepTCN", "lblDEP_UHF", "lblDEP_VHF"))
            c.check("alternate", v["lblAltnName"]?.startsWith("Pyeongtaek") == true && v["lblAltnTCN"] == "19 X" && v["lblALTN_UHF"] == "257.800",
                show("lblAltnName", "lblAltnTCN", "lblALTN_UHF"))
            c.check("ground and approach under the ROE box (D23)", v["txtExtra3"]?.startsWith("GND:") == true && v["txtExtra3"]!!.contains("253.700") &&
                v["txtExtra4"]?.startsWith("APP:") == true && v["txtExtra4"]!!.contains("306.300"), show("txtExtra2", "txtExtra3", "txtExtra4", "txtExtra5"))
            // the support block from the app's support data (A6, D22)
            c.check("tanker", v["txtTanker1"] == "Copper2 - KC-130" && v["txtTanker1_TCN"] == "059Y" && v["txtTanker1_UHF"] == "356.850",
                show("txtTanker1", "txtTanker1_TCN", "txtTanker1_UHF"))
            val live = com.bmscompanion.app.data.mission.MissionLink.live.value
            // LOC is bullseye-relative, as WDP's; with no bullseye it is blank and Notes carries the briefing's words,
            // as the Briefing page shows them
            c.check("tanker LOC and notes as the Briefing page says them",
                (if (live?.bullX != null) v["txtTanker1_Loc"]?.matches(Regex("\\d{3}/\\d+")) == true else v["txtTanker1_Loc"] == "") &&
                    v["txtTanker1_Notes"] == "21 nm SW of Yongin-si",
                show("txtTanker1_Loc", "txtTanker1_Notes") + (if (live?.bullX != null) " (bullseye from the sim)" else " (no bullseye)"))
            c.check("AWACS", v["txtAWACS"] == "Magic4 - E-3" && v["txtAWACS_UHF"] == "371.775" && v["txtAWACS_Notes"] == "17 nm NE of Sejong",
                show("txtAWACS", "txtAWACS_UHF", "txtAWACS_Loc", "txtAWACS_Notes"))
            // D25: the ATIS
            c.check("ATIS line 2 (D25: no dew point or QNH in a 4.38.1 briefing)", v["lblAtis2"] == "23/// A2992 STD NOSIG", show("lblAtis1", "lblAtis2"))
            w.onClick("btnATIS"); v = w.values(emptyList())
            c.check("civil ATIS visibility (D25)", v["lblAtis1"]?.contains(" 7SM ") == true && v["lblAtis1"]?.contains("71SM") == false, show("lblAtis1"))
            w.onClick("btnKmSm"); v = w.values(emptyList())
            c.check("M / SM", v["lblAtis1"]?.contains(" 9999 ") == true && v["lblAtis2"]?.contains("Q1013 STD") == true && v["txtStdQnh"] == "1013", show("lblAtis1", "lblAtis2", "txtStdQnh"))
            w.onClick("btnKmSm"); w.onClick("btnATIS")
            // E3: Link 16 STNs where WDP printed IDM addresses; no A/A TACAN in this plan (000X), so WDP's pairs stay
            v = w.values(emptyList())
            c.check("Link 16 STNs (E3)", v["txtLead_IDM"] == "74741" && v["txtWing1_IDM"] == "74742" && v["txtLead_TCN"] == "12Y", show("txtLead_IDM", "txtWing1_IDM", "txtLead_TCN"))
            // the coordination card: taxi time, the flight plan's alternate row (D21)
            c.check("taxi time", v["lblCommTO1"] == "04:21:00" && v["lblCommTaxi1"] == "04:15:00", show("lblCommTO1", "lblCommTaxi1"))
            c.check("times as the briefing prints them (D20)", v["lblTOS3"] == "  04:34:15" && v["lblCommTot1"] == "04:34:15", show("lblTOS3", "lblCommTot1"))
            c.check("the alternate's row (D21)", v["lblTOS8"] == "---" && v["lblAlt8"] == v["lblAltnElv"] && v["lblAlt6"] == v["lblArrElv"],
                show("lblTOS7", "lblAlt7", "lblTOS8", "lblAlt8", "lblAltnElv", "lblAlt6", "lblArrElv"))
            // WDP's buttons for what the app does not do are off the page
            val removed = DataCardWiring.REMOVED.filter { v[it] != "hidden" }
            c.check("removed controls hidden", removed.isEmpty(), if (removed.isEmpty()) DataCardWiring.REMOVED.joinToString() else "shown: $removed")
            c.check("Current Time", v["lblTime"] != "1: 00:00:00" && (live != null || v["lblTime"] == ""), show("lblTime") + if (live == null) " (BMS not running)" else "")

            // the lamp and Save DTC: the card's entries go to the DTC page's hook, and only the changed ones
            // the "Callsign.ini saved" lamp is WDP's again: green with nothing waiting, red with the card's entries (or the
            // DTC page's edits, WdpCartridge.pending, left out here) waiting — the toolbar's Save to DTC count
            val keepPending = WdpCartridge.pending
            // a DTC page with a cartridge loaded and no edits of its own (WDP's lamp is off until its cartridge is loaded)
            WdpCartridge.pending = { 0 }
            v = w.values(emptyList())
            c.check("nothing waiting with nothing typed: the lamp is green", w.unsavedEntries == 0 && v["pnlCallsignGreen"] == "shown" && v["pnlCallsignRed"] == "hidden",
                "unsavedEntries ${w.unsavedEntries}, " + show("pnlCallsignGreen", "pnlCallsignRed"))
            c.check("Get DTC File, Save DTC and the lamp's label are on the card", DataCardWiring.DTC_CONTROLS.take(3).none { v[it] == "hidden" },
                show("btnGetDTC", "btnSaveDTC", "lblCallsignIni"))
            val alowWas = v["txtALOW"]
            w.onValue("txtALOW", "700"); w.onValue("txtALOW.leave", "")
            v = w.values(emptyList())
            c.check("ALOW typed counts one on Save to DTC, and the lamp is red", w.unsavedEntries == 1 && v["pnlCallsignRed"] == "shown" && v["pnlCallsignGreen"] == "hidden",
                "unsavedEntries ${w.unsavedEntries}, " + show("txtALOW", "pnlCallsignGreen", "pnlCallsignRed"))
            var captured: WdpCartridge.CardEntries? = null
            var savedFlag = false
            val keepWrite = WdpCartridge.writeCardEntries
            val keepReload = WdpCartridge.reload
            val keepOpen = WdpCartridge.open
            try {
                WdpCartridge.writeCardEntries = { e, save -> captured = e; savedFlag = save; "Test.ini saved." }
                WdpDialogs.stack.clear()
                w.onClick("btnSaveDTC")
                val until = System.currentTimeMillis() + 5000
                while (captured == null && System.currentTimeMillis() < until) Thread.sleep(20)
                val e = captured
                c.check("Save DTC hands the card's entries on", e != null && savedFlag && e.alowAglFt == 700 && e.mslFloorFt == null && e.bingoLbs == null &&
                    e.ewsProgramNames == null && e.laserTgp == null && e.profile1 == null && e.attackProfile == null, "entries $e, save=$savedFlag")
                // Get DTC File: the DTC page's Open Callsign.ini File (a window in User\Config), and the card takes the
                // cartridge picked (ALOW back); a window closed with no file changes nothing
                var opened = 0
                WdpCartridge.open = { opened++; null }
                w.onClick("btnGetDTC")
                val until1 = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < until1 && opened == 0) Thread.sleep(20)
                Thread.sleep(100)
                c.check("Get DTC File with no file picked changes nothing", opened == 1 && w.values(emptyList())["txtALOW"] == "700", show("txtALOW"))
                WdpCartridge.open = { opened++; cartText }
                w.onClick("btnGetDTC")
                val until2 = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < until2 && (opened < 2 || w.values(emptyList())["txtALOW"] == "700")) Thread.sleep(20)
                v = w.values(emptyList())
                c.check("Get DTC File opens the cartridge and refreshes the card", opened == 2 && v["txtALOW"] == alowWas && w.unsavedEntries == 0 &&
                    v["pnlCallsignGreen"] == "shown", show("txtALOW", "pnlCallsignGreen") + ", unsavedEntries ${w.unsavedEntries}, ALOW was '$alowWas'")
                // the toolbar's Re-read DTC from BMS: the DTC page reads the cartridge again, and the card its boxes
                w.onValue("txtALOW", "700"); w.onValue("txtALOW.leave", "")
                var reloaded = false
                WdpCartridge.reload = { reloaded = true; "Test.ini loaded." }
                w.rereadDtc()
                val until3 = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < until3 && (!reloaded || w.values(emptyList())["txtALOW"] == "700")) Thread.sleep(20)
                v = w.values(emptyList())
                c.check("Re-read DTC from BMS refreshes the card", reloaded && v["txtALOW"] == alowWas && w.unsavedEntries == 0, show("txtALOW") + ", unsavedEntries ${w.unsavedEntries}, ALOW was '$alowWas'")
            } finally {
                WdpCartridge.writeCardEntries = keepWrite
                WdpCartridge.reload = keepReload
                WdpCartridge.open = keepOpen
                WdpCartridge.pending = keepPending
                WdpDialogs.stack.clear()
            }

            // Load DataCard leaves what the card deliberately blanks (guide §15-4) and loads the rest
            com.bmscompanion.app.ui.screens.wdp.CardCopies.save(
                com.bmscompanion.app.ui.screens.wdp.CardCopies.Kind.DATACARD, "b1c-test",
                mapOf("txtTanker1_Notes" to "00:00 - 17:02", "txtKias8" to "1.50 / 995", "txtFuel3" to "4000", "chbFuel" to "checked"),
            )
            WdpDialogs.stack.clear()
            // Load DataCard opens WDP's file window (none here: no file is picked), then offers the copies an earlier
            // version kept on the device; "Load a kept copy" opens the window of names
            w.onClick("btnLoadDataCard")
            val untilKept = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < untilKept && WdpDialogs.stack.none { it is com.bmscompanion.app.ui.screens.wdp.WdpMessage }) Thread.sleep(20)
            (WdpDialogs.stack.lastOrNull() as? com.bmscompanion.app.ui.screens.wdp.WdpMessage)?.let { m -> WdpDialogs.stack.remove(m); m.onAnswer?.invoke("Load a kept copy") }
            val win = WdpDialogs.stack.lastOrNull() as? WdpDialog
            win?.wiring?.onValue("txtfName", "b1c-test")
            win?.wiring?.onClick("btnOk")
            v = w.values(emptyList())
            c.check("Load DataCard (§15-4)", win != null && v["txtTanker1_Notes"] == "21 nm SW of Yongin-si" && v["txtKias8"] == "" && v["txtFuel3"] == "4000",
                show("txtTanker1_Notes", "txtKias8", "txtFuel3"))
            WdpDialogs.stack.clear()

            // the same briefing again with the cartridge saved (a new file time): what the pilot typed stays (guide §15-13)
            w.onValue("txtBrfNotes1", "keep me")
            runBlocking { w.prepare(mission.copy(dtc = dtc.copy(modified = dtc.modified + 1000))) { cartText } }
            v = w.values(emptyList())
            c.check("typed boxes survive a cartridge saved again", v["txtBrfNotes1"] == "keep me", show("txtBrfNotes1"))

            // ---- r3a-card: every field of the Briefing page and the card's Config/ROE boxes from the printed briefing
            head("the Briefing page and the Config rows from the printed briefing")
            val pw = DataCardWiring()
            runBlocking { pw.prepare(mission) { cartText } }
            v = pw.values(emptyList())
            val situation = (1..5).joinToString(" ") { v["txtBrfSituation$it"].orEmpty() }
            c.check("Situation keeps every sentence (a paragraph break cut none, the fifth line not cut short)", situation.contains("Hostile aircraft have been violating") && situation.contains("Be advised") && situation.trimEnd().endsWith("assigned station time."), situation)
            c.check("Your Task is the flight's role, TOT the time on station (a CAP)", v["txtBrfYourTask"] == "BARCAP" && v["lblBrfTot"] == "04:34:15",
                show("txtBrfYourTask", "lblBrfTot", "lblBrfTakeOff", "txtBrfYourDescription", "txtBrfDescription"))
            c.check("Intel: the air threats, the surface block's 'none known'", v["txtBrfIntel1"]?.startsWith("Ground threats: none known") == true &&
                v["txtBrfIntel2"]?.startsWith("Air threats: Hostile aircraft") == true, show("txtBrfIntel1", "txtBrfIntel2"))
            c.check("Mission Objective: the flight's task, station, time and the package", v["txtBrfObjective1"]?.startsWith("Cyborg6 BARCAP: Prevent") == true &&
                v["txtBrfObjective2"]?.startsWith("Station area: 23 nm northwest") == true && v["txtBrfObjective3"] == "Time on station: 04:34:15z - 05:06:13z" &&
                (1..10).any { v["txtBrfObjective$it"]?.contains("(you)") == true }, (1..6).joinToString(" | ") { v["txtBrfObjective$it"].orEmpty() })
            // each store by BMS's SMS name, as WDP's card prints it (D38)
            c.check("Config rows: the lead's stores by kind", v["lblAA"] == "4x 120C5 2x A-9X" && v["lblTanks"] == "2x TK370" && v["lblECM"] == "1x AL184" && v["lblAG"] == "",
                show("lblAA", "lblAG", "lblECM", "lblTanks"))
            c.check("ROE from the briefing", v["txtRoe1"]?.startsWith("Visually ID unknown") == true, (1..5).joinToString(" | ") { v["txtRoe$it"].orEmpty() })

            // the chart button: the airport diagram the Taxi page draws, the parking charts, then the instrument charts
            head("the chart window (rich integration: the app's airport diagram)")
            WdpDialogs.stack.clear()
            c.check("DEP chart button shown for Osan", v["btnDepChart"] == "shown", show("btnDepChart"))
            pw.onClick("btnDepChart")
            val chart = WdpDialogs.stack.lastOrNull() as? WdpDialog
            var cv = chart?.wiring?.values(emptyList())?.values.orEmpty()
            val until3 = System.currentTimeMillis() + 8000
            while (chart != null && cv["lblFile"].isNullOrEmpty() && System.currentTimeMillis() < until3) { Thread.sleep(50); cv = chart.wiring?.values(emptyList())?.values.orEmpty() }
            c.check("it opens on Osan's airport diagram", chart?.form == "fclsChart" && cv["lblFile"]?.contains("Airport diagram") == true && cv["btnAgc"] == "hidden" &&
                cv["btnApc1"]?.startsWith("APC ") == true, "form ${chart?.form}, lblFile '${cv["lblFile"]}', btnAgc '${cv["btnAgc"]}', btnApc1 '${cv["btnApc1"]}', btnApc2 '${cv["btnApc2"]}'")
            chart?.wiring?.onClick("btnApc1"); cv = chart?.wiring?.values(emptyList())?.values.orEmpty()
            c.check("APC 1 is the ramp for that runway end", cv["lblFile"]?.contains("Parking, RWY") == true && cv["btnAgc"] == "AGC" && cv["btnApc1"] == "hidden", "lblFile '${cv["lblFile"]}'")
            var steps = 0
            while (chart != null && cv["cntChart"].isNullOrEmpty() && steps < 12) { chart.wiring?.onClick("lblFile"); cv = chart.wiring?.values(emptyList())?.values.orEmpty(); steps++ }
            c.check("the foot line steps on to the instrument charts", !cv["cntChart"].isNullOrEmpty(), "after $steps taps: lblFile '${cv["lblFile"]}', cntChart '${cv["cntChart"]}'")
            WdpDialogs.stack.clear()

            // a flight plan row (WDP's ShowTarget on a double click): the steerpoint on the app's map, with its facts
            head("a flight plan row: the steerpoint on the app's map")
            WdpDialogs.stack.clear()
            pw.onClick("lblAction3")
            val sw0 = WdpDialogs.stack.lastOrNull() as? WdpDialog
            val sl = sw0?.wiring?.values(emptyList())?.values?.get("pnlSchedule.lines").orEmpty()
            // with a position (a cartridge BMS has saved) the steerpoint is on the map; without one the window says so
            val placed = mission.steerpoints.any { it.n == 3 && (it.x != 0.0 || it.y != 0.0) }
            val mapped = (sw0?.wiring as? com.bmscompanion.app.ui.screens.wdp.WdpControlContent)?.controlContent()?.containsKey("pnlSchedule") == true
            c.check("STPT 3 opens with its time, altitude" + (if (placed) ", position and map" else " and why it is not on the map"), sw0?.title == "STPT 3 · CAP" && sl.contains("Time      04:34:15") && sl.contains("Altitude  21000 ft") && sl.lines().all { it.length <= 56 } &&
                (if (placed) sl.contains("Position  ") && mapped else sl.contains("no position") && !mapped),
                "title '${sw0?.title}', lines '${sl.replace("\n", " / ")}'")
            WdpDialogs.stack.clear()
            pw.onClick("lblAction20")
            c.check("an empty row does nothing (as WDP)", WdpDialogs.stack.isEmpty(), "stack ${WdpDialogs.stack.size}")
            WdpDialogs.stack.clear()

            // the card's Loadout window (a callsign's radio button): the Arsenal's weights and the aircraft's own
            head("the card's Loadout window (rich integration: the Arsenal and the aircraft data)")
            pw.onClick("rbnCallsign1")
            val lw = WdpDialogs.stack.lastOrNull() as? WdpDialog
            var lv = lw?.wiring?.values(emptyList())?.values.orEmpty()
            val until4 = System.currentTimeMillis() + 8000
            while (lw != null && lv["lblEmptyW"].isNullOrEmpty() && System.currentTimeMillis() < until4) { Thread.sleep(50); lv = lw.wiring?.values(emptyList())?.values.orEmpty() }
            c.check("weights from the Arsenal and the F-16's data", lw?.form == "fclsLoadout" && lv["lblLoadW"]?.toIntOrNull()?.let { it > 2000 } == true &&
                lv["lblEmptyW"]?.toIntOrNull() != null && lv["lblGrossW"]?.toIntOrNull() != null && lv["lblFuelExtW"] == "4958",
                listOf("lblEmptyW", "lblLoadW", "lblFuelIntW", "lblFuelExtW", "lblFuelW", "lblGrossW", "lblMaxW", "lblDragVal").joinToString(", ") { "$it '${lv[it]}'" })
            val before = WdpDialogs.stack.size
            lw?.wiring?.onClick("dgvLoadOut:open:1")
            val facts = (WdpDialogs.stack.drop(before).lastOrNull() as? com.bmscompanion.app.ui.screens.wdp.WdpMessage)?.text
            c.check("a store's double tap says what the Arsenal knows", facts?.contains("Weight 338 lb each") == true, "'${facts?.replace("\n", " / ")}'")
            WdpDialogs.stack.clear()

            // ---- a save's flight (Open mission…): the briefing the PC makes of it has no situation, weather, comm
            // ladder, threats or ROE. When BMS's printed briefing is that flight's, the card takes them from it
            val saveBrief = b.copy(generated = null, situation = null, comms = emptyList(), weather = null, roe = emptyList(), emergency = emptyList(),
                sections = emptyList(), threats = emptyList(), roster = emptyList(), support = emptyList(), origin = "save")
            val row = com.bmscompanion.app.data.mission.CampFlightRow(id = "t", number = 1, callsign = "Cyborg6", mission = "BARCAP", squadron = "36th FS", briefed = true, f16 = true)
            @Suppress("UNCHECKED_CAST")
            val link = com.bmscompanion.app.data.mission.MissionLink::class.java.getDeclaredField("_mission").also { it.isAccessible = true }
                .get(com.bmscompanion.app.data.mission.MissionLink) as kotlinx.coroutines.flow.MutableStateFlow<com.bmscompanion.app.data.mission.MissionData?>
            val keepLink = link.value
            // the Planner reads BMS's own files (bmsFiles), never the Mission section's: the printed briefing goes there too
            @Suppress("UNCHECKED_CAST")
            val files = com.bmscompanion.app.data.mission.MissionLink::class.java.getDeclaredField("_bmsFiles").also { it.isAccessible = true }
                .get(com.bmscompanion.app.data.mission.MissionLink) as kotlinx.coroutines.flow.MutableStateFlow<com.bmscompanion.app.data.mission.MissionData?>
            val keepFiles = files.value
            try {
                link.value = com.bmscompanion.app.data.mission.MissionData(briefing = b)
                files.value = link.value
                head("a save's flight that is the printed briefing's (joined)")
                val sw = DataCardWiring()
                runBlocking { sw.prepare(mission.copy(briefing = saveBrief, flight = com.bmscompanion.app.data.mission.CampFlight(row = row, briefing = saveBrief))) { cartText } }
                v = sw.values(emptyList())
                val sit = (1..5).joinToString(" ") { v["txtBrfSituation$it"].orEmpty() }
                c.check("Situation, Intel and ROE from the printed briefing", sit.contains("Hostile aircraft have been violating") && v["txtBrfIntel2"]?.startsWith("Air threats") == true &&
                    v["txtRoe1"]?.startsWith("Visually") == true, "situation '$sit', " + show("txtBrfIntel1", "txtBrfIntel2", "txtRoe1"))
                c.check("ATIS, tanker and the save's squadron", v["lblAtis1"]?.contains("RKSO") == true && v["txtTanker1"] == "Copper2 - KC-130" && v["lblBrfSquad"] == "36th FS",
                    show("lblAtis1", "txtTanker1", "lblBrfSquad"))

                // …and when it is not (another flight printed, or none): each box says PRINT brings it (A9)
                head("a save's flight BMS has printed no briefing for")
                val other = saveBrief.copy(overview = saveBrief.overview.copy(flight = "Viper3", packageId = "9999"))
                val ow = DataCardWiring()
                runBlocking { ow.prepare(mission.copy(briefing = other, flight = com.bmscompanion.app.data.mission.CampFlight(row = row.copy(callsign = "Viper3", briefed = false), briefing = other))) { cartText } }
                v = ow.values(emptyList())
                val osit = (1..5).joinToString(" ") { v["txtBrfSituation$it"].orEmpty() }
                c.check("Situation says the printed briefing has it", osit.startsWith("This flight comes from a save") && osit.contains("press PRINT"), osit)
                c.check("Intel, ROE, ATIS and the weather panel say so too", v["txtBrfIntel1"]?.startsWith("Threats: in the briefing BMS prints") == true &&
                    v["txtRoe1"]?.startsWith("The ROE are in the briefing") == true && v["lblAtis1"]?.startsWith("No weather file for this flight") == true &&
                    v["pnlWx.lines"]?.contains("press PRINT") == true, show("txtBrfIntel1", "txtRoe1", "lblAtis1"))
                c.check("…and the rest from the save", v["txtBrfYourTask"] == "BARCAP" && v["lblBrfTakeOff"] == "04:21:00" && v["lblBrfSquad"] == "36th FS" &&
                    v["txtBrfObjective1"]?.startsWith("Viper3 BARCAP") == true, show("txtBrfYourTask", "lblBrfTakeOff", "lblBrfSquad", "lblBrfTot", "txtBrfObjective1"))
            } finally {
                link.value = keepLink
                files.value = keepFiles
                WdpDialogs.stack.clear()
            }
        }
    } catch (e: Throwable) {
        c.check("the cases ran", false, "${e::class.simpleName}: ${e.message}\n" + e.stackTrace.take(8).joinToString("\n") { "       at $it" })
    }
    text.appendLine("Weapon Delivery Planner DataCard — the Falcon BMS 4.38.1 cases (folder ${folder.path})")
    text.append(c.text)
    text.appendLine()
    text.appendLine(if (c.bad == 0) "PASS — every 4.38.1 case holds" else "FAIL — ${c.bad} case(s) failed")
    val s = text.toString()
    out.writeText(s)
    return s
}

/** The port stopped (threw) in the named stage of the row, as WDP's handler would with an unhandled exception. */
private class Stop(val stage: String, override val cause: Throwable) : Exception(stage, cause)

/**
 * The cells a flight plan feeds: its 24 rows, the Coordination Card's route box, the package's push, target and hold
 * (from the flights' waypoints), the tanker lists (on station during the flight), and the offsets a NaN bearing blanks.
 */
private val FROM_THE_PLAN = Regex(
    "^(lblAction|lblTOS|lblHdg|lblDist|txtKias|lblAlt|txtFuel|txtFormation|lblCommFunc|lblCommName|lblCommLat|lblCommLon|" +
        "lblCommPushTime|lblCommPushAlt|lblCommTot|txtCommHoldPt|txtCommHoldAlt|txtCommTransAlt|numRouteStpt|cboTanker|txtTanker|" +
        "lblVIP|lblPUP|lblOA1|lblOA2)\\w*(\\.\\w+)?$",
)

/** The row typed into or left the TGP laser code's second box, whose check WDP took from the first (D21). */
private fun laserBoxTwo(opt: (String) -> String?): Boolean =
    listOf("in_fieldEdits", "in_steps").any { opt(it).orEmpty().contains("txtLaserCode2") }

/**
 * The facts `expected-diffs/datacard.txt` asks about a differing cell: the row's inputs (`in_*`), the version gates
 * WDP branched on, and whether the two values have the shape of one fix —
 * - `times`: every clock time in the port's text is WDP's or one second (a minute, in an `hh:mm`) later, the rest
 *   of the text the same (D20); a window whose hour changed prints the whole departure (D21); `mtimes` the same
 *   after WDP's leading "m" (D21);
 * - `landing`: the cell's flight-plan row is a landing, the row after one, row 24 or an untimed row (D21);
 * - `tower`: the port prints the field's tower frequency, or nothing where it has none (D23);
 * - `extra`: the frequency line under the ROE box is the port's ATIS/GND/APP/OPS line (D23);
 * - `slot`: the package slot on show (D21: the fourth flight's TACAN pairs);
 * - `wp25`: the flight has 25 waypoints or more (D21: WDP's fuel ladder read a 25th entry it never cleared).
 */
private class RowFacts(private val p: DataCardPlan, private val opt: (String) -> String?) {
    private val build = opt("in_build")?.toIntOrNull() ?: 0
    private val minor = opt("in_minor")?.toIntOrNull() ?: 0
    private val base = mapOf(
        "in_profile" to opt("in_profile").orEmpty(),
        "laserGate" to if (minor >= 34 && build >= 17837) "1" else "0",
        "awacsGate" to if (minor >= 36 && build >= 12345) "1" else "0",
        "formationGate" to if (build >= 13261) "1" else "0",
        "slot" to p.selFltInPack.toString(),
        "wp25" to if ((p.flightTable?.getOrNull(p.selFlightNr)?.numWaypoints ?: 0) >= 25) "1" else "0",
        "laser2" to if (laserBoxTwo(opt)) "1" else "0",
    )

    fun of(name: String, want: String, got: String): Map<String, String> {
        val m = HashMap(base)
        m["times"] = if (timesClose(want, got)) "1" else "0"
        m["mtimes"] = if (want.startsWith("m") && timesClose(want.substring(1), got)) "1" else "0"
        m["landing"] = if (landingRow(name)) "1" else "0"
        m["tower"] = if (tower(name, got)) "1" else "0"
        m["extra"] = if (extra(name, got)) "1" else "0"
        return m
    }

    private fun landingRow(name: String): Boolean {
        val r = Regex("^(lblTOS|txtKias|lblAlt|txtFormation)(\\d+)$").find(name)?.groupValues?.get(2)?.toIntOrNull() ?: return false
        if (r < 2 || r > 24) return false
        val fp = p.flightPlan
        return r == 24 || fp[r - 1].action == 7 || fp[r - 2].action == 7 || (fp[r - 1].arrive == 0L && fp[r - 1].depart == 0L)
    }

    private fun tower(name: String, got: String): Boolean {
        val m = Regex("^lbl(DEP|ARR|ALTN)_(UHF|VHF)$").find(name) ?: return false
        val a = p.tblApt[listOf("DEP", "ARR", "ALTN").indexOf(m.groupValues[1])]
        val v = if (m.groupValues[2] == "UHF") a.uhf else a.vhf
        return got == (if (v == 0f) "" else DataCardNet.fmt(v, "#0.000"))
    }

    private fun extra(name: String, got: String): Boolean {
        val k = Regex("^txtExtra([1-5])$").find(name)?.groupValues?.get(1)?.toInt() ?: return false
        val prefix = listOf("Departure, Destination, Alternate", "ATIS:", "GND:", "APP:", "OPS:")[k - 1]
        return got.isEmpty() || got.startsWith(prefix)
    }

    companion object {
        private val TIME = Regex("\\d{2}:\\d{2}(?::\\d{2})?")

        /** D20/D21: the same text, each time the same or one unit later (a window's whole departure against its mm:ss). */
        fun timesClose(want: String, got: String): Boolean {
            if (TIME.replace(want, "#") != TIME.replace(got, "#")) return false
            val a = TIME.findAll(want).map { it.value }.toList()
            val b = TIME.findAll(got).map { it.value }.toList()
            if (a.isEmpty() || a.size != b.size) return false
            fun v(t: String): Int = t.split(':').map { it.toInt() }.let { if (it.size == 3) it[0] * 3600 + it[1] * 60 + it[2] else it[0] * 60 + it[1] }
            fun close(d: Int, mod: Int) = d.mod(mod) <= 1
            for (i in a.indices) {
                val x = a[i]
                var y = b[i]
                if (x.length == 5 && y.length == 8) y = y.substring(3)
                else if (x.length != y.length) return false
                val d = v(y) - v(x)
                val ok = if (x.length == 8) close(d, 86_400) else close(d, 3600) || close(d, 1440)
                if (!ok) return false
            }
            return true
        }
    }
}

/** The harness's \uXXXX escapes undone. */
private fun unescape(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        if (s[i] == '\\' && i + 6 <= s.length && s[i + 1] == 'u') { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
        else { sb.append(s[i]); i++ }
    }
    return sb.toString()
}

/**
 * One VB answer against the port's: a value is compared as the same double/float/int (the runtime wrote it "R", which
 * round-trips), an exception by its kind — the port throws ArithmeticException for VB's OverflowException,
 * IllegalArgumentException("Arg_…") for its ArgumentException and any other IllegalArgumentException for its
 * InvalidCastException (VB turns the parser's FormatException into that).
 */
private fun vbSame(want: String, f: () -> Any): Boolean {
    val got: Any = try { f() } catch (e: ArithmeticException) { "!OverflowException" } catch (e: IllegalArgumentException) {
        if (e.message?.startsWith("Arg_") == true) "!ArgumentException" else "!InvalidCastException"
    }
    if (want.startsWith("!") || got is String) return want == got
    return when (got) {
        is Boolean -> want == (if (got) "True" else "False")
        is Double -> { val w = want.toDoubleOrNull() ?: return false; if (w.isNaN()) got.isNaN() else w.toRawBits() == got.toRawBits() || (w == 0.0 && got == 0.0) }
        is Float -> { val w = want.toFloatOrNull() ?: return false; if (w.isNaN()) got.isNaN() else w.toRawBits() == got.toRawBits() || (w == 0f && got == 0f) }
        else -> want == got.toString()
    }
}

private val SEATS = listOf("rbnLead", "rbnWing", "rbnElmLead", "rbnElmWing")
private val MODE_LISTS = listOf("cboSubMode1", "cboFuze1", "cboSGL_PAIR1", "cboSubMode2", "cboFuze2", "cboSGL_PAIR2")
private val DTC_MODE_LISTS = listOf("cboP1_SubMode", "cboP1_Fuze", "cboP1_SGL_PAIR", "cboP2_SubMode", "cboP2_Fuze", "cboP2_SGL_PAIR")

/** What the port shows under a TSV column's name, in the harness's terms. */
private fun valueOf(p: DataCardPlan, name: String): String {
    if (name.endsWith(".enabled")) {
        val c = name.removeSuffix(".enabled")
        if (c.startsWith("rbnCallsign")) return if (p.rbnEnabled[c.removePrefix("rbnCallsign").toInt()]) "True" else "False"
        val s = SEATS.indexOf(c)
        if (s >= 0) return if (p.seatEnabled[s]) "True" else "False"
    }
    if (name.startsWith("rbnCallsign") && !name.contains('.')) return if (p.rbnCallsign[name.removePrefix("rbnCallsign").toInt()]) "checked" else "unchecked"
    SEATS.indexOf(name).let { if (it >= 0) return if (p.seatChecked[it]) "checked" else "unchecked" }
    if (name.startsWith("numRouteStpt")) return p.numRouteStpt[name.removePrefix("numRouteStpt").toInt()].toString()
    if (name == "numTaxi1") return p.numTaxi1.toString()
    if (name == "numTaxi2") return p.numTaxi2.toString()
    MODE_LISTS.indexOf(name.removeSuffix(".sel")).let { if (it >= 0 && name.endsWith(".sel")) return p.modeSel[it].toString() }
    DTC_MODE_LISTS.indexOf(name.removePrefix("dtc.").removeSuffix(".sel")).let { if (it >= 0 && name.startsWith("dtc.")) return p.dtcModeSel[it].toString() }
    if (name.startsWith("dtc.")) return p.dtcBoxes[name.removePrefix("dtc.")] ?: ""
    if (name == "cboTanker1.items") return p.cboTanker1.joinToString("~")
    if (name == "cboTanker2.items") return p.cboTanker2.joinToString("~")
    if (name == "cboTanker1.sel") return p.cboTankerSel[1].toString()
    if (name == "cboTanker2.sel") return p.cboTankerSel[2].toString()
    return p.labels[name] ?: ""
}

/** Runs one stage of the row; an exception in it is where the row stops. */
private inline fun stage(name: String, f: () -> Unit) {
    try { f() } catch (e: Stop) { throw e } catch (e: Exception) { throw Stop(name, e) }
}

/** The harness's row, step for step, stage for stage. */
private fun replay(p: DataCardPlan, col: (String) -> String, opt: (String) -> String?) {
    // ---------------------------------------------------------------- the campaign
    p.fixesUsed.clear()
    val flights = col("in_flights").split('|').map { fl ->
        val parts = fl.split(';')
        val (ps, lc, wps) = parts
        val points = wps.split(':').map { w ->
            val v = w.split(',').map { it.toLong() }
            DataCardPlan.Waypoint(v[0].toInt(), v[1].toInt(), v[2].toInt(), v[3], v[4], v[5].toInt(), v[6].toInt())
        }
        DataCardPlan.Flight(
            points,
            ps.split(',').map { it.toInt() }.toIntArray(),
            lc.split(',').map { it.toInt() }.toIntArray(),
            // the flight's own count, where the harness set one that is not the array's length
            parts.getOrNull(3)?.toIntOrNull() ?: points.size,
        )
    }
    p.flightTable = flights
    p.selFlightNr = col("in_sel").toInt()
    p.blnLoaded = true
    p.blnMissionLoaded = true
    val radio = col("in_fltRadio")
    p.fltRadio = if (radio.isEmpty()) emptyList() else radio.split('|').map { val r = it.split('~'); DataCardPlan.FltRad(r[0], r[1], r[2]) }
    p.transitionLevel = col("in_tl").toInt()
    val (lst, lcode) = col("in_laser").split(',').map { it.toInt() }
    p.laserLST = lst
    p.laserCode = lcode
    p.attack.modesel = 0
    p.campNavOffsets.modesel = 0
    // the Pop-up, HADB and TOSS pages' offsets, which the profile buttons copy into the cartridge
    opt("in_nav")?.takeIf { it.isNotEmpty() }?.split('|')?.forEachIndexed { i, e ->
        val v = e.split(',')
        val n = listOf(p.popUpNavOffsets, p.hadbNavOffsets, p.tossNavOffsets)[i]
        n.modesel = v[0].toInt()
        for (j in 0..7) {
            val o = n.points[j]
            o.stpt = v[1 + j * 4].toInt(); o.bearing = v[2 + j * 4].toFloat(); o.range = v[3 + j * 4].toInt(); o.elv = v[4 + j * 4].toInt()
        }
    }
    p.dtcLoaded = opt("in_dtcLoaded") == "1"
    // the DTC page's weapon-mode lists, set before a campaign is open (their change handlers do nothing yet)
    opt("in_dtcCbo")?.takeIf { it.isNotEmpty() }?.split(',')?.forEachIndexed { i, v -> p.dtcModeSel[i] = v.toInt() }
    p.blnMissionDtcLoaded = false

    // ---------------------------------------------------------------- the card's own toggles
    p.blnAtis = col("in_atis") == "1"
    p.blnKmSm = col("in_kmsm") == "1"
    p.blnSwing = col("in_swing") == "1"
    p.blnSwingFlpn = col("in_swingFlpn") == "1"
    p.intSetRange = col("in_setRange").toInt()
    p.altnFuel = col("in_altnFuel").toInt()
    p.taxiTime = col("in_taxiTime").toInt()
    p.blnKeepNames = opt("in_keep") == "1"
    p.blnWth = false
    p.firstTanker = -1
    p.secondTanker = -1
    // the DTC page's weapon boxes as the row starts (older references do not carry them)
    opt("in_dtcInit")?.takeIf { it.isNotEmpty() }?.split('|')?.forEach { kv -> val (k, v) = kv.split('=', limit = 2); p.dtcBoxes[k] = v }
    p.numTaxi1 = 6
    p.numTaxi2 = 6
    p.labels["lblRwyTaxiTime1"] = "6"
    p.labels["lblRwyTaxiTime2"] = "6"
    p.labels["lblLat"] = if (p.blnSwing) "Brg" else "Latitude"
    p.labels["lblLon"] = if (p.blnSwing) "Dist" else "Longitude"
    p.labels["lblFormation"] = if (p.blnSwingFlpn) "Swing" else "Form"
    p.labels["lblAtisType"] = "Military"
    for (k in 1..5) p.rbnCallsign[k] = false
    p.rbnCallsign[1] = true
    // FillPackages enables the five radio buttons, SelectNewFlight puts the pilot in the lead's seat
    p.enableCallsigns()
    for (i in 0..3) p.seatChecked[i] = i == 0
    p.milP = col("in_milP")

    // ---------------------------------------------------------------- SelectNewFlight
    p.blnSelectingFlight = true
    stage("ClearDatacard") { p.clearDatacard() }
    p.latLon = { i ->
        val ll = col("in_latlon").split('|').map { val x = it.split('~'); x[0] to x.getOrElse(1) { "" } }
        ll.getOrElse(i) { "" to "" }
    }
    stage("CreateFlightplan") { p.createFlightplan(false) }
    p.selFltInPack = col("in_selFltInPack").toInt()
    col("in_packages").split('|').forEachIndexed { k, e ->
        val v = e.split('~')
        val pk = p.packages[k]
        pk.callsign = v[0]; pk.acNr = v[1].toInt(); pk.acType = v[2]; pk.task = v[3]; pk.fltNr = v[4].toInt()
        pk.takeOffTime = v[5].toLong(); pk.pushStpt = v[6].toInt(); pk.targetStpt = v[7].toInt(); pk.f16 = v.getOrNull(8) == "1"
    }
    stage("FillAutoPackages") { p.fillAutoPackages() }
    stage("FillAutoFlight") { p.fillAutoFlight() }
    stage("FillDataCard1") { p.fillDataCard() }
    stage("FillCommCardPackages1") { p.fillCommCardPackages() }
    stage("FillCommCard1") { p.fillCommCard() }
    col("in_apts").split('|').forEachIndexed { k, e ->
        val v = e.split('~')
        val a = p.tblApt[k]
        a.name = v[0]; a.tcn = v[1]; a.elv = v[2].toFloat(); a.elvByTerrain = v[3] == "1"
        a.uhf = v[4].toFloat(); a.vhf = v[5].toFloat(); a.atisVHF = v[6].toFloat(); a.gndUHF = v[7].toFloat(); a.appUHF = v[8].toFloat()
        a.opsUHF = v[9].toFloat(); a.lsoUHF = v[10].toFloat(); a.rwy = v[11]; a.ils = v[12].toFloat()
    }
    p.blnLoaded = false
    stage("FillAptLabels") { p.fillAptLabels() }
    p.blnLoaded = true
    stage("FillFlightplan") { p.fillFlightplan() }
    p.selFlightArrStpt = col("in_arrStpt").toInt()
    fun tracks(s: String): List<DataCardPlan.Track> = if (s.isEmpty()) emptyList() else s.split('|').map {
        val v = it.split('~')
        fun n(x: String) = if (x == "\u0001") null else x
        DataCardPlan.Track(n(v[0]), v[2], n(v[1]), v[3], v[4].toLong(), v[5].toLong())
    }
    val tank = tracks(col("in_tankers")); val aw = tracks(col("in_awacs")); val js = tracks(col("in_jstar"))
    p.tblTankerTrack = tank; p.tankerNR = tank.size
    p.tblAwacsTrack = aw; p.awacsNR = aw.size
    p.tblJSTARTrack = js; p.jstarNR = js.size
    if (tank.isNotEmpty()) stage("FillTanker") { p.fillTanker() }
    if (aw.isNotEmpty()) stage("FillAwacs") { p.fillAwacs() }
    if (js.isNotEmpty()) stage("FillJSTAR") { p.fillJSTAR() }
    // each pick is the list's item (SelectedItem) and its button; "k:item" names the list, an older reference
    // gives the lists in the harness's fixed order (1, 2 — or 1, 2, 1, 2, 2 for five picks)
    val picks = col("in_tankerPick").split('|')
    val order = if (picks.size == 5) listOf(1, 2, 1, 2, 2) else List(picks.size) { it + 1 }
    picks.forEachIndexed { i, pick ->
        var k = order[i]
        var item = pick
        if (pick.length >= 2 && pick[1] == ':' && (pick[0] == '1' || pick[0] == '2')) { k = pick[0] - '0'; item = pick.substring(2) }
        else if (pick.isEmpty()) return@forEachIndexed
        stage("Tanker$i") { p.pickTanker(k, item) }
    }
    p.blnSelectingFlight = false

    // ---------------------------------------------------------------- OpenFile's tail
    val names = col("in_names").split('~')
    for (i in 0..3) p.strNames[i + 1] = names[i]
    p.strMission = col("in_mission")
    p.selPackageName = col("in_pkgName")
    val ews = col("in_ews").split('~')
    for (i in 0..5) p.strEws[i + 1] = ews[i]
    val wpn = col("in_wpn").split('~')
    for (i in 0..15) p.wpn[i] = wpn[i]
    val a = p.attack
    a.strProfile = col("in_profile")
    a.modesel = col("in_modesel").toInt()
    p.campNavOffsets.modesel = a.modesel
    a.popRef = col("in_popRef") == "1"
    a.tossRef = col("in_tossRef") == "1"
    val nums = col("in_dtcNums").split('~')
    var n = 0
    for (mode in listOf("VIP", "VRP")) for (pt in listOf("", "PUP", "OA1", "OA2")) for (q in listOf("BRG", "RNG", "ELV")) a.nums["$mode${pt}_$q"] = nums[n++]
    for (kv in col("in_pages").split('|')) {
        val eq = kv.indexOf('=')
        val k = kv.substring(0, eq)
        val v = kv.substring(eq + 1)
        when (k) {
            "pop.dblPullHeading" -> a.popPullHeading = v.toDouble()
            "pop.intPullDownAlt" -> a.popPullDownAlt = v.toInt()
            "pop.intClimbAngleDeg" -> a.popClimbAngleDeg = v.toInt()
            "pop.lblTurnVal" -> a.popTurnVal = v
            "pop.lblTargetHUDval" -> a.popTargetHud = v
            "dtc.strIngrHgt" -> a.strIngrHgt = v
            "dtc.strIngrSpd" -> a.strIngrSpd = v
            "dtc.strRelHgt" -> a.strRelHgt = v
            "dtc.strRelSpd" -> a.strRelSpd = v
            "dtc.strAttHed" -> a.strAttHed = v
            "dtc.strDA" -> a.strDA = v
            "dtc.strPullingG" -> a.strPullingG = v
            "toss.intAttackHeadingDeg" -> a.tossAttackHeadingDeg = v.toInt()
            "toss.intIngressHeight" -> a.tossIngressHeight = v.toInt()
            "toss.intIngressCAS" -> a.tossIngressCAS = v.toInt()
            "toss.intReleaseHeight" -> a.tossReleaseHeight = v.toInt()
            "toss.intReleaseCAS" -> a.tossReleaseCAS = v.toInt()
            "toss.intReleaseAngleDeg" -> a.tossReleaseAngleDeg = v.toInt()
            "toss.intPullingGs" -> a.tossPullingGs = v.toInt()
            "toss.strTurn" -> a.tossTurn = v
            "toss.lblTargetHUDval" -> a.tossTargetHud = v
            "hadb.intIngressAlt" -> a.hadbIngressAlt = v.toInt()
            "hadb.intIngressCAS" -> a.hadbIngressCAS = v.toInt()
            "hadb.intReleaseHeight" -> a.hadbReleaseHeight = v.toInt()
            "hadb.intCAS" -> a.hadbCAS = v.toInt()
            "hadb.intAttackHeadingDeg" -> a.hadbAttackHeadingDeg = v.toInt()
            "hadb.intDiveAngleDeg" -> a.hadbDiveAngleDeg = v.toInt()
            "hadb.intPullingGs" -> a.hadbPullingGs = v.toInt()
            "hadb.strTurnDirection" -> a.hadbTurnDirection = v
            "hadb.lblTargetHUDval" -> a.hadbTargetHud = v
            else -> error("unknown page field $k")
        }
    }
    stage("FillDataCard2") { p.fillDataCard() }
    stage("FillCommCardPackages2") { p.fillCommCardPackages() }
    stage("FillCommCard2") { p.fillCommCard() }

    // ---------------------------------------------------------------- the pilot at the page
    repeat(col("in_swingClicks").toInt()) { i -> stage("Swing$i") { p.setSwingFltpln() } }
    val edits = col("in_fuelEdits")
    if (edits.isNotEmpty()) edits.split('|').forEachIndexed { i, e ->
        val (s, kind, v) = e.split('=', limit = 3)
        val k = s.toInt()
        stage("Fuel$i") {
            p.type("txtFuel$k", v)
            if (kind == "k") p.txtFuelKeyUp(k)
            p.leave("txtFuel$k")
        }
    }
    repeat(col("in_rangeClicks").toInt()) { i -> stage("Range$i") { p.setRangeType(); p.fillAttackType() } }
    repeat(col("in_atisClicks").toInt()) { i -> stage("Atis$i") { p.btnATIS() } }
    repeat(col("in_kmsmClicks").toInt()) { i -> stage("KmSm$i") { p.btnKmSm() } }
    val taxi = col("in_taxi").toInt()
    if (taxi != 0) stage("Taxi1") { p.numTaxi1 = taxi; p.fillTaxi() }
    val taxi2 = opt("in_taxi2")?.toIntOrNull() ?: 0
    if (taxi2 != 0) stage("Taxi2") { p.numTaxi2 = taxi2; p.fillTaxi() }
    repeat(col("in_latClicks").toInt()) { i -> stage("Lat$i") { p.setSwing() } }
    val route = col("in_routeEdits")
    if (route.isNotEmpty()) route.split('|').forEachIndexed { i, e -> val (k, v) = e.split('=').map { it.toInt() }; stage("Route$i") { p.routeStpt(k, v) } }
    // typed into a box, then left (its Leave handler); "!box" is typed and not left
    val fields = col("in_fieldEdits")
    if (fields.isNotEmpty()) fields.split('|').forEachIndexed { i, e ->
        val (ctl0, v, mxt) = e.split('=', limit = 3)
        val noLeave = ctl0.startsWith("!")
        val ctl = ctl0.removePrefix("!")
        stage("Field$i") {
            if (!noLeave) p.dtcBoxes[ctl] = mxt
            p.type(ctl, v)
            if (!noLeave) p.leave(ctl)
        }
    }
    stage("FillDataCard3") { p.fillDataCard() }

    // ---------------------------------------------------------------- more of the page's own controls
    val steps = opt("in_steps").orEmpty()
    if (steps.isNotEmpty()) steps.split('|').forEachIndexed { i, st ->
        val v = st.split(':').map(::unescape)
        stage("Step$i") {
            when (v[0]) {
                "prof" -> p.profileButton(v[1])
                "seat" -> p.selectPilotSeat(v[1].toInt())
                "form" -> p.formationChosen(v[1].toInt(), v[2], v[3] == "1")
                "rv" -> p.routeValue(v[1].toInt(), v[2].toInt())
                "rc" -> p.routeStpt(v[1].toInt(), v[2].toInt())
                "tset" -> p.btnSetTanker(v[1].toInt())
                "tidx" -> p.cboTankerSelectIndex(v[1].toInt(), v[2].toInt())
                "titem" -> p.cboTankerSelectItem(v[1].toInt(), if (v[2] == "\u0001") null else v[2])
                "tbtn" -> p.btnTanker(v[1].toInt())
                "wmo" -> p.weaponModeOpen(v[1].toInt())
                "wms" -> p.weaponModeSelect(v[1].toInt(), v[2].toInt())
                "wmt" -> p.weaponModeTake(v[1].toInt())
                "type" -> p.type(v[1], v[2])
                "leave" -> p.leave(v[1])
                "key" -> p.txtFuelKeyUp(v[1].toInt())
                "fdc" -> p.fillDataCard()
                "fcc" -> p.fillCommCard()
                "fcp" -> p.fillCommCardPackages()
                "atis" -> p.btnATIS()
                "kmsm" -> p.btnKmSm()
                "range" -> { p.setRangeType(); p.fillAttackType() }
                "swing" -> p.setSwingFltpln()
                "lat" -> p.setSwing()
                "taxi" -> { if (v[1] == "1") p.numTaxi1 = v[2].toInt() else p.numTaxi2 = v[2].toInt(); p.fillTaxi() }
                else -> error("unknown step ${v[0]}")
            }
        }
    }
}
