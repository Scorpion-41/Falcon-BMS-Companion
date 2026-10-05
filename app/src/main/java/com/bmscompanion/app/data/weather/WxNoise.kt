/*
 * Ported from WeatherGen (src/weathergen/math.cljc in Tyrant's Virtual Mission Tools 0.63).
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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.truncate

/**
 * WeatherGen's noise and the small pieces of arithmetic its model is built from, function for function.
 *
 * Nothing here holds state or draws a random number: the "noise" is a hash of the coordinates, so the same inputs
 * give the same weather on a phone, in a browser and on the PC that writes the file — which is what lets the page
 * send a few hundred bytes of parameters and the PC build the identical map from them.
 *
 * Two details of the original matter for getting the same numbers out, and both are kept:
 * - `frac` takes off the **truncated** whole part (Clojure's `long`), so a negative number keeps a negative
 *   fraction; the callers then apply `mod 1.0`, which is **floored** (`Double.mod`, never `%`).
 * - `nearest` rounds halves **up** (Java's and JavaScript's `Math.round`), which is `roundToLong`, not `round`.
 */
object WxNoise {

    fun frac(x: Double): Double = x - truncate(x)

    fun clamp(min: Double, max: Double, v: Double): Double = when {
        v < min -> min
        max < v -> max
        else -> v
    }

    fun interpolate(x: Double, y: Double, f: Double): Double = (1.0 - f) * x + f * y

    /** `vector-interpolate`: [a] at [x1], [b] at [x2], straight between. */
    fun vectorInterpolate(a: DoubleArray, b: DoubleArray, x: Double, x1: Double, x2: Double): DoubleArray {
        val f = (x - x1) / (x2 - x1)
        return DoubleArray(a.size) { (1 - f) * a[it] + f * b[it] }
    }

    /** The hash: the fractional digits of a sine, far enough out to look random. */
    fun scramble(x: Double, seed: Double): Double {
        val a = abs(sin(x * seed))
        val b = a * 1E5
        return frac(b)
    }

    /** A value in 0..1 for each whole lattice point. */
    fun discreteNoise(x: Double, y: Double, seed: Double): Double = scramble(x * 65521 + y, seed)

    /** The lattice values blended across each square, so the field has no steps. */
    fun continuousNoise(x: Double, y: Double, seed: Double): Double {
        val xFrac = frac(x).mod(1.0)
        val yFrac = frac(y).mod(1.0)
        val xWhole = floor(x)
        val yWhole = floor(y)
        return interpolate(
            interpolate(discreteNoise(xWhole, yWhole, seed), discreteNoise(xWhole + 1, yWhole, seed), xFrac),
            interpolate(discreteNoise(xWhole, yWhole + 1, seed), discreteNoise(xWhole + 1, yWhole + 1, seed), xFrac),
            yFrac,
        )
    }

    /** Octaves of [continuousNoise] from [zoom] down to [floor], each half the size and half the weight. */
    fun fractal(x: Double, y: Double, zoom: Double, seed: Double, floor: Double): Double {
        var result = 0.0
        var z = zoom
        while (z >= floor) {
            result += continuousNoise(x / z, y / z, seed) * (z / zoom / 2.0)
            z /= 2
        }
        return result
    }

    fun magnitude(x: Double, y: Double): Double = sqrt(x * x + y * y)

    /** Compass heading of a vector (x east, y north), 0..360. */
    fun heading(x: Double, y: Double): Double = (atan2(x, y) * 180.0 / PI).mod(360.0)

    /** Round [x] to the nearest [n]: `nearest(15, 10)` is 20. */
    fun nearest(x: Double, n: Double): Double = (x / n).roundToLong() * n

    fun degToRad(deg: Double): Double = deg * PI / 180.0

    /** Clockwise by [deg]. */
    fun rotate(deg: Double, x: Double, y: Double): DoubleArray {
        val rad = degToRad(-deg)
        val cs = cos(rad)
        val sn = sin(rad)
        return doubleArrayOf(x * cs - y * sn, x * sn + y * cs)
    }

    fun normalize(v: DoubleArray): DoubleArray {
        val m = magnitude(v[0], v[1])
        return if (m == 0.0) v else doubleArrayOf(v[0] / m, v[1] / m)
    }

    /**
     * Maps [x] in 0..1 onto min..max, gathered about [mean]: a gamma-like shape, where a lower [shape] spreads the
     * values out and one below 1 makes the extremes the likely ones.
     */
    fun distribute(x: Double, min: Double, mean: Double, max: Double, shape: Double): Double {
        val x1 = x * 2 - 1
        val x2 = abs(x1).pow(shape)
        return if (x1 < 0) (1 - x2) * (mean - min) + min else x2 * (max - mean) + mean
    }

    /**
     * Throws away the tails of a value in 0..1 that clusters about 0.5 and stretches the rest over the whole range:
     * [spread] 0 changes nothing, 1 leaves only the middle.
     */
    fun rejectTails(spread: Double, x: Double): Double {
        val x1 = (x - 0.5) * 2 * (1 / (1 - spread))
        return clamp(-1.0, 1.0, x1) / 2 + 0.5
    }
}
