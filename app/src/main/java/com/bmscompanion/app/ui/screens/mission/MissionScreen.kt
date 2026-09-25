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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    val live by MissionLink.live.collectAsState()

    var tab by rememberMissionTab()
    var keepOn by rememberSaveable { mutableStateOf(Repo.getInt("mission_keep_on", 1) == 1) }
    val view = LocalView.current
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    val env = rememberMissionEnv(nav)
    val onTab: (MissionTab) -> Unit = { tab = it; saveMissionTab(it) }

    Column(Modifier.fillMaxSize().background(Hud.Bg)) {
        MissionHeader(state, info, live, keepOn, onKeepOn = { keepOn = !keepOn; Repo.putInt("mission_keep_on", if (keepOn) 1 else 0) }, onSetup = { nav.go(com.bmscompanion.app.ui.Routes.SETUP) })
        MissionTabStrip(tab, onTab)
        Box(Modifier.weight(1f).fillMaxWidth()) { MissionTabContent(tab, env, onTab) }
    }
}

@Composable
private fun MissionHeader(
    state: LinkState,
    info: com.bmscompanion.app.data.mission.BridgeInfo?,
    live: com.bmscompanion.app.data.mission.Live?,
    keepOn: Boolean,
    onKeepOn: () -> Unit,
    onSetup: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MissionStatus(state, info, live, badge = null, idleText = "Connect to BMS Companion on the PC to see live mission data", onSetup = onSetup)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onKeepOn) {
            Icon(if (keepOn) Icons.Filled.LightMode else Icons.Outlined.LightMode, "Keep screen on", tint = if (keepOn) Hud.Amber else Hud.TextDim)
        }
    }
}
