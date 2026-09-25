package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.Canvas
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.AirportSet
import com.bmscompanion.app.data.DataIndex
import com.bmscompanion.app.data.RadioEntry
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.RunwayEnd
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.ui.Routes
import com.bmscompanion.app.ui.components.AdaptiveSplit
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.ContentColumn
import com.bmscompanion.app.ui.components.EmptyState
import com.bmscompanion.app.ui.components.FavoriteButton
import com.bmscompanion.app.ui.components.Fmt
import com.bmscompanion.app.ui.components.KeyValueRow
import com.bmscompanion.app.ui.components.ListDetail
import com.bmscompanion.app.ui.components.AlphabetScrubber
import com.bmscompanion.app.ui.components.ListRow
import com.bmscompanion.app.ui.components.LoadingBox
import com.bmscompanion.app.ui.components.SearchField
import com.bmscompanion.app.ui.components.AirfieldChart
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.isMedium
import com.bmscompanion.app.ui.components.Stat
import com.bmscompanion.app.ui.components.StatGrid
import com.bmscompanion.app.ui.components.Tag
import com.bmscompanion.app.ui.components.TagFlow
import com.bmscompanion.app.ui.components.TheaterMap
import com.bmscompanion.app.ui.components.isWide
import com.bmscompanion.app.ui.components.rememberMapState
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.components.safeText
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot

const val FT_PER_NM = 6076.12

/** Bearing (true) and range in nm from point a to b (theater coords: x north, y east). */
fun bearingRange(ax: Double, ay: Double, bx: Double, by: Double): Pair<Double, Double> {
    val dn = bx - ax; val de = by - ay
    val brg = (Math.toDegrees(atan2(de, dn)) + 360) % 360
    return brg to hypot(dn, de) / FT_PER_NM
}

@Composable
fun TheaterPicker(current: String, onPick: (String) -> Unit) {
    val index by produceState<DataIndex?>(null) { value = Repo.index() }
    var open by remember { mutableStateOf(false) }
    val t = index?.theaters?.firstOrNull { it.id == current }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).clickable { open = true }.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Public, null, tint = Hud.Cyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(t?.name ?: current, color = Hud.Cyan, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null, tint = Hud.TextDim)
        }
        DropdownMenu(open, { open = false }) {
            index?.theaters?.filter { it.primary }?.forEach { th ->
                DropdownMenuItem(
                    text = {
                        Column(Modifier.widthIn(max = 320.dp)) {
                            Text(th.name)
                            Text("${th.airportCount} airfields", fontSize = 11.sp, color = Hud.TextDim)
                        }
                    },
                    onClick = { onPick(th.id); open = false },
                )
            }
        }
    }
}

@Composable
fun AirportsScreen(nav: NavHostController) {
    val theaterId = Repo.selectedTheater.value
    val theater by produceState<Theater?>(null, theaterId) { value = Repo.theater(theaterId) }
    val set by produceState<AirportSet?>(null, theater) { value = theater?.let { Repo.airportSet(it.airportSet) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable(theaterId) { mutableStateOf<Int?>(null) }
    val wide = isWide()
    val open: (Int) -> Unit = { id -> if (wide) selected = id else nav.go(Routes.airport(theaterId, id)) }

    val header: @Composable () -> Unit = {
        BmsTopBar("Airfields", theater?.desc?.takeIf { it.isNotBlank() } ?: "Runways · ILS · TACAN · Radios", actions = {
            TheaterPicker(theaterId) { Repo.setTheater(it) }
            Spacer(Modifier.width(8.dp))
        })
        TabRow(
            selectedTabIndex = tab, containerColor = Hud.Bg, contentColor = Hud.Amber,
            modifier = if (wide) Modifier.widthIn(max = 640.dp) else Modifier,
            indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = Hud.Amber) },
        ) {
            listOf("Airfields", "Map", "Navaids", "Radio").forEachIndexed { i, l ->
                Tab(tab == i, { tab = i }, text = { Text(l, maxLines = 1) }, unselectedContentColor = Hud.TextDim)
            }
        }
    }

    if (wide) {
        // Tablet landscape: full-width tabs; list+detail, or a full-size map with a detail side panel.
        var mapSelected by rememberSaveable(theaterId) { mutableStateOf<Int?>(null) }
        LaunchedEffect(set, wide) { if (selected == null) set?.airports?.firstOrNull()?.let { selected = it.id } }
        Column(Modifier.fillMaxSize()) {
            header()
            val s = set
            val th = theater
            if (s == null || th == null) { LoadingBox(); return@Column }
            val divider: @Composable () -> Unit = { Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline.copy(alpha = 0.5f))) }
            when (tab) {
                0 -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(400.dp).fillMaxHeight()) { AirportList(s.airports, selected) { selected = it } }
                    divider()
                    Box(Modifier.weight(1f).fillMaxHeight()) { selected?.let { AirportDetail(nav, theaterId, it, null) } ?: EmptyState("Select an airfield") }
                }
                1 -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { AirportMap(th, s, directOpen = true, onOpen = { mapSelected = it }) }
                    mapSelected?.let { id ->
                        divider()
                        Box(Modifier.width(460.dp).fillMaxHeight()) { androidx.compose.runtime.key(id) { AirportDetail(nav, theaterId, id) { mapSelected = null } } }
                    }
                }
                2 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = 900.dp)) { NavaidList(s) } }
                3 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = 900.dp)) { RadioList(th) } }
            }
        }
        return
    }

    ListDetail(
        selected = false,
        listWidth = 400.dp,
        list = {
            Column(Modifier.fillMaxSize()) {
                header()
                val s = set
                val th = theater
                if (s == null || th == null) { LoadingBox(); return@Column }
                when (tab) {
                    0 -> AirportList(s.airports, selected, open)
                    1 -> AirportMap(th, s, onOpen = { nav.go(Routes.airport(theaterId, it)) })
                    2 -> NavaidList(s)
                    3 -> RadioList(th)
                }
            }
        },
        detail = {},
    )
}

@Composable
private fun AirportList(list: List<Airport>, selected: Int?, onOpen: (Int) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    val types = remember(list) { list.map { airportKind(it) }.distinct().sorted() }
    val filtered = remember(list, q, type) {
        val nq = q.norm()
        list.filter { a ->
            (type == null || airportKind(a) == type) &&
                (nq.isEmpty() || a.name.norm().contains(nq) || (a.icao?.norm()?.contains(nq) == true) ||
                    (a.tacan?.label?.norm() == nq) || a.runways.any { it.ends.any { e -> e.ils?.norm() == nq } } ||
                    listOfNotNull(a.freqs?.towerUhf, a.freqs?.towerVhf, a.freqs?.approachUhf, a.freqs?.groundUhf).any { it.norm().startsWith(nq) })
        }
    }
    val rows = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = rows, contentPadding = PaddingValues(bottom = 24.dp, end = 20.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { SearchField(q, { q = it }, "Name, ICAO, TACAN (75X), ILS or frequency") }
            com.bmscompanion.app.ui.components.ChipRow(types, type, { it }, { type = it })
            Text("${filtered.size} airfields", style = LocalExtra.current.monoSmall, color = Hud.TextFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        items(filtered, key = { it.id }) { a ->
            ListRow(
                title = a.name,
                subtitle = listOfNotNull(
                    a.runways.joinToString(" ") { it.name }.ifBlank { null },
                    a.freqs?.towerUhf?.let { "TWR $it" },
                ).joinToString(" · "),
                selected = selected == a.id,
                leading = {
                    Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                        Text(a.icao ?: "—", style = LocalExtra.current.monoSmall, color = if (a.icao != null) Hud.Amber else Hud.TextFaint, fontWeight = FontWeight.Bold)
                    }
                },
                trailing = { a.tacan?.let { Tag(it.label, Hud.Green) } },
                onClick = { onOpen(a.id) },
            )
        }
    }
    // one row above the airfields: the search field and the count
    AlphabetScrubber(filtered, rows, { it.name }, before = 1)
    }
}

fun airportKind(a: Airport): String = when {
    Regex("(^|\\s)(CV|CVN|LHD|USS|TAKR|Carrier)", RegexOption.IGNORE_CASE).containsMatchIn(a.name) -> "Carrier"
    a.name.contains("highway", true) -> "Highway strip"
    else -> a.type
}

/** One searchable thing on the theater map. */
private data class MapHit(val kind: String, val title: String, val sub: String, val x: Double, val y: Double, val airport: Airport? = null)

@Composable
/** [directOpen]: tablet mode — tapping an airfield (or an airfield search result) opens it straight away in the side panel. */
private fun AirportMap(th: Theater, set: AirportSet, directOpen: Boolean = false, onOpen: (Int) -> Unit) {
    val tm = rememberTextMeasurer()
    val state = rememberMapState()
    var picked by remember { mutableStateOf<Airport?>(null) }
    // label settings (persisted): field labels 0=off 1=ICAO 2=name; towns, borders and map style are the shared MapLook settings
    var fieldLabels by remember { mutableIntStateOf(Repo.getInt("map_field_labels", 1)) }
    var tacanLabels by remember { mutableStateOf(Repo.getInt("map_tacan_labels", 0) == 1) }
    var navLabels by remember { mutableStateOf(Repo.getInt("map_nav_labels", 0) == 1) }
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var marker by remember { mutableStateOf<MapHit?>(null) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // zoom after the keyboard has closed, otherwise the view height changes under the fly-to and the target ends off-centre
    val flyAfterKeyboard: (Double, Double, Float) -> Unit = { x, y, z -> focus.clearFocus(); scope.launch { kotlinx.coroutines.delay(350); state.flyTo(x, y, z) } }

    val shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 5f)
    val labelStyle = remember { TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, shadow = shadow) }
    val subStyle = remember { TextStyle(color = Hud.Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, shadow = shadow) }
    val navStyle = remember { TextStyle(color = Hud.Cyan, fontSize = 10.sp, fontWeight = FontWeight.Medium, shadow = shadow) }

    val index = remember(set) {
        buildList {
            set.airports.forEach { a -> add(MapHit("Airfield", a.name, listOfNotNull(a.icao, a.tacan?.label, a.freqs?.towerUhf?.let { "TWR $it" }).joinToString(" · "), a.x, a.y, a)) }
            set.navaids.forEach { n -> add(MapHit("Navaid", n.name, n.tacan?.label ?: "", n.x, n.y)) }
            set.places.forEach { p -> add(MapHit("Town", p.n, "", p.x, p.y)) }
        }
    }
    val results = remember(query, index) {
        val q = query.trim()
        if (q.length < 2) emptyList() else {
            val nq = q.norm()
            index.mapNotNull { h ->
                val nt = h.title.norm()
                val score = when {
                    h.airport?.icao?.norm() == nq -> 100
                    h.airport?.tacan?.label?.norm() == nq -> 95
                    h.kind == "Navaid" && h.sub.norm() == nq -> 95
                    nt == nq -> 90
                    nt.startsWith(nq) -> 70
                    nt.contains(nq) -> 50
                    h.sub.norm().contains(nq) -> 40
                    else -> 0
                }
                if (score == 0) null else h to score + when (h.kind) { "Airfield" -> 6; "Navaid" -> 5; else -> 0 }
            }.sortedByDescending { it.second }.take(8).map { it.first }
        }
    }

    Box(Modifier.fillMaxSize()) {
        TheaterMap(
            th.map, th.sizeFt, Modifier.fillMaxSize(), state, fillWidth = isWide(),
            onTap = { x, y, pr ->
                focus.clearFocus()
                val best = set.airports.minByOrNull { hypot(it.x - x, it.y - y) }
                picked = best?.takeIf { val p = pr.toScreen(it.x, it.y); val t = pr.toScreen(x, y); hypot((p.x - t.x).toDouble(), (p.y - t.y).toDouble()) < 60 }
                if (directOpen) picked?.let { onOpen(it.id) }
            },
        ) { pr ->
            val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
            set.navaids.forEach { n ->
                val p = pr.toScreen(n.x, n.y)
                drawCircle(Hud.Cyan, 5f, p, style = Stroke(2f))
                if (navLabels) placeLabel(tm, n.name + (n.tacan?.let { " " + it.label } ?: ""), p + Offset(8f, 4f), navStyle, placed)
            }
            // airfields first so their labels win over place names
            set.airports.forEach { a ->
                val p = pr.toScreen(a.x, a.y)
                val col = when (airportKind(a)) { "Carrier" -> Hud.Blue; "Airbase" -> Hud.Amber; else -> Hud.Green }
                val sel = picked?.id == a.id
                drawCircle(Color.Black.copy(alpha = 0.6f), if (sel) 13f else 9f, p)
                drawCircle(col, if (sel) 10f else 6f, p)
                val text = when { fieldLabels == 2 -> a.name; fieldLabels == 1 -> a.icao ?: a.name; sel -> a.icao ?: a.name; else -> null }
                val shown = text != null && (sel || placeLabel(tm, text, p + Offset(11f, -14f), labelStyle, placed))
                if (sel && text != null) safeText(tm, text, p + Offset(11f, -14f), labelStyle)
                if (tacanLabels && a.tacan != null) placeLabel(tm, a.tacan.label, p + Offset(11f, if (shown) 6f else -8f), subStyle, placed)
            }
            // search result marker
            marker?.let { m ->
                val c = pr.toScreen(m.x, m.y)
                drawCircle(Hud.Magenta.copy(alpha = 0.25f), 26f, c)
                drawCircle(Hud.Magenta, 26f, c, style = Stroke(3f))
                drawLine(Hud.Magenta, c - Offset(34f, 0f), c - Offset(18f, 0f), 3f); drawLine(Hud.Magenta, c + Offset(18f, 0f), c + Offset(34f, 0f), 3f)
                drawLine(Hud.Magenta, c - Offset(0f, 34f), c - Offset(0f, 18f), 3f); drawLine(Hud.Magenta, c + Offset(0f, 18f), c + Offset(0f, 34f), 3f)
                if (m.airport == null) safeText(tm, m.title, c + Offset(30f, -30f), labelStyle)
            }
        }
        if (!directOpen) picked?.let { a ->
            Row(
                Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 520.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)).background(Hud.Surface.copy(alpha = 0.96f)).border(1.dp, Hud.Outline, RoundedCornerShape(14.dp))
                    .clickable { onOpen(a.id) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(a.icao, a.runways.joinToString(" ") { it.name }.ifBlank { null }, a.freqs?.towerUhf?.let { "TWR $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
                }
                a.tacan?.let { Tag(it.label, Hud.Green, filled = true) }
                Spacer(Modifier.width(8.dp))
                Text("OPEN ›", color = Hud.Amber, style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // slim map search
            Row(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp)).background(Hud.Bg.copy(alpha = 0.9f))
                    .border(1.dp, if (searchFocused) Hud.Cyan else Hud.Outline, RoundedCornerShape(21.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = Hud.TextDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Find airfield, ICAO, TACAN, navaid, town…", color = Hud.TextFaint, fontSize = 13.sp, maxLines = 1)
                    androidx.compose.foundation.text.BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Hud.Text, fontSize = 14.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Hud.Cyan),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                            results.firstOrNull()?.let { h -> marker = h; picked = h.airport; flyAfterKeyboard(h.x, h.y, 6f); if (directOpen) h.airport?.let { onOpen(it.id) } }
                        }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused },
                    )
                }
                if (query.isNotEmpty() || marker != null) {
                    Icon(Icons.Default.Clear, "Clear", tint = Hud.TextDim, modifier = Modifier.size(20.dp).clickable { query = ""; marker = null })
                }
            }
            if (searchFocused && results.isNotEmpty()) {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Hud.Surface.copy(alpha = 0.97f)).border(1.dp, Hud.Outline, RoundedCornerShape(12.dp)),
                ) {
                    results.forEach { h ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                marker = h; picked = h.airport; flyAfterKeyboard(h.x, h.y, if (h.kind == "Town") 7f else 6f)
                                if (directOpen) h.airport?.let { onOpen(it.id) }
                            }.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Tag(h.kind, when (h.kind) { "Airfield" -> Hud.Amber; "Navaid" -> Hud.Cyan; "Town" -> Color(0xFFFFD27A); else -> Hud.TextDim })
                            Spacer(Modifier.width(10.dp))
                            Text(h.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            if (h.sub.isNotBlank()) Text(h.sub, fontSize = 11.sp, color = Hud.TextDim, maxLines = 1)
                        }
                    }
                }
            }
            if (!searchFocused) Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Hud.Bg.copy(alpha = 0.82f)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                val names = listOf("Labels off", "ICAO", "Names")
                com.bmscompanion.app.ui.components.HudChip(names[fieldLabels], fieldLabels != 0) { fieldLabels = (fieldLabels + 1) % 3; Repo.putInt("map_field_labels", fieldLabels) }
                com.bmscompanion.app.ui.components.MapLookButton()
                com.bmscompanion.app.ui.components.HudChip("TACAN", tacanLabels) { tacanLabels = !tacanLabels; Repo.putInt("map_tacan_labels", if (tacanLabels) 1 else 0) }
                com.bmscompanion.app.ui.components.HudChip("Navaids", navLabels) { navLabels = !navLabels; Repo.putInt("map_nav_labels", if (navLabels) 1 else 0) }
            }
        }
    }
}

@Composable
private fun NavaidList(set: AirportSet) {
    var q by rememberSaveable { mutableStateOf("") }
    val nav = remember(set, q) { set.navaids.filter { q.isBlank() || it.name.norm().contains(q.norm()) || it.tacan?.label?.norm() == q.norm() }.sortedBy { it.name } }
    val tacanFields = remember(set, q) { set.airports.filter { it.tacan != null && (q.isBlank() || it.name.norm().contains(q.norm()) || it.tacan.label.norm() == q.norm()) }.sortedBy { it.tacan!!.channel } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Column(Modifier.padding(16.dp)) { SearchField(q, { q = it }, "Station name or channel") } }
        item { com.bmscompanion.app.ui.components.GroupHeader("VOR / TACAN stations", nav.size) }
        items(nav) { n ->
            ListRow(n.name, n.objective?.takeIf { it != n.name }, trailing = { n.tacan?.let { Tag(it.label, Hud.Green, filled = true) } }, onClick = {})
        }
        item { com.bmscompanion.app.ui.components.GroupHeader("Airfield TACANs (by channel)", tacanFields.size) }
        items(tacanFields) { a ->
            ListRow(a.name, listOfNotNull(a.icao, a.tacan?.station, a.tacan?.rangeNm?.let { "$it nm" }).joinToString(" · "), trailing = { Tag(a.tacan!!.label, Hud.Green, filled = true) }, onClick = {})
        }
    }
}

@Composable
private fun RadioList(th: Theater) {
    val list by produceState<List<RadioEntry>?>(null, th) { value = Repo.radio(th.radioSet) }
    var q by rememberSaveable { mutableStateOf("") }
    val l = list ?: return LoadingBox()
    val filtered = remember(l, q) { l.filter { q.isBlank() || it.agency.norm().contains(q.norm()) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SearchField(q, { q = it }, "Callsign (Cowboy, Magic, Texaco…)")
                Text("Flight / agency frequencies from RadioMap.dat — UHF1 = package tactical, VHF = intra-flight, UHF2 = backup.", style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                listOf("CALLSIGN" to 1.4f, "UHF1" to 1f, "VHF" to 1f, "UHF2" to 1f).forEach { (t, w) -> Text(t, Modifier.weight(w), style = LocalExtra.current.overline, color = Hud.Green) }
            }
        }
        items(filtered) { r ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(r.agency, Modifier.weight(1.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOf(r.uhf1, r.vhf, r.uhf2).forEach { Text(it ?: "—", Modifier.weight(1f), style = LocalExtra.current.monoSmall, color = if (it == null) Hud.TextFaint else Hud.Text) }
            }
        }
    }
}

// ===================== detail =====================

@Composable
fun AirportDetailRoute(nav: NavHostController, theater: String, id: Int) = AirportDetail(nav, theater, id) { nav.popBackStack() }

@Composable
fun AirportDetail(nav: NavHostController, theaterId: String, id: Int, onBack: (() -> Unit)?) {
    val th by produceState<Theater?>(null, theaterId) { value = Repo.theater(theaterId) }
    val set by produceState<AirportSet?>(null, th) { value = th?.let { Repo.airportSet(it.airportSet) } }
    val a = set?.airports?.firstOrNull { it.id == id }
    Column(Modifier.fillMaxSize()) {
        BmsTopBar(a?.name ?: "Airfield", listOfNotNull(a?.icao, th?.name).joinToString(" · "), onBack = onBack, actions = { FavoriteButton("ap:$theaterId:$id") })
        if (a == null || th == null) { if (set == null) LoadingBox() else EmptyState("Not found"); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            AdaptiveSplit(left = {
                TagFlow {
                    Tag(airportKind(a), Hud.Amber, filled = true)
                    a.elevationFt?.let { Tag("ELEV $it ft", Hud.TextDim) }
                    Tag("Theater mag decl 0°", Hud.TextFaint)
                }
                // Big nav tiles
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigTile("TACAN", a.tacan?.label ?: "—", a.tacan?.let { listOfNotNull(it.station, it.rangeNm?.let { r -> "$r nm" }).joinToString(" · ") }, Hud.Green, Modifier.weight(1f))
                    BigTile("TOWER", a.freqs?.towerUhf ?: "—", a.freqs?.towerVhf?.let { "VHF $it" }, Hud.Amber, Modifier.weight(1f))
                    val ils = a.runways.flatMap { r -> r.ends.filter { it.ils != null } }
                    BigTile("ILS", ils.firstOrNull()?.ils ?: "—", ils.joinToString(" ") { it.designator }.ifBlank { null }, Hud.Cyan, Modifier.weight(1f))
                }
                GroundChartCard(nav, theaterId, a)
                AirportChartsCard(nav, th!!.airportSet, a.id, a.name)
                a.freqs?.let { f ->
                    SectionCard("Radio frequencies") {
                        StatGrid(
                            listOf(
                                Stat("Ground UHF", f.groundUhf), Stat("Tower UHF", f.towerUhf, color = Hud.Amber), Stat("Tower VHF", f.towerVhf),
                                Stat("Approach / Dep UHF", f.approachUhf), Stat("ATIS VHF", f.atisVhf), Stat("Base Ops UHF", f.opsUhf), Stat("LSO UHF", f.lsoUhf),
                            ),
                            minCell = 140.dp,
                        )
                    }
                }
            }, right = {
                if (a.runways.isNotEmpty()) {
                    SectionCard("Runways", accent = Hud.Cyan) {
                        Row(verticalAlignment = Alignment.Top) {
                            RunwayDiagram(a, Modifier.width(130.dp).aspectRatio(1f))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                a.runways.forEach { r ->
                                    Column {
                                        Text("RWY ${r.name}", style = LocalExtra.current.mono, color = Hud.Cyan, fontWeight = FontWeight.Bold)
                                        Text(listOfNotNull(r.lengthFt?.let { "≈ ${Fmt.num(it)} ft" }, r.ends.firstOrNull()?.let { "${Fmt.hdg(it.headingTrue)}T" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        a.runways.flatMap { it.ends }.forEach { e -> RunwayEndCard(e) }
                    }
                }
                a.atc?.let { atc ->
                    SectionCard("ATC & weather minima") {
                        StatGrid(
                            listOf(
                                Stat("IFR min vis", atc.ifrMinVisM?.let { "$it m" }), Stat("IFR min ceiling", atc.ifrMinCloudFt?.let { "$it ft" }),
                                Stat("VFR min vis", atc.vfrMinVisM?.let { "$it m" }), Stat("VFR min ceiling", atc.vfrMinCloudFt?.let { "$it ft" }),
                                Stat("Active runways", atc.activeRunways?.toString()), Stat("Pattern", if (atc.shortPattern) "Short (base mandatory)" else "Normal", mono = false),
                            ),
                        )
                    }
                }
                NearestFields(nav, theaterId, a, set!!)
                SectionCard("Location") {
                    KeyValueRow("Theater position", "N ${Fmt.num(a.x / FT_PER_NM, 1)} nm · E ${Fmt.num(a.y / FT_PER_NM, 1)} nm", mono = true)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp))) {
                        TheaterMap(th!!.map, th!!.sizeFt, Modifier.fillMaxSize(), focus = a.x to a.y, focusScale = 5f) { pr ->
                            val p = pr.toScreen(a.x, a.y)
                            drawCircle(Hud.Amber, 9f, p); drawCircle(Color.Black, 9f, p, style = Stroke(2f))
                            drawCircle(Hud.Green.copy(alpha = 0.6f), pr.pxPerNm * 10, p, style = Stroke(1.5f))
                            drawCircle(Hud.Green.copy(alpha = 0.35f), pr.pxPerNm * 20, p, style = Stroke(1.5f))
                        }
                    }
                    Text("Rings: 10 / 20 nm", style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint)
                }
            })
        }
    }
}

/**
 * The field's ground chart, shown rather than described.
 *
 * It used to be a line of text and an Open button, which told a pilot nothing about the field they were looking at
 * — and it is now the only ground chart in the app, since the pictures BMS ships in its docs folder are gone. So
 * the card draws the real thing: the runways, the taxiways, the pavement and the ramp, at the size it takes to
 * recognise the place. Tapping anywhere on it opens the full page, where the chart zooms, takes a spot and gives
 * the clearance to the runway.
 *
 * The preview is deliberately inert — no panning, no tapping spots, no route — because a card that swallowed a
 * drag would fight the page it sits on for every scroll.
 */
@Composable
private fun GroundChartCard(nav: NavHostController, theaterId: String, a: Airport) {
    val th by produceState<Theater?>(null, theaterId) { value = Repo.theater(theaterId) }
    val field by produceState<com.bmscompanion.app.data.airfield.Airfield?>(null, th, a.id) {
        value = th?.airfieldSet?.let { Repo.airfield(it, a.id) }
    }
    val f = field ?: return
    val open = { nav.go(Routes.groundChart(theaterId, a.id)) }
    SectionCard(
        "Ground chart", accent = Hud.Green,
        trailing = {
            Text(
                "Open",
                Modifier.clip(RoundedCornerShape(8.dp)).background(Hud.Green.copy(alpha = 0.18f))
                    .border(1.dp, Hud.Green.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .clickable(onClick = open)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = Hud.Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        },
    ) {
        Box(
            Modifier.fillMaxWidth().height(if (isMedium()) 320.dp else 240.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .clickable(onClick = open),
        ) {
            AirfieldChart(
                field = f,
                // the first route only so the ramp is drawn; no path, so no clearance line across the preview.
                // A ship has no ramp to draw: its spot numbers turn with it.
                route = if (f.ship == null) f.routes.firstOrNull() else null,
                inks = TaxiPrefs.inks,
                modifier = Modifier.fillMaxSize(),
                labelScale = 0.85f,
                // A thumbnail's job is to be recognisable, so the field is turned to whatever angle fills the
                // card — most airfields lie diagonally and north-up left two thirds of it empty. The chart draws
                // its own north arrow, and the full page opens north-up as always.
                fitRotation = true,
                interactive = false,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (f.ship != null) "The deck, the landing area and the island. Tap for the full chart."
            else "Taxiways, hold shorts and every ramp spot. Tap for the full chart, a spot and the way to the runway.",
            color = Hud.TextDim, fontSize = 12.sp,
        )
    }
}

@Composable
private fun BigTile(label: String, value: String, sub: String?, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(label, style = LocalExtra.current.overline, color = color)
        Text(value, style = LocalExtra.current.mono.copy(fontSize = 20.sp), color = Hud.Text, fontWeight = FontWeight.Bold, maxLines = 1)
        if (!sub.isNullOrBlank()) Text(sub, fontSize = 11.sp, color = Hud.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RunwayEndCard(e: RunwayEnd) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RWY ${e.designator}", style = LocalExtra.current.mono, color = Hud.Amber, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${Fmt.hdg(e.headingTrue)} T", style = LocalExtra.current.monoSmall, color = Hud.TextDim)
            Spacer(Modifier.width(10.dp))
            if (e.ils != null) Tag("ILS ${e.ils}", Hud.Cyan, filled = true) else Tag("no ILS", Hud.TextFaint)
        }
        e.pattern?.let { p ->
            Spacer(Modifier.height(6.dp))
            fun pt(v: com.bmscompanion.app.data.PatternPoint?) = v?.let {
                if (it.b == 0.0) "${Fmt.num(it.a, if (it.a % 1 == 0.0) 0 else 1)} nm @ ${Fmt.num(it.altFt)} ft" else "(${Fmt.num(it.a, 1)}, ${Fmt.num(it.b, 1)}) @ ${Fmt.num(it.altFt)} ft"
            }
            KeyValueRow("Overhead break", p.overheadSide, mono = true)
            KeyValueRow("Final", pt(p.final), mono = true)
            if (p.hasBase) KeyValueRow("Base", pt(p.base), mono = true)
            KeyValueRow("Entry", pt(p.entry), mono = true)
            KeyValueRow("Holding", listOfNotNull(pt(p.holding), p.loiter?.let { "$it turns" }).joinToString(" · "), mono = true)
            if (p.hasLongFinal) KeyValueRow("Long final entry", pt(p.longEntry), mono = true)
        }
    }
}

@Composable
private fun RunwayDiagram(a: Airport, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF071018))) {
        val c = center
        val r = size.minDimension / 2
        drawCircle(Hud.Outline, r * 0.95f, c, style = Stroke(1.5f))
        listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (l, d) ->
            val rad = Math.toRadians(d.toDouble())
            val p = Offset(c.x + (r * 0.82f * Math.sin(rad)).toFloat() - 5f, c.y - (r * 0.82f * Math.cos(rad)).toFloat() - 8f)
            safeText(tm, l, p, TextStyle(color = if (l == "N") Hud.Amber else Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold))
        }
        val runways = a.runways
        val n = runways.size
        runways.forEachIndexed { i, rw ->
            val hdg = rw.ends.firstOrNull()?.headingTrue?.toFloat() ?: return@forEachIndexed
            val offset = (i - (n - 1) / 2f) * 12f
            rotate(hdg, c) {
                drawRect(Hud.TextDim, Offset(c.x - 5f + offset, c.y - r * 0.6f), androidx.compose.ui.geometry.Size(10f, r * 1.2f))
                drawLine(Color.White, Offset(c.x + offset, c.y - r * 0.55f), Offset(c.x + offset, c.y + r * 0.55f), 1f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
        }
    }
}

@Composable
private fun NearestFields(nav: NavHostController, theaterId: String, a: Airport, set: AirportSet) {
    val near = remember(a, set) {
        set.airports.filter { it.id != a.id && it.runways.isNotEmpty() && airportKind(it) != "Carrier" }
            .map { it to bearingRange(a.x, a.y, it.x, it.y) }.sortedBy { it.second.second }.take(6)
    }
    if (near.isEmpty()) return
    SectionCard("Nearest diverts", accent = Hud.Green) {
        near.forEach { (o, br) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { nav.go(Routes.airport(theaterId, o.id)) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(o.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(o.icao, o.tacan?.label, o.runways.joinToString(" ") { it.name }).joinToString(" · "), fontSize = 12.sp, color = Hud.TextDim, maxLines = 1)
                }
                Text("${Fmt.hdg(br.first)} / ${Fmt.num(br.second, 0)} nm", style = LocalExtra.current.mono, color = Hud.Green)
            }
        }
    }
}

@Suppress("unused") private fun String.up() = uppercase(Locale.US)

/** Draws a label only if it doesn't overlap one already placed (declutters zoomed-out maps). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.placeLabel(
    tm: androidx.compose.ui.text.TextMeasurer, text: String, topLeft: Offset, style: TextStyle,
    placed: MutableList<androidx.compose.ui.geometry.Rect>,
): Boolean {
    if (topLeft.x > size.width || topLeft.y > size.height) return false
    val layout = tm.measure(text, style)
    val r = androidx.compose.ui.geometry.Rect(topLeft.x, topLeft.y, topLeft.x + layout.size.width, topLeft.y + layout.size.height)
    if (r.right < 0 || r.bottom < 0) return false
    if (placed.any { it.overlaps(r) }) return false
    placed += r
    drawText(layout, topLeft = topLeft)
    return true
}
