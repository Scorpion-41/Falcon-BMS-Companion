package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.screens.wdp.WdpPage
import com.bmscompanion.app.ui.screens.wdp.WdpType
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * `--wdpsharp <out folder> [briefing.txt] [cartridge.ini]` — how sharp the Planner's type is (headless; reads only).
 *
 * The DataCard, DTC and Performance pages in the whole Planner, in PC windows of 1920 x 1080 and 2560 x 1440 pixels
 * at 100 % and 125 % desktop scaling, each drawn twice: in the platform's face with Skia's rasterisation as Compose
 * sets it up by default (`before`: Segoe UI, glyphs at quarter pixels, Normal hinting) and as the Planner sets it
 * ([WdpType], `after`: Arial, full hinting, glyphs on whole pixels; BMSC_WDP_FACE=default keeps Segoe UI, and
 * BMSC_WDP_TYPE another hinting, to see each half alone). Beside each pair, two crops of the page's type enlarged six times
 * pixel for pixel, and in the report per picture, counted where the two differ (the type): how many of the type's
 * pixels are part-covered (neither ink nor ground), and how dark the darkest pixel of each stroke is that a row
 * crosses, as a share of the darkest near it ("stem"; told, not judged: a glyph's bars are full ink beside its
 * stems, so it stays near 100 % either way). FAIL when `after` has no fewer part-covered pixels than `before` — a
 * stem smeared over two columns is two of them — or when the two lay the controls out differently (the fix changes
 * no layout).
 */
object WdpSharpRender {
    private val PAGES = listOf(WdpPage.DATACARD, WdpPage.DTC, WdpPage.PERFORMANCE)
    private val WINDOWS = listOf(Triple("1920x1080", 1920 to 1080, 1f), Triple("1920x1080@125", 1920 to 1080, 1.25f),
        Triple("2560x1440", 2560 to 1440, 1f), Triple("2560x1440@125", 2560 to 1440, 1.25f))

    fun run(out: File, briefing: File?, cartridge: File?): String = buildString {
        out.mkdirs()
        runCatching { org.jetbrains.skia.Surface.makeRasterN32Premul(1, 1).close() }
        val data = WdpFixtureMission.data(briefing, cartridge)
        val theater = System.getenv("BMSC_WDP_THEATER")?.let { name ->
            runBlocking { com.bmscompanion.app.ui.screens.wdp.plannerTheater(Repo.index().theaters, name) }
        }
        val mission = WdpFixtureMission.mission(data, theater)
        val s = com.bmscompanion.app.ui.screens.wdp.WdpSession
        WdpDtcFixture.file = cartridge
        s.dtcSource = WdpDtcFixture.source()
        s.theaterName = theater?.name; s.theater = theater
        if (data.briefing != null || data.dtc != null) {
            for (w in listOf(s.toss, s.popup, s.hadb)) w.onMission(mission)
            runBlocking { s.dataCard.prepare(mission) { cartridge?.takeIf { it.isFile }?.readText() } }
            s.dtc.onMission(mission)
            runBlocking { s.performance.prepare(mission) }
            com.bmscompanion.app.ui.screens.wdp.WdpHandOff.cardToPerformance(s.dataCard, s.performance, mission.briefing)
            s.appliedMission = mission
            Thread.sleep(1500)
        }
        appendLine("The Planner's type, before and after (after = ${System.getenv("BMSC_WDP_TYPE") ?: "full"} hinting, face ${System.getenv("BMSC_WDP_FACE") ?: "Arial"})")
        appendLine("briefing: ${if (data.briefing != null) "read" else "none"}, cartridge: ${if (data.dtc != null) "read" else "none"}")
        appendLine("counted where before and after differ: stem = the darkest pixel of each stroke a row crosses, as a share of the darkest near it; grey = part-covered pixels, as a share of the type's")
        appendLine()
        val probe = com.bmscompanion.app.ui.screens.wdp.WdpProbe
        val kept = WdpType.platform; val keptFace = WdpType.face; val keptPictures = WdpType.pictures
        val face = if (System.getenv("BMSC_WDP_FACE") == "default") null else wdpFace()
        try {
            for ((label, size, density) in WINDOWS) for (page in PAGES) {
                s.page = page.name
                if (page == WdpPage.DTC) s.dtc.onValue("tabDTC", "tabMain")
                val shots = HashMap<String, BufferedImage>()
                val rects = HashMap<String, Map<String, androidx.compose.ui.geometry.Rect>>()
                for (variant in listOf("before", "after")) {
                    WdpType.platform = if (variant == "after") variantType() else null
                    WdpType.face = if (variant == "after") face else null
                    WdpType.pictures = if (variant == "after") keptPictures else androidx.compose.ui.graphics.FilterQuality.Low
                    probe.clear(); probe.on = true
                    val scene = ImageComposeScene(size.first, size.second, Density(density)) {
                        com.bmscompanion.app.ui.screens.wdp.WdpPlanner(data, theater?.name, Modifier.fillMaxSize())
                    }
                    try {
                        var t = 0L
                        // the card's printed grid and the lamps are pictures loaded on their own: two seconds for them
                        repeat(50) { scene.render(t); t += 50_000_000; Thread.sleep(40) }
                        val bytes = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
                        File(out, "sharp-$label-${page.name.lowercase()}-$variant.png").writeBytes(bytes)
                        shots[variant] = ImageIO.read(ByteArrayInputStream(bytes))
                        rects[variant] = probe.rects.filterKeys { it.startsWith(page.form + "/") }.toMap()
                    } finally { scene.close(); probe.on = false }
                }
                val before = shots["before"]!!; val after = shots["after"]!!
                val moved = rects["before"]!!.count { (k, r) -> rects["after"]!![k] != r }
                val area = pageArea(rects["before"]!!.values, before)
                val mb = measure(before, area, after); val ma = measure(after, area, before)
                val tag = "$label ${page.label}"
                val sharper = ma.first < mb.first
                appendLine((if (sharper && moved == 0) "ok   " else "FAIL ") + "$tag: stem %.1f%% -> %.1f%%, grey %.1f%% -> %.1f%%, %d of %d controls placed differently"
                    .format(mb.second * 100, ma.second * 100, mb.first * 100, ma.first * 100, moved, rects["before"]!!.size))
                // two crops of the page's type, enlarged pixel for pixel: before above, after below
                for ((i, crop) in crops(rects["before"]!!, area).withIndex()) {
                    val k = 6
                    val img = BufferedImage(crop.width * k, crop.height * k * 2 + k, BufferedImage.TYPE_INT_RGB)
                    for (y in 0 until crop.height) for (x in 0 until crop.width) {
                        val b = before.getRGB(crop.x + x, crop.y + y); val a = after.getRGB(crop.x + x, crop.y + y)
                        for (dy in 0 until k) for (dx in 0 until k) {
                            img.setRGB(x * k + dx, y * k + dy, b)
                            img.setRGB(x * k + dx, (crop.height + 1) * k + y * k + dy, a)
                        }
                    }
                    ImageIO.write(img, "png", File(out, "crop-$label-${page.name.lowercase()}-$i.png"))
                }
            }
        } finally { WdpType.platform = kept; WdpType.face = keptFace; WdpType.pictures = keptPictures; s.dtc.onValue("tabDTC", "tabMain"); s.page = WdpPage.DATACARD.name }
    }

    /**
     * One line of the Planner's type in the default face and in Arial at the sizes the pages set it, under each of
     * Skia's rasterisations (`specimen.png`, 1:1, and `specimen-x6.png`, six times pixel for pixel): which knob does what.
     */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    fun specimen(out: File) {
        out.mkdirs()
        runCatching { org.jetbrains.skia.Surface.makeRasterN32Premul(1, 1).close() }
        val kinds = listOf<String?>(null, "full", "normal", "slight", "none")
        val faces = listOf("default" to null, "Arial" to androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.platform.SystemFont("Arial")),
            "Segoe UI" to androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.platform.SystemFont("Segoe UI")))
        val sizes = listOf(11f, 13.33f, 14.65f, 16f)
        val rowH = 22
        val rows = kinds.size * faces.size * sizes.size
        val scene = ImageComposeScene(560, rows * rowH, Density(1f)) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFC0C0C0))) {
                for (k in kinds) for ((fname, family) in faces) for (px in sizes) {
                    androidx.compose.foundation.text.BasicText(
                        "Drag ind 07:19:30 Wgt 39759 · ${k ?: "default"} · $fname · $px px",
                        Modifier.height(androidx.compose.ui.unit.Dp(rowH.toFloat())),
                        style = androidx.compose.ui.text.TextStyle(
                            color = androidx.compose.ui.graphics.Color.Black, fontSize = androidx.compose.ui.unit.TextUnit(px, androidx.compose.ui.unit.TextUnitType.Sp),
                            fontFamily = family, platformStyle = k?.let { crispType(it) },
                        ),
                    )
                }
            }
        }
        try {
            var t = 0L
            repeat(5) { scene.render(t); t += 50_000_000 }
            val bytes = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "specimen.png").writeBytes(bytes)
            val img = ImageIO.read(ByteArrayInputStream(bytes))
            val k = 6; val w = 200
            val big = BufferedImage(w * k, img.height * k, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until img.height) for (x in 0 until w) { val c = img.getRGB(x, y); for (dy in 0 until k) for (dx in 0 until k) big.setRGB(x * k + dx, y * k + dy, c) }
            ImageIO.write(big, "png", File(out, "specimen-x6.png"))
        } finally { scene.close() }
    }

    /** `after`: the Planner's own ([crispType]), or with BMSC_WDP_TYPE another to compare (normal, slight, none, lcd) */
    private fun variantType(): androidx.compose.ui.text.PlatformTextStyle = crispType(System.getenv("BMSC_WDP_TYPE")?.lowercase() ?: "full")

    /** the page's own box in the picture: the union of its controls' rectangles */
    private fun pageArea(rs: Collection<androidx.compose.ui.geometry.Rect>, img: BufferedImage): java.awt.Rectangle {
        if (rs.isEmpty()) return java.awt.Rectangle(0, 0, img.width, img.height)
        val l = rs.minOf { it.left }.toInt().coerceIn(0, img.width - 1); val t = rs.minOf { it.top }.toInt().coerceIn(0, img.height - 1)
        val r = rs.maxOf { it.right }.toInt().coerceIn(l + 1, img.width); val b = rs.maxOf { it.bottom }.toInt().coerceIn(t + 1, img.height)
        return java.awt.Rectangle(l, t, r - l, b - t)
    }

    /** two 200 x 90 px windows on the page's type: a quarter in from its top left, and its middle */
    private fun crops(rs: Map<String, androidx.compose.ui.geometry.Rect>, area: java.awt.Rectangle): List<java.awt.Rectangle> =
        listOf(0.25 to 0.22, 0.55 to 0.5).map { (fx, fy) ->
            java.awt.Rectangle((area.x + area.width * fx).toInt(), (area.y + area.height * fy).toInt(), 200, 90)
        }.map { it.intersection(area) }

    /**
     * (share of part-covered pixels among the type's, mean darkness of a stroke's darkest pixel as a share of full ink),
     * counted only where the two pictures differ — the type that changed, not what both share. Along a row, a pixel's
     * cover is how far it is from the median of its 9-pixel run (the ground) toward the farthest one (the ink), in
     * luminance, where the two are at least 80 apart; a stroke is a run of pixels with cover over 0.15, part-covered
     * one between 0.15 and 0.85.
     */
    private fun measure(img: BufferedImage, a: java.awt.Rectangle, other: BufferedImage): Pair<Double, Double> {
        fun lum(rgb: Int) = ((rgb shr 16 and 255) * 299 + (rgb shr 8 and 255) * 587 + (rgb and 255) * 114) / 1000
        var ink = 0L; var grey = 0L; var strokes = 0L; var peaks = 0.0
        val run = IntArray(9)
        for (y in a.y until a.y + a.height) {
            var peak = 0.0
            for (x in a.x + 4 until a.x + a.width - 4) {
                var c = 0.0
                if ((0 until 9).any { i -> img.getRGB(x - 4 + i, y) != other.getRGB(x - 4 + i, y) }) {
                    for (i in 0 until 9) run[i] = lum(img.getRGB(x - 4 + i, y))
                    val sorted = run.sorted()
                    val ground = sorted[4]
                    val far = if (kotlin.math.abs(sorted[0] - ground) > kotlin.math.abs(sorted[8] - ground)) sorted[0] else sorted[8]
                    if (kotlin.math.abs(far - ground) >= 80) c = ((run[4] - ground).toDouble() / (far - ground)).coerceIn(0.0, 1.0)
                }
                if (c > 0.15) { ink++; if (c < 0.85) grey++; peak = maxOf(peak, c) }
                else if (peak > 0.0) { strokes++; peaks += peak; peak = 0.0 }
            }
            if (peak > 0.0) { strokes++; peaks += peak }
        }
        return (if (ink == 0L) 0.0 else grey.toDouble() / ink) to (if (strokes == 0L) 0.0 else peaks / strokes)
    }
}
