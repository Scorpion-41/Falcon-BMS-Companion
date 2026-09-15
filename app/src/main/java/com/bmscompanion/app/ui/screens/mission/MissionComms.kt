package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.CommEntry
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.Preset
import com.bmscompanion.app.data.mission.RawSection
import com.bmscompanion.app.ui.components.CollapsibleCard
import com.bmscompanion.app.ui.components.Masonry
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra

@Composable
fun MissionCommsPane(env: MissionEnv) {
    val mission by MissionLink.mission.collectAsState()
    val live by MissionLink.live.collectAsState()
    val b = mission?.briefing
    val dtc = mission?.dtc
    if (b == null && !dtc.hasData()) {
        PaneEmpty("No comm plan yet", "Press PRINT on the BMS Briefing screen, and Save your DTC, to see the comm ladder and presets here.")
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Masonry(minColumn = 440.dp, maxColumns = 2) {
            if (b != null && b.comms.isNotEmpty()) LadderCard(b.comms, live?.uhfFreq ?: 0)
            SupportCard(rememberSupportAssets(env))
            if (dtc != null && (dtc.uhf.isNotEmpty() || dtc.vhf.isNotEmpty())) PresetCard(dtc.uhf, dtc.vhf, live?.uhfPreset ?: 0)
            if (dtc != null && dtc.iff.isNotEmpty()) SectionCard("IFF (DTC)", accent = Hud.Green) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Mode1 Code" to "M1", "Mode2 Code" to "M2", "Mode3A Code" to "M3", "Mode4 Key" to "M4 key").forEach { (k, label) ->
                        dtc.iff[k]?.let { v ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).padding(8.dp)) {
                                Text(label, fontSize = 10.sp, color = Hud.TextDim)
                                Text(v, style = LocalExtra.current.mono, color = Hud.Green)
                            }
                        }
                    }
                }
            }
            b?.sections?.firstOrNull { it.title == "Iff" }?.let { RawCard("IFF plan", it, initiallyOpen = dtc?.iff.isNullOrEmpty()) }
            b?.sections?.firstOrNull { it.title == "Link 16" }?.let { RawCard("Link 16", it) }
        }
    }
}

@Composable
fun LadderCard(comms: List<CommEntry>, tunedUhf: Int) {
    val tuned = if (tunedUhf > 0) String.format(java.util.Locale.US, "%.3f", tunedUhf / 1000.0) else null
    SectionCard("Comm ladder", accent = Hud.Amber, trailing = { tuned?.let { Text("UHF tuned $it", style = LocalExtra.current.monoSmall, color = Hud.Green) } }) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text("AGENCY", Modifier.weight(1f), fontSize = 10.sp, color = Hud.TextFaint)
            Text("UHF", Modifier.width(104.dp), fontSize = 10.sp, color = Hud.TextFaint)
            Text("VHF", Modifier.width(96.dp), fontSize = 10.sp, color = Hud.TextFaint)
        }
        var lastGroup: String? = null
        comms.forEach { c ->
            if (lastGroup != null && c.group != lastGroup) Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Hud.Outline.copy(alpha = 0.5f)))
            lastGroup = c.group
            val isTuned = tuned != null && c.uhf == tuned
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (isTuned) Hud.Green.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent).padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.agency, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Hud.Text, maxLines = 1)
                    Text(listOfNotNull(c.callsign, c.notes).joinToString(" · "), fontSize = 11.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Freq(c.uhf, c.uhfCh, Modifier.width(104.dp))
                Freq(c.vhf, c.vhfCh, Modifier.width(96.dp))
            }
        }
    }
}

@Composable
private fun Freq(f: String?, ch: Int?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(f ?: "—", style = LocalExtra.current.monoSmall.copy(fontSize = 13.sp), color = if (f != null) Hud.Amber else Hud.TextFaint)
        if (ch != null) Text(" $ch", style = LocalExtra.current.monoSmall, color = Hud.Cyan)
    }
}

@Composable
fun PresetCard(uhf: List<Preset>, vhf: List<Preset>, activePreset: Int) {
    SectionCard("DTC presets", accent = Hud.Cyan) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("UHF", style = LocalExtra.current.overline, color = Hud.Amber)
                uhf.forEach { PresetRow(it, it.ch == activePreset) }
            }
            if (vhf.isNotEmpty()) Column(Modifier.weight(1f)) {
                Text("VHF", style = LocalExtra.current.overline, color = Hud.Cyan)
                vhf.forEach { PresetRow(it, false) }
            }
        }
    }
}

@Composable
private fun PresetRow(p: Preset, active: Boolean) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(if (active) Hud.Green.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent).padding(vertical = 2.dp)) {
        Text("%2d".format(p.ch), Modifier.width(24.dp), style = LocalExtra.current.monoSmall, color = Hud.TextFaint)
        Text(p.freq, Modifier.width(66.dp), style = LocalExtra.current.monoSmall, color = Hud.Text)
        Text(p.comment ?: "", fontSize = 11.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Raw tab-separated section, shown as a monospace grid (keeps working even if BMS changes the layout). */
@Composable
fun RawCard(title: String, s: RawSection, initiallyOpen: Boolean = false) {
    CollapsibleCard(title, initiallyOpen = initiallyOpen, accent = Hud.TextDim) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            s.rows.forEach { row ->
                Row {
                    row.forEachIndexed { i, cell ->
                        Text(cell, style = LocalExtra.current.monoSmall, color = if (i == 0) Hud.TextDim else Hud.Text, modifier = Modifier.width(if (i == 0) 150.dp else 76.dp).padding(end = 6.dp), maxLines = 1)
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
