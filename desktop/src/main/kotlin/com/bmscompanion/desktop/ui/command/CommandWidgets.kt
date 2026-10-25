package com.bmscompanion.desktop.ui.command

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud

// The Command section's desk furniture: the Weather generator's look (EditorWidgets: hairline rules, no cards), sized
// for a mouse — rows of 24-28 dp, 12-sp type. Editor pages use these so the section reads as one tool.

/** A row height for a mouse: one line of the panel. */
val CMD_ROW = 26.dp

/** A folding section of a side panel: a header line with a chevron, its fold kept in the prefs (`open_cmd_<key>`). */
@Composable
fun Fold(title: String, key: String, startOpen: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    var open by remember(key) { mutableStateOf(Repo.getInt("open_cmd_$key", if (startOpen) 1 else 0) == 1) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(CMD_ROW).clickable { open = !open; Repo.putInt("open_cmd_$key", if (open) 1 else 0) }.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "▾" else "▸", color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.width(14.dp))
            Text(title.uppercase(), color = Hud.TextDim, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp), content = content)
        Rule()
    }
}

/** A tick box on one line: [label], an optional team-colour [swatch], indented under a heading line. */
@Composable
fun CheckLine(label: String, checked: Boolean, swatch: Color? = null, indent: Boolean = false, enabled: Boolean = true, lead: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(22.dp).clickable(enabled = enabled) { onChange(!checked) }.padding(start = if (indent) 14.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(12.dp).clip(RoundedCornerShape(2.dp))
                .background(if (checked) Hud.Amber.copy(alpha = if (enabled) 0.9f else 0.4f) else Color.Transparent)
                .border(1.dp, if (checked) Hud.Amber else Hud.Outline, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Text("✓", color = Hud.Bg, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(7.dp))
        if (lead != null) lead()
        else if (swatch != null) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(swatch).border(1.dp, Hud.Outline, RoundedCornerShape(5.dp)))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (enabled) Hud.Text else Hud.TextFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small flat chip (a choice in a row of them); [tint] for a team's, [lead] before the words (a team's flag). */
@Composable
fun MiniChip(text: String, selected: Boolean, tint: Color? = null, enabled: Boolean = true, lead: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val ink = tint ?: Hud.Amber
    val box = Modifier.clip(RoundedCornerShape(3.dp))
        .background(if (selected) ink.copy(alpha = 0.2f) else Hud.Surface)
        .border(1.dp, if (selected) ink.copy(alpha = 0.9f) else Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 7.dp, vertical = 3.dp)
    val colour = when {
        !enabled -> Hud.TextFaint
        selected -> if (tint != null) Hud.Text else Hud.Amber
        else -> Hud.TextDim
    }
    if (lead == null) Text(text, box, color = colour, fontSize = 11.5.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    else Row(box, verticalAlignment = Alignment.CenterVertically) {
        lead()
        Text(text, color = colour, fontSize = 11.5.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Chips that wrap onto as many lines as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MiniWrap(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
fun SmallNote(text: String, colour: Color = Hud.TextFaint) =
    Text(text, color = colour, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp))

/** A desk button: text, compact, flat; [strong] for the one action a bar leads with; [tip] under the mouse. */
@Composable
fun DeskButton(text: String, enabled: Boolean = true, strong: Boolean = false, danger: Boolean = false, tip: String? = null, onClick: () -> Unit) {
    val ink = if (danger) Hud.Red else Hud.Amber
    val body: @Composable () -> Unit = {
        Text(
            text,
            Modifier.clip(RoundedCornerShape(4.dp))
                .background(if (strong && enabled) ink else Hud.Surface)
                .border(1.dp, if (enabled) (if (strong) ink else Hud.Outline) else Hud.Outline.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = when {
                !enabled -> Hud.TextFaint
                strong -> Hud.Bg
                danger -> Hud.Red
                else -> Hud.Text
            },
            fontSize = 12.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
    if (tip == null) body() else Tip(tip) { body() }
}

/** A tool of the map's strip: lit while it is the tool. */
@Composable
fun ToolChip(text: String, selected: Boolean, tip: String, enabled: Boolean = true, onClick: () -> Unit) = Tip(tip) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(3.dp))
            .background(if (selected) Hud.Amber.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (selected) Hud.Amber else Hud.Outline.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = if (!enabled) Hud.TextFaint else if (selected) Hud.Amber else Hud.TextDim,
        fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
    )
}

/** A tooltip after half a second under the mouse. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Text(
                text, Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(4.dp)).background(Hud.Surface2)
                    .border(1.dp, Hud.Outline, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                color = Hud.Text, fontSize = 11.5.sp,
            )
        },
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
    ) { content() }
}

/** One ruled line of facts: a label column and a value (an inspector's read-only row). */
@Composable
fun FactRow(label: String, value: String, mono: Boolean = false, ink: Color = Hud.Text, lead: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 22.dp).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = if (lead != null) Alignment.CenterVertically else Alignment.Top) {
        Text(label, color = Hud.TextDim, fontSize = 11.5.sp, modifier = Modifier.width(110.dp))
        lead?.invoke()
        Text(value, color = ink, fontSize = 12.sp, fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.weight(1f))
    }
    Rule(0.18f)
}

/**
 * A thin bar: toolbars and strips. One line while everything fits, and otherwise it wraps onto more lines ([WrapRow])
 * rather than squeezing what does not fit (a Row gave the last buttons no width, their words broke a letter a line and
 * the bar grew taller than the map under it).
 */
@Composable
fun Strip(background: Color = Hud.Surface.copy(alpha = 0.6f), content: @Composable RowScope.() -> Unit) {
    WrapRow(Modifier.fillMaxWidth().background(background).padding(horizontal = 8.dp, vertical = 4.dp), content = content)
}

/** The least room a weighted sentence in a [WrapRow] is given before it goes onto the next line. */
private val WEIGHTED_LEAST = 160.dp

/** `Modifier.weight` inside a [WrapRow]: the room left on its line, shared by weight ([fill] false: up to it). */
private class WrapWeight(val weight: Float, val fill: Boolean) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@WrapWeight
}

/** A [RowScope] for [WrapRow]'s content: `weight` takes the room left on the line; every item is centred on its line. */
private object WrapScope : RowScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = this.then(WrapWeight(weight, fill))
    override fun Modifier.align(alignment: Alignment.Vertical): Modifier = this
    override fun Modifier.alignBy(alignmentLine: HorizontalAlignmentLine): Modifier = this
    override fun Modifier.alignByBaseline(): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}

/**
 * A row that wraps: items in order, [gap] apart, a new line where the next would not fit (never squeezed below its own
 * width — an item wider than the whole line is cut to it), each line as tall as its tallest item with the others centred
 * on it. A `Modifier.weight` item (a spacer, a text that may be cut) takes the room its line has left, at least its own
 * least width, so what follows it goes onto the next line when that is all the room there is.
 */
@Composable
fun WrapRow(modifier: Modifier = Modifier, gap: Dp = 6.dp, lineGap: Dp = 4.dp, content: @Composable RowScope.() -> Unit) {
    Layout(content = { WrapScope.content() }, modifier = modifier) { ms, c ->
        val g = gap.roundToPx()
        val lg = lineGap.roundToPx()
        val bounded = c.hasBoundedWidth
        val maxW = c.maxWidth
        val n = ms.size
        val weight = FloatArray(n) { (ms[it].parentData as? WrapWeight)?.weight ?: 0f }
        val placed = arrayOfNulls<Placeable>(n)
        val least = IntArray(n)
        for (i in 0 until n) {
            // a weighted item wants its least width, and a sentence at least WEIGHTED_LEAST of its own (or it would be cut to a word)
            if (weight[i] > 0f) least[i] = maxOf(ms[i].minIntrinsicWidth(Constraints.Infinity), minOf(ms[i].maxIntrinsicWidth(Constraints.Infinity), WEIGHTED_LEAST.roundToPx()))
                .coerceAtMost(if (bounded) maxW else Int.MAX_VALUE)
            else { val p = ms[i].measure(Constraints(maxWidth = maxW, maxHeight = c.maxHeight)); placed[i] = p; least[i] = p.width }
        }
        // break into lines
        val lines = ArrayList<IntArray>()
        var cur = ArrayList<Int>()
        var used = 0
        for (i in 0 until n) {
            val need = if (cur.isEmpty()) least[i] else used + g + least[i]
            if (bounded && cur.isNotEmpty() && need > maxW) { lines += cur.toIntArray(); cur = ArrayList(); used = least[i] } else used = need
            cur += i
        }
        if (cur.isNotEmpty()) lines += cur.toIntArray()
        // a separator that would start or end a line is left out (it separates nothing there)
        for (k in lines.indices) {
            var l = lines[k].toList()
            while (l.size > 1 && ms[l.first()].parentData === WrapSeparator) l = l.drop(1)
            while (l.size > 1 && ms[l.last()].parentData === WrapSeparator) l = l.dropLast(1)
            lines[k] = l.toIntArray()
        }
        // the weighted items of each line share what is left of it
        for (line in lines) {
            val ws = line.filter { weight[it] > 0f }
            if (ws.isEmpty()) continue
            val fixed = line.filter { weight[it] == 0f }.sumOf { placed[it]!!.width } + g * (line.size - 1)
            val total = ws.sumOf { weight[it].toDouble() }.toFloat()
            var left = if (bounded) (maxW - fixed).coerceAtLeast(0) else ws.sumOf { least[it] }
            ws.forEachIndexed { k, i ->
                val share = if (k == ws.lastIndex) left else (left * (weight[i] / total)).toInt()
                val w = share.coerceAtLeast(least[i])
                left -= share
                val fill = (ms[i].parentData as? WrapWeight)?.fill != false
                placed[i] = ms[i].measure(Constraints(minWidth = if (fill) w else 0, maxWidth = w, maxHeight = c.maxHeight))
            }
        }
        val heights = lines.map { l -> l.maxOf { placed[it]!!.height } }
        val widths = lines.map { l -> l.sumOf { placed[it]!!.width } + g * (l.size - 1) }
        val widest = widths.maxOrNull() ?: 0
        val width = (if (bounded) widest.coerceAtMost(maxW) else widest).coerceAtLeast(c.minWidth)
        val height = (heights.sum() + lg * (lines.size - 1).coerceAtLeast(0)).coerceIn(c.minHeight, c.maxHeight)
        layout(width, height) {
            var y = 0
            lines.forEachIndexed { li, l ->
                var x = 0
                for (i in l) {
                    val p = placed[i]!!
                    p.placeRelative(x, y + (heights[li] - p.height) / 2)
                    x += p.width + g
                }
                y += heights[li] + lg
            }
        }
    }
}

/** A vertical hairline between things in a strip. */
@Composable
fun VRule() = Box(Modifier.then(WrapSeparator).width(1.dp).height(18.dp).background(Hud.Outline.copy(alpha = 0.5f)))

/** A [VRule] inside a [WrapRow]: left out where it would start or end a line. */
private object WrapSeparator : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@WrapSeparator
}
