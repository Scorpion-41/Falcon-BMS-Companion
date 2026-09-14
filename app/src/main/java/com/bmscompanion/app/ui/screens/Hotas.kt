package com.bmscompanion.app.ui.screens

import androidx.compose.animation.animateContentSize
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.components.AssetImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.HotasButton
import com.bmscompanion.app.data.HotasControl
import com.bmscompanion.app.data.HotasFile
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.ChipRow
import com.bmscompanion.app.ui.components.CollapsibleCard
import com.bmscompanion.app.ui.components.ContentColumn
import com.bmscompanion.app.ui.components.LoadingBox
import com.bmscompanion.app.ui.components.Paragraph
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.Tag
import com.bmscompanion.app.ui.components.TagFlow
import com.bmscompanion.app.ui.components.isMedium
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlin.math.hypot

/**
 * Hotspot positions (x,y normalised to the image) on the real HOTAS illustrations:
 * F-16 line drawings from TO 1F-16CMAM-34-1-1 BMS (Hands-On Controls) and F-15C renders from TO 1F-15C-1 BMS.
 */
private data class GripImage(val path: String, val aspect: Float, val spots: Map<String, Offset>, val full: String? = null)

private val grips: Map<String, GripImage> = mapOf(
    "f16/stick" to GripImage(
        "img/hotas/f16_stick_grip.webp", 1000f / 1039f,
        mapOf(
            "trim" to Offset(0.486f, 0.133f), "pickle" to Offset(0.381f, 0.228f), "dms" to Offset(0.575f, 0.291f),
            "tms" to Offset(0.464f, 0.361f), "cms" to Offset(0.536f, 0.561f), "trigger" to Offset(0.319f, 0.478f),
            "paddle" to Offset(0.272f, 0.728f), "expand" to Offset(0.45f, 0.778f), "nws" to Offset(0.30f, 0.30f),
        ),
        full = "img/hotas/f16_stick.webp",
    ),
    "f16/throttle" to GripImage(
        "img/hotas/f16_throttle_grip.webp", 1000f / 713f,
        mapOf(
            "mru" to Offset(0.625f, 0.167f), "comm" to Offset(0.506f, 0.247f), "dogfight" to Offset(0.702f, 0.30f),
            "antelev" to Offset(0.729f, 0.44f), "cursor" to Offset(0.677f, 0.597f), "speedbrake" to Offset(0.62f, 0.50f),
            "hobo" to Offset(0.427f, 0.82f),
        ),
        full = "img/hotas/f16_throttle.webp",
    ),
    "f15c/stick" to GripImage(
        "img/hotas/f15c_stick.webp", 700f / 1273f,
        mapOf(
            "trim" to Offset(0.71f, 0.163f), "castle" to Offset(0.525f, 0.20f), "pickle" to Offset(0.295f, 0.30f),
            "trigger" to Offset(0.33f, 0.425f), "autoacq" to Offset(0.495f, 0.525f), "nws" to Offset(0.395f, 0.763f),
            "paddle" to Offset(0.47f, 0.913f),
        ),
    ),
    "f15c/throttle" to GripImage(
        "img/hotas/f15c_throttle.webp", 1000f / 721f,
        mapOf(
            "weaponmode" to Offset(0.14f, 0.556f), "boat" to Offset(0.14f, 0.435f), "speedbrake" to Offset(0.15f, 0.339f),
            "mic" to Offset(0.155f, 0.234f), "coolie" to Offset(0.25f, 0.234f), "tdc" to Offset(0.37f, 0.177f),
            "sbr" to Offset(0.48f, 0.185f), "antelev" to Offset(0.555f, 0.105f), "dispense" to Offset(0.565f, 0.266f),
            "fingerlift" to Offset(0.51f, 0.371f),
        ),
    ),
)

private val functionCharts = mapOf(
    "f16" to listOf(
        "Air-to-Air · Stick" to "img/hotas/f16_aa_stick.webp",
        "Air-to-Air · Throttle" to "img/hotas/f16_aa_throttle.webp",
        "Air-to-Ground · Stick" to "img/hotas/f16_ag_stick.webp",
        "Air-to-Ground · Throttle" to "img/hotas/f16_ag_throttle.webp",
    ),
)

@Composable
fun HotasScreen(nav: NavHostController, ac: String) {
    var aircraft by rememberSaveable { mutableStateOf(ac) }
    val file by produceState<HotasFile?>(null, aircraft) { value = Repo.hotas(aircraft) }
    var controlIdx by rememberSaveable(aircraft) { mutableIntStateOf(0) }
    var selectedBtn by rememberSaveable(aircraft, controlIdx) { mutableStateOf<String?>(null) }
    var mode by rememberSaveable(aircraft) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        BmsTopBar("HOTAS", file?.aircraft ?: "Real aircraft controls", onBack = { nav.popBackStack() })
        ChipRow(listOf("f16", "f15c"), aircraft, { if (it == "f16") "F-16C/D" else "F-15C" }, { if (it != null) aircraft = it }, allLabel = null)
        val f = file ?: run { LoadingBox(); return@Column }
        TabRow(
            selectedTabIndex = controlIdx, containerColor = Hud.Bg, contentColor = Hud.Amber,
            indicator = { pos -> if (controlIdx < pos.size) TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[controlIdx]), color = Hud.Amber) },
        ) {
            f.controls.forEachIndexed { i, c -> Tab(controlIdx == i, { controlIdx = i }, text = { Text(c.name, maxLines = 1) }, unselectedContentColor = Hud.TextDim) }
        }
        val control = f.controls.getOrNull(controlIdx) ?: return@Column
        val modes = remember(f) {
            f.controls.flatMap { c -> c.buttons.flatMap { b -> b.actions.flatMap { a -> a.byMode.map { it.mode.trim() } } } }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(24)
        }
        val medium = isMedium()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            ContentColumn(maxWidth = 1200.dp) {
                if (modes.isNotEmpty()) {
                    Text("FILTER BY MODE / SENSOR", style = LocalExtra.current.overline, color = Hud.TextDim)
                    ChipRow(modes, mode, { it }, { mode = it }, allLabel = "All modes", contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp))
                }
                if (medium) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(0.9f)) { GripDiagram(nav, aircraft, control, selectedBtn) { selectedBtn = it } }
                        Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ButtonDetails(control, selectedBtn, mode)
                        }
                    }
                } else {
                    GripDiagram(nav, aircraft, control, selectedBtn) { selectedBtn = it }
                    ButtonDetails(control, selectedBtn, mode)
                }
                FunctionCharts(nav, aircraft)
                if (f.overview.isNotBlank()) CollapsibleCard("HOTAS philosophy & overview", accent = Hud.Cyan, preview = f.overview) { Paragraph(f.overview) }
                if (f.sourceDocs.isNotEmpty()) Text("Sources: " + f.sourceDocs.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint)
            }
        }
    }
}

@Composable
private fun ButtonDetails(control: HotasControl, selected: String?, mode: String?) {
    val sel = control.buttons.firstOrNull { it.id == selected }
    if (sel != null) {
        ButtonCard(control.buttons.indexOf(sel) + 1, sel, mode, expanded = true)
    } else {
        Text("Tap a numbered control on the diagram, or browse below.", style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
    }
    control.buttons.forEachIndexed { i, b -> if (b.id != selected) ButtonCard(i + 1, b, mode, expanded = false) }
}

@Composable
private fun ButtonCard(number: Int, b: HotasButton, mode: String?, expanded: Boolean) {
    var open by rememberSaveable(b.id, expanded) { mutableStateOf(expanded) }
    val border = if (expanded) Hud.Amber else Hud.Outline.copy(alpha = 0.6f)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Hud.Surface).border(1.dp, border, RoundedCornerShape(16.dp))
            .clickable { open = !open }.animateContentSize().padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(if (expanded) Hud.Amber else Hud.Surface3), contentAlignment = Alignment.Center) {
                Text("$number", color = if (expanded) Hud.Bg else Hud.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(b.name, style = MaterialTheme.typography.titleSmall, color = if (expanded) Hud.Amber else Hud.Text)
                if (b.summary.isNotBlank()) Text(b.summary, style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
            }
            if (b.kind.isNotBlank()) Tag(b.kind, Hud.Cyan)
        }
        // Mode quick answer
        if (mode != null) {
            val hits = b.actions.flatMap { a -> a.byMode.filter { it.mode.trim() == mode }.map { a.input to it.effect } }
            if (hits.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                hits.forEach { (input, effect) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(input, Modifier.widthIn(min = 90.dp, max = 140.dp), style = LocalExtra.current.monoSmall, color = Hud.Green)
                        Spacer(Modifier.width(8.dp))
                        Text(effect, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (!open) {
                Text("No $mode-specific function listed.", style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            b.actions.forEach { a ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).padding(10.dp)) {
                    Text(a.input, style = LocalExtra.current.mono, color = Hud.Green, fontWeight = FontWeight.Bold)
                    if (a.general.isNotBlank()) Text(a.general, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                    a.byMode.forEach { m ->
                        val hl = mode != null && m.mode.trim() == mode
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).background(if (hl) Hud.Amber.copy(alpha = 0.12f) else Color.Transparent).padding(4.dp)) {
                            Text(m.mode, Modifier.width(110.dp), fontSize = 12.sp, color = if (hl) Hud.Amber else Hud.Cyan, fontWeight = FontWeight.SemiBold)
                            Text(m.effect, style = MaterialTheme.typography.bodySmall, color = Hud.Text)
                        }
                    }
                }
            }
            b.bmsNotes?.takeIf { it.isNotBlank() }?.let {
                Text("BMS NOTE", style = LocalExtra.current.overline, color = Hud.Amber, modifier = Modifier.padding(top = 8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
            }
            b.source?.let { Text("Source: $it", style = MaterialTheme.typography.labelSmall, color = Hud.TextFaint, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}


@Composable
private fun GripDiagram(nav: NavHostController, aircraft: String, control: HotasControl, selected: String?, onSelect: (String) -> Unit) {
    val grip = grips["$aircraft/${control.id}"]
    val tm = rememberTextMeasurer()
    SectionCard(control.name, accent = Hud.Green) {
        if (grip != null) {
            val maxH = if (grip.aspect < 1f) 520.dp else 380.dp
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFF4F6F8)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.heightIn(max = maxH).aspectRatio(grip.aspect)) {
                    AssetImage(grip.path, Modifier.fillMaxSize(), ContentScale.Fit)
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(control, grip) {
                            detectTapGestures { p ->
                                val hit = grip.spots.entries.filter { e -> control.buttons.any { it.id == e.key } }
                                    .minByOrNull { (_, o) -> hypot(p.x - o.x * size.width, p.y - o.y * size.height) }
                                if (hit != null && hypot(p.x - hit.value.x * size.width, p.y - hit.value.y * size.height) < 36.dp.toPx()) onSelect(hit.key)
                            }
                        },
                    ) {
                        val r = (size.width * 0.028f).coerceIn(8.dp.toPx(), 13.dp.toPx())
                        control.buttons.forEachIndexed { i, b ->
                            val o = grip.spots[b.id] ?: return@forEachIndexed
                            val c = Offset(o.x * size.width, o.y * size.height)
                            val sel = b.id == selected
                            if (sel) drawCircle(Hud.Amber.copy(alpha = 0.35f), r * 2.1f, c)
                            drawCircle(Color.Black.copy(alpha = 0.55f), r + 2.dp.toPx(), c)
                            drawCircle(if (sel) Hud.Amber else Color(0xFF1FA35A), r, c)
                            val t = tm.measure("${i + 1}", TextStyle(color = Color.White, fontSize = (11f / fontScale).sp, fontWeight = FontWeight.Bold))
                            drawText(t, topLeft = Offset(c.x - t.size.width / 2, c.y - t.size.height / 2))
                        }
                    }
                }
            }
            val missing = control.buttons.filter { it.id !in grip.spots }
            if (missing.isNotEmpty()) Text(
                "Not shown on the illustration: " + missing.joinToString { "${control.buttons.indexOf(it) + 1} " + it.name.substringBefore(" (") },
                style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint, modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                if (grip.full != null) "⤢ Open labelled manual drawing" else "⤢ Open full screen to zoom",
                Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable { nav.go(chartRoute(grip.full ?: grip.path, (if (aircraft == "f16") "F-16" else "F-15C") + " HOTAS · " + control.name)) }.padding(vertical = 4.dp),
                color = Hud.Cyan, style = MaterialTheme.typography.labelLarge,
            )
            Text(
                if (aircraft == "f16") "Illustration: TO 1F-16CMAM-34-1-1 BMS, Hands-On Controls" else "Illustration: TO 1F-15C-1 BMS",
                style = MaterialTheme.typography.labelSmall, color = Hud.TextFaint, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        TagFlow { control.buttons.forEachIndexed { i, b -> Box(Modifier.clickable { onSelect(b.id) }) { Tag("${i + 1} ${b.name.substringBefore(" (")}", if (b.id == selected) Hud.Amber else Hud.TextDim, filled = b.id == selected) } } }
    }
}

@Composable
private fun FunctionCharts(nav: NavHostController, aircraft: String) {
    val charts = functionCharts[aircraft] ?: return
    SectionCard("Dash-34 HOTAS function charts", accent = Hud.Cyan) {
        Text("Every switch position with its function and key-file callback, per master mode. Tap to open full screen and zoom.", style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            charts.forEach { (title, path) ->
                Column(Modifier.width(170.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).clickable { nav.go(chartRoute(path, "F-16 HOTAS · $title")) }) {
                    Box(Modifier.fillMaxWidth().height(120.dp).background(Color.White)) {
                        AssetImage(path, Modifier.fillMaxSize().padding(2.dp), ContentScale.Fit, sample = 4)
                    }
                    Text(title, Modifier.padding(8.dp), fontSize = 12.sp, maxLines = 2)
                }
            }
        }
    }
}
