package com.bmscompanion.app.data.wdp

import kotlin.math.abs

/**
 * The Windows Forms behaviour WDP's DTC page is built on, as far as the page's code depends on it: what a masked box
 * makes of a string, what an up/down box holds, when a check box, a list or an up/down box raises its "changed"
 * event, and that a radio button clears its neighbours. The page's handlers run on those events — a list the page
 * fills from the cartridge fires its handler, which writes the model back — so the port has to raise them exactly
 * when Windows does or the model drifts from WDP's. Every rule here is checked against the real controls by
 * `wdpref page Dtc` (the `mask` rows and every page row of `--wdppagetest dtc`).
 */

/** A .NET `Decimal` as the page's up/down boxes hold one: `mant / 10^scale`, the scale kept ("1.50" is not "1.5"). */
internal class DtcDec(val mant: Long, val scale: Int) : Comparable<DtcDec> {
    override fun toString(): String {
        val neg = mant < 0
        val d = abs(mant).toString().padStart(scale + 1, '0')
        val body = if (scale == 0) d else d.substring(0, d.length - scale) + "." + d.substring(d.length - scale)
        return if (neg) "-$body" else body
    }

    fun toDouble(): Double = mant.toDouble() / POW10[scale]

    private fun stripped(): Pair<Long, Int> {
        var m = mant
        var s = scale
        while (s > 0 && m % 10L == 0L) { m /= 10; s-- }
        return m to s
    }

    /** Equal by value, as `Decimal` compares: 1.50 == 1.5. */
    fun sameValue(o: DtcDec): Boolean = stripped() == o.stripped()

    override fun compareTo(other: DtcDec): Int = if (sameValue(other)) 0 else toDouble().compareTo(other.toDouble())

    /** `Convert.ToInt32(Decimal)`: rounded to even, and outside an Int an overflow. */
    fun toInt32(): Int {
        val r = roundEven()
        if (r > Int.MAX_VALUE || r < Int.MIN_VALUE) throw ArithmeticException("Value was either too large or too small for an Int32.")
        return r.toInt()
    }

    /** `Convert.ToInt16(Decimal)`. */
    fun toInt16(): Short {
        val r = roundEven()
        if (r > Short.MAX_VALUE || r < Short.MIN_VALUE) throw ArithmeticException("Value was either too large or too small for an Int16.")
        return r.toInt().toShort()
    }

    private fun roundEven(): Long {
        if (scale == 0) return mant
        if (scale > 18) return 0L
        val p = POW10L[scale]
        var q = mant / p
        val r = abs(mant % p)
        val half = p / 2
        if (r > half || (r == half && q % 2L != 0L)) q += if (mant < 0) -1 else 1
        return q
    }

    /** `Convert.ToSingle(Decimal)`: the value as a double, then a float. */
    fun toSingle(): Float = toDouble().toFloat()

    fun times(k: Int): DtcDec = DtcDec(mant * k, scale)
    operator fun plus(o: DtcDec): DtcDec {
        val s = maxOf(scale, o.scale)
        return DtcDec(mant * POW10L[s - scale] + o.mant * POW10L[s - o.scale], s)
    }

    companion object {
        private val POW10 = DoubleArray(40) { i -> var d = 1.0; repeat(i) { d *= 10.0 }; d }
        private val POW10L = LongArray(19) { i -> var d = 1L; repeat(i) { d *= 10L }; d }
        val ZERO = DtcDec(0, 0)
        fun of(v: Int) = DtcDec(v.toLong(), 0)

        /**
         * `new Decimal(float)` (`VarDecFromR4`): seven significant digits, then as many trailing zeros taken off
         * as its three steps (by 10^4, 10^2 and 10) manage, never more than six. NaN and infinities throw.
         */
        fun ofFloat(v: Float): DtcDec {
            val (m0, p0) = DataCardNet.decimalOf(v)
            if (m0 == 0L) return ZERO
            if (p0 <= 0) return DtcDec(m0, 0)
            var mant = abs(m0)
            var power = p0
            var lmax = minOf(power, 6)
            if ((mant and 0x0F) == 0L && lmax >= 4) { val div = mant / 10000; if (mant == div * 10000) { mant = div; power -= 4; lmax -= 4 } }
            if ((mant and 3) == 0L && lmax >= 2) { val div = mant / 100; if (mant == div * 100) { mant = div; power -= 2; lmax -= 2 } }
            if ((mant and 1) == 0L && lmax >= 1) { val div = mant / 10; if (mant == div * 10) { mant = div; power-- } }
            return DtcDec(if (m0 < 0) -mant else mant, power)
        }

        /** Invariant text ("12.3", "-5") into a Decimal, the scale as written: what the test's up/down edits carry. */
        fun parse(s: String): DtcDec {
            val neg = s.startsWith("-")
            val body = s.removePrefix("-")
            val dot = body.indexOf('.')
            val digits = if (dot < 0) body else body.substring(0, dot) + body.substring(dot + 1)
            val scale = if (dot < 0) 0 else body.length - dot - 1
            val m = digits.toLong()
            return DtcDec(if (neg) -m else m, scale)
        }
    }
}

/**
 * A `MaskedTextBox` with its `MaskedTextProvider`, for the masks the page uses: what setting `Text` places where
 * (one character at a time, replacing, a character the mask refuses dropped and the rest carried on, a space or
 * the prompt character clearing its place, a literal typed where the mask has it stepped over, whatever is left
 * of the old text after the last placed character cleared) and what `Text` reads back under the box's format.
 */
internal class DtcMaskedText(mask: String, private val prompt: Char, private val includePrompt: Boolean, private val includeLiterals: Boolean) {
    private val edit: BooleanArray
    private val maskCh: CharArray
    private val chars: CharArray
    private val assigned: BooleanArray
    val length: Int

    init {
        val e = ArrayList<Boolean>(); val mc = ArrayList<Char>(); val lit = ArrayList<Char>()
        var i = 0
        while (i < mask.length) {
            val c = mask[i]
            when (c) {
                '0', '9', '#', 'L', '?', '&', 'C', 'A', 'a' -> { e += true; mc += c; lit += prompt }
                '.' -> { e += false; mc += c; lit += '.' }
                ',' -> { e += false; mc += c; lit += ',' }
                ':' -> { e += false; mc += c; lit += ':' }
                '/' -> { e += false; mc += c; lit += '/' }
                '$' -> { e += false; mc += c; lit += '$' }
                '<', '>', '|' -> { }
                '\\' -> { i++; if (i < mask.length) { e += false; mc += mask[i]; lit += mask[i] } }
                else -> { e += false; mc += c; lit += c }
            }
            i++
        }
        length = e.size
        edit = e.toBooleanArray(); maskCh = mc.toCharArray(); chars = lit.toCharArray(); assigned = BooleanArray(length)
    }

    private fun findEditFrom(pos: Int): Int { for (i in maxOf(pos, 0) until length) if (edit[i]) return i; return -1 }

    private fun testEscape(c: Char, pos: Int): Boolean =
        if (!edit[pos]) c == chars[pos] else c == prompt || c == ' '

    private fun isPrintable(c: Char): Boolean {
        if (c.isLetterOrDigit() || c == ' ') return true
        return when (c.category) {
            CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION, CharCategory.START_PUNCTUATION,
            CharCategory.END_PUNCTUATION, CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION,
            CharCategory.OTHER_PUNCTUATION, CharCategory.MATH_SYMBOL, CharCategory.CURRENCY_SYMBOL,
            CharCategory.MODIFIER_SYMBOL, CharCategory.OTHER_SYMBOL -> true
            else -> false
        }
    }

    /** `MaskedTextProvider.Replace(char, position)`: where it went, or -1. */
    private fun replaceChar(c: Char, position: Int): Int {
        if (position < 0 || position >= length) return -1
        var tp = position
        if (!testEscape(c, tp)) { tp = findEditFrom(tp); if (tp < 0) return -1 }
        if (!isPrintable(c)) return -1
        if (!edit[tp]) return if (c == chars[tp]) tp else -1
        if (c == prompt || c == ' ') {
            if (assigned[tp]) { assigned[tp] = false; chars[tp] = prompt }
            return tp
        }
        val ok = when (maskCh[tp]) {
            '0' -> c.isDigit()
            '9' -> c.isDigit()
            '#' -> c.isDigit() || c == '-' || c == '+'
            'L', '?' -> c.isLetter()
            'A', 'a' -> c.isLetterOrDigit()
            else -> true
        }
        if (!ok) return -1
        chars[tp] = c; assigned[tp] = true
        return tp
    }

    /** `MaskedTextBox.Text = value`. */
    fun set(value: String?) {
        if (value.isNullOrEmpty()) { clearFrom(0); return }
        var start = 0
        val end = length - 1
        for (c in value) {
            val escapes = start in 0 until length && testEscape(c, start)
            if (!escapes) {
                val e = findEditFrom(start)
                if (e < 0) continue
                start = e
            }
            if (start <= end) {
                val placed = replaceChar(c, start)
                if (placed >= 0) start = placed + 1
            }
        }
        if (start <= end) clearFrom(start)
    }

    private fun clearFrom(from: Int) { for (i in from until length) if (edit[i]) { assigned[i] = false; chars[i] = prompt } }

    /** `MaskedTextBox.Text`. */
    fun text(): String {
        if (includePrompt && includeLiterals) return chars.concatToString()
        var last = length - 1
        if (!includePrompt) {
            var lastLit = -1
            if (includeLiterals) for (i in last downTo 0) if (!edit[i]) { lastLit = i; break }
            var lastAssigned = -1
            for (i in last downTo maxOf(lastLit, 0)) if (edit[i] && assigned[i]) { lastAssigned = i; break }
            last = if (lastAssigned != -1) lastAssigned else lastLit
            if (last == -1) return ""
        }
        val sb = StringBuilder()
        for (i in 0..last) {
            if (edit[i]) sb.append(if (assigned[i]) chars[i] else if (includePrompt) prompt else ' ')
            else if (includeLiterals) sb.append(chars[i])
        }
        return sb.toString()
    }
}

/**
 * One control of the page, with the properties and events its code touches. [fore]/[back] are null until the code
 * sets them (the designer's, inherited from the parent, apply until then); everything else starts as the designer
 * left it.
 */
internal class DtcCtl(val name: String, private val d: DtcDesigner.Designed?) {
    val kind: Char = d?.kind ?: 'L'
    val parent: String = d?.parent ?: ""
    private val masked: DtcMaskedText? = if (kind == 'M' && d != null) DtcMaskedText(
        d.mask, if (d.prompt.isNotEmpty()) d.prompt[0] else '_',
        d.format == "IncludePrompt" || d.format == "IncludePromptAndLiterals",
        d.format != "IncludePrompt" && d.format != "ExcludePromptAndLiterals",
    ).also { it.set(d.text ?: "") } else null

    private var plainText: String = d?.text ?: ""
    var text: String
        get() = masked?.text() ?: plainText
        set(v) { if (masked != null) masked.set(v) else plainText = v }

    var visible: Boolean = d?.flags?.contains('v') != true
    var enabled: Boolean = d?.flags?.contains('e') != true
    var fore: Int? = null
    var back: Int? = null

    // check box / radio button
    var checkState: Int = if (d?.flags?.contains('k') == true) 1 else 0
        private set
    val checked get() = checkState != 0
    var onCheckedChanged: (() -> Unit)? = null
    var radioSiblings: () -> List<DtcCtl> = { emptyList() }

    fun setCheckState(v: Int) {
        if (checkState == v) return
        val old = checked
        checkState = v
        if (old != checked) onCheckedChanged?.invoke()
    }

    fun setChecked(v: Boolean) {
        if (kind == 'R') {
            if (checked == v) return
            checkState = if (v) 1 else 0
            if (v) for (s in radioSiblings()) if (s !== this && s.checked) s.setChecked(false)
            onCheckedChanged?.invoke()
            return
        }
        if (v != checked) setCheckState(if (v) 1 else 0)
    }

    // list
    val items: MutableList<String> = (d?.items ?: emptyList()).toMutableList()
    /**
     * The designer sets a list's `Text` after its `Items`, and `ComboBox.Text` then selects the item it names
     * (`FindStringIgnoreCase`): `cboPreset_1`'s "0" starts selected at index 0, not -1.
     */
    var selectedIndex = if (kind == 'C' && !d?.text.isNullOrEmpty()) items.indexOfFirst { it.equals(d!!.text, ignoreCase = true) } else -1
        private set
    var onSelectedIndexChanged: (() -> Unit)? = null

    fun select(v: Int) {
        if (selectedIndex == v) return
        if (v < -1 || v >= items.size) throw IndexOutOfBoundsException("InvalidArgument=Value of '$v' is not valid for 'SelectedIndex'.")
        selectedIndex = v
        plainText = if (v >= 0) items[v] else ""
        onSelectedIndexChanged?.invoke()
    }

    /** `SelectedItem = s`: selected where the list holds it; nothing happens when it does not. */
    fun selectItem(s: String?) {
        if (s == null) { select(-1); return }
        val x = items.indexOf(s)
        if (x != -1) select(x)
    }

    val selectedItem: String? get() = if (selectedIndex >= 0) items[selectedIndex] else null

    fun clearItems() { items.clear(); selectedIndex = -1 }

    /** `Sorted = true` after the items went in: the list sorted, nothing selected. */
    fun sortItems() {
        items.sortWith { a, b -> DtcPage.cultureCompare(a, b) }
        select(-1)
    }

    // up/down box
    val maximum: DtcDec = DtcDec.of(d?.maximum ?: 100)
    val minimum: DtcDec = DtcDec.ZERO
    val decimals: Int = d?.decimals ?: 0
    var value: DtcDec = DtcDec.ZERO
        private set
    var onValueChanged: (() -> Unit)? = null

    fun setValue(v: DtcDec) {
        if (v.sameValue(value)) return
        if (v < minimum || v > maximum) throw IndexOutOfBoundsException("Value of '$v' is not valid for 'Value'.")
        value = v
        onValueChanged?.invoke()
    }

    // what the pilot does to it
    var onClick: (() -> Unit)? = null
    var onLeave: (() -> Unit)? = null

    /** A mouse click: a check box toggles and a radio button checks itself first, as their OnClick does. */
    fun click() {
        if (kind == 'K') setCheckState(if (checkState == 0) 1 else 0)
        else if (kind == 'R') setChecked(true)
        onClick?.invoke()
    }
}

/** A masked box on its own, for the test's `mask` rows: [input] set as `Text` under [mask], and `Text` read back. */
object DtcMaskProbe {
    fun text(mask: String, prompt: Char, format: String, input: String): String {
        val b = DtcMaskedText(mask, prompt, format == "IncludePrompt" || format == "IncludePromptAndLiterals",
            format != "IncludePrompt" && format != "ExcludePromptAndLiterals")
        b.set(input)
        return b.text()
    }
}
