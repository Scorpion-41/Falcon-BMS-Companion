package com.bmscompanion.app.data.wdp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.round
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.withSign

/**
 * What the Pop-up page reads outside itself, as Weapon Delivery Planner keeps it: the part of `clsCoordinates` that
 * turns a target's sim feet into the "37,24.123" / "127,01.456" strings on its coordinate labels, and the part of
 * `fclsMain` that answers the terrain height under it. Falcas's code, ported.
 *
 * **Two projections go through these functions** (D26). On every 1,024 km theater the pages are handed **WDP's
 * grid** ([WdpCoords.bmsGrid], through [WdpCoords.coordData]), because that is the latitude and longitude Falcon BMS
 * itself gives: its ACMI recordings and its AIPs' "BMS coord" agree with it to the print's thousandth of a minute.
 * It is carried as WDP carries it — the grid arithmetic in floats, whole degrees by floor — so a page prints WDP's
 * own strings. Where that grid is not established (the Falklands, 2,048 km) the pages get the terrain's
 * **projection string** ([TransverseMercatorMeta.appProjection], [WdpCoords.appMeta]): the feet go straight to
 * metres (3.27998 ft/m) and through the same GeographicLib series in doubles, and a southern or western position is
 * printed sign and magnitude: "-51,49.368" is 51°49.368′ S — the form the DTC page's hemisphere boxes already read
 * (WDP's floor of the signed degrees would print it "-52,10.632").
 *
 * - **`FeetToCoordsBoth`**: before a theater is set every figure is zero and the answer is `00,00.000/000,00.000`
 *   (which is what makes the page hide the labels). An old-terrain theater is a sphere around an origin
 *   (`FeetToCoords`: note it checks the **north** against the theater's **width**, and prints the latitude's
 *   degrees with no padding); a new-terrain one is a transverse Mercator (`ConvertSimXYToLatLon`, which carries the
 *   grid arithmetic in floats, then GeographicLib's UTM `TransverseMercator.Reverse`).
 * - **`ReadNewTerrainElvLoc`**: the heightmap's own size is worked out from the file's length, the cell is found
 *   by rounding, and anything off the map is -998 (an overflow caught) or -999 (no file, or past its end).
 * - **`ConvertLatLonToFeet`** and its steps, the way back from a label to feet that the TOSS page's `CoordFlow`
 *   takes, with GeographicLib's `TransverseMercator.Forward`; `WdpCoords` sets a theater's projection up with it.
 *
 * Checked against the program: `wdpref page Popup` samples `FeetToCoordsBoth` under an origin-less, two
 * old-terrain and three new-terrain theaters, and `ReadNewTerrainElvLoc` against a synthetic heightmap, several
 * thousand points each, and the page test demands the same strings and heights; `wdpref page Coords` does the same
 * both ways under every theater of a BMS install (`--wdppagetest coords`).
 */
object PopupCoords {

    /** `clsPhyconst`'s figures, floats as WDP declares them. */
    const val FT_PER_DEGREE = 365221.875f
    const val EARTH_RADIUS_FT = 2.09257E+07f
    const val DTR = 0.01745329f
    const val RTD = 57.29578f
    const val DEG_TO_MIN = 60f

    /**
     * BMSUtils' `F4Structs.TransverseMercatorMeta`, as a theater's NewTerrain data fill it (the grid BMS's own latitude
     * and longitude come from, [WdpCoords.meta]) — or, when [ftPerM] is set, the terrain's projection string
     * ([WdpCoords.appMeta], only where that grid is not established): [meridian] its `lon_0`, [offsetX]/[offsetY] its
     * false easting and northing negated (metres about the meridian = feet ÷ [ftPerM] + offset), [k0] and [lat0] its
     * scale and origin latitude. The grid fields are left at zero on that path.
     */
    data class TransverseMercatorMeta(
        val meridian: Double = 0.0,
        val offsetX: Double = 0.0,
        val offsetY: Double = 0.0,
        val theaterSizeInMeters: Long = 0,
        val heightmapSize: Float = 0f,
        val meterRes: Float = 0f,
        val ftToGrid: Float = 0f,
        val gridToFt: Float = 0f,
        val gridOffset: Float = 0f,
        /** feet per metre of the projection string (3.27998); 0 = the grid above */
        val ftPerM: Double = 0.0,
        val k0: Double = UTM_K0,
        val lat0: Double = 0.0,
    ) {
        /** The projection string rather than the grid (the Falklands). */
        val appProjection: Boolean get() = ftPerM > 0.0
    }

    /** GeographicLib's UTM scale, which [Utm] is built on; another theater scale is applied as a ratio to it. */
    const val UTM_K0 = 0.9996

    /** The page's `objC` after `SetCoordData` (or before it: all zero). */
    data class CoordData(
        val originLat: Double = 0.0,
        val originLong: Double = 0.0,
        val campW: Double = 0.0,
        val campH: Double = 0.0,
        val enableNewTerrain: Boolean = false,
        val tm: TransverseMercatorMeta = TransverseMercatorMeta(),
    )

    const val ZERO = "00,00.000/000,00.000"

    /** `clsCoordinates.FeetToCoordsBoth(FeetN, FeetE)`. */
    fun feetToCoordsBoth(c: CoordData, feetN: Double, feetE: Double): String {
        if (feetE == 0.0 && feetN == 0.0) return ZERO
        // The projection string keeps the feet as doubles: a float of three million feet is a quarter of a foot out, and
        // one print in twenty would land on the other side of a thousandth of a minute
        if (c.enableNewTerrain && c.tm.appProjection) return latLonLabel(simToLatLon(c.tm, feetN, feetE))
        if (c.enableNewTerrain) {
            val ll = convertSimXYToLatLon(c.tm, feetN.toFloat(), feetE.toFloat())
            return coordinatesToDegMin(ll.first, ll.second)
        }
        return feetToCoords(c, feetE, feetN)
    }

    /** `clsCoordinates.FeetToCoords(FeetE, FeetN)`: the old terrain's sphere. */
    fun feetToCoords(c: CoordData, feetE: Double, feetN: Double): String {
        if (feetE < 0.0 || feetE > c.campW) return ZERO
        if (feetN < 0.0 || feetN > c.campW) return ZERO      // the width, for the north too
        val num = min(feetE, c.campW)
        val num2 = min(feetN, c.campH)
        if (num == 0.0) return ZERO
        if (num2 == 0.0) return ZERO
        val num3 = (c.originLat * FT_PER_DEGREE.toDouble() + num2) / EARTH_RADIUS_FT.toDouble()
        val num4 = cos(num3)
        var num5 = (c.originLong * DTR.toDouble() * EARTH_RADIUS_FT.toDouble() * num4 + num) / (EARTH_RADIUS_FT.toDouble() * num4)
        val num6 = num3 * RTD.toDouble()
        num5 *= RTD.toDouble()
        var num7 = truncate(num6)
        var value = abs(num6 - num7.toDouble()) * DEG_TO_MIN.toDouble()
        value = round(value, 3)
        if (value >= 60.0) { value -= 60.0; num7++ }
        val text2 = num7.toString() + "," + PopupNet.fmt(value, 2, 3)
        var num8 = truncate(num5)
        var value2 = abs(num5 - num8.toDouble()) * DEG_TO_MIN.toDouble()
        value2 = round(value2, 3)
        if (value2 >= 60.0) { value2 -= 60.0; num8++ }
        val text3 = PopupNet.fmtInt(num8, 3) + "," + PopupNet.fmt(value2, 2, 3)
        return "$text2/$text3"
    }

    /** `(int)d` in a checked block: toward zero, an overflow outside the range. */
    private fun truncate(d: Double): Int {
        if (d.isNaN() || d >= 2147483648.0 || d <= -2147483649.0) throw PopupPlan.PopupOverflow()
        return d.toInt()
    }

    /** .NET Framework's `Math.Round(double, digits)`: scaled, to even, scaled back. */
    fun round(v: Double, digits: Int): Double {
        if (abs(v) >= 1e16) return v
        var p = 1.0
        repeat(digits) { p *= 10.0 }
        return TossPlan.bankers(v * p).toDouble() / p
    }

    /** `ConvertSimXYToLatLon(simX = north, simY = east)`: into the grid in floats, then the projection. */
    fun convertSimXYToLatLon(tm: TransverseMercatorMeta, simX: Float, simY: Float): Pair<Double, Double> {
        if (tm.appProjection) return simToLatLon(tm, simX.toDouble(), simY.toDouble())
        // float locals that never live across a call: the x87 keeps them at double precision (sampled: as floats,
        // one coordinate in twenty lands on the other side of a thousandth of a minute)
        val num = (0f - tm.gridOffset).toDouble() + simY.toDouble() * tm.ftToGrid.toDouble()
        val num2 = (0f - tm.gridOffset).toDouble() + simX.toDouble() * tm.ftToGrid.toDouble()
        return convertXZToLatLon(tm, num, num2)
    }

    private fun convertXZToLatLon(tm: TransverseMercatorMeta, x0: Double, z0: Double): Pair<Double, Double> {
        var x = x0
        var z = z0
        x += (0.5f * tm.heightmapSize).toDouble()
        z = 0.0 - (z - (0.5f * tm.heightmapSize).toDouble())
        x *= tm.meterRes.toDouble()
        z *= tm.meterRes.toDouble()
        z = tm.theaterSizeInMeters.toDouble() - z
        val x2 = x + tm.offsetX
        val y = z + tm.offsetY
        return Utm.reverse(tm.meridian, x2, y)
    }

    /** `CoordinatesToDegMin`: whole degrees by Floor, minutes as floats to three places. */
    fun coordinatesToDegMin(lat: Double, lon: Double): String {
        var num = floorInt(lat)
        var num2 = (abs(lat - num.toDouble()) * DEG_TO_MIN.toDouble()).toFloat()
        var num3 = floorInt(lon)
        var num4 = (abs(lon - num3.toDouble()) * DEG_TO_MIN.toDouble()).toFloat()
        num2 = round(num2.toDouble(), 3).toFloat()
        num4 = round(num4.toDouble(), 3).toFloat()
        if (num2 >= 60f) { num2 -= 60f; num++ }
        if (num4 >= 60f) { num4 -= 60f; num3++ }
        val latitude = PopupNet.fmtInt(num, 2) + "," + PopupNet.fmt(num2, 2, 3)
        val longitude = PopupNet.fmtInt(num3, 3) + "," + PopupNet.fmt(num4, 2, 3)
        return "$latitude/$longitude"
    }

    private fun floorInt(d: Double): Int = truncate(floor(d))

    // ---------------------------------------------------------------- the projection string (D26: where BMS's grid is not established)

    /**
     * (latitude, longitude) of a point [north] and [east] theater feet, on the projection string: metres about the
     * meridian, then GeographicLib's reverse series. A scale other than UTM's is a plain ratio of the metres, and an
     * origin latitude other than the equator adds that latitude's meridian distance, as PROJ's tmerc does.
     */
    fun simToLatLon(tm: TransverseMercatorMeta, north: Double, east: Double): Pair<Double, Double> {
        val s = UTM_K0 / tm.k0
        val x = (east / tm.ftPerM + tm.offsetX) * s
        val y = (north / tm.ftPerM + tm.offsetY) * s + originNorthing(tm)
        return Utm.reverse(tm.meridian, x, y)
    }

    /** Theater feet (north, east) of a latitude and longitude on the projection string; [simToLatLon] the other way. */
    fun latLonToSim(tm: TransverseMercatorMeta, lat: Double, lon: Double): Pair<Double, Double> {
        val s = UTM_K0 / tm.k0
        val (fx, fy) = Utm.forward(tm.meridian, lat, lon)
        val east = (fx / s - tm.offsetX) * tm.ftPerM
        val north = ((fy - originNorthing(tm)) / s - tm.offsetY) * tm.ftPerM
        return north to east
    }

    private fun originNorthing(tm: TransverseMercatorMeta): Double =
        if (tm.lat0 == 0.0) 0.0 else Utm.forward(tm.meridian, tm.lat0, tm.meridian).second

    /**
     * "37,24.123/127,01.456" for the projection string: degrees and minutes of the **magnitude**, a "-" for south and west
     * ("-51,49.368/-058,26.842"), minutes rounded once, in doubles, to the nearest thousandth (the last digit of the
     * DED's STPT page), carrying 60.000 into the degree. WDP floored the signed degrees and printed the minutes above
     * the floor, so every southern and western position read as the next degree out less the minutes (D26).
     */
    fun latLonLabel(ll: Pair<Double, Double>): String = part(ll.first, 2) + "/" + part(ll.second, 3)

    private fun part(v: Double, degDigits: Int): String {
        val a = abs(v)
        var deg = floor(a)
        var thousandths = kotlin.math.round((a - deg) * 60.0 * 1000.0)
        if (thousandths >= 60000.0) { thousandths -= 60000.0; deg += 1.0 }
        val sign = if (v < 0 && (deg > 0.0 || thousandths > 0.0)) "-" else ""
        return sign + PopupNet.fmtInt(truncate(deg), degDigits) + "," + PopupNet.fmt(thousandths / 1000.0, 2, 3)
    }

    /**
     * One label ("-51,49.368", "127,01.456") back to signed decimal degrees on the projection string: the magnitude's
     * degrees and minutes, then the sign; null when it is not that form or the minutes are not below 60.
     */
    fun labelToDegrees(s: String): Double? {
        val t = s.trim()
        val neg = t.startsWith("-")
        val body = t.removePrefix("-").removePrefix("+")
        val deg = body.substringBefore(',', "").toIntOrNull() ?: return null
        val min = body.substringAfter(',', "").toDoubleOrNull() ?: return null
        if (deg < 0 || min < 0.0 || min >= 60.0) return null
        val v = deg + min / 60.0
        return if (neg) -v else v
    }

    /**
     * The two halves of a label pair with the hemisphere spelled as a letter, the way the jet's DED writes it:
     * ("N37,05.410", "E127,01.797"), ("S51,49.368", "W058,26.842"). For a page that prints its own "N"/"E" before
     * the figures, which on a southern or western theater would read "N-51,…".
     */
    fun hemispheres(coords: String): Pair<String, String> {
        val n = getNorthDeg(coords)
        val e = getEastDeg(coords)
        return (if (n.startsWith("-")) "S" + n.substring(1) else "N$n") to (if (e.startsWith("-")) "W" + e.substring(1) else "E$e")
    }

    /** `GetNorthDeg`: everything before the "/" (and, with none, all but the last character). */
    fun getNorthDeg(coords: String?): String {
        if (coords == null) return "00,00.000"
        // "": the loop never meets its end (1 is never 0) and runs its checked counter into an overflow
        if (coords.isEmpty()) throw PopupPlan.PopupOverflow()
        var i = 1
        val length = coords.length
        while (i != length && coords.getOrNull(i - 1) != '/') i++
        return mid(coords, 1, i - 1)
    }

    /** `GetEastDeg`: everything after the "/". */
    fun getEastDeg(coords: String?): String {
        if (coords == null) return "000,00.000"
        if (coords.isEmpty()) throw PopupPlan.PopupOverflow()
        var i = 1
        val length = coords.length
        while (i != length && coords.getOrNull(i - 1) != '/') i++
        return mid(coords, i + 1, length)
    }

    /** VB `Strings.Mid(s, start, length)`, 1-based. */
    private fun mid(s: String, start: Int, length: Int): String {
        if (start > s.length || length <= 0) return ""
        val from = start - 1
        return s.substring(from, min(s.length, from + length))
    }

    // ---------------------------------------------------------------- and back to feet

    /** `Strings.Mid` given a start below 1 or a negative length: VB's `ArgumentException`. */
    class VbArgument : PopupPlan.PopupError("Argument 'Length' must be greater or equal to zero.")

    /** VB `Strings.Mid(s, start, length)` with its checks: a start below 1 or a negative length throws. */
    private fun vbMid(s: String, start: Int, length: Int): String {
        if (start < 1 || length < 0) throw VbArgument()
        return mid(s, start, length)
    }

    /**
     * `ConvertLatLonToFeet(Coords, ref simXY)`: a coordinate label back to feet, **as text**: each figure a float
     * printed by `Conversions.ToString` (seven significant digits), which is all the precision the TOSS and HADB
     * pages' `CoordFlow` gets back. Mind the order: it is **east first**, "east,north". `ConvertLatLonToSimXY`
     * calls the east figure simX, where `ConvertSimXYToLatLon` calls the north one simX, and the pages read the
     * string the first way.
     */
    fun convertLatLonToFeet(c: CoordData, coords: String): String {
        if (coords == ZERO) return "0.000000, 0.000000"
        if (c.tm.appProjection) {
            // The projection string reads the label as it is printed (sign and magnitude) and stays in doubles to the end
            val lat = labelToDegrees(getNorthDeg(coords)) ?: 0.0
            val lon = labelToDegrees(getEastDeg(coords)) ?: 0.0
            val (north, east) = latLonToSim(c.tm, lat, lon)
            return PopupNet.str(east.toFloat()) + "," + PopupNet.str(north.toFloat())
        }
        val (lat, lon) = coordinatesToDec(getNorthDeg(coords), getEastDeg(coords))
        val (simX, simY) = convertLatLonToSimXY(c.tm, lat.toFloat(), lon.toFloat())
        return PopupNet.str(simX) + "," + PopupNet.str(simY)
    }

    /**
     * `CoordinatesToDec(latitude, longitude)`: "37,24.123" is 37 whole degrees (VB's `ToInteger`, so "37.5" rounds to
     * even) plus 24.123 / 60 cut to a float. The sum is left at the x87's precision, which for these figures is the
     * exact sum; the caller cuts it to a float.
     */
    fun coordinatesToDec(latitude: String, longitude: String): Pair<Double, Double> {
        fun one(s: String): Double {
            val num = s.indexOf(",")
            val text = vbMid(s, 1, num)
            val deg = if (PopupNet.isNumeric(text)) PopupNet.toInteger(text) else 0
            val text2 = vbMid(s, num + 2, s.length)
            val min = if (PopupNet.isNumeric(text2)) (PopupNet.toDouble(text2) / DEG_TO_MIN.toDouble()).toFloat() else 0f
            return deg.toFloat().toDouble() + min.toDouble()
        }
        val lat = one(latitude)
        return lat to one(longitude)
    }

    /**
     * `ConvertLatLonToSimXY(lat, lon, ref simX, ref simY)` with `ConvertLatLonToXZ`: the forward projection, less the
     * theater's offsets, into grid cells as floats, then feet. Returns (simX, simY) = (**east, north**). The last step,
     * `(GRID_OFFSET + x) * GRID_TO_FT`, is float arithmetic the x87 carries at double precision and rounds once, into
     * the float it is stored in.
     */
    fun convertLatLonToSimXY(tm: TransverseMercatorMeta, lat: Float, lon: Float): Pair<Float, Float> {
        if (tm.appProjection) latLonToSim(tm, lat.toDouble(), lon.toDouble()).let { (n, e) -> return e.toFloat() to n.toFloat() }
        val (fx, fy) = Utm.forward(tm.meridian, lat.toDouble(), lon.toDouble())
        var x2 = fx - tm.offsetX
        var y = fy - tm.offsetY
        y = tm.theaterSizeInMeters.toDouble() - y
        x2 /= tm.meterRes.toDouble()
        y /= tm.meterRes.toDouble()
        val half = (0.5f * tm.heightmapSize).toDouble()
        val z = (half - y).toFloat()
        val x = (x2 - half).toFloat()
        val simX = ((tm.gridOffset.toDouble() + x.toDouble()) * tm.gridToFt.toDouble()).toFloat()
        val simY = ((tm.gridOffset.toDouble() + z.toDouble()) * tm.gridToFt.toDouble()).toFloat()
        return simX to simY
    }

    /** GeographicLib's `TransverseMercator.Forward(lon0, lat, lon)`: (x east of the meridian, y north), metres. */
    fun transverseMercatorForward(lon0: Double, lat: Double, lon: Double): Pair<Double, Double> = Utm.forward(lon0, lat, lon)

    /** GeographicLib's `TransverseMercator.Reverse(lon0, x, y)`: (latitude, longitude), degrees. */
    fun transverseMercatorReverse(lon0: Double, x: Double, y: Double): Pair<Double, Double> = Utm.reverse(lon0, x, y)

    // ---------------------------------------------------------------- the terrain

    /**
     * `fclsMain.ReadNewTerrainElvLoc(feetNorth, feetEast)` over a `Heightmap.raw` given as its bytes (null: no file),
     * with `fclsMain.CampH`/`CampW`.
     */
    fun readNewTerrainElvLoc(heightmap: ByteArray?, campH: Double, campW: Double, feetNorth: Float, feetEast: Float): Int {
        var result = -999
        if (heightmap == null) return result
        try {
            val len = heightmap.size.toLong()
            if (len < 2) throw PopupPlan.PopupOverflow()   // ReadUInt16 past the end
            val num = toUInt(PopupPlan.bankersD(sqrt(len.toDouble() / 2.0)))
            var num2 = toUInt(PopupPlan.bankersD((campH - feetNorth.toDouble()) / campH * num.toDouble()))
            var num3 = toUInt(PopupPlan.bankersD(feetEast.toDouble() / campW * num.toDouble()))
            if (num2 > num) num2 = num
            if (num3 > num) num3 = num
            val prod = num2 * num
            if (prod > 0xffffffffL) throw PopupPlan.PopupOverflow()
            val num4L = prod * 2L + num3 * 2L
            if (num4L > 0xffffffffL) throw PopupPlan.PopupOverflow()
            val num4 = num4L
            if (num4 >= 0L && num4 < len) {
                if (num4 + 2 > len) throw PopupPlan.PopupOverflow()   // ReadInt16 past the end
                val i = num4.toInt()
                result = ((heightmap[i].toInt() and 0xff) or (heightmap[i + 1].toInt() shl 8)).toShort().toInt()
            }
        } catch (e: PopupPlan.PopupError) {
            result = -998
        }
        return result
    }

    /** `(uint)d` in a checked block. */
    private fun toUInt(d: Double): Long {
        if (d.isNaN() || d < 0.0 || d >= 4294967296.0) throw PopupPlan.PopupOverflow()
        return d.toLong()
    }

    // ---------------------------------------------------------------- the projection

    /**
     * GeographicLib's `TransverseMercator` with its default (UTM) ellipsoid and scale — WGS84, k0 = 0.9996 — which is
     * what WDP's `new TransverseMercator()` is. Krüger's series to sixth order, as GeographicLib evaluates it.
     */
    private object Utm {
        private const val A = 6378137.0
        private const val F = 1.0 / 298.257223563
        private const val K0 = 0.9996
        private val n = F / (2 - F)
        private val e2 = F * (2 - F)
        private val es = sqrt(abs(e2))
        private val e2m = 1 - e2
        private val b1: Double
        private val a1: Double
        private val bet = DoubleArray(7)
        private val alp = DoubleArray(7)

        init {
            val n2 = n * n
            b1 = (((n2 + 4) * n2 + 64) * n2 + 256) / (256 * (1 + n))
            a1 = b1 * A
            val coeff = doubleArrayOf(
                384796.0, -382725.0, -6720.0, 932400.0, -1612800.0, 1209600.0, 2419200.0,
                -1118711.0, 1695744.0, -1174656.0, 258048.0, 80640.0, 3870720.0,
                22276.0, -16929.0, -15984.0, 12852.0, 362880.0,
                -830251.0, -158400.0, 197865.0, 7257600.0,
                -435388.0, 453717.0, 15966720.0,
                20648693.0, 638668800.0,
            )
            var o = 0
            var d = n
            for (l in 1..6) {
                val m = 6 - l
                var p = 0.0
                for (k in 0..m) p = p * n + coeff[o + k]
                bet[l] = d * p / coeff[o + m + 1]
                o += m + 2
                d *= n
            }
            // the forward series' coefficients, the same way (GeographicLib 1.51, sixth order)
            val alpCoeff = doubleArrayOf(
                31564.0, -66675.0, 34440.0, 47250.0, -100800.0, 75600.0, 151200.0,
                -1983433.0, 863232.0, 748608.0, -1161216.0, 524160.0, 1935360.0,
                670412.0, 406647.0, -533952.0, 184464.0, 725760.0,
                6601661.0, -7732800.0, 2230245.0, 7257600.0,
                -13675556.0, 3438171.0, 7983360.0,
                212378941.0, 319334400.0,
            )
            o = 0
            d = n
            for (l in 1..6) {
                val m = 6 - l
                var p = 0.0
                for (k in 0..m) p = p * n + alpCoeff[o + k]
                alp[l] = d * p / alpCoeff[o + m + 1]
                o += m + 2
                d *= n
            }
        }

        /** Radians in a degree, as GeographicLib has it: atan2(0, -1) / 180. */
        private const val DEGREE = PI / 180

        /**
         * `TransverseMercator::Forward` of GeographicLib 1.51, which WDP's NETGeographic.dll is (its `VersionInfo`
         * says so): the longitude difference taken exactly, the parity enforced, the conformal latitude, then
         * Clenshaw's sum of the sixth-order series in complex arithmetic, written out as MSVC's `std::complex` does it.
         */
        fun forward(lon0: Double, lat0: Double, lon1: Double): Pair<Double, Double> {
            var lat = if (abs(lat0) > 90) Double.NaN else lat0          // LatFix
            var lon = angDiff(lon0, lon1)
            var latsign = if (lat < 0) -1 else 1
            val lonsign = if (lon < 0) -1 else 1
            lon *= lonsign
            lat *= latsign
            val backside = lon > 90
            if (backside) {
                if (lat == 0.0) latsign = -1
                lon = 180 - lon
            }
            val (sphi, cphi) = sincosd(lat)
            val (slam, clam) = sincosd(lon)
            val etap: Double
            val xip: Double
            if (lat != 90.0) {
                val tau = sphi / cphi
                val taup = if (tau.isFinite()) taupf(tau) else tau
                xip = atan2(taup, clam)
                etap = asinhE(slam / hypot(taup, clam))
            } else {
                xip = PI / 2
                etap = 0.0
            }
            val c0 = cos(2 * xip)
            val ch0 = cosh(2 * etap)
            val s0 = sin(2 * xip)
            val sh0 = sinh(2 * etap)
            // a = 2 cos(2 zeta'); y0 and y1 are the Clenshaw sums (the derivative's, z, only feed gamma and k)
            val ar = 2 * c0 * ch0
            val ai = -2 * s0 * sh0
            var nn = 6
            var y0r = 0.0; var y0i = 0.0; var y1r = 0.0; var y1i = 0.0
            while (nn > 0) {
                var tr = ar * y0r - ai * y0i - y1r + alp[nn]
                var ti = ar * y0i + ai * y0r - y1i
                y1r = tr; y1i = ti
                nn--
                tr = ar * y1r - ai * y1i - y0r + alp[nn]
                ti = ar * y1i + ai * y1r - y0i
                y0r = tr; y0i = ti
                nn--
            }
            // a = sin(2 zeta'), y1 = zeta' + a * y0
            val br = s0 * ch0
            val bi = c0 * sh0
            val xi = xip + (br * y0r - bi * y0i)
            val eta = etap + (br * y0i + bi * y0r)
            val y = a1 * K0 * (if (backside) PI - xi else xi) * latsign
            val x = a1 * K0 * eta * lonsign
            return x to y
        }

        /** `Math::sincosd`: reduced exactly to within 45° of a multiple of 90 (`remquo`) before going to radians. */
        private fun sincosd(x: Double): Pair<Double, Double> {
            var q = round(x / 90.0)
            var r = x - q * 90.0
            if (r > 45.0) { r -= 90.0; q += 1.0 } else if (r < -45.0) { r += 90.0; q -= 1.0 }
            if (abs(r) == 45.0 && q % 2.0 != 0.0) { if (r > 0) { r -= 90.0; q += 1.0 } else { r += 90.0; q -= 1.0 } }
            if (r == 0.0) r = 0.0.withSign(x)
            r *= DEGREE
            val s = sin(r)
            val c = cos(r)
            var sinx: Double
            var cosx: Double
            when ((if (q.isNaN()) 0 else q.toInt()) and 3) {
                0 -> { sinx = s; cosx = c }
                1 -> { sinx = c; cosx = -s }
                2 -> { sinx = -s; cosx = -c }
                else -> { sinx = -c; cosx = s }
            }
            if (x != 0.0) { sinx += 0.0; cosx += 0.0 }
            return sinx to cosx
        }

        /** C's `remainder(x, 360)`: x less the nearest multiple of 360, the even one on a tie; exact. */
        private fun rem360(x: Double): Double {
            if (!x.isFinite()) return Double.NaN
            var n = round(x / 360.0)
            var r = x - n * 360.0
            if (r > 180.0) { r -= 360.0; n += 1.0 } else if (r < -180.0) { r += 360.0; n -= 1.0 }
            if (abs(r) == 180.0 && n % 2.0 != 0.0) { if (r > 0) r -= 360.0 else r += 360.0 }
            return if (r == 0.0) 0.0.withSign(x) else r
        }

        /** `Math::sum`: the rounded sum and its exact error. */
        private fun sum(u: Double, v: Double): Pair<Double, Double> {
            val s = u + v
            var up = s - v
            var vpp = s - up
            up -= u
            vpp -= v
            return s to -(up + vpp)
        }

        /** `Math::AngDiff(x, y)`: y - x, reduced to (-180, 180], computed exactly and then rounded. */
        private fun angDiff(x: Double, y: Double): Double {
            val (d0, t) = sum(rem360(-x), rem360(y))
            var d = rem360(d0)
            if (d == -180.0) d = 180.0                                       // AngNormalize
            return sum(if (d == 180.0 && t > 0) -180.0 else d, t).first
        }

        private fun eatanhe(x: Double): Double = es * atanhE(es * x)

        private fun taupf(tau: Double): Double {
            val tau1 = hypot(1.0, tau)
            val sig = sinh(eatanhe(tau / tau1))
            return hypot(1.0, sig) * tau - sig * tau1
        }

        /** `Math::tauf` (1.51): Newton on `taupf`, from a first guess, with its own `1 - es²` (not `1 - e2`). */
        private fun tauf(taup: Double): Double {
            val tol = sqrt(Math_ulp1) / 10
            val taumax = 2 / sqrt(Math_ulp1)
            val e2m = 1.0 - es * es
            var tau = if (abs(taup) > 70) taup * exp(eatanhe(1.0)) else taup / e2m
            val stol = tol * max(1.0, abs(taup))
            if (!(abs(tau) < taumax)) return tau
            for (i in 0 until 5) {
                val taupa = taupf(tau)
                val dtau = (taup - taupa) * (1 + e2m * (tau * tau)) / (e2m * hypot(1.0, tau) * hypot(1.0, taupa))
                tau += dtau
                if (!(abs(dtau) >= stol)) break
            }
            return tau
        }

        private const val Math_ulp1 = 2.220446049250313E-16

        /**
         * `TransverseMercator::Reverse` of GeographicLib 1.51: (latitude, longitude) in degrees for a point [x] east
         * of the meridian and [y] north of the equator, metres. The angles come out through `atan2d`/`atand`, which
         * fold the argument into ±45° before dividing by a degree; the longitude is normalised once, with the
         * meridian added.
         */
        fun reverse(lon0: Double, x: Double, y: Double): Pair<Double, Double> {
            var xi = y / (a1 * K0)
            var eta = x / (a1 * K0)
            val xisign = if (xi < 0) -1 else 1
            val etasign = if (eta < 0) -1 else 1
            xi *= xisign
            eta *= etasign
            val backside = xi > PI / 2
            if (backside) xi = PI - xi
            val c0 = cos(2 * xi)
            val ch0 = cosh(2 * eta)
            val s0 = sin(2 * xi)
            val sh0 = sinh(2 * eta)
            // complex a = 2 cos(2 zeta), and the Clenshaw sums y (the series) — z (its derivative) is not needed
            val ar = 2 * c0 * ch0
            val ai = -2 * s0 * sh0
            var nn = 6
            var y0r = 0.0; var y0i = 0.0; var y1r = 0.0; var y1i = 0.0
            while (nn > 0) {
                var tr = ar * y0r - ai * y0i - y1r - bet[nn]
                var ti = ar * y0i + ai * y0r - y1i
                y1r = tr; y1i = ti
                nn--
                tr = ar * y1r - ai * y1i - y0r - bet[nn]
                ti = ar * y1i + ai * y1r - y0i
                y0r = tr; y0i = ti
                nn--
            }
            // a = sin(2 zeta), y1 = zeta + a * y0
            val br = s0 * ch0
            val bi = c0 * sh0
            val xip = xi + (br * y0r - bi * y0i)
            val etap = eta + (br * y0i + bi * y0r)
            val s = sinh(etap)
            val cx = cos(xip)
            val c = if (0.0 < cx) cx else 0.0                           // std::max(0, cos): cos(pi/2) may be negative
            val r = hypot(s, c)
            var lat: Double
            var lon: Double
            if (r != 0.0) {
                lon = atan2d(s, c)
                val tau = tauf(sin(xip) / r)
                lat = atan2d(tau, 1.0)                                  // atand
            } else {
                lat = 90.0
                lon = 0.0
            }
            lat *= xisign
            if (backside) lon = 180 - lon
            lon *= etasign
            lon = rem360(lon + lon0)
            if (lon == -180.0) lon = 180.0                              // AngNormalize
            return lat to lon
        }

        /** `Math::atan2d`: the ratio folded into ±45° first, then to degrees, then back to its octant. */
        private fun atan2d(y0: Double, x0: Double): Double {
            var y = y0
            var x = x0
            var q = 0
            if (abs(y) > abs(x)) { val t = x; x = y; y = t; q = 2 }
            if (x < 0) { x = -x; ++q }
            var ang = atan2(y, x) / DEGREE
            when (q) {
                1 -> ang = (if (y >= 0) 180 else -180) - ang
                2 -> ang = 90 - ang
                3 -> ang = -90 + ang
            }
            return ang
        }

        /**
         * `asinh`, to the last bit or near it, as the C runtime computes it (fdlibm's `s_asinh.c`). Kotlin's own
         * works it out as `ln(x + sqrt(x² + 1))`, which near 0 is several units in the last place out — enough to
         * change the projection's easting in its last digits.
         */
        private fun asinhE(x: Double): Double {
            val ix = (x.toRawBits() ushr 32).toInt() and 0x7fffffff
            if (ix >= 0x7ff00000) return x + x                          // inf or NaN
            if (ix < 0x3e300000) return x                               // |x| < 2^-28
            val t = abs(x)
            val w = when {
                ix > 0x41b00000 -> ln(t) + LN2                          // |x| > 2^28
                ix > 0x40000000 -> ln(2.0 * t + 1.0 / (sqrt(x * x + 1.0) + t))   // 2 < |x| <= 2^28
                else -> { val tt = x * x; ln1p(t + tt / (1.0 + sqrt(1.0 + tt))) }
            }
            return if (x > 0) w else -w
        }

        /** `atanh` as fdlibm's `e_atanh.c` has it; Kotlin's own is `ln((1 + x) / (1 - x)) / 2`, as far out near 0. */
        private fun atanhE(x: Double): Double {
            val ix = (x.toRawBits() ushr 32).toInt() and 0x7fffffff
            val t0 = abs(x)
            if (t0 > 1.0 || x.isNaN()) return Double.NaN
            if (t0 == 1.0) return x / 0.0
            if (ix < 0x3e300000) return x                               // |x| < 2^-28
            val t = if (ix < 0x3fe00000) {                              // |x| < 0.5
                val tt = t0 + t0
                0.5 * ln1p(tt + tt * t0 / (1.0 - t0))
            } else {
                0.5 * ln1p((t0 + t0) / (1.0 - t0))
            }
            return if (x >= 0) t else -t
        }

        private const val LN2 = 6.93147180559945286227e-01
    }
}
