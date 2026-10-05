package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.MergedMission
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PlanItemSource
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.Tag
import com.bmscompanion.app.ui.theme.Hud
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// What the Mission section marks as the plan's (R3-IMPORT §3.5): one mark for what came from a plan, one for what the
// jet does not have yet, and the Plan card (the cartridge's settings). Since 1.3.8 nothing is laid over a printed
// briefing any more — Send to Mission and its banner are gone, and WDP mode's snapshot marks nothing as the plan's — so
// the marks only appear where a PC still serves a plan; the Plan card shows the cartridge's settings as "DTC settings".

/**
 * The Planner's own colour: WDP's pull-up magenta, which the attack is already drawn in on the map. Everything the
 * plan put on a page is marked in it.
 */
val PlanInk: Color get() = if (Hud.onLightMap || Hud.paperInks) Color(0xFFB0209A) else Color(0xFFFF5CE1)

/** The mission every Mission view draws: the briefing with BMS's route and the sent plan merged in (PlanMerge). */
@Composable
fun rememberMerged(): MergedMission {
    val mission by MissionLink.mission.collectAsState()
    // only the jet's navigation points matter to the merge; read through derivedStateOf so the 4 Hz live data does not
    // recompose the caller for nothing (the live value is read inside, only when the navpoints or the mission change)
    val liveState = MissionLink.live.collectAsState()
    val nav by remember { androidx.compose.runtime.derivedStateOf { liveState.value?.navPoints } }
    return remember(mission, nav) { PlanMerge.merge(mission, liveState.value) }
}

/**
 * The marks: PLAN for an item the plan changed or added, NOT IN JET beside it when the jet does not have it yet. On a
 * paper kneeboard the tag is a superscript-like P / P* (the sheet's legend explains it).
 */
@Composable
fun PlanTags(fromPlan: Boolean, notInJet: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (!fromPlan && !notInJet) return
    if (Hud.onPaper) {
        Text(if (notInJet) "P*" else "P", modifier, color = Hud.Text, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        return
    }
    // in a row: one tag — NOT IN JET is always the plan's, so it says both
    if (compact) {
        if (notInJet) Tag("NOT IN JET", PlanInk, modifier = modifier) else Tag("PLAN", PlanInk, filled = true, modifier = modifier)
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (fromPlan) Tag("PLAN", PlanInk, filled = true)
        if (notInJet) Tag("NOT IN JET", PlanInk)
    }
}

/** A cleared slot: the plan wiped it, the jet still has it until the pilot saves. */
@Composable
fun ClearedTag(modifier: Modifier = Modifier) {
    if (Hud.onPaper) { Text("×", modifier, color = Hud.TextDim, fontSize = 10.sp); return }
    Tag("CLEARED", Hud.TextDim, modifier = modifier)
}

/** What a source is called where a pilot reads it. */
fun sourceText(s: PlanItemSource, notInJet: Boolean = false): String = when (s) {
    PlanItemSource.PLAN -> if (notInJet) "the Planner's plan — not in the jet yet" else "the Planner's plan"
    PlanItemSource.JET -> if (notInJet) "the jet (the plan has it elsewhere)" else "the jet"
    PlanItemSource.CARTRIDGE -> "your DTC (cartridge)"
    PlanItemSource.ROUTE -> "BMS's mission file"
    PlanItemSource.SAVE -> "the save's flight plan"
    PlanItemSource.BRIEFING -> "the briefing"
}

/** "25 Sep 22:40", in the device's own time. */
fun planClock(ms: Long): String = if (ms <= 0) "" else SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(ms))

/**
 * The Plan card: what the cartridge carries besides points — laser codes, bingo, ALOW and the MSL floor, the six EWS
 * programs, the presets tuned at load, and the TACAN and ILS WDP writes. The plan's values win and are marked; a key
 * only WDP writes carries the note that BMS 4.38.1 reading it is not tested.
 */
@Composable
fun PlanCard(merged: MergedMission) {
    val rows = merged.settings
    if (rows.isEmpty()) return
    val any = rows.any { it.source == PlanItemSource.PLAN }
    SectionCard(if (any) "Plan" else "DTC settings", accent = if (any) PlanInk else Hud.Cyan, trailing = { if (any) PlanTags(true, rows.any { it.notInJet }) }) {
        rows.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.label, Modifier.width(118.dp), fontSize = 13.sp, color = Hud.TextDim)
                Column(Modifier.weight(1f)) {
                    Text(s.value, fontSize = 13.sp, color = Hud.Text)
                    s.was?.let { Text("cartridge: $it", fontSize = 11.sp, color = Hud.TextFaint) }
                }
                PlanTags(s.source == PlanItemSource.PLAN, s.notInJet, compact = true)
            }
        }
        if (rows.any { it.untested }) Text(
            "Laser, bingo, ALOW, MSL floor, TACAN and ILS are keys WDP writes: BMS 4.38.1 reading them is not tested.",
            fontSize = 11.sp, color = Hud.TextFaint, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
