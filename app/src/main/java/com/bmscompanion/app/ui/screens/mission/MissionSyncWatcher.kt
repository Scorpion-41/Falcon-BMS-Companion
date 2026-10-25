package com.bmscompanion.app.ui.screens.mission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionSync
import com.bmscompanion.app.data.mission.PrefWatch
import com.bmscompanion.app.ui.screens.wdp.PlannerMissionState
import com.bmscompanion.app.ui.screens.wdp.PlannerSessionSync
import com.bmscompanion.app.ui.screens.wdp.PlannerShared

/**
 * Where the pilot was on another device, once this one becomes active ([MissionSync], 1.4.2): set when the Mission tab
 * or the Planner's page was taken, so the app opens the Mission section if it is elsewhere ([MissionSyncWatcher]).
 */
object MissionSyncNav {
    var goMission by mutableStateOf(false)
}

/**
 * The Mission section in step with the other devices (drawn once in AppRoot, on every device but a VR board): the poll
 * runs while the link is up ([MissionSync.linked]); the device becomes active ([MissionSync.activated]) when the app
 * starts and links, when its window takes the focus (an Android app brought to the front, the PC window clicked, a
 * browser tab or window focused; the browser also tells of a tab shown again, web `Main.kt`); the Planner's flight
 * changing takes that flight's entries made elsewhere ([PlannerSessionSync.flightOpened]); a Mission tab taken from
 * another device is opened, and the Mission section with it when the app is elsewhere ([inMission], [openMission]).
 */
@Composable
fun MissionSyncWatcher(inMission: Boolean, openMission: () -> Unit) {
    if (com.bmscompanion.app.ui.Kneeboard.on) return
    remember {
        PlannerShared.ensure()
        MissionSync.onPlannerChanged = { PlannerShared.sync() }
        // the Mission tab another device was on (taken only as this one becomes active: MissionPrefs.NAV_KEYS)
        PrefWatch.on("mission_tab") {
            val t = Repo.getString("mission_tab")?.let { s -> MissionTab.entries.firstOrNull { it.name == s } }
            if (t != null) { MissionTabRequest.open(t); MissionSyncNav.goMission = true }
        }
        true
    }
    val link by MissionLink.state.collectAsState()
    val online = link is LinkState.Online
    LaunchedEffect(online) { MissionSync.linked(online) }
    // the window taking the focus (not the first composition: linking is the activation then)
    val focused = LocalWindowInfo.current.isWindowFocused
    var was by remember { mutableStateOf(focused) }
    LaunchedEffect(focused) {
        if (focused && !was) MissionSync.activated()
        was = focused
    }
    // the Planner's flight (or the printed briefing's) changed: its entries made on other devices
    val files by MissionLink.bmsFiles.collectAsState()
    @Suppress("UNUSED_VARIABLE") val read = PlannerMissionState.flight to PlannerMissionState.seat
    val fk = remember(files?.briefingModified, files?.briefing, PlannerMissionState.flight, PlannerMissionState.seat, PlannerMissionState.fromSave) { PlannerSessionSync.flightKey() }
    LaunchedEffect(fk, online) { if (fk != null && online) PlannerSessionSync.flightOpened() }
    // where the pilot was: the Mission section, when the app is elsewhere
    val open by rememberUpdatedState(openMission)
    val go = MissionSyncNav.goMission
    LaunchedEffect(go) {
        if (go) {
            MissionSyncNav.goMission = false
            if (!inMission) open()
        }
    }
}
