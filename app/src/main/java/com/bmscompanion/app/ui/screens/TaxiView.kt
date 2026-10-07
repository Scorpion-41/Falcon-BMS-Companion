package com.bmscompanion.app.ui.screens

import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.runtime.SideEffect
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

    private const val TURN_KEY = "taxi_chart_turn"
    private const val TURN_TALL_KEY = "taxi_chart_turn_tall"
    private var turnPref by mutableStateOf(Repo.getInt(TURN_KEY, 0))
    // an upright chart (a tablet or phone held upright) opens turned to fit: a field drawn north up there filled a third
    private var turnTallPref by mutableStateOf(Repo.getInt(TURN_TALL_KEY, TURN_FIT))

    // a carrier's deck always opens turned to fit: its "north" is the bow, so north up has no meaning there and a long
    // deck drawn upright in a wide box filled a sliver of it
    private const val TURN_DECK_KEY = "taxi_chart_turn_deck"
    private var turnDeckPref by mutableStateOf(Repo.getInt(TURN_DECK_KEY, TURN_FIT))

    /** The chart box is taller than wide: set after each layout by the chart (SideEffect), never during composition. */
    var tall by mutableStateOf(false)

    /** The chart shows a ship's deck: set by the page (SideEffect), never during composition. */
    var deck by mutableStateOf(false)

    /**
     * Which way the chart is turned on this device, kept apart for a carrier's deck ([deck]), an upright chart ([tall])
     * and any other: [TURN_FIT] turns the field to whatever angle fills the page (the boards' `bestFitHeading`), 0 is
     * north up, 90, 180 and 270 the heading put up the page. A deck and an upright chart start at [TURN_FIT], otherwise
     * north up. Kept between launches.
     */
    var turn: Int
        get() = when { deck -> turnDeckPref; tall -> turnTallPref; else -> turnPref }
        set(v) {
            when {
                deck -> { turnDeckPref = v; Repo.putInt(TURN_DECK_KEY, v) }
                tall -> { turnTallPref = v; Repo.putInt(TURN_TALL_KEY, v) }
                else -> { turnPref = v; Repo.putInt(TURN_KEY, v) }
            }
        }

    const val TURN_FIT = -1

    private const val JET_KEY = "taxi_show_jet"
    private var jetPref by mutableStateOf(Repo.getInt(JET_KEY, 1))

    /** Your own jet's symbol on the chart (the page still works out the spot and route from where it stands). */
    var showJet: Boolean
        get() = jetPref != 0
        set(v) { jetPref = if (v) 1 else 0; Repo.putInt(JET_KEY, jetPref) }

    private const val SHEET_KEY = "taxi_sheet_pct"
    private var sheetPct by mutableStateOf(Repo.getInt(SHEET_KEY, (TAXI_SHEET_SHARE * 100).toInt()))

    /** How much of a stacked page the cards' panel takes, 0 when it is folded down to its handle. Kept between launches. */
    var sheetShare: Float
        get() = sheetPct / 100f
        set(v) { sheetPct = (v * 100).roundToInt().coerceIn(0, 70); Repo.putInt(SHEET_KEY, sheetPct) }
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
            TaxiPill("My jet", TaxiPrefs.showJet) { TaxiPrefs.showJet = !TaxiPrefs.showJet }
        }
        // what the radio last said to the flight, and so what the page took from it
        radioLine?.takeIf { !isShip }?.let { line ->
            Spacer(Modifier.height(6.dp))
            Text("RADIO ${radio?.time.orEmpty()}  $line", color = Hud.Green, fontSize = 12.sp, maxLines = 2)
        }
        Spacer(Modifier.height(8.dp))

        val chart = @Composable { m: Modifier ->
            BoxWithConstraints(m.border(1.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))) {
                // a short chart (a tablet on its side under the Mission header) puts the turn buttons beside the zoom ones
                val shortChart = maxHeight < 330.dp
                val tallNow = maxHeight > maxWidth
                SideEffect {
                    if (TaxiPrefs.tall != tallNow) TaxiPrefs.tall = tallNow
                    if (TaxiPrefs.deck != isShip) TaxiPrefs.deck = isShip
                }
                AirfieldChart(
                    field = field,
                    route = if (isShip) null else route,
                    inks = inks,
                    modifier = Modifier.fillMaxSize(),
                    path = path?.nodes,
                    highlight = highlight,
                    you = live.you.takeIf { TaxiPrefs.showJet },
                    youHeading = live.heading,
                    traffic = live.traffic,
                    selectedSpot = if (outbound) spot?.n else liveSpot,
                    destinationSpot = if (outbound) null else spot?.n,
                    state = chartState,
                    zoomAnchor = spot?.let { p -> route?.nodes?.getOrNull(p.k)?.let { Offset(it.e.toFloat(), it.n.toFloat()) } } ?: live.you,
                    // the page's own turn (TaxiPrefs.turn): fitted to the box, or a heading put up the page
                    upHeading = TaxiPrefs.turn.takeIf { it > 0 }?.toDouble(),
                    fitRotation = TaxiPrefs.turn == TaxiPrefs.TURN_FIT,
                    onTapSpot = if (isShip) null else ({ spot -> pickedSpot = spot.n }),
                )
                // nothing is drawn over the chart: the field's details are a card under it (FieldCard)
                // turned, the north arrow in the corner puts it back north up
                if (TaxiPrefs.turn != 0 || chartState.twist != 0f) Box(
                    Modifier.align(Alignment.TopEnd).size(52.dp).clip(RoundedCornerShape(10.dp))
                        .clickable { TaxiPrefs.turn = 0; chartState.reset() }.semantics { contentDescription = "North up" },
                )
                ZoomButtons(Modifier.align(Alignment.BottomEnd).padding(8.dp), inks, chartState, sideBySide = shortChart)
            }
        }

        val cards = @Composable { m: Modifier ->
            Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ClearanceCard(live, spot, outbound, route, liveSpot, clearance, chosenRunway) { highlight = it }
                SpotCard(route, spot, liveSpot, outbound, chosenRunway) { pickedSpot = it }
                FieldCard(field, route, live, if (outbound) null else chosenRunway, chosenRunway)
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
                TaxiLayout.DECK -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    chart(Modifier.fillMaxWidth().weight(1f))
                    FieldCard(field, route, live, null, chosenRunway)
                }
                TaxiLayout.SIDE -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    chart(Modifier.weight(1f).fillMaxHeight())
                    cards(Modifier.width(taxiCardsWidth(w.value).dp).fillMaxHeight())
                }
                // Upright, the chart takes the page and the cards sit in a panel under it that the pilot drags up
                // or folds down to its handle (TaxiSheet): a chart held to a third of a tablet was the complaint.
                TaxiLayout.STACK -> Column(Modifier.fillMaxSize()) {
                    chart(Modifier.fillMaxWidth().weight(1f))
                    TaxiSheet(
                        h.value,
                        title = when {
                            clearance != null -> clearance.line
                            outbound -> "Taxi clearance and ramp spots"
                            else -> "Taxi in and ramp spots"
                        },
                    ) { m -> cards(m) }
                }
            }
        }
    }
}

/**
 * The cards' panel under a stacked chart: a handle the pilot drags (or taps) to raise the panel or fold it away, and
 * the cards scrolling in what it has. Its share of the page is kept between launches ([TaxiPrefs.sheetShare]).
 */
@Composable
private fun TaxiSheet(pageDp: Float, title: String, content: @Composable (Modifier) -> Unit) {
    var panel by remember(pageDp) { mutableStateOf(taxiSheetHeight(pageDp, TaxiPrefs.sheetShare)) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val most = pageDp * TAXI_SHEET_MAX
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { px ->
        panel = (panel - with(density) { px.toDp().value }).coerceIn(0f, most)
    }
    val settle = {
        // a panel dragged nearly shut folds away rather than leaving a sliver of card
        if (panel < 90f) panel = 0f
        TaxiPrefs.sheetShare = if (pageDp > 0f) panel / pageDp else TAXI_SHEET_SHARE
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(TAXI_SHEET_HANDLE_DP.dp)
                .draggable(drag, androidx.compose.foundation.gestures.Orientation.Vertical, onDragStopped = { settle() })
                .clickable {
                    panel = if (panel > 0f) 0f else taxiSheetHeight(pageDp, TAXI_SHEET_SHARE)
                    settle()
                }
                .semantics { contentDescription = if (panel > 0f) "Fold the cards away" else "Show the cards" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(width = 34.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Hud.TextFaint))
            Spacer(Modifier.width(12.dp))
            Text(title, Modifier.weight(1f), color = Hud.Text, fontSize = 12.5.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            androidx.compose.material3.Icon(
                if (panel > 0f) androidx.compose.material.icons.Icons.Default.ExpandMore else androidx.compose.material.icons.Icons.Default.ExpandLess,
                null, tint = Hud.Cyan, modifier = Modifier.padding(horizontal = 10.dp).size(22.dp),
            )
        }
        if (panel > 0f) content(Modifier.fillMaxWidth().height(panel.dp))
    }
}

/** The handle of the cards' panel under a stacked chart, in dp. */
const val TAXI_SHEET_HANDLE_DP = 40f

/** The panel's share of a stacked page to begin with: the chart keeps about three fifths, far more on a tall tablet. */
const val TAXI_SHEET_SHARE = 0.34f

/** The most of the page the panel may be raised to: the chart always keeps the rest. */
const val TAXI_SHEET_MAX = 0.7f

/** The cards' panel at [share] of a stacked page of [hDp], in dp: never under a card's worth unless folded away. */
fun taxiSheetHeight(hDp: Float, share: Float): Float =
    if (share <= 0f) 0f else (hDp * share).coerceIn(minOf(150f, hDp * TAXI_SHEET_MAX), hDp * TAXI_SHEET_MAX)

/** How the Taxi page sets out its chart and its two cards (the clearance and the ramp spots). */
enum class TaxiLayout {
    /** a carrier: the deck and nothing else */
    DECK,
    /** the chart on the left taking the whole height, the cards in their own scrolling column on the right */
    SIDE,
    /** the chart above taking most of the page, the cards in a panel under it that drags up or folds away ([TaxiSheet]) */
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
 * and when stacked the cards have a panel of their own under the chart ([TaxiSheet], [taxiChartHeight]).
 */
fun taxiLayout(wDp: Float, hDp: Float, ship: Boolean, wideScreen: Boolean): TaxiLayout = when {
    ship -> TaxiLayout.DECK
    wideScreen || (wDp >= 480f && wDp > hDp) -> TaxiLayout.SIDE
    else -> TaxiLayout.STACK
}

/** The cards' column beside the chart: about two fifths of the width, never so narrow the steps wrap every word. */
fun taxiCardsWidth(wDp: Float): Float = (wDp * 0.385f).coerceIn(260f, 420f).coerceAtMost(wDp * 0.5f)

/**
 * A stacked chart's height with the cards' panel at [share] of the page: everything the panel and its handle leave.
 * Up to 1.3.8 a stacked chart was held to 340 dp, a third of a tablet held upright.
 */
fun taxiChartHeight(hDp: Float, share: Float = TAXI_SHEET_SHARE): Float = hDp - TAXI_SHEET_HANDLE_DP - taxiSheetHeight(hDp, share)

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
private fun ZoomButtons(modifier: Modifier, inks: ChartInks, state: com.bmscompanion.app.ui.components.ChartState, sideBySide: Boolean = false) {
    // Turning the chart: the field turned to fill the box (pressed again, north up), and a quarter turn at a time from
    // whatever the page shows. Remembered on the device (TaxiPrefs.turn); the north arrow says where north went.
    val turnButtons = @Composable {
        val fit = TaxiPrefs.turn == TaxiPrefs.TURN_FIT
        buttonFace(inks, fit, if (fit) "North up" else "Turn the field to fill the chart", {
            TaxiPrefs.turn = if (fit) 0 else TaxiPrefs.TURN_FIT
            state.reset()
        }) { iconFace(inks, androidx.compose.material.icons.Icons.Default.ScreenRotation, fit) }
        buttonFace(inks, false, "Turn the chart 90 degrees", {
            TaxiPrefs.turn = ((state.turnShown.roundToInt() + 90) % 360 + 360) % 360
            state.reset()
        }) { iconFace(inks, androidx.compose.material.icons.Icons.AutoMirrored.Filled.RotateRight, false) }
    }
    // the turn buttons in a column of their own beside the zoom ones when the chart is short, else above them
    if (sideBySide) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { turnButtons() }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { zoomColumn(inks, state) }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            turnButtons()
            Spacer(Modifier.height(4.dp))
            zoomColumn(inks, state)
        }
    }
}

@Composable
private fun buttonFace(inks: ChartInks, on: Boolean, describe: String, action: () -> Unit, face: @Composable () -> Unit) = Box(
    Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
        .background(if (on) inks.accent.copy(alpha = 0.9f) else inks.ground.copy(alpha = 0.88f))
        .border(1.dp, inks.dim.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
        .clickable(onClick = action)
        .semantics { contentDescription = describe },
    contentAlignment = Alignment.Center,
) { face() }

@Composable
private fun iconFace(inks: ChartInks, v: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean) =
    androidx.compose.material3.Icon(v, null, tint = if (on) inks.ground else inks.ink, modifier = Modifier.size(18.dp))

/** Zoom in, zoom out, fit — the fit button an icon: the browser's font has no ⤢ (CLAUDE.md, the glyph gotcha). */
@Composable
private fun zoomColumn(inks: ChartInks, state: com.bmscompanion.app.ui.components.ChartState) {
    buttonFace(inks, false, "Zoom in", { state.zoomBy(com.bmscompanion.app.ui.components.ZoomMath.BUTTON) }) { Text("+", color = inks.ink, fontSize = 15.sp) }
    buttonFace(inks, false, "Zoom out", { state.zoomBy(1f / com.bmscompanion.app.ui.components.ZoomMath.BUTTON) }) { Text("−", color = inks.ink, fontSize = 15.sp) }
    buttonFace(inks, false, "Fit the chart", { state.reset() }) { iconFace(inks, androidx.compose.material.icons.Icons.Default.FitScreen, false) }
}

/**
 * The field's details — name and ICAO, runways and their size, the arresting cables, how the ramp is numbered and made
 * up (a ship: its class and deck) — as a card under the chart. Up to 1.3.8 they were a translucent box drawn over the
 * chart's top corner, which covered part of the field.
 */
@Composable
private fun FieldCard(field: Airfield, route: AfRoute?, live: TaxiLive, landedOn: String?, runwayInUse: String?) {
    SectionCard(field.icao?.let { "${field.name} · $it" } ?: field.name, accent = Hud.Green) {
        FieldFacts(field, route, live, landedOn, runwayInUse, ink = Hud.Text, dim = Hud.TextDim, accent = Hud.Amber)
    }
}

@Composable
private fun FieldFacts(
    field: Airfield, route: AfRoute?, live: TaxiLive, landedOn: String?, runwayInUse: String?,
    ink: androidx.compose.ui.graphics.Color, dim: androidx.compose.ui.graphics.Color, accent: androidx.compose.ui.graphics.Color,
) {
    val inks = object { val ink = ink; val dim = dim; val accent = accent }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // A ship says what it is instead: BMS's runway record for a carrier is the approach path, a catapult and
        // sometimes a rectangle of no width, and the deck the chart draws comes from the real ship.
        val ship = field.ship
        if (ship != null) {
            Text(ship.cls, color = inks.dim, fontSize = 12.sp)
            // where the drawing comes from, and what the ramp does, in the words a deck chart would use
            val about = listOfNotNull(
                ship.model.takeIf { it.isNotEmpty() }?.let { "deck from $it" },
                ship.skiDeg?.let { "${it.roundToInt()}° ski jump" },
            )
            if (about.isNotEmpty()) Text(about.joinToString(" · "), color = inks.dim, fontSize = 12.sp)
            // A ship's page has no clearance card to carry the note, so it goes here: on a deck the Tacview feed is
            // what puts the jet on the chart at all.
            live.note?.let { Text(it, color = inks.accent, fontSize = 12.sp, modifier = Modifier.widthIn(max = 280.dp)) }
        }
        else Text(chartRunways(field).joinToString("   ") { "${it.name}  ${it.lengthFt} × ${it.widthFt} ft" }, color = inks.dim, fontSize = 12.sp)
        // The arresting cables, from the end in use (or every end with one), as the distance from that end
        if (ship == null) cablesLines(field, runwayInUse).forEach { Text(it, color = inks.ink, fontSize = 12.sp, modifier = Modifier.widthIn(max = 320.dp)) }
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
            Text("$numbered · ${it.parking.size} spots$sizes$split$cell", color = inks.dim, fontSize = 12.sp)
        }
        if (live.traffic.isNotEmpty()) Text("${live.traffic.size} aircraft on the field", color = inks.dim, fontSize = 12.sp)
    }
}

/**
 * The arresting cables as lines of text: for the runway in use only ("Cables from 36: 1,500 ft · 7,500 ft"), else one
 * line per runway with the distances from each of its ends. Empty where BMS lays no cable.
 */
fun cablesLines(field: Airfield, runwayInUse: String?): List<String> {
    val withCables = field.runways.filter { it.cables.isNotEmpty() }
    if (withCables.isEmpty()) return emptyList()
    val inUse = runwayInUse?.let { d -> withCables.firstOrNull { r -> r.ends.any { it.designator == d } } }
    if (runwayInUse != null) {
        val r = inUse ?: return listOf("No arresting cables on runway $runwayInUse")
        return listOf("Cables from $runwayInUse: " + com.bmscompanion.app.data.airfield.cableDistances(r.cablesFrom(runwayInUse)))
    }
    return withCables.map { r ->
        "Cables " + r.ends.joinToString("  ") { e -> "from ${e.designator}: " + com.bmscompanion.app.data.airfield.cableDistances(r.cablesFrom(e.designator)) }
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
