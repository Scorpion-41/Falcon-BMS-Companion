package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.data.airfield.deckPosition
import com.bmscompanion.app.data.airfield.fieldOffset
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.screens.TaxiLive
import com.bmscompanion.app.ui.screens.fieldTraffic
import com.bmscompanion.app.ui.screens.TaxiView
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.hypot

/**
 * The Taxi page: the field you are at, drawn from BMS's own data, with the jet on it.
 *
 * The position comes from BMS's shared memory, which is filled whenever the sim is running — the Tacview stream
 * that the live map needs is only for *other* aircraft. Without either, the pilot names the spot and everything
 * works the same.
 */
@Composable
fun MissionTaxiPane(env: MissionEnv) {
    val mission by MissionLink.mission.collectAsState()
    // the field, runway, direction and spot picked here belong to one mission: a new PRINT, a Populate or a switch of
    // mode starts the page afresh (MissionData.missionKey), so the last mission's choice never stands for this one
    key(mission?.missionKey) { MissionTaxiBody(env) }
}

@Composable
private fun MissionTaxiBody(env: MissionEnv) {
    val live by MissionLink.live.collectAsState()
    val mission by MissionLink.mission.collectAsState()
    val contacts by MissionLink.contacts.collectAsState()
    val info by MissionLink.info.collectAsState()

    val chartIndex by produceState(emptyMap<String, String>(), env.theater?.airfieldSet) {
        value = env.theater?.airfieldSet?.let { Repo.airfieldIndex(it) }.orEmpty()
    }
    val charted = remember(env.set, chartIndex) {
        env.set?.airports.orEmpty().filter { chartIndex.containsKey(it.id.toString()) }.sortedBy { it.name }
    }

    // Which field to open. Sitting on one wins — that is where the pilot is — then the field the briefing departs
    // from, then the field STPT 1 of the merged route (the take-off) is on. Only while the jet is slow: airborne, the nearest field
    // changes every few seconds and the page would swap charts under the pilot.
    val onGround = (live?.gsKts ?: 999.0) < 150
    val nearest = remember(live?.x, live?.y, onGround, charted) {
        val jet = live?.takeIf { (it.x != 0.0 || it.y != 0.0) && onGround } ?: return@remember null
        charted.minByOrNull { hypot(it.x - jet.x, it.y - jet.y) }?.takeIf { hypot(it.x - jet.x, it.y - jet.y) < 4 * 6076 }
    }
    // a carrier is named only "USS" (the first word); the ladder, the TACAN and the ship under the jet say which one
    val deck = deckHulls(live, contacts?.contacts)
    // the mission with BMS's route and the sent plan merged in: its STPT 1 is the home field, whatever the cartridge
    // holds (in 4.38.1 its first non-zero point is a recon target, often at an enemy airbase)
    val merged = rememberMerged()
    val briefed = remember(env.set, chartIndex, merged, live?.voice, live?.tacanUfc, live?.tacanAux, deck) {
        val bases = if (merged.briefing != null) airbases(live, merged, contacts?.contacts) else airbases(live, mission?.briefing, contacts?.contacts)
        (bases.departureIn(env.set) ?: bases.arrivalIn(env.set))
            ?.takeIf { chartIndex.containsKey(it.id.toString()) }
    }
    val planned = remember(merged, charted) {
        merged.home?.let { sp -> charted.minByOrNull { hypot(it.x - sp.x, it.y - sp.y) }?.takeIf { hypot(it.x - sp.x, it.y - sp.y) < 5 * 6076 } }
    }

    var pickedField by rememberSaveable { mutableStateOf<Int?>(null) }
    val airport = charted.firstOrNull { it.id == pickedField } ?: nearest ?: briefed ?: planned ?: charted.firstOrNull()

    val field by produceState<Airfield?>(null, airport?.id, env.theater?.airfieldSet) {
        val set = env.theater?.airfieldSet
        value = if (set != null && airport != null) Repo.airfield(set, airport.id) else null
    }

    if (charted.isEmpty()) { TaxiEmpty("No ground charts for this theater yet."); return }
    val f = field
    if (f == null) { TaxiEmpty("Opening the ground chart…"); return }

    // A ship moves, so the jet goes on its deck through the ship's own live position and heading from the Tacview
    // feed; an airfield stays put, so there it is a subtraction. See deckPosition.
    val onDeck = remember(f.id, live, contacts) { deckPosition(f, live, contacts?.contacts, contacts?.t ?: 0L) }
    val you = remember(f.id, live?.x, live?.y, onDeck) {
        if (f.ship != null) onDeck?.let { Offset(it.e.toFloat(), it.n.toFloat()) }
        else live?.let { fieldOffset(f, it.x, it.y) }?.let { Offset(it.first.toFloat(), it.second.toFloat()) }
    }
    val traffic = remember(f.id, contacts) { fieldTraffic(f, contacts?.contacts.orEmpty()) }

    val streaming = contacts?.contacts?.isNotEmpty() == true || (info?.tacview?.objects ?: 0) > 0
    val note = when {
        live?.flying != true -> "No live position yet: BMS is not in 3D. Pick your spot below and the chart works the same."
        // on a ship the feed is not only for other aircraft: it is the only thing that says where the ship is
        f.ship != null && you == null && !streaming ->
            "Your place on the deck needs ACMI recording — press F in the cockpit. The ship moves, and only the Tacview feed says where it is and which way it is heading."
        f.ship != null && you == null -> "You are not on this ship."
        you == null -> "You are not at this field — the chart is working from the spot you pick."
        !streaming -> "Other aircraft need ACMI recording — press F in the cockpit."
        else -> null
    }

    TaxiView(
        field = f,
        modifier = Modifier.fillMaxSize(),
        airports = charted,
        airport = airport,
        onAirport = { pickedField = it.id },
        // on a deck the chart's north is the ship's bow, so the jet's heading is taken from the ship's
        live = TaxiLive(you = you, heading = if (f.ship != null) onDeck?.heading else live?.hdgTrue, onGround = onGround, traffic = traffic, note = note),
        onChoice = { MissionLink.publishTaxi(it.airportId, it.runway, it.outbound, it.spot) },
        // Ground's and Tower's calls to the flight, while BMS writes its debug log (RadioLog on the PC); nothing without it
        radio = com.bmscompanion.app.data.mission.RadioFeed.log.taxi,
    )
    com.bmscompanion.app.data.mission.RadioPolling(
        on = info?.radio?.state.let { it == com.bmscompanion.app.data.mission.RadioLogStatus.LIVE || it == com.bmscompanion.app.data.mission.RadioLogStatus.WAITING },
        periodMs = 2000,
    )
}

@Composable
private fun TaxiEmpty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Hud.TextDim, fontSize = 13.sp)
    }
}
