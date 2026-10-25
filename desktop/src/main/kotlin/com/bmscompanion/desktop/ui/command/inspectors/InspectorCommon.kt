package com.bmscompanion.desktop.ui.command.inspectors

import com.bmscompanion.desktop.ui.command.teamLead
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.command.AirOps
import com.bmscompanion.desktop.bridge.command.CmdObjective
import com.bmscompanion.desktop.bridge.command.CmdUnit
import com.bmscompanion.desktop.bridge.command.CommandSave
import com.bmscompanion.desktop.bridge.command.GroundOps
import com.bmscompanion.desktop.bridge.command.UnitChecks
import com.bmscompanion.desktop.bridge.command.UnitClasses
import com.bmscompanion.desktop.bridge.command.UnitKind
import com.bmscompanion.desktop.bridge.command.UnitLife
import com.bmscompanion.desktop.bridge.command.VuId
import com.bmscompanion.desktop.ui.command.CmdContext
import com.bmscompanion.desktop.ui.command.CmdKind
import com.bmscompanion.desktop.ui.command.CmdRef
import com.bmscompanion.desktop.ui.command.Fold
import com.bmscompanion.desktop.ui.command.MiniChip
import com.bmscompanion.desktop.ui.command.MiniWrap
import com.bmscompanion.desktop.ui.command.SmallNote
import java.util.WeakHashMap
import kotlin.math.hypot

// What every unit inspector shares: finding the record behind a ref, the teams, the unit's issues with their Fix,
// the roster slots, a route's table, and the clone / delete row.

fun CmdContext.unitOf(ref: CmdRef): CmdUnit? = core.unit(CampaignArchive.VuId.parse(ref.id))

fun refOf(u: CmdUnit): CmdRef = CmdRef(kindOf(u.kind), u.id.toString())

fun kindOf(k: UnitKind): CmdKind = when (k) {
    UnitKind.FLIGHT -> CmdKind.FLIGHT; UnitKind.PACKAGE -> CmdKind.PACKAGE; UnitKind.SQUADRON -> CmdKind.SQUADRON
    UnitKind.BATTALION -> CmdKind.BATTALION; UnitKind.BRIGADE -> CmdKind.BRIGADE; UnitKind.TASKFORCE -> CmdKind.TASKFORCE
}

fun objRef(o: CmdObjective): CmdRef = CmdRef(CmdKind.OBJECTIVE, o.id.toString())

/** Runs an edit that adds units; the first new one of [kind] is selected and shown. */
fun CmdContext.applyAndSelectNew(edit: com.bmscompanion.desktop.bridge.command.CommandEdit, kind: UnitKind): String? {
    val before = core.units.mapTo(HashSet()) { it.id }
    val why = apply(edit)
    if (why == null) core.units.firstOrNull { it.kind == kind && it.id !in before }?.let { focus(refOf(it)) }
    return why
}

/** The teams as choices (every one the header lists, those in use first). */
fun CmdContext.teamOptions(): List<Pair<Int, String>> = world.teams.sortedBy { if (it.inUse) 0 else 1 }.map { it.n to it.name }

/** A team's flag (or colour swatch) before its name in a choice, a head or a fact row; none for "every team" (null). */
fun CmdContext.teamFlag(n: Int?): (@Composable () -> Unit)? = n?.let { world.teamLead(it) }

/** The unit editor's per-device choices. */
object UnitPrefs {
    /** time the route again after a waypoint's time, place or speed changes */
    var retime by mutableStateOf(Repo.getInt("cmd_retime", 1) == 1)
        private set
    fun chooseRetime(v: Boolean) { retime = v; Repo.putInt("cmd_retime", if (v) 1 else 0) }
}

/** The unit issues of a file, worked out once per change. */
object UnitIssues {
    private val cache = WeakHashMap<CommandSave, Pair<Int, List<UnitChecks.Issue>>>()
    fun of(doc: CommandSave): List<UnitChecks.Issue> = synchronized(cache) {
        cache[doc]?.takeIf { it.first == doc.revision }?.second ?: runCatching { UnitChecks.run(doc) }.getOrDefault(emptyList()).also { cache[doc] = doc.revision to it }
    }
    fun byUnit(doc: CommandSave): Map<VuId, List<UnitChecks.Issue>> = of(doc).filter { it.unit != null }.groupBy { it.unit!! }
}

/** The issues of unit [u], each with its Fix. */
@Composable
fun IssuesBlock(ctx: CmdContext, u: CmdUnit) {
    val issues = UnitIssues.byUnit(ctx.core)[u.id].orEmpty()
    if (issues.isEmpty()) return
    issues.forEach { i ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(i.text, color = if (i.severity == UnitChecks.Severity.ERROR) Hud.Red else Hud.Amber, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
            val fix = i.fix
            if (fix != null) { Spacer(Modifier.width(4.dp)); SmallButton("Fix", tip = i.fixLabel) { ctx.apply(fix(ctx.core)) } }
        }
    }
}

/** The 16 slots of a unit's roster: vehicle, count (a chip per number), and the full roster. */
@Composable
fun RosterBlock(ctx: CmdContext, u: CmdUnit, title: String = "Roster") {
    val cls = UnitClasses.of(ctx.core)
    Fold("$title · ${UnitClasses.rosterTotal(u.roster)} of ${UnitClasses.rosterTotal(cls.maxRoster(u.type))}", "u_roster", startOpen = false) {
        for (k in 0 until 16) {
            val max = cls.slotMax(u.type, k)
            if (max == 0 && UnitClasses.slotCount(u.roster, k) == 0) continue
            Line("${k + 1}. ${cls.slotName(u.type, k) ?: "slot"}") {
                MiniWrap { (0..maxOf(max, UnitClasses.slotCount(u.roster, k))).forEach { n -> MiniChip("$n", UnitClasses.slotCount(u.roster, k) == n) { ctx.apply(GroundOps.setRosterSlot(ctx.core, u, k, n)) } } }
            }
        }
        Buttons { SmallButton("Full roster", tip = "Every slot at its class's count") { ctx.apply(GroundOps.fullRoster(ctx.core, u)) } }
    }
}

/** Clone and delete, with the children ([withChildren]: a squadron's flights, a brigade's battalions). */
@Composable
fun LifeBlock(ctx: CmdContext, u: CmdUnit, childrenWord: String? = null) {
    var both by remember(u.id) { mutableStateOf(false) }
    AdvancedBlock(ctx, u)
    Buttons {
        SmallButton("Clone", tip = "A copy with new ids beside it (undoable)") { ctx.applyAndSelectNew(UnitLife.clone(ctx.core, u), u.kind) }
        SmallButton("Delete", danger = true, tip = "Delete it (Undo brings it back)") {
            if (ctx.apply(UnitLife.delete(ctx.core, u, withChildren = both)) == null) ctx.selection.clear()
        }
        if (childrenWord != null) com.bmscompanion.desktop.ui.command.CheckLine("with its $childrenWord", both) { both = it }
    }
}

/** Objectives and units within [nm] of a cell, nearest first (for a target or an objective to send to). */
fun CommandSave.near(gx: Int, gy: Int, nm: Double, objectives: Boolean = true, units: Boolean = true): List<Pair<Any, Double>> {
    val out = ArrayList<Pair<Any, Double>>()
    val cells = nm * 1.852
    if (objectives) this.objectives.forEach { o -> val (x, y) = GroundOps.objCell(o); val d = hypot((x - gx).toDouble(), (y - gy).toDouble()); if (d <= cells) out += o to d / 1.852 }
    if (units) this.units.forEach { u -> if (u.kind == UnitKind.BATTALION || u.kind == UnitKind.TASKFORCE || u.kind == UnitKind.BRIGADE) { val d = hypot((u.x - gx).toDouble(), (u.y - gy).toDouble()); if (d <= cells) out += u to d / 1.852 } }
    return out.sortedBy { it.second }
}

fun CmdContext.nameOfAny(v: Any?): String = when (v) {
    is CmdObjective -> GroundOps.objName(core, v)
    is CmdUnit -> world.unit(v.id.toString())?.name ?: AirOps.nameOf(core, v)
    else -> "—"
}

/** "N 1,234,567 E …" is too long here: a cell as "x, y". */
fun cellText(x: Int, y: Int): String = "$x, $y"

@Composable
fun Hint(text: String) = SmallNote(text)
