package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampGround
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The ground's height under points of a theater, from Falcon BMS's own height map, for the Planner's attack pages
 * (`GET /api/campaign/ground`, [CampaignRoutes]).
 *
 * WDP's Pop-up page adds the ground under the target to every height it hands the jet (`Get_Coords`:
 * `TargetElv = fclsMain.GetTerrainHeight(target)`; the ingress altitude, the pull-down altitude and the VIP, VRP, PUP
 * and OA1 elevations carry it). `GetTerrainHeight` reads the theater's `<newterraindir>/HeightMaps/HeightMap.raw`
 * (`ReadNewTerrainElvLoc`): a square of int16 feet, the row the position's distance from the theater's north edge and
 * the column its east over the theater's size, each times the side and rounded half to even, two bytes at that cell
 * (on the last Korea save WDP's page carried 65 ft under steerpoint 3, its Setup.ini keeping `IngressAlt=565` for a
 * 500 ft ingress). That takes the height map for the campaign grid's square, which it is not: BMS lays its terrain in
 * metres of 3.28084 ft from the grid's south-west corner ([MapTiles.TerrainFrame]), so WDP's cell lies up to 8 samples
 * (880 ft) from the point in a 1,024 km theater's north-east. This reads the height map where BMS lays it, bilinear
 * between the four samples round the point, as BMS stands its ground units (D101; WDP's cell is kept for `--elevframe`).
 * Then, unlike WDP, it applies BMS's terrain levelling ([TerrainLevelling]), so a point on a levelled airbase reads the
 * ground the sim flattens it to.
 *
 * Read only: the file is opened for reading and shared, eight bytes a point, never whole (it is 2 GB in Korea). WDP
 * opens it for reading **and writing** with no sharing (`File.Open(path, FileMode.Open)`), so its read can fail while
 * another program holds the file; a point it cannot read comes back null here, never an exception.
 */
object TerrainHeights {
    /** The feet above sea level under each (north, east) of [points], in order; null where the map says nothing. */
    fun at(set: Theaters.TheaterSet, t: Theaters.Theater, points: List<Pair<Double, Double>>): CampGround {
        val file = heightMap(set, t) ?: run {
            // no height map: a point on a levelled airbase still has the level BMS gives it
            val levels = TerrainLevelling.of(set, t)
            val core = points.map { (n, e) -> if (n.isNaN() || e.isNaN()) null else levels.core(n, e)?.let { Math.round(it.level).toInt() } }
            return CampGround(t.name, core, if (core.all { it == null }) "Falcon BMS has no height map for ${t.name}." else null)
        }
        val levels = TerrainLevelling.of(set, t)
        val ground = try { read(file, t, points, wdp = false) } catch (e: Exception) {
            return CampGround(t.name, points.map { null }, "The height map of ${t.name} could not be read: ${e.message}")
        } ?: return CampGround(t.name, points.map { null }, "The app does not know the size of ${t.name}.")
        return CampGround(t.name, points.mapIndexed { i, (north, east) -> levelled(levels, north, east, ground[i]) })
    }

    /**
     * For checks (`--elevframe`): the height map alone under [points] — BMS's ground ([wdp] false) or the cell WDP's
     * `ReadNewTerrainElvLoc` reads (true) — or null when the theater has no height map or its size is not known.
     */
    internal fun heightMapAt(set: Theaters.TheaterSet, t: Theaters.Theater, points: List<Pair<Double, Double>>, wdp: Boolean): List<Double?>? =
        heightMap(set, t)?.let { read(it, t, points, wdp) }

    /**
     * The ground BMS puts there: the height map's [ground] with the theater's terrain levelling applied
     * ([TerrainLevelling]: inside an airbase's levelled polygon its level, in the rings round it a mix). WDP reads the
     * height map alone (D99).
     */
    private fun levelled(levels: TerrainLevelling.Levels, north: Double, east: Double, ground: Double?): Int? {
        if (levels.isEmpty || north.isNaN() || east.isNaN()) return ground?.let { Math.round(it).toInt() }
        return levels.at(north, east, ground)?.let { Math.round(it).toInt() }
    }

    /** [file]'s heights under [points] in feet, null where it says nothing; null when the size is unknown, throws when it cannot be opened. */
    private fun read(file: File, t: Theaters.Theater, points: List<Pair<Double, Double>>, wdp: Boolean): List<Double?>? {
        val len = file.length()
        val cells = Math.rint(sqrt(len / 2.0)).toInt()
        // the size of the terrain this height map belongs to (`NewTerrain/Theater.txt`), else the app's for the theater
        val km = MapTiles.sizeKmOf(file.parentFile?.parentFile) ?: theaterKm(t) ?: return null
        val frame = MapTiles.TerrainFrame(km, cells)
        return RandomAccessFile(file, "r").use { f ->
            points.map { (north, east) -> if (wdp) wdpCell(f, len, frame, north, east)?.toDouble() else ground(f, len, frame, north, east) }
        }
    }

    /**
     * BMS's ground at a point: the height map where BMS lays it ([MapTiles.TerrainFrame]: metres of 3.28084 ft, sample
     * k's centre k samples from the grid's south-west corner), bilinear between the four samples round the point — the
     * height BMS stands its ground units on, to the recording's 0.01 m (`--elevframe`, D101).
     */
    private fun ground(f: RandomAccessFile, len: Long, frame: MapTiles.TerrainFrame, north: Double, east: Double): Double? {
        val n = frame.n
        if (n <= 0 || north.isNaN() || east.isNaN() || north < 0 || east < 0 || north > frame.sizeFt || east > frame.sizeFt) return null
        val col = frame.colAt(east)
        val row = frame.rowAt(north)
        val c0 = floor(col).toInt()
        val r0 = floor(row).toInt()
        val fx = col - c0
        val fy = row - r0
        fun s(r: Int, c: Int) = sample(f, len, n.toLong(), r.coerceIn(0, n - 1).toLong(), c.coerceIn(0, n - 1).toLong())
        val nw = s(r0, c0) ?: return null
        val ne = s(r0, c0 + 1) ?: return null
        val sw = s(r0 + 1, c0) ?: return null
        val se = s(r0 + 1, c0 + 1) ?: return null
        return (nw * (1 - fx) + ne * fx) * (1 - fy) + (sw * (1 - fx) + se * fx) * fy
    }

    /**
     * `ReadNewTerrainElvLoc`'s cell, kept for the checks: the row from the north edge, the column from the west, each
     * over the campaign grid's side, clamped to the side — the height map taken as the grid's square (D101).
     */
    private fun wdpCell(f: RandomAccessFile, len: Long, frame: MapTiles.TerrainFrame, north: Double, east: Double): Int? {
        val cells = frame.n.toLong()
        val side = frame.sizeFt
        if (cells <= 0 || side <= 0.0 || north.isNaN() || east.isNaN()) return null
        // WDP's positions are floats: the target it reads the ground under is the float the page holds
        val n = north.toFloat().toDouble()
        val e = east.toFloat().toDouble()
        val row = Math.rint((side - n) / side * cells)
        val col = Math.rint(e / side * cells)
        if (row < 0 || col < 0) return null
        return sample(f, len, cells, minOf(row.toLong(), cells), minOf(col.toLong(), cells))
    }

    /** The int16 at ([row], [col]) of a [cells]-wide map; null past the file's end or when it cannot be read. */
    private fun sample(f: RandomAccessFile, len: Long, cells: Long, row: Long, col: Long): Int? {
        val at = row * cells * 2 + col * 2
        if (at + 2 > len) return null
        return try {
            f.seek(at)
            val lo = f.read()
            val hi = f.read()
            if (lo < 0 || hi < 0) null else ((hi shl 8) or lo).toShort().toInt()
        } catch (_: Exception) {
            null
        }
    }

    /** `<newterraindir>/HeightMaps/HeightMap.raw` of the theater's definition, found without regard to case. */
    internal fun heightMap(set: Theaters.TheaterSet, t: Theaters.Theater): File? {
        val rel = try {
            t.tdf.readLines().firstNotNullOfOrNull { line ->
                val l = line.trim()
                if (l.startsWith("newterraindir", ignoreCase = true)) l.substring("newterraindir".length).trim().takeIf { it.isNotEmpty() } else null
            }
        } catch (_: Exception) { null }
        // a definition without `newterraindir` (Israel, Hellas WCP, the Korea add-ons…): the sim flies its terrain
        // folder's NewTerrain, the base Korea's when it names none (Korea TvT). WDP finds nothing there (D99).
        val dir = (rel?.let { Theaters.resolveDir(set.data, it) })
            ?: (t.terrainDir ?: "TerrData\\Korea").let { Theaters.resolveDir(set.data, it) }?.let { Theaters.resolveDir(it, "NewTerrain") }
            ?: return null
        return Theaters.resolveDir(dir, "HeightMaps")?.let { Theaters.resolveFile(it, "HeightMap.raw") }
    }

    private val sizes = HashMap<String, Double?>()

    /** The theater's size in km as the app's data has it (its terrain's projection record). */
    private fun theaterKm(t: Theaters.Theater): Double? = synchronized(sizes) {
        sizes.getOrPut(t.appId) {
            try {
                kotlinx.coroutines.runBlocking { Repo.index().theaters.firstOrNull { it.id == t.appId } }?.projection?.sizeKm?.takeIf { it > 0.0 }
            } catch (_: Exception) { null }
        }
    }
}
