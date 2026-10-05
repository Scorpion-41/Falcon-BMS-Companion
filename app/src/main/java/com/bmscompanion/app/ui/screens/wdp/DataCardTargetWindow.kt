package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import com.bmscompanion.app.data.mission.CampFeature
import com.bmscompanion.app.data.mission.CampObjective
import com.bmscompanion.app.data.mission.CampObjectives
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.drawMapBase
import com.bmscompanion.app.ui.components.rememberMapBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * WDP's **Target Selection** window for the DataCard's DMPI boxes (`txtDMPI_Pri_MouseDoubleClick` →
 * `fclsTargetSelection`, and `fclsFed` when the box's strike steerpoint already has a target): every objective of the
 * save's start file in the left grid — by type as WDP's list is (its first type, Airbase, to begin with; "All" for
 * every one), sorted by name — and the selected objective's buildings under the map, with where each is, the ground
 * there and its worth (only those worth something unless **Show all** is ticked). The objective list's box also
 * takes typing: a word that is not a type finds every objective whose name holds it. A tap on a building (or on the
 * map near one) makes it the DMPI; **Apply**, or a double tap on the building, puts the objective, the building and
 * its position on the card ([onApply]; WDP's `FillPriTarget`). With no building picked it is the first one listed,
 * as WDP's `TgtBld` starts at 0.
 *
 * Everything comes from the PC ([MissionLink.campaignObjectives], [MissionLink.campaignFeatures]; read only). [ref] is
 * the save; [campId] and [building] preselect the strike steerpoint's own target, as `fclsFed` opens on it.
 */
internal class DataCardTargetWindow(
    private val ref: CampRef,
    private val coords: PopupCoords.CoordData?,
    private val theater: Theater?,
    private val campId: Int?,
    private val building: Int?,
    private val onApply: (CampObjective, CampFeature?) -> Unit,
) : CardWindow("fclsTargetSelection", "Target Selection"), WdpControlContent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var all: CampObjectives? = null
    private var error: String? = null
    /** "All", one of the types, or words typed to find a name */
    private var filter = ""
    private var shown: List<CampObjective> = emptyList()
    private var row = -1
    private var features: List<CampFeature>? = null
    private var featuresOf: String? = null
    private var feature = -1
    private var tapped: Pair<Double, Double>? = null

    init {
        v["cboObjectives.editable"] = "true"
        for (n in listOf("lblP", "lblPos", "lsbSelect", "lblInfo", "btnReloadTiles")) v[n] = "hidden"
        v["chbFill"] = "unchecked"; v["chbNr"] = "checked"; v["chbShowAll"] = "unchecked"; v["chbTiles"] = if (theater != null) "checked" else "unchecked"
        v["lblExplainTarget"] = "Loading the save's objectives from the PC…"
        refill()
        scope.launch { load() }
    }

    private suspend fun load() {
        val key = CampRef(ref.theater, ref.file)
        val cached = cache[key]
        val a = cached ?: runCatching { MissionLink.campaignObjectives(ref.theater, ref.file) }.getOrNull()?.let { p ->
            p.value ?: run { error = p.error ?: "The PC sent nothing."; null }
        }
        if (a == null) {
            error = error ?: "The PC could not be reached."
            refill(); version++
            return
        }
        cache[key] = a
        all = a
        val pre = campId?.let { id -> a.objectives.firstOrNull { it.campId == id } }
        filter = pre?.type?.takeIf { it.isNotEmpty() } ?: a.types.firstOrNull() ?: ALL
        refill()
        if (pre != null) { row = shown.indexOf(pre); pick(pre, building) }
        refill(); version++
    }

    private fun matches(o: CampObjective): Boolean = when {
        filter.isEmpty() || filter == ALL -> true
        all?.types?.contains(filter) == true -> o.type == filter
        else -> o.name.contains(filter.trim(), ignoreCase = true)
    }

    private fun label(north: Double, east: Double): Pair<String, String> {
        val c = coords ?: return ("N ${north.toLong()}'" to "E ${east.toLong()}'")
        return PopupCoords.hemispheres(PopupCoords.feetToCoordsBoth(c, north, east))
    }

    /** The buildings the grid lists: worth something, or all of them with Show all. */
    private fun listed(): List<CampFeature> = features.orEmpty().filter { checked("chbShowAll") || it.value > 0 }

    private fun refill() {
        val a = all
        v["cboObjectives"] = filter
        v["cboObjectives.items"] = (listOf(ALL) + a?.types.orEmpty()).joinToString("\n")
        shown = a?.objectives.orEmpty().filter(::matches).sortedBy { it.name.lowercase() }
        val cut = shown.take(MOST_ROWS)
        val rows = cut.map { o ->
            val (n, e) = label(o.x, o.y)
            listOf(a!!.objectives.indexOf(o).toString(), o.name, n, e, o.elevFt?.toString() ?: "", o.control).joinToString("\t")
        } + (if (shown.size > MOST_ROWS) listOf("\t… ${shown.size - MOST_ROWS} more: pick a type, or type part of a name") else emptyList())
        v["dgvObjectives.rows"] = rows.joinToString("\n")
        v["dgvObjectives.selected"] = row.toString()
        v["dgvFed.rows"] = listed().joinToString("\n") { f ->
            val (n, e) = label(f.x, f.y)
            listOf(f.n.toString(), f.name, n, e, f.elevFt?.toString() ?: "", valueText(f.value)).joinToString("\t")
        }
        v["dgvFed.selected"] = listed().indexOfFirst { it.n == feature }.toString()
        val sel = shown.getOrNull(row)
        val at = tapped ?: features?.firstOrNull { it.n == feature }?.let { it.x to it.y } ?: sel?.let { it.x to it.y }
        v["lblFeet"] = at?.let { "X: ${it.second.toLong()} Y: ${it.first.toLong()}" } ?: ""
        v["lblCurCoord"] = at?.let { p -> label(p.first, p.second).let { (n, e) -> "$n - $e" } } ?: ""
        v["lblExplainTarget"] = when {
            error != null -> "The objectives could not be read: $error"
            a == null -> "Loading the save's objectives from the PC…"
            sel == null -> "Pick an objective in the list (the box above it picks a type, or finds a name you type)."
            features == null -> "Loading ${sel.name}'s buildings…"
            else -> "Tap a building in the list or on the map, then Apply. A double tap on a building sets it at once."
        }
    }

    /** An objective selected: its buildings from the PC, [pre] (a building number) selected when it has one. */
    private fun pick(o: CampObjective, pre: Int? = null) {
        if (featuresOf == o.id && features != null) return
        featuresOf = o.id
        features = null
        feature = -1
        tapped = null
        scope.launch {
            val a = runCatching { MissionLink.campaignFeatures(ref.theater, ref.file, o.id) }.getOrNull()
            if (featuresOf != o.id) return@launch
            features = a?.value?.features.orEmpty()
            if (a?.value == null) error = a?.error ?: "the PC could not be reached"
            feature = pre?.takeIf { p -> features.orEmpty().any { it.n == p } } ?: -1
            // a building worth nothing is listed once Show all is ticked, as WDP's grid does for its target
            if (feature >= 0 && features.orEmpty().first { it.n == feature }.value <= 0) v["chbShowAll"] = "checked"
            refill(); version++
        }
    }

    override fun value(name: String, value: String) {
        when (name) {
            "cboObjectives" -> { filter = value; row = -1; features = null; featuresOf = null; feature = -1; refill() }
            "cboObjectives.leave" -> {}
            else -> super.value(name, value)
        }
    }

    override fun click(name: String) {
        when {
            name.startsWith("dgvObjectives:") -> {
                val i = name.substringAfterLast(':').toIntOrNull() ?: return
                val o = shown.getOrNull(i)?.takeIf { i < MOST_ROWS } ?: return
                row = i
                pick(o)
            }
            name.startsWith("dgvFed:") -> {
                val i = name.substringAfterLast(':').toIntOrNull() ?: return
                val f = listed().getOrNull(i) ?: return
                feature = f.n
                tapped = null
                if (name.contains(":open:")) { apply(); return }
            }
            name == "chbShowAll" || name == "chbFill" || name == "chbNr" -> toggle(name)
            name == "chbTiles" -> if (theater != null) toggle(name)
            name == "btnApply" -> { apply(); return }
            name == "btnCancel" -> { close(); return }
        }
        refill()
    }

    private fun apply() {
        val o = shown.getOrNull(row)
        if (o == null) { WdpDialogs.message(title, "Select an objective in the list first."); return }
        val f = features?.firstOrNull { it.n == feature } ?: listed().firstOrNull() ?: features?.firstOrNull()
        close()
        onApply(o, f)
    }

    override fun controlContent(): Map<String, @Composable () -> Unit> = mapOf("cntImageControl" to { TargetMap() })

    /**
     * The map WDP draws beside the list (`DrawRecon`): the selected objective's ground with its buildings — the one
     * picked in red, those worth nothing grey — over the app's theater map (Show Tiles). A tap picks the building
     * nearest it.
     */
    @Composable
    private fun TargetMap() {
        @Suppress("UNUSED_VARIABLE") val r = version
        val base = rememberMapBase(theater?.map)
        val tm = rememberTextMeasurer()
        val sel = shown.getOrNull(row)
        val list = features.orEmpty()
        // what the drawing reads, taken here: the window's fields are not state, and a drawing that only read them
        // inside would be kept as it was (the building picked stayed yellow)
        val picked = feature
        val tap = tapped
        val showAll = checked("chbShowAll")
        val names = checked("chbNr")
        val filled = checked("chbFill")
        val tiles = checked("chbTiles")
        // the buildings' extent, square, with a margin: at least two miles across
        val ns = list.map { it.x } + listOfNotNull(sel?.x)
        val es = list.map { it.y } + listOfNotNull(sel?.y)
        val span = if (ns.isEmpty()) 0.0 else maxOf(ns.max() - ns.min(), es.max() - es.min(), 12_000.0) * 1.15
        val cn = if (ns.isEmpty()) 0.0 else (ns.max() + ns.min()) / 2
        val ce = if (es.isEmpty()) 0.0 else (es.max() + es.min()) / 2
        Canvas(
            Modifier.fillMaxSize().clipToBounds().pointerInput(sel?.id, span) {
                detectTapGestures { p ->
                    if (span <= 0.0) return@detectTapGestures
                    val k = span / size.width
                    val n = cn + span / 2 - p.y * k
                    val e = ce - span / 2 + p.x * k
                    val near = listed().minByOrNull { hypot(it.x - n, it.y - e) }
                    if (near != null && hypot(near.x - n, near.y - e) < span / 10) { feature = near.n; tapped = null } else tapped = n to e
                    refill(); version++
                }
            },
        ) {
            drawRect(Color.Black)
            if (span <= 0.0) return@Canvas
            val w = size.width
            val upperN = cn + span / 2
            val upperE = ce - span / 2
            val t = theater
            if (t != null && tiles) {
                val side = (w * t.sizeFt / span).toFloat()
                drawMapBase(MapProjection((-upperE / t.sizeFt * side).toFloat(), (-(1.0 - upperN / t.sizeFt) * side).toFloat(), side, t.sizeFt, 1f), base, null)
            }
            val k = (w / span).toFloat()
            val mark = (w / 70f).coerceAtLeast(4f)
            val type = (w / 50f / (density * fontScale)).sp
            for (f in list) {
                if (!showAll && f.value <= 0 && f.n != picked) continue
                val at = Offset(((f.y - upperE) * k).toFloat(), ((upperN - f.x) * k).toFloat())
                val ink = when {
                    f.n == picked -> Color.Red
                    f.value <= 0 -> Color.Gray
                    else -> Color.Yellow
                }
                val tl = Offset(at.x - mark / 2, at.y - mark / 2)
                if (filled || f.n == picked) drawRect(ink, tl, Size(mark, mark)) else drawRect(ink, tl, Size(mark, mark), style = Stroke(1.2f))
                if (names && (f.n == picked || f.value > 0) && f.name.isNotEmpty()) {
                    drawText(tm.measure(f.name, TextStyle(color = ink, fontSize = type)), topLeft = at + Offset(mark, -mark))
                }
            }
            tap?.let { (n, e) ->
                val p = Offset(((e - upperE) * k).toFloat(), ((upperN - n) * k).toFloat())
                drawLine(Color.White, p - Offset(mark, 0f), p + Offset(mark, 0f), 1f)
                drawLine(Color.White, p - Offset(0f, mark), p + Offset(0f, mark), 1f)
            }
        }
    }

    companion object {
        const val ALL = "All"
        /** the most objectives the grid lists at once; a type or a name narrows it */
        const val MOST_ROWS = 500
        /** each save's objectives, as the PC last sent them: a save's start file does not change */
        private val cache = HashMap<CampRef, CampObjectives>()

        /** WDP's `ValueText`: a building's worth in words. */
        fun valueText(value: Int): String = when {
            value < 10 -> "Very Low"
            value < 25 -> "Low"
            value < 40 -> "Medium"
            value < 50 -> "High"
            else -> "Very High"
        }
    }
}
