package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.wdp.WdpForm
import com.bmscompanion.app.data.wdp.WdpValues
import com.bmscompanion.app.ui.Routes
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.screens.mission.MissionEnv
import com.bmscompanion.app.ui.screens.mission.norm
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay

/**
 * Weapon Delivery Planner for Falcon BMS 4.38.1, inside the app.
 *
 * **Falcas's program, made for 4.38.1** — `docs/WDP-PORT.md` says what has been taken across, what it is checked
 * against, which of WDP's bugs are fixed (each with its evidence) and where the app's own data replaces WDP's tables.
 *
 * The pages look like his, read out of his own layout (`WdpLayout`). What they compute is WDP's arithmetic only
 * where the app has nothing of its own — the attack geometry, the ballistics, the engine tables — and is checked
 * against the real program's answers; the airports, runways, radios, charts, HARM codes, theaters, aircraft and
 * weapons are the app's own Falcon BMS 4.38.1 data. WDP's buttons for what the app does not do (paper printing, a
 * second cartridge it cannot find) are taken off the page rather than answered with a message, and the ones every
 * page shares (Save DTC, Get DTC File, Different Flight) are the Planner's own toolbar: see [hidden].
 */
enum class WdpPage(
    val form: String,
    val label: String,
    /** The page's name where the toolbar is narrow (a tablet held upright, the phone's page list). */
    val short: String,
    /**
     * Controls the page hides that the designer shows, for three reasons.
     *
     * **Stacked views.** WDP keeps several of its tabs inside one control and flips panels at runtime: `cntDataCard`
     * holds the Briefing view (`pnlBrief` + `pnlWx`), the DataCard (`pnlPage_1`) and the Coordination Card
     * (`pnlPage_2`), all at the same place. Each page here shows one of them. The small `pnlBlocked_*` overlays on
     * TOSS are its "this reference is unavailable" flags, off until the wiring says so.
     *
     * **Removed.** WDP's controls for what the app does not do, listed once per page so that the Planner, the renders
     * and the click test agree on them (the click test reports each as "hidden on the page" and fails if one shows):
     * the card's Print, Print Preview, save timer and Mission.ini lamp, and its Upd Kneeboard, which did what the
     * toolbar's does (one place per action) ([DataCardWiring.REMOVED]); the DTC page's
     * Tactical Engagement buttons and lamps, Save List as jpg and Save PPT.ini ([DtcWiring.REMOVED]); the attack pages' Campaign and TE buttons, since the Planner has one
     * steerpoint table, filled slot by slot from the cartridge, BMS's route and the save ([WdpMission.slots];
     * [AttackSelection.REMOVED], D87). Save Map is also taken off in the
     * browser, which cannot save a picture it drew ([hiddenHere]).
     *
     * **The shell does it too**: the toolbar's **Save to DTC** (with Re-read DTC from BMS in its menu) and the identity
     * strip do for every page what WDP's own DTC buttons do on theirs, and those stay on their pages as well — the
     * DTC page's Save DTC on the five tabs that are not From mission… ([DtcWiring.SAVE_DTC]) and MAIN's Open/Save
     * Callsign.ini File, the card's Get DTC File, Save DTC, its "Callsign.ini saved" lamp ([DataCardWiring.DTC_CONTROLS])
     * and Different Flight.
     */
    val hidden: List<String> = emptyList(),
    val wired: Boolean = false,
) {
    BRIEFING("cntDataCard", "Briefing", "Brief", hidden = listOf("pnlPage_1", "pnlPage_2") + DataCardWiring.REMOVED, wired = true),
    DATACARD("cntDataCard", "DataCard", "Card", hidden = listOf("pnlPage_2", "pnlBrief", "pnlWx") + DataCardWiring.REMOVED, wired = true),
    COORDINATION("cntDataCard", "Coordination Card", "Coord", hidden = listOf("pnlPage_1", "pnlBrief", "pnlWx") + DataCardWiring.REMOVED, wired = true),
    /**
     * The app's own page, not one of WDP's: the DTC page's cartridge as the HSD will show it ([WdpMapPage]). Before the
     * DTC, where WDP's toolbar has its MAP button (`fclsMain` ToolStrip1: Mission Brief, DataCard, Coordination Card,
     * MAP, DTC, Performance, PopUp, HADB, TOSS).
     */
    MAP("", "Map", "Map", wired = true),
    DTC("cntDTC", "DTC", "DTC", hidden = DtcWiring.REMOVED, wired = true),
    PERFORMANCE("cntPerformance", "Performance", "Perf", wired = true),
    POPUP("cntPopUp", "Pop-up", "Pop-up", hidden = AttackSelection.REMOVED, wired = true),
    HADB("cntHADB", "HADB", "HADB", hidden = AttackSelection.REMOVED, wired = true),
    TOSS("cntTOSS", "TOSS", "TOSS", hidden = listOf("pnlBlocked_1", "pnlBlocked_2", "pnlBlocked_3") + AttackSelection.REMOVED, wired = true),
    /**
     * WDP's ATO Target List (`fclsAtoTargetList`, its Options → ATO Target List) as a page of its own ([AtoTargetsPage]),
     * last: WDP's toolbar order above is kept, and this came from its menu. A tab rather than a menu item, so it is one
     * press away like the other pages; the DataCard's flight selection turns to it ([AtoTargetList.open]).
     */
    ATO("", "ATO Targets", "ATO", wired = true);

    /**
     * A page the app draws itself, with no WDP layout behind it: the Map page and the ATO Targets page. The Planner loads
     * no form for it and gives it no wiring; the checks that walk every page's layout (the click test, the renders, the
     * outcome checks) leave it out.
     */
    val native: Boolean get() = form.isEmpty()

    /** [hidden]: Save Map now saves on the BMS PC from every device, the browser included. */
    fun hiddenHere(): List<String> = hidden

    /** The page's name in a toolbar of middling width: the two long ones shortened, the rest as they are. */
    val medium: String get() = when (this) { COORDINATION -> "Coord. Card"; PERFORMANCE -> "Perform."; else -> label }
}

/**
 * The theater BMS reports, from the app's list: by its exact name (letters and digits only, as BMS pads some with
 * spaces) or its id, and nothing else. The Mission section's `resolveTheater` also takes a name that merely starts
 * with another, which is fine for picking a map; here the theater decides every latitude and longitude on the pages,
 * and a coordinate from the wrong theater's projection is worse than none. A theater installed after this copy of
 * the app was built is therefore not found, and the Planner says so ([newerTheaterLine]).
 */
internal fun plannerTheater(theaters: List<Theater>, bmsName: String?): Theater? {
    if (bmsName.isNullOrBlank()) return null
    val n = bmsName.norm()
    return theaters.firstOrNull { it.name.norm() == n } ?: theaters.firstOrNull { it.id.norm() == n }
}

/**
 * The line the Planner shows when BMS runs a theater the app's data does not have, or has only in part. The attack
 * geometry, the ballistics and the engine tables need none of it, so the pages still plan; what goes is the
 * coordinates, the airports and the charts. Null when there is nothing to say.
 */
internal fun newerTheaterLine(bmsName: String?, theater: Theater?, known: Boolean): String? = when {
    bmsName.isNullOrBlank() || !known -> null
    theater == null -> "This theater (${bmsName.trim()}) is newer than this copy of BMS Companion: coordinates, airports and " +
        "charts are unavailable until the data is updated. The attack geometry, ballistics and performance still work."
    theater.planner?.ok == false -> "The app's data for ${theater.name} lacks " + theater.planner.missing.joinToString(", ") +
        ": those parts of the pages stay blank until the data is updated."
    else -> null
}

/**
 * The Planner filling the whole window: the mission header and tab strip step aside, and the page, always fitted
 * whole, grows into the room they leave. WDP's own page tabs and the Planner's toolbar stay, because switching pages
 * and saving are what a pilot still needs in that mode. Not remembered between launches, for the same reason the
 * Dashboard's is not; a phone held sideways turns it on by itself each time the Planner opens (its height is the page).
 */
object WdpFocus {
    /** what the pilot (or a phone held sideways) chose */
    internal var chosen by mutableStateOf(false)

    /**
     * Full window: [chosen], or **lifted** — while one of the Planner's own windows is up on a device worked by finger
     * (Open mission…, Pick a flight, Upd Kneeboard…), the Mission header and tab strip step aside for it too, so its list
     * gets the screen's height rather than what a tablet held sideways leaves under them. Setting it sets [chosen].
     */
    var on: Boolean
        get() = chosen || lifted
        set(v) { chosen = v }

    /** a Planner window is up by finger: the Mission chrome steps aside while it is (see [on]) */
    val lifted: Boolean get() = PlannerWindows.open != null && WdpTouch.device

    /** [on] was set by turning a phone sideways, and turning it upright again takes it off (not by the pilot's button) */
    internal var auto = false
}

/**
 * The Planner's pages, for as long as the app runs.
 *
 * The Planner is one tab of the Mission section, and a tab that is not showing is not composed: pages made with
 * `remember` inside it were thrown away whenever the pilot looked at the map and came back — the DTC edits not yet
 * saved, what was typed on the card, the TOSS sliders, each attack page's TGT STPT. WDP is one window that stays
 * open while a pilot works, so its pages live here instead: made the first time the Planner is shown, then kept —
 * also while the link to the PC is down (the note says so), so the work comes back with the link (R3-PLAN A18).
 */
object WdpSession {
    private val tossMade = lazy { TossWiring() }
    private val popupMade = lazy { PopupWiring() }
    private val dataCardMade = lazy { DataCardWiring() }
    private val hadbMade = lazy { HadbWiring() }
    val toss by tossMade
    val popup by popupMade
    val dataCard by dataCardMade
    val hadb by hadbMade

    /**
     * A new mission or a switch of mode ([com.bmscompanion.app.ui.screens.mission.MissionEpoch]): the attack and the
     * delivery start afresh. Pop-up, HADB and TOSS go back to WDP's defaults (their saved Setup.ini sections removed),
     * with no typed ELEV or target ground, and TGT STPT on the mission's first strike steerpoint (1 when it has none);
     * the DataCard's delivery block back to "None" with no profile chosen; the attack page last worked on forgotten. A
     * page not made yet only loses its saved section, and starts on the defaults when it is made.
     */
    fun resetAttack() {
        // no attack page's attack is current any more (AttackFocus): the maps draw the cartridge's until one is changed
        AttackFocus.clear()
        for (k in listOf(PopupWiring.INI_KEY, HadbWiring.INI_KEY, TossWiring.INI_KEY)) runCatching { com.bmscompanion.app.data.Repo.putString(k, null) }
        if (popupMade.isInitialized()) runCatching { popup.resetForMission() }
        if (hadbMade.isInitialized()) runCatching { hadb.resetForMission() }
        if (tossMade.isInitialized()) runCatching { toss.resetForMission() }
        if (dataCardMade.isInitialized()) runCatching { dataCard.resetAttack() }
        // the cartridge's delivery data ([NAV OFFSETS]) leaves the DTC page too, as the PC clears it from the file
        if (dtcMade.isInitialized()) runCatching { dtc.clearDeliveryForMission() }
    }
    // made with the Planner, not with its tab: the attack pages' Save to DTC and the toolbar's reach the cartridge
    // through it (WdpCartridge) before the DTC tab has ever been opened
    private val dtcMade = lazy { DtcWiring(dtcSource) }
    val dtc by dtcMade
    /** where [dtc] reads and saves the cartridge: the PC; the headless renders set a copy on disk before it is made */
    internal var dtcSource: DtcSource = LinkDtcSource
    // what it fetches on a pick (another field's runway widths) outlives the tab, as the page itself does
    val performance by lazy { PerformanceWiring(CoroutineScope(SupervisorJob() + Dispatchers.Main)) }

    /** The page on show. */
    var page by mutableStateOf(WdpPage.DATACARD.name)
    // (1.3.8: which attack Populate and the maps take is AttackFocus's — the attack page last changed or applied — and
    // never the page last looked at)
    /**
     * The Planner has been on screen in this run, so its pages — and any edits in them not saved yet — exist. Populate
     * from Planner, pressed elsewhere (the Kneeboards page, the Mission section's empty state), asks about unsaved
     * edits only then, and never makes the pages just to find out.
     */
    internal var started = false

    // What the pages were last given. Coming back to the tab hands them the same mission again, which is not handed
    // on at all: preparing costs a read of the airports and the cartridge. (A cartridge saved on the same briefing is
    // handed on, and the card keeps what the pilot typed on it: only a new briefing is a new card.)
    internal var appliedMission: WdpMission? = null
    /** What the attack pages were last given: [appliedMission] with the steerpoints placed this session ([WdpMission.placed]). */
    internal var appliedAttackMission: WdpMission? = null
    internal var appliedCoords: com.bmscompanion.app.data.wdp.PopupCoords.CoordData? = null
    // The theater as last resolved: without it the tab comes back with no theater for a moment, and that passing
    // "unknown theater" is a different mission to the pages.
    internal var theaterName: String? = null
    internal var theater: Theater? = null
    /** The link has been up since the app started: a link that drops is "lost", one never made "cannot reach". */
    internal var everLinked = false
}

// ==================================================================================================== the link

/**
 * Why the Planner cannot work now (R3-PLAN A18): every Planner function reads or writes Falcon BMS on the PC, so while
 * the link is not up — or the PC has no Falcon BMS — the Planner area shows a note ([PlannerNotLinked]) with Setup and
 * the Guide instead of pages that would do nothing. The work in the pages ([WdpSession]) is kept.
 */
enum class PlannerGate {
    /** no PC set up on this device */
    IDLE,
    /** looking for the PC */
    CONNECTING,
    /** the PC stopped answering (or never answered) */
    LOST,
    /** linked, but the PC has no Falcon BMS folder */
    NO_BMS,
}

/**
 * Which note the Planner shows, or null for the Planner itself. [local]: this device is the PC running Falcon BMS
 * (the PC window in "This PC" mode, whose data comes from inside the program) — it never shows the note.
 */
internal fun plannerGate(state: LinkState, info: BridgeInfo?, local: Boolean): PlannerGate? = when {
    local -> null
    state is LinkState.Online -> if (info != null && !info.bms.installed) PlannerGate.NO_BMS else null
    state is LinkState.Connecting -> PlannerGate.CONNECTING
    state is LinkState.Offline -> PlannerGate.LOST
    else -> PlannerGate.IDLE
}

/** The PC window's "This PC" mode reads Falcon BMS in-process, at this address; a phone or a browser elsewhere never is. */
internal fun plannerIsLocal(host: String?): Boolean = host == "127.0.0.1"

/** The note's title and text for [gate] (R3-UI §3.5). [host] names the PC; [everLinked]: the link was up before. */
internal fun plannerNote(gate: PlannerGate, host: String?, reason: String?, everLinked: Boolean): Pair<String, String> {
    val pc = host?.takeIf { it.isNotBlank() } ?: "the PC"
    val intro = "It opens your campaign files and your DTC on the PC where Falcon BMS runs, and saves the DTC and the " +
        "kneeboards there. Connect this device in Setup."
    return when (gate) {
        PlannerGate.IDLE -> "The Planner works with BMS Companion on the PC" to intro
        PlannerGate.CONNECTING -> "Connecting to $pc…" to intro
        PlannerGate.LOST -> if (everLinked) "Lost the link to $pc" to ("Your work in the Planner is kept and comes back with the link." +
            (reason?.takeIf { it.isNotBlank() }?.let { "\n($it)" } ?: ""))
        else "Cannot reach BMS Companion at $pc" to ("Is BMS Companion running on that PC, on the same network? " +
            (reason?.takeIf { it.isNotBlank() }?.let { "($it) " } ?: "") + "Check the address in Setup.")
        PlannerGate.NO_BMS -> "BMS Companion on the PC cannot find Falcon BMS" to
            "Set the Falcon BMS folder on the PC (BMS Companion there → Settings). The Planner opens and saves Falcon BMS's own files."
    }
}

/** The note in place of the Planner (R3-PLAN A18): what is wrong, and Setup and the Guide, which can be read offline. */
@Composable
fun PlannerNotLinked(gate: PlannerGate, host: String?, reason: String?, onSetup: (() -> Unit)?, modifier: Modifier = Modifier) {
    val (title, text) = plannerNote(gate, host, reason, WdpSession.everLinked)
    Box(modifier.fillMaxSize().background(Hud.Bg).padding(20.dp).plannerProbe("Shell/NotLinked/${gate.name}"), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(12.dp)).background(Hud.Surface).border(1.dp, Hud.Outline, RoundedCornerShape(12.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = Hud.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(text, color = Hud.TextDim, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onSetup != null) NoteButton("Setup", primary = true, probe = "Shell/NotLinked/Setup", onClick = onSetup)
                NoteButton("Guide", primary = onSetup == null, probe = "Shell/NotLinked/Guide") { PlannerGuide.open("remote") }
            }
        }
    }
}

@Composable
private fun NoteButton(text: String, primary: Boolean, probe: String, onClick: () -> Unit) {
    val finger = WdpTouch.device
    Box(
        Modifier.heightIn(min = if (finger) 48.dp else 0.dp).clip(RoundedCornerShape(10.dp)).background(if (primary) Hud.Amber else Hud.Surface3)
            .clickable(onClick = onClick).padding(horizontal = if (finger) 22.dp else 18.dp, vertical = 9.dp).plannerProbe(probe),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (primary) Hud.Bg else Hud.Text, fontWeight = FontWeight.Bold, fontSize = if (finger) 15.sp else 14.sp, maxLines = 1) }
}

// ==================================================================================================== the pane

@Composable
fun WdpPane(env: MissionEnv? = null, modifier: Modifier = Modifier, onOpenTab: ((com.bmscompanion.app.ui.screens.mission.MissionTab) -> Unit)? = null) {
    // Populate from Planner's "Show the Map": the Mission tabs are reachable from here only
    androidx.compose.runtime.DisposableEffect(onOpenTab) {
        PlannerPopulate.onShowMission = onOpenTab?.let { open ->
            {
                WdpFocus.on = false
                open(com.bmscompanion.app.ui.screens.mission.MissionTab.MAP)
            }
        }
        onDispose { PlannerPopulate.onShowMission = null }
    }
    // What BMS itself has — the briefing it printed, the cartridge it loaded and its own route — reaches every wired
    // page, or the flight of a save picked with Open mission… (PlannerMissionState). Always BMS's own files
    // (MissionLink.bmsFiles), never the Mission section's WDP snapshot, which is what this Planner populated: planning
    // from it would plan the last Populate rather than the files. A new mission sets each attack page's TGT STPT to
    // its first target once.
    val missionData by MissionLink.bmsFiles.collectAsState()
    // The theater BMS is on, as the Mission header names it: the pages print the latitude and longitude BMS itself
    // gives on that theater (WdpCoords.coordData, D26). Only the theater BMS reports is used, matched exactly (plannerTheater): a theater
    // picked elsewhere in the app might not be the mission's, and a coordinate from the wrong projection is worse
    // than none.
    val info by MissionLink.info.collectAsState()
    val live by MissionLink.live.collectAsState()
    val link by MissionLink.state.collectAsState()
    if (link is LinkState.Online) WdpSession.everLinked = true
    // Only the PC window reads Falcon BMS in-process. The browser version opened on the PC itself (http://127.0.0.1:47474,
    // the address OpenKneeboard and a pilot at the PC use) has the same host but is a client like any phone: its link
    // drops when the PC program stops, and then it needs the note. The browser has no installer (as WdpPage.hiddenHere
    // tells it); the PC window and the Android app have one.
    val gate = plannerGate(link, info, plannerIsLocal(MissionLink.host) && Platform.installer != null)
    // a save's flight is planned in the save's own theater, which may not be the one BMS is set to
    val saveTheater = PlannerMissionState.theater?.takeIf { PlannerMissionState.fromSave && PlannerMissionState.flight != null }
    val onSetup: (() -> Unit)? = env?.let { e -> { e.nav.go(Routes.SETUP) } }
    // Settings' "Change in Setup…" (the BMS folder is Setup's to change on the PC)
    androidx.compose.runtime.DisposableEffect(env) {
        PlannerSettings.onSetup = onSetup
        onDispose { if (PlannerSettings.onSetup === onSetup) PlannerSettings.onSetup = null }
    }
    // the note has no toolbar to leave full window with, and the Mission tabs are how a pilot goes elsewhere
    LaunchedEffect(gate) { if (gate != null) WdpFocus.on = false }
    Box(modifier.fillMaxSize()) {
        if (gate == null) {
            WdpPlanner(missionData, saveTheater ?: info?.bms?.theater ?: live?.theater, Modifier.fillMaxSize())
        } else {
            val reason = (link as? LinkState.Offline)?.reason
            PlannerNotLinked(gate, MissionLink.host, reason, onSetup)
            // the guide can be read without the PC (A18/A19), and opens from the note
            PlannerWindowHost()
        }
    }
    // the guide opens by itself the first time the Planner is shown on this device (A19)
    PlannerGuideFirstOpen()
}

/**
 * The mission the Planner's pages are given (R3-PLAN A1): the printed briefing and the cartridge with BMS's own route,
 * as by default; or, once **Open mission…** has picked a flight of a save ([PlannerMissionState]), that flight — the
 * briefing the PC made of it, its waypoints and the seat — with the pilot's answer to the Precision STPT question
 * ([WdpMission.precision]: Yes, the cartridge first for every slot it places; No, the save's waypoints). BMS's route is
 * only ever the briefed flight's, so it joins a save's flight only when that is the briefed one, and the printed
 * briefing stands in for the save's only then.
 */
internal fun plannerMission(data: MissionData?, theater: Theater?, flight: CampFlight? = PlannerMissionState.flight.takeIf { PlannerMissionState.fromSave }): WdpMission {
    if (flight == null) return WdpMission.of(data).copy(theater = theater)
    val briefed = flight.row.briefed
    // a save of another theater than the one BMS is set to is planned without the cartridge's positions: they are
    // in BMS's theater's feet (PlannerMissionState.missionData; the picker's source line says the same)
    val d = PlannerMissionState.missionData(data)
    return WdpMission(
        briefing = flight.briefing ?: data?.briefing?.takeIf { briefed },
        dtc = d?.dtc,
        theater = theater,
        // the save's own flight plan of the printed flight (fromSave) adds nothing to the flight itself
        route = data?.route?.takeIf { briefed && !it.fromSave },
        flight = flight,
        ref = PlannerMissionState.ref,
        seat = PlannerMissionState.seat.coerceIn(0, 3),
        precision = PlannerMissionState.precision,
    )
}

// ==================================================================================================== the shell

/**
 * How the toolbar is laid out, by the room the Planner has (R3-UI §3.10). With a mouse, FULL, ICONS and TABLET only
 * place the Steps and row B: row A is whatever [shellFit] measures to fit, every tab and action in view. By finger
 * they are the toolbar itself, falling back a step where a finger's row would not fit (`fingerFits`).
 */
internal enum class ShellLayout {
    /** 1300 dp and wider: page tabs and named actions on one row (by finger) */
    FULL,
    /** 840-1299 dp: the actions become icons, each named under it by finger */
    ICONS,
    /** 600-839 dp: by finger the tabs on row A and Open, Save, Populate, Steps and a menu on row B */
    TABLET,
    /** a phone upright (under 600 dp) or anything under 480 dp tall (a phone sideways): one row, the page in a list */
    COMPACT;

    companion object {
        fun of(width: Dp, height: Dp): ShellLayout = when {
            width < 600.dp || height < 480.dp -> COMPACT
            width < 840.dp -> TABLET
            width < 1300.dp -> ICONS
            else -> FULL
        }
    }
}

/** How a page tab names its page, by the room there is: "Coordination Card", "Coord. Card", "Coord". */
internal enum class TabNames { FULL, MEDIUM, SHORT }

internal fun WdpPage.named(n: TabNames): String = when (n) { TabNames.FULL -> label; TabNames.MEDIUM -> medium; TabNames.SHORT -> short }

/**
 * A tab of the toolbar's page strip: one page, or **Attack** over WDP's three attack pages, which a slim vertical rail
 * beside the page picks between ([AttackRailView], placed by [AttackRail]). Each attack page keeps its own [WdpPage] — the Guide's Go
 * there, the card's delivery profile, Upd Kneeboard's attack page, the hand-offs and each page's TGT STPT all still
 * name it — so turning the Planner to TOSS from anywhere shows Attack with TOSS chosen. The probe is
 * `Shell/Tab/<key>`: a page's name, or `ATTACK`.
 */
internal class ShellTab private constructor(val key: String, val label: String, val medium: String, val short: String, val pages: List<WdpPage>) {
    fun named(n: TabNames): String = when (n) { TabNames.FULL -> label; TabNames.MEDIUM -> medium; TabNames.SHORT -> short }

    /** The page a press on the tab shows: its own, or the attack page last used on this device. */
    fun target(): WdpPage = if (pages.size == 1) pages[0] else AttackTabs.last()

    companion object {
        val ATTACK = ShellTab("ATTACK", "Attack", "Attack", "Attack", AttackTabs.PAGES)

        /** WDP's toolbar order, the three attack pages one tab where Pop-up stood. */
        val ALL: List<ShellTab> = WdpPage.entries.mapNotNull { p ->
            when (p) {
                AttackTabs.PAGES[0] -> ATTACK
                in AttackTabs.PAGES -> null
                else -> ShellTab(p.name, p.label, p.medium, p.short, listOf(p))
            }
        }

        fun of(page: WdpPage): ShellTab = ALL.first { page in it.pages }
    }
}

/** The Attack tab's pages, and the one last used on this device (kept in its settings, so it opens there next time). */
internal object AttackTabs {
    val PAGES = listOf(WdpPage.POPUP, WdpPage.HADB, WdpPage.TOSS)
    const val KEY = "planner_attack_page"

    fun last(): WdpPage = runCatching { Repo.getString(KEY) }.getOrNull()?.let { n -> PAGES.firstOrNull { it.name == n } } ?: PAGES[0]

    fun used(page: WdpPage) {
        if (page in PAGES && runCatching { Repo.getString(KEY) }.getOrNull() != page.name) runCatching { Repo.putString(KEY, page.name) }
    }
}

/**
 * How a mouse's toolbar names its actions, by the room there is: [ALL] every one; [NAMED] the four that act on the
 * mission (Open mission…, Save to DTC, Populate from Planner, Upd Kneeboard), the rest as icons; [SHORT] those four in
 * a word; [SAVE] Save to DTC alone; [ICONS] none. An icon is still named by its tooltip (or the hint under the toolbar).
 */
internal enum class ActionNames {
    ALL, NAMED, SHORT, SAVE, ICONS;

    /** The words a toolbar action shows (by its probe key), or null for its icon alone. */
    fun text(key: String): String? = when (key) {
        "OpenMission" -> when (this) { ALL, NAMED -> "Open mission…"; SHORT -> "Open…"; else -> null }
        "SaveToDtc" -> if (this == ICONS) null else "Save to DTC"
        "Populate" -> when (this) { ALL, NAMED -> MissionMode.POPULATE; SHORT -> "Populate"; else -> null }
        "Print" -> when (this) { ALL, NAMED -> "Upd Kneeboard"; SHORT -> "Upd KB"; else -> null }
        "Steps", "Guide", "Options" -> if (this == ALL) key else null
        else -> null
    }

    companion object {
        /** the actions of a mouse's toolbar, in their order (Fit the page and Full window are always icons) */
        val KEYS = listOf("OpenMission", "SaveToDtc", "Populate", "Print", "Steps", "Guide", "Options")
    }
}

/**
 * The toolbar a mouse gets ([shellFit]): the tabs' names and padding, the actions' names, whether the actions sit on
 * row B instead ([below]: a window too narrow for both on one row), and whether the "WDP" mark has room.
 */
internal data class ShellFit(val tabs: TabNames, val tabPad: Dp, val actions: ActionNames, val below: Boolean, val mark: Boolean)

/** The widths of the toolbar's words in the type they are drawn in ([rememberShellWords]), for [shellFit]. */
internal class ShellWords(private val w: Map<String, Dp>) { fun of(key: String): Dp = w[key] ?: 0.dp }

/** A mouse toolbar's measures (ShellButton and SaveButton draw with these, and [shellFit] adds them up). */
internal object MouseBar {
    val HEIGHT = 28.dp
    val ICON = 16.dp
    /** padding outside a button, each side */
    val OUTER = 2.dp
    val PAD_NAMED = 8.dp
    val PAD_ICON = 6.dp
    val GAP = 5.dp
    val FONT = 11.5.sp
    val TAB_GAP = 4.dp
    /** the rule between the four that act on the mission and the rest */
    val DIVIDER = 9.dp
    val ICON_BUTTON = OUTER * 2 + PAD_ICON * 2 + ICON
    /** Save to DTC's menu arrow */
    val ARROW = 2.dp * 2 + ICON
}

/**
 * The type of the toolbar's words: the theme's body style with its 24-sp line and 0.5-sp letter spacing taken out
 * (that line box, five times the type's own on an 11.5-sp label, and the spacing left after the last letter pushed
 * the words off the middle of their buttons), the line a touch taller than the type and the type centred in it.
 */
internal fun shellTextStyle(base: TextStyle, size: TextUnit, weight: FontWeight = FontWeight.Normal): TextStyle = base.merge(
    TextStyle(
        fontSize = size, fontWeight = weight, lineHeight = size * 1.25f, letterSpacing = 0.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    ),
)

/**
 * Moves one line of words of [size] so that the middle of its capitals is the middle of its box, and so of the button
 * that centres the box. Centring the line box puts the font's ascent-to-descent span on the middle instead, which sits
 * the capitals a pixel or two low beside the icon, more at a desktop's 125 % (Segoe UI's tall ascent) than on Android;
 * a capital is some 0.7 em in every font the app draws with, so the measure holds on all three.
 */
internal fun Modifier.capsCentred(size: TextUnit): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val base = p[FirstBaseline]
    if (base == AlignmentLine.Unspecified) return@layout layout(p.width, p.height) { p.place(0, 0) }
    val capMiddle = base - size.toPx() * 0.7f / 2f
    val dy = (p.height / 2f - capMiddle).roundToInt()
    layout(p.width, p.height) { p.place(0, dy) }
}

/** [shellTextStyle] over the current text style. */
@Composable
internal fun shellType(size: TextUnit, weight: FontWeight = FontWeight.Normal): TextStyle = shellTextStyle(LocalTextStyle.current, size, weight)

/** Measures every word a toolbar may show, once per type and density. */
@Composable
internal fun rememberShellWords(): ShellWords {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    val density = LocalDensity.current
    return remember(measurer, style, density) {
        val out = HashMap<String, Dp>()
        fun put(key: String, text: String, size: TextUnit, weight: FontWeight) {
            val px = measurer.measure(text, shellTextStyle(style, size, weight), maxLines = 1, softWrap = false).size.width
            out[key] = with(density) { px.toDp() }
        }
        for (t in ShellTab.ALL) for (n in TabNames.entries) {
            put("tab/${n.name}/${t.key}", t.named(n), 12.sp, FontWeight.SemiBold)
            put("ftab/${n.name}/${t.key}", t.named(n), 13.sp, FontWeight.SemiBold)
        }
        for (a in ActionNames.entries) for (k in ActionNames.KEYS) a.text(k)?.let {
            put("act/$it", it, MouseBar.FONT, FontWeight.Normal)
            put("fact/$it", it, 13.sp, FontWeight.Normal)
        }
        put("mark", "WDP", 10.sp, FontWeight.Bold)
        for (c in listOf("0", "00", "99+")) put("count/$c", c, 10.sp, FontWeight.Bold)
        ShellWords(out)
    }
}

/** The width of the page tabs named [n] with [pad] each side, on a mouse ([finger] false) or by finger. */
internal fun tabsWidth(words: ShellWords, n: TabNames, pad: Dp, finger: Boolean = false): Dp =
    ShellTab.ALL.fold(0.dp) { s, t -> s + words.of("${if (finger) "ftab" else "tab"}/${n.name}/${t.key}") + pad * 2 } +
        MouseBar.TAB_GAP * (ShellTab.ALL.size - 1)

private fun countWidth(words: ShellWords, count: Int): Dp =
    if (count <= 0) 0.dp else 5.dp + words.of("count/" + if (count > 99) "99+" else if (count > 9) "00" else "0") + 10.dp

/** The width of a mouse toolbar's actions named [a] (Save to DTC's count, Fit the page when [zoomed]). */
internal fun actionsWidth(words: ShellWords, a: ActionNames, count: Int, zoomed: Boolean): Dp {
    val m = MouseBar
    fun button(key: String) = a.text(key)?.let { m.OUTER * 2 + m.PAD_NAMED * 2 + m.ICON + m.GAP + words.of("act/$it") } ?: m.ICON_BUTTON
    val saveText = a.text("SaveToDtc")
    val save = m.OUTER * 2 + (if (saveText != null) m.PAD_NAMED else m.PAD_ICON) + m.ICON +
        (saveText?.let { m.GAP + words.of("act/$it") } ?: 0.dp) + countWidth(words, count) + 4.dp + m.ARROW
    return button("OpenMission") + save + button("Populate") + button("Print") + m.DIVIDER +
        button("Steps") + button("Guide") + button("Options") + (if (zoomed) m.ICON_BUTTON else 0.dp) + m.ICON_BUTTON
}

/** row A's own padding (8 + 6), the space between the tabs and the actions, and a margin for the type's rounding */
private val ROW_A_EXTRA = 14.dp + 8.dp + 12.dp

/**
 * What a mouse's toolbar shows at [width]: every page tab and every action in view, never a sideways scroll. The
 * tabs keep their names longest and the actions shed theirs first (to a word, then to icons with their tooltips); a
 * window too narrow for even short tabs beside the icons puts the actions on row B.
 */
internal fun shellFit(width: Dp, words: ShellWords, count: Int, zoomed: Boolean): ShellFit {
    val room = width - ROW_A_EXTRA
    val tries = listOf(
        Triple(TabNames.FULL, 10.dp, ActionNames.ALL), Triple(TabNames.FULL, 10.dp, ActionNames.NAMED),
        Triple(TabNames.FULL, 8.dp, ActionNames.NAMED), Triple(TabNames.FULL, 8.dp, ActionNames.SHORT),
        Triple(TabNames.FULL, 6.dp, ActionNames.SHORT), Triple(TabNames.MEDIUM, 6.dp, ActionNames.SHORT),
        Triple(TabNames.SHORT, 8.dp, ActionNames.SHORT), Triple(TabNames.SHORT, 6.dp, ActionNames.SHORT),
        Triple(TabNames.MEDIUM, 6.dp, ActionNames.SAVE), Triple(TabNames.SHORT, 6.dp, ActionNames.SAVE),
        Triple(TabNames.SHORT, 6.dp, ActionNames.ICONS), Triple(TabNames.SHORT, 4.dp, ActionNames.ICONS),
    )
    for ((t, pad, a) in tries) {
        val need = tabsWidth(words, t, pad) + actionsWidth(words, a, count, zoomed)
        if (need <= room) return ShellFit(t, pad, a, below = false, mark = need + words.of("mark") + 6.dp <= room)
    }
    // two rows: the tabs have row A to themselves, and the actions go to the end of row B, leaving the identity room
    val tabRoom = width - 14.dp - 12.dp
    val (t, pad) = listOf(TabNames.FULL to 10.dp, TabNames.MEDIUM to 8.dp, TabNames.SHORT to 8.dp, TabNames.SHORT to 6.dp)
        .firstOrNull { (t, p) -> tabsWidth(words, t, p) <= tabRoom } ?: (TabNames.SHORT to 4.dp)
    val actionRoom = width - 18.dp - 12.dp - 300.dp
    val a = listOf(ActionNames.SHORT, ActionNames.SAVE).firstOrNull { actionsWidth(words, it, count, zoomed) <= actionRoom } ?: ActionNames.ICONS
    return ShellFit(t, pad, a, below = true, mark = false)
}

/**
 * Whether a finger's toolbar ([WdpTouch.device]) fits on one row at [width]: [full] the named actions with the tabs'
 * full names, else the short names with each icon named under it (48 dp a button).
 */
internal fun fingerFits(width: Dp, words: ShellWords, full: Boolean, count: Int, zoomed: Boolean, stepsNamed: Boolean): Boolean {
    val badge = countWidth(words, count)
    val actions = if (full) {
        fun named(text: String) = 4.dp + 20.dp + 18.dp + 6.dp + words.of("fact/$text")
        named("Open mission…") + (4.dp + 10.dp + 18.dp + 6.dp + words.of("fact/Save to DTC") + badge + 4.dp + 24.dp) +
            named(MissionMode.POPULATE) + named("Upd Kneeboard") + (if (stepsNamed) named("Steps") else 50.dp) +
            named("Guide") + named("Options")
    } else 50.dp * 6 + (4.dp + 48.dp + badge + 32.dp)
    val need = tabsWidth(words, if (full) TabNames.FULL else TabNames.SHORT, 9.dp, finger = true) + actions +
        50.dp + (if (zoomed) 50.dp else 0.dp) + 14.dp + 6.dp + 16.dp
    return need <= width
}

/** The shell's menus, one open at a time, drawn in the Planner's own box (not a popup: the checks find them there). */
private enum class ShellMenu { SAVE, MORE, PAGES, OPTIONS }

/** The seat names, as the flight picker and the identity strip say them. */
internal val SEAT_NAMES = listOf("Lead", "Wing", "Element lead", "Element wing")

/**
 * The Planner's toolbar actions (R3-PLAN A2), in one place so the rows, the phone's menu and the checks do the same.
 * Each is what the button does; windows open over the Planner ([PlannerWindows]), WDP's questions in its message box.
 */
internal object PlannerShell {
    /** The edits Save to DTC would write: the DTC page's changed settings plus the card's entries still waiting. */
    fun unsaved(): Int = WdpSession.dtc.unsaved + WdpSession.dataCard.unsavedEntries

    /** Save to DTC: the card's entries go to the DTC page, then the cartridge is saved, asking whatever it must first. */
    fun save() = WdpSession.dtc.saveToDtc(WdpSession.dataCard.entriesToHand())

    /** Re-read DTC from BMS: the cartridge read again (asking first over edits), into the DTC page and the card. */
    fun reread() = WdpSession.dataCard.rereadDtc()

    /**
     * **Populate from Planner** (WDP mode): the Mission section on every device is filled from the save's flight open
     * here, the cartridge as saved on the PC and the attack. Edits not saved yet are asked about first ("Save to DTC
     * and populate", "Populate without them", "Cancel"); with the printed briefing as the Planner's source it says that
     * Populate takes a save's flight, and offers Open mission… (and the last populated flight again, when there is one).
     * [saveFirst]: Save to DTC's menu item, which saves (asking what a save asks) and then populates.
     */
    fun populate(saveFirst: Boolean = false) {
        val open = PlannerPopulate.openFlight()
        if (open == null) {
            val again = PlannerPopulate.snapshotFlight(MissionLink.info.value?.mission)
            val lead = "Populate from Planner takes a flight of a save, and the Planner is planning " +
                (if (PlannerMissionState.fromSave) "no flight yet." else "BMS's printed briefing.")
            if (again != null) WdpDialogs.message(
                MissionMode.POPULATE,
                "$lead Open mission… and pick your flight and seat, or populate again from the flight populated last " +
                    "(${again.label}) with your cartridge as it is saved now.",
                listOf("Open mission…", "Populate again", PlannerPopulate.CANCEL),
            ) { a ->
                when (a) {
                    "Open mission…" -> openMission()
                    "Populate again" -> go(again)
                }
            } else WdpDialogs.message(
                MissionMode.POPULATE,
                "$lead Open mission… and pick your flight and seat, save your cartridge (Save to DTC), then press Populate from Planner.",
                listOf("Open mission…", PlannerPopulate.CANCEL),
            ) { a -> if (a == "Open mission…") openMission() }
            return
        }
        if (saveFirst) PlannerPopulate.saveThenPopulate(open, ::populated) else go(open)
    }

    /** Populates [t], asking first when the Planner holds edits not saved to the DTC (they would not travel). */
    private fun go(t: PlannerPopulate.Target) {
        val n = PlannerPopulate.unsaved()
        if (n == 0) { PlannerPopulate.launch(t, ::populated); return }
        WdpDialogs.message(
            MissionMode.POPULATE, PlannerPopulate.unsavedQuestion(n),
            listOf(PlannerPopulate.SAVE_AND_POPULATE, PlannerPopulate.WITHOUT, PlannerPopulate.CANCEL),
        ) { a ->
            when (a) {
                PlannerPopulate.SAVE_AND_POPULATE -> PlannerPopulate.saveThenPopulate(t, ::populated)
                PlannerPopulate.WITHOUT -> PlannerPopulate.launch(t, ::populated)
            }
        }
    }

    /** What a Populate answered, in the Planner's message box; "Show the Map" when the Mission tabs can be reached. */
    private fun populated(r: PlannerPopulate.Result) {
        val show = PlannerPopulate.onShowMission
        if (r.ok && show != null) WdpDialogs.message(MissionMode.POPULATE, r.text, listOf("OK", "Show the Map")) { a -> if (a == "Show the Map") show() }
        else WdpDialogs.message(if (r.ok) MissionMode.POPULATE else "Populate from Planner: not done", r.text)
    }

    fun openMission() = PlannerWindows.show(PlannerWindow.OPEN_MISSION)
    fun print() = PlannerWindows.show(PlannerWindow.PRINT)
    fun guide() = PlannerGuide.open(WdpSession.page)
    /** **Settings**: WDP's Settings window, as the Planner has it ([PlannerSettings]). */
    fun settings() = PlannerSettings.open()
    fun credit() = PlannerGuide.open("credit")

    /**
     * WDP's **Options** menu (`fclsMain.mnuOptions`, with its Settings and Help → About beside it), as the Planner has
     * it: the page list is the tab strip, and its ATO Target List is a page of the tab strip too ([WdpPage.ATO]), so what
     * is left is Settings and About. One list, so the toolbar's Options menu and the phone's ⋮ menu say the same; the
     * probes are `<prefix>/Settings`, `/Credit`.
     */
    val OPTIONS: List<Triple<String, String, () -> Unit>> = listOf(
        Triple("Settings…", "Settings") { settings() },
        Triple("About WDP (Falcas)", "Credit") { credit() },
    )
    fun fullWindow() { WdpFocus.on = !WdpFocus.on; WdpFocus.auto = false }

    /**
     * The identity strip's press: with a save open, its flight picker; with the printed briefing, WDP's selection
     * window over the briefing's package (WDP's Different Flight); with nothing yet, the Steps (Open mission… is the
     * toolbar's, not the strip's: one place per action).
     */
    fun identity(hasBriefing: Boolean) = when {
        PlannerMissionState.fromSave && PlannerMissionState.flight != null -> PlannerWindows.show(PlannerWindow.FLIGHT_PICKER, PlannerMissionState.ref?.file)
        hasBriefing -> WdpSession.dataCard.differentFlight()
        else -> PlannerSteps.show()
    }

    /** Back to BMS briefing (R3-PLAN A1): the printed briefing and the cartridge are the Planner's source again. */
    fun backToBriefing() = PlannerMissionState.backToBriefing()
}

/** One amber notice of the identity row: a short label and what a press says in full. */
internal class ShellNotice(val label: String, val detail: String, val onPress: (() -> Unit)? = null) {
    /** what a press does: its own action (the reset note's Undo question), else the detail in a message box */
    fun press() { onPress?.invoke() ?: WdpDialogs.message("Notice", detail) }
}

/** "14:02" in this device's time, or "" for none. */
internal fun hhmm(ms: Long): String =
    if (ms <= 0) "" else runCatching { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms)) }.getOrDefault("")

/**
 * The identity of what the Planner is planning (R3-UI §3.2), as segments read left to right: the theater, where the
 * mission comes from, the package, the flight, the seat, and where the steerpoints come from. [wide] keeps the
 * theater and the file's details; a narrow row drops them first.
 */
internal fun identitySegments(mission: WdpMission, missionData: MissionData?, theaterName: String?, cardSeat: Int, wide: Boolean): List<String> {
    val out = ArrayList<String>()
    val theater = mission.theater?.name ?: theaterName?.trim()?.takeIf { it.isNotEmpty() }
    val f = mission.flight
    if (f != null && PlannerMissionState.fromSave) {
        if (wide && theater != null) out += theater
        out += PlannerMissionState.ref?.file?.takeIf { it.isNotBlank() } ?: "Mission file"
        val pkg = f.packageNumber.takeIf { it > 0 }?.let { "Pkg $it" + (f.row.mission.takeIf { m -> m.isNotBlank() && wide }?.let { m -> " $m" } ?: "") }
        if (pkg != null) out += pkg
        out += f.row.callsign + (if (wide && f.row.count > 0 && f.row.aircraft.isNotBlank()) " · ${f.row.count}x ${f.row.aircraft}" else "")
        out += SEAT_NAMES.getOrElse(mission.seat) { "Lead" } + (if (wide && mission.sourceLine.isNotEmpty()) " · ${mission.sourceLine}" else "")
        // the answer to the Precision STPT question, which a narrow row keeps when it drops the source line
        if (!wide) mission.precision?.let { out += "Precision STPT: " + if (it) "Yes" else "No" }
        return out
    }
    val b = missionData?.briefing ?: return out
    if (wide && theater != null) out += theater
    out += "BMS briefing" + hhmm(missionData.briefingModified).takeIf { it.isNotEmpty() }?.let { ", printed $it" }.orEmpty()
    b.overview.packageId?.takeIf { it.isNotBlank() }?.let { out += "Pkg $it" }
    b.overview.flight?.takeIf { it.isNotBlank() }?.let { out += it }
    out += SEAT_NAMES.getOrElse(cardSeat) { "Lead" } + (if (wide && mission.sourceLine.isNotEmpty()) " · ${mission.sourceLine}" else "")
    return out
}

/**
 * What the Mission section shows now, for row B: "Mission: populated 22:51" or "Mission: not populated yet". What
 * changed on disk since a Populate is not shown (the pilot asked not to be told; `Populated.changed` is still served).
 * A press populates. [short] leaves "Mission: " off.
 */
internal fun missionShows(info: com.bmscompanion.app.data.mission.MissionSourceInfo?, short: Boolean = false): String {
    val p = info?.populated
    val s = when {
        info?.wdp != true -> "BMS briefing (EZBoards mode)"
        p == null -> "not populated yet"
        else -> "populated" + hhmm(p.at).takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
    }
    return if (short) s else "Mission: $s"
}

/** [missionShows]'s ink: cyan while the Mission section shows what was populated, amber before the first Populate. */
internal fun missionShowsInk(info: com.bmscompanion.app.data.mission.MissionSourceInfo?): Color {
    val p = info?.populated
    return if (info?.wdp == true && p != null) Hud.Cyan else Hud.Amber
}

/**
 * The Planner, given the mission and the theater's name as BMS reports it. [WdpPane] hands in what the PC sends;
 * the headless renders (`--wdprender`) hand in a briefing and a cartridge from disk, so the page they draw at a
 * phone's, a tablet's and a PC's width is this one, shell included. A save's flight picked with Open mission…
 * comes from [PlannerMissionState] ([plannerMission]).
 *
 * **The shell** (R3-PLAN A2, R3-UI §3): the page comes first — fitted whole, no scroll, and every dp the shell takes
 * is the page's — so the shell is two thin rows. **Row A** (40 dp): WDP's page tabs, the three attack pages one
 * **Attack** tab with its three pages a rail beside the page ([ShellTab], [AttackRail]), ATO Targets last; then Open
 * mission…, Save to DTC with the count of edits not saved and a menu (Re-read DTC from BMS, Save to DTC and populate),
 * Populate from Planner, Upd Kneeboard, a rule, Steps, Guide, WDP's Options menu (Settings…, About WDP;
 * [PlannerShell.OPTIONS]) and Full window. With a mouse every tab and action is always in view, never scrolled: the
 * names shorten as the room shrinks ([shellFit]), and a window too narrow for one row puts the actions on row B.
 * **Row B** (24 dp): the identity strip (what is planned, from where; a press
 * changes the flight, and with nothing open shows the Steps), Back to BMS briefing, amber notice chips, what the Mission
 * section shows (a status: populated when, or that it wants a Populate), and Falcas's credit. **One place per action**:
 * nothing in row B, the ⋮ menu or on a page repeats a toolbar button (WDP's own cartridge buttons on the DataCard and
 * the DTC page excepted, as pilots look for them there). The **Steps** ([PlannerSteps]) sit beside the page at 840 dp
 * and wider, a sheet below that. On a phone the two rows fold into one ([ShellLayout.COMPACT]); held sideways the
 * Planner goes full window by itself.
 *
 * The Planner only runs in WDP mode (the Mission tab strip greys it out in EZBoards mode), and EZBoards is suspended
 * there, so it offers no Generate kneeboards (EZBoards): the cockpit's kneeboards are its own Upd Kneeboard.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun WdpPlanner(missionData: MissionData?, theaterName: String?, modifier: Modifier = Modifier) {
    WdpSession.started = true
    // Settings → Auto load last mission on startup, once per run of the app
    PlannerStartup()
    // The pages run in the order of WDP's own tab strip, and open on the DataCard as it does.
    var page by WdpSession::page
    val current = WdpPage.entries.firstOrNull { it.name == page } ?: WdpPage.DATACARD
    val form by produceState<WdpForm?>(null, current) { value = if (current.native) null else Repo.wdpForm(current.form) }
    // The pages that are wired get their values from their plan; the rest show the designer's page as it is.
    val toss = WdpSession.toss
    val popup = WdpSession.popup
    val dataCard = WdpSession.dataCard
    val hadb = WdpSession.hadb
    val dtc = WdpSession.dtc
    val performance = WdpSession.performance
    // the card prints the chosen attack page's offsets and figures and shows its map (cntDTC.Profiles, FillMap)
    dataCard.attackPages = { Triple(popup.plan, hadb.plan, toss.plan) }
    // the card and the Performance page write into each other, as WDP's do (WdpHandOff)
    remember { WdpHandOff.join(dataCard, performance) }
    // whichever button saved the cartridge, the card measures its entries against the file as saved
    // (and the Steps mark Save to DTC done for the flight open now)
    remember { dtc.onSaved = { text -> dataCard.takeCartridge(text); PlannerSteps.saved() } }
    val wiring: WdpWiring? = when (current) {
        WdpPage.TOSS -> toss
        WdpPage.POPUP -> popup
        WdpPage.HADB -> hadb
        WdpPage.DTC -> dtc
        WdpPage.PERFORMANCE -> performance
        WdpPage.BRIEFING, WdpPage.DATACARD, WdpPage.COORDINATION -> dataCard
        // the Map page edits the DTC page's cartridge through its calls (DtcWiring's place…), not through a form;
        // the ATO Targets page only reads the save
        WdpPage.MAP, WdpPage.ATO -> null
    }
    val theater by produceState(if (theaterName == WdpSession.theaterName) WdpSession.theater else null, theaterName) {
        value = plannerTheater(Repo.index().theaters, theaterName)
        WdpSession.theaterName = theaterName; WdpSession.theater = value
    }
    // whether the lookup above has run for this name, so the "newer theater" notice never flashes while it loads
    val theaterKnown = theaterName == WdpSession.theaterName
    // the save's flight picked with Open mission…, read here so a new pick (or seat) is a new mission
    val flight = PlannerMissionState.flight.takeIf { PlannerMissionState.fromSave }
    val ref = PlannerMissionState.ref
    val seat = PlannerMissionState.seat
    val precision = PlannerMissionState.precision
    val mission = remember(missionData, theater, flight, ref, seat, precision) { plannerMission(missionData, theater, flight) }
    // the attack pages' mission: the same, with the steerpoints the pilot placed on the DTC page in this session over
    // the route whatever the Precision answer (WdpMission.placed; "IP STPT at the VRP")
    val placed = dtc.placedThisSession()
    val attackMission = remember(mission, placed) { mission.withPlaced(placed) }
    // SetCoordData, as fclsMain runs it for every page when a theater loads: Pop-up and HADB take it on their own;
    // TOSS and the DataCard read it from the mission. An unknown theater leaves the pages as they were.
    val coords = mission.coords
    LaunchedEffect(coords) {
        val c = coords ?: return@LaunchedEffect
        if (c == WdpSession.appliedCoords) return@LaunchedEffect
        popup.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
        hadb.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
        WdpSession.appliedCoords = c
    }
    LaunchedEffect(mission, theaterKnown) {
        // not before the theater is looked up for this name: a save of another theater would otherwise be handed to
        // every page once in the theater it replaces (a Hellas flight laid on Korea's airports and projection)
        if (!theaterKnown) return@LaunchedEffect
        if (mission == WdpSession.appliedMission) return@LaunchedEffect
        toss.onMission(attackMission)
        popup.onMission(attackMission)
        hadb.onMission(attackMission)
        WdpSession.appliedAttackMission = attackMission
        // the card also reads the airports, charts and cartridge the app has for the mission, so it is prepared
        dataCard.prepare(mission)
        dtc.onMission(mission)
        // the performance page reads the engines, the aircraft and weapon data and the theater's airfields first
        performance.prepare(mission)
        // then the card hands the page its cruise altitude, weather and runway, and the page its figures back
        WdpHandOff.cardToPerformance(dataCard, performance, mission.briefing)
        // only once all of it is done: leaving the tab half way leaves the rest to be done on the way back
        WdpSession.appliedMission = mission
    }
    // a steerpoint placed or moved on the DTC page: the attack pages plan from it (their tables only change on a change)
    LaunchedEffect(attackMission, theaterKnown) {
        if (!theaterKnown || mission != WdpSession.appliedMission) return@LaunchedEffect
        if (attackMission == WdpSession.appliedAttackMission) return@LaunchedEffect
        toss.onMission(attackMission)
        popup.onMission(attackMission)
        hadb.onMission(attackMission)
        WdpSession.appliedAttackMission = attackMission
    }
    // Open mission… or Pick a flight that gave the same mission again (a TE whose weather alone BMS saved, SAVE WTH):
    // the card reads the save's own weather file again all the same (a new mission reads it in prepare)
    val pick = PlannerMissionState.changes
    LaunchedEffect(pick) { if (mission == WdpSession.appliedMission) dataCard.pickedAgain(pick) }
    // BMS printed a briefing while a save's flight is planned: the card joins it (the newer weather wins)
    val printedAt = missionData?.briefingModified ?: 0L
    LaunchedEffect(printedAt) { dataCard.briefingPrinted(printedAt) }
    // back on a card page after working on an attack page or the Performance page: the card reads their figures
    // again, as WDP's pages update the card as they go
    LaunchedEffect(current) {
        if (wiring === dataCard) { dataCard.refreshAttack(); WdpHandOff.performanceToCard(performance, dataCard) }
        // (looking at an attack page makes it no current attack: only a change or an apply does, AttackFocus)
        // the Attack tab opens on the page last shown next time, on this device (AttackTabs)
        AttackTabs.used(current)
    }
    // the Steps (PlannerSteps): the ticks belong to the flight open now; an Upd Kneeboard that wrote pages is done for
    // it; a toolbar button lit by a press on its step goes out after a moment
    val stepsKey = PlannerSteps.missionKey()
    LaunchedEffect(stepsKey) { PlannerSteps.follow(stepsKey) }
    val printResults = KneeboardPrintSession.results
    LaunchedEffect(printResults) { PlannerSteps.printResults(printResults, KneeboardPrintSession.resultsOf) }
    val stepFlash = PlannerSteps.flash
    val stepFlashes = PlannerSteps.flashes
    LaunchedEffect(stepFlash, stepFlashes) { if (stepFlash != null) { delay(1600); PlannerSteps.flashOff() } }

    // read so that the take-off figures the Performance page hands the card are drawn when they arrive
    @Suppress("UNUSED_VARIABLE") val handOff = WdpHandOff.tick
    val hidden = current.hiddenHere()
    val values = wiring?.values(hidden)
        ?: remember(current) { WdpValues(hidden.associateWith { "hidden" }) }
    // The attack this page works out stays on its own map: nothing reaches the Mission map or the PC by itself any
    // more (A10). Populate from Planner takes it when the pilot presses it (WdpAttackOverlay.current / toSend).
    // A press or an entry on a card page may hand something to the Performance page (a take-off spec, a runway, the
    // weather again), and one on the Performance page changes the card's take-off figures: both are passed on after
    // the page itself has handled it.
    val onValue: ((String, String) -> Unit)? = wiring?.let { w ->
        { name: String, value: String ->
            w.onValue(name, value)
            if (w === dataCard) WdpHandOff.afterCard(name, dataCard, performance, mission.briefing)
            else if (w === performance) WdpHandOff.performanceToCard(performance, dataCard)
        }
    }
    val onClick: ((String) -> Unit)? = wiring?.let { w ->
        { name: String ->
            w.onClick(name)
            if (w === dataCard) WdpHandOff.afterCard(name, dataCard, performance, mission.briefing)
            else if (w === performance) WdpHandOff.performanceToCard(performance, dataCard)
        }
    }

    // What row B says. The identity follows the source; the notices are amber chips, never extra lines.
    val info by MissionLink.info.collectAsState()
    val hasBriefing = mission.briefing != null
    val notices = buildList {
        newerTheaterLine(theaterName, theater, theaterKnown)?.let { add(ShellNotice(if (theater == null) "Theater newer than the app" else "Theater data incomplete", it)) }
        val bmsTheater = info?.bms?.theater?.trim()
        val saveTheater = PlannerMissionState.theater?.trim()
        if (PlannerMissionState.fromSave && flight != null && !bmsTheater.isNullOrEmpty() && !saveTheater.isNullOrEmpty() && bmsTheater.norm() != saveTheater.norm())
            add(ShellNotice("BMS is on $bmsTheater; this file is $saveTheater",
                "Falcon BMS is set to $bmsTheater, and the mission open in the Planner is from $saveTheater. The pages use " +
                    "$saveTheater's coordinates. To fly it, choose $saveTheater in Falcon BMS's launcher first."))
        // the card's weather when it is not what a pilot would take for granted (the save's file not used, or newer than PRINT)
        dataCard.weatherNotice?.let { (label, detail) -> add(ShellNotice(label, detail)) }
        // what the PC clears by itself at a switch or a new mission is never shown (the pilot asked for no notice)
    }
    val count = PlannerShell.unsaved()

    // the scale the page is drawn at, which the child windows are drawn at too (WdpDialogHost)
    var pageScale by remember { mutableStateOf(0f) }
    // a phone's pinch zoom, new for every page, so a page always opens whole
    val zoom = remember(current) { WdpZoom() }
    var menu by remember { mutableStateOf<ShellMenu?>(null) }
    // the name of an icon under the mouse (or long-pressed), shown under the toolbar
    var hint by remember { mutableStateOf<String?>(null) }
    // worked by finger (WdpTouch): a finger's toolbar — 48 dp, each icon named under it — and bigger type
    val touch = WdpTouch.device
    // the Planner's window, read here rather than in the box below (see PlannerWindowHost's `open`)
    val window = PlannerWindows.open
    // the box the tooltips are drawn in follows the pointer (WdpTips; it takes nothing from the controls)
    BoxWithConstraints(modifier.fillMaxSize().wdpTipArea()) {
        val layout = ShellLayout.of(maxWidth, maxHeight)
        val phoneUpright = maxWidth < 600.dp && maxHeight >= 480.dp
        val narrowPhone = maxWidth < 400.dp
        // a phone held sideways: its height is the page, so the Planner goes full window by itself when it opens here
        // or the phone is turned (still not remembered: the pilot can turn it off), and turning it upright again gives
        // the Mission header back, unless the pilot chose full window himself.
        // Judged on the SCREEN of a phone or tablet, never on the Planner's own area and never on the PC: the area
        // grows when full window hides the Mission header, so a short PC window (snapped beside another) turned full
        // window on, grew past the line, turned it off, shrank back — and flickered between the two for ever.
        val screen = LocalConfiguration.current
        val short = Platform.touchFirst && screen.screenHeightDp in 1 until 480 && screen.screenWidthDp > screen.screenHeightDp
        LaunchedEffect(short) {
            if (short) { if (!WdpFocus.chosen) { WdpFocus.on = true; WdpFocus.auto = true } }
            else if (WdpFocus.auto) { WdpFocus.auto = false; if (WdpFocus.chosen) WdpFocus.on = false }
        }
        // a phone too narrow for the Full window button beside the others: it is in the ⋮ menu instead
        val fullInMenu = layout == ShellLayout.COMPACT && maxWidth < 480.dp
        val rowA = if (touch) 48.dp else 40.dp
        // the Steps: a panel beside the page at 840 dp and wider, else a sheet; the toolbar button of the step the pilot
        // is on wears an amber ring while they show, and one a step line lit wears it for a moment (PlannerSteps)
        // (and only held sideways: beside a landscape page on an upright tablet the panel left the page a third of the screen)
        val stepsSide = (layout == ShellLayout.FULL || layout == ShellLayout.ICONS) && maxWidth > maxHeight
        val stepsWidth = if (maxWidth >= 1500.dp) 290.dp else 256.dp
        // named on the toolbar only where the tabs still fit beside it (an icon with its tip below that)
        val stepsNamed = maxWidth >= 1500.dp
        PlannerSteps.side = stepsSide
        PlannerSteps.load()
        val stepsShown = if (stepsSide) PlannerSteps.open else PlannerSteps.sheet
        val stepFacts = PlannerSteps.facts(count, info?.mission, missionData?.briefingModified ?: 0L)
        val ringed = PlannerSteps.ringed(stepFacts, stepsShown)
        val stepsButton: @Composable (Boolean, (String, Boolean) -> Unit) -> Unit = { named, onHint ->
            ShellButton(
                "Steps", Icons.Default.FormatListNumbered, "Shell/Steps", named = named, on = stepsShown, onHint = onHint, short = "Steps",
            ) { PlannerSteps.toggle(); menu = null }
        }
        // Every page tab and every action in view, never a sideways scroll: the words are measured in the type they are
        // drawn in, and a mouse's toolbar sheds names until it fits (shellFit), or puts its actions on row B ([ShellFit.below]).
        val words = rememberShellWords()
        val mouse = !touch && layout != ShellLayout.COMPACT
        val fit = if (mouse) shellFit(maxWidth, words, count, zoom.zoomed) else null
        val below = fit?.below == true
        // by finger: the named toolbar where it fits, else the short names with each icon named under it, else the
        // tablet's two rows (a 48-dp button does not shrink)
        val touchBar = if (!touch || layout == ShellLayout.COMPACT) layout else when {
            layout == ShellLayout.FULL && fingerFits(maxWidth, words, true, count, zoom.zoomed, stepsNamed) -> ShellLayout.FULL
            layout != ShellLayout.TABLET && fingerFits(maxWidth, words, false, count, zoom.zoomed, false) -> ShellLayout.ICONS
            else -> ShellLayout.TABLET
        }
        // A tablet held upright, by finger: the page tabs get row A to themselves (all eight fit there), and the actions go
        // to row B beside the identity, rather than five tabs and a sideways scroll beside six buttons.
        val split = touch && touchBar == ShellLayout.TABLET
        val rowB = if (split) 46.dp else if (touch) 36.dp else if (below) 32.dp else 24.dp
        // where the toolbar's actions end: the menus and the icon names open under it
        val actionsBottom = if (split || below) rowA + rowB else rowA
        // ...and the landscape page leaves a band under it there: what the Mission section shows and the credit go into
        // it, so row B's identity keeps its room (on the app's own pages, which fill the area, or zoomed, they stay on row B)
        val pageAspect = form?.takeIf { it.width > 0 && !current.native }?.let { it.height.toFloat() / it.width }
        // the Attack tab's pages are a slim rail beside the page (AttackRail), never a row over it: the page keeps its height
        val subTabs = current in AttackTabs.PAGES
        val splitBand = if (split && pageAspect != null) {
            val areaH = maxHeight - rowA - rowB - 4.dp
            (areaH - minOf(areaH, (maxWidth - 8.dp) * pageAspect)) / 2
        } else 0.dp
        val footer = splitBand >= 40.dp && !zoom.zoomed
        // the actions
        val setHint: (String, Boolean) -> Unit = { label, on -> if (on) hint = label else if (hint == label) hint = null }
        // one place per action: what is here is in no other place of the shell (the ⋮ menu holds only what a
        // narrow toolbar has no room for), and a Steps line only lights its button here (PlannerSteps)
        val om = "Shell/OpenMission" in ringed
        val sm = "Shell/SaveToDtc" in ringed
        val pm = "Shell/Populate" in ringed
        val km = "Shell/Print" in ringed
        // a mouse's actions (row A, or row B where the window is too narrow for both: ShellFit.below): the four that act
        // on the mission, a rule, then the rest; each named as the room allows (shellFit), and always by its tooltip
        val mouseActions = @Composable {
            val a = fit?.actions ?: ActionNames.ICONS
            fun t(key: String) = a.text(key)
            ShellButton("Open mission…", Icons.Default.FolderOpen, "Shell/OpenMission", named = t("OpenMission") != null, shown = t("OpenMission"), onHint = setHint, mark = om) { PlannerShell.openMission() }
            SaveButton(count, named = t("SaveToDtc") != null, onHint = setHint, onMenu = { menu = if (menu == ShellMenu.SAVE) null else ShellMenu.SAVE }, mark = sm)
            ShellButton(MissionMode.POPULATE, Icons.Default.Publish, "Shell/Populate", named = t("Populate") != null, shown = t("Populate"), onHint = setHint, busy = PlannerPopulate.busy, mark = pm) { PlannerShell.populate() }
            ShellButton("Upd Kneeboard", Icons.Default.Print, "Shell/Print", named = t("Print") != null, shown = t("Print"), onHint = setHint, mark = km) { PlannerShell.print() }
            Box(Modifier.padding(horizontal = (MouseBar.DIVIDER - 1.dp) / 2).width(1.dp).height(18.dp).background(Hud.Outline))
            stepsButton(t("Steps") != null, setHint)
            ShellButton("Guide", Icons.Default.MenuBook, "Shell/Guide", named = t("Guide") != null, onHint = setHint) { PlannerShell.guide() }
            ShellButton("Options", Icons.Default.Settings, "Shell/Options", named = t("Options") != null, on = menu == ShellMenu.OPTIONS, onHint = setHint) { menu = if (menu == ShellMenu.OPTIONS) null else ShellMenu.OPTIONS }
            if (zoom.zoomed) ShellButton("Fit the page", Icons.Default.FitScreen, "Shell/Fit", onHint = setHint, tint = Hud.Amber, short = "Fit") { zoom.reset() }
            ShellButton(
                if (WdpFocus.on) "Leave full window" else "Full window",
                if (WdpFocus.on) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                "Shell/FullWindow", onHint = setHint, on = WdpFocus.on, short = if (WdpFocus.on) "Normal" else "Full",
            ) { PlannerShell.fullWindow() }
        }
        // a tablet's actions by finger (row B, where Options goes into the ⋮ menu)
        val tabletActions = @Composable {
            ShellButton("Open mission…", Icons.Default.FolderOpen, "Shell/OpenMission", onHint = setHint, short = "Open", mark = om) { PlannerShell.openMission() }
            SaveButton(count, named = false, onHint = setHint, onMenu = { menu = if (menu == ShellMenu.SAVE) null else ShellMenu.SAVE }, mark = sm)
            ShellButton(MissionMode.POPULATE, Icons.Default.Publish, "Shell/Populate", onHint = setHint, short = "Populate", busy = PlannerPopulate.busy, mark = pm) { PlannerShell.populate() }
            stepsButton(false, setHint)
            if (!split) ShellButton("Options", Icons.Default.Settings, "Shell/Options", on = menu == ShellMenu.OPTIONS, onHint = setHint, short = "Options") { menu = if (menu == ShellMenu.OPTIONS) null else ShellMenu.OPTIONS }
            ShellButton("More", Icons.Default.MoreVert, "Shell/More", onHint = setHint, short = "More") { menu = if (menu == ShellMenu.MORE) null else ShellMenu.MORE }
        }
        Column(Modifier.fillMaxSize().background(Hud.Bg)) {
            // ---- row A: the page tabs and the actions
            Row(
                Modifier.fillMaxWidth().height(rowA).padding(start = 8.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (layout == ShellLayout.COMPACT) {
                    ShellChip(
                        "Page: " + (if (current in AttackTabs.PAGES && !narrowPhone) "Attack · " else "") + (if (narrowPhone) current.short else current.label),
                        probe = "Shell/PagePicker", arrow = true,
                    ) { menu = if (menu == ShellMenu.PAGES) null else ShellMenu.PAGES }
                    Spacer(Modifier.width(6.dp))
                    val who = identitySegments(mission, missionData, theaterName, dataCard.plan.selPilotSeat, wide = false)
                        .let { s -> if (s.isEmpty()) "No mission yet" else s.drop(if (s.size > 2) s.size - 2 else 0).joinToString(" · ") }
                    Box(Modifier.weight(1f)) {
                        ShellChip(who, probe = "Shell/Identity", arrow = true, warn = notices.isNotEmpty()) { PlannerShell.identity(hasBriefing) }
                    }
                } else {
                    // (the "WDP" mark gives its room to the tabs by finger, and where the tabs need it)
                    if (fit?.mark == true) {
                        Text("WDP", color = Hud.TextFaint, style = shellType(10.sp, FontWeight.Bold), modifier = Modifier.padding(end = 6.dp))
                    }
                    Row(
                        // a mouse's tabs are all in view (shellFit), so they never scroll; a finger's may still be swiped
                        Modifier.weight(1f).then(if (touch) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                        horizontalArrangement = Arrangement.spacedBy(if (touch) 4.dp else MouseBar.TAB_GAP),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // the pages, the three attack pages one Attack tab (ShellTab; its three are a rail beside the page, AttackRail)
                        for (t in ShellTab.ALL) {
                            val on = current in t.pages
                            Text(
                                // a mouse's as the room allows; by finger the short names, so every tab shows beside the actions
                                if (fit != null) t.named(fit.tabs) else if (touchBar == ShellLayout.FULL) t.label else t.short,
                                Modifier.clip(RoundedCornerShape(if (touch) 8.dp else 6.dp))
                                    .background(if (on) Hud.Amber.copy(alpha = 0.16f) else Hud.Surface)
                                    // a faint light under the mouse, as every button of the Planner's own has
                                    .plannerPress { if (!on) page = t.target().name; menu = null }
                                    // the height of the buttons beside them, the words on its middle (shellType)
                                    .height(if (touch) 44.dp else MouseBar.HEIGHT)
                                    .padding(horizontal = if (touch) 9.dp else fit?.tabPad ?: 10.dp)
                                    .wrapContentHeight(Alignment.CenterVertically)
                                    .capsCentred(if (touch) 13.sp else 12.sp)
                                    .plannerProbe("Shell/Tab/${t.key}"),
                                color = if (on) Hud.Amber else Hud.TextDim,
                                style = shellType(if (touch) 13.sp else 12.sp),
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                // the actions (setHint and the Steps' marks are above, shared with row B); a mouse's are mouseActions
                // (Fit the page and Full window included), on row B where the window is too narrow for them here
                when {
                    layout == ShellLayout.COMPACT -> {
                        SaveButton(count, named = false, onHint = setHint, onMenu = null, mark = sm)
                        stepsButton(false, setHint)
                        ShellButton("More", Icons.Default.MoreVert, "Shell/More", onHint = setHint, short = "More") { menu = if (menu == ShellMenu.MORE) null else ShellMenu.MORE }
                    }
                    mouse -> if (!below) mouseActions()
                    touchBar == ShellLayout.FULL -> {
                        ShellButton("Open mission…", Icons.Default.FolderOpen, "Shell/OpenMission", named = true, onHint = setHint, mark = om) { PlannerShell.openMission() }
                        SaveButton(count, named = true, onHint = setHint, onMenu = { menu = if (menu == ShellMenu.SAVE) null else ShellMenu.SAVE }, mark = sm)
                        ShellButton(MissionMode.POPULATE, Icons.Default.Publish, "Shell/Populate", named = true, onHint = setHint, busy = PlannerPopulate.busy, mark = pm) { PlannerShell.populate() }
                        ShellButton("Upd Kneeboard", Icons.Default.Print, "Shell/Print", named = true, onHint = setHint, mark = km) { PlannerShell.print() }
                        stepsButton(stepsNamed, setHint)
                        ShellButton("Guide", Icons.Default.MenuBook, "Shell/Guide", named = true, onHint = setHint) { PlannerShell.guide() }
                        ShellButton("Options", Icons.Default.Settings, "Shell/Options", named = true, on = menu == ShellMenu.OPTIONS, onHint = setHint) { menu = if (menu == ShellMenu.OPTIONS) null else ShellMenu.OPTIONS }
                    }
                    touchBar == ShellLayout.ICONS -> {
                        ShellButton("Open mission…", Icons.Default.FolderOpen, "Shell/OpenMission", onHint = setHint, short = "Open", mark = om) { PlannerShell.openMission() }
                        SaveButton(count, named = false, onHint = setHint, onMenu = { menu = if (menu == ShellMenu.SAVE) null else ShellMenu.SAVE }, mark = sm)
                        ShellButton(MissionMode.POPULATE, Icons.Default.Publish, "Shell/Populate", onHint = setHint, short = "Populate", busy = PlannerPopulate.busy, mark = pm) { PlannerShell.populate() }
                        ShellButton("Upd Kneeboard", Icons.Default.Print, "Shell/Print", onHint = setHint, short = "Upd KB", mark = km) { PlannerShell.print() }
                        stepsButton(false, setHint)
                        ShellButton("Guide", Icons.Default.MenuBook, "Shell/Guide", onHint = setHint, short = "Guide") { PlannerShell.guide() }
                        ShellButton("Options", Icons.Default.Settings, "Shell/Options", on = menu == ShellMenu.OPTIONS, onHint = setHint, short = "Options") { menu = if (menu == ShellMenu.OPTIONS) null else ShellMenu.OPTIONS }
                    }
                    // by finger on a tablet they are on row B (split), and row A is the tabs'
                    else -> if (!split) tabletActions()
                }
                // back to the page fitted whole, after a pinch zoom (or a double tap)
                if (zoom.zoomed && !split && !mouse) ShellButton("Fit the page", Icons.Default.FitScreen, "Shell/Fit", onHint = setHint, tint = Hud.Amber, short = "Fit") { zoom.reset() }
                // Full window for the planner: the mission header and tab strip step aside and the page grows into the
                // room they leave, as it grows with the window itself
                if (!mouse && touchBar != ShellLayout.TABLET && !fullInMenu) {
                    ShellButton(
                        if (WdpFocus.on) "Leave full window" else "Full window",
                        if (WdpFocus.on) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        "Shell/FullWindow", onHint = setHint, on = WdpFocus.on, short = if (WdpFocus.on) "Normal" else "Full",
                    ) { PlannerShell.fullWindow() }
                }
            }
            // ---- row B: what is planned, from where; the notices; what the app shows; the credit
            if (layout != ShellLayout.COMPACT) {
                val wide = layout == ShellLayout.FULL || layout == ShellLayout.ICONS
                val segs = identitySegments(mission, missionData, theaterName, dataCard.plan.selPilotSeat, wide)
                val rowText = if (split) 12.sp else if (touch) 13.sp else 11.5.sp
                Row(
                    Modifier.fillMaxWidth().height(rowB).padding(start = 10.dp, end = if (split) 6.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (segs.isEmpty()) {
                            // nothing planned yet: the strip says so and shows the Steps (Open mission… is the toolbar's)
                            Box(
                                Modifier.weight(1f, fill = false).fillMaxHeight().clip(RoundedCornerShape(4.dp)).clickable { PlannerShell.identity(hasBriefing) }
                                    .padding(horizontal = 2.dp).plannerProbe("Shell/Identity"),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    "No mission yet: Open mission… on the toolbar, or press PRINT in BMS (Steps)", color = Hud.TextDim,
                                    fontSize = rowText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Box(
                                Modifier.weight(1f, fill = false).fillMaxHeight().clip(RoundedCornerShape(4.dp)).clickable { PlannerShell.identity(hasBriefing) }
                                    .padding(horizontal = 2.dp).plannerProbe("Shell/Identity"),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(segs.joinToString("  ›  ") + "  ▾", color = Hud.Text, fontSize = rowText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            // Back to BMS briefing is not here: it is in the windows this strip opens (Pick a flight,
                            // like Open mission…), beside what they say is planned — one place for it
                        }
                    }
                    notices.forEachIndexed { i, n -> NoticeChip(n, "Shell/Notice/$i", short = !wide) }
                    // what the Mission section shows: populated when, or that it wants a Populate (a status, not a second
                    // Populate button: the toolbar's is the one)
                    val source = info?.mission
                    // (an upright tablet's page band holds these two when it has room: SplitFooter, under the page)
                    if (!footer) {
                        Box(
                            Modifier.fillMaxHeight().padding(horizontal = 2.dp).plannerProbe("Shell/MissionShows"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                missionShows(source, short = !wide), color = missionShowsInk(source), fontSize = if (split) 11.5.sp else if (touch) 12.5.sp else 11.sp, maxLines = 1,
                            )
                        }
                        // Falcas's credit, always in view; the credit page is Options → About WDP
                        Box(
                            Modifier.fillMaxHeight().padding(horizontal = 2.dp).plannerProbe("Shell/Credit"),
                            contentAlignment = Alignment.Center,
                        ) { Text("WDP by Falcas", color = Hud.TextFaint, fontSize = if (split) 10.5.sp else if (touch) 12.sp else 10.5.sp, maxLines = 1) }
                    }
                    // a tablet held upright, by finger: the actions are here (row A is the tabs')
                    if (split) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            tabletActions()
                            if (zoom.zoomed) ShellButton("Fit the page", Icons.Default.FitScreen, "Shell/Fit", onHint = setHint, tint = Hud.Amber, short = "Fit") { zoom.reset() }
                        }
                    }
                    // a mouse's window too narrow for the tabs and the actions on one row: the actions are here
                    if (below) Row(verticalAlignment = Alignment.CenterVertically) { mouseActions() }
                }
            }

            // The page, fitted whole into the room left: WDP's pages are a fixed shape, drawn at whatever scale makes the
            // whole page fit, centred, with no scroll and no upper limit — a bigger window is a bigger page, laid out at
            // that size rather than stretched. On a phone two fingers zoom in (WdpZoom).
            val f = form
            // the page, and the Steps beside it where the Planner is 840 dp or wider (the page is fitted to what is left)
            Row(Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp, vertical = 2.dp)) {
                // the Attack tab's three pages, a rail beside the page: in the room the page leaves at its side, else above
                // its corner, else (only then) a rail's width taken from the page (AttackRail.place)
                val rail = if (subTabs && f != null && f.width > 0 && f.height > 0) {
                    AttackRail.place(maxWidth, maxHeight, f.width.toFloat(), f.height.toFloat(), touch)
                } else null
                when {
                    // the app's own page: a map, not a fitted form (it pans and zooms itself, so no WdpZoom)
                    current == WdpPage.MAP -> WdpMapPage(mission, Modifier.fillMaxSize())
                    // the app's own page too: WDP's ATO Target List as a page
                    current == WdpPage.ATO -> AtoTargetsPage(Modifier.fillMaxSize())
                    f == null -> Text("Loading ${current.label}…", color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                    else -> WdpFormView(
                        f, values, Modifier.fillMaxSize().padding(start = rail?.reserve ?: 0.dp), zoom = zoom, onScale = { pageScale = it },
                        onValue = onValue, onClick = onClick,
                        content = (wiring as? WdpControlContent)?.controlContent() ?: emptyMap(),
                    )
                }
                if (rail != null) AttackRailView(current, touch, Modifier.offset(rail.x, rail.y)) { page = it.name; menu = null }
                // no mission at all yet: the three card pages would be blank, so they say where to start instead
                val nothing = missionData?.briefing == null && missionData?.dtc == null && flight == null
                if (nothing && wiring === dataCard && f != null) StartHere(Modifier.align(Alignment.Center))
                // A phone held upright: the landscape page leaves a band under it, where the hint and the notices go at
                // no cost to the page (R3-UI §3.10), while the page is not zoomed.
                if (phoneUpright && f != null && !zoom.zoomed && f.width > 0) {
                    val pageH = minOf(maxHeight, maxWidth * (f.height.toFloat() / f.width))
                    val band = (maxHeight - pageH) / 2
                    if (band >= 120.dp) PhoneFooter(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = band),
                        identitySegments(mission, missionData, theaterName, dataCard.plan.selPilotSeat, wide = true), notices, info?.mission,
                    )
                    // however little room there is: the one line that says a bigger page is a turn of the phone away
                    else if (band >= 22.dp) TurnHint(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp))
                }
                // an upright tablet by finger: what the Mission section shows and the credit, in the band under the page
                if (footer) {
                    val source = info?.mission
                    Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            missionShows(source), color = missionShowsInk(source), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).plannerProbe("Shell/MissionShows"),
                        )
                        Spacer(Modifier.weight(1f))
                        Text("WDP by Falcas", color = Hud.TextFaint, fontSize = 12.sp, maxLines = 1, modifier = Modifier.plannerProbe("Shell/Credit"))
                    }
                }
            }
            if (stepsSide && PlannerSteps.open) PlannerStepsView(
                stepFacts, layout, sheet = false,
                Modifier.width(stepsWidth).fillMaxHeight().padding(end = 4.dp, top = 2.dp, bottom = 4.dp),
            )
            }
        }
        // the Steps as a sheet where the Planner is narrower than 840 dp (a tablet held upright, a phone)
        if (!stepsSide && PlannerSteps.sheet) PlannerStepsSheet(stepFacts, layout, top = actionsBottom)
        // ---- the shell's menus and the icon names, over the page
        menu?.let { m ->
            ShellMenuOverlay(
                m, layout, current, count, onPick = { page = it.name }, onClose = { menu = null }, notices = notices, fullInMenu = fullInMenu,
                split = split, top = actionsBottom,
            )
        }
        hint?.let { h ->
            Text(
                h,
                Modifier.align(Alignment.TopEnd).padding(top = actionsBottom + 2.dp, end = 10.dp).clip(RoundedCornerShape(6.dp)).background(Hud.Surface3)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = Hud.Text, fontSize = 12.sp,
            )
        }
        // the Planner's own windows over the page, and WDP's child windows and message boxes over those (a question a
        // window asks — a zeroing warning, a refusal — is one of WDP's boxes and must not be hidden under the window)
        PlannerWindowHost(open = window)
        WdpDialogHost(pageScale = pageScale)
        // a finger's editor for a small box, list or slider, over the page and over any window it was opened from
        WdpSheetHost()
        // the tooltips, over everything (they take no input)
        WdpTipHost()
    }
}

/** A toolbar action: an icon (named beside it when [named]; else named on hover and on a long press, [onHint]). */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ShellButton(
    label: String,
    icon: ImageVector,
    probe: String,
    named: Boolean = false,
    on: Boolean = false,
    tint: Color? = null,
    onHint: (String, Boolean) -> Unit,
    /** the one-word name shown under the icon where the Planner is worked by finger ([WdpTouch.device]) */
    short: String? = null,
    /** a press of it is on its way: the icon dims and the button takes no press until it is back */
    busy: Boolean = false,
    /** the Steps point at it (the step the pilot is on, or a press on its line): an amber ring ([PlannerSteps.ringed]) */
    mark: Boolean = false,
    /** the words beside the icon when [named], if not [label] (a mouse's toolbar short of room: "Open…") */
    shown: String? = null,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    // with a tooltip (WdpTips: tips/planner.json) the tip names it and says what it does, under the mouse and on a
    // finger's long press; the name under the toolbar is for a button without one, or with tips off
    val tipped = WdpTips.hasPlanner(probe)
    LaunchedEffect(hovered, tipped) { if (!named && hovered && !tipped) onHint(label, true) else onHint(label, false) }
    var pressedLong by remember { mutableStateOf(false) }
    LaunchedEffect(pressedLong) { if (pressedLong) { onHint(label, true); delay(1800); onHint(label, false); pressedLong = false } }
    // by finger: 44 dp tall and at least 48 wide, the icon named under it (a finger has no hover to name it)
    val finger = WdpTouch.device && !named && short != null
    if (finger) {
        Column(
            Modifier.padding(horizontal = 1.dp).height(44.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .background(if (on) Hud.Amber.copy(alpha = 0.16f) else Hud.Surface)
                .then(if (mark) Modifier.border(1.5.dp, Hud.Amber, RoundedCornerShape(8.dp)) else Modifier)
                .combinedClickable(enabled = !busy, onClick = { onHint(label, false); onClick() }, onLongClick = { if (!tipped) pressedLong = true })
                .alpha(if (busy) 0.5f else 1f)
                .padding(horizontal = 6.dp)
                .plannerProbe(probe),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, label, tint = tint ?: if (on) Hud.Amber else Hud.TextDim, modifier = Modifier.size(20.dp))
            Text(short ?: label, color = if (on || tint != null) (tint ?: Hud.Amber) else Hud.TextDim, style = shellType(10.5.sp), maxLines = 1, softWrap = false)
        }
        return
    }
    // with a mouse the compact toolbar's measures (MouseBar: shellFit adds them up to choose what fits)
    val m = !WdpTouch.device
    Row(
        Modifier.padding(horizontal = if (m) MouseBar.OUTER else 2.dp).height(if (m) MouseBar.HEIGHT else 44.dp).clip(RoundedCornerShape(if (m) 7.dp else 8.dp))
            .background(if (on) Hud.Amber.copy(alpha = 0.16f) else Hud.Surface)
            .then(if (mark) Modifier.border(1.5.dp, Hud.Amber, RoundedCornerShape(if (m) 7.dp else 8.dp)) else Modifier)
            .hoverable(src)
            .combinedClickable(enabled = !busy, onClick = { onHint(label, false); onClick() }, onLongClick = { if (!named && !tipped) pressedLong = true })
            .alpha(if (busy) 0.5f else 1f)
            .padding(horizontal = if (m) (if (named) MouseBar.PAD_NAMED else MouseBar.PAD_ICON) else if (named) 10.dp else 7.dp)
            .plannerProbe(probe),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = tint ?: if (on) Hud.Amber else Hud.TextDim, modifier = Modifier.size(if (m) MouseBar.ICON else 18.dp))
        if (named) {
            Spacer(Modifier.width(if (m) MouseBar.GAP else 6.dp))
            Text(shown ?: label, Modifier.capsCentred(if (m) MouseBar.FONT else 13.sp), color = Hud.Text, style = shellType(if (m) MouseBar.FONT else 13.sp), maxLines = 1, softWrap = false)
        }
    }
}

/**
 * **Save to DTC** with its count of edits not saved (R3-PLAN A2) and, beside it, the arrow that opens its menu (Re-read
 * DTC from BMS, Save to DTC and populate); on a phone the menu's items are in the ⋮ menu ([onMenu] null).
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SaveButton(count: Int, named: Boolean, onHint: (String, Boolean) -> Unit, onMenu: (() -> Unit)?, mark: Boolean = false) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val label = "Save to DTC" + (if (count > 0) " ($count not saved)" else "")
    // a tooltip (WdpTips) names it where it has one, as on the other buttons
    val tipped = WdpTips.hasPlanner("Shell/SaveToDtc")
    LaunchedEffect(hovered, label, tipped) { onHint(label, !named && hovered && !tipped) }
    // by finger: 44 dp tall, "Save" under the icon, and the menu's arrow a finger wide
    val finger = WdpTouch.device && !named
    // with a mouse the compact toolbar's measures (MouseBar), which shellFit adds up
    val m = !WdpTouch.device
    Row(
        Modifier.padding(horizontal = if (m) MouseBar.OUTER else 2.dp).height(if (m) MouseBar.HEIGHT else 44.dp).clip(RoundedCornerShape(if (m) 7.dp else 8.dp))
            .background(if (count > 0) Hud.Amber.copy(alpha = 0.16f) else Hud.Surface)
            .then(if (mark) Modifier.border(1.5.dp, Hud.Amber, RoundedCornerShape(if (m) 7.dp else 8.dp)) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.fillMaxHeight().hoverable(src).combinedClickable(onClick = { onHint(label, false); PlannerShell.save() }, onLongClick = { if (!tipped) onHint(label, true) })
                .then(if (finger) Modifier.widthIn(min = 48.dp) else Modifier)
                .padding(
                    start = if (m) (if (named) MouseBar.PAD_NAMED else MouseBar.PAD_ICON) else if (named) 10.dp else 7.dp,
                    end = if (onMenu == null) 8.dp else 4.dp,
                ).plannerProbe("Shell/SaveToDtc"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (finger) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Save, "Save to DTC", tint = if (count > 0) Hud.Amber else Hud.TextDim, modifier = Modifier.size(20.dp))
                Text("Save", color = if (count > 0) Hud.Amber else Hud.TextDim, style = shellType(10.5.sp), maxLines = 1, softWrap = false)
            } else Icon(Icons.Default.Save, "Save to DTC", tint = if (count > 0) Hud.Amber else Hud.TextDim, modifier = Modifier.size(if (m) MouseBar.ICON else 18.dp))
            if (named) {
                Spacer(Modifier.width(if (m) MouseBar.GAP else 6.dp))
                Text("Save to DTC", Modifier.capsCentred(if (m) MouseBar.FONT else 13.sp), color = Hud.Text, style = shellType(if (m) MouseBar.FONT else 13.sp), maxLines = 1, softWrap = false)
            }
            if (count > 0) {
                Spacer(Modifier.width(5.dp))
                Box(
                    Modifier.clip(CircleShape).background(Hud.Amber).padding(horizontal = 5.dp, vertical = 1.dp).plannerProbe("Shell/SaveCount"),
                    contentAlignment = Alignment.Center,
                ) { Text(if (count > 99) "99+" else count.toString(), Modifier.capsCentred(10.sp), color = Hud.Bg, style = shellType(10.sp, FontWeight.Bold), maxLines = 1) }
            }
        }
        if (onMenu != null) {
            Box(
                Modifier.fillMaxHeight().clickable { onMenu() }.then(if (finger) Modifier.widthIn(min = 32.dp) else Modifier)
                    .padding(horizontal = if (m) 2.dp else 3.dp).plannerProbe("Shell/SaveMenu"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.ArrowDropDown, "Save to DTC menu", tint = Hud.TextDim, modifier = Modifier.size(if (finger) 22.dp else if (m) MouseBar.ICON else 18.dp)) }
        }
    }
}

/**
 * Where the Attack tab's rail ([AttackRailView]) goes beside an attack page, which is fitted whole and centred in the
 * page area: in the room the page leaves at its left (a wide window, where the page is as tall as the area allows:
 * the rail costs nothing), else above the page's top-left corner (a tablet or a phone held upright, where the page is
 * as wide as the area allows and leaves a band over it: nothing either), else — only then — a rail's width is taken
 * from the page area ([Place.reserve]) and the page fitted to the rest. A row over the page took its height everywhere.
 */
internal object AttackRail {
    /** the rail's top-left corner in the page area, and the width kept from the page for it (0 when it sits in room the page leaves) */
    class Place(val x: Dp, val y: Dp, val reserve: Dp)

    fun width(finger: Boolean): Dp = if (finger) 60.dp else 48.dp
    fun item(finger: Boolean): Dp = if (finger) 56.dp else 46.dp
    fun head(finger: Boolean): Dp = if (finger) 18.dp else 16.dp
    /** the whole rail: its padding (3 dp each end), the "ATTACK" head, three buttons and the 2 dp between them */
    fun height(finger: Boolean): Dp = 6.dp + head(finger) + item(finger) * 3 + 4.dp

    /** the space between the rail and the page */
    private val GAP = 5.dp

    fun place(w: Dp, h: Dp, formW: Float, formH: Float, finger: Boolean): Place {
        val rw = width(finger)
        val rh = height(finger)
        fun fitted(room: Dp): Pair<Dp, Dp> {
            val s = minOf(room.value / formW, h.value / formH)
            return (formW * s).dp to (formH * s).dp
        }
        val lowest = (h - rh).coerceAtLeast(0.dp)
        val (pw, ph) = fitted(w)
        val side = (w - pw) / 2
        val band = (h - ph) / 2
        // beside the page, level with its top
        if (side >= rw + GAP) return Place(side - rw - GAP, band.coerceAtMost(lowest), 0.dp)
        // above its top-left corner
        if (band >= rh + GAP) return Place(side, band - rh - GAP, 0.dp)
        // a rail's width kept from the page area; the page is centred in the rest, the rail against its left edge
        val room = w - rw - GAP
        val (pw2, ph2) = fitted(room)
        return Place((room - pw2) / 2, ((h - ph2) / 2).coerceAtMost(lowest), rw + GAP)
    }
}

/**
 * The Attack tab's pages, **Pop-up**, **HADB** and **TOSS**, as a slim vertical rail beside the page ([AttackRail]
 * places it): each a button a finger's size by finger, with a small drawing of its delivery over its name, the one on
 * show lit amber and ringed. Each is its own page ([WdpPage]); the probes are `Shell/Attack` (the rail) and
 * `Shell/Attack/<page>`.
 */
@Composable
private fun AttackRailView(current: WdpPage, finger: Boolean, modifier: Modifier, onPick: (WdpPage) -> Unit) {
    Column(
        modifier.width(AttackRail.width(finger)).clip(RoundedCornerShape(9.dp)).background(Hud.Surface)
            .border(1.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(9.dp))
            .padding(3.dp).plannerProbe("Shell/Attack"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(AttackRail.head(finger) - 2.dp), contentAlignment = Alignment.Center) {
            Text("ATTACK", color = Hud.TextFaint, style = shellType(if (finger) 9.5.sp else 9.sp, FontWeight.Bold), maxLines = 1, softWrap = false)
        }
        for (p in AttackTabs.PAGES) {
            val on = p == current
            val ink = if (on) Hud.Amber else Hud.TextDim
            Box(
                Modifier.fillMaxWidth().height(AttackRail.item(finger)).clip(RoundedCornerShape(6.dp))
                    .background(if (on) Hud.Amber.copy(alpha = 0.18f) else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, Hud.Amber.copy(alpha = 0.7f), RoundedCornerShape(6.dp)) else Modifier)
                    .plannerPress { if (!on) onPick(p) }
                    .plannerProbe("Shell/Attack/${p.name}"),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AttackGlyph(p, ink, Modifier.width(if (finger) 30.dp else 26.dp).height(if (finger) 16.dp else 14.dp))
                    Spacer(Modifier.height(if (finger) 5.dp else 4.dp))
                    Text(
                        p.label, color = ink, style = shellType(if (finger) 11.5.sp else 10.5.sp),
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * The delivery each attack page plans, drawn small: Pop-up (low in, pull up, roll in and dive), HADB (high, then a
 * steep dive) and TOSS (low in, pull up, the bomb lofted on its arc, dashed).
 */
@Composable
private fun AttackGlyph(page: WdpPage, ink: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val line = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val dot = 1.9.dp.toPx()
        val path = Path()
        when (page) {
            WdpPage.POPUP -> {
                path.moveTo(0.02f * w, 0.84f * h); path.lineTo(0.32f * w, 0.84f * h)
                path.quadraticBezierTo(0.46f * w, 0.84f * h, 0.56f * w, 0.14f * h)
                path.lineTo(0.88f * w, 0.78f * h)
                drawPath(path, ink, style = line)
                drawCircle(ink, dot, androidx.compose.ui.geometry.Offset(0.94f * w, 0.9f * h))
            }
            WdpPage.HADB -> {
                path.moveTo(0.02f * w, 0.12f * h); path.lineTo(0.3f * w, 0.12f * h)
                path.quadraticBezierTo(0.42f * w, 0.12f * h, 0.48f * w, 0.26f * h)
                path.lineTo(0.8f * w, 0.8f * h)
                drawPath(path, ink, style = line)
                drawCircle(ink, dot, androidx.compose.ui.geometry.Offset(0.88f * w, 0.9f * h))
            }
            else -> {
                path.moveTo(0.02f * w, 0.86f * h); path.lineTo(0.26f * w, 0.86f * h)
                path.quadraticBezierTo(0.42f * w, 0.86f * h, 0.5f * w, 0.42f * h)
                drawPath(path, ink, style = line)
                val arc = Path()
                arc.moveTo(0.5f * w, 0.42f * h)
                arc.quadraticBezierTo(0.72f * w, -0.3f * h, 0.92f * w, 0.84f * h)
                val dash = Stroke(
                    width = 1.4.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.4.dp.toPx(), 2.2.dp.toPx())),
                )
                drawPath(arc, ink, style = dash)
                drawCircle(ink, dot, androidx.compose.ui.geometry.Offset(0.94f * w, 0.9f * h))
            }
        }
    }
}

/** A chip of the phone's row: its text, a ▾ when it opens something, an amber mark when there is a notice. */
@Composable
private fun ShellChip(text: String, probe: String, arrow: Boolean, warn: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.height(if (WdpTouch.device) 44.dp else 32.dp).clip(RoundedCornerShape(8.dp)).background(Hud.Surface).clickable(onClick = onClick)
            .padding(start = 10.dp, end = 4.dp).plannerProbe(probe),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (warn) { Icon(Icons.Default.Warning, "Notice", tint = Hud.Amber, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, color = Hud.Text, style = shellType(if (WdpTouch.device) 13.sp else 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).capsCentred(if (WdpTouch.device) 13.sp else 12.sp))
        if (arrow) Icon(Icons.Default.ArrowDropDown, null, tint = Hud.TextDim, modifier = Modifier.size(18.dp))
    }
}

/** An amber notice in row B: one line; a press shows what it means in full. */
@Composable
private fun NoticeChip(n: ShellNotice, probe: String, short: Boolean) {
    val finger = WdpTouch.device
    Row(
        Modifier.height(if (finger) 30.dp else 20.dp).then(if (finger) Modifier.widthIn(min = 36.dp) else Modifier)
            .clip(RoundedCornerShape(if (finger) 15.dp else 10.dp)).background(Hud.Amber.copy(alpha = 0.14f))
            .clickable { n.press() }.padding(horizontal = if (finger) 10.dp else 7.dp).plannerProbe(probe),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Warning, null, tint = Hud.Amber, modifier = Modifier.size(if (finger) 15.dp else 12.dp))
        if (!short) { Spacer(Modifier.width(4.dp)); Text(n.label, color = Hud.Amber, style = shellType(if (finger) 12.5.sp else 10.5.sp), maxLines = 1, modifier = Modifier.widthIn(max = 280.dp).capsCentred(if (finger) 12.5.sp else 10.5.sp), overflow = TextOverflow.Ellipsis) }
    }
}

/** The one line under the page of a phone held upright: a turn of the phone gives a bigger page, and a double tap zooms. */
@Composable
private fun TurnHint(modifier: Modifier) {
    Row(modifier.plannerProbe("Shell/TurnHint"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(Icons.Default.ScreenRotation, null, tint = Hud.TextFaint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Turn the phone for a bigger page · double-tap to zoom",
            color = Hud.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What a phone held upright shows in the band under the page: what is planned, the notices, the hint, the credit. */
@Composable
private fun PhoneFooter(modifier: Modifier, segs: List<String>, notices: List<ShellNotice>, source: com.bmscompanion.app.data.mission.MissionSourceInfo?) {
    Column(modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
        TurnHint(Modifier.fillMaxWidth())
        Text(
            if (segs.isEmpty()) "No mission yet: Open mission… (⋮) or press PRINT in BMS." else segs.joinToString("  ›  "),
            color = Hud.TextDim, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        notices.forEachIndexed { i, n -> NoticeChip(n, "Shell/Notice/$i", short = false) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // what the Mission section shows (a status: Populate from Planner is in the ⋮ menu and the Steps)
            Text(
                missionShows(source), color = missionShowsInk(source), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).plannerProbe("Shell/MissionShows"),
            )
            Box(Modifier.heightIn(min = 36.dp).plannerProbe("Shell/Credit"), contentAlignment = Alignment.Center) {
                Text("WDP by Falcas", color = Hud.TextFaint, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/**
 * "Start here", over the card pages while there is no mission at all (no briefing printed, no cartridge, no save open):
 * how to begin, in words only — Open mission… is the toolbar's and the rest of the evening the Steps' (R3-UI §3.10).
 */
@Composable
private fun StartHere(modifier: Modifier) {
    Column(
        modifier.widthIn(max = 460.dp).padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Surface.copy(alpha = 0.97f))
            .border(1.dp, Hud.Outline, RoundedCornerShape(10.dp)).padding(16.dp).plannerProbe("Shell/StartHere"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Start here", color = Hud.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("1  In Falcon BMS: open your campaign or TE, pick your flight, SAVE the DTC and the mission, PRINT.", color = Hud.TextDim, fontSize = 13.sp)
        Text("2  Here: Open mission… on the toolbar (on a phone, in ⋮), then pick your flight and seat.", color = Hud.TextDim, fontSize = 13.sp)
        // no buttons of its own: the toolbar has them, and the Steps say the rest (one place per action)
        Text("The toolbar's Steps lists the whole evening, from BMS to the cockpit.", color = Hud.TextFaint, fontSize = 12.5.sp)
    }
}

/**
 * The shell's menus: Save to DTC's (Re-read, Save to DTC and populate), Options (WDP's: Settings…, About WDP), the ⋮
 * menu of a narrow toolbar (only the actions that do not fit on it, and on a phone Save to DTC's menu items and the
 * Options, under their name: never a second copy of a button the row shows), and the phone's page list. Drawn in the
 * Planner's box under row A; a press outside closes it.
 */
@Composable
private fun ShellMenuOverlay(
    menu: ShellMenu,
    layout: ShellLayout,
    current: WdpPage,
    count: Int,
    onPick: (WdpPage) -> Unit,
    onClose: () -> Unit,
    notices: List<ShellNotice>,
    fullInMenu: Boolean = false,
    /** a tablet held upright by finger: the actions are on row B, and Options is in the ⋮ menu */
    split: Boolean = false,
    /** where the toolbar ends: the menus open under it */
    top: Dp = 40.dp,
) {
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }) {
        val items = ArrayList<Triple<String, String, (() -> Unit)?>>()
        fun item(label: String, probe: String, run: () -> Unit) { items += Triple(label, probe, run) }
        // a group's name in a menu (the ⋮ menu's Options), not a press
        fun header(label: String, probe: String) { items += Triple(label, probe, null) }
        when (menu) {
            // the tabs' order; the Attack tab is its name over its three pages, each one press away
            ShellMenu.PAGES -> for (t in ShellTab.ALL) {
                if (t.pages.size > 1) {
                    header(t.label, "Shell/PagePicker/${t.key}")
                    for (p in t.pages) item((if (p == current) "● " else "   ") + p.label, "Shell/PagePicker/${p.name}") { onPick(p) }
                } else t.pages[0].let { p -> item((if (p == current) "● " else "   ") + p.label, "Shell/PagePicker/${p.name}") { onPick(p) } }
            }
            ShellMenu.SAVE -> {
                item("Re-read DTC from BMS", "Shell/SaveMenu/Reread") { PlannerShell.reread() }
                item(PlannerPopulate.SAVE_AND_POPULATE, "Shell/SaveMenu/SaveAndPopulate") { PlannerShell.populate(saveFirst = true) }
            }
            ShellMenu.MORE -> {
                if (layout == ShellLayout.COMPACT) item("Open mission…", "Shell/More/OpenMission") { PlannerShell.openMission() }
                if (layout == ShellLayout.COMPACT) item(MissionMode.POPULATE, "Shell/More/Populate") { PlannerShell.populate() }
                item("Upd Kneeboard", "Shell/More/Print") { PlannerShell.print() }
                item("Guide", "Shell/More/Guide") { PlannerShell.guide() }
                if (layout == ShellLayout.COMPACT) {
                    // Save to DTC itself is on the phone's row; its menu's items are here (the row has no arrow for it)
                    item("Re-read DTC from BMS", "Shell/More/Reread") { PlannerShell.reread() }
                    item(PlannerPopulate.SAVE_AND_POPULATE, "Shell/More/SaveAndPopulate") { PlannerShell.populate(saveFirst = true) }
                    notices.forEachIndexed { i, n -> item("⚠ " + n.label, "Shell/More/Notice/$i") { n.press() } }
                    // a phone upright has no room for the button beside the others
                    if (fullInMenu) item(if (WdpFocus.on) "Leave full window" else "Full window", "Shell/More/FullWindow") { PlannerShell.fullWindow() }
                    // the toolbar's Options menu, which a phone's row has no room for
                    header("Options", "Shell/More/Options")
                    for ((label, key, run) in PlannerShell.OPTIONS) item(label, "Shell/More/$key", run)
                } else {
                    item(if (WdpFocus.on) "Leave full window" else "Full window", "Shell/More/FullWindow") { PlannerShell.fullWindow() }
                    // the Options button has no room on an upright tablet's row B: its items are here
                    if (split) {
                        header("Options", "Shell/More/Options")
                        for ((label, key, run) in PlannerShell.OPTIONS) item(label, "Shell/More/$key", run)
                    }
                }
            }
            ShellMenu.OPTIONS -> for ((label, key, run) in PlannerShell.OPTIONS) item(label, "Shell/Options/$key", run)
        }
        val finger = WdpTouch.device
        Column(
            Modifier.align(if (menu == ShellMenu.PAGES) Alignment.TopStart else Alignment.TopEnd)
                .padding(top = top, start = 8.dp, end = 8.dp).widthIn(min = if (finger) 240.dp else 200.dp, max = 340.dp)
                .clip(RoundedCornerShape(8.dp)).background(Hud.Surface2).border(1.dp, Hud.Outline, RoundedCornerShape(8.dp))
                // one of the app's own lists: on a phone held sideways a long menu scrolls rather than running off
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp).plannerProbe("Shell/Menu/${menu.name}"),
        ) {
            for ((label, probe, run) in items) {
                if (run == null) Text(
                    label.uppercase(),
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp).plannerProbe(probe),
                    color = Hud.TextFaint, fontSize = if (finger) 11.5.sp else 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                ) else Text(
                    label,
                    Modifier.fillMaxWidth().clickable { onClose(); run() }.padding(horizontal = 14.dp, vertical = if (finger) 11.dp else 8.dp).plannerProbe(probe),
                    color = Hud.Text, fontSize = if (finger) 14.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
