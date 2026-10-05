package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampFeature
import com.bmscompanion.app.data.mission.CampFeatures
import com.bmscompanion.app.data.mission.CampObjective
import com.bmscompanion.app.data.mission.CampObjectives
import com.bmscompanion.app.data.wdp.WdpCoords
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

/**
 * What WDP's Target Selection window lists for the DataCard's DMPI box (`cntDataCard.txtDMPI_Pri_MouseDoubleClick` →
 * `fclsTargetSelection`, `fclsFed`): a save's objectives and each one's buildings, read only, from Falcon BMS's own
 * files ([CampaignFiles.objectives], [CampaignFiles.features]; `/api/campaign/objectives`, `/api/campaign/features`).
 *
 * - **Objectives** are the start file's (`.obj`, [CampaignArchive.objectives]): name, type (the class table's objective
 *   type as Strings.txt 500 + type names it, WDP's `TypeToString(3, 4, type)`), position, the team holding it as the
 *   start file has it (WDP reads the save's current owner from its objective deltas, which the app does not read) and
 *   the terrain height there.
 * - **Buildings** are the objective class's features (`ObjectiveRelatedData/OCD_nnnnn/FED_nnnnn.XML`, the OCD number
 *   being the objective's class-table `EntityIdx`), each named by its own class-table entry's feature record
 *   (`Falcon4_FCD.xml`) and placed as WDP's `FillPriTarget` places it: the objective's position plus the feature's
 *   offset, `OffsetY` north and `OffsetX` east (the objective's heading is not applied: WDP does not, and every stock
 *   objective has none).
 * - **Heights** are BMS's height map (`<newterraindir>/HeightMaps/HeightMap.raw`, int16 feet, read two bytes at a
 *   time, never whole) at the cell WDP's `ReadNewTerrainElvLoc` picks: the map is square, the cell's row and column
 *   the position over the theater's size, rounded half to even.
 */
object CampaignTargets {
    /** Strings.txt: an objective type's name is string 500 + the type (501 "Airbase" … 531 "SAM Site"). */
    private const val TYPE_STRINGS = 500
    private const val FCD_FILE = "Falcon4_FCD.xml"

    fun objectives(set: Theaters.TheaterSet, t: Theaters.Theater, file: File): CampObjectives {
        val names = CampaignArchive.names(set, t)
        val save = CampaignArchive.cached(file, names)
        val walk = CampaignArchive.startFile(save)?.let { CampaignArchive.objectives(it) }
        val heights = heights(set, t)
        val types = sortedMapOf<Int, String>()
        val out = try {
            heights?.open()
            walk?.objectives.orEmpty().map { o ->
                val tn = names.ct(o.type)?.type ?: 0
                val type = names.strings[TYPE_STRINGS + tn]?.trim().orEmpty()
                if (tn > 0 && type.isNotEmpty()) types[tn] = type
                val team = save.header?.team(o.owner)?.name?.trim().orEmpty()
                CampObjective(
                    id = o.id.toString(), campId = o.campId, name = o.name.trim().ifEmpty { "$type ${o.campId}".trim() }, type = type,
                    x = o.north, y = o.east, elevFt = heights?.at(o.north, o.east), control = team.ifEmpty { "Team ${o.owner}" } + "(${o.owner})",
                )
            }
        } finally {
            heights?.close()
        }
        return CampObjectives(types.values.toList(), out)
    }

    /** The buildings of objective [objective] ("num/creator"), or null when the save's start file has no such objective. */
    fun features(set: Theaters.TheaterSet, t: Theaters.Theater, file: File, objective: String?): CampFeatures? {
        val id = CampaignArchive.VuId.parse(objective) ?: return null
        val names = CampaignArchive.names(set, t)
        val save = CampaignArchive.cached(file, names)
        val o = CampaignArchive.startFile(save)?.let { CampaignArchive.objectives(it) }?.get(id) ?: return null
        val ocd = names.ct(o.type)?.entityIdx?.takeIf { it >= 0 } ?: return CampFeatures(id.toString(), emptyList())
        val fcd = CampaignArchive.table(set.classFile(t, FCD_FILE), "FCD", setOf("Name"))
        val fed = fedFile(set, t, ocd)?.let { CampaignArchive.table(it, "FED", setOf("FeatureCtIdx", "Value", "OffsetX", "OffsetY")) }.orEmpty()
        val heights = heights(set, t)
        val out = try {
            heights?.open()
            fed.keys.sorted().map { n ->
                val f = fed.getValue(n)
                val ct = f["FeatureCtIdx"]?.trim()?.toIntOrNull()?.let { names.ct(it + 100) }
                val name = ct?.entityIdx?.let { fcd[it]?.get("Name") }?.trim().orEmpty()
                val north = o.north + (f["OffsetY"]?.trim()?.toDoubleOrNull() ?: 0.0)
                val east = o.east + (f["OffsetX"]?.trim()?.toDoubleOrNull() ?: 0.0)
                CampFeature(n, name, north, east, heights?.at(north, east), f["Value"]?.trim()?.toDoubleOrNull()?.toInt() ?: 0)
            }
        } finally {
            heights?.close()
        }
        return CampFeatures(id.toString(), out)
    }

    /** `OCD_nnnnn/FED_nnnnn.XML`, where the extractor looks for it: the 3D data folder, the object folder, then the default one. */
    private fun fedFile(set: Theaters.TheaterSet, t: Theaters.Theater, ocd: Int): File? {
        val id = ocd.toString().padStart(5, '0')
        val roots = listOfNotNull(set.threeDDataDir(t), set.objectDir(t), Theaters.resolveDir(set.data, "TerrData\\Objects"))
        for (r in roots) {
            val dir = Theaters.resolveDir(r, "ObjectiveRelatedData\\OCD_$id") ?: continue
            Theaters.resolveFile(dir, "FED_$id.XML")?.let { return it }
        }
        return null
    }

    /** The theater's height map with its size in feet (the app's projection), or null without either. */
    private fun heights(set: Theaters.TheaterSet, t: Theaters.Theater): Heights? {
        val rel = attempt(null) {
            t.tdf.readLines().firstNotNullOfOrNull { line ->
                val l = line.trim()
                if (l.startsWith("newterraindir", ignoreCase = true)) l.substring("newterraindir".length).trim().takeIf { it.isNotEmpty() } else null
            }
        } ?: return null
        val dir = Theaters.resolveDir(set.data, rel) ?: return null
        val file = Theaters.resolveDir(dir, "HeightMaps")?.let { Theaters.resolveFile(it, "HeightMap.raw") } ?: return null
        val size = synchronized(sizes) {
            sizes.getOrPut(t.appId) {
                attempt(null) { kotlinx.coroutines.runBlocking { Repo.index().theaters.firstOrNull { it.id == t.appId } }?.let { WdpCoords.coordData(it)?.campH } }
            }
        } ?: return null
        return Heights(file, size)
    }

    private val sizes = HashMap<String, Double?>()

    /** WDP's `ReadNewTerrainElvLoc` over the file itself: two bytes a position, the file opened for reading only. */
    private class Heights(val file: File, val campH: Double) {
        private var raf: RandomAccessFile? = null
        private var cells = 0L
        private var len = 0L

        fun open() {
            raf = attempt(null) { RandomAccessFile(file, "r") }
            len = attempt(0L) { raf?.length() ?: 0L }
            cells = Math.rint(sqrt(len / 2.0)).toLong()
        }

        fun close() {
            attempt(Unit) { raf?.close() }
            raf = null
        }

        fun at(north: Double, east: Double): Int? {
            val f = raf ?: return null
            if (cells <= 0 || campH <= 0.0) return null
            val row = Math.rint((campH - north) / campH * cells)
            val col = Math.rint(east / campH * cells)
            if (row.isNaN() || col.isNaN() || row < 0 || col < 0) return null
            val r = minOf(row.toLong(), cells)
            val c = minOf(col.toLong(), cells)
            val at = r * cells * 2 + c * 2
            if (at < 0 || at + 2 > len) return null
            return attempt(null) {
                f.seek(at)
                val lo = f.read()
                val hi = f.read()
                if (lo < 0 || hi < 0) null else ((hi shl 8) or lo).toShort().toInt()
            }
        }
    }

    private inline fun <T> attempt(fallback: T, body: () -> T): T = try { body() } catch (_: Throwable) { fallback }
}
