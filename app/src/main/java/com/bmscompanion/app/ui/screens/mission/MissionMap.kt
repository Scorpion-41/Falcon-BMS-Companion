package com.bmscompanion.app.ui.screens.mission

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

/** Map layer toggles, remembered across launches. */
/** A ring that is a warning, not a boundary: dashed, so it never reads as a drawn border on the chart. */
/** How wide a planned track is drawn: the width BMS uses for a tanker's own track. */
private const val TRACK_WIDTH_NM = 12.0

/** How wide a station is drawn: BMS names a point, and an orbit is a few miles across whatever the point. */
private const val STATION_NM = 12.0

private val SamRing = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(9f, 7f), 0f)

object MapLayers {
    var follow by mutableStateOf(Repo.getInt("m_follow", 1) == 1)
    var route by mutableStateOf(Repo.getInt("m_route", 1) == 1)
    /**
     * Air defences, under one switch: the sites the briefing named with the reach of each system, and the rings of
     * the pre-planned threats entered in the DTC. Two switches asked a pilot to know which list a given missile
     * battery came from before he could decide whether to see it, which is not a question anybody has.
     */
    var sams by mutableStateOf(Repo.getInt("m_sams", 1) == 1 && Repo.getInt("m_threats", 1) == 1)
    var traffic by mutableStateOf(Repo.getInt("m_traffic", 1) == 1)
    var hostiles by mutableStateOf(Repo.getInt("m_hostiles", 1) == 1)
    var labels by mutableStateOf(Repo.getInt("m_labels", 1) == 1)
    var fields by mutableStateOf(Repo.getInt("m_fields", 1) == 1)
    /** The tanker and AWACS tracks the campaign planned, and the stations the briefing describes in words. */
    var support by mutableStateOf(Repo.getInt("m_support", 1) == 1)
    fun save() {
        Repo.putInt("m_follow", if (follow) 1 else 0); Repo.putInt("m_route", if (route) 1 else 0)
        Repo.putInt("m_traffic", if (traffic) 1 else 0); Repo.putInt("m_hostiles", if (hostiles) 1 else 0); Repo.putInt("m_labels", if (labels) 1 else 0)
        Repo.putInt("m_fields", if (fields) 1 else 0)
        // one key for both, and the old one kept in step so an older version reads it the same way
        Repo.putInt("m_sams", if (sams) 1 else 0); Repo.putInt("m_threats", if (sams) 1 else 0)
        Repo.putInt("m_support", if (support) 1 else 0)
    }
}

// Symbol colours. On a board they come from the skin instead, so a printed page is drawn in printed inks
// rather than in the HUD's cyan and red.
val Friendly: Color get() = if (Hud.onPaper) Hud.Blue else Color(0xFF56C8F5)
val Hostile: Color get() = if (Hud.onPaper) Hud.Red else Color(0xFFFF5A5F)
val Neutral: Color get() = if (Hud.onPaper) Hud.Amber else Color(0xFFFFD27A)

fun contactColor(c: Contact) = when {
    c.own -> Hud.Amber
    supportColor(c) != null -> supportColor(c)!!
    c.friendly -> Friendly
    c.coalition.isNullOrBlank() -> Neutral
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
)

/** Which of the two roles a briefing station is, in the words the campaign uses. */
private fun role(st: SupportStation) = if (st.role.contains("tanker", true)) "Tanker" else "AWACS"

/** The stations named in the briefing's support section, placed against this theater's airfields and towns. */
private fun supportStations(
    mission: com.bmscompanion.app.data.mission.MissionData?,
    set: com.bmscompanion.app.data.AirportSet?,
    geo: com.bmscompanion.app.data.GeoLayers?,
): List<SupportStation> {
    val entries = mission?.briefing?.support.orEmpty()
    if (entries.isEmpty()) return emptyList()
    fun one(name: String): Pair<Double, Double>? {
        val n = name.trim().lowercase()
        if (n.length < 3) return null
        set?.airports?.firstOrNull { it.name.lowercase().startsWith(n) || it.icao?.lowercase() == n }?.let { return it.x to it.y }
        geo?.places?.firstOrNull { it.n.lowercase() == n }?.let { return it.x to it.y }
        return geo?.places?.firstOrNull { it.n.lowercase().startsWith(n) }?.let { it.x to it.y }
    }
    // "Nea Anchialos" is a place; so is the "Larissa" of "Larissa Airbase". Try what the briefing said, then its
    // first word, because a briefing writes a name the way a pilot says it and a map writes it the way it is.
    fun place(name: String): Pair<Double, Double>? =
        one(name) ?: name.trim().substringBefore(' ').takeIf { it.length >= 3 }?.let { one(it) }
    return entries.mapNotNull { e ->
        val role = e.role ?: return@mapNotNull null
        if (!role.contains("tanker", true) && !role.contains("awacs", true) && !role.contains("jstars", true)) return@mapNotNull null
        val (at, text) = stationFromNotes(e.notes, ::place) ?: return@mapNotNull null
        SupportStation(e.callsign, role, at.first, at.second, text)
    }
}

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
    return remember(live, contacts, mission, env.set, reference, geo) {
        val stpts = steerpoints(mission, live)
        val own = ownship(live)
        val ctcs = contacts?.contacts.orEmpty()
        val ownContact = ctcs.firstOrNull { it.own }
        val bases = airbases(live, mission?.briefing)
        MapData(
            stpts = stpts,
            route = stpts.filter { it.hasPos },
            ppts = preplannedThreats(mission, live),
            marks = markpoints(live),
            live = live,
            own = own,
            ownPos = own ?: ownContact?.let { it.x to it.y },
            ownHdg = if (own != null) live!!.hdgTrue else ownContact?.hdg ?: 0.0,
            ctcs = ctcs,
            sams = samSites(ctcs, reference).let { all ->
                // what the briefing did not name is not the pilot's to see
                val briefed = briefedSystems(mission, reference)
                all.filter { it.threatId != null && it.threatId in briefed }
            },
            stations = supportStations(mission, env.set, geo),
            tracks = mission?.tracks.orEmpty(),
            bull = bullseye(live, ctcs),
            depField = matchAirport(env.set, bases.departure),
            arrField = matchAirport(env.set, bases.arrival),
            altField = matchAirport(env.set, bases.alternate),
            contactsConnected = contacts?.connected,
        )
    }
}

@Composable
fun MissionMapPane(env: MissionEnv, state: MapState, sel: MapSel?, onSel: (MapSel?) -> Unit, onOpenTab: (MissionTab) -> Unit) {
    if (env.theater == null) {
        PaneEmpty("No theater yet", "Connect to the BMS PC to load the mission theater.", "Setup") { onOpenTab(MissionTab.SETUP) }
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
                PictureCard(d.ctcs, d.ownPos, d.ownHdg, d.bull, onPick = { onSel(MapSel.Ctc(it.id)) })
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
) {
    val th = env.theater ?: return
    val link by MissionLink.state.collectAsState()
    // measuring labels is the most expensive part of a redraw: cache layouts across the 4 Hz updates
    // the same table the Tankers & AWACS page builds: it carries the channel and which one is yours
    val support = rememberSupportAssets(env)
    val tm = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    val hitPx = with(density) { 30.dp.toPx() }
    var layersOpen by remember { mutableStateOf(!compactControls) }
    val visibleContacts = d.ctcs.filter { c ->
        c.kind != "bullseye" && c.kind != "sam" && !c.own && MapLayers.traffic && (c.friendly || MapLayers.hostiles)
    }

    // First view: the route (or ownship) instead of the whole theater.
    var framed by remember(th.id) { mutableStateOf(state.initialized) }
    LaunchedEffect(th.id, d.route.isNotEmpty(), d.ownPos != null) {
        if (framed) return@LaunchedEffect
        val target = d.ownPos ?: d.route.firstOrNull()?.let { it.x!! to it.y!! } ?: return@LaunchedEffect
        state.flyTo(target.first, target.second, 3.5f)
        framed = true
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

    val labelStyle = remember { TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, shadow = MapShadow) }
    val stptStyle = remember { TextStyle(color = Hud.Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, shadow = MapShadow) }
    val fieldStyle = remember { TextStyle(color = Hud.Green, fontSize = 11.sp, fontWeight = FontWeight.Bold, shadow = MapShadow) }

    Box(modifier) {
        TheaterMap(
            th.map, th.sizeFt, Modifier.fillMaxSize(), state, maxScale = 24f, fillWidth = false, zoomButtons = !bare,
            onUserGesture = { if (MapLayers.follow) { MapLayers.follow = false; MapLayers.save() } },
            onTap = { x, y, pr ->
                val tap = pr.toScreen(x, y)
                fun dist(px: Double, py: Double) = pr.toScreen(px, py).let { hypot((it.x - tap.x).toDouble(), (it.y - tap.y).toDouble()) }
                val cands = buildList<Pair<MapSel, Double>> {
                    visibleContacts.forEach { add(MapSel.Ctc(it.id) to dist(it.x, it.y)) }
                    if (MapLayers.route) d.route.forEach { add(MapSel.Stp(it.n) to dist(it.x!!, it.y!!) + 4) }
                    if (MapLayers.sams) d.ppts.forEachIndexed { i, p -> add(MapSel.Ppt(i) to dist(p.x, p.y) + 6) }
                    if (MapLayers.sams) d.sams.forEach { add(MapSel.Ctc(it.id) to dist(it.x, it.y) + 6) }
                    if (MapLayers.fields) env.set?.airports?.forEach { add(MapSel.Field(it.id) to dist(it.x, it.y) + 8) }
                }
                val best = cands.minByOrNull { it.second }
                onSel(if (best != null && best.second < hitPx * 1.6) best.first else MapSel.Pt(x, y))
            },
        ) { pr ->
            val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
            fun on(p: Offset) = p.x > -60 && p.y > -60 && p.x < size.width + 60 && p.y < size.height + 60

            // airfields: mission bases highlighted, others as small dots
            if (MapLayers.fields) env.set?.airports?.forEach { a ->
                val p = pr.toScreen(a.x, a.y)
                if (!on(p)) return@forEach
                val role = when (a.id) { d.depField?.id -> "DEP"; d.arrField?.id -> "ARR"; d.altField?.id -> "ALT"; else -> null }
                if (role != null) {
                    drawCircle(Color.Black.copy(alpha = 0.6f), 11f, p)
                    drawCircle(Hud.Green, 8f, p)
                    val tag = if (d.depField?.id == d.arrField?.id && role != "ALT") "HOME" else role
                    placeText(tm, "$tag ${a.icao ?: a.name}", p + Offset(12f, -8f), fieldStyle, placed)
                } else {
                    drawCircle(Color.Black.copy(alpha = 0.5f), 5.5f, p)
                    drawCircle(Hud.Green.copy(alpha = 0.55f), 3.5f, p)
                }
            }

            // pre-planned threat rings
            if (MapLayers.sams) d.ppts.forEach { t ->
                val c = pr.toScreen(t.x, t.y)
                val r = (t.rangeNm * pr.pxPerNm).toFloat()
                if (c.x + r < 0 || c.y + r < 0 || c.x - r > size.width || c.y - r > size.height) return@forEach
                drawCircle(Hostile.copy(alpha = 0.08f), r, c)
                drawCircle(Hostile.copy(alpha = 0.75f), r, c, style = Stroke(2.5f))
                drawCircle(Hostile, 5f, c)
                placeText(tm, t.name, c + Offset(8f, 2f), labelStyle.copy(color = Hostile), placed)
            }

            // The air defences BMS is streaming, each with the reach of its own system. Drawn under everything
            // that moves: a ring is a place you do not want to be, not a thing to look at.
            if (MapLayers.sams) d.sams.forEach { site ->
                val c = pr.toScreen(site.x, site.y)
                val ink = if (site.friendly) Friendly else Hostile
                val r = ((site.rangeNm ?: 0.0) * pr.pxPerNm).toFloat()
                if (r > 2f) {
                    if (c.x + r < 0 || c.y + r < 0 || c.x - r > size.width || c.y - r > size.height) return@forEach
                    drawCircle(ink.copy(alpha = 0.06f), r, c)
                    drawCircle(ink.copy(alpha = 0.5f), r, c, style = Stroke(2f, pathEffect = SamRing))
                } else if (!on(c)) return@forEach
                // a launcher is a square: the shape every briefing draws a SAM with
                val h = 4.5f
                drawRect(Color.Black.copy(alpha = 0.55f), Offset(c.x - h - 1.5f, c.y - h - 1.5f), Size(2 * h + 3f, 2 * h + 3f))
                drawRect(ink, Offset(c.x - h, c.y - h), Size(2 * h, 2 * h), style = Stroke(2f))
                if (MapLayers.labels) placeText(tm, site.label, c + Offset(9f, 2f), labelStyle.copy(color = ink), placed)
            }

            // What the campaign planned for the tankers and the AWACS, when the mission file gave it to us: the
            // transit drawn thin, and the leg they hold on drawn as the corridor a pilot goes looking for.
            if (MapLayers.support) d.tracks.forEach { t ->
                val ink = if (t.role.contains("tanker", true)) TankerColor else AwacsColor
                // the one your flight was given is the one you will actually fly to: drawn as such, with the
                // channel you tune written where you are looking
                val mine = t.yours
                val strong = if (mine) 1f else 0.6f
                val route = t.points.map { pr.toScreen(it.x, it.y) }
                if (route.size >= 2) {
                    for (i in 1 until route.size) {
                        val a = route[i - 1]
                        val b = route[i]
                        if (!on(a) && !on(b)) continue
                        drawLine(ink.copy(alpha = 0.35f * strong), a, b, strokeWidth = 1.5f, pathEffect = SamRing)
                    }
                }
                val legs = t.points.withIndex().filter { it.value.station }.map { route[it.index] }
                if (legs.size >= 2) {
                    val from = legs.first()
                    val to = legs.last()
                    val dx = to.x - from.x
                    val dy = to.y - from.y
                    val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (len > 1f && (on(from) || on(to))) {
                        // the corridor is as wide as a track is flown: twelve miles, the width BMS draws its own
                        val half = (TRACK_WIDTH_NM * pr.pxPerNm / 2).toFloat()
                        val nx = -dy / len * half
                        val ny = dx / len * half
                        val box = androidx.compose.ui.graphics.Path().apply {
                            moveTo(from.x + nx, from.y + ny)
                            lineTo(to.x + nx, to.y + ny)
                            lineTo(to.x - nx, to.y - ny)
                            lineTo(from.x - nx, from.y - ny)
                            close()
                        }
                        drawPath(box, ink.copy(alpha = if (mine) 0.16f else 0.07f))
                        drawPath(box, ink.copy(alpha = if (mine) 0.9f else 0.45f), style = Stroke(if (mine) 3f else 2f))
                        if (MapLayers.labels) {
                            val mid = Offset((from.x + to.x) / 2, (from.y + to.y) / 2)
                            // what to call it, and what to tune: the channel comes from the support table, which
                            // knows the briefing's TACAN and BMS's own allocation
                            val asset = support.firstOrNull { a -> a.callsign.equals(t.callsign, true) }
                            val label = listOfNotNull(
                                t.callsign ?: t.role.uppercase(),
                                asset?.tacan?.let { "TCN $it" },
                                if (mine) "YOURS" else null,
                            ).joinToString(" · ")
                            placeText(tm, label, mid + Offset(6f, -6f), labelStyle.copy(color = ink), placed)
                        }
                    }
                }
            }

            // Where the briefing says the tanker and the AWACS will be — on the map from the moment it is printed,
            // and drawn as an area rather than a point, because a station is an orbit and not a parking space.
            // A role the campaign gave us a real track for needs none of this.
            if (MapLayers.support) d.stations.forEach { st ->
                if (d.tracks.any { it.role.equals(role(st), true) }) return@forEach
                val c = pr.toScreen(st.x, st.y)
                val ink = if (st.role.contains("tanker", true)) TankerColor else AwacsColor
                val r = (STATION_NM * pr.pxPerNm).toFloat()
                if (c.x + r < 0 || c.y + r < 0 || c.x - r > size.width || c.y - r > size.height) return@forEach
                drawCircle(ink.copy(alpha = 0.06f), r, c)
                drawCircle(ink.copy(alpha = 0.55f), r, c, style = Stroke(2f, pathEffect = SamRing))
                drawCircle(Color.Black.copy(alpha = 0.5f), 6f, c)
                drawCircle(ink, 4f, c)
                if (MapLayers.labels) {
                    val what = st.role.uppercase()
                    placeText(tm, "$what ${st.callsign} station", c + Offset(9f, -8f), labelStyle.copy(color = ink), placed)
                }
            }

            // bullseye
            d.bull?.let { (bx, by) -> drawBullseye(pr.toScreen(bx, by), pr, rings = 5, ringNm = 20) }

            // flight plan
            if (MapLayers.route && d.route.size > 1) {
                val main = d.route.filterNot { it.isAlternate }
                for (i in 0 until main.size - 1) {
                    val a = pr.toScreen(main[i].x!!, main[i].y!!)
                    val b = pr.toScreen(main[i + 1].x!!, main[i + 1].y!!)
                    drawLine(Color.Black.copy(alpha = 0.5f), a, b, 6f)
                    drawLine(Hud.Amber, a, b, 3f)
                }
                d.route.filter { it.isAlternate }.forEach { alt ->
                    main.lastOrNull()?.let { last ->
                        drawLine(Hud.Amber.copy(alpha = 0.7f), pr.toScreen(last.x!!, last.y!!), pr.toScreen(alt.x!!, alt.y!!), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                    }
                }
            }
            if (MapLayers.route) d.route.forEach { s ->
                val p = pr.toScreen(s.x!!, s.y!!)
                if (!on(p)) return@forEach
                if (s.isTarget) {
                    val path = Path().apply { moveTo(p.x, p.y - 11f); lineTo(p.x + 11f, p.y); lineTo(p.x, p.y + 11f); lineTo(p.x - 11f, p.y); close() }
                    drawPath(path, Color.Black.copy(alpha = 0.6f), style = Stroke(6f))
                    drawPath(path, Hostile, style = Stroke(3f))
                } else {
                    drawCircle(Color.Black.copy(alpha = 0.6f), 9f, p)
                    drawCircle(Hud.Amber, 7f, p, style = Stroke(3f))
                }
                val text = if (MapLayers.labels && pr.scale >= 2.5f) "${s.n} ${s.desc ?: ""}".trim() else "${s.n}"
                placeText(tm, text, p + Offset(11f, -18f), stptStyle, placed)
            }
            // markpoints and datalink points
            if (MapLayers.route) d.marks.forEach { m ->
                val p = pr.toScreen(m.x, m.y)
                drawRect(Hud.Magenta, p - Offset(6f, 6f), androidx.compose.ui.geometry.Size(12f, 12f), style = Stroke(2.5f))
                placeText(tm, "${m.type} ${m.i}", p + Offset(9f, 2f), labelStyle.copy(color = Hud.Magenta), placed)
            }

            // traffic from the Tacview feed
            visibleContacts.forEach { c ->
                val p = pr.toScreen(c.x, c.y)
                if (!on(p)) return@forEach
                val col = contactColor(c)
                drawContact(c, p, col, pr)
                if (MapLayers.labels && c.kind != "missile") {
                    val who = listOfNotNull(supportLabel(c), c.group ?: c.name ?: c.kind).joinToString(" ")
                    placeText(tm, "$who ${flightLevel(c.altFt)}", p + Offset(10f, 4f), labelStyle.copy(color = col), placed)
                }
            }

            // ownship
            d.ownPos?.let { (ox, oy) -> drawOwnship(pr.toScreen(ox, oy), d.ownHdg, pr) }

            // selection
            selPos?.let { (sx, sy) ->
                val p = pr.toScreen(sx, sy)
                drawCircle(Hud.Magenta, 22f, p, style = Stroke(3f))
                d.ownPos?.let { (ox, oy) -> drawLine(Hud.Magenta.copy(alpha = 0.6f), pr.toScreen(ox, oy), p, 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))) }
            }
        }

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
                    HudChip("Traffic", MapLayers.traffic) { MapLayers.traffic = !MapLayers.traffic; MapLayers.save() }
                    HudChip("Hostiles", MapLayers.hostiles) { MapLayers.hostiles = !MapLayers.hostiles; MapLayers.save() }
                    HudChip("Labels", MapLayers.labels) { MapLayers.labels = !MapLayers.labels; MapLayers.save() }
                    HudChip("Fields", MapLayers.fields) { MapLayers.fields = !MapLayers.fields; MapLayers.save() }
                }
            }
            if (flightStrip) FlightStrip(d.live, d.bull)
            if (!knee) AcmiReminder()
        }
        // recenter
        if (!bare) Box(
            Modifier.align(Alignment.BottomEnd).padding(12.dp).size(46.dp).clip(RoundedCornerShape(23.dp)).background(Hud.Surface.copy(alpha = 0.95f))
                .border(1.dp, Hud.Outline, RoundedCornerShape(23.dp)).clickable {
                    val t = d.ownPos ?: d.route.firstOrNull()?.let { it.x!! to it.y!! }
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
            LayerRow("Traffic", MapLayers.traffic) { MapLayers.traffic = it }
            LayerRow("Hostiles", MapLayers.hostiles) { MapLayers.hostiles = it }
            LayerRow("Labels", MapLayers.labels) { MapLayers.labels = it }
            LayerRow("Airfields", MapLayers.fields) { MapLayers.fields = it }
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
            drawRect(Color.Black.copy(alpha = 0.6f), p - Offset(8f, 8f), androidx.compose.ui.geometry.Size(16f, 16f))
            drawRect(col, p - Offset(6f, 6f), androidx.compose.ui.geometry.Size(12f, 12f), style = Stroke(2.5f))
        }
        else -> if (c.friendly) {
            drawCircle(Color.Black.copy(alpha = 0.6f), 9f, p)
            drawCircle(col, 7f, p, style = Stroke(3f))
            if (c.kind == "heli") drawCircle(col, 2.5f, p)
        } else {
            val path = Path().apply { moveTo(p.x, p.y - 9f); lineTo(p.x + 9f, p.y); lineTo(p.x, p.y + 9f); lineTo(p.x - 9f, p.y); close() }
            drawPath(path, Color.Black.copy(alpha = 0.6f), style = Stroke(6f))
            drawPath(path, col, style = Stroke(3f))
        }
    }
}

/** Label with simple declutter: skipped if it would overlap one already drawn. */
fun DrawScope.placeText(tm: androidx.compose.ui.text.TextMeasurer, text: String, topLeft: Offset, style: TextStyle, placed: MutableList<androidx.compose.ui.geometry.Rect>) {
    if (topLeft.x > size.width || topLeft.y > size.height || text.isBlank()) return
    val layout = tm.measure(text, style)
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
    when (sel) {
        is MapSel.Ctc -> {
            val c = ctcs.firstOrNull { it.id == sel.id } ?: return
            // an air defence is not a contact to intercept: what is wanted is the system, its reach and where it is
            if (c.kind == "sam") {
                val site = d.sams.firstOrNull { it.id == c.id }
                accent = if (c.friendly) Friendly else Hostile
                title = site?.label ?: c.name ?: "Air defence"
                subtitle = listOfNotNull(c.name?.takeIf { it != title }, c.coalition).joinToString(" · ").ifBlank { null }
                rows += "RING" to (site?.rangeNm?.let { "${it.toInt()} nm engagement" } ?: "range not known")
                own?.let { rows += "BRAA" to bra(it.first, it.second, c.x, c.y) }
                d.bull?.let { rows += "BULLS" to bra(it.first, it.second, c.x, c.y) }
                pos = c.x to c.y
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
            accent = if (s.isTarget) Hostile else Hud.Amber
            title = "STPT ${s.n} · ${s.title}"
            subtitle = listOfNotNull(s.action, s.comments).joinToString(" · ").ifBlank { null }
            s.time?.let { rows += "TOS" to it }
            s.altText?.let { rows += "ALT" to it }
            s.cas?.let { rows += "CAS" to "$it kt" }
            s.targetName?.let { rows += "TGT" to it }
            if (s.hasPos) pos = s.x!! to s.y!!
            // takeoff / landing steerpoints sit on an airfield: offer its charts too
            if (s.hasPos) field = env.set?.airports?.minByOrNull { rangeNm(it.x, it.y, s.x!!, s.y!!) }?.takeIf { rangeNm(it.x, it.y, s.x!!, s.y!!) < 3 }
        }
        is MapSel.Ppt -> {
            val t = ppts.getOrNull(sel.index) ?: return
            accent = Hostile
            title = "Threat · ${t.name}"
            rows += "RANGE" to "%.0f nm".format(t.rangeNm)
            own?.let { if (rangeNm(it.first, it.second, t.x, t.y) < t.rangeNm) rows += "!" to "You are inside this ring" }
            pos = t.x to t.y
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
                        Text(v, style = LocalExtra.current.monoSmall.copy(fontSize = 13.sp), color = if (k == "!") Hostile else Hud.Text, maxLines = 2)
                    }
                }
                if (pair.size < perRow) Spacer(Modifier.weight(1f))
            }
        }
        } }
        field?.let { a ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Airfield details & charts ›",
                Modifier.clip(RoundedCornerShape(8.dp)).background(Hud.Green.copy(alpha = 0.15f)).clickable { env.theater?.let { th -> env.nav.go(missionAirportRoute(th.id, a.id)) } }.padding(horizontal = 10.dp, vertical = 6.dp),
                color = Hud.Green, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            )
        }
    }
}

fun missionAirportRoute(theater: String, id: Int) = "m/airport/${android.net.Uri.encode(theater)}/$id"

private fun airportKindShort(a: Airport) = com.bmscompanion.app.ui.screens.airportKind(a)

/** AWACS-style picture: nearest hostile air contacts first, ejected crews last, with bullseye position and BRAA. */
@Composable
fun PictureCard(ctcs: List<Contact>, own: Pair<Double, Double>?, ownHdg: Double, bull: Pair<Double, Double>?, onPick: (Contact) -> Unit) {
    val hostiles = ctcs.filter { !it.friendly && !it.own && (it.kind in setOf("air", "heli", "missile") || it.isCrew()) }
    val ref = own ?: bull
    val sorted = hostiles.sortedWith(compareBy({ it.isCrew() }, { c -> ref?.let { rangeNm(it.first, it.second, c.x, c.y) } ?: 0.0 }))
    val threats = hostiles.count { !it.isCrew() }
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
    com.bmscompanion.app.ui.components.SectionCard("Steerpoints", accent = Hud.Amber) {
        stpts.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected == s.n) Hud.Amber.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable(enabled = s.hasPos) { onPick(s) }.padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("%2d".format(s.n), style = LocalExtra.current.mono, color = if (s.isTarget) Hostile else Hud.Amber, modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (s.isTarget) Hostile else Hud.Text)
                    Text(listOfNotNull(s.time, s.altText, s.action).joinToString(" · "), fontSize = 11.sp, color = Hud.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
