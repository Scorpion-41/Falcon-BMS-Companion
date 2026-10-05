package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.components.KeyboardLift
import com.bmscompanion.app.ui.theme.Hud

/**
 * **A Planner box typed by finger: one bar on the keyboard.** A tap on a box or a number box opens this in place of a
 * dialog: the box's name (its label on the page, [TouchTargets.captionFor]) beside the value, the value with the
 * caret and the right keyboard, − and + for a number box, **Next** (the next box on the page, in reading order) and
 * **Done**. The page itself does not move or shrink: the keyboard is laid over the app ([KeyboardLift]) and the bar
 * rides on top of it, placed from the keyboard's inset in the drawing's layout pass (no recomposition while it slides).
 * The box being typed in is ringed on the page.
 *
 * It sets the page exactly as the dialog did ([WdpSheet.Text.commit]: the text if it changed, then `"<name>.leave"`;
 * [WdpSheet.Number]: the arrows at once, a typed value on Done). A tap on the page above it is Done, as leaving a box
 * for another is in Windows; ✕ puts it away without changing anything.
 */
@Composable
internal fun WdpEditBarHost(s: WdpSheet, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val host = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // the bar's own "Done" for whatever it shows now, for a tap on the page above it
    val done = remember { arrayOfNulls<() -> Unit>(1) }
    DisposableEffect(Unit) { KeyboardLift.hold++; onDispose { KeyboardLift.hold-- } }
    val ring = WdpTouch.ring
    BoxWithConstraints(
        modifier.fillMaxSize()
            .onGloballyPositioned { host[0] = it }
            .pointerInput(Unit) { detectTapGestures { (done[0] ?: { WdpTouch.close() })() } }
            .drawBehind {
                // the box being typed in, ringed where it is on the page
                val r = ring ?: return@drawBehind
                val c = host[0] ?: return@drawBehind
                if (!c.isAttached) return@drawBehind
                val o = c.positionInRoot()
                val g = 3.dp.toPx()
                drawRect(
                    Hud.Amber, topLeft = Offset(r.left - o.x - g, r.top - o.y - g), size = Size(r.width + 2 * g, r.height + 2 * g),
                    style = Stroke(2.dp.toPx()),
                )
            },
    ) {
        // under 760 dp (a phone, a tablet held upright) the name goes over the value, which then takes the width
        val narrow = maxWidth < 760.dp
        Column(
            Modifier.align(Alignment.BottomCenter)
                // on the keyboard: lifted by as much of the keyboard as reaches up into this box — as a layer's move, so
                // the keyboard sliding in redraws nothing (an offset re-recorded everything round the bar, every frame)
                .graphicsLayer {
                    val c = host[0]
                    val kb = ime.getBottom(density)
                    val over = if (c == null || !c.isAttached || kb <= 0) 0f else {
                        val bottom = c.positionInRoot().y + c.size.height
                        kb - (c.findRootCoordinates().size.height - bottom)
                    }
                    translationY = -over.coerceIn(0f, (c?.size?.height ?: 0).toFloat())
                }
                .fillMaxWidth()
                .background(Hud.Surface)
                .drawBehind { drawLine(Hud.Amber.copy(alpha = 0.6f), Offset(0f, 0f), Offset(size.width, 0f), 1.5.dp.toPx()) }
                // a press on the bar is the bar's, never the page's
                .pointerInput(Unit) { detectTapGestures { } }
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { WdpTouch.close(); true } else false }
                .plannerProbe("Sheet")
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            key(s) {
                when (s) {
                    is WdpSheet.Text -> TextBar(s, narrow, done)
                    is WdpSheet.Number -> NumberBar(s, narrow, done)
                    else -> {}
                }
            }
        }
    }
}

/** The box's name: beside the value on a tablet, over it on a phone held upright. */
@Composable
private fun BarTitle(s: WdpSheet, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(s.title, color = Hud.Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // the tip, less its first words where they are the name already (a box with no label is named by its tip)
        val named = s.title.substringAfterLast(" · ")
        WdpTips.forKey(s.key)?.takeIf { WdpTips.on }
            ?.let { t -> if (t.startsWith(named)) t.removePrefix(named).trimStart(',', '.', ':', ';', '—', ' ') else t }
            ?.takeIf { it.isNotBlank() }?.let { tip ->
            Text(tip, color = Hud.TextDim, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.plannerProbe("Sheet/Tip"))
        }
    }
}

@Composable
private fun TextBar(s: WdpSheet.Text, narrow: Boolean, done: Array<(() -> Unit)?>) {
    var v by remember(s) { mutableStateOf(TextFieldValue(s.initial, TextRange(0, s.initial.length))) }
    fun ok() { WdpTouch.close(); s.commit(v.text) }
    fun next() { s.commit(v.text); WdpTouch.next(s) }
    done[0] = ::ok
    BarRow(s, narrow, onNext = ::next, onDone = ::ok) { m ->
        BarInput(v, { v = it }, s.numeric, s.multiline, onDone = ::ok, onNext = ::next, modifier = m)
    }
}

@Composable
private fun NumberBar(s: WdpSheet.Number, narrow: Boolean, done: Array<(() -> Unit)?>) {
    val shown = s.value()
    var typed by remember(s) { mutableStateOf<TextFieldValue?>(null) }
    val v = typed ?: TextFieldValue(shown, TextRange(0, shown.length))
    fun commit() { typed?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { s.commit(it) } }
    fun ok() { WdpTouch.close(); commit() }
    fun next() { commit(); WdpTouch.next(s) }
    done[0] = ::ok
    BarRow(s, narrow, onNext = ::next, onDone = ::ok) { m ->
        Row(m, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton("−", "Sheet/Minus", wide = false) { s.step(typed?.text?.trim()?.takeIf { it.isNotEmpty() }, -1); typed = null }
            BarInput(
                v, { t -> typed = t.copy(text = t.text.filter { it.isDigit() || it == '.' || it == '-' }) },
                numeric = true, multiline = false, onDone = ::ok, onNext = ::next, modifier = Modifier.weight(1f), align = TextAlign.Center,
            )
            BarButton("+", "Sheet/Plus", wide = false) { s.step(typed?.text?.trim()?.takeIf { it.isNotEmpty() }, 1); typed = null }
        }
    }
}

/** Name, value, Next, Done, ✕: one row on a tablet; the name over the rest on a phone held upright. */
@Composable
private fun BarRow(s: WdpSheet, narrow: Boolean, onNext: () -> Unit, onDone: () -> Unit, value: @Composable (Modifier) -> Unit) {
    val hasNext = WdpTouch.hasNext(s)
    if (narrow) BarTitle(s, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // the name takes the room the value does not need: a value is a number or a short line, a name can be a sentence
        if (!narrow) BarTitle(s, Modifier.weight(1f))
        value(if (narrow) Modifier.weight(1f) else Modifier.width(if (s is WdpSheet.Number) 300.dp else 360.dp))
        if (hasNext) BarButton("Next", "Sheet/Next", wide = true, primary = false) { onNext() }
        BarButton("Done", "Sheet/OK", wide = true, primary = true) { onDone() }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).plannerPress { WdpTouch.close() }.plannerProbe("Sheet/Close"),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Close, "Cancel", tint = Hud.TextDim, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun BarButton(label: String, probe: String, wide: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.height(44.dp).widthIn(min = if (wide) 72.dp else 48.dp).clip(shape).background(if (primary) Hud.Amber else Hud.Surface3)
            .plannerPress { onClick() }.plannerProbe(probe).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) Hud.Bg else Hud.Text, fontSize = if (wide) 15.sp else 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The value: 18 sp, the keyboard asked for at once, the keyboard's own action key Next or Done. */
@Composable
private fun BarInput(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    numeric: Boolean,
    multiline: Boolean,
    onDone: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) {
    val focus = remember { FocusRequester() }
    val keys = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus(); keys?.show() } }
    val shape = RoundedCornerShape(8.dp)
    val style = TextStyle(color = Hud.Text, fontSize = 18.sp, textAlign = align)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = !multiline,
        maxLines = if (multiline) 3 else 1,
        textStyle = style,
        cursorBrush = SolidColor(Hud.Amber),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onNext = { onNext() }),
        modifier = modifier.heightIn(min = 44.dp, max = if (multiline) 96.dp else 44.dp)
            .clip(shape).background(Hud.Bg).border(1.5.dp, Hud.Amber.copy(alpha = 0.7f), shape)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (!multiline && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { onDone(); true }
                else if (e.type == KeyEventType.KeyDown && e.key == Key.Tab) { onNext(); true } else false
            }
            .plannerProbe("Sheet/Input")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) { inner() } },
    )
}

/** The control a sheet is open for, kept with its form's registry to find the next one ([WdpTouch.next]). */
internal class SheetOrigin(val reg: TouchTargets, val target: TouchTarget)
