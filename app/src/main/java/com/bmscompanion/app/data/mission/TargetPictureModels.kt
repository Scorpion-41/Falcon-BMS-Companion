package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable

// Pictures of the mission's targets, drawn by the PC from Falcon BMS's own 3-D models (docs/PROTOCOL.md, "Target
// pictures"). The PC answers which targets the mission names and draws each picture on request; nothing is shipped
// with the app and no picture is kept anywhere but the PC's small cache.

/**
 * One thing that can be pictured: [key] names it for `/api/targets/picture` (its number in the theater's class table,
 * "ct16"); [name] as BMS's tables name it ("SA-6 (2K12)", "Straight Flush", "Hangar"); [role] its part in its group
 * ("launcher", "radar", "air defence", "support", "unit", "building"); [count] how many the unit fields; [category] the
 * list it is in ([TargetCatalog]).
 */
@Serializable
data class TargetItem(
    val key: String = "",
    val name: String = "",
    val role: String? = null,
    val count: Int? = null,
    val category: String? = null,
)

/**
 * A group of pictures: a threat system's battalion ([kind] "threat": the launcher, its radars and the vehicles that go
 * with it, from BMS's unit table), the flight's target ("target": a unit's vehicles or an objective's buildings), or a
 * ship ("ship"). [note] says where or why ("2 nm west of Buk-myeon", "STPT 6").
 */
@Serializable
data class TargetGroup(
    val title: String = "",
    val kind: String = "",
    val note: String? = null,
    val items: List<TargetItem> = emptyList(),
)

/**
 * The mission's targets (`GET /api/targets/mission`): [theater] whose tables they come from (passed back as `theater=`
 * when asking for a picture), [mission] which mission they are of, [groups] targets first, then threats, then ships.
 * [error] says why there is nothing (no briefing, no model folder…).
 */
@Serializable
data class TargetSet(
    val theater: String = "",
    val mission: String = "",
    val groups: List<TargetGroup> = emptyList(),
    val error: String? = null,
)

/** Every ground target of a theater, by category (`GET /api/targets/catalog`): the Add list. */
@Serializable
data class TargetCatalog(
    val theater: String = "",
    val categories: List<TargetCategory> = emptyList(),
    val error: String? = null,
)

@Serializable
data class TargetCategory(val name: String = "", val items: List<TargetItem> = emptyList())

object TargetPictureApi {
    const val VIEW_FRONT_RIGHT = "fr"
    const val VIEW_REAR_LEFT = "rl"
    const val LOOK_PLAIN = "plain"
    const val LOOK_INTEL = "intel"

    /** The two views every target is drawn from, with the words the pages put under them. */
    val VIEWS = listOf(VIEW_FRONT_RIGHT to "Front right", VIEW_REAR_LEFT to "Rear left")
}
