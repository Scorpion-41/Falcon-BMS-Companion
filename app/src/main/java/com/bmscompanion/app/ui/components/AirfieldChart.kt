package com.bmscompanion.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.airfield.AfNode
import com.bmscompanion.app.data.airfield.AfRoute
import com.bmscompanion.app.data.airfield.AfRunway
import com.bmscompanion.app.data.airfield.AfShip
import com.bmscompanion.app.data.airfield.AfSpot
import com.bmscompanion.app.data.airfield.AfType
import com.bmscompanion.app.data.airfield.Airfield
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The inks a ground chart is drawn in.
 *
 * Deliberately its own palette rather than the app's HUD skin: a chart is read head-down in a lit cockpit or off a
 * kneeboard, and the pilot picks day or night for it whatever the rest of the app is wearing. The buildings are
 * coloured by what they are, and the taxiway letters carry the colours of the real thing — black on yellow, which
 * is what a taxiway location sign looks like on the field.
 */
data class ChartInks(
    val ground: Color,
    val pavement: Color,
    val pavementEdge: Color,
    /** the painted line down the middle of a taxiway, which is what the pilot actually follows */
    val centreline: Color,
    val apron: Color,
    val runway: Color,
    val runwayMark: Color,
    val ink: Color,
    val dim: Color,
    /** the way the pilot has been told to taxi: deliberately not the yellow of a painted centre line */
    val route: Color,
    val you: Color,
    /** anything with a roof over it — a hardened shelter, a hangar bay — kept clearly off the pavement tone so a
     *  covered spot reads as covered even when it is only a few pixels across */
    val shelter: Color,
    val hangar: Color,
    /** a carrier's island: its own colour, because it is the one landmark on a deck of identical grey */
    val island: Color,
    val building: Color,
    val fuel: Color,
    val tower: Color,
    val signFill: Color,
    val signInk: Color,
    val accent: Color,
    val wingman: Color,
    val friendly: Color,
    val hostile: Color,
    val neutral: Color,
) {
    companion object {
        /** Daylight: a printed airfield diagram, dark ink on pale concrete. */
        val day = ChartInks(
            ground = Color(0xFFEFF1EE), pavement = Color(0xFFC2C7C4), pavementEdge = Color(0xFFA7ADAA), centreline = Color(0xFFC9A21A),
            apron = Color(0xFFCFD4D0), runway = Color(0xFF3A4247), runwayMark = Color(0xFFF2F4F1),
            ink = Color(0xFF161B1F), dim = Color(0xFF5D6A71), route = Color(0xFF0B74C4),
            you = Color(0xFF1B7A54),
            shelter = Color(0xFF86A08E), hangar = Color(0xFF8D7B63), island = Color(0xFF5B4C7A), building = Color(0xFF7E8B99),
            fuel = Color(0xFFB08928), tower = Color(0xFF9C4A63),
            signFill = Color(0xFFE8C22A), signInk = Color(0xFF1A1A12),
            accent = Color(0xFF1D5A88),
            wingman = Color(0xFF2E9E5B), friendly = Color(0xFF2D6FB5), hostile = Color(0xFFC0392B), neutral = Color(0xFF7A7F84),
        )

        /** Night: the same chart with the glare taken out, to sit beside the app's dark pages. */
        val night = ChartInks(
            ground = Color(0xFF0E1215), pavement = Color(0xFF333B41), pavementEdge = Color(0xFF454F56), centreline = Color(0xFFD8C24A),
            apron = Color(0xFF272E33), runway = Color(0xFF1C2329), runwayMark = Color(0xFFAFBCC2),
            ink = Color(0xFFE3E9E6), dim = Color(0xFF93A0A6), route = Color(0xFF3FC4F5),
            you = Color(0xFF4ECF98),
            shelter = Color(0xFF5E7164), hangar = Color(0xFF6B5B47), island = Color(0xFF8E79BE), building = Color(0xFF4A5663),
            fuel = Color(0xFF8A6D24), tower = Color(0xFFA85E76),
            signFill = Color(0xFFCBA61F), signInk = Color(0xFF14140E),
            accent = Color(0xFF74B4E2),
            wingman = Color(0xFF4ECF98), friendly = Color(0xFF6FA8DC), hostile = Color(0xFFE06055), neutral = Color(0xFF9AA3A9),
        )
    }
}

/** Another aircraft on the field, as the AWACS feed reports it. */
data class ChartTraffic(
    /** field feet, east and north of the field origin */
    val e: Double,
    val n: Double,
    val heading: Double?,
    val kind: TrafficKind,
    val label: String? = null,
)

enum class TrafficKind { WINGMAN, FRIENDLY, HOSTILE, NEUTRAL }

class ChartState {
    var scale by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)

    /**
     * How the zoom buttons zoom. The chart sets this, because only it knows where a point on the field lands on
     * the page — and a button that zooms on the middle of the bounding box walks you into bare grass, since that
     * is rarely where anything is.
     */
    internal var zoomHook: ((Float) -> Unit)? = null

    fun reset() { scale = 1f; panX = 0f; panY = 0f }
    fun zoomBy(factor: Float) {
        val hook = zoomHook
        if (hook != null) hook(factor) else scale = (scale * factor).coerceIn(1f, 40f)
    }
}

@Composable
fun rememberChartState() = remember { ChartState() }

/**
 * Where a point in field feet lands on the canvas, and back again.
 *
 * [rot] turns the whole chart so a chosen heading points up the page. On a board that is the jet's own heading, so
 * what the pilot sees ahead of them is at the top of the chart — the way you read a map while taxiing.
 */
private class ChartView(
    val cx: Float, val cy: Float, val midE: Double, val midN: Double, val k: Float, val rot: Double,
    /** drawing a carrier deck rather than an airbase — see [ink] */
    val deck: Boolean = false,
) {
    private val c = cos(rot)
    private val s = sin(rot)
    private fun de(e: Double, n: Double) = (e - midE) * c - (n - midN) * s
    private fun dn(e: Double, n: Double) = (e - midE) * s + (n - midN) * c
    fun x(e: Double, n: Double) = cx + (de(e, n) * k).toFloat()
    fun y(e: Double, n: Double) = cy - (dn(e, n) * k).toFloat()
    fun at(e: Double, n: Double) = Offset(x(e, n), y(e, n))
    fun at(node: AfNode) = at(node.e, node.n)
    /** a compass bearing, as an angle to rotate a symbol drawn pointing up the page */
    fun screenAngle(bearing: Double) = (bearing - rot * 180.0 / kotlin.math.PI).toFloat()
    fun worldOf(px: Float, py: Float): Pair<Double, Double> {
        val dx = (px - cx) / k
        val dy = (cy - py) / k
        return (midE + dx * c + dy * s) to (midN - dx * s + dy * c)
    }
    fun ft(v: Double) = (v * k).toFloat()

    /**
     * A line width, in feet, for something drawn on the ground — and a thinner one on a deck.
     *
     * An airbase is two miles across and a carrier is a fifth of a mile, but both fill the same page, so a lane
     * drawn a hundred feet wide is a thin ribbon on one and a third of the beam on the other. Every width and every
     * minimum on a deck is a fraction of the airfield's, which is what turns a carrier from a heap of overlapping
     * shapes into a ship with markings on it.
     *
     * [floor] is the least it may come to in pixels, so a line never disappears at the widest zoom.
     */
    fun ink(v: Double, floor: Float): Float =
        if (deck) max(floor * 0.4f, ft(v * 0.3)) else max(floor, ft(v))
}

private const val FT_PER_NM = 6076.12

/** The field's extent once it has been turned: how much ground fits across and down, and what sits in the middle. */
private class Fit(val spanX: Double, val spanY: Double, val midE: Double, val midN: Double)

/**
 * How much room to leave round what is drawn, in feet.
 *
 * A share of it rather than a fixed distance: 1,100 ft is a sensible margin round a two-mile airbase and three
 * times the length of a carrier, which drew a ship the size of a thumbnail in an empty sea.
 */
private fun padFor(spanX: Double, spanY: Double) = (max(spanX, spanY) * 0.08).coerceIn(120.0, 1100.0)

/**
 * The extent of [pts] seen at [rot] radians, padded, with the middle of it given back in field coordinates.
 */
private fun fitTo(pts: DoubleArray, rot: Double): Fit {
    val c = cos(rot)
    val s = sin(rot)
    var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
    var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    var i = 0
    while (i + 1 < pts.size) {
        val x = pts[i] * c - pts[i + 1] * s
        val y = pts[i] * s + pts[i + 1] * c
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
        i += 2
    }
    val cx = (minX + maxX) / 2
    val cy = (minY + maxY) / 2
    val pad = padFor(maxX - minX, maxY - minY)
    // back out of the turned frame, so the view can be told where to sit in the field's own coordinates
    return Fit(
        spanX = (maxX - minX) + 2 * pad,
        spanY = (maxY - minY) + 2 * pad,
        midE = cx * c + cy * s,
        midN = -cx * s + cy * c,
    )
}

/**
 * The heading to put up the page so the field fills a box of the given width-to-height ratio.
 *
 * Tried every two degrees over half a turn — a field turned 180 degrees fits exactly as well — and the angle that
 * gives the largest scale wins. On a kneeboard, which is far taller than it is wide, that stands a single-runway
 * field upright whatever its real heading, and typically doubles how large it is drawn.
 */
private fun bestFitHeading(pts: DoubleArray, aspect: Float): Double {
    if (pts.size < 4 || aspect <= 0f) return 0.0
    var best = 0.0
    var bestK = -1.0
    var deg = 0
    while (deg < 180) {
        val f = fitTo(pts, deg * kotlin.math.PI / 180.0)
        val k = min(aspect / f.spanX, 1.0 / f.spanY)
        if (k > bestK) { bestK = k; best = deg.toDouble() }
        deg += 2
    }
    return best
}

/**
 * One airfield, drawn the way BMS built it.
 *
 * Everything on it comes out of the field's own authored data: the runway rectangles, the taxi lanes, the ramp
 * spots and the buildings. [route] is the runway in use — BMS authors a separate network and a separate numbering
 * of spots for each end, so the spots shown are the ones that end actually uses.
 */
@Composable
fun AirfieldChart(
    field: Airfield,
    route: AfRoute?,
    inks: ChartInks,
    modifier: Modifier = Modifier,
    path: List<Int>? = null,
    highlight: IntRange? = null,
    you: Offset? = null,
    youHeading: Double? = null,
    traffic: List<ChartTraffic> = emptyList(),
    selectedSpot: Int? = null,
    destinationSpot: Int? = null,
    interactive: Boolean = true,
    labelScale: Float = 1f,
    /** hold this point (field feet) in the middle of the page, whatever it does */
    follow: Offset? = null,
    /** how much ground to show across the page while following */
    followSpanFt: Double = 3600.0,
    /** turn the chart so this heading points up the page */
    upHeading: Double? = null,
    /**
     * Turn the whole field to whatever angle fits the page best.
     *
     * A kneeboard page is tall and most airfields are long and thin, so a field lying east-west is drawn across a
     * fifth of the page with the rest left empty. Turned upright it fills the page, and on a chart that is only
     * ever looked at — never taxied from, which is what [upHeading] is for — north is a convention, not a need.
     * The north arrow says which way it ended up.
     */
    fitRotation: Boolean = false,
    /** what the zoom buttons should close in on: the spot in hand, or the jet */
    zoomAnchor: Offset? = null,
    state: ChartState = rememberChartState(),
    onTapSpot: ((AfSpot) -> Unit)? = null,
) {
    // A chart holds the pointer for panning, zooming and tapping spots. Leaving it without handing focus back
    // left the search field on the page behind unclickable until the window was minimised and reopened.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { focus.clearFocus(force = true) } }
    val tm = rememberTextMeasurer(cacheSize = 256)
    val tap by rememberUpdatedState(onTapSpot)
    val activeRoute by rememberUpdatedState(route)

    // Clipped to the box it was given. A Compose canvas does not clip itself, and a chart is drawn from the
    // middle outwards — so on a VR board, where nothing around it clipped either, the field ran past the area it
    // had on the page and over whatever was beside it.
    BoxWithConstraints(modifier.clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        // the whole field, whichever runway is in use, so switching runway never makes half the airbase vanish
        val pts = remember(field.id) {
            val out = ArrayList<Double>(512)
            fun see(e: Double, n: Double) { out.add(e); out.add(n) }
            val ship = field.ship
            if (ship != null) {
                // The hull, and nothing else. A carrier's points run for a mile past the bow because the same list
                // carries the approach path, and framing on those drew a ship the size of a grain of rice in the
                // middle of an empty sea. Everything on the deck is inside the hull, and everything that is not is
                // clipped away, so the hull is the whole of what the page has to hold.
                var i = 0
                while (i + 1 < ship.hull.size) { see(ship.hull[i].toDouble(), ship.hull[i + 1].toDouble()); i += 2 }
            } else {
                for (r in field.routes) for (p in r.nodes) if (p.t != AfType.RUNWAY_END) see(p.e, p.n)
                for (r in field.runways) for (c in r.corners) see(c.e, c.n)
                for (f in field.features) see(f.e, f.n)
            }
            if (out.isEmpty()) see(0.0, 0.0)
            out.toDoubleArray()
        }

        // What the page is turned by, and what it then fits at. The two go together: the extent that has to fit is
        // the extent *after* turning, so a chart drawn at an angle used to be fitted to its unturned box and came
        // out too small — or, following the jet, cropped.
        val turn = remember(pts, w, h, upHeading, fitRotation, follow != null) {
            when {
                follow != null || !fitRotation -> (upHeading ?: 0.0)
                w <= 0f || h <= 0f -> (upHeading ?: 0.0)
                else -> bestFitHeading(pts, w / h)
            }
        }
        val rot = turn * kotlin.math.PI / 180.0

        val fit = remember(pts, w, h, rot) { fitTo(pts, rot) }
        val fitK = remember(fit, w, h) {
            if (w <= 0f || h <= 0f) 1f else min(w / fit.spanX.toFloat(), h / fit.spanY.toFloat())
        }

        fun view(): ChartView = when {
            follow != null -> ChartView(
                cx = w / 2, cy = h / 2,
                midE = follow.x.toDouble(), midN = follow.y.toDouble(),
                k = (min(w, h) / followSpanFt).toFloat(), rot = rot, deck = field.ship != null,
            )
            else -> ChartView(
                cx = w / 2 + state.panX, cy = h / 2 + state.panY,
                midE = fit.midE, midN = fit.midN,
                k = fitK * state.scale, rot = rot, deck = field.ship != null,
            )
        }

        fun zoomAt(next: Float, at: Offset) {
            val capped = next.coerceIn(1f, 40f)
            val c = Offset(at.x - w / 2, at.y - h / 2)
            val f = capped / state.scale
            state.panX = c.x - (c.x - state.panX) * f
            state.panY = c.y - (c.y - state.panY) * f
            state.scale = capped
        }

        androidx.compose.runtime.SideEffect {
            state.zoomHook = if (follow != null) null else { factor ->
                val v = view()
                val anchor = zoomAnchor?.let { v.at(it.x.toDouble(), it.y.toDouble()) } ?: Offset(w / 2, h / 2)
                zoomAt(state.scale * factor, anchor)
            }
        }

        val gestures = if (!interactive || follow != null) Modifier else Modifier
            .pointerInput(field.id, w, h) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val next = (state.scale * zoom).coerceIn(1f, 40f)
                    val c = Offset(centroid.x - w / 2, centroid.y - h / 2)
                    val f = next / state.scale
                    state.panX = c.x - (c.x - (state.panX + pan.x)) * f
                    state.panY = c.y - (c.y - (state.panY + pan.y)) * f
                    state.scale = next
                }
            }
            // A mouse wheel is how this is zoomed on a PC, and a trackpad on a laptop; the gesture detector above
            // never sees either, so the scroll events are taken straight off the pointer stream.
            .pointerInput(field.id, w, h) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val dy = change.scrollDelta.y
                        if (dy != 0f) {
                            zoomAt(state.scale * (if (dy < 0) 1.18f else 1f / 1.18f), change.position)
                            change.consume()
                        }
                    }
                }
            }
            .pointerInput(field.id, route?.designator, w, h) {
                detectTapGestures(
                    onDoubleTap = { p -> zoomAt(state.scale * 2f, p) },
                    onTap = { p ->
                        val r = activeRoute ?: return@detectTapGestures
                        val v = view()
                        val (e, n) = v.worldOf(p.x, p.y)
                        var best: AfSpot? = null
                        var away = Double.MAX_VALUE
                        for (spot in r.parking) {
                            val node = r.nodes.getOrNull(spot.k) ?: continue
                            val d = hypot(node.e - e, node.n - n)
                            if (d < away) { away = d; best = spot }
                        }
                        // within a spot's own length, or a finger's width on screen, whichever is more generous
                        val reach = max(160.0, (36f / v.k).toDouble())
                        if (best != null && away <= reach) tap?.invoke(best)
                    },
                )
            }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val v = view()
            val labels = LabelBoard(size.width, size.height)
            drawRect(inks.ground, size = Size(size.width, size.height))
            // On a ship the hull goes down first and everything painted on the deck is held inside it: a carrier's
            // own data runs far past the bow, and unclipped the markings and lanes ran out into the sea.
            drawShipHull(field, v, inks)
            val deck = field.ship?.let { deckPath(it.hull, v) }
            val onDeck: (DrawScope.() -> Unit) -> Unit = { body ->
                if (deck == null) body() else clipPath(deck) { body() }
            }
            onDeck {
                drawAsphalt(field, v, inks)
                drawGroundFeatures(field, v, inks)
                drawPavement(field, v, inks)
                drawDeckMarks(field, v, inks)
                drawRunways(field, v, inks)
                drawCentreline(field, v, inks)
            }
            // The island stands on the deck, so it goes on after the markings — but before the spots, which on
            // some ships BMS puts underneath it.
            drawShipIsland(field, v, inks)
            onDeck {
                drawBuiltFeatures(field, v, inks)
                // A deck has no ramp on the chart: see [TaxiView] for why a carrier's spot numbers cannot be drawn.
                val ship = field.ship
                if (ship == null) route?.let { r -> drawSpots(r, v, inks, selectedSpot, destinationSpot) }
                // A deck is three hundred metres long and its spots are drawn where the real ship parks them, not
                // where BMS stored them, so a line between the two would be a line to nowhere.
                if (ship == null && route != null && path != null && path.size > 1) drawRoutePath(route, path, highlight, v, inks)
            }
            traffic.forEach { drawTraffic(it, v, inks) }
            you?.let { drawYou(it, youHeading, v, inks) }

            // Every label is placed after the drawing, so one can be nudged clear of another and joined back to what
            // it names with a leader line. Numbers that used to vanish into each other now spread out instead.
            if (field.ship == null) route?.let { r -> spotLabels(r, v, inks, labels, labelScale, selectedSpot, destinationSpot) }
            taxiwayLabels(field, route, v, inks, labels, labelScale)
            runwayLabels(field, v, inks, labels, labelScale)
            labels.draw(this, tm)
            drawScaleBar(v, inks, tm, labelScale)
            // Turned to the jet's heading, the page no longer has north at the top: say where it went.
            if (upHeading != null || fitRotation) chartNorthArrow(Offset(size.width - 26f, 26f), turn, inks, 13f)
        }
    }
}

// ---------------------------------------------------------------- labels

/** One thing to write on the chart, and where it would like to go. */
private class ChartLabel(
    val text: String,
    val anchor: Offset,
    /** the direction the label prefers to sit in, as a screen vector */
    val away: Offset,
    val size: Float,
    val ink: Color,
    val fill: Color?,
    val weight: FontWeight,
    val leader: Boolean,
    val priority: Int,
)

/**
 * Places labels so they do not sit on top of one another.
 *
 * Each label tries its anchor first, then steps outward along the direction it prefers — for a ramp spot, away from
 * the lane it stands off. When it ends up more than a little way from what it names, a hairline leader is drawn
 * back to the spot so there is no doubt which number belongs to which.
 */
private class LabelBoard(val w: Float, val h: Float) {
    private val wanted = ArrayList<ChartLabel>()
    private val taken = ArrayList<FloatArray>()

    fun add(label: ChartLabel) { wanted.add(label) }

    private fun free(x: Float, y: Float, bw: Float, bh: Float): Boolean {
        for (r in taken) {
            if (x - bw / 2 < r[2] && x + bw / 2 > r[0] && y - bh / 2 < r[3] && y + bh / 2 > r[1]) return false
        }
        return true
    }

    fun draw(scope: DrawScope, tm: TextMeasurer) {
        for (label in wanted.sortedByDescending { it.priority }) {
            if (label.anchor.x < -80 || label.anchor.y < -80 || label.anchor.x > w + 80 || label.anchor.y > h + 80) continue
            val style = TextStyle(color = label.ink, fontSize = label.size.sp, fontWeight = label.weight)
            val layout = tm.measure(label.text, style)
            val bw = layout.size.width + 6f
            val bh = layout.size.height + 2f
            var at: Offset? = null
            val dir = if (label.away.getDistance() > 0.01f) label.away / label.away.getDistance() else Offset(0f, -1f)
            // straight on it, then further and further along the way it leans, then round the clock
            outer@ for (step in 0..7) {
                val reach = step * (bh * 0.85f)
                val spins = if (step == 0) 1 else 8
                for (spin in 0 until spins) {
                    val angle = spin * (2.0 * kotlin.math.PI / spins)
                    val ca = cos(angle).toFloat()
                    val sa = sin(angle).toFloat()
                    val d = Offset(dir.x * ca - dir.y * sa, dir.x * sa + dir.y * ca)
                    val p = label.anchor + d * reach
                    if (free(p.x, p.y, bw, bh)) { at = p; break@outer }
                }
            }
            val p = at ?: continue
            taken.add(floatArrayOf(p.x - bw / 2, p.y - bh / 2, p.x + bw / 2, p.y + bh / 2))
            val moved = (p - label.anchor).getDistance()
            if (label.leader && moved > bh * 0.7f) {
                scope.drawLine(label.ink.copy(alpha = 0.55f), label.anchor, p, strokeWidth = 1f)
            }
            if (label.fill != null) {
                scope.drawRoundRect(
                    color = label.fill,
                    topLeft = Offset(p.x - bw / 2, p.y - bh / 2),
                    size = Size(bw, bh),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f, 2.5f),
                )
            }
            scope.safeText(tm, label.text, Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f), style)
        }
    }
}

private fun spotLabels(
    r: AfRoute, v: ChartView, inks: ChartInks, labels: LabelBoard, labelScale: Float,
    selected: Int?, destination: Int?,
) {
    for (spot in r.parking) {
        val node = r.nodes.getOrNull(spot.k) ?: continue
        val lane = r.nodes.getOrNull(spot.l) ?: node
        val at = v.at(node)
        val from = v.at(lane)
        val mine = spot.n == selected
        val target = spot.n == destination
        labels.add(
            ChartLabel(
                text = spot.n.toString(),
                anchor = at,
                away = at - from,                       // out from the lane, which is where the room is
                size = (if (mine || target) 11.5f else 9.5f) * labelScale,
                ink = if (mine || target) inks.ground else inks.ink,
                fill = if (mine) inks.you else if (target) inks.route else null,
                weight = if (mine || target) FontWeight.Bold else FontWeight.Medium,
                leader = true,
                priority = if (mine || target) 100 else 10,
            ),
        )
    }
}

/**
 * The taxiway letters, in the colours of the sign on the field: black on yellow.
 *
 * Where BMS places its own taxi signs the letters go exactly there, which is where a pilot would read them. Fields
 * without signs fall back to the middle of each run of points carrying that letter.
 */
private fun taxiwayLabels(field: Airfield, route: AfRoute?, v: ChartView, inks: ChartInks, labels: LabelBoard, labelScale: Float) {
    if (field.signs.isNotEmpty()) {
        for (sign in field.signs) {
            labels.add(
                ChartLabel(
                    text = sign.l, anchor = v.at(sign.e, sign.n), away = Offset(0f, -1f),
                    size = 11f * labelScale, ink = inks.signInk, fill = inks.signFill,
                    weight = FontWeight.Bold, leader = false, priority = 60,
                ),
            )
        }
        return
    }
    val seen = HashSet<String>()
    for (r in listOfNotNull(route) + field.routes.filter { it !== route }) {
        var runStart = -1
        var runLetter: String? = null
        for (i in 0..r.nodes.size) {
            val letter = if (i < r.nodes.size) r.nodes[i].l else null
            if (letter != runLetter) {
                val done = runLetter
                if (done != null && runStart >= 0) {
                    val node = r.nodes.getOrNull((runStart + i - 1) / 2)
                    val key = done + "@" + (node?.e?.roundToInt() ?: 0) / 900 + "," + (node?.n?.roundToInt() ?: 0) / 900
                    if (node != null && seen.add(key)) {
                        labels.add(
                            ChartLabel(
                                text = done, anchor = v.at(node), away = Offset(0f, -1f),
                                size = 11f * labelScale, ink = inks.signInk, fill = inks.signFill,
                                weight = FontWeight.Bold, leader = false, priority = 60,
                            ),
                        )
                    }
                }
                runLetter = letter
                runStart = i
            }
        }
    }
}

private fun runwayLabels(field: Airfield, v: ChartView, inks: ChartInks, labels: LabelBoard, labelScale: Float) {
    for (rwy in chartRunways(field)) {
        val a = rwy.ends.getOrNull(0) ?: continue
        val b = rwy.ends.getOrNull(1) ?: continue
        // Threshold bars belong on a runway. A carrier's landing deck is 90 ft wide and already carries the
        // spots and the island; seven more stripes across it is ink for its own sake.
        if (v.deck) continue
        for (end in rwy.ends) {
            val other = if (end === a) b else a
            val brg = bearingOf(end.at.e, end.at.n, other.at.e, other.at.n)
            val rd = brg * kotlin.math.PI / 180.0
            val lp = v.at(end.at.e - sin(rd) * 700, end.at.n - cos(rd) * 700)
            labels.add(
                ChartLabel(
                    text = end.designator, anchor = lp, away = Offset(0f, -1f),
                    size = 14f * labelScale, ink = inks.ground, fill = inks.ink,
                    weight = FontWeight.Bold, leader = false, priority = 90,
                ),
            )
        }
    }
}

/**
 * A carrier's deck, as a shape on the page.
 *
 * Everything drawn on a ship is clipped to this, because almost nothing in a carrier's data stops at the ship. Its
 * "runways" include the approach path — the Carl Vinson's is 2,805 ft long against a 1,094 ft hull — and its taxi
 * network runs half a mile past the bow, so drawn straight the deck markings, the lanes and the centre lines all
 * ran off into the sea and the ship itself was a shape lost in the middle of them.
 */
private fun deckPath(pts: List<Int>, v: ChartView): Path? {
    if (pts.size < 6) return null
    val path = Path()
    path.moveTo(v.x(pts[0].toDouble(), pts[1].toDouble()), v.y(pts[0].toDouble(), pts[1].toDouble()))
    var i = 2
    while (i + 1 < pts.size) {
        path.lineTo(v.x(pts[i].toDouble(), pts[i + 1].toDouble()), v.y(pts[i].toDouble(), pts[i + 1].toDouble()))
        i += 2
    }
    path.close()
    return path
}

/** Is a point on the ship? A ray cast across the hull ring, in field feet. */
private fun inHull(hull: List<Int>, e: Double, n: Double): Boolean {
    if (hull.size < 6) return false
    var inside = false
    var j = hull.size - 2
    var i = 0
    while (i + 1 < hull.size) {
        val xi = hull[i].toDouble(); val yi = hull[i + 1].toDouble()
        val xj = hull[j].toDouble(); val yj = hull[j + 1].toDouble()
        if ((yi > n) != (yj > n) && e < (xj - xi) * (n - yi) / (yj - yi) + xi) inside = !inside
        j = i
        i += 2
    }
    return inside
}

/**
 * The runway slabs worth drawing.
 *
 * A land field draws all of them. A carrier draws **one**: the longest strip that lies wholly on the hull and is
 * wide enough to be a deck. Its data holds four or five, and only one of them is the angled deck — the others are
 * the approach path (which runs a mile past the bow), a catapult, and at some ships a rectangle of no width at
 * all. Drawn together they piled on top of each other and off the edge of the ship.
 */
fun chartRunways(field: Airfield): List<AfRunway> {
    // None of a ship's. Its landing deck is drawn from the real ship ([drawDeckMarks]); what BMS holds is the
    // approach path, a catapult and, on some ships, a rectangle of no width at all.
    if (field.ship != null) return emptyList()
    val hull = field.ship?.hull ?: return field.runways
    return field.runways
        .filter { r ->
            if (r.widthFt < 40 || r.corners.size < 4) return@filter false
            // its middle, not its corners: a deck strip runs to the very edge and an aircraft parks with its tail
            // over the side, so a corner or two outside the hull is the ship, not a mistake. The approach path is
            // the one whose middle is out at sea.
            val e = r.corners.sumOf { it.e } / r.corners.size
            val n = r.corners.sumOf { it.n } / r.corners.size
            inHull(hull, e, n)
        }
        .maxByOrNull { it.lengthFt }
        ?.let { listOf(it) }
        .orEmpty()
}

/**
 * A carrier's hull, laid down before the deck markings so everything else sits on the ship.
 *
 * The island goes on **after** them ([drawShipIsland]): it is a structure standing on the deck, and drawn first the
 * deck's own lines and lane edges ran straight across it.
 */
private fun DrawScope.drawShipHull(field: Airfield, v: ChartView, inks: ChartInks) {
    val ship = field.ship ?: return
    deckPath(ship.hull, v)?.let { hull ->
        drawPath(hull, inks.pavement)
        drawPath(hull, inks.ink, style = Stroke(width = max(1f, v.ft(6.0))), alpha = 0.85f)
    }
}

/** The island: the one landmark on a deck, and what tells a pilot which side is starboard. */
private fun DrawScope.drawShipIsland(field: Airfield, v: ChartView, inks: ChartInks) {
    val ship = field.ship ?: return
    // Translucent, and outlined in its own colour. BMS parks aircraft where the real ship has its island — three
    // of the Liaoning's eight spots are under it — so a solid block would simply hide them.
    for (isl in ship.islands) deckPath(isl, v)?.let { island ->
        drawPath(island, inks.island, alpha = 0.5f)
        drawPath(island, inks.island, style = Stroke(width = max(1f, v.ft(5.0))))
    }
}

/**
 * A carrier's deck markings: the landing area at its published angle, its centre line, the catapults and the ski
 * jump, all built from the real ship (see `tools/extractor/src/ships.mjs`).
 *
 * BMS's own runway rectangles are not drawn on a ship at all. It holds four or five of them and only one is a
 * deck: the rest are the approach path, a catapult and, on some ships, a rectangle of no width.
 */
private fun DrawScope.drawDeckMarks(field: Airfield, v: ChartView, inks: ChartInks) {
    val ship = field.ship ?: return
    deckPath(ship.strip, v)?.let { strip ->
        drawPath(strip, inks.runway)
        // Outlined more strongly than a taxiway would be: on a deck of one grey the landing area has to be the
        // thing the eye finds first.
        drawPath(strip, inks.runwayMark, style = Stroke(width = 1.2f), alpha = 0.75f)
    }
    if (ship.stripLine.size >= 4) {
        drawLine(
            inks.runwayMark,
            v.at(ship.stripLine[0].toDouble(), ship.stripLine[1].toDouble()),
            v.at(ship.stripLine[2].toDouble(), ship.stripLine[3].toDouble()),
            strokeWidth = v.ink(9.0, 0.8f), alpha = 0.8f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(max(3f, v.ft(60.0)), max(3f, v.ft(45.0)))),
        )
    }
    // The ski jump: hatched, because from above it is the one part of the deck that is not flat.
    deckPath(ship.ski, v)?.let { ramp ->
        drawPath(ramp, inks.apron, alpha = 0.8f)
        drawPath(ramp, inks.runwayMark, style = Stroke(width = 0.7f), alpha = 0.5f)
    }
    for (cat in ship.cats) {
        if (cat.size < 4) continue
        drawLine(
            inks.runwayMark,
            v.at(cat[0].toDouble(), cat[1].toDouble()),
            v.at(cat[2].toDouble(), cat[3].toDouble()),
            strokeWidth = v.ink(7.0, 0.6f), alpha = 0.55f,
        )
    }
}

/**
 * The asphalt, laid under everything else.
 *
 * Two sources, drawn as one surface. The field's 3D models give the real pavement — each road a centre line and
 * one width, so it strokes with straight edges down a straight taxiway and an even width all the way round a turn.
 * The taxi network gives a lane wherever BMS modelled no pavement: at Kuktong the whole parallel taxiway and its
 * ramp are terrain texture with only the four connectors modelled, so without the lanes the chart would lose the
 * taxiway a pilot actually taxis down. Neither covers every field on its own, and where both apply they overlap
 * into the same ground.
 *
 * All the edges are laid first, then all the fills, so the whole thing reads as one piece of ground with a rim
 * rather than as a heap of ribbons lying on top of one another.
 */
private fun DrawScope.drawAsphalt(field: Airfield, v: ChartView, inks: ChartInks) {
    // A carrier has no taxiways. Its "taxi network" is the sim's path out to the approach, and drawn on the deck it
    // was half a dozen long lines running through the strip, the spots and the island and off the bow.
    if (field.ship != null) return
    val lanes = Path()
    for (r in field.routes) {
        var i = 0
        while (i + 1 < r.ed.size) {
            val a = r.nodes.getOrNull(r.ed[i])
            val b = r.nodes.getOrNull(r.ed[i + 1])
            i += 2
            if (a == null || b == null) continue
            if (a.t !in WAY_TYPES || b.t !in WAY_TYPES) continue
            lanes.moveTo(v.x(a.e, a.n), v.y(a.e, a.n))
            lanes.lineTo(v.x(b.e, b.n), v.y(b.e, b.n))
        }
    }
    // The lanes first, under everything: where a field has real pavement these disappear beneath it, and where it
    // has none they are the whole surface.
    drawPath(lanes, inks.pavementEdge, style = Stroke(width = v.ink(104.0, 2.5f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(lanes, inks.pavement, style = Stroke(width = v.ink(86.0, 1.5f), cap = StrokeCap.Round, join = StrokeJoin.Round))

    if (field.paved.isEmpty()) return
    val surface = Path()
    surface.fillType = PathFillType.EvenOdd
    for (ring in field.paved) {
        if (ring.size < 6) continue
        surface.moveTo(v.x(ring[0].toDouble(), ring[1].toDouble()), v.y(ring[0].toDouble(), ring[1].toDouble()))
        var i = 2
        while (i + 1 < ring.size) {
            val e = ring[i].toDouble()
            val n = ring[i + 1].toDouble()
            surface.lineTo(v.x(e, n), v.y(e, n))
            i += 2
        }
        surface.close()
    }
    drawPath(surface, inks.pavement)
    // A hairline round the edge, because at the zoom that shows the whole field a taxiway is barely a line wide.
    drawPath(surface, inks.pavementEdge, style = Stroke(width = 1f), alpha = 0.7f)
}

/**
 * The line a pilot follows: the centre line of every taxiway, dashed, in the yellow it is painted in.
 *
 * It is the same network the routing uses — BMS's own ground points — so it runs exactly where the sim's aircraft
 * roll, through the turns and out to each ramp spot.
 */
private fun DrawScope.drawCentreline(field: Airfield, v: ChartView, inks: ChartInks) {
    // Not on a ship. A deck is a couple of hundred feet across and already carries the landing strip, the spots and
    // the island; a yellow line through all of it is one more thing crossing everything else, and a carrier deck
    // has no painted taxiway centre line to draw anyway.
    if (field.ship != null) return
    val line = Path()
    for (r in field.routes) {
        var i = 0
        while (i + 1 < r.ed.size) {
            val a = r.nodes.getOrNull(r.ed[i])
            val b = r.nodes.getOrNull(r.ed[i + 1])
            i += 2
            if (a == null || b == null) continue
            line.moveTo(v.x(a.e, a.n), v.y(a.e, a.n))
            line.lineTo(v.x(b.e, b.n), v.y(b.e, b.n))
        }
    }
    // Long dashes with short gaps, so the line reads as one line running through the turns rather than as a row
    // of ticks, and thin enough to sit under everything else without competing with it.
    val dash = max(5f, v.ft(170.0))
    drawPath(
        line,
        inks.centreline,
        alpha = 0.85f,
        style = Stroke(
            width = max(0.8f, v.ft(14.0)),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.3f)),
        ),
    )
}

// ---------------------------------------------------------------- pavement

/**
 * The lead-ins to the ramp spots: the short stubs off a taxiway that each aircraft is parked down.
 *
 * The taxiways themselves are laid by [drawAsphalt], with the real pavement. These are narrower and paler, so a
 * ramp reads as a row of stands rather than as more taxiway.
 */
private fun DrawScope.drawPavement(field: Airfield, v: ChartView, inks: ChartInks) {
    // Stubs off the taxiways, which a deck does not have either.
    if (field.ship != null) return
    val stubs = Path()
    for (r in field.routes) {
        var i = 0
        while (i + 1 < r.ed.size) {
            val a = r.nodes.getOrNull(r.ed[i])
            val b = r.nodes.getOrNull(r.ed[i + 1])
            i += 2
            if (a == null || b == null) continue
            if (a.t in WAY_TYPES && b.t in WAY_TYPES) continue
            stubs.moveTo(v.x(a.e, a.n), v.y(a.e, a.n))
            stubs.lineTo(v.x(b.e, b.n), v.y(b.e, b.n))
        }
    }
    drawPath(stubs, inks.apron, style = Stroke(width = v.ink(56.0, 1.5f), cap = StrokeCap.Round))
}

private val WAY_TYPES = setOf(AfType.TAXI_START, AfType.TAXI, AfType.ON_RUNWAY, AfType.HOLD_SHORT)

// ---------------------------------------------------------------- runways

private fun DrawScope.drawRunways(field: Airfield, v: ChartView, inks: ChartInks) {
    for (rwy in chartRunways(field)) {
        if (rwy.corners.size < 4) continue
        val slab = Path()
        rwy.corners.forEachIndexed { i, c ->
            if (i == 0) slab.moveTo(v.x(c.e, c.n), v.y(c.e, c.n)) else slab.lineTo(v.x(c.e, c.n), v.y(c.e, c.n))
        }
        slab.close()
        drawPath(slab, inks.runway)
        // A runway is 150 ft across a field miles wide: with the whole field in view the slab is barely a line, so
        // it is outlined as well — without it the strip reads as nothing but its centreline.
        drawPath(slab, inks.runwayMark, style = Stroke(width = if (v.deck) 0.7f else 1.5f), alpha = 0.55f)

        val a = rwy.ends.getOrNull(0) ?: continue
        val b = rwy.ends.getOrNull(1) ?: continue
        drawLine(
            inks.runwayMark, v.at(a.at.e, a.at.n), v.at(b.at.e, b.at.n),
            strokeWidth = v.ink(9.0, 0.8f), alpha = 0.8f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(max(3f, v.ft(140.0)), max(3f, v.ft(110.0)))),
        )

        // Threshold bars belong on a runway. A carrier's landing deck is 90 ft wide and already carries the
        // spots and the island; seven more stripes across it is ink for its own sake.
        if (v.deck) continue
        for (end in rwy.ends) {
            val other = if (end === a) b else a
            val brg = bearingOf(end.at.e, end.at.n, other.at.e, other.at.n)
            val rd = brg * kotlin.math.PI / 180.0
            val ue = sin(rd); val un = cos(rd)          // along the runway, inward
            val pe = cos(rd); val pn = -sin(rd)         // across it
            for (i in -3..3) {
                if (i == 0) continue
                val oe = pe * i * 17; val on = pn * i * 17
                drawLine(
                    inks.runwayMark,
                    v.at(end.at.e + oe + ue * 130, end.at.n + on + un * 130),
                    v.at(end.at.e + oe + ue * 430, end.at.n + on + un * 430),
                    strokeWidth = v.ink(11.0, 0.7f), alpha = 0.75f,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- furniture

/** The flat things: patches of apron and taxiway, drawn under the lanes so the pavement reads as one surface. */
private fun DrawScope.drawGroundFeatures(field: Airfield, v: ChartView, inks: ChartInks) {
    for (f in field.features) {
        if (f.k != "pavement") continue
        val c = v.at(f.e, f.n)
        if (c.x < -120 || c.y < -120 || c.x > size.width + 120 || c.y > size.height + 120) continue
        rotate(v.screenAngle(f.h), c) {
            drawRect(
                inks.pavement,
                topLeft = Offset(c.x - v.ft(f.w / 2), c.y - v.ft(f.l / 2)),
                size = Size(max(2f, v.ft(f.w)), max(2f, v.ft(f.l))),
            )
        }
    }
}

/**
 * Everything built: shelters, hangars, buildings, fuel, towers, walls and lights.
 *
 * Every object the sim places at the field is here, each at its own position and heading, so the chart reads like
 * the place rather than like a sketch of it. The footprints are representative — BMS publishes no dimensions.
 */
private fun DrawScope.drawBuiltFeatures(field: Airfield, v: ChartView, inks: ChartInks) {
    for (f in field.features) {
        if (f.k == "pavement") continue
        val c = v.at(f.e, f.n)
        if (c.x < -120 || c.y < -120 || c.x > size.width + 120 || c.y > size.height + 120) continue
        val angle = v.screenAngle(f.h)
        fun box(colour: Color, alpha: Float = 1f) = rotate(angle, c) {
            drawRect(
                colour,
                topLeft = Offset(c.x - v.ft(f.w / 2), c.y - v.ft(f.l / 2)),
                size = Size(max(1.2f, v.ft(f.w)), max(1.2f, v.ft(f.l))),
                alpha = alpha,
            )
        }
        when (f.k) {
            "shelter" -> box(inks.shelter, 0.85f)
            "hangar" -> box(inks.hangar)
            "building" -> box(inks.building, 0.9f)
            "fuel" -> drawCircle(inks.fuel, radius = max(1.5f, v.ft(f.w / 2)), center = c)
            "tower" -> {
                drawCircle(inks.tower, radius = max(2.5f, v.ft(f.w / 2)), center = c)
                drawCircle(inks.ground, radius = max(1f, v.ft(f.w / 5)), center = c)
            }
            "mast" -> drawCircle(inks.tower, radius = max(1.5f, v.ft(f.w / 2)), center = c, alpha = 0.8f)
            // a wall is a line on the ground, and is what tells a pilot where a ramp ends
            "wall" -> rotate(angle, c) {
                drawLine(
                    inks.dim,
                    Offset(c.x - v.ft(f.w / 2), c.y),
                    Offset(c.x + v.ft(f.w / 2), c.y),
                    strokeWidth = max(0.8f, v.ft(10.0)),
                    alpha = 0.5f,
                )
            }
            "light" -> if (v.ft(60.0) > 2f) drawCircle(inks.dim, radius = 1.2f, center = c, alpha = 0.6f)
        }
    }
}

// ---------------------------------------------------------------- ramp

/** An outlined box, which the spots want twice each. */
private fun DrawScope.drawRect(tl: Offset, size: Size, ink: Color, w: Float, a: Float) =
    drawRect(ink, topLeft = tl, size = size, style = Stroke(width = w), alpha = a)

private fun DrawScope.drawSpots(r: AfRoute, v: ChartView, inks: ChartInks, selected: Int?, destination: Int?) {
    for (spot in r.parking) {
        val node = r.nodes.getOrNull(spot.k) ?: continue
        val lane = r.nodes.getOrNull(spot.l) ?: node
        val c = v.at(node)
        if (c.x < -40 || c.y < -40 || c.x > size.width + 40 || c.y > size.height + 40) continue
        val face = bearingOf(lane.e, lane.n, node.e, node.n)   // nose-in: off the lane, into the spot
        val half = max(2f, v.ft((if (spot.w > 0) max(spot.w, 45) else 48).toDouble() / 2 + 8))
        val mine = spot.n == selected
        val target = spot.n == destination
        // Chosen and destination spots take their own colour; otherwise the two kinds of spot are told apart by
        // their shape, which needs no extra lines and so keeps a ramp of eighty stands legible.
        val picked = when {
            mine -> inks.you
            target -> inks.route
            else -> null
        }
        rotate(v.screenAngle(face), c) {
            val left = c.x - half
            val right = c.x + half
            val top = c.y - half * 1.15f
            val bottom = c.y + half * 1.15f
            if (spot.covered) {
                // A hardened shelter or a hangar, drawn as what it is: a bay with a domed back and its mouth on
                // the lane side, filled solid so a roof reads at a glance without a single extra stroke.
                val dome = min(half, (bottom - top) * 0.5f)
                val bay = Path()
                bay.moveTo(left, bottom)
                bay.lineTo(left, top + dome)
                bay.quadraticBezierTo(left, top, c.x, top)
                bay.quadraticBezierTo(right, top, right, top + dome)
                bay.lineTo(right, bottom)
                bay.close()
                drawPath(bay, picked ?: inks.shelter)
            } else {
                // Open ramp: a thin stand box, the way an airport chart draws one. Barely inked, so a whole apron
                // of them reads as a row of stands rather than as a grid drawn over the pavement.
                val tl = Offset(left, top)
                val sz = Size(half * 2, bottom - top)
                if (picked != null) drawRect(picked, topLeft = tl, size = sz)
                else drawRect(tl = tl, size = sz, ink = inks.dim, w = v.ink(3.0, 0.45f), a = 0.9f)
            }
        }
    }
}

// ---------------------------------------------------------------- the route, and who is on the field

private fun DrawScope.drawRoutePath(r: AfRoute, path: List<Int>, highlight: IntRange?, v: ChartView, inks: ChartInks) {
    val line = Path()
    path.forEachIndexed { i, k ->
        val p = r.nodes.getOrNull(k) ?: return@forEachIndexed
        if (i == 0) line.moveTo(v.x(p.e, p.n), v.y(p.e, p.n)) else line.lineTo(v.x(p.e, p.n), v.y(p.e, p.n))
    }
    drawPath(line, inks.route, style = Stroke(width = v.ink(58.0, 4f), cap = StrokeCap.Round, join = StrokeJoin.Round), alpha = 0.35f)
    drawPath(line, inks.route, style = Stroke(width = v.ink(20.0, 2f), cap = StrokeCap.Round, join = StrokeJoin.Round))

    if (highlight != null) {
        val part = Path()
        var started = false
        for (i in highlight) {
            val p = r.nodes.getOrNull(path.getOrNull(i) ?: continue) ?: continue
            if (!started) { part.moveTo(v.x(p.e, p.n), v.y(p.e, p.n)); started = true } else part.lineTo(v.x(p.e, p.n), v.y(p.e, p.n))
        }
        if (started) drawPath(part, inks.you, style = Stroke(width = v.ink(32.0, 3f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    r.nodes.getOrNull(path.last())?.let {
        drawCircle(inks.accent, radius = v.ink(70.0, 3f), center = v.at(it), style = Stroke(width = v.ink(22.0, 1.5f)))
    }
}

private fun DrawScope.aircraft(c: Offset, angle: Float, colour: Color, len: Float, edge: Color?, thin: Boolean = false) {
    val wing = len * 0.78f
    val path = Path()
    path.moveTo(c.x, c.y - len)
    path.lineTo(c.x - wing, c.y + len * 0.55f)
    path.lineTo(c.x, c.y + len * 0.18f)
    path.lineTo(c.x + wing, c.y + len * 0.55f)
    path.close()
    rotate(angle, c) {
        drawPath(path, colour)
        if (edge != null) drawPath(path, edge, style = Stroke(width = if (thin) 0.5f else 1f))
    }
}

private fun DrawScope.drawTraffic(t: ChartTraffic, v: ChartView, inks: ChartInks) {
    val c = v.at(t.e, t.n)
    if (c.x < -30 || c.y < -30 || c.x > size.width + 30 || c.y > size.height + 30) return
    val colour = when (t.kind) {
        TrafficKind.WINGMAN -> inks.wingman
        TrafficKind.FRIENDLY -> inks.friendly
        TrafficKind.HOSTILE -> inks.hostile
        TrafficKind.NEUTRAL -> inks.neutral
    }
    // An F-16 is about fifty feet long. Drawn much larger than that the symbols swallow the spots they are
    // parked on and run into one another down a line of shelters, so they are kept near life size.
    val len = max(4f, v.ft(56.0))
    if (t.heading != null) aircraft(c, v.screenAngle(t.heading), colour, len, inks.ground.copy(alpha = 0.6f), v.deck)
    else drawCircle(colour, radius = len * 0.55f, center = c)
}

private fun DrawScope.drawYou(you: Offset, heading: Double?, v: ChartView, inks: ChartInks) {
    val c = v.at(you.x.toDouble(), you.y.toDouble())
    // The halo says "you are here" on a field two miles across. On a deck a hundred feet either side of the jet is
    // a third of the ship, so it is drawn to the aircraft instead.
    drawCircle(inks.you, radius = if (v.deck) max(5f, v.ft(45.0)) else max(8f, v.ft(120.0)), center = c, alpha = 0.22f)
    if (heading != null) aircraft(c, v.screenAngle(heading), inks.you, max(7f, v.ft(84.0)), inks.ground, v.deck)
    else drawCircle(inks.you, radius = max(4f, v.ft(70.0)), center = c)
}

// ---------------------------------------------------------------- furniture of the chart itself

private fun DrawScope.drawScaleBar(v: ChartView, inks: ChartInks, tm: TextMeasurer, labelScale: Float) {
    var ft = 2000.0
    var px = v.ft(ft)
    while (px > size.width * 0.28f && ft > 50) { ft /= 2; px = v.ft(ft) }
    while (px < size.width * 0.08f && ft < 40000) { ft *= 2; px = v.ft(ft) }
    val y = size.height - 16.dp.toPx()
    val x0 = 14.dp.toPx()
    drawLine(inks.dim, Offset(x0, y), Offset(x0 + px, y), strokeWidth = 1.5f)
    drawLine(inks.dim, Offset(x0, y - 4f), Offset(x0, y + 1f), strokeWidth = 1.5f)
    drawLine(inks.dim, Offset(x0 + px, y - 4f), Offset(x0 + px, y + 1f), strokeWidth = 1.5f)
    val text = if (ft >= FT_PER_NM) "${((ft / FT_PER_NM) * 10).roundToInt() / 10.0} nm" else "${ft.roundToInt()} ft"
    safeText(tm, text, Offset(x0, y + 2f), TextStyle(color = inks.dim, fontSize = (9f * labelScale).sp))
}

/** A north arrow, for a chart that has been turned to put the jet's heading up the page. */
fun DrawScope.chartNorthArrow(at: Offset, upHeading: Double, inks: ChartInks, size: Float = 16f) {
    val angle = (-upHeading).toFloat()
    val path = Path()
    path.moveTo(at.x, at.y - size)
    path.lineTo(at.x - size * 0.42f, at.y + size * 0.55f)
    path.lineTo(at.x, at.y + size * 0.2f)
    path.lineTo(at.x + size * 0.42f, at.y + size * 0.55f)
    path.close()
    rotate(angle, at) {
        drawPath(path, inks.ink.copy(alpha = 0.75f))
    }
}

private fun bearingOf(e0: Double, n0: Double, e1: Double, n1: Double): Double =
    ((kotlin.math.atan2(e1 - e0, n1 - n0) * 180.0 / kotlin.math.PI) + 360.0) % 360.0

internal fun angleGap(a: Double, b: Double): Double = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)
