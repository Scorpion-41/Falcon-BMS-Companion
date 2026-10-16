package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.EzRun
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.Noteboards
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.screens.wdp.PlannerWindow
import com.bmscompanion.app.ui.screens.wdp.PlannerWindows
import com.bmscompanion.app.ui.screens.wdp.plannerProbe
import com.bmscompanion.app.ui.theme.Hud

/**
 * When Upd Kneeboard may open from the Kneeboards page (1.4.1). In WDP mode at once, as in the Planner. In EZBoards
 * mode once EZBoards has made its pages for the briefing printed now — its last run started at or after that PRINT and
 * finished — so the pages printed here land after EZBoards' and its next PRINT is the only thing that replaces them;
 * at once too when EZBoards is not set up in BMS Companion (nothing to wait for).
 */
object UpdKneeboardGate {
    /** Why Upd Kneeboard waits, or null when it can open. [local] is this device's own last GENERATE NOW, when newer. */
    fun reason(info: BridgeInfo?, linked: Boolean, local: EzRun? = null): String? {
        if (!linked || info == null) return "Connect this device to BMS Companion on the BMS PC first."
        if (info.wdpMode) return null
        val ez = info.ezBoards
        if (!ez.configured) return null
        if (ez.running) return "EZBoards is making its pages now: Upd Kneeboard opens when it is done."
        val printed = info.briefing.available
        val last = listOfNotNull(local, ez.lastRun).maxByOrNull { it.time }
        val after = "Available after EZBoards has made its pages for this briefing"
        return when {
            !printed -> "$after: press PRINT on the BMS briefing screen" + if (ez.autoOnPrint) "." else ", then GENERATE NOW."
            last == null || last.time < info.briefing.modified ->
                if (ez.autoOnPrint) "$after: they are made the moment the PRINT is seen." else "$after: press GENERATE NOW (PRINT does not run it while that is off)."
            !last.ok -> "EZBoards' last run did not finish: press GENERATE NOW, then Upd Kneeboard."
            else -> null
        }
    }
}

/**
 * The Kneeboards page's way to the cockpit pages of the app's own (1.4.1): **Upd Kneeboard** — the Planner's window,
 * in either mode, greyed with its reason in EZBoards mode until EZBoards has made its pages ([UpdKneeboardGate]) — and
 * **Edit notes**, the Noteboard editor on its own, for a pilot who reads the notes on the VR Notes board and prints
 * nothing. Both open over this page ([MissionBoardsPane] hosts the windows).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdKneeboardCard() {
    val info by MissionLink.info.collectAsState()
    val link by MissionLink.state.collectAsState()
    val ez by MissionLink.ez.collectAsState()
    val linked = link is LinkState.Online
    val wdp = info.wdpMode
    val why = UpdKneeboardGate.reason(info, linked, ez.result)
    SectionCard("Your pages on the cockpit kneeboard", accent = Hud.Cyan) {
        Text(
            if (wdp) "Upd Kneeboard puts the Planner's DataCard, your Noteboard and the pages you choose on BMS's cockpit kneeboard, as in the Planner."
            else "Upd Kneeboard adds pages of the app's own beside EZBoards': your Noteboard (notes and laser codes), target pictures, " +
                "the route map, ground charts and more. Page 1 stays EZBoards' unless you choose otherwise.",
            style = MaterialTheme.typography.bodySmall, color = Hud.TextDim,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("UPD KNEEBOARD", enabled = why == null, accent = Hud.Amber, probe = "Kneeboards/UpdKneeboard") {
                // the window lays the Mission set for the mode in force, and shows each page as it will be printed
                PlannerWindows.show(PlannerWindow.PRINT)
            }
            BigButton("EDIT NOTES", enabled = linked, accent = Hud.Cyan, probe = "Kneeboards/EditNotes") {
                PlannerWindows.show(PlannerWindow.NOTES, "0")
            }
        }
        if (why != null) Text(why, color = Hud.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).plannerProbe("Kneeboards/UpdWhy"))
        Noteboards.state?.mission?.let {
            Text("Notes kept for $it, on the BMS PC: the VR Notes board shows them too.", color = Hud.TextFaint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun BigButton(label: String, enabled: Boolean, accent: androidx.compose.ui.graphics.Color, probe: String, onClick: () -> Unit) {
    Box(
        Modifier.widthIn(min = 150.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Hud.Surface3 else Hud.Surface2)
            .border(1.dp, if (enabled) accent.copy(alpha = 0.7f) else Hud.Outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .plannerProbe(probe),
    ) {
        Column {
            Text(label, color = if (enabled) accent else Hud.TextFaint.copy(alpha = 0.7f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
