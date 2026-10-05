@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.wdp.WdpControl
import com.bmscompanion.app.data.wdp.WdpForm
import com.bmscompanion.app.data.wdp.WdpValues
import com.bmscompanion.app.ui.screens.wdp.DataCardWiring
import com.bmscompanion.app.ui.screens.wdp.DtcSource
import com.bmscompanion.app.ui.screens.wdp.DtcWiring
import com.bmscompanion.app.ui.screens.wdp.HadbWiring
import com.bmscompanion.app.ui.screens.wdp.PerformanceWiring
import com.bmscompanion.app.ui.screens.wdp.PopupWiring
import com.bmscompanion.app.ui.screens.wdp.TossWiring
import com.bmscompanion.app.ui.screens.wdp.WdpControlContent
import com.bmscompanion.app.ui.screens.wdp.WdpDialog
import com.bmscompanion.app.ui.screens.wdp.WdpDialogHost
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpFormView
import com.bmscompanion.app.ui.screens.wdp.WdpHandOff
import com.bmscompanion.app.ui.screens.wdp.WdpMessage
import com.bmscompanion.app.ui.screens.wdp.WdpMission
import com.bmscompanion.app.ui.screens.wdp.WdpPage
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.screens.wdp.WdpSession
import com.bmscompanion.app.ui.screens.wdp.WdpWiring
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import org.jetbrains.skia.Bitmap as SkBitmap
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.Rect as SkRect

/**
 * `--wdpoutcome <page|all> <outDir> [briefing.txt] [cartridge.ini] [WDP source folder]` — **two to five arguments**
 * after the flag (so `args.size` 3..6 in SelfTest). What every control of the Planner *does*, with a picture of the
 * page after it, for a tester to look at.
 *
 * `--wdpclicktest` proves that a press changed *some* value. This one records the outcome: every control of every page
 * (Briefing, DataCard, Coordination Card, each DTC tab, Pop-up, HADB, TOSS, Performance) is operated the way a pilot
 * would — a button pressed, a label or knob tapped (and right-clicked where WDP's handler looks at the right button),
 * every item of every list picked (twelve at most, spread), each tab chosen, grid rows tapped and double-tapped,
 * sliders moved to min / mid / max, up/downs stepped both ways, and text **typed into the real field** on a real
 * Compose scene: a click on the field, Ctrl+A, the text one key at a time (a frame between keys, as a person types),
 * then Tab to leave it — so a field that loses what is typed shows up here as it does for a pilot. Each action starts
 * from a fresh Planner (the page's wiring and the ones it hands off to, as `WdpPlanner` joins them, given the mission),
 * so actions are independent. What opens is followed: every child window one level down (two where a window opens
 * another, and a question's every answer), and controls an action brings into view (the DTC page's pilot box under
 * Change, the knob panels of the attack pages) are operated in that state.
 *
 * Per page, under `<outDir>`: `<page>/outcome.tsv` (one row per action: the window, the control and its caption,
 * WDP's own handlers for it, the action, the flag, what opened or what message appeared in full, every value that
 * changed as `key: old → new`, what reached the cartridge, the pixels changed, the pictures, anything thrown),
 * `<page>/<nnn>-<control>.png` (the page — and the window open over it — after the action), and contact sheets
 * `<page>-sheet.png`, `-sheet-2.png`… plus `<page>-sheet-flagged.png` (the flagged rows only). `summary.txt` has the
 * counts per page and the most suspicious rows.
 *
 * Flags: **THREW** (the press, something it started, or drawing the page after it threw), **TYPE-LOST** (typed text
 * not shown in the field, or shown there once the field is left while the page holds something else), **REVERTED** (typed text gone again once the field was left, with no message), **MSG**
 * (only a message — its text is listed so a tester can judge the "not supported" kind), **DEAD** (nothing changed at
 * all), **NO-FIELD** (no field to type into where the control is drawn), **NO-UI** (WDP answers a double-click on a
 * label, which the page cannot take), OPEN, ok, same (the item or radio already chosen, a box at its limit), disabled.
 * A typing row whose click on the field did not focus it says so in its note (the field is covered, or not where it
 * is drawn) and is counted per page.
 *
 * Nothing is written anywhere but [outDir]: the cartridge is the fixture's copy held in memory ([WdpDtcFixture]; saves
 * are applied to that copy and listed in the `cartridge` column), the Planner's own DTC page is pointed at the same
 * copy (`WdpSession.dtcSource`), no file dialog is opened (the harness has no installer, so Save Map answers as the
 * browser does), and the settings the pages remember are put back after every action and at the end. Run it with
 * APPDATA pointed at a scratch folder all the same. The run itself happens in a second JVM on a copy of the program
 * taken under `<outDir>/_program` and removed afterwards ([relaunch]), so a build started meanwhile cannot pull the jar
 * from under it (`BMSC_WDP_OUTCOME_INPLACE=1` runs in place). `BMSC_WDP_THEATER` names the theater (as for `--wdprender`);
 * `BMSC_WDP_DEFAULTS` a folder of copies of BMS's `*_Def.ini` (default: `defaults` beside the cartridge);
 * `BMSC_WDP_ROUTE` and `BMSC_WDP_SAVEFLIGHT` give BMS's route and a save's flight ([WdpFixtureMission]);
 * `BMSC_WDP_OUTCOME_FAST=1` also types each field with no frame between keys; `BMSC_WDP_OUTCOME_OPENERS=all` follows
 * every window each time it is opened rather than once per page view. The WDP source folder (the decompiled
 * `.cs` files of WeaponDeliveryPlanner) is optional: with it, the `wdp` column names WDP's handlers and a label WDP answers
 * that does nothing here is reported DEAD rather than left out.
 */
internal object WdpOutcome {
    private const val SCENE_W = 1320
    private const val SCENE_H = 900
    private const val MAX_DEPTH = 2
    private const val PICKS = 12
    private const val THUMB_W = 240
    private val BG = Color(0xFF15181C)

    // ------------------------------------------------------------------------------------------------ the run

    fun run(args: List<String>): String {
        if (System.getProperty(CHILD) == null && System.getenv("BMSC_WDP_OUTCOME_INPLACE") != "1") return relaunch(args)
        val which = args[0]
        val out = File(args[1]).also { it.mkdirs() }
        val briefing = args.getOrNull(2)?.let(::File)?.takeIf { it.isFile }
        val cartridge = args.getOrNull(3)?.let(::File)?.takeIf { it.isFile }
        val wdpSrc = args.getOrNull(4)?.let(::File)?.takeIf { it.isDirectory }
        val t0 = System.nanoTime()
        WdpProbe.on = true
        val watch = Snapshot.registerGlobalWriteObserver { writes.incrementAndGet() }
        val data = WdpFixtureMission.data(briefing, cartridge)
        val theater = System.getenv("BMSC_WDP_THEATER")?.let { name ->
            runBlocking { com.bmscompanion.app.ui.screens.wdp.plannerTheater(Repo.index().theaters, name) }
        }
        // the mission as the Planner builds it: no target bar, each attack page set once to the default TGT STPT
        val mission = WdpFixtureMission.mission(data, theater)
        WdpDtcFixture.file = cartridge
        WdpDtcFixture.defaultsDir = System.getenv("BMSC_WDP_DEFAULTS")?.let(::File)?.takeIf { it.isDirectory }
            ?: cartridge?.parentFile?.let { File(it, "defaults") }?.takeIf { it.isDirectory }
        // the Planner's own DTC page, should anything reach it, reads the check's copy and never the PC's cartridge
        WdpSession.dtcSource = WdpDtcFixture.source()
        val fx = Fixture(mission, cartridge?.readText())
        val prefs = PrefsGuard()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> asyncErrors += e; previousHandler?.uncaughtException(t, e) }
        val report = StringBuilder()
        report.appendLine("Weapon Delivery Planner — the outcome of every control")
        report.appendLine("briefing: ${if (data.briefing != null) "read" else "none"}, cartridge: ${if (data.dtc != null) "read" else "none"}, " +
            "theater: ${theater?.name ?: "none (set BMSC_WDP_THEATER)"}, BMS route: ${if (data.route != null) "read" else "none"}, " +
            "WDP source: ${if (wdpSrc != null) "read" else "not given"}")
        report.appendLine(WdpFixtureMission.describe(mission))
        val all = ArrayList<Row>()
        val stage = Stage()
        val writer = Writer()
        report.appendLine(runCatching { selfCheck(stage) }.getOrElse { "self-check of the typing probe threw: " + trace(it) })
        // each attack page's own TGT STPT (R3-PLAN A3): with `all`, `toss` or `tgtstpt` (which runs only this)
        if (which.equals("all", true) || which.equals("toss", true) || which.equals("tgtstpt", true)) {
            val lines = runCatching { tgtStpt(fx) }.getOrElse { listOf("FAIL tgtstpt: threw " + trace(it)) }
            File(out, "tgtstpt.txt").writeText(lines.joinToString("\n") + "\n")
            for (l in lines) report.appendLine(l)
        }
        try {
            for ((pageId, page, tabOnly) in pagesFor(which)) {
                val tp = System.nanoTime()
                val form = runBlocking { Repo.wdpForm(page.form) }
                if (form == null) { report.appendLine("FAIL $pageId: no layout"); continue }
                val ctx = PageCtx(pageId, page, form, File(out, pageId).also { it.mkdirs() }, fx, prefs, stage, writer, wdpSrc)
                for (v in viewsOf(page, form, tabOnly)) {
                    ctx.exploredWindows.clear(); ctx.exploredReveals.clear(); ctx.exploredQuestions.clear()
                    ctx.view = v
                    try { ctx.explore(emptyList(), 0, null) } catch (e: Throwable) {
                        ctx.rows += Row(ctx.rows.size + 1, pageId, v.id, "page", 0, "", "(view)", "", "", "", "open the view", "THREW", error = trace(e))
                    }
                }
                writer.drain()
                ctx.writeTsv()
                ctx.writeSheets()
                all += ctx.rows
                val secs = (System.nanoTime() - tp) / 1e9
                report.appendLine(ctx.countLine() + " — %.0f s".format(secs))
            }
        } finally {
            writer.shutdown()
            stage.close()
            prefs.restoreOriginal()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            WdpProbe.on = false
            watch.dispose()
        }
        writeAllTsv(File(out, "outcome-all.tsv"), all)
        report.appendLine()
        // rows alike but for a number (txtFuel9 … txtFuel24, Change STPT 24 / 89) count once, with how many there are
        val digits = Regex("[0-9]+")
        val alike = all.filter { it.suspicion() > 0 }.groupBy { r ->
            listOf(r.page, r.window, r.control, r.action, r.flag, r.message.take(80)).joinToString("|") { digits.replace(it, "#") }
        }.values.sortedWith(compareByDescending<List<Row>> { it[0].suspicion() }.thenBy { it[0].page }.thenBy { it[0].n })
        report.appendLine("The 30 most suspicious rows, alike ones together (page/nnn: window control — action → flag: what happened):")
        for (g in alike.take(30)) {
            val r = g[0]
            report.appendLine("  ${r.page}/${r.n.toString().padStart(3, '0')}: ${r.window.take(28)} ${r.control} — ${r.action} → ${r.flag}" +
                (if (g.size > 1) " (×${g.size}: rows " + g.take(5).joinToString(",") { it.n.toString() } + (if (g.size > 5) ",…" else "") + ")" else "") + ": " +
                (r.error.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: r.message.ifBlank { null } ?: r.note.ifBlank { null } ?: r.changed.ifBlank { "nothing changed" })
                    .replace('\n', ' ').take(200))
        }
        report.appendLine()
        report.appendLine("done in %.0f s".format((System.nanoTime() - t0) / 1e9))
        File(out, "summary.txt").writeText(report.toString())
        return report.toString()
    }

    /**
     * The attack pages' TGT STPT without a target bar (R3-PLAN A3), on a fresh Planner given the fixture's mission:
     * a new mission sets all three pages once to [WdpMission.defaultStpt]; TGT STPT 5 on TOSS makes the target STPT 5
     * and the IP STPT 4 there, and leaves the Pop-up and HADB pages as they were; the same mission handed on again
     * (a cartridge saved) leaves the box where the pilot put it; a new mission sets it to that mission's default
     * again; the TGT STPT tip names where the steerpoint came from (A4); IP STPT 3 moves the IP and the VIP lines'
     * steerpoint and IP STPT 4 follows the target again; Save to DTC's card fill leaves the card as its own TOSS button
     * does, and the page has no Send to DataCard (D87). Lines start with
     * `ok` or `FAIL`; a mission that places no steerpoint 4 and 5 is said so and not judged.
     */
    private fun tgtStpt(fx: Fixture): List<String> {
        val out = ArrayList<String>()
        val m = fx.mission
        fun check(what: String, good: Boolean, detail: String) { out += (if (good) "ok   " else "FAIL ") + "tgtstpt: " + what + " — " + detail }
        out += "ok   tgtstpt: " + WdpFixtureMission.describe(m)
        val s4 = m.steerpoint(4)?.takeIf { it.x != 0.0 || it.y != 0.0 }
        val s5 = m.steerpoint(5)?.takeIf { it.x != 0.0 || it.y != 0.0 }
        if (s4 == null || s5 == null) {
            out += "note tgtstpt: the mission places no steerpoint 4 and 5 (give BMSC_WDP_ROUTE a copy of a mission file): not judged"
            return out
        }
        val s = Session(fx).also { it.feed(WdpPage.TOSS) }
        fun box(w: WdpWiring, page: WdpPage) = edt { w.values(page.hidden)["numWaypoint"] }
        fun near(a: Pair<Double, Double>?, x: Double, y: Double) = a != null && kotlin.math.abs(a.first - x) <= 1.0 && kotlin.math.abs(a.second - y) <= 1.0
        fun fmt(a: Pair<Double, Double>?) = a?.let { "%.0f, %.0f".format(it.first, it.second) } ?: "none"
        val d = m.defaultStpt
        for ((w, p) in listOf(s.toss as WdpWiring to WdpPage.TOSS, s.popup to WdpPage.POPUP, s.hadb to WdpPage.HADB)) {
            val b = box(w, p)
            if (d != null) check("${p.label} opens on the default TGT STPT", b?.trim() == d.toString(), "box $b, default $d")
            else out += "note tgtstpt: no default TGT STPT in this mission; ${p.label} keeps its own ($b)"
        }
        val popupBefore = edt { s.popup.values(WdpPage.POPUP.hidden).values.toMap() }
        val hadbBefore = edt { s.hadb.values(WdpPage.HADB.hidden).values.toMap() }
        edt { s.onValue(WdpPage.TOSS, "numWaypoint", "5") }
        val plot = edt { s.toss.plan.attackPlot() }
        check("TOSS TGT STPT 5: the target is STPT 5", box(s.toss, WdpPage.TOSS)?.trim() == "5" && near(plot?.target, s5.x, s5.y),
            "box ${box(s.toss, WdpPage.TOSS)}, target ${fmt(plot?.target)}, STPT 5 at ${fmt(s5.x to s5.y)} (${m.source(5)?.label})")
        check("TOSS TGT STPT 5: the IP is STPT 4", near(plot?.ip, s4.x, s4.y),
            "IP ${fmt(plot?.ip)}, STPT 4 at ${fmt(s4.x to s4.y)} (${m.source(4)?.label})")
        val popupAfter = edt { s.popup.values(WdpPage.POPUP.hidden).values.toMap() }
        val hadbAfter = edt { s.hadb.values(WdpPage.HADB.hidden).values.toMap() }
        check("the Pop-up page is unchanged", popupAfter == popupBefore,
            (popupAfter.keys + popupBefore.keys).filter { popupAfter[it] != popupBefore[it] }.take(5).joinToString { "$it: ${popupBefore[it]} → ${popupAfter[it]}" }.ifEmpty { "TGT STPT ${popupAfter["numWaypoint"]}, every value the same" })
        check("the HADB page is unchanged", hadbAfter == hadbBefore,
            (hadbAfter.keys + hadbBefore.keys).filter { hadbAfter[it] != hadbBefore[it] }.take(5).joinToString { "$it: ${hadbBefore[it]} → ${hadbAfter[it]}" }.ifEmpty { "TGT STPT ${hadbAfter["numWaypoint"]}, every value the same" })
        // where STPT 5 came from is the TGT STPT box's tip (WDP's Campaign lamp is off the page: D87)
        val tip = edt { s.toss.values(WdpPage.TOSS.hidden).values }["numWaypoint.tip"].orEmpty()
        check("the TGT STPT tip says where STPT 5 came from", m.source(5)?.let { "position from ${it.from}" in tip } == true,
            "source ${m.source(5)?.label}, tip \"$tip\"")
        // IP STPT (D87): the IP follows the target (STPT 4); another IP picked moves the IP and the VIP lines' steerpoint
        val s3 = m.steerpoint(3)?.takeIf { it.x != 0.0 || it.y != 0.0 }
        if (s3 != null) {
            edt { s.onValue(WdpPage.TOSS, "numIPpoint", "3") }
            val plot3 = edt { s.toss.plan.attackPlot() }
            val vip3 = edt { s.toss.plan.navOffsets()["VIP"]?.stpt }
            check("TOSS IP STPT 3: the IP is STPT 3 and the VIP lines name it", near(plot3?.ip, s3.x, s3.y) && vip3 == 3 && box(s.toss, WdpPage.TOSS)?.trim() == "5",
                "IP ${fmt(plot3?.ip)}, STPT 3 at ${fmt(s3.x to s3.y)}, VIP stpt $vip3, TGT ${box(s.toss, WdpPage.TOSS)}")
            edt { s.onValue(WdpPage.TOSS, "numIPpoint", "4") }
            check("TOSS IP STPT 4 again follows the target", edt { s.toss.plan.ipStpt } == null && near(edt { s.toss.plan.attackPlot() }?.ip, s4.x, s4.y),
                "ipStpt ${edt { s.toss.plan.ipStpt }}")
            // a number typed is that steerpoint, even one away from the IP shown and with no position (then no VIP);
            // the arrows, which the box reports by name ("numIPpoint.arrows"), pass over a steerpoint with no position
            fun placed(n: Int) = m.steerpoint(n)?.let { it.x != 0.0 || it.y != 0.0 } == true
            val max = com.bmscompanion.app.ui.screens.wdp.AttackSelection.IP_MAX
            // an empty steerpoint e beside a placed one q (neither the target nor the IP that follows it)
            val pair = (1..max).flatMap { e -> listOf(e to e + 1, e to e - 1) }
                .firstOrNull { (e, q) -> q in 1..max && !placed(e) && placed(q) && e != 5 && q != 5 && q != 4 }
            if (pair != null) {
                val (e, q) = pair
                val by = if (e > q) 1 else -1
                val ipBox = com.bmscompanion.app.ui.screens.wdp.AttackSelection.IP_BOX
                check("the IP STPT box reports its arrows by name", edt { s.toss.values(WdpPage.TOSS.hidden) }["$ipBox.arrows"] == "click", "")
                edt { s.onValue(WdpPage.TOSS, ipBox, q.toString()) }
                edt { s.onValue(WdpPage.TOSS, ipBox, e.toString()) }
                check("TOSS IP STPT $e typed over $q: taken as typed though it has no position, so no VIP",
                    edt { s.toss.plan.ipStpt } == e && !edt { s.toss.plan.doVip } && box(s.toss, WdpPage.TOSS)?.trim() == "5",
                    "ipStpt ${edt { s.toss.plan.ipStpt }}, VIP ${edt { s.toss.plan.doVip }}, box ${edt { s.toss.values(WdpPage.TOSS.hidden) }[ipBox]}")
                edt { s.onValue(WdpPage.TOSS, ipBox, q.toString()) }
                edt { s.onClick(WdpPage.TOSS, "$ipBox:" + if (by > 0) "up" else "down") }
                val next = generateSequence(e + by) { it + by }.takeWhile { it in 1..max }.firstOrNull { placed(it) && it != 5 }
                check("TOSS IP STPT $q, ${if (by > 0) "up" else "down"} arrow: passes over STPT $e (no position)" + (next?.let { " to STPT $it" } ?: ", nowhere to go, stays"),
                    edt { s.toss.plan.ipWaypoint } == (next ?: q), "IP ${edt { s.toss.plan.ipWaypoint }}")
                edt { s.onValue(WdpPage.TOSS, ipBox, "4") }
            } else out += "note tgtstpt: no steerpoint without a position next to a placed one: typed IP over an empty steerpoint not judged"
        } else out += "note tgtstpt: the mission places no steerpoint 3: IP STPT not judged"
        // the page's Save to DTC fills the card once it has saved (D87; there is no Send to DataCard): what it does then
        // ([AttackSelection.saved]) leaves the card's Delivery block as the card's own TOSS button does — all but the
        // "Callsign.ini saved" lamp, which stays green after a save (the cartridge holds the attack) where the button
        // leaves the attack waiting for the card's Save DTC
        runCatching {
            s.feed(WdpPage.DATACARD)
            com.bmscompanion.app.ui.screens.wdp.AttackSelection.card = { s.dataCard }
            val dc = WdpPage.DATACARD
            val lamp = setOf("pnlCallsignGreen", "pnlCallsignRed")
            edt { s.dataCard.onClick("btnTOSS") }
            val viaCard = edt { s.dataCard.values(dc.hidden).values.toMap() }
            edt { s.dataCard.onClick("btnNone") }
            val none = edt { s.dataCard.plan.attack.strProfile }
            edt { com.bmscompanion.app.ui.screens.wdp.AttackSelection.saved("TOSS") }
            val viaPage = edt { s.dataCard.values(dc.hidden).values.toMap() }
            val diff = (viaCard.keys + viaPage.keys).filter { it !in lamp && viaCard[it] != viaPage[it] }
            check("Save to DTC's card fill leaves the Delivery block exactly as the card's TOSS button", diff.isEmpty() && none == "None" && edt { s.dataCard.plan.attack.strProfile } == "TOSS",
                "profile $none → ${edt { s.dataCard.plan.attack.strProfile }}, Delivery ${viaPage["txtWpn_AttackType"]}; " +
                    diff.take(5).joinToString { "$it: ${viaCard[it]} vs ${viaPage[it]}" }.ifEmpty { "every card value the same" })
            val tossValues = edt { s.toss.values(WdpPage.TOSS.hidden).values }
            check("no Send to DataCard on the page; Save to DTC's tip says it fills the card",
                tossValues.keys.none { it.startsWith("btnSendToCard") } && tossValues["btnSaveDTC.tip"]?.contains("DataCard's Delivery section") == true,
                tossValues["btnSaveDTC.tip"].orEmpty())
        }.onFailure { out += "FAIL tgtstpt: the card fill threw " + trace(it) }
        com.bmscompanion.app.ui.screens.wdp.AttackSelection.card = null
        // the same mission handed on again (the cartridge saved: a new file time) keeps the pilot's box
        val again = m.copy(dtc = (m.dtc ?: com.bmscompanion.app.data.mission.Dtc()).let { it.copy(modified = it.modified + 1000) })
        edt { s.toss.onMission(again); s.popup.onMission(again); s.hadb.onMission(again) }
        check("the same mission again keeps TOSS on 5", box(s.toss, WdpPage.TOSS)?.trim() == "5", "box ${box(s.toss, WdpPage.TOSS)}")
        // another mission (another package) sets the box to its default once more
        val other = again.copy(briefing = again.briefing?.let { b -> b.copy(overview = b.overview.copy(packageId = (b.overview.packageId ?: "") + "-other")) },
            flight = again.flight?.let { f -> f.copy(row = f.row.copy(id = f.row.id + "-other")) })
        if (d != null && d != 5) {
            edt { s.toss.onMission(other) }
            check("a new mission sets TOSS to its default again", box(s.toss, WdpPage.TOSS)?.trim() == d.toString(), "box ${box(s.toss, WdpPage.TOSS)}, default $d")
        }
        return out
    }

    /** `all`, a page (by its name, label or form), or `dtc:tabSTPT` for one DTC tab. */
    private fun pagesFor(which: String): List<Triple<String, WdpPage, String?>> {
        val (name, tab) = which.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
        return WdpPage.entries.filter { p ->
            // the app's own pages (the Map page) have no WDP layout, so nothing of WDP's to compare
            !p.native && (name.equals("all", true) || p.name.equals(name, true) || p.label.equals(name, true) || p.form.equals(name, true))
        }.map { Triple(it.name.lowercase(), it, tab) }
    }

    private const val CHILD = "bmsc.wdpoutcome.child"

    /**
     * Runs the check in a JVM of its own on a private copy of the program's own files (its jar and class folders;
     * the libraries in Gradle's cache are shared). The program runs from `desktop/build/libs/desktop.jar`, and any
     * build started while the check runs — another checkout step, an IDE — rewrites that jar in place: the classes
     * and the pages' layouts the check had not read yet then fail with "invalid LOC header" half way through, which
     * is how the first full runs ended. The copy is taken under [outDir] and removed afterwards.
     */
    private fun relaunch(args: List<String>): String {
        val out = File(args[1]).also { it.mkdirs() }
        val copy = File(out, "_program").also { it.deleteRecursively(); it.mkdirs() }
        val gradleHome = File(System.getProperty("user.home"), ".gradle").canonicalPath
        val cp = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }.mapIndexed { i, e ->
            val f = File(e)
            if (!f.exists() || f.canonicalPath.startsWith(gradleHome)) e
            else File(copy, "$i-${f.name}").also { d -> if (f.isDirectory) f.copyRecursively(d, true) else f.copyTo(d, true) }.path
        }
        val javaExe = File(System.getProperty("java.home"), "bin/java").path
        val jvm = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.filter { !it.startsWith("-agentlib") && !it.startsWith("-javaagent") }
        val cmd = listOf(javaExe) + jvm + listOf("-Xmx1g", "-XX:MaxMetaspaceSize=384m", "-D$CHILD=1", "-cp", cp.joinToString(File.pathSeparator),
            "com.bmscompanion.desktop.MainKt", "--wdpoutcome") + args
        val code = try { ProcessBuilder(cmd).inheritIO().start().waitFor() } finally { copy.deleteRecursively() }
        check(code == 0) { "the check's own run ended with exit code $code (its error, if any, is in the error log in the settings folder)" }
        return File(out, "summary.txt").takeIf { it.isFile }?.readText() ?: "no summary was written"
    }

    /**
     * The typing probe checked on two boxes of the check's own, through the same renderer: one whose page keeps every
     * key, one whose page drops them all. The first must come out ok and the second must not — the evidence that a
     * run with no TYPE-LOST means the pages kept what was typed, not that the probe cannot see a loss.
     */
    private fun selfCheck(stage: Stage): String {
        val form = WdpForm(form = "selfcheck", clientW = 320, clientH = 80, roots = listOf(
            WdpControl(name = "txtA", kind = "text", x = 10, y = 10, w = 200, h = 22),
            WdpControl(name = "txtB", kind = "text", x = 10, y = 44, w = 200, h = 22),
        ))
        class StubPage(val keep: Boolean) : WdpWiring {
            var a by mutableStateOf("Fixture")
            override fun values(hidden: List<String>) = WdpValues(mapOf("txtA" to "shown", "txtA.text" to a, "txtB" to "shown", "txtB.text" to ""))
            override fun onValue(name: String, value: String) { if (keep && name == "txtA") a = value }
            override fun onClick(name: String) {}
        }
        fun probe(keep: Boolean): String {
            val w = StubPage(keep)
            stage.showStub(form, w)
            stage.stable(8, 10).close()
            val node = stage.editableAt(SkRect.makeXYWH(10f, 10f, 200f, 22f)) ?: return "no field found"
            stage.click(node.boundsInRoot.center)
            stage.ctrlA()
            for (ch in "VIPER 21") { stage.type(ch); stage.frame().close() }
            val during = stage.textOf(node.id)
            stage.tab()
            stage.frame().close()
            val after = stage.textOf(node.id)
            // the same rules as a typing row
            val flag = when {
                during?.trim() != "VIPER 21" -> "TYPE-LOST"
                w.a.trim() == "Fixture" -> if (after?.trim() == "VIPER 21") "TYPE-LOST" else "REVERTED"
                else -> "ok"
            }
            return "field while typing \"${during ?: "?"}\", after leaving \"${after ?: "?"}\", page \"${w.a}\" → $flag"
        }
        val kept = probe(true)
        val dropped = probe(false)
        stage.clearStub()
        val pass = kept.endsWith("→ ok") && !dropped.endsWith("→ ok")
        return (if (pass) "ok   " else "FAIL ") + "self-check of the typing probe: a box whose page keeps each key: $kept; a box whose page drops them: $dropped"
    }

    // ------------------------------------------------------------------------------------------------ views

    /** One state of a page the pilot works in: a DTC tab, or the page as it opens. */
    private class ViewDef(val id: String, val setup: List<Op>, val inScope: (List<String>, WdpControl) -> Boolean)

    private val CARD_PANELS = setOf("pnlPage_1", "pnlPage_2", "pnlBrief", "pnlWx")

    private fun viewsOf(page: WdpPage, form: WdpForm, tabOnly: String?): List<ViewDef> = when (page) {
        WdpPage.DTC -> {
            val tabs = form.find("tabDTC")?.children.orEmpty().map { it.name }
            tabs.filter { tabOnly == null || it.equals(tabOnly, true) || it.removePrefix("tab").equals(tabOnly, true) }.map { t ->
                ViewDef(t, listOf(Op.Set("tabDTC", t, "select tab $t")), inScope = { anc, c -> t in anc || (t == "tabMain" && c.kind == "tabs") })
            }
        }
        // the three card pages are three views of one layout: each takes its own panel, and the buttons down the left
        // are the DataCard's, those down the right the Coordination Card's
        WdpPage.BRIEFING -> listOf(ViewDef("briefing", emptyList()) { anc, _ -> "pnlBrief" in anc || "pnlWx" in anc })
        WdpPage.DATACARD -> listOf(ViewDef("datacard", emptyList()) { anc, c -> "pnlPage_1" in anc || (anc.isEmpty() && c.name !in CARD_PANELS && c.x < 1013) })
        WdpPage.COORDINATION -> listOf(ViewDef("coordination", emptyList()) { anc, c -> "pnlPage_2" in anc || (anc.isEmpty() && c.name !in CARD_PANELS && c.x >= 1013) })
        else -> listOf(ViewDef(page.name.lowercase(), emptyList()) { _, _ -> true })
    }

    // ------------------------------------------------------------------------------------------------ actions

    /** What is done to the window on top — the page, a child window, or a message box. */
    private sealed class Op(val control: String, val label: String) {
        class Click(control: String, val name: String, label: String) : Op(control, label)
        class Set(control: String, val value: String, label: String) : Op(control, label)
        class Answer(val button: String) : Op("(message)", "answer \"$button\"")
        class Type(control: String, val text: String, val number: Boolean, val fast: Boolean, val commit: String? = null, commitCaption: String? = null) :
            Op(control, (if (fast) "type fast " else "type ") + "\"$text\"" + (commitCaption?.let { " then press $it" } ?: ""))

        override fun toString() = "$control:$label"
    }

    /** An action on one control; [optional] ones (a tap on a label WDP gives no handler) are listed only if they did something. */
    private class Act(val p: Placed, val op: Op, val optional: Boolean)

    private val TAPPABLE = setOf("label", "panel", "picture")
    private val INTERACTIVE = setOf("button", "check", "radio", "combo", "number", "slider", "text", "grid", "list", "tabs")

    private fun actionsFor(p: Placed, v: Map<String, String>, ev: Set<String>, fast: Boolean, sourceKnown: Boolean = false): List<Act> {
        val c = p.c
        val n = c.name
        fun spread(items: List<String>): List<String> =
            if (items.size <= PICKS) items else (0 until PICKS).map { items[Math.round(it * (items.size - 1) / (PICKS - 1.0)).toInt()] }.distinct()
        return when (c.kind) {
            "button", "check", "radio" -> listOf(Act(p, Op.Click(n, n, "press"), false))
            in TAPPABLE -> {
                val click = ev.any { it in CLICKS }
                val right = ev.any { it.endsWith("(R)") } || (ev.isEmpty() && c.kind != "label")
                listOf(Act(p, Op.Click(n, n, "tap"), !click)) +
                    (if (right) listOf(Act(p, Op.Click(n, "$n:right", "right-click"), !ev.any { it.endsWith("(R)") })) else emptyList())
            }
            "combo" -> {
                val items = v["$n.items"]?.split('\n')?.filter { it.isNotEmpty() } ?: c.items
                if (items.isEmpty()) listOf(Act(p, Op.Click(n, n, "tap (no items)"), false))
                else spread(items).map { Act(p, Op.Set(n, it, "pick \"${it.take(40)}\""), false) }
            }
            "text" -> if (c.readOnly) emptyList() else {
                val t = typedFor(n, shownText(c, v), number = false, multiline = c.multiline)
                listOf(Act(p, Op.Type(n, t, number = false, fast = false), false)) +
                    (if (fast) listOf(Act(p, Op.Type(n, t, number = false, fast = true), false)) else emptyList())
            }
            "number" -> {
                val now = v[n] ?: c.text ?: ""
                listOf(
                    Act(p, Op.Set(n, com.bmscompanion.app.ui.screens.wdp.stepped(now, 1), "step up"), false),
                    Act(p, Op.Set(n, com.bmscompanion.app.ui.screens.wdp.stepped(now, -1), "step down"), false),
                ) + (
                    // a spinner narrower than its arrows (the card's route spinners, 18 px) has no room to type in, in WDP either
                    if (c.w < 24) emptyList()
                    else listOf(Act(p, Op.Type(n, typedFor(n, now, number = true), number = true, fast = false), false))
                )
            }
            "slider" -> {
                val min = v["$n.min"]?.toIntOrNull() ?: 0
                val max = v["$n.max"]?.toIntOrNull() ?: 10
                listOf(min to "slider to min", (min + max) / 2 to "slider to mid", max to "slider to max").distinctBy { it.first }
                    .map { (x, l) -> Act(p, Op.Set(n, x.toString(), "$l ($x)"), false) }
            }
            "tabs" -> c.children.filter { (!it.hidden || v[it.name] == "shown") && v[it.name] != "hidden" }
                .map { Act(p, Op.Set(n, it.name, "select tab ${it.name}"), false) }
            "grid", "list" -> {
                val rows = if (c.kind == "grid") v["$n.rows"]?.takeIf { it.isNotEmpty() }?.split('\n')?.size ?: 0
                    else (v["$n.items"]?.let { if (it.isEmpty()) 0 else it.split('\n').size } ?: c.items.size)
                if (rows == 0) listOf(Act(p, Op.Click(n, "$n:row:0", "tap row 0 (empty)"), true))
                else listOf(0, 1, rows - 1).filter { it in 0 until rows }.distinct().map { Act(p, Op.Click(n, "$n:row:$it", "tap row $it"), false) } +
                    // a double-tap is listed when it does something, or when WDP answers one there (or its code is not at hand)
                    listOf(0, rows - 1).distinct().map { Act(p, Op.Click(n, "$n:open:$it", "double-tap row $it"), sourceKnown && ev.none { "DoubleClick" in it }) }
            }
            else -> emptyList()
        }
    }

    private val CLICKS = setOf("Click", "MouseClick", "MouseClick(R)", "MouseDown", "MouseDown(R)", "MouseUp", "MouseUp(R)", "DoubleClick", "MouseDoubleClick")

    /**
     * What a pilot would type into a box: a callsign where the box holds a name, a frequency where it holds one, the
     * number a digit on where it holds a number (so a masked box keeps its shape), and a callsign otherwise.
     */
    private fun typedFor(name: String, current: String, number: Boolean, multiline: Boolean = false): String {
        val n = name.lowercase()
        val cur = current.trim()
        if (number) return bump(cur).ifEmpty { "5" }
        if (multiline || cur.length > 24 || '\n' in cur) return "VIPER 21"
        if (Regex("callsign|pilot|flight|name|remark|note|comment|title|list|desc|word|agency|package|mission").containsMatchIn(n)) return "VIPER 21"
        if (Regex("uhf").containsMatchIn(n)) return "251.25"
        if (Regex("vhf").containsMatchIn(n)) return "121.50"
        if (Regex("ils").containsMatchIn(n)) return "109.30"
        if (Regex("freq").containsMatchIn(n)) return "251.25"
        if (cur.any { it.isDigit() }) return bump(cur)
        if (Regex("alt|fuel|hdg|head|spd|speed|dist|time|tot|wgt|weight|temp|wind|elev|rng|range|bingo|joker|laser|code|nr|num|qty|tacan|tcn|lat|lon|deg|min|sec|kts|nm|qnh|alow").containsMatchIn(n)) return "1234"
        return "VIPER 21"
    }

    /** [s] with its last digit one lower (a 0 becomes 1): a different value of the same shape, inside most ranges. */
    private fun bump(s: String): String {
        val i = s.indexOfLast { it.isDigit() }
        if (i < 0) return s
        val d = s[i]
        return s.substring(0, i) + (if (d == '0') '1' else d - 1) + s.substring(i + 1)
    }

    private val STATES = setOf("shown", "hidden", "checked", "unchecked")

    /** A name with its numbers padded, so "btnChange_2" sorts before "btnChange_10". */
    private fun naturalKey(n: String): String = Regex("[0-9]+").replace(n) { it.value.padStart(6, '0') }

    /** What the renderer shows in a control: `<name>.text`, else its value unless that is a state, else the designer's. */
    private fun shownText(c: WdpControl, v: Map<String, String>): String =
        v["${c.name}.text"] ?: v[c.name]?.takeUnless { it in STATES } ?: c.text ?: ""

    // ------------------------------------------------------------------------------------------------ where controls are

    /** A control drawn on the window, with where it is on the window (its designer pixels) and its parents. */
    private class Placed(val c: WdpControl, val x: Int, val y: Int, val enabled: Boolean, val anc: List<String>)

    /** The controls the renderer draws for [v], as `WdpFormView` decides (hidden, the tab shown, stacked alternatives). */
    private fun placed(form: WdpForm, v: WdpValues): List<Placed> {
        val out = ArrayList<Placed>()
        fun walk(c: WdpControl, px: Int, py: Int, enabled: Boolean, anc: List<String>) {
            val state = v[c.name]
            if (c.hidden && state != "shown") return
            if (state == "hidden") return
            val en = enabled && v["${c.name}.enabled"] != "false"
            val ax = px + c.x
            val ay = py + c.y
            out += Placed(c, ax, ay, en, anc)
            val kids = if (c.kind == "tabs") c.children.filter { it.name == selectedTab(c, v) } else visibleChildren(c, v)
            for (k in kids) walk(k, ax, ay, en, anc + c.name)
        }
        for (r in form.roots) walk(r, 0, 0, true, emptyList())
        return out
    }

    private fun selectedTab(c: WdpControl, values: WdpValues): String? {
        val pages = c.children.filter { !it.hidden || values[it.name] == "shown" }.filter { values[it.name] != "hidden" }
        return values[c.name]?.takeIf { v -> pages.any { it.name == v } } ?: pages.firstOrNull()?.name
    }

    // the renderer's rule for siblings stacked at one rectangle (WdpForm.kt visibleChildren), repeated here
    private fun visibleChildren(c: WdpControl, values: WdpValues): List<WdpControl> {
        if (c.children.size < 2) return c.children
        val out = ArrayList<WdpControl>(c.children.size)
        val taken = HashSet<Long>()
        val chosen = c.children.filter { values[it.name] == "shown" }
        for (k in chosen) taken += rectKey(k)
        for (k in c.children) {
            if (k.hidden && values[k.name] != "shown") continue
            val key = rectKey(k)
            val alternative = c.children.count { it !== k && rectKey(it) == key && it.kind == k.kind } > 0
            when {
                k in chosen -> out += k
                !alternative -> out += k
                key in taken -> continue
                else -> { taken += key; out += k }
            }
        }
        return out
    }

    private fun rectKey(k: WdpControl): Long =
        (k.x.toLong() and 0xFFFF) or ((k.y.toLong() and 0xFFFF) shl 16) or ((k.w.toLong() and 0xFFFF) shl 32) or ((k.h.toLong() and 0xFFFF) shl 48)

    // ------------------------------------------------------------------------------------------------ the Planner, fresh

    private class Fixture(val mission: WdpMission, val cartridgeText: String?)

    /** The fixture's cartridge copy, with what reaches it written down: its saves (all in memory). */
    private class Recording(private val inner: DtcSource) : DtcSource by inner {
        val log = CopyOnWriteArrayList<String>()
        override suspend fun load(callsign: String?): CartridgeState? {
            val st = inner.load(callsign)
            // the PC names the requested pilot back even when there is no file for them; the check has one cartridge
            if (callsign != null && !callsign.equals(st?.callsign, ignoreCase = true)) {
                return CartridgeState(available = false, callsign = callsign, file = "$callsign.ini",
                    error = "Callsign.ini: $callsign has no cartridge on this PC (the check holds one).")
            }
            return st
        }

        override suspend fun save(callsign: String?, edits: List<CartridgeEdit>): CartridgeState? {
            log += "saved ${edits.size} keys: " + edits.joinToString("; ") { "[${it.section}] ${it.key}=${it.value}" }.take(400)
            return inner.save(callsign, edits)
        }
    }

    /** Every page of the Planner, made and joined as `WdpPlanner` does, and given the mission. */
    private class Session(private val fx: Fixture) {
        val src = Recording(WdpDtcFixture.source())
        // made when first reached: a page gets the pages it talks to, as the Planner joins them, and no others — the
        // DTC page needs none of the rest, and making the DataCard and Performance pages costs a read of the airports
        val toss by lazy { TossWiring() }
        val popup by lazy { PopupWiring() }
        val hadb by lazy { HadbWiring() }
        val dtc by lazy { DtcWiring(src) }
        val performance by lazy {
            // every fresh page starts from WDP's first run (no Setup.ini), whatever the last action saved
            Repo.putString(PerformanceWiring.INI_KEY, null)
            PerformanceWiring()
        }
        val dataCard by lazy {
            DataCardWiring().also { card ->
                card.attackPages = { Triple(popup.plan, hadb.plan, toss.plan) }
                WdpHandOff.join(card, performance)
            }
        }

        /**
         * The Planner's LaunchedEffects: coordinates, then the mission to every page, then the card ↔ Performance
         * hand-off — for the pages [page] talks to: an attack page and its Save to DTC (the DTC page); the DTC page
         * alone; the card pages and everything they read (the attack pages' plans, the DTC page, Performance); the
         * Performance page and the card it hands its figures to.
         */
        fun feed(page: WdpPage) {
            val m = fx.mission
            val card = page == WdpPage.BRIEFING || page == WdpPage.DATACARD || page == WdpPage.COORDINATION
            val attack = card || page == WdpPage.POPUP || page == WdpPage.HADB || page == WdpPage.TOSS
            val perf = card || page == WdpPage.PERFORMANCE
            if (attack) {
                m.coords?.let { c ->
                    popup.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
                    hadb.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
                }
                toss.onMission(m); popup.onMission(m); hadb.onMission(m)
            }
            if (perf) runBlocking { dataCard.prepare(m) { fx.cartridgeText } }
            if (attack || page == WdpPage.DTC) dtc.onMission(m)
            if (perf) {
                runBlocking { performance.prepare(m) }
                WdpHandOff.cardToPerformance(dataCard, performance, m.briefing)
            }
            if (card) { dataCard.refreshAttack(); WdpHandOff.performanceToCard(performance, dataCard) }
        }

        private fun isCard(p: WdpPage) = p == WdpPage.BRIEFING || p == WdpPage.DATACARD || p == WdpPage.COORDINATION

        fun wiring(p: WdpPage): WdpWiring = when (p) {
            WdpPage.TOSS -> toss
            WdpPage.POPUP -> popup
            WdpPage.HADB -> hadb
            WdpPage.DTC -> dtc
            WdpPage.PERFORMANCE -> performance
            else -> dataCard
        }

        // what the Planner passes on after the page has handled it (WdpPlanner's onValue / onClick)
        fun onValue(p: WdpPage, name: String, value: String) {
            val w = wiring(p)
            w.onValue(name, value)
            // by the page, not by comparing with the other pages: that would make them (and a Performance page made
            // for nothing writes its first-run settings over the ones the check started from)
            if (isCard(p)) WdpHandOff.afterCard(name, dataCard, performance, fx.mission.briefing)
            else if (p == WdpPage.PERFORMANCE) WdpHandOff.performanceToCard(performance, dataCard)
        }

        fun onClick(p: WdpPage, name: String) {
            val w = wiring(p)
            w.onClick(name)
            // by the page, not by comparing with the other pages: that would make them (and a Performance page made
            // for nothing writes its first-run settings over the ones the check started from)
            if (isCard(p)) WdpHandOff.afterCard(name, dataCard, performance, fx.mission.briefing)
            else if (p == WdpPage.PERFORMANCE) WdpHandOff.performanceToCard(performance, dataCard)
        }
    }

    /** The settings the pages remember (Setup.ini's kin), put back before every fresh Planner and at the end. */
    private class PrefsGuard {
        private val props: java.util.Properties? = runCatching {
            val f = Repo::class.java.getDeclaredField("prefs").also { it.isAccessible = true }
            (runCatching { f.get(null) }.getOrNull() ?: f.get(Repo)) as java.util.Properties
        }.getOrNull()
        private val original = props?.let { p -> synchronized(p) { java.util.Properties().also { it.putAll(p) } } }

        fun restore() {
            val p = props ?: return
            val o = original ?: return
            synchronized(p) { p.clear(); p.putAll(o) }
        }

        /** The settings as they are now: what a Save slot, a saved card or a remembered page setting wrote. */
        fun current(): Map<String, String> {
            val p = props ?: return emptyMap()
            return synchronized(p) { p.entries.associate { it.key.toString() to it.value.toString() } }
        }

        fun restoreOriginal() {
            restore()
            // written back to the file as it was found
            Repo.putString("wdp_outcome_probe", null)
        }
    }

    // ------------------------------------------------------------------------------------------------ one page

    private class Row(
        val n: Int, val page: String, val view: String, val window: String, val depth: Int, val path: String,
        val control: String, val kind: String, val caption: String, val wdp: String, val action: String,
        var flag: String, var opened: String = "", var message: String = "", var changes: Int = 0, var changed: String = "",
        var cartridge: String = "", var pixels: Double = 0.0, var image: String = "", var image2: String = "",
        var error: String = "", var note: String = "",
    ) {
        @Volatile var thumb: Future<ByteArray?>? = null

        fun suspicion(): Int = if (note.contains("the check has no installer")) 5 else when (flag) {
            "THREW" -> 100
            "TYPE-LOST" -> 95
            "REVERTED" -> 70
            "NO-FIELD" -> 60
            "NO-UI" -> 55
            "MSG" -> if (Regex("(?i)not (supported|available|possible|implemented|offered)|cannot|can't|unavailable|only in|does not|doesn't|isn't|not yet|no longer").containsMatchIn(message)) 80 else 50
            "DEAD" -> if (wdp.isNotBlank()) 65 else 45
            else -> if (note.contains("did not take focus")) 30 else 0
        }
    }

    private class PageCtx(
        val pageId: String, val page: WdpPage, val form: WdpForm, val dir: File, val fx: Fixture, val prefs: PrefsGuard,
        val stage: Stage, val writer: Writer, wdpSrc: File?,
    ) {
        val rows = ArrayList<Row>()
        lateinit var view: ViewDef
        val exploredWindows = HashSet<String>()
        val exploredReveals = HashSet<String>()
        val exploredQuestions = HashSet<String>()
        private val events = WdpEvents(wdpSrc)
        private val fast = System.getenv("BMSC_WDP_OUTCOME_FAST") == "1"
        private val allOpeners = System.getenv("BMSC_WDP_OUTCOME_OPENERS").equals("all", true)

        /** A fresh Planner with the view's setup and [path] done: the state an action starts from. */
        fun fresh(path: List<Op>): Session {
            edt { WdpDialogs.stack.clear() }
            prefs.restore()
            val t = System.nanoTime()
            val s = Session(fx)
            s.feed(page)
            settle(s)
            // drawn before anything is done to it, as a pilot sees it first: some of a page is set up by drawing it
            // (the attack map's picture for Save Map, the platform's picture actions)
            for (op in view.setup) {
                edt { perform(s, op) }
                settle(s)
            }
            stage.show(s, page, form)
            stage.frame().close()
            for (op in path) {
                edt { perform(s, op) }
                settle(s)
                stage.frame().close()
            }
            timeFresh += System.nanoTime() - t
            freshCount++
            return s
        }

        var timeFresh = 0L
        var freshCount = 0
        var timeSettle = 0L
        var timePicture = 0L

        /** An arrow of a box that reports its arrows by name, not as the stepped value (`"<name>.arrows"`, `upDownArrow`). */
        fun arrowsByName(v: WdpValues, op: Op.Set) = op.label.startsWith("step ") && v["${op.control}.arrows"] == "click"
        fun arrowOf(op: Op.Set) = if (op.label == "step up") ":up" else ":down"

        /** Does [op] to the window on top, as the renderer would hand it on. */
        fun perform(s: Session, op: Op) {
            val top = WdpDialogs.stack.lastOrNull()
            when (op) {
                is Op.Answer -> {
                    val m = top as? WdpMessage ?: error("no message is open to answer")
                    WdpDialogs.stack.remove(m)
                    m.onAnswer?.invoke(op.button)
                }
                is Op.Click -> when (top) {
                    null -> s.onClick(page, op.name)
                    is WdpDialog -> top.wiring?.onClick(op.name)
                    else -> error("a message box is open")
                }
                is Op.Set -> when (top) {
                    // an up/down box whose arrows come back by name ("<name>.arrows", upDownArrow): its steps are clicks
                    null -> if (arrowsByName(s.wiring(page).values(page.hidden), op)) s.onClick(page, op.control + arrowOf(op))
                        else s.onValue(page, op.control, op.value)
                    is WdpDialog -> top.wiring?.onValue(op.control, op.value)
                    else -> error("a message box is open")
                }
                // replayed as the keys would arrive: the text growing one character at a time, then the box left
                is Op.Type -> {
                    val on: (String, String) -> Unit = when (top) {
                        null -> { n, v -> s.onValue(page, n, v) }
                        is WdpDialog -> { n, v -> top.wiring?.onValue(n, v) }
                        else -> error("a message box is open")
                    }
                    if (op.number) on(op.control, op.text)
                    else { var t = ""; for (ch in op.text) { t += ch; on(op.control, t) }; on("${op.control}.leave", "") }
                }
            }
        }

        // ---- state

        private inner class Snap(val page: Map<String, String>, val windows: List<Pair<Any, Map<String, String>?>>, val saves: Int, val settings: Map<String, String>) {
            val stack: List<Any> get() = windows.map { it.first }
            val top: Any? get() = windows.lastOrNull()?.first
        }

        private fun snap(s: Session): Snap = edt {
            val pv = runCatching { s.wiring(page).values(page.hidden).values }.getOrDefault(emptyMap())
            val ws = WdpDialogs.stack.toList().map { d ->
                d to (d as? WdpDialog)?.let { runCatching { it.wiring?.values(it.hidden)?.values }.getOrNull() }
            }
            Snap(pv, ws, s.src.log.size, prefs.current())
        }

        /** Waits until the Planner is still: whatever an action started on the UI thread (or off it) has finished. */
        fun settle(s: Session, minStillMs: Long = 12, maxMs: Long = 4000) {
            val start = System.nanoTime()
            try { settleIn(s, start, minStillMs, maxMs) } finally { timeSettle += System.nanoTime() - start }
        }

        private fun settleIn(s: Session, start: Long, minStillMs: Long, maxMs: Long) {
            // still = nothing written to the pages' state (every wiring keeps its version in Compose state, so a
            // change anywhere is a write), no window opened or closed, nothing saved — with the UI thread's queue run
            // through each time, so what a press queued there has happened
            fun sig(): Long = edt {
                var h = writes.get()
                for (d in WdpDialogs.stack) h = h * 31 + System.identityHashCode(d)
                h * 31 + s.src.log.size
            }
            var last = sig()
            var stillSince = System.nanoTime()
            while ((System.nanoTime() - start) / 1_000_000 < maxMs) {
                Thread.sleep(3)
                val now = sig()
                if (now != last) { last = now; stillSince = System.nanoTime() }
                else if ((System.nanoTime() - stillSince) / 1_000_000 >= minStillMs) break
            }
        }

        /** The window on top: its layout, values, where it is drawn in the scene and at what scale. */
        private inner class Win(val name: String, val form: WdpForm, val values: Map<String, String>, val ox: Float, val oy: Float, val k: Float, val isPage: Boolean, val message: WdpMessage?)

        private fun topWindow(s: Session): Win? {
            val top = edt { WdpDialogs.stack.lastOrNull() }
            return when (top) {
                null -> Win("page", form, edt { s.wiring(page).values(page.hidden).values }, 0f, 0f, 1f, true, null)
                is WdpDialog -> {
                    val f = runBlocking { Repo.wdpForm(top.form) } ?: return null
                    val v = edt { top.wiring?.values(top.hidden)?.values } ?: top.hidden.associateWith { "hidden" }
                    val (ox, oy, k) = dialogPlace(f)
                    Win("${top.form} \"${top.title}\"", f, v, ox, oy, k, false, null)
                }
                is WdpMessage -> Win("message \"${top.title}\"", form, emptyMap(), 0f, 0f, 1f, false, top)
                else -> null
            }
        }

        /** Where WdpDialogHost puts a window's form in the scene (the host's own arithmetic, repeated). */
        private fun dialogPlace(f: WdpForm): Triple<Float, Float, Float> {
            val fw = f.width.coerceAtLeast(1).toFloat()
            val fh = f.height.coerceAtLeast(1).toFloat()
            val k = minOf(SCENE_W * 0.94f / fw, (SCENE_H * 0.92f - 30f) / fh, 1.5f)
            val left = (SCENE_W - fw * k) / 2f
            val top = (SCENE_H - (30f + fh * k)) / 2f
            return Triple(left, top + 30f, k)
        }

        private fun interactiveNames(win: Win): Set<String> =
            placed(win.form, WdpValues(win.values)).filter { it.c.kind in INTERACTIVE && !(it.c.kind == "text" && it.c.readOnly) && it.enabled }
                .map { it.c.name }.toSet()

        // ---- exploring a window

        /**
         * Every action on the window on top after [path] (the page when there is none). [only] limits it to the
         * controls an action brought into view.
         */
        fun explore(path: List<Op>, depth: Int, only: Set<String>?) {
            stage.newComposition()
            var clean: Session? = fresh(path)
            val s0 = clean!!
            val win = topWindow(s0) ?: return
            if (win.message != null) { answers(path, depth, win); return }
            stage.show(s0, page, form)
            val baseImg = stage.stable(if (depth == 0 && only == null) 40 else 12, 25)
            val basePx = pixels(baseImg)
            baseImg.close()
            val ev = events.of(win.form.form)
            val shownHere = placed(win.form, WdpValues(win.values)).filter { p -> !win.isPage || view.inScope(p.anc, p.c) }
            // in the order of their names, numbers counted as numbers: the first of a row of like buttons (btnChange_1 …
            // btnChange_24) is the one whose window is followed, and the first steerpoint is the one a mission fills
            val pl = shownHere.filter { p -> only == null || p.c.name in only }.sortedWith(compareBy<Placed> { naturalKey(it.c.name) })
            val acts = pl.flatMap { p -> actionsFor(p, win.values, ev[p.c.name].orEmpty(), fast, events.known) }.toMutableList()
            // what a pilot does after typing: the window's OK (a child window), or the button a page turned into Accept
            // (the DTC page's pilot box) — so an entry is followed to where it is meant to arrive
            val commit = shownHere.firstOrNull { q ->
                q.c.kind == "button" && q.enabled && shownText(q.c, win.values).trim().let { t ->
                    if (win.isPage) t.equals("Accept", true) || t.equals("Apply", true)
                    else Regex("(?i)^(ok|accept|apply|select|save|done|set)$").matches(t)
                }
            }
            if (commit != null) {
                val typed = acts.filter { it.op is Op.Type && !(it.op as Op.Type).fast }
                acts += typed.map { t ->
                    val o = t.op as Op.Type
                    Act(t.p, Op.Type(o.control, o.text, o.number, false, commit.c.name, shownText(commit.c, win.values).trim()), false)
                }
            }
            val disabledDone = HashSet<String>()
            for (a in acts) {
                if (!a.p.enabled) {
                    if (a.optional || !disabledDone.add(a.p.c.name)) continue
                    rows += row(win, depth, path, a, "disabled", ev).also { it.note = "drawn disabled: the renderer passes it no input" }
                    continue
                }
                val s = clean ?: fresh(path)
                clean = null
                if (a.op is Op.Type) { typeInto(s, win, depth, path, a, ev, basePx); continue }
                if (act(s, win, depth, path, a, ev, basePx)) clean = s
            }
        }

        /** A question's every answer, each from a fresh Planner with the question on screen. */
        private fun answers(path: List<Op>, depth: Int, win: Win) {
            val m = win.message ?: return
            for (b in m.buttons) {
                val s = fresh(path)
                val a = Act(Placed(WdpControl(name = "(message)", kind = "button", text = b), 0, 0, true, emptyList()), Op.Answer(b), false)
                act(s, win, depth, path, a, emptyMap(), null)
            }
        }

        private fun row(win: Win, depth: Int, path: List<Op>, a: Act, flag: String, ev: Map<String, Set<String>>) = Row(
            rows.size + 1, pageId, view.id, win.name, depth, path.joinToString(" › "), a.p.c.name, a.p.c.kind,
            if (a.op is Op.Answer) a.op.button else shownText(a.p.c, win.values).replace('\n', ' ').take(60),
            ev[a.p.c.name].orEmpty().sorted().joinToString(","), a.op.label, flag,
        )

        /** One action and its outcome. Returns whether nothing changed (the session is still the window's baseline). */
        private fun act(s: Session, win: Win, depth: Int, path: List<Op>, a: Act, ev: Map<String, Set<String>>, basePx: ByteArray?): Boolean {
            pressedAt = outline(win, a.p)
            val before = snap(s)
            asyncErrors.clear()
            val err = runCatching { edt { perform(s, a.op) } }.exceptionOrNull()
            settle(s)
            val after = snap(s)
            val thrown = err ?: asyncErrors.firstOrNull()
            val newTop = after.top
            val opened = newTop is WdpDialog && newTop !in before.stack
            val message = newTop is WdpMessage && newTop !in before.stack
            val closed = before.stack.any { it !in after.stack }
            val changes = diff(before, after)
            val saves = after.saves - before.saves
            val nothing = thrown == null && !opened && !message && !closed && changes.isEmpty() && saves == 0
            if (nothing) {
                if (a.optional) return true
                val already = (a.p.c.kind == "radio" && win.values[a.p.c.name] == "checked") ||
                    (a.op is Op.Set && (a.p.c.kind == "slider" || a.p.c.kind == "number") && win.values[a.p.c.name]?.trim() == a.op.value.trim()) ||
                    (a.op is Op.Set && a.p.c.kind == "number" && a.op.label == "step down" && win.values[a.p.c.name]?.trim()?.toDoubleOrNull() == 0.0) ||
                    (a.op is Op.Set && a.p.c.kind == "combo" && shownText(a.p.c, win.values).trim() == a.op.value.trim()) ||
                    (a.op is Op.Set && a.p.c.kind == "tabs" && win.values[a.p.c.name] == a.op.value) ||
                    (a.op is Op.Click && a.op.name.startsWith(a.p.c.name + ":row:") && win.values[a.p.c.name + ".selected"] == a.op.name.substringAfterLast(":"))
                // a label WDP answers only on a double-click: the page has no double-click for a label, so a pilot
                // cannot do what WDP offers there
                val wdpEv = ev[a.p.c.name].orEmpty()
                val doubleOnly = a.p.c.kind in TAPPABLE && wdpEv.any { it == "DoubleClick" || it == "MouseDoubleClick" } &&
                    wdpEv.none { it == "Click" || it.startsWith("MouseClick") || it.startsWith("MouseDown") || it.startsWith("MouseUp") }
                rows += row(win, depth, path, a, if (already) "same" else if (doubleOnly) "NO-UI" else "DEAD", ev).also {
                    if (already) it.note = "already chosen"
                    if (doubleOnly && !already) it.note = "WDP answers a double-click here; a label on the page takes no double-click, and a tap does nothing"
                }
                return true
            }
            val r = row(win, depth, path, a, "ok", ev)
            rows += r
            r.changes = changes.size
            r.changed = changes.take(40).joinToString(" | ") + if (changes.size > 40) " | … ${changes.size - 40} more" else ""
            if (saves > 0) r.cartridge = s.src.log.drop(before.saves).joinToString(" / ")
            if (opened) r.opened = (newTop as WdpDialog).let { "${it.form} \"${it.title}\"" }
            if (message) (newTop as WdpMessage).let { r.message = "[${it.title}] ${it.text}" + if (it.buttons.size > 1) "  {${it.buttons.joinToString(" / ")}}" else "" }
            if (closed && !opened && !message) r.note = "closed the window"
            if (newTop is WdpMessage && !message && newTop !== before.top) r.message = "(now on top) [${newTop.title}] ${newTop.text}" + if (newTop.buttons.size > 1) "  {${newTop.buttons.joinToString(" / ")}}" else ""
            if (a.p.c.name == "btnSaveMap" && message) r.note = "the check has no installer, so no file dialog is opened: this is the browser's answer (the PC opens a save dialog)"
            r.flag = when {
                thrown != null -> "THREW"
                opened -> "OPEN"
                message && changes.isEmpty() && saves == 0 -> "MSG"
                else -> "ok"
            }
            if (thrown != null) r.error = trace(thrown)
            // the picture after it
            picture(s, r, win, a, basePx)
            // what it opened or brought into view, one level further
            if (depth < MAX_DEPTH && thrown == null) {
                val next = path + a.op
                when {
                    opened -> {
                        val key = (newTop as WdpDialog).form
                        if (allOpeners || exploredWindows.add(key)) explore(next, depth + 1, null)
                        else r.note = listOf(r.note, "window already followed from an earlier control in this view").filter { it.isNotBlank() }.joinToString("; ")
                    }
                    // a question: every answer. Also a message over another new window or question (a Save that would
                    // zero placed points shows "answer the question on the page" over it), and a question a closed message uncovers
                    newTop is WdpMessage && newTop !== before.top && (newTop.buttons.size > 1 || after.stack.count { it !in before.stack } > 1) -> {
                        val key = newTop.title + "|" + newTop.buttons.joinToString("|") + "|" + after.stack.size
                        if (allOpeners || exploredQuestions.add(key)) explore(next, depth + 1, null)
                    }
                    newTop === before.top && !(a.op is Op.Set && a.p.c.kind == "tabs") -> {
                        val nowWin = topWindow(s)
                        if (nowWin != null && nowWin.message == null) {
                            val revealed = (interactiveNames(nowWin) - interactiveNames(win)).filter { n ->
                                n !in exploredReveals && (!nowWin.isPage || placed(nowWin.form, WdpValues(nowWin.values)).any { it.c.name == n && view.inScope(it.anc, it.c) })
                            }.toSet()
                            if (revealed.isNotEmpty()) {
                                exploredReveals += revealed
                                r.note = listOf(r.note, "brought into view: " + revealed.sorted().joinToString(", ").take(200)).filter { it.isNotBlank() }.joinToString("; ")
                                explore(next, depth + 1, revealed)
                            }
                        }
                    }
                }
            }
            return false
        }

        /** Every value that differs, per window, as `key: old → new`. */
        private fun diff(a: Snap, b: Snap): List<String> {
            val out = ArrayList<String>()
            val inWindow = a.top != null
            fun cmp(x: Map<String, String>, y: Map<String, String>, prefix: String, f: WdpForm?) {
                for (k in (x.keys + y.keys)) if (x[k] != y[k] && !noop(f, k, x[k], y[k])) out += short("$prefix$k: ${short(x[k], 50)} → ${short(y[k], 50)}", 120)
            }
            cmp(a.page, b.page, if (inWindow) "page:" else "", form)
            // what reached the settings the app keeps (a DTC Save slot, a saved card): written, so it is an outcome
            cmp(a.settings, b.settings, "settings:", null)
            for ((d, v) in a.windows) {
                val w = b.windows.firstOrNull { it.first === d } ?: continue
                val df = (d as? WdpDialog)?.form?.let { runBlocking { Repo.wdpForm(it) } }
                if (v != null && w.second != null) cmp(v, w.second!!, (d as? WdpDialog)?.form?.let { "$it:" } ?: "", df)
            }
            return out
        }

        private val controlsOf = HashMap<String, Map<String, WdpControl>>()

        /**
         * A value that appeared or went away saying what the renderer shows anyway: a control the designer shows
         * reported "shown", its designer caption reported as its text, "enabled" as true. The DTC page reports a label
         * once it has been tapped, which drew nothing new.
         */
        private fun noop(f: WdpForm?, key: String, old: String?, new: String?): Boolean {
            if (f == null || (old != null && new != null)) return false
            val v = old ?: new ?: return true
            val byName = controlsOf.getOrPut(f.form) { f.all().associateBy { it.name } }
            val name = key.substringBefore('.')
            val c = byName[name] ?: return false
            return when (key) {
                name -> v == "shown" && !c.hidden
                "$name.text" -> v == (c.text ?: "")
                "$name.enabled" -> v == "true"
                else -> false
            }
        }

        // ---- typing, on the real field

        private fun typeInto(s: Session, win: Win, depth: Int, path: List<Op>, a: Act, ev: Map<String, Set<String>>, basePx: ByteArray?) {
            val op = a.op as Op.Type
            // what a click on the box opened, typed: an untyped var set inside the try below compiled to bytecode the JVM
            // refused (a VerifyError: a cast to the window's class left out)
            var clickDialog: WdpDialog? = null
            var clickMessage: WdpMessage? = null
            val r = row(win, depth, path, a, "ok", ev)
            rows += r
            val before = snap(s)
            val original = shownText(a.p.c, win.values)
            asyncErrors.clear()
            try {
                stage.show(s, page, form)
                stage.stable(12, 20).close()
                val want = sceneRect(win, a.p)
                pressedAt = want
                val node = stage.editableAt(want)
                if (node == null) {
                    // nothing to click where the box is drawn: say so, and type through the page as the keys would
                    r.flag = "NO-FIELD"
                    r.note = "no field to type into where ${a.p.c.name} is drawn (a read-only face, or covered); typed through the page instead"
                    edt { perform(s, op) }
                    settle(s)
                    val v = topWindow(s)?.values.orEmpty()
                    r.note += "; the page then shows \"${shownText(a.p.c, v)}\""
                } else {
                    val id = node.id
                    stage.click(node.boundsInRoot.center)
                    settle(s, minStillMs = 15)
                    // a box WDP opens a window from when it is clicked (the card's formation boxes): that is its outcome
                    val clickTop = edt { WdpDialogs.stack.lastOrNull() }
                    if (clickTop != null && clickTop !in before.stack) {
                        clickDialog = clickTop as? WdpDialog
                        clickMessage = clickTop as? WdpMessage
                        r.note = "a click on the box opens a window (nothing typed)"
                        throw OpenedByClick()
                    }
                    if (!stage.focused(id)) {
                        r.note = "a click on the field did not take focus; focused it directly"
                        stage.requestFocus(id)
                    }
                    stage.ctrlA()
                    for (ch in op.text) { stage.type(ch); if (!op.fast) stage.frame().close() }
                    stage.frame().close()
                    settle(s, minStillMs = 15)
                    val during = stage.textOf(id)
                    val held = shownText(a.p.c, topWindow(s)?.values.orEmpty())
                    // the field as the pilot sees it while still in it
                    val typingImg = stage.stable(6, 10)
                    val typingPx = pixels(typingImg)
                    r.image2 = "%03d-%s-typing.png".format(r.n, safe(a.p.c.name))
                    writer.save(typingImg, File(dir, r.image2), crop(win), pressedAt, null)
                    // leave it: Tab, as a pilot moves on (a multi-line box takes Tab as text, so another field is focused)
                    if (a.p.c.multiline) stage.focusOther(id) else stage.tab()
                    if (stage.focused(id)) stage.focusOther(id)
                    stage.frame().close()
                    settle(s)
                    val left = shownText(a.p.c, topWindow(s)?.values.orEmpty())
                    val shownAfter = stage.textOf(id)
                    if (op.commit != null) {
                        runCatching { edt { perform(s, Op.Click(op.commit, op.commit, "press")) } }.exceptionOrNull()?.let { throw it }
                        settle(s)
                        stage.frame().close()
                    }
                    val typed = op.text.trim()
                    val seen = during?.trim()
                    r.note = listOfNotNull(r.note.ifBlank { null },
                        "field while typing: \"${during ?: "?"}\"",
                        if (!op.number && held.trim() != (seen ?: "")) "page holds \"$held\"" else null,
                        "after leaving: \"$left\"" + if (shownAfter != null && shownAfter != left) " (field \"$shownAfter\")" else "",
                        "was \"$original\"").joinToString("; ")
                    r.flag = when {
                        seen == null -> "TYPE-LOST"
                        seen == typed -> "ok"
                        seen.endsWith(typed) || seen.contains(typed) -> "ok".also { r.note += "; appended (Ctrl+A did not select)" }
                        else -> "TYPE-LOST"
                    }
                    if (r.flag == "ok" && op.commit == null && left.trim() == original.trim() && typed != original.trim() &&
                        (op.number || shownAfter == null || shownAfter.trim() != typed)) {
                        val after = snap(s)
                        val t = after.top
                        if (!(t as? WdpMessage != null && t !in before.stack)) r.flag = "REVERTED"
                    }
                    // the field still showing what was typed while the page holds something else: the text is not kept
                    // (drawn again — another tab and back — the box shows the page's value)
                    if (r.flag == "ok" && op.commit == null && !op.number && shownAfter != null && shownAfter.trim() == typed &&
                        left.trim() != typed && edt { WdpDialogs.stack.lastOrNull() } === before.top) {
                        r.flag = "TYPE-LOST"
                        r.note += "; the field shows what was typed but the page does not hold it"
                    }
                    // pixels: the field while typing against the window as it opened
                    if (basePx != null) r.pixels = changedPct(basePx, typingPx, crop(win))
                }
            } catch (e: OpenedByClick) {
                // recorded below as what the click opened
            } catch (e: Throwable) {
                r.flag = "THREW"; r.error = trace(e)
            }
            val after = snap(s)
            val changes = diff(before, after)
            r.changes = changes.size
            r.changed = changes.take(40).joinToString(" | ") + if (changes.size > 40) " | … ${changes.size - 40} more" else ""
            val newTop = after.top
            // explicit casts in this function: smart casts here compiled to bytecode the JVM refused (VerifyError)
            (newTop as? WdpMessage)?.takeIf { it !in before.stack }?.let { m -> r.message = "[" + m.title + "] " + m.text }
            (newTop as? WdpDialog)?.takeIf { it !in before.stack }?.let { d -> r.opened = d.form + " \"" + d.title + "\"" }
            val saves = after.saves - before.saves
            if (saves > 0) r.cartridge = s.src.log.drop(before.saves).joinToString(" / ")
            asyncErrors.firstOrNull()?.let { if (r.flag != "THREW") { r.flag = "THREW"; r.error = trace(it) } }
            val byDialog = clickDialog
            val byMessage = clickMessage
            if ((byDialog != null || byMessage != null) && r.flag != "THREW") {
                r.flag = if (byDialog != null) "OPEN" else if (r.changes == 0) "MSG" else "ok"
                if (byMessage != null) r.message = "[" + byMessage.title + "] " + byMessage.text
            }
            // the picture once the field is left
            val img = runCatching { stage.stable(8, 8) }.getOrNull()
            if (img != null) {
                r.image = "%03d-%s.png".format(r.n, safe(a.p.c.name))
                r.thumb = writer.save(img, File(dir, r.image), crop(win), pressedAt, THUMB_W)
            }
            stage.newComposition()
            val openedWindow = clickDialog
            if (openedWindow != null && depth < MAX_DEPTH && r.flag != "THREW" && (allOpeners || exploredWindows.add(openedWindow.form))) {
                explore(path + Op.Click(a.p.c.name, a.p.c.name, "click"), depth + 1, null)
            }
        }

        private class OpenedByClick : RuntimeException()

        private fun sceneRect(win: Win, p: Placed): SkRect {
            val r = runCatching { edt { WdpProbe.rects["${win.form.form}/${p.c.name}"] } }.getOrNull()
            if (r != null && r.width > 0f && r.height > 0f) return SkRect.makeLTRB(r.left, r.top, r.right, r.bottom)
            return SkRect.makeXYWH(win.ox + p.x * win.k, win.oy + p.y * win.k, p.c.w * win.k, p.c.h * win.k)
        }

        /** The part of the scene the picture keeps: the page alone when no window is over it. */
        private fun crop(win: Win?): Pair<Int, Int> =
            if (win == null || (win.isPage && edt { WdpDialogs.stack.isEmpty() })) form.width.coerceAtMost(SCENE_W) to form.height.coerceAtMost(SCENE_H)
            else SCENE_W to SCENE_H

        private fun outline(win: Win, p: Placed): SkRect? = if (win.message != null) null else sceneRect(win, p)

        /** Where the acted control was when it was pressed (a window that closes takes its record with it). */
        private var pressedAt: SkRect? = null

        private fun picture(s: Session, r: Row, win: Win, a: Act, basePx: ByteArray?) {
            val t = System.nanoTime()
            try {
                stage.show(s, page, form)
                val img = stage.stable(12, 8)
                val cr = if (edt { WdpDialogs.stack.isEmpty() }) form.width.coerceAtMost(SCENE_W) to form.height.coerceAtMost(SCENE_H) else SCENE_W to SCENE_H
                if (basePx != null) r.pixels = changedPct(basePx, pixels(img), cr)
                r.image = "%03d-%s.png".format(r.n, safe(a.p.c.name))
                // the control acted on, outlined where it was (the window it was on may have closed since)
                r.thumb = writer.save(img, File(dir, r.image), cr, pressedAt, THUMB_W)
            } catch (e: Throwable) {
                r.flag = "THREW"
                r.error = listOf(r.error, "drawing the page after it: " + trace(e)).filter { it.isNotBlank() }.joinToString("\n")
                stage.reset()
            } finally { timePicture += System.nanoTime() - t }
        }

        // ---- output

        fun countLine(): String {
            val controls = rows.map { it.window + "/" + it.control }.toSet().size
            fun c(f: String) = rows.count { it.flag == f }
            val missed = rows.count { it.note.contains("did not take focus") }
            return "%-13s controls %4d, actions %4d: DEAD %d, MSG %d, THREW %d, TYPE-LOST %d, REVERTED %d, NO-FIELD %d, NO-UI %d, OPEN %d, ok %d, same %d, disabled %d; a click missed its field %d".format(
                pageId, controls, rows.size, c("DEAD"), c("MSG"), c("THREW"), c("TYPE-LOST"), c("REVERTED"), c("NO-FIELD"), c("NO-UI"), c("OPEN"), c("ok"), c("same"), c("disabled"), missed) +
                " (fresh Planners %d: %.0f s, waiting %.0f s, pictures %.0f s)".format(freshCount, timeFresh / 1e9, timeSettle / 1e9, timePicture / 1e9)
        }

        fun writeTsv() = writeAllTsv(File(dir, "outcome.tsv"), rows)

        fun writeSheets() {
            val per = 60
            rows.chunked(per).forEachIndexed { i, chunk ->
                sheet(chunk, File(dir.parentFile, if (i == 0) "$pageId-sheet.png" else "$pageId-sheet-${i + 1}.png"), "$pageId — sheet ${i + 1} of ${(rows.size + per - 1) / per}")
            }
            val flagged = rows.filter { it.flag in setOf("THREW", "TYPE-LOST", "REVERTED", "MSG", "DEAD", "NO-FIELD", "NO-UI") }
            flagged.chunked(per).forEachIndexed { i, chunk ->
                sheet(chunk, File(dir.parentFile, if (i == 0) "$pageId-sheet-flagged.png" else "$pageId-sheet-flagged-${i + 1}.png"), "$pageId — flagged rows, sheet ${i + 1}")
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ WDP's handlers

    /** Which events WDP's own code hangs on each control of a form, read from its decompiled designer properties. */
    private class WdpEvents(private val dir: File?) {
        private val cache = HashMap<String, Map<String, Set<String>>>()

        /** whether WDP's code was given, so a control with no handler in it is known to have none */
        val known: Boolean get() = dir != null

        fun of(form: String): Map<String, Set<String>> = cache.getOrPut(form) { read(form) }

        private fun read(form: String): Map<String, Set<String>> {
            val d = dir ?: return emptyMap()
            val f = listOf(File(d, "WeaponDeliveryPlanner/$form.cs"), File(d, "$form.cs")).firstOrNull { it.isFile } ?: return emptyMap()
            val text = f.readText()
            val out = HashMap<String, MutableSet<String>>()
            // internal virtual Label lblE { get …; set { EventHandler value2 = lblE_Click; … label.Click += value2; } }
            val prop = Regex("""internal virtual [\w.]+ (\w+)\s*\{(.*?)\n\t\}""", RegexOption.DOT_MATCHES_ALL)
            val handler = Regex("""\w*Handler\w* (\w+) = (\w+);""")
            val add = Regex("""\.(\w+) \+= (\w+);""")
            for (m in prop.findAll(text)) {
                val name = m.groupValues[1]
                val body = m.groupValues[2]
                val vars = handler.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
                for (e in add.findAll(body)) {
                    val event = e.groupValues[1]
                    val method = vars[e.groupValues[2]] ?: continue
                    val right = event.startsWith("Mouse") && methodBody(text, method).contains("MouseButtons.Right")
                    out.getOrPut(name) { HashSet() } += event + if (right) "(R)" else ""
                }
            }
            return out
        }

        private fun methodBody(text: String, method: String): String {
            val i = Regex("""void $method\(""").find(text)?.range?.first ?: return ""
            val end = text.indexOf("\n\t}", i).takeIf { it > 0 } ?: (i + 2000).coerceAtMost(text.length)
            return text.substring(i, end)
        }
    }

    // ------------------------------------------------------------------------------------------------ the scene

    private class Shown(val session: Session, val page: WdpPage, val form: WdpForm)

    /**
     * One Compose scene for the whole run, the size of a PC window, with the page drawn at its designer size in the
     * top left (scale 1, so a control's designer rectangle is where it is on the scene) and the Planner's dialog host
     * over it. Everything that touches it runs on the UI thread, as the app's window does.
     */
    private class Stage {
        private var shown by mutableStateOf<Shown?>(null)
        private var generation by mutableStateOf(0)
        /** A form of the check's own with a wiring of its own, for [selfCheck] (declared before the scene, which reads it). */
        private var stub by mutableStateOf<Pair<WdpForm, WdpWiring>?>(null)
        private var nanos = 0L
        private val keySource = javax.swing.JPanel()
        /** the scene's semantics, as a window's accessibility would see it: where the fields are and what they hold */
        private val owners = CopyOnWriteArrayList<SemanticsOwner>()
        private val surface = Surface.makeRasterN32Premul(SCENE_W, SCENE_H)
        private var scene: ComposeScene = make()

        private fun make(): ComposeScene = edt {
            val info = object : WindowInfo {
                override val isWindowFocused: Boolean get() = true
                override val containerSize: IntSize get() = IntSize(SCENE_W, SCENE_H)
            }
            val platform = object : PlatformContext by PlatformContext.Empty {
                override val windowInfo: WindowInfo get() = info
                override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners += semanticsOwner }
                    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners -= semanticsOwner }
                    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {}
                    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {}
                }
            }
            val context = object : ComposeSceneContext { override val platformContext: PlatformContext get() = platform }
            CanvasLayersComposeScene(density = Density(1f), size = IntSize(SCENE_W, SCENE_H), coroutineContext = Dispatchers.Unconfined, composeSceneContext = context)
                .also { it.setContent { Content() } }
        }

        @Composable
        private fun Content() {
            val st = stub
            if (st != null) {
                val f = st.first
                val w = st.second
                key(generation) {
                    Box(Modifier.fillMaxSize().background(BG)) {
                        Box(Modifier.size(f.width.dp, f.height.dp)) {
                            WdpFormView(f, w.values(emptyList()), Modifier.fillMaxSize(), onValue = w::onValue, onClick = w::onClick)
                        }
                    }
                }
                return
            }
            val s = shown ?: return
            key(generation, s.page, s.form) {
                Box(Modifier.fillMaxSize().background(BG)) {
                    Box(Modifier.size(s.form.width.dp, s.form.height.dp)) {
                        val w = s.session.wiring(s.page)
                        val values = w.values(s.page.hidden)
                        WdpFormView(
                            s.form, values, Modifier.fillMaxSize(),
                            onValue = { n, v -> s.session.onValue(s.page, n, v) },
                            onClick = { n -> s.session.onClick(s.page, n) },
                            content = (w as? WdpControlContent)?.controlContent() ?: emptyMap(),
                        )
                    }
                    WdpDialogHost()
                }
            }
        }

        /** The next picture from a composition of its own: no focus, no typed draft, no open list left from before. */
        fun newComposition() { edt { generation++ } }

        fun show(session: Session, page: WdpPage, form: WdpForm) {
            val cur = shown
            if (cur != null && cur.session === session && cur.page == page && stub == null) return
            edt { stub = null; shown = Shown(session, page, form) }
        }

        fun showStub(form: WdpForm, wiring: WdpWiring) { edt { stub = form to wiring; generation++ } }

        fun clearStub() { edt { stub = null; generation++ } }

        fun frame(): SkImage = edt {
            Snapshot.sendApplyNotifications()
            nanos += 16_666_667L
            surface.canvas.clear(0)
            scene.render(surface.canvas.asComposeCanvas(), nanos)
            surface.makeImageSnapshot()
        }

        /** Frames until two in a row are the same (pictures load on their own), at most [maxFrames]. */
        fun stable(maxFrames: Int, sleepMs: Long): SkImage {
            var prev: ByteArray? = null
            var img: SkImage? = null
            repeat(maxFrames) { i ->
                val f = frame()
                val px = pixels(f)
                if (prev != null && px.contentEquals(prev)) { img?.close(); return f }
                img?.close()
                img = f
                prev = px
                if (i < maxFrames - 1) Thread.sleep(sleepMs)
            }
            return img!!
        }

        fun reset() {
            runCatching { edt { scene.close() } }
            owners.clear()
            scene = make()
        }

        fun close() { runCatching { edt { scene.close() } }; surface.close() }

        // ---- input

        private fun nodes(): List<SemanticsNode> = edt {
            owners.flatMap { runCatching { it.getAllSemanticsNodes(mergingEnabled = false) }.getOrDefault(emptyList()) }
        }

        private fun editables(): List<SemanticsNode> = nodes().filter { SemanticsProperties.EditableText in it.config }

        /** The field drawn where [want] is: the one overlapping it most, or the nearest within a few pixels. */
        fun editableAt(want: SkRect): SemanticsNode? {
            val all = editables()
            fun overlap(n: SemanticsNode): Float {
                val b = n.boundsInRoot
                val w = (minOf(b.right, want.right) - maxOf(b.left, want.left)).coerceAtLeast(0f)
                val h = (minOf(b.bottom, want.bottom) - maxOf(b.top, want.top)).coerceAtLeast(0f)
                return w * h / (want.width * want.height).coerceAtLeast(1f)
            }
            val best = all.maxByOrNull { overlap(it) }
            if (best != null && overlap(best) > 0.25f) return best
            val cx = (want.left + want.right) / 2
            val cy = (want.top + want.bottom) / 2
            return all.minByOrNull { n -> val c = n.boundsInRoot.center; Math.hypot((c.x - cx).toDouble(), (c.y - cy).toDouble()) }
                ?.takeIf { n -> val c = n.boundsInRoot.center; Math.hypot((c.x - cx).toDouble(), (c.y - cy).toDouble()) < 12 }
        }

        private fun node(id: Int): SemanticsNode? = nodes().firstOrNull { it.id == id }

        fun textOf(id: Int): String? = node(id)?.config?.getOrNull(SemanticsProperties.EditableText)?.text

        fun focused(id: Int): Boolean = node(id)?.config?.getOrNull(SemanticsProperties.Focused) == true

        fun requestFocus(id: Int) {
            edt { node(id)?.config?.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() }
            frame().close()
        }

        /** Focus moved to some other field, which is how a multi-line box (or a stubborn one) is left. */
        fun focusOther(id: Int) {
            val other = editables().firstOrNull { it.id != id } ?: return
            edt { other.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() }
            frame().close()
        }

        fun click(at: Offset) {
            edt {
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            }
            frame().close()
            edt { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary) }
            frame().close()
            frame().close()
        }

        private fun key(id: Int, code: Int, ch: Char, mods: Int = 0) = edt {
            val loc = if (id == java.awt.event.KeyEvent.KEY_TYPED) java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN else java.awt.event.KeyEvent.KEY_LOCATION_STANDARD
            // turned into Compose's event by the desktop's own conversion (the one a window uses; internal, so reached by name)
            val awt = java.awt.event.KeyEvent(keySource, id, System.currentTimeMillis(), mods, code, ch, loc)
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(toCompose.invoke(null, awt)))
        }

        private val toCompose: java.lang.reflect.Method =
            Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").getMethod("toComposeEvent", java.awt.event.KeyEvent::class.java)

        fun type(ch: Char) = key(java.awt.event.KeyEvent.KEY_TYPED, java.awt.event.KeyEvent.VK_UNDEFINED, ch)

        fun ctrlA() {
            val ctrl = java.awt.event.InputEvent.CTRL_DOWN_MASK
            key(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.VK_CONTROL, java.awt.event.KeyEvent.CHAR_UNDEFINED, ctrl)
            key(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.VK_A, 'a', ctrl)
            key(java.awt.event.KeyEvent.KEY_RELEASED, java.awt.event.KeyEvent.VK_A, 'a', ctrl)
            key(java.awt.event.KeyEvent.KEY_RELEASED, java.awt.event.KeyEvent.VK_CONTROL, java.awt.event.KeyEvent.CHAR_UNDEFINED, 0)
            frame().close()
        }

        fun tab() {
            key(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.VK_TAB, '\t')
            key(java.awt.event.KeyEvent.KEY_RELEASED, java.awt.event.KeyEvent.VK_TAB, '\t')
            frame().close()
        }
    }

    // ------------------------------------------------------------------------------------------------ pictures

    private fun pixels(img: SkImage): ByteArray {
        val bmp = SkBitmap.makeFromImage(img)
        return try { bmp.readPixels() ?: ByteArray(0) } finally { bmp.close() }
    }

    /** Pixels that differ (any channel by more than a little) within the top-left [crop], in per cent of it. */
    private fun changedPct(a: ByteArray, b: ByteArray, crop: Pair<Int, Int>): Double {
        if (a.size != b.size || a.isEmpty()) return 100.0
        val (cw, ch) = crop
        var n = 0
        for (y in 0 until ch) {
            var i = y * SCENE_W * 4
            for (x in 0 until cw) {
                if (Math.abs(a[i] - b[i]) > 6 || Math.abs(a[i + 1] - b[i + 1]) > 6 || Math.abs(a[i + 2] - b[i + 2]) > 6) n++
                i += 4
            }
        }
        return n * 100.0 / (cw * ch).coerceAtLeast(1)
    }

    /** Pictures are written off the UI thread, a few at a time; each save hands back the small copy for the sheets. */
    private class Writer {
        private val pool = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, ArrayBlockingQueue(6), ThreadPoolExecutor.CallerRunsPolicy())
        private val pending = CopyOnWriteArrayList<Future<*>>()

        fun save(img: SkImage, file: File, crop: Pair<Int, Int>, outline: SkRect?, thumbW: Int?): Future<ByteArray?> {
            val f = pool.submit<ByteArray?> {
                try {
                    val (cw, ch) = crop
                    val cropped = Surface.makeRasterN32Premul(cw, ch).use { s ->
                        s.canvas.drawImage(img, 0f, 0f)
                        outline?.let { r ->
                            s.canvas.drawRect(r.inflate(2f), Paint().apply { color = 0xFFFF00FF.toInt(); mode = PaintMode.STROKE; strokeWidth = 2f })
                        }
                        s.makeImageSnapshot()
                    }
                    file.writeBytes(cropped.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    val thumb = thumbW?.let { tw ->
                        val th = (ch * tw / cw.coerceAtLeast(1)).coerceAtLeast(1)
                        Surface.makeRasterN32Premul(tw, th).use { s ->
                            s.canvas.drawImageRect(cropped, SkRect.makeWH(cw.toFloat(), ch.toFloat()), SkRect.makeWH(tw.toFloat(), th.toFloat()), SamplingMode.LINEAR, null, true)
                            s.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.bytes }
                        }
                    }
                    cropped.close()
                    thumb
                } finally { img.close() }
            }
            pending += f
            return f
        }

        fun drain() { for (f in pending) runCatching { f.get() }; pending.clear() }

        fun shutdown() { drain(); pool.shutdown() }
    }

    private fun SkRect.inflate(d: Float) = SkRect.makeLTRB(left - d, top - d, right + d, bottom + d)

    private val sheetFont: Font by lazy {
        val tf = runCatching { FontMgr.default.matchFamilyStyle("Segoe UI", FontStyle.NORMAL) }.getOrNull()
            ?: runCatching { FontMgr.default.matchFamilyStyle("Arial", FontStyle.NORMAL) }.getOrNull()
            ?: runCatching { FontMgr.default.legacyMakeTypeface("", FontStyle.NORMAL) }.getOrNull()
        Font(tf, 12f)
    }

    /** A contact sheet: six small after-pictures a line, each labelled with its row, control, action and flag. */
    private fun sheet(rows: List<Row>, file: File, title: String) {
        if (rows.isEmpty()) return
        val cols = 6
        val cellW = THUMB_W + 12
        val thumbH = 150
        val cellH = thumbH + 46
        val lines = (rows.size + cols - 1) / cols
        val w = cols * cellW + 12
        val h = 34 + lines * cellH + 8
        Surface.makeRasterN32Premul(w, h).use { s ->
            val c = s.canvas
            c.clear(0xFF101214.toInt())
            val ink = Paint().apply { color = 0xFFE8E8E8.toInt() }
            val dim = Paint().apply { color = 0xFF9AA0A6.toInt() }
            c.drawString(title, 12f, 22f, sheetFont, ink)
            rows.forEachIndexed { i, r ->
                val x = 6f + (i % cols) * cellW + 6
                val y = 34f + (i / cols) * cellH
                val flagInk = Paint().apply {
                    color = when (r.flag) {
                        "THREW", "TYPE-LOST" -> 0xFFFF5252.toInt()
                        "REVERTED", "MSG", "NO-FIELD", "NO-UI" -> 0xFFFFB74D.toInt()
                        "DEAD" -> 0xFFFFD54F.toInt()
                        "OPEN" -> 0xFF64B5F6.toInt()
                        "ok" -> 0xFF81C784.toInt()
                        else -> 0xFF9AA0A6.toInt()
                    }
                }
                val bytes = runCatching { r.thumb?.get() }.getOrNull()
                if (bytes != null) {
                    SkImage.makeFromEncoded(bytes).use { img ->
                        val th = minOf(img.height.toFloat(), thumbH.toFloat())
                        val tw = img.width * th / img.height
                        c.drawImageRect(img, SkRect.makeXYWH(x, y, tw, th))
                    }
                } else {
                    c.drawRect(SkRect.makeXYWH(x, y, THUMB_W.toFloat(), thumbH.toFloat()), Paint().apply { color = 0xFF22262B.toInt() })
                    c.drawString(if (r.flag == "disabled") "disabled" else "no change", x + 8, y + 20, sheetFont, dim)
                }
                c.drawRect(SkRect.makeXYWH(x, y, THUMB_W.toFloat(), thumbH.toFloat()), Paint().apply { color = flagInk.color; mode = PaintMode.STROKE; strokeWidth = 2f })
                c.drawString("${r.n.toString().padStart(3, '0')} ${r.control}".take(36), x, y + thumbH + 15, sheetFont, ink)
                c.drawString(r.action.take(26), x, y + thumbH + 30, sheetFont, dim)
                c.drawString(r.flag, x + THUMB_W - 70, y + thumbH + 30, sheetFont, flagInk)
                if (r.window != "page") c.drawString(r.window.take(34), x, y + thumbH + 43, sheetFont, dim)
            }
            file.writeBytes(s.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.bytes })
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private val asyncErrors = CopyOnWriteArrayList<Throwable>()

    /** Writes to Compose state outside a composition: how [PageCtx.settle] knows the pages have stopped changing. */
    private val writes = java.util.concurrent.atomic.AtomicLong()

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var r: Result<T>? = null
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r!!.getOrThrow()
    }

    private fun short(s: String?, max: Int): String {
        if (s == null) return "∅"
        val t = s.replace("\r", "").replace('\n', '⏎').replace('\t', ' ')
        return if (t.length <= max) t else t.take(max - 1) + "…"
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(60)

    private fun trace(e: Throwable): String = buildString {
        append("${e::class.simpleName}: ${e.message}")
        e.stackTrace.filter { it.className.startsWith("com.bmscompanion") }.take(6).forEach { append("\n    at $it") }
        e.cause?.let { append("\n  caused by ${it::class.simpleName}: ${it.message}") }
    }

    private fun tsv(s: String) = s.replace("\r", "").replace('\t', ' ').replace("\n", " ⏎ ")

    private fun writeAllTsv(file: File, rows: List<Row>) {
        file.bufferedWriter().use { w ->
            w.write(listOf("n", "page", "view", "window", "depth", "path", "control", "kind", "caption", "wdp", "action", "flag", "opened",
                "message", "changes", "changed", "cartridge", "pixels%", "image", "image2", "error", "note").joinToString("\t"))
            w.newLine()
            for (r in rows) {
                w.write(listOf(r.n.toString(), r.page, r.view, r.window, r.depth.toString(), r.path, r.control, r.kind, r.caption, r.wdp, r.action,
                    r.flag, r.opened, r.message, r.changes.toString(), r.changed, r.cartridge, "%.3f".format(r.pixels), r.image, r.image2, r.error, r.note)
                    .joinToString("\t") { tsv(it) })
                w.newLine()
            }
        }
    }
}
