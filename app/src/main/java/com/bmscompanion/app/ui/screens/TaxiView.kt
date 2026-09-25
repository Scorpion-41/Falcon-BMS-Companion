package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.airfield.AfRoute
import com.bmscompanion.app.data.airfield.AfSpot
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.data.airfield.TaxiChipKind
import com.bmscompanion.app.data.airfield.TaxiClearance
import com.bmscompanion.app.data.airfield.TaxiNet
import com.bmscompanion.app.data.airfield.clearanceFor
import com.bmscompanion.app.data.airfield.distanceLabel
import com.bmscompanion.app.data.airfield.fieldOffset
import com.bmscompanion.app.data.airfield.routeForPosition
import com.bmscompanion.app.data.mission.Contact
import com.bmscompanion.app.ui.components.AirfieldChart
import com.bmscompanion.app.ui.components.ChartInks
import com.bmscompanion.app.ui.components.chartRunways
import com.bmscompanion.app.ui.components.ChartTraffic
import com.bmscompanion.app.ui.components.TrafficKind
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.isWide
import com.bmscompanion.app.ui.components.rememberChartState
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlin.math.roundToInt

/** Day or night inks for the ground chart, remembered between launches. */
object TaxiPrefs {
    private const val NIGHT_KEY = "taxi_chart_night"
    private var night by mutableStateOf(Repo.getInt(NIGHT_KEY, 1))

    var darkChart: Boolean
        get() = night != 0
        set(v) { night = if (v) 1 else 0; Repo.putInt(NIGHT_KEY, night) }

    val inks: ChartInks get() = if (darkChart) ChartInks.night else ChartInks.day
}

/** What the jet is doing, when there is a jet. The chart works the same without it. */
data class TaxiLive(
    /** the jet in the field's own feet, east and north of its origin */
    val you: Offset? = null,
    val heading: Double? = null,
    val onGround: Boolean = false,
    val traffic: List<ChartTraffic> = emptyList(),
    /** something to say when there is no live picture, e.g. that ACMI recording is not running */
    val note: String? = null,
)

/**
 * No other aircraft are drawn on a ground chart.
 *
 * They used to be, from the Tacview feed, and they were in the wrong places: that feed carries its own coordinate
 * frame, and a shift of a few hundred feet that is invisible on a theater map parks an aircraft on the grass
 * beside a sixty-foot stand. Even placed exactly they added little — a pilot taxiing looks out of the canopy for
 * the jet in front, not at a kneeboard — and a ramp of eighty spots with a symbol on each is unreadable. So the
 * chart shows the pilot's own aircraft and nothing else: no wingmen, no flight, no traffic.
 */
fun fieldTraffic(field: Airfield, contacts: List<Contact>): List<ChartTraffic> = emptyList()

/** What the pilot has chosen on the page — the thing a VR board mirrors. */
data class TaxiChoice(val airportId: Int, val runway: String, val outbound: Boolean, val spot: Int?)

/**
 * The ground chart page: the field, the clearance, and the controls for both.
 *
 * Shared by the Mission section's Taxi tab, which knows where the jet is, and the Airfields section, which is for
 * looking a field up before you fly. Everything it draws comes out of the field's own authored data.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaxiView(
    field: Airfield,
    modifier: Modifier = Modifier,
    airports: List<Airport> = emptyList(),
    airport: Airport? = null,
    onAirport: ((Airport) -> Unit)? = null,
    live: TaxiLive = TaxiLive(),
    onChoice: ((TaxiChoice) -> Unit)? = null,
) {
    /**
     * A carrier is the deck and nothing else.
     *
     * A ship has no taxiways, no ramp a pilot is cleared to, and no runway that stays where it is: it steams into
     * wind, so its "runway 36" is whatever heading the ship is on this minute and the numbers BMS gives its spots
     * turn with it. Drawing a clearance to one of them would be drawing a lie. So the page keeps the diagram and
     * the day/night switch, and drops everything that belongs to an airfield.
     */
    val isShip = field.ship != null
    var pickedRunway by rememberSaveable(field.id) { mutableStateOf<String?>(null) }
    val autoRunway = remember(field.id, live.you, live.onGround) {
        live.you?.takeIf { live.onGround }?.let { routeForPosition(field, it.x.toDouble(), it.y.toDouble())?.designator }
    }
    /**
     * The runway in use, or none.
     *
     * Nothing is chosen to begin with. Looking a field up in Airfields is reading its taxiways and its ramp, not
     * being given a clearance to a runway nobody has said they are using — and a blue route drawn across the chart
     * before anyone asked for one is the loudest thing on the page. Picking a runway draws the route; picking it
     * again puts the chart back to plain. In the Mission section the jet's own position still answers the question
     * by itself, which is what a pilot wants the moment they spawn.
     */
    val chosenRunway = if (isShip) null else pickedRunway ?: autoRunway
    val route = field.routes.firstOrNull { it.designator == chosenRunway } ?: field.routes.firstOrNull()

    val net = remember(field.id, route?.designator) { route?.let { TaxiNet(field, it) } }
    val liveSpot = remember(net, live.you) {
        live.you?.let { p -> net?.nearestSpot(p.x.toDouble(), p.y.toDouble()) }?.takeIf { it.second < 220 }?.first?.n
    }

    var outbound by rememberSaveable { mutableStateOf(true) }
    var pickedSpot by rememberSaveable(field.id, route?.designator) { mutableStateOf<Int?>(null) }
    // With no runway in use, nothing is picked out on the ramp either: the page is the field as it stands.
    val spotNumber = pickedSpot ?: liveSpot ?: route?.parking?.firstOrNull()?.n?.takeIf { chosenRunway != null }
    val spot = spotNumber?.let { n -> route?.parking?.firstOrNull { it.n == n } }

    val path = remember(net, spot?.n, outbound, chosenRunway) {
        if (chosenRunway == null) return@remember null
        val n = net ?: return@remember null
        val r = route ?: return@remember null
        val s = spot ?: return@remember null
        val startAt = r.start ?: return@remember null
        if (outbound) n.path(s.k, startAt) else n.path(startAt, s.k)
    }
    val clearance = remember(path, outbound, spot?.n) {
        val n = net ?: return@remember null
        path?.let { clearanceFor(n, it, outbound, spot) }
    }

    // Tell whoever is listening — this is how a VR board follows the page. Done as an effect rather than
    // during composition, because publishing is work with the outside world, not part of drawing.
    // Nothing is published until a runway is in use: a VR board with no choice works it out from where the jet is
    // standing, which is better than being told about a runway nobody picked.
    val choice = if (chosenRunway == null) null else route?.let { TaxiChoice(field.id, it.designator, outbound, spot?.n) }
    androidx.compose.runtime.LaunchedEffect(choice) { choice?.let { onChoice?.invoke(it) } }

    var highlight by remember { mutableStateOf<IntRange?>(null) }
    val chartState = rememberChartState()
    val inks = TaxiPrefs.inks

    Column(modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (airports.isNotEmpty() && onAirport != null) {
                var open by remember { mutableStateOf(false) }
                Box {
                    TaxiPill((airport?.icao ?: airport?.name ?: field.name) + "  ▾", false) { open = true }
                    DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                        for (a in airports) DropdownMenuItem(
                            text = { Text(a.icao?.let { "$it — ${a.name}" } ?: a.name, fontSize = 13.sp) },
                            onClick = { open = false; onAirport(a); chartState.reset() },
                        )
                    }
                }
            }
            if (!isShip) {
                for (r in field.routes) {
                    val on = r.designator == chosenRunway
                    TaxiPill("RWY ${r.designator}" + if (r.designator == autoRunway) " ●" else "", on) {
                        // tapping the runway already in use puts it back to none, and the route with it
                        pickedRunway = if (on) null else r.designator
                        pickedSpot = null
                    }
                }
                TaxiPill("Taxi out", outbound) { outbound = true }
                TaxiPill("Taxi in", !outbound) { outbound = false }
            }
            TaxiPill(if (TaxiPrefs.darkChart) "Night chart" else "Day chart", false) { TaxiPrefs.darkChart = !TaxiPrefs.darkChart }
        }
        Spacer(Modifier.height(8.dp))

        val chart = @Composable { m: Modifier ->
            Box(m.border(1.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))) {
                AirfieldChart(
                    field = field,
                    route = if (isShip) null else route,
                    inks = inks,
                    modifier = Modifier.fillMaxSize(),
                    path = path?.nodes,
                    highlight = highlight,
                    you = live.you,
                    youHeading = live.heading,
                    traffic = live.traffic,
                    selectedSpot = if (outbound) spot?.n else liveSpot,
                    destinationSpot = if (outbound) null else spot?.n,
                    state = chartState,
                    zoomAnchor = spot?.let { p -> route?.nodes?.getOrNull(p.k)?.let { Offset(it.e.toFloat(), it.n.toFloat()) } } ?: live.you,
                    onTapSpot = if (isShip) null else ({ spot -> pickedSpot = spot.n }),
                )
                ChartLegend(Modifier.align(Alignment.TopStart).padding(8.dp), inks, field, route, live)
                ZoomButtons(Modifier.align(Alignment.BottomEnd).padding(8.dp), inks, chartState)
            }
        }

        when {
            // the deck gets the whole page: there is no clearance to put beside it
            isShip -> chart(Modifier.fillMaxSize())
            isWide() -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                chart(Modifier.weight(1.6f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClearanceCard(live, spot, outbound, route, liveSpot, clearance, chosenRunway) { highlight = it }
                    SpotCard(route, spot, liveSpot, outbound) { pickedSpot = it }
                }
            }
            else -> {
                chart(Modifier.fillMaxWidth().height(340.dp))
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClearanceCard(live, spot, outbound, route, liveSpot, clearance, chosenRunway) { highlight = it }
                    SpotCard(route, spot, liveSpot, outbound) { pickedSpot = it }
                }
            }
        }
    }
}

@Composable
fun TaxiPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Hud.Cyan.copy(alpha = 0.18f) else Hud.Surface)
            .border(1.dp, if (selected) Hud.Cyan.copy(alpha = 0.7f) else Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (selected) Hud.Cyan else Hud.Text,
        fontSize = 12.5.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun ZoomButtons(modifier: Modifier, inks: ChartInks, state: com.bmscompanion.app.ui.components.ChartState) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, action) in listOf<Pair<String, () -> Unit>>(
            "+" to { state.zoomBy(1.6f) },
            "−" to { state.zoomBy(1f / 1.6f) },
            "⤢" to { state.reset() },
        )) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                    .background(inks.ground.copy(alpha = 0.88f))
                    .border(1.dp, inks.dim.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .clickable(onClick = action),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = inks.ink, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun ChartLegend(modifier: Modifier, inks: ChartInks, field: Airfield, route: AfRoute?, live: TaxiLive) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(inks.ground.copy(alpha = 0.84f)).padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(field.icao?.let { "${field.name} · $it" } ?: field.name, color = inks.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        // A ship says what it is instead: BMS's runway record for a carrier is the approach path, a catapult and
        // sometimes a rectangle of no width, and the deck the chart draws comes from the real ship.
        val ship = field.ship
        if (ship != null) Text(ship.cls, color = inks.dim, fontSize = 10.sp)
        else Text(chartRunways(field).joinToString("   ") { "${it.name}  ${it.lengthFt} × ${it.widthFt} ft" }, color = inks.dim, fontSize = 10.sp)
        // A ship has no ramp on the chart, so it has nothing to say about one.
        if (ship == null) route?.let {
            // Say how the ramp splits, because the chart draws the two differently and a pilot wants to know
            // before taxiing whether the spot they have been given has a roof over it.
            val covered = it.parking.count { p -> p.covered }
            val split = if (covered > 0 && covered < it.parking.size) " · $covered under shelter" else ""
            Text("Ramp numbered for runway ${it.designator} · ${it.parking.size} spots$split", color = inks.dim, fontSize = 10.sp)
        }
        if (live.traffic.isNotEmpty()) Text("${live.traffic.size} aircraft on the field", color = inks.dim, fontSize = 10.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClearanceCard(
    live: TaxiLive,
    spot: AfSpot?,
    outbound: Boolean,
    route: AfRoute?,
    liveSpot: Int?,
    clearance: TaxiClearance?,
    chosenRunway: String?,
    onStep: (IntRange?) -> Unit,
) {
    SectionCard(if (outbound) "Taxi clearance — departure" else "Taxi clearance — after landing", accent = Hud.Cyan) {
        Column {
            val where = when {
                live.you == null -> live.note ?: "No live position — the chart is working from the spot you pick."
                liveSpot != null -> "You are on spot $liveSpot."
                else -> "You are on the field, between spots."
            }
            Text(where, color = if (live.you == null) Hud.Amber else Hud.Cyan, fontSize = 11.5.sp)
            Spacer(Modifier.height(6.dp))
            if (clearance == null) {
                Text(
                    when {
                        chosenRunway == null -> "Pick the runway in use above, and the chart draws the way to it."
                        spot == null -> "Pick a ramp spot."
                        else -> "No way to taxi between there and runway ${route?.designator}."
                    },
                    color = Hud.Text, fontSize = 14.sp,
                )
                return@Column
            }
            Text(clearance.line, color = Hud.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                distanceLabel(clearance.ft) + " · about " + (clearance.ft / 6076 * 4).roundToInt().coerceAtLeast(1) + " min at taxi speed",
                style = LocalExtra.current.monoSmall, color = Hud.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (chip in clearance.chips) {
                    val (bg, fg) = when (chip.kind) {
                        TaxiChipKind.WAY -> Hud.Amber.copy(alpha = 0.22f) to Hud.Amber
                        TaxiChipKind.END -> Hud.Cyan.copy(alpha = 0.22f) to Hud.Cyan
                        else -> Color.Transparent to Hud.TextDim
                    }
                    Text(
                        chip.text,
                        Modifier.clip(RoundedCornerShape(5.dp)).background(bg)
                            .border(1.dp, if (bg == Color.Transparent) Hud.Outline.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(5.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            clearance.steps.forEachIndexed { i, step ->
                Row(
                    Modifier.fillMaxWidth().clickable { onStep(stepRange(clearance, i)) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text("${i + 1}", Modifier.width(20.dp), color = Hud.TextFaint, style = LocalExtra.current.monoSmall)
                    Text(step.text, Modifier.weight(1f), color = Hud.Text, fontSize = 13.sp)
                    step.ft?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("${it.roundToInt()} ft", color = Hud.TextDim, style = LocalExtra.current.monoSmall)
                    }
                }
            }
        }
    }
}

/** Which slice of the path a step covers, so tapping it lights that stretch on the chart. */
private fun stepRange(clearance: TaxiClearance, index: Int): IntRange? {
    val step = clearance.steps.getOrNull(index) ?: return null
    val from = step.fromNode ?: return null
    return from..step.atNode
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpotCard(route: AfRoute?, spot: AfSpot?, liveSpot: Int?, outbound: Boolean, onPick: (Int) -> Unit) {
    val r = route ?: return
    SectionCard(if (outbound) "Where you are" else "Where you are going", accent = Hud.Amber) {
        Column {
            Text(
                if (outbound) "Tap a spot on the chart, or pick it here. Spot numbers belong to runway ${r.designator} — BMS numbers the ramp separately for each end."
                else "Pick the spot you have been given; the chart draws the way in from runway ${r.designator}.",
                color = Hud.TextDim, fontSize = 11.5.sp,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in r.parking) {
                    val mine = p.n == spot?.n
                    val here = p.n == liveSpot
                    Text(
                        p.n.toString() + if (here) "•" else "",
                        Modifier.clip(RoundedCornerShape(5.dp))
                            .background(if (mine) Hud.Cyan.copy(alpha = 0.22f) else Hud.Surface)
                            .border(1.dp, if (mine) Hud.Cyan.copy(alpha = 0.8f) else Hud.Outline.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                            .clickable { onPick(p.n) }
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        color = if (mine) Hud.Cyan else if (p.covered) Hud.Text else Hud.TextDim,
                        style = LocalExtra.current.monoSmall,
                    )
                }
            }
        }
    }
}
