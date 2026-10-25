package com.bmscompanion.desktop.bridge.command

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Falcon BMS's art resource bundles (`<name>.idx` + `<name>.rsc`, listed by the `.irc` files): the 2D campaign map
 * (`CampMap`) and the map's icons (`BluDark`, `RdAir_ne` …). Read only.
 *
 * `.idx`: `u32 size` (the bytes after this 8-byte header), `u32 version`, then records back to back — `u32 type`,
 * `char[32] name`, and by type: 100 an image (`u32 flags, u16 cx, u16 cy, u16 width, u16 height, u32 offset,
 * u32 paletteSize, u32 paletteOffset`, 60 bytes in all), 101 a sound (52), 102 flat data (44). `.rsc`: `u32 size,
 * u32 version`, then the data; every offset counts from byte 8. An image's flags: bit 0 = 8-bit indices into a palette
 * of `paletteSize` RGB555 colours, bit 1 = RGB555 pixels; rows top first, packed.
 */
object ResourceBundle {
    const val IMAGE = 100
    private const val SOUND = 101
    private const val FLAT = 102

    class Image(
        val name: String, val flags: Int, val cx: Int, val cy: Int, val width: Int, val height: Int,
        val offset: Long, val paletteSize: Int, val paletteOffset: Long,
    ) {
        val paletted: Boolean get() = flags and 1 != 0
        val direct: Boolean get() = !paletted && flags and 2 != 0
        val bytesPerPixel: Int get() = if (paletted) 1 else 2
        val pixelBytes: Long get() = width.toLong() * height * bytesPerPixel
    }

    class Bundle(val idx: File, val rsc: File, val version: Int, val images: List<Image>) {
        private val byName = images.associateBy { it.name.uppercase() }
        operator fun get(name: String): Image? = byName[name.uppercase()]
    }

    /** The bundle of [idx] (its `.rsc` beside it, any case); null when either is missing or the index cannot be read. */
    fun read(idx: File): Bundle? = try {
        val rsc = idx.parentFile?.listFiles()?.firstOrNull { it.isFile && it.name.equals(idx.nameWithoutExtension + ".rsc", ignoreCase = true) }
        if (rsc == null) null else {
            val b = ByteBuffer.wrap(idx.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val size = b.getInt(0)
            val version = b.getInt(4)
            val end = minOf(b.capacity(), 8 + size)
            val out = ArrayList<Image>()
            var p = 8
            while (p + 36 <= end) {
                val type = b.getInt(p)
                val name = String(ByteArray(32) { b.get(p + 4 + it) }, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
                val q = p + 36
                when (type) {
                    IMAGE -> {
                        if (q + 24 > end) break
                        out += Image(
                            name, b.getInt(q), b.getShort(q + 4).toInt() and 0xFFFF, b.getShort(q + 6).toInt() and 0xFFFF,
                            b.getShort(q + 8).toInt() and 0xFFFF, b.getShort(q + 10).toInt() and 0xFFFF,
                            b.getInt(q + 12).toLong() and 0xFFFFFFFFL, b.getInt(q + 16), b.getInt(q + 20).toLong() and 0xFFFFFFFFL,
                        )
                        p = q + 24
                    }
                    SOUND -> p = q + 16
                    FLAT -> p = q + 8
                    else -> p = end
                }
            }
            Bundle(idx, rsc, version, out)
        }
    } catch (_: Throwable) {
        null
    }

    /** An RGB555 value as opaque ARGB (each 5-bit channel spread over 8 bits). */
    fun argb555(v: Int): Int {
        val r = (v shr 10) and 31
        val g = (v shr 5) and 31
        val bl = v and 31
        return (0xFF shl 24) or (((r shl 3) or (r shr 2)) shl 16) or (((g shl 3) or (g shr 2)) shl 8) or ((bl shl 3) or (bl shr 2))
    }

    /** [img]'s palette as ARGB, read from the bundle's `.rsc` (an empty array for a picture without one). */
    fun palette(b: Bundle, img: Image): IntArray = try {
        if (!img.paletted || img.paletteSize !in 1..256) IntArray(0) else RandomAccessFile(b.rsc, "r").use { f ->
            val bytes = ByteArray(img.paletteSize * 2)
            f.seek(8 + img.paletteOffset)
            f.readFully(bytes)
            IntArray(img.paletteSize) { i -> argb555((bytes[i * 2].toInt() and 0xFF) or ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8)) }
        }
    } catch (_: Throwable) {
        IntArray(0)
    }

    /**
     * A small picture (an icon) read whole as ARGB, [key] (RGB555, BMS's magenta 0x7C1F by default) made transparent;
     * null when it cannot be read or is larger than [maxPixels].
     */
    fun smallImage(b: Bundle, img: Image, key: Int = 0x7C1F, maxPixels: Int = 1 shl 20): IntArray? = try {
        val n = img.width * img.height
        if (n <= 0 || n > maxPixels) null else RandomAccessFile(b.rsc, "r").use { f ->
            val bytes = ByteArray((img.pixelBytes).toInt())
            f.seek(8 + img.offset)
            f.readFully(bytes)
            if (img.paletted) {
                val raw = ByteArray(img.paletteSize.coerceIn(0, 256) * 2)
                f.seek(8 + img.paletteOffset)
                f.readFully(raw)
                val pal = IntArray(img.paletteSize.coerceIn(0, 256)) { i ->
                    val v = (raw[i * 2].toInt() and 0xFF) or ((raw[i * 2 + 1].toInt() and 0xFF) shl 8)
                    if ((v and 0x7FFF) == key) 0 else argb555(v)
                }
                IntArray(n) { i -> pal.getOrElse(bytes[i].toInt() and 0xFF) { 0 } }
            } else {
                IntArray(n) { i ->
                    val v = (bytes[i * 2].toInt() and 0xFF) or ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8)
                    if ((v and 0x7FFF) == key) 0 else argb555(v)
                }
            }
        }
    } catch (_: Throwable) {
        null
    }
}
