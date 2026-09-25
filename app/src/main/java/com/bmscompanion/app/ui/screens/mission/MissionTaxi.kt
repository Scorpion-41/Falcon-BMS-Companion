package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    // from, then whatever the first steerpoint is nearest. Only while the jet is slow: airborne, the nearest field
    // changes every few seconds and the page would swap charts under the pilot.
    val onGround = (live?.gsKts ?: 999.0) < 150
    val nearest = remember(live?.x, live?.y, onGround, charted) {
        val jet = live?.takeIf { (it.x != 0.0 || it.y != 0.0) && onGround } ?: return@remember null
        charted.minByOrNull { hypot(it.x - jet.x, it.y - jet.y) }?.takeIf { hypot(it.x - jet.x, it.y - jet.y) < 4 * 6076 }
    }
    val briefed = remember(env.set, chartIndex, mission) {
        val bases = airbases(live, mission?.briefing)
        (matchAirport(env.set, bases.departure) ?: matchAirport(env.set, bases.arrival))
            ?.takeIf { chartIndex.containsKey(it.id.toString()) }
    }
    val planned = remember(mission, charted) {
        mission?.dtc?.steerpoints?.firstOrNull()?.let { sp -> charted.minByOrNull { hypot(it.x - sp.x, it.y - sp.y) } }
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

    val you = remember(f.id, live?.x, live?.y) {
        live?.let { fieldOffset(f, it.x, it.y) }?.let { Offset(it.first.toFloat(), it.second.toFloat()) }
    }
    val traffic = remember(f.id, contacts) { fieldTraffic(f, contacts?.contacts.orEmpty()) }

    val streaming = contacts?.contacts?.isNotEmpty() == true || (info?.tacview?.objects ?: 0) > 0
    val note = when {
        live?.flying != true -> "No live position yet: BMS is not in 3D. Pick your spot below and the chart works the same."
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
        live = TaxiLive(you = you, heading = live?.hdgTrue, onGround = onGround, traffic = traffic, note = note),
        onChoice = { MissionLink.publishTaxi(it.airportId, it.runway, it.outbound, it.spot) },
    )
}

@Composable
private fun TaxiEmpty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Hud.TextDim, fontSize = 13.sp)
    }
}
