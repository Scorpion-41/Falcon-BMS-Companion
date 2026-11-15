package com.bmscompanion.app.ui.screens.mission

import com.bmscompanion.app.data.Aircraft
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Weapon
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.Store
import com.bmscompanion.app.data.wdp.PerformanceLoadout
import com.bmscompanion.app.data.wdp.PerformancePlan

/**
 * A loadout's drag index and load weight as the Planner's Performance page (and so the DataCard's Drag row, D38)
 * counts them: WDP's `WeightsDragsFuel` — the clean jet's 1, each store's drag and weight times its count, and once
 * for each loaded hardpoint its pylon and rack (`data/wdp/racks.json`, BMS 4.38.1's own), all in the mission theater's
 * figures ([PerformanceLoadout]). The briefing does not say which hardpoint a store hangs on, so the stores are hung
 * as the Performance page hangs them ([PerformanceLoadout.fromBriefing]). Only the F-16s, the jets the Planner has
 * pylon and rack data for: elsewhere a sum of the stores alone would not be BMS's figure, so nothing is shown.
 */
data class LoadoutFigures(val drag: Int, val loadLb: Int)

/** The tables the figures are read from, loaded once per page. */
class LoadoutTables(val aircraft: List<Aircraft>, val weapons: Map<String, Weapon>, val racks: PerformanceLoadout.Racks)

suspend fun loadoutTables(): LoadoutTables? = runCatching {
    LoadoutTables(Repo.aircraft(), Repo.weaponMap(), PerformanceLoadout.loadRacks())
}.getOrNull()

/** The package flight's aircraft type for an ordnance block ("Ouranos5" → "F-16C B52+ HAF"), as the fuel plan finds it. */
fun Briefing.ordnanceType(flight: String): String? {
    fun n(s: String?) = s?.replace(" ", "")?.lowercase()?.takeIf { it.isNotEmpty() }
    val f = n(flight) ?: return null
    return `package`.firstOrNull { n(it.callsign) == f }?.aircraft?.trim()?.replace(Regex("^\\d+\\s+"), "")?.trim()?.takeIf { it.isNotEmpty() }
}

/** [stores] of one jet of [type] in [theaterId]: its drag index and load, or null where the Planner has no rack data for it. */
fun loadoutFigures(t: LoadoutTables, type: String?, theaterId: String?, stores: List<Store>): LoadoutFigures? {
    val a = aircraftOf(t.aircraft, type ?: return null) ?: return null
    val v = a.variants.firstOrNull { theaterId != null && theaterId in it.theaters } ?: a.variants.firstOrNull() ?: return null
    if (v.stations.isEmpty()) return null
    val where = (listOfNotNull(theaterId) + v.theaters).distinct()
    val mounts = t.racks.forVariant(a.key, where)
    if (mounts.isEmpty()) return null
    val l = PerformanceLoadout(v.stations, t.weapons, mounts, t.racks.storeFigures(where))
    // the briefing does not say whether the jet carries conformal tanks: its name does ("F-16C-52+ CFT")
    if (type.contains("CFT", ignoreCase = true) || a.name.contains("CFT", ignoreCase = true)) l.airframeDrag = t.racks.cft(a.key, where)?.drag ?: 0
    l.fromBriefing(listOf(stores.map { it.qty to it.name }))
    val tot = l.totals(0)
    return LoadoutFigures(tot.totalDrag, tot.loadWeight)
}

/** The app's aircraft for a briefing's type name: the same name, BMS's data file name, else the name it begins with. */
private fun aircraftOf(list: List<Aircraft>, type: String): Aircraft? {
    val n = PerformancePlan.norm(type)
    if (n.isEmpty()) return null
    return list.firstOrNull { PerformancePlan.norm(it.name) == n }
        ?: list.firstOrNull { a -> a.variants.any { v -> v.spec.datFile?.let { PerformancePlan.norm(it) } == n } }
        ?: list.firstOrNull { PerformancePlan.norm(it.name).startsWith(n) || (n.startsWith(PerformancePlan.norm(it.name)) && it.name.length >= 5) }
}
