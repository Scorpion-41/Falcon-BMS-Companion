package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.command.CommandEdit
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.MiniChip
import com.bmscompanion.desktop.ui.command.MiniWrap
import com.bmscompanion.desktop.ui.command.Tip

// The objective, team and campaign editors' rows: a label column and a value edited in place — typed and taken on
// Enter or when the field is left, picked from a short menu, or ticked. Desk furniture of the Command section (no
// cards: ruled rows of 24 dp, 12-sp type). Every change is one undoable edit run through [CmdContext.run].

internal val ED_LABEL = 118.dp

/** Runs [edit] (null: nothing to do) and says so. */
internal fun CmdContext.edit(edit: CommandEdit?) { if (edit != null) run(edit) }

/** A ruled row: [label] then the value. */
@Composable
internal fun EdRow(label: String, tip: String? = null, labelWidth: Dp = ED_LABEL, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 25.dp).padding(horizontal = 6.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        val l: @Composable () -> Unit = { Text(label, color = Hud.TextDim, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(labelWidth)) }
        if (tip != null) Tip(tip) { l() } else l()
        content()
    }
    Rule(0.15f)
}

/** A heading inside a panel: small capitals, then a rule. */
@Composable
internal fun EdHeading(text: String, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = Hud.TextDim, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
    Rule(0.4f)
}

/** A field typed in: shown as [value], taken by [onCommit] on Enter or when it loses the keyboard (Escape puts it back). */
@Composable
internal fun EdField(
    value: String, enabled: Boolean = true, mono: Boolean = false, width: Dp? = null, align: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier, onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    fun commit() { if (text != value) onCommit(text) }
    val m = (if (width != null) modifier.width(width) else modifier)
    BasicTextField(
        text, { text = it }, singleLine = true, enabled = enabled,
        textStyle = TextStyle(color = if (enabled) Hud.Text else Hud.TextFaint, fontSize = 12.sp, fontFamily = if (mono) FontFamily.Monospace else null, textAlign = align),
        cursorBrush = SolidColor(Hud.Amber),
        modifier = m.clip(RoundedCornerShape(2.dp))
            .background(if (focused) Hud.Bg else Color.Transparent)
            .border(1.dp, if (focused) Hud.Amber.copy(alpha = 0.8f) else if (enabled) Hud.Outline.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(2.dp))
            .padding(horizontal = 5.dp, vertical = 3.dp)
            .onFocusChanged { f -> if (focused && !f.isFocused) commit(); focused = f.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) {
                    Key.Enter, Key.NumPadEnter -> { commit(); true }
                    Key.Escape -> { text = value; true }
                    else -> false
                }
            },
    )
}

/** A text row. */
@Composable
internal fun EdText(label: String, value: String, enabled: Boolean = true, tip: String? = null, mono: Boolean = false, onCommit: (String) -> Unit) =
    EdRow(label, tip) { EdField(value, enabled, mono, modifier = Modifier.weight(1f), onCommit = onCommit) }

/** A whole number in [range]; a typed value outside it is refused with a sentence in the status line. */
@Composable
internal fun EdInt(label: String, value: Int, range: IntRange, enabled: Boolean = true, unit: String? = null, tip: String? = null, onCommit: (Int) -> Unit) =
    EdRow(label, tip) {
        EdField("$value", enabled, mono = true, width = 90.dp, align = TextAlign.End) { s ->
            val v = s.trim().toIntOrNull()
            when {
                v == null -> CommandSession.status = "\"$s\" is not a whole number."
                v !in range -> CommandSession.status = "$label goes from ${range.first} to ${range.last}."
                else -> onCommit(v)
            }
        }
        if (unit != null) Text("  $unit", color = Hud.TextFaint, fontSize = 11.sp)
    }

/** A decimal number. */
@Composable
internal fun EdNum(label: String, value: Double, decimals: Int = 1, enabled: Boolean = true, unit: String? = null, tip: String? = null, onCommit: (Double) -> Unit) =
    EdRow(label, tip) {
        EdField("%.${decimals}f".format(value), enabled, mono = true, width = 110.dp, align = TextAlign.End) { s ->
            val v = s.trim().replace(",", "").toDoubleOrNull()
            if (v == null || !v.isFinite()) CommandSession.status = "\"$s\" is not a number." else onCommit(v)
        }
        if (unit != null) Text("  $unit", color = Hud.TextFaint, fontSize = 11.sp)
    }

/** A choice from a short menu: [options] (value, words). */
@Composable
internal fun EdChoice(label: String, value: Int, options: List<Pair<Int, String>>, enabled: Boolean = true, tip: String? = null, ink: ((Int) -> Color?)? = null, lead: ((Int) -> (@Composable () -> Unit)?)? = null, onPick: (Int) -> Unit) =
    EdRow(label, tip) { EdMenu(value, options, enabled, ink, Modifier.weight(1f), lead, onPick) }

@Composable
internal fun EdMenu(value: Int, options: List<Pair<Int, String>>, enabled: Boolean = true, ink: ((Int) -> Color?)? = null, modifier: Modifier = Modifier, lead: ((Int) -> (@Composable () -> Unit)?)? = null, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(2.dp)).border(1.dp, if (enabled) Hud.Outline.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(2.dp))
                .clickable(enabled = enabled) { open = true }.padding(horizontal = 5.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lead?.invoke(value)?.invoke()
            Text(options.firstOrNull { it.first == value }?.second ?: "$value", color = if (!enabled) Hud.TextFaint else ink?.invoke(value) ?: Hud.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (enabled) Text("  ▾", color = Hud.TextFaint, fontSize = 10.sp)
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 420.dp).background(Hud.Surface)) {
            options.forEach { (v, words) ->
                DropdownMenuItem(
                    text = { val l = lead?.invoke(v); if (l == null) Text(words, color = ink?.invoke(v) ?: if (v == value) Hud.Amber else Hud.Text, fontSize = 12.sp) else Row(verticalAlignment = Alignment.CenterVertically) { l(); Text(words, color = ink?.invoke(v) ?: if (v == value) Hud.Amber else Hud.Text, fontSize = 12.sp) } },
                    onClick = { open = false; if (v != value) onPick(v) },
                    modifier = Modifier.height(26.dp),
                )
            }
        }
    }
}

/** A tick box row. */
@Composable
internal fun EdCheck(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    com.bmscompanion.desktop.ui.command.CheckLine(label, checked, enabled = enabled, onChange = onChange)

/** Chips, one lit: a short choice on one line. */
@Composable
internal fun EdChips(label: String, value: Int, options: List<Pair<Int, String>>, enabled: Boolean = true, ink: ((Int) -> Color?)? = null, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(label, color = Hud.TextDim, fontSize = 11.5.sp)
        MiniWrap { options.forEach { (v, w) -> MiniChip(w, v == value, ink?.invoke(v), enabled) { if (v != value) onPick(v) } } }
    }
    Rule(0.15f)
}

/** A sentence in the panel. */
@Composable
internal fun EdNote(text: String, ink: Color = Hud.TextFaint) =
    Text(text, color = ink, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))

/** A row of buttons that wraps. */
@Composable
internal fun EdButtons(content: @Composable () -> Unit) = Box(Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) { MiniWrap(content) }

/** A panel column with a fixed width, for a page laid out as columns. */
@Composable
internal fun EdColumn(width: Dp, content: @Composable ColumnScope.() -> Unit) = Column(Modifier.widthIn(min = width, max = width), content = content)

internal fun edGapModifier(): Modifier = Modifier.height(6.dp)

@Composable internal fun EdGap() = Spacer(edGapModifier())
