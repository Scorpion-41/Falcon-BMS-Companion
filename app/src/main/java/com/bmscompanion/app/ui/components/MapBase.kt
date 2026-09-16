package com.bmscompanion.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.GeoLayers
import com.bmscompanion.app.data.GeoLine
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The base of every theater map: the chosen map style (with tile levels while zooming in) and the landmark layers
// (country and province borders, country/region labels, towns). All styles and layers share the campaign grid of the
// theater, so switching styles never moves anything.

/** Map look, shared by every map and remembered across launches. */
object MapLook {
    val styles = listOf("relief" to "Relief", "satellite" to "Satellite", "dark" to "Dark", "chart" to "Chart")

    var style by mutableStateOf(Repo.getString("map_style")?.takeIf { s -> styles.any { it.first == s } } ?: "relief")
        private set
    var borders by mutableStateOf(Repo.getInt("map_borders", 1) == 1)
        private set
    var provinces by mutableStateOf(Repo.getInt("map_provinces", 1) == 1)
        private set
    /** 0 = off, 1 = cities, 2 = cities and towns, 3 = all (smaller places appear as you zoom in), [MISSION] = only the mission's towns */
    var places by mutableIntStateOf(Repo.getInt("map_places", MISSION))
        private set

    fun chooseStyle(s: String) { style = s; Repo.putString("map_style", s) }
    fun showBorders(on: Boolean) { borders = on; Repo.putInt("map_borders", if (on) 1 else 0) }
    fun showProvinces(on: Boolean) { provinces = on; Repo.putInt("map_provinces", if (on) 1 else 0) }
    fun showPlaces(level: Int) { places = level.coerceIn(0, MISSION); Repo.putInt("map_places", places) }

    val light get() = style == "chart"
    const val MISSION = 4
    /** menu order: value to label */
    val placeOptions = listOf(0 to "Off", MISSION to "Mission", 1 to "Cities", 2 to "Cities and towns", 3 to "All places")
}

/**
 * The mission on the map, for Towns → Mission: the briefing text (every town it names counts) and the positions it
 * uses (steerpoints, targets, threats, airbases: the nearest town to each counts), plus the route (cities and towns
 * right beside it count). The target town (named in [targetText], e.g. "S-60 AAA above Tirana", or nearest to a
 * [targets] point) is drawn distinctly. Set by the Mission section; [mapId] keeps it off other theaters' maps.
 */
class MapMission(
    val mapId: String?,
    val text: String,
    val points: List<Pair<Double, Double>>,
    val route: List<Pair<Double, Double>>,
    val targetText: String = "",
    val targets: List<Pair<Double, Double>> = emptyList(),
) {
    override fun equals(other: Any?) = other is MapMission && other.mapId == mapId && other.text == text && other.points == points &&
        other.route == route && other.targetText == targetText && other.targets == targets
    override fun hashCode() = text.hashCode() * 31 + points.hashCode()
}

/** [GeoPaths.relevant] values */
const val PLACE_OTHER = 0
const val PLACE_MISSION = 1
const val PLACE_TARGET = 2

object MapFocus {
    var mission by mutableStateOf<MapMission?>(null)
}

/** "maps/korea/relief.webp" or the older "maps/korea.webp" → "korea". */
fun mapIdOf(imagePath: String?): String? = imagePath?.removePrefix("maps/")?.substringBefore('/')?.substringBefore('.')?.takeIf { it.isNotBlank() && imagePath.startsWith("maps/") }

// ------------------------------------------------------------------ tiles

private const val TILE = 512
/** Tile levels bundled with the app (tools/extractor/src/maps.mjs): z2 = 2048 px across the theater … z4 = 8192 px. */
/** tiles held in memory: one screen needs about a dozen, the rest is head-room for panning */
private const val MAX_TILES = 32
/** half-size tiles on devices with little memory (a quarter of the pixels) */
internal val tileSample get() = if (Repo.lowMemory) 2 else 1
private const val MIN_Z = 2
private const val MAX_Z = 4

/** Loaded images of one map, and the tiles the last frame asked for. */
class MapBaseState(val mapId: String?) {
    val images = mutableStateMapOf<String, ImageBitmap>()
    internal val wanted = LinkedHashSet<String>()
    internal val loading = HashSet<String>()
    internal val failed = HashSet<String>()
    var lastOverview: ImageBitmap? = null
}

@Composable
fun rememberMapBase(imagePath: String?): MapBaseState {
    val id = mapIdOf(imagePath)
    val state = remember(id) { MapBaseState(id) }
    LaunchedEffect(state) {
        // load what the frames asked for, a few at a time, newest request first
        while (true) {
            val next = state.wanted.reversed().filter { it !in state.images && it !in state.loading && it !in state.failed }.take(4 - state.loading.size)
            for (key in next) {
                state.loading += key
                launch {
                    // the same bitmap object is shared with Repo's cache, so this costs nothing extra and avoids re-decoding
                    val bmp = Repo.bitmap(key, sample = tileSample)
                    state.loading -= key
                    if (bmp == null) state.failed += key else state.images[key] = bmp.asImageBitmap()
                }
            }
            // keep memory bounded: drop tiles the last frame didn't need (a full view needs about a dozen)
            if (state.images.size > MAX_TILES) state.images.keys.filter { it !in state.wanted && it.count { ch -> ch == 0x2F.toChar() } > 2 }.take(state.images.size - MAX_TILES / 2).forEach { state.images.remove(it) }
            delay(if (next.isEmpty()) 80 else 16)
        }
    }
    return state
}

/** Draws the style's overview image and, when zoomed in, the sharper tiles of the visible area. */
fun DrawScope.drawMapBase(pr: MapProjection, base: MapBaseState, fallbackImage: ImageBitmap?) {
    val id = base.mapId
    val style = MapLook.style
    val overviewKey = if (id != null) "maps/$id/$style.webp" else null
    val wanted = base.wanted
    wanted.clear()
    val overview = overviewKey?.let { base.images[it] } ?: base.lastOverview ?: fallbackImage
    if (overviewKey != null && overviewKey !in base.images) wanted += overviewKey
    overview?.let {
        if (overviewKey != null && base.images[overviewKey] != null) base.lastOverview = it
        drawImage(it, dstOffset = IntOffset(pr.left.roundToInt(), pr.top.roundToInt()), dstSize = IntSize(pr.side.roundToInt(), pr.side.roundToInt()))
    }
    if (id == null || pr.side < 1300f) return
    if (pr.left + pr.side < 0 || pr.top + pr.side < 0 || pr.left > size.width || pr.top > size.height) return
    val target = ceil(ln(pr.side / TILE.toDouble()) / ln(2.0)).toInt().coerceIn(MIN_Z, MAX_Z)
    for (z in MIN_Z..target) {
        val count = 1 shl z
        val ts = pr.side / count
        val c0 = floor((-pr.left) / ts).toInt().coerceIn(0, count - 1)
        val c1 = floor((size.width - pr.left) / ts).toInt().coerceIn(0, count - 1)
        val r0 = floor((-pr.top) / ts).toInt().coerceIn(0, count - 1)
        val r1 = floor((size.height - pr.top) / ts).toInt().coerceIn(0, count - 1)
        for (r in r0..r1) for (c in c0..c1) {
            val key = "maps/$id/$style/$z/${r}_$c.webp"
            val img = base.images[key]
            if (img == null) { if (z == target) wanted += key; continue }
            if (z == target) wanted += key // still needed: keep it cached
            val x0 = (pr.left + c * ts).roundToInt(); val x1 = (pr.left + (c + 1) * ts).roundToInt()
            val y0 = (pr.top + r * ts).roundToInt(); val y1 = (pr.top + (r + 1) * ts).roundToInt()
            drawImage(img, dstOffset = IntOffset(x0, y0), dstSize = IntSize(x1 - x0, y1 - y0))
        }
    }
}

// ------------------------------------------------------------------ landmarks

/** Border and province lines as paths in unit space (0..1 across the theater, y down), built once per map. */
class GeoPaths(val mapId: String?, val geo: GeoLayers, private val sizeFt: Double) {
    private fun path(lines: List<GeoLine>, hi: Boolean, filter: (GeoLine) -> Boolean = { true }) = Path().apply {
        for (l in lines) {
            if (!filter(l)) continue
            val pts = if (hi) l.hi else l.lo
            if (pts.size < 4) continue
            for (i in pts.indices step 2) {
                val u = (pts[i + 1] / sizeFt).toFloat()
                val v = (1 - pts[i] / sizeFt).toFloat()
                if (i == 0) moveTo(u, v) else lineTo(u, v)
            }
        }
    }
    val bordersLo = path(geo.borders, false) { it.d == 0 }
    val bordersHi = path(geo.borders, true) { it.d == 0 }
    val disputedLo = path(geo.borders, false) { it.d != 0 }
    val disputedHi = path(geo.borders, true) { it.d != 0 }
    val provincesLo = path(geo.provinces, false)
    val provincesHi = path(geo.provinces, true)
    /** cities first, so the most important names win when labels compete for space */
    val places = geo.places.sortedBy { placeLevel(it.t) }

    private var relevantFor: MapMission? = null
    private var relevantFlags = IntArray(0)

    /** How each of [places] matters for [m] ([PLACE_OTHER], [PLACE_MISSION], [PLACE_TARGET]; index-aligned), computed once per mission update. */
    fun relevant(m: MapMission): IntArray {
        if (m === relevantFor) return relevantFlags
        val flags = IntArray(places.size)
        val nm = 6076.12
        val names = places.map { words(it.n) }
        fun named(text: String, value: Int) {
            val t = " " + words(text) + " "
            names.forEachIndexed { i, n -> if (n.length >= 4 && t.contains(" $n ")) flags[i] = max(flags[i], value) }
        }
        /** the nearest town to (x, y) within 14 nm; cities count as a little nearer, being better references */
        fun nearest(x: Double, y: Double): Int {
            var best = -1
            var bestD = 14 * nm
            places.forEachIndexed { i, p ->
                val d = hypot(p.x - x, p.y - y) * when (placeLevel(p.t)) { 1 -> 0.7; 2 -> 1.0; else -> 1.4 }
                if (d < bestD) { bestD = d; best = i }
            }
            return best
        }
        // named in the briefing ("4 nm south of Tirana", "Larissa Tower", "push towards Gjirokastra")
        named(m.text, PLACE_MISSION)
        m.points.forEach { (x, y) -> nearest(x, y).takeIf { it >= 0 }?.let { flags[it] = max(flags[it], PLACE_MISSION) } }
        // cities within 5 nm and towns within 2.5 nm of a route leg
        for (k in 0 until m.route.size - 1) {
            val (ax, ay) = m.route[k]; val (bx, by) = m.route[k + 1]
            places.forEachIndexed { i, p ->
                val limit = when (placeLevel(p.t)) { 1 -> 5 * nm; 2 -> 2.5 * nm; else -> return@forEachIndexed }
                if (flags[i] == PLACE_OTHER && segmentDistance(p.x, p.y, ax, ay, bx, by) < limit) flags[i] = PLACE_MISSION
            }
        }
        // the target town: named in the target area or task, else the nearest town to each target point
        named(m.targetText, PLACE_TARGET)
        if (flags.none { it == PLACE_TARGET }) m.targets.forEach { (x, y) -> nearest(x, y).takeIf { it >= 0 }?.let { flags[it] = PLACE_TARGET } }
        relevantFor = m
        relevantFlags = flags
        return flags
    }
}

private fun words(s: String) = buildString {
    var space = true
    for (ch in s) {
        if (ch.isLetterOrDigit()) { append(ch.lowercaseChar()); space = false } else if (!space) { append(' '); space = true }
    }
}.trim()

private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

private fun placeLevel(type: String) = when (type) { "city" -> 1; "town" -> 2; else -> 3 }

@Composable
fun rememberGeo(imagePath: String?, sizeFt: Double): GeoPaths? {
    val id = mapIdOf(imagePath)
    val geo by produceState<GeoPaths?>(null, id, sizeFt) { value = id?.let { Repo.geo(it) }?.let { GeoPaths(id, it, sizeFt) } }
    return geo
}

private val labelShadow = Shadow(Color.Black.copy(alpha = 0.85f), blurRadius = 4f)

/** Country and province borders, country and region names, and towns, drawn faintly so the map stays readable. */
fun DrawScope.drawLandmarks(pr: MapProjection, g: GeoPaths, tm: TextMeasurer) {
    val light = MapLook.light
    val photo = MapLook.style == "satellite" // busy imagery: a little more contrast for the faint layers
    val hi = pr.side > 3200f
    val unit = 1f / pr.side
    val dp = density
    fun lines(path: Path, color: Color, widthDp: Float, dash: FloatArray? = null) = withTransform({ translate(pr.left, pr.top); scale(pr.side, pr.side, Offset.Zero) }) {
        drawPath(path, color, style = Stroke(widthDp * dp * unit, pathEffect = dash?.let { d -> PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * dp * unit }) }))
    }
    val placed = ArrayList<Rect>()
    /** Draws a label unless it would leave the view or crowd a label already placed (with some breathing room). */
    fun label(text: String, center: Offset, style: TextStyle, maxWidthPx: Int = Int.MAX_VALUE, gapDp: Float = 6f): Boolean {
        if (center.x < 0 || center.y < 0 || center.x > size.width || center.y > size.height) return false
        val layout = tm.measure(text, style, constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxWidthPx.coerceAtLeast(40)))
        val r = Rect(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f, center.x + layout.size.width / 2f, center.y + layout.size.height / 2f)
        if (r.left < 0 || r.top < 0 || r.right > size.width || r.bottom > size.height) return false
        val gap = gapDp * dp
        val padded = Rect(r.left - gap, r.top - gap / 2, r.right + gap, r.bottom + gap / 2)
        if (placed.any { it.overlaps(padded) }) return false
        placed += r
        drawText(layout, topLeft = r.topLeft)
        return true
    }

    // provinces: thin, dashed, faint, and only once zoomed in a little
    if (MapLook.provinces && pr.side >= 1400f) {
        lines(if (hi) g.provincesHi else g.provincesLo, if (light) Color(0xFF6E5D78).copy(alpha = 0.5f) else Color.White.copy(alpha = if (photo) 0.42f else 0.28f), if (photo) 1.2f else 1f, floatArrayOf(5f, 4f))
    }
    // international borders: a casing for contrast, then a clear line; disputed lines dashed
    if (MapLook.borders) {
        val casing = if (light) Color.White.copy(alpha = 0.75f) else Color.Black.copy(alpha = 0.5f)
        val main = if (light) Color(0xFF5B3F6B) else Color(0xFFF7E7C6).copy(alpha = 0.9f)
        lines(if (hi) g.bordersHi else g.bordersLo, casing, 4.6f)
        lines(if (hi) g.bordersHi else g.bordersLo, main, 2.2f)
        lines(if (hi) g.disputedHi else g.disputedLo, casing, 3.6f, floatArrayOf(7f, 5f))
        lines(if (hi) g.disputedHi else g.disputedLo, main, 1.6f, floatArrayOf(7f, 5f))
    }

    // country names: large, spaced, faint
    if (MapLook.borders) for (cn in g.geo.countries) {
        val onScreenPx = cn.r * pr.pxPerNm
        if (onScreenPx < 70f) continue
        val sp = (onScreenPx / 22f).coerceIn(12f, 22f)
        label(cn.n.uppercase(), pr.toScreen(cn.x, cn.y), TextStyle(
            color = (if (light) Color(0xFF4A3A55) else Color.White).copy(alpha = if (light) 0.5f else 0.45f),
            fontSize = sp.sp, fontWeight = FontWeight.Bold, letterSpacing = (sp / 5f).sp, textAlign = TextAlign.Center, shadow = if (light) null else labelShadow,
        ), maxWidthPx = (onScreenPx * 1.2f).toInt())
    }

    // towns: a faint dashed ring with a transparent fill, names by importance as you zoom in
    // (Mission: only the mission's towns, at every zoom; without a mission for this map it shows cities)
    val placeColor = if (light) Color(0xFF3F3122) else Color(0xFFFFE6A8)
    if (MapLook.places > 0) {
        var labels = 0
        val maxLabels = 90
        val relevant = if (MapLook.places == MapLook.MISSION) MapFocus.mission?.takeIf { it.mapId == g.mapId }?.let { g.relevant(it) } else null
        val maxLevel = if (MapLook.places == MapLook.MISSION) 1 else MapLook.places
        val targetColor = if (light) Color(0xFFB4441A) else Hud.Amber
        // target towns first, so their names always get a place
        val order = if (relevant == null) g.places.indices.toList() else g.places.indices.sortedByDescending { relevant[it] }
        for (i in order) {
            val p = g.places[i]
            val level = placeLevel(p.t)
            if (relevant != null && relevant[i] == PLACE_TARGET) {
                val c = pr.toScreen(p.x, p.y)
                if (c.x < -60 || c.y < -60 || c.x > size.width + 60 || c.y > size.height + 60) continue
                val rPx = max((when (level) { 1 -> 9000.0; 2 -> 4200.0; else -> 1900.0 } / 6076.12 * pr.pxPerNm).toFloat(), 13f * dp)
                // solid amber ring with a soft fill and a small target mark
                drawCircle(targetColor.copy(alpha = 0.16f), rPx, c)
                drawCircle(Color.Black.copy(alpha = if (light) 0.25f else 0.55f), rPx, c, style = Stroke(3.6f * dp))
                drawCircle(targetColor, rPx, c, style = Stroke(2f * dp))
                val tick = min(rPx * 0.45f, 7f * dp)
                for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
                    drawLine(targetColor, c + Offset(dx * (rPx - tick), dy * (rPx - tick)), c + Offset(dx * (rPx + tick * 0.6f), dy * (rPx + tick * 0.6f)), strokeWidth = 2f * dp)
                }
                // name above the ring: the mission symbols (target steerpoint, contacts) sit on it and label to the right
                label(p.n.uppercase(), c - Offset(0f, rPx + 11f * dp), TextStyle(
                    color = targetColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    shadow = Shadow(if (light) Color.White else Color.Black, blurRadius = if (light) 3f else 5f),
                ), gapDp = 4f)
                labels++
                continue
            }
            if (relevant != null) {
                if (relevant[i] == PLACE_OTHER) continue
            } else {
                if (level > maxLevel) continue
                val minScale = when (level) { 1 -> 1f; 2 -> 1.8f; else -> 3.5f }
                if (pr.scale < minScale) continue
            }
            val c = pr.toScreen(p.x, p.y)
            if (c.x < -40 || c.y < -40 || c.x > size.width + 40 || c.y > size.height + 40) continue
            val radiusFt = when (level) { 1 -> 9000.0; 2 -> 4200.0; else -> 1900.0 }
            val rPx = (radiusFt / 6076.12 * pr.pxPerNm).toFloat()
            val ring = rPx >= 5f
            if (ring) {
                drawCircle(placeColor.copy(alpha = if (light) 0.06f else if (photo) 0.1f else 0.07f), rPx, c)
                drawCircle(placeColor.copy(alpha = if (light) 0.4f else if (photo) 0.55f else 0.32f), rPx, c, style = Stroke((if (photo) 1.3f else 1f) * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * dp, 3f * dp))))
            }
            if (labels < maxLabels) {
                val style = TextStyle(
                    color = placeColor.copy(alpha = when (level) { 1 -> 0.92f; 2 -> 0.8f; else -> 0.7f }),
                    fontSize = when (level) { 1 -> 11.sp; 2 -> 10.sp; else -> 9.sp },
                    fontWeight = if (level == 1) FontWeight.SemiBold else FontWeight.Normal,
                    shadow = if (light) null else labelShadow,
                )
                if (label(p.n, c + Offset(0f, max(rPx, 4f * dp) + 7f * dp), style, gapDp = if (ring) 4f else 10f)) {
                    labels++
                    if (!ring) drawCircle(placeColor.copy(alpha = 0.6f), (if (level == 1) 2.6f else 1.8f) * dp, c)
                }
            }
        }
    }

    // region (province, governorate) names: small, italic, only when the region is large on screen
    if (MapLook.provinces) for (r in g.geo.regions) {
        val onScreenPx = r.r * pr.pxPerNm
        if (onScreenPx < 110f || onScreenPx > 2600f) continue
        label(r.n, pr.toScreen(r.x, r.y), TextStyle(
            color = (if (light) Color(0xFF5F5066) else Color.White).copy(alpha = if (light) 0.55f else 0.42f),
            fontSize = 10.sp, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, shadow = if (light) null else labelShadow,
        ), maxWidthPx = (onScreenPx * 0.8f).toInt())
    }
}

// ------------------------------------------------------------------ controls

/** + and − buttons for maps (browsers and mice without pinch or wheel); [onZoom] gets the zoom factor. */
@Composable
fun MapZoomButtons(onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Hud.Surface.copy(alpha = 0.92f)).border(1.dp, Hud.Outline.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
    ) {
        ZoomKey("+", "Zoom in") { onZoom(1.6f) }
        Box(Modifier.width(36.dp).height(1.dp).background(Hud.Outline.copy(alpha = 0.7f)))
        ZoomKey("−", "Zoom out") { onZoom(1 / 1.6f) }
    }
}

@Composable
private fun ZoomKey(text: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Hud.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
}

/** A "Map" chip that opens the map style and landmark options. */
@Composable
fun MapLookButton() {
    var open by remember { mutableStateOf(false) }
    Box {
        HudChip("Map: ${MapLook.styles.first { it.first == MapLook.style }.second} ▾", open) { open = !open }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 230.dp)) {
            MenuHeader("MAP STYLE")
            MapLook.styles.forEach { (key, name) ->
                MenuRow(onClick = { MapLook.chooseStyle(key) }) {
                    RadioButton(MapLook.style == key, onClick = { MapLook.chooseStyle(key) }, colors = RadioButtonDefaults.colors(selectedColor = Hud.Amber))
                    Text(name, color = Hud.Text, fontSize = 14.sp)
                }
            }
            HorizontalDivider(color = Hud.Outline.copy(alpha = 0.6f))
            MenuHeader("LANDMARKS")
            MenuRow(onClick = { MapLook.showBorders(!MapLook.borders) }) {
                Checkbox(MapLook.borders, { MapLook.showBorders(it) }, colors = CheckboxDefaults.colors(checkedColor = Hud.Amber))
                Text("Country borders and names", color = Hud.Text, fontSize = 14.sp)
            }
            MenuRow(onClick = { MapLook.showProvinces(!MapLook.provinces) }) {
                Checkbox(MapLook.provinces, { MapLook.showProvinces(it) }, colors = CheckboxDefaults.colors(checkedColor = Hud.Amber))
                Text("Provinces and governorates", color = Hud.Text, fontSize = 14.sp)
            }
            MenuHeader("TOWNS")
            MapLook.placeOptions.forEach { (i, name) ->
                MenuRow(onClick = { MapLook.showPlaces(i) }) {
                    RadioButton(MapLook.places == i, onClick = { MapLook.showPlaces(i) }, colors = RadioButtonDefaults.colors(selectedColor = Hud.Amber))
                    Column(Modifier.padding(vertical = if (i == MapLook.MISSION) 4.dp else 0.dp)) {
                        Text(name, color = Hud.Text, fontSize = 14.sp)
                        if (i == MapLook.MISSION) Text(
                            if (MapFocus.mission != null) "Towns in the briefing and along the route" else "Towns of the briefing (cities until a mission is loaded)",
                            color = Hud.TextDim, fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(text: String) = Text(text, Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp), fontSize = 10.sp, color = Hud.TextFaint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

@Composable
private fun MenuRow(onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(2.dp))
        content()
    }
}
