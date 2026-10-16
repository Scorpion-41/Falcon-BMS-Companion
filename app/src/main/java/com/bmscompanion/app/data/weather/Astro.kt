package com.bmscompanion.app.data.weather

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * The sun and the moon for a mission (1.4.1, the Mission weather page's **Sun & Moon**): sunrise, sunset and civil
 * twilight, moonrise and moonset, the moon's phase and how much of it is lit — for a date and a place, worked out the
 * way an almanac does. Pure Kotlin, shared by the app, the PC and the browser.
 *
 * - The sun: Meeus, *Astronomical Algorithms* ch. 25, low accuracy (0.01°), apparent longitude; NOAA's figures.
 * - The moon: Meeus ch. 47 with the larger terms of tables 47.A and 47.B (a few arc minutes), and its horizontal
 *   parallax from the distance, so a moonrise is good to a minute or two.
 * - Rising and setting: the altitude over the local day sampled every 10 minutes and each crossing bisected; the sun's
 *   horizon is -0.833° (refraction and the half disc), civil twilight -6°, the moon's 0.7275 x parallax - 0.5667°.
 *
 * Times are **local hours after the local date's midnight** (0-24, the zone [SunMoon.tz] hours east of Greenwich);
 * zulu is that less the zone. Longitude east is positive.
 *
 * **What BMS itself prints** ([bmsSun]): BMS 4.38's briefing "Sunrise/Sunset" (`Weather::ComputeSunMoonTime`, read out
 * of `Falcon BMS.exe` with its pdb) uses one point per theater — the whole degrees of `<terraindir>\Weather\weather.ini`
 * (`Latitude`, `Longitude`, `TimeZone`) — and the hour angle about 12:00 local *mean* time, with no equation of time,
 * so its figure can be up to a quarter of an hour from the almanac's at the airfield. BMS draws the moon from the same
 * date (`GetMoonRaDec`, `ComputeMoonPhase`), so its phase is the real one for that date.
 */
object Astro {
    private const val RAD = PI / 180.0
    private const val DEG = 180.0 / PI

    /** A calendar date. */
    data class Ymd(val y: Int, val m: Int, val d: Int) {
        override fun toString(): String = "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    /** Julian day of [y]-[m]-[d] at [hoursUt] (Meeus 7.1, Gregorian). */
    fun jd(y: Int, m: Int, d: Int, hoursUt: Double = 0.0): Double {
        var yy = y
        var mm = m
        if (mm <= 2) { yy -= 1; mm += 12 }
        val a = floor(yy / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (yy + 4716)) + floor(30.6001 * (mm + 1)) + d + b - 1524.5 + hoursUt / 24.0
    }

    fun jd(date: Ymd, hoursUt: Double = 0.0): Double = jd(date.y, date.m, date.d, hoursUt)

    /** The calendar date of [jd] (Meeus 7.3), the day's fraction dropped. */
    fun dateOf(jd: Double): Ymd {
        val z = floor(jd + 0.5)
        val alpha = floor((z - 1867216.25) / 36524.25)
        val a = z + 1 + alpha - floor(alpha / 4.0)
        val b = a + 1524
        val c = floor((b - 122.1) / 365.25)
        val d = floor(365.25 * c)
        val e = floor((b - d) / 30.6001)
        val day = (b - d - floor(30.6001 * e)).toInt()
        val month = (if (e < 14) e - 1 else e - 13).toInt()
        val year = (if (month > 2) c - 4716 else c - 4715).toInt()
        return Ymd(year, month, day)
    }

    /** [date] moved by [days]. */
    fun plusDays(date: Ymd, days: Int): Ymd = dateOf(jd(date) + days + 0.5)

    /** A valid calendar date (year 1900-2200), else null. */
    fun validDate(y: Int, m: Int, d: Int): Ymd? {
        if (y !in 1900..2200 || m !in 1..12 || d !in 1..31) return null
        val back = dateOf(jd(y, m, d) + 0.5)
        return if (back.y == y && back.m == m && back.d == d) Ymd(y, m, d) else null
    }

    private fun norm360(x: Double): Double { val r = x % 360.0; return if (r < 0) r + 360.0 else r }

    /** Ecliptic longitude, latitude (deg), distance (km); right ascension and declination (deg). */
    class Body(val lon: Double, val lat: Double, val distKm: Double, val ra: Double, val dec: Double)

    private fun obliquity(t: Double) = 23.439291 - 0.0130042 * t

    private fun equatorial(lon: Double, lat: Double, eps: Double): Pair<Double, Double> {
        val l = lon * RAD; val b = lat * RAD; val e = eps * RAD
        val ra = atan2(sin(l) * cos(e) - tan(b) * sin(e), cos(l)) * DEG
        val dec = asin((sin(b) * cos(e) + cos(b) * sin(e) * sin(l)).coerceIn(-1.0, 1.0)) * DEG
        return norm360(ra) to dec
    }

    /** The sun's apparent place at [jd] (Meeus ch. 25, low accuracy). */
    fun sun(jd: Double): Body {
        val t = (jd - 2451545.0) / 36525.0
        val l0 = norm360(280.46646 + 36000.76983 * t + 0.0003032 * t * t)
        val m = norm360(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val mr = m * RAD
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(mr) + (0.019993 - 0.000101 * t) * sin(2 * mr) + 0.000289 * sin(3 * mr)
        val trueLon = l0 + c
        val e = 0.016708634 - 0.000042037 * t
        val v = mr + c * RAD
        val r = 1.000001018 * (1 - e * e) / (1 + e * cos(v))
        val omega = (125.04 - 1934.136 * t) * RAD
        val lon = norm360(trueLon - 0.00569 - 0.00478 * sin(omega))
        val eps = obliquity(t) + 0.00256 * cos(omega)
        val (ra, dec) = equatorial(lon, 0.0, eps)
        return Body(lon, 0.0, r * 149597870.7, ra, dec)
    }

    // Meeus table 47.A: D, M, M', F, Σl (1e-6 deg), Σr (1e-3 km) — the terms of 2,000e-6 deg and more
    private val LR = intArrayOf(
        0, 0, 1, 0, 6288774, -20905355,
        2, 0, -1, 0, 1274027, -3699111,
        2, 0, 0, 0, 658314, -2955968,
        0, 0, 2, 0, 213618, -569925,
        0, 1, 0, 0, -185116, 48888,
        0, 0, 0, 2, -114332, -3149,
        2, 0, -2, 0, 58793, 246158,
        2, -1, -1, 0, 57066, -152138,
        2, 0, 1, 0, 53322, -170733,
        2, -1, 0, 0, 45758, -204586,
        0, 1, -1, 0, -40923, -129620,
        1, 0, 0, 0, -34720, 108743,
        0, 1, 1, 0, -30383, 104755,
        2, 0, 0, -2, 15327, 10321,
        0, 0, 1, 2, -12528, 0,
        0, 0, 1, -2, 10980, 79661,
        4, 0, -1, 0, 10675, -34782,
        0, 0, 3, 0, 10034, -23210,
        4, 0, -2, 0, 8548, -21636,
        2, 1, -1, 0, -7888, 24208,
        2, 1, 0, 0, -6766, 30824,
        1, 0, -1, 0, -5163, -8379,
        1, 1, 0, 0, 4987, -16675,
        2, -1, 1, 0, 4036, -12831,
        2, 0, 2, 0, 3994, -10445,
        4, 0, 0, 0, 3861, -11650,
        2, 0, -3, 0, 3665, 14403,
        0, 1, -2, 0, -2689, -7003,
        2, 0, -1, 2, -2602, 0,
        2, -1, -2, 0, 2390, 10056,
        1, 0, 1, 0, -2348, 6322,
        2, -2, 0, 0, 2236, -9884,
    )

    // Meeus table 47.B: D, M, M', F, Σb (1e-6 deg) — the terms of 1,500e-6 deg and more
    private val B = intArrayOf(
        0, 0, 0, 1, 5128122,
        0, 0, 1, 1, 280602,
        0, 0, 1, -1, 277693,
        2, 0, 0, -1, 173237,
        2, 0, -1, 1, 55413,
        2, 0, -1, -1, 46271,
        2, 0, 0, 1, 32573,
        0, 0, 2, 1, 17198,
        2, 0, 1, -1, 9266,
        0, 0, 2, -1, 8822,
        2, -1, 0, -1, 8216,
        2, 0, -2, -1, 4324,
        2, 0, 1, 1, 4200,
        2, 1, 0, -1, -3359,
        2, -1, -1, 1, 2463,
        2, -1, 0, 1, 2211,
        2, -1, -1, -1, 2065,
        0, 1, -1, -1, -1870,
        4, 0, -1, -1, 1828,
        0, 1, 0, 1, -1794,
    )

    /** The moon's geocentric place at [jd] (Meeus ch. 47, the larger terms). */
    fun moon(jd: Double): Body {
        val t = (jd - 2451545.0) / 36525.0
        val lp = norm360(218.3164477 + 481267.88123421 * t)
        val d = norm360(297.8501921 + 445267.1114034 * t)
        val m = norm360(357.5291092 + 35999.0502909 * t)
        val mp = norm360(134.9633964 + 477198.8675055 * t)
        val f = norm360(93.2720950 + 483202.0175233 * t)
        val e = 1 - 0.002516 * t - 0.0000074 * t * t
        val a1 = norm360(119.75 + 131.849 * t)
        val a2 = norm360(53.09 + 479264.290 * t)
        val a3 = norm360(313.45 + 481266.484 * t)
        var sl = 0.0
        var sr = 0.0
        var i = 0
        while (i < LR.size) {
            val arg = (LR[i] * d + LR[i + 1] * m + LR[i + 2] * mp + LR[i + 3] * f) * RAD
            val em = when (abs(LR[i + 1])) { 1 -> e; 2 -> e * e; else -> 1.0 }
            sl += LR[i + 4] * em * sin(arg)
            sr += LR[i + 5] * em * cos(arg)
            i += 6
        }
        var sb = 0.0
        i = 0
        while (i < B.size) {
            val arg = (B[i] * d + B[i + 1] * m + B[i + 2] * mp + B[i + 3] * f) * RAD
            val em = when (abs(B[i + 1])) { 1 -> e; 2 -> e * e; else -> 1.0 }
            sb += B[i + 4] * em * sin(arg)
            i += 5
        }
        sl += 3958 * sin(a1 * RAD) + 1962 * sin((lp - f) * RAD) + 318 * sin(a2 * RAD)
        sb += -2235 * sin(lp * RAD) + 382 * sin(a3 * RAD) + 175 * sin((a1 - f) * RAD) + 175 * sin((a1 + f) * RAD) +
            127 * sin((lp - mp) * RAD) - 115 * sin((lp + mp) * RAD)
        val lon = norm360(lp + sl / 1e6)
        val lat = sb / 1e6
        val dist = 385000.56 + sr / 1000.0
        val (ra, dec) = equatorial(lon, lat, obliquity(t))
        return Body(lon, lat, dist, ra, dec)
    }

    /** Greenwich mean sidereal time at [jd], degrees (Meeus 12.4). */
    fun gmst(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        return norm360(280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * t * t - t * t * t / 38710000.0)
    }

    /** Altitude (deg, no refraction) of [b] seen from [lat]/[lon] (east +) at [jd]. */
    fun altitude(b: Body, jd: Double, lat: Double, lon: Double): Double {
        val h = (gmst(jd) + lon - b.ra) * RAD
        val p = lat * RAD
        val dc = b.dec * RAD
        return asin((sin(p) * sin(dc) + cos(p) * cos(dc) * cos(h)).coerceIn(-1.0, 1.0)) * DEG
    }

    /** The moon's rising/setting altitude for its distance: 0.7275 x horizontal parallax - 0.5667°. */
    private fun moonHorizon(b: Body): Double = 0.7275 * asin(6378.14 / b.distKm) * DEG - 0.5667

    /** One kind of crossing over a day: the first rise and the first set (JD), or null where there is none. */
    class Crossings(val rise: Double?, val set: Double?, val alwaysAbove: Boolean, val alwaysBelow: Boolean)

    /** [f] (altitude less the horizon at a JD) crossing zero over [from]..[from]+1 day, sampled every 10 min, bisected. */
    fun crossings(from: Double, f: (Double) -> Double): Crossings {
        val step = 10.0 / 1440.0
        var rise: Double? = null
        var set: Double? = null
        var t0 = from
        var v0 = f(t0)
        var anyAbove = v0 > 0
        var anyBelow = v0 <= 0
        for (k in 1..144) {
            val t1 = from + k * step
            val v1 = f(t1)
            if (v1 > 0) anyAbove = true else anyBelow = true
            if ((v0 <= 0) != (v1 <= 0)) {
                var a = t0; var b = t1; var va = v0
                repeat(18) {
                    val mid = (a + b) / 2
                    val vm = f(mid)
                    if ((va <= 0) == (vm <= 0)) { a = mid; va = vm } else b = mid
                }
                val at = (a + b) / 2
                if (v0 <= 0 && rise == null) rise = at
                if (v0 > 0 && set == null) set = at
            }
            t0 = t1; v0 = v1
        }
        return Crossings(rise, set, anyAbove && !anyBelow, anyBelow && !anyAbove)
    }

    /** The moon's phase, by its elongation in longitude from the sun. */
    enum class MoonPhase(val label: String) {
        NEW("New moon"), WAXING_CRESCENT("Waxing crescent"), FIRST_QUARTER("First quarter"), WAXING_GIBBOUS("Waxing gibbous"),
        FULL("Full moon"), WANING_GIBBOUS("Waning gibbous"), LAST_QUARTER("Last quarter"), WANING_CRESCENT("Waning crescent");

        companion object {
            /** Within 12° (a day) of 0, 90, 180 or 270° the principal phase, between them a crescent or gibbous moon. */
            fun of(elongation: Double): MoonPhase {
                val e = norm360(elongation)
                return when {
                    e < 12 || e >= 348 -> NEW
                    e < 78 -> WAXING_CRESCENT
                    e < 102 -> FIRST_QUARTER
                    e < 168 -> WAXING_GIBBOUS
                    e < 192 -> FULL
                    e < 258 -> WANING_GIBBOUS
                    e < 282 -> LAST_QUARTER
                    else -> WANING_CRESCENT
                }
            }
        }
    }

    /** The moon at one moment: lit fraction 0-1, elongation (deg, 0 new, 180 full), its phase, age in days. */
    class MoonState(val illumination: Double, val elongation: Double, val phase: MoonPhase, val ageDays: Double) {
        val waxing: Boolean get() = elongation < 180.0
    }

    fun moonState(jd: Double): MoonState {
        val s = sun(jd)
        val m = moon(jd)
        val elong = norm360(m.lon - s.lon)
        // phase angle from the geocentric elongation ψ (Meeus 48.2-48.3)
        val psi = kotlin.math.acos((cos(m.lat * RAD) * cos((m.lon - s.lon) * RAD)).coerceIn(-1.0, 1.0))
        val i = atan2(s.distKm * sin(psi), m.distKm - s.distKm * cos(psi))
        val k = (1 + cos(i)) / 2
        return MoonState(k, elong, MoonPhase.of(elong), elong / 360.0 * 29.530589)
    }

    /**
     * Everything for the local date [date] at [lat]/[lon] in the zone [tz] hours east, and the moon at [atJd] (the
     * mission's moment). Times are local hours after midnight (see the class notes).
     */
    class SunMoon(
        val date: Ymd, val tz: Double, val lat: Double, val lon: Double,
        val sunrise: Double?, val sunset: Double?, val dawn: Double?, val dusk: Double?,
        /** true: the sun never sets that day; false: it never rises; null: it does both */
        val midnightSun: Boolean?,
        val moonrise: Double?, val moonset: Double?,
        val moon: MoonState,
    )

    fun sunMoon(date: Ymd, tz: Double, lat: Double, lon: Double, atJd: Double): SunMoon {
        val from = jd(date) - tz / 24.0
        fun local(j: Double?) = j?.let { (it - from) * 24.0 }
        val sun = crossings(from) { j -> altitude(sun(j), j, lat, lon) + 0.8333 }
        val civil = crossings(from) { j -> altitude(sun(j), j, lat, lon) + 6.0 }
        val moon = crossings(from) { j -> val b = moon(j); altitude(b, j, lat, lon) - moonHorizon(b) }
        return SunMoon(
            date, tz, lat, lon,
            local(sun.rise), local(sun.set), local(civil.rise), local(civil.set),
            if (sun.alwaysAbove) true else if (sun.alwaysBelow) false else null,
            local(moon.rise), local(moon.set), moonState(atJd),
        )
    }

    /**
     * The sunrise and sunset Falcon BMS 4.38 prints in a briefing, zulu hours 0-24 (see the class notes): the date's
     * Julian day, the sun's declination by BMS's own low-accuracy series, the hour angle for -0.833° at [latDeg], and
     * 12 h ∓ that ∓ [lonDeg]/15 — no equation of time; [date] is the zulu date BMS's sky is at (the `.twx`'s). BMS
     * prints it cut to the second. Null where the sun does not rise or set.
     */
    fun bmsSun(date: Ymd, latDeg: Int, lonDeg: Int): Pair<Double, Double>? {
        // days from J2000 to the date's 00:00 (as BMS counts them: its two printed briefings at hand agree to the second)
        val n = jd(date) - 2451545.0
        val g = norm360(357.529 + 0.98560028 * n) * RAD
        val q = norm360(280.459 + 0.98564736 * n)
        val l = (q + 1.915 * sin(g) + 0.020 * sin(2 * g)) * RAD
        val e = (23.439 - 0.00000036 * n) * RAD
        val dec = asin(sin(e) * sin(l))
        val lat = latDeg * RAD
        val c = (-0.01448572613298893 - sin(lat) * sin(dec)) / (cos(lat) * cos(dec))
        if (c < -1 || c > 1) return null
        val h = kotlin.math.acos(c) * 12.0 / PI
        val lonH = lonDeg * RAD * 12.0 / PI
        fun wrap(x: Double): Double { var v = x; while (v >= 24) v -= 24; while (v < 0) v += 24; return v }
        return wrap(12 - h - lonH) to wrap(12 + h - lonH)
    }

    /** "05:33" from hours (wrapped into the day), rounded to the minute. */
    fun hm(hours: Double): String {
        var m = kotlin.math.round(hours * 60.0).toInt()
        m = ((m % 1440) + 1440) % 1440
        return (m / 60).toString().padStart(2, '0') + ":" + (m % 60).toString().padStart(2, '0')
    }
}
