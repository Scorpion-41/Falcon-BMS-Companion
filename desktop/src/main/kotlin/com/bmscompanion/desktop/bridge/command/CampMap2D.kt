package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.app.ui.components.BmsChartList
import com.bmscompanion.app.ui.components.BmsChartMap
import com.bmscompanion.desktop.bridge.ApiRequest
import com.bmscompanion.desktop.bridge.ApiResponse
import com.bmscompanion.desktop.bridge.Bridge
import com.bmscompanion.desktop.bridge.MapTiles
import com.bmscompanion.desktop.bridge.Theaters
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * The "BMS chart" map style (`bms2d`): Falcon BMS's own 2D campaign map, the picture its campaign, TE and ATO screens
 * show, read at run time from the theater's art and never shipped.
 *
 * Where: `Data\Art\Cmn_Res.lst` loads `campmap.irc`, which names the resource bundle `art\resource\campmap`, looked up
 * under the theater's `artdir` (`<Data>\<artdir>\Art\Resource`, else `<artdir>\Resource`), else BMS's own
 * `Data\Art\Resource` ([ResourceBundle] reads it). The picture is the bundle's `BIG_MAP_ID`: 16,384² 8-bit with a
 * palette in every main theater (62.5 m a pixel; Falklands 125 m), 4,096² in the Korea 2012 family. It covers the
 * whole campaign square, north up, linearly — the same square as every other style of the app's maps (not
 * `MapTiles.TerrainFrame`, which places the height map and the photoreal textures).
 *
 * How: the `.rsc` holds 270-500 MB, so the picture is memory-mapped (outside the program's heap) and only the rows a
 * tile needs are touched. Level z is 512 · 2^z px across the theater: the picture's own level ([Chart.nativeZ], 5 for
 * 16,384) is copied, a coarser one is box-averaged from it — the three coarsest from a 2,048² reduction made once
 * ([Chart.small]) — and a finer one (up to two steps) enlarged. Tiles are JPEG, kept in memory ([CACHE_BYTES]).
 * Nothing is written anywhere and nothing throws: a tile that cannot be drawn is a reason (404).
 */
object CampMap2D {
    const val STYLE = "bms2d"
    const val TILE = 512
    private const val CACHE_BYTES = 32L * 1024 * 1024
    private const val SMALL = 2048
    private const val MAP_NAME = "BIG_MAP_ID"

    /** One theater's chart: the bundle, the picture, its palette and the mapped pixels. */
    class Chart(
        val theater: Theaters.Theater, val mapId: String?, val bundle: ResourceBundle.Bundle, val image: ResourceBundle.Image,
        val palette: IntArray, val pixels: MappedByteBuffer,
    ) {
        val width: Int get() = image.width
        val height: Int get() = image.height
        /** the level drawn from the picture pixel for pixel (16,384 → 5, 4,096 → 3) */
        val nativeZ: Int get() = (31 - Integer.numberOfLeadingZeros(maxOf(1, width / TILE)))
        /** a [SMALL]² reduction of the whole picture (ARGB), made on first use */
        @Volatile var small: IntArray? = null
    }

    private val charts = ConcurrentHashMap<String, Any>() // theater appId → Chart or a reason (String)
    private val cache = object : LinkedHashMap<String, ByteArray>(128, 0.75f, true) {}
    private var cached = 0L
    private val gate = Semaphore(2)

    /** A check's BMS folder (read only); the program reads the install it found. */
    @Volatile var rootOverride: File? = null
    private fun set(): Theaters.TheaterSet? = (rootOverride ?: Bridge.install.baseDir?.let(::File))?.let { Theaters.at(it) }

    /** Drops the charts and tiles (another Falcon BMS install is read now). */
    fun forget() {
        charts.clear()
        synchronized(cache) { cache.clear(); cached = 0L }
    }

    // ================================================================ which file

    /** The `CampMap.idx` that theater [t] shows: under its `artdir`, else BMS's own; null when there is none. */
    fun locate(set: Theaters.TheaterSet, t: Theaters.Theater): File? {
        val places = ArrayList<String>()
        t.artDir?.trim()?.trim('"', '\\', '/')?.takeIf { it.isNotEmpty() }?.let { a ->
            places += "$a\\Art\\Resource"
            places += "$a\\Resource"
        }
        places += "Art\\Resource"
        for (p in places) {
            val dir = Theaters.resolveDir(set.data, p) ?: continue
            dir.listFiles()?.firstOrNull { it.isFile && it.name.equals("CampMap.idx", ignoreCase = true) }?.let { return it }
        }
        return null
    }

    /** The app's map id ([MapTiles.SOURCES]) of the terrain theater [t] is flown on, by its `terraindir`; null when none matches. */
    fun mapIdOf(set: Theaters.TheaterSet, t: Theaters.Theater): String? {
        // (a definition without a terraindir is flown on BMS's own Korea, as Korea TvT's is)
        val own = Theaters.resolveDir(set.data, t.terrainDir?.takeIf { it.isNotBlank() } ?: "TerrData\\Korea") ?: return null
        val path = runCatching { own.canonicalPath }.getOrDefault(own.absolutePath)
        for ((id, rel) in MapTiles.SOURCES) {
            val d = Theaters.resolveDir(set.root, rel) ?: continue
            val p = runCatching { d.canonicalPath }.getOrDefault(d.absolutePath)
            if (p.equals(path, ignoreCase = true)) return id
        }
        return null
    }

    /** Theater [t]'s chart, opened once; a String is why there is none. */
    fun chart(set: Theaters.TheaterSet, t: Theaters.Theater): Any = charts.getOrPut(t.appId) { open(set, t) }

    private fun open(set: Theaters.TheaterSet, t: Theaters.Theater): Any {
        val idx = locate(set, t) ?: return "No CampMap.idx was found for ${t.name}."
        val b = ResourceBundle.read(idx) ?: return "${idx.name} could not be read."
        val img = b[MAP_NAME] ?: return "${idx.name} has no $MAP_NAME picture."
        if (img.width != img.height || img.width < TILE || Integer.bitCount(img.width) != 1) return "$MAP_NAME is ${img.width} x ${img.height}: not a square of a power of two."
        if (!img.paletted && !img.direct) return "$MAP_NAME is in a layout this reader does not know (flags ${img.flags})."
        val len = img.pixelBytes
        return try {
            FileChannel.open(b.rsc.toPath(), StandardOpenOption.READ).use { ch ->
                if (8 + img.offset + len > ch.size()) return "${b.rsc.name} is shorter than its $MAP_NAME picture."
                val map = ch.map(FileChannel.MapMode.READ_ONLY, 8 + img.offset, len)
                val pal = if (img.paletted) ResourceBundle.palette(b, img) else IntArray(0)
                if (img.paletted && pal.isEmpty()) return "The palette of $MAP_NAME could not be read."
                Chart(t, mapIdOf(set, t), b, img, pal, map)
            }
        } catch (e: Throwable) {
            "${b.rsc.name} could not be opened: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ================================================================ routes

    /** Every theater's chart and, per map id, the one a map is drawn with when it names no theater (`/api/campmap/info`). */
    fun list(): BmsChartList {
        val set = set() ?: return BmsChartList()
        val current = runCatching { set.current(Bridge.install.theater) }.getOrNull()
        val theaters = set.all.mapNotNull { t ->
            val c = chart(set, t) as? Chart ?: return@mapNotNull null
            BmsChartMap(map = c.mapId.orEmpty(), theater = t.appId, name = t.name, nativeZ = c.nativeZ, width = c.width)
        }
        val maps = theaters.filter { it.map.isNotEmpty() }.groupBy { it.map }.map { (_, all) ->
            all.firstOrNull { current != null && it.theater == current.appId } ?: all.first()
        }
        return BmsChartList(maps = maps, theaters = theaters)
    }

    fun info(): ApiResponse = ApiResponse.json(Bridge.json.encodeToString(BmsChartList.serializer(), list()))

    /** `GET /api/maptile?map=<id>&style=bms2d[&theater=<app theater id>]&z=&r=&c=`: one 512 px JPEG tile of the chart. */
    fun handle(req: ApiRequest): ApiResponse {
        val z = req.query["z"]?.toIntOrNull()
        val r = req.query["r"]?.toIntOrNull()
        val c = req.query["c"]?.toIntOrNull()
        if (z == null || r == null || c == null || z !in 0..MapTiles.MAX_Z || r !in 0 until (1 shl z) || c !in 0 until (1 shl z))
            return ApiResponse.json("""{"error":"no such tile"}""", 404)
        val a = tile(req.query["map"].orEmpty(), req.query["theater"], z, r, c)
        return a.first?.let { ApiResponse(200, "image/jpeg", it) }
            ?: ApiResponse.json("""{"error":"${(a.second ?: "no tile").replace("\\", "\\\\").replace("\"", "\\\"")}"}""", 404)
    }

    /** The chart a request means: [theater] by app id, else the default for map [mapId] ([list]). */
    fun chartFor(mapId: String, theater: String?): Any {
        val set = set() ?: return "Falcon BMS was not found on this PC."
        val t = theater?.takeIf { it.isNotBlank() }?.let { id -> set.all.firstOrNull { it.appId == id } }
            ?: list().maps.firstOrNull { it.map == mapId }?.let { m -> set.all.firstOrNull { it.appId == m.theater } }
            ?: return "No BMS chart is known for $mapId."
        return chart(set, t)
    }

    /** One tile: (JPEG, null) or (null, why). */
    fun tile(mapId: String, theater: String?, z: Int, r: Int, c: Int): Pair<ByteArray?, String?> {
        val ch = chartFor(mapId, theater) as? Chart ?: return null to (chartFor(mapId, theater) as? String)
        if (z > ch.nativeZ + 2) return null to "Past BMS's own chart (level ${ch.nativeZ + 2} is its deepest)."
        val key = "${ch.theater.appId}/$z/$r/$c"
        synchronized(cache) { cache[key] }?.let { return it to null }
        gate.acquireUninterruptibly()
        val bytes = try {
            encode(render(ch, z, r, c))
        } catch (e: Throwable) {
            return null to "The tile could not be drawn: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            gate.release()
        }
        synchronized(cache) {
            cache.put(key, bytes)?.let { cached -= it.size }
            cached += bytes.size
            val it = cache.entries.iterator()
            while (cached > CACHE_BYTES && it.hasNext()) { val e = it.next(); cached -= e.value.size; it.remove() }
        }
        return bytes to null
    }

    // ================================================================ drawing

    private fun px(ch: Chart, x: Int, y: Int): Int {
        val i = y.toLong() * ch.width + x
        return if (ch.image.paletted) {
            ch.palette.getOrElse(ch.pixels.get(i.toInt()).toInt() and 0xFF) { 0xFF000000.toInt() }
        } else {
            val o = (i * 2).toInt()
            ResourceBundle.argb555((ch.pixels.get(o).toInt() and 0xFF) or ((ch.pixels.get(o + 1).toInt() and 0xFF) shl 8))
        }
    }

    /** The mean of the [n]² picture pixels from ([x0], [y0]). */
    private fun mean(ch: Chart, x0: Int, y0: Int, n: Int): Int {
        var r = 0L; var g = 0L; var b = 0L
        for (y in y0 until y0 + n) for (x in x0 until x0 + n) {
            val v = px(ch, x, y)
            r += (v shr 16) and 0xFF; g += (v shr 8) and 0xFF; b += v and 0xFF
        }
        val k = (n.toLong() * n)
        return (0xFF shl 24) or ((r / k).toInt() shl 16) or ((g / k).toInt() shl 8) or (b / k).toInt()
    }

    private fun small(ch: Chart): IntArray {
        ch.small?.let { return it }
        synchronized(ch) {
            ch.small?.let { return it }
            val side = minOf(SMALL, ch.width)
            val f = ch.width / side
            val out = IntArray(side * side)
            for (y in 0 until side) for (x in 0 until side) out[y * side + x] = if (f == 1) px(ch, x, y) else mean(ch, x * f, y * f, f)
            ch.small = out
            return out
        }
    }

    /** Tile ([z], [r], [c]) as ARGB, [TILE]². */
    internal fun render(ch: Chart, z: Int, r: Int, c: Int): IntArray {
        val out = IntArray(TILE * TILE)
        val across = TILE.toLong() shl z // level z's pixels across the theater
        val w = ch.width
        when {
            across <= SMALL && across <= w -> {
                // from the reduction: each level pixel is a block of it
                val s = small(ch)
                val side = minOf(SMALL, w)
                val f = (side / across).toInt()
                for (y in 0 until TILE) for (x in 0 until TILE) {
                    val sx = (c * TILE + x) * f
                    val sy = (r * TILE + y) * f
                    if (f == 1) { out[y * TILE + x] = s[sy * side + sx]; continue }
                    var rr = 0; var gg = 0; var bb = 0
                    for (yy in sy until sy + f) for (xx in sx until sx + f) {
                        val v = s[yy * side + xx]; rr += (v shr 16) and 0xFF; gg += (v shr 8) and 0xFF; bb += v and 0xFF
                    }
                    val k = f * f
                    out[y * TILE + x] = (0xFF shl 24) or ((rr / k) shl 16) or ((gg / k) shl 8) or (bb / k)
                }
            }
            across <= w -> {
                // box-averaged from the picture itself (1 = its own level)
                val f = (w / across).toInt()
                for (y in 0 until TILE) for (x in 0 until TILE) {
                    val sx = (c * TILE + x) * f
                    val sy = (r * TILE + y) * f
                    out[y * TILE + x] = if (f == 1) px(ch, sx, sy) else mean(ch, sx, sy, f)
                }
            }
            else -> {
                // finer than the picture: enlarged, bilinear between pixel centres
                val k = (across / w).toInt()
                for (y in 0 until TILE) {
                    val fy = ((r * TILE + y) + 0.5) / k - 0.5
                    val y0 = fy.toInt().coerceIn(0, ch.height - 1)
                    val y1 = (y0 + 1).coerceAtMost(ch.height - 1)
                    val ty = (fy - y0).coerceIn(0.0, 1.0)
                    for (x in 0 until TILE) {
                        val fx = ((c * TILE + x) + 0.5) / k - 0.5
                        val x0 = fx.toInt().coerceIn(0, w - 1)
                        val x1 = (x0 + 1).coerceAtMost(w - 1)
                        val tx = (fx - x0).coerceIn(0.0, 1.0)
                        out[y * TILE + x] = lerp(lerp(px(ch, x0, y0), px(ch, x1, y0), tx), lerp(px(ch, x0, y1), px(ch, x1, y1), tx), ty)
                    }
                }
            }
        }
        return out
    }

    private fun lerp(a: Int, b: Int, t: Double): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun encode(px: IntArray): ByteArray {
        val bytes = ByteArray(px.size * 4)
        for (i in px.indices) {
            val v = px[i]
            val o = i * 4
            bytes[o] = (v and 0xFF).toByte()
            bytes[o + 1] = ((v shr 8) and 0xFF).toByte()
            bytes[o + 2] = ((v shr 16) and 0xFF).toByte()
            bytes[o + 3] = 0xFF.toByte()
        }
        val info = ImageInfo.makeN32(TILE, TILE, ColorAlphaType.OPAQUE)
        return Image.makeRaster(info, bytes, TILE * 4).use { it.encodeToData(EncodedImageFormat.JPEG, 90)!!.bytes }
    }
}
