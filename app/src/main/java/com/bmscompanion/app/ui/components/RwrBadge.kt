package com.bmscompanion.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Threat
import com.bmscompanion.app.ui.theme.Mono
import kotlinx.coroutines.delay

/**
 * The mark the RWR draws with an emitter's code, as TO 1F-16CMAM-34-1-1 BMS 2.7.3.4.2 shows the ALR-93's symbols:
 * a hat over the code for an airplane, an arrow at its lower left for a SAM, a gun on a box under it for AAA, a stroke
 * from its top for a search radar. The ALR-56M marks only the airplane, with the same hat.
 */
enum class RwrMark { NONE, AIR, SAM, AAA, SEARCH }

object RwrMarks {
    /** A threat's mark on the ALR-56M ([alr93] false) or the ALR-93. */
    fun forThreat(t: Threat, alr93: Boolean): RwrMark = when (t.category) {
        "AIRCRAFT" -> RwrMark.AIR
        "SAM", "SAM_RADAR" -> if (alr93) RwrMark.SAM else RwrMark.NONE
        "AAA" -> if (alr93) RwrMark.AAA else RwrMark.NONE
        "SEARCH_RADAR" -> if (alr93) RwrMark.SEARCH else RwrMark.NONE
        else -> RwrMark.NONE
    }

    /** An RWR emitter's mark from its type in `curated/rwr.json` ("AIRCRAFT", "SAM", "RADAR (FCR)", "AAA" …). */
    fun forEmitter(type: String?, alr93: Boolean): RwrMark {
        val t = type?.uppercase() ?: return RwrMark.NONE
        return when {
            t == "AIRCRAFT" -> RwrMark.AIR
            !alr93 -> RwrMark.NONE
            t.startsWith("SAM") || t == "RADAR (FCR)" -> RwrMark.SAM
            t == "AAA" || t == "RADAR (AAA RADAR)" -> RwrMark.AAA
            t == "RADAR (EWR)" -> RwrMark.SEARCH
            else -> RwrMark.NONE
        }
    }
}

/**
 * What an RWR symbol in the data shows on the scope, part by part: "A/S (alternating)" is A and S flashing in turn,
 * "15/M (alternating)" 15 and M, "I then WO" I then WO; anything else is itself.
 */
fun rwrParts(symbol: String): List<String> {
    val s = symbol.substringBefore(" (").trim()
    val parts = when {
        " then " in s -> s.split(" then ")
        '/' in s && s.split('/').all { it.isNotBlank() && it.trim().length <= 4 } -> s.split('/')
        else -> listOf(s)
    }
    return parts.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("–") }
}

/** The part of an alternating symbol on show now: the scope flashes them about twice a second. */
@Composable
private fun alternating(parts: List<String>): String {
    if (parts.size < 2) return parts.first()
    val i by produceState(0, parts) { while (true) { delay(650); value = (value + 1) % parts.size } }
    return parts[i % parts.size]
}

/**
 * An RWR symbol drawn the way the scope draws it: the code in the scope's ink on its dark glass, with its [mark]
 * ([RwrMark]), an alternating symbol flashing between its parts. Green for the ALR-56M, cyan for the ALR-93 (the
 * callers' choice).
 */
@Composable
fun RwrBadge(symbol: String?, size: Dp = 40.dp, color: Color = com.bmscompanion.app.ui.theme.Hud.Green, mark: RwrMark = RwrMark.NONE) {
    if (symbol.isNullOrBlank()) return
    val text = alternating(remember(symbol) { rwrParts(symbol) })
    RwrGlass(size, color) { tm -> drawCode(tm, text, color, mark) }
}

/** The dark round glass every RWR symbol is drawn on, with its rim. */
@Composable
private fun RwrGlass(size: Dp, color: Color, draw: DrawScope.(androidx.compose.ui.text.TextMeasurer) -> Unit) {
    val tm = rememberTextMeasurer()
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(Color(0xFF06140C), r, center)
        drawCircle(color.copy(alpha = 0.7f), r - 1.5.dp.toPx() / 2, center, style = Stroke(1.5.dp.toPx()))
        draw(tm)
    }
}

private fun DrawScope.codeStyle(color: Color, px: Float) = TextStyle(
    color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = (px / density / fontScale).sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/**
 * The code in the middle of the glass, and its mark round it. With a mark the code is set a little smaller and moved
 * away from it, so the two fit the circle together.
 */
private fun DrawScope.drawCode(tm: androidx.compose.ui.text.TextMeasurer, code: String, color: Color, mark: RwrMark, scaleBy: Float = 1f) {
    val d = this.size.minDimension
    val r = d / 2
    var fs = when { code.length <= 2 -> 0.46f; code.length <= 4 -> 0.30f; else -> 0.22f } * d * scaleBy
    if (mark != RwrMark.NONE) fs *= 0.82f
    var layout = tm.measure(code, codeStyle(color, fs), maxLines = 1)
    val maxW = r * (if (mark == RwrMark.SAM) 1.25f else 1.55f)
    if (layout.size.width > maxW) { fs *= maxW / layout.size.width; layout = tm.measure(code, codeStyle(color, fs), maxLines = 1) }
    val cap = fs * 0.72f
    // where the code sits: down a little under a hat or a search stroke, up over the AAA box, right of a SAM arrow
    val dy = when (mark) { RwrMark.AIR, RwrMark.SEARCH -> fs * 0.16f; RwrMark.AAA -> -fs * 0.2f; else -> 0f }
    val dx = if (mark == RwrMark.SAM) fs * 0.2f else 0f
    val baseline = center.y + cap / 2 + dy
    val left = center.x - layout.size.width / 2f + dx
    drawText(layout, topLeft = Offset(left, baseline - layout.firstBaseline))
    val top = baseline - cap
    val w = (fs * 0.09f).coerceAtLeast(1.2f)
    val ink = color
    when (mark) {
        RwrMark.AIR -> {
            // the hat: a chevron over the code, a little wider than it
            val half = maxOf(layout.size.width * 0.5f, fs * 0.42f)
            val y0 = top - fs * 0.16f
            val path = Path().apply { moveTo(center.x + dx - half, y0); lineTo(center.x + dx, y0 - fs * 0.34f); lineTo(center.x + dx + half, y0) }
            drawPath(path, ink, style = Stroke(w, cap = StrokeCap.Round))
        }
        RwrMark.SAM -> {
            // an arrow at the code's lower left, pointing up and right
            val a = fs * 0.55f
            val tip = Offset(left - fs * 0.06f, baseline - cap * 0.55f)
            val tail = Offset(tip.x - a * 0.75f, tip.y + a * 0.75f)
            drawLine(ink, tail, tip, w, StrokeCap.Round)
            val h = a * 0.32f
            drawLine(ink, tip, Offset(tip.x - h, tip.y), w, StrokeCap.Round)
            drawLine(ink, tip, Offset(tip.x, tip.y + h), w, StrokeCap.Round)
        }
        RwrMark.AAA -> {
            // a gun on its box under the code: the box, and the barrel rising from its left corner
            val bw = maxOf(layout.size.width * 0.9f, fs * 0.8f)
            val bh = fs * 0.24f
            val bx = center.x - bw / 2
            val by = baseline + fs * 0.2f
            drawRect(ink, Offset(bx, by), Size(bw, bh), style = Stroke(w))
            drawLine(ink, Offset(bx, by), Offset(bx + bw * 0.75f, by - fs * 0.16f), w, StrokeCap.Round)
        }
        RwrMark.SEARCH -> {
            // a stroke rising from the top of the code, up and to the right
            val x0 = center.x + dx + layout.size.width * 0.12f
            drawLine(ink, Offset(x0, top - fs * 0.04f), Offset(x0 + fs * 0.32f, top - fs * 0.42f), w, StrokeCap.Round)
        }
        RwrMark.NONE -> Unit
    }
}

/**
 * One line of the RWR symbols page drawn as the scope shows it, rather than its description set in a circle: a
 * diamond, a box or an octagon round a code, the flashing launch circle, the new guy pulsing, the alternating codes,
 * the scope-centre letters, the ship of the ALR-69's SEA mode, the hat. A symbol the page does not know is its text.
 */
@Composable
fun RwrSymbolGlyph(symbol: String, size: Dp = 40.dp, color: Color = com.bmscompanion.app.ui.theme.Hud.Green) {
    val s = symbol.lowercase()
    val tick by produceState(0) { while (true) { delay(400); value++ } }
    val on = tick % 2 == 0
    val parts = remember(symbol) { rwrParts(symbol.replace(" / ", "/")) }
    // an ALR-93 symbol in that RWR's cyan, as its badges are everywhere else
    val color = if ("alr-93" in s) com.bmscompanion.app.ui.theme.Hud.Cyan else color
    RwrGlass(size, color) { tm ->
        val d = this.size.minDimension
        val w = (d * 0.035f).coerceAtLeast(1.2f)
        fun ring(path: Path) = drawPath(path, color, style = Stroke(w))
        when {
            s.startsWith("diamond") -> {
                drawCode(tm, "2", color, RwrMark.NONE, 0.8f)
                val k = d * 0.3f
                ring(Path().apply { moveTo(center.x, center.y - k); lineTo(center.x + k, center.y); lineTo(center.x, center.y + k); lineTo(center.x - k, center.y); close() })
            }
            s.startsWith("box around") || s.startsWith("square") -> {
                drawCode(tm, "2", color, RwrMark.NONE, 0.8f)
                val k = d * 0.22f
                drawRect(color, Offset(center.x - k, center.y - k), Size(2 * k, 2 * k), style = Stroke(w))
            }
            s.startsWith("octagon") -> {
                drawCode(tm, "6", color, RwrMark.NONE, 0.8f)
                val k = d * 0.24f
                val c = k * 0.42f
                ring(Path().apply {
                    moveTo(center.x - k + c, center.y - k); lineTo(center.x + k - c, center.y - k); lineTo(center.x + k, center.y - k + c)
                    lineTo(center.x + k, center.y + k - c); lineTo(center.x + k - c, center.y + k); lineTo(center.x - k + c, center.y + k)
                    lineTo(center.x - k, center.y + k - c); lineTo(center.x - k, center.y - k + c); close()
                })
            }
            s.startsWith("flashing circle") -> {
                drawCode(tm, "6", color, RwrMark.NONE, 0.8f)
                if (on) drawCircle(color, d * 0.28f, center, style = Stroke(w))
            }
            s.startsWith("pulsing") -> drawCode(tm, "8", color, RwrMark.NONE, if (on) 0.75f else 1.1f)
            s.startsWith("hat") -> drawCode(tm, "29", color, RwrMark.AIR)
            s.startsWith("sam arrow") -> drawCode(tm, "6", color, RwrMark.SAM)
            s.startsWith("aaa box") -> drawCode(tm, "K", color, RwrMark.AAA)
            s.startsWith("search radar stroke") -> drawCode(tm, "1", color, RwrMark.SEARCH)
            s.startsWith("ship icon") -> {
                // the ALR-69's SEA mode mark: a hull under a mast
                val hw = d * 0.26f
                val y = center.y + d * 0.06f
                ring(Path().apply { moveTo(center.x - hw, y); lineTo(center.x + hw, y); lineTo(center.x + hw * 0.7f, y + d * 0.12f); lineTo(center.x - hw * 0.7f, y + d * 0.12f); close() })
                drawLine(color, Offset(center.x, y), Offset(center.x, y - d * 0.2f), w)
            }
            s.startsWith("noise bars") -> {
                for (i in 0 until 4) {
                    val a = i * kotlin.math.PI / 2
                    val o = Offset(center.x + (d * 0.3f * kotlin.math.cos(a)).toFloat(), center.y + (d * 0.3f * kotlin.math.sin(a)).toFloat())
                    val v = Offset((d * 0.08f * kotlin.math.sin(a)).toFloat(), (-d * 0.08f * kotlin.math.cos(a)).toFloat())
                    drawLine(color, o - v, o + v, w)
                }
            }
            s.startsWith("hud lines") -> {
                drawLine(color, Offset(center.x, center.y - d * 0.3f), Offset(center.x, center.y - d * 0.08f), w)
                drawLine(color, Offset(center.x + d * 0.12f, center.y), Offset(center.x + d * 0.28f, center.y), w)
            }
            s.startsWith("inner vs outer") -> {
                drawCircle(color.copy(alpha = 0.5f), d * 0.16f, center, style = Stroke(w * 0.7f))
                drawCircle(color.copy(alpha = 0.5f), d * 0.32f, center, style = Stroke(w * 0.7f))
                drawCircle(color, d * 0.04f, Offset(center.x + d * 0.11f, center.y - d * 0.11f))
                drawCircle(color, d * 0.04f, Offset(center.x - d * 0.22f, center.y + d * 0.22f))
            }
            s.startsWith("\"t\" suffix") -> drawCode(tm, "6T", color, RwrMark.NONE)
            else -> drawCode(tm, parts[tick % parts.size], color, RwrMark.NONE)
        }
    }
}

/**
 * The threat a system named in the RWR and HARM tables is (curated/rwr.json, curated/harm.json → the Threats page's
 * entry): by its whole name or an alias; else by the name before its brackets, the bracket picking between twins
 * ("Big Bird (64N6E)"); else by its designation ("KS-19 100 mm"); else a name beginning with it, a system before its
 * radar ("Nike" is Nike Hercules); else every word of it among a threat's names ("M6 BSFV-AD"). Null when the
 * Threats page has no entry (Chaparral) or the row is no system at all (SEARCH, UNKNOWN).
 */
object RwrLinks {
    private val NON = Regex("[^a-z0-9]")
    private fun n(s: String?) = (s ?: "").lowercase().replace(NON, "")
    private fun keys(t: Threat) = (listOf(t.name) + t.aliases).map(::n)
    private val DESIGNATION = Regex("^[A-Za-z]+-?\\d+[A-Za-z]?(-\\d+)?")

    fun threatFor(system: String?, threats: List<Threat>): Threat? {
        val k = n(system)
        if (system == null || k.isEmpty()) return null
        threats.firstOrNull { k in keys(it) }?.let { return it }
        val head = system.substringBefore("(").trim()
        val inner = system.substringAfter("(", "").substringBefore(")")
        val hk = n(head)
        val innerWords = inner.lowercase().split(NON).filter { it.length >= 3 }
        val same = threats.filter { hk in keys(it) }
        if (same.isNotEmpty()) {
            val ik = n(inner)
            return same.firstOrNull { t -> ik.isNotEmpty() && keys(t).any { it.contains(ik) || ik.contains(it) } }
                ?: same.firstOrNull { t -> innerWords.any { w -> keys(t).any { it.contains(w) } } }
                ?: same.first()
        }
        DESIGNATION.find(head)?.let { m ->
            val d = n(m.value)
            threats.filter { t -> keys(t).any { it.startsWith(d) } }.minByOrNull { it.name.length }?.let { return it }
        }
        if (hk.length >= 4) {
            threats.filter { t -> keys(t).any { it.startsWith(hk) } }
                .minWithOrNull(compareBy<Threat>({ if (n(it.name).startsWith(hk)) 0 else 1 }, { it.name.length }))?.let { return it }
        }
        val words = head.lowercase().split(NON).filter { it.length >= 2 }
        if (words.isNotEmpty()) threats.firstOrNull { t -> val all = keys(t).joinToString("|"); words.all { all.contains(it) } }?.let { return it }
        return null
    }
}
