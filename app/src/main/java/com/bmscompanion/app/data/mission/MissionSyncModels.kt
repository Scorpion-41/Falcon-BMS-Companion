package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable

/**
 * One value of the Mission section the PC keeps for every device (1.4.2, `GET`/`POST /api/sync`; docs/PROTOCOL.md
 * "The Mission section on every device"): a preference of the Mission section (its own key, `map_style`, `m_route`,
 * `dash_wide_p1` …) or a piece of a Planner page's session (a key starting `@`: `@card/<flight>/<box>` …). The last
 * change wins, by the PC's clock ([MissionSync]).
 *
 * [value] is the value as its device keeps it (text; an Android int as its digits, a set as a JSON list); null = the
 * preference was removed. [type]: "i" an int, "s" a string, "S" a set of strings, "" not known (a value seeded from a
 * PC or browser, which keep every preference as text). [at] is the PC's clock (ms) when the change was made.
 */
@Serializable
data class SyncItem(
    val key: String = "",
    val value: String? = null,
    val type: String = "",
    val at: Long = 0,
    /** which kind of device made it ("PC", "Android", "Browser"), for the words only */
    val by: String = "",
)

/**
 * A device's change, posted: [age] ms ago by the device's own steady clock (the PC dates it with its clock less that),
 * [seen] the PC time of the value the device last had for the key. [weak]: made by the device itself (its preferences
 * given to an empty store), kept only when the PC has nothing newer than [seen].
 */
@Serializable
data class SyncChange(
    val key: String = "",
    val value: String? = null,
    val type: String = "",
    val age: Long = 0,
    val seen: Long = 0,
    val weak: Boolean = false,
)

/** `POST /api/sync`: one device's changes. */
@Serializable
data class SyncPost(val by: String = "", val changes: List<SyncChange> = emptyList())

/**
 * The PC's answer. A GET: the items changed after `since` (all with `since=0`), optionally only those whose key starts
 * with `prefix`. A POST: for every change posted, the item the PC now holds for its key, and in [kept] the keys whose
 * change is now the PC's (a key missing from [kept] lost to a newer change, which [items] carries).
 */
@Serializable
data class SyncAnswer(
    val items: List<SyncItem> = emptyList(),
    val kept: List<String> = emptyList(),
    /** the PC's clock now (ms) */
    val now: Long = 0,
    /** the newest time in `/api/planner/state` (the Planner's flight, the Upd Kneeboard plan, the Steps): a device that has seen an older one syncs those */
    val plannerAt: Long = 0,
    val error: String? = null,
)
