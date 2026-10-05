package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampGround
import com.bmscompanion.app.data.wdp.WdpCoords
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

/**
 * The ground's height under points of a theater, from Falcon BMS's own height map, for the Planner's attack pages
 * (`GET /api/campaign/ground`, [CampaignRoutes]).
 *
 * WDP's Pop-up page adds the ground under the target to every height it hands the jet (`Get_Coords`:
 * `TargetElv = fclsMain.GetTerrainHeight(target)`; the ingress altitude, the pull-down altitude and the VIP, VRP, PUP
 * and OA1 elevations carry it). `GetTerrainHeight` reads the theater's `<newterraindir>/HeightMaps/HeightMap.raw`
 * (`ReadNewTerrainElvLoc`): a square of int16 feet, the row the position's distance from the theater's north edge and
 * the column its east over the theater's size, each times the side and rounded half to even, two bytes at that cell.
 * This reads the same cell. On the last Korea save WDP's page carried 65 ft under steerpoint 3 (its Setup.ini kept
 * `IngressAlt=565` for a 500 ft ingress), which is what this answers there.
 *
 * Read only: the file is opened for reading and shared, two bytes a point, never whole (it is 2 GB in Korea). WDP
 * opens it for reading **and writing** with no sharing (`File.Open(path, FileMode.Open)`), so its read can fail while
 * another program holds the file; a point it cannot read comes back null here, never an exception.
 */
object TerrainHeights {
    /** The feet above sea level under each (north, east) of [points], in order; null where the map says nothing. */
    fun at(set: Theaters.TheaterSet, t: Theaters.Theater, points: List<Pair<Double, Double>>): CampGround {
        val file = heightMap(set, t)
            ?: return CampGround(t.name, points.map { null }, "Falcon BMS has no height map for ${t.name}.")
        val side = theaterFeet(t)
            ?: return CampGround(t.name, points.map { null }, "The app does not know the size of ${t.name}.")
        val raf = try { RandomAccessFile(file, "r") } catch (e: Exception) {
            return CampGround(t.name, points.map { null }, "The height map of ${t.name} could not be read: ${e.message}")
        }
        return raf.use { f ->
            val len = try { f.length() } catch (_: Exception) { 0L }
            val cells = Math.rint(sqrt(len / 2.0)).toLong()
            CampGround(t.name, points.map { (north, east) -> read(f, len, cells, side, north, east) })
        }
    }

    /** `ReadNewTerrainElvLoc`'s cell: the row from the north edge, the column from the west, clamped to the side. */
    private fun read(f: RandomAccessFile, len: Long, cells: Long, side: Double, north: Double, east: Double): Int? {
        if (cells <= 0 || side <= 0.0 || north.isNaN() || east.isNaN()) return null
        // WDP's positions are floats: the target it reads the ground under is the float the page holds
        val n = north.toFloat().toDouble()
        val e = east.toFloat().toDouble()
        val row = Math.rint((side - n) / side * cells)
        val col = Math.rint(e / side * cells)
        if (row < 0 || col < 0) return null
        val at = minOf(row.toLong(), cells) * cells * 2 + minOf(col.toLong(), cells) * 2
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
    private fun heightMap(set: Theaters.TheaterSet, t: Theaters.Theater): File? {
        val rel = try {
            t.tdf.readLines().firstNotNullOfOrNull { line ->
                val l = line.trim()
                if (l.startsWith("newterraindir", ignoreCase = true)) l.substring("newterraindir".length).trim().takeIf { it.isNotEmpty() } else null
            }
        } catch (_: Exception) { null } ?: return null
        val dir = Theaters.resolveDir(set.data, rel) ?: return null
        return Theaters.resolveDir(dir, "HeightMaps")?.let { Theaters.resolveFile(it, "HeightMap.raw") }
    }

    private val sizes = HashMap<String, Double?>()

    /** The theater's side in feet, as WDP's coordinate data has it (`CampH`, the app's projection of the theater). */
    private fun theaterFeet(t: Theaters.Theater): Double? = synchronized(sizes) {
        sizes.getOrPut(t.appId) {
            try {
                kotlinx.coroutines.runBlocking { Repo.index().theaters.firstOrNull { it.id == t.appId } }?.let { WdpCoords.coordData(it)?.campH }
            } catch (_: Exception) { null }
        }
    }
}
