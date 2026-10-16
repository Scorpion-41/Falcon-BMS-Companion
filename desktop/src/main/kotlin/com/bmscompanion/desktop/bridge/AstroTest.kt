package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.weather.Astro
import kotlin.math.abs
import kotlin.math.floor

/**
 * `--astrotest out.txt` — the Mission weather page's Sun & Moon ([Astro]) against an independent almanac and against
 * Falcon BMS's own printed figures. Pure, no files.
 *
 * 1. **The almanac**: sunrise, sunset, civil dawn and dusk, moonrise and moonset, the moon's lit fraction and its
 *    elongation at local noon, for six places (Osan, Ramat David, Pristina, London, Mount Pleasant, Tromsø — midnight
 *    sun and polar night included) on eight dates, against Astronomy Engine 2.1.19 (Don Cross, MIT; topocentric, VSOP87
 *    and the full lunar theory), its figures written into [REFS] once. Allowed: the sun's events 1 minute, the moon's 3,
 *    the lit fraction 0.01, the elongation 0.3°; an event one has and the other not, only within 10 minutes of the
 *    day's edge (a rise at 23:58 is the next day's to the other).
 * 2. **The moon's quarters** of April 2024 (Astronomy Engine's instants: new moon 8 April 18:21:25 UTC, the eclipse,
 *    full 23 April 23:49:38): the elongation at each within 0.3° of 0, 90, 180, 270, and the phase named.
 * 3. **BMS's own briefing figures**: [Astro.bmsSun] for two briefings BMS 4.38.1 printed (a Korea campaign's on
 *    2026-09-20: 21:28:18z / 09:43:41z; an Israel one's on 2026-09-16: 03:33:34z / 15:54:25z), to the second.
 */
object AstroTest {
    private class Ref(
        val name: String, val lat: Double, val lon: Double, val tz: Int, val y: Int, val m: Int, val d: Int,
        val sunrise: Double?, val sunset: Double?, val dawn: Double?, val dusk: Double?, val moonrise: Double?, val moonset: Double?,
        val illum: Double, val elong: Double,
    )

    private val REFS = listOf(
        Ref("Osan", 37.09, 127.03, 9, 2026, 9, 20, 6.2951, 18.5429, 5.8617, 18.9755, 15.0118, null, 0.6197, 103.73),
        Ref("Osan", 37.09, 127.03, 9, 2026, 9, 16, 6.2407, 18.6449, 5.8059, 19.0786, 11.4089, 21.0728, 0.2502, 59.76),
        Ref("Osan", 37.09, 127.03, 9, 2024, 4, 8, 6.1236, 19.0094, 5.6828, 19.4513, 5.7451, 18.4983, 0.0062, 351.01),
        Ref("Osan", 37.09, 127.03, 9, 2024, 4, 23, 5.7839, 19.2317, 5.3285, 19.6885, 18.6505, 5.2509, 0.9929, 170.33),
        Ref("Osan", 37.09, 127.03, 9, 2024, 6, 21, 5.2055, 19.9186, 4.6897, 20.4344, 19.5848, 3.8730, 0.9884, 168.49),
        Ref("Osan", 37.09, 127.03, 9, 2024, 12, 21, 7.6935, 17.3063, 7.2088, 17.7910, 23.2989, 11.8117, 0.6705, 250.20),
        Ref("Osan", 37.09, 127.03, 9, 2025, 3, 14, 6.7450, 18.6339, 6.3136, 19.0660, 18.6720, 6.6842, 0.9997, 178.19),
        Ref("Osan", 37.09, 127.03, 9, 2026, 1, 5, 7.7630, 17.4770, 7.2827, 17.9573, 19.5571, 9.3284, 0.9593, 203.19),
        Ref("RamatDavid", 32.665, 35.18, 2, 2026, 9, 20, 5.4341, 17.6491, 5.0238, 18.0587, 14.0742, 23.9946, 0.6464, 106.92),
        Ref("RamatDavid", 32.665, 35.18, 2, 2026, 9, 16, 5.3915, 17.7392, 4.9802, 18.1498, 10.5786, 20.5877, 0.2753, 63.04),
        Ref("RamatDavid", 32.665, 35.18, 2, 2024, 4, 8, 5.3015, 18.0742, 4.8849, 18.4917, 5.0132, 17.8834, 0.0018, 355.11),
        Ref("RamatDavid", 32.665, 35.18, 2, 2024, 4, 23, 5.0057, 18.2533, 4.5770, 18.6831, 17.9244, 4.5488, 0.9968, 173.57),
        Ref("RamatDavid", 32.665, 35.18, 2, 2024, 6, 21, 4.5349, 18.8376, 4.0580, 19.3145, 18.7107, 3.4551, 0.9936, 172.10),
        Ref("RamatDavid", 32.665, 35.18, 2, 2024, 12, 21, 6.6247, 16.6261, 6.1703, 17.0804, 22.7149, 10.9685, 0.6436, 253.45),
        Ref("RamatDavid", 32.665, 35.18, 2, 2025, 3, 14, 5.8469, 17.7748, 5.4381, 18.1842, 18.0469, 5.8733, 0.9998, 181.42),
        Ref("RamatDavid", 32.665, 35.18, 2, 2026, 1, 5, 6.7006, 16.7895, 6.2501, 17.2399, 19.1436, 8.4405, 0.9452, 207.05),
        Ref("Pristina", 42.57, 21.03, 1, 2026, 9, 20, 5.3481, 17.6176, 4.8783, 18.0863, 14.6236, 23.4183, 0.6502, 107.37),
        Ref("Pristina", 42.57, 21.03, 1, 2026, 9, 16, 5.2768, 17.7363, 4.8052, 18.2068, 11.1014, 19.9950, 0.2790, 63.51),
        Ref("Pristina", 42.57, 21.03, 1, 2024, 4, 8, 5.0965, 18.1696, 4.6163, 18.6513, 4.9099, 18.0195, 0.0014, 355.70),
        Ref("Pristina", 42.57, 21.03, 1, 2024, 4, 23, 4.6957, 18.4540, 4.1958, 18.9557, 18.1678, 4.3159, 0.9972, 174.03),
        Ref("Pristina", 42.57, 21.03, 1, 2024, 6, 21, 3.9748, 19.2846, 3.3910, 19.8684, 19.3531, 2.8042, 0.9942, 172.62),
        Ref("Pristina", 42.57, 21.03, 1, 2024, 12, 21, 7.0426, 16.0955, 6.5072, 16.6309, 22.5940, 11.0792, 0.6398, 253.91),
        Ref("Pristina", 42.57, 21.03, 1, 2025, 3, 14, 5.8251, 17.6865, 5.3579, 18.1546, 18.0227, 5.8915, 0.9997, 181.88),
        Ref("Pristina", 42.57, 21.03, 1, 2026, 1, 5, 7.0969, 16.2813, 6.5677, 16.8105, 18.7661, 8.8191, 0.9430, 207.59),
        Ref("London", 51.5074, -0.1278, 0, 2026, 9, 20, 5.7216, 18.0599, 5.1640, 18.6156, 15.8323, 23.1525, 0.6540, 107.83),
        Ref("London", 51.5074, -0.1278, 0, 2026, 9, 16, 5.6150, 18.2137, 5.0542, 18.7725, 12.3022, 19.6910, 0.2826, 63.97),
        Ref("London", 51.5074, -0.1278, 0, 2024, 4, 8, 5.3213, 18.7707, 4.7449, 19.3498, 5.2526, 18.7006, 0.0011, 356.28),
        Ref("London", 51.5074, -0.1278, 0, 2024, 4, 23, 4.7878, 19.1884, 4.1752, 19.8045, 18.9805, 4.5010, 0.9976, 174.49),
        Ref("London", 51.5074, -0.1278, 0, 2024, 6, 21, 3.7206, 20.3601, 2.9238, 21.1568, 20.7341, 2.4070, 0.9947, 173.14),
        Ref("London", 51.5074, -0.1278, 0, 2024, 12, 21, 8.0660, 15.8942, 7.3942, 16.5660, 22.9498, 11.6831, 0.6359, 254.37),
        Ref("London", 51.5074, -0.1278, 0, 2025, 3, 14, 6.2747, 18.0617, 5.7220, 18.6160, 18.4983, 6.3825, 0.9996, 182.34),
        Ref("London", 51.5074, -0.1278, 0, 2026, 1, 5, 8.0882, 16.1129, 7.4285, 16.7727, 18.7956, 9.7654, 0.9408, 208.14),
        Ref("MountPleasant", -51.82, -58.45, -3, 2026, 9, 20, 6.7839, 18.8052, 6.2265, 19.3643, 11.6371, 4.5482, 0.6653, 109.20),
        Ref("MountPleasant", -51.82, -58.45, -3, 2026, 9, 16, 6.9388, 18.6974, 6.3820, 19.2557, 8.4184, 0.7757, 0.2936, 65.37),
        Ref("MountPleasant", -51.82, -58.45, -3, 2024, 4, 8, 7.4738, 18.3601, 6.9104, 18.9224, 7.1182, 18.2440, 0.0003, 358.04),
        Ref("MountPleasant", -51.82, -58.45, -3, 2024, 4, 23, 7.8890, 17.8315, 7.3069, 18.4129, 17.5257, 7.4426, 0.9986, 175.89),
        Ref("MountPleasant", -51.82, -58.45, -3, 2024, 6, 21, 9.0437, 16.8150, 8.3642, 17.4946, 15.8285, 9.2465, 0.9961, 174.70),
        Ref("MountPleasant", -51.82, -58.45, -3, 2024, 12, 21, 4.5173, 21.2216, 3.7094, 22.0294, 0.5422, 11.4075, 0.6243, 255.75),
        Ref("MountPleasant", -51.82, -58.45, -3, 2025, 3, 14, 6.7585, 19.3188, 6.1946, 19.8806, 19.3926, 6.9633, 0.9990, 183.72),
        Ref("MountPleasant", -51.82, -58.45, -3, 2026, 1, 5, 4.7386, 21.2273, 3.9558, 22.0067, 22.9112, 7.1217, 0.9340, 209.77),
        Ref("Tromso", 69.65, 18.96, 1, 2026, 9, 20, 5.2714, 17.9454, 4.2507, 18.9574, null, null, 0.6502, 107.37),
        Ref("Tromso", 69.65, 18.96, 1, 2026, 9, 16, 5.0134, 18.2491, 3.9700, 19.2823, null, null, 0.2790, 63.51),
        Ref("Tromso", 69.65, 18.96, 1, 2024, 4, 8, 4.2351, 19.3409, 3.0624, 20.5336, 4.6346, 19.1706, 0.0014, 355.70),
        Ref("Tromso", 69.65, 18.96, 1, 2024, 4, 23, 3.0152, 20.4580, 1.3220, 22.2328, 20.2621, 3.1942, 0.9972, 174.03),
        Ref("Tromso", 69.65, 18.96, 1, 2024, 6, 21, null, null, null, null, null, null, 0.9942, 172.62),
        Ref("Tromso", 69.65, 18.96, 1, 2024, 12, 21, null, null, 9.5254, 13.8888, 22.0695, 12.1713, 0.6398, 253.91),
        Ref("Tromso", 69.65, 18.96, 1, 2025, 3, 14, 6.1673, 17.6423, 5.1770, 18.6390, 18.1489, 6.3916, 0.9997, 181.88),
        Ref("Tromso", 69.65, 18.96, 1, 2026, 1, 5, null, null, 9.3448, 14.3140, 15.7119, 12.3697, 0.9430, 207.59),
    )

    fun run(): String = buildString {
        appendLine("Sun & Moon (Astro.kt) against Astronomy Engine 2.1.19 and BMS's printed briefings")
        appendLine()
        appendLine("1. The almanac (${REFS.size} place-days; differences in minutes)")
        var worstSun = 0.0
        var worstMoon = 0.0
        var worstIllum = 0.0
        var worstElong = 0.0
        for (r in REFS) {
            val date = Astro.Ymd(r.y, r.m, r.d)
            val noon = Astro.jd(date, 12.0 - r.tz)
            val s = Astro.sunMoon(date, r.tz.toDouble(), r.lat, r.lon, noon)
            val bad = ArrayList<String>()
            fun cmp(what: String, ours: Double?, ref: Double?, tolMin: Double, moon: Boolean) {
                if (ours == null && ref == null) return
                if (ours == null || ref == null) {
                    val t = (ours ?: ref)!!
                    if (t < 10.0 / 60 || t > 24 - 10.0 / 60) return
                    bad += "$what ${ours?.let { Astro.hm(it) } ?: "none"} vs ${ref?.let { Astro.hm(it) } ?: "none"}"
                    return
                }
                val dm = abs(ours - ref) * 60
                if (moon) worstMoon = maxOf(worstMoon, dm) else worstSun = maxOf(worstSun, dm)
                if (dm > tolMin) bad += "$what ${Astro.hm(ours)} vs ${Astro.hm(ref)} (${fmt(dm)} min)"
            }
            cmp("sunrise", s.sunrise, r.sunrise, 1.0, false)
            cmp("sunset", s.sunset, r.sunset, 1.0, false)
            cmp("dawn", s.dawn, r.dawn, 1.0, false)
            cmp("dusk", s.dusk, r.dusk, 1.0, false)
            cmp("moonrise", s.moonrise, r.moonrise, 3.0, true)
            cmp("moonset", s.moonset, r.moonset, 3.0, true)
            val di = abs(s.moon.illumination - r.illum)
            var de = abs(s.moon.elongation - r.elong); if (de > 180) de = 360 - de
            worstIllum = maxOf(worstIllum, di); worstElong = maxOf(worstElong, de)
            if (di > 0.01) bad += "lit ${fmt(s.moon.illumination * 100)}% vs ${fmt(r.illum * 100)}%"
            if (de > 0.3) bad += "elongation ${fmt(s.moon.elongation)} vs ${fmt(r.elong)}"
            val line = "${r.name} $date: sun ${s.sunrise?.let { Astro.hm(it) } ?: "--:--"}-${s.sunset?.let { Astro.hm(it) } ?: "--:--"}" +
                " moon ${s.moonrise?.let { Astro.hm(it) } ?: "--:--"}/${s.moonset?.let { Astro.hm(it) } ?: "--:--"} ${s.moon.phase.label} ${fmt(s.moon.illumination * 100)}%"
            appendLine((if (bad.isEmpty()) "ok   " else "FAIL ") + line + if (bad.isEmpty()) "" else " — " + bad.joinToString("; "))
        }
        appendLine("worst: sun events ${fmt(worstSun)} min, moon events ${fmt(worstMoon)} min, lit fraction ${fmt(worstIllum * 100)} points, elongation ${fmt(worstElong)}°")

        appendLine()
        appendLine("2. The moon's quarters, April 2024")
        val quarters = listOf(
            Triple("new moon 2024-04-08 18:21:25Z", Astro.jd(2024, 4, 8, 18 + 21 / 60.0 + 25 / 3600.0), 0.0 to Astro.MoonPhase.NEW),
            Triple("first quarter 2024-04-15 19:13:46Z", Astro.jd(2024, 4, 15, 19 + 13 / 60.0 + 46 / 3600.0), 90.0 to Astro.MoonPhase.FIRST_QUARTER),
            Triple("full moon 2024-04-23 23:49:38Z", Astro.jd(2024, 4, 23, 23 + 49 / 60.0 + 38 / 3600.0), 180.0 to Astro.MoonPhase.FULL),
            Triple("last quarter 2024-04-02 03:15:19Z", Astro.jd(2024, 4, 2, 3 + 15 / 60.0 + 19 / 3600.0), 270.0 to Astro.MoonPhase.LAST_QUARTER),
        )
        for ((name, jd, want) in quarters) {
            val st = Astro.moonState(jd)
            var de = abs(st.elongation - want.first); if (de > 180) de = 360 - de
            val ok = de <= 0.3 && st.phase == want.second
            appendLine((if (ok) "ok   " else "FAIL ") + "$name: elongation ${fmt(st.elongation)}° (${fmt(de)}° off, ${fmt(de / 360 * 29.53 * 24 * 60)} min of the moon's motion), ${st.phase.label}, lit ${fmt(st.illumination * 100)}%")
        }

        appendLine()
        appendLine("3. BMS's own briefing figures (weather.ini's point, no equation of time)")
        fun hms(h: Double): String { val s = floor(h * 3600 + 1e-6).toInt(); return listOf(s / 3600, s % 3600 / 60, s % 60).joinToString(":") { it.toString().padStart(2, '0') } }
        for ((name, date, pt, want) in listOf(
            Quad("Korea KTO", Astro.Ymd(2026, 9, 20), 37 to 126, "21:28:18 / 09:43:41"),
            Quad("Israel", Astro.Ymd(2026, 9, 16), 31 to 34, "03:33:34 / 15:54:25"),
        )) {
            val b = Astro.bmsSun(date, pt.first, pt.second)
            val got = b?.let { hms(it.first) + " / " + hms(it.second) } ?: "none"
            appendLine((if (got == want) "ok   " else "FAIL ") + "$name $date at ${pt.first}/${pt.second}: $got (printed $want)")
        }
        appendLine()
        appendLine(if (lineSequence().any { it.startsWith("FAIL") }) "FAILED" else "ALL PASS")
    }

    private data class Quad(val a: String, val b: Astro.Ymd, val c: Pair<Int, Int>, val d: String)

    private fun fmt(v: Double): String = (kotlin.math.round(v * 100) / 100.0).toString()
}
