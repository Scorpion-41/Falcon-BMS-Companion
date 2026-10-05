package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.wdp.AttackGeometry
import com.bmscompanion.app.data.wdp.HadbPlan
import kotlin.math.abs
import com.bmscompanion.app.data.wdp.PopupCoords
import java.io.File

/**
 * `--wdppagetest hadb <hadb.tsv> <out.txt>` — the ported High Altitude Dive Bomb page against the real one.
 *
 * The reference is written by `tools/wdpref page Hadb`: every row is a SEQUENCE of what a user and the rest of the
 * program do to the page ("ini=;load;click=pnlBomb_Down;dive=25;spd=45;…;prec=1;src=Camp;camp=6,…;camptecall;wp=7"),
 * applied to WDP's own `cntHADB` on a form of its own, then every label it writes, the visibility of every control it
 * flips, every slider's value and range, the waypoint box, the picture it loaded, the PPT-number and Campaign/TE
 * buttons, the nav offsets it hands to the DTC page, its internal figures and strings, what fclsMain would write to
 * Setup.ini and how many handlers ended on an exception. The port replays the same ops and every one of those has to
 * come out the same, character for character (the nav bearings, floats, as the same number).
 *
 * **Except where WDP was wrong and the port fixes it**: `wdp/expected-diffs/hadb.txt` names each fix by its D-number
 * and the values it changes, on which rows; any other difference still fails. What the fixed values say instead is
 * checked by construction in [hadbGeometry].
 */
internal fun wdpHadbTest(reference: File, out: File): String {
    if (reference.path == "geometry") return attackGeometryOnly(out)
    val text = buildString {
        appendLine("Weapon Delivery Planner HADB page — the port against the program")
        appendLine("reference: ${reference.path}")
        if (!reference.isFile) {
            appendLine()
            appendLine("FAIL — no reference. Run tools/wdpref page Hadb from WDP's folder first.")
            return@buildString
        }
        val lines = reference.readLines().filter { it.isNotEmpty() }
        if (lines.size < 2) { appendLine("FAIL — empty reference"); return@buildString }
        val head = lines[0].split('\t')
        val idx = head.withIndex().associate { it.value to it.index }
        val compared = head.filter { it != "case" && it != "ops" }
        val allow = AttackAllowList.load("hadb")
        appendLine("expected differences: ${allow.describe()}")

        var rows = 0
        var bad = 0
        var failedOps = 0
        val mismatched = LinkedHashMap<String, Int>()
        val examples = StringBuilder()
        for (line in lines.drop(1)) {
            val f = line.split('\t')
            if (f.size < head.size) continue
            fun col(name: String) = f[idx.getValue(name)]
            rows++
            val ops = col("ops").split(';')
            val p = HadbPlan()
            // the lowest steerpoint the box reached: WDP's stopped at 3, the port's at 1 (D8)
            var wpMin = p.numWaypoint
            val got = try {
                for (op in ops) { apply(p, op); wpMin = minOf(wpMin, p.numWaypoint) }
                snapshot(p)
            } catch (e: Exception) {
                failedOps++
                if (examples.length < 6000) examples.appendLine("     row $rows: the replay itself failed: $e")
                emptyMap()
            }
            // what the expected-difference lines' conditions read: the ops, WDP's values and the port's
            val ctx = HashMap<String, String>()
            ctx["ops"] = col("ops"); ctx["wpmin"] = wpMin.toString()
            for (name in compared) { ctx["ref.$name"] = col(name); ctx["port.$name"] = got[name] ?: "" }
            var rowBad = false
            for (name in compared) {
                val want = col(name)
                val have = got[name] ?: "<missing>"
                if (!same(name, want, have) && allow.match(name, want, have, ctx) == null) {
                    rowBad = true
                    mismatched[name] = (mismatched[name] ?: 0) + 1
                    if (examples.length < 8000) examples.appendLine("     row $rows $name: port '${have.take(200)}' vs WDP '${want.take(200)}'   [${col("ops").take(400)}]")
                }
            }
            if (rowBad) bad++
        }

        appendLine("sequences replayed: $rows, values compared per sequence: ${compared.size} (${compared.count { it.startsWith("lbl") }} labels)")
        if (rows < 500) { appendLine("FAIL only $rows sequences in the reference (at least 500 expected)"); bad++ }
        if (bad == 0) {
            appendLine("ok   every value after every sequence is WDP's, or differs only as expected-diffs/hadb.txt says")
        } else {
            appendLine("FAIL $bad of $rows sequences differ where no fix says they should" + if (failedOps > 0) " ($failedOps could not be replayed)" else "")
            appendLine("     by value: " + mismatched.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}×${it.value}" })
            append(examples)
        }
        append(allow.report())
        appendLine()
        val (geo, geoBad) = hadbGeometry()
        append(geo)
        appendLine()
        appendLine(
            if (bad == 0 && geoBad == 0) "PASS — the HADB page answers as Weapon Delivery Planner does, except where it fixes WDP (expected-diffs/hadb.txt), and its figures land where it draws them"
            else "FAIL — the HADB port and Weapon Delivery Planner disagree beyond the listed fixes, or a figure does not land where the page draws it",
        )
    }
    runCatching { out.writeText(text) }
    return text
}

/**
 * The HADB page by construction: its four DED lines laid out again from the target land where the page draws the
 * points; the pull-up point's bearing is off the attack bearing by atan(R/D)·sin(angle off) (D4); a second flow
 * changes nothing, whatever moved last (D5, the ingress TAS); N at the right-hand end prints 0.0 (D5); the elevations
 * stand on the ground (D7); steerpoint 1 can be the target (D8); threat rings are where the threat is (D10).
 */
internal fun hadbGeometry(): Pair<String, Int> {
    val g = GeoChecks()
    val t = GuideCase.target
    fun plan(wp: Int = 15, ground: Boolean = true, ip: Boolean = true): HadbPlan {
        val p = HadbPlan()
        val m = p.main
        m.precision = true
        p.strDTC = "Camp"
        m.tblCampSTPT[wp - 1].apply { falconY = t.first.toFloat(); falconX = t.second.toFloat(); falconZ = t.third.toFloat() }
        if (ip && wp >= 2) m.tblCampSTPT[wp - 2].apply { falconY = GuideCase.ip.first.toFloat(); falconX = GuideCase.ip.second.toFloat(); falconZ = 300f }
        if (ground) m.groundElevation = { _, _, _ -> t.third }
        p.load(emptyList())
        p.guard { p.campTE() }
        p.setWaypoint(wp)
        return p
    }
    var cases = 0
    for (turn in listOf(0, 1)) for (off in listOf(0, 30, 60, 90)) for (hdg in listOf(0, 20, 135, 200, 359)) {
        val p = plan()
        p.slide("trbTurn", turn); p.slide("trbAngleOff", off); p.slide("trbHeading", hdg)
        val plot = p.attackPlot()
        val from = plot?.target
        val tag = "${if (turn == 0) "Left" else "Right"} off $off hdg $hdg"
        val sub = GeoChecks()
        sub.lands("$tag TGT-TO-VRP", from, p.labels["lblVRPbrg"], p.labels["lblVRPrng"], plot?.start)
        sub.lands("$tag TGT-TO-PUP", from, p.labels["lblVRPPUPbrg"], p.labels["lblVRPPUPrng"], plot?.pup)
        sub.lands("$tag OA1", from, p.labels["lblOA1brg"], p.labels["lblOA1rng"], plot?.oa1)
        sub.lands("$tag OA2", from, p.labels["lblOA2brg"], p.labels["lblOA2rng"], plot?.oa2)
        for ((k, lbl) in listOf("VRP" to "lblVRP", "VRPPUP" to "lblVRPPUP", "OA1_2" to "lblOA1", "OA2_2" to "lblOA2")) {
            val o = p.navOffsets[k]
            val same = o != null && o.bearing == (p.labels["${lbl}brg"]?.toFloatOrNull() ?: -1f) && o.range.toString() == p.labels["${lbl}rng"] && o.elv.toString() == p.labels["${lbl}elv"]
            sub.check("$tag $k is the DED line", same, "saved ${o?.bearing},${o?.range},${o?.elv}")
        }
        // D4 by construction: R = pull-up distance − MAP, D = the pull-up distance
        val d = p.labels["lblVRPPUPrng"]?.toDoubleOrNull() ?: 0.0
        val r = d - (p.labels["lblMAPVal"]?.toDoubleOrNull() ?: 0.0)
        val side = Math.atan(r / d) * Math.sin(off * Math.PI / 180.0) * 180.0 / Math.PI
        val want = AttackGeometry.wrap360(AttackGeometry.wrap360(hdg - 180.0) + if (turn == 1) -side else side)
        val got = p.labels["lblVRPPUPbrg"]?.toDoubleOrNull() ?: -1.0
        var db = abs(got - want) % 360.0
        if (db > 180.0) db = 360.0 - db
        sub.check("$tag D4 PUP bearing = attack bearing ∓ atan(R/D)·sin(off)", db <= 0.051, "PUP $got°, construction %.2f° (R %.0f, D %.0f)".format(want, r, d))
        // D5: the flow is at rest — one more changes nothing
        val once = p.labels.toMap()
        p.guard { p.programFlow(true) }
        val diff = once.filter { (k, v) -> p.labels[k] != v }.keys
        sub.check("$tag D5 a second flow changes nothing", diff.isEmpty(), "changed $diff")
        cases++
        if (sub.bad > 0) { g.text.append(sub.text.lines().filter { it.startsWith("FAIL") }.joinToString("\n")).append('\n'); g.bad += sub.bad }
    }
    g.check("every DED line of $cases plans lands on the point the page draws, and D4/D5 hold on each", true, "failures, if any, are listed above")

    // D5: the same sliders in two orders give the same page
    run {
        val a = plan(); a.slide("trbIngrSpd", 35); a.slide("trbReleaseHeight", 80); a.slide("trbDiveAngle", 40)
        val b = plan(); b.slide("trbDiveAngle", 40); b.slide("trbReleaseHeight", 80); b.slide("trbIngrSpd", 35)
        val diff = a.labels.filter { (k, v) -> b.labels[k] != v }.keys
        g.check("D5 the same settings reached in two orders give the same figures", diff.isEmpty(), if (diff.isEmpty()) "identical" else "differ in $diff")
    }
    // D5: the right-hand N
    run {
        val p = plan()
        p.headingShortcut(360)
        g.check("D5 N(360) prints OA2's bearing as 0.0", p.labels["lblOA2brg"] == "0.0", "OA2 ${p.labels["lblOA2brg"]} (WDP: 360.0)")
    }
    // D7
    run {
        val p = plan()
        val gnd = Math.round(t.third).toInt()
        val wheel = p.labels["lblWheelElv"]?.toIntOrNull() ?: 0
        val rel = p.labels["lblReleaseHeightVal"]?.toIntOrNull() ?: 0
        g.check("D7 VRP/PUP ELEV = the profile's height + target ground", p.labels["lblVRPelv"] == (wheel + gnd).toString() && p.labels["lblVRPPUPelv"] == (wheel + gnd).toString(),
            "VRP ${p.labels["lblVRPelv"]}, PUP ${p.labels["lblVRPPUPelv"]} (height $wheel + ground $gnd)")
        g.check("D7 OA1 ELEV = release height + target ground", p.labels["lblOA1elv"] == (rel + gnd).toString(), "OA1 ${p.labels["lblOA1elv"]} (release $rel + ground $gnd)")
        g.check("D7 OA2 ELEV stays 0 (BMS: ground level)", p.labels["lblOA2elv"] == "0", "OA2 ${p.labels["lblOA2elv"]}")
        g.check("D7 Ingress Alt is the VRP's ELEV", p.labels["lblAdvIngAltVal"] == p.labels["lblVRPelv"], "Ingress Alt ${p.labels["lblAdvIngAltVal"]}")
        p.elev.set("VRP", 0); p.guard { p.programFlow(true) }
        g.check("D7 a typed VRP ELEV of 0 is shown and saved", p.labels["lblVRPelv"] == "0" && p.navOffsets["VRP"]?.elv == 0, "DED ${p.labels["lblVRPelv"]}, saved ${p.navOffsets["VRP"]?.elv}")
        val q = plan(ground = false)
        g.check("D7 no known ground: the heights as WDP gives them", q.labels["lblVRPelv"] == q.labels["lblWheelElv"], "VRP ${q.labels["lblVRPelv"]}, height ${q.labels["lblWheelElv"]}")
    }
    // D8
    run {
        val p = plan(wp = 1)
        g.check("D8 steerpoint 1 can be the target (no IP before it)", p.numWaypoint == 1 && p.blnGotTGT && !p.blnDoVIP && p.labels["lblVRPwp"] == "1",
            "box ${p.numWaypoint}, target ${p.blnGotTGT}, IP ${p.blnDoVIP}, DED steerpoint ${p.labels["lblVRPwp"]}")
    }
    // D10
    run {
        val p = plan()
        p.main.threats = listOf(com.bmscompanion.app.data.wdp.AttackMap.Threat(east = (t.second + 20000.0).toFloat(), north = (t.first + 10000.0).toFloat(), rangeFt = 5000f, code = "SA-6"))
        val pic = p.mapPicture()
        val ring = pic.items.filterIsInstance<com.bmscompanion.app.data.wdp.PopupPlan.MapItem.Ellipse>().firstOrNull { it.color == "Red" }
        val c = com.bmscompanion.app.data.wdp.PopupPlan.MAP_PX / 2
        val cx = ring?.let { it.x + it.w / 2 } ?: -1
        val cy = ring?.let { it.y + it.h / 2 } ?: -1
        g.check("D10 a threat 20,000 ft east and 10,000 ft north is ringed right of and above the target", ring != null && cx - c > c - cy && c - cy > 0, "ring centre $cx,$cy, target $c,$c")
    }
    // D87: an IP STPT picked: the map's IP and the zeroed VIP lines' steerpoint follow it; the VRP plan (measured from the
    // target) does not move; back to WDP's rule; an IP with no position is no IP
    run {
        val p = plan()
        val before = listOf("lblVRPbrg", "lblVRPrng", "lblVRPPUPbrg", "lblVRPPUPrng", "lblOA1brg", "lblOA1rng").map { p.labels[it] }
        val other = Triple(t.first - 30000.0, t.second - 45000.0, 0.0)
        p.main.tblCampSTPT[10].apply { falconY = other.first.toFloat(); falconX = other.second.toFloat(); falconZ = 500f }
        p.setIpStpt(11)
        // HADB plans from a VRP only: since 1.3.8 its map draws no IP at all (B4; the IP STPT box is off the page, D94)
        val plotIp = p.attackPlot()?.ip
        val after = listOf("lblVRPbrg", "lblVRPrng", "lblVRPPUPbrg", "lblVRPPUPrng", "lblOA1brg", "lblOA1rng").map { p.labels[it] }
        val n = p.navOffsets
        g.check("D87 IP STPT 11: the (zeroed) VIP lines name it, the VRP plan is unchanged, the map draws no IP (D94)", plotIp == null && p.blnDoVIP && before == after &&
            n["VIP"]?.stpt == 11 && n["OA1_1"]?.stpt == 11 && n["VRP"]?.stpt == 15,
            "IP $plotIp, VIP ${n["VIP"]?.stpt}, OA1_1 ${n["OA1_1"]?.stpt}, VRP ${n["VRP"]?.stpt}; VRP lines ${if (before == after) "same" else "$before → $after"}")
        p.setIpStpt(null)
        g.check("D87 back to WDP's rule: the VIP lines name STPT 14 again", p.navOffsets["VIP"]?.stpt == 14 && p.attackPlot()?.ip == null,
            "IP ${p.attackPlot()?.ip}, VIP ${p.navOffsets["VIP"]?.stpt}")
        p.setIpStpt(13)
        g.check("D87 an IP STPT with no position: no IP", p.blnGotTGT && !p.blnDoVIP && p.attackPlot()?.ip == null, "target ${p.blnGotTGT}, IP ${p.blnDoVIP}, map IP ${p.attackPlot()?.ip}")
    }
    return ("HADB geometry, by construction:\n" + g.text) to g.bad
}

/** The coordinate presets of the harness (`Hadb.Coords`, the same as the Pop-up's), the same figures. */
private fun hadbCoordPreset(name: String): PopupCoords.CoordData = when (name) {
    "old1" -> PopupCoords.CoordData(34.0, 124.0, 3358699.5, 3358699.5, false)
    "old2" -> PopupCoords.CoordData(-53.5, -62.0, 2000000.0, 2500000.0, false)
    "new1" -> PopupCoords.CoordData(0.0, 0.0, 3358699.5, 3358699.5, true, PopupCoords.TransverseMercatorMeta(127.5, -512000.0, 3700000.0, 1024000L, 16384f, 62.5f, 1f / (62.5f * 3.27998f), 62.5f * 3.27998f, 8192f))
    "new2" -> PopupCoords.CoordData(0.0, 0.0, 3358699.5, 3358699.5, true, PopupCoords.TransverseMercatorMeta(22.0, -400000.0, 4300000.0, 1024000L, 1024f, 1000f, 1f / 3279.98f, 3279.98f, 512f))
    "new3" -> PopupCoords.CoordData(0.0, 0.0, 3358699.5, 3358699.5, true, PopupCoords.TransverseMercatorMeta(-60.0, -500000.0, -6100000.0, 1024000L, 4096f, 250f, 1f / (250f * 3.27998f), 250f * 3.27998f, 2048f))
    else -> PopupCoords.CoordData()
}

/** One op of the harness's sequence, on the port. */
private fun apply(p: HadbPlan, op: String) {
    val eq = op.indexOf('=')
    val k = if (eq < 0) op else op.substring(0, eq)
    val v = if (eq < 0) "" else op.substring(eq + 1)
    fun ints() = v.split(',').map { it.trim().toInt() }
    val m = p.main
    when (k) {
        "ini" -> p.setupIni = if (v.isEmpty()) emptyList() else v.split('|').map { kv -> val i = kv.indexOf(':'); kv.substring(0, i) to kv.substring(i + 1) }
        "noini" -> p.setupIni = null
        "load" -> p.show()
        "dive" -> p.slide("trbDiveAngle", v.toInt())
        "spd" -> p.slide("trbReleaseSpd", v.toInt())
        "rel" -> p.slide("trbReleaseHeight", v.toInt())
        "track" -> p.slide("trbTrackingTime", v.toInt())
        "ingr" -> p.slide("trbIngrSpd", v.toInt())
        "g" -> p.slide("trbG", v.toInt())
        "turn" -> p.slide("trbTurn", v.toInt())
        "vrp" -> p.slide("trbVrpToPup", v.toInt())
        "aoff" -> p.slide("trbAngleOff", v.toInt())
        "hdg" -> p.slide("trbHeading", v.toInt())
        "zoom" -> p.slide("trbZoom", v.toInt())
        "wp" -> p.setWaypoint(v.toInt())
        "click" -> when (v) {
            "lblN" -> p.headingShortcut(0)
            "lblE" -> p.headingShortcut(90)
            "lblS" -> p.headingShortcut(180)
            "lblW" -> p.headingShortcut(270)
            "lblN360" -> p.headingShortcut(360)
            "lbl0" -> p.angleOffShortcut(0)
            "lbl30" -> p.angleOffShortcut(30)
            "lbl60" -> p.angleOffShortcut(60)
            "lbl90" -> p.angleOffShortcut(90)
            "pnlBomb_Up" -> p.chooseBomb(false)
            "pnlBomb_Down" -> p.chooseBomb(true)
            "pnlExtraInfo_Up" -> p.chooseExtraInfo(true)
            "pnlExtraInfo_Down" -> p.chooseExtraInfo(false)
            "btnPPTnr" -> p.togglePPTnr()
            "btnCamp" -> p.chooseCampTE(true)
            "btnTE" -> p.chooseCampTE(false)
            else -> error("unknown click $v")
        }
        "sel" -> {
            val (panel, button) = v.split(':')
            val which = when (panel) { "pnlSelections_Up" -> "Selections"; "pnlSelections_Middle" -> "Profile"; else -> "DED Data" }
            p.chooseSelection(which, button != "R")
        }
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
            m.flightTable = Array(maxOf(sel + 1, 1)) { Array(count) { HadbPlan.GridWp() } }
        }
        "fwp" -> {
            val (i, gx, gy, gz) = ints()
            val w = m.flightTable!![m.selFlightNr][i]
            w.gridX = gx.toShort(); w.gridY = gy.toShort(); w.gridZ = gz.toShort()
        }
        "coord" -> {
            val c = hadbCoordPreset(v)
            m.originLat = c.originLat; m.originLong = c.originLong; m.campW = c.campW; m.campH = c.campH
            m.enableNewTerrain = c.enableNewTerrain; m.tm = c.tm
            p.setCoordData()
        }
        "getcoords" -> p.guard { p.getCoords() }
        "camptecall" -> p.guard { p.campTE() }
        "stpt" -> p.guard { p.stptChange() }
        "flow" -> p.guard { p.programFlow(v == "1") }
        else -> error("unknown op $op")
    }
}

/** The port's values under the harness's column names. */
private fun snapshot(p: HadbPlan): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    for ((k, v) in p.labels) m[k] = v
    m["lblTargetHUDval.back"] = p.hudBack
    for ((k, v) in p.shown) m["vis_$k"] = if (v) "shown" else "hidden"
    for ((n, b) in listOf("trbDiveAngle" to p.trbDiveAngle, "trbReleaseSpd" to p.trbReleaseSpd, "trbReleaseHeight" to p.trbReleaseHeight,
        "trbTrackingTime" to p.trbTrackingTime, "trbIngrSpd" to p.trbIngrSpd, "trbG" to p.trbG, "trbTurn" to p.trbTurn,
        "trbVrpToPup" to p.trbVrpToPup, "trbAngleOff" to p.trbAngleOff, "trbHeading" to p.trbHeading, "trbZoom" to p.trbZoom)) {
        m["sld_$n"] = b.value.toString(); m["sld_$n.min"] = b.min.toString(); m["sld_$n.max"] = b.max.toString()
    }
    m["numWaypoint"] = p.numWaypoint.toString()
    m["picProfile"] = p.profilePicture
    m["btnPPTnr.text"] = p.btnPPTnrText
    m["btnCamp.enabled"] = if (p.btnCampEnabled) "1" else "0"; m["btnCamp.back"] = p.btnCampBack
    m["btnTE.enabled"] = if (p.btnTEEnabled) "1" else "0"; m["btnTE.back"] = p.btnTEBack
    m["nav_Modesel"] = p.navModesel.toString()
    for ((k, o) in p.navOffsets) {
        m["nav_${k}_Stpt"] = o.stpt.toString(); m["nav_${k}_Bearing"] = o.bearing.toString()
        m["nav_${k}_Range"] = o.range.toString(); m["nav_${k}_Elv"] = o.elv.toString()
    }
    for ((k, v) in p.state()) m["st_$k"] = when (v) {
        null -> "<null>"
        is Boolean -> if (v) "1" else "0"
        else -> v.toString()
    }
    for ((k, v) in p.iniValues()) m["ini_$k"] = v ?: "<null>"
    m["errors"] = p.errors.toString()
    return m
}

/** Equal as text, or — for the nav bearings the harness prints round-trip — as the same float. */
private fun same(name: String, want: String, have: String): Boolean {
    if (want == have) return true
    if (name.startsWith("nav_") && name.endsWith("_Bearing")) {
        val a = want.toFloatOrNull() ?: return false
        val b = have.toFloatOrNull() ?: return false
        return a == b || (a.isNaN() && b.isNaN())
    }
    return false
}
