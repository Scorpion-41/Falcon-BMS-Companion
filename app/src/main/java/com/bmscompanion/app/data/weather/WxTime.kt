/*
 * Ported from WeatherGen (src/weathergen/time.cljc in Tyrant's Virtual Mission Tools 0.63).
 *
 * MIT License
 *
 * Copyright (c) 2017 Craig Andera
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */
package com.bmscompanion.app.data.weather

import kotlinx.serialization.Serializable
import kotlin.math.floor

/**
 * Falcon BMS's campaign clock: day 1 is the first day of the campaign, and the weather's own time axis is minutes
 * since 00:00 on day 1.
 */
@Serializable
data class CampaignTime(val day: Int = 1, val hour: Int = 5, val minute: Int = 0) {

    /** `campaign-time->minutes`. */
    val minutes: Int get() = ((day - 1) * 24 + hour) * 60 + minute

    fun plusMinutes(min: Double): CampaignTime = fromMinutes(minutes + min)

    /** "Day 1 05:00", the way the page and BMS's own UI print it. */
    val label: String get() = "Day $day ${two(hour)}:${two(minute)}"

    /**
     * The name BMS's Maps Auto Update looks for: `day*10000 + hour*100 + minute` (Technical Manual 13.6.1 — a
     * 30509.fmap is loaded at day 3, 05:09). WeatherGen writes the same thing as `"%d%02d%02d.fmap"`.
     */
    val fmapName: String get() = "$day${two(hour)}${two(minute)}.fmap"

    /**
     * This time at the start of its hour ("Day 1 01:02" → "Day 1 01:00"). A series of update maps is best on the
     * hour: BMS's own hourly maps then share its names, and none of them falls between two of the series'.
     */
    fun floorHour(): CampaignTime = copy(minute = 0)

    companion object {
        /**
         * A campaign clock as BMS keeps it — milliseconds since 00:00 on day 1, as a save's header holds it
         * (`CampFile.clock`, `CampFlight.clock`) — as a time.
         */
        fun ofClockMs(ms: Long): CampaignTime = fromMinutes(floor(ms / 60_000.0))

        /**
         * A time as a pilot types it: "Day 1 01:02", "D1 01:02", "1 01:02", "1 0102", WDP's Current Time "1, 01:02:16",
         * or BMS's update-map name "10102". Null for anything else, and for a day before 1.
         */
        fun parse(text: String): CampaignTime? {
            val s = text.trim().lowercase().removePrefix("day").removePrefix("d").trim()
            if (s.isEmpty()) return null
            if (s.all { it in '0'..'9' }) return if (s.length >= 5) ofFmapName(s) else null
            val tokens = s.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
            val n = tokens.map { it.toIntOrNull() ?: return null }
            if (n.size < 2) return null
            // "1 0102" and "1 0005": the time as HHMM, read from its digits (as a number 0005 would be 5, an hour)
            val hhmm = n.size == 2 && tokens[1].length in 3..4
            val hour = if (hhmm) n[1] / 100 else n[1]
            val minute = if (hhmm) n[1] % 100 else n.getOrElse(2) { 0 }
            return CampaignTime(n[0], hour, minute).takeIf { it.day in 1..99 && it.hour in 0..23 && it.minute in 0..59 }
        }

        /**
         * `minutes->campaign-time`. Floored like the original, so a fraction of a minute belongs to the minute it
         * started in, and so a negative time lands on the day before rather than rounding towards zero.
         */
        fun fromMinutes(min: Double): CampaignTime {
            val d = floor(min / (24 * 60)).toInt()
            val h = floor(min.mod(24.0 * 60) / 60).toInt()
            val m = floor(min.mod(60.0)).toInt()
            return CampaignTime(day = d + 1, hour = h, minute = m)
        }

        /** The inverse of [fmapName], or null for a file name that is not one of BMS's update maps. */
        fun ofFmapName(name: String): CampaignTime? {
            val stem = name.substringBeforeLast('.')
            if (stem.length < 5 || !stem.all { it in '0'..'9' }) return null
            val n = stem.toIntOrNull() ?: return null
            val t = CampaignTime(day = n / 10000, hour = n / 100 % 100, minute = n % 100)
            return t.takeIf { it.day >= 1 && it.hour in 0..23 && it.minute in 0..59 }
        }
    }
}

private fun two(v: Int) = if (v in 0..9) "0$v" else "$v"
