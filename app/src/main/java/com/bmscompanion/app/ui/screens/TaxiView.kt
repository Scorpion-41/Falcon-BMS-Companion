package com.bmscompanion.app.ui.screens

import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.luminance
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
import com.bmscompanion.app.data.airfield.landingRouteFor
import com.bmscompanion.app.data.airfield.routeForPosition
import com.bmscompanion.app.data.airfield.routeShown
import com.bmscompanion.app.data.airfield.spotLabel
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
    /** what the page opens on (the headless checks): a runway, and the way out or the way in */
    initialRunway: String? = null,
    initialOutbound: Boolean = true,
    /**
     * The last controller's call to the pilot's flight, from BMS's debug log (the Mission section's Taxi page; null
     * elsewhere and without the log). Each is applied once, as the pilot's own taps would: the runway to taxi to, or
     * the way in with the spot Ground gave; whatever the pilot picks afterwards wins.
     */
    radio: com.bmscompanion.app.data.mission.RadioTaxi? = null,
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
    var pickedRunway by rememberSaveable(field.id) { mutableStateOf(initialRunway) }
    var outbound by rememberSaveable { mutableStateOf(initialOutbound) }
    // The radio's cue (BMS's debug log): applied once per call, as a tap on its pill would be. A call for a runway this
    // field does not have is said and not applied (the page may be on another field than the controller's).
    var radioSpot by rememberSaveable(field.id) { mutableStateOf<Int?>(null) }
    var radioLine by rememberSaveable(field.id) { mutableStateOf<String?>(null) }
    var radioApplied by rememberSaveable(field.id) { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(radio?.seq, radio?.at, field.id) {
        val r = radio ?: return@LaunchedEffect
        if (isShip || r.at <= radioApplied) return@LaunchedEffect
        radioApplied = r.at
        val line = com.bmscompanion.app.data.mission.RadioCalls.cueLine(r)
        val a = com.bmscompanion.app.data.mission.RadioCalls.applyTo(r, field.routes.map { it.designator })
        if (a == null) { radioLine = "$line — not a runway of this chart"; return@LaunchedEffect }
        // the way in: the runway landed on when the landing clearance named it, else the network the jet stands in
        outbound = a.outbound; pickedRunway = a.runway; radioSpot = a.spot
        radioLine = line
    }
    // the network whose ramp the jet stands on; for the way in, the runway is the one whose landing taxis in on it
    val autoNet = remember(field.id, live.you, live.onGround) {
        live.you?.takeIf { live.onGround }?.let { routeForPosition(field, it.x.toDouble(), it.y.toDouble()) }
    }
    val autoRunway = autoNet?.let { if (outbound) it.designator else landingRouteFor(field, it).designator }
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
    /**
     * The network drawn, and so the numbers on every spot: for a departure the runway's own; for the way in, the
     * **taxi-in network** — the reciprocal end's, whose line-up is where the landing rolls out — because that is the
     * one BMS's Ground counts in when it says "park 04" ([taxiInRoute]). A pilot picks the number heard on the radio.
     */
    val route = routeShown(field, chosenRunway, outbound) ?: field.routes.firstOrNull()

    val net = remember(field.id, route?.designator) { route?.let { TaxiNet(field, it) } }
    val liveSpot = remember(net, live.you) {
        live.you?.let { p -> net?.nearestSpot(p.x.toDouble(), p.y.toDouble()) }?.takeIf { it.second < 220 }?.first?.n
    }

    // a new radio call starts the spot afresh (radioApplied): a spot tapped before it no longer stands
    var pickedSpot by rememberSaveable(field.id, route?.designator, radioApplied) { mutableStateOf<Int?>(null) }
    // the spot Ground gave on the radio ("park 0 0 4"), counted in the taxi-in network shown; a spot tapped wins
    val radioSpotShown = radioSpot?.takeIf { s -> !outbound && route?.parking?.any { it.n == s } == true }
    // With no runway in use, nothing is picked out on the ramp either: the page is the field as it stands.
    val spotNumber = pickedSpot ?: radioSpotShown ?: liveSpot ?: route?.parking?.firstOrNull()?.n?.takeIf { chosenRunway != null }
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
        path?.let { clearanceFor(n, it, outbound, spot, runway = chosenRunway ?: n.route.designator) }
    }

    // Tell whoever is listening — this is how a VR board follows the page. Done as an effect rather than
    // during composition, because publishing is work with the outside world, not part of drawing.
    // Nothing is published until a runway is in use: a VR board with no choice works it out from where the jet is
    // standing, which is better than being told about a runway nobody picked.
    // The runway is the one chosen (landed on, for the way in) and the spot is its number in the network shown:
    // a board finds the same network with routeShown.
    val choice = if (chosenRunway == null || route == null) null else TaxiChoice(field.id, chosenRunway, outbound, spot?.n)
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
                        radioSpot = null
                    }
                }
                TaxiPill("Taxi out", outbound) { outbound = true; radioSpot = null }
                TaxiPill("Taxi in", !outbound) { outbound = false }
            }
            TaxiPill(if (TaxiPrefs.darkChart) "Night chart" else "Day chart", false) { TaxiPrefs.darkChart = !TaxiPrefs.darkChart }
        }
        // what the radio last said to the flight, and so what the page took from it
        radioLine?.takeIf { !isShip }?.let { line ->
            Spacer(Modifier.height(6.dp))
            Text("RADIO ${radio?.time.orEmpty()}  $line", color = Hud.Green, fontSize = 12.sp, maxLines = 2)
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
                ChartLegend(Modifier.align(Alignment.TopStart).padding(8.dp), inks, field, route, live, if (outbound) null else chosenRunway)
                ZoomButtons(Modifier.align(Alignment.BottomEnd).padding(8.dp), inks, chartState)
            }
        }

        val cards = @Composable { m: Modifier ->
            Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ClearanceCard(live, spot, outbound, route, liveSpot, clearance, chosenRunway) { highlight = it }
                SpotCard(route, spot, liveSpot, outbound, chosenRunway) { pickedSpot = it }
            }
        }

        // The layout is decided by the room the page really has under the pills, not by the screen's width alone:
        // the chart takes every pointer event (pan, zoom, tap a spot), so a finger on it never scrolls the page, and
        // the cards must always keep a strip of their own to be dragged by. See taxiLayout.
        val wide = isWide()
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val w = maxWidth
            val h = if (constraints.hasBoundedHeight) maxHeight else 10_000.dp
            when (taxiLayout(w.value, h.value, isShip, wide)) {
                // the deck gets the whole page: there is no clearance to put beside it
                TaxiLayout.DECK -> chart(Modifier.fillMaxSize())
                TaxiLayout.SIDE -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    chart(Modifier.weight(1f).fillMaxHeight())
                    cards(Modifier.width(taxiCardsWidth(w.value).dp).fillMaxHeight())
                }
                TaxiLayout.STACK -> Column(Modifier.fillMaxSize()) {
                    chart(Modifier.fillMaxWidth().height(taxiChartHeight(h.value).dp))
                    Spacer(Modifier.height(10.dp))
                    cards(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

/** How the Taxi page sets out its chart and its two cards (the clearance and the ramp spots). */
enum class TaxiLayout {
    /** a carrier: the deck and nothing else */
    DECK,
    /** the chart on the left taking the whole height, the cards in their own scrolling column on the right */
    SIDE,
    /** the chart above, held well short of the page's height, the cards scrolling below it */
    STACK,
}

/**
 * Which layout fits the room the page has under its pills, in dp.
 *
 * Up to 1.3.7 the chart and the cards sat side by side only on a screen at least 840 dp wide; anything narrower
 * stacked a chart of a fixed 340 dp over the cards. A 1280 x 800 tablet in landscape is 640-850 dp wide and, under
 * the Mission section's header, source bar, tab strip and the pills, has well under 340 dp of height left — so the
 * chart took all of it, the cards got none, and since the chart keeps every finger for panning there was nothing
 * left to scroll by. So: side by side whenever the room is wider than it is tall (or the screen is wide, as before),
 * and when stacked the chart never takes more than [STACK_CHART_SHARE] of the height.
 */
fun taxiLayout(wDp: Float, hDp: Float, ship: Boolean, wideScreen: Boolean): TaxiLayout = when {
    ship -> TaxiLayout.DECK
    wideScreen || (wDp >= 480f && wDp > hDp) -> TaxiLayout.SIDE
    else -> TaxiLayout.STACK
}

/** The cards' column beside the chart: about two fifths of the width, never so narrow the steps wrap every word. */
fun taxiCardsWidth(wDp: Float): Float = (wDp * 0.385f).coerceIn(260f, 420f).coerceAtMost(wDp * 0.5f)

/** The share of the height a stacked chart may take: the rest is the cards', always enough to drag. */
const val STACK_CHART_SHARE = 0.6f

/** A stacked chart: 340 dp as it always was on a phone held upright, less when the page is short. */
fun taxiChartHeight(hDp: Float): Float = minOf(340f, hDp * STACK_CHART_SHARE)

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
        // the fit button is an icon: the browser's font has no ⤢ (CLAUDE.md, the glyph gotcha)
        for ((label, action) in listOf<Pair<String, () -> Unit>>(
            "+" to { state.zoomBy(com.bmscompanion.app.ui.components.ZoomMath.BUTTON) },
            "−" to { state.zoomBy(1f / com.bmscompanion.app.ui.components.ZoomMath.BUTTON) },
            "" to { state.reset() },
        )) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                    .background(inks.ground.copy(alpha = 0.88f))
                    .border(1.dp, inks.dim.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .clickable(onClick = action),
                contentAlignment = Alignment.Center,
            ) {
                if (label.isEmpty()) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.FitScreen, "Fit the chart", tint = inks.ink, modifier = Modifier.size(18.dp))
                else Text(label, color = inks.ink, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ChartLegend(modifier: Modifier, inks: ChartInks, field: Airfield, route: AfRoute?, live: TaxiLive, landedOn: String?) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(inks.ground.copy(alpha = 0.84f)).padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(field.icao?.let { "${field.name} · $it" } ?: field.name, color = inks.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        // A ship says what it is instead: BMS's runway record for a carrier is the approach path, a catapult and
        // sometimes a rectangle of no width, and the deck the chart draws comes from the real ship.
        val ship = field.ship
        if (ship != null) {
            Text(ship.cls, color = inks.dim, fontSize = 10.sp)
            // where the drawing comes from, and what the ramp does, in the words a deck chart would use
            val about = listOfNotNull(
                ship.model.takeIf { it.isNotEmpty() }?.let { "deck from $it" },
                ship.skiDeg?.let { "${it.roundToInt()}° ski jump" },
            )
            if (about.isNotEmpty()) Text(about.joinToString(" · "), color = inks.dim, fontSize = 10.sp)
            // A ship's page has no clearance card to carry the note, so it goes here: on a deck the Tacview feed is
            // what puts the jet on the chart at all.
            live.note?.let { Text(it, color = inks.accent, fontSize = 10.sp, modifier = Modifier.widthIn(max = 280.dp)) }
        }
        else Text(chartRunways(field).joinToString("   ") { "${it.name}  ${it.lengthFt} × ${it.widthFt} ft" }, color = inks.dim, fontSize = 10.sp)
        // A ship has no ramp on the chart, so it has nothing to say about one.
        if (ship == null) route?.let {
            // Say how the ramp splits, because the chart draws the two differently and a pilot wants to know
            // before taxiing whether the spot they have been given has a roof over it.
            val covered = it.parking.count { p -> p.covered }
            val split = if (covered > 0 && covered < it.parking.size) " · $covered under shelter" else ""
            // How the ramp is made up, in the same terms BMS's own parking charts use.
            val small = it.parking.count { p -> p.small }
            val alert = it.parking.count { p -> p.alert }
            val sizes = if (small in 1 until it.parking.size) " · $small small, ${it.parking.size - small} large" else ""
            val cell = if (alert > 0) " · $alert alert" else ""
            // the numbers are BMS's for this network: for the way in, the ones Ground says after landing on landedOn
            val numbered = if (landedOn != null && landedOn != it.designator) "Ground's numbers after landing on $landedOn (runway ${it.designator}'s ramp)"
                else "Ramp numbered for runway ${it.designator}"
            Text("$numbered · ${it.parking.size} spots$sizes$split$cell", color = inks.dim, fontSize = 10.sp)
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
                liveSpot != null -> "You are on spot ${spotLabel(liveSpot)}."
                else -> "You are on the field, between spots."
            }
            Text(where, color = if (live.you == null) Hud.Amber else Hud.Cyan, fontSize = 11.5.sp)
            Spacer(Modifier.height(6.dp))
            if (clearance == null) {
                Text(
                    when {
                        chosenRunway == null -> "Pick the runway in use above, and the chart draws the way to it."
                        spot == null -> "Pick a ramp spot."
                        else -> "No way to taxi between there and runway ${chosenRunway ?: route?.designator}."
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
private fun SpotCard(route: AfRoute?, spot: AfSpot?, liveSpot: Int?, outbound: Boolean, runway: String?, onPick: (Int) -> Unit) {
    val r = route ?: return
    // muted, as on the chart: the dusky rose of the night chart on a dark page, the brick of the day one on a light one
    val alertInk = if (Hud.Surface.luminance() > 0.5f) ChartInks.day.alert else ChartInks.night.alert
    SectionCard(if (outbound) "Where you are" else "Where you are going", accent = Hud.Amber) {
        Column {
            Text(
                if (outbound) "Tap a spot on the chart, or pick it here. Spot numbers belong to runway ${r.designator} — BMS numbers the ramp separately for each end."
                else "Pick the number Ground gave you: these are the numbers BMS's Ground says after landing on runway ${runway ?: r.designator}" +
                    (if (runway != null && runway != r.designator) " (it counts runway ${r.designator}'s ramp)" else "") + ". The chart draws the way in.",
                color = Hud.TextDim, fontSize = 11.5.sp,
            )
            // The key, in the same shapes and colours the chart and BMS's own parking charts use.
            if (r.parking.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        val small = r.parking.count { it.small }
                        if (small in 1 until r.parking.size) append("Round = small aircraft, square = no size limit.  ")
                        if (r.parking.any { it.alert }) append("Red = the alert cell.  ")
                        if (r.parking.any { it.covered }) append("Bright = under a shelter or a hangar.")
                    }.trim(),
                    color = Hud.TextDim, fontSize = 10.5.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in r.parking) {
                    val mine = p.n == spot?.n
                    val here = p.n == liveSpot
                    // Round for a small stand, square for one with no size limit: the encircled and boxed numbers
                    // on BMS's own parking charts, so the list reads the way the chart beside it does.
                    val shape = RoundedCornerShape(if (p.small) 50 else 4)
                    val edge = when {
                        mine -> Hud.Cyan.copy(alpha = 0.8f)
                        p.alert -> alertInk.copy(alpha = 0.85f)
                        else -> Hud.Outline.copy(alpha = 0.5f)
                    }
                    Text(
                        p.label + if (here) "•" else "",
                        Modifier.clip(shape)
                            .background(if (mine) Hud.Cyan.copy(alpha = 0.22f) else Hud.Surface)
                            .border(1.dp, edge, shape)
                            .clickable { onPick(p.n) }
                            .padding(horizontal = if (p.small) 8.dp else 7.dp, vertical = 4.dp),
                        color = when {
                            mine -> Hud.Cyan
                            p.alert -> alertInk
                            p.covered -> Hud.Text
                            else -> Hud.TextDim
                        },
                        // bold as on the chart, so the alert cell reads apart without its colour
                        fontWeight = if (p.alert && !mine) FontWeight.Bold else null,
                        style = LocalExtra.current.monoSmall,
                    )
                }
            }
        }
    }
}
