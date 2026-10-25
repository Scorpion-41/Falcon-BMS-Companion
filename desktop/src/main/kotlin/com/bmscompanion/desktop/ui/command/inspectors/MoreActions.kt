package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.command.AirOps
import com.bmscompanion.desktop.bridge.command.CmdBattalion
import com.bmscompanion.desktop.bridge.command.CmdBrigade
import com.bmscompanion.desktop.bridge.command.CmdFlight
import com.bmscompanion.desktop.bridge.command.CmdObjective
import com.bmscompanion.desktop.bridge.command.CmdPackage
import com.bmscompanion.desktop.bridge.command.CmdSquadron
import com.bmscompanion.desktop.bridge.command.CmdTaskForce
import com.bmscompanion.desktop.bridge.command.CmdUnit
import com.bmscompanion.desktop.bridge.command.GroundOps
import com.bmscompanion.desktop.bridge.command.ObjectiveOps
import com.bmscompanion.desktop.bridge.command.UnitClasses
import com.bmscompanion.desktop.bridge.command.UnitKind
import com.bmscompanion.desktop.bridge.command.UnitLife
import com.bmscompanion.desktop.bridge.command.UnitNames
import com.bmscompanion.desktop.bridge.command.VuId
import com.bmscompanion.desktop.ui.command.CmdAction
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdPage
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.CommandSlots
import com.bmscompanion.desktop.ui.command.DeskButton
import com.bmscompanion.desktop.ui.command.openFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.hypot

// The rest of the map's and the tables' menus, per kind, the way a campaign designer works them:
//  objective — view its buildings, its airport schedule, link to / unlink from the selected objective, remove its
//              links, add an objective here (a dialog), delete it; in a save every add/delete line is shown greyed with
//              the reason and one line opens the start the save takes its objectives from;
//  unit      — clone (a table row: in place), select its package or squadron, add a flight to a package, the airport
//              schedule of a squadron's base or a carrier, move the selected task force or ground unit here.
// Their dialogs (buildings, airport schedule, add an objective) are drawn by [MoreDialogs.Host] over the workspace.

object MoreActions {
    fun install() {
        CommandSlots.actions += listOf(
            ViewBuildings, AirportSchedule, LinkToSelected, UnlinkFromSelected, RemoveAllLinks, DeleteObjective, OpenStartForObjectives,
            CloneInPlace, SelectPackage, SelectSquadron, AddFlightToPackage, MoveSelectedHere,
        )
        CommandSlots.overlays += { ctx -> MoreDialogs.Host(ctx) }
        // one order for every menu: what the objective is, then what is added at it, then the units' own lines
        val order = listOf<CmdAction>(
            ViewBuildings, AirportSchedule, RepairObjective, LinkToSelected, UnlinkFromSelected, RemoveAllLinks,
            AddSquadronHere, AddBattalionHere, AddBrigadeHere, AddTaskForceHere, AddObjectiveHere, DeleteObjective, OpenStartForObjectives,
            AddFlightTo, AddFlightToPackage, SelectPackage, SelectSquadron, AddWaypointHere, BaseHere, PlaceHere, SendHere, MoveSelectedHere,
            CloneHere, CloneInPlace, DeleteUnit, DeleteWithChildren,
        )
        val sorted = CommandSlots.actions.sortedBy { a -> order.indexOf(a).let { if (it < 0) order.size else it } }
        CommandSlots.actions.clear(); CommandSlots.actions += sorted
    }
}

private const val CELL = CampaignArchive.FEET_PER_CELL
private fun cell(feet: Double) = floor(feet / CELL).toInt()
private val UNIT_KINDS = setOf(CmdKind.FLIGHT, CmdKind.PACKAGE, CmdKind.SQUADRON, CmdKind.BATTALION, CmdKind.BRIGADE, CmdKind.TASKFORCE)
private fun CmdContext.unitRef(ref: CmdRef?): CmdUnit? = ref?.takeIf { it.kind in UNIT_KINDS }?.let { unitOf(it) }
private fun CmdContext.isAirfield(o: CmdObjective) = UnitClasses.of(core).clsOfType(o.type)?.type in setOf(1, 2)
private fun CmdContext.isCarrier(u: CmdUnit?) = u is CmdTaskForce && UnitClasses.of(core).clsOfType(u.type)?.sub == 3

// ------------------------------------------------------------------ adding and deleting objectives: which file

/**
 * Where objectives may be added and deleted ([ObjectiveOps.Mode.FULL]: a campaign start being edited, or the pilot's
 * own file whose objectives BMS reads), and in a save the start it takes its objectives from.
 */
object ObjectiveAddDelete {
    /** null when objectives may be added and deleted here; else the reason in one sentence. */
    fun refusal(ctx: CmdContext): String? {
        val core = ctx.core
        return when {
            ctx.objMode() == ObjectiveOps.Mode.FULL -> null
            core.objectivesEditable -> "${ctx.doc.file.name} is one of BMS's campaign starts: choose Edit BMS's campaign start first (its original is backed up once)."
            core.startFile != null -> "A save takes its objectives from its start, ${core.startFile.name}: open the start to add or delete objectives."
            core.scenario.isNotEmpty() && !core.objectivesFromOwn -> "A save takes its objectives from its start, ${core.scenario}, which is not in this folder."
            else -> ObjectiveOps.modeWhy(core, ctx.editingStart())
        }
    }

    /** The start a save takes its objectives from, when it can be opened. */
    fun startOf(ctx: CmdContext): java.io.File? = ctx.core.startFile?.takeIf { it.isFile && ctx.objMode() != ObjectiveOps.Mode.FULL }

    /** A BMS start not yet being edited: the identity strip's Edit BMS's campaign start, asked from here. */
    fun canEditStart(ctx: CmdContext): Boolean = ctx.doc.editing?.let { it.isBmsStart && !it.editingStart } == true

    fun askEditStart(ctx: CmdContext) {
        val e = ctx.doc.editing ?: return
        EdDialogs.ask = EdAsk(
            "Edit BMS's campaign start in place?",
            "${ctx.doc.file.name} is one of Falcon BMS's own campaign starts. Before the first change is written, BMS's original is copied once into " +
                "\"BMS Companion Backup\\Command\" beside it, with a README saying how to put it back by hand. Restore BMS's original does it from here. " + savesLine(ctx),
            "Edit in place", danger = false,
        ) { e.editStart() }
    }

    /** Opens the save's start in the section (asking first when the save has edits not saved), then offers to edit it. */
    fun openStart(ctx: CmdContext) {
        val start = startOf(ctx) ?: run { ctx.say(refusal(ctx) ?: "This file has no start to open."); return }
        val n = ctx.doc.editing?.editCount ?: 0
        val go: () -> String? = {
            CoroutineScope(Dispatchers.Main).launch {
                openFile(start)
                val d = CommandSession.doc
                if (d != null && d.file == start) {
                    CommandSession.page = CmdPage.OBJECTIVES
                    val e = d.editing
                    if (e != null && e.isBmsStart && !e.editingStart) {
                        EdDialogs.ask = EdAsk(
                            "Edit ${start.name} in place?",
                            "${start.name} is the campaign start ${ctx.doc.file.name} takes its objectives from, and one of Falcon BMS's own. Objectives are added and deleted " +
                                "in it: before the first change is written, BMS's original is copied once into \"BMS Companion Backup\\Command\" beside it (Restore BMS's original puts it back). " +
                                "Every save that names it takes the change.",
                            "Edit in place", danger = false,
                        ) { e.editStart() }
                    }
                }
            }
            null
        }
        if (n > 0) EdDialogs.ask = EdAsk(
            "Open ${start.name}?",
            "${ctx.doc.file.name} has $n edit${if (n == 1) "" else "s"} not saved: they are lost when its start is opened (Save as… first to keep them).",
            "Open the start", danger = true, run = go,
        ) else go()
    }
}

/**
 * The inspector's block in a file where objectives are not added or deleted: the two buttons greyed with the reason
 * on one line, and the way there (open the save's start, or Edit BMS's campaign start).
 */
@Composable
internal fun AddDeleteBlock(ctx: CmdContext) {
    val why = ObjectiveAddDelete.refusal(ctx) ?: return
    EdHeading("Add or delete objectives")
    EdNote(why, Hud.TextDim)
    EdButtons {
        DeskButton("Add objective…", enabled = false, tip = why) {}
        DeskButton("Delete objective", enabled = false, tip = why) {}
        AddDeleteWay(ctx)
    }
}

/** The button that leads to where objectives are added and deleted, when there is one. */
@Composable
internal fun AddDeleteWay(ctx: CmdContext) {
    if (ObjectiveAddDelete.canEditStart(ctx)) DeskButton("Edit BMS's campaign start…", strong = true, tip = "Edit this start in place, after a one-time copy of BMS's original beside it") { ObjectiveAddDelete.askEditStart(ctx) }
    else ObjectiveAddDelete.startOf(ctx)?.let { s ->
        DeskButton("Open its start: ${s.nameWithoutExtension}", strong = true, tip = "Opens ${s.name}, the start this save takes its objectives from, to add or delete objectives there") { ObjectiveAddDelete.openStart(ctx) }
    }
}

/** The Objectives page's add / delete buttons: working in a start being edited, else greyed with the way there. */
@Composable
internal fun AddDeleteButtons(ctx: CmdContext, sel: List<CmdObjective>) {
    val why = ObjectiveAddDelete.refusal(ctx)
    DeskButton("Add objective…", enabled = why == null, tip = why ?: "A new objective: its class, owner, name and place") {
        val o = sel.firstOrNull()
        val size = ctx.world.sizeFt
        val n = o?.let { (it.simNorth.takeIf { v -> v != 0.0 } ?: ((it.y + 0.5) * CELL)) } ?: (size / 2)
        val e = o?.let { (it.simEast.takeIf { v -> v != 0.0 } ?: ((it.x + 0.5) * CELL)) + 3280.84 } ?: (size / 2)
        MoreDialogs.req = MoreDialogs.AddObjective(n, e, o)
    }
    DeskButton(if (sel.size > 1) "Delete ${sel.size}" else "Delete objective", enabled = why == null && sel.isNotEmpty(), danger = true, tip = why ?: "Delete the selected objectives (asks first)") { askDelete(ctx, sel) }
}

/** Under the Objectives page's strip where objectives are not added or deleted: the reason on one line and the way there. */
@Composable
internal fun AddDeleteReasonStrip(ctx: CmdContext, trailing: @Composable () -> Unit = {}) {
    val why = ObjectiveAddDelete.refusal(ctx) ?: return
    com.bmscompanion.desktop.ui.command.Strip {
        Text("Add / delete objectives: $why", color = Hud.TextDim, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp))
        AddDeleteWay(ctx)
        androidx.compose.foundation.layout.Box(Modifier.weight(0.001f))
        trailing()
    }
}

/**
 * Exports the open file's units (or objectives) as CSV where the pilot picks in Windows' own Save dialog (Documents to
 * begin with; never the Falcon BMS folder).
 */
internal fun exportCsv(ctx: CmdContext, objectives: Boolean) {
    val what = if (objectives) "objectives" else "units"
    val req = com.bmscompanion.app.data.FileRequest(
        com.bmscompanion.app.data.FileMode.SAVE, "Export the $what as CSV",
        java.io.File(System.getProperty("user.home") ?: ".", "Documents").path, "${ctx.doc.file.nameWithoutExtension} $what.csv",
        listOf(com.bmscompanion.app.data.FileFilter("CSV (*.csv)", "csv")), 0, "csv",
    )
    CoroutineScope(Dispatchers.Main).launch {
        when (val a = com.bmscompanion.desktop.PcFileDialogs.show(req)) {
            is com.bmscompanion.app.data.NativeAnswer.Picked -> {
                val text = if (objectives) com.bmscompanion.desktop.bridge.command.CommandExport.objectivesCsv(ctx.core) else com.bmscompanion.desktop.bridge.command.CommandExport.unitsCsv(ctx.core)
                val why = com.bmscompanion.desktop.bridge.command.CommandExport.write(java.io.File(a.path), text)
                if (why != null) CommandSession.error = why else ctx.say("The $what were written to ${a.path}.")
            }
            is com.bmscompanion.app.data.NativeAnswer.Failed -> CommandSession.error = "The Save dialog could not be shown: ${a.why}"
            else -> Unit
        }
    }
}

/** The unit flags by bit (the low ones for every unit; from 0x100000 an air unit's and a ground unit's differ). */
private val UNIT_FLAGS = listOf(
    0x1 to "Dead", 0x4 to "Assigned", 0x8 to "Ordered", 0x10 to "No planning", 0x20 to "Parent", 0x40 to "Engaged", 0x80 to "Human controlled",
    0x100 to "Scripted", 0x200 to "Commando", 0x400 to "Moving", 0x800 to "Refused", 0x1000 to "Has ECM", 0x2000 to "Cargo", 0x4000 to "Combat",
    0x8000 to "Broken", 0x10000 to "Losses", 0x20000 to "Inactive", 0x40000 to "Fragmented", 0x80000 to "Unload CFT",
)
private val AIR_HIGH = listOf(0x100000 to "Final", 0x200000 to "Has pilots", 0x400000 to "Diverted", 0x800000 to "Fired", 0x1000000 to "Locked", 0x2000000 to "IA kill", 0x4000000 to "No abort")
private val GROUND_HIGH = listOf(0x100000 to "Targeted", 0x200000 to "Retreating", 0x400000 to "Detached", 0x800000 to "Supported", 0x1000000 to "Temporary destination")

/**
 * A unit's raw record (folded): CampId (kept unique among objectives and units), owner's spotting by team, base flags
 * (emitting, jammed), every unit flag, destination, losses, tactic and reinforcement — what the designer reaches for
 * when BMS misbehaves. Every change is one undoable edit.
 */
@Composable
internal fun AdvancedBlock(ctx: CmdContext, u: CmdUnit) {
    val doc = ctx.core
    val name = AirOps.nameOf(doc, u)
    com.bmscompanion.desktop.ui.command.Fold("Advanced (the raw record)", "u_advanced", startOpen = false) {
        NumLine("CampId", u.campId.toLong(), 1L..65535L, tip = "Kept unique among the objectives and units") { v ->
            val n = v.toInt()
            if (n != u.campId && (doc.units.any { it !== u && it.campId == n } || doc.objectives.any { it.campId == n })) "CampId $n is taken."
            else ctx.apply(AirOps.field(doc, u, "CampId of $name") { campId = n })
        }
        NumLine("Name number", u.nameId.toLong(), 0L..65535L, tip = "The number in its name (\"36th\")") { v -> ctx.apply(AirOps.field(doc, u, "Name number of $name") { nameId = v.toInt() }) }
        Line("Seen by", tip = "The teams that have spotted it") {
            com.bmscompanion.desktop.ui.command.MiniWrap {
                ctx.world.teams.forEach { t ->
                    val bit = 1 shl t.n
                    com.bmscompanion.desktop.ui.command.MiniChip(t.name.take(10), u.spotted and bit != 0, com.bmscompanion.desktop.ui.command.teamInk(t.colour), lead = { com.bmscompanion.desktop.ui.command.TeamFlagLead(t.name, t.colour) }) {
                        ctx.apply(AirOps.field(doc, u, "Spotting of $name") { spotted = spotted xor bit })
                    }
                }
            }
        }
        Line("Base flags") {
            com.bmscompanion.desktop.ui.command.MiniWrap {
                listOf(0x1 to "Emitting", 0x4 to "Jammed").forEach { (bit, w) ->
                    com.bmscompanion.desktop.ui.command.MiniChip(w, u.baseFlags and bit != 0) { ctx.apply(AirOps.field(doc, u, "$w of $name") { baseFlags = baseFlags xor bit }) }
                }
            }
        }
        val high = if (u is CmdFlight || u is CmdPackage) AIR_HIGH else if (u is CmdSquadron) emptyList() else GROUND_HIGH
        Line("Unit flags", tip = "0x${u.unitFlags.toString(16)}") {
            com.bmscompanion.desktop.ui.command.MiniWrap {
                (UNIT_FLAGS + high).forEach { (bit, w) ->
                    com.bmscompanion.desktop.ui.command.MiniChip(w, u.unitFlags and bit != 0) { ctx.apply(AirOps.field(doc, u, "$w of $name ${if (u.unitFlags and bit != 0) "off" else "on"}") { unitFlags = unitFlags xor bit }) }
                }
            }
        }
        Line("Destination (x, y)") {
            NumBox(u.destX.toLong(), 0L..4000L, width = 60.dp) { v -> ctx.apply(AirOps.field(doc, u, "Destination of $name") { destX = v.toInt() }) }
            Text("  ", fontSize = 11.sp)
            NumBox(u.destY.toLong(), 0L..4000L, width = 60.dp) { v -> ctx.apply(AirOps.field(doc, u, "Destination of $name") { destY = v.toInt() }) }
        }
        NumLine("Losses", u.losses.toLong(), 0L..255L) { v -> ctx.apply(AirOps.field(doc, u, "Losses of $name") { losses = v.toInt() }) }
        NumLine("Tactic", u.tactic.toLong(), 0L..255L, tip = "BMS's tactic number (Falcon4.tt)") { v -> ctx.apply(AirOps.field(doc, u, "Tactic of $name") { tactic = v.toInt() }) }
        NumLine("Reinforcement", u.reinforcement.toLong(), 0L..255L, tip = "The day it joins (0 = from the start)") { v -> ctx.apply(AirOps.field(doc, u, "Reinforcement of $name") { reinforcement = v.toInt() }) }
        Hint("Id ${u.id} · target ${u.targetId} · cargo ${u.cargoId} · moved ${u.moved} · last check ${CampaignArchive.clock(u.lastCheck)}")
    }
}

// ------------------------------------------------------------------ the dialogs

object MoreDialogs {
    sealed class Req
    class Buildings(val id: VuId) : Req()
    /** an airbase (objective) or a carrier (task force) */
    class Schedule(val id: VuId) : Req()
    class AddObjective(val north: Double, val east: Double, val template: CmdObjective?) : Req()
    object ObjectivesBulk : Req()

    var req by mutableStateOf<Req?>(null)
    fun close() { req = null }

    @Composable
    fun Host(ctx: CmdContext) {
        EdDialogHost()
        when (val r = req) {
            is Buildings -> BuildingsDialog(ctx, r)
            is Schedule -> ScheduleDialog(ctx, r)
            is AddObjective -> AddObjectiveDialog(ctx, r)
            ObjectivesBulk -> ObjectivesBulkDialog(ctx)
            null -> Unit
        }
    }

    @Composable
    private fun ObjectivesBulkDialog(ctx: CmdContext) {
        @Suppress("UNUSED_VARIABLE") val v = watchEdits()
        val core = ctx.core
        val mode = ctx.objMode()
        val full = mode == ObjectiveOps.Mode.FULL
        val types = remember(core) { core.objectives.map { ObjectiveOps.typeOf(core, it) }.distinct().sorted().map { it to (core.string(500 + it)?.trim()?.ifEmpty { null } ?: "Type $it") } }
        var type by remember { mutableStateOf<Int?>(null) }
        var team by remember { mutableStateOf<Int?>(null) }
        var supply by remember { mutableStateOf(100L) }
        var fuel by remember { mutableStateOf(100L) }
        val fullWhy = if (full) null else ObjectiveAddDelete.refusal(ctx) ?: ObjectiveOps.modeWhy(core, ctx.editingStart())
        fun run(e: com.bmscompanion.desktop.bridge.command.CommandEdit) { ctx.edit(e) }
        Frame("Objectives: bulk operations", width = 600) {
            Text(ObjectiveOps.modeWhy(core, ctx.editingStart()), color = if (mode == ObjectiveOps.Mode.NONE) Hud.Amber else Hud.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
            EdHeading("Supply and fuel by kind")
            ChoiceLine<Int?>("Kind", type, listOf<Pair<Int?, String>>(null to "Every kind") + types) { type = it }
            ChoiceLine<Int?>("Owner", team, listOf<Pair<Int?, String>>(null to "Every team") + ctx.teamOptions(), lead = { ctx.teamFlag(it) }) { team = it }
            NumLine("Supply", supply, 0L..255L) { supply = it; null }
            NumLine("Fuel", fuel, 0L..255L) { fuel = it; null }
            Buttons {
                SmallButton("Set both", enabled = mode != ObjectiveOps.Mode.NONE) { run(com.bmscompanion.desktop.bridge.command.ObjectiveBulk.supplyAndFuel(core, type, team, supply.toInt(), fuel.toInt(), full)) }
                SmallButton("Supply only", enabled = mode != ObjectiveOps.Mode.NONE) { run(com.bmscompanion.desktop.bridge.command.ObjectiveBulk.supplyAndFuel(core, type, team, supply.toInt(), null, full)) }
                SmallButton("Fuel only", enabled = mode != ObjectiveOps.Mode.NONE) { run(com.bmscompanion.desktop.bridge.command.ObjectiveBulk.supplyAndFuel(core, type, team, null, fuel.toInt(), full)) }
            }
            EdHeading("The start's records")
            if (fullWhy != null) EdNote(fullWhy, Hud.TextDim)
            Buttons {
                SmallButton("First owner = owner", enabled = full, tip = "Every objective's first owner becomes who holds it now") { run(com.bmscompanion.desktop.bridge.command.ObjectiveBulk.firstOwnerToCurrent(core)) }
                SmallButton("Front lines from links", enabled = full, tip = "Front, second and third line marked again from the links and the teams' stances (Manual ones kept)") { run(com.bmscompanion.desktop.bridge.command.ObjectiveBulk.frontLines(core)) }
                SmallButton("Parents again", enabled = full, tip = "Cities and towns under the nearest main city of their side, villages under the nearest town, the rest under the nearest place") { run(com.bmscompanion.desktop.bridge.command.ObjectiveBulk.resetParents(core)) }
            }
            Buttons {
                SmallButton("Radars emitting", enabled = full, tip = "Every radar objective, airbase and airstrip emitting") { run(com.bmscompanion.desktop.bridge.command.ObjectiveBulk.radarsEmitting(core)) }
                SmallButton("Reset every objective", enabled = full, danger = true, tip = "No spotting, losses or repair time, base flags clear, every building intact: as a fresh start") { run(com.bmscompanion.desktop.bridge.command.ObjectiveBulk.resetObjectives(core)) }
            }
            if (!full) AddDeleteWay(ctx)
            EdNote("Each is one edit: Undo takes it back. Links: Auto-link, Recalculate costs and Check links are on the page's strip and the Checks page.")
        }
    }

    @Composable
    private fun Frame(title: String, width: Int = 560, buttons: @Composable () -> Unit = { DeskButton("Close", strong = true) { close() } }, content: @Composable () -> Unit) {
        AlertDialog(
            onDismissRequest = { close() },
            confirmButton = buttons,
            title = { Text(title, color = Hud.Text, fontSize = 15.sp) },
            text = { Column(Modifier.width(width.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) { content() } },
            containerColor = Hud.Surface,
        )
    }

    @Composable
    private fun BuildingsDialog(ctx: CmdContext, r: Buildings) {
        @Suppress("UNUSED_VARIABLE") val v = watchEdits()
        val o = ctx.core.objective(r.id) ?: run { close(); return }
        val mode = ctx.objMode()
        Frame("Buildings of ${ObjectiveOps.displayName(ctx.core, o)}") {
            Text(ObjectiveOps.modeWhy(ctx.core, ctx.editingStart()), color = if (mode == ObjectiveOps.Mode.NONE) Hud.Amber else Hud.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
            Buildings(ctx, o, mode == ObjectiveOps.Mode.FULL, mode != ObjectiveOps.Mode.NONE)
        }
    }

    /** One flight's line in the schedule. */
    private class Row(val f: CmdFlight, val t: Long?, val cells: List<String>)

    @Composable
    private fun ScheduleDialog(ctx: CmdContext, r: Schedule) {
        @Suppress("UNUSED_VARIABLE") val v = watchEdits()
        val core = ctx.core
        val base: Any = core.objective(r.id) ?: core.unit(r.id) ?: run { close(); return }
        val (bx, by) = when (base) { is CmdObjective -> GroundOps.objCell(base); is CmdUnit -> base.x to base.y; else -> 0 to 0 }
        val name = ctx.nameOfAny(base)
        val squadrons = core.squadrons.filter { it.airbaseId == r.id }
        fun near(x: Int, y: Int, cells: Int = 2) = hypot((x - bx).toDouble(), (y - by).toDouble()) <= cells
        fun where(f: CmdFlight, land: Boolean): String {
            val w = (if (land) f.waypoints.lastOrNull { it.action == AirOps.ACTION_LAND } else f.waypoints.firstOrNull()) ?: return ""
            if (near(w.gridX, w.gridY)) return "here"
            return core.near(w.gridX, w.gridY, 4.0, units = true).firstOrNull()?.first?.let { ctx.nameOfAny(it) } ?: "cell ${w.gridX}, ${w.gridY}"
        }
        val departures = core.flights.filter { f -> f.squadronId in squadrons.map { it.id } || f.waypoints.firstOrNull()?.let { near(it.gridX, it.gridY) } == true }
        val arrivals = core.flights.filter { f -> f !in departures && f.waypoints.lastOrNull { it.action == AirOps.ACTION_LAND }?.let { near(it.gridX, it.gridY) } == true }
        fun rowsOf(list: List<CmdFlight>, land: Boolean) = list.map { f ->
            val t = if (land) FlightTimes.landing(f) else FlightTimes.takeoff(f)
            Row(f, t, listOf(FlightTimes.hhmm(t), UnitNames.full(core, f), FlightTimes.mission(core, f.mission),
                core.packageOf(f)?.let { UnitNames.short(core, it) }.orEmpty(), if (land) where(f, false) else where(f, true), core.squadronOf(f)?.let { UnitNames.short(core, it) }.orEmpty()))
        }.sortedBy { it.t ?: Long.MAX_VALUE }
        val dep = rowsOf(departures, false)
        val arr = rowsOf(arrivals, true)
        val heads = listOf("Time" to 52.dp, "Flight" to 190.dp, "Mission" to 96.dp, "Package" to 92.dp, "To / from" to 120.dp, "Squadron" to 110.dp)
        Frame("Airport schedule · $name", width = 700) {
            Text("Clock ${CampaignArchive.clock(core.cmp?.currentTime ?: 0)} · ${squadrons.size} squadron${if (squadrons.size == 1) "" else "s"} based here" +
                if (squadrons.isNotEmpty()) ": " + squadrons.joinToString { UnitNames.full(core, it) } else "", color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
            EdHeading("Departures (${dep.size})")
            if (dep.isEmpty()) EdNote("No flight takes off here.") else MiniTable(heads, dep.map { it.cells }, maxHeight = 220.dp) { i -> ctx.focus(refOf(dep[i].f)); close() }
            EdHeading("Arrivals from elsewhere (${arr.size})")
            if (arr.isEmpty()) EdNote("No flight from another field lands here.") else MiniTable(heads, arr.map { it.cells }, maxHeight = 160.dp) { i -> ctx.focus(refOf(arr[i].f)); close() }
            EdNote("A click on a flight selects it. Times are the take-off (departures) and the landing (arrivals), on the campaign's clock.")
        }
    }

    @Composable
    private fun AddObjectiveDialog(ctx: CmdContext, r: AddObjective) {
        val core = ctx.core
        val classes = remember(core) { objectiveClasses(core) }
        val nearest = remember(r) { core.objectives.minByOrNull { hypot(it.y * CELL - r.north, it.x * CELL - r.east) } }
        var type by remember(r) { mutableStateOf(r.template?.type ?: nearest?.type ?: classes.firstOrNull()?.first ?: 0) }
        var owner by remember(r) { mutableStateOf(r.template?.let { core.ownerNow(it) } ?: nearest?.let { core.ownerNow(it) } ?: CommandSession.ownerTeam) }
        var name by remember(r) { mutableStateOf("") }
        var north by remember(r) { mutableStateOf(r.north) }
        var east by remember(r) { mutableStateOf(r.east) }
        val why = ObjectiveAddDelete.refusal(ctx)
        Frame(
            "Add an objective",
            buttons = {
                DeskButton("Add it", strong = true, enabled = why == null && type != 0) {
                    close()
                    val msg = addObjective(ctx, type, owner, name, north, east)
                    if (msg != null) CommandSession.error = msg
                }
                DeskButton("Cancel") { close() }
            },
        ) {
            if (why != null) Text(why, color = Hud.Amber, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
            ChoiceLine("Class", type, classes, tip = "What it is, and which buildings it has") { type = it }
            ChoiceLine("Owner", owner, ctx.teamOptions(), lead = { ctx.teamFlag(it) }, tip = "Its owner and first owner (the nearest objective's to begin with)") { owner = it }
            TextLine("Name", name, tip = "Empty: the class's name and the new CampId") { name = it; null }
            NumLine("North", north.toLong(), 0L..Long.MAX_VALUE, unit = "ft") { north = it.toDouble(); null }
            NumLine("East", east.toLong(), 0L..Long.MAX_VALUE, unit = "ft") { east = it.toDouble(); null }
            Hint("A new id and CampId, its buildings intact, no links (Link + on the map, or Auto-link), no parent. " + savesLine(ctx))
        }
    }

    /** Adds an objective of class [type] at (north, east): a copy of one of that class when the file has one. */
    internal fun addObjective(ctx: CmdContext, type: Int, owner: Int, name: String, north: Double, east: Double): String? {
        val core = ctx.core
        ObjectiveAddDelete.refusal(ctx)?.let { return it }
        val template = core.obj?.objectives?.firstOrNull { it.type == type } ?: core.obj?.objectives?.firstOrNull() ?: return "This file has no objectives of its own."
        val (e, o) = ObjectiveOps.add(core, template, north, east, name.trim())
        if (e == null || o == null) return "This file has no objectives of its own."
        if (o.type != type) {
            o.type = type; o.entityType = type
            val n = core.tables.features(core.names?.ct(type)?.entityIdx ?: -1).size
            o.fstatus = ByteArray(n)
            o.hasRadar = 0
        }
        o.owner = owner; o.firstOwner = owner
        if (name.isBlank()) o.name = o.name.copy().also { it.text = ((core.string(500 + (core.names?.ct(type)?.type ?: 0))?.trim().orEmpty()) + " ${o.campId}").trim() }
        val why = core.edits.run(e)
        if (why == null) { ctx.selection.set(objRef(o)); CommandSession.done(e.label) }
        ctx.changed()
        return why
    }
}

// ------------------------------------------------------------------ objectives

object ViewBuildings : CmdAction {
    override val label = "View buildings…"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ctx.objective(ref) != null
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        ctx.objective(ref)?.let { MoreDialogs.req = MoreDialogs.Buildings(it.id) }
    }
}

object AirportSchedule : CmdAction {
    override val label = "Airport schedule…"
    private fun baseOf(ctx: CmdContext, ref: CmdRef?): VuId? {
        ctx.objective(ref)?.let { return if (ctx.isAirfield(it)) it.id else null }
        return when (val u = ctx.unitRef(ref)) {
            is CmdSquadron -> u.airbaseId.takeUnless { it.isNone }
            is CmdTaskForce -> if (ctx.isCarrier(u)) u.id else null
            else -> null
        }
    }
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = baseOf(ctx, ref) != null
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        baseOf(ctx, ref)?.let { MoreDialogs.req = MoreDialogs.Schedule(it) }
    }
}

private fun CmdContext.selectedObjective(ref: CmdRef?): CmdObjective? =
    selection.primary?.takeIf { it != ref }?.let { objective(it) }

object LinkToSelected : CmdAction {
    override val label = "Link to the selected objective"
    override fun accepts(ctx: CmdContext, ref: CmdRef?): Boolean {
        val o = ctx.objective(ref) ?: return false
        val s = ctx.selectedObjective(ref) ?: return false
        return o.links.none { it.id == s.id }
    }
    override fun refusal(ctx: CmdContext, ref: CmdRef?): String? =
        if (ctx.objMode() != ObjectiveOps.Mode.FULL) "Links are edited only in a campaign start (Edit BMS's campaign start); a save takes them from its start." else null
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val s = ctx.selection.primary ?: return
        ref ?: return
        val why = CommandSlots.mapEdits?.addLink(s, ref) ?: if (CommandSlots.mapEdits == null) "Links are not edited here." else null
        if (why == null) CommandSession.done("Link ${ctx.world.nameOf(s)} and ${ctx.world.nameOf(ref)}") else ctx.say(why)
        ctx.changed()
    }
}

object UnlinkFromSelected : CmdAction {
    override val label = "Unlink from the selected objective"
    override fun accepts(ctx: CmdContext, ref: CmdRef?): Boolean {
        val o = ctx.objective(ref) ?: return false
        val s = ctx.selectedObjective(ref) ?: return false
        return o.links.any { it.id == s.id } || s.links.any { it.id == o.id }
    }
    override fun refusal(ctx: CmdContext, ref: CmdRef?): String? = LinkToSelected.refusal(ctx, ref)
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val s = ctx.selection.primary ?: return
        ref ?: return
        val why = CommandSlots.mapEdits?.removeLink(s, ref) ?: if (CommandSlots.mapEdits == null) "Links are not edited here." else null
        if (why == null) CommandSession.done("Unlink ${ctx.world.nameOf(s)} and ${ctx.world.nameOf(ref)}") else ctx.say(why)
        ctx.changed()
    }
}

object RemoveAllLinks : CmdAction {
    override val label = "Remove all its links"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ctx.objective(ref)?.links?.isNotEmpty() == true
    override fun refusal(ctx: CmdContext, ref: CmdRef?): String? = LinkToSelected.refusal(ctx, ref)
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val objs = (if (ref != null && ref in ctx.selection) ctx.selection.refs else listOfNotNull(ref)).mapNotNull { ctx.objective(it) }
        ctx.edit(ObjectiveOps.removeLinks(ctx.core, objs))
    }
}

object DeleteObjective : CmdAction {
    override val label = "Delete objective…"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ctx.objective(ref) != null
    override fun refusal(ctx: CmdContext, ref: CmdRef?): String? = ObjectiveAddDelete.refusal(ctx)
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val objs = (if (ref != null && ref in ctx.selection) ctx.selection.refs else listOfNotNull(ref)).mapNotNull { ctx.objective(it) }
        if (objs.isNotEmpty()) askDelete(ctx, objs)
    }
}

/** In a save: opens the start it takes its objectives from (where they are added and deleted). */
object OpenStartForObjectives : CmdAction {
    override val label: String get() = "Open its start to add or delete objectives"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = (ref == null || ref.kind == CmdKind.OBJECTIVE) && (ObjectiveAddDelete.startOf(ctx) != null || ObjectiveAddDelete.canEditStart(ctx))
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) =
        if (ObjectiveAddDelete.canEditStart(ctx)) ObjectiveAddDelete.askEditStart(ctx) else ObjectiveAddDelete.openStart(ctx)
}

// ------------------------------------------------------------------ units

/** A table row's clone: the copy stands where the unit stands (the map's Clone here puts it where it was clicked). */
object CloneInPlace : CmdAction {
    override val label = "Clone"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ctx.unitRef(ref) != null
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val u = ctx.unitRef(ref) ?: return
        ctx.applyAndSelectNew(UnitLife.clone(ctx.core, u), u.kind)
    }
}

object SelectPackage : CmdAction {
    override val label = "Select its package"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = (ctx.unitRef(ref) as? CmdFlight)?.let { ctx.core.packageOf(it) } != null
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        (ctx.unitRef(ref) as? CmdFlight)?.let { ctx.core.packageOf(it) }?.let { ctx.focus(refOf(it)) }
    }
}

object SelectSquadron : CmdAction {
    override val label = "Select its squadron"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = (ctx.unitRef(ref) as? CmdFlight)?.let { ctx.core.squadronOf(it) } != null
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        (ctx.unitRef(ref) as? CmdFlight)?.let { ctx.core.squadronOf(it) }?.let { ctx.focus(refOf(it)) }
    }
}

/** A flight of the package's first flight's squadron, taking off when that flight does. */
object AddFlightToPackage : CmdAction {
    override val label = "Add a flight to this package"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ctx.unitRef(ref) is CmdPackage
    override fun refusal(ctx: CmdContext, ref: CmdRef?): String? {
        val p = ctx.unitRef(ref) as? CmdPackage ?: return null
        val first = ctx.core.flightsOf(p).firstOrNull() ?: return "The package has no flight to take a squadron from."
        return when {
            p.elements.size >= 5 -> "A package has at most five flights."
            ctx.core.squadronOf(first) == null -> "Its first flight has no squadron."
            else -> null
        }
    }
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val p = ctx.unitRef(ref) as? CmdPackage ?: return
        val first = ctx.core.flightsOf(p).firstOrNull() ?: return
        val sq = ctx.core.squadronOf(first) ?: return
        ctx.applyAndSelectNew(UnitLife.addFlight(ctx.core, sq, 2, first.mission, FlightTimes.takeoff(first) ?: (ctx.core.cmp?.currentTime ?: 0), p), UnitKind.FLIGHT)
    }
}

/** Empty ground: the selected task force, battalion or brigade moved to the point clicked. */
object MoveSelectedHere : CmdAction {
    override val label = "Move the selected unit here"
    override val mapOnly = true
    private fun sel(ctx: CmdContext): CmdUnit? = ctx.selection.primary?.let { ctx.unitRef(it) }?.takeIf { it is CmdTaskForce || it is CmdBattalion || it is CmdBrigade }
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ref == null && sel(ctx) != null
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val u = sel(ctx) ?: return
        ctx.apply(GroundOps.moveUnit(ctx.core, u, cell(east), cell(north)))
    }
}
