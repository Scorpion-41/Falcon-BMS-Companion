package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.foundation.layout.width
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
import com.bmscompanion.desktop.bridge.command.CmdObjective
import com.bmscompanion.desktop.bridge.command.CmdSquadron
import com.bmscompanion.desktop.bridge.command.CmdTaskForce
import com.bmscompanion.desktop.bridge.command.CommandSave
import com.bmscompanion.desktop.bridge.command.GroundOps
import com.bmscompanion.desktop.bridge.command.UnitClasses
import com.bmscompanion.desktop.bridge.command.UnitKind
import com.bmscompanion.desktop.bridge.command.UnitLife
import com.bmscompanion.desktop.ui.command.CheckLine
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdInspector
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.Fold
import com.bmscompanion.desktop.ui.command.MiniChip
import com.bmscompanion.desktop.ui.command.MiniWrap
import com.bmscompanion.desktop.ui.command.teamInk

/** Airbases and airstrips (class type 1, 2) a team may base squadrons at, and carriers (task forces of sub type 3). */
fun CommandSave.basesFor(owner: Int, allTeams: Boolean): List<Pair<Any, String>> {
    val cls = UnitClasses.of(this)
    val fields = objectives.filter { o -> cls.clsOfType(o.type)?.type in setOf(1, 2) && (allTeams || AirOps.allied(this, owner, ownerNow(o))) }
        .map { it as Any to GroundOps.objName(this, it) }.sortedBy { it.second.lowercase() }
    val carriers = taskForces.filter { t -> cls.clsOfType(t.type)?.sub == 3 && (allTeams || AirOps.allied(this, owner, t.owner)) }
        .map { it as Any to "Carrier: ${cls.leadVcd(it.type)?.name ?: "task force ${it.nameId}"}" }
    return fields + carriers
}

/**
 * A squadron: its name (the header's row), owner (with its flights), base (its flights' take-off and landings move
 * with it), specialty, patch, paint and fuel, its aircraft (another type brings its flights along), the roster, the 48
 * pilots, the stores against its class's template, the schedule, adding a flight, clone and delete.
 */
object SquadronInspector : CmdInspector {
    override fun accepts(ref: CmdRef): Boolean = ref.kind == CmdKind.SQUADRON

    @Composable
    override fun Content(ctx: CmdContext, refs: List<CmdRef>) {
        watchEdits()
        val sq = ctx.unitOf(refs.first()) as? CmdSquadron ?: run { Hint("This squadron is no longer in the file."); return }
        val doc = ctx.core
        val cls = UnitClasses.of(doc)
        val si = doc.squadInfo(sq)
        val flights = doc.flights.filter { it.squadronId == sq.id }
        InspectorColumn {
            val team = ctx.world.team(sq.owner)
            val base: Any? = doc.objective(sq.airbaseId) ?: doc.unit(sq.airbaseId)
            InspectorHead(ctx.world.unit(sq.id.toString())?.name ?: AirOps.nameOf(doc, sq), "Squadron · ${cls.leadVcd(sq.type)?.name ?: doc.aircraft(sq) ?: ""}",
                "${team?.name ?: "team ${sq.owner}"} · at ${ctx.nameOfAny(base)} · ${flights.size} flight${if (flights.size == 1) "" else "s"} · id ${sq.id}", ink = team?.let { teamInk(it.colour) } ?: Hud.Text, flag = ctx.teamFlag(sq.owner))
            IssuesBlock(ctx, sq)
            Fold("Squadron", "u_sq", startOpen = true) {
                TextLine("Name", si?.squadronName?.text.orEmpty(), tip = "The name BMS shows (kept in the header's squadron list)") { t -> ctx.apply(GroundOps.setSquadronName(doc, sq, t)) }
                NumLine("Number", sq.nameId.toLong(), 0L..9999L, tip = "Its number (\"36th\")") { v -> ctx.apply(GroundOps.squadron(doc, sq, "Number of ${AirOps.nameOf(doc, sq)}") { nameId = v.toInt() }) }
                var withFlights by remember(sq.id) { mutableStateOf(true) }
                ChoiceLine("Owner", sq.owner, ctx.teamOptions(), lead = { ctx.teamFlag(it) }) { t -> ctx.apply(GroundOps.setSquadronOwner(doc, sq, t, withFlights)) }
                Line("") { CheckLine("its flights too", withFlights) { withFlights = it } }
                var allTeams by remember(sq.id) { mutableStateOf(false) }
                ChoiceLine("Base", base, listOfNotNull(base?.let { it to ctx.nameOfAny(it) }) + doc.basesFor(sq.owner, allTeams).filter { it.first !== base },
                    tip = "Its flights' take-off and landings move with it") { b -> if (b != null && b !== base) ctx.apply(GroundOps.moveBase(doc, sq, b)) }
                Line("") { CheckLine("list every team's airbases", allTeams) { allTeams = it } }
                ChoiceLine("Specialty", sq.specialty, GroundOps.SPECIALTIES.mapIndexed { i, s -> i to s }) { v -> ctx.apply(GroundOps.squadron(doc, sq, "Specialty of ${AirOps.nameOf(doc, sq)}") { specialty = v }) }
                Line("Patch", tip = "The patch's place in BMS's patch list (Art\\Patches\\ImageIds.id); the picture is BMS's own") {
                    NumBox(sq.squadronPatch.toLong(), 0L..65535L) { v -> ctx.apply(GroundOps.squadron(doc, sq, "Patch of ${AirOps.nameOf(doc, sq)}") { squadronPatch = v.toInt() }) }
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    com.bmscompanion.desktop.ui.command.tables.SquadPatch(doc, sq.squadronPatch, 40.dp)
                    com.bmscompanion.desktop.bridge.command.SquadronPatches.of(doc)?.name(sq.squadronPatch)?.let {
                        Text("  $it", color = Hud.TextFaint, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                if (sq.ver >= 105) NumLine("Paint", sq.texSet.toLong(), -1L..99L, tip = "The skin set its aircraft wear") { v -> ctx.apply(GroundOps.squadron(doc, sq, "Paint of ${AirOps.nameOf(doc, sq)}") { texSet = v.toInt() }) }
                NumLine("Fuel", sq.fuel.toLong(), 0L..Int.MAX_VALUE.toLong(), unit = "lb") { v -> ctx.apply(GroundOps.squadron(doc, sq, "Fuel of ${AirOps.nameOf(doc, sq)}") { fuel = v.toInt() }) }
                Line("Planned by BMS", tip = "Off: the campaign's air tasking does not plan missions for it") {
                    CheckLine("", sq.unitFlags and GroundOps.U_NO_PLANNING == 0) { on -> ctx.apply(GroundOps.squadron(doc, sq, "Planning of ${AirOps.nameOf(doc, sq)}") { unitFlags = if (on) unitFlags and GroundOps.U_NO_PLANNING.inv() else unitFlags or GroundOps.U_NO_PLANNING }) }
                }
                Line("Active", tip = "An inactive squadron waits (a reinforcement)") {
                    CheckLine("", sq.unitFlags and GroundOps.U_INACTIVE == 0) { on -> ctx.apply(GroundOps.squadron(doc, sq, "Active ${AirOps.nameOf(doc, sq)}") { unitFlags = if (on) unitFlags and GroundOps.U_INACTIVE.inv() else unitFlags or GroundOps.U_INACTIVE }) }
                }
            }
            Fold("Aircraft", "u_sqtype", startOpen = false) {
                var all by remember(sq.id) { mutableStateOf(false) }
                val types = cls.squadronTypes(if (all) null else sq.type)
                ChoiceLine("Fly instead", sq.type, types.map { it.type to it.label }, tip = "Its flights fly the new aircraft too (their loadouts cleared to the gun)") { t -> ctx.apply(GroundOps.changeSquadronType(doc, sq, t)) }
                Line("") { CheckLine("every role", all) { all = it } }
            }
            RosterBlock(ctx, sq)
            PilotsBlock(ctx, sq)
            StoresBlock(ctx, sq)
            Fold("Schedule", "u_sqsched", startOpen = false) {
                Hint(sq.schedule.mapIndexed { i, v -> "${i + 1}: ${v.toUInt().toString(16)}" }.chunked(4).joinToString("\n") { it.joinToString("  ") })
                Buttons { SmallButton("Clear", tip = "Every aircraft free of booked missions") { ctx.apply(GroundOps.clearSchedule(doc, sq)) } }
            }
            Fold("Flights", "u_sqflights", startOpen = false) {
                MiniTable(listOf("Flight" to 80.dp, "Mission" to 90.dp, "T/O" to 60.dp, "Pkg" to 50.dp),
                    flights.map { f -> listOf(doc.callsign(f), FlightTimes.mission(doc, f.mission), FlightTimes.hhmm(FlightTimes.takeoff(f)), doc.packageOf(f)?.campId?.toString() ?: "") }) { i -> ctx.focus(refOf(flights[i])) }
                var n by remember(sq.id) { mutableStateOf(2) }
                var mission by remember(sq.id) { mutableStateOf(flights.firstOrNull()?.mission ?: 0) }
                Line("New flight") { MiniWrap { (1..4).forEach { k -> MiniChip("$k", n == k) { n = k } } } }
                ChoiceLine("Mission", mission, (0 until 50).mapNotNull { m -> doc.string(CampaignArchive.MISSION_STRINGS + m)?.trim()?.takeIf { it.isNotEmpty() }?.let { m to it } }) { mission = it }
                var takeoff by remember(sq.id) { mutableStateOf((doc.cmp?.currentTime ?: 0L) + AirOps.HOUR_MS) }
                ClockLine("Take-off", takeoff) { v -> takeoff = v; null }
                Buttons { SmallButton("Add the flight", tip = "In a package of its own; draw its route afterwards") { ctx.applyAndSelectNew(UnitLife.addFlight(doc, sq, n, mission, takeoff), UnitKind.FLIGHT) } }
            }
            LifeBlock(ctx, sq, childrenWord = "flights")
        }
    }

    @Composable
    private fun PilotsBlock(ctx: CmdContext, sq: CmdSquadron) {
        val doc = ctx.core
        var sel by remember(sq.id) { mutableStateOf(-1) }
        val counts = sq.pilots.groupingBy { it.status }.eachCount()
        Fold("Pilots · ${counts[0] ?: 0} available, ${counts[4] ?: 0} flying", "u_sqpilots", startOpen = false) {
            MiniTable(
                listOf("#" to 22.dp, "Pilot" to 128.dp, "Skill" to 38.dp, "Status" to 66.dp, "Kills" to 36.dp, "Msn" to 36.dp),
                sq.pilots.mapIndexed { i, p -> listOf("${i + 1}", doc.pilotName(p.pilotId)?.trim() ?: "pilot ${p.pilotId}", "${p.skillAndRating}", GroundOps.PILOT_STATUS.getOrElse(p.status) { "${p.status}" }, "${p.aaKills + p.agKills + p.asKills + p.anKills}", "${p.missionsFlown}") },
                selected = sel, maxHeight = 200.dp,
                inks = sq.pilots.map { if (it.status == 1 || it.status == 2) Hud.Red else if (it.status == 4) Hud.Amber else null },
            ) { sel = if (sel == it) -1 else it }
            sq.pilots.getOrNull(sel)?.let { p ->
                NumLine("Skill", p.skillAndRating.toLong(), 0L..255L) { v -> ctx.apply(com.bmscompanion.desktop.bridge.command.AirOps.field(doc, sq, "Skill of a pilot") { pilots[sel].skillAndRating = v.toInt() }) }
                ChoiceLine("Status", p.status, GroundOps.PILOT_STATUS.mapIndexed { i, s -> i to s }) { v -> ctx.apply(AirOps.field(doc, sq, "Status of a pilot") { pilots[sel].status = v }) }
            }
            Buttons {
                SmallButton("Free the unused", tip = "Pilots marked flying with no seat, and the rescued, available again") { ctx.apply(GroundOps.freeUsedPilots(doc, sq)) }
                SmallButton("Bring back the lost", tip = "Killed and missing pilots available again") { ctx.apply(GroundOps.resurrectPilots(doc, sq)) }
                SmallButton("New pilots", tip = "48 new pilots from the team's ranks (those flying keep flying)") { ctx.apply(GroundOps.regeneratePilots(doc, sq)) }
            }
        }
    }

    @Composable
    private fun StoresBlock(ctx: CmdContext, sq: CmdSquadron) {
        val doc = ctx.core
        val cls = UnitClasses.of(doc)
        val template = remember(sq.type) { cls.storesTemplate(sq.type).orEmpty() }
        var all by remember(sq.id) { mutableStateOf(false) }
        val ids = (0 until minOf(1000, sq.stores.size)).filter { all || sq.store(it) > 0 || (template[it] ?: 0) > 0 }
        Fold("Stores · ${ids.count { sq.store(it) > 0 }} kinds", "u_sqstores", startOpen = false) {
            Line("") { CheckLine("every weapon", all) { all = it } }
            for (id in ids.take(300)) {
                val max = template[id] ?: 0
                val have = sq.store(id)
                Line(cls.weaponName(id), tip = "Weapon $id") {
                    NumBox(have.toLong(), 0L..255L, width = 52.dp) { v -> ctx.apply(GroundOps.setStore(doc, sq, id, v.toInt())) }
                    val ink = when { max == 0 -> Hud.TextFaint; have == 0 -> Hud.Red; have * 100 / max < 35 -> Hud.Amber; else -> Hud.TextDim }
                    Text("  of $max", color = ink, fontSize = 11.sp, modifier = Modifier.width(60.dp))
                }
            }
            Buttons { SmallButton("Resupply", tip = "Every store as the class's template has it") { ctx.apply(GroundOps.resupply(doc, sq)) } }
        }
    }
}
