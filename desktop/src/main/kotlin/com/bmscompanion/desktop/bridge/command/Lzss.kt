package com.bmscompanion.desktop.bridge.command

import java.io.ByteArrayOutputStream

/**
 * Falcon's packing of the campaign parts (`.cmp`, `.uni`, `.obd`, `.obj`), both ways.
 *
 * The stream: a flag byte carries eight decisions, least significant bit first — 1 = the next byte is itself, 0 = the
 * next two bytes copy a run out of a 4,096-byte ring. Of those two bytes the first's high nibble is the run length
 * less 2 (2-17 bytes) and its low nibble, with the second byte, the ring position (12 bits). The ring starts zeroed
 * and is written from position 1 as bytes come out; a run may reach into the bytes it is producing.
 *
 * [decode] is the reader the app has always used ([com.bmscompanion.desktop.bridge.MissionArchive.unpack] does the
 * same); [encode] is a greedy packer whose output that reader (and Falcon BMS, which reads any valid stream: its own
 * saves and the shipped starts are packed differently and both load) turns back into the same bytes.
 */
object Lzss {
    private const val RING = 4096
    private const val MASK = RING - 1
    private const val MIN_RUN = 2
    private const val MAX_RUN = 17
    /** The farthest back a run may start: the ring's size less the slot being written. */
    private const val MAX_BACK = RING - 1
    /** How many earlier places with the same two bytes are tried for each position (speed against size). */
    private const val TRIES = 96

    /** What [decodeCounting] produced: the bytes and how many packed bytes it used. */
    class Decoded(val bytes: ByteArray, val used: Int)

    /** [want] bytes unpacked from [src] at [from], or null when the stream runs out first. */
    fun decode(src: ByteArray, from: Int, want: Int): ByteArray? = decodeCounting(src, from, want)?.bytes

    fun decodeCounting(src: ByteArray, from: Int, want: Int): Decoded? {
        if (want < 0) return null
        if (want == 0) return Decoded(ByteArray(0), 0)
        if (from >= src.size) return null
        val out = ByteArray(want)
        val ring = ByteArray(RING)
        var read = from
        var head = 1
        var written = 0
        var flags = 0
        var bit = 8
        while (written < want) {
            if (bit == 8) {
                if (read >= src.size) return null
                flags = src[read++].toInt() and 0xFF
                bit = 0
            }
            val literal = (flags shr bit) and 1 == 1
            bit++
            if (literal) {
                if (read >= src.size) return null
                val c = src[read++]
                out[written++] = c
                ring[head] = c
                head = (head + 1) and MASK
            } else {
                if (read + 1 >= src.size) return null
                val a = src[read].toInt() and 0xFF
                val b = src[read + 1].toInt() and 0xFF
                read += 2
                val at = b or ((a and 0x0F) shl 8)
                val n = minOf((a shr 4) + MIN_RUN, want - written)
                for (i in 0 until n) {
                    val c = ring[(at + i) and MASK]
                    out[written++] = c
                    ring[head] = c
                    head = (head + 1) and MASK
                }
            }
        }
        return Decoded(out, read - from)
    }

    /**
     * [data] packed: at each position the longest run (2-17 bytes) found among recent earlier places with the same
     * first two bytes, else the byte itself. Byte `i` of the input sits at ring position `(i + 1) & 4095`, which is
     * what the run's position names.
     */
    fun encode(data: ByteArray): ByteArray {
        val n = data.size
        val out = ByteArrayOutputStream(n / 2 + 16)
        if (n == 0) return out.toByteArray()
        val head = IntArray(65536) { -1 }
        val prev = IntArray(n)
        var flagAt: Int
        val buf = ByteArray(1 + 16) // one flag byte and up to eight two-byte runs
        var used: Int
        var bit: Int
        var flags: Int
        fun key(i: Int) = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
        fun insert(i: Int) {
            if (i + 1 >= n) return
            val k = key(i)
            prev[i] = head[k]
            head[k] = i
        }
        var i = 0
        flagAt = 0; used = 1; bit = 0; flags = 0
        while (i < n) {
            var bestLen = 0
            var bestFrom = -1
            if (i + 1 < n) {
                val limit = minOf(MAX_RUN, n - i)
                var cand = head[key(i)]
                var tries = TRIES
                while (cand >= 0 && i - cand <= MAX_BACK && tries-- > 0) {
                    var len = 0
                    while (len < limit && data[cand + len] == data[i + len]) len++
                    if (len > bestLen) {
                        bestLen = len; bestFrom = cand
                        if (len == limit) break
                    }
                    cand = prev[cand]
                }
            }
            if (bestLen >= MIN_RUN) {
                val pos = (bestFrom + 1) and MASK
                buf[used++] = (((bestLen - MIN_RUN) shl 4) or ((pos shr 8) and 0x0F)).toByte()
                buf[used++] = (pos and 0xFF).toByte()
                for (k in 0 until bestLen) insert(i + k)
                i += bestLen
            } else {
                flags = flags or (1 shl bit)
                buf[used++] = data[i]
                insert(i)
                i++
            }
            bit++
            if (bit == 8) {
                buf[flagAt] = flags.toByte()
                out.write(buf, 0, used)
                used = 1; bit = 0; flags = 0
            }
        }
        if (bit > 0) {
            buf[flagAt] = flags.toByte()
            out.write(buf, 0, used)
        }
        return out.toByteArray()
    }
}
