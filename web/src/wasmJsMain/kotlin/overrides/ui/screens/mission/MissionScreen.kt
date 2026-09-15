package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.theme.Hud

// Browser version of app/.../ui/screens/mission/MissionScreen.kt (tabs and content are shared in MissionTabs.kt):
// polls while the Mission screen is shown, and has no keep-screen-on button.

@Composable
fun MissionScreen(nav: NavHostController) {
    // On a PC the window often sits behind (or next to) BMS without focus: keep polling while this screen exists.
    DisposableEffect(Unit) {
        MissionLink.acquire()
        onDispose { MissionLink.release() }
    }
    val state by MissionLink.state.collectAsState()
    val info by MissionLink.info.collectAsState()
    val live by MissionLink.live.collectAsState()

    var tab by rememberMissionTab()
    val env = rememberMissionEnv(nav)
    val onTab: (MissionTab) -> Unit = { tab = it; saveMissionTab(it) }

    Column(Modifier.fillMaxSize().background(Hud.Bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            MissionStatus(
                state, info, live,
                badge = null,
                idleText = "Connecting to BMS Companion on the PC…",
                onSetup = { onTab(MissionTab.SETUP) },
                modifier = Modifier.weight(1f),
            )
        }
        MissionTabStrip(tab, onTab)
        Box(Modifier.weight(1f).fillMaxWidth()) { MissionTabContent(tab, env, onTab) }
    }
}
