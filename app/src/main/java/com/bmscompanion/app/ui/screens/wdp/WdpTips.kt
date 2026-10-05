package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * **The Planner's tooltips**: a sentence or two on what a control is and what pressing or typing in it does, shown
 * when the mouse rests on it for half a second (Windows' own delay), as WDP's DTC page did with its 435 — and here on
 * every page and window, the Planner's toolbar and its own windows too. By finger a **long press** shows it, except
 * where a long press already means something (a knob's is WDP's right button, a big box's selects text); a small box's
 * editor ([WdpSheetHost]) carries its control's tip under the title. **Settings → Show tooltips** turns them all off
 * ([PlannerSettings.tooltips]).
 *
 * **Where the words are.** Data, not code: `assets/data/wdp/tips/<form>.json`, `{ "<control>": "text" }`, one file per
 * form, the control named as in the form's layout (`<form>.json`); `tips/planner.json` holds the shell's and the
 * Planner windows' own, by the name the control is probed by ([plannerProbe]: `"Shell/Populate"`, `"Settings/Tooltips"`).
 * Falcas's own tips seeded them (`tools/extractor/src/wdptips.mjs`, which never overwrites a tip already written). A
 * tip a wiring works out as it goes (a weapon's id, a file's path) is its value `"<name>.tip"` ([WdpWiring]), which
 * wins over the file's, and `""` there takes the file's away. A control with no tip shows none.
 *
 * **Which control.** Every control with a tip hears the mouse on its way back up (the Main pass, the innermost first)
 * and the first to hear a movement takes it ([claim]), so a box's tip is the box's and not the panel's it sits on; a
 * control a pilot works (a button, a box, a list) inside a panel with a tip takes the mouse even without one, so it
 * never shows the panel's. The tip is drawn by [WdpTipHost], over the Planner and its windows, at the mouse.
 */
object WdpTips {
    /** The tips of the Planner's own shell and windows, by probe name. */
    const val PLANNER = "planner"
    /** Windows' `InitialDelay`: the mouse rests this long before a tip shows. */
    const val DELAY_MS = 500L
    /** Windows' `ReshowDelay`: from one control to the next while a tip is up. */
    const val RESHOW_MS = 100L
    /** How long a finger's tip stays up. */
    const val TOUCH_MS = 6000L

    /** Each form's tips once its file has been read, by control name. */
    private val forms = mutableStateMapOf<String, Map<String, String>>()
    private val asked = HashSet<String>()

    /** Tips are shown (Settings → Show tooltips). */
    val on: Boolean get() = PlannerSettings.tooltips

    /** [form]'s tips, read the first time a form asks (empty until then, and while tips are off). */
    @Composable
    internal fun of(form: String): Map<String, String> {
        if (!on) return emptyMap()
        val m = forms[form]
        if (m == null) LaunchedEffect(form) { load(form) }
        return m ?: emptyMap()
    }

    /** Reads [form]'s tips file once; a missing or broken file is no tips. */
    suspend fun load(form: String) {
        if (!asked.add(form)) return
        forms[form] = parse(runCatching { Repo.text("data/wdp/tips/$form.json") }.getOrNull())
    }

    /** `{ "name": "text" }` → the map, blank texts left out; anything else is empty. */
    fun parse(text: String?): Map<String, String> = runCatching {
        Json.parseToJsonElement(text ?: return emptyMap()).jsonObject
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let { k to it } }
            .toMap()
    }.getOrDefault(emptyMap())

    /** The file's tip for [name] of [form], once that file has been read; null for none. */
    fun text(form: String, name: String): String? = forms[form]?.get(name)

    /** The tip for a control named `"<form>/<name>"` (a finger's target, an editor's key). */
    fun forKey(key: String): String? = if ('/' !in key) null else text(key.substringBefore('/'), key.substringAfter('/'))

    /** The Planner's own control [probe] has a tip, and tips are on. */
    fun hasPlanner(probe: String): Boolean = on && !text(PLANNER, probe).isNullOrBlank()

    // ---------------------------------------------------------------- what shows

    /** The control the mouse rests on and its tip; [quick] when a tip was up a moment ago (Windows' reshow). */
    internal class Rest(val key: String, val text: String, val quick: Boolean)

    /** A tip on screen: its text, where the pointer was (root pixels), and whether a finger asked for it. */
    internal class Shown(val text: String, val at: Offset, val touch: Boolean)

    internal var resting by mutableStateOf<Rest?>(null)
    internal var shown by mutableStateOf<Shown?>(null)

    /** The pointer as the Planner's box last saw it, in root pixels ([wdpTipArea]), and that box's own top left. */
    internal var pointer = Offset.Zero
    internal var origin = Offset.Zero

    private var lastClaim = Long.MIN_VALUE
    /** the control last pressed with the mouse: no tip for it until the mouse leaves it, as in Windows */
    private var pressedKey: String? = null

    /**
     * Whether the control hearing [ch] is the first to hear it. Every control on the pointer's way hears the same
     * movement, the innermost first; only that one's tip counts.
     */
    internal fun claim(ch: PointerInputChange): Boolean {
        val k = ch.uptimeMillis * 131 + ch.id.value
        if (k == lastClaim) return false
        lastClaim = k
        return true
    }

    /** The mouse is on [key], whose tip is [text] (none for a control that only keeps its parent's tip off itself). */
    internal fun hover(key: String, text: String?) {
        if (key == pressedKey) return
        val r = resting
        if (r != null && r.key == key && r.text == text) return
        if (r == null && text.isNullOrBlank()) return
        val up = shown
        resting = if (text.isNullOrBlank()) null else Rest(key, text, quick = up != null && !up.touch)
        if (up?.touch != true) shown = null
    }

    /** The mouse left [key]. */
    internal fun leave(key: String) {
        if (pressedKey == key) pressedKey = null
        if (resting?.key == key) {
            resting = null
            if (shown?.touch != true) shown = null
        }
    }

    /** A press on [key]: the tip goes, and stays away from that control until the mouse leaves it. */
    internal fun press(key: String?) {
        pressedKey = key
        resting = null
        shown = null
    }

    /** A finger's long press: [text] at [at] (root pixels), until the next touch or a few seconds. */
    fun pop(text: String, at: Offset) {
        if (!on || text.isBlank()) return
        resting = null
        shown = Shown(text, at, touch = true)
    }

    /** Takes any tip down (a window opened, tips turned off). */
    fun hide() {
        resting = null
        shown = null
    }
}

/** The tips of the form being drawn ([WdpFormView] provides them; paper and a form outside the Planner have none). */
internal val LocalWdpTips = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * The tip of a control or of one of the Planner's own buttons: [text] (null for none) under the mouse, [key] naming
 * the control ("<form>/<name>", "planner/<probe>"). With [touch] a finger's long press shows it as well; a form's
 * controls leave fingers to the form ([touchResolver]), which shows their tips itself. Nothing while tips are off.
 */
@Composable
internal fun Modifier.wdpTip(key: String, text: String?, touch: Boolean = false): Modifier {
    if (!WdpTips.on) return this
    val latest = rememberUpdatedState(text)
    DisposableEffect(key) { onDispose { WdpTips.leave(key) } }
    return pointerInput(key, touch) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Main)
                val ch = e.changes.firstOrNull() ?: continue
                val finger = ch.type == PointerType.Touch
                when (e.type) {
                    PointerEventType.Enter, PointerEventType.Move ->
                        if (!finger && !ch.pressed && WdpTips.claim(ch)) WdpTips.hover(key, latest.value)
                    PointerEventType.Exit -> if (!finger) WdpTips.leave(key)
                    PointerEventType.Press -> when {
                        !finger -> if (WdpTips.claim(ch)) WdpTips.press(key)
                        touch && !latest.value.isNullOrBlank() && WdpTips.claim(ch) -> {
                            // a finger held still on it: the tip, and the lift taken so the press does nothing else
                            val down = ch
                            var moved = false
                            var lifted = false
                            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val m = awaitPointerEvent(PointerEventPass.Main)
                                    if (m.changes.count { it.pressed } > 1) { moved = true; break }
                                    val c = m.changes.firstOrNull { it.id == down.id } ?: continue
                                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) { moved = true; break }
                                    if (!c.pressed) { lifted = true; break }
                                }
                            }
                            if (held == null && !moved && !lifted) {
                                latest.value?.let { WdpTips.pop(it, WdpTips.pointer) }
                                while (true) {
                                    val m = awaitPointerEvent(PointerEventPass.Main)
                                    m.changes.forEach { it.consume() }
                                    if (m.changes.none { it.pressed }) break
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

/**
 * The box the Planner's tips are drawn in ([WdpTipHost]): it follows the pointer on its way down to the controls (it
 * only looks: nothing is taken), so a tip can be put where the mouse is, and a finger's first touch takes a finger's
 * tip down.
 */
internal fun Modifier.wdpTipArea(): Modifier =
    onGloballyPositioned { WdpTips.origin = it.positionInRoot() }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = e.changes.firstOrNull() ?: continue
                    WdpTips.pointer = WdpTips.origin + ch.position
                    if (e.type == PointerEventType.Press && WdpTips.shown?.touch == true) WdpTips.hide()
                }
            }
        }

/**
 * Where the Planner's tips are drawn: over its pages, toolbar and windows (the Planner draws it last), in the box
 * [wdpTipArea] follows the pointer in. A mouse's tip shows under the pointer — above it near the bottom — after
 * [WdpTips.DELAY_MS] at rest; a finger's where the finger is, for [WdpTips.TOUCH_MS]. It takes no input: a press goes
 * through it to what is under it.
 */
@Composable
internal fun WdpTipHost() {
    val r = WdpTips.resting
    LaunchedEffect(r) {
        if (r == null) return@LaunchedEffect
        delay(if (r.quick) WdpTips.RESHOW_MS else WdpTips.DELAY_MS)
        if (WdpTips.resting === r && WdpTips.on) WdpTips.shown = WdpTips.Shown(r.text, WdpTips.pointer, touch = false)
    }
    val s = WdpTips.shown ?: return
    LaunchedEffect(s) {
        if (!s.touch) return@LaunchedEffect
        delay(WdpTips.TOUCH_MS)
        if (WdpTips.shown === s) WdpTips.shown = null
    }
    val finger = s.touch || WdpTouch.device
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .layout { measurable, c ->
                    val margin = 6.dp.roundToPx()
                    val maxW = minOf((if (finger) 360 else 340).dp.roundToPx(), (c.maxWidth - 2 * margin).coerceAtLeast(40))
                    val p = measurable.measure(Constraints(maxWidth = maxW, maxHeight = (c.maxHeight - 2 * margin).coerceAtLeast(20)))
                    val at = s.at - WdpTips.origin
                    // under the pointer, as Windows puts it (a finger's above the finger, which would hide it)
                    val below = (if (s.touch) 0 else 22).dp.roundToPx()
                    val above = (if (s.touch) 40 else 8).dp.roundToPx()
                    var x = (if (s.touch) at.x - p.width / 2f else at.x).toInt()
                    var y = if (s.touch) at.y.toInt() - above - p.height else at.y.toInt() + below
                    if (!s.touch && y + p.height > c.maxHeight - margin) y = at.y.toInt() - above - p.height
                    if (s.touch && y < margin) y = at.y.toInt() + 28.dp.roundToPx()
                    x = x.coerceIn(margin, (c.maxWidth - margin - p.width).coerceAtLeast(margin))
                    y = y.coerceIn(margin, (c.maxHeight - margin - p.height).coerceAtLeast(margin))
                    layout(c.maxWidth, c.maxHeight) { p.place(x, y) }
                }
                .clip(shape).background(TIP_BG).border(1.dp, Hud.Outline, shape)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                s.text,
                style = TextStyle(color = Hud.Text, fontSize = if (finger) 14.sp else 12.5.sp, lineHeight = if (finger) 19.sp else 17.sp),
            )
        }
    }
}

/** The tip's ground: darker than any of the Planner's surfaces, so it reads over a grey WDP page and a dark one. */
private val TIP_BG = Color(0xF2161A20)
