package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.ImageAction
import com.bmscompanion.app.data.LocalImageActions
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.AttackDrawing
import com.bmscompanion.app.ui.components.AttackInks
import com.bmscompanion.app.ui.components.drawAttackModel
import com.bmscompanion.app.data.wdp.AttackGeometry
import com.bmscompanion.app.data.wdp.PopupPlan
import com.bmscompanion.app.data.wdp.WdpValues
import com.bmscompanion.app.ui.components.MapBaseState
import com.bmscompanion.app.ui.components.MapLook
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.components.drawMapBase
import com.bmscompanion.app.ui.components.rememberMapBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A wiring that draws something of its own inside one of the layout's controls — the attack pages' map inside
 * `picSatView`. The renderer knows nothing about maps; it is handed the control's name and what to draw in its box
 * (`WdpFormView`'s `content`), so a Windows picture box that WDP paints with GDI+ can be any Compose drawing here.
 */
interface WdpControlContent {
    /** Control name → what is drawn inside that control's box, over the designer's own drawing of it. */
    fun controlContent(): Map<String, @Composable () -> Unit>
}

/**
 * The map on an attack page (Pop-up, HADB, TOSS), and the page's Save Map and Save to DTC.
 *
 * WDP crops its own bitmap of the theater around the target and draws the plan on it. The app has its own theater
 * map — the same styles and tiles as the Mission map, on every platform — so the plan's drawing
 * ([PopupPlan.MapPicture], its marks in picSatView's 435 pixels) is laid over the same rectangle of **that** map, cut
 * by the feet the plan's crop covers rather than by WDP's pixels. The marks' ink follows the map, as WDP's follows its
 * "white map" box: white on the darker styles, black on the light Chart one.
 *
 * [profile] is WDP's name for the page ("PopUp", "HADB", "TOSS"): the file name Save Map offers, and the profile
 * Save to DTC names.
 */
class WdpAttackMap(val profile: String) {
    /** The theater the mission is on; while it is unknown the marks are drawn on black. */
    var theater by mutableStateOf<Theater?>(null)

    /** Set while the map is on screen: the map drawn once more, off screen, at WDP's own 435 pixels. */
    internal var snapshot: (() -> ImageBitmap)? = null
    internal var actions: List<ImageAction> = emptyList()

    /**
     * `btnSave_Click`: WDP's "Save <page> map" window in its `SavedMaps` folder on `<page>.jpg` (`JPEG|*.jpg`), and the
     * map written there as a JPEG — on the BMS PC, from every device ([WdpFiles]): Windows' own dialog on the PC's
     * window, the PC's folders in the Planner's window anywhere else.
     */
    fun saveMap() {
        val snap = snapshot
        if (snap == null) {
            WdpDialogs.message("Save $profile map", "The map is drawn on the Profile panel. Turn the selector to Profile and press Save Map there.")
            return
        }
        val bytes = try { WdpPicture.jpeg(snap()) } catch (e: Exception) {
            WdpDialogs.message("Save $profile map", "The map could not be drawn to a picture: ${e.message ?: e::class.simpleName}")
            return
        }
        scope.launch { saveMapFile(bytes) }
    }

    /** The window and the write of [saveMap], for the checks as well: the file's path, or null when nothing was saved. */
    internal suspend fun saveMapFile(bytes: ByteArray): String? {
        val title = "Save $profile map"
        val f = WdpFiles.save(title, WdpFiles.SAVED_MAPS, "JPEG|*.jpg", profile, defaultExt = "jpg") ?: return null
        if (!WdpFiles.writeBytes(f, bytes, title)) return null
        WdpFiles.remember("map_$profile", f)
        return f.path
    }

    /**
     * Save to DTC for this page: see [saveNavOffsets]. Only the lines of the reference in use are handed on (D3:
     * WDP wrote all eight, so a VRP plan carried VIP lines measured from nowhere); the others keep what the cartridge
     * holds. **Once the cartridge is saved, the DataCard's Delivery block becomes this attack** ([AttackSelection.saved],
     * 1.3.8: the page's Save to DTC took over the test builds' Send to DataCard), so the card and the cartridge agree;
     * a save that fails leaves the card as it was.
     */
    fun saveDtc(offsets: Map<String, PopupPlan.Offset>, navModeSel: Int) =
        saveNavOffsets(profile, navOffsets(AttackGeometry.selectedOffsets(offsets, navModeSel == 1)), navModeSel) {
            AttackSelection.saved(profile)
        }

    /**
     * What is drawn inside picSatView: the map, from [picture] each time the page recomposes, and along its foot
     * [caption] — what the mission calls the TGT STPT ([stptCaption]), so the map says which target it is planned on;
     * across its top the page's line about the IP STPT that IP STPT at the VRP created ([notice]; on the screen only,
     * never in Save Map's picture).
     */
    internal fun content(
        caption: () -> String? = { null },
        onTarget: () -> Boolean = { true },
        notice: () -> AttackSelection.IpNotice? = { null },
        picture: () -> PopupPlan.MapPicture?,
    ): Map<String, @Composable () -> Unit> =
        mapOf("picSatView" to {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                WdpMapView(this@WdpAttackMap, picture(), caption(), onTarget())
                notice()?.let { AttackNoticeStrip(it) }
            }
        })

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        /**
         * Save to DTC on an attack page: WDP hands the page's nav offsets to the DTC page (`cntDTC.Profiles`) and the
         * main form writes the cartridge, then says "<file> saved." Here the DTC page owns the cartridge
         * ([WdpCartridge]); its answer is shown the same way, and while the DTC page has not loaded the cartridge the
         * pilot is told so rather than the button doing nothing.
         */
        fun saveNavOffsets(profile: String, offsets: Map<String, WdpCartridge.NavOffset>, navModeSel: Int, then: (() -> Unit)? = null) {
            val hook = WdpCartridge.saveNavOffsets
            if (hook == null) {
                WdpDialogs.message(
                    "Save to DTC",
                    "The data cartridge is not loaded yet: the Planner reads it from Falcon BMS on the PC once it is linked. Press Save to DTC here again in a moment." +
                        (if (then != null) " The DataCard is unchanged." else ""),
                )
                return
            }
            val wait = WdpMessage("Save to DTC", "Saving the $profile offsets to the cartridge…")
            WdpDialogs.stack += wait
            scope.launch {
                // [then] runs inside the save once it has written the cartridge (or after its question, later)
                var ran = false
                val after = then?.let { t -> { runCatching { t() }; ran = true } }
                val answer = try { hook(profile, offsets, navModeSel, after) } catch (e: Exception) { "The cartridge could not be saved: ${e.message ?: e::class.simpleName}" }
                WdpDialogs.stack.remove(wait)
                // "" = the DTC page put its own question up (the zeroing warning): no second box
                if (answer.isNotBlank()) WdpCartridge.answer(
                    "Message",
                    answer + when {
                        then == null -> ""
                        ran -> "\n\nThe DataCard's Delivery section now holds this attack."
                        else -> "\n\nThe DataCard is unchanged: it takes the attack once the cartridge is saved."
                    },
                )
            }
        }

        /** WDP's offsets (steerpoint, bearing, range, elevation) as the cartridge takes them. */
        fun navOffsets(src: Map<String, PopupPlan.Offset>): Map<String, WdpCartridge.NavOffset> =
            src.mapValues { (_, o) -> WdpCartridge.NavOffset(o.stpt, o.bearing, o.range, o.elv) }
    }
}

/**
 * The ground's elevation (feet above sea level) under the point an attack page plans against, or null where nothing
 * says (D7). First BMS's own height map at the point, read on the PC ([WdpGround]) — the cell WDP's Pop-up reads with
 * `GetTerrainHeight`. Where the PC has not answered, a **target point** of the cartridge — a steerpoint flagged as a
 * target, or a weapon target point — which BMS writes at the ground, so its altitude is the ground's; a plain
 * flight-plan steerpoint carries the altitude to be flown there, which is not the ground, and gives nothing. [n] is
 * the steerpoint the page shows, [north]/[east] the point itself (a weapon target point sits in that steerpoint's place).
 */
internal fun WdpMission.groundAt(n: Int, north: Double, east: Double): Double? = groundSourced(n, north, east)?.first

/** [groundAt] and where it came from. */
internal fun WdpMission.groundSourced(n: Int, north: Double, east: Double): Pair<Double, GroundSource>? {
    WdpGround.at(groundTheater(), north, east)?.let { return it.toDouble() to GroundSource.TERRAIN }
    fun near(x: Double, y: Double) = kotlin.math.abs(x - north) < 1.0 && kotlin.math.abs(y - east) < 1.0
    steerpoints.firstOrNull { it.n == n && it.isTarget && near(it.x, it.y) }?.let { return it.altFt to GroundSource.DTC }
    dtc?.weaponTargets.orEmpty().firstOrNull { near(it.x, it.y) }?.let { return it.altFt to GroundSource.DTC }
    return null
}

/** Every placed point of the mission a target could be on: the steerpoints and the cartridge's weapon target points. */
internal fun WdpMission.groundPoints(): List<Pair<Double, Double>> =
    steerpoints.filter { it.placed() }.map { it.x to it.y } +
        dtc?.weaponTargets.orEmpty().filter { it.x != 0.0 || it.y != 0.0 }.map { it.x to it.y }

/**
 * What the mission calls steerpoint [n], for the attack map's caption: "STPT 15 · EW Site Puryu-gogae 1 JLP-40 Radar
 * P-37". With a save's flight: the target the flight designates to this seat (else the waypoint's own target), else
 * the save's word for the waypoint's action, else the cartridge's name — the printed briefing may be another
 * flight's. Otherwise the cartridge's name for the point (BMS names a target steerpoint after what it is; "Not set"
 * is its empty slot), else the briefing's row. Null while the steerpoint has no position.
 */
internal fun WdpMission.stptCaption(n: Int?): String? {
    if (n == null) return null
    val p = steerpoint(n)?.takeIf { it.placed() } ?: return null
    fun String?.real() = this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Not set", ignoreCase = true) }
    val name = if (flight != null) {
        val w = flight.route.withIndex().firstOrNull { (i, w) -> (if (w.n > 0) w.n else i + 1) == n }?.value
        w?.let { it.designated.getOrNull(seat) ?: it.target }?.name.real() ?: w?.desc.real() ?: p.name.real()
    } else {
        p.name.real() ?: briefing?.steerpoints.orEmpty().firstOrNull { it.n == n }?.desc.real()
    }
    return "STPT $n" + (name?.let { " · $it" } ?: "")
}

/**
 * The attack map's caption: what the TGT STPT is ([stptCaption]) — or, while the page has no target, why the map
 * shows no ground: with no mission at all, where one comes from; with one, that this steerpoint has no position.
 */
internal fun WdpMission.attackCaption(n: Int?, onTarget: Boolean): String? = when {
    onTarget -> stptCaption(n)
    steerpoints.none { it.placed() } -> "No mission yet: Open mission… or press PRINT in BMS"
    else -> "STPT ${n ?: "?"} has no position in this mission: pick a target steerpoint in TGT STPT"
}

/**
 * The DED panels' units beside their figures. WDP pads each line's caption with spaces — "  RNG" and "FT" twenty-odd
 * spaces apart — so that the unit lands just past the figure's black box, which it does only with GDI's whole-pixel
 * spaces; in any other font the unit slid under the box (a sliver of the T showing, the ELEV unit behind its X). So
 * the caption keeps its word and the unit follows the figure, as the DED itself prints it. [lines]: caption label to
 * figure label; a figure label ending in "brg" is a bearing (°), the others feet. Only what is shown: the plan's
 * figures are untouched, and so is everything the page tests compare.
 */
internal fun MutableMap<String, String>.dedUnits(lines: List<Pair<String, String>>) {
    for ((cap, fig) in lines) {
        val bearing = fig.endsWith("brg")
        if (this[cap] != "hidden") this[cap] = if (bearing) "TBRG" else if (fig.endsWith("rng")) "  RNG" else "ELEV"
        val v = this[fig]
        if (!v.isNullOrBlank() && v != "hidden") this[fig] = if (bearing) "$v°" else "$v FT"
    }
}

/** The DED lines of the Pop-up and TOSS pages (the same names on both): caption label to figure label. */
internal val DED_LINES_VIP = listOf(
    "lblDEDvip_3" to "lblVIPbrg", "lblDEDvip_4" to "lblVIPrng", "lblDEDvip_5" to "lblVIPelv",
    "lblDEDpup_3" to "lblPUPbrg", "lblDEDpup_4" to "lblPUPrng", "lblDEDpup_5" to "lblPUPelv",
    "lblDEDOA1_4" to "lblOA1brg", "lblDEDOA1_3" to "lblOA1rng", "lblDEDOA1_5" to "lblOA1elv",
    "lblDEDOA2_4" to "lblOA2brg", "lblDEDOA2_3" to "lblOA2rng", "lblDEDOA2_5" to "lblOA2elv",
)

/** The DED lines of the HADB page: caption label to figure label. */
internal val DED_LINES_HADB = listOf(
    "lblDEDTEMPtbrg" to "lblVRPbrg", "lblDEDTEMPrng" to "lblVRPrng", "lblDEDTEMPelv" to "lblVRPelv",
    "lblDEDvrppup_3" to "lblVRPPUPbrg", "lblDEDvrppup_4" to "lblVRPPUPrng", "lblDEDvrppup_5" to "lblVRPPUPelv",
    "lblDEDoa1_3" to "lblOA1brg", "lblDEDoa1_4" to "lblOA1rng", "lblDEDoa1_5" to "lblOA1elv",
    "lblDEDoa2_4" to "lblOA2brg", "lblDEDOA2_3" to "lblOA2rng", "lblDEDoa2_5" to "lblOA2elv",
)

/**
 * An ELEV the pilot types on an attack page (D7; forum, 2024: "impossible to put manually the elevations for OA1, OA2,
 * VRP, PUP"): a DED line's, or the ground under the target (the Coordinates box's target Elv, which every DED ELEV
 * stands on). A tap or click on the figure opens WDP's own small entry window (`fclsPackageNr`, its second box put
 * away) asking for the figure in feet above sea level — or, where a finger works the Planner, the big editor a finger
 * gets for a box ([WdpTouch]). 0 is ground level, as BMS reads it, and an empty box puts the page's own figure back.
 * [line] names the line in the window's title and [how] says how the page's own figure is made ("500 ft above the
 * target + 65 ft ground"); the page files the figure under that line's nav-offset key (or [AttackGeometry.TGT]), so
 * what is typed is what the DED shows and Save to DTC writes.
 */
internal class ElevEntry(
    private val line: String,
    private val shown: String,
    private val how: String? = null,
    private val caption1: String = "0 = ground level",
    private val caption2: String = "Feet above sea level",
    private val onDone: (Int?) -> Unit,
) : WdpWiring {
    private var text = shown
    private var dialog: WdpDialog? = null
    private var version by mutableStateOf(0)

    fun open() {
        if (WdpTouch.device && WdpTouch.hosts > 0) {
            // a finger's editor: the big box with the number keyboard; its OK is the window's OK
            WdpTouch.sheet = WdpSheet.Text(
                // two lines at a finger's size: what the figure is, then what 0 and an empty box do
                "$line ELEV, feet above sea level · 0 = ground level, empty = the page's own",
                "elev/$line", shown, numeric = true, multiline = false,
            ) { t -> text = t; commit(fromSheet = true) }
            return
        }
        val d = WdpDialog("fclsPackageNr", "$line ELEV" + (how?.let { " — $it" } ?: ""), this, hidden = listOf("txtMissionName"))
        dialog = d
        WdpDialogs.show(d)
    }

    override fun values(hidden: List<String>): WdpValues {
        @Suppress("UNUSED_VARIABLE") val v = version
        val m = LinkedHashMap<String, String>()
        for (h in hidden) m[h] = "hidden"
        m["lblTitel"] = "$line ELEV"
        m["lblExplenation1"] = caption1
        m["lblExplenation2"] = caption2
        m["txtPackageNr"] = text
        // its tooltips (WdpTips) are its own: the window is the DataCard's package-number window retitled, whose file
        // tips (tips/fclsPackageNr.json) speak of a package, so they are replaced here and the captions' taken off
        m["txtPackageNr.tip"] = if (line == AttackGeometry.TGT)
            "The ground under the target, whole feet above sea level, -1500 to 99999. Empty puts BMS's terrain back. The page's ELEVs stand on it."
        else
            "$line ELEV, whole feet above sea level, -1500 to 99999; 0 is ground level wherever the point is. Empty puts the page's own figure back."
        m["btnOK.tip"] = "Uses the figure on the page at once. It reaches your cartridge with the page's Save to DTC."
        m["btnCancel.tip"] = "Closes the window and leaves the figure as it was."
        for (k in listOf("lblTitel", "lblExplenation1", "lblExplenation2", "txtMissionName")) m["$k.tip"] = ""
        return WdpValues(m)
    }

    override fun onValue(name: String, value: String) {
        if (name == "txtPackageNr") { text = value.filter { it.isDigit() || it == '-' }.take(6); version++ }
    }

    /** OK: a whole number of feet, or nothing for the page's own figure; anything else is refused with a reason. */
    private fun commit(fromSheet: Boolean = false) {
        val t = text.trim()
        val v = if (t.isEmpty()) null else t.toIntOrNull()
        if (t.isNotEmpty() && (v == null || v < -1500 || v > 99999)) {
            WdpDialogs.message("$line ELEV", "An elevation is a whole number of feet, from -1500 to 99999.")
            return
        }
        if (!fromSheet) dialog?.let { WdpDialogs.close(it) }
        onDone(v)
    }

    override fun onClick(name: String) {
        when (name) {
            "btnOK", "txtPackageNr:enter" -> commit()
            "btnCancel" -> dialog?.let { WdpDialogs.close(it) }
        }
    }
}

/**
 * A wiring's last line of defence: whatever still gets out of a handler is shown, as WDP's own error box shows it,
 * and the page stays where the handler left it.
 */
internal fun wdpError(page: String, e: Throwable) {
    WdpDialogs.message("Weapon Delivery Planner", "The $page page could not finish that: ${e.message ?: e::class.simpleName}")
}

/** The map inside picSatView: the theater map cut to the plan's crop, the plan's marks over it, scaled to the box. */
@Composable
private fun WdpMapView(host: WdpAttackMap, picture: PopupPlan.MapPicture?, caption: String?, onTarget: Boolean) {
    // With no target WDP plans against a stand-in point of its own, which is no place in the theater: the plan is
    // drawn on black then, as it is with no theater, and the caption says why ([attackCaption]).
    val theater = host.theater
    val shownTheater = if (onTarget) theater else null
    val base = rememberMapBase(theater?.map)
    val tm = rememberTextMeasurer()
    val light = MapLook.light
    val current by rememberUpdatedState(picture)
    val lightNow by rememberUpdatedState(light)
    val captionNow by rememberUpdatedState(caption)
    val theaterNow by rememberUpdatedState(shownTheater)
    host.actions = LocalImageActions.current
    DisposableEffect(host, base, tm) {
        host.snapshot = {
            val px = PopupPlan.MAP_PX
            val img = ImageBitmap(px, px)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(px.toFloat(), px.toFloat())) {
                drawWdpMap(current, theaterNow, base, tm, lightNow, caption = captionNow)
            }
            img
        }
        onDispose { host.snapshot = null }
    }
    Canvas(Modifier.fillMaxSize().clipToBounds()) { drawWdpMap(picture, shownTheater, base, tm, light, caption = caption) }
}

/**
 * A plan's picture over the theater map, and nothing else — the DataCard's picMap, where WDP places the picture its
 * `FillMap` makes (`PlaceMap`). [paper] is WDP's "white map": the marks in black on white, without the map.
 */
@Composable
internal fun WdpMapPicture(theater: Theater?, picture: PopupPlan.MapPicture?, paper: Boolean = false) {
    val base = rememberMapBase(theater?.map)
    val tm = rememberTextMeasurer()
    val light = MapLook.light
    Canvas(Modifier.fillMaxSize().clipToBounds()) { drawWdpMap(picture, if (paper) null else theater, base, tm, light || paper, paper) }
}

/** Draws [pic] over the theater map into the whole of this scope, which stands for picSatView's 435 pixels. */
private fun DrawScope.drawWdpMap(
    pic: PopupPlan.MapPicture?, theater: Theater?, base: MapBaseState, tm: TextMeasurer, light: Boolean, paper: Boolean = false,
    caption: String? = null,
) {
    drawRect(if (paper) Color.White else Color.Black)
    if (pic == null) return
    val w = size.width
    if (theater != null && pic.spanFt > 0.0) {
        // the whole theater at the scale that makes the plan's span fill the box, placed so the crop's corner is at 0,0
        val sizeFt = theater.sizeFt
        val side = (w * sizeFt / pic.spanFt).toFloat()
        val left = (-pic.upperE / sizeFt * side).toFloat()
        val top = (-(1.0 - pic.upperN / sizeFt) * side).toFloat()
        drawMapBase(MapProjection(left, top, side, sizeFt, 1f), base, null)
    }
    val k = w / pic.size
    // WDP's GenericSansSerif and Arial at 10 points are 13.3 of its 435 pixels
    val type = (13.333f / (density * fontScale)).sp
    // with the one attack drawing (1.3.8): WDP's route and threats, then the attack as every map draws it in place of
    // WDP's own marks; the route's "IP" square is a plain steerpoint there, the VIP cue being the only IP mark (B5)
    val attack = pic.attack
    val under = if (attack != null && pic.underlay >= 0) pic.underlay else pic.items.size
    withTransform({ scale(k, k, Offset.Zero) }) {
        for ((idx, it0) in pic.items.withIndex()) {
            if (idx >= under) break
            val it = if (attack != null && idx == pic.ipSquare && it0 is PopupPlan.MapItem.Rect) PopupPlan.MapItem.Ellipse(it0.x, it0.y, it0.w, it0.h, it0.color) else it0
            when (it) {
                is PopupPlan.MapItem.Line -> drawLine(ink(it.color, light), Offset(it.x1 + 0.5f, it.y1 + 0.5f), Offset(it.x2 + 0.5f, it.y2 + 0.5f), 1f)
                is PopupPlan.MapItem.Rect -> drawRect(ink(it.color, light), Offset(it.x + 0.5f, it.y + 0.5f), Size(it.w.toFloat(), it.h.toFloat()), style = Stroke(1f))
                // a red ellipse is a threat's ring (WDP's PPT red): drawn heavier, so it holds on every map style
                is PopupPlan.MapItem.Ellipse -> drawOval(ink(it.color, light), Offset(it.x + 0.5f, it.y + 0.5f), Size(it.w.toFloat(), it.h.toFloat()), style = Stroke(if (it.color == "Red") 1.8f else 1f))
                // GDI+ measures a pie's angles clockwise from three o'clock, as Compose does
                is PopupPlan.MapItem.Pie -> drawArc(
                    ink(it.color, light), it.start.toFloat(), it.sweep.toFloat(), true,
                    Offset(it.x + 0.5f, it.y + 0.5f), Size(it.w.toFloat(), it.h.toFloat()), style = Stroke(1f),
                )
                is PopupPlan.MapItem.Polygon -> if (it.points.isNotEmpty()) {
                    val path = Path()
                    it.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.first + 0.5f, p.second + 0.5f) else path.lineTo(p.first + 0.5f, p.second + 0.5f) }
                    path.close()
                    drawPath(path, ink(it.color, light), style = Stroke(1f))
                }
                is PopupPlan.MapItem.Text -> if (it.text.isNotEmpty()) {
                    // measured unbounded, then placed: drawText given only a position fits the text into what is left of
                    // the canvas, which is in screen pixels while the marks are in WDP's — on a phone the box is narrower
                    // than WDP's picture, and a label near its right edge had less than no room and threw
                    drawText(tm.measure(it.text, TextStyle(color = ink(it.color, light), fontSize = type)), topLeft = Offset(it.x.toFloat(), it.y.toFloat()))
                }
            }
        }
        if (attack != null && pic.spanFt > 0.0) {
            val f = pic.size / pic.spanFt
            val inks = AttackInks.of(light)
            val halo = androidx.compose.ui.graphics.Shadow(inks.halo, Offset.Zero, 3f)
            drawAttackModel(attack, { n, e -> Offset(((e - pic.upperE) * f).toFloat(), ((pic.upperN - n) * f).toFloat()) }, inks) { text, at, color ->
                drawText(tm.measure(text, TextStyle(color = color, fontSize = type, shadow = halo)), topLeft = at)
            }
        }
        // the caption along the foot: the attack (profile · VIP from STPT n / VRP · TGT STPT n) and what the target
        // steerpoint is, set smaller rather than cut when it is long
        val caption = if (attack == null) caption else
            (AttackDrawing.caption(attack) + (caption?.takeIf { it.startsWith("STPT ") && " · " in it }?.substringAfter(" · ")?.let { " · $it" } ?: ""))
        if (!caption.isNullOrBlank()) {
            val room = pic.size - 12f
            var laid = tm.measure(caption, TextStyle(color = ink("White", light), fontSize = type), softWrap = false, maxLines = 1)
            if (laid.size.width > room) {
                laid = tm.measure(caption, TextStyle(color = ink("White", light), fontSize = type * (room / laid.size.width)), softWrap = false, maxLines = 1)
            }
            val top = pic.size - laid.size.height - 6f
            drawRect(
                if (light) Color.White.copy(alpha = 0.78f) else Color.Black.copy(alpha = 0.62f),
                Offset(3f, top - 2f), Size(laid.size.width + 6f, laid.size.height + 4f),
            )
            drawText(laid, topLeft = Offset(6f, top))
        }
    }
}

/** A WinForms colour name as the map draws it; the plan's white ink turns black on the light Chart style. */
private fun ink(name: String, light: Boolean): Color = when (name) {
    "White" -> if (light) Color.Black else Color.White
    "Black" -> Color.Black
    "Blue" -> Color(0xFF0000FF)
    "Red" -> Color(0xFFFF0000)
    "LightBlue" -> Color(0xFFADD8E6)
    "Magenta" -> Color(0xFFFF00FF)
    "Lime" -> Color(0xFF00FF00)
    "DodgerBlue" -> Color(0xFF1E90FF)
    "Yellow" -> Color(0xFFFFFF00)
    else -> Color.White
}

/**
 * A PNG from a picture, in plain Kotlin: the app has no image encoder that runs on Android, the PC and the browser
 * alike. The pixels are stored rather than compressed (a 435-pixel map is under a megabyte that way), which every
 * reader accepts.
 */
/**
 * A picture the Planner drew, as WDP saves its pictures: JPEG (`ImageFormat.Jpeg`), through the platform's own encoder
 * ([Platform.encodeJpeg]); PNG bytes where there is none (the checks' own runs), which picture viewers open alike.
 */
internal object WdpPicture {
    fun jpeg(img: ImageBitmap): ByteArray = Platform.encodeJpeg?.invoke(img, 90) ?: WdpPng.encode(img)
}

internal object WdpPng {
    fun encode(img: ImageBitmap): ByteArray {
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        img.readPixels(px)
        // each row: filter type 0, then RGBA
        val raw = ByteArray(h * (1 + w * 4))
        var o = 0
        for (y in 0 until h) {
            raw[o++] = 0
            for (x in 0 until w) {
                val c = px[y * w + x]
                raw[o++] = (c shr 16).toByte(); raw[o++] = (c shr 8).toByte(); raw[o++] = c.toByte(); raw[o++] = (c ushr 24).toByte()
            }
        }
        val out = Bytes()
        out.put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = Bytes().apply { int(w); int(h); put(byteArrayOf(8, 6, 0, 0, 0)) }
        out.chunk("IHDR", ihdr.toArray())
        out.chunk("IDAT", zlibStored(raw))
        out.chunk("IEND", ByteArray(0))
        return out.toArray()
    }

    /** A zlib stream of stored (uncompressed) deflate blocks. */
    private fun zlibStored(data: ByteArray): ByteArray {
        val b = Bytes()
        b.put(byteArrayOf(0x78, 0x01))
        var i = 0
        while (true) {
            val n = minOf(65535, data.size - i)
            val last = i + n >= data.size
            val nn = n.inv() and 0xFFFF
            b.put(byteArrayOf(if (last) 1 else 0, (n and 0xFF).toByte(), (n shr 8).toByte(), (nn and 0xFF).toByte(), (nn shr 8).toByte()))
            b.put(data, i, n)
            i += n
            if (last) break
        }
        var a = 1L
        var s = 0L
        for (x in data) { a = (a + (x.toInt() and 0xFF)) % 65521; s = (s + a) % 65521 }
        b.int(((s shl 16) or a).toInt())
        return b.toArray()
    }

    private val crcTable = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
        c
    }

    private class Bytes {
        private var buf = ByteArray(1 shl 16)
        private var len = 0
        private fun room(n: Int) { if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n)) }
        fun put(b: ByteArray, from: Int = 0, n: Int = b.size) { room(n); b.copyInto(buf, len, from, from + n); len += n }
        fun int(v: Int) = put(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
        fun toArray() = buf.copyOf(len)
        fun chunk(type: String, data: ByteArray) {
            int(data.size)
            val t = type.encodeToByteArray()
            put(t); put(data)
            var c = -1
            for (x in t) c = crcTable[(c xor x.toInt()) and 0xFF] xor (c ushr 8)
            for (x in data) c = crcTable[(c xor x.toInt()) and 0xFF] xor (c ushr 8)
            int(c.inv())
        }
    }
}
