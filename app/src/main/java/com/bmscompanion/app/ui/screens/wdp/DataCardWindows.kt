package com.bmscompanion.app.ui.screens.wdp

import com.bmscompanion.app.data.mission.ownFlight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.ChartRef
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.airfield.AfRoute
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.OrdnanceAircraft
import com.bmscompanion.app.data.wdp.DataCardPlan
import com.bmscompanion.app.data.wdp.WdpValues
import com.bmscompanion.app.ui.components.AirfieldChart
import com.bmscompanion.app.ui.components.ChartInks
import kotlinx.coroutines.launch

/**
 * The DataCard's child windows — WDP's own `fcls*` forms, each drawn from its layout by [WdpDialogs] and given the
 * small state and handlers below. What each window is for is WDP's; how it gets its answer is the app's (the
 * briefing, the airport database, the app's settings), because the files WDP reads for them — `Names.ini`,
 * `Formations.ini`, its chart folders — are not on a phone.
 *
 * Every handler is guarded: anything that goes wrong is said in a message box with the reason, and the window stays
 * as far as it got, as WDP's unhandled-exception box lets the program carry on.
 */
internal open class CardWindow(val form: String, val title: String) : WdpWiring {
    /** bumped on every change: [values] reads it, so a change re-composes the window */
    protected var version by mutableIntStateOf(0)
    /** the window's values, as a page's are: text by control name, `"hidden"`, `"<name>.items"`, … */
    protected val v = LinkedHashMap<String, String>()
    private var dialog: WdpDialog? = null

    override fun values(hidden: List<String>): WdpValues {
        @Suppress("UNUSED_VARIABLE") val r = version   // read so a change re-composes the window
        val m = LinkedHashMap<String, String>()
        for (h in hidden) m[h] = "hidden"
        m.putAll(v)
        return WdpValues(m)
    }

    override fun onValue(name: String, value: String) = guard { value(name, value) }
    override fun onClick(name: String) = guard { click(name) }

    /** A typed value or a pick; by default the control just shows it, as a text box or a combo does. */
    protected open fun value(name: String, value: String) { if (!name.endsWith(".leave")) v[name] = value }

    protected open fun click(name: String) {}

    private fun guard(block: () -> Unit) {
        try { block() } catch (e: Exception) {
            WdpDialogs.message(title, "This did not work: " + (e.message ?: e::class.simpleName ?: "an error") + ".")
        }
        version++
    }

    fun open(): CardWindow { val d = WdpDialog(form, title, this); dialog = d; WdpDialogs.show(d); return this }

    fun close() { dialog?.let { WdpDialogs.close(it) } }

    protected fun checked(name: String) = v[name] == "checked"
    protected fun toggle(name: String) { v[name] = if (checked(name)) "unchecked" else "checked" }
}

/** `fclsMilCodes`: the military colour-code table, which is the window's own picture. */
internal class MilCodesWindow : CardWindow("fclsMilCodes", "Military colour codes") {
    override fun click(name: String) { if (name == "btnClose") close() }
}


/** `fclsPackageNr`: the mission name and package number a DataCard is saved under. OK demands both, as WDP's does. */
internal class PackageNrWindow(mission: String, pkg: String, title: String = "Save DataCard", private val onOk: (String, String) -> Unit) :
    CardWindow("fclsPackageNr", title) {
    init { v["txtMissionName"] = mission; v["txtPackageNr"] = pkg }

    override fun click(name: String) {
        when (name) {
            "btnOK" -> when {
                v["txtMissionName"].isNullOrBlank() -> WdpDialogs.message("Warning", "You have to enter a Mission Name")
                v["txtPackageNr"].isNullOrBlank() -> WdpDialogs.message("Warning", "You have to enter a Package Number")
                else -> { close(); onOk(v["txtMissionName"]!!.trim(), v["txtPackageNr"]!!.trim()) }
            }
            "btnCancel" -> close()
        }
    }
}

/**
 * WDP's file dialogs for the card's copies — Load DataCard, Save/Load Codewords, Save/Load Package Timing — as its
 * own `fclsBrowseTo` window: the name in the box, **Browse** stepping through the copies kept on this device, OK to
 * save or load that one. The copies live in the app's settings ([CardCopies]), so they are there on every platform.
 */
internal class CopyWindow(
    private val kind: CardCopies.Kind,
    private val saving: Boolean,
    suggested: String,
    private val onOk: (String) -> Unit,
) : CardWindow("fclsBrowseTo", (if (saving) "Save " else "Load ") + kind.label) {
    private val names = CardCopies.names(kind)
    private var at = -1

    init {
        v["txtfName"] = if (saving) suggested else names.firstOrNull() ?: ""
        v["lblInfo"] = info()
    }

    private fun info(): String = when {
        names.isEmpty() && saving -> "Save the ${kind.label.lowercase()} on this device under this name."
        names.isEmpty() -> "No ${kind.label} has been saved on this device yet."
        else -> (if (saving) "Save under this name (Browse: overwrite a copy). " else "Load a copy (Browse to step through them). ") +
            "Kept here: " + names.joinToString(", ")
    }

    override fun click(name: String) {
        when (name) {
            "btnBrowse" -> {
                if (names.isEmpty()) { WdpDialogs.message(title, "No ${kind.label} has been saved on this device yet."); return }
                at = (at + 1) % names.size
                v["txtfName"] = names[at]
                v["lblInfo"] = "Copy ${at + 1} of ${names.size}: ${names[at]}. " + info()
            }
            "btnOk" -> {
                val n = v["txtfName"]?.trim().orEmpty()
                if (n.isEmpty()) { WdpDialogs.message(title, "Enter a name for the copy."); return }
                if (!saving && n !in names) { WdpDialogs.message(title, "There is no ${kind.label} called \"$n\" on this device."); return }
                close()
                onOk(n)
            }
            "btnCancel" -> close()
        }
    }
}

/**
 * `fclsSelection`: the package's flights, to plan another one. WDP lists every package of the side from the
 * campaign; the app has the package the briefing prints, so the list is that package and its flights — tapping a
 * flight picks it, as WDP's tree does. **Find** looks the package number up, **Expand All** / **Collapse All** open
 * and close the package, **ATO Target List** turns the Planner to its ATO Targets page ([AtoTargetList.open]).
 */
internal class SelectionWindow(
    private val briefing: Briefing?,
    private val targets: List<String>,
    private val onPick: (Int) -> Unit,
) : CardWindow("fclsSelection", "Select a flight") {
    private var expanded = true

    init {
        v["txtPackage"] = briefing?.overview?.packageId ?: ""
        v["lblCurrTime"] = briefing?.ownFlight()?.takeoff?.let { "T/O " + it.removeSuffix("z") } ?: ""
        fill()
    }

    private fun flights() = briefing?.`package`.orEmpty().take(5)

    private fun fill() {
        val b = briefing
        val rows = ArrayList<String>()
        rows += (if (expanded) "[-] " else "[+] ") + "Package " + (b?.overview?.packageId ?: "?") +
            (b?.overview?.packageType?.let { "  $it" } ?: "")
        if (expanded) for (f in flights()) {
            rows += "      " + listOfNotNull(f.role ?: f.task, f.callsign, (f.count ?: 1).toString() + "x " + (f.aircraft ?: ""), f.takeoff?.let { "T/O " + it.removeSuffix("z") })
                .joinToString("  ") + if (briefing != null && f === briefing.ownFlight()) "   (your flight)" else ""
        }
        v["tvwSelection.items"] = rows.joinToString("\n")
    }

    override fun click(name: String) {
        when {
            name == "btnExpand" -> { expanded = true; fill(); v["tvwSelection.selected"] = "0" }
            name == "btnCollapseAll" -> { expanded = false; fill(); v.remove("tvwSelection.selected") }
            // txtPackage_KeyDown: Return finds the package, as the Find button does
            name == "btnFind" || name == "txtPackage:enter" -> {
                val t = v["txtPackage"]?.trim().orEmpty()
                if (t.toLongOrNull() == null) { WdpDialogs.message(title, "This is not a correct Package number, try again"); return }
                if (t == briefing?.overview?.packageId?.trim()) { expanded = true; fill(); v["tvwSelection.selected"] = "0" }
                else WdpDialogs.message(title, "Package: $t is not in this briefing.\nThe briefing BMS printed is for package ${briefing?.overview?.packageId ?: "?"}.")
            }
            // WDP's fclsAtoTargetList over this window: the Planner's ATO Targets page, once this window is closed
            name == "btnAtoTargetList" -> { close(); AtoTargetList.open() }
            name.startsWith("tvwSelection:") -> {
                val i = name.substringAfterLast(':').toIntOrNull() ?: return
                v["tvwSelection.selected"] = i.toString()
                if (i == 0) { if (name.contains(":open:")) { expanded = !expanded; fill() }; return }
                val k = i - 1
                if (k in flights().indices) { close(); onPick(k + 1) }
            }
        }
    }
}

/**
 * `fclsChart`: an airport's charts, as the app has them. WDP shows the airport ground chart (AGC) and a parking chart
 * (APC) per runway from its own `Charts` folder; the app draws both from BMS's own airfield data, as its Taxi page
 * and its Airfields page do — the field's diagram, and the ramp as BMS numbers it for each runway end — and it also
 * has the airport's instrument charts (the Airfields page's). So the window opens on the airport diagram; **AGC**
 * and **APC <runway>** are the drawn charts, and the line at the foot names the chart on show and steps on through
 * the parking charts to every instrument chart (a tap each). The diagram zooms and pans (the wheel, a pinch, a drag).
 *
 * [airfieldSet] is the theater's ground-chart set ([com.bmscompanion.app.data.Theater.airfieldSet]); without it the
 * field is looked for in the theater the app is set to and then in every theater, by its id and name. Where BMS has
 * no ground chart for the field the buttons are the first five instrument charts, as before.
 */
internal class ChartWindow(private val airport: Airport, private val charts: List<ChartRef>, private val airfieldSet: String? = null) :
    CardWindow("fclsChart", airport.fullName.ifEmpty { airport.name }.let { n -> n + (airport.icao?.takeIf { it !in n }?.let { " ($it)" } ?: "") } + " — charts"), WdpControlContent {
    /** BMS's own data for the field (its ground chart), once read; null while reading and where there is none */
    private var diagram by mutableStateOf<Airfield?>(null)
    private var looked = false
    /** what is on show: [GROUND], a parking chart (1..4, the route's place in [routes]), or an instrument chart ([FIRST_CHART] + its index) */
    private var at by mutableIntStateOf(-1)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    init {
        for (b in BUTTONS) v[b] = "hidden"
        v["cntChart.lines"] = airport.fullName.ifEmpty { airport.name } + "\n\nDrawing the airport diagram…"
        v["lblFile"] = ""
        scope.launch {
            val f = runCatching { findField() }.getOrNull()
            diagram = f
            looked = true
            if (f != null) show(GROUND) else if (charts.isNotEmpty()) chartList() else {
                v["cntChart.lines"] = airport.fullName.ifEmpty { airport.name } + "\n\n" +
                    "Falcon BMS's data has no ground chart for this field, and it has no instrument charts."
            }
            version++
        }
    }

    /** The field in [airfieldSet], else in the theater the app is set to, else in any theater that has it by this name. */
    private suspend fun findField(): Airfield? {
        val theaters = runCatching { Repo.index().theaters }.getOrNull().orEmpty()
        val sets = buildList {
            airfieldSet?.let { add(it) }
            theaters.firstOrNull { it.id == Repo.selectedTheater.value }?.airfieldSet?.let { add(it) }
            theaters.mapNotNullTo(this) { it.airfieldSet }
        }.distinct()
        for (s in sets) {
            val f = runCatching { Repo.airfield(s, airport.id) }.getOrNull() ?: continue
            // an id is the campaign's, and two theaters may use the same one for different fields: the name decides
            if (s == airfieldSet || f.name.equals(airport.name, true) || (f.icao != null && f.icao.equals(airport.icao, true))) return f
        }
        return null
    }

    /**
     * The parking charts there are: BMS numbers the ramp separately for each runway end, up to four as WDP's buttons,
     * in the order of the airport's runway ends as WDP's airport table has them (its runway[0..3].ParkingChart).
     */
    private fun routes(): List<AfRoute> {
        val all = diagram?.takeIf { it.ship == null }?.routes.orEmpty().filter { it.parking.isNotEmpty() }
        val ends = airport.runways.flatMap { r -> r.ends.map { it.designator } }
        val ordered = ends.mapNotNull { d -> all.firstOrNull { it.designator.equals(d, true) } }
        return (ordered + all.filter { it !in ordered }).distinct().take(4)
    }

    /** Every chart, in the order the foot line steps through them. */
    private fun order(): List<Int> =
        (if (diagram != null) listOf(GROUND) + routes().indices.map { it + 1 } else emptyList()) + charts.indices.map { FIRST_CHART + it }

    private fun name(i: Int): String = when {
        i == GROUND -> if (diagram?.ship != null) "Deck" else "Airport diagram"
        i < FIRST_CHART -> "Parking, RWY " + (routes().getOrNull(i - 1)?.designator ?: "")
        else -> charts.getOrNull(i - FIRST_CHART)?.title ?: ""
    }

    /** Where BMS has no ground chart: the window opens on the list of instrument charts, the buttons the first five. */
    private fun chartList() {
        BUTTONS.forEachIndexed { k, b -> v[b] = charts.getOrNull(k)?.let { short(it.title) } ?: "hidden" }
        v["cntChart.lines"] = (listOf(airport.fullName.ifEmpty { airport.name }, "") +
            charts.mapIndexed { k, c -> (k + 1).toString().padStart(3) + "  " + c.title }).joinToString("\n")
        v["lblFile"] = "${charts.size} charts  ▸"
    }

    private fun show(i: Int) {
        at = i
        v.remove("cntChart.lines")
        if (i >= FIRST_CHART) {
            val c = charts.getOrNull(i - FIRST_CHART) ?: return
            v["cntChart"] = c.pages.firstOrNull() ?: c.file
        } else v.remove("cntChart")
        if (diagram != null) {
            // as WDP's SetButtons: the button of the chart on show is hidden, the others stay
            v["btnAgc"] = if (i == GROUND) "hidden" else if (diagram?.ship != null) "Deck" else "AGC"
            val r = routes()
            APC.forEachIndexed { k, b -> v[b] = if (k + 1 != i && k < r.size) "APC " + r[k].designator else "hidden" }
        }
        val all = order()
        v["lblFile"] = "${all.indexOf(i) + 1}/${all.size}  ${name(i)}  ▸"
    }

    override fun controlContent(): Map<String, @Composable () -> Unit> = mapOf("cntChart" to {
        val f = diagram
        val i = at
        // on white, as WDP's charts are; turned to whatever angle fills the window's tall box (most fields lie across it)
        if (f != null && i in 0 until FIRST_CHART) AirfieldChart(
            field = f, route = if (i == GROUND) null else routes().getOrNull(i - 1), inks = ChartInks.day,
            modifier = Modifier.fillMaxSize(), fitRotation = true,
        )
    })

    override fun click(name: String) {
        when (name) {
            "btnClose" -> { close(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
            "lblFile" -> {
                if (!looked) return
                val all = order()
                if (all.isEmpty()) return
                show(all[(all.indexOf(at) + 1).mod(all.size)])
            }
            "btnAgc" -> if (diagram != null) show(GROUND) else show(FIRST_CHART)
            in APC -> {
                val k = APC.indexOf(name)
                if (diagram != null) show(k + 1) else show(FIRST_CHART + k + 1)
            }
        }
    }

    companion object {
        val BUTTONS = listOf("btnAgc", "btnApc1", "btnApc2", "btnApc3", "btnApc4")
        private val APC = listOf("btnApc1", "btnApc2", "btnApc3", "btnApc4")
        private const val GROUND = 0
        private const val FIRST_CHART = 10

        /** "09L · ILS or LOC/DME" → "09L ILS"; a title with no runway keeps its first word. */
        fun short(title: String): String {
            val parts = title.split(" · ")
            return if (parts.size >= 2) parts[0].trim() + " " + parts[1].trim().substringBefore(' ') else title.trim().substringBefore(' ').take(9)
        }
    }
}

/**
 * `fclsSelAPT`: pick another airfield for the departure, arrival or alternate row. WDP's two lists are the country
 * and its airports; the app's database has no countries, so the first list groups the theater's fields by kind
 * (airbases, airstrips, carriers …) instead, and the second is those fields by name.
 */
internal class SelAptWindow(private val airports: List<Airport>, current: String?, private val onSelect: (Airport?) -> Unit) :
    CardWindow("fclsSelAPT", "Select an airport") {
    private val kinds = listOf(ALL) + airports.map { it.type }.distinct().sorted()

    init {
        v["cboCountry.items"] = kinds.joinToString("\n")
        v["cboCountry"] = ALL
        list(ALL)
        airports.firstOrNull { it.name == current }?.let { v["cboAirports"] = it.name }
    }

    private fun shown(kind: String) = airports.filter { kind == ALL || it.type == kind }.sortedBy { it.name }

    private fun list(kind: String) {
        val names = shown(kind).map { it.name }
        v["cboAirports.items"] = names.joinToString("\n")
        v["cboAirports"] = names.firstOrNull() ?: ""
    }

    override fun value(name: String, value: String) {
        when (name) {
            "cboCountry" -> { v[name] = value; list(value) }
            "cboAirports" -> v[name] = value
            else -> super.value(name, value)
        }
    }

    override fun click(name: String) {
        when (name) {
            "btnSelect" -> { close(); onSelect(airports.firstOrNull { it.name == v["cboAirports"] }) }
            "btnCancel" -> close()
        }
    }

    companion object { const val ALL = "All airfields" }
}

/** `fclsFormation`: a steerpoint's formation, and "set all" for every steerpoint with one. */
internal class FormationWindow(current: String, private val onOk: (String, Boolean) -> Unit) : CardWindow("fclsFormation", "Formation") {
    init {
        // WDP reads Formations.ini; the names are BMS's own, which is what that file lists
        v["cboFormation.items"] = (listOf("") + (0..14).map { DataCardPlan.formationString(it) }.filter { it.isNotBlank() }.distinct()).joinToString("\n")
        v["cboFormation"] = current.trim()
        v["chbAllStpts"] = "unchecked"
    }

    override fun click(name: String) {
        when (name) {
            "chbAllStpts" -> toggle(name)
            "btnOK" -> { close(); onOk(v["cboFormation"] ?: "", checked("chbAllStpts")) }
            "btnCancel" -> close()
        }
    }
}

/**
 * `fclsNames`: the four pilots' names on the card. WDP offers the names in its `Names.ini`; the app offers the
 * briefing's own pilot roster and the names used on this device before, and remembers the ones picked.
 */
internal class NamesWindow(roster: List<String>, current: List<String>, private val onOk: (List<String>) -> Unit) :
    CardWindow("fclsNames", "Names") {
    init {
        val kept = runCatching { Repo.getString(KEY) }.getOrNull()?.split('\n').orEmpty()
        val names = (listOf("") + (roster + current + kept).map { it.trim() }.filter { it.isNotEmpty() }).distinct()
        BOXES.forEachIndexed { i, b ->
            v["$b.items"] = names.joinToString("\n")
            v[b] = current.getOrNull(i)?.trim() ?: ""
            // WDP's name boxes are DropDown combos and it reads their Text: a name not on the list is typed in
            v["$b.editable"] = "true"
        }
        v["Label1"] = "Names from the briefing's roster"
    }

    override fun click(name: String) {
        when (name) {
            "btnOK" -> {
                val picked = BOXES.map { v[it] ?: "" }
                val kept = runCatching { Repo.getString(KEY) }.getOrNull()?.split('\n').orEmpty()
                Repo.putString(KEY, (picked.filter { it.isNotBlank() } + kept).distinct().take(40).joinToString("\n"))
                close()
                onOk(picked)
            }
            "btnCancel" -> close()
        }
    }

    companion object {
        val BOXES = listOf("cboLead", "cboWing1", "cboElement", "cboWing4")
        private const val KEY = "wdp_card_names"
    }
}

/**
 * A steerpoint of the flight plan (a tap on its row: WDP's `ShowTarget`), in the airport schedule's tall window: what
 * the mission says of it at the top, and under it the steerpoint on the app's theater map — 40 nm around it, ringed,
 * with the route and the threat rings — drawn as the card's own map is ([WdpMapPicture]).
 */
internal class SteerpointWindow(
    title: String,
    lines: String,
    private val picture: com.bmscompanion.app.data.wdp.PopupPlan.MapPicture?,
    private val theater: com.bmscompanion.app.data.Theater?,
) : CardWindow("fclsAptSchedule", title), WdpControlContent {
    init { v["pnlSchedule.lines"] = lines; v["btnSaveJpg"] = "hidden" }

    override fun controlContent(): Map<String, @Composable () -> Unit> = if (picture == null) emptyMap() else mapOf("pnlSchedule" to {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().aspectRatio(1f).padding(androidx.compose.ui.unit.Dp(4f)),
            ) { WdpMapPicture(theater, picture) }
        }
    })

    override fun click(name: String) {
        if (name == "btnClose") close()
    }
}

// fclsAptSchedule (the Airport Schedule) is AptScheduleWindow, in AptSchedule.kt

/**
 * A rack a save's loadout leaves bare comes out of the campaign as a store whose name starts "Empty" (Hellas's SAT 001:
 * "2x Empty 2x AGM-65G"); BMS's printed briefing never lists one, and neither do the card's rows and Loadout window.
 */
internal val EMPTY_RACK = Regex("^\\s*empty\\b", RegexOption.IGNORE_CASE)

/**
 * `fclsLoadout`: what the flight carries. WDP reads the loadout, stations and weights out of the campaign and the
 * sim's data; the briefing prints each aircraft's stores, so the window lists those — per aircraft by its radio
 * button, filtered by the type list, with the stores summed by kind beside the picture. The weights are the app's
 * own data, as its Arsenal and aircraft pages show them: each store's weight and drag, the external tanks' fuel, and
 * the aircraft's empty weight, internal fuel and maximum; a double tap on a store says what the Arsenal knows of it.
 * The stations are not in the briefing and stay empty (the Performance page's Loadout is where stores are hung).
 */
internal class LoadoutWindow(private val aircraftType: String, private val jets: List<OrdnanceAircraft>, seat: Int) :
    CardWindow("fclsLoadout", "Loadout") {
    private var seatNow = seat.coerceIn(0, 3)
    private var filter = "Loadout"
    private var cleared = false
    /** the Arsenal's stores by their normalised name, and the aircraft's own data, once read */
    private var weapons: Map<String, com.bmscompanion.app.data.Weapon> = emptyMap()
    private var spec: com.bmscompanion.app.data.AircraftSpec? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    init {
        for (n in WEIGHTS) v[n] = ""
        // this window only shows the briefing's stores: its own tooltips where tips/fclsLoadout.json speaks of the
        // Performance page's Loadout, which hangs them (WdpTips; "" takes a tip away)
        for ((n, tip) in TIPS) v["$n.tip"] = tip
        for (k in 1..11) { v["lblAmount$k"] = ""; v["lblPylon$k"] = "" }
        v["lblAC"] = aircraftType
        v["cboType"] = filter
        fill()
        scope.launch {
            weapons = runCatching { Repo.weapons().associateBy { norm(it.name) } }.getOrNull().orEmpty()
            spec = runCatching { specOf(Repo.aircraft()) }.getOrNull()
            fill()
            version++
        }
    }

    /** The aircraft's own data: the type the briefing names ("2 F-16CM-40" is the F-16CM-40), by name or by its .dat file. */
    private fun specOf(all: List<com.bmscompanion.app.data.Aircraft>): com.bmscompanion.app.data.AircraftSpec? {
        val type = norm(aircraftType.trim().replace(Regex("^\\d+\\s+"), ""))
        if (type.isEmpty()) return null
        val a = all.firstOrNull { norm(it.name) == type }
            ?: all.firstOrNull { a -> a.variants.any { v -> v.spec.datFile?.let { norm(it) } == type } }
            ?: all.firstOrNull { type.startsWith(norm(it.name)) && norm(it.name).length >= 4 }
        return a?.variants?.firstOrNull()?.spec
    }

    /** The Arsenal's entry for a store the briefing names: by its whole name, else by its designation ("GBU-12"). */
    private fun weapon(store: String): com.bmscompanion.app.data.Weapon? =
        weapons[norm(store)] ?: norm(store.substringBefore(' ')).takeIf { it.length >= 3 }?.let { d ->
            weapons.entries.firstOrNull { it.key.startsWith(d) }?.value
        }

    private fun stores() = if (cleared) emptyList() else jets.getOrNull(seatNow)?.stores.orEmpty().filter { !EMPTY_RACK.containsMatchIn(it.name) }

    /** A store's kind: the Arsenal's category where it has the store, the name's otherwise. */
    private fun kind(store: String): String = when (weapon(store)?.category) {
        "AAM_IR", "AAM_RADAR" -> "A-A"
        "FUEL_TANK" -> "Fuel"
        "ECM_POD", "CM_POD" -> "ECM"
        "GUN" -> "Gun"
        "TARGETING_POD", "AVIONICS_POD", "RECON_POD" -> "Other"
        null -> kindOf(store)
        else -> "A-G"
    }

    /** The weights beside the picture, from the Arsenal and the aircraft's data; blank where the app does not know them. */
    private fun weights() {
        val all = stores()
        val known = all.mapNotNull { s -> weapon(s.name)?.let { s to it } }
        val load = known.sumOf { (s, w) -> s.qty * (w.weightLbs ?: 0.0) }
        val drag = known.sumOf { (s, w) -> s.qty * (w.drag ?: 0.0) }
        // an external tank's fuel: its gallons of JP-8 at 6.7 lb (the Arsenal's weight is the empty tank's)
        val ext = all.sumOf { s -> Regex("(\\d+)\\s*gal", RegexOption.IGNORE_CASE).find(s.name)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it * 6.7 * s.qty } ?: 0.0 }
        val sp = spec
        fun lb(d: Double?) = d?.let { kotlin.math.round(it).toInt().toString() } ?: ""
        v["lblLoadW"] = if (known.isEmpty() && all.isNotEmpty()) "" else lb(load)
        v["lblDragVal"] = if (known.isEmpty() && all.isNotEmpty()) "" else lb(drag)
        v["lblFuelExtW"] = lb(ext)
        v["lblEmptyW"] = lb(sp?.emptyWeightLbs)
        v["lblFuelIntW"] = lb(sp?.internalFuelLbs)
        v["lblMaxW"] = lb(sp?.maxWeightLbs)
        val fuel = sp?.internalFuelLbs?.plus(ext)
        v["lblFuelW"] = lb(fuel)
        val empty = sp?.emptyWeightLbs
        v["lblGrossW"] = if (empty != null && fuel != null) lb(empty + load + fuel) else ""
        v["lblFuelUsedW"] = ""
    }

    private fun fill() {
        val all = stores()
        val shown = when (filter) {
            "A-A" -> all.filter { kind(it.name) == "A-A" }
            "A-G" -> all.filter { kind(it.name) == "A-G" }
            "Other" -> all.filter { kind(it.name) !in listOf("A-A", "A-G") }
            else -> all
        }
        v["dgvLoadOut.rows"] = shown.joinToString("\n") { it.name + "\t" + it.qty + "\t\t\t\t\t\t\t\t\t\t\t\t\t" }
        fun sum(k: String) = all.filter { kind(it.name) == k }.joinToString(", ") { "${it.qty}x ${it.name}" }
        v["lblAA"] = "AA: " + sum("A-A")
        v["lblAG"] = "AG: " + sum("A-G")
        v["lblECM"] = "ECM: " + sum("ECM")
        v["lblFLL"] = "Fuel: " + sum("Fuel")
        v["lblAircraft"] = jets.getOrNull(seatNow)?.name?.ifBlank { null } ?: aircraftType
        // WDP hides the TGP and HTS boxes from BMS 4.34 on, where the pods are stores like any other (the list shows them)
        v["chbTgp"] = "hidden"; v["chbHTS"] = "hidden"
        SEATS.forEachIndexed { i, r ->
            v[r] = if (i == seatNow) "checked" else "unchecked"
            v["$r.enabled"] = (i < jets.size.coerceAtLeast(1)).toString()
        }
        weights()
    }

    /** What the Arsenal says of a store, for its double tap. */
    private fun facts(name: String, qty: Int): String {
        val w = weapon(name) ?: return "${qty}x $name (${kind(name)}).\nThe app's Arsenal has no entry by this name."
        val lines = ArrayList<String>()
        lines += "${qty}x ${w.name}: " + (com.bmscompanion.app.data.Labels.weaponCategory[w.category] ?: w.category)
        w.weightLbs?.takeIf { it > 0 }?.let { lines += "Weight ${it.toInt()} lb each" + if (qty > 1) ", ${(it * qty).toInt()} lb for $qty" else "" }
        w.drag?.takeIf { it > 0 }?.let { lines += "Drag index ${it.toInt()} each" }
        w.rangeKm?.takeIf { it > 0 }?.let { lines += "Range ${it.toInt()} km (${kotlin.math.round(it / 1.852).toInt()} nm)" }
        if (w.guidance.isNotEmpty()) lines += "Guidance: " + w.guidance.joinToString(", ")
        w.blastRadiusFt?.takeIf { it > 0 }?.let { lines += "Blast radius ${it.toInt()} ft" }
        lines += ""
        lines += "From the app's Arsenal (Reference, Arsenal: ${w.name})."
        return lines.joinToString("\n")
    }

    override fun value(name: String, value: String) {
        if (name == "cboType") { filter = value; v[name] = value; fill() } else super.value(name, value)
    }

    /** The grid's row [i] as shown, name and quantity. */
    private fun row(i: Int): List<String>? = v["dgvLoadOut.rows"]?.split('\n')?.getOrNull(i)?.split('\t')?.takeIf { it.firstOrNull()?.isNotBlank() == true }

    override fun click(name: String) {
        when (name) {
            in SEATS -> { val i = SEATS.indexOf(name); if (i < jets.size.coerceAtLeast(1)) { seatNow = i; fill() } }
            // what the pilot plans with, not what the jet carries: WDP's Clear All empties the list for the
            // performance figures, and so does this, until the window is opened again
            "btnClear" -> { cleared = true; fill() }
            "btnOK", "btnCancel" -> close()
            else -> if (name.startsWith("dgvLoadOut:")) {
                val i = name.substringAfterLast(':').toIntOrNull() ?: return
                val r = row(i)
                if (r == null) { WdpDialogs.message(title, "The briefing lists no stores for this aircraft."); return }
                v["dgvLoadOut.selected"] = i.toString()
                // a double tap is WDP's cell edit; here it says what the app's Arsenal knows of the store
                if (name.contains(":open:")) WdpDialogs.message(r[0], facts(r[0], r.getOrNull(1)?.toIntOrNull() ?: 1))
            }
        }
    }

    companion object {
        /** A store's kind as the window's type list and the card's Config rows sort it: A-A, A-G, ECM, Fuel, Gun or Other. */
        fun kindOf(store: String): String {
            val s = store.lowercase()
            return when {
                s.contains("aim-") || s.contains("r-7") || s.contains("r-2") || s.contains("r-3") || s.contains("r-6") || s.contains("magic") ||
                    s.contains("sidewinder") || s.contains("amraam") || s.contains("sparrow") || s.contains("python") || s.contains("derby") -> "A-A"
                s.contains("tank") || s.contains("gal") || s.contains("fuel") -> "Fuel"
                s.contains("alq") || s.contains("ecm") || s.contains("jammer") || s.contains("pod") && s.contains("ecm") -> "ECM"
                s.contains("mm ") || s.contains("m61") || s.contains("gun") -> "Gun"
                s.contains("litening") || s.contains("sniper") || s.contains("lantirn") || s.contains("aaq") || s.contains("asq") ||
                    s.contains("tgp") || s.contains("hts") || s.contains("acmi") || s.contains("travel") -> "Other"
                else -> "A-G"
            }
        }

        /**
         * A store as the card's narrow Config rows name it: "AIM-120C AMRAAM" is "AIM-120C", "Tank 370gal" is "370gal",
         * "AN/ALQ-184" is "ALQ-184" — the designation, as WDP's own card prints "120C5", "TK370", "AL184".
         */
        fun shortName(store: String): String {
            val s = store.trim().removePrefix("AN/")
            Regex("(\\d+)\\s*gal", RegexOption.IGNORE_CASE).find(s)?.let { return it.groupValues[1] + "gal" }
            return s.substringBefore(' ').ifEmpty { s }
        }

        val SEATS = listOf("rbnLead", "rbnWingman", "rbnElemLead", "rbnElemWingman")

        /**
         * The tooltips of this window where the form's own (tips/fclsLoadout.json, written for the Performance page's
         * Loadout, which hangs stores) would say something untrue of a window that only shows the briefing's.
         */
        private val TIPS: Map<String, String> = run {
            val seat = "as the briefing (or the save) lists them. This window only shows the loadout: the Performance page's Loadout is where stores are hung."
            val few = " Greyed when the flight has fewer aircraft."
            val shut = "Closes the window. Nothing here changes the loadout."
            val load = "What the stores weigh, lb, from the app's Arsenal; blank when it knows none of them."
            val drag = "The stores' drag index, from the app's Arsenal. The Performance page works its figures out from its own Loadout."
            val intFuel = "Internal fuel, lb: the jet's full tanks, from the app's aircraft data."
            val gross = "Empty weight + stores + fuel, lb; blank where the app does not know them all."
            mapOf(
                "lblAircraft" to "The jet of the seat shown, as the briefing (or the save) names it.",
                "lblAC" to "The flight's aircraft, how many and their type, from the briefing.",
                "rbnLead" to "Shows the lead's stores (#1), $seat",
                "rbnWingman" to "Shows the wingman's stores (#2).$few",
                "rbnElemLead" to "Shows the element lead's stores (#3).$few",
                "rbnElemWingman" to "Shows the element wingman's stores (#4).$few",
                "cboType" to "Which stores the list shows: Loadout or All (every one), A-A, A-G, or Other (pods, tanks, the gun).",
                "dgvLoadOut" to "The stores of the seat shown, $seat A double tap says what the app's Arsenal knows of one.",
                "btnClear" to "Empties the list shown here until the window is opened again. The loadout itself does not change.",
                "btnOK" to shut,
                "btnCancel" to shut,
                "picLoadout" to "The jet's hardpoints, as WDP draws them. Which station carries what is not in the briefing, so the stations stay empty here.",
                "lblAA" to "Air-to-air stores of the seat shown, with their counts.",
                "lblAG" to "Air-to-ground stores of the seat shown, with their counts.",
                "lblECM" to "Jamming (ECM) pods of the seat shown.",
                "lblFLL" to "External fuel tanks of the seat shown.",
                "lblLoad" to load, "lblLoadW" to load,
                "lblFuelInt" to intFuel, "lblFuelIntW" to intFuel,
                "lblFuelUsed" to "", "lblFuelUsedW" to "",
                "lblGross" to gross, "lblGrossW" to gross,
                "lblDrag" to drag, "lblDragVal" to drag,
            )
        }

        private val WEIGHTS = listOf("lblEmptyW", "lblLoadW", "lblFuelW", "lblGrossW", "lblDragVal", "lblMaxW", "lblFuelIntW", "lblFuelExtW", "lblFuelUsedW")
        /** A name as the Arsenal is matched by: lower case, letters and digits only ("AIM-120C AMRAAM" is "aim120camraam"). */
        private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
