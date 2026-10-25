package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
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
import com.bmscompanion.desktop.ui.command.Tip

// The unit inspectors' rows: a label column and a value edited in place — a box committed on Enter or when it loses
// the keyboard, a drop-down, a tick — each change one undoable edit. A refused change keeps the box red with the
// reason under the mouse (and in the status line).

/** The width of an inspector's label column. */
val LABEL_W = 104.dp

/** Runs [edit] for a row: the reason when it was refused, else null. */
fun CmdContext.apply(edit: CommandEdit): String? {
    val why = core.edits.run(edit)
    if (why != null) say(why) else com.bmscompanion.desktop.ui.command.CommandSession.done(edit.label)
    changed()
    return why
}

/** The inspector's title block: name, what it is, a line of facts. */
@Composable
fun InspectorHead(title: String, kind: String, facts: String? = null, ink: Color = Hud.Text, flag: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            flag?.invoke()
            Text(title, color = ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(kind.uppercase(), color = Hud.TextFaint, fontSize = 10.sp, letterSpacing = 1.sp)
        if (facts != null) Text(facts, color = Hud.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
    Rule()
}

/** A ruled row: [label] in the label column, [content] beside it. */
@Composable
fun Line(label: String, tip: String? = null, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(horizontal = 6.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        val l: @Composable () -> Unit = { Text(label, color = Hud.TextDim, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(LABEL_W)) }
        if (tip != null) Tip(tip) { l() } else l()
        content()
    }
    Rule(0.14f)
}

/**
 * A box edited in place: [value] shown, a new text given to [commit] on Enter or when the box loses the keyboard
 * (and only when it changed); [commit] answers the refusal or null.
 */
@Composable
fun EditBox(value: String, modifier: Modifier = Modifier, width: Dp? = null, mono: Boolean = false, align: TextAlign = TextAlign.Start, enabled: Boolean = true, resetAfter: Boolean = false, commit: (String) -> String?) {
    var text by remember(value) { mutableStateOf(value) }
    var error by remember(value) { mutableStateOf<String?>(null) }
    var committed by remember(value) { mutableStateOf(value) }
    fun go() {
        if (text == committed) return
        val t = text
        committed = t
        error = commit(t)
        if (error == null && resetAfter) { text = value; committed = value }
    }
    val box: @Composable () -> Unit = {
        BasicTextField(
            text, { text = it }, singleLine = true, enabled = enabled,
            textStyle = TextStyle(color = if (enabled) Hud.Text else Hud.TextFaint, fontSize = 12.sp, fontFamily = if (mono) FontFamily.Monospace else null, textAlign = align),
            cursorBrush = SolidColor(Hud.Amber),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { go() }),
            modifier = (if (width != null) modifier.width(width) else modifier)
                .clip(RoundedCornerShape(3.dp)).background(Hud.Bg.copy(alpha = 0.6f))
                .border(1.dp, if (error != null) Hud.Red else Hud.Outline.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
                .onFocusChanged { if (!it.isFocused) go() }
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { go(); true } else if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { text = value; error = null; true } else false }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
    val e = error
    if (e != null) Tip(e) { box() } else box()
}

/** A number box: the text read as a whole number in [range], else refused. */
@Composable
fun NumBox(value: Long, range: LongRange, width: Dp = 76.dp, enabled: Boolean = true, commit: (Long) -> String?) =
    EditBox(value.toString(), width = width, mono = true, align = TextAlign.End, enabled = enabled) { t ->
        val v = t.trim().replace(",", "").toLongOrNull() ?: return@EditBox "Not a number."
        if (v !in range) return@EditBox "From ${range.first} to ${range.last}."
        commit(v)
    }

@Composable
fun NumLine(label: String, value: Long, range: LongRange, tip: String? = null, unit: String? = null, enabled: Boolean = true, commit: (Long) -> String?) =
    Line(label, tip) {
        NumBox(value, range, enabled = enabled, commit = commit)
        if (unit != null) Text("  $unit", color = Hud.TextFaint, fontSize = 11.sp)
    }

@Composable
fun TextLine(label: String, value: String, tip: String? = null, commit: (String) -> String?) =
    Line(label, tip) { EditBox(value, Modifier.weight(1f), commit = commit) }

/** A drop-down of [options] (value, text); [searchable] adds a filter box for long lists. */
@Composable
fun <T> Choice(current: T, options: List<Pair<T, String>>, modifier: Modifier = Modifier, enabled: Boolean = true, searchable: Boolean = options.size > 14, lead: ((T) -> (@Composable () -> Unit)?)? = null, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val label = options.firstOrNull { it.first == current }?.second ?: current?.toString() ?: "—"
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(Hud.Surface)
                .border(1.dp, Hud.Outline.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
                .clickable(enabled = enabled) { open = true; filter = "" }.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lead?.invoke(current)?.invoke()
            Text("$label  ▾", maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (enabled) Hud.Text else Hud.TextFaint, fontSize = 12.sp)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(Hud.Surface).widthIn(min = 200.dp, max = 420.dp).heightIn(max = 460.dp)) {
            if (searchable) {
                BasicTextField(
                    filter, { filter = it }, singleLine = true,
                    textStyle = TextStyle(color = Hud.Text, fontSize = 12.sp), cursorBrush = SolidColor(Hud.Amber),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).border(1.dp, Hud.Outline, RoundedCornerShape(3.dp)).padding(5.dp),
                    decorationBox = { inner -> if (filter.isEmpty()) Text("Filter…", color = Hud.TextFaint, fontSize = 12.sp); inner() },
                )
            }
            val shown = if (filter.isBlank()) options else options.filter { it.second.contains(filter.trim(), ignoreCase = true) }
            shown.take(400).forEach { (v, t) ->
                DropdownMenuItem(
                    text = { Row(verticalAlignment = Alignment.CenterVertically) { lead?.invoke(v)?.invoke(); Text(t, fontSize = 12.sp, color = if (v == current) Hud.Amber else Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    onClick = { open = false; if (v != current) onPick(v) }, modifier = Modifier.height(26.dp),
                )
            }
            if (shown.size > 400) Text("${shown.size - 400} more — filter to find them", color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
fun <T> ChoiceLine(label: String, current: T, options: List<Pair<T, String>>, tip: String? = null, enabled: Boolean = true, lead: ((T) -> (@Composable () -> Unit)?)? = null, onPick: (T) -> Unit) =
    Line(label, tip) { Choice(current, options, Modifier.weight(1f), enabled = enabled, lead = lead, onPick = onPick) }

/** A row of small buttons under a section. */
@Composable
fun Buttons(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** A small flat button for inspector rows. */
@Composable
fun SmallButton(text: String, enabled: Boolean = true, danger: Boolean = false, tip: String? = null, onClick: () -> Unit) {
    val body: @Composable () -> Unit = {
        Text(
            text, Modifier.clip(RoundedCornerShape(3.dp)).background(Hud.Surface)
                .border(1.dp, if (enabled) (if (danger) Hud.Red.copy(alpha = 0.7f) else Hud.Outline) else Hud.Outline.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 7.dp, vertical = 3.dp),
            color = if (!enabled) Hud.TextFaint else if (danger) Hud.Red else Hud.Text, fontSize = 11.5.sp, maxLines = 1,
        )
    }
    if (tip != null) Tip(tip) { body() } else body()
}

/** A link to another record (selects it). */
@Composable
fun LinkText(text: String, onClick: () -> Unit) =
    Text(text, color = Hud.Cyan, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(onClick = onClick))

/** A table of rows inside an inspector: fixed column widths, a heading, 22-dp rows; [onRow] for a click. */
@Composable
fun MiniTable(heads: List<Pair<String, Dp>>, rows: List<List<String>>, selected: Int = -1, maxHeight: Dp = 240.dp, inks: List<Color?> = emptyList(), onRow: ((Int) -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth().background(Hud.Surface.copy(alpha = 0.6f)).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            heads.forEach { (h, w) -> Text(h.uppercase(), color = Hud.TextFaint, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.width(w).padding(horizontal = 3.dp)) }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { i, r ->
                Row(
                    Modifier.fillMaxWidth().height(21.dp).background(if (i == selected) Hud.Amber.copy(alpha = 0.16f) else Color.Transparent)
                        .then(if (onRow != null) Modifier.clickable { onRow(i) } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    r.forEachIndexed { j, c ->
                        Text(c, color = inks.getOrNull(i) ?: Hud.Text, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(heads.getOrNull(j)?.second ?: 60.dp).padding(horizontal = 3.dp))
                    }
                }
                Rule(0.1f)
            }
        }
    }
}

/** Reads the session's version so a composable is drawn again after every edit, undo and redo. */
@Composable
fun watchEdits(): Int = CommandSession.version

/** The scroll column every inspector sits in. */
@Composable
fun InspectorColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
}

@Composable
fun Remember(key: Any?, block: suspend () -> Unit) = LaunchedEffect(key) { block() }
