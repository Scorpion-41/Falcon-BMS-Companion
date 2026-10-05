package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable

/**
 * The ground under points of a theater, from Falcon BMS's own height map (`GET /api/campaign/ground?theater=&at=`;
 * docs/PROTOCOL.md, "Planner integration"): the ground the Planner's attack pages plan their heights above, as WDP's
 * Pop-up page reads it (`TerrainHeights` on the PC).
 *
 * [heights] is feet above sea level, one per point asked for and in the same order, null where the map says nothing
 * (outside the theater, no height map). [theater] is the theater-definition name the PC answered for, and [error] a
 * sentence when no point could be read at all.
 */
@Serializable
data class CampGround(
    val theater: String = "",
    val heights: List<Int?> = emptyList(),
    val error: String? = null,
)
