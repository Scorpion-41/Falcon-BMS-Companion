package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.CampDepartures
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.wdp.DataCardPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One line of WDP's Airport Schedule: its six columns, and whether it is the planned flight (WDP underlines it). */
internal class ScheduleRow(
    val time: String, val aircraft: String, val callsign: String, val squad: String, val pack: String, val mission: String,
    val own: Boolean = false,
)

/**
 * What the Airport Schedule (`fclsAptSchedule`) shows: the field it is for ("Departures from …"), its departures in
 * WDP's order, and [note], a line under them saying where they came from when that is not a save.
 */
internal class AptSchedule(val field: String, val rows: List<ScheduleRow>, val note: String? = null) {
    companion object {
        /** A save's departures as WDP prints them: `GetTimeDay`, the aircraft cut to 15 characters, the rest as they are. */
        fun of(d: CampDepartures): AptSchedule = AptSchedule(d.field, d.rows.map { r ->
            ScheduleRow(DataCardPlan.getTimeDay(r.depart), r.aircraft.take(15), r.callsign, r.squadron, r.packageNumber?.toString() ?: "", r.mission, r.own)
        })

        /**
         * With only a printed briefing, and no save found that holds its flight: the briefing's own package, by
         * take-off time. The briefing prints no day, no squadron and no other package, and [note] says so.
         */
        fun ofBriefing(b: Briefing, field: String): AptSchedule {
            val own = b.overview.flight
            val pack = b.overview.packageId?.let { Regex("\\d+").find(it)?.value } ?: ""
            val rows = b.`package`.mapNotNull { f ->
                val t = f.takeoff?.trim()?.removeSuffix("z")?.takeIf { it.contains(':') } ?: return@mapNotNull null
                ScheduleRow(t, (f.aircraft ?: "").take(15), f.callsign, "", pack, f.role ?: f.task ?: "", f.callsign.equals(own, ignoreCase = true))
            }.sortedBy { it.time }
            return AptSchedule(
                field, rows,
                note = "From the printed briefing: its own package only. The save that holds this flight lists every " +
                    "flight from the field (Open mission… and pick it, or save the campaign in BMS).",
            )
        }
    }
}

/**
 * `fclsAptSchedule`: WDP draws the schedule as a picture (Arial 12 on a 600-pixel-wide sheet, stretched over its
 * panel); the app draws the same sheet — the title, the six columns at WDP's own offsets, the planned flight
 * underlined — scaled to the panel, and scrolls it where a long schedule runs past the panel's foot. [later], when
 * given, is the schedule looked up after the window opened (the save that holds the printed briefing's flight): the
 * window shows [first] until it comes.
 *
 * **Save JPG** (`btnSaveJpg_Click`) is WDP's: its mission-and-package window, then the folders
 * DataCards\<mission>\<package>\<callsign> made and WDP's "Save Airport Schedule" window there on
 * `<field>Schedule.jpg` (`Picture (*.jpg)`), on the BMS PC ([WdpFiles]); the sheet is drawn at WDP's own 600 pixels and
 * written as a JPEG. **Fixed:** WDP writes the picture to the name it suggested, whatever name the pilot chose in its
 * window (`bitmap.Save(text4, …)`); here it goes where the pilot saved it. [card] is the DataCard's mission, package
 * and callsign, which the window's answer also sets on the card, as WDP's does.
 */
internal class AptScheduleWindow(first: AptSchedule, later: (suspend () -> AptSchedule?)? = null, private val card: ScheduleCard? = null) :
    CardWindow("fclsAptSchedule", "Airport Schedule"), WdpControlContent {
    private var sheet by mutableStateOf(first)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** Set while the sheet is on screen: the sheet drawn off screen at WDP's 600 pixels, for Save JPG. */
    internal var snapshot: (() -> androidx.compose.ui.graphics.ImageBitmap)? = null

    /** What Save JPG needs from the DataCard: the mission and package WDP's window starts from, the callsign, and where the answer goes. */
    class ScheduleCard(val mission: String, val pack: String, val callsign: String, val set: (mission: String, pack: String) -> Unit)

    init {
        if (card == null) v["btnSaveJpg"] = "hidden"
        if (later != null) scope.launch { runCatching { later() }.getOrNull()?.let { sheet = it; version++ } }
    }

    override fun controlContent(): Map<String, @Composable () -> Unit> = mapOf("pnlSchedule" to {
        val tm = androidx.compose.ui.text.rememberTextMeasurer()
        val now = sheet
        androidx.compose.runtime.DisposableEffect(now, tm) {
            snapshot = { scheduleImage(now, tm) }
            onDispose { snapshot = null }
        }
        ScheduleSheet(now)
    })

    override fun click(name: String) {
        when (name) {
            "btnClose" -> { scope.cancel(); close() }
            "btnSaveJpg" -> {
                val c = card ?: return
                PackageNrWindow(c.mission, c.pack, "Save Airport Schedule") { mis, pkg ->
                    c.set(mis, pkg)
                    val img = runCatching { (snapshot ?: { scheduleImage(sheet, null) }).invoke() }.getOrNull()
                    if (img == null) {
                        WdpDialogs.message("Save Airport Schedule", "The schedule could not be drawn to a picture.")
                    } else {
                        scope.launch { saveJpg(WdpPicture.jpeg(img), mis, pkg, c.callsign) }
                    }
                }.open()
            }
        }
    }

    /** The folders and the window of Save JPG, and the write: the file's path, or null when nothing was saved. */
    internal suspend fun saveJpg(bytes: ByteArray, mission: String, pack: String, callsign: String): String? {
        fun sub(base: String, n: String) = if (n.isBlank()) base else base + "\\" + n.trim()
        val packageDir = sub(sub(WdpFiles.DATACARDS, mission), pack)
        val dir = if (callsign.isBlank()) packageDir else sub(packageDir, callsign)
        WdpFiles.folder(dir)
        val field = sheet.field.substringBefore(" (").replace("Airbase", "").replace("Highwaystrip", "").replace("Airstrip", "")
        val f = WdpFiles.save("Save Airport Schedule", dir, "Picture (*.jpg)|*.jpg", field + "Schedule.jpg", defaultExt = "jpg", makeFolder = false) ?: return null
        if (!WdpFiles.writeBytes(f, bytes, "Save Airport Schedule")) return null
        return f.path
    }
}

/**
 * The schedule as WDP's picture: a white sheet 600 pixels wide (at least 1,200 high, as WDP's bitmap), Arial 12 and the
 * title in Arial 16 bold, the six columns at WDP's offsets and a rule under the planned flight — [ScheduleSheet]'s
 * layout, drawn at one pixel to its unit. Without [tm] (no window on screen) the text is left out.
 */
internal fun scheduleImage(s: AptSchedule, tm: androidx.compose.ui.text.TextMeasurer?): androidx.compose.ui.graphics.ImageBitmap {
    val line = 18.4f
    val rowsH = if (s.rows.isEmpty()) line * 2f else line * 2f + s.rows.size * line * 1.5f
    val h = maxOf(1200, (line * 3f + rowsH + line * 4f).toInt())
    val img = androidx.compose.ui.graphics.ImageBitmap(600, h)
    val canvas = androidx.compose.ui.graphics.Canvas(img)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr, canvas,
        androidx.compose.ui.geometry.Size(600f, h.toFloat()),
    ) {
        drawRect(Color.White)
        if (tm == null) return@draw
        val body = TextStyle(color = Color.Black, fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp), fontFamily = FontFamily.SansSerif)
        val bold = body.copy(fontWeight = FontWeight.Bold)
        val title = body.copy(fontSize = androidx.compose.ui.unit.TextUnit(21.3f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = FontWeight.Bold)
        fun text(t: String, x: Float, y: Float, st: TextStyle) {
            if (t.isEmpty()) return
            drawText(tm.measure(t, st, softWrap = false, maxLines = 1), topLeft = androidx.compose.ui.geometry.Offset(x, y))
        }
        val xs = SCHEDULE_WIDTHS.runningFold(12f) { a, w -> a + w }
        fun cells(values: List<String>, y: Float, st: TextStyle) = values.forEachIndexed { i, t -> text(t, xs.getOrElse(i) { xs.last() }, y, st) }
        var y = line
        text("Departures from ${s.field}".trimEnd(), 12f, y, title)
        y += line * 2f
        if (s.rows.isEmpty()) {
            text("Currently no flights planned from ${s.field}".trimEnd(), 12f, y, body)
            return@draw
        }
        cells(listOf("Dep Time", "Aircraft", "Callsign", "Squad", "Pack", "Mission"), y, bold)
        y += line * 2f
        for (r in s.rows) {
            cells(listOf(r.time, r.aircraft, r.callsign, r.squad, r.pack, r.mission), y, body)
            if (r.own) drawRect(Color.Black, topLeft = androidx.compose.ui.geometry.Offset(18f, y + line), size = androidx.compose.ui.geometry.Size(600f - 36f, 1f))
            y += line * 1.5f
        }
    }
    return img
}

/** WDP's schedule picture, drawn: its units are the 600-pixel sheet's, `u` dp each. */
@Composable
private fun ScheduleSheet(s: AptSchedule) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)) {
        val u: Dp = maxWidth / 600f
        val density = LocalDensity.current
        fun sp(px: Float) = with(density) { (u * px).toSp() }
        // Arial 12 pt is 16 px on WDP's sheet, its line 18.4; the title is Arial 16 bold
        val body = TextStyle(color = Color.Black, fontSize = sp(16f), fontFamily = FontFamily.SansSerif)
        val bold = body.copy(fontWeight = FontWeight.Bold)
        val title = body.copy(fontSize = sp(21.3f), fontWeight = FontWeight.Bold)
        val line = u * 18.4f
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(line))
            Box(Modifier.height(line * 2f).padding(start = u * 12f)) {
                Text("Departures from ${s.field}".trimEnd(), style = title, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
            }
            if (s.rows.isEmpty()) {
                Box(Modifier.height(line * 2f).padding(start = u * 12f)) {
                    Text("Currently no flights planned from ${s.field}".trimEnd(), style = body, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                }
            } else {
                Box(Modifier.height(line * 2f)) { ScheduleCells(listOf("Dep Time", "Aircraft", "Callsign", "Squad", "Pack", "Mission"), bold, u) }
                for (r in s.rows) {
                    Box(Modifier.fillMaxWidth().height(line * 1.5f)) {
                        ScheduleCells(listOf(r.time, r.aircraft, r.callsign, r.squad, r.pack, r.mission), body, u)
                        // WDP rules a line under the planned flight, 1.5 font sizes in from either side
                        if (r.own) Box(
                            Modifier.offset(y = line).padding(start = u * 18f, end = u * 18f).fillMaxWidth().height(1.dp).background(Color.Black),
                        )
                    }
                }
            }
            s.note?.let {
                Spacer(Modifier.height(line * 0.5f))
                Text(it, style = body.copy(fontSize = sp(13f), color = Color(0xFF444444)), modifier = Modifier.padding(start = u * 12f, end = u * 12f))
            }
            Spacer(Modifier.height(line))
        }
    }
}

/** One line of the sheet: its columns' left edges are WDP's — 12, then 8, 13, 9, 5 and 5 font sizes (12 px) on. */
@Composable
private fun ScheduleCells(values: List<String>, style: TextStyle, u: Dp) {
    Row(Modifier.fillMaxWidth().padding(start = u * 12f)) {
        values.forEachIndexed { i, t ->
            val w = SCHEDULE_WIDTHS.getOrNull(i)
            Box(if (w != null) Modifier.width(u * w) else Modifier.weight(1f)) {
                Text(t, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
            }
        }
    }
}

private val SCHEDULE_WIDTHS = listOf(96f, 156f, 108f, 60f, 60f)

/**
 * The save that holds the printed briefing's flight, for what WDP reads out of the campaign file it has loaded and a
 * printed briefing does not carry: the DataCard's **Current Time** (the save's clock) and the **Airport Schedule**
 * (the save's flights from the field). The PC marks the saves that hold the briefed flight (`CampFile.briefed`: its
 * callsign, package and flight number agree); the newest one is taken. Looked up once per printed briefing ([key]) and
 * kept; a PC that could not be reached is asked again the next time. Everything here runs on the main thread.
 */
internal object BriefedSave {
    class Found(val theater: String, val file: String, val clock: Long?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val found = HashMap<String, Found?>()
    private val asking = HashMap<String, Deferred<Found?>>()
    private val flights = HashMap<String, CampFlight>()

    /** Which printed briefing [b] is: its flight, package and mission, and when BMS printed it. */
    fun key(b: Briefing): String = listOf(b.overview.flight, b.overview.packageId, b.overview.mission, b.generated).joinToString("|")

    /** The save found for [key], when it has been looked up and there is one. */
    fun known(key: String): Found? = found[key]

    suspend fun find(key: String): Found? {
        if (found.containsKey(key)) return found[key]
        val d = asking.getOrPut(key) { scope.async { look(key) } }
        return try { d.await() } finally { asking.remove(key) }
    }

    private suspend fun look(key: String): Found? {
        val a = runCatching { MissionLink.campaignFiles() }.getOrNull() ?: return null
        if (!a.reached) return null
        val hit = a.value?.theaters.orEmpty()
            .flatMap { t -> t.files.filter { it.briefed && !it.start }.map { t to it } }
            .maxByOrNull { it.second.sortTime }
        val f = hit?.let { (t, file) -> Found(t.name, file.name, file.clock) }
        found[key] = f
        return f
    }

    /** That save's flight — the printed briefing's — as `/api/campaign/flight` gives it, or null. */
    suspend fun flight(key: String): CampFlight? {
        flights[key]?.let { return it }
        val f = find(key) ?: return null
        val ato = runCatching { MissionLink.campaignAto(f.theater, f.file) }.getOrNull()?.value ?: return null
        val row = ato.packages.flatMap { it.flights }.firstOrNull { it.briefed } ?: return null
        val flight = runCatching { MissionLink.campaignFlight(f.theater, f.file, row.id) }.getOrNull()?.value ?: return null
        flights[key] = flight
        return flight
    }
}
