package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.bmscompanion.app.data.mission.KbFileResult
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.mission.KbOwner
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.ui.screens.wdp.KneeboardPageHost
import com.bmscompanion.app.ui.screens.wdp.KneeboardPages
import com.bmscompanion.app.ui.screens.wdp.KneeboardPictures
import com.bmscompanion.app.ui.screens.wdp.KneeboardPrintSession
import com.bmscompanion.app.ui.screens.wdp.PlannerWindow
import com.bmscompanion.app.ui.screens.wdp.PlannerWindowHost
import com.bmscompanion.app.ui.screens.wdp.PlannerWindows
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.screens.wdp.WdpSession
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * `--planneroutcome print <outDir> <a copy of a BMS folder> [briefing.txt] [cartridge.ini]`: the Upd Kneeboard
 * window, in-process, against a copy (R3-PLAN K2).
 *
 * 1. **The capture** (U10): a page laid out at 1024 x 1536 and shown at a fifth of that is captured from its graphics
 *    layer at full size — the test page and a DataCard half, checked pixel by pixel where the test page puts its
 *    circle, and timed.
 * 2. **Not linked**: the window says the PC does it, draws no table, and its Print does nothing.
 * 3. **Linked** (This PC, the bridge running on the copy, EZBoards mode): sixteen pages with their owners, EZBoards' claims from the
 *    copy's `CONFIG_USER.BAT`, and the Mission set on the first two pages EZBoards leaves alone.
 * 4. **Print** through the window's own button: exactly those two files change (SHA of every file in the copy before
 *    and after), each half the window drew is in the file (decoded, compared with what was captured), the owners read
 *    back as BMS Companion with the kind printed, and printing again changes nothing.
 * 5. **A test page** on one more page, through the Test page button, decoded to a picture at the pad's shape.
 * 6. **The link drops**: the window turns into the note and Print does nothing; the link back brings the plan back.
 * 7. **Put BMS's page back** on one of the printed pages: BMS's own page again, by SHA.
 * 8. **Browse picture…**: the Open picture window (answered by `PcFiles.testAnswer`) with WDP's title and types, the
 *    chooser on the page picked, ← Insert left with a `.png` and the half's own Picture… with a `.dds`, its page buttons,
 *    Another picture… and Cancel; a half set to Blank keeping its picture for its Picture… to open again; the plan and
 *    the pictures kept on the device between launches; the print holds each picture stretched over its half, drawn by
 *    the PC from the file (no capture on the device), a 1024 x 2048 picture of one-pixel rules pixel for pixel, the
 *    owner reads back as Picture, and a picture gone by print time leaves its half as it is with the reason.
 * 9. **WDP mode**: the Mission set on pages 1 and 2 whatever EZBoards claims (on pages 2 and 3 in EZBoards mode, page 1
 *    claimed: two `SET KNEEBOARD` lines are added to the copy's EZBoards config for this part when it does not claim
 *    page 1, and taken out after), an untouched EZBoards-mode Mission set laid again when the PC switches, the first
 *    opening in WDP mode, Print writing page 1, and a plan changed by hand kept across a switch.
 * 10. **Every control in the window** pressed, in a window tall enough for the whole kind menu: none may throw, and
 *    each must do what it is for.
 *
 * Pictures go to `<outDir>/print/`. The copy is written (that is the point); nothing else is. With the briefing and the
 * cartridge (default: the copy's `User/Briefings/briefing.txt` and the one cartridge in `User/Config`) the card is
 * filled as the Planner fills it.
 */
object PrintOutcome {
    private const val SCENE_W = 1280
    private const val SCENE_H = 800

    fun run(outDir: File, more: List<String>): String {
        val out = StringBuilder()
        var fails = 0
        fun line(s: String) { out.appendLine(s) }
        fun check(ok: Boolean, what: String, detail: String = "") {
            if (!ok) fails++
            line((if (ok) "PASS " else "FAIL ") + what + if (!ok && detail.isNotEmpty()) " — $detail" else if (detail.isNotEmpty()) " ($detail)" else "")
        }
        val pics = File(outDir, "print").apply { mkdirs() }
        val root = more.getOrNull(0)?.let(::File) ?: Bridge.settings.value.BmsDirOverride?.let(::File)
        if (root == null || !root.isDirectory) return "FAIL: give a copy of a BMS folder: --planneroutcome print <outDir> <copy>\n"
        DevGuard.why(root)?.let { return "FAIL: $it — the print check writes its BMS folder, so it runs on a copy only.\n" }
        val briefing = more.getOrNull(1)?.let(::File) ?: File(root, "User/Briefings/briefing.txt")
        val cartridge = more.getOrNull(2)?.let(::File)
            ?: File(root, "User/Config").listFiles { f -> f.isFile && f.name.endsWith(".ini", true) && !f.name.endsWith("_Def.ini", true) }
                ?.firstOrNull { f -> runCatching { f.readText(Charsets.ISO_8859_1).contains("[STPT]") }.getOrDefault(false) }

        line("Upd Kneeboard, in-process (K2)")
        line("copy: ${root.name}; briefing: ${if (briefing.isFile) "read" else "none"}; cartridge: ${if (cartridge?.isFile == true) "read" else "none"}")
        line("")

        // every PNG the window encodes, kept to compare with what reaches the file
        val captures = CopyOnWriteArrayList<Triple<Int, Int, ByteArray>>()
        val encodeMs = CopyOnWriteArrayList<Long>()
        val oldEncode = Platform.encodePng
        Platform.encodePng = { img ->
            val t0 = System.nanoTime()
            val b = com.bmscompanion.desktop.skiaPng(img)
            encodeMs += (System.nanoTime() - t0) / 1_000_000
            if (b != null) captures += Triple(img.width, img.height, b)
            b
        }
        val before = Bridge.settings.value
        // what this device kept (the plan and the last picture opened), put back at the end: the check forgets both
        val keptKeys = listOf(KneeboardPrintSession.SAVED_KEY, "wdp_file_" + com.bmscompanion.app.ui.screens.wdp.KneeboardPictures.LAST_FILE)
        val kept = keptKeys.associateWith { k -> runCatching { com.bmscompanion.app.data.Repo.getString(k) }.getOrNull() }
        val wasRunning = Bridge.running
        val wasProbe = WdpProbe.on
        var acquired = 0
        WdpProbe.on = true
        WdpProbe.clear()
        try {
            // ---------------------------------------------------------------- 1. the capture
            line("== 1. Capture (U10): a page laid out at 1024 x 1536, shown at a fifth")
            val spike = Spike()
            try {
                for (kind in listOf(KbKind.TEST, KbKind.DATACARD_LEFT)) {
                    val k = KneeboardPages.of(kind)!!
                    val t0 = System.nanoTime()
                    val png = spike.capture(k)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val img = png?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
                    check(img != null && img.width == 1024 && img.height == 1536, "$kind: captured at 1024 x 1536 from a preview ${spike.shownW} x ${spike.shownH} px",
                        "${img?.width}x${img?.height}, ${png?.size ?: 0} bytes, $ms ms with the frames it waited" + (KneeboardPages.lastError?.let { "; $it" } ?: "") + (spike.error?.let { "; $it" } ?: ""))
                    if (png != null) File(pics, "capture-$kind.png").writeBytes(png)
                    if (img != null && kind == KbKind.TEST) {
                        // the circle: radius 150 units = 341 px about the middle, red; the page white between the grid lines
                        val r = (150 * 1024.0 / 450).toInt()
                        val hits = listOf(512 to 768 - r, 512 to 768 + r, 512 - r to 768, 512 + r to 768).count { (x, y) ->
                            (y - 3..y + 3).any { yy -> (x - 3..x + 3).any { xx -> red(img.getRGB(xx, yy)) } }
                        }
                        check(hits == 4, "the test page's circle is where the layout puts it, on all four sides (round at 1024 x 1536)", "$hits of 4")
                        val white = img.getRGB(512 + 20, 768 - 60) and 0xFFFFFF
                        check(white == 0xFFFFFF, "the page is white paper between its lines", "%06x".format(white))
                    }
                    if (img != null && kind == KbKind.DATACARD_LEFT) {
                        val inked = (0 until 1536 step 8).sumOf { y -> (0 until 1024 step 8).count { x -> (img.getRGB(x, y) and 0xFFFFFF) != 0xFFFFFF } }
                        val share = inked * 100 / ((1536 / 8) * (1024 / 8))
                        check(share in 3..95, "the DataCard half has the card on it ($share% of the page inked)", "$share%")
                    }
                }
                line("  preview on screen: ${spike.shownW} x ${spike.shownH} px; PNG encode: ${encodeMs.joinToString("/")} ms")
            } finally { spike.close() }
            line("")

            // the card, filled as the Planner fills it
            val data = WdpFixtureMission.data(briefing.takeIf { it.isFile }, cartridge?.takeIf { it.isFile })
            val theater = kotlinx.coroutines.runBlocking { com.bmscompanion.app.ui.screens.wdp.plannerTheater(com.bmscompanion.app.data.Repo.index().theaters, "Korea KTO") }
            val mission = WdpFixtureMission.mission(data, theater)
            runCatching { WdpWirings.feed(WdpSession.dataCard, mission, cartridge) }
                .onFailure { line("  (the card could not be filled: ${it.message})") }
            line("the card: ${WdpFixtureMission.describe(mission)}")
            line("")

            // ---------------------------------------------------------------- 2. not linked
            line("== 2. Not linked")
            MissionLink.forget()
            // a plan an earlier run left in this device's settings is forgotten: the window opens as it does the first time
            KneeboardPrintSession.forget()
            KneeboardPrintSession.state = null
            KneeboardPrintSession.results = emptyList()
            PlannerWindows.show(PlannerWindow.PRINT)
            val stage = Stage(SCENE_W, SCENE_H)
            try {
                stage.frames(10)
                check(MissionLink.state.value !is LinkState.Online, "the link is down", "${MissionLink.state.value}")
                check(WdpProbe.rects.keys.none { it.startsWith("planner/Print/Half/") }, "no page table is drawn", "")
                check(WdpProbe.rects.containsKey("planner/Print/Help"), "the note's Help button is there", "")
                stage.shot(File(pics, "window-not-linked.png"))
                stage.click("Print/Print")
                stage.frames(5)
                check(KneeboardPrintSession.state == null && KneeboardPrintSession.results.isEmpty() && !KneeboardPrintSession.busy,
                    "Print does nothing while not linked", "state ${KneeboardPrintSession.state != null}, results ${KneeboardPrintSession.results.size}")
                line("")

                // ---------------------------------------------------------------- 3. linked
                line("== 3. Linked (This PC, the bridge on the copy)")
                val ez = File(root, "Tools/EZBoards")
                // EZBoards mode first (the claims keep the Mission set off its pages); WDP mode is part 9
                Bridge.update { it.copy(BmsDirOverride = root.path, AutoEzBoardsOnPrint = false, EzBoardsDir = ez.takeIf { d -> d.isDirectory }?.path, MissionSource = MissionMode.EZBOARDS) }
                Bridge.startForCheck()
                MissionLink.useThisPc()
                MissionLink.acquire(); acquired++
                val online = stage.until(20_000) { MissionLink.state.value is LinkState.Online && KneeboardPrintSession.state != null && !KneeboardPrintSession.loading }
                check(online, "the window reads the pages once the link is up", "${MissionLink.state.value}")
                val st = KneeboardPrintSession.state
                check(st != null && st.error == null && st.pages.size == 16, "sixteen pages, no error", "${st?.pages?.size} ${st?.error ?: ""}")
                line("  theater ${st?.theater}; folder ${st?.folder}; EZBoards config ${st?.ezConfig ?: "none"}")
                st?.pages?.forEach { p ->
                    line("  page %2d %s: L %s%s, R %s%s%s".format(p.n, p.file, p.left, if (p.ezLeft) " (EZBoards at PRINT)" else "", p.right, if (p.ezRight) " (EZBoards at PRINT)" else "",
                        if (p.missing) " MISSING" else p.problem?.let { " — $it" } ?: ""))
                }
                val claims = st?.pages.orEmpty().flatMap { p -> listOfNotNull("${p.n}L".takeIf { p.ezLeft }, "${p.n}R".takeIf { p.ezRight }) }
                line("  EZBoards claims: ${claims.ifEmpty { listOf("none") }.joinToString()}")
                val set = KneeboardPrintSession.missionPages()
                check(set.size == 2 && set.none { n -> claims.any { it.startsWith("$n") && it.length == "$n".length + 1 } },
                    "the Mission set is on the first two pages EZBoards leaves alone: ${set.joinToString(" and ")}", "$set")
                // EZBoards not set up in BMS Companion: its claims are unknown, and the pages as it ships are page 1
                val unknown = KneeboardPrintSession.state?.let { st -> st.copy(ezConfig = null, pages = st.pages.map { it.copy(ezLeft = false, ezRight = false) }) }
                val setUnknown = KneeboardPrintSession.missionPages(unknown)
                check(setUnknown == listOf(2, 3), "with EZBoards not set up, the Mission set still leaves page 1 to it: pages 2 and 3", "$setUnknown")
                val plan = KneeboardPrintSession.plan.toMap()
                check(set.size == 2 && plan == mapOf("${set[0]}L" to KbKind.DATACARD_LEFT, "${set[0]}R" to KbKind.DATACARD_RIGHT,
                    "${set[1]}L" to KbKind.COORDINATION_LEFT, "${set[1]}R" to KbKind.COORDINATION_RIGHT),
                    "the window opened on the Mission set: DataCard on page ${set.getOrNull(0)}, Coordination on page ${set.getOrNull(1)}", "$plan")
                stage.frames(20, 30)   // the thumbnails
                stage.shot(File(pics, "window-linked.png"))
                // the picker: a half set by its menu, then set back
                if (stage.click("Print/Pick/4L") && stage.click("Print/Kind/${KbKind.BLANK}")) {
                    check(KneeboardPrintSession.kind(4, 'L') == KbKind.BLANK, "the picker sets a half (page 4 left: Blank)", KneeboardPrintSession.kind(4, 'L'))
                    stage.shot(File(pics, "window-picked.png"))
                    stage.click("Print/Pick/4L"); stage.click("Print/Kind/${KbKind.LEAVE}")
                    stage.frames(12)
                    check(KneeboardPrintSession.kind(4, 'L') == KbKind.LEAVE, "and back to Leave as it is", KneeboardPrintSession.kind(4, 'L'))
                } else check(false, "the picker opens and offers the kinds", "no rectangle for Print/Pick/4L or Print/Kind/blank")
                line("")

                // ---------------------------------------------------------------- 4. print
                line("== 4. Print (the Mission set)")
                val here = KneeboardPrint.place(root, Bridge.install.theater)
                val shaBefore = shaTree(root)
                captures.clear()
                stage.onUi { KneeboardPrintSession.results = emptyList() }
                val t0 = System.currentTimeMillis()
                check(stage.click("Print/Print"), "the Print button is pressed", "")
                val trace = ArrayList<String>()
                var lastP: String? = ""
                val done = stage.until(180_000) {
                    val p = KneeboardPrintSession.progress
                    if (p != lastP) { trace += "${System.currentTimeMillis() - t0} ms: ${p ?: "(none)"}"; lastP = p }
                    !KneeboardPrintSession.busy && KneeboardPrintSession.results.isNotEmpty()
                }
                trace.forEach { line("  $it") }
                val took = System.currentTimeMillis() - t0
                val res = KneeboardPrintSession.results
                check(done, "the print finishes", "busy ${KneeboardPrintSession.busy}, ${res.size} answers")
                res.forEach { line("  ${it.file}: ${it.status}${it.reason?.let { r -> " — $r" } ?: ""}") }
                line("  took $took ms for ${captures.size} halves (capture, encode, send, write)")
                val files = set.map { KneeboardPrint.pageName(it) }
                check(res.map { it.file }.sorted() == files.sorted() && res.all { it.status == KbFileResult.WRITTEN },
                    "the Mission set writes 2 files: ${files.joinToString()}", res.joinToString { "${it.file} ${it.status}" })
                check(captures.size == 4 && captures.all { it.first == 1024 && it.second == 1536 }, "four halves were drawn at 1024 x 1536", captures.joinToString { "${it.first}x${it.second}" })
                val shaAfter = shaTree(root)
                val changed = (shaBefore.keys + shaAfter.keys).filter { shaBefore[it] != shaAfter[it] }.sorted()
                check(changed.map { it.substringAfterLast('/') }.sorted() == files.sorted(), "only those files changed in the copy, and nothing new appeared", changed.joinToString())
                // each half in the file is the half the window drew
                val order = set.flatMap { n -> listOf(n to 0, n to 1) }
                for ((i, pr) in order.withIndex()) {
                    val (n, side) = pr
                    val f = here.page(n) ?: continue
                    val bytes = f.readBytes()
                    val info = Dds.header(bytes)
                    val cap = captures.getOrNull(i)?.third?.let { ImageIO.read(ByteArrayInputStream(it)) } ?: continue
                    val want = KneeboardPrint.raster(cap, info.width / 2, info.height)
                    val got = Dds.decode(bytes, info, 0, side * info.width / 2, 0, info.width / 2, info.height)
                    val db = Dds.psnr(want, got)
                    check(db >= 30.0, "page $n ${if (side == 0) "left" else "right"}: the file holds the half the window drew (%.1f dB, ${info.format})".format(db), "%.1f dB".format(db))
                }
                for (n in set) {
                    val f = here.page(n) ?: continue
                    val bytes = f.readBytes()
                    runCatching { ImageIO.write(Dds.image(bytes, Dds.header(bytes)), "png", File(pics, "done-${f.nameWithoutExtension}.png")) }
                    for (side in 0..1) KneeboardPrint.halfImage(bytes, side, 512)?.let { ImageIO.write(it, "png", File(pics, "done-${f.nameWithoutExtension}-${if (side == 0) "left" else "right"}-as-seen.png")) }
                }
                val st2 = KneeboardPrintSession.state
                val owners = set.map { n -> st2?.pages?.firstOrNull { it.n == n } }
                check(owners.size == 2 && owners[0]?.left == KbOwner.COMPANION && owners[0]?.leftKind == KbKind.DATACARD_LEFT && owners[0]?.rightKind == KbKind.DATACARD_RIGHT &&
                    owners[1]?.left == KbOwner.COMPANION && owners[1]?.leftKind == KbKind.COORDINATION_LEFT && owners[1]?.rightKind == KbKind.COORDINATION_RIGHT,
                    "the owners read back: BMS Companion, DataCard L/R on page ${set.getOrNull(0)}, Coordination L/R on page ${set.getOrNull(1)}",
                    owners.joinToString { "${it?.n}: ${it?.left}/${it?.leftKind} ${it?.right}/${it?.rightKind}" })
                stage.frames(20, 30)
                stage.shot(File(pics, "window-printed.png"))
                // again: the same pages drawn again are the same pages
                val shaMid = shaTree(root)
                stage.onUi { KneeboardPrintSession.results = emptyList() }
                stage.click("Print/Print")
                stage.until(180_000) { !KneeboardPrintSession.busy && KneeboardPrintSession.results.isNotEmpty() && KneeboardPrintSession.progress == null }
                val again = KneeboardPrintSession.results
                val sameFiles = shaTree(root) == shaMid
                check(again.size == 2 && again.all { it.status == KbFileResult.UNCHANGED } && sameFiles, "printing again changes nothing (the same page draws the same picture)",
                    again.joinToString { "${it.file} ${it.status}" } + if (sameFiles) "" else "; files changed")
                line("")

                // ---------------------------------------------------------------- 5. a test page
                line("== 5. Test page")
                val tn = (1..16).firstOrNull { n -> n !in set && KneeboardPrintSession.printable(KneeboardPrintSession.slot(n)) && KneeboardPrintSession.slot(n)?.ezLeft == false && KneeboardPrintSession.slot(n)?.ezRight == false }
                if (tn != null) {
                    stage.click("Print/LeaveAll")
                    // the half is picked by its picture: the middle of the cell is its kind's menu
                    stage.click("Print/Thumb/${tn}L")
                    check(KneeboardPrintSession.selN == tn && KneeboardPrintSession.selSide == 'L', "a half's picture picks it for the preview (page $tn left)",
                        "${KneeboardPrintSession.selN}${KneeboardPrintSession.selSide}")
                    stage.click("Print/TestPage")
                    check(KneeboardPrintSession.jobs().map { it.first } == listOf(tn) && KneeboardPrintSession.kind(tn, 'R') == KbKind.TEST,
                        "Leave all, then Test page on the page picked ($tn): only that page is planned, both knees", KneeboardPrintSession.jobs().toString())
                    val shaT = shaTree(root)
                    stage.onUi { KneeboardPrintSession.results = emptyList() }
                    stage.click("Print/Print")
                    stage.until(120_000) { !KneeboardPrintSession.busy && KneeboardPrintSession.progress == null && KneeboardPrintSession.results.isNotEmpty() }
                    val r = KneeboardPrintSession.results
                    val ch = shaTree(root).let { a -> (a.keys + shaT.keys).filter { a[it] != shaT[it] } }
                    check(r.singleOrNull()?.status == KbFileResult.WRITTEN && ch.size == 1, "the test page is written to ${KneeboardPrint.pageName(tn)} alone", r.joinToString { "${it.file} ${it.status} ${it.reason ?: ""}" } + " changed $ch")
                    val to = KneeboardPrintSession.state?.pages?.firstOrNull { it.n == tn }
                    check(to?.left == KbOwner.COMPANION && to.leftKind == KbKind.TEST && to.rightKind == KbKind.TEST, "its owner reads back: BMS Companion, test page, both knees",
                        "${to?.left}/${to?.leftKind} ${to?.right}/${to?.rightKind}")
                    here.page(tn)?.readBytes()?.let { b ->
                        KneeboardPrint.halfImage(b, 0, 512)?.let { ImageIO.write(it, "png", File(pics, "test-page-$tn-left-as-seen.png")) }
                        runCatching { ImageIO.write(Dds.image(b, Dds.header(b)), "png", File(pics, "test-page-$tn.png")) }
                    }
                } else line("  (no page free for the test page)")
                line("")

                // ---------------------------------------------------------------- 6. the link drops
                line("== 6. The link drops")
                KneeboardPrintSession.missionSet()
                val planKept = KneeboardPrintSession.plan.toMap()
                MissionLink.forget()
                stage.frames(10)
                check(WdpProbe.rects.keys.none { it.startsWith("planner/Print/Half/") }, "the window turns into the note: no page table", "")
                stage.shot(File(pics, "window-link-lost.png"))
                val shaD = shaTree(root)
                stage.click("Print/Print")
                stage.frames(10)
                check(!KneeboardPrintSession.busy && shaTree(root) == shaD, "Print does nothing without the link", "")
                MissionLink.useThisPc()
                MissionLink.acquire(); acquired++
                val back = stage.until(20_000) { MissionLink.state.value is LinkState.Online && WdpProbe.rects.keys.any { it.startsWith("planner/Print/Half/") } }
                check(back && KneeboardPrintSession.plan.toMap() == planKept, "the link back brings the window back with the plan as it was", "${KneeboardPrintSession.plan}")
                line("")

                // ---------------------------------------------------------------- 7. BMS's page back
                line("== 7. Put BMS's page back")
                val bn = set.firstOrNull()
                val shipped = bn?.let { here.shippedPage(it) }
                if (bn != null && shipped != null && st2?.shipped == true) {
                    stage.click("Print/Thumb/${bn}L")
                    stage.onUi { KneeboardPrintSession.results = emptyList() }
                    stage.click("Print/PutBack")
                    stage.click("Print/PutBackPage")
                    stage.until(60_000) { !KneeboardPrintSession.busy && KneeboardPrintSession.results.isNotEmpty() && KneeboardPrintSession.resultsOf?.startsWith("Put") == true }
                    val r = KneeboardPrintSession.results
                    val f = here.page(bn)!!
                    check(r.singleOrNull()?.status == KbFileResult.WRITTEN && sha(f.readBytes()) == sha(shipped.readBytes()), "page $bn is BMS's own again (SHA equal to BMS's shipped copy)",
                        r.joinToString { "${it.file} ${it.status} ${it.reason ?: ""}" })
                    val o = KneeboardPrintSession.state?.pages?.firstOrNull { it.n == bn }
                    check(o?.left == KbOwner.BMS && o.right == KbOwner.BMS, "and its owner reads back as BMS", "${o?.left}/${o?.right}")
                    stage.shot(File(pics, "window-put-back.png"))
                } else line("  (no shipped copy of page $bn in this theater)")
                line("")

                // ---------------------------------------------------------------- 8. Browse picture
                line("== 8. Browse picture (WDP's Browse Picture): a .png and a .dds on one page, stretched over each half")
                val pn = (1..16).firstOrNull { n -> n !in set && n != tn && KneeboardPrintSession.printable(KneeboardPrintSession.slot(n)) && KneeboardPrintSession.slot(n)?.ezLeft == false }
                val pictureDir = File(outDir, "print-pictures").apply { mkdirs() }
                val png = File(pictureDir, "quadrants.png")
                val source = quadrants(640, 400)
                ImageIO.write(source, "png", png)
                // a .dds from the copy (read, never written here): a page BMS ships, or the page file itself
                val ddsSrc = listOfNotNull(pn?.let { here.shippedPage(it) }, here.shippedPage(1), here.page(16)).firstOrNull { it.isFile }
                val dds = ddsSrc?.let { s -> File(pictureDir, "picture.dds").also { s.copyTo(it, overwrite = true) } }
                val asked = CopyOnWriteArrayList<com.bmscompanion.app.data.FileRequest>()
                var answer: String? = png.path
                com.bmscompanion.app.data.PcFiles.testAnswer = { r -> asked += r; answer }
                if (pn == null) line("  (no page free for the pictures)") else try {
                    stage.click("Print/LeaveAll")
                    stage.click("Print/Thumb/${pn}L")
                    check(stage.click("Print/Browse"), "the Browse picture… button is there", "")
                    val opened = stage.until(20_000) { KneeboardPrintSession.browse != null && KneeboardPictures.cached(png.path)?.let { it.image != null || it.error != null } == true }
                    val req = asked.lastOrNull()
                    check(req?.title == "Open picture" && req.filters.firstOrNull()?.extensions?.containsAll(listOf("jpg", "png", "bmp", "dds")) == true &&
                        req.filters.drop(1).map { it.extensions.firstOrNull() } == listOf("jpg", "png", "bmp", "dds"),
                        "the Open picture window: WDP's title, every picture type first, then WDP's four", "${req?.title}: ${req?.filters?.joinToString { it.label }}")
                    check(req?.startDir == "@bms" || req?.startDir?.contains("print-pictures") == true, "it starts where the last picture was, else in the Falcon BMS folder", "${req?.startDir}")
                    val loaded = KneeboardPictures.cached(png.path)
                    check(opened && loaded?.image != null && loaded.info?.width == 640 && loaded.info.height == 400 && loaded.info.format == "PNG",
                        "the chooser opens on the picture, read by the PC (${loaded?.info?.format} ${loaded?.info?.width} x ${loaded?.info?.height})", loaded?.error ?: "not read")
                    check(KneeboardPrintSession.browse?.page == pn, "on the page picked in the table ($pn)", "${KneeboardPrintSession.browse?.page}")
                    stage.shot(File(pics, "window-browse-picture.png"))
                    check(stage.click("Print/Pic/InsertL") && KneeboardPrintSession.kind(pn, 'L') == KbKind.PICTURE && KneeboardPrintSession.picture(pn, 'L') == png.path &&
                        KneeboardPrintSession.browse == null, "← Insert left puts it on page $pn's left half and closes the chooser",
                        "${KneeboardPrintSession.kind(pn, 'L')} ${KneeboardPrintSession.picture(pn, 'L')}")
                    // the right half through its own menu: Picture… opens the same window for that half
                    if (dds != null) {
                        answer = dds.path
                        stage.click("Print/Pick/${pn}R")
                        check(stage.click("Print/Kind/${KbKind.PICTURE}"), "the half's menu offers Picture…", "")
                        stage.until(20_000) { KneeboardPrintSession.browse != null && KneeboardPictures.cached(dds.path)?.let { it.image != null || it.error != null } == true }
                        val d = KneeboardPictures.cached(dds.path)
                        check(d?.image != null && d.info?.format?.startsWith("DDS") == true, "a .dds is read by the PC too (${d?.info?.format} ${d?.info?.width} x ${d?.info?.height})", d?.error ?: "not read")
                        check(KneeboardPrintSession.browse?.side == 'R' && KneeboardPrintSession.browse?.page == pn, "the chooser opens for that half (page $pn, right)", "${KneeboardPrintSession.browse?.page}${KneeboardPrintSession.browse?.side}")
                        stage.click("Print/Pic/InsertR")
                        // the half keeps its picture when set to another kind (WDP's m_Right[n].File): its Picture… then
                        // opens the chooser on that picture again, without the file window
                        stage.click("Print/Pick/${pn}R"); stage.click("Print/Kind/${KbKind.BLANK}")
                        val keptPath = KneeboardPrintSession.picture(pn, 'R')
                        val askedNow = asked.size
                        stage.click("Print/Pick/${pn}R"); stage.click("Print/Kind/${KbKind.PICTURE}")
                        stage.until(10_000) { KneeboardPrintSession.browse != null }
                        check(KneeboardPrintSession.kind(pn, 'R') == KbKind.BLANK && keptPath == dds.path && asked.size == askedNow &&
                            KneeboardPrintSession.browse?.path == dds.path && KneeboardPrintSession.browse?.side == 'R',
                            "a half set to Blank keeps its picture, and its Picture… opens the chooser on it again without asking for a file",
                            "kind ${KneeboardPrintSession.kind(pn, 'R')}, kept $keptPath, asked ${asked.size - askedNow}, chooser ${KneeboardPrintSession.browse?.path}")
                        stage.click("Print/Pic/InsertR")
                        check(KneeboardPrintSession.kind(pn, 'R') == KbKind.PICTURE && KneeboardPrintSession.picture(pn, 'R') == dds.path, "and Insert right → puts it back",
                            "${KneeboardPrintSession.kind(pn, 'R')} ${KneeboardPrintSession.picture(pn, 'R')}")
                    }
                    // the plan and the pictures are kept on the device: what the next launch finds is the same plan
                    val planNow = KneeboardPrintSession.plan.toMap()
                    val picsNow = KneeboardPrintSession.pictures.toMap()
                    stage.onUi { KneeboardPrintSession.reloadSaved() }
                    check(KneeboardPrintSession.plan.toMap() == planNow && KneeboardPrintSession.pictures.toMap() == picsNow && KneeboardPrintSession.planned,
                        "the plan and each half's picture are kept on the device between launches (${KneeboardPrintSession.SAVED_KEY})",
                        "kept ${KneeboardPrintSession.plan} ${KneeboardPrintSession.pictures}, was $planNow $picsNow")
                    // Cancel leaves the plan as it was
                    val planBefore = KneeboardPrintSession.plan.toMap()
                    answer = png.path
                    stage.click("Print/Browse")
                    stage.until(10_000) { KneeboardPrintSession.browse != null }
                    // the chooser's page buttons and Another picture… each do what they say
                    val p0 = KneeboardPrintSession.browse?.page ?: 0
                    val step = if (p0 < 16) 1 else -1
                    stage.click(if (step > 0) "Print/Pic/Next" else "Print/Pic/Prev"); stage.frames(3)
                    val p1 = KneeboardPrintSession.browse?.page
                    stage.click(if (step > 0) "Print/Pic/Prev" else "Print/Pic/Next"); stage.frames(3)
                    val p2 = KneeboardPrintSession.browse?.page
                    check(p0 in 1..16 && p1 == p0 + step && p2 == p0, "the chooser's + and − move its page ($p0 → $p1 → $p2)", "$p0 → $p1 → $p2")
                    val askedBefore = asked.size
                    stage.click("Print/Pic/Browse")
                    stage.until(10_000) { asked.size > askedBefore && KneeboardPrintSession.browse?.path == png.path }
                    check(asked.size > askedBefore && asked.last().title == "Open picture" && KneeboardPrintSession.browse?.path == png.path,
                        "Another picture… opens the Open picture window again, and the chooser stays open on what it answers", "${asked.size - askedBefore} asked")
                    stage.click("Print/Pic/Cancel")
                    check(KneeboardPrintSession.browse == null && KneeboardPrintSession.plan.toMap() == planBefore, "Cancel closes the chooser and changes nothing", "${KneeboardPrintSession.plan}")
                    stage.frames(20, 30)
                    stage.shot(File(pics, "window-pictures-planned.png"))
                    // print: the page file holds each picture stretched over its half
                    val shaP = shaTree(root)
                    captures.clear()
                    stage.onUi { KneeboardPrintSession.results = emptyList() }
                    stage.click("Print/Print")
                    stage.until(120_000) { !KneeboardPrintSession.busy && KneeboardPrintSession.progress == null && KneeboardPrintSession.results.isNotEmpty() }
                    val r = KneeboardPrintSession.results
                    val ch = shaTree(root).let { a -> (a.keys + shaP.keys).filter { a[it] != shaP[it] } }
                    check(r.singleOrNull()?.status == KbFileResult.WRITTEN && ch.map { it.substringAfterLast('/') } == listOf(KneeboardPrint.pageName(pn)),
                        "the pictures are written to ${KneeboardPrint.pageName(pn)} alone", r.joinToString { "${it.file} ${it.status} ${it.reason ?: ""}" } + " changed $ch")
                    check(captures.isEmpty(), "no Picture half is captured on the device: the PC draws it from the file", "${captures.size} captured")
                    // the .dds as the PC reads it, whole
                    val ddsImg = dds?.let { (PictureFile.read(it) as? PictureFile.Read.Ok)?.img }
                    // compared at a quarter of the half's size: the stretch, not the finest detail, is what is checked
                    here.page(pn)?.readBytes()?.let { bytes ->
                        val info = Dds.header(bytes)
                        val db = Dds.psnr(small(source), small(halfOf(bytes, info, 0)))
                        check(db >= 24.0, "the left half is the .png stretched over the whole half, as WDP stretches it (%.1f dB against the picture stretched)".format(db), "%.1f dB".format(db))
                        if (dds != null) {
                            val db2 = ddsImg?.let { Dds.psnr(small(it), small(halfOf(bytes, info, 1))) } ?: 0.0
                            check(db2 >= 22.0, "the right half is the .dds stretched over its half (%.1f dB)".format(db2), "%.1f dB".format(db2))
                        }
                        KneeboardPrint.halfImage(bytes, 0, 512)?.let { ImageIO.write(it, "png", File(pics, "picture-page-$pn-left-as-seen.png")) }
                        KneeboardPrint.halfImage(bytes, 1, 512)?.let { ImageIO.write(it, "png", File(pics, "picture-page-$pn-right-as-seen.png")) }
                    }
                    val o = KneeboardPrintSession.state?.pages?.firstOrNull { it.n == pn }
                    check(o?.left == KbOwner.COMPANION && o.leftKind == KbKind.PICTURE && (dds == null || o.rightKind == KbKind.PICTURE),
                        "the owner reads back: BMS Companion, Picture (the header's tag carries the new kind)", "${o?.left}/${o?.leftKind} ${o?.right}/${o?.rightKind}")
                    // full detail: a picture made at the half's own size, with rules one pixel thick, keeps every rule (a
                    // device's 1024 x 1536 capture stretched 4/3 lost a quarter of its rows and blurred the rest)
                    val halfW = here.page(pn)?.let { f -> RandomAccessFile(f, "r").use { raf -> ByteArray(Dds.HEADER).also { raf.readFully(it) } }.let { Dds.header(it, f.length()).width / 2 } } ?: 1024
                    val rules = rules(halfW, halfW * 2)
                    val rulesFile = File(pictureDir, "rules.png").also { ImageIO.write(rules, "png", it) }
                    stage.onUi { KneeboardPrintSession.pictures["${pn}L"] = rulesFile.path }
                    stage.onUi { KneeboardPrintSession.results = emptyList() }
                    stage.click("Print/Print")
                    stage.until(120_000) { !KneeboardPrintSession.busy && KneeboardPrintSession.progress == null && KneeboardPrintSession.results.isNotEmpty() }
                    here.page(pn)?.readBytes()?.let { bytes ->
                        val info = Dds.header(bytes)
                        val got = Dds.decode(bytes, info, 0, 0, 0, info.width / 2, info.height)
                        val want = rules.getRGB(0, 0, rules.width, rules.height, null, 0, rules.width)
                        val db = if (want.size == got.size) Dds.psnr(want, got) else 0.0
                        // every rule's row is dark in the middle of the half, and the rows between are white
                        val x = info.width / 4
                        val ruleRows = (0 until info.height).filter { y -> (rules.getRGB(x, y) and 0xFF) < 128 }
                        val kept = ruleRows.count { y -> (got[y * (info.width / 2) + x] and 0xFF) < 128 }
                        check(db >= 30.0 && kept == ruleRows.size,
                            "a ${rules.width} x ${rules.height} picture of one-pixel rules is in the half pixel for pixel (%.1f dB, $kept of ${ruleRows.size} rules kept)".format(db),
                            "%.1f dB, $kept of ${ruleRows.size} rules; ${KneeboardPrintSession.results.joinToString { "${it.file} ${it.status} ${it.reason ?: ""}" }}".format(db))
                        KneeboardPrint.halfImage(bytes, 0, 512)?.let { ImageIO.write(it, "png", File(pics, "picture-rules-as-seen.png")) }
                    }
                    stage.onUi { KneeboardPrintSession.pictures["${pn}L"] = png.path }
                    stage.onUi { KneeboardPrintSession.results = emptyList() }
                    stage.click("Print/Print")
                    stage.until(120_000) { !KneeboardPrintSession.busy && KneeboardPrintSession.progress == null && KneeboardPrintSession.results.isNotEmpty() }
                    // a picture gone by print time: its half is left as it is, and the window says why
                    val gone = File(pictureDir, "gone.png").also { ImageIO.write(source, "png", it) }
                    stage.onUi { KneeboardPrintSession.pictures["${pn}L"] = gone.path }
                    gone.delete()
                    val shaG = shaTree(root)
                    stage.onUi { KneeboardPrintSession.results = emptyList() }
                    stage.click("Print/Print")
                    stage.until(120_000) { !KneeboardPrintSession.busy && KneeboardPrintSession.progress == null && KneeboardPrintSession.results.isNotEmpty() }
                    val rg = KneeboardPrintSession.results
                    val refused = rg.firstOrNull { it.status == KbFileResult.REFUSED }
                    val leftSame = here.page(pn)?.readBytes()?.let { b -> Dds.psnr(small(source), small(halfOf(b, Dds.header(b), 0))) } ?: 0.0
                    check(refused != null && refused.reason?.contains("gone.png") == true && refused.file.contains("left knee") && leftSame >= 24.0,
                        "a picture gone by print time leaves its half as it is, and the window says why", rg.joinToString { "${it.file} ${it.status}: ${it.reason ?: ""}" })
                    line("  ${refused?.file}: ${refused?.reason}")
                    // the other half is still sent; the file may be rewritten (its three smallest levels are rebuilt from both
                    // halves, the kept one now from its compressed blocks), so what is checked is that the right half is the .dds still
                    if (dds != null) {
                        val rightSame = here.page(pn)?.readBytes()?.let { b -> ddsImg?.let { Dds.psnr(small(it), small(halfOf(b, Dds.header(b), 1))) } } ?: 0.0
                        val chG = shaTree(root).let { a -> (a.keys + shaG.keys).filter { a[it] != shaG[it] } }.map { it.substringAfterLast('/') }
                        check(rg.any { it.file == KneeboardPrint.pageName(pn) && it.status != KbFileResult.REFUSED } && rightSame >= 22.0 &&
                            chG.all { it == KneeboardPrint.pageName(pn) },
                            "the other half is still sent: the right half is the .dds again (%.1f dB), and no other file changed".format(rightSame),
                            rg.joinToString { "${it.file} ${it.status}" } + " changed $chG")
                    }
                    stage.shot(File(pics, "window-picture-gone.png"))
                    stage.onUi { KneeboardPrintSession.pictures["${pn}L"] = png.path }
                } finally {
                    com.bmscompanion.app.data.PcFiles.testAnswer = null
                }
                line("")

                // ---------------------------------------------------------------- 9. WDP mode
                line("== 9. WDP mode: EZBoards is paused, the Mission set starts at page 1")
                val claimedNow = KneeboardPrintSession.state?.pages.orEmpty().filter { it.ezLeft || it.ezRight }.map { it.n }
                line("  EZBoards claims in the copy: ${claimedNow.ifEmpty { listOf("none") }.joinToString()}")
                // the rule on a stock EZBoards setup (page 1 claimed), whatever the copy's own config says
                val stock = KneeboardPrintSession.state?.let { s0 -> s0.copy(pages = s0.pages.map { it.copy(ezLeft = it.n == 1, ezRight = it.n == 1) }) }
                check(KneeboardPrintSession.missionPages(stock?.copy(mode = MissionMode.EZBOARDS)) == listOf(2, 3), "EZBoards mode, page 1 claimed: the Mission set is on pages 2 and 3 (as before)",
                    "${KneeboardPrintSession.missionPages(stock?.copy(mode = MissionMode.EZBOARDS))}")
                check(KneeboardPrintSession.missionPages(stock?.copy(mode = MissionMode.WDP)) == listOf(1, 2), "WDP mode, page 1 claimed: the Mission set is on pages 1 and 2",
                    "${KneeboardPrintSession.missionPages(stock?.copy(mode = MissionMode.WDP))}")
                check(KneeboardPrintSession.missionPages(stock?.copy(mode = MissionMode.WDP, ezConfig = null)) == listOf(1, 2), "WDP mode, EZBoards not set up: pages 1 and 2 too",
                    "${KneeboardPrintSession.missionPages(stock?.copy(mode = MissionMode.WDP, ezConfig = null))}")
                // EZBoards claims page 1 in the copy for this part, as it does on a stock setup, so that the switch below
                // really moves the Mission set (a copy whose config claims nothing lays both modes on pages 1 and 2)
                val ezCfg = File(root, "Tools/EZBoards/CONFIG_USER.BAT")
                val ezCfgBefore = ezCfg.takeIf { it.isFile && Bridge.settings.value.EzBoardsDir != null }?.readBytes()
                val addClaims = ezCfgBefore != null && KneeboardPrintSession.slot(1)?.let { it.ezLeft && it.ezRight } != true
                try {
                if (addClaims) {
                    ezCfg.appendText("\r\nSET KNEEBOARD[F16_1L]=page_1\r\nSET KNEEBOARD[F16_1R]=page_2\r\n", Charsets.ISO_8859_1)
                    stage.onUi { kotlinx.coroutines.runBlocking { KneeboardPrintSession.load() } }
                }
                val claims1 = KneeboardPrintSession.slot(1)?.let { it.ezLeft && it.ezRight } == true
                line("  page 1 claimed by EZBoards for this part: $claims1" + if (addClaims) " (two SET KNEEBOARD lines added to the copy's config, taken out after)" else "")
                // an untouched Mission set of EZBoards mode is laid again when the PC is switched to WDP mode
                stage.onUi { KneeboardPrintSession.missionSet() }
                val ezSet = KneeboardPrintSession.plan.toMap()
                check(!claims1 || (ezSet["1L"] == null && ezSet["1R"] == null), "EZBoards mode with page 1 claimed: the Mission set leaves page 1 to EZBoards", "$ezSet")
                Bridge.update { it.copy(MissionSource = MissionMode.WDP) }
                stage.onUi { kotlinx.coroutines.runBlocking { KneeboardPrintSession.load() } }
                stage.frames(10)
                val wdpSet = KneeboardPrintSession.missionPages()
                check(KneeboardPrintSession.state?.mode == MissionMode.WDP, "the PC says it is in WDP mode with the pages", "${KneeboardPrintSession.state?.mode}")
                check(wdpSet.firstOrNull() == 1 && KneeboardPrintSession.plan.toMap() == mapOf("1L" to KbKind.DATACARD_LEFT, "1R" to KbKind.DATACARD_RIGHT,
                    "${wdpSet.getOrNull(1)}L" to KbKind.COORDINATION_LEFT, "${wdpSet.getOrNull(1)}R" to KbKind.COORDINATION_RIGHT) && KneeboardPrintSession.relaid != null,
                    "the EZBoards-mode Mission set is laid again for WDP mode: DataCard on page 1, Coordination on page ${wdpSet.getOrNull(1)}, and the window says so",
                    "before $ezSet, now ${KneeboardPrintSession.plan}; ${KneeboardPrintSession.relaid}")
                if (claims1) check(ezSet != KneeboardPrintSession.plan.toMap(), "the switch moved the set: EZBoards mode had it on pages ${ezSet.keys.map { it.dropLast(1) }.distinct().joinToString(" and ")}, WDP mode on 1 and ${wdpSet.getOrNull(1)}",
                    "before $ezSet, now ${KneeboardPrintSession.plan}")
                // without a claim on page 1 both modes lay the set on pages 1 and 2, and the switch would pass untested
                else check(false, "the switch was tested: page 1 claimed by EZBoards in the copy for this part",
                    "the copy has no EZBoards config to claim page 1 (Tools/EZBoards/CONFIG_USER.BAT and the EzBoardsDir setting): give the copy one")
                stage.frames(20, 30)
                stage.shot(File(pics, "window-wdp-mode.png"))
                // the window's first opening in WDP mode: the Mission set on page 1
                stage.onUi { KneeboardPrintSession.planned = false; KneeboardPrintSession.plan.clear(); kotlinx.coroutines.runBlocking { KneeboardPrintSession.load() } }
                check(KneeboardPrintSession.plan["1L"] == KbKind.DATACARD_LEFT && KneeboardPrintSession.plan["1R"] == KbKind.DATACARD_RIGHT && KneeboardPrintSession.relaid == null,
                    "opened first in WDP mode, the window is on the Mission set with the DataCard on page 1", "${KneeboardPrintSession.plan}")
                // print in WDP mode: page 1 is written, EZBoards' claim notwithstanding
                val shaW = shaTree(root)
                stage.onUi { KneeboardPrintSession.results = emptyList() }
                stage.click("Print/Print")
                stage.until(180_000) { !KneeboardPrintSession.busy && KneeboardPrintSession.progress == null && KneeboardPrintSession.results.isNotEmpty() }
                val rw = KneeboardPrintSession.results
                val chW = shaTree(root).let { a -> (a.keys + shaW.keys).filter { a[it] != shaW[it] } }.map { it.substringAfterLast('/') }.sorted()
                val p1 = KneeboardPrintSession.state?.pages?.firstOrNull { it.n == 1 }
                check(rw.any { it.file == KneeboardPrint.pageName(1) && it.status == KbFileResult.WRITTEN } && KneeboardPrint.pageName(1) in chW &&
                    p1?.left == KbOwner.COMPANION && p1.leftKind == KbKind.DATACARD_LEFT,
                    "Print writes the DataCard on page 1 (${KneeboardPrint.pageName(1)}) in WDP mode", rw.joinToString { "${it.file} ${it.status} ${it.reason ?: ""}" } + "; changed $chW")
                // a plan changed by hand is kept across a switch back, with a quiet line
                stage.onUi { KneeboardPrintSession.set(5, 'L', KbKind.BLANK) }
                val handPlan = KneeboardPrintSession.plan.toMap()
                Bridge.update { it.copy(MissionSource = MissionMode.EZBOARDS) }
                stage.onUi { kotlinx.coroutines.runBlocking { KneeboardPrintSession.load() } }
                check(KneeboardPrintSession.plan.toMap() == handPlan && KneeboardPrintSession.madeIn == MissionMode.WDP && KneeboardPrintSession.relaid == null,
                    "a plan changed by hand is kept when the mode changes (the window says which mode it was made in)", "${KneeboardPrintSession.plan}")
                stage.onUi { KneeboardPrintSession.missionSet() }
                } finally {
                    // the copy's EZBoards config as it was
                    if (addClaims && ezCfgBefore != null) runCatching { ezCfg.writeBytes(ezCfgBefore) }
                }
                stage.onUi { kotlinx.coroutines.runBlocking { KneeboardPrintSession.load() } }
                line("")

                // the narrow layout, for the picture
                PlannerWindows.show(PlannerWindow.PRINT)
            } finally {
                stage.close()
            }
            // ---------------------------------------------------------------- 10. every control in the window
            // a window tall enough for the whole kind menu to show without scrolling
            line("== 10. Every control in the window, pressed (none may throw; each does what it is for)")
            val tall = Stage(SCENE_W, 1400)
            try {
                tall.frames(12)
                sweep(tall, pictureDir = File(outDir, "print-pictures"), line = ::line, check = ::check)
                tall.shot(File(pics, "window-swept.png"))
            } finally { tall.close() }
            line("")
            val phone = Stage(400, 820)
            try {
                phone.frames(20, 30)
                phone.shot(File(pics, "window-phone.png"))
                check(WdpProbe.rects.containsKey("planner/Print/Preview"), "a phone's window shows the table and the preview under it", "")
            } finally { phone.close() }
        } catch (e: Throwable) {
            fails++
            line("FAIL: threw ${e::class.java.simpleName}: ${e.message}")
            e.stackTrace.take(14).forEach { line("    at $it") }
        } finally {
            PlannerWindows.close()
            repeat(acquired) { runCatching { MissionLink.release() } }
            Platform.encodePng = oldEncode
            WdpProbe.on = wasProbe
            Bridge.update { before }
            if (!wasRunning) Bridge.stop()
            for ((k, v) in kept) runCatching { com.bmscompanion.app.data.Repo.putString(k, v) }
            runCatching { KneeboardPrintSession.reloadSaved() }
        }
        line("")
        line(if (fails == 0) "ALL PASS" else "$fails FAIL")
        return out.toString()
    }

    /**
     * Part 10: every control the Upd Kneeboard window draws, pressed through its own probe with the state set so that
     * the press has something to do: a half's thumb, cell and kind menu (every kind in it), the buttons under the table,
     * the chooser's controls, Help. A press that throws, or a control whose press does nothing, fails. Print and Put
     * BMS's page back (page) are pressed and checked against the files in parts 4-9; Put BMS's pages back (all 16) is
     * opened but not pressed. (`--wdpclicktest` presses WDP's own forms; this window is the app's, so it is pressed here.)
     */
    private fun sweep(stage: Stage, pictureDir: File, line: (String) -> Unit, check: (Boolean, String, String) -> Unit) {
        val s = KneeboardPrintSession
        val picture = File(pictureDir, "quadrants.png").takeIf { it.isFile }
        var asks = 0
        var askMark = 0
        com.bmscompanion.app.data.PcFiles.testAnswer = { asks++; picture?.path }
        var pressed = 0
        val dead = ArrayList<String>()
        val threw = ArrayList<String>()
        /**
         * Presses [probe] after [setup]; [did] says whether the press did what it is for (waited for up to 5 s). A menu
         * left open is closed first, unless the press is on it ([inMenu]).
         */
        fun press(probe: String, what: String, setup: () -> Unit = {}, inMenu: Boolean = false, did: () -> Boolean) {
            try {
                if (!inMenu && WdpProbe.rects.keys.any { it.startsWith("planner/Print/Kind/") || it == "planner/Print/PutBackPage" }) {
                    stage.click("Print/Preview"); Thread.sleep(100); stage.frames(4)
                }
                if (PlannerWindows.open != PlannerWindow.PRINT) { PlannerWindows.show(PlannerWindow.PRINT); stage.frames(6) }
                stage.onUi { setup() }
                // a menu that just closed takes a moment to leave, and a press while it is there only closes it
                Thread.sleep(80)
                stage.frames(3)
                if (!stage.click(probe)) { dead += "$probe (not on screen)"; return }
                pressed++
                if (!stage.until(5_000) { did() }) dead += "$probe: $what"
            } catch (e: Throwable) {
                val c = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                threw += "$probe: ${c::class.java.simpleName}: ${c.message}"
            }
        }
        try {
            PlannerWindows.show(PlannerWindow.PRINT)
            stage.onUi { s.missionSet() }
            stage.frames(10)
            val pages = s.state?.pages.orEmpty().filter { s.printable(it) }.map { it.n }
            val a = pages.firstOrNull() ?: run { line("  (no page can be printed: nothing to press)"); return }
            val b = pages.firstOrNull { it != a } ?: a
            // the table: a half's picture and its cell pick it for the preview
            press("Print/Thumb/${a}L", "picks page $a left", { s.selN = b; s.selSide = 'R' }) { s.selN == a && s.selSide == 'L' }
            press("Print/Half/${b}R", "picks page $b right", { s.selN = a; s.selSide = 'L' }) { s.selN == b && s.selSide == 'R' }
            // the kind menu of a half, and every kind in it
            for (k in KneeboardPages.kinds) {
                val want = if (k.id == KbKind.LEAVE) KbKind.BLANK else KbKind.LEAVE
                press("Print/Pick/${b}L", "opens the kind menu", { s.cancelPicture(); s.set(b, 'L', want) }) { WdpProbe.rects.containsKey("planner/Print/Kind/${k.id}") }
                if (k.id == KbKind.PICTURE) {
                    press("Print/Kind/${k.id}", "opens Browse picture for the half", {}, inMenu = true) { s.browse != null }
                    stage.onUi { s.cancelPicture() }
                } else {
                    press("Print/Kind/${k.id}", "sets the half to ${k.id}", {}, inMenu = true) { s.kind(b, 'L') == k.id }
                }
            }
            // the buttons under the table
            press("Print/MissionSet", "lays the Mission set", { s.leaveAll() }) { s.plan.isNotEmpty() && s.laidFor != null }
            press("Print/TestPage", "puts the test page on both knees of page $a", { s.missionSet(); s.selN = a; s.selSide = 'L' }) {
                s.kind(a, 'L') == KbKind.TEST && s.kind(a, 'R') == KbKind.TEST
            }
            press("Print/LeaveAll", "leaves every page as it is", { s.missionSet() }) { s.plan.isEmpty() }
            if (s.state?.shipped == true) {
                press("Print/PutBack", "opens the Put BMS's page back menu", {}) {
                    WdpProbe.rects.containsKey("planner/Print/PutBackPage") && WdpProbe.rects.containsKey("planner/Print/PutBackAll")
                }
                // (the next press closes the menu first: a press outside it only closes it)
            } else line("  Put BMS's page back: disabled here (BMS ships no copy of these pages for this theater)")
            press("Print/Help", "opens the guide", {}) { PlannerWindows.open == PlannerWindow.GUIDE }
            // Browse picture… and its chooser
            if (picture != null) {
                press("Print/Browse", "opens the Open picture window, then the chooser", { s.cancelPicture(); s.selN = a; s.selSide = 'L'; askMark = asks }) {
                    asks > askMark &&
                    s.browse?.path == picture.path && KneeboardPictures.cached(picture.path)?.image != null
                }
                val p0 = s.browse?.page ?: a
                press("Print/Pic/Next", "turns the chooser's page on", { s.browse?.page = minOf(p0, 15) }) { s.browse?.page == minOf(p0, 15) + 1 }
                press("Print/Pic/Prev", "turns the chooser's page back", { s.browse?.page = maxOf(p0, 2) }) { s.browse?.page == maxOf(p0, 2) - 1 }
                press("Print/Pic/Browse", "opens the Open picture window again", { s.browse?.page = a; askMark = asks }) { asks > askMark && s.browse?.path == picture.path }
                press("Print/Pic/InsertL", "puts the picture on page $a left", { s.browse?.page = a }) {
                    s.browse == null && s.kind(a, 'L') == KbKind.PICTURE && s.picture(a, 'L') == picture.path
                }
                press("Print/Browse", "opens the chooser again", {}) { s.browse != null && KneeboardPictures.cached(picture.path)?.image != null }
                press("Print/Pic/InsertR", "puts the picture on page $a right", { s.browse?.page = a }) {
                    s.browse == null && s.kind(a, 'R') == KbKind.PICTURE && s.picture(a, 'R') == picture.path
                }
                press("Print/Browse", "opens the chooser again", {}) { s.browse != null }
                press("Print/Pic/Cancel", "closes the chooser", {}) { s.browse == null }
            } else line("  (no picture from part 8 to browse with)")
            line("  pressed $pressed; did nothing: ${dead.size}; threw: ${threw.size}")
            dead.forEach { line("  DEAD $it") }
            threw.forEach { line("  THREW $it") }
            check(threw.isEmpty(), "no control in the window throws ($pressed pressed)", threw.joinToString("; "))
            check(dead.isEmpty(), "every control in the window does what it is for", dead.joinToString("; "))
        } finally {
            com.bmscompanion.app.data.PcFiles.testAnswer = null
            stage.onUi { s.cancelPicture(); s.missionSet() }
            if (PlannerWindows.open != PlannerWindow.PRINT) PlannerWindows.show(PlannerWindow.PRINT)
        }
    }

    /** A picture of four coloured quarters and a ring, for Browse picture's stretch to show. */
    private fun quadrants(w: Int, h: Int): java.awt.image.BufferedImage {
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(0xC0, 0x30, 0x30); g.fillRect(0, 0, w / 2, h / 2)
        g.color = java.awt.Color(0x30, 0x90, 0x40); g.fillRect(w / 2, 0, w - w / 2, h / 2)
        g.color = java.awt.Color(0x30, 0x50, 0xC0); g.fillRect(0, h / 2, w / 2, h - h / 2)
        g.color = java.awt.Color(0xE0, 0xC0, 0x30); g.fillRect(w / 2, h / 2, w - w / 2, h - h / 2)
        g.color = java.awt.Color.WHITE
        g.stroke = java.awt.BasicStroke(12f)
        g.drawOval(w / 4, h / 4, w / 2, h / 2)
        g.dispose()
        return img
    }

    /**
     * A white page of black rules one pixel thick, every sixth row, with a few upright ones (never through the middle
     * column, where the check looks): the finest detail a kneeboard picture has, in two colours a DXT block keeps exactly.
     */
    private fun rules(w: Int, h: Int): java.awt.image.BufferedImage {
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val dark = y % 6 == 2 || (x % 64 == 31 && x != w / 2)
            img.setRGB(x, y, if (dark) 0x000000 else 0xFFFFFF)
        }
        return img
    }

    /** One half of a page file as a picture ([side] 0 left, 1 right). */
    private fun halfOf(bytes: ByteArray, info: Dds.Info, side: Int): java.awt.image.BufferedImage =
        Dds.toImage(Dds.decode(bytes, info, 0, side * info.width / 2, 0, info.width / 2, info.height), info.width / 2, info.height)

    /** [img] stretched to 256 x 512, a quarter of a half page. */
    private fun small(img: java.awt.image.BufferedImage): IntArray = KneeboardPrint.raster(img, 256, 512)

    private fun red(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return r > 120 && g < 90 && b < 90
    }

    private fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** SHA-256 of every file under [root], by its path relative to it. */
    private fun shaTree(root: File): Map<String, String> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to sha(it.readBytes()) }

    /** A scene of its own, whose composition, frames and coroutines all run on one thread, as a window's do. */
    private open class Scene(val w: Int, val h: Int) : AutoCloseable {
        private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "print-ui").apply { isDaemon = true } }
        val ui = exec.asCoroutineDispatcher()
        private var nanos = 0L
        lateinit var scene: ImageComposeScene

        fun <T> onUi(block: () -> T): T = exec.submit(Callable { block() }).get()

        fun start(content: @Composable () -> Unit) {
            scene = onUi { ImageComposeScene(w, h, Density(1f), coroutineContext = ui, content = content) }
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
                if (cond()) return true
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

        override fun close() {
            runCatching { onUi { scene.close() } }
            exec.shutdown()
        }
    }

    /** The Planner's window host over a dark page, the Print window open in it. */
    private class Stage(w: Int, h: Int) : Scene(w, h) {
        init {
            start { Box(Modifier.fillMaxSize().background(Hud.Bg)) { PlannerWindowHost() } }
        }

        /** Presses the control the window named [probe] ("Print/Print"); false when it is not on screen. */
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
    }

    /** One page host in a small box, recording into a layer, for the capture check. */
    private class Spike : Scene(260, 360) {
        var kind by mutableStateOf(KneeboardPages.LEAVE)
        var layer: GraphicsLayer? = null
        /** the composition's own scope: it carries the frame clock the page waits on */
        var scope: CoroutineScope? = null
        val shownW = 200
        val shownH = 300
        var error: String? = null

        init {
            start {
                val l = rememberGraphicsLayer()
                layer = l
                scope = androidx.compose.runtime.rememberCoroutineScope()
                Box(Modifier.fillMaxSize().background(Hud.Bg)) {
                    KneeboardPageHost(kind, 7, 'L', Modifier.size(shownW.dp, shownH.dp), l)
                }
            }
        }

        fun capture(k: com.bmscompanion.app.ui.screens.wdp.KbPageKind): ByteArray? {
            onUi { kind = k }
            val job = onUi {
                scope!!.async {
                    KneeboardPages.preload(k.id)
                    KneeboardPages.settle()
                    KneeboardPages.capturePng(layer!!)
                }
            }
            until(30_000) { job.isCompleted }
            return if (job.isCompleted) runCatching { kotlinx.coroutines.runBlocking { job.await() } }.onFailure { error = "${it::class.simpleName}: ${it.message}" }.getOrNull() else null.also { error = "not done in 30 s" }
        }
    }
}

