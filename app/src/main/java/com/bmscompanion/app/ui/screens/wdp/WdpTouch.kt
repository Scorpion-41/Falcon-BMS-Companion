package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/**
 * **The Planner by finger.** WDP's pages are drawn to the pixel and fitted whole to the window, so on a phone a box
 * is a few millimetres tall and on a tablet not much more — and the page never scrolls, so the keyboard that comes up
 * for a box low on the page would cover it. None of that is changed: the page stays WDP's. What changes is what a
 * **finger** does on it (a mouse never changes anything, on any device):
 *
 * - **A box or a number box** tapped by a finger opens one bar on the keyboard ([WdpEditBarHost]): its name, the value
 *   with the caret, Next and Done, the page left exactly as it is (the keyboard never resizes the app, KeyboardLift).
 * - **A list, a slider, a grid** tapped by a finger opens a big editor for it, a sheet over the
 *   Planner ([WdpSheetHost]): a list to pick from,
 *   big − and + with the value, a big slider, the rows at a finger's height. What it sets goes through the same calls
 *   typing or clicking on the page makes ([WdpSheet]), so the page cannot tell them apart. A box already big enough
 *   to type in (a page zoomed right in) is typed in on the page.
 * - **A button, a check box, a radio button, a tab** keeps its drawing and gets a finger-sized reach: a finger that
 *   lands in the gap beside one — on the form's ground, in a group box, on a panel of controls — presses the nearest
 *   control within reach ([TouchTargets.resolve]). A finger on another control is that control's, as it always was.
 * - **A long press** on a knob (a picture or a panel WDP hears clicks on) is WDP's right button: the knob steps back.
 * - **A double tap** on the page away from its controls zooms in there, and a second one fits the page again.
 *
 * Which pointer is a finger is Compose's own `PointerType.Touch`, per press. [device] says the Planner is worked by
 * finger — Android, a browser on a phone or a tablet ([Platform.touchFirst]), or any device a finger has touched the
 * Planner on — and lays out the shell and the windows at a finger's size.
 */
object WdpTouch {
    /** A finger has touched the Planner on this device (a PC's touch screen, a browser that did not say it had one). */
    internal var seen by mutableStateOf(false)

    /** The Planner is laid out for fingers here: 48 dp targets, named icons, bigger type in its own windows. */
    val device: Boolean get() = Platform.touchFirst || seen

    /** The editor on show, or null. */
    internal var sheet by mutableStateOf<WdpSheet?>(null)

    /** How many [WdpSheetHost]s are drawn: without one a box is typed in on the page, as with a mouse. */
    internal var hosts = 0

    fun close() { sheet = null; ring = null; origin = null }

    /** Where on the page the box being typed in is (root pixels), ringed while its bar shows ([WdpEditBarHost]). */
    internal var ring by mutableStateOf<Rect?>(null)

    /** The control the editor on show was opened for, and its form's registry: what **Next** starts from. */
    internal var origin: SheetOrigin? = null

    /** Whether there is another box after [s]'s on its page for **Next** to go to. */
    internal fun hasNext(s: WdpSheet): Boolean = origin?.takeIf { it.target.key == s.key }?.let { nextAfter(it) } != null

    /** **Next**: the editor of the next box on the page, in reading order (what was typed is already committed). */
    internal fun next(s: WdpSheet) {
        val o = origin?.takeIf { it.target.key == s.key }
        close()
        val t = o?.let { nextAfter(it) } ?: return
        act(o.reg, t)
    }

    /** The next box a finger types in after [o]'s: further along its line, else the first on the lines below. */
    private fun nextAfter(o: SheetOrigin): TouchTarget? {
        val cur = o.target.rect
        val tol = maxOf(cur.height * 0.5f, 2f)
        val all = o.reg.targets.values.filter {
            it !== o.target && it.kind == TouchKind.EDIT && it.keys && it.sheet != null && it.rect.width > 0f && it.rect.height > 0f
        }
        // a box in a column of boxes its own size (a table: the DataCard's route, its airbases) goes down the column,
        // as Enter does in a table; anything else goes along the line
        all.filter {
            it.rect.top >= cur.bottom - tol && it.rect.top < cur.bottom + cur.height * 1.6f &&
                kotlin.math.abs(it.rect.left - cur.left) < tol && kotlin.math.abs(it.rect.width - cur.width) < cur.width * 0.15f
        }.minByOrNull { it.rect.top }?.let { return it }
        all.filter { kotlin.math.abs(it.rect.top - cur.top) < tol && it.rect.left > cur.left }.minByOrNull { it.rect.left }?.let { return it }
        val below = all.filter { it.rect.top >= cur.top + tol }
        val top = below.minOfOrNull { it.rect.top } ?: return null
        return below.filter { it.rect.top < top + tol }.minByOrNull { it.rect.left }
    }

    /** For the headless checks: lay the Planner out for fingers, as the first finger on a PC's touch screen does. */
    fun assume(on: Boolean) { seen = on }
}

/** The small editors a finger's tap on a small control opens; each sets the page through the page's own calls. */
internal sealed class WdpSheet(val title: String, val key: String) {
    /** A TextBox: OK sends the text as typing would (only if it changed) and then leaves the box ("<name>.leave"). */
    class Text(
        title: String, key: String,
        val initial: String,
        val numeric: Boolean,
        val multiline: Boolean,
        val commit: (String) -> Unit,
    ) : WdpSheet(title, key)

    /** A NumericUpDown: − and + are its arrows (at once, as on the page); OK takes a typed value as leaving the box does. */
    class Number(
        title: String, key: String,
        val value: () -> String,
        val step: (typed: String?, by: Int) -> Unit,
        val commit: (String) -> Unit,
    ) : WdpSheet(title, key)

    /** A ComboBox: a pick is the list's own pick; an editable one also takes typed text, as typing in it would. */
    class Pick(
        title: String, key: String,
        val items: () -> List<String>,
        val selected: () -> String,
        val pick: (String) -> Unit,
        /** the typed text's commit, for a combo WDP reads as typed ("<combo>.editable"); null for a plain list */
        val typed: ((String) -> Unit)?,
    ) : WdpSheet(title, key)

    /** A TrackBar: every move sets the page as dragging its thumb does. */
    class Range(
        title: String, key: String,
        val min: () -> Int,
        val max: () -> Int,
        val value: () -> Int,
        val set: (Int) -> Unit,
    ) : WdpSheet(title, key)

    /** A DataGridView or a ListBox: a row is selected as a click on it does; Open is its double click. */
    class Rows(
        title: String, key: String,
        val columns: () -> List<String>,
        val rows: () -> List<List<String>>,
        val selected: () -> Int,
        val select: (Int) -> Unit,
        val open: (Int) -> Unit,
    ) : WdpSheet(title, key)
}

/** What a control is to a finger ([TouchTargets.resolve]). */
internal enum class TouchKind {
    /** a button, a check box, a radio button, a tab, a list WDP steps itself: pressed, with a finger-sized reach */
    PRESS,
    /** a box, a list, a number box, a slider, a grid: a tap opens its editor (while it is small), with a reach */
    EDIT,
    /** a label, a panel or a picture WDP hears clicks on: its own, a long press is the right button */
    TAP,
    /** anything else: a finger on it is the control's (nothing), never another's */
    BLOCK,
}

/** One control as a finger sees it, kept up to date by the control as it is drawn ([touchTarget]). */
internal class TouchTarget(val key: String, val name: String) {
    var kind = TouchKind.BLOCK
    /** where it is, in root pixels */
    var rect = Rect.Zero
    /** its place in the page's stacking: higher is nearer the top (WdpForm's zStep) */
    var z = ""
    /** a panel, a group, a tab page: a finger in its gaps may be meant for a control in it */
    var container = false
    /** a finger's tap: the control's own click (PRESS), or what WDP hears on a tap on a box before it takes the keys */
    var tap: (() -> Unit)? = null
    /** the editor a tap opens (EDIT), given the title it should carry */
    var sheet: ((String) -> WdpSheet?)? = null
    /** the control's own drags (a slider's thumb, a list that scrolls): a finger that moves is left to it */
    var drags = false
    /** a box typed in (a TextBox, a NumericUpDown): a finger's tap always opens its bar on the keyboard, whatever its size */
    var keys = false
    /**
     * The size that says whether it is small for a finger, in root pixels: a grid's or a list's row; 0 for the control's
     * own height (or width, if that is shorter).
     */
    var unit = 0f
    /** WDP's right button, for a long press (a knob steps back) */
    var right: (() -> Unit)? = null
    /** its tooltip ([WdpTips]), which a long press shows where it is not the right button or a box's own */
    var tip: String? = null
}

/** A label's caption or a group box's, which names the box a finger opens an editor for. */
internal class TouchCaption(var rect: Rect, var text: String, val group: Boolean)

/** How a finger's tap is taken: left to the page, or taken and acted on here. */
internal enum class TouchHow { PASS, PAGE, ACT }

/** Every control of one form as a finger sees it ([WdpFormView] keeps one while [WdpTouch.device]). */
internal class TouchTargets {
    val targets = LinkedHashMap<String, TouchTarget>()
    val captions = LinkedHashMap<String, TouchCaption>()

    /**
     * What a finger landing at [p] (root pixels) is for.
     *
     * The control under it — the top one, as the page stacks them — is the finger's, as under a mouse: a button
     * pressed, a label's click;
     * a small box, list or slider opens its editor ([TouchHow.ACT]). Only where the finger lands in a gap — on the
     * form's ground, or on a panel, a group or a tab page that holds controls — is it the nearest control whose reach
     * covers it ([reach]: a finger's 48 dp in each direction it is shorter than that, and a few dp more). A tap on a
     * label, a picture or the ground is also counted towards a double tap ([TouchHow.PAGE]).
     */
    fun resolve(p: Offset, minTouch: Float, pad: Float, small: Float): Pair<TouchTarget?, TouchHow> {
        var direct: TouchTarget? = null
        for (t in targets.values) {
            if (!t.rect.contains(p)) continue
            val d = direct
            if (d == null || t.z > d.z || t.z == d.z && t.rect.width * t.rect.height < d.rect.width * d.rect.height) direct = t
        }
        val gap = direct == null || direct.container && targets.values.any { it !== direct && live(it) && direct.rect.contains(it.rect.center) }
        if (direct != null && !gap) {
            return direct to when (direct.kind) {
                // a box typed in is always typed in the bar on the keyboard: typed on the page, the keyboard would cover it
                TouchKind.EDIT -> if (direct.keys || (if (direct.unit > 0f) direct.unit else minOf(direct.rect.height, direct.rect.width)) < small) TouchHow.ACT else TouchHow.PASS
                TouchKind.TAP -> TouchHow.PAGE
                else -> TouchHow.PASS
            }
        }
        var best: TouchTarget? = null
        var bestD = Float.MAX_VALUE
        for (t in targets.values) {
            if (!live(t) || t.rect.width <= 0f || t.rect.height <= 0f) continue
            if (!reach(t.rect, minTouch, pad).contains(p)) continue
            val d = distance(p, t.rect)
            if (d < bestD) { bestD = d; best = t }
        }
        if (best != null) return best to TouchHow.ACT
        return direct to TouchHow.PAGE
    }

    private fun live(t: TouchTarget) = t.kind == TouchKind.PRESS || t.kind == TouchKind.EDIT

    /**
     * The name a box's editor carries: the label to its left on the same line, or the one over it, and the group
     * box it sits in; the control's own name made readable where the page has no label for it (the DataCard's cells).
     */
    fun captionFor(t: TouchTarget): String {
        val r = t.rect
        val h = r.height.coerceAtLeast(1f)
        var left: TouchCaption? = null
        var leftD = Float.MAX_VALUE
        var above: TouchCaption? = null
        var aboveD = Float.MAX_VALUE
        for (c in captions.values) {
            if (c.group) continue
            val cr = c.rect
            val overlapV = minOf(cr.bottom, r.bottom) - maxOf(cr.top, r.top)
            if (cr.right <= r.left + h * 0.5f && overlapV > minOf(cr.height, h) * 0.4f) {
                val d = r.left - cr.right
                if (d < h * 9f && d < leftD) { leftD = d; left = c }
            }
            val overlapH = minOf(cr.right, r.right) - maxOf(cr.left, r.left)
            if (cr.bottom <= r.top + h * 0.5f && overlapH > 0f) {
                val d = r.top - cr.bottom
                if (d < h * 2.5f && d < aboveD) { aboveD = d; above = c }
            }
        }
        var label = (left ?: above)?.text
        // a cell of a table (the DataCard's route, its airbases): its column's heading, further up over the rows, with
        // the cell's own name for which row it is ("Min.Fuel · Fuel 1")
        if (label == null) {
            val head = captions.values.filter { c ->
                !c.group && c.rect.bottom <= r.top + h * 0.5f && r.top - c.rect.bottom < h * 30f &&
                    minOf(c.rect.right, r.right) - maxOf(c.rect.left, r.left) > minOf(c.rect.width, r.width) * 0.5f
            }.minByOrNull { r.top - it.rect.bottom }
            if (head != null) label = head.text + " · " + readableName(t.name)
        }
        // no label on the page (the DataCard's headings are part of its picture): the first words of its tooltip
        // ("Minimum fuel (lb) at steerpoint 2"), which name it better than its designer name does
        if (label == null) label = t.tip?.let { tip ->
            val cut = listOf(", ", ". ", ": ", " — ", "; ").mapNotNull { s -> tip.indexOf(s).takeIf { it > 0 } }.minOrNull() ?: tip.length
            tip.substring(0, cut).trim().takeIf { it.length in 3..60 }
        }
        val group = captions.values.filter { it.group && it.rect.contains(r.center) }.minByOrNull { it.rect.width * it.rect.height }?.text
        return listOfNotNull(group, label ?: readableName(t.name)).distinct().joinToString(" · ")
    }

    companion object {
        /** A control's rectangle grown to a finger: 48 dp ([minTouch]) where it is shorter, and [pad] all round. */
        fun reach(r: Rect, minTouch: Float, pad: Float): Rect {
            val gx = maxOf(pad, (minTouch - r.width) / 2f)
            val gy = maxOf(pad, (minTouch - r.height) / 2f)
            return Rect(r.left - gx, r.top - gy, r.right + gx, r.bottom + gy)
        }

        fun distance(p: Offset, r: Rect): Float {
            val dx = maxOf(r.left - p.x, 0f, p.x - r.right)
            val dy = maxOf(r.top - p.y, 0f, p.y - r.bottom)
            return sqrt(dx * dx + dy * dy)
        }
    }
}

/** "txtTgtElev" → "Tgt Elev", "nudWPT1Alt" → "WPT1 Alt": a control's designer name as words. */
internal fun readableName(name: String): String {
    val s = name.substringBefore(':')
    val cut = s.indexOfFirst { it.isUpperCase() || it.isDigit() }
    val base = if (cut in 1..4 && s.substring(0, cut).all { it.isLowerCase() }) s.substring(cut) else s
    val out = StringBuilder()
    for ((i, ch) in base.withIndex()) {
        if (ch == '_') { out.append(' '); continue }
        val prev = if (i > 0) base[i - 1] else ' '
        val next = if (i + 1 < base.length) base[i + 1] else ' '
        if (i > 0 && ch.isUpperCase() && (prev.isLowerCase() || prev.isUpperCase() && next.isLowerCase())) out.append(' ')
        out.append(ch)
    }
    return out.toString().replace(Regex(" +"), " ").trim().ifEmpty { name }
}

/** A text that is a number as a pilot types it: the digits keyboard, where the page's box holds one. */
internal fun looksNumeric(s: String): Boolean {
    val t = s.trim()
    return t.isNotEmpty() && t.any { it.isDigit() } && t.all { it.isDigit() || it == '.' || it == '-' || it == '+' }
}

/** The form's [TouchTargets] while the Planner is worked by finger; null otherwise (and always on paper). */
internal val LocalWdpTouchTargets = staticCompositionLocalOf<TouchTargets?> { null }

/**
 * Keeps [key] in the form's [TouchTargets] while it is drawn: where it is, and [setup] run on every drawing so its
 * actions are the page's latest. Nothing while the Planner is not worked by finger ([reg] null). A control that goes
 * takes an editor open for it with it.
 */
@Composable
internal fun Modifier.touchTarget(reg: TouchTargets?, key: String, name: String, setup: TouchTarget.() -> Unit): Modifier {
    if (reg == null) return this
    val t = remember(reg, key) { TouchTarget(key, name) }
    SideEffect { t.setup() }
    DisposableEffect(reg, key) {
        reg.targets[key] = t
        onDispose {
            if (reg.targets[key] === t) reg.targets.remove(key)
            if (WdpTouch.sheet?.key == key) WdpTouch.close()
        }
    }
    return onGloballyPositioned { t.rect = it.boundsInRoot() }
}

/** Keeps a label's or a group's caption in the form's [TouchTargets], to name the boxes beside it. */
@Composable
internal fun Modifier.touchCaption(reg: TouchTargets?, key: String, text: String, group: Boolean): Modifier {
    if (reg == null || text.isBlank()) return this
    val c = remember(reg, key) { TouchCaption(Rect.Zero, text, group) }
    c.text = text
    DisposableEffect(reg, key) {
        reg.captions[key] = c
        onDispose { if (reg.captions[key] === c) reg.captions.remove(key) }
    }
    return onGloballyPositioned { c.rect = it.boundsInRoot() }
}

/**
 * A finger on a form ([WdpFormView]): looks at each finger's press on its way down (before the controls), decides
 * what it is for ([TouchTargets.resolve]) and, where the tap is taken here, takes its lift so that the control under
 * it does nothing of its own, then acts: an editor, or the press of the control the finger was meant for. A finger
 * that moves (a slider dragged, a list scrolled) or is joined by another (a pinch) is left alone. A mouse, a pen and
 * a finger on a form with no [WdpSheetHost] to show an editor are never touched.
 *
 * [origin] is the form's top left in root pixels; [zoom] the form's pinch zoom, which a double tap on the page away
 * from its controls zooms in ([WdpZoom.zoomAt]) and a second double tap fits again; [fit] the scale the page is
 * fitted at, in pixels per designer pixel, and [view] the size of the box it is drawn in.
 */
internal fun Modifier.touchResolver(
    reg: TouchTargets,
    origin: FloatArray,
    zoom: WdpZoom?,
    fit: () -> Float,
    view: () -> Pair<Float, Float>,
    page: () -> Pair<Float, Float>,
): Modifier = pointerInput(reg, zoom) {
    var lastTap = 0L
    var lastAt = Offset.Zero
    // the double tap's zoom glides (WdpZoom.glideTo) beside the gesture loop, which goes on following fingers
    val glides = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext())
    var glide: kotlinx.coroutines.Job? = null
    // Only fingers are followed, each gesture from its first finger's press to its lift. Not awaitEachGesture, which
    // waits for every pointer to be up between gestures: a mouse's press whose release this form never saw (a window
    // it opened took it) would have kept it waiting, and the next finger would not have been seen at all.
    awaitPointerEventScope {
      while (true) {
        val first = awaitPointerEvent(PointerEventPass.Initial)
        if (first.type != PointerEventType.Press) continue
        val down = first.changes.firstOrNull { it.type == PointerType.Touch && it.changedToDownIgnoreConsumed() } ?: continue
        // a second finger joining is a pinch's, never a tap of its own
        if (first.changes.any { it.id != down.id && it.pressed && it.previousPressed }) continue
        if (!WdpTouch.seen && !Platform.touchFirst) WdpTouch.seen = true
        if (WdpTouch.sheet != null) continue
        val at = Offset(origin[0], origin[1]) + down.position
        val (target, how0) = reg.resolve(at, 48.dp.toPx(), 6.dp.toPx(), 40.dp.toPx())
        // an editor needs somewhere to be drawn; without one the box is typed in on the page
        val how = if (how0 == TouchHow.ACT && target?.kind == TouchKind.EDIT && WdpTouch.hosts == 0) TouchHow.PASS else how0
        if (WdpProbe.on) WdpProbe.note("finger ${target?.key} ${target?.kind} $how (targets ${reg.targets.size})")
        val right = target?.right?.takeIf { how == TouchHow.PAGE && target.kind == TouchKind.TAP }
        val slop = viewConfiguration.touchSlop
        var moved = false
        var multi = false
        var longDone = false
        var lift: androidx.compose.ui.input.pointer.PointerInputChange? = null
        // one event of the gesture: whether another finger joined or this one moved, and its lift
        fun take(e: androidx.compose.ui.input.pointer.PointerEvent) {
            if (e.changes.any { it.id != down.id && it.type == PointerType.Touch && it.pressed }) multi = true
            val ch = e.changes.firstOrNull { it.id == down.id } ?: return
            if ((ch.position - down.position).getDistance() > slop) moved = true
            if (!ch.pressed) lift = ch
        }
        // a long press on a control with a tooltip shows its tip (WdpTips), where a long press means nothing else: not
        // on a knob (WDP's right button), a box big enough to type in on the page (its own long press selects text),
        // or anything that drags
        val tip = target?.let { t -> t.tip?.takeIf { right == null && WdpTips.on && !t.drags && !(t.kind == TouchKind.EDIT && how == TouchHow.PASS) } }
        if (right != null || tip != null) {
            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (lift == null && !moved && !multi) take(awaitPointerEvent(PointerEventPass.Initial))
                true
            }
            if (ended == null) {
                // held still: WDP's right button on a knob, or the tip; what the finger does after it is nothing
                longDone = true
                if (right != null) right() else if (tip != null) WdpTips.pop(tip, at)
            }
        }
        while (lift == null) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (longDone) e.changes.forEach { it.consume() }
            take(e)
        }
        val up = lift ?: continue
        if (multi || longDone) { lastTap = 0L; continue }
        // a finger that moved was a drag (a slider's thumb, a list scrolled, a page panned), never a tap
        if (moved) { lastTap = 0L; continue }
        when (how) {
            TouchHow.ACT -> {
                val t = target ?: continue
                up.consume()
                lastTap = 0L
                act(reg, t)
            }
            TouchHow.PAGE -> {
                // a double tap away from the controls: in to where it landed, or back to the whole page
                val z = zoom ?: continue
                val now = up.uptimeMillis
                val near = (at - lastAt).getDistance() < 40.dp.toPx()
                if (lastTap > 0L && now - lastTap <= viewConfiguration.doubleTapTimeoutMillis + 120 && near) {
                    up.consume()
                    lastTap = 0L
                    val (vw, vh) = view()
                    val (cw, ch) = page()
                    // enough that a WDP box (20 px) is a finger's width, 2x at least, never past the pinch's limit;
                    // zoomed already, the whole page again
                    val perDp = fit() / density
                    val goal = if (z.zoomed) 1f else (1.45f / perDp.coerceAtLeast(0.05f)).coerceIn(2f, WdpZoom.MAX)
                    if (glide?.isActive != true) {
                        val q = down.position
                        glide = glides.launch { z.glideTo(q, goal, vw, vh, cw, ch) }
                    }
                } else { lastTap = now; lastAt = at }
            }
            TouchHow.PASS -> lastTap = 0L
        }
      }
    }
}

/** A tap taken for [t]: its press, or what WDP hears on the box and then its editor. */
private fun act(reg: TouchTargets, t: TouchTarget) {
    when (t.kind) {
        TouchKind.EDIT -> {
            val before = WdpDialogs.stack.size
            val windowBefore = PlannerWindows.open
            // WDP hangs Click handlers on some boxes (the DataCard's formation boxes open their window): a tap on one
            // still does that, and a window opened by it is the editor, not ours
            t.tap?.invoke()
            if (WdpDialogs.stack.size != before || PlannerWindows.open != windowBefore) return
            val s = t.sheet?.invoke(reg.captionFor(t))
            WdpTouch.sheet = s
            if (s != null) { WdpTouch.origin = SheetOrigin(reg, t); WdpTouch.ring = t.rect }
        }
        else -> t.tap?.invoke()
    }
}

// ==================================================================================================== the sheets

/** The sheet's colours: the app's own, as the Planner's windows are. */
private val SheetShape = RoundedCornerShape(14.dp)

/**
 * Where the editors are drawn: over the Planner and over WDP's windows (a box in a window opens one too), with the
 * app's look. On a phone a sheet up from the bottom, full width, over the keyboard; on a tablet a dialog of a
 * comfortable width. A press outside it, Escape or Cancel closes it without changing anything.
 */
@Composable
fun WdpSheetHost(modifier: Modifier = Modifier) {
    DisposableEffect(Unit) { WdpTouch.hosts++; onDispose { WdpTouch.hosts-- } }
    val s = WdpTouch.sheet ?: return
    // a box typed in: one bar on the keyboard, the page left as it is (WdpEditBar.kt)
    if (s is WdpSheet.Text || s is WdpSheet.Number) { WdpEditBarHost(s, modifier); return }
    // (no keyboard padding: the keyboard is laid over the app, and an input in a list's sheet lifts it, KeyboardLift)
    BoxWithConstraints(
        modifier.fillMaxSize().background(Color(0x99000000))
            .pointerInput(Unit) { detectTapGestures { WdpTouch.close() } },
        contentAlignment = Alignment.Center,
    ) {
        // a phone, either way up: a sheet up from the bottom (over the keyboard), the whole width of one held upright
        val phone = maxWidth < 600.dp || maxHeight < 480.dp
        val shape = if (phone) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) else SheetShape
        Column(
            Modifier.align(if (phone) Alignment.BottomCenter else Alignment.Center)
                .then(if (maxWidth < 600.dp) Modifier.fillMaxWidth() else Modifier.width(minOf(if (phone) 640.dp else 560.dp, maxWidth * 0.86f)))
                .heightIn(max = maxHeight * if (phone) 0.96f else 0.88f)
                .clip(shape).background(Hud.Surface).border(1.dp, Hud.Outline, shape)
                // a press on the sheet is the sheet's, never the scrim's
                .pointerInput(Unit) { detectTapGestures { } }
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { WdpTouch.close(); true } else false }
                .plannerProbe("Sheet")
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.title, color = Hud.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).plannerPress { WdpTouch.close() }.plannerProbe("Sheet/Close"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Close, "Close", tint = Hud.TextDim, modifier = Modifier.size(22.dp)) }
            }
            // the control's tooltip (WdpTips), which a finger has no mouse to rest on it for
            WdpTips.forKey(s.key)?.takeIf { WdpTips.on }?.let { tip ->
                Text(tip, color = Hud.TextDim, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.plannerProbe("Sheet/Tip"))
            }
            when (s) {
                is WdpSheet.Text -> TextSheet(s)
                is WdpSheet.Number -> NumberSheet(s)
                is WdpSheet.Pick -> PickSheet(s)
                is WdpSheet.Range -> RangeSheet(s)
                is WdpSheet.Rows -> RowsSheet(s)
            }
        }
    }
}

/** The big input every editor with typing shares: 20 sp, the keyboard asked for, Done as OK. */
@Composable
private fun BigInput(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    numeric: Boolean,
    multiline: Boolean,
    probe: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) {
    val focus = remember { FocusRequester() }
    val keys = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus(); keys?.show() } }
    val shape = RoundedCornerShape(10.dp)
    val style = TextStyle(color = Hud.Text, fontSize = 20.sp, textAlign = align)
    // one line has its figures on the middle of the box, as the page's boxes do ([lineAt]): the device's own line box
    // put them low on the PC and high on a phone
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val mid = if (multiline) null else remember(value.text, measurer, density) { lineMidOf(measurer, value.text, style, density) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = !multiline,
        textStyle = style,
        cursorBrush = SolidColor(Hud.Amber),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp, max = if (multiline) 220.dp else 56.dp)
            .clip(shape).background(Hud.Bg).border(1.5.dp, Hud.Amber.copy(alpha = 0.7f), shape)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (!multiline && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { onDone(); true } else false
            }
            .plannerProbe(probe)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        // (the box's width handed on to the line, so a centred number stays centred)
        decorationBox = { inner ->
            if (mid == null) inner() else Box(Modifier.fillMaxWidth().lineAt(mid) { it / 2f }, propagateMinConstraints = true) { inner() }
        },
    )
}

/** Cancel and OK, a finger's height, side by side. */
@Composable
private fun SheetButtons(onOk: () -> Unit, okLabel: String = "OK", cancel: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (cancel) SheetButton("Cancel", primary = false, probe = "Sheet/Cancel", modifier = Modifier.weight(1f)) { WdpTouch.close() }
        SheetButton(okLabel, primary = true, probe = "Sheet/OK", modifier = Modifier.weight(1f)) { onOk() }
    }
}

@Composable
private fun SheetButton(label: String, primary: Boolean, probe: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(52.dp).clip(shape).background(if (primary) Hud.Amber else Hud.Surface3)
            .plannerPress { onClick() }.plannerProbe(probe),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) Hud.Bg else Hud.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun TextSheet(s: WdpSheet.Text) {
    var v by remember(s) { mutableStateOf(TextFieldValue(s.initial, TextRange(0, s.initial.length))) }
    fun ok() { WdpTouch.close(); s.commit(v.text) }
    BigInput(v, { v = it }, s.numeric, s.multiline, "Sheet/Input", onDone = ::ok)
    SheetButtons(::ok)
}

@Composable
private fun NumberSheet(s: WdpSheet.Number) {
    val shown = s.value()
    // what is typed, until the page's value moves (an arrow, the wiring): then the page's
    var typed by remember(s) { mutableStateOf<TextFieldValue?>(null) }
    // the page's value selected, so the first digit typed replaces it
    val v = typed ?: TextFieldValue(shown, TextRange(0, shown.length))
    fun ok() { WdpTouch.close(); typed?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { s.commit(it) } }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", "Sheet/Minus") { s.step(typed?.text?.trim()?.takeIf { it.isNotEmpty() }, -1); typed = null }
        BigInput(
            v, { t -> typed = t.copy(text = t.text.filter { it.isDigit() || it == '.' || it == '-' }) },
            numeric = true, multiline = false, probe = "Sheet/Input", onDone = ::ok, modifier = Modifier.weight(1f), align = TextAlign.Center,
        )
        StepButton("+", "Sheet/Plus") { s.step(typed?.text?.trim()?.takeIf { it.isNotEmpty() }, 1); typed = null }
    }
    SheetButtons(::ok)
}

@Composable
private fun StepButton(label: String, probe: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(64.dp, 56.dp).clip(shape).background(Hud.Surface3).plannerPress { onClick() }.plannerProbe(probe),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Hud.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun PickSheet(s: WdpSheet.Pick) {
    val items = s.items()
    val sel = s.selected()
    var typed by remember(s) { mutableStateOf(TextFieldValue(sel, TextRange(0, sel.length))) }
    val commit = s.typed
    if (commit != null) {
        fun ok() { WdpTouch.close(); commit(typed.text) }
        BigInput(typed, { typed = it }, looksNumeric(sel), false, "Sheet/Input", onDone = ::ok)
        SheetButtons(::ok)
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = items.indexOf(sel).coerceAtLeast(0).let { (it - 2).coerceAtLeast(0) })
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Bg),
        state = list,
    ) {
        itemsIndexed(items) { i, item ->
            val on = item == sel
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).background(if (on) Hud.Amber.copy(alpha = 0.14f) else Color.Transparent)
                    .plannerPress { WdpTouch.close(); s.pick(item) }.plannerProbe("Sheet/Item/$i")
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.ifEmpty { "(none)" }, color = if (on) Hud.Amber else Hud.Text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                if (on) Icon(Icons.Default.Check, "Selected", tint = Hud.Amber, modifier = Modifier.size(22.dp))
            }
            if (i < items.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Outline.copy(alpha = 0.5f)))
        }
    }
}

@Composable
private fun RangeSheet(s: WdpSheet.Range) {
    val min = s.min()
    val max = s.max().coerceAtLeast(min + 1)
    val value = s.value().coerceIn(min, max)
    // the thumb follows the finger at once; the page is set to the whole number under it, as its own track does
    var at by remember(s) { mutableStateOf<Float?>(null) }
    Text(value.toString(), color = Hud.Text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", "Sheet/Minus") { s.set((value - 1).coerceIn(min, max)) }
        Slider(
            value = at ?: value.toFloat(),
            onValueChange = { f -> at = f; val n = f.roundToInt().coerceIn(min, max); if (n != s.value()) s.set(n) },
            onValueChangeFinished = { at = null },
            valueRange = min.toFloat()..max.toFloat(),
            colors = SliderDefaults.colors(thumbColor = Hud.Amber, activeTrackColor = Hud.Amber, inactiveTrackColor = Hud.Outline),
            modifier = Modifier.weight(1f).height(56.dp).plannerProbe("Sheet/Slider"),
        )
        StepButton("+", "Sheet/Plus") { s.set((value + 1).coerceIn(min, max)) }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(min.toString(), color = Hud.TextDim, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(max.toString(), color = Hud.TextDim, fontSize = 13.sp)
    }
    SheetButtons({ WdpTouch.close() }, okLabel = "Done", cancel = false)
}

@Composable
private fun RowsSheet(s: WdpSheet.Rows) {
    val cols = s.columns()
    val rows = s.rows()
    val sel = s.selected()
    if (cols.isNotEmpty()) Text(cols.joinToString("  ·  "), color = Hud.TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (rows.isEmpty()) Text("Nothing in this list yet.", color = Hud.TextDim, fontSize = 15.sp)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (sel - 2).coerceAtLeast(0))
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Bg), state = list) {
        itemsIndexed(rows) { i, cells ->
            val on = i == sel
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).background(if (on) Hud.Amber.copy(alpha = 0.14f) else Color.Transparent),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    cells.filter { it.isNotBlank() }.joinToString("  ·  ").ifEmpty { "(empty row)" },
                    Modifier.weight(1f).heightIn(min = 52.dp).plannerPress { WdpTouch.close(); s.select(i) }.plannerProbe("Sheet/Row/$i")
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    color = if (on) Hud.Amber else Hud.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                // WDP's double click on a row: the row's own window (Change, Loadout…), where the list has one
                Box(
                    Modifier.widthIn(min = 72.dp).height(52.dp).plannerPress { WdpTouch.close(); s.open(i) }.plannerProbe("Sheet/Open/$i"),
                    contentAlignment = Alignment.Center,
                ) { Text("Open ›", color = Hud.Cyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
            if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Outline.copy(alpha = 0.5f)))
        }
    }
    SheetButtons({ WdpTouch.close() }, okLabel = "Close", cancel = false)
}
