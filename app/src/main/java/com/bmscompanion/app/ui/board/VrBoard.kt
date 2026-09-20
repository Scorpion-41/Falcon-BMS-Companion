package com.bmscompanion.app.ui.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.ChartRef
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Threat
import com.bmscompanion.app.data.mission.BoardConfig
import com.bmscompanion.app.data.mission.BoardSlot
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.Kneeboard
import com.bmscompanion.app.ui.components.AssetImage
import com.bmscompanion.app.ui.screens.chartPage
import com.bmscompanion.app.ui.screens.mission.LiveMap
import com.bmscompanion.app.ui.screens.mission.MapLayers
import com.bmscompanion.app.ui.screens.mission.MapSel
import com.bmscompanion.app.ui.screens.mission.MissionEnv
import com.bmscompanion.app.ui.screens.mission.airbases
import com.bmscompanion.app.ui.screens.mission.matchAirport
import com.bmscompanion.app.ui.screens.mission.flightLevel
import com.bmscompanion.app.ui.screens.mission.rangeNm
import com.bmscompanion.app.ui.screens.mission.bra
import com.bmscompanion.app.ui.screens.mission.bullseye
import com.bmscompanion.app.ui.screens.mission.ownship
import com.bmscompanion.app.ui.screens.mission.rememberMapData
import com.bmscompanion.app.ui.screens.mission.rememberMissionEnv
import com.bmscompanion.app.ui.components.rememberMapState
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlinx.coroutines.delay

/**
 * A VR board: one OpenKneeboard tab, one kind of page, nothing to press.
 *
 * In a headset there is no pointer — OpenKneeboard supports graphics tablets and nothing else — so a board built like
 * a web page is a board a pilot can look at and not use. These pages are built the other way round: each one is a
 * printed sheet, the pilot's own next/previous page binding turns them, and everything that would need a finger is
 * decided beforehand on the PC (Mission -> VR boards) rather than in the cockpit.
 *
 * Each board answers on its own address, `/kneeboard/<n>`, so one OpenKneeboard tab is one board. What board 3 shows
 * is [BoardSlot] number 3 in the configuration the PC serves; change it there and the board follows on its next
 * refresh, mid-flight if need be.
 */
enum class BoardKind(
    val id: String,
    val label: String,
    val hint: String,
    /**
     * How many pages this kind of board is given in the VR program.
     *
     * Fixed, and larger than the content usually needs, because the number cannot be changed afterwards: a tab's
     * pages are set once and its next-page binding turns those. Ask for one page while a chart set is still loading
     * and the binding has nowhere to go for the rest of the flight. Pages past the end of the content say so.
     */
    val pages: Int,
    val configurable: Boolean = false,
) {
    MAP("map", "Live map", "The mission map: route, threat rings, traffic. Follows the jet.", 1, configurable = true),
    BRIEFING("briefing", "Briefing", "The whole briefing, packed onto a sheet or two.", 2),
    FLIGHT("flight", "Flight plan", "Steerpoints with time, heading, distance and altitude.", 2),
    COMMS("comms", "Comms ladder", "Agencies, callsigns and preset channels.", 2),
    SUPPORT("support", "Tankers & AWACS", "Who is on station, with TACAN and frequencies.", 1),
    THREATS("threats", "Mission threats", "Every threat the briefing names, packed onto a sheet.", 3),
    HARM("harm", "HARM / ALIC codes", "The ALIC table, for typing into the HARM.", 1),
    RUNWAYS("runways", "Airfield plates", "Ground and parking charts for the field you take off from.", 12),
    PLATES("plates", "Instrument charts", "Approach, departure and arrival plates for that field.", 40),
    PICTURE("picture", "Hostile picture", "The AWACS picture: what is out there, nearest first.", 1),

    /**
     * The kneeboard html_brief exported on the BMS PC, page for page. Twelve pages: its own layout is six, and a
     * pilot who has put their own PDFs in its folder gets those too.
     */
    EXPORTED("exported", "HTML Briefing kneeboards", "The pages BMS's HTML Briefing tool exported, as it made them.", 12),

    ;

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: MAP
    }
}

/**
 * The zoom steps a map board walks through, widest first.
 *
 * A map is one sheet, so the pages OpenKneeboard turns are put to the only use a map has for them: the pilot's
 * next-page binding zooms in, previous-page zooms out. The numbers are the map's own scale — 1 is the whole
 * theater, 16 is close enough to read a taxiway.
 */
val MAP_ZOOMS = listOf(1f, 2f, 4f, 8f, 16f)

/** What each step is called under the map, so a turn says what it did. */
val MAP_ZOOM_LABELS = listOf("Theater", "Wide", "Route", "Close", "Very close")

/**
 * The margin a page keeps from the tablet's own frame.
 *
 * A sheet that begins at the bezel reads like a page that has been cut short, and through a lens the first line is
 * the hardest one to find. Every page of print keeps the same margin at the head, the foot and the sides; a picture
 * — a plate, an exported kneeboard page — keeps a thinner one, because there what matters is how big it is drawn.
 */
private val SHEET_SIDE = 14.dp
private val SHEET_HEAD = 26.dp
private val SHEET_FOOT = 22.dp
private val PLATE_MARGIN = 8.dp

/** One printed page of a board. */
class BoardPage(val title: String, val content: @Composable () -> Unit)

/**
 * The board at `/kneeboard/<n>`.
 *
 * The configuration is re-read every few seconds, so changing a board on the PC reaches the headset without anyone
 * taking it off; the pages themselves are rebuilt from the mission whenever the briefing changes, which is what makes
 * "the charts for where I am taking off from" mean this mission's field rather than the one it meant last night.
 */
@Composable
fun VrBoardScreen(nav: NavHostController, slot: Int) {
    // Nothing polls the PC unless a screen asks it to, and a board is a screen like any other: without this the
    // mission never arrives and every page says "press PRINT" over a briefing that was printed half an hour ago.
    DisposableEffect(Unit) {
        MissionLink.acquire()
        onDispose { MissionLink.release() }
    }
    val env = rememberMissionEnv(nav)
    val config by produceState<BoardConfig?>(null) {
        while (true) {
            MissionLink.boards()?.let { value = it }
            delay(5_000)
        }
    }
    val cfg = config
    val board = cfg?.slots?.firstOrNull { it.n == slot }
    LaunchedEffect(cfg?.night, cfg?.print) {
        cfg ?: return@LaunchedEffect
        Kneeboard.useNight(cfg.night)
        Kneeboard.chooseTextSize(cfg.print)
    }

    var page by remember(slot) { mutableIntStateOf(0) }

    // What the tab is given, and what a turn of it means.
    //
    // The count is the number of sheets this board actually has — publish more and the binding walks into pages
    // that are not there ("9 / 5"), publish fewer and the rest are unreachable. It is republished whenever the
    // count changes, which is how a briefing that grows a section, or a board whose kind is changed on the PC,
    // keeps a binding that works. Nothing is published before the PC has said what this board is.
    //
    // The map is the exception: it is one sheet, so its pages are **zoom steps** instead. Next page zooms in,
    // previous page zooms out — the only two controls that matter on a map, and the pilot already has them bound.
    val shape = Kneeboard.shapes[Kneeboard.shape]
    val kind = board?.let { BoardKind.of(it.kind) }
    val zoomSteps = if (kind == BoardKind.MAP) MAP_ZOOMS.size else 0

    // What this board is called, so a stack of tabs in OpenKneeboard reads "Kneeboard 2 (Live map)" rather than
    // five lines of "BMS Companion". The name is set again when the board's kind is changed on the PC.
    LaunchedEffect(slot, kind) {
        val what = kind?.label
        Kneeboard.setTitle?.invoke(if (what == null) "BMS KB $slot" else "BMS KB $slot ($what)")
    }

    // Rebuild pages on the PC bumps the revision; keying on it throws away what was built and builds it again.
    // The map is built for the step it is on, so the sheets are made after the page is known.
    val pages = key(cfg?.rev ?: 0) {
      when {
        cfg == null -> listOf(BoardPage("") { BoardNotice("Looking for the PC…", "This board reads what to show from BMS Companion on the BMS PC.") })
        board == null -> listOf(BoardPage("") { BoardNotice("Board $slot is not set up", "Open Mission -> VR boards on the PC and give board $slot something to show.") })
        else -> boardPages(BoardKind.of(board.kind), board, env, if (zoomSteps > 0) page.coerceIn(0, zoomSteps - 1) else -1)
      }
    }
    val want = when {
        kind == null -> null
        zoomSteps > 0 -> zoomSteps
        else -> pages.size.coerceAtLeast(1)
    }
    LaunchedEffect(want, shape) {
        // Publishing can fail on something passing — OpenKneeboard still starting, a tab still loading — and a board
        // that gave up then has no pages for the rest of the flight, with nothing in a headset to press to retry.
        if (want == null) return@LaunchedEffect
        repeat(5) { attempt ->
            Kneeboard.publishPages?.invoke(slot, want, shape.w, shape.h)
            delay(if (attempt == 0) 2000L else 5000L)
            if (Kneeboard.lastError?.invoke().isNullOrEmpty()) return@LaunchedEffect
        }
    }
    val index = if (zoomSteps > 0) 0 else page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

    // What the last turn did, said once and then gone: the board is read at a glance, and a label that stays is a
    // label that is in the way. The map says the zoom it moved to, everything else says the sheet it turned to.
    val turnedTo = if (zoomSteps > 0) {
        "Zoom · " + MAP_ZOOM_LABELS.getOrNull(page.coerceIn(0, zoomSteps - 1)).orEmpty()
    } else {
        pages.getOrNull(index)?.title?.takeIf { it.isNotBlank() } ?: "Page ${index + 1}"
    }
    var bubble by remember(slot) { mutableStateOf<String?>(null) }

    // The turn, and the bubble that answers it, both come out of this one loop.
    //
    // A bubble is raised by a press and by nothing else: it was hung on the page title before, and a board whose
    // title arrived a moment after it opened — the mission landing, the sheets being measured — put up a bubble
    // nobody had asked for and then had no second title to take it down again. It is also taken down here rather
    // than by a waiting coroutine, so it cannot outlive the thing that put it up.
    val title = rememberUpdatedState(turnedTo)
    LaunchedEffect(slot, want) {
        var showing = 0
        var announce = false
        while (true) {
            delay(150)
            val turned = Kneeboard.takePage?.invoke() ?: -1
            // a ring: past the last sheet is the first one again, which is what a pilot expects of a kneeboard
            if (turned >= 0 && want != null && want > 0) {
                page = ((turned % want) + want) % want
                announce = true
            } else if (announce) {
                // a tick later, so the title is the one the turn arrived at rather than the one it left
                announce = false
                bubble = title.value
                showing = 15
            } else if (showing > 0 && --showing == 0) {
                bubble = null
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        val sheet = pages.getOrNull(index)
        // the tab has more pages than this board is using: say so rather than showing the last one again
        if (sheet != null) sheet.content() else BoardNotice("Nothing on this page", "Turn back for what this board has.")
        PageBubble(bubble)

        // Which sheet of how many, because nothing else on the board says so and the binding that turns them is the
        // pilot's own — but it is a corner of the page, not a strip taken off the board for it. On the map it is the
        // zoom step, for the same reason.
        val trouble = Kneeboard.lastError?.invoke().orEmpty()
        val counter = when {
            zoomSteps > 0 -> "${page.coerceIn(0, zoomSteps - 1) + 1} / $zoomSteps"
            pages.size > 1 -> "${index + 1} / ${pages.size}"
            else -> ""
        }
        if (counter.isNotEmpty()) Text(
            counter,
            Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp),
            color = Hud.Text.copy(alpha = 0.45f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        // the one thing worth a line of the board: the reason the pilot's page binding is doing nothing
        if (trouble.isNotEmpty()) Text(
            trouble,
            Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 6.dp, end = 60.dp),
            color = Hud.Red,
            fontSize = 11.sp,
            maxLines = 2,
        )
    }
}

/** What this kind of board shows, as pages. */
@Composable
private fun boardPages(kind: BoardKind, slot: BoardSlot, env: MissionEnv, step: Int = -1): List<BoardPage> = when (kind) {
    BoardKind.MAP -> listOf(BoardPage("Map") { BoardMap(env, slot, step) })
    BoardKind.BRIEFING -> briefingPages()
    BoardKind.FLIGHT -> flightPages()
    BoardKind.COMMS -> commsPages()
    BoardKind.SUPPORT -> supportPages(env)
    BoardKind.THREATS -> threatPages()
    BoardKind.HARM -> harmPages()
    BoardKind.RUNWAYS -> chartPages(env, instrument = false)
    BoardKind.PLATES -> chartPages(env, instrument = true)
    BoardKind.PICTURE -> picturePages(env)
    BoardKind.EXPORTED -> exportedPages()
}


// ---------------------------------------------------------------- the pages

/** The map, drawn with the layers and the zoom the PC page chose, and no controls at all. */
@Composable
private fun BoardMap(env: MissionEnv, slot: BoardSlot, step: Int = -1) {
    val state = rememberMapState()
    val d = rememberMapData(env)
    // the options are applied to the shared map settings, because that is what the map draws from
    LaunchedEffect(slot.options) {
        slot.options["style"]?.let { com.bmscompanion.app.ui.components.MapLook.chooseStyle(it) }
        slot.options["towns"]?.toIntOrNull()?.let { com.bmscompanion.app.ui.components.MapLook.showPlaces(it) }
        slot.options["borders"]?.let { com.bmscompanion.app.ui.components.MapLook.showBorders(it == "1") }
        fun layer(key: String, set: (Boolean) -> Unit) = slot.options[key]?.let { set(it == "1") }
        layer("route") { MapLayers.route = it }
        layer("threats") { MapLayers.threats = it }
        layer("traffic") { MapLayers.traffic = it }
        layer("hostiles") { MapLayers.hostiles = it }
        layer("labels") { MapLayers.labels = it }
        layer("fields") { MapLayers.fields = it }
        MapLayers.follow = slot.options["follow"] != "0"
        MapLayers.save()
    }
    // The zoom the board opens at — and, once the pilot turns a page, the step they have zoomed to. There is
    // nothing in a headset to pinch with, so the page binding is the zoom control.
    val chosen = slot.options["zoom"]?.toFloatOrNull()
    val zoom = if (step >= 0) MAP_ZOOMS.getOrNull(step) ?: chosen else chosen
    LaunchedEffect(zoom, d.ownPos, d.route.size) {
        val z = zoom ?: return@LaunchedEffect
        val t = d.ownPos ?: d.route.firstOrNull()?.let { it.x!! to it.y!! } ?: return@LaunchedEffect
        state.flyTo(t.first, t.second, z)
    }
    LiveMap(env, d, state, null as MapSel?, {}, Modifier.fillMaxSize(), flightStrip = false, bare = true)
}

/** One thing that goes on a sheet: a section, a row of a table, a threat — named for the page it lands on. */
private class BoardBlock(val name: String? = null, val content: @Composable ColumnScopeLike.() -> Unit)

/**
 * The sheets a list of blocks makes — measured, not guessed.
 *
 * A board has no scrollbar and nothing in the cockpit to drag one with, so a sheet shows what fits on it and the
 * rest goes on the next one. Guessing how much that was — characters to a line, lines to a page — was wrong in both
 * directions at once: sections were held back from a page that had room for them, and the page still ended in an
 * inch of empty board. Every block is measured at the width of the sheet instead, and the break falls where the
 * sheet actually runs out. The measurement is what decides how many pages there are, which is also the number
 * published to OpenKneeboard, so the pilot's binding turns exactly the sheets that exist.
 *
 * [header] is printed again at the top of every sheet — the header row of a table, which is no use on page one only.
 * [maxSheets], where it is set, is the number of sheets the board may run to before the print is set smaller to make
 * it fit: a briefing is read at a glance and a page turn, not five.
 */
@Composable
private fun packedPages(
    sheet: String,
    blocks: List<BoardBlock>,
    maxSheets: Int = 0,
    header: (@Composable ColumnScopeLike.() -> Unit)? = null,
): List<BoardPage> {
    if (blocks.isEmpty()) return emptyList()
    // where each page starts, as the last measurement found it; one page until the board has measured itself
    var cuts by remember(sheet) { mutableStateOf(listOf(0)) }
    val safe = cuts.filter { it in blocks.indices }.distinct().sorted().ifEmpty { listOf(0) }
    return safe.indices.map { p ->
        val from = safe[p]
        val to = safe.getOrNull(p + 1) ?: blocks.size
        val names = blocks.subList(from, to.coerceAtLeast(from)).mapNotNull { it.name }
        // the bubble on a page turn names what is on the sheet: its sections, or the sheet and its number
        val title = when {
            // the counter at the foot already says which sheet of how many, so the title is just what it is
            names.isEmpty() -> sheet
            names.size <= 2 -> names.joinToString(" · ")
            else -> names[0] + " · " + names[1] + " …"
        }
        BoardPage(title) {
            PackedSheet(sheet, blocks, p, header, safe, maxSheets) { found -> if (found != cuts) cuts = found }
        }
    }
}

/**
 * One sheet of a packed board: measures every block at the sheet's width, places the ones belonging to this page.
 *
 * The blocks of the other pages are composed and measured here too — a briefing is a page of print, so that costs
 * nothing worth saving, and it is what lets any page work out where all the breaks fall rather than depending on
 * the pages before it having been looked at.
 */
@Composable
private fun PackedSheet(
    title: String,
    blocks: List<BoardBlock>,
    page: Int,
    header: (@Composable ColumnScopeLike.() -> Unit)?,
    cuts: List<Int>,
    maxSheets: Int,
    onCuts: (List<Int>) -> Unit,
) {
    val scope = remember { ColumnScopeLike() }
    SubcomposeLayout(
        Modifier.fillMaxSize().padding(start = SHEET_SIDE, end = SHEET_SIDE, top = SHEET_HEAD, bottom = SHEET_FOOT),
    ) { constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val full = Density(density, fontScale)

        /** Everything on the sheet, measured with the print set [set] times smaller than the pilot chose. */
        fun laidOut(set: Float): Triple<Placeable, Placeable?, List<Placeable>> {
            fun one(slot: Any, content: @Composable () -> Unit): Placeable {
                val ink = if (set <= 1.001f) full else Density(full.density / set, full.fontScale)
                return subcompose(slot to set) {
                    CompositionLocalProvider(LocalDensity provides ink) {
                        Column(Modifier.fillMaxWidth()) { content() }
                    }
                }.first().measure(loose)
            }
            val head = one("title") { SheetTitle(title) }
            val hdr = header?.let { h -> one("header") { h(scope) } }
            return Triple(head, hdr, blocks.mapIndexed { i, b -> one(i) { b.content(scope) } })
        }

        /** Where the sheets break, for print of this size. */
        fun breaks(body: Int, parts: List<Placeable>): List<Int> {
            val found = mutableListOf(0)
            var used = 0
            parts.forEachIndexed { i, part ->
                // a block that will not fit starts the next sheet; one taller than any sheet has one to itself
                if (used > 0 && used + part.height > body) { found += i; used = 0 }
                used += part.height
            }
            return found
        }

        // The print is set at the size the pilot chose, and only smaller if that is what it takes to keep the board
        // to [maxSheets] sheets — a step at a time, stopping at the first size that fits, and never past about a
        // quarter down, which is still the "Small print" setting. A board that will not fit even then runs on
        // rather than becoming unreadable.
        // A table of rows gains height and nothing else from smaller print — a row does not reflow — so the ladder
        // goes down as far as the "Small print" setting before it gives up and lets the board run on.
        val sizes = if (maxSheets > 0) listOf(1f, 1.08f, 1.16f, 1.26f, 1.36f, 1.48f, 1.6f) else listOf(1f)
        var (head, hdr, parts) = laidOut(sizes.first())
        var body = (constraints.maxHeight - head.height - (hdr?.height ?: 0)).coerceAtLeast(1)
        var found = breaks(body, parts)
        for (set in sizes.drop(1)) {
            if (found.size <= maxSheets) break
            val smaller = laidOut(set)
            val smallerBody = (constraints.maxHeight - smaller.first.height - (smaller.second?.height ?: 0)).coerceAtLeast(1)
            val smallerBreaks = breaks(smallerBody, smaller.third)
            // a size that gains nothing is not worth the smaller print
            if (smallerBreaks.size < found.size) {
                head = smaller.first; hdr = smaller.second; parts = smaller.third
                body = smallerBody; found = smallerBreaks
            }
        }
        if (found != cuts) onCuts(found.toList())

        val from = cuts.getOrElse(page) { 0 }
        val to = (cuts.getOrNull(page + 1) ?: parts.size).coerceAtMost(parts.size)
        val sheet = parts
        val title0 = head
        val header0 = hdr
        layout(constraints.maxWidth, constraints.maxHeight) {
            var y = 0
            title0.place(0, y); y += title0.height
            header0?.let { it.place(0, y); y += it.height }
            for (i in from until to) { sheet[i].place(0, y); y += sheet[i].height }
        }
    }
}

/**
 * The mission briefing, filled onto as few sheets as it takes — one or two for most missions.
 *
 * A section to a page was a page of six lines and then five inches of empty board: the flight, the package, the
 * threats, the ROE, the emergencies and the weather are each short, and a pilot reaching for the briefing in the
 * cockpit wants it in a glance and a page turn, not six presses.
 */
@Composable
private fun briefingPages(): List<BoardPage> {
    val mission by MissionLink.mission.collectAsState()
    val b = mission?.briefing ?: return listOf(BoardPage("Briefing") {
        BoardNotice("No briefing yet", "Press PRINT on the BMS briefing screen and this board fills itself in.")
    })
    val blocks = mutableListOf<BoardBlock>()
    val o = b.overview
    val facts = listOfNotNull(
        o.flight?.let { "Flight" to it },
        o.mission?.let { "Mission" to it },
        o.packageId?.let { "Package" to it },
        o.targetArea?.let { "Target area" to it },
        o.tot?.let { "TOT" to it },
        o.sunrise?.let { "Sunrise" to it },
        o.sunset?.let { "Sunset" to it },
    )
    blocks += BoardBlock("Mission") {
        Head("Mission")
        Facts(facts)
        b.situation?.takeIf { it.isNotBlank() }?.let { Para(it) }
    }
    if (b.`package`.isNotEmpty()) blocks += BoardBlock("Package") {
        Head("Package")
        Table(
            weights = listOf(1.2f, 1.7f, 1f, 1f, 1f),
            header = listOf("Flight", "Aircraft", "T/O", "Push", "Target"),
            rows = b.`package`.map { f ->
                listOf(
                    f.callsign.orEmpty(),
                    f.aircraft.orEmpty(),
                    f.takeoff.orEmpty(),
                    f.push.orEmpty(),
                    f.target.orEmpty(),
                )
            },
        )
    }
    if (b.threats.isNotEmpty()) blocks += BoardBlock("Threats") {
        Head("Threats")
        b.threats.forEach { t -> t.title?.let { Head(it) }; t.lines.forEach { Para(it) } }
    }
    if (b.roe.isNotEmpty()) blocks += BoardBlock("ROE") {
        Head("Rules of engagement")
        b.roe.forEach { Para(it) }
    }
    if (b.emergency.isNotEmpty()) blocks += BoardBlock("Emergency") {
        Head("Emergency")
        b.emergency.forEach { t -> t.title?.let { Head(it) }; t.lines.forEach { Para(it) } }
    }
    b.weather?.let { w ->
        blocks += BoardBlock("Weather") {
            Head("Weather")
            Facts(w.rows.map { it.label to it.values.joinToString("  ") })
        }
    }
    return packedPages("Briefing", blocks, maxSheets = 2)
}

/** The flight plan, a row at a time, filling each sheet. */
@Composable
private fun flightPages(): List<BoardPage> {
    val mission by MissionLink.mission.collectAsState()
    val steer = mission?.briefing?.steerpoints.orEmpty()
    if (steer.isEmpty()) return listOf(BoardPage("Flight plan") {
        BoardNotice("No flight plan yet", "Press PRINT on the BMS briefing, or save the DTC, and this board fills itself in.")
    })
    val blocks = steer.mapIndexed { r, s ->
        BoardBlock {
            Cols(
                listOf(
                    "${s.n}  ${s.desc.orEmpty()}" to 3f,
                    s.time.orEmpty() to 2.5f,
                    s.heading.orEmpty() to 1.2f,
                    s.dist.orEmpty() to 1.4f,
                    s.alt.orEmpty() to 1.7f,
                    s.cas.orEmpty() to 1.4f,
                ),
                row = r,
            )
        }
    }
    return packedPages("Flight plan", blocks) {
        Cols(listOf("STPT" to 3f, "TIME" to 2.5f, "HDG" to 1.2f, "DIST" to 1.4f, "ALT" to 1.7f, "CAS" to 1.4f), header = true)
    }
}

/** The comm ladder, the same way. */
@Composable
private fun commsPages(): List<BoardPage> {
    val mission by MissionLink.mission.collectAsState()
    val comms = mission?.briefing?.comms.orEmpty()
    if (comms.isEmpty()) return listOf(BoardPage("Comms") {
        BoardNotice("No comm plan yet", "Press PRINT on the BMS briefing, or save the DTC, and this board fills itself in.")
    })
    val blocks = comms.mapIndexed { r, c ->
        BoardBlock {
            Cols(
                listOf(
                    c.agency to 2.5f,
                    c.callsign.orEmpty() to 2.4f,
                    listOfNotNull(c.uhfCh?.let { "$it" }, c.uhf).joinToString(" ") to 2.7f,
                    listOfNotNull(c.vhfCh?.let { "$it" }, c.vhf).joinToString(" ") to 2.6f,
                ),
                row = r,
            )
        }
    }
    return packedPages("Comms", blocks) {
        Cols(listOf("AGENCY" to 2.5f, "CALLSIGN" to 2.4f, "UHF" to 2.7f, "VHF" to 2.6f), header = true)
    }
}

@Composable
private fun supportPages(env: MissionEnv): List<BoardPage> {
    // the same table the app builds — briefing, comm ladder, radio plan, the AWACS feed and BMS's default tanker
    // channels — rather than the four fields the briefing prints, because a board is where it is actually needed
    val assets = com.bmscompanion.app.ui.screens.mission.rememberSupportAssets(env)
    if (assets.isEmpty()) return listOf(BoardPage("Support") {
        BoardSheet("Tankers & AWACS") { Para("Nothing in the briefing about tankers or AWACS.") }
    })
    val blocks = assets.map { a ->
        BoardBlock(a.callsign ?: a.role) {
            // the callsign is what is called on the radio, so it is the name of the block, not a word in a line
            Title(a.callsign ?: a.role.orEmpty(), tag = if (a.yours) "yours" else null)
            Note(listOfNotNull(a.role, a.aircraft).joinToString(" · "))
            // a channel and a frequency are reached for with the eyes outside: they are boxed and set large
            Chips(
                listOfNotNull(
                    a.tacan?.let { "TACAN" to it + if (a.tacanDefault) "*" else "" },
                    a.tieOn?.let { "Tie-on" to it },
                    a.uhf?.let { "UHF" to (a.uhfCh?.let { c -> "$c · " }.orEmpty() + it) },
                    a.vhf?.let { "VHF" to it },
                    a.loc?.let { "Bullseye" to it },
                ) + a.radios.map { r -> r.label to listOfNotNull(r.ch?.let { c -> "ch $c" }, r.uhf ?: r.vhf).joinToString(" · ") },
            )
            a.notes?.takeIf { it.isNotBlank() }?.let { Para(it) }
        }
    } + if (assets.any { it.tacanDefault }) {
        listOf(BoardBlock { Para("* BMS's default channel, not printed in the briefing.") })
    } else {
        emptyList()
    }
    return packedPages("Tankers & AWACS", blocks)
}

/** Every threat the briefing names, matched against the threat reference and laid down the sheet. */
@Composable
private fun threatPages(): List<BoardPage> {
    val mission by MissionLink.mission.collectAsState()
    val all by produceState<List<Threat>>(emptyList()) { value = Repo.threats() }
    val text = mission?.briefing?.threats.orEmpty().flatMap { it.lines }.joinToString(" ").lowercase()
    if (text.isBlank()) return listOf(BoardPage("Threats") {
        BoardNotice("No threats listed yet", "The briefing's threat section fills this board in.")
    })
    val named = all.filter { t ->
        val keys = (listOf(t.name) + t.aliases).map { it.lowercase() }.filter { it.length >= 3 }
        keys.any { text.contains(it) }
    }.distinctBy { it.id }
    if (named.isEmpty()) {
        val said = mission?.briefing?.threats.orEmpty()
        return packedPages("Threats", said.map { t ->
            BoardBlock(t.title) { t.title?.let { Head(it) }; t.lines.forEach { Para(it) } }
        }).ifEmpty { listOf(BoardPage("Threats") { BoardNotice("No threats listed yet", "The briefing's threat section fills this board in.") }) }
    }
    // a system is a heading and a handful of figures, so a page each was a page each of empty board
    val blocks = named.map { t ->
        BoardBlock(t.name) {
            Head(t.name)
            Rows(
                listOfNotNull(
                    t.type?.let { "Type" to it },
                    "Category" to t.category,
                    t.harmAlic?.let { "HARM ALIC" to it },
                    t.rwr["symbol"]?.let { sym -> "RWR" to sym },
                ) + t.stats.take(8).map { it.label to it.value },
            )
        }
    }
    return packedPages("Threats", blocks)
}

/**
 * The ALIC codes: one table, and one page unless the print is set large enough to need two.
 *
 * A row for each system — the site code, the tracking radar and its code, the search radar and its code — because
 * three pages that each said "SA-2" were three pages of a board for one number a pilot types into the HARM.
 */
@Composable
private fun harmPages(): List<BoardPage> {
    val harm by produceState<com.bmscompanion.app.data.HarmFile?>(null) { value = Repo.harm() }
    val codes = harm?.alicCodes.orEmpty()
    if (codes.isEmpty()) return listOf(BoardPage("HARM") { BoardNotice("HARM codes", "Loading the ALIC table…") })
    val rows = mutableListOf<List<Pair<String, Float>>>()
    codes.groupBy { it.system }.forEach { (system, group) ->
        val site = group.firstOrNull { it.category == "SAM" }
        val fcr = group.firstOrNull { it.category == "FCR" }
        val ewr = group.firstOrNull { it.category == "EWR" }
        val other = group.filter { it.category != "SAM" && it.category != "FCR" && it.category != "EWR" }
        if (site != null || fcr != null || ewr != null) rows += listOf(
            system to 1.7f,
            (site?.alic ?: "–") to 0.9f,
            listOfNotNull(fcr?.alic, fcr?.radar).joinToString(" ").ifBlank { "–" } to 3.3f,
            listOfNotNull(ewr?.alic, ewr?.radar).joinToString(" ").ifBlank { "–" } to 3.2f,
        )
        // AAA and the odd one out have no three-radar shape: they get their own line rather than a column
        other.forEach { c ->
            rows += listOf(
                system to 1.7f,
                c.alic to 0.9f,
                listOfNotNull(c.radar, c.category).joinToString(" ") to 6.5f,
            )
        }
    }
    val blocks = rows.mapIndexed { r, cells -> BoardBlock { Cols(cells, row = r) } }
    // one sheet: the table is read while typing a code into the HARM, and a page turn in the middle of that is
    // a page turn too many. The print is set down a step if that is what it takes.
    return packedPages("HARM ALIC codes", blocks, maxSheets = 1) {
        Cols(listOf("SYSTEM" to 1.7f, "SITE" to 0.9f, "TRACK (FCR)" to 3.3f, "SEARCH (EWR)" to 3.2f), header = true)
    }
}

/** Every page of every chart for the field this mission takes off from. */
@Composable
private fun chartPages(env: MissionEnv, instrument: Boolean): List<BoardPage> {
    val live by MissionLink.live.collectAsState()
    val mission by MissionLink.mission.collectAsState()
    val bases = airbases(live, mission?.briefing)
    val field: Airport? = matchAirport(env.set, bases.departure) ?: matchAirport(env.set, bases.arrival)
    val setId = env.theater?.airportSet
    val charts by produceState<List<ChartRef>?>(null, setId, field?.id) {
        value = if (setId == null || field == null) emptyList() else Repo.charts(setId)[field.id.toString()].orEmpty()
    }
    val what = if (instrument) "Instrument charts" else "Airfield plates"
    if (field == null) return listOf(BoardPage(what) {
        BoardNotice(what, "This board follows the field you take off from. Press PRINT on the BMS briefing and it fills itself in.")
    })
    val list = charts ?: return listOf(BoardPage(what) { BoardNotice(what, "Loading ${field.name}…") })
    val wanted = list.filter { it.pages.isNotEmpty() == instrument }
    if (wanted.isEmpty()) return listOf(BoardPage(what) {
        BoardNotice(what, "${field.name} has no ${if (instrument) "instrument charts" else "ground plates"} in this theater.")
    })
    return wanted.flatMap { c ->
        val files = if (c.pages.isEmpty()) listOf(c.file) else List(c.pages.size) { chartPage(c.pages[0], it + 1) }
        files.mapIndexed { i, file ->
            BoardPage(if (files.size > 1) "${c.title} ${i + 1}" else c.title) {
                Box(Modifier.fillMaxSize().padding(PLATE_MARGIN)) { AssetImage(file, Modifier.fillMaxSize()) }
            }
        }
    }
}

@Composable
private fun picturePages(env: MissionEnv): List<BoardPage> {
    val contacts by MissionLink.contacts.collectAsState()
    val live by MissionLink.live.collectAsState()
    val all = contacts?.contacts.orEmpty()
    val hostiles = all.filter { !it.friendly && !it.own && it.kind == "air" }
    if (hostiles.isEmpty()) return listOf(BoardPage("Picture") {
        BoardNotice("No hostile air", "The picture comes from BMS's Tacview feed — press F in the cockpit to start ACMI recording.")
    })
    val own = ownship(live)
    val bull = bullseye(live, all)
    // What a pilot does with this list is call it: BRAA off their own jet, bullseye for everyone else, and the
    // altitude. Nearest first, because that is the one that matters.
    val sorted = hostiles.sortedBy { c -> own?.let { rangeNm(it.first, it.second, c.x, c.y) } ?: Double.MAX_VALUE }
    val blocks = sorted.map { c ->
        BoardBlock {
            Cols(
                listOf(
                    listOfNotNull(c.name, c.group).joinToString(" · ").ifBlank { "Contact" } to 3.2f,
                    (own?.let { bra(it.first, it.second, c.x, c.y) } ?: "–") to 1.7f,
                    (bull?.let { bra(it.first, it.second, c.x, c.y) } ?: "–") to 1.7f,
                    flightLevel(c.altFt) to 1.1f,
                    (if (c.gsKts > 0) "${c.gsKts.toInt()}" else "") to 1f,
                ),
                row = sorted.indexOf(c),
            )
        }
    }
    return packedPages("Hostile picture", blocks) {
        Cols(listOf("CONTACT" to 3.2f, "BRAA" to 1.7f, "BULLS" to 1.7f, "ALT" to 1.1f, "GS" to 1f), header = true)
    }
}

// ---------------------------------------------------------------- the paper

/** A printed sheet: a heading, a rule, and the lines under it. */
@Composable
private fun BoardSheet(title: String, content: @Composable ColumnScopeLike.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = SHEET_SIDE, end = SHEET_SIDE, top = SHEET_HEAD, bottom = SHEET_FOOT),
    ) {
        SheetTitle(title)
        ColumnScopeLike().content()
    }
}

/**
 * What the sheet is: the one thing on the page set larger than the print, in the board's accent ink.
 *
 * Everything on a board used to be within a size and a shade of everything else, and through a headset lens that
 * reads as one grey block: the pilot has to work out what they are looking at before they can look for anything.
 * The page now has three voices — this title, the section headings under it, and the print itself.
 */
@Composable
private fun SheetTitle(title: String) {
    Text(
        title.uppercase(),
        color = Hud.Amber,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        maxLines = 1,
    )
    Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 5.dp).height(2.dp).background(Hud.Amber.copy(alpha = 0.55f)))
}

/** The little vocabulary a page is written in, so every board reads the same way. */
class ColumnScopeLike {
    @Composable
    fun Head(text: String) {
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(11.dp).background(Hud.Amber))
            Text(
                text.uppercase(),
                Modifier.padding(start = 5.dp),
                color = Hud.Text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(3.dp))
    }

    /**
     * The figures a pilot is actually reaching for — a TACAN channel, a frequency, a bullseye call — each in a
     * raised box of its own.
     *
     * On a kneeboard these are what the hand goes to while the eyes are outside: printed in the line with everything
     * else they have to be found by reading, which is exactly what there is no time for. Boxed and set large, they
     * are found by shape.
     */
    @Composable
    fun Chips(pairs: List<Pair<String, String>>, columns: Int = 3) {
        if (pairs.isEmpty()) return
        pairs.chunked(columns).forEach { line ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                line.forEach { (label, value) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(4.dp))
                            .background(Hud.Amber.copy(alpha = 0.10f))
                            .border(1.dp, Hud.Amber.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text(label.uppercase(), color = Hud.TextFaint, fontSize = 9.sp, letterSpacing = 0.8.sp, maxLines = 1)
                        Text(
                            value,
                            color = Hud.Text,
                            fontSize = if (value.length > 12) 12.sp else 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
                repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    /** The name of the thing the block is about: a filled strip, so a list of them reads as a list. */
    @Composable
    fun Title(text: String, tag: String? = null) {
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(Hud.Amber.copy(alpha = 0.16f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f), color = Hud.Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            tag?.let { Text(it.uppercase(), color = Hud.Text, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp) }
        }
        Spacer(Modifier.height(3.dp))
    }

    /**
     * A row of columns at the given weights: what a table on a board is made of.
     *
     * Data rows are banded and their first column is bold, because a board is read at arm's length through a
     * headset lens, in one glance, while flying — the band keeps the eye on the line and the bold name is what the
     * eye is looking for. Pass [row] the row's index for the banding.
     */
    @Composable
    fun Cols(cells: List<Pair<String, Float>>, header: Boolean = false, row: Int = -1) {
        // a hairline under every row and between every column: a table on a kneeboard is ruled, and a figure is
        // found by the cell it sits in rather than by reading along the line
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                .background(if (!header && row >= 0 && row % 2 == 1) Hud.Text.copy(alpha = 0.09f) else Color.Transparent)
                .padding(vertical = if (header) 2.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cells.forEachIndexed { i, (text, w) ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline))
                Text(
                    text,
                    Modifier.weight(w).padding(horizontal = 3.dp, vertical = 2.dp),
                    color = if (header) Hud.TextFaint else Hud.Text,
                    fontSize = if (header) 9.sp else 12.sp,
                    fontWeight = when {
                        header -> FontWeight.SemiBold
                        i == 0 && row >= 0 -> FontWeight.Bold
                        else -> FontWeight.Normal
                    },
                    // the first column is the name the eye searches for — it wraps rather than being cut short
                    maxLines = if (i == 0) 2 else 1,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Outline.copy(alpha = if (header) 0.7f else 1f)))
    }

    /**
     * Something that must not be read past: a bingo fuel, a ROE line, a threat that is already up. Boxed and with a
     * rule down its side, so it is found without reading the page.
     */
    @Composable
    fun Callout(title: String, text: String, accent: Color = Hud.Amber) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp)
                .background(accent.copy(alpha = 0.10f))
                .padding(start = 1.dp),
        ) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(title.uppercase(), style = LocalExtra.current.overline, color = accent)
                Text(text, color = Hud.Text, fontSize = 12.sp, lineHeight = 15.sp)
            }
        }
    }

    /**
     * Short facts side by side — frequencies, channels, TACANs. Two columns of pairs fit where one column of lines
     * would have run onto a second page, and nothing here is long enough to need the width.
     */
    @Composable
    fun Grid(pairs: List<Pair<String, String>>, columns: Int = 2) {
        pairs.chunked(columns).forEachIndexed { r, chunk ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (r % 2 == 1) Hud.Text.copy(alpha = 0.055f) else Color.Transparent)
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                chunk.forEach { (k, v) ->
                    Row(Modifier.weight(1f).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(k, Modifier.weight(1f), color = Hud.TextDim, fontSize = 11.sp, maxLines = 1)
                        Text(v, style = LocalExtra.current.monoSmall, color = Hud.Text, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    /**
     * A ruled table: a box around it, a hairline between every cell, and a header row that is part of the box.
     *
     * This is what a kneeboard looks like and what the pilot's eye is trained on — a figure sits in a cell of its
     * own, so it can be found by position rather than by reading the line. [weights] gives the columns their share
     * of the width; [header] is optional, and a cell that is blank still holds its place.
     */
    @Composable
    fun Table(
        weights: List<Float>,
        rows: List<List<String>>,
        header: List<String>? = null,
        labels: Set<Int> = emptySet(),
    ) {
        if (rows.isEmpty() && header == null) return
        val rule = Hud.Outline
        Column(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 5.dp)
                .border(1.dp, rule, RoundedCornerShape(3.dp)),
        ) {
            @Composable
            fun line(cells: List<String>, head: Boolean, band: Boolean) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                        .background(if (band) Hud.Text.copy(alpha = 0.05f) else Color.Transparent),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // a row with fewer cells than the table has columns spans the rest with its last cell, so a
                    // long value takes the width of the line instead of leaving empty boxes beside it
                    val spread = weights.mapIndexed { i, w ->
                        if (i == cells.size - 1) weights.drop(i).sum() else w
                    }.take(maxOf(cells.size, 1))
                    spread.forEachIndexed { i, w ->
                        if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(rule))
                        val label = head || i in labels
                        Text(
                            cells.getOrElse(i) { "" },
                            Modifier.weight(w).padding(horizontal = 5.dp, vertical = 3.dp),
                            color = if (label) Hud.TextDim else Hud.Text,
                            fontSize = if (head) 10.sp else if (label) 11.sp else 12.sp,
                            fontWeight = if (label) FontWeight.Normal else FontWeight.Bold,
                            letterSpacing = if (head) 0.6.sp else 0.sp,
                            maxLines = 2,
                        )
                    }
                }
            }
            header?.let {
                line(it.map { c -> c.uppercase() }, head = true, band = false)
                Box(Modifier.fillMaxWidth().height(1.dp).background(rule))
            }
            rows.forEachIndexed { r, cells ->
                if (r > 0 || header != null) Box(Modifier.fillMaxWidth().height(1.dp).background(rule))
                line(cells, head = false, band = r % 2 == 1)
            }
        }
    }

    /** Facts two to a line, each in its own cell: the densest way to print a dozen figures a pilot glances at. */
    @Composable
    fun Facts(pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) return
        // Two facts to a line where they fit, one where the value is long: a figure broken across two lines inside
        // a cell is the one thing worse than a half-empty row, and a board is read in a glance.
        val rows = mutableListOf<List<String>>()
        var held: Pair<String, String>? = null
        fun long(p: Pair<String, String>) = p.first.length + p.second.length > 26
        pairs.forEach { p ->
            when {
                long(p) -> {
                    held?.let { rows += listOf(it.first, it.second); held = null }
                    rows += listOf(p.first, p.second)
                }
                held == null -> held = p
                else -> {
                    rows += listOf(held!!.first, held!!.second, p.first, p.second)
                    held = null
                }
            }
        }
        held?.let { rows += listOf(it.first, it.second) }
        Table(listOf(1f, 1.3f, 1f, 1.3f), rows, labels = setOf(0, 2))
    }

    @Composable
    fun Para(text: String) {
        Text(text, color = Hud.Text, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 3.dp))
    }

    /** A note in the margin: what this page is, or what to do about it. Never mistaken for the briefing itself. */
    @Composable
    fun Note(text: String) {
        Text(text, color = Hud.TextFaint, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 3.dp))
    }

    @Composable
    fun Line(left: String, right: String, bold: Boolean = false) {
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(left, Modifier.weight(1f), color = Hud.Text, fontSize = 12.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 2)
            if (right.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(right, style = LocalExtra.current.monoSmall, color = Hud.TextDim, maxLines = 1)
            }
        }
    }

    @Composable
    fun Rows(pairs: List<Pair<String, String>>) {
        pairs.forEach { (k, v) -> Line(k, v) }
    }
}

/**
 * The pages html_brief exported, each one drawn as it was made.
 *
 * Nothing is re-typeset: this is the tool's own kneeboard, rendered on the PC and shown here, which is the point of
 * having it. A board with no export behind it says so, and a board whose briefing has moved on says that instead.
 */
@Composable
private fun exportedPages(): List<BoardPage> {
    val info by MissionLink.info.collectAsState()
    val kb = info?.kneeboard
    if (kb == null || !kb.available) return listOf(BoardPage("HTML Briefing") {
        BoardNotice(
            "No HTML Briefing kneeboard",
            kb?.message ?: "This board shows the pages BMS's HTML Briefing tool exports. Run it on the BMS PC and export, " +
                "or give this board something else to show — nothing else depends on that tool.",
        )
    })
    return (0 until kb.pages).map { i ->
        BoardPage("Page ${i + 1}") { ExportedPage(i, kb.exported, kb.stale && i == 0) }
    }
}

@Composable
private fun ExportedPage(index: Int, exported: Long, warn: Boolean) {
    val bmp by produceState<android.graphics.Bitmap?>(null, index, exported) {
        value = com.bmscompanion.app.data.mission.MissionLink
            .fetchBytes(com.bmscompanion.app.ui.screens.mission.kneeboardPagePath(index, 1600), timeoutMs = 20_000)
            ?.let { Repo.decodeBitmap(it) }
    }
    Box(Modifier.fillMaxSize().padding(PLATE_MARGIN)) {
        val b = bmp
        if (b != null) {
            com.bmscompanion.app.ui.screens.ZoomableBitmap(b, Modifier.fillMaxSize(), background = androidx.compose.ui.graphics.Color.White)
        } else {
            BoardNotice("Page ${index + 1}", "Fetching it from the PC…")
        }
        if (warn) Text(
            "The briefing has been printed again since this was exported",
            Modifier.align(Alignment.BottomCenter).padding(6.dp),
            color = Hud.Red, fontSize = 11.sp,
        )
    }
}

/**
 * The bubble that appears when a page is turned, and fades.
 *
 * Modelled on the little panel a tablet shows for the volume: it says what just happened, over the page rather
 * than beside it, and it is gone before it becomes furniture. In a headset it is the only confirmation a pilot
 * gets that the button they pressed did what they wanted, so it is large enough to read in a glance and dim
 * enough not to be read twice.
 */
@Composable
private fun BoxScope.PageBubble(text: String?) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(text) { shown.animateTo(if (text == null) 0f else 1f, tween(if (text == null) 450 else 160)) }
    var fading by remember { mutableStateOf<String?>(null) }
    if (text != null && text != fading) fading = text
    val label = text ?: fading ?: return
    if (shown.value <= 0.01f) return
    Box(
        Modifier.align(Alignment.TopCenter).padding(top = 10.dp).graphicsLayer { alpha = shown.value }
            .clip(RoundedCornerShape(50))
            .background(Hud.Bg.copy(alpha = 0.72f))
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(label, color = Hud.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}


/** When there is nothing to print yet, the board says why rather than showing an empty sheet. */
@Composable
private fun BoardNotice(title: String, text: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Hud.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(text, color = Hud.TextDim, fontSize = 13.sp)
    }
}

