package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import com.bmscompanion.app.data.airfield.AfFeature
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.ui.components.AirfieldChart
import com.bmscompanion.app.ui.components.ChartInks
import com.bmscompanion.app.ui.components.ChartState
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.MapState
import com.bmscompanion.app.ui.components.TheaterMap
import com.bmscompanion.app.ui.components.ZoomMath
import com.bmscompanion.app.ui.screens.wdp.CardMap
import com.bmscompanion.app.ui.screens.wdp.CardMapView
import com.bmscompanion.app.ui.screens.wdp.WdpZoom
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * `--zoomtest out.txt`: zooming is gradual and holds the point under the cursor, everywhere (ZoomMath). Headless, reads
 * nothing of BMS. The shared wheel arithmetic for a notch, a quarter notch, three notches at once and a browser's pixel
 * deltas; then the theater map (the PC and browser copy, which is also the Planner's Map page and the Weather tab's
 * map), the DataCard's map, a ground chart and the Planner's page zoom, each driven by synthetic scroll events, keys,
 * a double click and two-finger pinches in an [ImageComposeScene], with the point under the cursor (or between the
 * fingers) measured after every frame of the glide.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
object ZoomTest {
    private const val TOL_PX = 1f

    fun run(): String = buildString {
        var fails = 0
        fun check(name: String, ok: Boolean, detail: String = "") {
            appendLine((if (ok) "PASS  " else "FAIL  ") + name + if (detail.isNotEmpty()) "  ($detail)" else "")
            if (!ok) fails++
        }
        fun near(a: Float, b: Float, rel: Float = 0.002f) = abs(a - b) <= rel * max(abs(a), abs(b))
        org.jetbrains.skia.Surface.makeRasterN32Premul(1, 1).close()
        val unit0 = ZoomMath.wheelUnit
        try {
            // ------------------------------------------------------------------------------------- the arithmetic
            appendLine("== ZoomMath")
            ZoomMath.wheelUnit = { 1f }
            check("one notch away (+1) zooms out by 1/1.12", near(ZoomMath.wheelFactor(1f), 1f / 1.12f), "${ZoomMath.wheelFactor(1f)}")
            check("one notch towards (-1) zooms in by 1.12", near(ZoomMath.wheelFactor(-1f), 1.12f), "${ZoomMath.wheelFactor(-1f)}")
            val quarter = ZoomMath.wheelFactor(0.25f)
            check("a quarter notch (0.25) is a quarter of the zoom", near(quarter.pow(4), 1f / 1.12f), "x$quarter, four of them x${quarter.pow(4)}")
            check("three notches in one event (3) are held to 0.8", near(ZoomMath.wheelFactor(3f), 0.8f), "${ZoomMath.wheelFactor(3f)}")
            check("three notches the other way (-3) are held to 1.25", near(ZoomMath.wheelFactor(-3f), 1.25f), "${ZoomMath.wheelFactor(-3f)}")
            check("nothing and NaN zoom nothing", ZoomMath.wheelFactor(0f) == 1f && ZoomMath.wheelFactor(Float.NaN) == 1f)
            check("browser pixels: 120 px at 100 a notch is 1.2 notches", near(ZoomMath.wheelFactor(120f, 100f), 1.12f.pow(-1.2f)), "${ZoomMath.wheelFactor(120f, 100f)}")
            check("browser pixels: a touchpad's 25 x 4 px make one notch", near((1..25).fold(1f) { a, _ -> a * ZoomMath.wheelFactor(4f, 100f) }, 1f / 1.12f))
            check("browser lines: Firefox's 3 lines at 3 a notch is one notch", near(ZoomMath.wheelFactor(-3f, 3f), 1.12f))
            val k = ZoomMath.keep(Offset(120f, -40f), Offset(30f, 10f), 1.7f)
            check("keep: the anchor's content stays put", abs((Offset(120f, -40f) - k).x / 1.7f - (120f - 30f)) < 1e-3f)

            // ------------------------------------------------------------------------------------- the theater map
            appendLine("== Theater map (PC and browser copy; the Planner's Map page, the Weather tab)")
            for (cover in listOf(false, true)) {
                val label = if (cover) "cover box" else "fitted"
                val st = MapState().apply { scale = 3f; panX = 100f; panY = -50f; initialized = true }
                var proj: MapProjection? = null
                val rig = Rig(800, 600) {
                    TheaterMap(null, 1_000_000.0, Modifier.fillMaxSize(), st, maxScale = 180f, landmarks = false, zoomButtons = false, fillBox = cover) { pr -> proj = pr }
                }
                try {
                    rig.frames(4)
                    val p = Offset(520f, 230f)
                    // the anchor error: the theater point that was under the cursor, where it is now drawn
                    fun underCursor(): Pair<Double, Double> = proj!!.toTheater(p)
                    fun err(q: Pair<Double, Double>, at: Offset = p) = (proj!!.toScreen(q.first, q.second) - at).getDistance()
                    fun glide(name: String, want: Float, act: () -> Unit) {
                        val q = underCursor()
                        val s0 = st.scale
                        act()
                        var worst = 0f
                        var steps = 0
                        var biggest = 1f
                        var last = st.scale
                        repeat(40) {
                            rig.frames(1)
                            worst = max(worst, err(q))
                            if (st.scale != last) { steps++; biggest = max(biggest, max(st.scale / last, last / st.scale)); last = st.scale }
                        }
                        check("$label: $name zooms x%.4f".format(want), near(st.scale / s0, want), "x%.4f".format(st.scale / s0))
                        check("$label: $name keeps the point under the cursor (%.2f px at worst, every frame)".format(worst), worst <= TOL_PX)
                        if (want != 1f) check("$label: $name glides ($steps frames, at most x%.3f a frame)".format(biggest), steps >= 3 && biggest <= 1.25f)
                    }
                    glide("one notch away", 1f / 1.12f) { rig.wheel(p, 1f) }
                    glide("one notch towards", 1.12f) { rig.wheel(p, -1f) }
                    glide("four quarter notches at once", 1f / 1.12f) { repeat(4) { rig.wheel(p, 0.25f) } }
                    glide("three notches in one event", 0.8f) { rig.wheel(p, 3f) }
                    glide("a touchpad's 25 small deltas", 1.12f) { repeat(25) { rig.wheel(p, -0.04f); rig.frames(1) } }
                    ZoomMath.wheelUnit = { 100f }
                    glide("a browser's 120 px", 1.12f.pow(-1.2f)) { rig.wheel(p, 120f) }
                    glide("a browser's -100 px", 1.12f) { rig.wheel(p, -100f) }
                    ZoomMath.wheelUnit = { 1f }
                    if (cover) {
                        // the stored pan past the edge the drawing holds it to: the jump the cover box used to make
                        st.scale = 1.2f; st.panX = 5000f; st.panY = -4000f
                        rig.frames(3)
                        glide("a notch with the pan held at the edge", 1.12f) { rig.wheel(p, -1f) }
                        st.scale = 3f; st.panX = 0f; st.panY = 0f
                        rig.frames(3)
                    }
                    // the + key (WdpMapPage's own keys go through MapState.zoomBy too): about the middle
                    run {
                        val mid = Offset(400f, 300f)
                        val q = proj!!.toTheater(mid)
                        val s0 = st.scale
                        st.zoomBy(ZoomMath.BUTTON)
                        var worst = 0f
                        repeat(40) { rig.frames(1); worst = max(worst, err(q, mid)) }
                        check("$label: the + key zooms x1.5 about the middle", near(st.scale / s0, 1.5f) && worst <= TOL_PX, "x%.4f, %.2f px".format(st.scale / s0, worst))
                    }
                    glide("a double click", 2f) { rig.doubleClick(p) }
                    // held at the limit: the glide stops there
                    st.scale = 175f; rig.frames(2)
                    rig.wheel(p, -3f); rig.frames(40)
                    check("$label: zoom stops at the map's limit", st.scale == 180f, "${st.scale}")
                    st.scale = 3f; st.panX = 0f; st.panY = 0f; rig.frames(3)
                    // a pinch: fingers 120 px apart about the cursor opening to 300 px, then one lifts and the other drags
                    run {
                        val q = underCursor()
                        val s0 = st.scale
                        var worst = 0f
                        var biggest = 1f
                        var last = st.scale
                        val pinch = (0..12).map { i -> val d = 60f + 90f * i / 12f; listOf(p - Offset(d, 0f), p + Offset(d, 0f)) }
                        rig.touch(listOf(pinch[0].take(1)), up = false)
                        for (f in pinch) {
                            rig.touch(listOf(f), up = false)
                            worst = max(worst, err(q))
                            biggest = max(biggest, max(st.scale / last, last / st.scale)); last = st.scale
                        }
                        check("$label: a pinch zooms in (x%.3f) about the fingers (%.2f px at worst)".format(st.scale / s0, worst), st.scale / s0 > 1.8f && worst <= TOL_PX)
                        check("$label: a pinch is continuous (at most x%.3f an event)".format(biggest), biggest <= 1.25f)
                        // the second finger lifts: no jump, and the first then drags the map with it
                        val sLift = st.scale
                        val f0 = pinch.last()[0]
                        val q0 = proj!!.toTheater(f0)
                        rig.lift(pinch.last(), 1)
                        val jump = err(q0, f0)
                        check("$label: a finger lifting moves nothing (x%.4f, %.2f px)".format(st.scale / sLift, jump), st.scale == sLift && jump <= TOL_PX)
                        var drag = f0
                        repeat(6) { drag += Offset(9f, 4f); rig.touch(listOf(listOf(drag)), up = false) }
                        val follow = err(q0, drag)
                        check("$label: the other finger then drags the map (%.2f px off the finger)".format(follow), st.scale == sLift && follow <= TOL_PX)
                        rig.release(listOf(drag))
                    }
                } catch (e: Throwable) {
                    check("$label: the theater map ran", false, "${e::class.simpleName}: ${e.message}")
                    e.stackTrace.filter { it.className.startsWith("com.bmscompanion") }.take(6).forEach { appendLine("       at $it") }
                } finally { rig.close() }
            }

            // ------------------------------------------------------------------------------------- the DataCard's map
            appendLine("== DataCard map")
            run {
                val view = CardMapView().apply { zoom = 2f; pan = Offset(-200f, -200f) }
                val rig = Rig(400, 400) { Box(Modifier.fillMaxSize()) { CardMap(null, null, false, emptyList(), view) } }
                try {
                    rig.frames(3)
                    val p = Offset(150f, 260f)
                    fun glide(name: String, want: Float, act: () -> Unit) {
                        val u = (p - view.pan) / view.zoom
                        val z0 = view.zoom
                        act()
                        var worst = 0f
                        repeat(40) { rig.frames(1); worst = max(worst, (view.pan + u * view.zoom - p).getDistance()) }
                        check("DataCard: $name zooms x%.4f about the cursor (%.2f px at worst)".format(want, worst), near(view.zoom / z0, want) && worst <= TOL_PX, "x%.4f".format(view.zoom / z0))
                    }
                    glide("one notch towards", 1.12f) { rig.wheel(p, -1f) }
                    glide("one notch away", 1f / 1.12f) { rig.wheel(p, 1f) }
                    ZoomMath.wheelUnit = { 100f }
                    glide("a browser's -120 px", 1.12f.pow(1.2f)) { rig.wheel(p, -120f) }
                    ZoomMath.wheelUnit = { 1f }
                } catch (e: Throwable) {
                    check("DataCard map ran", false, "${e::class.simpleName}: ${e.message}")
                } finally { rig.close() }
            }

            // ------------------------------------------------------------------------------------- a ground chart
            appendLine("== Ground chart")
            run {
                val field = Airfield(id = 1, name = "Test", features = listOf(AfFeature(e = -4000.0, n = -1500.0), AfFeature(e = 4000.0, n = 1500.0)))
                val cs = ChartState().apply { scale = 2f; panX = 40f; panY = -30f }
                val w = 700f; val h = 500f
                val rig = Rig(w.toInt(), h.toInt()) { AirfieldChart(field, null, ChartInks.day, Modifier.fillMaxSize(), state = cs) }
                try {
                    rig.frames(4)
                    val p = Offset(480f, 180f)
                    val c = Offset(w / 2, h / 2)
                    fun glide(name: String, want: Float, at: Offset, act: () -> Unit) {
                        val d = (at - c - Offset(cs.panX, cs.panY)) / cs.scale
                        val s0 = cs.scale
                        act()
                        var worst = 0f
                        repeat(40) { rig.frames(1); worst = max(worst, (c + Offset(cs.panX, cs.panY) + d * cs.scale - at).getDistance()) }
                        check("ground chart: $name zooms x%.4f about its point (%.2f px at worst)".format(want, worst), near(cs.scale / s0, want) && worst <= TOL_PX, "x%.4f".format(cs.scale / s0))
                    }
                    glide("one notch towards", 1.12f, p) { rig.wheel(p, -1f) }
                    glide("a touchpad's 20 small deltas", 1f / 1.12f, p) { repeat(20) { rig.wheel(p, 0.05f); rig.frames(1) } }
                    glide("the + key", 1.5f, c) { cs.zoomBy(ZoomMath.BUTTON) }
                    glide("a double click", 2f, p) { rig.doubleClick(p) }
                } catch (e: Throwable) {
                    check("ground chart ran", false, "${e::class.simpleName}: ${e.message}")
                } finally { rig.close() }
            }

            // ------------------------------------------------------------------------------------- the Planner's page zoom
            appendLine("== Planner page zoom (a finger's double tap)")
            run {
                val z = WdpZoom()
                val q = Offset(300f, 400f)
                var worst = 0f
                var frames = 0
                var done = false
                val rig = Rig(100, 100) {
                    LaunchedEffect(Unit) { z.glideTo(q, 2f, 1000f, 1000f, 1000f, 1000f); done = true }
                }
                try {
                    repeat(30) {
                        rig.frames(1)
                        z.live?.let { (s, a) -> frames++; worst = max(worst, (a + q * s - q).getDistance()) }
                    }
                    check("page zoom glides ($frames frames) holding the tapped point (%.2f px at worst)".format(worst), done && frames >= 5 && worst <= TOL_PX)
                    check("page zoom lands at x2 where zoomAt puts it", z.zoom == 2f && z.live == null && (z.placed(1000f, 1000f, 2000f, 2000f) - Offset(-300f, -400f)).getDistance() < 0.01f,
                        "zoom ${z.zoom}, corner ${z.placed(1000f, 1000f, 2000f, 2000f)}")
                } catch (e: Throwable) {
                    check("page zoom ran", false, "${e::class.simpleName}: ${e.message}")
                } finally { rig.close() }
            }
        } finally { ZoomMath.wheelUnit = unit0 }
        appendLine(if (fails == 0) "ALL PASS" else "$fails FAILED")
    }

    /** A headless scene with a clock that moves 16 ms a frame, and a mouse and fingers to drive it. */
    private class Rig(w: Int, h: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(w, h, Density(1f), content = content)
        var t = 0L
        var ms = 1_000L

        fun frames(n: Int) { repeat(n) { scene.render(t).close(); t += 16_000_000L } }

        fun wheel(at: Offset, dy: Float) {
            ms += 8
            scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms)
            scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(0f, dy), timeMillis = ms)
        }

        fun doubleClick(at: Offset) {
            ms += 500
            repeat(2) {
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                frames(1); ms += 40
                scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ms, buttons = PointerButtons(), button = PointerButton.Primary)
                frames(1); ms += 60
            }
        }

        private var down = 0

        /** fingers at these places (pressed); a new finger is a press */
        fun touch(steps: List<List<Offset>>, up: Boolean) {
            for (f in steps) {
                ms += 16
                val type = if (f.size > down) PointerEventType.Press else PointerEventType.Move
                scene.sendPointerEvent(type, f.mapIndexed { k, p -> ComposeScenePointer(PointerId(k.toLong()), p, pressed = true, type = PointerType.Touch) }, timeMillis = ms)
                down = f.size
                frames(1)
            }
            if (up) release(steps.last())
        }

        /** finger [which] of [at] lifts; the others stay down where they are */
        fun lift(at: List<Offset>, which: Int) {
            ms += 16
            scene.sendPointerEvent(PointerEventType.Release, at.mapIndexed { k, p -> ComposeScenePointer(PointerId(k.toLong()), p, pressed = k != which, type = PointerType.Touch) }, timeMillis = ms)
            down = at.size - 1
            frames(1)
        }

        fun release(at: List<Offset>) {
            ms += 16
            scene.sendPointerEvent(PointerEventType.Release, at.mapIndexed { k, p -> ComposeScenePointer(PointerId(k.toLong()), p, pressed = false, type = PointerType.Touch) }, timeMillis = ms)
            down = 0
            frames(2)
            ms += 400
        }

        fun close() = scene.close()
    }
}
