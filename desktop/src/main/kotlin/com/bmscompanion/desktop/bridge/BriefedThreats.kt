package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CampSite
import com.bmscompanion.app.data.mission.MissionPicture
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The printed briefing's surface-to-air threats, placed on the map: each row of BMS's Threat Analysis ("SA-19
 * (2K22)missile launchers 2 nm west of Buk-myeon", [MissionPicture.briefedThreats]) found among the save's units —
 * an air-defence battalion of that system, or a battalion carrying it as its short-range SAM. These are the sites a
 * pilot was told of, so they are the ones every map rings by default.
 *
 * A row is the unit of that system whose position BMS words exactly so ([CampaignBriefing.Places]: the nearest
 * city or town, whole cells, miles cut to whole) — **"exact"**; else the battalion of that system nearest the point the
 * words describe, within [NEAR_NM] (it may have moved since the print) — **"near"**; else a hostile unit there that BMS
 * words exactly so, whatever the unit table says it fields (Korea's motor rifle battalions are briefed as "SA-19
 * (2K22)missile launchers") — **"unit"**; else the described point itself,
 * the town moved along the compass word by the miles plus half a mile — **"words"** (a save of another campaign, or one
 * that no longer has it). A row whose town the save does not have is left out rather than guessed at.
 *
 * Read only; nothing here throws.
 */
object BriefedThreats {
    /** How far from the described point a battalion of the system may be and still be the row's. */
    const val NEAR_NM = 5.0

    private const val NM = 6076.12
    /** a campaign cell in feet (CampaignArchive's FEET_PER_CELL): the briefing measures between whole cells */
    private const val FT_PER_CELL = 3279.98

    /** A row and where it was put: [how] "exact", "near", "unit" (a unit worded so, whatever it fields) or "words". */
    class Placed(val row: MissionPicture.BriefedThreat, val site: CampSite, val how: String)

    private fun whole(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    /**
     * The rows of [rows] placed among [units] (the save's map intel, [MapIntel]: a row's system is a unit's own or the
     * short-range SAM it carries, `shorad` — BMS lists a mechanised battalion's SA-19 as "SA-19 (2K22)missile
     * launchers"); [places] words a cell and finds a town ([CampaignBriefing.Places] of the save's start file).
     */
    fun place(
        rows: List<MissionPicture.BriefedThreat>, units: List<com.bmscompanion.app.data.mission.CampMapUnit>, places: CampaignBriefing.Places,
    ): List<Placed> = try {
        val ground = units.filter { !it.dead && it.side != "friendly" && it.domain != "sea" }
        fun systems(u: com.bmscompanion.app.data.mission.CampMapUnit) = listOfNotNull(u.system.takeIf { it.isNotBlank() }, u.shorad?.takeIf { it.isNotBlank() })
        fun cell(ft: Double) = kotlin.math.floor(ft / FT_PER_CELL).toInt()
        val used = HashSet<String>()
        val out = ArrayList<Placed>()
        for (row in rows) {
            // the same vehicle name, else the same system by its first word ("SA-19 (2K22)" and "SA-19 Grison")
            val named = ground.filter { u -> u.id !in used && systems(u).any { whole(it) == whole(row.system) } }
                .ifEmpty { ground.filter { u -> u.id !in used && systems(u).any { MissionPicture.systemKey(it) == MissionPicture.systemKey(row.system) } } }
            fun site(u: com.bmscompanion.app.data.mission.CampMapUnit) = CampSite(system = row.system, name = u.name, x = u.x, y = u.y, spotted = true)
            val exact = named.firstOrNull { u -> places.words(cell(u.y), cell(u.x))?.equals(row.where, ignoreCase = true) == true }
            if (exact != null) {
                used += exact.id
                out += Placed(row, site(exact), "exact")
                continue
            }
            val town = row.place?.let { places.town(it) } ?: continue
            val brg = row.compass?.let { MissionPicture.COMPASS[it] }
            val d = (row.nm ?: 0) + 0.5
            val north = if (brg == null) town.north else town.north + cos(Math.toRadians(brg)) * d * NM
            val east = if (brg == null) town.east else town.east + sin(Math.toRadians(brg)) * d * NM
            val near = named.map { it to hypot(it.x - north, it.y - east) }.filter { it.second <= NEAR_NM * NM }.minByOrNull { it.second }?.first
            // BMS may name a battalion by a SAM among its vehicles that the unit table's first vehicle and short-range
            // SAM do not show (Korea's motor rifle battalions with an SA-19): then the unit BMS words exactly so,
            // nearest the described point, whatever it fields
            val worded = if (near == null) ground.filter { u -> u.id !in used && hypot(u.x - north, u.y - east) <= 3 * NM &&
                places.words(cell(u.y), cell(u.x))?.equals(row.where, ignoreCase = true) == true }
                .minByOrNull { hypot(it.x - north, it.y - east) } else null
            if (near != null) {
                used += near.id
                out += Placed(row, site(near), "near")
            } else if (worded != null) {
                used += worded.id
                out += Placed(row, site(worded), "unit")
            } else {
                out += Placed(row, CampSite(system = row.system, name = null, x = north, y = east, spotted = true), "words")
            }
        }
        out
    } catch (e: Throwable) {
        BridgeLog.info("The briefing's threats could not be placed: ${e.message}")
        emptyList()
    }
}
