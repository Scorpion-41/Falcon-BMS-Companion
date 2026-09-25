package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.AirportSet
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.EmptyState
import com.bmscompanion.app.ui.components.LoadingBox

/**
 * A field's ground chart on its own, away from any mission: pick a theater in Airfields, pick a field, and read the
 * taxiways, the ramp and the way between them. The same page the Mission section's Taxi tab shows, without a jet on
 * it — for planning, or for learning a field before you fly it.
 */
@Composable
fun GroundChartRoute(nav: NavHostController, theaterId: String, id: Int, onBack: (() -> Unit)? = { nav.popBackStack() }) {
    val th by produceState<Theater?>(null, theaterId) { value = Repo.theater(theaterId) }
    val set by produceState<AirportSet?>(null, th) { value = th?.let { Repo.airportSet(it.airportSet) } }
    val index by produceState(emptyMap<String, String>(), th) {
        value = th?.airfieldSet?.let { Repo.airfieldIndex(it) }.orEmpty()
    }
    val charted = remember(set, index) {
        set?.airports.orEmpty().filter { index.containsKey(it.id.toString()) }.sortedBy { it.name }
    }
    var picked by rememberSaveable { mutableIntStateOf(id) }
    val airport = charted.firstOrNull { it.id == picked } ?: charted.firstOrNull()
    val field by produceState<Airfield?>(null, airport?.id, th) {
        val setId = th?.airfieldSet
        value = if (setId != null && airport != null) Repo.airfield(setId, airport.id) else null
    }

    Column(Modifier.fillMaxSize()) {
        BmsTopBar(
            airport?.name ?: "Ground chart",
            listOfNotNull(airport?.icao, th?.name).joinToString(" · "),
            onBack = onBack,
        )
        when {
            set == null || th == null -> LoadingBox()
            charted.isEmpty() -> EmptyState("No ground charts for this theater")
            field == null -> LoadingBox()
            else -> TaxiView(
                field = field!!,
                modifier = Modifier.fillMaxSize(),
                airports = charted,
                airport = airport,
                onAirport = { picked = it.id },
            )
        }
    }
}
