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
import com.bmscompanion.app.data.weather.WxColors
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
    var mission by mutableStateOf(Repo.getInt(KEY, 0) == 0)
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

/** What the map is coloured by (one at a time); wind barbs and isobars are drawn over any of them. */
internal enum class WxFill(val label: String) { CLOUDS("Clouds"), PRECIP("Precipitation"), VISIBILITY("Visibility"), TEMPERATURE("Temperature"), NONE("None") }

/** The page's view choices, kept on the device. */
internal object MissionWxLook {
    var fill by mutableStateOf(WxFill.entries.getOrNull(Repo.getInt("mwx_fill", 0)) ?: WxFill.CLOUDS)
        private set
    var wind by mutableStateOf(Repo.getInt("mwx_wind", 1) == 1)
        private set
    var level by mutableIntStateOf(Repo.getInt("mwx_level", 0).coerceIn(0, 9))
        private set
    var isobars by mutableStateOf(Repo.getInt("mwx_isobars", 0) == 1)
        private set

    /** A fill chip pressed: that fill, or none when it was the one on. */
    fun fill(v: WxFill) { fill = if (fill == v) WxFill.NONE else v; Repo.putInt("mwx_fill", fill.ordinal) }
    fun wind(v: Boolean) { wind = v; Repo.putInt("mwx_wind", if (v) 1 else 0) }
    fun level(v: Int) { level = v.coerceIn(0, 9); Repo.putInt("mwx_level", level) }
    fun isobars(v: Boolean) { isobars = v; Repo.putInt("mwx_isobars", if (v) 1 else 0) }
}

/** The mission picture drawn over the weather: the route and the flight's airfields. */
internal class WxPicture(val route: List<WxRoutePt> = emptyList(), val fields: List<Pair<Airport, String>> = emptyList())
internal class WxRoutePt(val x: Double, val y: Double, val n: Int, val target: Boolean, val land: Boolean, val refuel: Boolean, val onRoute: Boolean = true)

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
                WxRoutePt(it.x!!, it.y!!, it.n, it.isTarget, it.isLand, it.isRefuel, it.onRoute)
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
        val mapBox: @Composable (Modifier) -> Unit = { mod ->
            Column(mod) {
                if (mapModel) LayerChips()
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    WxMissionMap(w, theater, map, picture, picked) { picked = it }
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
                if (mapModel && w.frames.size > 1) TimeLine(w, frame, onFrame)
            }
        }
        val panel: @Composable (Modifier) -> Unit = { mod ->
            Column(mod.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (mapModel) MapPanel(w, frame, map, picked, theater) else TypePanel(w)
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

@Composable
private fun WxMissionMap(w: MissionWeather, theater: Theater?, map: MissionWxMap?, picture: WxPicture, picked: Pair<Int, Int>?, onPick: (Pair<Int, Int>?) -> Unit) {
    if (theater == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("The app has no map of ${w.theater.ifBlank { "this theater" }}.", color = Hud.TextDim, fontSize = 12.sp)
        }
        return
    }
    val state = rememberMapState()
    val text = rememberTextMeasurer(cacheSize = 512)
    val fill = MissionWxLook.fill
    val image = remember(map, fill) { map?.let { fillImage(it, fill) } }
    val isobars = remember(map) { map?.let { isobarSegments(it) } }
    val uniform = if (w.model != MissionWeather.MAP_MODEL || w.frames.isEmpty()) WxType.ofCode(w.condition.coerceIn(1, 4)) else null
    Box(Modifier.fillMaxSize()) {
        TheaterMap(
            imagePath = theater.map, sizeFt = theater.sizeFt, state = state, modifier = Modifier.fillMaxSize(), fillWidth = true,
            onTap = { xFt, yFt, _ ->
                val m = map
                onPick(if (m == null) null else cellOf(xFt, yFt, theater.sizeFt, m.cols, m.rows)?.takeIf { it != picked })
            },
        ) { pr ->
            if (uniform != null) {
                // the one type BMS lays over the whole theater: a wash of its colour, nothing pretending to be a map
                drawRect(typeSwatch(uniform).copy(alpha = 0.14f), Offset(pr.left, pr.top), Size(pr.side, pr.side))
            }
            if (map != null) drawMissionWeather(pr, map, image, isobars, text)
            drawPicture(pr, picture, w.places, text)
            if (map != null) picked?.let { (c, r) ->
                val cw = pr.side / map.cols
                val ch = pr.side / map.rows
                drawRect(Color.Black.copy(alpha = 0.6f), Offset(pr.left + c * cw - 1.5f, pr.top + r * ch - 1.5f), Size(cw + 3f, ch + 3f), style = Stroke(4f))
                drawRect(Hud.Amber, Offset(pr.left + c * cw - 1.5f, pr.top + r * ch - 1.5f), Size(cw + 3f, ch + 3f), style = Stroke(2f))
            }
        }
        if (map != null) MapLegend(map, Modifier.align(Alignment.TopStart))
        if (uniform != null) {
            Text(
                "${uniform.label} over the whole theater",
                Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(5.dp)).background(Hud.Bg.copy(alpha = 0.86f))
                    .border(1.dp, typeSwatch(uniform).copy(alpha = 0.8f), RoundedCornerShape(5.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                color = Hud.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The fill layer, a pixel a cell, drawn scaled and smoothed over the theater. Null for no fill. */
private fun fillImage(m: MissionWxMap, fill: WxFill): ImageBitmap? {
    if (fill == WxFill.NONE) return null
    val img = ImageBitmap(m.cols, m.rows)
    val canvas = androidx.compose.ui.graphics.Canvas(img)
    val paint = Paint()
    for (r in 0 until m.rows) for (c in 0 until m.cols) {
        val i = r * m.cols + c
        paint.color = when (fill) {
            WxFill.CLOUDS -> cloudInk(WxCover.ofCode(m.cover[i]), m.towering.getOrNull(i) == 1)
            WxFill.PRECIP -> precipInk(m, i)
            WxFill.VISIBILITY -> visInk(m.visKm[i].toDouble())
            WxFill.TEMPERATURE -> Color(WxColors.temperature(m.tempC[i].toDouble())).copy(alpha = 0.5f)
            WxFill.NONE -> Color.Transparent
        }
        canvas.drawRect(c.toFloat(), r.toFloat(), c + 1f, r + 1f, paint)
    }
    return img
}

private fun cloudInk(c: WxCover, towering: Boolean): Color = when (c) {
    WxCover.NONE -> Color.Transparent
    WxCover.FEW -> Color(0xFFE6ECF2).copy(alpha = 0.22f)
    WxCover.SCATTERED -> Color(0xFFE6ECF2).copy(alpha = 0.38f)
    WxCover.BROKEN -> (if (towering) Color(0xFFD7DCE6) else Color(0xFFE6ECF2)).copy(alpha = 0.56f)
    WxCover.OVERCAST -> Color(0xFFC9D0DA).copy(alpha = 0.72f)
}

/** BMS rains only in inclement weather (snow at or below freezing); a version 8 map's shower flag adds showers. */
private fun precipKind(m: MissionWxMap, i: Int): Int = when {
    m.type[i] == WxType.INCLEMENT.code -> if (m.tempC[i] <= 0f) 3 else 2
    m.shower.getOrNull(i) == 1 -> 1
    else -> 0
}

private val RAIN = Color(0xFF2E7BEA)
private val SHOWER = Color(0xFF5FB4F5)
private val SNOW = Color(0xFFF2F6FF)

private fun precipInk(m: MissionWxMap, i: Int): Color = when (precipKind(m, i)) {
    3 -> SNOW.copy(alpha = 0.6f)
    2 -> RAIN.copy(alpha = 0.55f)
    1 -> SHOWER.copy(alpha = 0.38f)
    else -> Color.Transparent
}

/** Visibility, red under 1.6 km (a mile), amber under 5, yellow under 8, a pale green beyond. */
private fun visInk(km: Double): Color = when {
    km < 1.6 -> Color(0xFFE53935).copy(alpha = 0.62f)
    km < 5 -> Color(0xFFFF9800).copy(alpha = 0.55f)
    km < 8 -> Color(0xFFFFEB3B).copy(alpha = 0.45f)
    km < 20 -> Color(0xFF8BC34A).copy(alpha = 0.3f)
    else -> Color(0xFF8BC34A).copy(alpha = 0.12f)
}

private fun DrawScope.drawMissionWeather(pr: MapProjection, m: MissionWxMap, image: ImageBitmap?, isobars: List<Isobar>?, text: TextMeasurer) {
    val cw = pr.side / m.cols
    val ch = pr.side / m.rows
    image?.let {
        drawImage(
            it, srcOffset = IntOffset.Zero, srcSize = IntSize(m.cols, m.rows),
            dstOffset = IntOffset(pr.left.roundToInt(), pr.top.roundToInt()), dstSize = IntSize(pr.side.roundToInt(), pr.side.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
    drawRect(Color.White.copy(alpha = 0.35f), Offset(pr.left, pr.top), Size(pr.side, pr.side), style = Stroke(1f))
    val ink = Color.White
    val halo = Color.Black
    if (MissionWxLook.isobars && isobars != null) {
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
    // figures and glyphs in as many cells as they can be read in, on a fixed lattice so they do not shimmer
    val fill = MissionWxLook.fill
    val glyphs = fill == WxFill.CLOUDS || fill == WxFill.VISIBILITY || fill == WxFill.TEMPERATURE
    val c0 = floor(-pr.left / cw).toInt().coerceAtLeast(0)
    val c1 = ceil((size.width - pr.left) / cw).toInt().coerceAtMost(m.cols - 1)
    val r0 = floor(-pr.top / ch).toInt().coerceAtLeast(0)
    val r1 = ceil((size.height - pr.top) / ch).toInt().coerceAtMost(m.rows - 1)
    if (glyphs) {
        // the fill already says it everywhere: the glyphs and figures are a sparse key over it, never a carpet
        val stride = ceil(56.dp.toPx() / cw).toInt().coerceAtLeast(1)
        val style = TextStyle(color = ink, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, shadow = Shadow(halo, blurRadius = 3f))
        val unit = (cw * stride).coerceAtMost(64f)
        for (r in (r0 - r0 % stride)..r1 step stride) for (c in (c0 - c0 % stride)..c1 step stride) {
            if (r < 0 || c < 0) continue
            val i = r * m.cols + c
            val centre = Offset(pr.left + (c + 0.5f) * cw, pr.top + (r + 0.5f) * ch)
            when (fill) {
                WxFill.CLOUDS -> {
                    val cover = WxCover.ofCode(m.cover[i])
                    if (cover == WxCover.NONE) continue
                    coverGlyph(centre, cover, m.towering.getOrNull(i) == 1, (unit * 0.2f).coerceIn(3f, 8f), ink, halo)
                    if (unit >= 44f) {
                        val t = text.measure(three(m.cloudBaseFt[i] / 100), style)
                        drawText(t, topLeft = centre + Offset(-t.size.width / 2f, unit * 0.22f + 2f))
                    }
                }
                WxFill.VISIBILITY -> {
                    val t = text.measure(visText(m.visKm[i].toDouble()), style)
                    drawText(t, topLeft = centre - Offset(t.size.width / 2f, t.size.height / 2f))
                }
                else -> {
                    val t = text.measure("${m.tempC[i].roundToInt()}°", style)
                    drawText(t, topLeft = centre - Offset(t.size.width / 2f, t.size.height / 2f))
                }
            }
        }
    }
    if (MissionWxLook.wind) {
        val stride = ceil(56.dp.toPx() / cw).toInt().coerceAtLeast(1)
        val unit = (cw * stride).coerceAtMost(64f)
        val level = MissionWxLook.level
        // offset half a stride from the figures, so a barb does not sit on a number
        val off = if (glyphs && stride > 1) stride / 2 else 0
        for (r in (r0 - r0 % stride + off)..r1 step stride) for (c in (c0 - c0 % stride + off)..c1 step stride) {
            if (r < 0 || c < 0 || r >= m.rows || c >= m.cols) continue
            val k = (r * m.cols + c) * MissionWxMap.LEVELS + level
            val centre = Offset(pr.left + (c + 0.5f) * cw, pr.top + (r + 0.5f) * ch)
            windBarb(centre, m.windDeg[k].toDouble(), m.windKt[k].toDouble(), unit * 0.46f, Color(0xFFFFE082), halo)
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

/** The layers, one line that scrolls sideways where it does not fit (a phone), so the map keeps its height. */
@Composable
private fun LayerChips() {
    Row(
        Modifier.fillMaxWidth().background(Hud.Surface.copy(alpha = 0.35f))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (f in listOf(WxFill.CLOUDS, WxFill.PRECIP, WxFill.VISIBILITY, WxFill.TEMPERATURE)) {
            EditorChip(f.label, selected = MissionWxLook.fill == f) { MissionWxLook.fill(f) }
        }
        EditorChip("Wind", selected = MissionWxLook.wind) { MissionWxLook.wind(!MissionWxLook.wind) }
        EditorChip("Pressure", selected = MissionWxLook.isobars) { MissionWxLook.isobars(!MissionWxLook.isobars) }
        if (MissionWxLook.wind) {
            Spacer(Modifier.width(4.dp))
            WxDefaults.WIND_ALTS.forEachIndexed { i, ft ->
                EditorChip(windAltLabel(ft), selected = MissionWxLook.level == i) { MissionWxLook.level(i) }
            }
        }
    }
}

/** "SFC", "FL060", "FL300", as a pilot names the levels the map carries. */
private fun windAltLabel(ft: Int): String = if (ft == 0) "SFC" else "FL" + (ft / 100).toString().padStart(3, '0')

/** The key for the fill and what the overlays show, over the map's top-left corner. */
@Composable
private fun MapLegend(m: MissionWxMap, modifier: Modifier) {
    val fill = MissionWxLook.fill
    Column(
        modifier.padding(10.dp).widthIn(max = 250.dp).clip(RoundedCornerShape(5.dp)).background(Hud.Bg.copy(alpha = 0.84f))
            .border(1.dp, Hud.Outline.copy(alpha = 0.7f), RoundedCornerShape(5.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (fill) {
            WxFill.CLOUDS -> {
                Swatches(listOf("FEW" to cloudInk(WxCover.FEW, false), "SCT" to cloudInk(WxCover.SCATTERED, false), "BKN" to cloudInk(WxCover.BROKEN, false), "OVC" to cloudInk(WxCover.OVERCAST, false)))
                LegendLine("Low cloud, base in 100s ft · ▲ towering")
            }
            WxFill.PRECIP -> {
                val any = m.type.indices.any { precipKind(m, it) > 0 }
                Swatches(listOf("Rain" to RAIN.copy(alpha = 0.55f), "Snow" to SNOW.copy(alpha = 0.6f)) + (if (m.shower.isNotEmpty()) listOf("Showers" to SHOWER.copy(alpha = 0.38f)) else emptyList()))
                LegendLine(if (any) "BMS rains in inclement weather, and snows at or below 0 °C" else "No precipitation anywhere on this map")
            }
            WxFill.VISIBILITY -> {
                Swatches(listOf("<1.6" to visInk(1.0), "<5" to visInk(3.0), "<8" to visInk(6.0), "8+" to visInk(10.0)))
                LegendLine("Visibility, km")
            }
            WxFill.TEMPERATURE -> {
                Box(Modifier.width(190.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(Brush.horizontalGradient((0..8).map { Color(WxColors.temperature(it * 5.0)) })))
                LegendLine("Temperature at the surface, °C (0 · 20 · 40)")
            }
            WxFill.NONE -> {}
        }
        if (MissionWxLook.wind) LegendLine("Wind at " + WxDefaults.WIND_ALTS[MissionWxLook.level].let { if (it == 0) "the surface" else group(it) + " ft" } + ", barbs into the wind")
        if (MissionWxLook.isobars) {
            val lo = m.pressureMb.minOrNull() ?: 0f
            val hi = m.pressureMb.maxOrNull() ?: 0f
            LegendLine(if (hi - lo < 0.9f) "Pressure ${lo.roundToInt()} mb everywhere: no isobars" else "Isobars, mb (${lo.roundToInt()}–${hi.roundToInt()})")
        }
    }
}

@Composable
private fun Swatches(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (name, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c.copy(alpha = maxOf(c.alpha, 0.5f))).border(0.5.dp, Hud.Outline, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(4.dp))
                Text(name, color = Hud.Text, fontSize = 10.5.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LegendLine(s: String) = Text(s, color = Hud.TextDim, fontSize = 10.5.sp, lineHeight = 13.sp)

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

private fun frameWords(f: MissionWxFrame?, next: MissionWxFrame?): String = when {
    f == null -> ""
    f.kind == "save" -> "the save's own map (${f.file})" + (next?.minute?.let { " until ${minuteText(it)}" } ?: "")
    else -> "update map ${f.file}" + (next?.minute?.let { " until ${minuteText(it)}" } ?: "")
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
    val cover = WxCover.ofCode(m.cover[i])
    val cloud = if (cover == WxCover.NONE) "SKC" else cover.metar + three(m.cloudBaseFt[i] / 100) + if (m.towering.getOrNull(i) == 1) " TCU" else ""
    val mb = m.pressureMb[i].toDouble()
    val inHg = WxModel.mbToInHg(mb)
    val k = i * MissionWxMap.LEVELS
    val precip = when (precipKind(m, i)) { 3 -> " SN"; 2 -> " RA"; 1 -> " SHRA"; else -> "" }
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

