package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.BoardTable
import com.bmscompanion.app.data.mission.KbCockpit
import com.bmscompanion.app.data.mission.KbOwner
import com.bmscompanion.app.data.mission.KbPrintState
import com.bmscompanion.app.data.mission.KbSlot
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.LoadingBox
import com.bmscompanion.app.ui.components.Masonry
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.Tag
import com.bmscompanion.app.ui.components.isMedium
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.screens.ZoomableBitmap
import com.bmscompanion.app.ui.screens.wdp.KneeboardPages
import com.bmscompanion.app.ui.screens.wdp.plannerProbe
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The cockpit kneeboard as Falcon BMS will show it in 3D (1.4.1): the Kneeboards page's top section, its Dashboard
 * card and the large viewer, all from `GET /api/kbprint/state` ([KbPrintState], judged by [KbCockpit]) and the PC's
 * pictures of each half (`/api/kbprint/thumb`).
 *
 * **The ground truth is the page files** (`7982-7997.dds` in `KoreaObj` of the theater's 3D data, or their
 * `KoreaObj_HiRes` twins, which BMS shows instead): whichever tool wrote a page last — EZBoards, Upd Kneeboard,
 * html_brief, WDP, or nothing since BMS shipped it — is what the cockpit shows, so this shows the files, never a plan.
 * A page written at or after the mission's start ([KbPrintState.since]: the PRINT in EZBoards mode, the Planner's open
 * or Populate in WDP mode) is this mission's; one written before is marked as an earlier mission's. Pages that are
 * BMS's own on both knees (the shipped checklists) fold away.
 *
 * **Cost**: the state is a few header reads on the PC, asked every [POLL_MS] while one of these is on screen and at
 * once when EZBoards runs, html_brief exports, BMS prints or the Planner populates; a half's picture is fetched again
 * only when its file's time changes (the path carries it), and the PC keeps its decoded pictures by path, size and time.
 */
object CockpitKneeboards {
    const val POLL_MS = 4_000L

    var state by mutableStateOf<KbPrintState?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** A first answer came (right or wrong). */
    var loaded by mutableStateOf(false)
        private set

    /** The halves the viewer steps through, as the page that opened it showed them. */
    var order: List<Pair<Int, Char>> = emptyList()

    /** Pages that are BMS's own on both knees are shown (on every device: MissionSync). */
    var showOriginals by com.bmscompanion.app.data.mission.prefState("kb_view_originals") { Repo.getInt("kb_view_originals", 0) == 1 }
        private set

    fun toggleOriginals() {
        showOriginals = !showOriginals
        Repo.putInt("kb_view_originals", if (showOriginals) 1 else 0)
    }

    private val mutex = Mutex()
    private var lastAt = 0L

    /** Asks the PC again, unless it was asked less than a poll ago ([force]: a file is known to have changed). */
    suspend fun refresh(force: Boolean = false) = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && loaded && now - lastAt < POLL_MS - 500) return@withLock
        lastAt = now
        val a = MissionLink.kbState()
        if (a.value != null || a.reached) {
            // a refusal may still carry the state (a theater with no page folder): both are kept
            state = a.value
            error = a.error ?: a.value?.error
        } else error = a.error
        loaded = true
    }

    /** The pages shown, in BMS's order: every page, or without the pages that are BMS's own on both knees. */
    fun shownPages(s: KbPrintState?): List<KbSlot> = KbCockpit.pages(s).filter { showOriginals || !KbCockpit.allOriginal(it) }

    fun open(nav: NavHostController, halves: List<Pair<Int, Char>>, start: Pair<Int, Char>) {
        order = halves
        nav.go("m/kneeboardview?k=" + halves.indexOf(start).coerceAtLeast(0))
    }
}

/**
 * Keeps [CockpitKneeboards] current while the caller is on screen: every [CockpitKneeboards.POLL_MS], and at once when
 * the PC says a writer ran (EZBoards' last run, html_brief's export, a PRINT, a Populate, the mode).
 */
@Composable
fun PollCockpitKneeboards() {
    val info by MissionLink.info.collectAsState()
    val link by MissionLink.state.collectAsState()
    val online = link is LinkState.Online
    val ez = info?.ezBoards
    val key = listOf(ez?.lastRun?.time, ez?.running, info?.kneeboard?.exported, info?.briefing?.modified, info?.mission?.populated?.at, info.wdpMode)
    LaunchedEffect(online, key) {
        if (!online) return@LaunchedEffect
        CockpitKneeboards.refresh(force = true)
        while (true) {
            delay(CockpitKneeboards.POLL_MS)
            CockpitKneeboards.refresh()
        }
    }
}

// ------------------------------------------------------------------------------------------------ words

/** Who made a half, in a word or two. */
internal fun cockpitOwner(owner: String): String = when (owner) {
    KbOwner.BMS -> "BMS original"
    KbOwner.EZBOARDS -> "EZBoards"
    KbOwner.HTMLBRIEF -> "html_brief"
    KbOwner.WDP -> "WDP"
    KbOwner.COMPANION -> "BMS Companion"
    KbOwner.MISSING -> "No page file"
    else -> "Another tool"
}

internal fun cockpitOwnerInk(owner: String): Color = when (owner) {
    KbOwner.EZBOARDS -> Hud.Amber
    KbOwner.COMPANION -> Hud.Cyan
    KbOwner.HTMLBRIEF -> Hud.Green
    KbOwner.WDP -> modeInk(MissionMode.WDP)
    else -> Hud.TextFaint
}

/** What BMS Companion printed on a half ("Noteboard 1", "Route map"), or null. */
internal fun cockpitKindWord(kind: String?): String? = kind?.let { k -> KneeboardPages.of(k)?.short ?: k }

private fun clock(ms: Long): String {
    if (ms <= 0) return ""
    val today = SimpleDateFormat("yyyy MM dd", Locale.US).format(Date(System.currentTimeMillis()))
    val day = SimpleDateFormat("yyyy MM dd", Locale.US).format(Date(ms))
    return SimpleDateFormat(if (day == today) "HH:mm" else "d MMM HH:mm", Locale.US).format(Date(ms))
}

/** "left knee" / "right knee" */
private fun kneeWord(side: Char) = if (side == 'L') "left knee" else "right knee"

/** The line that says which mission the pages are judged by. */
private fun sinceLine(s: KbPrintState): String {
    if (s.since <= 0) return if (s.mode == MissionMode.WDP) "No flight opened in the Planner yet: every page is shown as it is."
    else "No briefing printed yet: every page is shown as it is."
    val what = when (s.sinceFrom) {
        KbPrintState.SINCE_OPENED -> "opened in the Planner"
        KbPrintState.SINCE_POPULATED -> "populated"
        else -> "printed"
    }
    return "This mission" + (s.mission?.let { ": $it," } ?: "") + " $what ${clock(s.since)}."
}

// ------------------------------------------------------------------------------------------------ one half

/** A half page as BMS shows it, at [w] px wide (the PC's picture), kept while a newer one loads. */
@Composable
private fun CockpitHalf(s: KbSlot, side: Char, w: Int, modifier: Modifier = Modifier) {
    val path = KbCockpit.picturePath(s, side, w)
    var img by remember(s.n, side, w) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(s.n, side, w) { mutableStateOf(false) }
    LaunchedEffect(path) {
        val b = MissionLink.fetchBytes(path)?.let { Repo.decodeBitmap(it) }?.asImageBitmap()
        if (b != null) { img = b; failed = false } else if (img == null) failed = true
    }
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)).background(Color.White), contentAlignment = Alignment.Center) {
        val i = img
        when {
            i != null -> Image(i, "Page ${s.n}, ${kneeWord(side)}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            failed -> Text("Cannot be shown", color = Color(0xFF606060), fontSize = 11.sp)
            else -> CircularProgressIndicator(Modifier.size(16.dp), color = Hud.Amber, strokeWidth = 2.dp)
        }
    }
}

/** Under a half: who made it, and what it is when BMS Companion did. */
@Composable
private fun HalfCaption(s: KbSlot, side: Char) {
    val owner = KbCockpit.owner(s, side)
    val kind = if (owner == KbOwner.COMPANION) cockpitKindWord(KbCockpit.kind(s, side)) else null
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            (if (side == 'L') "L · " else "R · ") + cockpitOwner(owner),
            color = cockpitOwnerInk(owner), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (kind != null) Text(kind, color = Hud.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** This mission / earlier mission, for a page that is not BMS's own on both knees. */
@Composable
private fun PageStatus(state: KbPrintState?, s: KbSlot) {
    when {
        KbCockpit.allOriginal(s) -> Tag("BMS original", Hud.TextFaint)
        KbCockpit.current(state, s) -> Tag("this mission", Hud.Green)
        KbCockpit.earlier(state, s) -> Tag("earlier mission", Hud.Amber)
        else -> {}
    }
}

/** One page: its number, when it was written, whose mission, and its two halves side by side as on the two knees. */
@Composable
private fun CockpitPageCard(state: KbPrintState?, s: KbSlot, w: Int, modifier: Modifier, onOpen: (Char) -> Unit) {
    val earlier = KbCockpit.earlier(state, s) && !KbCockpit.allOriginal(s)
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Hud.Surface2)
            .border(1.dp, if (KbCockpit.current(state, s) && !KbCockpit.allOriginal(s)) Hud.Green.copy(alpha = 0.45f) else Hud.Outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PAGE ${s.n}", style = LocalExtra.current.overline, color = Hud.Text)
            Spacer(Modifier.width(6.dp))
            Text(clock(KbCockpit.shown(s).modified), color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
            PageStatus(state, s)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (side in listOf('L', 'R')) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { onOpen(side) }.plannerProbe("Kneeboards/View/${s.n}$side")) {
                    Box {
                        CockpitHalf(s, side, w, Modifier.fillMaxWidth())
                        // an earlier mission's page is still what the cockpit shows: greyed, not hidden
                        if (earlier) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.28f)))
                    }
                    HalfCaption(s, side)
                }
            }
        }
    }
}


// ------------------------------------------------------------------------------------------------ the section

/**
 * The Kneeboards page's top section: every kneeboard of the mission **to look at**, apart from the making of them
 * below. Three views — **In the cockpit** (the page files, what BMS shows in 3D; the default), **HTML Briefing**
 * (html_brief's exported document pages, [htmlBrief]) and **EZBoards tables** (what EZBoards' xbrief read from the
 * briefing, [tables], shown only when there are any) — and a fold remembered per device (`open_kb_view`).
 */
@Composable
fun KneeboardViewSection(nav: NavHostController, boardTables: List<BoardTable>, htmlBrief: @Composable () -> Unit) {
    PollCockpitKneeboards()
    val tables = boardTables.filter { it.rows.isNotEmpty() }
    // a table with a title and no rows is xbrief's note under them
    val tablesNote = boardTables.firstOrNull { it.rows.isEmpty() && it.title.isNotBlank() }?.title
    val info by MissionLink.info.collectAsState()
    val s = CockpitKneeboards.state
    var open by com.bmscompanion.app.data.mission.rememberPrefState("open_kb_view") { Repo.getInt("open_kb_view", 1) == 1 }
    val views = listOfNotNull("cockpit", "html", if (tables.isNotEmpty()) "tables" else null)
    var view by com.bmscompanion.app.data.mission.rememberPrefState("kb_view_tab") { Repo.getString("kb_view_tab") ?: "cockpit" }
    if (view !in views) view = "cockpit"
    val current = KbCockpit.pages(s).count { KbCockpit.current(s, it) && !KbCockpit.allOriginal(it) }
    Surface(
        Modifier.fillMaxWidth(), color = Hud.Surface, shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Hud.Outline.copy(alpha = 0.6f)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open; Repo.putInt("open_kb_view", if (open) 1 else 0) }
                    .padding(horizontal = 16.dp, vertical = 14.dp).plannerProbe("Kneeboards/ViewFold"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(width = 3.dp, height = 14.dp).background(Hud.Green, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Kneeboards for this mission", style = MaterialTheme.typography.titleSmall)
                    if (!open) Text(
                        if (s == null) "What BMS shows in the cockpit, and the HTML Briefing pages."
                        else if (current > 0) "$current ${if (current == 1) "page" else "pages"} made for this mission, as BMS shows them in the cockpit."
                        else "Nothing made for this mission yet: what BMS shows in the cockpit now.",
                        style = MaterialTheme.typography.bodySmall, color = Hud.TextDim, maxLines = 2,
                    )
                }
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Hud.TextDim)
            }
            if (open) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp)) {
                ViewChips(views, view) { view = it; Repo.putString("kb_view_tab", it) }
                Spacer(Modifier.height(10.dp))
                when (view) {
                    "html" -> htmlBrief()
                    "tables" -> {
                        Text(
                            "What EZBoards' xbrief reads from the printed briefing and your cartridge: the content of its pages, as tables.",
                            style = MaterialTheme.typography.bodySmall, color = Hud.TextDim, modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Masonry(minColumn = 480.dp, maxColumns = 2) { tables.forEach { BoardTableCard(it) } }
                        tablesNote?.let { Text(it, fontSize = 11.sp, color = Hud.TextFaint, modifier = Modifier.padding(top = 6.dp, start = 4.dp)) }
                    }
                    else -> CockpitPages(nav, info.wdpMode)
                }
            }
        }
    }
}

@Composable
private fun ViewChips(views: List<String>, view: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (v in views) {
            val on = v == view
            Text(
                when (v) { "html" -> "HTML Briefing"; "tables" -> "EZBoards tables"; else -> "In the cockpit" },
                Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Hud.Green.copy(alpha = 0.18f) else Hud.Surface2)
                    .border(1.dp, if (on) Hud.Green.copy(alpha = 0.7f) else Hud.Outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .clickable { onPick(v) }.padding(horizontal = 14.dp, vertical = 7.dp).plannerProbe("Kneeboards/ViewTab/$v"),
                color = if (on) Hud.Text else Hud.TextDim, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/** No link, or the PC could not read the pages: the sentence, or null when there are pages to show. */
@Composable
private fun cockpitProblem(): String? {
    val link by MissionLink.state.collectAsState()
    val s = CockpitKneeboards.state
    return when {
        link !is LinkState.Online -> "Connect this device to BMS Companion on the BMS PC to see the cockpit kneeboard."
        !CockpitKneeboards.loaded -> null
        s == null || s.pages.isEmpty() -> CockpitKneeboards.error ?: "The PC did not say where this theater's kneeboard pages are."
        KbCockpit.pages(s).isEmpty() -> s.error ?: "${s.theater ?: "This theater"} has no cockpit kneeboard pages."
        else -> null
    }
}

/** **In the cockpit**: the theater's pages in BMS's order, two halves each, BMS's own pages folded away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CockpitPages(nav: NavHostController, wdp: Boolean) {
    val s = CockpitKneeboards.state
    cockpitProblem()?.let { Text(it, color = Hud.TextDim, fontSize = 13.sp); return }
    if (s == null) { Box(Modifier.fillMaxWidth().height(120.dp)) { LoadingBox() }; return }
    val all = KbCockpit.pages(s)
    val shown = CockpitKneeboards.shownPages(s)
    val originals = all.filter { KbCockpit.allOriginal(it) }
    Text(
        "What Falcon BMS shows on the kneeboard in the cockpit" + (s.theater?.let { " (${it.trim()})" } ?: "") +
            ", read from its page files: whichever tool wrote a page last is what you will see. " + sinceLine(s),
        style = MaterialTheme.typography.bodySmall, color = Hud.TextDim,
    )
    if (s.bmsIn3d) Text(
        "You are in the cockpit: BMS read these pages as you entered it, so a page written now shows the next time you enter.",
        style = MaterialTheme.typography.bodySmall, color = Hud.Amber, modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(10.dp))
    val halves = shown.flatMap { listOf(it.n to 'L', it.n to 'R') }
    if (shown.isEmpty()) {
        Text(
            "Every page holds Falcon BMS's own page as it ships: nothing has been made for the cockpit yet. " +
                if (wdp) "In WDP mode the Planner's Upd Kneeboard makes them." else "EZBoards makes them at PRINT, and Upd Kneeboard adds pages of the app's own.",
            color = Hud.TextDim, fontSize = 13.sp,
        )
    } else BoxWithConstraints(Modifier.fillMaxWidth()) {
        // a page card is two halves side by side: on a phone one page a row, wider screens as many as fit
        val cardMin = if (maxWidth < 600.dp) maxWidth else 300.dp
        val cols = (maxWidth / cardMin).toInt().coerceIn(1, 4)
        val gap = 10.dp
        val cardW = (maxWidth - gap * (cols - 1)) / cols
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            shown.forEach { p ->
                CockpitPageCard(s, p, 360, Modifier.width(cardW)) { side -> CockpitKneeboards.open(nav, halves, p.n to side) }
            }
        }
    }
    if (originals.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { CockpitKneeboards.toggleOriginals() }
                .padding(vertical = 8.dp, horizontal = 4.dp).plannerProbe("Kneeboards/Originals"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (CockpitKneeboards.showOriginals) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Hud.TextDim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                (if (CockpitKneeboards.showOriginals) "Hide " else "Show ") + pagesWord(originals.map { it.n }) +
                    ": Falcon BMS's own " + (if (originals.size == 1) "page" else "pages") + " as shipped, on both knees",
                color = Hud.TextDim, fontSize = 12.sp,
            )
        }
    }
    Text(
        "Tap a page to read it large. The pages here follow the files by themselves: EZBoards' run, Upd Kneeboard, an HTML Briefing export.",
        style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint, modifier = Modifier.padding(top = 4.dp),
    )
}

/** "page 4" / "pages 4-16" / "pages 2, 5 and 7-9" */
private fun pagesWord(ns: List<Int>): String {
    val runs = ArrayList<IntRange>()
    ns.sorted().forEach { n -> if (runs.isNotEmpty() && runs.last().last == n - 1) runs[runs.size - 1] = runs.last().first..n else runs += n..n }
    val parts = runs.map { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
    val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    return (if (ns.size == 1) "page " else "pages ") + list
}

// ------------------------------------------------------------------------------------------------ the Dashboard card

/**
 * The Dashboard's **Kneeboards** card: the cockpit kneeboard of this mission, one page at a time with a page switcher
 * (the pages made for something, BMS's own pages left out unless that is all there is), a tap opening the viewer.
 * The page picked is remembered per device (`dash_kb_page`).
 */
@Composable
fun CockpitKneeboardCard(nav: NavHostController, onOpenTab: (MissionTab) -> Unit) {
    PollCockpitKneeboards()
    val s = CockpitKneeboards.state
    SectionCard("Kneeboards", accent = Hud.Green, trailing = { SmallLink("Kneeboards ›") { onOpenTab(MissionTab.BOARDS) } }) {
        cockpitProblem()?.let { Text(it, color = Hud.TextDim, fontSize = 13.sp); return@SectionCard }
        if (s == null) { Box(Modifier.fillMaxWidth().height(100.dp)) { LoadingBox() }; return@SectionCard }
        val all = KbCockpit.pages(s)
        val made = all.filter { !KbCockpit.allOriginal(it) }
        val pages = made.ifEmpty { all }
        var pick by remember { mutableIntStateOf(Repo.getInt("dash_kb_page", 0)) }
        // the page asked for, else this mission's first, else the first made for anything
        val page = pages.firstOrNull { it.n == pick } ?: pages.firstOrNull { KbCockpit.current(s, it) } ?: pages.first()
        if (made.isEmpty()) Text("Only Falcon BMS's own pages are in the cockpit: nothing has been made for this mission yet.", color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in pages) {
                    val on = p.n == page.n
                    val ink = when { KbCockpit.current(s, p) -> Hud.Green; KbCockpit.earlier(s, p) -> Hud.Amber; else -> Hud.TextDim }
                    Text(
                        "${p.n}",
                        Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) ink.copy(alpha = 0.22f) else Hud.Surface2)
                            .border(1.dp, if (on) ink else Hud.Outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable { pick = p.n; Repo.putInt("dash_kb_page", p.n) }
                            .padding(horizontal = 11.dp, vertical = 6.dp).plannerProbe("Dash/Kneeboards/${p.n}"),
                        color = if (on) Hud.Text else ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            PageStatus(s, page)
        }
        Spacer(Modifier.height(8.dp))
        val halves = pages.flatMap { listOf(it.n to 'L', it.n to 'R') }
        // (a wide card would make the page taller than a screen: two halves of at most 240 dp each)
        Row(Modifier.widthIn(max = 488.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (side in listOf('L', 'R')) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { CockpitKneeboards.open(nav, halves, page.n to side) }) {
                    CockpitHalf(page, side, 512, Modifier.fillMaxWidth())
                    HalfCaption(page, side)
                }
            }
        }
        Text(
            "Page ${page.n}" + clock(KbCockpit.shown(page).modified).takeIf { it.isNotEmpty() }?.let { ", written $it" }.orEmpty() + ". " + sinceLine(s),
            color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ------------------------------------------------------------------------------------------------ the viewer

/**
 * One half large (`m/kneeboardview?k=<index>`): zoom and pan ([ZoomableBitmap], a double tap, the wheel on the PC),
 * a swipe while fitted, ← → and Page Up/Down step through the halves in cockpit order (page 1 left, page 1 right,
 * page 2 …, as [CockpitKneeboards.order] has them), Escape closes. The 1024-pixel picture is the file's half at full
 * width. It follows the file while open.
 */
@Composable
fun CockpitKneeboardViewer(nav: NavHostController, start: Int) {
    PollCockpitKneeboards()
    val s = CockpitKneeboards.state
    // (opened by its address alone: the pages the section shows)
    val order = CockpitKneeboards.order.ifEmpty { KbCockpit.halves(s, skipOriginal = !CockpitKneeboards.showOriginals).ifEmpty { KbCockpit.halves(s) } }
    var i by remember { mutableIntStateOf(start) }
    val close = { nav.popBackStack(); Unit }
    if (order.isEmpty()) {
        Column(Modifier.fillMaxSize().background(Hud.Bg)) {
            BmsTopBar("Cockpit kneeboard", onBack = close)
            Box(Modifier.fillMaxSize().padding(24.dp)) { cockpitProblem()?.let { Text(it, color = Hud.TextDim) } ?: LoadingBox() }
        }
        return
    }
    val idx = i.coerceIn(0, order.size - 1)
    val (n, side) = order[idx]
    val slot = s?.pages?.firstOrNull { it.n == n }
    val step = { d: Int -> i = (idx + d + order.size) % order.size }
    val focus = remember { FocusRequester() }
    LaunchedEffect(idx) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize().background(Hud.Bg).focusRequester(focus).focusable().onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.DirectionLeft, Key.PageUp -> { step(-1); true }
                Key.DirectionRight, Key.PageDown, Key.Spacebar -> { step(1); true }
                Key.Escape -> { close(); true }
                else -> false
            }
        },
    ) {
        val sub = listOfNotNull(
            slot?.let { sl ->
                val o = KbCockpit.owner(sl, side)
                cockpitOwner(o) + (if (o == KbOwner.COMPANION) cockpitKindWord(KbCockpit.kind(sl, side))?.let { ": $it" }.orEmpty() else "")
            },
            slot?.let { clock(KbCockpit.shown(it).modified).takeIf { c -> c.isNotEmpty() }?.let { c -> "written $c" } },
            slot?.let { when { KbCockpit.allOriginal(it) -> null; KbCockpit.current(s, it) -> "this mission"; KbCockpit.earlier(s, it) -> "an earlier mission"; else -> null } },
        ).joinToString(" · ")
        BmsTopBar("Page $n, ${kneeWord(side)}", sub.ifEmpty { null }, onBack = close) {
            IconButton(onClick = close) { Icon(Icons.Default.Close, "Close", tint = Hud.Text) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (slot == null) LoadingBox()
            else {
                val path = KbCockpit.picturePath(slot, side, 1024)
                var bmp by remember(n, side) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(path) { MissionLink.fetchBytes(path, timeoutMs = 30_000)?.let { Repo.decodeBitmap(it) }?.let { bmp = it } }
                val b = bmp
                if (b == null) LoadingBox() else ZoomableBitmap(b, Modifier.fillMaxSize(), onSwipe = { step(it) })
            }
        }
        // the halves in cockpit order: 1L 1R 2L …, this mission's in green, an earlier mission's in amber
        Row(
            Modifier.fillMaxWidth().background(Hud.Surface).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StepKey(Icons.Default.ChevronLeft, "Previous half") { step(-1) }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                order.forEachIndexed { k, (pn, ps) ->
                    val p = s?.pages?.firstOrNull { it.n == pn }
                    val ink = when { p == null -> Hud.TextDim; KbCockpit.allOriginal(p) -> Hud.TextFaint; KbCockpit.current(s, p) -> Hud.Green; KbCockpit.earlier(s, p) -> Hud.Amber; else -> Hud.TextDim }
                    val on = k == idx
                    Text(
                        "$pn$ps",
                        Modifier.clip(RoundedCornerShape(9.dp)).background(if (on) ink.copy(alpha = 0.25f) else Hud.Surface2)
                            .border(1.dp, if (on) ink else Color.Transparent, RoundedCornerShape(9.dp))
                            .clickable { i = k }.padding(horizontal = 10.dp, vertical = 8.dp),
                        color = if (on) Hud.Text else ink, fontSize = 13.sp,
                    )
                }
            }
            if (isMedium()) Text("${idx + 1} / ${order.size}", fontSize = 12.sp, color = Hud.TextDim)
            StepKey(Icons.Default.ChevronRight, "Next half") { step(1) }
        }
    }
}

@Composable
private fun StepKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Hud.Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Hud.Amber) }
}
