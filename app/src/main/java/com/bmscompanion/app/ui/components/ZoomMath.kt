package com.bmscompanion.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * How every zoomable view zooms, in one place: the theater map (both copies), the Planner's Map page, the DataCard's
 * map, the ground charts, the chart viewer and the Planner's page zoom.
 *
 * **A wheel is measured, not counted.** One notch is [NOTCH] (12 %), and a scroll delta is that many notches of
 * [wheelUnit]: what one notch reports on this platform. Compose Desktop hands over AWT's precise wheel rotation (±1 a
 * notch, a fraction from a high-resolution wheel or a touchpad), Android ±1, and the browser the DOM event's raw
 * `deltaY` — about 100 pixels a notch in Chrome and Edge, 3 lines in Firefox — so the browser sets [wheelUnit] from the
 * event's `deltaMode` (web `Main.kt`). A touchpad's run of small deltas therefore zooms in proportion, never a step per
 * event, and no single event moves more than [EVENT_MIN]..[EVENT_MAX]. Before this the PC map took 25 % a notch, the
 * browser's took the whole range in one, and the charts a fixed step for every event, which a touchpad sends dozens of.
 *
 * **A step glides** ([ZoomGlide]): the wheel, the +/− keys ([BUTTON]) and a double tap ([DOUBLE_TAP]) ask for a
 * factor about a point, and it is applied over a few frames, eased, always about that point — so the point under the
 * cursor stays under it the whole way, and notches that arrive while it glides add up rather than restart it. A pinch
 * or a drag follows the fingers exactly and stops a glide in flight.
 */
object ZoomMath {
    /** one wheel notch */
    const val NOTCH = 1.12f
    /** the + and − keys */
    const val BUTTON = 1.5f
    /** a double tap or double click */
    const val DOUBLE_TAP = 2f
    /** no single scroll event zooms past these, however large its delta */
    const val EVENT_MIN = 0.8f
    const val EVENT_MAX = 1.25f
    /** how quickly a glide closes on its target: about 95 % in three of these */
    const val WHEEL_TAU_MS = 40f
    const val STEP_TAU_MS = 55f

    /**
     * What one wheel notch reports as `scrollDelta.y` here: 1 on the PC and Android, set by the browser version from
     * the wheel event's `deltaMode` (100 for pixels, 3 for lines, 1 for pages).
     */
    var wheelUnit: () -> Float = { 1f }

    /** The zoom one scroll event asks for: below 1 for a delta away from the screen (positive y), above 1 towards it. */
    fun wheelFactor(dy: Float, unit: Float = wheelUnit()): Float {
        if (dy == 0f || !dy.isFinite()) return 1f
        val notches = dy / unit.coerceAtLeast(1e-3f)
        return NOTCH.pow(-notches).coerceIn(EVENT_MIN, EVENT_MAX)
    }

    /** A pinch's factor for one event, with a glitch (NaN, zero, a wild jump as a finger lands) taken as no zoom. */
    fun pinch(zoom: Float): Float = if (zoom.isFinite() && zoom > 0f) zoom.coerceIn(0.5f, 2f) else 1f

    /**
     * Where the pan goes when the view is scaled by [f] about [anchor], both measured from the point the view scales
     * about (its middle for most views, its corner for the DataCard's): the point under [anchor] stays under it.
     */
    fun keep(anchor: Offset, pan: Offset, f: Float): Offset = anchor - (anchor - pan) * f
}

/**
 * A zoom that glides: [by] asks for a factor about a point, and over the next frames [apply] is handed pieces of it
 * (each about the latest point asked about) until it is all applied. [apply] zooms the view by the factor it is given,
 * held to the view's own limits, and answers the factor it really applied; a glide that runs into a limit ends there
 * rather than pushing on. [stop] drops what is left (a finger has taken over); [finish] applies it at once.
 */
class ZoomGlide internal constructor(private val scope: CoroutineScope, private val apply: State<(Float, Offset) -> Float>) {
    /** the natural log of the factor still to apply */
    private var left = 0f
    private var at = Offset.Zero
    private var tau = ZoomMath.WHEEL_TAU_MS
    private var job: Job? = null

    val gliding: Boolean get() = job?.isActive == true

    fun by(factor: Float, anchor: Offset, tauMs: Float = ZoomMath.STEP_TAU_MS) {
        if (!factor.isFinite() || factor <= 0f || factor == 1f) return
        left += ln(factor)
        at = anchor
        tau = tauMs
        if (job?.isActive != true) job = scope.launch { run() }
    }

    fun stop() { left = 0f; job?.cancel(); job = null }

    fun finish() {
        val l = left
        stop()
        if (l != 0f) apply.value(exp(l), at)
    }

    private suspend fun run() {
        var last = -1L
        while (abs(left) > 1e-4f) {
            val now = withFrameNanos { it }
            val dt = if (last < 0L) 16f else ((now - last) / 1_000_000f).coerceIn(0f, 100f)
            last = now
            var step = left * (1f - exp(-dt / tau))
            if (abs(left - step) < 2e-3f) step = left
            left -= step
            val got = apply.value(exp(step), at)
            // held at the view's limit: the rest of this glide would only push against it
            if (abs(ln(got.coerceAtLeast(1e-6f)) - step) > 1e-4f) left = 0f
        }
        left = 0f
    }
}

/** A [ZoomGlide] for this composition; [apply] is read fresh each frame, so it may close over the latest layout. */
@Composable
fun rememberZoomGlide(apply: (factor: Float, anchor: Offset) -> Float): ZoomGlide {
    val scope = rememberCoroutineScope()
    val cb = rememberUpdatedState(apply)
    return remember(scope) { ZoomGlide(scope, cb) }
}

/**
 * The mouse wheel (and a touchpad's two-finger scroll, and a browser's pinch on a touchpad, which arrives as a wheel)
 * zooming [glide] about the pointer by [ZoomMath.wheelFactor]. The scroll is taken, so nothing under the map scrolls.
 */
fun Modifier.wheelZoom(glide: ZoomGlide): Modifier = wheelZoom(glide) { f, at -> glide.by(f, at, ZoomMath.WHEEL_TAU_MS) }

/**
 * The same for a view that animates its own way (the chart viewer's springs): [onZoom] is handed each scroll event's
 * factor and the pointer. The pointer input restarts when [keys] change, so pass whatever [onZoom] closes over.
 */
fun Modifier.wheelZoom(vararg keys: Any?, onZoom: (factor: Float, at: Offset) -> Unit): Modifier = pointerInput(*keys) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            if (e.type != PointerEventType.Scroll) continue
            val ch = e.changes.firstOrNull() ?: continue
            val f = ZoomMath.wheelFactor(ch.scrollDelta.y)
            if (f == 1f) continue
            onZoom(f, ch.position)
            ch.consume()
        }
    }
}
