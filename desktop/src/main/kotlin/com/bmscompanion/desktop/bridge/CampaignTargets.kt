package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CampFeature
import com.bmscompanion.app.data.mission.CampFeatures
import com.bmscompanion.app.data.mission.CampObjective
import com.bmscompanion.app.data.mission.CampObjectives
import java.io.File

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
 * - **Heights** are the PC's ground at the position ([TerrainHeights], as `/api/campaign/ground` answers): BMS's
 *   height map where BMS lays it, bilinear, with the terrain levelling under the airbases — not the cell WDP's
 *   `ReadNewTerrainElvLoc` picks, which takes the map for the grid's square (D99, D101).
 */
object CampaignTargets {
    /** Strings.txt: an objective type's name is string 500 + the type (501 "Airbase" … 531 "SAM Site"). */
    private const val TYPE_STRINGS = 500
    private const val FCD_FILE = "Falcon4_FCD.xml"

    fun objectives(set: Theaters.TheaterSet, t: Theaters.Theater, file: File): CampObjectives {
        val names = CampaignArchive.names(set, t)
        val save = CampaignArchive.cached(file, names)
        val walk = CampaignArchive.startFile(save)?.let { CampaignArchive.objectives(it) }
        val objs = walk?.objectives.orEmpty().toList()
        val ground = heights(set, t, objs.map { it.north to it.east })
        val types = sortedMapOf<Int, String>()
        val out = objs.mapIndexed { i, o ->
            val tn = names.ct(o.type)?.type ?: 0
            val type = names.strings[TYPE_STRINGS + tn]?.trim().orEmpty()
            if (tn > 0 && type.isNotEmpty()) types[tn] = type
            val team = save.header?.team(o.owner)?.name?.trim().orEmpty()
            CampObjective(
                id = o.id.toString(), campId = o.campId, name = o.name.trim().ifEmpty { "$type ${o.campId}".trim() }, type = type,
                x = o.north, y = o.east, elevFt = ground.getOrNull(i), control = team.ifEmpty { "Team ${o.owner}" } + "(${o.owner})",
            )
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
        val out = fed.keys.sorted().map { n ->
            val f = fed.getValue(n)
            val ct = f["FeatureCtIdx"]?.trim()?.toIntOrNull()?.let { names.ct(it + 100) }
            val name = ct?.entityIdx?.let { fcd[it]?.get("Name") }?.trim().orEmpty()
            val north = o.north + (f["OffsetY"]?.trim()?.toDoubleOrNull() ?: 0.0)
            val east = o.east + (f["OffsetX"]?.trim()?.toDoubleOrNull() ?: 0.0)
            CampFeature(n, name, north, east, null, f["Value"]?.trim()?.toDoubleOrNull()?.toInt() ?: 0)
        }
        val ground = heights(set, t, out.map { it.x to it.y })
        return CampFeatures(id.toString(), out.mapIndexed { i, f -> f.copy(elevFt = ground.getOrNull(i)) })
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

    /** The ground under [points] as `/api/campaign/ground` answers it ([TerrainHeights]); nulls where it says nothing. */
    private fun heights(set: Theaters.TheaterSet, t: Theaters.Theater, points: List<Pair<Double, Double>>): List<Int?> =
        if (points.isEmpty()) emptyList() else try { TerrainHeights.at(set, t, points).heights } catch (_: Throwable) { emptyList() }
}
