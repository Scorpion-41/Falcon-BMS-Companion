package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.LocalAirport
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.DataIndex
import com.bmscompanion.app.data.EncyEntry
import com.bmscompanion.app.data.Labels
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.Routes
import com.bmscompanion.app.ui.components.AssetImage
import com.bmscompanion.app.ui.components.AdaptiveSplit
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.ChipRow
import com.bmscompanion.app.ui.components.ContentColumn
import com.bmscompanion.app.ui.components.EmptyState
import com.bmscompanion.app.ui.components.FavoriteButton
import com.bmscompanion.app.ui.components.GroupHeader
import com.bmscompanion.app.ui.components.ListDetail
import com.bmscompanion.app.ui.components.ListRow
import com.bmscompanion.app.ui.components.LoadingBox
import com.bmscompanion.app.ui.components.SearchField
import com.bmscompanion.app.ui.components.Tag
import com.bmscompanion.app.ui.components.TagFlow
import com.bmscompanion.app.ui.components.Thumb
import com.bmscompanion.app.ui.components.isWide
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class HubTile(val title: String, val subtitle: String, val icon: ImageVector, val color: Color, val route: String)

@Composable
private fun TileGrid(tiles: List<HubTile>, nav: NavHostController, minTile: androidx.compose.ui.unit.Dp = 160.dp) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = (maxWidth / minTile).toInt().coerceIn(2, 6)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { t ->
                        Column(
                            Modifier.weight(1f).height(118.dp).clip(RoundedCornerShape(16.dp))
                                .background(Brush.linearGradient(listOf(t.color.copy(alpha = 0.16f), Hud.Surface)))
                                .border(1.dp, t.color.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                                .clickable { nav.go(t.route) }.padding(14.dp),
                        ) {
                            Icon(t.icon, null, tint = t.color, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.weight(1f))
                            Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.subtitle, fontSize = 11.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(nav: NavHostController) {
    val index by produceState<DataIndex?>(null) { value = Repo.index() }
    val best = Repo.getInt("bullseye_best", 0)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Hero
        Box(Modifier.fillMaxWidth().height(210.dp)) {
            AssetImage("maps/korea.webp", Modifier.fillMaxSize(), ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Hud.Bg.copy(alpha = 0.35f), Hud.Bg))))
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Text("FALCON BMS", style = LocalExtra.current.overline, color = Hud.Green)
                Text("BMS Companion", style = MaterialTheme.typography.displaySmall)
                Text("BMS ${index?.bmsVersion ?: "4.38"} · ${index?.theaters?.count { it.primary } ?: 0} theaters · ${index?.theaters?.size ?: 0} incl. add-ons", color = Hud.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            ContentColumn(maxWidth = 1100.dp) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Hud.Surface2).clickable { nav.go(Routes.search()) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, tint = Hud.TextDim)
                    Spacer(Modifier.width(12.dp))
                    Text("Search everything: aircraft, SAMs, ICAO, TACAN, brevity…", color = Hud.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Default theater", color = Hud.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TheaterPicker(Repo.selectedTheater.value) { Repo.setTheater(it) }
                }
                // Live mission entry point (needs the PC bridge)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Hud.Green.copy(alpha = 0.10f))
                        .border(1.dp, Hud.Green.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).clickable { nav.go(Routes.MISSION) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.MyLocation, null, tint = Hud.Green, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Live mission", style = MaterialTheme.typography.titleMedium, color = Hud.Text)
                        Text("Map & AWACS picture · briefing · loadout · comms · EZBoards (PC bridge)", style = MaterialTheme.typography.bodySmall, color = Hud.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("OPEN ›", color = Hud.Green, style = MaterialTheme.typography.labelLarge)
                }
                Text("REFERENCE", style = LocalExtra.current.overline, color = Hud.Amber)
                TileGrid(
                    listOf(
                        HubTile("Aircraft", "Flyable types & loadouts", Icons.Default.Flight, Hud.Amber, Routes.ARSENAL),
                        HubTile("Threat Guide", "SAM · AAA · fighters · ships", Icons.Default.Radar, Hud.Red, Routes.THREATS),
                        HubTile("HARM & RWR", "ALIC codes · RWR symbols", Icons.Default.Warning, Hud.Green, Routes.HARM),
                        HubTile("Airfields", "Runways · ILS · TACAN", Icons.Default.LocalAirport, Hud.Cyan, Routes.AIRPORTS),
                        HubTile("Encyclopedia", "TacRef database", Icons.Default.MenuBook, Hud.Blue, Routes.ENCY),
                        HubTile("Favorites", "Your bookmarks", Icons.Default.Star, Hud.Amber, Routes.FAVORITES),
                    ),
                    nav,
                )
                Text("COCKPIT", style = LocalExtra.current.overline, color = Hud.Amber, modifier = Modifier.padding(top = 6.dp))
                TileGrid(cockpitTiles(best), nav)
                Text(
                    "Data extracted from your Falcon BMS 4.38 install (Objects DB, TacRefDB, Stations+ILS, ATC, BmsRack, theater terrain) and the BMS manuals. Not affiliated with BMS.",
                    style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint, modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        }
    }
}

private fun cockpitTiles(best: Int) = listOf(
    HubTile("F-16 HOTAS", "Real stick & throttle", Icons.Default.Gamepad, Hud.Green, Routes.hotas("f16")),
    HubTile("F-15C HOTAS", "Real stick & throttle", Icons.Default.SettingsInputComponent, Hud.Green, Routes.hotas("f15c")),
    HubTile("Checklists", "Normal & emergency", Icons.Default.Checklist, Hud.Cyan, Routes.CHECKLISTS),
    HubTile("Comms & Brevity", "9-line · ATC · tanker", Icons.Default.RecordVoiceOver, Hud.Magenta, Routes.COMMS),
    HubTile("Bullseye Trainer", if (best > 0) "Best score $best" else "SA & orientation game", Icons.Default.TrackChanges, Hud.Amber, Routes.BULLSEYE),
    HubTile("Tools", "Calculators & converters", Icons.Default.Build, Hud.Blue, Routes.TOOLS),
)

@Composable
fun CockpitHubScreen(nav: NavHostController) {
    Column(Modifier.fillMaxSize()) {
        BmsTopBar("Cockpit", "HOTAS · checklists · comms · training")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            ContentColumn(maxWidth = 1100.dp) {
                TileGrid(cockpitTiles(Repo.getInt("bullseye_best", 0)) + listOf(
                    HubTile("HARM & RWR", "ALIC · symbols", Icons.Default.Warning, Hud.Green, Routes.HARM),
                    HubTile("Favorites", "Bookmarks", Icons.Default.Star, Hud.Amber, Routes.FAVORITES),
                ), nav)
            }
        }
    }
}

// ============================ Encyclopedia ============================

@Composable
fun EncyclopediaScreen(nav: NavHostController) {
    val all by produceState<List<EncyEntry>?>(null) { value = Repo.encyclopedia() }
    var q by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    var sub by rememberSaveable { mutableStateOf<String?>(null) }
    var theaterOnly by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val wide = isWide()
    LaunchedEffect(wide, all) { if (wide && selected == null) selected = all?.firstOrNull { Repo.selectedTheater.value in it.theaters }?.key }
    ListDetail(
        selected = selected != null,
        list = {
            Column(Modifier.fillMaxSize()) {
                BmsTopBar("Encyclopedia", "Tactical reference database", onBack = { nav.popBackStack() })
                val list = all ?: run { LoadingBox(); return@Column }
                val th = Repo.selectedTheater.value
                val cats = remember(list) { list.map { it.catName }.distinct() }
                val subs = remember(list, cat) { list.filter { it.catName == cat }.mapNotNull { it.subName }.distinct() }
                val filtered = remember(list, q, cat, sub, theaterOnly, th) {
                    val nq = q.norm()
                    list.filter { e ->
                        (!theaterOnly || th in e.theaters) && (cat == null || e.catName == cat) && (sub == null || e.subName == sub) &&
                            (nq.isEmpty() || e.name.norm().contains(nq))
                    }
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchField(q, { q = it }, "Search vehicles, ships, aircraft, munitions")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                com.bmscompanion.app.ui.components.HudChip(if (theaterOnly) "Current theater only" else "All theaters", theaterOnly) { theaterOnly = !theaterOnly }
                                Spacer(Modifier.weight(1f))
                                Text("${filtered.size}", style = LocalExtra.current.monoSmall, color = Hud.TextFaint)
                            }
                        }
                        ChipRow(cats, cat, { it }, { cat = it; sub = null })
                        if (subs.isNotEmpty()) { Spacer(Modifier.height(6.dp)); ChipRow(subs, sub, { it }, { sub = it }) }
                    }
                    items(filtered, key = { it.key }) { e ->
                        ListRow(e.name, listOfNotNull(e.subName ?: e.catName, e.rwr?.let { "RWR: $it" }).joinToString(" · "), selected = selected == e.key, leading = { Thumb(e.pic) }) {
                            if (wide) selected = e.key else nav.go(Routes.ency(e.key))
                        }
                    }
                }
            }
        },
        detail = { selected?.let { EncyDetail(nav, it, null) } },
    )
}

@Composable
fun EncyDetailRoute(nav: NavHostController, key: String) = EncyDetail(nav, key) { nav.popBackStack() }

@Composable
fun EncyDetail(nav: NavHostController, key: String, onBack: (() -> Unit)?) {
    val e by produceState<EncyEntry?>(null, key) { value = Repo.encyMap()[key] }
    val names by produceState<Map<String, String>>(emptyMap()) { value = Repo.theaterNames() }
    Column(Modifier.fillMaxSize()) {
        BmsTopBar(e?.name ?: "Entry", e?.let { listOfNotNull(it.catName, it.subName).joinToString(" · ") }, onBack = onBack, actions = { FavoriteButton("en:$key") })
        val en = e ?: run { LoadingBox(); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            AdaptiveSplit(left = {
                HeroImage(en.pic)
            }, right = {
                EncyclopediaCards(en)
                Text("In theaters: " + en.theaters.joinToString { names[it] ?: it }, style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint)
            })
        }
    }
}

// ============================ Favorites ============================

@Composable
fun FavoritesScreen(nav: NavHostController) {
    val favs by Repo.favorites.collectAsState()
    val resolved by produceState<List<Triple<String, String, String>>?>(null, favs) {
        val ac = Repo.aircraftMap(); val wp = Repo.weaponMap(); val en = Repo.encyMap(); val th = Repo.threats()
        value = favs.mapNotNull { f ->
            val (type, rest) = f.substringBefore(':') to f.substringAfter(':')
            when (type) {
                "ac" -> ac[rest]?.let { Triple("Aircraft", it.name, Routes.aircraft(rest)) }
                "wp" -> wp[rest]?.let { Triple("Weapons", it.name, Routes.weapon(rest)) }
                "en" -> en[rest]?.let { Triple("Encyclopedia", it.name, Routes.ency(rest)) }
                "th" -> th.firstOrNull { it.id == rest }?.let { Triple("Threats", it.name, Routes.threat(rest)) }
                "ap" -> {
                    val theaterId = rest.substringBefore(':'); val id = rest.substringAfter(':').toIntOrNull() ?: return@mapNotNull null
                    val t = Repo.theater(theaterId) ?: return@mapNotNull null
                    Repo.airportSet(t.airportSet).airports.firstOrNull { it.id == id }?.let { Triple("Airfields", "${it.name} (${t.name})", Routes.airport(theaterId, id)) }
                }
                else -> null
            }
        }.sortedWith(compareBy({ it.first }, { it.second }))
    }
    Column(Modifier.fillMaxSize()) {
        BmsTopBar("Favorites", "Tap ☆ on any page to bookmark it", onBack = { nav.popBackStack() })
        val r = resolved ?: run { LoadingBox(); return@Column }
        if (r.isEmpty()) { EmptyState("No favorites yet"); return@Column }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            r.groupBy { it.first }.forEach { (g, items) ->
                item { GroupHeader(g, items.size) }
                items(items) { (_, name, route) -> ListRow(name, onClick = { nav.go(route) }) }
            }
        }
    }
}

// ============================ Global search ============================

private data class Hit(val group: String, val title: String, val subtitle: String, val route: String?, val score: Int)

@Composable
fun SearchScreen(nav: NavHostController, initial: String) {
    var q by rememberSaveable { mutableStateOf(initial) }
    var hits by remember { mutableStateOf<List<Hit>?>(emptyList()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(q) {
        if (q.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(220)
        hits = null
        hits = withContext(Dispatchers.Default) { searchAll(q.trim()) }
    }
    Column(Modifier.fillMaxSize()) {
        BmsTopBar("Search", "Everything in the app", onBack = { nav.popBackStack() })
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SearchField(q, { q = it }, "Try: SA-10, RKSO, 75X, JDAM, BANDIT, TMS…", Modifier.focusRequester(focus))
        }
        val h = hits
        when {
            h == null -> LoadingBox()
            q.trim().length < 2 -> SearchHints { q = it }
            h.isEmpty() -> EmptyState("No results for “$q”")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                h.groupBy { it.group }.forEach { (g, list) ->
                    item(key = "g$g") { GroupHeader(g, list.size) }
                    items(list.take(40)) { hit -> ListRow(hit.title, hit.subtitle, onClick = { hit.route?.let { nav.go(it) } }) }
                }
            }
        }
    }
}

@Composable
private fun SearchHints(onPick: (String) -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("QUICK SEARCHES", style = LocalExtra.current.overline, color = Hud.TextDim)
        TagFlow {
            listOf("F-16CM-50", "SA-6", "SA-10", "MiG-29", "AIM-120", "GBU-12", "Osan", "RKTN", "75X", "BANDIT", "TMS", "DMS", "JFS", "Tanker").forEach {
                Box(Modifier.clickable { onPick(it) }) { Tag(it, Hud.Cyan) }
            }
        }
    }
}

private suspend fun searchAll(q: String): List<Hit> {
    val nq = q.norm()
    fun score(name: String): Int { val n = name.norm(); return when { n == nq -> 100; n.startsWith(nq) -> 80; n.contains(nq) -> 50; else -> 0 } }
    val out = ArrayList<Hit>()
    Repo.aircraft().forEach { a -> val s = score(a.name); if (s > 0) out += Hit("Aircraft", a.name, "${a.familyTitle} · ${a.role}", Routes.aircraft(a.key), s) }
    Repo.threats().forEach { t ->
        val s = maxOf(score(t.name), t.aliases.maxOfOrNull { score(it) } ?: 0, if (t.rwr.values.any { it?.norm() == nq }) 60 else 0, if (t.harmAlic?.contains(q) == true) 60 else 0)
        if (s > 0) out += Hit("Threat guide", t.name, listOfNotNull(t.side, Labels.threatCategory[t.category], t.rwrSymbol()?.let { "RWR $it" }).joinToString(" · "), Routes.threat(t.id), s)
    }
    Repo.weapons().forEach { w -> val s = score(w.name); if (s > 0) out += Hit("Weapons & stores", w.name, Labels.weaponCategory[w.category] ?: w.category, Routes.weapon(w.key), s) }
    val index = Repo.index()
    val seenSets = HashSet<String>()
    for (t in index.theaters.filter { it.primary }) {
        if (!seenSets.add(t.airportSet)) continue
        val set = Repo.airportSet(t.airportSet)
        set.airports.forEach { a ->
            val s = maxOf(score(a.name), if (a.icao?.norm() == nq) 100 else 0, if (a.tacan?.label?.norm() == nq) 90 else 0,
                if (a.runways.any { r -> r.ends.any { it.ils?.norm() == nq } }) 90 else 0,
                if (listOfNotNull(a.freqs?.towerUhf, a.freqs?.towerVhf, a.freqs?.approachUhf, a.freqs?.groundUhf, a.freqs?.atisVhf).any { it.norm() == nq }) 90 else 0)
            if (s > 0) out += Hit("Airfields", a.name + (a.icao?.let { " ($it)" } ?: ""), "${t.name} · " + listOfNotNull(a.tacan?.label, a.freqs?.towerUhf?.let { "TWR $it" }).joinToString(" · "), Routes.airport(t.id, a.id), s)
        }
        set.navaids.forEach { n -> if (score(n.name) > 0 || n.tacan?.label?.norm() == nq) out += Hit("Navaids", n.name, "${t.name} · ${n.tacan?.label ?: ""}", null, 40) }
    }
    Repo.encyclopedia().forEach { e -> val s = score(e.name); if (s > 0) out += Hit("Encyclopedia", e.name, e.subName ?: e.catName, Routes.ency(e.key), s - 5) }
    Repo.harm()?.alicCodes?.forEach { a -> if (score(a.system) > 0 || a.alic == q) out += Hit("HARM ALIC", "${a.system} — ${a.alic}", listOfNotNull(a.category, a.radar).joinToString(" · "), Routes.HARM, 70) }
    Repo.comms()?.let { c ->
        c.brevity.forEach { b -> val s = score(b.word); if (s >= 50) out += Hit("Brevity", b.word, b.meaning, Routes.COMMS, s) }
        c.glossary.forEach { g -> if (g.term.norm() == nq) out += Hit("Glossary", g.term, g.meaning, Routes.COMMS, 60) }
    }
    listOf("f16" to "F-16", "f15c" to "F-15C").forEach { (id, label) ->
        Repo.hotas(id)?.controls?.forEach { c -> c.buttons.forEach { b -> if (score(b.name) > 0 || b.id.norm() == nq) out += Hit("HOTAS", "$label · ${b.name}", b.summary, Routes.hotas(id), 70) } }
    }
    Repo.checklists().forEach { cl -> cl.sections.forEach { s ->
        val sc = score(s.title)
        val inItems = sc == 0 && s.items.any { (it.text ?: "").norm().contains(nq) }
        if (sc > 0 || inItems) out += Hit("Checklists", s.title, "${cl.title} · ${s.group}", Routes.checklist(cl.id + "|" + s.group), if (sc > 0) sc else 30)
    } }
    return out.sortedWith(compareByDescending<Hit> { groupOrder(it.group) }.thenByDescending { it.score }.thenBy { it.title })
        .let { list -> val best = list.groupBy { it.group }.mapValues { e -> e.value.maxOf { it.score } }; list.sortedWith(compareByDescending<Hit> { best[it.group] }.thenByDescending { it.score }) }
}

private fun groupOrder(g: String) = when (g) { "Aircraft" -> 9; "Threat guide" -> 8; "Airfields" -> 7; "Weapons & stores" -> 6; "HOTAS" -> 5; else -> 0 }
