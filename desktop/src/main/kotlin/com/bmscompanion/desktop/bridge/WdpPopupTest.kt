package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.wdp.AttackGeometry
import com.bmscompanion.app.data.wdp.PopupCoords
import kotlin.math.abs
import com.bmscompanion.app.data.wdp.PopupNet
import com.bmscompanion.app.data.wdp.PopupPlan
import com.bmscompanion.app.data.wdp.PopupX87
import java.io.File

/**
 * `--wdppagetest popup <popup.tsv> <out.txt>` — the ported Pop-up page against the real one.
 *
 * The reference is written by `tools/wdpref page Popup`: every row is a SEQUENCE of what a user and the rest of the
 * program do to the page ("ini=…;load;prec=1;src=Camp;camp=6,…;wp=7;getcoords;dive=25;click=pnlRefDown;…"),
 * applied to WDP's own `cntPopUp` on a form of its own, then every label, the visibility of every control it flips,
 * every slider's value and range, the waypoint box, the check boxes, the picture it loaded, the Campaign/TE
 * buttons, the nav offsets and DTC strings it hands on, its internal points and strings, what fclsMain would write
 * to Setup.ini, its print table, the message boxes it opened and — when the reference was made with the map capture
 * (`POPUP_HARMONY`) — every mark `Draw()` put on the map, in order, with the crop of the theater bitmap under them.
 * The port replays the same ops and every one of those has to come out the same, character for character (floats
 * and doubles as the same number). Beside it, `<ref>.geo.tsv` holds direct samples of the page's and
 * clsCoordinates' helpers, fclsMain's terrain lookup, VB's own string conversions and the x87 trigonometry.
 *
 * **Except where WDP was wrong and the port fixes it**: `wdp/expected-diffs/popup.txt` names each fix by its D-number
 * and the values it changes, on which rows; any other difference still fails. What the fixed values say instead is
 * checked by construction in [popupGeometry].
 */
internal fun wdpPopupTest(reference: File, out: File): String {
    if (reference.path == "geometry") return attackGeometryOnly(out)
    val text = buildString {
        appendLine("Weapon Delivery Planner Pop-up page — the port against the program")
        appendLine("reference: ${reference.path}")
        if (!reference.isFile) {
            appendLine()
            appendLine("FAIL — no reference. Run tools/wdpref page Popup from WDP's folder first.")
            return@buildString
        }
        val lines = reference.readLines().filter { it.isNotEmpty() }
        if (lines.size < 2) { appendLine("FAIL — empty reference"); return@buildString }
        val head = lines[0].split('\t')
        val idx = head.withIndex().associate { it.value to it.index }
        val compared = head.filter { it != "case" && it != "ops" }
        val heightmap = File(reference.path + ".heightmap.raw").takeIf { it.isFile }?.readBytes()
        val withMap = "map" in idx
        val allow = AttackAllowList.load("popup")
        appendLine("expected differences: ${allow.describe()}")

        var rows = 0
        var bad = 0
        val mismatched = LinkedHashMap<String, Int>()
        val examples = StringBuilder()
        var failedOps = 0
        for (line in lines.drop(1)) {
            val f = line.split('\t')
            if (f.size < head.size) continue
            fun col(name: String) = f[idx.getValue(name)]
            rows++
            val ops = col("ops").split(';')
            val p = PopupPlan()
            p.main.heightmap = heightmap
            p.main.mapWidth = 512; p.main.mapHeight = 512   // the harness's bitThrMap
            p.main.backColor = "ffe0e0e0"
            // whether the port's box ever went below 3, where WDP's stopped (D8)
            var below3 = false
            val got = try {
                for (op in ops) { apply(p, op); if (p.numWaypoint.toDouble() < 3.0) below3 = true }
                snapshot(p, withMap)
            } catch (e: Exception) {
                failedOps++
                if (examples.length < 6000) examples.appendLine("     row $rows: the replay itself failed: $e")
                emptyMap()
            }
            // what the expected-difference lines' conditions read: the ops, WDP's values and the port's
            val ctx = HashMap<String, String>()
            ctx["ops"] = col("ops"); ctx["wpbelow3"] = if (below3) "1" else "0"
            for (name in compared) { ctx["ref.$name"] = col(name); ctx["port.$name"] = got[name] ?: "" }
            var rowBad = false
            for (name in compared) {
                val want = col(name)
                val have = got[name] ?: "<missing>"
                if (!same(name, want, have) && allow.match(name, want, have, ctx) == null) {
                    rowBad = true
                    mismatched[name] = (mismatched[name] ?: 0) + 1
                    if (examples.length < 8000) examples.appendLine("     row $rows $name: port '${have.take(400)}' vs WDP '${want.take(400)}'   [${col("ops").take(300)}]")
                }
            }
            if (rowBad) bad++
        }

        appendLine("sequences replayed: $rows, values compared per sequence: ${compared.size} (${compared.count { it.startsWith("lbl") }} labels" +
            (if (withMap) ", and the map's marks" else "") + ")")
        if (!withMap) appendLine("note the reference was made without the map capture (POPUP_HARMONY): Draw's marks are not compared")
        if (bad == 0) {
            appendLine("ok   every value after every sequence is WDP's, or differs only as expected-diffs/popup.txt says")
        } else {
            appendLine("FAIL $bad of $rows sequences differ where no fix says they should" + if (failedOps > 0) " ($failedOps could not be replayed)" else "")
            appendLine("     by value: " + mismatched.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}×${it.value}" })
            append(examples)
        }
        append(allow.report())

        val geo = File(reference.path + ".geo.tsv")
        if (geo.isFile) {
            val probe = PopupPlan()
            var n = 0
            val geoBad = LinkedHashMap<String, Int>()
            val geoCount = LinkedHashMap<String, Int>()
            val geoEx = StringBuilder()
            // the x87 samples are not WDP's: they measure the model of .NET's trigonometry itself (see below)
            var x87n = 0
            var x87Bad = 0
            val x87Ex = StringBuilder()
            for (g in geo.readLines().drop(1)) {
                val c = g.split('\t')
                if (c.size < 7) continue
                n++
                geoCount[c[0]] = (geoCount[c[0]] ?: 0) + 1
                val (got, want) = geoSample(probe, c, heightmap) ?: continue
                if (c[0] == "x87") {
                    x87n++
                    if (got != want) { x87Bad++; if (x87Bad <= 5) x87Ex.appendLine("     ${c.take(4).joinToString(" | ")}: port '$got' vs .NET '$want'") }
                    continue
                }
                if (got != want) {
                    geoBad[c[0]] = (geoBad[c[0]] ?: 0) + 1
                    if ((geoBad[c[0]] ?: 0) <= 12) geoEx.appendLine("     ${c.take(6).joinToString(" | ")}: port '$got' vs WDP '$want'")
                }
            }
            appendLine("direct samples compared: ${n - x87n} (" + geoCount.entries.filter { it.key != "x87" }.joinToString(", ") { "${it.key} ${it.value}" } + ")")
            if (geoBad.isEmpty()) appendLine("ok   Bearing, Distance, NewPos, ScalePointToMap, FeetToRad/RadToFeet, the coordinate strings, the terrain and VB's conversions agree on every sample")
            else {
                appendLine("FAIL " + geoBad.entries.joinToString(", ") { "${it.key}×${it.value}" })
                append(geoEx)
                bad += geoBad.values.sum()
            }
            if (x87n > 0) {
                // Not part of the verdict, and said so: these are products and quotients of .NET's own Math.Sin/Cos/
                // Tan/Atan on random arguments, compiled as WDP is (optimised, x86), against PopupX87's model of the
                // x87 instructions — the true value rounded to 64 bits after a 66-bit-π reduction. The instructions
                // themselves are not correctly rounded (Intel documents an error of up to one unit in the 64th bit),
                // so a few products in 100,000 land one unit apart in the 53rd. WDP's own uses of these functions
                // are compared above, strictly: NewPos_N/E and Bearing directly, the rest through every label.
                appendLine("info x87 model: ${x87n - x87Bad} of $x87n samples (8 values each) agree to the bit" +
                    if (x87Bad > 0) "; $x87Bad differ by one unit in the last place of one value (the x87's own rounding, not modelled)" else "")
                append(x87Ex)
            }
        } else {
            appendLine("FAIL — no direct samples beside the reference (${geo.name})")
            bad++
        }
        appendLine()
        val (geoText, geoBad) = popupGeometry()
        append(geoText)
        appendLine()
        appendLine(
            if (bad == 0 && geoBad == 0) "PASS — the Pop-up page answers as Weapon Delivery Planner does, except where it fixes WDP (expected-diffs/popup.txt), and its figures land where it draws them"
            else "FAIL — the Pop-up port and Weapon Delivery Planner disagree beyond the listed fixes, or a figure does not land where the page draws it",
        )
    }
    runCatching { out.writeText(text) }
    return text
}

/**
 * The Pop-up page by construction: every DED line, VRP and VIP, both profiles, laid out again from its steerpoint
 * lands where the page draws the point (D2 among them: Type 2's VIP OA2); the guide's case reads 020.0°; the heights
 * stand on the ground under the target when the cartridge gives it (D7) and typed ELEVs win; the aim-off OA2 lies
 * beyond the target on the attack heading, as the dive geometry puts it; steerpoint 1 can be the target (D8).
 *
 * The range tolerance is 2 ft here, not 1: Pop-up measures its VIP lines, as WDP does and the comparison pins, on
 * positions printed to seven significant figures (a foot at a theater's scale), and draws its points as floats.
 */
internal fun popupGeometry(): Pair<String, Int> {
    val g = GeoChecks()
    val t = GuideCase.target
    val ip = GuideCase.ip
    fun plan(type1: Boolean, vip: Boolean, oa2: Boolean, hdg: Int, turnRight: Boolean, wp: Int = 15, ground: Boolean = true): PopupPlan {
        val p = PopupPlan()
        val m = p.main
        m.precision = true
        p.strDTC = "Camp"
        m.tblCampSTPT[wp - 1].apply { falconY = t.first.toFloat(); falconX = t.second.toFloat(); falconZ = t.third.toFloat() }
        if (wp >= 2) m.tblCampSTPT[wp - 2].apply { falconY = ip.first.toFloat(); falconX = ip.second.toFloat(); falconZ = 300f }
        if (ground) m.groundElevation = { _, _, _ -> t.third }
        p.load(null)
        p.guard { p.campTE() }
        p.setWaypoint(wp)
        p.chooseProfile(type1)
        p.setOA2(oa2)
        p.trbTurn.slide(if (turnRight) 1 else 0); p.guard { p.changeTurn() }
        p.guard { p.trbHeading.set(hdg); p.changeHeading() }
        p.chooseRef(vip)
        return p
    }
    var cases = 0
    for (vip in listOf(false, true)) for (type1 in listOf(true, false)) for (oa2 in listOf(false, true)) for (right in listOf(false, true)) for (hdg in listOf(0, 20, 135, 200, 359)) {
        val p = plan(type1, vip, oa2, hdg, right)
        val plot = p.attackPlot()
        val tag = "${if (vip) "VIP" else "VRP"} type ${if (type1) 1 else 2} oa2 ${if (oa2) "aim-off" else "=OA1"} ${if (right) "Right" else "Left"} hdg $hdg"
        val from = if (vip) plot?.ip else plot?.target
        val sub = GeoChecks()
        fun lands(n: String, lbl: String, at: Pair<Double, Double>?) = sub.lands("$tag $n", from, p.labels["lbl${lbl}brg"], p.labels["lbl${lbl}rng"], at, maxFt = 2.0)
        lands(if (vip) "VIP-TO-TGT" else "TGT-TO-VRP", "VIP", if (vip) plot?.target else plot?.start)
        lands("PUP", "PUP", plot?.pup)
        lands("OA1", "OA1", plot?.oa1)
        lands("OA2", "OA2", plot?.oa2 ?: plot?.oa1)
        val nav = p.navOffsets
        val keys = if (vip) AttackGeometry.VIP_KEYS else AttackGeometry.VRP_KEYS
        for ((k, lbl) in keys.zip(listOf("VIP", "PUP", "OA1", "OA2"))) {
            val o = nav[k]
            val same = o != null && o.bearing == (p.labels["lbl${lbl}brg"]?.toFloatOrNull() ?: -1f) && o.range.toString() == p.labels["lbl${lbl}rng"] && o.elv.toString() == p.labels["lbl${lbl}elv"]
            sub.check("$tag $k is the DED line", same, "saved ${o?.bearing},${o?.range},${o?.elv}")
        }
        cases++
        if (sub.bad > 0) { g.text.append(sub.text.lines().filter { it.startsWith("FAIL") }.joinToString("\n")).append('\n'); g.bad += sub.bad }
    }
    g.check("every DED line of $cases plans lands on the point the page draws (0.1°, 2 ft)", true, "failures, if any, are listed above")

    run {
        val p = plan(true, vip = true, oa2 = false, hdg = 20, turnRight = true)
        val want = GuideCase.ipToTarget
        g.check("guide case: IP 8 nm on 020° → VIP-TO-TGT 20.0°", p.labels["lblVIPbrg"] == "20.0",
            "VIP-TO-TGT ${p.labels["lblVIPbrg"]}° ${p.labels["lblVIPrng"]} ft (true %.2f° %.1f ft)".format(want.bearingDeg, want.rangeFt))
    }
    // the aim-off point: beyond the target on the attack heading, release height ÷ tan(dive) − bomb range from it
    run {
        val p = plan(true, vip = false, oa2 = true, hdg = 20, turnRight = true)
        val plot = p.attackPlot()
        val m = if (plot?.oa2 != null) AttackGeometry.bearingRange(plot.target.first, plot.target.second, plot.oa2!!.first, plot.oa2!!.second) else null
        g.check("the aim-off OA2 lies beyond the target, on the attack heading", m != null && abs(m.bearingDeg - 20.0) < 0.05 && p.labels["lblOA2brg"] == "20.0" && p.labels["lblOA2rng"] == p.labels["lblAODval"],
            "OA2 ${p.labels["lblOA2brg"]}° ${p.labels["lblOA2rng"]} ft, aim-off distance ${p.labels["lblAODval"]} ft")
    }
    // D7: the ground under the target, through WDP's own terrain path; typed figures win until the target changes
    run {
        val p = plan(true, vip = false, oa2 = true, hdg = 0, turnRight = true)
        val q = plan(true, vip = false, oa2 = true, hdg = 0, turnRight = true, ground = false)
        val gnd = Math.round(t.third).toInt()
        val a = p.labels["lblVIPelv"]?.toIntOrNull() ?: 0
        val b = q.labels["lblVIPelv"]?.toIntOrNull() ?: 0
        g.check("D7 the VRP ELEV stands on the target's ground", a - b == gnd, "VRP $a with the ground, $b without (ground $gnd)")
        g.check("D7 OA1 ELEV is the pull-down altitude above sea level", p.labels["lblOA1elv"] == p.labels["lblPulldownAltVal"] &&
            (p.labels["lblPulldownAltVal"]?.toIntOrNull() ?: 0) - (q.labels["lblPulldownAltVal"]?.toIntOrNull() ?: 0) == gnd,
            "OA1 ${p.labels["lblOA1elv"]}, pull-down ${p.labels["lblPulldownAltVal"]} (without the ground ${q.labels["lblPulldownAltVal"]})")
        p.elev.set("OA2_2", 0); p.guard { p.programFlow(true) }
        g.check("D7 a typed OA2 ELEV of 0 is shown and saved", p.labels["lblOA2elv"] == "0" && p.navOffsets["OA2_2"]?.elv == 0, "DED ${p.labels["lblOA2elv"]}, saved ${p.navOffsets["OA2_2"]?.elv}")
    }
    // D64: profile Type 2's run-in is laid out from the height above the target, so the ground under it moves only the ELEVs
    run {
        val p = plan(false, vip = false, oa2 = false, hdg = 20, turnRight = true)
        val q = plan(false, vip = false, oa2 = false, hdg = 20, turnRight = true, ground = false)
        val same = listOf("lblVIPbrg", "lblVIPrng", "lblPUPbrg", "lblPUPrng").all { p.labels[it] == q.labels[it] }
        g.check("D64 Type 2's VRP and pull-up point do not move with the ground under the target", same,
            "VRP ${p.labels["lblVIPbrg"]}° ${p.labels["lblVIPrng"]} ft, PUP ${p.labels["lblPUPbrg"]}° ${p.labels["lblPUPrng"]} ft " +
                "(without the ground ${q.labels["lblVIPbrg"]}° ${q.labels["lblVIPrng"]} ft, ${q.labels["lblPUPbrg"]}° ${q.labels["lblPUPrng"]} ft)")
    }
    // D8
    run {
        val p = plan(true, vip = false, oa2 = false, hdg = 0, turnRight = true, wp = 1)
        g.check("D8 steerpoint 1 can be the target (no IP before it)", p.numWaypoint.toInt32() == 1 && p.blnGotTGT && !p.blnDoVIP && p.labels["lblVIPwp"] == "1",
            "box ${p.numWaypoint}, target ${p.blnGotTGT}, IP ${p.blnDoVIP}, DED steerpoint ${p.labels["lblVIPwp"]}")
    }
    // D87: an IP STPT picked other than the one before the target — the VIP lines are measured from it, name it, and
    // laid out again from it land on the target; back to WDP's rule; an IP with no position is no VIP
    for (type1 in listOf(true, false)) run {
        val other = Triple(t.first - 30000.0, t.second - 45000.0, 0.0)
        val p = plan(type1, vip = true, oa2 = false, hdg = 20, turnRight = true)
        p.main.tblCampSTPT[10].apply { falconY = other.first.toFloat(); falconX = other.second.toFloat(); falconZ = 500f }
        p.setIpStpt(11)
        p.chooseRef(true)
        val plot = p.attackPlot()
        val want = AttackGeometry.bearingRange(other.first, other.second, t.first, t.second)
        val sub = GeoChecks()
        val tag = "D87 type ${if (type1) 1 else 2} IP STPT 11"
        sub.lands("$tag VIP-TO-TGT", plot?.ip, p.labels["lblVIPbrg"], p.labels["lblVIPrng"], plot?.target, maxFt = 2.0)
        sub.lands("$tag VIP-TO-PUP", plot?.ip, p.labels["lblPUPbrg"], p.labels["lblPUPrng"], plot?.pup, maxFt = 2.0)
        sub.lands("$tag OA1", plot?.ip, p.labels["lblOA1brg"], p.labels["lblOA1rng"], plot?.oa1, maxFt = 2.0)
        val atIp = plot?.ip?.let { abs(it.first - other.first) <= 1.0 && abs(it.second - other.second) <= 1.0 } == true
        g.check("$tag: the IP is STPT 11 and every VIP line lands from it", sub.bad == 0 && p.blnDoVIP && atIp &&
            abs((p.labels["lblVIPbrg"]?.toDoubleOrNull() ?: -1.0) - want.bearingDeg) <= 0.1,
            "IP ${plot?.ip}, VIP-TO-TGT ${p.labels["lblVIPbrg"]}° ${p.labels["lblVIPrng"]} ft (true %.2f° %.1f ft)".format(want.bearingDeg, want.rangeFt) +
                sub.text.lines().filter { it.startsWith("FAIL") }.joinToString(" "))
        val nav = p.navOffsets
        g.check("$tag: the DED and the saved VIP lines name STPT 11, the VRP lines the target", p.labels["lblVIPwp"] == "11" &&
            nav["VIP"]?.stpt == 11 && nav["VIPPUP"]?.stpt == 11 && nav["OA1_1"]?.stpt == 11 && nav["OA2_1"]?.stpt == 11 && nav["VRP"]?.stpt == 15,
            "DED ${p.labels["lblVIPwp"]}, VIP ${nav["VIP"]?.stpt}, VIPPUP ${nav["VIPPUP"]?.stpt}, OA1_1 ${nav["OA1_1"]?.stpt}, VRP ${nav["VRP"]?.stpt}")
        p.setIpStpt(null)
        val back = p.attackPlot()?.ip?.let { abs(it.first - ip.first) <= 1.0 && abs(it.second - ip.second) <= 1.0 } == true
        g.check("D87 type ${if (type1) 1 else 2} back to WDP's rule: the IP is STPT 14 again", back && p.labels["lblVIPwp"] == "14" && p.navOffsets["VIP"]?.stpt == 14,
            "IP ${p.attackPlot()?.ip}, DED ${p.labels["lblVIPwp"]}, VIP ${p.navOffsets["VIP"]?.stpt}")
        p.setIpStpt(13)
        g.check("D87 type ${if (type1) 1 else 2} an IP STPT with no position: no VIP, the reference on VRP", p.blnGotTGT && !p.blnDoVIP && p.navModesel == 2,
            "target ${p.blnGotTGT}, IP ${p.blnDoVIP}, reference ${p.navModesel}")
    }
    return ("Pop-up geometry, by construction:\n" + g.text) to g.bad
}

/** The coordinate presets of the harness (`Popup.Coords`), the same figures. */
private fun coordPreset(name: String): PopupCoords.CoordData = when (name) {
    "old1" -> PopupCoords.CoordData(34.0, 124.0, 3358699.5, 3358699.5, false)
    "old2" -> PopupCoords.CoordData(-53.5, -62.0, 2000000.0, 2500000.0, false)
    "new1" -> PopupCoords.CoordData(0.0, 0.0, 3358699.5, 3358699.5, true, PopupCoords.TransverseMercatorMeta(127.5, -512000.0, 3700000.0, 1024000L, 16384f, 62.5f, 1f / (62.5f * 3.27998f), 62.5f * 3.27998f, 8192f))
    "new2" -> PopupCoords.CoordData(0.0, 0.0, 3358699.5, 3358699.5, true, PopupCoords.TransverseMercatorMeta(22.0, -400000.0, 4300000.0, 1024000L, 1024f, 1000f, 1f / 3279.98f, 3279.98f, 512f))
    "new3" -> PopupCoords.CoordData(0.0, 0.0, 3358699.5, 3358699.5, true, PopupCoords.TransverseMercatorMeta(-60.0, -500000.0, -6100000.0, 1024000L, 4096f, 250f, 1f / (250f * 3.27998f), 250f * 3.27998f, 2048f))
    else -> PopupCoords.CoordData()
}

/** One op of the harness's sequence, on the port. */
private fun apply(p: PopupPlan, op: String) {
    val eq = op.indexOf('=')
    val k = if (eq < 0) op else op.substring(0, eq)
    val v = if (eq < 0) "" else op.substring(eq + 1)
    fun slide(b: PopupPlan.Bar, change: () -> Unit) { b.slide(v.toInt()); p.guard(change) }
    fun ints() = v.split(',').map { it.trim().toInt() }
    val m = p.main
    when (k) {
        "ver" -> p.setVersion(v == "1")
        "mainloaded" -> m.blnLoaded = v == "1"
        // Setup.ini as the harness writes it: one line per pair, in order (a key twice is two lines), or none at all
        "ini" -> p.setupIni = if (v.isEmpty()) emptyList() else v.split('|').map { kv -> val i = kv.indexOf(':'); kv.substring(0, i) to kv.substring(i + 1) }
        "noini" -> p.setupIni = null
        "mdtc" -> m.missionDtcLoaded = v == "1"
        "load" -> p.show()
        "ingr" -> slide(p.trbIngressHeight, p::changeIngressHeight)
        "dive" -> slide(p.trbDiveAngle, p::changeDiveAngle)
        "speed" -> slide(p.trbSpeed, p::changeSpeed)
        "rel" -> slide(p.trbReleaseHeight, p::changeReleaseHeight)
        "track" -> slide(p.trbTrackingTime, p::changeTrackingTime)
        "g" -> slide(p.trbG, p::changeG)
        "turn" -> slide(p.trbTurn, p::changeTurn)
        "hdg" -> slide(p.trbHeading, p::changeHeading)
        "vrp" -> slide(p.trbVrpToPup, p::changeVrpToPup)
        "zoom" -> slide(p.trbZoom, p::zoomLevel)
        "wp" -> p.setWaypoint(v.toInt())
        "wpdec" -> p.setWaypointDecimal(PopupNet.toDecimal(v))
        "wpup" -> p.waypointUp()
        "wpdown" -> p.waypointDown()
        "wptext" -> p.typeWaypoint(v)
        "oa2" -> p.setOA2(v == "1")
        "click" -> when (v) {
            "lblN" -> p.guard { p.trbHeading.set(0); p.changeHeading() }
            "lblE" -> p.guard { p.trbHeading.set(90); p.changeHeading() }
            "lblS" -> p.guard { p.trbHeading.set(180); p.changeHeading() }
            "lblW" -> p.guard { p.trbHeading.set(270); p.changeHeading() }
            "lblN360" -> p.guard { p.trbHeading.set(360); p.changeHeading() }
            "pnlRefUp", "pnlDED_Ref_Up" -> p.chooseRef(false)
            "pnlRefDown", "pnlDED_Ref_Down" -> p.chooseRef(true)
            "pnlProfile_Up", "pnlProfile2_Up" -> p.chooseProfile(false)
            "pnlProfile_Down", "pnlProfile2_Down" -> p.chooseProfile(true)
            "pnlBomb_Up" -> p.chooseBomb(false)
            "pnlBomb_Down" -> p.chooseBomb(true)
            "btnCamp" -> p.chooseCampTE(true)
            "btnTE" -> p.chooseCampTE(false)
            "chbShowPPT" -> p.toggleShowPPT()
            "chbShowPPTNr" -> p.toggleShowPPTNr()
            "btnSaveDTC" -> p.guard { p.saveDtc() }
            else -> error("unknown click $v")
        }
        "sel" -> {
            val (panel, button) = v.split(':')
            val which = when (panel) { "pnlSelections_Up" -> "Selections"; "pnlSelections_Middle" -> "Profile"; else -> "DED Data" }
            p.chooseSelection(which, button != "R")
        }
        "vis" -> p.setVisible(v == "1")
        "prec" -> m.precision = v == "1"
        "src" -> p.strDTC = if (v == "null") null else v
        "campte" -> p.blnCampTE = v == "1"
        "camp", "te" -> {
            val parts = v.split(',')
            val t = (if (k == "camp") m.tblCampSTPT else m.tblMissionSTPT)[parts[0].toInt()]
            t.falconY = parts[1].toFloat(); t.falconX = parts[2].toFloat(); t.falconZ = parts[3].toFloat()
        }
        "flight" -> {
            val (nr, sel, count) = ints()
            m.flightNR = nr; m.selFlightNr = sel
            m.flightTable = Array(maxOf(sel + 1, 1)) { Array(count) { PopupPlan.GridWp() } }
        }
        "flightnr" -> m.flightNR = v.toInt()
        "fwp" -> {
            val (i, gx, gy, gz) = ints()
            val w = m.flightTable!![m.selFlightNr][i]
            w.gridX = gx.toShort(); w.gridY = gy.toShort(); w.gridZ = gz.toShort()
        }
        "terrain" -> { m.terrainLoaded = v != "0"; m.newTerrainElvLoaded = v == "1" }
        "coord" -> {
            val c = coordPreset(v)
            m.originLat = c.originLat; m.originLong = c.originLong; m.campW = c.campW; m.campH = c.campH
            m.enableNewTerrain = c.enableNewTerrain; m.tm = c.tm
            p.setCoordData()
        }
        "getcoords" -> p.guard { p.getCoords() }
        "camptecall" -> p.guard { p.campTE() }
        "stpt" -> p.guard { p.stptChange() }
        "flow" -> p.guard { p.programFlow(v == "1") }
        "draw" -> p.guard { p.draw(v == "1") }
        else -> error("unknown op $op")
    }
}

/** The port's values under the harness's column names. */
private fun snapshot(p: PopupPlan, withMap: Boolean): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    for ((k, v) in p.labels) m[k] = v
    m["lblTargetHUDval.back"] = p.hudBack
    m["lblVIP.fore"] = p.vipLegendFore
    for ((k, v) in p.shown) m["vis_$k"] = if (v) "shown" else "hidden"
    for ((n, b) in listOf("trbIngressHeight" to p.trbIngressHeight, "trbDiveAngle" to p.trbDiveAngle, "trbSpeed" to p.trbSpeed,
        "trbReleaseHeight" to p.trbReleaseHeight, "trbTrackingTime" to p.trbTrackingTime, "trbG" to p.trbG, "trbTurn" to p.trbTurn,
        "trbHeading" to p.trbHeading, "trbVrpToPup" to p.trbVrpToPup, "trbZoom" to p.trbZoom)) {
        m["sld_$n"] = b.value.toString(); m["sld_$n.min"] = b.min.toString(); m["sld_$n.max"] = b.max.toString()
    }
    m["numWaypoint"] = p.numWaypoint.toString()
    m["numWaypoint.text"] = p.numWaypointText
    m["chbOA2"] = p.chbOA2State.toString()
    m["chbShowPPT"] = if (p.chbShowPPT) "1" else "0"
    m["chbShowPPTNr"] = if (p.chbShowPPTNr) "1" else "0"
    m["picProfile"] = p.profilePicture
    m["btnCamp.enabled"] = if (p.btnCampEnabled) "1" else "0"; m["btnCamp.back"] = p.btnCampBack; m["btnCamp.disposed"] = if (p.buttonsDisposed) "1" else "0"
    m["btnTE.enabled"] = if (p.btnTEEnabled) "1" else "0"; m["btnTE.back"] = p.btnTEBack; m["btnTE.disposed"] = if (p.buttonsDisposed) "1" else "0"
    m["btnSaveDTC.text"] = p.btnSaveDTCText; m["btnSaveDTC.size"] = "${p.btnSaveDTCSize.first}x${p.btnSaveDTCSize.second}"
    m["nav_Modesel"] = p.navModesel.toString()
    for ((k, o) in p.navOffsets) {
        m["nav_${k}_Stpt"] = o.stpt.toString(); m["nav_${k}_Bearing"] = o.bearing.toString()
        m["nav_${k}_Range"] = o.range.toString(); m["nav_${k}_Elv"] = o.elv.toString()
    }
    for (s in listOf("strIngrHgt", "strIngrSpd", "strRelHgt", "strRelSpd", "strAttHed", "strDA", "strPullingG", "strTurn", "strTGTHUD")) m["dtc_$s"] = p.dtcStrings[s] ?: ""
    for ((k, v) in p.state()) m["st_$k"] = when (v) {
        null -> "<null>"
        is Boolean -> if (v) "1" else "0"
        else -> v.toString()
    }
    for ((k, v) in p.iniValues()) m["ini_$k"] = v
    val before = p.errors
    p.guard { p.makePrintDocument() }
    for (r in 1..26) for (c in 0..1) m["prt_${r}_$c"] = p.print[r][c] ?: "<null>"
    m["errors"] = before.toString() + if (p.errors != before) "+print" else ""
    m["msgbox"] = p.messageBoxes.toString()
    if (withMap) { m["map"] = mapText(p.mapPicture); m["save"] = p.dtcCalls.joinToString(";") }
    return m
}

/** The port's map picture in the harness's notation: the picture's size, the crop, then each mark as it was drawn. */
private fun mapText(pic: PopupPlan.MapPicture?): String {
    if (pic == null) return ""
    val sb = StringBuilder("size:${pic.size}x${pic.size};crop:${pic.crop.joinToString(",")}")
    for (it in pic.items) {
        sb.append(';')
        sb.append(when (it) {
            is PopupPlan.MapItem.Line -> "L:${it.x1},${it.y1},${it.x2},${it.y2},${it.color},${it.dash}"
            is PopupPlan.MapItem.Rect -> "R:${it.x},${it.y},${it.w},${it.h},${it.color},Solid"
            is PopupPlan.MapItem.Ellipse -> "E:${it.x},${it.y},${it.w},${it.h},${it.color},Solid"
            is PopupPlan.MapItem.Pie -> "P:${it.x},${it.y},${it.w},${it.h},${it.start},${it.sweep},${it.color},Solid"
            is PopupPlan.MapItem.Polygon -> "G:" + it.points.joinToString(";") { (x, y) -> "$x,$y" } + ",${it.color},Solid"
            is PopupPlan.MapItem.Text -> "T:" + it.text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace(";", "\\;") +
                "@${it.x},${it.y},${it.color},${it.font},${it.size}"
        })
    }
    return sb.toString()
}

/** Equal as text, or — for the floats and doubles the harness prints round-trip — as the same number. */
private fun same(name: String, want: String, have: String): Boolean {
    if (want == have) return true
    // the harness prints floats and doubles round-trip ("R"): compare them as the same number of the same type
    val isFloat = name.startsWith("st_") && (name.endsWith(".X") || name.endsWith(".Y") || name.endsWith(".Z")) || (name.startsWith("nav_") && name.endsWith("_Bearing"))
    val isDouble = name.startsWith("st_") && name.endsWith("]")
    if (isFloat) {
        val a = want.toFloatOrNull() ?: return false
        val b = have.toFloatOrNull() ?: return false
        return a == b || (a.isNaN() && b.isNaN())
    }
    if (isDouble) {
        val a = want.toDoubleOrNull() ?: return false
        val b = have.toDoubleOrNull() ?: return false
        return a == b || (a.isNaN() && b.isNaN())
    }
    return false
}

private fun errName(e: Throwable): String = when (e) {
    is PopupPlan.PopupOverflow -> "ERR:OverflowException"
    is PopupNet.PopupCast -> "ERR:InvalidCastException"
    is PopupNet.PopupArgument -> "ERR:ArgumentException"
    is PopupNet.PopupFormat -> "ERR:FormatException"
    else -> "ERR:" + e::class.simpleName
}

private fun tryStr(f: () -> String): String = try { f() } catch (e: Exception) { errName(e) }

private fun hexD(s: String): Double = Double.fromBits(java.lang.Long.parseUnsignedLong(s, 16))
private fun hex(d: Double): String = String.format("%016X", d.toRawBits())
private fun hexF(f: Float): String = String.format("%08X", f.toRawBits())

/** One direct sample: (the port's answer, the program's), or null for a line it does not know. */
private fun geoSample(probe: PopupPlan, c: List<String>, heightmap: ByteArray?): Pair<String, String>? {
    val want = c[6]
    fun num(s: String) = s.toDouble()
    return when (c[0]) {
        "bearing" -> tryStr { probe.bearing(c[1], c[2], c[3], c[4]).toString() } to (want.toFloatOrNull()?.toString() ?: want)
        "distance" -> tryStr { probe.distance(c[1], c[2], c[3], c[4]).toString() } to want
        "newpos" -> {
            val bn = c[1].toFloat(); val be = c[2].toFloat(); val brg = c[3].toFloat(); val d = c[4].toInt()
            tryStr { "${probe.newPosN(bn, be, brg, d)},${probe.newPosE(bn, be, brg, d)}" } to
                (if (want.startsWith("ERR")) want else want.split(',').joinToString(",") { it.toFloat().toString() })
        }
        "scale" -> tryStr { probe.scalePointToMap(num(c[1]), num(c[2]), num(c[3]), num(c[4]), num(c[5])).let { "${it.first},${it.second}" } } to want
        "feettorad" -> tryStr { probe.feetToRad(c[1].toInt()).toString() } to (want.toDoubleOrNull()?.toString() ?: want)
        "radtofeet" -> tryStr { probe.radToFeet(num(c[1])).toString() } to want
        "coords" -> tryStr { PopupCoords.feetToCoordsBoth(coordPreset(c[1]), num(c[2]), num(c[3])) } to want
        "terrain" -> tryStr { PopupCoords.readNewTerrainElvLoc(heightmap, num(c[1]), num(c[1]), c[2].toFloat(), c[3].toFloat()).toString() } to want
        "x87" -> {
            val x = hexD(c[1]); val d = hexD(c[2]); val q = hexD(c[3])
            val got = listOf(
                hex(PopupX87.mul(PopupX87.sin(x), d)), hex(PopupX87.mul(PopupX87.cos(x), d)), hex(PopupX87.mul(PopupX87.tan(x), d)),
                hex(PopupX87.mul(PopupX87.atan(q), d)), hex(PopupX87.div(d, PopupX87.tan(x))), hex(PopupX87.sub(PopupX87.atan(q), x)),
                hexF(PopupX87.atan(q).toFloat()), hex(PopupX87.sin(x).toDouble()),
            ).joinToString(",")
            got to want
        }
        "vb" -> {
            // the harness writes a no-break space as the six characters   and a tab as \t
            val s = c[1].removePrefix("[").removeSuffix("]").replace("\\u00a0", " ").replace("\\t", "\t")
            val isn = tryStr { if (PopupNet.isNumeric(s)) "1" else "0" }
            val dbl = tryStr { PopupNet.toDouble(s).toString() }
            val int = tryStr { PopupNet.toInteger(s).toString() }
            val bool = tryStr { if (PopupNet.toBoolean(s)) "True" else "False" }
            val dec = tryStr { PopupNet.toDecimal(s).toString() }
            val sgl = tryStr { PopupNet.toSingle(s).toString() }
            fun dnum(x: String) = x.toDoubleOrNull()?.toString() ?: x
            val wantDec = c[6].substringBefore('|')
            val wantSgl = c[6].substringAfter('|')
            fun fnum(x: String) = x.toFloatOrNull()?.toString() ?: x
            "$isn\t${dnum(dbl)}\t$int\t$bool\t$dec|${fnum(sgl)}" to "${c[2]}\t${dnum(c[3])}\t${c[4]}\t${c[5]}\t$wantDec|${fnum(wantSgl)}"
        }
        else -> null
    }
}
