package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.wdp.AttackGeometry
import com.bmscompanion.app.data.wdp.AttackMap
import com.bmscompanion.app.data.wdp.Performance
import com.bmscompanion.app.data.wdp.TossPlan
import java.io.File
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt

/**
 * `--wdptosstest <toss.tsv> <out.txt>` (also `--wdppagetest toss …`) — the ported TOSS page against the real one.
 *
 * The reference is written by `tools/wdpref toss`, which creates WDP's own `cntTOSS`, runs its Setup, then moves
 * its sliders through the same handlers a mouse would and copies every label out — 648 combinations of turn, G,
 * release angle, release speed, release height, angle-off and heading. The port is driven through the identical
 * sequence and every label has to come out the same, character for character: a bearing that is `271.3` here and
 * `271.2` there is a port that rounds differently, and a pilot reading it off a tablet has no way to know.
 *
 * **Except where WDP was wrong and the port fixes it.** Those differences are listed, by the plan's D-number, in
 * `wdp/expected-diffs/toss.txt` (the labels each fix changes, and on which rows); a difference that no line there
 * names still fails. What the fixed labels say instead is checked by construction in the geometry section
 * ([tossGeometry]): every DED line laid out again from its steerpoint must land on the point the page draws.
 *
 * `--wdppagetest toss geometry <out.txt>` runs the geometry section of all three attack pages without a reference.
 */
internal fun wdpTossTest(reference: File, out: File): String {
    if (reference.path == "geometry") return attackGeometryOnly(out)
    val text = buildString {
        appendLine("Weapon Delivery Planner TOSS page — the port against the program")
        appendLine("reference: ${reference.path}")
        if (!reference.isFile) {
            appendLine()
            appendLine("FAIL — no reference. Run tools/wdpref in toss mode from WDP's folder first.")
            return@buildString
        }
        val lines = reference.readLines()
        if (lines.size < 2) { appendLine("FAIL — empty reference"); return@buildString }
        val allow = AttackAllowList.load("toss")
        appendLine("expected differences: ${allow.describe()}")
        val head = lines[0].split('\t')
        val labelCols = head.withIndex().filter { it.value.startsWith("lbl") }

        var rows = 0
        var bad = 0
        val mismatched = LinkedHashMap<String, Int>()
        val examples = StringBuilder()
        for (line in lines.drop(1)) {
            val f = line.split('\t')
            if (f.size < head.size) continue
            fun col(name: String) = f[head.indexOf(name)]
            rows++

            // the same slider moves, in the same order, through the same handlers
            val plan = TossPlan()
            plan.ref = false
            plan.load()
            plan.waypoint = col("waypoint").toInt()
            plan.ingressCas = col("ingrCas").toInt(); plan.changeIngressSpeed()
            plan.ingressHeight = col("ingrHeight").toInt() * 100; plan.changeIngressHeight()
            plan.pullingGs = col("g").toInt(); plan.changePullingG()
            plan.turnDirection = col("turn"); plan.changeTurn()
            plan.oa2ToPupNm = col("oa2").toInt(); plan.changeOa2ToPup()
            plan.releaseAngleDeg = col("relAngle").toInt(); plan.changeRelAngle()
            plan.releaseCas = col("relCas").toInt(); plan.changeRelSpd()
            plan.releaseHeight = col("relHeight").toInt() * 100; plan.changeRelHeight()
            plan.angleOff = col("angleOff").toInt(); plan.changeAngleOff()
            plan.attackHeadingDeg = col("heading").toInt(); plan.changeHeading()
            plan.programFlow()

            // the row's inputs, which the expected-difference lines' conditions read
            val ctx = head.withIndex().filter { !it.value.startsWith("lbl") }.associate { it.value to f[it.index] }
            var rowBad = false
            for ((i, name) in labelCols) {
                val want = f[i]
                val got = plan.labels[name] ?: ""
                if (want == got) continue
                if (allow.match(name, want, got, ctx) != null) continue
                rowBad = true
                mismatched[name] = (mismatched[name] ?: 0) + 1
                if (examples.length < 3000) examples.appendLine("     row $rows $name: port '$got' vs WDP '$want'")
            }
            if (rowBad) bad++
        }

        appendLine("rows compared: $rows, labels per row: ${labelCols.size}")
        if (bad == 0) {
            appendLine("ok   every label on every row is WDP's, or differs only as expected-diffs/toss.txt says")
        } else {
            appendLine("FAIL $bad of $rows rows differ where no fix says they should")
            appendLine("     by label: " + mismatched.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}×${it.value}" })
            append(examples)
        }
        append(allow.report())

        appendLine()
        val (geo, geoBad) = tossGeometry()
        append(geo)
        appendLine()
        appendLine(
            if (bad == 0 && geoBad == 0) "PASS — the TOSS page answers as Weapon Delivery Planner does, except where it fixes WDP (expected-diffs/toss.txt), and its figures land where it draws them"
            else "FAIL — the TOSS port and Weapon Delivery Planner disagree beyond the listed fixes, or a figure does not land where the page draws it",
        )
    }
    runCatching { out.writeText(text) }
    return text
}

/** The geometry sections of all three attack pages, with no reference: `--wdppagetest <toss|hadb|popup> geometry out`. */
internal fun attackGeometryOnly(out: File): String {
    val text = buildString {
        appendLine("Weapon Delivery Planner attack pages — geometry, by construction")
        var bad = 0
        for ((name, run) in listOf("TOSS" to ::tossGeometry, "HADB" to ::hadbGeometry, "Pop-up" to ::popupGeometry)) {
            appendLine()
            appendLine("== $name")
            val (t, b) = run()
            append(t)
            bad += b
        }
        appendLine()
        appendLine(if (bad == 0) "PASS — every DED line lands where its page draws the point" else "FAIL — $bad geometry check(s) failed")
    }
    runCatching { out.writeText(text) }
    return text
}

/**
 * The expected differences of one page's comparison: `wdp/expected-diffs/<page>.txt`, one fix per line.
 *
 * `<D-number>  <labels>  [when <condition> [&& <condition>]…]  [value <WDP's>><the port's>]`
 *
 * Labels are comma-separated, `*` standing for any run of characters. A condition is `<key><op><value>` with `=`,
 * `!=` or `~` (a regular expression found anywhere); the key is one of the row's facts (its inputs, `ref.<column>`
 * for WDP's value, `port.<column>` for the port's), and a value starting with `@` is another fact. `value` limits the
 * line to that one pair of texts ("360.0" printed as "0.0"). A difference is allowed when some line names its label
 * and all that line's conditions hold; the report counts, per line, the cells it allowed, and names a line that
 * allowed nothing — a fix the reference never exercises, or a line that no longer describes the page.
 */
internal class AttackAllowList private constructor(private val page: String, private val rules: List<Rule>) {
    class Rule(val d: String, val labels: List<Regex>, val conds: List<Triple<String, String, String>>, val value: Pair<String, String>?, val text: String) {
        var used = 0
    }

    fun match(label: String, want: String, got: String, ctx: Map<String, String>): String? {
        for (r in rules) {
            if (r.labels.none { it.matches(label) }) continue
            if (r.value != null && (want != r.value.first || got != r.value.second)) continue
            if (!r.conds.all { (k, op, v0) -> holds(ctx[k] ?: "", op, if (v0.startsWith("@")) ctx[v0.substring(1)] ?: "" else v0) }) continue
            r.used++
            return r.d
        }
        return null
    }

    private fun holds(a: String, op: String, b: String): Boolean = when (op) {
        "=" -> a == b
        "!=" -> a != b
        "~" -> Regex(b).containsMatchIn(a)
        else -> false
    }

    fun describe(): String = if (rules.isEmpty()) "none (wdp/expected-diffs/$page.txt not found)"
        else rules.map { it.d }.distinct().joinToString(", ") + " (wdp/expected-diffs/$page.txt, ${rules.size} line(s))"

    fun report(): String = buildString {
        for (r in rules) appendLine("info ${r.d}: ${r.used} cell(s) differ as expected — ${r.text}" + if (r.used == 0) "  (allowed nothing here)" else "")
    }

    companion object {
        fun load(page: String): AttackAllowList {
            val text = AttackAllowList::class.java.getResourceAsStream("/wdp/expected-diffs/$page.txt")?.use { it.readBytes().decodeToString() }
                ?: File("desktop/src/main/resources/wdp/expected-diffs/$page.txt").takeIf { it.isFile }?.readText()
                ?: ""
            return AttackAllowList(page, text.lines().mapNotNull { parse(it) })
        }

        private fun parse(line: String): Rule? {
            val t = line.substringBefore('#').trim()
            if (t.isEmpty()) return null
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 2) return null
            val d = parts[0]
            val labels = parts[1].split(',').filter { it.isNotEmpty() }.map { g -> Regex(g.split('*').joinToString(".*") { Regex.escape(it) }) }
            val conds = ArrayList<Triple<String, String, String>>()
            var value: Pair<String, String>? = null
            var i = 2
            while (i < parts.size) {
                when (parts[i]) {
                    "when", "&&" -> {}
                    "value" -> { i++; val v = parts.getOrNull(i) ?: ""; value = v.substringBefore('>') to v.substringAfter('>') }
                    else -> {
                        val c = parts[i]
                        val m = Regex("^([^!=~]+)(!=|=|~)(.*)$").find(c) ?: error("expected-diffs: cannot read '$c' in '$line'")
                        conds += Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    }
                }
                i++
            }
            return Rule(d, labels, conds, value, t)
        }
    }
}

/** A running list of checks: each line says ok or FAIL, and the count of failures is kept. */
internal class GeoChecks {
    val text = StringBuilder()
    var bad = 0
    fun check(name: String, ok: Boolean, detail: String) {
        text.appendLine((if (ok) "ok   " else "FAIL ") + name + "  — " + detail)
        if (!ok) bad++
    }

    /**
     * One DED line laid out again from its steerpoint: [from] is the steerpoint (north, east), [brg]/[rng] the figures
     * the page prints, [at] where the page draws the point. The printed bearing must match to [maxDeg] and the range
     * to [maxFt] — the DED's own rounding and nothing more.
     */
    fun lands(name: String, from: Pair<Double, Double>?, brg: String?, rng: String?, at: Pair<Double, Double>?, maxDeg: Double = 0.1, maxFt: Double = 1.0) {
        if (from == null || at == null) { check(name, false, "no point to measure (from=$from at=$at)"); return }
        val b = brg?.toDoubleOrNull()
        val r = rng?.toDoubleOrNull()
        if (b == null || r == null) { check(name, false, "the DED line reads '$brg' / '$rng'"); return }
        val m = AttackGeometry.bearingRange(from.first, from.second, at.first, at.second)
        var db = abs(m.bearingDeg - b) % 360.0
        if (db > 180.0) db = 360.0 - db
        val dr = abs(m.rangeFt - r)
        check(name, db <= maxDeg && dr <= maxFt,
            "DED %s° %s ft, drawn at %.2f° %.1f ft (Δ %.3f°, %.2f ft)".format(brg, rng, m.bearingDeg, m.rangeFt, db, dr))
    }
}

/** The guide's synthetic mission: the real STPT 15 (an EW site 1,913 ft up) and an IP 8 nm from it on a bearing of 200°. */
internal object GuideCase {
    val target = Triple(1569052.125, 988445.4375, 1913.301758)
    const val NM = 6076.1157
    val ip: Triple<Double, Double, Double> = AttackGeometry.lay(target.first, target.second, 200.0, 8 * NM).let { Triple(it.first, it.second, 300.0) }
    val ipToTarget: AttackGeometry.BrgRng get() = AttackGeometry.bearingRange(ip.first, ip.second, target.first, target.second)
}

/**
 * The TOSS page by construction. Every DED line, VRP and VIP, laid out again from its steerpoint lands where the page
 * draws the point (D1, D2, D3); the guide's case reads 020.0° like Pop-up; OA1 at 90° off is the point R across the
 * run-in at the lineup distance (D4); the elevations stand on the ground (D7); Save to DTC hands on the figures
 * the DED shows, and only the reference in use (D3); threat rings are drawn where the threat is (D10).
 */
internal fun tossGeometry(): Pair<String, Int> {
    val g = GeoChecks()
    val t = GuideCase.target
    val ip = GuideCase.ip
    fun plan(turn: String, off: Int, hdg: Int, vip: Boolean, ground: Double? = t.third): TossPlan {
        val p = TossPlan()
        p.ref = false
        p.load()
        p.setTargets(t, ip, ground)
        p.turnDirection = turn; p.changeTurn()
        p.angleOff = off; p.changeAngleOff()
        p.attackHeadingDeg = hdg; p.changeHeading()
        p.chooseRef(vip)
        return p
    }

    // the guide's case (§14 item 46): WDP said 070.0° / 48,605 ft, the true course is 020°
    run {
        val p = plan("Right", 0, 20, vip = true)
        val want = GuideCase.ipToTarget
        g.check("guide case: IP 8 nm on 020° → VIP-TO-TGT 20.0°", p.labels["lblVIPbrg"] == "20.0",
            "VIP-TO-TGT ${p.labels["lblVIPbrg"]}° ${p.labels["lblVIPrng"]} ft (true: %.2f° %.1f ft; WDP: 70.0° 48605 ft)".format(want.bearingDeg, want.rangeFt))
        g.check("guide case: VIP-TO-TGT range", abs((p.labels["lblVIPrng"]?.toDoubleOrNull() ?: 0.0) - want.rangeFt) <= 0.5, "got ${p.labels["lblVIPrng"]}")
        g.check("guide case: the VIP line names the IP's steerpoint", p.labels["lblVIPwp"] == (p.waypoint - 1).toString(), "got ${p.labels["lblVIPwp"]}")
    }

    // D87: an IP STPT picked other than the one before the target (the wiring hands its position in): every VIP line is
    // measured from it and lands on its point, and the DED and Save to DTC name that steerpoint
    run {
        val other = Triple(t.first - 30000.0, t.second - 45000.0, 500.0)
        val p = TossPlan()
        p.ref = false
        p.load()
        p.waypoint = 15
        p.ipStpt = 11
        p.setTargets(t, other, t.third)
        p.turnDirection = "Right"; p.changeTurn()
        p.attackHeadingDeg = 20; p.changeHeading()
        p.chooseRef(true)
        val plot = p.attackPlot()
        val from = other.first to other.second
        val sub = GeoChecks()
        sub.lands("D87 IP STPT 11 VIP-TO-TGT", from, p.labels["lblVIPbrg"], p.labels["lblVIPrng"], plot?.target)
        sub.lands("D87 IP STPT 11 PUP", from, p.labels["lblPUPbrg"], p.labels["lblPUPrng"], plot?.pup)
        sub.lands("D87 IP STPT 11 OA1", from, p.labels["lblOA1brg"], p.labels["lblOA1rng"], plot?.oa1)
        sub.lands("D87 IP STPT 11 OA2", from, p.labels["lblOA2brg"], p.labels["lblOA2rng"], plot?.oa2)
        val saved = p.savedOffsets()
        g.check("D87 IP STPT 11: every VIP line lands from STPT 11", sub.bad == 0 && p.doVip, sub.text.lines().filter { it.startsWith("FAIL") }.joinToString(" ").ifEmpty { "VIP-TO-TGT ${p.labels["lblVIPbrg"]}° ${p.labels["lblVIPrng"]} ft" })
        g.check("D87 IP STPT 11: the DED and Save to DTC name STPT 11", p.labels["lblVIPwp"] == "11" && saved["VIP"]?.stpt == 11 && saved["OA1_1"]?.stpt == 11 && p.navOffsets()["VRP"]?.stpt == 15,
            "DED ${p.labels["lblVIPwp"]}, saved VIP ${saved["VIP"]?.stpt}, OA1_1 ${saved["OA1_1"]?.stpt}, VRP ${p.navOffsets()["VRP"]?.stpt}")
        p.ipStpt = null
        p.setTargets(t, ip, t.third)
        g.check("D87 back to WDP's rule: the VIP line names STPT 14", p.labels["lblVIPwp"] == "14" && p.navOffsets()["VIP"]?.stpt == 14, "DED ${p.labels["lblVIPwp"]}, VIP ${p.navOffsets()["VIP"]?.stpt}")
    }

    // every line, both references, over turns, angles off and headings
    var cases = 0
    for (vip in listOf(false, true)) for (turn in listOf("Left", "Right")) for (off in listOf(0, 30, 60, 90)) for (hdg in listOf(0, 20, 135, 200, 359)) {
        val p = plan(turn, off, hdg, vip)
        val plot = p.attackPlot()
        val from = if (vip) ip.first to ip.second else t.first to t.second
        val tag = "${if (vip) "VIP" else "VRP"} $turn off $off hdg $hdg"
        val sub = GeoChecks()
        if (vip) sub.lands("$tag VIP-TO-TGT", from, p.labels["lblVIPbrg"], p.labels["lblVIPrng"], plot?.target)
        else sub.lands("$tag TGT-TO-VRP", from, p.labels["lblVIPbrg"], p.labels["lblVIPrng"], plot?.start)
        sub.lands("$tag PUP", from, p.labels["lblPUPbrg"], p.labels["lblPUPrng"], plot?.pup)
        sub.lands("$tag OA1", from, p.labels["lblOA1brg"], p.labels["lblOA1rng"], plot?.oa1)
        sub.lands("$tag OA2", from, p.labels["lblOA2brg"], p.labels["lblOA2rng"], plot?.oa2)
        // one source: what Save to DTC hands on is what the DED lines show
        val saved = p.savedOffsets()
        val keys = if (vip) AttackGeometry.VIP_KEYS else AttackGeometry.VRP_KEYS
        sub.check("$tag Save to DTC hands on the reference in use only", saved.keys.toList() == keys, "keys ${saved.keys}")
        for ((k, lbl) in keys.zip(listOf("VIP", "PUP", "OA1", "OA2"))) {
            val o = saved[k]
            val same = o != null && o.bearing == (p.labels["lbl${lbl}brg"]?.toFloatOrNull() ?: -1f) && o.range.toString() == p.labels["lbl${lbl}rng"] &&
                o.elv.toString() == p.labels["lbl${lbl}elv"] && o.stpt.toString() == p.labels["lbl${lbl}wp"]
            sub.check("$tag $k is the DED line", same, "saved ${o?.stpt},${o?.bearing},${o?.range},${o?.elv} vs DED ${p.labels["lbl${lbl}wp"]},${p.labels["lbl${lbl}brg"]},${p.labels["lbl${lbl}rng"]},${p.labels["lbl${lbl}elv"]}")
        }
        cases++
        // only the failures are listed for the grid, which is long
        if (sub.bad > 0) { g.text.append(sub.text.lines().filter { it.startsWith("FAIL") }.joinToString("\n")).append('\n'); g.bad += sub.bad }
    }
    g.check("every DED line of $cases plans lands on the point the page draws (0.1°, 1 ft)", true, "failures, if any, are listed above")

    // D4 by construction: at 90° off OA1 is the point the turn radius across the run-in at the lineup distance
    for (turn in listOf("Left", "Right")) {
        val p = plan(turn, 90, 0, vip = false)
        val tas = TossPlan.bankers(Performance.tas(p.ingressCas, p.ingressHeight)).toInt()
        val r = TossPlan.bankers(Performance.turnRadius(p.pullingGs, tas)).toDouble()
        val along = (p.labels["lblOA2rng"]?.toDoubleOrNull() ?: 0.0) + 6000.0
        val wantRng = sqrt(along * along + r * r)
        val side = atan(r / along) * 180.0 / Math.PI
        val wantBrg = AttackGeometry.wrap360(180.0 + if (turn == "Right") -side else side)
        val got = p.labels["lblOA1brg"]?.toDoubleOrNull() ?: -1.0
        g.check("D4 OA1 at 90° off, turn $turn: atan(R/D) across the run-in",
            abs(got - wantBrg) <= 0.051 && abs((p.labels["lblOA1rng"]?.toDoubleOrNull() ?: 0.0) - wantRng) <= 1.0,
            "OA1 ${p.labels["lblOA1brg"]}° ${p.labels["lblOA1rng"]} ft, construction %.2f° %.0f ft (R %.0f, D %.0f; WDP's tan gave %.2f°)".format(
                wantBrg, wantRng, r, along, AttackGeometry.wrap360(180.0 + (if (turn == "Right") -1 else 1) * Math.tan(r / along) * 180.0 / Math.PI)))
    }

    // D4 order: the VRP is laid out from this flow's OA1, so one more flow changes nothing
    run {
        val p = plan("Left", 0, 0, vip = false)
        p.angleOff = 60; p.changeAngleOff()
        val once = p.labels.toMap()
        p.programFlow()
        val diff = once.filter { (k, v) -> p.labels[k] != v }.keys
        g.check("D4 the VRP follows an angle-off change at once (no flow behind)", diff.isEmpty(), if (diff.isEmpty()) "a second flow changes nothing" else "a second flow changed $diff")
    }

    // D7: heights on the ground under the target; 0 stays ground level; typed figures win until the target changes
    run {
        val p = plan("Right", 30, 0, vip = false)
        val h = p.ingressHeight
        val gnd = Math.round(t.third).toInt()
        g.check("D7 VRP/PUP/OA1 ELEV = ingress height + target ground", listOf("VIP", "PUP", "OA1").all { p.labels["lbl${it}elv"] == (h + gnd).toString() },
            "VRP ${p.labels["lblVIPelv"]}, PUP ${p.labels["lblPUPelv"]}, OA1 ${p.labels["lblOA1elv"]} (ingress $h + ground $gnd)")
        g.check("D7 OA2 ELEV stays 0 (BMS: ground level)", p.labels["lblOA2elv"] == "0", "OA2 ${p.labels["lblOA2elv"]}")
        val unknown = plan("Right", 30, 0, vip = false, ground = null)
        g.check("D7 no known ground: the heights as WDP gives them", unknown.labels["lblVIPelv"] == h.toString(), "VRP ${unknown.labels["lblVIPelv"]}")
        p.elev.set("OA2_2", 1234); p.programFlow()
        g.check("D7 a typed OA2 ELEV is shown and saved", p.labels["lblOA2elv"] == "1234" && p.savedOffsets()["OA2_2"]?.elv == 1234, "DED ${p.labels["lblOA2elv"]}, saved ${p.savedOffsets()["OA2_2"]?.elv}")
        p.setTargets(Triple(t.first + 5000.0, t.second, t.third), ip, t.third)
        g.check("D7 a new target forgets the typed ELEV", p.labels["lblOA2elv"] == "0", "OA2 ${p.labels["lblOA2elv"]}")
    }

    // D3: the VRP line's caption, and no VIP lines measured from nowhere
    run {
        val p = TossPlan(); p.ref = false; p.load(); p.setTargets(t, null, t.third)
        g.check("D3 the VRP line reads VRP (WDP: VPP)", p.labels["lblDEDvip_2"] == "VRP", "got ${p.labels["lblDEDvip_2"]}")
        val nav = p.navOffsets()
        g.check("D3 without an IP the VIP lines are zeros", listOf("VIP", "VIPPUP", "OA1_1", "OA2_1").all { nav[it]?.range == 0 }, "VIP ranges ${listOf("VIP", "VIPPUP", "OA1_1", "OA2_1").map { nav[it]?.range }}")
    }

    // D10: a threat north-east of the target is ringed north-east of it on the map
    run {
        val p = plan("Right", 0, 0, vip = false)
        p.threats = listOf(AttackMap.Threat(east = (t.second + 20000.0).toFloat(), north = (t.first + 10000.0).toFloat(), rangeFt = 5000f, code = "SA-6"))
        val pic = p.mapPicture()
        val ring = pic.items.filterIsInstance<com.bmscompanion.app.data.wdp.PopupPlan.MapItem.Ellipse>().firstOrNull { it.color == "Red" }
        val c = com.bmscompanion.app.data.wdp.PopupPlan.MAP_PX / 2
        val cx = ring?.let { it.x + it.w / 2 } ?: -1
        val cy = ring?.let { it.y + it.h / 2 } ?: -1
        g.check("D10 a threat 20,000 ft east and 10,000 ft north is ringed right of and above the target", ring != null && cx - c > cy.let { c - it } && c - cy > 0,
            "ring centre $cx,$cy, target $c,$c")
    }
    return ("TOSS geometry, by construction:\n" + g.text) to g.bad
}
