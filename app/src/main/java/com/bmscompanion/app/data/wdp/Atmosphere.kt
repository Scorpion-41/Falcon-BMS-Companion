package com.bmscompanion.app.data.wdp

/**
 * The atmosphere conversions the Performance page works in, from Falcas's `clsMeteo`.
 *
 * Pressure altitude, the two pressure units, the two temperature scales and the deviation from a standard day —
 * every performance figure leans on these, so they come first.
 *
 * **One of WDP's is fixed here** (the Planner is WDP for Falcon BMS 4.38.1, and fixes the program's slips):
 * **pressure altitude** — WDP's `PressAlt` takes the *absolute* difference from 29.92 and allows **100 ft** per inch,
 * so any setting away from standard, high or low, lowered it, by a tenth of the real amount. A pressure altitude is
 * higher than the field on a low-pressure day and lower on a high one, by about **1,000 ft per inch** (the standard
 * approximation; BMS's own atmosphere takes 1 hPa per 30 ft, User Manual 4.38.1 §4.1, which is 1,016 ft per inch —
 * within 1.6 %). At Osan (42 ft) 30.42 in made WDP's −8 ft where it is −458 ft, and 29.42 in also −8 ft where it is
 * +542 ft: a hot, low-pressure day was planned as a sea-level one, and the take-off factor *fell* as the day got
 * worse, where the same page of BMS's manual says "low pressure / high temperature will decrease performance" (D28).
 *
 * **The standard lapse is WDP's own, 2 °C per 1,000 ft, because it is Falcon BMS's**: "Atmosphere in BMS follows the
 * ISA standards for the most part: 15°C on ground 1013.2 hPa and a dry adiabatic lapse rate of -2°C per 1000 feet"
 * (User Manual 4.38.1 §4.1, p.49). A day's deviation from standard is measured against the atmosphere the sim flies
 * in. The port once used the ICAO 1.98 on this page (D32, withdrawn): it moved the ISA deviation by a degree on some
 * days and the turn calculator's standard temperature at 26,000 ft and above, away from both WDP and BMS.
 *
 * WDP's own pressure altitude is kept beside the fixed one ([wdpPressureAltitudeFt]) for one reason only: the
 * comparison check (`--wdppagetest performance`) runs the page once as the program answered, to show that the fixes
 * are the only differences from it. Nothing the pilot sees uses it.
 *
 * **Two of the originals could never be called.** In `clsMeteo`, `Inch` returns `HpaToInch(Hpa)` while `Hpa`
 * returns `InchToHpa(Inch)`: each property is defined in terms of the other, so reading either recurses until the
 * stack gives out. The program never reads them, which is why nobody has met it. Here they are plain functions
 * that take what they convert, which is what the arithmetic inside them always meant.
 */
object Atmosphere {

    /** The standard altimeter setting, inches of mercury. */
    const val STD_INHG = 29.92

    /** Falcon BMS's standard lapse, °C per 1,000 ft (User Manual 4.38.1 §4.1) — WDP's `GetISADev` uses the same. */
    const val LAPSE_C_PER_1000FT = 2.0

    /**
     * Pressure altitude, from an elevation and the altimeter setting in inches of mercury: 1,000 ft per inch
     * below 29.92 added, above it taken away. Osan (42 ft): 29.92 → 42, 30.42 → −458, 29.42 → 542.
     */
    fun pressureAltitudeFt(altitudeFt: Int, settingInHg: Double): Int =
        altitudeFt + ((STD_INHG - settingInHg) * 1000.0).roundToIntBankers()

    /** The standard day's temperature at [altitudeFt], °C. */
    fun isaTempC(altitudeFt: Int): Double = 15.0 - altitudeFt / 1000.0 * LAPSE_C_PER_1000FT

    /** How far off a standard day [tempC] is at [elevationFt], in whole degrees: WDP's `GetISADev`, digit for digit. */
    fun isaDeviation(elevationFt: Int, tempC: Double): Int = (tempC - isaTempC(elevationFt)).roundToIntBankers()

    /** WDP's `PressAlt`, slip included (see the class notes): for the comparison check only. */
    fun wdpPressureAltitudeFt(altitudeFt: Int, settingInHg: Double): Int {
        val correction =
            if (settingInHg > 29.92) ((settingInHg - 29.92) * 100.0).roundToIntBankers()
            else ((29.92 - settingInHg) * 100.0).roundToIntBankers()
        return altitudeFt - correction
    }

    /** Hectopascals to inches of mercury, clamped to the range an altimeter can be set to. */
    fun hpaToInches(hpa: Int): Double = (0.02953 * hpa).coerceIn(27.0, 31.0)

    /** Inches of mercury to hectopascals, clamped the same way. */
    fun inchesToHpa(inches: Double): Double = (33.8639 * inches).coerceIn(914.0, 1050.0)

    fun celsiusToFahrenheit(c: Double): Double = c * 1.8 + 32.0

    fun fahrenheitToCelsius(f: Double): Double = (f - 32.0) * (5.0 / 9.0)

    /** .NET rounds a tie to the even digit; Kotlin's `roundToInt` rounds it up. The figures are WDP's, so .NET's. */
    private fun Double.roundToIntBankers(): Int {
        val floor = kotlin.math.floor(this)
        val diff = this - floor
        val v = when {
            diff > 0.5 -> floor + 1.0
            diff < 0.5 -> floor
            floor.toLong() % 2L == 0L -> floor
            else -> floor + 1.0
        }
        return v.toInt()
    }
}
