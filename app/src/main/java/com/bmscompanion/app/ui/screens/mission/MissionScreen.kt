package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra

// The tabs and their content are in MissionTabs.kt (shared with the PC version, which has its own MissionScreen).

@Composable
fun MissionScreen(nav: NavHostController) {
    // Poll the bridge only while this screen is resumed.
    LifecycleResumeEffect(Unit) {
        MissionLink.acquire()
        onPauseOrDispose { MissionLink.release() }
    }
    val state by MissionLink.state.collectAsState()
    val info by MissionLink.info.collectAsState()
    // (the live readings, four a second, are read by the header alone: read here they recomposed the whole section)

    var tab by rememberMissionTab()
    // the screen stays on while the Mission section is shown (it is read in flight, hands on the stick). The sun
    // button that switched this off was taken out in 1.3.8: pilots took it for a day/night switch that did nothing.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val env = rememberMissionEnv(nav)
    val onTab: (MissionTab) -> Unit = { tab = it; saveMissionTab(it) }

    // The Dashboard can take the whole pane (DashFocus); every other tab always keeps its chrome.
    val focused = ((DashFocus.on || MfdFull.on) && tab == MissionTab.DASH) ||
        (com.bmscompanion.app.ui.screens.wdp.WdpFocus.on && tab == MissionTab.PLANNER && info.wdpMode)
    // Back leaves the MFDs' full page before it leaves the section (this file is Android's alone)
    androidx.activity.compose.BackHandler(enabled = MfdFull.on) { MfdFull.on = false }
    Column(Modifier.fillMaxSize().background(Hud.Bg)) {
        if (!focused) MissionHeader(state, info, onSetup = { nav.go(com.bmscompanion.app.ui.Routes.SETUP) })
        // where everything below comes from, on every tab: the EZBoards | WDP switch and its line (MissionSource.kt)
        if (!focused) MissionSourceBar(onTab)
        if (!focused) MissionTabStrip(tab, onTab)
        Box(Modifier.weight(1f).fillMaxWidth()) { MissionTabContent(tab, env, onTab) }
    }
}

@Composable
private fun MissionHeader(
    state: LinkState,
    info: com.bmscompanion.app.data.mission.BridgeInfo?,
    onSetup: () -> Unit,
) {
    val live by MissionLink.live.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MissionStatus(state, info, live, badge = null, idleText = "Connect to BMS Companion on the PC to see live mission data", onSetup = onSetup)
        Spacer(Modifier.weight(1f))
    }
}
