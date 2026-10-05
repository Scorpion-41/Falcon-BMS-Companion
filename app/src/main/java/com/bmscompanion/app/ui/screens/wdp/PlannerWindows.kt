package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.bmscompanion.app.ui.theme.Hud

/**
 * The Planner's own windows — not Weapon Delivery Planner's forms ([WdpDialogs] draws those), but the ones this app
 * adds around them, in the app's own look: opening a campaign file, picking a flight in it, printing to the cockpit's
 * kneeboard, and the guide. Unlike WDP's pages, these may scroll: a list
 * of five hundred files cannot fit a window (R3-PLAN A21).
 *
 * Each window's content lives in its own file (one batch each); this file only says which is open and draws the frame.
 */
enum class PlannerWindow(val title: String, val probe: String) {
    OPEN_MISSION("Open mission", "OpenMission"),
    FLIGHT_PICKER("Pick a flight", "FlightPicker"),
    PRINT("Upd Kneeboard", "Print"),
    GUIDE("Planner guide", "Guide"),
    /** WDP's Settings window (Options → Settings), with what means something here ([PlannerSettings]) */
    SETTINGS("Planner settings", "Settings"),
    // WDP's ATO Target List is a page of the Planner's own now (WdpPage.ATO, AtoTargetsPage), not a window
}

/**
 * Which of the Planner's windows is open. One at a time: opening another replaces it (Open mission hands over to the
 * flight picker, the guide's "go there" buttons to the window they name). Held here rather than by a page so a
 * toolbar button, a note and another window can all open one.
 */
object PlannerWindows {
    /** The window on screen, or null. */
    var open: PlannerWindow? by mutableStateOf(null)
        private set

    /**
     * What it was opened for, when anything: the guide's section ("toss", "dtc", "print"…), the flight picker's file.
     * Each window reads its own.
     */
    var arg: String? by mutableStateOf(null)
        private set

    fun show(w: PlannerWindow, arg: String? = null) {
        this.arg = arg
        open = w
    }

    fun close() {
        open = null
        arg = null
    }

    val isOpen: Boolean get() = open != null
}

/**
 * Where the Planner's windows are drawn: over the Planner, which takes no input while one is up. The Planner
 * (`WdpPlanner`) draws it over its page and toolbar and **before** [WdpDialogHost]: a WDP message box opened from one
 * of these windows — a save's question, a refusal, a notice — is drawn by that host, so it comes up over the window
 * that asked rather than hidden under it. While the Planner cannot work (the not-linked note, `WdpPane`), the note
 * draws this host over itself, so the guide can still be read (R3-PLAN A18, A19).
 */
@Composable
fun PlannerWindowHost(
    modifier: Modifier = Modifier,
    /**
     * The window to draw. The Planner reads it outside the box its page is laid out in and hands it in: read only in
     * here, a window opened in the same frame as a page change (the DataCard composing ~600 controls) was sometimes
     * never drawn, the host's own invalidation lost in the box's subcomposition.
     */
    open: PlannerWindow? = PlannerWindows.open,
) {
    val w = open ?: return
    BoxWithConstraints(
        modifier.fillMaxSize()
            .background(Color(0x66000000))
            // the Planner underneath takes nothing while a window is up
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        // A phone gets the whole screen, as a sheet. By finger on a tablet the window takes the whole Planner less a
        // thin margin (and the Mission header steps aside for it, WdpFocus.lifted): its list is the point of it, and a
        // window of the PC's proportions inside a tablet's short landscape screen left the list a few lines, or none.
        // With a mouse, a window of a sensible size that grows with a big screen as the Planner's page does: at least
        // 960 x 760 where there is room, and about two thirds of a bigger screen.
        val finger = WdpTouch.device
        val wide = maxWidth >= 600.dp
        val sheet = !wide && finger
        val width = when {
            sheet -> maxWidth
            finger || !wide -> maxWidth - 16.dp
            else -> minOf(maxWidth * 0.9f, maxOf(960.dp, maxWidth * 0.62f))
        }
        val height = when {
            sheet -> maxHeight
            finger || !wide -> maxHeight - 12.dp
            else -> minOf(maxHeight * 0.92f, maxOf(760.dp, maxHeight * 0.85f))
        }
        // by finger every window but Settings (a few rows) is the full height, so its list fills it
        val full = sheet || (finger && w != PlannerWindow.SETTINGS)
        androidx.compose.runtime.key(w) {
            PlannerWindowFrame(
                w.title, w.probe,
                Modifier.width(width).then(if (full) Modifier.height(height) else Modifier.heightIn(max = height)),
                onClose = PlannerWindows::close, sheet = sheet,
                // the save's flight being planned, and the way back to the printed briefing, in the title bar of the two
                // windows that pick one (a line of its own took a card's height above the list)
                actions = if (w == PlannerWindow.OPEN_MISSION || w == PlannerWindow.FLIGHT_PICKER) ({ PlanningInTitle(w.probe) }) else null,
            ) {
                when (w) {
                    PlannerWindow.OPEN_MISSION -> CampaignBrowserWindow(onClose = PlannerWindows::close)
                    PlannerWindow.FLIGHT_PICKER -> FlightPickerWindow(onClose = PlannerWindows::close)
                    PlannerWindow.PRINT -> KneeboardPrintWindow(onClose = PlannerWindows::close)
                    PlannerWindow.GUIDE -> PlannerGuideWindow(section = PlannerWindows.arg, onClose = PlannerWindows::close)
                    PlannerWindow.SETTINGS -> PlannerSettingsWindow(onClose = PlannerWindows::close)
                }
            }
        }
    }
}

/**
 * The frame every Planner window has: a title bar with the title and a ×, and the content under it. It takes the
 * keyboard when it opens, and Escape closes it. [probe] names it for the headless checks ([plannerProbe]).
 */
@Composable
fun PlannerWindowFrame(
    title: String,
    probe: String,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    /** a phone's whole screen: square corners, no shadow */
    sheet: Boolean = false,
    /** what the window adds to its title bar, after the title and before the × (kept to one slim line) */
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(if (sheet) 0.dp else 10.dp)
    // one slim line: the content is what the window is for. By finger (WdpTouch) the × keeps a finger's width.
    val finger = WdpTouch.device
    val bar = if (finger) 40.dp else 34.dp
    Column(
        modifier
            // a soft shadow as a few faint rings, not a blur (a blur costs a frame's time on the PC's software renderer)
            .drawBehind {
                if (sheet) return@drawBehind
                val step = 2.dp.toPx()
                val alphas = floatArrayOf(0.3f, 0.16f, 0.08f, 0.04f)
                for (i in alphas.indices) {
                    val s = step * (i + 0.5f)
                    drawRoundRect(
                        Color.Black.copy(alpha = alphas[i]), topLeft = Offset(-s, -s + step),
                        size = Size(size.width + 2 * s, size.height + 2 * s),
                        cornerRadius = CornerRadius(12.dp.toPx() + s), style = Stroke(width = step),
                    )
                }
            }
            .clip(shape).background(Hud.Surface).border(1.dp, Hud.Outline, shape)
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onClose(); true } else false }
            .focusRequester(focus).focusable()
            .plannerProbe(probe),
    ) {
        Row(
            Modifier.fillMaxWidth().height(bar).background(Hud.Surface2)
                .drawBehind { drawLine(Hud.Outline, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title, color = Hud.Text, fontSize = if (finger) 15.sp else 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (actions == null) Modifier.weight(1f) else Modifier,
            )
            if (actions != null) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { actions() }
            // the ×: red under the mouse, as a window's is
            val src = remember { MutableInteractionSource() }
            val hovered by src.collectIsHoveredAsState()
            val pressed by src.collectIsPressedAsState()
            Box(
                Modifier.size(width = if (finger) 52.dp else 46.dp, height = bar)
                    .background(if (pressed) Color(0xFFA32417) else if (hovered) Color(0xFFC42B1C) else Color.Transparent)
                    .hoverable(src).clickable(src, indication = null) { onClose() }.plannerProbe("$probe/Close"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Close, "Close", tint = if (hovered || pressed) Color.White else Hud.TextDim, modifier = Modifier.size(if (finger) 20.dp else 18.dp)) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/**
 * The title bar's line while a save's flight is planned: "Planning Cajun1, Lead: Save-Day 1 03 06 49.cam" in small amber
 * type and **Back to BMS briefing** beside it (`<probe>/BackToBriefing`). Nothing while the printed briefing is planned.
 */
@Composable
internal fun RowScope.PlanningInTitle(probe: String) {
    if (!PlannerMissionState.fromSave) return
    val label = PlannerMissionState.label ?: return
    Text(
        "Planning $label", color = Hud.Amber, fontSize = fingerSp(11.5f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
    )
    PlannerButton("Back to BMS briefing", probe = "$probe/BackToBriefing", small = true, slim = true) { PlannerMissionState.backToBriefing() }
}

/**
 * A thin hairline between the parts of a window (no cards inside windows: rows sit on the window's own ground, ruled
 * off from each other).
 */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Hud.Outline.copy(alpha = 0.55f)))
}

/** The height of a control in the windows' one slim tool row: a finger's 40 dp, or 30 dp with a mouse. */
internal val toolHeight: androidx.compose.ui.unit.Dp @Composable get() = if (WdpTouch.device) 40.dp else 30.dp

/**
 * The windows' search box: one slim line (a field the tool row's height, not Material's 56 dp one) with a magnifier,
 * the hint while empty and a × to clear it. [probe] names the field itself, so a press in its middle types into it.
 */
@Composable
internal fun PlannerSearch(value: String, onChange: (String) -> Unit, hint: String, probe: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.height(toolHeight).clip(shape).background(Hud.Surface2).border(1.dp, Hud.Outline, shape).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = Hud.TextDim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f).plannerProbe(probe), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, color = Hud.TextFaint, fontSize = fingerSp(12.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.foundation.text.BasicTextField(
                value, onChange, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Hud.Text, fontSize = fingerSp(13f)),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Hud.Amber),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) Box(
            Modifier.size(toolHeight).clickable { onChange("") }.plannerProbe("$probe/Clear"),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Close, "Clear", tint = Hud.TextDim, modifier = Modifier.size(16.dp)) }
        else Spacer(Modifier.width(8.dp))
    }
}

/** A square button holding an icon, the tool row's height; its name is its tooltip ([plannerProbe]) and its description. */
@Composable
internal fun PlannerIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, probe: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(toolHeight).clip(RoundedCornerShape(6.dp)).background(Hud.Surface3).plannerPress(enabled) { onClick() }
            .alpha(if (enabled) 1f else 0.4f).plannerProbe(probe),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Hud.Text, modifier = Modifier.size(if (WdpTouch.device) 20.dp else 17.dp)) }
}

/**
 * One choice of several as a joined strip (the list's order: Newest | Modified | Created | Name), the tool row's
 * height. Each part keeps its own probe ([probes]), so the checks and the tooltips find it as they found the chips.
 */
@Composable
internal fun PlannerSegments(labels: List<String>, probes: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.height(toolHeight).clip(shape).border(1.dp, Hud.Outline, shape), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, l ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline))
            val on = i == selected
            Box(
                Modifier.fillMaxHeight().background(if (on) Hud.Amber.copy(alpha = 0.18f) else Hud.Surface)
                    .plannerPress { onPick(i) }.padding(horizontal = if (WdpTouch.device) 11.dp else 9.dp).plannerProbe(probes[i]),
                contentAlignment = Alignment.Center,
            ) {
                Text(l, color = if (on) Hud.Amber else Hud.TextDim, fontSize = fingerSp(12f), fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/**
 * How a button in the Planner's windows answers the mouse, in place of its `clickable`: a faint light over its fill
 * while the mouse is on it and a faint dark while it is pressed, drawn over the fill and under the caption, so every
 * button in these windows answers the same way ([PlannerButton], Send's, Print's, the guide's).
 */
@Composable
internal fun Modifier.plannerPress(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    return drawBehind {
        if (enabled && (hovered || pressed)) drawRect(if (pressed) Color.Black.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f))
    }.hoverable(src, enabled).clickable(src, indication = null, enabled = enabled) { onClick() }
}

/**
 * A row or a button of the Planner's windows at a finger's height where the Planner is worked by one
 * ([WdpTouch.device]): at least [min] tall, its content where it was. With a mouse it is as it always was.
 */
@Composable
internal fun Modifier.fingerHeight(min: androidx.compose.ui.unit.Dp = 44.dp): Modifier =
    if (WdpTouch.device) heightIn(min = min) else this

/** A size of type in the Planner's windows, a step bigger where the Planner is worked by finger ([WdpTouch.device]). */
internal fun fingerSp(sp: Float): androidx.compose.ui.unit.TextUnit = if (WdpTouch.device) (sp + 1f).sp else sp.sp

/**
 * What a window that is not built yet shows: its purpose, a line saying it is on its way, and a Close button. Each
 * window's own batch replaces its stub with the real thing.
 */
@Composable
internal fun PlannerWindowStub(probe: String, purpose: String, onClose: () -> Unit) {
    Text(purpose, color = Hud.Text, fontSize = 14.sp)
    Text("This window is not built yet.", color = Hud.TextDim, fontSize = 13.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Hud.Surface3).clickable { onClose() }
                .padding(horizontal = 16.dp, vertical = 8.dp).plannerProbe("$probe/Done"),
        ) { Text("Close", color = Hud.Text, fontSize = 14.sp) }
    }
}

/**
 * Names a control of the Planner's shell or of one of its windows for the headless checks and the browser audit:
 * while [WdpProbe.on], its rectangle in the scene is kept in [WdpProbe.rects] under `"planner/<name>"`, beside WDP's
 * own controls (`"<form>/<control>"`), and taken out when the control goes. Off in the app, where it costs nothing.
 * Name buttons `"<Window or Shell>/<Button>"`, e.g. `"Shell/Populate"`, `"OpenMission/Refresh"`.
 *
 * The name is also the control's **tooltip** key: `assets/data/wdp/tips/planner.json` (`{ "Shell/Populate": "…" }`)
 * gives it a tip under the mouse and on a finger's long press ([WdpTips]); a name with no tip there has none.
 */
@Composable
fun Modifier.plannerProbe(name: String): Modifier {
    val tip = WdpTips.of(WdpTips.PLANNER)[name]
    val tipped = if (tip != null) wdpTip("planner/$name", tip, touch = true) else this
    if (!WdpProbe.on) return tipped
    val key = "planner/$name"
    DisposableEffect(key) { onDispose { WdpProbe.rects.remove(key) } }
    return tipped.onGloballyPositioned { WdpProbe.rects[key] = Rect(it.positionInRoot(), it.size.toSize()) }
}
