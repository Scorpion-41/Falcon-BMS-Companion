package com.bmscompanion.desktop.ui.command.inspectors

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.command.AirOps
import com.bmscompanion.desktop.bridge.command.CmdFlight
import com.bmscompanion.desktop.bridge.command.CmdPackage
import com.bmscompanion.desktop.bridge.command.CommandSave
import com.bmscompanion.desktop.bridge.command.UnitClasses
import com.bmscompanion.desktop.bridge.command.UnitLife
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdInspector
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.Fold
import com.bmscompanion.desktop.ui.command.teamInk

/** A flight's take-off (waypoint 0's departure) and time on target (its first mission waypoint's arrival). */
object FlightTimes {
    fun takeoff(f: CmdFlight): Long? = f.waypoints.firstOrNull()?.depart?.takeIf { it > 0 }
    fun tot(f: CmdFlight): Long? = f.waypoints.firstOrNull { it.action >= 9 }?.arrive ?: f.timeOnTarget.takeIf { it > 0 }
    fun landing(f: CmdFlight): Long? = f.waypoints.lastOrNull { it.action == AirOps.ACTION_LAND && it.arrive > 0 }?.arrive
    fun hhmm(ms: Long?): String = ms?.let { CampaignArchive.clock(it).substringBeforeLast(':') } ?: ""
    fun mission(doc: CommandSave, m: Int): String = doc.string(CampaignArchive.MISSION_STRINGS + m)?.trim() ?: "mission $m"
}

/** A package: its flights with their timing (a click opens one), owner, shifting every flight's times, clone, delete. */
object PackageInspector : CmdInspector {
    override fun accepts(ref: CmdRef): Boolean = ref.kind == CmdKind.PACKAGE

    @Composable
    override fun Content(ctx: CmdContext, refs: List<CmdRef>) {
        watchEdits()
        val p = ctx.unitOf(refs.first()) as? CmdPackage ?: run { Hint("This package is no longer in the file."); return }
        val doc = ctx.core
        val flights = doc.flightsOf(p)
        val cls = UnitClasses.of(doc)
        InspectorColumn {
            val team = ctx.world.team(p.owner)
            InspectorHead(com.bmscompanion.desktop.bridge.command.UnitNames.full(doc, p), "Package · ${flights.size} flight${if (flights.size == 1) "" else "s"}",
                "${FlightTimes.mission(doc, p.request.mission)} · ${team?.name ?: "team ${p.owner}"} · id ${p.id}", ink = team?.let { teamInk(it.colour) } ?: Hud.Text, flag = ctx.teamFlag(p.owner))
            IssuesBlock(ctx, p)
            Fold("Flights", "u_pkgflights", startOpen = true) {
                MiniTable(
                    listOf("Flight" to 78.dp, "Mission" to 74.dp, "Aircraft" to 70.dp, "T/O" to 52.dp, "TOT" to 52.dp),
                    flights.map { f -> listOf(com.bmscompanion.desktop.bridge.command.UnitNames.callsign(doc, f), FlightTimes.mission(doc, f.mission), cls.leadVcd(f.type)?.name ?: "", FlightTimes.hhmm(FlightTimes.takeoff(f)), FlightTimes.hhmm(FlightTimes.tot(f))) },
                ) { i -> ctx.focus(refOf(flights[i])) }
                if (p.elements.size > flights.size) Hint("${p.elements.size - flights.size} of its elements are not flights of the file.")
                Buttons {
                    val first = flights.firstOrNull()
                    val sq = first?.let { doc.squadronOf(it) }
                    SmallButton("Add a flight", enabled = sq != null && p.elements.size < 5, tip = "A two-ship of the first flight's squadron in this package") {
                        if (sq != null) ctx.applyAndSelectNew(UnitLife.addFlight(doc, sq, 2, first.mission, FlightTimes.takeoff(first) ?: (doc.cmp?.currentTime ?: 0), p), com.bmscompanion.desktop.bridge.command.UnitKind.FLIGHT)
                    }
                }
            }
            Fold("Package", "u_pkg", startOpen = true) {
                ChoiceLine("Owner", p.owner, ctx.teamOptions(), lead = { ctx.teamFlag(it) }) { t -> ctx.apply(AirOps.field(doc, p, "Owner of package ${p.campId}") { owner = t }) }
                NumLine("Name number", p.nameId.toLong(), 0L..65_000L) { v -> ctx.apply(AirOps.field(doc, p, "Name of package ${p.campId}") { nameId = v.toInt() }) }
                ClockLine("Time on target", p.request.tot, tip = "The time on target BMS planned the package for") { v -> ctx.apply(AirOps.field(doc, p, "Time on target of package ${p.campId}") { request.tot = v }) }
                ShiftLine(ctx, "every flight") { ms -> AirOps.shiftTimes(doc, flights, ms, "Shift the times of package ${p.campId}", p) }
            }
            LifeBlock(ctx, p)
            Hint("Deleting a package deletes its flights.")
        }
    }
}
