package com.bmscompanion.desktop.bridge

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.Typeface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The map tiles past the levels the app bundles (`GET /api/maptile`), drawn on the PC from Falcon BMS's own terrain.
 *
 * The apps carry each style down to level 4 (8,192 px across the theater, 125 m a pixel in Korea). Below that the
 * map asks the PC, which has the files the sim flies on:
 *
 * - **Relief, Dark, Chart and Topographic** from `NewTerrain/HeightMaps/HeightMap.raw` — 32,768² int16 feet, a sample
 *   every 31.25 m of the theater's grid — box-averaged where a pixel covers several samples and interpolated
 *   (Catmull-Rom) where a sample covers several pixels, shaded as `tools/extractor/src/maps.mjs` shades the bundled
 *   levels (the same ramps, the same sun, the same exaggeration as its top level) so a deep tile continues the bundled
 *   one. Water is a sample that is ≤ 0 and exactly equal to its neighbours two samples away (heightmap.mjs). BMS's
 *   terrain levelling under the airbases is laid over the heights ([TerrainLevelling], [flatten]), so a field reads
 *   flat at the level the sim gives it (the levels the apps bundle were drawn without it: a field is a few pixels there).
 * - **Satellite** from BMS's own photoreal textures in `NewTerrain/Photoreal`: `GlobalColorMap.dds` (the bundled
 *   satellite, level 4), `4K/<n>.dds` (64 km cells of 4,096 px, 15.6 m a pixel), `16K/<n>.dds` (32 km cells of 8,192
 *   px, 3.9 m) and `Hires/Day/R<row>_C<col>.dds` (1 km cells of 2,048 px, 0.5 m, round the airbases). `<n>` is the
 *   cell's place counted from 1, row by row from the north-west, and `<row>`/`<col>` count from 1 the same way; a cell with no file (open sea) shows the coarser
 *   layer. The colours are levelled exactly as the extractor levels the bundled picture.
 * - **Topographic** (`topo`): pale hypsometric tints under a soft hillshade, water, and contour lines whose interval
 *   follows the ground a pixel covers (1,000 ft across the whole theater down to 10 ft round a runway), every fifth
 *   line heavier and labelled in feet. The bundled topographic levels (overview, z2, z3) come out of the same code
 *   (`--maptiles`, [bundle]).
 *
 * Read only, nothing kept on disk: the files are opened for reading for one tile at a time, and the answers are kept
 * in memory ([CACHE_BYTES], least recently used dropped). Nothing throws: a tile that cannot be drawn is a reason.
 */
object MapTiles {
    const val TILE = 512
    /** the deepest level drawn (Korea: 2,097,152 px across, 0.49 m a pixel — the Hires photoreal's own resolution) */
    const val MAX_Z = 12
    val STYLES = listOf("relief", "satellite", "dark", "chart", "topo")
    private const val CACHE_BYTES = 64L * 1024 * 1024

    /** Each map id's terrain folder under the BMS folder (the extractor's `MAP_SOURCES`). */
    val SOURCES = linkedMapOf(
        "korea" to "Data/TerrData/Korea",
        "balkans" to "Data/Add-On Balkans/Terrdata/Balkans",
        "hellas" to "Data/Add-On Hellas/TerrData/Hellas",
        "israel" to "Data/Add-On Israel/Terrdata/Israel",
        "falklands" to "Data/Add-On Falklands/TerrData/Falklands",
    )

    /** Each map id's own theater's `objectdir`, whose `TerrainRelatedData` levels the terrain under its airbases. */
    val OBJECTS = linkedMapOf(
        "korea" to "Data/TerrData/Objects",
        "balkans" to "Data/Add-On Balkans/Terrdata/objects",
        "hellas" to "Data/Add-On Hellas/TerrData/Objects",
        "israel" to "Data/Add-On Israel/Terrdata/objects",
        "falklands" to "Data/Add-On Falklands/TerrData/objects",
    )

    /** A check's BMS folder (read only); the program itself reads the install it found. */
    @Volatile var rootOverride: File? = null
    private fun root(): File? = rootOverride ?: Bridge.install.baseDir?.let(::File)

    // ================================================================ route

    /** `GET /api/maptile?map=<id>&style=<style>&z=<level>&r=<row>&c=<col>`: one 512 px JPEG tile. */
    fun handle(req: ApiRequest): ApiResponse {
        val id = req.query["map"].orEmpty()
        val style = req.query["style"].orEmpty()
        val z = req.query["z"]?.toIntOrNull()
        val r = req.query["r"]?.toIntOrNull()
        val c = req.query["c"]?.toIntOrNull()
        if (id !in SOURCES || style !in STYLES || z == null || r == null || c == null || z !in 0..MAX_Z || r !in 0 until (1 shl z) || c !in 0 until (1 shl z))
            return ApiResponse.json("""{"error":"no such tile"}""", 404)
        val t = tile(id, style, z, r, c)
        return t.bytes?.let { ApiResponse(200, "image/jpeg", it) }
            ?: ApiResponse.json("""{"error":"${(t.why ?: "no tile").replace("\\", "\\\\").replace("\"", "\\\"")}"}""", 404)
    }

    class Answer(val bytes: ByteArray?, val why: String?)

    private val cache = object : LinkedHashMap<String, ByteArray>(256, 0.75f, true) {}
    private var cached = 0L
    private val gate = Semaphore(3)

    /** One tile, from memory when it was drawn before. */
    fun tile(id: String, style: String, z: Int, r: Int, c: Int): Answer {
        val key = "$id/$style/$z/$r/$c"
        synchronized(cache) { cache[key] }?.let { return Answer(it, null) }
        val t = terrain(id) ?: return Answer(null, "Falcon BMS's terrain for $id was not found on this PC.")
        gate.acquireUninterruptibly()
        val bytes = try {
            val px = render(t, style, z, r, c, TILE) ?: return Answer(null, "The terrain files of $id could not be read.")
            encode(px, TILE, EncodedImageFormat.JPEG, if (style == "topo") 90 else 85)
        } catch (e: Throwable) {
            return Answer(null, "The tile could not be drawn: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            gate.release()
        }
        synchronized(cache) {
            cache.put(key, bytes)?.let { cached -= it.size }
            cached += bytes.size
            val it = cache.entries.iterator()
            while (cached > CACHE_BYTES && it.hasNext()) { val e = it.next(); cached -= e.value.size; it.remove() }
        }
        return Answer(bytes, null)
    }

    // ================================================================ terrain

    class Terrain(val id: String, val dir: File, val sizeKm: Double) {
        val sizeFt = sizeKm * 1000.0 * 3.27998
        val heightFile: File? = Theaters.resolveDir(dir, "NewTerrain/HeightMaps")?.let { d ->
            d.listFiles()?.firstOrNull { it.name.equals("HeightMap.raw", ignoreCase = true) }
        }
        val n: Int = heightFile?.length()?.let { Math.rint(sqrt(it / 2.0)).toInt() } ?: 0
        /** where BMS lays this terrain on the campaign grid ([TerrainFrame]) */
        val frame = TerrainFrame(sizeKm, n)
        /** the map's square as a fraction of the terrain's width ([TerrainFrame.scale]) */
        val scale: Double get() = frame.scale
        /** half a height-map sample, as a fraction of the terrain's width */
        val halfSample: Double get() = frame.halfSample
        val photo: File? = Theaters.resolveDir(dir, "NewTerrain/Photoreal")
        val names = ConcurrentHashMap<String, Set<String>>()
        /** the extractor's auto levels for the satellite picture: (low, scale × 1000) */
        @Volatile var levels: IntArray? = null
        /** BMS's terrain levelling under the airbases ([TerrainLevelling]), applied to every height read */
        @Volatile var flattening: TerrainLevelling.Levels? = null
        fun has(sub: String, name: String): Boolean = names.getOrPut(sub) {
            photo?.let { Theaters.resolveDir(it, sub) }?.list()?.map { it.lowercase() }?.toSet() ?: emptySet()
        }.contains(name.lowercase())
        fun file(sub: String, name: String): File? = photo?.let { Theaters.resolveDir(it, sub) }?.let { File(it, name) }
    }

    /**
     * Where BMS lays a terrain of [sizeKm] and [n] × [n] height-map samples on the campaign grid — the one place this
     * is worked out, for the PC's map tiles ([Terrain]) and its ground at a point ([TerrainHeights]).
     *
     * BMS lays its terrain (height map and photoreal alike) in metres of 3.28084 ft, sample k's centre k samples from
     * the south-west corner of the campaign grid, while the grid — the map's square, every object's position — counts
     * 3.27998 ft a metre. So the square is [scale] of the terrain's width, and the terrain starts half a sample
     * south-west of it: drawn as one square, the textures sat up to 880 ft (Korea's north-east corner) off BMS's own
     * runways, 51 ft at the south-west (`--satalign`); read as one square (WDP's `ReadNewTerrainElvLoc`), the ground
     * was the same distance off (`--elevframe`: BMS's ground units stand on this frame's samples, bilinear, to 0.01 m).
     */
    class TerrainFrame(sizeKm: Double, val n: Int) {
        /** the campaign grid's side, feet (3.27998 a metre) */
        val sizeFt = sizeKm * 1000.0 * 3.27998
        /** the terrain's side, feet (3.28084 a metre) */
        val terrainFt = sizeKm * 1000.0 * 3.28084
        val scale: Double = sizeFt / terrainFt
        /** half a height-map sample, as a fraction of the terrain's width */
        val halfSample: Double = if (n > 0) 0.5 / n else 0.0
        /** [east] (theater feet) as a column of samples, sample k's centre at k from the west */
        fun colAt(east: Double): Double = east / terrainFt * n
        /** [north] (theater feet) as a row of samples, sample k's centre at k from the north */
        fun rowAt(north: Double): Double = n - 1 - north / terrainFt * n
    }

    /** `Theater size in KM=` of a terrain's `NewTerrain/Theater.txt` ([newTerrain] is that folder); null when it says none */
    fun sizeKmOf(newTerrain: File?): Double? = try {
        newTerrain?.let { Theaters.resolveFile(it, "Theater.txt") }?.readText(Charsets.ISO_8859_1)
            ?.let { Regex("Theater size in KM=([\\d.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
    } catch (_: Exception) { null }

    private val terrains = ConcurrentHashMap<String, Terrain>()

    fun terrain(id: String): Terrain? {
        val base = root() ?: return null
        terrains[id]?.takeIf { it.dir.path.startsWith(base.path, ignoreCase = true) }?.let { return it }
        val rel = SOURCES[id] ?: return null
        val dir = Theaters.resolveDir(base, rel) ?: return null
        val km = sizeKmOf(Theaters.resolveDir(dir, "NewTerrain")) ?: 1024.0
        return Terrain(id, dir, km).also {
            it.flattening = TerrainLevelling.ofObjects(OBJECTS[id]?.let { rel -> Theaters.resolveDir(base, rel) })
            terrains[id] = it
        }
    }

    // ================================================================ heights

    /** Heights in feet and water (0..1) at the centres of a (size + 2)² grid of pixels: the tile and one pixel round it. */
    internal class Grid(val w: Int, val h: FloatArray, val wet: FloatArray)

    private const val D = 2

    private fun open(f: File): FileChannel = FileChannel.open(f.toPath(), StandardOpenOption.READ)

    /** Samples [row0, row0 + rows) × [col0, col0 + cols), edges repeated past the map's sides. */
    private fun readBlock(ch: FileChannel, n: Int, row0: Int, rows: Int, col0: Int, cols: Int): ShortArray {
        val out = ShortArray(rows * cols)
        val a = max(0, col0)
        val b = min(n, col0 + cols)
        if (b <= a) return out
        val buf = ByteBuffer.allocate((b - a) * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (j in 0 until rows) {
            val row = (row0 + j).coerceIn(0, n - 1)
            buf.clear()
            var pos = (row.toLong() * n + a) * 2
            while (buf.hasRemaining()) { val k = ch.read(buf, pos); if (k <= 0) break; pos += k }
            buf.flip()
            val o = j * cols
            val sb = buf.asShortBuffer()
            val got = sb.remaining()
            for (x in 0 until cols) {
                val cx = (col0 + x).coerceIn(a, b - 1) - a
                out[o + x] = if (cx < got) sb.get(cx) else 0
            }
        }
        return out
    }

    /** heightmap.mjs's water: ≤ 0 and equal to the eight samples [D] away. */
    private fun wetAt(s: ShortArray, cols: Int, x: Int, y: Int): Boolean {
        val v = s[y * cols + x]
        if (v > 0) return false
        val up = (y - D) * cols; val mid = y * cols; val dn = (y + D) * cols
        return s[mid + x - D] == v && s[mid + x + D] == v && s[up + x] == v && s[dn + x] == v &&
            s[up + x - D] == v && s[up + x + D] == v && s[dn + x - D] == v && s[dn + x + D] == v
    }

    private fun grid(t: Terrain, z: Int, r: Int, c: Int, size: Int): Grid? {
        val file = t.heightFile ?: return null
        val n = t.n
        if (n <= 0) return null
        val w = size + 2
        val h = FloatArray(w * w)
        val wet = FloatArray(w * w)
        val S = n.toDouble() / (1 shl z)     // samples a tile covers
        val spp = S / size                    // samples a pixel covers
        // a pixel's centre (theater feet, as [flatten] has it) as a place among the samples ([TerrainFrame])
        val tiles = (1 shl z).toDouble()
        fun colOf(i: Int) = t.frame.colAt(t.sizeFt * (c + (i + 0.5) / size) / tiles)
        fun rowOf(j: Int) = t.frame.rowAt(t.sizeFt * (1 - (r + (j + 0.5) / size) / tiles))
        open(file).use { ch ->
            if (spp >= 1.0) {
                val k = spp.roundToInt()
                val stride = max(1, k / 4)      // the water test on a subsample once a pixel covers 64+ samples
                fun first(s: Double) = floor(s - (k - 1) / 2.0 + 0.5).toInt() // the first of the k samples round s
                val col0 = first(colOf(-1)) - D
                val cols = first(colOf(size)) + k + D - col0
                for (j in -1..size) {
                    val row0 = first(rowOf(j)) - D
                    val s = readBlock(ch, n, row0, k + 2 * D, col0, cols)
                    for (i in -1..size) {
                        var sum = 0L
                        var tests = 0
                        var wets = 0
                        val x0 = first(colOf(i)) - col0
                        for (dy in 0 until k) {
                            val o = (D + dy) * cols
                            for (dx in 0 until k) sum += s[o + x0 + dx]
                        }
                        var dy = 0
                        while (dy < k) {
                            var dx = 0
                            while (dx < k) { tests++; if (wetAt(s, cols, x0 + dx, D + dy)) wets++; dx += stride }
                            dy += stride
                        }
                        val o = (j + 1) * w + (i + 1)
                        h[o] = sum.toFloat() / (k * k)
                        wet[o] = if (wets * 2 >= tests) 1f else 0f
                    }
                }
            } else {
                // a sample covers several pixels: Catmull-Rom over the samples, the water flag bilinear
                val u0 = colOf(-1)
                val u1 = colOf(size)
                val v0 = rowOf(-1)
                val v1 = rowOf(size)
                val sx0 = floor(u0).toInt() - 1
                val sy0 = floor(v0).toInt() - 1
                val sw = ceil(u1).toInt() + 3 - sx0
                val sh = ceil(v1).toInt() + 3 - sy0
                val cols = sw + 2 * D
                val raw = readBlock(ch, n, sy0 - D, sh + 2 * D, sx0 - D, cols)
                val sv = FloatArray(sw * sh)
                val sWet = FloatArray(sw * sh)
                for (y in 0 until sh) for (x in 0 until sw) {
                    sv[y * sw + x] = raw[(y + D) * cols + x + D].toFloat()
                    sWet[y * sw + x] = if (wetAt(raw, cols, x + D, y + D)) 1f else 0f
                }
                fun cr(p0: Float, p1: Float, p2: Float, p3: Float, f: Float): Float =
                    p1 + 0.5f * f * (p2 - p0 + f * (2f * p0 - 5f * p1 + 4f * p2 - p3 + f * (3f * (p1 - p2) + p3 - p0)))
                val row = FloatArray(4)
                for (j in -1..size) {
                    val v = rowOf(j) - sy0
                    val yi = floor(v).toInt().coerceIn(1, sh - 3)
                    val fy = (v - yi).toFloat()
                    for (i in -1..size) {
                        val u = colOf(i) - sx0
                        val xi = floor(u).toInt().coerceIn(1, sw - 3)
                        val fx = (u - xi).toFloat()
                        for (q in 0 until 4) {
                            val o = (yi - 1 + q) * sw + xi
                            row[q] = cr(sv[o - 1], sv[o], sv[o + 1], sv[o + 2], fx)
                        }
                        val o = (j + 1) * w + (i + 1)
                        h[o] = cr(row[0], row[1], row[2], row[3], fy)
                        val a = (yi * sw + xi)
                        val top = sWet[a] * (1 - fx) + sWet[a + 1] * fx
                        val bot = sWet[a + sw] * (1 - fx) + sWet[a + sw + 1] * fx
                        wet[o] = top * (1 - fy) + bot * fy
                    }
                }
            }
        }
        flatten(t, z, r, c, size, h, wet)
        return Grid(w, h, wet)
    }

    /**
     * For checks (`--elevframe`): the height the deepest level draws at the pixel whose centre is nearest
     * ([north], [east]) theater feet — a 64 px grid of that tile — as (north, east, feet) of that centre.
     */
    internal fun heightAt(t: Terrain, north: Double, east: Double): Triple<Double, Double, Float>? {
        val size = 64
        val tiles = 1 shl MAX_Z
        val fx = east / t.sizeFt * tiles
        val fy = (1 - north / t.sizeFt) * tiles
        val c = floor(fx).toInt()
        val r = floor(fy).toInt()
        if (c !in 0 until tiles || r !in 0 until tiles) return null
        val i = floor((fx - c) * size).toInt().coerceIn(0, size - 1)
        val j = floor((fy - r) * size).toInt().coerceIn(0, size - 1)
        val g = grid(t, MAX_Z, r, c, size) ?: return null
        return Triple(t.sizeFt * (1 - (r + (j + 0.5) / size) / tiles), t.sizeFt * (c + (i + 0.5) / size) / tiles, g.h[(j + 1) * g.w + (i + 1)])
    }

    /**
     * BMS's terrain levelling ([TerrainLevelling]) over the grid's heights, each pixel's centre taken in theater feet
     * (its place on the map's square, the campaign grid the zones are drawn in): the level inside an airbase's 100 %
     * polygon (and no water there), the linear mix in its rings. Only the zones that reach the tile.
     */
    private fun flatten(t: Terrain, z: Int, r: Int, c: Int, size: Int, h: FloatArray, wet: FloatArray) {
        val all = t.flattening ?: return
        if (all.isEmpty || t.n <= 0) return
        val w = size + 2
        val tiles = (1 shl z).toDouble()
        fun northAt(j: Int) = t.sizeFt * (1 - (r + (j + 0.5) / size) / tiles)
        fun eastAt(i: Int) = t.sizeFt * (c + (i + 0.5) / size) / tiles
        val n0 = northAt(size); val n1 = northAt(-1)
        val e0 = eastAt(-1); val e1 = eastAt(size)
        val levels = all.within(n0, n1, e0, e1)
        if (levels.isEmpty) return
        for (j in -1..size) {
            val north = northAt(j)
            for (i in -1..size) {
                val east = eastAt(i)
                val (zone, pct) = levels.weight(north, east) ?: continue
                val o = (j + 1) * w + (i + 1)
                if (pct >= 99.9) { h[o] = zone.level.toFloat(); wet[o] = 0f }
                else { val p = (pct / 100.0).toFloat(); h[o] = zone.level.toFloat() * p + h[o] * (1 - p) }
            }
        }
    }

    // ================================================================ colours (maps.mjs)

    private class Ramp(val stops: DoubleArray, val rgb: Array<IntArray>) {
        fun at(v: Double, out: DoubleArray) {
            if (v <= stops[0]) { for (k in 0..2) out[k] = rgb[0][k].toDouble(); return }
            for (i in 1 until stops.size) if (v <= stops[i]) {
                val f = (v - stops[i - 1]) / (stops[i] - stops[i - 1])
                for (k in 0..2) out[k] = rgb[i - 1][k] + (rgb[i][k] - rgb[i - 1][k]) * f
                return
            }
            for (k in 0..2) out[k] = rgb.last()[k].toDouble()
        }
    }

    private fun ramp(vararg s: Pair<Int, IntArray>) = Ramp(s.map { it.first.toDouble() }.toDoubleArray(), s.map { it.second }.toTypedArray())

    private val RELIEF_LAND = ramp(0 to intArrayOf(58, 82, 62), 800 to intArrayOf(78, 102, 72), 2500 to intArrayOf(110, 120, 82), 5000 to intArrayOf(138, 128, 96), 8000 to intArrayOf(160, 146, 126), 11000 to intArrayOf(214, 210, 204))
    private val RELIEF_SEA = ramp(-3000 to intArrayOf(14, 28, 44), -200 to intArrayOf(20, 40, 60), 0 to intArrayOf(28, 52, 74))
    private val CHART_LAND = ramp(0 to intArrayOf(236, 232, 214), 1500 to intArrayOf(226, 222, 196), 4000 to intArrayOf(212, 200, 170), 8000 to intArrayOf(196, 182, 156), 11000 to intArrayOf(238, 236, 232))
    private val CHART_SEA = ramp(-3000 to intArrayOf(150, 192, 216), 0 to intArrayOf(184, 214, 230))
    /** Topographic: pale tints, a printed map's greens and buffs, so the contours and the mission's inks read over them */
    private val TOPO_LAND = ramp(0 to intArrayOf(206, 222, 186), 600 to intArrayOf(218, 228, 192), 1500 to intArrayOf(232, 230, 196), 3000 to intArrayOf(236, 222, 184), 5000 to intArrayOf(226, 204, 170), 7500 to intArrayOf(214, 194, 170), 10000 to intArrayOf(238, 234, 228))
    private const val TOPO_WATER = 0xFFB4D3E8.toInt()
    private const val TOPO_COAST = 0xFF5F8FBF.toInt()
    private const val TOPO_LINE = 0xFF8E5A2E.toInt()

    /** maps.mjs's exaggeration at its top level (8,192 px): every PC level shades with the same */
    private const val EXAGGERATION = 1.575

    private fun argb(r: Double, g: Double, b: Double): Int =
        (0xFF shl 24) or (r.coerceIn(0.0, 255.0).toInt() shl 16) or (g.coerceIn(0.0, 255.0).toInt() shl 8) or b.coerceIn(0.0, 255.0).toInt()

    /** The contour interval and the index interval (every fifth line), in feet, for the ground a pixel covers. */
    fun contourStep(metresPerPx: Double): Pair<Int, Int> = when {
        metresPerPx >= 400 -> 1000 to 5000
        metresPerPx >= 100 -> 500 to 2500
        metresPerPx >= 50 -> 250 to 1000
        metresPerPx >= 25 -> 200 to 1000
        metresPerPx >= 12 -> 100 to 500
        metresPerPx >= 6 -> 50 to 250
        metresPerPx >= 1.5 -> 20 to 100
        else -> 10 to 50
    }

    /** A tile's pixels (ARGB), and for a topographic one what its contour labels are placed from. */
    class Pixels internal constructor(val px: IntArray, internal val labels: Labels?)

    /** One tile's pixels in [style]; null when the files cannot be read. */
    fun render(t: Terrain, style: String, z: Int, r: Int, c: Int, size: Int): Pixels? =
        if (style == "satellite") satellite(t, z, r, c, size)?.let { Pixels(it, null) } else shaded(t, style, z, r, c, size)

    private fun shaded(t: Terrain, style: String, z: Int, r: Int, c: Int, size: Int): Pixels? {
        val g = grid(t, z, r, c, size) ?: return null
        val w = g.w
        val H = g.h
        val pxFt = t.sizeFt / (size.toDouble() * (1 shl z))
        val metresPerPx = t.sizeKm * 1000.0 / (size.toDouble() * (1 shl z))
        val out = IntArray(size * size)
        val lx = -0.5; val ly = -0.5; val lz = Math.sqrt(0.5)
        val col = DoubleArray(3)
        val (step, index) = contourStep(metresPerPx)
        for (y in 0 until size) for (x in 0 until size) {
            val o = (y + 1) * w + (x + 1)
            val hv = H[o].toDouble()
            val gx = (H[o + 1] - H[o - 1]) / (2 * pxFt) * EXAGGERATION
            val gy = (H[o + w] - H[o - w]) / (2 * pxFt) * EXAGGERATION
            val len = sqrt(gx * gx + gy * gy + 1)
            val shade = ((-gx * lx + gy * ly + lz) / len) / lz
            val land = g.wet[o] < 0.5f
            val px: Int = when (style) {
                "relief" -> if (land) {
                    RELIEF_LAND.at(hv, col); val s = shade.coerceIn(0.35, 1.35); argb(col[0] * s, col[1] * s, col[2] * s)
                } else { RELIEF_SEA.at(hv, col); argb(col[0], col[1], col[2]) }
                "dark" -> if (land) {
                    val s = (1 + (shade - 1) * 0.8).coerceIn(0.55, 1.45); argb(42 * s, 52 * s, 54 * s)
                } else argb(12.0, 19.0, 27.0)
                "chart" -> if (land) {
                    CHART_LAND.at(hv, col); val s = (1 + (shade - 1) * 0.45).coerceIn(0.78, 1.06); argb(col[0] * s, col[1] * s, col[2] * s)
                } else { CHART_SEA.at(hv, col); argb(col[0], col[1], col[2]) }
                else -> if (land) {
                    TOPO_LAND.at(hv, col)
                    val s = (1 + (shade - 1) * 0.45).coerceIn(0.74, 1.06)
                    var rr = col[0] * s; var gg = col[1] * s; var bb = col[2] * s
                    // the contour through this pixel, if one passes within a line's width: the distance to the nearest
                    // level in pixels is its height difference over the slope, which keeps every line one width wide
                    val sx = (H[o + 1] - H[o - 1]) / 2.0
                    val sy = (H[o + w] - H[o - w]) / 2.0
                    val slope = hypot(sx, sy)
                    val level = Math.round(hv / step) * step
                    // no line at sea level: it would only double the coastline drawn below
                    if (slope > step * 0.002 && level != 0L) {
                        val d = abs(hv - level) / slope
                        val major = level % index == 0L
                        val half = if (major) 0.85 else 0.45
                        val a = ((half + 0.5 - d).coerceIn(0.0, 1.0)) * (if (major) 0.9 else 0.6)
                        if (a > 0) {
                            rr += (((TOPO_LINE shr 16) and 0xFF) - rr) * a
                            gg += (((TOPO_LINE shr 8) and 0xFF) - gg) * a
                            bb += ((TOPO_LINE and 0xFF) - bb) * a
                        }
                    }
                    argb(rr, gg, bb)
                } else TOPO_WATER
            }
            out[y * size + x] = px
        }
        // the coastline: a thin darker line where land meets water (chart, dark and topographic, as the bundled levels)
        if (style != "relief") {
            val coast = when (style) { "chart" -> argb(120.0, 150.0, 170.0); "dark" -> argb(70.0, 92.0, 104.0); else -> TOPO_COAST }
            for (y in 0 until size) for (x in 0 until size) {
                val o = (y + 1) * w + (x + 1)
                if (g.wet[o] < 0.5f && (g.wet[o - 1] >= 0.5f || g.wet[o + 1] >= 0.5f || g.wet[o - w] >= 0.5f || g.wet[o + w] >= 0.5f)) out[y * size + x] = coast
            }
        }
        return Pixels(out, if (style == "topo") Labels(g, step, index) else null)
    }

    // ================================================================ contour labels

    internal class Labels(val g: Grid, val step: Int, val index: Int)

    private val typeface: Typeface? by lazy {
        runCatching { FontMgr.default.matchFamilyStyle("Segoe UI", FontStyle.BOLD) }.getOrNull()
            ?: runCatching { FontMgr.default.matchFamilyStyle("Arial", FontStyle.BOLD) }.getOrNull()
            ?: runCatching { FontMgr.default.legacyMakeTypeface("", FontStyle.BOLD) }.getOrNull()
    }

    /**
     * The heights of the index contours, written along the line where it runs smooth: one candidate a cell of a coarse
     * grid, kept clear of the tile's edges (a label is never cut by its neighbour) and of the labels already placed.
     */
    private fun drawLabels(canvas: org.jetbrains.skia.Canvas, lab: Labels, size: Int) {
        val tf = typeface ?: return
        val font = Font(tf, if (size > TILE) 15f else 14f)
        val fill = Paint().apply { color = TOPO_LINE; isAntiAlias = true }
        val halo = Paint().apply { color = 0xE8F2EEDC.toInt(); isAntiAlias = true; mode = PaintMode.STROKE; strokeWidth = 3.2f }
        val g = lab.g
        val w = g.w
        val H = g.h
        val cell = 150
        val margin = 46
        val placed = ArrayList<FloatArray>()
        var cy = margin
        while (cy < size - margin) {
            var cx = margin
            while (cx < size - margin) {
                var done = false
                var yy = cy
                while (!done && yy < min(cy + cell, size - margin)) {
                    var xx = cx
                    while (!done && xx < min(cx + cell, size - margin)) {
                        val o = (yy + 1) * w + (xx + 1)
                        if (g.wet[o] < 0.5f) {
                            val hv = H[o].toDouble()
                            val sx = (H[o + 1] - H[o - 1]) / 2.0
                            val sy = (H[o + w] - H[o - w]) / 2.0
                            val slope = hypot(sx, sy)
                            val level = Math.round(hv / lab.index) * lab.index
                            // on the line, on ground steep enough for the line to be a line, and not too steep to read
                            if (level > 0 && slope > lab.step * 0.01 && abs(hv - level) / slope < 0.5 && slope < lab.step * 0.4) {
                                // the tangent, and the same tangent a label's half-length either side: a smooth run
                                var ang = Math.toDegrees(atan2(sx, -sy))
                                if (ang > 90) ang -= 180.0
                                if (ang < -90) ang += 180.0
                                if (smooth(g, xx, yy, ang, 22, size)) {
                                    val text = level.toString()
                                    val line = TextLine.make(text, font)
                                    val tw = line.width
                                    val box = floatArrayOf(xx - tw / 2 - 8, yy - 16f, xx + tw / 2 + 8, yy + 16f)
                                    if (placed.none { p -> p[0] < box[2] && box[0] < p[2] && p[1] < box[3] && box[1] < p[3] }) {
                                        placed += box
                                        canvas.save()
                                        canvas.translate(xx.toFloat(), yy.toFloat())
                                        canvas.rotate(ang.toFloat())
                                        val bx = -tw / 2
                                        val by = line.capHeight / 2
                                        canvas.drawTextLine(line, bx, by, halo)
                                        canvas.drawTextLine(line, bx, by, fill)
                                        canvas.restore()
                                        done = true
                                    }
                                }
                            }
                        }
                        xx += 3
                    }
                    yy += 3
                }
                cx += cell
            }
            cy += cell
        }
    }

    /** True when the contour through ([x], [y]) keeps roughly the heading [ang] for [half] pixels either way. */
    private fun smooth(g: Grid, x: Int, y: Int, ang: Double, half: Int, size: Int): Boolean {
        val rad = Math.toRadians(ang)
        val dx = kotlin.math.cos(rad)
        val dy = kotlin.math.sin(rad)
        for (s in intArrayOf(-half, half)) {
            val px = (x + dx * s).roundToInt()
            val py = (y + dy * s).roundToInt()
            if (px < 1 || py < 1 || px >= size - 1 || py >= size - 1) return false
            val o = (py + 1) * g.w + (px + 1)
            if (g.wet[o] >= 0.5f) return false
            val sx = (g.h[o + 1] - g.h[o - 1]) / 2.0
            val sy = (g.h[o + g.w] - g.h[o - g.w]) / 2.0
            var a = Math.toDegrees(atan2(sx, -sy))
            if (a > 90) a -= 180.0
            if (a < -90) a += 180.0
            var diff = abs(a - ang)
            if (diff > 90) diff = 180 - diff
            if (diff > 18) return false
        }
        return true
    }

    // ================================================================ encoding

    private fun encode(p: Pixels, size: Int, format: EncodedImageFormat, quality: Int): ByteArray {
        val px = p.px
        val bytes = ByteArray(size * size * 4)
        for (i in px.indices) {
            val v = px[i]
            val o = i * 4
            bytes[o] = (v and 0xFF).toByte()
            bytes[o + 1] = ((v shr 8) and 0xFF).toByte()
            bytes[o + 2] = ((v shr 16) and 0xFF).toByte()
            bytes[o + 3] = 0xFF.toByte()
        }
        val info = ImageInfo.makeN32(size, size, ColorAlphaType.OPAQUE)
        val img = Image.makeRaster(info, bytes, size * 4)
        val labels = p.labels
        return img.use { image ->
            if (labels == null) image.encodeToData(format, quality)!!.bytes
            else Surface.makeRaster(info).use { s ->
                s.canvas.drawImage(image, 0f, 0f)
                drawLabels(s.canvas, labels, size)
                s.makeImageSnapshot().use { it.encodeToData(format, quality)!!.bytes }
            }
        }
    }

    // ================================================================ satellite (BMS's photoreal textures)

    /** A DDS file's top: size, levels and where its pixels start (DXT1 or DXT5, with or without a DX10 header). */
    private class Dxt(val width: Int, val height: Int, val levels: Int, val blockBytes: Int, val data: Long) {
        fun levelW(l: Int) = max(1, width shr l)
        fun levelH(l: Int) = max(1, height shr l)
        fun offset(l: Int): Long {
            var o = data
            for (i in 0 until l) o += max(1, (levelW(i) + 3) / 4).toLong() * max(1, (levelH(i) + 3) / 4) * blockBytes
            return o
        }
    }

    private val dxts = ConcurrentHashMap<String, Dxt>()

    private fun dxt(f: File, ch: FileChannel): Dxt? = dxts[f.path] ?: run {
        val head = ByteBuffer.allocate(148).order(ByteOrder.LITTLE_ENDIAN)
        ch.read(head, 0)
        if (head.position() < 128 || head.getInt(0) != 0x20534444) return null
        val h = head.getInt(12); val w = head.getInt(16)
        val mips = max(1, head.getInt(28))
        val four = String(ByteArray(4) { head.get(84 + it) }, Charsets.ISO_8859_1)
        val (bytes, data) = when (four) {
            "DXT1" -> 8 to 128L
            "DXT5", "DXT4", "DXT3", "DXT2" -> 16 to 128L
            "DX10" -> when (head.getInt(128)) { 70, 71, 72 -> 8 to 148L; 76, 77, 78, 73, 74, 75 -> 16 to 148L; else -> return null }
            else -> return null
        }
        Dxt(w, h, mips, bytes, data).also { dxts[f.path] = it }
    }

    private val EXPAND5 = IntArray(32) { (it shl 3) or (it shr 2) }
    private val EXPAND6 = IntArray(64) { (it shl 2) or (it shr 4) }
    private fun rgb565(c: Int) = intArrayOf(EXPAND5[(c shr 11) and 31], EXPAND6[(c shr 5) and 63], EXPAND5[c and 31])

    /** The four colours of the colour block at [o] of [buf] into [pal] (ARGB, alpha 0xFF). */
    private fun palette(buf: ByteBuffer, o: Int, fourColour: Boolean, pal: IntArray) {
        val c0 = buf.getShort(o).toInt() and 0xFFFF
        val c1 = buf.getShort(o + 2).toInt() and 0xFFFF
        val p0 = rgb565(c0); val p1 = rgb565(c1)
        pal[0] = rgbInt(p0[0], p0[1], p0[2]); pal[1] = rgbInt(p1[0], p1[1], p1[2])
        if (fourColour || c0 > c1) {
            pal[2] = rgbInt((2 * p0[0] + p1[0]) / 3, (2 * p0[1] + p1[1]) / 3, (2 * p0[2] + p1[2]) / 3)
            pal[3] = rgbInt((p0[0] + 2 * p1[0]) / 3, (p0[1] + 2 * p1[1]) / 3, (p0[2] + 2 * p1[2]) / 3)
        } else {
            pal[2] = rgbInt((p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2, (p0[2] + p1[2]) / 2)
            pal[3] = rgbInt(0, 0, 0)
        }
    }

    /** The brightest channel a fill may have: BMS's sea fills are navy, teal and black; snow and cloud are not fills. */
    private const val FILL_MAX = 90

    /**
     * Whether a block is drawn as a fill draws it: dark ([FILL_MAX]) and either one colour, or two colours with
     * nothing between them (a hard seam between two shades). A photograph's block, even of smooth water, uses the
     * in-between colours too — a test of how far apart the colours are instead took the Falklands' smooth coastal
     * water for fill.
     */
    private fun fillLike(buf: ByteBuffer, o: Int, fourColour: Boolean, pal: IntArray): Boolean {
        palette(buf, o, fourColour, pal)
        val idx = buf.getInt(o + 4)
        var used = 0
        for (k in 0 until 16) used = used or (1 shl ((idx ushr (2 * k)) and 3))
        val solid = used == 1 || used == 2 || used == 4 || used == 8
        if (!solid && (used and 0b1100) != 0) return false
        for (i in 0 until 4) if (used and (1 shl i) != 0) {
            val p = pal[i]
            if (max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF)) > FILL_MAX) return false
        }
        return true
    }

    /** How wide, blocks of mip level 1 either side, a patch must be to count as a fill: three blocks are 24 px of the
     *  texture (375 m of a 4K cell, 94 m of a 16K one). */
    private const val FILL_RADIUS = 1

    /** How far a fill is grown past its blocks: over the seams between its shades, which mip level 1 has already
     *  blended into one or two blocks of in-between colours. */
    private const val FILL_GROW = 2

    /**
     * Where a texture is fill ([fillLike]), worked out once from its mip level 1 — the full picture, where a fill is
     * exactly what was painted (the smaller levels blend its seams into shades a photograph has too) — as one bit a
     * block of that level ([bw] × [bh]); [fillMask] with [FILL_RADIUS].
     */
    internal class FillMap(val bw: Int, val bh: Int, val level: Int, val bits: java.util.BitSet) {
        /** How much of the texture is fill at (x, y) of level [l]'s pixels (centres), 0..1, between the blocks' centres. */
        fun at(l: Int, x: Double, y: Double): Double {
            val k = Math.scalb(1.0, l - level) / 4 // blocks of the map a pixel of level l spans
            val fx = (x + 0.5) * k - 0.5; val fy = (y + 0.5) * k - 0.5
            val ix = floor(fx).toInt().coerceIn(0, max(0, bw - 2)); val iy = floor(fy).toInt().coerceIn(0, max(0, bh - 2))
            val ix2 = min(ix + 1, bw - 1); val iy2 = min(iy + 1, bh - 1)
            val tx = (fx - ix).coerceIn(0.0, 1.0); val ty = (fy - iy).coerceIn(0.0, 1.0)
            fun b(x: Int, y: Int) = if (bits[y * bw + x]) 1.0 else 0.0
            return (b(ix, iy) * (1 - tx) + b(ix2, iy) * tx) * (1 - ty) + (b(ix, iy2) * (1 - tx) + b(ix2, iy2) * tx) * ty
        }
    }

    private val fillMaps = object : LinkedHashMap<String, FillMap>(64, 0.75f, true) {}
    private var fillBytes = 0L
    private const val FILL_CACHE_BYTES = 24L * 1024 * 1024

    /** The least ground a fill covers, metres a side: the sea's are tens of kilometres; a patch of the coast's water
     *  that came out flat is a few hundred metres, and showing the GlobalColorMap through it spotted the coast. */
    private const val FILL_MIN_METRES = 1500.0

    /**
     * [f]'s fill map, from memory or worked out now (a few megabytes read once a texture); [cellMetres] is the ground
     * the texture covers a side.
     */
    private fun fillMap(f: File, ch: FileChannel, d: Dxt, cellMetres: Double): FillMap? {
        synchronized(fillMaps) { fillMaps[f.path] }?.let { return it }
        val level = min(1, d.levels - 1)
        val bw = max(1, (d.levelW(level) + 3) / 4); val bh = max(1, (d.levelH(level) + 3) / 4)
        val minBlocks = (FILL_MIN_METRES / (cellMetres / bw)).let { (it * it).roundToInt() }
        val pal = IntArray(4)
        val four = d.blockBytes == 16
        val colourAt = if (four) 8 else 0
        val like = BooleanArray(bw * bh)
        val buf = ByteBuffer.allocate(bw * d.blockBytes).order(ByteOrder.LITTLE_ENDIAN)
        val base = d.offset(level)
        for (by in 0 until bh) {
            buf.clear()
            var pos = base + by.toLong() * bw * d.blockBytes
            while (buf.hasRemaining()) { val k = ch.read(buf, pos); if (k <= 0) break; pos += k }
            for (bx in 0 until bw) like[by * bw + bx] = fillLike(buf, bx * d.blockBytes + colourAt, four, pal)
        }
        val mask = fillMask(like, bw, bh, FILL_RADIUS, atW = true, atN = true, atE = true, atS = true)
        val bits = java.util.BitSet(bw * bh)
        for (i in mask.indices) if (mask[i] > 0f) bits.set(i)
        dropSmall(bits, bw, bh, minBlocks)
        val map = FillMap(bw, bh, level, bits)
        synchronized(fillMaps) {
            fillMaps.put(f.path, map)?.let { fillBytes -= it.bw.toLong() * it.bh / 8 }
            fillBytes += bw.toLong() * bh / 8
            val it = fillMaps.entries.iterator()
            while (fillBytes > FILL_CACHE_BYTES && it.hasNext()) { val e = it.next(); fillBytes -= e.value.bw.toLong() * e.value.bh / 8; it.remove() }
        }
        return map
    }

    /** Clears each patch of [bits] (side by side, not corner to corner) smaller than [minBlocks] that does not reach
     *  the texture's edge (where it may go on in the next cell). */
    private fun dropSmall(bits: java.util.BitSet, bw: Int, bh: Int, minBlocks: Int) {
        val seen = java.util.BitSet(bw * bh)
        val queue = IntArray(bw * bh)
        var start = bits.nextSetBit(0)
        while (start >= 0) {
            if (!seen[start]) {
                var head = 0; var tail = 0
                queue[tail++] = start; seen.set(start)
                var edge = false
                while (head < tail) {
                    val q = queue[head++]
                    val x = q % bw; val y = q / bw
                    if (x == 0 || y == 0 || x == bw - 1 || y == bh - 1) edge = true
                    if (x > 0) { val n = q - 1; if (bits[n] && !seen[n]) { seen.set(n); queue[tail++] = n } }
                    if (x < bw - 1) { val n = q + 1; if (bits[n] && !seen[n]) { seen.set(n); queue[tail++] = n } }
                    if (y > 0) { val n = q - bw; if (bits[n] && !seen[n]) { seen.set(n); queue[tail++] = n } }
                    if (y < bh - 1) { val n = q + bw; if (bits[n] && !seen[n]) { seen.set(n); queue[tail++] = n } }
                }
                if (!edge && tail < minBlocks) for (i in 0 until tail) bits.clear(queue[i])
            }
            start = bits.nextSetBit(start + 1)
        }
    }

    /**
     * The level's pixels [x0, x0 + w) × [y0, y0 + h), ARGB (the colour block is all that is read). With [fill], the
     * alpha says how much of a pixel is **not** fill (0: all fill): BMS's 4K and 16K cells paint the sea it draws
     * water over with flat navy — exactly one colour, in patches of a few shades — and [paintGrid] lets the coarser
     * layer show through it.
     */
    private fun decode(ch: FileChannel, d: Dxt, level: Int, x0: Int, y0: Int, w: Int, h: Int, fill: FillMap? = null): IntArray {
        val lw = d.levelW(level)
        val bw = max(1, (lw + 3) / 4)
        val out = IntArray(w * h)
        val bx0 = x0 / 4; val bx1 = (x0 + w - 1) / 4
        val by0 = y0 / 4; val by1 = (y0 + h - 1) / 4
        val ax0 = bx0; val ay0 = by0
        val nb = bx1 - bx0 + 1; val nr = by1 - by0 + 1
        val rowBytes = nb * d.blockBytes
        val buf = ByteBuffer.allocate(rowBytes * nr).order(ByteOrder.LITTLE_ENDIAN)
        val base = d.offset(level)
        for (q in 0 until nr) {
            buf.limit((q + 1) * rowBytes); buf.position(q * rowBytes)
            var pos = base + ((ay0 + q).toLong() * bw + ax0) * d.blockBytes
            while (buf.hasRemaining()) { val k = ch.read(buf, pos); if (k <= 0) break; pos += k }
        }
        buf.clear()
        val pal = IntArray(4)
        val four = d.blockBytes == 16
        val colourAt = if (four) 8 else 0
        for (by in by0..by1) for (bx in bx0..bx1) {
            val qx = bx - ax0; val qy = by - ay0
            val o = (qy * nb + qx) * d.blockBytes + colourAt
            palette(buf, o, four, pal)
            val idx = buf.getInt(o + 4)
            for (j in 0 until 4) {
                val y = by * 4 + j - y0
                if (y < 0 || y >= h) continue
                for (i in 0 until 4) {
                    val x = bx * 4 + i - x0
                    if (x < 0 || x >= w) continue
                    var p = pal[(idx ushr (2 * (j * 4 + i))) and 3]
                    if (fill != null) {
                        val m = fill.at(level, (bx * 4 + i).toDouble(), (by * 4 + j).toDouble())
                        p = (p and 0x00FFFFFF) or (((1 - m) * 255).roundToInt().coerceIn(0, 255) shl 24)
                    }
                    out[y * w + x] = p
                }
            }
        }
        return out
    }

    /**
     * Which of [nb] × [nr] blocks are fill, 1f or 0f ([fillMap]), from which are [flat] ([fillLike]): a core block has
     * every block [r] all round flat (in runs along rows, then columns, so a wide [r] costs little; past the blocks
     * given only the texture's own edge agrees — [atW], [atN], [atE], [atS] say where they reach it), and a fill block
     * is within [r] + [FILL_GROW] of a core: the flat patch itself, if it is that wide, and its seams.
     */
    private fun fillMask(flat: BooleanArray, nb: Int, nr: Int, r: Int, atW: Boolean, atN: Boolean, atE: Boolean, atS: Boolean): FloatArray {
        // r flat blocks either side along the row
        val row = BooleanArray(nb * nr)
        val left = IntArray(nb); val right = IntArray(nb)
        for (y in 0 until nr) {
            for (x in 0 until nb) { val f = flat[y * nb + x]; left[x] = if (!f) 0 else if (x > 0) left[x - 1] + 1 else if (atW) r + 1 else 1 }
            for (x in nb - 1 downTo 0) { val f = flat[y * nb + x]; right[x] = if (!f) 0 else if (x < nb - 1) right[x + 1] + 1 else if (atE) r + 1 else 1 }
            for (x in 0 until nb) row[y * nb + x] = left[x] > r && right[x] > r
        }
        // and so r rows above and below
        val core = BooleanArray(nb * nr)
        val up = IntArray(nr); val down = IntArray(nr)
        for (x in 0 until nb) {
            for (y in 0 until nr) { val f = row[y * nb + x]; up[y] = if (!f) 0 else if (y > 0) up[y - 1] + 1 else if (atN) r + 1 else 1 }
            for (y in nr - 1 downTo 0) { val f = row[y * nb + x]; down[y] = if (!f) 0 else if (y < nr - 1) down[y + 1] + 1 else if (atS) r + 1 else 1 }
            for (y in 0 until nr) core[y * nb + x] = up[y] > r && down[y] > r
        }
        // grown back by r (so a fill keeps its own extent: an opening) and FILL_GROW more, rows then columns
        val g = r + FILL_GROW
        val across = BooleanArray(nb * nr)
        for (y in 0 until nr) {
            var last = -1_000_000
            for (x in 0 until nb) { if (core[y * nb + x]) last = x; if (x - last <= g) across[y * nb + x] = true }
            last = 1_000_000
            for (x in nb - 1 downTo 0) { if (core[y * nb + x]) last = x; if (last - x <= g) across[y * nb + x] = true }
        }
        val fill = FloatArray(nb * nr)
        for (x in 0 until nb) {
            var last = -1_000_000
            for (y in 0 until nr) { if (across[y * nb + x]) last = y; if (y - last <= g) fill[y * nb + x] = 1f }
            last = 1_000_000
            for (y in nr - 1 downTo 0) { if (across[y * nb + x]) last = y; if (last - y <= g) fill[y * nb + x] = 1f }
        }
        return fill
    }

    /**
     * The level's pixels at the columns [xs] and rows [ys] (each ascending), as ys.size × xs.size ARGB — each the
     * mean of the [box] × [box] square (1, 2 or 4: its whole block) it falls in, so a picture read at a stride is
     * box-filtered rather than point-sampled. For a texture far sharper than what is drawn and with no smaller mip
     * level (the Falklands' GlobalColorMap is 16,384 px in one level): reading it whole would take a gigabyte.
     */
    private fun decodeSampled(ch: FileChannel, d: Dxt, level: Int, xs: IntArray, ys: IntArray, box: Int = 4): IntArray {
        val lw = d.levelW(level)
        val bw = max(1, (lw + 3) / 4)
        val out = IntArray(xs.size * ys.size)
        if (xs.isEmpty() || ys.isEmpty()) return out
        val bxs = IntArray(xs.size) { xs[it] / 4 }
        val bx0 = bxs.first(); val nb = bxs.last() - bx0 + 1
        val buf = ByteBuffer.allocate(nb * d.blockBytes).order(ByteOrder.LITTLE_ENDIAN)
        val base = d.offset(level)
        val pal = IntArray(4)
        val four = d.blockBytes == 16
        val colourAt = if (four) 8 else 0
        val means = IntArray(nb)
        var lastRow = -1
        for ((yi, y) in ys.withIndex()) {
            val by = y / 4
            if (by != lastRow) {
                buf.clear()
                var pos = base + (by.toLong() * bw + bx0) * d.blockBytes
                while (buf.hasRemaining()) { val k = ch.read(buf, pos); if (k <= 0) break; pos += k }
                java.util.Arrays.fill(means, 0)
                lastRow = by
            }
            for ((xi, bx) in bxs.withIndex()) {
                val q = bx - bx0
                if (box >= 4) {
                    if (means[q] == 0) {
                        val o = q * d.blockBytes + colourAt
                        palette(buf, o, four, pal)
                        val idx = buf.getInt(o + 4)
                        var r = 0; var g = 0; var b = 0
                        for (k in 0 until 16) { val p = pal[(idx ushr (2 * k)) and 3]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF }
                        means[q] = rgbInt(r / 16, g / 16, b / 16)
                    }
                    out[yi * xs.size + xi] = means[q]
                } else {
                    val o = q * d.blockBytes + colourAt
                    palette(buf, o, four, pal)
                    val idx = buf.getInt(o + 4)
                    val i0 = (xs[xi] and 3) / box * box; val j0 = (y and 3) / box * box
                    var r = 0; var g = 0; var b = 0
                    for (j in j0 until j0 + box) for (i in i0 until i0 + box) {
                        val p = pal[(idx ushr (2 * (j * 4 + i))) and 3]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                    }
                    val n = box * box
                    out[yi * xs.size + xi] = rgbInt(r / n, g / n, b / n)
                }
            }
        }
        return out
    }

    private fun rgbInt(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * Paints a grid of [cells] × [cells] textures over the tile, each by bilinear sampling from the mip level that is
     * just sharper than the tile: [file] gives a cell's texture, or null where the grid has none (open sea). The
     * textures cover the terrain, not the map's square ([Terrain.scale]). With [fillThrough], a texture's flat fill
     * ([decode]) leaves a pixel a coarser layer already painted as it is. A texture more than twice as sharp as the
     * tile even at its smallest level is read at a stride ([decodeSampled]), never whole.
     */
    private fun paintGrid(t: Terrain, out: IntArray, size: Int, z: Int, r: Int, c: Int, cells: Int, fillThrough: Boolean, file: (Int, Int) -> File?) {
        val tiles = (1 shl z).toDouble()
        // the tile on the terrain, in fractions of its width from the west and the north
        val span = t.scale / tiles
        val u0 = t.scale * c / tiles + t.halfSample; val u1 = u0 + span
        val v0 = t.scale * r / tiles + (1 - t.scale - t.halfSample); val v1 = v0 + span
        val cellOut = size / (cells * span) // output pixels a cell spans
        val cc0 = floor(u0 * cells).toInt().coerceIn(0, cells - 1)
        val cc1 = (ceil(u1 * cells).toInt() - 1).coerceIn(0, cells - 1)
        val cr0 = floor(v0 * cells).toInt().coerceIn(0, cells - 1)
        val cr1 = (ceil(v1 * cells).toInt() - 1).coerceIn(0, cells - 1)
        for (cr in cr0..cr1) for (cc in cc0..cc1) {
            val f = file(cr, cc) ?: continue
            try {
                open(f).use { ch ->
                    val d = dxt(f, ch) ?: return@use
                    // where the cell's left and top edges fall in the tile's pixels
                    val left = (cc.toDouble() / cells - u0) / span * size
                    val top = (cr.toDouble() / cells - v0) / span * size
                    val level = floor(ln(max(1.0, d.width / cellOut)) / ln(2.0)).toInt().coerceIn(0, d.levels - 1)
                    val lw = d.levelW(level); val lh = d.levelH(level)
                    val s = lw / cellOut // texture pixels a tile pixel
                    val ox0 = max(0, floor(left).toInt()); val ox1 = min(size, ceil(left + cellOut).toInt())
                    val oy0 = max(0, floor(top).toInt()); val oy1 = min(size, ceil(top + cellOut).toInt())
                    if (ox1 <= ox0 || oy1 <= oy0) return@use
                    if (s > 2.0) {
                        // no level small enough: one block's mean a pixel, read at a stride
                        val xs = IntArray(ox1 - ox0) { floor((ox0 + it + 0.5 - left) * s).toInt().coerceIn(0, lw - 1) }
                        val ys = IntArray(oy1 - oy0) { floor((oy0 + it + 0.5 - top) * s).toInt().coerceIn(0, lh - 1) }
                        val px = decodeSampled(ch, d, level, xs, ys)
                        for (y in oy0 until oy1) for (x in ox0 until ox1) out[y * size + x] = px[(y - oy0) * xs.size + (x - ox0)]
                        return@use
                    }
                    val sx0 = floor((ox0 + 0.5 - left) * s - 0.5).toInt() - 1
                    val sx1 = ceil((ox1 - 0.5 - left) * s - 0.5).toInt() + 2
                    val sy0 = floor((oy0 + 0.5 - top) * s - 0.5).toInt() - 1
                    val sy1 = ceil((oy1 - 0.5 - top) * s - 0.5).toInt() + 2
                    val rx0 = sx0.coerceIn(0, lw - 1) and 3.inv(); val rx1 = sx1.coerceIn(0, lw - 1)
                    val ry0 = sy0.coerceIn(0, lh - 1) and 3.inv(); val ry1 = sy1.coerceIn(0, lh - 1)
                    val rw = rx1 - rx0 + 1; val rh = ry1 - ry0 + 1
                    val fill = if (fillThrough) fillMap(f, ch, d, t.sizeKm * 1000.0 / cells) else null
                    val src = decode(ch, d, level, rx0, ry0, rw, rh, fill)
                    for (y in oy0 until oy1) {
                        val sy = ((y + 0.5 - top) * s - 0.5 - ry0).coerceIn(0.0, rh - 1.0)
                        val yi = min(sy.toInt(), rh - 2).coerceAtLeast(0)
                        val fy = (sy - yi).coerceIn(0.0, 1.0)
                        val y2 = min(yi + 1, rh - 1)
                        for (x in ox0 until ox1) {
                            val sx = ((x + 0.5 - left) * s - 0.5 - rx0).coerceIn(0.0, rw - 1.0)
                            val xi = min(sx.toInt(), rw - 2).coerceAtLeast(0)
                            val fx = (sx - xi).coerceIn(0.0, 1.0)
                            val x2 = min(xi + 1, rw - 1)
                            val a = src[yi * rw + xi]; val b = src[yi * rw + x2]; val cc = src[y2 * rw + xi]; val dd = src[y2 * rw + x2]
                            val o = y * size + x
                            val px = bilinear(a, b, cc, dd, fx, fy)
                            val under = out[o]
                            if (fill == null || under == 0) { out[o] = px; continue }
                            // over a coarser layer's picture a fill (alpha 0) leaves that picture, its edge blended
                            fun al(p: Int) = (p ushr 24) / 255.0
                            val k = (al(a) * (1 - fx) + al(b) * fx) * (1 - fy) + (al(cc) * (1 - fx) + al(dd) * fx) * fy
                            out[o] = if (k >= 0.999) px else if (k <= 0.001) under else argb(
                                ((px shr 16) and 0xFF) * k + ((under shr 16) and 0xFF) * (1 - k),
                                ((px shr 8) and 0xFF) * k + ((under shr 8) and 0xFF) * (1 - k),
                                (px and 0xFF) * k + (under and 0xFF) * (1 - k))
                        }
                    }
                }
            } catch (_: Exception) {
                // a texture that cannot be read leaves the coarser layer showing
            }
        }
    }

    private fun bilinear(a: Int, b: Int, c: Int, d: Int, fx: Double, fy: Double): Int {
        fun ch(s: Int): Double {
            val top = ((a shr s) and 0xFF) * (1 - fx) + ((b shr s) and 0xFF) * fx
            val bot = ((c shr s) and 0xFF) * (1 - fx) + ((d shr s) and 0xFF) * fx
            return top * (1 - fy) + bot * fy
        }
        return argb(ch(16), ch(8), ch(0))
    }

    /** The bundled satellite's auto levels (maps.mjs `readSatellite`): luminance's 1st and 99.5th percentile. */
    internal fun levels(t: Terrain): IntArray {
        t.levels?.let { return it }
        val f = t.file("", "GlobalColorMap.dds")?.takeIf { it.isFile } ?: t.photo?.listFiles()?.firstOrNull { it.name.equals("GlobalColorMap.dds", true) }
        val lv = try {
            f?.let { file ->
                open(file).use { ch ->
                    val d = dxt(file, ch) ?: return@use null
                    var level = 0
                    while (d.levelW(level) > 2048 && level < d.levels - 1) level++
                    // a picture with no level of 2,048 px or less (the Falklands': 16,384 px, one level) is read at a
                    // stride: whole, it took a gigabyte, and the PC's 512 MB failed every satellite tile of the theater.
                    // Each sample the mean of the pixels one of maps.mjs's 8,192 px has, so the percentiles agree
                    // with the bundled levels' (16, 1442 in the Falklands; block means gave 16, 1526, a brighter seam)
                    val stride = max(1, (d.levelW(level) + 2047) / 2048)
                    val px = if (stride == 1) decode(ch, d, level, 0, 0, d.levelW(level), d.levelH(level))
                    else decodeSampled(ch, d, level, IntArray(d.levelW(level) / stride) { it * stride }, IntArray(d.levelH(level) / stride) { it * stride },
                        box = (d.levelW(level) / 8192).coerceIn(1, 4))
                    val hist = IntArray(256)
                    for (v in px) hist[((((v shr 16) and 0xFF) * 0.3 + ((v shr 8) and 0xFF) * 0.59 + (v and 0xFF) * 0.11)).roundToInt().coerceIn(0, 255)]++
                    val total = px.size.toLong()
                    var acc = 0L; var lo = 0; var hi = 255
                    for (v in 0 until 256) { acc += hist[v]; if (acc < total * 0.01) lo = v; if (acc < total * 0.995) hi = v }
                    intArrayOf(lo, (235.0 / max(40, hi - lo) * 1000).roundToInt())
                }
            }
        } catch (_: Exception) { null } ?: intArrayOf(0, 1000)
        t.levels = lv
        return lv
    }

    /**
     * One layer of the photoreal: [cells] across the theater, painted from level [fromZ], [file] a cell's texture;
     * with [fillThrough] its flat fills let the coarser layers show ([paintGrid]).
     */
    private class PhotoLayer(val cells: Int, val fromZ: Int, val fillThrough: Boolean = false, val file: (Int, Int) -> File?)

    /**
     * The maps whose 4K and 16K fills show the GlobalColorMap under them ([PhotoLayer.fillThrough]). Every theater's
     * cells fill their sea with flat navy (4.38.1's 4K cells: Korea 4 % of their blocks, Israel 9, the Falklands 15),
     * but only the Falklands' GlobalColorMap — the bundled levels — shows that sea in another colour: a light blue 42 a
     * channel from the fills on average, where Korea's is a navy 15 from them, and showing it through Korea's fills only
     * spotted its sea. The other three theaters' GlobalColorMaps are BC7, which the PC does not read, so there is
     * nothing under their fills to show.
     */
    private val FILL_THROUGH = setOf("falklands")

    private fun photoLayers(t: Terrain): List<PhotoLayer> {
        val km = t.sizeKm
        val global = t.photo?.listFiles()?.firstOrNull { it.name.equals("GlobalColorMap.dds", true) }
        // cells of 64 km (4,096 px), then 32 km (8,192 px), then the 1 km Hires cells round the airbases — in the
        // Falklands' 2,048 km too: 32 × 32 4K cells (1-1024), 64 × 64 16K, 2,048 × 2,048 Hires (all 16 named `x…`, off)
        val g4 = (km / 64).roundToInt()
        val g16 = (km / 32).roundToInt()
        val gh = km.roundToInt()
        val through = t.id in FILL_THROUGH
        return listOf(
            PhotoLayer(if (global != null) 1 else 0, 0) { _, _ -> global },
            PhotoLayer(g4, 5, fillThrough = through) { row, col -> "${row * g4 + col + 1}.dds".takeIf { t.has("4K", it) }?.let { t.file("4K", it) } },
            PhotoLayer(g16, 7, fillThrough = through) { row, col -> "${row * g16 + col + 1}.dds".takeIf { t.has("16K", it) }?.let { t.file("16K", it) } },
            // the Hires names count from 1 too: R1_C1 is the north-west cell (Osan's runways are R670_C470)
            PhotoLayer(gh, 10) { row, col -> "R${row + 1}_C${col + 1}.dds".takeIf { t.has("Hires/Day", it) }?.let { t.file("Hires/Day", it) } },
        )
    }

    /** A satellite tile; [only] (0 GlobalColorMap, 1 4K, 2 16K, 3 Hires) paints that layer alone at any level (`--satalign`). */
    internal fun satellite(t: Terrain, z: Int, r: Int, c: Int, size: Int, only: Int? = null): IntArray? {
        t.photo ?: return null
        val out = IntArray(size * size)
        photoLayers(t).forEachIndexed { i, l ->
            if (l.cells > 0 && (if (only == null) z >= l.fromZ else i == only)) paintGrid(t, out, size, z, r, c, l.cells, l.fillThrough && only == null, l.file)
        }
        val (lo, k) = levels(t).let { it[0] to it[1] / 1000.0 }
        for (i in out.indices) {
            val v = out[i]
            if (v == 0) { out[i] = argb(18.0, 32.0, 46.0); continue }
            out[i] = argb((((v shr 16) and 0xFF) - lo) * k, (((v shr 8) and 0xFF) - lo) * k, ((v and 0xFF) - lo) * k)
        }
        return out
    }

    // ================================================================ the bundled topographic levels

    /**
     * `--maptiles <BMS folder> <assets folder> [ids] [styles] [maxZ]`: writes the bundled levels of [styles] (topo by
     * default) — the 1,024 px overview `maps/<id>/<style>.webp` and `maps/<id>/<style>/<z>/<r>_<c>.webp` for z = 2 …
     * [maxZ] — the way maps.mjs lays the other styles out. Reads the BMS folder only.
     */
    fun bundle(bms: File, assets: File, ids: List<String>, styles: List<String>, maxZ: Int): String = buildString {
        rootOverride = bms
        for (id in ids) {
            val t = terrain(id) ?: run { appendLine("$id: no terrain"); return@run null } ?: continue
            for (style in styles) {
                var bytes = 0L
                val t0 = System.currentTimeMillis()
                val overview = File(assets, "maps/$id/$style.webp")
                overview.parentFile.mkdirs()
                render(t, style, 0, 0, 0, 1024)?.let { overview.writeBytes(encode(it, 1024, EncodedImageFormat.WEBP, 82)); bytes += overview.length() }
                for (z in 2..maxZ) {
                    val dir = File(assets, "maps/$id/$style/$z")
                    dir.deleteRecursively(); dir.mkdirs()
                    val count = 1 shl z
                    for (r in 0 until count) for (c in 0 until count) {
                        val px = render(t, style, z, r, c, TILE) ?: continue
                        val f = File(dir, "${r}_$c.webp")
                        f.writeBytes(encode(px, TILE, EncodedImageFormat.WEBP, 82))
                        bytes += f.length()
                    }
                }
                appendLine("$id $style: ${"%.1f".format(bytes / 1e6)} MB, ${(System.currentTimeMillis() - t0) / 1000} s")
            }
        }
    }
}
