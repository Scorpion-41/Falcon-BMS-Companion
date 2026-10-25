package com.bmscompanion.desktop.ui.command

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.CampaignArchive
import kotlin.math.roundToInt

/**
 * What the section shows of a page while the editor has registered nothing for it ([CommandSlots]): read-only tables
 * for Units, Objectives and the ATO, read-only views of Teams and Campaign, a sentence elsewhere, and an inspector of
 * facts. An editor's own slot always wins over these.
 */
object CommandBuiltIns {
    val tables: Map<CmdPage, CmdTable> = mapOf(
        CmdPage.UNITS to object : CmdTable {
            override val page = CmdPage.UNITS
            @Composable override fun Content(ctx: CmdContext) = UnitsTable(ctx)
        },
        CmdPage.OBJECTIVES to object : CmdTable {
            override val page = CmdPage.OBJECTIVES
            @Composable override fun Content(ctx: CmdContext) = ObjectivesTable(ctx)
        },
        CmdPage.ATO to object : CmdTable {
            override val page = CmdPage.ATO
            @Composable override fun Content(ctx: CmdContext) = AtoTable(ctx)
        },
    )

    val pages: Map<CmdPage, CmdPageView> = mapOf(
        CmdPage.TEAMS to view(CmdPage.TEAMS) { TeamsView(it) },
        CmdPage.CAMPAIGN to view(CmdPage.CAMPAIGN) { CampaignView(it) },
        CmdPage.PILOTS to view(CmdPage.PILOTS) { NotYet("Pilots", "The pilots and callsigns of the file (.plt) are edited here.") },
        CmdPage.CHECKS to view(CmdPage.CHECKS) { NotYet("Checks", "One report of everything in the file BMS would trip over — doubled ids, dangling references, squadrons missing from the header's list, packages of more than five flights … — each with its Fix.") },
        CmdPage.FILES to view(CmdPage.FILES) { NotYet("Files", "The theater-wide files (Stations+Ils.dat, RadioMap.dat, the TE lists …), each behind its one-time backup.") },
    )

    private fun view(p: CmdPage, body: @Composable (CmdContext) -> Unit) = object : CmdPageView {
        override val page = p
        @Composable override fun Content(ctx: CmdContext) = body(ctx)
    }

    /** The facts of the selection, read only. */
    @Composable
    fun Inspector(ctx: CmdContext, refs: List<CmdRef>) {
        val world = ctx.world
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (refs.size > 1) {
                Text("${refs.size} selected", color = Hud.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(8.dp))
                refs.groupBy { it.kind }.forEach { (k, rs) -> FactRow(k.label, "${rs.size}") }
                Spacer(Modifier.height(6.dp))
            }
            val r = refs.first()
            val lines = hoverLines(world, ctx.doc, r)
            Text(lines.firstOrNull() ?: world.nameOf(r), color = Hud.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            Text(r.kind.label.uppercase(), color = Hud.TextFaint, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Rule()
            lines.drop(1).forEach { FactRow("", it) }
            when (r.kind) {
                CmdKind.OBJECTIVE -> world.objective(r.id)?.let { o ->
                    FactRow("Id", o.ref.id, mono = true)
                    FactRow("CampId", "${o.campId}", mono = true)
                    FactRow("Type", "${o.typeName} (${o.type})")
                    FactRow("Class (OCD)", "${o.ocd}", mono = true)
                    FactRow("Owner", world.team(o.owner)?.name ?: "${o.owner}", lead = world.teamLead(o.owner))
                    FactRow("First owner", world.team(o.firstOwner)?.name ?: "${o.firstOwner}", lead = world.teamLead(o.firstOwner))
                    FactRow("Position", "N ${o.north.roundToInt()}  E ${o.east.roundToInt()} ft", mono = true)
                    FactRow("Flags", "0x" + o.flags.toString(16), mono = true)
                }
                CmdKind.DIVISION -> Unit
                CmdKind.WAYPOINT -> Unit
                else -> world.unit(r.id)?.let { u ->
                    FactRow("Id", u.ref.id, mono = true)
                    FactRow("CampId", "${u.campId}", mono = true)
                    FactRow("Class", u.cls)
                    FactRow("Owner", world.team(u.owner)?.name ?: "${u.owner}", lead = world.teamLead(u.owner))
                    FactRow("Position", "N ${u.north.roundToInt()}  E ${u.east.roundToInt()} ft", mono = true)
                    if (u.route.isNotEmpty()) FactRow("Waypoints", "${u.route.size} (flying to ${u.currentWp + 1})")
                    FactRow("Unit flags", "0x" + u.unitFlags.toString(16), mono = true)
                }
            }
            SmallNote(if (ctx.doc.editing == null) "Read only: the editor of this kind is not in this build yet." else "This kind has no inspector of its own yet.")
        }
    }
}

@Composable
private fun NotYet(title: String, what: String) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(title, color = Hud.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(what, color = Hud.TextDim, fontSize = 12.5.sp)
        Spacer(Modifier.height(6.dp))
        Text("Not in this build yet.", color = Hud.TextFaint, fontSize = 12.sp)
    }
}

private fun sideRefs(ctx: CmdContext, r: CmdRef, additive: Boolean) {
    if (additive) ctx.selection.toggle(r) else { ctx.selection.set(r); CommandSession.flyTo(r) }
}

@Composable
private fun UnitsTable(ctx: CmdContext) {
    @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
    val world = ctx.world
    var kind by remember { mutableStateOf<CmdKind?>(null) }
    val rows = remember(world, kind) { world.units.filter { kind == null || it.kind == kind } }
    Column(Modifier.fillMaxSize()) {
        Strip {
            MiniChip("All", kind == null) { kind = null }
            listOf(CmdKind.SQUADRON, CmdKind.FLIGHT, CmdKind.PACKAGE, CmdKind.BATTALION, CmdKind.BRIGADE, CmdKind.TASKFORCE).forEach { k ->
                MiniChip(k.label + "s", kind == k) { kind = k }
            }
        }
        CmdGrid(
            rows = rows,
            cols = listOf(
                GridCol("Name", 230.dp) { it.name },
                GridCol("Kind", 90.dp) { it.kind.label },
                GridCol("CampId", 70.dp, numeric = true, sort = { it.campId }) { "${it.campId}" },
                GridCol("Team", 110.dp, ink = { u -> world.team(u.owner)?.let { teamInk(it.colour) } }, lead = { u -> world.teamLead(u.owner) }) { world.team(it.owner)?.name ?: "${it.owner}" },
                GridCol("Class", 170.dp) { it.aircraft ?: it.cls },
                GridCol("Base / objective", 180.dp) { u -> (u.airbase ?: u.objective)?.let { world.objective(it)?.name ?: world.unit(it)?.name }.orEmpty() },
                GridCol("Waypoints", 80.dp, numeric = true, sort = { it.route.size }) { "${it.route.size}" },
                GridCol("Grid", 100.dp, sort = { it.east }) { "${(it.east / CampaignArchive.FEET_PER_CELL).toInt()}, ${(it.north / CampaignArchive.FEET_PER_CELL).toInt()}" },
                GridCol("State", 120.dp) { u -> listOfNotNull(if (u.inactive) "inactive" else null, if (u.dead) "destroyed" else null, if (u.moving) "moving" else null).joinToString(", ") },
            ),
            key = { it.ref.id },
            selected = { it.ref in ctx.selection },
            onClick = { u, add -> sideRefs(ctx, u.ref, add) },
            scrollTo = ctx.selection.primary?.id,
        )
    }
}

@Composable
private fun ObjectivesTable(ctx: CmdContext) {
    @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
    val world = ctx.world
    var group by remember { mutableStateOf<ObjGroup?>(null) }
    val rows = remember(world, group) { world.objectives.filter { group == null || it.group == group } }
    Column(Modifier.fillMaxSize()) {
        Strip {
            MiniChip("All", group == null) { group = null }
            ObjGroup.entries.forEach { g -> MiniChip(g.label.substringBefore(',').substringBefore(" and"), group == g) { group = g } }
        }
        CmdGrid(
            rows = rows,
            cols = listOf(
                GridCol("Name", 230.dp) { it.name },
                GridCol("Type", 130.dp) { it.typeName },
                GridCol("CampId", 70.dp, numeric = true, sort = { it.campId }) { "${it.campId}" },
                GridCol("Owner", 110.dp, ink = { o -> world.team(o.owner)?.let { teamInk(it.colour) } }, lead = { o -> world.teamLead(o.owner) }) { world.team(it.owner)?.name ?: "${it.owner}" },
                GridCol("First owner", 110.dp, lead = { o -> world.teamLead(o.firstOwner) }) { world.team(it.firstOwner)?.name ?: "${it.firstOwner}" },
                GridCol("Line", 60.dp, numeric = true, sort = { it.frontLine }) { if (it.frontLine == 0) "" else "${it.frontLine}" },
                GridCol("Priority", 70.dp, numeric = true, sort = { it.priority ?: -1 }) { it.priority?.toString().orEmpty() },
                GridCol("Links", 60.dp, numeric = true, sort = { it.links?.size ?: -1 }) { it.links?.size?.toString().orEmpty() },
                GridCol("Grid", 100.dp, sort = { it.east }) { "${(it.east / CampaignArchive.FEET_PER_CELL).toInt()}, ${(it.north / CampaignArchive.FEET_PER_CELL).toInt()}" },
                GridCol("Changed", 80.dp) { if (it.owner != it.startOwner) "owner" else if (it.hasDelta) "yes" else "" },
            ),
            key = { it.ref.id },
            selected = { it.ref in ctx.selection },
            onClick = { o, add -> sideRefs(ctx, o.ref, add) },
            scrollTo = ctx.selection.primary?.id,
        )
    }
}

/** The ATO, flat: every flight with its package, mission and times. */
@Composable
private fun AtoTable(ctx: CmdContext) {
    @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
    val world = ctx.world
    val strings = ctx.doc.names.strings
    val rows = remember(world) { world.units.filter { it.kind == CmdKind.FLIGHT } }
    CmdGrid(
        rows = rows,
        cols = listOf(
            GridCol("Flight", 130.dp) { it.name },
            GridCol("Package", 80.dp, numeric = true, sort = { u -> world.unit(u.parent)?.campId ?: -1 }) { u -> world.unit(u.parent)?.campId?.toString().orEmpty() },
            GridCol("Team", 110.dp, ink = { u -> world.team(u.owner)?.let { teamInk(it.colour) } }, lead = { u -> world.teamLead(u.owner) }) { world.team(it.owner)?.name ?: "${it.owner}" },
            GridCol("Mission", 120.dp) { u -> u.mission?.let { strings[CampaignArchive.MISSION_STRINGS + it]?.trim() }.orEmpty() },
            GridCol("Aircraft", 150.dp) { it.aircraft ?: it.cls },
            GridCol("Squadron", 170.dp) { u -> world.unit(u.squadron)?.name.orEmpty() },
            GridCol("Take-off", 90.dp, sort = { it.route.firstOrNull()?.arrive ?: 0L }) { u -> u.route.firstOrNull()?.arrive?.takeIf { it > 0 }?.let { CampaignArchive.clock(it) }.orEmpty() },
            GridCol("Waypoints", 80.dp, numeric = true, sort = { it.route.size }) { "${it.route.size}" },
        ),
        key = { it.ref.id },
        selected = { it.ref in ctx.selection },
        onClick = { u, add -> sideRefs(ctx, u.ref, add) },
        scrollTo = ctx.selection.primary?.id,
    )
}

/** The teams: name, colour, units and objectives held, and the stances table when the file's team part is read. */
@Composable
private fun TeamsView(ctx: CmdContext) {
    val world = ctx.world
    val recs = ctx.core.tea?.teams?.map { it.team }
    val stance = listOf("none", "allied", "friendly", "neutral", "hostile", "war")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Teams", color = Hud.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text("TEAM", color = Hud.TextFaint, fontSize = 10.sp, modifier = Modifier.width(180.dp))
            Text("UNITS", color = Hud.TextFaint, fontSize = 10.sp, modifier = Modifier.width(70.dp))
            Text("OBJECTIVES", color = Hud.TextFaint, fontSize = 10.sp, modifier = Modifier.width(90.dp))
            world.teams.filter { it.inUse }.forEach { Text(it.name.take(10).uppercase(), color = Hud.TextFaint, fontSize = 10.sp, modifier = Modifier.width(80.dp)) }
        }
        Rule()
        world.teams.filter { it.inUse }.forEach { tm ->
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TeamFlagLead(tm.name, tm.colour)
                Text(tm.name, color = Hud.Text, fontSize = 12.sp, modifier = Modifier.width(161.dp))
                Text("${world.units.count { it.owner == tm.n }}", color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.width(70.dp))
                Text("${world.objectives.count { it.owner == tm.n }}", color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.width(90.dp))
                val rec = recs?.getOrNull(tm.n)
                world.teams.filter { it.inUse }.forEach { other ->
                    val s = rec?.stance?.getOrNull(other.n)
                    Text(s?.let { stance.getOrNull(it) } ?: "—", color = if (s == 4 || s == 5) Hud.Red else Hud.TextDim, fontSize = 12.sp, modifier = Modifier.width(80.dp))
                }
            }
            Rule(0.15f)
        }
        if (recs == null) SmallNote("The file's team part could not be read: no stances.")
        SmallNote("Read only until the editor of teams is in this build.")
    }
}

/** The campaign header's figures, read only. */
@Composable
private fun CampaignView(ctx: CmdContext) {
    val h = ctx.core.cmp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Campaign", color = Hud.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (h == null) { SmallNote("The campaign header could not be read."); return@Column }
        Column(Modifier.width(520.dp)) {
            FactRow("Name", h.uiName.text.trim().takeIf { it.isNotEmpty() && it != "???" } ?: "—")
            FactRow("Theater", h.theaterName.text)
            FactRow("Start file", h.scenario.text)
            FactRow("Saved as", h.saveFile.text)
            FactRow("Clock", CampaignArchive.clock(h.currentTime))
            FactRow("Started", CampaignArchive.clock(h.startTime))
            FactRow("Time limit", if (h.timeLimit > 0) CampaignArchive.clock(h.timeLimit) else "—")
            FactRow("Day", "${h.currentDay} (day zero ${h.dayZero})")
            FactRow("Theater size", "${h.theaterSizeX} x ${h.theaterSizeY} km")
            FactRow("Bullseye", "grid ${h.bullseyeX}, ${h.bullseyeY}")
            FactRow("Squadrons listed", "${h.squadInfo.size}")
            FactRow("TE type", "${h.teType} · flags 0x${h.teFlags.toString(16)} · victory points ${h.teVictoryPoints}")
        }
        SmallNote("Read only until the editor of the header is in this build.")
    }
}
