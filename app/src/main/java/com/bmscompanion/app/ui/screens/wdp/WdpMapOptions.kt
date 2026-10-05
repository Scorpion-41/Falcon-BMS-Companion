package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.wdp.PlannerIntel
import com.bmscompanion.app.ui.components.MapLook
import com.bmscompanion.app.ui.components.drawUnitSymbol
import com.bmscompanion.app.ui.theme.Hud

/**
 * The Planner's **Map** page's options, laid out as WDP's MAP tab lays its panel out beside the map (map and grouped
 * options, in WDP's order: map, airfields, the flight plan, the package, SAMs and intel, the tools, the lines), in the
 * app's own look: hairline rules, faint uppercase headers that fold, dense rows, a sub-option indented under its parent
 * and shown only while the parent is on (WDP hides them until ticked).
 *
 * [WdpMapPrefs] keeps every switch per device (`wdpmap_…`), with WDP's start state; [mapControl] names each control for
 * the checks ([plannerProbe], `Map/<name>`) and gives it its tooltip from `assets/data/wdp/tips/wdpmap.json`
 * ([WdpTips]), which Settings → Show tooltips governs as it does every other Planner tip.
 */

/** The tips file of the Map page's own controls. */
internal const val WDPMAP_TIPS = "wdpmap"

/** A Map page control, named for the checks (`Map/<name>`) and given its tooltip. */
@Composable
internal fun Modifier.mapControl(name: String): Modifier {
    // "Package/2", "Types/SA-2": the family's tip where the control has none of its own
    val tips = WdpTips.of(WDPMAP_TIPS)
    return plannerProbe("Map/$name").wdpTip("$WDPMAP_TIPS/$name", tips[name] ?: tips[name.substringBefore('/')], touch = true)
}

/** One of the Map page's switches, kept per device; [old] is the pre-1.3.8 layer pref it starts from, read once. */
internal class MapSwitch(private val key: String, old: String?, def: Boolean) {
    var on by mutableStateOf(Repo.getInt(key, old?.let { Repo.getInt(it, if (def) 1 else 0) } ?: if (def) 1 else 0) == 1)
        private set

    fun set(v: Boolean) { on = v; Repo.putInt(key, if (v) 1 else 0) }
    fun flip() = set(!on)
}

/**
 * The Map page's settings, WDP's `[Map]` choices per device. They start as every map of the mission looks
 * (MissionMapMarks.kt): STPT, Numbers, PPT, Lines, Tanker tracks and Nav offsets on; Trk/Dist off at 3 nm; Bullseye
 * and Threats (the mission's) on; Airports and Airstrips (the theater's other fields: the flight's own are always
 * drawn) off, airfields unlabelled; All known SAMs, WDP's look, VORTAC, Grid,
 * Search, No radar, the own-side layers, ships and the package's other flights off; Circle fill 20 (the rings' fill on
 * every map; WDP starts at 50). No ground-unit or cheat layers.
 */
internal object WdpMapPrefs {
    /**
     * WDP's own look (Falcas's), off at the start: the steerpoints as squares, triangles and diamonds, the airfields
     * filled with the colour of the side holding them, the bullseye's 30 nm rings, the side's station boxes dash-dot
     * and the tankers' times. Off, the page draws the mission as the Mission map and the VR map board do.
     */
    val wdpLook = MapSwitch("wdpmap_wdplook", null, false)

    /** The Map page's own map style (WDP's "Use white map" is Chart); it starts on the pilot's style for every map. */
    var style by mutableStateOf(Repo.getString("wdpmap_style")?.takeIf { s -> MapLook.styles.any { it.first == s } } ?: MapLook.style)
        private set
    fun chooseStyle(s: String) { style = s; Repo.putString("wdpmap_style", s) }

    /** Label ink of airfields and navaids: 0 Auto, 1 White, 2 Black, 3 ONC (WDP's Text color). */
    var ink by mutableIntStateOf(Repo.getInt("wdpmap_ink", 0))
        private set
    fun chooseInk(i: Int) { ink = i.coerceIn(0, 3); Repo.putInt("wdpmap_ink", ink) }

    val grid = MapSwitch("wdpmap_grid", null, false)
    val bullseye = MapSwitch("wdpmap_bulls", null, true)
    val extraLines = MapSwitch("wdpmap_bullsextra", null, false)
    val cursorBulls = MapSwitch("wdpmap_cbulls", null, false)

    // The theater's other fields, off at the start as on every map of the mission: the flight's own (take-off, landing,
    // alternate) are always drawn, and the rest only crowded the map (WDP starts with the airports on, the airstrips off).
    val airports = MapSwitch("wdpmap_airports", null, false)
    val airstrips = MapSwitch("wdpmap_airstrips", null, false)
    /** 0 Name & data, 1 ICAO, 2 Off — off at the start, as on the Mission map (WDP starts on ICAO) */
    var airportLabels by mutableIntStateOf(Repo.getInt("wdpmap_aplabels", 2))
        private set
    fun chooseAirportLabels(i: Int) { airportLabels = i.coerceIn(0, 2); Repo.putInt("wdpmap_aplabels", airportLabels) }
    val vortac = MapSwitch("wdpmap_vortac", null, false)
    val maxRange = MapSwitch("wdpmap_vortacrange", null, false)

    val stpt = MapSwitch("wdpmap_stpt", "wm_route", true)
    val numbers = MapSwitch("wdpmap_numbers", "wm_labels", true)
    val trkDist = MapSwitch("wdpmap_trkdist", null, false)
    var trkMin by mutableIntStateOf(Repo.getInt("wdpmap_trkmin", 3))
        private set
    fun chooseTrkMin(n: Int) { trkMin = n.coerceIn(0, 20); Repo.putInt("wdpmap_trkmin", trkMin) }
    val ppt = MapSwitch("wdpmap_ppt", "wm_ppts", true)
    val pptNumbers = MapSwitch("wdpmap_pptnumbers", null, false)
    val lines = MapSwitch("wdpmap_lines", "wm_lines", true)
    val offsets = MapSwitch("wdpmap_offsets", "wm_offsets", true)
    val tankers = MapSwitch("wdpmap_tankers", "wm_support", true)
    val awacs = MapSwitch("wdpmap_awacs", null, true)
    val onStation = MapSwitch("wdpmap_onstation", null, true)

    val jstarArea = MapSwitch("wdpmap_jstar", null, false)
    val threats = MapSwitch("wdpmap_threats", "wm_threats", true)
    /**
     * Every enemy site the side has spotted (WDP's own Threats layer), not only the mission's — off at the start: the
     * map rings the mission's threats (the briefing's, else those along the route) as every map of the mission does.
     */
    val allThreats = MapSwitch("wdpmap_allthreats", null, false)
    val search = MapSwitch("wdpmap_search", null, false)
    val searchLarge = MapSwitch("wdpmap_searchlarge", null, false)
    val noRadar = MapSwitch("wdpmap_noradar", null, false)
    val ownSams = MapSwitch("wdpmap_ownsams", null, false)
    val ownSearch = MapSwitch("wdpmap_ownsearch", null, false)
    val ships = MapSwitch("wdpmap_ships", null, false)
    // No ground-unit layers and no "Cheat SAM" layers (WDP's Unspotted / On the move / Inactive): the map shows only
    // what the flight's side knows, and the PC sends nothing else (MissionPicture).

    /** Circle fill, 0-100: alpha value/255 as WDP's slider (at most 39 %); 20 at the start, every map's ring fill. */
    var fill by mutableIntStateOf(Repo.getInt("wdpmap_fill", 20)) // = RING_FILL × 255
        private set
    fun chooseFill(v: Int) { fill = v.coerceIn(0, 100); Repo.putInt("wdpmap_fill", fill) }
    val fillAlpha: Float get() = fill / 255f

    // ---- the Types block: the systems switched off, per theater (a system new to a save starts on)

    private val typesOffs = HashMap<String, Set<String>>()
    private var typesVersion by mutableIntStateOf(0)

    fun typesOff(theater: String): Set<String> {
        @Suppress("UNUSED_VARIABLE") val v = typesVersion
        return typesOffs.getOrPut(theater) {
            Repo.getString("wdpmap_typesoff@$theater")?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        }
    }

    fun setTypesOff(theater: String, off: Set<String>) {
        typesOffs[theater] = off
        Repo.putString("wdpmap_typesoff@$theater", off.sorted().joinToString("\n"))
        typesVersion++
    }

    // ---- folds, remembered as open_wdpmap_<section>

    private val folds = HashMap<String, Boolean>()
    private var foldVersion by mutableIntStateOf(0)

    fun open(section: String, def: Boolean): Boolean {
        @Suppress("UNUSED_VARIABLE") val v = foldVersion
        return folds.getOrPut(section) { Repo.getInt("open_wdpmap_$section", if (def) 1 else 0) == 1 }
    }

    fun setOpen(section: String, v: Boolean) {
        folds[section] = v
        Repo.putInt("open_wdpmap_$section", if (v) 1 else 0)
        foldVersion++
    }
}

/** What the Map page's tool buttons do beyond the cartridge ([MapActions] has those): the view and the picture. */
internal class MapTools(
    val fit: () -> Unit,
    val saveMap: () -> Unit,
    val refreshIntel: () -> Unit,
)

// ==================================================================================================== the panel

/**
 * The Map options, in WDP's order. [phone]: sections past MAP and FLIGHT PLAN start folded, rows are a finger's height
 * and buttons come in full-width pairs.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MapOptions(d: WdpMapData, actions: MapActions, tools: MapTools, touch: Boolean, phone: Boolean) {
    val p = WdpMapPrefs
    val x = d.x
    val rowH = if (touch) 48.dp else 30.dp

    // ---------------------------------------------------------------- 1. MAP
    OptSection("map", "Map", defaultOpen = true) {
        OptLabel("Style")
        Segmented("Style", MapLook.styles.map { it.second }, MapLook.styles.indexOfFirst { it.first == p.style }.coerceAtLeast(0), touch) {
            p.chooseStyle(MapLook.styles[it].first)
        }
        // Off: the mission as the Mission map and the VR map board draw it; on: as WDP draws it
        OptSwitch("WdpLook", "WDP's look (Falcas)", p.wdpLook.on, rowH) { p.wdpLook.set(it) }
        OptLabel("Label ink")
        Segmented("LabelInk", listOf("Auto", "White", "Black", "ONC"), p.ink, touch) { p.chooseInk(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
            SmallChip("Borders", MapLook.borders, touch, "Borders") { MapLook.showBorders(!MapLook.borders) }
            SmallChip("Provinces", MapLook.provinces, touch, "Provinces") { MapLook.showProvinces(!MapLook.provinces) }
            val towns = MapLook.placeOptions.indexOfFirst { it.first == MapLook.places }.coerceAtLeast(0)
            SmallChip("Towns: " + MapLook.placeOptions[towns].second + " ▾", MapLook.places != 0, touch, "Towns") {
                MapLook.showPlaces(MapLook.placeOptions[(towns + 1) % MapLook.placeOptions.size].first)
            }
        }
        val gridOk = x.coords?.enableNewTerrain == true
        OptSwitch("Grid", "Lat/long grid", p.grid.on && gridOk, rowH, enabled = gridOk,
            note = if (gridOk) null else "The theater's coordinates are not known, so there is no grid.") { p.grid.set(it) }
        OptSwitch("Bullseye", "Bullseye rings", p.bullseye.on, rowH, enabled = d.bull != null) { p.bullseye.set(it) }
        if (p.bullseye.on) {
            OptSwitch("ExtraLines", "Extra lines", p.extraLines.on, rowH, indent = 1) { p.extraLines.set(it) }
            OptSwitch("CursorBullseye", "Cursor bullseye", p.cursorBulls.on, rowH, indent = 1) { p.cursorBulls.set(it) }
        }
    }

    // ---------------------------------------------------------------- 2. AIRFIELDS & NAVAIDS
    OptSection("airfields", "Airfields & navaids", defaultOpen = !phone) {
        OptSwitch("Airports", "Airports", p.airports.on, rowH) { p.airports.set(it) }
        OptSwitch("Airstrips", "Airstrips and highway strips", p.airstrips.on, rowH) { p.airstrips.set(it) }
        if (p.airports.on || p.airstrips.on) {
            OptLabel("Labels")
            Segmented("AirportLabels", listOf("Name & data", "ICAO", "Off"), p.airportLabels, touch) { p.chooseAirportLabels(it) }
        }
        OptSwitch("Vortac", "VORTAC", p.vortac.on, rowH) { p.vortac.set(it) }
        if (p.vortac.on) OptSwitch("MaxRange", "Max range", p.maxRange.on, rowH, indent = 1) { p.maxRange.set(it) }
    }

    // ---------------------------------------------------------------- 3. FLIGHT PLAN
    OptSection("flightplan", "Flight plan", defaultOpen = true) {
        OptLabel("Viewing")
        Segmented("Viewing", listOf("What the jet flies", "Cartridge", "Mission file"), WdpMapView.viewing, touch) { WdpMapView.viewing = it }
        SavedLamp(d)
        OptSwitch("Stpt", "STPT", p.stpt.on, rowH) { p.stpt.set(it) }
        if (p.stpt.on) {
            OptSwitch("StptNumbers", "Numbers", p.numbers.on, rowH, indent = 1) { p.numbers.set(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { OptSwitch("TrkDist", "Trk/Dist", p.trkDist.on, rowH, indent = 1) { p.trkDist.set(it) } }
                if (p.trkDist.on) Stepper("TrkDistMin", "≥ ${p.trkMin} nm", touch, { p.chooseTrkMin(p.trkMin - 1) }, { p.chooseTrkMin(p.trkMin + 1) })
            }
        }
        OptSwitch("Ppt", "PPT", p.ppt.on, rowH) { p.ppt.set(it) }
        if (p.ppt.on) OptSwitch("PptNumbers", "PPT numbers", p.pptNumbers.on, rowH, indent = 1) { p.pptNumbers.set(it) }
        OptSwitch("Lines", "Lines", p.lines.on, rowH) { p.lines.set(it) }
        if (d.shown.offsets.isNotEmpty() || d.attack != null) OptSwitch("NavOffsets", "Nav offsets", p.offsets.on, rowH) { p.offsets.set(it) }
        OptSwitch("Tankers", "Tanker tracks", p.tankers.on, rowH) { p.tankers.set(it) }
        if (p.tankers.on) {
            OptSwitch("Awacs", "AWACS", p.awacs.on, rowH, indent = 1) { p.awacs.set(it) }
            OptSwitch("OnStationOnly", "Only on station during my flight", p.onStation.on, rowH, indent = 1) { p.onStation.set(it) }
        }
    }

    // ---------------------------------------------------------------- 4. PACKAGE
    OptSection("package", "Package", defaultOpen = !phone) {
        val f = x.flight
        val rows = f?.packageFlights.orEmpty()
        if (f == null || rows.isEmpty()) {
            OptNote(if (f == null) "Open mission… to see your package's routes." else "Your flight is alone in its package.")
        } else {
            OptLabel("Show other flights in package")
            rows.forEachIndexed { i, r ->
                val own = r.id == f.row.id
                val label = listOf(r.callsign, r.aircraft, r.task ?: r.mission).filter { it.isNotBlank() }.joinToString(" - ")
                OptSwitch("Package/${i + 1}", label, own || r.id in WdpMapView.packageOn, rowH, enabled = !own) { on ->
                    if (on) WdpMapView.packageOn.add(r.id) else WdpMapView.packageOn.remove(r.id)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 5. SAMS & INTEL
    OptSection("intel", "SAMs & intel", defaultOpen = !phone, extra = {
        SmallChip("Get new intel", false, touch, "RefreshIntel") { tools.refreshIntel() }
    }) {
        val intel = x.intel
        x.intelNote?.let { OptNote(it) }
        if (intel != null) {
            val active = intel.jstars.filter { it.active }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = rowH).mapControl("JstarLamp")) {
                Lamp(if (active.isNotEmpty()) Hud.Green else Hud.TextFaint)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (active.isNotEmpty()) "JSTAR active: " + active.joinToString(", ") { it.callsign }
                    else if (intel.jstars.isNotEmpty()) "JSTAR not on station (" + intel.jstars.joinToString(", ") { it.callsign } + ")" else "No JSTAR on your side",
                    color = if (active.isNotEmpty()) Hud.Text else Hud.TextDim, fontSize = 12.5.sp,
                )
            }
            OptSwitch("JstarArea", "JSTAR area", p.jstarArea.on, rowH) { p.jstarArea.set(it) }
            TypesBlock(d, touch)
        }
        OptSwitch("Threats", "Threats", p.threats.on, rowH) { p.threats.set(it) }
        if (p.threats.on) OptSwitch("AllThreats", "All known SAMs, not only the mission's", p.allThreats.on, rowH, indent = 1) { p.allThreats.set(it) }
        if (intel != null) {
            OptSwitch("Search", "Search radars", p.search.on, rowH) { p.search.set(it) }
            if (p.search.on) OptSwitch("SearchLarge", "S Large (75 nm)", p.searchLarge.on, rowH, indent = 1) { p.searchLarge.set(it) }
            OptSwitch("NoRadar", "No radar", p.noRadar.on, rowH) { p.noRadar.set(it) }
            OptSwitch("OwnSams", "Own side SAMs", p.ownSams.on, rowH) { p.ownSams.set(it) }
            OptSwitch("OwnSearch", "Own side search", p.ownSearch.on, rowH) { p.ownSearch.set(it) }
        }
        OptSwitch("Ships", "Ships", p.ships.on, rowH) { p.ships.set(it) }
        // Circle fill
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().mapControl("CircleFill")) {
            Text("Circle fill", color = Hud.Text, fontSize = 12.5.sp, modifier = Modifier.width(78.dp))
            Slider(
                value = p.fill.toFloat(), onValueChange = { p.chooseFill(it.toInt()) }, valueRange = 0f..100f,
                modifier = Modifier.weight(1f).height(if (touch) 40.dp else 26.dp),
                colors = SliderDefaults.colors(thumbColor = Hud.Amber, activeTrackColor = Hud.Amber.copy(alpha = 0.65f), inactiveTrackColor = Hud.Outline.copy(alpha = 0.7f)),
            )
            Spacer(Modifier.width(6.dp))
            Text("${p.fill}%", color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.width(38.dp))
        }
        // the PPT tools
        val auto = actions.autoPpt()
        ButtonRow(phone) {
            OptButton("AutoPpt", "Auto PPT", touch, enabled = auto != null, primary = true) { auto?.let { runAction(it) } }
            OptButton("ClearPpt", "Clear PPT", touch, enabled = d.loaded && d.shown.ppts.isNotEmpty()) { actions.clearPpts()?.let { runAction(it) } }
        }
        actions.bulk().firstOrNull()?.let { a ->
            OptButton("AddCrossing", a.label, touch, fill = true) { runAction(a) }
        }
        Legend(touch, phone)
    }

    // ---------------------------------------------------------------- 6. TOOLS
    OptSection("tools", "Tools", defaultOpen = true) {
        ButtonRow(phone) {
            OptButton("Measure", if (WdpMapView.measureOn) "Measure ✓" else "Measure", touch, primary = WdpMapView.measureOn) { WdpMapView.toggleMeasure() }
            OptButton("SaveMap", "Save Map", touch) { tools.saveMap() }
            if (!phone) OptButton("Fit", "Fit", touch) { tools.fit() }
        }
        if (phone) OptButton("Fit", "Fit to the flight", touch, fill = true) { tools.fit() }
    }

    // ---------------------------------------------------------------- 7. LINES
    OptSection("lines", "Lines", defaultOpen = !phone) {
        for (n in 1..4) {
            val label = actions.lineLabel(n)
            Row(
                Modifier.fillMaxWidth().heightIn(min = rowH).clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = label != null) { actions.selectLine(n) }.mapControl("Line/$n").padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Line $n:", color = Hud.TextDim, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp))
                Text(label ?: "empty", color = if (label == null) Hud.TextFaint else Hud.Text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ButtonRow(phone) {
            OptButton("ChangeArea", "Change Area…", touch, enabled = d.loaded) { actions.changeArea() }
            OptButton("ClearLines", "Clear Lines", touch, enabled = d.loaded && d.shown.lines.isNotEmpty()) { actions.clearLines()?.let { runAction(it) } }
        }
        OptNote("A line not named after something of the mission was drawn by hand (WDP calls it a Random line).")
    }
}

/** A tool's answer on the status line. */
private fun runAction(a: MapAction) {
    val r = runCatching { a.run() }.getOrElse { "That did not work: " + (it.message ?: it::class.simpleName) }
    if (r != null) WdpMapView.status = r
}

/** FLIGHT PLAN's lamp: the edits Save to DTC would write, and the TE's own file it writes too. */
@Composable
private fun SavedLamp(d: WdpMapData) {
    val dtc = WdpSession.dtc
    val n = if (d.loaded) dtc.unsaved else 0
    Column(Modifier.mapControl("SavedLamp").padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lamp(if (!d.loaded) Hud.TextFaint else if (n > 0) Hud.Red else Hud.Green)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    !d.loaded -> "No cartridge loaded"
                    n > 0 -> "$n edit${if (n == 1) "" else "s"} not saved"
                    else -> "Saved"
                },
                color = Hud.Text, fontSize = 12.5.sp,
            )
        }
        dtc.teIni()?.let { Text("Save to DTC also writes $it", color = Hud.TextDim, fontSize = 11.5.sp, modifier = Modifier.padding(start = 20.dp)) }
    }
}

/** The Types block: WDP's OPFOR | NATO tabs, here the systems the save holds on each side, with All and None. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TypesBlock(d: WdpMapData, touch: Boolean) {
    val x = d.x
    var own by remember { mutableStateOf(false) }
    Column(Modifier.mapControl("Types"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OptLabel("Types")
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) { Segmented("TypesSide", listOf("Hostile", "Own side"), if (own) 1 else 0, touch) { own = it == 1 } }
        }
        val list = if (own) x.typesOwn else x.typesHostile
        val off = WdpMapPrefs.typesOff(x.theaterKey)
        if (list.isEmpty()) OptNote(if (own) "Your side has no air defence in this save." else "The save holds no hostile air defence.")
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (t in list) {
                val on = t.system !in off
                val side = if (own) "friendly" else "hostile"
                val count = if (!own && t.seen != t.count) "${t.count}/${t.seen}" else "${t.count}"
                Row(
                    Modifier.heightIn(min = if (touch) 40.dp else 26.dp).clip(RoundedCornerShape(13.dp))
                        .background(if (on) Hud.Amber.copy(alpha = 0.16f) else Hud.Surface2)
                        .border(1.dp, if (on) Hud.Amber.copy(alpha = 0.6f) else Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(13.dp))
                        .clickable { WdpMapPrefs.setTypesOff(x.theaterKey, if (on) off + t.system else off - t.system) }
                        .mapControl("Types/${t.system}").padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(Modifier.size(12.dp)) { drawUnitSymbol("airdefence", side, center, size.height * 0.8f, dim = !on) }
                    Spacer(Modifier.width(5.dp))
                    Text(shortSystem(t.system), color = if (on) Hud.Text else Hud.TextFaint, fontSize = 12.sp, maxLines = 1)
                    Spacer(Modifier.width(5.dp))
                    Text(count, color = if (on) Hud.Amber else Hud.TextFaint, fontSize = 11.sp)
                }
            }
        }
        if (list.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val mine = list.map { it.system }.toSet()
            SmallChip("All", false, touch, "TypesAll") { WdpMapPrefs.setTypesOff(x.theaterKey, off - mine) }
            SmallChip("None", false, touch, "TypesNone") { WdpMapPrefs.setTypesOff(x.theaterKey, off + mine) }
        }
    }
}

/** "SA-2 (S-75)" → "SA-2": a system's short name for a chip. */
internal fun shortSystem(s: String): String {
    val t = s.trim()
    val first = t.substringBefore(' ')
    return if (first.any { it.isDigit() } && first.length >= 3 && t.length > 12) first else t
}

/** WDP's Colors box, in the page's inks. */
@Composable
private fun Legend(touch: Boolean, phone: Boolean) {
    OptSection("legend", "Colours", defaultOpen = !phone, nested = true) {
        val rows = listOf(
            // one red for a hostile SAM, in the DTC as a PPT or not (solid or dashed, the row below)
            MapInk.threat to "Threat / PPT in DTC",
            MapInk.search to "Search radar",
            MapInk.noRadar to "No radar",
            MapInk.ownSam to "Own side SAM",
            MapInk.jstar to "JSTAR",
        )
        for ((ink, text) in rows) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = if (touch) 32.dp else 20.dp)) {
            Canvas(Modifier.size(width = 26.dp, height = 12.dp)) {
                drawCircle(ink.copy(alpha = 0.25f), size.height / 2, Offset(size.height / 2 + 2f, size.height / 2))
                drawCircle(ink, size.height / 2 - 1f, Offset(size.height / 2 + 2f, size.height / 2), style = Stroke(2f))
            }
            Text(text, color = Hud.TextDim, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 26.dp, height = 12.dp)) {
                drawLine(Hud.TextDim, Offset(0f, size.height * 0.3f), Offset(size.width - 4f, size.height * 0.3f), 2f)
                drawLine(Hud.TextDim, Offset(0f, size.height * 0.8f), Offset(size.width - 4f, size.height * 0.8f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)))
            }
            Text("solid = in your DTC, dashed = not yet", color = Hud.TextDim, fontSize = 12.sp)
        }
    }
}

// ==================================================================================================== widgets

/** A section: a hairline rule, a faint uppercase header that folds (remembered), and its rows. */
@Composable
internal fun OptSection(
    key: String, title: String, defaultOpen: Boolean, nested: Boolean = false,
    extra: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    val open = WdpMapPrefs.open(key, defaultOpen)
    Column(Modifier.fillMaxWidth()) {
        if (!nested) Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Outline.copy(alpha = 0.45f)))
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (WdpTouch.device) 44.dp else 28.dp).clip(RoundedCornerShape(4.dp))
                .clickable { WdpMapPrefs.setOpen(key, !open) }.mapControl("Section/$key").padding(top = if (nested) 2.dp else 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "▾" else "▸", color = Hud.TextFaint, fontSize = 11.sp, modifier = Modifier.width(14.dp))
            Text(
                if (nested) title else title.uppercase(), color = if (nested) Hud.TextDim else Hud.TextFaint,
                fontSize = if (nested) 12.sp else 11.sp, fontWeight = FontWeight.Bold, letterSpacing = if (nested) 0.sp else 0.8.sp,
                modifier = Modifier.weight(1f),
            )
            extra?.invoke()
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
internal fun OptLabel(text: String) = Text(text, color = Hud.TextDim, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))

@Composable
internal fun OptNote(text: String) = Text(text, color = Hud.TextFaint, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 2.dp))

/** A switch row: a tick box and its caption; [indent] 1 for a sub-option. */
@Composable
internal fun OptSwitch(
    name: String, label: String, on: Boolean, height: Dp, indent: Int = 0, enabled: Boolean = true, note: String? = null, set: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = height).clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled) { set(!on) }
            .mapControl(name).padding(start = (indent * 18).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TickBox(on, enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) Hud.Text else Hud.TextFaint, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, color = Hud.TextFaint, fontSize = 10.5.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun TickBox(on: Boolean, enabled: Boolean) {
    val ink = if (!enabled) Hud.TextFaint else Hud.Amber
    Canvas(Modifier.size(15.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        if (on) drawRoundRect(ink, cornerRadius = r)
        else drawRoundRect(Hud.TextDim.copy(alpha = if (enabled) 0.8f else 0.4f), cornerRadius = r, style = Stroke(1.4.dp.toPx()))
        if (on) {
            val w = size.width
            val tick = Path().apply { moveTo(w * 0.22f, w * 0.52f); lineTo(w * 0.42f, w * 0.72f); lineTo(w * 0.78f, w * 0.3f) }
            drawPath(tick, Hud.Bg, style = Stroke(2.dp.toPx()))
        }
    }
}

/** A choice of a few: joined boxes, the chosen one filled. */
@Composable
internal fun Segmented(name: String, options: List<String>, selected: Int, touch: Boolean, choose: (Int) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, Hud.Outline.copy(alpha = 0.8f), shape).mapControl(name),
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            if (i > 0) Box(Modifier.width(1.dp).heightIn(min = if (touch) 44.dp else 26.dp).background(Hud.Outline.copy(alpha = 0.8f)))
            Box(
                Modifier.weight(1f).heightIn(min = if (touch) 44.dp else 26.dp).background(if (on) Hud.Amber.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { choose(i) }.padding(horizontal = 4.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(o, color = if (on) Hud.Amber else Hud.TextDim, fontSize = if (touch) 13.sp else 11.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun SmallChip(text: String, on: Boolean, touch: Boolean, name: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = if (touch) 40.dp else 24.dp).clip(RoundedCornerShape(12.dp))
            .background(if (on) Hud.Amber.copy(alpha = 0.18f) else Hud.Surface2)
            .border(1.dp, if (on) Hud.Amber.copy(alpha = 0.6f) else Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).mapControl(name).padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (on) Hud.Amber else Hud.TextDim, fontSize = 12.sp, maxLines = 1) }
}

@Composable
private fun Stepper(name: String, text: String, touch: Boolean, minus: () -> Unit, plus: () -> Unit) {
    Row(Modifier.mapControl(name), verticalAlignment = Alignment.CenterVertically) {
        IconKey(null, "−", touch, minus)
        Text(text, color = Hud.Text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
        IconKey(null, "+", touch, plus)
    }
}

/** A small square key with a glyph ("⟳", "+", "×"). */
@Composable
internal fun IconKey(name: String?, glyph: String, touch: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(if (touch) 40.dp else 24.dp).clip(RoundedCornerShape(6.dp)).background(Hud.Surface2).clickable(onClick = onClick)
            .then(if (name != null) Modifier.mapControl(name) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Hud.Text, fontSize = if (touch) 17.sp else 14.sp) }
}

@Composable
private fun Lamp(ink: Color) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(ink).border(1.dp, Hud.Bg.copy(alpha = 0.6f), CircleShape))
}

@Composable
private fun ButtonRow(phone: Boolean, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.OptButton(
    name: String, text: String, touch: Boolean, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit,
) = OptButtonBox(name, text, touch, enabled, primary, Modifier.weight(1f), onClick)

@Composable
private fun OptButton(name: String, text: String, touch: Boolean, fill: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
    OptButtonBox(name, text, touch, enabled, false, if (fill) Modifier.fillMaxWidth() else Modifier, onClick)

@Composable
private fun OptButtonBox(name: String, text: String, touch: Boolean, enabled: Boolean, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = when {
        !enabled -> Hud.Surface2.copy(alpha = 0.6f)
        primary -> Hud.Amber
        else -> Hud.Surface3
    }
    Box(
        modifier.heightIn(min = if (touch) 44.dp else 28.dp).clip(RoundedCornerShape(8.dp)).background(bg)
            .clickable(enabled = enabled, onClick = onClick).mapControl(name).padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (!enabled) Hud.TextFaint else if (primary) Hud.Bg else Hud.Text,
            fontSize = if (touch) 14.sp else 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The options in a sheet over the map (a tablet: at the right, 340 dp; a phone: the whole screen), with its ×. */
@Composable
internal fun MapOptionsSheet(phone: Boolean, modifier: Modifier, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.background(Hud.Surface.copy(alpha = if (phone) 1f else 0.98f))
            .then(if (phone) Modifier else Modifier.widthIn(max = 340.dp).border(1.dp, Hud.Outline.copy(alpha = 0.6f)))
            // it takes every tap on it, so none reaches the map underneath
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (phone) 52.dp else 40.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Map options", color = Hud.Text, fontSize = if (phone) 17.sp else 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.size(if (phone) 48.dp else 34.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = close), contentAlignment = Alignment.Center) {
                Text("×", color = Hud.TextDim, fontSize = if (phone) 24.sp else 19.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Outline.copy(alpha = 0.5f)))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            content = content,
        )
    }
}
