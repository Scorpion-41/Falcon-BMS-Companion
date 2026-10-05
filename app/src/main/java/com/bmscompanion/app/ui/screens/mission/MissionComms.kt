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
import com.bmscompanion.app.data.mission.MergedMission
import com.bmscompanion.app.data.mission.MergedPreset
import com.bmscompanion.app.data.mission.MergedSetting
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PlanItemSource
import com.bmscompanion.app.data.mission.Preset
import com.bmscompanion.app.data.mission.RawSection
import com.bmscompanion.app.ui.components.CollapsibleCard
import com.bmscompanion.app.ui.components.Masonry
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra

// The Comms page reads the merged mission (PlanMerge): the briefing's ladder is never changed, the presets are the
// plan's where the Planner sent one — marked, with what the cartridge had — and a ladder row whose preset the plan
// moved says so. The TACAN and ILS WDP writes into the cartridge are shown, marked as keys BMS 4.38.1 reading is not
// established for.

@Composable
fun MissionCommsPane(env: MissionEnv) {
    val merged = rememberMerged()
    val live by MissionLink.live.collectAsState()
    val b = merged.briefing
    val dtc = merged.dtc
    if (b == null && merged.presets.isEmpty() && !dtc.hasData() && merged.plan == null) {
        PaneEmpty("No comm plan yet", "Press PRINT on the BMS Briefing screen, and Save your DTC, to see the comm ladder and presets here.")
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Masonry(minColumn = 440.dp, maxColumns = 2) {
            if (b != null && b.comms.isNotEmpty()) LadderCard(b.comms, live?.uhfFreq ?: 0, merged.presets)
            else if (b != null && merged.fromSave) SectionCard("Comm ladder", accent = Hud.TextDim) {
                Text(fillHint("This briefing was built from your save, which holds no comm ladder. Press PRINT in BMS for the ladder.", "Your save holds no comm ladder: the presets below are your cartridge's."), fontSize = 13.sp, color = Hud.TextDim)
            }
            SupportCard(rememberSupportAssets(env))
            if (merged.presets.isNotEmpty() || radioSettings(merged).isNotEmpty()) PresetCard(merged, live?.uhfPreset ?: 0)
            IffCard(merged)
            b?.sections?.firstOrNull { it.title == "Iff" }?.let { RawCard("IFF plan", it, initiallyOpen = dtc?.iff.isNullOrEmpty()) }
            b?.sections?.firstOrNull { it.title == "Link 16" }?.let { RawCard("Link 16", it) }
        }
    }
}

/** Two frequencies are the same channel when they agree to the kilohertz ("251" and "251.000"). */
private fun sameFreq(a: String?, b: String?): Boolean {
    val x = a?.trim()?.toDoubleOrNull()
    val y = b?.trim()?.toDoubleOrNull()
    return if (x != null && y != null) kotlin.math.abs(x - y) < 0.0005 else a?.trim() == b?.trim()
}

/**
 * What a comm ladder row gets when the plan sent from the Planner changed the preset the briefing puts it on:
 * "UHF preset 3 is now 251.000 (Package)". Null when the preset still holds the row's frequency, or the plan did not
 * touch it. The ladder itself is the briefing's and is never changed.
 */
fun presetNote(c: CommEntry, presets: List<MergedPreset>): String? {
    fun one(band: String, ch: Int?, freq: String?): String? {
        if (ch == null) return null
        val p = presets.firstOrNull { it.band == band && it.ch == ch && it.changed } ?: return null
        if (freq != null && sameFreq(p.freq, freq)) return null
        return "$band preset $ch is now ${p.freq}" + (p.comment?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    }
    return listOfNotNull(one("UHF", c.uhfCh, c.uhf), one("VHF", c.vhfCh, c.vhf)).joinToString(" · ").ifEmpty { null }
}

@Composable
fun LadderCard(comms: List<CommEntry>, tunedUhf: Int, presets: List<MergedPreset> = emptyList()) {
    val tuned = if (tunedUhf > 0) String.format(java.util.Locale.US, "%.3f", tunedUhf / 1000.0) else null
    val notes = comms.map { presetNote(it, presets) }
    val moved = notes.any { it != null }
    SectionCard(
        "Comm ladder", accent = Hud.Amber,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (moved) PlanTags(true, false, compact = true)
                tuned?.let { Text("UHF tuned $it", style = LocalExtra.current.monoSmall, color = Hud.Green) }
            }
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text("AGENCY", Modifier.weight(1f), fontSize = 10.sp, color = Hud.TextFaint)
            Text("UHF", Modifier.width(104.dp), fontSize = 10.sp, color = Hud.TextFaint)
            Text("VHF", Modifier.width(96.dp), fontSize = 10.sp, color = Hud.TextFaint)
        }
        var lastGroup: String? = null
        comms.forEachIndexed { i, c ->
            if (lastGroup != null && c.group != lastGroup) Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Hud.Outline.copy(alpha = 0.5f)))
            lastGroup = c.group
            val isTuned = tuned != null && c.uhf == tuned
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (isTuned) Hud.Green.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent).padding(vertical = 4.dp, horizontal = 2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.agency, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Hud.Text, maxLines = 1)
                        Text(listOfNotNull(c.callsign, c.notes).joinToString(" · "), fontSize = 11.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Freq(c.uhf, c.uhfCh, Modifier.width(104.dp))
                    Freq(c.vhf, c.vhfCh, Modifier.width(96.dp))
                }
                // the briefing still says the old channel: say where it went, in the plan's ink
                notes[i]?.let { Text(it, fontSize = 11.sp, color = PlanInk, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (moved) Text(
            "The plan sent from the Planner changed presets the ladder refers to; the ladder is the briefing's and is shown as printed.",
            fontSize = 10.sp, color = Hud.TextFaint, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Freq(f: String?, ch: Int?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(f ?: "—", style = LocalExtra.current.monoSmall.copy(fontSize = 13.sp), color = if (f != null) Hud.Amber else Hud.TextFaint)
        // in brackets, as the Planner's radio page writes it: "254.725 15" read as one number
        if (ch != null) Text(" [$ch]", style = LocalExtra.current.monoSmall, color = Hud.Cyan)
    }
}

/** The cartridge as it is on disk, with no plan: its presets as merged items, so both cards draw alike. */
@Composable
fun PresetCard(uhf: List<Preset>, vhf: List<Preset>, activePreset: Int) {
    val presets = uhf.map { MergedPreset("UHF", it.ch, it.freq, it.comment) } + vhf.map { MergedPreset("VHF", it.ch, it.freq, it.comment) }
    PresetCard(MergedMission(presets = presets), activePreset)
}

/**
 * The radio settings of the cartridge the pilot will load: the preset tuned on each radio after a load, and the TACAN
 * and ILS WDP writes into `[COMMS]`. From the merge's settings, so the plan's value wins and is marked.
 */
fun radioSettings(merged: MergedMission): List<MergedSetting> =
    listOf("comm1", "comm2", "tacan", "ils").mapNotNull { k -> merged.settings.firstOrNull { it.key == k } }

/**
 * The presets the pilot will have: the plan's where the Planner sent one (marked PLAN, NOT IN JET until the pilot
 * saves and loads the DTC, with what the cartridge had), the cartridge's otherwise. The preset each radio is tuned to
 * after a load is marked LOAD; the TACAN and ILS rows carry the note that BMS 4.38.1 reading them is not tested.
 */
@Composable
fun PresetCard(merged: MergedMission, activePreset: Int) {
    val uhf = merged.presets.filter { it.band == "UHF" }
    val vhf = merged.presets.filter { it.band == "VHF" }
    val comm = merged.plan?.dtc?.comm ?: merged.dtc?.comm
    val settings = radioSettings(merged)
    val changed = merged.presets.any { it.changed }
    SectionCard(
        if (merged.planApplied) "Radio presets" else "DTC presets", accent = Hud.Cyan,
        trailing = { if (changed || settings.any { it.source == PlanItemSource.PLAN }) PlanTags(true, merged.presets.any { it.notInJet } || settings.any { it.notInJet }, compact = true) },
    ) {
        // side by side while nothing is marked; a changed preset carries a second line, so then UHF and VHF go one
        // under the other and a phone keeps its width for the figures
        if (changed) {
            PresetColumn("UHF", Hud.Amber, uhf, activePreset, comm?.comm1)
            if (vhf.isNotEmpty()) { Spacer(Modifier.height(8.dp)); PresetColumn("VHF", Hud.Cyan, vhf, 0, comm?.comm2) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { PresetColumn("UHF", Hud.Amber, uhf, activePreset, comm?.comm1) }
            if (vhf.isNotEmpty()) Column(Modifier.weight(1f)) { PresetColumn("VHF", Hud.Cyan, vhf, 0, comm?.comm2) }
        }
        if (settings.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Hud.Outline.copy(alpha = 0.5f)))
            settings.forEach { s -> RadioSettingRow(s) }
        }
    }
}

@Composable
private fun PresetColumn(label: String, ink: androidx.compose.ui.graphics.Color, rows: List<MergedPreset>, active: Int, atLoad: Int?) {
    if (rows.isEmpty()) return
    Text(label, style = LocalExtra.current.overline, color = ink)
    rows.forEach { PresetRow(it, it.ch == active && active > 0, it.ch == atLoad) }
}

@Composable
private fun PresetRow(p: MergedPreset, active: Boolean, atLoad: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(if (active) Hud.Green.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent).padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%2d".format(p.ch), Modifier.width(24.dp), style = LocalExtra.current.monoSmall, color = Hud.TextFaint)
            Text(p.freq, Modifier.width(66.dp), style = LocalExtra.current.monoSmall, color = if (p.changed) PlanInk else Hud.Text)
            Text(p.comment ?: "", Modifier.weight(1f), fontSize = 11.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (atLoad) Text("LOAD", fontSize = 9.sp, color = Hud.Green, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
        }
        if (p.changed) Row(Modifier.padding(start = 24.dp, top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            PlanTags(true, p.notInJet, compact = true)
            p.was?.let { Text("cartridge: $it", fontSize = 10.sp, color = Hud.TextFaint, modifier = Modifier.padding(start = 6.dp)) }
                ?: Text("new in the plan", fontSize = 10.sp, color = Hud.TextFaint, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** "TACAN  94X  [PLAN]", and under a key only WDP writes, what is not established about it. */
@Composable
private fun RadioSettingRow(s: MergedSetting) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.label, Modifier.width(96.dp), fontSize = 12.sp, color = Hud.TextDim)
            Text(s.value, Modifier.weight(1f), style = LocalExtra.current.monoSmall, color = if (s.source == PlanItemSource.PLAN) PlanInk else Hud.Text, maxLines = 1)
            PlanTags(s.source == PlanItemSource.PLAN, s.notInJet, compact = true)
        }
        s.was?.let { Text("cartridge: $it", fontSize = 10.sp, color = Hud.TextFaint, modifier = Modifier.padding(start = 96.dp)) }
        if (s.untested) Text("WDP key: BMS 4.38.1 reading not tested", fontSize = 10.sp, color = Hud.TextFaint, modifier = Modifier.padding(start = 96.dp))
    }
}

/**
 * The IFF codes the pilot will load: the plan's where the Planner sent one, each code the plan changed in the plan's
 * ink and marked; the cartridge's otherwise.
 */
@Composable
fun IffCard(merged: MergedMission) {
    val disk = merged.dtc?.iff.orEmpty()
    val plan = merged.plan?.dtc?.iff.orEmpty()
    // key in the cartridge, label, the code shown, what the cartridge on disk has (null when the plan did not change it)
    data class Code(val label: String, val value: String, val was: String?, val fromPlan: Boolean)
    val codes = listOf("Mode1 Code" to "M1", "Mode2 Code" to "M2", "Mode3A Code" to "M3", "Mode4 Key" to "M4 key").mapNotNull { (k, label) ->
        val p = plan[k]?.takeIf { it.isNotBlank() }
        val v = p ?: disk[k] ?: return@mapNotNull null
        val fromPlan = p != null && p != disk[k]
        Code(label, v, if (fromPlan) disk[k] else null, fromPlan)
    }
    if (codes.isEmpty()) return
    val any = codes.any { it.fromPlan }
    SectionCard("IFF (DTC)", accent = Hud.Green, trailing = { if (any) PlanTags(true, true, compact = true) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            codes.forEach { c ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).padding(8.dp)) {
                    Text(c.label, fontSize = 10.sp, color = Hud.TextDim)
                    Text(c.value, style = LocalExtra.current.mono, color = if (c.fromPlan) PlanInk else Hud.Green)
                    if (c.fromPlan) Text("plan · was ${c.was ?: "—"}", fontSize = 9.sp, color = Hud.TextFaint, maxLines = 1)
                }
            }
        }
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
