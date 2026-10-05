package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampAtoTarget
import com.bmscompanion.app.data.mission.CampAtoTargets
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.data.wdp.DataCardPlan
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.data.wdp.WdpCoords
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.launch

/**
 * **ATO Targets**, the Planner's page ([WdpPage.ATO], last in the tab row): WDP's `fclsAtoTargetList` (its Options →
 * ATO Target List, and the button in its flight selection), drawn as a whole page rather than a window, so it is one
 * tab press away like the other pages and needs no menu. For information only: the targets the flights of the pilot's
 * side are tasked to attack in the save the Planner has open, so the pilot can see at a glance what is being hit, by
 * whom and when. The DataCard's flight selection (WDP's ATO Target List button) turns to this page ([AtoTargetList.open]).
 *
 * - **Which save**: the one it was turned to for ([AtoTargetList.open]), else the save the Planner plans from, else the
 *   one the flight picker shows, else the save BMS printed its briefing from. Read again every time the page is shown
 *   (BMS writes `Auto Save.cam` over the same name). **Which side**: the team of the flight the Planner has open from that
 *   save, else the pilot's own as the PC takes it (the PC's `/api/campaign/atotargets`, `CampaignAtoTargets`).
 * - Two lists as WDP has them, **Units** (targets that are units of the save: battalions, brigades, ships) and
 *   **Objectives** (airbases, bridges, factories…), each with WDP's twelve columns: Nr, TARGET, TOT, Lat, Long, Package,
 *   Flight, Aircraft, Type, Squadron, Airbase, TakeOff. Both open sorted by TARGET; a press on a heading sorts by that
 *   column, again the other way. Numbers and times sort as numbers and times (WDP sorted them as text: "10" before
 *   "9").
 * - A row is steel blue when its flight has taken off by the save's clock (**Flight is airborne**), and its TOT red when
 *   that time has passed (**TOT has passed**). A press selects a row, as WDP's full-row select does; nothing opens it
 *   (WDP's window answers no double click either).
 * - **Lat/Long** are printed with the Planner's own projection ([WdpCoords], as every latitude and longitude of the
 *   Planner): WDP's window printed 00,00.000 for every target (docs/WDP-PORT.md, "ATO Target List").
 * - Wider than the page (a phone, a small PC window), the lists scroll sideways with Nr and TARGET kept in place; a
 *   phone shows one list at a time. The bar under a list drags it sideways with a mouse.
 */
@Composable
fun AtoTargetsPage(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(Hud.Bg).padding(horizontal = 8.dp, vertical = 4.dp).plannerProbe("AtoTargets/Page"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) { AtoTargetListBody() }
}

/** The page's content: the save, WDP's words, the clock and the key, then the two lists. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ColumnScope.AtoTargetListBody() {
    val link by MissionLink.state.collectAsState()
    val linked = link is LinkState.Online
    val s = AtoTargetList
    val scope = rememberCoroutineScope()
    val arg = s.asked
    // the save the Planner plans from changes the page's save, as the window read it each time it opened
    val planned = PlannerMissionState.ref?.takeIf { PlannerMissionState.fromSave }
    var ref by remember(arg, planned) { mutableStateOf(s.refFor(arg)) }
    var looked by remember(arg, planned) { mutableStateOf(ref != null) }
    LaunchedEffect(arg, planned, linked) {
        if (ref == null && linked) ref = runCatching { s.briefedSave() }.getOrNull()
        if (linked) looked = true
    }
    // read the save again every time the page is shown: BMS writes a save over the same name
    LaunchedEffect(linked, ref) { val r = ref; if (linked && r != null) s.load(r) }

    if (!linked) {
        Text(
            "Connect this device to BMS Companion on your PC: the ATO Target List is read from a campaign save on that PC.",
            color = Hud.Text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.plannerProbe("AtoTargets/NotLinked"),
        )
        return
    }
    val r = ref
    if (r == null) {
        // no button here: Open mission… is the toolbar's (one place per action)
        Text(
            if (!looked) "Looking for the save…" else
                "The ATO Target List is read from a campaign save. Open one with Open mission… on the toolbar and plan a flight of it, " +
                    "or press PRINT on BMS's briefing screen, so the Planner knows which save it is.",
            color = Hud.Text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.plannerProbe("AtoTargets/NoSave"),
        )
        return
    }
    val d = s.data?.takeIf { s.ref == r }
    // the save, on the head line below once it is read (WDP's words above its lists are that line's tooltip)
    if (d == null) Text(
        listOf(r.file, r.theater).joinToString(" · "),
        color = Hud.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.plannerProbe("AtoTargets/File"),
    )
    when {
        s.error != null && s.ref == r -> {
            Text(s.error!!, color = Hud.Red, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.plannerProbe("AtoTargets/Error"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlannerButton("Try again", probe = "AtoTargets/Retry") { scope.launch { s.load(r) } }
            }
            return
        }
        d == null -> {
            Text("Reading ${r.file} on the PC…", color = Hud.TextDim, fontSize = 13.sp)
            return
        }
    }
    val shown = d ?: return

    // the save's theater, for latitude and longitude (the Planner's projection, as every page prints them)
    val theater by produceState<com.bmscompanion.app.data.Theater?>(null, shown.theater) {
        value = runCatching { plannerTheater(Repo.index().theaters, shown.theater) }.getOrNull()
    }
    val coords = remember(theater) { WdpCoords.coordData(theater) }

    // ONE head line: the save, WDP's Current Time, the side, and the two colours' key (WDP's words above its lists and
    // its "For information only." are the save's tooltip)
    FlowRow(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            listOf(r.file, r.theater).joinToString(" · "),
            color = Hud.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterVertically).plannerProbe("AtoTargets/File")
                .wdpTip("planner/AtoTargets/Info", AtoTargetList.INFO + "\nFor information only.", touch = true),
        )
        Text(
            "Current Time: " + DataCardPlan.getTimeDay(shown.clock), color = Hud.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterVertically).plannerProbe("AtoTargets/Clock"),
        )
        if (shown.sideTeams.isNotEmpty()) Text(
            "Your side: " + shown.sideTeams.joinToString(", "), color = Hud.TextDim, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        Spacer(Modifier.weight(1f))
        KeyChip("TOT has passed", AtoTargetList.PASSED, Color.White, "AtoTargets/KeyPassed")
        KeyChip("Flight is airborne", AtoTargetList.AIRBORNE, Color(0xFF14202C), "AtoTargets/KeyAirborne")
    }
    for ((i, n) in shown.notes.withIndex()) Text(
        "⚠ $n", color = Hud.Amber, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.plannerProbe("AtoTargets/Note/$i").wdpTip("planner/AtoTargets/Note/$i", n, touch = true),
    )

    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val narrow = maxWidth < 600.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (narrow) {
                // a phone: one list at a time
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlannerChip("Units (${shown.units.size})", on = s.tab == 0, probe = "AtoTargets/Tab/Units") { s.tab = 0 }
                    PlannerChip("Objectives (${shown.objectives.size})", on = s.tab == 1, probe = "AtoTargets/Tab/Objectives") { s.tab = 1 }
                }
                val k = s.tab.coerceIn(0, 1)
                AtoGrid(k, if (k == 0) shown.units else shown.objectives, shown.clock, coords, narrow = true, Modifier.fillMaxWidth().weight(1f))
            } else {
                for (k in 0..1) {
                    val rows = if (k == 0) shown.units else shown.objectives
                    Text(
                        (if (k == 0) "Units" else "Objectives") + " (${rows.size})",
                        color = Hud.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.plannerProbe("AtoTargets/" + AtoTargetList.GRIDS[k]),
                    )
                    AtoGrid(
                        k, rows, shown.clock, coords, narrow = false,
                        // an empty list takes a line, and the other the room
                        if (rows.isEmpty()) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

/** One of the two colours' key: WDP's red "TOT has passed" and steel-blue "Flight is airborne" labels. */
@Composable
private fun KeyChip(text: String, fill: Color, ink: Color, probe: String) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(fill).padding(horizontal = 8.dp, vertical = 3.dp).plannerProbe(probe),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = ink, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

// ---------------------------------------------------------------- state

/**
 * The ATO Target List's state, kept for as long as the app runs: the save it shows and the PC's answer, each list's
 * sort and selected row, and which list a phone shows.
 */
object AtoTargetList {
    /** the save shown (theater and file; no flight) */
    var ref: CampRef? by mutableStateOf(null)
        private set
    var data: CampAtoTargets? by mutableStateOf(null)
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set
    /** on a phone, the list shown: 0 Units, 1 Objectives */
    var tab: Int by mutableIntStateOf(0)
    /** each list's sort, Units then Objectives: the column and whether it runs up (WDP opens both on TARGET, A to Z) */
    val sorts = mutableStateListOf(AtoCol.TARGET to true, AtoCol.TARGET to true)
    /** each list's selected row, by its flight's id */
    val selected = mutableStateListOf<String?>(null, null)
    private var askedN = 0
    /** the save the page was last turned to for ([open]), as the flight picker's argument; null = the Planner's own */
    var asked: String? by mutableStateOf(null)
        private set

    /** The two lists' names, for the probes. */
    val GRIDS = listOf("Units", "Objectives")

    /** WDP's own words above the lists (`lblInfoOnly`). */
    const val INFO = "This is a list of targets that your side has flights tasked to attack.\n" +
        "It will help you to get a quick overview what targets are being attacked."

    /** WDP's `Color.Red` for a TOT that has passed, and its `LightSteelBlue` for an airborne flight's row. */
    val PASSED = Color(0xFFE0301E)
    val AIRBORNE = Color(0xFFB0C4DE)

    /**
     * Turns the Planner to the **ATO Targets** page: on save [ref] (its theater and file) when given, else on the save the
     * Planner plans from (the DataCard's flight selection, WDP's ATO Target List button). Any window over the page closes.
     */
    fun open(ref: CampRef? = null) {
        asked = ref?.let { FlightPicker.argOf(CampRef(it.theater, it.file)) }
        PlannerWindows.close()
        WdpSession.page = WdpPage.ATO.name
    }

    /** The save the page is for: the one it was turned to for, else the Planner's own, else the one the flight picker shows. */
    fun refFor(arg: String?): CampRef? =
        FlightPicker.refOf(arg)
            ?: PlannerMissionState.ref?.takeIf { PlannerMissionState.fromSave }?.let { CampRef(it.theater, it.file) }
            ?: FlightPicker.ref

    /** The save BMS printed its briefing from, when the PC can tell (the Planner on the printed briefing). */
    suspend fun briefedSave(): CampRef? {
        val b = MissionLink.bmsFiles.value?.briefing?.takeIf { it.origin == null } ?: return null
        return BriefedSave.find(BriefedSave.key(b))?.let { CampRef(it.theater, it.file) }
    }

    /** The side: the team of the flight the Planner has open from save [r]; null lets the PC take the pilot's own. */
    fun sideFor(r: CampRef): Int? {
        val p = PlannerMissionState.ref ?: return null
        if (!PlannerMissionState.fromSave || p.theater != r.theater || !p.file.equals(r.file, ignoreCase = true)) return null
        return PlannerMissionState.flight?.row?.team?.takeIf { it in 1..7 }
    }

    /** Reads save [r]'s list from the PC. */
    suspend fun load(r: CampRef) {
        val n = ++askedN
        if (ref != r) {
            ref = r
            data = null
            selected[0] = null; selected[1] = null
        }
        error = null
        loading = true
        val a = runCatching { MissionLink.campaignAtoTargets(r.theater, r.file, sideFor(r)) }
            .getOrElse { PcAnswer(error = "The PC could not be reached: ${it.message ?: "error"}.") }
        if (n != askedN) return
        loading = false
        val v = a.value
        if (v == null) { error = a.error ?: "The PC's answer could not be read."; return }
        data = v
    }

    /** What a cell of [c] prints for row [r]; [ll] its latitude and longitude. */
    fun cell(c: AtoCol, r: CampAtoTarget, ll: Pair<String, String>?): String = when (c) {
        AtoCol.NR -> if (r.nr >= 0) r.nr.toString() else ""
        AtoCol.TARGET -> r.target
        AtoCol.TOT -> DataCardPlan.getTimeDay(r.tot)
        AtoCol.LAT -> ll?.first.orEmpty()
        AtoCol.LONG -> ll?.second.orEmpty()
        AtoCol.PACKAGE -> r.packageNumber?.toString().orEmpty()
        AtoCol.FLIGHT -> r.flight
        AtoCol.AIRCRAFT -> r.aircraft
        AtoCol.TYPE -> r.mission
        AtoCol.SQUADRON -> r.squadron
        AtoCol.AIRBASE -> r.airbase
        AtoCol.TAKEOFF -> DataCardPlan.getTimeDay(r.takeoff)
    }

    /** Row [r]'s latitude and longitude as the Planner prints them ("N37,05.123", "E127,02.345"); feet without a projection. */
    fun latLong(coords: PopupCoords.CoordData?, r: CampAtoTarget): Pair<String, String>? {
        val north = r.x ?: return null
        val east = r.y ?: return null
        val c = coords ?: return ("N ${north.toLong()}'" to "E ${east.toLong()}'")
        return runCatching { PopupCoords.hemispheres(PopupCoords.feetToCoordsBoth(c, north, east)) }.getOrNull()
    }

    /** How column [c] sorts: numbers and times as numbers, names without case. */
    fun order(c: AtoCol): Comparator<CampAtoTarget> = when (c) {
        AtoCol.NR -> compareBy { it.nr }
        AtoCol.TARGET -> compareBy { it.target.lowercase() }
        AtoCol.TOT -> compareBy { it.tot }
        AtoCol.LAT -> compareBy { it.x ?: Double.NEGATIVE_INFINITY }
        AtoCol.LONG -> compareBy { it.y ?: Double.NEGATIVE_INFINITY }
        AtoCol.PACKAGE -> compareBy { it.packageNumber ?: -1 }
        AtoCol.FLIGHT -> compareBy { it.flight.lowercase() }
        AtoCol.AIRCRAFT -> compareBy { it.aircraft.lowercase() }
        AtoCol.TYPE -> compareBy { it.mission.lowercase() }
        AtoCol.SQUADRON -> compareBy<CampAtoTarget> { it.squadron.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.squadron.lowercase() }
        AtoCol.AIRBASE -> compareBy { it.airbase.lowercase() }
        AtoCol.TAKEOFF -> compareBy { it.takeoff }
    }

    /** [rows] in list [k]'s sort (a stable sort over the PC's order, which is WDP's: by TARGET). */
    fun sorted(k: Int, rows: List<CampAtoTarget>): List<CampAtoTarget> {
        val (c, up) = sorts.getOrNull(k) ?: (AtoCol.TARGET to true)
        val o = order(c)
        return rows.sortedWith(if (up) o else o.reversed())
    }

    /** A press on list [k]'s heading [c]: sorts by it, and the other way when it already is. */
    fun sortBy(k: Int, c: AtoCol) {
        val (was, up) = sorts[k]
        sorts[k] = c to (if (was == c) !up else true)
    }
}

/** WDP's twelve columns, with their widths in dp (a mouse's; a phone's TARGET is narrower). Nr and TARGET stay put. */
enum class AtoCol(val head: String, val wide: Int, val narrow: Int) {
    NR("Nr", 40, 34), TARGET("TARGET", 190, 136), TOT("TOT", 84, 84), LAT("Lat", 88, 88), LONG("Long", 96, 96),
    PACKAGE("Package", 66, 66), FLIGHT("Flight", 84, 84), AIRCRAFT("Aircraft", 112, 112), TYPE("Type", 96, 96),
    SQUADRON("Squadron", 72, 72), AIRBASE("Airbase", 132, 132), TAKEOFF("TakeOff", 84, 84);

    val frozen: Boolean get() = this == NR || this == TARGET
}

/**
 * One of the two lists: the headings, then a row per flight. Wider than its box it scrolls sideways (a finger's drag,
 * Shift and the wheel, or the bar under it) with Nr and TARGET held in place over the rest.
 */
@Composable
private fun AtoGrid(k: Int, rows: List<CampAtoTarget>, clock: Long, coords: PopupCoords.CoordData?, narrow: Boolean, modifier: Modifier) {
    val s = AtoTargetList
    val grid = AtoTargetList.GRIDS[k]
    val shape = RoundedCornerShape(8.dp)
    if (rows.isEmpty()) {
        Text(
            "No flight of your side is tasked against " + (if (k == 0) "a unit." else "an objective."),
            color = Hud.TextDim, fontSize = 12.5.sp,
            modifier = modifier.clip(shape).background(Hud.Surface2).padding(horizontal = 10.dp, vertical = 8.dp).plannerProbe("AtoTargets/$grid/Empty"),
        )
        return
    }
    val sort = s.sorts.getOrNull(k)
    val shownRows = remember(rows, sort) { s.sorted(k, rows) }
    val ll = remember(rows, coords) { rows.associateWith { s.latLong(coords, it) } }
    val finger = WdpTouch.device
    val grow = if (finger) 1.12f else 1f
    val rowH = if (finger) 30.dp else 24.dp
    val headH = if (finger) 30.dp else 26.dp
    val fs = fingerSp(12f)
    val h = rememberScrollState()
    Column(modifier.clip(shape).border(1.dp, Hud.Outline, shape).background(Hud.Surface)) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val cols = AtoCol.entries
            fun base(c: AtoCol): Dp = ((if (narrow) c.narrow else c.wide) * grow).dp
            val total = cols.fold(0.dp) { a, c -> a + base(c) }
            // a box wider than the table gives TARGET the room left
            val extra = if (maxWidth > total) maxWidth - total else 0.dp
            fun width(c: AtoCol): Dp = base(c) + if (c == AtoCol.TARGET) extra else 0.dp
            val frozenW = width(AtoCol.NR) + width(AtoCol.TARGET)
            Box(Modifier.fillMaxSize().horizontalScroll(h)) {
                Column(Modifier.width(total + extra).fillMaxHeight()) {
                    // headings: a press sorts, again the other way
                    Row(Modifier.fillMaxWidth().height(headH).background(Hud.Surface2)) {
                        Row(
                            Modifier.width(frozenW).fillMaxHeight().zIndex(1f).graphicsLayer { translationX = h.value.toFloat() }
                                .background(Hud.Surface2),
                        ) { for (c in cols.filter { it.frozen }) Heading(k, c, width(c), fs) }
                        for (c in cols.filter { !it.frozen }) Heading(k, c, width(c), fs)
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).plannerProbe("AtoTargets/$grid/List")) {
                        itemsIndexed(shownRows, key = { i, r -> r.flightId + "|" + r.nr + "|" + i }) { i, r ->
                            val airborne = clock > r.takeoff
                            val passed = clock > r.tot
                            val picked = s.selected.getOrNull(k) == r.flightId
                            val tint = when {
                                picked -> Hud.Amber.copy(alpha = 0.22f)
                                airborne -> AtoTargetList.AIRBORNE.copy(alpha = 0.22f)
                                else -> Color.Transparent
                            }
                            Row(
                                Modifier.fillMaxWidth().height(rowH)
                                    .pointerInput(r.flightId, k) { detectTapGestures { s.selected[k] = r.flightId } }
                                    .plannerProbe("AtoTargets/$grid/Row/$i"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    Modifier.width(frozenW).fillMaxHeight().zIndex(1f).graphicsLayer { translationX = h.value.toFloat() }
                                        .background(Hud.Surface).background(tint),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    for (c in cols.filter { it.frozen }) Cell(s.cell(c, r, ll[r]), width(c), fs, bold = c == AtoCol.TARGET)
                                }
                                Row(Modifier.fillMaxHeight().background(tint), verticalAlignment = Alignment.CenterVertically) {
                                    for (c in cols.filter { !it.frozen }) {
                                        if (c == AtoCol.TOT && passed) Cell(s.cell(c, r, ll[r]), width(c), fs, fill = AtoTargetList.PASSED, ink = Color.White, bold = true)
                                        else Cell(s.cell(c, r, ll[r]), width(c), fs)
                                    }
                                }
                            }
                            // a hairline between rows
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Outline.copy(alpha = 0.45f)))
                        }
                    }
                }
            }
        }
        HBar(h)
    }
}

/** A heading of list [k]: its name, and ▲ or ▼ on the column the list is sorted by. */
@Composable
private fun Heading(k: Int, c: AtoCol, w: Dp, fs: androidx.compose.ui.unit.TextUnit) {
    val sort = AtoTargetList.sorts.getOrNull(k)
    val on = sort?.first == c
    Box(
        Modifier.width(w).fillMaxHeight().plannerPress { AtoTargetList.sortBy(k, c) }
            .plannerProbe("AtoTargets/" + AtoTargetList.GRIDS[k] + "/Sort/" + c.head)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            c.head + if (on) (if (sort?.second == true) " ▲" else " ▼") else "",
            color = if (on) Hud.Amber else Hud.TextDim, fontSize = fs, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun Cell(
    text: String, w: Dp, fs: androidx.compose.ui.unit.TextUnit, bold: Boolean = false,
    fill: Color = Color.Transparent, ink: Color = Hud.Text,
) {
    Box(Modifier.width(w).fillMaxHeight().background(fill).padding(horizontal = 5.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text, color = ink, fontSize = fs, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The bar under a list that is wider than its box: the thumb shows which part is in view, and dragging it (or a press
 * on the track) moves the list sideways — the way a mouse moves it, which cannot drag the list itself.
 */
@Composable
private fun HBar(h: ScrollState) {
    val max = h.maxValue
    if (max <= 0 || max == Int.MAX_VALUE) return
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(if (WdpTouch.device) 14.dp else 10.dp).background(Hud.Surface2).plannerProbe("AtoTargets/Bar"),
    ) {
        val track = with(density) { maxWidth.toPx() }
        // the view is the track's width (the list fills it); the content is that and what scrolls
        val content = track + max
        val thumb = maxOf(with(density) { 28.dp.toPx() }, track * track / content)
        val scale = if (track - thumb > 0f) max / (track - thumb) else 0f
        Box(
            Modifier.fillMaxSize()
                .draggable(rememberDraggableState { dx -> h.dispatchRawDelta(dx * scale) }, Orientation.Horizontal)
                .pointerInput(max, track) {
                    detectTapGestures { o ->
                        val at = ((o.x - thumb / 2f) * scale).toInt().coerceIn(0, max)
                        h.dispatchRawDelta((at - h.value).toFloat())
                    }
                },
        ) {
            Box(
                Modifier.offset { IntOffset(((track - thumb) * h.value / max.toFloat()).toInt(), 0) }
                    .width(with(density) { thumb.toDp() }).fillMaxHeight().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Hud.TextFaint),
            )
        }
    }
}
