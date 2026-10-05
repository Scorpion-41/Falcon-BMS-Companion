package com.bmscompanion.app.ui.screens.mission

import com.bmscompanion.app.ui.components.drawDashedLine
import com.bmscompanion.app.ui.components.AttackInks
import com.bmscompanion.app.ui.components.drawAttackModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import com.bmscompanion.app.ui.components.MapLook
import com.bmscompanion.app.ui.theme.inMapInks
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.Contact
import com.bmscompanion.app.data.mission.HostileContacts
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.Kneeboard
import com.bmscompanion.app.ui.KneeboardActionsSlot
import com.bmscompanion.app.ui.KneeboardButton
import com.bmscompanion.app.ui.components.MapLookMenuItems
import com.bmscompanion.app.ui.components.HudChip
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.MapState
import com.bmscompanion.app.ui.components.Tag
import com.bmscompanion.app.ui.components.TheaterMap
import com.bmscompanion.app.ui.components.isWide
import com.bmscompanion.app.ui.components.safeText
import com.bmscompanion.app.ui.go
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

sealed interface MapSel {
    data class Ctc(val id: String) : MapSel
    data class Stp(val n: Int) : MapSel
    data class Field(val id: Int) : MapSel
    data class Ppt(val index: Int) : MapSel
    data class Pt(val x: Double, val y: Double) : MapSel
}

/** How far the Attack chip zooms: an attack's cues, a few miles either side of the target, across the map. */
private const val ATTACK_ZOOM = 110f

// An attack's cues are drawn by the one attack drawing (ui/components/AttackDraw.kt), in its own inks.

object MapLayers {
    var follow by mutableStateOf(Repo.getInt("m_follow", 1) == 1)
    var route by mutableStateOf(Repo.getInt("m_route", 1) == 1)
    /**
     * Air defences, under one switch: the mission's threats ([com.bmscompanion.app.data.mission.MissionGround]: the
     * sites the briefing named, else the spotted ones along the route) with the reach of each system, the rings of
     * the pre-planned threats entered in the DTC, and the live ones of the Tacview feed that are one of those. Nothing
     * else: a site the side has not spotted is never on the map, and there is no switch that would put it there.
     */
    var sams by mutableStateOf(Repo.getInt("m_sams", 1) == 1 && Repo.getInt("m_threats", 1) == 1)
    /** Friendly, neutral and the rest of your own flight. Independent of [hostiles]. */
    var traffic by mutableStateOf(Repo.getInt("m_traffic", 1) == 1)
    /** The other side, counted by [com.bmscompanion.app.data.mission.Contact.hostile]. Independent of [traffic]. */
    var hostiles by mutableStateOf(Repo.getInt("m_hostiles", 1) == 1)
    var labels by mutableStateOf(Repo.getInt("m_labels", 1) == 1)
    /**
     * Every other airfield of the theater. Off by default: the flight's own — where it takes off, lands and its
     * alternate — are always drawn, and the rest only crowded the map (a key of its own, so the old "m_fields",
     * which was on for everyone, does not turn them all back on).
     */
    var fields by mutableStateOf(Repo.getInt("m_allfields", 0) == 1)
    /** The tanker and AWACS tracks the campaign planned, and the stations the briefing describes in words. */
    var support by mutableStateOf(Repo.getInt("m_support", 1) == 1)
    /** The attack the plan sent from the Planner carries: VRP or VIP, pull-up point, offsets, the run-in. */
    var attack by mutableStateOf(Repo.getInt("m_attack", 1) == 1)
    /** The cartridge's lines (LINES STPT 31-54, four groups of six), dashed like the HSD draws them. */
    var lines by mutableStateOf(Repo.getInt("m_lines", 1) == 1)
    /**
     * Everything a plan puts on the map, at once: its points, PPTs and lines and its attack (WDP mode's populated
     * flight, or a plan an older device still has the PC lay over the briefing). Off, the map is what it would be
     * without it.
     */
    var plan by mutableStateOf(Repo.getInt("m_plan", 1) == 1)
    fun save() {
        Repo.putInt("m_follow", if (follow) 1 else 0); Repo.putInt("m_route", if (route) 1 else 0)
        Repo.putInt("m_traffic", if (traffic) 1 else 0); Repo.putInt("m_hostiles", if (hostiles) 1 else 0); Repo.putInt("m_labels", if (labels) 1 else 0)
        Repo.putInt("m_allfields", if (fields) 1 else 0)
        // one key for both, and the old one kept in step so an older version reads it the same way
        Repo.putInt("m_sams", if (sams) 1 else 0); Repo.putInt("m_threats", if (sams) 1 else 0)
        Repo.putInt("m_support", if (support) 1 else 0)
        Repo.putInt("m_attack", if (attack) 1 else 0)
        Repo.putInt("m_lines", if (lines) 1 else 0); Repo.putInt("m_plan", if (plan) 1 else 0)
    }
}

// Symbol colours. On a board they come from the skin instead, so a printed page is drawn in printed inks
// rather than in the HUD's cyan and red.
val Friendly: Color get() = if (Hud.paperInks) Hud.Blue else if (Hud.onLightMap) Hud.Cyan else Color(0xFF56C8F5)
val Hostile: Color get() = if (Hud.paperInks) Hud.Red else if (Hud.onLightMap) Hud.Red else Color(0xFFFF5A5F)
val Neutral: Color get() = if (Hud.paperInks) Hud.Amber else if (Hud.onLightMap) Hud.Amber else Color(0xFFFFD27A)

/**
 * What a symbol is outlined with so that it holds on whatever is underneath: dark behind a bright symbol on relief or
 * satellite, white behind a dark one on the chart.
 */
val MapHalo: Color get() = if (Hud.onLightMap) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.6f)

/**
 * Your own flight. Lime rather than the tanker's mint, and well away from your own amber, so the three read apart at
 * a glance: you, the jets flying on your wing, and everyone else on your side.
 */
val Wingman: Color get() = if (Hud.paperInks) Hud.Green else if (Hud.onLightMap) Color(0xFF3F7A00) else Color(0xFFB4F04A)

fun contactColor(c: Contact) = when {
    c.own -> Hud.Amber
    c.wingman -> Wingman
    supportColor(c) != null -> supportColor(c)!!
    c.friendly -> Friendly
    c.neutral || c.coalition.isNullOrBlank() -> Neutral
    else -> Hostile
}

/** Ejected crew in a parachute or on the ground. Older bridges sent them as "air" named "Ejected Crew". */
fun Contact.isCrew() = kind == "crew" || name?.contains("Ejected", ignoreCase = true) == true

/** Everything the live map and its side panels draw, derived once per update of the bridge data. */
class MapData(
    val stpts: List<Stpt>,
    val route: List<Stpt>,
    val ppts: List<Threat>,
    val marks: List<com.bmscompanion.app.data.mission.NavPoint>,
    val live: com.bmscompanion.app.data.mission.Live?,
    val own: Pair<Double, Double>?,
    val ownPos: Pair<Double, Double>?,
    val ownHdg: Double,
    val ctcs: List<Contact>,
    val sams: List<SamSite>,
    val stations: List<SupportStation>,
    val tracks: List<com.bmscompanion.app.data.mission.SupportTrack>,
    val bull: Pair<Double, Double>?,
    val depField: Airport?,
    val arrField: Airport?,
    val altField: Airport?,
    val contactsConnected: Boolean?,
    // 1.3.8: the merged mission (PlanMerge) the rest was drawn from, its lines and its attack
    val merged: com.bmscompanion.app.data.mission.MergedMission = com.bmscompanion.app.data.mission.MergedMission(),
    val lines: List<List<com.bmscompanion.app.data.mission.MergedPoint>> = emptyList(),
    val attack: com.bmscompanion.app.data.mission.AttackOverlay? = null,
    /** [bull] is the save header's bullseye (before 3D), not the live one */
    val bullFromSave: Boolean = false,
    /** a plan is applied, whether or not the Plan layer shows it (the chip is offered while it is) */
    val planAvailable: Boolean = false,
    /** the mission's threat picture ([MissionData.ground]) */
    val ground: com.bmscompanion.app.data.mission.MissionGround? = null,
    /** its air defences and ships, each with its reach, drawn by [drawMissionSites] */
    val groundSites: List<com.bmscompanion.app.data.mission.MissionPicture.Site> = emptyList(),
    /** the route the flight plan joins (feet), for whether a site is named */
    val routeFt: List<Pair<Double, Double>> = emptyList(),
)

/** What of [MapData] changes only with the mission (not with every live tick): worked out once per mission change. */
private class MapBase(
    val merged: com.bmscompanion.app.data.mission.MergedMission,
    val stpts: List<Stpt>,
    val route: List<Stpt>,
    val routeFt: List<Pair<Double, Double>>,
    val ppts: List<Threat>,
    val tracks: List<com.bmscompanion.app.data.mission.SupportTrack>,
    val stations: List<SupportStation>,
    val groundSites: List<com.bmscompanion.app.data.mission.MissionPicture.Site>,
    /** the sites a live air defence must be one of to be drawn: the mission's and the PPTs */
    val known: List<com.bmscompanion.app.data.mission.CampSite>,
)

/** Which of the two roles a briefing station is, in the words the campaign uses. */
private fun role(st: SupportStation) = if (st.role.contains("tanker", true)) "Tanker" else "AWACS"

@Composable
fun rememberMapData(env: MissionEnv): MapData {
    val live by MissionLink.live.collectAsState()
    val contacts by MissionLink.contacts.collectAsState()
    val mission by MissionLink.mission.collectAsState()
    // the threat reference is read once and kept: it is what gives an air defence its ring
    val reference by androidx.compose.runtime.produceState(emptyList<com.bmscompanion.app.data.Threat>()) {
        value = com.bmscompanion.app.data.Repo.threats()
    }
    // the theater's towns, so "20 nm northeast of Larissa" can be turned back into a place
    val geo by androidx.compose.runtime.produceState<com.bmscompanion.app.data.GeoLayers?>(null, env.theater?.mapId) {
        value = env.theater?.mapId?.let { com.bmscompanion.app.data.Repo.geo(it) }
    }
    val showPlan = MapLayers.plan
    // The merge reads only the jet's steerpoints from the live data, so it is worked out again when those change and
    // when the mission does — not on each of the four live ticks a second, which made every frame of a pan wait on it.
    val nav = live?.navPoints
    val base = remember(nav, mission, env.set, reference, geo, showPlan) {
        // the Plan chip off is the map without the plan: the same merge, with nothing sent
        val merged = com.bmscompanion.app.data.mission.PlanMerge.merge(if (showPlan) mission else mission?.copy(plan = null), live)
        val stpts = steerpoints(merged)
        val route = stpts.filter { it.hasPos }
        // the campaign's tanker and AWACS tracks; with no printed briefing, those of the save flight the Planner sent
        val tracks = plannedTracks(mission?.tracks.orEmpty(), merged)
        val ppts = preplannedPoints(merged)
        val groundSites = missionSites(mission?.ground, reference)
        MapBase(
            merged = merged, stpts = stpts, route = route,
            routeFt = route.filter { it.onRoute }.map { it.x!! to it.y!! },
            ppts = ppts, tracks = tracks,
            // the same stations as the Support card: your tanker on the merged route's Refuel steerpoint (the plan's,
            // the cartridge's, BMS's believed route or the save's flight), the others where the briefing says
            stations = plannedStations(merged, env.set, geo, tracks),
            groundSites = groundSites,
            known = groundSites.map { com.bmscompanion.app.data.mission.CampSite(system = it.system, x = it.x, y = it.y, spotted = true) } +
                ppts.filter { !it.marker }.map { com.bmscompanion.app.data.mission.CampSite(system = it.name, x = it.x, y = it.y, spotted = true) },
        )
    }
    // the feed's air defences: only those that are one of the mission's known sites (the PC sends no others either)
    val sams = remember(contacts, base, reference) {
        val all = contacts?.contacts.orEmpty().filter { it.kind == "sam" && it.hostile }
        val known = all.filter { c -> com.bmscompanion.app.data.mission.MissionPicture.knownSite(c.name, c.x, c.y, base.known) }.map { it.id }.toSet()
        if (known.isEmpty()) emptyList() else samSites(all.filter { it.id in known }, reference)
    }
    return remember(live, contacts, base, sams, mission) {
        val merged = base.merged
        val own = ownship(live)
        val ctcs = contacts?.contacts.orEmpty()
        val ownContact = ctcs.firstOrNull { it.own }
        val bases = if (merged.briefing != null) airbases(live, merged, ctcs) else airbases(live, mission?.briefing, ctcs)
        val liveBull = bullseye(live, ctcs)
        // the save's own bullseye stands in before 3D only; in 3D the jet's is the one
        val saveBull = if (liveBull == null && !merged.inJet) merged.saveBullseye
            ?: mission?.ground?.let { g -> if (g.bullseyeX != null && g.bullseyeY != null) g.bullseyeX to g.bullseyeY else null } else null
        MapData(
            stpts = base.stpts,
            route = base.route,
            ppts = base.ppts,
            marks = markpoints(live),
            live = live,
            own = own,
            ownPos = own ?: ownContact?.let { it.x to it.y },
            ownHdg = if (own != null) live!!.hdgTrue else ownContact?.hdg ?: 0.0,
            ctcs = ctcs,
            sams = sams,
            stations = base.stations,
            tracks = base.tracks,
            bull = liveBull ?: saveBull,
            depField = bases.departureIn(env.set),
            arrField = bases.arrivalIn(env.set),
            altField = bases.alternateIn(env.set),
            contactsConnected = contacts?.connected,
            merged = merged,
            lines = merged.lines,
            attack = merged.attack,
            bullFromSave = saveBull != null,
            planAvailable = mission?.plan?.applied == true,
            ground = mission?.ground,
            groundSites = base.groundSites,
            routeFt = base.routeFt,
        )
    }
}

@Composable
fun MissionMapPane(env: MissionEnv, state: MapState, sel: MapSel?, onSel: (MapSel?) -> Unit, onOpenTab: (MissionTab) -> Unit) {
    if (env.theater == null) {
        PaneEmpty("No theater yet", "Connect to the BMS PC to load the mission theater.", "Setup") { env.nav.go(com.bmscompanion.app.ui.Routes.SETUP) }
        return
    }
    val d = rememberMapData(env)
    // on a kneeboard the map is the whole board, so its options move into a ⋯ next to the sections button
    if (Kneeboard.on) KneeboardActionsSlot(Unit) { MapOptionsButton() }
    if (isWide()) {
        Row(Modifier.fillMaxSize()) {
            LiveMap(env, d, state, sel, onSel, Modifier.weight(1f).fillMaxHeight(), flightStrip = false)
            Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline.copy(alpha = 0.5f)))
            Column(Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (sel != null) SelectionCard(env, sel, d, Modifier.fillMaxWidth()) { onSel(null) }
                FlightTiles(d.live, d.bull, compact = true)
                // the Map tab drops the card while hostile contacts are off (the Dashboard's keeps its note)
                if (rememberHostilesOn()) PictureCard(d.ctcs, d.ownPos, d.ownHdg, d.bull, onPick = { onSel(MapSel.Ctc(it.id)) })
                SteerpointList(d.stpts, d.ownPos, d.bull, selected = (sel as? MapSel.Stp)?.n) { s -> onSel(MapSel.Stp(s.n)); state.flyTo(s.x!!, s.y!!, maxOf(state.scale, 4f)); MapLayers.follow = false }
            }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            LiveMap(env, d, state, sel, onSel, Modifier.fillMaxSize(), flightStrip = true)
            if (sel != null) SelectionCard(env, sel, d, Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 64.dp, bottom = 10.dp).widthIn(max = 520.dp).fillMaxWidth()) { onSel(null) }
        }
    }
}

/**
 * The live theater map with its layer chips, recenter button and selection highlight. Used full-screen by the Map tab
 * and as a card on the Dashboard ([compactControls] folds the layer chips behind one button).
 */
@Composable
fun LiveMap(
    env: MissionEnv,
    d: MapData,
    state: MapState,
    sel: MapSel?,
    onSel: (MapSel?) -> Unit,
    modifier: Modifier,
    flightStrip: Boolean,
    compactControls: Boolean = false,
    /** A VR board has no pointer, so it gets the chart and none of the controls. */
    bare: Boolean = false,
    /** Keep the theater covering the frame at every zoom — what a dashboard card wants, not a full page. */
    fillBox: Boolean = false,
) {
    val th = env.theater ?: return
    // the attack is the one Populate from Planner took (WDP mode) or the cartridge's own (EZBoards mode, 1.3.8:
    // PlanMerge.cartridgeAttack), and only on a map of the theater it was planned in: sim feet from one theater are a
    // place in every other. Every copy of the map — a board's too — reads it from the mission.
    val planAttack = d.attack?.takeIf { it.theater.isEmpty() || it.theater.equals(th.id, ignoreCase = true) }
    val link by MissionLink.state.collectAsState()
    // measuring labels is the most expensive part of a redraw: cache layouts across the 4 Hz updates
    // the same table the Tankers & AWACS page builds: it carries the channel and which one is yours
    val support = rememberSupportAssets(env)
    val tm = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    val hitPx = with(density) { 30.dp.toPx() }
    var layersOpen by remember { mutableStateOf(!compactControls) }
    // Two independent switches, not one nested in the other: Traffic is your own side, Hostiles is theirs. A pilot who
    // turns Hostiles off wants the enemy off the map and his own flight left on it, and one who turns Traffic off wants
    // the reverse. Nesting them let Traffic quietly govern both, so switching Traffic on brought the enemy back with it.
    // The switch is a second filter: hostile contacts reach a device at all only while the PC's setting is on
    // (HostileContacts, off by default), and the chip is not offered otherwise.
    val hostilesOn = rememberHostilesOn()
    val showHostiles = hostilesOn && MapLayers.hostiles
    val showTraffic = MapLayers.traffic
    val visibleContacts = remember(d.ctcs, showHostiles, showTraffic) {
        d.ctcs.filter { c ->
            c.kind != "bullseye" && c.kind != "sam" && !c.own &&
                if (c.hostile) showHostiles else showTraffic
        }
    }
    // the theater's airfields that are drawn, each with its runways, worked out once per theater rather than on every
    // frame of a pan (a theater has hundreds, and each frame built a list for each)
    val fieldRunways = remember(env.set) {
        env.set?.airports.orEmpty().map { Triple(it, airfieldRunways(it), mapsAirfield(it)) }
    }

    // First view: the route (or ownship) instead of the whole theater.
    // and again after a new mission or a switch of mode (MissionEpoch): the new route, not the last one's
    val epoch = MissionEpoch.n
    var framed by remember(th.id, epoch) { mutableStateOf(MissionEpoch.framed(state, state.initialized)) }
    LaunchedEffect(th.id, epoch, d.route.isNotEmpty(), d.ownPos != null) {
        if (framed) return@LaunchedEffect
        val target = d.ownPos ?: (d.route.firstOrNull { it.onRoute } ?: d.route.firstOrNull())?.let { it.x!! to it.y!! } ?: return@LaunchedEffect
        state.flyTo(target.first, target.second, 3.5f)
        framed = true
        MissionEpoch.markFramed(state)
    }
    LaunchedEffect(d.live?.t, MapLayers.follow) {
        if (MapLayers.follow && d.own != null) state.flyTo(d.own.first, d.own.second, maxOf(state.scale, 3f))
    }

    val selPos: Pair<Double, Double>? = when (sel) {
        is MapSel.Ctc -> d.ctcs.firstOrNull { it.id == sel.id }?.let { it.x to it.y }
        is MapSel.Stp -> d.stpts.firstOrNull { it.n == sel.n && it.hasPos }?.let { it.x!! to it.y!! }
        is MapSel.Field -> env.set?.airports?.firstOrNull { it.id == sel.id }?.let { it.x to it.y }
        is MapSel.Ppt -> d.ppts.getOrNull(sel.index)?.let { it.x to it.y }
        is MapSel.Pt -> sel.x to sel.y
        null -> null
    }


    Box(modifier) {
        TheaterMap(
            // Close enough to read an attack's cues — a pull-up point two or three miles out, offsets a few thousand
            // feet from the target — which at the old limit were one blob. Past the sharpest tiles the ground only
            // magnifies, but the symbols and lines stay sharp, and they are what is looked at that close.
            th.map, th.sizeFt, Modifier.fillMaxSize(), state, maxScale = ATTACK_ZOOM * 1.6f, fillWidth = false, zoomButtons = !bare,
            fillBox = fillBox,
            onUserGesture = { if (MapLayers.follow) { MapLayers.follow = false; MapLayers.save() } },
            onTap = { x, y, pr ->
                val tap = pr.toScreen(x, y)
                fun dist(px: Double, py: Double) = pr.toScreen(px, py).let { hypot((it.x - tap.x).toDouble(), (it.y - tap.y).toDouble()) }
                val cands = buildList<Pair<MapSel, Double>> {
                    visibleContacts.forEach { add(MapSel.Ctc(it.id) to dist(it.x, it.y)) }
                    if (MapLayers.route) d.route.forEach { add(MapSel.Stp(it.n) to dist(it.x!!, it.y!!) + 4) }
                    if (MapLayers.sams || MapLayers.support) d.ppts.forEachIndexed { i, p -> if (if (p.marker) MapLayers.support || MapLayers.sams else MapLayers.sams) add(MapSel.Ppt(i) to dist(p.x, p.y) + 6) }
                    if (MapLayers.sams) d.sams.forEach { add(MapSel.Ctc(it.id) to dist(it.x, it.y) + 6) }
                    val flightFields = setOfNotNull(d.depField?.id, d.arrField?.id, d.altField?.id)
                    env.set?.airports?.forEach { if (it.id in flightFields || MapLayers.fields && mapsAirfield(it)) add(MapSel.Field(it.id) to dist(it.x, it.y) + 8) }
                }
                val best = cands.minByOrNull { it.second }
                onSel(if (best != null && best.second < hitPx * 1.6) best.first else MapSel.Pt(x, y))
            },
        ) { pr -> inMapInks(MapLook.light) {
            // The colours are taken here, inside the pass: remembered once, they kept whatever ink was current when
            // the map first appeared. A label is white with a dark blur behind it on relief, satellite and dark — and
            // the other way about on the chart, where white on white with a black blur was unreadable.
            // Everything here is drawn with the marks every map of the mission shares (MissionMapMarks.kt), so the
            // Planner's Map page draws the same mission the same way; the labels are gathered and placed last, the
            // flight plan's first (MapNames).
            val labelStyle = mapLabelStyle()
            val stptStyle = labelStyle.copy(color = Hud.Amber)
            val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
            val names = MapNames()
            fun on(p: Offset) = onMap(p)

            // Airfields, drawn as airfields: a ring with the field's own runways across it, at their real headings.
            // Where you take off and land is solid, the alternate an open ring, and (only with All airfields on) every
            // other field small and faint — three kinds that read apart without a legend. The symbols themselves are drawn last, over everything
            // else on the map: a take-off steerpoint sits on the field it leaves from, aircraft sit on it on the
            // ground, and either of them hid the airfield underneath. A carrier is drawn only when it is the flight's:
            // the theater's list has it where the campaign started it.
            val fieldSymbols = ArrayList<Triple<Offset, List<Offset>, FieldMark>>()
            fieldRunways.forEach { (a, runways, drawn) ->
                val p = pr.toScreen(a.x, a.y)
                if (!on(p)) return@forEach
                val dep = a.id == d.depField?.id
                val arr = a.id == d.arrField?.id
                val alt = a.id == d.altField?.id
                val tag = fieldTag(dep, arr, alt)
                if (tag == null && (!MapLayers.fields || !drawn)) return@forEach
                fieldSymbols += Triple(p, runways, fieldMark(dep, arr, alt))
                if (tag != null) nameField(a, tag, p, names)
            }

            // Pre-planned threats: a ring for a threat, and for a marker (AWACS, tanker, a friendly — Ppt.ini gives those
            // a tenth of a foot) a small flag with no ring, which is what the HSD shows. What the plan put there and the
            // jet does not have yet is dashed with a hollow centre; what it cleared is faint.
            if (MapLayers.sams) d.ppts.filter { !it.marker }.forEach { t ->
                val c = pr.toScreen(t.x, t.y)
                val r = (t.rangeNm * pr.pxPerNm).toFloat()
                if (!ringOnMap(c, r)) return@forEach
                drawPptRing(c, r, RING_FILL, plan = t.notInJet, fade = if (t.cleared) 0.35f else 1f)
                names.add(NamePrio.PPT, planLabel(t.name, t.fromPlan, t.notInJet, t.cleared), pptLabelAt(c), labelStyle.copy(color = if (t.notInJet) PlanInk else SamRed))
            }
            if (MapLayers.sams || MapLayers.support) d.ppts.filter { it.marker }.forEach { t ->
                val c = pr.toScreen(t.x, t.y)
                if (!on(c)) return@forEach
                val ink = if (t.notInJet) PlanInk else Friendly
                drawPptMarker(c, ink, dashed = t.notInJet, fade = if (t.cleared) 0.4f else 1f)
                if (MapLayers.labels) names.add(NamePrio.PPT, planLabel(t.name, t.fromPlan, t.notInJet, t.cleared), markerLabelAt(c), labelStyle.copy(color = ink))
            }

            // The cartridge's lines, each joined in its own order and never to another, dashed like the HSD draws
            // them. A plan's line the jet does not have yet is drawn thinner in the Planner's colour.
            if (MapLayers.lines) d.lines.forEach { line ->
                val first = line.firstOrNull() ?: return@forEach
                val pts = line.map { pr.toScreen(it.x, it.y) }
                if (pts.none { on(it) }) return@forEach
                val ink = if (first.notInJet) PlanInk else LineInk
                drawCartridgeLine(pts, ink, plan = first.notInJet, fade = if (first.cleared) 0.35f else 1f)
                if (MapLayers.labels) names.add(NamePrio.LINE, planLabel("LINE ${first.line}", first.source == com.bmscompanion.app.data.mission.PlanItemSource.PLAN, first.notInJet, first.cleared), lineLabelAt(pts[0]), labelStyle.copy(color = ink))
            }

            // The air defences BMS is streaming, each with the reach of its own system. Drawn under everything
            // that moves: a ring is a place you do not want to be, not a thing to look at.
            if (MapLayers.sams) d.sams.forEach { site ->
                val c = pr.toScreen(site.x, site.y)
                val ink = if (site.friendly) Friendly else SamRed
                if (!drawLiveSam(c, ((site.rangeNm ?: 0.0) * pr.pxPerNm).toFloat(), ink)) return@forEach
                if (MapLayers.labels) names.add(NamePrio.SITE, site.label, c + Offset(9f, 2f), labelStyle.copy(color = ink))
            }

            // The mission's threats (MissionPicture): the sites the briefing names (else the spotted ones along the
            // route), drawn as the Planner's Map page draws them, so every map of the mission looks the same by default
            if (MapLayers.sams) drawMissionSites(pr, d.groundSites, MapLayers.labels, RING_FILL, names, d.routeFt)

            // What the campaign planned for the tankers and the AWACS, when the mission file gave it to us: only the
            // leg they hold on, drawn as the corridor a pilot goes looking for (their transit is not drawn).
            if (MapLayers.support) d.tracks.forEach { t ->
                // the one your flight was given is the one you will actually fly to: drawn as such, with the
                // channel you tune written where you are looking — the channel comes from the support table, which
                // knows the briefing's TACAN and BMS's own allocation
                val legs = t.points.filter { it.station }
                val asset = support.firstOrNull { a -> a.callsign.equals(t.callsign, true) }
                drawSupportTrack(
                    pr,
                    legs.takeIf { it.size >= 2 }?.first()?.let { com.bmscompanion.app.data.wdp.DtcFromMission.Pt(it.x, it.y) },
                    legs.takeIf { it.size >= 2 }?.last()?.let { com.bmscompanion.app.data.wdp.DtcFromMission.Pt(it.x, it.y) },
                    trackInk(t.role), t.yours,
                    if (MapLayers.labels) trackLabel(t.callsign ?: t.role.uppercase(), asset?.tacan, t.yours) else null, names,
                )
            }

            // Where the briefing says the tanker and the AWACS will be — on the map from the moment it is printed,
            // and drawn as an area rather than a point, because a station is an orbit and not a parking space.
            // A role the campaign gave us a real track for needs none of this.
            if (MapLayers.support) d.stations.forEach { st ->
                if (d.tracks.any { it.role.equals(role(st), true) }) return@forEach
                drawSupportStation(
                    pr.toScreen(st.x, st.y), (STATION_NM * pr.pxPerNm).toFloat(), trackInk(st.role),
                    if (MapLayers.labels) "${st.role.uppercase()} ${st.callsign} station" else null, names,
                )
            }

            // bullseye (before 3D possibly the save header's own, which says so)
            d.bull?.let { (bx, by) ->
                val c = pr.toScreen(bx, by)
                drawBullseye(c, pr, rings = 5, ringNm = 20)
                if (d.bullFromSave && MapLayers.labels) names.add(NamePrio.BULL, "BULLSEYE (from the save)", c + Offset(12f, 6f), labelStyle.copy(color = Hud.Cyan))
            }

            // Flight plan: the route line joins flight-plan points only — the briefing's rows and the plan's own points.
            // A precision target (the recon bank, action -1) is marked where it is and never joined: joining it drew a
            // leg from the last route point out to the target area.
            val flightPlan = d.route.filter { it.onRoute }
            if (MapLayers.route && flightPlan.size > 1) drawRouteLegs(
                flightPlan.map { s -> LegPt(pr.toScreen(s.x!!, s.y!!), land = s.isLand, refuel = s.isRefuel, alternate = s.isAlternate) },
                Hud.Amber,
            )
            if (MapLayers.route) d.route.forEach { s ->
                val p = pr.toScreen(s.x!!, s.y!!)
                // Plan and jet disagree: the plan's point is drawn hollow and dashed in the Planner's colour, joined by
                // a thin line to where the jet has it (in 3D the jet's is the one shown; before 3D the plan's is, and
                // the line goes to where the jet would load it)
                val other = if (s.otherX != null && s.otherY != null) pr.toScreen(s.otherX, s.otherY) else null
                if (s.notInJet && other != null && (on(p) || on(other))) {
                    drawDashedLine(PlanInk.copy(alpha = 0.8f), p, other, 1.5f, floatArrayOf(5f, 5f))
                    val planAt = if (s.source == com.bmscompanion.app.data.mission.PlanItemSource.JET) other else p
                    drawCircle(MapHalo, 11f, planAt, style = Stroke(4.5f))
                    drawCircle(PlanInk, 10f, planAt, style = Stroke(2.5f, pathEffect = PlanDash))
                }
                if (!on(p)) return@forEach
                val hollowPlan = s.notInJet && s.source != com.bmscompanion.app.data.mission.PlanItemSource.JET
                val ink = if (hollowPlan || !s.isTarget && s.fromPlan) PlanInk else if (s.isTarget) Hostile else Hud.Amber
                drawSteerpoint(p, s.isTarget, ink, hollow = hollowPlan, fade = if (s.cleared) 0.4f else 1f)
                val base = stptText(s.n, s.desc ?: s.targetName?.takeIf { s.isTarget } ?: if (s.planRow) s.title else null, MapLayers.labels, pr)
                val text = if (MapLayers.labels) planLabel(base, s.fromPlan, s.notInJet, s.cleared) else base
                names.add(NamePrio.STPT, text, stptLabelAt(p), if (s.notInJet || s.fromPlan) stptStyle.copy(color = PlanInk) else stptStyle)
            }
            // The attack the plan carries, around its target (drawAttack, the same on a VR board's map)
            if (MapLayers.attack && planAttack != null) drawAttack(planAttack, pr, tm, labelStyle, placed) { on(it) }
            // markpoints and datalink points
            if (MapLayers.route) d.marks.forEach { m ->
                val p = pr.toScreen(m.x, m.y)
                drawRect(Hud.Magenta, p - Offset(6f, 6f), androidx.compose.ui.geometry.Size(12f, 12f), style = Stroke(2.5f))
                names.add(NamePrio.TRAFFIC, "${m.type} ${m.i}", p + Offset(9f, 2f), labelStyle.copy(color = Hud.Magenta))
            }

            // traffic from the Tacview feed
            visibleContacts.forEach { c ->
                val p = pr.toScreen(c.x, c.y)
                if (!on(p)) return@forEach
                val col = contactColor(c)
                drawContact(c, p, col, pr)
                if (MapLayers.labels && c.kind != "missile") {
                    val who = listOfNotNull(supportLabel(c), c.group ?: c.name ?: c.kind).joinToString(" ")
                    names.add(NamePrio.TRAFFIC, "$who ${flightLevel(c.altFt)}", p + Offset(10f, 4f), labelStyle.copy(color = col))
                }
            }

            // the airfields, over everything the map has drawn so far — your own jet and the selection still win,
            // because where you are is the one thing that must never be covered
            fieldSymbols.forEach { (p, runways, mark) -> drawAirfield(p, runways, mark) }

            // the labels, the flight plan's first
            names.place(this, tm, placed)

            // ownship
            d.ownPos?.let { (ox, oy) -> drawOwnship(pr.toScreen(ox, oy), d.ownHdg, pr) }

            // selection
            selPos?.let { (sx, sy) ->
                val p = pr.toScreen(sx, sy)
                drawSelection(p)
                d.ownPos?.let { (ox, oy) -> drawDashedLine(Hud.Magenta.copy(alpha = 0.6f), pr.toScreen(ox, oy), p, 2f, floatArrayOf(10f, 8f)) }
            }
        } }

        // Top controls. The full-screen map on a kneeboard has no chip row of its own: MissionMapPane puts the same
        // options behind the ⋯ button, and the corner above is left to the floating sections button.
        val knee = bare || (Kneeboard.on && !compactControls)
        Column(
            Modifier.align(Alignment.TopStart).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!knee) Row(
                // on paper the page behind is cream too, so the strip needs an edge of its own to be a strip at all
                Modifier.clip(RoundedCornerShape(12.dp)).background(if (Hud.onPaper) Hud.Surface else Hud.Bg.copy(alpha = 0.82f))
                    .border(1.dp, if (Hud.onPaper) Hud.Outline else Color.Transparent, RoundedCornerShape(12.dp))
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (compactControls) HudChip(if (layersOpen) "Layers ‹" else "Layers ›", layersOpen) { layersOpen = !layersOpen }
                if (layersOpen) {
                    com.bmscompanion.app.ui.components.MapLookButton()
                    HudChip("Follow", MapLayers.follow) { MapLayers.follow = !MapLayers.follow; MapLayers.save() }
                    HudChip("Route", MapLayers.route) { MapLayers.route = !MapLayers.route; MapLayers.save() }
                    HudChip("SAMs", MapLayers.sams) { MapLayers.sams = !MapLayers.sams; MapLayers.save() }
                    HudChip("Support", MapLayers.support) { MapLayers.support = !MapLayers.support; MapLayers.save() }
                    // the cartridge's lines, offered when there are any
                    if (d.lines.isNotEmpty() || (MapLayers.plan && d.merged.planApplied && d.merged.lines.isNotEmpty())) HudChip("Lines", MapLayers.lines) { MapLayers.lines = !MapLayers.lines; MapLayers.save() }
                    // everything the plan put on the map, at once — offered while a plan is applied
                    if (d.planAvailable) HudChip("Plan", MapLayers.plan) { MapLayers.plan = !MapLayers.plan; MapLayers.save() }
                    // shown once a plan with an attack was sent; turning it on flies the map to it
                    planAttack?.let { a ->
                        HudChip("Attack", MapLayers.attack) {
                            MapLayers.attack = !MapLayers.attack; MapLayers.save()
                            if (MapLayers.attack) a.target?.let { t -> MapLayers.follow = false; state.flyTo(t.north, t.east, ATTACK_ZOOM) }
                        }
                    }
                    HudChip("Friendlies", MapLayers.traffic) { MapLayers.traffic = !MapLayers.traffic; MapLayers.save() }
                    if (hostilesOn) HudChip("Hostiles", MapLayers.hostiles) { MapLayers.hostiles = !MapLayers.hostiles; MapLayers.save() }
                    HudChip("Labels", MapLayers.labels) { MapLayers.labels = !MapLayers.labels; MapLayers.save() }
                    HudChip("All fields", MapLayers.fields) { MapLayers.fields = !MapLayers.fields; MapLayers.save() }
                }
            }
            if (flightStrip) FlightStrip(d.live, d.bull)
        }
        // The picture is missing whenever BMS is not recording, and a VR board has no pointer to dismiss a corner
        // pill with, so the warning sits in the middle of the map on every copy of it and leaves when data arrives.
        AcmiCenterNotice(Modifier.align(Alignment.Center))
        // recenter
        if (!bare) Box(
            Modifier.align(Alignment.BottomEnd).padding(12.dp).size(46.dp).clip(RoundedCornerShape(23.dp)).background(Hud.Surface.copy(alpha = 0.95f))
                .border(1.dp, Hud.Outline, RoundedCornerShape(23.dp)).clickable {
                    val t = d.ownPos ?: (d.route.firstOrNull { it.onRoute } ?: d.route.firstOrNull())?.let { it.x!! to it.y!! }
                    if (t != null) state.flyTo(t.first, t.second, maxOf(state.scale, 4f))
                    if (d.own != null) { MapLayers.follow = true; MapLayers.save() }
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.MyLocation, "Center", tint = if (MapLayers.follow) Hud.Amber else Hud.TextDim) }
    }
}

/** Everything the map's chip row holds, behind one ⋯ button, for the kneeboard. */
@Composable
private fun MapOptionsButton() {
    var open by remember { mutableStateOf(false) }
    Box {
        KneeboardButton(Icons.Default.MoreHoriz, "Map options") { open = !open }
        // the panel is painted here rather than left to Material: a menu whose background comes from the theme and
        // whose text comes from the skin can end up the same colour as the page it floats over
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(Hud.Surface).widthIn(min = 210.dp)) {
            LayerRow("Follow ownship", MapLayers.follow) { MapLayers.follow = it }
            LayerRow("Route and steerpoints", MapLayers.route) { MapLayers.route = it }
            LayerRow("SAMs and threat rings", MapLayers.sams) { MapLayers.sams = it }
            LayerRow("Tanker and AWACS stations", MapLayers.support) { MapLayers.support = it }
            LayerRow("Attack profile (Planner)", MapLayers.attack) { MapLayers.attack = it }
            LayerRow("Lines (DTC)", MapLayers.lines) { MapLayers.lines = it }
            LayerRow("Plan sent from the Planner", MapLayers.plan) { MapLayers.plan = it }
            LayerRow("Friendly and neutral traffic", MapLayers.traffic) { MapLayers.traffic = it }
            if (rememberHostilesOn()) LayerRow("Hostile traffic", MapLayers.hostiles) { MapLayers.hostiles = it }
            else Text(HostileContacts.OFF, color = Hud.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LayerRow("Labels", MapLayers.labels) { MapLayers.labels = it }
            LayerRow("All airfields", MapLayers.fields) { MapLayers.fields = it }
            HorizontalDivider(color = Hud.Outline.copy(alpha = 0.6f))
            MapLookMenuItems()
        }
    }
}

@Composable
private fun LayerRow(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { set(!on); MapLayers.save() }.padding(end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(on, { set(it); MapLayers.save() }, colors = CheckboxDefaults.colors(checkedColor = Hud.Amber))
        Text(label, color = Hud.Text, fontSize = 14.sp)
    }
}

val MapShadow = Shadow(Color.Black, blurRadius = 5f)

/** The same, for a light chart: the label is dark and what stands it off the ground is white. */
val MapGlow = Shadow(Color.White, blurRadius = 5f)

/** A map label with the plan's marks spelt out: "SA-3 · PLAN · NOT IN JET". */
fun planLabel(text: String, fromPlan: Boolean, notInJet: Boolean, cleared: Boolean = false): String = when {
    cleared -> "$text · CLEARED"
    notInJet -> "$text · NOT IN JET"
    fromPlan -> "$text · PLAN"
    else -> text
}

/** Bullseye symbol with range rings every [ringNm] and cardinal spokes. */
fun DrawScope.drawBullseye(c: Offset, pr: MapProjection, rings: Int, ringNm: Int) {
    for (ring in 1..rings) drawCircle(Hud.Cyan.copy(alpha = if (ring % 2 == 0) 0.25f else 0.16f), pr.pxPerNm * ringNm * ring, c, style = Stroke(1.2f))
    drawCircle(Hud.Cyan, 9f, c, style = Stroke(2.5f))
    drawCircle(Hud.Cyan, 3f, c)
    for (a in 0 until 360 step 90) {
        val rad = Math.toRadians(a.toDouble())
        val len = pr.pxPerNm * ringNm * rings
        drawLine(Hud.Cyan.copy(alpha = 0.35f), c, c + Offset((sin(rad) * len).toFloat(), (-cos(rad) * len).toFloat()), 1f)
    }
}

/**
 * The attack, drawn as every map draws it (1.3.8, `drawAttackModel`): the path the jet flies, the dotted leg past the
 * target, the VIP square or the VRP circle (never both, no loose IP), the pull-up point, the mode's offset aimpoints
 * (no dashed lines from them to the target, B15) and the target on top, labelled through the map's own anti-overlap,
 * and under the target the caption — "Pop-up · VRP · TGT STPT 7", with "not in the cartridge — Save to DTC" in amber
 * when the cartridge as saved does not hold it. One drawing for the Mission map, a VR board's map and the Planner's Map
 * page. [captioned] false leaves the caption off.
 */
fun DrawScope.drawAttack(
    attack: com.bmscompanion.app.data.mission.AttackOverlay,
    pr: MapProjection,
    tm: androidx.compose.ui.text.TextMeasurer,
    labelStyle: TextStyle,
    placed: MutableList<androidx.compose.ui.geometry.Rect>,
    captioned: Boolean = true,
    visible: (Offset) -> Boolean,
) {
    val light = Hud.onLightMap || Hud.paperInks
    drawAttackModel(
        attack, { n, e -> pr.toScreen(n, e) }, AttackInks.of(light), notes = MapLayers.labels, visible = visible,
    ) { text, at, ink -> placeText(tm, text, at, labelStyle.copy(color = ink), placed) }
    if (!captioned) return
    val t = attack.target ?: return
    val p = pr.toScreen(t.north, t.east)
    if (!visible(p)) return
    val (cap, warn) = com.bmscompanion.app.data.mission.AttackDrawing.captionWithSaved(attack)
    if (cap.isNotEmpty()) placeText(tm, cap, p + Offset(-12f, 14f), labelStyle.copy(color = if (light) Color.Black else Color.White), placed)
    if (warn != null) placeText(tm, warn, p + Offset(-12f, 30f), labelStyle.copy(color = Hud.Amber), placed)
}

/** Ownship jet symbol with a heading line. */
fun DrawScope.drawOwnship(p: Offset, hdg: Double, pr: MapProjection) {
    val len = (pr.pxPerNm * 5).coerceIn(30f, 160f)
    val rad = Math.toRadians(hdg)
    drawLine(Hud.Amber.copy(alpha = 0.8f), p, p + Offset((sin(rad) * len).toFloat(), (-cos(rad) * len).toFloat()), 2.5f)
    rotate(hdg.toFloat(), p) {
        val jet = Path().apply {
            moveTo(p.x, p.y - 16f); lineTo(p.x + 3f, p.y - 4f); lineTo(p.x + 13f, p.y + 4f); lineTo(p.x + 13f, p.y + 7f); lineTo(p.x + 3f, p.y + 5f)
            lineTo(p.x + 3f, p.y + 11f); lineTo(p.x + 7f, p.y + 15f); lineTo(p.x - 7f, p.y + 15f); lineTo(p.x - 3f, p.y + 11f); lineTo(p.x - 3f, p.y + 5f)
            lineTo(p.x - 13f, p.y + 7f); lineTo(p.x - 13f, p.y + 4f); lineTo(p.x - 3f, p.y - 4f); close()
        }
        drawPath(jet, Color.Black, style = Stroke(5f))
        drawPath(jet, Hud.Amber)
    }
}

fun DrawScope.drawContact(c: Contact, p: Offset, col: Color, pr: MapProjection) {
    // one-minute speed vector
    if (c.gsKts > 30 && c.kind != "ship") {
        val len = (c.gsKts / 60.0 * pr.pxPerNm).toFloat().coerceIn(10f, 120f)
        val rad = Math.toRadians(c.hdg)
        drawLine(col.copy(alpha = 0.85f), p, p + Offset((sin(rad) * len).toFloat(), (-cos(rad) * len).toFloat()), 2f)
    }
    supportRole(c.name)?.takeIf { c.kind == "air" }?.let { role -> drawSupportSymbol(role, p, col, c.hdg); return }
    when (c.kind) {
        "missile" -> {
            drawCircle(Color.Black, 5f, p); drawCircle(col, 3.5f, p)
        }
        "ship" -> {
            drawRect(MapHalo, p - Offset(8f, 8f), androidx.compose.ui.geometry.Size(16f, 16f))
            drawRect(col, p - Offset(6f, 6f), androidx.compose.ui.geometry.Size(12f, 12f), style = Stroke(2.5f))
        }
        else -> if (c.friendly) {
            drawCircle(MapHalo, 9f, p)
            drawCircle(col, 7f, p, style = Stroke(3f))
            if (c.kind == "heli") drawCircle(col, 2.5f, p)
        } else if (c.neutral) {
            // neither side: a square, the way a picture marks a neutral, so it never passes for a threat
            drawRect(MapHalo, p - Offset(8f, 8f), androidx.compose.ui.geometry.Size(16f, 16f))
            drawRect(col, p - Offset(6.5f, 6.5f), androidx.compose.ui.geometry.Size(13f, 13f), style = Stroke(2.5f))
        } else {
            val path = Path().apply { moveTo(p.x, p.y - 9f); lineTo(p.x + 9f, p.y); lineTo(p.x, p.y + 9f); lineTo(p.x - 9f, p.y); close() }
            drawPath(path, MapHalo, style = Stroke(6f))
            drawPath(path, col, style = Stroke(3f))
        }
    }
}

/** Label with simple declutter: skipped if it would overlap one already drawn. */
fun DrawScope.placeText(tm: androidx.compose.ui.text.TextMeasurer, text: String, topLeft: Offset, style: TextStyle, placed: MutableList<androidx.compose.ui.geometry.Rect>) {
    if (topLeft.x > size.width || topLeft.y > size.height || text.isBlank()) return
    val layout = com.bmscompanion.app.ui.components.MapText.label(this, tm, text, style).layout
    val r = androidx.compose.ui.geometry.Rect(topLeft.x, topLeft.y, topLeft.x + layout.size.width, topLeft.y + layout.size.height)
    if (r.right < 0 || r.bottom < 0 || placed.any { it.overlaps(r) }) return
    placed += r
    safeText(tm, text, topLeft, style)
}

@Composable
private fun FlightStrip(live: com.bmscompanion.app.data.mission.Live?, bull: Pair<Double, Double>?) {
    val l = live?.takeIf { it.flying } ?: return
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Hud.Bg.copy(alpha = 0.85f)).border(1.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StripItem("ALT", "%,d".format(l.altFt.toInt()))
        StripItem("KIAS", "${l.kias.toInt()}")
        StripItem("HDG", "%03d".format(l.hdgMag.toInt()))
        StripItem("FUEL", "%,d".format(l.fuelTotal.toInt()), if (l.bingo > 0 && l.fuelTotal < l.bingo) Hud.Red else Hud.Text)
        bull?.let { StripItem("BULLS", bra(it.first, it.second, l.x, l.y), Hud.Cyan) }
    }
}

@Composable
private fun StripItem(label: String, value: String, color: Color = Hud.Text) {
    Column {
        Text(label, fontSize = 9.sp, color = Hud.TextDim, fontWeight = FontWeight.SemiBold)
        Text(value, style = LocalExtra.current.monoSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = color)
    }
}

@Composable
fun SelectionCard(env: MissionEnv, sel: MapSel, d: MapData, modifier: Modifier, onClose: () -> Unit) {
    val stpts = d.stpts
    val ppts = d.ppts
    val ctcs = d.ctcs
    val own = d.ownPos
    val bull = d.bull
    var title = ""
    var subtitle: String? = null
    val rows = ArrayList<Pair<String, String>>()
    var accent = Hud.Magenta
    var field: Airport? = null
    var pos: Pair<Double, Double>? = null
    // a threat names a system the app's Threat Guide describes: the card offers that entry
    var guide: com.bmscompanion.app.data.Threat? = null
    val reference by androidx.compose.runtime.produceState(emptyList<com.bmscompanion.app.data.Threat>()) { value = Repo.threats() }
    when (sel) {
        is MapSel.Ctc -> {
            val c = ctcs.firstOrNull { it.id == sel.id } ?: return
            // an air defence is not a contact to intercept: what is wanted is the system, its reach and where it is
            if (c.kind == "sam") {
                val site = d.sams.firstOrNull { it.id == c.id }
                accent = if (c.friendly) Friendly else if (c.neutral) Neutral else Hostile
                title = site?.label ?: c.name ?: "Air defence"
                subtitle = listOfNotNull(c.name?.takeIf { it != title }, c.coalition).joinToString(" · ").ifBlank { null }
                rows += "RING" to (site?.rangeNm?.let { "${it.toInt()} nm engagement" } ?: "range not known")
                own?.let { rows += "BRAA" to bra(it.first, it.second, c.x, c.y) }
                // (BULLS is added below for every card with a position; adding it here too listed it twice)
                pos = c.x to c.y
                guide = site?.threatId?.let { id -> reference.firstOrNull { it.id == id } } ?: threatGuideEntry(c.name, reference)
            } else {
            accent = contactColor(c)
            title = c.group ?: c.name ?: "Contact"
            subtitle = listOfNotNull(c.name?.takeIf { it != title }, c.pilot, c.coalition).joinToString(" · ")
            rows += "ALT" to "%,d ft".format(c.altFt.toInt())
            rows += "HDG" to "%03d°T".format(c.hdg.toInt())
            if (c.gsKts > 0) rows += "GS" to "${c.gsKts.toInt()} kt"
            own?.let { rows += "BRAA" to "${bra(it.first, it.second, c.x, c.y)} · ${flightLevel(c.altFt)} · ${aspect(c.hdg, c.x, c.y, it.first, it.second)}" }
            pos = c.x to c.y
            }
        }
        is MapSel.Stp -> {
            val s = stpts.firstOrNull { it.n == sel.n } ?: return
            accent = if (s.notInJet || s.fromPlan) PlanInk else if (s.isTarget) Hostile else Hud.Amber
            title = "STPT ${s.n} · ${s.title}"
            subtitle = listOfNotNull(s.action, s.comments).joinToString(" · ").ifBlank { null }
            s.time?.let { rows += "TOS" to it }
            s.altText?.let { rows += "ALT" to it }
            s.cas?.let { rows += "CAS" to "$it kt" }
            s.targetName?.let { rows += "TGT" to it }
            // where the point came from: tapping a plan item says so
            rows += "FROM" to sourceText(s.source, s.notInJet)
            if (s.notInJet) rows += "!" to "Not in the jet yet: Save to DTC in the Planner, then LOAD in BMS's DTC window"
            if (s.cleared) rows += "!" to "Cleared in the Planner — still in the jet until saved"
            if (!s.onRoute && s.isTarget) rows += "NOTE" to "A precision target: marked, not part of the route"
            if (s.hasPos) pos = s.x!! to s.y!!
            // takeoff / landing steerpoints sit on an airfield: offer its charts too
            if (s.hasPos) field = env.set?.airports?.minByOrNull { rangeNm(it.x, it.y, s.x!!, s.y!!) }?.takeIf { rangeNm(it.x, it.y, s.x!!, s.y!!) < 3 }
        }
        is MapSel.Ppt -> {
            val t = ppts.getOrNull(sel.index) ?: return
            accent = if (t.notInJet) PlanInk else if (t.marker) Friendly else SamRed
            title = (if (t.marker) "PPT ${t.n} · " else "Threat · ") + t.name
            if (!t.marker) rows += "RANGE" to "%.0f nm".format(t.rangeNm)
            t.code?.let { rows += "CODE" to it }
            rows += "FROM" to sourceText(t.source, t.notInJet)
            if (t.notInJet) rows += "!" to "Not in the jet yet: Save to DTC in the Planner, then LOAD in BMS's DTC window"
            if (t.cleared) rows += "!" to "Cleared in the Planner — still in the jet until saved"
            own?.let { if (!t.marker && rangeNm(it.first, it.second, t.x, t.y) < t.rangeNm) rows += "!" to "You are inside this ring" }
            pos = t.x to t.y
            if (!t.marker) guide = threatGuideEntry(t.name, reference)
        }
        is MapSel.Field -> {
            val a = env.set?.airports?.firstOrNull { it.id == sel.id } ?: return
            field = a
            accent = Hud.Green
            title = a.name
            subtitle = listOfNotNull(a.icao, airportKindShort(a)).joinToString(" · ")
            a.tacan?.let { rows += "TACAN" to it.label }
            a.freqs?.towerUhf?.let { rows += "TOWER" to (it + (a.freqs.towerVhf?.let { v -> " / $v" } ?: "")) }
            a.runways.flatMap { r -> r.ends.filter { it.ils != null } }.takeIf { it.isNotEmpty() }?.let { ils -> rows += "ILS" to ils.joinToString("  ") { "${it.designator} ${it.ils}" } }
            if (a.runways.isNotEmpty()) rows += "RWY" to a.runways.joinToString("  ") { it.name }
            pos = a.x to a.y
        }
        is MapSel.Pt -> {
            title = "Map point"
            pos = sel.x to sel.y
            // a point on an airfield (a weapon target on a runway, a tap near a field) offers its charts
            field = env.set?.airports?.minByOrNull { rangeNm(it.x, it.y, sel.x, sel.y) }?.takeIf { rangeNm(it.x, it.y, sel.x, sel.y) < 3 }
        }
    }
    pos?.let { (px, py) ->
        bull?.let { rows += "BULLS" to bra(it.first, it.second, px, py) }
        if (sel !is MapSel.Ctc) own?.let { rows += "FROM YOU" to bra(it.first, it.second, px, py) }
    }

    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Hud.Surface.copy(alpha = 0.97f)).border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.Close, "Close", tint = Hud.TextDim, modifier = Modifier.size(22.dp).clickable(onClick = onClose))
        }
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.BoxWithConstraints { val boxW = maxWidth; Column {
        // two columns when the card is wide (tablet side panel), one on phones
        val perRow = if (boxW >= 460.dp) 2 else 1
        rows.chunked(perRow).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { (k, v) ->
                    Row(Modifier.weight(1f).padding(vertical = 2.dp)) {
                        Text(k, fontSize = 11.sp, color = if (k == "!") Hostile else Hud.TextDim, modifier = Modifier.width(72.dp))
                        Text(v, style = if (k == "FROM" || k == "NOTE" || k == "!") MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp) else LocalExtra.current.monoSmall.copy(fontSize = 13.sp), color = if (k == "!") Hostile else Hud.Text, maxLines = 2)
                    }
                }
                if (pair.size < perRow) Spacer(Modifier.weight(1f))
            }
        }
        } }
        field?.let { a ->
            Spacer(Modifier.height(8.dp))
            Text(
                if (sel is MapSel.Field) "Airfield details & charts ›" else "${a.name}: details & charts ›",
                Modifier.clip(RoundedCornerShape(8.dp)).background(Hud.Green.copy(alpha = 0.15f)).clickable { env.theater?.let { th -> env.nav.go(missionAirportRoute(th.id, a.id)) } }.padding(horizontal = 10.dp, vertical = 6.dp),
                color = Hud.Green, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        guide?.let { t ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Threat guide: ${t.name} ›",
                Modifier.clip(RoundedCornerShape(8.dp)).background(Hostile.copy(alpha = 0.15f)).clickable { env.nav.go("m/threat/${android.net.Uri.encode(t.id)}") }.padding(horizontal = 10.dp, vertical = 6.dp),
                color = Hostile, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

fun missionAirportRoute(theater: String, id: Int) = "m/airport/${android.net.Uri.encode(theater)}/$id"

private fun airportKindShort(a: Airport) = com.bmscompanion.app.ui.screens.airportKind(a)

/** AWACS-style picture: nearest hostile air contacts first, ejected crews last, with bullseye position and BRAA. */
@Composable
fun PictureCard(ctcs: List<Contact>, own: Pair<Double, Double>?, ownHdg: Double, bull: Pair<Double, Double>?, onPick: (Contact) -> Unit) {
    val hostiles = ctcs.filter { it.hostile && (it.kind in setOf("air", "heli", "missile") || it.isCrew()) }
    val ref = own ?: bull
    val sorted = hostiles.sortedWith(compareBy({ it.isCrew() }, { c -> ref?.let { rangeNm(it.first, it.second, c.x, c.y) } ?: 0.0 }))
    val threats = hostiles.count { !it.isCrew() }
    // hostile contacts come from the live feed only while the PC's setting is on (HostileContacts, off by default)
    if (!rememberHostilesOn()) {
        com.bmscompanion.app.ui.components.SectionCard("Picture", accent = Hostile) {
            Text(HostileContacts.OFF + ": the enemy is what the briefing and the cockpit show.", style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
        }
        return
    }
    com.bmscompanion.app.ui.components.SectionCard("Picture", accent = Hostile, trailing = {
        Text("$threats hostile", fontSize = 11.sp, color = Hud.TextDim)
        // same setting as the map's Hostiles chip
        Box(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).clickable { MapLayers.hostiles = !MapLayers.hostiles; MapLayers.save() }.padding(4.dp)) {
            Tag("HOSTILES", if (MapLayers.hostiles) Hostile else Hud.TextDim, filled = MapLayers.hostiles)
        }
    }) {
        if (ctcs.isEmpty()) {
            Text("No AWACS feed. Enable the Tacview real-time stream in BMS (see Setup).", style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
            return@SectionCard
        }
        if (!MapLayers.hostiles) { Text("Hostiles hidden. Tap HOSTILES to show them.", style = MaterialTheme.typography.bodySmall, color = Hud.TextDim); return@SectionCard }
        if (sorted.isEmpty()) { Text("Picture clean", color = Hud.Green, style = LocalExtra.current.mono); return@SectionCard }
        sorted.take(10).forEach { c ->
            val crew = c.isCrew()
            Row(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(if (crew) "CREW" else if (c.kind == "missile") "MSL" else "HOST", if (crew) Hud.TextDim else Hostile, filled = !crew)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(listOfNotNull(c.group, c.name).distinct().joinToString(" · "), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (crew) Hud.TextDim else Hud.Text)
                    Text(
                        listOfNotNull(
                            bull?.let { "BULLS ${bra(it.first, it.second, c.x, c.y)}" },
                            own?.let { "BRAA ${bra(it.first, it.second, c.x, c.y)} ${aspect(c.hdg, c.x, c.y, it.first, it.second)}" },
                        ).joinToString("  "),
                        style = LocalExtra.current.monoSmall, color = Hud.TextDim,
                    )
                }
                Text(flightLevel(c.altFt), style = LocalExtra.current.mono, color = if (crew) Hud.TextDim else Hostile)
            }
        }
    }
}

@Composable
fun SteerpointList(stpts: List<Stpt>, own: Pair<Double, Double>?, bull: Pair<Double, Double>?, selected: Int?, onPick: (Stpt) -> Unit) {
    if (stpts.isEmpty()) return
    // the card's title is tagged like THREATS / PRESETS / COMMS when any row is the plan's or not in the jet yet
    val fromPlan = stpts.any { it.fromPlan }
    val notInJet = stpts.any { it.notInJet }
    com.bmscompanion.app.ui.components.SectionCard(
        "Steerpoints", accent = Hud.Amber,
        trailing = if (fromPlan || notInJet) ({ PlanTags(fromPlan || notInJet, notInJet, compact = true) }) else null,
    ) {
        stpts.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected == s.n) Hud.Amber.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable(enabled = s.hasPos) { onPick(s) }.padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("%2d".format(s.n), style = LocalExtra.current.mono, color = if (s.isTarget) Hostile else Hud.Amber, modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (s.isTarget) Hostile else Hud.Text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlanTags(s.fromPlan, s.notInJet, Modifier.padding(end = 6.dp), compact = true)
                        if (s.cleared) ClearedTag(Modifier.padding(end = 6.dp))
                        Text(listOfNotNull(s.time, s.altText, s.action).joinToString(" · "), fontSize = 11.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (s.hasPos) {
                    val ref = own ?: bull
                    ref?.let { Text(bra(it.first, it.second, s.x!!, s.y!!), style = LocalExtra.current.monoSmall, color = if (own != null) Hud.Text else Hud.Cyan) }
                }
            }
        }
        Text(if (own != null) "Bearing/range from you" else "Bearing/range from bullseye", fontSize = 10.sp, color = Hud.TextFaint)
    }
}
