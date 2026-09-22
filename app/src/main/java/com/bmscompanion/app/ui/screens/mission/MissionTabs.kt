package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import com.bmscompanion.app.ui.theme.LocalExtra
import com.bmscompanion.app.data.mission.LinkState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import com.bmscompanion.app.ui.components.MapFocus
import com.bmscompanion.app.ui.components.MapMission
import com.bmscompanion.app.ui.components.mapIdOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.AirportSet
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.isMedium
import com.bmscompanion.app.ui.components.rememberMapState
import com.bmscompanion.app.ui.theme.Hud

// Shared by the Android and PC MissionScreen: the tab list, tab strip and what each tab shows.

/**
 * What Mission is divided into.
 *
 * Fewer tabs than there were, because several of them were drawing the same figures. There is no **Flight** tab: every
 * panel it had — ownship, RWR, DED, the picture — was already a Dashboard card, so the tab was a second copy of a
 * screen the pilot had arranged himself. The cards stay, and the "In flight" layout puts them out the way the tab did.
 * **Kneeboards** is one page for everything that gets printed or pinned in the headset: the BMS kneeboards, the VR
 * boards ([vrBoardsPane], PC only) and the HTML Briefing pages, which were a tab, a tab and a card on Briefing.
 * **AWACS** is a whole page for a job most pilots never do, so it is off until [MissionTabPrefs.showAwacs] says otherwise.
 */
enum class MissionTab(val label: String, val icon: ImageVector) {
    DASH("Dashboard", Icons.Default.Dashboard),
    MAP("Map", Icons.Default.Map),
    AWACS("AWACS", Icons.Default.Radar),
    BRIEF("Briefing", Icons.Default.Description),
    COMMS("Comms", Icons.Default.Headset),
    BOARDS("Kneeboards", Icons.Default.Assignment),
    SETUP("Setup", Icons.Default.Settings),
}

/**
 * The VR board setup, set by the PC entry point. Null everywhere else. It is a section of the Kneeboards page rather
 * than a tab of its own, so what it returns is laid into that page's scroll and must not scroll itself.
 */
var vrBoardsPane: (@Composable () -> Unit)? = null

/** What the pilot has chosen to see. Kept in the prefs file, which an update never rewrites. */
object MissionTabPrefs {
    private const val AWACS_KEY = "mission_tab_awacs"
    private var awacs by mutableStateOf(Repo.getInt(AWACS_KEY, 0))

    /** The AWACS page: off until it is asked for, on every device and every install. */
    var showAwacs: Boolean
        get() = awacs == 1
        set(v) { awacs = if (v) 1 else 0; Repo.putInt(AWACS_KEY, awacs) }
}

/** The tabs this build actually has. */
val missionTabs: List<MissionTab> get() = MissionTab.entries.filter { it != MissionTab.AWACS || MissionTabPrefs.showAwacs }


/**
 * The tabs a VR kneeboard offers. Nobody runs a GCI picture from the cockpit, the kneeboards page is for setting up
 * before the flight, and so are the setup guides.
 */
val kneeboardMissionTabs = listOf(MissionTab.DASH, MissionTab.MAP, MissionTab.BRIEF, MissionTab.COMMS)

/** Things every Mission pane needs: bundled theater data resolved from the BMS theater name. */
data class MissionEnv(val nav: NavHostController, val theater: Theater?, val set: AirportSet?)

@Composable
fun rememberMissionEnv(nav: NavHostController): MissionEnv {
    val info by MissionLink.info.collectAsState()
    val live by MissionLink.live.collectAsState()
    val theaterName = info?.bms?.theater ?: live?.theater
    val theater by produceState<Theater?>(null, theaterName) {
        val all = Repo.index().theaters
        value = resolveTheater(all, theaterName) ?: all.firstOrNull { it.id == Repo.selectedTheater.value }
    }
    val set by produceState<AirportSet?>(null, theater) { value = theater?.let { Repo.airportSet(it.airportSet) } }
    val env = remember(nav, theater, set) { MissionEnv(nav, theater, set) }
    PublishMapMission(env)
    return env
}

/** Tells every map which towns this mission uses (Map menu → Towns → Mission). */
@Composable
private fun PublishMapMission(env: MissionEnv) {
    val live by MissionLink.live.collectAsState()
    val mission by MissionLink.mission.collectAsState()
    val navPoints = live?.navPoints
    val voice = live?.voice
    val focus = remember(mission, navPoints, voice, env.theater, env.set) {
        val b = mission?.briefing
        if (b == null && navPoints.isNullOrEmpty()) return@remember null
        val text = buildString {
            b?.sections?.forEach { s -> s.rows.forEach { r -> r.forEach { append(it).append(' ') }; append('\n') } }
            b?.overview?.targetArea?.let { append(it).append('\n') }
            b?.situation?.let { append(it).append('\n') }
        }
        val stpts = steerpoints(mission, live).filter { it.hasPos }
        val bases = airbases(live, b)
        val points = buildList {
            stpts.forEach { add(it.x!! to it.y!!) }
            mission?.dtc?.weaponTargets?.forEach { add(it.x to it.y) }
            preplannedThreats(mission, live).forEach { add(it.x to it.y) }
            listOf(bases.departure, bases.arrival, bases.alternate).mapNotNull { matchAirport(env.set, it) }.forEach { add(it.x to it.y) }
        }
        val ownFlight = b?.`package`?.firstOrNull { it.primary } ?: b?.`package`?.firstOrNull { f -> b.overview.flight?.let { f.callsign.equals(it, true) } == true }
        val targetText = listOfNotNull(b?.overview?.targetArea, b?.overview?.mission, b?.overview?.packageMission, ownFlight?.target).joinToString("\n")
        val targets = (stpts.filter { it.isTarget }.map { it.x!! to it.y!! } + mission?.dtc?.weaponTargets.orEmpty().map { it.x to it.y }).distinct()
        MapMission(env.theater?.mapId ?: mapIdOf(env.theater?.map), text, points, stpts.filterNot { it.isAlternate }.map { it.x!! to it.y!! }, targetText, targets)
    }
    SideEffect { if (MapFocus.mission != focus && focus != null) MapFocus.mission = focus }
}

/** The open tab, remembered across launches; Setup until a bridge is configured. */
@Composable
fun rememberMissionTab(): MutableState<MissionTab> = rememberSaveable {
    val savedName = Repo.getString("mission_tab")
    val saved = savedName?.let { s -> MissionTab.entries.firstOrNull { it.name == s } }?.takeIf { it in missionTabs }
    val start = if (MissionLink.host == null) MissionTab.SETUP else saved ?: MissionTab.DASH
    // a kneeboard is served by the PC it talks to, and only offers the tabs worth having in the cockpit
    mutableStateOf(if (com.bmscompanion.app.ui.Kneeboard.on && start !in kneeboardMissionTabs) MissionTab.DASH else start)
}

fun saveMissionTab(t: MissionTab) = Repo.putString("mission_tab", t.name)

@Composable
fun MissionTabStrip(tab: MissionTab, onTab: (MissionTab) -> Unit) {
    val medium = isMedium()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        missionTabs.forEach { t ->
            val sel = t == tab
            Row(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Hud.Amber.copy(alpha = 0.16f) else Hud.Surface)
                    .border(1.dp, if (sel) Hud.Amber.copy(alpha = 0.6f) else Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable { onTab(t) }
                    .padding(horizontal = if (medium) 14.dp else 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, null, tint = if (sel) Hud.Amber else Hud.TextDim, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(t.label, color = if (sel) Hud.Amber else Hud.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The content of the selected tab. State that must survive switching tabs (map position and selection,
 * AWACS tools) lives here rather than inside the panes.
 */
@Composable
fun MissionTabContent(tab: MissionTab, env: MissionEnv, onTab: (MissionTab) -> Unit) {
    val mapState = rememberMapState()
    var mapSel by remember { mutableStateOf<MapSel?>(null) }
    var focusVersion by remember { mutableIntStateOf(0) }
    val awacs = remember { AwacsState() }
    val showOnMap: (MapSel, Double, Double) -> Unit = { sel, x, y ->
        mapSel = sel
        mapState.flyTo(x, y, maxOf(mapState.scale, 5f))
        focusVersion++
        onTab(MissionTab.MAP)
    }
    when (tab) {
        MissionTab.DASH -> MissionDashboardPane(env, mapState, mapSel, { mapSel = it }, onOpenTab = onTab)
        MissionTab.MAP -> MissionMapPane(env, mapState, mapSel, { mapSel = it }, onOpenTab = onTab)
        MissionTab.AWACS -> MissionAwacsPane(env, awacs, onOpenTab = onTab)
        MissionTab.BRIEF -> MissionBriefingPane(env, showOnMap)
        MissionTab.COMMS -> MissionCommsPane(env)
        MissionTab.BOARDS -> MissionBoardsPane(env, onSetup = { onTab(MissionTab.SETUP) })
        MissionTab.SETUP -> MissionSetupPane(onConnected = { onTab(MissionTab.DASH) })
    }
}

/** "MISSION ● LINKED 04:12:30Z" and the status line below it. Shared with the PC header. */
@Composable
fun MissionStatus(
    state: LinkState,
    info: com.bmscompanion.app.data.mission.BridgeInfo?,
    live: com.bmscompanion.app.data.mission.Live?,
    badge: String?,
    idleText: String,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (dot, label) = when (state) {
        LinkState.Idle -> Hud.TextFaint to "NOT CONNECTED"
        LinkState.Connecting -> Hud.Amber to "CONNECTING…"
        is LinkState.Online -> Hud.Green to (if (info?.demo == true) "DEMO" else "LINKED")
        is LinkState.Offline -> Hud.Red to "NO LINK"
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MISSION", style = MaterialTheme.typography.titleLarge, color = Hud.Text)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(label, style = LocalExtra.current.overline, color = dot, modifier = Modifier.clickable(onClick = onSetup))
            if (badge != null) {
                Spacer(Modifier.width(8.dp))
                Text(badge, style = LocalExtra.current.overline, color = Hud.TextFaint)
            }
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
            LinkState.Idle -> idleText
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, color = if (state is LinkState.Offline) Hud.Red.copy(alpha = 0.85f) else Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Small reusable pill used on map overlays. */
/**
 * In 3D without the AWACS feed: BMS only streams its Tacview telemetry while ACMI recording runs, which is toggled
 * with F in the cockpit (default key). Hidden in demo mode, when the stream is off in the settings, and once dismissed.
 */
@Composable
fun AcmiReminder(modifier: Modifier = Modifier) {
    val info by MissionLink.info.collectAsState()
    val live by MissionLink.live.collectAsState()
    val contacts by MissionLink.contacts.collectAsState()
    val dismissed = rememberSaveable { mutableStateOf(false) }
    val i = info ?: return
    // not connected, or connected but nothing streamed (in 3D with recording on there is at least your own jet)
    val streaming = (contacts?.connected == true || i.tacview.connected) && (contacts?.contacts?.isNotEmpty() == true || i.tacview.objects > 0)
    val missing = !i.demo && i.tacview.enabled && live?.flying == true && !streaming
    if (!missing || dismissed.value) return
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(Hud.Bg.copy(alpha = 0.9f)).border(1.dp, Hud.Amber.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(start = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("F", Modifier.border(1.5.dp, Hud.Amber, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 1.dp), color = Hud.Amber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text("No AWACS picture yet", color = Hud.Text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text("Press F in the cockpit to start ACMI recording; BMS streams the air picture only while it records.", color = Hud.TextDim, fontSize = 11.sp)
        }
        Text("✕", Modifier.clickable { dismissed.value = true }.padding(horizontal = 10.dp, vertical = 4.dp), color = Hud.TextDim, fontSize = 13.sp)
    }
}

@Composable
fun OverlayPill(text: String, color: Color = Hud.Text, modifier: Modifier = Modifier) {
    Text(
        text, modifier.clip(RoundedCornerShape(8.dp)).background(Hud.Bg.copy(alpha = 0.82f)).border(1.dp, Hud.Outline.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = color, style = LocalExtra.current.monoSmall, maxLines = 1,
    )
}

/**
 * Which Mission pages this pilot wants. One card on Setup, on the phone and on the PC alike.
 *
 * The AWACS page is the only one that hides: it is a GCI console, and the pilots who want it know they do. Everything
 * on it — the picture, the bullseye calls — a Dashboard card shows as well.
 */
@Composable
fun TabsCard() {
    com.bmscompanion.app.ui.components.SectionCard("Mission pages", accent = Hud.Cyan) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show the AWACS page", color = Hud.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "A GCI station: the whole picture, ranges and bearings between contacts, and the calls to read out. " +
                        "The Dashboard's Picture card covers the everyday need.",
                    color = Hud.TextDim, fontSize = 12.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.Switch(
                checked = MissionTabPrefs.showAwacs,
                onCheckedChange = { MissionTabPrefs.showAwacs = it },
            )
        }
    }
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
