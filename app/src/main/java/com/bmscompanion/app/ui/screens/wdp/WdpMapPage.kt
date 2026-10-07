package com.bmscompanion.app.ui.screens.wdp

import com.bmscompanion.app.ui.components.drawDashedLine
import com.bmscompanion.app.ui.components.drawDashedCircle
import com.bmscompanion.app.ui.components.drawDashedPolyline
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.AirportSet
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Threat
import com.bmscompanion.app.data.mission.AttackCue
import com.bmscompanion.app.data.mission.AttackDrawing
import com.bmscompanion.app.data.mission.AttackOverlay
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.wdp.DtcFromMission
import com.bmscompanion.app.data.wdp.DtcFromMission.Place
import com.bmscompanion.app.data.wdp.DtcFromMission.Pt
import com.bmscompanion.app.data.wdp.PlannerMap
import com.bmscompanion.app.ui.components.HudChip
import com.bmscompanion.app.ui.components.MapLook
import com.bmscompanion.app.ui.components.MapLookButton
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.MapState
import com.bmscompanion.app.ui.components.TheaterMap
import com.bmscompanion.app.ui.components.safeText
import com.bmscompanion.app.ui.screens.mission.AwacsColor
import com.bmscompanion.app.ui.screens.mission.Friendly
import com.bmscompanion.app.ui.screens.mission.Hostile
import com.bmscompanion.app.ui.screens.mission.SamRed
import com.bmscompanion.app.ui.screens.mission.MapGlow
import com.bmscompanion.app.ui.screens.mission.MapHalo
import com.bmscompanion.app.ui.screens.mission.MapShadow
import com.bmscompanion.app.ui.screens.mission.TankerColor
import com.bmscompanion.app.ui.screens.mission.bullseye
import com.bmscompanion.app.ui.screens.mission.drawAttack
import com.bmscompanion.app.ui.screens.mission.FieldMark
import com.bmscompanion.app.ui.screens.mission.LegPt
import com.bmscompanion.app.ui.screens.mission.LineInk
import com.bmscompanion.app.ui.screens.mission.NamePrio
import com.bmscompanion.app.ui.screens.mission.airfieldRunways
import com.bmscompanion.app.ui.screens.mission.drawAirfield
import com.bmscompanion.app.ui.screens.mission.drawBullseye
import com.bmscompanion.app.ui.screens.mission.drawCartridgeLine
import com.bmscompanion.app.ui.screens.mission.drawLiveSam
import com.bmscompanion.app.ui.screens.mission.drawPptMarker
import com.bmscompanion.app.ui.screens.mission.drawPptRing
import com.bmscompanion.app.ui.screens.mission.drawRouteLegs
import com.bmscompanion.app.ui.screens.mission.drawSelection
import com.bmscompanion.app.ui.screens.mission.drawSteerpoint
import com.bmscompanion.app.ui.screens.mission.drawSupportStation
import com.bmscompanion.app.ui.screens.mission.drawSupportTrack
import com.bmscompanion.app.ui.screens.mission.drawThreatSite
import com.bmscompanion.app.ui.screens.mission.fieldMark
import com.bmscompanion.app.ui.screens.mission.fieldTag
import com.bmscompanion.app.ui.screens.mission.lineLabelAt
import com.bmscompanion.app.ui.screens.mission.markerLabelAt
import com.bmscompanion.app.ui.screens.mission.nameField
import com.bmscompanion.app.ui.screens.mission.pptLabelAt
import com.bmscompanion.app.ui.screens.mission.receiverTacan
import com.bmscompanion.app.ui.screens.mission.stptLabelAt
import com.bmscompanion.app.ui.screens.mission.stptText
import com.bmscompanion.app.ui.screens.mission.trackInk
import com.bmscompanion.app.ui.screens.mission.trackLabel
import com.bmscompanion.app.ui.screens.mission.placeText
import com.bmscompanion.app.ui.screens.mission.threatGuideEntry
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.inMapInks
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampMagVar
import com.bmscompanion.app.data.mission.CampMapIntel
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.data.wdp.DataCardPlan
import com.bmscompanion.app.data.wdp.PlannerIntel
import com.bmscompanion.app.ui.components.MapZoomButtons
import com.bmscompanion.app.ui.components.unitKindLabel
import com.bmscompanion.app.ui.screens.airportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Planner's **Map** page: what the HSD will show, on the app's own theater map, beside what the mission knows and
 * the cartridge does not carry yet — and one tap to put it there.
 *
 * - **In your DTC**, drawn solid ([PlannerMap.Shown]): the DTC page's cartridge as it stands, its unsaved edits
 *   included — the steerpoints (the flight plan joined, with BMS's own route where the cartridge leaves a slot empty, as
 *   the jet flies it in 4.38.1), the open steerpoints 81-99, lines L1-L4 dashed as the HSD draws them, the PPTs with
 *   their number, type and ring, the attack's nav offsets (VIP or VRP, the pull-up point, OA1 and OA2) laid out from
 *   their steerpoints, and the bullseye.
 * - **Not in your DTC**, drawn dashed and hollow ([PlannerMap.Known]): the tankers' and AWACS's planned tracks and
 *   stations, the known air-defence sites with their rings (the save's, and while Falcon BMS flies the ones the Tacview
 *   feed reports of the systems the briefing names), the flight's departure, arrival and alternate fields, and an attack
 *   worked out on an attack page and not saved to the DTC.
 *
 * Tapping a thing says what it is and offers what it can become (**Add as line / PPT / steerpoint / target**, a TACAN,
 * an ILS) through the DTC page's own calls ([DtcWiring]'s place… calls, [DtcFromMission]): the change shows at once on
 * every page, counts in Save to DTC's number, and asks before it replaces anything placed. A cartridge item offers to
 * be moved (the next tap puts it) or taken out. **HSD preview** draws the same on black, north up, centred on a
 * steerpoint, at the HSD's own ranges, in the HSD's colours — what the pilot will see on the MFD.
 */
@Composable
fun WdpMapPage(mission: WdpMission, modifier: Modifier = Modifier) {
    val dtc = WdpSession.dtc
    val th = mission.theater
    val served by MissionLink.bmsFiles.collectAsState()
    val contacts by MissionLink.contacts.collectAsState()
    // the feed's air defences decide the facts (a site of a briefed system), not every move of every aircraft
    val samKey = contacts?.contacts.orEmpty().filter { it.kind == "sam" && it.hostile }.joinToString(",") { it.id }
    val reference by produceState(emptyList<Threat>()) { value = runCatching { Repo.threats() }.getOrDefault(emptyList()) }
    val set by produceState<AirportSet?>(null, th?.airportSet) { value = th?.let { t -> runCatching { Repo.airportSet(t.airportSet) }.getOrNull() } }
    val gathered by produceState<Pair<DtcFromMission.Facts, Pt?>?>(null, mission, served?.tracks, served?.briefing, samKey) {
        // the Planner hands the DTC page this mission a moment before the page is drawn: wait for it, so the facts are
        // this mission's and not the last one's
        var waited = 0
        while (WdpSession.appliedMission !== mission && waited < 40) { delay(100); waited++ }
        runCatching { dtc.prepareFacts() }
        val f = runCatching { WdpMapView.facts(dtc) }.getOrNull()
        value = f?.let { it to mapBullseye(mission) }
    }
    // the save as WDP's MAP tab draws it (the PC's /api/campaign/mapintel: every unit, search radar, field and JSTARS),
    // the flight whose package and side it is, and the theater's magnetic variation
    val intelKey = WdpMapIntel.key(mission)
    val loaded by produceState(WdpMapIntel.cached(intelKey), intelKey, WdpMapView.intelAsk) {
        value = WdpMapIntel.load(mission, intelKey, force = WdpMapView.intelAsk > 0 && WdpMapIntel.cached(intelKey) != null)
    }
    val magVar by produceState(WdpMapIntel.cachedVar(mission), th?.id) { value = WdpMapIntel.magVar(mission) }
    // read so the page follows every edit on the DTC page (and its own), saved or not
    val picture = dtc.dtcPicture()
    val free = dtc.freeSlots()
    val facts0 = gathered?.first
    val typesOff = WdpMapPrefs.typesOff(th?.id ?: "")
    val liveShips = contacts?.contacts.orEmpty().filter { it.hostile && it.kind == "ship" }.map { DtcFromMission.Place(it.name ?: "Ship", Pt(it.x, it.y), 0.0, DtcMissionFacts.SHIPS) }
    val extras = remember(loaded, magVar, set, reference, picture, mission, liveShips.size) {
        mapExtras(dtc, mission, loaded, magVar, set, reference, liveShips)
    }
    // only the mission's threats unless the options ask for every known one: the sites the printed briefing names when
    // the flight open is the one BMS printed (the PC placed them, MissionData.ground), else those along the route
    val allThreats = WdpMapPrefs.allThreats.on
    val briefed = served?.ground?.takeIf { g ->
        g.threatsFrom == "briefing" && (mission.flight == null || mission.flight.row.briefed)
    }?.airDefences
    val facts = remember(facts0, extras, typesOff, allThreats, briefed) { facts0?.let { withIntel(it, extras, typesOff, reference, allThreats, briefed) } }
    val data = remember(facts, picture, free, set, reference, gathered?.second, extras) {
        facts?.let { mapData(dtc, it, mission, set, reference, gathered?.second, extras) }
    }
    val coords = remember(mission.coords) { DtcCoords(mission.coords) }
    // the package's other flights are off at the start, as in WDP: the options' PACKAGE section shows each one asked for

    BoxWithConstraints(modifier.fillMaxSize().background(Hud.Bg)) {
        // the widths the rest of the app lays out by (isWide, isMedium), measured on the page's own box: the Planner's
        // box is the window less its rail
        val wide = maxWidth >= 840.dp
        // a phone: the list and the options are sheets over the whole map, as the Planner's windows fill a phone's screen
        val phone = maxWidth < 600.dp
        val panelW = if (maxWidth >= 1200.dp) 420.dp else 360.dp
        val touch = WdpTouch.device
        when {
            th == null -> PageNote(
                "The theater is not known",
                "The Map page draws the mission on the theater's map, and the Planner does not know which theater this is. " +
                    "Open a mission (Open mission… on the toolbar) or press PRINT in Falcon BMS.",
                null,
            ) {}
            data == null -> PageNote("Reading what the mission holds…", "The flight plan, the tracks, the air defences and the airfields.", null) {}
            data.facts.route.isEmpty() && !data.loaded -> PageNote(
                "No mission yet",
                "Open a mission file in the Planner (Open mission… on the toolbar), or press PRINT in Falcon BMS. The map then shows what your HSD will show.",
                null,
            ) {}
            else -> Row(Modifier.fillMaxSize()) {
                val ui = remember(dtc) { PageUi() }
                val actions = remember(data, coords) { MapActions(dtc, data, coords, ui, mission) }
                val scope = rememberCoroutineScope()
                val layer = rememberGraphicsLayer()
                val tools = remember(layer, mission) {
                    MapTools(
                        fit = { WdpMapView.fitAsk++ },
                        saveMap = { scope.launch { WdpMapView.status = saveMapPicture(layer, mission) } },
                        refreshIntel = { WdpMapView.intelAsk++; WdpMapView.status = "Asking the PC for the save's intel again…" },
                    )
                }
                WdpMapView.tools = tools
                // the ground under what is picked, for its card (the strip asks for the pointer's)
                val picked = WdpMapView.sel
                LaunchedEffect(picked, data) { picked?.let { pickPos(data, it) }?.let { askGround(data.x.groundTheater, it) } }
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val boxW = with(LocalDensity.current) { maxWidth.toPx() }
                    val boxH = with(LocalDensity.current) { maxHeight.toPx() }
                    // the map under its controls; the HSD preview below them, so nothing covers the display
                    if (WdpMapView.hsd) Column(Modifier.fillMaxSize()) {
                        Controls(data, wide, phone, touch, Modifier.fillMaxWidth().padding(8.dp))
                        MapArea(th.map, th.sizeFt, data, actions, layer, Modifier.weight(1f).fillMaxWidth())
                    } else {
                        MapArea(th.map, th.sizeFt, data, actions, layer, Modifier.fillMaxSize())
                        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (phone) {
                                ReadoutStrip(data, coords, boxW, boxH, phone = true, Modifier.fillMaxWidth())
                                Controls(data, wide, phone, touch, Modifier.align(Alignment.End))
                            } else Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.weight(1f)) { ReadoutStrip(data, coords, boxW, boxH, phone = false, Modifier) }
                                Spacer(Modifier.width(8.dp))
                                Controls(data, wide, phone, touch, Modifier)
                            }
                            if (!data.loaded) NoCartridgeChip(Modifier)
                        }
                        // beside the cursor: what it rests on, and the bullseye (PC and browser)
                        val at = MapCursor.at
                        val hover = MapCursor.hover
                        if (at != null && !touch) {
                            if (hover != null) MapCursor.hoverAt?.let { h -> HoverBox(actions.hoverFacts(hover), h, Modifier.fillMaxSize()) }
                            else if (WdpMapPrefs.cursorBulls.on && WdpMapPrefs.bullseye.on) {
                                val p = MapCursor.pointer()
                                if (p != null && data.bull != null) CursorBulls(PlannerMap.braText(data.bull, p), at, Modifier.fillMaxSize())
                            }
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp, start = 10.dp, end = 64.dp).bannerWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WdpMapView.moving?.let { m -> MoveBanner(m) }
                        if (WdpMapView.measureOn && !WdpMapView.hsd) MeasureBanner(data, coords, touch)
                        WdpMapView.status?.let { s -> StatusLine(s) }
                        if (!wide) {
                            val sel = WdpMapView.sel
                            if (WdpMapView.listOpen) { if (!phone) ListSheet(data, actions, touch) }
                            else if (sel != null && !WdpMapView.optionsOpen) actions.card(sel)?.let { SelectionCard(it, touch, Modifier.fillMaxWidth()) }
                        }
                    }
                    // the options over the map: a side sheet on a tablet, the whole screen on a phone
                    if (!wide && WdpMapView.optionsOpen) {
                        MapOptionsSheet(
                            phone, if (phone) Modifier.fillMaxSize() else Modifier.align(Alignment.TopEnd).fillMaxHeight().width(340.dp),
                            close = { WdpMapView.optionsOpen = false },
                        ) { MapOptions(data, actions, tools, touch, phone) }
                    }
                    if (!wide && phone && WdpMapView.listOpen) ListScreen(data, actions, touch, Modifier.fillMaxSize())
                }
                if (wide) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline.copy(alpha = 0.5f)))
                    Column(Modifier.width(panelW).fillMaxHeight()) {
                        WdpMapView.sel?.let { s -> actions.card(s)?.let { Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) { SelectionCard(it, touch, Modifier.fillMaxWidth()) } } }
                        PanelTabs(touch)
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(if (WdpMapView.panelTab == 0) 0.dp else 12.dp),
                        ) {
                            if (WdpMapView.panelTab == 0) MapOptions(data, actions, tools, touch, phone = false)
                            else PanelContent(data, actions, touch)
                        }
                    }
                }
            }
        }
    }
    // the status line fades after a while
    val status = WdpMapView.status
    LaunchedEffect(status) { if (status != null) { delay(7000); if (WdpMapView.status == status) WdpMapView.status = null } }
}

/** The wide panel's two tabs: WDP's options, and the list of what the HSD will show and what it will not. */
@Composable
private fun PanelTabs(touch: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Segmented("PanelTab", listOf("Map options", "HSD list"), WdpMapView.panelTab, touch) { WdpMapView.panelTab = it }
    }
}

// ==================================================================================================== state

/** The Map page's view, kept for as long as the app runs (the other pages' state is, [WdpSession]); the layers are [WdpMapPrefs]. */
internal object WdpMapView {
    var hsd by mutableStateOf(Repo.getInt("wm_hsd", 0) == 1)
    var hsdRange by mutableIntStateOf(Repo.getInt("wm_hsd_range", 60))
    /** the steerpoint the HSD preview centres on; null: the first target of the flight plan */
    var hsdCentre by mutableStateOf<Int?>(null)
    /** the HSD preview also shows, dim and dashed, what the mission knows and the cartridge does not carry */
    var ghosts by mutableStateOf(Repo.getInt("wm_ghosts", 1) == 1)

    var sel by mutableStateOf<MapPick?>(null)
    /** a cartridge item waiting for the tap that says where it goes */
    var moving by mutableStateOf<MapPick?>(null)
    var status by mutableStateOf<String?>(null)
    /** the list (what is in the DTC, what is not) over a narrow map */
    var listOpen by mutableStateOf(false)
    /** the options over a narrow map (a side sheet, a phone's whole screen) */
    var optionsOpen by mutableStateOf(false)
    /** the wide panel's tab: 0 Map options, 1 HSD list */
    var panelTab by mutableIntStateOf(0)
    /** FLIGHT PLAN's Viewing: 0 what the jet flies, 1 the cartridge, 2 the mission file (not kept: it opens on 0) */
    var viewing by mutableIntStateOf(0)
    /** the package's other flights shown (their ids; not kept): none at the start of each mission, as in WDP */
    val packageOn = mutableStateListOf<String>()
    /** (no longer used: the package's flights are not turned on by themselves) */
    var packageFor: String? = null
    /** a phone's readout strip opened to two lines */
    var readoutOpen by mutableStateOf(false)

    /** Measure: on, its first and second point */
    var measureOn by mutableStateOf(false)
    var m1 by mutableStateOf<Pt?>(null)
    var m2 by mutableStateOf<Pt?>(null)

    fun toggleMeasure() {
        measureOn = !measureOn
        m1 = null; m2 = null
        if (measureOn) { sel = null; moving = null }
    }

    /** A tap while measuring: the first point, the second, then a new first. */
    fun measureTap(at: Pt) {
        if (m1 == null || m2 != null) { m1 = at; m2 = null } else m2 = at
    }

    /** The page's tools as last composed (Save Map, for the checks) */
    internal var tools: MapTools? = null

    /** Fit and the refresh of the intel, asked by counting */
    var fitAsk by mutableIntStateOf(0)
    var intelAsk by mutableIntStateOf(0)

    /** the theater map's pan and zoom, and the mission it was framed on */
    val map = MapState()
    var framed: String? = null

    /** Where the page's facts come from: the DTC page's ([DtcWiring.missionFacts]); the checks hand in their own. */
    internal var facts: (DtcWiring) -> DtcFromMission.Facts = { it.missionFacts() }

    fun save() {
        fun b(k: String, v: Boolean) = Repo.putInt(k, if (v) 1 else 0)
        b("wm_hsd", hsd); Repo.putInt("wm_hsd_range", hsdRange)
        b("wm_ghosts", ghosts)
    }

    /**
     * A new mission or a switch of mode ([com.bmscompanion.app.data.mission.MissionEpoch]): nothing of the last
     * mission's view stays — the selection, a move waiting, the measure, the HSD's centre, the package flights shown,
     * the Viewing choice, the list, the status, the framing and the save's cached picture. The layers (WdpMapPrefs),
     * the HSD's range and the map style are the pilot's preferences, not the mission's, and stay.
     */
    fun resetForMission() {
        sel = null; moving = null; status = null; listOpen = false
        measureOn = false; m1 = null; m2 = null
        hsdCentre = null; viewing = 0
        packageOn.clear(); packageFor = null
        framed = null
        MapCursor.tapAt = null
        WdpMapIntel.clear()
        fitAsk++
    }
}

/**
 * The save's picture for the Map page ([CampMapIntel]), the flight whose package and side it is, and the theater's
 * variation, asked of the PC once per mission and kept for as long as the app runs.
 */
internal object WdpMapIntel {
    class Loaded(val intel: CampMapIntel?, val flight: CampFlight?, val note: String?)

    private val cache = HashMap<String, Loaded>()
    private val vars = HashMap<String, CampMagVar?>()

    /** The checks hand in their own answer; null asks the PC. */
    internal var source: ((WdpMission) -> Loaded?)? = null
    internal var varSource: ((WdpMission) -> CampMagVar?)? = null

    fun key(m: WdpMission): String = listOf(m.ref?.theater, m.ref?.file, m.ref?.flight, m.flight?.row?.id, m.theater?.id, m.briefing?.steerpoints?.size).joinToString("|")

    fun cached(k: String): Loaded? = cache[k]

    private fun varKey(m: WdpMission) = m.ref?.theater ?: m.theater?.id ?: ""

    fun cachedVar(m: WdpMission): CampMagVar? = vars[varKey(m)]

    suspend fun load(m: WdpMission, k: String, force: Boolean): Loaded {
        if (!force) cache[k]?.let { return it }
        source?.let { s -> return (s(m) ?: Loaded(null, m.flight, null)).also { cache[k] = it } }
        val ref = m.ref
        val a = runCatching {
            if (ref != null && m.flight != null) MissionLink.campaignMapIntel(ref.theater, ref.file, ref.flight)
            else MissionLink.campaignMapIntel(null, null, null)
        }.getOrNull()
        val intel = a?.value?.takeIf { a.ok }
        val note = when {
            intel != null -> intel.notes.firstOrNull()
            a == null || !a.reached -> "The PC is not answering: the map shows what the flight carries (its spotted air defences)."
            a.error == PcAnswer.OLD_PC -> "BMS Companion on the PC is older than this app: the map shows the save's spotted air defences only. Update it on the PC for the save's whole picture."
            else -> a.error
        }
        // the flight whose package and side support the map draws: the one planned, else the one BMS briefed
        var flight = m.flight
        if (flight == null && intel?.flight != null && intel.file.isNotBlank()) {
            flight = runCatching { MissionLink.campaignFlight(intel.theater, intel.file, intel.flight!!) }.getOrNull()?.value
        }
        val out = Loaded(intel, flight, note)
        // an answer that did not reach the PC is not kept, so the next visit asks again
        if (a?.reached == true || intel != null) cache[k] = out
        return out
    }

    suspend fun magVar(m: WdpMission): CampMagVar? {
        val k = varKey(m)
        if (k in vars) return vars[k]
        varSource?.let { return it(m).also { v -> vars[k] = v } }
        val a = runCatching { MissionLink.campaignMagVar(m.ref?.theater ?: m.theater?.id) }.getOrNull() ?: return null
        if (a.reached) vars[k] = a.value?.takeIf { a.ok }
        return a.value?.takeIf { a.ok }
    }

    /** For the checks: forget every answer. */
    fun clear() { cache.clear(); vars.clear() }
}

/**
 * The facts with the save's intel for its air defences: the threats the map draws (WDP's rules, only what the side has
 * seen), the feed's kept. Unless [allThreats] (the Map options' "All known SAMs"), only the **mission's** threats
 * ([missionThreats]): the sites the printed briefing names ([briefed], when the Planner has that flight open), else the
 * spotted ones whose ring reaches the route — as the Mission map and the VR map board draw them.
 */
internal fun withIntel(
    f: DtcFromMission.Facts, x: MapExtras, off: Set<String>, reference: List<Threat>,
    allThreats: Boolean = false, briefed: List<com.bmscompanion.app.data.mission.CampSite>? = null,
): DtcFromMission.Facts {
    val intel = x.intel
    val all = if (intel == null) {
        // an older PC: the flight's spotted sites, the Types block's choice applied by name
        f.sites.filter { s -> off.isEmpty() || s.source != DtcMissionFacts.SAVE_SITES || s.names.none { it in off } }
    } else {
        val units = PlannerIntel.threats(intel, off)
        val sites = units.map { u -> DtcMissionFacts.siteOf(PlannerIntel.threatType(u), u.x, u.y, reference, INTEL_SITES) }
        sites + f.sites.filter { it.source != DtcMissionFacts.SAVE_SITES && sites.none { s -> s.at.dist(it.at) < 3000.0 } }
    }
    val shown = if (allThreats) all else missionThreats(all, f, x, briefed, reference)
    return f.copy(sites = shown, notes = if (all.isEmpty()) f.notes else f.notes.filter { it != DtcMissionFacts.NO_SITES })
}

/**
 * The mission's threats among [all] (the map's known sites): with [briefed], those the briefing names (and any it names
 * the save no longer holds, where the PC placed it); without, the save's whose ring
 * ([com.bmscompanion.app.data.mission.MissionPicture.alongRoute]) comes within 3 nm of the flight plan. A site the
 * Tacview feed reports stays only where it is one of those.
 */
internal fun missionThreats(
    all: List<DtcFromMission.Site>, f: DtcFromMission.Facts, x: MapExtras,
    briefed: List<com.bmscompanion.app.data.mission.CampSite>?, reference: List<Threat>,
): List<DtcFromMission.Site> {
    val near = com.bmscompanion.app.data.mission.MissionPicture.LIVE_MATCH_NM * DtcFromMission.NM
    fun same(s: DtcFromMission.Site, k: DtcFromMission.Site) =
        s.at.dist(k.at) <= near && s.names.any { a -> k.names.any { b -> com.bmscompanion.app.data.mission.MissionPicture.sameSystem(a, b) } }
    val (live, save) = all.partition { it.source == LIVE_SITES }
    val kept = if (briefed != null) {
        val told = briefed.map { DtcMissionFacts.siteOf(it.system, it.x, it.y, reference, BRIEFED_SITES) }
        val found = save.filter { s -> told.any { same(s, it) } }
        found + told.filter { t -> found.none { same(it, t) } }
    } else {
        val route = f.route.map { it.at.north to it.at.east }
        val margin = com.bmscompanion.app.data.mission.MissionPicture.ROUTE_MARGIN_NM * DtcFromMission.NM
        save.filter { s ->
            val ring = s.names.firstNotNullOfOrNull { n -> runCatching { x.ringOf(n) }.getOrNull() }
                ?: (com.bmscompanion.app.data.mission.MissionPicture.UNKNOWN_RING_NM * DtcFromMission.NM)
            route.isNotEmpty() && com.bmscompanion.app.data.mission.MissionPicture.distanceToRoute(s.at.north, s.at.east, route) - ring <= margin
        }
    }
    return kept + live.filter { l -> kept.any { same(l, it) } }
}

/** Where an intel site comes from, as a card says it. */
internal const val INTEL_SITES = "the save's intel"

/** A site the printed briefing names that the save no longer holds where BMS said: placed by the briefing's words. */
internal const val BRIEFED_SITES = "the briefing"

/** Where a site the Tacview feed reports comes from ([DtcMissionFacts.gather]'s word for it). */
private const val LIVE_SITES = "the Tacview feed"

/**
 * What the map draws beside the cartridge, from the PC's intel ([WdpMapIntel.Loaded]), the variation and the theater's
 * airfields: worked out once per change of any of them.
 */
internal fun mapExtras(
    dtc: DtcWiring, mission: WdpMission, loaded: WdpMapIntel.Loaded?, magVar: CampMagVar?, set: AirportSet?, reference: List<Threat>,
    liveShips: List<DtcFromMission.Place>,
): MapExtras {
    val intel = loaded?.intel
    val flight = loaded?.flight ?: mission.flight
    val table = runCatching { dtc.pptTypesInUse }.getOrDefault(emptyList())
    fun typed(system: String) = DtcFromMission.threatType(table, DtcMissionFacts.siteOf(system, 0.0, 0.0, reference, INTEL_SITES))
    val ringCache = HashMap<String, Double?>()
    val ringOf: (String) -> Double? = { s ->
        // the one rule every map of the mission rings a system by (the Mission map's sites too)
        ringCache.getOrPut(s) { com.bmscompanion.app.ui.screens.mission.siteRingFt(s, table, reference) }
    }
    val codeOf: (String) -> String? = { s -> typed(s)?.first }
    val stations = flight?.sideSupport.orEmpty().ifEmpty { flight?.support.orEmpty() }.mapNotNull { PlannerIntel.station(it) }
    val route = flight?.route.orEmpty()
    val land = route.indexOfFirst { it.action == PlannerIntel.LAND }.let { if (it < 0) route.size - 1 else it }
    val window = if (route.isEmpty()) null else route.first().departMs to (route.getOrNull(land)?.arriveMs ?: route.last().arriveMs)
    val fieldOf = intel?.fields.orEmpty().mapNotNull { f -> f.airport?.toIntOrNull()?.let { it to f } }.toMap()
    val threatUnits = intel?.let { PlannerIntel.threats(it, emptySet()) }.orEmpty()
    val comm = runCatching { dtc.model?.comm }.getOrNull()
    return MapExtras(
        intel = intel,
        intelNote = loaded?.note,
        magVar = magVar,
        coords = mission.coords,
        airports = set?.airports.orEmpty().filter { (it.x != 0.0 || it.y != 0.0) && airfieldLayer(it) != null },
        navaids = set?.navaids.orEmpty().filter { it.x != 0.0 || it.y != 0.0 },
        fieldOf = fieldOf,
        flight = flight,
        stations = stations,
        window = window,
        typesHostile = intel?.let { PlannerIntel.types(it, "hostile", ringOf) }.orEmpty(),
        typesOwn = intel?.let { PlannerIntel.types(it, "friendly", ringOf) }.orEmpty(),
        siteUnit = threatUnits.associateBy { Pt(it.x, it.y) },
        theaterKey = mission.theater?.id ?: "",
        ringOf = ringOf,
        codeOf = codeOf,
        groundTheater = mission.groundTheater(),
        tacan = comm?.let { c -> if (c.tacanChannel in 1..126) Triple(c.tacanChannel, c.tacanBand, c.tacanDomain) else null },
        liveShips = liveShips,
    )
}

/** What a tap on the Map page picked. */
internal sealed interface MapPick {
    data class Stpt(val n: Int) : MapPick
    data class Ppt(val slot: Int) : MapPick
    /** point [k] (0-5) of line [line] (1-4) */
    data class LinePt(val line: Int, val k: Int) : MapPick
    data class Offset(val label: String, val stpt: Int) : MapPick
    data object Bullseye : MapPick
    data class Track(val callsign: String) : MapPick
    data class Station(val callsign: String) : MapPick
    data class Site(val index: Int) : MapPick
    data class Field(val id: Int) : MapPick
    /** the attack an attack page has worked out and not saved to the DTC */
    data object Attack : MapPick
    data class Point(val at: Pt) : MapPick
    /** a unit of the save's intel ([MapExtras.units]): a SAM site off the threat layer, a ground unit, a ship */
    data class GroundUnit(val id: String) : MapPick
    /** a search radar of the save ([MapExtras.radars]) */
    data class Radar(val id: String) : MapPick
    /** navaid [i] of the theater ([MapExtras.navaids]) */
    data class Navaid(val i: Int) : MapPick
    /** waypoint [k] of another flight of the package */
    data class PackageWp(val flight: String, val k: Int) : MapPick
    data class Jstar(val callsign: String) : MapPick
    /** a tanker, AWACS or JSTARS of the side ([MapExtras.stations]) that is not the package's */
    data class SideStation(val callsign: String) : MapPick
    /** a Tacview ship */
    data class LiveShip(val i: Int) : MapPick
}

/** One of the flight's airfields: its role ("departure", "arrival and alternate"), the TACANs and ILSs the mission offers there. */
internal data class MapField(
    val airport: Airport,
    val role: String,
    val tacans: List<DtcFromMission.TacanChoice>,
    val ils: List<DtcFromMission.IlsChoice>,
) {
    val at: Pt get() = Pt(airport.x, airport.y)
}

/** Everything the page draws and offers, worked out once per change of the mission or the cartridge. */
internal class WdpMapData(
    val facts: DtcFromMission.Facts,
    val shown: PlannerMap.Shown,
    val known: PlannerMap.Known,
    val fields: List<MapField>,
    /** the bullseye and where it comes from */
    val bull: Pt?,
    val lineOptions: List<DtcFromMission.LineOption>,
    val pptOptions: DtcFromMission.PptOptions,
    val free: DtcWiring.FreeSlots?,
    /** a cartridge is on the DTC page */
    val loaded: Boolean,
    /** the Planner's current attack (AttackFocus) where the cartridge's nav offsets do not hold it */
    val attack: AttackOverlay?,
    val targets: Set<Int>,
    /** WDP's MAP tab's layers: the save's intel, the theater's airfields and navaids, the side's support, the variation */
    val x: MapExtras = MapExtras(),
    /** the one attack the map draws (1.3.8): the current attack, else the cartridge's; null for none */
    val drawn: AttackOverlay? = null,
) {
    /** The steerpoints of FLIGHT PLAN's Viewing: what the jet flies, the cartridge's alone, or the mission file's. */
    fun view(viewing: Int): PlannerMap.Shown = when (viewing) {
        1 -> shown.copy(stpts = shown.stpts.filter { it.inDtc })
        2 -> shown.copy(stpts = facts.route.map { r ->
            PlannerMap.Stpt(r.n, r.at, r.name, inDtc = false, onRoute = true, target = r.n in targets || r.action in ATTACKS,
                action = r.action, elevFt = r.altFt, from = r.from)
        })
        else -> shown
    }
}

/** The actions of a steerpoint that is a target (as [PlannerMap] has them). */
private val ATTACKS = setOf(14, 15, 16, 17, 18, 19, 20, 21, 23, 25, 30)

/** The bullseye the HSD will show: the save header's for the briefed flight, else the sim's while it runs. */
internal fun mapBullseye(mission: WdpMission): Pt? {
    mission.route?.let { r -> r.bullseyeX?.let { x -> r.bullseyeY?.let { y -> return Pt(x, y) } } }
    // a save's flight carries its save header's bullseye
    mission.flight?.let { f -> f.bullseyeX?.let { x -> f.bullseyeY?.let { y -> return Pt(x, y) } } }
    val live = runCatching { MissionLink.live.value }.getOrNull()
    val ctcs = runCatching { MissionLink.contacts.value?.contacts }.getOrNull()
    bullseye(live, ctcs)?.let { return Pt(it.first, it.second) }
    // the mission file BMS wrote beside the save the Planner opened, when it is that save
    val served = runCatching { MissionLink.bmsFiles.value?.route }.getOrNull()
    val file = PlannerMissionState.ref?.file
    if (served != null && file != null && served.save.equals(file, true)) served.bullseyeX?.let { x -> served.bullseyeY?.let { y -> return Pt(x, y) } }
    return null
}

/** Builds what the page draws: [facts] with the DTC page's cartridge, options and free slots. */
internal fun mapData(
    dtc: DtcWiring,
    facts: DtcFromMission.Facts,
    mission: WdpMission,
    set: AirportSet?,
    reference: List<Threat>,
    bull: Pt?,
    x: MapExtras = MapExtras(),
): WdpMapData {
    val lineOptions = runCatching { dtc.lineOptions(facts) }.getOrDefault(emptyList())
    val options = runCatching { dtc.pptOptions(facts) }.getOrDefault(DtcFromMission.PptOptions(emptyList(), emptyList(), emptyList(), emptyList()))
    val targets = mission.choices.filter { it.key.startsWith("STPT ") }.map { it.waypoint }.toSet()
    val shown = PlannerMap.shown(dtc.model, facts.route, targets, lineOptions)
    val known = PlannerMap.known(facts, options.options, lineOptions, shown) { s ->
        (threatGuideEntry(s.name, reference) ?: s.names.firstNotNullOfOrNull { threatGuideEntry(it, reference) })
            ?.let { it.numbers["maxRangeNm"] ?: it.numbers["typicalRangeNm"] }?.takeIf { it > 0.0 }?.times(DtcFromMission.NM)
    }
    // the flight's fields, each once, with the roles it has
    val bases = runCatching { missionBases(mission.briefing, mission.flight) }.getOrNull()
    val roles = LinkedHashMap<Int, Pair<Airport, MutableList<String>>>()
    for ((a, what) in listOfNotNull(bases?.departureIn(set)?.let { it to "departure" }, bases?.arrivalIn(set)?.let { it to "arrival" }, bases?.alternateIn(set)?.let { it to "alternate" })) {
        if (a.x == 0.0 && a.y == 0.0) continue
        roles.getOrPut(a.id) { a to ArrayList() }.second += what
    }
    val fields = roles.values.map { (a, r) ->
        MapField(
            a, r.joinToString(" and "),
            facts.tacans.filter { it.domain == 0 && it.label.startsWith(a.name + " ") },
            facts.ils.filter { it.label.startsWith(a.name + " RWY") },
        )
    }
    // ONE attack on the map (1.3.8): the Planner's current attack (AttackFocus), else the cartridge's. The cartridge's
    // takes the current attack's shape (its profile) when it is that attack; the caption says when the cartridge as
    // saved does not hold what is drawn. No ghost of another page's attack beside the cartridge's. With no current
    // attack the cartridge's is drawn as the Mission map draws it (AttackDrawing.fromCartridge with no profile: start →
    // PUP → TGT, the generic caption) — never shaped by the DataCard's profile, which may name another page's attack.
    val focus = runCatching { WdpAttackOverlay.toSend() }.getOrNull()
    val sameAsCartridge = focus != null && AttackDrawing.same(focus, shown.attack)
    val profile = if (sameAsCartridge) focus?.profile else null
    val shaped = if (profile != null && shown.attack != null) PlannerMap.shown(dtc.model, facts.route, targets, lineOptions, profile, focus?.tgtStpt) else shown
    val cartridge = shaped.attack?.let { a -> a.copy(saved = runCatching { AttackDrawing.same(a, dtc.savedAttack(a.profile.ifBlank { null })) }.getOrDefault(true)) }
    val drawn = focus ?: cartridge
    // the current attack where the cartridge does not hold it: its own pick and list row
    val attack = focus?.takeIf { !sameAsCartridge }
    return WdpMapData(facts, shaped, known, fields, bull, lineOptions, options, dtc.freeSlots(), dtc.model != null, attack, targets, x, drawn)
}

// ==================================================================================================== the actions

/** What the page does besides the cartridge: the selection, a move waiting for its tap, the HSD's centre, the status line. */
internal open class MapUi {
    open fun status(s: String?) { WdpMapView.status = s }
    open fun select(p: MapPick?) { WdpMapView.sel = p }
    open fun move(p: MapPick?) { WdpMapView.moving = p; if (p != null) WdpMapView.status = null }
    open fun centre(n: Int) { WdpMapView.hsdCentre = n; if (!WdpMapView.hsd) { WdpMapView.hsd = true; WdpMapView.save() } }
    open fun openPage(page: WdpPage) { WdpSession.page = page.name }
}

private class PageUi : MapUi()

/** A button of a selection card: [run] answers what was done (null: a question is on screen, its answer comes later). */
internal class MapAction(val label: String, val primary: Boolean = false, val run: () -> String?)

/** What a selection card says: a title, which of the two kinds it is, lines of facts, and the actions. */
internal data class MapCard(val title: String, val inDtc: Boolean?, val tag: String, val lines: List<String>, val actions: List<MapAction>, val accent: Color)

/**
 * What each pick is and what can be done with it, through the DTC page's calls. Plain code, so the checks press the
 * same buttons ([card]) a pilot does.
 */
internal class MapActions(
    private val dtc: DtcWiring,
    val data: WdpMapData,
    private val coords: DtcCoords,
    private val ui: MapUi,
    private val mission: WdpMission? = null,
) {
    private val d get() = data
    private val x get() = data.x
    private val done: (String) -> Unit = { ui.status(it) }

    private fun where(at: Pt): List<String> {
        val out = ArrayList<String>()
        out += positionText(coords, at)
        d.bull?.let { out += "Bullseye " + PlannerMap.braText(it, at) }
        return out
    }

    private fun nextPpt(): Int? = d.free?.ppts?.firstOrNull()
    private fun nextStpt(): Int? = d.free?.let { PlannerMap.freeStpt(d.shown, it.stpts) }
    private fun nextOpen(): Int? = d.free?.open1?.firstOrNull()
    private fun nextHarpoon(): Int? = d.free?.open2?.firstOrNull()

    private fun addPpt(o: DtcFromMission.PptOption): MapAction? {
        if (!d.loaded) return null
        val slot = nextPpt()
        return if (slot != null) MapAction("Add as PPT $slot", primary = true) { answer(dtc.placePpt(slot, o, ask = true, done = done)) }
        else MapAction("Add as PPT 70 (replace)", primary = true) { answer(dtc.placePpt(70, o, ask = true, done = done)) }
    }

    /** WDP's PPT window at a point, in ADD: the type [code] (or [name]) chosen, the ground's elevation where known. */
    private fun pptWindow(at: Pt, code: String?, name: String?, label: String = "Add as PPT…"): MapAction? {
        if (!d.loaded) return null
        return MapAction(label) {
            val elev = groundAt(x.groundTheater, at)
            answer(dtc.newPpt(at.north, at.east, code, elev, name, done))
        }
    }

    private fun addStpt(p: Place, n: Int? = nextStpt()): MapAction? =
        if (!d.loaded || n == null) null else MapAction("Add as STPT $n") { answer(dtc.placeSteerpoint(n, p, ask = true, done = done)) }

    /** [p] into the Open bank (STPT 81-99) through the chooser, as [action] unless the pilot picks another type. */
    private fun addOpen(p: Place, action: Int = OpenBankChooser.PRECISION): MapAction? =
        openBank(listOf(OpenBankChooser.point(p.name, p.at, action, p.group, elevFt = p.elevFt)))

    /**
     * **Add to Open bank…** (1.3.9): the chooser of STPT 81-99 ([OpenBankChooser]) over [choices], the first the
     * default; [start] a slot to open on (a bank steerpoint changing its type). Placed, the new steerpoint's card shows.
     */
    internal fun openBank(choices: List<OpenBankChooser.Choice>, start: Int? = null, label: String = OpenBankChooser.LABEL): MapAction? {
        if (!d.loaded || choices.isEmpty()) return null
        return MapAction(label) {
            OpenBankChooser(dtc, choices, x.groundTheater, { at -> where(at).firstOrNull().orEmpty() }, start) { n, msg ->
                ui.status(msg)
                ui.select(MapPick.Stpt(n))
            }.open()
            null
        }
    }

    /** The bank chooser for what [p] is, as its card offers it (the phone's list opens it on a long press); null when it offers none. */
    fun openBankFor(p: MapPick): MapAction? = runCatching { card(p)?.actions?.firstOrNull { it.label == OpenBankChooser.LABEL } }.getOrNull()

    /** A field within 2 nm of [at] (the right-click's own airfield): the one nearest. */
    private fun fieldNear(at: Pt): Airport? =
        (d.fields.map { it.airport } + x.airports).distinctBy { it.id }
            .map { it to Pt(it.x, it.y).dist(at) }.filter { it.second <= 2 * DtcFromMission.NM }.minByOrNull { it.second }?.first

    private fun addHarpoon(p: Place): MapAction? {
        val n = nextHarpoon() ?: return null
        return if (!d.loaded) null else MapAction("Harpoon STPT $n") { answer(dtc.placeSteerpoint(n, p, ask = true, done = done)) }
    }

    private fun addTarget(p: Place): MapAction? = if (!d.loaded) null else MapAction("Add to targets") { dtc.addTargets(listOf(p)) }

    /** "A point here instead…": the point card at an item's place (a finger's right-click). */
    private fun pointHere(at: Pt): MapAction = MapAction("A point here instead…") { ui.select(MapPick.Point(at)); null }

    /** A question on screen answers later (through [done]); nothing to say now. */
    private fun answer(s: String): String? = if (dtc.asked(s)) null else s

    private fun lineActions(opt: DtcFromMission.LineOption): List<MapAction> {
        if (!d.loaded) return emptyList()
        val free = d.free?.lines.orEmpty()
        val order = free + (1..4).filter { it !in free }
        return order.take(if (free.isEmpty()) 4 else free.size.coerceAtMost(2)).mapIndexed { i, k ->
            MapAction(if (k in free) "Add as line $k" else "As line $k (replace)", primary = i == 0) { answer(dtc.placeLine(k, opt, ask = true, done = done)) }
        }
    }

    /** WDP's "tanker track into Line n": the station box, four corners and the first again. */
    private fun orbitActions(st: PlannerIntel.Station?): List<MapAction> {
        if (!d.loaded || st == null) return emptyList()
        val opt = PlannerIntel.orbitLine(st)
        val free = d.free?.lines.orEmpty()
        val k = free.firstOrNull() ?: 4
        return listOf(MapAction("Orbit box as line $k" + if (k in free) "" else " (replace)") { answer(dtc.placeLine(k, opt, ask = true, done = done)) })
    }

    private fun tacanFor(callsign: String): DtcFromMission.TacanChoice? =
        d.facts.tacans.firstOrNull { it.domain == 1 && it.label.startsWith("$callsign ", ignoreCase = true) }

    private fun time(ms: Long?): String? = ms?.takeIf { it > 0 }?.let { PlannerIntel.hhmm(it) }

    /** The card for [p], or null when it names nothing the page still has. */
    fun card(p: MapPick): MapCard? = when (p) {
        is MapPick.Stpt -> stptCard(p.n)
        is MapPick.Ppt -> pptCard(p.slot)
        is MapPick.LinePt -> lineCard(p.line, p.k)
        is MapPick.Offset -> offsetCard(p)
        MapPick.Bullseye -> d.bull?.let { b ->
            MapCard(
                "Bullseye", true, "What the HSD shows",
                listOf("The campaign's bullseye. The cartridge carries no bullseye position in 4.38.1 (only whether the MFDs show bullseye information), so the jet takes it from the mission.") + where(b).take(1),
                emptyList(), Hud.Cyan,
            )
        }
        is MapPick.Track -> trackCard(p.callsign)
        is MapPick.Station -> stationCard(p.callsign)
        is MapPick.Site -> siteCard(p.index)
        is MapPick.Field -> fieldCard(p.id)
        MapPick.Attack -> d.attack?.let { a ->
            val page = WdpPage.entries.firstOrNull { it.label == a.page }
            MapCard(
                "Attack (${a.page} page)", false, "Not in your DTC",
                listOf(
                    AttackDrawing.caption(a),
                    a.cues.joinToString("  ") { it.label },
                    "The Planner's current attack, worked out on the ${a.page} page and not in the cartridge's nav offsets. That page's Save to DTC puts them there; the HSD then shows them.",
                ),
                listOfNotNull(page?.let { MapAction("Open the ${a.page} page", primary = true) { ui.openPage(it); null } }), AttackMagenta,
            )
        }
        is MapPick.Point -> pointCard(p.at)
        is MapPick.GroundUnit -> unitCard(p.id)
        is MapPick.Radar -> radarCard(p.id)
        is MapPick.Navaid -> navaidCard(p.i)
        is MapPick.PackageWp -> packageCard(p.flight, p.k)
        is MapPick.Jstar -> jstarCard(p.callsign)
        is MapPick.SideStation -> sideStationCard(p.callsign)
        is MapPick.LiveShip -> liveShipCard(p.i)
    }

    // ---------------------------------------------------------------- what the hover box says (and a card's first lines)

    /**
     * WDP's info label: what the pointer rests on, in a few lines, the first its name. The cards of the same things
     * start with the same facts, so the two cannot drift.
     */
    fun hoverFacts(p: MapPick): List<String> = runCatching { facts(p) }.getOrDefault(emptyList())

    private fun facts(p: MapPick): List<String> = when (p) {
        is MapPick.Stpt -> {
            val s = d.view(WdpMapView.viewing).stpt(p.n)
            if (s == null) emptyList() else {
                val w = mission?.flight?.route?.withIndex()?.firstOrNull { (i, w) -> (if (w.n > 0) w.n else i + 1) == p.n }?.value
                listOfNotNull(
                    "STPT ${p.n}" + (s.name?.let { " · $it" } ?: ""),
                    DataCardPlan.actionString(s.action) + (if (s.elevFt != 0.0) "  ${thousands(s.elevFt.toLong())} ft" else "") + (time(w?.arriveMs)?.let { "  $it" } ?: ""),
                    positionText(coords, s.at),
                    "From " + (if (s.inDtc) "your DTC" else s.from.ifBlank { "the mission" }),
                )
            }
        }
        is MapPick.LinePt -> listOf("L${p.line} point ${p.k + 1}") + (d.shown.line(p.line)?.name?.let { listOf(it) } ?: emptyList())
        is MapPick.Ppt -> d.shown.ppt(p.slot)?.let { pp ->
            listOfNotNull(
                "PPT ${p.slot} · ${pp.name}",
                if (pp.marker) "Marker (${pp.code})" else "Ring ${PlannerMap.miles(pp.rangeFt)} · code ${pp.code}",
                positionText(coords, pp.at),
                groundText(x.groundTheater, pp.at).takeIf { it != "—" }?.let { "Ground $it" },
            )
        }.orEmpty()
        is MapPick.Site -> d.known.sites.getOrNull(p.index)?.let { s ->
            val u = x.siteUnit[s.site.at]
            if (u != null) unitFacts(u) else listOfNotNull(s.site.name, s.ringFt?.let { "Ring ${PlannerMap.miles(it)}" }, "From ${s.site.source}", positionText(coords, s.site.at))
        }.orEmpty()
        is MapPick.GroundUnit -> x.units[p.id]?.let { unitFacts(it) }.orEmpty()
        is MapPick.Radar -> x.radars[p.id]?.let { r ->
            listOfNotNull(
                r.name, r.type.ifBlank { null }, sideWord(r.side) + " · " + (if (r.working) "working" else "destroyed") + " (${r.intact} of ${r.radars} radars)",
                positionText(coords, Pt(r.x, r.y)),
            )
        }.orEmpty()
        is MapPick.Field -> fieldFacts(p.id)
        is MapPick.Navaid -> x.navaids.getOrNull(p.i)?.let { n ->
            listOfNotNull(
                n.name + (n.tacan?.let { " · TACAN ${it.channel}${it.band}" } ?: ""),
                n.tacan?.rangeNm?.let { "Range $it nm" },
                positionText(coords, Pt(n.x, n.y)),
            )
        }.orEmpty()
        is MapPick.PackageWp -> {
            val r = x.packageRoutes[p.flight]
            val w = r?.getOrNull(p.k)
            val row = x.packageRows[p.flight]
            if (w == null) emptyList() else listOfNotNull(
                (row?.callsign ?: "Package flight") + " · STPT ${if (w.n > 0) w.n else p.k + 1}",
                DataCardPlan.actionString(w.action) + (time(w.arriveMs)?.let { "  $it" } ?: ""),
                positionText(coords, Pt(w.x, w.y)),
            )
        }
        is MapPick.Jstar -> x.intel?.jstars?.firstOrNull { it.callsign == p.callsign }?.let { j ->
            listOfNotNull(
                j.callsign + (j.aircraft?.let { " · $it" } ?: ""),
                "On station ${PlannerIntel.hhmm(j.from)} - ${PlannerIntel.hhmm(j.to)}" + if (j.active) " (now)" else "",
                "Sees ${j.rangeNm} nm round its station",
            )
        }.orEmpty()
        is MapPick.Track, is MapPick.Station, is MapPick.SideStation -> {
            val call = when (p) { is MapPick.Track -> p.callsign; is MapPick.Station -> p.callsign; is MapPick.SideStation -> p.callsign; else -> "" }
            val st = x.stations.firstOrNull { it.support.callsign == call }
            val sup = d.known.tracks.firstOrNull { it.support.callsign == call }?.support
            listOfNotNull(
                "$call · " + (st?.support?.role ?: sup?.role ?: "support") + (st?.support?.aircraft?.let { " ($it)" } ?: ""),
                (st?.support?.tacan ?: sup?.tacan)?.let { "TACAN $it" },
                st?.let { "On station ${PlannerIntel.hhmm(it.from)} - ${PlannerIntel.hhmm(it.to)}" },
            )
        }
        is MapPick.LiveShip -> x.liveShips.getOrNull(p.i)?.let { listOf(it.name, "Hostile ship (the Tacview feed)", positionText(coords, it.at)) }.orEmpty()
        is MapPick.Offset, MapPick.Bullseye, MapPick.Attack, is MapPick.Point -> card(p)?.let { listOf(it.title) + it.lines.take(2) }.orEmpty()
    }

    private fun sideWord(side: String) = when (side) { "friendly" -> "Own side"; "hostile" -> "Hostile"; "neutral" -> "Neutral"; else -> "Side unknown" }

    /** A unit's facts: system, name, side, how it is seen, its radar, whether it moves, where. */
    private fun unitFacts(u: com.bmscompanion.app.data.mission.CampMapUnit): List<String> {
        val seen = PlannerIntel.seenHow(u, x.clock, x.intel?.jstars.orEmpty())
        val radar = when (u.radar) {
            "alive" -> "radar working"
            "dead" -> "radar destroyed"
            "none" -> "no radar (guns or MANPADS)"
            else -> null
        }
        return listOfNotNull(
            PlannerIntel.threatType(u).ifBlank { unitKindLabel(u.kind) },
            u.name,
            sideWord(u.side) + " " + unitKindLabel(u.kind).lowercase() + (seen?.let { " · $it" } ?: if (u.side == "hostile") " · not seen by your side" else ""),
            listOfNotNull(radar.takeIf { PlannerIntel.airDefence(u) }, "on the move".takeIf { u.moving }, "destroyed".takeIf { u.dead },
                u.alive?.let { a -> u.total?.let { t -> "$a of $t vehicles" } }).joinToString(" · ").ifBlank { null },
            positionText(coords, Pt(u.x, u.y)),
        )
    }

    private fun fieldFacts(id: Int): List<String> {
        val a = d.fields.firstOrNull { it.airport.id == id }?.airport ?: x.airports.firstOrNull { it.id == id } ?: return emptyList()
        val f = x.fieldOf[id]
        val fr = a.freqs
        val out = ArrayList<String>()
        out += a.name + (a.icao?.let { " ($it)" } ?: "") + (a.elevationFt?.let { " · ${thousands(it.toLong())} ft" } ?: "")
        f?.let { out += sideWord(it.side) + (if (it.owner != it.startOwner) " (taken since the campaign began)" else "") }
        listOfNotNull(
            fr?.atisVhf?.let { "ATIS $it" }, fr?.groundUhf?.let { "GND $it" },
            (fr?.towerUhf ?: fr?.towerVhf)?.let { "TWR " + listOfNotNull(fr?.towerUhf, fr?.towerVhf).joinToString("/") },
            fr?.approachUhf?.let { "APP $it" }, fr?.opsUhf?.let { "OPS $it" }, fr?.lsoUhf?.let { "LSO $it" },
        ).takeIf { it.isNotEmpty() }?.let { out += it.joinToString("  ") }
        a.tacan?.let { out += "TACAN ${it.channel}${it.band}" + (it.rangeNm?.let { r -> " · $r nm" } ?: "") }
        val rw = a.runways.flatMap { it.ends }.filter { it.designator.isNotBlank() }.take(4)
        if (rw.isNotEmpty()) out += "RWY " + rw.joinToString(", ") { e -> e.designator + (e.ils?.takeIf { it.isNotBlank() }?.let { " ILS $it" } ?: "") }
        f?.squadrons?.takeIf { it.isNotEmpty() }?.let { sq ->
            out += "Squadrons: " + sq.groupBy { s -> if (s.side == "friendly") (s.name ?: s.aircraft) else s.aircraft }
                .map { (k, v) -> if (v.size > 1) "$k ×${v.size}" else k }.joinToString(", ")
        }
        return out
    }

    // ---------------------------------------------------------------- the cartridge's things

    private fun stptCard(n: Int): MapCard? {
        val view = WdpMapView.viewing
        val s = d.view(view).stpt(n) ?: return null
        val lines = ArrayList<String>()
        lines += when {
            view == 2 -> "The mission file's STPT $n (${s.from}): read only here; Put in the DTC makes it the cartridge's."
            s.open -> "Open steerpoint (${if (n <= 89) "Open 1" else "Open 2"} tab), ${DataCardPlan.actionString(s.action)}: the HSD marks it with a cross."
            s.inDtc && !s.onRoute -> "A precision steerpoint in your cartridge (not joined to the route)."
            s.inDtc -> "In your cartridge; the jet takes it over its own route's."
            else -> "From ${s.from}: Falcon BMS 4.38.1 keeps the flight plan in the mission file, so the jet has it without the cartridge."
        }
        if (s.elevFt != 0.0) lines += (if (s.inDtc && !s.onRoute || s.open) "Elevation " else "Altitude ") + "${s.elevFt.toInt()} ft"
        lines += where(s.at)
        val acts = ArrayList<MapAction>()
        if (view == 2) {
            if (d.loaded) acts += MapAction("Put in the DTC", primary = true) {
                answer(dtc.placeSteerpoint(n, Place(s.name ?: "STPT $n", s.at, s.elevFt, "Steerpoints"), ask = true, done = done))
            }
        } else {
            if (d.loaded) acts += MapAction("Move", primary = true) { ui.move(MapPick.Stpt(n)); "Tap the map where STPT $n goes." }
            if (d.loaded && s.inDtc) acts += MapAction("Take out of the DTC") { removeStpt(n) }
            // a bank steerpoint: its type (or slot) changed in the chooser, the point where it is
            if (s.open && s.inDtc) openBank(listOf(OpenBankChooser.point(s.name ?: "STPT $n", s.at, s.action, "Steerpoints", elevFt = s.elevFt)), start = n, label = "Change type…")?.let { acts += it }
            if (!s.inDtc && d.loaded && DtcFromMission.routeFill(dtc.model!!, d.facts.route).isNotEmpty())
                acts += MapAction("Put the flight plan in the DTC") { dtc.fillRoute(d.facts) }
            acts += MapAction("Centre the HSD here") { ui.centre(n); null }
        }
        acts += pointHere(s.at)
        return MapCard("STPT $n" + (s.name?.let { " · $it" } ?: ""), s.inDtc || !s.open, if (s.inDtc) "In your DTC" else "What the HSD shows (BMS's route)", lines, acts, if (s.target) Hostile else Hud.Amber)
    }

    private fun removeStpt(n: Int): String {
        val onPlan = d.facts.route.any { it.n == n }
        dtc.placeSteerpoint(n, Place("Not set", Pt(0.0, 0.0), 0.0, ""), ask = false)
        ui.select(null)
        return "STPT $n taken out of the cartridge" + (if (onPlan) ": the jet flies BMS's own STPT $n." else ".") + " Save to DTC writes it (and asks first)."
    }

    private fun pptCard(slot: Int): MapCard? {
        val p = d.shown.ppt(slot) ?: return null
        val lines = ArrayList<String>()
        lines += if (p.marker) "A marker (${p.code.ifBlank { "no code" }}): the HSD shows the point, no ring." else "Ring ${PlannerMap.miles(p.rangeFt)} · type ${p.name} (code ${p.code})"
        if (!p.marker) {
            val dr = DtcFromMission.distanceToRoute(p.at, d.shown.route.map { it.at })
            if (dr != null) lines += if (dr <= p.rangeFt) "Your route crosses this ring." else "The ring's edge is ${PlannerMap.miles(dr - p.rangeFt)} from your route."
        }
        groundText(x.groundTheater, p.at).takeIf { it != "—" }?.let { lines += "Ground $it" }
        lines += where(p.at)
        val acts = ArrayList<MapAction>()
        if (d.loaded) {
            acts += MapAction("Move", primary = true) { ui.move(MapPick.Ppt(slot)); "Tap the map where PPT $slot goes." }
            acts += MapAction("Change…") { dtc.editPpt(slot); null }
            acts += MapAction("Take out of the DTC") { removePpt(slot) }
        }
        acts += pointHere(p.at)
        return MapCard("PPT $slot · ${p.name}", true, "In your DTC", lines, acts, if (p.marker) Friendly else SamRed)
    }

    private fun removePpt(slot: Int): String {
        dtc.placePpt(slot, DtcFromMission.PptOption("", DtcFromMission.PptKind.THREAT, "", "", 0.0, Pt(0.0, 0.0), 0.0, ""), ask = false)
        // an empty slot as BMS writes one
        dtc.model?.ppt?.getOrNull(slot - 56)?.let { it.falconZ = 0f; it.code = ""; it.name = "" }
        ui.select(null)
        return "PPT $slot taken out of the cartridge. Save to DTC writes it (and asks first)."
    }

    private fun lineCard(line: Int, k: Int): MapCard? {
        val l = d.shown.line(line) ?: return null
        val at = l.points.getOrNull(k) ?: return null
        val lines = ArrayList<String>()
        lines += "Point ${k + 1} of ${l.points.size} (STPT ${30 + (line - 1) * 6 + k + 1}). The HSD draws the line dashed."
        l.name?.let { lines += "Laid from the mission: $it" }
        lines += where(at)
        val acts = ArrayList<MapAction>()
        if (d.loaded) {
            acts += MapAction("Move point", primary = true) { ui.move(MapPick.LinePt(line, k)); "Tap the map where point ${k + 1} of line $line goes." }
            acts += MapAction("Take the point out") { editLine(line, l.points.filterIndexed { i, _ -> i != k }, l.name) }
            acts += MapAction("Take line $line out") { editLine(line, emptyList(), l.name) }
        }
        acts += pointHere(at)
        return MapCard("Line $line" + (l.name?.let { " · $it" } ?: ""), true, "In your DTC", lines, acts, LineInkMap)
    }

    /** Line [line] laid along [pts]: the DTC page's own call, the line named as WDP's page names one. */
    private fun editLine(line: Int, pts: List<Pt>, name: String?): String {
        val label = when {
            pts.isEmpty() -> "No Line"
            name != null -> "$name, edited"
            else -> "Random Line"
        }
        dtc.placeLine(line, DtcFromMission.LineOption(label, "Line $line", DtcFromMission.LineKind.ROUTE, pts, "the Map page"), ask = false)
        ui.select(null)
        return if (pts.isEmpty()) "Line $line taken out of the cartridge. Save to DTC writes it." else "Line $line: ${pts.size} points. Save to DTC writes it."
    }

    private fun offsetCard(p: MapPick.Offset): MapCard? {
        val o = d.shown.offsets.firstOrNull { it.label == p.label && it.stpt == p.stpt } ?: return null
        return MapCard(
            o.label + (if (o.kind == AttackCue.Kind.TARGET) " (${d.shown.offsetMode} mode)" else ""), true, "In your DTC (NAV OFFSETS)",
            listOf(o.note, "The attack pages' Save to DTC sets these; the DED shows them as the jet lays them out.") + where(o.at).take(1),
            emptyList(), AttackMagenta,
        )
    }

    // ---------------------------------------------------------------- support

    private fun trackCard(callsign: String): MapCard? {
        val t = d.known.tracks.firstOrNull { it.support.callsign == callsign } ?: return null
        val s = t.support
        val lines = ArrayList<String>()
        lines += "${s.role} ${s.callsign}" + (if (s.yours) " (yours)" else "") + ": the campaign's planned track, ${t.points.size} points" +
            (if (t.legs.size >= 2) ", holding on ${t.legs.size - 1} leg" + (if (t.legs.size > 2) "s" else "") else "") + "."
        s.tacan?.let { lines += "TACAN $it" + (s.tieOn?.let { x -> " · tie on $x" } ?: "") }
        x.stations.firstOrNull { it.support.callsign == callsign }?.let { lines += "On station ${PlannerIntel.hhmm(it.from)} - ${PlannerIntel.hhmm(it.to)}" }
        lines += if (t.inLine != null) "In your DTC as line ${t.inLine}." else "Not in your DTC: as a line, the HSD draws it."
        val acts = ArrayList<MapAction>()
        if (t.inLine == null) t.line?.let { acts += lineActions(it) }
        else acts += MapAction("Show line ${t.inLine}") { ui.select(MapPick.LinePt(t.inLine, 0)); null }
        // a track with a station leg is laid as its box already (Add as line); the orbit box only where it is not
        if (t.line == null || DtcFromMission.stationLeg(s) == null) acts += orbitActions(x.stations.firstOrNull { it.support.callsign == callsign } ?: t.legs.takeIf { it.size >= 2 }?.let {
            PlannerIntel.Station(com.bmscompanion.app.data.mission.CampSupport(s.role, s.callsign), it.first(), it.last(), 0, 0)
        })
        d.known.stations.firstOrNull { it.support.callsign == callsign }?.let { st ->
            if (st.inPpt == null) st.option?.let { o -> addPpt(o)?.let { a -> acts += MapAction("Station as " + a.label.removePrefix("Add as "), run = a.run) } }
        }
        tacanFor(callsign)?.let { c -> if (d.loaded) acts += MapAction("TACAN ${c.short.substringAfter(' ')}") { dtc.setTacan(c) } }
        return MapCard("${s.callsign} track", t.inLine != null, if (t.inLine != null) "In your DTC as line ${t.inLine}" else "Not in your DTC", lines, acts, supportInk(s.role))
    }

    private fun stationCard(callsign: String): MapCard? {
        val st = d.known.stations.firstOrNull { it.support.callsign == callsign } ?: return null
        val s = st.support
        val lines = ArrayList<String>()
        lines += "${s.role} ${s.callsign}" + (if (s.yours) " (yours)" else "") + ": station from ${st.support.stationFrom ?: "the mission"}."
        s.tacan?.let { lines += "TACAN $it" + (s.tieOn?.let { x -> " · tie on $x" } ?: "") }
        lines += when {
            st.inPpt != null -> "In your DTC as PPT ${st.inPpt}."
            st.option == null -> "The theater's PPT table has no marker for a ${s.role.lowercase()}."
            else -> "Not in your DTC: as a PPT it is a marker on the HSD (${st.option.code})."
        }
        lines += where(st.at)
        val acts = ArrayList<MapAction>()
        if (st.inPpt == null) st.option?.let { o -> addPpt(o)?.let { acts += it } }
        else acts += MapAction("Show PPT ${st.inPpt}") { ui.select(MapPick.Ppt(st.inPpt)); null }
        addStpt(Place("${s.callsign} (${s.role}) station", st.at, null, DtcMissionFacts.STATIONS))?.let { acts += it }
        addOpen(Place("${s.callsign} (${s.role}) station", st.at, null, DtcMissionFacts.STATIONS), OpenBankChooser.NAV)?.let { acts += it }
        tacanFor(callsign)?.let { c -> if (d.loaded) acts += MapAction("TACAN ${c.short.substringAfter(' ')}") { dtc.setTacan(c) } }
        acts += orbitActions(x.stations.firstOrNull { it.support.callsign == callsign })
        return MapCard("${s.callsign} station", st.inPpt != null, if (st.inPpt != null) "In your DTC as PPT ${st.inPpt}" else "Not in your DTC", lines, acts, supportInk(s.role))
    }

    /** A tanker, AWACS or JSTARS of the side, not the package's: WDP's station box. */
    private fun sideStationCard(callsign: String): MapCard? {
        val st = x.stations.firstOrNull { it.support.callsign == callsign } ?: return null
        val s = st.support
        val lines = hoverFacts(MapPick.SideStation(callsign)).drop(1).toMutableList()
        lines += "WDP's station box: 30,000 ft either side of its station leg."
        lines += where(st.mid)
        val acts = ArrayList<MapAction>()
        acts += orbitActions(st)
        s.tacan?.dropLast(1)?.toIntOrNull()?.let { ch ->
            if (d.loaded && ch in 1..126) {
                val tie = DtcFromMission.tieOn(ch)
                acts += MapAction("TACAN ${tie}Y A/A") {
                    dtc.setTacan(DtcFromMission.TacanChoice("${s.callsign} (${s.role.lowercase()}) ${s.tacan}: set ${tie}Y A/A TR", "${s.callsign} ${tie}Y A/A", tie, 1, 1))
                }
            }
        }
        addStpt(Place("${s.callsign} (${s.role}) station", st.mid, null, DtcMissionFacts.STATIONS))?.let { acts += it }
        addOpen(Place("${s.callsign} (${s.role}) station", st.mid, null, DtcMissionFacts.STATIONS), OpenBankChooser.NAV)?.let { acts += it }
        return MapCard("${s.callsign} · ${s.role}", false, "Not in your DTC", lines, acts, supportInk(s.role))
    }

    private fun jstarCard(callsign: String): MapCard? {
        val j = x.intel?.jstars?.firstOrNull { it.callsign == callsign } ?: return null
        val lines = hoverFacts(MapPick.Jstar(callsign)).drop(1) + "Hostile units inside its ${j.rangeNm} nm count as seen while it is on station." + where(Pt(j.x, j.y))
        return MapCard("${j.callsign} · JSTARS", null, if (j.active) "On station" else "Not on station now", lines, listOf(pointHere(Pt(j.x, j.y))), MapInk.jstar)
    }

    // ---------------------------------------------------------------- what the mission knows

    private fun siteCard(i: Int): MapCard? {
        val s = d.known.sites.getOrNull(i) ?: return null
        val u = x.siteUnit[s.site.at]
        val lines = ArrayList<String>()
        if (u != null) lines += unitFacts(u).drop(1).dropLast(1)
        lines += when {
            s.option != null -> "Ring ${PlannerMap.miles(s.option.rangeFt)}: the theater's PPT type ${s.option.typeName} (code ${s.option.code})."
            s.ringFt != null -> "Ring ${PlannerMap.miles(s.ringFt)} (the threat reference). The theater's PPT table has no type for it, so it cannot be a PPT ring."
            else -> "No reach known for this system."
        }
        s.edgeFt?.let { lines += if (it <= 0.0) "Your route crosses this ring." else "The ring's edge is ${PlannerMap.miles(it)} from your route." }
        lines += "From ${s.site.source}."
        if (s.inPpt != null) lines += "In your DTC as PPT ${s.inPpt}."
        lines += where(s.site.at)
        val place = Place(s.site.name, s.site.at, s.site.elevFt.takeIf { it != 0.0 }, DtcMissionFacts.AIR_DEFENCES)
        val acts = ArrayList<MapAction>()
        if (s.inPpt != null) acts += MapAction("Show PPT ${s.inPpt}", primary = true) { ui.select(MapPick.Ppt(s.inPpt)); null }
        else s.option?.let { o -> addPpt(o)?.let { acts += it } }
        if (s.inPpt == null) pptWindow(s.site.at, s.option?.code ?: u?.let { x.codeOf(PlannerIntel.threatType(it)) }, s.option?.typeName)?.let { acts += it }
        addOpen(place)?.let { acts += it }
        addTarget(place)?.let { acts += it }
        acts += pointHere(s.site.at)
        return MapCard(s.site.name, s.inPpt != null, if (s.inPpt != null) "In your DTC as PPT ${s.inPpt}" else "Not in your DTC", lines, acts, MapInk.threat)
    }

    /** A unit off the threat layer: a SAM with no radar, one of the own side, a ground unit, a ship, what the side has not seen. */
    private fun unitCard(id: String): MapCard? {
        val u = x.units[id] ?: return null
        val at = Pt(u.x, u.y)
        val facts = unitFacts(u)
        val lines = ArrayList<String>(facts.drop(1).dropLast(1))
        val type = PlannerIntel.threatType(u)
        if (PlannerIntel.airDefence(u)) x.ringOf(type)?.let { lines += "Ring ${PlannerMap.miles(it)}" }
        lines += where(at)
        val sea = u.domain == "sea"
        val place = Place(u.name ?: type.ifBlank { unitKindLabel(u.kind) }, at, null, if (sea) DtcMissionFacts.SHIPS else DtcMissionFacts.AIR_DEFENCES)
        val acts = ArrayList<MapAction>()
        if (PlannerIntel.airDefence(u)) {
            val code = x.codeOf(type)
            val ring = x.ringOf(type)
            if (code != null && ring != null && u.side == "hostile") {
                addPpt(DtcFromMission.PptOption(shortSystem(type), DtcFromMission.PptKind.THREAT, code, shortSystem(type), ring, at, 0.0, INTEL_SITES))?.let { acts += it }
            }
            pptWindow(at, code, null)?.let { acts += it }
        }
        if (sea) addHarpoon(place)?.let { acts += it } else addOpen(place)?.let { acts += it }
        addTarget(place)?.let { acts += it }
        acts += pointHere(at)
        val ink = when {
            u.dead -> MapInk.dead
            u.side == "friendly" -> MapInk.ownSam
            u.radar == "dead" -> MapInk.noRadar
            !PlannerIntel.seen(u) -> MapInk.unspotted
            else -> MapInk.threat
        }
        return MapCard(facts.first(), false, sideWord(u.side), lines, acts, ink)
    }

    private fun radarCard(id: String): MapCard? {
        val r = x.radars[id] ?: return null
        val at = Pt(r.x, r.y)
        val lines = hoverFacts(MapPick.Radar(id)).drop(1).dropLast(1).toMutableList()
        lines += "Ring ${PlannerIntel.SEARCH_NM.toInt()} nm (75 with S Large): WDP's figure, since BMS publishes no search-radar range."
        lines += where(at)
        val place = Place(r.name, at, null, "Search radars")
        val acts = ArrayList<MapAction>()
        pptWindow(at, null, null)?.let { acts += it }
        addOpen(place)?.let { acts += it }
        addTarget(place)?.let { acts += it }
        acts += pointHere(at)
        return MapCard(r.name, false, "Search radar", lines, acts, MapInk.search)
    }

    private fun fieldCard(id: Int): MapCard? {
        val mine = d.fields.firstOrNull { it.airport.id == id }
        val a = mine?.airport ?: x.airports.firstOrNull { it.id == id } ?: return null
        val at = Pt(a.x, a.y)
        val lines = ArrayList<String>()
        if (mine != null) lines += "Your ${mine.role} field."
        lines += fieldFacts(id).drop(1)
        lines += where(at)
        val acts = ArrayList<MapAction>()
        addStpt(Place(a.name + (mine?.let { " (${it.role})" } ?: ""), at, (a.elevationFt ?: 0).toDouble(), if (mine != null) DtcMissionFacts.YOUR_BASES else "Airbases"))?.let { acts += it }
        // an alternate into STPT 81-99 as Land (the field's name with its ICAO, its elevation)
        openBank(listOf(OpenBankChooser.field(a)))?.let { acts += it }
        if (d.loaded) {
            val tacan = mine?.tacans?.firstOrNull() ?: a.tacan?.let { t ->
                DtcFromMission.TacanChoice("${a.name} ${t.channel}${t.band.uppercase()}", "${a.name.take(14)} ${t.channel}${t.band.uppercase()}", t.channel, if (t.band.equals("Y", true)) 1 else 0, 0)
            }
            tacan?.let { c -> acts += MapAction("TACAN ${a.tacan?.label ?: c.channel.toString()}") { dtc.setTacan(c) } }
            mine?.ils?.take(2)?.forEach { c -> acts += MapAction("ILS ${c.short.substringAfterLast(' ')}") { dtc.setIls(c) } }
        }
        acts += MapAction("Charts…") { openCharts(a); null }
        acts += pointHere(at)
        return MapCard(a.name, null, mine?.let { "Your ${it.role} field" } ?: airportKind(a), lines, acts, if (mine != null) Hud.Green else MapInk.side(x.fieldOf[id]?.side, Hud.TextDim))
    }

    /** The field's charts: the app's own ground chart and the theater's instrument charts (the DTC page's window). */
    private fun openCharts(a: Airport) {
        val t = mission?.theater
        chartScope.launch {
            val list = t?.let { runCatching { Repo.charts(it.airportSet)[a.id.toString()] }.getOrNull() }.orEmpty()
            val field = t?.airfieldSet?.let { set -> runCatching { Repo.airfield(set, a.id) }.getOrNull() }
            val w = DtcChartWindow(a, field, list)
            if (w.isEmpty) WdpDialogs.message("Charts", "Falcon BMS's data has no layout of ${a.name} to draw, and there are no instrument charts of it.")
            else w.open()
        }
    }

    private fun navaidCard(i: Int): MapCard? {
        val n = x.navaids.getOrNull(i) ?: return null
        val at = Pt(n.x, n.y)
        val lines = hoverFacts(MapPick.Navaid(i)).drop(1).dropLast(1) + where(at)
        val acts = ArrayList<MapAction>()
        n.tacan?.let { t ->
            if (d.loaded) acts += MapAction("TACAN ${t.channel}${t.band}", primary = true) {
                dtc.setTacan(DtcFromMission.TacanChoice("${n.name} ${t.channel}${t.band.uppercase()}", "${n.name.take(14)} ${t.channel}${t.band.uppercase()}", t.channel, if (t.band.equals("Y", true)) 1 else 0, 0))
            }
        }
        addStpt(Place(n.name, at, null, "Navaids"))?.let { acts += it }
        addOpen(Place(n.name, at, null, "Navaids"), OpenBankChooser.NAV)?.let { acts += it }
        acts += pointHere(at)
        return MapCard(n.name, null, "VORTAC", lines, acts, MapInk.onc)
    }

    private fun packageCard(flight: String, k: Int): MapCard? {
        val w = x.packageRoutes[flight]?.getOrNull(k) ?: return null
        val at = Pt(w.x, w.y)
        val lines = hoverFacts(MapPick.PackageWp(flight, k)).drop(1).dropLast(1) + where(at)
        val acts = ArrayList<MapAction>()
        val row = x.packageRows[flight]
        addStpt(Place("${row?.callsign ?: "Package"} STPT ${if (w.n > 0) w.n else k + 1}", at, w.altFt, "Package"))?.let { acts += it }
        addOpen(Place("${row?.callsign ?: "Package"} STPT ${if (w.n > 0) w.n else k + 1}", at, null, "Package"), OpenBankChooser.NAV)?.let { acts += it }
        addTarget(Place("${row?.callsign ?: "Package"} STPT ${if (w.n > 0) w.n else k + 1}", at, null, "Package"))?.let { acts += it }
        acts += pointHere(at)
        return MapCard((row?.callsign ?: "Package flight") + " · STPT ${if (w.n > 0) w.n else k + 1}", false, "Your package", lines, acts, MapInk.pack)
    }

    private fun liveShipCard(i: Int): MapCard? {
        val s = x.liveShips.getOrNull(i) ?: return null
        val acts = ArrayList<MapAction>()
        addHarpoon(s)?.let { acts += it }
        addOpen(s)?.let { acts += it }
        addTarget(s)?.let { acts += it }
        acts += pointHere(s.at)
        return MapCard(s.name, false, "Hostile ship", listOf("From the Tacview feed: where it is now.") + where(s.at), acts, Hostile)
    }

    private fun pointCard(at: Pt): MapCard {
        val lines = ArrayList<String>()
        lines += where(at)
        groundText(x.groundTheater, at).takeIf { it != "—" }?.let { lines += "Ground $it" }
        d.shown.stpts.minByOrNull { it.at.dist(at) }?.let { s -> lines += "STPT ${s.n} " + PlannerMap.braText(s.at, at) }
        val acts = ArrayList<MapAction>()
        val place = Place("Map point", at, null, "Map")
        addStpt(place)?.let { acts += MapAction(it.label, primary = true, run = it.run) }
        // the Open bank (STPT 81-99): a right-click on an airfield offers the field itself first (Land), then the point (Nav)
        openBank(listOfNotNull(fieldNear(at)?.let { OpenBankChooser.field(it) }, OpenBankChooser.point("Map point", at, OpenBankChooser.NAV, "Map", label = "This point")))?.let { acts += it }
        pptWindow(at, null, null, "PPT here…")?.let { acts += it }
        if (d.loaded) {
            for (l in 1..4) {
                val pts = d.shown.line(l)?.points.orEmpty()
                if (pts.isEmpty()) {
                    if ((1 until l).all { d.shown.line(it) != null }) acts += MapAction("Start line $l") { editLine(l, listOf(at), null) }
                } else if (pts.size < 6) acts += MapAction("Add to line $l (${pts.size + 1}/6)") { editLine(l, pts + at, d.shown.line(l)?.name) }
            }
        }
        addTarget(place)?.let { acts += it }
        return MapCard("A point on the map", false, "Not in your DTC", lines, acts, Hud.TextDim)
    }

    /** A cartridge item put where the pilot tapped ([WdpMapView.moving]). */
    fun moveTo(p: MapPick, at: Pt): String? {
        ui.move(null)
        return when (p) {
            is MapPick.Stpt -> {
                val s = d.shown.stpt(p.n) ?: return null
                // an Open bank steerpoint keeps its type (an alternate stays Land); STPT 1-24 go in as Precision, which the jet takes
                answer(dtc.placeSteerpoint(p.n, Place(s.name ?: "STPT ${p.n}", at, s.elevFt, ""), ask = false, action = if (s.open && s.inDtc) s.action else -1))
            }
            is MapPick.Ppt -> {
                val o = d.shown.ppt(p.slot) ?: return null
                val m = dtc.model?.ppt?.getOrNull(p.slot - 56)
                dtc.placePpt(p.slot, DtcFromMission.PptOption(o.name, DtcFromMission.PptKind.THREAT, o.code, o.name, o.rangeFt, at, abs((m?.falconZ ?: 0f).toDouble()), "the Map page"), ask = false)
                "PPT ${p.slot} moved. Save to DTC writes it."
            }
            is MapPick.LinePt -> {
                val l = d.shown.line(p.line) ?: return null
                editLine(p.line, l.points.mapIndexed { i, q -> if (i == p.k) at else q }, l.name)
            }
            else -> null
        }.also { ui.select(p) }
    }

    /**
     * What the list offers to add at once: the rings the route crosses as PPTs. (The flight plan is not offered here:
     * the jet has it from the mission file in 4.38.1; a steerpoint's card puts it in the cartridge for whoever wants it.)
     */
    fun bulk(): List<MapAction> {
        if (!d.loaded) return emptyList()
        val out = ArrayList<MapAction>()
        // several sites of one battery carry the same option: counted and placed once (DtcFromMission.onePerSite)
        val rings = DtcFromMission.onePerSite(d.known.ringsOnRoute.mapNotNull { it.option })
        if (rings.isNotEmpty()) out += MapAction("Add the ${rings.size} ring" + (if (rings.size > 1) "s" else "") + " your route crosses as PPTs", primary = true) { dtc.addPpts(rings) }
        return out
    }

    // ---------------------------------------------------------------- the panel's tools (WDP's MAP tab buttons)

    /** The threat rings Auto PPT would put in PPT 56-70: those the map draws, typed by the PPT table ([PlannerIntel.autoPpt]). */
    /** The typed threat rings the map draws (with Threats on): what Auto PPT chooses from. */
    private fun drawnRings(): List<DtcFromMission.PptOption> =
        if (WdpMapPrefs.threats.on) d.known.sites.mapNotNull { s -> s.option ?: s.inPpt?.let { slot -> d.shown.ppt(slot)?.let { p ->
            DtcFromMission.PptOption(p.name, DtcFromMission.PptKind.THREAT, p.code, p.name, p.rangeFt, p.at, 0.0, "your DTC")
        } } }.distinctBy { it.code + "@" + it.at } else emptyList()

    fun autoPptOptions(): List<DtcFromMission.PptOption> = PlannerIntel.autoPpt(drawnRings(), d.shown.route.map { it.at })

    /**
     * Auto PPT, or null while there is nothing to do it with (no threat ring drawn, no cartridge). With more than
     * fifteen rings and none near enough the route (WDP's rule), it says so and leaves the PPTs as they are — WDP
     * emptied them.
     */
    fun autoPpt(): MapAction? {
        if (!d.loaded) return null
        val drawn = drawnRings()
        if (drawn.none { !it.marker }) return null
        return MapAction("Auto PPT", primary = true) {
            val opts = autoPptOptions()
            if (opts.isEmpty()) "None of the ${drawn.size} threat rings on the map has a waypoint of your route within 1.5 times its range " +
                "(WDP's rule once there are more than fifteen, rings of 30,000 ft and more): the PPTs are left as they are."
            else answer(dtc.replacePpts(opts, ask = true, done = done))
        }
    }

    fun clearPpts(): MapAction? = if (!d.loaded) null else MapAction("Clear PPT") { answer(dtc.clearPpts(ask = true, done = done)) }

    fun clearLines(): MapAction? = if (!d.loaded) null else MapAction("Clear Lines") { answer(dtc.clearLines(ask = true, done = done)) }

    /** Change Area…: the DTC page's own window, with the mission's lines and the orbit boxes of the side's support. */
    fun changeArea() = dtc.openChangeArea(
        x.stations.filter { st -> d.known.tracks.none { it.support.callsign == st.support.callsign && it.line != null } }.map { PlannerIntel.orbitLine(it) },
    )

    fun lineLabel(n: Int): String? = dtc.lineLabel(n)

    fun selectLine(n: Int) { if (d.shown.line(n) != null) ui.select(MapPick.LinePt(n, 0)) }

    companion object {
        private val chartScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}

// ==================================================================================================== picking

/** Which layers are drawn (and can be picked): the Map options ([WdpMapPrefs]) as they stand. */
internal data class MapLayersNow(
    val route: Boolean, val ppts: Boolean, val lines: Boolean, val offsets: Boolean,
    val support: Boolean, val threats: Boolean, val fields: Boolean, val labels: Boolean, val ghosts: Boolean, val hsd: Boolean,
    val grid: Boolean = false,
    val bulls: Boolean = true,
    val extraLines: Boolean = false,
    val airports: Boolean = false,
    val airstrips: Boolean = false,
    /** 0 Name & data, 1 ICAO, 2 Off */
    val apLabels: Int = 2,
    val vortac: Boolean = false,
    val maxRange: Boolean = false,
    val trkDist: Boolean = false,
    val trkMin: Int = 3,
    val pptNumbers: Boolean = false,
    val awacs: Boolean = true,
    val onStation: Boolean = true,
    val jstar: Boolean = false,
    val search: Boolean = false,
    val searchLarge: Boolean = false,
    val noRadar: Boolean = false,
    val ownSams: Boolean = false,
    val ownSearch: Boolean = false,
    val ships: Boolean = false,
    /** Circle fill's alpha */
    val fill: Float = com.bmscompanion.app.ui.screens.mission.RING_FILL,
    /** Label ink: 0 Auto, 1 White, 2 Black, 3 ONC */
    val ink: Int = 0,
    /** FLIGHT PLAN's Viewing */
    val viewing: Int = 0,
    val typesOff: Set<String> = emptySet(),
    val packageOn: Set<String> = emptySet(),
    /** WDP's own look ([WdpMapPrefs.wdpLook]); off, every map of the mission's (MissionMapMarks.kt) */
    val wdpLook: Boolean = false,
) {
    /** what the mission knows beside the cartridge: on the map by its own layer, on the HSD preview only with [ghosts] */
    fun known(layer: Boolean) = layer && (!hsd || ghosts)

    /** a layer of WDP's MAP tab: not on the HSD preview (the jet's HSD has none of them) */
    fun map(layer: Boolean) = layer && !hsd

    companion object {
        fun now(theater: String = ""): MapLayersNow = WdpMapPrefs.run {
            MapLayersNow(
                route = stpt.on, ppts = ppt.on, lines = lines.on, offsets = offsets.on, support = tankers.on, threats = threats.on,
                fields = true, labels = numbers.on, ghosts = WdpMapView.ghosts, hsd = WdpMapView.hsd,
                grid = grid.on, bulls = bullseye.on, extraLines = extraLines.on, airports = airports.on, airstrips = airstrips.on,
                apLabels = airportLabels, vortac = vortac.on, maxRange = maxRange.on, trkDist = trkDist.on, trkMin = trkMin,
                pptNumbers = pptNumbers.on, awacs = awacs.on, onStation = onStation.on, jstar = jstarArea.on, search = search.on,
                searchLarge = searchLarge.on, noRadar = noRadar.on, ownSams = ownSams.on, ownSearch = ownSearch.on,
                ships = ships.on,
                fill = fillAlpha, ink = ink, viewing = WdpMapView.viewing, typesOff = typesOff(theater), packageOn = WdpMapView.packageOn.toSet(),
                wdpLook = wdpLook.on,
            )
        }
        val ALL = MapLayersNow(true, true, true, true, true, true, true, true, true, false)
    }
}

/** The side's stations the map draws as WDP's boxes: tankers (and AWACS with that switch), on station while the flight is up. */
internal fun sideStations(d: WdpMapData, l: MapLayersNow): List<PlannerIntel.Station> {
    if (!l.map(l.support)) return emptyList()
    val w = d.x.window
    return d.x.stations.filter { st ->
        val role = st.support.role
        (role.contains("tank", true) || l.awacs && !role.contains("jstar", true)) &&
            (!l.onStation || PlannerIntel.onStationDuring(st, w?.first, w?.second))
    }
}

/** The intel units each ring layer draws, with the Types filter. */
internal class IntelLayers(d: WdpMapData, l: MapLayersNow) {
    private val i = d.x.intel
    val noRadar = if (i != null && l.map(l.noRadar)) PlannerIntel.noRadar(i, l.typesOff) else emptyList()
    val ownSams = if (i != null && l.map(l.ownSams)) PlannerIntel.ownSams(i, l.typesOff) else emptyList()
    val search = if (i != null && l.map(l.search)) i.radars.filter { it.side != "friendly" && it.working } else emptyList()
    val ownSearch = if (i != null && l.map(l.ownSearch)) i.radars.filter { it.side == "friendly" && it.working } else emptyList()
    /** the ships the Ships switch draws ([drawShips]) */
    val units = if (i != null && l.map(l.ships)) i.units.filter { u ->
        val sea = u.domain == "sea" || u.kind in com.bmscompanion.app.ui.components.SEA_UNIT_KINDS
        sea && !u.dead && (u.side == "friendly" || PlannerIntel.seen(u))
    } else emptyList()
}

/**
 * What a tap at [tap] (screen pixels) picks with projection [pr]: the nearest thing drawn within [hitPx], a cartridge
 * item before what the mission knows at the same distance; else the point on the map.
 */
internal fun pickAt(d: WdpMapData, pr: MapProjection, tap: Offset, hitPx: Float, layers: MapLayersNow): MapPick {
    fun dist(p: Pt) = pr.toScreen(p.north, p.east).let { hypot(it.x - tap.x, it.y - tap.y) }
    fun distXY(x: Double, y: Double) = dist(Pt(x, y))
    val c = ArrayList<Pair<MapPick, Float>>()
    val shown = d.view(layers.viewing)
    if (layers.route) for (s in shown.stpts) c += MapPick.Stpt(s.n) to dist(s.at)
    if (layers.ppts) for (p in d.shown.ppts) c += MapPick.Ppt(p.slot) to dist(p.at) + 2f
    if (layers.lines) for (l in d.shown.lines) {
        l.points.forEachIndexed { k, p -> c += MapPick.LinePt(l.n, k) to dist(p) + 1f }
        val s = l.points.map { pr.toScreen(it.north, it.east) }
        for (i in 1 until s.size) {
            val dd = PlannerMap.segmentDistance(tap.x, tap.y, s[i - 1].x, s[i - 1].y, s[i].x, s[i].y)
            val k = if (hypot(tap.x - s[i - 1].x, tap.y - s[i - 1].y) <= hypot(tap.x - s[i].x, tap.y - s[i].y)) i - 1 else i
            c += MapPick.LinePt(l.n, k) to dd + 10f
        }
    }
    // the cartridge's offsets where they are what is drawn (the HSD preview, or no other attack current)
    if (layers.offsets && (layers.hsd || d.attack == null)) for (o in d.shown.offsets) c += MapPick.Offset(o.label, o.stpt) to dist(o.at) + 1f
    d.bull?.let { c += MapPick.Bullseye to dist(it) + 6f }
    if (layers.known(layers.support)) {
        // a track is only its box about the leg held on (its transit is not drawn): a tap inside the box, or near it
        val halfBox = (PlannerIntel.ORBIT_HALF_FT / DtcFromMission.NM * pr.pxPerNm).toFloat()
        for (t in d.known.tracks) {
            val s = t.legs.map { pr.toScreen(it.north, it.east) }
            for (i in 1 until s.size) c += MapPick.Track(t.support.callsign) to
                (PlannerMap.segmentDistance(tap.x, tap.y, s[i - 1].x, s[i - 1].y, s[i].x, s[i].y) - halfBox).coerceAtLeast(0f) + 12f
        }
        for (st in d.known.stations) c += MapPick.Station(st.support.callsign) to dist(st.at) + 5f
    }
    val own = d.known.tracks.map { it.support.callsign }.toSet() + d.known.stations.map { it.support.callsign }
    for (st in sideStations(d, layers)) if (st.support.callsign !in own) {
        val a = pr.toScreen(st.a.north, st.a.east); val b = pr.toScreen(st.b.north, st.b.east)
        c += MapPick.SideStation(st.support.callsign) to PlannerMap.segmentDistance(tap.x, tap.y, a.x, a.y, b.x, b.y) + 12f
    }
    if (layers.known(layers.threats)) d.known.sites.forEachIndexed { i, s -> if (s.inPpt == null || !layers.ppts) c += MapPick.Site(i) to dist(s.site.at) + 4f }
    if (layers.known(layers.fields)) for (f in d.fields) c += MapPick.Field(f.airport.id) to dist(f.at) + 8f
    if (layers.offsets && d.attack != null && !layers.hsd) for (q in d.attack.cues) c += MapPick.Attack to dist(Pt(q.north, q.east)) + 6f
    // WDP's MAP tab's layers
    if (!layers.hsd) {
        val mine = d.fields.map { it.airport.id }.toSet()
        for (a in d.x.airports) {
            if (a.id in mine) continue
            val layer = airfieldLayer(a) ?: continue
            if (layer == "airport" && layers.airports || layer == "airstrip" && layers.airstrips) c += MapPick.Field(a.id) to distXY(a.x, a.y) + 9f
        }
        if (layers.vortac) d.x.navaids.forEachIndexed { i, n -> c += MapPick.Navaid(i) to distXY(n.x, n.y) + 9f }
        val il = IntelLayers(d, layers)
        for (u in il.noRadar + il.ownSams + il.units) c += MapPick.GroundUnit(u.id) to distXY(u.x, u.y) + 7f
        for (r in il.search + il.ownSearch) c += MapPick.Radar(r.id) to distXY(r.x, r.y) + 8f
        if (layers.jstar) for (j in d.x.intel?.jstars.orEmpty()) if (j.active) c += MapPick.Jstar(j.callsign) to distXY(j.x, j.y) + 6f
        if (layers.ships) d.x.liveShips.forEachIndexed { i, s -> c += MapPick.LiveShip(i) to dist(s.at) + 7f }
        for (id in layers.packageOn) d.x.packageRoutes[id]?.forEachIndexed { k, w -> c += MapPick.PackageWp(id, k) to distXY(w.x, w.y) + 3f }
    }
    val best = c.minByOrNull { it.second }
    if (best != null && best.second < hitPx) return best.first
    val (x, y) = pr.toTheater(tap)
    return MapPick.Point(Pt(x, y))
}

/** Where a pick is, for the selection ring. */
internal fun pickPos(d: WdpMapData, p: MapPick): Pt? = when (p) {
    is MapPick.Stpt -> d.view(WdpMapView.viewing).stpt(p.n)?.at
    is MapPick.Ppt -> d.shown.ppt(p.slot)?.at
    is MapPick.LinePt -> d.shown.line(p.line)?.points?.getOrNull(p.k)
    is MapPick.Offset -> d.shown.offsets.firstOrNull { it.label == p.label && it.stpt == p.stpt }?.at
    MapPick.Bullseye -> d.bull
    is MapPick.Track -> d.known.tracks.firstOrNull { it.support.callsign == p.callsign }?.let { t -> t.legs.takeIf { it.size >= 2 }?.let { mid(it) } ?: mid(t.points) }
    is MapPick.Station -> d.known.stations.firstOrNull { it.support.callsign == p.callsign }?.at
    is MapPick.Site -> d.known.sites.getOrNull(p.index)?.site?.at
    is MapPick.Field -> (d.fields.firstOrNull { it.airport.id == p.id }?.airport ?: d.x.airports.firstOrNull { it.id == p.id })?.let { Pt(it.x, it.y) }
    MapPick.Attack -> d.attack?.target?.let { Pt(it.north, it.east) }
    is MapPick.Point -> p.at
    is MapPick.GroundUnit -> d.x.units[p.id]?.let { Pt(it.x, it.y) }
    is MapPick.Radar -> d.x.radars[p.id]?.let { Pt(it.x, it.y) }
    is MapPick.Navaid -> d.x.navaids.getOrNull(p.i)?.let { Pt(it.x, it.y) }
    is MapPick.PackageWp -> d.x.packageRoutes[p.flight]?.getOrNull(p.k)?.let { Pt(it.x, it.y) }
    is MapPick.Jstar -> d.x.intel?.jstars?.firstOrNull { it.callsign == p.callsign }?.let { Pt(it.x, it.y) }
    is MapPick.SideStation -> d.x.stations.firstOrNull { it.support.callsign == p.callsign }?.mid
    is MapPick.LiveShip -> d.x.liveShips.getOrNull(p.i)?.at
}

private fun mid(pts: List<Pt>): Pt? = if (pts.isEmpty()) null else Pt(pts.map { it.north }.average(), pts.map { it.east }.average())

// ==================================================================================================== the map

private val STATION_NM = 12.0
private val Dashed = PathEffect.dashPathEffect(floatArrayOf(9f, 7f), 0f)
private val LineDash = PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f)
private val Fine = PathEffect.dashPathEffect(floatArrayOf(4f, 5f), 0f)
/** the same patterns as plain arrays, for dashes drawn as strokes (drawDashedLine, drawDashedCircle) */
private val DashedOn = floatArrayOf(9f, 7f)
private val LineDashOn = floatArrayOf(14f, 8f)
private val FineOn = floatArrayOf(4f, 5f)

private val AttackMagenta: Color get() = if (Hud.onLightMap) Color(0xFFB0209A) else Color(0xFFFF5CE1)
private val AttackRef: Color get() = if (Hud.onLightMap) Color(0xFF1565C0) else Color(0xFF4FA8FF)
private val LineInkMap: Color get() = if (Hud.onLightMap) Color(0xFF37474F) else Color(0xFFE8ECEF)
private fun supportInk(role: String): Color = if (role.contains("tank", true)) TankerColor else AwacsColor

/** The HSD's colours: white route and steerpoints, yellow PPTs, dashed white lines, cyan bullseye, blue-grey rings. */
private object HsdInk {
    val glass = Color(0xFF000000)
    val bezel = Color(0xFF2B2F33)
    val rings = Color(0xFF3F8FAF)
    val white = Color(0xFFF2F2F2)
    val ppt = Color(0xFFFFE34A)
    val bull = Color(0xFF6FD3FF)
    val oa = Color(0xFF6CE66C)
    val ghost = Color(0xFF8E9AA6)
    val sel = Color(0xFFFF5CE1)
}

@Composable
private fun MapArea(image: String?, sizeFt: Double, d: WdpMapData, actions: MapActions, layer: GraphicsLayer, modifier: Modifier) {
    val tm = rememberTextMeasurer(cacheSize = 512)
    val density = LocalDensity.current
    val hitPx = with(density) { 30.dp.toPx() } * 1.5f
    val onTapAt: (MapProjection, Offset) -> Unit = { pr, p ->
        val (x, y) = pr.toTheater(p)
        val moving = WdpMapView.moving
        when {
            moving != null -> actions.moveTo(moving, Pt(x, y))?.let { WdpMapView.status = it }
            // Measure: the taps place its points and open no cards
            WdpMapView.measureOn && !WdpMapView.hsd -> WdpMapView.measureTap(Pt(x, y))
            else -> {
                MapCursor.tapAt = Pt(x, y)
                WdpMapView.listOpen = false
                WdpMapView.sel = pickAt(d, pr, p, hitPx, MapLayersNow.now(d.x.theaterKey))
            }
        }
    }
    if (WdpMapView.hsd) {
        HsdView(d, sizeFt, onTapAt, modifier)
        return
    }
    // WDP's info label: once the mouse has rested on something for 300 ms
    val cur = MapCursor.at
    LaunchedEffect(cur, d) {
        MapCursor.hover = null
        val a = cur ?: return@LaunchedEffect
        delay(300)
        val pr = MapCursor.proj ?: return@LaunchedEffect
        if (WdpMapView.measureOn || WdpMapView.moving != null) return@LaunchedEffect
        MapCursor.hover = pickAt(d, pr, a, hitPx, MapLayersNow.now(d.x.theaterKey)).takeIf { it !is MapPick.Point }
        MapCursor.hoverAt = a
    }
    // Esc clears the measure: the map takes the keyboard while Measure is on
    val keys = remember { androidx.compose.ui.focus.FocusRequester() }
    val measuring = WdpMapView.measureOn
    LaunchedEffect(measuring) { if (measuring) runCatching { keys.requestFocus() } }
    // a right-click: the point card there, even over something (WDP's add menu)
    BoxWithConstraints(
        modifier
            .focusRequester(keys)
            .onKeyEvent { e ->
                if (e.key == Key.Escape && e.type == KeyEventType.KeyDown && WdpMapView.measureOn) { WdpMapView.toggleMeasure(); true } else false
            }
            .focusable(enabled = measuring)
            .mapPointer { p ->
            MapCursor.proj?.let { pr ->
                val (n, e) = pr.toTheater(p)
                WdpMapView.listOpen = false
                WdpMapView.sel = MapPick.Point(Pt(n, e))
            }
        },
    ) {
        val key = d.facts.route.joinToString { "${it.n}" } + d.shown.stpts.size
        val st = WdpMapView.map
        // the map covers its box (a phone is tall, a tablet wide: fitting a square left bands of nothing), so the
        // flight plan is fitted into the box's shorter side
        val fit = with(LocalDensity.current) { (min(maxWidth.toPx(), maxHeight.toPx()) / max(maxWidth.toPx(), maxHeight.toPx()).coerceAtLeast(1f)) }
        // the flight's own area: its flight plan, its tankers' and AWACS's legs, its PPTs and lines (a Recon target far
        // off in the cartridge is not what a pilot looks at first); with none of those, the whole theater
        val frame: () -> Unit = {
            val pts = (d.shown.route.map { it.at } + d.known.tracks.flatMap { it.legs } + d.shown.ppts.map { it.at } + d.shown.lines.flatMap { it.points })
                .ifEmpty { d.shown.stpts.map { it.at } }
            if (pts.isEmpty()) st.flyTo(sizeFt / 2, sizeFt / 2, 1f)
            else {
                val n0 = pts.minOf { it.north }; val n1 = pts.maxOf { it.north }
                val e0 = pts.minOf { it.east }; val e1 = pts.maxOf { it.east }
                val extent = max(max(n1 - n0, e1 - e0), 20 * DtcFromMission.NM)
                st.flyTo((n0 + n1) / 2, (e0 + e1) / 2, (0.8 * fit * sizeFt / extent).toFloat().coerceIn(1f, 60f))
            }
        }
        LaunchedEffect(key) {
            if (WdpMapView.framed == key) return@LaunchedEffect
            frame()
            WdpMapView.framed = key
        }
        val asked = WdpMapView.fitAsk
        LaunchedEffect(asked) { if (asked > 0) frame() }
        val style = WdpMapPrefs.style
        // the map and everything drawn over it, recorded as it draws: Save Map's picture (the keys and chips are not in it)
        Box(Modifier.fillMaxSize().drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }) {
            TheaterMap(
                image, sizeFt, Modifier.fillMaxSize(), st, maxScale = 180f, fillBox = true, style = style, zoomButtons = false,
                onTap = { x, y, pr -> onTapAt(pr, pr.toScreen(x, y)) },
            ) { pr ->
                MapCursor.proj = pr
                if (!MapCursor.ready) MapCursor.ready = true
                inMapInks(style == "chart") { drawPlanner(pr, d, MapLayersNow.now(d.x.theaterKey), WdpMapView.sel, tm, hsd = false) }
            }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 17.dp, bottom = 70.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FitKey(frame)
            // the map's own gliding zoom about its middle, as TheaterMap's own keys zoom it (ZoomMath)
            MapZoomButtons(onZoom = { f -> st.zoomBy(f) })
        }
    }
}

/** Fit: the flight's area again (the first view's framing), above the zoom keys. */
@Composable
private fun FitKey(onFit: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Hud.Surface.copy(alpha = 0.92f))
            .border(1.dp, Hud.Outline.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).clickable(onClick = onFit).mapControl("Fit"),
        contentAlignment = Alignment.Center,
    ) {
        val ink = Hud.Text
        Canvas(Modifier.size(18.dp)) {
            // four corners of a frame
            val w = size.width; val l = w * 0.32f; val s = 2.dp.toPx()
            for ((cx, cy, dx, dy) in listOf(listOf(0f, 0f, 1f, 1f), listOf(w, 0f, -1f, 1f), listOf(0f, w, 1f, -1f), listOf(w, w, -1f, -1f))) {
                drawLine(ink, Offset(cx, cy), Offset(cx + dx * l, cy), s)
                drawLine(ink, Offset(cx, cy), Offset(cx, cy + dy * l), s)
            }
            drawCircle(ink, w * 0.1f, Offset(w / 2, w / 2))
        }
    }
}

/**
 * Save Map (WDP's `btnSaveMap_Click`): the map as it is shown, every overlay but none of the page's chips, as a JPEG
 * on the BMS PC — into the DataCards folder of the save's flight when one is planned
 * (`@datacards\<save>\<package>\<callsign>\<callsign>_Map.jpg`), else WDP's `SavedMaps` (`MAP.jpg`). Returns what
 * the status line says.
 */
internal suspend fun saveMapPicture(layer: GraphicsLayer, mission: WdpMission): String? {
    val title = "Save Map"
    val img = try { layer.toImageBitmap() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        return "The map could not be drawn to a picture: ${e.message ?: e::class.simpleName}"
    }
    if (img.width <= 0 || img.height <= 0) return "The map is not on screen: nothing to save."
    val bytes = try { WdpPicture.jpeg(img) } catch (e: Exception) { return "The map could not be encoded: ${e.message ?: e::class.simpleName}" }
    val f = mission.flight
    val (folder, name) = if (f != null && PlannerMissionState.fromSave) {
        fun clean(s: String) = s.filter { it !in "\\/:*?\"<>|" }.trim()
        val save = clean(PlannerMissionState.file?.title ?: mission.ref?.file?.substringBeforeLast('.') ?: "Mission")
        val call = clean(f.row.callsign).ifBlank { "Flight" }
        (WdpFiles.DATACARDS + "\\" + save + "\\" + f.packageNumber + "\\" + call) to "${call}_Map.jpg"
    } else WdpFiles.SAVED_MAPS to "MAP.jpg"
    val file = WdpFiles.save(title, folder, "JPEG|*.jpg", name, defaultExt = "jpg") ?: return null
    if (!WdpFiles.writeBytes(file, bytes, title)) return null
    WdpFiles.remember("map_MAP", file)
    return "Map saved: ${file.path}"
}

/** The HSD preview: the same, on black, north up, centred on a steerpoint, at the HSD's range, in the HSD's colours. */
@Composable
private fun HsdView(d: WdpMapData, sizeFt: Double, onTap: (MapProjection, Offset) -> Unit, modifier: Modifier) {
    val tm = rememberTextMeasurer(cacheSize = 128)
    val centreN = WdpMapView.hsdCentre?.takeIf { n -> d.shown.stpt(n) != null } ?: PlannerMap.defaultCentre(d.shown)
    val centre = centreN?.let { d.shown.stpt(it)?.at } ?: d.bull ?: Pt(sizeFt / 2, sizeFt / 2)
    val range = WdpMapView.hsdRange.takeIf { it in PlannerMap.HSD_RANGES } ?: 60
    val tapCb = androidx.compose.runtime.rememberUpdatedState(onTap)
    Box(modifier.background(Color(0xFF0B0D0F)), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(centre, range, sizeFt) {
                detectTapGestures { p -> hsdFrame(size.width.toFloat(), size.height.toFloat(), centre, range, sizeFt)?.let { (pr, _) -> tapCb.value(pr, p) } }
            },
        ) {
            val (pr, box) = hsdFrame(size.width, size.height, centre, range, sizeFt) ?: return@Canvas
            // the display: a black square in a dark bezel
            drawRoundRect(HsdInk.bezel, box.topLeft - Offset(6f, 6f), Size(box.width + 12f, box.height + 12f), androidx.compose.ui.geometry.CornerRadius(10f, 10f))
            drawRect(HsdInk.glass, box.topLeft, box.size)
            clipRect(box.left, box.top, box.right, box.bottom) {
                val c = box.center
                val r = box.width / 2
                for (i in 1..4) drawCircle(HsdInk.rings.copy(alpha = if (i == 4) 0.75f else 0.5f), r * i / 4f, c, style = Stroke(1.3f))
                // the north tick on the outer ring
                drawLine(HsdInk.rings, Offset(c.x, box.top + 2f), Offset(c.x, box.top + 14f), 2f)
                drawPlanner(pr, d, MapLayersNow.now(), WdpMapView.sel, tm, hsd = true, centreStpt = centreN)
            }
            val mono = TextStyle(color = HsdInk.white, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            // the MFD's own figures, each on black so nothing drawn under them can make them unreadable
            fun figure(text: String, at: Offset, style: TextStyle, right: Boolean = false) {
                val l = com.bmscompanion.app.ui.components.MapText.label(this, tm, text, style).layout
                val x = if (right) at.x - l.size.width else at.x
                drawRect(HsdInk.glass, Offset(x - 4f, at.y - 2f), Size(l.size.width + 8f, l.size.height + 4f))
                safeText(tm, text, Offset(x, at.y), style)
            }
            figure("$range", box.topLeft + Offset(8f, 8f), mono)
            figure("CEN  NORTH UP", box.topLeft + Offset(8f, box.height - 22f), mono.copy(color = HsdInk.rings, fontSize = 10.sp))
            centreN?.let { n -> figure("STPT $n", Offset(box.right - 8f, box.top + 8f), mono, right = true) }
        }
    }
}

/** The HSD square in a canvas of [w]×[h] and the projection that puts [centre] in its middle at [rangeNm] to its edge. */
private fun hsdFrame(w: Float, h: Float, centre: Pt, rangeNm: Int, sizeFt: Double): Pair<MapProjection, Rect>? {
    val side = min(w, h) - 24f
    if (side <= 40f) return null
    // a tall box (a phone): the display at the top, the room under it left to the card
    val box = Rect(Offset((w - side) / 2, if (h - side > 80f) 12f else (h - side) / 2), Size(side, side))
    val ppf = (side / 2) / (rangeNm * DtcFromMission.NM)
    val theater = (sizeFt * ppf).toFloat()
    val left = box.center.x - (centre.east * ppf).toFloat()
    val top = box.center.y - ((sizeFt - centre.north) * ppf).toFloat()
    // scale: how far in the theater is magnified, which only decides when the long labels are drawn
    return MapProjection(left, top, theater, sizeFt, (theater / side).coerceAtLeast(1f)) to box
}

/**
 * Everything, in WDP's drawing order with the page's own added (bottom to top): the grid; the bullseye's rings; the
 * airfields; the VORTACs; the tanker and AWACS boxes; the JSTARS areas; ground units and ships; the cartridge's lines;
 * the rings (threats, search radars, what the side has not seen, no radar, the own side, the own search radars, then
 * the cartridge's PPTs); the package's other routes; the flight plan with its leg labels; the nav offsets; the flight's
 * fields; the measure; the selection. [hsd]: the HSD's colours and symbols, WDP's layers left out (the jet's HSD has
 * none of them), and what the cartridge does not carry only as dim ghosts.
 *
 * The names are placed last, the cartridge's first: a label that would overlap one already placed is left out, and the
 * PPT numbers, the steerpoints and the lines are what the page is about — ninety SA-2s of a campaign must not push them
 * off the map. A site is named only near the route or when the map is close in.
 */
private fun DrawScope.drawPlanner(
    pr: MapProjection,
    d: WdpMapData,
    layers: MapLayersNow,
    sel: MapPick?,
    tm: androidx.compose.ui.text.TextMeasurer,
    hsd: Boolean,
    centreStpt: Int? = null,
) {
    val placed = ArrayList<Rect>()
    class Name(val prio: Int, val text: String, val at: Offset, val style: TextStyle)
    val names = ArrayList<Name>()
    fun on(p: Offset) = p.x > -60 && p.y > -60 && p.x < size.width + 60 && p.y < size.height + 60
    fun ringOn(c: Offset, r: Float) = !(c.x + r < 0 || c.y + r < 0 || c.x - r > size.width || c.y - r > size.height)
    val light = Hud.onLightMap
    val label = if (hsd) TextStyle(color = HsdInk.white, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    else TextStyle(color = if (light) Color(0xFF12151A) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, shadow = if (light) MapGlow else MapShadow)
    val halo = if (hsd) Color.Black else MapHalo
    val showLabels = layers.labels
    val sink = NameSink { prio, text, at, style -> if (text.isNotBlank() && on(at)) names += Name(prio, text, at, style) }
    fun name(prio: Int, text: String, at: Offset, style: TextStyle) = sink.add(prio, text, at, style)
    val notIn = " · not in DTC"
    val ghost: (Color) -> Color = { if (hsd) HsdInk.ghost else it }
    val x = d.x
    val fill = if (hsd) 0f else layers.fill
    val shown = d.view(layers.viewing)
    val apInk = labelInk(layers.ink, light)
    // Away from the HSD preview the page draws the mission with the marks every map of it shares (MissionMapMarks.kt),
    // so it looks as the Mission map and the VR map board do; WDP's own look only when the pilot asks for it
    val app = !hsd && !layers.wdpLook
    val wdp = !hsd && layers.wdpLook
    // the airfields' symbols, drawn over the route that leaves from them (a take-off steerpoint sits on its field)
    val fieldMarks = ArrayList<Triple<Offset, List<Offset>, FieldMark>>()

    // ---------------------------------------------------------------- WDP's map: the grid, the bullseye, the airfields, the VORTACs
    if (layers.map(layers.grid)) x.coords?.takeIf { it.enableNewTerrain }?.let { drawGraticule(pr, it, tm) }
    if (layers.map(layers.bulls)) d.bull?.let { b ->
        // every map's bullseye (20 nm rings); WDP's 30 nm rings, labelled, with WDP's look or the extra lines
        val c = pr.toScreen(b.north, b.east)
        if (layers.wdpLook || layers.extraLines) drawBullseyeRings(pr, c, layers.extraLines, tm) else drawBullseye(c, pr, rings = 5, ringNm = 20)
    }
    val flightFieldIds = d.fields.map { it.airport.id }.toSet()
    if (wdp) drawAirfields(pr, x, flightFieldIds, layers.airports, layers.airstrips, layers.apLabels, apInk, sink)
    else if (app) {
        val apStyle = TextStyle(color = apInk, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 12.sp, shadow = if (0.2126f * apInk.red + 0.7152f * apInk.green + 0.0722f * apInk.blue > 0.5f) MapShadow else MapGlow)
        for (a in x.airports) {
            if (a.id in flightFieldIds) continue
            val kind = airfieldLayer(a) ?: continue
            if (kind == "airport" && !layers.airports || kind == "airstrip" && !layers.airstrips) continue
            val p = pr.toScreen(a.x, a.y)
            if (!on(p)) continue
            fieldMarks += Triple(p, airfieldRunways(a), FieldMark.OTHER)
            airportLabel(a, layers.apLabels)?.let { sink.add(NamePrio.AIRPORT, it, p + Offset(9f, -7f), apStyle) }
        }
    }
    if (layers.map(layers.vortac)) drawVortacs(pr, x, layers.maxRange, ((MapCursor.hover ?: sel) as? MapPick.Navaid)?.i, layers.fill, apInk, sink)

    // ---------------------------------------------------------------- the tankers and AWACS: the side's on station, then the package's
    val packCalls = d.known.tracks.map { it.support.callsign }.toSet() + d.known.stations.map { it.support.callsign }
    for (st in sideStations(d, layers)) {
        if (st.support.callsign in packCalls) continue
        if (wdp) drawOrbitBox(pr, st, supportInk(st.support.role), fill, if (showLabels) stationLabel(st) else st.support.callsign, sink)
        else drawSupportTrack(
            pr, st.a, st.b, trackInk(st.support.role), mine = false,
            label = if (showLabels) trackLabel(st.support.callsign, receiverTacan(st.support.tacan), false) else null, names = sink,
        )
    }
    if (app && layers.support) for (t in d.known.tracks) {
        // the package's own: every map's track and box, the Planner's word for whether the cartridge holds it after
        val st = x.stations.firstOrNull { it.support.callsign == t.support.callsign }
        val legA = st?.a ?: t.legs.takeIf { it.size >= 2 }?.first()
        val legB = st?.b ?: t.legs.takeIf { it.size >= 2 }?.last()
        val text = trackLabel(t.support.callsign, receiverTacan(t.support.tacan), t.support.yours) + (if (t.inLine != null) " · L${t.inLine}" else notIn)
        drawSupportTrack(pr, legA, legB, trackInk(t.support.role), t.support.yours, if (showLabels) text else null, sink)
    }
    if (!app && layers.known(layers.support)) {
        // only the track itself (the box about the leg held on), solid; never the transit to it and back
        for (t in d.known.tracks) {
            val ink = ghost(supportInk(t.support.role))
            val legs = t.legs.map { pr.toScreen(it.north, it.east) }
            val st = x.stations.firstOrNull { it.support.callsign == t.support.callsign }
            if (t.legs.size >= 2 && t.inLine == null) {
                // WDP's station box: 30,000 ft either side of the station leg
                val a = st?.a ?: t.legs.first()
                val b = st?.b ?: t.legs.last()
                val box = PlannerIntel.orbitBox(a, b).map { pr.toScreen(it.north, it.east) }
                val path = Path().apply { box.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close() }
                if (!hsd) drawPath(path, ink.copy(alpha = if (t.support.yours) maxOf(fill, 0.12f) else fill))
                drawPath(path, ink.copy(alpha = 0.85f), style = Stroke(2f))
            }
            if (showLabels && legs.size >= 2) {
                val at = Offset((legs.first().x + legs.last().x) / 2, (legs.first().y + legs.last().y) / 2)
                val text = listOfNotNull(t.support.callsign, t.support.tacan?.let { "TCN $it" }, if (t.support.yours) "YOURS" else null).joinToString(" · ") +
                    (if (t.inLine != null) " · L${t.inLine}" else notIn) + (st?.let { "\n" + PlannerIntel.hhmm(it.from) + " - " + PlannerIntel.hhmm(it.to) } ?: "")
                name(3, text, at + Offset(8f, -6f), label.copy(color = ink, lineHeight = 13.sp))
            }
        }
    }
    if (layers.map(layers.jstar)) drawJstar(pr, x, layers.fill)

    // ---------------------------------------------------------------- ships (no ground units: they buried the map)
    if (!hsd && layers.ships) drawShips(pr, x, tm)

    // ---------------------------------------------------------------- the cartridge's lines
    if (layers.lines && !hsd) for (l in d.shown.lines) {
        // every map's line: dashed as the HSD draws it, with a dot at each point
        val pts = l.points.map { pr.toScreen(it.north, it.east) }
        if (pts.none { on(it) }) continue
        drawCartridgeLine(pts, LineInk)
        if (showLabels && pts.isNotEmpty()) name(NamePrio.LINE, "LINE ${l.n}" + (l.name?.let { " $it" } ?: ""), lineLabelAt(pts[0]), label.copy(color = LineInk))
    }
    if (layers.lines && hsd) for (l in d.shown.lines) {
        val pts = l.points.map { pr.toScreen(it.north, it.east) }
        val ink = if (hsd) HsdInk.white else LineInkMap
        for (i in 1 until pts.size) {
            if (!hsd) drawLine(halo.copy(alpha = 0.7f), pts[i - 1], pts[i], 5.5f)
            // on the map a line the cartridge holds is solid (the HSD draws it dashed)
            if (hsd) drawDashedLine(ink, pts[i - 1], pts[i], 1.8f, LineDashOn) else drawLine(ink, pts[i - 1], pts[i], 2.6f)
        }
        pts.forEach { if (!hsd) drawCircle(halo, 5f, it); drawCircle(ink, if (hsd) 2.2f else 3.5f, it, style = if (hsd) androidx.compose.ui.graphics.drawscope.Fill else Stroke(2f)) }
        if (showLabels && pts.isNotEmpty()) name(2, "L${l.n}" + (l.name?.let { " $it" } ?: ""), pts[0] + Offset(8f, 4f), label.copy(color = ink))
    }

    // ---------------------------------------------------------------- the rings: threats, search, beyond intel, no radar, own side
    if (!hsd && layers.threats) for (s in d.known.sites) {
        if (s.inPpt != null && layers.ppts) continue
        val siteName = if (showLabels) siteShort(s.site.name) else null
        if (s.site.source != LIVE_SITES) {
            // every map's site of the save: the SAM red, dashed until the cartridge holds it
            drawThreatSite(pr, s.site.at, s.ringFt, dashed = s.inPpt == null, fill = fill, label = siteName, edgeFt = s.edgeFt, names = sink)
        } else {
            // one the Tacview feed reports while BMS flies: as the Mission map draws the feed's air defences
            val c = pr.toScreen(s.site.at.north, s.site.at.east)
            if (drawLiveSam(c, ((s.ringFt ?: 0.0) * pr.pxPerNm / DtcFromMission.NM).toFloat(), SamRed) && siteName != null) {
                name(NamePrio.SITE, siteName, c + Offset(9f, 2f), label.copy(color = SamRed))
            }
        }
    }
    if (hsd && layers.known(layers.threats)) for (s in d.known.sites) {
        if (s.inPpt != null && layers.ppts) continue
        val c = pr.toScreen(s.site.at.north, s.site.at.east)
        val r = ((s.ringFt ?: 0.0) * pr.pxPerNm / DtcFromMission.NM).toFloat()
        val ink = ghost(MapInk.threat)
        if (r > 2f) {
            if (!ringOn(c, r)) continue
            if (!hsd && fill > 0f) drawCircle(ink.copy(alpha = fill * 0.6f), r, c)
            if (s.inPpt != null) drawCircle(ink.copy(alpha = if (hsd) 0.5f else 0.8f), r, c, style = Stroke(1.6f)) else drawDashedCircle(ink.copy(alpha = if (hsd) 0.5f else 0.8f), r, c, 1.6f, DashedOn)
        } else if (!on(c)) continue
        val h = 4f
        drawRect(halo, Offset(c.x - h - 1.5f, c.y - h - 1.5f), Size(2 * h + 3f, 2 * h + 3f))
        drawRect(ink, Offset(c.x - h, c.y - h), Size(2 * h, 2 * h), style = Stroke(1.6f))
        val near = (s.edgeFt ?: Double.MAX_VALUE) < 40 * DtcFromMission.NM
        if (showLabels && (near || pr.pxPerNm > 9f)) name(5, siteShort(s.site.name), c + Offset(8f, 1f), label.copy(color = ink, fontSize = 10.sp))
    }
    if (!hsd) {
        val il = IntelLayers(d, layers)
        for (r in il.search) drawSearch(pr, r, layers.searchLarge, dotted = false, tm = tm, names = sink)
        fun ring(u: com.bmscompanion.app.data.mission.CampMapUnit, ink: Color, dashed: Boolean, f: Float) =
            drawUnitRing(pr, Pt(u.x, u.y), if (PlannerIntel.airDefence(u)) x.ringOf(PlannerIntel.threatType(u)) else null, ink, dashed, f)
        for (u in il.noRadar) ring(u, MapInk.noRadar, true, 0f)
        for (u in il.ownSams) ring(u, MapInk.ownSam, false, fill)
        for (r in il.ownSearch) drawSearch(pr, r, layers.searchLarge, dotted = true, tm = tm, names = sink)
    }
    if (app && layers.support) for (st in d.known.stations) {
        // every map's station (an orbit around the point the briefing names), only where the flight has no track
        if (st.inPpt != null && layers.ppts) continue
        if (d.known.tracks.any { it.support.callsign == st.support.callsign && it.legs.size >= 2 }) continue
        val what = if (st.support.role.contains("tank", true)) "TANKER" else "AWACS"
        drawSupportStation(
            pr.toScreen(st.at.north, st.at.east), (STATION_NM * pr.pxPerNm).toFloat(), trackInk(st.support.role),
            if (showLabels) "$what ${st.support.callsign} station$notIn" else null, sink,
        )
    }
    if (!app && layers.known(layers.support)) for (st in d.known.stations) {
        if (st.inPpt != null && layers.ppts) continue
        val c = pr.toScreen(st.at.north, st.at.east)
        val ink = ghost(supportInk(st.support.role))
        val hasTrack = d.known.tracks.any { it.support.callsign == st.support.callsign && it.legs.size >= 2 }
        if (!hasTrack) {
            val r = (STATION_NM * pr.pxPerNm).toFloat()
            if (ringOn(c, r)) drawCircle(ink.copy(alpha = 0.6f), r, c, style = Stroke(1.5f))
        }
        if (!on(c)) continue
        drawCircle(halo, 6.5f, c)
        drawCircle(ink, 5f, c, style = Stroke(2f))
        if (showLabels && !hasTrack) name(3, "${st.support.callsign} station$notIn", c + Offset(9f, -8f), label.copy(color = ink))
    }
    // (no ghost of an attack page's attack: the map draws one attack, the current one, below — B9)
    // the cartridge's PPTs: WDP's "PPT in DTC" red, over every other ring
    if (layers.ppts) for (p in d.shown.ppts) {
        val c = pr.toScreen(p.at.north, p.at.east)
        if (p.marker) {
            if (!on(c)) continue
            if (hsd) {
                val t = p.code.ifBlank { "${p.slot}" }
                val l = com.bmscompanion.app.ui.components.MapText.label(this, tm, t, label.copy(color = HsdInk.ppt)).layout
                safeText(tm, t, c - Offset(l.size.width / 2f, l.size.height / 2f), label.copy(color = HsdInk.ppt))
            } else {
                // every map's marker: a small flag
                drawPptMarker(c, Friendly)
                if (showLabels) name(NamePrio.PPT, p.name.ifBlank { "PPT ${p.slot}" } + if (layers.pptNumbers) "/${p.slot}" else "", markerLabelAt(c), label.copy(color = Friendly))
            }
            continue
        }
        val r = (p.rangeFt * pr.pxPerNm / DtcFromMission.NM).toFloat()
        if (!ringOn(c, r)) continue
        if (hsd) {
            drawCircle(HsdInk.ppt, r, c, style = Stroke(1.6f))
            // the HSD writes the threat's code in the middle of its ring
            val t = p.code.ifBlank { "?" }
            val l = com.bmscompanion.app.ui.components.MapText.label(this, tm, t, label.copy(color = HsdInk.ppt)).layout
            if (on(c)) safeText(tm, t, c - Offset(l.size.width / 2f, l.size.height / 2f), label.copy(color = HsdInk.ppt))
            if (showLabels) name(1, "${p.slot}", c + Offset(-6f, 9f), label.copy(color = HsdInk.ppt.copy(alpha = 0.75f), fontSize = 9.sp))
        } else {
            // every map's PPT ring: the hostile red, filled at Circle fill, named by its system
            drawPptRing(c, r, fill)
            val short = p.name.ifBlank { p.code }
            name(NamePrio.PPT, if (layers.pptNumbers) "$short/${p.slot}" else short, pptLabelAt(c), label.copy(color = SamRed))
        }
    }

    // ---------------------------------------------------------------- the package's other flights
    if (!hsd) for (id in layers.packageOn) x.packageRoutes[id]?.let { drawPackageRoute(pr, it, x.packageRows[id]?.callsign.orEmpty(), sink, wdpShapes = layers.wdpLook) }

    // ---------------------------------------------------------------- the bullseye's mark
    d.bull?.let { b ->
        val c = pr.toScreen(b.north, b.east)
        val ink = if (hsd) HsdInk.bull else MapInk.bull
        if (on(c) && (hsd || layers.bulls)) {
            // every map's bullseye draws its own mark (drawBullseye); WDP's rings have none
            if (hsd || layers.wdpLook || layers.extraLines) {
                drawCircle(ink, 9f, c, style = Stroke(2.2f))
                drawCircle(ink, 3f, c)
            }
            // named as the Mission map names it: the save header's, before 3D
            val fromSave = runCatching { bullseye(MissionLink.live.value, MissionLink.contacts.value?.contacts) == null }.getOrDefault(true)
            if (showLabels && wdp) name(2, "BULLSEYE", c + Offset(12f, 6f), label.copy(color = ink, fontSize = 10.sp))
            else if (showLabels && app && fromSave) name(NamePrio.BULL, "BULLSEYE (from the save)", c + Offset(12f, 6f), label.copy(color = Hud.Cyan))
        }
    }

    // ---------------------------------------------------------------- the flight plan: WDP's symbols, its legs, Trk/Dist
    if (layers.route) {
        val route = shown.route
        val mission = layers.viewing == 2
        val ink = if (hsd) HsdInk.white else if (mission) Hud.Amber.copy(alpha = 0.7f) else Hud.Amber
        val arrival = route.indexOfFirst { it.action == PlannerIntel.LAND }
        // every map's legs (no leg between a refuel point and a landing, the alternate's thin and dashed)
        if (!hsd) drawRouteLegs(
            route.map { s -> LegPt(pr.toScreen(s.at.north, s.at.east), land = s.action == PlannerIntel.LAND, refuel = s.action == PlannerIntel.REFUEL) },
            ink, dashed = mission,
        )
        if (hsd) for (i in 1 until route.size) {
            val s0 = route[i - 1]
            val s1 = route[i]
            // no leg joins a refuel point to a landing (WDP draws none)
            if (!hsd && (s0.action == PlannerIntel.REFUEL && s1.action == PlannerIntel.LAND || s0.action == PlannerIntel.LAND && s1.action == PlannerIntel.REFUEL)) continue
            val a = pr.toScreen(s0.at.north, s0.at.east)
            val b = pr.toScreen(s1.at.north, s1.at.east)
            if (!on(a) && !on(b)) continue
            // the leg from the arrival on to the alternate: dashed
            val dashed = !hsd && (mission || arrival >= 0 && i - 1 >= arrival)
            if (!hsd) drawLine(halo, a, b, 6f)
            if (dashed) drawDashedLine(ink, a, b, if (hsd) 1.8f else 3f, DashedOn) else drawLine(ink, a, b, if (hsd) 1.8f else 3f)
        }
        val ip = if (hsd) null else PlannerIntel.ipOf(route.map { it.n to it.action })
        for (s in shown.stpts) {
            val p = pr.toScreen(s.at.north, s.at.east)
            if (!on(p)) continue
            if (hsd) {
                if (s.open) {
                    // the HSD's large cross for a target steerpoint 81-99
                    drawLine(HsdInk.white, p - Offset(9f, 0f), p + Offset(9f, 0f), 2f)
                    drawLine(HsdInk.white, p - Offset(0f, 9f), p + Offset(0f, 9f), 2f)
                } else if (s.n == centreStpt) drawCircle(HsdInk.white, 6f, p)
                else drawCircle(HsdInk.white, 6f, p, style = Stroke(1.8f))
                if (showLabels) name(0, "${s.n}", p + Offset(8f, -16f), label)
                continue
            }
            val base = if (s.target) Hostile else Hud.Amber
            val sInk = if (mission) base.copy(alpha = 0.75f) else base
            if (s.open) {
                drawLine(halo, p - Offset(10f, 0f), p + Offset(10f, 0f), 6f); drawLine(halo, p - Offset(0f, 10f), p + Offset(0f, 10f), 6f)
                drawLine(sInk, p - Offset(9f, 0f), p + Offset(9f, 0f), 3f); drawLine(sInk, p - Offset(0f, 9f), p + Offset(0f, 9f), 3f)
            } else if (layers.wdpLook) {
                // WDP's symbols: STPT 1 and the IP squares, a tasked point a triangle, a target the page's diamond
                val shape = when {
                    s.target -> Shape.DIAMOND
                    s.n == 1 || s.n == ip -> Shape.SQUARE
                    s.action in PlannerIntel.TASK_ACTIONS -> Shape.TRIANGLE
                    else -> Shape.CIRCLE
                }
                stptSymbol(p, shape, sInk, if (shape == Shape.CIRCLE) 7f else 7.5f, 3f, dashed = mission)
            } else {
                // every map's steerpoint: a ring, a target a diamond; the mission file's view dashed
                drawSteerpoint(p, s.target, sInk, hollow = mission)
            }
            // a slot the cartridge places: a dot in the middle (BMS's route alone has none)
            if (s.inDtc && !s.open) drawCircle(base, 2.5f, p)
            if (layers.wdpLook) {
                val num = "${s.n}" + if (s.n == ip) " IP" else ""
                val text = if (showLabels && pr.pxPerNm > 5f) "$num ${s.name.orEmpty()}".trim() else num
                name(0, text, p + Offset(11f, -18f), label.copy(color = Hud.Amber))
            } else name(NamePrio.STPT, stptText(s.n, s.name, showLabels, pr), stptLabelAt(p), label.copy(color = Hud.Amber))
        }
        if (layers.trkDist && !hsd) drawLegLabels(pr, PlannerIntel.legLabels(route.map { Triple(it.n, it.at, it.action) }, layers.trkMin.toDouble()), ink, sink)
    }
    if (layers.offsets && d.shown.offsets.isNotEmpty()) {
        if (hsd) {
            val pts = d.shown.runIn.map { pr.toScreen(it.north, it.east) }
            drawDashedPolyline(HsdInk.white.copy(alpha = 0.6f), pts, 1.3f, FineOn)
            for (o in d.shown.offsets) {
                if (o.kind == AttackCue.Kind.TARGET) continue
                val p = pr.toScreen(o.at.north, o.at.east)
                if (!on(p)) continue
                val ink = if (o.kind == AttackCue.Kind.OA) HsdInk.oa else HsdInk.white
                val tri = Path().apply { moveTo(p.x, p.y - 7f); lineTo(p.x + 6f, p.y + 5f); lineTo(p.x - 6f, p.y + 5f); close() }
                if (o.kind == AttackCue.Kind.PUP) drawCircle(ink, 4.5f, p, style = Stroke(1.6f)) else drawPath(tri, ink, style = Stroke(1.6f))
                if (showLabels) name(1, o.label, p + Offset(8f, 2f), label.copy(color = ink, fontSize = 10.sp))
            }
        }
    }
    // the map: ONE attack, the current one (else the cartridge's), drawn as every map draws it, captioned — "not in the
    // cartridge — Save to DTC" when the cartridge as saved does not hold it
    if (layers.offsets && !hsd) d.drawn?.let { a -> drawAttack(a, pr, tm, label, placed) { on(it) } }
    // the flight's fields, over the route that leaves from them (a take-off steerpoint sits on its field): every map's
    // symbols, the theater's other fields with them, and the flight's named "HOME RKSO" in the airfield green
    if (!hsd) {
        if (layers.fields) for (f in d.fields) {
            val p = pr.toScreen(f.airport.x, f.airport.y)
            if (!on(p)) continue
            val dep = f.role.contains("departure"); val arr = f.role.contains("arrival"); val alt = f.role.contains("alternate")
            fieldMarks += Triple(p, airfieldRunways(f.airport), fieldMark(dep, arr, alt))
            fieldTag(dep, arr, alt)?.let { nameField(f.airport, it, p, sink) }
        }
        fieldMarks.forEach { (p, runways, mark) -> drawAirfield(p, runways, mark) }
    }
    if (hsd && layers.known(layers.fields)) for (f in d.fields) {
        val p = pr.toScreen(f.airport.x, f.airport.y)
        if (!on(p)) continue
        val ink = ghost(Hud.Green)
        drawCircle(halo, 11f, p, style = Stroke(5f))
        drawCircle(ink, 9f, p, style = Stroke(2.5f))
        f.airport.runways.mapNotNull { it.ends.firstOrNull()?.headingTrue }.map { ((it % 180) + 180) % 180 }.distinctBy { (it / 6).toInt() }.forEach { hdg ->
            val a = hdg * PI / 180
            val v = Offset(sin(a).toFloat(), -cos(a).toFloat())
            drawLine(ink, p - v * 13f, p + v * 13f, 2.5f, cap = StrokeCap.Round)
        }
        if (showLabels) name(4, (f.airport.icao ?: f.airport.name) + " " + fieldRoles(f.role), p + Offset(14f, 4f), label.copy(color = ink, fontSize = 10.sp))
    }
    // the station the cartridge's TACAN is tuned to
    if (!hsd) tunedAt(d)?.let { t -> val p = pr.toScreen(t.north, t.east); if (on(p)) drawTunedTacan(p, tm) }

    // ---------------------------------------------------------------- the measure, and a finger's last tap
    if (!hsd && WdpMapView.measureOn) {
        val a = WdpMapView.m1
        val b = WdpMapView.m2
        val cursor = if (a != null && b == null) MapCursor.pointer() else null
        val coords = DtcCoords(x.coords)
        drawMeasure(pr, a, b, cursor, listOfNotNull(a?.let { positionText(coords, it) }, b?.let { positionText(coords, it) }), tm)
    } else if (!hsd && WdpTouch.device) MapCursor.tapAt?.let { t ->
        val p = pr.toScreen(t.north, t.east)
        val ink = if (light) Color(0xFF111111) else Color.White
        drawLine(MapHalo, p - Offset(10f, 0f), p + Offset(10f, 0f), 3.5f); drawLine(MapHalo, p - Offset(0f, 10f), p + Offset(0f, 10f), 3.5f)
        drawLine(ink, p - Offset(9f, 0f), p + Offset(9f, 0f), 1.5f); drawLine(ink, p - Offset(0f, 9f), p + Offset(0f, 9f), 1.5f)
    }

    // ---------------------------------------------------------------- the names, the cartridge's first
    for (n in names.sortedBy { it.prio }) placeText(tm, n.text, n.at, n.style, placed)

    // ---------------------------------------------------------------- the selection, and a move waiting for its tap
    sel?.let { s ->
        val at = pickPos(d, s) ?: return@let
        val p = pr.toScreen(at.north, at.east)
        if (hsd) drawCircle(HsdInk.sel, 20f, p, style = Stroke(3f)) else drawSelection(p)
    }
    WdpMapView.moving?.let { m ->
        val at = pickPos(d, m) ?: return@let
        val p = pr.toScreen(at.north, at.east)
        drawCircle(if (hsd) HsdInk.sel else Hud.Magenta, 24f, p, style = Stroke(2f, pathEffect = Dashed))
    }
}


/**
 * Where the station the cartridge's TACAN is tuned to stands: a field's or a navaid's (T/R), or a tanker's (A/A TR,
 * the channel a receiver ties on with) at its station.
 */
internal fun tunedAt(d: WdpMapData): Pt? {
    val (ch, band, domain) = d.x.tacan ?: return null
    val b = if (band == 1) "Y" else "X"
    if (domain == 0) {
        // a channel is used by more than one station in a theater: the flight's own fields first, else the station
        // nearest the flight plan's first point
        d.fields.map { it.airport }.firstOrNull { a -> a.tacan?.let { it.channel == ch && it.band.equals(b, true) } == true }?.let { return Pt(it.x, it.y) }
        val from = d.shown.route.firstOrNull()?.at
        val all = d.x.airports.filter { a -> a.tacan?.let { it.channel == ch && it.band.equals(b, true) } == true }.map { Pt(it.x, it.y) } +
            d.x.navaids.filter { n -> n.tacan?.let { it.channel == ch && it.band.equals(b, true) } == true }.map { Pt(it.x, it.y) }
        return if (from == null) all.firstOrNull() else all.minByOrNull { it.dist(from) }
    }
    for (st in d.x.stations) {
        val own = st.support.tacan?.dropLast(1)?.toIntOrNull() ?: continue
        if (DtcFromMission.tieOn(own) == ch) return st.mid
    }
    for (t in d.known.tracks) {
        val own = t.support.tacan?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: continue
        if (DtcFromMission.tieOn(own) == ch || own == ch) return (t.legs.takeIf { it.size >= 2 } ?: t.points).let { mid(it) }
    }
    return null
}

/** "SA-2 Guideline" → "SA-2": a system's short name, as the HSD and a briefing write it. */
private fun siteShort(name: String): String {
    val first = name.trim().substringBefore(' ')
    return if (first.any { it.isDigit() } && first.length >= 3) first else name
}

/** "departure and arrival" → "DEP/ARR". */
private fun fieldRoles(role: String): String =
    role.split(" and ").joinToString("/") { when (it.trim()) { "departure" -> "DEP"; "arrival" -> "ARR"; "alternate" -> "ALT"; else -> it.uppercase() } }

// ==================================================================================================== the controls

/**
 * The chips over the map's top right: [Options] and [List] where the panel is not beside the map, [HSD preview] and
 * [Measure] everywhere; the HSD preview's own controls under them while it shows.
 */
@Composable
private fun Controls(d: WdpMapData, wide: Boolean, phone: Boolean, touch: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Hud.Bg.copy(alpha = 0.85f)).horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!wide) Box(Modifier.mapControl("Options")) {
                HudChip(if (WdpMapView.optionsOpen) "Options ✓" else "Options", WdpMapView.optionsOpen) {
                    WdpMapView.optionsOpen = !WdpMapView.optionsOpen
                    if (WdpMapView.optionsOpen) WdpMapView.listOpen = false
                }
            }
            if (!wide) Box(Modifier.mapControl("List")) {
                HudChip(if (WdpMapView.listOpen) "List ✓" else "List", WdpMapView.listOpen) {
                    WdpMapView.listOpen = !WdpMapView.listOpen
                    if (WdpMapView.listOpen) { WdpMapView.sel = null; WdpMapView.optionsOpen = false }
                }
            }
            Box(Modifier.mapControl("Hsd")) {
                HudChip(if (phone) (if (WdpMapView.hsd) "HSD ✓" else "HSD") else if (WdpMapView.hsd) "HSD preview ✓" else "HSD preview", WdpMapView.hsd) {
                    WdpMapView.hsd = !WdpMapView.hsd; WdpMapView.save()
                }
            }
            if (!WdpMapView.hsd) Box(Modifier.mapControl("Measure")) {
                HudChip(if (WdpMapView.measureOn) "Measure ✓" else "Measure", WdpMapView.measureOn) { WdpMapView.toggleMeasure() }
            }
            if (WdpMapView.hsd) Box(Modifier.mapControl("Ghosts")) {
                HudChip("Not in DTC", WdpMapView.ghosts) { WdpMapView.ghosts = !WdpMapView.ghosts; WdpMapView.save() }
            }
        }
        if (WdpMapView.hsd) HsdControls(d)
    }
}

@Composable
private fun HsdControls(d: WdpMapData) {
    val route = d.shown.stpts.sortedBy { it.n }
    val centre = WdpMapView.hsdCentre?.takeIf { n -> d.shown.stpt(n) != null } ?: PlannerMap.defaultCentre(d.shown)
    val range = WdpMapView.hsdRange.takeIf { it in PlannerMap.HSD_RANGES } ?: 60
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Hud.Bg.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val i = route.indexOfFirst { it.n == centre }
        HudChip("◂", false) { route.getOrNull(if (i <= 0) route.size - 1 else i - 1)?.let { WdpMapView.hsdCentre = it.n } }
        Text(centre?.let { "STPT $it" } ?: "No STPT", color = Hud.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        HudChip("▸", false) { route.getOrNull(if (i < 0 || i >= route.size - 1) 0 else i + 1)?.let { WdpMapView.hsdCentre = it.n } }
        Spacer(Modifier.width(6.dp))
        val k = PlannerMap.HSD_RANGES.indexOf(range)
        HudChip("−", false) { PlannerMap.HSD_RANGES.getOrNull(k - 1)?.let { WdpMapView.hsdRange = it; WdpMapView.save() } }
        Text("$range nm", color = Hud.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        HudChip("+", false) { PlannerMap.HSD_RANGES.getOrNull(k + 1)?.let { WdpMapView.hsdRange = it; WdpMapView.save() } }
    }
}

@Composable
private fun MoveBanner(m: MapPick) {
    val what = when (m) {
        is MapPick.Stpt -> "STPT ${m.n}"
        is MapPick.Ppt -> "PPT ${m.slot}"
        is MapPick.LinePt -> "point ${m.k + 1} of line ${m.line}"
        else -> "it"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Hud.Surface).border(1.dp, Hud.Magenta, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Tap the map where $what goes.", color = Hud.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        ActionButton(MapAction("Cancel") { null }, WdpTouch.device) { WdpMapView.moving = null }
    }
}

@Composable
private fun StatusLine(s: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Hud.Surface3.copy(alpha = 0.95f)).clickable { WdpMapView.status = null }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(s, color = Hud.Text, fontSize = 13.sp, lineHeight = 17.sp) }
}

@Composable
private fun NoCartridgeChip(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Hud.Surface3).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text("No cartridge loaded: nothing can be added", color = Hud.Amber, fontSize = 12.sp)
    }
}

@Composable
private fun PageNote(title: String, text: String, button: String?, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(12.dp)).background(Hud.Surface).border(1.dp, Hud.Outline, RoundedCornerShape(12.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = Hud.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = Hud.TextDim, fontSize = 14.sp, lineHeight = 19.sp)
            if (button != null) ActionButton(MapAction(button, primary = true) { null }, WdpTouch.device) { onClick() }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SelectionCard(c: MapCard, touch: Boolean, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Hud.Surface.copy(alpha = 0.97f)).border(1.dp, c.accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.title, color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            KindTag(c.tag, c.inDtc)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(if (touch) 40.dp else 28.dp).clip(RoundedCornerShape(14.dp)).clickable { WdpMapView.sel = null }, contentAlignment = Alignment.Center) {
                Text("×", color = Hud.TextDim, fontSize = 18.sp)
            }
        }
        c.lines.forEach { Text(it, color = Hud.TextDim, fontSize = 12.5.sp, lineHeight = 16.sp) }
        if (c.actions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val ui = remember { PageUi() }
            c.actions.forEach { a -> ActionButton(a, touch) { runAction(a, ui) } }
        }
    }
}

private fun runAction(a: MapAction, ui: MapUi) {
    val r = runCatching { a.run() }.getOrElse { "That did not work: " + (it.message ?: it::class.simpleName) }
    if (r != null) ui.status(r)
}

@Composable
private fun KindTag(text: String, inDtc: Boolean?) {
    val ink = when (inDtc) { true -> Hud.Green; false -> Hud.Amber; null -> Hud.TextDim }
    Box(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, ink.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(text, color = ink, fontSize = 10.5.sp, maxLines = 1)
    }
}

@Composable
private fun ActionButton(a: MapAction, touch: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = if (touch) 44.dp else 30.dp).clip(RoundedCornerShape(8.dp)).background(if (a.primary) Hud.Amber else Hud.Surface3)
            .clickable(onClick = onClick).then(if (a.label == OpenBankChooser.LABEL) Modifier.mapControl("OpenBank") else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(a.label, color = if (a.primary) Hud.Bg else Hud.Text, fontSize = if (touch) 14.sp else 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun ListSheet(d: WdpMapData, actions: MapActions, touch: Boolean) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(12.dp)).background(Hud.Surface.copy(alpha = 0.97f))
            .border(1.dp, Hud.Outline, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { PanelContent(d, actions, touch) }
}

/**
 * The list on a phone: a sheet over the whole map with its own ×, as the Planner's windows fill a phone's screen, and
 * rows at a finger's height ([Row2]). A tap on a row closes it and shows that thing's card on the map. It takes every
 * tap on it, so none reaches the map underneath.
 */
@Composable
private fun ListScreen(d: WdpMapData, actions: MapActions, touch: Boolean, modifier: Modifier) {
    Column(modifier.background(Hud.Bg).pointerInput(Unit) { detectTapGestures { } }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("On the map", color = Hud.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { WdpMapView.listOpen = false }, contentAlignment = Alignment.Center) {
                Text("×", color = Hud.TextDim, fontSize = 24.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Outline.copy(alpha = 0.5f)))
        // what the last action did (the page's own line is under this sheet)
        WdpMapView.status?.let { s -> Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) { StatusLine(s) } }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { PanelContent(d, actions, touch) }
    }
}

/** The list beside the map (below it on a tablet held upright, a sheet on a phone): what the HSD will show, what the mission knows beside it, the legend. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PanelContent(d: WdpMapData, actions: MapActions, touch: Boolean) {
    val ui = remember { PageUi() }
    fun pick(p: MapPick) { ui.select(p); WdpMapView.listOpen = false }
    // a long press on a field or a threat (by finger): straight to the Open bank chooser
    fun longBank(p: MapPick): (() -> Unit)? = if (!touch) null else actions.openBankFor(p)?.let { a -> { WdpMapView.listOpen = false; runAction(a, ui) } }
    Head("What your HSD will show")
    val plan = d.shown.route
    Row2(
        "STPT",
        if (plan.isEmpty()) "No flight plan" else "${plan.first().n}-${plan.last().n} flight plan" +
            plan.count { it.inDtc }.let { k -> if (k == 0) " (BMS's route; none in your DTC)" else " ($k in your DTC)" } +
            d.shown.stpts.count { it.inDtc && !it.onRoute }.let { k -> if (k > 0) " · $k more in your DTC" else "" },
    ) { plan.firstOrNull()?.let { pick(MapPick.Stpt(it.n)) } }
    if (d.shown.ppts.isEmpty()) Row2("PPT", "none") {}
    d.shown.ppts.forEach { p -> Row2("PPT ${p.slot}", p.name + if (p.marker) " (marker)" else " · ${PlannerMap.miles(p.rangeFt)} ring", SamRed.takeIf { !p.marker }) { pick(MapPick.Ppt(p.slot)) } }
    if (d.shown.lines.isEmpty()) Row2("Lines", "none") {}
    d.shown.lines.forEach { l -> Row2("L${l.n}", (l.name ?: "${l.points.size} points")) { pick(MapPick.LinePt(l.n, 0)) } }
    // the Open bank (DTC page's Open 1 and Open 2 tabs)
    d.shown.stpts.filter { it.open && it.inDtc }.sortedBy { it.n }.forEach { s ->
        Row2("STPT ${s.n}", (s.name ?: "a point") + " · " + DataCardPlan.actionString(s.action)) { pick(MapPick.Stpt(s.n)) }
    }
    if (d.shown.offsets.isNotEmpty()) Row2("Offsets", d.shown.offsets.joinToString(" ") { it.label }) { d.shown.offsets.firstOrNull()?.let { pick(MapPick.Offset(it.label, it.stpt)) } }
    if (d.bull != null) Row2("Bulls", "the campaign's bullseye") { pick(MapPick.Bullseye) }

    Head("Not in your DTC yet")
    val bulk = actions.bulk()
    if (bulk.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bulk.forEach { a -> ActionButton(a, touch) { runAction(a, ui) } }
    }
    val tracks = d.known.tracks.filter { it.inLine == null }
    tracks.forEach { t -> Row2(t.support.role, "${t.support.callsign} track" + (t.support.tacan?.let { " · TCN $it" } ?: ""), supportInk(t.support.role)) { pick(MapPick.Track(t.support.callsign)) } }
    d.known.stations.filter { it.inPpt == null && d.known.tracks.none { t -> t.support.callsign == it.support.callsign && t.inLine == null } }.forEach { s ->
        Row2(s.support.role, "${s.support.callsign} station", supportInk(s.support.role)) { pick(MapPick.Station(s.support.callsign)) }
    }
    val sites = d.known.sites.withIndex().filter { it.value.inPpt == null }.sortedBy { it.value.edgeFt ?: Double.MAX_VALUE }
    sites.take(12).forEach { (i, s) ->
        Row2(
            "Threat", s.site.name + (s.ringFt?.let { " · ${PlannerMap.miles(it)}" } ?: "") + (if (s.crossesRoute) " · crosses your route" else s.edgeFt?.let { " · ${PlannerMap.miles(it)} off" }.orEmpty()),
            SamRed, onLong = longBank(MapPick.Site(i)),
        ) { pick(MapPick.Site(i)) }
    }
    if (sites.size > 12) Text("and ${sites.size - 12} more on the map", color = Hud.TextFaint, fontSize = 12.sp)
    d.fields.forEach { f -> Row2("Field", "${f.airport.name} (${f.role})", Hud.Green, onLong = longBank(MapPick.Field(f.airport.id))) { pick(MapPick.Field(f.airport.id)) } }
    if (d.attack != null) Row2("Attack", "${d.attack.page} page's offsets", AttackMagenta) { pick(MapPick.Attack) }
    if (tracks.isEmpty() && sites.isEmpty() && d.known.stations.none { it.inPpt == null } && d.fields.isEmpty()) Text("Nothing: the cartridge carries all the mission knows.", color = Hud.TextDim, fontSize = 12.5.sp)
    d.facts.notes.forEach { Text(it, color = Hud.TextFaint, fontSize = 12.sp, lineHeight = 16.sp) }

    Head("How to read it")
    Text(
        "Unmarked: in your DTC, the way the HSD will show it (unsaved edits too). Marked \"not in DTC\": the mission knows it, " +
            "your cartridge does not carry it. Tap anything to see what it is and add it as a line, a PPT, a steerpoint or a target; " +
            "a DTC item can be moved or taken out. Every change here is the DTC page's own edit: Save to DTC writes it.",
        color = Hud.TextDim, fontSize = 12.sp, lineHeight = 16.sp,
    )
}

@Composable
private fun Head(text: String) = Text(text.uppercase(), color = Hud.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold)

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Row2(key: String, value: String, ink: Color? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    // by finger (WdpTouch) a row is a finger's height, as the Planner's windows' rows are; a long press is the row's
    // second action where it has one (the Open bank chooser, without the card first)
    val touch = WdpTouch.device
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (touch) 44.dp else 0.dp).clip(RoundedCornerShape(6.dp))
            .then(if (onLong != null) Modifier.combinedClickable(onLongClick = onLong, onClick = onClick).plannerProbe("Map/ListBank/$value") else Modifier.clickable(onClick = onClick))
            .padding(vertical = 3.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = ink ?: Hud.TextDim, fontSize = if (touch) 13.sp else 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(if (touch) 70.dp else 64.dp), maxLines = 1)
        Text(value, color = Hud.Text, fontSize = if (touch) 14.sp else 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
