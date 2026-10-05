package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.ui.theme.Hud

/**
 * The Planner guide (R3-PLAN A19): how a new pilot gets from Falcon BMS to the cockpit with the Planner, as a paged
 * window over the Planner. Its text is [PlannerGuideText]; this object is how the rest of the Planner opens it.
 *
 * - The toolbar's **Guide** opens it at the page on screen: `PlannerGuide.open(WdpSession.page)`.
 * - The not-linked note opens it at the phone-and-browser page: `PlannerGuide.open("remote")`.
 * - "WDP by Falcas" opens the credit: `PlannerGuide.open("credit")`; the Print window's Help the kneeboard step:
 *   `PlannerGuide.open("print")`; the file browser's help the Open mission step: `PlannerGuide.open("open")`.
 * - **By itself the first time** the Planner is opened on a device: [PlannerGuideFirstOpen] in the Planner's pane,
 *   which opens it at the beginning once and remembers that in the device's settings ([SEEN_KEY]).
 *
 * The guide needs nothing from the PC: it is static text, so it can be read while the device is not linked. Its "go
 * there" buttons open the Open mission… and Upd Kneeboard windows (which say for themselves when they need the
 * link), press Populate from Planner, or turn the Planner to a page and close the guide.
 */
object PlannerGuide {
    /** The device's setting that says the guide has opened by itself once (1) or not yet (absent or 0). */
    const val SEEN_KEY = "planner_guide_seen"

    /** The guide's pages, by id, in order. A page of the Planner is its card's id in lower case ("toss", "dtc"). */
    val ids: List<String> get() = PlannerGuideText.sections.map { it.id }

    /**
     * The guide's page on screen, by id, while the guide is open, and null when it is not. For the checks, which read
     * it between frames: a plain field set after each composition, not state.
     */
    var shownId: String? = null
        internal set

    /** True while a narrow window shows the contents in place of the text (a wide one shows both). For the checks. */
    var shownContents: Boolean = false
        internal set

    /**
     * The guide's page for [arg]: a page's own id ("step7"), one of its other names ("print", "open", "remote",
     * "credit", "missing"…), or a Planner page's name as [WdpPage] spells it ("TOSS", "COORDINATION"). Case, spaces
     * and punctuation do not matter. Anything else, and null, is the beginning.
     */
    fun indexOf(arg: String?): Int {
        val n = arg?.let(::norm)?.takeIf { it.isNotEmpty() } ?: return 0
        val all = PlannerGuideText.sections
        all.indexOfFirst { norm(it.id) == n }.takeIf { it >= 0 }?.let { return it }
        all.indexOfFirst { s -> s.aliases.any { norm(it) == n } }.takeIf { it >= 0 }?.let { return it }
        return 0
    }

    /** Opens the guide at [section] (see [indexOf]), in place of any other Planner window. */
    fun open(section: String? = null) = PlannerWindows.show(PlannerWindow.GUIDE, section)

    /** Whether the guide has already opened by itself on this device. Unreadable settings count as seen. */
    val seen: Boolean get() = runCatching { Repo.getInt(SEEN_KEY, 0) != 0 }.getOrDefault(true)

    /**
     * Opens the guide at its beginning if it has never opened by itself on this device, and remembers that it has.
     * Does nothing while another Planner window is up (the guide waits for the next time). True when it opened.
     */
    fun firstOpen(): Boolean {
        if (seen || PlannerWindows.isOpen) return false
        runCatching { Repo.putInt(SEEN_KEY, 1) }
        open("start")
        return true
    }

    /** For the checks: forget ([seen] false) or set the first-open flag. */
    fun setSeen(value: Boolean) {
        runCatching { Repo.putInt(SEEN_KEY, if (value) 1 else 0) }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}

/**
 * Put once in the Planner's pane: the first time the Planner is shown on a device, the guide opens by itself at its
 * beginning ([PlannerGuide.firstOpen]).
 */
@Composable
fun PlannerGuideFirstOpen() {
    LaunchedEffect(Unit) { PlannerGuide.firstOpen() }
}

/**
 * The Planner guide: how to plan a flight here, step by step.
 *
 * [section] is the section to open at, the page on screen when the guide was asked for ("toss", "dtc", "print"…), or
 * null for the beginning ([PlannerGuide.indexOf]).
 *
 * A wide window shows the contents beside the text; a phone shows the text, with **Contents** at the foot to swap it
 * for the list. **Previous** and **Next** turn the pages. The text of each page scrolls (R3-PLAN A21: the guide is
 * one of the app's own lists, not one of WDP's fitted pages).
 */
@Composable
fun ColumnScope.PlannerGuideWindow(section: String?, onClose: () -> Unit) {
    val all = PlannerGuideText.sections
    var at by remember(section) { mutableIntStateOf(PlannerGuide.indexOf(section)) }
    var contents by remember(section) { mutableStateOf(false) }
    // read here, in this scope, so that a turn of the page recomposes it and the SideEffect runs again (reads inside a
    // SideEffect are not watched, and the pages themselves are drawn in the composition of BoxWithConstraints)
    val shownNow = all[at].id
    val contentsNow = contents
    SideEffect {
        PlannerGuide.shownId = shownNow
        PlannerGuide.shownContents = contentsNow
    }
    DisposableEffect(Unit) {
        onDispose {
            PlannerGuide.shownId = null
            PlannerGuide.shownContents = false
        }
    }
    val go: (GuideGo) -> Unit = { to ->
        when (to) {
            GuideGo.OpenMission -> PlannerWindows.show(PlannerWindow.OPEN_MISSION)
            GuideGo.Populate -> { onClose(); PlannerShell.populate() }
            GuideGo.Print -> PlannerWindows.show(PlannerWindow.PRINT)
            is GuideGo.Page -> {
                WdpSession.page = to.page
                onClose()
            }
            is GuideGo.Section -> {
                at = PlannerGuide.indexOf(to.id)
                contents = false
            }
        }
    }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val wide = maxWidth >= 560.dp
        val contentsWidth = if (maxWidth >= 800.dp) 232.dp else 190.dp
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    wide -> Row(Modifier.fillMaxSize()) {
                        GuideContents(all, at, Modifier.width(contentsWidth).fillMaxHeight()) { at = it }
                        Box(Modifier.padding(horizontal = 12.dp).width(1.dp).fillMaxHeight().background(Hud.Outline))
                        GuideSectionText(all[at], Modifier.weight(1f).fillMaxHeight(), go)
                    }
                    contents -> GuideContents(all, at, Modifier.fillMaxSize()) {
                        at = it
                        contents = false
                    }
                    else -> GuideSectionText(all[at], Modifier.fillMaxSize(), go)
                }
            }
            Spacer(Modifier.height(6.dp))
            // the foot: previous, where we are, next
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FootButton(if (wide) "‹ Previous" else "‹", "Guide/Prev", enabled = at > 0) {
                    at -= 1
                    contents = false
                }
                if (!wide) FootButton(if (contents) "Text" else "Contents", "Guide/Contents") { contents = !contents }
                Text(
                    "${at + 1} of ${all.size}", color = Hud.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                    maxLines = 1, modifier = Modifier.weight(1f),
                )
                val next = all.getOrNull(at + 1)
                FootButton(
                    if (wide && next != null) "Next: ${next.short}  ›" else "Next  ›", "Guide/Next",
                    enabled = next != null, primary = true, maxWidth = if (wide) 340.dp else 160.dp,
                ) {
                    at += 1
                    contents = false
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- pieces

/** The contents: the guide's pages under their groups, the one on screen marked; a tap opens a page. */
@Composable
private fun GuideContents(all: List<GuideSection>, at: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    // the list's rows: a group's name (String) before its first page, then each page (its index)
    val rows: List<Any> = remember(all) {
        val out = ArrayList<Any>()
        var group: String? = null
        all.forEachIndexed { k, s ->
            if (s.group != group) {
                group = s.group
                out += s.group
            }
            out += k
        }
        out
    }
    val row = rows.indexOf(at).coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (row - 3).coerceAtLeast(0))
    // keep the page on screen in view as Previous and Next turn the pages
    LaunchedEffect(row) {
        if (state.layoutInfo.visibleItemsInfo.none { it.index == row }) state.scrollToItem((row - 3).coerceAtLeast(0))
    }
    LazyColumn(modifier.plannerProbe("Guide/ContentsList"), state = state) {
        items(rows.size) { r ->
            when (val v = rows[r]) {
                is String -> Text(
                    v.uppercase(), color = Hud.TextFaint, fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp, top = if (r == 0) 2.dp else 12.dp, bottom = 4.dp),
                )
                else -> {
                    val k = v as Int
                    val s = all[k]
                    val here = k == at
                    Text(
                        s.short, color = if (here) Hud.Text else Hud.TextDim, fontSize = 13.5.sp, lineHeight = 17.sp,
                        fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().heightIn(min = if (WdpTouch.device) 40.dp else 30.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (here) Hud.Surface3 else Color.Transparent)
                            .clickable { onPick(k) }.plannerProbe("Guide/Toc/${s.id}")
                            .wrapContentHeight(Alignment.CenterVertically)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** One page of the guide: its group, its title, its blocks, scrolled on their own. */
@Composable
private fun GuideSectionText(s: GuideSection, modifier: Modifier, go: (GuideGo) -> Unit) {
    key(s.id) {
        val scroll = rememberScrollState()
        Box(modifier.plannerProbe("Guide/Body")) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.group.uppercase(), color = Hud.TextFaint, fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    s.title, color = Hud.Text, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.plannerProbe("Guide/Title"),
                )
                // "go there" buttons that follow one another share a line
                var k = 0
                val blocks = s.blocks
                while (k < blocks.size) {
                    val b = blocks[k]
                    if (b is GuideBlock.Go) {
                        val run = ArrayList<GuideBlock.Go>()
                        while (k < blocks.size && blocks[k] is GuideBlock.Go) run += blocks[k++] as GuideBlock.Go
                        GoButtons(run, go)
                    } else {
                        GuideBlockView(b, go)
                        k++
                    }
                }
                Spacer(Modifier.height(6.dp))
                // the end of the page, for the checks: reachable when this is inside the box above
                Box(Modifier.fillMaxWidth().height(1.dp).plannerProbe("Guide/End/${s.id}"))
            }
            // more below: a fade at the foot says so
            if (scroll.value < scroll.maxValue) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Hud.Surface))),
                )
            }
        }
    }
}

@Composable
private fun GuideBlockView(b: GuideBlock, go: (GuideGo) -> Unit) {
    when (b) {
        is GuideBlock.Para -> GuideText(b.text)
        is GuideBlock.Sub -> Text(
            b.text, color = Hud.Cyan, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp),
        )
        is GuideBlock.Steps -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            b.items.forEachIndexed { k, item -> GuideItemRow("${k + 1}.", item) }
        }
        is GuideBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            b.items.forEach { item -> GuideItemRow("•", item) }
        }
        is GuideBlock.Why -> WhyText(b.text)
        is GuideBlock.Note -> Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Hud.Amber.copy(alpha = 0.10f))
                .border(1.dp, Hud.Amber.copy(alpha = 0.45f), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        ) { GuideText(b.text) }
        is GuideBlock.Table -> GuideTable(b, go)
        is GuideBlock.Go -> GoButtons(listOf(b), go)
    }
}

/** A step or a bullet: its mark, its text, the points under it and why it matters. */
@Composable
private fun GuideItemRow(mark: String, item: GuideItem) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            mark, color = Hud.Cyan, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(if (mark.length > 1) 26.dp else 16.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            GuideText(item.text)
            for (s in item.sub) {
                Row(Modifier.fillMaxWidth()) {
                    Text("–", color = Hud.TextDim, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.width(14.dp))
                    GuideText(s, Modifier.weight(1f), size = 13.5.sp)
                }
            }
            item.why?.let { WhyText(it) }
        }
    }
}

/** Why a step matters, set in under it with a rule down its side. */
@Composable
private fun WhyText(text: String) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(Hud.Cyan.copy(alpha = 0.45f)))
        Spacer(Modifier.width(9.dp))
        GuideText("**Why:** $text", Modifier.weight(1f), color = Hud.TextDim, size = 13.sp)
    }
}

/**
 * A two-column table: side by side where there is room, the first cell above the second on a phone. A row that
 * [GuideRow.link]s opens that page of the guide from its first cell.
 */
@Composable
private fun GuideTable(t: GuideBlock.Table, go: (GuideGo) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = maxWidth >= 470.dp
        val shape = RoundedCornerShape(6.dp)
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, Hud.Outline, shape)) {
            if (side) {
                Row(Modifier.fillMaxWidth().background(Hud.Surface2).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(t.head.getOrElse(0) { "" }, color = Hud.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.34f))
                    Spacer(Modifier.width(10.dp))
                    Text(t.head.getOrElse(1) { "" }, color = Hud.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.66f))
                }
            }
            t.rows.forEachIndexed { k, r ->
                if (side || k > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Outline.copy(alpha = 0.6f)))
                if (side) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
                        TableTerm(r, Modifier.weight(0.34f), go)
                        Spacer(Modifier.width(10.dp))
                        GuideText(r.text, Modifier.weight(0.66f), size = 13.5.sp)
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        TableTerm(r, Modifier, go)
                        GuideText(r.text, size = 13.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableTerm(r: GuideRow, modifier: Modifier, go: (GuideGo) -> Unit) {
    val link = r.link
    if (link == null) {
        Text(r.term, color = Hud.Text, fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
    } else {
        Box(modifier) {
            Text(
                r.term, color = Hud.Cyan, fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { go(GuideGo.Section(link)) }.plannerProbe("Guide/Link/${r.term}"),
            )
        }
    }
}

/** The "go there" buttons: each opens the window or the page it names. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoButtons(run: List<GuideBlock.Go>, go: (GuideGo) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in run) {
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier.heightIn(min = if (WdpTouch.device) 42.dp else 32.dp).clip(shape).background(Hud.Cyan.copy(alpha = 0.10f))
                    .border(1.dp, Hud.Cyan.copy(alpha = 0.6f), shape).plannerPress { go(b.to) }
                    .plannerProbe("Guide/Go/${b.to.key}").padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text("${b.label}  ›", color = Hud.Cyan, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun FootButton(
    label: String,
    probe: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    maxWidth: androidx.compose.ui.unit.Dp = 200.dp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.widthIn(max = maxWidth).height(if (WdpTouch.device) 42.dp else 32.dp).alpha(if (enabled) 1f else 0.35f).clip(shape)
            .background(if (primary) Hud.Cyan.copy(alpha = 0.16f) else Hud.Surface3)
            .then(if (primary) Modifier.border(1.dp, Hud.Cyan.copy(alpha = 0.5f), shape) else Modifier)
            .then(if (enabled) Modifier.plannerPress { onClick() } else Modifier)
            .plannerProbe(probe).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (primary) Hud.Cyan else Hud.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A paragraph with the guide's markup: `**bold**` and backquoted `code`. */
@Composable
private fun GuideText(text: String, modifier: Modifier = Modifier, color: Color = Hud.Text, size: TextUnit = 14.sp) {
    Text(rich(text), modifier, color = color, fontSize = size, lineHeight = (size.value * 1.43f).sp)
}

/** The guide's markup as styled text. Built on each composition (it is short), so a change of skin re-colours it. */
private fun rich(text: String): AnnotatedString = buildAnnotatedString {
    var k = 0
    while (k < text.length) {
        val bold = text.indexOf("**", k)
        val code = text.indexOf('`', k)
        val next = listOf(bold, code).filter { it >= 0 }.minOrNull() ?: text.length
        if (next > k) append(text.substring(k, next))
        if (next >= text.length) break
        if (next == bold) {
            val end = text.indexOf("**", bold + 2)
            if (end < 0) { append(text.substring(bold)); break }
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Hud.Text)) { append(text.substring(bold + 2, end)) }
            k = end + 2
        } else {
            val end = text.indexOf('`', code + 1)
            if (end < 0) { append(text.substring(code)); break }
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.92.em, background = Hud.Surface3)) {
                append(text.substring(code + 1, end))
            }
            k = end + 1
        }
    }
}
