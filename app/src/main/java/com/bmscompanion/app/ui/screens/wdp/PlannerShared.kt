package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PlannerSync
import com.bmscompanion.app.data.mission.PlannerSyncClient

/**
 * The Planner's pieces of planning every device shares through the PC ([PlannerSync], 1.4.2): the open flight
 * ([PlannerMissionState]), the Upd Kneeboard plan ([KneeboardPrintSession]) and the Steps ([PlannerSteps]). Registered
 * here, once, before the first [sync]; their owners post their own changes.
 *
 * Synced when the device links to the PC ([PlannerSharedWatcher], in AppRoot), when the Planner is shown and when Upd
 * Kneeboard reads its pages ([KneeboardPrintSession.load]) — the flight first, so the kneeboard's lists are of it.
 */
object PlannerShared {
    private var registered = false

    fun ensure() {
        if (registered) return
        registered = true
        PlannerSync.register(PlannerSync.FLIGHT, PlannerSyncClient.Part({ PlannerMissionState.sharedValue() }) { PlannerMissionState.adoptShared(it) })
        PlannerSync.register(PlannerSync.KNEEBOARD, PlannerSyncClient.Part({ KneeboardPrintSession.sharedValue() }) { KneeboardPrintSession.adoptShared(it) })
        PlannerSync.register(PlannerSync.STEPS, PlannerSyncClient.Part({ PlannerSteps.load(); PlannerSteps.sharedValue() }) { PlannerSteps.adoptShared(it) })
        // and the pages' entries and where the pilot is in the Planner, key by key (MissionSync, 1.4.2)
        PlannerSessionSync.ensure()
    }

    /** Brings every piece in step with the PC; false when the PC could not be reached. Never throws. */
    suspend fun sync(): Boolean {
        ensure()
        return PlannerSync.sync(PlannerSync.FLIGHT, PlannerSync.KNEEBOARD, PlannerSync.STEPS)
    }
}

/** Syncs the planning each time the device links to the PC (drawn once in AppRoot, on every device). */
@Composable
fun PlannerSharedWatcher() {
    val link by MissionLink.state.collectAsState()
    val online = link is LinkState.Online
    LaunchedEffect(online) { if (online) PlannerShared.sync() }
}
