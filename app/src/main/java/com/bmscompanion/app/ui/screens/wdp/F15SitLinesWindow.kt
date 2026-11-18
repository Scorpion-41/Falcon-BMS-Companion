package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.wdp.DtcIni
import com.bmscompanion.app.data.wdp.F15SitLines
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.launch

/**
 * **F-15 SIT lines** (the toolbar's Options → F-15 SIT lines…; Falcon BMS 4.38.2): the F-15C's SIT display shows the
 * cartridge's four steerpoint lines and up to seven lines of its own, styled by the cartridge's `[F15_SIT_LINES]`
 * section, which BMS reads and nothing in BMS writes ([F15SitLines]). The window reads the pilot's cartridge, lets
 * each line be named, coloured, dashed and shown or hidden, fills a line of its own from what the mission knows (the
 * DTC page's line options: tanker and AWACS tracks, CAP and station boxes …) or from one of the four steerpoint lines,
 * and writes the section with **Save to DTC** — directly, as the Planner writes the cartridge, the rest of the file
 * untouched. The keys are not leftovers of a mission: they stay until changed here, like the EWS programs.
 */
object F15SitLinesWindowState {
    fun open() = PlannerWindows.show(PlannerWindow.F15_SIT)
}

@Composable
internal fun ColumnScope.F15SitLinesWindow(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<F15SitLines.Line>() }
    var text by remember { mutableStateOf<String?>(null) }
    var file by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<Int?>(null) }
    var dirty by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        busy = true
        val c = runCatching { MissionLink.cartridge() }.getOrNull()
        text = c?.text; file = c?.file
        lines.clear(); lines.addAll(F15SitLines.read(c?.text))
        status = when {
            c == null || !c.available -> c?.error ?: "Your cartridge could not be read from the PC."
            F15SitLines.present(c.text) -> "${c.file}: the SIT lines as saved."
            else -> "${c.file} has no SIT lines yet: BMS shows the steerpoint lines in its own colours until they are saved here."
        }
        dirty = false; busy = false
    }

    fun set(i: Int, l: F15SitLines.Line) { lines[i] = l; dirty = true }

    // what a line of its own can be filled from: the mission's lines, then the cartridge's four steerpoint lines
    val options = remember(text) {
        val mission = runCatching { WdpSession.dtc.lineOptions() }.getOrDefault(emptyList())
            .map { o -> o.label to o.points.map { F15SitLines.Point(it.north, it.east) } }
        val own = (0 until 4).mapNotNull { g ->
            val pts = (0 until 6).mapNotNull { k ->
                val f = DtcIni.read(text, "STPT", "lineSTPT_${g * 6 + k}").split(',').mapNotNull { it.trim().toDoubleOrNull() }
                if (f.size < 2 || (f[0] == 0.0 && f[1] == 0.0)) null else F15SitLines.Point(f[0], f[1])
            }
            if (pts.size >= 2) "Steerpoint line ${g + 1} (${pts.size} points)" to pts else null
        }
        mission + own
    }

    Column(
        Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SitNote(
            "The F-15C's SIT display shows your cartridge's four steerpoint lines and up to seven lines of its own " +
                "(Falcon BMS 4.38.2). Name, colour and dash them here, then Save to DTC and LOAD in BMS. The F-16 is not affected.",
        )
        SitHeading("Steerpoint lines 1-4")
        lines.forEachIndexed { i, l -> if (!l.custom) SitLineRow(l, null, picking == i, { picking = if (picking == i) null else i }, { set(i, it) }) }
        SitHeading("Lines of your own (6-12)")
        lines.forEachIndexed { i, l ->
            if (l.custom) {
                SitLineRow(l, options, picking == i, { picking = if (picking == i) null else i }, { set(i, it) })
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            status ?: "", color = Hud.TextDim, fontSize = fingerSp(11.5f), lineHeight = 15.sp,
            modifier = Modifier.weight(1f).plannerProbe("SitLines/Status"),
        )
        PlannerButton("Read the cartridge", probe = "SitLines/Read", enabled = !busy) { reload++ }
        PlannerButton(if (busy) "Saving…" else "Save to DTC", probe = "SitLines/Save", primary = dirty, enabled = !busy && text != null) {
            busy = true
            scope.launch {
                val r = runCatching { MissionLink.cartridgeSave(null, F15SitLines.edits(lines.toList())) }.getOrNull()
                status = when {
                    r == null -> "Not saved: the PC could not be reached."
                    r.error != null -> "Not saved: ${r.error}"
                    else -> "${r.file ?: file}: the SIT lines are saved. LOAD the cartridge in BMS's DTC window to fly them."
                }
                if (r?.error == null && r != null) { text = r.text; dirty = false }
                busy = false
            }
        }
    }
}

/** One SIT line: name, shown, colour, dash, and for a line of its own its points and where they came from. */
@Composable
private fun SitLineRow(
    l: F15SitLines.Line,
    options: List<Pair<String, List<F15SitLines.Point>>>?,
    picking: Boolean,
    onPick: () -> Unit,
    onChange: (F15SitLines.Line) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).plannerProbe("SitLines/Line${l.n}"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${l.n}", color = Hud.TextDim, fontSize = fingerSp(12f), fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
            // the name (BMS keeps 12 characters)
            Box(
                Modifier.width(150.dp).fingerHeight(30.dp).clip(RoundedCornerShape(5.dp)).background(Hud.Surface2)
                    .border(1.dp, Hud.Outline, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    l.name, { onChange(l.copy(name = it.take(F15SitLines.MAX_NAME).uppercase())) }, singleLine = true,
                    textStyle = TextStyle(color = Hud.Text, fontSize = fingerSp(12.5f)), cursorBrush = SolidColor(Hud.Amber),
                    modifier = Modifier.fillMaxWidth().plannerProbe("SitLines/Line${l.n}/Name"),
                )
                if (l.name.isEmpty()) Text(if (l.custom) "NAME" else "LINE ${l.n}", color = Hud.TextFaint, fontSize = fingerSp(12f))
            }
            // shown
            PlannerSegments(listOf("Shown", "Hidden"), listOf("SitLines/Line${l.n}/Shown", "SitLines/Line${l.n}/Hidden"), if (l.shown) 0 else 1) {
                onChange(l.copy(shown = it == 0))
            }
            if (l.custom) Text(
                if (l.points.size >= 2) "${l.points.size} points" + (l.from.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") else "no points",
                color = if (l.points.size >= 2) Hud.Text else Hud.TextFaint, fontSize = fingerSp(11.5f), maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Row(
            Modifier.padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // the colour, BMS's twelve MFD colours
            F15SitLines.COLORS.forEachIndexed { i, (name, argb) ->
                Box(
                    Modifier.size(if (WdpTouch.device) 26.dp else 20.dp).clip(RoundedCornerShape(4.dp)).background(Color(argb))
                        .border(if (i == l.color) 2.dp else 1.dp, if (i == l.color) Hud.Amber else Hud.Outline, RoundedCornerShape(4.dp))
                        .plannerPress { onChange(l.copy(color = i)) }.plannerProbe("SitLines/Line${l.n}/Color/$i")
                        .wdpTip("planner/SitLines/Color/$i", name, touch = true),
                )
            }
        }
        Row(Modifier.padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlannerSegments(F15SitLines.DASHES, F15SitLines.DASHES.indices.map { "SitLines/Line${l.n}/Dash/$it" }, l.dash) { onChange(l.copy(dash = it)) }
            if (options != null) {
                PlannerButton(if (picking) "Close" else "From the mission…", probe = "SitLines/Line${l.n}/From", small = true) { onPick() }
                if (l.points.isNotEmpty()) PlannerButton("Clear", probe = "SitLines/Line${l.n}/Clear", small = true) { onChange(l.copy(points = emptyList(), from = "")) }
            }
        }
        if (picking && options != null) Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (options.isEmpty()) SitNote("Nothing to take: open a mission, or put lines on the DTC page first.")
            options.forEachIndexed { k, (label, pts) ->
                Text(
                    "$label · ${pts.size} points", color = Hud.Text, fontSize = fingerSp(12f),
                    modifier = Modifier.fillMaxWidth().fingerHeight(28.dp).clip(RoundedCornerShape(4.dp)).background(Hud.Surface2)
                        .plannerPress {
                            val name = l.name.ifBlank { label.uppercase().take(F15SitLines.MAX_NAME) }
                            onChange(l.copy(points = pts.take(F15SitLines.MAX_POINTS), from = label, name = name)); onPick()
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp).plannerProbe("SitLines/Line${l.n}/Option/$k"),
                )
            }
        }
    }
}

@Composable
private fun SitHeading(text: String) {
    Text(text.uppercase(), color = Hud.TextFaint, fontSize = fingerSp(10f), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
}

@Composable
private fun SitNote(text: String) {
    Text(text, color = Hud.TextDim, fontSize = fingerSp(11.5f), lineHeight = 15.sp, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
}
