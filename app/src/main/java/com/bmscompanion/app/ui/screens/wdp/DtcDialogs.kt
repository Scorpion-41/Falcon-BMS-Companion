package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.drawMapBase
import com.bmscompanion.app.ui.components.rememberMapBase
import kotlin.math.abs
import kotlin.math.hypot
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.ChartRef
import com.bmscompanion.app.data.airfield.AfRoute
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.ui.components.AirfieldChart
import com.bmscompanion.app.ui.components.ChartInks
import com.bmscompanion.app.data.wdp.DataCardPlan
import com.bmscompanion.app.data.wdp.DtcLoad
import com.bmscompanion.app.data.wdp.DtcPage
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.data.wdp.WdpValues

/**
 * The DTC page's child windows — Change STPT, Change PPT, the frequency box, the airport list and the rest — each
 * WDP's own form, drawn from its own layout, with a small wiring of its own.
 *
 * They are written for what each window is for rather than line by line: a Change window shows the point, lets
 * the pilot type a new one, checks it is inside the theater and hands it back to the page on Apply, exactly as
 * WDP's do. Where WDP's window reads its own database (the target list off the campaign's objectives, the airport
 * list), the window here reads the app's: the mission's targets and steerpoints, the theater's airbases.
 */
internal abstract class DtcWindow(private val form: String, private val title: String) : WdpWiring {
    private var version by mutableIntStateOf(0)
    private var window: WdpDialog? = null

    /** What the pilot has typed into a field or picked in a list, by control. */
    protected val typed = LinkedHashMap<String, String>()

    fun open() {
        val d = WdpDialog(form, title, this, hidden = hiddenControls(), onDismiss = { cancelled() })
        window = d
        WdpDialogs.show(d)
    }

    private var closed = false

    protected fun close() { closed = true; window?.let { WdpDialogs.close(it) } }
    protected fun refresh() { version++ }
    protected open fun hiddenControls(): List<String> = emptyList()
    protected open fun cancelled() {}
    protected abstract fun fill(v: MutableMap<String, String>)
    protected abstract fun pressed(name: String)
    protected open fun left(name: String) {}
    protected open fun picked(name: String, value: String) { typed[name] = value }

    override fun values(hidden: List<String>): WdpValues {
        @Suppress("UNUSED_VARIABLE") val v = version
        val m = LinkedHashMap<String, String>()
        fill(m)
        m.putAll(typed)
        // what the window hides wins over what it holds (a feet-only position has no N/S to pick)
        for (h in hidden) m[h] = "hidden"
        // whether the window is still up: no control of the form is called this, and a check can see OK and
        // Cancel close it
        m["window"] = if (closed) "closed" else "open"
        return WdpValues(m)
    }

    override fun onValue(name: String, value: String) = guard {
        if (name.endsWith(".leave")) left(name.removeSuffix(".leave")) else picked(name, value)
    }

    override fun onClick(name: String) = guard { pressed(name) }

    /** Nothing may throw out of a window: the reason is shown the way WDP shows its error boxes. */
    private fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            WdpDialogs.message("Error", "An error has occurred: " + (e.message ?: e::class.simpleName ?: "unknown"))
        }
        version++
    }
}

/**
 * Coordinates as the page prints and reads them: WDP's latitude and longitude ("37,24.123" / "127,05.456") where the
 * theater's projection is known, the sim's own feet ("1234567'") where it is not.
 */
internal class DtcCoords(val data: PopupCoords.CoordData?) {
    val latLon: Boolean get() = data != null && data.enableNewTerrain

    fun label(northFt: Float, eastFt: Float): Pair<String, String> =
        if (latLon) DtcLoad.northEast(data!!, northFt, eastFt) else DtcPage.feetLabels(northFt, eastFt)

    /**
     * What the pilot typed, back to feet (north, east), or null when it is not a position inside the theater.
     * The empty mark ("00,00.000", or nothing) is a point cleared, at 0, 0, as in WDP.
     */
    fun feet(north: String, east: String, south: Boolean = false, west: Boolean = false): Pair<Float, Float>? {
        val n = north.trim()
        val e = east.trim()
        fun empty(s: String) = s.isEmpty() || s.all { it == '0' || it == ',' || it == '.' || it == '\'' || it == ' ' || it == '_' }
        if (empty(n) && empty(e)) return 0f to 0f
        if (!latLon) {
            val fn = n.trimEnd('\'').removeSuffix("ft").trim().toDoubleOrNull() ?: return null
            val fe = e.trimEnd('\'').removeSuffix("ft").trim().toDoubleOrNull() ?: return null
            if (fn < 0 || fe < 0) return null
            return fn.toFloat() to fe.toFloat()
        }
        var lat = degrees(n) ?: return null
        var lon = degrees(e) ?: return null
        if (south && lat > 0) lat = -lat
        if (west && lon > 0) lon = -lon
        val (simEast, simNorth) = PopupCoords.convertLatLonToSimXY(data!!.tm, lat.toFloat(), lon.toFloat())
        val size = if (data.campW > 0) data.campW else Double.MAX_VALUE
        if (simNorth < 0f || simEast < 0f || simNorth > size || simEast > size) return null
        return simNorth to simEast
    }

    /** "37,24.123" (degrees, minutes — the page's own form, whole degrees by floor) or "37.402" (decimal degrees). */
    private fun degrees(s: String): Double? {
        val t = s.replace('_', '0').replace(" ", "")
        if (!t.contains(',')) return t.toDoubleOrNull()
        val deg = t.substringBefore(',').toIntOrNull() ?: return null
        val min = t.substringAfter(',').toDoubleOrNull() ?: return null
        if (min < 0 || min >= 60) return null
        return deg + min / 60.0
    }
}

/** WDP's `fclsInfo`: the "?" buttons' explanation, and Close. */
internal class DtcInfoWindow(private val text: String) : DtcWindow("fclsInfo", "Info") {
    override fun fill(v: MutableMap<String, String>) { v["txtInfo"] = text }
    override fun pressed(name: String) { if (name == "btnClose") close() }
}

/** The actions a steerpoint can carry, as the Change STPT window lists them (`L.ActionString`), sorted. */
internal object DtcActions {
    val names: List<String> = (listOf("", "Precision") + (0..26).map { DataCardPlan.actionString(it) }).distinct().sorted()
    fun toInt(s: String?): Int = when {
        s.isNullOrEmpty() -> 0
        else -> (-1..30).firstOrNull { DataCardPlan.actionString(it) == s } ?: 0
    }
}

/**
 * WDP's `fclsChangeSTPT`, `fclsChangeLineSTPT` and `fclsChangeTgt`: one point, typed in. Apply checks the position
 * is inside the theater (as `CheckCoords` does) and hands north, east, elevation and — for a steerpoint — the action
 * back to the page; a steerpoint that is not a precision one gets WDP's question first.
 */
internal class DtcPointWindow(
    form: String,
    title: String,
    private val numberLabel: String,
    private val numberText: String,
    private val north: String,
    private val east: String,
    private val elev: String,
    private val action: String?,
    private val coords: DtcCoords,
    /**
     * The point as the page holds it (north, east, elevation in feet). A position or an elevation Apply finds as the
     * window showed it hands these back unchanged, so opening a point and pressing Apply does not move it by the
     * rounding of its label (a thousandth of a minute is up to 6 ft) nor round its elevation to the foot.
     */
    private val origin: Triple<Float, Float, Float>? = null,
    private val onApply: (northFt: Float, eastFt: Float, elevFt: Float, action: String?) -> Unit,
) : DtcWindow(form, title) {
    private val hasAction = form == "fclsChangeSTPT"

    init {
        typed["mxtSTPT_N"] = north.removePrefix("-")
        typed["mxtSTPT_E"] = east.removePrefix("-")
        typed["mxtSTPT_elv"] = elev
        typed["cboSTPT_NS"] = if (north.startsWith("-")) "S" else "N"
        typed["cboSTPT_EW"] = if (east.startsWith("-")) "W" else "E"
        if (hasAction) typed["cboAction"] = action ?: ""
    }


    override fun fill(v: MutableMap<String, String>) {
        v[numberLabel] = numberText
        // a position in feet has no hemisphere to pick
        if (!coords.latLon) { v["cboSTPT_NS.enabled"] = "false"; v["cboSTPT_EW.enabled"] = "false" }
        if (hasAction) v["cboAction.items"] = DtcActions.names.joinToString("\n")
    }

    override fun pressed(name: String) {
        when (name) {
            "btnClear" -> {
                typed["mxtSTPT_N"] = "00,00.000"; typed["mxtSTPT_E"] = "000,00.000"; typed["mxtSTPT_elv"] = "0000"
                if (hasAction) typed["cboAction"] = ""
            }
            "btnCancel" -> close()
            "btnApply" -> apply()
        }
    }

    private fun apply() {
        val shownPos = typed["mxtSTPT_N"] == north.removePrefix("-") && typed["mxtSTPT_E"] == east.removePrefix("-") &&
            typed["cboSTPT_NS"] == (if (north.startsWith("-")) "S" else "N") && typed["cboSTPT_EW"] == (if (east.startsWith("-")) "W" else "E")
        val pos = if (shownPos && origin != null) origin.first to origin.second
            else coords.feet(typed["mxtSTPT_N"].orEmpty(), typed["mxtSTPT_E"].orEmpty(), typed["cboSTPT_NS"] == "S", typed["cboSTPT_EW"] == "W")
        if (pos == null) {
            WdpDialogs.message(
                "Caution",
                "Coordinate was not inside the theater. Try again or use 00,00.000 to reset the coordinate",
            )
            return
        }
        val elv = if (origin != null && typed["mxtSTPT_elv"] == elev) origin.third
            else typed["mxtSTPT_elv"].orEmpty().trim().ifEmpty { "0" }.toFloatOrNull()
        if (elv == null) {
            WdpDialogs.message("Caution", "The elevation must be a number of feet.")
            return
        }
        fun done(act: String?) { onApply(pos.first, pos.second, elv, act); close() }
        if (hasAction && typed["cboAction"] != "Precision") {
            WdpDialogs.message(
                "Question",
                "The STPT is not set to a precision stpt.\nThe change will not have effect in Falcon,\ndo you want to change it to a precision stpt?",
                listOf("Yes", "No"),
            ) { a -> done(if (a == "Yes") "Precision" else typed["cboAction"]) }
            return
        }
        done(if (hasAction) typed["cboAction"] else null)
    }
}

/**
 * WDP's `fclsChangePPT`: a pre-planned threat's position, elevation and type. The type list is the theater's PPT
 * table (the page's PPT management tab); its range comes with it.
 */
internal class DtcPptWindow(
    private val nr: Int,
    private val north: String,
    private val east: String,
    private val elev: String,
    name: String,
    private val table: List<Triple<String, String, Double>>,
    private val coords: DtcCoords,
    /** The PPT as the page holds it (north, east, elevation in feet), handed back where Apply finds them as shown. */
    private val origin: Triple<Float, Float, Float>? = null,
    private val onApply: (northFt: Float, eastFt: Float, elevFt: Float, name: String, code: String, rangeFt: Float) -> Unit,
) : DtcWindow("fclsChangePPT", "Change PPT") {
    private val names = table.map { it.second }.filter { it.isNotEmpty() }

    init {
        typed["mxtPPT_N"] = north.removePrefix("-")
        typed["mxtPPT_E"] = east.removePrefix("-")
        typed["txtPPT_elv"] = elev
        typed["cboPPT_NS"] = if (north.startsWith("-")) "S" else "N"
        typed["cboPPT_EW"] = if (east.startsWith("-")) "W" else "E"
        typed["cboName"] = names.firstOrNull { it == name } ?: names.firstOrNull().orEmpty()
    }


    private fun type() = table.firstOrNull { it.second == typed["cboName"] }

    override fun fill(v: MutableMap<String, String>) {
        v["lblPPT"] = "PPT: $nr"
        if (!coords.latLon) { v["cboPPT_NS.enabled"] = "false"; v["cboPPT_EW.enabled"] = "false" }
        v["cboName.items"] = names.joinToString("\n")
        val t = type()
        v["lblRNG"] = DtcNum.f1((t?.third ?: 0.0) / NM_TO_FT) + "nm"
        v["lblCode"] = t?.first ?: ""
    }

    override fun left(name: String) {
        // txtPPT_elv_Leave: anything not a number goes back to what it was
        if (name == "txtPPT_elv" && typed[name]?.trim()?.toDoubleOrNull() == null) typed[name] = "0"
    }

    override fun pressed(name: String) {
        when (name) {
            "btnClear" -> {
                typed["mxtPPT_N"] = "00,00.000"; typed["mxtPPT_E"] = "000,00.000"; typed["txtPPT_elv"] = "0"
                typed["cboName"] = names.firstOrNull().orEmpty()
            }
            "btnCancel" -> close()
            "btnApply" -> {
                val shownPos = typed["mxtPPT_N"] == north.removePrefix("-") && typed["mxtPPT_E"] == east.removePrefix("-") &&
                    typed["cboPPT_NS"] == (if (north.startsWith("-")) "S" else "N") && typed["cboPPT_EW"] == (if (east.startsWith("-")) "W" else "E")
                val pos = if (shownPos && origin != null) origin.first to origin.second
                    else coords.feet(typed["mxtPPT_N"].orEmpty(), typed["mxtPPT_E"].orEmpty(), typed["cboPPT_NS"] == "S", typed["cboPPT_EW"] == "W")
                if (pos == null) {
                    WdpDialogs.message("Caution", "Coordinate was not inside the theater. Try again or use 00,00.000 to reset the coordinate")
                    return
                }
                val elv = if (origin != null && typed["txtPPT_elv"] == elev) origin.third
                    else typed["txtPPT_elv"].orEmpty().trim().ifEmpty { "0" }.toFloatOrNull() ?: 0f
                val t = type()
                onApply(pos.first, pos.second, elv, t?.second ?: "", t?.first ?: "", (t?.third ?: 0.0).toFloat())
                close()
            }
        }
    }

    companion object { const val NM_TO_FT = 6076.1157 }
}

/** WDP's `fclsChangeCOMM`: one UHF or VHF preset's frequency. Apply hands the typed text to the page's `ChangeCOMM`. */
internal class DtcCommWindow(
    private val vhf: Boolean,
    private val preset: Int,
    freq: String,
    private val onApply: (String) -> Unit,
) : DtcWindow("fclsChangeCOMM", "Change COMM") {
    init { typed["mxtFreq"] = freq }

    override fun fill(v: MutableMap<String, String>) {
        v["lblType"] = if (vhf) "VHF" else "UHF"
        // the preset's number in brackets, as the RADIO/NAV tab prints it, so it is never read as part of a frequency
        v["lblComm"] = "Preset [$preset]"
    }

    override fun pressed(name: String) {
        when (name) {
            "btnClear" -> typed["mxtFreq"] = ""
            "btnCancel" -> close()
            "btnApply" -> {
                val f = typed["mxtFreq"].orEmpty().trim()
                val mhz = f.toDoubleOrNull()
                val ok = if (vhf) mhz != null && mhz in 108.0..151.975 else mhz != null && mhz in 225.0..399.975
                if (!ok) {
                    WdpDialogs.message("Caution", if (vhf) "A VHF preset is 108.000 to 151.975." else "A UHF preset is 225.000 to 399.975.")
                    return
                }
                onApply(DtcNum.f3(mhz!!))
                close()
            }
        }
    }
}

/**
 * WDP's `fclsChangeLine` (Change Area): what each of the four lines should be. WDP offers the areas of its own
 * theater database; the app offers what it knows of this mission ([com.bmscompanion.app.data.wdp.DtcFromMission]) —
 * the tanker and AWACS tracks the campaign planned, the flight's CAP station, stretches of the flight plan — and "No
 * Line" to clear one. [initial] is what each line's list starts on (null: as it is): From mission… starts the free
 * lines on what it suggests. Apply hands back, per line, null (keep), the name and no points (clear), or the name and
 * the points.
 */
internal class DtcAreaWindow(
    private val areas: List<Pair<String, List<Pair<Double, Double>>>>,
    title: String = "Change Area",
    initial: List<String?> = emptyList(),
    private val onApply: (List<Pair<String, List<Pair<Double, Double>>>?>) -> Unit,
) : DtcWindow("fclsChangeLine", title) {
    private val items = listOf(KEEP, NONE) + areas.map { it.first }

    init { for (i in 1..4) typed["cboArea$i"] = initial.getOrNull(i - 1)?.takeIf { it in items } ?: KEEP }

    override fun fill(v: MutableMap<String, String>) {
        for (i in 1..4) v["cboArea$i.items"] = items.joinToString("\n")
    }

    override fun pressed(name: String) {
        when {
            name.startsWith("btnClearLine") -> typed["cboArea" + name.removePrefix("btnClearLine")] = NONE
            name == "btnCancel" -> close()
            name == "btnApply" -> {
                // null keeps a line; no points clears it; points set it
                onApply((1..4).map { i ->
                    when (val pick = typed["cboArea$i"]) {
                        KEEP, null -> null
                        NONE -> NONE to emptyList()
                        else -> areas.firstOrNull { it.first == pick }
                    }
                })
                close()
            }
        }
    }

    companion object {
        const val KEEP = "(as it is)"
        const val NONE = "No Line"
    }
}

/**
 * One thing a target can be set to, in [DtcTargetWindow]'s list: what it is, where (feet north, east), its
 * elevation, and which list it came from.
 */
internal data class DtcObjective(val name: String, val north: Double, val east: Double, val elev: Double, val control: String, val group: String)

/**
 * WDP's `fclsTargetSelection`: a target picked from a list rather than typed. WDP lists the campaign's objectives
 * and their buildings over its map; here the list is what the app knows the mission to be about — its targets and
 * steerpoints — and the theater's airbases, and the map is the app's own theater map around the one selected.
 *
 * The map's switches do what WDP's do to its map: Show Tiles puts the terrain under it, Fill Objects fills the
 * marks, Show Names writes them, Reload Tiles draws it again. A tap on the map picks the nearest objective and
 * shows the position tapped, as WDP's left click does; Show all lists every objective in view under it.
 */
internal class DtcTargetWindow(
    private val objectives: List<DtcObjective>,
    private val coords: DtcCoords,
    private val theater: Theater?,
    title: String = "Target Selection",
    /**
     * A tap on the map away from every objective picks the point tapped ("Map point", elevation not known): From
     * mission… sets a steerpoint that way. Without it, as in WDP, only an objective can be picked.
     */
    private val allowPoint: Boolean = false,
    private val onApply: (DtcObjective) -> Unit,
) : DtcWindow("fclsTargetSelection", title), WdpControlContent {
    private var filter = ALL
    private var row = -1
    /** The last position tapped on the map (north, east feet), for the cursor read-outs. */
    private var cursor: Pair<Double, Double>? = null
    private val switches = mutableMapOf("chbFill" to false, "chbNr" to true, "chbShowAll" to false, "chbTiles" to (theater != null))

    private fun shown() = if (filter == ALL) objectives else objectives.filter { it.group == filter }

    /** The map is centred on the selected objective, else on the first in the list with a position. */
    private fun centre(): DtcObjective? = shown().getOrNull(row) ?: shown().firstOrNull { it.north != 0.0 || it.east != 0.0 }

    private fun inView(o: DtcObjective, c: DtcObjective) = abs(o.north - c.north) <= SPAN_FT / 2 && abs(o.east - c.east) <= SPAN_FT / 2

    // lblInfo is WDP's hint drawn over its map; here the map is in its place. Reload Tiles read WDP's tile files
    // again; the app's map is drawn from its own and is always whole, so the button would do nothing.
    override fun hiddenControls() = listOf("lblP", "lblPos", "lsbSelect", "lblInfo", "btnReloadTiles")

    private fun rowOf(i: Int, o: DtcObjective): String {
        val (n, e) = coords.label(o.north.toFloat(), o.east.toFloat())
        return listOf((i + 1).toString(), o.name, n, e, o.elev.toInt().toString(), o.control).joinToString("\t")
    }

    override fun fill(v: MutableMap<String, String>) {
        val list = shown()
        v["cboObjectives"] = filter
        v["cboObjectives.items"] = (listOf(ALL) + objectives.map { it.group }.distinct()).joinToString("\n")
        v["dgvObjectives.rows"] = list.mapIndexed { i, o -> rowOf(i, o) }.joinToString("\n")
        v["dgvObjectives.selected"] = row.toString()
        val sel = list.getOrNull(row)
        val c = centre()
        val fed = when {
            switches["chbShowAll"] == true && c != null -> objectives.filter { inView(it, c) }
            sel != null -> listOf(sel)
            else -> emptyList()
        }
        v["dgvFed.rows"] = fed.mapIndexed { i, o -> rowOf(i, o) }.joinToString("\n")
        val at = cursor ?: sel?.let { it.north to it.east }
        v["lblFeet"] = at?.let { "N ${it.first.toLong()}'  E ${it.second.toLong()}'" } ?: ""
        v["lblCurCoord"] = at?.let { p -> coords.label(p.first.toFloat(), p.second.toFloat()).let { (n, e) -> "$n / $e" } } ?: ""
        for ((k, on) in switches) v[k] = if (on) "checked" else "unchecked"
        // WDP's own hint speaks of a right click on the list, which a phone has not got
        v["lblExplainTarget"] = if (allowPoint) "Tap a place in the list, or on the map: near a mark it picks that, elsewhere the point tapped. Then Apply."
            else "Tap a target in the list, or on the map (the nearest is picked), then Apply. A double tap on a row sets it at once."
    }

    override fun picked(name: String, value: String) {
        if (name == "cboObjectives") { filter = value; row = -1; cursor = null }
    }

    override fun pressed(name: String) {
        when {
            name.startsWith("dgvObjectives:") -> { row = name.substringAfterLast(':').toIntOrNull() ?: -1; cursor = null }
            name.startsWith("dgvFed:") -> {}
            name == "chbTiles" && theater == null -> WdpDialogs.message(
                "Target Selection",
                "The theater Falcon BMS is on is not known yet, so there is no map to show: start BMS, or link this device to the PC.",
            )
            name in switches -> switches[name] = !(switches[name] ?: false)
            name == "btnCancel" -> close()
            name == "btnApply" -> {
                val sel = shown().getOrNull(row)
                val at = cursor
                if (sel == null && allowPoint && at != null) {
                    onApply(DtcObjective(MAP_POINT, at.first, at.second, Double.NaN, "Map", "Map"))
                    close()
                    return
                }
                if (sel == null) { WdpDialogs.message("Target Selection", "Select an objective in the list first."); return }
                onApply(sel)
                close()
            }
        }
        // a double tap on a row is WDP's "set as target"
        if (name.startsWith("dgvObjectives:open:")) pressed("btnApply")
    }

    /** A tap on the map: the nearest objective in view is selected, and the position tapped is read out. */
    private fun tapped(north: Double, east: Double) {
        cursor = north to east
        val near = shown().withIndex().minByOrNull { (_, o) -> hypot(o.north - north, o.east - east) }
        if (near != null && hypot(near.value.north - north, near.value.east - east) < SPAN_FT / 12) row = near.index
        else if (allowPoint) row = -1
        refresh()
    }

    override fun controlContent(): Map<String, @Composable () -> Unit> = mapOf("cntImageControl" to { TargetMap() })

    @Composable
    private fun TargetMap() {
        val base = rememberMapBase(theater?.map)
        val tm = rememberTextMeasurer()
        // read through values() so the map redraws whenever the window does
        val v = values(emptyList())
        val c = centre()
        val tiles = v["chbTiles"] == "checked" && theater != null
        val names = v["chbNr"] == "checked"
        val filled = v["chbFill"] == "checked"
        val sel = shown().getOrNull(row)
        Canvas(
            Modifier.fillMaxSize().clipToBounds().pointerInput(c) {
                detectTapGestures { p ->
                    if (c != null) {
                        val k = SPAN_FT / size.width
                        tapped(c.north + SPAN_FT / 2 - p.y * k, c.east - SPAN_FT / 2 + p.x * k)
                    }
                }
            },
        ) {
            drawRect(Color.Black)
            if (c == null) return@Canvas
            val w = size.width
            val upperN = c.north + SPAN_FT / 2
            val upperE = c.east - SPAN_FT / 2
            val t = theater
            if (tiles && t != null) {
                // the whole theater at the scale that makes the span fill the box, placed so the view's corner is at 0,0
                val side = (w * t.sizeFt / SPAN_FT).toFloat()
                val pr = MapProjection((-upperE / t.sizeFt * side).toFloat(), (-(1.0 - upperN / t.sizeFt) * side).toFloat(), side, t.sizeFt, 1f)
                drawMapBase(pr, base, null)
            }
            val k = (w / SPAN_FT).toFloat()
            val mark = (w / 60f).coerceAtLeast(5f)
            val type = (w / 45f / (density * fontScale)).sp
            for (o in objectives) {
                if (!inView(o, c)) continue
                val at = Offset(((o.east - upperE) * k).toFloat(), ((upperN - o.north) * k).toFloat())
                val ink = when {
                    o == sel -> Color.Red
                    o.group == "Airbases" -> Color(0xFF00FFFF)
                    else -> Color.Yellow
                }
                val tl = Offset(at.x - mark / 2, at.y - mark / 2)
                if (filled) drawRect(ink, tl, Size(mark, mark)) else drawRect(ink, tl, Size(mark, mark), style = Stroke(1.5f))
                // measured unbounded, then placed: given only a position, drawText fits the name into what is left of the
                // map and throws for a point closer to the right edge than the mark is wide
                if (names) drawText(tm.measure(o.name, TextStyle(color = ink, fontSize = type)), topLeft = at + Offset(mark, -mark))
            }
            val at = cursor
            if (at != null) {
                val p = Offset(((at.second - upperE) * k).toFloat(), ((upperN - at.first) * k).toFloat())
                drawLine(Color.White, p - Offset(mark, 0f), p + Offset(mark, 0f), 1f)
                drawLine(Color.White, p - Offset(0f, mark), p + Offset(0f, mark), 1f)
            }
        }
    }

    companion object {
        const val ALL = "All"
        /** The ground the map covers, side to side: an airbase and what stands around it. */
        const val SPAN_FT = 24_000.0
        /** The name of a point tapped on the map ([allowPoint]); its elevation is NaN: not known. */
        const val MAP_POINT = "Map point"
    }
}

/** WDP's `fclsSelAPT`: an airport of the theater BMS is on, for the RADIO/NAV tab's airport block. */
internal class DtcSelAptWindow(
    private val theater: String,
    airports: List<Airport>,
    current: Int?,
    private val onSelect: (Airport) -> Unit,
) : DtcWindow("fclsSelAPT", "Select Airport") {
    private val list = airports.sortedBy { it.name }

    init {
        typed["cboCountry"] = theater
        typed["cboAirports"] = list.firstOrNull { it.id == current }?.name ?: list.firstOrNull()?.name.orEmpty()
    }

    override fun fill(v: MutableMap<String, String>) {
        v["cboCountry.items"] = theater
        v["cboAirports.items"] = list.joinToString("\n") { it.name }
    }

    override fun pressed(name: String) {
        when (name) {
            "btnCancel" -> close()
            "btnSelect" -> {
                val a = list.firstOrNull { it.name == typed["cboAirports"] } ?: return
                onSelect(a)
                close()
            }
        }
    }
}

/**
 * WDP's `fclsChart`, from the RADIO/NAV tab's Charts: the selected airport's charts, out of the app rather than out of
 * WDP's own Charts folder. **AGC** is the field drawn from Falcon BMS's own airfield data — runways, asphalt, taxiways,
 * ramp and buildings — as the Taxi page draws it; each **APC** is the ramp as BMS numbers it for one runway end (BMS
 * numbers a ramp separately for each end), captioned with that end. After them come the airport's instrument charts
 * from the app's Charts, where it has any, each page a chart of its own. As in WDP's SetButtons, the button of the
 * chart on show is hidden. The line at the foot names the chart on show and how many there are, and a tap turns to
 * the next — the way to the instrument charts (WDP's line opened its Charts folder in Explorer). The drawn chart is
 * turned to fill the window's tall box, the north arrow saying which way, and zooms and pans (wheel, pinch, drag). A
 * carrier is its deck alone, as on the Taxi page: a ship's ramp numbers turn with the ship.
 */
internal class DtcChartWindow(
    private val airport: Airport,
    private val field: Airfield?,
    charts: List<ChartRef>,
) : DtcWindow("fclsChart", airport.name + (airport.icao?.let { " ($it)" } ?: "") + " — charts"), WdpControlContent {
    /** the instrument charts' pages, each a step of its own: its name at the foot (short: the line is 166 px), picture */
    private val pages: List<Pair<String, String>> = charts.flatMap { c ->
        val ps = c.pages.ifEmpty { listOf(c.file) }.filter { it.isNotBlank() }
        ps.mapIndexed { i, p -> (short(c.title) + if (ps.size > 1) " p${i + 1}" else "") to p }
    }

    /** the ramps there are, one per runway end BMS built a network for (none on a ship), as the APC buttons show them */
    private val ramps: List<AfRoute> = if (field == null || field.ship != null) emptyList()
        else field.routes.filter { it.parking.isNotEmpty() }.take(APC.size)

    /** every chart in the order the line at the foot steps through: -1 the field, 0..3 a ramp, 100 + n a chart page */
    private val steps: List<Int> = (if (field != null) listOf(-1) + ramps.indices else emptyList()) + pages.indices.map { 100 + it }

    private var active by mutableIntStateOf(steps.firstOrNull() ?: -1)

    /** nothing to show: no layout of the field and no instrument chart */
    val isEmpty: Boolean get() = steps.isEmpty()

    private fun nameOf(step: Int): String = when {
        step >= 100 -> pages[step - 100].first
        step >= 0 -> "Parking RWY " + ramps[step].designator
        field?.ship != null -> "Flight deck"
        else -> "Ground chart"
    }

    override fun fill(v: MutableMap<String, String>) {
        v["btnAgc"] = if (field == null || active == -1) "hidden" else "shown"
        v["btnAgc.text"] = if (field?.ship != null) "Deck" else "AGC"
        APC.forEachIndexed { k, b ->
            val r = ramps.getOrNull(k)
            v[b] = if (r == null || active == k) "hidden" else "shown"
            if (r != null) v["$b.text"] = "APC " + r.designator
        }
        val at = steps.indexOf(active)
        v["lblFile"] = if (steps.size > 1) "${at + 1}/${steps.size} ${nameOf(active)} ▸" else nameOf(active)
        // an instrument chart is a picture of the app's (a path with a folder in it, drawn fitted); the drawn chart
        // is the content below, over an empty box
        if (active >= 100) v["cntChart"] = pages[active - 100].second
    }

    override fun controlContent(): Map<String, @Composable () -> Unit> = mapOf("cntChart" to {
        val a = active
        if (a < 100 && field != null) {
            // on white, as WDP's charts are
            AirfieldChart(
                field = field, route = ramps.getOrNull(a), inks = ChartInks.day,
                modifier = Modifier.fillMaxSize(), fitRotation = true,
            )
        }
    })

    override fun pressed(name: String) {
        when (name) {
            "btnClose" -> close()
            "btnAgc" -> if (field != null) active = -1
            in APC -> APC.indexOf(name).takeIf { it in ramps.indices }?.let { active = it }
            "lblFile" -> if (steps.size > 1) active = steps[(steps.indexOf(active) + 1) % steps.size]
        }
    }

    companion object {
        val APC = listOf("btnApc1", "btnApc2", "btnApc3", "btnApc4")

        /** "09L · ILS or LOC/DME" → "09L ILS or LOC/DME"; anything past 18 characters ends in "…". */
        fun short(title: String): String {
            val parts = title.split(" · ")
            val s = (if (parts.size >= 2) parts[0].trim() + " " + parts[1].trim() else title.trim()).ifEmpty { "Chart" }
            return if (s.length <= 18) s else s.take(17).trimEnd() + "…"
        }
    }
}

/**
 * WDP's `fclsAptToStpt`: a runway threshold or centre made a steerpoint. From mission… uses it for any point of the
 * mission, into a steerpoint ([range] 1-24, [start] the first free), an open steerpoint (81-89, 90-99) or a row of
 * the target list ([word] "Target", 1-100).
 */
internal class DtcAptToStptWindow(
    private val what: String,
    private val north: String,
    private val east: String,
    title: String = "Airport to STPT",
    private val range: IntRange = 1..24,
    start: Int = range.first,
    private val word: String = "STPT",
    private val onApply: (Int) -> Unit,
) : DtcWindow("fclsAptToStpt", title) {
    private var nr = start.coerceIn(range.first, range.last)

    override fun fill(v: MutableMap<String, String>) {
        v["lblInfo"] = "Set $word $nr to the $what?"
        v["lblNorth"] = "N   $north"
        v["lblEast"] = "E $east"
        v["lblStpt"] = word
        v["numStpt"] = nr.toString()
        v["numStpt.min"] = range.first.toString()
        v["numStpt.max"] = range.last.toString()
    }

    override fun picked(name: String, value: String) {
        if (name == "numStpt") nr = (value.trim().toIntOrNull() ?: nr).coerceIn(range.first, range.last)
    }

    override fun pressed(name: String) {
        when (name) {
            "btnCancel" -> close()
            "btnApply" -> { onApply(nr); close() }
        }
    }
}

/** The few number formats the windows print, without `String.format` (not every platform has it). */
internal object DtcNum {
    fun f1(v: Double): String = fixed(v, 1)
    fun f3(v: Double): String = fixed(v, 3)

    fun fixed(v: Double, places: Int): String {
        var p = 1L
        repeat(places) { p *= 10 }
        val r = kotlin.math.round(kotlin.math.abs(v) * p).toLong()
        val whole = r / p
        val frac = (r % p).toString().padStart(places, '0')
        return (if (v < 0 && r != 0L) "-" else "") + whole + if (places > 0) ".$frac" else ""
    }
}
