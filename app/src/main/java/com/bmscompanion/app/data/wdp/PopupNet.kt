package com.bmscompanion.app.data.wdp

/**
 * How .NET Framework and the Visual Basic runtime turn numbers into text and text into numbers, as Weapon Delivery
 * Planner's Pop-up page meets them — the part of a port that is easiest to get nearly right.
 *
 * The page prints floats and doubles through three doors, and each rounds in its own way. Checked against the
 * program itself (the `FmtProbe` run of `tools/wdpref`, .NET Framework 4.8, en-US):
 *
 * - **A float keeps 7 significant digits, a double 15**, before anything else happens. `Conversions.ToString` and a
 *   custom format such as `"##0.0"` both start from those digits, rounded **half up** on the exact binary value:
 *   `1234567.5f` prints `1234568`, `1234568.5f` prints `1234569`, and the double nearest 0.35 (which is a hair
 *   under it) prints `0.4` through `"##0.0"`, because at 15 digits it *is* 0.35.
 * - **A custom format then rounds half up again**, on those digits: `123.45f` is `123.4499969…`, which is
 *   `123.4500` at 7 digits and so `123.5`. Formatting the exact value would say `123.4`.
 * - **A number that rounds to zero loses its sign** (`-0.04` → `0.0`), and `"G"` goes to exponent form once the
 *   exponent reaches the precision (`12345678f` → `1.234568E+07`).
 *
 * And it reads text back through VB's `Conversions` (the second half of this file), whose rules are not Kotlin's:
 * "1,000" and "(300)" and "&H1E" are numbers, "NaN" and "∞" are numbers and "Infinity" is not, " True " is not a
 * Boolean, a null string is 0 and an empty one throws, and "&H-1" throws something `IsNumeric` does not catch.
 *
 * The digits are worked out exactly (the double's binary value expanded in decimal) so none of this depends on the
 * platform's own number printing, which differs between the JVM, Android and the browser.
 */
object PopupNet {

    /** A string VB could not read as a number (`InvalidCastException`). */
    class PopupCast(s: String) : PopupPlan.PopupError("Conversion from string \"$s\" to type 'Double' is not valid.")

    /** `Conversions.ToString(Single)`: "G", 7 significant digits. */
    fun str(v: Float): String = general(v.toDouble(), 7)

    /** `Conversions.ToString(Double)`: "G", 15 significant digits. */
    fun str(v: Double): String = general(v, 15)

    /** `Strings.Format(Single, "##0.0")` (and `"#0.0"`, which prints the same). */
    fun f1(v: Float): String = custom(v.toDouble(), 7, leadingZero = true)

    /** `Strings.Format(Double, "##0.0")` (and `"#0.0"`). */
    fun f1(v: Double): String = custom(v, 15, leadingZero = true)

    /** `Strings.Format(Double, "#.0")`: the same, without a zero before the point. */
    fun f1NoLead(v: Double): String = custom(v, 15, leadingZero = false)

    /** `Strings.Format(Single, fmt)` for a custom format of [minInt] integer zeros and [decimals] places ("#00.000"). */
    fun fmt(v: Float, minInt: Int, decimals: Int): String = customN(v.toDouble(), 7, minInt, decimals)

    /** `Strings.Format(Double, fmt)` for a custom format of [minInt] integer zeros and [decimals] places. */
    fun fmt(v: Double, minInt: Int, decimals: Int): String = customN(v, 15, minInt, decimals)

    /** `Strings.Format(Integer, "#00")` / `"#000"`: at least [minInt] digits, the sign in front. */
    fun fmtInt(v: Int, minInt: Int): String {
        val a = kotlin.math.abs(v.toLong()).toString().padStart(minInt, '0')
        return if (v < 0) "-$a" else a
    }

    // ---------------------------------------------------------------- the digits

    /** A number's significant digits (no leading or trailing zeros) and where the point goes: 0.d1d2… × 10^point. */
    private class Digits(val d: String, val point: Int)

    /** The exact decimal expansion of a finite, non-zero double's magnitude. */
    private fun exact(v: Double): Digits {
        val bits = v.toRawBits()
        val expBits = ((bits ushr 52) and 0x7ffL).toInt()
        val frac = bits and 0xfffffffffffffL
        val m: Long
        val e: Int
        if (expBits == 0) { m = frac; e = -1074 } else { m = frac or (1L shl 52); e = expBits - 1075 }
        // v = m × 2^e. With e < 0 that is m × 5^-e / 10^-e, so the digits of m × 5^-e are the digits of v.
        val big = Big(m)
        val shift: Int
        if (e >= 0) { repeat(e) { big.times(2) }; shift = 0 } else { var k = -e; while (k > 0) { val s = minOf(k, 13); big.times(POW5[s]); k -= s }; shift = -e }
        var s = big.toString()
        val point = s.length - shift
        s = s.trimEnd('0')
        return Digits(s, point)
    }

    /** Rounded to [precision] significant digits, half up on the exact value, as .NET Framework's DoubleToNumber. */
    private fun rounded(v: Double, precision: Int): Digits {
        val x = exact(v)
        if (x.d.length <= precision) return x
        return roundAt(x.d, x.point, precision)
    }

    /** Keep [keep] digits of [d], rounding half up on the next one; carries may lengthen the number by one place. */
    private fun roundAt(d: String, point: Int, keep: Int): Digits {
        if (keep < 0) return Digits("", 0)
        if (keep >= d.length) return Digits(d.trimEnd('0'), point)
        val head = d.substring(0, keep).toCharArray()
        if (d[keep] < '5') return Digits(head.concatToString().trimEnd('0'), if (head.isEmpty()) 0 else point)
        var i = keep - 1
        while (i >= 0) {
            if (head[i] == '9') { head[i] = '0'; i-- } else { head[i] = head[i] + 1; break }
        }
        return if (i < 0) Digits(("1" + head.concatToString()).trimEnd('0'), point + 1) else Digits(head.concatToString().trimEnd('0'), point)
    }

    private fun special(v: Double): String? = when {
        v.isNaN() -> "NaN"
        // the culture's own symbols, which on Windows 10's en-US are these (the coordinates harness prints them)
        v == Double.POSITIVE_INFINITY -> "∞"
        v == Double.NEGATIVE_INFINITY -> "-∞"
        else -> null
    }

    /** .NET "G" with the type's default precision. */
    private fun general(v: Double, precision: Int): String {
        special(v)?.let { return it }
        if (v == 0.0) return "0"
        val r = rounded(kotlin.math.abs(v), precision)
        val sign = if (v < 0) "-" else ""
        val exp = r.point - 1
        if (exp >= precision || exp < -5) {
            val mant = if (r.d.length > 1) r.d.substring(0, 1) + "." + r.d.substring(1) else r.d
            val es = kotlin.math.abs(exp).toString().padStart(2, '0')
            return sign + mant + "E" + (if (exp < 0) "-" else "+") + es
        }
        return sign + fixed(r.d, r.point)
    }

    private fun fixed(d: String, point: Int): String = when {
        point <= 0 -> "0." + "0".repeat(-point) + d
        d.length <= point -> d + "0".repeat(point - d.length)
        else -> d.substring(0, point) + "." + d.substring(point)
    }

    /** A custom one-decimal format: the type's digits first, then half up to one place; zero has no sign. */
    private fun custom(v: Double, precision: Int, leadingZero: Boolean): String {
        special(v)?.let { return it }
        var intPart: String
        var decimal: Char
        var negative = v < 0
        if (v == 0.0) {
            intPart = ""; decimal = '0'; negative = false
        } else {
            val r = rounded(kotlin.math.abs(v), precision)
            val one = roundAt(r.d, r.point, r.point + 1)
            if (one.d.isEmpty()) {
                intPart = ""; decimal = '0'; negative = false
            } else {
                val all = fixed(one.d, one.point)
                val dot = all.indexOf('.')
                if (dot < 0) { intPart = all; decimal = '0' } else { intPart = all.substring(0, dot); decimal = all[dot + 1] }
                if (intPart == "0") intPart = ""
            }
        }
        if (intPart.isEmpty() && leadingZero) intPart = "0"
        return (if (negative) "-" else "") + intPart + "." + decimal
    }

    /** A custom format of [minInt] integer zeros and [decimals] places: the type's digits first, then half up. */
    private fun customN(v: Double, precision: Int, minInt: Int, decimals: Int): String {
        special(v)?.let { return it }
        val zero = "0".repeat(minInt) + (if (decimals > 0) "." + "0".repeat(decimals) else "")
        if (v == 0.0) return zero
        val r = rounded(kotlin.math.abs(v), precision)
        val one = roundAt(r.d, r.point, r.point + decimals)
        if (one.d.isEmpty()) return zero
        val all = fixed(one.d, one.point)
        val dot = all.indexOf('.')
        var intPart = if (dot < 0) all else all.substring(0, dot)
        var fracPart = if (dot < 0) "" else all.substring(dot + 1)
        if (intPart == "0") intPart = ""
        intPart = intPart.padStart(minInt, '0')
        fracPart = fracPart.padEnd(decimals, '0')
        return (if (v < 0) "-" else "") + intPart + (if (decimals > 0) ".$fracPart" else "")
    }

    private val POW5 = LongArray(14).also { it[0] = 1; for (i in 1 until 14) it[i] = it[i - 1] * 5 }

    /** Just enough of an unsigned big integer: base 10^9 limbs, multiply by a small number, print. */
    private class Big(start: Long) {
        private var limbs = IntArray(4)
        private var n = 0

        init {
            var s = start
            while (s > 0) { push((s % BASE).toInt()); s /= BASE }
        }

        private fun push(v: Int) {
            if (n == limbs.size) limbs = limbs.copyOf(n * 2)
            limbs[n++] = v
        }

        fun times(k: Long) {
            var carry = 0L
            for (i in 0 until n) {
                // limb < 1e9 and k ≤ 5^13 ≈ 1.2e9: the product stays below 2^63
                val p = limbs[i].toLong() * k + carry
                limbs[i] = (p % BASE).toInt()
                carry = p / BASE
            }
            while (carry > 0) { push((carry % BASE).toInt()); carry /= BASE }
        }

        override fun toString(): String {
            if (n == 0) return "0"
            val sb = StringBuilder(limbs[n - 1].toString())
            for (i in n - 2 downTo 0) sb.append(limbs[i].toString().padStart(9, '0'))
            return sb.toString()
        }

        companion object { const val BASE = 1_000_000_000L }
    }

    // ---------------------------------------------------------------- VB's own conversions of a string
    //
    // Checked against the Microsoft.VisualBasic runtime WDP runs on (`wdpref page Popup` samples every one of these
    // over the strings a Setup.ini can hold): `Versioned.IsNumeric`, `Conversions.ToDouble/ToInteger/ToBoolean/
    // ToDecimal(String)`. The grammar is .NET's NumberStyles.Any in en-US — white space either side, a sign or a "$"
    // before or a sign after, parentheses for a negative, "," anywhere in the whole part, an exponent — plus VB's
    // "&H" and "&O"; exactly "NaN" is a number and "Infinity" is not; a null string is 0 and an empty one is not a
    // number at all.

    /** A string .NET could not parse (`FormatException`): VB turns it into [PopupCast]; `IsNumeric` into false. */
    class PopupFormat : PopupPlan.PopupError("Input string was not in a correct format.")

    /** `Convert.ToInt64("-…", 16 or 8)`: an `ArgumentException`, which none of VB's conversions catch. */
    class PopupArgument : PopupPlan.PopupError("String cannot contain a minus sign if the base is not 10.")

    /** `Conversions.ToDouble(String)`: null is 0; anything else must be a number, or it throws as VB does. */
    fun toDouble(s: String?): Double {
        if (s == null) return 0.0
        return try {
            hexOrOct(s)?.toDouble() ?: parseDouble(s)
        } catch (e: PopupFormat) {
            throw PopupCast(s)
        }
    }

    /** `Conversions.ToSingle(String)`: as [toDouble], then a value beyond a float's range is an overflow (infinity is not). */
    fun toSingle(s: String?): Float {
        if (s == null) return 0f
        return try {
            val h = hexOrOct(s)
            if (h != null) h.toFloat() else {
                val d = parseDouble(s)
                if ((d < -Float.MAX_VALUE.toDouble() || d > Float.MAX_VALUE.toDouble()) && !d.isInfinite()) throw PopupPlan.PopupOverflow()
                d.toFloat()
            }
        } catch (e: PopupFormat) {
            throw PopupCast(s)
        }
    }

    /** `Conversions.ToInteger(String)`: rounded to even, and an overflow is an exception. */
    fun toInteger(s: String?): Int {
        if (s == null) return 0
        return try {
            val h = hexOrOct(s)
            if (h != null) {
                if (h > Int.MAX_VALUE || h < Int.MIN_VALUE) throw PopupPlan.PopupOverflow()
                h.toInt()
            } else PopupPlan.cint(parseDouble(s))
        } catch (e: PopupFormat) {
            throw PopupCast(s)
        }
    }

    /**
     * `Conversions.ToBoolean(String)`: "True"/"False" in any case (and nothing around them), else a number other
     * than 0; a null string is read as "" and so is not a Boolean at all.
     */
    fun toBoolean(s0: String?): Boolean {
        val s = s0 ?: ""
        if (s.equals("False", ignoreCase = true)) return false
        if (s.equals("True", ignoreCase = true)) return true
        return try {
            val h = hexOrOct(s)
            if (h != null) h != 0L else parseDouble(s) != 0.0
        } catch (e: PopupFormat) {
            throw PopupCast(s)
        }
    }

    /**
     * `Versioned.IsNumeric(String)`. A malformed "&H…"/"&O…" is simply not a number, but the two things
     * `Convert.ToInt64` throws besides — a minus sign after "&H" (`ArgumentException`) and more digits than 64 bits
     * hold (`OverflowException`) — are not caught, so they leave `IsNumeric` itself, and `Setup()` with it. A
     * decimal number too big for a double is merely not numeric (`Double.TryParse`).
     */
    fun isNumeric(s: String?): Boolean {
        if (s == null) return false
        try {
            if (hexOrOct(s) != null) return true
        } catch (e: PopupFormat) {
            return false
        }
        val n = parseNumber(s) ?: return specialDouble(s) != null
        return try { n.toDouble(); true } catch (e: PopupPlan.PopupOverflow) { false }
    }

    /** `Conversions.ToDecimal(String)`, as a [Dec]: the digits and the scale the text gave, trailing zeros kept. */
    fun toDecimal(s: String?): Dec {
        if (s == null) return Dec.ZERO
        try {
            hexOrOct(s)?.let { return Dec.of(it) }
        } catch (e: PopupFormat) {
            throw PopupCast(s)
        }
        val n = parseNumber(s) ?: throw PopupCast(s)
        n.toDouble()   // out of any range at all is an overflow here too
        return n.toDec()
    }

    /**
     * `Decimal.Parse(text, en-US)` — NumberStyles.Number, what a NumericUpDown reads typed text with: white space,
     * a sign before or after, thousands separators and a point; no exponent, currency or parentheses. Null for
     * anything else, and for a number too big for a Decimal (the box ignores both).
     */
    fun parseNumberStyle(text: String): Dec? {
        if (text.any { !(it in '0'..'9' || it == '.' || it == ',' || it == '+' || it == '-' || isWhite(it)) }) return null
        val n = parseNumber(text) ?: return null
        return try { n.toDouble(); n.toDec() } catch (e: PopupPlan.PopupError) { null }
    }

    /** VB's ParseDouble: `Double.Parse` with NumberStyles.Any in en-US — the number, else the culture's symbols. */
    private fun parseDouble(s: String): Double {
        val n = parseNumber(s) ?: return specialDouble(s) ?: throw PopupFormat()
        return n.toDouble()
    }

    /**
     * What `Double.Parse` accepts when the number itself does not parse: the trimmed text equal to the culture's
     * symbols. On the machines WDP runs on, en-US says "∞" and "-∞" (Windows' own locale data) and "NaN"; the word
     * "Infinity" is not one of them.
     */
    private fun specialDouble(s: String): Double? = when (s.trim { netWhiteSpace(it) }) {
        "∞" -> Double.POSITIVE_INFINITY
        "-∞" -> Double.NEGATIVE_INFINITY
        "NaN" -> Double.NaN
        else -> null
    }

    /** .NET's `Char.IsWhiteSpace`, which `String.Trim()` uses. */
    private fun netWhiteSpace(c: Char): Boolean = c == ' ' || c in '\u0009'..'\u000d' || c == ' ' || c == '\u0085' ||
        c == ' ' || c in ' '..' ' || c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　'

    private fun isWhite(c: Char) = c == ' ' || c in '\u0009'..'\u000d'

    /**
     * VB's `IsHexOrOctValue`: after spaces (only U+0020 and U+3000), a "&" with at least two characters after it
     * makes the rest a hex or octal number, read by `Convert.ToInt64(rest, 16 or 8)`; null when the text is not of
     * that shape. "&" and a letter other than H or O is a `FormatException`, and so is anything `ToInt64` will not
     * read to the last character (a trailing space included).
     */
    private fun hexOrOct(s: String): Long? {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '&' && i + 2 < s.length) break
            if (ch != ' ' && ch != '　') return null
            i++
        }
        if (i >= s.length) return null
        val radix = when (s[i + 1]) { 'h', 'H' -> 16; 'o', 'O' -> 8; else -> throw PopupFormat() }
        return toInt64(s.substring(i + 2), radix)
    }

    /** `Convert.ToInt64(v, radix)` for 16 and 8 (ParseNumbers.StringToLong, IsTight): unsigned, all 64 bits. */
    private fun toInt64(v: String, radix: Int): Long {
        var i = 0
        if (v[0] == '-') throw PopupArgument()
        if (v[0] == '+') i++
        if (radix == 16 && i + 1 < v.length && v[i] == '0' && (v[i + 1] == 'x' || v[i + 1] == 'X')) i += 2
        val start = i
        val r = radix.toULong()
        val maxVal = ULong.MAX_VALUE / r
        var result = 0uL
        while (i < v.length) {
            val c = v[i]
            val d = when (c) { in '0'..'9' -> c - '0'; in 'A'..'Z' -> c - 'A' + 10; in 'a'..'z' -> c - 'a' + 10; else -> 99 }
            if (d >= radix) break
            if (result > maxVal) throw PopupPlan.PopupOverflow()
            val temp = result * r + d.toULong()
            if (temp < result) throw PopupPlan.PopupOverflow()
            result = temp
            i++
        }
        if (i == start) throw PopupFormat()
        if (i < v.length) throw PopupFormat()
        return result.toLong()
    }

    /** A parsed number: its digits exactly, and where the point goes. */
    private class Num(val neg: Boolean, val digits: String, val fracDigits: Int, val exp: Int, val nan: Boolean = false) {
        fun toDouble(): Double {
            if (nan) return Double.NaN
            val t = digits.trimStart('0')
            // no digit but zeros: .NET Framework's ParseNumber drops the sign, so "-0" is 0, not -0
            if (t.isEmpty()) return 0.0
            val e = exp - fracDigits
            // the number is t × 10^e; far out of range either way it is an overflow or a zero, as .NET says
            if (t.length + e > 400) throw PopupPlan.PopupOverflow()
            // a number too small for a double is 0 — a positive one, whatever its sign (measured: "-1e-330" is 0, not -0)
            if (t.length + e < -400) return 0.0
            val d = (t + "E" + e).toDouble()
            if (d.isInfinite()) throw PopupPlan.PopupOverflow()
            if (d == 0.0) return 0.0
            return if (neg) -d else d
        }

        fun toDec(): Dec {
            // value = digits × 10^(exp - fracDigits); a decimal keeps the scale unless the exponent takes it away
            var scale = fracDigits - exp
            var ds = digits
            if (scale < 0) { ds += "0".repeat(-scale); scale = 0 }
            return Dec.normalise(neg, ds, scale)
        }
    }

    /** .NET's ParseNumber for NumberStyles.Any in en-US, or null when the text is not a number. */
    private fun parseNumber(s: String): Num? {
        val t = s.trim { isWhite(it) }
        var i = 0
        var neg = false
        var signSeen = false
        var paren = false
        var currency = false
        // the leading part: a sign, "(", "$", each once, white space between
        while (i < t.length) {
            val c = t[i]
            when {
                (c == '+' || c == '-') && !signSeen && !paren -> { signSeen = true; neg = c == '-'; i++ }
                c == '(' && !paren && !signSeen -> { paren = true; neg = true; i++ }
                c == '$' && !currency -> { currency = true; i++ }
                isWhite(c) && (signSeen || paren || currency) -> i++
                else -> break
            }
        }
        val digits = StringBuilder()
        var frac = 0
        var any = false
        while (i < t.length && (isDigit(t[i]) || (t[i] == ',' && any))) {
            if (t[i] != ',') { digits.append(t[i]); any = true }
            i++
        }
        if (i < t.length && t[i] == '.') {
            i++
            while (i < t.length && isDigit(t[i])) { digits.append(t[i]); frac++; any = true; i++ }
        }
        if (!any) return null
        var exp = 0
        if (i < t.length && (t[i] == 'e' || t[i] == 'E')) {
            var j = i + 1
            var eneg = false
            if (j < t.length && (t[j] == '+' || t[j] == '-')) { eneg = t[j] == '-'; j++ }
            if (j < t.length && isDigit(t[j])) {
                var e = 0L
                while (j < t.length && isDigit(t[j])) { if (e < 100000) e = e * 10 + (t[j] - '0'); j++ }
                exp = (if (eneg) -e else e).toInt()
                i = j
            }
        }
        // the trailing part: a sign (unless one came first), ")" to close "(", "$", white space
        while (i < t.length) {
            val c = t[i]
            when {
                (c == '+' || c == '-') && !signSeen && !paren -> { signSeen = true; neg = c == '-'; i++ }
                c == ')' && paren -> { paren = false; i++ }
                c == '$' && !currency -> { currency = true; i++ }
                isWhite(c) -> i++
                else -> return null
            }
        }
        if (paren) return null
        return Num(neg, digits.toString(), frac, exp)
    }

    private fun isDigit(c: Char) = c in '0'..'9'

    /**
     * A .NET `Decimal`, as far as the page needs one: numWaypoint's value, which Setup() can fill from the ini with
     * a fraction ("4.5") or trailing zeros ("5.0"), both of which it prints back as they were.
     */
    class Dec private constructor(val neg: Boolean, /** no leading zeros; "0" for zero */ val digits: String, val scale: Int) {
        /** `Decimal.ToString()`. */
        override fun toString(): String {
            val d = digits.padStart(scale + 1, '0')
            val body = if (scale == 0) d else d.substring(0, d.length - scale) + "." + d.substring(d.length - scale)
            return if (neg && digits != "0") "-$body" else body
        }

        fun toDouble(): Double = toString().toDouble()

        /** `Decimal.ToString("F0")`: to no places, half away from zero (4.5 is "5", 5.5 is "6"). */
        fun f0(): String {
            val padded = digits.padStart(scale + 1, '0')
            var whole = padded.substring(0, padded.length - scale).trimStart('0').ifEmpty { "0" }
            val frac = padded.substring(padded.length - scale)
            if (frac.isNotEmpty() && frac[0] >= '5') whole = addMag(whole, "1")
            return if (neg && whole != "0") "-$whole" else whole
        }

        /** Compared by value, as `Decimal`'s equality is: 5.0 equals 5. */
        fun sameValue(o: Dec): Boolean = signedAligned(maxOf(scale, o.scale)) == o.signedAligned(maxOf(scale, o.scale))

        private fun signedAligned(sc: Int): String {
            val t = (digits + "0".repeat(sc - scale)).trimStart('0')
            return if (t.isEmpty()) "0" else if (neg) "-$t" else t
        }

        /** `Decimal.Subtract(this, n)` for a whole n (the page's values are small; a long carries them). */
        fun minus(n: Int): Dec {
            if (digits.length + 2 < 18 && scale < 16) {
                var p = 1L
                repeat(scale) { p *= 10 }
                val me = digits.toLong().let { if (neg) -it else it }
                return fromLong(me - n.toLong() * p, scale)
            }
            // a long mantissa (a waypoint written with many places): digit by digit, signs and all
            val other = (kotlin.math.abs(n.toLong()).toString() + "0".repeat(scale))
            val otherNeg = n > 0   // this − n = this + (−n)
            val r: String
            val rNeg: Boolean
            if (neg == otherNeg) { r = addMag(digits, other); rNeg = neg }
            else if (cmpMag(digits, other) >= 0) { r = subMag(digits, other); rNeg = neg }
            else { r = subMag(other, digits); rNeg = otherNeg }
            return normalise(rNeg, r, scale)
        }

        private fun cmpMag(a0: String, b0: String): Int {
            val a = a0.trimStart('0'); val b = b0.trimStart('0')
            return if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
        }

        private fun addMag(a0: String, b0: String): String {
            val len = maxOf(a0.length, b0.length) + 1
            val a = a0.padStart(len, '0'); val b = b0.padStart(len, '0')
            val out = CharArray(len)
            var carry = 0
            for (i in len - 1 downTo 0) { val d = (a[i] - '0') + (b[i] - '0') + carry; carry = d / 10; out[i] = '0' + d % 10 }
            return out.concatToString().trimStart('0').ifEmpty { "0" }
        }

        /** a − b for a ≥ b. */
        private fun subMag(a0: String, b0: String): String {
            val len = maxOf(a0.length, b0.length)
            val a = a0.padStart(len, '0'); val b = b0.padStart(len, '0')
            val out = CharArray(len)
            var borrow = 0
            for (i in len - 1 downTo 0) {
                var d = (a[i] - '0') - (b[i] - '0') - borrow
                borrow = if (d < 0) { d += 10; 1 } else 0
                out[i] = '0' + d
            }
            return out.concatToString().trimStart('0').ifEmpty { "0" }
        }

        /** `Convert.ToInt32(Decimal)`: rounded to even on the decimal digits, and too big is an overflow. */
        fun toInt32(): Int {
            val padded = digits.padStart(scale + 1, '0')
            val whole = padded.substring(0, padded.length - scale)
            val fracPart = padded.substring(padded.length - scale)
            if (whole.length > 11) throw PopupPlan.PopupOverflow()
            var w = whole.toLong()
            val half = fracPart.isNotEmpty() && fracPart[0] == '5' && fracPart.drop(1).all { it == '0' }
            val above = fracPart.isNotEmpty() && (fracPart[0] > '5' || (fracPart[0] == '5' && !half))
            if (above || (half && w % 2L == 1L)) w++
            val r = if (neg) -w else w
            if (r > Int.MAX_VALUE || r < Int.MIN_VALUE) throw PopupPlan.PopupOverflow()
            return r.toInt()
        }

        companion object {
            val ZERO = Dec(false, "0", 0)
            fun of(v: Long): Dec = fromLong(v, 0)
            fun of(v: Int): Dec = fromLong(v.toLong(), 0)

            private fun fromLong(v: Long, scale: Int): Dec {
                val neg = v < 0
                val a = if (neg) (-v).toString() else v.toString()
                return Dec(neg, a, scale)
            }

            /**
             * At most 28 places and a mantissa below 2^96, dropping digits (half up) where the text asked for more;
             * the zeros the text wrote after the point are kept ("5.0" stays "5.0", as .NET keeps them).
             */
            internal fun normalise(neg: Boolean, digits0: String, scale0: Int): Dec {
                var ds = digits0.trimStart('0').ifEmpty { "0" }
                var scale = scale0
                fun roundOff() {
                    val last = ds.last()
                    ds = ds.dropLast(1).ifEmpty { "0" }
                    scale--
                    if (last >= '5') ds = increment(ds)
                }
                while (scale > 28) roundOff()
                while (scale > 0 && greaterThanMax(ds)) roundOff()
                if (greaterThanMax(ds)) throw PopupPlan.PopupOverflow()
                return Dec(neg, ds.trimStart('0').ifEmpty { "0" }, scale)
            }

            private const val MAX = "79228162514264337593543950335"
            private fun greaterThanMax(d: String): Boolean {
                val t = d.trimStart('0')
                return t.length > MAX.length || (t.length == MAX.length && t > MAX)
            }

            private fun increment(d: String): String {
                val c = d.toCharArray()
                var i = c.size - 1
                while (i >= 0) { if (c[i] == '9') { c[i] = '0'; i-- } else { c[i] = c[i] + 1; return c.concatToString() } }
                return "1" + c.concatToString()
            }
        }
    }
}
