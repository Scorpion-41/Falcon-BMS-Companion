package com.bmscompanion.desktop.ui.command.inspectors

import com.bmscompanion.desktop.ui.command.teamLead
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.command.CmdTeam
import com.bmscompanion.desktop.bridge.command.CommandSave
import com.bmscompanion.desktop.bridge.command.TeamOps
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CMD_BAR
import com.bmscompanion.desktop.ui.command.CmdHBar
import com.bmscompanion.desktop.ui.command.CmdVBar
import com.bmscompanion.desktop.ui.command.shiftWheel
import androidx.compose.foundation.layout.fillMaxHeight
import com.bmscompanion.desktop.ui.command.CmdPage
import com.bmscompanion.desktop.ui.command.CmdPageView
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.DeskButton
import com.bmscompanion.desktop.ui.command.FactRow
import com.bmscompanion.desktop.ui.command.MiniChip
import com.bmscompanion.desktop.ui.command.Strip
import com.bmscompanion.desktop.ui.command.teamInk

/**
 * The Teams page: the stances of every team toward every other (a matrix, each cell a menu), then the chosen team —
 * name, colour, flag and motto (kept in the team part and the header alike), controller, flags, experience and
 * figures, its priorities (objective types, unit types, missions), its ground and air actions, and its air tasking
 * manager's mission requests and airbase schedules (view and delete) — and adding or deleting a team.
 */
object TeamsPage : CmdPageView {
    override val page = CmdPage.TEAMS

    @Composable
    override fun Content(ctx: CmdContext) {
        @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
        val core = ctx.core
        var sel by remember { mutableIntStateOf(1) }
        Column(Modifier.fillMaxSize()) {
            EdDialogHost()
            val tea = core.tea
            Strip {
                Text("TEAMS", color = Hud.TextDim, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                core.teams.forEachIndexed { n, e ->
                    MiniChip("$n ${core.teamName(n)}", sel == n, teamInk(e.team.teamColor), lead = core.teamLead(n)) { sel = n }
                }
                Spacer(Modifier.weight(1f))
                DeskButton("Add a team", enabled = TeamOps.addRefusal(core) == null, tip = TeamOps.addRefusal(core) ?: "A new team after the last, neutral toward everyone") {
                    EdDialogs.ask = EdAsk("Add a team?", "Team ${core.teams.size} is added after the last, modelled on ${core.teamName(core.teams.size - 1)}, neutral toward every team.", "Add it", false) {
                        core.edits.run(TeamOps.add(core, "Team ${core.teams.size}", (core.teams.size % 8)))
                    }
                }
                DeskButton("Delete ${core.teamName(sel)}", enabled = TeamOps.deleteRefusal(core, sel) == null, danger = true, tip = TeamOps.deleteRefusal(core, sel) ?: "Remove this team") {
                    EdDialogs.ask = EdAsk("Delete ${core.teamName(sel)}?", "The team and its tasking managers are removed; the other teams' stances toward it are cleared.", "Delete", true) {
                        val why = core.edits.run(TeamOps.delete(core, sel)); if (why == null) sel = (sel - 1).coerceAtLeast(0); why
                    }
                }
            }
            Rule(0.5f)
            if (tea == null || core.teams.isEmpty()) { EdNote("This file has no team part.", Hud.Amber); return@Column }
            if (!tea.editable) EdNote("The team part could not be read exactly: it is shown, not edited.", Hud.Amber)
            val n = sel.coerceIn(0, core.teams.size - 1)
            // (a definite width inside the sideways scroll, or the headings' weighted text has none)
            val vs = rememberScrollState()
            val hs = rememberScrollState()
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth().shiftWheel(hs)) {
            Column(Modifier.fillMaxSize().padding(end = CMD_BAR, bottom = CMD_BAR).verticalScroll(vs).horizontalScroll(hs).padding(8.dp).width(1080.dp)) {
                Matrix(ctx)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    EdColumn(330.dp) { Identity(ctx, n) }
                    EdColumn(300.dp) { Figures(ctx, n) }
                    EdColumn(300.dp) { Actions(ctx, n) }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    EdColumn(300.dp) { Priorities(ctx, n, "objective") }
                    EdColumn(260.dp) { Priorities(ctx, n, "unit") }
                    EdColumn(300.dp) { Priorities(ctx, n, "mission") }
                }
                Spacer(Modifier.height(10.dp))
                Tasking(ctx, n)
            }
                CmdVBar(androidx.compose.foundation.rememberScrollbarAdapter(vs), Modifier.align(Alignment.TopEnd).padding(bottom = CMD_BAR).fillMaxHeight())
                CmdHBar(hs, Modifier.align(Alignment.BottomStart).padding(end = CMD_BAR).fillMaxWidth())
            }
        }
    }

    @Composable
    private fun Matrix(ctx: CmdContext) {
        val core = ctx.core
        val teams = core.teams
        val used = (ctx.world.units.map { it.owner } + ctx.world.objectives.map { it.owner }).toSet()
        EdHeading("Stances (row toward column)")
        Row(Modifier.padding(start = 6.dp)) {
            Text("", modifier = Modifier.width(150.dp))
            teams.forEachIndexed { j, e ->
                Row(Modifier.width(90.dp).padding(start = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    core.teamLead(j)()
                    Text(core.teamName(j).take(10), color = teamInk(e.team.teamColor), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
            Text("CONTROLLED BY", color = Hud.TextFaint, fontSize = 9.5.sp, modifier = Modifier.width(130.dp).padding(start = 5.dp))
        }
        teams.forEachIndexed { i, e ->
            Row(Modifier.padding(start = 6.dp).height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(150.dp), verticalAlignment = Alignment.CenterVertically) {
                    core.teamLead(i)()
                    Text("$i ${core.teamName(i)}" + if (i !in used) " (none)" else "", color = if (i in used) Hud.Text else Hud.TextFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                teams.indices.forEach { j ->
                    val s = e.team.stance.getOrElse(j) { 0 }
                    if (i == j) Text("—", color = Hud.TextFaint, fontSize = 12.sp, modifier = Modifier.width(90.dp).padding(start = 5.dp))
                    else EdMenu(s, TeamOps.STANCES.mapIndexed { k, w -> k to w }, core.partEditable("tea"), ink = { stanceInk(it) }, modifier = Modifier.width(90.dp).padding(end = 6.dp)) { nv ->
                        ctx.edit(TeamOps.stance(core, i, j, nv))
                    }
                }
                EdMenu(e.team.cteam, teams.indices.map { it to "$it ${core.teamName(it)}" }, core.partEditable("tea"), modifier = Modifier.width(130.dp), lead = { core.teamLead(it) }) { c -> ctx.edit(TeamOps.controller(core, i, c)) }
            }
            Rule(0.1f)
        }
        EdNote("A team is friendly to the team that controls it. BMS reads each row as that team's view, so a war is usually set both ways (the Checks page names a one-sided one).")
    }

    private fun stanceInk(s: Int) = when (s) { 1, 2 -> Hud.Green; 4, 5 -> Hud.Red; 3 -> Hud.TextDim; else -> Hud.TextFaint }

    @Composable
    private fun Identity(ctx: CmdContext, n: Int) {
        val core = ctx.core
        val t = core.teams[n].team
        val h = core.cmp?.teams?.getOrNull(n)
        val can = core.partEditable("tea")
        EdHeading("Team $n")
        EdText("Name", t.name.text, can) { ctx.edit(TeamOps.rename(core, n, it)) }
        if (h != null && h.name.text.trim() != t.name.text.trim()) FactRow("Header's name", h.name.text, ink = Hud.Amber)
        EdChips("Colour", t.teamColor, (0 until 8).map { it to COLOURS[it] }, can, ink = { teamInk(it) }) { ctx.edit(TeamOps.colour(core, n, it)) }
        EdInt("Flag", t.teamFlag, 0..255, can, tip = "BMS's flag picture number") { ctx.edit(TeamOps.flag(core, n, it)) }
        EdText("Motto", t.motto.text, can) { ctx.edit(TeamOps.motto(core, n, it)) }
        EdInt("Equipment of", t.equipment, 0..255, can, tip = "The country whose equipment the team fields (BMS's equipment list number)") { v -> ctx.edit(TeamOps.field(core, n, "Equipment", { it.equipment }, { tt, x -> tt.equipment = x }, v)) }
        EdHeading("Flags")
        TeamOps.TEAM_FLAGS.forEach { (bit, words) ->
            EdCheck(words, t.flags and bit != 0, can) { on -> ctx.edit(TeamOps.field(core, n, words, { it.flags }, { tt, x -> tt.flags = x }, if (on) t.flags or bit else t.flags and bit.inv())) }
        }
        FactRow("Id", "${t.id} · class ${t.entityType}", mono = true)
        FactRow("Pilot names", "colonel ${t.firstColonel} · commander ${t.firstCommander} · wingmen ${t.firstWingman}-${t.lastWingman}", mono = true)
    }

    @Composable
    private fun Figures(ctx: CmdContext, n: Int) {
        val core = ctx.core
        val t = core.teams[n].team
        val can = core.partEditable("tea")
        fun num(label: String, get: (CmdTeam) -> Int, set: (CmdTeam, Int) -> Unit, range: IntRange = 0..255): @Composable () -> Unit = {
            EdInt(label, get(t), range, can) { v -> ctx.edit(TeamOps.field(core, n, label, get, set, v)) }
        }
        EdHeading("Experience and figures")
        num("Air experience", { it.airExperience }, { a, v -> a.airExperience = v })()
        num("Air defence exp.", { it.airDefenseExperience }, { a, v -> a.airDefenseExperience = v })()
        num("Ground experience", { it.groundExperience }, { a, v -> a.groundExperience = v })()
        num("Naval experience", { it.navalExperience }, { a, v -> a.navalExperience = v })()
        num("Initiative", { it.initiative }, { a, v -> a.initiative = v }, 0..100)()
        num("Supply available", { it.supplyAvail }, { a, v -> a.supplyAvail = v }, 0..65535)()
        num("Fuel available", { it.fuelAvail }, { a, v -> a.fuelAvail = v }, 0..65535)()
        num("Replacements", { it.replacementsAvail }, { a, v -> a.replacementsAvail = v }, 0..65535)()
        num("Reinforcement", { it.reinforcement }, { a, v -> a.reinforcement = v }, -32768..32767)()
        EdHeading("Most vehicles")
        listOf("Air", "Ground", "Air defence", "Naval").forEachIndexed { i, w ->
            EdInt(w, t.maxVehicle.getOrElse(i) { 0 }, 0..255, can) { v -> ctx.edit(TeamOps.field(core, n, "Most $w vehicles", { it.maxVehicle }, { a, x -> a.maxVehicle = x }, t.maxVehicle.copyOf().also { it[i] = v })) }
        }
        EdHeading("Strength (start → now)")
        val s0 = t.startStats; val s1 = t.currentStats
        FactRow("Aircraft", "${s0.aircraft} → ${s1.aircraft}", mono = true)
        FactRow("Ground vehicles", "${s0.groundVehs} → ${s1.groundVehs}", mono = true)
        FactRow("Air defence", "${s0.airDefenseVehs} → ${s1.airDefenseVehs}", mono = true)
        FactRow("Ships", "${s0.ships} → ${s1.ships}", mono = true)
        FactRow("Airbases", "${s0.airbases} → ${s1.airbases}", mono = true)
        FactRow("Supply · fuel", "${s1.supply} (${s1.supplyLevel} %) · ${s1.fuel} (${s1.fuelLevel} %)", mono = true)
    }

    @Composable
    private fun Actions(ctx: CmdContext, n: Int) {
        val core = ctx.core
        val t = core.teams[n].team
        val can = core.partEditable("tea")
        fun objName(id: CampaignArchive.VuId) = core.objective(id)?.let { com.bmscompanion.desktop.bridge.command.ObjectiveOps.displayName(core, it) } ?: if (id.isNone) "—" else id.toString()
        fun objOf(s: String) = if (s.isBlank()) CampaignArchive.VuId.NONE else core.objectives.firstOrNull { it.campId.toString() == s.trim() }?.id
        EdHeading("Ground action")
        val g = t.groundAction
        EdChoice("Kind", g.type, TeamOps.GROUND_ACTIONS.mapIndexed { i, w -> i to w }, can) { v -> ctx.edit(TeamOps.field(core, n, "Ground action", { it.groundAction.type }, { a, x -> a.groundAction.type = x }, v)) }
        EdText("Objective (CampId)", core.objective(g.objective)?.campId?.toString().orEmpty(), can, tip = objName(g.objective)) { s ->
            objOf(s)?.let { id -> ctx.edit(TeamOps.field(core, n, "Ground action's objective", { it.groundAction.objective }, { a, x -> a.groundAction.objective = x }, id)) } ?: run { CommandSession.status = "No objective has CampId $s." }
        }
        FactRow("Toward", objName(g.objective))
        ClockRow(ctx, n, "From", { it.groundAction.time }) { a, x -> a.groundAction.time = x }
        ClockRow(ctx, n, "Until", { it.groundAction.timeout }) { a, x -> a.groundAction.timeout = x }
        EdInt("Tempo", g.tempo, 0..255, can) { v -> ctx.edit(TeamOps.field(core, n, "Ground tempo", { it.groundAction.tempo }, { a, x -> a.groundAction.tempo = x }, v)) }
        for ((title, air, set) in listOf(
            Triple("Offensive air action", t.offensiveAir, { a: CmdTeam -> a.offensiveAir }),
            Triple("Defensive air action", t.defensiveAir, { a: CmdTeam -> a.defensiveAir }),
        )) {
            EdHeading(title)
            EdChoice("Kind", air.type, TeamOps.AIR_ACTIONS.mapIndexed { i, w -> i to w }, can) { v -> ctx.edit(TeamOps.field(core, n, title, { set(it).type }, { a, x -> set(a).type = x }, v)) }
            EdText("Objective (CampId)", core.objective(air.objective)?.campId?.toString().orEmpty(), can, tip = objName(air.objective)) { s ->
                objOf(s)?.let { id -> ctx.edit(TeamOps.field(core, n, "$title's objective", { set(it).objective }, { a, x -> set(a).objective = x }, id)) } ?: run { CommandSession.status = "No objective has CampId $s." }
            }
            FactRow("Toward", objName(air.objective))
            ClockRow(ctx, n, "From", { set(it).start }) { a, x -> set(a).start = x }
            ClockRow(ctx, n, "Until", { set(it).stop }) { a, x -> set(a).stop = x }
        }
    }

    @Composable
    private fun ClockRow(ctx: CmdContext, n: Int, label: String, get: (CmdTeam) -> Long, set: (CmdTeam, Long) -> Unit) {
        val core = ctx.core
        val value = get(core.teams[n].team)
        EdRow(label) {
            EdField(if (value == 0L) "" else CampaignArchive.clock(value), core.partEditable("tea"), mono = true, width = 120.dp) { s ->
                val ms = if (s.isBlank()) 0L else com.bmscompanion.desktop.bridge.command.CampaignOps.parseClock(s)
                if (ms == null) CommandSession.status = "Write a time as D2 04:30:00 (day, hours, minutes, seconds)."
                else ctx.edit(TeamOps.field(core, n, label, get, set, ms))
            }
        }
    }

    @Composable
    private fun Priorities(ctx: CmdContext, n: Int, which: String) {
        val core = ctx.core
        val t = core.teams[n].team
        val (arr, title, nameOf) = when (which) {
            "objective" -> Triple(t.objtypePriority, "Objective priorities", { i: Int -> core.string(500 + i)?.trim()?.ifEmpty { null } ?: "Type $i" })
            "unit" -> Triple(t.unittypePriority, "Unit type priorities", { i: Int -> "Unit type $i" })
            else -> Triple(t.missionPriority, "Mission priorities", { i: Int -> core.string(CampaignArchive.MISSION_STRINGS + i)?.trim()?.ifEmpty { null } ?: "Mission $i" })
        }
        var open by remember(which) { mutableStateOf(false) }
        EdHeading("$title (${arr.size})") { Text(if (open) "Fold" else "Show all", color = Hud.Amber, fontSize = 11.sp, modifier = Modifier.clickable { open = !open }) }
        val shown = if (open) arr.indices.toList() else arr.indices.filter { arr[it] > 0 }.take(10)
        for (i in shown) {
            Row(Modifier.fillMaxWidth().height(23.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$i", color = Hud.TextFaint, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(22.dp))
                Text(nameOf(i), color = Hud.Text, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                EdField("${arr[i]}", core.partEditable("tea"), mono = true, width = 56.dp, align = TextAlign.End) { s ->
                    s.trim().toIntOrNull()?.let { ctx.edit(TeamOps.priority(core, n, which, i, it)) } ?: run { CommandSession.status = "A priority is a number from 0 to 255." }
                }
            }
            Rule(0.08f)
        }
        if (!open && arr.count { it > 0 } > 10) EdNote("${arr.count { it > 0 } - 10} more above 0 …")
    }

    @Composable
    private fun Tasking(ctx: CmdContext, n: Int) {
        val core = ctx.core
        val e = core.teams[n]
        val atm = e.air
        val can = core.partEditable("tea")
        EdHeading("Air tasking manager of ${core.teamName(n)} — ${atm.requests.size} mission requests, ${atm.airbases.size} airbase schedules") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DeskButton("Delete its tasks", enabled = can && atm.requests.isNotEmpty(), danger = true) { ctx.edit(TeamOps.clearRequests(core, n)) }
                DeskButton("Delete every team's tasks", enabled = can, danger = true) { ctx.edit(TeamOps.clearRequests(core, n, all = true)) }
                DeskButton("Delete its schedules", enabled = can && atm.airbases.isNotEmpty(), danger = true) { ctx.edit(TeamOps.clearSchedules(core, n)) }
                DeskButton("Delete every team's schedules", enabled = can, danger = true) { ctx.edit(TeamOps.clearSchedules(core, n, all = true)) }
            }
        }
        FactRow("Manager", "id ${atm.base.id} · flags 0x${atm.flags.toString(16)} · manager flags 0x${atm.base.managerFlags.toString(16)} · cycle ${atm.cycle} · CA strength ${atm.averageCAStrength}, missions ${atm.averageCAMissions}", mono = true)
        FactRow("Ground · naval", "id ${e.ground.base.id} flags 0x${e.ground.flags.toString(16)} · id ${e.naval.base.id} flags 0x${e.naval.flags.toString(16)}", mono = true)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(560.dp)) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    listOf("MISSION" to 140, "TARGET" to 170, "TOT" to 100, "WHO" to 70, "PRIO" to 50).forEach { (h, w) -> Text(h, color = Hud.TextFaint, fontSize = 9.5.sp, modifier = Modifier.width(w.dp)) }
                }
                atm.requests.take(200).forEach { r ->
                    Row(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(core.string(CampaignArchive.MISSION_STRINGS + r.mission)?.trim() ?: "${r.mission}", color = Hud.Text, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.width(140.dp))
                        Text(core.objective(r.target)?.let { com.bmscompanion.desktop.bridge.command.ObjectiveOps.displayName(core, it) } ?: core.unit(r.target)?.let { "unit ${it.campId}" } ?: "${r.target}", color = Hud.TextDim, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(170.dp))
                        Text(if (r.tot > 0) CampaignArchive.clock(r.tot) else "", color = Hud.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(100.dp))
                        Text(core.teamName(r.who), color = Hud.TextDim, fontSize = 11.sp, maxLines = 1, modifier = Modifier.width(70.dp))
                        Text("${r.priority}", color = Hud.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(50.dp))
                        Text("✕", color = if (can) Hud.Red else Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.clickable(enabled = can) { ctx.edit(TeamOps.removeRequest(core, n, r)) })
                    }
                    Rule(0.08f)
                }
                if (atm.requests.size > 200) EdNote("${atm.requests.size - 200} more requests are not listed.")
                if (atm.requests.isEmpty()) EdNote("No mission requests waiting.")
            }
            Column(Modifier.width(460.dp)) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text("AIRBASE", color = Hud.TextFaint, fontSize = 9.5.sp, modifier = Modifier.width(170.dp))
                    Text("SCHEDULE (32 SLOTS)", color = Hud.TextFaint, fontSize = 9.5.sp)
                }
                atm.airbases.forEach { a ->
                    Row(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(core.objective(a.id)?.let { com.bmscompanion.desktop.bridge.command.ObjectiveOps.displayName(core, it) } ?: core.unit(a.id)?.let { "unit ${it.campId}" } ?: "${a.id}", color = Hud.Text, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(170.dp))
                        Text(a.schedule.joinToString("") { b -> val x = b.toInt() and 0xFF; if (x == 0) "·" else if (x < 10) "$x" else "+" }, color = Hud.TextDim, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("✕", color = if (can) Hud.Red else Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.clickable(enabled = can) { ctx.edit(TeamOps.removeSchedule(core, n, a)) })
                    }
                    Rule(0.08f)
                }
                if (atm.airbases.isEmpty()) EdNote("No airbase schedules (BMS plans them again).")
            }
        }
    }

    private val COLOURS = listOf("White", "Green", "Blue", "Brown", "Orange", "Yellow", "Red", "Grey")
}
