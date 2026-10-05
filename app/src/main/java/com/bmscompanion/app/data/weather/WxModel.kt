/*
 * Ported from WeatherGen (src/weathergen/model.cljc in Tyrant's Virtual Mission Tools 0.63).
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

import com.bmscompanion.app.data.weather.WxNoise.clamp
import com.bmscompanion.app.data.weather.WxNoise.distribute
import com.bmscompanion.app.data.weather.WxNoise.fractal
import com.bmscompanion.app.data.weather.WxNoise.frac
import com.bmscompanion.app.data.weather.WxNoise.interpolate
import com.bmscompanion.app.data.weather.WxNoise.nearest
import com.bmscompanion.app.data.weather.WxNoise.normalize
import com.bmscompanion.app.data.weather.WxNoise.rejectTails
import com.bmscompanion.app.data.weather.WxNoise.rotate
import com.bmscompanion.app.data.weather.WxNoise.vectorInterpolate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One cell of a generated weather, in the units a pilot reads: inHg, °C, knots, feet, km.
 *
 * [windKt]/[windDeg] hold the ten levels an `.fmap` carries ([WxDefaults.WIND_ALTS], surface first); a heading is
 * the direction the wind blows **from**, as a METAR gives it. [value] is the pattern value everything else follows
 * from (0 deep low, 1 strong high) and [mixture] how much of each type the cell is, inclement first.
 */
class WxCell(
    val value: Double,
    val type: WxType,
    val mixture: DoubleArray,
    val pressureInHg: Double,
    val tempC: Double,
    val windKt: DoubleArray,
    val windDeg: DoubleArray,
    val cover: WxCover,
    val towering: Boolean,
    val baseFt: Double,
    val size: Double,
    val visKm: Double,
) {
    val pressureMb: Double get() = WxModel.inHgToMb(pressureInHg)
}

/** A whole theater's weather: row-major from the north-west corner, `index = y * cols + x` (x east, y south). */
class WxGrid(val cols: Int, val rows: Int, val cells: Array<WxCell>) {
    fun at(x: Int, y: Int): WxCell = cells[y * cols + x]
}

/**
 * WeatherGen's weather model, function for function (`model.cljc`), so that a seed gives Craig Andera's weather.
 *
 * The idea, in one paragraph: a checkerboard of highs and lows ([pressurePattern]) is sampled through a warp of
 * smooth noise that evolves with time ([perturb]), which gives one number per cell, the **value**. The four types
 * are bands of that value in proportion to their weights, crossfaded at each edge ([mix]); pressure is the value
 * scaled into the theater's range; wind runs along the contours of the pattern near a high or low and follows the
 * prevailing wind between them; and temperature, cloud and visibility are each read off a differently scaled copy
 * of the same pattern, so they vary together the way a front does.
 *
 * **Checked against the original**: `--wxgentest` runs the real `model.cljc` on the JVM (`tools/wxref/wxref.clj`) and
 * demands the same answers from this port with [exact] on.
 *
 * One thing is corrected, and only when [exact] is off, which is how the app runs it. WeatherGen turns the wind aloft
 * toward the prevailing wind, and an override's wind toward its own, with a straight average of two headings — so a
 * ground wind from 004 and a prevailing wind from 325 average through 180, not through north. In the reference run
 * one cell's column veers 004, 036, 068 … 292 on the way up (seed 42, day 1 09:00, cell 10,40), a wind that swings
 * right round the compass. Here headings are averaged the short way round the circle.
 */
object WxModel {

    /** WeatherGen's `inhg->mmhg`, which despite its name gives millibars: 29.92 inHg is 1013 mb. */
    fun inHgToMb(inHg: Double): Double = (inHg - 28.05) / 3.25 * 110 + 950
    fun mbToInHg(mb: Double): Double = (mb - 950) / 110 * 3.25 + 28.05

    private val ORDER = WxType.entries // inclement, poor, fair, sunny: WeatherGen's `types`

    // ------------------------------------------------------------------------------------------------ the pattern

    /**
     * Two fractal fields at the whole "time slices" either side of [t], blended by where [t] lies between them:
     * this is what makes the pattern change smoothly with time.
     */
    fun smoothedNoise(x: Double, y: Double, t: Double, seed: Double, zoom: Double): Double {
        val tStar = floor(t + seed).toLong().toDouble()
        val v = interpolate(
            fractal(x, y, zoom, tStar - 0.01, 1.0),
            fractal(x, y, zoom, tStar + 1.01, 1.0),
            frac(t).mod(1.0),
        )
        return clamp(0.0, 1.0, v.pow(1.25) + 0.1)
    }

    /** Where a point is sampled once the pattern has been warped: turbulence of [power] at the scale of [size]. */
    fun perturb(x: Double, y: Double, t: Double, seed: Double, power: Double, size: Double): DoubleArray {
        val xs = x / size
        val ys = y / size
        val xt = smoothedNoise(xs, ys, t, seed, 32.0)
        val yt = smoothedNoise(xs, ys, t + 16, seed, 32.0)
        return doubleArrayOf(power * xt + x, power * yt + y)
    }

    /** A checkerboard of high and low pressure cells, in 0..1. */
    fun pressurePattern(x: Double, y: Double, x1: Double, y1: Double): Double {
        val v = sin(x) * sin(y) + sin(x / x1) * sin(y / y1)
        return (v + 2) / 4
    }

    /**
     * How much of each type a pattern value is, inclement first: the types' weights become thresholds across 0..1,
     * and each type fades into the next over [fade].
     */
    fun mix(x: Double, fade: Double, p: WxGenParams): DoubleArray {
        val i = p.inclement.weight
        val pw = p.poor.weight
        val f = p.fair.weight
        val s = p.sunny.weight
        val total = i + pw + f + s
        val iS = i / total
        val pS = (i + pw) / total
        val fS = (i + pw + f) / total
        val xs = doubleArrayOf(
            0.0, (1 - fade) * iS,
            interpolate(iS, pS, fade), interpolate(pS, iS, fade),
            interpolate(pS, fS, fade), interpolate(fS, pS, fade),
            1 - fade, 1.0,
        )
        val vs = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0, 1.0),
        )
        // the first segment holding x, as WeatherGen's `filter ... first`; the value is clamped to 0..1 before it
        // gets here, so one always does
        val k = (0 until xs.size - 1).firstOrNull { xs[it] <= x && x <= xs[it + 1] } ?: (xs.size - 2)
        // A segment of no width (a crossfade of 0) would divide nought by nought; WeatherGen gets NaN there, which
        // only an exact hit on the threshold can reach. Its left end is the answer.
        if (xs[k + 1] == xs[k]) return vs[k].copyOf()
        return vectorInterpolate(vs[k], vs[k + 1], x, xs[k], xs[k + 1])
    }

    /** A type setting weighted by the mixture: `mix-on`. */
    private inline fun mixOn(p: WxGenParams, mixture: DoubleArray, sel: (WxTypeParams) -> Double): Double {
        var sum = 0.0
        for (k in ORDER.indices) {
            val term = sel(p.type(ORDER[k])) * mixture[k]
            sum = if (k == 0) term else sum + term
        }
        return sum
    }

    // ------------------------------------------------------------------------------------------------ wind

    /**
     * The surface wind's direction as a vector: along the pattern's contours near a high or low (so it circulates),
     * the prevailing wind where the pattern is in between.
     */
    fun windDirection(px: Double, py: Double, v: Double, prevailingDeg: Double): DoubleArray {
        val n = normalize(
            doubleArrayOf(
                cos(px) * sin(py) + (1.0 / 3) * cos(px / 3) * sin(py / 3),
                sin(px) * cos(py) + (1.0 / 3) * sin(px / 3) * cos(py / 3),
            ),
        )
        val w0 = rotate(90.0, n[0], n[1])
        val w1 = rotate(prevailingDeg, 0.0, 1.0)
        val c = abs(v - 0.5)
        return normalize(vectorInterpolate(w1, w0, c, 0.0, 0.5))
    }

    /** Headings blended by [f]: WeatherGen's straight average when [exact], otherwise the short way round. */
    private fun blendHeading(a: Double, b: Double, f: Double, exact: Boolean): Double =
        if (exact) interpolate(a, b, f) else a + f * ((b - a + 540.0).mod(360.0) - 180.0)

    // ------------------------------------------------------------------------------------------------ overrides

    /** How much of an animated override is showing at [t]: none before begin, ramping up to peak, down to end. */
    fun timeWeight(o: WxOverride, current: CampaignTime): Double {
        if (!o.animate) return 1.0
        val bm = o.begin.minutes.toDouble()
        val pm = o.peak.minutes.toDouble()
        val tm = o.taper.minutes.toDouble()
        val em = o.end.minutes.toDouble()
        val t = current.minutes.toDouble()
        return when {
            em < t -> 0.0
            tm < t -> 1 - (t - tm) / (em - tm)
            pm < t -> 1.0
            bm < t -> (t - bm) / (pm - bm)
            else -> 0.0
        }
    }

    /** Full strength inside the falloff, fading to nothing at the radius. */
    private fun positionWeight(o: WxOverride, x: Double, y: Double): Double {
        val dx = o.x - x
        val dy = o.y - y
        val d2 = dx * dx + dy * dy
        val r2 = o.radius * o.radius
        val fr = o.falloff
        val f2 = fr * fr
        return when {
            d2 < f2 -> 1.0
            d2 < r2 -> 1 - (sqrt(d2) - fr) / (o.radius - o.falloff)
            else -> 0.0
        }
    }

    fun overrideWeight(o: WxOverride, x: Double, y: Double, current: CampaignTime): Double =
        positionWeight(o, x, y) * timeWeight(o, current) * o.strength

    /** The pattern value an override's weather type stands for: the middle of that type's band. */
    private fun overridePressure(p: WxGenParams, type: WxType): Double {
        val i = p.inclement.weight
        val pw = p.poor.weight
        val f = p.fair.weight
        val s = p.sunny.weight
        val total = i + pw + f + s
        return when (type) {
            WxType.SUNNY -> 1.0
            WxType.INCLEMENT -> 0.0
            WxType.POOR -> (pw / 2 + i) / total
            WxType.FAIR -> (f / 2 + i + pw) / total
        }
    }

    /** Overrides with a type pull the pattern value itself, so the weather around a storm crossfades into it. */
    private fun override(p: WxGenParams, x: Double, y: Double, v0: Double): Double {
        var v = v0
        for (o in p.overrides) {
            val type = o.type ?: continue
            val w = positionWeight(o, x, y) * timeWeight(o, p.current) * o.strength
            val vs = overridePressure(p, type)
            v = vs * w + v * (1 - w)
        }
        return clamp(0.0, 1.0, v)
    }

    private inline fun overrideParam(p: WxGenParams, x: Double, y: Double, v0: Double, get: (WxOverride) -> Double?): Double {
        var v = v0
        for (o in p.overrides) {
            val ov = get(o) ?: continue
            val w = overrideWeight(o, x, y, p.current)
            v = ov * w + v * (1 - w)
        }
        return v
    }

    // ------------------------------------------------------------------------------------------------ one cell

    /** The weather in cell ([x], [y]): x east from the west edge, y south from the north edge. */
    fun weather(p: WxGenParams, x: Int, y: Int, exact: Boolean = false): WxCell {
        val cx = x.toDouble()
        val cy = y.toDouble()
        val xs = (p.originX + cx) / p.featureSize
        val ys = (p.originY + cy) / p.featureSize
        val t = p.timeOffset + p.current.minutes
        val ts = t / p.evolution / 10
        val pert = perturb(xs, ys, ts, p.seed, p.turbulencePower, p.turbulenceSize)
        val px = pert[0]
        val py = pert[1]
        val value = override(p, cx, cy, pressurePattern(px, py, 3.0, 3.0))
        val windDir = windDirection(px, py, value, p.prevailingDeg)
        val pressureT = t / p.pressureSpeed / 4
        val theaterMin = interpolate(
            p.pressureMin,
            p.pressureMax - p.pressureVariance,
            fractal(pressureT, 1234.0, 32.0, p.seed, 1.0),
        )
        val pressure = theaterMin + p.pressureVariance * value
        val mixture = mix(value, p.crossfade, p)
        val windVar = rejectTails(p.windUniformity, smoothedNoise(xs * p.featureSize, ys * p.featureSize, ts, p.seed + 17, 32.0))
        val windAdjVar = pressurePattern(px, py, 3.2, 3.2)
        val tempVar = pressurePattern(px, py, 3.3, 3.3)
        val coverageVar = rejectTails(0.9, pressurePattern(px + 1000, py + 1000, 2.5, 3.5))
        // the largest share wins; on a tie the later type, as WeatherGen's stable sort leaves it last
        var best = 0
        for (k in 1 until 4) if (mixture[k] >= mixture[best]) best = k
        val type = ORDER[best]
        val toweringVar = pressurePattern(px, py, 1.5, 1.5)
        val baseVar = pressurePattern(px, py, 3.4, 3.5)
        val sizeVar = pressurePattern(px, py, 3.5, 3.6)
        val visVar = pressurePattern(px, py, 3.7, 3.8)

        // temperature
        val tMean = mixOn(p, mixture) { it.temp.mean }
        val tMin = mixOn(p, mixture) { it.temp.min }
        val tMax = mixOn(p, mixture) { it.temp.max }
        val temp = overrideParam(p, cx, cy, distribute(tempVar, tMin, tMean, tMax, 1.0)) { it.tempC }

        // wind: the surface, then nine levels above it, then any override
        val speeds = DoubleArray(10)
        val headings = DoubleArray(10)
        val wMean = mixOn(p, mixture) { it.wind.mean }
        val wMin = mixOn(p, mixture) { it.wind.min }
        val wMax = mixOn(p, mixture) { it.wind.max }
        speeds[0] = distribute(windVar, wMin, wMean, wMax, 1.0)
        headings[0] = WxNoise.heading(windDir[0], windDir[1])
        for (k in 1 until 10) {
            val a = p.windsAloft.firstOrNull { it.altFt == WxDefaults.WIND_ALTS[k] } ?: WxDefaults.ALOFT[k - 1]
            speeds[k] = speeds[0] + interpolate(a.speed.from, a.speed.to, windAdjVar)
            headings[k] = blendHeading(headings[0], p.prevailingDeg, a.bias, exact).mod(360.0)
        }
        for (o in p.overrides) {
            val w = overrideWeight(o, cx, cy, p.current)
            for (alt in o.windAlts) {
                val k = WxDefaults.WIND_ALTS.indexOf(alt)
                if (k < 0) continue
                o.windDeg?.let { d ->
                    val h = if (exact) headings[k] * (1 - w) + w * d else blendHeading(headings[k], d, w, false)
                    headings[k] = nearest(h.mod(360.0), 1.0)
                }
                o.windKt?.let { s -> speeds[k] = speeds[k] * (1 - w) + w * s }
            }
        }

        // cloud: none at all for sunny
        val lc = p.type(type).lowClouds
        val sunny = type == WxType.SUNNY || lc == null
        val cover = if (sunny) WxCover.NONE else {
            val from = lc!!.coverFrom.value - 0.12
            val to = lc.coverTo.value + 0.12
            val r = overrideParam(p, cx, cy, interpolate(from, to, coverageVar)) { it.cover?.value }
            val lo = if (type == WxType.FAIR) 0.25 else 0.5
            val hi = if (type == WxType.FAIR) 0.75 else 1.0
            WxCover.ofValue(nearest(clamp(lo, hi, r), 0.25)) ?: WxCover.NONE
        }
        val towering = if (sunny) false else {
            val th = lc!!.towering
            overrideParam(p, cx, cy, toweringVar) { o -> o.towering?.let { if (it) th / 2 else (1 + th) / 2 } } < th
        }
        val base = if (sunny) 0.0 else nearest(overrideParam(p, cx, cy, interpolate(lc!!.base.from, lc.base.to, baseVar)) { it.baseFt }, 100.0)
        val size = if (sunny) 0.0 else overrideParam(p, cx, cy, interpolate(lc!!.size.from, lc.size.to, sizeVar)) { it.size }
        val vis = overrideParam(
            p, cx, cy,
            interpolate(mixOn(p, mixture) { it.visibility.from }, mixOn(p, mixture) { it.visibility.to }, visVar),
        ) { it.visKm }

        return WxCell(
            value = value,
            type = type,
            mixture = mixture,
            pressureInHg = nearest(pressure, 0.01),
            tempC = temp,
            windKt = speeds,
            windDeg = headings,
            cover = cover,
            towering = towering,
            baseFt = base,
            size = size,
            visKm = vis,
        )
    }

    /** Every cell of the theater. 3,481 cells for a 59 x 59 map: milliseconds, even on a phone. */
    fun grid(p: WxGenParams, exact: Boolean = false): WxGrid =
        WxGrid(p.cols, p.rows, Array(p.cols * p.rows) { weather(p, it % p.cols, it / p.cols, exact) })

    // ------------------------------------------------------------------------------------------------ time

    /** How far the pattern moves in [minutes], in cells: WeatherGen takes a cell as 9 nm, so the speed is knots. */
    private fun drift(p: WxGenParams, minutes: Double): DoubleArray {
        val d = rotate(-p.movement.headingDeg, 0.0, 1.0)
        val c = minutes * (p.movement.speedKt / 60 / 9)
        return doubleArrayOf(c * d[0], c * d[1])
    }

    /** [steps] steps of the movement: later in time, and the pattern carried along with the weather's heading. */
    fun step(p: WxGenParams, steps: Int): WxGenParams {
        val dt = steps * p.movement.stepMin
        val d = drift(p, dt)
        return p.copy(current = p.current.plusMinutes(dt), originX = p.originX + d[0], originY = p.originY + d[1])
    }

    /** "Jump to": moves to [t], and the weather changes with it. */
    fun jumpToTime(p: WxGenParams, t: CampaignTime): WxGenParams {
        val d = drift(p, (t.minutes - p.current.minutes).toDouble())
        return p.copy(current = t, originX = p.originX + d[0], originY = p.originY + d[1])
    }

    /** "Set to": relabels the clock as [t] and leaves the weather exactly as it is. */
    fun setTime(p: WxGenParams, t: CampaignTime): WxGenParams =
        p.copy(timeOffset = p.timeOffset + (p.current.minutes - t.minutes), current = t)

    /**
     * One cell's weather now and at [steps] times after, each on a multiple of [interval] minutes. Overrides marked
     * to be left out of the forecast are left out after "now" (unless before [WxGenParams.maxTime]), so a scripted
     * surprise does not give itself away. As in WeatherGen, the forecast moves the pattern and holds its evolution.
     */
    fun forecast(p: WxGenParams, x: Int, y: Int, interval: Double, steps: Int, exact: Boolean = false): List<Pair<CampaignTime, WxCell>> {
        val now = p.current.minutes.toDouble()
        val maxT = p.maxTime?.minutes?.toDouble()
        val hidden = p.copy(overrides = p.overrides.filterNot { it.excludeFromForecast })
        val times = listOf(now) + (0 until steps).map { i -> (((i + 1) * interval + now) / interval).toLong() * interval }
        return times.map { t ->
            val dt = t - now
            val d = drift(p, dt)
            val base = if (t == now || (maxT != null && t <= maxT)) p else hidden
            val at = CampaignTime.fromMinutes(t)
            val q = base.copy(current = at, timeOffset = base.timeOffset - dt, originX = base.originX + d[0], originY = base.originY + d[1])
            at to weather(q, x, y, exact)
        }
    }
}
