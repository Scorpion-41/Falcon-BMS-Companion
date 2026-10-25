package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable

/**
 * One piece of the pilot's planning the PC keeps for every device (1.4.2, `GET`/`POST /api/planner/state`; docs/PROTOCOL.md
 * "Planning on the PC"): the Planner's open flight, the Upd Kneeboard plan, the Steps' ticks. A pilot plans on the PC
 * and flies with a tablet: the tablet takes what was planned instead of asking for it again ([PlannerSync]).
 *
 * [value] is the piece as its owner keeps it (a JSON or text of its own; the PC never reads it). [at] is the PC's clock
 * when the change was made (the PC's own, so devices with clocks of their own never disagree about which is newer).
 */
@Serializable
data class PlannerStateEntry(
    /** what it is: [PlannerSync.FLIGHT], [PlannerSync.KNEEBOARD], [PlannerSync.STEPS] (new ones may come) */
    val name: String = "",
    val value: String = "",
    /** the PC's clock (ms) when the change was made; 0 in a post (the PC sets it) */
    val at: Long = 0,
    /** which kind of device made it, in a word ("PC", "Android", "Browser"); for the words only */
    val by: String = "",
)

/**
 * A device's change, posted (`POST /api/planner/state`): [age] ms ago by the device's own steady clock (the PC sets the
 * entry's time to its clock less that), [seen] the PC time of the entry the device last had. [weak]: a change the
 * device made by itself (a default laid, a mission followed) — kept only when the PC has nothing newer than [seen].
 */
@Serializable
data class PlannerStatePost(
    val name: String = "",
    val value: String = "",
    val by: String = "",
    val age: Long = 0,
    val seen: Long = 0,
    val weak: Boolean = false,
)

/** The PC's answer: every entry it keeps (a GET), or the one posted and whether the post was [kept]. */
@Serializable
data class PlannerStateAll(
    val entries: List<PlannerStateEntry> = emptyList(),
    /** a POST: true when the device's change is now the PC's; false when the PC kept a newer one (in [entries]) */
    val kept: Boolean = false,
    /** the PC's clock now (ms) */
    val now: Long = 0,
    val error: String? = null,
)
