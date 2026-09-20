package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.mission.LinkMode
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.desktop.LocalPcActions
import com.bmscompanion.desktop.WindowControls

// PC version of app/.../ui/screens/mission/MissionScreen.kt (tabs and content are shared in MissionTabs.kt). Differences:
// - polls while the Mission screen is shown, even when the window is behind BMS (Android: only while resumed);
// - the header's keep-screen-on button becomes "keep window on top", plus full screen and the switch to the server page;
// - the header shows whether the link is "THIS PC" or "LAN".

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
    val mode by MissionLink.mode.collectAsState()
    val actions = LocalPcActions.current

    var tab by rememberMissionTab()
    val env = rememberMissionEnv(nav)
    val onTab: (MissionTab) -> Unit = { tab = it; saveMissionTab(it) }

    Column(Modifier.fillMaxSize().background(Hud.Bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            MissionStatus(
                state, info, live,
                badge = mode?.let { if (it == LinkMode.LOCAL) "THIS PC" else "LAN" },
                idleText = "Choose where Falcon BMS runs in Setup to see live mission data",
                onSetup = { onTab(MissionTab.SETUP) },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { WindowControls.toggleOnTop() }) {
                val onTop = WindowControls.alwaysOnTop
                Icon(if (onTop) Icons.Filled.PushPin else Icons.Outlined.PushPin, "Keep window on top", tint = if (onTop) Hud.Amber else Hud.TextDim)
            }
            IconButton(onClick = actions.toggleFullscreen) {
                Icon(if (actions.isFullscreen()) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Full screen (F11)", tint = Hud.TextDim)
            }
            IconButton(onClick = actions.switchToServer) {
                Icon(Icons.Filled.Dns, "Server page", tint = Hud.TextDim)
            }
        }
        MissionTabStrip(tab, onTab)
        Box(Modifier.weight(1f).fillMaxWidth()) { MissionTabContent(tab, env, onTab) }
    }
}
