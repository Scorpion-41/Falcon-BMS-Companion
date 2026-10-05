package com.bmscompanion.app.ui.screens.wdp

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.bmscompanion.app.data.Repo
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.abs
import kotlin.math.roundToLong
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.bmscompanion.app.data.wdp.WdpControl
import com.bmscompanion.app.data.wdp.WdpForm
import com.bmscompanion.app.data.wdp.WdpValues
import com.bmscompanion.app.ui.theme.Hud
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlin.math.roundToInt

/**
 * Draws a Weapon Delivery Planner page from its layout.
 *
 * **The page is Falcas's design**, read out of his own designer code (see `WdpLayout`); this only paints it. One
 * renderer covers every page in the program, which is why porting the rest is a matter of wiring behaviour rather
 * than rebuilding forms.
 *
 * **It scales rather than reflows.** WDP's pages are laid out to the pixel — labels sit against the boxes they
 * name and columns line up by coordinate — so a page is drawn at its own size and scaled to fit the device. A
 * reflowed version would be a different page that happened to contain the same fields, and a pilot who knows where
 * a number lives would have to learn it again. On a phone it scales down; on a tablet or the PC it is near life size.
 *
 * **It looks like WDP by default.** The point of porting the program rather than re-imagining it is that someone
 * who has used it for years sits down and knows the page: Windows grey, white fields, black ink, the same boxes in
 * the same places. So the designer's own colours are honoured, with the Windows control colour under them. The
 * app's dark HUD is the alternative ([WdpLook.HUD]), for a tablet in a dark cockpit — same geometry, different light.
 *
 * Three Windows Forms behaviours had to be reproduced before the pages read right, and each is worth knowing:
 *
 * - **Colours inherit.** A label with no ForeColor of its own takes its parent's, and WDP's dark panels carry white
 *   text that way — none of the labels on the TOSS page set a colour themselves. Without the cascade they came out
 *   black on charcoal.
 * - **Text sits at the top of its box and is clipped to it.** Labels default to TopLeft, and a control never paints
 *   outside its rectangle. Compose centres text and lets it overflow, so an 11 px font in a 14 px box spilled onto
 *   the control beneath. Every control clips to its bounds, and a label's text is top-aligned unless the designer
 *   said otherwise.
 * - **A point is 1.33 px.** Windows fonts are in points; the boxes are in pixels. The type is sized from the smaller
 *   of the font's own height and what the box can hold, with the line's leading trimmed so the glyphs, not the
 *   line, are what has to fit.
 */
enum class WdpLook { WDP, HUD }

@Composable
fun WdpFormView(
    form: WdpForm,
    values: WdpValues,
    modifier: Modifier = Modifier,
    look: WdpLook = WdpLook.WDP,
    /**
     * Fill the width and let the height follow, for the headless pictures of a whole page (`--wdprender`). The
     * Planner never uses it: a page there is fitted whole into the room it has, and never scrolls.
     */
    fitWidth: Boolean = false,
    /**
     * Draw at this many pixels per designer pixel instead of fitting: a child window drawn at the scale of the page
     * behind it ([WdpDialogHost] works out the number, shrinking it only when the window would not fit).
     */
    fixedScale: Float? = null,
    /** The page's pinch zoom, where the page offers one ([WdpZoom]); null for none. */
    zoom: WdpZoom? = null,
    /** Hears the scale the form is drawn at, in pixels per designer pixel. */
    onScale: ((Float) -> Unit)? = null,
    /** null while a page is read-only; the name of the control and its new value otherwise */
    onValue: ((String, String) -> Unit)? = null,
    onClick: ((String) -> Unit)? = null,
    /**
     * Control name → something of the wiring's own drawn inside that control's box, over the designer's drawing of
     * it: what WDP paints into a picture box at runtime (the attack pages' map). See [WdpControlContent].
     */
    content: Map<String, @Composable () -> Unit> = emptyMap(),
) {
    val w = form.width
    val h = form.height
    if (w <= 0 || h <= 0) return
    val p = paletteOf(look)
    // Each control reads the values through its own subscription (WdpLiveValues), and is handed callbacks and the
    // wiring's own drawings that stay the same objects: a new set of values, a new lambda or a new content map from
    // the page's wiring (it builds them afresh on every recomposition) recomposed every control on the page — over a
    // thousand on the DataCard — for a change to one box, or for none at all.
    val liveValues = remember(form) { WdpLiveValues(form) }
    liveValues.update(values)
    SideEffect { liveValues.flush() }
    val valueNow = androidx.compose.runtime.rememberUpdatedState(onValue)
    val clickNow = androidx.compose.runtime.rememberUpdatedState(onClick)
    val onValueOut: ((String, String) -> Unit)? = if (onValue == null) null else remember { { n: String, v: String -> valueNow.value?.invoke(n, v) } }
    val onClickOut: ((String) -> Unit)? = if (onClick == null) null else remember { { n: String -> clickNow.value?.invoke(n) } }
    val contentNow = androidx.compose.runtime.rememberUpdatedState(content)
    val contentOut: Map<String, @Composable () -> Unit> = remember(content.keys) {
        content.keys.associateWith { k -> val draw: @Composable () -> Unit = { contentNow.value[k]?.invoke() }; draw }
    }
    // the box being typed in, so that a press anywhere else on the form leaves it first (see [WdpTyping])
    val typing = remember { WdpTyping() }
    val focusManager = LocalFocusManager.current
    val origin = remember { floatArrayOf(0f, 0f) }
    // one measurer for every control of the form, which sets a value smaller where it is wider than its box ([fit])
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 256)
    // a finger on the page (WdpTouch): what each control is to a finger, and the box's geometry for a double tap
    val fingers = remember { TouchTargets() }
    val geo = remember { FloatArray(5) }
    BoxWithConstraints(
        modifier
            .onGloballyPositioned { val o = it.positionInRoot(); origin[0] = o.x; origin[1] = o.y }
            // Windows moves the focus to whatever is clicked, so a box's Leave runs before the button's Click: the
            // DTC page commits a typed steerpoint on Leave, and Save pressed straight after typing must see it.
            // Compose moves the focus only to what takes it, so the press is looked at on its way down.
            .pointerInput(typing) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type != PointerEventType.Press) continue
                        val leave = typing.leave ?: continue
                        val at = e.changes.firstOrNull()?.position ?: continue
                        if (!typing.rect().contains(Offset(origin[0] + at.x, origin[1] + at.y))) { leave(); focusManager.clearFocus() }
                    }
                }
            }
            // a finger's tap: its editor, or a finger-sized reach round the small controls; a mouse passes untouched
            .touchResolver(fingers, origin, zoom, fit = { geo[4] }, view = { geo[0] to geo[1] }, page = { geo[2] to geo[3] }),
    ) {
        val availW = constraints.maxWidth.toFloat()
        // fitted whole: the page is never taller than the room it has, however the window is shaped, and it has no
        // upper limit either, so it keeps growing with the window (the type is laid out at the scale, not stretched)
        val availH = if (fitWidth || !constraints.hasBoundedHeight) Float.MAX_VALUE else constraints.maxHeight.toFloat()
        val fit = fixedScale ?: minOf(availW / w, availH / h)
        val scale = fit * (zoom?.zoom ?: 1f)
        val cw = w * scale
        val ch = h * scale
        val viewW = availW
        val viewH = if (availH == Float.MAX_VALUE) ch else availH
        if (onScale != null) SideEffect { onScale(scale) }
        SideEffect { geo[0] = viewW; geo[1] = viewH; geo[2] = cw; geo[3] = ch; geo[4] = fit }
        if (WdpProbe.on) SideEffect { WdpProbe.scales[form.form] = scale }
        // The form's own colours where the designer set them — most of WDP's windows are charcoal with white ink
        // set once on the form, which every control then inherits — and its own picture under everything (the Mil
        // Codes window is nothing but its picture).
        val dark = remember(form, p.modern) { darkPage(form, p) }
        val page = surfaceOf(WdpControl(bg = form.bg), p)?.takeIf { it.alpha > 0f } ?: if (dark) Modern.PAGE_DARK else p.page
        val ink = colorOf(form.fg, p) ?: p.ink
        val at = zoom?.placed(viewW, viewH, cw, ch) ?: Offset((viewW - cw) / 2f, if (fitWidth) 0f else (viewH - ch) / 2f)
        val live = zoom?.live
        Box(
            Modifier.fillMaxWidth().height(px(viewH))
                .then(if (zoom != null) Modifier.clipToBounds().pinchZoom(zoom, geo) else Modifier),
        ) {
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val pl = measurable.measure(Constraints.fixed(cw.roundToInt().coerceAtLeast(1), ch.roundToInt().coerceAtLeast(1)))
                        layout(viewW.roundToInt(), viewH.roundToInt()) { pl.place(at.x.roundToInt(), at.y.roundToInt()) }
                    }
                    // during a pinch the page as laid out is moved and scaled as a picture; it is laid out again at
                    // the new size when the fingers lift, so the type is sharp whenever the page is still
                    .then(
                        if (live == null) Modifier else Modifier.graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = live.first; scaleY = live.first
                            translationX = live.second.x - at.x; translationY = live.second.y - at.y
                        },
                    ),
            ) {
                // On a finger's device (a tablet, a phone) the page is kept as a picture of its own while it is no
                // bigger than its box: a frame that changes nothing on it (the caret in the editor bar, the toolbar, a
                // window over it) is one picture drawn, not every control of a thousand drawn again — 20-28 ms a frame
                // on a 120 Hz tablet before. Zoomed in, it is far bigger than any picture a GPU holds, and is drawn as it was.
                val keep = WdpTouch.device && cw * ch <= viewW * viewH * 1.05f
                Box(
                    Modifier.fillMaxSize()
                        .then(if (keep) Modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen } else Modifier)
                        .background(page),
                ) {
                    form.image?.let { Picture(it) }
                    // Windows Forms z-order: the first control a parent adds is the one on top, so a page is painted
                    // from the end of its list back to the start. That is how the red BLOCKED flag sits over the VIP
                    // legend. Each control is keyed by its name, so one that appears or goes keeps the others' state.
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalWdpContent provides contentOut, LocalWdpTyping provides typing, LocalWdpFormName provides form.form,
                        LocalWdpMeasurer provides measurer,
                        // only while the Planner is worked by finger: a mouse's page keeps nothing of this
                        LocalWdpTouchTargets provides (if (WdpTouch.device) fingers else null),
                        // the form's tooltips (WdpTips; tips/<form>.json), none while Settings has them off
                        LocalWdpTips provides WdpTips.of(form.form),
                    ) {
                        for (i in form.roots.indices.reversed()) {
                            val c = form.roots[i]
                            key(c.name) { Control(c, liveValues, scale, p, Inherited(ink, null, page), onValueOut, onClickOut, z = zStep(i)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A page's pinch zoom, for a phone: the Planner fits a page whole, which on a phone held upright is small type.
 * Two fingers zoom in and move the page about; one finger is left to the page, so a slider, a knob, a list or a
 * box to type in works as it does unzoomed, and nothing ever scrolls. 1 is the page fitted whole; the Planner makes
 * a new one for every page, so a page always opens whole, and it offers a button to go back to that.
 */
class WdpZoom {
    var zoom by mutableStateOf(1f)
        internal set
    /** where the top left of the zoomed page sits in its box, before it is held to the box's edges ([placed]) */
    internal var origin by mutableStateOf(Offset.Zero)
    /** during a pinch: the extra scale and where the page's top left is, drawn as a layer until the fingers lift */
    internal var live by mutableStateOf<Pair<Float, Offset>?>(null)

    val zoomed: Boolean get() = zoom > 1.001f

    fun reset() { zoom = 1f; origin = Offset.Zero; live = null }

    /**
     * Zooms to [to] (1 = fitted) keeping the point [q] of the box where it is, as a pinch about it would: a finger's
     * double tap on the page ([touchResolver]). [vw] × [vh] is the box, [cw] × [ch] the page as drawn now.
     */
    internal fun zoomAt(q: Offset, to: Float, vw: Float, vh: Float, cw: Float, ch: Float) {
        val a = placed(vw, vh, cw, ch)
        val z1 = to.coerceIn(1f, MAX)
        origin = q + (a - q) * (z1 / zoom)
        zoom = z1
        live = null
        if (!zoomed) reset()
    }

    /**
     * Where the page's top left goes in a box of [vw] × [vh] when the page is [cw] × [ch]: centred along an axis it
     * does not fill, and otherwise kept so that the page covers the box (no bare margin past its edge).
     */
    internal fun placed(vw: Float, vh: Float, cw: Float, ch: Float): Offset = placeAt(origin, vw, vh, cw, ch)

    private fun placeAt(o: Offset, vw: Float, vh: Float, cw: Float, ch: Float): Offset = Offset(
        if (cw <= vw) (vw - cw) / 2f else o.x.coerceIn(vw - cw, 0f),
        if (ch <= vh) (vh - ch) / 2f else o.y.coerceIn(vh - ch, 0f),
    )

    /**
     * [zoomAt], gliding over [ms]: the page as laid out is moved and scaled as a picture ([live], as a pinch does) to
     * where [zoomAt] puts it, eased, the point [q] held in place unless an edge stops it, and then laid out there once.
     * [to] at 1 fits the page whole again. Cancelled part way, it lands where it was going.
     */
    internal suspend fun glideTo(q: Offset, to: Float, vw: Float, vh: Float, cw: Float, ch: Float, ms: Float = 200f) {
        val a0 = placed(vw, vh, cw, ch)
        val z1 = to.coerceIn(1f, MAX)
        val sF = z1 / zoom
        if (kotlin.math.abs(sF - 1f) < 1e-3f) { zoomAt(q, z1, vw, vh, cw, ch); return }
        val o1 = q + (a0 - q) * sF
        val aF = placeAt(o1, vw, vh, cw * sF, ch * sF)
        try {
            var start = -1L
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                if (start < 0L) start = now
                val t = ((now - start) / 1_000_000f / ms).coerceIn(0f, 1f)
                val u = 1f - (1f - t) * (1f - t) * (1f - t)
                val s = kotlin.math.exp(u * kotlin.math.ln(sF))
                // the corner moves in step with the scale, which is what keeps q still: a0 → aF as s goes 1 → sF
                live = s to (a0 + (aF - a0) * ((s - 1f) / (sF - 1f)))
                if (t >= 1f) break
            }
        } finally {
            zoom = z1
            origin = o1
            live = null
            if (!zoomed) reset()
        }
    }

    companion object { const val MAX = 6f }
}

/**
 * Two fingers on the page zoom it and move it; one finger passes through. The events are looked at on their way
 * down (the Initial pass), and only those with two fingers or more (or the rest of a pinch once it has started)
 * are taken, so the page's own controls never see a pinch and always see a tap, a drag or a key.
 *
 * The box and the page's size are read from [geo] (the view's [WdpFormView] keeps them there) when a gesture starts,
 * not taken as keys: a key that changed while the fingers were down restarted the handler, which then waited for a
 * new press and lost the pinch — and the first finger on a PC's touch screen does change them, since it switches the
 * Planner to its finger layout ([WdpTouch.device]) and the page's box with it.
 */
private fun Modifier.pinchZoom(z: WdpZoom, geo: FloatArray): Modifier =
    pointerInput(z) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinching = false
            var s = 1f
            var a = z.placed(geo[0], geo[1], geo[2], geo[3])
            do {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val down = e.changes.count { it.pressed }
                if (down >= 2 || pinching && down >= 1) {
                    pinching = true
                    val zc = e.calculateZoom()
                    val pan = e.calculatePan()
                    val c = e.calculateCentroid(useCurrent = true)
                    val total = (z.zoom * s * zc).coerceIn(1f, WdpZoom.MAX)
                    val step = total / (z.zoom * s)
                    if (c != Offset.Unspecified) a = c + (a - c) * step + pan
                    s *= step
                    z.live = s to a
                    e.changes.forEach { it.consume() }
                }
            } while (e.changes.any { it.pressed })
            if (pinching) {
                z.zoom = (z.zoom * s).coerceIn(1f, WdpZoom.MAX)
                z.origin = a
                z.live = null
                if (!z.zoomed) z.reset()
            }
        }
    }

/**
 * The box being typed in on a form, if any: its bounds (root pixels) and what leaving it does. [WdpFormView] leaves
 * it when a press lands anywhere else on the form, before that press reaches what it landed on — Windows' order.
 */
internal class WdpTyping {
    /** where the box is now (it moves when a phone's page is zoomed while it has the keyboard) */
    var rect: () -> Rect = { Rect.Zero }
    var leave: (() -> Unit)? = null
}

private val LocalWdpTyping = androidx.compose.runtime.staticCompositionLocalOf { WdpTyping() }
private val LocalWdpFormName = androidx.compose.runtime.staticCompositionLocalOf { "" }

/** True while a panel is drawn as paper ([WdpPanelView] with `print`): no buttons, lists without their arrow. */
private val LocalWdpPrint = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Whether a wiring's own content ([WdpControlContent]) is being drawn as paper: a note for the pilot at the screen stays off it. */
internal val wdpOnPaper: Boolean
    @Composable get() = LocalWdpPrint.current

/** Controls left off the paper by name ([WdpPanelView]'s `hide`): a line that speaks to the pilot at the screen. */
private val LocalWdpPrintHide = androidx.compose.runtime.staticCompositionLocalOf { emptySet<String>() }

/** The form's one text measurer ([fit]); null outside a form, where nothing is fitted. */
private val LocalWdpMeasurer = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.text.TextMeasurer?> { null }

/**
 * One control of a page — a panel and everything on it — drawn on its own, filling the box it is given, for Print to
 * kneeboard (R3-PLAN A15-A17): the DataCard's `pnlPage_1` and the Coordination Card's `pnlPage_2` are 900 x 675, and
 * each knee's page is one half of it ([crop]), drawn at the kneeboard page's own size rather than captured from the
 * screen, so the type is laid out at that size and not enlarged.
 *
 * [crop] is the part of the control drawn, `x, y, w, h` in designer pixels from the control's own corner; null draws
 * all of it. The part is scaled to fit the box, keeping its shape, and centred. [values] and [content] are the page's,
 * as [WdpFormView] takes them, so it is the page as the pilot left it: what was typed, the pictures the wiring draws.
 *
 * With [print] (the default) it is drawn as paper, as WDP's own kneeboard card was: the buttons the page carries for
 * the pilot are left off, a list shows its choice without the drop-down arrow, and nothing takes input. The page's
 * own colours are kept. [hide] names other controls to leave off the paper, such as a label telling the pilot at the
 * screen to click a picture.
 */
@Composable
fun WdpPanelView(
    form: WdpForm,
    panel: String,
    values: WdpValues,
    modifier: Modifier = Modifier,
    crop: IntArray? = null,
    look: WdpLook = WdpLook.WDP,
    print: Boolean = true,
    hide: Set<String> = emptySet(),
    content: Map<String, @Composable () -> Unit> = emptyMap(),
) {
    val c = form.find(panel) ?: return
    val cx = crop?.getOrNull(0) ?: 0
    val cy = crop?.getOrNull(1) ?: 0
    val cw = (crop?.getOrNull(2) ?: c.w).coerceAtLeast(1)
    val ch = (crop?.getOrNull(3) ?: c.h).coerceAtLeast(1)
    // paper is drawn as it always was; a panel drawn for the screen has the Planner's look
    val p = paletteOf(look, modern = !print)
    val page = surfaceOf(WdpControl(bg = form.bg), p)?.takeIf { it.alpha > 0f } ?: p.page
    val ink = colorOf(form.fg, p) ?: p.ink
    val typing = remember { WdpTyping() }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 256)
    val live = remember(form) { WdpLiveValues(form) }
    live.update(values)
    SideEffect { live.flush() }
    BoxWithConstraints(modifier.clipToBounds().background(page)) {
        val bw = constraints.maxWidth.toFloat()
        val bh = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else bw * ch / cw
        val scale = minOf(bw / cw, bh / ch)
        val left = (bw - cw * scale) / 2f - cx * scale
        val top = (bh - ch * scale) / 2f - cy * scale
        Box(
            Modifier.fillMaxWidth().height(px(bh)).layout { measurable, _ ->
                val pl = measurable.measure(Constraints.fixed((c.w * scale).roundToInt().coerceAtLeast(1), (c.h * scale).roundToInt().coerceAtLeast(1)))
                layout(bw.roundToInt(), bh.roundToInt()) { pl.place(left.roundToInt(), top.roundToInt()) }
            },
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalWdpContent provides content, LocalWdpTyping provides typing, LocalWdpFormName provides form.form,
                LocalWdpPrint provides print, LocalWdpPrintHide provides (if (print) hide else emptySet()),
                LocalWdpMeasurer provides measurer,
            ) {
                // drawn at the control's own corner; nothing on it reaches the page's wiring
                Control(c.copy(x = 0, y = 0), live, scale, p, Inherited(ink, null, page), null, null)
            }
        }
    }
}

/**
 * What the headless checks read back (`--wdptypetest`): where each control was drawn and what each box shows, by
 * `"<form>/<control>"`. Off in the app, where it costs nothing; a check turns it on before it composes a page.
 */
object WdpProbe {
    var on = false
    /** the control's rectangle in the scene, in pixels, as last laid out */
    val rects = HashMap<String, Rect>()
    /** what a box shows, text and caret, as last drawn */
    val boxes = HashMap<String, TextFieldValue>()
    /** the box that has the keyboard, or null */
    var focused: String? = null
    /** the scale each form was last drawn at, pixels per designer pixel */
    val scales = HashMap<String, Float>()
    /** what the boxes did with the keyboard, in order ("<box> took the keys", "<box> Tab: moved"), for a check's report */
    val events = ArrayList<String>()

    fun clear() { rects.clear(); boxes.clear(); focused = null; scales.clear(); events.clear() }

    internal fun note(e: String) { if (on) { events += e; if (events.size > 200) events.removeAt(0) } }
}
// ---------------------------------------------------------------- palette

/** What each kind of control is painted with, under one look or the other (a data class: equal palettes let a control skip). */
private data class Palette(
    /** the Planner's modern drawing ([Modern]): on screen under the WDP look; never on paper, never under the HUD look */
    val modern: Boolean,
    val page: Color,
    val panel: Color,
    val ink: Color,
    val field: Color,
    val fieldInk: Color,
    val button: Color,
    val buttonInk: Color,
    val line: Color,
    val caption: Color,
    /** whether the designer's own colours are used as they are, or mapped onto the app's surfaces */
    val literal: Boolean,
)

private fun paletteOf(look: WdpLook, modern: Boolean = true) = when (look) {
    // Windows Forms as Falcas shipped it — Control grey under everything, white where you type, black ink — in the
    // Planner's modern light ([Modern]): the same greys a shade cooler, so a WDP pilot sees his page and the app's
    WdpLook.WDP -> if (modern) Palette(
        modern = true,
        page = Modern.PAGE_LIGHT, panel = Modern.PAGE_LIGHT,
        ink = Color(0xFF000000),
        field = Color(0xFFFFFFFF), fieldInk = Color(0xFF000000),
        button = Modern.BUTTON, buttonInk = Color(0xFF14181D),
        line = Modern.LINE, caption = Color(0xFF000000),
        literal = true,
    ) else Palette(
        // paper (a panel printed to the kneeboard): Windows' own greys, square boxes, black marks, as it always was
        modern = false,
        page = Color(0xFFF0F0F0), panel = Color(0xFFF0F0F0),
        ink = Color(0xFF000000),
        field = Color(0xFFFFFFFF), fieldInk = Color(0xFF000000),
        button = Color(0xFFE1E1E1), buttonInk = Color(0xFF000000),
        line = Color(0xFFADADAD), caption = Color(0xFF000000),
        literal = true,
    )
    WdpLook.HUD -> Palette(
        modern = false,
        page = Hud.Bg, panel = Hud.Surface,
        ink = Hud.Text,
        field = Hud.Bg, fieldInk = Hud.Text,
        button = Hud.Surface2, buttonInk = Hud.Amber,
        line = Hud.Outline, caption = Hud.Amber,
        literal = false,
    )
}

/**
 * **The Planner's modern look**, laid over WDP's own colours without moving anything. Every control stays where
 * Falcas put it and the size he made it; what changes is how it is drawn: flat buttons with slightly rounded corners
 * that light up under the mouse and darken when pressed, boxes with a thin edge, a faint inset and a blue focus ring,
 * light tab strips and group frames, round slider thumbs on filled tracks, check boxes that fill when ticked.
 *
 * **The colours are WDP's where they mean something** — the red and green lamps, the green Camp/TE button, the DED's
 * black boxes and their highlighted figures, the DataCard's yellow cells, the attack drawings — and harmonised with
 * the app's dark HUD where they are only furniture: WDP's charcoal (#404040) becomes the HUD's blue-grey
 * ([CHARCOAL]), and a page made of charcoal panels ([darkPage]: TOSS, Pop-up, HADB, Performance, DTC) sits on the
 * app's own dark ground ([PAGE_DARK]) instead of Windows' light grey, so the panels read as the app's cards. The
 * card pages keep their light grey: the DataCard is paper, and its labels take their ink from the page.
 *
 * Paper — a panel printed to the kneeboard ([WdpPanelView]) — is drawn as it always was: square boxes, black marks.
 */
private object Modern {
    /** the ground a charcoal page's panels sit on: the app's background, a shade lifted */
    val PAGE_DARK = Color(0xFF0E1319)
    /** Windows' Control grey (#F0F0F0), a shade cooler */
    val PAGE_LIGHT = Color(0xFFECEFF2)
    /** WDP's charcoal panels and windows (#404040), in the HUD's blue-grey */
    val CHARCOAL = Color(0xFF29313B)
    val WDP_CHARCOAL = Color(0xFF404040)
    /** a button with no colour of its own (Windows' #E1E1E1) */
    val BUTTON = Color(0xFFE1E5EA)
    /** WDP's Silver buttons (#C0C0C0), lighter and cooler */
    val SILVER = Color(0xFFD3D9DF)
    /** a box's thin edge */
    val LINE = Color(0xFF9FA8B3)
    /** the focus ring, a ticked box, the selected row, a slider's filled track */
    val ACCENT = Color(0xFF3B82F6)
    /** the same on a charcoal panel, where the darker blue sinks */
    val ACCENT_ON_DARK = Color(0xFF5E9BFF)
    /** an unticked box's edge */
    val CHECK_EDGE = Color(0xFF8A95A3)
    /** a grid's header row */
    val HEADER = Color(0xFFE6EAEF)
    /** the arrows of a list and a number box */
    val SOFT_INK = Color(0xFF4A5563)
}

private fun lum(c: Color) = 0.3f * c.red + 0.59f * c.green + 0.11f * c.blue

/** Dark enough that what sits on it is drawn light: WDP's charcoal, black, the dark page. */
private fun Color.isDark() = alpha > 0.5f && lum(this) < 0.42f

/** [isDark] for the windows' frames ([WdpDialogHost]): a charcoal window gets a dark title bar. */
internal fun Color.wdpIsDark(): Boolean = isDark()

/**
 * What a form is drawn on in the Planner's look — its own BackColor, or the page's ground ([darkPage]) — for a
 * window's frame, which takes that colour up to its edge and picks its title bar by it.
 */
internal fun wdpFormGround(form: WdpForm): Color {
    val p = paletteOf(WdpLook.WDP)
    return surfaceOf(WdpControl(bg = form.bg), p)?.takeIf { it.alpha > 0f } ?: if (darkPage(form, p)) Modern.PAGE_DARK else p.page
}

/** A box's thin edge: the Planner's line round a white box, a coloured box's own colour darker (the DataCard's yellow). */
private fun boxEdge(fill: Color): Color = when {
    fill.isDark() -> Color.White.copy(alpha = 0.3f)
    lum(fill) > 0.93f -> Modern.LINE
    else -> lerp(fill, Color.Black, 0.32f)
}

/** The faint shade along the top of a box, so it reads as a well to type into rather than a slab. */
private fun Modifier.inset(fill: Color): Modifier = drawBehind {
    val h = (size.height * 0.09f).coerceIn(1f, 2.5f)
    drawRect(Color.Black.copy(alpha = if (fill.isDark()) 0.35f else 0.08f), size = androidx.compose.ui.geometry.Size(size.width, h))
}

/** The faint line along the bottom of a button, which is all the depth a flat button keeps. */
private fun Modifier.buttonShade(): Modifier = drawBehind {
    val h = (size.height * 0.06f).coerceIn(1f, 2f)
    drawRect(Color.Black.copy(alpha = 0.12f), topLeft = Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h))
}

/**
 * A box's frame: rounded, a thin edge ([boxEdge]), a faint inset along its top, and the focus ring while it has the
 * keyboard, in the modern look; Windows' square hairline otherwise (paper, the HUD look). The same for a box to type
 * in, a number box, a list's box and a read-only box, so they all read as one kind of thing.
 */
@Composable
private fun Modifier.boxFrame(c: WdpControl, scale: Float, p: Palette, fill: Color, focused: Boolean, hovered: Boolean = false): Modifier {
    if (!p.modern) return clip(RoundedCornerShape(2.dp)).background(fill).edgeUnder(1.dp, if (focused) FOCUS_BLUE else p.line, 2.dp)
    val radius = cornerFor(scale, 2.5f, 5.dp, c.w, c.h)
    val edge = when {
        focused -> accentOn(fill)
        hovered -> lerp(boxEdge(fill), if (fill.isDark()) Color.White else Color.Black, 0.35f)
        else -> boxEdge(fill)
    }
    return clip(RoundedCornerShape(radius)).background(fill).inset(fill).edgeUnder(if (focused) 1.5.dp else 1.dp, edge, radius)
}

/**
 * A box's edge drawn under what the box holds rather than over it (as `border` draws it): with a value's figures in the
 * middle of its box ([lineAt]), the tails of g, p, y and a comma in a box barely taller than its type — the DataCard's
 * are 13 px under an 11 px font — cross its bottom edge instead of being cut off by it ("engaging" read "enqaqinq").
 */
private fun Modifier.edgeUnder(width: Dp, color: Color, radius: Dp): Modifier = drawBehind {
    val sw = width.toPx()
    drawRoundRect(
        color, topLeft = Offset(sw / 2f, sw / 2f), size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius((radius.toPx() - sw / 2f).coerceAtLeast(0f)),
        style = androidx.compose.ui.graphics.drawscope.Stroke(sw),
    )
}

/** A face under the mouse: a light one lighter still, a coloured or dark one lifted a little. */
private fun hoverOf(c: Color): Color = if (lum(c) > 0.78f) lerp(c, Color.White, 0.5f) else lerp(c, Color.White, 0.14f)

/** A face being pressed. */
private fun pressOf(c: Color): Color = lerp(c, Color.Black, if (lum(c) > 0.78f) 0.12f else 0.18f)

/** A button's own edge: its face, darker. */
private fun edgeOf(c: Color): Color = lerp(c, Color.Black, 0.24f)

/** A hairline that reads on [behind] without shouting: light on charcoal, dark on grey or paper. */
private fun hairlineOn(behind: Color): Color = if (behind.isDark()) Color.White.copy(alpha = 0.24f) else Color.Black.copy(alpha = 0.2f)

/** The accent that holds on [behind]. */
private fun accentOn(behind: Color): Color = if (behind.isDark()) Modern.ACCENT_ON_DARK else Modern.ACCENT

/** A corner of [designerPx] designer pixels at the page's [scale], never under 1 dp nor over [max]. */
@Composable
private fun corner(scale: Float, designerPx: Float, max: Dp): Dp = px(designerPx * scale).coerceIn(1.dp, max)

/** A control's corner: [designerPx] at the page's [scale], but never more than a quarter of its [w] × [h] (a DED cell, a thin box). */
@Composable
private fun cornerFor(scale: Float, designerPx: Float, max: Dp, w: Int, h: Int): Dp =
    px(minOf(designerPx, minOf(w, h) * 0.25f) * scale).coerceIn(0.dp, max)

/**
 * Whether a page is made of WDP's charcoal panels (TOSS, Pop-up, HADB, Performance and the DTC's tab pages): more
 * than a third of it covered by dark panels. Those pages sit on the app's dark ground rather than Windows' grey.
 */
private fun darkPage(form: WdpForm, p: Palette): Boolean {
    if (!p.modern) return false
    val w = form.width.toFloat()
    val h = form.height.toFloat()
    if (w <= 0f || h <= 0f) return false
    var dark = 0f
    for (c in form.roots) {
        if (c.hidden) continue
        val fill = if (c.kind == "tabs") c.children.firstOrNull()?.let { surfaceOf(it, p) } else surfaceOf(c, p)
        if (fill != null && fill.isDark()) dark += c.w.toFloat() * c.h
    }
    return dark / (w * h) > 0.34f
}

/**
 * The little round lamps WDP draws as pictures (the DTC page's red and green callsign lamps, the DataCard's): a
 * square photograph of a round light on the grey it was taken on. Drawn round, the square of old grey around the
 * light is gone, whatever the panel under it is now.
 */
private fun isLamp(c: WdpControl): Boolean {
    val img = c.image ?: return false
    return (img.endsWith("Green.BackgroundImage") || img.endsWith("Red.BackgroundImage")) && c.w <= 44 && abs(c.w - c.h) <= 3
}

/** [WdpFormView]'s `content`, reaching every control however deep it sits. */
private val LocalWdpContent = androidx.compose.runtime.staticCompositionLocalOf<Map<String, @Composable () -> Unit>> { emptyMap() }

/** What a control gets from its parents when it sets nothing itself: Windows Forms' ambient properties. */
private data class Inherited(
    val fg: Color, val fontSizePt: Double?, /** what shows through a transparent control */ val bg: Color,
    /** a control it sits in has a tooltip: a control a pilot works keeps that tip off itself even without one of its own */
    val tipAbove: Boolean = false,
)

// ---------------------------------------------------------------- controls

@Composable
private fun px(v: Float): Dp = with(LocalDensity.current) { v.toDp() }

@Composable
private fun Control(
    designer: WdpControl,
    /** the page's values, read through this control's own subscription ([WdpLiveValues]) */
    live: WdpLiveValues,
    scale: Float,
    p: Palette,
    inh: Inherited,
    onValueIn: ((String, String) -> Unit)?,
    onClickIn: ((String) -> Unit)?,
    /** where it is in the page's stacking, for a finger ([zStep]) */
    z: String = "",
) {
    // Hidden by the designer unless the wiring shows it; shown by the designer unless the wiring hides it. The
    // second is how one WDP control hosts several of its tabs — cntDataCard holds the Briefing view, the DataCard
    // and the Coordination Card as three panels at one rectangle, and the program flips them at runtime.
    val values = live.read(designer.name)
    val state = values[designer.name]
    if (designer.hidden && state != "shown") return
    if (state == "hidden") return
    // What the program changes at runtime besides the text: ForeColor / BackColor ("<name>.fore", "<name>.back",
    // or "<name>.color" for the ForeColor), applied over the designer's, and Enabled ("<name>.enabled"). A disabled
    // control is drawn greyed and passes no input — to its children either, as in Windows Forms.
    val c = values["${designer.name}.fore"] ?: values["${designer.name}.color"] ?: values["${designer.name}.back"]
    val cc = if (c == null) designer else designer.copy(
        fg = values["${designer.name}.fore"] ?: values["${designer.name}.color"] ?: designer.fg,
        bg = values["${designer.name}.back"] ?: designer.bg,
    )
    val enabled = values["${designer.name}.enabled"] != "false"
    val onValue = if (enabled) onValueIn else null
    val onClick = if (enabled) onClickIn else null
    ControlBody(cc, values, live, state, scale, p, inh, onValue, onClick, enabled, z)
}

/**
 * A control's place in the page's stacking as a text that sorts higher the nearer the top it is: one step per
 * level, the parent's first child (Windows Forms' top) the highest, and a child above its parent. A finger's tap
 * goes to the top control under it, as a mouse's does ([TouchTargets.resolve]).
 */
private fun zStep(index: Int): String = (9999 - index.coerceIn(0, 9999)).toString().padStart(4, '0')

@Composable
private fun ControlBody(
    c: WdpControl,
    values: WdpValues,
    live: WdpLiveValues,
    state: String?,
    scale: Float,
    p: Palette,
    inh: Inherited,
    onValue: ((String, String) -> Unit)?,
    onClick: ((String) -> Unit)?,
    enabled: Boolean,
    z: String = "",
) {
    // on paper a page's buttons are not there: they are for the pilot at the screen (WdpPanelView)
    if (c.kind == "button" && LocalWdpPrint.current) return
    if (c.name in LocalWdpPrintHide.current) return
    // A label whose text is its own name is a placeholder the program fills at runtime ("lblHdg3"; over a hundred
    // on the DataCard), and so is one whose text is its name without the prefix and is a word ending in a number
    // ("AC1" in lblAC1, "LineVal7") — plus the DataCard's six airport boxes. Until the page's wiring gives it a value
    // it shows nothing, which is what WDP shows before a mission is loaded. Only labels, and only those: a button, a
    // group or a check box whose caption repeats its name ("TE" on btnTE, "Coordinates" on grpCoordinates) is a
    // caption, and so is "Reference" on lblReference, "30" on lbl30 or the compass letters under a heading slider.
    val designed = c.text?.takeUnless { t ->
        c.kind == "label" && (t == c.name ||
            c.name.endsWith(t) && c.name.length - t.length <= 3 &&
            (t.last().isDigit() && t.any { it.isLetter() } || t in AIRPORT_BOXES))
    } ?: ""
    // "shown" is a visibility, not a caption: a wiring that shows a button must not print the word on it. The
    // caption a wiring sets for a control whose value is its state (a button, a checkbox) is "<name>.text".
    val raw = state?.takeUnless { it == "shown" }
    val shown = values["${c.name}.text"] ?: raw ?: designed
    // its tooltip (WdpTips): the wiring's "<name>.tip" as the page goes ("" for none), else the form's tips file; never on paper
    // (a tab page's is its tab's, in the strip: TabStrip)
    val tipText = if (LocalWdpPrint.current || c.kind == "tab") null
        else (values["${c.name}.tip"] ?: LocalWdpTips.current[c.name])?.takeIf { it.isNotBlank() }
    // what this control passes on: its own colour if it set one, else what it was given
    val mine = Inherited(
        colorOf(c.fg, p) ?: inh.fg, c.fontSize ?: inh.fontSizePt,
        surfaceOf(c, p)?.takeIf { it.alpha > 0f } ?: inh.bg,
        tipAbove = inh.tipAbove || tipText != null,
    )
    // A label whose box is no taller than its type — WDP's value labels are 13 px boxes with a 13 px font — is
    // not clipped: Compose's line box is taller than the font and sits the glyphs lower in it than Windows does,
    // so clipping such a box takes the tops off the digits. Windows shows those labels whole, and so does this.
    val tightLabel = c.kind == "label" && c.h < ((c.fontSize ?: inh.fontSizePt ?: 8.25).toFloat() * 1.333f * 1.6f)
    // WDP hangs Click handlers on labels and panels as well as buttons — the compass letters under the heading
    // slider, the rotary knobs that are a stack of pictures. On a wired page they report taps like a button does,
    // with no ripple, since in WDP nothing shows either; the wiring ignores the names it has no handler for.
    val taps = remember { MutableInteractionSource() }
    var localTab by remember(c.name) { mutableStateOf<String?>(null) }
    val tappable = onClick != null && (c.kind == "label" || c.kind == "panel" || c.kind == "picture")
    // the modern look's shapes (never on paper): a dark panel on a ground of another colour is one of the page's cards
    // and has rounded corners — TOSS's three panels, the DTC's pages, a DED's black glass — and a lamp is round
    // what the control is to a finger (WdpTouch), while the Planner is worked by one; nothing on paper or for a mouse
    val fingers = LocalWdpTouchTargets.current
    val touch = if (fingers == null || LocalWdpPrint.current) Modifier
        else touchOf(
            fingers, c, values, shown, designed.takeIf { raw == null && values["${c.name}.text"] == null }, scale, onValue, onClick, tappable, z,
            drawn = LocalWdpContent.current[c.name] != null, tip = tipText,
        )
    // the tip under the mouse; a control a pilot works inside one with a tip keeps that tip off itself even without its own
    val tipped = if (tipText != null || inh.tipAbove && c.kind in TIP_WORKED)
        Modifier.wdpTip(LocalWdpFormName.current + "/" + c.name, tipText) else Modifier
    val shape = if (!p.modern) null else when {
        isLamp(c) -> CircleShape
        c.kind == "panel" || c.kind == "tab" -> surfaceOf(c, p)?.takeIf { it.isDark() && it != inh.bg }?.let {
            val r = cornerFor(scale, 6f, 10.dp, c.w, c.h)
            if (c.kind == "tab") RoundedCornerShape(0.dp, r, r, r) else RoundedCornerShape(r)
        }
        else -> null
    }
    Box(
        Modifier.offset(px(c.x * scale), px(c.y * scale)).size(px(c.w * scale), px(c.h * scale))
            .then(touch)
            .then(tipped)
            // an AutoSize label grows to fit what the program puts in it, so it is not clipped either (see [Label])
            .then(
                if (tightLabel || c.kind == "label" && c.autoSize) Modifier
                else if (shape != null) Modifier.clip(shape) else Modifier.clipToBounds(),
            )
            // Windows greys a disabled button's caption but keeps its colour — WDP's Camp/TE pair shows the one in
            // use as a green, disabled button — so a button is not faded; anything else is.
            .then(if (enabled || c.kind == "button") Modifier else Modifier.alpha(0.45f))
            .then(if (tappable) Modifier.clickable(taps, indication = null) { onClick?.invoke(c.name) } else Modifier)
            // WDP's knobs step forward on the left button and back on the right; a mouse's right button reaches the
            // wiring as "<name>:right", and a plain tap stays the left one (a finger has no other)
            .secondaryClick(c.name, tappable) { onClick?.invoke("${c.name}:right") }
            .then(if (WdpProbe.on) Modifier.probed(LocalWdpFormName.current + "/" + c.name) else Modifier),
    ) {
        // Falcas's own artwork where the designer set one: the DataCard's grid, the selector pictures on TOSS.
        // Windows paints BackColor first and the image over it, so for a container the picture comes after its
        // fill — the DataCard panel is white with the card on top, and drawn the other way round the white won.
        // A label or a field keeps the picture under its text.
        val container = c.kind == "panel" || c.kind == "group" || c.kind == "picture"
        if (!container) c.image?.let { Picture(it) }
        when (c.kind) {
            "panel" -> Box(Modifier.fillMaxSize().background(surfaceOf(c, p) ?: Color.Transparent))
            "group" -> GroupBox(c, scale, p, mine, inh.bg)
            "button" -> Face(
                if (enabled) c else c.copy(fg = "#6D6D6D"), shown, scale, p, mine,
                surfaceOf(c, p) ?: p.button, p.buttonInk, onClick, raised = true,
            )
            // a field the pilot can type into, once the page's wiring is listening; read-only ones stay a Face
            "text" -> if (onValue != null && !c.readOnly) Field(c, shown, values, scale, p, mine, onValue, onClick)
                else Face(c, shown, scale, p, mine, surfaceOf(c, p) ?: p.field, p.fieldInk, null, sunken = true)
            // a number box that takes no input (disabled, or the page read-only) keeps its arrows, as Windows draws
            // them; only on paper is it the number alone
            "number" -> if (onValue != null) UpDown(c, shown, values, scale, p, mine, onValue, onClick)
                else LocalWdpPrint.current.let { paper ->
                    Face(
                        c, shown, scale, p, mine, surfaceOf(c, p) ?: p.field, p.fieldInk, null, sunken = true,
                        reserve = if (paper) 0f else SPIN_W * scale, spin = !paper,
                    )
                }
            "combo" -> Combo(c, shown, values, scale, p, mine, onValue, onClick)
            "tabs" -> TabStrip(c, selectedTab(c, values, localTab), scale, p, mine, mine.bg, z) { page ->
                localTab = page
                onValue?.invoke(c.name, page)
            }
            "tab" -> Box(Modifier.fillMaxSize().background(surfaceOf(c, p) ?: p.panel))
            "grid" -> Grid(c, values, scale, p, mine, onClick)
            "list" -> ListBox(c, values, scale, p, mine, onClick)
            "check", "radio" -> CheckOrRadio(c, values, designed, scale, p, mine, onClick)
            "label" -> Label(c, shown, scale, p, mine)
            "picture" -> {
                Box(Modifier.fillMaxSize().background(surfaceOf(c, p) ?: p.panel))
                // a picture the program loads at runtime: the attack pages' profile drawings by their file name, or
                // (a path with a folder in it) one of the app's own pictures — the chart window shows the airport's
                // charts that way — fitted rather than stretched, since a chart has its own shape
                val file = raw?.takeIf { it.endsWith(".jpg", true) || it.endsWith(".png", true) || it.endsWith(".gif", true) || it.endsWith(".webp", true) }
                if (file != null && '/' in file) PictureFile(file, ContentScale.Fit)
                else file?.let { PictureFile("data/wdp/pictures/" + it.lowercase()) }
            }
            "slider" -> Slider(c, values, scale, p, mine.bg, onValue)
            else -> Box(Modifier.fillMaxSize())
        }
        if (container) c.image?.let { Picture(it) }
        // What WDP paints into a panel at runtime — the weather list, the airport schedule, a kneeboard preview — is
        // a picture of text there; here it is the text itself, one line per line, under "<name>.lines".
        if (container) values["${c.name}.lines"]?.let { PaintedLines(c, it, scale, p, mine) }
        // What a wiring draws of its own (the loadout window, the attack map, the chart window) sizes its type from the
        // page's scale: the app theme's fixed 24 sp line would stand its small type in a line taller than its row, and
        // on a phone or a tablet, where the scale is small against the density, push it out of the row altogether; its
        // half-sp letter spacing, a pixel a letter there, broke the loadout's store names over two lines.
        LocalWdpContent.current[c.name]?.let { draw ->
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(
                    lineHeight = TextUnit.Unspecified, letterSpacing = TextUnit.Unspecified,
                ),
            ) { draw() }
        }
        // a tab control shows one page, the one its strip has selected; everything else shows what is visible
        val kids = if (c.kind == "tabs") c.children.filter { it.name == selectedTab(c, values, localTab) }
            else visibleChildren(c, values)
        // WDP's BringToFront: a control the wiring says is in front ("<name>.front") is drawn over its siblings,
        // as the DTC page's pilot box is over the pilot's name while it is being changed
        val ordered = if (kids.none { values["${it.name}.front"] == "true" }) kids
            else kids.filter { values["${it.name}.front"] == "true" } + kids.filter { values["${it.name}.front"] != "true" }
        for (i in ordered.indices.reversed()) {
            val k = ordered[i]
            key(k.name) { Control(k, live, scale, p, mine, onValue, onClick, z = z + zStep(i)) }
        }
    }
}

/**
 * Which of a control's children are drawn.
 *
 * WDP stacks alternative views on top of one another: `pnlPage_1`, `pnlPage_2`, `pnlBrief` and `pnlWx` all sit
 * at the same rectangle on the DataCard page and the program shows one at a time by flipping `Visible` at runtime
 * — 237 such switches in that page alone, and TOSS does the same with its Up/Middle/Down selection panels. Drawn
 * all at once they are a jumble. So siblings that share a rectangle are treated as alternatives: the page's
 * wiring can name the one to show (a value of `"shown"` under the control's name), and failing that the first in
 * designer order is drawn, which is what Windows shows before any code has run.
 */
private val AIRPORT_BOXES = setOf("DepName", "ArrName", "AltnName", "DepElv", "ArrElv", "AltnElv")

/** The kinds of control a pilot works: inside a panel with a tooltip, one of them without its own shows none ([WdpTips]). */
private val TIP_WORKED = setOf("button", "text", "number", "combo", "check", "radio", "slider", "list", "grid", "tabs")

private fun visibleChildren(c: WdpControl, values: WdpValues): List<WdpControl> {
    if (c.children.size < 2) return c.children
    val out = ArrayList<WdpControl>(c.children.size)
    val takenRects = HashSet<Long>()
    // anything the wiring has explicitly shown wins its rectangle outright
    val chosen = c.children.filter { values[it.name] == "shown" }
    for (k in chosen) takenRects += rectKey(k)
    for (k in c.children) {
        if (k.hidden && values[k.name] != "shown") continue
        val key = rectKey(k)
        val isAlternative = c.children.count { it !== k && rectKey(it) == key && it.kind == k.kind } > 0
        when {
            k in chosen -> out += k
            !isAlternative -> out += k
            key in takenRects -> continue
            else -> { takenRects += key; out += k }
        }
    }
    return out
}

private fun rectKey(k: WdpControl): Long =
    (k.x.toLong() and 0xFFFF) or ((k.y.toLong() and 0xFFFF) shl 16) or ((k.w.toLong() and 0xFFFF) shl 32) or ((k.h.toLong() and 0xFFFF) shl 48)

/**
 * A control's designer picture, drawn under its children.
 *
 * The extractor keeps each image in the format Falcas saved it, so the file may be a PNG, a BMP or a JPEG and the
 * name carries no extension; the three are tried in turn and a miss costs one failed asset read, once, since the
 * result is remembered for the control's lifetime.
 */
@Composable
private fun PictureFile(path: String, fit: ContentScale = ContentScale.FillBounds) {
    val bmp by produceState<Bitmap?>(null, path) { value = Repo.bitmap(path) }
    bmp?.let {
        Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = fit)
    }
}

/**
 * Lines of text a program paints into a panel, top left, in a fixed-width face so the columns WDP lines up with
 * spaces stay lined up. The control's own font size and ink, as a label's would be.
 */
@Composable
private fun PaintedLines(c: WdpControl, text: String, scale: Float, p: Palette, inh: Inherited) {
    Column(Modifier.fillMaxSize().padding(px(4 * scale))) {
        for (line in text.split('\n')) {
            Text(
                line.ifEmpty { " " },
                color = colorOf(c.fg, p) ?: inh.fg,
                style = style(c, scale, inh).copy(fontFamily = FontFamily.Monospace),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun Picture(name: String) {
    val bmp by produceState<Bitmap?>(null, name) {
        value = listOf("png", "jpg", "bmp", "gif").firstNotNullOfOrNull { ext -> Repo.bitmap("data/wdp/img/$name.$ext") }
    }
    bmp?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
    }
}

/**
 * A Windows Forms TrackBar: a track with a thumb, dragged or tapped to a value.
 *
 * The value and its range come from the page's values as integers — `name`, `name.min`, `name.max` — because a
 * WDP slider's range is not fixed: the TOSS page re-derives the release-height slider's limits from the pull-up
 * every time the height or G changes, and the port does the same. Horizontal, as every slider in the program is.
 */
@Composable
private fun Slider(c: WdpControl, values: WdpValues, scale: Float, p: Palette, behind: Color, onValue: ((String, String) -> Unit)?) {
    val min = values["${c.name}.min"]?.toIntOrNull() ?: 0
    val max = values["${c.name}.max"]?.toIntOrNull() ?: 10
    val value = values[c.name]?.toIntOrNull() ?: min
    val span = (max - min).coerceAtLeast(1)
    val frac = ((value - min).toFloat() / span).coerceIn(0f, 1f)
    val cb = androidx.compose.runtime.rememberUpdatedState(onValue)
    Box(
        Modifier.fillMaxSize()
            .then(
                if (onValue == null) Modifier
                else Modifier.pointerInput(c.name, min, max) {
                    fun at(x: Float) {
                        val f = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        cb.value?.invoke(c.name, (min + kotlin.math.round(f * span)).toInt().toString())
                    }
                    detectDragGestures(onDragStart = { at(it.x) }) { change, _ -> at(change.position.x); change.consume() }
                }
            )
            .then(
                if (onValue == null) Modifier
                else Modifier.pointerInput(c.name + "tap", min, max) {
                    detectTapGestures { cb.value?.invoke(c.name, (min + kotlin.math.round((it.x / size.width) * span)).toInt().toString()) }
                }
            ),
    ) {
        if (!p.modern) {
            // paper and the HUD look: the track, with the thumb over it, both in the page's own inks
            Box(
                Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center)
                    .background(p.line, RoundedCornerShape(2.dp)),
            )
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val x = (maxWidth - 10.dp) * frac
                Box(
                    Modifier.offset(x = x).size(10.dp, 18.dp).align(Alignment.CenterStart)
                        .background(p.button, RoundedCornerShape(2.dp))
                        .border(1.dp, p.line.copy(alpha = 0.9f), RoundedCornerShape(2.dp)),
                )
            }
        } else {
            // a thin rounded track filled up to a round thumb, the thumb where Windows puts its own (the value's
            // share of the track), in the accent that holds on the panel behind
            val track = corner(scale, 4f, 5.dp).coerceAtLeast(3.dp)
            val thumb = corner(scale, 14f, 20.dp).coerceAtLeast(12.dp)
            val accent = accentOn(behind)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val x = (maxWidth - thumb) * frac
                Box(
                    Modifier.fillMaxWidth().height(track).align(Alignment.Center).clip(RoundedCornerShape(track / 2))
                        .background(if (behind.isDark()) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.16f)),
                )
                Box(
                    Modifier.width(x + thumb / 2).height(track).align(Alignment.CenterStart).clip(RoundedCornerShape(track / 2))
                        .background(if (onValue != null) accent else accent.copy(alpha = 0.55f)),
                )
                Box(
                    Modifier.offset(x = x).size(thumb).align(Alignment.CenterStart)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.White)
                        .border(maxOf(1.dp, thumb / 7), if (onValue != null) accent else Modern.CHECK_EDGE, androidx.compose.foundation.shape.CircleShape),
                )
            }
        }
    }
}

/**
 * What a box being typed in keeps between drawings: the text and caret it shows, what the wiring said last, what the
 * box itself last sent it, and whether it has the keyboard.
 */
private class TypingState(shown: String) {
    // a box not being typed in has its caret at the start, as a Windows TextBox whose Text was set: a text longer
    // than the box then shows its beginning ("Prevent hostile aircraft fr…"), not its end scrolled into view
    var tfv by mutableStateOf(TextFieldValue(shown, TextRange(0)))
    var incoming = shown
    /** the texts this box sent the wiring since it last committed: the wiring echoing one back is no news */
    val sent = ArrayList<String>()
    var focused by mutableStateOf(false)
    /** on a finger's device: something gave this box the keyboard on the page (a mouse's press), so it is a real text field ([TypingField]) */
    var armed by mutableStateOf(false)
    /** something to commit when the box is left: set when it takes the keyboard and when it is typed in */
    var leavePending = false
    /** the control's rectangle in the scene, for [WdpTyping] */
    var bounds = Rect.Zero

    /**
     * The wiring's value, [shown], against what the box holds. It is taken when the box is not being typed in, and
     * while it is only when the wiring has changed it to something the box did not send (a reformat, a validation, a
     * value from elsewhere), with the caret kept where it was. An echo of a keystroke changes nothing, so the caret
     * stays where the pilot put it and no keystroke is lost however late the wiring answers.
     */
    fun take(shown: String) {
        if (shown != incoming) {
            if (!focused || shown !in sent) {
                tfv = if (focused) TextFieldValue(shown, TextRange(tfv.selection.start.coerceAtMost(shown.length), tfv.selection.end.coerceAtMost(shown.length)))
                else TextFieldValue(shown, TextRange(0))
            }
            incoming = shown
        } else if (!focused && tfv.text != shown) {
            // left, and the wiring kept what it had: the box shows the page's value, as the page will next time
            tfv = TextFieldValue(shown, TextRange(0))
        }
    }
}

/**
 * The editing every typed box shares, so that each behaves as a Windows TextBox: the box keeps its own text and
 * caret while it has the keyboard — a keystroke is never lost, the caret never jumps to the end, a character typed
 * in the middle stays in the middle — and the page's wiring hears every change ([perKey]: a TextBox, a combo's
 * text) or only the committed value (a NumericUpDown). The wiring's own answer is taken as [TypingState.take] says.
 *
 * Leaving the box commits it ([commit]): a press anywhere else on the form (before that press does anything, as
 * Windows moves the focus first), Tab, the box going away with its page, and Enter in a one-line box, which also
 * reports `"<name>:enter"` as a click — WDP's KeyDown handlers for Return, and a window's AcceptButton
 * ([WdpDialogHost]). `"<name>.focus"` = `"true"` puts the keyboard in the box when it appears, as WDP's `Focus()`.
 */
@Composable
private fun TypingField(
    c: WdpControl,
    st: TypingState,
    values: WdpValues,
    textStyle: TextStyle,
    ink: Color,
    modifier: Modifier,
    perKey: Boolean,
    singleLine: Boolean,
    onValue: (String, String) -> Unit,
    onClick: ((String) -> Unit)?,
    commit: (String) -> Unit,
    accept: (String) -> String = { it },
    keyboard: KeyboardType = KeyboardType.Text,
    /** a one-line box's [lineMid]: its figures go on the box's middle ([lineAt]); null = the line box centred */
    lineMid: Float? = null,
) {
    val typing = LocalWdpTyping.current
    val key = LocalWdpFormName.current + "/" + c.name
    val focusManager = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val latestCommit = androidx.compose.runtime.rememberUpdatedState(commit)
    val latestClick = androidx.compose.runtime.rememberUpdatedState(onClick)
    val latestValue = androidx.compose.runtime.rememberUpdatedState(onValue)
    val leave: () -> Unit = remember(st) {
        {
            if (st.leavePending) {
                st.leavePending = false
                st.sent.clear()
                latestCommit.value(st.tfv.text)
            }
        }
    }
    fun enter() {
        leave()
        latestClick.value?.invoke("${c.name}:enter")
    }
    // a box that goes away while it has the keyboard (its page changed, its window closed) is left, as in Windows
    androidx.compose.runtime.DisposableEffect(st) {
        onDispose {
            if (st.focused) {
                st.focused = false
                leave()
                if (typing.leave === leave) typing.leave = null
            }
            if (WdpProbe.on && WdpProbe.focused == key) WdpProbe.focused = null
        }
    }
    if (values["${c.name}.focus"] == "true") androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    // read here, so this part is drawn again when the box takes or loses the keyboard (the probe hears it)
    val hasKeys = st.focused
    if (WdpProbe.on) androidx.compose.runtime.SideEffect {
        WdpProbe.boxes[key] = st.tfv
        if (hasKeys) WdpProbe.focused = key else if (WdpProbe.focused == key) WdpProbe.focused = null
    }
    // On a finger's device (WdpTouch.device) a box is typed in the bar on the keyboard (WdpEditBar), so on the page it
    // is only its text until something gives it the keyboard here: a mouse's press, a finger the page did not take for
    // the bar, or the wiring's "<name>.focus". A text field is heavy to compose (selection, magnifier, menus, an input
    // session) and a page has hundreds of boxes: as text, a page switch on a tablet took a fraction of the time.
    if (WdpTouch.device && !hasKeys && !st.armed && values["${c.name}.focus"] != "true") {
        Box(
            modifier.pointerInput(st) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.type != androidx.compose.ui.input.pointer.PointerType.Touch) { st.armed = true; return@awaitEachGesture }
                    // a finger's lift the page took for the bar is consumed, and this box stays text
                    if (waitForUpOrCancellation() != null) st.armed = true
                }
            },
        ) {
            val line: @Composable () -> Unit = {
                androidx.compose.foundation.text.BasicText(
                    st.tfv.text, style = textStyle.copy(color = ink),
                    softWrap = !singleLine, maxLines = if (singleLine) 1 else Int.MAX_VALUE, overflow = TextOverflow.Clip,
                )
            }
            if (lineMid != null) Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().lineAt(lineMid) { it / 2f }, propagateMinConstraints = true) { line() }
            } else Box(Modifier.fillMaxSize(), contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) { line() }
        }
        return
    }
    // armed by a press: the keyboard comes to it at once, as the press would have given it
    if (st.armed && !hasKeys) androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    BasicTextField(
        value = st.tfv,
        onValueChange = { new ->
            if (WdpProbe.on) WdpProbe.note("$key change '${new.text}' ${new.selection}")
            val t = accept(new.text)
            // a character the box does not take (a letter in a number box) is simply not there: nothing moves
            val v = when {
                t == new.text -> new
                t == st.tfv.text -> st.tfv
                else -> TextFieldValue(t, TextRange(new.selection.end.coerceAtMost(t.length)))
            }
            val changed = v.text != st.tfv.text
            st.tfv = v
            if (changed) {
                st.leavePending = true
                if (perKey) {
                    st.sent += v.text
                    if (st.sent.size > 32) st.sent.removeAt(0)
                    latestValue.value(c.name, v.text)
                }
            }
        },
        modifier = modifier
            .focusRequester(requester)
            .onFocusChanged { f ->
                WdpProbe.note("$key focus " + f.isFocused)
                if (f.isFocused && !st.focused) {
                    st.focused = true
                    st.leavePending = true
                    st.sent.clear()
                    typing.rect = { st.bounds }
                    typing.leave = leave
                } else if (!f.isFocused && st.focused) {
                    st.focused = false
                    st.armed = false
                    leave()
                    if (typing.leave === leave) typing.leave = null
                }
            }
            .onPreviewKeyEvent { e ->
                val ch = e.utf16CodePoint
                if (WdpProbe.on && e.type == KeyEventType.KeyDown) WdpProbe.note("$key key ${e.key} on '${st.tfv.text}' ${st.tfv.selection}")
                when {
                    e.type == KeyEventType.KeyDown && singleLine && (e.key == Key.Enter || e.key == Key.NumPadEnter) -> { enter(); true }
                    e.type == KeyEventType.KeyDown && e.key == Key.Tab -> {
                        val moved = focusManager.moveFocus(if (e.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                        if (!moved) focusManager.clearFocus()
                        WdpProbe.note("$key Tab: " + if (moved) "moved on" else "nothing next, cleared")
                        true
                    }
                    // the characters Enter and Tab also type are not the box's (a one-line box has no line breaks)
                    e.type != KeyEventType.KeyDown && e.type != KeyEventType.KeyUp && (ch == 9 || singleLine && (ch == 10 || ch == 13)) -> true
                    else -> false
                }
            },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { enter() }),
        textStyle = textStyle.copy(color = ink),
        cursorBrush = SolidColor(ink),
        decorationBox = { inner ->
            if (lineMid != null) Box(Modifier.fillMaxSize()) {
                // the line laid out at its own height, even where that is taller than the box, its figures on the box's
                // middle; the caret is part of the line, so it stays on it. The box's width is handed on to the line
                // (propagateMinConstraints): a line only as wide as its text has nothing to centre or right-align in.
                Box(Modifier.fillMaxWidth().lineAt(lineMid) { it / 2f }, propagateMinConstraints = true) { inner() }
            } else Box(Modifier.fillMaxSize(), contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) { inner() }
        },
    )
}

/**
 * A Windows Forms TextBox the pilot types into. What is typed goes to the page's wiring as it is typed, and the
 * box's Leave to the wiring as `"<name>.leave"` (WDP validates most boxes there, and the wiring may put its own
 * text back: that is shown); see [TypingField] for what is Windows' in how the box behaves.
 */
@Composable
private fun Field(
    c: WdpControl, shown: String, values: WdpValues, scale: Float, p: Palette, inh: Inherited,
    onValue: (String, String) -> Unit, onClick: ((String) -> Unit)?,
) {
    val st = remember { TypingState(shown) }
    st.take(shown)
    // a TextBox's TextAlign: Left, Right or Center; a one-line box has its figures on its middle, and a value too
    // long for its box is set smaller ([boxText])
    val align = when {
        c.align?.endsWith("Right") == true -> TextAlign.End
        c.align?.endsWith("Center") == true -> TextAlign.Center
        else -> TextAlign.Start
    }
    // A one-line text field set Center or Right lays a line as wide as itself or wider out past its right edge (the
    // DataCard's Fuze box showed "NOSE" as a sliver of its N), so its text is fitted a pixel clear of the edge, and a
    // text still too long at the smallest size it is set at is set from the left, the start of it in view, as Windows
    // shows a TextBox's text that overflows.
    val room = c.w * scale - with(LocalDensity.current) { 6.dp.toPx() } - 1f
    val text = boxText(c, st.tfv.text, scale, style(c, scale, inh).copy(textAlign = align), room)
    val over = align != TextAlign.Start && !c.multiline && !fitsIn(st.tfv.text, text.style, room)
    Box(
        Modifier.fillMaxSize().boxFrame(c, scale, p, surfaceOf(c, p) ?: p.field, st.focused)
            .onGloballyPositioned { st.bounds = it.boundsInRoot() }
            .reportsTap(c.name, onClick)
            .padding(horizontal = 3.dp),
    ) {
        TypingField(
            c, st, values,
            if (over) text.style.copy(textAlign = TextAlign.Start) else text.style,
            colorOf(c.fg, p) ?: p.fieldInk,
            Modifier.fillMaxSize(),
            perKey = true, singleLine = !c.multiline, lineMid = text.mid,
            onValue = onValue, onClick = onClick,
            commit = { onValue("${c.name}.leave", "") },
        )
    }
}

/** Whether [text] set in [style] is no wider than [widthPx] on one line; true where nothing measures. */
@Composable
private fun fitsIn(text: String, style: TextStyle, widthPx: Float): Boolean {
    val m = LocalWdpMeasurer.current ?: return true
    return remember(text, style, widthPx, m) {
        runCatching { m.measure(text, style, maxLines = 1, softWrap = false).size.width <= widthPx }.getOrDefault(true)
    }
}

/** Windows 10's focused-box outline. */
private val FOCUS_BLUE = Color(0xFF0078D7)

/**
 * Arial's ascent and descent in ems (its usWinAscent and usWinDescent, 1854 and 434 of 2048): the line GDI sets WDP's
 * text in, and so where Windows puts a line in its box.
 */
private const val WIN_ASCENT = 0.905f
private const val WIN_DESCENT = 0.212f

/**
 * The middle of Windows' line, in ems above its baseline. It is also the middle of a figure or a capital — Arial's
 * are 0.716 em tall, Segoe UI's 0.700, Roboto's 0.711 — which is why a value Windows centres in its box looks centred.
 */
private const val WIN_MID = (WIN_ASCENT - WIN_DESCENT) / 2f

/**
 * Where the middle of [text]'s figures is in its line as this device lays it out: px from the top of the line box, for
 * [lineAt]. Measured on the face the device really draws with (Segoe UI on the PC, Roboto on a phone, the browser's
 * own), whose line is not Arial's: Segoe UI's has 0.17 em more room above the capitals and is 0.2 em taller, so a line
 * placed as Windows places Arial's — from the top of a label, or its line box centred in a box — sat its figures low,
 * and a line placed by a guess at its ink sat them high. Null where nothing measures (the text is then centred by its
 * line box).
 */
@Composable
private fun lineMid(text: String, style: TextStyle): Float? {
    val m = LocalWdpMeasurer.current ?: return null
    val density = LocalDensity.current
    return remember(text, style, m, density) { lineMidOf(m, text, style, density) }
}

/** [lineMid] measured with a [TextMeasurer][androidx.compose.ui.text.TextMeasurer] of the caller's own (the finger's editor, [WdpSheetHost]). */
internal fun lineMidOf(m: androidx.compose.ui.text.TextMeasurer, text: String, style: TextStyle, density: androidx.compose.ui.unit.Density): Float? =
    if (!style.fontSize.isSp) null else runCatching {
        val line = m.measure(text.ifEmpty { "0" }, style, maxLines = 1, softWrap = false)
        midOf(line.firstBaseline, with(density) { style.fontSize.toPx() })
    }.getOrNull()

/**
 * [lineMid] from a line's measured baseline and its type's size in px. The baseline is taken to the whole pixel the
 * glyphs are drawn on (the PC's hinted type snaps it there: 12.24 px is drawn on 12, 17.90 on 18), so that placing
 * the line at a whole pixel ([lineAt]) puts the figures within half a pixel of where they belong, not a pixel.
 */
private fun midOf(firstBaseline: Float, emPx: Float): Float = kotlin.math.round(firstBaseline) - WIN_MID * emPx

/**
 * Lays a line of text out at its own height, even where that is taller than the box it is in, and puts the middle of
 * its figures ([mid], from [lineMid]) [y] px below the top of that box — `{ it / 2f }` the box's middle, which is where
 * Windows' TextBox, list, grid cell and button have theirs. The box's height is what it was given (the line's own
 * where it was given none: a list's row). [mid] null leaves the line where its parent aligns it.
 */
internal fun Modifier.lineAt(mid: Float?, y: (Int) -> Float): Modifier = if (mid == null) this else layout { measurable, constraints ->
    val line = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val h = if (constraints.hasBoundedHeight) constraints.maxHeight else line.height
    val w = line.width.coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(w, h) { line.place(0, (y(h) - mid).roundToInt()) }
}

/**
 * A box's text as it is drawn: [style], and [mid], its [lineMid] — null for a box of several lines, which Windows
 * sets from the top.
 */
private class BoxText(val style: TextStyle, val mid: Float?)

/**
 * How a TextBox shows [shown] (a typed box, [Field], or a read-only one, [Face]).
 *
 * **In the middle.** Windows sizes a one-line TextBox to its font, so a value sits in the middle of its box: the middle
 * of its figures on the middle of the box ([lineAt]), for every size the text is set at and every size the page is
 * drawn at. The line is laid out at its own height even where the box is shorter than it — WDP's DataCard boxes are
 * 13 px tall under an 11 px Arial, and Segoe UI's line is 14.6 px at that size — so nothing is squeezed: with the
 * figures centred, a descender (g, p, y, a comma) ends inside a box that size, on screen and on the kneeboard.
 *
 * **Too long for its box**, a value is set smaller until it fits ([fit]), on screen and on paper alike — a CAP window
 * "04:36:13-05:06:13", a frequency, a target's name — rather than cut off at the box's edge. [roomPx] is the width the
 * text has: the box less its padding and, for a list or a number box, less its arrows. It follows what is typed, so a
 * box being typed in keeps the whole of its text in view as it grows. A box of several lines is fitted to its height.
 */
@Composable
private fun boxText(c: WdpControl, shown: String, scale: Float, base: TextStyle, roomPx: Float): BoxText {
    if (c.multiline) return BoxText(fit(shown, base, roomPx, (c.h * scale - 4f).coerceAtLeast(1f)), null)
    val style = fit(shown, base, roomPx)
    return BoxText(style, lineMid(shown, style))
}

/**
 * A NumericUpDown: the value with the two little arrows on its right, as Windows draws it. The upper arrow adds
 * `"<name>.increment"` (1 unless the wiring says) and the lower takes it away, keeping the decimals the box shows
 * ("123.5" steps to "124.5"); the page's wiring clamps, as the control's Minimum/Maximum would.
 *
 * The number itself can be typed over, as in Windows. What is typed is sent when the box is left or Enter is
 * pressed, not per key: that is when a NumericUpDown takes its value, and a wiring clamping to its Minimum would
 * otherwise turn the first digit of "150" into the minimum before the second arrived. An arrow pressed while
 * typing steps from what is typed, as Windows validates the text first.
 */
@Composable
private fun UpDown(
    c: WdpControl, shown: String, values: WdpValues, scale: Float, p: Palette, inh: Inherited,
    onValue: (String, String) -> Unit, onClick: ((String) -> Unit)?,
) {
    val st = remember { TypingState(shown) }
    st.take(shown)
    val shownNow by androidx.compose.runtime.rememberUpdatedState(shown)
    fun step(by: Int) {
        val typed = st.tfv.text.trim()
        st.leavePending = false
        upDownArrow(c.name, values, shownNow, typed.takeIf { it.isNotEmpty() && it != shownNow.trim() }, by, onValue, onClick)
    }
    // the number set smaller where it is wider than the room the arrows leave it ([boxText]), as it is typed too
    val room = c.w * scale - SPIN_W * scale - with(LocalDensity.current) { 4.dp.toPx() }
    val text = boxText(c, st.tfv.text, scale, style(c, scale, inh), room)
    Box(
        Modifier.fillMaxSize().boxFrame(c, scale, p, surfaceOf(c, p) ?: p.field, st.focused)
            .onGloballyPositioned { st.bounds = it.boundsInRoot() },
    ) {
        TypingField(
            c, st, values, text.style, colorOf(c.fg, p) ?: p.fieldInk,
            Modifier.align(Alignment.CenterStart).fillMaxSize().padding(start = 3.dp, end = px(SPIN_W * scale)).reportsTap(c.name, onClick),
            perKey = false, singleLine = true,
            onValue = onValue, onClick = onClick,
            commit = { t -> val typed = t.trim(); if (typed.isNotEmpty() && typed != shownNow.trim()) onValue(c.name, typed) },
            accept = { t -> t.filter { it.isDigit() || it == '.' || it == '-' } },
            keyboard = KeyboardType.Decimal,
            lineMid = text.mid,
        )
        SpinButtons(scale, p, Modifier.align(Alignment.CenterEnd)) { by -> step(by) }
    }
}

/** The width of a NumericUpDown's arrows, in designer pixels (Windows' is 16 with its border). */
private const val SPIN_W = 15f

/**
 * A NumericUpDown's two arrow buttons, one over the other, drawn rather than set in a font: the triangles "▲ ▼" of a
 * font are a glyph the app's face may not have, sit on its baseline rather than in the middle of their half, and at a
 * small page's size came out as a sliver or not at all. Each triangle is sized to its half of the box, with a rule
 * between the halves and down the left edge, as Windows draws the buttons. [onStep] null: drawn, and does nothing (a
 * disabled or read-only box, which Windows still draws with its arrows).
 */
@Composable
private fun SpinButtons(scale: Float, p: Palette, modifier: Modifier, onStep: ((Int) -> Unit)?) {
    if (p.modern) {
        // the modern look: no grey block, only a faint well with a hairline, arrows in a soft ink, and each half
        // lighting under the mouse and darkening as it is pressed
        Column(modifier.fillMaxHeight().width(px(SPIN_W * scale)).background(Color.Black.copy(alpha = 0.035f))) {
            for (up in booleanArrayOf(true, false)) key(up) {
                val src = remember { MutableInteractionSource() }
                val hovered = onStep != null && src.collectIsHoveredAsState().value
                val pressed = onStep != null && src.collectIsPressedAsState().value
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .then(
                            if (onStep != null) Modifier.hoverable(src).clickable(src, indication = null) { onStep(if (up) 1 else -1) }
                            else Modifier,
                        )
                        .drawBehind {
                            val w = size.width
                            val h = size.height
                            if (pressed || hovered) drawRect(Color.Black.copy(alpha = if (pressed) 0.14f else 0.07f))
                            drawLine(Color.Black.copy(alpha = 0.14f), Offset(0.5f, 0f), Offset(0.5f, h), 1f)
                            if (up) drawLine(Color.Black.copy(alpha = 0.08f), Offset(0f, h - 0.5f), Offset(w, h - 0.5f), 1f)
                            triangle(up, if (onStep != null) Modern.SOFT_INK else Modern.SOFT_INK.copy(alpha = 0.5f), minOf(w * 0.45f, h * 0.72f))
                        },
                )
            }
        }
        return
    }
    Column(modifier.fillMaxHeight().width(px(SPIN_W * scale)).background(p.button)) {
        for (up in booleanArrayOf(true, false)) {
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .then(if (onStep != null) Modifier.clickable { onStep(if (up) 1 else -1) } else Modifier)
                    .drawBehind {
                        val w = size.width
                        val h = size.height
                        drawLine(p.line, Offset(0.5f, 0f), Offset(0.5f, h), 1f)
                        if (up) drawLine(p.line, Offset(0f, h - 0.5f), Offset(w, h - 0.5f), 1f)
                        triangle(up, p.buttonInk, minOf(w * 0.5f, h * 0.8f))
                    },
            )
        }
    }
}

/** A chevron pointing [down] or up, [w] px across, stroked, in the middle of what is drawn on: a modern list's arrow. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.chevron(down: Boolean, ink: Color, w: Float) {
    val cw = w.coerceAtLeast(4f)
    val ch = cw * 0.5f
    val cx = size.width / 2f
    val cy = size.height / 2f
    val path = androidx.compose.ui.graphics.Path().apply {
        if (down) { moveTo(cx - cw / 2f, cy - ch / 2f); lineTo(cx, cy + ch / 2f); lineTo(cx + cw / 2f, cy - ch / 2f) }
        else { moveTo(cx - cw / 2f, cy + ch / 2f); lineTo(cx, cy - ch / 2f); lineTo(cx + cw / 2f, cy + ch / 2f) }
    }
    drawPath(
        path, ink,
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = (cw * 0.2f).coerceIn(1.2f, 2.4f),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        ),
    )
}

/** A filled arrow pointing [up] or down, [base] px across, in the middle of what is drawn on. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.triangle(up: Boolean, ink: Color, base: Float) {
    val b = base.coerceAtLeast(3f)
    val t = b * 0.55f
    val cx = size.width / 2f
    val cy = size.height / 2f
    val tip = if (up) cy - t / 2f else cy + t / 2f
    val foot = if (up) cy + t / 2f else cy - t / 2f
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(cx - b / 2f, foot); lineTo(cx + b / 2f, foot); lineTo(cx, tip); close()
    }
    drawPath(path, ink)
}

/**
 * A NumericUpDown's arrow pressed ([by] 1 up, -1 down) over a box showing [shown] with [typed] in it (null: nothing
 * typed that the page has not had), as the page hears it. Usually the stepped value, from what is typed when there is
 * something, as Windows validates the text first — to most pages an arrow and the same number typed are one thing.
 * A box whose `"<name>.arrows"` is `"click"` is one where they are not (the attack pages' IP STPT, whose arrows pass
 * over steerpoints with no position; Pop-up's TGT STPT, where WDP's `UpButton` and `ValidateEditText` differ): what
 * is typed is committed first as typing, then the arrow is reported by name, `"<name>:up"` / `":down"`.
 */
internal fun upDownArrow(
    name: String, values: WdpValues, shown: String, typed: String?, by: Int,
    onValue: (String, String) -> Unit, onClick: ((String) -> Unit)?,
) {
    if (values["$name.arrows"] == "click" && onClick != null) {
        if (typed != null) onValue(name, typed)
        onClick("$name:" + if (by > 0) "up" else "down")
        return
    }
    val inc = values["$name.increment"]?.toIntOrNull() ?: 1
    onValue(name, stepped(typed ?: shown, by * inc))
}

/** [shown] plus [by], written with as many decimals as it had; a box showing nothing steps from 0. */
internal fun stepped(shown: String, by: Int): String {
    val t = shown.trim()
    val places = if (t.contains('.')) t.substringAfter('.').length else 0
    val v = t.toDoubleOrNull() ?: return by.toString()
    if (places == 0) return (v.roundToLong() + by).toString()
    var unit = 1L
    repeat(places) { unit *= 10 }
    val n = (v * unit).roundToLong() + by * unit
    val a = abs(n)
    return (if (n < 0) "-" else "") + (a / unit) + "." + (a % unit).toString().padStart(places, '0')
}

/**
 * A CheckBox or RadioButton: the box or the dot, then the caption.
 *
 * Its value is its **state**, not its text: `"checked"` / `"unchecked"` under the control's name, falling back to
 * what the designer left. The caption is the designer's text unless the wiring sets `"<name>.text"`. Without this a
 * wiring that ticked a box would have replaced its caption with the word "checked".
 */
@Composable
private fun CheckOrRadio(
    c: WdpControl,
    values: WdpValues,
    designed: String,
    scale: Float,
    p: Palette,
    inh: Inherited,
    onClick: ((String) -> Unit)?,
) {
    val state = values[c.name]
    val on = when (state) { "checked" -> true; "unchecked" -> false; else -> c.checked }
    val caption = values["${c.name}.text"] ?: designed
    val markPx = (13 * scale).coerceAtMost(c.h * scale)
    val mark = px(markPx)
    val src = remember { MutableInteractionSource() }
    val hovered = p.modern && onClick != null && src.collectIsHoveredAsState().value
    Row(
        Modifier.fillMaxSize().then(
            if (onClick == null) Modifier
            else if (p.modern) Modifier.hoverable(src).clickable(src, indication = null) { onClick(c.name) }
            else Modifier.clickable { onClick(c.name) },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (p.modern) {
            // the modern look: a rounded box that fills with the accent when ticked (a white tick in it), a round one
            // with a white dot; an empty one's edge darkens under the mouse
            val accent = accentOn(inh.bg)
            val shape = if (c.kind == "radio") CircleShape else RoundedCornerShape(mark * 0.24f)
            val edge = if (on) accent else if (hovered) lerp(Modern.CHECK_EDGE, Color.Black, 0.35f) else Modern.CHECK_EDGE
            Box(
                Modifier.size(mark).clip(shape).background(if (on) accent else p.field).border(1.dp, edge, shape)
                    .then(if (on && c.kind != "radio") Modifier.drawBehind { tick(Color.White, round = true) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (on && c.kind == "radio") Box(Modifier.size(mark * 0.42f).clip(shape).background(Color.White))
            }
        } else {
        val shape = if (c.kind == "radio") CircleShape else RoundedCornerShape(1.dp)
        Box(
            Modifier.size(mark).clip(shape).background(p.field).border(1.dp, p.line, shape)
                // the tick is drawn, not set in a font: a "✓" at the caption's size in a 13 px box came out as a
                // clipped slash (the MFD tab's 72 boxes), and the app's face may not have the glyph at all
                .then(if (on && c.kind != "radio") Modifier.drawBehind { tick(p.fieldInk) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (on && c.kind == "radio") Box(Modifier.size(mark / 2).clip(shape).background(p.fieldInk))
        }
        }
        if (caption.isNotEmpty()) {
            Spacer(Modifier.width(px(3 * scale)))
            val base = style(c, scale, inh).copy(fontWeight = if (c.bold) FontWeight.Bold else FontWeight.Normal)
            val s = fit(caption, base, c.w * scale - markPx - 3 * scale - 1f)
            Text(
                // its figures level with the middle of the mark, as Windows centres a check box's caption ([lineAt])
                caption, Modifier.lineAt(lineMid(caption, s)) { it / 2f }, color = colorOf(c.fg, p) ?: inh.fg,
                style = s,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            )
        }
    }
}

/** Windows' check mark: a short stroke down and a long one up, sized to the box it is drawn in ([round]: the modern one). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.tick(ink: Color, round: Boolean = false) {
    val s = size.minDimension
    val x0 = (size.width - s) / 2f
    val y0 = (size.height - s) / 2f
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(x0 + s * 0.22f, y0 + s * 0.50f)
        lineTo(x0 + s * 0.42f, y0 + s * 0.70f)
        lineTo(x0 + s * 0.78f, y0 + s * 0.30f)
    }
    drawPath(
        path, ink,
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = (s * (if (round) 0.13f else 0.14f)).coerceAtLeast(1.4f),
            cap = if (round) androidx.compose.ui.graphics.StrokeCap.Round else androidx.compose.ui.graphics.StrokeCap.Square,
            join = if (round) androidx.compose.ui.graphics.StrokeJoin.Round else androidx.compose.ui.graphics.StrokeJoin.Miter,
        ),
    )
}

/** A group box: a hairline rectangle with its caption sitting on the top edge, the way Windows draws one. */
@Composable
private fun GroupBox(c: WdpControl, scale: Float, p: Palette, inh: Inherited, behind: Color) {
    // the modern look: a lighter hairline with rounder corners, light on a charcoal panel and dark on grey
    val ground = surfaceOf(c, p)?.takeIf { it.alpha > 0f } ?: behind
    val frame = if (p.modern) RoundedCornerShape(corner(scale, 5f, 8.dp)) else RoundedCornerShape(3.dp)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                // the frame starts half a caption down; padded, not offset, or the clip at the box takes its bottom edge
                .padding(top = px(6 * scale))
                .border(1.dp, if (p.modern) hairlineOn(ground) else p.line, frame),
        )
        c.text?.takeIf { it.isNotBlank() }?.let {
            val s = style(c, scale, inh).copy(fontWeight = if (c.bold) FontWeight.Bold else FontWeight.Normal)
            // set from the top as GDI sets it, so the frame runs through the middle of the caption's figures ([Label])
            val half = (WIN_ASCENT + WIN_DESCENT) / 2f * with(LocalDensity.current) { if (s.fontSize.isSp) s.fontSize.toPx() else 0f }
            Text(
                it,
                // the caption cuts the frame with whatever is behind the group — WDP's charcoal panels, mostly
                Modifier.lineAt(lineMid(it, s)) { half }
                    .offset(px(8 * scale), 0.dp).background(surfaceOf(c, p)?.takeIf { it.alpha > 0f } ?: behind).padding(horizontal = 3.dp),
                color = colorOf(c.fg, p) ?: (if (p.literal) inh.fg else p.caption),
                style = s, maxLines = 1, softWrap = false,
            )
        }
    }
}

@Composable
private fun Face(
    c: WdpControl,
    shown: String,
    scale: Float,
    p: Palette,
    inh: Inherited,
    fill: Color,
    ink: Color,
    onClick: ((String) -> Unit)?,
    raised: Boolean = false,
    sunken: Boolean = false,
    /** px at the box's right edge that its text keeps clear of: a list's drop-down arrow, a number box's arrows */
    reserve: Float = 0f,
    /** a number box shown read-only or disabled: its arrows are drawn, as Windows draws them, and do nothing */
    spin: Boolean = false,
) {
    // The modern look: a button is flat with slightly rounded corners, a thin edge of its own colour and a faint shade
    // along its foot, lighter under the mouse and darker while pressed; a box is framed as a typed box is ([boxFrame]),
    // its edge darkening under the mouse where a press opens something (a list). Windows' square hairline otherwise.
    val live = p.modern && onClick != null
    val src = remember { MutableInteractionSource() }
    val hovered = live && src.collectIsHoveredAsState().value
    val pressed = live && src.collectIsPressedAsState().value
    var m = Modifier.fillMaxSize()
    m = when {
        !p.modern -> m.clip(RoundedCornerShape(2.dp)).background(fill)
            .then(if (raised || sunken) Modifier.edgeUnder(1.dp, p.line, 2.dp) else Modifier)
        raised -> {
            val shape = RoundedCornerShape(cornerFor(scale, 3f, 6.dp, c.w, c.h))
            val face = when { pressed -> pressOf(fill); hovered -> hoverOf(fill); else -> fill }
            val edge = if (fill.isDark()) Color.White.copy(alpha = if (hovered) 0.34f else 0.2f)
                else edgeOf(fill).let { if (hovered) lerp(it, Color.Black, 0.2f) else it }
            m.clip(shape).background(face).then(if (pressed) Modifier else Modifier.buttonShade()).border(1.dp, edge, shape)
        }
        else -> m.boxFrame(c, scale, p, fill, focused = false, hovered = hovered)
    }
    if (onClick != null) m = if (live) m.hoverable(src).clickable(src, indication = null) { onClick(c.name) } else m.clickable { onClick(c.name) }
    val density = LocalDensity.current
    val weight = if (c.bold) FontWeight.Bold else FontWeight.Normal
    val italic = if (c.italic) FontStyle.Italic else FontStyle.Normal
    val base = style(c, scale, inh).copy(fontWeight = weight, fontStyle = italic)
    // a box's value (a read-only TextBox, a list's choice, and every box on paper): its figures on the box's middle,
    // and set smaller when too long for it ([boxText]), as a typed box is; a button's caption is fitted to the button
    // the same way, and a one-line caption centred as Windows centres it
    val text = if (sunken && shown.isNotEmpty()) boxText(c, shown, scale, base, c.w * scale - reserve - with(density) { 6.dp.toPx() }) else null
    // a button tall enough for two lines wraps its caption, as Windows' does, and is fitted to its height
    val fontPx = (c.fontSize ?: inh.fontSizePt ?: 8.25).toFloat() * 1.333f * scale
    val wraps = c.multiline || !sunken && c.h * scale >= fontPx * 2.4f
    val shownStyle = text?.style ?: fit(shown, base, c.w * scale - with(density) { 4.dp.toPx() }, if (wraps) c.h * scale - 2f else null)
    val mid = when {
        wraps || shown.isEmpty() -> null
        text != null -> text.mid
        else -> lineMid(shown, shownStyle)
    }
    // a TextBox of several lines is set from its top, as Windows sets it
    Box(m, contentAlignment = if (sunken && c.multiline) Alignment.TopStart else if (sunken) Alignment.CenterStart else Alignment.Center) {
        if (shown.isNotEmpty()) {
            Text(
                shown,
                Modifier.padding(start = if (sunken) 3.dp else 0.dp, end = if (sunken) 3.dp + px(reserve) else 0.dp)
                    .lineAt(mid) { it / 2f },
                color = colorOf(c.fg, p) ?: ink,
                style = shownStyle,
                maxLines = if (wraps) Int.MAX_VALUE else 1,
                softWrap = wraps,
                overflow = TextOverflow.Clip,
                textAlign = if (sunken) TextAlign.Start else TextAlign.Center,
            )
        }
        if (spin) SpinButtons(scale, p, Modifier.align(Alignment.CenterEnd), null)
    }
}

@Composable
private fun Label(c: WdpControl, shown: String, scale: Float, p: Palette, inh: Inherited) {
    // Windows Forms: TopLeft unless the designer said otherwise. "MiddleCenter" is vertical then horizontal.
    val a = c.align ?: "TopLeft"
    // A label only a little taller than its type — WDP's value labels are 12 px boxes with an 11 px font — is not
    // clipped (ControlBody): its line may run past the box, as it does in Windows, where only a descender shows below.
    val fontPx = ((c.fontSize ?: inh.fontSizePt ?: 8.25).toFloat()) * 1.333f
    val tight = c.h < fontPx * 1.6f
    val vertical = when { a.startsWith("Middle") -> 1; a.startsWith("Bottom") -> 2; else -> 0 }
    val horizontal = when { a.endsWith("Right") -> 2; a.endsWith("Center") -> 1; else -> 0 }
    // A label the designer made one line is one line: a word that spilled into a second row would land on the
    // control underneath, so it is clipped at the box instead, which is what Windows does. A box with room for
    // several lines is a label with AutoSize off (an AutoSize label's box is its one line of text), and Windows
    // wraps those at word breaks — the Performance page's Climb Explanation is a paragraph in such a box.
    val rows = if (c.multiline) 3 else if (!tight && c.h >= fontPx * 2.4f) (c.h / (fontPx * 1.2f)).toInt() else 1
    // Windows draws a label's text a sixth of the font's height in from either side (TextRenderer's padding);
    // without it a value right-aligned against its unit ran into it — "100feet"
    val pad = fontPx * scale / 6f
    // a wrapped paragraph at Arial's own line spacing, as GDI sets it; Compose's default is looser
    val base = style(c, scale, inh).let { if (rows > 1) it.copy(lineHeight = 1.15.em) else it }
        .copy(fontWeight = if (c.bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (c.italic) FontStyle.Italic else FontStyle.Normal)
    // One line is placed where GDI places it in the box — from the top, in the middle or at the foot, as the designer
    // aligned it — with Arial's line, whose middle is the middle of the figures ([WIN_MID]); this device's face is
    // then set with its figures there ([lineAt]). A value label barely taller than its type (the DataCard's HDG, Dist
    // and Alt cells, the DTC's frequencies, TOSS's figures) so has its figures in the middle of its box, as in WDP;
    // laid from the top of the app's own line, whose face has more room above its capitals, they sat low in it.
    val em = with(LocalDensity.current) { if (base.fontSize.isSp) base.fontSize.toPx() else fontPx * scale }
    val half = (WIN_ASCENT + WIN_DESCENT) / 2f * em
    val lineY: (Int) -> Float = when (vertical) { 1 -> { h -> h / 2f }; 2 -> { h -> h - half }; else -> { _ -> half } }
    val placed = rows == 1 && LocalWdpMeasurer.current != null
    // a paragraph (and a line where nothing measures) keeps the Box's own alignment: from the top where the label is
    // barely taller than its type, as the designer said otherwise
    val align = if (placed) {
        when (horizontal) { 2 -> Alignment.TopEnd; 1 -> Alignment.TopCenter; else -> Alignment.TopStart }
    } else when (if (tight) 0 else vertical) {
        1 -> when (horizontal) { 2 -> Alignment.CenterEnd; 1 -> Alignment.Center; else -> Alignment.CenterStart }
        2 -> when (horizontal) { 2 -> Alignment.BottomEnd; 1 -> Alignment.BottomCenter; else -> Alignment.BottomStart }
        else -> when (horizontal) { 2 -> Alignment.TopEnd; 1 -> Alignment.TopCenter; else -> Alignment.TopStart }
    }
    Box(
        Modifier.fillMaxWidth().then(if (tight && !placed) Modifier.wrapContentHeight(Alignment.Top, unbounded = true) else Modifier.fillMaxSize())
            .background(surfaceOf(c, p) ?: Color.Transparent),
        contentAlignment = align,
    ) {
        if (shown.isNotEmpty()) {
            // a caption spaced out to put its unit beyond the figure laid over it: each piece where GDI puts it
            val pieces = if (rows == 1 && horizontal == 0 && !c.autoSize) spacedPieces(shown) else null
            if (pieces != null) {
                SpacedCaption(shown, pieces, base, c, scale, pad, fontPx, colorOf(c.fg, p) ?: inh.fg, lineY)
            } else {
            // too long for its box, a label's text is set smaller until it fits ([fit]): a DataCard frequency, a
            // Mach/GS pair, a CAP window, a paragraph one line too long for its box. An AutoSize label grows instead.
            val shownStyle = if (c.autoSize) base else fit(shown, base, c.w * scale - 2 * pad, if (rows > 1) c.h * scale else null)
            val mid = if (rows == 1) lineMid(shown, shownStyle) else null
            Text(
                shown,
                // an AutoSize label (the Loadout window's aircraft name) grows to the right when its text is longer
                // than the designer's placeholder, as Windows grows it; shorter, it sits where the designer aligned it
                Modifier.padding(horizontal = px(pad))
                    .then(if (c.autoSize) Modifier.wrapContentWidth(Alignment.Start, unbounded = true) else Modifier)
                    .lineAt(mid, lineY),
                color = colorOf(c.fg, p) ?: inh.fg,
                style = shownStyle,
                // a paragraph takes the lines its box has room for (the box clips, so nothing spills onto the
                // control under it); set smaller, that is more lines than at its own size
                maxLines = if (rows > 1 && !tight) Int.MAX_VALUE else rows,
                softWrap = rows > 1,
                overflow = TextOverflow.Clip,
                textAlign = when (horizontal) { 2 -> TextAlign.End; 1 -> TextAlign.Center; else -> TextAlign.Start },
            )
            }
        }
    }
}

/**
 * The pieces of a caption WDP spaced out with a run of three or more blanks to put a unit beyond the figure the designer
 * laid over the gap ("Bingo Fuel                        lbs", "ALOW TFadv                   feet", "DEST     OA1"),
 * each with where it starts in the caption; null for any other text. Leading blanks stay with the first piece.
 */
internal fun spacedPieces(s: String): List<Pair<Int, String>>? {
    var out: ArrayList<Pair<Int, String>>? = null
    var start = 0
    var i = 0
    while (i < s.length) {
        if (s[i] != ' ') { i++; continue }
        var j = i
        while (j < s.length && s[j] == ' ') j++
        if (j - i >= 3 && j < s.length && s.substring(start, i).isNotBlank()) {
            (out ?: ArrayList<Pair<Int, String>>().also { out = it }).add(start to s.substring(start, i))
            start = j
        }
        i = j
    }
    val list = out ?: return null
    list.add(start to s.substring(start).trimEnd())
    return list
}

/** Arial's advance widths (Helvetica's metrics, thousandths of an em) for ' ' to '~'. */
private val ARIAL_ADVANCE = intArrayOf(
    278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
    556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
    1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
    667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
    333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
    556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
)

/**
 * How far GDI sets the first [count] characters of [s] in Arial at [emPx] designer pixels: each advance rounded to a
 * whole pixel, as a hinted face is laid out on the screen (an 11 px space is 3 px, a 13 px one 4).
 */
internal fun gdiAdvance(s: String, count: Int, emPx: Float, bold: Boolean): Float {
    var x = 0
    for (k in 0 until count.coerceAtMost(s.length)) {
        val ch = s[k]
        val w = if (ch == '°') 400 else ARIAL_ADVANCE.getOrNull(ch.code - 32) ?: 556
        x += kotlin.math.round(w * (if (bold) 1.07f else 1f) * emPx / 1000f).toInt()
    }
    return x.toFloat()
}

/**
 * A caption WDP spaced out to line a unit up beyond a figure ([spacedPieces]), laid out a piece at a time: each piece
 * starts where GDI sets it in the caption ([gdiAdvance]), so the unit sits where the designer saw it, clear of the box
 * over the gap. Laid out as one string in the app's face, whose blanks are narrower than GDI's whole pixels, the unit
 * slid under the box ("400feet", "8sec"). A piece never starts before the one ahead of it ends; the last is set smaller
 * where it would run past the caption's box ([fitFactor]). Each piece has its figures at [lineY], as the label's one
 * line would ([lineAt]).
 */
@Composable
private fun SpacedCaption(
    shown: String, pieces: List<Pair<Int, String>>, base: TextStyle, c: WdpControl, scale: Float, pad: Float,
    fontPx: Float, ink: Color, lineY: (Int) -> Float,
) {
    val m = LocalWdpMeasurer.current
    val density = LocalDensity.current
    val placed = remember(shown, base, scale, c.w, pad, m, density) {
        val em = fontPx
        val gap = kotlin.math.round(278 * em / 1000f) * scale
        var end = 0f
        pieces.mapIndexed { i, (start, piece) ->
            val x = if (i == 0) 0f else maxOf(gdiAdvance(shown, start, em, c.bold) * scale, end + gap)
            val room = c.w * scale - 2 * pad - x
            val k = if (i == pieces.lastIndex && m != null && room > 1f) runCatching { fitFactor(m, piece, base, room, null) }.getOrDefault(1f) else 1f
            val st = if (k < 1f) base.copy(fontSize = base.fontSize * k) else base
            val line = m?.let { runCatching { it.measure(piece, st, maxLines = 1, softWrap = false) }.getOrNull() }
            end = x + (line?.size?.width?.toFloat() ?: 0f)
            val mid = line?.let { l -> st.fontSize.takeIf { it.isSp }?.let { midOf(l.firstBaseline, with(density) { it.toPx() }) } }
            PlacedPiece(piece, x, st, mid)
        }
    }
    for ((piece, x, st, mid) in placed) {
        Text(
            piece,
            Modifier.padding(start = px(pad)).offset(x = px(x)).wrapContentWidth(Alignment.Start, unbounded = true).lineAt(mid, lineY),
            color = ink, style = st, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        )
    }
}

/** A piece of a [SpacedCaption]: its text, where it starts, its type, and its [lineMid]. */
private data class PlacedPiece(val text: String, val x: Float, val style: TextStyle, val mid: Float?)

// ---------------------------------------------------------------- tabs, grids, lists, drop-downs

/**
 * Which page of a TabControl is up: the one the wiring names (`values[tabControl]` = a TabPage's name), else the one
 * the pilot last picked on an unwired page, else the first page the designer did not hide.
 */
private fun selectedTab(c: WdpControl, values: WdpValues, local: String?): String? {
    val pages = c.children.filter { !it.hidden || values[it.name] == "shown" }.filter { values[it.name] != "hidden" }
    return values[c.name]?.takeIf { v -> pages.any { it.name == v } }
        ?: local?.takeIf { v -> pages.any { it.name == v } }
        ?: pages.firstOrNull()?.name
}

/**
 * A TabControl's strip of tabs, across the top of the control where Windows draws it (the pages start below it).
 * A tap selects the page — on the page itself, and through the wiring as `onValue(tabControl, pageName)`.
 */
@Composable
private fun TabStrip(c: WdpControl, selected: String?, scale: Float, p: Palette, inh: Inherited, behind: Color, z: String, onPick: (String) -> Unit) {
    val pages = c.children.filter { !it.hidden }
    val stripH = ((pages.minOfOrNull { it.y } ?: 22) - 2).coerceAtLeast(16)
    if (p.modern) { ModernTabStrip(c, pages, selected, stripH, scale, p, inh, behind, z, onPick); return }
    Row(
        Modifier.fillMaxWidth().height(px(stripH * scale)).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (t in pages) {
            val on = t.name == selected
            Box(
                Modifier.fillMaxHeight(if (on) 1f else 0.88f)
                    .background(if (on) p.panel else p.button)
                    .border(1.dp, p.line)
                    .clickable { onPick(t.name) }
                    .padding(horizontal = px(8 * scale)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.text ?: t.name, color = if (on) p.ink else p.buttonInk,
                    style = style(c, scale, inh), maxLines = 1, softWrap = false,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * The modern look's tab strip: the tabs are flat, the one up has rounded top corners, the colour of its page and a
 * line of the accent along its top, and reaches down to join its page; the others are text on the strip's ground,
 * dimmer, with a faint wash under the mouse. The first tab lines up with the pages' left edge. Same order, same
 * captions, same place as the Windows strip.
 */
@Composable
private fun ModernTabStrip(
    c: WdpControl, pages: List<WdpControl>, selected: String?, stripH: Int, scale: Float, p: Palette, inh: Inherited,
    behind: Color, z: String, onPick: (String) -> Unit,
) {
    val top = pages.minOfOrNull { it.y } ?: (stripH + 2)
    val left = (pages.minOfOrNull { it.x } ?: 2).coerceIn(0, 6)
    val r = corner(scale, 4f, 7.dp)
    val dark = behind.isDark()
    val fingers = LocalWdpTouchTargets.current?.takeIf { !LocalWdpPrint.current }
    val form = LocalWdpFormName.current
    Row(
        Modifier.fillMaxWidth().height(px(top * scale)).horizontalScroll(rememberScrollState()).padding(start = px(left * scale)),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (t in pages) key(t.name) {
            val on = t.name == selected
            val fill = surfaceOf(t, p)?.takeIf { it.alpha > 0f } ?: p.panel
            val src = remember { MutableInteractionSource() }
            val hovered by src.collectIsHoveredAsState()
            val ink = when {
                on -> if (fill.isDark()) Color.White else Color(0xFF14181D)
                dark -> Color.White.copy(alpha = if (hovered) 0.95f else 0.66f)
                else -> Color.Black.copy(alpha = if (hovered) 0.9f else 0.6f)
            }
            val accent = accentOn(fill)
            // a tab page's tooltip is its tab's (WdpTips): over the page itself it would cover every label on it
            val tabTip = LocalWdpTips.current[t.name]
            Box(
                Modifier.fillMaxHeight(if (on) 1f else (stripH * 0.9f / top).coerceIn(0.5f, 1f))
                    // a finger's reach round the tab, while the Planner is worked by one (WdpTouch)
                    .touchTarget(fingers, "$form/${c.name}#${t.name}", t.name) { this.z = z + "~"; kind = TouchKind.PRESS; tap = { onPick(t.name) }; tip = tabTip }
                    .then(if (tabTip != null) Modifier.wdpTip("$form/${t.name}#tab", tabTip) else Modifier)
                    .clip(RoundedCornerShape(topStart = r, topEnd = r))
                    .background(
                        when {
                            on -> fill
                            hovered -> if (dark) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.06f)
                            else -> Color.Transparent
                        },
                    )
                    .then(
                        if (on) Modifier.drawBehind { drawRect(accent, size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
                        else Modifier,
                    )
                    .hoverable(src).clickable(src, indication = null) { onPick(t.name) }
                    .padding(horizontal = px(8 * scale)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.text ?: t.name, color = ink,
                    style = style(c, scale, inh), maxLines = 1, softWrap = false,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** One row of a grid or list as the wiring gives it: cells separated by tabs. */
private fun rowsOf(values: WdpValues, name: String): List<List<String>> =
    values["$name.rows"]?.takeIf { it.isNotEmpty() }?.split('\n')?.map { it.split('\t') } ?: emptyList()

/**
 * A DataGridView: the designer's column headers (or the wiring's `"<grid>.columns"`, tab-separated), and the
 * wiring's `"<grid>.rows"` (rows separated by new lines, cells by tabs). `"<grid>.selected"` is the highlighted
 * row. A tap on a row is `onClick("<grid>:row:<i>")`; a double tap, which in WDP opens the row's Change dialog,
 * is `onClick("<grid>:open:<i>")`.
 */
@Composable
private fun Grid(c: WdpControl, values: WdpValues, scale: Float, p: Palette, inh: Inherited, onClick: ((String) -> Unit)?) {
    val cols = values["${c.name}.columns"]?.split('\t') ?: c.columns
    val widths = values["${c.name}.widths"]?.split('\t')?.mapNotNull { it.toIntOrNull() } ?: c.columnWidths
    val rows = rowsOf(values, c.name)
    val sel = values["${c.name}.selected"]?.toIntOrNull() ?: -1
    val rowH = px(22 * scale)
    val cellStyle = style(c, scale, inh)
    @Composable
    fun Cells(cells: List<String>, ink: Color, bold: Boolean) {
        for (i in (if (cols.isEmpty()) cells.indices else cols.indices)) {
            val w = widths.getOrNull(i) ?: 100
            val cell = cells.getOrNull(i) ?: ""
            // a cell's text set smaller where it is wider than its column ([fit]), rather than cut off
            val s = fit(cell, cellStyle.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal), (w - 6) * scale)
            Text(
                cell,
                // its figures on the row's middle, where a DataGridView centres a cell's text ([lineAt])
                Modifier.width(px(w * scale)).padding(horizontal = px(3 * scale)).lineAt(lineMid(cell, s)) { it / 2f },
                color = ink, style = s, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            )
        }
    }
    // the modern look: rounded, a light header with a rule under it, a hairline between rows, the accent on the
    // selected one; the Windows grid otherwise
    val frame = if (p.modern) RoundedCornerShape(corner(scale, 3f, 6.dp)) else null
    Column(
        Modifier.fillMaxSize().then(if (frame != null) Modifier.clip(frame) else Modifier).background(p.field)
            .then(if (frame != null) Modifier.border(1.dp, Modern.LINE, frame) else Modifier.border(1.dp, p.line)),
    ) {
        if (cols.isNotEmpty()) Row(
            Modifier.fillMaxWidth().height(rowH).background(if (p.modern) Modern.HEADER else p.button)
                .then(if (p.modern) Modifier.drawBehind { drawLine(Modern.LINE, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cells(cols, p.buttonInk, bold = true)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { r, cells ->
                Row(
                    Modifier.fillMaxWidth().height(rowH)
                        .background(if (r == sel) (if (p.modern) Modern.ACCENT else Color(0xFF0078D7)) else Color.Transparent)
                        .then(
                            if (p.modern && r != sel) Modifier.drawBehind {
                                drawLine(Color.Black.copy(alpha = 0.07f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
                            } else Modifier,
                        )
                        .rowTaps(c.name, r, onClick),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cells(cells, if (r == sel) Color.White else p.fieldInk, bold = false)
                }
            }
        }
    }
}

/**
 * A ListBox: `"<list>.items"` (new-line separated; the designer's items otherwise) with `"<list>.selected"`
 * highlighted. Taps as a grid's: `"<list>:row:<i>"`, and `"<list>:open:<i>"` for a double tap.
 */
@Composable
private fun ListBox(c: WdpControl, values: WdpValues, scale: Float, p: Palette, inh: Inherited, onClick: ((String) -> Unit)?) {
    val items = values["${c.name}.items"]?.let { if (it.isEmpty()) emptyList() else it.split('\n') } ?: c.items
    val sel = values["${c.name}.selected"]?.toIntOrNull() ?: -1
    val itemStyle = style(c, scale, inh)
    val fill = surfaceOf(c, p) ?: p.field
    val frame = if (p.modern) RoundedCornerShape(corner(scale, 3f, 6.dp)) else null
    Column(
        Modifier.fillMaxSize().then(if (frame != null) Modifier.clip(frame) else Modifier).background(fill)
            .then(if (frame != null) Modifier.border(1.dp, boxEdge(fill), frame) else Modifier.border(1.dp, p.line))
            .verticalScroll(rememberScrollState()),
    ) {
        items.forEachIndexed { i, item ->
            val s = fit(item, itemStyle, (c.w - 8) * scale)
            Text(
                item,
                Modifier.fillMaxWidth()
                    .background(if (i == sel) (if (p.modern) Modern.ACCENT else Color(0xFF0078D7)) else Color.Transparent)
                    .rowTaps(c.name, i, onClick)
                    .padding(horizontal = px(3 * scale), vertical = px(1 * scale))
                    // its figures on the middle of the row (and of its highlight), as a ListBox sets an item ([lineAt])
                    .lineAt(lineMid(item, s)) { it / 2f },
                color = if (i == sel) Color.White else colorOf(c.fg, p) ?: p.fieldInk,
                style = s, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            )
        }
    }
}

/**
 * A row of a grid or a list answering the mouse as a DataGridView's and a ListBox's do: the first click selects the
 * row at once (`"<control>:row:<i>"`), and a second click on it soon after is the double click that opens it
 * (`"<control>:open:<i>"`), after the first has already selected it. Compose's own double-tap detection holds every
 * single tap back until the double-tap time has run out, and turned a quick second click into an open instead of a
 * select: a row answered only on the second try — the "click the weapon row twice" the pilot reported.
 *
 * The handler is read when a click lands, not keyed into the gesture: the first click redraws the page with a new
 * handler, and a gesture keyed on it would restart there and never see the second click.
 */
@Composable
private fun Modifier.rowTaps(name: String, row: Int, onClick: ((String) -> Unit)?): Modifier {
    val latest = androidx.compose.runtime.rememberUpdatedState(onClick)
    if (onClick == null) return this
    return pointerInput(name, row) {
        awaitEachGesture {
            awaitFirstDown()
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
            up.consume()
            latest.value?.invoke("$name:row:$row")
            withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() } ?: return@awaitEachGesture
            val up2 = waitForUpOrCancellation() ?: return@awaitEachGesture
            up2.consume()
            latest.value?.invoke("$name:open:$row")
        }
    }
}

/**
 * A ComboBox. With items — the wiring's `"<combo>.items"` (new-line separated) or the designer's — a tap opens the
 * list and picking one is `onValue(combo, item)`, as WDP's SelectedIndexChanged. Without items it reports a tap,
 * for a wiring that steps through its own list.
 *
 * A combo whose text WDP reads as typed (`"<combo>.editable"` = `"true"`, Windows' DropDown style: the Names
 * window's pilots) is a box to type in as well: what is typed goes to the wiring as it is typed, like a TextBox's
 * ([TypingField]), and the arrow opens the list. The others are lists to pick from, whatever the designer's style:
 * WDP reads their selected item, so a typed text there would be thrown away.
 */
@Composable
private fun Combo(
    c: WdpControl, shown: String, values: WdpValues, scale: Float, p: Palette, inh: Inherited,
    onValue: ((String, String) -> Unit)?, onClick: ((String) -> Unit)?,
) {
    val items = values["${c.name}.items"]?.let { if (it.isEmpty()) emptyList() else it.split('\n') } ?: c.items
    var open by remember { mutableStateOf(false) }
    val drop = items.isNotEmpty() && onValue != null
    val editable = onValue != null && values["${c.name}.editable"] == "true"
    val paper = LocalWdpPrint.current
    // a list the wiring wants dropped open as it appears ("<combo>.drop", a token that changes with each ask)
    val dropAsk = values["${c.name}.drop"]
    androidx.compose.runtime.LaunchedEffect(dropAsk, drop) { if (!dropAsk.isNullOrEmpty() && drop && !paper) open = true }
    // the arrow's width, which the text keeps clear of (none on paper, where the box is the choice alone)
    val arrow = if (paper) 0f else 16f * scale
    Box(Modifier.fillMaxSize()) {
        if (editable && onValue != null) {
            val st = remember { TypingState(shown) }
            st.take(shown)
            val text = boxText(c, st.tfv.text, scale, style(c, scale, inh), c.w * scale - arrow - with(LocalDensity.current) { 4.dp.toPx() })
            Box(
                Modifier.fillMaxSize().boxFrame(c, scale, p, surfaceOf(c, p) ?: p.field, st.focused)
                    .onGloballyPositioned { st.bounds = it.boundsInRoot() },
            ) {
                TypingField(
                    c, st, values, text.style, colorOf(c.fg, p) ?: p.fieldInk,
                    Modifier.fillMaxSize().padding(start = 3.dp, end = px(arrow)),
                    perKey = true, singleLine = true,
                    onValue = onValue, onClick = onClick,
                    commit = { onValue("${c.name}.leave", "") },
                    lineMid = text.mid,
                )
            }
        } else {
            Face(
                c, shown, scale, p, inh, surfaceOf(c, p) ?: p.field, p.fieldInk, if (drop) ({ _ -> open = true }) else onClick,
                sunken = true, reserve = arrow,
            )
        }
        // Windows' drop-down button, so the box reads as a list and not a field (not on paper, where it is the choice);
        // the arrow drawn, sized to the button, as a font's "▾" sat on its baseline and was a speck on a small page
        // (the modern look: no grey button, only a chevron in the box, fainter where the list cannot be opened)
        val boxFill = surfaceOf(c, p) ?: p.field
        if (!paper) Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(px(arrow))
                .then(if (p.modern) Modifier else Modifier.background(p.button))
                .then(if (editable && drop) Modifier.clickable { open = true } else Modifier)
                .drawBehind {
                    if (p.modern) {
                        val ink = if (boxFill.isDark()) Color.White.copy(alpha = 0.8f) else Modern.SOFT_INK
                        chevron(true, if (drop || onClick != null) ink else ink.copy(alpha = 0.45f), minOf(size.width * 0.5f, size.height * 0.42f))
                    } else {
                        drawLine(p.line, Offset(0.5f, 0f), Offset(0.5f, size.height), 1f)
                        triangle(false, p.buttonInk, minOf(size.width * 0.5f, size.height * 0.4f))
                    }
                },
        )
        // the list at the page's size, but never smaller than a list can be read at on a phone
        val itemStyle = style(c, scale, inh).let { s -> if (s.fontSize.value < 13f) s.copy(fontSize = 13.sp) else s }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in items) {
                DropdownMenuItem(
                    text = { Text(item, style = itemStyle) },
                    onClick = { open = false; onValue?.invoke(c.name, item) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- sizing and colour

/**
 * The type for a control, fitted to the box the designer gave it.
 *
 * A Windows Forms font is in points and a point is 1.33 px, so 8.25 pt is 11 px — and most of WDP's labels are
 * 14 px tall. The size is the smaller of the font's own height and what the box can hold, scaled with the page and
 * turned into sp through the device density (the same conversion the box went through). The line height is set to
 * the font size with its leading trimmed, so it is the glyphs that have to fit the box and not the line.
 */
@Composable
private fun style(c: WdpControl, scale: Float, inh: Inherited): TextStyle {
    // Sized from the font alone, as Windows does; a box too small for it clips, as Windows does. Fitting the type
    // to the box instead made a 12 px value label smaller than the 14 px unit beside it, which WDP never shows.
    val pt = (c.fontSize ?: inh.fontSizePt ?: 8.25).toFloat()
    val px = pt * 1.333f * scale
    val size: TextUnit = with(LocalDensity.current) { px.coerceAtLeast(5f).toSp() }
    // No line-height trimming: squeezing the line to the em box cropped the tops of the digits. Windows never
    // clips the top of a label — only whatever runs off the bottom — and with the text top-aligned and the control
    // clipped to its bounds, that is exactly what happens here without any help.
    return TextStyle(fontSize = size)
}

/**
 * The smallest a value is set to fit its box, as a share of its own type: three fifths, which at the page's scale is
 * still read at a glance (an 11 px value comes down to 6.6 px at 1:1, and grows with the window as the page does).
 */
internal const val FIT_MIN = 0.6f

/**
 * [base] set smaller where [text] does not fit the room it has, so that a value is never cut off at the edge of its
 * box: WDP's boxes are sized to Arial's figures and the app's face is wider, so a frequency ("254.725"), a CAP window
 * ("04:36:13-05:06:13"), a Mach/GS pair or a name lost its last characters. One line ([heightPx] null): the text must
 * be no wider than [widthPx]. Wrapped ([heightPx] set): the lines it wraps into at [widthPx] must be no taller than
 * [heightPx]. The type comes down in small steps to [FIT_MIN] of its size and no further; below that the box clips, as
 * Windows' does. Text that fits is left exactly as it was, so a page whose values fit looks as WDP's does.
 */
@Composable
private fun fit(text: String, base: TextStyle, widthPx: Float, heightPx: Float? = null): TextStyle {
    val m = LocalWdpMeasurer.current
    if (m == null || text.isEmpty() || widthPx <= 1f || !base.fontSize.isSp) return base
    val k = remember(text, base, widthPx, heightPx) { runCatching { fitFactor(m, text, base, widthPx, heightPx) }.getOrDefault(1f) }
    return if (k >= 1f) base else base.copy(fontSize = base.fontSize * k)
}

/** How much of its size [style] keeps for [text] to fit ([fit]); 1 when it fits already. */
internal fun fitFactor(m: androidx.compose.ui.text.TextMeasurer, text: String, style: TextStyle, widthPx: Float, heightPx: Float?): Float {
    fun fits(k: Float): Boolean {
        val s = if (k == 1f) style else style.copy(fontSize = style.fontSize * k)
        return if (heightPx == null) m.measure(text, s, maxLines = 1, softWrap = false).size.width <= widthPx + 0.5f
        else m.measure(text, s, softWrap = true, constraints = Constraints(maxWidth = widthPx.toInt().coerceAtLeast(1))).size.height <= heightPx + 0.5f
    }
    if (fits(1f)) return 1f
    // one line: the width is near enough proportional to the size, so the first guess is close and a step or two finds it
    var k = if (heightPx == null) {
        val w = m.measure(text, style, maxLines = 1, softWrap = false).size.width.toFloat()
        (widthPx / w.coerceAtLeast(1f)).coerceIn(FIT_MIN, 1f)
    } else 0.95f
    while (k > FIT_MIN && !fits(k)) k -= 0.03f
    return k.coerceAtLeast(FIT_MIN)
}

/**
 * A control's own background. Under the WDP look it is used as the designer set it; under the HUD look a grey is
 * mapped to the nearest app surface by how light it is, and anything with real colour — the black DED boxes, a
 * green "YES" — is kept, because those are meaning, not decoration.
 */
private fun surfaceOf(c: WdpControl, p: Palette): Color? {
    val raw = colorOf(c.bg, p) ?: return null
    if (p.literal) return raw
    val sat = maxOf(raw.red, raw.green, raw.blue) - minOf(raw.red, raw.green, raw.blue)
    if (sat > 0.18f) return raw
    val lum = 0.3f * raw.red + 0.59f * raw.green + 0.11f * raw.blue
    return when {
        lum < 0.12f -> Hud.Bg
        lum < 0.45f -> Hud.Surface
        else -> Hud.Surface2
    }
}

private fun colorOf(name: String?, p: Palette): Color? {
    if (name.isNullOrBlank()) return null
    // "#rrggbb" from the layout; "#aarrggbb" or bare "aarrggbb" (Color.ToArgb's hex) from a wiring
    val hex = name.removePrefix("#")
    if (hex.length == 6 || hex.length == 8 && (name.startsWith("#") || hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })) {
        val v = hex.toLongOrNull(16)
        if (v != null) {
            val c = Color(if (hex.length == 6) 0xFF000000 or v else v)
            // WDP's charcoal, from the layout or from a wiring (a ForeColor set to the panel's colour hides a label
            // on it, so the two stay one colour): the Planner's charcoal under the WDP look
            return if (p.modern && c == Modern.WDP_CHARCOAL) Modern.CHARCOAL else c
        }
    }
    // the named colours Falcas used; literal under the WDP look, the app's equivalents under the HUD look
    return if (p.literal) when (name) {
        "White" -> Color.White
        "Black" -> Color.Black
        "Red" -> Color(0xFFFF0000)
        "Lime" -> Color(0xFF00FF00)
        "Green" -> Color(0xFF008000)
        "Yellow" -> Color(0xFFFFFF00)
        "Blue" -> Color(0xFF0000FF)
        "Navy" -> Color(0xFF000080)
        "DodgerBlue" -> Color(0xFF1E90FF)
        "Orange" -> Color(0xFFFFA500)
        "Silver" -> if (p.modern) Modern.SILVER else Color(0xFFC0C0C0)
        "Gray" -> Color(0xFF808080)
        "DarkGray" -> Color(0xFFA9A9A9)
        "LightGray" -> Color(0xFFD3D3D3)
        // the DataCard's yellow cells, and the handful of others Falcas used
        "PaleGoldenrod" -> Color(0xFFEEE8AA)
        "Khaki" -> Color(0xFFF0E68C)
        "Magenta" -> Color(0xFFFF00FF)
        "LimeGreen" -> Color(0xFF32CD32)
        "LightBlue" -> Color(0xFFADD8E6)
        "LightSteelBlue" -> Color(0xFFB0C4DE)
        "Transparent" -> Color.Transparent
        "Control", "ControlLight" -> if (p.modern) Modern.PAGE_LIGHT else Color(0xFFF0F0F0)
        "Window" -> Color.White
        else -> null
    } else when (name) {
        "White", "Window" -> Hud.Text
        "Black" -> Hud.Bg
        "Red" -> Hud.Red
        "Lime", "Green" -> Hud.Green
        "Yellow" -> Hud.Amber
        "Blue", "Navy", "DodgerBlue" -> Hud.Cyan
        "Orange" -> Hud.Amber
        "Magenta" -> Color(0xFFFF4FD8)
        "Control", "ControlLight" -> Hud.Surface2
        "Transparent" -> Color.Transparent
        else -> null
    }
}

/**
 * A right click on a control WDP answers clicks on, for a mouse: WinForms raises a label's or a panel's Click for
 * either button, and WDP's rotary knobs read which one it was (forward on the left, back on the right). It sits
 * inside the control's tap in the modifier chain, so it sees the press first and takes it: a right click is never
 * also a left one. Like a tap, only the innermost control answers — a knob, not the panel it sits on — because a
 * press another control has taken is left alone. [onRight] is read at the click rather than when the page was
 * first drawn: the same control name sits on several pages, and a stale handler would step the page shown before.
 */
@Composable
private fun Modifier.secondaryClick(key: String, enabled: Boolean, onRight: () -> Unit): Modifier {
    val latest = androidx.compose.runtime.rememberUpdatedState(onRight)
    if (!enabled) return this
    return pointerInput(key) {
        awaitPointerEventScope {
            var down = false
            while (true) {
                val e = awaitPointerEvent()
                when (e.type) {
                    androidx.compose.ui.input.pointer.PointerEventType.Press ->
                        if (e.buttons.isSecondaryPressed && !e.buttons.isPrimaryPressed && e.changes.none { it.isConsumed }) {
                            down = true; e.changes.forEach { it.consume() }
                        }
                    androidx.compose.ui.input.pointer.PointerEventType.Release -> if (down) {
                        down = false
                        e.changes.forEach { it.consume() }
                        latest.value()
                    }
                }
            }
        }
    }
}

/**
 * Where a control was laid out, for [WdpProbe]: its whole rectangle in the scene, clipped or not, for as long as the
 * control is on the page (it is taken out when the control goes: a hidden panel, another tab, a closed window).
 */
@Composable
private fun Modifier.probed(key: String): Modifier {
    androidx.compose.runtime.DisposableEffect(key) { onDispose { WdpProbe.rects.remove(key) } }
    return onGloballyPositioned { WdpProbe.rects[key] = Rect(it.positionInRoot(), it.size.toSize()) }
}

/** A list's items as the page draws them: the wiring's `"<list>.items"` (new-line separated), else the designer's. */
private fun itemsOf(values: WdpValues, c: WdpControl): List<String> =
    values["${c.name}.items"]?.let { if (it.isEmpty()) emptyList() else it.split('\n') } ?: c.items

/**
 * What a control is to a finger ([WdpTouch]), kept in the form's [TouchTargets] while the Planner is worked by one.
 *
 * Every editor goes through the calls the page itself makes, so the wiring cannot tell a finger's edit from a mouse's
 * and a keyboard's: a box's OK is its text (when it changed) and then `"<name>.leave"`, as typing and leaving it are;
 * a number box's − and + are its arrows and its OK the typed value, as leaving it is; a list's pick is its own; a
 * slider's move is its drag; a grid's row is its click and Open its double click. A tap on a box still reports the
 * click WDP hangs on some boxes before the editor opens, as a press on the box does.
 *
 * [caption] is a label's own designer text (none where the program fills it in), which names the box beside it.
 */
@Composable
private fun touchOf(
    fingers: TouchTargets, c: WdpControl, values: WdpValues, shown: String, caption: String?, scale: Float,
    onValue: ((String, String) -> Unit)?, onClick: ((String) -> Unit)?, tappable: Boolean, z: String,
    /** the wiring draws something of its own in it (the loadout's stores, an attack map): a finger there is its own */
    drawn: Boolean = false,
    /** its tooltip, which a finger's long press shows where a long press means nothing else ([WdpTips]) */
    tip: String? = null,
): Modifier {
    val key = LocalWdpFormName.current + "/" + c.name
    // read when the editor is open, so it shows what the page shows as the page changes under it
    val vals = androidx.compose.runtime.rememberUpdatedState(values)
    val now = androidx.compose.runtime.rememberUpdatedState(shown)
    val cap = when (c.kind) {
        "label" -> caption?.trim()?.trimEnd(':')?.trim()?.takeIf { t -> t.any { it.isLetter() } }
        "group" -> c.text?.trim()?.takeIf { it.isNotEmpty() }
        else -> null
    }
    return Modifier
        .then(if (cap != null) Modifier.touchCaption(fingers, "$key#caption", cap, group = c.kind == "group") else Modifier)
        .touchTarget(fingers, key, c.name) {
            this.z = z
            container = c.kind == "panel" || c.kind == "group" || c.kind == "tab" || c.kind == "tabs"
            kind = TouchKind.BLOCK
            tap = null; sheet = null; right = null; drags = false; unit = 0f; keys = c.kind == "text" || c.kind == "number"
            this.tip = tip
            when (c.kind) {
                "button", "check", "radio" -> if (onClick != null) { kind = TouchKind.PRESS; tap = { onClick(c.name) } }
                "text" -> if (onValue != null && !c.readOnly) {
                    kind = TouchKind.EDIT
                    tap = onClick?.let { k -> { k(c.name) } }
                    sheet = { title ->
                        val v = now.value
                        WdpSheet.Text(title, key, v, numeric = looksNumeric(v), multiline = c.multiline) { t ->
                            if (t != now.value) onValue(c.name, t)
                            onValue("${c.name}.leave", "")
                        }
                    }
                }
                "number" -> if (onValue != null) {
                    kind = TouchKind.EDIT
                    tap = onClick?.let { k -> { k(c.name) } }
                    sheet = { title ->
                        WdpSheet.Number(
                            title, key,
                            value = { now.value },
                            step = { typed, by ->
                                upDownArrow(c.name, vals.value, now.value, typed?.takeIf { it != now.value.trim() }, by, onValue, onClick)
                            },
                            commit = { t -> if (t != now.value.trim()) onValue(c.name, t) },
                        )
                    }
                }
                "combo" -> {
                    val editable = onValue != null && values["${c.name}.editable"] == "true"
                    if (onValue != null && (itemsOf(values, c).isNotEmpty() || editable)) {
                        kind = TouchKind.EDIT
                        sheet = { title ->
                            WdpSheet.Pick(
                                title, key,
                                items = { itemsOf(vals.value, c) },
                                selected = { now.value },
                                pick = { onValue(c.name, it) },
                                typed = if (!editable) null else { t ->
                                    if (t != now.value) onValue(c.name, t)
                                    onValue("${c.name}.leave", "")
                                },
                            )
                        }
                    } else if (onClick != null) { kind = TouchKind.PRESS; tap = { onClick(c.name) } }
                }
                "slider" -> if (onValue != null) {
                    kind = TouchKind.EDIT
                    drags = true
                    sheet = { title ->
                        val min = { vals.value["${c.name}.min"]?.toIntOrNull() ?: 0 }
                        WdpSheet.Range(
                            title, key, min = min,
                            max = { vals.value["${c.name}.max"]?.toIntOrNull() ?: 10 },
                            value = { vals.value[c.name]?.toIntOrNull() ?: min() },
                            set = { onValue(c.name, it.toString()) },
                        )
                    }
                }
                "grid", "list" -> if (onClick != null) {
                    val grid = c.kind == "grid"
                    val rows = { v: WdpValues -> if (grid) rowsOf(v, c.name) else itemsOf(v, c).map { listOf(it) } }
                    if (rows(values).isNotEmpty()) {
                        kind = TouchKind.EDIT
                        drags = true
                        // small for a finger when its rows are: a grid's are 22 px, a list's about 15
                        unit = (if (grid) 22f else 15f) * scale
                        sheet = { title ->
                            WdpSheet.Rows(
                                title, key,
                                columns = { if (grid) vals.value["${c.name}.columns"]?.split('\t') ?: c.columns else emptyList() },
                                rows = { rows(vals.value) },
                                selected = { vals.value["${c.name}.selected"]?.toIntOrNull() ?: -1 },
                                select = { i -> onClick("${c.name}:row:$i") },
                                open = { i -> onClick("${c.name}:row:$i"); onClick("${c.name}:open:$i") },
                            )
                        }
                    }
                }
                "label", "panel", "picture" -> if (tappable && onClick != null) {
                    kind = TouchKind.TAP
                    right = { onClick("${c.name}:right") }
                }
            }
            // what the wiring draws takes its own taps, double taps and long presses: never a gap, never the page's
            if (drawn) { kind = TouchKind.BLOCK; container = false; right = null; this.tip = null }
        }
}

/**
 * A tap on a box reported as a click as well, without taking it from the box (which still gets the keyboard): WDP
 * hangs Click handlers on some of its text boxes — the DataCard's formation boxes open the formation window, its
 * weapon boxes open their lists, its taxi-time boxes their spinners — and on some up/down boxes.
 */
@Composable
private fun Modifier.reportsTap(name: String, onClick: ((String) -> Unit)?): Modifier {
    val latest = androidx.compose.runtime.rememberUpdatedState(onClick)
    if (onClick == null) return this
    return pointerInput(name) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = waitForUpOrCancellation(PointerEventPass.Initial)
            if (up != null) latest.value?.invoke(name)
        }
    }
}
