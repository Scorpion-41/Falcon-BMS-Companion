package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.CampaignArchive
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** A campaign entity's id (number and creator), the same class the rest of the PC side reads saves with. */
typealias VuId = CampaignArchive.VuId

/** Text in a save is Windows' ANSI code page. */
internal val ANSI: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

/** Reading ran past the end of a part: the walk stops with this reason. */
class ShortRead(message: String) : RuntimeException(message)

/**
 * A little-endian reader over [b] from [p]. Every read checks the bytes are there and throws [ShortRead] otherwise,
 * which the part walks turn into a sentence.
 */
class LeIn(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
    val left: Int get() = end - p
    private fun need(n: Int) { if (n < 0 || p + n > end) throw ShortRead("needed $n bytes at byte $p of $end") }
    fun skip(n: Int) { need(n); p += n }
    fun bytes(n: Int): ByteArray { need(n); val o = b.copyOfRange(p, p + n); p += n; return o }
    fun u8(): Int { need(1); return b[p++].toInt() and 0xFF }
    fun u16(): Int { need(2); val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8); p += 2; return v }
    fun i16(): Int = u16().toShort().toInt()
    fun i32(): Int {
        need(4)
        val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
        p += 4
        return v
    }
    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
    fun i64(): Long { val lo = u32(); val hi = i32().toLong(); return (hi shl 32) or lo }
    fun id(): VuId { val num = u32(); val creator = u32(); return VuId(num, creator) }
    fun text(n: Int): FixedText = FixedText(bytes(n))
    fun ints16(n: Int): IntArray = IntArray(n) { i16() }
    fun ints32(n: Int): IntArray = IntArray(n) { i32() }
    fun u8s(n: Int): IntArray = IntArray(n) { u8() }
}

/**
 * A little-endian writer. Values are written by their low bits, so a field read signed and written back unsigned (or
 * the other way round) keeps its bytes.
 */
class LeOut(size: Int = 256) {
    private val o = ByteArrayOutputStream(size)
    val size: Int get() = o.size()
    fun u8(v: Int) { o.write(v and 0xFF) }
    fun u16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
    fun i16(v: Int) = u16(v)
    fun i32(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF) }
    fun u32(v: Long) = i32(v.toInt())
    fun i64(v: Long) { i32(v.toInt()); i32((v ushr 32).toInt()) }
    fun id(v: VuId) { u32(v.num); u32(v.creator) }
    fun bytes(b: ByteArray) { o.write(b, 0, b.size) }
    fun text(t: FixedText) = bytes(t.bytes)
    fun ints16(a: IntArray) = a.forEach { i16(it) }
    fun ints32(a: IntArray) = a.forEach { i32(it) }
    fun u8s(a: IntArray) = a.forEach { u8(it) }
    fun toByteArray(): ByteArray = o.toByteArray()
}

/**
 * A fixed-size text field of a save (names, mottos: ANSI, NUL-padded). The bytes are kept exactly as read — BMS and
 * other tools leave old text after the terminating NUL — and only replaced when [text] is set: the new text, cut to
 * leave room for a NUL, then zeros.
 */
class FixedText(bytes: ByteArray) {
    var bytes: ByteArray = bytes
        private set
    val capacity: Int get() = bytes.size

    var text: String
        get() {
            var n = 0
            while (n < bytes.size && bytes[n].toInt() != 0) n++
            return String(bytes, 0, n, ANSI)
        }
        set(v) {
            val enc = v.toByteArray(ANSI)
            val out = ByteArray(bytes.size)
            System.arraycopy(enc, 0, out, 0, minOf(enc.size, maxOf(0, out.size - 1)))
            bytes = out
        }

    fun copy(): FixedText = FixedText(bytes.copyOf())
    override fun toString(): String = text

    companion object {
        fun of(size: Int, text: String = ""): FixedText = FixedText(ByteArray(size)).also { if (text.isNotEmpty()) it.text = text }
    }
}

/** A float kept as its bits, so an odd value (a NaN with a payload) is written back exactly as read. */
internal fun Float.bits(): Int = toRawBits()
internal fun fl(bits: Int): Float = Float.fromBits(bits)
