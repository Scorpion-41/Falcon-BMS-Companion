package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.CommEntry
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.wdp.DtcEdits
import com.bmscompanion.app.data.wdp.DtcHarmCode
import com.bmscompanion.app.data.wdp.DtcIni
import com.bmscompanion.app.data.wdp.DtcLoad
import com.bmscompanion.app.data.wdp.DtcMaskProbe
import com.bmscompanion.app.data.wdp.DtcModel
import com.bmscompanion.app.data.wdp.DtcPage
import com.bmscompanion.app.data.wdp.DtcSave
import com.bmscompanion.app.data.wdp.DtcTables
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.ui.screens.wdp.DtcSource
import com.bmscompanion.app.ui.screens.wdp.DtcWiring
import com.bmscompanion.app.ui.screens.wdp.WdpCartridge
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpMessage
import com.bmscompanion.app.ui.screens.wdp.WdpMission
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `--wdppagetest dtc <dtc.tsv> <out.txt>` — the ported DTC page (cartridge reading, the page, writing) against the
 * real Weapon Delivery Planner, with the differences the Planner makes on purpose allowed by name.
 *
 * `--wdppagetest dtc <cartridge.ini> <out.txt>` — the Falcon BMS 4.38.1 cases instead ([dtcCartridgeCases]): a copy
 * of a real cartridge read, edited and written in memory (nothing is written to disk), for what the Planner does
 * differently from WDP.
 *
 * The reference is written by `tools/wdpref page Dtc`, which works over synthetic cartridges only. Each row has a
 * kind; every output column of every row is compared character for character (text is the whole file, CR and LF
 * included, escaped with backslashes; floats by their bits).
 *
 * - `ini`: Windows' profile API itself over random small files — the read, write and delete calls `clsIni` makes.
 * - `mask`: a MaskedTextBox's Text after Text is set, for every mask the page uses.
 * - `init`: the model a fresh `fclsMain` holds.
 * - `pbase`: the page after its Load: every control's state (the baseline the later rows are told as differences
 *   from), and the model after it (`b_`), which the Load's own list handlers change.
 * - `cart`: one session step, replayed in order on one page and one model as WDP keeps one `fclsMain`: the cartridge
 *   read through the page's `GetCampFile` (the model straight after the reader, `m_`; the page, `p_load`; the model
 *   after the page filled itself, `g_`), the pilot's edits through the page's own handlers (`in_ui`; `p_ui`, `u_`),
 *   edits to the model by reflection and the include boxes and HARM buttons (`in_mut`, `in_incl`, `in_rbn`), then
 *   `SaveCallsign_DTC`: the file (`out_text`), the model (`s_`) and the page (`p_save`).
 *
 * The page is compared property by property: every property the port holds must equal the program's, and a
 * property the program changed that the port does not hold is a difference too.
 *
 * ### What is allowed to differ (`resources/wdp/expected-diffs/dtc.txt`)
 * The Planner is "WDP for Falcon BMS 4.38.1": it fixes WDP's bugs and takes the app's data where WDP took its own
 * (the plan's A- and D-numbers). Each such difference is one line of the allow-list — the D-number, the column, the
 * property or cartridge key it touches, and why — and is counted under its D-number, not as a failure. Anything
 * else that differs still fails. Three rules come with that:
 * - **Only 4.38.1 sessions are judged.** Every row is still replayed (they share one page, as WDP's do), but a
 *   `cart` row read or saved with an older BMS version than 4.38 build 25688 is not judged: WDP branched on the
 *   version at a dozen places and the Planner no longer does. A 4.38.1 row whose page WDP left half filled (it threw
 *   part way, `p_exc`) straight after a row that was not judged is judged on its reading of the file alone (the
 *   `m_` columns): what the page did not reach still shows that older session, where WDP had branched on the
 *   version and the Planner had not, and WDP's save takes the HARM boxes from the page.
 * - **The file is compared key by key** (`out_text`): the Planner writes only the keys the pilot edited, each in
 *   place ([DtcEdits]), so where WDP's whole-model save put a key is not what reaches the cartridge; what each key
 *   holds is. Numbers that are the same float are the same (D17: WDP printed seven digits).
 * - **The page's HARM lists are the app's** (A1), so the page is given the app's `curated/harm.json`, as the
 *   product is.
 */
internal fun wdpDtcTest(reference: File, out: File): String {
    if (reference.extension.equals("ini", ignoreCase = true)) return dtcCartridgeCases(reference)
    val report = StringBuilder()
    report.appendLine("Weapon Delivery Planner DTC page — the port against the program")
    report.appendLine("reference: ${reference.path}")
    if (!reference.isFile) {
        report.appendLine("FAIL — no reference. Run tools/wdpref page Dtc from WDP's folder first.")
        return report.toString()
    }
    val lines = reference.readText().split("\r\n").filter { it.isNotEmpty() }
    val head = lines[0].split('\t')
    val idx = head.withIndex().associate { it.value to it.index }
    val harmCodes = dtcAppHarmCodes()
    val allow = DtcAllow.load()
    report.appendLine("allow-list: ${allow.size} entries (resources/wdp/expected-diffs/dtc.txt)")

    val counts = LinkedHashMap<String, Int>()
    val badByCol = LinkedHashMap<String, Int>()
    val expected = LinkedHashMap<String, Int>()
    val examples = StringBuilder()
    var nExamples = 0
    var badRows = 0
    var cells = 0
    var notJudged = 0
    var notJudgedStale = 0
    var staleRow = false
    var prevCartJudged = true
    val model = DtcModel()
    DtcLoad.DEBUG = System.getenv("DTC_DEBUG") != null
    var page: DtcPage? = null
    var baseline: Map<String, String> = emptyMap()

    for ((ln, line) in lines.drop(1).withIndex()) {
        val f = line.split('\t')
        fun col(name: String): String = idx[name]?.let { f.getOrNull(it) } ?: ""
        val kind = col("kind")
        counts[kind] = (counts[kind] ?: 0) + 1
        val got = LinkedHashMap<String, String>()
        val want = LinkedHashMap<String, String>()
        fun expect(name: String, value: String) { got[name] = value; want[name] = col(name) }
        fun allowed(column: String, prop: String): Boolean {
            val d = allow.match(column, prop) ?: return false
            expected[d] = (expected[d] ?: 0) + 1
            return true
        }
        fun expectPage(p: DtcPage, name: String) {
            val diff = dtcParseDump(col(name))
            val exp = HashMap(baseline).also { it.putAll(diff) }
            val mine = dtcPageDump(p)
            val gotSb = StringBuilder(); val wantSb = StringBuilder()
            for ((k, v) in mine) {
                val e = exp[k]
                // Open 2 read from target_89 (P2) can fail on a key WDP never read: "Harpoon" in the load-error line
                val p2 = k == "lblLoadError.T" && e != null && e != v && v.replace("Harpoon ", "") == e
                if (p2) { expected["P2"] = (expected["P2"] ?: 0) + 1; continue }
                if (e != v && !allowed(name, k)) { gotSb.append(k).append('=').append(v).append(' '); wantSb.append(k).append('=').append(e ?: "(no such property)").append(' ') }
            }
            for ((k, v) in diff) if (k !in mine && !allowed(name, k)) { gotSb.append(k).append("=(not held) "); wantSb.append(k).append('=').append(v).append(' ') }
            got[name] = gotSb.toString(); want[name] = wantSb.toString()
        }
        /** The saved file, key by key (see the KDoc): each key's value, floats as floats, the allow-list applied. */
        fun expectFile(name: String, text: String) {
            val w = dtcUnesc(col(name)) ?: ""
            val gotSb = StringBuilder(); val wantSb = StringBuilder()
            val a = dtcKeys(w); val b = dtcKeys(text)
            for (k in (a.keys + b.keys)) {
                val x = a[k]; val y = b[k]
                if (x == y) continue
                // a value WDP printed differently on purpose-fixed grounds (D17, P1) is counted, not failed
                val why = if (x != null && y != null) dtcValueDiff(x, y, k) else null
                if (why == "same") continue
                if (why != null) { expected[why] = (expected[why] ?: 0) + 1; continue }
                if (allowed(name, k)) continue
                gotSb.append(k).append('=').append(y ?: "(none)").append(' ')
                wantSb.append(k).append('=').append(x ?: "(none)").append(' ')
            }
            got[name] = gotSb.toString(); want[name] = wantSb.toString()
        }
        var judged = true
        when (kind) {
            "ini" -> {
                val text = dtcUnesc(col("in_text")) ?: ""
                val sec = dtcUnesc(col("in_sec")) ?: ""
                val key = dtcUnesc(col("in_key")) ?: ""
                val result = when (col("in_op")) {
                    "R" -> DtcIni.read(text, sec, key)
                    "W" -> DtcIni.write(text, sec, key, dtcUnesc(col("in_val")) ?: "")
                    "DK" -> DtcIni.deleteKey(text, sec, key)
                    else -> DtcIni.deleteSection(text, sec)
                }
                expect("out_text", dtcEsc(result))
            }
            "mask" -> {
                val prompt = dtcUnesc(col("in_prompt")) ?: ""
                val fmt = col("in_fmt")
                expect("out_text", dtcEsc(DtcMaskProbe.text(dtcUnesc(col("in_mask")) ?: "", if (prompt.isEmpty()) '_' else prompt[0], fmt, dtcUnesc(col("in_text")) ?: "")))
            }
            "init" -> for ((k, v) in dtcDump(model, "m_")) expect(k, v)
            "pbase" -> {
                baseline = dtcParseDump(col("p"))
                model.minorPart = 38; model.build = 30000
                val p = DtcPage(model)
                p.harmCodes = harmCodes
                p.load()
                page = p
                val mine = dtcPageDump(p)
                val gotSb = StringBuilder(); val wantSb = StringBuilder()
                for ((k, v) in mine) if (baseline[k] != v && !allowed("p", k)) { gotSb.append(k).append('=').append(v).append(' '); wantSb.append(k).append('=').append(baseline[k] ?: "(no such property)").append(' ') }
                got["p"] = gotSb.toString(); want["p"] = wantSb.toString()
                for ((k, v) in dtcDump(model, "b_")) if (v != col(k) && allowed(k, "*")) expect(k, col(k)) else expect(k, v)
            }
            "cart" -> {
                val p = page ?: continue
                val text = dtcUnesc(col("in_text")) ?: ""
                judged = dtcIs4381(col("in_minor"), col("in_build")) && dtcIs4381(col("in_sminor"), col("in_sbuild"))
                val own = judged
                staleRow = judged && col("p_exc").isNotEmpty() && !prevCartJudged
                if (staleRow) notJudgedStale++
                prevCartJudged = own
                model.minorPart = col("in_minor").toInt(); model.build = col("in_build").toInt()
                p.coords = dtcTheater(col("in_theater"))
                p.pptTable = col("in_ppt").split(';').filter { it.isNotEmpty() }.map {
                    val eq = it.indexOf('=')
                    Triple(dtcUnesc(it.substring(0, eq)) ?: "", dtcUnesc(it.substring(eq + 1)) ?: "", 10.0)
                }
                var afterLoad: Map<String, String> = emptyMap()
                p.afterLoad = { afterLoad = dtcDump(model, "m_") }
                val threw = try { p.getCampFile(text, dtcUnesc(col("in_file")) ?: ""); false } catch (e: Exception) { true }
                p.afterLoad = null
                expect("p_exc", if (threw == (col("p_exc") != "")) col("p_exc") else if (threw) "(threw)" else "")
                val res = p.lastLoad
                expect("m_loaded", if (res?.loaded == true) "1" else "0")
                expect("m_err", (res?.loadError ?: 0).toString())
                for ((k, v) in afterLoad) expect(k, v)
                expectPage(p, "p_load")
                for ((k, v) in dtcDump(model, "g_")) expect(k, v)

                // the pilot's edits
                val ops = col("in_ui").split('\u0001').filter { it.isNotEmpty() }
                val opResults = StringBuilder()
                for (op in ops) {
                    val bang = op.lastIndexOf('!')
                    val expectThrow = bang > op.indexOf('=').coerceAtLeast(op.indexOf(':'))
                    val body = if (expectThrow) op.substring(0, bang) else op
                    val kindOp = body.substringBefore(':')
                    val rest = body.substringAfter(':')
                    val name = rest.substringBefore('=')
                    val value = rest.substringAfter('=', "")
                    val didThrow = try {
                        when (kindOp) {
                            "click" -> p.click(name)
                            "comm" -> p.comm(name, dtcUnesc(value) ?: "")
                            "text" -> p.type(name, dtcUnesc(value) ?: "")
                            "sel" -> p.choose(name, value.toInt())
                            "num" -> p.number(name, value)
                        }
                        false
                    } catch (e: Exception) { true }
                    opResults.append(if (didThrow == expectThrow) "ok " else "$body:${if (didThrow) "threw" else "did not throw"} ")
                }
                got["ui"] = opResults.toString(); want["ui"] = "ok ".repeat(ops.size)
                expectPage(p, "p_ui")
                for ((k, v) in dtcDump(model, "u_")) expect(k, v)

                for (mu in col("in_mut").split('\u0001').filter { it.isNotEmpty() }) {
                    val eq = mu.indexOf('=')
                    try { dtcMutate(model, mu.substring(0, eq), mu.substring(eq + 1)) } catch (e: Exception) { }
                }
                val incl = col("in_incl")
                for (i in 0..5) p.setIncl(i, incl[i] == '1')
                val rb = col("in_rbn")
                for (group in listOf(listOf(0, 1), listOf(2, 3, 4), listOf(5, 6, 7, 8))) {
                    for (g in group) p.setRadio(DTC_RBN[g], false)
                    for (g in group) if (rb[g] == '1') p.setRadio(DTC_RBN[g], true)
                }
                model.minorPart = col("in_sminor").toInt(); model.build = col("in_sbuild").toInt()
                val saved = p.saveDtc(text)
                expect("s_threw", if (saved.threw) "1" else "0")
                expectFile("out_text", saved.text)
                for ((k, v) in dtcDump(model, "s_")) expect(k, v)
                expectPage(p, "p_save")
            }
            else -> continue
        }
        if (!judged) { notJudged++; continue }
        var rowBad = false
        for ((name, w) in want) {
            if (kind == "cart" && staleRow && !name.startsWith("m_")) continue
            cells++
            val g = got[name] ?: ""
            if (g != w) {
                // a whole model column the allow-list names (a D-number that moves a table, not one property)
                if (allowed("$kind.$name", "*")) continue
                // the same P2 in the model: the load-error bits differ by the Open 2 (Harpoon) part alone
                if (name == "m_err" && (g.toIntOrNull() ?: 0) xor (w.toIntOrNull() ?: 0) == 32) { expected["P2"] = (expected["P2"] ?: 0) + 1; continue }
                rowBad = true
                badByCol["$kind.$name"] = (badByCol["$kind.$name"] ?: 0) + 1
                if (nExamples < 40) {
                    nExamples++
                    examples.appendLine("row ${ln + 1} [$kind] $name")
                    for (c in head) if (c.startsWith("in_") && c != "in_text" && col(c).isNotEmpty()) examples.appendLine("    $c = ${col(c).take(400)}")
                    // the page and file columns already hold only the properties that differ: show them all, so
                    // each can be put to its D-number; a model column is the whole dump, so from where it first differs
                    val whole = name.startsWith("p") || name == "out_text"
                    val (a, b) = if (whole) w.take(6000) to g.take(6000) else dtcFirstDiff(w, g)
                    examples.appendLine("    want: $a")
                    examples.appendLine("    got:  $b")
                }
            }
        }
        if (rowBad) badRows++
    }
    report.appendLine("rows by kind: " + counts.entries.joinToString { "${it.key} ${it.value}" })
    report.appendLine("cart rows not judged (read or saved as a BMS older than 4.38 build 25688): $notJudged")
    report.appendLine("cart rows judged on their reading only (WDP threw half way over a page an older session had filled): $notJudgedStale")
    report.appendLine("cells compared: $cells")
    if (expected.isNotEmpty()) {
        report.appendLine("expected differences (the allow-list), by D-number:")
        for ((k, v) in expected.entries.sortedBy { it.key }) report.appendLine("  $k: $v  ${allow.why(k)}")
    }
    if (badByCol.isNotEmpty()) {
        report.appendLine("mismatches by column:")
        for ((k, v) in badByCol.entries.sortedByDescending { it.value }) report.appendLine("  $k: $v")
        report.appendLine()
        report.append(examples)
    }
    report.appendLine()
    val total = counts.values.sum() - (counts["kind"] ?: 0) - notJudged
    report.appendLine(
        if (badRows == 0) "PASS — $total rows judged, $cells cells identical to the program or different as the allow-list says"
        else "FAIL — $badRows of $total judged rows differ",
    )
    return report.toString()
}

/** A session read or saved as 4.38 build 25688 or later: the only BMS the Planner reads and writes. */
private fun dtcIs4381(minor: String, build: String): Boolean = (minor.toIntOrNull() ?: 0) >= 38 && (build.toIntOrNull() ?: 0) >= 25688

/** The app's ALIC codes as the page's HARM lists show them (what the product gives the page). */
internal fun dtcAppHarmCodes(): List<DtcHarmCode> = runBlocking { DtcWiring.harmCodesOf(Repo.harm()?.alicCodes.orEmpty()) }

/** Every key of an INI text as "Section/Key" → value, first of each (as the profile API reads them). */
private fun dtcKeys(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var section = ""
    for (raw in text.split('\n')) {
        val s = raw.trimEnd('\r').trim()
        if (s.startsWith("[")) { section = s.substring(1).substringBefore(']').trim(); continue }
        val eq = s.indexOf('=')
        if (eq <= 0 || s.startsWith(";")) continue
        val k = section + "/" + s.substring(0, eq).trim()
        if (k !in out) out[k] = s.substring(eq + 1).trim()
    }
    return out
}

/**
 * How WDP's value [a] and the port's [b] of one key differ, when they differ only in ways the Planner means:
 * "same" (the same floats), "D17" (a number WDP printed with seven significant digits, the port exactly),
 * "P1" (the port keeps the name BMS gave a steerpoint, which WDP dropped), "P3" (a weapon target keeps the name the
 * file gave it, where WDP's steerpoint reader had reset it to "Not set"), or null for any other difference.
 */
private fun dtcValueDiff(a: String, b: String, key: String = ""): String? {
    var x = a.split(',').map { it.trim() }
    var y = b.split(',').map { it.trim() }
    var named = false
    if (y.size == x.size + 1 && x.size == 4) { y = y.dropLast(1); named = true }
    var kept = false
    if (key.startsWith("STPT/wpntarget_") && x.size == 5 && x[4] == "Not set" && (y.size == 4 || (y.size == 5 && y[4] != "Not set"))) {
        x = x.dropLast(1); if (y.size == 5) y = y.dropLast(1); kept = true
    }
    if (x.size != y.size) return null
    var seven = false
    for (i in x.indices) {
        if (x[i] == y[i]) continue
        val p = x[i].toDoubleOrNull() ?: return null
        val q = y[i].toDoubleOrNull() ?: return null
        if (p.toFloat() == q.toFloat()) continue
        // a NaN or an infinity (a value WDP computed from nothing) is never one of the expected forms
        if (!p.isFinite() || !q.isFinite()) return null
        if (q != 0.0 && java.math.BigDecimal(q).round(java.math.MathContext(7)).toDouble() == p) { seven = true; continue }
        return null
    }
    return if (kept) "P3" else if (named) "P1" else if (seven) "D17" else "same"
}

/**
 * The comparison's allow-list, `resources/wdp/expected-diffs/dtc.txt`: one line per expected difference —
 * `<D-number> <column glob> <property or Section/Key glob, * for the whole column> <why>` — `#` starts a comment.
 */
private class DtcAllow(private val entries: List<Entry>) {
    class Entry(val id: String, val column: Regex, val prop: Regex, val why: String)

    val size get() = entries.size
    fun match(column: String, prop: String): String? = entries.firstOrNull { it.column.matches(column) && it.prop.matches(prop) }?.id
    fun why(id: String): String = entries.firstOrNull { it.id == id }?.why.orEmpty()

    companion object {
        fun load(): DtcAllow {
            val text = DtcAllow::class.java.getResourceAsStream("/wdp/expected-diffs/dtc.txt")?.use { it.readBytes().decodeToString() }
                ?: listOf(File("desktop/src/main/resources/wdp/expected-diffs/dtc.txt"), File("src/main/resources/wdp/expected-diffs/dtc.txt"))
                    .firstOrNull { it.isFile }?.readText()
                ?: ""
            val out = ArrayList<Entry>()
            for (raw in text.lines()) {
                val l = raw.substringBefore('#').trim()
                if (l.isEmpty()) continue
                val f = l.split(Regex("\\s+"), limit = 4)
                if (f.size < 3) continue
                out += Entry(f[0], glob(f[1]), glob(f[2]), f.getOrNull(3).orEmpty())
            }
            return DtcAllow(out)
        }

        private fun glob(g: String): Regex = Regex(g.map { c ->
            when (c) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(c.toString()) }
        }.joinToString(""))
    }
}

// ================================================================ the Falcon BMS 4.38.1 cases

/**
 * What the Planner does differently from WDP, checked on a copy of a real 4.38.1 cartridge, in memory: the file is
 * read once and never written. [cart] may sit beside copies of BMS's `*_Def.ini` (for the Load buttons' "BMS
 * default"); without them that case is skipped and says so.
 */
internal fun dtcCartridgeCases(cart: File): String = buildString {
    var failures = 0
    fun check(what: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        appendLine("${if (ok) "ok  " else "FAIL"} $what" + if (!ok && detail.isNotEmpty()) "  [$detail]" else "")
    }
    appendLine("DTC page — the Falcon BMS 4.38.1 cases")
    if (!cart.isFile) { appendLine("FAIL — no cartridge at ${cart.path}"); return@buildString }
    // nothing here writes, but a real install is not even read as a test fixture: point this at a copy
    var up: File? = cart.absoluteFile.parentFile
    repeat(5) { if (up != null && File(up, "Bin/x64/Falcon BMS.exe").isFile) { appendLine("REFUSED: ${cart.path} is inside a real Falcon BMS install. Copy it first."); return@buildString }; up = up?.parentFile }
    val original = cart.readText(Charsets.ISO_8859_1)
    val harm = dtcAppHarmCodes()
    appendLine("cartridge: ${cart.name} (${original.split("\r\n").size} lines), HARM codes from the app: ${harm.size}")

    fun page(text: String) = DtcPage().also { p -> p.harmCodes = harm; p.load(); p.getCampFile(text, cart.name) }
    /** The keys the page's edits change, as the product finds them. */
    fun edits(text: String, p: DtcPage): List<CartridgeEdit> {
        val base = page(text)
        return DtcEdits.between(DtcSave.saveCallsign(text, base.m) { base.applyHarm() }.text, DtcSave.saveCallsign(text, p.m) { p.applyHarm() }.text)
    }
    fun names(e: List<CartridgeEdit>) = e.joinToString { "[${it.section}] ${it.key}=${it.value}" }
    fun section(text: String, name: String): List<String> {
        val out = ArrayList<String>(); var inIt = false
        for (l in text.split("\r\n")) {
            if (l.trim().startsWith("[")) { inIt = l.trim().equals("[$name]", ignoreCase = true); if (inIt) out += l; continue }
            if (inIt) out += l
        }
        return out
    }

    // ---- D11: ILS below 109.00
    appendLine(); appendLine("D11 — ILS frequencies below 109.00")
    run {
        val t = DtcIni.write(original, "COMMS", "ILS Frequency", "10870")
        val p = page(t)
        check("ILS Frequency=10870 loads as 108.70 (WDP: 109.00)", p.c("mxtILS_FREQ").text == "108.70" && p.m.comm.ilsFrequency == 10870, p.c("mxtILS_FREQ").text)
        p.type("mxtBingo_Chaff", "4")
        val e = edits(t, p)
        check("an unrelated edit leaves it alone: ${names(e)}", e.none { it.key.equals("ILS Frequency", true) })
        check("the whole-page save writes 10870 back", DtcIni.read(DtcSave.saveCallsign(t, p.m) { p.applyHarm() }.text, "COMMS", "ILS Frequency") == "10870")
        p.type("mxtILS_FREQ", "108.10")
        check("108.10 typed is written 10810", edits(t, p).firstOrNull { it.key == "ILS Frequency" }?.value == "10810")
        p.type("mxtILS_FREQ", "112.50")
        check("112.50 typed is kept to the band: 111.95, and the box says so", p.m.comm.ilsFrequency == 11195 && p.c("mxtILS_FREQ").text == "111.95", p.c("mxtILS_FREQ").text)
        val noIls = DtcIni.deleteKey(original, "COMMS", "ILS Frequency")
        val ils1 = DtcIni.read(noIls, "Radio", "ILS_1")
        if (ils1.isNotEmpty()) {
            val q = page(noIls)
            check("with no ILS Frequency the box starts from BMS's ILS_1 ($ils1)", q.m.comm.ilsFrequency == ils1.toInt(), q.c("mxtILS_FREQ").text)
            check("… which is not an edit", edits(noIls, q).isEmpty(), names(edits(noIls, q)))
        } else appendLine("     (this cartridge has no [Radio] ILS_1: that case is skipped)")
    }

    // ---- D13: IFF codes keep their leading zeros
    appendLine(); appendLine("D13 — IFF codes keep their leading zeros")
    run {
        val t = DtcIni.write(DtcIni.write(original, "IFF", "Mode3A Code", "0554"), "IFF", "TIME 11 Mode1 Code", "03")
        val p = page(t)
        p.number("numMode3A_D4", "5")
        val e = edits(t, p)
        check("Mode 3A 0554 edited to 0555 is written with four digits: ${names(e)}", e.singleOrNull { it.key == "Mode3A Code" }?.value == "0555" && e.size == 1)
        val whole = DtcSave.saveCallsign(t, p.m) { p.applyHarm() }.text
        check("TIME 11 Mode1 Code=03 comes back 03 (WDP: 3)", DtcIni.read(whole, "IFF", "TIME 11 Mode1 Code") == "03")
        check("Mode1 Code keeps two digits", DtcIni.read(whole, "IFF", "Mode1 Code").length == 2, DtcIni.read(whole, "IFF", "Mode1 Code"))
        val def = "[IFF]\r\nMode1 Code=00\r\nMode2 Code=0000\r\nMode3A Code=1200\r\nTIME 0 Criteria=-1\r\nPOS 0 Mode1=1\r\n"
        val m = DtcModel()
        val r = DtcLoad.loadCallsign(def, m)
        check("BMS's IFF_Def.ini (criteria -1) reads whole: the position events are loaded (WDP stopped at -1)",
            (r.loadError and 16384) == 0 && m.iff.posSettings!![0].mode1.toInt() == 1 && m.iff.timeSettings!![0].timeCriteria == -1L)
        val w = DtcSave.saveCallsign(def, m).text
        check("… and written back as BMS wrote it: 00 / 0000 / 1200 / -1",
            DtcIni.read(w, "IFF", "Mode1 Code") == "00" && DtcIni.read(w, "IFF", "Mode2 Code") == "0000" &&
                DtcIni.read(w, "IFF", "Mode3A Code") == "1200" && DtcIni.read(w, "IFF", "TIME 0 Criteria") == "-1")
    }

    // ---- A1 / D12: the HARM lists
    appendLine(); appendLine("A1 / D12 — HARM codes from the app's list")
    run {
        var t = original
        for ((k, v) in listOf("THREAT 0 0" to "0608", "THREAT 0 1" to "0693", "THREAT 0 2" to "0220", "THREAT 0 3" to "0620", "THREAT 0 4" to "0999")) t = DtcIni.write(t, "HARM", k, v)
        val p = page(t)
        fun shows(h: Int) = (p.c("cboTbl1_Thr$h").selectedItem ?: "") + " | " + p.c("lblTbl1_Thr$h").text
        check("0608 shows SA-8, symbol 8", shows(1) == "SA-8 | 8", shows(1))
        check("0693 shows Patriot, symbol P (WDP: Hawk, 230 for both)", shows(2).startsWith("Patriot") && shows(2).endsWith("| P"), shows(2))
        check("0220 shows SA-20 Tombstone, symbol 20T (absent from WDP)", shows(3) == "SA-20 Tombstone | 20T", shows(3))
        check("0620 (Clam Shell, no symbol) shows its name and an empty symbol", shows(4).startsWith("SA-20 Clam Shell") && shows(4).endsWith("| "), shows(4))
        check("0999, in no list, shows as its code (WDP: Not in List)", shows(5) == "Code 0999 | ", shows(5))
        p.type("mxtBingo_Chaff", "4")
        check("an unrelated edit writes no HARM key", edits(t, p).none { it.section.equals("HARM", true) }, names(edits(t, p)))
        val whole = DtcSave.saveCallsign(t, p.m) { p.applyHarm() }.text
        check("all five round-trip, four digits each",
            listOf("0608", "0693", "0220", "0620", "0999") == (0..4).map { DtcIni.read(whole, "HARM", "THREAT 0 $it") })
        val chair = p.c("cboTbl1_Thr5").items.indexOf("SA-17 Chair Back")
        p.choose("cboTbl1_Thr5", chair)
        check("choosing SA-17 Chair Back writes 0217 (the tracking radar; 0117 is the whole system)",
            edits(t, p).firstOrNull { it.key == "THREAT 0 4" }?.value == "0217" && p.c("lblTbl1_Thr5").text == "17T")
        val codeItem = p.c("cboTbl1_Thr5").items.indexOf("Code 0999")
        p.choose("cboTbl1_Thr5", codeItem)
        check("choosing \"Code 0999\" again keeps 0999", edits(t, p).none { it.key == "THREAT 0 4" })
    }

    // ---- D16: steerpoints 90-99
    appendLine(); appendLine("D16 — STPT 90-99 (forum #2150: 99 became 98)")
    run {
        var t = original
        for (n in 89..98) t = DtcIni.write(t, "STPT", "target_$n", "${1000000 + n * 1000}.250000, ${2000000 + n * 1000}.750000, -${n * 10}.000000, 1, Point $n")
        val p = page(t)
        check("all ten read into Open 2 in order", (0..9).all { p.m.hpn[it].target == "Point ${89 + it}" }, (0..9).joinToString { p.m.hpn[it].target.orEmpty() })
        p.m.hpn[9].falconY += 100f
        val e = edits(t, p)
        check("moving STPT 99 changes target_98 only: ${names(e)}", e.size == 1 && e[0].key == "target_98")
        val back = DtcEdits.apply(t, e)
        check("… and the other nine are byte for byte where they were", (89..97).all { DtcIni.read(back, "STPT", "target_$it") == DtcIni.read(t, "STPT", "target_$it") })
        val whole = DtcSave.saveCallsign(t, page(t).m).text
        check("a whole save writes each at its own key, names kept, positions exact (D17)",
            (89..98).all { DtcIni.read(whole, "STPT", "target_$it") == DtcIni.read(t, "STPT", "target_$it") },
            (89..98).firstOrNull { DtcIni.read(whole, "STPT", "target_$it") != DtcIni.read(t, "STPT", "target_$it") }?.let { "target_$it: ${DtcIni.read(whole, "STPT", "target_$it")}" }.orEmpty())
    }

    // ---- E1: 4.38.1's sections untouched
    appendLine(); appendLine("E1 — [LINK16], [MAP_POP] and [Radio] ILS_1..4 survive a save byte for byte")
    run {
        val p = page(original)
        p.type("mxtBingo_Chaff", "4")
        p.number("numMode3A_D4", "7")
        p.choose("cboAG1_Left", p.c("cboAG1_Left").items.indexOf("HSD"))
        p.comm("btnUHF_3", "251.000")
        p.choose("cboTbl2_Thr1", p.c("cboTbl2_Thr1").items.indexOf("SA-11"))
        val e = edits(original, p)
        appendLine("     edits: ${names(e)}")
        val after = DtcEdits.apply(original, e)
        for (s in listOf("LINK16", "MAP_POP")) {
            val before = section(original, s)
            check("[$s] (${before.size} lines) is byte for byte the same", before.isNotEmpty() && before == section(after, s), if (before.isEmpty()) "not in this cartridge" else "")
        }
        val ilsKeys = (1..4).flatMap { listOf("ILS_$it", "ILS_COMMENT_$it") }
        check("[Radio] ILS_1..4 and their comments are the same, and no edit names them",
            ilsKeys.all { DtcIni.read(after, "Radio", it) == DtcIni.read(original, "Radio", it) } && e.none { it.key in ilsKeys })
        val bl = original.split("\r\n"); val al = after.split("\r\n")
        check("the file keeps its line count (${bl.size}) and only the edited keys' lines moved",
            bl.size == al.size && bl.indices.count { bl[it] != al[it] } == e.size, "${bl.indices.count { bl[it] != al[it] }} lines for ${e.size} edits")
    }

    // ---- nav offsets on steerpoint 0
    appendLine(); appendLine("NAV OFFSETS — no offset aim point for steerpoint 0")
    run {
        val p = page(original)
        p.m.nav.oa1_1.stpt = 5; p.m.nav.oa1_1.bearing = 90f; p.m.nav.oa1_1.range = 3000
        p.m.nav.oa2_1.stpt = 0; p.m.nav.oa1_2.stpt = 0; p.m.nav.oa2_2.stpt = 0
        val whole = DtcSave.saveCallsign(original, p.m).text
        check("OA1-5 is written, OA1-0 / OA2-0 are not (WDP wrote both)",
            DtcIni.read(whole, "NAV OFFSETS", "OA1-5").isNotEmpty() && DtcIni.read(whole, "NAV OFFSETS", "OA1-0").isEmpty() && DtcIni.read(whole, "NAV OFFSETS", "OA2-0").isEmpty())
    }

    // ---- D14: the MFD rule on all 72 selectors
    appendLine(); appendLine("D14 — the MFD selectors: one rule, all 72")
    run {
        val p = page(original)
        val all = DtcTables.MFD_SELECTORS
        for (s in all) p.choose(s.name, 0)
        val hsd = p.c(all[0].name).items.indexOf("HSD")
        var cases = 0; var bad = 0; var wdpDiffers = 0
        var firstBad = ""
        for (s in all) {
            val other = all.first { it.mode == (s.mode + 1) % 6 && it.mfd == s.mfd && it.pos == s.pos }
            for (o in all.filter { it.mode == s.mode && it.name != s.name }) {
                cases++
                p.choose(other.name, hsd)
                p.choose(o.name, hsd)
                val before = all.associate { it.name to p.c(it.name).selectedIndex }
                p.choose(s.name, hsd)
                val ok = p.c(o.name).selectedIndex == 0 && p.c(s.name).selectedIndex == hsd && p.c(other.name).selectedIndex == hsd &&
                    all.filter { it.name != o.name && it.name != s.name }.all { p.c(it.name).selectedIndex == before[it.name] }
                if (!ok) { bad++; if (firstBad.isEmpty()) firstBad = "${s.name} over ${o.name}" }
                // what WDP's own handler would have done: blank o only if one of its tests compares o with s
                val wdpBlanks = s.clears.any { (target, with) -> target == o.name && (with == s.name || before[with] == hsd) }
                if (!wdpBlanks) wdpDiffers++
                p.choose(s.name, 0); p.choose(o.name, 0); p.choose(other.name, 0)
            }
        }
        check("choosing a page blanks the other box of the same master mode showing it, and nothing else ($cases cases)", bad == 0, "$bad bad, first $firstBad")
        appendLine("     WDP's own handlers would have done otherwise in $wdpDiffers of those $cases cases")
        val m = p.m.mfd
        p.choose("cboNAV2_Center", p.c("cboNAV2_Center").items.indexOf("TCN"))
        check("the model follows the boxes (NAV MFD 2 centre = TCN 9)", m.nav!![1].center == 9, m.nav!![1].center.toString())
    }

    // ---- the page, as the pilot uses it (the wiring, over a stand-in for the PC)
    appendLine(); appendLine("The page's wiring")
    dtcWiringCases(original, cart.parentFile, ::check, this)

    appendLine()
    appendLine(if (failures == 0) "PASS" else "FAIL — $failures check(s)")
}

/** A stand-in for the PC: the cartridge in memory, a second pilot with no file, BMS's defaults from [defaults]. */
private class DtcCaseSource(var text: String, private val defaults: File?) : DtcSource {
    var modified = 1L
    val saved = ArrayList<List<CartridgeEdit>>()
    private fun state(message: String? = null) = CartridgeState(
        available = true, callsign = "Case", file = "Case.ini", text = text, modified = modified,
        path = "C:\\Falcon BMS\\User\\Config\\Case.ini", message = message,
    )
    override suspend fun load(callsign: String?): CartridgeState =
        if (callsign != null && !callsign.equals("Case", ignoreCase = true)) CartridgeState(
            available = false, callsign = callsign, file = "$callsign.ini",
            error = "There is no cartridge for $callsign in User\\Config yet: select that pilot in Falcon BMS and save the DTC once.",
        ) else state()
    override suspend fun save(callsign: String?, edits: List<CartridgeEdit>): CartridgeState {
        saved += edits
        text = DtcEdits.apply(text, edits)
        modified++
        return state("Case.ini saved.")
    }
    override suspend fun bmsDefaults() = BmsDefaultsFiles.read(defaults)
    override suspend fun harmCodes() = dtcAppHarmCodes()
}

private fun dtcWiringCases(original: String, dir: File?, check: (String, Boolean, String) -> Unit, out: StringBuilder) {
    fun top(): Any? = WdpDialogs.stack.lastOrNull()
    fun answer(a: String) { (top() as? WdpMessage)?.let { m -> WdpDialogs.stack.remove(m); m.onAnswer?.invoke(a) } }
    // a Load opens WDP's file window on the PC first (none here: it answers "no file" on the main thread a moment
    // later), and only then offers BMS's default: wait for the question rather than look at once
    fun awaitMessage(ms: Long = 10_000) { val t0 = System.currentTimeMillis(); while (top() !is WdpMessage && System.currentTimeMillis() - t0 < ms) Thread.sleep(20) }
    val briefing = Briefing(comms = listOf(
        CommEntry(agency = "Base Ops", uhf = "382.500 MHz", uhfCh = 1),
        CommEntry(agency = "Dep Tower", uhf = "362.400 MHz", uhfCh = 3, vhf = "120.550 MHz", vhfCh = 3),
    ))
    val src = DtcCaseSource(original, dir)
    WdpDialogs.stack.clear()
    val w = DtcWiring(src)
    val mission = WdpMission(briefing = briefing, dtc = Dtc(modified = 1))
    w.onMission(mission)
    fun v(name: String) = w.values(emptyList()).values[name]
    check("the page opens on the cartridge", v("lblCampLoaded.text") != "Data NOT Loaded" && v("lblPilotName.text") == "Case", v("lblCampLoaded.text").orEmpty())

    // what the app does not do is not on the page
    val hiddenOk = DtcWiring.REMOVED.all { v(it) == "hidden" }
    check("TE side, Save List as jpg and Save PPT.ini are off the page (${DtcWiring.REMOVED.size} controls)", hiddenOk,
        DtcWiring.REMOVED.filter { v(it) != "hidden" }.joinToString())

    // §15-1: BMS rewrites the file and the page takes it quietly: the status line must not stick on "Loading…"
    src.modified = 5
    w.onMission(mission.copy(dtc = Dtc(modified = 5)))
    check("§15-1 after a quiet reload the status line is not stuck on loading", v("lblLoadError.text")?.contains("Loading") == false, v("lblLoadError.text").orEmpty())

    // §15-2: a partly typed ILS frequency
    WdpDialogs.stack.clear()
    val ilsBefore = v("mxtILS_FREQ.text")
    w.onValue("mxtILS_FREQ", "1"); w.onValue("mxtILS_FREQ.leave", "")
    check("§15-2 a partly typed ILS frequency leaves the box as it was, with no error box", top() == null && v("mxtILS_FREQ.text") == ilsBefore,
        (top() as? WdpMessage)?.text.orEmpty() + " box " + v("mxtILS_FREQ.text"))

    // §15-3: another pilot with no cartridge
    w.onClick("btnPilot"); w.onValue("txtPilot", "Nobody"); w.onClick("btnPilot")
    val msg = (top() as? WdpMessage)?.text.orEmpty()
    check("§15-3 a pilot with no cartridge: \"not found\", and the page keeps the pilot it had", msg.contains("Nobody not found") && v("lblPilotName.text") == "Case", msg.take(80))
    WdpDialogs.stack.clear()

    // Default = BMS's comm plan
    w.onClick("btnDefault")
    val u1 = v("lblUHF_Val_1.text"); val u3 = v("lblUHF_Val_3.text"); val c3 = v("txtUHF_3.text"); val vh3 = v("lblVHF_Val_3.text")
    check("Default fills the briefing's comm plan: UHF 1 382.500, UHF 3 362.400 \"DEP Tower\", VHF 3 120.550", u1 == "382.500" && u3 == "362.400" && c3 == "DEP Tower" && vh3 == "120.550", "$u1 $u3 $c3 $vh3")
    WdpDialogs.stack.clear()

    // Load → BMS default (MFD_Def.ini), when copies of BMS's defaults sit beside the cartridge
    if (dir != null && File(dir, "MFD_Def.ini").isFile) {
        w.onClick("btnLoad_MFD"); awaitMessage()
        val offered = (top() as? WdpMessage)?.buttons.orEmpty()
        answer("BMS default")
        check("Load on the MFD tab offers BMS's own MFD_Def.ini and loads it (left MFD A-G: FCR, FLCS, TEST)",
            "BMS default" in offered && v("cboAG1_Left") == "FCR" && v("cboAG1_Center") == "FLCS" && v("cboAG1_Right") == "TEST",
            "${v("cboAG1_Left")} ${v("cboAG1_Center")} ${v("cboAG1_Right")} offered $offered")
        WdpDialogs.stack.clear()
        w.onClick("btnHarm_Load"); awaitMessage(); answer("BMS default")
        check("… and on the HARM tab, HARM_Def.ini (table 1 first: 0202, SA-2 Fan Song)", v("numTbl1_Thr1") == "202" && v("cboTbl1_Thr1") == "SA-2 Fan Song", "${v("numTbl1_Thr1")} ${v("cboTbl1_Thr1")}")
        WdpDialogs.stack.clear()
    } else out.appendLine("     (no MFD_Def.ini beside the cartridge: the BMS-default case is skipped)")

    // the DataCard's entries through the DTC page
    val said = runBlocking {
        WdpCartridge.writeCardEntries!!(WdpCartridge.CardEntries(alowAglFt = 500, laserTgp = 1511, ewsProgramNames = listOf("Slapshot"),
            profile1 = WdpCartridge.BombProfile(submode = "CCIP", releaseAngleDeg = 30)), false)
    }
    check("the card's entries reach the DTC page's boxes: $said",
        v("mxtALOW_AGL.text") == "500" && v("mxtLaserCode.text") == "1511" && v("txtComment1.text") == "Slapshot" && v("cboP1_SubMode") == "CCIP" && v("mxtP1_Angle.text")?.trim() == "30",
        "${v("mxtALOW_AGL.text")} ${v("mxtLaserCode.text")} ${v("txtComment1.text")} ${v("cboP1_SubMode")} ${v("mxtP1_Angle.text")}")

    // the card's Re-read DTC from BMS (WDP's Get DTC File) with edits on the page asks first (§15-14)
    WdpDialogs.stack.clear()
    val r = runBlocking { WdpCartridge.reload!!() }
    check("Get DTC File with unsaved edits asks first and drops nothing: $r", (top() as? WdpMessage)?.title == "Re-read DTC from BMS" && v("mxtLaserCode.text") == "1511", "")
    answer("Cancel")

    // another flight given with those edits not saved: asked (WDP re-reads the cartridge for every flight it is given),
    // and Keep the changes keeps them for it (docs/DATA-STORES.md); the same flight again asks nothing
    WdpDialogs.stack.clear()
    val other = mission.copy(briefing = briefing.copy(overview = com.bmscompanion.app.data.mission.BriefOverview(flight = "Other1")))
    w.onMission(other)
    check("another flight with unsaved DTC edits asks Read the cartridge / Keep the changes, and drops nothing yet",
        (top() as? WdpMessage)?.title == "Another flight" && v("mxtLaserCode.text") == "1511", (top() as? WdpMessage)?.title.orEmpty())
    answer("Keep the changes")
    WdpDialogs.stack.clear()
    w.onMission(other.copy(dtc = Dtc(modified = 5)))
    check("Keep the changes keeps them, and the same flight again asks nothing", top() == null && v("mxtLaserCode.text") == "1511", "")
    w.onMission(mission.copy(dtc = Dtc(modified = 5)))
    WdpDialogs.stack.clear()

    // and the save writes exactly those (the Planner's Save to DTC: the SYSTEMS tab's own Save DTC is From mission… now)
    WdpDialogs.stack.clear()
    w.saveToDtc(null)
    val keys = src.saved.lastOrNull().orEmpty().map { "[${it.section}] ${it.key}" }
    val wanted = listOf("[ICP] Alow AGL", "[Laser] LaserTGP", "[EWS] PGM 0 Comment", "[FCC_AGB] Profile1_Submode", "[FCC_AGB] Profile1_Release_Angle")
    check("Save writes the card's entries with the Default's presets, and nothing of [LINK16]/[MAP_POP]: $keys",
        wanted.all { it in keys } && keys.none { it.startsWith("[LINK16]") || it.startsWith("[MAP_POP]") }, "")
    WdpDialogs.stack.clear()
}

private val DTC_RBN = listOf("rbnHAS", "rbnPOS", "rbnPB", "rbnEom", "rbnRuk", "rbnTbl1", "rbnTbl2", "rbnTbl3", "rbnTbl0")

/** A page dump as the harness writes it: `name.P=value` entries separated by U+0001. */
private fun dtcParseDump(s: String): Map<String, String> {
    val r = HashMap<String, String>()
    for (e in s.split('\u0001')) {
        if (e.isEmpty()) continue
        val eq = e.indexOf('=')
        r[e.substring(0, eq)] = e.substring(eq + 1)
    }
    return r
}

/** The port's page in the harness's form (texts, items and cells escaped as the harness escapes them). */
private fun dtcPageDump(p: DtcPage): Map<String, String> {
    val r = LinkedHashMap<String, String>()
    for ((k, v) in p.dump()) {
        r[k] = when {
            k.endsWith(".T") -> dtcEsc(v)
            k.endsWith(".L") -> if (v.isEmpty()) "" else v.split('\u0002').joinToString("\u0002") { dtcEsc(it) }
            k.endsWith(".G") -> if (v.isEmpty()) "" else v.split('\u0002').joinToString("\u0002") { row -> row.split('\u0003').joinToString("\u0003") { if (it == "\u0004") it else dtcEsc(it) } }
            else -> v
        }
    }
    return r
}

/** The two strings from a little before where they first differ. */
private fun dtcFirstDiff(a: String, b: String): Pair<String, String> {
    var i = 0
    while (i < a.length && i < b.length && a[i] == b[i]) i++
    val from = maxOf(0, i - 80)
    return ("…" + a.substring(from, minOf(a.length, i + 200))) to ("…" + b.substring(from, minOf(b.length, i + 200)))
}

internal fun dtcUnesc(s: String): String? {
    if (s == "\\0") return null
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                '\\' -> sb.append('\\'); 'r' -> sb.append('\r'); 'n' -> sb.append('\n'); 't' -> sb.append('\t')
                else -> { sb.append(c); sb.append(s[i + 1]) }
            }
            i += 2
        } else { sb.append(c); i++ }
    }
    return sb.toString()
}

internal fun dtcEsc(s: String?): String {
    if (s == null) return "\\0"
    val sb = StringBuilder()
    for (c in s) when (c) {
        '\\' -> sb.append("\\\\"); '\r' -> sb.append("\\r"); '\n' -> sb.append("\\n"); '\t' -> sb.append("\\t"); else -> sb.append(c)
    }
    return sb.toString()
}

internal fun dtcTheater(name: String): PopupCoords.CoordData = when (name) {
    "old1" -> PopupCoords.CoordData(34.0, 124.0, 3358699.5, 3358699.5, false)
    "old2" -> PopupCoords.CoordData(-53.5, -62.0, 2000000.0, 2500000.0, false)
    "new1" -> PopupCoords.CoordData(0.0, 0.0, 3358699.5, 3358699.5, true, PopupCoords.TransverseMercatorMeta(127.5, -512000.0, 3700000.0, 1024000L, 16384f, 62.5f, 1f / (62.5f * 3.27998f), 62.5f * 3.27998f, 8192f))
    "new2" -> PopupCoords.CoordData(0.0, 0.0, 3358699.5, 3358699.5, true, PopupCoords.TransverseMercatorMeta(22.0, -400000.0, 4300000.0, 1024000L, 1024f, 1000f, 1f / 3279.98f, 3279.98f, 512f))
    else -> PopupCoords.CoordData()
}

/** `cntDTC.ApplyHarm` over the nine radio buttons' states (HAS, POS, PB, EOM, RUK, table 1, 2, 3, 0). */
internal fun dtcApplyHarm(m: DtcModel, rb: List<Boolean>) {
    m.harm.mode = if (rb[0]) 1 else if (rb[1]) 0 else 1
    m.harm.subMode = if (rb[2]) 0 else if (rb[3]) 1 else if (rb[4]) 2 else 0
    m.harm.ter = if (rb[5]) 0 else if (rb[6]) 1 else if (rb[7]) 2 else -1
}

private fun hexF(v: Float): String = if (v.isNaN()) "NaN" else v.toRawBits().toUInt().toString(16).padStart(8, '0')

/** The same serialisation the harness writes (field order, floats by bits, strings escaped, null arrays "null"). */
internal fun dtcDump(m: DtcModel, p: String): Map<String, String> {
    val r = LinkedHashMap<String, String>()
    fun s(v: String?) = dtcEsc(v)
    fun b(v: Boolean) = if (v) "1" else "0"
    fun <T> arr(a: Array<T>?, each: (T) -> String) = if (a == null) "null" else "${a.size}:" + a.joinToString(";") { each(it) }
    r[p + "stpt"] = arr(m.stpt) { listOf(hexF(it.falconX), hexF(it.falconY), hexF(it.falconZ), s(it.north), s(it.east), it.action.toString(), s(it.target)).joinToString("|") }
    r[p + "tgt"] = arr(m.tgt) { listOf(hexF(it.falconX), hexF(it.falconY), hexF(it.falconZ), s(it.north), s(it.east), it.action.toString(), s(it.target)).joinToString("|") }
    r[p + "ppt"] = arr(m.ppt) { listOf(s(it.name), hexF(it.falconX), hexF(it.falconY), hexF(it.falconZ), hexF(it.falconRng), s(it.code), s(it.north), s(it.east)).joinToString("|") }
    r[p + "line"] = arr(m.line) { listOf(hexF(it.falconX), hexF(it.falconY), hexF(it.falconZ), s(it.north), s(it.east)).joinToString("|") }
    r[p + "open"] = arr(m.open) { listOf(hexF(it.falconX), hexF(it.falconY), hexF(it.falconZ), s(it.north), s(it.east), it.action.toString(), s(it.target)).joinToString("|") }
    r[p + "hpn"] = arr(m.hpn) { listOf(hexF(it.falconX), hexF(it.falconY), hexF(it.falconZ), s(it.north), s(it.east), it.action.toString(), s(it.target)).joinToString("|") }
    val e = m.ews
    r[p + "ews"] = listOf(b(e.reqjam), b(e.reqctr), b(e.bingo), b(e.fdbk), e.flareBingo.toString(), e.chaffBingo.toString(), e.modeSelection.toString(), e.numberSelection.toString()).joinToString("|") +
        "#" + arr(e.program) { listOf(it.chaffBQ, it.chaffBI, it.chaffSQ, it.chaffSI, it.flareBQ, it.flareBI, it.flareSQ, it.flareSI).joinToString("|") + "|" + s(it.comment) }
    val md = m.mfd
    r[p + "mfd"] = listOf(md.aG, md.aA, md.nav, md.msl, md.dgf, md.sJ).joinToString("#") { a -> arr(a) { "${it.left}|${it.center}|${it.right}|${it.csel}" } }
    val rd = m.radio
    r[p + "radio"] = listOf(
        rd.uhf?.let { a -> "${a.size}:" + a.joinToString(";") } ?: "null",
        rd.vhf?.let { a -> "${a.size}:" + a.joinToString(";") } ?: "null",
        arr(rd.uhfComment) { s(it) }, arr(rd.vhfComment) { s(it) },
    ).joinToString("#")
    val c = m.comm
    r[p + "comm"] = listOf(c.comm1.toString(), c.comm2.toString(), c.tacanChannel.toString(), c.ilsFrequency.toString(), c.ilsCrs.toString(), c.tacanBand.toString(), c.tacanDomain.toString(), s(c.comm1Comment), s(c.comm2Comment)).joinToString("|")
    val nv = m.nav
    r[p + "nav"] = nv.modesel.toString() + "#" + listOf(nv.vip, nv.vipPup, nv.vrp, nv.vrpPup, nv.oa1_1, nv.oa2_1, nv.oa1_2, nv.oa2_2).joinToString("#") { "${it.stpt}|${hexF(it.bearing)}|${it.range}|${it.elv}" }
    val h = m.hud
    r[p + "hud"] = listOf(h.color, h.scales, h.brightness, h.fpm, h.ded, h.velocity, h.alt, h.symWheelPos).joinToString("|")
    val i = m.icp
    r[p + "icp"] = listOf(i.masterMode.toString(), hexF(i.alowAgl), i.alowMsl.toString(), i.alowTfAdv.toString(), hexF(i.manualWingspan), hexF(i.bingoFuel)).joinToString("|")
    val x = m.iff
    r[p + "iff"] = listOf(x.mode1On, x.mode2On, x.mode3aOn, x.mode4On, x.modeCOn, x.modeSOn, x.mode1Code.toInt(), x.mode2Code.toInt(), x.mode3aCode.toInt(), x.mode4Key.toInt(), x.autoChange).joinToString("|") +
        "#" + arr(x.timeSettings) { "${it.mode1Code}|${it.mode3aCode}|${it.mode4Key}|${it.timeCriteria.toULong()}" } +
        "#" + arr(x.posSettings) { listOf(it.mode1.toInt(), it.mode2.toInt(), it.mode3a.toInt(), it.mode4.toInt(), it.modeC.toInt(), it.modeS.toInt(), it.wayPoint, it.direction).joinToString("|") }
    r[p + "misc"] = listOf(b(m.bullseyeInfoOnMfd), m.wideView.toString(), m.otwMode.toString(), m.masterArm.toString(), m.ralt.toString(), m.dedLight.toString()).joinToString("|")
    r[p + "harm"] = arr(m.harm.table) { "${it.threat0}|${it.threat1}|${it.threat2}|${it.threat3}|${it.threat4}" } + "#" + "${m.harm.mode}|${m.harm.subMode}|${m.harm.ter}"
    r[p + "laser"] = "${m.laser.laserSt}|${m.laser.laserCode}|${m.laser.lstCode}"
    fun bomb(q: com.bmscompanion.app.data.wdp.DtcBombProfile) = listOf(q.submode.toString(), q.fuze.toString(), q.sglPair.toString(), q.releaseSpacing.toString(), q.releasePulse.toString(), q.releaseAngle.toString(), hexF(q.c1Ad1), hexF(q.c1Ad2), hexF(q.c2Ad), q.c2Ba.toString()).joinToString("|")
    r[p + "fcc"] = "${m.aim.spotScan}|${m.aim.tdBp}|${m.aim.targetSize}#${m.agm.mavAutoPwr}|${m.agm.mavAutoPwrDir}|${m.agm.mavAutoPwrWpt}#${bomb(m.agb1)}#${bomb(m.agb2)}"
    r[p + "incl"] = listOf(m.cmdsIncl, m.hudIncl, m.viewsIncl, m.masterArmIncl, m.snsrPowerIncl, m.intLghtIncl).joinToString("") { b(it) }
    return r
}

/** One edit the harness made by reflection, by the same path, on the port's model. */
internal fun dtcMutate(m: DtcModel, path: String, enc: String) {
    val parts = path.split('.')
    val tag = enc.substringBefore(':')
    val raw = enc.substringAfter(':', "")
    fun fl(): Float = if (raw == "NaN") Float.NaN else Float.fromBits(raw.toLong(16).toInt())
    fun i(): Int = raw.toInt()
    fun str(): String? = dtcUnesc(raw)
    fun bo(): Boolean = raw == "1"
    fun n(k: Int) = parts[k].toInt()
    if (tag == "null") {
        when (path) {
            "CampEWS.Program" -> m.ews.program = null
            "CampRadio.UHF" -> m.radio.uhf = null
            "CampRadio.VHFcomment" -> m.radio.vhfComment = null
            "CampIff.timeSettings" -> m.iff.timeSettings = null
            "CampHarm.Table" -> m.harm.table = null
            "CampMFD.S_J" -> m.mfd.sJ = null
            "CampMFD.A_G" -> m.mfd.aG = null
        }
        return
    }
    when (parts[0]) {
        "tblCampSTPT", "tblCampOpen", "tblCampHpn" -> {
            val t = when (parts[0]) { "tblCampSTPT" -> m.stpt; "tblCampOpen" -> m.open; else -> m.hpn }[n(1)]
            when (parts[2]) { "FalconX" -> t.falconX = fl(); "FalconY" -> t.falconY = fl(); "FalconZ" -> t.falconZ = fl(); "Action" -> t.action = i(); "Target" -> t.target = str() }
        }
        "tblCampPPT" -> {
            val t = m.ppt[n(1)]
            when (parts[2]) { "FalconX" -> t.falconX = fl(); "FalconY" -> t.falconY = fl(); "FalconZ" -> t.falconZ = fl(); "FalconRNG" -> t.falconRng = fl(); "Code" -> t.code = str() }
        }
        "tblCampLine" -> {
            val t = m.line[n(1)]
            when (parts[2]) { "FalconX" -> t.falconX = fl(); "FalconY" -> t.falconY = fl(); "FalconZ" -> t.falconZ = fl() }
        }
        "tblCampTgt" -> {
            val t = m.tgt[n(1)]
            when (parts[2]) { "FalconX" -> t.falconX = fl(); "FalconY" -> t.falconY = fl(); "FalconZ" -> t.falconZ = fl(); "Action" -> t.action = i(); "Target" -> t.target = str() }
        }
        "CampEWS" -> {
            val e = m.ews
            if (parts[1] == "Program") {
                val pr = e.program!![n(2)]
                when (parts[3]) {
                    "ChaffBQ" -> pr.chaffBQ = i(); "ChaffBI" -> pr.chaffBI = i(); "ChaffSQ" -> pr.chaffSQ = i(); "ChaffSI" -> pr.chaffSI = i()
                    "FlareBQ" -> pr.flareBQ = i(); "FlareBI" -> pr.flareBI = i(); "FlareSQ" -> pr.flareSQ = i(); "FlareSI" -> pr.flareSI = i()
                    "Comment" -> pr.comment = str()
                }
            } else when (parts[1]) {
                "Reqjam" -> e.reqjam = bo(); "Reqctr" -> e.reqctr = bo(); "Bingo" -> e.bingo = bo(); "Fdbk" -> e.fdbk = bo()
                "FlareBingo" -> e.flareBingo = i(); "ChaffBingo" -> e.chaffBingo = i()
                "ModeSelection" -> e.modeSelection = i(); "NumberSelection" -> e.numberSelection = i()
            }
        }
        "CampMFD" -> {
            val a = when (parts[1]) { "A_G" -> m.mfd.aG; "A_A" -> m.mfd.aA; "NAV" -> m.mfd.nav; "MSL" -> m.mfd.msl; "DGF" -> m.mfd.dgf; else -> m.mfd.sJ }!![n(2)]
            when (parts[3]) { "Left" -> a.left = i(); "Center" -> a.center = i(); "Right" -> a.right = i(); "Csel" -> a.csel = i() }
        }
        "CampRadio" -> when (parts[1]) {
            "UHF" -> m.radio.uhf!![n(2)] = i()
            "VHF" -> m.radio.vhf!![n(2)] = i()
            "UHFcomment" -> m.radio.uhfComment!![n(2)] = str()
            "VHFcomment" -> m.radio.vhfComment!![n(2)] = str()
        }
        "CampComm" -> {
            val c = m.comm
            when (parts[1]) {
                "Comm1" -> c.comm1 = i(); "Comm2" -> c.comm2 = i(); "TACANChannel" -> c.tacanChannel = i(); "ILSFrequency" -> c.ilsFrequency = i()
                "ILSCRS" -> c.ilsCrs = i(); "TACANBand" -> c.tacanBand = i(); "TACANDomain" -> c.tacanDomain = i()
                "Comm1_Comment" -> c.comm1Comment = str(); "Comm2_Comment" -> c.comm2Comment = str()
            }
        }
        "CampNavOffsets" -> {
            if (parts[1] == "Modesel") { m.nav.modesel = i(); return }
            val o = when (parts[1]) {
                "VIP" -> m.nav.vip; "VIPPUP" -> m.nav.vipPup; "VRP" -> m.nav.vrp; "VRPPUP" -> m.nav.vrpPup
                "OA1_1" -> m.nav.oa1_1; "OA2_1" -> m.nav.oa2_1; "OA1_2" -> m.nav.oa1_2; else -> m.nav.oa2_2
            }
            when (parts[2]) { "Stpt" -> o.stpt = i(); "Bearing" -> o.bearing = fl(); "Range" -> o.range = i(); "Elv" -> o.elv = i() }
        }
        "CampHud" -> {
            val h = m.hud
            when (parts[1]) {
                "Color" -> h.color = i(); "Scales" -> h.scales = i(); "Brightness" -> h.brightness = i(); "FPM" -> h.fpm = i()
                "DED" -> h.ded = i(); "Velocity" -> h.velocity = i(); "Alt" -> h.alt = i(); "SymWheelPos" -> h.symWheelPos = i()
            }
        }
        "CampICP" -> {
            val c = m.icp
            when (parts[1]) {
                "MasterMode" -> c.masterMode = i(); "AlowAGL" -> c.alowAgl = fl(); "AlowMSL" -> c.alowMsl = i(); "AlowTFAdv" -> c.alowTfAdv = i()
                "ManualWingspan" -> c.manualWingspan = fl(); "BingoFuel" -> c.bingoFuel = fl()
            }
        }
        "CampIff" -> {
            val x = m.iff
            when (parts[1]) {
                "timeSettings" -> {
                    val t = x.timeSettings!![n(2)]
                    when (parts[3]) {
                        "Mode1_Code" -> t.mode1Code = i(); "Mode3A_Code" -> t.mode3aCode = i().toShort(); "Mode4_Key" -> t.mode4Key = i()
                        "TimeCriteria" -> t.timeCriteria = raw.toULong().toLong()
                    }
                }
                "posSettings" -> {
                    val q = x.posSettings!![n(2)]
                    when (parts[3]) {
                        "Mode1" -> q.mode1 = i().toShort(); "Mode2" -> q.mode2 = i().toShort(); "Mode3A" -> q.mode3a = i().toShort()
                        "Mode4" -> q.mode4 = i().toShort(); "ModeC" -> q.modeC = i().toShort(); "ModeS" -> q.modeS = i().toShort()
                        "WayPoint" -> q.wayPoint = i(); "Direction" -> q.direction = i()
                    }
                }
                "Mode1_On" -> x.mode1On = i(); "Mode2_On" -> x.mode2On = i(); "Mode3A_On" -> x.mode3aOn = i(); "Mode4_On" -> x.mode4On = i()
                "ModeC_On" -> x.modeCOn = i(); "ModeS_On" -> x.modeSOn = i(); "AutoChange" -> x.autoChange = i()
                "Mode1_Code" -> x.mode1Code = i().toShort(); "Mode2_Code" -> x.mode2Code = i().toShort()
                "Mode3A_Code" -> x.mode3aCode = i().toShort(); "Mode4_Key" -> x.mode4Key = i().toShort()
            }
        }
        "CampView" -> m.wideView = i()
        "CampOTW" -> m.otwMode = i()
        "CampWeapons" -> m.masterArm = i()
        "CampSnsr" -> m.ralt = i()
        "CampLightning" -> m.dedLight = i()
        "CampLaser" -> when (parts[1]) { "LaserST" -> m.laser.laserSt = i(); "LaserCode" -> m.laser.laserCode = i().toShort(); "LSTCode" -> m.laser.lstCode = i().toShort() }
        "CampAIM" -> when (parts[1]) { "SpotScan" -> m.aim.spotScan = i(); "TD_BP" -> m.aim.tdBp = i(); "TargetSize" -> m.aim.targetSize = i() }
        "CampAGM" -> when (parts[1]) { "Mav_AutoPwr" -> m.agm.mavAutoPwr = i(); "Mav_AutoPwrDir" -> m.agm.mavAutoPwrDir = i(); "Mav_AutoPwrWpt" -> m.agm.mavAutoPwrWpt = i() }
        "CampHarm" -> {
            val t = m.harm.table!![n(2)]
            when (parts[3]) { "Threat_0" -> t.threat0 = i(); "Threat_1" -> t.threat1 = i(); "Threat_2" -> t.threat2 = i(); "Threat_3" -> t.threat3 = i(); "Threat_4" -> t.threat4 = i() }
        }
        "CampAGB" -> {
            val q = if (parts[1] == "Profile1") m.agb1 else m.agb2
            when (parts[2]) {
                "Submode" -> q.submode = i(); "Fuze" -> q.fuze = i(); "SGL_PAIR" -> q.sglPair = i(); "Release_Spacing" -> q.releaseSpacing = i()
                "Release_Pulse" -> q.releasePulse = i(); "Release_Angle" -> q.releaseAngle = i(); "C1_AD1" -> q.c1Ad1 = fl(); "C1_AD2" -> q.c1Ad2 = fl()
                "C2_AD" -> q.c2Ad = fl(); "C2_BA" -> q.c2Ba = i()
            }
        }
    }
}
