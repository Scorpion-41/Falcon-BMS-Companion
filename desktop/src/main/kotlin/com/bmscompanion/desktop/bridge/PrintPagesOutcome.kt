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
import com.bmscompanion.app.data.mission.TargetPictureApi
import com.bmscompanion.app.ui.screens.mission.TargetPics
import com.bmscompanion.app.ui.screens.wdp.KbPageKind
import com.bmscompanion.app.ui.screens.wdp.KbTargetChoice
import com.bmscompanion.app.ui.screens.wdp.KneeboardTargets
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
 * 3. **Target pictures** (1.4.0, [targetPictures]): two halves of the mission's targets, a choice made in the picker and
 *    kept across a launch, every picture inked, and a half the PC cannot draw refused. The copy's mission must name
 *    targets that have models (its `Data\TerrData\Objects\Models`), or only the refusal is checked.
 * 3b. **Target pictures from the Planner's flight** (1.4.1, [plannerTargets]): WDP mode, nothing populated, no Briefing
 *    card — the picker lists the flight's targets, Add… adds one, a print draws them (a copy with models, as 3; run a
 *    copy whose briefing is another theater's with `BMSC_DEV_THEATER` set, and its KoreaObj pages in that theater).
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
        // 1.4.0
        KbKind.ROUTE_MAP_DARK, KbKind.SCHEDULE,
    )

    fun run(outDir: File, more: List<String>): String {
        val out = StringBuilder()
        var fails = 0
        fun line(s: String) { out.appendLine(s) }
        fun check(ok: Boolean, what: String, detail: String = "") {
            if (!ok) fails++
            line((if (ok) "PASS " else "FAIL ") + what + if (!ok && detail.isNotEmpty()) " — $detail" else if (detail.isNotEmpty()) " ($detail)" else "")
        }
        // a table's rows are on the page, not only laid out: the dark ink (text) between the title and the page's foot,
        // some 80 sampled pixels a row at least (an empty row, the 1.3.9 bug, has its grey band and no ink)
        fun tableInked(img: BufferedImage, rows: Int) {
            var dark = 0
            for (y in (img.height * 8 / 100) until (img.height * 80 / 100) step 2) for (x in 0 until img.width step 2) {
                val c = img.getRGB(x, y)
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                if (maxOf(r, g, b) < 120 || (r > 150 && g < 90 && b < 90)) dark++
            }
            check(rows > 0 && dark >= rows * 80, "the table's $rows rows are drawn ($dark dark pixels in the body)", "$dark dark pixels for $rows rows")
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
            check(ORDER.all { k -> KneeboardPages.of(k) != null }, "the Print window offers all ${ORDER.size} kinds after the card pages",
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
                    // a dark page is all "off white" by design: only the day pages are held to under 99 %
                    check(ink > 1500 && (kind == KbKind.ROUTE_MAP_DARK || inked(img) < 99), "$kind: the page has something on it ($ink dark pixels in a quarter sample, ${inked(img)}% off white)", "$ink")
                    when (kind) {
                        KbKind.BRIEFING -> check(num(said, "briefing lines filled") >= 4, "briefing: the Briefing page's boxes are filled", said)
                        KbKind.WEATHER -> check(num(said, "weather lines") >= 3 || data.briefing?.weather == null, "weather: the weather list is on it", said)
                        KbKind.TARGETS_LEFT -> {
                            val routeN = WdpSession.appliedMission?.slots?.count { it.point.n in 1..24 && it.point.action != -1 && (it.point.x != 0.0 || it.point.y != 0.0) } ?: 0
                            check(said.startsWith("$routeN of $routeN flight-plan steerpoints") && routeN > 0, "targets-l: every flight-plan steerpoint is listed ($routeN)", said)
                            check(Regex("(\\d+) of (\\d+) target points").find(said)?.let { it.groupValues[1] == it.groupValues[2] && it.groupValues[1] != "0" } == true,
                                "targets-l: every target point is listed", said)
                            check(said.contains("DTC page"), "targets-l: from the cartridge as the DTC page holds it", said)
                            val rows = KneeboardExtraPages.rows[kind].orEmpty()
                            val stpts = WdpSession.appliedMission?.slots?.filter { it.point.n in 1..24 && (it.point.x != 0.0 || it.point.y != 0.0) }?.map { it.point.n.toString() }.orEmpty()
                            check(stpts.isNotEmpty() && stpts.all { n -> rows.any { it.startsWith("$n | ") } }, "targets-l: a row for each placed steerpoint (${rows.size} rows)", rows.take(3).joinToString(" / "))
                            tableInked(img, rows.size)
                        }
                        KbKind.TARGETS_RIGHT -> {
                            check(said.startsWith("2 of 2 PPTs, 4 of 4 line points, 2 of 2 weapon targets"), "targets-r: the two PPTs, the line's four points and the two weapon targets", said)
                            val rows = KneeboardExtraPages.rows[kind].orEmpty()
                            check(listOf("Bridge", "Depot", "Line 1").all { w -> rows.any { w in it } } && rows.any { it.startsWith("56 | ") } && rows.any { it.startsWith("57 | ") },
                                "targets-r: the rows name PPT 56 and 57, line 1 and the weapon targets Bridge and Depot", rows.joinToString(" / "))
                            tableInked(img, rows.size)
                        }
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
                        KbKind.ROUTE_MAP_DARK -> {
                            val m = Regex("(\\d+) of (\\d+) pictures").find(said)
                            check(said.contains("/dark ") && m != null && m.groupValues[1] == m.groupValues[2] && m.groupValues[2].toInt() > 1,
                                "routemap-dark: on the dark map style, every picture there when captured", said)
                            val dark = count(img) { r, g, b -> maxOf(r, g, b) < 70 }
                            val all = (img.width / 2) * (img.height / 2)
                            check(dark * 100 / all >= 60, "routemap-dark: the page is dark (${dark * 100 / all}% of it under 70)", "${dark * 100 / all}%")
                            val route = count(img) { r, g, b -> b > 200 && g > 160 && r in 100..180 }
                            check(route > 400, "routemap-dark: the flight plan is drawn in its light blue ($route px)", "$route px")
                        }
                        KbKind.SCHEDULE -> {
                            val rows = KneeboardExtraPages.rows[kind].orEmpty()
                            val own = data.briefing?.overview?.flight
                            check(rows.isNotEmpty() && own != null && rows.any { it.contains(own, ignoreCase = true) },
                                "schedule: the departures are listed, your own flight among them (${rows.size} rows)", said + " / " + rows.take(3).joinToString(" / "))
                            tableInked(img, rows.size)
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
            // in WDP mode: the Planner's pages are printed there only (1.4.1: EZBoards mode offers none of them)
            Bridge.update { it.copy(BmsDirOverride = root.path, AutoEzBoardsOnPrint = false, EzBoardsDir = ez.takeIf { d -> d.isDirectory }?.path, MissionSource = com.bmscompanion.app.data.mission.MissionMode.WDP) }
            Bridge.startForCheck()
            MissionLink.useThisPc()
            MissionLink.acquire(); acquired++
            PlannerWindows.show(PlannerWindow.PRINT)
            val stage = Stage(SCENE_W, SCENE_H)
            try {
                val online = stage.until(20_000) { MissionLink.state.value is LinkState.Online && KneeboardPrintSession.state != null && !KneeboardPrintSession.loading }
                check(online, "the window reads the pages once the link is up", "${MissionLink.state.value}")
                val pages = (4 until 4 + (ORDER.size + 1) / 2).toList()
                check(pages.all { KneeboardPrintSession.printable(KneeboardPrintSession.slot(it)) }, "pages ${pages.first()}-${pages.last()} can be printed into", pages.joinToString { n -> KneeboardPrintSession.slot(n).let { s -> "$n: " + (if (s == null) "no slot" else if (s.missing) "missing" else s.problem ?: "ok") } })
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
                check(res.map { it.file }.sorted() == files.sorted() && res.all { it.status == KbFileResult.WRITTEN }, "${files.size} files written: ${files.joinToString()}", res.joinToString { "${it.file} ${it.status}" })
                check(captures.size == ORDER.size && captures.all { it.first == 1024 && it.second == 1536 }, "${ORDER.size} halves drawn at 1024 x 1536", captures.joinToString { "${it.first}x${it.second}" })
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
                // the parts after this one read the printed briefing's mission, in EZBoards mode
                Bridge.update(reapply = false) { it.copy(MissionSource = com.bmscompanion.app.data.mission.MissionMode.EZBOARDS) }
            }
            line("")
            fails += targetPictures(root, outDir, pics, captures, ::line)
            line("")
            fails += plannerTargets(outDir, pics, captures, ::line)
            line("")
            // 4. the Noteboard and Upd Kneeboard from the Kneeboards page (1.4.1; alone: --planneroutcome notes), last:
            // it fills the Planner's pages from the printed briefing as the window does outside the Planner
            fails += NoteboardOutcome.checks(root, outDir, File(pics, "notes").apply { mkdirs() }, ::line)
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
     * 3. **Target pictures** (1.4.0): the mission's targets as the Briefing's card lists them, on two halves of one page
     * (the first four of the list's default order and the next four), one half's choice changed in the picker and kept
     * with the plan across a launch, the page printed with every picture drawn (ink, names), the pictures and the picker
     * at PC and phone size; then a half whose target the PC cannot draw, which is refused and leaves its file as it is.
     * Runs in the bridge part 2 started (This PC). Answers how many checks failed.
     */
    private fun targetPictures(root: File, outDir: File, pics: File, captures: CopyOnWriteArrayList<Triple<Int, Int, ByteArray>>, line: (String) -> Unit): Int {
        var fails = 0
        fun check(ok: Boolean, what: String, detail: String = "") {
            if (!ok) fails++
            line((if (ok) "PASS " else "FAIL ") + what + if (!ok && detail.isNotEmpty()) " — $detail" else if (detail.isNotEmpty()) " ($detail)" else "")
        }
        line("== 3. Target pictures: the mission's targets, two views each, chosen per half")
        val s = KneeboardPrintSession
        val t = KneeboardTargets
        val oldCache = TargetPictures.cacheOverride
        TargetPictures.cacheOverride = File(outDir, "target-cache")
        val stage = Stage(SCENE_W, SCENE_H)
        try {
            val online = stage.until(20_000) { MissionLink.state.value is LinkState.Online && s.state != null && !s.loading }
            check(online, "the window is up with the link", "${MissionLink.state.value}")
            runBlocking { t.load(force = true) }
            val set = t.set
            val mission = set?.mission.orEmpty()
            TargetPics.saveAdded(mission, emptyList()); TargetPics.saveRemoved(mission, emptyList())
            stage.onUi { t.choices.clear(); s.leaveAll() }
            val all = t.entries()
            line("  the mission's list: ${set?.groups?.size ?: 0} groups, ${all.size} entries" + (set?.error?.let { " — $it" } ?: t.error?.let { " — $it" } ?: ""))
            check(KneeboardPages.of(KbKind.TARGET_PICTURES) != null, "the Print window offers Target pictures")
            val n = (10..16).firstOrNull { s.printable(s.slot(it)) && s.printable(s.slot(it + 1)) } ?: 10
            if (all.isEmpty()) {
                line("  (the copy's mission names no target with a model: only the refusal is checked; use a copy holding the models)")
            } else {
                // the picker opened as the half's own menu opens it (the menu's last items lie below an 800-px scene)
                stage.onUi { s.set(n, 'L', KbKind.TARGET_PICTURES); t.pick(n, 'L') }
                stage.frames(10, 30)
                check(t.picking?.n == n && t.picking?.side == 'L', "the picker opens on page $n left", "${t.picking?.n}${t.picking?.side}")
                stage.onUi { s.set(n, 'R', KbKind.TARGET_PICTURES) }
                val order = t.defaultOrder(all).map { it.id }
                val alloc = t.allocation()
                check(alloc["${n}L"] == order.take(4) && alloc["${n}R"] == order.drop(4).take(4),
                    "page $n left takes the list's first four, the right half the next ${order.drop(4).take(4).size}",
                    "${alloc["${n}L"]} / ${alloc["${n}R"]}")
                line("  default order: " + t.defaultOrder(all).take(8).joinToString(" / ") { it.item.name + (it.item.role?.let { r -> " ($r)" } ?: "") })
                stage.until(60_000) { t.itemsFor(n, 'L').all { e -> TargetPictureApi.VIEWS.all { (v, _) -> t.images[t.path(set!!.theater, e.item.key, v)] != null } } }
                stage.shot(File(pics, "targets-picker-pc.png"))
                // a choice of its own: the list's last entry in place of the half's fourth
                val chosen = (order.take(3) + order.last()).distinct()
                stage.onUi { t.picking?.draft?.let { d -> d.clear(); d.addAll(chosen) } }
                check(stage.click("Print/Tgt/Done") && t.picking == null, "Done closes the picker")
                check(t.choices["${n}L"]?.ids == chosen && !t.isDefault(n, 'L'), "page $n left keeps its own choice", "${t.choices["${n}L"]?.ids}")
                // kept with the plan across a launch
                stage.onUi { s.reloadSaved() }
                check(s.kind(n, 'L') == KbKind.TARGET_PICTURES && t.choices["${n}L"]?.ids == chosen, "the choice is kept with the plan (kb_print_plan) across a launch")
                check(t.allocation()["${n}R"]?.none { it in chosen } == true, "the right half takes the next of the list, none of the left's")
                // a choice made for another mission is dropped: that half follows the list again
                val kept = t.choices["${n}L"]!!
                stage.onUi { t.choices["${n}L"] = KbTargetChoice("another-mission", kept.ids) }
                check(t.isDefault(n, 'L') && t.allocation()["${n}L"] == order.take(4), "a choice made for another mission gives way to the list")
                stage.onUi { t.choices["${n}L"] = kept }
                // printed
                stage.onUi { s.results = emptyList(); s.selN = n; s.selSide = 'L' }
                val shaBefore = shaTree(root)
                captures.clear()
                check(stage.click("Print/Print"), "the Print button is pressed")
                val done = stage.until(240_000) { !s.busy && s.results.isNotEmpty() && s.progress == null }
                s.results.forEach { line("  ${it.file}: ${it.status}${it.reason?.let { r -> " — $r" } ?: ""}") }
                check(done && s.results.size == 1 && s.results[0].status == KbFileResult.WRITTEN, "page $n written with both target halves")
                for ((i, side) in listOf('L', 'R').withIndex()) {
                    val want = t.itemsFor(n, side)
                    val said = t.drawn["$n$side"].orEmpty()
                    line("    $n$side: $said")
                    check(want.isNotEmpty() && said.startsWith("${want.size} targets, ${want.size * 2} of ${want.size * 2} pictures: " + want.joinToString(" / ") { it.item.name }),
                        "page $n ${if (side == 'L') "left" else "right"}: ${want.size} targets × 2 views drawn, names on the page", said)
                    val cap = captures.getOrNull(i)?.third
                    val img = cap?.let { ImageIO.read(ByteArrayInputStream(it)) }
                    if (cap != null) File(pics, "targets-page-$n$side.png").writeBytes(cap)
                    // the pictures are the targeting pod's: dark ground, light models
                    val dark = img?.let { count(it) { r, g, b -> maxOf(r, g, b) < 90 } } ?: 0
                    val light = img?.let { count(it) { r, g, b -> minOf(r, g, b) > 170 && maxOf(r, g, b) < 250 } } ?: 0
                    check(img != null && img.width == 1024 && dark > want.size * 2 * 8_000 && light > want.size * 2 * 400,
                        "page $n ${if (side == 'L') "left" else "right"}: the pictures are inked ($dark dark, $light light pixels in a quarter sample)", "$dark / $light")
                }
                val changed = shaTree(root).let { after -> (shaBefore.keys + after.keys).filter { shaBefore[it] != after[it] } }
                check(changed.isNotEmpty() && changed.all { it.substringAfterLast('/') == KneeboardPrint.pageName(n) }, "only page $n's file changed", changed.joinToString())
                val slot = s.state?.pages?.firstOrNull { it.n == n }
                check(slot?.leftKind == KbKind.TARGET_PICTURES && slot.rightKind == KbKind.TARGET_PICTURES, "the owners read back: Target pictures on both halves", "${slot?.leftKind}/${slot?.rightKind}")
                // the phone: the picker as a full-screen sheet with a finger's rows
                val touch = com.bmscompanion.app.data.Platform.touchFirst
                com.bmscompanion.app.data.Platform.touchFirst = true
                val phone = Stage(393, 800, 2f)
                try {
                    phone.frames(10, 30)
                    phone.onUi { t.pick(n, 'R') }
                    phone.frames(20, 30)
                    phone.shot(File(pics, "targets-picker-phone.png"))
                    phone.onUi { t.picking = null }
                } finally { phone.close(); com.bmscompanion.app.data.Platform.touchFirst = touch }
            }
            // a half the PC cannot draw: refused with a sentence, its file left as it is
            stage.onUi {
                s.leaveAll()
                if (set != null) {
                    TargetPics.saveAdded(mission, listOf(com.bmscompanion.app.data.mission.TargetItem(key = "ct999999", name = "No such model", category = "Test", role = "added")))
                    s.set(n + 1, 'R', KbKind.TARGET_PICTURES)
                    t.choices["${n + 1}R"] = KbTargetChoice(mission, listOf("added|No such model#ct999999"))
                } else s.set(n + 1, 'R', KbKind.TARGET_PICTURES)
                s.results = emptyList()
            }
            val before = shaTree(root)
            check(stage.click("Print/Print"), "Print pressed for a half the PC cannot draw")
            val done = stage.until(120_000) { !s.busy && s.results.isNotEmpty() && s.progress == null }
            s.results.forEach { line("  ${it.file}: ${it.status}${it.reason?.let { r -> " — $r" } ?: ""}") }
            val r = s.results.firstOrNull()
            check(done && s.results.size == 1 && r?.status == KbFileResult.REFUSED && r.file.endsWith("right knee") &&
                (r.reason.orEmpty().contains("could not draw No such model") || set == null || all.isEmpty()),
                "the half is refused with a sentence", "${r?.status}: ${r?.reason}")
            check(shaTree(root) == before, "no file changed")
            TargetPics.saveAdded(mission, emptyList())
            stage.onUi { t.choices.clear(); s.leaveAll() }
        } catch (e: Throwable) {
            fails++
            line("FAIL: target pictures threw ${e::class.java.simpleName}: ${e.message}")
            e.stackTrace.take(10).forEach { line("    at $it") }
        } finally {
            stage.close()
            TargetPictures.cacheOverride = oldCache
        }
        return fails
    }

    /**
     * 3b. **Target pictures from the Planner's own flight** (1.4.1, a pilot's report: "you must first add target pictures
     * from the main Briefing"): WDP mode, nothing populated, the Briefing card never opened, and the flight of the save
     * that holds the printed one open in the Planner (as Open mission… → Pick a flight leaves it). The PC's old answer
     * for the Mission section ("Populate from Planner first.") is shown, then the picker lists the Planner flight's
     * targets, its **Add…** opens the Add list and a target added there lands on the list and on the half, and a print
     * draws the half with every picture. Answers how many checks failed.
     */
    private fun plannerTargets(outDir: File, pics: File, captures: CopyOnWriteArrayList<Triple<Int, Int, ByteArray>>, line: (String) -> Unit): Int {
        var fails = 0
        fun check(ok: Boolean, what: String, detail: String = "") {
            if (!ok) fails++
            line((if (ok) "PASS " else "FAIL ") + what + if (!ok && detail.isNotEmpty()) " — $detail" else if (detail.isNotEmpty()) " ($detail)" else "")
        }
        line("== 3b. Target pictures from the flight open in the Planner (WDP mode, not populated, no Briefing card)")
        val s = KneeboardPrintSession
        val t = KneeboardTargets
        val p = com.bmscompanion.app.ui.screens.wdp.PlannerMissionState
        val oldCache = TargetPictures.cacheOverride
        TargetPictures.cacheOverride = File(outDir, "target-cache")
        val oldMode = MissionSource.mode()
        val old = listOf(p.source, p.theater, p.ref, p.kind, p.flight)
        val stage = Stage(SCENE_W, SCENE_H)
        try {
            // the flight: the save that provably holds the printed one (EZBoards mode's own proof), as the Planner opens it
            val set = Theaters.of(Bridge.install)
            val v = MissionDtcFile.verdict()
            val th = v.theater?.let { set?.byName(it) } ?: set?.current(Bridge.install.theater)
            val saveFile = v.save
            val b = Bridge.currentBriefing()
            val fid = if (set != null && th != null && saveFile != null && b != null)
                MissionDtcFile.briefedFlight(CampaignArchive.cached(saveFile, CampaignArchive.names(set, th)), b)?.id?.toString() else null
            if (th == null || saveFile == null || fid == null) {
                line("  (the copy's briefing is not provably a flight of one of its saves: nothing to plan from — use a copy whose save holds the printed flight)")
                return 0
            }
            val ref = com.bmscompanion.app.data.mission.CampRef(th.name, saveFile.name, fid)
            val flight = (CampaignFiles.flight(CampaignFiles.context(), th.name, saveFile.name, fid) as? CampaignFiles.Answer.Ok)?.value
            check(flight != null, "the Planner's flight is read: ${th.name} › ${saveFile.name} › ${flight?.row?.callsign ?: fid}")
            // WDP mode, nothing populated, nothing added or removed on any Briefing card of this device
            Bridge.update(reapply = false) { it.copy(MissionSource = com.bmscompanion.app.data.mission.MissionMode.WDP) }
            MissionSource.discardSnapshot()
            TargetPics.saveAdded("none", emptyList()); TargetPics.saveRemoved("none", emptyList())
            stage.onUi {
                Snapshot.withMutableSnapshot {
                    p.theater = th.name; p.ref = ref; p.flight = flight; p.kind = null
                    p.source = com.bmscompanion.app.ui.screens.wdp.PlannerMissionState.SAVE
                }
                t.choices.clear(); s.leaveAll()
            }
            check(MissionSource.wdp && MissionSource.populated() == null, "WDP mode, not populated")
            // the Print window up (a part before this one may have closed it)
            stage.onUi { if (PlannerWindows.open != PlannerWindow.PRINT) PlannerWindows.show(PlannerWindow.PRINT) }
            check(stage.until(30_000) { MissionLink.state.value is LinkState.Online && s.state != null && !s.loading }, "the Print window is up with the link")
            // the pages read again in WDP mode (the window holds one plan per mode and asks for the Planner's flight's
            // targets in WDP mode only, 1.4.1): the state it had was read in EZBoards mode
            stage.onUi { runBlocking { s.load() } }
            check(s.wdpMode(), "the window has the pages of WDP mode", "${s.state?.mode}")
            val section = TargetPictures.missionSet()
            line("  the Mission section's answer (the Briefing card's): ${section.groups.size} groups" + (section.error?.let { " — $it" } ?: ""))
            check(section.error != null && section.groups.isEmpty(), "the Mission section has no targets before a Populate (what the pilot ran into)", "${section.groups.size} groups")
            check(t.query().contains("save=") && t.query().contains("flight="), "the picker asks for the Planner's flight", t.query())
            runBlocking { t.load(force = true) }
            val ts = t.set
            val all = t.entries()
            line("  the Planner flight's list: ${ts?.groups?.size ?: 0} groups, ${all.size} entries, mission ${ts?.mission}" + (ts?.error?.let { " — $it" } ?: t.error?.let { " — $it" } ?: ""))
            ts?.groups?.forEach { g -> line("    ${g.kind.uppercase()} ${g.title}${g.note?.let { " · $it" } ?: ""}: " + g.items.joinToString(", ") { it.name }) }
            if (ts != null && ts.error == null && ts.mission.startsWith("plan-") && all.isEmpty()) {
                line("PASS the picker asks the PC for the Planner flight's list, which answers with no error (${ts.mission})")
                line("  (the copy holds none of the flight's targets' models: the rest is checked on a copy that does, as part 3)")
                return fails
            }
            check(ts != null && ts.error == null && ts.mission.startsWith("plan-") && all.isNotEmpty(), "the picker's list is the Planner flight's, with no Populate and no Briefing card", "${ts?.mission}: ${ts?.error}")
            val n = (10..16).firstOrNull { s.printable(s.slot(it)) } ?: 10
            stage.onUi { s.set(n, 'L', KbKind.TARGET_PICTURES); t.pick(n, 'L') }
            stage.frames(10, 30)
            check(t.picking?.n == n && t.picking?.draft?.isNotEmpty() == true, "the picker opens on page $n left with the flight's first targets", "${t.picking?.draft?.toList()}")
            // Add…: the button opens the Add list; a target picked there joins the list and the half
            check(stage.click("Print/Tgt/Add") && t.adding, "Add… opens the Add list")
            stage.onUi { t.adding = false }
            stage.frames(6, 30)
            val catalog = runBlocking { TargetPics.json("/api/targets/catalog?theater=${TargetPics.enc(ts?.theater.orEmpty())}", com.bmscompanion.app.data.mission.TargetCatalog.serializer()) }
            val have = all.map { it.item.key }.toSet()
            val extra = catalog?.categories?.flatMap { it.items }?.firstOrNull { it.key !in have }
            line("  the Add list: ${catalog?.categories?.size ?: 0} categories, ${catalog?.categories?.sumOf { it.items.size } ?: 0} targets; adding ${extra?.name}")
            check(extra != null, "the Add list offers the theater's ground targets")
            if (extra != null) {
                stage.onUi { t.picking?.draft?.let { d -> while (d.size > 3) d.removeAt(d.size - 1) } }
                val id = stage.onUi { t.add(extra) }
                stage.frames(6, 30)
                check(id != null && t.entries().any { it.id == id } && t.picking?.draft?.lastOrNull() == id,
                    "${extra.name} is on the list and ticked on the half", "$id; draft ${t.picking?.draft?.toList()}")
                check(TargetPics.added(ts!!.mission).any { it.key == extra.key }, "the addition is kept on this device for the Planner flight's mission")
                stage.shot(File(pics, "targets-planner-picker.png"))
                check(stage.click("Print/Tgt/Done") && t.picking == null, "Done closes the picker")
                // printed
                stage.onUi { s.results = emptyList(); s.selN = n; s.selSide = 'L' }
                captures.clear()
                check(stage.click("Print/Print"), "the Print button is pressed")
                val done = stage.until(240_000) { !s.busy && s.results.isNotEmpty() && s.progress == null }
                s.results.forEach { line("  ${it.file}: ${it.status}${it.reason?.let { r -> " — $r" } ?: ""}") }
                check(done && s.results.size == 1 && s.results[0].status == KbFileResult.WRITTEN, "page $n written with the Planner flight's targets")
                val want = t.itemsFor(n, 'L')
                val said = t.drawn["${n}L"].orEmpty()
                line("    ${n}L: $said")
                check(want.any { it.item.key == extra.key } && said.startsWith("${want.size} targets, ${want.size * 2} of ${want.size * 2} pictures:") && said.contains(extra.name),
                    "page $n left: ${want.size} targets × 2 views drawn, the added one among them", said)
                val cap = captures.firstOrNull()?.third
                if (cap != null) File(pics, "targets-planner-page-${n}L.png").writeBytes(cap)
                val img = cap?.let { ImageIO.read(ByteArrayInputStream(it)) }
                val dark = img?.let { count(it) { r, g, b -> maxOf(r, g, b) < 90 } } ?: 0
                check(img != null && dark > want.size * 2 * 8_000, "page $n left: the pictures are inked ($dark dark pixels in a quarter sample)", "$dark")
                stage.onUi { t.unadd(extra.key) }
                check(TargetPics.added(ts.mission).none { it.key == extra.key }, "the ✕ takes the added target off again")
            }
        } catch (e: Throwable) {
            fails++
            line("FAIL: target pictures from the Planner threw ${e::class.java.simpleName}: ${e.message}")
            e.stackTrace.take(10).forEach { line("    at $it") }
        } finally {
            runCatching {
                stage.onUi {
                    t.choices.clear(); s.leaveAll(); t.picking = null; t.adding = false
                    Snapshot.withMutableSnapshot {
                        p.source = old[0] as String; p.theater = old[1] as String?; p.ref = old[2] as com.bmscompanion.app.data.mission.CampRef?
                        p.kind = old[3] as String?; p.flight = old[4] as com.bmscompanion.app.data.mission.CampFlight?
                    }
                }
            }
            runCatching { Bridge.update(reapply = false) { it.copy(MissionSource = oldMode) } }
            TargetPics.saveAdded("none", emptyList())
            stage.close()
            TargetPictures.cacheOverride = oldCache
        }
        return fails
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
    private open class Scene(val w: Int, val h: Int, val density: Float = 1f) : AutoCloseable {
        private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "printpages-ui").apply { isDaemon = true } }
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
    private class Stage(w: Int, h: Int, density: Float = 1f) : Scene(w, h, density) {
        init {
            // inside the app's theme, as the Planner is
            start { com.bmscompanion.app.ui.theme.BmsTheme { Box(Modifier.fillMaxSize().background(Hud.Bg)) { PlannerWindowHost() } } }
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
                // inside the app's theme, as the Print window draws its pages: the theme's text style is what once
                // emptied the target list's rows (1.4.0), and a page drawn without it hid that
                com.bmscompanion.app.ui.theme.BmsTheme {
                    Box(Modifier.fillMaxSize().background(Hud.Bg)) {
                        KneeboardPageHost(kind, 7, 'L', Modifier.size(200.dp, 300.dp), l)
                    }
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
