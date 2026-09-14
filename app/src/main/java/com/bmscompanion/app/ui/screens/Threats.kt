package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.EncyEntry
import com.bmscompanion.app.data.Labels
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Threat
import com.bmscompanion.app.data.ThreatFile
import com.bmscompanion.app.ui.Routes
import com.bmscompanion.app.ui.components.AdaptiveSplit
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.ChipRow
import com.bmscompanion.app.ui.components.CollapsibleCard
import com.bmscompanion.app.ui.components.ContentColumn
import com.bmscompanion.app.ui.components.FavoriteButton
import com.bmscompanion.app.ui.components.Fmt
import com.bmscompanion.app.ui.components.GroupHeader
import com.bmscompanion.app.ui.components.KeyValueRow
import com.bmscompanion.app.ui.components.ListDetail
import com.bmscompanion.app.ui.components.ListRow
import com.bmscompanion.app.ui.components.LoadingBox
import com.bmscompanion.app.ui.components.Paragraph
import com.bmscompanion.app.ui.components.RwrBadge
import com.bmscompanion.app.ui.components.SearchField
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.Stat
import com.bmscompanion.app.ui.components.StatGrid
import com.bmscompanion.app.ui.components.Tag
import com.bmscompanion.app.ui.components.TagFlow
import com.bmscompanion.app.ui.components.isWide
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.components.safeText
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import java.util.Locale
import kotlin.math.max

fun Threat.num(k: String): Double? = numbers[k]
fun Threat.maxRange(): Double? = num("maxRangeNm") ?: num("typicalRangeNm")
fun Threat.rwrSymbol(): String? = rwr["alr56m"]?.takeIf { it.isNotBlank() } ?: rwr["alr93"]?.takeIf { it.isNotBlank() }
fun sideColor(side: String) = if (side.equals("BLUEFOR", true)) Hud.Blue else Hud.Red

@Composable
fun ThreatsScreen(nav: NavHostController) {
    val threats by produceState<List<Threat>?>(null) { value = Repo.threats() }
    var q by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    var side by rememberSaveable { mutableStateOf<String?>(null) }
    var chart by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val wide = isWide()
    LaunchedEffect(wide, threats) { if (wide && selected == null) selected = threats?.let { l -> Labels.threatCategory.keys.firstNotNullOfOrNull { k -> l.firstOrNull { it.category == k } } }?.id }

    ListDetail(
        selected = selected != null,
        listWidth = 420.dp,
        list = {
            Column(Modifier.fillMaxSize()) {
                BmsTopBar("Threat Guide", "BMS 4.38 threat reference", actions = {
                    IconButton({ nav.go(Routes.HARM) }) { Icon(Icons.Default.Warning, "HARM & RWR", tint = Hud.Green) }
                    IconButton({ nav.go(Routes.ENCY) }) { Icon(Icons.Default.MenuBook, "Encyclopedia", tint = Hud.Cyan) }
                    IconButton({ chart = !chart }) { Icon(if (chart) Icons.Default.ViewList else Icons.Default.BarChart, "Chart") }
                })
                val all = threats ?: run { LoadingBox(); return@Column }
                val cats = remember(all) { Labels.threatCategory.keys.filter { k -> all.any { it.category == k } } }
                val filtered = remember(all, q, cat, side) {
                    val nq = q.norm()
                    all.filter { t ->
                        (cat == null || t.category == cat) && (side == null || t.side.equals(side, true)) &&
                            (nq.isEmpty() || t.name.norm().contains(nq) || t.aliases.any { it.norm().contains(nq) } ||
                                t.rwr.values.any { it?.norm() == nq } || (t.harmAlic?.contains(q.trim()) == true))
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SearchField(q, { q = it }, "Name, NATO name, RWR symbol, ALIC…")
                        }
                        ChipRow(cats, cat, { Labels.threatCategory[it] ?: it }, { cat = it })
                        Spacer(Modifier.height(6.dp))
                        ChipRow(listOf("OPFOR", "BLUEFOR"), side, { it }, { side = it }, allLabel = "Both sides")
                    }
                    if (chart) {
                        item { RangeChart(filtered) { id -> if (wide) selected = id else nav.go(Routes.threat(id)) } }
                    } else {
                        val groups = Labels.threatCategory.keys.mapNotNull { k -> filtered.filter { it.category == k }.takeIf { it.isNotEmpty() }?.let { k to it } }
                        groups.forEach { (k, list) ->
                            item(key = "h$k") { GroupHeader(Labels.threatCategory[k] ?: k, list.size) }
                            items(list, key = { it.id }) { t ->
                                ListRow(
                                    title = t.name,
                                    subtitle = listOfNotNull(
                                        t.type,
                                        t.maxRange()?.let { "max ${Fmt.nm(it)}" },
                                        t.num("maxAltFt")?.let { "${Fmt.num(it / 1000.0, 0)}k ft" },
                                    ).joinToString(" · "),
                                    selected = selected == t.id,
                                    leading = {
                                        Box(Modifier.size(width = 4.dp, height = 40.dp).background(sideColor(t.side), RoundedCornerShape(2.dp)))
                                        Spacer(Modifier.width(10.dp))
                                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                            RwrBadge(t.rwrSymbol() ?: "–", color = if (t.rwrSymbol() == null) Hud.TextFaint else Hud.Green)
                                        }
                                    },
                                    onClick = { if (wide) selected = t.id else nav.go(Routes.threat(t.id)) },
                                )
                            }
                        }
                        item { ThreatNotesFooter() }
                    }
                }
            }
        },
        detail = { selected?.let { ThreatDetail(nav, it, null) } },
    )
}

@Composable
private fun ThreatNotesFooter() {
    val files by produceState<List<ThreatFile>>(emptyList()) { value = Repo.threatFiles() }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val general = files.flatMap { it.generalNotes } + files.flatMap { it.notes.values.flatten() }
        if (general.isNotEmpty()) CollapsibleCard("General threat notes", accent = Hud.Red) {
            general.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
        }
        val cm = files.flatMap { it.cmEffect }
        if (cm.isNotEmpty()) CollapsibleCard("Countermeasure effect scale") { cm.forEach { KeyValueRow(it.effect, it.decoys.ifBlank { "—" } + " decoys", mono = true) } }
        val ab = files.flatMap { it.abbreviations }.distinctBy { it.abbr }
        if (ab.isNotEmpty()) CollapsibleCard("Abbreviations") { ab.forEach { KeyValueRow(it.abbr, it.meaning) } }
        val formations = files.flatMap { it.formations }
        if (formations.isNotEmpty()) CollapsibleCard("Aircraft formations") { formations.forEach { f -> KeyValueRow(f.name, f.description) } }
        val sensors = files.flatMap { it.sensors }
        if (sensors.isNotEmpty()) CollapsibleCard("Sensors") {
            sensors.forEach { s ->
                Text(s.name, style = MaterialTheme.typography.titleSmall, color = Hud.Amber, modifier = Modifier.padding(top = 8.dp))
                s.stats.forEach { KeyValueRow(it.label, it.value) }
                Paragraph(s.notes, Hud.TextDim)
            }
        }
        val ag = files.flatMap { it.agWeapons }
        if (ag.isNotEmpty()) CollapsibleCard("Air-to-ground weapons chart") {
            ag.forEach { s ->
                Text(s.name, style = MaterialTheme.typography.titleSmall, color = Hud.Amber, modifier = Modifier.padding(top = 8.dp))
                s.stats.forEach { KeyValueRow(it.label, it.value) }
                Paragraph(s.notes, Hud.TextDim)
            }
        }
    }
}

@Composable
private fun RangeChart(list: List<Threat>, onOpen: (String) -> Unit) {
    val rows = list.filter { it.maxRange() != null }.sortedByDescending { it.maxRange() }
    val maxR = rows.maxOfOrNull { it.maxRange()!! } ?: 1.0
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Hud.Amber, RoundedCornerShape(2.dp))); Text(" typical  ", fontSize = 11.sp, color = Hud.TextDim)
            Box(Modifier.size(10.dp).background(Hud.Red.copy(alpha = 0.45f), RoundedCornerShape(2.dp))); Text(" max engagement range (nm)", fontSize = 11.sp, color = Hud.TextDim)
        }
        Spacer(Modifier.height(8.dp))
        rows.forEach { t ->
            val mx = t.maxRange()!!
            val typ = t.num("typicalRangeNm")
            Row(Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, Modifier.width(120.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).height(14.dp)) {
                    Box(Modifier.fillMaxWidth((mx / maxR).toFloat().coerceIn(0.01f, 1f)).height(14.dp).clip(RoundedCornerShape(3.dp)).background(Hud.Red.copy(alpha = 0.45f)))
                    if (typ != null) Box(Modifier.fillMaxWidth((typ / maxR).toFloat().coerceIn(0.01f, 1f)).height(14.dp).clip(RoundedCornerShape(3.dp)).background(Hud.Amber))
                }
                Text(Fmt.num(mx, if (mx < 10) 1 else 0) ?: "", Modifier.width(44.dp).padding(start = 6.dp), style = LocalExtra.current.monoSmall)
            }
        }
    }
}

@Composable
fun ThreatDetailRoute(nav: NavHostController, id: String) = ThreatDetail(nav, id) { nav.popBackStack() }

@Composable
fun ThreatDetail(nav: NavHostController, id: String, onBack: (() -> Unit)?) {
    val t by produceState<Threat?>(null, id) { value = Repo.threats().firstOrNull { it.id == id } }
    val ency by produceState<EncyEntry?>(null, t) {
        val th = t ?: return@produceState
        val keys = (listOf(th.name) + th.aliases).map { it.norm() }.filter { it.length >= 3 }
        value = Repo.encyclopedia().firstOrNull { e -> val en = e.name.norm(); keys.any { k -> en.startsWith(k) || k.startsWith(en) } }
    }
    val th = t
    Column(Modifier.fillMaxSize()) {
        BmsTopBar(th?.name ?: "Threat", th?.let { listOfNotNull(it.side, Labels.threatCategory[it.category]).joinToString(" · ") }, onBack = onBack, actions = { FavoriteButton("th:$id") })
        if (th == null) { LoadingBox(); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            AdaptiveSplit(left = {
                ency?.pic?.let { HeroImage(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TagFlow {
                            Tag(th.side, sideColor(th.side), filled = true)
                            th.type?.let { Tag(it, Hud.Amber) }
                            th.year?.let { Tag("IOC $it", Hud.TextDim) }
                            if (th.impossibleToEvade) Tag("Hard to evade / drag", Hud.Red, filled = true)
                        }
                        if (th.aliases.isNotEmpty()) Text("aka " + th.aliases.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
                    }
                    th.rwr["alr56m"]?.takeIf { it.isNotBlank() }?.let { Column(horizontalAlignment = Alignment.CenterHorizontally) { RwrBadge(it, 56.dp); Text("ALR-56M", fontSize = 9.sp, color = Hud.TextDim) } }
                    Spacer(Modifier.width(8.dp))
                    th.rwr["alr93"]?.takeIf { it.isNotBlank() }?.let { Column(horizontalAlignment = Alignment.CenterHorizontally) { RwrBadge(it, 56.dp, Hud.Cyan); Text("ALR-93", fontSize = 9.sp, color = Hud.TextDim) } }
                }
                SectionCard("Key numbers", accent = Hud.Red) {
                    StatGrid(
                        listOf(
                            Stat("Min range", th.num("minRangeNm")?.let { Fmt.nm(it) }),
                            Stat("Typical engagement", th.num("typicalRangeNm")?.let { Fmt.nm(it) }, color = Hud.Amber),
                            Stat("Max range", th.num("maxRangeNm")?.let { Fmt.nm(it) }, color = Hud.Red),
                            Stat("Min altitude", th.num("minAltFt")?.let { Fmt.ft(it) }),
                            Stat("Max altitude", th.num("maxAltFt")?.let { Fmt.ft(it) }),
                            Stat("Radar lock", th.num("radarLockRangeNm")?.let { Fmt.nm(it) }),
                            Stat("Radar range", th.num("radarRangeNm")?.let { Fmt.nm(it) }),
                            Stat("Max speed", th.num("maxSpeedMach")?.let { "M ${Fmt.num(it, 1)}" }),
                            Stat("Max G", th.num("maxG")?.let { Fmt.num(it, 0) + " G" }),
                            Stat("HARM", th.harm),
                            Stat("ALIC", th.harmAlic, color = Hud.Green),
                        ),
                    )
                    if (th.maxRange() != null && th.num("maxAltFt") != null) {
                        Spacer(Modifier.height(12.dp))
                        EnvelopeDiagram(th)
                    }
                }
            }, right = {
                com.bmscompanion.app.ui.components.TextCard("Tactics", th.tactics, Hud.Green, threshold = 320)
                com.bmscompanion.app.ui.components.TextCard("Notes", th.notes)
                if (th.stats.isNotEmpty()) CollapsibleCard("Threat guide data (${th.stats.size} values)", initiallyOpen = th.stats.size <= 8, preview = th.stats.take(4).joinToString(" · ") { it.label + ": " + it.value }) { th.stats.forEach { KeyValueRow(it.label, it.value) } }
                if (th.weapons.isNotEmpty()) SectionCard("Weapons") { TagFlow { th.weapons.forEach { Tag(it, Hud.Cyan) } } }
                ency?.let { e ->
                    SectionCard("Encyclopedia", accent = Hud.Cyan) {
                        ListRow(e.name, e.subName ?: e.catName, onClick = { nav.go(Routes.ency(e.key)) })
                    }
                }
                Text("Source: BMS Threat Guide (4.38). Values observed in KTO; they vary with launch conditions.", style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint)
            })
        }
    }
}

/** Side-view engagement envelope: slant range (x) vs altitude (y). Approximation from guide numbers. */
@Composable
private fun EnvelopeDiagram(t: Threat) {
    val tm = rememberTextMeasurer()
    val maxR = t.maxRange()!!
    val typR = t.num("typicalRangeNm")
    val minR = t.num("minRangeNm") ?: 0.0
    val maxAlt = t.num("maxAltFt")!!
    val minAlt = t.num("minAltFt") ?: 0.0
    Canvas(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF071018))) {
        val padL = 44f; val padB = 28f; val padT = 12f; val padR = 16f
        val w = size.width - padL - padR; val h = size.height - padB - padT
        val xMax = maxR * 1.15; val yMax = maxAlt * 1.15
        fun px(nm: Double) = padL + (nm / xMax * w).toFloat()
        fun py(ft: Double) = padT + h - (ft / yMax * h).toFloat()
        // grid
        val gridColor = Hud.Outline.copy(alpha = 0.5f)
        for (i in 0..4) {
            val nm = xMax * i / 4; drawLine(gridColor, Offset(px(nm), padT), Offset(px(nm), padT + h), 1f)
            label(tm, Fmt.num(nm, if (xMax < 10) 1 else 0) ?: "", Offset(px(nm) - 8f, padT + h + 6f))
            val ft = yMax * i / 4; drawLine(gridColor, Offset(padL, py(ft)), Offset(padL + w, py(ft)), 1f)
            label(tm, "${Fmt.num(ft / 1000, 0)}k", Offset(4f, py(ft) - 8f))
        }
        fun envelope(r: Double): Path = Path().apply {
            moveTo(px(0.0), py(max(minAlt, 0.0)))
            val steps = 40
            for (i in 0..steps) {
                val a = Math.PI / 2 * i / steps
                val x = r * Math.cos(a); val y = maxAlt * (r / maxR) * Math.sin(a)
                lineTo(px(x), py(max(y, minAlt)))
            }
            lineTo(px(0.0), py(max(minAlt, 0.0)))
            close()
        }
        drawPath(envelope(maxR), Hud.Red.copy(alpha = 0.22f))
        drawPath(envelope(maxR), Hud.Red, style = Stroke(2f))
        typR?.let { drawPath(envelope(it), Hud.Amber.copy(alpha = 0.25f)); drawPath(envelope(it), Hud.Amber, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))) }
        if (minR > 0) drawRect(Color(0xFF071018), Offset(px(0.0), padT), Size(px(minR) - px(0.0), h))
        if (minAlt > 0) drawLine(Hud.Cyan, Offset(padL, py(minAlt)), Offset(padL + w, py(minAlt)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        drawLine(Hud.TextDim, Offset(padL, padT + h), Offset(padL + w, padT + h), 2f)
        label(tm, "nm", Offset(padL + w - 18f, padT + h - 18f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(tm: TextMeasurer, s: String, at: Offset) {
    safeText(tm, s, at, TextStyle(color = Hud.TextDim, fontSize = 9.sp, fontWeight = FontWeight.Medium))
}
