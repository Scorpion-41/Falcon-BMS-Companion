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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.min

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
    overlay: DrawScope.(MapProjection) -> Unit = {},
) {
    val bmp by produceState<Bitmap?>(null, imagePath) { value = imagePath?.let { Repo.bitmap(it) } }
    // Callers pass fresh lambdas on every recomposition (live data); keying the gesture detectors on them
    // would restart detection mid-tap, so read the latest callbacks through state instead.
    val tapCb = androidx.compose.runtime.rememberUpdatedState(onTap)
    val gestureCb = androidx.compose.runtime.rememberUpdatedState(onUserGesture)
    BoxWithConstraints(modifier.clipToBounds().background(Hud.Bg)) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        fun proj(): MapProjection {
            val side = min(w, h) * state.scale
            return MapProjection((w - side) / 2 + state.panX, (h - side) / 2 + state.panY, side, sizeFt, state.scale)
        }
        if (!state.initialized && focus != null && w > 0) {
            state.scale = focusScale
            val side = min(w, h) * focusScale
            state.panX = -((focus.second / sizeFt) * side - side / 2).toFloat()
            state.panY = -(((1 - focus.first / sizeFt)) * side - side / 2).toFloat()
            state.initialized = true
        }
        if (!state.initialized && focus == null && fillWidth && w > h * 1.15f && h > 0) {
            state.scale = (w / h).coerceIn(1f, maxScale)
            state.initialized = true
        }
        state.pendingFocus?.let { (fx, fy, fs) ->
            if (w > 0) {
                val sc = fs.coerceIn(1f, maxScale)
                val side = min(w, h) * sc
                state.scale = sc
                state.panX = -((fy / sizeFt) * side - side / 2).toFloat()
                state.panY = -(((1 - fx / sizeFt)) * side - side / 2).toFloat()
                state.initialized = true
                state.pendingFocus = null
            }
        }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(sizeFt, w, h) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        gestureCb.value?.invoke()
                        val newScale = (state.scale * zoom).coerceIn(1f, maxScale)
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
                        onDoubleTap = { p ->
                            val newScale = (state.scale * 2f).coerceAtMost(maxScale)
                            val c = Offset(p.x - w / 2, p.y - h / 2)
                            val f = newScale / state.scale
                            state.panX = c.x - (c.x - state.panX) * f
                            state.panY = c.y - (c.y - state.panY) * f
                            state.scale = newScale
                        },
                        onTap = { p -> tapCb.value?.let { cb -> val pr = proj(); val (x, y) = pr.toTheater(p); cb(x, y, pr) } },
                    )
                },
        ) {
            val pr = proj()
            bmp?.let { b ->
                drawImage(
                    b.asImageBitmap(),
                    dstOffset = IntOffset(pr.left.toInt(), pr.top.toInt()),
                    dstSize = IntSize(pr.side.toInt(), pr.side.toInt()),
                )
            }
            overlay(pr)
        }
    }
}
