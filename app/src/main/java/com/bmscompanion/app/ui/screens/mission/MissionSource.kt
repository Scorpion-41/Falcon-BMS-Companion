package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.mission.MissionSourceInfo
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.screens.wdp.PlannerPopulate
import com.bmscompanion.app.ui.screens.wdp.PlannerShell
import com.bmscompanion.app.ui.screens.wdp.WdpFocus
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// The Mission section's two modes, as every screen shows them (1.3.8): EZBoards mode, the BMS briefing filled when the
// pilot presses PRINT, and WDP mode, the Planner, filled when he presses Populate from Planner. The mode lives on the PC
// (BridgeInfo.mission; MissionMode has every sentence), so a phone, a tablet, a browser, the PC window and the VR
// boards always agree. Here: the switch and the line under it, the same switch in Setup, the Planner's locked card in
// EZBoards mode, the one empty state of WDP mode before the first Populate, and the Populate button itself.

/** The ink of each mode, on the switch and wherever a mode is named: amber for EZBoards, cyan for WDP. */
fun modeInk(mode: String): Color = if (mode == MissionMode.WDP) Hud.Cyan else Hud.Amber

/** The Mission section is in WDP mode, as the PC last said; false offline and on a PC from before the modes. */
val BridgeInfo?.wdpMode: Boolean get() = this?.mission?.wdp == true

/**
 * A switch of mode on its way to the PC, and what it answered when it refused. Held here rather than in the button,
 * so the switch in the Mission section and the one in Setup show the same, and a press outlives the screen it was
 * made on.
 */
object ModeSwitching {
    /** the mode a press is switching to, or null */
    var toward: String? by mutableStateOf(null)
        private set
    /** the PC's sentence when it did not switch (an older PC answers [com.bmscompanion.app.data.mission.PcAnswer.OLD_PC]) */
    var error: String? by mutableStateOf(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Switches every device to [mode]. Instant and asks nothing; the PC clears every leftover of the Planner's still in
     * the cartridge (and the other mode's cockpit pages, and a snapshot of another flight) **silently**: no device shows
     * a notice of it (the pilot asked for none; the PC's summary, `MissionSourceInfo.reset`, is not drawn). Into
     * EZBoards mode the Mission section goes to its Briefing tab ([MissionEpoch], [MissionTabRequest]).
     */
    fun to(mode: String) {
        if (toward != null) return
        toward = mode
        error = null
        scope.launch {
            try {
                val planner = runCatching { com.bmscompanion.app.ui.screens.wdp.PlannerLeftovers.openFlight() }.getOrNull()
                val a = runCatching { MissionLink.setMissionMode(mode, planner) }.getOrNull()
                error = when {
                    a == null -> "Could not reach the PC."
                    a.error != null -> a.error
                    else -> null
                }
                // the Planner fills the whole window only in WDP mode; leaving it must give the Mission header back
                if (mode != MissionMode.WDP) WdpFocus.on = false
                // the cartridge may have changed under the Planner's DTC page: it reads the file again (asking first)
                if (a?.value?.reset?.keys?.isNotEmpty() == true) runCatching { com.bmscompanion.app.ui.screens.wdp.WdpCartridge.reload?.invoke() }
            } finally {
                toward = null
            }
        }
    }

}

/**
 * The switch: **EZBoards | WDP**, the mode on show filled in its ink. A press on the other half switches every device
 * at once. [large]: Setup's, a finger's height.
 */
@Composable
fun ModeSwitch(source: MissionSourceInfo?, enabled: Boolean, modifier: Modifier = Modifier, large: Boolean = false) {
    val mode = source?.mode ?: MissionMode.EZBOARDS
    val toward = ModeSwitching.toward
    val shape = RoundedCornerShape(if (large) 12.dp else 10.dp)
    Row(
        modifier.height(if (large) 44.dp else 34.dp).clip(shape).background(Hud.Surface).border(1.dp, Hud.Outline, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(MissionMode.EZBOARDS to MissionMode.EZBOARDS_LABEL, MissionMode.WDP to MissionMode.WDP_LABEL).forEachIndexed { i, (m, label) ->
            val on = m == mode
            val ink = modeInk(m)
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline))
            Row(
                Modifier.fillMaxHeight()
                    .background(if (on) ink.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable(enabled = enabled && !on && toward == null) { ModeSwitching.to(m) }
                    .padding(horizontal = if (large) 20.dp else 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (toward == m) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = ink, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(6.dp))
                } else if (on) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(ink))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label, color = if (on) ink else if (enabled) Hud.TextDim else Hud.TextFaint,
                    fontSize = if (large) 15.sp else 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1,
                )
            }
        }
    }
}

/** The line under the switch: the PC's own words, or the same said here when an older PC sends none. */
fun sourceLine(info: BridgeInfo?): String {
    val src = info?.mission ?: MissionSourceInfo()
    src.line?.takeIf { it.isNotBlank() }?.let { return it }
    return if (src.wdp) "From the Planner · " + if (src.populated == null) "not populated yet" else "populated"
    else "From BMS briefing · " + if (info?.briefing?.available == true) "printed" + (info.briefing.generated?.let { " $it" } ?: "") else "not printed yet"
}

/**
 * The top of the Mission section: the switch, and under it (beside it where there is room) where everything below
 * comes from — "From BMS briefing · printed 22:40", "From the Planner · populated 22:51 · Auto Save.cam · Cyborg6".
 * What changed on disk after the last Populate is not shown (the pilot asked not to be told; `Populated.changed` is
 * still served); nothing is ever taken again by itself. Hidden while this device is not linked: the mode lives on the PC.
 */
@Composable
fun MissionSourceBar(onOpenTab: (MissionTab) -> Unit, modifier: Modifier = Modifier) {
    val info by MissionLink.info.collectAsState()
    val link by MissionLink.state.collectAsState()
    val i = info ?: return
    if (link !is LinkState.Online) return
    val src = i.mission
    val error = ModeSwitching.error
    // this device's own refusal, while nothing has been populated since (by it or by another device)
    val failed = PlannerPopulate.current(src)?.takeIf { !it.ok && src.wdp }
    Column(modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 2.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeSwitch(src, enabled = true)
            Spacer(Modifier.width(10.dp))
            Text(
                sourceLine(i), Modifier.weight(1f, fill = false), color = if (src.wdp && src.populated == null) Hud.Amber else Hud.TextDim,
                fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        (error ?: failed?.let { PlannerPopulate.resultLine(it) })?.let {
            Text(it, Modifier.padding(top = 3.dp), color = Hud.Red, fontSize = 11.5.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // what a switch or a new mission cleared is never shown: it happens silently (the pilot asked for no notice)
    }
}

/**
 * **Populate from Planner** as a button, wherever the Mission section offers it (the Kneeboards page, the empty
 * state). It does what the Planner's own button does, from outside the Planner:
 *
 * - with a save's flight open in the Planner (or, on a device where it is not, the flight populated last), it
 *   populates at once and says what it did under the button;
 * - with edits in the Planner not saved to the DTC, it opens the Planner, which asks there ("Save to DTC and
 *   populate", "Populate without them", "Cancel") — the edits, the save and its questions are the Planner's;
 * - with no flight at all, it opens the Planner at Open mission….
 */
@Composable
fun PopulateButton(onOpenTab: (MissionTab) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, label: String = MissionMode.POPULATE, showResult: Boolean = !compact) {
    val info by MissionLink.info.collectAsState()
    val link by MissionLink.state.collectAsState()
    val t = PlannerPopulate.target(info?.mission)
    val busy = PlannerPopulate.busy
    val enabled = link is LinkState.Online && info.wdpMode && !busy
    val press: () -> Unit = {
        when {
            t == null -> { onOpenTab(MissionTab.PLANNER); PlannerShell.openMission() }
            PlannerPopulate.unsaved() > 0 -> { onOpenTab(MissionTab.PLANNER); PlannerShell.populate() }
            else -> PlannerPopulate.launch(t)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val shape = RoundedCornerShape(if (compact) 8.dp else 12.dp)
        Row(
            Modifier.heightIn(min = if (compact) 32.dp else 44.dp).clip(shape)
                .background(if (enabled) Hud.Cyan.copy(alpha = if (compact) 0.16f else 0.20f) else Hud.Surface2)
                .border(1.dp, if (enabled) Hud.Cyan.copy(alpha = 0.7f) else Hud.Outline.copy(alpha = 0.6f), shape)
                .clickable(enabled = enabled, onClick = press)
                .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(if (compact) 13.dp else 16.dp), color = Hud.Cyan, strokeWidth = 2.dp)
            else Icon(Icons.Default.Publish, null, tint = if (enabled) Hud.Cyan else Hud.TextFaint, modifier = Modifier.size(if (compact) 15.dp else 18.dp))
            Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
            Column {
                Text(
                    if (busy) "Populating…" else label,
                    color = if (enabled || busy) Hud.Cyan else Hud.TextFaint, fontWeight = FontWeight.Bold, fontSize = if (compact) 12.5.sp else 14.sp, maxLines = 1,
                )
                // which flight a press takes, so the button never surprises: the open flight, the last one again, or
                // none yet (then the press opens the Planner at Open mission…)
                if (!compact) Text(
                    when {
                        t == null -> "Open your flight in the Planner first"
                        t.again -> "again: " + t.label
                        else -> t.label
                    },
                    color = Hud.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showResult) PlannerPopulate.current(info?.mission)?.let { r ->
            Text(
                PlannerPopulate.resultLine(r), color = if (r.ok) Hud.Green else Hud.Red, fontSize = 12.sp, lineHeight = 15.sp,
                maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * WDP mode before the first Populate from Planner: the one thing every Mission view shows (Dashboard, Map, Taxi,
 * Briefing, Comms, AWACS), so nothing of the printed briefing can be mistaken for the Planner's flight. What to do,
 * the button, the Planner, and the way back to EZBoards mode.
 */
@Composable
fun NotPopulatedPane(onOpenTab: (MissionTab) -> Unit) {
    val info by MissionLink.info.collectAsState()
    // a Dashboard that had the whole window keeps no button to give it back while this stands in for it
    LaunchedEffect(Unit) { if (!com.bmscompanion.app.ui.Kneeboard.on) DashFocus.on = false }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .then(if (Hud.onPaper) Modifier else Modifier.clip(RoundedCornerShape(16.dp)).background(Hud.Surface).border(1.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(16.dp)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ModeTag(MissionMode.WDP)
            Text("Not populated yet", style = MaterialTheme.typography.titleMedium, color = Hud.Text)
            Text(
                "In WDP mode the Mission section shows the flight you open in the Planner, with your saved cartridge, once " +
                    "you press Populate from Planner. Nothing comes in by itself.",
                color = Hud.TextDim, fontSize = 13.sp, lineHeight = 18.sp,
            )
            if (!Hud.onPaper) {
                NumberedLine("1", "In the Planner, Open mission… and pick your flight and seat.")
                NumberedLine("2", "Plan, then Save to DTC.")
                NumberedLine("3", "Populate from Planner: every device shows it.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    PopulateButton(onOpenTab)
                    if (PlannerPopulate.target(info?.mission) != null) PlainButton("Open the Planner") { onOpenTab(MissionTab.PLANNER) }
                }
                Column {
                    Text("Want BMS's printed briefing instead?", color = Hud.TextFaint, fontSize = 12.sp)
                    Text(
                        "Switch to EZBoards mode", Modifier.clip(RoundedCornerShape(4.dp)).clickable { ModeSwitching.to(MissionMode.EZBOARDS) }.padding(vertical = 4.dp),
                        color = modeInk(MissionMode.EZBOARDS), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                // a VR board has no pointer: it says where to press, on the PC or a tablet
                Text("Press Populate from Planner in BMS Companion (the Planner or Mission → Kneeboards) and this page fills itself in.", color = Hud.Text, fontSize = 13.sp)
            }
        }
    }
}

/**
 * The Planner in EZBoards mode: greyed out on the tab strip, and this card in its place. It says in one line what WDP
 * mode changes, and switches there with one press (every device follows).
 */
@Composable
fun PlannerLockedPane() {
    val info by MissionLink.info.collectAsState()
    val link by MissionLink.state.collectAsState()
    // the Planner may have had the whole window when the mode changed under it (another device switched)
    LaunchedEffect(Unit) { WdpFocus.on = false }
    val linked = link is LinkState.Online && info != null
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(16.dp)).background(Hud.Surface).border(1.dp, Hud.Outline, RoundedCornerShape(16.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Hud.Cyan.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lock, null, tint = Hud.Cyan, modifier = Modifier.size(22.dp))
            }
            Text("The Planner works in WDP mode", color = Hud.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(MissionMode.WDP_CHANGES, color = Hud.TextDim, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
            Text(
                "You are in EZBoards mode: the Mission section comes from BMS's printed briefing. Switching asks nothing, " +
                    "clears the Planner's leftovers from your cartridge, and you can switch back at any time.",
                color = Hud.TextFaint, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
            )
            val toward = ModeSwitching.toward
            Row(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(if (linked) Hud.Cyan else Hud.Surface3)
                    .clickable(enabled = linked && toward == null) { ModeSwitching.to(MissionMode.WDP) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (toward != null) { CircularProgressIndicator(Modifier.size(15.dp), color = Hud.Bg, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text("Switch to WDP mode", color = if (linked) Hud.Bg else Hud.TextFaint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            if (!linked) Text("Connect to BMS Companion on the PC first: the mode is kept there, for every device.", color = Hud.Amber, fontSize = 12.sp, textAlign = TextAlign.Center)
            ModeSwitching.error?.let { Text(it, color = Hud.Red, fontSize = 12.sp, textAlign = TextAlign.Center) }
        }
    }
}

/**
 * Setup's card: the same switch, what each mode fills the Mission section from, and where it comes from now. On
 * every Setup page (the PC's, a phone's or tablet's, a browser's), because it is a choice made once rather than per
 * mission.
 */
@Composable
fun MissionSourceCard() {
    val info by MissionLink.info.collectAsState()
    val link by MissionLink.state.collectAsState()
    val linked = link is LinkState.Online && info != null
    val src = info?.mission
    SectionCard("Mission source", accent = Hud.Cyan) {
        Text(
            "Where the Mission section's data comes from, on every device at once.",
            color = Hud.TextDim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        ModeSwitch(src, enabled = linked, large = true)
        Spacer(Modifier.height(10.dp))
        val mode = src?.mode ?: MissionMode.EZBOARDS
        ModeMeaning(MissionMode.EZBOARDS, MissionMode.EZBOARDS_NAME, MissionMode.EZBOARDS_SAYS, mode == MissionMode.EZBOARDS)
        ModeMeaning(MissionMode.WDP, MissionMode.WDP_NAME, MissionMode.WDP_SAYS, mode == MissionMode.WDP)
        Spacer(Modifier.height(8.dp))
        if (linked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(modeInk(mode)))
                Spacer(Modifier.width(8.dp))
                Text(sourceLine(info), color = Hud.Text, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text("Connect to BMS Companion on the PC to choose: the mode is kept there.", color = Hud.Amber, fontSize = 12.sp)
        }
        ModeSwitching.error?.let { Text(it, color = Hud.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(
            "Switching is instant and asks nothing. It clears every leftover of the Planner's — the lines, PPTs, " +
                "steerpoints and nav offsets it saved that your cartridge still holds — the other mode's cockpit " +
                "kneeboard pages from an earlier flight (BMS's own put back) and a Populate of another flight. What BMS " +
                "or you changed since the Planner saved it is never touched. In WDP mode EZBoards' own kneeboards are " +
                "paused, and your EZBoards setting is kept.",
            color = Hud.TextFaint, fontSize = 11.5.sp, lineHeight = 15.sp,
        )
    }
}

@Composable
private fun ModeMeaning(mode: String, name: String, says: String, on: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            name, Modifier.width(118.dp), color = if (on) modeInk(mode) else Hud.TextDim,
            fontSize = 12.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
        )
        Text(says.replaceFirstChar { it.uppercase() }, color = if (on) Hud.Text else Hud.TextDim, fontSize = 12.5.sp)
    }
}

/** "WDP MODE" / "EZBOARDS MODE" in the mode's ink. */
@Composable
fun ModeTag(mode: String) {
    com.bmscompanion.app.ui.components.Tag(if (mode == MissionMode.WDP) "WDP MODE" else "EZBOARDS MODE", modeInk(mode), filled = true)
}

@Composable
private fun NumberedLine(n: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Hud.Cyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(n, color = Hud.Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.padding(top = 2.dp), color = Hud.Text, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun PlainButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(Hud.Surface3).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Hud.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1) }
}

/**
 * What an empty card says is missing. In EZBoards mode that is PRINT's job ([ez]); in WDP mode, after a Populate, it
 * is simply not in the flight the Planner populated ([wdp]) — telling a pilot in WDP mode to press PRINT would mix the
 * two sources. Read where the mission is already collected, so a change of mode redraws it.
 */
fun fillHint(ez: String, wdp: String = "Not in the flight populated from the Planner."): String =
    if (MissionLink.mission.value?.mode == MissionMode.WDP) wdp else ez

/**
 * WDP mode's empty WEATHER card: Populate brings the save's own weather file (`<save>.twx`), so an empty card says why
 * that file gave none (the PC's note: not there, another save's, not readable) — or, for a snapshot taken before
 * Populate carried the weather, that Populate again brings it.
 */
fun wdpNoWeather(p: com.bmscompanion.app.data.mission.Populated? = MissionLink.mission.value?.populated): String =
    p?.notes?.firstOrNull { it.startsWith(MissionMode.NO_WEATHER) }?.removePrefix(MissionMode.NO_WEATHER)
        ?.let { "No weather from the save: $it Save the weather in BMS (SAVE WTH in a TE, the save in a campaign), then Populate from Planner." }
        ?: "The save's weather file (.twx) comes with Populate from Planner."

