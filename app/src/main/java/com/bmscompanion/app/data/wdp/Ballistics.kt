package com.bmscompanion.app.data.wdp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Weapon Delivery Planner's bomb ballistics, ported to Kotlin so they run on a phone, a tablet and in a browser.
 *
 * **This is Falcas's work.** Weapon Delivery Planner is his program and this is his algorithm, ported; see `docs/WDP-PORT.md` for what was taken, what it is checked against and how the credit is carried.
 * BMS Companion adds nothing to the physics here — the point of a port is that the answers do not change.
 *
 * ## What it is
 *
 * A forward integration of an unguided bomb, stepped every tenth of a second from release until it reaches the
 * target's height. Gravity acts on the vertical, a drag term proportional to speed acts along the flight path, and
 * the horizontal distance covered is the bomb's range. The last part-step is interpolated rather than rounded, so
 * the answer does not jump by a step's worth of travel.
 *
 * Two things about it are worth knowing before trusting a number.
 *
 * **Drag is by category, not by store.** A bomb is low-drag, high-drag or cluster, and that chooses one coefficient
 * — 0.165 for slicks and cluster munitions, 1.0 for retarded. There is no per-weapon drag table anywhere in the
 * program, so two different low-drag bombs give exactly the same range. That is the model's resolution, and the
 * page says so rather than implying more.
 *
 * **A retarded bomb also falls faster.** When the coefficient reaches 1.0 the vertical acceleration is changed from
 * gravity to -20.91505 ft/s², which is not physics but a fitted figure standing in for a drogue's vertical drag.
 * It is kept exactly as it is: a "tidier" constant here would quietly move every high-drag release.
 *
 * The port is checked against the original program rather than against a reading of it — `--wdptest` runs 720
 * releases through both and insists on the same range and the same time of fall, to the digit each is printed to.
 */
object Ballistics {

    private const val KTS_TO_FT_SEC = 1.687778
    private const val GRAVITY = -32.177

    /** The drag categories the planner offers, and the one coefficient each of them carries. */
    enum class Drag(val label: String, val coefficient: Double) {
        LOW("Low", 0.165),
        HIGH("High", 1.0),
        CLUSTER("Cluster", 0.165);

        companion object {
            /** Falcas's own switch: anything unrecognised, including nothing at all, is treated as low-drag. */
            fun of(name: String?): Drag = entries.firstOrNull { it.label.equals(name, ignoreCase = true) } ?: LOW
        }
    }

    /** Where a bomb lands and how long it takes: range in feet along the ground, fall in seconds. */
    data class Shot(val rangeFt: Int, val timeOfFallSec: Double)

    /**
     * How far ahead of the release point a bomb travels, and how long it falls.
     *
     * [diveAngleDeg] is the flight-path angle at release, positive nose-up (TOSS passes its release angle) and
     * negative in a dive (Pop-up and HADB pass minus their dive angle): the first step adds sin(angle) x TAS to the
     * height. [tasKts] is true airspeed at release, [releaseHeightFt] the height above
     * the target — not above the sea, which is the mistake that makes a plan miss by the elevation of the target.
     */
    fun bombRange(diveAngleDeg: Int, tasKts: Int, releaseHeightFt: Int, drag: Drag): Shot {
        val step = 0.1
        val dragScale = 140.0
        var vertAccel = GRAVITY

        val diveRad = PI / 180.0 * diveAngleDeg
        val tasFtSec = tasKts * KTS_TO_FT_SEC
        // rounded to two places before the integration starts, as the original does: the whole run is sensitive to
        // the first step, and this is what makes the two programs agree digit for digit rather than nearly
        var vx = round2(cos(diveRad) * tasFtSec)
        var vy = round2(sin(diveRad) * tasFtSec)

        val k = drag.coefficient
        // a retarded bomb is given its own vertical acceleration, standing in for the drogue
        if (k >= 1.0) vertAccel = -20.91505

        // the first half-step, taken before the loop, exactly as the original
        var x = 0.0
        var y = releaseHeightFt.toDouble()
        var dx = vx
        var dy = vy + -16.0885
        vy += -32.177
        x += dx
        y += dy

        var tof = 1.0
        while (y > 0.0) {
            // the drag term acts along the path; the +0.1 keeps the divisor off zero when the bomb is falling
            // straight down, which is the one input that would otherwise end the run with a division by nothing
            val speed = sqrt(vx * vx + 0.1)
            var dragAccel = k * dragScale * (abs(vx) / speed)
            if (vx > 0.0) dragAccel = -dragAccel
            vx = if (vx <= 0.0) 0.0 else vx + dragAccel * step
            vy += vertAccel * step
            dx = vx * step + 0.5 * dragAccel * step * step
            dy = vy * step + 0.5 * vertAccel * step * step
            x += dx
            y += dy
            tof += step
        }

        // the bomb passed the target's height somewhere inside the last step: go back the fraction that overshot
        val overshoot = y / dy
        val seconds = round1(tof + overshoot * step)
        val range = x + overshoot * dx
        return Shot(halfUp(range).toInt(), seconds)
    }

    /** Convenience for the common case, where the drag comes from a weapon's own category name. */
    fun bombRange(diveAngleDeg: Int, tasKts: Int, releaseHeightFt: Int, dragName: String?): Shot =
        bombRange(diveAngleDeg, tasKts, releaseHeightFt, Drag.of(dragName))

    // ---------------------------------------------------------------- rounding
    //
    // .NET's Math.Round is banker's rounding: it breaks a tie to the nearest even digit, where Kotlin's roundToInt
    // always breaks upwards. Over 720 releases that difference shows up, so the two are separated here and each is
    // used exactly where the original uses it: Math.Round(x, n) for the working, and the same for the answer.

    private fun round2(v: Double) = bankers(v * 100.0) / 100.0
    private fun round1(v: Double) = bankers(v * 10.0) / 10.0

    /** .NET's Math.Round(x) — to even on a tie. */
    private fun halfUp(v: Double): Long = bankers(v).toLong()

    private fun bankers(v: Double): Double {
        val floor = kotlin.math.floor(v)
        val diff = v - floor
        return when {
            diff > 0.5 -> floor + 1.0
            diff < 0.5 -> floor
            // exactly a half: go to the even side
            floor.toLong() % 2L == 0L -> floor
            else -> floor + 1.0
        }
    }

    @Suppress("unused")
    private fun unusedRounding(v: Double) = v.roundToInt() + v.roundToLong()
}
