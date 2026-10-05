package com.bmscompanion.app.data.wdp

import kotlin.math.abs
import kotlin.math.floor

/**
 * The .NET Framework and Visual Basic runtime behaviour the DataCard page's text depends on.
 *
 * Weapon Delivery Planner is a VB.NET program: its labels are written by `Conversions.ToString`, `Strings.Format`
 * and `Math.Round`, and the card reads back what a pilot typed through `Versioned.IsNumeric` and
 * `Conversions.ToInteger`. Each has its own rounding, and the card's text is only right if all of them are:
 *
 * - **A float keeps 7 significant digits, a double 15**, rounded half up on the exact binary value, before any
 *   format sees it. `Conversions.ToString` ("G") prints those digits; a custom format (`"#.0"`, `"#0.000"`,
 *   `".#0"`) then rounds them **half up again** at its own last place. So a float `12.25f` is `"12.3"` through
 *   `"#.0"`, where rounding the binary value once would give `"12.2"` for `12.2499…`.
 * - **`Math.Round` is to-even**, and `Math.Round(x, n)` is `Round(x × 10^n) / 10^n` in doubles.
 * - **A number that rounds to zero prints without its sign**, and `"#.0"` prints no digit before the point:
 *   a distance of 0.04 nm is `".0"`.
 *
 * The digits are worked out from the double's exact binary value, so none of this depends on how the JVM, Android
 * or the browser print numbers. Kept private to the DataCard port so its checks stand on their own.
 */
internal object DataCardNet {

    // ---------------------------------------------------------------- rounding

    /** .NET `Math.Round(double)`: to the even integer on a tie. */
    fun bankers(v: Double): Double {
        if (v.isNaN() || v.isInfinite()) return v
        val f = floor(v)
        val d = v - f
        return when {
            d > 0.5 -> f + 1.0
            d < 0.5 -> f
            (f % 2.0) == 0.0 -> f
            else -> f + 1.0
        }
    }

    private val POW10 = doubleArrayOf(1.0, 10.0, 100.0, 1000.0, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15)

    /** .NET Framework `Math.Round(double, digits)`: scale, round to even, scale back — all in doubles. */
    fun round(v: Double, digits: Int): Double {
        if (abs(v) >= 1e16) return v
        val p = POW10[digits]
        return bankers(v * p) / p
    }

    /** A C# `checked((int)Math.Round(x))`: the rounded value, or an overflow the caller's try/catch sees. */
    fun roundInt(v: Double): Int {
        val r = bankers(v)
        if (r.isNaN() || r > Int.MAX_VALUE.toDouble() || r < Int.MIN_VALUE.toDouble()) throw ArithmeticException("int overflow")
        return r.toInt()
    }

    /** A C# `checked((short)x)`. */
    fun checkedShort(v: Int): Short {
        if (v > Short.MAX_VALUE || v < Short.MIN_VALUE) throw ArithmeticException("short overflow")
        return v.toShort()
    }

    // ---------------------------------------------------------------- decimals

    private val DBL_POW10 = DoubleArray(29) { i -> var d = 1.0; repeat(i) { d *= 10.0 }; d }

    /**
     * `new decimal(float)` (`VarDecFromR4`): the float rounded to seven significant digits — scaled by a power of
     * ten estimated from its binary exponent, truncated to a whole number, then rounded half to even on what the
     * truncation left — as (mantissa, scale): the value is `mantissa / 10^scale`. NaN and infinities throw
     * (OverflowException), as the constructor does.
     */
    fun decimalOf(v: Float): Pair<Long, Int> {
        if (v.isNaN() || v.isInfinite()) throw ArithmeticException("Value was either too large or too small for a Decimal.")
        val bits = v.toRawBits()
        val iExp = ((bits ushr 23) and 0xFF) - 126
        if (iExp < -94) return 0L to 0
        if (iExp > 96) throw ArithmeticException("Value was either too large or too small for a Decimal.")
        var dbl = abs(v.toDouble())
        var power = 6 - ((iExp * 19728) shr 16)
        if (power >= 0) {
            if (power > 28) power = 28
            dbl *= DBL_POW10[power]
        } else {
            if (power != -1 || dbl >= 1e7) dbl /= DBL_POW10[-power] else power = 0
        }
        if (dbl < 1e6 && power < 28) { dbl *= 10.0; power++ }
        var mant = dbl.toLong()
        val rest = dbl - mant.toDouble()
        if (rest > 0.5 || (rest == 0.5 && (mant and 1L) != 0L)) mant++
        if (mant == 0L) return 0L to 0
        var m = if (v < 0f) -mant else mant
        var p = power
        while (p < 0) { m *= 10; p++ }
        return m to p
    }

    /** A `NumericUpDown` with one decimal place showing `new decimal(v)`: `decimal.ToString("F1")`, half away from zero. */
    fun decimalF1(v: Float): String {
        val (m0, p) = decimalOf(v)
        val neg = m0 < 0
        var m = abs(m0)
        // to tenths: m / 10^(p-1), half away from zero on the decimal digits
        var tenths: Long
        if (p <= 1) {
            tenths = m
            if (p == 0) tenths *= 10
        } else {
            var div = 1L
            repeat(p - 1) { div *= 10 }
            tenths = m / div
            if ((m % div) * 2 >= div) tenths++
        }
        val s = (tenths / 10).toString() + "." + (tenths % 10).toString()
        return if (neg && tenths != 0L) "-$s" else s
    }

    // ---------------------------------------------------------------- VB strings

    /**
     * VB `Strings.LCase` under en-US on the .NET Framework (`TextInfo.ToLower`, NLS): each UTF-16 unit mapped on
     * its own by its simple lower case. So `İ` is `i` (Kotlin's `lowercase()` makes it `i̇`, two characters), and
     * `Σ` is always `σ` (no final-sigma rule, which `lowercase()` applies).
     */
    fun lcase(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(c.lowercaseChar())
        return sb.toString()
    }

    // ---------------------------------------------------------------- VB conversions

    /** `Conversions.ToString(Single)`: "G", 7 significant digits. */
    fun str(v: Float): String = general(v.toDouble(), 7)

    /** `Conversions.ToString(Double)`: "G", 15 significant digits. */
    fun str(v: Double): String = general(v, 15)

    /** `Strings.Format(Single, pattern)`. */
    fun fmt(v: Float, pattern: String): String = custom(v.toDouble(), 7, pattern)

    /** `Strings.Format(Double, pattern)`. */
    fun fmt(v: Double, pattern: String): String = custom(v, 15, pattern)

    /** `Strings.Format(Integer, pattern)`: an integer is exact, so only the pattern rounds. */
    fun fmt(v: Int, pattern: String): String = custom(v.toDouble(), 15, pattern)

    /**
     * VB `Versioned.IsNumeric(String)`, as the .NET Framework runs it for WDP (en-US). Two readers, in this order:
     *
     * 1. `IsHexOrOctValue`: after spaces (U+0020, U+3000 only), `&H…` or `&O…` is `Convert.ToInt64(rest, 16|8)` —
     *    a leading `+`, for hex a `0x`, up to 64 bits (`&HFFFFFFFFFFFFFFFF` is -1), nothing after. A string that
     *    starts so and does not convert is **not** a number, and not tried as a decimal; but a `-` after the
     *    prefix (`&H-5`) throws `ArgumentException` and more than 64 bits throws `OverflowException`, out of
     *    `IsNumeric` itself (only a `FormatException` is caught).
     * 2. `Double.TryParse(s, NumberStyles.Any, en-US)`: white space (U+0009–U+000D, U+0020 — not a no-break
     *    space) around, a sign in front (no space after it unless a `$` came first) or behind, parentheses for a
     *    negative, one `$` before or after, `,` group separators once a digit has been seen, one `.`, an exponent,
     *    trailing NULs; or, trimmed, one of the culture's own symbols `NaN`, `∞`, `-∞` (Windows 10's en-US has
     *    `∞`, so `Infinity` is not a number). Out of a double's range is not a number.
     *
     * Checked against the program's own runtime (`Microsoft.VisualBasic` 10.0 on .NET Framework 4.8) over 6,000
     * strings — the fixed edge cases plus random runs of `&H`, `$`, `(`, signs, separators, `e`, NUL, NBSP, `∞`.
     */
    fun isNumeric(s: String?): Boolean {
        if (s == null) return false
        try {
            if (hexOct(s) != null) return true
        } catch (e: NumberFormatException) {
            return false
        }
        return tryParseDouble(s) != null
    }

    /** VB `Conversions.ToDouble(String)`: `&H`/`&O` as a 64-bit integer, else `Double.Parse`. Throws where VB throws. */
    fun toDouble(s: String?): Double {
        if (s == null) return 0.0
        hexOct(s)?.let { return it.toDouble() }
        return parseDouble(s)
    }

    /** VB `Conversions.ToSingle(String)`: a hex/octal value narrowed; a decimal past a float's range throws. */
    fun toSingle(s: String?): Float {
        if (s == null) return 0f
        hexOct(s)?.let { return it.toFloat() }
        val d = parseDouble(s)
        if ((d < -Float.MAX_VALUE.toDouble() || d > Float.MAX_VALUE.toDouble()) && !d.isInfinite()) throw ArithmeticException("Overflow")
        return d.toFloat()
    }

    /** VB `Conversions.ToInteger(String)`: `CInt` of the hex/octal value, or of the double rounded to even. */
    fun toInteger(s: String?): Int {
        if (s == null) return 0
        hexOct(s)?.let { if (it > Int.MAX_VALUE || it < Int.MIN_VALUE) throw ArithmeticException("Overflow"); return it.toInt() }
        return roundInt(parseDouble(s))
    }

    /** VB `Conversions.ToShort(String)`. */
    fun toShort(s: String?): Short {
        if (s == null) return 0
        hexOct(s)?.let { if (it > Short.MAX_VALUE || it < Short.MIN_VALUE) throw ArithmeticException("Overflow"); return it.toShort() }
        return checkedShort(roundInt(parseDouble(s)))
    }

    /** `Double.Parse(s, NumberStyles.Any, en-US)` as `Conversions.ParseDouble` calls it: throws on anything else. */
    private fun parseDouble(s: String): Double {
        val n = parseNumber(s)
        if (n != null) {
            if (n.isInfinite()) throw ArithmeticException("Overflow")
            return n
        }
        return special(s) ?: throw IllegalArgumentException("InvalidCast")
    }

    /** `Double.TryParse(s, NumberStyles.Any, en-US)`: null where it answers false. */
    private fun tryParseDouble(s: String): Double? {
        val n = parseNumber(s)
        if (n != null) return if (n.isInfinite()) null else n
        return special(s)
    }

    /** The culture's symbols, compared with the string `Trim`med of .NET's white space. */
    private fun special(s: String): Double? = when (s.trim { netWhite(it) }) {
        "∞" -> Double.POSITIVE_INFINITY
        "-∞" -> Double.NEGATIVE_INFINITY
        "NaN" -> Double.NaN
        else -> null
    }

    /** .NET `Char.IsWhiteSpace`, which `String.Trim` uses. */
    private fun netWhite(c: Char): Boolean = c == ' ' || c in '\u0009'..'\u000d' || c == '\u0085' || c == ' ' || c == ' ' ||
        c in ' '..' ' || c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　'

    /**
     * VB `IsHexOrOctValue`: null when the string is not `&H`/`&O`; the value when it is; otherwise it throws —
     * [NumberFormatException] for what `Convert.ToInt64` calls badly formed (the only one `IsNumeric` catches),
     * [IllegalArgumentException] for a minus sign, [ArithmeticException] past 64 bits.
     */
    private fun hexOct(s: String): Long? {
        val n = s.length
        var i = 0
        while (i < n) {
            val ch = s[i]
            if (ch == '&' && i + 2 < n) {
                val k = s[i + 1].lowercaseChar()
                val rest = s.substring(i + 2)
                return when (k) {
                    'h' -> toInt64(rest, 16)
                    'o' -> toInt64(rest, 8)
                    else -> throw NumberFormatException()
                }
            }
            if (ch != ' ' && ch != '　') return null
            i++
        }
        return null
    }

    /** `Convert.ToInt64(s, radix)` (`ParseNumbers.StringToLong`, `IsTight`). */
    private fun toInt64(s: String, radix: Int): Long {
        val n = s.length
        var i = 0
        if (n == 0) throw NumberFormatException()
        if (s[i] == '-') throw IllegalArgumentException("Arg_CannotHaveNegativeValue")
        if (s[i] == '+') i++
        if (radix == 16 && i + 1 < n && s[i] == '0' && (s[i + 1] == 'x' || s[i + 1] == 'X')) i += 2
        val start = i
        var result = 0UL
        val maxVal = ULong.MAX_VALUE / radix.toULong()
        while (i < n) {
            val c = s[i]
            val v = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'Z' -> c - 'A' + 10
                in 'a'..'z' -> c - 'a' + 10
                else -> 99
            }
            if (v >= radix) break
            if (result > maxVal) throw ArithmeticException("Overflow_UInt64")
            val temp = result * radix.toULong() + v.toULong()
            if (temp < result) throw ArithmeticException("Overflow_UInt64")
            result = temp
            i++
        }
        if (i == start) throw NumberFormatException()
        if (i < n) throw NumberFormatException()
        return result.toLong()
    }

    /**
     * `Number.ParseNumber` + `NumberBufferToDouble` with `NumberStyles.Any` and en-US's symbols: the double, or
     * null where the text is not a number. An out-of-range number comes back infinite (TryParse: false, Parse:
     * overflow). The end of the string reads as a NUL, as the pointer walk does, and only NULs may follow.
     */
    private fun parseNumber(s: String): Double? {
        val sign1 = 1; val parens = 2; val digitsSeen = 4; val nonZero = 8; val decimal = 16; val currency = 32
        val n = s.length
        fun ch(i: Int): Char = if (i < n) s[i] else '\u0000'
        fun white(c: Char) = c == ' ' || c in '\u0009'..'\u000d'
        fun match(p: Int, what: String): Int? {
            var q = p
            for (w in what) {
                val c = ch(q)
                if (c != w && !(w == ' ' && c == ' ')) return null
                q++
            }
            return q
        }
        var state = 0
        var neg = false
        var currSym: String? = "$"
        var p = 0
        var c = ch(p)
        while (true) {
            // white space is eaten unless a sign not followed by a currency symbol came first ("- 7" is not a number)
            if (!white(c) || ((state and sign1) != 0 && (state and currency) == 0)) {
                var next: Int? = null
                if ((state and sign1) == 0 && (match(p, "+").also { next = it } != null || (match(p, "-").also { next = it } != null).also { if (it) neg = true })) {
                    state = state or sign1
                    p = next!! - 1
                } else if (c == '(' && (state and sign1) == 0) {
                    state = state or sign1 or parens
                    neg = true
                } else if (currSym != null && match(p, currSym!!).also { next = it } != null) {
                    state = state or currency
                    currSym = null
                    p = next!! - 1
                } else break
            }
            p++; c = ch(p)
        }
        val digits = StringBuilder()
        var digEnd = 0
        var scale = 0
        while (true) {
            if (c in '0'..'9') {
                state = state or digitsSeen
                if (c != '0' || (state and nonZero) != 0) {
                    if (digits.length < 50) {
                        digits.append(c)
                        if (c != '0') digEnd = digits.length
                    }
                    if ((state and decimal) == 0) scale++
                    state = state or nonZero
                } else if ((state and decimal) != 0) scale--
            } else if ((state and decimal) == 0 && match(p, ".") != null) {
                state = state or decimal
            } else if ((state and digitsSeen) != 0 && (state and decimal) == 0 && match(p, ",") != null) {
                // a group separator
            } else break
            p++; c = ch(p)
        }
        if ((state and digitsSeen) == 0) return null
        if (c == 'E' || c == 'e') {
            val temp = p
            p++; c = ch(p)
            var negExp = false
            if (match(p, "+") != null) { p++; c = ch(p) } else if (match(p, "-") != null) { p++; c = ch(p); negExp = true }
            if (c in '0'..'9') {
                var exp = 0
                do {
                    exp = exp * 10 + (c - '0')
                    p++; c = ch(p)
                    if (exp > 1000) {
                        exp = 9999
                        while (c in '0'..'9') { p++; c = ch(p) }
                    }
                } while (c in '0'..'9')
                if (negExp) exp = -exp
                scale += exp
            } else {
                p = temp; c = ch(p)
            }
        }
        while (true) {
            if (!white(c)) {
                var next: Int? = null
                if ((state and sign1) == 0 && (match(p, "+").also { next = it } != null || (match(p, "-").also { next = it } != null).also { if (it) neg = true })) {
                    state = state or sign1
                    p = next!! - 1
                } else if (c == ')' && (state and parens) != 0) {
                    state = state and parens.inv()
                } else if (currSym != null && match(p, currSym!!).also { next = it } != null) {
                    currSym = null
                    p = next!! - 1
                } else break
            }
            p++; c = ch(p)
        }
        if ((state and parens) != 0) return null
        if ((state and nonZero) == 0) {
            scale = 0
            if ((state and decimal) == 0) neg = false
        }
        for (i in p until n) if (s[i] != '\u0000') return null
        val kept = digits.substring(0, digEnd)
        if (kept.isEmpty()) return if (neg) -0.0 else 0.0
        // 0.d1d2… × 10^scale; beyond ±400 the answer is 0 or out of range whatever the digits are
        val v = when {
            scale > 400 -> Double.POSITIVE_INFINITY
            scale < -400 -> 0.0
            else -> "0.${kept}e$scale".toDouble()
        }
        return if (neg) -v else v
    }

    // ---------------------------------------------------------------- the digits

    /** Significant digits (no leading or trailing zeros) and where the point goes: 0.d1d2… × 10^point. */
    private class Digits(val d: String, val point: Int)

    /** The exact decimal expansion of a finite, non-zero double's magnitude. */
    private fun exact(v: Double): Digits {
        val bits = v.toRawBits()
        val expBits = ((bits ushr 52) and 0x7ffL).toInt()
        val frac = bits and 0xfffffffffffffL
        val m: Long
        val e: Int
        if (expBits == 0) { m = frac; e = -1074 } else { m = frac or (1L shl 52); e = expBits - 1075 }
        // v = m × 2^e; with e < 0 that is m × 5^-e / 10^-e, so the digits of m × 5^-e are the digits of v
        val big = Big(m)
        val shift: Int
        if (e >= 0) { repeat(e) { big.times(2) }; shift = 0 } else { var k = -e; while (k > 0) { val s = minOf(k, 13); big.times(POW5[s]); k -= s }; shift = -e }
        val s = big.toString()
        val point = s.length - shift
        return Digits(s.trimEnd('0'), point)
    }

    private val POW5 = LongArray(14).also { it[0] = 1; for (i in 1 until 14) it[i] = it[i - 1] * 5 }

    /** A non-negative integer in base 10^9, just big enough for m × 5^k. */
    private class Big(v: Long) {
        private var w = IntArray(4)
        private var n = 0
        init { var x = v; while (x > 0) { push((x % 1_000_000_000L).toInt()); x /= 1_000_000_000L } }
        private fun push(x: Int) { if (n == w.size) w = w.copyOf(n * 2); w[n++] = x }
        fun times(f: Long) {
            var carry = 0L
            for (i in 0 until n) { val p = w[i] * f + carry; w[i] = (p % 1_000_000_000L).toInt(); carry = p / 1_000_000_000L }
            while (carry > 0) { push((carry % 1_000_000_000L).toInt()); carry /= 1_000_000_000L }
        }
        override fun toString(): String {
            if (n == 0) return "0"
            val sb = StringBuilder(w[n - 1].toString())
            for (i in n - 2 downTo 0) { val s = w[i].toString(); repeat(9 - s.length) { sb.append('0') }; sb.append(s) }
            return sb.toString()
        }
    }

    /** Round a digit string half up at [keep] digits (keep ≥ 0), carrying into a new leading digit if needed. */
    private fun roundAt(d: String, point: Int, keep: Int): Digits {
        if (keep < 0) return Digits("", point)
        if (d.length <= keep) return Digits(d, point)
        val head = d.substring(0, keep).toCharArray()
        var p = point
        if (d[keep] >= '5') {
            var i = keep - 1
            while (i >= 0 && head[i] == '9') { head[i] = '0'; i-- }
            if (i >= 0) head[i] = head[i] + 1
            else return Digits("1", p + 1)
        }
        return Digits(head.concatToString().trimEnd('0'), p)
    }

    /** The number as .NET holds it before formatting: [precision] significant digits, half up on the exact value. */
    private fun number(v: Double, precision: Int): Digits {
        if (v == 0.0) return Digits("", 0)
        val x = exact(v)
        return roundAt(x.d, x.point, precision)
    }

    /** `"G"` at the type's precision: the digits, fixed while the exponent fits, otherwise `d.ddddE+xx`. */
    private fun general(v: Double, precision: Int): String {
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "∞" else "-∞"   // Windows 10's en-US symbols
        val n = number(abs(v), precision)
        if (n.d.isEmpty()) return "0"
        val sign = if (v < 0) "-" else ""
        val exp = n.point - 1
        // .NET's "G": fixed while -5 < exponent < precision, so 0.0001 is fixed and 1E-05 is not
        if (exp >= precision || exp < -4) {
            val mant = n.d.substring(0, 1) + (if (n.d.length > 1) "." + n.d.substring(1) else "")
            val e = abs(exp).toString().padStart(2, '0')
            return sign + mant + "E" + (if (exp < 0) "-" else "+") + e
        }
        val sb = StringBuilder()
        if (n.point <= 0) {
            sb.append("0.")
            repeat(-n.point) { sb.append('0') }
            sb.append(n.d)
        } else if (n.d.length <= n.point) {
            sb.append(n.d)
            repeat(n.point - n.d.length) { sb.append('0') }
        } else {
            sb.append(n.d, 0, n.point).append('.').append(n.d, n.point, n.d.length)
        }
        return sign + sb.toString()
    }

    /**
     * A .NET custom numeric format of the kinds WDP uses: `#`/`0` placeholders, one `.`, nothing else.
     * The integer part prints every digit it has and at least as many as there are `0`s from the first `0` to the
     * point; the fraction rounds (half up) at the number of placeholders after the point and prints up to the last
     * `0` placeholder, and beyond it only digits that are not trailing zeros.
     */
    private fun custom(v: Double, precision: Int, pattern: String): String {
        val dot = pattern.indexOf('.')
        val intPart = if (dot < 0) pattern else pattern.substring(0, dot)
        val fracPart = if (dot < 0) "" else pattern.substring(dot + 1)
        val firstZero = intPart.indexOf('0')
        val minInt = if (firstZero < 0) 0 else intPart.length - firstZero
        val decimals = fracPart.length
        val lastZero = fracPart.lastIndexOf('0') + 1   // fraction digits always printed
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "∞" else "-∞"
        var n = number(abs(v), precision)
        // round at the last fraction place
        if (n.d.isNotEmpty()) n = roundAt(n.d, n.point, n.point + decimals)
        val intDigits = if (n.d.isEmpty() || n.point <= 0) "" else if (n.d.length >= n.point) n.d.substring(0, n.point) else n.d + "0".repeat(n.point - n.d.length)
        val fracAll = StringBuilder()
        for (i in 0 until decimals) {
            val idx = n.point + i
            fracAll.append(if (n.d.isNotEmpty() && idx >= 0 && idx < n.d.length) n.d[idx] else '0')
        }
        var frac = fracAll.toString()
        var keep = frac.length
        while (keep > lastZero && frac[keep - 1] == '0') keep--
        frac = frac.substring(0, keep)
        val sb = StringBuilder()
        sb.append(intDigits.padStart(minInt, '0'))
        if (frac.isNotEmpty()) sb.append('.').append(frac)
        val zero = n.d.isEmpty()
        return (if (v < 0 && !zero) "-" else "") + sb.toString()
    }
}
