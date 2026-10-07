package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.Aircraft
import com.bmscompanion.app.data.AircraftVariant
import com.bmscompanion.app.data.EncyEntry
import com.bmscompanion.app.data.Labels
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Weapon
import com.bmscompanion.app.ui.Routes
import com.bmscompanion.app.ui.components.AssetImage
import com.bmscompanion.app.ui.components.AdaptiveSplit
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.ChipRow
import com.bmscompanion.app.ui.components.CollapsibleCard
import com.bmscompanion.app.ui.components.ContentColumn
import com.bmscompanion.app.ui.components.EmptyState
import com.bmscompanion.app.ui.components.FavoriteButton
import com.bmscompanion.app.ui.components.Fmt
import com.bmscompanion.app.ui.components.GroupHeader
import com.bmscompanion.app.ui.components.KeyValueRow
import com.bmscompanion.app.ui.components.ListDetail
import com.bmscompanion.app.ui.components.ListRow
import com.bmscompanion.app.ui.components.LoadingBox
import com.bmscompanion.app.ui.components.Paragraph
import com.bmscompanion.app.ui.components.PinnedSearchField
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.Stat
import com.bmscompanion.app.ui.components.StatGrid
import com.bmscompanion.app.ui.components.Tag
import com.bmscompanion.app.ui.components.TagFlow
import com.bmscompanion.app.ui.components.Thumb
import com.bmscompanion.app.ui.components.isWide
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import java.util.Locale

private val NOT_ALNUM = Regex("[^a-z0-9]")
/** compiled once: lists and the map search call this for every row on every keystroke */
fun String.norm() = lowercase(Locale.US).replace(NOT_ALNUM, "")

// ======================= Arsenal (list) =======================

/** The `arsenal` route: the Reference section, open on Arsenal. */
@Composable
fun ArsenalScreen(nav: NavHostController) = ReferenceScreen(nav, ReferenceTab.ARSENAL)

/** The Arsenal page of the Reference section: flyable aircraft and the stores they carry, in two lists. */
@Composable
internal fun ArsenalPage(nav: NavHostController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedAc by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedWp by rememberSaveable { mutableStateOf<String?>(null) }
    val wide = isWide()
    val selected = if (tab == 0) selectedAc else selectedWp
    // Tablet: open with something useful in the detail pane instead of an empty "Select an item".
    LaunchedEffect(wide, tab) {
        if (!wide) return@LaunchedEffect
        if (tab == 0 && selectedAc == null) selectedAc = Repo.aircraft().let { l -> (l.firstOrNull { it.key == "f-16cm-50" } ?: l.firstOrNull())?.key }
        if (tab == 1 && selectedWp == null) selectedWp = Repo.weapons().let { l -> (l.firstOrNull { it.key == "aim-120c-amraam" } ?: l.firstOrNull())?.key }
    }

    ListDetail(
        selected = selected != null,
        list = {
            Column(Modifier.fillMaxSize()) {
                // the section's own bar names the page; the two lists are its sub-pages
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = Hud.Bg,
                    contentColor = Hud.Amber,
                    indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = Hud.Amber) },
                ) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Aircraft") }, unselectedContentColor = Hud.TextDim)
                    Tab(tab == 1, { tab = 1 }, text = { Text("Weapons & Stores") }, unselectedContentColor = Hud.TextDim)
                }
                if (tab == 0) AircraftList(selectedAc) { key -> if (wide) selectedAc = key else nav.go(Routes.aircraft(key)) }
                else WeaponList(selectedWp) { key -> if (wide) selectedWp = key else nav.go(Routes.weapon(key)) }
            }
        },
        detail = {
            if (tab == 0) selectedAc?.let { AircraftDetail(nav, it, onBack = null) }
            else selectedWp?.let { WeaponDetail(nav, it, onBack = null) }
        },
    )
}

@Composable
private fun TheaterFilter(selected: String?, onSelect: (String?) -> Unit) {
    val index by produceState<com.bmscompanion.app.data.DataIndex?>(null) { value = Repo.index() }
    var open by remember { mutableStateOf(false) }
    val name = index?.theaters?.firstOrNull { it.id == selected }?.name ?: "All theaters"
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).clickable { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = if (selected == null) Hud.TextDim else Hud.Cyan)
            Icon(Icons.Default.ArrowDropDown, null, tint = Hud.TextDim)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("All theaters") }, { onSelect(null); open = false })
            index?.theaters?.forEach { t -> DropdownMenuItem({ Text(t.name) }, { onSelect(t.id); open = false }) }
        }
    }
}

@Composable
private fun AircraftList(selected: String?, onOpen: (String) -> Unit) {
    val all by produceState<List<Aircraft>?>(null) { value = Repo.aircraft() }
    var q by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf<String?>(null) }
    var theater by rememberSaveable { mutableStateOf<String?>(null) }
    val list = all ?: return LoadingBox()
    val roles = remember(list) { list.map { it.role }.distinct().sorted() }
    val filtered = remember(list, q, role, theater) {
        val nq = q.norm()
        list.filter { a ->
            (role == null || a.role == role) &&
                (theater == null || a.variants.any { theater in it.theaters }) &&
                (nq.isEmpty() || a.name.norm().contains(nq) || a.familyTitle.norm().contains(nq))
        }
    }
    val grouped = remember(filtered) { filtered.groupBy { it.familyTitle } }
    // the search stays at the top however far the list is scrolled; the filters scroll with it
    Column(Modifier.fillMaxSize()) {
    PinnedSearchField(q, { q = it }, "Search aircraft (F-16CM-50, MiG-29…)", Modifier.arsenalProbe("search"))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TheaterFilter(theater) { theater = it }
                    Spacer(Modifier.weight(1f))
                    Text("${filtered.size} aircraft", style = LocalExtra.current.monoSmall, color = Hud.TextFaint)
                }
            }
            ChipRow(roles, role, { it }, { role = it })
        }
        grouped.forEach { (family, acs) ->
            item(key = "h-$family") { GroupHeader(family, acs.size) }
            items(acs, key = { it.key }) { a ->
                ListRow(
                    title = a.name,
                    subtitle = buildString {
                        append(a.role)
                        val v = a.variants.firstOrNull()
                        v?.spec?.inService?.let { append(" · $it") }
                        if (a.variants.size > 1) append(" · ${a.variants.size} theater variants")
                    },
                    selected = selected == a.key,
                    leading = { Thumb(a.pic) },
                    onClick = { onOpen(a.key) },
                )
            }
        }
    }
    }
}

@Composable
private fun WeaponList(selected: String?, onOpen: (String) -> Unit) {
    val all by produceState<List<Weapon>?>(null) { value = Repo.weapons() }
    var q by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    val list = all ?: return LoadingBox()
    val cats = remember(list) { Labels.weaponCategory.keys.filter { k -> list.any { it.category == k } } }
    val filtered = remember(list, q, cat) {
        val nq = q.norm()
        list.filter { (cat == null || it.category == cat) && (nq.isEmpty() || it.name.norm().contains(nq)) }
    }
    val grouped = remember(filtered) { Labels.weaponCategory.keys.mapNotNull { k -> filtered.filter { it.category == k }.takeIf { it.isNotEmpty() }?.let { k to it } } }
    Column(Modifier.fillMaxSize()) {
    PinnedSearchField(q, { q = it }, "Search stores (AIM-120, GBU-12, Sniper…)", Modifier.arsenalProbe("search"))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ChipRow(cats, cat, { Labels.weaponCategory[it] ?: it }, { cat = it })
        }
        grouped.forEach { (c, ws) ->
            item(key = "h-$c") { GroupHeader(Labels.weaponCategory[c] ?: c, ws.size) }
            items(ws, key = { it.key }) { w ->
                ListRow(
                    title = w.name,
                    subtitle = listOfNotNull(
                        w.guidance.joinToString(" / ").ifBlank { null },
                        Fmt.lbs(w.weightLbs),
                        if (w.carriedBy.isNotEmpty()) "${w.carriedBy.size} aircraft" else null,
                    ).joinToString(" · "),
                    selected = selected == w.key,
                    leading = { Thumb(w.pic) },
                    onClick = { onOpen(w.key) },
                )
            }
        }
    }
    }
}

// ======================= Aircraft detail =======================

@Composable
fun AircraftDetailRoute(nav: NavHostController, key: String) = AircraftDetail(nav, key, onBack = { nav.popBackStack() })

@Composable
fun AircraftDetail(nav: NavHostController, key: String, onBack: (() -> Unit)?) {
    val ac by produceState<Aircraft?>(null, key) { value = Repo.aircraftMap()[key] }
    val weapons by produceState<Map<String, Weapon>?>(null) { value = Repo.weaponMap() }
    val ency by produceState<EncyEntry?>(null, ac) { value = ac?.tacref?.let { Repo.encyMap()[it] } }
    val names by produceState<Map<String, String>>(emptyMap()) { value = Repo.theaterNames() }
    val a = ac
    val wmap = weapons
    Column(Modifier.fillMaxSize()) {
        BmsTopBar(a?.name ?: "Aircraft", a?.let { "${it.familyTitle} · ${it.role}" }, onBack = onBack, actions = { FavoriteButton("ac:$key") })
        if (a == null || wmap == null) { LoadingBox(); return@Column }
        val preferred = Repo.selectedTheater.value
        var variantIdx by rememberSaveable(key) {
            mutableIntStateOf(a.variants.indexOfFirst { preferred in it.theaters }.coerceAtLeast(0))
        }
        val v = a.variants.getOrNull(variantIdx) ?: a.variants.firstOrNull()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (v == null) return@Column
            AdaptiveSplit(left = {
                HeroImage(a.pic)
                TagFlow {
                    Tag(a.role, Hud.Amber, filled = true)
                    a.familyTitle.takeIf { it.isNotBlank() }?.let { Tag(it, Hud.Cyan) }
                    v?.spec?.fm?.type?.let { Tag(if (it == "FM_AFM") "Advanced FM" else it, Hud.Green) }
                    v?.spec?.datFile?.let { Tag("$it.txtpb", Hud.TextDim) }
                }
                if (a.variants.size > 1) {
                    VariantPicker(a.variants, variantIdx, names) { variantIdx = it }
                } else {
                    Text("Theaters: " + v.theaters.joinToString { names[it] ?: it }, style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
                }
                SectionCard("Performance & specs") {
                    val s = v.spec
                    StatGrid(
                        listOf(
                            Stat("Max G", s.fm?.maxG?.let { Fmt.num(it, if (it % 1 == 0.0) 0 else 1) + " G" }),
                            Stat("Max AoA", s.fm?.aoaMax?.let { "${Fmt.num(it)}°" }),
                            Stat("Max speed", Fmt.kts(s.maxSpeedKts)),
                            Stat("Max CAS", Fmt.kts(s.fm?.maxVcasKts)),
                            Stat("Corner speed", Fmt.kts(s.fm?.cornerKts)),
                            Stat("Ceiling", Fmt.ft(s.ceilingFt)),
                            Stat("Cruise alt", Fmt.ft(s.cruiseAltFt)),
                            Stat("Empty weight", Fmt.lbs(s.emptyWeightLbs)),
                            Stat("Internal fuel", Fmt.lbs(s.internalFuelLbs)),
                            Stat("Max weight", Fmt.lbs(s.maxWeightLbs)),
                            Stat("Length", Fmt.ft(s.fm?.lengthFt)),
                            Stat("Wingspan", Fmt.ft(s.fm?.spanFt)),
                            Stat("Wing area", s.fm?.wingAreaSqft?.let { Fmt.num(it) + " ft²" }),
                            Stat("Crew", s.crew?.toString()),
                            Stat("In service", s.inService?.toString()),
                            Stat("RCS factor", s.rcs?.let { Fmt.num(it, 2) }),
                            Stat("Chaff", s.fm?.chaff?.toString()),
                            Stat("Flares", s.fm?.flares?.toString()),
                        ),
                    )
                    s.radar?.let { r ->
                        Spacer(Modifier.height(12.dp))
                        KeyValueRow("Radar", r.name, mono = true, valueColor = Hud.Cyan)
                        KeyValueRow("Detection range", Fmt.nm(r.detectionNm), mono = true)
                        KeyValueRow("Scan width", r.scanWidthDeg?.let { "±${Fmt.num(it / 2)}°" }, mono = true)
                    }
                    v.gun?.let { g ->
                        KeyValueRow("Gun", "${g.name} · ${g.rounds} rds", mono = true, valueColor = Hud.Amber)
                    }
                }
            }, right = {
                LoadoutSection(nav, v, wmap)
                ency?.let { EncyclopediaCards(it) }
            }, leftWeight = 0.9f, rightWeight = 1.1f)
        }
    }
}

@Composable
fun HeroImage(pic: String?) {
    Column(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(320f / 151f).clip(RoundedCornerShape(16.dp)).background(Hud.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            AssetImage(Repo.tacrefImagePath(pic), Modifier.fillMaxSize(), ContentScale.Crop) {
                Text("No image", color = Hud.TextFaint, modifier = Modifier.align(Alignment.Center))
            }
        }
        PhotoCreditLine(pic)
    }
}

/**
 * The small line under a photograph that is not BMS's own art (a Wikimedia Commons file, `ph-…`): its author and
 * licence, as CC BY and CC BY-SA require; a tap opens the file's page. Nothing for BMS's pictures.
 */
@Composable
fun PhotoCreditLine(pic: String?) {
    if (pic == null || !pic.startsWith("ph-")) return
    val credit by produceState<com.bmscompanion.app.data.PhotoCredit?>(null, pic) { value = Repo.photoCredits()[pic] }
    val c = credit ?: return
    val open = com.bmscompanion.app.data.Platform.openUrl
    Text(
        "Photo: ${c.author}, ${c.licence}",
        Modifier.fillMaxWidth().padding(top = 3.dp, start = 4.dp, end = 4.dp)
            .let { m -> if (open != null && c.source.isNotBlank()) m.clickable { open(c.source) } else m },
        fontSize = 10.sp, color = Hud.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun VariantPicker(variants: List<AircraftVariant>, idx: Int, names: Map<String, String>, onPick: (Int) -> Unit) {
    SectionCard("Theater data variant", accent = Hud.Cyan) {
        Text(
            "Loadouts and stats differ between theater databases. Pick the one you fly:",
            style = MaterialTheme.typography.bodySmall, color = Hud.TextDim,
        )
        Spacer(Modifier.height(8.dp))
        // a drop-down rather than a row of chips: the row ran off the card's edge and hid the variants past it
        var open by remember { mutableStateOf(false) }
        fun label(v: AircraftVariant) = v.theaters.joinToString(", ") { names[it] ?: it }
        Box(Modifier.fillMaxWidth().arsenalProbe("variants")) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Hud.Surface2)
                    .border(1.dp, Hud.Cyan.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .clickable { open = true }.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label(variants[idx.coerceIn(0, variants.lastIndex)]), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = Hud.Cyan, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text("${idx + 1} of ${variants.size}", style = LocalExtra.current.monoSmall, color = Hud.TextFaint, modifier = Modifier.padding(horizontal = 6.dp))
                Icon(Icons.Default.ArrowDropDown, null, tint = Hud.TextDim)
            }
            DropdownMenu(open, { open = false }, Modifier.widthIn(min = 260.dp, max = 520.dp).heightIn(max = 420.dp)) {
                variants.forEachIndexed { i, v ->
                    val here = i == idx
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    label(v), maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    color = if (here) Hud.Amber else Hud.Text,
                                    fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${v.theaters.size} theater${if (v.theaters.size == 1) "" else "s"}" +
                                        (v.spec.datFile?.takeIf { variants.map { x -> x.spec.datFile }.distinct().size > 1 }?.let { " · $it" } ?: ""),
                                    style = LocalExtra.current.monoSmall, color = Hud.TextFaint,
                                )
                            }
                        },
                        leadingIcon = { Text(if (here) "●" else "", color = Hud.Amber, modifier = Modifier.width(12.dp)) },
                        onClick = { onPick(i); open = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadoutSection(nav: NavHostController, v: AircraftVariant, wmap: Map<String, Weapon>) {
    if (v.stations.isEmpty()) return
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var station by rememberSaveable { mutableIntStateOf(-1) }
    SectionCard("Stores & stations", trailing = {
        SingleChoiceSegmentedButtonRow {
            listOf("Stations", "Matrix").forEachIndexed { i, l ->
                SegmentedButton(
                    selected = mode == i, onClick = { mode = i },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = Hud.Amber.copy(alpha = 0.2f), activeContentColor = Hud.Amber, inactiveContainerColor = Hud.Surface2, inactiveContentColor = Hud.TextDim),
                    icon = {},
                ) { Text(l, fontSize = 12.sp) }
            }
        }
    }) {
        if (mode == 0) {
            val stations = v.stations.sortedBy { it.n }
            val current = stations.firstOrNull { it.n == station } ?: stations.first()
            StationStrip(stations.map { it.n to it.label }, current.n) { station = it }
            Spacer(Modifier.height(10.dp))
            Text(
                "Station ${current.n}" + (current.label.takeIf { it.isNotBlank() }?.let { " · ${it.uppercase(Locale.US)}" } ?: "") + (current.list?.let { "  ($it)" } ?: ""),
                style = MaterialTheme.typography.titleSmall, color = Hud.Amber,
            )
            Spacer(Modifier.height(6.dp))
            val byCat = current.weapons.mapNotNull { sw -> wmap[sw.key]?.let { it to sw } }.groupBy { it.first.category }
            Labels.weaponCategory.keys.filter { it in byCat }.forEach { c ->
                Text((Labels.weaponCategory[c] ?: c).uppercase(Locale.US), style = LocalExtra.current.overline, color = Hud.Green, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                byCat[c]!!.forEach { (w, sw) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { nav.go(Routes.weapon(w.key)) }.padding(vertical = 7.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(w.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        sw.rack?.let { Text(it, style = LocalExtra.current.monoSmall, color = Hud.TextFaint, modifier = Modifier.padding(end = 10.dp)) }
                        Text("×${sw.max}", style = LocalExtra.current.mono, color = Hud.Cyan)
                    }
                }
            }
        } else {
            WeaponMatrix(nav, v, wmap)
        }
    }
}

/** The stations as buttons, wrapping onto a second line where the card is narrow (a scrolled row hid the ones past its edge). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationStrip(stations: List<Pair<Int, String>>, current: Int, onPick: (Int) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().arsenalProbe("stations"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        stations.forEach { (n, label) ->
            val sel = n == current
            Column(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Hud.Amber.copy(alpha = 0.2f) else Hud.Surface2)
                    .border(1.dp, if (sel) Hud.Amber else Hud.Outline, RoundedCornerShape(10.dp))
                    .clickable { onPick(n) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("$n", style = LocalExtra.current.mono, color = if (sel) Hud.Amber else Hud.Text, fontWeight = FontWeight.Bold)
                Text(label.take(10), fontSize = 9.sp, color = Hud.TextDim, maxLines = 1)
            }
        }
    }
}

@Composable
private fun WeaponMatrix(nav: NavHostController, v: AircraftVariant, wmap: Map<String, Weapon>) {
    val stations = v.stations.sortedBy { it.n }
    val rows = remember(v) {
        val keys = stations.flatMap { s -> s.weapons.map { it.key } }.distinct().mapNotNull { wmap[it] }
        keys.sortedWith(compareBy({ Labels.weaponCategory.keys.indexOf(it.category) }, { it.name }))
    }
    val cell = 34.dp
    val nameW = 170.dp
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column {
            Row {
                Spacer(Modifier.width(nameW))
                stations.forEach { s -> Text("${s.n}", Modifier.width(cell), textAlign = TextAlign.Center, style = LocalExtra.current.mono, color = Hud.Amber) }
            }
            var lastCat = ""
            rows.forEach { w ->
                if (w.category != lastCat) {
                    lastCat = w.category
                    Text((Labels.weaponCategory[w.category] ?: w.category).uppercase(Locale.US), style = LocalExtra.current.overline, color = Hud.Green, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                }
                Row(Modifier.clickable { nav.go(Routes.weapon(w.key)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(w.name, Modifier.width(nameW), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    stations.forEach { s ->
                        val sw = s.weapons.firstOrNull { it.key == w.key }
                        Box(Modifier.width(cell).height(22.dp).padding(1.dp).background(if (sw != null) Hud.Cyan.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            if (sw != null) Text(if (sw.max > 1) "${sw.max}" else "●", color = Hud.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ======================= Weapon detail =======================

@Composable
fun WeaponDetailRoute(nav: NavHostController, key: String) = WeaponDetail(nav, key, onBack = { nav.popBackStack() })

@Composable
fun WeaponDetail(nav: NavHostController, key: String, onBack: (() -> Unit)?) {
    val w by produceState<Weapon?>(null, key) { value = Repo.weaponMap()[key] }
    val acs by produceState<Map<String, Aircraft>?>(null) { value = Repo.aircraftMap() }
    val ency by produceState<EncyEntry?>(null, w) { value = w?.tacref?.let { Repo.encyMap()[it] } }
    val wp = w
    Column(Modifier.fillMaxSize()) {
        BmsTopBar(wp?.name ?: "Weapon", wp?.let { Labels.weaponCategory[it.category] }, onBack = onBack, actions = { FavoriteButton("wp:$key") })
        if (wp == null) { LoadingBox(); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // the order a pilot reads a store in: the picture, what it is (Tactical reference, General info, both
            // open), who carries it (folded, as a tree of families), then the sim's figures and the rest of TacRef
            val general = ency?.sections?.firstOrNull { it.lines.isNotEmpty() && it.title.orEmpty().startsWith("General", ignoreCase = true) }
            AdaptiveSplit(left = {
                HeroImage(wp.pic)
                TagFlow {
                    Tag(Labels.weaponCategory[wp.category] ?: wp.category, Hud.Amber, filled = true)
                    wp.guidance.forEach { Tag(it, Hud.Green) }
                }
                ency?.description?.takeIf { it.isNotBlank() }?.let { text ->
                    Box(Modifier.arsenalProbe("tacref")) {
                        CollapsibleCard("Tactical reference", initiallyOpen = true, accent = Hud.Green, preview = text, rememberKey = "wp_tacref") { Paragraph(text) }
                    }
                }
                general?.let { s ->
                    Box(Modifier.arsenalProbe("general")) {
                        CollapsibleCard(s.title ?: "General info", initiallyOpen = true, accent = Hud.Green, preview = s.lines.take(3).joinToString(" · "), rememberKey = "wp_general") {
                            EncySectionLines(s.lines)
                        }
                    }
                }
                val carriers = remember(wp, acs) { wp.carriedBy.mapNotNull { acs?.get(it) } }
                if (carriers.isNotEmpty()) Box(Modifier.arsenalProbe("carriers")) { CarriedByTree(nav, carriers) }
            }, right = {
                SectionCard("Data", Modifier.arsenalProbe("data")) {
                    StatGrid(
                        listOf(
                            Stat("Weight", Fmt.lbs(wp.weightLbs)),
                            Stat("Drag index", wp.drag?.let { Fmt.num(it, if (it % 1 == 0.0) 0 else 2) }),
                            Stat("Campaign range", wp.rangeKm?.takeIf { it > 0 }?.let { "${Fmt.num(it)} km · ${Fmt.num(it / 1.852, 1)} nm" }),
                            Stat("Blast radius", wp.blastRadiusFt?.takeIf { it > 0 }?.let { Fmt.ft(it) }),
                            Stat("Sim class", wp.simClass?.uppercase(Locale.US)),
                            Stat("Sim data", wp.simName),
                        ),
                    )
                    wp.hits?.let { h ->
                        Spacer(Modifier.height(12.dp))
                        Text("CAMPAIGN HIT CHANCE %", style = LocalExtra.current.overline, color = Hud.TextDim)
                        Spacer(Modifier.height(6.dp))
                        listOf("Air" to h.air, "Low air" to h.lowAir, "Ground" to h.ground, "Naval" to h.naval).forEach { (l, v) -> Bar(l, v) }
                    }
                }
                ency?.let { EncyclopediaCards(it, withDescription = false, except = general) }
            })
        }
    }
}

/**
 * Who carries a store, as a tree folded in one card (folded to begin with; the fold is kept per device): one row per
 * aircraft family — the extractor's own family (`Aircraft.family`, BMS's NCTR code, titled `familyTitle`), the same
 * grouping as the Aircraft list — with its count, opening to its variants; a family of one is that aircraft's own row.
 * A variant opens that aircraft.
 */
@Composable
private fun CarriedByTree(nav: NavHostController, carriers: List<Aircraft>) {
    if (carriers.isEmpty()) return
    val families = remember(carriers) {
        carriers.groupBy { it.family ?: it.familyTitle.ifBlank { it.name } }.values
            .map { acs -> (acs.first().familyTitle.ifBlank { acs.first().name }) to acs.sortedWith(compareBy(NumericOrder) { it.name }) }
            .sortedWith(compareBy(NumericOrder) { it.first })
    }
    val open = remember(carriers) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    CollapsibleCard(
        "Carried by ${carriers.size} flyable aircraft",
        initiallyOpen = false,
        accent = Hud.Cyan,
        preview = families.joinToString(" · ") { (title, acs) -> if (acs.size == 1) acs[0].name else "$title (${acs.size})" },
        rememberKey = "wp_carriers",
    ) {
        Column {
            families.forEachIndexed { i, (title, acs) ->
                if (i > 0) com.bmscompanion.app.ui.components.Divider()
                if (acs.size == 1) {
                    TreeRow(acs[0].name, null, expanded = null, indent = false) { nav.go(Routes.aircraft(acs[0].key)) }
                } else {
                    val isOpen = open[title] == true
                    Box(Modifier.arsenalProbe("family/$title")) {
                        TreeRow(title, acs.size, expanded = isOpen, indent = false) { open[title] = !isOpen }
                    }
                    if (isOpen) acs.forEach { a -> TreeRow(a.name, null, expanded = null, indent = true) { nav.go(Routes.aircraft(a.key)) } }
                }
            }
        }
    }
}

/** One row of [CarriedByTree]: a family (with its count and a fold arrow) or an aircraft (indented under its family). */
@Composable
private fun TreeRow(text: String, count: Int?, expanded: Boolean?, indent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(start = if (indent) 30.dp else 0.dp, top = if (indent) 5.dp else 8.dp, bottom = if (indent) 5.dp else 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded != null) {
            Icon(
                Icons.Default.ArrowDropDown, null, tint = Hud.Cyan,
                modifier = Modifier.size(20.dp).rotate(if (expanded) 0f else -90f),
            )
            Spacer(Modifier.width(4.dp))
        } else if (!indent) {
            Spacer(Modifier.width(24.dp))
        }
        Text(
            text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontSize = if (indent) 13.sp else 14.sp,
            fontWeight = if (expanded != null) FontWeight.SemiBold else FontWeight.Normal,
            color = if (expanded != null) Hud.Text else if (indent) Hud.TextDim else Hud.Text,
        )
        count?.let { Text("$it", style = LocalExtra.current.monoSmall, color = Hud.TextFaint) }
    }
}

/** A part's place for the headless check (`--arsenalrender`, ArsenalRender.kt), as `arsenal/<name>`; nothing otherwise. */
internal fun Modifier.arsenalProbe(name: String): Modifier =
    if (!com.bmscompanion.app.ui.screens.wdp.WdpProbe.on) this
    else onGloballyPositioned {
        com.bmscompanion.app.ui.screens.wdp.WdpProbe.rects["arsenal/$name"] = androidx.compose.ui.geometry.Rect(it.positionInRoot(), it.size.toSize())
    }

/** Names in the order a pilot expects: "F-16C-30" before "F-16C-252", digits compared as numbers. */
private object NumericOrder : Comparator<String> {
    private val parts = Regex("\\d+|\\D+")
    override fun compare(a: String, b: String): Int {
        val pa = parts.findAll(a.lowercase(Locale.US)).map { it.value }.toList()
        val pb = parts.findAll(b.lowercase(Locale.US)).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]; val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) (x.toLongOrNull() ?: 0L).compareTo(y.toLongOrNull() ?: 0L) else x.compareTo(y)
            if (c != 0) return c
        }
        return pa.size - pb.size
    }
}

@Composable
private fun Bar(label: String, value: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), fontSize = 12.sp, color = Hud.TextDim)
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Hud.Surface3)) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0, 100) / 100f).height(8.dp).background(if (value > 0) Hud.Green else Color.Transparent))
        }
        Text("$value", Modifier.width(36.dp), textAlign = TextAlign.End, style = LocalExtra.current.monoSmall)
    }
}

/**
 * A TacRef entry's cards: its description ("Tactical reference", unless [withDescription] is false because the page
 * shows it elsewhere), each section folded, and the RWR emitter. [except] is a section the page already shows.
 */
@Composable
fun EncyclopediaCards(e: EncyEntry, withDescription: Boolean = true, except: com.bmscompanion.app.data.EncySection? = null) {
    if (withDescription && e.description.isNotBlank()) {
        com.bmscompanion.app.ui.components.TextCard("Tactical reference", e.description, Hud.Green)
    }
    e.sections.filter { it.lines.isNotEmpty() && it !== except }.forEach { s ->
        CollapsibleCard(s.title ?: "Details", initiallyOpen = false, accent = Hud.Green, preview = s.lines.take(3).joinToString(" · ")) {
            EncySectionLines(s.lines)
        }
    }
    e.rwr?.let { SectionCard("RWR", accent = Hud.Green) { KeyValueRow("Emitter", it, mono = true, valueColor = Hud.Green) } }
}

/** A TacRef section's lines: "Label: value" as a key and value, anything else as a line of text. */
@Composable
private fun EncySectionLines(lines: List<String>) {
    lines.forEach { line ->
        val idx = line.indexOf(':')
        if (idx in 1..40) KeyValueRow(line.substring(0, idx), line.substring(idx + 1).trim().ifBlank { "—" })
        else Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
    }
}

@Composable
fun NoData(text: String = "No data") = EmptyState(text, Modifier.fillMaxWidth().height(120.dp))

@Suppress("unused")
private val unusedSize = Modifier.size(1.dp)
