package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.AirportSet
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.isMedium
import com.bmscompanion.app.ui.components.rememberMapState
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra

enum class MissionTab(val label: String, val icon: ImageVector) {
    MAP("Map", Icons.Default.Map),
    FLIGHT("Flight", Icons.Default.Flight),
    BRIEF("Briefing", Icons.Default.Description),
    COMMS("Comms", Icons.Default.Headset),
    BOARDS("Boards", Icons.Default.Assignment),
    SETUP("Setup", Icons.Default.Settings),
}

/** Things every Mission pane needs: bundled theater data resolved from the BMS theater name. */
data class MissionEnv(val nav: NavHostController, val theater: Theater?, val set: AirportSet?)

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

    var tab by rememberSaveable { mutableStateOf(if (MissionLink.host == null) MissionTab.SETUP else MissionTab.MAP) }
    var keepOn by rememberSaveable { mutableStateOf(Repo.getInt("mission_keep_on", 1) == 1) }
    val view = LocalView.current
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    val theaterName = info?.bms?.theater ?: live?.theater
    val theater by produceState<Theater?>(null, theaterName) {
        val all = Repo.index().theaters
        value = resolveTheater(all, theaterName) ?: all.firstOrNull { it.id == Repo.selectedTheater.value }
    }
    val set by produceState<AirportSet?>(null, theater) { value = theater?.let { Repo.airportSet(it.airportSet) } }
    val env = MissionEnv(nav, theater, set)
    val mapState = rememberMapState()
    var mapSel by remember { mutableStateOf<MapSel?>(null) }
    var focusVersion by remember { mutableIntStateOf(0) }
    val showOnMap: (MapSel, Double, Double) -> Unit = { sel, x, y ->
        mapSel = sel
        mapState.flyTo(x, y, maxOf(mapState.scale, 5f))
        focusVersion++
        tab = MissionTab.MAP
    }

    Column(Modifier.fillMaxSize().background(Hud.Bg)) {
        MissionHeader(state, info, live, keepOn, onKeepOn = { keepOn = !keepOn; Repo.putInt("mission_keep_on", if (keepOn) 1 else 0) }, onSetup = { tab = MissionTab.SETUP })
        TabStrip(tab) { tab = it }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                MissionTab.MAP -> MissionMapPane(env, mapState, mapSel, { mapSel = it }, onOpenTab = { tab = it })
                MissionTab.FLIGHT -> MissionFlightPane(env)
                MissionTab.BRIEF -> MissionBriefingPane(env, showOnMap)
                MissionTab.COMMS -> MissionCommsPane(env)
                MissionTab.BOARDS -> MissionBoardsPane(env, onSetup = { tab = MissionTab.SETUP })
                MissionTab.SETUP -> MissionSetupPane(onConnected = { tab = MissionTab.MAP })
            }
        }
    }
}

@Composable
private fun TabStrip(tab: MissionTab, onTab: (MissionTab) -> Unit) {
    val medium = isMedium()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MissionTab.entries.forEach { t ->
            val sel = t == tab
            Row(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Hud.Amber.copy(alpha = 0.16f) else Hud.Surface)
                    .border(1.dp, if (sel) Hud.Amber.copy(alpha = 0.6f) else Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable { onTab(t) }
                    .padding(horizontal = if (medium) 16.dp else 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, null, tint = if (sel) Hud.Amber else Hud.TextDim, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(t.label, color = if (sel) Hud.Amber else Hud.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
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
    val (dot, label) = when (state) {
        LinkState.Idle -> Hud.TextFaint to "NOT CONNECTED"
        LinkState.Connecting -> Hud.Amber to "CONNECTING…"
        is LinkState.Online -> Hud.Green to (if (info?.demo == true) "DEMO" else "LINKED")
        is LinkState.Offline -> Hud.Red to "NO LINK"
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MISSION", style = MaterialTheme.typography.titleLarge, color = Hud.Text)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
                Text(label, style = LocalExtra.current.overline, color = dot, modifier = Modifier.clickable(onClick = onSetup))
                if (state is LinkState.Online && live != null && live.timeSec > 0 && live.flying) {
                    Spacer(Modifier.width(12.dp))
                    Text(zulu(live.timeSec), style = LocalExtra.current.monoSmall, color = Hud.Cyan)
                }
            }
            val sub = when (state) {
                is LinkState.Offline -> state.reason
                is LinkState.Online -> listOfNotNull(
                    info?.bms?.let { b -> if (!b.running) "BMS not running" else if (b.flying) "BMS in 3D" else "BMS in UI" },
                    info?.bms?.theater,
                    info?.tacview?.takeIf { it.connected }?.let { "AWACS feed ${it.objects}" },
                ).joinToString(" · ")
                LinkState.Connecting -> MissionLink.host ?: ""
                LinkState.Idle -> "Set up the PC bridge to see live mission data"
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = if (state is LinkState.Offline) Hud.Red.copy(alpha = 0.85f) else Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onKeepOn) {
            Icon(if (keepOn) Icons.Filled.LightMode else Icons.Outlined.LightMode, "Keep screen on", tint = if (keepOn) Hud.Amber else Hud.TextDim)
        }
    }
}

/** Small reusable pill used on map overlays. */
@Composable
fun OverlayPill(text: String, color: Color = Hud.Text, modifier: Modifier = Modifier) {
    Text(
        text, modifier.clip(RoundedCornerShape(8.dp)).background(Hud.Bg.copy(alpha = 0.82f)).border(1.dp, Hud.Outline.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = color, style = LocalExtra.current.monoSmall, maxLines = 1,
    )
}

@Composable
fun PaneEmpty(title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Hud.Text)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Hud.TextDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (action != null && onAction != null) {
                Text(
                    action, Modifier.clip(RoundedCornerShape(10.dp)).background(Hud.Amber).clickable(onClick = onAction).padding(horizontal = 16.dp, vertical = 9.dp),
                    color = Hud.Bg, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
