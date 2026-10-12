package com.bmscompanion.desktop.bridge

/**
 * A plain LZMA decoder (the "LZMA alone" stream, no container), for Falcon BMS's `.bml` models ([BmlModel]).
 *
 * Written after Igor Pavlov's reference decoder in the LZMA SDK (`LzmaSpec.cpp`, public domain): a range decoder over
 * adaptive bit probabilities, literals coded against the previous byte, matches against a sliding window, four repeat
 * distances and the twelve-state machine. The whole output is held in one array, which is also the window, so the
 * size has to be known up front — a `.bml` states it in its header. Nothing here throws to its caller: [decode] answers
 * null for a stream it cannot read.
 */
object Lzma {
    private const val PROB_INIT = 1 shl 10
    private const val STATES = 12
    private const val POS_BITS_MAX = 4
    private const val LEN_TO_POS_STATES = 4
    private const val ALIGN_BITS = 4
    private const val START_POS_MODEL = 4
    private const val END_POS_MODEL = 14
    private const val FULL_DISTANCES = 1 shl (END_POS_MODEL shr 1)
    private const val MATCH_MIN_LEN = 2

    /**
     * Decodes [size] bytes of the LZMA stream at [src] from [offset], whose five property bytes are [props]
     * (lc/lp/pb in the first, the dictionary size in the next four). Null when the stream is damaged or short.
     */
    fun decode(props: ByteArray, src: ByteArray, offset: Int, size: Int): ByteArray? = try {
        Decoder(props, src, offset, size).run()
    } catch (_: Throwable) {
        null
    }

    private class Range(val src: ByteArray, var p: Int) {
        var range = -1 // 0xFFFFFFFF
        var code = 0

        init {
            p++ // the first byte is always 0
            for (i in 0 until 4) code = (code shl 8) or next()
        }

        fun next(): Int = if (p < src.size) src[p++].toInt() and 0xFF else throw IllegalStateException("LZMA stream ended early")

        private fun normalize() {
            if (Integer.compareUnsigned(range, 1 shl 24) < 0) {
                range = range shl 8
                code = (code shl 8) or next()
            }
        }

        fun bit(probs: ShortArray, i: Int): Int {
            val prob = probs[i].toInt()
            val bound = (range ushr 11) * prob
            return if (Integer.compareUnsigned(code, bound) < 0) {
                range = bound
                probs[i] = (prob + ((2048 - prob) ushr 5)).toShort()
                normalize()
                0
            } else {
                range -= bound
                code -= bound
                probs[i] = (prob - (prob ushr 5)).toShort()
                normalize()
                1
            }
        }

        fun direct(n: Int): Int {
            var res = 0
            for (i in 0 until n) {
                range = range ushr 1
                code -= range
                val t = 0 - (code ushr 31)
                code += range and t
                normalize()
                res = (res shl 1) + (t + 1)
            }
            return res
        }

        fun tree(probs: ShortArray, base: Int, bits: Int): Int {
            var m = 1
            for (i in 0 until bits) m = (m shl 1) + bit(probs, base + m)
            return m - (1 shl bits)
        }

        fun reverse(probs: ShortArray, base: Int, bits: Int): Int {
            var m = 1
            var sym = 0
            for (i in 0 until bits) {
                val b = bit(probs, base + m)
                m = (m shl 1) + b
                sym = sym or (b shl i)
            }
            return sym
        }
    }

    private class Len {
        val choice = ShortArray(2) { PROB_INIT.toShort() }
        val low = ShortArray((1 shl POS_BITS_MAX) shl 3) { PROB_INIT.toShort() }
        val mid = ShortArray((1 shl POS_BITS_MAX) shl 3) { PROB_INIT.toShort() }
        val high = ShortArray(256) { PROB_INIT.toShort() }

        fun decode(rc: Range, posState: Int): Int {
            if (rc.bit(choice, 0) == 0) return rc.tree(low, posState shl 3, 3)
            if (rc.bit(choice, 1) == 0) return 8 + rc.tree(mid, posState shl 3, 3)
            return 16 + rc.tree(high, 0, 8)
        }
    }

    private class Decoder(props: ByteArray, src: ByteArray, offset: Int, val size: Int) {
        val lc: Int
        val lp: Int
        val pb: Int
        val dictSize: Long
        val out = ByteArray(size)
        var pos = 0
        val rc = Range(src, offset)
        val lit: ShortArray
        val isMatch = ShortArray(STATES shl POS_BITS_MAX) { PROB_INIT.toShort() }
        val isRep = ShortArray(STATES) { PROB_INIT.toShort() }
        val isRepG0 = ShortArray(STATES) { PROB_INIT.toShort() }
        val isRepG1 = ShortArray(STATES) { PROB_INIT.toShort() }
        val isRepG2 = ShortArray(STATES) { PROB_INIT.toShort() }
        val isRep0Long = ShortArray(STATES shl POS_BITS_MAX) { PROB_INIT.toShort() }
        val posSlot = ShortArray(LEN_TO_POS_STATES shl 6) { PROB_INIT.toShort() }
        val posDecoders = ShortArray(1 + FULL_DISTANCES - END_POS_MODEL) { PROB_INIT.toShort() }
        val align = ShortArray(1 shl ALIGN_BITS) { PROB_INIT.toShort() }
        val len = Len()
        val repLen = Len()

        init {
            var d = props[0].toInt() and 0xFF
            require(d < 9 * 5 * 5)
            lc = d % 9; d /= 9
            lp = d % 5
            pb = d / 5
            var ds = 0L
            for (i in 0 until 4) ds = ds or ((props[1 + i].toLong() and 0xFF) shl (8 * i))
            dictSize = maxOf(ds, 1L shl 12)
            lit = ShortArray(0x300 shl (lc + lp)) { PROB_INIT.toShort() }
        }

        fun byteAt(dist: Int): Int = out[pos - dist].toInt() and 0xFF

        fun literal(state: Int, rep0: Int) {
            val prev = if (pos > 0) byteAt(1) else 0
            val litState = ((pos and ((1 shl lp) - 1)) shl lc) + (prev ushr (8 - lc))
            val base = 0x300 * litState
            var sym = 1
            if (state >= 7) {
                var matchByte = byteAt(rep0 + 1)
                do {
                    val matchBit = (matchByte ushr 7) and 1
                    matchByte = matchByte shl 1
                    val b = rc.bit(lit, base + ((1 + matchBit) shl 8) + sym)
                    sym = (sym shl 1) or b
                    if (matchBit != b) break
                } while (sym < 0x100)
            }
            while (sym < 0x100) sym = (sym shl 1) or rc.bit(lit, base + sym)
            out[pos++] = (sym - 0x100).toByte()
        }

        fun distance(length: Int): Int {
            val lenState = minOf(length, LEN_TO_POS_STATES - 1)
            val slot = rc.tree(posSlot, lenState shl 6, 6)
            if (slot < START_POS_MODEL) return slot
            val direct = (slot ushr 1) - 1
            var dist = (2 or (slot and 1)) shl direct
            if (slot < END_POS_MODEL) {
                dist += rc.reverse(posDecoders, dist - slot, direct)
            } else {
                dist += rc.direct(direct - ALIGN_BITS) shl ALIGN_BITS
                dist += rc.reverse(align, 0, ALIGN_BITS)
            }
            return dist
        }

        fun run(): ByteArray? {
            var state = 0
            var rep0 = 0
            var rep1 = 0
            var rep2 = 0
            var rep3 = 0
            while (pos < size) {
                val posState = pos and ((1 shl pb) - 1)
                if (rc.bit(isMatch, (state shl POS_BITS_MAX) + posState) == 0) {
                    literal(state, rep0)
                    state = if (state < 4) 0 else if (state < 10) state - 3 else state - 6
                    continue
                }
                var length: Int
                if (rc.bit(isRep, state) != 0) {
                    if (pos == 0) return null
                    if (rc.bit(isRepG0, state) == 0) {
                        if (rc.bit(isRep0Long, (state shl POS_BITS_MAX) + posState) == 0) {
                            state = if (state < 7) 9 else 11
                            out[pos] = out[pos - rep0 - 1]
                            pos++
                            continue
                        }
                    } else {
                        val dist: Int
                        if (rc.bit(isRepG1, state) == 0) {
                            dist = rep1
                        } else {
                            if (rc.bit(isRepG2, state) == 0) {
                                dist = rep2
                            } else {
                                dist = rep3
                                rep3 = rep2
                            }
                            rep2 = rep1
                        }
                        rep1 = rep0
                        rep0 = dist
                    }
                    length = repLen.decode(rc, posState)
                    state = if (state < 7) 8 else 11
                } else {
                    rep3 = rep2
                    rep2 = rep1
                    rep1 = rep0
                    length = len.decode(rc, posState)
                    state = if (state < 7) 7 else 10
                    rep0 = distance(length)
                    if (rep0 == -1) break // the end marker
                    if (rep0 < 0 || rep0 >= pos || rep0.toLong() >= dictSize) return null
                }
                length += MATCH_MIN_LEN
                val from = pos - rep0 - 1
                if (from < 0) return null
                val n = minOf(length, size - pos)
                for (i in 0 until n) out[pos + i] = out[from + i]
                pos += n
            }
            return if (pos == size) out else null
        }
    }
}
