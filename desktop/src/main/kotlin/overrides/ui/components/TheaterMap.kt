package com.bmscompanion.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.Kneeboard
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// PC version of app/.../ui/components/TheaterMap.kt: identical API and drawing, plus mouse-wheel zoom around the cursor.

/** Maps theater coordinates (x = north ft, y = east ft) to screen space for the current pan/zoom. */
class MapProjection(val left: Float, val top: Float, val side: Float, val sizeFt: Double, val scale: Float) {
    fun toScreen(xFt: Double, yFt: Double) = Offset(left + (yFt / sizeFt * side).toFloat(), top + ((1 - xFt / sizeFt) * side).toFloat())
    fun toTheater(p: Offset): Pair<Double, Double> = Pair((1 - (p.y - top) / side) * sizeFt, ((p.x - left) / side) * sizeFt)
    /** screen pixels per nautical mile */
    val pxPerNm get() = (side / (sizeFt / 6076.12)).toFloat()
}

class MapState {
    var scale by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var initialized by mutableStateOf(false)
    /** (xFt, yFt, scale) to centre on at the next layout */
    var pendingFocus by mutableStateOf<Triple<Double, Double, Float>?>(null)
    fun flyTo(xFt: Double, yFt: Double, scale: Float) { pendingFocus = Triple(xFt, yFt, scale) }
}

@Composable
fun rememberMapState() = remember { MapState() }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TheaterMap(
    imagePath: String?,
    sizeFt: Double,
    modifier: Modifier = Modifier,
    state: MapState = rememberMapState(),
    maxScale: Float = 10f,
    focus: Pair<Double, Double>? = null,
    focusScale: Float = 4f,
    /** start zoomed so the (square) map fills the width of a wide viewport instead of leaving side bands */
    fillWidth: Boolean = false,
    onTap: ((xFt: Double, yFt: Double, proj: MapProjection) -> Unit)? = null,
    /** called when the user pans or zooms (e.g. to stop following a moving aircraft) */
    onUserGesture: (() -> Unit)? = null,
    /** borders, provinces, country/region names and towns (MapLook settings) */
    landmarks: Boolean = true,
    /** + / − buttons (bottom right, above a map's own corner button) */
    zoomButtons: Boolean = true,
    overlay: DrawScope.(MapProjection) -> Unit = {},
) {
    // map style overview + zoom-level tiles, and the landmark layers (MapBase.kt)
    val base = rememberMapBase(imagePath)
    // Read during composition, not only inside the draw: a map that nobody touches — the little one on an
    // airfield page — stayed black until a finger brushed it, because the tiles arrive after the first frame and
    // nothing asked to be drawn again. Counting them here is what asks.
    @Suppress("UNUSED_VARIABLE") val tilesReady = base.images.size
    val geo = if (landmarks) rememberGeo(imagePath, sizeFt) else null
    val landmarkText = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 192)
    val legacy by produceState<Bitmap?>(null, imagePath) { value = imagePath?.takeIf { mapIdOf(it) == null }?.let { Repo.bitmap(it) } }
    // Callers pass fresh lambdas on every recomposition (live data); keying the gesture detectors on them
    // would restart detection mid-tap, so read the latest callbacks through state instead.
    val tapCb = androidx.compose.runtime.rememberUpdatedState(onTap)
    val gestureCb = androidx.compose.runtime.rememberUpdatedState(onUserGesture)
    BoxWithConstraints(modifier.clipToBounds().background(Hud.Bg)) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        // A board is tall and a theater is square: fitted inside the box, a third of the board would be blank paper.
        // On a board the map covers its box instead, and the smallest zoom becomes the fit rather than the cover, so
        // zooming out still shows the whole theater.
        val cover = if (Kneeboard.on) max(w, h) else min(w, h)
        // A board is all map: the square never shrinks below the page, or the widest zoom step leaves a band of bare
        // board down one side. (It used to zoom out to the whole theater, which is what put that band there.)
        val minScale = 1f
        // Nothing but map on a board. The theater is drawn as a square, so panning towards its edge — which is where
        // a mission near the top of the map puts it — slid the square off the page and left a band of bare board
        // above it. The pan is held inside the square whenever the square is big enough to cover the page.
        // The pan is held inside the square so the square always covers the page. This is applied when the
        // projection is worked out, never written back into the state during composition: following the jet moves
        // the pan four times a second, and a clamp that writes state each time it does invalidates the composition,
        // which then re-clamps — the map and the bare board alternated down one edge several times a second.
        fun proj(): MapProjection {
            val side = cover * state.scale
            var px = state.panX
            var py = state.panY
            if (Kneeboard.on && w > 0f && h > 0f) {
                px = px.coerceIn(-((side - w) / 2f).coerceAtLeast(0f), ((side - w) / 2f).coerceAtLeast(0f))
                py = py.coerceIn(-((side - h) / 2f).coerceAtLeast(0f), ((side - h) / 2f).coerceAtLeast(0f))
            }
            return MapProjection((w - side) / 2 + px, (h - side) / 2 + py, side, sizeFt, state.scale)
        }
        /** Zooms by [factor] keeping the point under [p] (canvas pixels) in place. */
        fun zoomAt(p: Offset, factor: Float) {
            val newScale = (state.scale * factor).coerceIn(minScale, maxScale)
            val c = Offset(p.x - w / 2, p.y - h / 2)
            val f = newScale / state.scale
            state.panX = c.x - (c.x - state.panX) * f
            state.panY = c.y - (c.y - state.panY) * f
            state.scale = newScale
            state.initialized = true
        }
        if (!state.initialized && focus != null && w > 0) {
            state.scale = focusScale
            val side = cover * focusScale
            state.panX = -((focus.second / sizeFt) * side - side / 2).toFloat()
            state.panY = -(((1 - focus.first / sizeFt)) * side - side / 2).toFloat()
            state.initialized = true
        }
        if (!state.initialized && focus == null && fillWidth && w > h * 1.15f && h > 0) {
            state.scale = (w / h).coerceIn(minScale, maxScale)
            state.initialized = true
        }
        state.pendingFocus?.let { (fx, fy, fs) ->
            if (w > 0) {
                val sc = fs.coerceIn(minScale, maxScale)
                val side = cover * sc
                state.scale = sc
                state.panX = -((fy / sizeFt) * side - side / 2).toFloat()
                state.panY = -(((1 - fx / sizeFt)) * side - side / 2).toFloat()
                state.initialized = true
                state.pendingFocus = null
            }
        }
        Canvas(
            Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll) { e ->
                    val ch = e.changes.firstOrNull() ?: return@onPointerEvent
                    val dy = ch.scrollDelta.y
                    if (dy != 0f) {
                        gestureCb.value?.invoke()
                        zoomAt(ch.position, 1.25f.pow(-dy))
                        ch.consume()
                    }
                }
                .pointerInput(sizeFt, w, h) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        gestureCb.value?.invoke()
                        val newScale = (state.scale * zoom).coerceIn(minScale, maxScale)
                        val c = Offset(centroid.x - w / 2, centroid.y - h / 2)
                        val f = newScale / state.scale
                        state.panX = c.x - (c.x - (state.panX + pan.x)) * f
                        state.panY = c.y - (c.y - (state.panY + pan.y)) * f
                        state.scale = newScale
                        state.initialized = true
                    }
                }
                .pointerInput(sizeFt, w, h) {
                    detectTapGestures(
                        onDoubleTap = { p -> zoomAt(p, 2f) },
                        onTap = { p -> tapCb.value?.let { cb -> val pr = proj(); val (x, y) = pr.toTheater(p); cb(x, y, pr) } },
                    )
                },
        ) {
            val pr = proj()
            drawMapBase(pr, base, legacy?.asImageBitmap())
            geo?.let { drawLandmarks(pr, it, landmarkText) }
            overlay(pr)
        }
        if (zoomButtons) MapZoomButtons(
            onZoom = { factor -> gestureCb.value?.invoke(); zoomAt(Offset(w / 2, h / 2), factor) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 17.dp, bottom = 70.dp),
        )
    }
}
