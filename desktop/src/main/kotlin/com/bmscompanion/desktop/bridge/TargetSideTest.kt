package com.bmscompanion.desktop.bridge

import java.io.File

/**
 * `--targetsidetest out.txt <a BMS folder>` (read only): for every save of every theater, the flights a pilot sits in
 * (else each team's first flight whose route points at something), every place its waypoints point at that has a
 * model, with its owner, its waypoint's action and whether the Target pictures card's side rule keeps it
 * ([TargetPictures.flightTargetsOf]) — the auto set before the rule and after. FAIL where a kept place is of the
 * flight's own or an allied side, or is a take-off or landing waypoint's.
 */
internal object TargetSideTest {
    fun run(root: File): String {
        val out = StringBuilder()
        var fails = 0
        var before = 0
        var after = 0
        var flightsChecked = 0
        val set = Theaters.at(root)
        out.appendLine("Target pictures, the flight's targets by side (1.4.0), ${root.name}: ${set.all.size} theaters")
        for (t in set.all) {
            val names = runCatching { CampaignArchive.names(set, t) }.getOrNull() ?: continue
            val tables = runCatching { TargetModels.tables(set, t) }.getOrNull() ?: continue
            for (file in runCatching { set.saves(t) }.getOrDefault(emptyList())) {
                val save = runCatching { CampaignArchive.cached(file, names) }.getOrNull() ?: continue
                if (save.start) continue
                val flights = save.playerFlights.ifEmpty {
                    save.flights.filter { f -> f.waypoints.any { it.target != null && !it.target.isNone && it.action != 1 && it.action != 7 } }
                        .groupBy { it.owner }.values.map { it.first() }.take(3)
                }
                if (flights.isEmpty()) continue
                out.appendLine()
                out.appendLine("== ${t.name}: ${file.name}  (teams: ${save.teams?.joinToString { "${it.n} ${it.name.trim()}" } ?: "not read"})")
                fun team(n: Int?) = n?.let { k -> save.teams?.firstOrNull { it.n == k }?.name?.trim() ?: "team $k" } ?: "?"
                for (f in flights) {
                    val read = runCatching { TargetPictures.flightTargetsOf(save, f, tables) }
                    val list = read.getOrNull()
                    if (list == null) {
                        val e = read.exceptionOrNull()
                        fails++; out.appendLine("FAIL  ${names.callsign(f)}: ${e?.javaClass?.simpleName} ${e?.message}"); continue
                    }
                    flightsChecked++
                    before += list.size; after += list.count { it.kept }
                    out.appendLine("  ${names.callsign(f)} (${team(f.owner)}, mission ${f.mission}): ${list.size} places before, ${list.count { it.kept }} after")
                    for (x in list) {
                        val g = x.group
                        out.appendLine("     ${if (x.kept) "kept" else "out "}  ${g.note}  ${g.title}  [${team(x.owner)}, action ${x.action}, ${g.items.size} kinds]${x.why?.let { "  — $it" } ?: ""}")
                        if (x.kept) {
                            val owner = x.owner
                            if (owner != null && (owner == f.owner || SupportSide.ours(save, f.owner, owner))) { fails++; out.appendLine("FAIL  ${g.title} kept, of the flight's side (${team(owner)})") }
                            if (x.action == 1 || x.action == 7) { fails++; out.appendLine("FAIL  ${g.title} kept, a take-off or landing waypoint's") }
                        }
                    }
                }
            }
        }
        out.appendLine()
        out.appendLine("$flightsChecked flights: $before places before the side rule, $after after")
        out.appendLine(if (fails == 0) "ALL PASS" else "$fails FAIL")
        return out.toString()
    }
}
