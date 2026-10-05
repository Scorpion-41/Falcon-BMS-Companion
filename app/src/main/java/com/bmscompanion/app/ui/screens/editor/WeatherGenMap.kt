package com.bmscompanion.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.weather.WxCell
import com.bmscompanion.app.data.weather.WxColors
import com.bmscompanion.app.data.weather.WxCover
import com.bmscompanion.app.data.weather.WxDefaults
import com.bmscompanion.app.data.weather.WxGrid
import com.bmscompanion.app.data.weather.WxOverride
import com.bmscompanion.app.data.weather.WxType
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.TheaterMap
import com.bmscompanion.app.ui.components.rememberMapState
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The generated weather over the theater: the cells coloured by the chosen display, a figure or a symbol in each
 * cell for the chosen overlay, the override regions with their handles, the legend, and a hover readout.
 *
 * The map underneath is the app's own (`TheaterMap`), so nothing new ships: the 59 x 59 cells are laid over the
 * same projection every other map uses, cell (0, 0) at the north-west corner.
 */

/** The projection the map last drew with, for turning a pointer into a cell between frames. Not state: read, never observed. */
private class ProjBox {
    var p: MapProjection? = null
    /** where the map's box is in the window, for the browser audit's probe only */
    var origin: Offset = Offset.Zero
}

/**
 * The Weather page's entries in the browser audit's probe (`?probe=1`, `window.__bmscProbe()`, see `Browser.kt`):
 * `"weather/<name>"` rectangles in window pixels — the theater picker and its entries, the map's grid, each drawn
 * region's centre, the selection controls — so a headless browser can click them on a page that is one canvas. Off
 * everywhere else, where it costs one boolean.
 */
internal object WxProbe {
    val on: Boolean get() = com.bmscompanion.app.ui.screens.wdp.WdpProbe.on
    fun put(key: String, r: androidx.compose.ui.geometry.Rect) {
        if (on) com.bmscompanion.app.ui.screens.wdp.WdpProbe.rects["weather/$key"] = r
    }
    fun drop(key: String) { com.bmscompanion.app.ui.screens.wdp.WdpProbe.rects.remove("weather/$key") }
    /** every "weather/<prefix>…" entry, for a set of things (the regions) that changes as it is drawn */
    fun dropAll(prefix: String) {
        if (on) com.bmscompanion.app.ui.screens.wdp.WdpProbe.rects.keys.removeAll { it.startsWith("weather/$prefix") }
    }
}

/** Where a control is, for [WxProbe]; nothing at all when the probe is off. */
@Composable
internal fun Modifier.wxProbe(key: String): Modifier {
    if (!WxProbe.on) return this
    androidx.compose.runtime.DisposableEffect(key) { onDispose { WxProbe.drop(key) } }
    return onGloballyPositioned { c ->
        val p = c.positionInWindow()
        WxProbe.put(key, androidx.compose.ui.geometry.Rect(p, Size(c.size.width.toFloat(), c.size.height.toFloat())))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WxGenMap(th: Theater, st: WxGenState, airports: List<Airport>) {
    val mapState = rememberMapState()
    val holder = remember { ProjBox() }
    val grid = st.grid
    val display = WxGenLook.display
    // One small picture per grid, a pixel a cell, drawn scaled up: 3,481 rectangles a frame would be the same
    // picture at far more cost, every time the map pans.
    val fill = remember(grid, display) { grid?.let { fillImage(it, display) } }
    val text = rememberTextMeasurer(cacheSize = 512)
    val handlePx = with(LocalDensity.current) { 24.dp.toPx() }

    fun cellAt(pos: Offset): Pair<Double, Double>? {
        val pr = holder.p ?: return null
        val g = st.grid ?: return null
        val cw = pr.side / g.cols
        val ch = pr.side / g.rows
        return ((pos.x - pr.left) / cw - 0.5).toDouble() to ((pos.y - pr.top) / ch - 0.5).toDouble()
    }

    /** The whole cell a screen point is in, or null off the grid. */
    fun cellIndexAt(pos: Offset): Pair<Int, Int>? {
        val g = st.grid ?: return null
        val (x, y) = cellAt(pos) ?: return null
        val c = (x + 0.5).toInt()
        val r = (y + 0.5).toInt()
        return if (x > -0.5 && y > -0.5 && c < g.cols && r < g.rows) c to r else null
    }

    /** A region's centre on screen. */
    fun centreOf(o: WxOverride): Offset? {
        val pr = holder.p ?: return null
        val g = st.grid ?: return null
        val cw = pr.side / g.cols
        return Offset(pr.left + ((o.x + 0.5) * cw).toFloat(), pr.top + ((o.y + 0.5) * cw).toFloat())
    }

    /**
     * The region whose centre — where its number is drawn — is under a screen point, nearest first. Only a region the
     * map shows can be clicked: one with its outline off is reached from the list in the panel.
     */
    fun regionAt(pos: Offset): Int? {
        var best: Int? = null
        var bestD = Float.MAX_VALUE
        st.params.overrides.forEachIndexed { i, o ->
            if (!o.showOutline && i !in st.regions) return@forEachIndexed
            val d = centreOf(o)?.let { (pos - it).getDistance() } ?: return@forEachIndexed
            if (d <= handlePx && d < bestD) { best = i; bestD = d }
        }
        return best
    }

    Box(
        Modifier.fillMaxSize()
            .then(if (WxProbe.on) Modifier.onGloballyPositioned { holder.origin = it.positionInWindow() } else Modifier)
            // Hover: the cell under a mouse, for the readout. Watched on the way down and never consumed, so the
            // map's own gestures are untouched.
            .pointerInput(st) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        when (ev.type) {
                            PointerEventType.Move -> if (ev.changes.none { it.pressed }) {
                                val g = st.grid
                                st.hover = ev.changes.firstOrNull()?.let { cellAt(it.position) }?.let { (x, y) ->
                                    val c = (x + 0.5).toInt()
                                    val r = (y + 0.5).toInt()
                                    if (g != null && x > -0.5 && y > -0.5 && c < g.cols && r < g.rows) c to r else null
                                }
                            }
                            PointerEventType.Exit -> st.hover = null
                            else -> {}
                        }
                    }
                }
            }
            // The gestures that are the page's own, taken on the way down before the map sees them — and only these,
            // so a drag anywhere else still pans:
            // - a press on a picked region's centre drags every picked region together (WeatherGen's editing latch,
            //   grown to a selection); on the ring of the one picked region it sizes it;
            // - a click with Shift or Ctrl held (or any tap in Select several) adds what it lands on to the selection,
            //   or takes it away, at once — a plain click is left to the map, which picks one thing (onTap below);
            // - a press held still drops a new region.
            .pointerInput(st) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val mods = currentEvent.keyboardModifiers
                    val additive = st.multi || mods.isShiftPressed || mods.isCtrlPressed || mods.isMetaPressed
                    val pr0 = holder.p ?: return@awaitEachGesture
                    val g = st.grid ?: return@awaitEachGesture
                    val cw0 = pr0.side / g.cols
                    val overrides0 = st.params.overrides
                    val picked = st.regions.filter { it in overrides0.indices }
                    val onCentre = picked
                        .map { it to ((centreOf(overrides0[it])?.let { c -> (down.position - c).getDistance() }) ?: Float.MAX_VALUE) }
                        .filter { it.second <= handlePx }
                        .minByOrNull { it.second }?.first
                    val e = st.editing
                    val onRing = onCentre == null && e != null && overrides0.getOrNull(e)?.let { o ->
                        val c = centreOf(o) ?: return@let false
                        abs((down.position - c).getDistance() - (o.radius * cw0).toFloat()) <= handlePx
                    } == true
                    if (onCentre != null || onRing) {
                        down.consume()
                        val start = cellAt(down.position) ?: return@awaitEachGesture
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                ch.consume()
                                // pressed and let go where it was: a click on the region, not a drag
                                if (!moved && onCentre != null) st.click(onCentre, null, additive)
                                break
                            }
                            if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved) {
                                val (x, y) = cellAt(ch.position) ?: break
                                if (onCentre != null) {
                                    // every picked region by the same amount, from where each was when the press began
                                    val dx = x - start.first
                                    val dy = y - start.second
                                    st.edit { p ->
                                        p.copy(overrides = p.overrides.mapIndexed { k, o ->
                                            val o0 = overrides0.getOrNull(k)
                                            if (k in picked && o0 != null) {
                                                o.copy(
                                                    x = round1((o0.x + dx).coerceIn(-10.0, g.cols + 10.0)),
                                                    y = round1((o0.y + dy).coerceIn(-10.0, g.rows + 10.0)),
                                                )
                                            } else o
                                        })
                                    }
                                } else if (e != null) {
                                    st.editOverride(e) {
                                        val rx = x - it.x
                                        val ry = y - it.y
                                        val r = round1(kotlin.math.sqrt(rx * rx + ry * ry).coerceIn(1.0, 80.0))
                                        it.copy(radius = r, falloff = it.falloff.coerceAtMost(r))
                                    }
                                }
                            }
                            ch.consume()
                        }
                        return@awaitEachGesture
                    }
                    // Anything else: let go without moving is a click, held still is a long press, moved is the map's.
                    // 1 = a click, 0 = the map's; null = held (the timeout)
                    val outcome: Int? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var result = 0
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (ch == null || ev.changes.size > 1) break
                            if (!ch.pressed) {
                                if (additive) {
                                    // taken here, so the map's own tap (which would pick one thing) never sees it
                                    ch.consume()
                                    result = 1
                                }
                                break
                            }
                            if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        }
                        result
                    }
                    if (outcome == 1) {
                        if (st.placing) {
                            st.placing = false
                            cellAt(down.position)?.let { (x, y) -> st.addOverride(round1(x), round1(y)) }
                        } else {
                            st.click(regionAt(down.position), cellIndexAt(down.position), additive = true)
                        }
                    }
                    if (outcome == null) {
                        cellAt(down.position)?.let { (x, y) ->
                            if (x > -0.5 && y > -0.5 && x < g.cols - 0.5 && y < g.rows - 0.5) st.addOverride(round1(x), round1(y))
                        }
                        // the rest of this press is the region's, so the map neither taps nor pans on the way up
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            ev.changes.forEach { it.consume() }
                            if (ev.changes.none { it.pressed }) break
                        }
                    }
                }
            },
    ) {
        TheaterMap(
            imagePath = th.map,
            sizeFt = th.sizeFt,
            state = mapState,
            modifier = Modifier.fillMaxSize(),
            fillWidth = true,
            style = if (WxGenLook.bms) "chart" else null,
            // the theater here is the one whose weather is being made, not necessarily the one being flown
            mission = false,
            // a plain click: the region whose number it lands on, else the cell, picked alone (Shift, Ctrl and Select
            // several are taken above, before the map sees them)
            onTap = { xFt, yFt, pr ->
                val g = st.grid
                if (g != null) {
                    if (st.placing) {
                        st.placing = false
                        st.addOverride(round1(yFt / th.sizeFt * g.cols - 0.5), round1((1 - xFt / th.sizeFt) * g.rows - 0.5))
                    } else {
                        st.click(regionAt(pr.toScreen(xFt, yFt)), cellOf(xFt, yFt, th.sizeFt, g.cols, g.rows), additive = false)
                    }
                }
            },
        ) { proj ->
            holder.p = proj
            val g = grid ?: return@TheaterMap
            drawWeather(proj, g, fill, text, st, airports, th)
            if (WxProbe.on) {
                val o = holder.origin
                WxProbe.put("grid", androidx.compose.ui.geometry.Rect(o + Offset(proj.left, proj.top), Size(proj.side, proj.side)))
                WxProbe.dropAll("region-")
                val cw = proj.side / g.cols
                st.params.overrides.forEachIndexed { i, r ->
                    if (!r.showOutline && i !in st.regions) return@forEachIndexed
                    val c = o + Offset(proj.left + ((r.x + 0.5) * cw).toFloat(), proj.top + ((r.y + 0.5) * cw).toFloat())
                    WxProbe.put("region-${i + 1}", androidx.compose.ui.geometry.Rect(c - Offset(1f, 1f), Size(2f, 2f)))
                }
            }
        }
        Legend(Modifier.align(Alignment.TopStart))
        // A touch screen has no Shift key: this is it. On the PC and in a browser Shift- or Ctrl-click does the same.
        Box(Modifier.align(Alignment.TopEnd).padding(10.dp).wxProbe("select-several")) {
            // short on a phone, where the legend takes most of the top edge; the banner below says what it does
            val roomy = com.bmscompanion.app.ui.components.isMedium()
            EditorChip(
                when {
                    st.multi -> if (roomy) "Selecting several" else "Selecting"
                    else -> if (roomy) "Select several" else "Select"
                },
                selected = st.multi,
            ) { st.multi = !st.multi }
        }
        val bottom = Modifier.align(Alignment.BottomCenter)
        val nRegions = st.regions.size
        when {
            st.placing -> Banner(bottom, "Tap the map where the region goes", "Cancel") { st.placing = false }
            nRegions > 1 -> Banner(
                bottom,
                "$nRegions regions picked: drag one's centre to move them together; the panel changes all of them", "Done",
            ) { st.regions = emptySet() }
            st.editing != null -> Banner(
                bottom,
                "Region ${(st.editing ?: 0) + 1}: drag the centre to move it, the ring to size it. " +
                    (if (st.multi) "Tap another to add it." else "Shift- or Ctrl-click another to add it."),
                "Done",
            ) { st.editing = null }
            st.cells.size > 1 -> Banner(bottom, "${st.cells.size} cells picked: the panel sums them up", "Clear") {
                st.cells = emptySet(); st.selected = null
            }
            st.multi -> Banner(bottom, "Select several: tap region numbers or cells to add them or take them away", "Done") {
                st.multi = false
            }
        }
        // the readout sits above a banner rather than over its words
        val banner = st.placing || nRegions > 0 || st.cells.size > 1 || st.multi
        st.grid?.cell(st.hover)?.let { c ->
            HoverCard(Modifier.align(Alignment.BottomStart).padding(bottom = if (banner) 50.dp else 0.dp), st.hover!!, c)
        }
    }
}

private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

// ------------------------------------------------------------------------------------------------ drawing

private fun fillImage(g: WxGrid, d: WxDisplay): ImageBitmap? {
    if (d == WxDisplay.NONE) return null
    val img = ImageBitmap(g.cols, g.rows)
    val canvas = Canvas(img)
    val paint = Paint()
    for (y in 0 until g.rows) for (x in 0 until g.cols) {
        val c = g.at(x, y)
        paint.color = Color(
            when (d) {
                WxDisplay.TYPE -> WxColors.type(c.type)
                WxDisplay.PRESSURE -> WxColors.pressure(c.pressureInHg)
                else -> WxColors.temperature(c.tempC)
            },
        )
        canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
    }
    return img
}

private fun DrawScope.drawWeather(
    proj: MapProjection,
    g: WxGrid,
    fill: ImageBitmap?,
    text: TextMeasurer,
    st: WxGenState,
    airports: List<Airport>,
    th: Theater,
) {
    val cw = proj.side / g.cols
    val ch = proj.side / g.rows
    val light = WxGenLook.bms
    val ink = if (light) Color(0xFF12161B) else Color.White
    val halo = if (light) Color.White else Color.Black

    fill?.let {
        drawImage(
            it,
            srcOffset = IntOffset.Zero, srcSize = IntSize(g.cols, g.rows),
            dstOffset = IntOffset(proj.left.roundToInt(), proj.top.roundToInt()),
            dstSize = IntSize(proj.side.roundToInt(), proj.side.roundToInt()),
            alpha = WxGenLook.opacity,
            filterQuality = FilterQuality.None,
        )
    }
    if (WxGenLook.grid && cw > 3f) {
        val fine = ink.copy(alpha = 0.12f)
        val bold = ink.copy(alpha = 0.32f)
        for (c in 1 until g.cols) drawRect(if (c % 10 == 0) bold else fine, Offset(proj.left + c * cw, proj.top), Size(1f, proj.side))
        for (r in 1 until g.rows) drawRect(if (r % 10 == 0) bold else fine, Offset(proj.left, proj.top + r * ch), Size(proj.side, 1f))
    }
    drawRect(ink.copy(alpha = 0.45f), Offset(proj.left, proj.top), Size(proj.side, proj.side), style = Stroke(1f))

    // The overlay, in as many cells as it can be read in: at the whole-theater zoom every cell of a 59-wide grid is
    // a few pixels, so the figures thin out to every second or third cell, on a fixed lattice so they do not shimmer
    // as the map pans, and fill in as it zooms.
    val overlay = WxGenLook.overlay
    if (overlay != WxOverlay.NONE) {
        val minPx = when (overlay) {
            WxOverlay.WIND -> 30f
            WxOverlay.COVER -> 20f
            WxOverlay.PRESSURE -> if (WxGenLook.mb) 34f else 42f
            else -> 30f
        }
        val stride = ceil(minPx / cw).toInt().coerceAtLeast(1)
        val c0 = floor(-proj.left / cw).toInt().coerceAtLeast(0)
        val c1 = ceil((size.width - proj.left) / cw).toInt().coerceAtMost(g.cols - 1)
        val r0 = floor(-proj.top / ch).toInt().coerceAtLeast(0)
        val r1 = ceil((size.height - proj.top) / ch).toInt().coerceAtMost(g.rows - 1)
        val style = TextStyle(
            color = ink, fontSize = if (cw * stride > 44f) 11.sp else 9.5.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold, shadow = Shadow(halo, blurRadius = 3f),
        )
        val level = WxGenLook.windLevel
        val unit = (cw * stride).coerceAtMost(64f)
        for (r in (r0 - r0 % stride)..r1 step stride) {
            if (r < 0) continue
            for (c in (c0 - c0 % stride)..c1 step stride) {
                if (c < 0) continue
                val cell = g.at(c, r)
                val centre = Offset(proj.left + (c + 0.5f) * cw, proj.top + (r + 0.5f) * ch)
                when (overlay) {
                    WxOverlay.WIND -> windBarb(centre, cell.windDeg[level], cell.windKt[level], unit * 0.46f, ink, halo)
                    WxOverlay.COVER -> coverGlyph(centre, cell.cover, cell.towering, (unit * 0.24f).coerceAtMost(9f), ink, halo)
                    else -> {
                        val s = overlayText(overlay, cell)
                        if (s.isNotEmpty()) {
                            val m = text.measure(s, style)
                            drawText(m, topLeft = centre - Offset(m.size.width / 2f, m.size.height / 2f))
                        }
                    }
                }
            }
        }
    }

    // the override regions: full strength inside the inner ring, fading out to the outer. Picked ones are lit; the one
    // picked alone has its handles.
    val single = st.editing
    st.params.overrides.forEachIndexed { i, o ->
        val picked = i in st.regions
        val edit = single == i
        if (!o.showOutline && !picked) return@forEachIndexed
        val centre = Offset(proj.left + ((o.x + 0.5) * cw).toFloat(), proj.top + ((o.y + 0.5) * ch).toFloat())
        val colour = if (picked) Hud.Amber else o.type?.let { typeSwatch(it) } ?: Hud.Magenta
        val rOuter = (o.radius * cw).toFloat()
        drawCircle(halo.copy(alpha = 0.5f), rOuter, centre, style = Stroke(if (picked) 5f else 4f))
        drawCircle(colour, rOuter, centre, style = Stroke(if (picked) 2.5f else 2f))
        if (picked && !edit) {
            // one of several picked: its centre marked, to show where to grab them all
            drawCircle(halo, 7f, centre)
            drawCircle(Hud.Amber, 5f, centre)
        }
        if (o.falloff > 0) {
            drawCircle(
                colour.copy(alpha = 0.8f), (o.falloff * cw).toFloat(), centre,
                style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
            )
        }
        val label = text.measure("${i + 1}", TextStyle(color = colour, fontSize = 11.sp, fontWeight = FontWeight.Bold, shadow = Shadow(halo, blurRadius = 3f)))
        if (edit) {
            drawCircle(halo, 9f, centre)
            drawCircle(Hud.Amber, 7f, centre)
            val rim = centre + Offset(rOuter, 0f)
            drawRect(halo, rim - Offset(7f, 7f), Size(14f, 14f))
            drawRect(Hud.Amber, rim - Offset(5f, 5f), Size(10f, 10f))
            drawText(label, topLeft = centre + Offset(10f, -label.size.height - 4f))
        } else if (picked) {
            drawText(label, topLeft = centre + Offset(8f, -label.size.height - 2f))
        } else {
            drawText(label, topLeft = centre - Offset(label.size.width / 2f, label.size.height / 2f))
        }
    }

    // every picked cell, and — heavier — the one the inspector is showing, with the airfield whose forecast it gives
    if (st.cells.size > 1) {
        for ((c, r) in st.cells) {
            if (st.selected == c to r) continue
            drawRect(halo.copy(alpha = 0.7f), Offset(proj.left + c * cw, proj.top + r * ch), Size(cw, ch), style = Stroke(3f))
            drawRect(Hud.Amber.copy(alpha = 0.85f), Offset(proj.left + c * cw, proj.top + r * ch), Size(cw, ch), style = Stroke(1.5f))
        }
    }
    st.selected?.let { (c, r) ->
        drawRect(halo, Offset(proj.left + c * cw - 1.5f, proj.top + r * ch - 1.5f), Size(cw + 3f, ch + 3f), style = Stroke(4f))
        drawRect(Hud.Amber, Offset(proj.left + c * cw - 1.5f, proj.top + r * ch - 1.5f), Size(cw + 3f, ch + 3f), style = Stroke(2f))
    }
    st.airport?.let { a ->
        val p = proj.toScreen(a.x, a.y)
        drawCircle(halo, 9f, p, style = Stroke(4f))
        drawCircle(Hud.Cyan, 9f, p, style = Stroke(2f))
    }
}

/** What an overlay writes in a cell: cloud base in hundreds of feet as a METAR does, and the rest as the pilot reads them. */
private fun overlayText(o: WxOverlay, c: WxCell): String = when (o) {
    WxOverlay.BASE -> if (c.cover == WxCover.NONE) "" else three((c.baseFt / 100).toInt())
    WxOverlay.VISIBILITY -> visText(c.visKm)
    WxOverlay.PRESSURE -> pressureText(c.pressureInHg, WxGenLook.mb)
    WxOverlay.TEMPERATURE -> "${c.tempC.roundToInt()}°"
    else -> ""
}

/**
 * A wind barb, pointing into the wind: the staff runs from the cell toward where the wind comes **from**, and the
 * feathers sit on its clockwise side, as a northern-hemisphere station plot draws them — a pennant for 50 kt, a
 * full feather for 10 and a half for 5, rounded to the nearest 5. Under 3 kt it is a ring: calm.
 */
private fun DrawScope.windBarb(c: Offset, fromDeg: Double, kt: Double, len: Float, ink: Color, halo: Color) {
    if (kt < 2.5) {
        drawCircle(halo, len * 0.2f, c, style = Stroke(3.5f))
        drawCircle(ink, len * 0.2f, c, style = Stroke(1.5f))
        return
    }
    val rad = fromDeg * kotlin.math.PI / 180
    val u = Offset(sin(rad).toFloat(), -cos(rad).toFloat())
    val perp = Offset(-u.y, u.x)
    // the barb is centred on the cell rather than hung from it, so neighbouring barbs do not overlap
    val start = c - u * (len / 2)
    val end = c + u * (len / 2)
    var rest = (kt / 5).roundToInt() * 5
    val feather = len * 0.42f
    val gap = len * 0.16f
    val strokes = mutableListOf<Pair<Offset, Offset>>()
    val pennants = mutableListOf<Path>()
    var at = 0f
    while (rest >= 50) {
        val a = end - u * at
        val b = end - u * (at + gap * 1.2f)
        pennants += Path().apply {
            moveTo(a.x, a.y); lineTo((a + perp * feather).x, (a + perp * feather).y); lineTo(b.x, b.y); close()
        }
        at += gap * 1.5f
        rest -= 50
    }
    while (rest >= 10) {
        val a = end - u * at
        strokes += a to a + perp * feather + u * (gap * 0.9f)
        at += gap
        rest -= 10
    }
    if (rest >= 5) {
        // a lone half feather is set in from the end, so it is not mistaken for a full one
        if (at == 0f) at = gap
        val a = end - u * at
        strokes += a to a + (perp * feather + u * (gap * 0.9f)) * 0.5f
    }
    for (w in listOf(4f to halo, 1.6f to ink)) {
        drawLine(w.second, start, end, strokeWidth = w.first, cap = StrokeCap.Round)
        for ((a, b) in strokes) drawLine(w.second, a, b, strokeWidth = w.first, cap = StrokeCap.Round)
    }
    for (p in pennants) { drawPath(p, halo, style = Stroke(3f)); drawPath(p, ink) }
    drawCircle(ink, 1.8f, start)
}

/**
 * Sky cover as a station plot draws it: an empty ring for clear, a quarter, half, three quarters or all of it filled
 * for FEW, SCT, BKN and OVC, and a small peak over it where cumulus is towering.
 */
private fun DrawScope.coverGlyph(c: Offset, cover: WxCover, towering: Boolean, r: Float, ink: Color, halo: Color) {
    drawCircle(halo, r + 1.5f, c)
    val sweep = when (cover) {
        WxCover.NONE -> 0f
        WxCover.FEW -> 90f
        WxCover.SCATTERED -> 180f
        WxCover.BROKEN -> 270f
        WxCover.OVERCAST -> 360f
    }
    if (sweep > 0f) drawArc(ink, -90f, sweep, true, c - Offset(r, r), Size(2 * r, 2 * r))
    drawCircle(ink, r, c, style = Stroke(1.4f))
    if (towering) {
        val top = c + Offset(0f, -r - 2f)
        val p = Path().apply {
            moveTo(top.x - r * 0.7f, top.y); lineTo(top.x, top.y - r * 1.1f); lineTo(top.x + r * 0.7f, top.y); close()
        }
        drawPath(p, halo, style = Stroke(2.5f))
        drawPath(p, Color(0xFFFF7043))
    }
}

// ------------------------------------------------------------------------------------------------ text

internal fun three(v: Int): String = v.coerceIn(0, 999).toString().padStart(3, '0')

/** WeatherGen's rule: a decimal only below 2 km, where a tenth is the difference between legal and not. */
internal fun visText(km: Double): String =
    if (km < 2) ((km * 10).roundToInt() / 10.0).toString() else km.roundToInt().toString()

internal fun pressureText(inHg: Double, mb: Boolean): String =
    if (mb) com.bmscompanion.app.data.weather.WxModel.inHgToMb(inHg).roundToInt().toString()
    else {
        val h = (inHg * 100).roundToInt()
        "${h / 100}.${(h % 100).toString().padStart(2, '0')}"
    }

/** "325@12", heading from, three digits, and knots — WeatherGen's `format-wind` without the "kts". */
internal fun windText(deg: Double, kt: Double): String {
    val d = deg.roundToInt().mod(360).let { if (it == 0) 360 else it }
    return "${three(d)}@${kt.roundToInt().toString().padStart(2, '0')}"
}

/** "BKN045 TCU", or "SKC". */
internal fun cloudText(c: WxCell): String =
    if (c.cover == WxCover.NONE) "SKC"
    else c.cover.metar + three((c.baseFt / 100).toInt()) + if (c.towering) " TCU" else ""

/** WeatherGen's `format-precipitation`: only inclement weather rains, and it snows below freezing. */
internal fun precipText(c: WxCell): String =
    if (c.type != WxType.INCLEMENT) "None" else if (c.tempC > 0) "Rain" else "Snow"

internal fun altLabel(ft: Int): String = if (ft == 0) "SFC" else "${ft / 1000}k"

// ------------------------------------------------------------------------------------------------ on the map

/** What the colours mean, over the top-left corner: the key for the chosen display, and what the overlay shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(modifier: Modifier) {
    val d = WxGenLook.display
    val o = WxGenLook.overlay
    if (d == WxDisplay.NONE && o == WxOverlay.NONE) return
    Column(
        modifier.padding(10.dp).widthIn(max = 230.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Hud.Bg.copy(alpha = 0.84f))
            .border(1.dp, Hud.Outline.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (d) {
            WxDisplay.TYPE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in listOf(WxType.SUNNY, WxType.FAIR, WxType.POOR, WxType.INCLEMENT)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(typeSwatch(t)))
                        Spacer(Modifier.width(5.dp))
                        Text(t.label, color = Hud.Text, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            WxDisplay.PRESSURE -> Ramp(
                (0..17).map { Color(WxColors.pressure(28.5 + it * 0.1)) },
                listOf(28.5, 29.3, 29.9, 30.2).map { pressureText(it, WxGenLook.mb) },
                if (WxGenLook.mb) "Pressure, mb" else "Pressure, inHg",
            )
            WxDisplay.TEMPERATURE -> Ramp(
                (0..8).map { Color(WxColors.temperature(it * 5.0)) },
                listOf("0", "20", "40"),
                "Temperature, °C",
            )
            WxDisplay.NONE -> {}
        }
        if (o != WxOverlay.NONE) {
            Text(
                when (o) {
                    WxOverlay.WIND -> "Wind at " + WxDefaults.WIND_ALTS[WxGenLook.windLevel].let { if (it == 0) "the surface" else group(it) + " ft" }
                    WxOverlay.COVER -> "Cloud cover: FEW ◔  SCT ◑  BKN ◕  OVC ●, ▲ towering"
                    WxOverlay.BASE -> "Cloud base, hundreds of feet"
                    WxOverlay.VISIBILITY -> "Visibility, km"
                    WxOverlay.PRESSURE -> if (WxGenLook.mb) "Pressure, mb" else "Pressure, inHg"
                    WxOverlay.TEMPERATURE -> "Temperature, °C"
                    WxOverlay.NONE -> ""
                },
                color = Hud.TextDim, fontSize = 10.5.sp, lineHeight = 13.sp,
            )
        }
    }
}

internal fun group(v: Int): String {
    val s = v.toString()
    return if (s.length <= 3) s else s.dropLast(3) + "," + s.takeLast(3)
}

@Composable
private fun Ramp(colours: List<Color>, labels: List<String>, title: String) {
    Column(Modifier.width(190.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Hud.TextDim, fontSize = 10.5.sp)
        Box(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(2.dp)).background(Brush.horizontalGradient(colours)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (l in labels) Text(l, color = Hud.TextFaint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun Banner(modifier: Modifier, text: String, action: String, onAction: () -> Unit) {
    Row(
        modifier.padding(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Hud.Bg.copy(alpha = 0.9f))
            .border(1.dp, Hud.Amber.copy(alpha = 0.8f), RoundedCornerShape(5.dp))
            .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Hud.Text, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(10.dp))
        EditorChip(action, selected = true, onClick = onAction)
    }
}

/** One line for the cell under the mouse: where, what, and the numbers a pilot reads first. */
@Composable
private fun HoverCard(modifier: Modifier, xy: Pair<Int, Int>, c: WxCell) {
    Text(
        "${xy.first},${xy.second}  ${c.type.label}  ${pressureText(c.pressureInHg, WxGenLook.mb)}  ${c.tempC.roundToInt()}°C  " +
            "${windText(c.windDeg[0], c.windKt[0])}kt  ${cloudText(c)}  ${visText(c.visKm)} km",
        modifier.padding(10.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Hud.Bg.copy(alpha = 0.88f))
            .border(1.dp, Hud.Outline.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        color = Hud.Text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
    )
}
