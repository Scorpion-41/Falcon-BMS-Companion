package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.airfield.Airfield
import kotlinx.coroutines.runBlocking
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * `--satalign out.txt <a BMS folder> [picture folder]` — how exactly the airfield diagrams (BMS's own runway
 * rectangles and pavement, `af-*.json`, theater feet) lie on the Satellite style's photoreal layers ([MapTiles]:
 * GlobalColorMap, 4K, 16K, Hires) in every theater. Reads the folder only.
 *
 * Each field's window is drawn by [MapTiles.satellite] one layer at a time, exactly as a map tile is (1,024 px tiles
 * stitched, a pixel's centre at its tile's place + ½), and the field's runways and pavement are slid over it: the
 * shift where the pavement differs most from the ground round it is where the texture's field lies from the
 * diagram's (east, north, feet; a single runway can slide along itself, so a field's own answer is only as good as
 * its "sep"). Then the layers against each other (picture against picture: a placement error between layers shows
 * here, texture content does not), and every runway of up to 25 fields a theater slid **across** itself over the
 * satellite as the map shows it — a sharp answer whatever its length — with the one shift per theater that explains
 * the sharp ones best (least squares, runways more than 100 ft from it left out as content: a strip the imagery does
 * not show, a parallel taxiway taken for the runway). Up to 1.4.0's first builds that shift was the terrain's frame
 * ([MapTiles.Terrain.scale]: 51 ft at the south-west corner to 880 ft at the north-east); now a few feet.
 * Pictures (when a folder is given): each theater's first fields over the 4K/16K/Hires layers, the diagram's runways
 * red and moved by the field's measured shift green; `BMSC_SATALIGN_FIELDS=<names>` adds those fields as the map
 * shows them, `BMSC_SATALIGN_ONLY=<map ids>` measures only those terrains.
 */
object SatAlign {
    private val NAMES = listOf("Global", "4K", "16K", "Hires")
    /** the map level each layer is measured at (Korea's 1,024 km: 51, 25.6, 6.4 and 3.2 ft a pixel at 1,024 px tiles) */
    private val LEVEL = intArrayOf(6, 7, 9, 10)
    private val SEARCH_FT = doubleArrayOf(1500.0, 500.0, 300.0, 200.0)
    private const val TILE = 1024
    private const val NO_DATA = 0xFF12202E.toInt()

    private class Window(val px: IntArray, val w: Int, val h: Int, val x0: Int, val y0: Int, val fpp: Double, val sizeFt: Double) {
        fun x(east: Double) = east / fpp - x0
        fun y(north: Double) = (sizeFt - north) / fpp - y0
        fun lum(i: Int): Float { val v = px[i]; return (((v shr 16) and 0xFF) * 0.3f + ((v shr 8) and 0xFF) * 0.59f + (v and 0xFF) * 0.11f) }
    }

    /** The window round (north, east) as the map draws it at level [z] with 1,024 px tiles, [layer] alone (-1: every layer). */
    private fun window(t: MapTiles.Terrain, layer: Int, z: Int, north: Double, east: Double, half: Double): Window? {
        val n = 1 shl z
        val T = t.sizeFt / n
        val cA = floor((east - half) / T).toInt().coerceIn(0, n - 1)
        val cB = floor((east + half) / T).toInt().coerceIn(0, n - 1)
        val rA = floor((t.sizeFt - north - half) / T).toInt().coerceIn(0, n - 1)
        val rB = floor((t.sizeFt - north + half) / T).toInt().coerceIn(0, n - 1)
        val w = (cB - cA + 1) * TILE
        val h = (rB - rA + 1) * TILE
        val out = IntArray(w * h)
        for (r in rA..rB) for (c in cA..cB) {
            val tile = MapTiles.satellite(t, z, r, c, TILE, layer.takeIf { it >= 0 }) ?: return null
            val ox = (c - cA) * TILE; val oy = (r - rA) * TILE
            for (y in 0 until TILE) System.arraycopy(tile, y * TILE, out, (oy + y) * w + ox, TILE)
        }
        return Window(out, w, h, cA * TILE, rA * TILE, T / TILE, t.sizeFt)
    }

    /** Sobel magnitude of the luminance after a 3×3 box; 0 next to pixels with no texture. */
    private fun gradient(win: Window): FloatArray {
        val w = win.w; val h = win.h
        val l = FloatArray(w * h) { win.lum(it) }
        val b = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var s = 0f
            for (dy in -1..1) for (dx in -1..1) s += l[(y + dy) * w + x + dx]
            b[y * w + x] = s / 9f
        }
        val g = FloatArray(w * h)
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val o = y * w + x
            if (win.px[o] == NO_DATA || win.px[o - 2] == NO_DATA || win.px[o + 2] == NO_DATA || win.px[o - 2 * w] == NO_DATA || win.px[o + 2 * w] == NO_DATA) continue
            val gx = (b[o - w + 1] + 2 * b[o + 1] + b[o + w + 1]) - (b[o - w - 1] + 2 * b[o - 1] + b[o + w - 1])
            val gy = (b[o + w - 1] + 2 * b[o + w] + b[o + w + 1]) - (b[o - w - 1] + 2 * b[o - w] + b[o - w + 1])
            g[o] = sqrt(gx * gx + gy * gy)
        }
        return g
    }

    /** The diagram's runways (and with [paved] its pavement) filled at the window's pixel centres. */
    private fun mask(win: Window, f: Airfield, paved: Boolean, runways: List<com.bmscompanion.app.data.airfield.AfRunway> = f.runways): BooleanArray {
        val img = BufferedImage(win.w, win.h, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = java.awt.Color.WHITE
        fun px(e: Double) = win.x(f.e + e)
        fun py(n: Double) = win.y(f.n + n)
        for (rw in runways) {
            if (rw.corners.size < 3) continue
            val p = Path2D.Double()
            rw.corners.forEachIndexed { i, c -> if (i == 0) p.moveTo(px(c.e), py(c.n)) else p.lineTo(px(c.e), py(c.n)) }
            p.closePath(); g.fill(p)
        }
        if (paved && f.paved.isNotEmpty()) {
            val p = Path2D.Double(Path2D.WIND_EVEN_ODD)
            for (ring in f.paved) {
                var i = 0
                while (i + 1 < ring.size) {
                    val x = px(ring[i].toDouble()); val y = py(ring[i + 1].toDouble())
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    i += 2
                }
                p.closePath()
            }
            g.fill(p)
        }
        g.dispose()
        val r = img.raster
        val out = BooleanArray(win.w * win.h)
        for (y in 0 until win.h) for (x in 0 until win.w) out[y * win.w + x] = r.getSample(x, y, 0) > 127
        return out
    }

    private fun edges(m: BooleanArray, w: Int, h: Int, margin: Int): IntArray {
        val list = ArrayList<Int>()
        for (y in margin until h - margin) for (x in margin until w - margin) {
            val o = y * w + x
            if (m[o] && (!m[o - 1] || !m[o + 1] || !m[o - w] || !m[o + w])) list += o
        }
        return list.toIntArray()
    }

    class Shift(val dx: Double, val dy: Double, val peak: Double, val contrast: Double, val edgePx: Int)

    /** The shift (px, sub-pixel) that puts the most of [e]'s edge pixels on strong gradient, within ±[radius] px. */
    private fun search(g: FloatArray, w: Int, e: IntArray, radius: Int): Shift? {
        if (e.size < 40) return null
        val step = if (e.size > 20000) 3 else if (e.size > 8000) 2 else 1
        fun score(sx: Int, sy: Int): Double {
            var s = 0.0; var i = 0
            val d = sy * w + sx
            while (i < e.size) { s += g[e[i] + d]; i += step }
            return s / ((e.size + step - 1) / step)
        }
        val coarse = max(1, radius / 25)
        var best = Double.NEGATIVE_INFINITY; var bx = 0; var by = 0
        var sum = 0.0; var count = 0
        var sy = -radius
        while (sy <= radius) {
            var sx = -radius
            while (sx <= radius) {
                val s = score(sx, sy); sum += s; count++
                if (s > best) { best = s; bx = sx; by = sy }
                sx += coarse
            }
            sy += coarse
        }
        val mean = sum / count
        val cx = bx; val cy = by
        for (yy in cy - coarse..cy + coarse) for (xx in cx - coarse..cx + coarse) {
            if (abs(xx) > radius || abs(yy) > radius) continue
            val s = score(xx, yy)
            if (s > best) { best = s; bx = xx; by = yy }
        }
        // a parabola through the peak and its neighbours, each axis
        fun sub(a: Double, b: Double, c: Double) = if (a - 2 * b + c < 0) ((a - c) / (2 * (a - 2 * b + c))).coerceIn(-0.5, 0.5) else 0.0
        val c0 = score(bx, by)
        val fx = if (abs(bx) < radius) sub(score(bx - 1, by), c0, score(bx + 1, by)) else 0.0
        val fy = if (abs(by) < radius) sub(score(bx, by - 1), c0, score(bx, by + 1)) else 0.0
        return Shift(bx + fx, by + fy, best, if (mean > 0) best / mean else 0.0, e.size)
    }

    /** Picture [b] against picture [a] (gradients), the shift of b's content from a's in px; edges of a's strongest tenth. */
    private fun picShift(a: FloatArray, b: FloatArray, w: Int, h: Int, radius: Int): Shift? {
        val margin = radius + 3
        val vals = ArrayList<Float>()
        for (y in margin until h - margin step 3) for (x in margin until w - margin step 3) { val v = a[y * w + x]; if (v > 0) vals += v }
        if (vals.size < 1000) return null
        vals.sort()
        val cut = vals[(vals.size * 0.9).toInt()]
        val list = ArrayList<Int>()
        for (y in margin until h - margin) for (x in margin until w - margin) {
            val o = y * w + x
            if (a[o] >= cut && b[o] > 0) list += o
        }
        return search(b, w, list.toIntArray(), radius)
    }

    /** The band round [m]: within [d] px of it (a square) and not in it. */
    private fun ring(m: BooleanArray, w: Int, h: Int, d: Int): BooleanArray {
        val rows = BooleanArray(w * h)
        for (y in 0 until h) {
            var last = -1_000_000
            for (x in 0 until w) { if (m[y * w + x]) last = x; if (x - last <= d) rows[y * w + x] = true }
            last = 1_000_000
            for (x in w - 1 downTo 0) { if (m[y * w + x]) last = x; if (last - x <= d) rows[y * w + x] = true }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var last = -1_000_000
            for (y in 0 until h) { if (rows[y * w + x]) last = y; if (y - last <= d) out[y * w + x] = true }
            last = 1_000_000
            for (y in h - 1 downTo 0) { if (rows[y * w + x]) last = y; if (last - y <= d) out[y * w + x] = true }
        }
        for (i in out.indices) if (m[i]) out[i] = false
        return out
    }

    /** The set pixels of [b] at least [margin] from the sides, thinned to at most [cap]. */
    private fun indices(b: BooleanArray, w: Int, h: Int, margin: Int, cap: Int): IntArray {
        val all = ArrayList<Int>()
        for (y in margin until h - margin) for (x in margin until w - margin) if (b[y * w + x]) all += y * w + x
        if (all.size <= cap) return all.toIntArray()
        val k = (all.size + cap - 1) / cap
        return IntArray(all.size / k) { all[it * k] }
    }

    class Match(val dx: Double, val dy: Double, val score: Double, val sep: Double)

    /**
     * The shift (px) within [radius] of ([cx], [cy]) that puts [inside] on ground most unlike [outside]: |mean luminance
     * over the pavement − mean over the band round it|. [sep] is the best score over the best one more than [apart] px away.
     */
    private fun contrast(l: FloatArray, w: Int, h: Int, inside: IntArray, outside: IntArray, cx: Int, cy: Int, radius: Int, apart: Int): Match? {
        if (inside.size < 30 || outside.size < 30) return null
        val side = 2 * radius + 1
        val sc = DoubleArray(side * side) { Double.NaN }
        var best = -1.0; var bx = 0; var by = 0
        for (sy in -radius..radius) for (sx in -radius..radius) {
            val d = (cy + sy) * w + (cx + sx)
            var a = 0.0; var nA = 0
            for (i in inside) { val o = i + d; if (o in l.indices) { val v = l[o]; if (v >= 0) { a += v; nA++ } } }
            var b = 0.0; var nB = 0
            for (i in outside) { val o = i + d; if (o in l.indices) { val v = l[o]; if (v >= 0) { b += v; nB++ } } }
            if (nA < inside.size / 2 || nB < outside.size / 2) continue
            val s = abs(a / nA - b / nB)
            sc[(sy + radius) * side + sx + radius] = s
            if (s > best) { best = s; bx = sx; by = sy }
        }
        if (best <= 0) return null
        var second = 0.0
        for (sy in -radius..radius) for (sx in -radius..radius) {
            val s = sc[(sy + radius) * side + sx + radius]
            if (!s.isNaN() && max(abs(sx - bx), abs(sy - by)) > apart && s > second) second = s
        }
        fun at(x: Int, y: Int) = if (abs(x) <= radius && abs(y) <= radius) sc[(y + radius) * side + x + radius] else Double.NaN
        fun sub(a: Double, b: Double, c: Double) = if (!a.isNaN() && !c.isNaN() && a - 2 * b + c < 0) ((a - c) / (2 * (a - 2 * b + c))).coerceIn(-0.5, 0.5) else 0.0
        val fx = sub(at(bx - 1, by), best, at(bx + 1, by))
        val fy = sub(at(bx, by - 1), best, at(bx, by + 1))
        return Match(cx + bx + fx, cy + by + fy, best, if (second > 0) best / second else 9.99)
    }

    private fun luminance(win: Window) = FloatArray(win.px.size) { if (win.px[it] == NO_DATA || win.px[it] == 0) -1f else win.lum(it) }

    private class Row(val theater: String, val field: String, val n: Double, val e: Double, val layer: Int, val de: Double, val dn: Double, val sep: Double)

    /** The field's pavement against [layer] at level [z]: the shift in feet (east, north), searched within [radiusFt] of [startFt]. */
    private fun measure(t: MapTiles.Terrain, f: Airfield, layer: Int, z: Int, startFt: Pair<Double, Double>, radiusFt: Double): Pair<Match, Window>? {
        val win = window(t, layer, z, f.n, f.e, extent(f) + radiusFt + 400) ?: return null
        val l = luminance(win)
        if (l.count { it >= 0 } < l.size / 3) return null
        val m = mask(win, f, true)
        val band = max(3, (120 / win.fpp).roundToInt())
        val radius = max(2, (radiusFt / win.fpp).roundToInt())
        val margin = radius + 2 + abs(startFt.first / win.fpp).roundToInt() + abs(startFt.second / win.fpp).roundToInt()
        val inside = indices(m, win.w, win.h, margin, 12000)
        val outside = indices(ring(m, win.w, win.h, band), win.w, win.h, margin, 12000)
        val cx = (startFt.first / win.fpp).roundToInt(); val cy = (-startFt.second / win.fpp).roundToInt()
        val r = contrast(l, win.w, win.h, inside, outside, cx, cy, radius, max(2, (150 / win.fpp).roundToInt())) ?: return null
        return r to win
    }

    fun run(bms: File, pictures: File?): String = buildString {
        appendLine("Satellite layers against the airfield diagrams, ${bms.name} (read only)")
        appendLine("Each field's runways and pavement (af-*.json) slid over the photoreal: the shift where the pavement differs most")
        appendLine("from the ground round it is where the texture's field lies from the diagram's (+E east, +N north, feet).")
        appendLine("sep = that contrast over the best one more than 150 ft away (under ~1.15: no clear answer).")
        appendLine()
        MapTiles.rootOverride = bms
        pictures?.mkdirs()
        picDir = pictures
        val index = runBlocking { Repo.index() }
        val rows = ArrayList<Row>()
        val acrossAll = ArrayList<Across>()
        for (id in MapTiles.SOURCES.keys) {
            if (System.getenv("BMSC_SATALIGN_ONLY")?.split(',')?.contains(id) == false) continue
            val t = MapTiles.terrain(id) ?: run { appendLine("$id: no terrain"); null } ?: continue
            val th = index.theaters.firstOrNull { it.map?.startsWith("maps/$id/") == true && it.airfieldSet != null && it.airportSet.isNotEmpty() }
            if (th == null) { appendLine("$id: no theater in the app's data"); continue }
            val airports = runBlocking { Repo.airportSet(th.airportSet) }.airports.filter { it.type != "Carrier" && it.runways.isNotEmpty() }
            val fields = airports.mapNotNull { a -> runBlocking { Repo.airfield(th.airfieldSet!!, a.id) }?.takeIf { it.ship == null && it.runways.isNotEmpty() } }
                .sortedByDescending { f -> f.runways.maxOf { it.lengthFt } }
            val adj = (ln(t.sizeKm / 1024.0) / ln(2.0)).roundToInt()
            // the longest fields first, then every fifth of the rest: big and small, spread over the theater
            val pick = (fields.take(8) + fields.drop(8).filterIndexed { i, _ -> i % 5 == 0 }).take(18)
            appendLine("== $id (${th.id}, ${t.sizeKm.roundToInt()} km), ${pick.size} of ${fields.size} fields")
            for ((fi, f) in pick.withIndex()) {
                val line = StringBuilder("  %-26s n %8.0f e %8.0f".format(f.name.take(26), f.n, f.e))
                // the 16K picture at its own texel first, a wide search; then 4K, and the 16K and Hires at 3.2 ft a pixel round it
                val coarse = measure(t, f, 2, 8 + adj, 0.0 to 0.0, 1000.0)
                if (coarse == null) { appendLine("$line  no 16K texture"); continue }
                val fpp = coarse.second.fpp
                val c = coarse.first
                val start = c.dx * fpp to -c.dy * fpp
                line.append("  16K/%-4.1f E %+5.0f N %+5.0f sep %.2f".format(fpp, start.first, start.second, c.sep))
                rows += Row(id, f.name, f.n, f.e, 2, start.first, start.second, c.sep)
                measure(t, f, 1, 7 + adj, start, 300.0)?.let { (m, w) ->
                    line.append("  4K E %+5.0f N %+5.0f sep %.2f".format(m.dx * w.fpp, -m.dy * w.fpp, m.sep))
                    rows += Row(id, f.name, f.n, f.e, 1, m.dx * w.fpp, -m.dy * w.fpp, m.sep)
                }
                for (layer in intArrayOf(2, 3)) measure(t, f, layer, 10 + adj, start, 40.0)?.let { (m, w) ->
                    line.append("  ${NAMES[layer]}/3.2 E %+5.0f N %+5.0f sep %.2f".format(m.dx * w.fpp, -m.dy * w.fpp, m.sep))
                    rows += Row(id, f.name, f.n, f.e, layer + 10, m.dx * w.fpp, -m.dy * w.fpp, m.sep)
                    if (pictures != null && fi < 3) picture(pictures, id, f, NAMES[layer], w, Shift(m.dx, m.dy, m.score, m.sep, 0))
                }
                appendLine(line)
            }
            // the layers against each other, over the first fields' windows at each pair's finer level
            appendLine("  layers against each other (content of the finer one from the coarser one's, ft):")
            for (f in pick.take(3)) for (pair in listOf(0 to 1, 1 to 2, 2 to 3)) {
                val z = LEVEL[pair.second] - 1 + adj
                val a = window(t, pair.first, z, f.n, f.e, 6000.0) ?: continue
                val b = window(t, pair.second, z, f.n, f.e, 6000.0) ?: continue
                if (a.px.count { it != NO_DATA } < a.px.size / 4 || b.px.count { it != NO_DATA } < b.px.size / 4) continue
                val s = picShift(gradient(a), gradient(b), a.w, a.h, (SEARCH_FT[pair.first] / a.fpp).roundToInt().coerceAtMost(120)) ?: continue
                appendLine("    %-26s %s→%s  E %+6.0f N %+6.0f c %.2f".format(f.name.take(26), NAMES[pair.first], NAMES[pair.second], s.dx * a.fpp, -s.dy * a.fpp, s.contrast))
            }
            // every runway slid across itself over the picture the map shows at 3.2 ft a pixel: a sharp answer whatever its length
            val step = max(1, fields.size / 25)
            val across = fields.filterIndexed { i, _ -> i % step == 0 }.flatMap { across(t, it, 10 + adj, id) }
            appendLine("  runways across (the satellite as the map shows it, 3.2 ft a pixel; + = the texture's runway lies to the right of the diagram's, looking from its first end):")
            for (a in across) appendLine("    %-26s %-8s %+6.0f ft  sharp %.1f".format(a.field.take(26), a.rwy, a.off, a.sharp))
            acrossAll += across
            appendLine()
        }
        appendLine("Runways across, per theater: the one shift (east, north) that best explains every sharp runway (sharp ≥ 1.5), then what is left")
        for ((id, list) in acrossAll.groupBy { it.theater }) {
            var use = list.filter { it.sharp >= 1.5 && abs(it.off) < REACH - 20 }
            var fit: DoubleArray? = null
            repeat(2) {
                fit = solve(use) ?: return@repeat
                val (e, n) = fit!!.let { it[0] to it[1] }
                val kept = use.filter { abs(it.off - (e * it.ne + n * it.nn)) < 100 }
                if (kept.size >= 5) use = kept
            }
            val f = fit
            if (f == null) { appendLine("  %-9s too few sharp runways (%d of %d)".format(id, use.size, list.size)); continue }
            val res = use.map { abs(it.off - (f[0] * it.ne + f[1] * it.nn)) }.sorted()
            val all = list.filter { it.sharp >= 1.5 }.map { abs(it.off) }.sorted()
            appendLine("  %-9s E %+5.0f N %+5.0f ft from %d of %d runways; across them, median |left| %.0f ft (90 %%: %.0f); sharp runways' own median |shift| %.0f ft".format(
                id, f[0], f[1], use.size, list.size, res[res.size / 2], res[(res.size * 9) / 10], if (all.isEmpty()) 0.0 else all[all.size / 2]))
        }
        appendLine()
        appendLine("Summary: per theater and layer, the fields with a clear answer (sep ≥ 1.15): median shift and spread round it")
        fun med(v: List<Double>) = v.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
        for ((key, list) in rows.groupBy { it.theater to it.layer }) {
            val good = list.filter { it.sep >= 1.15 }
            val name = if (key.second >= 10) NAMES[key.second - 10] + "/3.2" else NAMES[key.second]
            if (good.isEmpty()) { appendLine("  ${key.first} $name: no clear answer"); continue }
            val me = med(good.map { it.de }); val mn = med(good.map { it.dn })
            val spread = med(good.map { kotlin.math.hypot(it.de - me, it.dn - mn) })
            val size = med(good.map { kotlin.math.hypot(it.de, it.dn) })
            appendLine("  %-9s %-9s median E %+5.0f N %+5.0f ft, spread %4.0f ft, median size %4.0f ft (%d of %d fields)".format(key.first, name, me, mn, spread, size, good.size, list.size))
        }
        appendLine()
        appendLine("Rows (theater, field, n, e, layer, dE, dN, sep) for fitting:")
        for (r in rows) appendLine("ROW\t${r.theater}\t${r.field}\t${r.n.roundToInt()}\t${r.e.roundToInt()}\t${r.layer}\t${"%.1f".format(r.de)}\t${"%.1f".format(r.dn)}\t${"%.2f".format(r.sep)}")
    }

    private const val REACH = 450.0

    private class Across(val theater: String, val field: String, val rwy: String, val ne: Double, val nn: Double, val off: Double, val sharp: Double)

    /** Least squares of off = E·ne + N·nn over [list]: (E, N), null when the headings do not decide both. */
    private fun solve(list: List<Across>): DoubleArray? {
        if (list.size < 3) return null
        var a = 0.0; var b = 0.0; var c = 0.0; var p = 0.0; var q = 0.0
        for (x in list) { a += x.ne * x.ne; b += x.ne * x.nn; c += x.nn * x.nn; p += x.ne * x.off; q += x.nn * x.off }
        val det = a * c - b * b
        if (abs(det) < 1e-6 * list.size * list.size) return null
        return doubleArrayOf((c * p - b * q) / det, (a * q - b * p) / det)
    }

    /**
     * Each runway of [f] (3,000 ft or longer) slid across itself over the satellite as the map draws it at level [z]
     * (every layer): the shift along its normal (to the right of the line from its first end) where the strip differs
     * most from the ground beside it, and how sharp that answer is (the best contrast over the profile's median).
     */
    private fun across(t: MapTiles.Terrain, f: Airfield, z: Int, id: String): List<Across> {
        val rws = f.runways.filter { it.lengthFt >= 3000 && it.corners.size >= 4 && it.ends.size == 2 }
        if (rws.isEmpty()) return emptyList()
        val reachOut = rws.maxOf { r -> r.corners.maxOf { max(abs(it.e), abs(it.n)) } }
        val win = window(t, -1, z, f.n, f.e, reachOut + REACH + 300) ?: return emptyList()
        val l = luminance(win)
        val out = ArrayList<Across>()
        for (rw in rws) {
            val a = rw.ends[0].at; val b = rw.ends[1].at
            val len = kotlin.math.hypot(b.e - a.e, b.n - a.n)
            if (len < 1) continue
            val ue = (b.e - a.e) / len; val un = (b.n - a.n) / len
            val ne = un; val nn = -ue // the right-hand normal, east and north
            val m = mask(win, f, false, listOf(rw))
            val margin = (REACH / win.fpp).roundToInt() + 3
            val inside = indices(m, win.w, win.h, margin, 15000)
            val outside = indices(ring(m, win.w, win.h, max(3, (100 / win.fpp).roundToInt())), win.w, win.h, margin, 15000)
            if (inside.size < 50 || outside.size < 50) continue
            val steps = (REACH / win.fpp).roundToInt()
            val prof = DoubleArray(2 * steps + 1) { Double.NaN }
            for (k in -steps..steps) {
                val s = k * win.fpp
                val d = (-s * nn / win.fpp).roundToInt() * win.w + (s * ne / win.fpp).roundToInt()
                var si = 0.0; var ni = 0; var so = 0.0; var no = 0
                for (i in inside) { val v = l[i + d]; if (v >= 0) { si += v; ni++ } }
                for (i in outside) { val v = l[i + d]; if (v >= 0) { so += v; no++ } }
                if (ni > inside.size / 2 && no > outside.size / 2) prof[k + steps] = abs(si / ni - so / no)
            }
            val valid = prof.filter { !it.isNaN() }.sorted()
            if (valid.size < steps) continue
            var bk = 0; var best = -1.0
            for (k in prof.indices) if (!prof[k].isNaN() && prof[k] > best) { best = prof[k]; bk = k }
            val fx = if (bk in 1 until prof.size - 1 && !prof[bk - 1].isNaN() && !prof[bk + 1].isNaN()) {
                val p0 = prof[bk - 1]; val p2 = prof[bk + 1]; val den = p0 - 2 * best + p2
                if (den < 0) ((p0 - p2) / (2 * den)).coerceIn(-0.5, 0.5) else 0.0
            } else 0.0
            val median = valid[valid.size / 2]
            out += Across(id, f.name, rw.name, ne, nn, (bk - steps + fx) * win.fpp, if (median > 0) best / median else 9.9)
        }
        if (picDir != null && f.name in picNames) picture(picDir!!, id, f, "map", win, null)
        return out
    }

    /** `BMSC_SATALIGN_FIELDS`: fields (names, comma-separated) drawn as the map shows them, with the picture folder */
    private var picDir: File? = null
    private val picNames: Set<String> by lazy { System.getenv("BMSC_SATALIGN_FIELDS")?.split(',')?.map { it.trim() }?.toSet().orEmpty() }

    private fun extent(f: Airfield): Double {
        var r = 0.0
        for (rw in f.runways) for (c in rw.corners) r = max(r, max(abs(c.e), abs(c.n)))
        return r
    }

    /** A crop round the longest runway's first end: the picture, the diagram's runway edges red, moved by the shift green. */
    private fun picture(dir: File, id: String, f: Airfield, label: String, win: Window, s: Shift?) {
        val rw = f.runways.maxByOrNull { it.lengthFt } ?: return
        val end = rw.ends.firstOrNull()?.at ?: return
        val cx = win.x(f.e + end.e).roundToInt(); val cy = win.y(f.n + end.n).roundToInt()
        val half = 300
        val img = BufferedImage(2 * half, 2 * half, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 2 * half) for (x in 0 until 2 * half) {
            val wx = cx - half + x; val wy = cy - half + y
            if (wx in 0 until win.w && wy in 0 until win.h) img.setRGB(x, y, win.px[wy * win.w + wx] and 0xFFFFFF)
        }
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        fun outline(dx: Double, dy: Double, color: java.awt.Color) {
            g.color = color
            for (r in f.runways) {
                val p = Path2D.Double()
                r.corners.forEachIndexed { i, c ->
                    val x = win.x(f.e + c.e) - (cx - half) + dx; val y = win.y(f.n + c.n) - (cy - half) + dy
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.closePath(); g.draw(p)
            }
        }
        outline(0.0, 0.0, java.awt.Color.RED)
        if (s != null) outline(s.dx, s.dy, java.awt.Color.GREEN)
        g.color = java.awt.Color.WHITE
        g.drawString("${f.name} $label %.1f ft/px  red = diagram, green = moved by the measured shift".format(win.fpp), 6, 14)
        g.dispose()
        val safe = f.name.replace(Regex("[^A-Za-z0-9]+"), "_")
        ImageIO.write(img, "png", File(dir, "$id-$safe-$label.png"))
    }
}
