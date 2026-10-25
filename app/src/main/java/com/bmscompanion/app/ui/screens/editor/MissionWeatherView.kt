package com.bmscompanion.app.ui.screens.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.mission.prefIntState
import com.bmscompanion.app.data.mission.prefState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionWeather
import com.bmscompanion.app.data.mission.MissionWxFrame
import com.bmscompanion.app.data.mission.MissionWxMap
import com.bmscompanion.app.data.mission.MissionWxPlace
import com.bmscompanion.app.data.mission.PcTwxType
import com.bmscompanion.app.data.weather.CampaignTime

import com.bmscompanion.app.data.weather.WxCover
import com.bmscompanion.app.data.weather.WxDefaults
import com.bmscompanion.app.data.weather.WxModel
import com.bmscompanion.app.data.weather.WxType
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.TheaterMap
import com.bmscompanion.app.ui.components.isMedium
import com.bmscompanion.app.ui.components.isWide
import com.bmscompanion.app.ui.components.rememberMapState
import com.bmscompanion.app.ui.screens.mission.FieldMark
import com.bmscompanion.app.ui.screens.mission.LegPt
import com.bmscompanion.app.ui.screens.mission.MissionEnv
import com.bmscompanion.app.ui.screens.mission.MissionTab
import com.bmscompanion.app.ui.screens.mission.MissionTabRequest
import com.bmscompanion.app.ui.screens.mission.airfieldRunways
import com.bmscompanion.app.ui.screens.mission.drawAirfield
import com.bmscompanion.app.ui.screens.mission.drawRouteLegs
import com.bmscompanion.app.ui.screens.mission.drawSteerpoint
import com.bmscompanion.app.ui.screens.mission.isLand
import com.bmscompanion.app.ui.screens.mission.isRefuel
import com.bmscompanion.app.ui.screens.mission.rememberMapData
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * The Weather tab's first page, **Mission weather** (1.4.0): the weather the mission actually flies, drawn over the
 * theater — read on the PC from the save BMS flies (`GET /api/weather/mission`, MissionWeatherRoute) and shown on every
 * device. Under Map Model the save's own map and, with MAPS AUTO UPDATE on, the update maps through the flight, stepped
 * with a time line that marks the save's clock, the take-off, the time over target and the landing; under the
 * Probabilistic and Deterministic models the one type BMS lays over the whole theater, with its table and how BMS
 * changes it — a map BMS does not have is not drawn. The route, the flight's airfields and the target sit on top. Read
 * only: nothing here writes anything.
 */

/** Which page of the Weather tab is on show: the mission's weather (the first) or the generator, kept on the device. */
object WeatherTabPage {
    private const val KEY = "weather_tab_page"
    // another device's page is taken when this one becomes active (MissionPrefs.NAV_KEYS)
    var mission by com.bmscompanion.app.data.mission.prefState(KEY) { Repo.getInt(KEY, 0) == 0 }
        private set

    fun show(mission: Boolean) { this.mission = mission; Repo.putInt(KEY, if (mission) 0 else 1) }

    /** The Briefing's and the Dashboard's weather "Map" button: the Weather tab, on the mission's weather. */
    fun openMission() {
        show(true)
        MissionTabRequest.open(MissionTab.WEATHER)
    }
}

/** The Weather card's button to the Mission weather page (Briefing, Dashboard); nothing on paper or a VR board. */
@Composable
fun MissionWeatherMapButton() {
    if (Hud.onPaper || com.bmscompanion.app.ui.Kneeboard.on) return
    Text(
        "MAP ›",
        Modifier.clip(RoundedCornerShape(4.dp)).clickable { WeatherTabPage.openMission() }
            .border(1.dp, Hud.Cyan.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = Hud.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
    )
}

/**
 * What the map is coloured by (one at a time, each a colour ramp, `MissionWxRamps.kt`); wind barbs, isobars and the
 * station circles are drawn over any of them. New fills go at the end (the device keeps the ordinal). [CLOUDS] is the
 * cloud coverage (it was labelled "Clouds" and drew the station circles; those are the **Cloud circles** overlay now).
 */
internal enum class WxFill(val label: String) {
    CLOUDS("Cloud coverage"), PRECIP("Precipitation"), VISIBILITY("Visibility"), TEMPERATURE("Temperature"), NONE("None"),
    PRESSURE("Pressure"), WIND("Wind speed"),
    // after the pilots' feedback: the weather type first (the global picture), and every array of the map as its own fill
    TYPE("Weather type"), CLOUD_BASE("Cloud base"), CLOUD_SIZE("Cloud size"), TOWERING("Towering cumulus"), SHOWER("Shower"),
    FOG("Fog altitude");

    companion object {
        /** The chips' order. */
        val SHOWN = listOf(TYPE, CLOUDS, CLOUD_BASE, CLOUD_SIZE, TOWERING, PRECIP, SHOWER, FOG, VISIBILITY, PRESSURE, WIND, TEMPERATURE)
    }
}

/**
 * What the weather map draws: one [fill] (at wind [level] for the wind's), and the overlays. The page passes the
 * pilot's choice ([MissionWxLook.view]); anything else drawing this page's map (a kneeboard page) may pass its own.
 */
internal data class WxView(
    val fill: WxFill,
    val level: Int = 0,
    val wind: Boolean = false,
    val isobars: Boolean = false,
    val smooth: Boolean = true,
    /** the station circles (cover, base, ▲ towering), off by default: they add noise over a fill */
    val stations: Boolean = false,
)

/** The page's view choices, kept on the device. */
internal object MissionWxLook {
    /** never chosen (no "mwx_fill" kept): the weather type, the default since the pilots' feedback */
    // (each follows another device's change too: MissionSync, PrefWatch)
    var fill by prefState("mwx_fill") { WxFill.entries.getOrNull(Repo.getInt("mwx_fill", -1)) ?: WxFill.TYPE }
        private set
    /** the cloud station circles, an overlay (off by default) */
    var stations by prefState("mwx_stations") { Repo.getInt("mwx_stations", 0) == 1 }
        private set
    fun stations(v: Boolean) { stations = v; Repo.putInt("mwx_stations", if (v) 1 else 0) }

    /** The choices as one value, for the drawing. */
    fun view(): WxView = WxView(fill, level, wind, isobars, smooth, stations)
    var wind by prefState("mwx_wind") { Repo.getInt("mwx_wind", 1) == 1 }
        private set
    var level by prefIntState("mwx_level") { Repo.getInt("mwx_level", 0).coerceIn(0, 9) }
        private set
    var isobars by prefState("mwx_isobars") { Repo.getInt("mwx_isobars", 0) == 1 }
        private set
    /** 1.4.1: the fill blended between the cell centres (the Windy look), else a block a cell */
    var smooth by prefState("mwx_smooth") { Repo.getInt("mwx_smooth", 1) == 1 }
        private set

    /** A fill chip pressed: that fill, or none when it was the one on. */
    fun fill(v: WxFill) { fill = if (fill == v) WxFill.NONE else v; Repo.putInt("mwx_fill", fill.ordinal) }
    /** The wind barbs on or off. */
    fun wind(v: Boolean) { wind = v; Repo.putInt("mwx_wind", if (v) 1 else 0) }
    fun level(v: Int) { level = v.coerceIn(0, 9); Repo.putInt("mwx_level", level) }
    fun isobars(v: Boolean) { isobars = v; Repo.putInt("mwx_isobars", if (v) 1 else 0) }
    fun smooth(v: Boolean) { smooth = v; Repo.putInt("mwx_smooth", if (v) 1 else 0) }
}

/** The mission picture drawn over the weather: the route and the flight's airfields. */
internal class WxPicture(val route: List<WxRoutePt> = emptyList(), val fields: List<Pair<Airport, String>> = emptyList())
internal class WxRoutePt(
    val x: Double, val y: Double, val n: Int, val target: Boolean, val land: Boolean, val refuel: Boolean, val onRoute: Boolean = true,
    /** the planned altitude (the briefing's ALT column, or the save's waypoint), ft; null when not known */
    val alt: Double? = null,
)

/** The Weather tab's first page: fetches the mission's weather and its maps from the PC, and draws [MissionWeatherPage]. */
@Composable
fun MissionWeatherPane(env: MissionEnv) {
    val link by MissionLink.state.collectAsState()
    val online = link is LinkState.Online
    val mission by MissionLink.mission.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var answer by remember { mutableStateOf<com.bmscompanion.app.data.mission.PcAnswer<MissionWeather>?>(null) }
    LaunchedEffect(online, refresh, mission?.briefingModified, mission?.populated?.at) {
        if (online) answer = MissionLink.missionWeather()
    }
    val w = answer?.value
    val maps = remember(w) { mutableStateMapOf<String, MissionWxMap>() }
    val mapErrors = remember(w) { mutableStateMapOf<String, String>() }
    var frame by remember(w) { mutableIntStateOf(w?.start ?: 0) }
    val frameId = w?.frames?.getOrNull(frame)?.id
    // the map shown here is the one Upd Kneeboard's Weather map prints (MissionWxShare)
    androidx.compose.runtime.SideEffect { MissionWxShare.shownFrame = frameId }
    LaunchedEffect(frameId, w) {
        val id = frameId ?: return@LaunchedEffect
        if (maps[id] != null) return@LaunchedEffect
        val r = MissionLink.missionWeatherMap(id)
        val m = r.value
        if (m != null && m.whole) maps[id] = m else mapErrors[id] = r.error ?: "The map came back incomplete."
        // the next frame too, so stepping forward does not wait
        w?.frames?.getOrNull(frame + 1)?.id?.let { next ->
            if (maps[next] == null) MissionLink.missionWeatherMap(next).value?.takeIf { it.whole }?.let { maps[next] = it }
        }
    }
    val theater by produceState<Theater?>(env.theater, w?.appTheater, w?.theater, env.theater) {
        val all = Repo.index().theaters
        value = w?.appTheater?.let { id -> all.firstOrNull { it.id == id } }
            ?: w?.theater?.let { com.bmscompanion.app.ui.screens.mission.resolveTheater(all, it) }
            ?: env.theater
    }
    // the mission picture as every map draws it (the merged route, the flight's fields)
    val data = rememberMapData(env)
    val picture = remember(data.route, data.depField, data.arrField, data.altField) {
        WxPicture(
            route = data.route.filter { it.x != null && it.y != null }.map {
                WxRoutePt(it.x!!, it.y!!, it.n, it.isTarget, it.isLand, it.isRefuel, it.onRoute, alt = briefedAltFt(it.altText))
            },
            fields = listOfNotNull(
                data.depField?.let { it to (if (it == data.arrField) "HOME" else "DEP") },
                data.arrField?.takeIf { it != data.depField }?.let { it to "ARR" },
                data.altField?.let { it to "ALT" },
            ),
        )
    }
    MissionWeatherPage(
        w = w,
        why = when {
            !online -> "Connect to the PC that runs Falcon BMS (Setup) to see the mission's weather."
            answer == null -> null
            w == null -> answer?.error ?: "The PC did not answer."
            else -> null
        },
        theater = theater,
        maps = maps, mapErrors = mapErrors, frame = frame, onFrame = { frame = it },
        picture = picture, briefing = mission?.briefing,
        onRefresh = { refresh++ },
    )
}

/**
 * The page itself, from what was fetched: split from [MissionWeatherPane] so `--wxviewrender` draws exactly what a
 * pilot sees from the PC's own answer. [why] is shown in place of the weather (no PC, an error); null with [w] null
 * means it is still being read.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MissionWeatherPage(
    w: MissionWeather?,
    why: String?,
    theater: Theater?,
    maps: Map<String, MissionWxMap>,
    mapErrors: Map<String, String>,
    frame: Int,
    onFrame: (Int) -> Unit,
    picture: WxPicture,
    briefing: Briefing?,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // what this is: the save, the flight, the model and as of when
        FlowRow(
            Modifier.fillMaxWidth().background(Hud.Surface.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(sourceLine(w), Modifier.align(Alignment.CenterVertically), color = Hud.TextDim, fontSize = 12.sp, lineHeight = 15.sp)
            Text(
                "Read again", Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(4.dp)).clickable(onClick = onRefresh)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = Hud.Cyan, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Rule(0.5f)
        if (w == null || !w.ok) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    w?.why ?: why ?: "Reading the mission's weather on the PC…",
                    color = if (w?.why != null || why != null) Hud.Text else Hud.TextDim, fontSize = 13.5.sp, lineHeight = 19.sp,
                )
                briefing?.let { BriefingFigures(it) }
            }
            return@Column
        }
        val wide = isWide()
        val mapModel = w.model == MissionWeather.MAP_MODEL && w.frames.isNotEmpty()
        val map = w.frames.getOrNull(frame)?.let { maps[it.id] }
        var picked by remember(w) { mutableStateOf<Pair<Int, Int>?>(null) }
        // Escape finishes (then takes away) the profile's line: the map column takes the keyboard while one is drawn
        val keys = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(WxProfile.drawing) { if (WxProfile.drawing) runCatching { keys.requestFocus() } }
        val mapBox: @Composable (Modifier) -> Unit = { mod -> BoxWithConstraints(mod) {
            // a short window (a browser of 900 px): the chips on one scrolling line, and the profile folded unless the
            // map keeps 60 % of the column with it open (and open, it never takes the map under half)
            val room = maxHeight
            val short = room < SHORT_ROOM
            val timeline = if (mapModel && w.frames.size > 1) TIMELINE_DP else 0.dp
            val chipsDp = if (short || !isMedium()) CHIPS_ONE_LINE else CHIPS_WRAPPED
            val profileRoomy = room - chipsDp - PROFILE_HEAD - PROFILE_BODY - timeline >= room * 0.6f
            val profileMax = (room * 0.5f - chipsDp - PROFILE_HEAD - timeline).coerceAtLeast(64.dp)
            Column(
                Modifier.fillMaxSize().focusRequester(keys).focusable().onKeyEvent { e ->
                    e.type == KeyEventType.KeyDown && e.key == Key.Escape && WxProfile.escape()
                },
            ) {
                if (mapModel) LayerChips(oneLine = short)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    WxMissionMap(w, theater, map, picture, picked, onPick = { picked = it })
                    if (mapModel && map == null) {
                        val f = w.frames.getOrNull(frame)
                        Text(
                            f?.let { mapErrors[it.id] } ?: "Reading ${f?.file ?: "the map"}…",
                            Modifier.align(Alignment.Center).clip(RoundedCornerShape(5.dp)).background(Hud.Bg.copy(alpha = 0.88f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            color = Hud.Text, fontSize = 12.sp,
                        )
                    }
                }
                if (mapModel) WxProfileStrip(w, map, theater?.sizeFt, picture, roomy = profileRoomy, maxBody = profileMax)
                if (mapModel && w.frames.size > 1) TimeLine(w, frame, onFrame)
            }
        } }
        val panel: @Composable (Modifier) -> Unit = { mod ->
            Column(mod.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (mapModel) MapPanel(w, frame, map, picked, theater) else TypePanel(w)
                Rule(0.35f)
                SunMoonBlock(w, theater, briefing?.overview?.sunrise)
                briefing?.let { BriefingFigures(it) }
                w.notes.forEach { Text(it, color = Hud.TextFaint, fontSize = 11.5.sp, lineHeight = 15.sp) }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                mapBox(Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline.copy(alpha = 0.5f)))
                panel(Modifier.width(360.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                mapBox(Modifier.fillMaxWidth().weight(0.6f))
                Rule(0.5f)
                panel(Modifier.fillMaxWidth().weight(0.4f))
            }
        }
    }
}

/** "Korea KTO · TR_BMS_08_TFR.trn · Viper 1 · Map Model · saved D1 10:20". */
private fun sourceLine(w: MissionWeather?): String {
    if (w == null) return "Mission weather"
    return listOfNotNull(
        w.theater.takeIf { it.isNotBlank() },
        w.save.takeIf { it.isNotBlank() },
        w.flight,
        modelName(w).takeIf { w.ok },
        w.clock?.let { "saved ${clockText(it)}" },
    ).joinToString(" · ").ifEmpty { "Mission weather" }
}

private fun modelName(w: MissionWeather): String = when (w.model) {
    MissionWeather.PROBABILISTIC -> "Probabilistic model"
    MissionWeather.DETERMINISTIC -> "Deterministic model"
    MissionWeather.MAP_MODEL -> if (w.mapUpdates) "Map Model, maps update" else "Map Model"
    else -> "Model ${w.model}"
}

/** "D1 05:00" from campaign ms. */
internal fun clockText(ms: Long): String = minuteText(ms / 60_000L)

private fun minuteText(minute: Long): String {
    val t = CampaignTime.fromMinutes(minute.toDouble())
    return "D${t.day} ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

// ------------------------------------------------------------------------------------------------ the map

/**
 * The weather over the theater: the type's wash (no map) or the map with [view]'s fill and overlays, the mission
 * picture, the profile's line and cursor, the picked cell, and the key. A tap picks a cell ([onPick]), or adds a point
 * to the profile's line while one is drawn. [view] is the pilot's choice on the page; another user (a kneeboard page)
 * may pass its own and leave [legend] or [profile] out.
 */
@Composable
internal fun WxMissionMap(
    w: MissionWeather, theater: Theater?, map: MissionWxMap?, picture: WxPicture, picked: Pair<Int, Int>?,
    view: WxView = MissionWxLook.view(),
    legend: Boolean = true,
    profile: Boolean = true,
    onPick: (Pair<Int, Int>?) -> Unit = {},
) {
    if (theater == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("The app has no map of ${w.theater.ifBlank { "this theater" }}.", color = Hud.TextDim, fontSize = 12.sp)
        }
        return
    }
    val state = rememberMapState()
    val text = rememberTextMeasurer(cacheSize = 512)
    val fill = view.fill
    // built once per map, fill and (for the wind) level — never per frame
    val level = if (fill == WxFill.WIND) view.level else 0
    val image = remember(map, fill, level) { map?.let { fillImage(it, fill, level) } }
    val isobars = remember(map) { map?.let { isobarSegments(it) } }
    val uniform = if (w.model != MissionWeather.MAP_MODEL || w.frames.isEmpty()) WxType.ofCode(w.condition.coerceIn(1, 4)) else null
    Box(Modifier.fillMaxSize()) {
        TheaterMap(
            imagePath = theater.map, sizeFt = theater.sizeFt, state = state, modifier = Modifier.fillMaxSize(), fillWidth = true,
            onTap = { xFt, yFt, _ ->
                val m = map
                if (profile && WxProfile.drawing) WxProfile.add(xFt, yFt)
                else onPick(if (m == null) null else cellOf(xFt, yFt, theater.sizeFt, m.cols, m.rows)?.takeIf { it != picked })
            },
        ) { pr ->
            if (uniform != null) {
                // the one type BMS lays over the whole theater: a wash of its colour, nothing pretending to be a map
                drawRect(WxRamps.typeColor(uniform).copy(alpha = 0.22f), Offset(pr.left, pr.top), Size(pr.side, pr.side))
            }
            if (map != null) drawMissionWeather(pr, map, image, isobars, text, view)
            drawPicture(pr, picture, w.places, text)
            if (profile) drawProfileOnMap(pr, text)
            if (map != null) picked?.let { (c, r) ->
                val cw = pr.side / map.cols
                val ch = pr.side / map.rows
                drawRect(Color.Black.copy(alpha = 0.6f), Offset(pr.left + c * cw - 1.5f, pr.top + r * ch - 1.5f), Size(cw + 3f, ch + 3f), style = Stroke(4f))
                drawRect(Hud.Amber, Offset(pr.left + c * cw - 1.5f, pr.top + r * ch - 1.5f), Size(cw + 3f, ch + 3f), style = Stroke(2f))
            }
        }
        if (map != null && legend) MapLegend(map, Modifier.align(Alignment.TopStart), view = view)
        if (uniform != null && legend) {
            Text(
                "${uniform.label} over the whole theater",
                Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(5.dp)).background(Hud.Bg.copy(alpha = 0.86f))
                    .border(1.dp, WxRamps.typeColor(uniform).copy(alpha = 0.8f), RoundedCornerShape(5.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                color = Hud.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The fill a pixel a cell ([cells]) and the same smoothed onto [SMOOTH_K] pixels a cell ([smooth]). */
private class FillImage(val cells: ImageBitmap, val smooth: ImageBitmap)

/** Pixels a cell of the smoothed fill: enough that the bilinear step drawn each frame no longer shows a lattice. */
private const val SMOOTH_K = 4

/**
 * The fill layer, a pixel a cell (its centre), drawn over the theater either a block a cell or blended between the
 * cell centres (**Smooth**: scaled once onto [SMOOTH_K] pixels a cell by the platform's best filter — a cubic on the
 * PC and in the browser — and drawn each frame with a plain bilinear one). Null for no fill. Built once per map, fill
 * and level, so a frame only scales it.
 */
private fun fillImage(m: MissionWxMap, fill: WxFill, level: Int): FillImage? {
    if (fill == WxFill.NONE) return null
    val img = ImageBitmap(m.cols, m.rows)
    val canvas = androidx.compose.ui.graphics.Canvas(img)
    val paint = Paint()
    val pressure = WxRamps.pressureRange(m)
    for (r in 0 until m.rows) for (c in 0 until m.cols) {
        paint.color = WxRamps.ink(m, r * m.cols + c, fill, level, pressure)
        canvas.drawRect(c.toFloat(), r.toFloat(), c + 1f, r + 1f, paint)
    }
    val big = ImageBitmap(m.cols * SMOOTH_K, m.rows * SMOOTH_K)
    androidx.compose.ui.graphics.Canvas(big).drawImageRect(
        img, IntOffset.Zero, IntSize(m.cols, m.rows), IntOffset.Zero, IntSize(m.cols * SMOOTH_K, m.rows * SMOOTH_K),
        Paint().apply { filterQuality = FilterQuality.High },
    )
    return FillImage(img, big)
}

/**
 * The map's weather over [pr]: [image] (from [fillImage] for [view]'s fill), the precipitation marks, the isobars, the
 * figures or station circles on a sparse lattice, and the wind barbs, as [view] says (the pilot's choice by default).
 */
private fun DrawScope.drawMissionWeather(
    pr: MapProjection, m: MissionWxMap, image: FillImage?, isobars: List<Isobar>?, text: TextMeasurer,
    view: WxView = MissionWxLook.view(),
) {
    val cw = pr.side / m.cols
    val ch = pr.side / m.rows
    image?.let {
        // a pixel's centre is its cell's: the blocks, or the smoothed image blended between its pixels
        val smooth = view.smooth
        val src = if (smooth) it.smooth else it.cells
        drawImage(
            src, srcOffset = IntOffset.Zero, srcSize = IntSize(src.width, src.height),
            dstOffset = IntOffset(pr.left.roundToInt(), pr.top.roundToInt()), dstSize = IntSize(pr.side.roundToInt(), pr.side.roundToInt()),
            filterQuality = if (smooth) FilterQuality.Low else FilterQuality.None,
        )
    }
    val fill = view.fill
    // the visible cells, for everything drawn a cell at a time
    val c0 = floor(-pr.left / cw).toInt().coerceAtLeast(0)
    val c1 = ceil((size.width - pr.left) / cw).toInt().coerceAtMost(m.cols - 1)
    val r0 = floor(-pr.top / ch).toInt().coerceAtLeast(0)
    val r1 = ceil((size.height - pr.top) / ch).toInt().coerceAtMost(m.rows - 1)
    // showers hatched, snow outlined: told apart without their colour (the Shower fill hatched as well)
    if ((fill == WxFill.PRECIP || fill == WxFill.SHOWER) && image != null) precipMarks(m, pr.left, pr.top, cw, ch, c0, c1, r0, r1, 7.dp.toPx(), showerFlag = fill == WxFill.SHOWER)
    drawRect(Color.White.copy(alpha = 0.35f), Offset(pr.left, pr.top), Size(pr.side, pr.side), style = Stroke(1f))
    val ink = Color.White
    val halo = Color.Black
    if (view.isobars && isobars != null) {
        // the isobars' figures in the lines' own pale blue, apart from the cells' white figures
        val label = TextStyle(color = Color(0xFFB8D4FF), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, shadow = Shadow(halo, blurRadius = 3f))
        isobars.forEach { iso ->
            for ((a, b) in iso.segments) {
                val p = Offset(pr.left + (a.x + 0.5f) * cw, pr.top + (a.y + 0.5f) * ch)
                val q = Offset(pr.left + (b.x + 0.5f) * cw, pr.top + (b.y + 0.5f) * ch)
                if (p.x < -20 && q.x < -20 || p.y < -20 && q.y < -20 || p.x > size.width + 20 && q.x > size.width + 20 || p.y > size.height + 20 && q.y > size.height + 20) continue
                drawLine(halo.copy(alpha = 0.45f), p, q, 3.2f)
                drawLine(Color(0xFFE8F0FF), p, q, 1.4f)
            }
        }
        // the figures last, none over another (lines bunch where the gradient is steep)
        val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
        isobars.forEach { iso ->
            iso.labels.forEach { at ->
                val p = Offset(pr.left + (at.x + 0.5f) * cw, pr.top + (at.y + 0.5f) * ch)
                if (p.x < 0 || p.y < 0 || p.x > size.width || p.y > size.height) return@forEach
                val t = text.measure(iso.mb.toString(), label)
                val r = androidx.compose.ui.geometry.Rect(p - Offset(t.size.width / 2f, t.size.height / 2f), Size(t.size.width.toFloat(), t.size.height.toFloat()))
                if (placed.any { it.inflate(14f).overlaps(r) }) return@forEach
                placed += r
                drawText(t, topLeft = r.topLeft)
            }
        }
    }
    // figures and glyphs in as many cells as they can be read in, on a fixed lattice so they do not shimmer: the cloud
    // station circles when they are on (an overlay, off by default), else the fill's own figure where it has one
    val figures = fill == WxFill.VISIBILITY || fill == WxFill.TEMPERATURE || (fill == WxFill.PRESSURE && !view.isobars) ||
        (fill == WxFill.WIND && !view.wind) || fill == WxFill.CLOUD_BASE || fill == WxFill.CLOUD_SIZE || (fill == WxFill.FOG && m.fogFt.isNotEmpty())
    val glyphs = view.stations || figures
    if (glyphs) {
        // the fill already says it everywhere: the glyphs and figures are a sparse key over it, never a carpet
        val stride = ceil(56.dp.toPx() / cw).toInt().coerceAtLeast(1)
        val style = TextStyle(color = ink, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, shadow = Shadow(halo, blurRadius = 3f))
        val unit = (cw * stride).coerceAtMost(64f)
        for (r in (r0 - r0 % stride)..r1 step stride) for (c in (c0 - c0 % stride)..c1 step stride) {
            if (r < 0 || c < 0) continue
            val i = r * m.cols + c
            val centre = Offset(pr.left + (c + 0.5f) * cw, pr.top + (r + 0.5f) * ch)
            if (view.stations) {
                val cover = WxRamps.coverAt(m, i)
                if (cover == WxCover.NONE) continue
                coverGlyph(centre, cover, m.towering.getOrNull(i) == 1, (unit * 0.2f).coerceIn(3f, 8f), ink, halo)
                if (unit >= 44f) {
                    val t = text.measure(three(m.cloudBaseFt[i] / 100), style)
                    drawText(t, topLeft = centre + Offset(-t.size.width / 2f, unit * 0.22f + 2f))
                }
                continue
            }
            when (fill) {
                WxFill.CLOUD_BASE, WxFill.CLOUD_SIZE -> {
                    if (!WxRamps.cloudy(m, i)) continue
                    val s = if (fill == WxFill.CLOUD_BASE) three(m.cloudBaseFt[i] / 100) else oneDecimal(m.cloudSize.getOrNull(i) ?: continue)
                    val t = text.measure(s, style)
                    drawText(t, topLeft = centre - Offset(t.size.width / 2f, t.size.height / 2f))
                }
                WxFill.FOG -> {
                    val fog = m.fogFt.getOrNull(i) ?: continue
                    if (fog <= 0) continue
                    val t = text.measure(three(fog / 100), style)
                    drawText(t, topLeft = centre - Offset(t.size.width / 2f, t.size.height / 2f))
                }
                WxFill.VISIBILITY -> {
                    val t = text.measure(visText(m.visKm[i].toDouble()), style)
                    drawText(t, topLeft = centre - Offset(t.size.width / 2f, t.size.height / 2f))
                }
                WxFill.PRESSURE -> {
                    val t = text.measure(m.pressureMb[i].roundToInt().toString(), style)
                    drawText(t, topLeft = centre - Offset(t.size.width / 2f, t.size.height / 2f))
                }
                WxFill.WIND -> {
                    val t = text.measure(m.windKt[i * MissionWxMap.LEVELS + view.level].toString(), style)
                    drawText(t, topLeft = centre - Offset(t.size.width / 2f, t.size.height / 2f))
                }
                WxFill.TEMPERATURE -> {
                    val t = text.measure("${m.tempC[i].roundToInt()}°", style)
                    drawText(t, topLeft = centre - Offset(t.size.width / 2f, t.size.height / 2f))
                }
                else -> {}
            }
        }
    }
    if (view.wind) {
        val stride = ceil(56.dp.toPx() / cw).toInt().coerceAtLeast(1)
        val unit = (cw * stride).coerceAtMost(64f)
        val level = view.level
        // offset half a stride from the figures, so a barb does not sit on a number
        val off = if (glyphs && stride > 1) stride / 2 else 0
        for (r in (r0 - r0 % stride + off)..r1 step stride) for (c in (c0 - c0 % stride + off)..c1 step stride) {
            if (r < 0 || c < 0 || r >= m.rows || c >= m.cols) continue
            val k = (r * m.cols + c) * MissionWxMap.LEVELS + level
            val centre = Offset(pr.left + (c + 0.5f) * cw, pr.top + (r + 0.5f) * ch)
            windBarb(centre, m.windDeg[k].toDouble(), m.windKt[k].toDouble(), unit * 0.46f, if (fill == WxFill.WIND) Color.White else Color(0xFFFFE082), halo)
        }
    }
}

/** The route, the flight's airfields and — with no route — the take-off, target and landing the save gives. */
private fun DrawScope.drawPicture(pr: MapProjection, p: WxPicture, places: List<MissionWxPlace>, text: TextMeasurer) {
    val label = TextStyle(color = Hud.Green, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, shadow = Shadow(Color.Black, blurRadius = 3f))
    p.fields.forEach { (a, tag) ->
        val at = pr.toScreen(a.x, a.y)
        drawAirfield(at, airfieldRunways(a), if (tag == "ALT") FieldMark.ALTERNATE else FieldMark.HOME)
        val t = text.measure("$tag ${a.icao ?: a.name}", label)
        drawText(t, topLeft = at + Offset(14f, 3f))
    }
    val plan = p.route.filter { it.onRoute }
    if (plan.size > 1) drawRouteLegs(plan.map { LegPt(pr.toScreen(it.x, it.y), land = it.land, refuel = it.refuel) }, Hud.Amber)
    val stpt = TextStyle(color = Hud.Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, shadow = Shadow(Color.Black, blurRadius = 3f))
    p.route.forEach { s ->
        val at = pr.toScreen(s.x, s.y)
        drawSteerpoint(at, s.target, if (s.target) Color(0xFFFF5252) else Hud.Amber)
        if (pr.scale >= 1.6f || s.target) {
            val t = text.measure(s.n.toString(), stpt)
            drawText(t, topLeft = at + Offset(10f, -18f))
        }
    }
    if (p.route.isEmpty()) {
        val ink = Hud.Cyan
        val st = label.copy(color = ink)
        places.forEach { pl ->
            val at = pr.toScreen(pl.x, pl.y)
            drawCircle(Color.Black.copy(alpha = 0.6f), 8f, at, style = Stroke(5f))
            drawCircle(ink, 7f, at, style = Stroke(2.5f))
            val t = text.measure(shortPlace(pl.label) + (pl.name?.let { " $it" } ?: ""), st)
            drawText(t, topLeft = at + Offset(11f, -6f))
        }
    }
}

/** "3.3" (one decimal, no platform formatting: the browser has only stand-ins for it). */
internal fun oneDecimal(v: Float): String {
    val t = (v * 10f).roundToInt()
    return (if (t < 0) "-" else "") + abs(t) / 10 + "." + abs(t) % 10
}

private fun shortPlace(label: String): String = when {
    label.startsWith("Take", true) -> "T/O"
    label.startsWith("Target", true) -> "TGT"
    label.startsWith("Land", true) -> "LDG"
    else -> label
}

// ------------------------------------------------------------------------------------------------ isobars

private class Isobar(val mb: Int, val segments: List<Pair<Offset, Offset>>, val labels: List<Offset>)

/**
 * QNH isobars by marching squares over the cell centres (x east, y south, in cells): every 2 mb, every 1 mb when the
 * whole map spans less than 6 mb, none when it is flat (a map of one type is one pressure). A few labels per line.
 */
private fun isobarSegments(m: MissionWxMap): List<Isobar> {
    val p = m.pressureMb
    val lo = p.minOrNull() ?: return emptyList()
    val hi = p.maxOrNull() ?: return emptyList()
    if (hi - lo < 0.9f) return emptyList()
    // a chart's 4 mb, closer where the whole map spans little: never more than a dozen lines
    val step = listOf(1, 2, 4, 8, 16).first { (hi - lo) / it <= 12f || it == 16 }
    val first = (ceil(lo / step) * step).toInt()
    val out = ArrayList<Isobar>()
    var level = first
    while (level <= hi && out.size < 60) {
        val v = level.toFloat()
        val segs = ArrayList<Pair<Offset, Offset>>()
        for (y in 0 until m.rows - 1) for (x in 0 until m.cols - 1) {
            val a = p[y * m.cols + x]; val b = p[y * m.cols + x + 1]
            val c = p[(y + 1) * m.cols + x + 1]; val d = p[(y + 1) * m.cols + x]
            val pts = ArrayList<Offset>(4)
            fun edge(v1: Float, v2: Float, p1: Offset, p2: Offset) {
                if ((v1 < v) != (v2 < v)) {
                    val t = if (v2 == v1) 0.5f else (v - v1) / (v2 - v1)
                    pts += Offset(p1.x + (p2.x - p1.x) * t, p1.y + (p2.y - p1.y) * t)
                }
            }
            val tl = Offset(x.toFloat(), y.toFloat()); val tr = Offset(x + 1f, y.toFloat())
            val br = Offset(x + 1f, y + 1f); val bl = Offset(x.toFloat(), y + 1f)
            edge(a, b, tl, tr); edge(b, c, tr, br); edge(c, d, br, bl); edge(d, a, bl, tl)
            if (pts.size == 2) segs += pts[0] to pts[1]
            else if (pts.size == 4) { segs += pts[0] to pts[1]; segs += pts[2] to pts[3] }
        }
        if (segs.isNotEmpty()) {
            // labels: spread out, at most one per 18-cell block
            val seen = HashSet<Int>()
            val labels = segs.mapNotNull { (s, e) ->
                val mid = Offset((s.x + e.x) / 2, (s.y + e.y) / 2)
                val key = (mid.x / 18).toInt() * 100 + (mid.y / 18).toInt()
                if (seen.add(key)) mid else null
            }
            out += Isobar(level, segs, labels)
        }
        level += step
    }
    return out
}

// ------------------------------------------------------------------------------------------------ controls

/** Under this height the map column is short: the chips go on one line that scrolls sideways. */
private val SHORT_ROOM = 720.dp
/** Roughly what the furniture round the map takes (chips on one line or wrapped, the profile's title line and body,
 *  the time line): only for deciding what folds, never for laying anything out. */
private val CHIPS_ONE_LINE = 40.dp
private val CHIPS_WRAPPED = 76.dp
private val PROFILE_HEAD = 32.dp
private val PROFILE_BODY = 150.dp
private val TIMELINE_DP = 96.dp

/**
 * The layers: on a tablet or PC every chip in view, wrapped onto a second line where they need it; on a phone one line
 * that scrolls sideways, so the map keeps its height.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayerChips(oneLine: Boolean = false) {
    val chips: @Composable () -> Unit = {
        // the fills (one at a time), then the overlays and the look
        for (f in WxFill.SHOWN) {
            EditorChip(f.label, selected = MissionWxLook.fill == f) { MissionWxLook.fill(f) }
        }
        Box(Modifier.padding(vertical = 3.dp).width(1.dp).height(18.dp).background(Hud.Outline.copy(alpha = 0.6f)))
        EditorChip("Barbs", selected = MissionWxLook.wind) { MissionWxLook.wind(!MissionWxLook.wind) }
        EditorChip("Isobars", selected = MissionWxLook.isobars) { MissionWxLook.isobars(!MissionWxLook.isobars) }
        EditorChip("Cloud circles", selected = MissionWxLook.stations) { MissionWxLook.stations(!MissionWxLook.stations) }
        EditorChip("Smooth", selected = MissionWxLook.smooth) { MissionWxLook.smooth(!MissionWxLook.smooth) }
        if (MissionWxLook.wind || MissionWxLook.fill == WxFill.WIND) {
            WxDefaults.WIND_ALTS.forEachIndexed { i, ft ->
                EditorChip(windAltLabel(ft), selected = MissionWxLook.level == i) { MissionWxLook.level(i) }
            }
        }
    }
    if (isMedium() && !oneLine) {
        FlowRow(
            Modifier.fillMaxWidth().background(Hud.Surface.copy(alpha = 0.35f)).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) { chips() }
    } else {
        Row(
            Modifier.fillMaxWidth().background(Hud.Surface.copy(alpha = 0.35f))
                .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { chips() }
    }
}

/** "SFC", "FL060", "FL300", as a pilot names the levels the map carries. */
private fun windAltLabel(ft: Int): String = if (ft == 0) "SFC" else "FL" + (ft / 100).toString().padStart(3, '0')

/**
 * The key for the fill and what the overlays show, over the map's top-left corner. Its sentences fold away on a tap
 * (folded to begin with on a phone, where the key would otherwise cover a third of the map); the title and the scale stay.
 */
@Composable
private fun MapLegend(m: MissionWxMap, modifier: Modifier, open: Boolean? = null, view: WxView = MissionWxLook.view()) {
    val fill = view.fill
    val medium = isMedium()
    // [open]: a printed page's key (Upd Kneeboard's Weather map), always unfolded whatever the device
    var more by remember(medium, open) { mutableStateOf(open ?: medium) }
    @Composable
    fun LegendLine(s: String) { if (more) Text(s, color = Hud.TextDim, fontSize = 10.5.sp, lineHeight = 13.sp) }
    @Composable
    fun LegendTitle(s: String) = Text(s + if (more) "  ▾" else "  ▸", color = Hud.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 13.sp)
    Column(
        modifier.padding(10.dp).widthIn(max = 250.dp).clip(RoundedCornerShape(5.dp)).background(Hud.Bg.copy(alpha = 0.84f))
            .border(1.dp, Hud.Outline.copy(alpha = 0.7f), RoundedCornerShape(5.dp)).clickable { more = !more }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val levelWords = WxDefaults.WIND_ALTS[view.level.coerceIn(0, WxDefaults.WIND_ALTS.size - 1)].let { if (it == 0) "the surface" else group(it) + " ft" }
        val cloudless = m.type.indices.none { WxRamps.cloudy(m, it) }
        when (fill) {
            WxFill.TYPE -> {
                LegendTitle("Weather type")
                TypeKey()
                LegendLine("BMS's four types, cell by cell: each brings its own cloud, visibility and wind")
            }
            WxFill.CLOUDS -> {
                LegendTitle("Cloud coverage")
                CoverKey(circles = view.stations)
                LegendLine("Pale FEW to dark OVC. Sunny cells are clear whatever the map's code (the Technical Manual's rule).")
            }
            WxFill.CLOUD_BASE -> {
                LegendTitle("Cloud base")
                ScaleBar(WxRamps.CLOUD_BASE, 0f, 15000f, listOf("0", "3,750", "7,500", "11,250", "15,000 ft"))
                LegendLine(if (cloudless) "No cloud anywhere on this map" else "Feet MSL, where there is cloud · number = hundreds of ft")
            }
            WxFill.CLOUD_SIZE -> {
                LegendTitle("Cloud size")
                ScaleBar(WxRamps.CLOUD_SIZE, 0f, 5f, listOf("0 congestus", "2.5", "5 humilis"))
                LegendLine(
                    when {
                        m.cloudSize.isEmpty() -> "The PC did not send the cloud size (an older BMS Companion on the PC)"
                        cloudless -> "No cloud anywhere on this map"
                        else -> "The map's cumulus size, where there is cloud: 0 deep congestus, 5 flat humilis"
                    },
                )
            }
            WxFill.TOWERING -> {
                LegendTitle("Towering cumulus")
                FlagKey("Towering cumulus", WxRamps.TOWERING.copy(alpha = 0.8f))
                LegendLine(if (m.towering.none { it == 1 }) "No towering cumulus anywhere on this map" else "The cells the map flags with towering cumulus")
            }
            WxFill.SHOWER -> {
                LegendTitle("Shower")
                FlagKey("Showers", WxRamps.showerInk().copy(alpha = 0.8f), hatched = true)
                LegendLine(
                    when {
                        m.shower.isEmpty() -> "This map (version ${m.version}) carries no shower flag"
                        m.shower.none { it == 1 } -> "No showers anywhere on this map"
                        else -> "The cells the map flags with showers"
                    },
                )
            }
            WxFill.FOG -> {
                LegendTitle("Fog altitude")
                ScaleBar(WxRamps.FOG, 0f, 12000f, listOf("0", "3,000", "6,000", "9,000", "12,000 ft"))
                LegendLine(
                    if (m.fogFt.isEmpty()) "This map (version ${m.version}) carries no fog layer"
                    else "The fog layer's height (feet, as the map holds it; a version 8 map's array 29), under which its visibility applies · number = hundreds of ft",
                )
            }
            WxFill.PRECIP -> {
                val any = m.type.indices.any { WxRamps.precipKind(m, it) > 0 }
                LegendTitle("Precipitation")
                PrecipKey(showers = m.shower.isNotEmpty())
                LegendLine(if (any) "BMS rains in inclement weather and snows there at or below 0 °C; showers are a map's own flag. BMS gives no intensity." else "No precipitation anywhere on this map")
            }
            WxFill.PRESSURE -> {
                val (lo, hi) = WxRamps.pressureRange(m)
                LegendTitle("Pressure (QNH)")
                ScaleBar(WxRamps.PRESSURE, 0f, 1f, pressureLabels(lo, hi))
                LegendLine("Low blue, high red, over this map's range")
            }
            WxFill.WIND -> {
                LegendTitle("Wind speed at $levelWords")
                ScaleBar(WxRamps.WIND, 0f, 140f, listOf("0", "35", "70", "105", "140 kt"))
            }
            WxFill.TEMPERATURE -> {
                LegendTitle("Temperature at the surface")
                ScaleBar(WxRamps.TEMPERATURE, -30f, 40f, listOf("−30", "−12", "5", "23", "40 °C"))
            }
            WxFill.VISIBILITY -> {
                LegendTitle("Visibility")
                ScaleBar(WxRamps.VISIBILITY, 0f, 30f, listOf("0", "7.5", "15", "22.5", "30 km"))
                LegendLine("Red under 1.6 km (a mile), amber under 5, yellow under 8")
            }
            WxFill.NONE -> {}
        }
        if (view.stations && fill != WxFill.CLOUDS) {
            LegendLine("Circles: the sky covered — ¼ FEW, ½ SCT, ¾ BKN, full OVC · number = cloud base in hundreds of ft · ▲ towering cumulus")
        } else if (view.stations) {
            LegendLine("Circle = the sky covered: ¼ FEW, ½ SCT, ¾ BKN, full OVC · number = cloud base in hundreds of ft · ▲ towering cumulus")
        }
        if (view.wind) LegendLine("Barbs: wind at $levelWords, pointing into the wind")
        if (view.isobars) {
            val lo = m.pressureMb.minOrNull() ?: 0f
            val hi = m.pressureMb.maxOrNull() ?: 0f
            LegendLine(if (hi - lo < 0.9f) "Pressure ${lo.roundToInt()} mb everywhere: no isobars" else "Isobars, mb (${lo.roundToInt()}–${hi.roundToInt()})")
        }
    }
}



/**
 * The maps through time: a tick per map, the one shown lit, and the save's clock, the take-off, the time over target
 * and the landing marked. A tap or a drag picks the map in force at that moment; a mark's tap jumps to its map.
 */
@Composable
private fun TimeLine(w: MissionWeather, frame: Int, onFrame: (Int) -> Unit) {
    val frames = w.frames
    val minutes = frames.map { it.minute ?: 0L }
    val marks = buildList {
        w.clock?.let { add("SAVE" to it / 60_000L) }
        w.places.forEach { p -> if (p.ms > 0) add(shortPlace(p.label) to p.ms / 60_000L) }
    }
    val lo = (minutes + marks.map { it.second }).minOrNull() ?: 0L
    val hi = maxOf((minutes + marks.map { it.second }).maxOrNull() ?: 0L, lo + 60)
    val text = rememberTextMeasurer()
    val f = frames.getOrNull(frame)
    Column(Modifier.fillMaxWidth().background(Hud.Surface.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("◀", frame > 0) { onFrame(frame - 1) }
            Spacer(Modifier.width(8.dp))
            Text(
                (f?.minute?.let { minuteText(it) } ?: "") + "  " + frameWords(f, frames.getOrNull(frame + 1)),
                Modifier.weight(1f), color = Hud.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 2, lineHeight = 15.sp,
            )
            Spacer(Modifier.width(8.dp))
            StepButton("▶", frame < frames.size - 1) { onFrame(frame + 1) }
        }
        fun pick(x: Float, width: Float) {
            val minute = lo + ((x / width).coerceIn(0f, 1f) * (hi - lo)).toLong()
            onFrame(inForce(minutes, minute))
        }
        Canvas(
            Modifier.fillMaxWidth().height(52.dp)
                .pointerInput(frames) { detectTapGestures { pick(it.x, size.width.toFloat()) } }
                .pointerInput(frames) { detectHorizontalDragGestures { ch, _ -> pick(ch.position.x, size.width.toFloat()) } },
        ) {
            val pad = 8f
            val wpx = size.width - 2 * pad
            fun xOf(m: Long) = pad + ((m - lo).toFloat() / (hi - lo)) * wpx
            val y = size.height * 0.74f
            drawLine(Hud.Outline, Offset(pad, y), Offset(size.width - pad, y), 2f)
            minutes.forEachIndexed { i, m ->
                val x = xOf(m)
                drawLine(if (i == frame) Hud.Amber else Hud.TextDim, Offset(x, y - 6f), Offset(x, y + 6f), if (i == frame) 3f else 1.5f)
            }
            f?.minute?.let { m ->
                val next = minutes.getOrNull(frame + 1) ?: hi
                drawRect(Hud.Amber.copy(alpha = 0.25f), Offset(xOf(m), y - 4f), Size(xOf(next) - xOf(m), 8f))
                drawCircle(Hud.Amber, 6f, Offset(xOf(m), y))
            }
            val style = TextStyle(color = Hud.Cyan, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
            // labels on two rows where they would touch (the take-off is often minutes after the save)
            val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
            marks.sortedBy { it.second }.forEach { (name, m) ->
                val x = xOf(m)
                val tri = Path().apply { moveTo(x, y - 7f); lineTo(x - 5f, y - 15f); lineTo(x + 5f, y - 15f); close() }
                drawPath(tri, if (name == "SAVE") Hud.TextDim else Hud.Cyan)
                val t = text.measure(name, if (name == "SAVE") style.copy(color = Hud.TextDim) else style)
                val left = (x - t.size.width / 2f).coerceIn(0f, size.width - t.size.width)
                val low = androidx.compose.ui.geometry.Rect(Offset(left, y - 17f - t.size.height), Size(t.size.width.toFloat(), t.size.height.toFloat()))
                val r = (0..2).map { k -> low.translate(0f, -k * t.size.height.toFloat()) }.firstOrNull { c ->
                    placed.none { p -> p.left - 3f < c.right && c.left < p.right + 3f && p.top < c.bottom - 1f && c.top < p.bottom - 1f }
                } ?: low
                placed += r
                drawText(t, topLeft = Offset(r.left, r.top.coerceAtLeast(0f)))
            }
        }
    }
}

/** The frame in force at [minute]: the last that starts at or before it, else the first. */
private fun inForce(minutes: List<Long>, minute: Long): Int = minutes.indexOfLast { it <= minute }.coerceAtLeast(0)

private fun frameWords(f: MissionWxFrame?, next: MissionWxFrame?): String {
    // never `when { f == null -> …; f.kind == "save" -> … }`: Kotlin/Wasm turns that into a string switch on `f` itself
    // (String.hashCode of a MissionWxFrame), which trapped and froze the whole browser page (CLAUDE.md, Gotchas)
    if (f == null) return ""
    val until = next?.minute?.let { " until ${minuteText(it)}" } ?: ""
    val save = f.kind == "save"
    return if (save) "the save's own map (${f.file})$until" else "update map ${f.file}$until"
}

@Composable
private fun StepButton(s: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(5.dp)).background(if (enabled) Hud.Surface else Hud.Surface.copy(alpha = 0.3f))
            .border(1.dp, Hud.Outline.copy(alpha = if (enabled) 0.8f else 0.3f), RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(s, color = if (enabled) Hud.Text else Hud.TextFaint, fontSize = 13.sp) }
}

// ------------------------------------------------------------------------------------------------ the panel

/** Map Model: the figures at the take-off, target and landing on the map shown, and the cell tapped, level by level. */
@Composable
private fun MapPanel(w: MissionWeather, frame: Int, map: MissionWxMap?, picked: Pair<Int, Int>?, theater: Theater?) {
    val f = w.frames.getOrNull(frame)
    PanelTitle("On this map" + (f?.minute?.let { " · ${minuteText(it)}" } ?: ""))
    if (map == null || theater == null) {
        Text("Reading the map…", color = Hud.TextDim, fontSize = 12.sp)
        return
    }
    if (w.places.isEmpty()) Text("The flight's take-off, target and landing are not known for this save.", color = Hud.TextFaint, fontSize = 11.5.sp, lineHeight = 15.sp)
    w.places.forEach { p ->
        val cell = cellOf(p.x, p.y, theater.sizeFt, map.cols, map.rows)
        val at = if (p.ms > 0) " · ${clockText(p.ms)}" else ""
        val here = w.frames.indices.lastOrNull { (w.frames[it].minute ?: 0L) <= p.ms / 60_000L }
        val other = if (p.ms > 0 && here != null && here != frame && w.frames.size > 1) " (another map is in force then)" else ""
        Column {
            Text(p.label + (p.name?.let { " — $it" } ?: "") + at + other, color = Hud.Cyan, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            if (cell == null) Text("off the map", color = Hud.TextFaint, fontSize = 12.sp)
            else Text(cellLine(map, cell.first, cell.second), color = Hud.Text, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
        }
    }
    Rule(0.35f)
    if (picked == null) {
        Text("Tap the map for a cell's whole column.", color = Hud.TextFaint, fontSize = 11.5.sp)
    } else {
        val (c, r) = picked
        PanelTitle("Cell $c,$r")
        Text(cellLine(map, c, r), color = Hud.Text, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
        val i = r * map.cols + c
        val type = WxType.ofCode(map.type[i])
        val stratus = map.stratusFt.getOrNull(if (type == WxType.SUNNY || type == WxType.FAIR) 0 else 1)
        val contrail = map.contrailFt.getOrNull(type.code - 1)
        Text(
            "${type.label}" + (if (map.towering.getOrNull(i) == 1) " · towering cumulus" else "") + (if (map.shower.getOrNull(i) == 1) " · showers" else "") +
                (map.cloudSize.getOrNull(i)?.takeIf { WxRamps.cloudy(map, i) }?.let { " · cloud size ${oneDecimal(it)}" } ?: "") +
                (map.fogFt.getOrNull(i)?.takeIf { it > 0 }?.let { " · fog layer to ${group(it)} ft" } ?: "") +
                (stratus?.takeIf { it > 0 }?.let { " · high stratus ${group(it)} ft" } ?: "") + (contrail?.takeIf { it > 0 }?.let { " · contrails from ${group(it)} ft" } ?: ""),
            color = Hud.TextDim, fontSize = 11.5.sp, lineHeight = 15.sp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            WxDefaults.WIND_ALTS.forEachIndexed { k, ft ->
                val j = i * MissionWxMap.LEVELS + k
                Row {
                    Text(windAltLabel(ft), Modifier.width(64.dp), color = Hud.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text(windText(map.windDeg[j].toDouble(), map.windKt[j].toDouble()) + " kt", color = if (k == MissionWxLook.level) Hud.Amber else Hud.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
    Rule(0.35f)
    Text(
        "Map ${map.file} (version ${map.version}) drifts toward ${three(map.moveHeading)}° at ${map.moveKt.roundToInt()} kt between updates (as its header says; not drawn).",
        color = Hud.TextFaint, fontSize = 11.sp, lineHeight = 14.sp,
    )
}

/** "BKN045 · 12 km · 23° · 1013 mb 29.91 · 270@15". */
private fun cellLine(m: MissionWxMap, c: Int, r: Int): String {
    val i = r * m.cols + c
    val cover = WxRamps.coverAt(m, i)
    val cloud = if (cover == WxCover.NONE) "SKC" else cover.metar + three(m.cloudBaseFt[i] / 100) + if (m.towering.getOrNull(i) == 1) " TCU" else ""
    val mb = m.pressureMb[i].toDouble()
    val inHg = WxModel.mbToInHg(mb)
    val k = i * MissionWxMap.LEVELS
    val precip = when (WxRamps.precipKind(m, i)) { 3 -> " SN"; 2 -> " RA"; 1 -> " SHRA"; else -> "" }
    return "${windText(m.windDeg[k].toDouble(), m.windKt[k].toDouble())}kt  ${visText(m.visKm[i].toDouble())} km$precip\n" +
        "$cloud  ${m.tempC[i].roundToInt()}°C  Q${mb.roundToInt()}  A${pressureText(inHg, false)}"
}

/** Probabilistic or Deterministic: the type BMS lays over the theater, the four types' tables, and how it changes. */
@Composable
private fun TypePanel(w: MissionWeather) {
    val twx = w.twx
    val now = w.condition.coerceIn(1, 4)
    PanelTitle("The whole theater: ${MissionWeather.TYPE_NAMES[now - 1]}" + (w.clock?.let { " at ${clockText(it)}" } ?: ""))
    Text(
        when (w.model) {
            MissionWeather.PROBABILISTIC -> "BMS's Probabilistic model has no map: the theater is one weather type at a time, and at each change interval BMS draws the next type from the four chances below."
            MissionWeather.DETERMINISTIC -> "BMS's Deterministic model has no map: the theater is one weather type at a time. The briefing printed in BMS gives the type at take-off, target and landing."
            else -> "This Map Model mission's map could not be read, so the type its weather file is in is shown with that type's table."
        },
        color = Hud.TextDim, fontSize = 12.sp, lineHeight = 16.sp,
    )
    if (twx == null || twx.types.size < 4) return
    TypeTable(twx.types, now, twx.windHeld, twx.windDeg)
    when (w.model) {
        MissionWeather.PROBABILISTIC -> if (twx.probabilities.size == 4) {
            PanelTitle("Chances at each change")
            Text(
                MissionWeather.TYPE_NAMES.zip(twx.probabilities).joinToString("  ") { (n, p) -> "$n ${(p * 100).roundToInt()}%" } +
                    (if (twx.intervalMin > 0) "\nEvery ${twx.intervalMin}" + (if (twx.intervalMax > twx.intervalMin) "–${twx.intervalMax}" else "") + " min" else ""),
                color = Hud.Text, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp,
            )
        }
        // the Deterministic model's schedule is not shown: in the files BMS 4.38.1 writes it is always six entries a day
        // apart at one hour, and it disagrees with the type the save is in (which the printed briefings confirm), so
        // what it means is not known (`PcTwx.changes` is still served)
        else -> {}
    }
}

/** The four types side by side, the one now marked: wind, visibility, cloud, contrails, temperature, QNH. */
@Composable
private fun TypeTable(types: List<PcTwxType>, now: Int, windHeld: Boolean, windDeg: Int) {
    val rows: List<Pair<String, (PcTwxType) -> String>> = listOf(
        "Wind" to { t -> (if (windHeld) three(if (windDeg == 0) 360 else windDeg) else "VRB") + "@" + (t.windKt.firstOrNull() ?: 0) },
        "Visibility" to { t -> if (t.fogEndFt > 0) visText(t.fogEndFt / 3280.84) + " km" else "–" },
        "Cumulus" to { t -> if (t.cumulusFt > 0) group(t.cumulusFt) else "–" },
        "Stratus" to { t -> if (t.stratusFt > 0) group(t.stratusFt) else "–" },
        "Contrails" to { t -> if (t.contrailFt > 0) group(t.contrailFt) else "–" },
        "Temp" to { t -> t.tempC.firstOrNull()?.let { "$it°C" } ?: "–" },
        "QNH" to { t -> t.qnhMb.firstOrNull()?.toString() ?: "–" },
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Spacer(Modifier.width(76.dp))
            MissionWeather.TYPE_NAMES.forEachIndexed { k, n ->
                Text(
                    if (n.length > 5) n.take(4) + "." else n, Modifier.weight(1f), color = if (k + 1 == now) Hud.Amber else Hud.TextFaint,
                    fontSize = 11.sp, fontWeight = if (k + 1 == now) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        rows.forEach { (name, value) ->
            Row {
                Text(name, Modifier.width(76.dp), color = Hud.TextDim, fontSize = 11.5.sp)
                types.take(4).forEachIndexed { k, t ->
                    Text(
                        value(t), Modifier.weight(1f), color = if (k + 1 == now) Hud.Text else Hud.TextDim,
                        fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                    )
                }
            }
        }
        Text("Feet MSL; wind in knots; QNH in mb.", color = Hud.TextFaint, fontSize = 10.5.sp)
    }
}

/** The printed briefing's (or the snapshot's) weather block, compact: what BMS says at take-off, target and landing. */
@Composable
private fun BriefingFigures(b: Briefing) {
    val wx = b.weather ?: return
    if (wx.rows.isEmpty()) return
    Rule(0.35f)
    PanelTitle("The briefing says")
    Row {
        Spacer(Modifier.width(78.dp))
        wx.columns.forEach { Text(it, Modifier.weight(1f), color = Hud.TextFaint, fontSize = 10.5.sp, maxLines = 1) }
    }
    wx.rows.forEach { r ->
        Row(Modifier.padding(vertical = 1.dp)) {
            Text(r.label, Modifier.width(78.dp), color = Hud.TextDim, fontSize = 11.5.sp)
            r.values.forEach { Text(it, Modifier.weight(1f), color = Hud.Text, fontSize = 11.5.sp, lineHeight = 14.sp) }
        }
    }
    wx.source?.let { Text(it, color = Hud.TextFaint, fontSize = 10.5.sp, lineHeight = 13.sp) }
}

@Composable
private fun PanelTitle(s: String) = Text(s.uppercase(), color = Hud.TextFaint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)

// ------------------------------------------------------------------------------------------------ lent to Upd Kneeboard

/**
 * This page's own drawing, lent to Upd Kneeboard's **Weather map** (`wdp/KneeboardWeatherPage.kt`), so the printed
 * page shows the mission's weather exactly as this page does: the pilot's choices here ([MissionWxLook]) and the map
 * shown here ([shownFrame]). It adds nothing of its own — keep it in step when the drawing above changes.
 */
internal object MissionWxShare {
    /** The id of the map this page last showed (null before it was opened in this run): the one the printed page takes. */
    var shownFrame: String? = null

    /** What the drawing makes once per map, fill and level: the fill picture and the isobars. */
    class Layers(internal val image: Any?, internal val isobars: Any?)

    fun layers(m: MissionWxMap): Layers {
        val fill = MissionWxLook.fill
        return Layers(fillImage(m, fill, if (fill == WxFill.WIND) MissionWxLook.level else 0), isobarSegments(m))
    }

    /** BMS's one type over the whole theater when the mission has no map (Probabilistic, Deterministic); else null. */
    fun uniform(w: MissionWeather): WxType? =
        if (w.model != MissionWeather.MAP_MODEL || w.frames.isEmpty()) WxType.ofCode(w.condition.coerceIn(1, 4)) else null

    /** The weather over [pr] as [WxMissionMap] draws it: the type's wash (no map), the map with its overlays, then [picture]. */
    @Suppress("UNCHECKED_CAST")
    fun draw(scope: DrawScope, pr: MapProjection, w: MissionWeather, m: MissionWxMap?, layers: Layers?, picture: WxPicture, text: TextMeasurer) = with(scope) {
        uniform(w)?.let { drawRect(typeSwatch(it).copy(alpha = 0.14f), Offset(pr.left, pr.top), Size(pr.side, pr.side)) }
        if (m != null) drawMissionWeather(pr, m, layers?.image as FillImage?, layers?.isobars as List<Isobar>?, text)
        drawPicture(pr, picture, w.places, text)
    }

    /** The map's key, unfolded. */
    @Composable
    fun Legend(m: MissionWxMap, modifier: Modifier) = MapLegend(m, modifier, open = true)

    /** The one type's table and how BMS changes it (no map). */
    @Composable
    fun Types(w: MissionWeather) = TypePanel(w)

    /** "VRB@5kt 12 km …" at a point of the theater, as the panel words a cell; null off the map. */
    fun cell(m: MissionWxMap, xFt: Double, yFt: Double, sizeFt: Double): String? =
        cellOf(xFt, yFt, sizeFt, m.cols, m.rows)?.let { (c, r) -> cellLine(m, c, r) }

    fun source(w: MissionWeather): String = sourceLine(w)
    fun minute(m: Long): String = minuteText(m)
    fun place(label: String): String = shortPlace(label)
}

