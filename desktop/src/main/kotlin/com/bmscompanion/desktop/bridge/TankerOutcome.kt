@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.ui.screens.wdp.PlannerMissionState
import com.bmscompanion.app.ui.screens.wdp.PlannerWindows
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpFocus
import com.bmscompanion.app.ui.screens.wdp.WdpHandOff
import com.bmscompanion.app.ui.screens.wdp.WdpPage
import com.bmscompanion.app.ui.screens.wdp.WdpPlanner
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.screens.wdp.WdpSession
import com.bmscompanion.app.ui.screens.wdp.WdpTouch
import com.bmscompanion.app.ui.screens.wdp.plannerMission
import com.bmscompanion.app.ui.screens.wdp.plannerTheater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

/**
 * `--planneroutcome tanker <outDir> <a copy of the BMS folder> [<save file>] [<flight callsign>]`: the DataCard's two
 * tanker rows chosen the way a pilot chooses them, on the composed Planner, with real presses — WDP's
 * `btnSetTanker1_Click` (the small box beside "Tanker 1") shows `cboTanker1`, a list of "No Tanker" and the tankers on
 * station during the flight (`FillTanker`); a press on the list drops it open; a pick, then **Select**
 * (`btnTanker1_Click` → `TankerText`) fills the row: callsign - type, TACAN, UHF, LOC, notes.
 *
 * Run three ways: the mouse at a PC window's size, a finger at a tablet's (the list is the finger's editor, a
 * `WdpSheet.Pick`), and a finger at a phone's. The save defaults to `Auto Save.cam` in the copy's current theater's
 * campaign folder and the flight to the briefed one (the printed briefing's), else the first flight whose card lists a
 * tanker. Read only: no Save to DTC is pressed, and the cartridge is the copy's, held in memory.
 */
internal object TankerOutcome {
    private class Report {
        val text = StringBuilder()
        var pass = 0
        var fail = 0
        fun check(ok: Boolean, s: String, why: () -> String = { "" }) {
            if (ok) { pass++; text.appendLine("PASS  $s") } else { fail++; text.appendLine("FAIL  $s" + why().let { if (it.isEmpty()) "" else " — $it" }) }
        }
        fun info(s: String) = text.appendLine("      $s")
        fun head(s: String) { text.appendLine(); text.appendLine("== $s") }
    }

    private class Size(val name: String, val w: Int, val h: Int, val density: Float, val finger: Boolean)

    fun run(outDir: File, more: List<String>): String {
        val dir = File(outDir, "tanker").also { it.mkdirs() }
        val r = Report()
        r.text.appendLine("The DataCard's tanker rows: the box beside Tanker 1/2, its list, Select, the row")
        val root = more.getOrNull(0)?.let(::File)?.takeIf { it.isDirectory }
        if (root == null) {
            r.check(false, "a copy of a BMS folder: `--planneroutcome tanker <outDir> <a copy of the BMS folder> [save] [callsign]`")
            return r.text.toString()
        }
        val pageBefore = WdpSession.page
        WdpProbe.on = true
        try {
            go(r, dir, root, more.getOrNull(1), more.getOrNull(2))
        } catch (e: Throwable) {
            r.check(false, "the run threw ${e::class.simpleName}: ${e.message}")
            e.stackTrace.filter { it.className.startsWith("com.bmscompanion") }.take(12).forEach { r.info("  at $it") }
        } finally {
            edt { PlannerWindows.close(); WdpDialogs.stack.clear(); WdpFocus.on = false; WdpTouch.close(); WdpTouch.assume(false) }
            edt {
                PlannerMissionState.source = PlannerMissionState.BRIEFING
                PlannerMissionState.flight = null; PlannerMissionState.ref = null; PlannerMissionState.theater = null; PlannerMissionState.seat = 0
            }
            WdpProbe.on = false
            edt { WdpProbe.clear() }
            WdpSession.page = pageBefore
        }
        r.head("summary")
        r.text.appendLine("PASS ${r.pass}, FAIL ${r.fail}")
        r.text.appendLine(if (r.fail == 0) "ALL PASS" else "FAIL: ${r.fail} of ${r.pass + r.fail} checks")
        return r.text.toString()
    }

    private fun go(r: Report, dir: File, root: File, saveName: String?, callsign: String?) {
        // ---- the flight, as Open mission… hands it to the Planner
        val set = Theaters.at(root)
        val t = set.current(null) ?: set.all.firstOrNull() ?: run { r.check(false, "the copy's theater list"); return }
        val campaign = set.campaignDir(t) ?: run { r.check(false, "the theater's campaign folder (${t.name})"); return }
        val file = File(campaign, saveName ?: "Auto Save.cam")
        if (!file.isFile) { r.check(false, "the save ${file.name} in ${t.name}'s campaign folder"); return }
        val bf = Theaters.resolveFile(root, "User\\Briefings\\briefing.txt")
        val printed = bf?.let { f -> runCatching { BriefingParser.parse(f.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")) }.getOrNull() }
        val ctx = CampaignFiles.Context(set, t.name, printed, bf?.lastModified() ?: 0L)
        val ato = (CampaignFiles.ato(ctx, t.name, file.name) as? CampaignFiles.Answer.Ok)?.value
        val rows = ato?.packages?.flatMap { it.flights }.orEmpty()
        val row = (if (callsign != null) rows.firstOrNull { it.callsign.equals(callsign, true) } else rows.firstOrNull { it.briefed })
            ?: rows.firstOrNull()
        val flight = row?.let { (CampaignFiles.flight(ctx, t.name, file.name, it.id) as? CampaignFiles.Answer.Ok)?.value }
        r.check(flight != null, "the flight read from the save", { "${file.name}: ${rows.size} flights, ${callsign ?: "the briefed one"} not read" })
        if (flight == null) return
        val tankersOfSide = flight.sideSupport.filter { it.role == "Tanker" }.map { it.callsign }
        r.info("${flight.row.callsign} in ${file.name} (${t.name}); the side's tankers in the save: ${tankersOfSide.joinToString().ifEmpty { "none" }}")
        val theater = runBlocking { plannerTheater(Repo.index().theaters, t.name) }
        r.check(theater != null, "the theater is known to the app", { t.name })
        val cartridge = File(root, "User/Config").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".ini", true) && runCatching { it.readText(Charsets.ISO_8859_1).contains("[STPT]") }.getOrDefault(false) }
            .maxByOrNull { it.lastModified() }
        val data = MissionData(briefing = printed, dtc = cartridge?.let { runCatching { DtcParser.parse(it) }.getOrNull() })
        WdpDtcFixture.file = cartridge
        WdpSession.dtcSource = WdpDtcFixture.source()

        for (size in listOf(
            Size("pc", 1600, 1000, 1f, finger = false),
            Size("tablet", 1280, 800, 1.5f, finger = true),
            Size("phone", 2379, 1071, 2.625f, finger = true),
        )) {
            r.head("${size.name}: ${size.w} x ${size.h} px at ${size.density}, ${if (size.finger) "by finger" else "by mouse"}")
            // a fresh card for every run: the pages are the Planner's (WdpSession), primed as the Planner primes them
            edt {
                PlannerMissionState.source = PlannerMissionState.SAVE
                PlannerMissionState.flight = flight
                PlannerMissionState.theater = t.name
                PlannerMissionState.ref = CampRef(t.name, file.name, flight.row.id)
                PlannerMissionState.seat = 0
                WdpSession.page = WdpPage.DATACARD.name
                WdpTouch.close(); WdpTouch.assume(size.finger)
            }
            prime(data, theater, flight, cartridge)
            val card = WdpSession.dataCard
            fun v(n: String) = edt { card.values(WdpPage.DATACARD.hidden).values[n] ?: "" }
            val items = v("cboTanker1.items").split('\n').filter { it.isNotEmpty() }
            r.info("the list WDP fills (FillTanker): ${items.joinToString(" | ")}")
            r.check(items.firstOrNull() == "No Tanker" && items.size >= 2, "the list holds No Tanker and the tankers on station", { items.joinToString(" | ") })
            val stage = Stage(size) { WdpPlanner(data, t.name, Modifier.fillMaxSize()) }
            try {
                stage.settle(40, 30)
                stage.png(File(dir, "${size.name}-0-card.png"))
                for (k in 1..2) {
                    val want = items.drop(1).getOrNull(if (k == 1) items.size - 2 else 0) ?: continue   // tanker 1: the last on station, 2: the first
                    choose(r, stage, dir, size, k, want) { v(it) }
                }
                // and back to none on tanker 2
                choose(r, stage, dir, size, 2, "No Tanker") { v(it) }
            } finally { stage.close() }
        }
    }

    /** The box beside the row, the list, the item, Select — each a press where it is drawn — then the row. */
    private fun choose(r: Report, s: Stage, dir: File, size: Size, k: Int, want: String, v: (String) -> String) {
        val set = s.rect("cntDataCard/btnSetTanker$k")
        r.check(set != null && set.width >= 4 && set.height >= 4, "${size.name} T$k: the box beside \"Tanker $k\" is drawn", { "$set" })
        if (set == null) return
        s.tap(set.center, size.finger)
        val combo = s.rect("cntDataCard/cboTanker$k")
        r.check(v("cboTanker$k") == "shown" && combo != null, "${size.name} T$k: a press on the box shows the tanker list", { "cboTanker$k=${v("cboTanker$k")}, drawn: ${combo != null}" })
        if (combo == null) return
        r.check(s.rect("cntDataCard/btnTanker$k") != null, "${size.name} T$k: and its Select button")
        s.settle(6, 20)
        s.png(File(dir, "${size.name}-T$k-1-list.png"))
        // the list comes up dropped open (below the box, or above it where the window has no room), every tanker to see
        val first = s.textNode(want, notIn = combo)
        r.check(first != null, "${size.name} T$k: the list comes up open, offering \"$want\"",
            { "on screen: ${s.texts().filter { it.length < 30 }.take(40).joinToString(" | ")}" })
        // closed with a press away from it (which only closes it), the box stays, and its own press opens it again:
        // the list dropped below it (the mouse), or the finger's picker (a sheet over the page, rows a finger's height)
        s.escape()
        s.settle(6, 20)
        r.check(s.textNode(want, notIn = combo) == null && v("cboTanker$k") == "shown",
            "${size.name} T$k: a press away closes the list and leaves the box")
        s.tap(combo.center, size.finger)
        s.settle(6, 20)
        s.png(File(dir, "${size.name}-T$k-2-open.png"))
        val item = s.textNode(want, notIn = combo)
        val shown = s.texts()
        if (size.finger) r.check(WdpTouchSheetOpen(), "${size.name} T$k: a finger on the list opens the picker (a sheet a finger can use)")
        r.check(item != null, "${size.name} T$k: a press on the list opens it, offering \"$want\"",
            { "after the press cboTanker$k=${v("cboTanker$k.text")}; on screen: ${shown.filter { it.length < 30 }.take(40).joinToString(" | ")}" })
        if (item == null) { s.escape(); return }
        s.tap(item.center, size.finger)
        s.settle(6, 20)
        r.check(v("cboTanker$k.text") == want, "${size.name} T$k: picking \"$want\" makes it the list's choice", { "cboTanker$k=${v("cboTanker$k.text")}" })
        // a finger's picker may need its OK
        if (WdpTouchSheetOpen()) s.textNode("OK")?.let { s.tap(it.center, size.finger); s.settle(4) }
        val select = s.rect("cntDataCard/btnTanker$k")
        r.check(select != null, "${size.name} T$k: Select is still there after the pick")
        if (select == null) return
        s.tap(select.center, size.finger)
        s.settle(6, 20)
        val row = listOf("", "_TCN", "_UHF", "_Loc", "_Notes").associateWith { v("txtTanker$k$it") }
        r.info("row: " + row.entries.joinToString("  ") { "${it.key.ifEmpty { "name" }}=${it.value}" })
        if (want == "No Tanker") r.check(row.values.all { it.isEmpty() }, "${size.name} T$k: No Tanker + Select empties the row", { row.toString() })
        else r.check(row[""]!!.startsWith("$want - ") && row["_TCN"]!!.isNotEmpty() && row["_UHF"]!!.isNotEmpty(),
            "${size.name} T$k: Select fills the row with $want (callsign - type, TACAN, UHF)", { row.toString() })
        r.check(v("cboTanker$k") == "hidden" && v("btnTanker$k") == "hidden", "${size.name} T$k: the list and Select close after Select")
        s.png(File(dir, "${size.name}-T$k-3-row.png"))
    }

    private fun WdpTouchSheetOpen(): Boolean = edt { WdpTouch.sheet != null }

    private fun prime(data: MissionData, theater: com.bmscompanion.app.data.Theater?, flight: com.bmscompanion.app.data.mission.CampFlight, cartridge: File?) {
        val s = WdpSession
        s.theaterName = theater?.name; s.theater = theater
        val mission = plannerMission(data, theater, flight)
        for (w in listOf(s.toss, s.popup, s.hadb)) w.onMission(mission)
        runBlocking { s.dataCard.prepare(mission) { cartridge?.readText(Charsets.ISO_8859_1) } }
        s.dtc.onMission(mission)
        runBlocking { s.performance.prepare(mission) }
        WdpHandOff.cardToPerformance(s.dataCard, s.performance, mission.briefing)
        s.appliedMission = mission
        Thread.sleep(1500)
    }

    /** A scene with its semantics (what an open list offers is found by its text, wherever the popup put it). */
    private class Stage(val size: Size, content: @androidx.compose.runtime.Composable () -> Unit) {
        private val owners = CopyOnWriteArrayList<SemanticsOwner>()
        private val surface = Surface.makeRasterN32Premul(size.w, size.h)
        private var nanos = 0L
        private var ms = 1_000L
        private val scene: ComposeScene = edt {
            val info = object : WindowInfo {
                override val isWindowFocused: Boolean get() = true
                override val containerSize: IntSize get() = IntSize(size.w, size.h)
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
            CanvasLayersComposeScene(density = Density(size.density), size = IntSize(size.w, size.h), coroutineContext = Dispatchers.Unconfined, composeSceneContext = context)
                .also { it.setContent(content) }
        }

        fun frame() = edt {
            Snapshot.sendApplyNotifications()
            nanos += 16_666_667L
            surface.canvas.clear(0)
            scene.render(surface.canvas.asComposeCanvas(), nanos)
        }

        fun settle(n: Int = 3, sleepMs: Long = 0) = repeat(n) { frame(); if (sleepMs > 0) Thread.sleep(sleepMs) }

        fun rect(key: String): Rect? = edt { WdpProbe.rects[key] }

        fun png(f: File) {
            frame()
            runCatching { f.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes) }
        }

        private fun nodes(): List<SemanticsNode> = edt {
            owners.flatMap { runCatching { it.getAllSemanticsNodes(mergingEnabled = false) }.getOrDefault(emptyList()) }
        }

        fun texts(): List<String> = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }

        /** The node showing exactly [text], below [below] and outside [notIn] (the closed list's own face shows the choice). */
        fun textNode(text: String, below: Float = -1f, notIn: Rect? = null): Rect? = nodes()
            .filter { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.trim() == text }
            .map { it.boundsInWindow }
            .filter { it.width > 0 && it.height > 0 && it.top >= below && (notIn == null || !notIn.contains(it.center)) }
            .maxByOrNull { it.width * it.height }   // the open list's row, never a word of the page under it

        fun tap(at: Offset, finger: Boolean) {
            ms += 50
            val type = if (finger) PointerType.Touch else PointerType.Mouse
            edt {
                if (!finger) scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms, type = type)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms, type = type, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            }
            settle(1)
            ms += 60
            edt { scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ms, type = type, buttons = PointerButtons(), button = PointerButton.Primary) }
            settle(4, 10)
            ms += 400   // never a double tap
        }

        /** A press well away from everything, which closes an open list without picking. */
        fun escape() = tap(Offset(4f, size.h - 4f), finger = false)

        fun close() { runCatching { edt { scene.close() } }; surface.close() }
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var r: Result<T>? = null
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r!!.getOrThrow()
    }
}
