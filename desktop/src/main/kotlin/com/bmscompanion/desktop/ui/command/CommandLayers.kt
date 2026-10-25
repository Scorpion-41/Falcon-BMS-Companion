package com.bmscompanion.desktop.ui.command

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.components.BmsChart
import com.bmscompanion.app.ui.components.MapLook
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Which owner an objective is coloured by: the owner now, the first owner, or only those this save changed. */
enum class OwnerView(val label: String) { CURRENT("Owner now"), FIRST("First owner"), CHANGED("Changed in this save") }

/** Links between objectives: none, the selected objective's, or every link. */
enum class LinkView(val label: String) { OFF("Off"), SELECTED("Selected"), ALL("All") }

/**
 * Everything the layers panel switches, as one value kept in the PC's prefs (`cmd_layers`): what the map shows of the
 * open file. Every side is shown unless a team is hidden ([hiddenTeams]).
 */
@Serializable
data class CmdLayerPrefs(
    // the map
    val style: String = "relief",
    val bmsIcons: Boolean = false,
    val names: Boolean = true,
    /** the app's borders and towns (off: the file's own cities and towns are its objectives) */
    val landmarks: Boolean = false,
    /** far out, leave out the less important of two symbols that would overlap, and show a base's squadrons as one */
    val declutter: Boolean = true,
    /** the team whose friends and foes the unit symbols' frames show (0 = by team colour only) */
    val seenFrom: Int = 0,
    // objectives
    val objectives: Boolean = true,
    val groups: Set<String> = ObjGroup.entries.filter { it != ObjGroup.GEO && it != ObjGroup.INFRASTRUCTURE }.map { it.name }.toSet(),
    val frontLine: Boolean = false,
    val secondLine: Boolean = false,
    val thirdLine: Boolean = false,
    val samCapable: Boolean = false,
    val noLinks: Boolean = false,
    val minPriority: Int = 0,
    val ownerView: OwnerView = OwnerView.CURRENT,
    // overlays
    val links: LinkView = LinkView.SELECTED,
    val parents: Boolean = false,
    val adRings: Boolean = false,
    val adTeams: Set<Int> = emptySet(),
    val adLong: Boolean = true,
    val adMedium: Boolean = true,
    val adShort: Boolean = false,
    // air
    val squadrons: Boolean = true,
    val flights: Boolean = true,
    val roles: Set<String> = AirRole.entries.map { it.name }.toSet(),
    val routeOnHover: Boolean = true,
    // ground
    val battalions: Boolean = true,
    val brigades: Boolean = false,
    val divisions: Boolean = false,
    val landKinds: Set<String> = LAND_KINDS.map { it.first }.toSet(),
    val orders: Int = -1,
    val inBrigadeOnly: Boolean = false,
    val movingOnly: Boolean = false,
    val inactiveOnly: Boolean = false,
    val groundRoutes: Boolean = false,
    val assignments: Boolean = false,
    val brigadeElements: Boolean = true,
    val divisionElements: Boolean = true,
    // naval
    val taskForces: Boolean = true,
    val allNavalRoutes: Boolean = false,
    // teams
    val hiddenTeams: Set<Int> = emptySet(),
) {
    companion object {
        /** the land kinds offered (UnitSymbols' kinds) */
        val LAND_KINDS = listOf(
            "airdefence" to "Air defence", "armor" to "Armour", "mechanized" to "Mechanised", "infantry" to "Infantry",
            "airmobile" to "Airmobile", "marine" to "Marines", "cavalry" to "Cavalry", "recon" to "Recon",
            "artillery" to "Self-propelled art.", "towed" to "Towed artillery", "rocket" to "Rockets (MLRS)",
            "missile" to "Missiles (SSM)", "engineer" to "Engineers", "supply" to "Supply", "hq" to "Headquarters", "other" to "Other",
        )
    }
}

/** The layers in force, kept in the prefs at every change. */
object CmdLayers {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    var prefs by mutableStateOf(
        Repo.getString("cmd_layers")?.let { s -> runCatching { json.decodeFromString(CmdLayerPrefs.serializer(), s) }.getOrNull() } ?: CmdLayerPrefs(),
    )
        private set

    fun update(f: (CmdLayerPrefs) -> CmdLayerPrefs) {
        prefs = f(prefs)
        runCatching { Repo.putString("cmd_layers", json.encodeToString(CmdLayerPrefs.serializer(), prefs)) }
    }

    /** the map style for a theater ([theaterId]'s BMS chart when that is chosen and the PC has it) */
    fun styleFor(theaterId: String): String {
        val s = prefs.style
        if (!BmsChart.isChart(s)) return s
        val has = BmsChart.list?.theaters?.any { it.theater == theaterId } == true
        return if (has) "${BmsChart.STYLE}@$theaterId" else "chart"
    }
}

/** BMS's team colours (white, green, blue, brown, orange, yellow, red, grey) as map inks. */
fun teamInk(colour: Int): Color = when (colour) {
    0 -> Color(0xFFF2F2F2)
    1 -> Color(0xFF2E9E3E)
    2 -> Color(0xFF3B6FE0)
    3 -> Color(0xFF9A5B2E)
    4 -> Color(0xFFF08A1C)
    5 -> Color(0xFFE8D020)
    6 -> Color(0xFFE03A30)
    else -> Color(0xFF8C8C8C)
}

// ------------------------------------------------------------------ the panel

/** The left panel: the map's look, then a folding section per kind of thing on the map. */
@Composable
internal fun LayersPanel(doc: CmdDocument?) {
    @Suppress("UNUSED_VARIABLE") val v = CommandSession.version // (a renamed or added team is listed again)
    val p = CmdLayers.prefs
    fun set(f: (CmdLayerPrefs) -> CmdLayerPrefs) = CmdLayers.update(f)
    val teams = doc?.world?.teams.orEmpty().filter { it.inUse }
    // the legend beside each tick box: what that layer draws (BMS's icons or the app's symbols, as the map does)
    val lg = remember(doc, p.bmsIcons) { LegendSource(doc, p.bmsIcons) }
    Column(Modifier.fillMaxWidth()) {
        Fold("Map", "map") {
            Text("Style", color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
            MiniWrap {
                MapLook.styles.forEach { (k, name) -> MiniChip(name, p.style == k) { set { it.copy(style = k) } } }
            }
            if (p.style == BmsChart.STYLE && doc != null && BmsChart.list?.theaters?.none { it.theater == doc.theaterId } != false)
                SmallNote("BMS's own chart was not found for ${doc.theaterName}: the bundled Chart is shown.")
            CheckLine("BMS icons (else the app's symbols)", p.bmsIcons) { v -> set { it.copy(bmsIcons = v) } }
            if (p.bmsIcons && doc != null) {
                val why = remember(doc) {
                    runCatching { com.bmscompanion.desktop.bridge.command.CampMapIcons.forTheater(doc.set, doc.theater).missingWhy }.getOrNull()
                }
                why?.let { SmallNote(it) }
            }
            CheckLine("Names", p.names) { v -> set { it.copy(names = v) } }
            Tip("Zoomed out, a symbol that would overlap a more important one is left out (airbases and cities stay), and a base's squadrons are shown as one with their count. Area still selects every one.") {
                CheckLine("Fewer symbols when zoomed out", p.declutter) { v -> set { it.copy(declutter = v) } }
            }
            CheckLine("Borders and towns (not on BMS chart)", p.landmarks) { v -> set { it.copy(landmarks = v) } }
            Text("Symbols' frames seen from", color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
            MiniWrap {
                MiniChip("Team colours", p.seenFrom == 0) { set { it.copy(seenFrom = 0) } }
                teams.forEach { tm -> MiniChip(tm.name, p.seenFrom == tm.n, teamInk(tm.colour), lead = { TeamFlagLead(tm.name, tm.colour) }) { set { it.copy(seenFrom = tm.n) } } }
            }
        }
        Fold("Teams", "teams") {
            if (teams.isEmpty()) SmallNote("Open a file to list its teams.")
            teams.forEach { tm ->
                CheckLine(tm.name, tm.n !in p.hiddenTeams, lead = { TeamFlagLead(tm.name, tm.colour) }) { v ->
                    set { it.copy(hiddenTeams = if (v) it.hiddenTeams - tm.n else it.hiddenTeams + tm.n) }
                }
            }
        }
        Fold("Objectives", "objectives") {
            CheckLine("Objectives", p.objectives) { v -> set { it.copy(objectives = v) } }
            ObjGroup.entries.forEach { g ->
                LegendCheck(g.label, g.name in p.groups, OBJECTIVE_TYPES_SHOWN[g].orEmpty().take(3).map { lg.objective(it) }, indent = true) { v ->
                    set { it.copy(groups = if (v) it.groups + g.name else it.groups - g.name) }
                }
            }
            Text("Only", color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
            CheckLine("Front line", p.frontLine, indent = true) { v -> set { it.copy(frontLine = v) } }
            CheckLine("Second line", p.secondLine, indent = true) { v -> set { it.copy(secondLine = v) } }
            CheckLine("Third line", p.thirdLine, indent = true) { v -> set { it.copy(thirdLine = v) } }
            CheckLine("SAM capable", p.samCapable, indent = true) { v -> set { it.copy(samCapable = v) } }
            CheckLine("No links", p.noLinks, indent = true) { v -> set { it.copy(noLinks = v) } }
            Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Priority ≥", color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.width(66.dp))
                Slider(
                    p.minPriority.toFloat(), { v -> set { it.copy(minPriority = v.toInt()) } }, valueRange = 0f..100f,
                    modifier = Modifier.weight(1f).height(20.dp),
                    colors = SliderDefaults.colors(thumbColor = Hud.Amber, activeTrackColor = Hud.Amber.copy(alpha = 0.6f), inactiveTrackColor = Hud.Outline),
                )
                Text("${p.minPriority}", color = Hud.Text, fontSize = 12.sp, modifier = Modifier.width(28.dp).padding(start = 4.dp))
            }
            Text("Colour by", color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
            MiniWrap { OwnerView.entries.forEach { v -> MiniChip(v.label, p.ownerView == v) { set { it.copy(ownerView = v) } } } }
        }
        Fold("Overlays", "overlays") {
            Text("Links", color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
            MiniWrap { LinkView.entries.forEach { v -> MiniChip(v.label, p.links == v) { set { it.copy(links = v) } } } }
            LegendCheck("Parents", p.parents, listOf(LegendSource.line("An objective to its parent", Hud.Magenta, dashed = true))) { v -> set { it.copy(parents = v) } }
            LegendCheck("Air-defence rings", p.adRings, listOf(LegendSource.ring("How far an air-defence battalion reaches, in its team's colour"))) { v -> set { it.copy(adRings = v) } }
            if (p.adRings) {
                CheckLine("Long range", p.adLong, indent = true) { v -> set { it.copy(adLong = v) } }
                CheckLine("Medium range", p.adMedium, indent = true) { v -> set { it.copy(adMedium = v) } }
                CheckLine("Short range", p.adShort, indent = true) { v -> set { it.copy(adShort = v) } }
                Text("Teams (none ticked = every team)", color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
                teams.forEach { tm ->
                    CheckLine(tm.name, tm.n in p.adTeams, indent = true, lead = { TeamFlagLead(tm.name, tm.colour) }) { v ->
                        set { it.copy(adTeams = if (v) it.adTeams + tm.n else it.adTeams - tm.n) }
                    }
                }
            }
        }
        Fold("Air units", "air") {
            LegendCheck("Squadrons", p.squadrons, listOf(lg.squadron(AirRole.FIGHTER))) { v -> set { it.copy(squadrons = v) } }
            LegendCheck("Flights", p.flights, listOf(lg.flight(AirRole.FIGHTER))) { v -> set { it.copy(flights = v) } }
            AirRole.entries.forEach { r ->
                LegendCheck(r.label, r.name in p.roles, listOf(lg.squadron(r), lg.flight(r)), indent = true) { v -> set { it.copy(roles = if (v) it.roles + r.name else it.roles - r.name) } }
            }
            CheckLine("Route of the flight under the mouse", p.routeOnHover) { v -> set { it.copy(routeOnHover = v) } }
        }
        Fold("Ground units", "ground") {
            LegendCheck("Battalions", p.battalions, listOf(lg.land("infantry", "Battalion (II over its frame)"))) { v -> set { it.copy(battalions = v) } }
            LegendCheck("Brigades", p.brigades, listOf(lg.land("armor", "Brigade (X over its frame)", CmdKind.BRIGADE))) { v -> set { it.copy(brigades = v) } }
            LegendCheck("Divisions", p.divisions, listOf(lg.division())) { v -> set { it.copy(divisions = v) } }
            CmdLayerPrefs.LAND_KINDS.forEach { (k, name) ->
                LegendCheck(name, k in p.landKinds, listOf(lg.land(k, name)), indent = true) { v -> set { it.copy(landKinds = if (v) it.landKinds + k else it.landKinds - k) } }
            }
            Text("Orders", color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
            MiniWrap {
                MiniChip("All", p.orders < 0) { set { it.copy(orders = -1) } }
                CommandWorldBuilder.ORDERS.forEachIndexed { i, o -> MiniChip(o, p.orders == i) { set { it.copy(orders = i) } } }
            }
            CheckLine("In a brigade only", p.inBrigadeOnly) { v -> set { it.copy(inBrigadeOnly = v) } }
            CheckLine("Moving only", p.movingOnly) { v -> set { it.copy(movingOnly = v) } }
            CheckLine("Inactive only", p.inactiveOnly) { v -> set { it.copy(inactiveOnly = v) } }
            CheckLine("Routes of every battalion", p.groundRoutes) { v -> set { it.copy(groundRoutes = v) } }
            CheckLine("Assigned objectives", p.assignments) { v -> set { it.copy(assignments = v) } }
            CheckLine("Brigade elements (selected)", p.brigadeElements) { v -> set { it.copy(brigadeElements = v) } }
            CheckLine("Division elements (selected)", p.divisionElements) { v -> set { it.copy(divisionElements = v) } }
        }
        Fold("Naval", "naval") {
            LegendCheck("Task forces", p.taskForces, listOf(lg.land("destroyer", "Task force (a ship)", CmdKind.TASKFORCE))) { v -> set { it.copy(taskForces = v) } }
            CheckLine("Routes of every task force", p.allNavalRoutes) { v -> set { it.copy(allNavalRoutes = v) } }
        }
        Spacer(Modifier.height(12.dp))
    }
}
