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
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.KbFileResult
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.mission.KbOwner
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionRoute
import com.bmscompanion.app.ui.screens.wdp.KbPageKind
import com.bmscompanion.app.ui.screens.wdp.KneeboardExtraPages
import com.bmscompanion.app.ui.screens.wdp.KneeboardPageHost
import com.bmscompanion.app.ui.screens.wdp.KneeboardPages
import com.bmscompanion.app.ui.screens.wdp.KneeboardPrintSession
import com.bmscompanion.app.ui.screens.wdp.PlannerWindow
import com.bmscompanion.app.ui.screens.wdp.PlannerWindowHost
import com.bmscompanion.app.ui.screens.wdp.PlannerWindows
import com.bmscompanion.app.ui.screens.wdp.WdpAttackOverlay
import com.bmscompanion.app.ui.screens.wdp.WdpPage
import com.bmscompanion.app.ui.screens.wdp.WdpProbe
import com.bmscompanion.app.ui.screens.wdp.WdpSession
import com.bmscompanion.app.ui.screens.wdp.plannerMission
import com.bmscompanion.app.ui.screens.wdp.plannerTheater
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * `--planneroutcome printpages <outDir> <a copy of a BMS folder> [briefing.txt] [cartridge.ini]`: the Upd
 * Kneeboard pages beyond the card ones (R3-PLAN K3), in-process, against a copy.
 *
 * The Planner is given the copy's briefing, BMS's route beside the save (`Data/Campaign/Auto Save.ini`, when the copy
 * has one) and the copy's cartridge **with two threats, a line and two weapon target points added** (the copy made in
 * `<outDir>`; the given file is only read), the TOSS page is set to a TGT STPT, and then:
 *
 * 1. **Each kind drawn** at 1024 x 1536 and captured from the page host, as the Print window does, after its own
 *    preload: the picture kept, what the page says it drew, and a check that it drew it (rows, map pictures, airfield,
 *    attack figures, the inks a kind must have).
 * 2. **Each kind printed** through the Print window itself (This PC, the bridge on the copy): nine halves on pages 4-8,
 *    only those five files changed, each half in the file is the half the window drew (decoded, ≥ 30 dB), the owners
 *    read back as BMS Companion with the kind printed, and each half decoded to a picture at the pad's shape.
 *
 * Pictures go to `<outDir>/printpages/`. The copy's page files are written (that is the point); nothing else is.
 */
object PrintPagesOutcome {
    private const val SCENE_W = 1280
    private const val SCENE_H = 800

    /** The kinds, in the order they are printed on pages 4-8 (left, right, left, right…). */
    private val ORDER = listOf(
        KbKind.BRIEFING, KbKind.WEATHER, KbKind.TARGETS_LEFT, KbKind.TARGETS_RIGHT, KbKind.ROUTE_MAP,
        KbKind.ATTACK, KbKind.DEPARTURE, KbKind.ARRIVAL, KbKind.ALTERNATE,
    )

    fun run(outDir: File, more: List<String>): String {
        val out = StringBuilder()
        var fails = 0
        fun line(s: String) { out.appendLine(s) }
        fun check(ok: Boolean, what: String, detail: String = "") {
            if (!ok) fails++
            line((if (ok) "PASS " else "FAIL ") + what + if (!ok && detail.isNotEmpty()) " — $detail" else if (detail.isNotEmpty()) " ($detail)" else "")
        }
        val pics = File(outDir, "printpages").apply { mkdirs() }
        val root = more.getOrNull(0)?.let(::File) ?: Bridge.settings.value.BmsDirOverride?.let(::File)
        if (root == null || !root.isDirectory) return "FAIL: give a copy of a BMS folder: --planneroutcome printpages <outDir> <copy>\n"
        DevGuard.why(root)?.let { return "FAIL: $it — the check prints into its BMS folder, so it runs on a copy only.\n" }
        val briefing = more.getOrNull(1)?.let(::File) ?: File(root, "User/Briefings/briefing.txt")
        val cartridge = more.getOrNull(2)?.let(::File)
            ?: File(root, "User/Config").listFiles { f -> f.isFile && f.name.endsWith(".ini", true) && !f.name.endsWith("_Def.ini", true) }
                ?.firstOrNull { f -> runCatching { f.readText(Charsets.ISO_8859_1).contains("[STPT]") }.getOrDefault(false) }
        val routeFile = File(root, "Data/Campaign/Auto Save.ini").takeIf { f -> f.isFile && runCatching { f.readText(Charsets.ISO_8859_1).contains("[STPT]") }.getOrDefault(false) }

        line("Upd Kneeboard, the other pages, in-process (K3)")
        line("copy: ${root.name}; briefing: ${if (briefing.isFile) "read" else "none"}; cartridge: ${if (cartridge?.isFile == true) "read" else "none"}; BMS's route: ${routeFile?.name ?: "none"}")
        line("")

        // Skia's native library, loaded here on one thread before anything else: skiko marks it loaded before it has
        // finished loading it, so a first use on two threads at once (a page's pictures loading in the background while
        // a scene is made) fails with an UnsatisfiedLinkError on the second
        runCatching { org.jetbrains.skia.Surface.makeRasterN32Premul(1, 1).close() }
        val captures = CopyOnWriteArrayList<Triple<Int, Int, ByteArray>>()
        val oldEncode = Platform.encodePng
        Platform.encodePng = { img ->
            val b = com.bmscompanion.desktop.skiaPng(img)
            if (b != null) captures += Triple(img.width, img.height, b)
            b
        }
        val before = Bridge.settings.value
        val wasRunning = Bridge.running
        val wasProbe = WdpProbe.on
        var acquired = 0
        WdpProbe.on = true
        WdpProbe.clear()
        try {
            // ---------------------------------------------------------------- the Planner, filled
            line("== 0. The Planner, filled from the copy")
            val plus = cartridge?.takeIf { it.isFile }?.let { plusCartridge(it, File(outDir, "cartridge-plus.ini")) }
            line("  the cartridge with PPT 56 (SA2, 16.5 nm), PPT 57 (AAA, 3.3 nm), line 1 (4 points) and weapon targets 1-2 added: ${plus?.name ?: "no cartridge"}")
            val route = routeFile?.let { f ->
                runCatching {
                    val d = DtcParser.parse(f)
                    MissionRoute(file = f.name, save = f.nameWithoutExtension, kind = CampKind.CAMPAIGN, modified = f.lastModified(),
                        steerpoints = d.steerpoints, ppts = d.ppts, lines = d.lines, weaponTargets = d.weaponTargets)
                }.getOrNull()
            }
            val data = MissionData(
                briefing = briefing.takeIf { it.isFile }?.let { runCatching { BriefingParser.parse(it.readText()) }.getOrNull() },
                dtc = plus?.let { runCatching { DtcParser.parse(it) }.getOrNull() },
                route = route,
            )
            val theater = runBlocking { plannerTheater(Repo.index().theaters, "Korea KTO") }
            val mission = plannerMission(data, theater, null)
            // the DTC page reads the cartridge from the fixture, as the headless renders make it (before it is first made)
            WdpDtcFixture.file = plus
            WdpSession.dtcSource = WdpDtcFixture.source()
            WdpSession.theaterName = theater?.name
            WdpSession.theater = theater
            mission.coords?.let { c ->
                WdpSession.popup.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
                WdpSession.hadb.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
                WdpSession.appliedCoords = c
            }
            WdpSession.toss.onMission(mission)
            WdpSession.popup.onMission(mission)
            WdpSession.hadb.onMission(mission)
            runCatching { WdpWirings.feed(WdpSession.dataCard, mission, plus) }.onFailure { line("  (the card could not be filled: ${it.message})") }
            WdpSession.dtc.onMission(mission)
            val dtcUp = waitFor(20_000) { WdpSession.dtc.loaded != null }
            WdpSession.appliedMission = mission
            line("  ${WdpFixtureMission.describe(mission)}")
            check(dtcUp, "the DTC page loaded the cartridge", "file ${WdpSession.dtc.fileName ?: "none"}")
            // the TOSS page planned against STPT 3, the page the Print window's attack profile takes
            WdpSession.toss.onValue("numWaypoint", "3")
            com.bmscompanion.app.ui.screens.wdp.AttackFocus.touch(WdpPage.TOSS)
            WdpSession.page = WdpPage.DATACARD.name
            check(WdpAttackOverlay.current(WdpPage.TOSS, WdpSession.toss) != null, "the TOSS page has an attack on STPT 3")
            line("  the card's airfields: " + (0..2).joinToString(" / ") { WdpSession.dataCard.plan.tblApt[it].name ?: "-" })
            line("")

            // ---------------------------------------------------------------- 1. each kind drawn
            line("== 1. Each kind drawn at 1024 x 1536 and captured, as the Print window captures a page")
            check(ORDER.all { k -> KneeboardPages.of(k) != null }, "the Print window offers all nine kinds after the card pages",
                KneeboardPages.kinds.joinToString { it.id })
            val drawnPng = HashMap<String, ByteArray>()
            val spike = Spike()
            try {
                for (kind in ORDER) {
                    val k = KneeboardPages.of(kind) ?: continue
                    val t0 = System.nanoTime()
                    val png = spike.capture(k)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val img = png?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
                    val said = KneeboardExtraPages.drawn[kind] ?: "(nothing said)"
                    check(img != null && img.width == 1024 && img.height == 1536, "$kind: drawn at 1024 x 1536 in $ms ms", "${img?.width}x${img?.height}" + (spike.error?.let { "; $it" } ?: ""))
                    line("    the page: $said")
                    if (png == null || img == null) continue
                    drawnPng[kind] = png
                    File(pics, "drawn-$kind.png").writeBytes(png)
                    // ink: pixels well off white (a page of text has a few thousand, a map or a chart most of the page)
                    val ink = count(img) { r, g, b -> r + g + b < 600 }
                    check(ink > 1500 && inked(img) < 99, "$kind: the page has something on it ($ink dark pixels in a quarter sample, ${inked(img)}% off white)", "$ink")
                    when (kind) {
                        KbKind.BRIEFING -> check(num(said, "briefing lines filled") >= 4, "briefing: the Briefing page's boxes are filled", said)
                        KbKind.WEATHER -> check(num(said, "weather lines") >= 3 || data.briefing?.weather == null, "weather: the weather list is on it", said)
                        KbKind.TARGETS_LEFT -> {
                            val routeN = WdpSession.appliedMission?.slots?.count { it.point.n in 1..24 && it.point.action != -1 && (it.point.x != 0.0 || it.point.y != 0.0) } ?: 0
                            check(said.startsWith("$routeN of $routeN flight-plan steerpoints") && routeN > 0, "targets-l: every flight-plan steerpoint is listed ($routeN)", said)
                            check(Regex("(\\d+) of (\\d+) target points").find(said)?.let { it.groupValues[1] == it.groupValues[2] && it.groupValues[1] != "0" } == true,
                                "targets-l: every target point is listed", said)
                            check(said.contains("DTC page"), "targets-l: from the cartridge as the DTC page holds it", said)
                        }
                        KbKind.TARGETS_RIGHT -> check(said.startsWith("2 of 2 PPTs, 4 of 4 line points, 2 of 2 weapon targets"), "targets-r: the two PPTs, the line's four points and the two weapon targets", said)
                        KbKind.ROUTE_MAP -> {
                            val m = Regex("(\\d+) of (\\d+) pictures").find(said)
                            check(m != null && m.groupValues[1] == m.groupValues[2] && m.groupValues[2].toInt() > 1, "routemap: every map picture it needs was there when it was captured", said)
                            val blue = count(img) { r, g, b -> b > 110 && r < 60 && g < 100 }
                            val red = count(img) { r, g, b -> r > 150 && g < 90 && b < 90 }
                            check(blue > 400, "routemap: the flight plan is drawn in its blue ($blue px)", "$blue px")
                            check(red > 200, "routemap: threats and targets are drawn in red ($red px)", "$red px")
                            val colours = variety(img)
                            val table = drawnPng[KbKind.TARGETS_LEFT]?.let { ImageIO.read(ByteArrayInputStream(it)) }?.let { variety(it) } ?: 0
                            check(colours > 80 && colours > 3 * table, "routemap: the chart map is under it ($colours colours, the steerpoint table has $table)", "$colours colours")
                        }
                        KbKind.ATTACK -> {
                            check(said.startsWith("TOSS, TGT STPT 3:") && num(said, "figures") >= 8 && said.endsWith("map drawn"), "attack: the TOSS page's profile, DED data, map and figures", said)
                        }
                        else -> {
                            check(Regex("(\\d+) runways").find(said)?.groupValues?.get(1)?.toInt()?.let { it >= 1 } == true, "$kind: the airfield's chart (runways, spots)", said)
                            val runway = count(img) { r, g, b -> abs(r - 0x3A) < 14 && abs(g - 0x42) < 14 && abs(b - 0x47) < 14 }
                            check(runway > 1500, "$kind: the runways are drawn in the day inks ($runway px)", "$runway px")
                        }
                    }
                }
            } finally { spike.close() }
            line("")

            // ---------------------------------------------------------------- 2. each kind printed
            line("== 2. Each kind printed through the Print window (This PC, the bridge on the copy)")
            val ez = File(root, "Tools/EZBoards")
            Bridge.update { it.copy(BmsDirOverride = root.path, AutoEzBoardsOnPrint = false, EzBoardsDir = ez.takeIf { d -> d.isDirectory }?.path) }
            Bridge.startForCheck()
            MissionLink.useThisPc()
            MissionLink.acquire(); acquired++
            PlannerWindows.show(PlannerWindow.PRINT)
            val stage = Stage(SCENE_W, SCENE_H)
            try {
                val online = stage.until(20_000) { MissionLink.state.value is LinkState.Online && KneeboardPrintSession.state != null && !KneeboardPrintSession.loading }
                check(online, "the window reads the pages once the link is up", "${MissionLink.state.value}")
                val pages = (4..8).toList()
                check(pages.all { KneeboardPrintSession.printable(KneeboardPrintSession.slot(it)) }, "pages 4-8 can be printed into", pages.joinToString { "${it}: ${KneeboardPrintSession.slot(it)?.problem ?: "ok"}" })
                val plan = ORDER.mapIndexed { i, k -> Triple(pages[i / 2], if (i % 2 == 0) 'L' else 'R', k) }
                stage.onUi {
                    KneeboardPrintSession.leaveAll()
                    for ((n, side, k) in plan) KneeboardPrintSession.set(n, side, k)
                    KneeboardPrintSession.selN = 6; KneeboardPrintSession.selSide = 'L'
                    KneeboardPrintSession.results = emptyList()
                }
                stage.frames(20, 30)
                stage.shot(File(pics, "window-planned.png"))
                val here = KneeboardPrint.place(root, Bridge.install.theater)
                val shaBefore = shaTree(root)
                captures.clear()
                val t0 = System.currentTimeMillis()
                check(stage.click("Print/Print"), "the Print button is pressed", "")
                val done = stage.until(240_000) { !KneeboardPrintSession.busy && KneeboardPrintSession.results.isNotEmpty() && KneeboardPrintSession.progress == null }
                val res = KneeboardPrintSession.results
                check(done, "the print finishes", "busy ${KneeboardPrintSession.busy}, ${res.size} answers")
                res.forEach { line("  ${it.file}: ${it.status}${it.reason?.let { r -> " — $r" } ?: ""}") }
                line("  took ${System.currentTimeMillis() - t0} ms for ${captures.size} halves")
                val files = pages.map { KneeboardPrint.pageName(it) }
                check(res.map { it.file }.sorted() == files.sorted() && res.all { it.status == KbFileResult.WRITTEN }, "five files written: ${files.joinToString()}", res.joinToString { "${it.file} ${it.status}" })
                check(captures.size == ORDER.size && captures.all { it.first == 1024 && it.second == 1536 }, "nine halves drawn at 1024 x 1536", captures.joinToString { "${it.first}x${it.second}" })
                val shaAfter = shaTree(root)
                val changed = (shaBefore.keys + shaAfter.keys).filter { shaBefore[it] != shaAfter[it] }.sorted()
                check(changed.map { it.substringAfterLast('/') }.sorted() == files.sorted(), "only those files changed in the copy, and nothing new appeared", changed.joinToString())
                for ((i, pr) in plan.withIndex()) {
                    val (n, side, kind) = pr
                    val f = here.page(n) ?: continue
                    val bytes = f.readBytes()
                    val info = Dds.header(bytes)
                    val sideI = if (side == 'L') 0 else 1
                    val capBytes = captures.getOrNull(i)?.third
                    val cap = capBytes?.let { ImageIO.read(ByteArrayInputStream(it)) }
                    if (cap == null) { check(false, "$kind: a capture to compare with", "none"); continue }
                    capBytes.let { File(pics, "printed-capture-$kind.png").writeBytes(it) }
                    val want = KneeboardPrint.raster(cap, info.width / 2, info.height)
                    val got = Dds.decode(bytes, info, 0, sideI * info.width / 2, 0, info.width / 2, info.height)
                    val db = Dds.psnr(want, got)
                    check(db >= 30.0, "$kind on page $n ${if (side == 'L') "left" else "right"}: the file holds the half the window drew (%.1f dB, ${info.format})".format(db), "%.1f dB".format(db))
                    KneeboardPrint.halfImage(bytes, sideI, 512)?.let { ImageIO.write(it, "png", File(pics, "as-seen-$kind.png")) }
                    // the same page drawn alone in part 1 and in the window's print: the same page
                    drawnPng[kind]?.let { ImageIO.read(ByteArrayInputStream(it)) }?.let { alone ->
                        val a = KneeboardPrint.raster(alone, 512, 768)
                        val b = KneeboardPrint.raster(cap, 512, 768)
                        val same = Dds.psnr(a, b)
                        check(same >= 24.0, "$kind: the window's print draws the page as it was drawn alone (%.1f dB)".format(same), "%.1f dB".format(same))
                    }
                }
                for (n in pages) {
                    val f = here.page(n) ?: continue
                    val bytes = f.readBytes()
                    runCatching { ImageIO.write(Dds.image(bytes, Dds.header(bytes)), "png", File(pics, "file-${f.nameWithoutExtension}.png")) }
                }
                val st = KneeboardPrintSession.state
                val owners = plan.map { (n, side, k) ->
                    val s = st?.pages?.firstOrNull { it.n == n }
                    Triple(k, if (side == 'L') s?.left else s?.right, if (side == 'L') s?.leftKind else s?.rightKind)
                }
                check(owners.all { it.second == KbOwner.COMPANION && it.third == it.first }, "the owners read back: BMS Companion, with the kind printed on each half",
                    owners.filter { it.second != KbOwner.COMPANION || it.third != it.first }.joinToString { "${it.first}: ${it.second}/${it.third}" })
                stage.frames(20, 30)
                stage.shot(File(pics, "window-printed.png"))
            } finally {
                stage.close()
            }
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
        }
        line("")
        line(if (fails == 0) "ALL PASS" else "$fails FAIL")
        return out.toString()
    }

    /**
     * The cartridge [src] with two threats (PPT 56, 57), line 1 and two weapon target points put in, near BMS's route
     * for the fixture's flight (Osan, the CAP west of it), written to [dst]. The source is only read.
     */
    private fun plusCartridge(src: File, dst: File): File {
        val text = src.readText(Charsets.ISO_8859_1)
        val set = linkedMapOf(
            "ppt_0" to "1140000.000000, 1300000.000000, 0.000000, 100000.000000, SA2",
            "ppt_1" to "1122000.000000, 1450000.000000, 0.000000, 20000.000000, AAA",
            "lineSTPT_0" to "1170000.000000, 1250000.000000, 0.000000",
            "lineSTPT_1" to "1180000.000000, 1300000.000000, 0.000000",
            "lineSTPT_2" to "1175000.000000, 1350000.000000, 0.000000",
            "lineSTPT_3" to "1185000.000000, 1400000.000000, 0.000000",
            "wpntarget_0" to "1150000.000000, 1380000.000000, -120.000000, -1, Bridge",
            "wpntarget_1" to "1150500.000000, 1381000.000000, -118.000000, -1, Depot",
        )
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(nl).toMutableList()
        val left = set.keys.toMutableSet()
        for (i in lines.indices) {
            val k = lines[i].substringBefore('=').trim()
            set[k]?.let { lines[i] = "$k=$it"; left -= k }
        }
        if (left.isNotEmpty()) {
            val at = lines.indexOfFirst { it.trim().equals("[STPT]", true) }
            if (at >= 0) lines.addAll(at + 1, left.map { "$it=${set[it]}" })
        }
        dst.parentFile?.mkdirs()
        dst.writeText(lines.joinToString(nl), Charsets.ISO_8859_1)
        return dst
    }

    private fun num(said: String, after: String): Int = Regex("(\\d+) $after").find(said)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    /** The share of the page that is not white, sampled every 8 pixels, in per cent. */
    private fun inked(img: BufferedImage): Int {
        var n = 0; var all = 0
        for (y in 0 until img.height step 8) for (x in 0 until img.width step 8) { all++; if ((img.getRGB(x, y) and 0xFFFFFF) != 0xFFFFFF) n++ }
        return n * 100 / all.coerceAtLeast(1)
    }

    private fun count(img: BufferedImage, test: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until img.height step 2) for (x in 0 until img.width step 2) {
            val c = img.getRGB(x, y)
            if (test((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)) n++
        }
        return n
    }

    /** How many different colours the page has (to 4 bits a channel): a map has many, a table a handful. */
    private fun variety(img: BufferedImage): Int {
        val seen = HashSet<Int>()
        for (y in 0 until img.height step 4) for (x in 0 until img.width step 4) seen += (img.getRGB(x, y) shr 4) and 0x0F0F0F
        return seen.size
    }

    private fun abs(v: Int) = if (v < 0) -v else v

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < ms) { if (runCatching(cond).getOrDefault(false)) return true; Thread.sleep(50) }
        return false
    }

    private fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun shaTree(root: File): Map<String, String> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to sha(it.readBytes()) }

    /** A scene of its own, whose composition, frames and coroutines all run on one thread, as a window's do. */
    private open class Scene(val w: Int, val h: Int) : AutoCloseable {
        private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "printpages-ui").apply { isDaemon = true } }
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
                Box(Modifier.fillMaxSize().background(Hud.Bg)) {
                    KneeboardPageHost(kind, 7, 'L', Modifier.size(200.dp, 300.dp), l)
                }
            }
        }

        fun capture(k: KbPageKind): ByteArray? {
            error = null
            onUi { kind = k }
            val job = onUi {
                scope!!.async {
                    KneeboardPages.preload(k.id)
                    KneeboardExtraPages.preload(k.id)
                    KneeboardPages.settle()
                    KneeboardPages.capturePng(layer!!)
                }
            }
            until(60_000) { job.isCompleted }
            return if (job.isCompleted) runCatching { runBlocking { job.await() } }.onFailure { error = "${it::class.simpleName}: ${it.message}" }.getOrNull()
            else null.also { error = "not done in 60 s" }
        }
    }
}
