@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import com.bmscompanion.app.data.mission.BmsStatus
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampFlightRow
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.CampWaypoint
import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionIniResult
import com.bmscompanion.app.data.wdp.DtcEdits
import com.bmscompanion.app.ui.screens.wdp.AttackTabs
import com.bmscompanion.app.ui.screens.wdp.DtcSource
import com.bmscompanion.app.ui.screens.wdp.ShellTab
import com.bmscompanion.app.ui.screens.wdp.DtcWiring
import com.bmscompanion.app.ui.screens.wdp.PlannerGate
import com.bmscompanion.app.ui.screens.wdp.PlannerMissionState
import com.bmscompanion.app.ui.screens.wdp.PlannerNotLinked
import com.bmscompanion.app.ui.screens.wdp.PlannerShell
import com.bmscompanion.app.ui.screens.wdp.PlannerWindow
import com.bmscompanion.app.ui.screens.wdp.PlannerWindowHost
import com.bmscompanion.app.ui.screens.wdp.PlannerWindows
import com.bmscompanion.app.ui.screens.wdp.WdpDialog
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpFocus
import com.bmscompanion.app.ui.screens.wdp.WdpMessage
import com.bmscompanion.app.ui.screens.wdp.WdpPage
import com.bmscompanion.app.ui.screens.wdp.WdpPlanner
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.screens.wdp.WdpSession
import com.bmscompanion.app.ui.screens.wdp.plannerGate
import com.bmscompanion.app.ui.screens.wdp.plannerMission
import com.bmscompanion.app.ui.screens.wdp.plannerNote
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities

/**
 * The `shell` part of `--planneroutcome`: the Planner's own shell around WDP's pages (R3-PLAN U2, A2, A14, A18).
 *
 * `--planneroutcome shell <outDir> <briefing.txt> <cartridge.ini>` — the briefing and the cartridge are copies, read
 * once; the cartridge should hold placed steerpoints (a pilot's with BMS's Recon targets in STPT 15-22 does). Nothing
 * is written anywhere but `<outDir>/shell/`: the DTC page's cartridge is held in memory by a recording source (a save
 * applies its edits to that text), and EZBoards is a counter.
 *
 * What it checks, in a headless scene driven with the mouse as a pilot would:
 * 1. **The note** (A18): which note each link state gives ([plannerGate]: idle, connecting, lost, no Falcon BMS, and
 *    none on "This PC"), each drawn at a phone's and a PC's size with its title, Setup and Guide; Guide opens the guide
 *    at the remote-device page and Setup does what it is given.
 * 2. **Rows A and B** at a PC's size: every page tab turns the page (ATO Targets included, the app's own page);
 *    Open mission…, Upd Kneeboard and Guide (at the page on screen) open their windows; Populate from Planner asks for
 *    a save's flight when the Planner plans the printed briefing; "Mission: …" and the credit are words, not second
 *    buttons; Options holds Settings… and About WDP only; Full window toggles; the identity strip opens WDP's selection
 *    window over the briefing's package, and with a save open the flight picker, which holds Back to BMS briefing (row
 *    B does not). The shell is two rows (64 dp) and the page starts under it; the card's Upd Kneeboard is not on it.
 *    **The Steps** (PlannerSteps): the toolbar's Steps shows and hides the panel beside the page; the step is 4 with
 *    the printed briefing; a press on step 4 lights Open mission… rather than opening it; a BMS step ticks.
 * 3. **Save to DTC**: the count rises on an edit of the DTC page and on an entry of the card, and falls to 0 on Save
 *    (which writes at once, as WDP's does: no question first); its menu's Re-read DTC from BMS reads the cartridge
 *    again, and it offers Save to DTC and populate (not pressed here: it would populate the PC).
 * 4. **Clear asks** (A14): Clear All STPTs with placed steerpoints asks first, naming them; Cancel keeps them.
 * 5. **A zeroing save warns** (A14): the save lists the points it would set to 0 and asks; Cancel writes nothing.
 * 6. **A TE's mission file** (A13): with a TE open — the pilot's own or one that ships with BMS — Save to DTC also
 *    writes its mission file; with a campaign save it does not.
 * 7. **Sizes**: the Planner at 400×800, 860×393, 1280×800 and 1920×1080 — the phone's single row with its page list
 *    and ⋮ menu, full window by itself on a phone held sideways, icons with names at middling widths — with pictures of
 *    the DataCard, DTC and TOSS pages at each (`shell/<size>-<page>.png`).
 * 8. **The toolbar fits** (a mouse): at PC windows from 1024 to 2560 dp (and less the rail) and two narrow browser
 *    windows every tab and action is drawn whole and in view, never scrolled (`shell/fit-<w>x<h>.png`); the three attack
 *    pages are one Attack tab whose rail beside the page turns it, and it reopens on the one used last; two windows at
 *    1.25× (a Windows desktop at 125 %) for the type.
 * 9. **The Attack rail** at 1600×900 and 1280×800 by mouse, and by finger on a tablet (1138×711, 711×1138) and a phone
 *    (412×915): three buttons beside the page, never over it, a finger high by finger, each turning the page
 *    (`shell/rail-<size>-<page>.png`).
 */
object ShellOutcome {
    private class Size(val name: String, val wDp: Int, val hDp: Int, val density: Float) {
        val w get() = (wDp * density).toInt()
        val h get() = (hDp * density).toInt()
    }

    private val PC = Size("pc", 1600, 900, 1f)
    private val SIZES = listOf(
        Size("w400x800", 400, 800, 2f), Size("w860x393", 860, 393, 2f),
        Size("w1280x800", 1280, 800, 1f), Size("w1920x1080", 1920, 1080, 1f), Size("w2560x1440", 2560, 1440, 1f),
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

    /** The DTC page's cartridge, in memory: what each call did is counted, and a save applies its edits to the text. */
    private class Recorder(var text: String?) : DtcSource {
        var loads = 0
        val saves = ArrayList<List<CartridgeEdit>>()
        val teSaves = ArrayList<CampRef>()
        var generated = 0

        fun state(message: String? = null) = CartridgeState(
            available = text != null, callsign = "Fixture", file = "Fixture.ini", text = text, modified = 1L + saves.size,
            path = "C:\\Falcon BMS\\User\\Config\\Fixture.ini", message = message,
            error = if (text == null) "No cartridge was given to the check." else null,
        )

        override suspend fun load(callsign: String?): CartridgeState { loads++; return state() }
        override suspend fun save(callsign: String?, edits: List<CartridgeEdit>): CartridgeState {
            if (text == null) return state()
            saves += edits
            text = text?.let { DtcEdits.apply(it, edits) }
            return state("Fixture.ini saved (${edits.size} keys).")
        }
        override suspend fun saveTe(callsign: String?, edits: List<CartridgeEdit>, te: CampRef): CartridgeState {
            teSaves += te
            val s = save(callsign, edits)
            return if (s.error != null) s else s.copy(mission = MissionIniResult(file = te.file.substringBeforeLast('.') + ".ini", written = true))
        }
        override suspend fun harmCodes() = dtcAppHarmCodes()
        override fun generateBoards(): (() -> Unit)? = { generated++ }
    }

    fun run(outDir: File, more: List<String>): String {
        val dir = File(outDir, "shell").also { it.mkdirs() }
        val r = Report()
        r.text.appendLine("The Planner's shell: rows A and B, the not-linked note, Save to DTC, Clear asks, the zeroing warning")
        val briefing = more.getOrNull(0)?.let(::File)?.takeIf { it.isFile }
        val cartridge = more.getOrNull(1)?.let(::File)?.takeIf { it.isFile }
        r.text.appendLine("briefing: ${if (briefing != null) "given" else "none"}, cartridge: ${if (cartridge != null) "given" else "none"}")
        val pageBefore = WdpSession.page
        WdpProbe.on = true
        try {
            gates(r)
            notes(r, File(dir, "note"))
            if (cartridge == null) r.fail("no cartridge given: `--planneroutcome shell <outDir> <briefing.txt> <cartridge.ini>` (a copy, read only)")
            else {
                val rec = Recorder(cartridge.readText(Charsets.ISO_8859_1))
                WdpSession.dtcSource = rec
                val data = WdpFixtureMission.data(briefing, cartridge)
                val theater = System.getenv("BMSC_WDP_THEATER")?.let { name ->
                    runBlocking { com.bmscompanion.app.ui.screens.wdp.plannerTheater(Repo.index().theaters, name) }
                }
                prime(data, theater, cartridge)
                try { shell(r, dir, data, theater?.name, rec) } catch (e: Throwable) { threw(r, "the shell run", e) }
                try { sizes(r, dir, data, theater?.name) } catch (e: Throwable) { threw(r, "the sizes run", e) }
                try { fits(r, dir, data, theater?.name) } catch (e: Throwable) { threw(r, "the toolbar-fits run", e) }
                try { rail(r, dir, data, theater?.name) } catch (e: Throwable) { threw(r, "the Attack rail run", e) }
                try { empty(r, dir, theater?.name) } catch (e: Throwable) { threw(r, "the empty-states run", e) }
            }
        } finally {
            edt { PlannerWindows.close(); WdpDialogs.stack.clear(); WdpFocus.on = false }
            resetSave()
            WdpProbe.on = false
            edt { WdpProbe.clear() }
            WdpSession.page = pageBefore
        }
        r.head("summary")
        r.text.appendLine("PASS ${r.pass}, FAIL ${r.fail}")
        r.text.appendLine(if (r.fail == 0) "ALL PASS" else "FAIL: ${r.fail} of ${r.pass + r.fail} checks")
        return r.text.toString()
    }

    private fun threw(r: Report, what: String, e: Throwable) {
        r.fail("$what threw ${e::class.simpleName}: ${e.message}")
        e.stackTrace.filter { it.className.startsWith("com.bmscompanion") }.take(10).forEach { r.info("  at $it") }
    }

    private fun resetSave() = edt {
        PlannerMissionState.source = PlannerMissionState.BRIEFING
        PlannerMissionState.flight = null
        PlannerMissionState.ref = null
        PlannerMissionState.kind = null
        PlannerMissionState.theater = null
        PlannerMissionState.seat = 0
    }

    // ------------------------------------------------------------------------------------------------ 1. the note

    private fun gates(r: Report) {
        r.head("which note each link state gives (plannerGate)")
        val installed = BridgeInfo(bms = BmsStatus(installed = true))
        val noBms = BridgeInfo(bms = BmsStatus(installed = false))
        val rows = listOf(
            Triple(LinkState.Idle, null as BridgeInfo?, PlannerGate.IDLE),
            Triple(LinkState.Connecting, null, PlannerGate.CONNECTING),
            Triple(LinkState.Offline("BMS-PC", "timed out"), installed, PlannerGate.LOST),
            Triple(LinkState.Online("BMS-PC"), noBms, PlannerGate.NO_BMS),
            Triple(LinkState.Online("BMS-PC"), installed, null),
            Triple(LinkState.Online("BMS-PC"), null, null),
        )
        for ((state, info, want) in rows) {
            val got = plannerGate(state, info, local = false)
            r.check(got == want, "${state::class.simpleName}${if (info != null) " (BMS ${if (info.bms.installed) "found" else "not found"})" else ""} → ${want?.name ?: "the Planner"}", { "got ${got?.name ?: "the Planner"}" })
        }
        val local = rows.all { (state, info, _) -> plannerGate(state, info, local = true) == null }
        r.check(local, "\"This PC\" (the PC window reading BMS in-process): never a note, in any state")
        r.check(com.bmscompanion.app.ui.screens.wdp.plannerIsLocal("127.0.0.1") && !com.bmscompanion.app.ui.screens.wdp.plannerIsLocal("BMS-PC") &&
            !com.bmscompanion.app.ui.screens.wdp.plannerIsLocal(null), "\"This PC\" is told by its in-process address only")
    }

    private fun notes(r: Report, dir: File) {
        r.head("the note in place of the Planner, at a phone's and a PC's size")
        dir.mkdirs()
        for (size in listOf(Size("phone", 400, 800, 2f), PC)) {
            for (gate in PlannerGate.entries) {
                for (ever in listOf(false, true)) {
                    if (ever && gate != PlannerGate.LOST) continue
                    var setup = 0
                    edt { WdpSession.everLinked = ever; PlannerWindows.close() }
                    val d = Probe(size) {
                        Box(Modifier.fillMaxSize().background(Hud.Bg)) {
                            PlannerNotLinked(gate, "BMS-PC", "timed out", onSetup = { setup++ })
                            PlannerWindowHost()
                        }
                    }
                    try {
                        d.settle(4)
                        val (title, _) = plannerNote(gate, "BMS-PC", "timed out", ever)
                        val name = "${size.name}-${gate.name.lowercase()}${if (gate == PlannerGate.LOST) (if (ever) "-was-linked" else "-never") else ""}"
                        d.png(File(dir, "$name.png"))
                        val shown = d.rect("planner/Shell/NotLinked/${gate.name}") != null
                        r.check(shown, "$name: the note is drawn (\"$title\")")
                        d.clickKey("planner/Shell/NotLinked/Setup")
                        r.check(setup == 1, "$name: Setup does what it is given")
                        d.clickKey("planner/Shell/NotLinked/Guide", settleAfter = false)
                        val open = edt { PlannerWindows.open }
                        val arg = edt { PlannerWindows.arg }
                        r.check(open == PlannerWindow.GUIDE && arg == "remote", "$name: Guide opens the guide at the remote-device page", { "opened ${open?.name} at $arg" })
                        if (size == PC && gate == PlannerGate.IDLE) { d.settle(6, 30); d.png(File(dir, "$name-guide.png")) }
                        edt { PlannerWindows.close() }
                    } catch (e: Throwable) {
                        threw(r, "the note $gate at ${size.name}", e)
                    } finally { d.close() }
                }
            }
        }
        edt { WdpSession.everLinked = false }
        r.info("pictures: $dir")
    }

    // ------------------------------------------------------------------------------------------------ 2-6. the shell

    /** The pages given the mission as the Planner gives it, with the check's cartridge (read from its copy, never the PC's). */
    private fun prime(data: MissionData, theater: com.bmscompanion.app.data.Theater?, cartridge: File) {
        val s = WdpSession
        s.theaterName = theater?.name; s.theater = theater
        val mission = plannerMission(data, theater, null)
        for (w in listOf(s.toss, s.popup, s.hadb)) w.onMission(mission)
        runBlocking { s.dataCard.prepare(mission) { cartridge.readText(Charsets.ISO_8859_1) } }
        s.dtc.onMission(mission)
        runBlocking { s.performance.prepare(mission) }
        com.bmscompanion.app.ui.screens.wdp.WdpHandOff.cardToPerformance(s.dataCard, s.performance, mission.briefing)
        s.appliedMission = mission
        Thread.sleep(1500)   // the DTC page reads its cartridge on its own scope
    }

    private fun topMessage(): WdpMessage? = edt { WdpDialogs.stack.lastOrNull() as? WdpMessage }

    private fun answer(m: WdpMessage, a: String) = edt { WdpDialogs.stack.remove(m); m.onAnswer?.invoke(a) }

    /** Answers every message box on top with its last button (Cancel / Not now / OK), so the next step starts clean. */
    private fun dismissAll() = edt {
        repeat(8) {
            val m = WdpDialogs.stack.lastOrNull() ?: return@repeat
            WdpDialogs.stack.remove(m)
            if (m is WdpMessage) m.onAnswer?.invoke(m.buttons.lastOrNull() ?: "OK")
        }
        WdpDialogs.stack.clear()
    }

    private fun shell(r: Report, dir: File, data: MissionData, theaterName: String?, rec: Recorder) {
        val s = WdpSession
        edt { s.page = WdpPage.DATACARD.name; WdpFocus.on = false }
        r.head("rows A and B at a PC's size (${PC.wDp}×${PC.hDp} dp)")
        val d = Probe(PC) { WdpPlanner(data, theaterName, Modifier.fillMaxSize()) }
        try {
            d.settle(30, 40)
            d.png(File(dir, "pc-datacard.png"))
            val keys = listOf("OpenMission", "SaveToDtc", "SaveMenu", "Populate", "Print", "Steps", "Guide", "Options", "FullWindow", "Identity", "MissionShows", "Credit") +
                ShellTab.ALL.map { "Tab/${it.key}" }
            val missing = keys.filter { d.rect("planner/Shell/$it") == null }
            r.check(missing.isEmpty(), "row A and row B: all ${keys.size} controls are drawn", { "missing: $missing" })
            val shell = keys.mapNotNull { d.rect("planner/Shell/$it") }
            val bottom = shell.maxOfOrNull { it.bottom } ?: 0f
            r.check(bottom <= 64f * PC.density + 1f, "the shell is two rows: it ends at ${bottom.toInt()} px (64 dp or less)")
            val pageTop = edt { WdpProbe.rects.filterKeys { it.startsWith("cntDataCard/") }.values.minOfOrNull { it.top } }
            r.check(pageTop != null && pageTop >= bottom - 1f, "the page starts under the shell (at ${pageTop?.toInt()} px)")
            val scale = edt { WdpProbe.scales["cntDataCard"] }
            r.info("DataCard drawn at ${scale?.let { "%.3f".format(it / PC.density) }} dp a designer pixel")
            val offPage = listOf("btnGetDTC", "btnSaveDTC", "lblCallsignIni").filter { d.rect("cntDataCard/$it") == null }
            val lamps = listOf("pnlCallsignGreen", "pnlCallsignRed").count { d.rect("cntDataCard/$it") != null }
            r.check(offPage.isEmpty() && lamps == 1, "the card's Get DTC File, Save DTC and its \"Callsign.ini saved\" lamp are on the page, as in WDP (one lamp lit)",
                { "not drawn: $offPage, lamps drawn: $lamps" })
            r.check(d.rect("cntDataCard/btnKneeboard") == null, "the card's own Upd Kneeboard is not on the page (the toolbar's is the one)")
            r.check(d.rect("planner/Shell/OpenMissionLink") == null && d.rect("planner/Shell/BackToBriefing") == null,
                "row B repeats no toolbar action (no Open mission… link, no Back to BMS briefing)")
            val noMission = d.rect("planner/Shell/StartHere") == null
            r.check(noMission, "with a briefing and a cartridge there is no \"Start here\" card over the page")

            // every tab turns the page (the Attack tab to one of its three)
            var tabs = 0
            for (t in ShellTab.ALL) {
                d.clickKey("planner/Shell/Tab/${t.key}")
                if (edt { s.page } in t.pages.map { it.name }) tabs++ else r.fail("the ${t.label} tab left the page on ${edt { s.page }}")
            }
            r.check(tabs == ShellTab.ALL.size, "every page tab turns the page ($tabs of ${ShellTab.ALL.size})")
            r.check(ShellTab.ALL.size == WdpPage.entries.size - 2 && WdpPage.entries.all { p -> ShellTab.ALL.count { p in it.pages } == 1 } &&
                d.rect("planner/Shell/Tab/POPUP") == null && d.rect("planner/Shell/Tab/TOSS") == null,
                "Pop-up, HADB and TOSS are one Attack tab; every page is under exactly one tab")
            // the Attack tab: its rail beside the page turns to each attack page, and it reopens on the last one used
            d.clickKey("planner/Shell/Tab/ATTACK")
            var subs = 0
            for (p in AttackTabs.PAGES) {
                d.clickKey("planner/Shell/Attack/${p.name}")
                if (edt { s.page } == p.name && d.rect("planner/Shell/Attack") != null) subs++ else r.fail("the ${p.label} rail button left the page on ${edt { s.page }}")
            }
            r.check(subs == AttackTabs.PAGES.size, "the Attack tab's rail turns to Pop-up, HADB and TOSS ($subs of ${AttackTabs.PAGES.size})")
            d.png(File(dir, "pc-attack.png"))
            d.clickKey("planner/Shell/Attack/HADB")
            d.clickKey("planner/Shell/Tab/DATACARD")
            r.check(d.rect("planner/Shell/Attack") == null, "no Attack rail beside a page that is not an attack page")
            d.clickKey("planner/Shell/Tab/ATTACK")
            r.check(edt { s.page } == WdpPage.HADB.name && AttackTabs.last() == WdpPage.HADB,
                "the Attack tab reopens on the attack page used last (HADB), kept in the device's settings", { "page ${edt { s.page }}" })
            // turned to TOSS from elsewhere (the Guide's Go there, the card's profile): Attack lit, TOSS chosen
            edt { s.page = WdpPage.TOSS.name }; d.settle(6)
            r.check(d.rect("planner/Shell/Attack/TOSS") != null && edt { s.page } == WdpPage.TOSS.name && AttackTabs.last() == WdpPage.TOSS,
                "a page turned to TOSS from elsewhere shows the Attack tab with TOSS chosen")
            d.clickKey("planner/Shell/Tab/DATACARD")

            // the windows
            fun window(key: String, want: PlannerWindow, arg: String?, what: String) {
                d.clickKey("planner/Shell/$key", settleAfter = false)
                val open = edt { PlannerWindows.open }
                val a = edt { PlannerWindows.arg }
                r.check(open == want && (arg == null || a == arg), "$what", { "opened ${open?.name ?: "nothing"}${a?.let { " at $it" } ?: ""}" })
                edt { PlannerWindows.close() }
                d.settle(3)
            }
            window("OpenMission", PlannerWindow.OPEN_MISSION, null, "Open mission… opens the campaign-file browser")
            window("Print", PlannerWindow.PRINT, null, "Upd Kneeboard opens its window")
            window("Guide", PlannerWindow.GUIDE, WdpPage.DATACARD.name, "Guide opens the guide at the page on screen")
            // the credit is in view, but the credit page is Options → About WDP's (one place per action)
            d.clickKey("planner/Shell/Credit", settleAfter = false)
            r.check(edt { PlannerWindows.open } == null, "\"WDP by Falcas\" is the credit in words, not a second About button", { "opened ${edt { PlannerWindows.open }}" })
            edt { PlannerWindows.close() }; d.settle(2)
            // WDP's Options menu (fclsMain.mnuOptions, its Settings and Help → About): Settings…, About WDP (its ATO
            // Target List is the ATO Targets tab)
            d.clickKey("planner/Shell/Options")
            val optionKeys = listOf("Settings", "Credit")
            val inMenu = optionKeys.filter { d.rect("planner/Shell/Options/$it") != null }
            r.check(d.rect("planner/Shell/Menu/OPTIONS") != null && inMenu.size == optionKeys.size && d.rect("planner/Shell/Options/AtoTargets") == null,
                "Options opens WDP's Options menu: Settings…, About WDP (the ATO Target List is a tab now)", { "items drawn: $inMenu" })
            d.png(File(dir, "pc-options.png"))
            window("Options/Settings", PlannerWindow.SETTINGS, null, "Options → Settings… opens the Planner's settings (WDP's Settings window)")
            // Settings: its switches pressed; Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints is the PC's (1.3.9), read and set over
            // /api/planner/settings — pressed where the check reaches a PC, else shown greyed with the reason
            run {
                val ps = com.bmscompanion.app.ui.screens.wdp.PlannerSettings
                edt { PlannerWindows.show(PlannerWindow.SETTINGS) }
                d.settle(6, 20)
                val until = System.currentTimeMillis() + 3_000
                while (edt { ps.cleanOpened == null && ps.cleanOpenedNote == null } && System.currentTimeMillis() < until) { Thread.sleep(50); d.settle(1) }
                d.settle(3)
                r.check(d.rect("planner/Settings/CleanOpened") != null && d.rect("planner/Settings/Tooltips") != null && d.rect("planner/Settings/AutoLoad") != null,
                    "Settings shows Show tooltips, Auto load last mission and Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints")
                for (key in listOf("Tooltips", "AutoLoad")) {
                    val before = edt { if (key == "Tooltips") ps.tooltips else ps.autoLoad }
                    d.clickKey("planner/Settings/$key")
                    val flipped = edt { if (key == "Tooltips") ps.tooltips else ps.autoLoad } != before
                    d.clickKey("planner/Settings/$key")
                    r.check(flipped && edt { if (key == "Tooltips") ps.tooltips else ps.autoLoad } == before, "Settings: $key pressed turns it over, pressed again back")
                }
                val c0 = edt { ps.cleanOpened }
                if (c0 != null) {
                    d.clickKey("planner/Settings/CleanOpened"); d.settle(4, 30)
                    val w = System.currentTimeMillis() + 2_000
                    while (edt { ps.cleanOpened } == c0 && System.currentTimeMillis() < w) { Thread.sleep(50); d.settle(1) }
                    val flipped = edt { ps.cleanOpened } == !c0 && Bridge.settings.value.CleanOpenedMission == !c0
                    d.clickKey("planner/Settings/CleanOpened"); d.settle(4, 30)
                    val w2 = System.currentTimeMillis() + 2_000
                    while (edt { ps.cleanOpened } != c0 && System.currentTimeMillis() < w2) { Thread.sleep(50); d.settle(1) }
                    r.check(flipped && edt { ps.cleanOpened } == c0 && Bridge.settings.value.CleanOpenedMission == c0,
                        "Settings: Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints pressed turns the PC's setting over, pressed again back (was $c0)")
                } else {
                    d.clickKey("planner/Settings/CleanOpened"); d.settle(3, 20)
                    r.check(edt { ps.cleanOpened } == null && edt { ps.cleanOpenedNote } != null,
                        "Settings: with no PC reached, Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints is greyed and says why (\"${edt { ps.cleanOpenedNote }}\")")
                }
                d.png(File(dir, "pc-settings.png"))
                edt { PlannerWindows.close() }
                d.settle(3)
            }
            d.clickKey("planner/Shell/Options")
            window("Options/Credit", PlannerWindow.GUIDE, "credit", "Options → About WDP (Falcas) opens the guide's credit page")
            r.check(d.rect("planner/Shell/Menu/OPTIONS") == null, "a pick closes the Options menu")
            // Populate from Planner with the printed briefing as the source: it says it takes a save's flight, and asks
            d.clickKey("planner/Shell/Populate")
            val m = topMessage()
            r.check(m != null && m.title == com.bmscompanion.app.data.mission.MissionMode.POPULATE && "Open mission…" in m.buttons,
                "Populate with no save's flight open asks for one (Open mission…)", { "top: ${m?.title} ${m?.buttons}" })
            dismissAll(); d.settle(2)
            // "Mission: …" says what the Mission section shows; it is not a second Populate button
            d.clickKey("planner/Shell/MissionShows")
            r.check(topMessage() == null, "\"Mission: …\" is a status: a press populates nothing", { "top: ${topMessage()?.title}" })
            dismissAll(); d.settle(2)

            // the ATO Target List is a tab of its own (WdpPage.ATO), not a window
            d.clickKey("planner/Shell/Tab/ATO")
            d.settle(6, 20)
            r.check(edt { s.page } == WdpPage.ATO.name && d.rect("planner/AtoTargets/Page") != null && edt { PlannerWindows.open } == null,
                "the ATO Targets tab shows WDP's ATO Target List as a page", { "page ${edt { s.page }}, window ${edt { PlannerWindows.open }}" })
            d.png(File(dir, "pc-ato.png"))
            edt { s.page = WdpPage.DATACARD.name; com.bmscompanion.app.ui.screens.wdp.AtoTargetList.open() }
            d.settle(4)
            r.check(edt { s.page } == WdpPage.ATO.name, "the DataCard's flight selection (AtoTargetList.open) turns to the ATO Targets tab")
            d.clickKey("planner/Shell/Tab/DATACARD")

            // ---- the Steps (PlannerSteps)
            val steps = com.bmscompanion.app.ui.screens.wdp.PlannerSteps
            val stepsWas = edt { steps.open }
            if (!stepsWas) { d.clickKey("planner/Shell/Steps"); d.settle(3) }
            r.check(d.rect("planner/Steps/Panel") != null, "Steps: the panel beside the page", { "panel ${d.rect("planner/Steps/Panel")}" })
            val lines = (1..10).count { d.rect("planner/Steps/Step/$it") != null }
            r.check(lines == 10, "Steps: ten numbered lines", { "$lines drawn" })
            val pageRight = edt { WdpProbe.rects.filterKeys { it.startsWith("cntDataCard/") }.values.maxOfOrNull { it.right } } ?: 0f
            val panelLeft = d.rect("planner/Steps/Panel")?.left ?: 0f
            r.check(pageRight <= panelLeft + 1f, "Steps: the page is fitted beside the panel, not under it (page ends at ${pageRight.toInt()}, panel at ${panelLeft.toInt()})")
            d.png(File(dir, "pc-steps.png"))
            val f0 = edt { steps.facts(PlannerShell.unsaved(), com.bmscompanion.app.data.mission.MissionLink.info.value?.mission, data.briefingModified) }
            r.check(steps.current(f0) == 1 || steps.current(f0) == 4, "Steps: with the printed briefing and nothing opened, the step is 1-4 (${steps.current(f0)})")
            d.clickKey("planner/Steps/Step/4")
            r.check(edt { PlannerWindows.open } == null && edt { steps.flash } == "Shell/OpenMission",
                "Steps: a press on step 4 lights the toolbar's Open mission… and opens nothing itself (one place per action)",
                { "window ${edt { PlannerWindows.open }}, lit ${edt { steps.flash }}" })
            val ticked = edt { 7 in steps.ticks }
            d.clickKey("planner/Steps/Step/7")
            r.check(edt { 7 in steps.ticks } != ticked, "Steps: a press on a BMS step ticks it")
            d.clickKey("planner/Steps/Step/7")
            d.clickKey("planner/Shell/Steps"); d.settle(3)
            r.check(d.rect("planner/Steps/Panel") == null, "Steps: the toolbar's Steps hides the panel again")
            if (stepsWas) { d.clickKey("planner/Shell/Steps"); d.settle(3) }
            // tooltips (WdpTips): the mouse resting on a control shows its tip after half a second, the shell's from
            // tips/planner.json and a page's from tips/<form>.json; with Settings → Show tooltips off, none shows
            fun tipAt(key: String): String? {
                val at = d.rect(key) ?: return "(not on screen)"
                edt { com.bmscompanion.app.ui.screens.wdp.WdpTips.hide() }
                d.hover(Offset(at.center.x, at.bottom + 200f)); d.settle(2)
                d.hover(at.center); d.hover(at.center + Offset(1f, 0f))
                d.settle(10, 90)
                return edt { com.bmscompanion.app.ui.screens.wdp.WdpTips.shown?.text }
            }
            val tips = com.bmscompanion.app.ui.screens.wdp.WdpTips
            val gearTip = tips.text(tips.PLANNER, "Shell/Options")
            val shownGear = tipAt("planner/Shell/Options")
            r.check(gearTip != null && shownGear == gearTip, "the mouse resting on Options shows its tip from tips/planner.json", { "shown: $shownGear; file: $gearTip" })
            val formTip = tips.text("cntDataCard", "btnAirportSchedule")
            val shownForm = tipAt("cntDataCard/btnAirportSchedule")
            r.check(formTip != null && shownForm == formTip, "a page's control shows its tip from tips/cntDataCard.json (Airport Schedule)", { "shown: $shownForm; file: $formTip" })
            edt { com.bmscompanion.app.ui.screens.wdp.PlannerSettings.chooseTooltips(false) }
            d.settle(3)
            val shownOff = tipAt("planner/Shell/Options")
            edt { com.bmscompanion.app.ui.screens.wdp.PlannerSettings.chooseTooltips(true) }
            d.hover(Offset(1f, PC.hDp * PC.density - 2f)); d.settle(3)
            r.check(shownOff == null, "Settings → Show tooltips off: no tip shows", { "shown: $shownOff" })
            d.clickKey("planner/Shell/FullWindow")
            val on = WdpFocus.on
            d.clickKey("planner/Shell/FullWindow")
            r.check(on && !WdpFocus.on, "Full window turns on and off")

            // the identity strip: WDP's selection window over the briefing's package (Different Flight)
            d.clickKey("planner/Shell/Identity")
            val sel = edt { WdpDialogs.stack.lastOrNull() }
            r.check(sel is WdpDialog && sel.form == "fclsSelection" || data.briefing == null && sel is WdpMessage,
                "the identity strip (printed briefing) opens WDP's selection window over the package", { "opened ${(sel as? WdpDialog)?.form ?: (sel as? WdpMessage)?.title ?: "nothing"}" })
            if (sel is WdpDialog) d.png(File(dir, "pc-identity-selection.png"))
            dismissAll(); d.settle(3)

            // ---- 3. Save to DTC: the count
            r.head("Save to DTC: the count, the save, the menu")
            val c0 = edt { PlannerShell.unsaved() }
            r.check(c0 == 0, "no edits: the count is 0", { "count $c0" })
            r.check(d.rect("planner/Shell/SaveCount") == null, "no edits: no count is drawn on Save to DTC")
            edt { s.dtc.onValue("mxtBingo", "3100"); s.dtc.onValue("mxtBingo.leave", "") }
            d.settle(3)
            val c1 = edt { PlannerShell.unsaved() }
            r.check(c1 >= 1 && d.rect("planner/Shell/SaveCount") != null, "an edit on the DTC page (bingo 3100): the count rises to $c1 and is drawn")
            edt { s.dataCard.onValue("txtALOW", "1234"); s.dataCard.onValue("txtALOW.leave", "") }
            d.settle(3)
            val c2 = edt { PlannerShell.unsaved() }
            r.check(c2 == c1 + 1, "an entry on the card (ALOW 1234): the count rises to $c2", { "was $c1" })
            d.png(File(dir, "pc-count.png"))
            d.clickKey("planner/Shell/SaveToDtc")
            d.settle(3)
            val ask = topMessage()
            r.check(rec.saves.size == 1 && ask?.buttons?.any { it.contains("Back up", true) } != true,
                "the first Save writes at once, as WDP's does: no question first, saved once", { "saves ${rec.saves.size}, top: ${ask?.title}" })
            val keysSaved = rec.saves.lastOrNull().orEmpty().map { "${it.section}/${it.key}" }
            r.check(keysSaved.size >= 2 && keysSaved.any { it.contains("Bingo", true) } && keysSaved.any { it.contains("Alow", true) },
                "the save wrote the DTC page's edit and the card's entry", { "keys: $keysSaved" })
            val c3 = edt { PlannerShell.unsaved() }
            r.check(c3 == 0 && d.rect("planner/Shell/SaveCount") == null, "after Save the count falls to 0", { "count $c3" })
            val said = topMessage()
            r.info("the answer: ${said?.title}: ${said?.text?.take(90)?.replace('\n', ' ')}")
            dismissAll(); d.settle(2)

            // the menu
            d.clickKey("planner/Shell/SaveMenu")
            r.check(d.rect("planner/Shell/Menu/SAVE") != null, "the arrow beside Save to DTC opens its menu")
            d.png(File(dir, "pc-save-menu.png"))
            val loads = rec.loads
            d.clickKey("planner/Shell/SaveMenu/Reread")
            d.settle(3)
            r.check(rec.loads == loads + 1 && d.rect("planner/Shell/Menu/SAVE") == null, "Re-read DTC from BMS reads the cartridge again (and the menu closes)", { "loads ${rec.loads - loads}" })
            dismissAll(); d.settle(2)
            d.clickKey("planner/Shell/SaveMenu")
            r.check(d.rect("planner/Shell/SaveMenu/SaveAndPopulate") != null && d.rect("planner/Shell/SaveMenu/Generate") == null,
                "the menu offers Save to DTC and populate, and no Generate kneeboards (EZBoards is paused in WDP mode)")
            edt { WdpDialogs.stack.clear() }; d.clickKey("planner/Shell/SaveMenu"); d.settle(2)

            // ---- 4. Clear asks
            r.head("Clear All asks before wiping placed points (A14)")
            edt { s.page = WdpPage.DTC.name; s.dtc.onValue("tabDTC", "tabSTPT") }
            d.settle(20, 30)
            r.check(d.rect("cntDTC/btnSaveDtcStpt") != null, "the STPT tab's own button (From mission…) is on the page")
            d.png(File(dir, "pc-dtc-stpt.png"))
            val placed = edt { s.dtc.unsaved }
            if (d.rect("cntDTC/btnClearStpt") == null) r.fail("Clear All STPTs is not on the STPT tab")
            else {
                d.clickKey("cntDTC/btnClearStpt")
                val q = topMessage()
                r.check(q != null && q.title == "Clear All" && "Cancel" in q.buttons, "Clear All STPTs asks first", { "top: ${q?.title}" })
                r.info("the question: ${q?.text?.replace('\n', ' ')?.take(200)}")
                r.check(q?.text?.contains("STPT 15-22") == true || q?.text?.contains("STPT ") == true, "the question names the placed steerpoints")
                if (q != null) { d.png(File(dir, "pc-clear-question.png")); answer(q, "Cancel") }
                d.settle(3)
                r.check(edt { s.dtc.zeroed() }.isEmpty() && edt { s.dtc.unsaved } == placed, "Cancel: nothing cleared")
                d.clickKey("cntDTC/btnClearStpt")
                topMessage()?.let { answer(it, "Clear") }
                d.settle(3)
                val gone = edt { s.dtc.zeroed() }
                r.check(gone.isNotEmpty() && gone.all { it.startsWith("STPT") }, "Clear: the placed steerpoints go to 0 on the page (${gone.joinToString()})")
                d.png(File(dir, "pc-dtc-cleared.png"))

                // ---- 5. a zeroing save warns
                r.head("a save that would zero placed points warns first (A14)")
                val saves = rec.saves.size
                d.clickKey("planner/Shell/SaveToDtc")
                val w = topMessage()
                r.check(w != null && "Save anyway" in w.buttons && gone.all { w.text.contains(it) }, "Save lists the points it would zero (${gone.joinToString()}) and asks", { "top: ${w?.title}: ${w?.text?.take(80)}" })
                if (w != null) { d.png(File(dir, "pc-zero-warning.png")); answer(w, "Cancel") }
                d.settle(3)
                r.check(rec.saves.size == saves && edt { PlannerShell.unsaved() } > 0, "Cancel: nothing written, the edits still counted")
                d.clickKey("planner/Shell/SaveToDtc")
                topMessage()?.let { answer(it, "Save anyway") }
                d.settle(3)
                r.check(rec.saves.size == saves + 1 && edt { PlannerShell.unsaved() } == 0, "Save anyway: written once, the count back to 0")
                val zeroedInFile = rec.text?.lineSequence()?.firstOrNull { it.startsWith("target_14=") }?.startsWith("target_14=0.000000, 0.000000") == true
                r.check(zeroedInFile, "the cartridge now holds STPT 15 at 0,0 (what the pilot agreed to)")
                dismissAll(); d.settle(2)
                // a Clear with nothing placed does not ask
                d.clickKey("cntDTC/btnClearStpt")
                r.check(topMessage() == null, "Clear All STPTs with nothing placed clears without a question")
                dismissAll()
            }

            // ---- 6. a TE's mission file
            r.head("a Tactical Engagement's mission file on Save to DTC (A13)")
            val flight = CampFlight(
                row = CampFlightRow(id = "shell", number = 1, callsign = "Test1", mission = "Strike", aircraft = "F-16CM-50", count = 2, briefed = false, f16 = true),
                packageNumber = 7288,
                route = listOf(CampWaypoint(n = 1, x = 1_000_000.0, y = 1_000_000.0, altFt = 20_000.0, action = 1, desc = "Takeoff")),
            )
            edt {
                s.page = WdpPage.DATACARD.name
                PlannerMissionState.flight = flight
                PlannerMissionState.ref = CampRef("Korea KTO", "MyTE.tac", "shell")
                PlannerMissionState.kind = CampKind.TE
                PlannerMissionState.theater = theaterName
                PlannerMissionState.source = PlannerMissionState.SAVE
            }
            d.settle(20, 30)
            // a flight given while the page holds edits made for the one before asks first (D84): read the cartridge again
            topMessage()?.takeIf { it.title == DtcWiring.ANOTHER_FLIGHT }?.let { answer(it, "Read the cartridge"); d.settle(3) }
            r.check(d.rect("planner/Shell/BackToBriefing") == null, "with a save open, row B has no Back to BMS briefing of its own (the flight picker has it)")
            d.png(File(dir, "pc-save-open.png"))
            d.clickKey("planner/Shell/Identity", settleAfter = false)
            r.check(edt { PlannerWindows.open } == PlannerWindow.FLIGHT_PICKER, "the identity strip (a save open) opens the flight picker")
            d.settle(4)
            r.check(d.rect("planner/FlightPicker/BackToBriefing") != null, "the flight picker holds Back to BMS briefing, beside what is planned")
            edt { PlannerWindows.close() }; d.settle(3)
            edt { s.dtc.onValue("mxtBingo", "3300"); s.dtc.onValue("mxtBingo.leave", "") }
            d.settle(2)
            val te = rec.teSaves.size
            topMessage()?.takeIf { it.title == DtcWiring.ANOTHER_FLIGHT }?.let { answer(it, "Keep the changes"); d.settle(2) }
            d.clickKey("planner/Shell/SaveToDtc")
            var a = topMessage()
            r.check(rec.teSaves.size == te + 1 && rec.teSaves.last().file == "MyTE.tac", "a pilot's own TE: Save to DTC writes the cartridge and names the TE to the PC",
                { "TE saves ${rec.teSaves.size - te}" })
            r.check(a?.text?.contains("MyTE.ini was written too") == true, "the answer says the TE's mission file was written too", { "said: ${a?.text?.take(120)}" })
            dismissAll(); d.settle(2)
            edt {
                PlannerMissionState.ref = CampRef("Korea KTO", "TE_BMS_03_Airbase Attack.tac", "shell")
                s.dtc.onValue("mxtBingo", "3400"); s.dtc.onValue("mxtBingo.leave", "")
            }
            d.settle(2)
            topMessage()?.takeIf { it.title == DtcWiring.ANOTHER_FLIGHT }?.let { answer(it, "Keep the changes"); d.settle(2) }
            d.clickKey("planner/Shell/SaveToDtc")
            a = topMessage()
            r.check(rec.teSaves.size == te + 2 && rec.teSaves.last().file.startsWith("TE_BMS_03") && a?.text?.contains("was written too") == true &&
                a?.text?.contains("ships with Falcon BMS") != true,
                "a TE that ships with BMS: saved like the pilot's own, its mission file too", { "TE saves ${rec.teSaves.size - te}, said: ${a?.text?.take(120)}" })
            dismissAll(); d.settle(2)
            edt { PlannerMissionState.kind = CampKind.CAMPAIGN; s.dtc.onValue("mxtBingo", "3500"); s.dtc.onValue("mxtBingo.leave", "") }
            d.settle(2)
            d.clickKey("planner/Shell/SaveToDtc")
            r.check(rec.teSaves.size == te + 3, "a campaign save: named to the PC too, whose LOAD takes lines, PPTs and targets from its mission file (D46)",
                { "TE saves ${rec.teSaves.size - te}" })
            dismissAll(); d.settle(2)
            d.clickKey("planner/Shell/Identity", settleAfter = false)
            d.settle(4)
            d.clickKey("planner/FlightPicker/BackToBriefing")
            r.check(!PlannerMissionState.fromSave, "Back to BMS briefing (in the flight picker): the printed briefing is the source again")
            edt { PlannerWindows.close() }; d.settle(2)
        } finally {
            dismissAll()
            resetSave()
            d.close()
        }
    }

    // ------------------------------------------------------------------------------------------------ 7. sizes

    private fun sizes(r: Report, dir: File, data: MissionData, theaterName: String?) {
        val s = WdpSession
        // the DataCard's scale (dp a designer pixel) at each size with room for the tabs: the page grows with the window
        val grows = LinkedHashMap<String, Float>()
        for (size in SIZES) {
            r.head("the Planner at ${size.name} (${size.wDp}×${size.hDp} dp at ${size.density}×)")
            edt { WdpFocus.on = false; s.page = WdpPage.DATACARD.name; s.dtc.onValue("tabDTC", "tabSTPT") }
            // Under 480 dp tall is a phone held sideways, and the Planner goes full window by itself only on a
            // touch-first screen that short (WdpPlanner judges the screen, never its own area or a PC window: a short
            // PC window flickered in and out of full window). So that size is drawn once as the phone — touch first,
            // the screen's size in LocalConfiguration as Android gives it — for that alone, and the rest of the run
            // is the same size as a PC window, which must stay as it is.
            val sideways = size.hDp < 480
            if (sideways) {
                com.bmscompanion.app.data.Platform.touchFirst = true
                val phone = Probe(size) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalConfiguration provides androidx.compose.ui.platform.Configuration(size.wDp, size.hDp),
                    ) { WdpPlanner(data, theaterName, Modifier.fillMaxSize()) }
                }
                try {
                    phone.settle(30, 40)
                    r.check(WdpFocus.on, "a phone held sideways (a touch-first screen under 480 dp tall): full window by itself")
                } finally {
                    phone.close()
                    com.bmscompanion.app.data.Platform.touchFirst = false
                    edt { WdpFocus.on = false; WdpFocus.auto = false }
                }
            }
            val d = Probe(size) { WdpPlanner(data, theaterName, Modifier.fillMaxSize()) }
            try {
                d.settle(30, 40)
                val compact = size.wDp < 600 || size.hDp < 480
                if (compact) {
                    r.check(d.rect("planner/Shell/PagePicker") != null && d.rect("planner/Shell/Tab/DTC") == null, "one row: the page list in place of the tabs")
                    r.check(d.rect("planner/Shell/Identity") != null && d.rect("planner/Shell/SaveToDtc") != null && d.rect("planner/Shell/More") != null,
                        "one row: the flight, Save to DTC and the ⋮ menu")
                } else {
                    r.check(ShellTab.ALL.all { d.rect("planner/Shell/Tab/${it.key}") != null }, "the page tabs")
                    r.check(listOf("OpenMission", "SaveToDtc", "Identity", "MissionShows", "Credit").all { d.rect("planner/Shell/$it") != null }, "the actions and row B")
                    r.check(d.rect("planner/Shell/Options") != null, "WDP's Options menu on the toolbar (${if (size.wDp >= 1300) "named" else "an icon"})")
                }
                if (sideways) r.check(!WdpFocus.on, "a PC window as short: not full window by itself (it flickered in and out)")
                if (size.wDp < 600 && size.hDp >= 480) r.check(d.rect("planner/Shell/MissionShows") != null, "a phone held upright: the flight, the hint and the credit in the band under the page")
                for (p in listOf(WdpPage.DATACARD, WdpPage.DTC, WdpPage.TOSS)) {
                    edt { s.page = p.name }
                    d.settle(20, 30)
                    val scale = edt { WdpProbe.scales[p.form] }
                    val bottomOfShell = edt { WdpProbe.rects.filterKeys { it.startsWith("planner/Shell/") && !it.startsWith("planner/Shell/Attack") && !it.startsWith("planner/Shell/Notice") && it != "planner/Shell/MissionShows" && it != "planner/Shell/Credit" }.values.maxOfOrNull { it.bottom } } ?: 0f
                    d.png(File(dir, "${size.name}-${p.name.lowercase()}.png"))
                    if (p == WdpPage.DATACARD && scale != null && size.wDp >= 600 && size.hDp >= 480) grows[size.name] = scale / size.density
                    r.check(scale != null && scale > 0f, "${p.label}: drawn whole at ${scale?.let { "%.3f".format(it / size.density) }} dp a designer pixel (shell ends at ${(bottomOfShell / size.density).toInt()} dp)")
                }
                if (compact) {
                    edt { s.page = WdpPage.DATACARD.name }
                    d.settle(4)
                    d.clickKey("planner/Shell/PagePicker")
                    r.check(d.rect("planner/Shell/Menu/PAGES") != null, "the page list opens")
                    d.png(File(dir, "${size.name}-pages.png"))
                    d.clickKey("planner/Shell/PagePicker/TOSS")
                    r.check(edt { s.page } == WdpPage.TOSS.name, "a page picked from the list is shown")
                    d.clickKey("planner/Shell/More")
                    r.check(d.rect("planner/Shell/Menu/MORE") != null && d.rect("planner/Shell/More/Print") != null && d.rect("planner/Shell/More/Reread") != null,
                        "the ⋮ menu holds the rest (Populate, Print, Guide, Re-read, Save to DTC and populate…)")
                    r.check(listOf("Options", "Settings", "Credit").all { d.rect("planner/Shell/More/$it") != null },
                        "the ⋮ menu holds WDP's Options under their name (Settings…, About WDP)")
                    r.check(d.rect("planner/Shell/More/SaveToDtc") == null && d.rect("planner/Shell/More/BackToBriefing") == null,
                        "the ⋮ menu repeats nothing the row shows (no Save to DTC: it is on the row)")
                    d.png(File(dir, "${size.name}-more.png"))
                    d.clickKey("planner/Shell/More/Print", settleAfter = false)
                    r.check(edt { PlannerWindows.open } == PlannerWindow.PRINT, "⋮ → Upd Kneeboard opens its window")
                    edt { PlannerWindows.close() }
                    d.settle(3)
                    // the Steps as a sheet; where the row has no room for an action, its step does it in one press
                    d.clickKey("planner/Shell/Steps")
                    r.check(d.rect("planner/Steps/Sheet") != null && (1..10).all { d.rect("planner/Steps/Step/$it") != null },
                        "Steps on a phone: a sheet with the ten lines")
                    d.png(File(dir, "${size.name}-steps.png"))
                    d.clickKey("planner/Steps/Step/4", settleAfter = false)
                    d.settle(3)
                    r.check(edt { PlannerWindows.open } == PlannerWindow.OPEN_MISSION && d.rect("planner/Steps/Sheet") == null,
                        "Steps on a phone: step 4 opens Open mission… (the row has no room for it) and the sheet steps aside",
                        { "opened ${edt { PlannerWindows.open }}" })
                    edt { PlannerWindows.close(); com.bmscompanion.app.ui.screens.wdp.PlannerSteps.sheet = false }
                } else if (size.wDp < 1300) {
                    r.check(d.rect("planner/Shell/OpenMission") != null, "icons for the actions at this width")
                }
            } catch (e: Throwable) {
                threw(r, "the ${size.name} run", e)
            } finally {
                dismissAll()
                edt { PlannerWindows.close(); WdpFocus.on = false; WdpFocus.auto = false }
                d.close()
            }
        }
        val g = grows.values.toList()
        r.check(g.size >= 3 && g.zipWithNext().all { (a, b) -> b > a * 1.05f }, "the page grows with the window: DataCard " +
            grows.entries.joinToString(" → ") { "${it.key.removePrefix("w")} %.2f".format(it.value) } + " dp a designer pixel")
        r.info("pictures: $dir")
    }

    /**
     * A mouse's toolbar at the PC windows a pilot uses (1024 to 2560 dp wide, each also less the app's 80-dp rail, which
     * is the Planner's own width there) and two narrow browser windows: every page tab and every action is drawn whole,
     * inside the Planner's width and over nothing else — no sideways scroll hides one (the tabs once scrolled, and HADB,
     * TOSS and ATO Targets were out of sight until the bar was dragged). Pictures `shell/fit-<w>x<h>.png`.
     */
    private fun fits(r: Report, dir: File, data: MissionData, theaterName: String?) {
        val s = WdpSession
        val windows = listOf(1024 to 768, 1280 to 800, 1366 to 768, 1600 to 900, 1920 to 1080, 2560 to 1440)
        val sizes = windows.flatMap { (w, h) -> listOf(Size("fit-${w}x$h", w, h, 1f), Size("fit-${w - 80}x$h", w - 80, h, 1f)) } +
            listOf(Size("fit-860x900", 860, 900, 1f), Size("fit-700x900", 700, 900, 1f)) +
            // a Windows desktop at 125 % (1920×1080 and 2560×1440 screens): the type at a fractional scale
            listOf(Size("fit-1536x864@1.25", 1536, 864, 1.25f), Size("fit-2048x1152@1.25", 2048, 1152, 1.25f))
        r.head("a mouse's toolbar fits: every tab and action in view at ${sizes.size} widths")
        val actions = listOf("OpenMission", "SaveToDtc", "SaveMenu", "Populate", "Print", "Steps", "Guide", "Options", "FullWindow")
        for (size in sizes) {
            edt { WdpFocus.on = false; s.page = (if (size.wDp == 1280) WdpPage.TOSS else WdpPage.DATACARD).name }
            val d = Probe(size) { WdpPlanner(data, theaterName, Modifier.fillMaxSize()) }
            try {
                d.settle(30, 40)
                d.png(File(dir, "${size.name}.png"))
                val keys = ShellTab.ALL.map { "Tab/${it.key}" } + actions
                val rects = keys.associateWith { d.rect("planner/Shell/$it") }
                val missing = rects.filterValues { it == null }.keys
                val placed = rects.mapNotNull { (k, v) -> v?.let { k to it } }
                val outside = placed.filter { (_, v) -> v.left < -0.5f || v.right > size.w + 0.5f }.map { it.first }
                // (a tab's probe is its words, inside its padding: "DTC" is some 23 dp; a squeezed one is far less)
                val squeezed = placed.filter { (_, v) -> v.width < 14f * size.density }.map { it.first }
                val overlaps = placed.flatMapIndexed { i, (a, ra) ->
                    placed.drop(i + 1).filter { (_, rb) -> ra.overlaps(rb) && ra.intersect(rb).let { it.width > 1f && it.height > 1f } }.map { (b, _) -> "$a/$b" }
                }
                val below = (rects["Options"]?.top ?: 0f) > (rects["Tab/DATACARD"]?.bottom ?: 0f) - 1f
                r.check(missing.isEmpty() && outside.isEmpty() && squeezed.isEmpty() && overlaps.isEmpty(),
                    "${size.wDp}×${size.hDp} dp: all ${ShellTab.ALL.size} tabs and ${actions.size} actions drawn whole and in view" +
                        (if (below) " (the actions on row B)" else ""),
                    { "missing $missing, outside $outside, squeezed $squeezed, overlapping $overlaps" })
                if (size.wDp >= 944) r.check(!below, "${size.wDp} dp: tabs and actions on one row")
                if (s.page == WdpPage.TOSS.name) r.check(d.rect("planner/Shell/Attack/TOSS") != null && AttackTabs.PAGES.all { d.rect("planner/Shell/Attack/${it.name}") != null },
                    "${size.wDp} dp on TOSS: the Attack tab's rail beside the page")
            } catch (e: Throwable) {
                threw(r, "the ${size.name} fit", e)
            } finally {
                edt { PlannerWindows.close(); WdpFocus.on = false }
                d.close()
            }
        }
        edt { s.page = WdpPage.DATACARD.name }
    }

    /**
     * The Attack tab's rail (`AttackRail`): by mouse at two PC windows, and by finger on a tablet both ways up and on a
     * phone held upright. On each attack page the three buttons are drawn, inside the Planner and under the toolbar,
     * clear of every control of the page (the rail sits in room the page leaves, or takes its own width), a finger
     * high by finger; a press on each turns the page. Pictures `shell/rail-<size>-<page>.png`.
     */
    private fun rail(r: Report, dir: File, data: MissionData, theaterName: String?) {
        val s = WdpSession
        class RailSize(val size: Size, val finger: Boolean)
        val sizes = listOf(
            RailSize(Size("rail-1600x900", 1600, 900, 1f), false), RailSize(Size("rail-1280x800", 1280, 800, 1f), false),
            RailSize(Size("rail-1138x711", 1138, 711, 2f), true), RailSize(Size("rail-711x1138", 711, 1138, 2f), true),
            RailSize(Size("rail-412x915", 412, 915, 2.625f), true),
        )
        r.head("the Attack tab's rail beside the page, at ${sizes.size} sizes")
        val touchWas = com.bmscompanion.app.data.Platform.touchFirst
        for (rs in sizes) {
            val size = rs.size
            com.bmscompanion.app.data.Platform.touchFirst = rs.finger
            edt { WdpFocus.on = false; WdpFocus.auto = false; s.page = WdpPage.TOSS.name }
            val d = Probe(size) { WdpPlanner(data, theaterName, Modifier.fillMaxSize()) }
            try {
                d.settle(30, 40)
                dismissAll()
                edt { PlannerWindows.close() }
                d.settle(4)
                var turned = 0
                for (p in AttackTabs.PAGES) {
                    val key = "planner/Shell/Attack/${p.name}"
                    if (d.rect(key) == null) { r.fail("${size.name}: no ${p.label} button on the rail"); continue }
                    d.clickKey(key)
                    d.settle(20, 30)
                    if (edt { s.page } == p.name) turned++
                    val rail = d.rect("planner/Shell/Attack")
                    val buttons = AttackTabs.PAGES.mapNotNull { d.rect("planner/Shell/Attack/${it.name}") }
                    val controls = edt { WdpProbe.rects.filterKeys { it.startsWith(p.form + "/") }.values.toList() }
                    val page = controls.reduceOrNull { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
                    val shellBottom = edt {
                        WdpProbe.rects.filterKeys { k -> k.startsWith("planner/Shell/") && !k.startsWith("planner/Shell/Attack") && k != "planner/Shell/MissionShows" && k != "planner/Shell/Credit" && k != "planner/Shell/TurnHint" && !k.startsWith("planner/Shell/Notice") }
                            .values.maxOfOrNull { it.bottom }
                    } ?: 0f
                    // (the mouse off the rail first, so the picture shows the rail and not its tooltip)
                    d.hover(Offset(size.w - 2f, size.h - 2f)); d.settle(3)
                    d.png(File(dir, "${size.name}-${p.name.lowercase()}.png"))
                    if (rail == null || page == null) { r.fail("${size.name} ${p.label}: rail ${rail != null}, page drawn ${page != null}"); continue }
                    val inside = rail.left >= -0.5f && rail.right <= size.w + 0.5f && rail.top >= shellBottom - 0.5f && rail.bottom <= size.h + 0.5f
                    val clear = !(rail.overlaps(page) && rail.intersect(page).let { it.width > 1f && it.height > 1f })
                    val minH = buttons.minOfOrNull { it.height / size.density } ?: 0f
                    val scale = edt { WdpProbe.scales[p.form] }?.let { it / size.density }
                    r.check(inside && clear && buttons.size == 3 && (!rs.finger || minH >= 44f),
                        "${size.name} (${if (rs.finger) "finger" else "mouse"}) ${p.label}: the rail's three buttons beside the page, clear of it " +
                            "(page at ${scale?.let { "%.3f".format(it) }} dp a designer pixel, buttons ${"%.0f".format(minH)} dp high)",
                        { "inside $inside (rail $rail, shell ends ${shellBottom}), clear of the page $clear (page $page), ${buttons.size} buttons, ${"%.0f".format(minH)} dp" })
                }
                r.check(turned == AttackTabs.PAGES.size, "${size.name}: each rail button turns to its page ($turned of ${AttackTabs.PAGES.size})")
                edt { s.page = WdpPage.DATACARD.name }
                d.settle(6)
                r.check(d.rect("planner/Shell/Attack") == null, "${size.name}: no rail beside the DataCard")
            } catch (e: Throwable) {
                threw(r, "the ${size.name} rail", e)
            } finally {
                dismissAll()
                edt { PlannerWindows.close(); WdpFocus.on = false; WdpFocus.auto = false }
                d.close()
                com.bmscompanion.app.data.Platform.touchFirst = touchWas
            }
        }
        edt { s.page = WdpPage.DATACARD.name }
    }

    /**
     * The Planner with nothing to plan yet: no briefing printed, no cartridge read, no save opened (a PC that has never
     * pressed PRINT, or a pilot who planned only from saves). The card pages say where to start, and both ways in work.
     */
    private fun empty(r: Report, dir: File, theaterName: String?) {
        val s = WdpSession
        for (size in listOf(PC, SIZES.first())) {
            r.head("nothing to plan yet (no briefing, no cartridge, no save) at ${size.name} (${size.wDp}×${size.hDp} dp)")
            edt { WdpFocus.on = false; s.page = WdpPage.DATACARD.name; PlannerWindows.close() }
            val d = Probe(size) { WdpPlanner(MissionData(), theaterName, Modifier.fillMaxSize()) }
            try {
                d.settle(30, 40)
                d.png(File(dir, "${size.name}-empty.png"))
                val start = d.rect("planner/Shell/StartHere")
                r.check(start != null && start.left >= 0f && start.right <= size.w + 0.5f && start.bottom <= size.h + 0.5f,
                    "the card says where to start (\"Start here\", inside the screen)", { "rect $start" })
                r.check(d.rect("planner/Shell/StartHere/Open") == null && d.rect("planner/Shell/StartHere/Guide") == null,
                    "\"Start here\" has no buttons of its own (Open mission… is the toolbar's, the rest the Steps')")
                val steps = com.bmscompanion.app.ui.screens.wdp.PlannerSteps
                val phone = size.wDp < 600
                val panelWas = edt { steps.open }
                edt { steps.sheet = false; if (!phone) steps.choosePanel(false) }
                d.settle(4)
                // nothing open: the identity strip (row B; on a phone the row's chip) shows the Steps
                d.clickKey("planner/Shell/Identity", settleAfter = false)
                d.settle(4)
                r.check(d.rect(if (phone) "planner/Steps/Sheet" else "planner/Steps/Panel") != null && edt { PlannerWindows.open } == null,
                    "the identity strip with nothing open shows the Steps (not a second Open mission…)", { "window ${edt { PlannerWindows.open }}" })
                d.png(File(dir, "${size.name}-empty-steps.png"))
                // the way in: the toolbar's Open mission… (a phone's ⋮), and the Steps' Full guide
                if (phone) { d.clickKey("planner/Steps/Close"); d.settle(2); d.clickKey("planner/Shell/More"); d.clickKey("planner/Shell/More/OpenMission", settleAfter = false) }
                else d.clickKey("planner/Shell/OpenMission", settleAfter = false)
                d.settle(4)
                r.check(edt { PlannerWindows.open } == PlannerWindow.OPEN_MISSION, "Open mission… (${if (phone) "⋮" else "the toolbar"}) opens the file list", { "opened ${edt { PlannerWindows.open }}" })
                d.png(File(dir, "${size.name}-empty-open.png"))
                edt { PlannerWindows.close() }
                d.settle(4)
                if (phone) { d.clickKey("planner/Shell/Steps"); d.settle(3) }
                d.clickKey("planner/Steps/FullGuide", settleAfter = false)
                d.settle(4)
                r.check(edt { PlannerWindows.open } == PlannerWindow.GUIDE && edt { PlannerWindows.arg } == "start", "Steps → Full guide opens the guide at its start",
                    { "opened ${edt { PlannerWindows.open }} at ${edt { PlannerWindows.arg }}" })
                edt { PlannerWindows.close(); steps.sheet = false; if (!phone) steps.choosePanel(panelWas) }
                for (p in listOf(WdpPage.DTC, WdpPage.TOSS)) {
                    edt { s.page = p.name }
                    d.settle(20, 30)
                    r.check(edt { WdpProbe.scales[p.form] }?.let { it > 0f } == true, "${p.label}: still drawn whole with nothing loaded (nothing thrown)")
                }
                d.png(File(dir, "${size.name}-empty-toss.png"))
            } catch (e: Throwable) {
                threw(r, "the empty ${size.name} run", e)
            } finally {
                dismissAll()
                edt { PlannerWindows.close(); WdpFocus.on = false; s.page = WdpPage.DATACARD.name }
                d.close()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the scene

    /** A headless scene driven like the PC window: frames, the probe's rectangles, mouse presses, pictures. */
    private class Probe(val size: Size, content: @Composable () -> Unit) {
        private val scene = edt { ImageComposeScene(size.w, size.h, Density(size.density)) { content() } }
        private var nanos = 0L
        private var ms = 1_000L

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

        /** The mouse moved to [at] with no button down (a tooltip's rest). */
        fun hover(at: Offset) {
            ms += 50
            edt { scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms) }
            settle(1)
        }

        /** Presses the control the probe knows as [key] at its centre; a missing control is a failure of the step. */
        fun clickKey(key: String, settleAfter: Boolean = true) {
            val at = rect(key) ?: error("'$key' is not on screen")
            click(at.center, settleAfter)
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
