package com.bmscompanion.desktop.bridge

import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * DirectDraw Surface files, as far as Falcon BMS's cockpit kneeboard pages need them: the 128-byte header, and the
 * three formats the pages are found in on an install — DXT1 (BC1) and DXT5 (BC3), with one mip level or the whole
 * chain, and uncompressed 32-bit BGRA (html_brief's). DXT3 and 24-bit files are read (for a picture of what a page
 * holds) but never written; a DX10 header (BC7 and the rest) is neither.
 *
 * **Writing a page keeps the file as it was.** [print] starts from the file's own bytes and replaces only the half page
 * being printed: its format, size, mip count and header stay (only the header's unused `dwReserved1` field, offset 32,
 * gets a tag), and the other half's compressed blocks are copied, never decoded and encoded again. A DXT block is 4×4
 * pixels and the halves meet at a block edge, so that holds for every mip level at least 8 pixels wide (0-8 in a
 * 2048-pixel file); the three smallest levels, where one block spans both halves, are made again from both halves.
 *
 * The encoder is a port of `stb_dxt` (public domain, Fabian Giesen and Sean Barrett): the principal axis of the
 * block's colours picks the end points, a least-squares pass refines them twice, and every colour block is written in
 * the four-colour mode (`c0 > c1`; a block of one colour gets index 0 throughout), because in DXT1's three-colour mode
 * index 3 is transparent black. DXT5's alpha block is always opaque (`FF FF 00 00 00 00 00 00`), as every page BMS
 * ships is.
 *
 * Nothing here touches the disk: callers pass bytes and get bytes. Reading never throws on a bad file: [header] says
 * what is wrong in [Info.unreadable] and [Info.unwritable].
 */
object Dds {
    const val HEADER = 128
    const val TAG_OFFSET = 32
    const val TAG_LENGTH = 44

    private const val DDSD_PITCH = 0x8
    private const val DDSD_MIPMAPCOUNT = 0x20000
    private const val DDPF_ALPHAPIXELS = 0x1
    private const val DDPF_FOURCC = 0x4
    private const val DDPF_RGB = 0x40

    /** The layouts this object knows. [block] is the side of a block in pixels (1 for uncompressed), [bytes] its size. */
    enum class Kind(val label: String, val block: Int, val bytes: Int, val writable: Boolean) {
        DXT1("DXT1", 4, 8, true),
        DXT3("DXT3", 4, 16, false),
        DXT5("DXT5", 4, 16, true),
        BGRA("BGRA", 1, 4, true),
        RGB24("RGB24", 1, 3, false),
        OTHER("other", 0, 0, false),
    }

    /** What a file's header says, and whether the rest of the file agrees with it. */
    class Info(
        val width: Int,
        val height: Int,
        /** levels actually stored (a header's mip count of 0 is one level) */
        val levels: Int,
        val kind: Kind,
        /** "DXT1", "DXT5", "BGRA", "DXT3", "RGB24", "DX10", or the four-character code */
        val format: String,
        val flags: Int,
        val pitch: Int,
        val depth: Int,
        val mipCount: Int,
        val pfFlags: Int,
        val bits: Int,
        val masks: IntArray,
        val caps: Int,
        val caps2: Int,
        /** the text in `dwReserved1` (offset 32): `IMAGEMAGICK`, `BMSCOMPANION…`, or empty */
        val tag: String,
        val fileLength: Long,
        /** the length the header describes */
        val expectedLength: Long,
        /** why the pixels cannot be read, or null */
        val unreadable: String?,
        /** why this program will not write the file, or null */
        val unwritable: String?,
    ) {
        val readable: Boolean get() = unreadable == null
        val writable: Boolean get() = unwritable == null
        /** the file is empty, or shorter than its header says */
        val cutShort: Boolean get() = fileLength == 0L || fileLength < HEADER || (expectedLength > 0 && fileLength < expectedLength)
        fun levelWidth(l: Int) = max(1, width shr l)
        fun levelHeight(l: Int) = max(1, height shr l)
        fun levelBytes(l: Int): Long = levelBytes(kind, levelWidth(l), levelHeight(l))
        fun levelOffset(l: Int): Long {
            var o = HEADER.toLong()
            for (i in 0 until l) o += levelBytes(i)
            return o
        }
    }

    private fun levelBytes(kind: Kind, w: Int, h: Int): Long =
        if (kind.block == 4) max(1, (w + 3) / 4).toLong() * max(1, (h + 3) / 4) * kind.bytes
        else w.toLong() * h * kind.bytes

    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }

    /**
     * Reads the header of [bytes] (at least the first 128 bytes of a file; [fileLength] is the whole file's length, so
     * a caller may pass the header alone). Never throws.
     */
    fun header(bytes: ByteArray, fileLength: Long = bytes.size.toLong()): Info {
        fun bad(why: String) = Info(0, 0, 0, Kind.OTHER, "?", 0, 0, 0, 0, 0, 0, IntArray(4), 0, 0, "", fileLength, 0, why, why)
        if (fileLength == 0L) return bad("the file is empty")
        if (bytes.size < HEADER || fileLength < HEADER) return bad("the file is shorter than a DDS header (cut short)")
        if (bytes[0] != 'D'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != 'S'.code.toByte() || bytes[3] != ' '.code.toByte())
            return bad("the file is not a DDS texture")
        if (u32(bytes, 4) != 124) return bad("its DDS header has an unknown size")
        val flags = u32(bytes, 8)
        val height = u32(bytes, 12)
        val width = u32(bytes, 16)
        val pitch = u32(bytes, 20)
        val depth = u32(bytes, 24)
        val mipCount = u32(bytes, 28)
        val tag = String(bytes, TAG_OFFSET, TAG_LENGTH, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
        val pfFlags = u32(bytes, 80)
        val fourCC = String(bytes, 84, 4, Charsets.ISO_8859_1)
        val bits = u32(bytes, 88)
        val masks = intArrayOf(u32(bytes, 92), u32(bytes, 96), u32(bytes, 100), u32(bytes, 104))
        val caps = u32(bytes, 108)
        val caps2 = u32(bytes, 112)

        var format: String
        val kind: Kind = when {
            pfFlags and DDPF_FOURCC != 0 -> {
                format = fourCC.trimEnd('\u0000', ' ')
                when (fourCC) {
                    "DXT1" -> Kind.DXT1
                    "DXT3" -> Kind.DXT3
                    "DXT5" -> Kind.DXT5
                    else -> Kind.OTHER
                }
            }
            pfFlags and DDPF_RGB != 0 && bits == 32 && byteMasks(masks, alpha = pfFlags and DDPF_ALPHAPIXELS != 0) -> { format = "BGRA"; Kind.BGRA }
            pfFlags and DDPF_RGB != 0 && bits == 24 && byteMasks(masks.copyOf(3).let { intArrayOf(it[0], it[1], it[2], 0) }, alpha = false) -> { format = "RGB24"; Kind.RGB24 }
            else -> { format = if (pfFlags and DDPF_RGB != 0) "RGB$bits" else "unknown"; Kind.OTHER }
        }
        if (format.isEmpty()) format = "unknown"
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384)
            return Info(width, height, 0, kind, format, flags, pitch, depth, mipCount, pfFlags, bits, masks, caps, caps2, tag, fileLength, 0,
                "its header gives an impossible size ($width×$height)", "its header gives an impossible size ($width×$height)")

        if (kind == Kind.OTHER) {
            val what = if (format == "DX10") "a DirectX 10 header (BC7 or another newer format)" else "$format pixels"
            val why = "it is stored as $what, which BMS Companion does not read or write"
            return Info(width, height, max(1, mipCount), kind, format, flags, pitch, depth, mipCount, pfFlags, bits, masks, caps, caps2, tag, fileLength, 0, why, why)
        }

        // the levels stored: the header's count when the file is exactly that long, else one level when it is exactly that
        val full = 32 - Integer.numberOfLeadingZeros(max(width, height))
        val declared = if (mipCount <= 0) 1 else min(mipCount, full)
        fun length(levels: Int): Long {
            var n = HEADER.toLong()
            for (l in 0 until levels) n += levelBytes(kind, max(1, width shr l), max(1, height shr l))
            return n
        }
        val levels = when {
            length(declared) == fileLength -> declared
            flags and DDSD_MIPMAPCOUNT == 0 && length(1) == fileLength -> 1
            else -> declared
        }
        val expected = length(levels)
        val unreadable = when {
            mipCount > full -> "its header claims more mip levels ($mipCount) than a ${width}×$height texture has"
            fileLength < expected -> "the file is shorter than its header says (cut short: $fileLength of $expected bytes)"
            fileLength > expected -> "the file is longer than its header says ($fileLength of $expected bytes)"
            else -> null
        }
        val pow2 = width and (width - 1) == 0 && height and (height - 1) == 0
        val unwritable = unreadable ?: when {
            !kind.writable -> "it is stored as $format, which BMS Companion reads but does not write"
            caps2 != 0 -> "it is a cube map or a volume texture"
            width != height -> "it is not square (${width}×$height)"
            !pow2 || width < 8 -> "its size (${width}×$height) is not one BMS Companion writes"
            kind == Kind.BGRA && flags and DDSD_PITCH != 0 && pitch != width * 4 -> "its rows are padded (pitch $pitch for $width pixels)"
            else -> null
        }
        return Info(width, height, levels, kind, format, flags, pitch, depth, mipCount, pfFlags, bits, masks, caps, caps2, tag, fileLength, expected, unreadable, unwritable)
    }

    /** Every non-zero mask is one whole byte, and no two share one (the red, green and blue masks must be there). */
    private fun byteMasks(m: IntArray, alpha: Boolean): Boolean {
        val seen = HashSet<Int>()
        for (i in 0 until 4) {
            val v = m[i]
            if (v == 0) { if (i < 3) return false else continue }
            val shift = Integer.numberOfTrailingZeros(v)
            if (shift % 8 != 0 || (v ushr shift) != 0xFF) return false
            if (!seen.add(shift)) return false
        }
        return true
    }

    // ================================================================ decoding

    private val EXPAND5 = IntArray(32) { (it shl 3) or (it shr 2) }
    private val EXPAND6 = IntArray(64) { (it shl 2) or (it shr 4) }

    private fun rgb565(c: Int): Int {
        val r = EXPAND5[(c shr 11) and 31]
        val g = EXPAND6[(c shr 5) and 63]
        val b = EXPAND5[c and 31]
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mix(a: Int, b: Int, wa: Int, wb: Int, d: Int): Int {
        val r = (((a shr 16) and 0xFF) * wa + ((b shr 16) and 0xFF) * wb) / d
        val g = (((a shr 8) and 0xFF) * wa + ((b shr 8) and 0xFF) * wb) / d
        val bl = ((a and 0xFF) * wa + (b and 0xFF) * wb) / d
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** One colour block at [o] into [out] (16 ARGB pixels). [fourColour] for DXT3/5, whose colour block has no alpha mode. */
    private fun colourBlock(b: ByteArray, o: Int, out: IntArray, fourColour: Boolean) {
        val c0 = u16(b, o)
        val c1 = u16(b, o + 2)
        val p0 = rgb565(c0)
        val p1 = rgb565(c1)
        val p2: Int
        val p3: Int
        if (fourColour || c0 > c1) {
            p2 = mix(p0, p1, 2, 1, 3)
            p3 = mix(p0, p1, 1, 2, 3)
        } else {
            p2 = mix(p0, p1, 1, 1, 2)
            p3 = 0 // transparent black
        }
        val idx = u32(b, o + 4)
        for (i in 0 until 16) {
            out[i] = when ((idx ushr (2 * i)) and 3) {
                0 -> p0
                1 -> p1
                2 -> p2
                else -> p3
            }
        }
    }

    private fun alphaDxt5(b: ByteArray, o: Int, out: IntArray) {
        val a0 = b[o].toInt() and 0xFF
        val a1 = b[o + 1].toInt() and 0xFF
        val a = IntArray(8)
        a[0] = a0; a[1] = a1
        if (a0 > a1) for (i in 1..6) a[i + 1] = ((7 - i) * a0 + i * a1) / 7
        else { for (i in 1..4) a[i + 1] = ((5 - i) * a0 + i * a1) / 5; a[6] = 0; a[7] = 255 }
        var bitsLo = 0L
        for (i in 0 until 6) bitsLo = bitsLo or ((b[o + 2 + i].toLong() and 0xFF) shl (8 * i))
        for (i in 0 until 16) {
            val alpha = a[((bitsLo ushr (3 * i)) and 7).toInt()]
            out[i] = (out[i] and 0x00FFFFFF) or (alpha shl 24)
        }
    }

    private fun alphaDxt3(b: ByteArray, o: Int, out: IntArray) {
        for (i in 0 until 16) {
            val nib = ((b[o + i / 2].toInt() and 0xFF) shr ((i and 1) * 4)) and 15
            out[i] = (out[i] and 0x00FFFFFF) or ((nib * 17) shl 24)
        }
    }

    /**
     * The pixels of the rectangle [x0],[y0] [w]×[h] of mip level [level], as ARGB. The file must be [Info.readable]
     * (a caller that has not checked gets an exception, which is its own fault).
     */
    fun decode(bytes: ByteArray, info: Info, level: Int, x0: Int, y0: Int, w: Int, h: Int): IntArray {
        require(info.readable) { info.unreadable ?: "unreadable" }
        val lw = info.levelWidth(level)
        val lh = info.levelHeight(level)
        require(x0 >= 0 && y0 >= 0 && x0 + w <= lw && y0 + h <= lh) { "outside the level" }
        val base = info.levelOffset(level).toInt()
        val out = IntArray(w * h)
        when (info.kind) {
            Kind.DXT1, Kind.DXT3, Kind.DXT5 -> {
                val bw = max(1, (lw + 3) / 4)
                val px = IntArray(16)
                val bx0 = x0 / 4; val bx1 = (x0 + w - 1) / 4
                val by0 = y0 / 4; val by1 = (y0 + h - 1) / 4
                for (by in by0..by1) for (bx in bx0..bx1) {
                    val o = base + (by * bw + bx) * info.kind.bytes
                    when (info.kind) {
                        Kind.DXT1 -> colourBlock(bytes, o, px, fourColour = false)
                        Kind.DXT3 -> { colourBlock(bytes, o + 8, px, fourColour = true); alphaDxt3(bytes, o, px) }
                        else -> { colourBlock(bytes, o + 8, px, fourColour = true); alphaDxt5(bytes, o, px) }
                    }
                    for (j in 0 until 4) {
                        val y = by * 4 + j - y0
                        if (y < 0 || y >= h) continue
                        for (i in 0 until 4) {
                            val x = bx * 4 + i - x0
                            if (x < 0 || x >= w) continue
                            out[y * w + x] = px[j * 4 + i]
                        }
                    }
                }
            }
            Kind.BGRA, Kind.RGB24 -> {
                val bpp = info.kind.bytes
                val shifts = info.masks.map { if (it == 0) -1 else Integer.numberOfTrailingZeros(it) }
                val hasAlpha = info.kind == Kind.BGRA && info.masks[3] != 0 && info.pfFlags and DDPF_ALPHAPIXELS != 0
                for (y in 0 until h) {
                    var o = base + ((y0 + y) * lw + x0) * bpp
                    for (x in 0 until w) {
                        val v = if (bpp == 4) u32(bytes, o) else ((bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or ((bytes[o + 2].toInt() and 0xFF) shl 16))
                        val r = (v ushr shifts[0]) and 0xFF
                        val g = (v ushr shifts[1]) and 0xFF
                        val b = (v ushr shifts[2]) and 0xFF
                        val a = if (hasAlpha) (v ushr shifts[3]) and 0xFF else 0xFF
                        out[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        o += bpp
                    }
                }
            }
            Kind.OTHER -> error("unreadable")
        }
        return out
    }

    /** A whole mip level as a picture (opaque: alpha is dropped, as the cockpit shows it). */
    fun image(bytes: ByteArray, info: Info, level: Int = 0): BufferedImage {
        val w = info.levelWidth(level)
        val h = info.levelHeight(level)
        val px = decode(bytes, info, level, 0, 0, w, h)
        return toImage(px, w, h)
    }

    fun toImage(px: IntArray, w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, px, 0, w)
        return img
    }

    // ================================================================ encoding (stb_dxt)

    private fun mul8Bit(a: Int, b: Int): Int {
        val t = a * b + 128
        return (t + (t shr 8)) shr 8
    }

    private fun as16Bit(r: Int, g: Int, b: Int): Int = (mul8Bit(r, 31) shl 11) + (mul8Bit(g, 63) shl 5) + mul8Bit(b, 31)

    private fun lerp13(a: Int, b: Int): Int = (2 * a + b) / 3

    /** stb__PrepareOptTable: for each 8-bit value, the pair of 5- (or 6-) bit end points whose 1/3 point matches it best. */
    private fun optTable(expand: IntArray): Array<IntArray> = Array(256) { i ->
        var best = 256
        var bmx = 0
        var bmn = 0
        for (mn in expand.indices) for (mx in expand.indices) {
            val mine = expand[mn]
            val maxe = expand[mx]
            var err = abs(lerp13(maxe, mine) - i)
            err += abs(maxe - mine) * 3 / 100
            if (err < best) { best = err; bmx = mx; bmn = mn }
        }
        intArrayOf(bmx, bmn)
    }

    private val OMATCH5 by lazy { optTable(EXPAND5) }
    private val OMATCH6 by lazy { optTable(EXPAND6) }
    private val MID5 = FloatArray(32) { if (it == 31) 1f else (EXPAND5[it] + EXPAND5[it + 1]) / 2f / 255f }
    private val MID6 = FloatArray(64) { if (it == 63) 1f else (EXPAND6[it] + EXPAND6[it + 1]) / 2f / 255f }

    private fun quantize5(v: Float): Int {
        val x = v.coerceIn(0f, 1f)
        var q = (x * 31).toInt()
        if (x > MID5[q]) q++
        return q
    }

    private fun quantize6(v: Float): Int {
        val x = v.coerceIn(0f, 1f)
        var q = (x * 63).toInt()
        if (x > MID6[q]) q++
        return q
    }

    /** The four colours of a block with end points [c0], [c1], as r,g,b triples (stb__EvalColors). */
    private fun evalColours(c0: Int, c1: Int, out: IntArray) {
        val p0 = rgb565(c0)
        val p1 = rgb565(c1)
        val r0 = (p0 shr 16) and 0xFF; val g0 = (p0 shr 8) and 0xFF; val b0 = p0 and 0xFF
        val r1 = (p1 shr 16) and 0xFF; val g1 = (p1 shr 8) and 0xFF; val b1 = p1 and 0xFF
        out[0] = r0; out[1] = g0; out[2] = b0
        out[3] = r1; out[4] = g1; out[5] = b1
        out[6] = lerp13(r0, r1); out[7] = lerp13(g0, g1); out[8] = lerp13(b0, b1)
        out[9] = lerp13(r1, r0); out[10] = lerp13(g1, g0); out[11] = lerp13(b1, b0)
    }

    /** stb__MatchColorsBlock: each pixel's index, by projecting onto the line between the end points. */
    private fun matchColours(rgb: IntArray, colour: IntArray): Int {
        val dirr = colour[0] - colour[3]
        val dirg = colour[1] - colour[4]
        val dirb = colour[2] - colour[5]
        val stops = IntArray(4) { colour[it * 3] * dirr + colour[it * 3 + 1] * dirg + colour[it * 3 + 2] * dirb }
        val c0Point = stops[1] + stops[3]
        val halfPoint = stops[3] + stops[2]
        val c3Point = stops[2] + stops[0]
        var mask = 0
        for (i in 15 downTo 0) {
            val dot = (rgb[i * 3] * dirr + rgb[i * 3 + 1] * dirg + rgb[i * 3 + 2] * dirb) * 2
            mask = mask shl 2
            mask = mask or if (dot < halfPoint) (if (dot < c0Point) 1 else 3) else (if (dot < c3Point) 2 else 0)
        }
        return mask
    }

    /** stb__OptimizeColorsBlock: the end points from the principal axis of the block's colours. Returns (max16, min16). */
    private fun optimiseColours(rgb: IntArray): IntArray {
        val mu = IntArray(3)
        val mn = IntArray(3)
        val mx = IntArray(3)
        for (ch in 0 until 3) {
            var muv = rgb[ch]; var minv = muv; var maxv = muv
            for (i in 1 until 16) {
                val v = rgb[i * 3 + ch]
                muv += v
                if (v < minv) minv = v else if (v > maxv) maxv = v
            }
            mu[ch] = (muv + 8) shr 4
            mn[ch] = minv
            mx[ch] = maxv
        }
        val cov = IntArray(6)
        for (i in 0 until 16) {
            val r = rgb[i * 3] - mu[0]
            val g = rgb[i * 3 + 1] - mu[1]
            val b = rgb[i * 3 + 2] - mu[2]
            cov[0] += r * r; cov[1] += r * g; cov[2] += r * b
            cov[3] += g * g; cov[4] += g * b; cov[5] += b * b
        }
        val covf = FloatArray(6) { cov[it] / 255f }
        var vfr = (mx[0] - mn[0]).toFloat()
        var vfg = (mx[1] - mn[1]).toFloat()
        var vfb = (mx[2] - mn[2]).toFloat()
        repeat(4) {
            val r = vfr * covf[0] + vfg * covf[1] + vfb * covf[2]
            val g = vfr * covf[1] + vfg * covf[3] + vfb * covf[4]
            val b = vfr * covf[2] + vfg * covf[4] + vfb * covf[5]
            vfr = r; vfg = g; vfb = b
        }
        var magn = abs(vfr).toDouble()
        if (abs(vfg) > magn) magn = abs(vfg).toDouble()
        if (abs(vfb) > magn) magn = abs(vfb).toDouble()
        val vr: Int; val vg: Int; val vb: Int
        if (magn < 4.0) { vr = 299; vg = 587; vb = 114 } else {
            magn = 512.0 / magn
            vr = (vfr * magn).toInt(); vg = (vfg * magn).toInt(); vb = (vfb * magn).toInt()
        }
        var minp = 0; var maxp = 0
        var mind = rgb[0] * vr + rgb[1] * vg + rgb[2] * vb
        var maxd = mind
        for (i in 1 until 16) {
            val dot = rgb[i * 3] * vr + rgb[i * 3 + 1] * vg + rgb[i * 3 + 2] * vb
            if (dot < mind) { mind = dot; minp = i }
            if (dot > maxd) { maxd = dot; maxp = i }
        }
        return intArrayOf(
            as16Bit(rgb[maxp * 3], rgb[maxp * 3 + 1], rgb[maxp * 3 + 2]),
            as16Bit(rgb[minp * 3], rgb[minp * 3 + 1], rgb[minp * 3 + 2]),
        )
    }

    private val W1TAB = intArrayOf(3, 0, 2, 1)
    private val PRODS = intArrayOf(0x090000, 0x000900, 0x040102, 0x010402)

    /** stb__RefineBlock: least-squares end points for the indices chosen. [ends] is (max16, min16), updated; true when they moved. */
    private fun refine(rgb: IntArray, ends: IntArray, mask: Int): Boolean {
        val oldMax = ends[0]
        val oldMin = ends[1]
        val max16: Int
        val min16: Int
        // every pixel has the same index: the system would be singular, so match the average colour instead
        if (Integer.compareUnsigned(mask xor (mask shl 2), 4) < 0) {
            var r = 8; var g = 8; var b = 8
            for (i in 0 until 16) { r += rgb[i * 3]; g += rgb[i * 3 + 1]; b += rgb[i * 3 + 2] }
            r = r shr 4; g = g shr 4; b = b shr 4
            max16 = (OMATCH5[r][0] shl 11) or (OMATCH6[g][0] shl 5) or OMATCH5[b][0]
            min16 = (OMATCH5[r][1] shl 11) or (OMATCH6[g][1] shl 5) or OMATCH5[b][1]
        } else {
            var akku = 0
            var at1r = 0; var at1g = 0; var at1b = 0
            var at2r = 0; var at2g = 0; var at2b = 0
            var cm = mask
            for (i in 0 until 16) {
                val step = cm and 3
                val w1 = W1TAB[step]
                val r = rgb[i * 3]; val g = rgb[i * 3 + 1]; val b = rgb[i * 3 + 2]
                akku += PRODS[step]
                at1r += w1 * r; at1g += w1 * g; at1b += w1 * b
                at2r += r; at2g += g; at2b += b
                cm = cm ushr 2
            }
            at2r = 3 * at2r - at1r
            at2g = 3 * at2g - at1g
            at2b = 3 * at2b - at1b
            val xx = akku shr 16
            val yy = (akku shr 8) and 0xFF
            val xy = akku and 0xFF
            val f = 3.0f / 255.0f / (xx * yy - xy * xy)
            max16 = (quantize5((at1r * yy - at2r * xy) * f) shl 11) or
                (quantize6((at1g * yy - at2g * xy) * f) shl 5) or
                quantize5((at1b * yy - at2b * xy) * f)
            min16 = (quantize5((at2r * xx - at1r * xy) * f) shl 11) or
                (quantize6((at2g * xx - at1g * xy) * f) shl 5) or
                quantize5((at2b * xx - at1b * xy) * f)
        }
        ends[0] = max16
        ends[1] = min16
        return oldMin != min16 || oldMax != max16
    }

    /**
     * One DXT colour block (8 bytes at [o] in [dest]) for 16 ARGB pixels (stb__CompressColorBlock, high quality), always
     * in the four-colour mode: `c0 > c1`, or `c0 == c1` with every index 0.
     */
    fun encodeColourBlock(px: IntArray, dest: ByteArray, o: Int) {
        val rgb = IntArray(48)
        for (i in 0 until 16) {
            val p = px[i]
            rgb[i * 3] = (p shr 16) and 0xFF; rgb[i * 3 + 1] = (p shr 8) and 0xFF; rgb[i * 3 + 2] = p and 0xFF
        }
        var mask: Int
        val ends = IntArray(2)
        var constant = true
        for (i in 1 until 16) if ((px[i] and 0xFFFFFF) != (px[0] and 0xFFFFFF)) { constant = false; break }
        if (constant) {
            val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
            mask = 0xAAAAAAAA.toInt()
            ends[0] = (OMATCH5[r][0] shl 11) or (OMATCH6[g][0] shl 5) or OMATCH5[b][0]
            ends[1] = (OMATCH5[r][1] shl 11) or (OMATCH6[g][1] shl 5) or OMATCH5[b][1]
        } else {
            val colour = IntArray(12)
            val e = optimiseColours(rgb)
            ends[0] = e[0]; ends[1] = e[1]
            mask = if (ends[0] != ends[1]) { evalColours(ends[0], ends[1], colour); matchColours(rgb, colour) } else 0
            for (pass in 0 until 2) {
                val last = mask
                if (refine(rgb, ends, mask)) {
                    if (ends[0] != ends[1]) { evalColours(ends[0], ends[1], colour); mask = matchColours(rgb, colour) }
                    else { mask = 0; break }
                }
                if (mask == last) break
            }
        }
        var max16 = ends[0]
        var min16 = ends[1]
        if (max16 < min16) {
            val t = min16; min16 = max16; max16 = t
            mask = mask xor 0x55555555
        }
        // one colour: DXT1 would read c0 == c1 as its three-colour mode, where index 3 is transparent black
        if (max16 == min16) mask = 0
        dest[o] = max16.toByte(); dest[o + 1] = (max16 shr 8).toByte()
        dest[o + 2] = min16.toByte(); dest[o + 3] = (min16 shr 8).toByte()
        put32(dest, o + 4, mask)
    }

    /** DXT5's alpha block for an opaque page: both end points 255, every index 0. */
    private val OPAQUE_ALPHA = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0)

    /**
     * Encodes the [w]×[h] ARGB picture [px] into the blocks (or pixels) of level [level] of [dest], starting at pixel
     * column [x0] of that level, leaving every other block as it is. Pictures smaller than a block are padded by
     * repeating their edge.
     */
    private fun encodeRegion(dest: ByteArray, info: Info, level: Int, x0: Int, px: IntArray, w: Int, h: Int) {
        val base = info.levelOffset(level).toInt()
        val lw = info.levelWidth(level)
        when (info.kind) {
            Kind.DXT1, Kind.DXT5 -> {
                val bw = max(1, (lw + 3) / 4)
                val blk = IntArray(16)
                val bxs = max(1, (w + 3) / 4)
                val bys = max(1, (h + 3) / 4)
                for (by in 0 until bys) for (bx in 0 until bxs) {
                    for (j in 0 until 4) for (i in 0 until 4) {
                        val x = min(bx * 4 + i, w - 1)
                        val y = min(by * 4 + j, h - 1)
                        blk[j * 4 + i] = px[y * w + x]
                    }
                    val o = base + (by * bw + x0 / 4 + bx) * info.kind.bytes
                    if (info.kind == Kind.DXT5) {
                        System.arraycopy(OPAQUE_ALPHA, 0, dest, o, 8)
                        encodeColourBlock(blk, dest, o + 8)
                    } else encodeColourBlock(blk, dest, o)
                }
            }
            Kind.BGRA -> {
                val shifts = info.masks.map { if (it == 0) -1 else Integer.numberOfTrailingZeros(it) }
                // the byte no mask covers (an X8R8G8B8 file) is written 255, as an opaque alpha would be
                val covered = info.masks.fold(0) { a, m -> a or m }
                val spare = covered.inv()
                for (y in 0 until h) {
                    var o = base + (y * lw + x0) * 4
                    for (x in 0 until w) {
                        val p = px[y * w + x]
                        var v = (((p shr 16) and 0xFF) shl shifts[0]) or (((p shr 8) and 0xFF) shl shifts[1]) or ((p and 0xFF) shl shifts[2])
                        v = if (shifts[3] >= 0) v or (0xFF shl shifts[3]) else v or spare
                        put32(dest, o, v)
                        o += 4
                    }
                }
            }
            else -> error("not writable")
        }
    }

    /** Halves a picture with a 2×2 box filter (a side of 1 stays 1). */
    fun boxDown(px: IntArray, w: Int, h: Int): IntArray {
        val nw = max(1, w / 2)
        val nh = max(1, h / 2)
        val out = IntArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            val xa = min(2 * x, w - 1); val xb = min(2 * x + 1, w - 1)
            val ya = min(2 * y, h - 1); val yb = min(2 * y + 1, h - 1)
            val a = px[ya * w + xa]; val b = px[ya * w + xb]; val c = px[yb * w + xa]; val d = px[yb * w + xb]
            fun ch(s: Int) = ((((a shr s) and 0xFF) + ((b shr s) and 0xFF) + ((c shr s) and 0xFF) + ((d shr s) and 0xFF) + 2) / 4)
            out[y * nw + x] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return out
    }

    /**
     * The page file [current] with the halves in [halves] (index 0 the left knee, 1 the right; each the half's picture
     * at full size, `width/2 × height` ARGB, or null to leave that half as it is) printed into it, and the header's
     * `dwReserved1` set to [tag] (up to 44 ASCII characters; null keeps it).
     *
     * The result has exactly the length, header (but for the tag), format and mip count of [current]. A half left as it
     * is keeps its bytes for every level at least 8 pixels wide (DXT) or 2 pixels wide (BGRA); the levels below that are
     * made from both halves. [info] must be `header(current)` and [Info.writable].
     */
    fun print(current: ByteArray, info: Info, halves: Array<IntArray?>, tag: String?): ByteArray {
        require(info.writable) { info.unwritable ?: "not writable" }
        require(halves.size == 2)
        val w = info.width
        val h = info.height
        val hw = w / 2
        halves.forEach { r -> if (r != null) require(r.size == hw * h) { "a half must be ${hw}×$h" } }
        val out = current.copyOf()
        if (tag != null) {
            val t = tag.toByteArray(Charsets.US_ASCII)
            for (i in 0 until TAG_LENGTH) out[TAG_OFFSET + i] = if (i < t.size) t[i] else 0
        }
        if (halves.all { it == null }) return out

        val bd = info.kind.block
        fun copyable(l: Int): Boolean { val lw = info.levelWidth(l); return lw >= 2 * bd && (lw / 2) % bd == 0 }
        // each printed half's own mip chain, as far as its levels can be written on their own
        val chain = Array(2) { ArrayList<IntArray>() }
        for (s in 0..1) {
            val r = halves[s] ?: continue
            chain[s].add(r)
        }
        var l = 0
        while (l < info.levels && copyable(l)) {
            val lw = info.levelWidth(l)
            val lh = info.levelHeight(l)
            for (s in 0..1) {
                if (halves[s] == null) continue
                if (chain[s].size <= l) {
                    val prev = chain[s][l - 1]
                    chain[s].add(boxDown(prev, info.levelWidth(l - 1) / 2, info.levelHeight(l - 1)))
                }
                encodeRegion(out, info, l, s * (lw / 2), chain[s][l], lw / 2, lh)
            }
            l++
        }
        if (l < info.levels) {
            // the smallest levels span both halves: the level above, put together from both, box-filtered down
            val pl = l - 1
            val pw = info.levelWidth(pl)
            val ph = info.levelHeight(pl)
            var full = IntArray(pw * ph)
            for (s in 0..1) {
                val part = if (halves[s] != null) chain[s][pl] else decode(current, info, pl, s * (pw / 2), 0, pw / 2, ph)
                for (y in 0 until ph) System.arraycopy(part, y * (pw / 2), full, y * pw + s * (pw / 2), pw / 2)
            }
            var fw = pw; var fh = ph
            while (l < info.levels) {
                full = boxDown(full, fw, fh)
                fw = max(1, fw / 2); fh = max(1, fh / 2)
                encodeRegion(out, info, l, 0, full, fw, fh)
                l++
            }
        }
        return out
    }

    // ================================================================ measuring

    /** Peak signal-to-noise ratio of two ARGB pictures over red, green and blue, in dB (99 when identical). */
    fun psnr(a: IntArray, b: IntArray): Double {
        require(a.size == b.size)
        var se = 0.0
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            val dr = ((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)
            val dg = ((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)
            val db = (x and 0xFF) - (y and 0xFF)
            se += (dr * dr + dg * dg + db * db).toDouble()
        }
        if (se == 0.0) return 99.0
        val mse = se / (a.size * 3.0)
        return 10 * log10(255.0 * 255.0 / mse)
    }

    /** The largest difference in any of red, green or blue between two ARGB pictures, and how many pixels differ by more than [tolerance]. */
    fun maxDiff(a: IntArray, b: IntArray, tolerance: Int = 2): Pair<Int, Int> {
        var worst = 0
        var over = 0
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            val d = maxOf(abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)), abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)), abs((x and 0xFF) - (y and 0xFF)))
            if (d > worst) worst = d
            if (d > tolerance) over++
        }
        return worst to over
    }

    /** A header for a new file (for the checks' synthetic pages): [kind] DXT1, DXT5 or BGRA, [levels] 1 or more. */
    fun newHeader(kind: Kind, width: Int, height: Int, levels: Int): ByteArray {
        val b = ByteArray(HEADER)
        b[0] = 'D'.code.toByte(); b[1] = 'D'.code.toByte(); b[2] = 'S'.code.toByte(); b[3] = ' '.code.toByte()
        put32(b, 4, 124)
        var flags = 0x1007 // caps, height, width, pixel format
        flags = if (kind == Kind.BGRA) flags or DDSD_PITCH else flags or 0x80000 // pitch / linear size
        if (levels > 1) flags = flags or DDSD_MIPMAPCOUNT
        put32(b, 8, flags)
        put32(b, 12, height)
        put32(b, 16, width)
        put32(b, 20, if (kind == Kind.BGRA) width * 4 else levelBytes(kind, width, height).toInt())
        put32(b, 24, 0)
        put32(b, 28, if (levels > 1) levels else 0)
        put32(b, 76, 32)
        when (kind) {
            Kind.DXT1, Kind.DXT5 -> { put32(b, 80, DDPF_FOURCC); System.arraycopy(kind.label.toByteArray(Charsets.US_ASCII), 0, b, 84, 4) }
            Kind.BGRA -> {
                put32(b, 80, DDPF_RGB or DDPF_ALPHAPIXELS); put32(b, 88, 32)
                put32(b, 92, 0xFF0000); put32(b, 96, 0xFF00); put32(b, 100, 0xFF); put32(b, 104, 0xFF000000.toInt())
            }
            else -> error("newHeader: $kind")
        }
        put32(b, 108, if (levels > 1) 0x401008 else 0x1000)
        return b
    }

    /** A whole new file of one colour (for the checks). */
    fun newFile(kind: Kind, size: Int, levels: Int, argb: Int): ByteArray {
        val head = newHeader(kind, size, size, levels)
        var len = HEADER.toLong()
        for (l in 0 until levels) len += levelBytes(kind, max(1, size shr l), max(1, size shr l))
        val out = head.copyOf(len.toInt())
        val info = header(out)
        check(info.writable) { info.unwritable ?: "not writable" }
        for (l in 0 until levels) {
            val lw = info.levelWidth(l); val lh = info.levelHeight(l)
            encodeRegion(out, info, l, 0, IntArray(lw * lh) { argb }, lw, lh)
        }
        return out
    }
}
