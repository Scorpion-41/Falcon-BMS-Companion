package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.ui.screens.mission.airbases
import kotlinx.coroutines.CancellationException

/**
 * What Upd Kneeboard's pages draw from **in EZBoards mode** (1.4.1): the Mission section's own data — the printed
 * briefing, the cartridge and BMS's route as `/api/mission` serves them, and the flight's departure, arrival and
 * alternate fields as the Mission section finds them ([airbases]) — and **never the Planner's pages**: in EZBoards mode
 * the window offers no page of the Planner's ([KneeboardPrintSession.offered]), and the pages it does offer that are
 * drawn by [KneeboardExtraPages] (the route maps and the ground charts) read the mission and the airfields here
 * ([KneeboardExtraPages.mission], [KneeboardExtraPages.cardAirport]) instead of the Planner's.
 *
 * [active] only when the PC says it is in EZBoards mode: with no word from the PC (a headless check before the link)
 * the pages read the Planner as they always did.
 */
internal object KneeboardEzSource {
    /** The mission the pages draw in EZBoards mode, or null before [prepare] has run. */
    var mission by mutableStateOf<WdpMission?>(null)
        private set

    /** The flight's departure, arrival and alternate fields (null where the mission names none or the app has none). */
    var airports by mutableStateOf<List<Airport?>>(listOf(null, null, null))
        private set

    private var preparedFor: Any? = null

    /** The PC said it is in EZBoards mode (its pages' state, else its `/api/info`). */
    val active: Boolean
        get() {
            val m = KneeboardPrintSession.state?.mode ?: MissionLink.info.value?.mission?.mode ?: return false
            return MissionMode.of(m) == MissionMode.EZBOARDS
        }

    /** Reads the Mission section's mission, its theater and its fields (again only when the mission changed). */
    suspend fun prepare(data: MissionData? = MissionLink.mission.value) {
        val name = MissionLink.info.value?.bms?.theater ?: MissionLink.live.value?.theater
        val key = listOf(data?.version, name)
        if (key == preparedFor && mission != null) return
        try {
            val theater = plannerTheater(Repo.index().theaters, name)
            val set = theater?.let { Repo.airportSet(it.airportSet) }
            val bases = airbases(MissionLink.live.value, data?.briefing)
            airports = listOf(bases.departureIn(set), bases.arrivalIn(set), bases.alternateIn(set))
            mission = WdpMission.of(data).copy(theater = theater)
            preparedFor = key
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the pages say what they lack
        }
    }

    /** For the checks: forgets what was read. */
    fun forget() { mission = null; airports = listOf(null, null, null); preparedFor = null }
}
