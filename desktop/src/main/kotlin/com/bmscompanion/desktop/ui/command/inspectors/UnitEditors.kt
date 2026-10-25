package com.bmscompanion.desktop.ui.command.inspectors

import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.command.AirOps
import com.bmscompanion.desktop.bridge.command.CmdBrigade
import com.bmscompanion.desktop.bridge.command.CmdGroundUnit
import com.bmscompanion.desktop.bridge.command.CmdObjective
import com.bmscompanion.desktop.bridge.command.CmdSquadron
import com.bmscompanion.desktop.bridge.command.CmdTaskForce
import com.bmscompanion.desktop.bridge.command.CmdUnit
import com.bmscompanion.desktop.bridge.command.CommandEdit
import com.bmscompanion.desktop.bridge.command.Edits
import com.bmscompanion.desktop.bridge.command.GroundOps
import com.bmscompanion.desktop.bridge.command.UnitChecks
import com.bmscompanion.desktop.bridge.command.UnitClasses
import com.bmscompanion.desktop.bridge.command.UnitKind
import com.bmscompanion.desktop.bridge.command.UnitLife
import com.bmscompanion.desktop.ui.command.CmdAction
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdMapEdits
import com.bmscompanion.desktop.ui.command.CmdPage
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.CommandSlots
import com.bmscompanion.desktop.ui.command.tables.AtoTable
import com.bmscompanion.desktop.ui.command.tables.UnitDialogs
import com.bmscompanion.desktop.ui.command.tables.UnitsTable
import kotlin.math.floor
import kotlin.math.hypot

/**
 * The unit editor put into the section's slots (one call from `CommandRegistry.install`): the inspectors of flights
 * (and their waypoints), packages, squadrons, battalions, brigades and task forces; the Units and ATO pages' tables;
 * the map's menu lines for units (add here, clone here, delete, a waypoint here, base / place / send the selection
 * here); the map's Move and Clone tools on units ([UnitMapEdits]); the add and bulk dialogs; and the unit checks on
 * the Checks page.
 */
object UnitEditors {
    fun install() {
        CommandSlots.inspectors += FlightInspector
        CommandSlots.inspectors += PackageInspector
        CommandSlots.inspectors += SquadronInspector
        CommandSlots.inspectors += BattalionInspector
        CommandSlots.inspectors += BrigadeInspector
        CommandSlots.inspectors += TaskForceInspector
        CommandSlots.tables[CmdPage.UNITS] = UnitsTable
        CommandSlots.tables[CmdPage.ATO] = AtoTable
        CommandSlots.actions += listOf(AddSquadronHere, AddBattalionHere, AddBrigadeHere, AddTaskForceHere, AddFlightTo, AddWaypointHere,
            BaseHere, PlaceHere, SendHere, CloneHere, DeleteUnit, DeleteWithChildren)
        CommandSlots.mapEdits = UnitMapEdits(CommandSlots.mapEdits)
        CommandSlots.overlays += { ctx -> UnitDialogs.Host(ctx) }
        UnitChecks.install()
    }
}

private const val CELL = CampaignArchive.FEET_PER_CELL
private fun cell(feet: Double) = floor(feet / CELL).toInt()
private fun CmdContext.unitAt(ref: CmdRef?): CmdUnit? = ref?.takeIf { it.kind in UNIT_KINDS }?.let { unitOf(it) }
private val UNIT_KINDS = setOf(CmdKind.FLIGHT, CmdKind.PACKAGE, CmdKind.SQUADRON, CmdKind.BATTALION, CmdKind.BRIGADE, CmdKind.TASKFORCE)
private fun CmdContext.objAt(ref: CmdRef?): CmdObjective? = ref?.takeIf { it.kind == CmdKind.OBJECTIVE }?.let { core.objective(CampaignArchive.VuId.parse(it.id)) }
private fun CmdContext.selected(): CmdUnit? = selection.primary?.let { r -> if (r.kind == CmdKind.WAYPOINT) unitOf(r) else unitAt(r) }
private fun CmdContext.isAirfield(o: CmdObjective) = UnitClasses.of(core).clsOfType(o.type)?.type in setOf(1, 2)
private fun CmdContext.isCarrier(u: CmdUnit?) = u is CmdTaskForce && UnitClasses.of(core).clsOfType(u.type)?.sub == 3
private fun CmdContext.ownerHere(o: CmdObjective?) = o?.let { core.ownerNow(it) } ?: CommandSession.ownerTeam

/**
 * The map's edits on units: Move takes a squadron to the airbase it is dropped on (with its flights), a task force
 * with its carrier's squadrons, a ground unit with its destination, and a waypoint with its route timed again; Clone
 * puts a copy where the map was clicked. Objectives, links and owners go to [inner] (squadrons keep their header row).
 */
class UnitMapEdits(private val inner: CmdMapEdits?) : CmdMapEdits {
    private val doc get() = CommandSession.doc
    private fun unit(r: CmdRef): CmdUnit? = doc?.core?.unit(CampaignArchive.VuId.parse(r.id))
    // (null is "done": an elvis on the edit log's answer would turn every success into "No file is open.")
    private fun run(e: CommandEdit): String? { val d = doc ?: return "No file is open."; return d.core.edits.run(e) }

    override fun move(ref: CmdRef, north: Double, east: Double): String? {
        val d = doc ?: return "No file is open."
        if (ref.kind == CmdKind.WAYPOINT) {
            val u = unit(ref) ?: return inner?.move(ref, north, east)
            return run(AirOps.moveWaypoint(d.core, u, ref.sub, cell(east), cell(north), UnitPrefs.retime))
        }
        if (ref.kind in UNIT_KINDS) {
            val u = unit(ref) ?: return "That unit is not in the file."
            return run(GroundOps.moveUnit(d.core, u, cell(east), cell(north)))
        }
        return if (inner == null) "That cannot be moved." else inner.move(ref, north, east)
    }

    override fun clone(ref: CmdRef, north: Double, east: Double): String? {
        val d = doc ?: return "No file is open."
        val u = if (ref.kind in UNIT_KINDS) unit(ref) else null
        if (u == null) return if (inner == null) "Only units are cloned on the map." else inner.clone(ref, north, east)
        val before = d.core.units.mapTo(HashSet()) { it.id }
        val why = run(UnitLife.clone(d.core, u, cell(east) to cell(north)))
        if (why == null) d.core.units.firstOrNull { it.kind == u.kind && it.id !in before }?.let { CommandSession.selection.set(refOf(it)) }
        return why
    }

    override fun addLink(a: CmdRef, b: CmdRef): String? = if (inner == null) "Links are not edited here." else inner.addLink(a, b)
    override fun removeLink(a: CmdRef, b: CmdRef): String? = if (inner == null) "Links are not edited here." else inner.removeLink(a, b)

    override fun setOwner(refs: List<CmdRef>, team: Int): String? {
        val d = doc ?: return "No file is open."
        val sqs = refs.filter { it.kind == CmdKind.SQUADRON }.mapNotNull { unit(it) as? CmdSquadron }
        val rest = refs.filter { it.kind != CmdKind.SQUADRON }
        var why: String? = null
        if (sqs.isNotEmpty()) why = run(if (sqs.size == 1) GroundOps.setSquadronOwner(d.core, sqs[0], team, withFlights = true) else Edits.group("Owner of ${sqs.size} squadrons", sqs.map { GroundOps.setSquadronOwner(d.core, it, team, true) }))
        // (null from the inner edits is "done": the first refusal of either is the answer)
        if (rest.isNotEmpty()) (inner?.setOwner(rest, team) ?: if (inner == null) "Owners are not set here." else null)?.let { if (why == null) why = it }
        return why
    }
}

// ------------------------------------------------------------------ the map's menu lines

object AddSquadronHere : CmdAction {
    override val label = "Add a squadron here…"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ctx.objAt(ref)?.let { ctx.isAirfield(it) } == true || ctx.isCarrier(ctx.unitAt(ref))
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val at: Any = ctx.objAt(ref) ?: ctx.unitAt(ref) ?: return
        val owner = (at as? CmdObjective)?.let { ctx.core.ownerNow(it) } ?: (at as CmdUnit).owner
        UnitDialogs.open(UnitDialogs.Add(UnitKind.SQUADRON, cell(east), cell(north), at, owner))
    }
}

object AddBattalionHere : CmdAction {
    override val label = "Add a battalion here…"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ref == null || ref.kind == CmdKind.OBJECTIVE
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val o = ctx.objAt(ref)
        UnitDialogs.open(UnitDialogs.Add(UnitKind.BATTALION, cell(east), cell(north), o, ctx.ownerHere(o)))
    }
}

object AddBrigadeHere : CmdAction {
    override val label = "Add a brigade here…"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ref == null || ref.kind == CmdKind.OBJECTIVE
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val o = ctx.objAt(ref)
        UnitDialogs.open(UnitDialogs.Add(UnitKind.BRIGADE, cell(east), cell(north), o, ctx.ownerHere(o)))
    }
}

object AddTaskForceHere : CmdAction {
    override val label = "Add a task force here…"
    // (empty water, or a port, a beach or the sea as an objective)
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ref == null || ctx.objAt(ref)?.let { UnitClasses.of(ctx.core).clsOfType(it.type)?.type in setOf(4, 19, 27) } == true
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val o = ctx.objAt(ref)
        UnitDialogs.open(UnitDialogs.Add(UnitKind.TASKFORCE, cell(east), cell(north), o, ctx.ownerHere(o)))
    }
}

object AddFlightTo : CmdAction {
    override val label = "Add a flight of this squadron"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ref?.kind == CmdKind.SQUADRON
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val sq = ctx.unitAt(ref) as? CmdSquadron ?: return
        val mission = ctx.core.flights.firstOrNull { it.squadronId == sq.id }?.mission ?: 0
        ctx.applyAndSelectNew(UnitLife.addFlight(ctx.core, sq, 2, mission, (ctx.core.cmp?.currentTime ?: 0L) + AirOps.HOUR_MS), UnitKind.FLIGHT)
    }
}

/** A waypoint where the map was clicked, in the leg of the selected unit's route nearest to it. */
object AddWaypointHere : CmdAction {
    override val label = "Add a waypoint here (selected route)"
    override val mapOnly = true
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ctx.selected()?.let { it.kind != UnitKind.PACKAGE && it.kind != UnitKind.SQUADRON } == true
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val u = ctx.selected() ?: return
        val gx = cell(east); val gy = cell(north)
        val ws = u.waypoints
        val after = when {
            ws.isEmpty() -> -1
            ws.size == 1 -> 0
            else -> (0 until ws.size - 1).minByOrNull { i -> hypot((ws[i].gridX + ws[i + 1].gridX) / 2.0 - gx, (ws[i].gridY + ws[i + 1].gridY) / 2.0 - gy) } ?: (ws.size - 1)
        }
        val atEnd = u.kind != UnitKind.FLIGHT && ws.isNotEmpty() && hypot((ws.last().gridX - gx).toDouble(), (ws.last().gridY - gy).toDouble()) < hypot((ws[after].gridX - gx).toDouble(), (ws[after].gridY - gy).toDouble()) && after == ws.size - 2
        val idx = if (atEnd) ws.size - 1 else after
        if (ctx.apply(AirOps.insertWaypoint(ctx.core, u, idx, gx to gy)) == null) ctx.selectWaypoint(u, idx + 1)
    }
}

object BaseHere : CmdAction {
    override val label = "Base the selected squadron here"
    override val mapOnly = true
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ctx.selected() is CmdSquadron && (ctx.objAt(ref)?.let { ctx.isAirfield(it) } == true || ctx.isCarrier(ctx.unitAt(ref)))
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val sq = ctx.selected() as? CmdSquadron ?: return
        val base: Any = ctx.objAt(ref) ?: ctx.unitAt(ref) ?: return
        ctx.apply(GroundOps.moveBase(ctx.core, sq, base))
    }
}

object PlaceHere : CmdAction {
    override val label = "Place the selected battalion or brigade here"
    override val mapOnly = true
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ctx.selected() is CmdGroundUnit && ctx.objAt(ref) != null
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val u = ctx.selected() as? CmdGroundUnit ?: return
        ctx.apply(GroundOps.placeAt(ctx.core, u, ctx.objAt(ref) ?: return, withElements = u is CmdBrigade))
    }
}

object SendHere : CmdAction {
    override val label = "Send the selected battalion or brigade here"
    override val mapOnly = true
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ctx.selected() is CmdGroundUnit && ctx.objAt(ref) != null
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val u = ctx.selected() as? CmdGroundUnit ?: return
        ctx.apply(GroundOps.setAobj(ctx.core, u, ctx.objAt(ref)))
    }
}

object CloneHere : CmdAction {
    override val label = "Clone here"
    override val mapOnly = true
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ctx.unitAt(ref) != null
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val u = ctx.unitAt(ref) ?: return
        ctx.applyAndSelectNew(UnitLife.clone(ctx.core, u, cell(east) to cell(north)), u.kind)
    }
}

object DeleteUnit : CmdAction {
    override val label = "Delete"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = ctx.unitAt(ref) != null
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val u = ctx.unitAt(ref) ?: return
        if (ctx.apply(UnitLife.delete(ctx.core, u)) == null && ref != null && ref in ctx.selection) ctx.selection.clear()
    }
}

object DeleteWithChildren : CmdAction {
    override val label = "Delete with its flights or battalions"
    override fun accepts(ctx: CmdContext, ref: CmdRef?) = when (val u = ctx.unitAt(ref)) {
        is CmdSquadron -> ctx.core.flights.any { it.squadronId == u.id }
        is CmdBrigade -> u.elements.isNotEmpty()
        else -> false
    }
    override fun run(ctx: CmdContext, ref: CmdRef?, north: Double, east: Double) {
        val u = ctx.unitAt(ref) ?: return
        if (ctx.apply(UnitLife.delete(ctx.core, u, withChildren = true)) == null) ctx.selection.clear()
    }
}
