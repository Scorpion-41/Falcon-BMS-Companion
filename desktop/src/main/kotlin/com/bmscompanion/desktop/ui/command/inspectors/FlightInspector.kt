package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.command.AirOps
import com.bmscompanion.desktop.bridge.command.CmdFlight
import com.bmscompanion.desktop.bridge.command.CmdPackage
import com.bmscompanion.desktop.bridge.command.CmdSquadron
import com.bmscompanion.desktop.bridge.command.Loadout
import com.bmscompanion.desktop.bridge.command.UnitClasses
import com.bmscompanion.desktop.ui.command.CheckLine
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdInspector
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.Fold
import com.bmscompanion.desktop.ui.command.MiniChip
import com.bmscompanion.desktop.ui.command.MiniWrap
import com.bmscompanion.desktop.ui.command.teamInk
import kotlin.math.roundToInt

/** Plane states (a seat's aircraft). */
private val PLANE_STATES = listOf(0 to "No aircraft", 1 to "Missing", 2 to "Lost", 3 to "Returning", 4 to "Available")

/**
 * A flight: callsign (unique, its number marked in use), mission, aircraft and seats (pilot, state, laser code, fuel,
 * paint, conformal tanks), position, the route with its waypoints and the loadout, its package and squadron, and
 * shifting its times. Every row is one undoable edit.
 */
object FlightInspector : CmdInspector {
    override fun accepts(ref: CmdRef): Boolean = ref.kind == CmdKind.FLIGHT ||
        (ref.kind == CmdKind.WAYPOINT && com.bmscompanion.desktop.ui.command.CommandSession.doc?.core?.unit(CampaignArchive.VuId.parse(ref.id)) is CmdFlight)

    @Composable
    override fun Content(ctx: CmdContext, refs: List<CmdRef>) {
        watchEdits()
        val f = ctx.unitOf(refs.first()) as? CmdFlight ?: run { Hint("This flight is no longer in the file."); return }
        val doc = ctx.core
        val cls = UnitClasses.of(doc)
        val sq = doc.squadronOf(f)
        val pkg = doc.packageOf(f)
        InspectorColumn {
            val team = ctx.world.team(f.owner)
            InspectorHead(
                com.bmscompanion.desktop.bridge.command.UnitNames.full(doc, f), "Flight · ${cls.leadVcd(f.type)?.name ?: doc.aircraft(f) ?: "aircraft ${f.type}"}",
                "${doc.string(CampaignArchive.MISSION_STRINGS + f.mission)?.trim() ?: "mission ${f.mission}"} · ${team?.name ?: "team ${f.owner}"} · id ${f.id} · CampId ${f.campId}",
                ink = team?.let { teamInk(it.colour) } ?: Hud.Text, flag = ctx.teamFlag(f.owner),
            )
            IssuesBlock(ctx, f)
            Line("Package") {
                if (pkg != null) LinkText("${com.bmscompanion.desktop.bridge.command.UnitNames.full(doc, pkg)} · ${pkg.elements.size} flight${if (pkg.elements.size == 1) "" else "s"}") { ctx.focus(refOf(pkg)) } else Text("none", color = Hud.Red, fontSize = 12.sp)
            }
            Line("Squadron") {
                if (sq != null) LinkText(ctx.world.unit(sq.id.toString())?.name ?: AirOps.nameOf(doc, sq)) { ctx.focus(refOf(sq)) } else Text("none", color = Hud.Red, fontSize = 12.sp)
            }

            Fold("Flight", "u_flight", startOpen = true) {
                val range = cls.callsignRange(f.type)?.toList().orEmpty()
                val ids = (listOf(f.callsignId) + range).distinct()
                Line("Callsign", tip = "The callsign word and number (1-9), unique among the flights") {
                    Choice(f.callsignId, ids.map { it to (doc.string(CampaignArchive.CALLSIGN_STRINGS + it)?.trim() ?: "Callsign $it") }, Modifier.weight(1f)) { id ->
                        val num = com.bmscompanion.desktop.bridge.command.CommandIds.callsignNumber(doc, id, except = f) ?: f.callsignNum
                        ctx.apply(AirOps.setCallsign(doc, f, id, num))
                    }
                    Text(" ", fontSize = 11.sp)
                    NumBox(f.callsignNum.toLong(), 1L..9L, width = 40.dp) { n -> ctx.apply(AirOps.setCallsign(doc, f, f.callsignId, n.toInt())) }
                }
                ChoiceLine("Mission", f.mission, (0 until 50).mapNotNull { m -> doc.string(CampaignArchive.MISSION_STRINGS + m)?.trim()?.takeIf { it.isNotEmpty() }?.let { m to it } }) { m -> ctx.apply(AirOps.setMission(doc, f, m)) }
                ChoiceLine("Owner", f.owner, ctx.teamOptions(), lead = { ctx.teamFlag(it) }) { t -> ctx.apply(AirOps.field(doc, f, "Owner of ${doc.callsign(f)}") { owner = t }) }
                Line("Aircraft") { MiniWrap { (1..4).forEach { n -> MiniChip("$n", f.aircraft == n) { ctx.apply(AirOps.setAircraft(doc, f, n)) } } } }
                NumLine("Priority", f.priority.toLong(), 0L..255L) { v -> ctx.apply(AirOps.field(doc, f, "Priority of ${doc.callsign(f)}") { priority = v.toInt() }) }
                ClockLine("Time on target", f.timeOnTarget) { v -> ctx.apply(AirOps.field(doc, f, "Time on target of ${doc.callsign(f)}") { timeOnTarget = v }) }
                ShiftLine(ctx, "the whole route") { ms -> AirOps.shiftTimes(doc, listOf(f), ms, "Shift the times of ${doc.callsign(f)}") }
            }

            Fold("Seats", "u_seats", startOpen = true) {
                for (s in 0 until 4) Seat(ctx, f, sq, s)
                NumLine("Fuel used", f.fuelBurnt.toLong(), 0L..200_000L, unit = "lb") { v -> ctx.apply(AirOps.field(doc, f, "Fuel used by ${doc.callsign(f)}") { fuelBurnt = v.toInt() }) }
            }

            Fold("Position", "u_fpos", startOpen = false) {
                Line("Cell (x, y)") {
                    NumBox(f.x.toLong(), 0L..4000L, width = 60.dp) { v -> ctx.apply(AirOps.field(doc, f, "Position of ${doc.callsign(f)}") { x = v.toInt() }) }
                    Text("  ", fontSize = 11.sp)
                    NumBox(f.y.toLong(), 0L..4000L, width = 60.dp) { v -> ctx.apply(AirOps.field(doc, f, "Position of ${doc.callsign(f)}") { y = v.toInt() }) }
                }
                NumLine("Altitude", (-f.z).roundToInt().toLong(), 0L..80_000L, unit = "ft") { v -> ctx.apply(AirOps.field(doc, f, "Altitude of ${doc.callsign(f)}") { z = -v.toFloat() }) }
                NumLine("Flying to", f.currentWp + 1L, 1L..maxOf(1, f.waypoints.size).toLong(), tip = "The waypoint it is flying to") { v -> ctx.apply(AirOps.field(doc, f, "Waypoint of ${doc.callsign(f)}") { currentWp = (v - 1).toInt() }) }
                Buttons { SmallButton("Place by the clock", tip = "Where the route puts it at the campaign's time") { ctx.apply(AirOps.field(doc, f, "Place ${doc.callsign(f)} by the clock") { AirOps.placeByClock(this, doc.cmp?.currentTime ?: 0) }) } }
            }

            RouteBlock(ctx, f, air = true)
            LoadoutBlock(ctx, f, sq)

            Fold("Package and squadron", "u_fpkg", startOpen = false) {
                val packages = doc.packages.filter { it.id != f.packageId && it.elements.size < 5 && AirOps.allied(doc, f.owner, it.owner) }
                ChoiceLine<CmdPackage?>("Move to", null, listOf<Pair<CmdPackage?, String>>(null to "another package…") + packages.map { it to "${com.bmscompanion.desktop.bridge.command.UnitNames.full(doc, it)} (${it.elements.size})" }) { p ->
                    if (p != null) ctx.apply(AirOps.moveToPackage(doc, f, p))
                }
                Buttons { SmallButton("Own package", tip = "A new package for this flight alone") { ctx.apply(AirOps.ownPackage(doc, f)) } }
                val squadrons = doc.squadrons.filter { it.id != f.squadronId && AirOps.allied(doc, f.owner, it.owner) && cls.flightTypeFor(it.type) != null }
                ChoiceLine<CmdSquadron?>("Squadron", null, listOf<Pair<CmdSquadron?, String>>(null to "fly for another squadron…") + squadrons.map { it to "${ctx.world.unit(it.id.toString())?.name ?: AirOps.nameOf(doc, it)} · ${cls.leadVcd(it.type)?.name.orEmpty()}" },
                    tip = "Its pilots, base and paint follow; another aircraft clears the loadout") { s -> if (s != null) ctx.apply(AirOps.assignSquadron(doc, f, s)) }
            }
            LifeBlock(ctx, f)
        }
    }

    @Composable
    private fun Seat(ctx: CmdContext, f: CmdFlight, sq: CmdSquadron?, s: Int) {
        val doc = ctx.core
        val on = f.planeStats[s] != 0
        val pilots = sq?.pilots?.mapIndexedNotNull { i, p -> if (p.status == AirOps.PILOT_FREE || i == f.pilots[s]) i to "${doc.pilotName(p.pilotId)?.trim() ?: "pilot ${p.pilotId}"} (${p.skillAndRating})" else null }.orEmpty()
        Line("Seat ${s + 1}") {
            Choice(f.pilots[s], listOf(AirOps.NO_PILOT to "No pilot") + pilots, Modifier.weight(1f), enabled = on && sq != null) { p -> ctx.apply(AirOps.setPilot(doc, f, s, p)) }
        }
        if (!on) return
        Row(Modifier.fillMaxWidth().padding(start = LABEL_W + 6.dp, end = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Choice(f.planeStats[s], PLANE_STATES.drop(1), Modifier.width(92.dp)) { v -> ctx.apply(AirOps.setSeat(doc, f, "Aircraft state", s, v) { it.planeStats }) }
            Text(" laser ", color = Hud.TextFaint, fontSize = 10.5.sp)
            NumBox(f.laserCode[s].toLong(), 1111L..1788L, width = 48.dp) { v -> ctx.apply(AirOps.setLaser(doc, f, s, v.toInt())) }
        }
        Row(Modifier.fillMaxWidth().padding(start = LABEL_W + 6.dp, end = 6.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("fuel ", color = Hud.TextFaint, fontSize = 10.5.sp)
            NumBox(f.fuelInitial[s].toLong(), 0L..200_000L, width = 64.dp) { v -> ctx.apply(AirOps.setSeat(doc, f, "Fuel", s, v.toInt()) { it.fuelInitial }) }
            if (f.ver >= 105) {
                Text(" paint ", color = Hud.TextFaint, fontSize = 10.5.sp)
                NumBox(f.texSet[s].toLong(), -1L..99L, width = 36.dp) { v -> ctx.apply(AirOps.setSeat(doc, f, "Paint", s, v.toInt()) { it.texSet }) }
            }
            if (f.ver >= 109) CheckLine("CFT", f.loadedCft[s] != 0) { c -> ctx.apply(AirOps.setSeat(doc, f, "Conformal tanks", s, if (c) 1 else 0) { it.loadedCft }) }
        }
    }

    /** The loadout of the chosen seats: one row per hardpoint, its store and count; weights below. */
    @Composable
    private fun LoadoutBlock(ctx: CmdContext, f: CmdFlight, sq: CmdSquadron?) {
        val doc = ctx.core
        val cls = UnitClasses.of(doc)
        val hps = remember(f.type) { cls.hardpoints(f.type) }
        val n = f.aircraft.coerceIn(1, 4)
        var seats by remember(f.id) { mutableStateOf((0 until n).toSet()) }
        val shown = seats.minOrNull() ?: 0
        val l: Loadout? = AirOps.loadoutOf(f, shown)
        Fold("Loadout${if (f.loadouts.size > 1) " · one per aircraft" else ""}", "u_loadout", startOpen = false) {
            if (hps.isEmpty()) { Hint("The aircraft's hardpoints were not found in the class tables."); return@Fold }
            Line("Aircraft") {
                MiniWrap {
                    MiniChip("All", seats.size == n) { seats = (0 until n).toSet() }
                    (0 until n).forEach { s -> MiniChip("${s + 1}", s in seats && seats.size < n) { seats = setOf(s) } }
                }
            }
            for (h in hps) {
                if (h.fixed != null) { if (h.fixedCount > 0) Line("HP ${h.n}") { Text("${cls.weaponName(h.fixed)} × ${h.fixedCount} (fixed)", color = Hud.TextFaint, fontSize = 11.5.sp) }; continue }
                if (!h.loadable) continue
                val wid = l?.weapons?.getOrNull(h.n) ?: 0
                val cnt = l?.counts?.getOrNull(h.n) ?: 0
                val opts = listOf(0 to "Empty") + h.allowed.map { (id, max) -> id to "${cls.weaponName(id)} (≤$max)${sq?.let { s -> " · ${s.store(id)} held" } ?: ""}" }
                Line("HP ${h.n}", tip = h.listName) {
                    Choice(if (cnt > 0) wid else 0, opts, Modifier.weight(1f)) { id -> ctx.apply(AirOps.setStore(doc, f, seats, h.n, id, if (id == 0) 0 else h.max(id))) }
                    if (cnt > 0) { Text(" ×", color = Hud.TextFaint, fontSize = 11.sp); NumBox(cnt.toLong(), 0L..h.max(wid).toLong().coerceAtLeast(0), width = 36.dp) { c -> ctx.apply(AirOps.setStore(doc, f, seats, h.n, wid, c.toInt())) } }
                }
            }
            val wt = cls.weights(f.type, l)
            Hint("Empty ${wt.empty} + fuel ${wt.internalFuel} + tanks ${wt.externalFuel} + stores ${wt.stores} = ${wt.gross} lb" + (if (wt.max > 0) " of ${wt.max}" else "") + " · drag ${wt.drag}")
            if (wt.over) Hint("Heavier than the aircraft's maximum.")
            Buttons {
                SmallButton("Clear", tip = "Only the aircraft's own gun") { ctx.apply(AirOps.clearLoadout(doc, f)) }
                SmallButton("Same for all", enabled = f.loadouts.size > 1, tip = "Aircraft ${shown + 1}'s loadout for every aircraft") { ctx.apply(AirOps.loadoutToAll(doc, f, shown)) }
            }
        }
    }
}
