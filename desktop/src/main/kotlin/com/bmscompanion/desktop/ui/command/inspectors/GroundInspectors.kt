package com.bmscompanion.desktop.ui.command.inspectors

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
import com.bmscompanion.desktop.bridge.command.CmdGroundUnit
import com.bmscompanion.desktop.bridge.command.CmdObjective
import com.bmscompanion.desktop.bridge.command.CmdTaskForce
import com.bmscompanion.desktop.bridge.command.CmdUnit
import com.bmscompanion.desktop.bridge.command.GroundOps
import com.bmscompanion.desktop.bridge.command.UnitClasses
import com.bmscompanion.desktop.bridge.command.UnitKind
import com.bmscompanion.desktop.ui.command.CheckLine
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdInspector
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.CommandSession
import com.bmscompanion.desktop.ui.command.Fold
import com.bmscompanion.desktop.ui.command.teamInk

// Battalions, brigades and task forces. Their objective, brigade and support links, positions and routes, rosters and
// classes, each an undoable edit with BMS's links kept in step (GroundOps).

@Composable
private fun GroundHead(ctx: CmdContext, u: CmdUnit, kind: String) {
    val team = ctx.world.team(u.owner)
    val cls = UnitClasses.of(ctx.core)
    InspectorHead(ctx.world.unit(u.id.toString())?.name ?: AirOps.nameOf(ctx.core, u), "$kind · ${cls.ucd(u)?.name ?: ""}",
        "${team?.name ?: "team ${u.owner}"} · cell ${cellText(u.x, u.y)} · id ${u.id} · CampId ${u.campId}", ink = team?.let { teamInk(it.colour) } ?: Hud.Text, flag = ctx.teamFlag(u.owner))
    IssuesBlock(ctx, u)
}

/**
 * Orders: BMS's orders and the assigned objective (the nearest objectives as choices, or any objective clicked with
 * the map's Assign tool), given as BMS gives them ([GroundOps.giveOrders]: ordered, sent there, the old route
 * cleared); a brigade's battalions take them too when ticked, else the brigade hands them out.
 * "Place it there" puts it on the objective; Halt stops it (and the other ground units selected) where it stands.
 */
@Composable
private fun OrdersBlock(ctx: CmdContext, u: CmdGroundUnit, withElements: Boolean) {
    val doc = ctx.core
    var toBattalions by remember(u.id) { mutableStateOf(true) }
    val near = remember(doc.revision, u.id) { doc.near(u.x, u.y, 40.0, units = false).take(40) }
    val current = doc.objective(u.aobj)
    val opts = listOf<Pair<CmdObjective?, String>>(null to "None") +
        (if (current != null && near.none { it.first === current }) listOf(current to GroundOps.objName(doc, current)) else emptyList()) +
        near.map { (o, d) -> (o as CmdObjective) to "${GroundOps.objName(doc, o)} · ${"%.0f".format(d)} nm" }
    val brigade = u is CmdBrigade
    ChoiceLine("Orders", u.orders, GroundOps.ORDERS.mapIndexed { i, s -> i to s }, tip = "BMS's unit orders; with an objective they are given as BMS gives them (the unit ordered, sent there, its old route cleared)") { v ->
        ctx.apply(if (current == null) AirOps.field(doc, u, "Orders of ${AirOps.nameOf(doc, u)}") { orders = v } else GroundOps.giveOrders(doc, u, v, current, brigade && toBattalions))
    }
    ChoiceLine("Objective", current, opts, tip = "The assigned objective: where its orders send it (the map draws it as an amber diamond and line). Any objective: the Assign tool") { o ->
        ctx.apply(if (o == null) GroundOps.setAobj(doc, u, null) else GroundOps.giveOrders(doc, u, u.orders, o, brigade && toBattalions))
    }
    if (brigade) Line("") { CheckLine("its battalions too (else the brigade hands out its own)", toBattalions) { toBattalions = it } }
    Buttons {
        SmallButton("Pick on the map", tip = "The Assign tool: click the objective it is ordered to (Shift + click adds more units)") {
            if (refOf(u) !in ctx.selection.refs) ctx.selection.set(refOf(u))
            CommandSession.tool = com.bmscompanion.desktop.ui.command.CmdTool.ASSIGN
        }
        if (current != null) {
            SmallButton("Place it there", tip = if (withElements) "The brigade and its battalions stand on the objective, their routes cleared" else "It stands on the objective, its route cleared") {
                ctx.apply(GroundOps.placeAt(doc, u, current, withElements))
            }
            SmallButton("Show", tip = "Select the objective") { ctx.focus(objRef(current)) }
        }
    }
    val sel = ctx.groundUnits(ctx.selection.refs).takeIf { l -> l.size > 1 && l.any { it === u } } ?: listOf(u)
    Buttons {
        SmallButton(if (sel.size > 1) "Halt the ${sel.size} selected — clear routes" else "Halt — clear the route", danger = true,
            tip = "It stops where it stands at once: route cleared, destination its own cell, not moving${if (brigade) "; its battalions halt too" else ""}. Orders stay; set NoPlan to keep BMS's HQ from moving it on") {
            ctx.apply(GroundOps.halt(doc, sel))
        }
    }
    if (current != null && u is CmdBattalion) GroundOps.siteShortfall(doc, u.type, current)?.let { Text(it, color = Hud.Amber, fontSize = 11.sp, modifier = Modifier) }
    if (!GroundOps.setByHq(u)) Hint("Not set by HQ: BMS's headquarters leaves these orders as they are.")
    NumLine("Division", u.division.toLong(), 0L..9999L, tip = "Units of one team with the same division number make a division (BMS forms it)") { v -> ctx.apply(AirOps.field(doc, u, "Division of ${AirOps.nameOf(doc, u)}") { division = v.toInt() }) }
}

/** NoPlan, Set by HQ and the temporary destination of a battalion or a brigade, each with a line on what it does. */
@Composable
private fun PlanningLines(ctx: CmdContext, u: CmdGroundUnit) {
    val doc = ctx.core
    fun flag(bit: Int, on: Boolean, what: String) = ctx.apply(GroundOps.setFlag(doc, listOf(u), bit, on, "$what ${if (on) "on" else "off"}: ${AirOps.nameOf(doc, u)}"))
    Line("NoPlan") { CheckLine("", u.unitFlags and GroundOps.U_NO_PLANNING != 0) { flag(GroundOps.U_NO_PLANNING, it, "NoPlan") } }
    Hint("BMS's planners leave it out: an air-defence site (HAWK, Patriot, a mobile SA-x) stops moving round, an artillery battery holds its station, ready to fire on call.")
    Line("Set by HQ") { CheckLine("", GroundOps.setByHq(u)) { flag(GroundOps.U_NOT_BY_HQ, !it, "Set by HQ") } }
    Hint("Off: BMS's headquarters gives it no new orders; it keeps the orders set here.")
    Line("Temp destination") { CheckLine("", u.unitFlags and GroundOps.U_TEMP_DEST != 0) { flag(GroundOps.U_TEMP_DEST, it, "Temporary destination") } }
    Hint("BMS's temporary-destination mark — the same bit a flight's Locked is. Set, BMS works the final place out from the orders again as the unit moves; designers set it with NoPlan for artillery that holds and fires on call.")
}

@Composable
private fun CommonLines(ctx: CmdContext, u: CmdUnit) {
    val doc = ctx.core
    ChoiceLine("Owner", u.owner, ctx.teamOptions(), lead = { ctx.teamFlag(it) }) { t -> ctx.apply(AirOps.field(doc, u, "Owner of ${AirOps.nameOf(doc, u)}") { owner = t }) }
    NumLine("Number", u.nameId.toLong(), 0L..9999L, tip = "Its number in its name (\"502nd\")") { v -> ctx.apply(AirOps.field(doc, u, "Number of ${AirOps.nameOf(doc, u)}") { nameId = v.toInt() }) }
    Line("Cell (x, y)") {
        NumBox(u.x.toLong(), 0L..4000L, width = 60.dp) { v -> ctx.apply(GroundOps.moveUnit(doc, u, v.toInt(), u.y)) }
        Text("  ", fontSize = 11.sp)
        NumBox(u.y.toLong(), 0L..4000L, width = 60.dp) { v -> ctx.apply(GroundOps.moveUnit(doc, u, u.x, v.toInt())) }
    }
    Line("Spotted", tip = "Which teams have seen it (bits)") {
        Text("0x${u.spotted.toString(16)}", color = Hud.TextDim, fontSize = 12.sp)
        Text("  ", fontSize = 11.sp)
        SmallButton("Seen by all") { ctx.apply(AirOps.field(doc, u, "Spot ${AirOps.nameOf(doc, u)}") { spotted = 255; spotTime = doc.cmp?.currentTime ?: 0 }) }
        Text(" ", fontSize = 11.sp)
        SmallButton("By none") { ctx.apply(AirOps.field(doc, u, "Unspot ${AirOps.nameOf(doc, u)}") { spotted = 0; spotTime = 0 }) }
    }
}

@Composable
private fun TypeLines(ctx: CmdContext, u: CmdUnit, kind: UnitKind) {
    val doc = ctx.core
    val cls = UnitClasses.of(doc)
    var all by remember(u.id) { mutableStateOf(false) }
    val sub = cls.clsOfType(u.type)?.sub
    val types = cls.typesOf(kind).filter { all || it.sub == sub }
    ChoiceLine("Class", u.type, types.map { it.type to it.label }, tip = "Another class of the same kind: at full strength of it") { t -> ctx.apply(GroundOps.changeGroundType(doc, u, t)) }
    Line("") { CheckLine("every kind", all) { all = it } }
}

/** A battalion. */
object BattalionInspector : CmdInspector {
    override fun accepts(ref: CmdRef): Boolean = ref.kind == CmdKind.BATTALION

    @Composable
    override fun Content(ctx: CmdContext, refs: List<CmdRef>) {
        watchEdits()
        val b = ctx.unitOf(refs.first()) as? CmdBattalion ?: run { Hint("This battalion is no longer in the file."); return }
        val doc = ctx.core
        val sub = GroundOps.subOf(doc, b)
        InspectorColumn {
            GroundHead(ctx, b, "Battalion")
            Fold("Battalion", "u_batt", startOpen = true) {
                CommonLines(ctx, b)
                NumLine("Supply", b.supply.toLong(), 0L..100L, unit = "%") { v -> ctx.apply(AirOps.field(doc, b, "Supply of ${AirOps.nameOf(doc, b)}") { supply = v.toInt() }) }
                NumLine("Morale", b.morale.toLong(), 0L..100L, unit = "%") { v -> ctx.apply(AirOps.field(doc, b, "Morale of ${AirOps.nameOf(doc, b)}") { morale = v.toInt() }) }
                NumLine("Fatigue", b.fatigue.toLong(), 0L..255L) { v -> ctx.apply(AirOps.field(doc, b, "Fatigue of ${AirOps.nameOf(doc, b)}") { fatigue = v.toInt() }) }
            }
            Fold("Orders", "u_battorders", startOpen = true) { OrdersBlock(ctx, b, withElements = false) }
            Fold("Planning", "u_battplan", startOpen = true) { PlanningLines(ctx, b) }
            Fold("Brigade", "u_battbde", startOpen = true) {
                val bde = doc.unit(b.parentId) as? CmdBrigade
                val bdes = doc.brigades.filter { AirOps.allied(doc, b.owner, it.owner) && (it === bde || it.elements.size < 5) }
                    .sortedBy { kotlin.math.hypot((it.x - b.x).toDouble(), (it.y - b.y).toDouble()) }
                ChoiceLine<CmdBrigade?>("In brigade", bde, listOf<Pair<CmdBrigade?, String>>(null to "None (independent)") + bdes.map { it to "${ctx.world.unit(it.id.toString())?.name ?: "Brigade ${it.nameId}"} (${it.elements.size})" },
                    tip = "A brigade takes five battalions; its battalions take its division") { t -> ctx.apply(GroundOps.setBrigade(doc, b, t)) }
                if (sub == GroundOps.SUB_AIR_DEFENCE || sub == GroundOps.SUB_SUPPLY) {
                    val sup = doc.unit(b.cargoId) as? CmdBrigade
                    ChoiceLine<CmdBrigade?>(if (sub == GroundOps.SUB_SUPPLY) "Supplies" else "Protects", sup, listOf<Pair<CmdBrigade?, String>>(null to "No brigade") + doc.brigades.filter { AirOps.allied(doc, b.owner, it.owner) }.map { it to (ctx.world.unit(it.id.toString())?.name ?: "Brigade ${it.nameId}") },
                        tip = "An air-defence or supply battalion follows the brigade it protects or supplies") { t -> ctx.apply(GroundOps.setSupports(doc, b, t)) }
                }
                bde?.let { Line("") { LinkText("Show the brigade") { ctx.focus(refOf(it)) } } }
            }
            Fold("Class", "u_batttype", startOpen = false) { TypeLines(ctx, b, UnitKind.BATTALION) }
            RosterBlock(ctx, b)
            RouteBlock(ctx, b, air = false)
            LifeBlock(ctx, b)
        }
    }
}

/** A brigade: its ≤ 5 battalions, what it gives them (orders, owner, division, objective), placing them all. */
object BrigadeInspector : CmdInspector {
    override fun accepts(ref: CmdRef): Boolean = ref.kind == CmdKind.BRIGADE

    @Composable
    override fun Content(ctx: CmdContext, refs: List<CmdRef>) {
        watchEdits()
        val br = ctx.unitOf(refs.first()) as? CmdBrigade ?: run { Hint("This brigade is no longer in the file."); return }
        val doc = ctx.core
        val els = doc.battalionsOf(br)
        InspectorColumn {
            GroundHead(ctx, br, "Brigade")
            Fold("Battalions · ${br.elements.size} of 5", "u_bdeels", startOpen = true) {
                MiniTable(listOf("Battalion" to 196.dp, "Cell" to 64.dp), els.map { listOf(ctx.world.unit(it.id.toString())?.name ?: AirOps.nameOf(doc, it), cellText(it.x, it.y)) }) { i -> ctx.focus(refOf(els[i])) }
                val free = remember(doc.revision, br.id) {
                    doc.battalions.filter { it.parentId.isNone && AirOps.allied(doc, br.owner, it.owner) }.sortedBy { kotlin.math.hypot((it.x - br.x).toDouble(), (it.y - br.y).toDouble()) }.take(40)
                }
                ChoiceLine<CmdBattalion?>("Add", null, listOf<Pair<CmdBattalion?, String>>(null to "an independent battalion…") + free.map { it to (ctx.world.unit(it.id.toString())?.name ?: AirOps.nameOf(doc, it)) }, enabled = br.elements.size < 5) { b ->
                    if (b != null) ctx.apply(GroundOps.setBrigade(doc, b, br))
                }
                ChoiceLine<CmdBattalion?>("Take out", null, listOf<Pair<CmdBattalion?, String>>(null to "a battalion…") + els.map { it to (ctx.world.unit(it.id.toString())?.name ?: AirOps.nameOf(doc, it)) }, enabled = els.isNotEmpty()) { b ->
                    if (b != null) ctx.apply(GroundOps.setBrigade(doc, b, null))
                }
                val support = doc.battalions.filter { it.cargoId == br.id }
                if (support.isNotEmpty()) Hint("Protected or supplied by: " + support.joinToString { ctx.world.unit(it.id.toString())?.name ?: AirOps.nameOf(doc, it) })
            }
            Fold("Brigade", "u_bde", startOpen = true) {
                CommonLines(ctx, br)
                Buttons {
                    SmallButton("Owner to all", tip = "Its battalions get the brigade's owner") { ctx.apply(GroundOps.pushToElements(doc, br, "Owner")) }
                    SmallButton("Division to all") { ctx.apply(GroundOps.pushToElements(doc, br, "Division")) }
                }
            }
            Fold("Orders", "u_bdeorders", startOpen = true) {
                OrdersBlock(ctx, br, withElements = true)
                Buttons {
                    SmallButton("Orders to all", tip = "Its battalions get the brigade's orders as they are, nothing else") { ctx.apply(GroundOps.pushToElements(doc, br, "Orders")) }
                    SmallButton("Objective to all", tip = "Its battalions get the brigade's objective and destination as they are") { ctx.apply(GroundOps.pushToElements(doc, br, "Objective")) }
                }
            }
            Fold("Planning", "u_bdeplan", startOpen = false) { PlanningLines(ctx, br) }
            Fold("Class", "u_bdetype", startOpen = false) { TypeLines(ctx, br, UnitKind.BRIGADE) }
            RouteBlock(ctx, br, air = false)
            LifeBlock(ctx, br, childrenWord = "battalions")
        }
    }
}

/** A naval task force: roster, supply, orders, its route (drawn on the map), the carrier's squadrons. */
object TaskForceInspector : CmdInspector {
    override fun accepts(ref: CmdRef): Boolean = ref.kind == CmdKind.TASKFORCE ||
        (ref.kind == CmdKind.WAYPOINT && CommandSession.doc?.core?.unit(CampaignArchive.VuId.parse(ref.id))?.kind in setOf(UnitKind.TASKFORCE, UnitKind.BATTALION, UnitKind.BRIGADE))

    @Composable
    override fun Content(ctx: CmdContext, refs: List<CmdRef>) {
        watchEdits()
        val u = ctx.unitOf(refs.first())
        if (u is CmdBattalion) return BattalionInspector.Content(ctx, refs)
        if (u is CmdBrigade) return BrigadeInspector.Content(ctx, refs)
        val t = u as? CmdTaskForce ?: run { Hint("This task force is no longer in the file."); return }
        val doc = ctx.core
        InspectorColumn {
            GroundHead(ctx, t, "Task force")
            Fold("Task force", "u_tf", startOpen = true) {
                CommonLines(ctx, t)
                ChoiceLine("Orders", t.orders, GroundOps.ORDERS.mapIndexed { i, s -> i to s }) { v -> ctx.apply(AirOps.field(doc, t, "Orders of ${AirOps.nameOf(doc, t)}") { orders = v }) }
                NumLine("Supply", t.supply.toLong(), 0L..100L, unit = "%") { v -> ctx.apply(AirOps.field(doc, t, "Supply of ${AirOps.nameOf(doc, t)}") { supply = v.toInt() }) }
                val sqs = doc.squadrons.filter { it.airbaseId == t.id }
                if (sqs.isNotEmpty()) {
                    Hint("Its squadrons (they stand where it is): " + sqs.joinToString { ctx.world.unit(it.id.toString())?.name ?: AirOps.nameOf(doc, it) })
                }
            }
            Fold("Class", "u_tftype", startOpen = false) { TypeLines(ctx, t, UnitKind.TASKFORCE) }
            RosterBlock(ctx, t, "Ships")
            RouteBlock(ctx, t, air = false)
            Hint("Right-click the map with the task force selected: Add a waypoint here.")
            LifeBlock(ctx, t)
        }
    }
}
