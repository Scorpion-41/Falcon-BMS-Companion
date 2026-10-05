package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.wdp.Atmosphere
import com.bmscompanion.app.data.wdp.DataCardNet
import com.bmscompanion.app.data.wdp.Engines
import com.bmscompanion.app.data.wdp.PerformanceLoadout
import com.bmscompanion.app.data.wdp.PerformancePlan
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.ui.screens.mission.airbases
import com.bmscompanion.app.ui.screens.mission.resolveTheater
import com.bmscompanion.app.ui.screens.wdp.PerformanceWiring
import com.bmscompanion.app.ui.screens.wdp.WdpDialog
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpMessage
import com.bmscompanion.app.ui.screens.wdp.WdpMission
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `--wdppagetest performance <reference.tsv> <out.txt>` — the Performance page, the port against the program, and
 * the fixes against what they must answer.
 *
 * The reference is `wdpref page Performance`: the real `cntPerformance`, loaded as its Load event loads it (WDP's
 * own Setup.ini read), then each row's jet, airfield and weather put into it the way WDP puts them — the
 * databases' figures into its fields, the pilot's into its boxes and lists through their own events — and every
 * label and rewritten box copied out. The second section is the turn calculator: speed, altitude and G picked from
 * their lists, and on every third row an actual temperature.
 *
 * Each row is replayed twice:
 * - **as the program** (`PerformancePlan(wdpSlips = true)`): every label must be identical. This is what shows the
 *   port is the program apart from the fixes.
 * - **as the page** (the fixes on): a label may differ only where a fix applies to the row, and only in the labels
 *   that fix moves — the allow-list below (D28-D31 of the plan). Anything else is a failure. (D32, the ICAO 1.98 °C
 *   lapse, is withdrawn: BMS's atmosphere is WDP's 2 °C per 1,000 ft, so the ISA deviation and the turn calculator's
 *   standard temperature must now be WDP's to the digit.)
 *
 * Then the fixes are checked for being right, not only for being the only differences: the pressure-altitude table
 * (Osan 42 ft: 29.92 → 42, 30.42 → −458, 29.42 → 542), the take-off factor rising on a low-pressure day, the Actual
 * Temp moving the turn figures, "9000" staying 9000, the third runway's own metres, the refusal chart continuous
 * across its 12,000 and 15,000 ft edges, the GE-100's AB schedule, and pylons and racks adding Falcon BMS's weight
 * and drag to a loaded F-16.
 */
internal fun wdpPerformanceTest(reference: File, out: File): String =
    // `<x>.cases.tsv`: the page and WDP end to end on named scenarios (WdpPerfCompare.kt); anything else is the grid
    if (reference.name.endsWith("cases.tsv", ignoreCase = true)) WdpPerfCompare.run(reference, out) else buildString {
    appendLine("Weapon Delivery Planner Performance page — the port against the program, and the fixes")
    appendLine("reference: ${reference.path}")
    if (!reference.isFile) { appendLine(); appendLine("FAIL — no reference. Run `wdpref page Performance` from WDP's folder first."); return@buildString }
    val engines = try { runBlocking { Engines.load() } } catch (e: Exception) { appendLine("FAIL — ${e.message}"); return@buildString }
    // the [Performance] section of the Setup.ini the reference read, as the harness's WDP folder has it
    val ini = mapOf("Temp" to "25", "Type" to "52", "Pitch" to "13", "Power" to "0", "CruiseAlt" to "25000")
    var failed = false

    val lines = reference.readLines()
    val head = lines.firstOrNull()?.split('\t') ?: run { appendLine("FAIL — empty reference"); return@buildString }
    var rows = 0; var cells = 0
    var asWdpBad = 0; var fixedBad = 0; var expected = 0
    val byLabel = LinkedHashMap<String, Int>()
    val byFix = LinkedHashMap<String, Int>()
    val examples = StringBuilder()
    var turnHead: List<String> = emptyList()
    var turnRows = 0; var turnAsWdpBad = 0; var turnFixedBad = 0; var turnExpected = 0; var picked = 0

    val climb = listOf("lblDistMil_Val", "lblFuelMil_Val", "lblTimeMil_Val", "lblDistMax_Val", "lblFuelMax_Val", "lblTimeMax_Val")
    val refusal = listOf("lblRefusal_Val", "lblRefusal_Val.fore")

    for (line in lines.drop(1)) {
        val f = line.split('\t')
        when (f[0]) {
            "perf" -> {
                rows++
                fun col(n: String) = f.getOrNull(head.indexOf(n)) ?: ""
                if (col("error").isNotEmpty()) { appendLine("note: the program stopped on row $rows (${col("error")})"); continue }
                fun run(slips: Boolean): Pair<PerformancePlan, Map<String, String>> {
                    val p = PerformancePlan(wdpSlips = slips)
                    p.engines = engines
                    p.load(ini)
                    p.currentEngine = col("engine").toInt()
                    p.intEmpty = col("empty").toInt()
                    p.intIntFuel = col("intFuel").toInt()
                    p.intMax = col("max").toInt()
                    p.intLoadout = col("loadout").toInt()
                    p.intDrag = col("drag").toInt()
                    p.intExtFuel = col("extFuel").toInt()
                    p.intElv = col("elv").toInt()
                    p.intTODA = col("toda").toInt()
                    p.intRwyHed = col("rwyHed").toInt()
                    try {
                        p.setFuel()
                        p.txtTemp.set(col("temp"))
                        p.txtWindDir.set(col("windDir"))
                        p.txtWindSpd.set(col("windSpd"))
                        if (col("qnhKind") == "hpa") { p.txtQNH_Hpa.set(col("qnh")); p.qnhHpa() } else { p.txtQNH_In.set(col("qnh")); p.qnhInch() }
                        p.programFlow()
                        p.cboPitch.select(col("pitch").toInt())
                        p.cboPower.select(col("power").toInt())
                        p.check(col("radio"))
                        p.txtCruiseAlt.set(col("cruiseAlt"))
                        p.programFlow()
                        p.setTaxiFuel(col("taxiFuel").toInt())
                        p.programFlow()
                    } catch (e: Exception) { if (examples.length < 6000) examples.appendLine("  row $rows: the port threw ${e::class.simpleName} (slips=$slips)") }
                    val got = HashMap<String, String>(p.labels)
                    got["txtCruiseAlt"] = p.txtCruiseAlt.text; got["txtQNH_In"] = p.txtQNH_In.text; got["txtQNH_Hpa"] = p.txtQNH_Hpa.text
                    got["txtTemp"] = p.txtTemp.text; got["txtWindDir"] = p.txtWindDir.text; got["txtWindSpd"] = p.txtWindSpd.text
                    got["lblGross_Val.fore"] = p.fore["lblGross_Val"] ?: "White"
                    got["lblRefusal_Val.fore"] = p.fore["lblRefusal_Val"] ?: "White"
                    return p to got
                }
                val (wp, asWdp) = run(true)
                val (fp, fixed) = run(false)

                // which fixes this row meets, and the labels each may move
                val allowed = LinkedHashMap<String, List<String>>()
                if (wp.dblQNH_In != 29.92) allowed["D28 pressure altitude"] = listOf("lblFactor_Val") + refusal
                if (!DataCardNet.isNumeric(col("temp"))) allowed["D29 temperature refused"] =
                    listOf("txtTemp", "lblTempF", "lblISA_Dev", "lblFactor_Val") + refusal + climb
                if (asWdp["txtCruiseAlt"] != fixed["txtCruiseAlt"]) allowed["D30 cruise compared as a number"] =
                    listOf("txtCruiseAlt") + climb
                if (Atmosphere.isaDeviation(fp.intElv, fp.dblTempC) != 0) allowed["D31 ISA deviation to the climb charts"] = climb
                if (col("engine") == "100" && col("drag").toInt() in 101..200) allowed["D31 GE-100 AB schedule"] = listOf("lblScheduleMax_Val")
                if (col("toda").toInt() - 150 in 12001..14999) allowed["D31 refusal chart 12,000-15,000 ft"] = refusal
                val mayMove = allowed.values.flatten().toSet()

                for (n in head.drop(21)) {
                    if (n == "error") continue
                    cells++
                    val want = col(n)
                    if (want != (asWdp[n] ?: "")) {
                        asWdpBad++; byLabel[n] = (byLabel[n] ?: 0) + 1
                        if (examples.length < 6000) examples.appendLine("  row $rows $n: program \"$want\", port as the program \"${asWdp[n]}\"")
                    }
                    val have = fixed[n] ?: ""
                    if (want != have) {
                        if (n in mayMove) {
                            expected++
                            for ((fix, labels) in allowed) if (n in labels) byFix[fix] = (byFix[fix] ?: 0) + 1
                        } else {
                            fixedBad++
                            if (examples.length < 6000) examples.appendLine("  row $rows $n: program \"$want\", page \"$have\" — no fix explains it" +
                                (if (allowed.isEmpty()) "" else " (fixes on this row: ${allowed.keys.joinToString()})"))
                        }
                    }
                }
            }
            "turnhead" -> turnHead = f
            "turn" -> {
                turnRows++
                fun col(n: String) = f.getOrNull(turnHead.indexOf(n)) ?: ""
                val actual = col("actual").toInt()
                if (actual >= 0) picked++
                fun run(slips: Boolean): Map<String, String> {
                    val p = PerformancePlan(wdpSlips = slips)
                    p.engines = engines
                    p.load(ini)
                    p.cboCAS.select(col("cas").toInt())
                    p.cboAltitude.select(col("alt").toInt())
                    p.cboGs.select(col("g").toInt())
                    if (actual >= 0) p.cboActualTemp.select(actual)
                    val got = HashMap<String, String>(p.labels)
                    got["cboActualTemp"] = p.cboActualTemp.text
                    return got
                }
                val asWdp = run(true)
                val fixed = run(false)
                val labels = turnHead.drop(5).filter { it.isNotEmpty() }
                val differs = labels.filter { col(it) != (asWdp[it] ?: "") }
                if (differs.isNotEmpty()) {
                    turnAsWdpBad++
                    if (examples.length < 6000) examples.appendLine("  turn row $turnRows as the program: " + differs.joinToString { "$it program \"${col(it)}\" port \"${asWdp[it]}\"" })
                }
                // D29 (a pick is used) moves the temperature and everything worked from it; CAS, G and the altitude alone
                // move nothing here (the standard day is WDP's 2 °C a thousand feet, BMS's), so on a row without a pick
                // nothing may differ
                val turnMay = if (actual >= 0) setOf("lblTempStandard", "cboActualTemp", "lblISAdevVal", "lblDensityAlt", "lblTAS", "lblMach", "lblTurnRadius") else emptySet()
                val fixedDiffers = labels.filter { col(it) != (fixed[it] ?: "") }
                val unexplained = fixedDiffers.filter { it !in turnMay }
                if (unexplained.isNotEmpty()) {
                    turnFixedBad++
                    if (examples.length < 6000) examples.appendLine("  turn row $turnRows page: " + unexplained.joinToString { "$it program \"${col(it)}\" page \"${fixed[it]}\"" })
                } else if (fixedDiffers.isNotEmpty()) turnExpected++
            }
        }
    }
    appendLine()
    appendLine("take-off, climb and cruise: $rows rows, $cells labels")
    appendLine("  as the program (slips kept): ${cells - asWdpBad} identical, $asWdpBad differ")
    for ((k, n) in byLabel) appendLine("    $k: $n")
    appendLine("  as the page (slips fixed): $expected labels differ where a fix applies, $fixedBad differ unexplained")
    for ((k, n) in byFix) appendLine("    $k: $n labels")
    appendLine("turn calculator: $turnRows rows ($picked with an Actual Temp picked)")
    appendLine("  as the program: ${turnRows - turnAsWdpBad} identical, $turnAsWdpBad differ")
    appendLine("  as the page: $turnExpected rows differ where D29 applies, $turnFixedBad differ unexplained")
    if (asWdpBad + fixedBad + turnAsWdpBad + turnFixedBad > 0) failed = true

    // ---------------------------------------------------------------- the fixes answer what they must
    appendLine()
    appendLine("the fixes, checked for their answers")
    fun check(name: String, ok: Boolean, detail: String) {
        appendLine((if (ok) "  ok    " else "  FAIL  ") + name + " — " + detail)
        if (!ok) failed = true
    }

    // D28: the pressure-altitude table
    for ((q, want) in listOf(29.92 to 42, 30.42 to -458, 29.42 to 542)) {
        val pa = Atmosphere.pressureAltitudeFt(42, q)
        check("D28 pressure altitude, Osan 42 ft at $q in", pa == want, "$pa ft (want $want; WDP ${Atmosphere.wdpPressureAltitudeFt(42, q)})")
    }

    // a page at Osan as the mission put it: GE-100, 35,635 lb, TODA 9,010 ft, 23 °C, wind 1°/15 kt
    fun osan(slips: Boolean = false, qnhIn: String = "29.92", tempC: String = "23"): PerformancePlan {
        val p = PerformancePlan(wdpSlips = slips)
        p.engines = engines
        p.load(ini)
        p.missionAircraft = "F-16CM-40"; p.missionIsF16 = true
        p.cboType.selectItem("F-16CM-40")
        p.database(PerformancePlan.Apt("Osan AB", "RKSO", elevation = 42, runways = listOf(
            PerformancePlan.Rwy("09L", 9010, 9010, 149), PerformancePlan.Rwy("27R", 9010, 9010, 149),
            PerformancePlan.Rwy("09R", 9000, 9000, 150), PerformancePlan.Rwy("27L", 9000, 9000, 200))))
        p.cboType.selectItem("F-16CM-40"); p.typeChange()
        p.applyLoadout(3841, 102, 7162, 5032)
        p.txtWindDir.set("1"); p.txtWindSpd.set("15"); p.txtTemp.set(tempC)
        p.txtQNH_In.set(qnhIn); p.qnhInLeave()
        p.programFlow()
        return p
    }
    fun factor(p: PerformancePlan) = p.labels["lblFactor_Val"] ?: ""
    val std = osan(); val low = osan(qnhIn = "29.42"); val high = osan(qnhIn = "30.42")
    val wStd = osan(true); val wLow = osan(true, "29.42"); val wHigh = osan(true, "30.42")
    check("D28 take-off factor rises on a low-pressure day", factor(low).toDouble() > factor(std).toDouble() && factor(high).toDouble() < factor(std).toDouble(),
        "29.42 in ${factor(low)}, 29.92 in ${factor(std)}, 30.42 in ${factor(high)} (WDP: ${factor(wLow)}, ${factor(wStd)}, ${factor(wHigh)})")

    // D29: Temp C +15 changes the factor; Actual Temp +15 changes the turn figures; a non-number is refused
    val hot = osan(tempC = "38")
    check("D29 Temp C +15 °C moves the take-off factor", factor(hot) != factor(std), "23 °C ${factor(std)}, 38 °C ${factor(hot)}")
    run {
        // as the wiring opens the page: the turn calculator worked out once, at 15,000 ft, 230 kt, 4 g
        val p = osan(); p.cboAltitude.selectItem("15000"); p.cboCAS.selectItem("230"); p.cboGs.selectItem("4"); p.programflowTurn()
        val isa = p.labels["lblTempStandard"] ?: ""
        val before = listOf("lblTAS", "lblMach", "lblDensityAlt", "lblTurnRadius").map { p.labels[it] }
        p.cboActualTemp.selectItem(((isa.toIntOrNull() ?: 0) + 15).toString())
        val after = listOf("lblTAS", "lblMach", "lblDensityAlt", "lblTurnRadius").map { p.labels[it] }
        check("D29 Actual Temp ISA+15 is used", p.cboActualTemp.text == ((isa.toIntOrNull() ?: 0) + 15).toString() && before != after && p.labels["lblISAdevVal"] == "15",
            "ISA $isa °C, list ${p.cboActualTemp.text}, ISA dev ${p.labels["lblISAdevVal"]}; TAS/Mach/DA/radius $before → $after")
        p.cboAltitude.selectItem("5000")
        check("D29 the pick is kept as a deviation when the altitude changes", p.labels["lblISAdevVal"] == "15",
            "at 5000 ft: ISA ${p.labels["lblTempStandard"]}, list ${p.cboActualTemp.text}, dev ${p.labels["lblISAdevVal"]}")
    }
    run {
        val p = osan(); val f0 = factor(p)
        p.txtTemp.set("-"); val mid = factor(p); val box = p.txtTemp.text
        p.txtTemp.set("-5"); val cold = factor(p)
        p.txtTemp.set("abc"); p.tempLeave()
        check("D29 a Temp C that is not a number is refused", mid == f0 && box == "-" && cold != f0 && p.txtTemp.text == "-5",
            "23 °C $f0; typing \"-\" keeps $mid and the box \"$box\"; -5 °C $cold; \"abc\" then leaving gives \"${p.txtTemp.text}\"")
    }

    // D30: "9000" typed a key at a time stays 9000; the third runway's metres
    run {
        val p = osan(); val sc = p.labels["lblServiceCeiling_Val"]
        for (s in listOf("9", "90", "900", "9000")) p.txtCruiseAlt.set(s)
        val w = osan(true); for (s in listOf("9", "90", "900", "9000")) w.txtCruiseAlt.set(s)
        check("D30 cruise \"9000\" stays 9000", p.txtCruiseAlt.text == "9000", "page \"${p.txtCruiseAlt.text}\" (service ceiling $sc; WDP \"${w.txtCruiseAlt.text}\")")
        p.txtCruiseAlt.set("99000")
        check("D30 a cruise above the service ceiling still becomes it", p.txtCruiseAlt.text == sc, "99000 → \"${p.txtCruiseAlt.text}\"")
        p.cboRWY.select(2)
        val w3 = p.labels["lblWidth_Val"]
        w.cboRWY.select(2)
        check("D30 the third runway's metres from its own width", w3 == "150 f / 46 m", "RWY 09R (150 ft; the fourth is 200 ft): \"$w3\" (WDP \"${w.labels["lblWidth_Val"]}\")")
    }

    // D31: the refusal chart has no step at 12,000 or 15,000 ft; the GE-100's AB schedule
    for ((id, e) in engines) {
        fun r(len: Int, slips: Boolean): Double {
            if (slips || len <= 12000 || len >= 15000) return e.refusalSpeed(true, 1.5, 36000.0, len, 0.0)
            val t = (len - 12000) / 3000.0
            val lo = e.refusalSpeed(true, 1.5, 36000.0, 12000, 0.0); val hi = e.refusalSpeed(true, 1.5, 36000.0, 15000, 0.0)
            return e.refusalWindCorrection(lo + (hi - lo) * t, 0.0)
        }
        fun step(slips: Boolean) = maxOf(kotlin.math.abs(r(12001, slips) - r(12000, slips)), kotlin.math.abs(r(15000, slips) - r(14999, slips)))
        check("D31 refusal chart continuous at 12,000/15,000 ft, ${e.cls}", step(false) < 0.05,
            "largest 1-ft step ${"%.3f".format(step(false))} kt (WDP ${"%.3f".format(step(true))} kt)")
    }
    run {
        val p = osan(); p.applyLoadout(3841, 120, 7162, 5032); p.programFlow(); val a = p.labels["lblScheduleMax_Val"]
        p.applyLoadout(3841, 180, 7162, 5032); p.programFlow(); val b = p.labels["lblScheduleMax_Val"]
        val w = osan(true); w.applyLoadout(3841, 120, 7162, 5032); w.programFlow()
        check("D31 GE-100 MAX AB schedule, drag 120 and 180", a == "565 / 0.90" && b == "550 / 0.90", "\"$a\", \"$b\" (WDP \"${w.labels["lblScheduleMax_Val"]}\")")
    }

    // D33: pylons and racks — the mission's F-16CM-40 loadout with and without them
    run {
        val aircraft = runBlocking { Repo.aircraft() }.firstOrNull { it.key == "f-16cm-40" }
        val weapons = runBlocking { Repo.weaponMap() }
        val racks = runBlocking { PerformanceLoadout.loadRacks() }
        if (aircraft == null) { check("D33 pylons and racks", false, "no F-16CM-40 in the app's aircraft data"); return@run }
        val v = aircraft.variants.first()
        val stores = listOf(4 to "AIM-120C AMRAAM", 2 to "AIM-9X Sidewinder", 2 to "Tank 370gal", 1 to "AN/ALQ-184")
        val bare = PerformanceLoadout(v.stations, weapons).also { it.fromBriefing(listOf(stores)) }.totals(0)
        val l = PerformanceLoadout(v.stations, weapons, racks.forVariant(aircraft.key, v.theaters))
        l.fromBriefing(listOf(stores))
        val t = l.totals(0)
        val hung = l.seats[0].entries.sortedBy { it.key }.joinToString { (hp, ld) -> "$hp:${ld.count}x${ld.key}" + (l.mount(hp, ld)?.let { m -> "+${m.pylonLbs + m.rackLbs}lb/${m.pylonDrag + m.rackDrag}" } ?: "") }
        check("D33 pylons and racks add BMS's weight and drag", t.loadWeight > bare.loadWeight && t.totalDrag > bare.totalDrag,
            "stores alone ${bare.loadWeight} lb / drag ${bare.totalDrag}; with pylons and racks ${t.loadWeight} lb / drag ${t.totalDrag} [$hung]")
        val two = PerformanceLoadout(v.stations, weapons, racks.forVariant(aircraft.key, v.theaters))
        two.fromBriefing(listOf(listOf(7 to "Mk-82 AIR")))
        val hung2 = two.seats[0].entries.sortedBy { it.key }.joinToString { (hp, ld) -> "$hp:${ld.count}x" + (two.mount(hp, ld)?.let { m -> "${m.slots}-slot ${m.rackLbs}lb/${m.rackDrag} on ${m.pylonLbs}lb/${m.pylonDrag}" } ?: "none") }
        val racked = two.seats[0].all { (hp, ld) -> two.mount(hp, ld)?.let { (ld.count > 1) == (it.rackLbs > 0) && it.pylonLbs > 0 } ?: false }
        check("D33 a TER for three bombs, the bare pylon for one", two.seats[0].isNotEmpty() && racked, "7x Mk-82 AIR → $hung2; totals ${two.totals(0)}")
    }

    // the inputs from Falcon BMS's own data (2026-09-30): the field's elevation, the theater's stores, the conformal
    // tanks (D66), a save's burnt fuel
    run {
        val theaters = runBlocking { Repo.index().theaters }
        fun field(theater: String, name: String) = theaters.firstOrNull { it.id == theater }
            ?.let { t -> runBlocking { Repo.airportSet(t.airportSet) }.airports.firstOrNull { it.name == name } }?.elevationFt
        val got = listOf("korea-kto" to "Osan AB", "korea-kto" to "Samjiyon Airport", "hellas" to "Kasteli Airbase", "hellas" to "Larissa Airbase", "hellas" to "Ohrid Airport")
            .map { (t, n) -> n to field(t, n) }
        check("every field's elevation: the ATC file's, else BMS's height map under the runways", got.map { it.second } == listOf(42, 4450, 1180, 240, 2296),
            got.joinToString { "${it.first} ${it.second}" } + " (want 42 and 2,296 from the ATC files; 4,450, 1,180 and 240 from the height map, where the app had 0 or none)")
        val racks = runBlocking { PerformanceLoadout.loadRacks() }
        val kto = racks.storeFigures(listOf("korea-kto"))["an-aaq-13-navpod"]
        val hel = racks.storeFigures(listOf("hellas"))["an-aaq-13-navpod"]
        check("each store weighs and drags what the mission theater's own weapon table says", kto?.drag == 22.0 && hel?.drag == 32.0,
            "AN/AAQ-13 NAVPOD: Korea ${kto?.weightLbs} lb / ${kto?.drag}, Hellas ${hel?.weightLbs} lb / ${hel?.drag}")
        val cft = racks.cft("f-16c-52plus-cft", listOf("korea-kto", "falklands"))
        val p = osan()
        p.aircraftData = { PerformancePlan.AcData(20300, 7162, 52000, cft) }
        p.cboType.selectItem("F-16C-52+CFT"); p.programFlow()
        val on = "${p.intEmpty} / ${p.intIntFuel} / drag ${p.intDrag}"
        p.cftFromFuel(7162)
        val off = "${p.intEmpty}"
        check("D66 a CFT F-16 carries BMS's tanks: 1,700 lb, 3,060 lb, drag 20; a save's fuel of the jet's own tanks alone takes them off",
            cft == PerformancePlan.Cft(1700, 3060, 20) && on == "22000 / 10222 / drag 20" && off == "20300",
            "F-16C-52+CFT $on (WDP 21200 / 10219 / drag 0); fuel 7,162 lb → empty $off")
        val b = osan()
        b.fuelBurnt = 2800; b.departed = true; b.setFuel()
        check("a save's burnt fuel off the block fuel, and no take-off fuel once the flight is under way (WDP's SetFuel)",
            b.labels["lblBlockFuel_Val"] == (7162 + 5032 - 2800).toString() && b.labels["lblFuel_val"] == "----",
            "block ${b.labels["lblBlockFuel_Val"]}, take-off ${b.labels["lblFuel_val"]}")
    }

    // ---------------------------------------------------------------- the page on a briefing (the parser's fixture)
    appendLine()
    appendLine("the page on the briefing fixture (Hellas, Larissa, wind 44°/15 kt)")
    val savedIni = Repo.getString(PerformanceWiring.INI_KEY)
    try {
        val text = object {}.javaClass.getResourceAsStream("/bridge/sample_briefing.txt")?.bufferedReader()?.readText()
        if (text == null) check("fixture", false, "the sample briefing is not in the resources")
        else {
            val briefing = BriefingParser.parse(text)
            val theater = runBlocking { resolveTheater(Repo.index().theaters, "Hellas") }
            val mission = WdpMission.of(MissionData(briefing = briefing)).copy(theater = theater)
            Repo.putString(PerformanceWiring.INI_KEY, null)
            val w = PerformanceWiring()
            runBlocking { w.prepare(mission) }
            fun v(n: String) = w.values(emptyList()).values[n] ?: ""
            val dep = airbases(null, briefing)
            check("departure matched as the Briefing page matches it", v("lblAPT_VAL").isNotEmpty() && v("lblAPT_VAL") == (runBlocking { theater?.let { Repo.airportSet(it.airportSet) } }?.let { dep.departureIn(it)?.name } ?: "?"),
                "briefing \"${dep.departure}\" → page \"${v("lblAPT_VAL")}\" (${v("lblIcao")})")
            val rows = v("cboRWY.items").split('\n')
            check("the runway most nearly into the wind", v("cboRWY").isNotEmpty(), "wind ${v("txtWindDir")}/${v("txtWindSpd")} → RWY ${v("cboRWY")} of $rows")
            val load0 = v("lblLoadout_val"); val drag0 = v("lblDrag_Val")
            fun dialog() = WdpDialogs.stack.lastOrNull() as? WdpDialog
            fun dv(n: String) = dialog()?.wiring?.values(emptyList())?.values?.get(n) ?: ""
            // Cancel after Clear All: the page and the next Set keep the flight's stores
            w.onClick("btnLoadout"); val opened = dv("lblLoadW")
            check("the briefing's jet and stores reach the page", load0 == opened && load0 != "0",
                "\"${briefing.`package`.firstOrNull { it.primary }?.aircraft}\" → Type ${v("cboType")} (${v("lblPowerPlant_val")}), empty ${v("lblEmpty_Val")}; page load $load0 lb / drag $drag0, window $opened lb")
            dialog()?.wiring?.onClick("btnClear"); val cleared = dv("lblLoadW")
            dialog()?.wiring?.onClick("btnCancel")
            val closed1 = dialog() == null
            w.onClick("btnLoadout"); val reopened = dv("lblLoadW")
            check("Loadout Cancel discards the edits", closed1 && cleared == "0" && reopened == opened && v("lblLoadout_val") == load0,
                "opened $opened lb, Clear All $cleared, Cancel closed=$closed1, reopened $reopened lb, page $load0 → ${v("lblLoadout_val")}")
            // the window's × discards, as Cancel does (the app's Loadout window, PerfLoadoutWindow)
            dialog()?.wiring?.onClick("btnClear"); val cleared2 = dv("lblLoadW")
            WdpDialogs.closeTop()
            val closed2 = dialog() == null
            w.onClick("btnLoadout"); val reopened2 = dv("lblLoadW")
            check("Loadout × discards the edits", closed2 && cleared2 == "0" && reopened2 == opened && v("lblLoadout_val") == load0,
                "Clear All $cleared2, × closed=$closed2, reopened $reopened2 lb, page ${v("lblLoadout_val")}")
            // OK with a mission loaded asks WDP's question (fclsLoadout.CloseForm): No and Cancel keep the window open with
            // nothing handed over, Yes applies
            fun question() = WdpDialogs.stack.lastOrNull() as? WdpMessage
            fun answer(a: String) { question()?.let { m -> WdpDialogs.stack.remove(m); m.onAnswer?.invoke(a) } }
            dialog()?.wiring?.onClick("btnClear"); dialog()?.wiring?.onClick("btnOK")
            val q = question()
            check("Loadout OK with a mission asks WDP's question", q != null && q.title == "Question" && q.buttons == listOf("Yes", "No", "Cancel") &&
                q.text == "Loadout will not be changed for your mission, only on the datacard.\nDo you want to continue?",
                "box \"${q?.title}\": \"${q?.text?.replace('\n', ' ')}\" ${q?.buttons}")
            answer("No")
            val afterNo = dialog()?.form; val loadNo = v("lblLoadout_val"); val winNo = dv("lblLoadW")
            dialog()?.wiring?.onClick("btnOK"); answer("Cancel")
            val afterCancel = dialog()?.form; val loadCancel = v("lblLoadout_val")
            check("No and Cancel keep the window open, nothing handed to the page", afterNo == "fclsLoadout" && afterCancel == "fclsLoadout" &&
                loadNo == load0 && loadCancel == load0 && winNo == "0",
                "after No: window $afterNo (its load $winNo), page $loadNo; after Cancel: window $afterCancel, page $loadCancel (was $load0)")
            dialog()?.wiring?.onClick("btnOK"); answer("Yes")
            check("Yes applies and closes the window", dialog() == null && question() == null && v("lblLoadout_val") == "0" && v("lblDrag_Val") == "1",
                "page load $load0 → ${v("lblLoadout_val")}, drag $drag0 → ${v("lblDrag_Val")}")
            // and Set opens again on what OK kept
            w.onClick("btnLoadout"); val reopened3 = dv("lblLoadW")
            check("Set opens again on what OK kept", reopened3 == "0", "reopened $reopened3 lb")
            WdpDialogs.closeTop()
            appendLine("        (the flight's loadout with its pylons and racks, before Clear All: $load0 lb, drag $drag0)")
            while (WdpDialogs.stack.isNotEmpty()) WdpDialogs.stack.removeAt(WdpDialogs.stack.lastIndex)
        }
    } catch (e: Exception) {
        check("the page on a briefing", false, "threw ${e::class.simpleName}: ${e.message}")
    } finally {
        Repo.putString(PerformanceWiring.INI_KEY, savedIni)
    }

    appendLine()
    appendLine(if (failed) "FAIL" else "PASS")
    out.writeText(toString())
}
