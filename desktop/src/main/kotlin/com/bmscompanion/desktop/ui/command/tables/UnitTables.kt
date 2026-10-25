package com.bmscompanion.desktop.ui.command.tables

import com.bmscompanion.desktop.ui.command.teamLead
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import com.bmscompanion.desktop.ui.command.CMD_BAR
import com.bmscompanion.desktop.ui.command.CmdHBar
import com.bmscompanion.desktop.ui.command.CmdVBar
import com.bmscompanion.desktop.ui.command.shiftWheel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.screens.editor.Rule
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.bridge.CampaignArchive
import com.bmscompanion.desktop.bridge.command.CmdFlight
import com.bmscompanion.desktop.bridge.command.CmdPackage
import com.bmscompanion.desktop.bridge.command.UnitChecks
import com.bmscompanion.desktop.bridge.command.UnitNames
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
import com.bmscompanion.desktop.ui.command.RefMenuItems
import com.bmscompanion.desktop.ui.command.Strip
import com.bmscompanion.desktop.ui.command.VRule
import com.bmscompanion.desktop.ui.command.ViewUnit
import com.bmscompanion.desktop.ui.command.inspectors.FlightTimes
import com.bmscompanion.desktop.ui.command.inspectors.UnitIssues
import com.bmscompanion.desktop.ui.command.inspectors.refOf
import com.bmscompanion.desktop.ui.command.teamInk

/** The search box of a table strip. */
@Composable
fun FilterBox(text: String, hint: String, width: Dp = 180.dp, onChange: (String) -> Unit) {
    BasicTextField(
        text, onChange, singleLine = true,
        textStyle = TextStyle(color = Hud.Text, fontSize = 12.sp), cursorBrush = SolidColor(Hud.Amber),
        modifier = Modifier.width(width).clip(RoundedCornerShape(3.dp)).background(Hud.Bg.copy(alpha = 0.6f)).border(1.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
        decorationBox = { inner -> if (text.isEmpty()) Text(hint, color = Hud.TextFaint, fontSize = 12.sp); inner() },
    )
}

/**
 * The Units page's table: every unit of the file, filtered by kind, team, a text (name, class, aircraft) and parent
 * (the package, squadron or brigade selected: its flights or battalions). A row with an issue BMS would trip over is
 * red, one with a warning amber (the Issues column says what; the inspector has the Fix). The columns are a campaign
 * designer's usual list — Nr, Name (type and role, no callsign), Type, Squad Patch (BMS's own picture), Callsign,
 * Flag, Info (the team), Home, Elements, Time, ID, CampId, UHF, VHF, InActive, NoPlan — then Parent, Wpts, Grid,
 * State and Issues; Nr and Name stay in place while the rest scroll sideways ([UnitRowFacts]).
 */
object UnitsTable : CmdTable {
    override val page = CmdPage.UNITS

    @Composable
    override fun Content(ctx: CmdContext) {
        @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
        val world = ctx.world
        var kind by remember { mutableStateOf<CmdKind?>(null) }
        val teams = remember { mutableStateListOf<Int>() }
        var text by remember { mutableStateOf("") }
        var ofParent by remember { mutableStateOf(false) }
        var issuesOnly by remember { mutableStateOf(false) }
        val issues = UnitIssues.byUnit(ctx.core)
        val parent = ctx.selection.primary?.takeIf { it.kind in setOf(CmdKind.PACKAGE, CmdKind.SQUADRON, CmdKind.BRIGADE) }
        val parentId = parent?.id
        val rows = remember(world, kind, teams.toList(), text, ofParent, parentId, issuesOnly) {
            val q = text.trim()
            world.units.filter { u ->
                (kind == null || u.kind == kind) &&
                    (teams.isEmpty() || u.owner in teams) &&
                    (q.isEmpty() || u.name.contains(q, true) || u.cls.contains(q, true) || (u.aircraft?.contains(q, true) == true) || u.campId.toString() == q) &&
                    (!ofParent || parentId == null || u.ref.id == parentId || u.parent == parentId || u.squadron == parentId) &&
                    (!issuesOnly || issues.containsKey(u.id))
            }
        }
        Column(Modifier.fillMaxSize()) {
            Strip {
                MiniChip("All", kind == null) { kind = null }
                listOf(CmdKind.SQUADRON, CmdKind.FLIGHT, CmdKind.PACKAGE, CmdKind.BATTALION, CmdKind.BRIGADE, CmdKind.TASKFORCE).forEach { k -> MiniChip(k.label + "s", kind == k) { kind = k } }
                VRule()
                world.teams.filter { it.inUse }.forEach { t -> MiniChip(t.name.take(12), t.n in teams, tint = teamInk(t.colour), lead = { com.bmscompanion.desktop.ui.command.TeamFlagLead(t.name, t.colour) }) { if (t.n in teams) teams.remove(t.n) else teams.add(t.n) } }
                VRule()
                FilterBox(text, "Name, callsign, aircraft, CampId") { text = it }
                MiniChip(parent?.let { "Of ${world.nameOf(it).take(18)}" } ?: "Of the selection", ofParent && parent != null, enabled = parent != null) { ofParent = !ofParent }
                MiniChip("With issues (${issues.size})", issuesOnly) { issuesOnly = !issuesOnly }
                Box(Modifier.weight(1f))
                DeskButton("Export CSV…", tip = "Every unit of the file as a CSV list (names, types, teams, strength, bases), where you pick") { com.bmscompanion.desktop.ui.command.inspectors.exportCsv(ctx, objectives = false) }
                DeskButton("Bulk…", tip = "Operations on many units at once") { UnitDialogs.open(UnitDialogs.Bulk) }
            }
            val info = remember(world) { UnitRowFacts.of(ctx, world) }
            CmdGrid(
                rows = rows,
                frozen = 2,
                cols = listOf(
                    GridCol<ViewUnit>("Nr", 46.dp, numeric = true, sort = { info[it.ref.id]?.nr ?: -1 }) { u -> info[u.ref.id]?.nr?.toString().orEmpty() },
                    GridCol("Name", 230.dp, sort = { info[it.ref.id]?.name?.lowercase() }) { u -> info[u.ref.id]?.name ?: u.shortName },
                    GridCol("Type", 170.dp) { u -> info[u.ref.id]?.type ?: u.kind.label },
                    GridCol("Squad Patch", 84.dp, sort = { info[it.ref.id]?.patch ?: -1 }, lead = { u -> info[u.ref.id]?.patch?.let { n -> patchLead(ctx, n) } }) { "" },
                    GridCol("Callsign", 130.dp) { u -> info[u.ref.id]?.callsign.orEmpty() },
                    GridCol("Flag", 44.dp, sort = { it.owner }, lead = { u -> world.teamLead(u.owner) }) { "" },
                    GridCol("Info", 130.dp, sort = { it.owner }, ink = { u -> world.team(u.owner)?.let { teamInk(it.colour) } }) { u -> "${world.team(u.owner)?.name ?: "Team"} (${u.owner})" },
                    GridCol("Home", 200.dp) { u -> info[u.ref.id]?.home.orEmpty() },
                    GridCol("Elements", 120.dp, sort = { elementCount(ctx, it) }) { elements(ctx, it) },
                    GridCol("Time", 78.dp, sort = { info[it.ref.id]?.time ?: Long.MAX_VALUE }) { u -> info[u.ref.id]?.time?.let { CampaignArchive.clock(it).substringBeforeLast(':') }.orEmpty() },
                    GridCol("ID", 84.dp, sort = { it.id.num }) { it.id.toString() },
                    GridCol("CampId", 66.dp, numeric = true, sort = { it.campId }) { "${it.campId}" },
                    GridCol("UHF", 72.dp, numeric = true) { u -> info[u.ref.id]?.uhf.orEmpty() },
                    GridCol("VHF", 72.dp, numeric = true) { u -> info[u.ref.id]?.vhf.orEmpty() },
                    GridCol("InActive", 70.dp) { u -> if (u.inactive) "Yes" else "" },
                    GridCol("NoPlan", 64.dp) { u -> if (u.unitFlags and 0x10 != 0) "Yes" else "" },
                    GridCol("Parent", 160.dp) { u -> (u.parent ?: u.squadron)?.let { world.unit(it)?.name }.orEmpty() },
                    GridCol("Wpts", 50.dp, numeric = true, sort = { it.route.size }) { "${it.route.size}" },
                    GridCol("Grid", 90.dp, sort = { it.east }) { "${(it.east / CampaignArchive.FEET_PER_CELL).toInt()}, ${(it.north / CampaignArchive.FEET_PER_CELL).toInt()}" },
                    GridCol("State", 90.dp) { u -> listOfNotNull(if (u.dead) "destroyed" else null, if (u.moving) "moving" else null).joinToString(", ") },
                    GridCol("Issues", 320.dp) { u -> issues[u.id]?.joinToString(" · ") { it.text }.orEmpty() },
                ),
                key = { it.ref.id },
                selected = { it.ref in ctx.selection },
                onClick = { u, add -> if (add) ctx.selection.toggle(u.ref) else ctx.focus(u.ref) },
                scrollTo = ctx.selection.primary?.id,
                rowInk = { u -> issues[u.id]?.let { l -> if (l.any { it.severity == UnitChecks.Severity.ERROR }) Hud.Red else Hud.Amber } },
                rowMenu = { u, close -> RefMenuItems(ctx, if (u.ref in ctx.selection) ctx.selection.refs else listOf(u.ref), u.ref, close) },
            )
        }
    }
}

/** How many a unit holds, for the Units table's Elements column (aircraft, flights, battalions, vehicles). */
internal fun elementCount(ctx: CmdContext, u: ViewUnit): Int = when (u.kind) {
    CmdKind.FLIGHT -> (ctx.core.unit(u.id) as? CmdFlight)?.aircraft ?: 0
    CmdKind.PACKAGE, CmdKind.BRIGADE -> u.elements.size
    else -> com.bmscompanion.desktop.bridge.command.CampaignOps.rosterTotal(u.roster)
}

internal fun elements(ctx: CmdContext, u: ViewUnit): String {
    val n = elementCount(ctx, u)
    return when (u.kind) {
        CmdKind.FLIGHT -> "$n Aircraft"
        CmdKind.PACKAGE -> "$n Flight${if (n == 1) "" else "s"}"
        CmdKind.BRIGADE -> "$n Battalion${if (n == 1) "" else "s"}"
        CmdKind.SQUADRON -> {
            val fl = ctx.core.flights.count { it.squadronId == u.id }
            "$n AC" + if (fl > 0) " / $fl Flt${if (fl == 1) "" else "s"}" else ""
        }
        CmdKind.TASKFORCE -> "$n Ship${if (n == 1) "" else "s"}"
        else -> "$n Vehicles"
    }
}

/** A Squad Patch cell: the squadron's patch from BMS's art, 20 dp. */
private fun patchLead(ctx: CmdContext, n: Int): @Composable () -> Unit = { SquadPatch(ctx.core, n, 20.dp) }

/**
 * What the Units table shows of a unit beyond the map's picture of it, worked out once per edit: its place in the
 * file's unit list (Nr), its type-and-role name and its callsign apart ([UnitNames.tableName], [UnitNames.callsignText]),
 * the kind with a flight's or package's mission, the squadron patch (a flight shows its squadron's), its home (a
 * squadron's base, a flight's squadron at its base, a battalion's brigade), its first time (a flight's take-off, a
 * package's first, a ground unit's first departure) and a flight's radios from the theater's RadioMap.
 */
internal class UnitRowFacts(
    val nr: Int, val name: String, val type: String, val patch: Int?, val callsign: String, val home: String,
    val time: Long?, val uhf: String, val vhf: String,
) {
    companion object {
        fun of(ctx: CmdContext, world: com.bmscompanion.desktop.ui.command.CmdWorld): Map<String, UnitRowFacts> = runCatching {
            val doc = ctx.core
            val out = HashMap<String, UnitRowFacts>()
            fun baseName(sq: com.bmscompanion.desktop.bridge.command.CmdSquadron): String =
                if (sq.airbaseId.isNone) "Off map" else sq.airbaseId.toString().let { k -> world.objective(k)?.name ?: world.unit(k)?.shortName ?: "" }
            doc.units.forEachIndexed { i, u ->
                runCatching {
                    val type = when (u) {
                        is CmdFlight -> "Flight - " + FlightTimes.mission(doc, u.mission)
                        is CmdPackage -> "Package" + (UnitNames.packageMission(doc, u)?.let { " - $it" } ?: "")
                        is com.bmscompanion.desktop.bridge.command.CmdSquadron -> "Squadron"
                        is com.bmscompanion.desktop.bridge.command.CmdBattalion -> "Battalion"
                        is com.bmscompanion.desktop.bridge.command.CmdBrigade -> "Brigade"
                        is com.bmscompanion.desktop.bridge.command.CmdTaskForce -> "Task force"
                    }
                    val sq = when (u) {
                        is com.bmscompanion.desktop.bridge.command.CmdSquadron -> u
                        is CmdFlight -> doc.squadronOf(u)
                        else -> null
                    }
                    val home = when (u) {
                        is com.bmscompanion.desktop.bridge.command.CmdSquadron -> baseName(u)
                        is CmdFlight -> sq?.let { listOf(baseName(it), UnitNames.callsignText(doc, it)).filter { s -> s.isNotEmpty() }.joinToString(" ") }.orEmpty()
                        is com.bmscompanion.desktop.bridge.command.CmdBattalion -> (doc.unit(u.parentId)?.let { UnitNames.short(doc, it) })
                            ?: u.aobj.takeUnless { it.isNone }?.let { world.objective(it.toString())?.name }.orEmpty()
                        is com.bmscompanion.desktop.bridge.command.CmdBrigade -> u.aobj.takeUnless { it.isNone }?.let { world.objective(it.toString())?.name }.orEmpty()
                        else -> ""
                    }
                    val time = when (u) {
                        is CmdFlight -> FlightTimes.takeoff(u)
                        is CmdPackage -> doc.flightsOf(u).mapNotNull { FlightTimes.takeoff(it) }.minOrNull()
                        is com.bmscompanion.desktop.bridge.command.CmdSquadron -> null
                        else -> u.waypoints.firstOrNull()?.depart?.takeIf { it > 0 }
                    }
                    val radio = (u as? CmdFlight)?.let { com.bmscompanion.desktop.bridge.command.FlightRadios.of(doc, it) }
                    out[u.id.toString()] = UnitRowFacts(
                        i, UnitNames.tableName(doc, u), type, sq?.squadronPatch, UnitNames.callsignText(doc, u), home, time,
                        radio?.uhf.orEmpty(), radio?.vhf.orEmpty(),
                    )
                }
            }
            out
        }.getOrDefault(emptyMap())
    }
}

/**
 * The ATO page's table: team → package → flight, a package folding open on its flights, with take-off, time on
 * target and landing, the mission and the aircraft; a click selects (and shows) the package or the flight.
 */
object AtoTable : CmdTable {
    override val page = CmdPage.ATO

    private class Row(val ref: CmdRef, val depth: Int, val cells: List<String>, val ink: Color?, val key: String, val folder: String? = null)

    @Composable
    override fun Content(ctx: CmdContext) {
        @Suppress("UNUSED_VARIABLE") val v = CommandSession.version
        val doc = ctx.core
        val world = ctx.world
        val open = remember { mutableStateListOf<String>() }
        var text by remember { mutableStateOf("") }
        val sel = ctx.selection.primary
        // a selected flight opens its package and team
        LaunchedEffect(sel) {
            val f = sel?.takeIf { it.kind == CmdKind.FLIGHT }?.let { doc.unit(CampaignArchive.VuId.parse(it.id)) } as? CmdFlight
            doc.packageOf(f ?: return@LaunchedEffect)?.let { p -> if (p.id.toString() !in open) open.add(p.id.toString()); if ("t${p.owner}" !in open) open.add("t${p.owner}") }
        }
        val cls = com.bmscompanion.desktop.bridge.command.UnitClasses.of(doc)
        val rows = remember(doc.revision, open.toList(), text) {
            val q = text.trim()
            val out = ArrayList<Row>()
            val byTeam = doc.packages.groupBy { it.owner }.toSortedMap()
            for ((team, pkgs) in byTeam) {
                val tm = world.team(team)
                val tKey = "t$team"
                val flightsOfTeam = pkgs.sumOf { it.elements.size }
                out += Row(CmdRef(CmdKind.TEAM, "$team"), 0, listOf(tm?.name ?: "Team $team", "${pkgs.size} packages", "$flightsOfTeam flights", "", "", "", ""), tm?.let { teamInk(it.colour) }, tKey, folder = tKey)
                if (tKey !in open && q.isEmpty()) continue
                val sorted = pkgs.sortedBy { p -> doc.flightsOf(p).mapNotNull { FlightTimes.takeoff(it) }.minOrNull() ?: Long.MAX_VALUE }
                for (p in sorted) {
                    val fl = doc.flightsOf(p)
                    if (q.isNotEmpty() && fl.none { f -> UnitNames.callsign(doc, f).contains(q, true) || doc.callsign(f).contains(q, true) || FlightTimes.mission(doc, f.mission).contains(q, true) || (doc.aircraft(f)?.contains(q, true) == true) } && p.campId.toString() != q && UnitNames.packageNumber(p).toString() != q) continue
                    val pKey = p.id.toString()
                    val err = p.elements.size > 5 || p.elements.size != fl.size
                    out += Row(refOf(p), 1, listOf(UnitNames.short(doc, p), UnitNames.packageMission(doc, p) ?: FlightTimes.mission(doc, p.request.mission), "${fl.size} flight${if (fl.size == 1) "" else "s"}",
                        FlightTimes.hhmm(fl.mapNotNull { FlightTimes.takeoff(it) }.minOrNull()), FlightTimes.hhmm(fl.mapNotNull { FlightTimes.tot(it) }.minOrNull()), FlightTimes.hhmm(fl.mapNotNull { FlightTimes.landing(it) }.maxOrNull()), ""),
                        if (err) Hud.Red else null, pKey, folder = pKey)
                    if (pKey !in open && q.isEmpty()) continue
                    for (f in fl) out += Row(refOf(f), 2, listOf(UnitNames.callsign(doc, f), FlightTimes.mission(doc, f.mission), "${f.aircraft} × ${cls.leadVcd(f.type)?.name ?: doc.aircraft(f) ?: ""}",
                        FlightTimes.hhmm(FlightTimes.takeoff(f)), FlightTimes.hhmm(FlightTimes.tot(f)), FlightTimes.hhmm(FlightTimes.landing(f)),
                        doc.squadronOf(f)?.let { sq -> listOfNotNull(UnitNames.callsignText(doc, sq).ifEmpty { null }, sq.airbaseId.takeUnless { it.isNone }?.let { world.objective(it.toString())?.name }).joinToString(" · ") }.orEmpty()), null, f.id.toString())
                }
            }
            out
        }
        val widths = listOf(200.dp, 130.dp, 150.dp, 70.dp, 70.dp, 70.dp, 220.dp)
        val list = rememberLazyListState()
        val hscroll = rememberScrollState()
        Column(Modifier.fillMaxSize().shiftWheel(hscroll)) {
            Strip {
                Text("AIR TASKING ORDER", color = Hud.TextDim, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                VRule()
                FilterBox(text, "Callsign, mission, package") { text = it }
                MiniChip("Open all", false) { open.clear(); doc.packages.forEach { open.add(it.id.toString()); open.add("t${it.owner}") } }
                MiniChip("Close all", false) { open.clear() }
                Box(Modifier.weight(1f))
                Text("${doc.packages.size} packages · ${doc.flights.size} flights · clock ${CampaignArchive.clock(doc.cmp?.currentTime ?: 0)}", color = Hud.TextFaint, fontSize = 11.sp)
            }
            val heads = listOf("Team / package / flight", "Mission", "Aircraft", "Take-off", "TOT", "Landing", "Squadron")
            @Composable
            fun head(i: Int) = Text(heads[i].uppercase(), color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.width(widths[i]).padding(horizontal = 6.dp))
            // the tree column stays in place, the others scroll sideways together (bar at the foot, Shift + wheel)
            Row(Modifier.fillMaxWidth().background(Hud.Surface.copy(alpha = 0.7f)).height(22.dp).padding(end = CMD_BAR), verticalAlignment = Alignment.CenterVertically) {
                head(0)
                Row(Modifier.weight(1f).horizontalScroll(hscroll)) { for (i in 1 until heads.size) head(i) }
            }
            Rule(0.5f)
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = CMD_BAR), state = list) {
                items(rows, key = { it.key }) { r ->
                    val on = r.ref == sel
                    @Composable
                    fun cell(i: Int, c: String) {
                        val w = widths[i]
                        val lead = if (i == 0) (if (r.folder != null) (if (r.folder in open) "▾ " else "▸ ") else "   ") else ""
                        val team = if (i == 0 && r.ref.kind == CmdKind.TEAM) r.ref.id.toIntOrNull() else null
                        if (team != null) Row(Modifier.width(w).padding(start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(lead, color = Hud.Text, fontSize = 12.sp)
                            world.teamLead(team)()
                            Text(c, color = r.ink ?: Hud.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else Text(lead + c, color = r.ink ?: if (r.depth == 0) Hud.Text else if (i == 0 && r.depth == 1) Hud.Text else Hud.TextDim, fontSize = 12.sp,
                            fontWeight = if (r.depth < 2 && i == 0) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontFamily = if (i in 3..5) FontFamily.Monospace else null,
                            modifier = Modifier.width(w).padding(start = if (i == 0) (6 + r.depth * 14).dp else 6.dp, end = 6.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth().height(22.dp).background(if (on) Hud.Amber.copy(alpha = 0.16f) else if (r.depth == 0) Hud.Surface.copy(alpha = 0.35f) else Color.Transparent)
                            .clickable {
                                r.folder?.let { k -> if (k in open) open.remove(k) else open.add(k) }
                                if (r.ref.kind != CmdKind.TEAM) ctx.focus(r.ref)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        cell(0, r.cells[0])
                        Row(Modifier.weight(1f).horizontalScroll(hscroll)) { for (i in 1 until r.cells.size) cell(i, r.cells[i]) }
                    }
                    Rule(0.1f)
                }
            }
                CmdVBar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            CmdHBar(hscroll, Modifier.fillMaxWidth().padding(start = widths[0], end = CMD_BAR))
        }
    }
}
