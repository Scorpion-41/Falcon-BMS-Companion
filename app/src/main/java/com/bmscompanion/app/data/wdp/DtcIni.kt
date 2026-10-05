package com.bmscompanion.app.data.wdp

/**
 * Windows' profile API — `GetPrivateProfileStringW` / `WritePrivateProfileStringW` — over the text of a file, as
 * Weapon Delivery Planner's `clsIni` calls it on the pilot's cartridge. WDP reads and writes the cartridge one key at
 * a time through these two calls, so what lands in the file is decided as much by Windows as by WDP: where a new key
 * goes, what happens to the spaces around a value that is replaced, what a deleted section takes with it. A port
 * that wrote the file its own way would produce a different file from the same model.
 *
 * Nothing here was taken from documentation; every rule below was found by driving the real API over crafted files
 * and is re-checked by `wdpref page Dtc` against thousands of random ones (the `ini` rows of `--wdppagetest dtc`):
 *
 * - **Lines** end at CR LF, LF, or a CR on its own. Before any change a file that does not end in LF has the spaces
 *   and tabs at its very end dropped and then CR LF added; everything Windows adds ends in CR LF.
 * - **Sections**: a line whose first non-blank character is `[`; the name runs to the first `]` (or the end of the
 *   line) and is compared trimmed and without regard to case. **Only the first** section of a name exists — a key
 *   in a second `[STPT]` is invisible to a read and a write adds its key to the first.
 * - **Keys**: a line with an `=` whose first non-blank character is not `;`. The key is what is before the first
 *   `=`, trimmed; the first of a name wins. A read gives the value trimmed of spaces and tabs, without one pair of
 *   matching quotes (`"…"` or `'…'`), at most 1,023 characters.
 * - **Replacing** a value rewrites from just after the `=` to the end of the value: the blanks before the old value
 *   go, the line's indentation, its key's spelling and the blanks after the value survive.
 * - **Adding** a key to a section puts `key=value` after the section's last key line — past comments and lines with
 *   no `=`, which stay below it — except that in a section that runs to the end of a file whose last line is blank,
 *   it goes at the very end. A new section goes at the end of the file.
 * - **Deleting** a key removes its line from the key's first character (the line's indentation stays behind and
 *   joins the next line). Deleting a section removes from its `[` to the end of its last line that is not blank or
 *   a comment, or to the end of the file under the same last-line-blank rule as adding.
 *
 * Text only: the caller reads the file and writes the result. Files are ASCII in practice; the rules are the same
 * for any character, but Windows reads a file without a byte-order mark in the system code page, which is the
 * caller's business.
 */
object DtcIni {

    private class Line(val start: Int, val contentEnd: Int, val end: Int)

    private fun isBlankChar(c: Char) = c == ' ' || c == '\t'

    private fun lines(t: String): List<Line> {
        val out = ArrayList<Line>()
        var i = 0
        val n = t.length
        while (i < n) {
            var j = i
            while (j < n && t[j] != '\r' && t[j] != '\n') j++
            val contentEnd = j
            if (j < n) {
                j += if (t[j] == '\r' && j + 1 < n && t[j + 1] == '\n') 2 else 1
            }
            out += Line(i, contentEnd, j)
            i = j
        }
        return out
    }

    private fun firstNonBlank(t: String, l: Line): Int {
        var i = l.start
        while (i < l.contentEnd && isBlankChar(t[i])) i++
        return i
    }

    private fun isBlank(t: String, l: Line) = firstNonBlank(t, l) == l.contentEnd
    private fun isHeader(t: String, l: Line): Boolean { val i = firstNonBlank(t, l); return i < l.contentEnd && t[i] == '[' }
    private fun isComment(t: String, l: Line): Boolean { val i = firstNonBlank(t, l); return i < l.contentEnd && t[i] == ';' }
    private fun eqAt(t: String, l: Line): Int { for (i in l.start until l.contentEnd) if (t[i] == '=') return i; return -1 }
    private fun isKey(t: String, l: Line) = !isComment(t, l) && !isHeader(t, l) && eqAt(t, l) >= 0

    private fun trimBlanks(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isBlankChar(s[a])) a++
        while (b > a && isBlankChar(s[b - 1])) b--
        return s.substring(a, b)
    }

    private fun sameName(a: String, b: String): Boolean = a.uppercase() == b.uppercase()

    private fun headerName(t: String, l: Line): String {
        val open = firstNonBlank(t, l)
        var close = open + 1
        while (close < l.contentEnd && t[close] != ']') close++
        return trimBlanks(t.substring(open + 1, close))
    }

    private fun keyName(t: String, l: Line): String = trimBlanks(t.substring(l.start, eqAt(t, l)))

    /** The first section of that name: the index of its header and the index past its last line. */
    private fun section(t: String, ls: List<Line>, name: String): IntRange? {
        val want = trimBlanks(name)
        var h = -1
        for (i in ls.indices) {
            if (h < 0) {
                if (isHeader(t, ls[i]) && sameName(headerName(t, ls[i]), want)) h = i
            } else if (isHeader(t, ls[i])) {
                return h until i
            }
        }
        return if (h < 0) null else h until ls.size
    }

    private fun findKey(t: String, ls: List<Line>, sec: IntRange, key: String): Int {
        val want = trimBlanks(key)
        for (i in sec.first + 1..sec.last) if (isKey(t, ls[i]) && sameName(keyName(t, ls[i]), want)) return i
        return -1
    }

    /** `GetPrivateProfileString(section, key, "", buf, 1024, file)`: "" when there is no such key. */
    fun read(text: String?, section: String, key: String): String {
        if (text == null) return ""
        val ls = lines(text)
        val sec = section(text, ls, section) ?: return ""
        val k = findKey(text, ls, sec, key)
        if (k < 0) return ""
        val l = ls[k]
        var v = trimBlanks(text.substring(eqAt(text, l) + 1, l.contentEnd))
        if (v.length >= 2 && (v[0] == '"' || v[0] == '\'') && v[v.length - 1] == v[0]) v = v.substring(1, v.length - 1)
        return if (v.length > 1023) v.substring(0, 1023) else v
    }

    /** Where the file really ends for Windows: before the spaces and tabs at its very end. */
    private fun effEnd(t: String): Int {
        var e = t.length
        while (e > 0 && isBlankChar(t[e - 1])) e--
        return e
    }

    /**
     * What every write and delete does to the end of a file first, whether or not it then changes anything: a last
     * line with no LF loses its trailing blanks and gets CR LF. Blanks after a final LF are left alone (until
     * something is added at the end, which goes where they start).
     */
    private fun normalise(text: String): String {
        val e = effEnd(text)
        return if (e > 0 && text[e - 1] != '\n') text.substring(0, e) + "\r\n" else text
    }


    /**
     * The part of a changed file after [from] that Windows writes back: up to its last non-blank character, so the
     * blanks after a final LF go whenever something changes (and a line's indentation left behind by a delete stays).
     */
    private fun tail(t: String, from: Int): String { val e = effEnd(t); return if (from >= e) "" else t.substring(from, e) }

    /** Where a section's content ends for adding a key (after its last key line, or the whole file). */
    private fun insertPoint(t: String, ls: List<Line>, sec: IntRange): Int {
        val toEof = sec.last == ls.size - 1
        if (toEof && sec.last > sec.first && isBlank(t, ls[sec.last])) return effEnd(t)
        var p = ls[sec.first].end
        for (i in sec.first + 1..sec.last) if (isKey(t, ls[i])) p = ls[i].end
        return p
    }

    /** `WritePrivateProfileString(section, key, value, file)`: the new text. */
    fun write(text: String?, section: String, key: String, value: String): String {
        val t = normalise(text ?: "")
        val ls = lines(t)
        val sec = section(t, ls, section)
            ?: return t.substring(0, effEnd(t)) + "[" + section + "]\r\n" + key + "=" + value + "\r\n"
        val k = findKey(t, ls, sec, key)
        if (k >= 0) {
            val l = ls[k]
            val afterEq = eqAt(t, l) + 1
            var ve = l.contentEnd
            while (ve > afterEq && isBlankChar(t[ve - 1])) ve--
            return t.substring(0, afterEq) + value + tail(t, ve)
        }
        val p = insertPoint(t, ls, sec)
        return t.substring(0, p) + key + "=" + value + "\r\n" + tail(t, p)
    }

    /** `WritePrivateProfileString(section, key, null, file)`. */
    fun deleteKey(text: String?, section: String, key: String): String {
        val t = normalise(text ?: "")
        val ls = lines(t)
        val sec = section(t, ls, section) ?: return t
        val k = findKey(t, ls, sec, key)
        if (k < 0) return t
        val l = ls[k]
        return t.substring(0, firstNonBlank(t, l)) + tail(t, l.end)
    }

    /** `WritePrivateProfileString(section, null, null, file)`. */
    fun deleteSection(text: String?, section: String): String {
        val t = normalise(text ?: "")
        val ls = lines(t)
        val sec = section(t, ls, section) ?: return t
        val from = firstNonBlank(t, ls[sec.first])
        val toEof = sec.last == ls.size - 1
        val to = if (toEof && sec.last > sec.first && isBlank(t, ls[sec.last])) t.length else {
            var p = ls[sec.first].end
            for (i in sec.first + 1..sec.last) if (!isBlank(t, ls[i]) && !isComment(t, ls[i])) p = ls[i].end
            p
        }
        return t.substring(0, from) + tail(t, to)
    }
}
