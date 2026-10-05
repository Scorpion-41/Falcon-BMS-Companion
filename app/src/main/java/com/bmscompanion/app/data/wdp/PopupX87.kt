package com.bmscompanion.app.data.wdp

import kotlin.math.abs
import kotlin.math.round

/**
 * `Math.Sin`, `Math.Cos`, `Math.Tan` and `Math.Atan` as Weapon Delivery Planner meets them: a 32-bit .NET Framework
 * program, whose JIT turns each into one x87 instruction (`fsin`, `fcos`, `fptan`, `fpatan`). The answer is left on
 * the x87 stack with a **64-bit** mantissa, and the arithmetic that follows it in the same expression uses that
 * value, rounding only its own result to a double (the runtime runs the FPU at 53-bit precision). So
 * `Math.Sin(b) * (double)d` is the extended sine times d, rounded once — not the double sine times d. `Math.Sqrt`
 * (`fsqrt`, rounded at 53 bits) and the library calls (`Pow`, `Acos`) come back as plain doubles.
 *
 * Measured on .NET Framework 4.8, x86 (a probe multiplying each function's result by a random integer, both ways):
 * one product in four differs in its last bit between the two readings for sin, cos, tan and atan, none for sqrt,
 * pow and acos. It shows on the page where a product lands on a tie: `NewPos_E` at a bearing of 3π/2 (as a float)
 * and 4,499,437 ft gives -3428948.0 in WDP, and -3428947.8 when the sine is rounded to a double first.
 *
 * The functions return the value as a pair of doubles, [Ext.hi] + [Ext.lo], equal to the true value rounded to 64
 * significant bits; the few operations WDP applies to such a value round the exact result once.
 */
internal object PopupX87 {

    /** A value on the x87 stack: [hi] + [lo], 64 significant bits. */
    class Ext(val hi: Double, val lo: Double) {
        /** Stored to a double (`fstp m64`). */
        fun toDouble(): Double = hi + lo

        /** Cut to a float (`fstp m32`, one rounding from 64 bits). */
        fun toFloat(): Float {
            val f = hi.toFloat()
            val back = f.toDouble()
            if (back == hi || lo == 0.0) return f
            // hi may sit exactly half-way between two floats: lo then decides, not the tie rule
            val other = if (back < hi) nextFloatUp(f) else nextFloatDown(f)
            val mid = (back + other.toDouble()) / 2.0
            if (mid != hi) return f
            return if ((lo > 0.0) == (other.toDouble() > back)) other else f
        }
    }

    fun sin(x: Double): Ext = trig(x, 0)
    fun cos(x: Double): Ext = trig(x, 1)
    fun tan(x: Double): Ext = trig(x, 2)

    fun atan(x: Double): Ext {
        if (x.isNaN()) return Ext(Double.NaN, 0.0)
        if (x.isInfinite()) return round64(if (x > 0) PIO2 else PIO2.neg())
        val y0 = kotlin.math.atan(x)
        // one Newton step on tan(y) = x from the double answer: y + cos(y)·(x·cos(y) − sin(y)) doubles the digits
        val (s, c) = sinCos(DD(y0, 0.0))
        val corr = c.mul(c.mulD(x).sub(s))
        return round64(DD(y0, 0.0).add(corr))
    }

    /** `ext * b`, rounded once to a double (`fmul` at 53-bit precision). */
    fun mul(a: Ext, b: Double): Double {
        if (!a.hi.isFinite() || !b.isFinite()) return a.hi * b
        val p = twoProd(a.hi, b)
        val r = p.hi + (p.lo + a.lo * b)
        return if (r == 0.0) a.hi * b else r   // a zero keeps the sign of the product
    }

    /** `a / ext`, rounded once to a double (`fdiv` at 53-bit precision). */
    fun div(a: Double, b: Ext): Double {
        if (!a.isFinite() || !b.hi.isFinite() || b.hi == 0.0) return a / b.hi
        val q0 = a / b.hi
        if (!q0.isFinite() || q0 == 0.0) return q0
        // the residual a − q0·(hi + lo), exactly enough, then one correction
        val p = twoProd(q0, b.hi)
        val r = ((a - p.hi) - p.lo) - q0 * b.lo
        return q0 + r / (b.hi + b.lo)
    }

    /** `ext - b`, rounded once to a double. */
    fun sub(a: Ext, b: Double): Double {
        val s = twoSum(a.hi, -b)
        val r = s.hi + (s.lo + a.lo)
        return if (r == 0.0) a.hi - b else r
    }

    // ------------------------------------------------------------------------------------------ the arithmetic

    /** A double-double: about 106 significant bits. */
    private class DD(val hi: Double, val lo: Double) {
        fun neg() = DD(-hi, -lo)
        fun add(o: DD): DD {
            val s = twoSum(hi, o.hi)
            val t = twoSum(lo, o.lo)
            var c = s.lo + t.hi
            val v = quickTwoSum(s.hi, c)
            c = t.lo + v.lo
            return quickTwoSum(v.hi, c)
        }
        fun sub(o: DD) = add(o.neg())
        fun mul(o: DD): DD {
            val p = twoProd(hi, o.hi)
            val lo2 = p.lo + (hi * o.lo + lo * o.hi)
            return quickTwoSum(p.hi, lo2)
        }
        fun mulD(d: Double): DD {
            val p = twoProd(hi, d)
            return quickTwoSum(p.hi, p.lo + lo * d)
        }
        fun div(o: DD): DD {
            val q1 = hi / o.hi
            var r = sub(o.mulD(q1))
            val q2 = r.hi / o.hi
            r = r.sub(o.mulD(q2))
            val q3 = r.hi / o.hi
            return quickTwoSum(q1, q2).add(DD(q3, 0.0))
        }
        fun divD(d: Double): DD = div(DD(d, 0.0))
    }

    private fun quickTwoSum(a: Double, b: Double): DD {
        val s = a + b
        return DD(s, b - (s - a))
    }

    private fun twoSum(a: Double, b: Double): DD {
        val s = a + b
        val bb = s - a
        return DD(s, (a - (s - bb)) + (b - bb))
    }

    private const val SPLIT = 134217729.0   // 2^27 + 1

    private fun split(a: Double): DD {
        if (abs(a) > 6.69692879491417e+299) {   // would overflow: split a scaled copy
            val s = split(a * 3.7252902984e-09)
            return DD(s.hi * 268435456.0, s.lo * 268435456.0)
        }
        val t = SPLIT * a
        val hi = t - (t - a)
        return DD(hi, a - hi)
    }

    /** a × b exactly, as hi + lo (Dekker, no fused multiply-add needed). */
    private fun twoProd(a: Double, b: Double): DD {
        val p = a * b
        if (!p.isFinite()) return DD(p, 0.0)
        val sa = split(a)
        val sb = split(b)
        val err = ((sa.hi * sb.hi - p) + sa.hi * sb.lo + sa.lo * sb.hi) + sa.lo * sb.lo
        return DD(p, err)
    }

    /**
     * π/2 as the x87 knows it: fsin, fcos and fptan reduce their argument with a **66-bit** π
     * (0xC90FDAA22168C234C × 2^-66), not the true one — which is why their answers near a multiple of π differ from
     * the true sine in the twelfth digit (`Math.Sin` of the float nearest π is -8.74227800037288E-08 in WDP,
     * -8.742278000372475E-08 in truth). The reduction here uses the same constant, split exactly into two doubles.
     */
    private const val P1 = 1.5707963267948966
    private const val P2 = 6.1230317691118863e-17
    /** the true π/2, for `Math.Atan` of an infinity (fpatan does no reduction) */
    private val PIO2 = DD(1.5707963267948966, 6.123233995736766e-17)

    /** which: 0 sin, 1 cos, 2 tan. */
    private fun trig(x: Double, which: Int): Ext {
        if (!x.isFinite()) return Ext(Double.NaN, 0.0)
        if (abs(x) > 1.0e9) {
            // far outside anything the page asks; the library's answer
            return Ext(when (which) { 0 -> kotlin.math.sin(x); 1 -> kotlin.math.cos(x); else -> kotlin.math.tan(x) }, 0.0)
        }
        val k = round(x / P1)
        // r = x − k·π/2, carried in double-double
        val r = DD(x, 0.0).sub(twoProdDD(k, P1)).sub(twoProdDD(k, P2))
        val (s, c) = sinCos(r)
        val n = ((k % 4.0) + 4.0) % 4.0
        val q = n.toInt()
        val v = when (which) {
            0 -> when (q) { 0 -> s; 1 -> c; 2 -> s.neg(); else -> c.neg() }
            1 -> when (q) { 0 -> c; 1 -> s.neg(); 2 -> c.neg(); else -> s }
            else -> if (q % 2 == 0) s.div(c) else c.div(s).neg()
        }
        return round64(v)
    }

    private fun twoProdDD(a: Double, b: Double): DD = twoProd(a, b)

    /** sin and cos of a small argument (|r| ≤ π/4), by their series in double-double. */
    private fun sinCos(r: DD): Pair<DD, DD> {
        val r2 = r.mul(r)
        var term = r
        var sin = r
        var i = 1
        while (i < 40) {
            term = term.mul(r2).divD(-((2 * i) * (2 * i + 1)).toDouble())
            sin = sin.add(term)
            if (abs(term.hi) < 1e-40 * abs(sin.hi) || term.hi == 0.0) break
            i++
        }
        var cterm = DD(1.0, 0.0)
        var cos = DD(1.0, 0.0)
        i = 1
        while (i < 40) {
            cterm = cterm.mul(r2).divD(-((2 * i - 1) * (2 * i)).toDouble())
            cos = cos.add(cterm)
            if (abs(cterm.hi) < 1e-40 || cterm.hi == 0.0) break
            i++
        }
        return sin to cos
    }

    /** The double-double rounded to 64 significant bits, as the x87 register holds it. */
    private fun round64(v: DD): Ext {
        val hi = v.hi
        val lo = v.lo
        if (!hi.isFinite() || hi == 0.0 || lo == 0.0) return Ext(hi, lo)
        val bits = hi.toRawBits()
        val expBits = ((bits ushr 52) and 0x7ffL).toInt()
        if (expBits == 0) return Ext(hi, lo)   // subnormal: nothing the page meets
        var e = expBits - 1023
        val powerOfTwo = (bits and 0xfffffffffffffL) == 0L
        if (powerOfTwo && (lo < 0.0) != (hi < 0.0)) e -= 1
        val qe = e - 63
        if (qe < -1000) return Ext(hi, lo)
        val q = Double.fromBits((qe + 1023).toLong() shl 52)
        val l = round(lo / q) * q     // kotlin.math.round: half to even
        return Ext(hi, l)
    }

    private fun nextFloatUp(f: Float): Float = if (f >= 0f) Float.fromBits(f.toRawBits() + 1) else Float.fromBits(f.toRawBits() - 1)
    private fun nextFloatDown(f: Float): Float = if (f > 0f) Float.fromBits(f.toRawBits() - 1) else if (f == 0f) -Float.fromBits(1) else Float.fromBits(f.toRawBits() + 1)
}
