package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.Checklist
import com.bmscompanion.app.data.ChecklistItem
import com.bmscompanion.app.data.ChecklistSection
import com.bmscompanion.app.data.CommsFile
import com.bmscompanion.app.data.HarmFile
import com.bmscompanion.app.data.LineBrief
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.RwrFile
import com.bmscompanion.app.ui.Routes
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.ChipRow
import com.bmscompanion.app.ui.components.CollapsibleCard
import com.bmscompanion.app.ui.components.ContentColumn
import com.bmscompanion.app.ui.components.GroupHeader
import com.bmscompanion.app.ui.components.KeyValueRow
import com.bmscompanion.app.ui.components.ListDetail
import com.bmscompanion.app.ui.components.ListRow
import com.bmscompanion.app.ui.components.LoadingBox
import com.bmscompanion.app.ui.components.Paragraph
import com.bmscompanion.app.ui.components.RwrBadge
import com.bmscompanion.app.ui.components.SearchField
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.Tag
import com.bmscompanion.app.ui.components.isMedium
import com.bmscompanion.app.ui.components.isWide
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ============================ Checklists ============================

@Composable
fun ChecklistsScreen(nav: NavHostController) {
    val lists by produceState<List<Checklist>?>(null) { value = Repo.checklists() }
    Column(Modifier.fillMaxSize()) {
        BmsTopBar("Checklists", "Normal & emergency procedures", onBack = { nav.popBackStack() })
        val l = lists ?: run { LoadingBox(); return@Column }
        LazyVerticalGrid(GridCells.Adaptive(380.dp), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            gridItems(l) { c ->
                val groups = c.sections.groupBy { it.group }
                SectionCard(c.title, accent = Hud.Green) {
                    Text(c.document, style = LocalExtra.current.monoSmall, color = Hud.TextDim)
                    Spacer(Modifier.height(8.dp))
                    groups.forEach { (g, s) ->
                        ListRow(g.ifBlank { "Procedures" }, "${s.size} sections · ${s.sumOf { it.items.size }} items", onClick = { nav.go(Routes.checklist(c.id + "|" + g)) })
                    }
                }
            }
        }
    }
}

@Composable
fun ChecklistScreen(nav: NavHostController, id: String) {
    val clId = id.substringBefore('|')
    val group = id.substringAfter('|', "")
    val cl by produceState<Checklist?>(null, clId) { value = Repo.checklists().firstOrNull { it.id == clId } }
    var q by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val wide = isWide()
    val c = cl
    LaunchedEffect(wide, c) { if (wide && selected == null) selected = c?.sections?.firstOrNull { group.isEmpty() || it.group == group }?.id }
    val sections = remember(c, group, q) {
        c?.sections.orEmpty().filter { group.isEmpty() || it.group == group }.filter { s ->
            q.isBlank() || s.title.contains(q, true) || s.items.any {
                (it.text ?: "").contains(q, true) || (it.action ?: "").contains(q, true) || (it.title ?: "").contains(q, true)
            }
        }
    }
    // The checklists either side of the open one, in the order the list shows them: within its group, and within the
    // search while one is typed. A procedure opened from a warning light may sit in another group; then it is that
    // group's order. At either end of a group there is nothing to go to, and no button.
    fun neighbour(id: String, step: Int): ChecklistSection? {
        val own = c?.sections?.firstOrNull { it.id == id } ?: return null
        val order = sections.takeIf { l -> l.any { it.id == id } } ?: c.sections.filter { it.group == own.group }
        val i = order.indexOfFirst { it.id == id }
        return if (i < 0) null else order.getOrNull(i + step)
    }
    // Where a warning light's "open procedure" came from, so Back returns to the light rather than to the list.
    var trail by remember(id) { mutableStateOf(listOf<String>()) }
    fun go(target: String) { trail = emptyList(); selected = target }
    fun openRef(target: String) { selected?.let { trail = trail + it }; selected = target }
    fun back() { if (trail.isNotEmpty()) { selected = trail.last(); trail = trail.dropLast(1) } else selected = null }
    if (!wide && selected != null) {
        c?.sections?.firstOrNull { it.id == selected }?.let { s ->
            ChecklistSectionView(c, s.id, onBack = { back() }, highlight = q, prev = neighbour(s.id, -1), next = neighbour(s.id, 1), onGo = { go(it) }, onOpenRef = { openRef(it) })
        }
        return
    }
    ListDetail(
        selected = selected != null,
        list = {
            Column(Modifier.fillMaxSize()) {
                BmsTopBar(group.ifBlank { "Checklist" }, c?.title, onBack = { nav.popBackStack() })
                if (c == null) { LoadingBox(); return@Column }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { SearchField(q, { q = it }, "Search steps (e.g. JFS, FLCS, hot start)") }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(sections, key = { it.id }) { s ->
                        val done = Repo.getStringSet("cl:${c.id}:${s.id}").size
                        val steps = s.items.count { it.type == "step" }
                        ListRow(
                            s.title, "${s.items.size} items" + if (done > 0) " · $done/$steps checked" else "",
                            selected = selected == s.id,
                            leading = { Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (s.group.contains("Emergenc", true) || s.group.contains("Warning", true)) Hud.Red else Hud.Green)) },
                            onClick = { go(s.id) },
                        )
                    }
                }
            }
        },
        detail = {
            c?.let { cc ->
                selected?.let {
                    ChecklistSectionView(
                        cc, it, onBack = if (trail.isNotEmpty()) ({ back() }) else null, highlight = q,
                        prev = neighbour(it, -1), next = neighbour(it, 1), onGo = { n -> go(n) }, onOpenRef = { n -> openRef(n) },
                    )
                }
            }
        },
    )
}

@Composable
private fun ChecklistSectionView(
    c: Checklist,
    sectionId: String,
    onBack: (() -> Unit)?,
    highlight: String,
    prev: ChecklistSection? = null,
    next: ChecklistSection? = null,
    onGo: (String) -> Unit = {},
    onOpenRef: (String) -> Unit = {},
) {
    val s = c.sections.firstOrNull { it.id == sectionId } ?: return
    val prefKey = "cl:${c.id}:${s.id}"
    var checked by remember(prefKey) { mutableStateOf(Repo.getStringSet(prefKey)) }
    fun toggle(k: String) { checked = if (k in checked) checked - k else checked + k; Repo.putStringSet(prefKey, checked) }
    val emergency = s.group.contains("Emergenc", true)
    // a warning light's link names the procedure it opens: its title, and which engine's where the groups differ by it
    val refLabel: (String) -> String? = { ref ->
        c.sections.firstOrNull { it.id == ref }?.let { t ->
            val engine = Regex("\\(([^)]*)\\)\\s*$").find(t.group)?.groupValues?.get(1)
            if (engine != null && !t.title.contains(engine)) "${t.title} ($engine)" else t.title
        }
    }
    Column(Modifier.fillMaxSize()) {
        BmsTopBar(s.title, s.group, onBack = onBack, actions = {
            IconButton({ checked = emptySet(); Repo.putStringSet(prefKey, emptySet()) }) { Icon(Icons.Default.RestartAlt, "Reset", tint = Hud.TextDim) }
        })
        // keyed by the checklist, so the next one opens at its top rather than at the scroll the last one ended on
        key(s.id) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexedCompat(s.items) { i, item ->
                    ChecklistItemView(item, "$i" in checked, emergency, highlight, refLabel, onOpenRef) { toggle("$i") }
                }
                if (prev != null || next != null) item { ChecklistFoot(prev, next, onGo) }
            }
        }
    }
}

/**
 * The foot of a checklist: the one before on the left and the one after on the right, each naming its checklist, each
 * whole card a button. A lone one takes the full width.
 */
@Composable
private fun ChecklistFoot(prev: ChecklistSection?, next: ChecklistSection?, onGo: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        prev?.let { p -> StepCard(p, forward = false, Modifier.weight(1f)) { onGo(p.id) } }
        next?.let { n -> StepCard(n, forward = true, Modifier.weight(1f)) { onGo(n.id) } }
    }
}

@Composable
private fun StepCard(to: ChecklistSection, forward: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (to.group.contains("Emergenc", true) || to.group.contains("Warning", true)) Hud.Red else Hud.Green
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(Hud.Surface)
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!forward) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous checklist", tint = tint)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f), horizontalAlignment = if (forward) Alignment.Start else Alignment.End) {
            Text(if (forward) "NEXT" else "PREVIOUS", style = LocalExtra.current.overline, color = tint)
            Text(
                to.title, style = MaterialTheme.typography.titleSmall, color = Hud.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = if (forward) TextAlign.Start else TextAlign.End,
            )
        }
        if (forward) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next checklist", tint = tint)
        }
    }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexedCompat(list: List<T>, content: @Composable (Int, T) -> Unit) {
    items(list.size) { i -> content(i, list[i]) }
}

@Composable
private fun ChecklistItemView(
    it: ChecklistItem,
    checked: Boolean,
    emergency: Boolean,
    highlight: String,
    refLabel: (String) -> String?,
    onOpenRef: (String) -> Unit,
    onToggle: () -> Unit,
) {
    when (it.type) {
        "light" -> LightView(it, highlight, refLabel, onOpenRef)
        "table" -> ChecklistTable(it)
        "subhead" -> Text((it.text ?: it.title ?: "").uppercase(), style = LocalExtra.current.overline, color = Hud.Cyan, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        "note", "caution", "warning" -> {
            val color = when (it.type) { "warning" -> Hud.Red; "caution" -> Hud.Amber; else -> Hud.Cyan }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text(it.type.uppercase(), style = LocalExtra.current.overline, color = color)
                Text(it.text ?: "", style = MaterialTheme.typography.bodyMedium, color = Hud.Text)
            }
        }
        else -> {
            val hl = highlight.isNotBlank() && ((it.text ?: "").contains(highlight, true) || (it.action ?: "").contains(highlight, true))
            val body = MaterialTheme.typography.bodyMedium
            // The box, the number and the words sit on the step's first line: the number and the words share its
            // baseline (they are set in different faces and sizes, so tops would not line up), and the box is centred
            // in the height of that line.
            val firstLine = with(LocalDensity.current) { (if (body.lineHeight.isSp) body.lineHeight else body.fontSize * 1.4f).toDp() }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (hl) Hud.Amber.copy(alpha = 0.12f) else if (checked) Hud.Green.copy(alpha = 0.06f) else Color.Transparent)
                    .clickable(onClick = onToggle).padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.height(firstLine), contentAlignment = Alignment.Center) {
                    Icon(if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null, tint = if (checked) Hud.Green else Hud.TextFaint, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(it.n ?: "", Modifier.width(28.dp).alignByBaseline(), style = LocalExtra.current.monoSmall, color = if (emergency || it.critical) Hud.Red else Hud.TextDim)
                Column(Modifier.weight(1f).alignByBaseline()) {
                    Row {
                        Text(it.text ?: "", Modifier.weight(1f).alignByBaseline(), style = body, color = if (checked) Hud.TextDim else Hud.Text, fontWeight = if (it.critical) FontWeight.Bold else FontWeight.Normal)
                        it.action?.let { a -> Text(a, Modifier.padding(start = 10.dp).widthIn(max = 180.dp).alignByBaseline(), style = LocalExtra.current.monoSmall, color = if (checked) Hud.TextDim else Hud.Amber, fontWeight = FontWeight.Bold) }
                    }
                    it.note?.let { n -> Text(n, style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint) }
                }
            }
        }
    }
}

/**
 * A table from the checklist, its columns sized to what they hold: a short "Term" column beside a long "Definition"
 * gets a narrow share and the definition the rest, instead of every column the same 130 dp (which set the definitions
 * one word to a line with half the card empty). Only when the columns cannot all fit at a readable width does the
 * table keep them at a fixed width and scroll sideways.
 */
@Composable
private fun ChecklistTable(it: ChecklistItem) {
    val cols = maxOf(it.columns.size, it.rows.maxOfOrNull { r -> r.size } ?: 0)
    if (cols == 0) return
    val weights = (0 until cols).map { i ->
        (listOfNotNull(it.columns.getOrNull(i)) + it.rows.mapNotNull { r -> r.getOrNull(i) })
            .maxOfOrNull { s -> s.length }.let { n -> (n ?: 6).coerceIn(6, 48).toFloat() }
    }
    val rule = Hud.TextFaint.copy(alpha = 0.18f)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Surface).padding(10.dp)) {
        val minCol = 72.dp
        val fits = maxWidth >= minCol * cols
        val avg = weights.sum() / cols
        val fixed = weights.map { w -> (minCol * (w / avg) * 1.4f).coerceIn(minCol, 240.dp) }
        val across = if (fits) Modifier.fillMaxWidth() else Modifier.width(fixed.fold(0.dp) { a, b -> a + b })
        @Composable
        fun RowScope.cell(i: Int, content: @Composable (Modifier) -> Unit) =
            content(if (fits) Modifier.weight(weights[i]) else Modifier.width(fixed[i]))
        Column(if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())) {
            it.title?.let { t -> Text(t, Modifier.padding(start = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = Hud.Amber) }
            if (it.columns.isNotEmpty()) Row {
                it.columns.forEachIndexed { i, col -> cell(i) { m -> Text(col, m.padding(4.dp), style = LocalExtra.current.overline, color = Hud.Green) } }
            }
            it.rows.forEach { r ->
                Box(across.height(1.dp).background(rule))
                Row {
                    r.forEachIndexed { i, text ->
                        cell(i) { m ->
                            Text(text, m.padding(horizontal = 4.dp, vertical = 5.dp), fontSize = 13.sp, color = if (i == 0 && cols > 1) Hud.TextDim else Hud.Text)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One cockpit light: its legend on a lamp in the panel's colour, what lights it, what to do, and a link to each full
 * procedure in this checklist (one per engine where the procedure differs by engine).
 */
@Composable
private fun LightView(it: ChecklistItem, highlight: String, refLabel: (String) -> String?, onOpenRef: (String) -> Unit) {
    val lamp = when (it.panel) { "warning" -> Hud.Red; "caution" -> Hud.Amber; else -> Hud.Green }
    val hl = highlight.isNotBlank() && listOf(it.title, it.text, it.action).any { s -> (s ?: "").contains(highlight, true) }
    val card = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
        .background(if (hl) Hud.Amber.copy(alpha = 0.12f) else Hud.Surface).padding(10.dp)
    // the lamp: dark glass with the legend lit in the light's own colour, as on the panel
    val lampView: @Composable (Modifier) -> Unit = { m ->
        Box(
            m.heightIn(min = 40.dp).clip(RoundedCornerShape(4.dp))
                .background(lamp.copy(alpha = 0.14f)).border(1.dp, lamp.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text((it.title ?: "").uppercase(), style = LocalExtra.current.monoSmall, color = lamp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
    // beside the words where there is room; on a phone above them, so the words have the whole width
    if (isMedium()) Row(card, verticalAlignment = Alignment.Top) {
        lampView(Modifier.width(132.dp))
        Spacer(Modifier.width(12.dp))
        LightWords(it, lamp, refLabel, onOpenRef, Modifier.weight(1f))
    } else Column(card) {
        lampView(Modifier.widthIn(min = 120.dp))
        Spacer(Modifier.height(8.dp))
        LightWords(it, lamp, refLabel, onOpenRef, Modifier.fillMaxWidth())
    }
}

@Composable
private fun LightWords(it: ChecklistItem, lamp: Color, refLabel: (String) -> String?, onOpenRef: (String) -> Unit, modifier: Modifier) {
    Column(modifier) {
        it.text?.let { t -> Text(t, style = MaterialTheme.typography.bodyMedium, color = Hud.Text) }
        it.action?.let { a ->
            Spacer(Modifier.height(6.dp))
            Text("WHAT TO DO", style = LocalExtra.current.overline, color = lamp)
            Text(a, style = MaterialTheme.typography.bodyMedium, color = Hud.Amber)
        }
        // a procedure the checklist splits across two sections gets two links with one title: number them
        val links = it.refs.mapNotNull { ref -> refLabel(ref)?.let { ref to it } }
        val times = links.groupingBy { l -> l.second }.eachCount()
        val seen = HashMap<String, Int>()
        links.forEach { (ref, title) ->
            val label = if ((times[title] ?: 0) > 1) "$title (part ${(seen[title] ?: 0) + 1})".also { seen[title] = (seen[title] ?: 0) + 1 } else title
            Row(
                Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).clickable { onOpenRef(ref) }.padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = Hud.Cyan, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Open the procedure", tint = Hud.Cyan, modifier = Modifier.size(14.dp))
            }
        }
        it.note?.let { n -> Text(n, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint) }
    }
}

// ============================ Comms ============================

@Composable
fun CommsScreen(nav: NavHostController) {
    val comms by produceState<CommsFile?>(null) { value = Repo.comms() }
    val tabs = listOf("Brevity", "CAS / 9-Line", "ATC", "Tanker", "AWACS", "Radio calls", "NATO", "IFF", "Glossary")
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var q by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        BmsTopBar("Comms & Brevity", "Radio procedures reference", onBack = { nav.popBackStack() })
        ScrollableTabRow(
            selectedTabIndex = tab, containerColor = Hud.Bg, contentColor = Hud.Amber, edgePadding = 12.dp,
            indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = Hud.Amber) },
        ) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }, unselectedContentColor = Hud.TextDim) } }
        val c = comms ?: run { LoadingBox(); return@Column }
        when (tab) {
            0 -> SearchableList(q, { q = it }, "Search brevity (BANDIT, SPIKED…)", c.brevity.map { Triple(it.word, it.meaning, it.bmsUsage) })
            8 -> SearchableList(q, { q = it }, "Search abbreviations", c.glossary.map { Triple(it.term, it.meaning, null) })
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                ContentColumn(maxWidth = 1400.dp) { com.bmscompanion.app.ui.components.Masonry(minColumn = 440.dp) {
                    when (tab) {
                        1 -> {
                            c.casCheckIn?.let { BriefCard(it) }
                            c.nineLine?.let { BriefCard(it, Hud.Red) }
                            c.sitrep?.let { BriefCard(it, Hud.Cyan) }
                            c.casProcedure?.let { JsonCard("CAS flow", it) }
                            c.authentication?.let { JsonCard("Authentication", it) }
                        }
                        2 -> c.atcProcedures.forEach { p -> CollapsibleCard(p.title, initiallyOpen = false, preview = p.steps.firstOrNull()) { Steps(p.steps) } }
                        3 -> c.tankerProcedures.forEach { p -> CollapsibleCard(p.title, initiallyOpen = false, accent = Hud.Cyan, preview = p.steps.firstOrNull()) { Steps(p.steps) } }
                        4 -> SectionCard("AWACS calls", accent = Hud.Cyan) { c.awacsCalls.forEach { KeyValueRow(it.call, it.meaning) } }
                        5 -> {
                            c.radioCallFormat.forEach { r ->
                                CollapsibleCard(r.title, initiallyOpen = (r.description?.length ?: 0) < 160, preview = r.example ?: r.description) {
                                    r.example?.let { Text("“$it”", style = LocalExtra.current.mono, color = Hud.Green) }
                                    Paragraph(r.description, Hud.TextDim)
                                }
                            }
                            if (c.aiCommands.isNotEmpty()) c.aiCommands.forEach { JsonCard(jsonTitle(it) ?: "AI commands", it) }
                        }
                        6 -> SectionCard("NATO phonetic alphabet") {
                            c.natoAlphabet.chunked(2).forEach { row ->
                                Row { row.forEach { l -> Row(Modifier.weight(1f).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(l.letter, Modifier.width(28.dp), style = LocalExtra.current.mono, color = Hud.Amber, fontWeight = FontWeight.Bold)
                                    Column { Text(l.word); l.pronunciation?.let { Text(it, fontSize = 11.sp, color = Hud.TextDim) } }
                                } } }
                            }
                        }
                        7 -> SectionCard("IFF modes") { c.iffModes.forEach { KeyValueRow("Mode ${it.mode}", it.description) } }
                    }
                } }
            }
        }
    }
}

@Composable
private fun SearchableList(q: String, onQ: (String) -> Unit, hint: String, rows: List<Triple<String, String, String?>>) {
    val filtered = remember(rows, q) { rows.filter { q.isBlank() || it.first.contains(q, true) || it.second.contains(q, true) } }
    LazyVerticalGrid(GridCells.Adaptive(460.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.padding(16.dp)) { SearchField(q, onQ, hint); Text("${filtered.size} entries", style = LocalExtra.current.monoSmall, color = Hud.TextFaint, modifier = Modifier.padding(top = 6.dp)) } }
        gridItems(filtered) { (w, m, bms) ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(w, style = LocalExtra.current.mono, color = Hud.Amber, fontWeight = FontWeight.Bold)
                Text(m, style = MaterialTheme.typography.bodyMedium)
                bms?.let { Text("BMS: $it", style = MaterialTheme.typography.bodySmall, color = Hud.Cyan) }
            }
        }
    }
}

@Composable
private fun BriefCard(b: LineBrief, accent: Color = Hud.Amber) {
    SectionCard(b.title, accent = accent) {
        b.routing?.let { JsonBlock(it) ; Spacer(Modifier.height(8.dp)) }
        b.lines.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(l.n ?: "", Modifier.width(30.dp), style = LocalExtra.current.mono, color = accent, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f)) {
                    Text(l.label, fontWeight = FontWeight.SemiBold)
                    l.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Hud.TextDim) }
                    l.example?.let { Text("e.g. $it", style = LocalExtra.current.monoSmall, color = Hud.Green) }
                }
            }
        }
        if (b.remarks.isNotEmpty()) {
            Text("REMARKS", style = LocalExtra.current.overline, color = Hud.TextDim, modifier = Modifier.padding(top = 8.dp))
            b.remarks.forEach { JsonBlock(it) }
        }
    }
}

@Composable
fun Steps(steps: List<String>) {
    steps.forEachIndexed { i, s ->
        Row(Modifier.padding(vertical = 4.dp)) {
            Text("${i + 1}.", Modifier.width(26.dp), style = LocalExtra.current.monoSmall, color = Hud.Amber)
            Text(s, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun jsonTitle(e: JsonElement): String? = (e as? JsonObject)?.let { o -> listOf("title", "name", "group").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull } }

@Composable
fun JsonCard(title: String, e: JsonElement) = CollapsibleCard(title) { JsonBlock(e) }

/** Generic, readable rendering of loosely structured JSON (used for agent-curated extras). */
@Composable
fun JsonBlock(e: JsonElement, depth: Int = 0) {
    when (e) {
        is JsonPrimitive -> Text(e.contentOrNull ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
        is JsonArray -> e.forEach { item ->
            if (item is JsonPrimitive) Row(Modifier.padding(vertical = 2.dp)) { Text("•  ", color = Hud.Amber); Text(item.contentOrNull ?: "", style = MaterialTheme.typography.bodyMedium) }
            else Column(Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Hud.Surface2).padding(8.dp)) { JsonBlock(item, depth + 1) }
        }
        is JsonObject -> e.forEach { (k, v) ->
            if (k == "source" || k == "id") return@forEach
            if (v is JsonPrimitive) KeyValueRow(com.bmscompanion.app.ui.components.Fmt.titleCase(k.replace(Regex("([a-z])([A-Z])"), "$1 $2")), v.contentOrNull)
            else {
                Text(com.bmscompanion.app.ui.components.Fmt.titleCase(k.replace(Regex("([a-z])([A-Z])"), "$1 $2")), style = LocalExtra.current.overline, color = Hud.Green, modifier = Modifier.padding(top = 6.dp))
                JsonBlock(v, depth + 1)
            }
        }
    }
}

// ============================ HARM & RWR ============================

@Composable
fun HarmRwrScreen(nav: NavHostController) {
    val harm by produceState<HarmFile?>(null) { value = Repo.harm() }
    val rwr by produceState<RwrFile?>(null) { value = Repo.rwr() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var q by rememberSaveable { mutableStateOf("") }
    val tabs = listOf("ALIC codes", "HARM modes", "RWR emitters", "RWR symbols", "RWR controls")
    Column(Modifier.fillMaxSize()) {
        BmsTopBar("HARM & RWR", "AGM-88 ALIC tables · threat warning", onBack = { nav.popBackStack() })
        ScrollableTabRow(
            selectedTabIndex = tab, containerColor = Hud.Bg, contentColor = Hud.Amber, edgePadding = 12.dp,
            indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = Hud.Amber) },
        ) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }, unselectedContentColor = Hud.TextDim) } }
        val h = harm; val r = rwr
        if (h == null || r == null) { LoadingBox(); return@Column }
        when (tab) {
            0 -> {
                var cat by rememberSaveable { mutableStateOf<String?>(null) }
                val cats = h.alicCodes.map { it.category }.distinct()
                val rows = h.alicCodes.filter { (cat == null || it.category == cat) && (q.isBlank() || it.system.contains(q, true) || it.alic.contains(q) || (it.radar ?: "").contains(q, true)) }
                val wideGrid = isWide()
                LazyVerticalGrid(GridCells.Adaptive(500.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Column {
                        Column(Modifier.padding(16.dp)) { SearchField(q, { q = it }, "System, radar or code") }
                        ChipRow(cats, cat, { it }, { cat = it })
                        if (!wideGrid) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            listOf("SYM" to 0.6f, "SYSTEM / RADAR" to 2f, "TYPE" to 0.8f, "ALIC" to 0.8f).forEach { (t, w) -> Text(t, Modifier.weight(w), style = LocalExtra.current.overline, color = Hud.Green) }
                        }
                        if (wideGrid) Spacer(Modifier.height(8.dp))
                    } }
                    gridItems(rows) { a ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(0.6f)) { RwrBadge(a.symbol?.ifBlank { null } ?: "–", 34.dp) }
                                Column(Modifier.weight(2f)) { Text(a.system, fontWeight = FontWeight.SemiBold); a.radar?.let { Text(it, fontSize = 12.sp, color = Hud.TextDim) } }
                                Text(a.category, Modifier.weight(0.8f), fontSize = 12.sp, color = Hud.Cyan)
                                Text(a.alic, Modifier.weight(0.8f), style = LocalExtra.current.mono.copy(fontSize = 18.sp), color = Hud.Amber, fontWeight = FontWeight.Bold)
                            }
                            a.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            1 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                ContentColumn(maxWidth = 1400.dp) { com.bmscompanion.app.ui.components.Masonry(minColumn = 440.dp) {
                    com.bmscompanion.app.ui.components.TextCard("Overview", h.overview, Hud.Green)
                    h.modes.forEach { m ->
                        CollapsibleCard(m.name, initiallyOpen = false, preview = m.description) {
                            Paragraph(m.description)
                            if (m.hotas.isNotEmpty()) {
                                Text("HOTAS", style = LocalExtra.current.overline, color = Hud.Green, modifier = Modifier.padding(top = 8.dp))
                                m.hotas.forEach { KeyValueRow(it.input, it.effect) }
                            }
                            if (m.steps.isNotEmpty()) { Text("PROCEDURE", style = LocalExtra.current.overline, color = Hud.Green, modifier = Modifier.padding(top = 8.dp)); Steps(m.steps) }
                        }
                    }
                    if (h.optionsAndSettings.isNotEmpty()) CollapsibleCard("Options & settings") { h.optionsAndSettings.forEach { JsonBlock(it) } }
                    if (h.defaultTables.isNotEmpty()) CollapsibleCard("Threat table setups") { h.defaultTables.forEach { JsonBlock(it) } }
                    if (h.tips.isNotEmpty()) CollapsibleCard("Tips (${h.tips.size})", accent = Hud.Cyan, preview = h.tips.first()) { h.tips.forEach { Row(Modifier.padding(vertical = 3.dp)) { Text("•  ", color = Hud.Cyan); Text(it, style = MaterialTheme.typography.bodyMedium) } } }
                } }
            }
            2 -> {
                var type by rememberSaveable { mutableStateOf<String?>(null) }
                val types = r.emitters.mapNotNull { it.type }.distinct()
                val rows = r.emitters.filter { (type == null || it.type == type) && (q.isBlank() || it.system.contains(q, true) || it.symbol.equals(q, true) || (it.alr93 ?: "").equals(q, true)) }
                LazyVerticalGrid(GridCells.Adaptive(500.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Column { Column(Modifier.padding(16.dp)) { SearchField(q, { q = it }, "Symbol or system (e.g. 29, SA-6)") }; ChipRow(types, type, { it }, { type = it }) } }
                    gridItems(rows) { e ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { RwrBadge(e.alr56m?.ifBlank { null } ?: e.symbol.ifBlank { "–" }, 42.dp); Text("56M", fontSize = 8.sp, color = Hud.TextFaint) }
                            Spacer(Modifier.width(6.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { RwrBadge(e.alr93?.ifBlank { null } ?: "–", 42.dp, Hud.Cyan); Text("93", fontSize = 8.sp, color = Hud.TextFaint) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.system, fontWeight = FontWeight.SemiBold)
                                Text(listOfNotNull(e.type, e.radars).joinToString(" · "), fontSize = 12.sp, color = Hud.TextDim)
                                e.notes?.let { Text(it, fontSize = 12.sp, color = Hud.TextFaint, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
            3 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                ContentColumn(maxWidth = 1400.dp) { com.bmscompanion.app.ui.components.Masonry(minColumn = 440.dp) {
                    com.bmscompanion.app.ui.components.TextCard("Reading the RWR", r.overview, Hud.Green)
                    CollapsibleCard("Symbols & modifiers (${r.symbols.size})", initiallyOpen = true) {
                        r.symbols.forEach { s ->
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                                RwrBadge(s.symbol.ifBlank { "?" }, 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column { Text(s.meaning, fontWeight = FontWeight.SemiBold); s.details?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Hud.TextDim) } }
                            }
                        }
                    }
                    if (r.tones.isNotEmpty()) CollapsibleCard("Audio tones (${r.tones.size})", accent = Hud.Cyan, preview = r.tones.joinToString(" · ") { it.name }) { r.tones.forEach { KeyValueRow(it.name, it.description) } }
                } }
            }
            4 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                ContentColumn(maxWidth = 1400.dp) { com.bmscompanion.app.ui.components.Masonry(minColumn = 440.dp) {
                    r.controls.forEach { c -> CollapsibleCard(c.name, preview = c.description) { c.panel?.let { Tag(it, Hud.Cyan) }; Paragraph(c.description) } }
                    if (r.preflight.isNotEmpty()) CollapsibleCard("Preflight check", accent = Hud.Green, preview = r.preflight.first()) { Steps(r.preflight) }
                } }
            }
        }
    }
}

@Suppress("unused") private val keepGroupHeader: @Composable (String) -> Unit = { GroupHeader(it) }
