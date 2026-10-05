/*
 * Parameters and defaults ported from WeatherGen (src/weathergen/ui.cljs and src/vmt/fmap.cljc in Tyrant's Virtual
 * Mission Tools 0.63).
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
import kotlin.random.Random

/** BMS's four weathers, in WeatherGen's order: lowest pressure first. [code] is the value an `.fmap` stores. */
@Serializable
enum class WxType(val code: Int, val label: String) {
    INCLEMENT(4, "Inclement"),
    POOR(3, "Poor"),
    FAIR(2, "Fair"),
    SUNNY(1, "Sunny"),
    ;

    companion object {
        fun ofCode(code: Int): WxType = entries.firstOrNull { it.code == code } ?: FAIR
    }
}

/**
 * Low cloud coverage.
 *
 * [value] is where WeatherGen places each on its 0..1 scale; [code] is what an `.fmap` stores, and it is **not**
 * oktas: BMS's maps hold 0 / 1 / 5 / 9 / 13 for none / FEW / SCT / BKN / OVC (`vmt/fmap.cljc`
 * `coverage-code->type`; Korea's 741 hourly maps in `WeatherMapsUpdates` hold only 1, 5 and 9, and BMS's own training
 * maps add 13 on inclement cells). [oktas] sits inside the range the Technical Manual gives each (FEW 1-2, SCT 3-4,
 * BKN 5-7, OVC 8), which is the unit the rest of the app speaks.
 */
@Serializable
enum class WxCover(val value: Double, val code: Int, val oktas: Int, val metar: String) {
    NONE(0.0, 0, 0, "SKC"),
    FEW(0.25, 1, 1, "FEW"),
    SCATTERED(0.5, 5, 3, "SCT"),
    BROKEN(0.75, 9, 6, "BKN"),
    OVERCAST(1.0, 13, 8, "OVC"),
    ;

    companion object {
        fun ofValue(v: Double): WxCover? = entries.firstOrNull { it.value == v }

        /**
         * What a stored code reads as. The five codes are exact; anything between them (an older BMS Companion wrote
         * oktas here, and one of BMS's own training maps holds a single 6) goes to the code at or below it, since
         * the codes are four apart.
         */
        fun ofCode(code: Int): WxCover = when {
            code <= 0 -> NONE
            code < 5 -> FEW
            code < 9 -> SCATTERED
            code < 13 -> BROKEN
            else -> OVERCAST
        }

        /** Oktas, as the manual counts them, to the category. */
        fun ofOktas(oktas: Int): WxCover = when {
            oktas <= 0 -> NONE
            oktas <= 2 -> FEW
            oktas <= 4 -> SCATTERED
            oktas <= 7 -> BROKEN
            else -> OVERCAST
        }
    }
}

@Serializable
data class WxRange(val from: Double = 0.0, val to: Double = 0.0)

@Serializable
data class WxSpread(val min: Double = 0.0, val mean: Double = 0.0, val max: Double = 0.0)

/**
 * A weather type's low cloud: base in feet, size 0 (congestus) to 5 (humilis), the coverage range, and the
 * towering-cumulus threshold, which is roughly the share of that type's cells that grow towers (see [WxTowering]).
 */
@Serializable
data class WxLowClouds(
    val base: WxRange = WxRange(),
    val size: WxRange = WxRange(0.0, 5.0),
    val coverFrom: WxCover = WxCover.SCATTERED,
    val coverTo: WxCover = WxCover.OVERCAST,
    val towering: Double = 0.4,
)

/**
 * One weather type's settings. [weight] (1..100) is how much of the pressure range the type takes: the types are
 * laid out inclement, poor, fair, sunny from low pressure to high, in proportion to their weights.
 */
@Serializable
data class WxTypeParams(
    val weight: Double = 50.0,
    /** knots */
    val wind: WxSpread = WxSpread(),
    /** °C */
    val temp: WxSpread = WxSpread(),
    /** km */
    val visibility: WxRange = WxRange(),
    /** none for sunny, which is clear by definition */
    val lowClouds: WxLowClouds? = null,
)

/** A level of wind above the surface: this much faster than the ground wind, and this far turned toward the prevailing. */
@Serializable
data class WxAloft(val altFt: Int = 0, val speed: WxRange = WxRange(), val bias: Double = 0.0)

/**
 * A region that forces its own weather over the pattern: full strength inside [falloff] cells of its centre,
 * fading to nothing at [radius]. Every attribute is optional; the ones left null are the pattern's own.
 * [x]/[y] are in cells, x east from the west edge and y south from the north edge.
 */
@Serializable
data class WxOverride(
    val x: Double = 30.0,
    val y: Double = 30.0,
    val radius: Double = 8.0,
    val falloff: Double = 2.0,
    val strength: Double = 1.0,
    val type: WxType? = null,
    val tempC: Double? = null,
    val visKm: Double? = null,
    val cover: WxCover? = null,
    val baseFt: Double? = null,
    val size: Double? = null,
    val towering: Boolean? = null,
    val windDeg: Double? = null,
    val windKt: Double? = null,
    /** the levels [windDeg]/[windKt] apply at, in feet; empty means none, which is WeatherGen's default */
    val windAlts: List<Int> = emptyList(),
    /** fade in and out over time: nothing before [begin], full from [peak] to [taper], nothing after [end] */
    val animate: Boolean = false,
    val begin: CampaignTime = CampaignTime(1, 5, 0),
    val peak: CampaignTime = CampaignTime(1, 6, 0),
    val taper: CampaignTime = CampaignTime(1, 8, 0),
    val end: CampaignTime = CampaignTime(1, 9, 0),
    /** a surprise: left out of the forecast after the forecast's own "now" */
    val excludeFromForecast: Boolean = false,
    val showOutline: Boolean = false,
)

/** Which way the pattern travels (the heading it moves toward), how fast in knots, and the step between maps in minutes. */
@Serializable
data class WxMovement(val headingDeg: Double = 135.0, val speedKt: Double = 20.0, val stepMin: Double = 60.0)

/**
 * The whole-map altitudes an `.fmap` header carries: the high stratus layer for sunny/fair and for poor/inclement,
 * and the contrail altitude for each type, sunny first. WeatherGen's defaults.
 */
@Serializable
data class WxCloudLayers(
    val stratusFairFt: Int = 43300,
    val stratusInclementFt: Int = 33000,
    val contrailsFt: List<Int> = listOf(34000, 28000, 25000, 20000),
)

/**
 * Everything that decides a generated weather: WeatherGen's `weather-params`, `movement-params` and `cloud-params`
 * in one object, with its defaults (`ui.cljs` `default-weather-params`). A few hundred bytes of JSON; the grid itself
 * is rebuilt from it wherever it is needed and never sent.
 */
@Serializable
data class WxGenParams(
    val seed: Double = 1234.0,
    /** "Zoom": cells per unit of pattern space, so bigger means bigger weather systems */
    val featureSize: Double = 10.0,
    /** "X/Y Offset": where in pattern space the grid sits */
    val originX: Double = 1000.0,
    val originY: Double = 1000.0,
    /** "T Offset" */
    val timeOffset: Double = 1234.0,
    val current: CampaignTime = CampaignTime(1, 5, 0),
    /** overrides marked exclude-from-forecast still show in a forecast up to this time */
    val maxTime: CampaignTime? = null,
    /** minutes for the pattern to change completely while standing still */
    val evolution: Double = 3600.0,
    /** "Warp strength" and its spatial size */
    val turbulencePower: Double = 250.0,
    val turbulenceSize: Double = 1.0,
    /** blending between types across each threshold; 0.1 is good, above 0.3 looks strange */
    val crossfade: Double = 0.1,
    /** inHg */
    val pressureMin: Double = 28.0,
    val pressureMax: Double = 31.0,
    /** the most the pressure varies across the theater at one time */
    val pressureVariance: Double = 1.2,
    /** how slowly the theater's mean pressure drifts */
    val pressureSpeed: Double = 100.0,
    val prevailingDeg: Double = 325.0,
    val windUniformity: Double = 0.7,
    /** kept for WeatherGen's settings; its temperature does not use it */
    val tempUniformity: Double = 0.7,
    val sunny: WxTypeParams = WxDefaults.SUNNY,
    val fair: WxTypeParams = WxDefaults.FAIR,
    val poor: WxTypeParams = WxDefaults.POOR,
    val inclement: WxTypeParams = WxDefaults.INCLEMENT,
    /** the nine levels above the surface, 3,000 to 50,000 ft */
    val windsAloft: List<WxAloft> = WxDefaults.ALOFT,
    val overrides: List<WxOverride> = emptyList(),
    val movement: WxMovement = WxMovement(),
    val clouds: WxCloudLayers = WxCloudLayers(),
    /** the grid; every Falcon BMS 4.38.1 theater's is 59 x 59, and the writer checks against the theater's own map */
    val cols: Int = 59,
    val rows: Int = 59,
) {
    fun type(t: WxType): WxTypeParams = when (t) {
        WxType.INCLEMENT -> inclement
        WxType.POOR -> poor
        WxType.FAIR -> fair
        WxType.SUNNY -> sunny
    }

    fun withType(t: WxType, p: WxTypeParams): WxGenParams = when (t) {
        WxType.INCLEMENT -> copy(inclement = p)
        WxType.POOR -> copy(poor = p)
        WxType.FAIR -> copy(fair = p)
        WxType.SUNNY -> copy(sunny = p)
    }
}

/** The named towering-cumulus thresholds WeatherGen offers per type (`ui.cljs` `towering-thresholds`). */
object WxTowering {
    val NAMES = listOf("None", "Rare", "Some", "Common", "Frequent", "Prevalent", "Always")

    fun thresholds(t: WxType): List<Double> = when (t) {
        WxType.FAIR -> listOf(0.0, 0.3, 0.4, 0.5, 0.6, 0.7, 1.0)
        WxType.POOR -> listOf(0.0, 0.2, 0.25, 0.4, 0.45, 0.5, 1.0)
        WxType.INCLEMENT -> listOf(0.0, 0.07, 0.1, 0.2, 0.35, 0.45, 1.0)
        WxType.SUNNY -> List(7) { 0.0 }
    }
}

/** WeatherGen's defaults (`ui.cljs:200-279`, `:465-499`). */
object WxDefaults {
    val SUNNY = WxTypeParams(50.0, WxSpread(5.0, 10.0, 30.0), WxSpread(20.0, 22.0, 24.0), WxRange(24.0, 48.0), null)
    val FAIR = WxTypeParams(
        50.0, WxSpread(0.0, 7.0, 20.0), WxSpread(18.0, 21.0, 23.0), WxRange(16.0, 48.0),
        WxLowClouds(WxRange(3000.0, 10000.0), WxRange(0.0, 5.0), WxCover.FEW, WxCover.BROKEN, 0.4),
    )
    val POOR = WxTypeParams(
        50.0, WxSpread(10.0, 15.0, 30.0), WxSpread(15.0, 18.0, 21.0), WxRange(8.0, 16.0),
        WxLowClouds(WxRange(0.0, 10000.0), WxRange(0.0, 5.0), WxCover.SCATTERED, WxCover.OVERCAST, 0.4),
    )
    val INCLEMENT = WxTypeParams(
        50.0, WxSpread(15.0, 25.0, 60.0), WxSpread(12.0, 14.0, 16.0), WxRange(3.0, 11.0),
        WxLowClouds(WxRange(0.0, 10000.0), WxRange(0.0, 5.0), WxCover.SCATTERED, WxCover.OVERCAST, 0.35),
    )
    val ALOFT = listOf(
        WxAloft(3000, WxRange(2.0, 3.0), 0.1),
        WxAloft(6000, WxRange(4.0, 6.0), 0.2),
        WxAloft(9000, WxRange(7.0, 9.0), 0.3),
        WxAloft(12000, WxRange(8.0, 12.0), 0.4),
        WxAloft(18000, WxRange(11.0, 13.0), 0.5),
        WxAloft(24000, WxRange(13.0, 17.0), 0.6),
        WxAloft(30000, WxRange(16.0, 18.0), 0.7),
        WxAloft(40000, WxRange(18.0, 22.0), 0.8),
        WxAloft(50000, WxRange(20.0, 25.0), 0.9),
    )

    /** The ten levels an `.fmap` carries wind at, surface first (`model.cljc` `wind-alts`, `vmt/fmap.cljc` `wind-data`). */
    val WIND_ALTS = listOf(0, 3000, 6000, 9000, 12000, 18000, 24000, 30000, 40000, 50000)
}

/**
 * WeatherGen's "Randomize" buttons (`ui.cljs:294-492`), each giving plausible, ordered values: visibility and cloud
 * rising from inclement to sunny, wind falling, temperature rising.
 *
 * Three things are corrected, each where the code plainly means something other than what it does:
 * - `random-atmosphere-params` means to leave the rarest towering settings out of a random pick ("Always",
 *   "Prevalent", "Frequent" for fair), but removes those *names* from a list of the thresholds' *numbers*, so it
 *   removes nothing and "Always" — a tower on every cell — comes up as often as any other. Here they are left out.
 * - `random-temp-params` builds the poor, fair and sunny means with `(map #(+ temp-mean-i))`, a function that
 *   ignores its argument, so all three came out equal to the inclement mean and the sorted offsets it had just drawn
 *   were thrown away. Here they are added.
 * - `random-wind-params` builds each level of wind aloft from the one below (`reductions`), but the first level is
 *   the raw draw, whose "to" is only the 1..5 kt width while its "from" can be 10: a range running backwards. Here
 *   the first level's "to" is its "from" plus that width, as every later level's is.
 */
object WxRandom {
    private fun int(r: Random, from: Int, to: Int, step: Int = 1): Int {
        val steps = (to - from) / step + 1
        return (r.nextDouble() * steps).toInt() * step + from
    }

    fun atmosphere(p: WxGenParams, r: Random = Random.Default): WxGenParams {
        val vis = List(4) { int(r, 0, 600) / 10.0 }.sorted()             // inclement, poor, fair, sunny
        val visd = List(4) { int(r, 1, 150) / 10.0 }.sorted()            // sunny, fair, poor, inclement
        val base = List(6) { int(r, 0, 10000, 100).toDouble() }.sorted() // i, p, f from; i, p, f to
        fun size() = List(2) { r.nextDouble() * 5 }.sorted()
        val covers = listOf(WxCover.FEW, WxCover.SCATTERED, WxCover.BROKEN, WxCover.OVERCAST)
        fun cover(from: Int, to: Int) = List(2) { covers[int(r, from, to) - 1] }.sortedBy { it.value }
        fun towering(t: WxType, drop: Int) = WxTowering.thresholds(t).dropLast(drop).random(r)
        fun visTo(from: Double, d: Double) = WxNoise.clamp(0.0, 30.0, from + d)
        val si = size()
        val sp = size()
        val sf = size()
        val ci = cover(3, 4)
        val cp = cover(3, 4)
        val cf = cover(1, 3)
        return p.copy(
            sunny = p.sunny.copy(visibility = WxRange(vis[3], visTo(vis[3], visd[0]))),
            fair = p.fair.copy(
                visibility = WxRange(vis[2], visTo(vis[2], visd[1])),
                lowClouds = WxLowClouds(WxRange(base[2], base[5]), WxRange(sf[0], sf[1]), cf[0], cf[1], towering(WxType.FAIR, 3)),
            ),
            poor = p.poor.copy(
                visibility = WxRange(vis[1], visTo(vis[1], visd[2])),
                lowClouds = WxLowClouds(WxRange(base[1], base[4]), WxRange(sp[0], sp[1]), cp[0], cp[1], towering(WxType.POOR, 2)),
            ),
            inclement = p.inclement.copy(
                visibility = WxRange(vis[0], visTo(vis[0], visd[3])),
                lowClouds = WxLowClouds(WxRange(base[0], base[3]), WxRange(si[0], si[1]), ci[0], ci[1], towering(WxType.INCLEMENT, 1)),
            ),
        )
    }

    fun wind(p: WxGenParams, r: Random = Random.Default): WxGenParams {
        val m = List(4) { int(r, 0, 25) }.sorted() // sunny, fair, poor, inclement
        var from = 0
        var bias = 0.0
        val aloft = WxDefaults.WIND_ALTS.drop(1).mapIndexed { i, alt ->
            val dFrom = int(r, -5, 10)
            val dTo = int(r, 1, 5)
            val dBias = (r.nextDouble() - 0.1) / 4
            from = if (i == 0) dFrom else from + dFrom
            bias = if (i == 0) dBias else WxNoise.clamp(0.0, 1.0, bias + dBias)
            WxAloft(alt, WxRange(from.toDouble(), (from + dTo).toDouble()), WxNoise.clamp(0.0, 1.0, bias))
        }
        fun spread(min: Int, mean: Int, max: Int) = WxSpread(min.toDouble(), mean.toDouble(), max.toDouble())
        return p.copy(
            sunny = p.sunny.copy(wind = spread(0, m[0], int(r, m[0], 30))),
            fair = p.fair.copy(wind = spread(0, m[1], int(r, m[1], 30))),
            poor = p.poor.copy(wind = spread(int(r, 0, m[2]), m[2], int(r, m[2], 50))),
            inclement = p.inclement.copy(wind = spread(int(r, 0, m[3]), m[3], int(r, m[3], 55))),
            windsAloft = aloft,
        )
    }

    fun temperature(p: WxGenParams, r: Random = Random.Default): WxGenParams {
        val i = int(r, -10, 40)
        val (tp, tf, ts) = List(3) { int(r, 1, 10) }.sorted().map { it + i }
        fun spread(mean: Int) = WxSpread((mean - int(r, 1, 5)).toDouble(), mean.toDouble(), (mean + int(r, 1, 5)).toDouble())
        return p.copy(
            sunny = p.sunny.copy(temp = spread(ts)),
            fair = p.fair.copy(temp = spread(tf)),
            poor = p.poor.copy(temp = spread(tp)),
            inclement = p.inclement.copy(temp = spread(i)),
        )
    }

    /** `random-cloud-params`: the stratus layers and contrails, each in order. */
    fun clouds(p: WxGenParams, r: Random = Random.Default): WxGenParams {
        val (si, sf) = List(2) { int(r, 16000, 40000, 500) }.sorted()
        val (ci, cp, cf, cs) = List(4) { int(r, 20000, 40000, 1000) }.sorted()
        return p.copy(clouds = WxCloudLayers(sf, si, listOf(cs, cf, cp, ci)))
    }

    /** "Random" beside the seed: 100..5099, as WeatherGen draws it. */
    fun seed(r: Random = Random.Default): Double = (100 + r.nextInt(5000)).toDouble()
}

/**
 * WeatherGen's colours for the map (`ui.cljs:1807-1848`), as ARGB. The pressure and temperature ramps are the
 * original's, with one difference: WeatherGen's `gradient-color` finds no segment for a value outside the ramp
 * (a pressure under 28.5 inHg, which its own default minimum of 28 allows, or a temperature under 0 °C or over 40)
 * and drew nothing there; here the ends of the ramp carry on.
 */
object WxColors {
    fun type(t: WxType): Int = when (t) {
        WxType.SUNNY -> argb(0.25, 255, 255, 255)
        WxType.FAIR -> argb(1.0, 0, 255, 0)
        WxType.POOR -> argb(1.0, 255, 255, 0)
        WxType.INCLEMENT -> argb(1.0, 192, 0, 0)
    }

    private val PRESSURE = listOf(
        28.5 to intArrayOf(192, 0, 0), 28.9 to intArrayOf(192, 0, 0), 29.3 to intArrayOf(255, 255, 0),
        29.5 to intArrayOf(0, 255, 0), 29.9 to intArrayOf(0, 128, 255), 30.2 to intArrayOf(255, 255, 255),
        31.0 to intArrayOf(255, 255, 255),
    )
    private val TEMPERATURE = listOf(0.0 to intArrayOf(0, 0, 255), 20.0 to intArrayOf(0, 255, 0), 40.0 to intArrayOf(255, 0, 0))

    fun pressure(inHg: Double): Int = ramp(PRESSURE, inHg)
    fun temperature(c: Double): Int = ramp(TEMPERATURE, c)

    private fun ramp(stops: List<Pair<Double, IntArray>>, v: Double): Int {
        val x = WxNoise.clamp(stops.first().first, stops.last().first, v)
        val i = (0 until stops.size - 1).firstOrNull { x >= stops[it].first && x <= stops[it + 1].first } ?: 0
        val (lo, l) = stops[i]
        val (hi, h) = stops[i + 1]
        val f = if (hi == lo) 0.0 else (x - lo) / (hi - lo)
        // WeatherGen truncates each channel with `long`
        fun ch(k: Int) = (l[k] + (h[k] - l[k]) * f).toInt()
        return argb(1.0, ch(0), ch(1), ch(2))
    }

    private fun argb(a: Double, r: Int, g: Int, b: Int): Int =
        ((a * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b
}
