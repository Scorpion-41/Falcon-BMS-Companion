package com.bmscompanion.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.WeatherState
import com.bmscompanion.app.data.mission.WxTheater
import com.bmscompanion.app.data.weather.CampaignTime
import com.bmscompanion.app.data.weather.WxCell
import com.bmscompanion.app.data.weather.WxColors
import com.bmscompanion.app.data.weather.WxDefaults
import com.bmscompanion.app.data.weather.WxGenParams
import com.bmscompanion.app.data.weather.WxGrid
import com.bmscompanion.app.data.weather.WxModel
import com.bmscompanion.app.data.weather.WxOverride
import com.bmscompanion.app.data.weather.WxType
import com.bmscompanion.app.ui.components.isMedium
import com.bmscompanion.app.ui.components.isWide
import com.bmscompanion.app.ui.theme.Hud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/*
 * **Generate**: WeatherGen's weather, on the app's own theater map.
 *
 * Craig Andera's WeatherGen (ported in `data/weather`, MIT) makes a whole theater's weather out of a handful of
 * numbers: a pattern of highs and lows that drifts and evolves with time, the four BMS types laid over it by pressure,
 * wind that runs round the highs and lows, and cloud, visibility and temperature that change across a front rather
 * than in patches. This page is that generator's controls, in WeatherGen's own order, beside the map it makes.
 *
 * The page never ships a grid anywhere. It runs the generator locally for the preview — 3,481 cells, a few
 * milliseconds — and a save sends only the parameters, from which the PC builds the same weather into the file.
 * So the preview works with no PC at all, and only saving needs one.
 */

/** What colours the cells. WeatherGen's "Display". */
internal enum class WxDisplay(val label: String) { NONE("None"), TYPE("Type"), PRESSURE("Pressure"), TEMPERATURE("Temp") }

/** What is written or drawn in each cell. WeatherGen's "Overlay". */
internal enum class WxOverlay(val label: String) {
    NONE("None"), WIND("Wind"), COVER("Cloud cover"), BASE("Cloud base"), VISIBILITY("Visibility"),
    PRESSURE("Pressure"), TEMPERATURE("Temperature"),
}

/**
 * How the generated map is drawn, remembered across launches. The defaults are WeatherGen's: coloured by type at a
 * third opacity, nothing written in the cells, pressure in inHg.
 */
internal object WxGenLook {
    var display by mutableStateOf(WxDisplay.entries.getOrNull(Repo.getInt("wx_gen_display", 1)) ?: WxDisplay.TYPE)
        private set
    var overlay by mutableStateOf(WxOverlay.entries.getOrNull(Repo.getInt("wx_gen_overlay", 0)) ?: WxOverlay.NONE)
        private set
    /** which of the ten wind levels the barbs show, 0 being the surface */
    var windLevel by mutableIntStateOf(Repo.getInt("wx_gen_wind_level", 0).coerceIn(0, 9))
        private set
    var opacity by mutableFloatStateOf(Repo.getInt("wx_gen_opacity", 33).coerceIn(0, 100) / 100f)
        private set
    var mb by mutableStateOf(Repo.getInt("wx_gen_mb", 0) == 1)
        private set
    var grid by mutableStateOf(Repo.getInt("wx_gen_grid", 0) == 1)
        private set
    /** the light chart style under the weather, which is how BMS's own 2D planning map looks */
    var bms by mutableStateOf(Repo.getInt("wx_bms_map", 1) == 1)
        private set

    fun display(v: WxDisplay) { display = v; Repo.putInt("wx_gen_display", v.ordinal) }
    fun overlay(v: WxOverlay) { overlay = v; Repo.putInt("wx_gen_overlay", v.ordinal) }
    fun windLevel(v: Int) { windLevel = v.coerceIn(0, 9); Repo.putInt("wx_gen_wind_level", windLevel) }
    fun opacity(v: Float) { opacity = v.coerceIn(0f, 1f); Repo.putInt("wx_gen_opacity", (opacity * 100).roundToInt()) }
    fun mb(v: Boolean) { mb = v; Repo.putInt("wx_gen_mb", if (v) 1 else 0) }
    fun grid(v: Boolean) { grid = v; Repo.putInt("wx_gen_grid", if (v) 1 else 0) }
    fun bms(v: Boolean) { bms = v; Repo.putInt("wx_bms_map", if (v) 1 else 0) }
}

/**
 * Where the settings a theater opened with came from, for the line under the theater picker: a pilot who switches
 * theater should see that the weather switched with it, and why it is the weather it is.
 */
internal sealed class WxOrigin {
    /** this device's own settings for this theater, kept as they were last changed */
    object Own : WxOrigin()
    /** what the PC saved for this theater: the settings the named generated map was made from */
    data class Pc(val map: String) : WxOrigin()
    /** nothing kept for this theater anywhere yet: WeatherGen's defaults on a seed of the theater's own */
    data class Fresh(val seed: Int) : WxOrigin()
}

/**
 * The settings a generated map was made from, as the PC keeps them beside the map (`WeatherStore`) and hands them
 * back (`GET /api/weather/params`): [name] is the map's file name without `.fmap` ("BMSC Frontal day").
 */
@Serializable
internal data class WxGenSaved(val name: String = "", val params: WxGenParams = WxGenParams())

/**
 * Everything the Generate page is working on for **one theater**: the parameters (kept in the prefs under the
 * theater's own key, so each theater keeps its own weather across a restart and a switch back and forth), the grid
 * they make, and what is picked on the map.
 *
 * One set of parameters for every theater is what made the weather look stuck: WeatherGen's grid is the same 59 x 59
 * whatever it is laid over, so switching theater laid the very same weather over the new map. [key] is the theater
 * the settings belong to; null (a headless render, a preview with no theater) keeps nothing.
 *
 * What is picked is a **selection**, not one thing: any number of override regions ([regions]) and of cells
 * ([cells]). Shift- or Ctrl-click adds to it or takes away (on a touch screen, [multi] makes every tap do that); a
 * plain click picks one. The panel then edits every selected region at once.
 */
@Stable
internal class WxGenState(initial: WxGenParams = WxGenParams(), val key: String? = null) {
    var params by mutableStateOf(initial)
    var grid by mutableStateOf<WxGrid?>(null)
    /** where [params] came from when the theater was opened; [WxOrigin.Own] once the pilot changes anything */
    var origin by mutableStateOf<WxOrigin>(WxOrigin.Own)
    /** the cell the inspector shows, x east and y south: the last one picked */
    var selected by mutableStateOf<Pair<Int, Int>?>(null)
    /** every picked cell, [selected] among them; more than one and the inspector sums them up */
    var cells by mutableStateOf<Set<Pair<Int, Int>>>(emptySet())
    /** the cell under the mouse, on the PC and in a browser */
    var hover by mutableStateOf<Pair<Int, Int>?>(null)
    /** the override regions picked, by index: drawn lit, moved together, and edited together in the panel */
    var regions by mutableStateOf<Set<Int>>(emptySet())
    /** a touch screen's Shift key: every tap adds to the selection or takes away, instead of picking one */
    var multi by mutableStateOf(false)
    /** a tap drops an override rather than selecting a cell */
    var placing by mutableStateOf(false)
    var animating by mutableStateOf(false)
    /** the airfield whose forecast the inspector shows; null for the one nearest the selected cell */
    var airport by mutableStateOf<Airport?>(null)

    /**
     * The one region whose handles are live on the map (WeatherGen's `editing?` latch): the region picked, when
     * exactly one is. Setting it picks that region alone, or nothing.
     */
    var editing: Int?
        get() = regions.singleOrNull()
        set(v) { regions = setOfNotNull(v) }

    fun edit(f: (WxGenParams) -> WxGenParams) {
        val next = f(params)
        if (next != params) origin = WxOrigin.Own
        params = next
    }

    fun editOverride(i: Int, f: (WxOverride) -> WxOverride) = edit { p ->
        if (i !in p.overrides.indices) p else p.copy(overrides = p.overrides.mapIndexed { k, o -> if (k == i) f(o) else o })
    }

    /** One change made to every region in [which] at once: how the panel edits a selection. */
    fun editOverrides(which: Collection<Int>, f: (WxOverride) -> WxOverride) = edit { p ->
        p.copy(overrides = p.overrides.mapIndexed { k, o -> if (k in which) f(o) else o })
    }

    /** WeatherGen's "Add New": an inclement storm at every wind level, fading in over the next hour, outlined, being edited. */
    fun addOverride(x: Double, y: Double): Int {
        val now = params.current
        val o = WxOverride(
            x = x, y = y, radius = 8.0, falloff = 2.0, strength = 1.0, type = WxType.INCLEMENT,
            windAlts = WxDefaults.WIND_ALTS,
            begin = now, peak = now.plusMinutes(60.0), taper = now.plusMinutes(180.0), end = now.plusMinutes(240.0),
            showOutline = true,
        )
        edit { it.copy(overrides = it.overrides + o) }
        editing = params.overrides.size - 1
        return params.overrides.size - 1
    }

    /**
     * A region laid over the picked cells, so that several cells can be given one weather at once: centred on them,
     * just big enough to take them all in, of the type most of them already are, and picked so the panel edits it.
     */
    fun coverCells(): Int? {
        val cs = cells.takeIf { it.isNotEmpty() } ?: return null
        val cx = cs.sumOf { it.first.toDouble() } / cs.size
        val cy = cs.sumOf { it.second.toDouble() } / cs.size
        val reach = cs.maxOf { (x, y) -> kotlin.math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) }
        val g = grid
        val type = g?.let { gr -> cs.mapNotNull { gr.cell(it)?.type }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key }
        val radius = (reach + 1.5).coerceIn(1.5, 60.0)
        val o = WxOverride(
            x = (cx * 10).roundToInt() / 10.0, y = (cy * 10).roundToInt() / 10.0,
            radius = (radius * 10).roundToInt() / 10.0, falloff = ((radius - 1).coerceAtLeast(0.5) * 10).roundToInt() / 10.0,
            strength = 1.0, type = type ?: WxType.FAIR, showOutline = true,
        )
        edit { it.copy(overrides = it.overrides + o) }
        cells = emptySet()
        editing = params.overrides.size - 1
        return params.overrides.size - 1
    }

    fun removeOverride(i: Int) = removeOverrides(setOf(i))

    /** Takes the regions out and renumbers what stays picked, so the selection still names the same regions. */
    fun removeOverrides(which: Set<Int>) {
        if (which.isEmpty()) return
        edit { p -> p.copy(overrides = p.overrides.filterIndexed { k, _ -> k !in which }) }
        regions = regions.filter { it !in which }.map { r -> r - which.count { it < r } }.toSet()
    }

    /**
     * A click on the map. [additive] is Shift, Ctrl or the touch screen's Select several: it adds what was clicked to
     * the selection, or takes it away if it was in it already; a plain click picks that one thing alone. A [region]
     * wins over the [cell] under it.
     */
    fun click(region: Int?, cell: Pair<Int, Int>?, additive: Boolean) {
        if (region != null) {
            regions = if (!additive) setOf(region) else if (region in regions) regions - region else regions + region
            return
        }
        if (cell == null) return
        if (!additive) {
            regions = emptySet()
            cells = setOf(cell)
            selected = cell
        } else if (cell in cells) {
            cells = cells - cell
            if (selected == cell) selected = cells.lastOrNull()
        } else {
            cells = cells + cell
            selected = cell
        }
        airport = null
    }

    /** Nothing picked. */
    fun clearSelection() {
        regions = emptySet()
        cells = emptySet()
        selected = null
        airport = null
    }

    /** Kept for this theater a moment after the last change. Nothing is kept for a state that has no theater. */
    fun save() {
        val k = key ?: return
        Repo.putString(keyFor(k), Repo.json.encodeToString(WxGenParams.serializer(), params))
    }

    companion object {
        /** The key every theater shared before the settings were kept per theater (1.3.8's first builds). */
        const val KEY = "wx_gen_params"

        fun keyFor(theater: String) = "$KEY@$theater"

        private fun decode(s: String?): WxGenParams? =
            s?.let { runCatching { Repo.json.decodeFromString(WxGenParams.serializer(), it) }.getOrNull() }

        /** A seed of the theater's own, the same every time for the same theater: 1 to 9999. */
        fun seedFor(theater: String): Int {
            var h = 7L
            for (c in theater.lowercase()) h = (h * 31 + c.code) % 1_000_003L
            return (h % 9999).toInt() + 1
        }

        /**
         * The settings a theater opens with: this device's own for it ([key], then [alsoKey] — the same theater as
         * the app's bundled map names it, for settings made with no PC connected); else, once, the settings every
         * theater shared before they were kept apart (the first theater opened takes them over); else WeatherGen's
         * defaults on the theater's own seed, so no two theaters open with the same weather.
         */
        fun open(key: String?, alsoKey: String? = null): WxGenState {
            if (key == null) return WxGenState()
            val own = decode(Repo.getString(keyFor(key))) ?: alsoKey?.takeIf { it != key }?.let { decode(Repo.getString(keyFor(it))) }
            if (own != null) return WxGenState(own, key).also { it.origin = WxOrigin.Own }
            decode(Repo.getString(KEY))?.let { shared ->
                Repo.putString(KEY, null)
                return WxGenState(shared, key).also { it.origin = WxOrigin.Own; it.save() }
            }
            val seed = seedFor(key)
            return WxGenState(WxGenParams(seed = seed.toDouble()), key).also { it.origin = WxOrigin.Fresh(seed) }
        }
    }
}

/** The weather a cell of the grid shows, or null off the grid. */
internal fun WxGrid.cell(xy: Pair<Int, Int>?): WxCell? =
    xy?.takeIf { (x, y) -> x in 0 until cols && y in 0 until rows }?.let { (x, y) -> at(x, y) }

/** The cell a theater position falls in: x east from the west edge, y south from the north edge. */
internal fun cellOf(xFt: Double, yFt: Double, sizeFt: Double, cols: Int, rows: Int): Pair<Int, Int>? {
    val c = (yFt / sizeFt * cols).toInt()
    val r = ((1 - xFt / sizeFt) * rows).toInt()
    return if (c in 0 until cols && r in 0 until rows) c to r else null
}

/** The type's ink, WeatherGen's colours but with sunny opaque, for a swatch or a word rather than a wash. */
internal fun typeSwatch(t: WxType): Color = when (t) {
    WxType.SUNNY -> Color(0xFFE8EEF2)
    else -> Color(WxColors.type(t))
}

/**
 * The Generate page. [th] is the bundled theater drawn under the weather; [wx] is the same theater as the PC knows
 * it, which is what a save goes to, and is null with no PC. [noSave] is why there is nowhere to save when the PC is
 * connected but has no theater to save to; the Save panel shows it in place of its buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeatherGenBody(
    th: Theater?,
    wx: WxTheater?,
    online: Boolean,
    busy: Boolean,
    error: String?,
    onBusy: (Boolean) -> Unit,
    onState: (WeatherState?) -> Unit,
    reload: () -> Unit,
    st: WxGenState = remember { WxGenState() },
    noSave: String? = null,
) {
    val params = st.params

    // The preview, rebuilt whenever a setting changes — or the theater, which brings a state of its own and may well
    // bring the same settings (two untouched theaters), so the state is part of the key. A slider sends a stream of
    // values, so each waits a moment for the next and only the last is built: 3,481 cells take milliseconds, but not
    // on every pixel of a drag.
    LaunchedEffect(st, params) {
        if (st.grid != null) delay(70)
        st.grid = withContext(Dispatchers.Default) { WxModel.grid(params) }
    }
    // kept in the prefs a moment after the last change, not on every one; only once the pilot has changed something,
    // so opening a theater and leaving it keeps nothing
    LaunchedEffect(st, params) {
        delay(800)
        if (st.origin == WxOrigin.Own) st.save()
    }
    // WeatherGen's Animate: a step forward every so often, until stopped
    LaunchedEffect(st, st.animating) {
        while (st.animating) {
            delay(700)
            st.edit { WxModel.step(it, 1) }
        }
    }

    val airports by produceState(emptyList<Airport>(), th?.airportSet) {
        value = th?.airportSet?.takeIf { it.isNotEmpty() }?.let { Repo.airportSet(it).airports }.orEmpty()
    }

    // Everything below remembers things about the weather it shows — the map's pan and zoom, which fold of the
    // override list is open, the Save panel's times — so a theater switch starts all of it afresh with the new state.
    key(st) { GenLayout(th, wx, st, airports, online, busy, error, onBusy, onState, reload, noSave) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenLayout(
    th: Theater?,
    wx: WxTheater?,
    st: WxGenState,
    airports: List<Airport>,
    online: Boolean,
    busy: Boolean,
    error: String?,
    onBusy: (Boolean) -> Unit,
    onState: (WeatherState?) -> Unit,
    reload: () -> Unit,
    noSave: String?,
) {
    Column(Modifier.fillMaxSize()) {
        TimeBar(st)
        Rule(0.5f)
        val wide = isWide()
        val medium = isMedium()
        val map: @Composable () -> Unit = {
            if (th != null) WxGenMap(th, st, airports) else Middle("No map for this theater", "The weather can still be set and saved.")
        }
        val panels: @Composable () -> Unit = {
            WxGenPanels(th, wx, st, airports, online, busy, error, onBusy, onState, reload, noSave)
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { map() }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Hud.Outline.copy(alpha = 0.5f)))
                Column(
                    Modifier.width(430.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                ) { panels() }
            }
        } else {
            // A phone scrolls the whole page, map first; the map keeps its own pan and zoom inside its box.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(if (medium) 520.dp else 360.dp)) { map() }
                Rule()
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) { panels() }
            }
        }
    }
}

/**
 * The clock: where in the campaign the map is, a step either way, and Animate. Stepping **moves** the weather
 * (WeatherGen's `step`: later in time, and the pattern carried along its heading) — it is how a pilot sees what
 * the take-off weather turns into by the time they land.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeBar(st: WxGenState) {
    val p = st.params
    FlowRow(
        Modifier.fillMaxWidth().background(Hud.Surface.copy(alpha = 0.35f)).padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            p.current.label, Modifier.align(Alignment.CenterVertically),
            color = Hud.Amber, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(2.dp))
        EditorChip("◀ Step") { st.animating = false; st.edit { WxModel.step(it, -1) } }
        EditorChip("Step ▶") { st.animating = false; st.edit { WxModel.step(it, 1) } }
        EditorChip(if (st.animating) "■ Stop" else "▶ Animate", selected = st.animating) { st.animating = !st.animating }
        Text(
            "every ${p.movement.stepMin.roundToInt()} min · moving ${p.movement.headingDeg.roundToInt()}° at ${p.movement.speedKt.roundToInt()} kt",
            Modifier.align(Alignment.CenterVertically),
            color = Hud.TextFaint, fontSize = 11.sp, maxLines = 1,
        )
    }
}

/** "Day 1 05:00" as its parts, for the steppers. */
internal fun CampaignTime.withDay(d: Int) = copy(day = d.coerceIn(1, 99))
internal fun CampaignTime.withHour(h: Int) = copy(hour = (h + 24) % 24)
internal fun CampaignTime.withMinute(m: Int) = copy(minute = (m + 60) % 60)
