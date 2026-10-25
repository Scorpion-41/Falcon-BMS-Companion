package com.bmscompanion.app.ui.screens.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionWeather
import com.bmscompanion.app.data.mission.MissionWxMap
import com.bmscompanion.app.data.weather.WxCover
import com.bmscompanion.app.data.weather.WxType
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.isMedium
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The Mission weather page's **vertical profile**: under the map, the weather along a path as a side view — altitude
 * 0-40,000 ft up, the distance along the path across, a column per sample coloured by the cell's weather type, the fog
 * layer, the cloud base with the cloud above it, towering cumulus, rain, showers and snow under the base, the ground
 * (BMS's height map, asked of the PC once per path) and the flight's planned altitude. The path is the route (the
 * mission's steerpoints from the take-off to the landing; with no route the save's take-off, target and landing), or a
 * line the pilot draws on the map (**Draw line**: a tap or click per point, Esc or Done to finish). Values are sampled
 * from the map on show (the time line's frame), so stepping through time redraws it.
 *
 * BMS's map gives the cloud **base** and the fog layer's height; it gives no cloud tops. The cloud above the base is
 * drawn deeper the larger the cumulus (size 0 congestus deep, 5 humilis shallow) and towering cumulus taller still —
 * indicative, and the legend line says so.
 */

/** The strip's path and cursor, kept for the session; whether the strip is open is kept on the device. */
internal object WxProfile {
    private const val OPEN = "mwx_profile_open"
    /** -1 never chosen: open where the map keeps most of the height with it ([roomy]: a tablet or PC with the room),
     *  folded elsewhere (a phone, a short window) */
    private var openPref by com.bmscompanion.app.data.mission.prefIntState(OPEN) { Repo.getInt(OPEN, -1) }

    fun isOpen(roomy: Boolean): Boolean = if (openPref < 0) roomy else openPref == 1
    fun setOpen(v: Boolean) { openPref = if (v) 1 else 0; Repo.putInt(OPEN, openPref) }

    /** the pilot's line, theater feet (x north, y east) */
    val line = mutableStateListOf<Pair<Double, Double>>()
    /** a tap on the map adds a point to [line] */
    var drawing by mutableStateOf(false)
        private set
    /** the strip shows [line] rather than the route */
    var useLine by mutableStateOf(false)
        private set
    /** where the strip's cursor is, along the path (nm) and on the map; null with none */
    var cursorNm by mutableStateOf<Double?>(null)
        private set
    var cursorAt by mutableStateOf<Pair<Double, Double>?>(null)
        private set

    fun startLine() { line.clear(); drawing = true; useLine = true; cursor(null, null) }
    fun add(x: Double, y: Double) { if (drawing) { line += x to y; cursor(null, null) } }
    fun undo() { if (line.isNotEmpty()) line.removeAt(line.size - 1) }
    /** The line finished: shown when it has two points, else back to the route. */
    fun done() { drawing = false; if (line.size < 2) { line.clear(); useLine = false } }
    fun clear() { line.clear(); drawing = false; useLine = false; cursor(null, null) }
    fun route() { if (drawing) done(); useLine = false; cursor(null, null) }
    /** The line chip: the line drawn before, else a new one. */
    fun showLine() { if (line.size >= 2) { useLine = true; cursor(null, null) } else startLine() }
    fun cursor(nm: Double?, at: Pair<Double, Double>?) { cursorNm = nm; cursorAt = at }

    /** Escape: finishes the line being drawn, else takes away a line shown. True when it did something. */
    fun escape(): Boolean {
        if (drawing) { done(); return true }
        if (useLine) { clear(); return true }
        return false
    }
}

/** A point the path runs through: a steerpoint ("3"), a place ("TGT") or a corner of the pilot's line ("B"). */
internal class WxProfilePoint(val label: String, val x: Double, val y: Double, val altFt: Double? = null, val target: Boolean = false)

/** A path sampled evenly: [samples] positions (theater ft) at [sampleNm] along it; [pointNm] each point's distance. */
internal class WxProfilePath(
    val points: List<WxProfilePoint>,
    val pointNm: List<Double>,
    val totalNm: Double,
    val samples: List<Pair<Double, Double>>,
    val sampleNm: List<Double>,
) {
    /** The position [nm] along the path. */
    fun at(nm: Double): Pair<Double, Double> {
        val k = ((nm / totalNm) * (samples.size - 1)).coerceIn(0.0, (samples.size - 1).toDouble())
        val a = k.toInt().coerceAtMost(samples.size - 2)
        val f = k - a
        val p = samples[a]
        val q = samples[a + 1]
        return (p.first + (q.first - p.first) * f) to (p.second + (q.second - p.second) * f)
    }
}

private const val FT_PER_NM = 6076.12

/** [points] sampled at [n] + 1 even steps; null for fewer than two points or a path of no length. */
internal fun profilePath(points: List<WxProfilePoint>, n: Int = 160): WxProfilePath? {
    if (points.size < 2) return null
    val at = ArrayList<Double>(points.size)
    var d = 0.0
    points.forEachIndexed { i, p ->
        if (i > 0) {
            val q = points[i - 1]
            d += sqrt((p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y)) / FT_PER_NM
        }
        at += d
    }
    if (d < 0.05) return null
    val samples = ArrayList<Pair<Double, Double>>(n + 1)
    val nm = ArrayList<Double>(n + 1)
    var seg = 0
    for (k in 0..n) {
        val s = d * k / n
        while (seg < points.size - 2 && at[seg + 1] < s) seg++
        val a = points[seg]
        val b = points[seg + 1]
        val len = at[seg + 1] - at[seg]
        val f = if (len <= 0.0) 0.0 else ((s - at[seg]) / len).coerceIn(0.0, 1.0)
        samples += (a.x + (b.x - a.x) * f) to (a.y + (b.y - a.y) * f)
        nm += s
    }
    return WxProfilePath(points, at, d, samples, nm)
}

/** The cell of [m] under each sample of [path] (row-major index), -1 off the map. */
internal fun profileCells(path: WxProfilePath, m: MissionWxMap, sizeFt: Double): IntArray =
    IntArray(path.samples.size) { k ->
        val (x, y) = path.samples[k]
        cellOf(x, y, sizeFt, m.cols, m.rows)?.let { (c, r) -> r * m.cols + c } ?: -1
    }

/** The briefing's ALT column as feet: "21.0M" is 21,000, "500" 500, "--" nothing. */
internal fun briefedAltFt(s: String?): Double? {
    val t = s?.trim()?.takeIf { it.isNotEmpty() && it != "--" } ?: return null
    val num = t.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
    return if (num < 100.0) num * 1000.0 else num
}

/** How deep the cloud above a base is drawn (BMS gives no tops): by the cumulus size, towering cumulus taller. */
private fun cloudDepthFt(size: Float?, towering: Boolean): Float {
    val s = (size ?: 3f).coerceIn(0f, 5f)
    return 1500f + (5f - s) * 1100f + if (towering) 9000f else 0f
}

/**
 * The profile itself, over the whole [DrawScope]: [m] the map on show ([cells] from [profileCells]), [ground] the PC's
 * ground under each sample (null where not known), [cursorNm] the cursor. Altitudes 0 to [topFt]. Drawn in screen
 * inks; usable on any canvas (a kneeboard page may call it with its own size).
 */
internal fun DrawScope.drawWxProfile(
    path: WxProfilePath,
    m: MissionWxMap,
    cells: IntArray,
    ground: List<Int?>?,
    text: TextMeasurer,
    cursorNm: Double?,
    topFt: Float = 40_000f,
) {
    val left = 34.dp.toPx()
    val right = 8.dp.toPx()
    val top = 6.dp.toPx()
    val bottom = 16.dp.toPx()
    val w = size.width - left - right
    val h = size.height - top - bottom
    if (w < 20f || h < 20f) return
    fun xOf(nm: Double) = left + (nm / path.totalNm).toFloat() * w
    fun yOf(ft: Float) = top + h - (ft / topFt).coerceIn(0f, 1.02f) * h
    val n = path.samples.size
    val colW = w / (n - 1)
    // the plot's ground
    drawRect(Color(0xFF0E1620), Offset(left, top), Size(w, h))
    // columns: the weather type, the fog, the cloud, what falls from it
    for (k in 0 until n) {
        val i = cells[k]
        if (i < 0) continue
        // whole pixels, each column starting where the last ended: overlapping translucent columns striped the strip
        val x0 = (xOf(path.sampleNm[k]) - colW / 2).coerceAtLeast(left).roundToInt().toFloat()
        val x1 = (xOf(path.sampleNm[k]) + colW / 2).coerceAtMost(left + w).roundToInt().toFloat()
        val cw = x1 - x0
        if (cw <= 0f) continue
        val type = WxType.ofCode(m.type[i].coerceIn(1, 4))
        drawRect(WxRamps.typeColor(type).copy(alpha = 0.30f), Offset(x0, top), Size(cw, h))
        val gnd = (ground?.getOrNull(k) ?: 0).toFloat().coerceAtLeast(0f)
        val fog = m.fogFt.getOrNull(i)?.toFloat() ?: 0f
        if (fog > gnd) {
            // the fog layer: a violet haze from the ground to its height, its top a dotted line
            drawRect(Color(0xFFB9AEE0).copy(alpha = 0.30f), Offset(x0, yOf(fog)), Size(cw, yOf(gnd) - yOf(fog)))
            if (k % 2 == 0) drawLine(Color(0xFFCFC4F5), Offset(x0, yOf(fog)), Offset(x1, yOf(fog)), 1.5f)
        }
        if (WxRamps.cloudy(m, i)) {
            val base = m.cloudBaseFt[i].toFloat()
            val tcu = m.towering.getOrNull(i) == 1
            val topOf = base + cloudDepthFt(m.cloudSize.getOrNull(i), tcu)
            val cover = WxRamps.coverFraction(WxRamps.coverAt(m, i).code)
            // what falls: rain, showers or snow, from the base to the ground
            val kind = WxRamps.precipKind(m, i)
            if (kind > 0 && k % 2 == 0) {
                val ink = when (kind) { 3 -> WxRamps.SNOW_EDGE; 2 -> WxRamps.RAIN; else -> WxRamps.SHOWER }
                val xc = (x0 + x1) / 2
                var y = yOf(base) + 3f
                val end = yOf(gnd)
                while (y < end) { drawLine(ink.copy(alpha = 0.85f), Offset(xc, y), Offset(xc - 1.5f, (y + 5f).coerceAtMost(end)), 1.2f); y += 9f }
            }
            drawRect(Color(0xFFE9EEF4).copy(alpha = 0.22f + 0.6f * cover), Offset(x0, yOf(topOf)), Size(cw, yOf(base) - yOf(topOf)))
            if (tcu) drawRect(WxRamps.TOWERING.copy(alpha = 0.30f), Offset(x0, yOf(topOf)), Size(cw, yOf(base) - yOf(topOf)))
            // the base, the one figure the map gives: a firm line
            drawLine(Color.White, Offset(x0, yOf(base)), Offset(x1, yOf(base)), 2f)
        }
    }
    // towering cumulus: a mark over the first column of each run
    var run = false
    for (k in 0 until n) {
        val i = cells[k]
        val tcu = i >= 0 && WxRamps.cloudy(m, i) && m.towering.getOrNull(i) == 1
        if (tcu && !run) {
            val topOf = m.cloudBaseFt[i] + cloudDepthFt(m.cloudSize.getOrNull(i), true)
            val x = xOf(path.sampleNm[k])
            val y = yOf(topOf) - 3f
            val tri = Path().apply { moveTo(x, y - 7f); lineTo(x - 5f, y); lineTo(x + 5f, y); close() }
            drawPath(tri, WxRamps.TOWERING)
        }
        run = tcu
    }
    // the ground
    if (ground != null && ground.any { it != null }) {
        val p = Path()
        p.moveTo(left, top + h)
        for (k in 0 until n) p.lineTo(xOf(path.sampleNm[k]), yOf((ground.getOrNull(k) ?: 0).toFloat().coerceAtLeast(0f)))
        p.lineTo(left + w, top + h)
        p.close()
        drawPath(p, Color(0xFF6B5638))
        val edge = Path()
        for (k in 0 until n) {
            val pt = Offset(xOf(path.sampleNm[k]), yOf((ground.getOrNull(k) ?: 0).toFloat().coerceAtLeast(0f)))
            if (k == 0) edge.moveTo(pt.x, pt.y) else edge.lineTo(pt.x, pt.y)
        }
        drawPath(edge, Color(0xFFB89A6A), style = Stroke(1.2f))
    }
    // the altitude grid and its figures
    val axis = TextStyle(color = Hud.TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    var ft = 0
    while (ft <= topFt) {
        val y = yOf(ft.toFloat())
        drawLine(Color.White.copy(alpha = if (ft == 0) 0.35f else 0.10f), Offset(left, y), Offset(left + w, y), 1f)
        val t = text.measure(if (ft == 0) "0" else "${ft / 1000}k", axis)
        drawText(t, topLeft = Offset(left - t.size.width - 4f, y - t.size.height / 2f))
        ft += if (topFt > 20_000f) 10_000 else 5_000
    }
    // the points: a faint line up, the label under the axis, none over another
    val stpt = TextStyle(color = Hud.Amber, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
    var lastRight = -1e9f
    path.points.forEachIndexed { j, p ->
        val x = xOf(path.pointNm[j])
        drawLine(Color.White.copy(alpha = 0.16f), Offset(x, top), Offset(x, top + h), 1f)
        val t = text.measure(p.label, if (p.target) stpt.copy(color = Color(0xFFFF6E6E)) else stpt)
        val lx = (x - t.size.width / 2f).coerceIn(0f, size.width - t.size.width)
        if (lx > lastRight + 3f) {
            drawText(t, topLeft = Offset(lx, top + h + 2f))
            lastRight = lx + t.size.width
        }
    }
    val total = text.measure("${path.totalNm.roundToInt()} nm", axis)
    drawText(total, topLeft = Offset(left + w - total.size.width - 2f, top + 2f))
    // the planned altitude: the points that have one, joined; the take-off and the landing on the ground
    val planned = path.points.mapIndexedNotNull { j, p ->
        val alt = p.altFt?.toFloat() ?: if (j == 0 || j == path.points.size - 1) {
            val k = ((path.pointNm[j] / path.totalNm) * (n - 1)).roundToInt().coerceIn(0, n - 1)
            (ground?.getOrNull(k) ?: 0).toFloat()
        } else null
        alt?.let { Offset(xOf(path.pointNm[j]), yOf(it)) }
    }
    if (path.points.any { it.altFt != null } && planned.size >= 2) {
        for (j in 1 until planned.size) {
            drawLine(Color.Black.copy(alpha = 0.6f), planned[j - 1], planned[j], 4f)
            drawLine(Hud.Amber, planned[j - 1], planned[j], 2f)
        }
        planned.forEach { drawCircle(Hud.Amber, 3f, it) }
    }
    cursorNm?.let { c ->
        val x = xOf(c.coerceIn(0.0, path.totalNm))
        drawLine(Hud.Cyan, Offset(x, top), Offset(x, top + h), 1.5f)
    }
    drawRect(Color.White.copy(alpha = 0.25f), Offset(left, top), Size(w, h), style = Stroke(1f))
}

/** The pilot's line and the strip's cursor over the weather map. */
internal fun DrawScope.drawProfileOnMap(pr: MapProjection, text: TextMeasurer) {
    val line = WxProfile.line
    if (line.isNotEmpty() && (WxProfile.useLine || WxProfile.drawing)) {
        val pts = line.map { (x, y) -> pr.toScreen(x, y) }
        for (j in 1 until pts.size) {
            drawLine(Color.Black.copy(alpha = 0.65f), pts[j - 1], pts[j], 5f)
            drawLine(Color.White, pts[j - 1], pts[j], 2.2f)
        }
        val st = TextStyle(color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 3f))
        pts.forEachIndexed { j, p ->
            drawCircle(Color.Black.copy(alpha = 0.7f), 6f, p)
            drawCircle(Color.White, 4.5f, p)
            val t = text.measure(letter(j), st)
            drawText(t, topLeft = p + Offset(7f, -t.size.height - 2f))
        }
    }
    WxProfile.cursorAt?.let { (x, y) ->
        val p = pr.toScreen(x, y)
        drawCircle(Color.Black.copy(alpha = 0.6f), 8f, p, style = Stroke(4f))
        drawCircle(Hud.Cyan, 8f, p, style = Stroke(2f))
    }
}

/** "A", "B" … "Z", "AA". */
private fun letter(j: Int): String = if (j < 26) ('A' + j).toString() else "A" + ('A' + (j - 26) % 26)

/**
 * The folding strip under the map: its title, the path chips (Route / Line / Draw), the hint while drawing, and the
 * profile with a cursor a tap or a drag moves (its figures in the title line, its place ringed on the map). Map Model
 * only ([m] the map on show; nothing while it is being read).
 */
@Composable
internal fun WxProfileStrip(
    w: MissionWeather, m: MissionWxMap?, sizeFt: Double?, picture: WxPicture,
    roomy: Boolean = true, maxBody: Dp = Dp.Infinity,
) {
    val medium = isMedium()
    val open = WxProfile.isOpen(roomy && medium)
    // the body's height: never so much that the map is left under half its column
    val body = minOf(if (medium) 150.dp else 118.dp, maxBody)
    // the route from the take-off to the landing, else the save's take-off, target and landing
    val routePts = remember(picture, w) {
        val plan = picture.route.filter { it.onRoute }
        if (plan.size >= 2) plan.map { WxProfilePoint(it.n.toString(), it.x, it.y, it.alt, it.target) }
        else w.places.map { WxProfilePoint(placeTag(it.label), it.x, it.y, target = it.label.startsWith("Target", true)) }
    }
    val useLine = WxProfile.useLine && WxProfile.line.size >= 2
    val linePts = WxProfile.line.toList()
    val points = if (useLine) linePts.mapIndexed { j, (x, y) -> WxProfilePoint(letter(j), x, y) } else routePts
    val path = remember(points.map { it.x to it.y }, useLine) { profilePath(points) }
    val cells = remember(path, m, sizeFt) { if (path != null && m != null && sizeFt != null) profileCells(path, m, sizeFt) else null }
    // the ground under the samples, asked of the PC once per path (nothing drawn while it is not known)
    var ground by remember(path) { mutableStateOf<List<Int?>?>(null) }
    LaunchedEffect(path, w.theater) {
        val p = path ?: return@LaunchedEffect
        val r = runCatching { MissionLink.campaignGround(w.theater.takeIf { it.isNotBlank() }, p.samples) }.getOrNull()
        r?.value?.heights?.takeIf { it.size == p.samples.size }?.let { ground = it }
    }
    Column(Modifier.fillMaxWidth().background(Hud.Surface.copy(alpha = 0.35f))) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "VERTICAL PROFILE " + if (open) "▾" else "▸",
                Modifier.clip(RoundedCornerShape(4.dp)).clickable { WxProfile.setOpen(!open) }.padding(horizontal = 4.dp, vertical = 3.dp),
                color = Hud.TextDim, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
            )
            EditorChip("Route", selected = !WxProfile.useLine) { WxProfile.route() }
            if (WxProfile.drawing) {
                EditorChip("Done", selected = true) { WxProfile.done() }
                if (WxProfile.line.isNotEmpty()) EditorChip("Undo") { WxProfile.undo() }
                EditorChip("Clear") { WxProfile.clear() }
            } else {
                EditorChip(if (WxProfile.line.size >= 2) "Line" else "Draw line", selected = WxProfile.useLine) {
                    if (!open) WxProfile.setOpen(true)
                    WxProfile.showLine()
                }
                if (WxProfile.line.size >= 2) {
                    EditorChip("Redraw") { if (!open) WxProfile.setOpen(true); WxProfile.startLine() }
                    EditorChip("Clear") { WxProfile.clear() }
                }
            }
            val words = when {
                WxProfile.drawing -> "Tap the map to add points (${WxProfile.line.size} so far) · Esc or Done to finish"
                else -> readout(path, m, cells, ground) ?: pathWords(points, useLine, routePts.isNotEmpty() && picture.route.count { it.onRoute } >= 2)
            }
            Text(words, color = if (WxProfile.drawing) Hud.Cyan else Hud.TextDim, fontSize = 11.sp, maxLines = 1)
        }
        if (!open) return@Column
        val text = rememberTextMeasurer(cacheSize = 128)
        Box(Modifier.fillMaxWidth().height(body).padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) {
            if (path == null || m == null || cells == null) {
                Text(
                    when {
                        m == null -> "Reading the map…"
                        WxProfile.drawing -> "Tap the map: two points or more make a line."
                        else -> "No route to follow: draw a line on the map."
                    },
                    Modifier.align(Alignment.Center), color = Hud.TextFaint, fontSize = 11.5.sp,
                )
            } else {
                Canvas(
                    Modifier.fillMaxWidth().height(body - 4.dp)
                        .pointerInput(path) {
                            fun put(x: Float) {
                                val left = 34.dp.toPx()
                                val wpx = size.width - left - 8.dp.toPx()
                                val nm = ((x - left) / wpx).coerceIn(0f, 1f) * path.totalNm
                                WxProfile.cursor(nm, path.at(nm))
                            }
                            detectTapGestures { put(it.x) }
                        }
                        .pointerInput(path) {
                            detectHorizontalDragGestures { ch, _ ->
                                val left = 34.dp.toPx()
                                val wpx = size.width - left - 8.dp.toPx()
                                val nm = ((ch.position.x - left) / wpx).coerceIn(0f, 1f) * path.totalNm
                                WxProfile.cursor(nm, path.at(nm))
                            }
                        },
                ) { drawWxProfile(path, m, cells, ground, text, WxProfile.cursorNm) }
            }
        }
    }
}

/** "T/O", "TGT", "LDG" for the save's places. */
private fun placeTag(label: String): String = when {
    label.startsWith("Take", true) -> "T/O"
    label.startsWith("Target", true) -> "TGT"
    label.startsWith("Land", true) -> "LDG"
    else -> label
}

/** What the strip follows, when no cursor is set. */
private fun pathWords(points: List<WxProfilePoint>, line: Boolean, route: Boolean): String = when {
    points.size < 2 -> "Nothing to follow yet"
    line -> "Along your line ${points.first().label}–${points.last().label} · tap the profile for the figures"
    route -> "Along the route, STPT ${points.first().label}–${points.last().label} · tap the profile for the figures"
    else -> "Take-off, target and landing · tap the profile for the figures"
}

/** The cursor's figures: "48 nm · Poor · BKN 035 · TCU · showers · fog 1,200 ft · vis 6 km · ground 820 ft". */
private fun readout(path: WxProfilePath?, m: MissionWxMap?, cells: IntArray?, ground: List<Int?>?): String? {
    val nm = WxProfile.cursorNm ?: return null
    if (path == null || m == null || cells == null) return null
    val k = ((nm / path.totalNm) * (cells.size - 1)).roundToInt().coerceIn(0, cells.size - 1)
    val i = cells[k]
    if (i < 0) return "${nm.roundToInt()} nm · off the map"
    val type = WxType.ofCode(m.type[i].coerceIn(1, 4))
    val cover = WxRamps.coverAt(m, i)
    val parts = ArrayList<String>()
    parts += "${nm.roundToInt()} nm"
    parts += type.label
    parts += if (cover == WxCover.NONE) "no cloud" else cover.metar + " " + three(m.cloudBaseFt[i] / 100)
    if (cover != WxCover.NONE && m.towering.getOrNull(i) == 1) parts += "TCU"
    when (WxRamps.precipKind(m, i)) { 3 -> parts += "snow"; 2 -> parts += "rain"; 1 -> parts += "showers" }
    m.fogFt.getOrNull(i)?.takeIf { it > 0 }?.let { parts += "fog to ${group(it)} ft" }
    parts += "vis ${visText(m.visKm[i].toDouble())} km"
    ground?.getOrNull(k)?.let { parts += "ground ${group(it.coerceAtLeast(0))} ft" }
    return parts.joinToString(" · ")
}
