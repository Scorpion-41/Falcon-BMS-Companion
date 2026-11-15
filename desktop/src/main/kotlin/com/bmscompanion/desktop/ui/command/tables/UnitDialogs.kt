package com.bmscompanion.desktop.ui.command.tables

import com.bmscompanion.desktop.ui.command.inspectors.teamFlag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.command.CmdObjective
import com.bmscompanion.desktop.bridge.command.CmdTaskForce
import com.bmscompanion.desktop.bridge.command.GroundOps
import com.bmscompanion.desktop.bridge.command.UnitBulk
import com.bmscompanion.desktop.bridge.command.UnitClasses
import com.bmscompanion.desktop.bridge.command.UnitKind
import com.bmscompanion.desktop.bridge.command.UnitLife
import com.bmscompanion.desktop.ui.command.CheckLine
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.DeskButton
import com.bmscompanion.desktop.ui.command.MiniChip
import com.bmscompanion.desktop.ui.command.MiniWrap
import com.bmscompanion.desktop.ui.command.inspectors.ChoiceLine
import com.bmscompanion.desktop.ui.command.inspectors.Hint
import com.bmscompanion.desktop.ui.command.inspectors.Line
import com.bmscompanion.desktop.ui.command.inspectors.SmallButton
import com.bmscompanion.desktop.ui.command.inspectors.applyAndSelectNew
import com.bmscompanion.desktop.ui.command.inspectors.apply
import com.bmscompanion.desktop.ui.command.inspectors.basesFor
import com.bmscompanion.desktop.ui.command.inspectors.nameOfAny
import com.bmscompanion.desktop.ui.command.inspectors.near
import com.bmscompanion.desktop.ui.command.inspectors.teamOptions
import com.bmscompanion.desktop.ui.command.inspectors.unitOf

/** The unit editor's dialogs: adding a unit (its class, team and place) and the bulk operations. */
object UnitDialogs {
    sealed class Req
    object Bulk : Req()
    /** The Units page's Add…: which kind to add, where (the selection, else the middle of the theater). */
    object AddAny : Req()
    /** Add a unit of [kind] at cell [gx]/[gy]; [at] the objective (or carrier) it was asked on. */
    class Add(val kind: UnitKind, val gx: Int, val gy: Int, val at: Any?, val owner: Int) : Req()

    var req by mutableStateOf<Req?>(null)
        private set

    fun open(r: Req) { req = r }
    fun close() { req = null }

    @Composable
    fun Host(ctx: CmdContext) {
        when (val r = req) {
            is Add -> AddDialog(ctx, r)
            Bulk -> BulkDialog(ctx)
            AddAny -> AddAnyDialog(ctx)
            null -> Unit
        }
    }

    /**
     * Add…: a squadron (at the selected airbase or carrier, else one picked in the next window), a flight of a
     * squadron, a battalion, brigade or task force where the selection is (else the middle of the theater). Units
     * live in the save, so every file takes them — a played save as well as a campaign start.
     */
    @Composable
    private fun AddAnyDialog(ctx: CmdContext) {
        val doc = ctx.core
        val cls = UnitClasses.of(doc)
        val selRef = ctx.selection.primary
        val selObj = selRef?.takeIf { it.kind == com.bmscompanion.desktop.ui.command.CmdKind.OBJECTIVE }?.let { doc.objective(com.bmscompanion.desktop.bridge.CampaignArchive.VuId.parse(it.id)) }
        val selUnit = selRef?.takeIf { it.kind != com.bmscompanion.desktop.ui.command.CmdKind.OBJECTIVE }?.let { ctx.unitOf(it) }
        val mid = (ctx.world.sizeFt / 2 / com.bmscompanion.desktop.bridge.CampaignArchive.FEET_PER_CELL).toInt()
        val (gx, gy) = selObj?.let { GroundOps.objCell(it) } ?: selUnit?.let { it.x to it.y } ?: (mid to mid)
        val place = selObj?.let { GroundOps.objName(doc, it) } ?: selUnit?.let { com.bmscompanion.desktop.bridge.command.AirOps.nameOf(doc, it) } ?: "the middle of the theater"
        val owner = selObj?.let { doc.ownerNow(it) } ?: selUnit?.owner ?: com.bmscompanion.desktop.ui.command.CommandSession.ownerTeam
        val airfield = selObj?.takeIf { cls.clsOfType(it.type)?.type in setOf(1, 2) }
        val carrier = (selUnit as? CmdTaskForce)?.takeIf { cls.clsOfType(it.type)?.sub == 3 }
        val sqs = remember(doc.revision) { doc.squadrons.filter { cls.flightTypeFor(it.type) != null }.sortedBy { ctx.world.unit(it.id.toString())?.name ?: "" } }
        val start = (selUnit as? com.bmscompanion.desktop.bridge.command.CmdSquadron) ?: (selUnit as? com.bmscompanion.desktop.bridge.command.CmdFlight)?.let { f -> doc.unit(f.squadronId) as? com.bmscompanion.desktop.bridge.command.CmdSquadron }
        var sq by remember { mutableStateOf(start ?: sqs.firstOrNull()) }
        val editable = doc.uni?.editable == true
        AlertDialog(
            onDismissRequest = { close() },
            confirmButton = { DeskButton("Close") { close() } },
            title = { Text("Add a unit", color = Hud.Text, fontSize = 15.sp) },
            text = {
                Column(Modifier.width(500.dp)) {
                    Hint(if (editable) "Units live in the save, so any file takes new ones — a played save as well as a campaign start. Each addition is one step the toolbar's Undo takes back."
                        else "This file's units could not be read exactly, so none can be added to it.")
                    Section("Squadron")
                    Hint(when {
                        airfield != null -> "At ${GroundOps.objName(doc, airfield)} (the selected airbase)."
                        carrier != null -> "On ${place} (the selected carrier)."
                        else -> "Its airbase or carrier is picked in the next window (or select an airbase first; its inspector and the map's right-click offer the same)."
                    })
                    MiniWrap { SmallButton("Add a squadron…", enabled = editable) { open(Add(UnitKind.SQUADRON, gx, gy, airfield ?: carrier, owner)) } }
                    Section("Flight")
                    ChoiceLine<com.bmscompanion.desktop.bridge.command.CmdSquadron?>("Squadron", sq, sqs.map { it to (ctx.world.unit(it.id.toString())?.name ?: com.bmscompanion.desktop.bridge.command.AirOps.nameOf(doc, it)) }) { sq = it }
                    MiniWrap {
                        SmallButton("Add a two-ship", enabled = editable && sq != null, tip = "In a package of its own, an hour from the campaign's clock; draw its route afterwards") {
                            val s = sq ?: return@SmallButton
                            val mission = doc.flights.firstOrNull { it.squadronId == s.id }?.mission ?: 0
                            close()
                            ctx.applyAndSelectNew(UnitLife.addFlight(doc, s, 2, mission, (doc.cmp?.currentTime ?: 0L) + com.bmscompanion.desktop.bridge.command.AirOps.HOUR_MS), UnitKind.FLIGHT)
                        }
                    }
                    Section("Ground and naval units")
                    Hint("At $place; move it afterwards with the map's Move tool.")
                    MiniWrap {
                        SmallButton("Battalion…", enabled = editable) { open(Add(UnitKind.BATTALION, gx, gy, selObj, owner)) }
                        SmallButton("Brigade…", enabled = editable) { open(Add(UnitKind.BRIGADE, gx, gy, selObj, owner)) }
                        SmallButton("Task force…", enabled = editable) { open(Add(UnitKind.TASKFORCE, gx, gy, selObj, owner)) }
                    }
                }
            },
            containerColor = Hud.Surface,
        )
    }

    @Composable
    private fun AddDialog(ctx: CmdContext, r: Add) {
        val doc = ctx.core
        val cls = UnitClasses.of(doc)
        val types = remember(r.kind) { cls.typesOf(r.kind) }
        var type by remember(r) { mutableStateOf(types.firstOrNull()?.type ?: 0) }
        var owner by remember(r) { mutableStateOf(r.owner) }
        var orders by remember(r) { mutableStateOf<Int?>(null) }
        var base by remember(r) { mutableStateOf(r.at) }
        val word = when (r.kind) { UnitKind.SQUADRON -> "squadron"; UnitKind.BATTALION -> "battalion"; UnitKind.BRIGADE -> "brigade"; else -> "task force" }
        val warn = (base as? CmdObjective)?.let { if (r.kind == UnitKind.BATTALION) GroundOps.siteShortfall(doc, type, it) else null }
        AlertDialog(
            onDismissRequest = { close() },
            confirmButton = {
                DeskButton("Add the $word", strong = true, enabled = type != 0 && (r.kind != UnitKind.SQUADRON || base != null)) {
                    val e = when (r.kind) {
                        UnitKind.SQUADRON -> UnitLife.addSquadron(doc, type, base!!, owner)
                        UnitKind.BATTALION -> UnitLife.addBattalion(doc, type, owner, r.gx, r.gy, base as? CmdObjective, orders)
                        UnitKind.BRIGADE -> UnitLife.addBrigade(doc, type, owner, r.gx, r.gy, base as? CmdObjective)
                        else -> UnitLife.addTaskForce(doc, type, owner, r.gx, r.gy)
                    }
                    close()
                    ctx.applyAndSelectNew(e, r.kind)
                }
            },
            dismissButton = { DeskButton("Cancel") { close() } },
            title = { Text("Add a $word", color = Hud.Text, fontSize = 15.sp) },
            text = {
                Column(Modifier.width(460.dp)) {
                    ChoiceLine("Class", type, types.map { it.type to it.label }) { type = it }
                    ChoiceLine("Team", owner, ctx.teamOptions(), lead = { ctx.teamFlag(it) }) { owner = it }
                    when (r.kind) {
                        UnitKind.SQUADRON -> ChoiceLine("Base", base, doc.basesFor(owner, false).let { l -> if (base != null && l.none { it.first === base }) listOf(base to ctx.nameOfAny(base)) + l else l }) { base = it }
                        UnitKind.BATTALION, UnitKind.BRIGADE -> {
                            val nearby = remember(r) { doc.near(r.gx, r.gy, 25.0, units = false).take(30).map { it.first as CmdObjective } }
                            val opts = listOf<Pair<Any?, String>>(null to "None: stands at the point clicked") + (listOfNotNull(base as? CmdObjective) + nearby).distinct().map { it to GroundOps.objName(doc, it) }
                            ChoiceLine("Objective", base, opts) { base = it }
                            if (r.kind == UnitKind.BATTALION) ChoiceLine("Orders", orders ?: (if (cls.clsOfType(type)?.sub == GroundOps.SUB_AIR_DEFENCE) 9 else 0), GroundOps.ORDERS.mapIndexed { i, s -> i to s }) { orders = it }
                        }
                        else -> Unit
                    }
                    warn?.let { Text(it, color = Hud.Amber, fontSize = 12.sp) }
                    Hint(
                        when (r.kind) {
                            UnitKind.SQUADRON -> "A squadron at full strength, stocked from its class's template, with 48 pilots from the team's ranks and its row in the header's squadron list."
                            UnitKind.BATTALION -> "At full strength, supply and morale 100; an air-defence battalion is assigned, ordered and emitting."
                            UnitKind.BRIGADE -> "An empty brigade: give it battalions from its inspector (five at most)."
                            else -> "At full strength with supply 100 and no route: give it waypoints from its inspector or the map."
                        },
                    )
                }
            },
            containerColor = Hud.Surface,
        )
    }

    @Composable
    private fun Section(title: String) {
        Spacer(Modifier.height(8.dp))
        Text(title.uppercase(), color = Hud.TextDim, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Rule()
    }

    @Composable
    private fun BulkDialog(ctx: CmdContext) {
        val doc = ctx.core
        val cls = UnitClasses.of(doc)
        val teams = ctx.teamOptions()
        AlertDialog(
            onDismissRequest = { close() },
            confirmButton = { DeskButton("Close", strong = true) { close() } },
            title = { Text("Units in bulk", color = Hud.Text, fontSize = 15.sp) },
            text = {
                Column(Modifier.width(560.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                    Hint("Each is one step the toolbar's Undo takes back.")
                    Section("Squadrons and rosters")
                    MiniWrap {
                        SmallButton("Resupply every squadron", tip = "Stores as each squadron's class template has them") { ctx.apply(UnitBulk.resupplyAll(doc)) }
                        SmallButton("Full rosters: squadrons", tip = "Every squadron at its class's full strength") { ctx.apply(UnitBulk.fullRosters(doc, setOf(UnitKind.SQUADRON))) }
                        SmallButton("battalions") { ctx.apply(UnitBulk.fullRosters(doc, setOf(UnitKind.BATTALION, UnitKind.BRIGADE))) }
                        SmallButton("task forces") { ctx.apply(UnitBulk.fullRosters(doc, setOf(UnitKind.TASKFORCE))) }
                        SmallButton("Reset spotting", tip = "No unit seen by anyone; BMS spots them again as the campaign runs") { ctx.apply(UnitBulk.resetSpotted(doc)) }
                    }

                    Section("A team's units to another team")
                    var from by remember { mutableStateOf(teams.getOrNull(0)?.first ?: 0) }
                    var to by remember { mutableStateOf(teams.getOrNull(1)?.first ?: 1) }
                    ChoiceLine("From", from, teams) { from = it }
                    ChoiceLine("To", to, teams) { to = it }
                    MiniWrap { SmallButton("Move every unit", tip = "Objectives and the teams' relations are not changed") { ctx.apply(UnitBulk.changeTeam(doc, from, to)) } }

                    Section("Every unit of a class to another class")
                    var kind by remember { mutableStateOf(UnitKind.SQUADRON) }
                    var owner by remember { mutableStateOf(-1) }
                    MiniWrap { MiniChip("Squadrons", kind == UnitKind.SQUADRON) { kind = UnitKind.SQUADRON }; MiniChip("Battalions", kind == UnitKind.BATTALION) { kind = UnitKind.BATTALION } }
                    ChoiceLine("Team", owner, listOf(-1 to "Every team") + teams) { owner = it }
                    val present = doc.units.filter { it.kind == kind && (owner < 0 || it.owner == owner) }.map { it.type }.distinct()
                    val all = cls.typesOf(kind)
                    var fromT by remember(kind, owner) { mutableStateOf(present.firstOrNull() ?: 0) }
                    var toT by remember(kind) { mutableStateOf(all.firstOrNull()?.type ?: 0) }
                    ChoiceLine("From class", fromT, present.map { t -> t to ((all.firstOrNull { it.type == t }?.label ?: "class $t") + " (${doc.units.count { it.kind == kind && it.type == t && (owner < 0 || it.owner == owner) }})") }) { fromT = it }
                    ChoiceLine("To class", toT, all.map { it.type to it.label }) { toT = it }
                    MiniWrap { SmallButton("Change them", tip = "Full strength of the new class; a squadron's flights fly the new aircraft (loadouts cleared to the gun)") { ctx.apply(UnitBulk.changeClassAll(doc, kind, owner, fromT, toT)) } }

                    Section("Air defence on objectives")
                    var adOwner by remember { mutableStateOf(teams.getOrNull(0)?.first ?: 0) }
                    val objTypes = remember { doc.objectives.mapNotNull { cls.clsOfType(it.type)?.type }.distinct().sorted() }
                    var objType by remember { mutableStateOf(objTypes.firstOrNull { it == 31 } ?: objTypes.firstOrNull() ?: 0) }
                    val adTypes = cls.typesOf(UnitKind.BATTALION).filter { it.sub == GroundOps.SUB_AIR_DEFENCE }
                    val chosen = remember { mutableStateListOf<Int>() }
                    var siteCheck by remember { mutableStateOf(true) }
                    ChoiceLine("Team", adOwner, teams) { adOwner = it }
                    ChoiceLine("Objectives", objType, objTypes.map { t -> t to (doc.string(500 + t)?.trim()?.ifEmpty { null } ?: "type $t") }) { objType = it }
                    ChoiceLine("Battalion", 0, listOf(0 to "add a class…") + adTypes.map { it.type to it.label }) { if (it != 0 && it !in chosen) chosen.add(it) }
                    if (chosen.isNotEmpty()) Line("Taken in turn") { MiniWrap { chosen.forEach { t -> MiniChip((adTypes.firstOrNull { it.type == t }?.label ?: "$t") + "  ✕", true) { chosen.remove(t) } } } }
                    CheckLine("Leave out objectives with too few SAM/AAA spots", siteCheck) { siteCheck = it }
                    val plan = remember(doc.revision, adOwner, objType, chosen.toList(), siteCheck) { UnitBulk.planAirDefence(doc, adOwner, objType, chosen.toList(), siteCheck) }
                    Hint("${plan.place.size} objective${if (plan.place.size == 1) "" else "s"} get a battalion; ${plan.skipped.size} left out" + (plan.skipped.firstOrNull()?.let { " (${GroundOps.objName(doc, it.first)}: ${it.second}${if (plan.skipped.size > 1) " …" else ""})" } ?: "") + ".")
                    MiniWrap { SmallButton("Place them", enabled = plan.place.isNotEmpty()) { ctx.apply(UnitBulk.addAirDefence(doc, adOwner, plan)) } }

                    Section("NoPlan for ground units")
                    var npOwner by remember { mutableStateOf(-1) }
                    var npOff by remember { mutableStateOf(false) }
                    ChoiceLine("Team", npOwner, listOf(-1 to "Every team") + teams) { npOwner = it }
                    CheckLine("Take NoPlan off them instead", npOff) { npOff = it }
                    val ad = UnitBulk.battalionsOfRole(doc, npOwner, artillery = false)
                    val art = UnitBulk.battalionsOfRole(doc, npOwner, artillery = true)
                    Hint("With NoPlan BMS's planners leave a battalion where it is: an air-defence site (HAWK, Patriot, a mobile SA-x) stops moving round, an artillery battery holds its station, ready to fire on call.")
                    MiniWrap {
                        SmallButton("Air-defence battalions (${ad.count { (it.unitFlags and GroundOps.U_NO_PLANNING != 0) == npOff }} of ${ad.size})", enabled = ad.isNotEmpty()) { ctx.apply(UnitBulk.noPlanFor(doc, npOwner, artillery = false, on = !npOff)) }
                        SmallButton("Artillery, rockets, missiles (${art.count { (it.unitFlags and GroundOps.U_NO_PLANNING != 0) == npOff }} of ${art.size})", enabled = art.isNotEmpty()) { ctx.apply(UnitBulk.noPlanFor(doc, npOwner, artillery = true, on = !npOff)) }
                    }

                    Section("Fix every unit issue")
                    val issues = com.bmscompanion.desktop.ui.command.inspectors.UnitIssues.of(doc)
                    Hint("${issues.size} issue${if (issues.size == 1) "" else "s"}, ${issues.count { it.fix != null }} with a fix. The Checks page lists them one by one.")
                    MiniWrap { SmallButton("Fix them all", enabled = issues.any { it.fix != null }) { ctx.apply(com.bmscompanion.desktop.bridge.command.UnitChecks.fixAll(doc)) } }
                }
            },
            containerColor = Hud.Surface,
        )
    }
}
