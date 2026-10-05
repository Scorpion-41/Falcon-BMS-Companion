package com.bmscompanion.app.data.wdp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The geometry the three attack pages (Pop-up, HADB, TOSS) share, written once and the right way round.
 *
 * WDP measures bearings on each page with its own four-quadrant arithmetic, and on TOSS it reads the target and the IP
 * back **east first** into slots it then treats as north first, so every VIP figure there is measured in a mirrored
 * frame: with the IP 8 nm from the target on a course of 020°, TOSS said 070.0° (90° − 20°) where Pop-up says 020.0°.
 * Here a bearing is one formula on sim feet, north and east named as such, used by all three pages.
 *
 * Positions are sim feet: north (x) and east (y), as the app's cartridge and the Mission map hold them.
 */
object AttackGeometry {
    /** A true bearing in degrees, [0, 360), and a range in feet. */
    class BrgRng(val bearingDeg: Double, val rangeFt: Double)

    /** From (n1, e1) to (n2, e2): true bearing, clockwise from north, and range. */
    fun bearingRange(n1: Double, e1: Double, n2: Double, e2: Double): BrgRng {
        val dn = n2 - n1
        val de = e2 - e1
        return BrgRng(wrap360(atan2(de, dn) * 180.0 / PI), sqrt(dn * dn + de * de))
    }

    /** The point [rangeFt] from (n, e) along the true bearing [bearingDeg]: (north, east). */
    fun lay(n: Double, e: Double, bearingDeg: Double, rangeFt: Double): Pair<Double, Double> {
        val b = bearingDeg * PI / 180.0
        return (n + cos(b) * rangeFt) to (e + sin(b) * rangeFt)
    }

    /** Degrees into [0, 360). */
    fun wrap360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0.0) d += 360.0
        if (d >= 360.0) d -= 360.0
        return d
    }

    /**
     * A bearing as the DED line prints it: one decimal, and a bearing that rounds to 360.0 is north, 0.0. WDP printed
     * "360.0" for the right-hand N of the compass on HADB, and a figure between 359.95 and 360 as "360.0" on all three.
     */
    fun bearingText(deg: Double, format: (Double) -> String): String {
        val t = format(wrap360(deg))
        return if (t == "360.0") "0.0" else t
    }

    /** The nav-offset keys of the VIP reference (measured from the IP) and of the VRP one (from the target). */
    val VIP_KEYS = listOf("VIP", "VIPPUP", "OA1_1", "OA2_1")
    val VRP_KEYS = listOf("VRP", "VRPPUP", "OA1_2", "OA2_2")

    /**
     * The lines Save to DTC writes: those of the reference in use only. The others keep what the cartridge holds —
     * a VIP planned in BMS's own DTC screen is not wiped by a VRP plan made here (D3).
     */
    fun <T> selectedOffsets(all: Map<String, T>, vip: Boolean): Map<String, T> {
        val keys = if (vip) VIP_KEYS else VRP_KEYS
        return all.filterKeys { it in keys }
    }

    /**
     * An ELEV figure for the jet. WDP hands BMS either 0 or a height above the target — the ingress altitude, the
     * pull-down, the release height — as if the target stood at sea level, and it never did. BMS reads the two
     * differently. **0 is ground level wherever the point is** (Dash-34 4.38, offset aimpoint sighting: "entering 0
     * for elevation places offsets at ground level, regardless of terrain MSL altitude"), so a point on the ground
     * keeps its 0. **Any other figure is feet above sea level**, so a height above the target becomes that height plus
     * the target's own elevation (the Training Manual on pop-ups: the profile's altitudes are above the ground and
     * "you need to add [the target elevation] to all numbers … to calculate MSL").
     */
    fun elevation(heightAboveTarget: Int, targetElevFt: Double): Int =
        if (heightAboveTarget == 0) 0 else heightAboveTarget + targetElevFt.roundToInt()

    /**
     * The ELEV figures the pilot typed over the page's own (forum, 2024: "impossible to put manually the elevations
     * for OA1, OA2, VRP, PUP"). A typed figure wins until the target changes — a new target is a new plan, and an
     * elevation typed for the old one would be wrong for it.
     *
     * Keys are the nav-offset line the figure belongs to ("VRP", "VRPPUP", "OA1_2", … "VIP", "OA2_1"), and [TGT] for
     * the ground under the target itself, which every ELEV above it is measured from.
     */
    class ElevOverrides {
        private val typed = LinkedHashMap<String, Int>()
        private var targetKey: String? = null

        /** Forgets every typed figure when the target is not the one they were typed for. */
        fun target(north: Double, east: Double) {
            val k = "${north.roundToInt()},${east.roundToInt()}"
            if (k != targetKey) { typed.clear(); targetKey = k }
        }

        /** A figure typed for [line]; null or blank puts the page's own back. */
        fun set(line: String, feet: Int?) { if (feet == null) typed.remove(line) else typed[line] = feet }

        /** The typed figure for [line] if there is one, otherwise the page's own. */
        fun of(line: String, own: Int): Int = typed[line] ?: own

        fun isTyped(line: String): Boolean = line in typed

        /**
         * The figure typed for [line] when it was typed for the target at (north, east) — read before [target] has
         * moved the overrides on to a new target, so a new target never takes the last one's ground.
         */
        fun typedFor(line: String, north: Double, east: Double): Int? =
            if ("${north.roundToInt()},${east.roundToInt()}" == targetKey) typed[line] else null

        fun clear() = typed.clear()
    }

    /** The ElevOverrides key of the ground under the target (typed on the Coordinates box's target Elv). */
    const val TGT = "TGT"
}
