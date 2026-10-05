package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.CampAto
import com.bmscompanion.app.data.mission.CampFile
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampFlightRow
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampPackage
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.CampTeam
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.launch

/**
 * **Pick a flight**: the save's whole air tasking order, and the pilot's seat in the flight (R3-PLAN A8, R3-UI §3.4,
 * R3-CAM §4.4). It replaces WDP's `fclsSelection` and its side question.
 *
 * - **Team → package → flight.** A package is listed under every team that has a flight in it, marked "(package of
 *   ROK)" when another team owns it: WDP filed a package under its owner only, so in Korea a U.S. flight could only be
 *   found under ROK (fix D44). The pilot's own team comes first, then its allies (the save's own team table).
 * - It **opens on the briefed flight** (callsign, package number and flight number of the printed briefing), or else
 *   on the flight with a player slot, and on the flight already planned when it is this save's.
 * - **Find package** by its number, **Expand all** / **Collapse all**, and the **ATO Target List**: every package of
 *   the side with what each of its flights is tasked against.
 * - Flights of other aircraft are dimmed, but can be picked: the attack pages and the DTC are for the F-16.
 * - **Seat**: Lead, Wing, Element lead, Element wing (WDP's `SelPilotSeat`), as many as the flight has aircraft.
 * - **Plan this flight asks WDP's question** "Did you save Precision STPT in the DTC for THIS flight in BMS?" where WDP
 *   asks it (a campaign always; a TE when its mission file is beside it), No the default (D45). Before the press the
 *   picker says what each answer gives: "No (default): 8 from the save. Yes: 7 from your DTC, 1 from the save".
 * - The sentences the PC has about the save and the flight (another theater, older than the printed briefing, already
 *   flown, …: R3-CAM §4.5) are shown as chips.
 *
 * **Plan this flight** hands the flight to the Planner ([PlannerMissionState.plan]); every page then plans it. Cancel
 * changes nothing. The list may scroll (A21).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ColumnScope.FlightPickerWindow(onClose: () -> Unit) {
    val link by MissionLink.state.collectAsState()
    val linked = link is LinkState.Online
    val p = FlightPicker
    val scope = rememberCoroutineScope()
    val ref = remember(PlannerWindows.arg) { FlightPicker.refOf(PlannerWindows.arg) ?: PlannerMissionState.ref?.let { CampRef(it.theater, it.file) } ?: p.ref }
    // Read the save again every time the window opens: BMS writes a save over the same name (Auto Save.cam at every
    // campaign save), so a list kept by name showed the last mission's packages — the new flight could not be found.
    LaunchedEffect(linked, ref) { if (linked && ref != null) p.load(ref, again = p.ref == ref) }

    if (ref == null) {
        Text("Open a save first: Open mission… lists them.", color = Hud.Text, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlannerButton("Open mission…", probe = "FlightPicker/Files", primary = true) { PlannerWindows.show(PlannerWindow.OPEN_MISSION) }
        }
        return
    }
    if (!linked) {
        NotLinkedNote(link, "FlightPicker")
        return
    }
    val ato = p.ato?.takeIf { p.ref == ref }
    // the file: its name, theater, campaign clock and name, on one line in small type
    val fileLine = @Composable {
        Text(
            listOfNotNull(
                ref.file, ref.theater, ato?.clock?.takeIf { it > 0 }?.let { campClock(it) }, ato?.title?.trim()?.takeIf { it.isNotEmpty() },
                ato?.modified?.takeIf { it > 0 }?.let { "saved " + fileWhen(it) },
            ).joinToString(" · "),
            color = Hud.TextDim, fontSize = fingerSp(12f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    when {
        p.error != null && p.ref == ref -> {
            fileLine()
            Text(p.error!!, color = Hud.Red, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.plannerProbe("FlightPicker/Error"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlannerButton("‹ Files", probe = "FlightPicker/Files") { PlannerWindows.show(PlannerWindow.OPEN_MISSION) }
                PlannerButton("Try again", probe = "FlightPicker/Retry") { scope.launch { p.load(ref, again = true) } }
            }
            return
        }
        ato == null -> {
            fileLine()
            Text("Reading ${ref.file} on the PC…", color = Hud.TextDim, fontSize = 13.sp)
            return
        }
    }
    val shown = ato ?: return

    // The tree takes the window's height. Where there is room the flight picked sits beside it, full height with the
    // buttons at its foot; narrower, under it, never more than two fifths of the height.
    val list = rememberLazyListState()
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val beside = maxWidth >= 760.dp
        val panelW = minOf(400.dp, maxWidth * 0.4f)
        val boxH = maxHeight
        val treeSide = @Composable { m: Modifier ->
            Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                fileLine()
                // tools: one slim row (sideways scroll where it does not fit)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PackageField(Modifier) { p.find() }
                    PlannerButton("Find package", probe = "FlightPicker/Find", small = true) { p.find() }
                    PlannerButton("Expand all", probe = "FlightPicker/ExpandAll", small = true) { p.expandAll(true) }
                    PlannerButton("Collapse all", probe = "FlightPicker/CollapseAll", small = true) { p.expandAll(false) }
                    PlannerChip("ATO Target List", on = p.targets, probe = "FlightPicker/TargetList") { p.targets = !p.targets }
                }
                p.findNote?.let { Text(it, color = Hud.Amber, fontSize = fingerSp(11.5f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (p.targets) TargetList(shown, Modifier.weight(1f).fillMaxWidth()) { pkg -> p.reveal(pkg) }
                else FlightTree(shown, list, Modifier.weight(1f).fillMaxWidth())
            }
        }
        if (beside) {
            Row(Modifier.fillMaxSize()) {
                treeSide(Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.padding(horizontal = 8.dp).width(1.dp).fillMaxHeight().background(Hud.Outline.copy(alpha = 0.55f)))
                SelectedFlight(shown, ref, Modifier.width(panelW).fillMaxHeight(), fill = true)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                treeSide(Modifier.weight(1f).fillMaxWidth())
                Hairline(Modifier.padding(vertical = 4.dp))
                SelectedFlight(shown, ref, Modifier.fillMaxWidth().heightIn(max = boxH * 0.42f), fill = false)
            }
        }
    }
}

// ---------------------------------------------------------------- state

/**
 * The flight picker's state, kept for as long as the app runs: the save it shows and its ATO, the flights read so far,
 * which teams and packages are open, the flight and seat picked.
 */
object FlightPicker {
    /** the save shown (theater and file; no flight) */
    var ref: CampRef? by mutableStateOf(null)
        private set
    /** the Open mission row it was picked from, handed on to the Planner */
    var file: CampFile? by mutableStateOf(null)
        private set
    var ato: CampAto? by mutableStateOf(null)
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set
    /** the flight picked ([CampFlightRow.id]) */
    var selected: String? by mutableStateOf(null)
    var seat: Int by mutableIntStateOf(0)
    /** each flight as the PC read it, by id, for the save shown */
    val flights = mutableStateMapOf<String, PcAnswer<CampFlight>>()
    /** teams opened or closed by the pilot, by number; packages by "<team>/<package id>" */
    val openTeams = mutableStateMapOf<Int, Boolean>()
    val openPackages = mutableStateMapOf<String, Boolean>()
    var findText: String by mutableStateOf("")
    var findNote: String? by mutableStateOf(null)
    var targets: Boolean by mutableStateOf(false)
    var planning: Boolean by mutableStateOf(false)
        private set
    var planError: String? by mutableStateOf(null)
    /**
     * The tree line to bring into view once the tree has been laid out with it ([TreeItem.key]): set by [reveal], taken
     * by the tree. (Scrolling at once would scroll the list as it was: a LazyColumn keeps its place by the first line's
     * key, so the index of a line that is not there yet points at another.)
     */
    var scrollKey: String? by mutableStateOf(null)
    private var asked = 0

    /** Opens the picker on save [ref]'s file ([file]: the row it was picked from). */
    fun show(ref: CampRef, file: CampFile? = null) {
        val r = CampRef(ref.theater, ref.file)
        if (this.ref != r) this.file = file
        else if (file != null) this.file = file
        PlannerWindows.show(PlannerWindow.FLIGHT_PICKER, argOf(r))
    }

    /** "<theater>|<file>": what the picker is opened for ([PlannerWindows.arg]). Neither can hold a `|`. */
    fun argOf(r: CampRef) = r.theater + "|" + r.file

    fun refOf(arg: String?): CampRef? {
        if (arg == null || '|' !in arg) return null
        val t = arg.substringBefore('|').trim()
        val f = arg.substringAfter('|').trim()
        return if (t.isEmpty() || f.isEmpty()) null else CampRef(t, f)
    }

    /** Reads save [r]'s ATO, unless it is the one shown (or [again]). */
    suspend fun load(r: CampRef, again: Boolean = false) {
        if (!again && ref == r && ato != null && error == null) return
        val n = ++asked
        if (ref != r) {
            ref = r
            ato = null
            flights.clear(); openTeams.clear(); openPackages.clear()
            selected = null; seat = 0; findNote = null; targets = false; planError = null
            if (file?.name?.equals(r.file, true) != true) file = PlannerMissionState.file?.takeIf { it.name.equals(r.file, true) }
        }
        error = null
        loading = true
        val a = runCatching { MissionLink.campaignAto(r.theater, r.file) }.getOrElse { PcAnswer.unreachable(it.message ?: "error") }
        if (n != asked) return
        loading = false
        val v = a.value
        if (v == null) { error = a.error ?: "The PC's answer could not be read."; return }
        ato = v
        if (again) flights.clear()
        // open on the flight already planned from this save, else the briefed one, else the player's
        val planned = PlannerMissionState.ref?.takeIf { PlannerMissionState.fromSave && it.theater == r.theater && it.file.equals(r.file, true) }
        val rows = v.packages.flatMap { it.flights }
        val start = rows.firstOrNull { it.id == planned?.flight } ?: rows.firstOrNull { it.briefed } ?: rows.firstOrNull { it.player }
        if (start != null && (selected == null || rows.none { it.id == selected })) {
            selected = start.id
            seat = if (start.id == planned?.flight) PlannerMissionState.seat else 0
        }
        selected?.let { loadFlight(it) }
    }

    /** Reads flight [id] of the save shown (once). */
    suspend fun loadFlight(id: String) {
        val r = ref ?: return
        if (flights[id]?.value != null) return
        val a = runCatching { MissionLink.campaignFlight(r.theater, r.file, id) }.getOrElse { PcAnswer.unreachable(it.message ?: "error") }
        if (ref == r) flights[id] = a
    }

    fun select(row: CampFlightRow) {
        if (selected != row.id) seat = 0
        selected = row.id
        planError = null
    }

    fun row(id: String?): CampFlightRow? = ato?.packages?.firstNotNullOfOrNull { p -> p.flights.firstOrNull { it.id == id } }
    fun packageOf(id: String?): CampPackage? = ato?.packages?.firstOrNull { p -> p.flights.any { it.id == id } }

    /** Whether team [t]'s branch is open: the pilot's choice, else open when it holds the flight picked. */
    fun teamOpen(t: CampTeam): Boolean = openTeams[t.n] ?: (row(selected)?.team == t.n)
    fun packageOpen(t: CampTeam, p: CampPackage): Boolean =
        openPackages["${t.n}/${p.id}"] ?: p.flights.any { it.id == selected && it.team == t.n }

    fun expandAll(on: Boolean) {
        val a = ato ?: return
        for (t in a.teams) openTeams[t.n] = on
        for (t in a.teams) for (p in a.packages) if (p.flights.any { it.team == t.n }) openPackages["${t.n}/${p.id}"] = on
        findNote = null
    }

    /** **Find package**: opens the package whose number was typed, under each team with a flight in it, and shows it. */
    fun find() {
        val a = ato ?: return
        val want = findText.trim().trimStart('#')
        if (want.isEmpty()) { findNote = "Type a package number first."; return }
        val hits = a.packages.filter { it.number.toString() == want }.ifEmpty { a.packages.filter { it.number.toString().contains(want) } }
        if (hits.isEmpty()) { findNote = "There is no package $want in ${a.file}."; return }
        findNote = if (hits.size > 1) "${hits.size} packages match $want: ${hits.take(6).joinToString { it.number.toString() }}${if (hits.size > 6) "…" else ""}." else null
        reveal(hits[0])
    }

    /** Opens package [p] (and its teams) in the tree and brings it into view. */
    fun reveal(p: CampPackage) {
        val a = ato ?: return
        targets = false
        for (t in a.teams) if (p.flights.any { it.team == t.n }) { openTeams[t.n] = true; openPackages["${t.n}/${p.id}"] = true }
        scrollKey = treeItems(a).firstOrNull { it is TreeItem.Pkg && it.pkg.id == p.id }?.key
    }

    /**
     * Plans the flight picked: reads it if it has not been read, asks WDP's Precision STPT question where WDP asks it
     * (`cntDataCard.SelectNewFlight`: [PlannerMissionState.asksPrecision]), then hands the flight to the Planner with the
     * answer ([PlannerMissionState.precision]) and closes the window. No is the default button, as the question itself
     * says (WDP's own box has Yes as its default); closing the question with its × or Escape is No too, as a Yes/No box
     * in Windows cannot be closed without an answer. A flight nothing is asked about is planned as WDP's No.
     */
    suspend fun plan() {
        val r = ref ?: return
        val id = selected ?: return
        planning = true
        planError = null
        loadFlight(id)
        val a = flights[id]
        val f = a?.value
        planning = false
        if (f == null) { planError = a?.error ?: "The flight could not be read."; return }
        val s = seat.coerceIn(0, (f.row.count - 1).coerceIn(0, 3))
        val row = file?.takeIf { it.name.equals(r.file, true) }
        val kind = kindOf(r, f)
        fun go(precision: Boolean) {
            PlannerMissionState.plan(r.theater, CampRef(r.theater, r.file, id), f, s, row, precision)
            PlannerWindows.close()
        }
        if (!PlannerMissionState.asksPrecision(kind, f)) { go(false); return }
        WdpDialogs.message(
            PlannerMissionState.PRECISION_TITLE,
            PlannerMissionState.precisionQuestion(kind == CampKind.CAMPAIGN, f.row.callsign, savePath(r)),
            listOf(PlannerMissionState.YES, PlannerMissionState.NO), default = 1,
        ) { answer -> go(answer == PlannerMissionState.YES) }
    }

    /** Save [r]'s kind ([CampKind]): as the Open mission list said, else as the PC read it with [f], else by its extension. */
    fun kindOf(r: CampRef, f: CampFlight?): String? =
        file?.takeIf { it.name.equals(r.file, true) }?.kind?.takeIf { it.isNotEmpty() }
            ?: f?.kind?.takeIf { it.isNotEmpty() } ?: PlannerMissionState.kindOf(r.file)

    /**
     * Save [r]'s full path on the BMS PC, which WDP's question ends with: the BMS folder, the theater's campaign folder
     * as the Open mission list gave it, and the file. Only the file's name when the folders are not known here.
     */
    fun savePath(r: CampRef): String {
        val folder = CampaignBrowser.files?.theaters?.firstOrNull { it.name == r.theater }?.folder?.trim()?.trimEnd('\\', '/')
        if (folder.isNullOrEmpty()) return r.file
        if (':' in folder || folder.startsWith("\\\\")) return folder + "\\" + r.file
        val base = MissionLink.info.value?.bms?.baseDir?.trim()?.trimEnd('\\', '/')
        return if (base.isNullOrEmpty()) r.file else base + "\\" + folder.trimStart('\\', '/') + "\\" + r.file
    }
}

/** One line of the flight tree. */
internal sealed class TreeItem(val key: String) {
    class Team(val team: CampTeam, val flights: Int, val open: Boolean) : TreeItem("team/${team.n}")
    class Pkg(val team: CampTeam, val pkg: CampPackage, val owner: CampTeam?, val flights: Int, val open: Boolean) : TreeItem("pkg/${team.n}/${pkg.id}")
    class Flight(val team: CampTeam, val pkg: CampPackage, val row: CampFlightRow) : TreeItem("flt/${team.n}/${row.id}")
}

/** The tree as it is open now: team, then each package with a flight of that team, then that team's flights in it. */
internal fun treeItems(a: CampAto): List<TreeItem> {
    val p = FlightPicker
    val out = ArrayList<TreeItem>()
    for (t in a.teams) {
        val pkgs = a.packages.filter { pk -> pk.flights.any { it.team == t.n } }
        if (pkgs.isEmpty()) continue
        val open = p.teamOpen(t)
        out += TreeItem.Team(t, pkgs.sumOf { pk -> pk.flights.count { it.team == t.n } }, open)
        if (!open) continue
        for (pk in pkgs) {
            val mine = pk.flights.filter { it.team == t.n }
            val pOpen = p.packageOpen(t, pk)
            out += TreeItem.Pkg(t, pk, a.teams.firstOrNull { it.n == pk.owner }?.takeIf { it.n != t.n }, mine.size, pOpen)
            if (pOpen) for (f in mine) out += TreeItem.Flight(t, pk, f)
        }
    }
    return out
}

// ---------------------------------------------------------------- parts

@Composable
private fun FlightTree(a: CampAto, list: LazyListState, modifier: Modifier) {
    val items = treeItems(a)
    // the first time a save is shown: bring the flight picked into view
    val sel = FlightPicker.selected
    LaunchedEffect(a) {
        if (FlightPicker.scrollKey != null) return@LaunchedEffect
        val i = items.indexOfFirst { it is TreeItem.Flight && it.row.id == sel }
        if (i > 0) runCatching { list.scrollToItem((i - 2).coerceAtLeast(0)) }
    }
    // a package Find package or the ATO Target List asked for, once the tree holds it
    val want = FlightPicker.scrollKey
    LaunchedEffect(want, items.size) {
        if (want == null) return@LaunchedEffect
        val i = items.indexOfFirst { it.key == want }
        if (i >= 0) { runCatching { list.scrollToItem(i) }; FlightPicker.scrollKey = null }
    }
    if (items.isEmpty()) {
        Text("This save has no flights to plan.", color = Hud.TextDim, fontSize = 13.sp, modifier = modifier)
        return
    }
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        for (it in items) item(it.key) {
            when (it) {
                is TreeItem.Team -> TeamLine(it)
                is TreeItem.Pkg -> PackageLine(it)
                is TreeItem.Flight -> FlightLine(it)
            }
        }
    }
}

@Composable
private fun TeamLine(t: TreeItem.Team) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp).fingerHeight(40.dp).background(Hud.Surface2)
            .clickable { FlightPicker.openTeams[t.team.n] = !t.open }
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .plannerProbe("FlightPicker/Team/${t.team.n}")
            .wdpTip(
                "planner/FlightPicker/Team/${t.team.n}",
                "The packages with a flight of ${t.team.name}" + (if (t.team.allied) ", on your side" else "") + ". Press to show or hide them.",
                touch = true,
            ),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (t.open) "▾" else "▸", color = Hud.TextDim, fontSize = 13.sp, modifier = Modifier.width(12.dp))
        Text(t.team.name, color = Hud.Text, fontSize = fingerSp(13f), fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (t.team.allied) PlannerTag("your side", Hud.Green)
        Spacer(Modifier.weight(1f))
        Text(countWord(t.flights, "flight"), color = Hud.TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun PackageLine(p: TreeItem.Pkg) {
    val pk = p.pkg
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp).fingerHeight(40.dp)
            .drawBehind { drawLine(Hud.Outline.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1f) }
            .clickable { FlightPicker.openPackages["${p.team.n}/${pk.id}"] = !p.open }
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .plannerProbe("FlightPicker/Package/${pk.number}")
            .wdpTip(
                "planner/FlightPicker/Package/${p.team.n}/${pk.id}",
                "Package ${pk.number}: its mission, take-off and target. Press to show or hide its flights of ${p.team.name}.",
                touch = true,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (p.open) "▾" else "▸", color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.width(12.dp))
        Text(
            listOfNotNull(
                "Package ${pk.number}", pk.mission, pk.takeoff?.let { "T/O " + campTime(it).take(5) }, pk.target?.let { "Target: $it" },
            ).joinToString(" · ") + (p.owner?.let { " (package of ${it.name})" } ?: ""),
            color = Hud.Text, fontSize = fingerSp(12.5f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (!p.open) Text("${p.flights}", color = Hud.TextFaint, fontSize = 11.sp)
    }
}

@Composable
private fun FlightLine(f: TreeItem.Flight) {
    val r = f.row
    val on = FlightPicker.selected == r.id
    Column(
        Modifier.fillMaxWidth().padding(start = 28.dp).fingerHeight(44.dp)
            .background(if (on) Hud.Amber.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { FlightPicker.select(r) }
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .alpha(if (r.f16) 1f else 0.55f)
            .plannerProbe("FlightPicker/Flight/${r.id}")
            .wdpTip(
                "planner/FlightPicker/Flight/${r.id}",
                "Picks ${r.callsign} to plan: pick your seat below, then Plan this flight." +
                    (if (r.f16) "" else " Not an F-16: the attack pages and the DTC are for the F-16."),
                touch = true,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (on) "●" else "○", color = if (on) Hud.Amber else Hud.TextFaint, fontSize = 11.sp)
            Text(
                "${r.mission}  ${r.callsign}  ${r.count}× ${r.aircraft}" + (r.squadron?.let { "  “$it”" } ?: ""),
                color = if (on) Hud.Amber else Hud.Text, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (r.briefed) PlannerTag("briefed", Hud.Green)
            if (r.player) PlannerTag("player", Hud.Cyan)
        }
        Text(
            listOfNotNull(r.base, f.team.name, r.takeoff.takeIf { it > 0 }?.let { "T/O " + campTime(it).take(5) }, r.target?.let { "→ $it" })
                .joinToString(" · "),
            color = Hud.TextDim, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 17.dp),
        )
    }
}

/** **ATO Target List**: the side's packages and what each flight is tasked against; a package opens it in the tree. */
@Composable
private fun TargetList(a: CampAto, modifier: Modifier, onPackage: (CampPackage) -> Unit) {
    val side = a.teams.filter { it.allied }.map { it.n }.toSet()
    val pkgs = a.packages.filter { p -> side.isEmpty() || p.flights.any { it.team in side } || p.owner in side }
    LazyColumn(modifier.plannerProbe("FlightPicker/Targets"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item("head") {
            Text(
                if (side.isEmpty()) "Every package's targets" else "Your side's packages and their targets (" + a.teams.filter { it.allied }.joinToString { it.name } + ")",
                color = Hud.TextFaint, fontSize = 11.sp,
            )
        }
        for (p in pkgs) item(p.id) {
            Column(
                Modifier.fillMaxWidth().fingerHeight().clip(RoundedCornerShape(6.dp)).clickable { onPackage(p) }.padding(horizontal = 6.dp, vertical = 4.dp)
                    .plannerProbe("FlightPicker/Target/${p.number}"),
            ) {
                Text(
                    listOfNotNull("Package ${p.number}", p.mission, p.takeoff?.let { "T/O " + campTime(it).take(5) }).joinToString(" · ") +
                        "  →  " + (p.target ?: "no target"),
                    color = Hud.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                for (f in p.flights.filter { side.isEmpty() || it.team in side }) Text(
                    "${f.callsign} (${f.mission})  →  ${f.target ?: "no target"}",
                    color = Hud.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun PackageField(modifier: Modifier, onGo: () -> Unit) {
    val p = FlightPicker
    Row(
        modifier.width(104.dp).height(toolHeight).clip(RoundedCornerShape(6.dp)).background(Hud.Surface2).border(1.dp, Hud.Outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Pkg ", color = Hud.TextFaint, fontSize = fingerSp(12f))
        BasicTextField(
            p.findText, { v -> p.findText = v.filter { it.isDigit() }.take(6); p.findNote = null },
            singleLine = true,
            textStyle = TextStyle(color = Hud.Text, fontSize = fingerSp(13f)),
            cursorBrush = SolidColor(Hud.Amber),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onGo() }),
            modifier = Modifier.weight(1f).plannerProbe("FlightPicker/Package"),
        )
    }
}

/** The flight picked: what it is, its seat, where its steerpoints will come from, the PC's notes, and the buttons. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SelectedFlight(a: CampAto, ref: CampRef, modifier: Modifier, fill: Boolean) {
    val p = FlightPicker
    val scope = rememberCoroutineScope()
    val row = p.row(p.selected)
    val pkg = p.packageOf(p.selected)
    LaunchedEffect(p.selected) { p.selected?.let { p.loadFlight(it) } }
    val answer = p.selected?.let { p.flights[it] }
    val flight = answer?.value
    val data by MissionLink.bmsFiles.collectAsState()
    // what the pages will be given: the save's flight with the cartridge, laid out below for either answer
    val info by MissionLink.info.collectAsState()
    val mission = remember(data, flight, info?.bms?.theater) {
        flight?.let { plannerMission(PlannerMissionState.missionData(data, ref.theater, info?.bms?.theater), null, it) }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    // what is picked, flat on the window's ground (no card), scrolling on its own when it is long
    Column(
        Modifier.weight(1f, fill = fill).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (row == null) {
            Text("Pick your flight in the list.", color = Hud.TextDim, fontSize = 13.sp)
        } else {
            Text(
                listOfNotNull("${row.callsign}", row.mission, "${row.count}× ${row.aircraft}", row.base).joinToString(" · "),
                color = Hud.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.plannerProbe("FlightPicker/Selected"),
            )
            Text(
                listOfNotNull(
                    pkg?.let { "package ${it.number}" }, "flight ${row.number}",
                    row.takeoff.takeIf { it > 0 }?.let { "T/O " + campTime(it) }, row.tot.takeIf { it > 0 }?.let { "TOT " + campTime(it) },
                    if (row.briefed) "the flight BMS briefed" else null,
                ).joinToString(" · "),
                color = Hud.TextDim, fontSize = 12.sp,
            )
            if (!row.f16) Text("${row.aircraft}: the attack pages and the DTC are for the F-16. The card and the briefing still plan it.", color = Hud.Amber, fontSize = 12.sp, lineHeight = 15.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Seat", color = Hud.TextFaint, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
                PlannerMissionState.SEATS.forEachIndexed { i, s ->
                    PlannerChip(s, on = p.seat == i, probe = "FlightPicker/Seat/$s", enabled = i < row.count.coerceAtLeast(1)) { p.seat = i }
                }
            }
            when {
                answer == null -> Text("Reading ${row.callsign}'s route…", color = Hud.TextDim, fontSize = 12.sp)
                flight == null -> Text(answer.error ?: "The flight could not be read.", color = Hud.Red, fontSize = 12.sp, lineHeight = 15.sp)
                else -> {
                    val m = mission
                    // what each answer to the Precision STPT question will give, before the press that asks it
                    val no = m?.copy(precision = false)
                    val yes = m?.copy(precision = true)
                    fun counts(w: WdpMission?) = w?.sourceCounts?.ifEmpty { null } ?: "nothing placed"
                    val yesEmpty = yes?.emptyInDtc.orEmpty()
                    val yesLine = counts(yes) + (if (yesEmpty.isEmpty()) "" else " (${stptRange(yesEmpty)} empty in your DTC)")
                    val kind = p.kindOf(ref, flight)
                    val asks = PlannerMissionState.asksPrecision(kind, flight)
                    // where the attack pages' TGT STPT will be set: it can hang on the answer (a cartridge target counts
                    // only with Yes), and a flight with no strike steerpoint and no target sets none
                    fun opensOn(w: WdpMission?) = w?.defaultStpt?.let { d ->
                        "STPT $d" + if (d == w.strikeSteerpoints.firstOrNull()) " (the first strike steerpoint)" else ""
                    }
                    val opens = when {
                        m == null -> null
                        !asks || no?.defaultStpt == yes?.defaultStpt -> opensOn(no)?.let { "the attack pages open on $it" }
                            ?: "no strike steerpoint or target: TGT STPT is yours to set on the attack pages"
                        else -> listOf("No" to no, "Yes" to yes).joinToString(", ") { (said, w) ->
                            opensOn(w)?.let { "with $said the attack pages open on $it" } ?: "with $said no TGT STPT is set from the flight"
                        }
                    }
                    val question = if (asks) {
                        "Plan this flight asks whether you saved Precision STPT in the DTC for this flight. No (the default): ${counts(no)}. Yes: $yesLine."
                    } else {
                        "No Precision STPT question: there is no mission file beside this ${if (kind == CampKind.TRAINING) "training mission" else "TE"}, so ${counts(no)}."
                    }
                    val line = listOfNotNull(
                        "${flight.route.size} waypoints",
                        opens,
                    ).joinToString(" · ") + "\n" + question
                    Text(line, color = Hud.Text, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.plannerProbe("FlightPicker/Source"))
                    // the stores hardpoint by hardpoint, counted by name ("4× AIM-120C AMRAAM")
                    val stores = flight.loadouts.firstOrNull()?.stores.orEmpty().filter { it.name.isNotBlank() }
                        .groupBy { it.name }.map { (name, s) -> name to s.sumOf { it.qty.coerceAtLeast(1) } }
                    if (stores.isNotEmpty()) Text(
                        "Loadout" + (if (flight.loadouts.size > 1) " (lead)" else "") + ": " + stores.joinToString { (n, q) -> (if (q > 1) "${q}× " else "") + n },
                        color = Hud.TextDim, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val notes = flight?.notes ?: a.notes
        for ((i, n) in notes.withIndex()) NoteChip(n, "FlightPicker/Note/$i")
    }
    p.planError?.let { Text(it, color = Hud.Red, fontSize = 12.sp, lineHeight = 15.sp) }
    // the window's buttons, at the foot of the panel (the × in the title bar is its Cancel)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlannerButton("‹ Files", probe = "FlightPicker/Files") { PlannerWindows.show(PlannerWindow.OPEN_MISSION) }
        Spacer(Modifier.weight(1f))
        PlannerButton(
            if (p.planning) "Reading…" else "Plan this flight", probe = "FlightPicker/Plan", primary = true,
            enabled = row != null && !p.planning && answer?.let { it.value != null } != false,
        ) { scope.launch { p.plan() } }
    }
    }
}

/** One of the PC's sentences about the save or the flight, amber, two lines until tapped. */
@Composable
private fun NoteChip(text: String, probe: String) {
    var full by remember(text) { mutableStateOf(false) }
    // one thin amber line (a tap shows the whole sentence), not a card
    Text(
        "⚠ $text", color = Hud.Amber, fontSize = fingerSp(11.5f), lineHeight = 15.sp,
        maxLines = if (full) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable { full = !full }.padding(vertical = 2.dp).plannerProbe(probe),
    )
}
