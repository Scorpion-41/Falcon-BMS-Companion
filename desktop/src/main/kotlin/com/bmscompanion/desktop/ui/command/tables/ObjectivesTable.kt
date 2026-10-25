package com.bmscompanion.desktop.ui.command.tables

import com.bmscompanion.desktop.ui.command.teamLead
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.command.CmdDelta
import com.bmscompanion.desktop.bridge.command.ObjectiveOps
import com.bmscompanion.desktop.bridge.command.ObjectiveOps.FeatureStates
import com.bmscompanion.desktop.bridge.command.PersistentObject
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdGrid
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdPage
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.CmdTable
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.DeskButton
import com.bmscompanion.desktop.ui.command.GridCol
import com.bmscompanion.desktop.ui.command.MiniChip
import com.bmscompanion.desktop.ui.command.ObjGroup
import com.bmscompanion.desktop.ui.command.Strip
import com.bmscompanion.desktop.ui.command.VRule
import com.bmscompanion.desktop.ui.command.ViewObjective
import com.bmscompanion.desktop.ui.command.inspectors.EdDialogHost
import com.bmscompanion.desktop.ui.command.inspectors.AddDeleteButtons
import com.bmscompanion.desktop.ui.command.inspectors.AddDeleteReasonStrip
import com.bmscompanion.desktop.ui.command.RefMenuItems
import com.bmscompanion.desktop.ui.command.inspectors.EdMenu
import com.bmscompanion.desktop.ui.command.inspectors.edit
import com.bmscompanion.desktop.ui.command.inspectors.askDelete
import com.bmscompanion.desktop.ui.command.inspectors.editingStart
import com.bmscompanion.desktop.ui.command.inspectors.objMode
import com.bmscompanion.desktop.ui.command.inspectors.objective
import com.bmscompanion.desktop.ui.command.inspectors.radarAll
import com.bmscompanion.desktop.ui.command.teamInk
import kotlin.math.roundToInt

/**
 * The Objectives page's drawer: the objectives (filtered by kind, owner, a search, "changed in this save"), the save's
 * objective changes (deltas) and its persistent objects (craters, wrecks), each a sortable table sharing the map's
 * selection, with the page's bulk actions on the strip above.
 */
object ObjectivesTable : CmdTable {
    override val page = CmdPage.OBJECTIVES

    private enum class View(val label: String) { OBJECTIVES("Objectives"), DELTAS("Changes in this save"), PERSISTENT("Persistent objects") }

    @Composable
    override fun Content(ctx: CmdContext) {
        @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
        var view by remember { mutableStateOf(View.OBJECTIVES) }
        Column(Modifier.fillMaxSize()) {
            EdDialogHost()
            Strip {
                View.entries.forEach { w ->
                    val n = when (w) {
                        View.OBJECTIVES -> ctx.world.objectives.size
                        View.DELTAS -> ctx.core.obd?.count ?: 0
                        View.PERSISTENT -> ctx.core.pst?.objects?.size ?: 0
                    }
                    MiniChip("${w.label} $n", view == w) { view = w }
                }
                VRule()
                ObjectiveOps.modeWhy(ctx.core, ctx.editingStart()).let { why ->
                    Box(Modifier.weight(1f)) { com.bmscompanion.desktop.ui.command.Tip(why) { Text(why, color = Hud.TextFaint, fontSize = 11.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } }
                }
            }
            when (view) {
                View.OBJECTIVES -> Objectives(ctx)
                View.DELTAS -> Deltas(ctx)
                View.PERSISTENT -> Persistent(ctx)
            }
        }
    }

    @Composable
    private fun Objectives(ctx: CmdContext) {
        val world = ctx.world
        val core = ctx.core
        var group by remember { mutableStateOf<ObjGroup?>(null) }
        var team by remember { mutableStateOf(-1) }
        var changed by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        val rows = remember(world, group, team, changed, query) {
            val q = query.trim().lowercase()
            world.objectives.filter { o ->
                (group == null || o.group == group) && (team < 0 || o.owner == team) && (!changed || o.hasDelta || o.owner != o.startOwner) &&
                    (q.isEmpty() || o.name.lowercase().contains(q) || o.campId.toString() == q || o.typeName.lowercase().contains(q))
            }
        }
        val mode = ctx.objMode()
        val full = mode == ObjectiveOps.Mode.FULL
        val sel = ctx.selection.refs.mapNotNull { ctx.objective(it) }
        Strip {
            BasicTextField(
                query, { query = it }, singleLine = true, textStyle = TextStyle(color = Hud.Text, fontSize = 12.sp), cursorBrush = SolidColor(Hud.Amber),
                modifier = Modifier.width(150.dp).clip(RoundedCornerShape(3.dp)).background(Hud.Bg).border(1.dp, Hud.Outline, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("Find name, CampId, type", color = Hud.TextFaint, fontSize = 11.5.sp); inner() } },
            )
            EdMenu(group?.ordinal ?: -1, listOf(-1 to "Every kind") + ObjGroup.entries.map { it.ordinal to it.label }, modifier = Modifier.width(170.dp)) { group = ObjGroup.entries.getOrNull(it) }
            EdMenu(team, listOf(-1 to "Every owner") + world.teams.filter { it.inUse }.map { it.n to it.name }, ink = { n -> world.team(n)?.let { teamInk(it.colour) } }, modifier = Modifier.width(140.dp), lead = { n -> if (n < 0) null else world.teamLead(n) }) { team = it }
            MiniChip("Changed", changed) { changed = !changed }
            VRule()
            if (mode != ObjectiveOps.Mode.NONE) DeskButton("Repair ${if (sel.isEmpty()) "…" else "${sel.size}"}", enabled = sel.isNotEmpty(), tip = "Every building of the selected objectives intact") { ctx.edit(ObjectiveOps.repair(core, sel, full)) }
            VRule()
            // add and delete: working in a start being edited, else greyed with the reason and the way to its start
            AddDeleteButtons(ctx, sel)
        }
        val bulk: @Composable () -> Unit = {
            DeskButton("Bulk…", tip = "Supply and fuel by kind; first owners, front lines, parents, radars and a reset of a start's records") { com.bmscompanion.desktop.ui.command.inspectors.MoreDialogs.req = com.bmscompanion.desktop.ui.command.inspectors.MoreDialogs.ObjectivesBulk }
            DeskButton("Export CSV…", tip = "Every objective as a CSV list (owners, priority, supply, damage, place), where you pick") { com.bmscompanion.desktop.ui.command.inspectors.exportCsv(ctx, objectives = true) }
        }
        // elsewhere: why objectives are not added or deleted here, and the way to the file where they are
        if (!full) AddDeleteReasonStrip(ctx, bulk)
        // a start being edited: the record's own actions on a second line
        if (full) Strip {
            bulk()
            VRule()
            run {
                DeskButton("Auto-link", enabled = sel.isNotEmpty(), tip = "Link the selected objectives to their nearest neighbours") { ctx.edit(ObjectiveOps.autoLink(core, sel)) }
                DeskButton("Recalculate costs", tip = "Every link's costs from its length, by the theater's own links (the selected, else all)") { ctx.edit(ObjectiveOps.recost(core, sel.ifEmpty { core.objectives })) }
                DeskButton("Radar from terrain", tip = "The radar sectors of the selected radars (else every radar of the start) from the ground round them") {
                    val ocds = core.tables.ocd
                    radarAll(ctx, sel.ifEmpty { core.objectives.filter { o -> (ocds[ObjectiveOps.ocdOf(core, o)]?.radarFeature ?: 255) < 255 } }.filter { it.hasRadar != 0 })
                }
                DeskButton("Check links", tip = "The network's problems, with their fixes, on the Checks page") { ctx.open(CmdPage.CHECKS) }
                DeskButton("Remove links ${sel.size}", enabled = sel.isNotEmpty(), danger = true, tip = "Every link of the selected objectives") { ctx.edit(ObjectiveOps.removeLinks(core, sel)) }
            }
        }
        CmdGrid(
            rows = rows,
            cols = listOf(
                GridCol<ViewObjective>("Name", 220.dp) { it.name },
                GridCol("Type", 120.dp) { it.typeName },
                GridCol("CampId", 66.dp, numeric = true, sort = { it.campId }) { "${it.campId}" },
                GridCol("Owner", 105.dp, ink = { o -> world.team(o.owner)?.let { teamInk(it.colour) } }, lead = { o -> world.teamLead(o.owner) }) { world.team(it.owner)?.name ?: "${it.owner}" },
                GridCol("First", 90.dp, lead = { o -> world.teamLead(o.firstOwner) }) { world.team(it.firstOwner)?.name ?: "${it.firstOwner}" },
                GridCol("Prio", 50.dp, numeric = true, sort = { it.priority ?: -1 }) { it.priority?.toString().orEmpty() },
                GridCol("Supply", 58.dp, numeric = true) { o -> core.objective(o.id)?.let { "${core.delta(it.id)?.supply ?: it.supply}" }.orEmpty() },
                GridCol("Fuel", 50.dp, numeric = true) { o -> core.objective(o.id)?.let { "${core.delta(it.id)?.fuel ?: it.fuel}" }.orEmpty() },
                GridCol("Dmg", 46.dp, numeric = true, sort = { o -> core.objective(o.id)?.let { damaged(ctx, it) } ?: 0 }) { o -> core.objective(o.id)?.let { damaged(ctx, it) }?.takeIf { it > 0 }?.toString().orEmpty() },
                GridCol("Line", 44.dp, numeric = true, sort = { it.frontLine }) { if (it.frontLine == 0) "" else "${it.frontLine}" },
                GridCol("Links", 50.dp, numeric = true, sort = { it.links?.size ?: -1 }) { it.links?.size?.toString().orEmpty() },
                GridCol("SAM", 40.dp) { if (it.samCapable) "✓" else "" },
                GridCol("Radar", 50.dp) { o -> if (o.radar) "✓" else "" },
                GridCol("Grid", 86.dp, sort = { it.east }) { "${(it.east / CampaignArchive.FEET_PER_CELL).toInt()}, ${(it.north / CampaignArchive.FEET_PER_CELL).toInt()}" },
                GridCol("Changed", 70.dp) { if (it.owner != it.startOwner) "owner" else if (it.hasDelta) "yes" else "" },
            ),
            key = { it.ref.id },
            selected = { it.ref in ctx.selection },
            onClick = { o, add -> if (add) ctx.selection.toggle(o.ref) else ctx.focus(o.ref) },
            scrollTo = ctx.selection.primary?.id,
            rowMenu = { o, close -> RefMenuItems(ctx, if (o.ref in ctx.selection) ctx.selection.refs else listOf(o.ref), o.ref, close) },
        )
    }

    private fun damaged(ctx: CmdContext, o: com.bmscompanion.desktop.bridge.command.CmdObjective): Int {
        val s = ObjectiveOps.statesNow(ctx.core, o)
        return FeatureStates.damaged(s, s.size)
    }

    @Composable
    private fun Deltas(ctx: CmdContext) {
        val core = ctx.core
        val obd = core.obd
        if (obd == null) { Text("This file has no objective changes part.", color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.padding(10.dp)); return }
        val unknown = remember(CommandSession.version) { core.unknownDeltas().toSet() }
        val idle = remember(CommandSession.version) { ObjectiveOps.idleDeltas(core) }
        val sel = ctx.selection.refs.filter { it.kind == CmdKind.OBJECTIVE }.map { it.id }.toSet()
        val selected = obd.deltas.filter { it.id.toString() in sel }
        Strip {
            Text(
                if (core.deltasIgnored) "BMS ignores these: the file names itself as its start." else "${obd.count} objectives differ from their start" + if (unknown.isNotEmpty()) " · ${unknown.size} name no objective of the start" else "",
                color = if (unknown.isNotEmpty()) Hud.Red else Hud.TextDim, fontSize = 11.5.sp, modifier = Modifier.weight(1f),
            )
            DeskButton("Delete ${selected.size}", enabled = selected.isNotEmpty(), danger = true, tip = "The selected objectives are as their start again") {
                ctx.edit(ObjectiveOps.removeDeltas(core, selected, "Remove ${selected.size} objective changes"))
            }
            DeskButton("Clean ${idle.size}", enabled = idle.isNotEmpty(), tip = "Drop the changes equal to the start and those naming no objective") {
                ctx.edit(ObjectiveOps.removeDeltas(core, idle, "Clean ${idle.size} objective changes"))
            }
            DeskButton("Remove all", enabled = obd.count > 0, danger = true, tip = "Every objective as its start") {
                ctx.edit(ObjectiveOps.removeDeltas(core, obd.deltas.toList(), "Remove every objective change"))
            }
        }
        val world = ctx.world
        CmdGrid(
            rows = obd.deltas.toList(),
            cols = listOf(
                GridCol<CmdDelta>("Objective", 220.dp, ink = { d -> if (d in unknown) Hud.Red else null }) { d -> core.objective(d.id)?.let { ObjectiveOps.displayName(core, it) } ?: "${d.id} (not in the start)" },
                GridCol("Id", 90.dp) { it.id.toString() },
                GridCol("Owner", 110.dp, ink = { d -> world.team(d.owner)?.let { teamInk(it.colour) } }, lead = { d -> world.teamLead(d.owner) }) { d -> world.team(d.owner)?.name ?: "${d.owner}" },
                GridCol("Was", 100.dp, lead = { d -> core.objective(d.id)?.let { world.teamLead(it.owner) } }) { d -> core.objective(d.id)?.let { world.team(it.owner)?.name ?: "${it.owner}" }.orEmpty() },
                GridCol("Supply", 60.dp, numeric = true) { "${it.supply}" },
                GridCol("Fuel", 50.dp, numeric = true) { "${it.fuel}" },
                GridCol("Losses", 60.dp, numeric = true) { "${it.losses}" },
                GridCol("Damaged", 70.dp, numeric = true, sort = { FeatureStates.damaged(it.fstatus, it.fstatus.size) }) { d -> FeatureStates.damaged(d.fstatus, d.fstatus.size).takeIf { it > 0 }?.toString().orEmpty() },
                GridCol("Repaired", 90.dp, sort = { it.lastRepair }) { d -> if (d.lastRepair > 0) CampaignArchive.clock(d.lastRepair) else "" },
                GridCol("Idle", 50.dp) { if (it in idle && it !in unknown) "yes" else "" },
            ),
            key = { it.id.toString() + "@" + System.identityHashCode(it) },
            selected = { it.id.toString() in sel },
            onClick = { d, add -> val r = CmdRef(CmdKind.OBJECTIVE, d.id.toString()); if (add) ctx.selection.toggle(r) else if (core.objective(d.id) != null) ctx.focus(r) else ctx.selection.set(r) },
            scrollTo = ctx.selection.primary?.id,
        )
    }

    @Composable
    private fun Persistent(ctx: CmdContext) {
        val core = ctx.core
        val pst = core.pst
        if (pst == null) { Text("This file has no persistent objects part.", color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.padding(10.dp)); return }
        var picked by remember { mutableStateOf<Set<Int>>(emptySet()) }
        val rows = pst.objects.withIndex().toList()
        Strip {
            Text("${pst.objects.size} craters, wrecks and other objects left on the ground", color = Hud.TextDim, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
            DeskButton("Delete ${picked.size}", enabled = picked.isNotEmpty(), danger = true) {
                val list = picked.mapNotNull { pst.objects.getOrNull(it) }
                picked = emptySet()
                ctx.edit(ObjectiveOps.removePersistent(core, list, "Remove ${list.size} persistent objects"))
            }
            DeskButton("Remove all", enabled = pst.objects.isNotEmpty(), danger = true) { picked = emptySet(); ctx.edit(ObjectiveOps.removePersistent(core, pst.objects.toList(), "Remove every persistent object")) }
        }
        CmdGrid(
            rows = rows,
            cols = listOf(
                GridCol<IndexedValue<PersistentObject>>("Nr", 50.dp, numeric = true, sort = { it.index }) { "${it.index + 1}" },
                GridCol("North", 90.dp, numeric = true, sort = { it.value.x }) { "${it.value.x.roundToInt()}" },
                GridCol("East", 90.dp, numeric = true, sort = { it.value.y }) { "${it.value.y.roundToInt()}" },
                GridCol("At", 200.dp) { p -> ObjectiveOps.nearestObjective(core, p.value)?.let { ObjectiveOps.displayName(core, it) }.orEmpty() },
                GridCol("Type", 70.dp, numeric = true) { "${it.value.visType}" },
                GridCol("Flags", 70.dp) { "0x" + it.value.flags.toString(16) },
                GridCol("Left by", 120.dp) { "${it.value.num}/${it.value.creator}" },
            ),
            key = { System.identityHashCode(it.value) },
            selected = { it.index in picked },
            onClick = { p, add -> picked = if (add) (if (p.index in picked) picked - p.index else picked + p.index) else setOf(p.index) },
        )
    }
}
