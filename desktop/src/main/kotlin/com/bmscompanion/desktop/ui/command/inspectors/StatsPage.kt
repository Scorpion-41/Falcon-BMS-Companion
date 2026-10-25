package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.command.CampaignOps
import com.bmscompanion.desktop.ui.command.AirRole
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdGrid
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdPage
import com.bmscompanion.desktop.ui.command.CmdPageView
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.GridCol
import com.bmscompanion.desktop.ui.command.MiniChip
import com.bmscompanion.desktop.ui.command.ObjGroup
import com.bmscompanion.desktop.ui.command.Strip
import com.bmscompanion.desktop.ui.command.VRule
import com.bmscompanion.desktop.ui.command.ViewTeam
import com.bmscompanion.desktop.ui.command.ViewUnit
import com.bmscompanion.desktop.ui.command.teamInk

/**
 * The Stats page (read only): per team what it holds — objectives and airbases, squadrons and their aircraft by role,
 * flights, battalions, brigades and their vehicles, air-defence battalions, task forces and their ships — and below,
 * the same by type (the aircraft, the battalions' main systems, the ships), one column per team. Aircraft and vehicles
 * are the rosters' totals (what BMS counts as a unit's strength).
 */
object StatsPage : CmdPageView {
    override val page = CmdPage.STATS

    private class TeamRow(val t: ViewTeam, val cells: Map<String, Int>)
    private class TypeRow(val kind: String, val name: String, val units: Map<Int, Int>, val strength: Map<Int, Int>)

    private val AIR_COLS = listOf(
        AirRole.FIGHTER to "Fighters", AirRole.ATTACK to "Attack", AirRole.BOMBER to "Bombers", AirRole.SUPPORT to "AWACS/ECM",
        AirRole.TANKER to "Tankers", AirRole.TRANSPORT to "Transport", AirRole.RECON to "Recon", AirRole.HELI to "Helis",
    )

    private fun strength(u: ViewUnit) = CampaignOps.rosterTotal(u.roster)

    @Composable
    override fun Content(ctx: CmdContext) {
        @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
        val world = ctx.world
        var aircraftOnly by remember { mutableStateOf(false) }
        val teams = world.teams.filter { it.inUse }
        val rows = remember(world) {
            teams.map { t ->
                val us = world.units.filter { it.owner == t.n }
                val sq = us.filter { it.kind == CmdKind.SQUADRON }
                val c = LinkedHashMap<String, Int>()
                c["Objectives"] = world.objectives.count { it.owner == t.n }
                c["Airbases"] = world.objectives.count { it.owner == t.n && it.group == ObjGroup.AIRPORTS }
                c["Squadrons"] = sq.size
                c["Aircraft"] = sq.sumOf { strength(it) }
                AIR_COLS.forEach { (r, h) -> c[h] = sq.filter { it.role == r }.sumOf { strength(it) } }
                c["Flights"] = us.count { it.kind == CmdKind.FLIGHT }
                c["Packages"] = us.count { it.kind == CmdKind.PACKAGE }
                c["Brigades"] = us.count { it.kind == CmdKind.BRIGADE }
                c["Battalions"] = us.count { it.kind == CmdKind.BATTALION }
                c["Vehicles"] = us.filter { it.kind == CmdKind.BATTALION }.sumOf { strength(it) }
                c["AD battalions"] = us.count { it.kind == CmdKind.BATTALION && it.sub == 1 }
                c["Task forces"] = us.count { it.kind == CmdKind.TASKFORCE }
                c["Ships"] = us.filter { it.kind == CmdKind.TASKFORCE }.sumOf { strength(it) }
                TeamRow(t, c)
            }
        }
        val types = remember(world, aircraftOnly) {
            world.units.filter { it.kind == CmdKind.SQUADRON || (!aircraftOnly && (it.kind == CmdKind.BATTALION || it.kind == CmdKind.TASKFORCE)) }
                .groupBy { (if (it.kind == CmdKind.SQUADRON) "Aircraft" else if (it.kind == CmdKind.TASKFORCE) "Ship" else "Ground") to (it.aircraft ?: it.cls.ifEmpty { "?" }) }
                .map { (k, us) -> TypeRow(k.first, k.second, us.groupingBy { it.owner }.eachCount(), us.groupBy { it.owner }.mapValues { e -> e.value.sumOf { strength(it) } }) }
                .sortedWith(compareBy({ it.kind }, { it.name.lowercase() }))
        }
        Column(Modifier.fillMaxSize()) {
            Strip {
                Text("STATS · ${ctx.doc.file.name}", color = Hud.TextDim, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                VRule()
                Text("${world.units.size} units · ${world.objectives.size} objectives · aircraft and vehicles are the rosters' totals", color = Hud.TextFaint, fontSize = 11.sp)
            }
            Box(Modifier.fillMaxWidth().height((70 + 22 * rows.size.coerceAtLeast(1)).dp)) {
                val keys = rows.firstOrNull()?.cells?.keys?.toList().orEmpty()
                CmdGrid(
                    rows = rows,
                    cols = listOf(GridCol<TeamRow>("Team", 140.dp, ink = { teamInk(it.t.colour) }, lead = { r -> { com.bmscompanion.desktop.ui.command.TeamFlagLead(r.t.name, r.t.colour) } }) { it.t.name }) +
                        keys.map { k -> GridCol<TeamRow>(k, if (k.length > 9) 96.dp else 78.dp, numeric = true, sort = { it.cells[k] ?: 0 }) { "${it.cells[k] ?: 0}" } },
                    key = { it.t.n },
                    selected = { false },
                    onClick = { _, _ -> },
                )
            }
            Rule()
            Strip {
                Text("BY TYPE", color = Hud.TextDim, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                VRule()
                MiniChip("Aircraft only", aircraftOnly) { aircraftOnly = !aircraftOnly }
                Text("each team: units (strength)", color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
            }
            CmdGrid(
                rows = types,
                cols = listOf(
                    GridCol<TypeRow>("Type", 220.dp) { it.name },
                    GridCol("Kind", 80.dp) { it.kind },
                    GridCol("All", 80.dp, numeric = true, sort = { it.strength.values.sum() }) { r -> "${r.units.values.sum()} (${r.strength.values.sum()})" },
                ) + teams.map { t ->
                    GridCol<TypeRow>(t.name, 110.dp, numeric = true, ink = { teamInk(t.colour) }, sort = { it.strength[t.n] ?: 0 }) { r -> r.units[t.n]?.let { "$it (${r.strength[t.n] ?: 0})" }.orEmpty() }
                },
                key = { it.kind + "|" + it.name },
                selected = { false },
                onClick = { r, _ -> ctx.say("${r.name}: ${r.units.values.sum()} units, strength ${r.strength.values.sum()}") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
