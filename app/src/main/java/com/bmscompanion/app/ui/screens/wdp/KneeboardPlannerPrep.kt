package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The Planner's pages filled for Upd Kneeboard when the window is opened outside the Planner in **WDP mode** (1.4.1:
 * from the Kneeboards page). The DataCard, the Coordination Card, the Briefing page, the target list, the route
 * map, the Airport Schedule and the ground charts are drawn from the Planner's own pages ([WdpSession]), which the
 * Planner fills when it is on screen. In WDP mode it may not have been yet: then they are filled here exactly as the
 * Planner fills them — from Falcon BMS's own files
 * (`MissionLink.bmsFiles`: the printed briefing, the cartridge, BMS's route; [plannerMission]), in the theater BMS is
 * set to, with the save's flight Open mission… picked in WDP mode — and the Planner, shown later, finds them filled
 * for the same mission and leaves them as they are. **Never in EZBoards mode**: the window offers no page of the
 * Planner's there, and the pages it offers read the Mission section's own data ([KneeboardEzSource]).
 *
 * Read only: nothing is written anywhere (the cartridge is read through the PC, as the Planner reads it). Pages the
 * Planner itself filled in this run (WDP mode, also after a switch to EZBoards mode) are what print, with whatever the
 * pilot typed on them, and nothing here touches them; only pages filled here are filled again for a new briefing.
 */
internal object KneeboardPlannerPrep {
    /** Filling the pages now (the window says so). */
    var preparing by mutableStateOf(false)
        private set

    /** The mission filled here last: a mission the Planner itself applied is never replaced from here. */
    private var prepared: WdpMission? = null

    suspend fun ensure() {
        val wdp = KneeboardPrintSession.wdpMode()
        // EZBoards mode (1.4.1): no page of the Planner's is offered there, so nothing of it is filled
        if (!wdp || WdpSession.started) return
        // pages the Planner filled in this run (WDP mode, before a switch) are the pilot's work: printed as left
        val cur = WdpSession.appliedMission
        if (cur != null && cur !== prepared) return
        try {
            // BMS's own files, which the link fetches by itself (a few seconds at most)
            var data = MissionLink.bmsFiles.value
            var waited = 0
            while (data == null && waited++ < 50) { delay(100); data = MissionLink.bmsFiles.value }
            val info = MissionLink.info.value
            // a save's flight is planned in the save's own theater, as WdpPane does (WDP mode only)
            val saveTheater = if (wdp) PlannerMissionState.theater?.takeIf { PlannerMissionState.fromSave && PlannerMissionState.flight != null } else null
            val name = saveTheater ?: info?.bms?.theater ?: MissionLink.live.value?.theater
            val theater = if (name != null && name == WdpSession.theaterName && WdpSession.theater != null) WdpSession.theater
            else plannerTheater(Repo.index().theaters, name)
            val flight = if (wdp) PlannerMissionState.flight.takeIf { PlannerMissionState.fromSave } else null
            val mission = plannerMission(data, theater, flight)
            if (mission == WdpSession.appliedMission) return
            preparing = true
            WdpSession.theaterName = name
            WdpSession.theater = theater
            // what WdpPlanner does for a new mission, in its order
            val toss = WdpSession.toss
            val popup = WdpSession.popup
            val hadb = WdpSession.hadb
            val dataCard = WdpSession.dataCard
            val dtc = WdpSession.dtc
            val performance = WdpSession.performance
            dataCard.attackPages = { Triple(popup.plan, hadb.plan, toss.plan) }
            mission.coords?.let { c ->
                if (c != WdpSession.appliedCoords) {
                    popup.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
                    hadb.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
                    WdpSession.appliedCoords = c
                }
            }
            val attackMission = mission.withPlaced(dtc.placedThisSession())
            toss.onMission(attackMission)
            popup.onMission(attackMission)
            hadb.onMission(attackMission)
            WdpSession.appliedAttackMission = attackMission
            dataCard.prepare(mission)
            dtc.onMission(mission)
            performance.prepare(mission)
            WdpHandOff.cardToPerformance(dataCard, performance, mission.briefing)
            WdpSession.appliedMission = mission
            prepared = mission
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the pages print what they have; the window is not the place for a stack trace
        } finally {
            preparing = false
        }
    }
}
