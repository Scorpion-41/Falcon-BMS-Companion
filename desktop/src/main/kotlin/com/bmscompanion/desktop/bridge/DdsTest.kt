package com.bmscompanion.desktop.bridge

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * `--ddstest out.txt [<fixture>] [<work folder>]`: the DDS codec ([Dds]) on its own.
 *
 * 1. **Round trips**: solid colours, black text on white, two-colour edges, gradients and noise through the DXT1 and
 *    DXT5 encoders and back, against PSNR floors; the four-colour guard (every DXT1 block `c0 > c1`, or `c0 == c1` with
 *    all indices 0, so no pixel ever decodes transparent); DXT5's opaque alpha block; BGRA exactly.
 * 2. **The writer** on synthetic files of each kind (DXT1, DXT5, BGRA; one level and the full chain): a half printed,
 *    the other half's bytes kept for every level at least a block pair wide, the header kept but for the tag.
 * 3. **Real files** (with a fixture): every kneeboard page and shipped backup under it decoded, every level; one file
 *    of each layout compared with BMS's own `Tools\Textureconverter\texconv.exe -ft png` (±2 per channel).
 *
 * Writes nothing but into the work folder (default: a temporary folder, deleted afterwards). The fixture is only read.
 */
object DdsTest {
    fun run(fixture: File?, more: List<String>): String {
        val sb = StringBuilder()
        var fails = 0
        fun line(s: String) { sb.append(s).append('\n') }
        fun check(ok: Boolean, what: String, detail: String = "") {
            if (!ok) fails++
            line((if (ok) "PASS " else "FAIL ") + what + if (detail.isNotEmpty()) "  | $detail" else "")
        }
        val work = more.firstOrNull()?.let(::File) ?: File(System.getProperty("java.io.tmpdir"), "bmsc-ddstest-${System.nanoTime()}")
        val temporary = more.isEmpty()
        work.mkdirs()
        try {
            line("DDS codec check (Dds.kt)")
            line("")
            roundTrips(::line, ::check, work)
            line("")
            writer(::line, ::check)
            line("")
            if (fixture != null) realFiles(fixture, work, ::line, ::check) else line("(no fixture given: real files not checked)")
        } catch (e: Throwable) {
            check(false, "the check ran to the end", "${e::class.java.simpleName}: ${e.message}\n" + e.stackTrace.take(8).joinToString("\n") { "    at $it" })
        } finally {
            if (temporary) runCatching { work.deleteRecursively() }
        }
        line("")
        line(if (fails == 0) "ALL PASS" else "$fails FAIL(s)")
        return sb.toString()
    }

    // ------------------------------------------------------------------ pictures

    private fun picture(w: Int, h: Int, draw: (java.awt.Graphics2D) -> Unit): IntArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        draw(g)
        g.dispose()
        return img.getRGB(0, 0, w, h, null, 0, w).also { for (i in it.indices) it[i] = it[i] or (0xFF shl 24) }
    }

    fun textPicture(w: Int, h: Int, seed: Int = 0): IntArray = picture(w, h) { g ->
        g.color = Color.WHITE; g.fillRect(0, 0, w, h)
        g.color = Color.BLACK
        g.font = Font(Font.MONOSPACED, Font.BOLD, 14)
        var y = 16
        var i = seed
        while (y < h) {
            g.drawString("STPT ${i % 24 + 1}  N37 ${10 + i % 50}.${(i * 37) % 1000}  E127 0${i % 9}.${(i * 91) % 1000}  2,500 FT", 4, y)
            y += 17; i++
        }
    }

    private fun gradient(w: Int, h: Int): IntArray = IntArray(w * h) { p ->
        val x = p % w; val y = p / w
        (0xFF shl 24) or ((x * 255 / (w - 1)) shl 16) or ((y * 255 / (h - 1)) shl 8) or (((x + y) * 255 / (w + h - 2)))
    }

    private fun edges(w: Int, h: Int): IntArray = picture(w, h) { g ->
        g.color = Color(200, 30, 30); g.fillRect(0, 0, w, h)
        g.color = Color(20, 40, 210); g.fillRect(w / 2 + 1, 0, w / 2, h)
        g.color = Color.BLACK; g.fillRect(0, h / 2 + 3, w, h / 4)
        g.color = Color.WHITE; g.stroke = BasicStroke(5f); g.drawLine(0, 0, w, h)
    }

    private fun noise(w: Int, h: Int, seed: Long): IntArray {
        val r = java.util.Random(seed)
        return IntArray(w * h) { (0xFF shl 24) or r.nextInt(0x1000000) }
    }

    // ------------------------------------------------------------------ 1. round trips

    /** Encodes [px] ([w]×[h], multiples of 4) as a one-level file of [kind] and decodes it back. */
    private fun roundTrip(px: IntArray, w: Int, h: Int, kind: Dds.Kind): Pair<IntArray, ByteArray> {
        val file = Dds.newHeader(kind, w, h, 1).copyOf(Dds.HEADER + when (kind) {
            Dds.Kind.DXT1 -> w * h / 2
            Dds.Kind.DXT5 -> w * h
            else -> w * h * 4
        })
        val info = Dds.header(file)
        check(info.readable) { "synthetic header: ${info.unreadable}" }
        // print both halves: the whole picture goes through the encoder
        val hw = w / 2
        val left = IntArray(hw * h) { px[(it / hw) * w + it % hw] }
        val right = IntArray(hw * h) { px[(it / hw) * w + hw + it % hw] }
        val out = Dds.print(file, info, arrayOf(left, right), null)
        return Dds.decode(out, Dds.header(out), 0, 0, 0, w, h) to out
    }

    private fun roundTrips(line: (String) -> Unit, report: (Boolean, String, String) -> Unit, work: File) {
        fun check(ok: Boolean, what: String, detail: String = "") = report(ok, what, detail)
        line("== 1. Round trips (encode, decode, PSNR over red, green and blue)")
        val size = 128
        val cases = listOf(
            Triple("solid white", IntArray(size * size) { 0xFFFFFFFF.toInt() }, 45.0),
            Triple("solid black", IntArray(size * size) { 0xFF000000.toInt() }, 45.0),
            Triple("solid (123,45,67)", IntArray(size * size) { 0xFF7B2D43.toInt() }, 40.0),
            Triple("solid pure red", IntArray(size * size) { 0xFFFF0000.toInt() }, 45.0),
            Triple("black text on white", textPicture(size, size), 30.0),
            Triple("two-colour edges", edges(size, size), 30.0),
            Triple("gradient", gradient(size, size), 35.0),
            Triple("noise (no floor, guard only)", noise(size, size, 7), 0.0),
        )
        for (kind in listOf(Dds.Kind.DXT1, Dds.Kind.DXT5, Dds.Kind.BGRA)) {
            for ((name, px, floor) in cases) {
                val (back, _) = roundTrip(px, size, size, kind)
                val db = Dds.psnr(px, back)
                val want = if (kind == Dds.Kind.BGRA) 99.0 else floor
                check(db >= want, "${kind.label} $name: %.1f dB".format(db), "floor ${if (want >= 99) "exact" else "%.0f dB".format(want)}")
            }
        }

        // a colour 5:6:5 holds exactly: both end points the same, every index 0 (never the three-colour mode's index 3)
        run {
            val exact = 0xFF000000.toInt() or (0xA5 shl 16) or (0xA2 shl 8) or 0x52 // 5:6:5 20,40,10 exactly
            val blk = ByteArray(8)
            Dds.encodeColourBlock(IntArray(16) { exact }, blk, 0)
            val c0 = (blk[0].toInt() and 0xFF) or ((blk[1].toInt() and 0xFF) shl 8)
            val c1 = (blk[2].toInt() and 0xFF) or ((blk[3].toInt() and 0xFF) shl 8)
            val mask = (blk[4].toInt() and 0xFF) or ((blk[5].toInt() and 0xFF) shl 8) or ((blk[6].toInt() and 0xFF) shl 16) or ((blk[7].toInt() and 0xFF) shl 24)
            check(c0 >= c1 && (c0 != c1 || mask == 0), "a block of one colour: c0 >= c1, and when equal every index is 0",
                "c0=%04x c1=%04x mask=%08x".format(c0, c1, mask))
        }
        // the four-colour guard over many blocks: random, flat, two-tone and one-channel blocks
        run {
            val r = java.util.Random(42)
            var bad = 0
            var transparent = 0
            val blocks = 20000
            val blk = ByteArray(8)
            val file = Dds.newHeader(Dds.Kind.DXT1, 4, 4, 1).copyOf(Dds.HEADER + 8)
            val info = Dds.header(file)
            for (i in 0 until blocks) {
                val px = when (i % 4) {
                    0 -> IntArray(16) { (0xFF shl 24) or r.nextInt(0x1000000) }
                    1 -> { val c = (0xFF shl 24) or r.nextInt(0x1000000); IntArray(16) { c } }
                    2 -> { val a = r.nextInt(0x1000000); val b = r.nextInt(0x1000000); IntArray(16) { (0xFF shl 24) or if (r.nextBoolean()) a else b } }
                    else -> { val base = r.nextInt(256); IntArray(16) { (0xFF shl 24) or ((base + r.nextInt(3)).coerceAtMost(255) shl 8) } }
                }
                Dds.encodeColourBlock(px, blk, 0)
                val c0 = (blk[0].toInt() and 0xFF) or ((blk[1].toInt() and 0xFF) shl 8)
                val c1 = (blk[2].toInt() and 0xFF) or ((blk[3].toInt() and 0xFF) shl 8)
                val mask = (blk[4].toInt() and 0xFF) or ((blk[5].toInt() and 0xFF) shl 8) or ((blk[6].toInt() and 0xFF) shl 16) or ((blk[7].toInt() and 0xFF) shl 24)
                if (c0 < c1 || (c0 == c1 && mask != 0)) bad++
                System.arraycopy(blk, 0, file, Dds.HEADER, 8)
                val dec = Dds.decode(file, info, 0, 0, 0, 4, 4)
                if (dec.any { (it ushr 24) != 0xFF }) transparent++
            }
            check(bad == 0 && transparent == 0, "DXT1 four-colour guard over $blocks blocks (random, flat, two-tone, near-flat)",
                "$bad blocks with c0 < c1 or c0 == c1 and an index; $transparent decoded with a transparent pixel")
        }
        // DXT5's alpha block is opaque everywhere
        run {
            val (back, bytes) = roundTrip(textPicture(64, 64), 64, 64, Dds.Kind.DXT5)
            val info = Dds.header(bytes)
            var wrong = 0
            val n = (64 / 4) * (64 / 4)
            for (b in 0 until n) {
                val o = info.levelOffset(0).toInt() + b * 16
                val a = bytes.copyOfRange(o, o + 8)
                if (!(a[0] == 0xFF.toByte() && a[1] == 0xFF.toByte() && a.drop(2).all { it == 0.toByte() })) wrong++
            }
            check(wrong == 0 && back.all { (it ushr 24) == 0xFF }, "DXT5 alpha block FF FF 00 00 00 00 00 00 in every block, alpha 255 decoded", "$wrong of $n blocks differ")
        }
        // timing of the encoder at page size
        run {
            val w = 1024; val h = 2048
            val px = textPicture(w, h, 3)
            val file = Dds.newHeader(Dds.Kind.DXT5, 2 * w, h, 1).copyOf(Dds.HEADER + 2 * w * h)
            val info = Dds.header(file)
            val t0 = System.nanoTime()
            Dds.print(file, info, arrayOf(px, null), null)
            val ms = (System.nanoTime() - t0) / 1_000_000
            check(ms < 2000, "DXT5 encode of one 1024x2048 half page: $ms ms", "target < 2000 ms")
        }
        runCatching { ImageIO.write(Dds.toImage(textPicture(256, 256), 256, 256), "png", File(work, "text-source.png")) }
    }

    // ------------------------------------------------------------------ 2. the writer

    private fun writer(line: (String) -> Unit, report: (Boolean, String, String) -> Unit) {
        fun check(ok: Boolean, what: String, detail: String = "") = report(ok, what, detail)
        line("== 2. The writer on synthetic files (a half printed, the other half kept)")
        val size = 256
        for (kind in listOf(Dds.Kind.DXT1, Dds.Kind.DXT5, Dds.Kind.BGRA)) for (levels in listOf(1, 9)) {
            // the "other tool's" page: noise on the left, a gradient on the right, encoded once
            var base = Dds.newFile(kind, size, levels, 0xFF808080.toInt())
            var info = Dds.header(base)
            base = Dds.print(base, info, arrayOf(noise(size / 2, size, 1), gradient(size / 2, size)), "IMAGEMAGICK")
            info = Dds.header(base)
            val page = textPicture(size / 2, size, 5)
            val out = Dds.print(base, info, arrayOf(page, null), "BMSCOMPANIONtest Lc2 Rb-")
            val oi = Dds.header(out)
            val name = "${kind.label}, $levels level(s), ${size}px"
            check(out.size == base.size && oi.levels == info.levels && oi.format == info.format, "$name: same length, format and levels",
                "${out.size} of ${base.size}, ${oi.format} ${oi.levels}")
            val headSame = (0 until Dds.HEADER).all { it in Dds.TAG_OFFSET until Dds.TAG_OFFSET + Dds.TAG_LENGTH || out[it] == base[it] }
            check(headSame && oi.tag == "BMSCOMPANIONtest Lc2 Rb-", "$name: header kept but for the tag", "tag '${oi.tag}'")
            var kept = 0
            var copyLevels = 0
            for (l in 0 until info.levels) {
                val lw = info.levelWidth(l)
                if (!(lw >= 2 * kind.block && (lw / 2) % kind.block == 0)) continue
                copyLevels++
                if (halfBytes(base, info, l, 1).contentEquals(halfBytes(out, oi, l, 1))) kept++
            }
            check(kept == copyLevels, "$name: the right half's bytes kept on $kept of $copyLevels block-aligned levels")
            val db = Dds.psnr(page, Dds.decode(out, oi, 0, 0, 0, size / 2, size))
            check(db >= (if (kind == Dds.Kind.BGRA) 99.0 else 30.0), "$name: printed half %.1f dB".format(db))
            // print the right half next: the left half, just printed, is kept
            val out2 = Dds.print(out, oi, arrayOf(null, textPicture(size / 2, size, 9)), null)
            val keptLeft = (0 until info.levels).filter { val lw = info.levelWidth(it); lw >= 2 * kind.block && (lw / 2) % kind.block == 0 }
                .all { halfBytes(out, oi, it, 0).contentEquals(halfBytes(out2, Dds.header(out2), it, 0)) }
            check(keptLeft, "$name: printing the other half keeps the first")
        }
        // refusals
        val dx10 = Dds.newFile(Dds.Kind.DXT1, 64, 1, -1).also { System.arraycopy("DX10".toByteArray(), 0, it, 84, 4) }
        check(Dds.header(dx10).unwritable?.contains("DirectX 10") == true, "a DX10 header is refused", Dds.header(dx10).unwritable ?: "")
        val short = Dds.newFile(Dds.Kind.DXT5, 64, 7, -1).let { it.copyOf(it.size - 100) }
        check(Dds.header(short).cutShort && !Dds.header(short).writable, "a file cut short is refused", Dds.header(short).unwritable ?: "")
        val oblong = Dds.newHeader(Dds.Kind.DXT1, 64, 32, 1).copyOf(Dds.HEADER + 64 * 32 / 2)
        check(!Dds.header(oblong).writable && Dds.header(oblong).readable, "an oblong file is read but not written", Dds.header(oblong).unwritable ?: "")
        val odd = Dds.newHeader(Dds.Kind.DXT1, 96, 96, 1).copyOf(Dds.HEADER + 96 * 96 / 2)
        check(!Dds.header(odd).writable, "a size that is not a power of two is refused", Dds.header(odd).unwritable ?: "")
        val rgb24 = Dds.newHeader(Dds.Kind.BGRA, 16, 16, 1).also { b ->
            b[88] = 24; b[89] = 0; b[90] = 0; b[91] = 0
            for (i in 104 until 108) b[i] = 0
        }.copyOf(Dds.HEADER + 16 * 16 * 3)
        check(Dds.header(rgb24).readable && !Dds.header(rgb24).writable, "a 24-bit file is read but not written", Dds.header(rgb24).unwritable ?: "")
        check(!Dds.header(ByteArray(0), 0).readable && Dds.header(ByteArray(0), 0).cutShort, "an empty file is unreadable and cut short")
    }

    /** The bytes of one half ([side] 0/1) of level [l]: a block column range per block row, or a pixel range per row. */
    fun halfBytes(bytes: ByteArray, info: Dds.Info, l: Int, side: Int): ByteArray {
        val lw = info.levelWidth(l); val lh = info.levelHeight(l)
        val off = info.levelOffset(l).toInt()
        val out = java.io.ByteArrayOutputStream()
        if (info.kind.block == 4) {
            val bw = maxOf(1, (lw + 3) / 4); val bh = maxOf(1, (lh + 3) / 4)
            val half = bw / 2
            for (by in 0 until bh) out.write(bytes, off + (by * bw + side * half) * info.kind.bytes, half * info.kind.bytes)
        } else {
            val half = lw / 2
            for (y in 0 until lh) out.write(bytes, off + (y * lw + side * half) * info.kind.bytes, half * info.kind.bytes)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ 3. real files

    private fun realFiles(fixture: File, work: File, line: (String) -> Unit, report: (Boolean, String, String) -> Unit) {
        fun check(ok: Boolean, what: String, detail: String = "") = report(ok, what, detail)
        line("== 3. Real files under ${fixture.name} (every level decoded)")
        val files = fixture.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".dds", true) && (it.name.matches(Regex("79\\d\\d\\.dds", RegexOption.IGNORE_CASE))) }
            .sortedBy { it.path.lowercase() }.toList()
        check(files.isNotEmpty(), "kneeboard pages found in the fixture", "${files.size}")
        val layouts = LinkedHashMap<String, File>()
        var bad = 0
        for (f in files) {
            val bytes = f.readBytes()
            val info = Dds.header(bytes)
            val rel = f.path.substring(fixture.path.length).trimStart('\\', '/')
            if (!info.readable) { line("  unreadable: $rel: ${info.unreadable}"); bad++; continue }
            val layout = "${info.format} ${info.levels} level(s) flags=%x depth=${info.depth} caps=%x tag='${info.tag}'".format(info.flags, info.caps)
            val t0 = System.nanoTime()
            var lum = 0.0
            try {
                for (l in 0 until info.levels) {
                    val px = Dds.decode(bytes, info, l, 0, 0, info.levelWidth(l), info.levelHeight(l))
                    if (l == 0) lum = px.sumOf { (((it shr 16) and 0xFF) + ((it shr 8) and 0xFF) + (it and 0xFF)).toLong() } / (px.size * 3.0)
                }
            } catch (e: Throwable) { line("  decode failed: $rel: ${e.message}"); bad++; continue }
            val ms = (System.nanoTime() - t0) / 1_000_000
            layouts.putIfAbsent(layout, f)
            line("  %-78s %s  mean %.0f  %d ms".format(rel, layout, lum, ms))
        }
        check(bad == 0, "every page file decodes at every level", "$bad failed")
        line("  layouts found: ${layouts.size}")
        layouts.keys.forEach { line("    $it") }
        val want = listOf("DXT5 12", "DXT1 1 level(s) flags=81007", "BGRA 1", "DXT1 12", "DXT1 1 level(s) flags=a1007")
        for (w in want) check(layouts.keys.any { it.startsWith(w) }, "the fixture has a '$w…' page (BMS DXT5, EZBoards, html_brief, Balkans, Hellas)")

        // texconv, BMS's own converter, reads each layout the same way (±2)
        val texconv = listOf("Tools\\Textureconverter\\texconv.exe", "Tools\\EZBoards\\bin\\texconv.exe").map { File(fixture, it) }.firstOrNull { it.isFile }
        if (texconv == null) { line("  (no texconv.exe in the fixture: comparison skipped)"); return }
        val out = File(work, "texconv").also { it.mkdirs() }
        for ((layout, f) in layouts) {
            val dir = File(out, "l${layouts.keys.indexOf(layout)}").also { it.mkdirs() }
            val p = ProcessBuilder(texconv.path, "-nologo", "-y", "-ft", "png", "-o", dir.path, f.path).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor(60, TimeUnit.SECONDS)
            val png = File(dir, f.nameWithoutExtension + ".png")
            if (!png.isFile) { check(false, "texconv reads $layout", text.lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" / ")); continue }
            val img = ImageIO.read(png)
            val bytes = f.readBytes()
            val info = Dds.header(bytes)
            val ours = Dds.decode(bytes, info, 0, 0, 0, info.width, info.height)
            val theirs = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            // compare colour only; a transparent pixel (DXT1's three-colour index 3) is black in both
            val (worst, over) = Dds.maxDiff(ours, theirs, 2)
            check(img.width == info.width && over == 0, "texconv agrees on $layout (${f.name})", "largest difference $worst, $over pixels over 2")
        }
    }
}
