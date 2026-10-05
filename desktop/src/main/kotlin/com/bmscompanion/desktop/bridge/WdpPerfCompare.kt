package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.wdp.Atmosphere
import com.bmscompanion.app.data.wdp.DataCardNet
import com.bmscompanion.app.data.wdp.Engines
import com.bmscompanion.app.data.wdp.PerformanceLoadout
import com.bmscompanion.app.data.wdp.PerformancePlan
import com.bmscompanion.app.ui.screens.wdp.DataCardWiring
import com.bmscompanion.app.ui.screens.wdp.PerfLoadoutWindow
import com.bmscompanion.app.ui.screens.wdp.PerformanceWiring
import com.bmscompanion.app.ui.screens.wdp.WdpDialog
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpHandOff
import com.bmscompanion.app.ui.screens.wdp.WdpMessage
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `--wdppagetest performance <x>.cases.tsv <out.txt>` — the Performance page and WDP's, end to end, on identical inputs.
 *
 * The grid comparison (`--wdppagetest performance perf.tsv`) replays 700 random rows through the page's arithmetic.
 * This one answers what a pilot sees when both programs are given the same jet, stores, fuel, field and weather: the
 * named scenarios below, each run
 * - in **WDP** (`wdpref page PerfCase`, reading `<x>.cases.tsv.in`, which this check writes when the reference is
 *   missing or no longer matches the scenarios): the real `cntPerformance`, the databases' figures in its fields, the
 *   pilot's inputs through each control's event; its labels, the engine's labels and the take-off block it writes
 *   into the DataCard;
 * - in **the Planner as the pilot drives it**: a fresh Performance page (no mission), the jet picked in Type, the
 *   field in Select APT, the stores hung station by station in the Loadout window and OK pressed, every box typed and
 *   left, every list picked, the radio clicked, and the take-off block handed to a DataCard ([WdpHandOff]). The
 *   figures WDP's databases would put in its fields (weights, loadout, drag, fuel, elevation, runway) are read off this
 *   page and written into WDP's case, so both programs plan on exactly the same numbers;
 * - in **the page's arithmetic**, both ways: as WDP (`wdpSlips`, every label must be WDP's) and as the Planner.
 *
 * Every label is listed per scenario. A difference from WDP must be one of the page's documented fixes that applies
 * to the scenario (D28 pressure altitude, D29 the turn calculator's Actual Temp, D30 the cruise compared as a number,
 * D31 the ISA deviation to the climb charts, the GE-100's AB schedule, the refusal cell); anything else fails, as
 * does any difference between the page as driven and its arithmetic.
 *
 * Then **the inputs**, checked against Falcon BMS itself: the loadout weight and drag the page gives the flights of
 * BMS's own training missions (`Data/Campaign/TR_BMS_*.trn` of the BMS folder in the settings) against the gross
 * weights and drag factor BMS's Training Manual 4.38.1 prints for them — with BMS's pylons and racks, and without
 * them, as WDP 3.7.24 totals a 4.38.1 loadout.
 */
internal object WdpPerfCompare {

    /** One scenario: the jet, the field, the stores and the pilot's inputs. */
    data class Scenario(
        val name: String,
        val what: String,
        val jet: String = "F-16CM-40",
        val field: String = "Osan AB",
        val rwy: String = "09L",
        /** hardpoint → store key, hung through the Loadout window; null for a case of given figures only (WDP's own inputs) */
        val stores: List<Pair<Int, String>>? = emptyList(),
        /** the database figures of a case the page cannot produce itself (WDP's loadout without pylons, its Afyon) */
        val given: Map<String, String> = emptyMap(),
        val temp: String = "25",
        val windDir: String = "0",
        val windSpd: String = "0",
        val qnh: String = "29.92",
        val qnhKind: String = "in",
        val pitch: String = "13",
        val power: String = "Full AB",
        val radio: String = "radCruiseAlt",
        val cruiseAlt: String = "21000",
        val taxiFuel: String = "200",
        val cas: String = "",
        val alt: String = "",
        val g: String = "",
        val actual: String = "",
    )

    /** A Korea campaign save's F-16CM-40 two-ship out of Osan (the case the comparison was asked for): what its lead carries, hardpoint by hardpoint. */
    private val SAVE = listOf(
        1 to "aim-120b-amraam", 2 to "aim-120c-amraam", 3 to "aim-120c-amraam", 4 to "tank-370gal", 5 to "an-alq-184",
        6 to "tank-370gal", 7 to "aim-120c-amraam", 8 to "aim-120c-amraam", 9 to "aim-120b-amraam",
    )

    /** WDP 3.7.24's totals of that loadout on 4.38.1: the stores alone (its rack file is not where 4.38 keeps it). */
    private val WDP_STORES_ONLY = mapOf(
        "engine" to "100", "empty" to "19800", "intFuel" to "7162", "max" to "48000", "loadout" to "4141", "drag" to "102", "extFuel" to "5032",
        "elv" to "42", "toda" to "9010", "rwyHed" to "90",
    )

    /** An empty F-16CM-40 as WDP's aircraft table weighs it (the clean jet's drag index is 1), for a case of WDP's own field. */
    private val WDP_EMPTY = mapOf(
        "engine" to "100", "empty" to "19800", "intFuel" to "7162", "max" to "48000", "loadout" to "0", "drag" to "1", "extFuel" to "0",
    )

    val SCENARIOS = listOf(
        Scenario("a1-empty", "empty loadout, cruise 21,000 ft, 25 °C, 13°/AB (the user's check, ISA+10 at Osan)"),
        Scenario("a2-empty-isa", "empty loadout, cruise 21,000 ft, 15 °C: a standard day at Osan", temp = "15"),
        Scenario("a3-empty-opt", "empty loadout, 25 °C, the optimum cruise altitude", radio = "radOptCruiseAlt"),
        Scenario("b1-save", "the save's loadout with BMS's pylons and racks, 25 °C, 13°/AB", stores = SAVE),
        Scenario("b2-save-wdp-stores", "the save's loadout as WDP 3.7.24 totals it on 4.38.1 (stores only), 25 °C, 13°/AB", stores = null, given = WDP_STORES_ONLY),
        Scenario("c1-save-10ab", "the save's loadout, pitch 10, Full AB", stores = SAVE, pitch = "10"),
        Scenario("c2-save-13mil", "the save's loadout, pitch 13, MIL", stores = SAVE, power = "MIL"),
        Scenario("c3-save-10mil", "the save's loadout, pitch 10, MIL", stores = SAVE, pitch = "10", power = "MIL"),
        Scenario("d1-save-5c-headwind", "the save's loadout, 5 °C, wind 080/15 on 09L", stores = SAVE, temp = "5", windDir = "80", windSpd = "15"),
        Scenario("d2-save-35c-tailwind", "the save's loadout, 35 °C, wind 270/10 on 09L", stores = SAVE, temp = "35", windDir = "270", windSpd = "10"),
        Scenario("d3-save-qnh-in", "the save's loadout, 23 °C, 080/15, QNH 30.12 in", stores = SAVE, temp = "23", windDir = "80", windSpd = "15", qnh = "30.12"),
        Scenario("d4-save-qnh-hpa", "the save's loadout, 23 °C, 080/15, QNH 1005 hPa", stores = SAVE, temp = "23", windDir = "80", windSpd = "15", qnh = "1005", qnhKind = "hpa"),
        Scenario("d5-save-service", "the save's loadout, 25 °C, the service ceiling", stores = SAVE, radio = "radServiceCeiling"),
        Scenario(
            "e1-bms-training-01", "BMS Training Manual 4.38.1 mission 1: F-16DM-52 at Gunsan 36, AIM-9M + ASQ-T50, 9 °C, 320/5, Q1010, pitch 10 " +
                "(the manual's WDP: rotation 125 kt; MIL climb to 10,200 ft 445 kt/M0.84 in 40 s and 5.0 nm)",
            jet = "F-16DM-52", field = "Gunsan AB", rwy = "36", stores = listOf(1 to "aim-9m-sidewinder", 9 to "an-asq-t50-v-1"),
            temp = "9", windDir = "320", windSpd = "5", qnh = "1010", qnhKind = "hpa", pitch = "10", cruiseAlt = "10200",
        ),
        Scenario(
            "e2-bms-training-01-13", "the same at WDP's default pitch 13",
            jet = "F-16DM-52", field = "Gunsan AB", rwy = "36", stores = listOf(1 to "aim-9m-sidewinder", 9 to "an-asq-t50-v-1"),
            temp = "9", windDir = "320", windSpd = "5", qnh = "1010", qnhKind = "hpa", cruiseAlt = "10200",
        ),
        Scenario(
            "e3-bms-training-01-13-notaxi", "the same at pitch 13 with no taxi fuel (the manual's 26,531 lb gross weight)",
            jet = "F-16DM-52", field = "Gunsan AB", rwy = "36", stores = listOf(1 to "aim-9m-sidewinder", 9 to "an-asq-t50-v-1"),
            temp = "9", windDir = "320", windSpd = "5", qnh = "1010", qnhKind = "hpa", cruiseAlt = "10200", taxiFuel = "0",
        ),
        Scenario(
            "f1-user-wdp", "WDP's own page on the save as the user's copy shows it: Afyon (3,310 ft, 14R 9,952 ft), stores only, 25 °C, 13°/AB",
            stores = null, given = WDP_STORES_ONLY + mapOf("elv" to "3310", "toda" to "9952", "rwyHed" to "140"),
        ),
        Scenario(
            "g1-user-wdp-osan", "WDP as the user set it last (its Setup.ini): Osan picked by hand in Select APT, from WDP's own Korea database " +
                "(97 ft, 09L 9,006 ft), no stores and no tanks, 20 °C, cruise 34,000 ft, taxi fuel 300, 13°/AB",
            stores = null, given = WDP_EMPTY + mapOf("elv" to "97", "toda" to "9006", "rwyHed" to "90"),
            temp = "20", cruiseAlt = "34000", taxiFuel = "300",
        ),
        Scenario(
            "g2-user-bms-osan", "the same pilot inputs on Falcon BMS 4.38.1's Osan AB (42 ft, 09L 9,010 ft), as the Planner plans them",
            temp = "20", cruiseAlt = "34000", taxiFuel = "300",
        ),
        Scenario("t1-turn-26000", "turn calculator: 400 kt CAS, 26,000 ft, 4 g, with the save's loadout", stores = SAVE, cas = "400", alt = "26000", g = "4"),
        Scenario("t2-turn-pick", "turn calculator: 300 kt CAS, 36,000 ft, 5 g, Actual Temp -50 picked", stores = SAVE, cas = "300", alt = "36000", g = "5", actual = "-50"),
    )

    private val DATABASE = listOf("engine", "empty", "intFuel", "max", "loadout", "drag", "extFuel", "elv", "toda", "rwyHed")
    private val CLIMB = listOf("lblDistMil_Val", "lblFuelMil_Val", "lblTimeMil_Val", "lblDistMax_Val", "lblFuelMax_Val", "lblTimeMax_Val")
    private val REFUSAL = listOf("lblRefusal_Val", "lblRefusal_Val.fore", "card.lblRefusal", "card.lblRefusal.fore")
    private val TURN = listOf("cboActualTemp", "lblISAdevVal", "lblDensityAlt", "lblTAS", "lblMach", "lblTurnRadius")

    /** Setup.ini's [Performance] as the harness's copy of WDP's has it, so both pages open alike. */
    private const val INI = "Apt=Osan AB\nRwy=\nTemp=25\nType=29\nPitch=13\nPower=0\nTaxiFuel=200\nCruiseAlt=21000"
    private val INI_MAP = INI.split('\n').associate { it.substringBefore('=') to it.substringAfter('=') }

    fun run(reference: File, out: File): String = buildString {
        appendLine("Weapon Delivery Planner Performance page — WDP and the Planner end to end, on identical inputs")
        appendLine("reference: ${reference.path}")
        val engines = try { runBlocking { Engines.load() } } catch (e: Exception) { appendLine("FAIL — ${e.message}"); return@buildString }
        val savedIni = Repo.getString(PerformanceWiring.INI_KEY)
        var failed = false
        try {
            // the pilot's page for every scenario it can drive, and the inputs WDP's case gets from it
            val driven = LinkedHashMap<String, Pair<Map<String, String>, Map<String, String>>>()   // name → (inputs, labels)
            val inputs = LinkedHashMap<String, Map<String, String>>()
            for (s in SCENARIOS) {
                val pilot = if (s.stores != null) pilotPage(s) else null
                val db = pilot?.first ?: s.given
                val inp = LinkedHashMap<String, String>()
                for (k in DATABASE) inp[k] = db[k] ?: ""
                inp += pilotInputs(s)
                inputs[s.name] = inp
                if (pilot != null) driven[s.name] = pilot
            }
            val spec = inputs.map { (n, m) -> n + "\t" + m.entries.joinToString(";") { "${it.key}=${it.value}" } }
            val inFile = File(reference.path + ".in")
            val ref = if (reference.isFile) parse(reference) else emptyMap()
            val refSpec = ref.map { (n, c) -> n + "\t" + c.first }
            if (ref.isEmpty() || refSpec != spec) {
                inFile.writeText("# name<TAB>inputs: the Performance page's end-to-end cases (WdpPerfCompare.kt)\n" + spec.joinToString("\n") + "\n")
                appendLine()
                appendLine((if (ref.isEmpty()) "no reference yet" else "the reference was made for other cases") + ": wrote ${inFile.path}.")
                appendLine("Run `wdpref page PerfCase <WeaponDeliveryPlanner.exe> ${reference.path}` from a folder of its own, then this again.")
                failed = true
                return@buildString
            }

            var same = 0; var fixed = 0; var bad = 0; var asWdpBad = 0; var drivenBad = 0; var inputBad = 0
            val byFix = LinkedHashMap<String, Int>()
            for (s in SCENARIOS) {
                val inp = inputs.getValue(s.name)
                val wdp = ref.getValue(s.name).second
                appendLine()
                appendLine("== ${s.name} — ${s.what}")
                appendLine("   inputs: " + inp.entries.joinToString(", ") { "${it.key} ${it.value}" })
                wdp["error"]?.let { appendLine("   WDP stopped: $it") }
                val asWdp = arithmetic(inp, s, engines, slips = true)
                val page = arithmetic(inp, s, engines, slips = false)
                val pilot = driven[s.name]?.second
                // the page as driven must be its own arithmetic, and must have planned on the inputs WDP was given
                if (pilot != null) {
                    val off = page.keys.filter { (pilot[it] ?: "") != page[it] }
                    if (off.isNotEmpty()) { drivenBad += off.size; appendLine("   FAIL the page as driven differs from its arithmetic: " + off.joinToString { "$it ${pilot[it]} / ${page[it]}" }) }
                }
                val allowed = fixesFor(inp, page, wdp)
                appendLine("   %-24s %-16s %-16s %s".format("label", "WDP", "Planner", "why they differ"))
                for (label in wdp.keys.filter { it != "error" }) {
                    val want = wdp.getValue(label)
                    val have = (pilot ?: page)[label] ?: "(none)"
                    if (label in asWdp && !same(label, want, asWdp.getValue(label))) {
                        asWdpBad++
                        appendLine("   FAIL as WDP: $label WDP \"$want\", the port as WDP \"${asWdp[label]}\"")
                    }
                    val why = when {
                        same(label, want, have) -> { same++; "" }
                        else -> {
                            val fix = allowed.entries.firstOrNull { label in it.value }?.key
                            if (fix != null) { fixed++; byFix[fix] = (byFix[fix] ?: 0) + 1; fix } else { bad++; "UNEXPLAINED" }
                        }
                    }
                    appendLine("   %-24s %-16s %-16s %s".format(label, want, have, why))
                }
                if (pilot != null) {
                    val db = driven.getValue(s.name).first
                    val offIn = DATABASE.filter { db[it] != inp[it] }
                    if (offIn.isNotEmpty()) { inputBad++; appendLine("   FAIL inputs moved: $offIn") }
                }
            }
            appendLine()
            appendLine("summary: ${SCENARIOS.size} scenarios")
            appendLine("  the port as WDP: ${if (asWdpBad == 0) "every label WDP's" else "$asWdpBad labels differ"}")
            appendLine("  the page as driven against its arithmetic: ${if (drivenBad == 0) "identical" else "$drivenBad labels differ"}")
            appendLine("  the Planner against WDP: $same labels identical, $fixed differ where a documented fix applies, $bad differ unexplained")
            for ((k, n) in byFix) appendLine("    $k: $n labels")
            if (asWdpBad + drivenBad + bad + inputBad > 0) failed = true
        } finally {
            while (WdpDialogs.stack.isNotEmpty()) WdpDialogs.stack.removeAt(WdpDialogs.stack.lastIndex)
            Repo.putString(PerformanceWiring.INI_KEY, savedIni)
        }

        // ------------------------------------------------------------------------ the inputs, against BMS itself
        appendLine()
        if (!bmsWeights(this)) failed = true
        appendLine()
        appendLine(if (failed) "FAIL" else "PASS")
    }

    // ================================================================ WDP's differences the page is allowed

    /** The documented fixes that apply to a case, and the labels each may move. */
    private fun fixesFor(inp: Map<String, String>, page: Map<String, String>, wdp: Map<String, String>): Map<String, List<String>> {
        val m = LinkedHashMap<String, List<String>>()
        val qnhIn = page["txtQNH_In"]?.toDoubleOrNull()
        if (qnhIn != null && qnhIn != 29.92) m["D28 pressure altitude"] = listOf("lblFactor_Val", "dblTOFactor") + REFUSAL
        if (inp["actual"].orEmpty().isNotEmpty()) m["D29 Actual Temp used"] = TURN
        if (page["txtCruiseAlt"] != wdp["txtCruiseAlt"]) m["D30 cruise compared as a number"] = listOf("txtCruiseAlt") + CLIMB
        val elv = inp["elv"]?.toIntOrNull() ?: 0
        val t = inp["temp"]?.toDoubleOrNull()
        if (t != null && Atmosphere.isaDeviation(elv, t) != 0) m["D31 ISA deviation to the climb charts"] = CLIMB
        if (inp["engine"] == "100" && (inp["drag"]?.toIntOrNull() ?: 0) in 101..200) m["D31 GE-100 AB schedule"] = listOf("lblScheduleMax_Val")
        if (((inp["toda"]?.toIntOrNull() ?: 0) - 150) in 12001..14999) m["D31 refusal chart 12,000-15,000 ft"] = REFUSAL
        return m
    }

    private fun same(label: String, a: String, b: String): Boolean {
        if (a == b) return true
        // the take-off factor as a double: the same to the last bits (.NET's "R" against Kotlin's shortest form)
        if (label == "dblTOFactor") { val x = a.toDoubleOrNull(); val y = b.toDoubleOrNull(); return x != null && y != null && kotlin.math.abs(x - y) < 1e-12 }
        return false
    }

    // ================================================================ the Planner's page

    private fun pilotInputs(s: Scenario): Map<String, String> = linkedMapOf(
        "temp" to s.temp, "windDir" to s.windDir, "windSpd" to s.windSpd, "qnh" to s.qnh, "qnhKind" to s.qnhKind,
        "pitch" to s.pitch, "power" to s.power, "radio" to s.radio, "cruiseAlt" to s.cruiseAlt, "taxiFuel" to s.taxiFuel,
        "cas" to s.cas, "alt" to s.alt, "g" to s.g, "actual" to s.actual,
    )

    private fun top(): WdpDialog? = WdpDialogs.stack.lastOrNull() as? WdpDialog

    /**
     * The page as a pilot drives it: a fresh page with no mission, the jet in Type, the field in Select APT, the runway,
     * the stores through the Loadout window, then every box and list. Answers the database figures it planned on
     * (what WDP's case is given) and every label.
     */
    private fun pilotPage(s: Scenario): Pair<Map<String, String>, Map<String, String>> {
        Repo.putString(PerformanceWiring.INI_KEY, INI)
        val w = PerformanceWiring()
        runBlocking { w.prepare(null) }
        w.onValue("cboType", s.jet)
        if (w.plan.airport?.name != s.field) {
            w.onClick("btnSelect")
            top()?.wiring?.let { d -> d.onValue("cboAirports", s.field); d.onClick("btnSelect") }
        }
        w.onValue("cboRWY", s.rwy)
        // the stores, station by station, in the Loadout window (Both sides off: each hardpoint as the save has it)
        w.onClick("btnLoadout")
        val win = top()?.wiring as? PerfLoadoutWindow ?: error("the Loadout window did not open")
        win.onClick("btnClear")
        if (win.values(emptyList())["both"] == "true") win.toggleBoth()
        for ((hp, key) in s.stores.orEmpty()) { win.onClick(PerfLoadoutWindow.anchor(hp)); win.tapStore(key) }
        val hung = win.values(emptyList())["stores"].orEmpty()
        win.onClick("btnOK")
        (WdpDialogs.stack.lastOrNull() as? WdpMessage)?.let { m -> WdpDialogs.stack.remove(m); m.onAnswer?.invoke("Yes") }
        check(top() == null) { "the Loadout window stayed open (stores $hung)" }
        w.onValue("numTaxiFuel", s.taxiFuel)
        w.onValue("txtTemp", s.temp); w.onValue("txtTemp.leave", "")
        w.onValue("txtWindDir", s.windDir); w.onValue("txtWindSpd", s.windSpd)
        if (s.qnhKind == "hpa") { w.onValue("txtQNH_Hpa", s.qnh); w.onValue("txtQNH_Hpa.leave", "") }
        else { w.onValue("txtQNH_In", s.qnh); w.onValue("txtQNH_In.leave", "") }
        w.onValue("cboPitch", s.pitch)
        w.onValue("cboPower", s.power)
        w.onClick(s.radio)
        w.onValue("txtCruiseAlt", s.cruiseAlt); w.onValue("txtCruiseAlt.leave", "")
        if (s.cas.isNotEmpty()) w.onValue("cboCAS", s.cas)
        if (s.alt.isNotEmpty()) w.onValue("cboAltitude", s.alt)
        if (s.g.isNotEmpty()) w.onValue("cboGs", s.g)
        if (s.actual.isNotEmpty()) w.onValue("cboActualTemp", s.actual)
        val p = w.plan
        val db = linkedMapOf(
            "engine" to p.currentEngine.toString(), "empty" to p.intEmpty.toString(), "intFuel" to p.intIntFuel.toString(), "max" to p.intMax.toString(),
            "loadout" to p.intLoadout.toString(), "drag" to p.intDrag.toString(), "extFuel" to p.intExtFuel.toString(),
            "elv" to p.intElv.toString(), "toda" to p.intTODA.toString(), "rwyHed" to p.intRwyHed.toString(),
        )
        return db to labelsOf(p, w)
    }

    /** The page's arithmetic given a case the way WDP's harness gives it (the port as WDP with [slips]). */
    private fun arithmetic(inp: Map<String, String>, s: Scenario, engines: Map<Int, com.bmscompanion.app.data.wdp.Engine>, slips: Boolean): Map<String, String> {
        Repo.putString(PerformanceWiring.INI_KEY, INI)
        val w = if (slips) null else PerformanceWiring()
        val p = w?.plan ?: PerformancePlan(wdpSlips = true).also { it.load(INI_MAP) }
        fun i(k: String) = inp[k]?.toIntOrNull() ?: 0
        p.engines = engines
        // the jet picked in Type, weighing what the case gives (WDP's aircraft table writes the engine and the weights)
        p.aircraftData = { PerformancePlan.AcData(i("empty"), i("intFuel"), i("max")) }
        p.cboType.selectItem(s.jet); p.typeChange()
        p.currentEngine = i("engine"); p.intEmpty = i("empty"); p.intIntFuel = i("intFuel"); p.intMax = i("max")
        p.intLoadout = i("loadout"); p.intDrag = i("drag"); p.intExtFuel = i("extFuel")
        p.intElv = i("elv"); p.intTODA = i("toda"); p.intRwyHed = i("rwyHed")
        p.setFuel(); p.fillLoadoutDrag()
        p.txtTemp.set(inp.getValue("temp")); p.txtWindDir.set(inp.getValue("windDir")); p.txtWindSpd.set(inp.getValue("windSpd"))
        if (inp["qnhKind"] == "hpa") { p.txtQNH_Hpa.set(inp.getValue("qnh")); p.qnhHpa() } else { p.txtQNH_In.set(inp.getValue("qnh")); p.qnhInch() }
        p.programFlow()
        p.cboPitch.selectItem(inp["pitch"]); p.cboPower.selectItem(inp["power"])
        p.check(inp.getValue("radio"))
        p.txtCruiseAlt.set(inp.getValue("cruiseAlt"))
        p.programFlow()
        p.setTaxiFuel(i("taxiFuel")); p.setFuel(); p.weights(); p.programFlow()
        // the turn calculator worked out once the page is loaded, as fclsMain does, then the picks
        p.programflowTurn()
        inp["cas"]?.takeIf { it.isNotEmpty() }?.let { p.cboCAS.selectItem(it) }
        inp["alt"]?.takeIf { it.isNotEmpty() }?.let { p.cboAltitude.selectItem(it) }
        inp["g"]?.takeIf { it.isNotEmpty() }?.let { p.cboGs.selectItem(it) }
        inp["actual"]?.takeIf { it.isNotEmpty() }?.let { p.cboActualTemp.selectItem(it) }
        return labelsOf(p, w)
    }

    /** Every label as PerfCase writes WDP's: the page's, its boxes and lists, the engine's, and the DataCard's take-off block. */
    private fun labelsOf(p: PerformancePlan, w: PerformanceWiring?): Map<String, String> {
        val m = LinkedHashMap<String, String>(p.labels)
        m["txtCruiseAlt"] = p.txtCruiseAlt.text; m["txtQNH_In"] = p.txtQNH_In.text; m["txtQNH_Hpa"] = p.txtQNH_Hpa.text
        m["txtTemp"] = p.txtTemp.text; m["txtWindDir"] = p.txtWindDir.text; m["txtWindSpd"] = p.txtWindSpd.text
        m["cboActualTemp"] = p.cboActualTemp.text; m["cboPitch"] = p.cboPitch.text; m["cboPower"] = p.cboPower.text
        m["lblGross_Val.fore"] = p.fore["lblGross_Val"] ?: "White"
        m["lblRefusal_Val.fore"] = p.fore["lblRefusal_Val"] ?: "White"
        m["intGrossWt"] = p.intGrossWt.toString()
        m["dblTOFactor"] = p.dblTOFactor.toString()
        if (w != null) {
            val card = DataCardWiring()
            WdpHandOff.performanceToCard(w, card)
            for (n in listOf("lblTOSpec", "lblRotation", "lblRefusal", "lblMilClimb", "lblGrossWgt")) m["card.$n"] = card.plan.text(n)
            m["card.lblRefusal.fore"] = if (card.plan.labels["lblRefusal.fore"] == "Red") "Red" else "Black"
        }
        return m
    }

    /** PerfCase's output: case name → (its inputs as written, label → text). */
    private fun parse(f: File): Map<String, Pair<String, Map<String, String>>> {
        val out = LinkedHashMap<String, Pair<String, Map<String, String>>>()
        var name: String? = null; var spec = ""; var labels = LinkedHashMap<String, String>()
        fun flush() { name?.let { out[it] = spec to labels } }
        for (line in f.readLines()) {
            if (line.startsWith("case\t")) {
                val parts = line.split('\t', limit = 3)
                if (parts.getOrNull(1) == "name") continue
                flush(); name = parts.getOrNull(1); spec = parts.getOrNull(2).orEmpty(); labels = LinkedHashMap()
            } else if (line.startsWith("\t") && name != null) {
                val parts = line.substring(1).split('\t', limit = 2)
                labels[parts[0]] = parts.getOrNull(1).orEmpty()
            }
        }
        flush()
        return out
    }

    // ================================================================ the inputs against BMS's own figures

    /** A flight of a BMS training mission and what BMS's Training Manual 4.38.1 prints for it. */
    private class Published(val file: String, val callsign: String, val gross: Int, val dragFactor: Int? = null, val page: String, val note: String? = null)

    private val PUBLISHED = listOf(
        Published("TR_BMS_01_GroundOPS.trn", "Goblin2", 26531, 9, "p.9, p.52"),
        Published("TR_BMS_07_Flameout.trn", "Barrel1", 28734, null, "p.141"),
        Published("TR_BMS_09_Failures.trn", "Magnet4", 34198, null, "p.157"),
        Published("TR_BMS_17A_IR_Intercept.trn", "Falcon1", 35950, null, "mission 17A"),
        Published("TR_BMS_17C_IDM_LINK16.trn", "Gamble6", 39058, null, "mission 17C"),
        Published("TR_BMS_18_Barcap.trn", "Falcon1", 36788, null, "mission 18"),
        Published("TR_BMS_19_Guns.trn", "Falcon1", 31159, null, "p.315"),
        Published("TR_BMS_27_JTAC.trn", "Cyborg7", 39552, null, "mission 27",
            "the manual lists an AN/AAQ-33 and AIM-120Cs; the mission file carries an AN/AAQ-14 and AIM-120Bs"),
        Published("TR_BMS_28_SEAD-EW.trn", "Panther4", 38976, null, "p.371",
            "the manual's figure is 142 lb over the mission file's stores and BMS's pylons"),
    )

    /**
     * BMS's gross weight of a training flight — empty weight, the flight's own fuel, its tanks' fuel and its stores on
     * their pylons and racks, as the loadout screen adds them (User Manual 4.38.1 p.151: "Gross weight is the sum of
     * the clean weight + munitions weight + fuel weight") — against the page's loadout for the same stores, with and
     * without the pylons and racks. True when every figure without a note is met with them.
     */
    private fun bmsWeights(sb: StringBuilder): Boolean {
        sb.appendLine("the loadout's weight and drag against Falcon BMS's own figures (Training Manual 4.38.1)")
        val root = (System.getenv("BMSC_WDP_BMS") ?: Bridge.settings.value.BmsDirOverride)?.let(::File)
        val campaign = root?.let { Theaters.resolveDir(it, "Data/Campaign") }
        if (root == null || campaign == null) { sb.appendLine("  skipped: no BMS folder in the settings (BmsDirOverride) or BMSC_WDP_BMS"); return true }
        val aircraft = runBlocking { Repo.aircraft() }
        val weapons = runBlocking { Repo.weaponMap() }
        val racks = runBlocking { PerformanceLoadout.loadRacks() }
        val set = Theaters.at(root)
        val t = set.owning(campaign) ?: set.all.first()
        val names = CampaignArchive.names(set, t)
        var ok = true; var with = 0; var without = 0; var checked = 0
        for (pub in PUBLISHED) {
            val f = File(campaign, pub.file)
            if (!f.isFile) { sb.appendLine("  ${pub.file}: not in this BMS folder"); continue }
            val save = CampaignArchive.read(f, names)
            val fl = save.flights.firstOrNull { names.callsign(it).equals(pub.callsign, true) }
            if (fl == null) { sb.appendLine("  ${pub.file}: no flight ${pub.callsign}"); ok = false; continue }
            val type = names.aircraft(fl.type).orEmpty()
            val n = PerformancePlan.norm(type)
            val a = aircraft.firstOrNull { PerformancePlan.norm(it.name) == n }
                ?: aircraft.firstOrNull { x -> x.variants.any { v -> v.spec.datFile?.let { PerformancePlan.norm(it) } == n } }
            val v = a?.variants?.firstOrNull()
            if (a == null || v == null) { sb.appendLine("  ${pub.file}: ${pub.callsign}'s $type is not in the app's aircraft data"); ok = false; continue }
            val withM = PerformanceLoadout(v.stations, weapons, racks.forVariant(a.key, v.theaters))
            val bare = PerformanceLoadout(v.stations, weapons)
            val l = fl.loadouts.firstOrNull() ?: continue
            for (hp in l.weapons.indices) {
                if (l.weapons[hp] == 0 || l.counts[hp] == 0) continue
                val w = names.weapon(l.weapons[hp])?.trim()?.let { nm -> weapons.values.firstOrNull { it.name.equals(nm, true) } } ?: continue
                if (w.category == "GUN") continue
                withM.seats[0][hp] = PerformanceLoadout.Load(w.key, l.counts[hp])
                bare.seats[0][hp] = PerformanceLoadout.Load(w.key, l.counts[hp])
            }
            val empty = v.spec.emptyWeightLbs?.toInt() ?: 0
            val fuel = fl.fuelInitial.getOrNull(0) ?: 0
            val tw = withM.totals(0); val tb = bare.totals(0)
            val gw = empty + fuel + tw.extFuel + tw.loadWeight
            val gb = empty + fuel + tb.extFuel + tb.loadWeight
            checked++
            if (gw == pub.gross) with++
            if (gb == pub.gross) without++
            val met = gw == pub.gross && (pub.dragFactor == null || tw.totalDrag == pub.dragFactor)
            if (!met && pub.note == null) ok = false
            sb.appendLine("  %-30s %-9s %-14s BMS %6d%s | with pylons and racks %6d (%s lb, drag %d) | stores alone %6d (%s lb, drag %d)%s".format(
                pub.file, pub.callsign, type, pub.gross, pub.dragFactor?.let { ", drag factor $it" } ?: "",
                gw, tw.loadWeight, tw.totalDrag, gb, tb.loadWeight, tb.totalDrag,
                when { met -> "  = BMS"; pub.note != null -> "  (${pub.note})"; else -> "  FAIL" }))
        }
        sb.appendLine("  BMS's gross weight met: with pylons and racks $with of $checked, stores alone $without of $checked")
        return ok
    }
}
