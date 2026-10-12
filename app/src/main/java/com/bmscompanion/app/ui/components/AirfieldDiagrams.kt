package com.bmscompanion.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.airfield.AfType
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.ui.screens.airportKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A theater map close in draws every field as its own ground chart instead of a symbol: the asphalt out of the
 * field's 3D models, the taxi network where BMS modelled none, the runways with their numbers, centre lines and
 * threshold bars, the taxiway letters where BMS puts its signs, and the shelters, hangars and buildings — the same
 * data the Taxi page draws (`assets/data/airfields/af-*.json`), at the field's own position and heading in the
 * theater's frame, scaling with the map.
 *
 * From [FROM_PX_PER_NM] (a 9,000 ft runway about 150 px long). Lazy throughout: a theater's list of fields is read the
 * first time a map comes that close, and a field's chart only while it is on screen; each chart is laid into paths in
 * the field's own feet once, and a frame only places them (one transform a field), so a pan costs a handful of path
 * draws. Carriers are left as they are (a ship moves). On Satellite the photograph already shows the pavement, so
 * only the runways' outlines, numbers and centre lines and the taxiway centre lines are drawn over it.
 *
 * Every map that draws its fields through [drawnAt] (the Reference map, `drawAirfield` of the Mission and Planner
 * maps) leaves out its symbol where a diagram was drawn this frame; their names stay.
 */
object AirfieldDiagrams {
    /** screen pixels a nautical mile from which a field is its diagram */
    const val FROM_PX_PER_NM = 100f

    fun shown(pr: MapProjection) = pr.pxPerNm >= FROM_PX_PER_NM

    /** the screen positions of the fields drawn as diagrams in the frame being drawn */
    private val drawn = ArrayList<Offset>()

    /** True when a field's diagram was drawn at [p] (its airport's position on the screen) in this frame. */
    fun drawnAt(p: Offset): Boolean {
        for (d in drawn) if (abs(d.x - p.x) < 40f && abs(d.y - p.y) < 40f) return true
        return false
    }

    /** how far each field in [drawn] reaches on the screen (px), in the same order */
    private val reach = ArrayList<Float>()

    /**
     * The screen position of the field whose diagram covers [tap] in the frame last drawn (the nearest when two
     * overlap), or null: a tap anywhere on a drawn field — a runway a mile from its reference point — is a tap on
     * that field (the Mission map's airfield card).
     */
    fun fieldAt(tap: Offset): Offset? {
        var best: Offset? = null
        var bestD = Float.MAX_VALUE
        for (i in drawn.indices) {
            val d = drawn[i]
            val dist = kotlin.math.hypot(d.x - tap.x, d.y - tap.y)
            if (dist <= reach.getOrElse(i) { 0f } && dist < bestD) { best = d; bestD = dist }
        }
        return best
    }

    internal fun begin() { drawn.clear(); reach.clear() }
    internal fun add(p: Offset, r: Float) { drawn += p; reach += r }
}

/** One field a theater's maps can draw: its chart file and the airport's position (theater feet). */
class DiagramField internal constructor(val file: String, val set: String, val airport: Int, val north: Double, val east: Double)

/** A theater map's diagrams: the field list (read when first needed) and the charts loaded so far. */
class DiagramSet internal constructor(val mapId: String) {
    internal var fields: List<DiagramField>? = null
    internal var asked by mutableStateOf(false)
    internal val shapes = mutableStateMapOf<String, Diagram>()
    internal val wanted = LinkedHashSet<String>()
    internal val loading = HashSet<String>()
    internal val failed = HashSet<String>()
}

@Composable
fun rememberAirfieldDiagrams(imagePath: String?): DiagramSet? {
    val id = mapIdOf(imagePath) ?: return null
    val set = remember(id) { DiagramSet(id) }
    LaunchedEffect(set) {
        snapshotFlow { set.asked }.first { it }
        set.fields = runCatching { fieldsOf(id) }.getOrNull().orEmpty()
        while (true) {
            val next = set.wanted.filter { it !in set.shapes && it !in set.loading && it !in set.failed }.take(3)
            for (file in next) {
                val f = set.fields?.firstOrNull { it.file == file } ?: continue
                set.loading += file
                launch {
                    val af = runCatching { Repo.airfield(f.set, f.airport) }.getOrNull()
                    val d = af?.takeIf { it.ship == null }?.let { runCatching { Diagram.of(it) }.getOrNull() }
                    set.loading -= file
                    if (d == null) set.failed += file else set.shapes[file] = d
                }
            }
            // keep what was drawn lately; a theater's worth of charts is a few megabytes of paths at most
            if (set.shapes.size > 48) set.shapes.keys.filter { it !in set.wanted }.take(set.shapes.size - 32).forEach { set.shapes.remove(it) }
            delay(if (next.isEmpty()) 120 else 30)
        }
    }
    return set
}

/** Every field with a chart among the theaters that fly on [mapId]'s terrain, one per chart file, carriers left out. */
private suspend fun fieldsOf(mapId: String): List<DiagramField> {
    val pairs = Repo.index().theaters.filter { mapIdOf(it.map) == mapId && it.airfieldSet != null }.map { it.airportSet to it.airfieldSet!! }.distinct()
    val seen = HashSet<String>()
    val out = ArrayList<DiagramField>()
    for ((ap, af) in pairs) {
        val index = Repo.airfieldIndex(af)
        for (a in Repo.airportSet(ap).airports) {
            val file = index[a.id.toString()] ?: continue
            if (!seen.add(file) || airportKind(a) == "Carrier") continue
            out += DiagramField(file, af, a.id, a.x, a.y)
        }
    }
    return out
}

/** A runway: its slab, and each end's designator and where it stands. */
internal class RunwayShape(val slab: Path, val ends: List<Triple<String, Double, Pair<Double, Double>>>, val length: Double)

/** One field's chart laid into paths in its own feet (x east, y north), once. */
internal class Diagram(
    val north: Double, val east: Double, val radiusFt: Double,
    val paved: Path?, val patches: Path?, val lanes: Path?, val stubs: Path?, val centre: Path?,
    val runways: List<RunwayShape>, val shelters: Path?, val hangars: Path?, val buildings: Path?,
    val signs: List<Triple<String, Double, Double>>,
) {
    companion object {
        private val WAYS = setOf(AfType.TAXI_START, AfType.TAXI, AfType.ON_RUNWAY, AfType.HOLD_SHORT)

        /** [edges] (pairs of point keys) as polylines: each run walked from a junction or an end through points shared by two. */
        private fun chain(edges: List<LongArray>, at: Map<Long, Pair<Float, Float>>): Path {
            val p = Path()
            val adj = HashMap<Long, MutableList<Int>>()
            edges.forEachIndexed { i, e -> adj.getOrPut(e[0]) { ArrayList(2) }.add(i); adj.getOrPut(e[1]) { ArrayList(2) }.add(i) }
            val used = BooleanArray(edges.size)
            fun walk(start: Long, first: Int) {
                val s = at[start] ?: return
                p.moveTo(s.first, s.second)
                var node = start
                var ei = first
                while (true) {
                    used[ei] = true
                    val e = edges[ei]
                    node = if (e[0] == node) e[1] else e[0]
                    val q = at[node] ?: break
                    p.lineTo(q.first, q.second)
                    val here = adj[node] ?: break
                    if (here.size != 2) break
                    ei = here.firstOrNull { !used[it] } ?: break
                }
            }
            for ((node, list) in adj) if (list.size != 2) for (ei in list) if (!used[ei]) walk(node, ei)
            for (i in edges.indices) if (!used[i]) walk(edges[i][0], i)
            return p
        }

        fun of(f: Airfield): Diagram {
            var reach = 3000.0
            fun see(e: Double, n: Double) { reach = max(reach, hypot(e, n)) }
            val paved = if (f.paved.isEmpty()) null else Path().apply {
                fillType = PathFillType.EvenOdd
                for (ring in f.paved) {
                    if (ring.size < 6) continue
                    moveTo(ring[0].toFloat(), ring[1].toFloat())
                    var i = 2
                    while (i + 1 < ring.size) { lineTo(ring[i].toFloat(), ring[i + 1].toFloat()); see(ring[i].toDouble(), ring[i + 1].toDouble()); i += 2 }
                    close()
                }
            }
            // The networks of the runway ends share most of their lanes: each lane once, and the lanes joined into
            // runs (a stroke of a few long polylines costs a fraction of thousands of round-capped segments)
            val at = HashMap<Long, Pair<Float, Float>>()
            val seen = HashSet<Long>()
            val laneEdges = ArrayList<LongArray>()
            val stubEdges = ArrayList<LongArray>()
            for (r in f.routes) {
                var i = 0
                while (i + 1 < r.ed.size) {
                    val ia = r.ed[i]; val ib = r.ed[i + 1]
                    i += 2
                    val a = r.nodes.getOrNull(ia) ?: continue
                    val b = r.nodes.getOrNull(ib) ?: continue
                    val ka = ((a.e.roundToInt() + 0x80000).toLong() shl 21) or (a.n.roundToInt() + 0x80000).toLong()
                    val kb = ((b.e.roundToInt() + 0x80000).toLong() shl 21) or (b.n.roundToInt() + 0x80000).toLong()
                    if (ka == kb || !seen.add(if (ka < kb) ka * 31 + kb else kb * 31 + ka)) continue
                    at[ka] = a.e.toFloat() to a.n.toFloat(); at[kb] = b.e.toFloat() to b.n.toFloat()
                    see(a.e, a.n); see(b.e, b.n)
                    (if (a.t in WAYS && b.t in WAYS) laneEdges else stubEdges) += longArrayOf(ka, kb)
                }
            }
            val lanes = chain(laneEdges, at)
            val stubs = chain(stubEdges, at)
            val centre = chain(laneEdges + stubEdges, at)
            val anyLane = laneEdges.isNotEmpty(); val anyStub = stubEdges.isNotEmpty(); val anyCentre = anyLane || anyStub
            val runways = chartRunways(f).filter { it.corners.size >= 4 }.map { rw ->
                val slab = Path()
                rw.corners.forEachIndexed { i, c -> if (i == 0) slab.moveTo(c.e.toFloat(), c.n.toFloat()) else slab.lineTo(c.e.toFloat(), c.n.toFloat()); see(c.e, c.n) }
                slab.close()
                RunwayShape(slab, rw.ends.map { Triple(it.designator, it.course, it.at.e to it.at.n) }, rw.lengthFt.toDouble())
            }
            fun boxes(kind: String): Path? {
                val p = Path()
                var any = false
                for (ft in f.features) {
                    if (ft.k != kind) continue
                    val th = ft.h * PI / 180.0
                    val cs = cos(th); val sn = sin(th)
                    val hw = ft.w / 2; val hl = ft.l / 2
                    val pts = listOf(-hw to -hl, hw to -hl, hw to hl, -hw to hl).map { (dx, dy) ->
                        // the chart turns a box clockwise on the page by its heading: page x east, page y south
                        val x = dx * cs - dy * sn
                        val y = dx * sn + dy * cs
                        (ft.e + x).toFloat() to (ft.n - y).toFloat()
                    }
                    p.moveTo(pts[0].first, pts[0].second)
                    for (k in 1..3) p.lineTo(pts[k].first, pts[k].second)
                    p.close()
                    any = true
                }
                return if (any) p else null
            }
            return Diagram(
                f.n, f.e, reach + 600.0, paved, boxes("pavement"), lanes.takeIf { anyLane }, stubs.takeIf { anyStub }, centre.takeIf { anyCentre },
                runways, boxes("shelter"), boxes("hangar"), boxes("building"),
                f.signs.map { Triple(it.l, it.e, it.n) },
            )
        }
    }
}

/** The inks a diagram is drawn in on a light map, a dark one, and over the satellite photograph. */
internal class DiagramInks(
    val pavement: Color, val edge: Color, val apron: Color, val runway: Color, val mark: Color, val centre: Color,
    val shelter: Color, val hangar: Color, val building: Color, val text: Color, val halo: Color,
)

private val LIGHT = ChartInks.day.let {
    DiagramInks(it.pavement, it.pavementEdge, it.apron, it.runway, it.runwayMark, it.centreline, it.shelter, it.hangar, it.building, Color(0xFF161B1F), Color.White)
}
private val DARK = DiagramInks(
    pavement = Color(0xFF5A646B), edge = Color(0xFF79848C), apron = Color(0xFF4C565D), runway = Color(0xFF1E252B), mark = Color(0xFFDDE4E7),
    centre = Color(0xFFE2C64A), shelter = Color(0xFF6E8676), hangar = Color(0xFF7D6B53), building = Color(0xFF5A6774), text = Color.White, halo = Color.Black,
)
private val PHOTO = DARK

/** Labels' styles, one per size step (MapText keys on the style). */
private val runwayStyles = HashMap<Int, TextStyle>()

private fun runwayStyle(px: Int, ink: DiagramInks, density: Float): TextStyle =
    runwayStyles.getOrPut(px * 4 + (if (ink === LIGHT) 0 else 1) + (density * 100).roundToInt() * 1000) {
        TextStyle(color = ink.text, fontSize = (px / density).sp, fontWeight = FontWeight.Bold, shadow = androidx.compose.ui.graphics.Shadow(ink.halo, blurRadius = 3f))
    }

private val signStyle = TextStyle(color = Color(0xFF1A1A12), fontSize = 10.sp, fontWeight = FontWeight.Bold)


/**
 * Draws the diagrams of the fields on screen (from [AirfieldDiagrams.FROM_PX_PER_NM]); before a map's own overlay.
 * [layer] (Android) keeps the filled and stroked part as a picture between frames ([DiagramLayer]); the runway
 * numbers, centre-line dashes, threshold bars and taxiway letters are drawn on top each frame in screen pixels.
 */
fun DrawScope.drawAirfieldDiagrams(pr: MapProjection, set: DiagramSet, tm: TextMeasurer, style: String, layer: DiagramLayer? = null) {
    AirfieldDiagrams.begin()
    if (!AirfieldDiagrams.shown(pr)) return
    if (!set.asked) set.asked = true
    val fields = set.fields ?: return
    set.wanted.clear()
    val k = (pr.side / pr.sizeFt).toFloat() // px a foot
    val photo = style == "satellite"
    val ink = if (MapLook.isLight(style)) LIGHT else if (photo) PHOTO else DARK
    val shown = ArrayList<Diagram>()
    for (f in fields) {
        val at = pr.toScreen(f.north, f.east)
        val shape = set.shapes[f.file]
        val r = ((shape?.radiusFt ?: 15000.0) * k).toFloat()
        if (at.x + r < 0f || at.x - r > size.width || at.y + r < 0f || at.y - r > size.height) continue
        set.wanted += f.file
        if (shape == null) continue
        shown += shape
        AirfieldDiagrams.add(at, r)
    }
    if (shown.isEmpty()) return
    if (layer == null || !layer.draw(this, pr, DiagramLayer.Key(shown.toList(), style, density))) drawDiagramShapes(pr, shown, ink, photo)
    for (shape in shown) drawDiagramMarks(pr, shape, ink, tm)
}

/** The asphalt, lanes, buildings, runway slabs and centre lines of [shown]: in the fields' own feet, one transform each. */
private fun DrawScope.drawDiagramShapes(pr: MapProjection, shown: List<Diagram>, ink: DiagramInks, photo: Boolean) {
    val k = (pr.side / pr.sizeFt).toFloat()
    val px = 1f / k // a pixel, in feet
    for (shape in shown) {
        val o = pr.toScreen(shape.north, shape.east)
        withTransform({ translate(o.x, o.y); scale(k, -k, Offset.Zero) }) {
            if (!photo) {
                shape.patches?.let { drawPath(it, ink.pavement) }
                shape.lanes?.let {
                    // a rim for the lanes only where the field has no modelled asphalt (which carries its own edge)
                    if (shape.paved == null) drawPath(it, ink.edge, style = Stroke(max(104f, 2.5f * px), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(it, ink.pavement, style = Stroke(max(86f, 1.5f * px), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                shape.paved?.let { drawPath(it, ink.pavement); drawPath(it, ink.edge, style = Stroke(px), alpha = 0.7f) }
                shape.stubs?.let { drawPath(it, ink.apron, style = Stroke(max(56f, 1.5f * px), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                if (60f * k > 2f) {
                    shape.buildings?.let { drawPath(it, ink.building, alpha = 0.9f) }
                    shape.hangars?.let { drawPath(it, ink.hangar) }
                    shape.shelters?.let { drawPath(it, ink.shelter, alpha = 0.9f) }
                }
            }
            for (rw in shape.runways) {
                if (!photo) drawPath(rw.slab, ink.runway)
                drawPath(rw.slab, ink.mark, style = Stroke(max(if (photo) 2f * px else 1.2f * px, 4f)), alpha = if (photo) 0.9f else 0.6f)
            }
            shape.centre?.let { drawPath(it, ink.centre, style = Stroke(max(12f, 0.9f * px), cap = StrokeCap.Round, join = StrokeJoin.Round), alpha = if (photo) 0.75f else 0.85f) }
        }
    }
}

/** A field's runway centre-line dashes, threshold bars and numbers and its taxiway letters, in screen pixels. */
private fun DrawScope.drawDiagramMarks(pr: MapProjection, shape: Diagram, ink: DiagramInks, tm: TextMeasurer) {
    val k = (pr.side / pr.sizeFt).toFloat()
    for (rw in shape.runways) {
        if (rw.ends.size < 2) continue
        val (_, _, a) = rw.ends[0]
        val (_, _, b) = rw.ends[1]
        val pa = pr.toScreen(shape.north + a.second, shape.east + a.first)
        val pb = pr.toScreen(shape.north + b.second, shape.east + b.first)
        drawDashedLine(ink.mark.copy(alpha = 0.85f), pa, pb, max(1f, 9f * k), floatArrayOf(max(3f, 120f * k), max(3f, 80f * k)))
        val len = hypot((pb.x - pa.x).toDouble(), (pb.y - pa.y).toDouble()).toFloat()
        if (len < 1f) continue
        val ux = (pb.x - pa.x) / len; val uy = (pb.y - pa.y) / len
        for ((i, end) in rw.ends.withIndex()) {
            val p0 = if (i == 0) pa else pb
            val sx = if (i == 0) ux else -ux; val sy = if (i == 0) uy else -uy // into the runway
            // threshold bars: six 17 ft apart, 300 ft long, from 130 ft in, where the field is drawn large enough
            if (17f * k > 2.5f) for (j in -3..3) {
                if (j == 0) continue
                val ox = -sy * j * 17f * k; val oy = sx * j * 17f * k
                drawLine(ink.mark.copy(alpha = 0.8f), Offset(p0.x + ox + sx * 130f * k, p0.y + oy + sy * 130f * k), Offset(p0.x + ox + sx * 430f * k, p0.y + oy + sy * 430f * k), max(1f, 11f * k))
            }
            // the number, painted reading up the runway from its threshold (as a pilot landing on it reads it),
            // once a runway is wide enough on the page for the numbers of parallel strips to stay apart
            if (150f * k < 4.5f) continue
            val sizePx = (70f * k).coerceIn(10f * density, 30f * density).roundToInt()
            val l = MapText.label(this, tm, end.first, runwayStyle(sizePx, ink, density))
            val inset = max(560f * k, l.height * 0.9f + 6f)
            val c = Offset(p0.x + sx * inset, p0.y + sy * inset)
            if (c.x < -60f || c.y < -60f || c.x > size.width + 60f || c.y > size.height + 60f) continue
            val angle = (atan2(sx.toDouble(), -sy.toDouble()) * 180.0 / PI).toFloat()
            rotate(angle, c) { MapText.draw(this, l, Offset(c.x - l.width / 2f, c.y - l.height / 2f)) }
        }
    }
    // the taxiway letters where BMS puts its own signs: black on yellow, as on the field
    if (pr.pxPerNm >= 380f) for ((letter, e, n) in shape.signs) {
        if (letter.isBlank()) continue
        val c = pr.toScreen(shape.north + n, shape.east + e)
        if (c.x < -20f || c.y < -20f || c.x > size.width + 20f || c.y > size.height + 20f) continue
        val l = MapText.label(this, tm, letter, signStyle)
        val w = l.width + 6f; val h = l.height + 2f
        drawRoundRect(Color(0xFFE8C22A), Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(2f, 2f))
        MapText.draw(this, l, Offset(c.x - l.width / 2f, c.y - l.height / 2f))
    }
}

/**
 * The diagrams' fills and strokes kept as a picture on the GPU between frames, as [LandmarkLayer] keeps the borders:
 * a phone's GPU draws a large stroked path as a mask made again every frame, and a field's taxi network is thousands
 * of feet of stroke. Recorded a margin larger than the view; a pan moves the picture, a zoom draws it scaled until the
 * scale is a quarter off or the zoom rests ([settled], bumped by the map), and a field coming or going records it
 * again. Android only: the PC and the browser (Skia) draw the shapes directly.
 */
class DiagramLayer(private val layer: androidx.compose.ui.graphics.layer.GraphicsLayer) {
    init { layer.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen }

    /** what was recorded: the fields (by identity), the style and the density */
    class Key internal constructor(internal val shown: List<Diagram>, internal val style: String, internal val density: Float) {
        override fun equals(other: Any?) = other is Key && other.style == style && other.density == density && other.shown.size == shown.size &&
            other.shown.indices.all { other.shown[it] === shown[it] }
        override fun hashCode() = style.hashCode() * 31 + shown.size
    }

    /** bumped by the map when a zoom has come to rest */
    var settled = 0
    private var seenSettled = 0
    private var key: Key? = null
    private var side = 0f
    private var ox = 0f
    private var oy = 0f
    private var w = 0
    private var h = 0

    /** Draws the shapes for [pr]; false when it cannot (the picture would be too large), and the caller draws them. */
    internal fun draw(scope: DrawScope, pr: MapProjection, k: Key): Boolean = with(scope) {
        val f = if (side > 0f) pr.side / side else 0f
        val rest = settled != seenSettled
        seenSettled = settled
        val vx0 = -pr.left; val vy0 = -pr.top
        val vx1 = size.width - pr.left; val vy1 = size.height - pr.top
        val covers = f * ox <= vx0 + 0.5f && f * oy <= vy0 + 0.5f && f * (ox + w) >= vx1 - 0.5f && f * (oy + h) >= vy1 - 0.5f
        val fresh = k == key && f in 0.8f..1.25f && covers && !(rest && f != 1f)
        if (!fresh) {
            val m = if (Repo.lowMemory) 128f else 256f
            val x0 = floor(vx0 - m); val y0 = floor(vy0 - m)
            val ww = ceil(vx1 + m - x0).toInt(); val hh = ceil(vy1 + m - y0).toInt()
            if (ww > 4096 || hh > 4096) { key = null; return false }
            side = pr.side; ox = x0; oy = y0; w = ww; h = hh
            val ink = if (MapLook.isLight(k.style)) LIGHT else if (k.style == "satellite") PHOTO else DARK
            val p0 = MapProjection(-ox, -oy, pr.side, pr.sizeFt, pr.scale)
            layer.record(androidx.compose.ui.unit.Density(density, fontScale), layoutDirection, androidx.compose.ui.unit.IntSize(w, h)) {
                drawDiagramShapes(p0, k.shown, ink, k.style == "satellite")
            }
            key = k
        }
        val s = pr.side / side
        val tx = pr.left + s * ox
        val ty = pr.top + s * oy
        if (s == 1f) translate(round(tx), round(ty)) { drawLayer(layer) }
        else withTransform({ translate(tx, ty); scale(s, s, Offset.Zero) }) { drawLayer(layer) }
        true
    }
}
