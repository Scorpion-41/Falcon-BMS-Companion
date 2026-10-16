package com.bmscompanion.app.ui.screens.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.mission.LinkState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.MissionWeather
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.data.wdp.WdpCoords
import com.bmscompanion.app.data.weather.Astro
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The Mission weather page's **Sun & Moon** (1.4.1): sunrise, sunset and civil twilight, moonrise, moonset and the
 * moon's phase for the mission's date at the take-off field, worked out by [Astro] on the device.
 *
 * The date is the one BMS's own sky is at: the save's `.twx` keeps the zulu date at its clock (BMS's `Weather` moves it
 * on as the clock passes midnight zulu), so the date at the take-off is that one moved by the days between the two.
 * Local time is BMS's own zone for the theater (`weather.ini`'s `TimeZone`, the "l" of its briefing), else the
 * longitude's. The place is the take-off field, else the middle of the theater, in BMS's own latitude and longitude
 * (the Planner's conversion, `WdpCoords`/`PopupCoords`).
 */

/** What the block shows, worked out once per answer. */
internal class MissionSky(
    val sm: Astro.SunMoon,
    /** "the take-off field, Gunsan AB" or "the middle of the theater" */
    val where: String,
    /** BMS's own briefing figures for the date (zulu hours), at its theater point; null without weather.ini */
    val bms: Pair<Double, Double>?,
    val bmsPoint: Pair<Int, Int>?,
    /** the zone came from weather.ini (BMS's own), not the longitude */
    val bmsZone: Boolean,
)

internal object MissionSkies {
    /** The mission's sky, or null where the save's weather file names no date (an older PC, an old file). */
    fun of(w: MissionWeather, theater: Theater?, printedZone: Double? = null): MissionSky? {
        val twx = w.twx ?: return null
        val date = Astro.validDate(twx.year, twx.month, twx.day) ?: return null
        val dayMs = 86_400_000L
        val twxClock = twx.clock ?: w.clock ?: return null
        val takeoff = w.places.firstOrNull { it.label.startsWith("Take", ignoreCase = true) }
        val at = takeoff?.ms?.takeIf { it > 0 } ?: w.clock ?: twxClock
        val utDate = Astro.plusDays(date, (floor(at.toDouble() / dayMs) - floor(twxClock.toDouble() / dayMs)).toInt())
        val utHours = ((at % dayMs + dayMs) % dayMs) / 3_600_000.0
        // where: the take-off field, else the middle of the theater; BMS's latitude and longitude, else weather.ini's point
        val coords = WdpCoords.coordData(theater)?.takeIf { it.enableNewTerrain }
        fun ll(n: Double, e: Double): Pair<Double, Double>? = coords?.let {
            runCatching { PopupCoords.convertSimXYToLatLon(it.tm, n.toFloat(), e.toFloat()) }.getOrNull()
        }?.takeIf { (la, lo) -> la.isFinite() && lo.isFinite() && abs(la) <= 90 && abs(lo) <= 180 }
        val half = (theater?.sizeFt ?: 0.0) / 2
        val (pos, where) = takeoff?.let { t -> ll(t.x, t.y)?.let { it to ("the take-off field" + (t.name?.let { n -> ", $n" } ?: "")) } }
            ?: ll(half, half)?.let { it to "the middle of the theater" }
            ?: w.sky?.let { (it.lat.toDouble() to it.lon.toDouble()) to "BMS's point for the theater" }
            ?: return null
        val tz = w.sky?.timeZone?.toDouble() ?: printedZone ?: (pos.second / 15.0).roundToInt().toDouble()
        val localDate = Astro.plusDays(utDate, floor((utHours + tz) / 24.0).toInt())
        val sm = Astro.sunMoon(localDate, tz, pos.first, pos.second, Astro.jd(utDate, utHours))
        val bmsPoint = w.sky?.let { it.lat to it.lon }
        // BMS works its figure out for the zulu date its sky is at (the take-off's)
        val bms = bmsPoint?.let { (la, lo) -> Astro.bmsSun(utDate, la, lo) }
        return MissionSky(sm, where, bms, bmsPoint, w.sky != null || printedZone != null)
    }

    /**
     * The zone a printed briefing's "Sunrise: 00:00:00z(09:00:00l)" shows (BMS prints it even when the time is zero),
     * hours east of zulu; null when there is none to read.
     */
    private val ZONE = Regex("""(\d{1,2}):(\d{2}):\d{2}z\((\d{1,2}):(\d{2}):\d{2}l\)""")

    fun printedZone(sunrise: String?): Double? {
        val m = ZONE.find(sunrise ?: return null) ?: return null
        val (zh, zm, lh, lm) = m.destructured
        var d = (lh.toInt() * 60 + lm.toInt()) - (zh.toInt() * 60 + zm.toInt())
        while (d <= -12 * 60) d += 1440
        while (d > 14 * 60) d -= 1440
        return d / 60.0
    }

    /** "06:18L (21:18z)" for local hours [h] of the sky's day; "none that day" for none. */
    fun both(s: Astro.SunMoon, h: Double?): String = h?.let { Astro.hm(it.coerceAtMost(23.99)) + "L (" + Astro.hm(it - s.tz) + "z)" } ?: "none that day"

    fun short(h: Double?): String = h?.let { Astro.hm(it.coerceAtMost(23.99)) } ?: "—"

    /** "20 Sep 2026". */
    fun dateText(d: Astro.Ymd): String =
        "${d.d} ${listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[d.m - 1]} ${d.y}"

    /** "UTC+9" */
    fun zoneText(tz: Double): String = "UTC" + (if (tz >= 0) "+" else "−") + abs(tz).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
}

/** The panel's Sun & Moon block. */
@Composable
internal fun SunMoonBlock(w: MissionWeather, theater: Theater?, printedSunrise: String? = null) {
    val sky = remember(w, theater, printedSunrise) { runCatching { MissionSkies.of(w, theater, MissionSkies.printedZone(printedSunrise)) }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (sky == null) {
            Text("SUN & MOON", color = Hud.TextFaint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Text(
                "The save's weather file names no date (an older PC or an older file), so the sun and the moon are not worked out.",
                color = Hud.TextFaint, fontSize = 11.sp, lineHeight = 14.sp,
            )
            return@Column
        }
        val s = sky.sm
        Text(
            "SUN & MOON · ${MissionSkies.dateText(s.date).uppercase()} · ${MissionSkies.zoneText(s.tz)}",
            color = Hud.TextFaint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
        )
        val mono = FontFamily.Monospace
        @Composable
        fun line(label: String, value: String, ink: Color = Hud.Text) = Row {
            Text(label, Modifier.width(88.dp), color = Hud.TextDim, fontSize = 12.sp)
            Text(value, color = ink, fontSize = 12.sp, fontFamily = mono)
        }
        when (s.midnightSun) {
            true -> line("Sun", "up all day")
            false -> line("Sun", "below the horizon all day")
            null -> {
                line("Sunrise", MissionSkies.both(s, s.sunrise), Color(0xFFFFD54F))
                line("Sunset", MissionSkies.both(s, s.sunset), Color(0xFFFFB74D))
            }
        }
        if (s.dawn != null || s.dusk != null) line("Civil twilight", "${MissionSkies.short(s.dawn)}–${MissionSkies.short(s.dusk)}L")
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoonGlyph(s.moon, south = s.lat < 0, size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("${s.moon.phase.label}, ${(s.moon.illumination * 100).roundToInt()} % lit", color = Hud.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Text("age ${((s.moon.ageDays * 10).roundToInt() / 10.0)} days", color = Hud.TextFaint, fontSize = 10.5.sp)
            }
        }
        line("Moonrise", MissionSkies.both(s, s.moonrise))
        line("Moonset", MissionSkies.both(s, s.moonset))
        val bmsWords = sky.bms?.let { (r, st) ->
            " BMS's own briefing prints ${Astro.hm(r)}z / ${Astro.hm(st)}z for this date: it works them out for one point of the theater " +
                "(${sky.bmsPoint!!.first}°, ${sky.bmsPoint.second}°) and without the equation of time, so it can differ by a few minutes."
        } ?: ""
        Text(
            "At ${sky.where}, for the mission's date as BMS's sky has it (the save's weather file), " +
                (if (sky.bmsZone) "local time as BMS prints it" else "local time by the longitude") + "; the moon's phase at the take-off. " +
                "BMS draws the moon from the same date, so its phase is the real one." + bmsWords,
            color = Hud.TextFaint, fontSize = 10.5.sp, lineHeight = 13.sp,
        )
    }
}

/**
 * The moon as it looks: the dark disc, and the lit part bounded by the limb and the terminator (a half ellipse whose
 * width follows the lit fraction). Waxing lit on the right in the northern hemisphere, mirrored in the southern.
 */
@Composable
internal fun MoonGlyph(m: Astro.MoonState, south: Boolean, size: Dp) {
    Canvas(Modifier.size(size)) { drawMoon(m, south, Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension / 2 - 1f) }
}

internal fun DrawScope.drawMoon(m: Astro.MoonState, south: Boolean, c: Offset, r: Float) {
    drawCircle(Color(0xFF2A3038), r, c)
    val k = m.illumination.toFloat().coerceIn(0f, 1f)
    val right = m.waxing != south
    val side = if (right) 1f else -1f
    val e = r * (1f - 2f * k)
    val p = Path()
    val steps = 24
    for (i in 0..steps) {
        val t = -PI.toFloat() / 2 + PI.toFloat() * i / steps
        val x = c.x + side * r * cos(t); val y = c.y + r * sin(t)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    for (i in steps downTo 0) {
        val t = -PI.toFloat() / 2 + PI.toFloat() * i / steps
        p.lineTo(c.x + side * e * cos(t), c.y + r * sin(t))
    }
    p.close()
    if (k > 0.005f) drawPath(p, Color(0xFFF3EBC8))
    drawCircle(Color(0xFF9AA4AE), r, c, style = Stroke(1f))
}

/**
 * The Briefing's and Dashboard's Weather card: one line of sun and moon, from the mission's weather on the PC (nothing
 * on paper or a VR board, nothing without the PC or a date).
 */
@Composable
fun SunMoonLine() {
    if (Hud.onPaper || com.bmscompanion.app.ui.Kneeboard.on) return
    val link by MissionLink.state.collectAsState()
    val mission by MissionLink.mission.collectAsState()
    val online = link is LinkState.Online
    val sky by produceState<MissionSky?>(null, online, mission?.briefingModified, mission?.populated?.at) {
        if (!online) { value = null; return@produceState }
        val w = runCatching { MissionLink.missionWeather().value }.getOrNull()?.takeIf { it.ok } ?: return@produceState
        val all = runCatching { Repo.index().theaters }.getOrNull().orEmpty()
        val th = w.appTheater?.let { id -> all.firstOrNull { it.id == id } }
            ?: w.theater.takeIf { it.isNotBlank() }?.let { com.bmscompanion.app.ui.screens.mission.resolveTheater(all, it) }
        value = runCatching { MissionSkies.of(w, th, MissionSkies.printedZone(mission?.briefing?.overview?.sunrise)) }.getOrNull()
    }
    val s = sky?.sm ?: return
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        MoonGlyph(s.moon, south = s.lat < 0, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        val sun = when (s.midnightSun) {
            true -> "Sun up all day"
            false -> "Sun down all day"
            null -> "Sun ${MissionSkies.short(s.sunrise)}–${MissionSkies.short(s.sunset)}L"
        }
        Text(
            "$sun · ${s.moon.phase.label} ${(s.moon.illumination * 100).roundToInt()} % · moon ${MissionSkies.short(s.moonrise)}–${MissionSkies.short(s.moonset)}L",
            color = Hud.TextDim, fontSize = 11.5.sp, maxLines = 1,
        )
    }
}
