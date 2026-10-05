package com.bmscompanion.desktop.bridge

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.DirectColorModel
import java.awt.image.Raster
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.IIOException
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A picture file on this PC, for Upd Kneeboard's Picture halves (WDP's Browse Picture, which reads a `.jpg`, `.png` or
 * `.bmp` with GDI+ and a `.dds` with DevIL). Read by `GET /api/files/picture` (the device's preview) and by
 * `POST /api/kbprint/file`, which draws a Picture half **from the file's own pixels**, as WDP does, never from a
 * device's capture.
 *
 * - `.jpg`, `.png`, `.bmp` (and whatever else Java's own readers take): through `ImageIO`. A CMYK JPEG, which Java
 *   does not read, is refused with a sentence saying what to do.
 * - `.dds`: DXT1, DXT3, DXT5 through [Dds]; DXT2 and DXT4 (their premultiplied twins) the same way, the colour divided
 *   by the alpha again; and every uncompressed layout of 8, 16, 24 or 32 bits a pixel described by bit masks: BGRA,
 *   RGB, R5G6B5, A1R5G5B5, A4R4G4B4, A2R10G10B10, luminance (L8, A8L8, L16) and alpha alone (A8, shown as grey), rows
 *   padded or not. A DX10 header (BC7 and the rest), ATI1/ATI2 (BC4/BC5, one- and two-channel textures such as normal
 *   maps), floating-point and YUV layouts, cube maps and volume textures are refused in a sentence.
 *
 * **Memory is bounded before anything is decoded**: the PC program has a 512 MB heap and serves every device at once.
 * The size is read from the header first; a picture of more than [BUDGET] pixels is read at a whole fraction of its
 * size (Java's readers skip pixels for that; a `.dds` is decoded a band at a time and each block of pixels averaged),
 * and a `.dds` that carries mip levels is read at the smallest level still big enough for its use. Only one picture is
 * decoded at a time, and a file over [MAX_BYTES] is not read at all. Read only; nothing here throws.
 */
object PictureFile {
    /** Pixels decoded at most, 8192 x 4096 (four times a half page's 1024 x 2048 each way): above it, a fraction. */
    const val BUDGET = 32L * 1024 * 1024

    /** The largest file read (a 4096-pixel 32-bit DDS with its mip levels is 85 MB). */
    const val MAX_BYTES = 96L * 1024 * 1024

    /** The largest side taken, as [Dds.header] has it. */
    const val MAX_SIDE = 16384

    private const val MB = 1024L * 1024

    /** What a read gave: the picture ([width] x [height] is the file's own size, [img] may be smaller), or why not. */
    sealed interface Read {
        class Ok(val img: BufferedImage, val format: String, val width: Int, val height: Int) : Read
        class Bad(val why: String) : Read
    }

    private val lock = Any()

    /**
     * The pixels of [file], at full size when that is within [BUDGET]. [enough] says whether a smaller size would still
     * do for the use (width, height): a `.dds` with mip levels is read at the smallest level for which it is true, and
     * with [skipOk] (a preview, where skipped pixels do not matter) a `.jpg`/`.png`/`.bmp` is read at the largest whole
     * fraction for which it is true. [Read.Bad.why] is a sentence ending in a full stop. Never throws.
     */
    fun read(file: File, skipOk: Boolean = false, enough: (Int, Int) -> Boolean = { _, _ -> false }): Read = synchronized(lock) {
        try {
            val size = file.length()
            when {
                !file.isFile -> Read.Bad("it is not on the PC.")
                size <= 0L -> Read.Bad("the file is empty.")
                size > MAX_BYTES -> Read.Bad("it is ${size / MB} MB, and the kneeboard takes picture files up to ${MAX_BYTES / MB} MB.")
                file.name.substringAfterLast('.', "").equals("dds", ignoreCase = true) -> dds(file, enough)
                else -> imageIo(file, if (skipOk) enough else null)
            }
        } catch (e: OutOfMemoryError) {
            Read.Bad("it is too large for BMS Companion to read (the PC ran short of memory for it).")
        } catch (e: Throwable) {
            Read.Bad((e.message ?: e.javaClass.simpleName).trimEnd('.') + ".")
        }
    }

    /**
     * The whole fraction a picture of [w] x [h] is read at: 1, or the smallest that brings it within [BUDGET] — counted
     * in 4-byte pixels, so a picture whose decoded pixels take [bytes] each (a 16-bit RGBA PNG: 8) is allowed fewer.
     */
    fun factor(w: Int, h: Int, bytes: Int = 4): Int {
        val budget = BUDGET * 4 / bytes.coerceIn(1, 16)
        val px = w.toLong() * h
        if (px <= budget) return 1
        var k = ceil(sqrt(px.toDouble() / budget)).toInt()
        while ((w / k).toLong() * (h / k) > budget) k++
        return k
    }

    // ---------------------------------------------------------------- jpg, png, bmp

    private fun imageIo(file: File, enough: ((Int, Int) -> Boolean)?): Read {
        val stream = ImageIO.createImageInputStream(file) ?: return Read.Bad("it could not be opened.")
        return stream.use { s ->
            val readers = ImageIO.getImageReaders(s)
            if (!readers.hasNext()) return Read.Bad("it is not a JPEG, PNG or BMP picture Java can read.")
            val r = readers.next()
            try {
                r.setInput(s, true, true)
                val format = r.formatName.uppercase().let { if (it == "JPG") "JPEG" else it }
                val w = r.getWidth(0)
                val h = r.getHeight(0)
                if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) return Read.Bad("its size (${w}×$h) is not a picture's.")
                // what each decoded pixel takes (a 16-bit PNG twice an 8-bit one's), from the reader's own pixel layout
                val bytes = runCatching { r.getRawImageType(0)?.colorModel?.pixelSize }.getOrNull()?.let { (it + 7) / 8 }?.coerceAtLeast(3) ?: 4
                var k = factor(w, h, bytes)
                // a preview: the largest whole fraction still enough for it (pixels skipped, which a preview does not mind)
                if (enough != null) while (k < 64 && enough(w / (k + 1), h / (k + 1))) k++
                val p = r.defaultReadParam
                if (k > 1) p.setSourceSubsampling(k, k, 0, 0)
                val img = try {
                    r.read(0, p)
                } catch (e: IIOException) {
                    if (format == "JPEG" && e.message?.contains("Unsupported Image Type", ignoreCase = true) == true)
                        return Read.Bad("it is a CMYK JPEG (the kind printing programs save), which BMS Companion cannot read: save it as an RGB JPEG or a PNG.")
                    throw e
                }
                return Read.Ok(img, format, w, h)
            } finally {
                r.dispose()
            }
        }
    }

    // ---------------------------------------------------------------- dds

    private const val DDSD_PITCH = 0x8
    private const val DDSD_MIPMAPCOUNT = 0x20000
    private const val DDPF_ALPHAPIXELS = 0x1
    private const val DDPF_ALPHA = 0x2
    private const val DDPF_FOURCC = 0x4
    private const val DDPF_RGB = 0x40
    private const val DDPF_LUMINANCE = 0x20000

    /** How a level's pixels are laid out: DXT blocks through [Dds], or [bpp]-byte pixels with bit masks. */
    private class Layout(
        val format: String,
        /** the [Dds.Kind] whose blocks these are (DXT1, DXT3, DXT5), null for uncompressed */
        val blocks: Dds.Kind?,
        val premultiplied: Boolean = false,
        val bpp: Int = 0,
        val masks: IntArray = IntArray(4),
        val alpha: Boolean = false,
        val luminance: Boolean = false,
        val alphaOnly: Boolean = false,
    ) {
        fun rowBytes(w: Int): Long = if (blocks != null) max(1, (w + 3) / 4).toLong() * blocks.bytes else w.toLong() * bpp
        fun levelBytes(w: Int, h: Int): Long = if (blocks != null) rowBytes(w) * max(1, (h + 3) / 4) else rowBytes(w) * h
    }

    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** The layout the header [head] describes, or the sentence saying why it is not read. */
    private fun layout(head: ByteArray): Any {
        val pfFlags = u32(head, 80)
        val fourCC = String(head, 84, 4, Charsets.ISO_8859_1)
        val bits = u32(head, 88)
        val masks = intArrayOf(u32(head, 92), u32(head, 96), u32(head, 100), u32(head, 104))
        if (pfFlags and DDPF_FOURCC != 0) {
            val name = fourCC.trimEnd(Char(0), ' ')
            return when (fourCC) {
                "DXT1" -> Layout("DXT1", Dds.Kind.DXT1)
                "DXT2" -> Layout("DXT2", Dds.Kind.DXT3, premultiplied = true)
                "DXT3" -> Layout("DXT3", Dds.Kind.DXT3)
                "DXT4" -> Layout("DXT4", Dds.Kind.DXT5, premultiplied = true)
                "DXT5" -> Layout("DXT5", Dds.Kind.DXT5)
                "DX10" -> "it has a DirectX 10 header (BC7 or another newer format), which BMS Companion does not read: save it as DXT1, DXT5 or 32-bit, or as a PNG."
                "ATI1", "ATI2", "BC4U", "BC4S", "BC5U", "BC5S" -> "it is stored as $name, a one- or two-channel texture (a normal map, for instance), not a picture."
                else -> {
                    val code = u32(head, 84)
                    if (name.isNotEmpty() && name.all { it in ' '..'~' }) "it is stored as $name pixels, which BMS Companion does not read."
                    else "it is stored as Direct3D format $code (floating-point or 16 bits a channel), which BMS Companion does not read."
                }
            }
        }
        val alphaOnly = pfFlags and DDPF_ALPHA != 0 && pfFlags and (DDPF_RGB or DDPF_LUMINANCE) == 0
        val luminance = pfFlags and DDPF_LUMINANCE != 0
        val rgb = pfFlags and DDPF_RGB != 0
        if (!rgb && !luminance && !alphaOnly) return "its pixels are not stored as colour, grey or alpha (YUV or bump-map pixels), which BMS Companion does not read."
        if (bits != 8 && bits != 16 && bits != 24 && bits != 32) return "its pixels are $bits bits each, which BMS Companion does not read."
        val used = if (alphaOnly) masks[3] else masks[0] or masks[1] or masks[2]
        if (used == 0) return "its header gives no bit masks for its pixels."
        val alpha = masks[3] != 0 && (pfFlags and (DDPF_ALPHAPIXELS or DDPF_ALPHA) != 0)
        val format = when {
            alphaOnly -> "A$bits"
            luminance -> (if (alpha) "A" else "") + "L$bits"
            bits == 32 && alpha -> "BGRA"
            bits == 24 -> "RGB24"
            else -> "RGB$bits"
        }
        return Layout(format, null, bpp = bits / 8, masks = masks, alpha = alpha, luminance = luminance, alphaOnly = alphaOnly)
    }

    private fun dds(file: File, enough: (Int, Int) -> Boolean): Read = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        if (length < Dds.HEADER) return Read.Bad("the file is shorter than a DDS header (cut short).")
        val head = ByteArray(Dds.HEADER)
        raf.readFully(head)
        val info = Dds.header(head, length)
        if (info.width <= 0 || info.height <= 0) return Read.Bad((info.unreadable ?: "it is not a DDS texture") + ".")
        if (info.width > MAX_SIDE || info.height > MAX_SIDE) return Read.Bad("its header gives an impossible size (${info.width}×${info.height}).")
        if (info.caps2 and 0x200 != 0) return Read.Bad("it is a cube map, not a picture.")
        if (info.caps2 and 0x200000 != 0 || (info.flags and 0x800000 != 0 && info.depth > 1)) return Read.Bad("it is a volume texture, not a picture.")
        val lay = when (val l = layout(head)) {
            is Layout -> l
            else -> return Read.Bad(l as String)
        }
        val w0 = info.width
        val h0 = info.height
        // rows padded past the pixels (a pitch the header gives and means): level 0 only, read row by row
        val padded = lay.blocks == null && info.flags and DDSD_PITCH != 0 &&
            info.pitch.toLong() > lay.rowBytes(w0) && info.pitch.toLong() <= lay.rowBytes(w0) * 4
        val stride0 = if (padded) info.pitch.toLong() else lay.rowBytes(w0)
        // the levels the file holds whole: its header's count, as far as its length carries them
        val full = 32 - Integer.numberOfLeadingZeros(max(w0, h0))
        val declared = when {
            padded -> 1
            info.flags and DDSD_MIPMAPCOUNT != 0 || info.mipCount > 1 -> info.mipCount.coerceIn(1, full)
            else -> 1
        }
        val offsets = ArrayList<Long>()
        var at = Dds.HEADER.toLong()
        for (l in 0 until declared) {
            val lw = max(1, w0 shr l)
            val lh = max(1, h0 shr l)
            val bytes = if (l == 0 && lay.blocks == null) stride0 * lh else lay.levelBytes(lw, lh)
            if (at + bytes > length) break
            offsets += at
            at += bytes
        }
        if (offsets.isEmpty()) return Read.Bad("the file is shorter than its header says (cut short).")
        // the smallest level still enough for the use
        var level = 0
        while (level + 1 < offsets.size && enough(max(1, w0 shr (level + 1)), max(1, h0 shr (level + 1)))) level++
        val lw = max(1, w0 shr level)
        val lh = max(1, h0 shr level)
        val stride = if (level == 0) stride0 else lay.rowBytes(lw)
        val k = factor(lw, lh)
        val ow = max(1, lw / k)
        val oh = max(1, lh / k)
        val out = IntArray(ow * oh)
        // a band of rows at a time: whole blocks, whole fractions, about 8 MB of pixels
        val unit = if (k % 4 == 0) k else if (k % 2 == 0) k * 2 else k * 4
        val band = max(unit, (2_000_000 / max(1, lw)) / unit * unit)
        val last = if (k == 1) lh else oh * k
        var y0 = 0
        while (y0 < last) {
            val rows = minOf(band, last - y0)
            val px = decodeRows(raf, lay, offsets[level], stride, lw, lh, y0, rows)
            if (k == 1) System.arraycopy(px, 0, out, y0 * ow, rows * ow)
            else average(px, lw, rows, k, out, ow, y0 / k)
            y0 += rows
        }
        if (lay.premultiplied) unpremultiply(out)
        val shown = if (level == 0) lay.format else "${lay.format}, mip level $level"
        Read.Ok(argbImage(out, ow, oh), "DDS $shown", w0, h0)
    }

    /** Rows [y0] to [y0] + [rows] of a [lw] x [lh] level starting at [offset] ([stride] bytes a row), as ARGB. */
    private fun decodeRows(raf: RandomAccessFile, lay: Layout, offset: Long, stride: Long, lw: Int, lh: Int, y0: Int, rows: Int): IntArray {
        val blocks = lay.blocks
        if (blocks != null) {
            // whole block rows, read into a one-level file of their own, where [Dds.decode] looks for them
            val by0 = y0 / 4
            val by1 = (y0 + rows - 1) / 4
            val bh = minOf((by1 - by0 + 1) * 4, max(4, lh - by0 * 4))
            val bytes = ByteArray((Dds.HEADER + lay.rowBytes(lw) * (by1 - by0 + 1)).toInt())
            raf.seek(offset + lay.rowBytes(lw) * by0)
            raf.readFully(bytes, Dds.HEADER, bytes.size - Dds.HEADER)
            val one = Dds.Info(
                lw, bh, 1, blocks, blocks.label, 0, 0, 0, 1, DDPF_FOURCC, 0, IntArray(4), 0x1000, 0, "",
                bytes.size.toLong(), bytes.size.toLong(), null, "a picture",
            )
            return Dds.decode(bytes, one, 0, 0, y0 - by0 * 4, lw, rows)
        }
        val bpp = lay.bpp
        val row = ByteArray(lay.rowBytes(lw).toInt())
        val out = IntArray(lw * rows)
        val shifts = IntArray(4) { if (lay.masks[it] == 0) 0 else Integer.numberOfTrailingZeros(lay.masks[it]) }
        val widths = IntArray(4) { Integer.bitCount(lay.masks[it]) }
        fun channel(v: Int, i: Int): Int {
            val n = widths[i]
            if (n == 0) return 0
            val x = (v ushr shifts[i]) and (if (n >= 32) -1 else (1 shl n) - 1)
            return if (n >= 8) x ushr (n - 8) else (x * 255 + ((1 shl n) - 1) / 2) / ((1 shl n) - 1)
        }
        for (y in 0 until rows) {
            raf.seek(offset + stride * (y0 + y))
            raf.readFully(row)
            var o = 0
            for (x in 0 until lw) {
                var v = 0
                for (b in 0 until bpp) v = v or ((row[o + b].toInt() and 0xFF) shl (8 * b))
                o += bpp
                val a = if (lay.alpha && !lay.alphaOnly) channel(v, 3) else 0xFF
                out[y * lw + x] = when {
                    lay.alphaOnly -> { val g = channel(v, 3); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                    lay.luminance -> { val g = channel(v, 0); (a shl 24) or (g shl 16) or (g shl 8) or g }
                    else -> (a shl 24) or (channel(v, 0) shl 16) or (channel(v, 1) shl 8) or channel(v, 2)
                }
            }
        }
        return out
    }

    /** Each [k] x [k] block of [px] ([w] wide, [rows] tall, a whole number of blocks) averaged into [out] from row [oy]. */
    private fun average(px: IntArray, w: Int, rows: Int, k: Int, out: IntArray, ow: Int, oy: Int) {
        val n = k * k
        for (by in 0 until rows / k) {
            for (bx in 0 until ow) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (j in 0 until k) {
                    var i = (by * k + j) * w + bx * k
                    for (q in 0 until k) {
                        val c = px[i++]
                        a += c ushr 24; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                    }
                }
                out[(oy + by) * ow + bx] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }

    /** DXT2 and DXT4 keep colour multiplied by alpha: divided again, so a half-transparent pixel is its own colour. */
    private fun unpremultiply(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0 || a == 255) continue
            val r = minOf(255, ((c shr 16) and 0xFF) * 255 / a)
            val g = minOf(255, ((c shr 8) and 0xFF) * 255 / a)
            val b = minOf(255, (c and 0xFF) * 255 / a)
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** [px] as an ARGB picture, without a copy. */
    private fun argbImage(px: IntArray, w: Int, h: Int): BufferedImage {
        val m = intArrayOf(0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000.toInt())
        val cm = DirectColorModel(32, m[0], m[1], m[2], m[3])
        val raster = Raster.createPackedRaster(DataBufferInt(px, px.size), w, h, w, m, null)
        return BufferedImage(cm, raster, false, null)
    }
}
