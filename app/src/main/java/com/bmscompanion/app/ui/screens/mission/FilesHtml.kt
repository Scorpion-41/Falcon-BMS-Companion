package com.bmscompanion.app.ui.screens.mission

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * An HTML page of the BMS PC as the Files tab shows it: not a browser, but the page's reading matter laid out as
 * blocks the app draws itself (headings, paragraphs, lists, table rows, pictures, rules), on every device alike — an
 * Android WebView is not shared code, and a page served from the PC's own origin could run its scripts against the
 * PC's API. Scripts, styles and forms are dropped; bold, italics, code and links are kept. A link is handed to
 * [onLink] with its `href` as written (the tab resolves it against the page's folder); a picture names its `src` the
 * same way and is fetched from the PC.
 *
 * Pure and forgiving: whatever the markup, it returns blocks; nothing here throws on a malformed page.
 */
object FilesHtml {
    sealed interface Block
    data class Heading(val level: Int, val text: AnnotatedString) : Block
    data class Para(val text: AnnotatedString, val pre: Boolean = false, val quote: Boolean = false) : Block
    data class Item(val text: AnnotatedString, val marker: String, val depth: Int) : Block
    data class Row(val cells: List<AnnotatedString>, val header: Boolean) : Block
    data class Image(val src: String, val alt: String?) : Block
    data object Rule : Block

    /** The page's `<title>`, when it has one. */
    fun title(html: String): String? =
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
            ?.groupValues?.get(1)?.let { decode(it).trim().replace(Regex("\\s+"), " ") }?.takeIf { it.isNotEmpty() }

    private val DROP = Regex("<(script|style|noscript|template|head|svg|iframe|object)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    /** `<!DOCTYPE html>`, `<?xml …?>`, `<![CDATA[`: not text */
    private val DECLARATION = Regex("<[!?][^>]*>")
    private val TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>")
    private val BLOCKS = setOf(
        "p", "div", "section", "article", "header", "footer", "nav", "main", "aside", "center", "form", "fieldset",
        "dl", "dt", "dd", "figure", "figcaption", "address", "body", "html", "caption",
    )

    fun parse(html: String, linkInk: Color, codeInk: Color, onLink: (String) -> Unit): List<Block> {
        val out = ArrayList<Block>()
        val src = html.replace(COMMENT, "").replace(DECLARATION, "").replace(DROP, " ")
        var b = AnnotatedString.Builder()
        var pre = 0
        var quote = 0
        var heading = 0
        val lists = ArrayList<Pair<Boolean, Int>>()     // ordered, count so far
        var itemMarker: String? = null
        var row: MutableList<AnnotatedString>? = null
        var rowHeader = false
        var cell: AnnotatedString.Builder? = null
        val styles = ArrayList<String>()                 // open inline tags, innermost last, for their pops
        var pushed = 0

        fun target() = cell ?: b
        fun lastChar(): Char? = target().toAnnotatedString().text.lastOrNull()
        fun text(raw: String) {
            if (raw.isEmpty()) return
            val t = decode(if (pre > 0) raw else raw.replace(Regex("\\s+"), " "))
            val s = if (pre == 0 && (lastChar() == null || lastChar() == ' ' || lastChar() == '\n')) t.trimStart() else t
            if (s.isNotEmpty()) target().append(s)
        }
        fun flush() {
            val built = b.toAnnotatedString()
            // an inline tag still open ends with its block (its closing tag later pops nothing)
            repeat(pushed) { runCatching { b.pop() } }
            val txt = if (pre > 0) built else trimmed(built)
            if (txt.text.isNotBlank()) {
                out += when {
                    heading > 0 -> Heading(heading, txt)
                    itemMarker != null -> Item(txt, itemMarker!!, (lists.size - 1).coerceAtLeast(0))
                    else -> Para(txt, pre = pre > 0, quote = quote > 0)
                }
            }
            b = AnnotatedString.Builder()
            pushed = 0
        }
        fun endRow() {
            cell?.let { c -> row?.add(trimmed(c.toAnnotatedString())) }
            cell = null
            row?.takeIf { r -> r.any { it.text.isNotBlank() } }?.let { out += Row(it.toList(), rowHeader) }
            row = null
            rowHeader = false
        }

        var at = 0
        for (m in TAG.findAll(src)) {
            text(src.substring(at, m.range.first))
            at = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            when (name) {
                "br" -> if (cell != null) cell!!.append(' ') else { if (pre > 0) b.append('\n') else flush() }
                "hr" -> { flush(); out += Rule }
                "h1", "h2", "h3", "h4", "h5", "h6" -> { flush(); heading = if (closing) 0 else name[1] - '0' }
                "pre" -> { flush(); pre = if (closing) (pre - 1).coerceAtLeast(0) else pre + 1 }
                "blockquote" -> { flush(); quote = if (closing) (quote - 1).coerceAtLeast(0) else quote + 1 }
                "ul", "ol" -> {
                    flush(); itemMarker = null
                    if (closing) { if (lists.isNotEmpty()) lists.removeAt(lists.size - 1) } else lists += (name == "ol") to 0
                }
                "li" -> {
                    flush()
                    if (closing) itemMarker = null
                    else {
                        val k = lists.size - 1
                        itemMarker = if (k >= 0 && lists[k].first) {
                            lists[k] = true to lists[k].second + 1
                            "${lists[k].second}."
                        } else "•"
                    }
                }
                "table", "tbody", "thead", "tfoot" -> { if (row != null) endRow(); flush() }
                "tr" -> { flush(); if (row != null) endRow(); if (!closing) row = ArrayList() }
                "td", "th" -> {
                    if (row == null) { flush(); row = ArrayList() }
                    cell?.let { c -> row!!.add(trimmed(c.toAnnotatedString())) }
                    cell = if (closing) null else AnnotatedString.Builder()
                    if (name == "th" && !closing) rowHeader = true
                }
                "img" -> if (!closing) {
                    attr(attrs, "src")?.let { s ->
                        flush()
                        out += Image(s, attr(attrs, "alt")?.let(::decode)?.takeIf { it.isNotBlank() })
                    }
                }
                "a", "b", "strong", "i", "em", "code", "tt", "kbd", "u" -> {
                    val into = target()
                    if (!closing) {
                        val style = when (name) {
                            "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
                            "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
                            "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
                            "a" -> SpanStyle(color = linkInk, textDecoration = TextDecoration.Underline)
                            else -> SpanStyle(fontFamily = FontFamily.Monospace, color = codeInk)
                        }
                        val href = if (name == "a") attr(attrs, "href")?.let(::decode)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("javascript:", true) } else null
                        if (href != null) into.pushLink(LinkAnnotation.Clickable(href, TextLinkStyles(style)) { onLink(href) })
                        else into.pushStyle(style)
                        styles += name
                        if (into === b) pushed++
                    } else if (styles.lastOrNull() == name) {
                        styles.removeAt(styles.size - 1)
                        runCatching { into.pop() }
                        if (into === b && pushed > 0) pushed--
                    }
                }
                else -> if (name in BLOCKS) { if (cell == null) flush() }
            }
        }
        text(src.substring(at))
        if (row != null) endRow()
        flush()
        return out
    }

    private fun trimmed(s: AnnotatedString): AnnotatedString {
        val start = s.text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return AnnotatedString("")
        val end = s.text.indexOfLast { !it.isWhitespace() } + 1
        return s.subSequence(start, end)
    }

    /** An attribute's value (`src="a.png"`, `src='a.png'`, `src=a.png`), or null. */
    fun attr(attrs: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(attrs)?.let { m ->
            m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
        }

    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "deg" to "°",
        "copy" to "©", "reg" to "®", "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“", "bull" to "•", "middot" to "·", "times" to "×", "plusmn" to "±", "micro" to "µ",
    )

    fun decode(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let(::codePoint)
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[e.lowercase()]
        } ?: m.value
    }

    private fun codePoint(c: Int): String? = when {
        c in 1..0xFFFF -> c.toChar().toString()
        c in 0x10000..0x10FFFF -> { val v = c - 0x10000; charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString() }
        else -> null
    }
}
