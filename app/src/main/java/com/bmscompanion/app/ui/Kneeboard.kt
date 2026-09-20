package com.bmscompanion.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import com.bmscompanion.app.ui.theme.HudSkin
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.delay

/**
 * Kneeboard mode: the browser version on a VR kneeboard, and nothing else — the Android app and the PC window never
 * switch to it.
 *
 * The app does not draw in the headset. **OpenKneeboard** shows this page as a *Web Dashboard* tab and owns the board
 * itself: where it sits, dragging, resizing, rotating, the toggle key and letting the mouse in and out. Open the
 * browser version there at `/kneeboard`.
 *
 * A board is a few hundred pixels across, so the page is built the way a watch face is: the content gets all of it.
 * No navigation rail, no bottom bar, no tab strip, no page header. A small [KneeboardButton] row floats on top — the
 * ☰ sections menu plus whatever the open page adds through [KneeboardActionsSlot] — and fades out a couple of seconds
 * after the mouse stops moving. Sections that are of no use in the cockpit are left out: the GCI picture, the EZBoards
 * tab (the briefing already has the same tables), the setup guides, Home and Media.
 */
object Kneeboard {
    var on by mutableStateOf(Repo.getInt("kneeboard", 0) == 1)
        private set

    /** Night flying: the same page under a dimmed light instead of daylight paper. */
    var night by mutableStateOf(Repo.getInt("kneeboard_night", 0) == 1)
        private set

    /**
     * How large the print is on the board, as the width the page is laid out at.
     *
     * A board is asked for in pixels — 1000 x 1600 and up, because it is drawn once and then looked at through a
     * headset, where a low-resolution board is a blurry one. But pixels are not size: laid out one-for-one, a 1000
     * pixel board is treated as a 1000 point wide monitor and everything on it comes out the size it would be on a
     * monitor, which through a lens a foot from your eye is unreadable. So the board is laid out at a few hundred
     * points wide however many pixels it has — like a phone held at arm's length — and drawn at the full resolution.
     *
     * The numbers are that width in points: smaller means fewer points across, so everything on the board is bigger.
     */
    val textSizes = listOf(
        BoardText("Small print", 620f),
        BoardText("Normal", 480f),
        BoardText("Large", 380f),
        BoardText("Very large", 310f),
    )

    var textSize by mutableStateOf(Repo.getInt("kneeboard_text", 1).coerceIn(0, textSizes.lastIndex))
        private set

    fun chooseTextSize(index: Int) {
        textSize = index.coerceIn(0, textSizes.lastIndex)
        Repo.putInt("kneeboard_text", textSize)
    }

    /** The density the board draws at, for a board this many pixels wide. */
    fun densityFor(widthPx: Float, fallback: Float): Float =
        if (widthPx <= 0f) fallback else (widthPx / textSizes[textSize].width).coerceIn(0.6f, 6f)

    /**
     * The shape of the board in VR.
     *
     * OpenKneeboard gives a Web Dashboard tab a landscape page, and its settings let you move and resize the tab but
     * not reproportion it — the shape has to come from the page. It does not have to be decided once and for all in
     * the address: [askShape] is wired up by the browser entry point (nothing else can ask), the choice is remembered
     * per board, and the ☰ menu offers the list. A board strapped to a thigh is portrait, so that is the first one.
     */
    val shapes = listOf(
        BoardShape("Kneeboard 5:8", 1000, 1600),
        BoardShape("Portrait 3:4", 1080, 1440),
        BoardShape("Tall 2:3", 1000, 1500),
        BoardShape("Square", 1280, 1280),
        BoardShape("Landscape 4:3", 1440, 1080),
    )

    /** Set by the browser entry point where the VR program offers it; null everywhere else, and the menu leaves it out. */
    var askShape: ((Int, Int) -> Unit)? = null

    /**
     * Tells the VR program which sections the board has, as pages it can flip between.
     *
     * In the headset there is no pointer: OpenKneeboard supports graphics tablets and nothing else ("Mice are not
     * supported in-game"), so a pilot with a stick and a keyboard cannot press anything on the board. What they can
     * do is bind a button to next/previous page, which they already have for their charts. Publishing each section
     * as a page puts the whole board on that one button.
     */
    var publishPages: ((slot: Int, want: Int, w: Int, h: Int) -> Unit)? = null

    /** The section the pilot has just flipped to, or -1. Polled: the event itself arrives in JavaScript. */
    var takePage: (() -> Int)? = null

    /** Why page turning is not working, if it is not: shown on the board, where there is no console to read. */
    var lastError: (() -> String)? = null

    /**
     * Names the page — which is the name OpenKneeboard writes on the tab when a board is added.
     *
     * Every board is served from the same app, so every tab was called "BMS Companion" and a stack of five of them
     * said nothing about which was which. Set by the browser entry point; nothing else can name a page.
     */
    var setTitle: ((String) -> Unit)? = null

    /**
     * Rounds the corners of the page itself.
     *
     * The board draws a tablet with round corners, but the page it is drawn on is a rectangle, and the corners came
     * out filled rather than absent — a browser canvas has no transparency to give. Clipping the canvas in the
     * browser does what the drawing cannot: outside the curve there is nothing at all, and the headset shows the
     * cockpit through it.
     */
    var roundCorners: ((Double) -> Unit)? = null

    var shape by mutableStateOf(Repo.getInt("kneeboard_shape", 0).coerceIn(0, shapes.lastIndex))
        private set

    fun chooseShape(index: Int) {
        shape = index.coerceIn(0, shapes.lastIndex)
        Repo.putInt("kneeboard_shape", shape)
        applyShape()
    }

    /** Asks for the remembered shape. The page asks once as it loads too, so the board is right before anything is drawn. */
    fun applyShape() = shapes[shape].let { askShape?.invoke(it.w, it.h) }

    /**
     * Which numbered board this page is, from `/kneeboard/<n>`, or null for the board without a number.
     *
     * A numbered board is one OpenKneeboard tab showing one thing: no menu, no buttons, nothing to press — because in
     * a headset there is nothing to press with.
     */
    var slot by mutableStateOf<Int?>(null)
        private set

    fun useSlot(n: Int?) { slot = n }

    /** Only the browser version turns this on, from the /kneeboard address (see web Main.kt). */
    fun set(value: Boolean) {
        on = value
        Repo.putInt("kneeboard", if (value) 1 else 0)
        apply()
    }

    /** The light a board is read under, told to it by the PC rather than chosen in the cockpit. */
    fun useNight(on: Boolean) {
        if (night == on) return
        night = on
        Repo.putInt("kneeboard_night", if (on) 1 else 0)
        apply()
    }

    /** Named toggleNight, not setNight: the property already owns that JVM signature. */
    fun toggleNight() {
        night = !night
        Repo.putInt("kneeboard_night", if (night) 1 else 0)
        apply()
    }

    private fun apply() {
        HudSkin.board = on
        HudSkin.night = night
    }

    init { apply() }
}

/** One shape a VR board can be asked to take, in pixels. */
data class BoardShape(val label: String, val w: Int, val h: Int)

/** One size of print on the board, as the width in points the page is laid out at. */
data class BoardText(val label: String, val width: Float)

/** What the open page contributes to the floating controls, for as long as it is shown. */
object KneeboardMenu {
    var menu by mutableStateOf<(@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)?>(null)
    var actions by mutableStateOf<(@Composable RowScope.() -> Unit)?>(null)
}

/**
 * Puts the page's own entries at the top of the ☰ menu (the Mission tabs, for example).
 *
 * [key] is whatever the entries depend on, so a menu that marks the open tab passes that tab.
 */
@Composable
fun KneeboardMenuSlot(key: Any?, content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit) {
    DisposableEffect(key) {
        KneeboardMenu.menu = content
        onDispose { KneeboardMenu.menu = null }
    }
}

/** Puts the page's own buttons next to ☰ — the map's ⋯ options, for example. See [KneeboardMenuSlot] for [key]. */
@Composable
fun KneeboardActionsSlot(key: Any?, content: @Composable RowScope.() -> Unit) {
    DisposableEffect(key) {
        KneeboardMenu.actions = content
        onDispose { KneeboardMenu.actions = null }
    }
}

/** One entry of the ☰ sections list. */
data class KneeboardSection(val route: String, val label: String, val icon: ImageVector, val go: () -> Unit)

/**
 * Wraps the whole page in kneeboard mode: the content fills the board and the controls float over it, fading away
 * when the mouse stops.
 *
 * The pointer is watched on the wrapper rather than on a sheet above the page, so the map underneath still pans and
 * zooms: a parent sees the [PointerEventPass.Initial] pass and hands the event on untouched.
 */
@Composable
fun KneeboardFrame(
    enabled: Boolean,
    chrome: Boolean,
    sections: List<KneeboardSection>,
    current: String,
    content: @Composable BoxScope.() -> Unit,
) {
    var woke by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(woke, open, chrome) {
        visible = true
        if (open) return@LaunchedEffect
        delay(2500)
        visible = false
    }
    val watch = if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val type = awaitPointerEvent(PointerEventPass.Initial).type
                if (type == PointerEventType.Move || type == PointerEventType.Press || type == PointerEventType.Enter || type == PointerEventType.Scroll) woke++
            }
        }
    }
    // The page itself is rounded off to match the tablet, so the corners are absent rather than black.
    val corner = with(LocalDensity.current) { (BEZEL * 2.4f).toPx() }
    LaunchedEffect(enabled, corner) { Kneeboard.roundCorners?.invoke(if (enabled) corner.toDouble() else 0.0) }
    Box(Modifier.fillMaxSize().then(if (enabled) Modifier else Modifier.background(Hud.Bg)).then(watch)) {
        if (!enabled) {
            content()
        } else {
            // the foot leaves room for the floating ☰ row, so a line of the page is never under a button
            Box(Modifier.fillMaxSize().padding(start = BEZEL, end = BEZEL, top = BEZEL, bottom = CHIN + (if (chrome) 28.dp else 0.dp))) {
                PaperSheet()
                content()
            }
            BoardFurniture()
        }
        if (!enabled || !chrome) return@Box
        // bottom left: the top left of a page is its title or its back arrow, and a map keeps its recenter
        // button bottom right
        AnimatedVisibility(visible, Modifier.align(Alignment.BottomStart), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                KneeboardButton(Icons.Default.Menu, "Sections") { open = true }
                KneeboardButton(
                    if (Kneeboard.night) Icons.Default.LightMode else Icons.Default.DarkMode,
                    if (Kneeboard.night) "Daylight" else "Night",
                ) { Kneeboard.toggleNight() }
                KneeboardMenu.actions?.invoke(this)
            }
        }
        if (open) KneeboardSheet(sections, current) { open = false }
    }
}

/**
 * The sheet the board is printed on.
 *
 * Paper is not a flat fill: it is stock with a grain, a few stains where it has been handled, and edges that have
 * darkened. All of it is drawn rather than loaded — a texture would be a download for every board and a bitmap to
 * keep in memory on a machine that is also running BMS — and it is fixed, not random, so the sheet does not shimmer
 * while the page redraws four times a second.
 *
 * The wash is the ink colour at very low alpha, so the same drawing ages the daylight sheet and the night one.
 */
@Composable
fun BoxScope.PaperSheet() {
    val ink = Hud.Text
    val warm = Hud.Outline
    Canvas(Modifier.matchParentSize()) {
        // the sheet paints its own stock: behind it is the bezel, not the page
        drawRect(Hud.Bg)
        // where the sheet has been handled: soft, broad stains rather than anything you would notice as a shape
        val stains = listOf(
            Triple(0.16f, 0.10f, 0.62f), Triple(0.86f, 0.26f, 0.70f),
            Triple(0.30f, 0.66f, 0.66f), Triple(0.74f, 0.90f, 0.54f),
        )
        for ((fx, fy, fr) in stains) {
            val c = Offset(size.width * fx, size.height * fy)
            val r = size.minDimension * fr
            drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.030f), Color.Transparent), center = c, radius = r), radius = r, center = c)
        }
        // the grain: laid lines, the way stock is made, far too faint to read as lines
        val step = 7.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(warm.copy(alpha = 0.055f), Offset(0f, y), Offset(size.width, y), 1f)
            y += step
        }
        // and the edges, which are always darker than the middle
        drawRect(
            Brush.radialGradient(
                listOf(Color.Transparent, ink.copy(alpha = 0.085f)),
                center = Offset(size.width / 2, size.height / 2),
                radius = size.maxDimension * 0.62f,
            ),
        )
    }
}

/** The bezel: thin around the sides and head, deeper at the chin where the home button sits. */
private val BEZEL = 9.dp
private val CHIN = 30.dp

/**
 * The board, as the object it is in the headset: a tablet.
 *
 * Drawn rather than pictured, and drawn as a solid: the bezel is lit along its top edge and shadowed along the
 * bottom, the screen is recessed into it behind a dark inner edge, and the chin carries a home button. The corners
 * are round and nothing is painted outside them, so the board ends where the tablet ends instead of sitting in an
 * invisible square — a headset composites whatever is behind it into those corners.
 *
 * One dark tone whatever the page, so the page is the only thing on the board with a colour.
 */
@Composable
private fun BoxScope.BoardFurniture() {
    val body = Color(0xFF1B1E23)
    val shade = Color(0xFF0B0D10)
    val glass = Color.White
    Canvas(Modifier.matchParentSize()) {
        val b = BEZEL.toPx()
        val chin = CHIN.toPx()
        val outer = CornerRadius(b * 2.4f, b * 2.4f)
        val screen = RoundRect(b, b, size.width - b, size.height - chin, CornerRadius(b * 1.1f, b * 1.1f))

        // the tablet: everything inside the rounded outline, with the screen cut out of it
        val frame = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, outer))
            addRoundRect(screen)
            fillType = PathFillType.EvenOdd
        }
        // aluminium: lighter where the light falls, darker at the chin
        drawPath(
            frame,
            Brush.linearGradient(
                listOf(body.copy(alpha = 1f), shade),
                start = Offset(size.width * 0.2f, 0f),
                end = Offset(size.width * 0.8f, size.height),
            ),
        )
        // the lit top edge and the shadow under the bottom one: what makes it read as a solid rather than a rectangle
        val edge = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, outer))
            addRoundRect(RoundRect(1.5f, 1.5f, size.width - 1.5f, size.height - 1.5f, outer))
            fillType = PathFillType.EvenOdd
        }
        drawPath(edge, Brush.verticalGradient(listOf(glass.copy(alpha = 0.16f), Color.Transparent, Color.Black.copy(alpha = 0.35f))))

        // the screen sits in a well: a dark inner edge, and a hairline of light on its lower lip
        drawRoundRect(
            Color.Black.copy(alpha = 0.55f),
            Offset(screen.left - 1.5f, screen.top - 1.5f),
            Size(screen.width + 3f, screen.height + 3f),
            CornerRadius(b * 1.2f, b * 1.2f),
            style = Stroke(3f),
        )
        drawRoundRect(
            glass.copy(alpha = 0.10f),
            Offset(screen.left, screen.top),
            Size(screen.width, screen.height),
            CornerRadius(b * 1.1f, b * 1.1f),
            style = Stroke(1f),
        )

        // the camera above the screen, and the home button in the chin
        drawCircle(glass.copy(alpha = 0.18f), b * 0.16f, Offset(size.width / 2, b * 0.5f))
        val home = Offset(size.width / 2, size.height - chin / 2)
        val r = chin * 0.30f
        drawCircle(Color.Black.copy(alpha = 0.45f), r + 1.5f, home)
        drawCircle(glass.copy(alpha = 0.22f), r, home, style = Stroke(1.6f))
        drawRoundRect(
            glass.copy(alpha = 0.16f),
            Offset(home.x - r * 0.42f, home.y - r * 0.42f),
            Size(r * 0.84f, r * 0.84f),
            CornerRadius(r * 0.22f, r * 0.22f),
            style = Stroke(1.2f),
        )
    }
}

/** A small dark control that stays readable over a map. */
@Composable
fun KneeboardButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Hud.Bg.copy(alpha = 0.78f))
            .border(1.dp, Hud.Outline.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Hud.Text, modifier = Modifier.size(16.dp)) }
}

/** The ☰ menu: the page's own entries (the Mission tabs) above the sections. */
@Composable
private fun BoxScope.KneeboardSheet(sections: List<KneeboardSection>, current: String, dismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Hud.Bg.copy(alpha = 0.6f)).clickable(onClick = dismiss))
    Column(
        Modifier.align(Alignment.BottomStart).padding(5.dp).widthIn(min = 148.dp, max = 240.dp)
            .clip(RoundedCornerShape(10.dp)).background(Hud.Surface).border(1.dp, Hud.Outline, RoundedCornerShape(10.dp)).padding(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("BMS COMPANION", Modifier.weight(1f), fontSize = 9.sp, color = Hud.TextFaint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            KneeboardButton(Icons.Default.Close, "Close the menu", dismiss)
        }
        KneeboardMenu.menu?.invoke(this, dismiss)
        sections.forEach { s -> KneeboardMenuRow(s.label, current == s.route, s.icon) { s.go(); dismiss() } }
        KneeboardMenuDivider()
        Text(
            "PRINT SIZE",
            Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
            fontSize = 9.sp, color = Hud.TextFaint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        )
        Kneeboard.textSizes.forEachIndexed { i, t -> KneeboardMenuRow(t.label, i == Kneeboard.textSize) { Kneeboard.chooseTextSize(i) } }
        if (Kneeboard.askShape != null) {
            KneeboardMenuDivider()
            Text(
                "BOARD SHAPE",
                Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                fontSize = 9.sp, color = Hud.TextFaint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            )
            Kneeboard.shapes.forEachIndexed { i, s -> KneeboardMenuRow(s.label, i == Kneeboard.shape) { Kneeboard.chooseShape(i) } }
        }
    }
}

/** A line of the ☰ menu. */
@Composable
fun KneeboardMenuRow(label: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
            .background(if (selected) Hud.Amber.copy(alpha = 0.16f) else Hud.Surface)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) Hud.Amber else Hud.TextDim, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = if (selected) Hud.Amber else Hud.Text, fontSize = 13.sp)
    }
}

/** The rule between the page's own menu entries and the sections list. */
@Composable
fun KneeboardMenuDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).height(1.dp).background(Hud.Outline.copy(alpha = 0.7f)))
}
