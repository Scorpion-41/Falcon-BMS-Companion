package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.BriefingStatus
import com.bmscompanion.app.data.mission.EzRun
import com.bmscompanion.app.data.mission.EzStatus
import com.bmscompanion.app.data.mission.KbFileResult
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionSourceInfo
import com.bmscompanion.app.data.mission.NoteBlock
import com.bmscompanion.app.data.mission.NoteRow
import com.bmscompanion.app.data.mission.Noteboard
import com.bmscompanion.app.data.mission.Noteboards
import com.bmscompanion.app.data.mission.NotesDoc
import com.bmscompanion.app.data.mission.NotesState
import com.bmscompanion.app.data.mission.BoardSlot
import com.bmscompanion.app.ui.board.BoardKind
import com.bmscompanion.app.ui.screens.mission.MissionEnv
import com.bmscompanion.app.ui.screens.mission.UpdKneeboardCard
import com.bmscompanion.app.ui.screens.mission.UpdKneeboardGate
import com.bmscompanion.app.ui.screens.wdp.KbPageKind
import com.bmscompanion.app.ui.screens.wdp.KneeboardExtraPages
import com.bmscompanion.app.ui.screens.wdp.KneeboardNotes
import com.bmscompanion.app.ui.screens.wdp.KneeboardEzSource
import com.bmscompanion.app.data.mission.KbOwner
import com.bmscompanion.app.ui.screens.wdp.KneeboardTargets
import com.bmscompanion.app.ui.screens.wdp.KneeboardPageHost
import com.bmscompanion.app.ui.screens.wdp.KneeboardPages
import com.bmscompanion.app.ui.screens.wdp.KneeboardPrintSession
import com.bmscompanion.app.ui.screens.wdp.PlannerWindow
import com.bmscompanion.app.ui.screens.wdp.PlannerWindowHost
import com.bmscompanion.app.ui.screens.wdp.PlannerWindows
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.theme.BmsTheme
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * The Noteboard and Upd Kneeboard from the Kneeboards page (1.4.1), in-process, against a copy of a BMS folder:
 * `--planneroutcome notes <outDir> <copy>` alone, and part 4 of `--planneroutcome printpages`.
 *
 * 1. **The PC keeps the notes** (`/api/notes`, kept in a scratch folder here): nothing written gives the default boards
 *    (laser codes and notes); a change is kept, in the file, and read back as another device would; a mission with
 *    nothing written starts from the newest notes kept with Keep for the next mission.
 * 2. **The pages drawn** (Noteboard 1 and 2, at 1024 x 1536 as the Print window captures them): the laser codes,
 *    figures, notes and comm ladder are on the page, inked.
 * 3. **Printed through the Print window** onto one page file of the copy, both halves, the owners read back.
 * 4. **The editor in the window** (a Noteboard half's Edit notes…): it opens in place of the table, a block is added by
 *    its button, Done closes it; pictures at PC, tablet and phone size.
 * 5. **The Kneeboards page's Upd Kneeboard in EZBoards mode** (with a stand-in EZBoards folder whose CONFIG_USER.BAT
 *    claims page 1 and whose EZBOARDS.BAT does nothing): greyed with its reason before EZBoards has run for the printed
 *    briefing, open after GENERATE NOW; the window then lays the EZBoards-mode Mission set off page 1. The gate's rules
 *    checked on made-up states too.
 * 6. **The VR Notes board**, both noteboards, as the headset shows them.
 *
 * Pictures go to `<outDir>/notes/`. Only the copy's one page file and the scratch notes folder are written.
 */
object NoteboardOutcome {
    /** The Planner's pages, never offered in EZBoards mode (1.4.1). */
    private val PLANNER_KINDS = setOf(
        KbKind.DATACARD_LEFT, KbKind.DATACARD_RIGHT, KbKind.COORDINATION_LEFT, KbKind.COORDINATION_RIGHT, KbKind.BRIEFING,
        KbKind.WEATHER, KbKind.TARGETS_LEFT, KbKind.TARGETS_RIGHT, KbKind.SCHEDULE, KbKind.ATTACK,
    )

    private const val NOTE_TEXT = "PUSH 12:30 - FENCE IN AT STPT 4\nTGT AREA: SA-6 SOUTH OF THE RIVER, STAY ABOVE 15,000 FT\nWINCHESTER: RTB VIA STPT 6, TANKER TEXACO 1 ON 92Y"

    /** `--planneroutcome notes <outDir> <copy>`: starts the bridge on the copy (This PC) and runs every part. */
    fun run(outDir: File, more: List<String>): String {
        val out = StringBuilder()
        val root = more.getOrNull(0)?.let(::File) ?: Bridge.settings.value.BmsDirOverride?.let(::File)
        if (root == null || !root.isDirectory) return "FAIL: give a copy of a BMS folder: --planneroutcome notes <outDir> <copy>\n"
        DevGuard.why(root)?.let { return "FAIL: $it — the check prints into its BMS folder, so it runs on a copy only.\n" }
        val before = Bridge.settings.value
        val wasRunning = Bridge.running
        val wasProbe = WdpProbe.on
        val oldEncode = Platform.encodePng
        var acquired = 0
        var fails = 0
        runCatching { org.jetbrains.skia.Surface.makeRasterN32Premul(1, 1).close() }
        if (Platform.encodePng == null) Platform.encodePng = { img -> com.bmscompanion.desktop.skiaPng(img) }
        WdpProbe.on = true
        try {
            Bridge.update { it.copy(BmsDirOverride = root.path, AutoEzBoardsOnPrint = false, EzBoardsDir = null) }
            Bridge.startForCheck()
            MissionLink.useThisPc()
            MissionLink.acquire(); acquired++
            fails += checks(root, outDir, File(outDir, "notes").apply { mkdirs() }) { out.appendLine(it) }
        } catch (e: Throwable) {
            fails++
            out.appendLine("FAIL: threw ${e::class.java.simpleName}: ${e.message}")
            e.stackTrace.take(14).forEach { out.appendLine("    at $it") }
        } finally {
            PlannerWindows.close()
            repeat(acquired) { runCatching { MissionLink.release() } }
            Platform.encodePng = oldEncode
            WdpProbe.on = wasProbe
            Bridge.update { before }
            if (!wasRunning) Bridge.stop()
        }
        out.appendLine("")
        out.appendLine(if (fails == 0) "ALL PASS" else "$fails FAIL")
        return out.toString()
    }

    /** Every part, in the bridge already running on [root] with This PC's link. Answers how many checks failed. */
    internal fun checks(root: File, outDir: File, pics: File, line: (String) -> Unit): Int {
        var fails = 0
        fun check(ok: Boolean, what: String, detail: String = "") {
            if (!ok) fails++
            line((if (ok) "PASS " else "FAIL ") + what + if (!ok && detail.isNotEmpty()) " — $detail" else if (detail.isNotEmpty()) " ($detail)" else "")
        }
        val store = File(outDir, "notes-store").apply { deleteRecursively(); mkdirs() }
        val oldFolder = NotesStore.folderOverride
        NotesStore.folderOverride = store
        NotesStore.reset()
        Noteboards.forget()
        val oldEz = Bridge.settings.value.EzBoardsDir
        val json = Json { ignoreUnknownKeys = true }
        try {
            // ------------------------------------------------------------ 1. the PC keeps the notes
            line("== Noteboard 1. The PC keeps the notes (/api/notes)")
            runBlocking { Noteboards.load() }
            val s0 = Noteboards.state
            val printed = Bridge.currentBriefing() != null
            check(s0 != null, "GET /api/notes answers", Noteboards.error ?: "")
            check(!printed || (s0?.key?.isNotBlank() == true && s0.mission != null), "the notes are kept for the printed flight (${s0?.mission})", "key '${s0?.key}'")
            check(s0 != null && s0.doc == null && !s0.carried,"nothing written yet: the PC has no notes for it")
            check(Noteboards.board(0).blocks.map { it.type } == listOf(NoteBlock.LASER, NoteBlock.TEXT) && Noteboards.board(1).blocks.map { it.type } == listOf(NoteBlock.TEXT),
                "the default boards: Noteboard 1 laser codes and notes, Noteboard 2 notes", Noteboards.current().boards.joinToString { b -> b.blocks.joinToString("+") { it.type } })
            Noteboards.updateBlock(0, "laser") { it.copy(rows = listOf(NoteRow("TGP", "1511"), NoteRow("LST", "1688"), NoteRow("#2", "1512"), NoteRow("Buddy", ""))) }
            Noteboards.updateBlock(0, "notes") { it.copy(text = NOTE_TEXT) }
            Noteboards.addBlock(0, NoteBlock.FIGURES)
            val fig = Noteboards.board(0).blocks.last().id
            Noteboards.updateBlock(0, fig) { it.copy(rows = listOf(NoteRow("Joker", "4,000"), NoteRow("Bingo", "2,500"), NoteRow("Push", "12:30"))) }
            Noteboards.addBlock(0, NoteBlock.COMMS)
            Noteboards.updateBoard(1) { it.copy(title = "Checklist") }
            Noteboards.updateBlock(1, "notes") { it.copy(text = "FENCE IN\nMASTER ARM - ARM\nLASER ARM - ARM\nTGP - A-G, CODE 1511\nCHAFF/FLARE - ON\n\nFENCE OUT\nMASTER ARM - SAFE\nSQUAWK - CHECK") }
            check(Noteboards.dirty, "a change is waiting to be kept")
            runBlocking { Noteboards.flush() }
            check(!Noteboards.dirty && Noteboards.error == null, "every change kept on the PC", Noteboards.error ?: "")
            val file = File(store, NotesStore.FILE_NAME)
            check(file.isFile && file.readText().contains("1511") && file.readText().contains("FENCE IN AT STPT 4"), "the notes are in ${file.name}, in the settings folder (never the BMS folder)")
            // read back as another device does: the PC's memory dropped, the file read again
            NotesStore.reset()
            val back = runCatching { json.decodeFromString(NotesState.serializer(), Bridge.handle(ApiRequest("GET", "/api/notes")).body.decodeToString()) }.getOrNull()
            val b0 = back?.doc?.boards?.getOrNull(0)
            check(b0 != null && b0.blocks.any { it.type == NoteBlock.LASER && it.rows.any { r -> r.value == "1511" } } && b0.blocks.any { it.text == NOTE_TEXT } &&
                back.doc?.boards?.getOrNull(1)?.title == "Checklist", "read back from the file: the laser codes, the notes and Noteboard 2's title")
            // a mission with nothing written starts from the newest notes kept for the next mission
            val kept = file.readText()
            // (another flight of the same install: kept notes carry over within one install only)
            val carry = NotesDoc("zz|another flight|1" + NotesStore.installTag(),listOf(Noteboard("", listOf(NoteBlock("n", NoteBlock.TEXT, text = "CARRIED OVER")))), keep = true, modified = System.currentTimeMillis())
            file.writeText("""{"version":1,"missions":[${json.encodeToString(NotesDoc.serializer(), carry)}]}""")
            NotesStore.reset()
            val c = NotesStore.state()
            check(c.carried && c.doc?.boards?.firstOrNull()?.blocks?.firstOrNull()?.text == "CARRIED OVER" && c.doc?.key == c.key,
                "a mission with nothing written starts from the notes kept with Keep for the next mission", "carried ${c.carried}")
            file.writeText(kept)
            NotesStore.reset()
            check(!NotesStore.state().carried, "its own notes again once they are back")
            check(Bridge.handle(ApiRequest("POST", "/api/notes", body = "not json".toByteArray())).status == 400, "POST of something that is not noteboards is refused")
            line("")

            // ------------------------------------------------------------ 2. the pages drawn
            line("== Noteboard 2. The pages drawn at 1024 x 1536, as the Print window captures them")
            check(KneeboardPages.of(KbKind.NOTES) != null && KneeboardPages.of(KbKind.NOTES_2) != null, "the Print window offers Noteboard 1 and Noteboard 2",
                KneeboardPages.kinds.take(6).joinToString { it.id })
            // the comm ladder block draws from the mission the Mission section shows: wait for the link to bring it
            val t0 = System.currentTimeMillis()
            while (MissionLink.mission.value?.briefing == null && printed && System.currentTimeMillis() - t0 < 15_000) Thread.sleep(100)
            val spike = Spike()
            try {
                for (kind in listOf(KbKind.NOTES, KbKind.NOTES_2)) {
                    val png = spike.capture(KneeboardPages.of(kind)!!)
                    val img = png?.let { ImageIO.read(ByteArrayInputStream(it)) }
                    val said = KneeboardExtraPages.drawn[kind].orEmpty()
                    check(img != null && img.width == 1024 && img.height == 1536, "$kind: drawn at 1024 x 1536", spike.error ?: "${img?.width}x${img?.height}")
                    line("    the page: $said")
                    if (png == null || img == null) continue
                    File(pics, "page-$kind.png").writeBytes(png)
                    val dark = count(img) { r, g, b -> maxOf(r, g, b) < 100 }
                    if (kind == KbKind.NOTES) {
                        check(said.contains("laser TGP 1511, LST 1688, #2 1512") && !said.contains("Buddy"), "notes: the three laser codes typed, the empty row left off", said)
                        check(said.contains("figures Joker 4,000, Bingo 2,500, Push 12:30") && said.contains("notes ${NOTE_TEXT.length} characters"), "notes: the figures and the notes", said)
                        val rows = KneeboardExtraPages.rows[kind].orEmpty()
                        check(rows.contains("TGP | 1511") && rows.contains("Joker | 4,000"), "notes: the rows as drawn", rows.take(5).joinToString(" / "))
                        val comms = Bridge.currentBriefing()?.comms?.count { it.agency.isNotBlank() } ?: 0
                        check(comms == 0 || said.contains("comm ladder"), "notes: the briefing's comm ladder ($comms rows in the briefing)", said)
                        // the laser codes are 20-unit digits near the top: a band of heavy ink there
                        val top = countIn(img, 0.06, 0.22) { r, g, b -> maxOf(r, g, b) < 100 }
                        // (measured: about 1,100 in that band for three codes, 4,000 on the page without the comm ladder)
                        check(top > 600, "notes: the laser codes are inked at the top of the page ($top dark pixels)", "$top")
                        check(dark > 3000, "notes: the page is inked ($dark dark pixels in a quarter sample)", "$dark")
                    } else {
                        check(said.contains("notes ") && dark > 1200, "notes-2: the checklist is on the page ($dark dark pixels)", said)
                    }
                }
            } finally { spike.close() }
            line("")

            // ------------------------------------------------------------ 3. printed through the window
            line("== Noteboard 3. Printed through the Print window (This PC, the bridge on the copy)")
            val s = KneeboardPrintSession
            PlannerWindows.show(PlannerWindow.PRINT)
            val stage = Stage(1280, 800)
            try {
                check(stage.until(20_000) { MissionLink.state.value is LinkState.Online && s.state != null && !s.loading }, "the window reads the pages", "${MissionLink.state.value}")
                // the pages read again for the mode the PC is in now (a part before may have left them read in another
                // mode, and the plan is the mode's own: one set before the window catches up would be put aside with it)
                stage.onUi { runBlocking { s.load() } }
                stage.frames(10, 30)
                val n = (12 downTo 2).firstOrNull { s.printable(s.slot(it)) }
                check(n != null, "a page to print into", s.state?.pages?.joinToString { "${it.n}:${it.problem ?: if (it.missing) "missing" else "ok"}" } ?: "")
                if (n != null) {
                    stage.onUi { s.leaveAll(); s.set(n, 'L', KbKind.NOTES); s.set(n, 'R', KbKind.NOTES_2); s.selN = n; s.selSide = 'L'; s.results = emptyList() }
                    stage.frames(20, 30)
                    stage.shot(File(pics, "window-planned.png"))
                    check(WdpProbe.rects.containsKey("planner/Print/EditNotes"), "the preview of a Noteboard half offers Edit notes…")
                    check(stage.click("Print/Print"), "Print is pressed")
                    val done = stage.until(120_000) { !s.busy && s.results.isNotEmpty() && s.progress == null }
                    val res = s.results
                    res.forEach { line("  ${it.file}: ${it.status}${it.reason?.let { r -> " — $r" } ?: ""}") }
                    // (unchanged when an earlier run printed the same notes into the same copy)
                    check(done && res.size == 1 && res[0].status in setOf(KbFileResult.WRITTEN, KbFileResult.UNCHANGED), "page $n written, both halves", res.joinToString { "${it.file} ${it.status}" })
                    val slot = s.slot(n)
                    check(slot?.leftKind == KbKind.NOTES && slot.rightKind == KbKind.NOTES_2, "the owners read back: Noteboard 1 left, Noteboard 2 right", "${slot?.leftKind}/${slot?.rightKind}")
                    KneeboardPrint.place(root, Bridge.install.theater).page(n)?.let { f ->
                        val bytes = f.readBytes()
                        KneeboardPrint.halfImage(bytes, 0, 512)?.let { ImageIO.write(it, "png", File(pics, "as-seen-notes.png")) }
                        KneeboardPrint.halfImage(bytes, 1, 512)?.let { ImageIO.write(it, "png", File(pics, "as-seen-notes-2.png")) }
                    }

                    // -------------------------------------------------------- 4. the editor in the window
                    line("")
                    line("== Noteboard 4. The editor, in place of the page table (Edit notes…)")
                    check(stage.click("Print/EditNotes"), "Edit notes… is pressed")
                    stage.frames(10, 30)
                    check(KneeboardNotes.editing?.board == 0 && WdpProbe.rects.containsKey("planner/Print/Notes"), "the editor opens on Noteboard 1")
                    val blocks = Noteboards.board(0).blocks.size
                    check(stage.click("Print/Notes/Add/steerpoints"), "+ Steerpoints is pressed")
                    stage.frames(10, 30)
                    check(Noteboards.board(0).blocks.size == blocks + 1 && Noteboards.board(0).blocks.last().type == NoteBlock.STEERPOINTS, "a steerpoint block is added",
                        "${Noteboards.board(0).blocks.size} blocks")
                    stage.frames(20, 30)
                    stage.shot(File(pics, "editor-pc.png"))
                    check(stage.click("Print/Notes/Done"), "Done is pressed")
                    check(KneeboardNotes.editing == null, "Done closes the editor, the table is back")
                    runBlocking { Noteboards.flush() }
                }
            } finally { stage.close() }
            for ((name, w, h, d) in listOf(Quad("editor-tablet", 1000, 700, 1.5f), Quad("editor-phone", 400, 820, 2.5f))) {
                val st = Stage(w, h, d)
                try {
                    st.onUi { KneeboardNotes.editing = KneeboardNotes.Editing(0, 0, 'L') }
                    st.frames(30, 30)
                    st.shot(File(pics, "$name.png"))
                    check(WdpProbe.rects.containsKey("planner/Print/Notes"), "$name: the editor shows")
                } finally { st.onUi { KneeboardNotes.editing = null }; st.close() }
            }
            PlannerWindows.close()
            line("")

            // ------------------------------------------------------------ 5. EZBoards mode, from the Kneeboards page
            line("== Noteboard 5. Upd Kneeboard from the Kneeboards page in EZBoards mode")
            fun info(configured: Boolean = true, printedAt: Long = 1000, run: EzRun? = null, running: Boolean = false, auto: Boolean = true, wdp: Boolean = false) =
                BridgeInfo(
                    briefing = BriefingStatus(available = printedAt > 0, modified = printedAt),
                    ezBoards = EzStatus(configured = configured, autoOnPrint = auto, running = running, lastRun = run),
                    mission = MissionSourceInfo(mode = if (wdp) com.bmscompanion.app.data.mission.MissionMode.WDP else com.bmscompanion.app.data.mission.MissionMode.EZBOARDS),
                )
            val g = UpdKneeboardGate
            check(g.reason(info(run = null), true) != null, "gate: EZBoards set up, PRINT seen, no run yet: greyed", g.reason(info(), true) ?: "")
            check(g.reason(info(run = EzRun(time = 500, ok = true)), true) != null, "gate: EZBoards' last run was for an earlier PRINT: greyed")
            check(g.reason(info(run = EzRun(time = 2000, ok = true)), true) == null, "gate: EZBoards ran after this PRINT: open")
            check(g.reason(info(run = EzRun(time = 2000, ok = false)), true) != null, "gate: EZBoards' run failed: greyed, GENERATE NOW said")
            check(g.reason(info(run = EzRun(time = 2000, ok = true), running = true), true) != null, "gate: EZBoards running: greyed")
            check(g.reason(info(configured = false), true) == null, "gate: EZBoards not set up: open, nothing to wait for")
            check(g.reason(info(wdp = true), true) == null, "gate: WDP mode: open, as in the Planner")
            check(g.reason(info(printedAt = 0, run = null), true)?.contains("PRINT") == true, "gate: no briefing printed: greyed, PRINT said")
            check(g.reason(info(run = EzRun(time = 2000, ok = true)), false) != null, "gate: no link: greyed")

            // a stand-in EZBoards: its config claims page 1 both knees, its EZBOARDS.BAT writes page 1 of the copy as
            // EZBoards does (the file with ImageMagick's mark in its header, which is how the owner is read) and succeeds
            val ez = File(outDir, "ezboards-standin").apply { deleteRecursively(); mkdirs() }
            val page1 = KneeboardPrint.place(root, Bridge.install.theater).page(1)
            val page1Bytes = page1?.readBytes()
            if (page1 != null && page1Bytes != null && page1Bytes.size > 128) {
                val made = page1Bytes.copyOf()
                for (i in 32 until 76) made[i] = 0
                "IMAGEMAGICK".toByteArray(Charsets.US_ASCII).copyInto(made, 32)
                File(ez, "page1.dds").writeBytes(made)
                File(ez, "EZBOARDS.BAT").writeText("@echo off\r\ncopy /Y \"${File(ez, "page1.dds").path}\" \"${page1.path}\" >nul\r\nexit /b 0\r\n")
            } else File(ez, "EZBOARDS.BAT").writeText("@echo off\r\nexit /b 0\r\n")
            File(ez, "CONFIG_USER.BAT").writeText("@echo off\r\nSET KNEEBOARD[F16_1L]=BRIEFING\r\nSET KNEEBOARD[F16_1R]=COMMS\r\n")
            Bridge.update(reapply = false) { it.copy(EzBoardsDir = ez.path, AutoEzBoardsOnPrint = false) }
            val card = CardStage(1100, 900)
            try {
                card.until(15_000) { MissionLink.info.value?.ezBoards?.configured == true }
                card.frames(10, 30)
                val inf = MissionLink.info.value
                line("  the PC: EZBoards ${if (inf?.ezBoards?.configured == true) "set up (stand-in)" else "not set up"}, briefing ${if (inf?.briefing?.available == true) "printed" else "not printed"}, last run ${inf?.ezBoards?.lastRun?.let { "${it.ok} at ${it.time}" } ?: "none"}")
                val greyed = WdpProbe.rects.containsKey("planner/Kneeboards/UpdWhy")
                check(greyed, "before EZBoards has run for this PRINT: Upd Kneeboard is greyed with its reason", UpdKneeboardGate.reason(inf, true, null) ?: "no reason")
                card.shot(File(pics, "kneeboards-greyed.png"))
                card.click("Kneeboards/UpdKneeboard")
                check(PlannerWindows.open == null, "a press while greyed opens nothing")
                // GENERATE NOW, as the page's button sends it: the stand-in runs and finishes
                val gen = Bridge.handle(ApiRequest("POST", "/api/ezboards/generate"))
                check(gen.status == 200, "GENERATE NOW runs EZBoards (the stand-in)", gen.body.decodeToString().take(200))
                check(card.until(15_000) { MissionLink.info.value?.ezBoards?.lastRun?.ok == true && !WdpProbe.rects.containsKey("planner/Kneeboards/UpdWhy") },
                    "after it: Upd Kneeboard is open", UpdKneeboardGate.reason(MissionLink.info.value, true, null) ?: "")
                card.shot(File(pics, "kneeboards-open.png"))
                card.onUi { KneeboardPrintSession.forget() }
                check(card.click("Kneeboards/UpdKneeboard") && PlannerWindows.open == PlannerWindow.PRINT, "a press opens Upd Kneeboard over the Kneeboards page", "${PlannerWindows.open}")
                check(card.until(20_000) { s.state != null && !s.loading && s.planned }, "the window reads the pages and lays its EZBoards-mode default")
                card.frames(20, 30)
                card.shot(File(pics, "kneeboards-updkneeboard.png"))
                card.shot(File(pics, "ez-window-pc.png"))
                val st = s.state
                val p1 = st?.pages?.firstOrNull { it.n == 1 }
                check(p1?.let { it.ezLeft && it.ezRight } == true, "page 1 is marked EZBoards' (its CONFIG_USER.BAT)")
                // EZBoards' halves are EZBoards' pages: written by it (the file says so), never a kind of ours
                check(page1Bytes == null || (p1?.left == KbOwner.EZBOARDS && p1.right == KbOwner.EZBOARDS),
                    "after GENERATE NOW page 1's halves are EZBoards' own pages, read from the file", "${p1?.left}/${p1?.right}")
                check(s.kind(1, 'L') == KbKind.LEAVE && s.kind(1, 'R') == KbKind.LEAVE && s.ezClaimed(1, 'L') && s.ezClaimed(1, 'R'),
                    "page 1 left and right are EZBoards' page, nothing of ours planned on them")
                card.click("Print/Pick/1L")
                check(!WdpProbe.rects.keys.any { it.startsWith("planner/Print/Kind/") }, "page 1's halves open no kind menu")
                val first = s.defaultPages(st)
                check(first.isNotEmpty() && 1 !in first, "EZBoards mode's default leaves page 1 to EZBoards (pages ${first.joinToString()})")
                val p = first.firstOrNull()
                // beside it the target pictures where the PC can draw a target of the mission, else left as it is
                val drawable = KneeboardTargets.set?.let { KneeboardTargets.entries(it).isNotEmpty() } == true
                val right = if (drawable) KbKind.TARGET_PICTURES else KbKind.LEAVE
                check(p != null && s.kind(p, 'L') == KbKind.NOTES && s.kind(p, 'R') == right,
                    "page $p: Noteboard 1" + (if (drawable) " and the target pictures" else " (its right half left as it is: no target the PC can draw)"), p?.let { "${s.kind(it, 'L')}/${s.kind(it, 'R')}" } ?: "")
                first.getOrNull(1)?.let { q -> check(s.kind(q, 'L') == KbKind.ROUTE_MAP && s.kind(q, 'R') == KbKind.DEPARTURE, "page $q: the route map and the departure chart", "${s.kind(q, 'L')}/${s.kind(q, 'R')}") }
                // the kind menu offers no page of the Planner's (1.4.1), the window's own kinds only
                if (p != null) {
                    card.click("Print/Pick/${p}L")
                    val offeredNow = WdpProbe.rects.keys.filter { it.startsWith("planner/Print/Kind/") }.map { it.substringAfterLast('/') }
                    check(offeredNow.isNotEmpty() && offeredNow.none { it in PLANNER_KINDS } && KbKind.NOTES in offeredNow,
                        "EZBoards mode's kind menu offers no page of the Planner's: ${offeredNow.joinToString()}")
                    card.click("Print/Preview"); card.frames(6)
                }
                check(PLANNER_KINDS.none { s.offered(it) } && PLANNER_KINDS.all { s.unavailable(it) != null }, "no page of the Planner's is offered or printed in EZBoards mode")
                // nothing of the Planner's is filled in EZBoards mode; the pages read the Mission section's own mission
                check(card.until(20_000) { KneeboardEzSource.mission != null }, "the route map and charts read the Mission section's own mission (KneeboardEzSource)",
                    "${KneeboardEzSource.mission?.briefing?.overview?.flight}")
                // a plan of WDP mode's (the Mission set, the Planner's pages on pages 1-3), then EZBoards mode again
                val ezPlan = s.plan.toMap()
                Bridge.update(reapply = false) { it.copy(MissionSource = com.bmscompanion.app.data.mission.MissionMode.WDP) }
                card.onUi { runBlocking { s.load() } }
                val wdpPlan = s.plan.toMap()
                check(s.kind(1, 'L') == KbKind.BRIEFING && s.kind(1, 'R') == KbKind.WEATHER, "WDP mode: its own plan, the Mission set from page 1", "$wdpPlan")
                Bridge.update(reapply = false) { it.copy(MissionSource = com.bmscompanion.app.data.mission.MissionMode.EZBOARDS) }
                card.onUi { runBlocking { s.load() } }
                card.frames(20, 30)
                val back = s.state?.pages?.firstOrNull { it.n == 1 }
                check(s.plan.toMap() == ezPlan && s.kind(1, 'L') == KbKind.LEAVE && s.kind(1, 'R') == KbKind.LEAVE &&
                    (page1Bytes == null || (back?.left == KbOwner.EZBOARDS && back.right == KbOwner.EZBOARDS)) &&
                    (1..16).none { n -> listOf('L', 'R').any { side -> s.kind(n, side) in PLANNER_KINDS } },
                    "back in EZBoards mode after a WDP-mode plan: EZBoards mode's own plan, page 1 EZBoards' pages, no page of the Planner's", "${s.plan}")
                card.shot(File(pics, "ez-window-after-wdp.png"))
                Bridge.update(reapply = false) { it.copy(MissionSource = com.bmscompanion.app.data.mission.MissionMode.WDP) }
                card.onUi { runBlocking { s.load() } }
                check(s.plan.toMap() == wdpPlan, "back in WDP mode: the WDP-mode plan comes back as it was", "${s.plan}")
                Bridge.update(reapply = false) { it.copy(MissionSource = com.bmscompanion.app.data.mission.MissionMode.EZBOARDS) }
                card.onUi { runBlocking { s.load() } }
                // printed in EZBoards mode: nothing on page 1
                if (page1 != null) {
                    val before1 = page1.readBytes()
                    card.onUi { s.results = emptyList() }
                    check(card.click("Print/Print"), "Print is pressed in EZBoards mode")
                    val done = card.until(180_000) { !s.busy && s.results.isNotEmpty() && s.progress == null }
                    line("  printed: " + s.results.joinToString { "${it.file} ${it.status}" })
                    check(done && s.results.none { it.file.startsWith(KneeboardPrint.pageName(1)) } && page1.readBytes().contentEquals(before1),
                        "the print leaves page 1 (EZBoards') exactly as it was", s.results.joinToString { "${it.file} ${it.status}" })
                    // EZBoards runs again while the window is open: the pages are read again by themselves
                    val stamp0 = s.stamp
                    Bridge.handle(ApiRequest("POST", "/api/ezboards/generate"))
                    check(card.until(15_000) { s.stamp > stamp0 && !s.loading }, "EZBoards ran with the window open: the pages are read again (owners and pictures)")
                    check(s.state?.pages?.firstOrNull { it.n == 1 }?.left == KbOwner.EZBOARDS, "and page 1 is EZBoards' page")
                }
                card.onUi { s.selN = first.firstOrNull() ?: 2; s.selSide = 'L' }
                card.frames(20, 30)
                card.shot(File(pics, "ez-window-pc-printed.png"))
            } finally { card.close() }
            // the window in EZBoards mode on a tablet and a phone
            for ((name, w, h, d) in listOf(Quad("ez-window-tablet", 1000, 740, 1.5f), Quad("ez-window-phone", 400, 820, 2.5f))) {
                val st2 = Stage(w, h, d)
                try {
                    st2.onUi { PlannerWindows.show(PlannerWindow.PRINT) }
                    st2.until(15_000) { s.state != null && !s.loading }
                    st2.frames(30, 30)
                    st2.shot(File(pics, "$name.png"))
                    check(WdpProbe.rects.containsKey("planner/Print/Pick/1L") || WdpProbe.rects.containsKey("planner/Print/Preview"), "$name: the window shows its table")
                } finally { st2.close() }
            }
            PlannerWindows.close()
            KneeboardPrintSession.forget()
            line("")

            // ------------------------------------------------------------ 6. the VR Notes board
            line("== Noteboard 6. The VR Notes board")
            val sb = StringBuilder()
            val env = { nav: androidx.navigation.NavHostController -> MissionEnv(nav, null, null) }
            PlanViewsRender.boards(true)
            try {
                for (page in 0..1) PlanViewsRender.board(pics, "vr-notes-$page", BoardKind.NOTES, BoardSlot(5, BoardKind.NOTES.id, mapOf("board" to "both")), page, env, sb) { what, ok, detail -> check(ok, what, detail) }
            } finally { PlanViewsRender.boards(false) }
            for (page in 0..1) {
                val img = runCatching { ImageIO.read(File(pics, "vr-notes-$page.png")) }.getOrNull()
                // the board's print on its sheet (the paper skin here: dark ink on the aged sheet)
                // inside the sheet only, clear of the tablet's dark rim
                val ink = img?.let { i -> count(i.getSubimage(i.width / 10, i.height / 20, i.width * 8 / 10, i.height * 55 / 100)) { r, g, b -> maxOf(r, g, b) < 90 } } ?: 0
                // (measured: about 1,900 for Noteboard 2's nine lines, 10,000 and more for Noteboard 1)
                check(ink > 1000, "VR board, Noteboard ${page + 1}: the notes are on the sheet ($ink dark pixels)", "$ink")
            }
            check(BoardKind.of("notes") == BoardKind.NOTES && BoardKind.NOTES.configurable, "the VR board kind Notes, configurable (which noteboard)")
        } catch (e: Throwable) {
            fails++
            line("FAIL: the Noteboard check threw ${e::class.java.simpleName}: ${e.message}")
            e.stackTrace.take(12).forEach { line("    at $it") }
        } finally {
            runCatching { Bridge.update(reapply = false) { it.copy(EzBoardsDir = oldEz) } }
            KneeboardNotes.editing = null
            NotesStore.folderOverride = oldFolder
            NotesStore.reset()
            Noteboards.forget()
        }
        return fails
    }

    private data class Quad(val name: String, val w: Int, val h: Int, val d: Float)

    private fun count(img: BufferedImage, test: (Int, Int, Int) -> Boolean): Int = countIn(img, 0.0, 1.0, test)

    private fun countIn(img: BufferedImage, from: Double, to: Double, test: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in (img.height * from).toInt() until (img.height * to).toInt() step 2) for (x in 0 until img.width step 2) {
            val c = img.getRGB(x, y)
            if (test((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)) n++
        }
        return n
    }

    // ---------------------------------------------------------------- scenes

    private open class Scene(val w: Int, val h: Int, val density: Float = 1f) : AutoCloseable {
        private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "notes-ui").apply { isDaemon = true } }
        val ui = exec.asCoroutineDispatcher()
        private var nanos = 0L
        lateinit var scene: ImageComposeScene

        fun <T> onUi(block: () -> T): T = exec.submit(Callable { block() }).get()

        fun start(content: @Composable () -> Unit) {
            scene = onUi { ImageComposeScene((w * density).toInt(), (h * density).toInt(), Density(density), coroutineContext = ui, content = content) }
        }

        fun frame(): org.jetbrains.skia.Image = onUi {
            Snapshot.sendApplyNotifications()
            nanos += 16_666_667L
            scene.render(nanos)
        }

        fun frames(n: Int, sleepMs: Long = 15) = repeat(n) { frame().close(); Thread.sleep(sleepMs) }

        fun until(timeoutMs: Long, cond: () -> Boolean): Boolean {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < timeoutMs) {
                frame().close()
                if (runCatching(cond).getOrDefault(false)) return true
                Thread.sleep(20)
            }
            return false
        }

        fun shot(file: File) {
            frames(4)
            val img = frame()
            img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { file.writeBytes(it) }
            img.close()
        }

        fun click(probe: String): Boolean {
            frames(2)
            val r = WdpProbe.rects["planner/$probe"] ?: return false
            val at = Offset(r.center.x, r.center.y)
            onUi {
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            }
            frame().close()
            onUi { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary) }
            frames(8)
            return true
        }

        override fun close() {
            runCatching { onUi { scene.close() } }
            exec.shutdown()
        }
    }

    /** The Planner's window host over a dark page, in the app's theme. */
    private class Stage(w: Int, h: Int, density: Float = 1f) : Scene(w, h, density) {
        init { start { BmsTheme { Box(Modifier.fillMaxSize().background(Hud.Bg)) { PlannerWindowHost() } } } }
    }

    /** The Kneeboards page's Upd Kneeboard card, with the windows it opens over it. */
    private class CardStage(w: Int, h: Int) : Scene(w, h) {
        init {
            start {
                BmsTheme {
                    Box(Modifier.fillMaxSize().background(Hud.Bg)) {
                        Column(Modifier.padding(12.dp)) { UpdKneeboardCard() }
                        PlannerWindowHost()
                    }
                }
            }
        }
    }

    /** One page host in a small box, recording into a layer: a page drawn and captured the way the Print window does. */
    private class Spike : Scene(260, 360) {
        var kind by mutableStateOf(KneeboardPages.LEAVE)
        var layer: GraphicsLayer? = null
        var scope: CoroutineScope? = null
        var error: String? = null

        init {
            start {
                val l = rememberGraphicsLayer()
                layer = l
                scope = androidx.compose.runtime.rememberCoroutineScope()
                BmsTheme { Box(Modifier.fillMaxSize().background(Hud.Bg)) { KneeboardPageHost(kind, 7, 'L', Modifier.size(200.dp, 300.dp), l) } }
            }
        }

        fun capture(k: KbPageKind): ByteArray? {
            error = null
            onUi { kind = k }
            val job = onUi {
                scope!!.async {
                    KneeboardPages.preload(k.id)
                    KneeboardPages.settle()
                    if (!KneeboardPages.awaitDrawn(layer!!, k.id, 7, 'L')) null else KneeboardPages.capturePng(layer!!)
                }
            }
            until(30_000) { job.isCompleted }
            return if (job.isCompleted) runCatching { runBlocking { job.await() } }.onFailure { error = "${it::class.simpleName}: ${it.message}" }.getOrNull()
            else null.also { error = "not done in 30 s" }
        }
    }
}
