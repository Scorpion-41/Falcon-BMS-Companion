package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import com.bmscompanion.app.ui.Kneeboard
import com.bmscompanion.app.ui.KneeboardMenuDivider
import com.bmscompanion.app.ui.KneeboardMenuRow
import com.bmscompanion.app.ui.KneeboardMenuSlot
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

    if (Kneeboard.on) {
        // On the board there is no status line and no tab strip: the tabs move into the ☰ menu and the pane gets
        // everything.
        KneeboardMenuSlot(tab) { dismiss ->
            kneeboardMissionTabs.forEach { t -> KneeboardMenuRow(t.label, t == tab, t.icon) { onTab(t); dismiss() } }
            KneeboardMenuDivider()
        }
        // no fill here: the sheet KneeboardFrame draws under the page is what the board is printed on
        Box(Modifier.fillMaxSize()) { MissionTabContent(tab, env, onTab) }
        return
    }

    Column(Modifier.fillMaxSize().background(Hud.Bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            MissionStatus(
                state, info, live,
                badge = null,
                idleText = "Connecting to BMS Companion on the PC…",
                onSetup = { onTab(MissionTab.SETUP) },
            )
        }
        MissionTabStrip(tab, onTab)
        Box(Modifier.weight(1f).fillMaxWidth()) { MissionTabContent(tab, env, onTab) }
    }
}
