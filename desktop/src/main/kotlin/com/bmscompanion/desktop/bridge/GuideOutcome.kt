@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.screens.wdp.GuideBlock
import com.bmscompanion.app.ui.screens.wdp.GuideGo
import com.bmscompanion.app.ui.screens.wdp.GuideSection
import com.bmscompanion.app.ui.screens.wdp.PlannerGuide
import com.bmscompanion.app.ui.screens.wdp.PlannerGuideText
import com.bmscompanion.app.ui.screens.wdp.PlannerWindow
import com.bmscompanion.app.ui.screens.wdp.PlannerWindowHost
import com.bmscompanion.app.ui.screens.wdp.PlannerWindows
import com.bmscompanion.app.ui.screens.wdp.WdpPage
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.screens.wdp.WdpSession
import com.bmscompanion.app.ui.theme.Hud
import org.jetbrains.skia.EncodedImageFormat
import java.awt.event.KeyEvent as AwtKey
import java.io.File
import javax.swing.SwingUtilities

/**
 * The `guide` part of `--planneroutcome`: the Planner guide opens, every section is reachable, every button goes where
 * it says, at phone and PC sizes (R3-PLAN G1, A19).
 *
 * `--planneroutcome guide <outDir> [a copy of a BMS folder]`. Two halves:
 *
 * 1. **The text** ([PlannerGuideText]), read as data: every section has an id, a title and something in it; no id or
 *    other name is claimed twice; each Planner page ([WdpPage]) and each name the other windows open it with ("print",
 *    "open", "remote", "credit"…) lands on the section meant; every link and "go there" button names a section or a page
 *    that exists; the buttons are called what the screen calls them (R3-PLAN A2) and none of the old names is left
 *    ("Use in app", "Save to cartridge", "Target bar"…); the markup is balanced; and nothing personal is in it (no drive
 *    paths or addresses; with a BMS folder given, none of its pilots' callsigns). The whole text goes to
 *    `guide/guide-text.txt` for a reader to check against the screen.
 * 2. **The window**, composed in a headless scene with the real [PlannerWindowHost] at a phone (400×800 dp at 2×), a
 *    tablet (1024×768) and a PC (1600×900) size, and driven with the mouse and the keyboard as a pilot would:
 *    - the first-open flag: the guide opens by itself at its beginning once, and never again, nor over another window;
 *    - **Next** through every page (each one's text scrolled to its end with the wheel: the end must come into view),
 *      **Previous** back, and every page picked from the contents (a phone opens them with **Contents**);
 *    - every link in a table and every "go there" button pressed: a link turns to its section, Open mission… and Upd
 *      Kneeboard open their windows, Populate from Planner closes the guide and asks its question, a page button
 *      turns the Planner to that page and closes the guide;
 *    - opened at each Planner page's section; closed with × and with Escape.
 *
 *    Pictures of every page, top and (when it scrolls) end, at the phone and PC sizes, go to `guide/<size>/`; also the
 *    windows the "go there" buttons open (`guide/goes/`), drawn in a scene of their own.
 *
 * Nothing is written anywhere but [outDir] and the device settings' first-open flag, which is put back as it was.
 */
object GuideOutcome {
    private class Size(val name: String, val wDp: Int, val hDp: Int, val density: Float, val pictures: Boolean) {
        val w get() = (wDp * density).toInt()
        val h get() = (hDp * density).toInt()
    }

    private val SIZES = listOf(
        Size("phone", 400, 800, 2f, pictures = true),
        Size("tablet", 1024, 768, 1f, pictures = false),
        Size("pc", 1600, 900, 1f, pictures = true),
    )

    private class Report {
        val text = StringBuilder()
        var pass = 0
        var fail = 0
        fun pass(s: String) { pass++; text.appendLine("PASS  $s") }
        fun fail(s: String) { fail++; text.appendLine("FAIL  $s") }
        fun check(ok: Boolean, s: String, why: () -> String = { "" }) = if (ok) pass(s) else fail(s + why().let { if (it.isEmpty()) "" else " — $it" })
        fun info(s: String) = text.appendLine("      $s")
        fun head(s: String) { text.appendLine(); text.appendLine("== $s") }
    }

    fun run(outDir: File, more: List<String>): String {
        val dir = File(outDir, "guide").also { it.mkdirs() }
        val r = Report()
        r.text.appendLine("Planner guide: the text, and the window at phone, tablet and PC sizes")
        val sections = PlannerGuideText.sections
        r.text.appendLine("${sections.size} sections: " + sections.groupBy { it.group }.entries.joinToString("; ") { (g, s) -> "$g ${s.size}" })

        text(r, sections, dir, more.firstOrNull()?.let(::File))

        // the window: the device's first-open flag is the one thing touched outside outDir, and it is put back
        val seenBefore = runCatching { Repo.getInt(PlannerGuide.SEEN_KEY, 0) }.getOrDefault(0)
        val pageBefore = WdpSession.page
        WdpProbe.on = true
        try {
            for (size in SIZES) {
                r.head("window at ${size.name} (${size.wDp}×${size.hDp} dp, ${size.w}×${size.h} px)")
                try {
                    window(r, sections, size, File(dir, size.name))
                } catch (e: Throwable) {
                    r.fail("the ${size.name} run threw ${e::class.simpleName}: ${e.message}")
                    e.stackTrace.filter { it.className.startsWith("com.bmscompanion") }.take(8).forEach { r.info("  at $it") }
                }
            }
            r.head("the windows the \"go there\" buttons open (pictures only; each window checks itself)")
            goesPictures(r, sections, File(dir, "goes"))
        } finally {
            edt { PlannerWindows.close() }
            WdpProbe.on = false
            edt { WdpProbe.clear() }
            WdpSession.page = pageBefore
            runCatching { Repo.putInt(PlannerGuide.SEEN_KEY, seenBefore) }
        }
        r.head("summary")
        r.text.appendLine("PASS ${r.pass}, FAIL ${r.fail}")
        r.text.appendLine(if (r.fail == 0) "ALL PASS" else "FAIL: ${r.fail} of ${r.pass + r.fail} checks")
        return r.text.toString()
    }

    // ------------------------------------------------------------------------------------------------ the text

    private val NAMES = listOf(
        "Open mission…", "Save to DTC", "Populate from Planner", "Upd Kneeboard", "Guide", "Full window",
        "Back to BMS briefing", "Re-read DTC from BMS", "Save to DTC and populate", "WDP mode", "EZBoards mode",
        // the toolbar's Options menu (WDP's: Settings…, About WDP), and the Settings window's switches (1.3.9: the PC's
        // Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints)
        "Options", "Settings…", "About WDP", "Show tooltips", "Auto load last mission on startup", "Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints",
        // 1.3.8: the toolbar's Steps (the evening in ten lines) and WDP's ATO Target List as the last page tab
        "Steps", "ATO Targets",
        // 1.3.8: Pop-up, HADB and TOSS under one Attack tab (a rail beside the page)
        "Attack",
        // Upd Kneeboard's own buttons (1.3.8: Browse picture…, WDP's Browse Picture)
        "Mission set", "Browse picture…", "← Insert left", "Insert right →",
        // WDP's own cartridge buttons on the DataCard (back on the page in 1.3.8, beside the toolbar's Save to DTC)
        "Get DTC File", "Save DTC",
        // the Map page's tools (WDP's MAP tab, 1.3.8)
        "Measure", "Save Map", "Auto PPT", "Clear PPT", "Change Area…", "Clear Lines", "Fit",
        // the Kneeboards page in WDP mode: the greyed EZBoards and html_brief buttons and the way to the Planner (1.3.8)
        "Open the Planner", "Run HTML Briefing",
        // the attack pages' Coordinates box (1.3.8, D87): the IP picked beside the target
        "IP STPT",
        // 1.3.8 (D94): a steerpoint on the VRP becomes the IP, Pop-up and TOSS in VRP mode, after its one question
        "IP STPT at the VRP", "Create IP STPT",
    )
    private val OLD_NAMES = listOf(
        "Use in app", "Save to cartridge", "Target bar", "Open mission file", "Print to kneeboard",
        // retired in 1.3.8: WDP mode's Populate from Planner replaced Send to Mission, and EZBoards is paused there
        "Send to Mission", "Generate kneeboards (EZBoards)",
        // retired in 1.3.8: the Options menu's ATO Target List… became the ATO Targets tab
        "ATO Target List…",
        // retired in 1.3.8: what an earlier flight left in the cartridge is cleared by itself at a new mission
        "Clear leftovers from the cartridge…",
        // retired within 1.3.8: a switch or a new mission clears silently, with no notice and no undo on screen
        "Undo the cartridge reset",
        // retired within 1.3.8: WDP's Campaign and TE buttons are off the attack pages (D87)
        "Campaign lamp",
        // retired within 1.3.8: an attack page's Save to DTC fills the DataCard itself (D87)
        "Send to DataCard",
    )

    /** Who opens the guide with what, and where it must land. */
    private val OPENERS = listOf(
        null to "start", "" to "start", "start" to "start", "no such page" to "start",
        "print" to "step7", "Print" to "step7", "open" to "step3", "OpenMission" to "step3", "send" to "step5",
        "populate" to "step5", "save" to "step5", "remote" to "remote", "notlinked" to "remote", "credit" to "credit",
        "about" to "credit", "missing" to "missing", "savedtc" to "savedtc", "words" to "words",
    )

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun textsOf(s: GuideSection): List<String> = buildList {
        add(s.title); add(s.short)
        for (b in s.blocks) when (b) {
            is GuideBlock.Para -> add(b.text)
            is GuideBlock.Sub -> add(b.text)
            is GuideBlock.Why -> add(b.text)
            is GuideBlock.Note -> add(b.text)
            is GuideBlock.Steps -> b.items.forEach { add(it.text); it.why?.let(::add); addAll(it.sub) }
            is GuideBlock.Bullets -> b.items.forEach { add(it.text); it.why?.let(::add); addAll(it.sub) }
            is GuideBlock.Table -> { addAll(b.head); b.rows.forEach { add(it.term); add(it.text) } }
            is GuideBlock.Go -> add(b.label)
        }
    }

    private fun text(r: Report, sections: List<GuideSection>, dir: File, bmsRoot: File?) {
        r.head("the text")
        r.check(sections.size >= 20, "${sections.size} sections (the start, nine steps, a card per page, and the rest)")
        val ids = sections.map { it.id }
        r.check(ids.distinct().size == ids.size, "every section's id is its own", { "twice: " + ids.groupBy { it }.filter { it.value.size > 1 }.keys })
        for (s in sections) {
            val empty = s.title.isBlank() || s.short.isBlank() || s.blocks.isEmpty()
            if (empty) r.fail("section ${s.id}: no title, contents line or text")
        }
        r.check(sections.none { it.title.isBlank() || it.short.isBlank() || it.blocks.isEmpty() }, "every section has a title, a contents line and text")
        // no name claimed twice: a name must lead to one section only
        val claims = HashMap<String, MutableSet<String>>()
        for (s in sections) (listOf(s.id) + s.aliases).forEach { claims.getOrPut(norm(it)) { LinkedHashSet() } += s.id }
        val twice = claims.filter { it.value.size > 1 }
        r.check(twice.isEmpty(), "no name leads to two sections", { twice.entries.joinToString { "${it.key} → ${it.value}" } })
        // each Planner page opens its own card
        for (p in WdpPage.entries) {
            val got = sections[PlannerGuide.indexOf(p.name)].id
            r.check(got == p.name.lowercase(), "the ${p.label} page (\"${p.name}\") opens its card", { "opens '$got'" })
        }
        for ((arg, want) in OPENERS) {
            val got = sections[PlannerGuide.indexOf(arg)].id
            r.check(got == want, "opened with ${arg?.let { "\"$it\"" } ?: "nothing"} → $want", { "got '$got'" })
        }
        // links and "go there" buttons name what exists
        var links = 0
        var goes = 0
        for (s in sections) for (b in s.blocks) when (b) {
            is GuideBlock.Table -> for (row in b.rows) row.link?.let { l ->
                links++
                if (ids.none { it == l }) r.fail("${s.id}: the link '${row.term}' names no section ('$l')")
                if (l == s.id) r.fail("${s.id}: the link '${row.term}' goes to its own section")
            }
            is GuideBlock.Go -> {
                goes++
                when (val to = b.to) {
                    is GuideGo.Section -> if (ids.none { it == to.id }) r.fail("${s.id}: '${b.label}' names no section ('${to.id}')")
                    is GuideGo.Page -> if (WdpPage.entries.none { it.name == to.page }) r.fail("${s.id}: '${b.label}' names no page ('${to.page}')")
                    else -> {}
                }
            }
            else -> {}
        }
        r.pass("$links links and $goes \"go there\" buttons checked for their targets (a FAIL line above for each that names nothing)")
        val dupGoes = sections.filter { s -> s.blocks.filterIsInstance<GuideBlock.Go>().map { it.to.key }.let { it.distinct().size != it.size } }
        r.check(dupGoes.isEmpty(), "no section has two buttons going to the same place", { dupGoes.joinToString { it.id } })
        val dupLinks = sections.filter { s -> s.blocks.filterIsInstance<GuideBlock.Table>().flatMap { t -> t.rows.filter { it.link != null }.map { it.term } }.let { it.distinct().size != it.size } }
        r.check(dupLinks.isEmpty(), "no section has two links with the same words", { dupLinks.joinToString { it.id } })
        val openWindows = sections.flatMap { s -> s.blocks.filterIsInstance<GuideBlock.Go>().map { it.to } }
        r.check(openWindows.any { it == GuideGo.OpenMission }, "a button opens Open mission…")
        r.check(openWindows.any { it is GuideGo.Page && it.page == WdpPage.DTC.name }, "a button opens the DTC page")
        r.check(openWindows.any { it == GuideGo.Print }, "a button opens Upd Kneeboard")

        // the names on screen, and none of the old ones
        val all = sections.flatMap(::textsOf).joinToString("\n")
        for (n in NAMES) r.check(all.contains(n), "names \"$n\"")
        for (n in OLD_NAMES) {
            val at = sections.filter { s -> textsOf(s).any { it.contains(n, ignoreCase = true) } }.map { it.id }
            r.check(at.isEmpty(), "no \"$n\" (an old name)", { "in ${at.joinToString()}" })
        }
        val bare = Regex("Open mission(?!…)").findAll(all).count()
        r.check(bare == 0, "\"Open mission\" is always \"Open mission…\", as on the button", { "$bare without the dots" })
        // markup
        val badMarks = sections.flatMap { s -> textsOf(s).filter { Regex("\\*\\*").findAll(it).count() % 2 != 0 || it.count { c -> c == '`' } % 2 != 0 }.map { "${s.id}: ${it.take(50)}" } }
        r.check(badMarks.isEmpty(), "every ** and ` is closed", { badMarks.joinToString(" | ") })
        // nothing personal
        val drive = Regex("\\b[A-Za-z]:\\\\").find(all)
        r.check(drive == null, "no drive path", { "'${all.substring(maxOf(0, drive!!.range.first - 20), minOf(all.length, drive.range.last + 30))}'" })
        val ip = Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b").find(all)
        r.check(ip == null, "no network address", { "'${ip!!.value}'" })
        r.check(!all.contains("Users\\") && !all.contains("Users/"), "no user folder")
        val pilots = bmsRoot?.let { root ->
            File(root, "User/Config").listFiles { f -> f.isFile && f.name.endsWith(".ini", true) }.orEmpty()
                .map { it.nameWithoutExtension }
                .filter { n -> n.length >= 3 && !n.contains("_Def", true) && !n.startsWith("Falcon", true) && !n.contains(" ") }
        }
        if (pilots == null) r.info("(no BMS folder given: callsigns not looked for)")
        else {
            val found = pilots.filter { p -> Regex("\\b" + Regex.escape(p) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(all) }
            r.check(found.isEmpty(), "none of the ${pilots.size} pilot file names in the given BMS folder's User/Config is in the text", { "found ${found.size}" })
        }
        // the whole text, for a reader
        val words = sections.associate { s -> s.id to textsOf(s).sumOf { it.split(Regex("\\s+")).count { w -> w.isNotBlank() } } }
        r.info("words: ${words.values.sum()} in all; " + words.entries.joinToString(", ") { "${it.key} ${it.value}" })
        runCatching { File(dir, "guide-text.txt").writeText(plain(sections)) }
            .onSuccess { r.info("the whole text: ${File(dir, "guide-text.txt")}") }
    }

    /** The guide as plain text, section by section, as a reader would check it against the screen. */
    private fun plain(sections: List<GuideSection>): String = buildString {
        for ((k, s) in sections.withIndex()) {
            appendLine("#${k + 1} [${s.id}] ${s.group} / ${s.title}   (contents: ${s.short}; also: ${s.aliases.joinToString()})")
            for (b in s.blocks) when (b) {
                is GuideBlock.Para -> appendLine(b.text)
                is GuideBlock.Sub -> appendLine("-- ${b.text}")
                is GuideBlock.Why -> appendLine("   Why: ${b.text}")
                is GuideBlock.Note -> appendLine("!! ${b.text}")
                is GuideBlock.Steps -> b.items.forEachIndexed { n, it ->
                    appendLine("${n + 1}. ${it.text}"); it.sub.forEach { s2 -> appendLine("     - $s2") }; it.why?.let { w -> appendLine("     Why: $w") }
                }
                is GuideBlock.Bullets -> b.items.forEach {
                    appendLine("  * ${it.text}"); it.sub.forEach { s2 -> appendLine("     - $s2") }; it.why?.let { w -> appendLine("     Why: $w") }
                }
                is GuideBlock.Table -> {
                    appendLine("  | ${b.head.joinToString(" | ")} |")
                    b.rows.forEach { appendLine("  | ${it.term}${it.link?.let { l -> " (→ $l)" } ?: ""} | ${it.text} |") }
                }
                is GuideBlock.Go -> appendLine("  [${b.label} ›] → ${b.to.key}")
            }
            appendLine()
        }
    }

    // ------------------------------------------------------------------------------------------------ the window

    private fun window(r: Report, sections: List<GuideSection>, size: Size, pics: File) {
        if (size.pictures) pics.mkdirs()
        val ids = sections.map { it.id }
        edt { PlannerWindows.close(); WdpProbe.clear() }

        // the first-open flag, before anything is drawn: not over another window, then once, then never again
        PlannerGuide.setSeen(false)
        edt { PlannerWindows.show(PlannerWindow.PRINT) }
        val overOther = edt { PlannerGuide.firstOpen() }
        r.check(!overOther && edt { PlannerWindows.open } == PlannerWindow.PRINT && !PlannerGuide.seen,
            "first open waits while another window (Print) is up, and the flag stays unset")
        edt { PlannerWindows.close() }
        val first = edt { PlannerGuide.firstOpen() }
        r.check(first && edt { PlannerWindows.open } == PlannerWindow.GUIDE, "first open: the guide opens by itself")
        r.check(PlannerGuide.seen && Repo.getInt(PlannerGuide.SEEN_KEY, 0) == 1, "first open: the flag is kept in the device's settings (${PlannerGuide.SEEN_KEY} = 1)")
        val again = edt { PlannerWindows.close(); PlannerGuide.firstOpen() }
        r.check(!again && edt { PlannerWindows.open } == null, "first open: never again once seen")
        edt { PlannerGuide.firstOpen(); PlannerGuide.setSeen(false); PlannerGuide.firstOpen() }

        val d = Driver(size)
        try {
            d.settle(4)
            r.check(PlannerGuide.shownId == "start", "first open lands on the beginning", { "on ${PlannerGuide.shownId}" })
            val frame = d.rect("planner/Guide")
            r.check(frame != null && frame.left >= 0f && frame.top >= 0f && frame.right <= size.w + 0.5f && frame.bottom <= size.h + 0.5f,
                "the window fits the screen", { "window $frame in ${size.w}×${size.h}" })
            val body = d.rect("planner/Guide/Body")
            r.check(body != null && body.height >= 200 * size.density, "the text has room (${body?.height?.div(size.density)?.toInt()} dp tall)")
            val wide = d.rect("planner/Guide/ContentsList") != null
            r.info(if (wide) "contents beside the text" else "contents behind the Contents button")

            // Next through every page, each read to its end
            var nextOk = 0
            var endOk = 0
            for (i in ids.indices) {
                if (i > 0) {
                    d.clickKey("planner/Guide/Next")
                    if (PlannerGuide.shownId == ids[i]) nextOk++ else r.fail("Next from ${ids[i - 1]} → ${PlannerGuide.shownId}, not ${ids[i]}")
                }
                if (size.pictures) d.png(File(pics, "%02d-%s.png".format(i + 1, ids[i])))
                val scrolled = d.reveal("planner/Guide/Body", "planner/Guide/End/${ids[i]}")
                if (scrolled != null) {
                    endOk++
                    if (scrolled > 0 && size.pictures) d.png(File(pics, "%02d-%s-end.png".format(i + 1, ids[i])))
                } else r.fail("${ids[i]}: the end of the page never came into view with the wheel")
            }
            r.check(nextOk == ids.size - 1, "Next turns through all ${ids.size} pages in order ($nextOk of ${ids.size - 1})")
            r.check(endOk == ids.size, "every page's text can be read to its end ($endOk of ${ids.size})")
            d.clickKey("planner/Guide/Next")
            r.check(PlannerGuide.shownId == ids.last(), "Next does nothing on the last page")

            // Previous back to the beginning
            var prevOk = 0
            for (i in ids.indices.reversed().drop(1)) {
                d.clickKey("planner/Guide/Prev")
                if (PlannerGuide.shownId == ids[i]) prevOk++ else r.fail("Previous → ${PlannerGuide.shownId}, not ${ids[i]}")
            }
            r.check(prevOk == ids.size - 1, "Previous turns back through every page ($prevOk of ${ids.size - 1})")
            d.clickKey("planner/Guide/Prev")
            r.check(PlannerGuide.shownId == ids.first(), "Previous does nothing on the first page")

            // every page from the contents
            var tocOk = 0
            for ((i, id) in ids.withIndex()) {
                if (!wide) {
                    d.clickKey("planner/Guide/Contents")
                    if (!PlannerGuide.shownContents) { r.fail("Contents did not show the contents (for $id)"); continue }
                    if (i == ids.size / 2 && size.pictures) d.png(File(pics, "contents.png"))
                }
                if (d.reveal("planner/Guide/ContentsList", "planner/Guide/Toc/$id") == null) { r.fail("$id is not in the contents, or cannot be scrolled to"); continue }
                d.clickKey("planner/Guide/Toc/$id")
                if (PlannerGuide.shownId == id && !PlannerGuide.shownContents) tocOk++
                else r.fail("contents → $id showed ${PlannerGuide.shownId}${if (PlannerGuide.shownContents) " (contents still up)" else ""}")
            }
            r.check(tocOk == ids.size, "every page opens from the contents ($tocOk of ${ids.size})")
            if (!wide) {
                d.clickKey("planner/Guide/Contents")
                d.clickKey("planner/Guide/Contents")
                r.check(!PlannerGuide.shownContents, "Contents pressed again goes back to the text")
            }

            // links in the tables
            var linkOk = 0
            var linkAll = 0
            for (s in sections) for (row in s.blocks.filterIsInstance<GuideBlock.Table>().flatMap { it.rows }.filter { it.link != null }) {
                linkAll++
                d.openAt(s.id)
                if (PlannerGuide.shownId != s.id) { r.fail("opened at ${s.id}, showed ${PlannerGuide.shownId}"); continue }
                val key = "planner/Guide/Link/${row.term}"
                if (d.reveal("planner/Guide/Body", key) == null) { r.fail("${s.id}: the link '${row.term}' is not on the page, or cannot be scrolled to"); continue }
                d.clickKey(key)
                if (PlannerGuide.shownId == row.link) linkOk++ else r.fail("${s.id}: the link '${row.term}' → ${PlannerGuide.shownId}, not ${row.link}")
            }
            r.check(linkOk == linkAll, "every link in a table turns to its section ($linkOk of $linkAll)")

            // "go there" buttons
            var goOk = 0
            var goAll = 0
            for (s in sections) for (b in s.blocks.filterIsInstance<GuideBlock.Go>()) {
                goAll++
                d.openAt(s.id)
                val key = "planner/Guide/Go/${b.to.key}"
                if (d.reveal("planner/Guide/Body", key) == null) { r.fail("${s.id}: '${b.label}' is not on the page, or cannot be scrolled to"); continue }
                // the Planner on another page than the one the button names, so a press that does nothing shows
                val elsewhere = if ((b.to as? GuideGo.Page)?.page == WdpPage.DATACARD.name) WdpPage.BRIEFING else WdpPage.DATACARD
                edt { WdpSession.page = elsewhere.name }
                // pressed, and looked at before the window it opens is drawn: each window's own batch checks that window
                d.clickKey(key, settleAfter = false)
                val open = edt { PlannerWindows.open }
                val (ok, what) = when (val to = b.to) {
                    GuideGo.OpenMission -> (open == PlannerWindow.OPEN_MISSION) to "opens Open mission…"
                    GuideGo.Populate -> {
                        // no save's flight is open here: the press asks for one, in the Planner's message box
                        val asked = edt { (com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.lastOrNull() as? com.bmscompanion.app.ui.screens.wdp.WdpMessage)?.title }
                        edt { com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear() }
                        (open == null && asked == com.bmscompanion.app.data.mission.MissionMode.POPULATE) to "closes the guide and presses Populate from Planner"
                    }
                    GuideGo.Print -> (open == PlannerWindow.PRINT) to "opens Upd Kneeboard"
                    is GuideGo.Page -> (open == null && WdpSession.page == to.page) to "turns the Planner to ${to.page} and closes the guide"
                    is GuideGo.Section -> {
                        d.settle(2)
                        (open == PlannerWindow.GUIDE && PlannerGuide.shownId == to.id) to "turns to ${to.id}"
                    }
                }
                if (ok) { goOk++; r.pass("${s.id}: '${b.label}' $what") }
                else r.fail("${s.id}: '${b.label}' should have ${what.substringBefore(' ')} … — window ${open?.name}, page ${WdpSession.page}, section ${PlannerGuide.shownId}")
                edt { PlannerWindows.close() }
                d.settle(2)
            }
            r.check(goOk == goAll, "every \"go there\" button goes where it says ($goOk of $goAll)")

            // opened at the page on screen
            var atOk = 0
            for (p in WdpPage.entries) {
                d.openAt(p.name)
                if (PlannerGuide.shownId == p.name.lowercase()) atOk++ else r.fail("opened from ${p.label}: showed ${PlannerGuide.shownId}")
            }
            r.check(atOk == WdpPage.entries.size, "opened from each Planner page, the guide shows that page's card ($atOk of ${WdpPage.entries.size})")
            for ((arg, want) in listOf("print" to "step7", "remote" to "remote", "credit" to "credit", "open" to "step3")) {
                d.openAt(arg)
                r.check(PlannerGuide.shownId == want, "opened with \"$arg\" (another window's help): shows $want", { "showed ${PlannerGuide.shownId}" })
            }

            // closing
            d.openAt("start")
            d.clickKey("planner/Guide/Close")
            r.check(edt { PlannerWindows.open } == null && PlannerGuide.shownId == null, "× closes the guide")
            d.openAt("step4")
            d.settle(3)
            d.key(AwtKey.VK_ESCAPE, 27.toChar())
            d.settle(2)
            r.check(edt { PlannerWindows.open } == null, "Escape closes the guide")
        } finally {
            d.close()
        }
    }

    /** A picture of each window a "go there" button opens, in a scene of its own (so a window's own trouble stays its own). */
    private fun goesPictures(r: Report, sections: List<GuideSection>, dir: File) {
        dir.mkdirs()
        val size = SIZES.last()
        val seen = HashSet<String>()
        for (s in sections) for (b in s.blocks.filterIsInstance<GuideBlock.Go>()) {
            if (b.to is GuideGo.Section || b.to is GuideGo.Page || !seen.add(b.to.key)) continue
            edt { PlannerWindows.close(); WdpProbe.clear() }
            val d = Driver(size)
            try {
                d.openAt(s.id)
                d.reveal("planner/Guide/Body", "planner/Guide/Go/${b.to.key}")
                d.clickKey("planner/Guide/Go/${b.to.key}")
                d.settle(6, 30)
                val f = File(dir, "${b.to.key}.png")
                d.png(f)
                r.info("'${b.label}' → ${edt { PlannerWindows.open }?.title}: $f")
            } catch (e: Throwable) {
                r.info("'${b.label}': the window it opens threw while drawing (${e::class.simpleName}: ${e.message}) — that window's batch checks it")
            } finally {
                edt { PlannerWindows.close() }
                d.close()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the scene

    /** The Planner's window host alone, over the app's background, in a headless scene driven like the PC window. */
    private class Driver(val size: Size) {
        private val scene = edt {
            ImageComposeScene(size.w, size.h, Density(size.density)) {
                Box(Modifier.fillMaxSize().background(Hud.Bg)) { PlannerWindowHost() }
            }
        }
        private var nanos = 0L
        private var ms = 1_000L
        private val src = java.awt.Canvas()

        fun frame() = edt {
            Snapshot.sendApplyNotifications()
            nanos += 16_666_667L
            scene.render(nanos)
        }

        fun settle(n: Int = 3, sleepMs: Long = 0) = repeat(n) { frame().close(); if (sleepMs > 0) Thread.sleep(sleepMs) }

        fun rect(key: String): Rect? = edt { WdpProbe.rects[key] }

        fun png(f: File) {
            val img = frame()
            runCatching { f.parentFile?.mkdirs(); f.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
            img.close()
        }

        /** Opens the guide afresh at [section], as a button elsewhere would. */
        fun openAt(section: String) {
            edt { PlannerWindows.close() }
            settle(2)
            edt { PlannerGuide.open(section) }
            settle(3)
        }

        fun click(at: Offset, settleAfter: Boolean = true) {
            ms += 50
            edt {
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            }
            settle(1)
            ms += 60
            edt { scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ms, buttons = PointerButtons(), button = PointerButton.Primary) }
            if (settleAfter) settle(3)
            ms += 400 // never a double click
        }

        /** Presses the control the probe knows as [key] at its centre; a missing control is a failure of the step. */
        fun clickKey(key: String, settleAfter: Boolean = true) {
            val at = rect(key) ?: error("'$key' is not on screen")
            click(at.center, settleAfter)
        }

        fun wheel(at: Offset, notches: Float) {
            ms += 30
            edt { scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(0f, notches), timeMillis = ms) }
            settle(8)
        }

        /**
         * Scrolls [viewport] with the wheel until [target] lies inside it. The number of wheel turns it took (0 when it was
         * already in view), or null when it never came into view.
         */
        fun reveal(viewport: String, target: String, maxTurns: Int = 60): Int? {
            var turns = 0
            var down = true
            var lastTop: Float? = null
            var stuck = 0
            while (turns <= maxTurns * 2) {
                val vp = rect(viewport) ?: return null
                val t = rect(target)
                if (t != null && t.top >= vp.top - 1f && t.bottom <= vp.bottom + 1f) return turns
                // which way: towards the target when it is laid out; a lazy list's missing row is looked for down, then up
                val dir = when {
                    t != null -> if (t.bottom > vp.bottom) 1f else -1f
                    down -> 1f
                    else -> -1f
                }
                wheel(vp.center, 5f * dir)
                turns++
                if (t == null) {
                    // a missing row: when the list stops moving, turn round
                    val probeTop = edt { WdpProbe.rects.filterKeys { it.startsWith("planner/Guide/Toc/") }.values.minOfOrNull { it.top } }
                    if (probeTop == lastTop) stuck++ else stuck = 0
                    lastTop = probeTop
                    if (stuck >= 2) { if (!down) return null; down = false; stuck = 0 }
                }
            }
            return null
        }

        fun key(code: Int, ch: Char) {
            for (id in listOf(AwtKey.KEY_PRESSED, AwtKey.KEY_RELEASED)) {
                ms += 20
                val e = AwtKey(src, id, ms, 0, code, ch, AwtKey.KEY_LOCATION_STANDARD)
                val type = if (id == AwtKey.KEY_PRESSED) androidx.compose.ui.input.key.KeyEventType.KeyDown else androidx.compose.ui.input.key.KeyEventType.KeyUp
                val ke = androidx.compose.ui.input.key.KeyEvent(
                    androidx.compose.ui.input.key.Key(code, AwtKey.KEY_LOCATION_STANDARD), type, ch.code, false, false, false, false, e,
                )
                edt { scene.sendKeyEvent(ke) }
                settle(1)
            }
        }

        fun close() = runCatching { edt { scene.close() } }
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var r: Result<T>? = null
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r!!.getOrThrow()
    }
}
