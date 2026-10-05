@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bmscompanion.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.WeatherState
import com.bmscompanion.app.data.mission.WxTheater
import com.bmscompanion.app.data.weather.CampaignTime
import com.bmscompanion.app.data.weather.WxAloft
import com.bmscompanion.app.data.weather.WxCell
import com.bmscompanion.app.data.weather.WxCover
import com.bmscompanion.app.data.weather.WxDefaults
import com.bmscompanion.app.data.weather.WxGenParams
import com.bmscompanion.app.data.weather.WxLowClouds
import com.bmscompanion.app.data.weather.WxModel
import com.bmscompanion.app.data.weather.WxOverride
import com.bmscompanion.app.data.weather.WxRandom
import com.bmscompanion.app.data.weather.WxRange
import com.bmscompanion.app.data.weather.WxSpread
import com.bmscompanion.app.data.weather.WxTowering
import com.bmscompanion.app.data.weather.WxType
import com.bmscompanion.app.data.weather.WxTypeParams
import com.bmscompanion.app.ui.screens.wdp.BriefedSave
import com.bmscompanion.app.ui.screens.wdp.PlannerMissionState
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The Generate page's controls, in WeatherGen's own order so a pilot who knows that tool finds each one where they
 * expect it: the selected cell and its forecast first, then how the map is drawn, the presets, and the generator's
 * seven panels — weather parameters, type weights, override regions, atmosphere, advanced, time, save.
 *
 * Each panel folds, and remembers whether it was open, because on a phone they stack under the map and nobody needs
 * all seven at once.
 */

private val TYPES_HIGH_FIRST = listOf(WxType.SUNNY, WxType.FAIR, WxType.POOR, WxType.INCLEMENT)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WxGenPanels(
    th: Theater?,
    wx: WxTheater?,
    st: WxGenState,
    airports: List<Airport>,
    online: Boolean,
    busy: Boolean,
    error: String?,
    onBusy: (Boolean) -> Unit,
    onState: (WeatherState?) -> Unit,
    reload: () -> Unit,
    noSave: String? = null,
) {
    Inspector(th, st, airports)
    DisplayPanel()
    PresetsPanel(st)
    ParametersPanel(st)
    WeightsPanel(st)
    OverridesPanel(st)
    AtmospherePanel(st)
    AdvancedPanel(st)
    TimePanel(st)
    SavePanel(wx, st, online, busy, error, onBusy, onState, reload, noSave)
    Spacer(Modifier.height(24.dp))
}

// ------------------------------------------------------------------------------------------------ furniture

/**
 * A panel that folds: the Editor's group title, with a caret, remembered in the prefs under `open_wx_<key>` so the
 * page opens the way the pilot left it.
 */
@Composable
private fun Fold(
    title: String,
    key: String,
    initiallyOpen: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(Repo.getInt("open_wx_$key", if (initiallyOpen) 1 else 0) == 1) }
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clickable { open = !open; Repo.putInt("open_wx_$key", if (open) 1 else 0) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "▾" else "▸", color = Hud.Amber, fontSize = 12.sp)
            Spacer(Modifier.width(7.dp))
            Text(title.uppercase(), color = Hud.Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(Hud.Outline.copy(alpha = 0.55f)))
        }
        if (trailing != null && open) { Spacer(Modifier.width(10.dp)); trailing() }
    }
    if (open) content()
}

/** A label and a run of chips that wrap onto a second line on a phone rather than running off the edge. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrapRow(label: String?, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().heightIn(min = ROW).padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (label != null) {
            Text(label, Modifier.align(Alignment.CenterVertically).padding(end = 4.dp), color = Hud.TextDim, fontSize = 13.sp, maxLines = 1)
        }
        content()
    }
}

/** A campaign time a step at a time: ◀ Day 1 05:00 ▶, with [step] minutes a press. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeRow(label: String, t: CampaignTime, step: Int, onChange: (CampaignTime) -> Unit) {
    WrapRow(label) {
        EditorChip("◀◀") { onChange(t.plusMinutes(-60.0 * 6).floorDay()) }
        EditorChip("◀") { onChange(t.plusMinutes(-step.toDouble()).floorDay()) }
        Text(
            t.label, Modifier.align(Alignment.CenterVertically).width(98.dp),
            color = Hud.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, maxLines = 1,
        )
        EditorChip("▶") { onChange(t.plusMinutes(step.toDouble())) }
        EditorChip("▶▶") { onChange(t.plusMinutes(60.0 * 6)) }
    }
}

/** The campaign starts on day 1: a time before it is day 1 00:00. */
private fun CampaignTime.floorDay(): CampaignTime = if (day < 1) CampaignTime(1, 0, 0) else this

@Composable
private fun TextInput(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, placeholder: String = "") {
    Box(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(Hud.Surface)
            .border(1.dp, Hud.Outline.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = Hud.TextFaint, fontSize = 12.5.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = TextStyle(color = Hud.Text, fontSize = 12.5.sp, fontFamily = if (singleLine) FontFamily.Default else FontFamily.Monospace),
            cursorBrush = SolidColor(Hud.Amber),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A setting's name with a swatch of its type's colour before it. */
@Composable
private fun Swatch(t: WxType) = Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(typeSwatch(t)))

// ------------------------------------------------------------------------------------------------ the inspector

/**
 * The selected cell, read the way a forecast is: type, pressure, temperature, cloud as a METAR writes it, the wind
 * at all ten of BMS's levels, then how the next few steps go for that cell and for an airfield — the one chosen, or
 * the nearest — from the app's own airport data.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Inspector(th: Theater?, st: WxGenState, airports: List<Airport>) {
    val g = st.grid
    val p = st.params
    Fold("Selected cell", "inspect", initiallyOpen = true, trailing = {
        if (st.selected != null || st.cells.isNotEmpty()) EditorChip("Clear") { st.cells = emptySet(); st.selected = null; st.airport = null }
    }) {
        if (g != null && st.cells.size > 1) CellsSummary(st, g)
        val sel = st.selected
        val cell = g?.cell(sel)
        if (sel == null || g == null || cell == null) {
            Note(
                "Tap a cell on the map to read its weather and its forecast; Shift- or Ctrl-click (or Select several) " +
                    "to add more and read them together. Press and hold to drop an override region there.",
            )
            return@Fold
        }
        if (st.cells.size > 1) {
            Text(
                "LAST PICKED", Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Swatch(cell.type)
            Spacer(Modifier.width(7.dp))
            Text("${cell.type.label}   cell ${sel.first}, ${sel.second}", color = Hud.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        CellFacts(cell, p)
        val step = p.movement.stepMin
        Text(
            "FORECAST, EVERY ${step.roundToInt()} MIN", Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
            color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        )
        ForecastStrip(remember(p, sel) { WxModel.forecast(p, sel.first, sel.second, step, 6) })

        // the airfield: the one picked, or the nearest to the selected cell
        if (th != null && airports.isNotEmpty()) {
            val near = remember(sel, airports, th.sizeFt, g.cols) {
                val cx = (1 - (sel.second + 0.5) / g.rows) * th.sizeFt
                val cy = (sel.first + 0.5) / g.cols * th.sizeFt
                airports.minByOrNull { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }
            }
            val a = st.airport ?: near
            var query by remember { mutableStateOf("") }
            Text(
                "AIRFIELD", Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            )
            TextInput(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Find an airfield by name or ICAO")
            if (query.isNotBlank()) {
                val q = query.trim().lowercase()
                val hits = airports.filter { it.name.lowercase().contains(q) || it.icao?.lowercase()?.contains(q) == true }.take(8)
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (h in hits) EditorChip(h.icao?.let { "${h.name} ($it)" } ?: h.name) { st.airport = h; query = "" }
                    if (hits.isEmpty()) Note("No airfield by that name here.")
                }
            }
            if (a != null) {
                val ac = cellOf(a.x, a.y, th.sizeFt, g.cols, g.rows)
                Text(
                    (a.icao?.let { "$it  " } ?: "") + a.name + if (st.airport == null) "  · nearest" else "",
                    Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp), color = Hud.Cyan, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                )
                if (ac != null) ForecastStrip(remember(p, ac) { WxModel.forecast(p, ac.first, ac.second, step, 6) })
            }
        }
    }
}

/**
 * Several picked cells read together: how many of each type, and the range of everything else across them. And
 * the way to change them together: a region laid over them, which the override editor then sets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CellsSummary(st: WxGenState, g: com.bmscompanion.app.data.weather.WxGrid) {
    val cells = st.cells.mapNotNull { g.cell(it) }
    if (cells.isEmpty()) return
    fun range(v: List<Double>, fmt: (Double) -> String): String {
        val lo = v.min()
        val hi = v.max()
        return if (fmt(lo) == fmt(hi)) fmt(lo) else fmt(lo) + " – " + fmt(hi)
    }
    Text(
        "${cells.size} CELLS PICKED", Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
        color = Hud.Amber, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
    )
    FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in TYPES_HIGH_FIRST) {
            val n = cells.count { it.type == t }
            if (n == 0) continue
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(t); Spacer(Modifier.width(5.dp))
                Text("${t.label} $n", color = Hud.Text, fontSize = 12.5.sp)
            }
        }
    }
    val covers = cells.map { it.cover }.distinct().sortedBy { it.value }
    val clouded = cells.filter { it.cover != WxCover.NONE }
    val lines = listOf(
        "Pressure" to range(cells.map { it.pressureInHg }) { pressureText(it, WxGenLook.mb) } + if (WxGenLook.mb) " mb" else " inHg",
        "Temperature" to range(cells.map { it.tempC }) { "${it.roundToInt()}" } + " °C",
        "Wind" to range(cells.map { it.windDeg[0] }) { three(it.roundToInt().mod(360)) } + "° at " +
            range(cells.map { it.windKt[0] }) { "${it.roundToInt()}" } + " kt",
        "Cloud" to (covers.joinToString(" / ") { if (it == WxCover.NONE) "SKC" else it.metar } +
            if (clouded.isEmpty()) "" else ", base " + range(clouded.map { it.baseFt }) { group(((it / 100).roundToInt() * 100)) } + " ft"),
        "Visibility" to range(cells.map { it.visKm }) { visText(it) } + " km",
    )
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        for ((k, v) in lines) {
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(k, Modifier.width(92.dp), color = Hud.TextFaint, fontSize = 12.sp)
                Text(v, color = Hud.Text, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
    WrapRow(null) {
        EditorChip("Cover them with a region") { st.coverCells() }
    }
    Note("A region over them gives them one weather: it opens in Override regions below, with the type most of them already are.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CellFacts(c: WxCell, p: WxGenParams) {
    val contrail = p.clouds.contrailsFt.getOrNull(TYPES_HIGH_FIRST.indexOf(c.type)) ?: 0
    val stratus = if (c.type == WxType.SUNNY || c.type == WxType.FAIR) p.clouds.stratusFairFt else p.clouds.stratusInclementFt
    val lines = listOf(
        "Pressure" to "${pressureText(c.pressureInHg, false)} inHg · ${pressureText(c.pressureInHg, true)} mb",
        "Temperature" to "${c.tempC.roundToInt()} °C · precip ${precipText(c)}",
        "Cloud" to "${cloudText(c)}${if (c.cover != WxCover.NONE) " · size " + ((c.size * 10).roundToInt() / 10.0) else ""}",
        "High" to "stratus ${group(stratus)} ft · COTRA${three(contrail / 100)}",
        "Visibility" to "${visText(c.visKm)} km",
    )
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        for ((k, v) in lines) {
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(k, Modifier.width(92.dp), color = Hud.TextFaint, fontSize = 12.sp)
                Text(v, color = Hud.Text, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Row(Modifier.padding(vertical = 2.dp)) {
            Text("Wind", Modifier.width(92.dp), color = Hud.TextFaint, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                WxDefaults.WIND_ALTS.forEachIndexed { k, alt ->
                    Row {
                        Text(altLabel(alt).padStart(3), color = Hud.TextFaint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(4.dp))
                        Text(windText(c.windDeg[k], c.windKt[k]), color = Hud.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/** A few steps of forecast side by side, a column each, scrolling sideways on a narrow screen. */
@Composable
private fun ForecastStrip(rows: List<Pair<CampaignTime, WxCell>>) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((t, c) in rows) {
            Column(
                Modifier.width(78.dp).clip(RoundedCornerShape(4.dp)).background(Hud.Surface)
                    .border(1.dp, typeSwatch(c.type).copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                val hhmm = t.label.substringAfterLast(' ')
                Text(if (t.day != rows.first().first.day) "D${t.day} $hhmm" else hhmm, color = Hud.Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(c.type); Spacer(Modifier.width(4.dp))
                    Text(c.type.label, color = Hud.Text, fontSize = 10.5.sp, maxLines = 1)
                }
                for (s in listOf(windText(c.windDeg[0], c.windKt[0]), cloudText(c).substringBefore(' '), "${visText(c.visKm)} km", "${c.tempC.roundToInt()}°C", pressureText(c.pressureInHg, WxGenLook.mb))) {
                    Text(s, color = Hud.TextDim, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ display and presets

@Composable
private fun DisplayPanel() {
    Fold("Display", "display", initiallyOpen = true) {
        WrapRow("Colour") { for (d in WxDisplay.entries) EditorChip(d.label, selected = WxGenLook.display == d) { WxGenLook.display(d) } }
        WrapRow("Overlay") { for (o in WxOverlay.entries) EditorChip(o.label, selected = WxGenLook.overlay == o) { WxGenLook.overlay(o) } }
        if (WxGenLook.overlay == WxOverlay.WIND) {
            WrapRow("Wind at") {
                WxDefaults.WIND_ALTS.forEachIndexed { k, alt -> EditorChip(altLabel(alt), selected = WxGenLook.windLevel == k) { WxGenLook.windLevel(k) } }
            }
        }
        NumRow("Opacity", (WxGenLook.opacity * 100.0).roundToInt().toDouble(), 0.0, 100.0, "%", 1.0) { WxGenLook.opacity((it / 100).toFloat()) }
        WrapRow("Pressure") {
            EditorChip("inHg", selected = !WxGenLook.mb) { WxGenLook.mb(false) }
            EditorChip("mb", selected = WxGenLook.mb) { WxGenLook.mb(true) }
            Spacer(Modifier.width(8.dp))
            EditorChip("Grid", selected = WxGenLook.grid) { WxGenLook.grid(!WxGenLook.grid) }
            EditorChip("BMS 2D", selected = WxGenLook.bms) { WxGenLook.bms(!WxGenLook.bms) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsPanel(st: WxGenState) {
    var last by remember { mutableStateOf<WxGenPreset?>(null) }
    Fold("Presets", "presets", initiallyOpen = true) {
        FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (pr in WX_GEN_PRESETS) EditorChip(pr.name, selected = last == pr) {
                st.edit { pr.make(it) }
                st.editing = null
                last = pr
            }
        }
        Note(last?.note ?: "A preset sets the generator's weights, ranges and regions; your seed and clock are kept, and every setting below tunes from there.")
    }
}

// ------------------------------------------------------------------------------------------------ 1-2

@Composable
private fun ParametersPanel(st: WxGenState) {
    val p = st.params
    Fold("Weather parameters", "params", initiallyOpen = true, trailing = {
        EditorChip("Random") { st.edit { it.copy(seed = WxRandom.seed()) } }
    }) {
        NumRow("Seed", p.seed, 1.0, 9999.0, "", 1.0) { v -> st.edit { it.copy(seed = v) } }
        NumRow("Min pressure", p.pressureMin, 27.0, 32.0, "inHg", 0.01) { v -> st.edit { it.copy(pressureMin = v.coerceAtMost(it.pressureMax - 0.1)) } }
        NumRow("Max pressure", p.pressureMax, 27.0, 32.0, "inHg", 0.01) { v -> st.edit { it.copy(pressureMax = v.coerceAtLeast(it.pressureMin + 0.1)) } }
        NumRow("Pressure spread", p.pressureVariance, 0.0, 4.0, "inHg", 0.05) { v -> st.edit { it.copy(pressureVariance = v) } }
        NumRow("Prevailing wind", p.prevailingDeg, 0.0, 359.0, "°", 1.0) { v -> st.edit { it.copy(prevailingDeg = v) } }
        NumRow("Weather heading", p.movement.headingDeg, 0.0, 359.0, "°", 1.0) { v -> st.edit { it.copy(movement = it.movement.copy(headingDeg = v)) } }
        NumRow("Weather speed", p.movement.speedKt, 0.0, 100.0, "kt", 1.0) { v -> st.edit { it.copy(movement = it.movement.copy(speedKt = v)) } }
        Note(
            "The seed is the weather system: the same seed and settings always give the same weather. Pressure spread " +
                "is the most it varies across the theater at one time. The weather moves toward its heading at its " +
                "speed as the clock steps.",
        )
    }
}

@Composable
private fun WeightsPanel(st: WxGenState) {
    val p = st.params
    Fold("Weather type weights", "weights", initiallyOpen = true) {
        for (t in TYPES_HIGH_FIRST) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(t)
                Box(Modifier.weight(1f)) {
                    NumRow(t.label, p.type(t).weight, 1.0, 100.0, "", 1.0) { v -> st.edit { it.withType(t, it.type(t).copy(weight = v)) } }
                }
            }
        }
        Note("How much of the pressure range each type takes: inclement in the lows, sunny on the highs. Less on the left, more on the right.")
    }
}

// ------------------------------------------------------------------------------------------------ 3 overrides

/**
 * The override regions, and the editor for whichever are picked.
 *
 * Picking is the map's (click a region's number; Shift- or Ctrl-click, or Select several, for more than one) and the
 * list's own (a name picks that region alone, the box beside it adds it or takes it away). One region picked is
 * edited under its own line, as before; several are edited together above the list, every change going to all of
 * them, and a setting they do not agree on reads "mixed" until it is set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverridesPanel(st: WxGenState) {
    val p = st.params
    val picked = st.regions.filter { it in p.overrides.indices }.sorted()
    Fold("Override regions", "overrides", initiallyOpen = true, trailing = {
        EditorChip("+ Add") { st.addOverride(30.0, 30.0) }
    }) {
        WrapRow(null) {
            EditorChip("Place on the map", selected = st.placing) { st.placing = !st.placing; st.editing = null }
            if (p.overrides.size > 1) {
                val everything = picked.size == p.overrides.size
                EditorChip(if (everything) "Pick none" else "Pick all") {
                    st.regions = if (everything) emptySet() else p.overrides.indices.toSet()
                }
            }
        }
        if (p.overrides.isEmpty()) {
            Note("A region forces its own weather over the pattern — a storm, a clear patch — fading into the weather round it. Press and hold on the map to drop one.")
        } else {
            Note(
                "Click a region's number on the map, or its name here, to edit it. To change several at once, " +
                    "Shift- or Ctrl-click them (on a touch screen, Select several on the map, or the boxes here).",
            )
        }
        if (picked.size > 1) {
            Rule(0.4f)
            Row(Modifier.fillMaxWidth().heightIn(min = ROW).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${picked.size} REGIONS PICKED: " + picked.joinToString(", ") { "${it + 1}" },
                    Modifier.weight(1f),
                    color = Hud.Amber, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 2,
                )
                Box(Modifier.wxProbe("picked-done")) { EditorChip("Done") { st.regions = emptySet() } }
                Spacer(Modifier.width(8.dp))
                EditorChip("Remove ${picked.size}", tint = Hud.Red) { st.removeOverrides(picked.toSet()) }
            }
            OverrideEditor(st, picked)
            Rule(0.4f)
        }
        p.overrides.forEachIndexed { i, o ->
            val on = i in st.regions
            Rule(0.25f)
            Row(Modifier.fillMaxWidth().heightIn(min = ROW).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.wxProbe("pick-${i + 1}")) { PickBox(on) { st.regions = if (on) st.regions - i else st.regions + i } }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${i + 1}  " + (o.type?.label ?: "No type") + (if (o.animate) "  · peak ${o.peak.label}" else "") +
                        (if (!o.showOutline) "  · hidden" else ""),
                    Modifier.weight(1f).clickable { st.editing = if (st.editing == i) null else i },
                    color = if (on) Hud.Amber else Hud.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                EditorChip("Remove", tint = Hud.Red) { st.removeOverride(i) }
            }
            if (picked.size == 1 && picked[0] == i) OverrideEditor(st, picked)
        }
    }
}

/** A tick box: whether a region is in the selection. Drawn here rather than borrowed, to match the chips beside it. */
@Composable
private fun PickBox(checked: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(4.dp))
            .background(if (checked) Hud.Amber else Hud.Surface)
            .border(1.5.dp, if (checked) Hud.Amber else Hud.Outline, RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Default.Check, contentDescription = "Picked", tint = Hud.Bg, modifier = Modifier.size(16.dp))
    }
}

/** What a set of regions says about one setting: the first one's value, and whether any of the others differs. */
private class Agree<T>(val value: T, val mixed: Boolean)

/**
 * The settings of the regions [idx], all at once: each row shows the value they share, or "mixed", and whatever is
 * set goes to every one of them. With one region this is simply that region's editor, centre included; with several
 * the centres are left alone (setting them would stack the regions on one spot) — they move together on the map.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverrideEditor(st: WxGenState, idx: List<Int>) {
    val all = idx.mapNotNull { st.params.overrides.getOrNull(it) }
    if (all.isEmpty()) return
    val one = all.singleOrNull()
    fun set(f: (WxOverride) -> WxOverride) = st.editOverrides(idx, f)
    fun <T> agree(get: (WxOverride) -> T): Agree<T> {
        val v = all.map(get)
        return Agree(v.first(), v.distinct().size > 1)
    }
    fun <T : Any> shared(get: (WxOverride) -> T?): Agree<T>? {
        val first = all.firstNotNullOfOrNull(get) ?: return null
        return Agree(first, all.map(get).distinct().size > 1)
    }

    Column(Modifier.padding(start = 10.dp)) {
        if (one != null) {
            NumRow("Centre X", one.x, -10.0, 69.0, "cell", 0.5) { v -> set { it.copy(x = v) } }
            NumRow("Centre Y", one.y, -10.0, 69.0, "cell", 0.5) { v -> set { it.copy(y = v) } }
        }
        agree { it.radius }.let { a ->
            NumRow("Radius", a.value, 1.0, 60.0, "cells", 0.5, mixed = a.mixed) { v -> set { it.copy(radius = v, falloff = it.falloff.coerceAtMost(v)) } }
        }
        agree { it.falloff }.let { a ->
            NumRow("Falloff", a.value, 0.0, one?.radius ?: all.maxOf { it.radius }, "cells", 0.5, mixed = a.mixed) { v ->
                set { it.copy(falloff = v.coerceAtMost(it.radius)) }
            }
        }
        agree { it.strength }.let { a -> NumRow("Strength", a.value, 0.0, 1.0, "", 0.05, mixed = a.mixed) { v -> set { it.copy(strength = v) } } }
        Note("Full strength inside the falloff, fading to nothing at the radius. Keep the falloff a few cells inside the radius so the edge blends.")
        WrapRow("Override") {
            OptChip(all, ::set, "Type", { it.type }, { it.copy(type = WxType.INCLEMENT) }, { it.copy(type = null) })
            OptChip(all, ::set, "Temp", { it.tempC }, { it.copy(tempC = 15.0) }, { it.copy(tempC = null) })
            OptChip(all, ::set, "Vis", { it.visKm }, { it.copy(visKm = 10.0) }, { it.copy(visKm = null) })
            OptChip(all, ::set, "Cover", { it.cover }, { it.copy(cover = WxCover.BROKEN) }, { it.copy(cover = null) })
            OptChip(all, ::set, "Base", { it.baseFt }, { it.copy(baseFt = 3000.0) }, { it.copy(baseFt = null) })
            OptChip(all, ::set, "Size", { it.size }, { it.copy(size = 2.0) }, { it.copy(size = null) })
            OptChip(all, ::set, "Towering", { it.towering }, { it.copy(towering = true) }, { it.copy(towering = null) })
            OptChip(all, ::set, 
                "Wind", { o -> (o.windDeg ?: o.windKt)?.let { true } },
                { it.copy(windDeg = 270.0, windKt = 20.0) }, { it.copy(windDeg = null, windKt = null) },
            )
        }
        shared { it.type }?.let { a ->
            WrapRow(if (a.mixed) "Type (mixed)" else "Type") {
                for (x in TYPES_HIGH_FIRST) {
                    Box(Modifier.wxProbe("type-" + x.label)) {
                        EditorChip(x.label, selected = !a.mixed && a.value == x, tint = typeSwatch(x)) { set { it.copy(type = x) } }
                    }
                }
            }
        }
        shared { it.tempC }?.let { a -> NumRow("Temperature", a.value, -50.0, 50.0, "°C", 1.0, mixed = a.mixed) { n -> set { it.copy(tempC = n) } } }
        shared { it.visKm }?.let { a -> NumRow("Visibility", a.value, 0.0, 60.0, "km", 0.5, mixed = a.mixed) { n -> set { it.copy(visKm = n) } } }
        shared { it.cover }?.let { a ->
            WrapRow(if (a.mixed) "Cloud cover (mixed)" else "Cloud cover") {
                for (x in listOf(WxCover.FEW, WxCover.SCATTERED, WxCover.BROKEN, WxCover.OVERCAST)) {
                    EditorChip(x.metar, selected = !a.mixed && a.value == x) { set { it.copy(cover = x) } }
                }
            }
        }
        shared { it.baseFt }?.let { a -> NumRow("Cloud base", a.value, 0.0, 10000.0, "ft", 100.0, mixed = a.mixed) { n -> set { it.copy(baseFt = n) } } }
        shared { it.size }?.let { a -> NumRow("Cloud size", a.value, 0.0, 5.0, "", 0.1, mixed = a.mixed) { n -> set { it.copy(size = n) } } }
        shared { it.towering }?.let { a ->
            WrapRow(if (a.mixed) "Towering (mixed)" else "Towering") {
                EditorChip("Yes", selected = !a.mixed && a.value) { set { it.copy(towering = true) } }
                EditorChip("No", selected = !a.mixed && !a.value) { set { it.copy(towering = false) } }
            }
        }
        if (all.any { it.windDeg != null || it.windKt != null }) {
            val d = shared { it.windDeg }
            val k = shared { it.windKt }
            NumRow("Wind from", d?.value ?: 0.0, 0.0, 359.0, "°", 1.0, mixed = d == null || d.mixed) { n -> set { it.copy(windDeg = n) } }
            NumRow("Wind speed", k?.value ?: 0.0, 0.0, 200.0, "kt", 1.0, mixed = k == null || k.mixed) { n -> set { it.copy(windKt = n) } }
            WrapRow("Winds at") {
                val allAlts = all.all { o -> WxDefaults.WIND_ALTS.all { it in o.windAlts } }
                val someAlts = all.any { o -> o.windAlts.isNotEmpty() }
                EditorChip("All", selected = allAlts, mixed = !allAlts && someAlts) {
                    set { it.copy(windAlts = if (allAlts) emptyList() else WxDefaults.WIND_ALTS) }
                }
                for (alt in WxDefaults.WIND_ALTS) {
                    val have = all.count { alt in it.windAlts }
                    EditorChip(altLabel(alt), selected = have == all.size, mixed = have in 1 until all.size) {
                        val add = have < all.size
                        set { o ->
                            o.copy(windAlts = if (add) WxDefaults.WIND_ALTS.filter { a -> a in o.windAlts || a == alt } else o.windAlts - alt)
                        }
                    }
                }
            }
        }
        WrapRow(null) {
            FlagChip(all, ::set, "Show outline", { it.showOutline }) { o, v -> o.copy(showOutline = v) }
            FlagChip(all, ::set, "Fade in/out", { it.animate }) { o, v -> o.copy(animate = v) }
            FlagChip(all, ::set, "Not in forecast", { it.excludeFromForecast }) { o, v -> o.copy(excludeFromForecast = v) }
        }
        if (all.any { it.animate }) {
            // several regions keep their own times: a step moves each of them by the same amount
            fun shown(get: (WxOverride) -> CampaignTime) = all.map(get).distinct().singleOrNull()
            TimeShiftRow("Begin", shown { it.begin }, 15) { m -> set { it.copy(begin = it.begin.plusMinutes(m).floorDay()) } }
            TimeShiftRow("Peak", shown { it.peak }, 15) { m -> set { it.copy(peak = it.peak.plusMinutes(m).floorDay()) } }
            TimeShiftRow("Taper", shown { it.taper }, 15) { m -> set { it.copy(taper = it.taper.plusMinutes(m).floorDay()) } }
            TimeShiftRow("End", shown { it.end }, 15) { m -> set { it.copy(end = it.end.plusMinutes(m).floorDay()) } }
            Note(
                "Nothing before Begin, growing to full at Peak, full until Taper, gone by End." +
                    if (all.size > 1) " With several picked, a step moves each one's time by the same amount." else "",
            )
        }
        if (all.any { it.excludeFromForecast }) Note("Left out of every forecast after now, so a scripted surprise does not give itself away.")
    }
}

/** An optional setting of the regions [all]: on for all of them, for some (mixed), or for none. A press turns it on for all, or off when all have it. */
@Composable
private fun <T : Any> OptChip(
    all: List<WxOverride>,
    set: ((WxOverride) -> WxOverride) -> Unit,
    label: String,
    get: (WxOverride) -> T?,
    on: (WxOverride) -> WxOverride,
    off: (WxOverride) -> WxOverride,
) {
    val have = all.count { get(it) != null }
    EditorChip(label, selected = have == all.size, mixed = have in 1 until all.size) {
        if (have == all.size) set(off) else set { if (get(it) == null) on(it) else it }
    }
}

/** A yes-or-no of the regions [all], the same way: lit when all say yes, mixed when some do. */
@Composable
private fun FlagChip(
    all: List<WxOverride>,
    set: ((WxOverride) -> WxOverride) -> Unit,
    label: String,
    get: (WxOverride) -> Boolean,
    put: (WxOverride, Boolean) -> WxOverride,
) {
    val have = all.count(get)
    EditorChip(label, selected = have == all.size, mixed = have in 1 until all.size) {
        val v = have < all.size
        set { put(it, v) }
    }
}

/** A time a step at a time, as [TimeRow], for several regions: [shown] is their shared time, null when they differ. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeShiftRow(label: String, shown: CampaignTime?, step: Int, onShift: (Double) -> Unit) {
    WrapRow(label) {
        EditorChip("◀◀") { onShift(-60.0 * 6) }
        EditorChip("◀") { onShift(-step.toDouble()) }
        Text(
            shown?.label ?: "mixed", Modifier.align(Alignment.CenterVertically).width(98.dp),
            color = if (shown == null) Hud.TextFaint else Hud.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace, maxLines = 1,
        )
        EditorChip("▶") { onShift(step.toDouble()) }
        EditorChip("▶▶") { onShift(60.0 * 6) }
    }
}

// ------------------------------------------------------------------------------------------------ 4 atmosphere

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AtmospherePanel(st: WxGenState) {
    val p = st.params
    var type by remember { mutableStateOf(WxType.FAIR) }
    var level by remember { mutableStateOf(0) }
    Fold("Atmosphere", "atmosphere") {
        WrapRow(null) {
            EditorChip("Randomise clouds & vis") { st.edit { WxRandom.clouds(WxRandom.atmosphere(it)) } }
            EditorChip("Randomise wind") { st.edit { WxRandom.wind(it) } }
            EditorChip("Randomise temperature") { st.edit { WxRandom.temperature(it) } }
            EditorChip("Reset", tint = Hud.Red) {
                st.edit { q ->
                    val d = WxGenParams()
                    fun keep(t: WxType) = d.type(t).copy(weight = q.type(t).weight)
                    q.copy(sunny = keep(WxType.SUNNY), fair = keep(WxType.FAIR), poor = keep(WxType.POOR), inclement = keep(WxType.INCLEMENT),
                        windsAloft = d.windsAloft, clouds = d.clouds)
                }
            }
        }
        Text("HIGH CLOUD", Modifier.padding(start = 4.dp, top = 8.dp), color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        NumRow("Sunny / fair", p.clouds.stratusFairFt.toDouble(), 0.0, 60000.0, "ft", 500.0) { v -> st.edit { it.copy(clouds = it.clouds.copy(stratusFairFt = v.toInt())) } }
        NumRow("Poor / incl.", p.clouds.stratusInclementFt.toDouble(), 0.0, 60000.0, "ft", 500.0) { v -> st.edit { it.copy(clouds = it.clouds.copy(stratusInclementFt = v.toInt())) } }

        WrapRow("Type") { for (t in TYPES_HIGH_FIRST) EditorChip(t.label, selected = type == t, tint = typeSwatch(t)) { type = t } }
        val tp = p.type(type)
        fun setT(f: (WxTypeParams) -> WxTypeParams) = st.edit { it.withType(type, f(it.type(type))) }
        NumRow("Visibility from", tp.visibility.from, 0.0, 60.0, "km", 0.5) { v -> setT { it.copy(visibility = it.visibility.copy(from = v)) } }
        NumRow("Visibility to", tp.visibility.to, 0.0, 60.0, "km", 0.5) { v -> setT { it.copy(visibility = it.visibility.copy(to = v)) } }
        SpreadRows("Wind", tp.wind, 0.0, 100.0, "kt") { s -> setT { it.copy(wind = s) } }
        SpreadRows("Temp", tp.temp, -50.0, 50.0, "°C") { s -> setT { it.copy(temp = s) } }
        val ci = TYPES_HIGH_FIRST.indexOf(type)
        NumRow("Contrails", (p.clouds.contrailsFt.getOrNull(ci) ?: 0).toDouble(), 0.0, 60000.0, "ft", 500.0) { v ->
            st.edit { q -> q.copy(clouds = q.clouds.copy(contrailsFt = List(4) { k -> if (k == ci) v.toInt() else q.clouds.contrailsFt.getOrElse(k) { 0 } })) }
        }
        val lc = tp.lowClouds
        if (type == WxType.SUNNY || lc == null) {
            Note("Sunny is clear of low cloud by definition.")
        } else {
            fun setC(f: (WxLowClouds) -> WxLowClouds) = setT { it.copy(lowClouds = f(it.lowClouds ?: lc)) }
            NumRow("Base from", lc.base.from, 0.0, 10000.0, "ft", 100.0) { v -> setC { it.copy(base = it.base.copy(from = v)) } }
            NumRow("Base to", lc.base.to, 0.0, 10000.0, "ft", 100.0) { v -> setC { it.copy(base = it.base.copy(to = v)) } }
            NumRow("Size from", lc.size.from, 0.0, 5.0, "", 0.1) { v -> setC { it.copy(size = it.size.copy(from = v)) } }
            NumRow("Size to", lc.size.to, 0.0, 5.0, "", 0.1) { v -> setC { it.copy(size = it.size.copy(to = v)) } }
            // fair weather is FEW to BKN; poor and inclement SCT to OVC — WeatherGen's own limits, and BMS's editor's
            val covers = if (type == WxType.FAIR) listOf(WxCover.FEW, WxCover.SCATTERED, WxCover.BROKEN)
            else listOf(WxCover.SCATTERED, WxCover.BROKEN, WxCover.OVERCAST)
            WrapRow("Cover from") {
                for (c in covers) EditorChip(c.metar, selected = lc.coverFrom == c) {
                    setC { it.copy(coverFrom = c, coverTo = if (it.coverTo.value < c.value) c else it.coverTo) }
                }
            }
            WrapRow("Cover to") {
                for (c in covers) EditorChip(c.metar, selected = lc.coverTo == c) {
                    setC { it.copy(coverTo = c, coverFrom = if (it.coverFrom.value > c.value) c else it.coverFrom) }
                }
            }
            val ths = WxTowering.thresholds(type)
            val chosen = ths.indices.minByOrNull { abs(ths[it] - lc.towering) } ?: 0
            WrapRow("Towering") {
                WxTowering.NAMES.forEachIndexed { k, n -> EditorChip(n, selected = k == chosen) { setC { it.copy(towering = ths[k]) } } }
            }
        }

        Text("WINDS ALOFT", Modifier.padding(start = 4.dp, top = 10.dp), color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        WrapRow("Level") {
            WxDefaults.WIND_ALTS.drop(1).forEachIndexed { k, alt -> EditorChip(altLabel(alt), selected = level == k) { level = k } }
        }
        val alt = WxDefaults.WIND_ALTS[level + 1]
        val a = p.windsAloft.firstOrNull { it.altFt == alt } ?: WxDefaults.ALOFT[level]
        fun setA(f: (WxAloft) -> WxAloft) = st.edit { q ->
            val list = if (q.windsAloft.any { it.altFt == alt }) q.windsAloft else q.windsAloft + a
            q.copy(windsAloft = list.map { if (it.altFt == alt) f(it) else it })
        }
        NumRow("Faster by, from", a.speed.from, -100.0, 100.0, "kt", 1.0) { v -> setA { it.copy(speed = it.speed.copy(from = v)) } }
        NumRow("Faster by, to", a.speed.to, -100.0, 100.0, "kt", 1.0) { v -> setA { it.copy(speed = it.speed.copy(to = v)) } }
        NumRow("Toward prevailing", a.bias, 0.0, 1.0, "", 0.05) { v -> setA { it.copy(bias = v) } }
        Note("Each level blows this much faster than the surface wind, turned this far toward the prevailing wind (0 not at all, 1 all the way).")
    }
}

/** Min, mean and max, kept in that order: moving one past its neighbour carries the neighbour along. */
@Composable
private fun SpreadRows(name: String, s: WxSpread, lo: Double, hi: Double, unit: String, onChange: (WxSpread) -> Unit) {
    NumRow("$name min", s.min, lo, hi, unit, 1.0) { v -> onChange(WxSpread(v, maxOf(s.mean, v), maxOf(s.max, v))) }
    NumRow("$name mean", s.mean, lo, hi, unit, 1.0) { v -> onChange(WxSpread(minOf(s.min, v), v, maxOf(s.max, v))) }
    NumRow("$name max", s.max, lo, hi, unit, 1.0) { v -> onChange(WxSpread(minOf(s.min, v), minOf(s.mean, v), v)) }
}

// ------------------------------------------------------------------------------------------------ 5-6

@Composable
private fun AdvancedPanel(st: WxGenState) {
    val p = st.params
    Fold("Advanced", "advanced") {
        NumRow("X offset", p.originX, 0.0, 10000.0, "", 1.0) { v -> st.edit { it.copy(originX = v) } }
        NumRow("Y offset", p.originY, 0.0, 10000.0, "", 1.0) { v -> st.edit { it.copy(originY = v) } }
        NumRow("T offset", p.timeOffset, 0.0, 10000.0, "", 1.0) { v -> st.edit { it.copy(timeOffset = v) } }
        NumRow("Evolution", p.evolution, 60.0, 10000.0, "min", 10.0) { v -> st.edit { it.copy(evolution = v) } }
        NumRow("Wind uniformity", p.windUniformity, 0.0, 1.0, "", 0.05) { v -> st.edit { it.copy(windUniformity = v) } }
        NumRow("Temp uniformity", p.tempUniformity, 0.0, 1.0, "", 0.05) { v -> st.edit { it.copy(tempUniformity = v) } }
        NumRow("Warp strength", p.turbulencePower, 0.0, 1000.0, "", 5.0) { v -> st.edit { it.copy(turbulencePower = v) } }
        NumRow("Crossfade", p.crossfade, 0.0, 0.5, "", 0.01) { v -> st.edit { it.copy(crossfade = v) } }
        NumRow("Zoom", p.featureSize, 1.0, 40.0, "", 0.5) { v -> st.edit { it.copy(featureSize = v) } }
        NumRow("Pressure speed", p.pressureSpeed, 1.0, 10000.0, "", 1.0) { v -> st.edit { it.copy(pressureSpeed = v) } }
        Note(
            "Offsets move the grid through the pattern; evolution is how many minutes the pattern takes to change " +
                "completely while standing still; zoom is the size of the weather systems; crossfade blends one type " +
                "into the next (0.1 looks right, above 0.3 strange). Temp uniformity is kept for WeatherGen's settings — " +
                "its temperature does not use it.",
        )
    }
}

@Composable
private fun TimePanel(st: WxGenState) {
    val p = st.params
    var target by remember { mutableStateOf(p.current) }
    Fold("Time", "time") {
        Text("NOW  " + p.current.label, Modifier.padding(start = 4.dp, top = 2.dp), color = Hud.Amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        TimeRow("Time", target, 15) { target = it }
        WrapRow(null) {
            EditorChip("Jump to") { st.edit { WxModel.jumpToTime(it, target) } }
            EditorChip("Set to") { st.edit { WxModel.setTime(it, target) } }
        }
        Note("Jump to moves the weather on (or back) to that time. Set to relabels the clock and leaves the weather exactly as it is — to make this weather the weather at your mission's start.")
        NumRow("Step", p.movement.stepMin, 55.0, 360.0, "min", 5.0) { v -> st.edit { it.copy(movement = it.movement.copy(stepMin = v)) } }
        Note("BMS disregards update maps less than 55 minutes apart (Technical Manual 13.6.1), so a step is never shorter. 60 keeps a series on the hour, the names BMS's own update maps have.")
    }
}

// ------------------------------------------------------------------------------------------------ 7 save

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavePanel(
    wx: WxTheater?,
    st: WxGenState,
    online: Boolean,
    busy: Boolean,
    error: String?,
    onBusy: (Boolean) -> Unit,
    onState: (WeatherState?) -> Unit,
    reload: () -> Unit,
    noSave: String? = null,
) {
    val scope = rememberCoroutineScope()
    val p = st.params
    var name by remember { mutableStateOf(Repo.getString("wx_gen_name") ?: "My weather") }
    var from by remember { mutableStateOf(p.current) }
    var to by remember { mutableStateOf(p.current.plusMinutes(12 * 60.0)) }
    // a call that got no answer at all says so: a button that does nothing and says nothing is the failure to avoid
    var noAnswer by remember { mutableStateOf(false) }
    // why a map's settings could not be opened
    var openNote by remember { mutableStateOf<String?>(null) }
    fun call(block: suspend () -> WeatherState?) {
        onBusy(true)
        Repo.putString("wx_gen_name", name)
        scope.launch {
            val r = block()
            noAnswer = r == null
            onState(r)
            onBusy(false)
        }
    }
    Fold("Save", "save", initiallyOpen = true) {
        when {
            !online || wx == null -> Note(
                noSave.takeIf { online }
                    ?: ("Saving writes into Falcon BMS, so it needs the PC that runs it: connect to it (Setup) and the buttons " +
                        "appear here. The preview works without it."),
                Hud.TextDim,
            )
            // listed so the pilot sees it, never written: its campaign folder is not there, or is another theater's
            wx.blocked != null -> Note(wx.blocked + " Nothing can be saved for this theater. The preview works without it.", Hud.TextDim)
            !wx.backedUp -> {
                Note(
                    if (wx.stockMaps) {
                        "Before anything is written, BMS's four ready-made weather maps for this theater (Sunny, Fair, Poor, " +
                            "Inclement) are copied into a folder beside them called \"BMS Companion Backup\", with a note " +
                            "saying how to put everything back by hand."
                    } else {
                        "This theater ships none of BMS's four ready-made weather maps, so there is nothing to copy first. " +
                            "Enabling it makes the folder \"BMS Companion Backup\" beside its campaign files, with a note " +
                            "saying how to undo everything by hand; every file written is listed there."
                    },
                    Hud.TextDim,
                )
                ActionButton(if (busy) "Copying…" else if (wx.stockMaps) "Back up this theater and enable" else "Enable this theater", enabled = !busy) {
                    call { MissionLink.weatherBackUp(wx.id).also { reload() } }
                }
            }
            else -> {
                val mapName = "BMSC " + name.trim().ifEmpty { "<name>" }
                // BMS's own update maps: null from an older PC, which is taken as "there may be some"
                val bmsMaps = wx.bmsUpdates
                Text("MAP", Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp), color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                TextInput(name, { name = it.take(40) }, Modifier.fillMaxWidth(), placeholder = "Map name")
                Spacer(Modifier.height(8.dp))
                ActionButton(if (busy) "Writing…" else "Save map as \"BMSC ${name.trim()}\"", enabled = !busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    call { MissionLink.weatherGenerate(wx.id, name.trim(), st.params) }
                }
                Note(
                    "One map, the weather at ${p.current.label}, beside BMS's own maps (which are left as they are)." +
                        (if (bmsMaps != 0) " Fly it with MAPS AUTO UPDATE off, or BMS's own hourly maps take over within the hour." else "") +
                        (if (!wx.stockMaps) " " + gridNote(wx.gridFrom) else ""),
                )

                Text("SERIES", Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp), color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                val step = p.movement.stepMin.roundToInt().coerceAtLeast(55)
                MissionClockRow(wx, busy) { t0 ->
                    // the weather shown becomes the weather at the mission's hour (Time → Set to), and the series starts
                    // there, keeping its length
                    val span = (to.minutes - from.minutes).coerceAtLeast(step)
                    st.edit { WxModel.setTime(it, t0) }
                    from = t0
                    to = t0.plusMinutes(span.toDouble())
                }
                TimeRow("From", from, step) { from = it }
                TimeRow("To", to, step) { to = it }
                val count = if (to.minutes < from.minutes) 0 else (to.minutes - from.minutes) / step + 1
                ActionButton(
                    if (busy) "Writing…" else "Save series: $count maps, every $step min",
                    enabled = !busy && name.isNotBlank() && count in 1..240,
                    modifier = Modifier.fillMaxWidth(),
                ) { call { MissionLink.weatherSeries(wx.id, name.trim(), st.params, from, to, step) } }
                Note(
                    "The first map as \"$mapName\", then one update map a step into WeatherMapsUpdates, for every mission " +
                        "in this theater flown with MAPS AUTO UPDATE on. BMS's own maps of the same names are copied aside " +
                        "first; Restore update maps puts them back." + bmsUpdatesNote(wx),
                )
                // a start or a step off the hour interleaves with BMS's hourly maps, and BMS drops a map under 55 min
                // after the one before
                if (bmsMaps != 0 && (from.minute != 0 || step % 60 != 0)) {
                    Note(
                        "Off the hour: BMS's own hourly maps fall between these, and BMS disregards a map less than " +
                            "55 minutes after the one before, so some maps of either may be skipped. Start on the hour " +
                            "with a 60 min step to replace BMS's maps one for one.",
                        Hud.Amber,
                    )
                }

                val maps = wx.added.filter { !it.contains('/') }
                val updates = wx.replaced.size + wx.added.count { it.contains('/') }
                if (maps.isNotEmpty() || updates > 0) {
                    Text("WRITTEN BY THIS APP", Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp), color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    for (m in maps) {
                        Row(Modifier.fillMaxWidth().heightIn(min = ROW).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m, Modifier.weight(1f), color = Hud.Text, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                            // the settings the map was made from, back into the generator: to see it, change it, save it again
                            EditorChip("Open", enabled = !busy) {
                                scope.launch {
                                    val r = pcSavedParams(wx.id, m.removeSuffix(".fmap"))
                                    if (r == null) {
                                        openNote = "The PC has no settings kept with $m (a map written by an earlier version keeps none)."
                                    } else {
                                        st.params = r.second
                                        st.origin = WxOrigin.Pc(r.first)
                                        st.clearSelection()
                                        name = r.first.removePrefix("BMSC ").trim()
                                        openNote = null
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            EditorChip("Remove", tint = Hud.Red, enabled = !busy) {
                                call { MissionLink.weatherRemoveGenerated(wx.id, m.removeSuffix(".fmap")) }
                            }
                        }
                    }
                    openNote?.let { Note(it, Hud.Red) }
                    if (updates > 0) {
                        Spacer(Modifier.height(6.dp))
                        ActionButton("Restore update maps ($updates)", enabled = !busy, danger = true) {
                            call { MissionLink.weatherRestoreSeries(wx.id) }
                        }
                    }
                }
                error?.let { Note(it, Hud.Red) }
                if (noAnswer) Note("The PC did not answer. Check the connection and try again.", Hud.Red)
                InBms(mapName, bmsMaps != 0)
            }
        }
        SettingsBox(st)
    }
}

/**
 * What a pilot does in BMS for a map written here to be flown — the only way it reaches a mission, for a campaign as
 * for a TE. BMS reads no map at all under its Probabilistic and Deterministic models, which is what every campaign
 * flies until it is switched; and a mission that saved a map keeps its own copy, so writing the map again here does
 * not reach it.
 */
@Composable
private fun InBms(map: String, hourlyMaps: Boolean) {
    Text("IN BMS, WITH THE CAMPAIGN OR TE LOADED", Modifier.padding(start = 4.dp, top = 12.dp, bottom = 3.dp), color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Step("1", "Weather → WEATHER tab → Weather Model: Map Model")
    Step("2", "Pick \"$map\" (BMS says MAP LOADED)")
    Step("3", "MAPS AUTO UPDATE: on for a series, off for a single map" + if (hourlyMaps) " (or BMS's hourly maps take over)" else "")
    Step("4", "SAVE WTH (a TE must have been saved once), then save the TE or campaign")
    Note(
        "A campaign flies BMS's own four-type weather until this is done. Saving this map again here does not change a " +
            "mission that already saved it: pick it and save again.",
    )
}

/** One numbered step of [InBms]. */
@Composable
private fun Step(n: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.5.dp)) {
        Text(n, Modifier.width(16.dp), color = Hud.Amber, fontSize = 11.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold)
        Text(text, color = Hud.TextDim, fontSize = 11.5.sp, lineHeight = 15.sp)
    }
}

/** Where a theater without the four ready-made maps takes a generated map's grid from, in words. */
private fun gridNote(gridFrom: String?): String = when (gridFrom) {
    null -> ""
    "built-in" -> "This theater ships no weather map, so the map has BMS's usual 59 x 59 grid."
    else -> "This theater has none of BMS's ready-made maps; the grid is taken from $gridFrom."
}

/** BMS's own update maps in the theater, as the series note ends: they go on loading round a series. */
private fun bmsUpdatesNote(wx: WxTheater): String {
    val n = wx.bmsUpdates ?: return " BMS's own hourly update maps still load before, after and between these times."
    if (n == 0) return " This theater has no update maps of BMS's own."
    val span = wx.bmsUpdatesFirst?.let { a -> wx.bmsUpdatesLast?.let { b -> " (${a.label} to ${b.label})" } }.orEmpty()
    return " BMS's own $n update maps$span still load before, after and between these times."
}

/**
 * The series' start at the mission's clock: the clock of the save the Planner has open (or of the printed briefing's
 * save, once the Planner has found it), else a time the pilot types — the DataCard's Current Time as it reads ("1,
 * 01:02:16"), "Day 1 01:02" or BMS's "10102". The start is that hour ([CampaignTime.floorHour]), so the series keeps
 * to BMS's hourly names.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MissionClockRow(wx: WxTheater, busy: Boolean, onStart: (CampaignTime) -> Unit) {
    val known = missionClock(wx.name)
    var typed by remember { mutableStateOf("") }
    val parsed = CampaignTime.parse(typed)
    val start = (parsed ?: known?.first)?.floorHour()
    WrapRow("Mission clock") {
        TextInput(
            typed, { typed = it.take(20) }, Modifier.width(128.dp).align(Alignment.CenterVertically),
            placeholder = known?.first?.label ?: "Day 1 01:02",
        )
        EditorChip(start?.let { "Start series at ${it.label}" } ?: "Start series there", enabled = start != null && !busy) {
            start?.let(onStart)
        }
    }
    Note(
        when {
            typed.isNotBlank() && parsed == null -> "Type the mission's day and time, e.g. Day 1 01:02 (the clock at the top of BMS's campaign screen, or the DataCard's Current Time)."
            known != null && typed.isBlank() -> "${known.first.label} is the clock of ${known.second}. The series starts on that hour, and the generator's clock is set to it without changing the weather shown."
            else -> "The clock of the mission's save: type it as BMS's campaign screen shows it at the top, or open the mission in the Planner first (the DataCard's Current Time). The series starts on that hour, and the generator's clock is set to it without changing the weather shown."
        },
    )
}

/**
 * The clock of the mission being planned in [theater] (a theater definition's name, compared by letters and digits),
 * with where it came from: the save open in the Planner (Open mission…), else the save holding BMS's printed briefing
 * when the Planner has already looked it up. Null when neither is known, or it is another theater's.
 */
private fun missionClock(theater: String): Pair<CampaignTime, String>? {
    fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    val want = key(theater).takeIf { it.isNotEmpty() } ?: return null
    val pm = PlannerMissionState
    if (pm.fromSave && pm.theater?.let { key(it) } == want) {
        (pm.flight?.clock ?: pm.file?.clock)?.let { return CampaignTime.ofClockMs(it) to "${pm.ref?.file ?: "the save"}, open in the Planner" }
    }
    val b = MissionLink.bmsFiles.value?.briefing ?: return null
    val found = BriefedSave.known(BriefedSave.key(b))?.takeIf { key(it.theater) == want } ?: return null
    return found.clock?.let { CampaignTime.ofClockMs(it) to "${found.file}, the printed briefing's save" }
}

/**
 * The settings as text, WeatherGen's `.vmtw` in spirit: every parameter in a few hundred bytes of JSON, to keep, to
 * send to a wingman, or to carry from the phone to the PC. Loading takes anything this app wrote, and anything it
 * does not recognise is ignored rather than refused.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsBox(st: WxGenState) {
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    Text("SETTINGS", Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp), color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    WrapRow(null) {
        EditorChip("Show as text") { text = Repo.json.encodeToString(WxGenParams.serializer(), st.params); msg = "Copy this text to keep it or share it." }
        EditorChip("Load text", enabled = text.isNotBlank()) {
            val p = runCatching { Repo.json.decodeFromString(WxGenParams.serializer(), text) }.getOrNull()
            if (p == null) msg = "That text is not a set of weather settings."
            else { st.edit { p }; st.editing = null; msg = "Loaded." }
        }
    }
    TextInput(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 160.dp), singleLine = false, placeholder = "Paste settings here to load them")
    msg?.let { Note(it) }
    Note("These settings are kept on this device as you change them, for each theater separately.")
}
