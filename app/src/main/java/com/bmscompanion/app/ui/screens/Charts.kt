package com.bmscompanion.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.ChartRef
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.Kneeboard
import com.bmscompanion.app.ui.components.AssetImage
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.LoadingBox
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.abs
import kotlin.math.min

/**
 * [pages] > 1 for an instrument chart: its pages are the same file with the page number swapped in
 * ("charts/<id>-1.webp" … "charts/<id>-<pages>.webp"), so the route stays short.
 */
fun chartRoute(file: String, title: String, pages: Int = 0, start: Int = 1, setId: String = "", airportId: Int = 0): String {
    val route = StringBuilder("chart?file=${android.net.Uri.encode(file)}&title=${android.net.Uri.encode(title)}")
    if (pages > 1) route.append("&pages=$pages")
    if (start > 1) route.append("&start=$start")
    // the airfield it belongs to, so the viewer can walk on into the next chart instead of ending at the last page
    if (setId.isNotEmpty()) route.append("&set=${android.net.Uri.encode(setId)}&id=$airportId")
    return route.toString()
}

/** The file of page [page] (1-based) of a chart whose first page is [first]. */
fun chartPage(first: String, page: Int): String =
    if (isKneeboardPage(first)) "$KNEEBOARD_SCHEME:$page"
    else first.replace(Regex("-1\\.webp$"), "-$page.webp")

@Composable
fun AirportChartsCard(nav: NavHostController, setId: String, airportId: Int, airportName: String) {
    val charts by produceState<List<ChartRef>?>(null, setId, airportId) { value = Repo.charts(setId)[airportId.toString()].orEmpty() }
    val list = charts ?: return
    if (list.isEmpty()) return
    // the BMS ground plates, and (where the theater ships them) the instrument charts: approach, SID, STAR…
    // anything that came out of a chart PDF is an instrument chart, even the many one-page ones; the BMS plates
    // (ground, parking, end of runway) are the other group
    val plates = list.filter { it.pages.isEmpty() }
    val instrument = list.filter { it.pages.isNotEmpty() }
    if (plates.isNotEmpty()) ChartStrip("Charts (${plates.size})", plates, nav, airportName, setId, airportId, "Tap to open · pinch to zoom")
    if (instrument.isNotEmpty()) ChartStrip("Instrument charts (${instrument.size})", instrument, nav, airportName, setId, airportId, "Approach, departure and arrival · tap to open")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartStrip(title: String, list: List<ChartRef>, nav: NavHostController, airportName: String, setId: String, airportId: Int, hint: String) {
    SectionCard(title, accent = Hud.Amber) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            list.forEach { c ->
                Column(Modifier.width(142.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).clickable { nav.go(chartRoute(c.file, "$airportName · ${c.title}", c.pages.size, 1, setId, airportId)) }) {
                    Box(Modifier.fillMaxWidth().height(160.dp).background(Color.White)) {
                        AssetImage(c.file, Modifier.fillMaxSize().padding(2.dp), ContentScale.Fit, sample = 8)
                        if (c.pages.size > 1) Text(
                            "${c.pages.size} pages",
                            Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Hud.Bg.copy(alpha = 0.75f)).padding(horizontal = 5.dp, vertical = 2.dp),
                            fontSize = 10.sp, color = Hud.Text,
                        )
                    }
                    Text(c.title, Modifier.padding(8.dp), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun ChartViewerScreen(
    nav: NavHostController,
    file: String,
    title: String,
    pages: Int = 0,
    start: Int = 1,
    setId: String = "",
    airportId: Int = 0,
) {
    val count = maxOf(pages, 1)
    var page by rememberSaveable(file) { mutableIntStateOf(start.coerceIn(1, count)) }
    var turns by rememberSaveable(file) { mutableIntStateOf(0) } // quarter turns, for a page the extractor left sideways
    val current = if (count > 1) chartPage(file, page) else file
    val bmp by produceState<Bitmap?>(null, current) { value = chartBitmap(current) }
    val close = { nav.popBackStack(); Unit }

    // The airfield's other charts of the same kind, in the order the cards show them. Reading one chart to the end is
    // usually reading the set, and closing and reopening between every one of Osan's eight is no way to fly.
    val siblings by produceState(emptyList<ChartRef>(), setId, airportId, file) {
        val all = if (setId.isEmpty()) emptyList() else Repo.charts(setId)[airportId.toString()].orEmpty()
        val instrument = all.firstOrNull { it.file == file }?.pages?.isNotEmpty() ?: true
        value = all.filter { it.pages.isNotEmpty() == instrument }
    }
    val airport = title.substringBefore(" · ", "")
    val index = siblings.indexOfFirst { it.file == file }
    // the set reads as a ring: off the end of the last chart is the first one again
    val previous = if (index >= 0 && siblings.size > 1) siblings[(index - 1 + siblings.size) % siblings.size] else null
    val next = if (index >= 0 && siblings.size > 1) siblings[(index + 1) % siblings.size] else null
    val open = { ref: ChartRef, from: Int ->
        nav.popBackStack()
        nav.go(chartRoute(ref.file, if (airport.isEmpty()) ref.title else "$airport · ${ref.title}", ref.pages.size, from, setId, airportId))
    }
    val step = { delta: Int ->
        val target = page + delta
        when {
            target in 1..count -> page = target
            // one chart on its own still turns: its last page leads back to its first
            next == null || previous == null -> page = if (target > count) 1 else count
            target > count -> open(next, 1)
            else -> open(previous, maxOf(previous.pages.size, 1))
        }
        Unit
    }
    val where = if (index >= 0 && siblings.size > 1) "chart ${index + 1} of ${siblings.size}" else null

    Column(
        Modifier.fillMaxSize().background(Hud.Bg)
            // ← → (and page up/down) turn the page and then carry on into the next chart, Esc closes
            .chartKeys(focusKey = file to page, onPrev = { step(-1) }, onNext = { step(1) }, onClose = close),
    ) {
        BmsTopBar(
            title.substringAfter(" · ", title),
            listOfNotNull(airport.ifEmpty { null }, where).joinToString(" · ").ifEmpty { null },
            onBack = close,
        ) {
            IconButton(onClick = { turns = (turns + 1) % 4 }) { Icon(Icons.Default.Rotate90DegreesCw, "Turn the page", tint = Hud.TextDim) }
            IconButton(onClick = close) { Icon(Icons.Default.Close, "Close the chart", tint = Hud.Text) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val b = bmp
            if (b == null) LoadingBox() else ZoomableBitmap(b, Modifier.fillMaxSize(), turns = turns, onSwipe = { step(it) })
        }
        if (count > 1 || siblings.size > 1) {
            PageBar(
                page = page,
                pages = count,
                canPrev = true,
                canNext = true,
                onStep = { step(it) },
                onPage = { page = it },
            )
        }
    }
}

/** Page strip for multi-page charts: previous/next and a tap-able page number for every page. */
@Composable
private fun PageBar(page: Int, pages: Int, canPrev: Boolean, canNext: Boolean, onStep: (Int) -> Unit, onPage: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Hud.Surface).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageKey(Icons.Default.ChevronLeft, "Previous page", canPrev) { onStep(-1) }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pages > 1) for (p in 1..pages) Text(
                "$p",
                Modifier.clip(RoundedCornerShape(9.dp)).background(if (p == page) Hud.Amber else Hud.Surface2).clickable { onPage(p) }
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                color = if (p == page) Hud.Bg else Hud.TextDim, fontSize = 13.sp,
            )
        }
        if (pages > 1) Text("$page / $pages", fontSize = 12.sp, color = Hud.TextDim)
        PageKey(Icons.Default.ChevronRight, "Next page", canNext) { onStep(1) }
    }
}

/** Turning the page is the thing you do most in a chart, often with gloves or a shaky hand: give it a big target. */
@Composable
private fun PageKey(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Hud.Surface3 else Hud.Surface2)
            .border(1.dp, if (enabled) Hud.Amber.copy(alpha = 0.55f) else Hud.Outline.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .let { if (enabled) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (enabled) Hud.Amber else Hud.TextFaint, modifier = Modifier.size(30.dp)) }
}

/** Zoom: how far the page is allowed in, how far one wheel notch and one button press take it, and how it gets there. */
private const val MAX_ZOOM = 8f
private const val WHEEL_STEP = 1.07f
private const val BUTTON_STEP = 1.55f
private val ZOOM_SPRING = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/**
 * The chart viewer. Zoom lives on three controls that all mean the same thing: pinch (or the wheel), a double tap,
 * and the +/- buttons in the corner - because a gloved hand in a cockpit cannot pinch and a mouse cannot pinch at all.
 *
 * Zoom and pan are animated rather than set, so the page glides to where it is going: a wheel notch is a small step
 * and the spring carries it, which reads as one smooth movement instead of a stack of jumps. Dragging and pinching
 * skip the animation and follow the finger exactly.
 *
 * Swiping turns the page, but only while the page is fitted: at 1x a drag moves nothing (the page is centred and
 * stays centred), so that drag is free, and the moment you zoom in it goes back to being how you move around the
 * plate. The swipe has to cross a sixth of the width to count, which is far enough that panning a zoomed page and
 * then letting go cannot turn it by accident.
 */
@Composable
fun ZoomableBitmap(b: Bitmap, modifier: Modifier, background: Color = Color.White, turns: Int = 0, onSwipe: ((Int) -> Unit)? = null) {
    val zoom = remember { Animatable(1f) }
    val panX = remember { Animatable(0f) }
    val panY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val zoomedIn by remember { derivedStateOf { zoom.value > 1.02f } }
    // how far the finger has travelled sideways in this one gesture, reset the moment it goes down again
    val swiped = remember { mutableFloatStateOf(0f) }
    val img = remember(b) { b.asImageBitmap() }
    BoxWithConstraints(modifier.clipToBounds().background(background)) {
        val d = LocalDensity.current
        val w = with(d) { maxWidth.toPx() }; val h = with(d) { maxHeight.toPx() }
        // a quarter turn swaps the page's width and height for the "fit to window" calculation
        val sideways = turns % 2 == 1
        val pw = if (sideways) b.height else b.width
        val ph = if (sideways) b.width else b.height
        val fit = min(w / pw, h / ph)
        // zooming about a point leaves whatever is under that point where it is; back at 1x the page sits centred again
        fun zoomAbout(p: Offset, to: Float, smooth: Boolean, by: Offset = Offset.Zero) {
            val ns = to.coerceIn(1f, MAX_ZOOM)
            val c = Offset(p.x - w / 2, p.y - h / 2)
            val f = ns / zoom.value
            val nx = if (ns <= 1f) 0f else c.x - (c.x - (panX.value + by.x)) * f
            val ny = if (ns <= 1f) 0f else c.y - (c.y - (panY.value + by.y)) * f
            scope.launch {
                if (!smooth) { zoom.snapTo(ns); panX.snapTo(nx); panY.snapTo(ny) }
                else {
                    launch { zoom.animateTo(ns, ZOOM_SPRING) }
                    launch { panX.animateTo(nx, ZOOM_SPRING) }
                    launch { panY.animateTo(ny, ZOOM_SPRING) }
                }
            }
        }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(b) {
                    // a fresh gesture starts from zero, watched on the way down so nothing here consumes the event
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        swiped.floatValue = 0f
                    }
                }
                .pointerInput(b, onSwipe) {
                    detectTransformGestures { centroid, pan, gesture, _ ->
                        val fitted = zoom.value <= 1.02f && gesture in 0.99f..1.01f
                        if (fitted && onSwipe != null) {
                            swiped.floatValue += pan.x
                            if (abs(swiped.floatValue) > w / 6f) {
                                onSwipe(if (swiped.floatValue < 0) 1 else -1)   // dragging left brings the next page in
                                swiped.floatValue = 0f
                            }
                        } else {
                            zoomAbout(centroid, zoom.value * gesture, smooth = false, by = pan)
                        }
                    }
                }
                .pointerInput(b) {
                    detectTapGestures(onDoubleTap = { p -> zoomAbout(p, if (zoom.value > 1.5f) 1f else 3f, smooth = true) })
                },
        ) {
            val dw = b.width * fit * zoom.value; val dh = b.height * fit * zoom.value
            rotate(turns * 90f, Offset(w / 2 + panX.value, h / 2 + panY.value)) {
                drawImage(img, dstOffset = IntOffset(((w - dw) / 2 + panX.value).toInt(), ((h - dh) / 2 + panY.value).toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
            }
        }
        val centre = Offset(w / 2, h / 2)
        // A numbered board is one OpenKneeboard tab in a headset: there is no pointer to press a zoom key with, and
        // a key that cannot be pressed is only in the way of the chart.
        if (Kneeboard.slot == null) Column(
            Modifier.align(Alignment.BottomEnd).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZoomKey(Icons.Default.Add, "Zoom in") { zoomAbout(centre, zoom.value * BUTTON_STEP, smooth = true) }
            ZoomKey(Icons.Default.Remove, "Zoom out", enabled = zoomedIn) { zoomAbout(centre, zoom.value / BUTTON_STEP, smooth = true) }
            ZoomKey(Icons.Default.FitScreen, "Fit the page", enabled = zoomedIn) { zoomAbout(centre, 1f, smooth = true) }
        }
        // a kneeboard says nothing it does not have to
        if (!Hud.onPaper) Text("Swipe to turn the page · double-tap to zoom", Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(RoundedCornerShape(8.dp)).background(Hud.Bg.copy(alpha = 0.75f)).border(1.dp, Hud.Outline, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 11.sp, color = Hud.TextDim)
    }
}

/** A corner button on the page itself: small enough to keep out of the chart, big enough for a gloved thumb. */
@Composable
private fun ZoomKey(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
            .background(Hud.Bg.copy(alpha = 0.78f))
            .border(1.dp, if (enabled) Hud.Amber.copy(alpha = 0.5f) else Hud.Outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .let { if (enabled) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (enabled) Hud.Amber else Hud.TextFaint, modifier = Modifier.size(22.dp)) }
}

/**
 * ← → (and page up/down) turn the page, Esc closes. The viewer asks for the keyboard as it opens; on a tablet
 * there is none and the page bar and gestures do the work.
 */
@Composable
private fun Modifier.chartKeys(focusKey: Any, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit): Modifier {
    val focus = remember { FocusRequester() }
    // clicking a page button moves the focus onto it, and the keys are read here: take it back each time
    LaunchedEffect(focusKey) { runCatching { focus.requestFocus() } }
    return this.focusRequester(focus).focusable().onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (e.key) {
            Key.DirectionLeft, Key.PageUp -> { onPrev(); true }
            Key.DirectionRight, Key.PageDown, Key.Spacebar -> { onNext(); true }
            Key.Escape -> { onClose(); true }
            else -> false
        }
    }
}
