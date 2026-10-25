package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionWeather
import com.bmscompanion.app.data.mission.MissionWxFrame
import com.bmscompanion.app.data.mission.MissionWxMap
import com.bmscompanion.app.ui.components.MapLook
import com.bmscompanion.app.ui.components.drawLandmarks
import com.bmscompanion.app.ui.screens.editor.MissionWxLook
import com.bmscompanion.app.ui.screens.editor.MissionWxShare
import com.bmscompanion.app.ui.screens.editor.WxPicture
import com.bmscompanion.app.ui.screens.editor.WxRoutePt
import com.bmscompanion.app.ui.screens.editor.clockText
import kotlinx.coroutines.CancellationException

/*
 * Upd Kneeboard's **Weather map** (1.4.2, `KbKind.WEATHER_MAP`): the mission's weather over the route as Mission →
 * Weather → Mission weather shows it — the same drawing (`MissionWxShare`, lent by that page), with the pilot's choices
 * there (the fill, the barbs and their level, the isobars, the smoothing: `MissionWxLook`) and the map shown there (or,
 * before it was opened, the one the PC starts on), over the pilot's own map style ([MapLook]). Its key, unfolded, sits
 * on the map; the figures at the take-off, the target and the landing go under it. Read from the same routes the page
 * reads (`/api/weather/mission`, `/map?frame=`), in both modes.
 */

/** The mission's weather as the Weather map prints it, read from the PC ahead of the capture ([preload]). */
internal object KneeboardWeather {
    var weather by mutableStateOf<MissionWeather?>(null)
        private set
    /** why there is no weather to print (no PC, the PC's own sentence); null while it is being read or once it is in */
    var why by mutableStateOf<String?>(null)
        private set
    /** the maps read, by frame id */
    val maps = mutableStateMapOf<String, MissionWxMap>()
    /** a map that could not be read, by frame id: the PC's sentence */
    val mapErrors = mutableStateMapOf<String, String>()

    /**
     * The query the weather was read for, Target pictures' own ([KneeboardTargets.query]): in WDP mode the save and
     * flight open in the Planner, populated or not; else `printed=1`, the populated mission and then the printed
     * briefing (1.4.1: the page said "nothing is populated" until Populate).
     */
    private var readFor: String? = null

    /** The map printed: the one Mission weather last showed, else the one the PC starts on. */
    fun frameOf(w: MissionWeather?): MissionWxFrame? {
        val frames = w?.frames ?: return null
        return frames.firstOrNull { it.id == MissionWxShare.shownFrame } ?: frames.getOrNull(w.start) ?: frames.firstOrNull()
    }

    /** Reads the mission's weather, the map to print and the map pictures under it. Never throws. */
    suspend fun preload() {
        read()
        try {
            val d = planData()
            val f = routeFrame(d, WX_W_PX, WX_H_PX, MapLook.style, WX_MIN_SPAN_NM) ?: d.mission.theater?.let { theaterFrame(it, WX_W_PX, WX_H_PX, MapLook.style) }
            f?.let { KneeboardExtraPages.loadTiles(it.all) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { /* drawn with what there is */ }
    }

    /** The page's own read: the weather and the map, the pictures being the page's to load. */
    suspend fun refresh() = read()

    private suspend fun read() {
        try {
            if (MissionLink.state.value !is LinkState.Online) {
                why = "Connect to the PC that runs Falcon BMS (Setup) to print the mission's weather."
                return
            }
            val q = KneeboardTargets.query()
            // another flight: the maps read for the last one are not this one's (their ids, "save", repeat)
            if (q != readFor) { maps.clear(); mapErrors.clear(); readFor = q }
            val r = MissionLink.missionWeather(q)
            val w = r.value
            weather = w
            why = if (w == null) r.error ?: "The PC did not answer." else null
            val f = frameOf(w) ?: return
            if (maps[f.id] != null) return
            val m = MissionLink.missionWeatherMap(f.id, q)
            val got = m.value
            if (got != null && got.whole) maps[f.id] = got else mapErrors[f.id] = m.error ?: "The map came back incomplete."
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            why = "The mission's weather could not be read (${e.message ?: e::class.simpleName})."
        }
    }
}

/** The map box as a page is drawn (its pixel size), which the tiles are chosen for ahead of time. */
private const val WX_W_PX = 969
private const val WX_H_PX = 1180

/** The weather wants more room round the route than the route map: the cells are some 9 nm across. */
private const val WX_MIN_SPAN_NM = 140.0

@Composable
internal fun WeatherMapPage(scope: KbPageScope) {
    val d = remember(KneeboardEzSource.mission) { planData() }
    val theater = d.mission.theater
    val style = MapLook.style
    LaunchedEffect(theater, style) { KneeboardExtraPages.mapExtras(theater, style) }
    var read by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { KneeboardWeather.refresh(); read = true }
    if (!read) KneeboardPages.PageLoading()
    val w = KneeboardWeather.weather
    val frame = KneeboardWeather.frameOf(w)
    val map = frame?.let { KneeboardWeather.maps[it.id] }
    val view = MissionWxLook.view()
    val layers = remember(map, view) { map?.let { MissionWxShare.layers(it) } }
    // dark, as the page is: the weather's figures and barbs are white over its fills
    val inks = RouteInks.NIGHT.on(style)
    val sub = if (w == null || !w.ok) null else MissionWxShare.source(w) + (frame?.minute?.let { " · map of ${MissionWxShare.minute(it)}" } ?: "")
    KbSheet(scope, "WEATHER MAP", sub, inks) {
        if (w == null || !w.ok) {
            KbNote(w?.why ?: KneeboardWeather.why ?: "Reading the mission's weather on the PC…", Modifier.fillMaxWidth().weight(1f))
            KneeboardExtraPages.drawn[KbKind.WEATHER_MAP] = "no weather: " + (w?.why ?: KneeboardWeather.why ?: "not read")
            return@KbSheet
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).border(1.dp, inks.ink)) {
            val f = remember(d, style, constraints.maxWidth, constraints.maxHeight) {
                routeFrame(d, constraints.maxWidth, constraints.maxHeight, style, WX_MIN_SPAN_NM)
                    ?: theater?.let { theaterFrame(it, constraints.maxWidth, constraints.maxHeight, style) }
            }
            if (f == null) {
                KbNote("Falcon BMS's theater is not known to this copy of the app, so there is no map to draw.", Modifier.align(Alignment.Center))
                KneeboardExtraPages.drawn[KbKind.WEATHER_MAP] = "no map"
                return@BoxWithConstraints
            }
            var busy by remember(f.all) { mutableStateOf(f.all.any { it !in KneeboardExtraPages.images }) }
            LaunchedEffect(f.all) { KneeboardExtraPages.loadTiles(f.all); busy = false }
            if (busy) KneeboardPages.PageLoading()
            val tm = rememberTextMeasurer(cacheSize = 256)
            val geo = f.mapId?.let { KneeboardExtraPages.geo[it] }
            val picture = remember(d) { pictureOf(d) }
            Canvas(Modifier.fillMaxSize().clipToBounds()) {
                drawRect(inks.map)
                drawFrameMap(f)
                geo?.let { drawLandmarks(f.projection, it, tm, mission = true, styleOverride = f.style) }
                MissionWxShare.draw(this, f.projection, w, map, layers, picture, tm)
            }
            // the key cannot be folded on paper: it goes in the corner that hides the fewest of the plan's points
            // (top left first), so it never sits over the target the way a fixed corner did
            val keyW = with(androidx.compose.ui.platform.LocalDensity.current) { 270.dp.toPx() }
            val keyH = with(androidx.compose.ui.platform.LocalDensity.current) { 190.dp.toPx() }
            val keyAt = remember(f, picture, keyW, keyH) {
                val w0 = constraints.maxWidth.toFloat()
                val h0 = constraints.maxHeight.toFloat()
                val pts = picture.route.map { f.projection.toScreen(it.x, it.y) } +
                    picture.fields.map { (a, _) -> f.projection.toScreen(a.x, a.y) }
                listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd).minByOrNull { a ->
                    val left = if (a == Alignment.TopStart || a == Alignment.BottomStart) 0f else w0 - keyW
                    val top = if (a == Alignment.TopStart || a == Alignment.TopEnd) 0f else h0 - keyH
                    pts.count { it.x in left..(left + keyW) && it.y in top..(top + keyH) }
                } ?: Alignment.TopStart
            }
            if (map != null) MissionWxShare.Legend(map, Modifier.align(keyAt))
            else {
                val note = MissionWxShare.uniform(w)?.let { "${it.label} over the whole theater" }
                    ?: frame?.let { KneeboardWeather.mapErrors[it.id] } ?: "The map could not be read."
                Text(
                    note, Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(4.dp)).background(inks.page.copy(alpha = 0.86f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    color = inks.ink, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            val have = f.keys.count { it in KneeboardExtraPages.images }
            KneeboardExtraPages.drawn[KbKind.WEATHER_MAP] =
                "model ${w.model}, fill ${view.fill.name}, " + (if (map != null) "map ${frame?.file} drawn" else "no map") +
                    ", ${w.places.size} places; map ${f.mapId}/${f.style} z${f.z}: $have of ${f.keys.size} pictures"
        }
        Places(w, map, theater?.sizeFt, inks)
    }
}

/** The figures at the take-off, the target and the landing on the map printed, as the page's panel words them. */
@Composable
private fun Places(w: MissionWeather, map: MissionWxMap?, sizeFt: Double?, inks: RouteInks) {
    if (map == null || sizeFt == null) {
        if (MissionWxShare.uniform(w) != null) Column(Modifier.fillMaxWidth().padding(top = 4.dp)) { MissionWxShare.Types(w) }
        return
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        for (p in w.places) {
            val cell = MissionWxShare.cell(map, p.x, p.y, sizeFt) ?: continue
            val at = if (p.ms > 0) " · ${clockText(p.ms)}" else ""
            Text(
                MissionWxShare.place(p.label) + (p.name?.let { " $it" } ?: "") + at,
                color = inks.route, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp, maxLines = 1,
            )
            Text(cell, color = inks.ink, fontSize = 7.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 9.5.sp, maxLines = 2)
        }
    }
}

/** The plan as the weather page draws it over the weather: the flight plan and the card's airfields. */
private fun pictureOf(d: KbPlanData): WxPicture {
    val fields = (0..2).map { KneeboardExtraPages.cardAirport(it) }
    val dep = fields[0]
    val arr = fields[1]
    return WxPicture(
        route = (d.route + d.stptTargets).sortedBy { it.n }.map { p ->
            WxRoutePt(p.north, p.east, p.n, target = p.n in d.targetStpts || p in d.stptTargets, land = false, refuel = false, onRoute = p in d.route)
        },
        fields = listOfNotNull(
            dep?.let { it to (if (arr != null && it.id == arr.id) "HOME" else "DEP") },
            arr?.takeIf { dep == null || it.id != dep.id }?.let { it to "ARR" },
            fields[2]?.let { it to "ALT" },
        ),
    )
}
