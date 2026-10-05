package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampFile
import com.bmscompanion.app.data.mission.CampFiles
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.CampTheater
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.components.SearchField
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **Open mission…**: Falcon BMS's campaign, TE and training saves on the PC, one group per theater, newest first
 * (R3-PLAN A6-A8, R3-UI §3.3, R3-CAM §4.4). It replaces WDP's File > Open, and works the same on the PC, a phone and a
 * browser: the PC lists the files of every theater in `theater.lst` (`/api/campaign/files`) and reads the one picked;
 * the file itself never leaves the PC.
 *
 * - **Groups**: the theater Falcon BMS is set to first and open, the others by their newest file, closed, each with its
 *   count, its newest date and its folder in small type. A search opens every group that has a match.
 * - **Order** within a group: newest first by the later of created and modified (a TE a friend just copied in comes
 *   out on top; a bulk copy's created times are ignored by the PC), or Modified, Created or Name. Each row names the
 *   date it shows ("saved 25 Sep 22:42", "copied in 11 Sep 11:44").
 * - **A row** holds the name, its kind (Campaign, TE, Training), the date, the campaign clock and name, the flights and
 *   packages, the flight with a player slot, the size, and a badge when it holds the flight the printed briefing is for.
 * - **BMS's own missions** are folded away until *Show BMS's own missions* is on: the missions BMS ships
 *   (`TE_BMS_*`, `TR_BMS_*`), which open and save like the pilot's own, as in WDP, and the campaign starts and
 *   templates, which hold no flights and are the only files that cannot be opened (greyed, the reason on tap).
 * - **Recent**: the last five files opened on this device.
 *
 * Picking a file opens the flight picker ([FlightPickerWindow]) on it. The list may scroll (A21). Without the link to
 * the PC it says so and nothing else: the files are on that PC.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ColumnScope.CampaignBrowserWindow(onClose: () -> Unit) {
    val link by MissionLink.state.collectAsState()
    val linked = link is LinkState.Online
    val b = CampaignBrowser
    val scope = rememberCoroutineScope()
    LaunchedEffect(linked, b.showStock) { if (linked) b.load() }

    if (!linked) {
        NotLinkedNote(link, "OpenMission")
        return
    }

    // ONE slim row: the search, the order, BMS's own missions, Refresh, Browse… and the guide (the hints are the
    // controls' tooltips, not lines of their own). Narrower than that, the search takes the row and the rest scroll
    // sideways on the one under it. The list below takes all the rest of the window.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val oneRow = maxWidth >= 820.dp
        val search = @Composable { m: Modifier ->
            PlannerSearch(b.query, { b.query = it }, "Find a save: name, campaign, callsign, theater", "OpenMission/Search", m)
        }
        val tools = @Composable {
            PlannerSegments(
                CampSort.entries.map { it.label }, CampSort.entries.map { "OpenMission/Sort/${it.name}" }, b.sort.ordinal,
            ) { b.sort = CampSort.entries[it] }
            PlannerChip("Show BMS's own missions", on = b.showStock, probe = "OpenMission/ShowStock", tick = true) { b.showStock = !b.showStock }
            PlannerIconButton(Icons.Default.Refresh, "Refresh", "OpenMission/Refresh", enabled = !b.loading) { scope.launch { b.load() } }
            // WDP's own "Open Campaign or TE" window, on the BMS PC
            PlannerButton("Browse…", probe = "OpenMission/Browse", enabled = !b.loading, small = true) { scope.launch { b.browse() } }
            PlannerIconButton(Icons.Default.MenuBook, "Guide", "OpenMission/Guide") { PlannerGuide.open("open") }
        }
        if (oneRow) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                search(Modifier.weight(1f))
                tools()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                search(Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { tools() }
            }
        }
    }

    val files = b.files
    when {
        b.error != null -> Text(b.error!!, color = Hud.Red, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.plannerProbe("OpenMission/Error"))
        files == null -> Text("Reading the campaign folders on the PC…", color = Hud.TextDim, fontSize = 13.sp)
        files.error != null -> Text(files.error, color = Hud.Red, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.plannerProbe("OpenMission/Error"))
    }
    if (files == null || files.error != null) return

    val list = rememberLazyListState()
    // a new search, switch or order is a different list: it starts at its top. (A LazyColumn keeps its place by the
    // first item's key, so without this a search that had scrolled to one theater stayed there after the next.)
    LaunchedEffect(b.query, b.showStock, b.sort) { runCatching { list.scrollToItem(0) } }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().alpha(if (b.loading) 0.6f else 1f), state = list) {
        recentItems(files)
        val q = b.query.trim()
        var shown = 0
        for (t in files.theaters) {
            val g = BrowserGroup(t, q, b.showStock, b.sort)
            if (q.isNotEmpty() && g.matches == 0) continue
            shown++
            theaterItems(g, files)
        }
        if (shown == 0) item("none") {
            Text(
                if (q.isNotEmpty()) "No save matches “$q”." + (if (!b.showStock) " BMS's own missions are not searched until Show BMS's own missions is on." else "")
                else "Falcon BMS's theater list names no theaters.",
                color = Hud.TextDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
    // one muted line under the list (the × in the title bar closes the window)
    Text(
        files.briefing?.let { k -> "The printed briefing is for ${k.callsign}" + (k.packageId?.let { ", package $it" } ?: "") + (if (k.printed > 0) ", printed ${fileWhen(k.printed)}" else "") + "." }
            ?: "No briefing is printed: pick your flight in the save.",
        color = Hud.TextFaint, fontSize = fingerSp(11f), maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------- state

/** How the Open mission list orders the files of a theater. */
enum class CampSort(val label: String, val hint: String?) {
    NEWEST("Newest", null),
    MODIFIED("Modified", null),
    CREATED("Created", "Created: when the file arrived on this PC. A TE copied in from a friend shows the day it was copied, not the day it was made."),
    NAME("Name", null),
}

/**
 * The Open mission list, kept for as long as the app runs: opening the window again shows the last listing at once
 * while the PC reads the folders again, and the search, the switches and which groups are open stay as they were.
 */
object CampaignBrowser {
    /** The last listing the PC gave (with the starts and templates when [showStock] is on). */
    var files: CampFiles? by mutableStateOf(null)
    /** Whether [files] was asked for with the starts and templates (`all=1`). */
    var filesAll: Boolean = false
        private set
    var loading: Boolean by mutableStateOf(false)
    /** The PC's sentence when it refused, or why it could not be reached; null when the listing came back. */
    var error: String? by mutableStateOf(null)
    var query: String by mutableStateOf("")
    var showStock: Boolean by mutableStateOf(false)
    var sort: CampSort by mutableStateOf(CampSort.NEWEST)
    /** Theater groups the pilot opened or closed, by name; a group not in it is open when it is BMS's current one. */
    val open = mutableStateMapOf<String, Boolean>()
    /** The start whose reason is on show ("<theater>|<file>"), or null. */
    var why: String? by mutableStateOf(null)
    /** How many listings have come back, for the checks. */
    var loads: Int by mutableIntStateOf(0)
    private var asked = 0

    /** Reads the listing from the PC; a later call wins over an earlier one still under way. */
    suspend fun load() {
        val all = showStock
        val n = ++asked
        loading = true
        val a = runCatching { MissionLink.campaignFiles(all) }.getOrElse { com.bmscompanion.app.data.mission.PcAnswer.unreachable(it.message ?: "error") }
        if (n != asked) return
        loading = false
        val v = a.value
        if (v != null && (a.error == null || v.theaters.isNotEmpty())) {
            files = v
            filesAll = all
            error = null
        } else {
            error = a.error ?: "The PC's answer could not be read."
        }
        loads++
    }

    /** Whether theater [t]'s group is open. */
    fun isOpen(t: CampTheater, searching: Boolean): Boolean = searching || (open[t.name] ?: t.current)

    /**
     * **Browse…**: WDP's own "Open Campaign or TE" window (`OpenMission`) on the BMS PC — in the folder of the last save
     * opened (the campaign folder of the theater BMS is on, the first time), `all|*.cam;*.tac;*.trn` then each kind,
     * the last save's kind chosen — and the save picked opened as a row of the list is. The PC opens a save from a
     * theater's own campaign folder (where BMS keeps its companion files); one picked anywhere else is named, and not
     * opened. Answers what happened, for the checks.
     */
    suspend fun browse(): String? {
        if (files == null) load()
        val list = files ?: return error
        val last = PlannerRecent.items.firstOrNull()
        val lastTheater = last?.let { r -> list.theaters.firstOrNull { it.name.equals(r.theater, true) } }
        // a theater's folder is named from the BMS folder ("Data\Campaign"), as the PC lists it
        fun inBms(t: CampTheater?) = t?.folder?.takeIf { it.isNotBlank() }?.let { "@bms\\" + it.trim('\\', '/') }
        val start = inBms(lastTheater) ?: inBms(list.theaters.firstOrNull { it.current }) ?: "@campaign"
        val ext = last?.file?.substringAfterLast('.', "")?.lowercase()
        val f = com.bmscompanion.app.data.PcFiles.open(
            "Open Campaign or TE", start,
            com.bmscompanion.app.data.FileFilter.parse("all|*.cam;*.tac;*.trn|(*.cam)|*.cam|(*.tac)|*.tac|(*.trn)|*.trn"),
            last?.file, filterIndex = when (ext) { "cam" -> 1; "tac" -> 2; "trn" -> 3; else -> 0 },
        ) ?: return null
        val base = runCatching { MissionLink.filesStat("@bms").value?.takeIf { it.dir }?.path }.getOrNull()?.trimEnd('\\', '/')
        val here = f.folder.trimEnd('\\', '/')
        val matches = list.theaters.filter { th -> base != null && th.folder.isNotBlank() && (base + "\\" + th.folder.trim('\\', '/')).equals(here, ignoreCase = true) }
        val t = matches.firstOrNull { th -> th.files.any { it.name.equals(f.name, ignoreCase = true) } } ?: matches.firstOrNull { it.current } ?: matches.firstOrNull()
        if (t == null) {
            val why = "${f.name} is in ${f.folder}, which is not a theater's campaign folder. BMS Companion opens a save from " +
                "the campaign folder of its theater, where Falcon BMS keeps the files that go with it: copy it there, or pick it in the list."
            com.bmscompanion.app.ui.screens.wdp.WdpDialogs.message("Open Campaign or TE", why)
            return why
        }
        val kind = when (f.extension) { "cam" -> CampKind.CAMPAIGN; "trn" -> CampKind.TRAINING; else -> CampKind.TE }
        val file = t.files.firstOrNull { it.name.equals(f.name, ignoreCase = true) } ?: CampFile(name = f.name, kind = kind)
        openFile(t, file)
        return "${t.name}|${file.name}"
    }

    /** Opens [file] of theater [t]: the flight picker takes over the window. A start is refused (its reason shows). */
    fun openFile(t: CampTheater, file: CampFile) {
        if (file.start) { why = t.name + "|" + file.name; return }
        PlannerRecent.add(t.name, file.name)
        FlightPicker.show(CampRef(t.name, file.name), file)
    }
}

/**
 * The last five saves opened on this device, newest first, in the device's own settings (`planner_recent`: one
 * "<theater>\t<file>" line each). Only names, never a path.
 */
object PlannerRecent {
    const val KEY = "planner_recent"
    const val MOST = 5

    var items: List<CampRef> by mutableStateOf(read())
        private set

    private fun read(): List<CampRef> = runCatching {
        Repo.getString(KEY).orEmpty().split('\n').mapNotNull { l ->
            val t = l.substringBefore('\t', "").trim()
            val f = l.substringAfter('\t', "").trim()
            if (t.isEmpty() || f.isEmpty()) null else CampRef(t, f)
        }.take(MOST)
    }.getOrDefault(emptyList())

    fun add(theater: String, file: String) {
        val r = CampRef(theater, file)
        items = (listOf(r) + items.filterNot { it.theater.equals(theater, true) && it.file.equals(file, true) }).take(MOST)
        runCatching { Repo.putString(KEY, items.joinToString("\n") { "${it.theater}\t${it.file}" }) }
    }

    /** Forgets them all (the checks start from none). */
    fun clear() {
        items = emptyList()
        runCatching { Repo.putString(KEY, null) }
    }
}

/** One theater's files as the list shows them: the pilot's own saves, BMS's missions and the starts, in [sort] order. */
private class BrowserGroup(val t: CampTheater, private val q: String, showStock: Boolean, sort: CampSort) {
    private fun hit(f: CampFile) = q.isEmpty() || listOfNotNull(f.name, f.title, f.player, t.name).any { it.contains(q, ignoreCase = true) }
    private val sorted = t.files.sortedWith(sort.comparator())
    val own = sorted.filter { !it.stock && !it.start && hit(it) }
    val missions = if (showStock) sorted.filter { it.stock && !it.start && hit(it) } else emptyList()
    val starts = if (showStock) sorted.filter { it.start && hit(it) } else emptyList()
    /** BMS's own files the switch hides here */
    val hidden = if (showStock) 0 else t.files.count { (it.stock || it.start) && hit(it) }
    val matches = own.size + missions.size + starts.size
    val newest = t.files.filter { !it.start }.maxOfOrNull { it.sortTime }
}

private fun CampSort.comparator(): Comparator<CampFile> = when (this) {
    CampSort.NEWEST -> compareByDescending<CampFile> { it.sortTime }.thenByDescending { it.modified }.thenBy { it.name.lowercase() }
    CampSort.MODIFIED -> compareByDescending<CampFile> { it.modified }.thenBy { it.name.lowercase() }
    CampSort.CREATED -> compareByDescending<CampFile> { it.created }.thenBy { it.name.lowercase() }
    CampSort.NAME -> compareBy<CampFile> { it.name.lowercase() }
}

// ---------------------------------------------------------------- the list

private fun LazyListScope.recentItems(files: CampFiles) {
    val recent = PlannerRecent.items
    if (recent.isEmpty() || CampaignBrowser.query.isNotBlank()) return
    // the last five, as one line of chips across the top of the list (five rows of their own pushed the list down)
    item("recent") {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("RECENT", color = Hud.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            for (r in recent) {
                val t = files.theaters.firstOrNull { it.name.equals(r.theater, true) }
                val f = t?.files?.firstOrNull { it.name.equals(r.file, true) }
                val can = t != null && f != null && !f.start
                val shape = RoundedCornerShape(6.dp)
                Text(
                    r.file + (f?.let { "  " + fileWhen(it.modified) } ?: ""),
                    color = if (can) Hud.Text else Hud.TextFaint, fontSize = fingerSp(12f), maxLines = 1,
                    modifier = Modifier.height(toolHeight).clip(shape).background(Hud.Surface2)
                        .clickable(enabled = can) { if (t != null && f != null) CampaignBrowser.openFile(t, f) }
                        .padding(horizontal = 10.dp).wrapContentHeight(Alignment.CenterVertically)
                        .plannerProbe("OpenMission/Recent/${r.file}")
                        .wdpTip(
                            "planner/OpenMission/Recent/${r.theater}/${r.file}",
                            if (can) "${r.file} (${r.theater}): a save you opened on this device lately. Press to open it again and pick your flight."
                            else "${r.file} (${r.theater}): a save you opened on this device lately; it is not in the list now" +
                                (if (!CampaignBrowser.showStock) " (or it is folded under Show BMS's own missions)" else "") + ", so it cannot be opened from here.",
                            touch = true,
                        ),
                )
            }
        }
    }
}

private fun LazyListScope.theaterItems(g: BrowserGroup, files: CampFiles) {
    val t = g.t
    val b = CampaignBrowser
    val searching = b.query.isNotBlank()
    val open = b.isOpen(t, searching)
    item("theater/${t.name}") { TheaterHeader(g, open, files) }
    if (!open) return
    t.error?.let { e -> item("theater/${t.name}/error") { Text(e, color = Hud.Red, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 22.dp, bottom = 4.dp)) } }
    for (f in g.own) item("file/${t.name}/${f.name}") { FileRow(t, f) }
    if (g.own.isEmpty() && t.error == null && (!searching || g.missions.isEmpty() && g.starts.isEmpty())) item("theater/${t.name}/empty") {
        Text(
            if (searching) "None of your own saves here matches." else "No saves of your own here yet.",
            color = Hud.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(start = 22.dp, top = 2.dp, bottom = 4.dp),
        )
    }
    if (g.hidden > 0) item("theater/${t.name}/hidden") {
        Text(
            "+ ${g.hidden} of BMS's own missions and campaign starts: Show BMS's own missions",
            color = Hud.TextFaint, fontSize = 12.sp,
            modifier = Modifier.padding(start = 22.dp, top = 2.dp, bottom = 4.dp).clip(RoundedCornerShape(4.dp))
                .clickable { b.showStock = true }.plannerProbe("OpenMission/Hidden/${t.name}")
                .wdpTip("planner/OpenMission/Hidden/${t.name}", "Lists the TEs, trainings and campaign starts that come with Falcon BMS too (Show BMS's own missions).", touch = true)
                .then(if (WdpTouch.device) Modifier.padding(vertical = 8.dp) else Modifier),
        )
    }
    if (g.missions.isNotEmpty()) {
        val te = g.missions.count { it.kind == CampKind.TE }
        val tr = g.missions.count { it.kind == CampKind.TRAINING }
        val other = g.missions.size - te - tr
        val parts = listOfNotNull("TE $te".takeIf { te > 0 }, "Training $tr".takeIf { tr > 0 }, "other $other".takeIf { other > 0 })
        item("theater/${t.name}/missions") {
            SubLabel("BMS missions (${parts.joinToString(", ")})")
        }
        for (f in g.missions) item("file/${t.name}/${f.name}") { FileRow(t, f) }
    }
    if (g.starts.isNotEmpty()) {
        item("theater/${t.name}/starts") { SubLabel("Campaign starts and templates (${g.starts.size}): no flights, never opened") }
        for (f in g.starts) item("file/${t.name}/${f.name}") { FileRow(t, f) }
    }
}

@Composable
private fun TheaterHeader(g: BrowserGroup, open: Boolean, files: CampFiles) {
    val t = g.t
    // one line: the theater, a mark when BMS is set to it, and what is in it in small type (cut short on a phone)
    Row(
        Modifier.fillMaxWidth().fingerHeight(40.dp).background(Hud.Surface2)
            .clickable { CampaignBrowser.open[t.name] = !open }
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .plannerProbe("OpenMission/Theater/${t.name}")
            .wdpTip(
                "planner/OpenMission/Theater/${t.name}",
                "${t.name}: the saves in its campaign folder on the PC (${t.folder})" + (if (t.current) ", the theater Falcon BMS is set to" else "") +
                    ". Press to show or hide them.",
                touch = true,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (open) "▾" else "▸", color = Hud.TextDim, fontSize = 13.sp, modifier = Modifier.width(16.dp))
        Text(t.name, color = Hud.Text, fontSize = fingerSp(13f), fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (t.current) {
            Spacer(Modifier.width(8.dp))
            PlannerTag("BMS is set to this", Hud.Green)
        }
        Spacer(Modifier.width(10.dp))
        val own = t.files.count { !it.stock && !it.start }
        Text(
            listOfNotNull(
                "$own of yours", (t.files.size - own).takeIf { it > 0 }?.let { "$it from BMS" }, g.newest?.let { "newest " + dayOnly(it) }, t.folder,
            ).joinToString(" · "),
            color = Hud.TextFaint, fontSize = fingerSp(11f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FileRow(t: CampTheater, f: CampFile) {
    val b = CampaignBrowser
    val key = t.name + "|" + f.name
    val showWhy = f.start && b.why == key
    Column(
        Modifier.fillMaxWidth().fingerHeight(48.dp)
            .drawBehind { drawLine(Hud.Outline.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1f) }
            .clickable {
                if (f.start) b.why = if (showWhy) null else key else b.openFile(t, f)
            }
            .padding(start = 22.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
            .alpha(if (f.start) 0.5f else 1f)
            .plannerProbe("OpenMission/File/${t.name}/${f.name}")
            .wdpTip(
                "planner/OpenMission/File/${t.name}/${f.name}",
                if (f.start) "A campaign start or template: it holds no flights, so the Planner never opens it. Press to see why."
                else "Opens ${f.name} and lists its packages and flights, to pick your flight and seat." +
                    (if (f.briefed) " It holds the flight BMS printed its briefing for." else ""),
                touch = true,
            ),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        // the name and its tags; on a phone the tags go under a long name rather than cut it
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(f.name, color = Hud.Text, fontSize = fingerSp(13f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterVertically))
            PlannerTag(kindWord(f.kind), Hud.Cyan, modifier = Modifier.align(Alignment.CenterVertically))
            if (f.briefed) PlannerTag("Matches the printed briefing", Hud.Green, modifier = Modifier.align(Alignment.CenterVertically))
        }
        // one line, so the most useful first: when, your flight, the campaign clock, its name, its size
        val details = listOfNotNull(
            whenWords(f, b.sort),
            f.player?.let { "Your flight: $it" },
            f.clock?.let { campClock(it) },
            f.title?.trim()?.takeIf { it.isNotEmpty() },
            if (f.flights != null) countWord(f.packages ?: 0, "package") + " · " + countWord(f.flights, "flight") else null,
            sizeText(f.size),
        )
        Text(details.joinToString(" · "), color = Hud.TextDim, fontSize = fingerSp(11.5f), lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        f.error?.let { Text(it, color = Hud.Red, fontSize = 12.sp, lineHeight = 15.sp) }
        if (showWhy) {
            Text(
                f.stockWhy ?: "${f.name.substringBeforeLast('.')} is a campaign start: the Planner does not open it. Start the campaign in Falcon BMS, SAVE, then open your save.",
                color = Hud.Amber, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.plannerProbe("OpenMission/Why"),
            )
        }
    }
}

// ---------------------------------------------------------------- shared with the flight picker

/**
 * What a window that reads the PC's campaign files shows without the link: the sentence, the link's state, and the
 * Guide's page on planning from a phone or a browser.
 */
@Composable
internal fun NotLinkedNote(link: LinkState, probe: String) {
    Text(
        "Connect this device to BMS Companion on your PC to open Falcon BMS's campaign files. They live on that PC.",
        color = Hud.Text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.plannerProbe("$probe/NotLinked"),
    )
    val why = when (link) {
        is LinkState.Connecting -> "Connecting to the PC…"
        is LinkState.Offline -> "Lost the link to ${link.host}: what you are planning is kept and comes back with it."
        else -> "This device is not linked to a PC yet: Setup links it."
    }
    Text(why, color = Hud.TextDim, fontSize = 12.sp, lineHeight = 15.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlannerButton("Guide", probe = "$probe/Guide") { PlannerGuide.open("remote") }
        Spacer(Modifier.weight(1f))
        PlannerButton("Close", probe = "$probe/Cancel") { PlannerWindows.close() }
    }
}

@Composable
internal fun PlannerButton(
    text: String,
    probe: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    /** the tool row's height ([toolHeight]) rather than a full button's */
    small: Boolean = false,
    /** no finger height: a button in a title bar, which is a finger's height itself */
    slim: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.then(if (slim) Modifier else if (small) Modifier.heightIn(min = toolHeight) else Modifier.fingerHeight())
            .clip(RoundedCornerShape(6.dp))
            .background(if (primary && enabled) Hud.Amber else Hud.Surface3)
            .plannerPress(enabled) { onClick() }
            .padding(horizontal = if (small) 10.dp else 12.dp, vertical = if (small || slim) 4.dp else 7.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .plannerProbe(probe),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (primary && enabled) Color(0xFF14171B) else Hud.Text, fontSize = fingerSp(if (small) 12f else 13f),
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        )
    }
}

@Composable
internal fun PlannerChip(text: String, on: Boolean, probe: String, tick: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = toolHeight).clip(RoundedCornerShape(6.dp))
            .background(if (on) Hud.Amber.copy(alpha = 0.16f) else Hud.Surface)
            .border(1.dp, if (on) Hud.Amber.copy(alpha = 0.5f) else Hud.Outline, RoundedCornerShape(6.dp))
            .plannerPress(enabled) { onClick() }
            .padding(horizontal = if (WdpTouch.device) 11.dp else 9.dp, vertical = 3.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .plannerProbe(probe),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            (if (tick) (if (on) "☑ " else "☐ ") else "") + text,
            color = if (on) Hud.Amber else Hud.TextDim, fontSize = fingerSp(12f), maxLines = 1,
        )
    }
}

@Composable
internal fun PlannerTag(text: String, color: Color, size: TextUnit = 10.5.sp, modifier: Modifier = Modifier) {
    Text(
        text, color = color, fontSize = size, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = Hud.TextFaint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
}

@Composable
private fun SubLabel(text: String) {
    Text(text, color = Hud.TextDim, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 22.dp, top = 6.dp, bottom = 2.dp))
}

/** "1 flight", "12 flights" */
internal fun countWord(n: Int, word: String): String = "$n $word" + if (n == 1) "" else "s"

internal fun kindWord(kind: String): String = when (kind) {
    CampKind.CAMPAIGN -> "Campaign"
    CampKind.TE -> "TE"
    CampKind.TRAINING -> "Training"
    else -> kind.ifEmpty { "?" }
}

/** Which date a row shows under [sort], and what it means. */
private fun whenWords(f: CampFile, sort: CampSort): String? = when (sort) {
    CampSort.MODIFIED -> f.modified.takeIf { it > 0 }?.let { "saved " + fileWhen(it) }
    CampSort.CREATED -> f.created.takeIf { it > 0 }?.let { "created " + fileWhen(it) }
    else -> when {
        // the created time won: the file was copied in after it was last written
        f.created > f.modified + 60_000 && f.sortTime == f.created -> "copied in " + fileWhen(f.created)
        f.modified > 0 -> "saved " + fileWhen(f.modified)
        else -> null
    }
}

private fun day(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

/** "today 22:42", "yesterday 22:42", "25 Sep 22:42", or "25 Sep 2025" for another year, in the device's own time. */
internal fun fileWhen(ms: Long): String {
    if (ms <= 0) return ""
    val now = Date().time
    val d = day(ms)
    val hm = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
    return when {
        d == day(now) -> "today $hm"
        d == day(now - 86_400_000L) -> "yesterday $hm"
        d.take(4) == day(now).take(4) -> SimpleDateFormat("d MMM", Locale.US).format(Date(ms)) + " " + hm
        else -> SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(ms))
    }
}

/** "25 Sep", or "25 Sep 2025" for another year. */
private fun dayOnly(ms: Long): String =
    if (day(ms).take(4) == day(Date().time).take(4)) SimpleDateFormat("d MMM", Locale.US).format(Date(ms))
    else SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(ms))

/** A campaign time (ms from day 1 00:00) as BMS shows it: "Day 1 04:16". */
internal fun campClock(ms: Long): String {
    if (ms < 0) return ""
    val day = ms / 86_400_000L + 1
    val rest = ms % 86_400_000L
    val h = rest / 3_600_000L
    val m = rest / 60_000L % 60
    return "Day $day " + h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')
}

/** A campaign time of day, "04:21:00" (the day left out: a flight's own times are all on one day). */
internal fun campTime(ms: Long): String {
    if (ms <= 0) return ""
    val rest = ms % 86_400_000L
    return listOf(rest / 3_600_000L, rest / 60_000L % 60, rest / 1000L % 60).joinToString(":") { it.toString().padStart(2, '0') }
}

private fun sizeText(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
    else -> {
        val tenths = (bytes * 10 + 512 * 1024) / (1024 * 1024)
        "${tenths / 10}.${tenths % 10} MB"
    }
}
