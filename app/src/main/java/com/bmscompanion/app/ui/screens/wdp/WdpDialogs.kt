package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.wdp.WdpForm
import com.bmscompanion.app.data.wdp.WdpValues
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * One of Weapon Delivery Planner's own child windows — Change STPT, Loadout, Formation, Mil Codes and the rest of
 * the `fcls*` forms — drawn from its own layout like a page, with a wiring of its own.
 *
 * A page's wiring opens one with [WdpDialogs.show] where WDP calls `ShowDialog()`, and the dialog's wiring closes it
 * ([WdpDialogs.close]) from its OK or Cancel, having done what WDP's did — typically calling back into the page
 * that opened it. [onDismiss] is the window's own × (WDP's Cancel), for a dialog whose opener must hear of it.
 */
class WdpDialog(
    val form: String,
    val title: String,
    val wiring: WdpWiring? = null,
    /** controls the dialog hides that the designer shows, as a page's `hidden` list */
    val hidden: List<String> = emptyList(),
    val onDismiss: (() -> Unit)? = null,
)

/**
 * A message box — WDP's `MessageBox.Show` — with its buttons; [onAnswer] hears which was pressed. [default] is the
 * button Enter presses and the one drawn filled (the first, as in Windows, unless a question says otherwise: the
 * Precision STPT question's is No).
 */
class WdpMessage(
    val title: String,
    val text: String,
    val buttons: List<String> = listOf("OK"),
    val default: Int = 0,
    val onAnswer: ((String) -> Unit)? = null,
)

/**
 * A window the Planner draws itself, at the device's own sizes rather than a WDP form's scale: [content] fills it, up to
 * [maxW] × [maxH] dp and never more than the room there is (on a phone, nearly the whole screen). What a scaled WDP
 * window cannot make finger-sized opens one — the Loadout window's store picker on a phone or a tablet. [onDismiss] is
 * its × and Escape.
 */
class WdpPanelWindow(
    val title: String,
    val maxW: Float = 1000f,
    val maxH: Float = 900f,
    val onDismiss: (() -> Unit)? = null,
    val content: @Composable () -> Unit,
)

/**
 * The Planner's open child windows, the top one last. Modal as WDP's are: while one is open the page under it takes
 * no input, and closing it hands the page back. Held here rather than by a page, so a dialog can open a dialog
 * (Change STPT's airport list) and a message box can come from anywhere.
 */
object WdpDialogs {
    internal val stack = mutableStateListOf<Any>()

    fun show(d: WdpDialog) { stack += d }

    fun close(d: WdpDialog) { stack.remove(d) }

    /** Closes whatever is on top: the window's ×, and Escape. */
    fun closeTop() {
        val top = stack.lastOrNull() ?: return
        stack.removeAt(stack.lastIndex)
        (top as? WdpDialog)?.onDismiss?.invoke()
        (top as? WdpPanelWindow)?.onDismiss?.invoke()
        (top as? WdpMessage)?.let { m -> m.onAnswer?.invoke(m.buttons.lastOrNull() ?: "OK") }
    }

    fun show(w: WdpPanelWindow) { stack += w }

    fun close(w: WdpPanelWindow) { stack.remove(w) }

    fun message(title: String, text: String, buttons: List<String> = listOf("OK"), default: Int = 0, onAnswer: ((String) -> Unit)? = null) {
        stack += WdpMessage(title, text, buttons, default.coerceIn(0, (buttons.size - 1).coerceAtLeast(0)), onAnswer)
    }

    val isOpen: Boolean get() = stack.isNotEmpty()
}

/**
 * Where the dialogs are drawn: over the Planner, which takes no input while one is up. Put it last in the box that
 * holds the page so it covers it.
 *
 * The page is not dimmed behind a window, as Windows does not dim WDP's main window behind its dialogs; the window
 * stands out by its title bar and a dark edge. (A see-through wash over the whole Planner also cost 100 ms a frame
 * wherever Skia draws on the processor — the PC's software renderer, the headless checks — which is a key typed in a
 * window taking a tenth of a second to show.)
 *
 * [pageScale] is the scale the page behind is drawn at (pixels per designer pixel, [WdpFormView]'s `onScale`): a
 * window is drawn at that same scale, as WDP's windows are the same size as its pages, so it grows with the window
 * and with a pinch zoom as the page does; it is made smaller only where it would not fit. 0 (unknown) fits it.
 *
 * **Every open window is drawn**, the top one last, as Windows keeps a window on the screen under the message box it
 * raised: a question or a refusal from inside a window (the Loadout's, a Change window's) used to replace the window
 * on the screen until it was answered, so the pilot could not see what it was asking about. Only the top one takes
 * input and the keys; each window under it is covered as the page is.
 */
@Composable
fun WdpDialogHost(modifier: Modifier = Modifier, pageScale: Float = 0f) {
    val stack = WdpDialogs.stack.toList()
    val top = stack.lastOrNull() ?: return
    BoxWithConstraints(
        modifier.fillMaxSize()
            // the page underneath takes nothing while a dialog is up, as with ShowDialog
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        val fullW = constraints.maxWidth.toFloat()
        val fullH = constraints.maxHeight.toFloat()
        for (entry in stack) {
            // one window at a time takes the keys: Escape is its Cancel, Enter its AcceptButton (WindowFrame)
            androidx.compose.runtime.key(entry) {
                val isTop = entry === top
                // a window under the top one takes nothing, as the page under the first takes nothing
                if (isTop && stack.size > 1) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } })
                when (entry) {
                    is WdpDialog -> DialogWindow(entry, fullW, fullH, pageScale, isTop)
                    is WdpMessage -> MessageWindow(entry, fullW * 0.96f, fullH * 0.96f, pageScale, isTop)
                    is WdpPanelWindow -> PanelWindow(entry, fullW, fullH, isTop)
                }
            }
        }
    }
}

/**
 * The buttons WDP's windows name as their `AcceptButton` (Enter) and `CancelButton` (Escape), from its designer
 * code. A window with no Cancel button of its own is closed by Escape as by its ×.
 */
private val ACCEPT = mapOf(
    "fclsAptToStpt" to "btnApply", "fclsChangeCOMM" to "btnApply", "fclsChangeLine" to "btnApply",
    "fclsChangeLineSTPT" to "btnApply", "fclsChangePPT" to "btnApply", "fclsChangeSTPT" to "btnApply",
    "fclsSelAPT" to "btnSelect",
)
private val CANCEL = setOf(
    "fclsAptToStpt", "fclsChangeCOMM", "fclsChangeLine", "fclsChangeLineSTPT", "fclsChangePPT", "fclsChangeSTPT",
    "fclsSelAPT", "fclsNames", "fclsPackageNr", "fclsTargetSelection",
).associateWith { "btnCancel" }

/**
 * A window in the Planner's look: rounded corners, a soft shadow, a title bar in the window's own light — dark over
 * one of WDP's charcoal windows, light over a grey one — with the title and an × that turns red under the mouse, as
 * Windows 11's does, and the form under it. [titleHeight] sizes the bar as the form is sized (never smaller than a
 * finger can close it). The window takes the keyboard when it opens, so nothing on the page behind hears a key.
 * [onKey] sees each key before any box in the window does (Escape is the window's whatever has the keyboard);
 * [onKeyAfter] sees what no box took (Enter where no box is being typed in). [ground] is what the form is drawn on,
 * so the frame's colour runs up to the window's edge without a seam.
 */
@Composable
private fun WindowFrame(
    title: String,
    titleHeight: androidx.compose.ui.unit.Dp,
    titleSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onKey: (KeyEvent) -> Boolean,
    onKeyAfter: (KeyEvent) -> Boolean = { false },
    /** whether this is the window on top, which has the keys; a window a message box closed over takes them back */
    isTop: Boolean = true,
    ground: Color = WindowLook.LIGHT_BODY,
    content: @Composable () -> Unit,
) {
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(isTop) { if (isTop) runCatching { focus.requestFocus() } }
    val dark = ground.wdpIsDark()
    val shape = RoundedCornerShape(WindowLook.RADIUS)
    // as wide as the form (or the message) under the title bar: the bar fills the window's width, and without the
    // intrinsic width it filled the whole screen, so every window was a full-width strip with the form at its left
    Column(
        modifier.width(IntrinsicSize.Max)
            // the shadow: a few faint rings round the window rather than a blur, which costs a frame's time wherever
            // Skia draws on the processor (the PC's software renderer)
            .drawBehind { windowShadow(WindowLook.RADIUS.toPx()) }
            .clip(shape).background(ground)
            .border(1.dp, if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.28f), shape)
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && onKey(e) }
            .onKeyEvent { e -> e.type == KeyEventType.KeyDown && onKeyAfter(e) }
            .focusRequester(focus).focusable(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(titleHeight).background(if (dark) WindowLook.DARK_BAR else WindowLook.LIGHT_BAR)
                .drawBehind {
                    drawLine(
                        if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.1f),
                        Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f,
                    )
                }
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title, color = if (dark) WindowLook.DARK_TITLE else WindowLook.LIGHT_TITLE, fontSize = titleSize,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            CloseButton(titleHeight, dark, onClose)
        }
        content()
    }
}

/** The Planner's window colours, for WDP's windows and message boxes. */
private object WindowLook {
    val RADIUS = 8.dp
    val LIGHT_BAR = Color(0xFFF7F8FA)
    val LIGHT_TITLE = Color(0xFF1B1F24)
    val LIGHT_BODY = Color(0xFFECEFF2)
    val DARK_BAR = Color(0xFF1E252E)
    val DARK_TITLE = Color(0xFFE6EDF3)
    val CLOSE_HOVER = Color(0xFFC42B1C)
    val ACCENT = Color(0xFF3B82F6)
}

/** A soft shadow round a rounded window, as rings, drawn behind it (see [WindowFrame]). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.windowShadow(radius: Float) {
    val step = 2.dp.toPx()
    val alphas = floatArrayOf(0.22f, 0.13f, 0.07f, 0.035f)
    for (i in alphas.indices) {
        val s = step * (i + 0.5f)
        drawRoundRect(
            Color.Black.copy(alpha = alphas[i]),
            topLeft = Offset(-s, -s + step),
            size = androidx.compose.ui.geometry.Size(size.width + 2 * s, size.height + 2 * s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius + s),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = step),
        )
    }
}

/** A title bar's ×: drawn, not set in a font; red under the mouse, darker red while pressed. */
@Composable
private fun CloseButton(height: androidx.compose.ui.unit.Dp, dark: Boolean, onClose: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val ink = when {
        hovered || pressed -> Color.White
        dark -> WindowLook.DARK_TITLE
        else -> WindowLook.LIGHT_TITLE
    }
    Box(
        Modifier.size(width = height * 1.45f, height = height)
            .background(if (pressed) WindowLook.CLOSE_HOVER.copy(alpha = 0.8f) else if (hovered) WindowLook.CLOSE_HOVER else Color.Transparent)
            .hoverable(src).clickable(src, indication = null) { onClose() }
            .drawBehind {
                val a = minOf(size.height * 0.3f, 10.dp.toPx()) / 2f
                val c = center
                val w = maxOf(1.2f, 1.2.dp.toPx())
                drawLine(ink, Offset(c.x - a, c.y - a), Offset(c.x + a, c.y + a), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(ink, Offset(c.x - a, c.y + a), Offset(c.x + a, c.y - a), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            },
    )
}

/**
 * A message box's button in the Planner's look: rounded, the first (Enter's) filled with the accent, the others light
 * with a thin edge; lighter under the mouse, darker while pressed. Same size and order as before.
 */
@Composable
private fun MessageButton(text: String, first: Boolean, size: androidx.compose.ui.unit.TextUnit, onClick: () -> Unit) {
    // by finger: a finger's height and width, the caption bigger (WdpTouch)
    val finger = WdpTouch.device
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val base = if (first) WindowLook.ACCENT else Color(0xFFFFFFFF)
    val fill = when {
        pressed -> androidx.compose.ui.graphics.lerp(base, Color.Black, if (first) 0.18f else 0.1f)
        hovered -> if (first) androidx.compose.ui.graphics.lerp(base, Color.White, 0.14f) else Color(0xFFF1F4F7)
        else -> base
    }
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.widthIn(min = if (finger) 90.dp else 80.dp).height(if (finger) 42.dp else 28.dp).clip(if (finger) RoundedCornerShape(8.dp) else shape).background(fill)
            .border(1.dp, if (first) androidx.compose.ui.graphics.lerp(base, Color.Black, 0.2f) else Color(0xFFC3CAD3), if (finger) RoundedCornerShape(8.dp) else shape)
            .hoverable(src).clickable(src, indication = null) { onClick() }
            .padding(horizontal = if (finger) 18.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (first) Color.White else WindowLook.LIGHT_TITLE, fontSize = if (finger) maxOf(size.value, 15f).sp else size,
            fontWeight = if (first) FontWeight.Medium else FontWeight.Normal, maxLines = 1,
        )
    }
}

/**
 * A window's title bar at [k] pixels per designer pixel: Windows' 30 px caption, never under 28 dp — and by finger
 * ([WdpTouch.device]) never under 44 dp, so its × (half as wide again) is a finger's size, with its title at 15 sp.
 */
@Composable
private fun titleBar(k: Float): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.TextUnit> = with(LocalDensity.current) {
    val finger = WdpTouch.device
    // by finger one slim line (the × keeps a finger's width): the form's own scale, which is large by finger, made the
    // bar as tall as two rows of the form
    if (finger) 38.dp to 14.sp
    else maxOf((30f * k).toDp(), 28.dp) to maxOf((12.5f * k).toSp().value, 12.5f).sp
}

@Composable
private fun DialogWindow(d: WdpDialog, fullW: Float, fullH: Float, pageScale: Float, isTop: Boolean = true) {
    val form by produceState<WdpForm?>(null, d.form) { value = Repo.wdpForm(d.form) }
    val f = form
    val density = LocalDensity.current
    val values = d.wiring?.values(d.hidden) ?: WdpValues(d.hidden.associateWith { "hidden" })
    fun usable(b: String?) = b != null && values[b] != "hidden" && values["$b.enabled"] != "false"
    val accept = ACCEPT[d.form]?.takeIf { usable(it) }
    val cancel = CANCEL[d.form]?.takeIf { usable(it) }
    // Enter in a box of the window (reported as "<box>:enter") presses the window's AcceptButton after the box's
    // own handler has heard it, as Windows does; a window without one leaves Enter to the box
    val onClick: ((String) -> Unit)? = d.wiring?.let { w ->
        { n: String ->
            w.onClick(n)
            if (n.endsWith(":enter") && accept != null && WdpDialogs.stack.lastOrNull() === d) w.onClick(accept)
        }
    }
    val fw = (f?.width ?: 400).coerceAtLeast(1)
    val fh = (f?.height ?: 200).coerceAtLeast(1)
    val k: Float
    val barH: androidx.compose.ui.unit.Dp
    val barSp: androidx.compose.ui.unit.TextUnit
    if (pageScale > 0f) {
        // the page's scale, less only what it takes to fit; the fit allows for the title bar at that size. By finger
        // (WdpTouch) a window is drawn at least at 1.5 dp a designer pixel where it fits, so its boxes and buttons are a
        // finger's size on a tablet, and as big as fits on a phone, where it takes the whole width like a sheet
        val finger = WdpTouch.device
        val phone = with(density) { fullW.toDp() } < 600.dp
        val maxW = fullW * if (finger && phone) 0.99f else 0.96f
        val maxH = fullH * if (finger && phone) 0.99f else 0.96f
        val want = if (finger) maxOf(pageScale, 1.5f * density.density) else pageScale
        val fitW = maxW / fw
        val bar = titleBar(minOf(want, fitW, maxH / fh))
        val fitH = (maxH - with(density) { bar.first.toPx() }) / fh
        k = if (finger && phone) minOf(fitW, fitH) else minOf(want, fitW, fitH)
        val b = titleBar(k)
        barH = b.first; barSp = b.second
    } else {
        // no page behind to take the scale from (a headless check drawing a window by itself): fitted, and at most
        // 1.5 dp a designer pixel, under a 30 dp title bar, as the host has always drawn them
        val kd = minOf(with(density) { fullW.toDp().value } * 0.94f / fw, (with(density) { fullH.toDp().value } * 0.92f - 30f) / fh, 1.5f)
        k = kd * density.density
        barH = 30.dp; barSp = 12.5.sp
    }
    // a window has a pinch zoom of its own on a phone, as the page has
    val zoom = remember(d) { WdpZoom() }
    WindowFrame(
        d.title, barH, barSp,
        ground = f?.let { wdpFormGround(it) } ?: WindowLook.LIGHT_BODY,
        onClose = { WdpDialogs.closeTop() },
        onKey = { e ->
            if (e.key == Key.Escape) { if (cancel != null) d.wiring?.onClick(cancel) else WdpDialogs.closeTop(); true } else false
        },
        // Enter that no box took (none has the keyboard): the AcceptButton straight away
        onKeyAfter = { e ->
            if ((e.key == Key.Enter || e.key == Key.NumPadEnter) && accept != null) { d.wiring?.onClick(accept); true } else false
        },
        isTop = isTop,
    ) {
        if (f == null) {
            Text("Loading…", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(24.dp))
        } else {
            Box(Modifier.size(with(density) { (fw * k).toDp() }, with(density) { (fh * k).toDp() })) {
                WdpFormView(
                    f, values, Modifier.fillMaxSize(), fixedScale = k, zoom = zoom,
                    onValue = d.wiring?.let { it::onValue },
                    onClick = onClick,
                    // what a window draws of its own inside a control, as a page does (the chart window's charts)
                    content = (d.wiring as? WdpControlContent)?.controlContent() ?: emptyMap(),
                )
            }
        }
    }
}

/**
 * A [WdpPanelWindow]: the Planner's frame round content of its own, at the device's sizes — nearly the whole screen
 * on a phone, at most its [WdpPanelWindow.maxW] × [WdpPanelWindow.maxH] dp elsewhere. Escape and the × close it.
 */
@Composable
private fun PanelWindow(w: WdpPanelWindow, fullW: Float, fullH: Float, isTop: Boolean) {
    val density = LocalDensity.current
    val finger = WdpTouch.device
    val barH = if (finger) 38.dp else 30.dp
    val barSp = if (finger) 14.sp else 12.5.sp
    val phone = with(density) { fullW.toDp() } < 600.dp
    val share = if (phone) 0.99f else 0.96f
    val wPx = minOf(fullW * share, with(density) { w.maxW.dp.toPx() })
    val hPx = minOf(fullH * share, with(density) { w.maxH.dp.toPx() }) - with(density) { barH.toPx() }
    WindowFrame(
        w.title, barH, barSp,
        onClose = { WdpDialogs.closeTop() },
        onKey = { e -> if (e.key == Key.Escape) { WdpDialogs.closeTop(); true } else false },
        isTop = isTop,
        ground = Color.White,
    ) {
        Box(Modifier.size(with(density) { wPx.coerceAtLeast(1f).toDp() }, with(density) { hPx.coerceAtLeast(1f).toDp() })) { w.content() }
    }
}

/**
 * A message box, sized to what it says: the text wraps at a width that suits it and the box is as tall as the
 * text, at the page's scale (never smaller than it can be read); a text too long for the window is set smaller
 * until it fits, so there is never a scroll bar. Enter is its first button, Escape its last (as the × is).
 */
@Composable
private fun MessageWindow(m: WdpMessage, maxW: Float, maxH: Float, pageScale: Float, isTop: Boolean = true) {
    val density = LocalDensity.current
    val k = if (pageScale > 0f) pageScale else density.density
    val (barH, barSp) = titleBar(k)
    val measurer = rememberTextMeasurer()
    // by finger (WdpTouch): the text at 15 sp at least, and the buttons a finger's height
    val finger = WdpTouch.device
    // Windows' message text is 9 pt (12 px) at the page's scale, and never under 13 sp here
    val wantPx = maxOf(12f * 1.12f * k, with(density) { (if (finger) 14.sp else 13.sp).toPx() })
    val padH = with(density) { 16.dp.toPx() }
    val padV = with(density) { 12.dp.toPx() }
    val buttonsPx = with(density) { ((if (finger) 42.dp else 28.dp) + 20.dp).toPx() } + with(density) { barH.toPx() }
    // a message box is wide enough for about sixty characters, and no wider than the window
    val boxW = minOf(maxW, maxOf(with(density) { 300.dp.toPx() }, wantPx * 32f))
    val textW = (boxW - 2 * padH).coerceAtLeast(40f)
    val roomH = (maxH - buttonsPx - 2 * padV).coerceAtLeast(20f)
    val fontPx = remember(m, textW, roomH, wantPx) {
        var f = wantPx
        val floor = with(density) { 8.sp.toPx() }
        while (f > floor) {
            val r = measurer.measure(
                m.text, TextStyle(fontSize = with(density) { f.toSp() }),
                constraints = Constraints(maxWidth = textW.toInt()),
            )
            if (r.size.height <= roomH) break
            f -= 0.5f
        }
        f
    }
    val textSp = with(density) { fontPx.toSp() }
    // the text's own width where it is short: a two-word message is a small box, as in Windows
    val natural = remember(m, fontPx, textW) {
        measurer.measure(m.text, TextStyle(fontSize = textSp), constraints = Constraints(maxWidth = textW.toInt())).size.width.toFloat()
    }
    val buttonSp = maxOf(barSp.value, 12.5f).sp
    fun answer(b: String) { WdpDialogs.stack.remove(m); m.onAnswer?.invoke(b) }
    WindowFrame(
        m.title, barH, barSp,
        Modifier.widthIn(min = with(density) { minOf(boxW, 280.dp.toPx()).toDp() }),
        onClose = { WdpDialogs.closeTop() },
        onKey = { e ->
            when (e.key) {
                Key.Enter, Key.NumPadEnter -> { answer(m.buttons.getOrNull(m.default) ?: m.buttons.firstOrNull() ?: "OK"); true }
                Key.Escape -> { WdpDialogs.closeTop(); true }
                else -> false
            }
        },
        isTop = isTop,
    ) {
        Column(Modifier.background(Color.White).fillMaxWidth()) {
            Text(
                m.text, color = WindowLook.LIGHT_TITLE, fontSize = textSp, lineHeight = textSp * 1.25f,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    .width(with(density) { natural.coerceAtLeast(1f).toDp() + 1.dp }),
            )
        }
        // the buttons wrap onto a second line where they do not fit one (three long answers on a phone), right-aligned
        // as Windows lays them; where they fit, this is the one row it always was
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().background(Color(0xFFF1F3F6))
                .drawBehind { drawLine(Color.Black.copy(alpha = 0.08f), Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f) }
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            m.buttons.forEachIndexed { i, b ->
                MessageButton(b, first = i == m.default, size = buttonSp) { answer(b) }
            }
        }
    }
}
