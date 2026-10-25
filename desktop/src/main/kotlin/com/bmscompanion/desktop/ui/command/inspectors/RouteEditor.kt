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
import com.bmscompanion.desktop.bridge.command.CmdObjective
import com.bmscompanion.desktop.bridge.command.CmdUnit
import com.bmscompanion.desktop.bridge.command.CmdWaypoint
import com.bmscompanion.desktop.bridge.command.UnitClasses
import com.bmscompanion.desktop.ui.command.CheckLine
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.Fold
import com.bmscompanion.desktop.ui.command.MiniChip
import com.bmscompanion.desktop.ui.command.MiniWrap
import kotlin.math.roundToInt

// A route: its table (a click selects a waypoint, which the map shows and lets you drag) and the selected waypoint's
// fields. Times are the master: a leg's speed is worked out from them, and setting a speed sets the arrival. With
// "Time the route again" on, a change of time, place or speed moves the waypoints around it, keeping the locked ones.

/** The waypoint shown: the selection's when it is one of [u]'s waypoints. */
fun CmdContext.selectedWaypoint(u: CmdUnit): Int? =
    selection.primary?.takeIf { it.kind == CmdKind.WAYPOINT && it.id == u.id.toString() }?.sub?.takeIf { it in u.waypoints.indices }

fun CmdContext.selectWaypoint(u: CmdUnit, i: Int?) {
    if (i == null) selection.set(refOf(u)) else { selection.set(CmdRef(CmdKind.WAYPOINT, u.id.toString(), i)); focus(CmdRef(CmdKind.WAYPOINT, u.id.toString(), i)) }
}

private fun hhmm(ms: Long): String = AirOps.clock(ms).let { it.substringBeforeLast(':') }

/** The route of [u] and the selected waypoint's editor; [air]: a flight (altitudes, speeds, targets, formations). */
@Composable
fun RouteBlock(ctx: CmdContext, u: CmdUnit, air: Boolean) {
    val ws = u.waypoints
    val sel = ctx.selectedWaypoint(u)
    Fold("Route · ${ws.size} waypoint${if (ws.size == 1) "" else "s"}", if (air) "u_route_air" else "u_route", startOpen = true) {
        val rows = ws.mapIndexed { i, w ->
            val kts = AirOps.legKts(ws, i)
            listOf(
                "${i + 1}", AirOps.ACTIONS.firstOrNull { it.first == w.action }?.second ?: "${w.action}",
                if (air) "${w.gridZ * 10}" else cellText(w.gridX, w.gridY),
                hhmm(w.arrive) + if (w.hasDepart && w.depart != w.arrive) "…" else "",
                kts?.roundToInt()?.toString() ?: "",
                (if (AirOps.timeLocked(w)) "T" else "") + (if (AirOps.speedLocked(w)) "S" else ""),
            )
        }
        val now = ctx.core.cmp?.currentTime ?: 0L
        MiniTable(
            listOf("#" to 24.dp, "Action" to 82.dp, (if (air) "Alt ft" else "Cell") to 58.dp, "Arrive" to 82.dp, "Kts" to 40.dp, "Lock" to 34.dp),
            rows, selected = sel ?: -1, maxHeight = 220.dp,
            inks = ws.mapIndexed { i, w -> if (i > 0 && w.arrive in 1 until ws[i - 1].depart) Hud.Red else if (w.depart < now) Hud.TextFaint else null },
        ) { i -> ctx.selectWaypoint(u, if (sel == i) null else i) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CheckLine("Time the route again after a change", UnitPrefs.retime) { UnitPrefs.chooseRetime(it) }
        }
        Buttons {
            SmallButton("Add after", tip = "A waypoint after the selected one (or at the end), halfway along the leg") {
                val after = sel ?: (if (air && ws.size >= 2) ws.size - 2 else ws.size - 1)
                if (ctx.apply(AirOps.insertWaypoint(ctx.core, u, after)) == null) ctx.selectWaypoint(u, after + 1)
            }
            SmallButton("Delete", enabled = sel != null, danger = true) { sel?.let { if (ctx.apply(AirOps.deleteWaypoint(ctx.core, u, it)) == null) ctx.selectWaypoint(u, null) } }
            SmallButton("Lock times", tip = "Lock (or, when all are locked, unlock) every waypoint's time") {
                val all = ws.isNotEmpty() && ws.all { AirOps.timeLocked(it) }
                ctx.apply(AirOps.lockAll(ctx.core, u, AirOps.TIME_LOCK, !all))
            }
            if (air) SmallButton("Lock speeds") {
                val all = ws.drop(1).isNotEmpty() && ws.drop(1).all { AirOps.speedLocked(it) }
                ctx.apply(AirOps.lockAll(ctx.core, u, AirOps.SPEED_LOCK, !all))
            }
        }
        if (sel != null) WaypointFields(ctx, u, sel, air)
        else Hint("Click a waypoint (here or on the map) to edit it; drag it on the map with the Move tool.")
    }
}

@Composable
private fun WaypointFields(ctx: CmdContext, u: CmdUnit, i: Int, air: Boolean) {
    val w = u.waypoints[i]
    val doc = ctx.core
    val retime = UnitPrefs.retime
    Text("WAYPOINT ${i + 1}", color = Hud.Amber, fontSize = 10.5.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp))
    Line("Cell (x, y)") {
        NumBox(w.gridX.toLong(), 0L..4000L, width = 60.dp) { ctx.apply(AirOps.moveWaypoint(doc, u, i, it.toInt(), w.gridY, retime)) }
        Text("  ", fontSize = 11.sp)
        NumBox(w.gridY.toLong(), 0L..4000L, width = 60.dp) { ctx.apply(AirOps.moveWaypoint(doc, u, i, w.gridX, it.toInt(), retime)) }
    }
    if (air) NumLine("Altitude", w.gridZ * 10L, 0L..80_000L, unit = "ft") { v -> ctx.apply(AirOps.waypoint(doc, u, i, "Altitude") { gridZ = (v / 10).toInt() }) }
    ChoiceLine("Action", w.action, AirOps.ACTIONS) { a -> ctx.apply(AirOps.setAction(doc, u, i, a)) }
    if (air) ChoiceLine("En route", w.routeAction, AirOps.ACTIONS, tip = "What the flight does on the leg into this waypoint") { a -> ctx.apply(AirOps.waypoint(doc, u, i, "Route action") { routeAction = a }) }
    Line("Formation") {
        Choice(AirOps.formation(w), AirOps.FORMATIONS.mapIndexed { k, n -> k to n }, Modifier.weight(1f)) { f -> ctx.apply(AirOps.waypoint(doc, u, i, "Formation") { formation = AirOps.formationByte(f, AirOps.spacing(this)) }) }
        if (air) {
            Text("  spacing ", color = Hud.TextFaint, fontSize = 11.sp)
            NumBox(AirOps.spacing(w).toLong(), -4L..4L, width = 40.dp) { s -> ctx.apply(AirOps.waypoint(doc, u, i, "Spacing") { formation = AirOps.formationByte(AirOps.formation(this), s.toInt()) }) }
        }
    }
    Line("Arrive", tip = "Day and time, \"D2 06:30\" or \"2 06:30:00\"") {
        EditBox(AirOps.clock(w.arrive), Modifier.weight(1f), mono = true) { t ->
            val ms = AirOps.parseClock(t) ?: return@EditBox "A day and a time: D2 06:30."
            ctx.apply(AirOps.setArrival(doc, u, i, ms, retime))
        }
    }
    Line("Station", tip = "Time spent at the waypoint (a hold, a CAP): minutes") {
        NumBox((w.depart - w.arrive) / 60_000, 0L..24 * 60L, width = 60.dp) { m -> ctx.apply(AirOps.setStation(doc, u, i, m * 60_000)) }
        Text("  min · leaves ${hhmm(w.depart)}", color = Hud.TextFaint, fontSize = 11.sp)
    }
    if (i > 0) Line("Speed in", tip = "Ground speed of the leg into this waypoint, from the times; typing one sets the arrival") {
        val kts = AirOps.legKts(u.waypoints, i)
        NumBox(kts?.roundToInt()?.toLong() ?: 0L, 30L..2000L, width = 60.dp) { v -> ctx.apply(AirOps.setLegSpeed(doc, u, i, v.toDouble())) }
        Text("  kt · ${"%.1f".format(AirOps.legNm(u.waypoints, i))} nm" + (kts?.let { " · CAS ${AirOps.casOf(it, w.gridZ * 10.0).roundToInt()}" } ?: ""), color = Hud.TextFaint, fontSize = 11.sp)
    }
    Line("Locks") {
        CheckLine("time", AirOps.timeLocked(w)) { on -> ctx.apply(AirOps.waypoint(doc, u, i, if (on) "Lock time" else "Unlock time") { flags = if (on) flags or AirOps.TIME_LOCK else flags and AirOps.TIME_LOCK.inv() }) }
    }
    if (air && i > 0) Line("") { CheckLine("speed of the leg in", AirOps.speedLocked(w)) { on -> ctx.apply(AirOps.waypoint(doc, u, i, if (on) "Lock speed" else "Unlock speed") { flags = if (on) flags or AirOps.SPEED_LOCK else flags and AirOps.SPEED_LOCK.inv() }) } }
    Fold("Flags 0x${w.flags.toString(16)}", "u_wpflags", startOpen = false) {
        AirOps.WP_FLAGS.filter { it.first != AirOps.TIME_LOCK && it.first != AirOps.SPEED_LOCK }.forEach { (bit, name) ->
            CheckLine(name, w.flags and bit != 0L) { on -> ctx.apply(AirOps.waypoint(doc, u, i, "Flag $name") { flags = if (on) flags or bit else flags and bit.inv() }) }
        }
    }
    TargetFields(ctx, u, i, w, air)
}

@Composable
private fun TargetFields(ctx: CmdContext, u: CmdUnit, i: Int, w: CmdWaypoint, air: Boolean) {
    val doc = ctx.core
    val near = remember(doc.revision, u.id, i) { doc.near(w.gridX, w.gridY, 12.0).take(40) }
    val current: Any? = if (w.hasTarget) doc.objective(w.targetId) ?: doc.unit(w.targetId) else null
    val options = listOf<Pair<Any?, String>>(null to "None") +
        (if (current != null && near.none { it.first === current }) listOf(current to ctx.nameOfAny(current)) else emptyList()) +
        near.map { (v, d) -> v to "${ctx.nameOfAny(v)} · ${"%.1f".format(d)} nm" }
    Line("Target", tip = "An objective or a ground unit within 12 nm of the waypoint") {
        Choice(current, options, Modifier.weight(1f)) { v ->
            val id = when (v) { is CmdObjective -> v.id; is CmdUnit -> v.id; else -> null }
            ctx.apply(AirOps.setTarget(doc, u, i, id, 255, null))
        }
    }
    if (current is CmdObjective && air && u is CmdFlight) {
        val buildings = remember(current.id) { UnitClasses.of(doc).buildings(current) }
        var seat by remember(u.id, i) { mutableStateOf(0) }
        Line("Seat") { MiniWrap { (0 until 4).forEach { s -> MiniChip("${s + 1}", seat == s) { seat = s } } } }
        val d = w.designated.getOrNull(seat)
        val bOptions = listOf(255 to "The whole objective") + buildings.filter { it.third > 0 || it.first == d?.building }.map { (n, name, v) -> n to "$name${if (v == 0) " (no value)" else ""}" }
        ChoiceLine("Building", if (d?.id == current.id) d.building else 255, bOptions, tip = "The building seat ${seat + 1} is given (its designated target)") { b ->
            ctx.apply(AirOps.setTarget(doc, u, i, current.id, b, seat))
        }
        Hint("Designated: " + w.designated.mapIndexed { s, x -> "${s + 1}: " + if (x.id.isNone) "—" else if (x.building == 255) "whole" else (buildings.getOrNull(x.building)?.second ?: "#${x.building}") }.joinToString(" · "))
    }
    if (current == null && w.hasTarget && !w.targetId.isNone) Hint("Its target ${w.targetId} is not in the file.")
}

/** "+00:30", "-1 02:00": a shift for a flight's or a package's times. */
@Composable
fun ShiftLine(ctx: CmdContext, label: String, run: (Long) -> com.bmscompanion.desktop.bridge.command.CommandEdit) {
    Line("Shift times", tip = "Moves every time by this much: \"+00:30\", \"-0:15\", \"+1 02:00\" (a day and hours) or minutes") {
        EditBox("+00:00", Modifier.width(90.dp), mono = true, resetAfter = true) { t ->
            val ms = AirOps.parseShift(t) ?: return@EditBox "A shift: +00:30, -0:15 or +1 02:00."
            ctx.apply(run(ms))
        }
        Text("  $label", color = Hud.TextFaint, fontSize = 11.sp)
    }
}

/** A campaign time edited as "D2 06:30". */
@Composable
fun ClockLine(label: String, ms: Long, tip: String? = null, commit: (Long) -> String?) = Line(label, tip) {
    EditBox(if (ms > 0) CampaignArchive.clock(ms) else "", Modifier.weight(1f), mono = true) { t ->
        val v = AirOps.parseClock(t) ?: return@EditBox "A day and a time: D2 06:30."
        commit(v)
    }
}
