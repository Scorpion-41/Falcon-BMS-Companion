package com.bmscompanion.app.data.wdp

/**
 * The Briefing page's prose laid out on its lines (1.4.0). WDP's Briefing page gives each section a stack of one-line
 * boxes — Situation 5, Intel 9, Mission Objective 10, Notes 12, every one 446 px of Arial 8.25 — and the page's
 * renderer sets a value too wide for its box smaller (down to [FIT_MIN][com.bmscompanion.app.ui.screens.wdp.FIT_MIN]),
 * so a long threat list or package row came out in tiny type between full-size lines. Here a paragraph is word-wrapped
 * onto the section's next boxes instead, so every line keeps the page's one text size.
 *
 * The width is measured in Arial's own advance widths ([ARIAL]), the face WDP's boxes were sized for, against
 * [LINE_EM]: the box less the room the renderer keeps round its text (16 px of a phone's 446) and a few per cent for the
 * face a device really draws with (Segoe UI and Roboto set prose no wider than Arial). Pure and the same on every
 * device, so a phone, the PC and the kneeboard print break the lines at the same words. As the pilot types, [reflow]
 * lays the paragraph being typed out again, as a word processor does (D100 in `docs/WDP-PORT.md`).
 */
object BriefingLines {
    /** Arial's advance widths, thousandths of an em, for the characters from space (32) to tilde (126). */
    private val ARIAL = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, // space ! " # $ % & ' ( ) * + , - . /
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, // 0-9 : ; < = > ?
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, // @ A-O
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, // P-Z [ \ ] ^ _
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, // ` a-o
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,      // p-z { | } ~
    )

    /** A line's width, in ems of Arial: 446 px of box at 11 px an em, less 16 px of padding and 4 % to spare. */
    const val LINE_EM = 37.5f

    /** What a line that carries on from the one above starts with, in a list (Intel, Mission Objective). */
    const val HANG = "    "

    /** [s]'s width in ems of Arial; a character Arial's table here does not cover counts as a figure, "…" as an em. */
    fun em(s: String): Float {
        var w = 0
        for (c in s) w += when {
            c.code in 32..126 -> ARIAL[c.code - 32]
            c == '…' -> 1000
            c == '\t' -> 278
            else -> 556
        }
        return w / 1000f
    }

    /** Whether [line] fits a box of the page at its own size. */
    fun fits(line: String, maxEm: Float = LINE_EM): Boolean = em(line) <= maxEm

    /**
     * [text] word-wrapped into lines no wider than [maxEm], each line after the first starting with [indent]. Spaces
     * inside a line are kept as they are (a package row's columns); a line breaks only at a space, and a word longer
     * than a whole line is broken where the line ends. A blank [text] is one empty line.
     */
    fun wrap(text: String, maxEm: Float = LINE_EM, indent: String = ""): List<String> {
        val src = text.trimEnd()
        if (src.isBlank()) return listOf("")
        val out = ArrayList<String>()
        var rest = src
        var lead = ""
        while (rest.isNotEmpty()) {
            val room = maxEm - em(lead)
            if (em(rest) <= room) { out += lead + rest; break }
            // the longest run of whole words that fits
            var cut = -1
            var i = 0
            while (i < rest.length) {
                if (rest[i] == ' ' && i > 0 && rest[i - 1] != ' ') {
                    if (em(rest.substring(0, i)) <= room) cut = i else break
                }
                i++
            }
            if (cut <= 0) {
                // one word wider than the line: as much of it as fits
                var n = 1
                while (n < rest.length && em(rest.substring(0, n + 1)) <= room) n++
                cut = n
            }
            out += lead + rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart()
            lead = indent
        }
        return out
    }

    /**
     * The section's lines, and the whole text when it did not all fit (for the last line's tip), else null; [marks]
     * says which lines carry on the line above ([CONT_SPACE]), for [reflow].
     */
    class Flow(val lines: List<String>, val cut: String?, val marks: IntArray = IntArray(lines.size))

    /**
     * [paragraphs] wrapped ([wrap]) one after the other onto [rows] lines, blank paragraphs kept as blank lines. When
     * they need more lines than there are, trailing blank lines go first; then the last line ends in "…" and [Flow.cut]
     * holds the whole text, one paragraph a line.
     */
    fun flow(paragraphs: List<String>, rows: Int, indent: String = "", maxEm: Float = LINE_EM): Flow {
        val wrapped = paragraphs.map { wrap(it, maxEm, indent) }
        val lines = wrapped.flatten().dropLastWhile { it.isBlank() }
        val marks = IntArray(rows)
        var at = 0
        for (w in wrapped) for (i in w.indices) { if (i > 0 && at < rows) marks[at] = CONT_SPACE; at++ }
        if (lines.size <= rows) return Flow(lines + List(rows - lines.size) { "" }, null, marks)
        val kept = lines.take(rows).toMutableList()
        kept[rows - 1] = ellipsis(kept[rows - 1], maxEm)
        return Flow(kept, paragraphs.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"), marks)
    }

    // ------------------------------------------------------------------------------------- typing, as a word processor

    /** A line of a section that starts a paragraph (marks of [reflow]). */
    const val NEW = 0
    /** A line that carries on the line above, joined to it as it stands (a break [reflow] made: its spaces stay above). */
    const val CONT = 1
    /** A line that carries on the line above, joined to it with a space (a break [wrap] made, which dropped the spaces). */
    const val CONT_SPACE = 2

    /**
     * [text] broken into the pieces of its lines without losing a character (unlike [wrap]): a line breaks after a
     * run of spaces, which stay at the end of the line above, not measured — as a word processor lets them hang — so
     * the pieces joined are [text] again, and a space typed at the end of a full line is still there when the next
     * word comes. The first line has [maxEm], the others [maxEm] less [indent]. An empty [text] is one empty piece.
     */
    fun pieces(text: String, maxEm: Float = LINE_EM, indent: String = ""): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = ArrayList<String>()
        var pos = 0
        while (true) {
            val room = maxEm - if (out.isEmpty()) 0f else em(indent)
            val rest = text.substring(pos)
            if (rest.isBlank() || em(rest.trimEnd()) <= room) { out += rest; break }
            // the last word start whose line before it fits
            var cut = -1
            for (b in 1 until rest.length) {
                if (rest[b - 1] != ' ' || rest[b] == ' ') continue
                val head = rest.substring(0, b)
                if (head.isBlank()) continue
                if (em(head.trimEnd()) <= room) cut = b else break
            }
            if (cut <= 0) {
                // one word wider than the line: as much of it as fits
                var n = 1
                while (n < rest.length && em(rest.substring(0, n + 1)) <= room) n++
                cut = n
            }
            out += rest.substring(0, cut)
            pos += cut
        }
        return out
    }

    /**
     * A section after [reflow]: its lines, each line's mark ([NEW], [CONT], [CONT_SPACE]), and the line and column the
     * caret is on (column in the line as shown, its indent counted), or -1 when no caret was given.
     */
    class Reflow(val lines: List<String>, val marks: IntArray, val caretLine: Int, val caretCol: Int)

    /**
     * A section of lines ([rows], with their [marks]) after line [k] was edited, laid out again as a word processor
     * would: the paragraph [k] belongs to — the line where it starts and the lines that carry it on (a mark, or in a
     * list section ([indent] not empty) a line starting with the indent under a line with text) — is joined, the
     * continuation lines' indents taken off, and broken again ([pieces]); the lines below move down or up by the
     * difference. Moving down takes only the empty lines at the section's foot: with too few, nothing changes and null
     * is returned (the line stays as typed, set smaller to fit, and nothing is pushed off the end).
     *
     * [caret] is the caret's place in line [k] (null: none, as when a box is left). [join] edits the paragraph first:
     * -1 deletes the character before the start of line [k] (Backspace at the start of a continuation line: the space
     * the line broke at, or the last character above it), +1 the character after its end (Delete at the end of a line
     * whose next line carries it on); a join where line [k] does not carry on, or is not carried on, returns null —
     * Windows' Backspace at 0 and Delete at the end do nothing either.
     */
    fun reflow(rows: List<String>, marks: IntArray, k: Int, caret: Int?, indent: String = "", maxEm: Float = LINE_EM, join: Int = 0): Reflow? {
        val n = rows.size
        if (k !in 0 until n) return null
        fun mark(j: Int) = marks.getOrElse(j) { NEW }
        fun cont(j: Int): Boolean {
            if (j <= 0 || j >= n || rows[j - 1].isBlank()) return false
            // the line edited may have just been emptied: as a continuation it then goes, as a word processor's would
            if (mark(j) != NEW && (rows[j].isNotBlank() || j == k)) return true
            return indent.isNotEmpty() && rows[j].startsWith(indent) && rows[j].isNotBlank()
        }
        var s = k
        while (cont(s)) s--
        var e = k
        while (cont(e + 1)) e++
        if (join < 0 && s == k) return null
        if (join > 0 && e == k) return null
        // the paragraph's text, and the caret's place in it
        val sb = StringBuilder()
        var abs = -1
        var lineStart = 0
        var lineEnd = 0
        for (j in s..e) {
            var t = rows[j]
            var strip = 0
            if (j > s) {
                if (indent.isNotEmpty()) { while (strip < indent.length && strip < t.length && t[strip] == ' ') strip++; t = t.substring(strip) }
                val spaced = mark(j) != CONT
                if (spaced && sb.isNotEmpty() && !sb.last().isWhitespace() && t.isNotEmpty() && !t[0].isWhitespace()) sb.append(' ')
            }
            if (j == k) {
                lineStart = sb.length
                lineEnd = sb.length + t.length
                if (caret != null) abs = sb.length + (caret - strip).coerceIn(0, t.length)
            }
            sb.append(t)
        }
        if (join < 0) {
            if (lineStart <= 0) return null
            sb.deleteAt(lineStart - 1)
            abs = lineStart - 1
        } else if (join > 0) {
            if (lineEnd >= sb.length) return null
            sb.deleteAt(lineEnd)
            abs = lineEnd
        }
        val text = sb.toString()
        val ps = pieces(text, maxEm, indent)
        val made = ps.mapIndexed { i, p -> if (i == 0) p else indent + p }
        val old = e - s + 1
        val below = rows.subList(e + 1, n)
        val foot = below.size - below.indexOfLast { it.isNotBlank() } - 1
        if (made.size - old > foot) return null
        val keep = below.size - maxOf(0, made.size - old)
        val lines = rows.subList(0, s) + made + below.take(keep)
        val newMarks = IntArray(n)
        for (j in 0 until s) newMarks[j] = mark(j)
        for (i in made.indices) newMarks[s + i] = if (i == 0) NEW else CONT
        for (j in 0 until keep) newMarks[s + made.size + j] = mark(e + 1 + j)
        val padded = lines + List(n - lines.size) { "" }
        // an empty line carries nothing on
        for (j in 0 until n) if (padded[j].isEmpty()) newMarks[j] = NEW
        // the caret: in the piece holding it, the earlier piece where it sits on a break
        var cl = -1
        var cc = -1
        if (abs >= 0) {
            var at = 0
            for ((i, p) in ps.withIndex()) {
                if (abs <= at + p.length || i == ps.lastIndex) {
                    cl = s + i
                    cc = (abs - at).coerceIn(0, p.length) + if (i == 0) 0 else indent.length
                    break
                }
                at += p.length
            }
        }
        return Reflow(padded, newMarks, cl, cc)
    }

    /** [line] ending in "…" and still no wider than [maxEm], a word at a time shorter where it must be. */
    private fun ellipsis(line: String, maxEm: Float): String {
        var s = line.trimEnd()
        while (s.isNotEmpty() && em("$s …") > maxEm) {
            val sp = s.trimEnd().lastIndexOf(' ')
            s = if (sp > 0) s.substring(0, sp).trimEnd() else s.dropLast(1)
        }
        return "$s …"
    }
}
