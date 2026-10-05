package com.bmscompanion.desktop.bridge

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

/**
 * `--mfdbench <out.txt> [folder]` — what the MFD pictures cost, before and after, without Falcon BMS.
 *
 * Like `--rttselftest` it publishes a texture under BMS's own area names, with the two MFD rectangles where BMS
 * puts them and a padded row pitch as a Direct3D staging texture has, and then reads it the way the app does.
 * The pictures are made to be like the real thing, because the cost of compressing a picture depends on the
 * picture: the left display is a radar page (black, thin green lines, text, a few moving contacts) and the right
 * one pod video (grey, textured, all of it moving). Every frame is different, which is the worst case; a page that
 * is not moving is measured separately.
 *
 * "Before" is the reader as it stood in the first 1.3.8 test build (1.3.7 had no picture at all), kept here exactly so the comparison can be run again: the area
 * opened and mapped for every frame, one native call per row, every pixel converted and written into an AWT image,
 * then PNG through ImageIO — for the PC's own window as much as for a phone, which decoded it again. "After" is
 * [RttTextures] as it is now. Times are wall-clock time on one thread of an otherwise idle PC, which for work that
 * runs on one thread is what the PC pays; the percentage is of one core, for both displays at the frame rate given.
 */
object MfdBench {
    private const val FRAMES = 60
    private val SIDE = System.getenv("BMSC_MFD_SIZE")?.toIntOrNull()?.coerceIn(128, 1200) ?: 600
    private val TEX_W = SIDE * 2 + 480
    private val TEX_H = SIDE + 300
    /** padded as a Direct3D staging texture pads its rows: up to a multiple of 256 bytes */
    private val PITCH = (TEX_W * 4 + 255) / 256 * 256
    private const val FPS = 10

    private val LEFT = intArrayOf(80, 150, 80 + SIDE, 150 + SIDE)
    private val RIGHT get() = intArrayOf(TEX_W - 80 - SIDE, 150, TEX_W - 80, 150 + SIDE)

    fun run(outDir: File?): String = buildString {
        // Wall time on one thread: every step here is single-threaded, and Windows counts thread CPU time in 15.6 ms
        // ticks, too coarse for work that takes a fraction of one.
        fun cpuMs() = System.nanoTime() / 1_000_000.0
        appendLine("MFD picture cost, measured with --mfdbench (no Falcon BMS needed)")
        appendLine("texture $TEX_W x $TEX_H at pitch $PITCH; two MFDs of $SIDE x $SIDE; $FRAMES frames each; rates are for both displays at $FPS a second")
        appendLine("left = radar page (thin green lines, text, moving contacts), right = pod video (grey, textured, all moving)")
        appendLine()

        val mapping = PITCH.toLong() * TEX_H
        val fd2 = RttSelfTest.create(SharedMemoryReader.FLIGHT_DATA2, FD2.size.toLong())
            ?: run { appendLine("could not create ${SharedMemoryReader.FLIGHT_DATA2} — is Falcon BMS running? Close it first."); return@buildString }
        val tex = RttSelfTest.create(RttTextures.AREA, mapping)
        if (tex == null) { RttSelfTest.closeAll(fd2); run { appendLine("could not create ${RttTextures.AREA}"); return@buildString } }
        try {
            fd2.second.setShort(FD2.rttSize.toLong(), TEX_W.toShort())
            fd2.second.setShort((FD2.rttSize + 2).toLong(), TEX_H.toShort())
            val rects = List(7) { i -> when (i) { 4 -> LEFT; 5 -> RIGHT; else -> intArrayOf(0, 0, 0, 0) } }
            rects.forEachIndexed { i, r -> for (k in 0..3) fd2.second.setShort((FD2.rttArea + i * 8 + k * 2).toLong(), r[k].toShort()) }

            val radar = List(FRAMES) { bgra(radarPage(it)) }
            val pod = List(FRAMES) { bgra(podVideo(it)) }
            fun publish(i: Int) {
                blit(tex.second, radar[i % FRAMES], LEFT)
                blit(tex.second, pod[i % FRAMES], RIGHT)
            }
            publish(0)

            RttTextures.requireBms = false
            RttTextures.shareMs = 0
            RttTextures.reset()
            val displays = listOf(RttTextures.Display.MFDLEFT, RttTextures.Display.MFDRIGHT)

            // ---- before: the first 1.3.8 test build, per frame, for each display
            var legacyBytes = LongArray(2)
            var legacyServer = 0.0
            var legacyDecode = 0.0
            repeat(5) { i -> publish(i); displays.forEach { d -> Legacy.png(d)?.let { decode(it) } } }   // warm up
            for (i in 0 until FRAMES) {
                publish(i)
                displays.forEachIndexed { k, d ->
                    val t0 = cpuMs()
                    val png = Legacy.png(d) ?: run { appendLine("legacy reader read nothing"); return@buildString }
                    val t1 = cpuMs()
                    decode(png)
                    val t2 = cpuMs()
                    legacyServer += t1 - t0
                    legacyDecode += t2 - t1
                    legacyBytes[k] += png.size.toLong()
                }
            }
            val n = (FRAMES * 2).toDouble()
            appendLine("BEFORE (the first 1.3.8 test build)")
            appendLine("  read + PNG on the PC       %6.2f ms a display-frame".format(legacyServer / n))
            appendLine("  decode on the client       %6.2f ms".format(legacyDecode / n))
            appendLine("  PNG size                   radar %d KB, pod %d KB".format(legacyBytes[0] / FRAMES / 1024, legacyBytes[1] / FRAMES / 1024))
            appendLine("  PC's own window            %6.2f ms a display-frame = %.1f%% of one core (it encoded and decoded its own picture)".format((legacyServer + legacyDecode) / n, (legacyServer + legacyDecode) / n * 2 * FPS / 10))
            appendLine("  phone/browser, PC side     %6.2f ms = %.1f%% of one core, %d KB/s".format(legacyServer / n, legacyServer / n * 2 * FPS / 10, (legacyBytes[0] + legacyBytes[1]) / FRAMES * FPS / 1024))
            appendLine("  a page that is not moving  the same again: every frame read, encoded and sent in full")
            appendLine()

            // ---- after: the PC's own window — read, compare, wrap as a Skia image; no encoding
            RttTextures.reset()
            repeat(5) { i -> publish(i); displays.forEach { d -> RttTextures.frame(d)?.let { RttTextures.image(it).close() } } }
            var localRead = 0.0
            var localWrap = 0.0
            for (i in 0 until FRAMES) {
                publish(i + 7)
                for (d in displays) {
                    val t0 = cpuMs()
                    val f = RttTextures.frame(d) ?: run { appendLine("reader read nothing"); return@buildString }
                    val t1 = cpuMs()
                    RttTextures.image(f).close()
                    val t2 = cpuMs()
                    localRead += t1 - t0
                    localWrap += t2 - t1
                }
            }
            // unchanged: the same frame published, read again
            var still = 0.0
            var stillNew = 0
            val before = displays.map { RttTextures.frame(it)?.tag }
            for (i in 0 until FRAMES) for ((k, d) in displays.withIndex()) {
                val t0 = cpuMs()
                val f = RttTextures.frame(d)
                still += cpuMs() - t0
                if (f?.tag != before[k]) stillNew++
            }
            appendLine("AFTER — the PC's own window (in-process, no encoding)")
            appendLine("  read the rectangle         %6.2f ms a display-frame".format(localRead / n))
            appendLine("  wrap for drawing           %6.2f ms".format(localWrap / n))
            appendLine("  moving page                %6.2f ms = %.1f%% of one core".format((localRead + localWrap) / n, (localRead + localWrap) / n * 2 * FPS / 10))
            appendLine("  page not moving            %6.2f ms = %.1f%% of one core (compared, not redrawn; %d frames wrongly new)".format(still / n, still / n * 2 * FPS / 10, stillNew))
            appendLine()

            // ---- after: over the network — each format on each picture, and the size a phone would ask for
            appendLine("AFTER — a phone or a browser (encoded once per changed frame, 204 when unchanged)")
            val formats = listOf(
                Triple("PNG (Skia)", EncodedImageFormat.PNG, 100),
                Triple("JPEG q85", EncodedImageFormat.JPEG, 85),
                Triple("JPEG q80", EncodedImageFormat.JPEG, 80),
                Triple("WebP q80", EncodedImageFormat.WEBP, 80),
                Triple("WebP q100", EncodedImageFormat.WEBP, 100),
            )
            val sample = displays.associateWith { d -> publish(3); RttTextures.frame(d)!! }
            for ((label, fmt, q) in formats) {
                for (width in listOf(0, 512)) {
                    var enc = 0.0
                    var dec = 0.0
                    val size = LongArray(2)
                    for (i in 0 until FRAMES / 3) {
                        publish(i + 11)
                        displays.forEachIndexed { k, d ->
                            val f = RttTextures.frame(d)!!
                            val t0 = cpuMs()
                            val b = RttTextures.encode(f, width, fmt, q)!!
                            val t1 = cpuMs()
                            decode(b)
                            dec += cpuMs() - t1
                            enc += t1 - t0
                            size[k] += b.size.toLong()
                        }
                    }
                    val m = (FRAMES / 3 * 2).toDouble()
                    val per = FRAMES / 3
                    appendLine(
                        "  %-11s %-9s encode %6.2f ms, decode %5.2f ms, radar %3d KB, pod %3d KB -> %4d KB/s moving; PC %.1f%% of one core".format(
                            label, if (width == 0) "full" else "w=$width", enc / m, dec / m, size[0] / per / 1024, size[1] / per / 1024,
                            (size[0] + size[1]) / per * FPS / 1024, enc / m * 2 * FPS / 10,
                        ),
                    )
                    if (outDir != null && width == 0) {
                        outDir.mkdirs()
                        displays.forEach { d ->
                            val b = RttTextures.encode(sample.getValue(d), 0, fmt, q)!!
                            // written back out as PNG, so what the format did to the picture can be looked at
                            Image.makeFromEncoded(b).use { img -> img.encodeToData(EncodedImageFormat.PNG)?.bytes }
                                ?.let { File(outDir, "bench-${d.id}-${label.replace(' ', '-').replace("(", "").replace(")", "").lowercase()}.png").writeBytes(it) }
                        }
                    }
                }
            }
            // the whole path a phone takes, with the format chosen: frame, encode (cached), and the 204 on a still page
            RttTextures.reset()
            publish(20)
            var first = 0.0
            var again = 0.0
            var sent204 = 0
            for (i in 0 until FRAMES) for (d in displays) {
                val t0 = cpuMs()
                val e = RttTextures.encoded(d, 0, null)!!
                val t1 = cpuMs()
                val e2 = RttTextures.encoded(d, 0, e.hash)!!
                again += cpuMs() - t1
                first += t1 - t0
                if (e2 === RttTextures.UNCHANGED) sent204++
            }
            appendLine("  chosen (${RttTextures.format} q${RttTextures.quality}): a still page costs %.2f ms a request and sends nothing (%d of %d answered 204)".format(again / n, sent204, FRAMES * 2))
        } finally {
            RttTextures.reset()
            RttTextures.shareMs = 15
            RttTextures.requireBms = true
            RttSelfTest.closeAll(tex)
            RttSelfTest.closeAll(fd2)
        }
    }

    /** Forces a full decode, as drawing the picture would. */
    private fun decode(bytes: ByteArray) {
        Image.makeFromEncoded(bytes).use { img ->
            Bitmap().use { b ->
                b.allocN32Pixels(img.width, img.height)
                img.readPixels(b)
            }
        }
    }

    private fun blit(tex: Pointer, bgra: ByteArray, r: IntArray) {
        val w = r[2] - r[0]
        for (y in 0 until r[3] - r[1]) tex.write((r[1] + y).toLong() * PITCH + r[0] * 4L, bgra, y * w * 4, w * 4)
    }

    private fun bgra(img: BufferedImage): ByteArray {
        val w = img.width
        val out = ByteArray(w * img.height * 4)
        val px = IntArray(w)
        for (y in 0 until img.height) {
            img.getRGB(0, y, w, 1, px, 0, w)
            for (x in 0 until w) {
                val p = px[x]
                val o = (y * w + x) * 4
                out[o] = (p and 0xFF).toByte(); out[o + 1] = (p shr 8 and 0xFF).toByte(); out[o + 2] = (p shr 16 and 0xFF).toByte(); out[o + 3] = -1
            }
        }
        return out
    }

    /** An FCR page, near enough: range arcs, azimuth ticks, the cursor, contacts that move a little each frame, legends. */
    internal fun radarPage(i: Int, s: Int = SIDE): BufferedImage {
        val img = BufferedImage(s, s, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.BLACK; g.fillRect(0, 0, s, s)
        val green = Color(0x3C, 0xF0, 0x5A)
        g.color = green
        g.stroke = BasicStroke(maxOf(1f, s / 400f))
        val cx = s / 2; val base = (s * 0.88).toInt()
        for (k in 1..3) { val r = s * 0.25 * k; g.drawArc((cx - r).toInt(), (base - r).toInt(), (2 * r).toInt(), (2 * r).toInt(), 30, 120) }
        for (a in -60..60 step 30) {
            val rad = Math.toRadians(90.0 - a)
            g.drawLine(cx + (cos(rad) * s * 0.7).toInt(), base - (sin(rad) * s * 0.7).toInt(), cx + (cos(rad) * s * 0.74).toInt(), base - (sin(rad) * s * 0.74).toInt())
        }
        g.font = Font(Font.MONOSPACED, Font.BOLD, maxOf(9, s / 26))
        val top = listOf("CRM", "RWS", "NORM", "OVRD", "CNTL")
        top.forEachIndexed { k, t -> g.drawString(t, (s * (0.08 + 0.2 * k)).toInt(), (s * 0.06).toInt()) }
        listOf("FCR", "TGP", "SMS", "WPN", "DCLT").forEachIndexed { k, t -> g.drawString(t, (s * (0.08 + 0.2 * k)).toInt(), (s * 0.97).toInt()) }
        listOf("A", "40", "C", "4B").forEachIndexed { k, t -> g.drawString(t, (s * 0.02).toInt(), (s * (0.2 + 0.18 * k)).toInt()) }
        // contacts: small filled blocks that drift, and a cursor that sweeps
        for (c in 0 until 6) {
            val x = (s * (0.25 + 0.1 * c) + (i * (c + 1)) % 17).toInt()
            val y = (s * (0.3 + 0.07 * (c % 3)) + (i * 3 + c * 11) % 23).toInt()
            g.fillRect(x, y, maxOf(4, s / 60), maxOf(3, s / 90))
        }
        val cur = (s * 0.3 + (i * 7) % (s / 2)).toInt()
        g.drawLine(cur - 6, s / 2 - 8, cur - 6, s / 2 + 8); g.drawLine(cur + 6, s / 2 - 8, cur + 6, s / 2 + 8)
        g.dispose()
        return img
    }

    /** Pod video: a grey scene with texture across all of it and crosshairs, moving every frame, as a TGP picture does. */
    internal fun podVideo(i: Int, s: Int = SIDE): BufferedImage {
        val img = BufferedImage(s, s, BufferedImage.TYPE_INT_RGB)
        var seed = 1234567 + i * 7919
        for (y in 0 until s) for (x in 0 until s) {
            seed = seed * 1103515245 + 12345
            val noise = (seed ushr 24) and 0x1F
            val field = ((sin((x + i * 2) / 23.0) + cos((y - i) / 31.0)) * 40 + 110).toInt()
            val road = if (kotlin.math.abs((x - y / 2 - i) % 160) < 6) 60 else 0
            val v = (field + noise + road).coerceIn(0, 255)
            img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
        }
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.stroke = BasicStroke(maxOf(1f, s / 300f))
        g.drawLine(s / 2 - s / 8, s / 2, s / 2 - s / 40, s / 2); g.drawLine(s / 2 + s / 40, s / 2, s / 2 + s / 8, s / 2)
        g.drawLine(s / 2, s / 2 - s / 8, s / 2, s / 2 - s / 40); g.drawLine(s / 2, s / 2 + s / 40, s / 2, s / 2 + s / 8)
        g.font = Font(Font.MONOSPACED, Font.BOLD, maxOf(9, s / 26))
        g.drawString("A-G  WIDE  TV", (s * 0.05).toInt(), (s * 0.08).toInt())
        g.dispose()
        return img
    }

    /**
     * The reader exactly as the first 1.3.8 test build had it, for the "before" column: the area opened and mapped for every frame, a
     * native call per row, every pixel through AWT, and ImageIO's PNG — which the PC's own window then decoded again.
     */
    private object Legacy {
        fun png(display: RttTextures.Display): ByteArray? {
            val img = grab(display) ?: return null
            return ByteArrayOutputStream(64 * 1024).also { ImageIO.write(img, "png", it) }.toByteArray()
        }

        private fun grab(display: RttTextures.Display): BufferedImage? {
            val fd2 = SharedMemoryReader.mapArea(SharedMemoryReader.FLIGHT_DATA2, FD2.size) ?: return null
            val texW = fd2.getShort(FD2.rttSize).toInt() and 0xFFFF
            val texH = fd2.getShort(FD2.rttSize + 2).toInt() and 0xFFFF
            val at0 = FD2.rttArea + display.ordinal * 8
            val left = fd2.getShort(at0).toInt() and 0xFFFF
            val top = fd2.getShort(at0 + 2).toInt() and 0xFFFF
            val w = (fd2.getShort(at0 + 4).toInt() and 0xFFFF) - left
            val h = (fd2.getShort(at0 + 6).toInt() and 0xFFFF) - top
            val k = Kernel32.INSTANCE
            val handle = k.OpenFileMapping(WinNT.FILE_MAP_READ, false, RttTextures.AREA) ?: return null
            val view = k.MapViewOfFile(handle, WinNT.FILE_MAP_READ, 0, 0, 0) ?: run { k.CloseHandle(handle); return null }
            try {
                val info = WinNT.MEMORY_BASIC_INFORMATION()
                k.VirtualQueryEx(k.GetCurrentProcess(), view, info, BaseTSD.SIZE_T(info.size().toLong()))
                val size = info.regionSize.toLong()
                val pitch = PITCH.toLong()   // that build worked this out from the section's size; the answer is the same
                if (texW <= 0 || texH <= 0) return null
                val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val row = ByteArray(w * 4)
                val px = IntArray(w)
                for (y in 0 until h) {
                    val at = (top + y).toLong() * pitch + left.toLong() * 4
                    if (at + row.size > size) break
                    view.read(at, row, 0, row.size)
                    for (x in 0 until w) {
                        val b = row[x * 4].toInt() and 0xFF
                        val g = row[x * 4 + 1].toInt() and 0xFF
                        val r = row[x * 4 + 2].toInt() and 0xFF
                        px[x] = (r shl 16) or (g shl 8) or b
                    }
                    out.setRGB(0, y, w, 1, px, 0, w)
                }
                return out
            } finally {
                k.UnmapViewOfFile(view)
                k.CloseHandle(handle)
            }
        }
    }
}
