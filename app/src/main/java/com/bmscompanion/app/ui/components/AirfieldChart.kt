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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.airfield.AfDeckMark
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
    /**
     * The alert cell: red, as BMS's own parking charts print those numbers, and used nowhere else on the ground. A
     * muted brick by day and a dusky rose by night rather than a signal red — it marks a stand nobody is sent to, not
     * a warning — and lighter than the day ink, darker than the night ink, so it reads apart by lightness too.
     */
    val alert: Color,
    val hangar: Color,
    /** a carrier's island on a chart drawn before decks had their detail: its own colour, the one landmark there */
    val island: Color,
    /**
     * A carrier's flight deck: the dark grey of non-skid, by day and by night, because that is what a deck looks
     * like and what every painted mark on it is chosen to stand out from.
     */
    val deck: Color,
    /** what stands below a deck's edge and is seen from above — sponsons, galleries, catwalks — and the ski jump */
    val deckEdge: Color,
    /** the white paint on a deck: landing-area edges, centre lines, wires, the hull number */
    val deckPaint: Color,
    /** the yellow paint: centre lines on some decks, take-off lines, spots, and the edges of the lifts */
    val deckYellow: Color,
    /** the red of a foul line */
    val deckRed: Color,
    /** an island and what stands on it: lighter than the deck, the way a superstructure reads from above */
    val islandTop: Color,
    val building: Color,
    val fuel: Color,
    /** masts, radio and water towers: small round things that are not the control tower */
    val tower: Color,
    /**
     * The control tower: a violet no other ground object wears, because it is the landmark a pilot finds his way
     * round a field by — drawn as its own shape from above, with a "TWR" plate in the same ink.
     */
    val controlTower: Color,
    /** an arresting cable across a runway: the amber of the cable markers, bright against the dark runway */
    val cable: Color,
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
            shelter = Color(0xFF86A08E), alert = Color(0xFF9B4B45),
            hangar = Color(0xFF8D7B63), island = Color(0xFF5B4C7A), building = Color(0xFF7E8B99),
            deck = Color(0xFF4B5258), deckEdge = Color(0xFFA3AAB0), deckPaint = Color(0xFFF6F7F5),
            deckYellow = Color(0xFFE6BA2C), deckRed = Color(0xFFD8392D), islandTop = Color(0xFFC6CCD0),
            fuel = Color(0xFFB08928), tower = Color(0xFF9C4A63),
            controlTower = Color(0xFF6A3EC2), cable = Color(0xFFE3A008),
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
            shelter = Color(0xFF5E7164), alert = Color(0xFFC47C74),
            hangar = Color(0xFF6B5B47), island = Color(0xFF8E79BE), building = Color(0xFF4A5663),
            deck = Color(0xFF2F363C), deckEdge = Color(0xFF5E6870), deckPaint = Color(0xFFCBD3D7),
            deckYellow = Color(0xFFC9A63A), deckRed = Color(0xFFD0564B), islandTop = Color(0xFF7F8A92),
            fuel = Color(0xFF8A6D24), tower = Color(0xFFA85E76),
            controlTower = Color(0xFF9473E6), cable = Color(0xFFE8B83A),
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

    /** the heading the chart last drew up the page — what a "turn 90°" steps on from when the page fitted it itself */
    var turnShown: Double = 0.0
        internal set

    /**
     * Degrees two fingers have turned the chart by, on top of the page's own turn (north up, a heading, or fitted).
     * Not kept between launches: a reset (North up, the turn buttons, Fit) puts it back to 0.
     */
    var twist by mutableFloatStateOf(0f)

    fun reset() { scale = 1f; panX = 0f; panY = 0f; twist = 0f }
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
private fun bestFitHeading(pts: DoubleArray, aspect: Float, step: Int = 2): Double {
    if (pts.size < 4 || aspect <= 0f) return 0.0
    var best = 0.0
    var bestK = -1.0
    var deg = 0
    while (deg < 180) {
        val f = fitTo(pts, deg * kotlin.math.PI / 180.0)
        val k = min(aspect / f.spanX, 1.0 / f.spanY)
        if (k > bestK) { bestK = k; best = deg.toDouble() }
        deg += step
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
                // a deck is fitted bow up or bow across, never at a slant: its angled landing area fits a hair
                // better diagonally, and a carrier drawn askew reads as wrong
                field.ship != null -> bestFitHeading(pts, w / h, step = 90)
                else -> bestFitHeading(pts, w / h)
            }
        }
        val rot = turn * kotlin.math.PI / 180.0
        // what is drawn: the page's turn plus two fingers' twist (the fit stays the page's, so a twist never rescales)
        val shownTurn = if (follow != null) turn else turn + state.twist
        val shownRot = shownTurn * kotlin.math.PI / 180.0

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
                k = fitK * state.scale, rot = shownRot, deck = field.ship != null,
            )
        }

        /** zooms to [next] (1 to 40 times) about [at] after moving by [by]; answers the factor applied */
        fun zoomAt(next: Float, at: Offset, by: Offset = Offset.Zero): Float {
            val old = state.scale
            val capped = next.coerceIn(1f, 40f)
            val f = capped / old
            val np = ZoomMath.keep(Offset(at.x - w / 2, at.y - h / 2), Offset(state.panX, state.panY) + by, f)
            state.panX = np.x
            state.panY = np.y
            state.scale = capped
            return f
        }
        // the wheel, the keys and a double tap glide (ZoomMath); a pinch follows the fingers
        val glide = rememberZoomGlide { f, at -> zoomAt(state.scale * f, at) }

        androidx.compose.runtime.SideEffect {
            state.turnShown = shownTurn
            state.zoomHook = if (follow != null) null else { factor ->
                val v = view()
                val anchor = zoomAnchor?.let { v.at(it.x.toDouble(), it.y.toDouble()) } ?: Offset(w / 2, h / 2)
                glide.by(factor, anchor)
            }
        }

        val gestures = if (!interactive || follow != null) Modifier else Modifier
            .pointerInput(field.id, w, h) {
                // two fingers also turn the chart about the point between them; panZoomLock keeps a pinch or a drag
                // from turning it unless the turn came first (a mouse has one pointer and never turns it)
                detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, rotation ->
                    glide.stop()
                    zoomAt(state.scale * ZoomMath.pinch(zoom), centroid, pan)
                    if (rotation != 0f) {
                        // the picture turns clockwise with the fingers (y down), so the heading up the page goes down
                        val a = rotation * (kotlin.math.PI / 180.0)
                        val ox = w / 2 + state.panX - centroid.x
                        val oy = h / 2 + state.panY - centroid.y
                        val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
                        state.panX = centroid.x + ox * c - oy * s - w / 2
                        state.panY = centroid.y + ox * s + oy * c - h / 2
                        state.twist = ((state.twist - rotation) % 360f + 360f) % 360f
                    }
                }
            }
            // A mouse wheel is how this is zoomed on a PC, and a trackpad on a laptop; the gesture detector above
            // never sees either, so the scroll events are taken straight off the pointer stream — measured, so a
            // touchpad's many small deltas zoom in proportion rather than a step each.
            .wheelZoom(glide)
            .pointerInput(field.id, route?.designator, w, h) {
                detectTapGestures(
                    onDoubleTap = { p -> glide.by(ZoomMath.DOUBLE_TAP, p) },
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
                drawDeckMarks(field, v, inks, tm)
                drawRunways(field, v, inks)
                drawCables(field, v, inks)
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
            towerLabels(field, v, inks, labels, labelScale)
            cableLabels(field, v, inks, labels, labelScale)
            labels.draw(this, tm)
            drawScaleBar(v, inks, tm, labelScale)
            // Turned to the jet's heading, the page no longer has north at the top: say where it went.
            if (upHeading != null || fitRotation || state.twist != 0f) chartNorthArrow(Offset(size.width - 26f, 26f), shownTurn, inks, 13f)
        }
    }
}

// ---------------------------------------------------------------- labels

/** One thing to write on the chart, and where it would like to go. */
/** What a label's own background is drawn as: a plate behind the text, a ring round it, or a box round it. */
private enum class LabelShape { PLATE, RING, BOX }

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
    /**
     * A spot number carries its size in the shape around it, which is how BMS's own parking charts print it: a
     * small stand's number is encircled, a large one's is boxed. [PLATE] is the filled slab a taxiway sign and a
     * chosen spot get.
     */
    val shape: LabelShape = LabelShape.PLATE,
    /** drawn round the shape rather than filled, so a number stays readable over pavement */
    val outline: Color? = null,
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
            // A ring has to be round, so it is sized on the larger of the two extents; a box takes the text's own
            // shape. Both are drawn a hair wider than the glyphs so the digits never touch the line.
            val ringR = max(bw, bh) / 2f + 1f
            when {
                label.fill == null && label.outline == null -> Unit
                label.shape == LabelShape.RING -> {
                    if (label.fill != null) scope.drawCircle(label.fill, ringR, p)
                    if (label.outline != null) scope.drawCircle(label.outline, ringR, p, style = Stroke(width = 1.1f))
                }
                label.shape == LabelShape.BOX -> {
                    val tl = Offset(p.x - bw / 2, p.y - bh / 2)
                    if (label.fill != null) scope.drawRect(label.fill, topLeft = tl, size = Size(bw, bh))
                    if (label.outline != null) {
                        scope.drawRect(label.outline, topLeft = tl, size = Size(bw, bh), style = Stroke(width = 1.1f))
                    }
                }
                else -> {
                    if (label.fill != null) {
                        scope.drawRoundRect(
                            color = label.fill,
                            topLeft = Offset(p.x - bw / 2, p.y - bh / 2),
                            size = Size(bw, bh),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f, 2.5f),
                        )
                    }
                    if (label.outline != null) {
                        scope.drawRoundRect(
                            color = label.outline,
                            topLeft = Offset(p.x - bw / 2, p.y - bh / 2),
                            size = Size(bw, bh),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f, 2.5f),
                            style = Stroke(width = 1.1f),
                        )
                    }
                }
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
        val picked = mine || target
        // BMS's own parking charts print a small stand's number encircled, a large one's boxed and the alert cell's
        // in red, so ours are drawn the same way and a pilot who knows those charts reads this one without a key.
        // The chosen and destination spots keep their filled plate: which spot is yours outranks what size it is.
        labels.add(
            ChartLabel(
                text = spot.label,                      // BMS Ground's number, "04"
                anchor = at,
                away = at - from,                       // out from the lane, which is where the room is
                size = (if (picked) 11.5f else 9.5f) * labelScale,
                ink = when {
                    picked -> inks.ground
                    spot.alert -> inks.alert
                    else -> inks.ink
                },
                fill = if (mine) inks.you else if (target) inks.route else null,
                weight = if (picked || spot.alert) FontWeight.Bold else FontWeight.Medium,
                leader = true,
                priority = if (picked) 100 else if (spot.alert) 20 else 10,
                shape = if (picked) LabelShape.PLATE else if (spot.small) LabelShape.RING else LabelShape.BOX,
                // The ring or box is the whole point, so it is always drawn — but faintly, because a ramp of eighty
                // stands drawn in full-strength outlines reads as chain-link. The alert cell keeps its red at full.
                outline = when {
                    picked -> null
                    spot.alert -> inks.alert
                    else -> inks.ink.copy(alpha = 0.45f)
                },
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

/** The deck's own grey, a shade lighter or darker where the ship's paint is ([AfShip.tone]). */
private fun deckColour(ship: AfShip, inks: ChartInks): Color = when {
    ship.tone > 0 -> lerp(inks.deck, inks.deckEdge, 0.28f)
    ship.tone < 0 -> lerp(inks.deck, Color.Black, 0.22f)
    else -> inks.deck
}

/** A mark's points as a path on the page; closed for a ring. */
private fun markPath(p: List<Int>, v: ChartView, close: Boolean): Path? {
    if (p.size < 4) return null
    val path = Path()
    path.moveTo(v.x(p[0].toDouble(), p[1].toDouble()), v.y(p[0].toDouble(), p[1].toDouble()))
    var i = 2
    while (i + 1 < p.size) {
        path.lineTo(v.x(p[i].toDouble(), p[i + 1].toDouble()), v.y(p[i].toDouble(), p[i + 1].toDouble()))
        i += 2
    }
    if (close) path.close()
    return path
}

/**
 * A carrier's hull, laid down before the deck markings so everything else sits on the ship.
 *
 * With a detailed deck ([AfShip.marks]) that is three layers, the way a deck diagram is drawn: the structures below
 * the deck edge in a lighter grey — sponsons, galleries, the catwalk that runs round a carrier — then the flight deck
 * itself in dark non-skid grey on top of them, so what sticks out past the deck reads as structure and not as deck.
 *
 * The island goes on **after** the markings ([drawShipIsland]): it is a structure standing on the deck, and drawn
 * first the deck's own lines ran straight across it.
 */
private fun DrawScope.drawShipHull(field: Airfield, v: ChartView, inks: ChartInks) {
    val ship = field.ship ?: return
    val hull = deckPath(ship.hull, v) ?: return
    if (ship.marks.isEmpty()) {
        drawPath(hull, inks.pavement)
        drawPath(hull, inks.ink, style = Stroke(width = max(1f, v.ft(6.0))), alpha = 0.85f)
        return
    }
    // the catwalk: a narrow rim of structure all the way round, as a carrier has
    drawPath(hull, inks.deckEdge, style = Stroke(width = max(1.2f, v.ft(9.0)), join = StrokeJoin.Round))
    for (m in ship.marks) if (m.k == "edge") markPath(m.p, v, close = true)?.let {
        drawPath(it, inks.deckEdge)
        drawPath(it, inks.ink, style = Stroke(width = max(0.5f, v.ft(0.8))), alpha = 0.35f)
    }
    drawPath(hull, deckColour(ship, inks))
    drawPath(hull, inks.ink, style = Stroke(width = max(0.8f, v.ft(1.2))), alpha = 0.7f)
}

/**
 * The islands, and what stands on them: the one landmark on a deck, and what tells a pilot which side is starboard.
 *
 * A light structure with a shadow on the deck, the way a superstructure reads from above, and its radomes, funnels,
 * masts and platforms drawn on top once the page is close enough to show them.
 */
private fun DrawScope.drawShipIsland(field: Airfield, v: ChartView, inks: ChartInks) {
    val ship = field.ship ?: return
    if (ship.marks.isEmpty()) {
        // Translucent, and outlined in its own colour. BMS parks aircraft where the real ship has its island — three
        // of the Liaoning's eight spots are under it — so a solid block would simply hide them.
        for (isl in ship.islands) deckPath(isl, v)?.let { island ->
            drawPath(island, inks.island, alpha = 0.5f)
            drawPath(island, inks.island, style = Stroke(width = max(1f, v.ft(5.0))))
        }
        return
    }
    val shadow = max(1f, v.ft(5.0))
    val edge = Stroke(width = max(0.7f, v.ft(1.2)))
    for (m in ship.marks) if (m.k == "island") markPath(m.p, v, close = true)?.let { island ->
        translate(shadow, shadow) { drawPath(island, Color.Black, alpha = 0.35f) }
        drawPath(island, inks.islandTop)
        drawPath(island, inks.ink, style = edge, alpha = 0.8f)
    }
    // the detail on top: only once there is room for it, or it is a smudge
    if (v.k < 0.22f) return
    val part = lerp(inks.islandTop, inks.deck, 0.35f)
    for (m in ship.marks) if (m.k == "part") markPath(m.p, v, close = true)?.let {
        drawPath(it, part)
        drawPath(it, inks.ink, style = Stroke(width = max(0.5f, v.ft(0.8))), alpha = 0.6f)
    }
}

/**
 * Everything painted or fitted on a carrier's deck, in the order the ship's data gives it: the landing area and its
 * lines, the lifts, the ski jump, the arresting wires, the catapults and their blast deflectors, the painted spots
 * and the hull number — each from the ship's own model or a published figure (see `tools/extractor/src/ships.mjs`).
 *
 * Widths are in feet, so a line is as wide on the page as it is on the deck, with a floor in pixels so nothing
 * vanishes on a board at its widest; the finest things — the boxes along a landing-area edge, the deck lights, the
 * hatches, the sheaves at the ends of the wires — appear once the page is close enough to show them, as the detail
 * of an airfield chart does.
 *
 * BMS's own runway rectangles are not drawn on a ship at all: they are the approach path, a catapult and, on some
 * ships, a rectangle of no width.
 */
private fun DrawScope.drawDeckMarks(field: Airfield, v: ChartView, inks: ChartInks, tm: TextMeasurer) {
    val ship = field.ship ?: return
    if (ship.marks.isEmpty()) { drawPlainDeck(ship, v, inks); return }
    val fine = v.k >= 0.45f
    val deck = deckColour(ship, inks)
    fun w(ft: Double, floor: Float) = max(floor, v.ft(ft))
    fun dashes(on: Double, off: Double) = PathEffect.dashPathEffect(floatArrayOf(max(3f, v.ft(on)), max(2f, v.ft(off))))
    fun pt(m: AfDeckMark, i: Int = 0) = v.at(m.p[i].toDouble(), m.p[i + 1].toDouble())
    for (m in ship.marks) {
        when (m.k) {
            "lane" -> markPath(m.p, v, close = true)?.let { drawPath(it, Color.Black, alpha = 0.13f) }
            "lift", "lift?" -> markPath(m.p, v, close = true)?.let {
                drawPath(it, lerp(deck, inks.deckEdge, 0.12f))
                drawPath(
                    it, inks.deckYellow,
                    style = Stroke(width = w(2.5, 1f), pathEffect = if (m.k == "lift?") dashes(8.0, 6.0) else null),
                    alpha = if (m.k == "lift?") 0.7f else 0.95f,
                )
            }
            "hatch" -> if (fine) markPath(m.p, v, close = true)?.let {
                drawPath(it, Color.Black, alpha = 0.18f)
                drawPath(it, inks.deckPaint, style = Stroke(width = w(0.8, 0.5f)), alpha = 0.45f)
            }
            "ski" -> markPath(m.p, v, close = true)?.let {
                drawPath(it, inks.deckEdge, alpha = 0.42f)
                drawPath(it, inks.deckPaint, style = Stroke(width = w(1.5, 0.7f)), alpha = 0.55f)
            }
            "step" -> if (m.p.size >= 4) drawLine(inks.deckPaint, pt(m), pt(m, 2), strokeWidth = w(1.5, 0.6f), alpha = 0.5f)
            "line", "ladder" -> markPath(m.p, v, close = false)?.let { line ->
                drawPath(line, inks.deckPaint, style = Stroke(width = w(3.0, 0.9f), join = StrokeJoin.Round), alpha = 0.92f)
                if (m.k == "ladder" && fine && m.p.size >= 4) ladderBoxes(m, v, inks)
            }
            "dash", "ydash", "faint" -> markPath(m.p, v, close = false)?.let {
                val ink = if (m.k == "ydash") inks.deckYellow else inks.deckPaint
                drawPath(it, ink, style = Stroke(width = w(2.5, 0.8f), pathEffect = dashes(24.0, 16.0)), alpha = if (m.k == "faint") 0.3f else 0.9f)
            }
            "foul", "foulk" -> markPath(m.p, v, close = false)?.let {
                // red and white (or red and black) in turn: the ground laid first, the red dashes on it
                val under = if (m.k == "foulk") Color(0xFF151515) else inks.deckPaint
                drawPath(it, under, style = Stroke(width = w(2.5, 0.9f)), alpha = 0.85f)
                drawPath(it, inks.deckRed, style = Stroke(width = w(2.5, 0.9f), pathEffect = dashes(12.0, 12.0)))
            }
            "lights" -> if (fine && m.p.size >= 4) deckLights(m, v, inks)
            "wire" -> if (m.p.size >= 4) {
                val a = pt(m)
                val b = pt(m, 2)
                drawLine(inks.deckPaint, a, b, strokeWidth = w(1.6, 0.7f), alpha = 0.9f)
                // the sheaves at either end, where the wire goes down through the deck
                if (fine) for (c in listOf(a, b)) drawCircle(inks.deckPaint, radius = w(2.5, 1f), center = c, alpha = 0.9f)
            }
            "cat" -> if (m.p.size >= 4) {
                val a = pt(m)
                val b = pt(m, 2)
                // the track: a light slot down the deck, with the shuttle at the end the jet is launched from
                drawLine(inks.deckEdge, a, b, strokeWidth = w(6.0, 1.5f))
                drawLine(deck, a, b, strokeWidth = w(1.5, 0.5f))
                drawCircle(inks.deckPaint, radius = w(3.5, 1.2f), center = a)
            }
            "jbd" -> markPath(m.p, v, close = true)?.let {
                drawPath(it, inks.deckEdge, alpha = 0.95f)
                drawPath(it, inks.deckYellow, style = Stroke(width = w(1.2, 0.6f)))
            }
            "spot", "tee" -> if (m.p.size >= 2) {
                val c = pt(m)
                val r = max(2f, v.ft(m.r.toDouble()))
                val ink = if (m.k == "tee") inks.deckPaint else inks.deckYellow
                drawCircle(ink, radius = r, center = c, style = Stroke(width = w(2.0, 0.7f)), alpha = 0.9f)
                if (m.k == "tee") {
                    // the lineup line across the spot, square to the ship's axis
                    val e = m.p[0].toDouble()
                    val n = m.p[1].toDouble()
                    drawLine(ink, v.at(e - m.r * 1.3, n), v.at(e + m.r * 1.3, n), strokeWidth = w(2.0, 0.7f), alpha = 0.9f)
                }
                m.t?.let { t -> if (r >= 5f) deckText(tm, t, c, r * 1.1f, 0.0, v, ink, 0.95f) }
            }
            "text" -> if (m.p.size >= 2) {
                // the hull number, reading from astern, the way a pilot in the groove sees it
                val px = v.ft(m.r.toDouble())
                if (px >= 6f) m.t?.let { deckText(tm, it, pt(m), px, 0.0, v, inks.deckPaint, 0.85f) }
            }
        }
    }
}

/** The boxes painted along a landing-area edge, one every seventy feet. */
private fun DrawScope.ladderBoxes(m: AfDeckMark, v: ChartView, inks: ChartInks) {
    val e0 = m.p[0].toDouble()
    val n0 = m.p[1].toDouble()
    val e1 = m.p[m.p.size - 2].toDouble()
    val n1 = m.p[m.p.size - 1].toDouble()
    val len = hypot(e1 - e0, n1 - n0)
    if (len < 1) return
    val angle = v.screenAngle(bearingOf(e0, n0, e1, n1))
    val hw = v.ft(4.0)
    val hl = v.ft(9.0)
    var d = 35.0
    while (d < len - 10) {
        val c = v.at(e0 + (e1 - e0) * d / len, n0 + (n1 - n0) * d / len)
        rotate(angle, c) {
            drawRect(inks.deckPaint, topLeft = Offset(c.x - hw, c.y - hl), size = Size(hw * 2, hl * 2), alpha = 0.9f)
        }
        d += 70.0
    }
}

/** A row of deck lights, one every thirty-seven feet — the spacing BMS lays them at. */
private fun DrawScope.deckLights(m: AfDeckMark, v: ChartView, inks: ChartInks) {
    val e0 = m.p[0].toDouble()
    val n0 = m.p[1].toDouble()
    val e1 = m.p[m.p.size - 2].toDouble()
    val n1 = m.p[m.p.size - 1].toDouble()
    val len = hypot(e1 - e0, n1 - n0)
    var d = 0.0
    while (d <= len) {
        val t = if (len > 0) d / len else 0.0
        drawCircle(inks.deckPaint, radius = max(0.8f, v.ft(1.6)), center = v.at(e0 + (e1 - e0) * t, n0 + (n1 - n0) * t), alpha = 0.8f)
        d += 37.0
    }
}

/** Text painted on the deck: centred on [at], [px] tall, the top of it towards [bearing]. */
private fun DrawScope.deckText(tm: TextMeasurer, text: String, at: Offset, px: Float, bearing: Double, v: ChartView, ink: Color, alpha: Float) {
    if (at.x < -px * 3 || at.y < -px * 3 || at.x > size.width + px * 3 || at.y > size.height + px * 3) return
    // a font's size is a little more than the height of its figures
    val style = TextStyle(color = ink.copy(alpha = alpha), fontSize = (px * 1.35f).toSp(), fontWeight = FontWeight.Bold)
    val layout = tm.measure(text, style)
    rotate(v.screenAngle(bearing), at) {
        drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
    }
}

/**
 * The plain deck an older chart carries: the landing area at its published angle, its centre line, the catapults
 * and the ski jump, all built from the published dimensions rather than the ship's model.
 */
private fun DrawScope.drawPlainDeck(ship: AfShip, v: ChartView, inks: ChartInks) {
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
            "tower" -> drawControlTower(f, c, v, inks)
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

/** Rings of east,north pairs in field feet as one even-odd path on the page. */
private fun ringsPath(rings: List<List<Int>>, v: ChartView): Path? {
    val path = Path()
    path.fillType = PathFillType.EvenOdd
    var any = false
    for (r in rings) {
        if (r.size < 6) continue
        path.moveTo(v.x(r[0].toDouble(), r[1].toDouble()), v.y(r[0].toDouble(), r[1].toDouble()))
        var i = 2
        while (i + 1 < r.size) { path.lineTo(v.x(r[i].toDouble(), r[i + 1].toDouble()), v.y(r[i].toDouble(), r[i + 1].toDouble())); i += 2 }
        path.close()
        any = true
    }
    return if (any) path else null
}

/**
 * A control tower, as the shape it has from above: its outline out of its own BMS model (or the model's stated box),
 * at its own position and heading, in the tower's violet with a dark rim and a shadow so it stands off the ground;
 * the shaft and the cab, where the tower stands over a building, solid on top. Too small on the page to show a
 * shape, it is a dot that still reads as the tower; the "TWR" plate is placed with the other labels ([towerLabels]).
 */
private fun DrawScope.drawControlTower(f: com.bmscompanion.app.data.airfield.AfFeature, c: Offset, v: ChartView, inks: ChartInks) {
    val outline = if (f.p.isNotEmpty()) ringsPath(f.p, v) else null
    val across = max(v.ft(f.w), v.ft(f.l))
    if (outline == null || across < 5f) {
        drawCircle(Color.Black, radius = max(3.5f, across / 2) + 1.2f, center = c, alpha = 0.45f)
        drawCircle(inks.controlTower, radius = max(3.5f, across / 2), center = c)
        return
    }
    val lift = max(1f, min(4f, v.ft(8.0)))
    translate(lift, lift) { drawPath(outline, Color.Black, alpha = 0.3f) }
    val top = if (f.top.isNotEmpty()) ringsPath(f.top, v) else null
    drawPath(outline, inks.controlTower, alpha = if (top != null) 0.55f else 1f)
    if (top != null) drawPath(top, inks.controlTower)
    drawPath(outline, lerp(inks.controlTower, Color.Black, 0.45f), style = Stroke(width = max(0.8f, v.ft(1.5)), join = StrokeJoin.Round))
}

/** Where a tower's middle is on the page: its outline's, or its position. */
private fun towerCentre(f: com.bmscompanion.app.data.airfield.AfFeature): Pair<Double, Double> {
    var se = 0.0; var sn = 0.0; var k = 0
    for (r in f.top.ifEmpty { f.p }) { var i = 0; while (i + 1 < r.size) { se += r[i]; sn += r[i + 1]; k++; i += 2 } }
    return if (k > 0) (se / k) to (sn / k) else f.e to f.n
}

/**
 * "TWR" beside each control tower, in the tower's violet. Placed by the [LabelBoard] like every other label, so it
 * steps aside for the spot numbers rather than covering them, with a leader back to the tower when it has to.
 */
private fun towerLabels(field: Airfield, v: ChartView, inks: ChartInks, labels: LabelBoard, labelScale: Float) {
    for (f in field.features) {
        if (f.k != "tower") continue
        val (e, n) = towerCentre(f)
        val c = v.at(e, n)
        val r = max(4f, max(v.ft(f.w), v.ft(f.l)) / 2)
        labels.add(
            ChartLabel(
                text = "TWR", anchor = c + Offset(0f, -(r + 9f)), away = Offset(0f, -1f),
                size = 9.5f * labelScale, ink = Color.White, fill = inks.controlTower,
                weight = FontWeight.Bold, leader = true, priority = 12,
            ),
        )
    }
}

/**
 * The arresting cables: a line across the runway where each lies, with the housings either side, in the amber of the
 * cable markers. Labelled "CABLE" once the runway is wide enough on the page to carry it.
 */
private fun DrawScope.drawCables(field: Airfield, v: ChartView, inks: ChartInks) {
    for (rwy in chartRunways(field)) {
        val a = rwy.ends.getOrNull(0) ?: continue
        val b = rwy.ends.getOrNull(1) ?: continue
        val brg = bearingOf(a.at.e, a.at.n, b.at.e, b.at.n) * kotlin.math.PI / 180.0
        val pe = cos(brg); val pn = -sin(brg)          // across the runway
        val half = rwy.widthFt / 2.0 + 18.0
        for (cab in rwy.cables) {
            val p = v.at(cab.e - pe * half, cab.n - pn * half)
            val q = v.at(cab.e + pe * half, cab.n + pn * half)
            drawLine(Color.Black, p, q, strokeWidth = max(2.4f, v.ft(9.0)), alpha = 0.35f)
            drawLine(inks.cable, p, q, strokeWidth = max(1.4f, v.ft(5.0)))
            val box = max(1.6f, v.ft(9.0))
            for (end in listOf(p, q)) drawRect(inks.cable, topLeft = Offset(end.x - box, end.y - box), size = Size(box * 2, box * 2))
        }
    }
}

private fun cableLabels(field: Airfield, v: ChartView, inks: ChartInks, labels: LabelBoard, labelScale: Float) {
    for (rwy in chartRunways(field)) {
        // only once the runway is wide enough on the page to be read across: at a whole field's zoom the amber lines
        // and the legend's distances say it, and four more plates were clutter
        if (rwy.cables.isEmpty() || v.ft(rwy.widthFt.toDouble()) < 10f) continue
        val a = rwy.ends.getOrNull(0) ?: continue
        val b = rwy.ends.getOrNull(1) ?: continue
        val brg = bearingOf(a.at.e, a.at.n, b.at.e, b.at.n) * kotlin.math.PI / 180.0
        val pe = cos(brg); val pn = -sin(brg)
        val out = rwy.widthFt / 2.0 + 60.0
        for (cab in rwy.cables) {
            val at = v.at(cab.e + pe * out, cab.n + pn * out)
            val dir = at - v.at(cab.e, cab.n)
            labels.add(
                ChartLabel(
                    text = "CABLE", anchor = at, away = dir, size = 8.5f * labelScale,
                    // below the spot numbers: a cable label steps aside, or is left off, rather than move a stand's
                    ink = inks.signInk, fill = inks.cable, weight = FontWeight.Bold, leader = true, priority = 9,
                ),
            )
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
        // The alert cell is red on BMS's own parking charts, and it is worth seeing from across the field rather
        // than only when the numbers are large enough to read: an unchosen alert stand is inked red as well.
        val edge = if (picked == null && spot.alert) inks.alert else null
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
                if (edge != null) drawPath(bay, edge, style = Stroke(width = v.ink(4.0, 0.9f)))
            } else {
                // Open ramp: a thin stand box, the way an airport chart draws one. Barely inked, so a whole apron
                // of them reads as a row of stands rather than as a grid drawn over the pavement.
                val tl = Offset(left, top)
                val sz = Size(half * 2, bottom - top)
                if (picked != null) drawRect(picked, topLeft = tl, size = sz)
                else drawRect(tl = tl, size = sz, ink = edge ?: inks.dim, w = v.ink(if (edge != null) 4.0 else 3.0, 0.45f), a = 0.9f)
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
    drawCircle(inks.you, radius = if (v.deck) max(5f, v.ft(40.0)) else max(8f, v.ft(120.0)), center = c, alpha = 0.22f)
    // On a deck the jet is drawn at its own size — an F-16 is fifty feet long — because there the chart knows where
    // it stands to within a few feet, and a symbol twice its size covers the wire or the catapult it is on.
    val len = if (v.deck) max(6f, v.ft(32.0)) else max(7f, v.ft(84.0))
    if (heading != null) aircraft(c, v.screenAngle(heading), inks.you, len, if (v.deck) Color.Black else inks.ground, v.deck)
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
