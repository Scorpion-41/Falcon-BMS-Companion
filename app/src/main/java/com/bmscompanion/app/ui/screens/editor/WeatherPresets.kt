package com.bmscompanion.app.ui.screens.editor

import com.bmscompanion.app.data.weather.CampaignTime
import com.bmscompanion.app.data.weather.WxCover
import com.bmscompanion.app.data.weather.WxGenParams
import com.bmscompanion.app.data.weather.WxLowClouds
import com.bmscompanion.app.data.weather.WxMovement
import com.bmscompanion.app.data.weather.WxOverride
import com.bmscompanion.app.data.weather.WxRange
import com.bmscompanion.app.data.weather.WxSpread
import com.bmscompanion.app.data.weather.WxType
import com.bmscompanion.app.data.weather.WxTypeParams

/**
 * Whole-theater weather a pilot would ask for by name, for the generator.
 *
 * A preset here is not one weather: it is a **set of generator parameters**, laid over WeatherGen's defaults, so what
 * comes out is still a pattern — highs and lows, fronts, a wind that follows them — only one leaning the way the
 * name says. The pilot's own seed, clock and grid are kept, so choosing a preset changes the character of the
 * weather they were looking at rather than throwing it away, and every slider is still there to tune from.
 *
 * Only the weights, ranges and overrides a preset is about are changed; everything else is WeatherGen's default.
 */
data class WxGenPreset(val name: String, val note: String, val make: (WxGenParams) -> WxGenParams)

/** WeatherGen's defaults, keeping what is the pilot's rather than the weather's: the seed, the clock and the grid. */
private fun fresh(p: WxGenParams) = WxGenParams(seed = p.seed, current = p.current, cols = p.cols, rows = p.rows)

private fun WxTypeParams.w(weight: Double) = copy(weight = weight)
private fun WxSpread.shift(d: Double) = WxSpread(min + d, mean + d, max + d)
private fun WxTypeParams.warmer(d: Double) = copy(temp = temp.shift(d))

/** On the preset's own day, at a given hour: overrides are timed in campaign time, so they follow the pilot's day. */
private fun WxGenParams.at(hour: Int) = CampaignTime(current.day, hour, 0)

/** A timed storm cell: nothing before [from], full strength from [peak] to [taper], gone at [until]. */
private fun WxGenParams.cell(x: Double, y: Double, r: Double, from: Int, peak: Int, taper: Int, until: Int, type: WxType = WxType.INCLEMENT) =
    WxOverride(
        x = x, y = y, radius = r, falloff = (r - 4).coerceAtLeast(1.0), strength = 1.0, type = type,
        towering = if (type == WxType.INCLEMENT) true else null,
        animate = true, begin = at(from), peak = at(peak), taper = at(taper), end = at(until), showOutline = true,
    )

val WX_GEN_PRESETS: List<WxGenPreset> = listOf(
    WxGenPreset("WeatherGen defaults", "Equal weights, the original's own ranges") { fresh(it) },
    WxGenPreset("Clear high pressure", "A strong high over the theater: sunny almost everywhere, light wind") { p ->
        val d = fresh(p)
        d.copy(
            sunny = d.sunny.w(100.0).copy(wind = WxSpread(0.0, 6.0, 15.0)),
            fair = d.fair.w(30.0),
            poor = d.poor.w(4.0),
            inclement = d.inclement.w(1.0),
            pressureMin = 29.9, pressureMax = 30.7, pressureVariance = 0.4,
        )
    },
    WxGenPreset("Summer fair", "Warm, fair weather cumulus, the odd poor patch") { p ->
        val d = fresh(p)
        d.copy(
            sunny = d.sunny.w(55.0).warmer(6.0),
            fair = d.fair.w(80.0).warmer(6.0),
            poor = d.poor.w(15.0).warmer(6.0),
            inclement = d.inclement.w(4.0).warmer(6.0),
        )
    },
    // Overrides are fixed to the map while the pattern drifts under them, so a band that sweeps across the theater
    // is three cells in a line, each at its worst a few hours after the one to its west.
    WxGenPreset("Frontal day", "A band of storms crossing west to east through the day") { p ->
        val d = fresh(p)
        d.copy(
            poor = d.poor.w(60.0),
            inclement = d.inclement.w(40.0),
            movement = WxMovement(headingDeg = 90.0, speedKt = 20.0, stepMin = 60.0),
            overrides = listOf(
                d.cell(14.0, 29.0, 13.0, 4, 7, 9, 12),
                d.cell(29.0, 29.0, 13.0, 7, 10, 12, 15),
                d.cell(44.0, 29.0, 13.0, 10, 13, 15, 18),
            ),
        )
    },
    WxGenPreset("Low cloud and drizzle", "Poor almost everywhere: a low, broken to overcast deck") { p ->
        val d = fresh(p)
        d.copy(
            sunny = d.sunny.w(3.0),
            fair = d.fair.w(15.0),
            poor = d.poor.w(100.0).copy(
                visibility = WxRange(3.0, 8.0),
                lowClouds = WxLowClouds(WxRange(300.0, 2500.0), WxRange(0.0, 2.0), WxCover.BROKEN, WxCover.OVERCAST, 0.2),
            ),
            inclement = d.inclement.w(20.0),
            pressureMin = 28.8, pressureMax = 30.0,
        )
    },
    WxGenPreset("Afternoon storms", "Fair morning, storm cells building after midday") { p ->
        val d = fresh(p)
        d.copy(
            sunny = d.sunny.w(40.0).warmer(5.0),
            fair = d.fair.w(70.0).warmer(5.0),
            poor = d.poor.w(20.0).warmer(5.0),
            inclement = d.inclement.w(5.0).warmer(5.0),
            overrides = listOf(
                d.cell(18.0, 20.0, 8.0, 12, 14, 17, 19),
                d.cell(38.0, 34.0, 9.0, 13, 15, 17, 20),
                d.cell(27.0, 46.0, 7.0, 12, 15, 16, 18),
            ),
        )
    },
    WxGenPreset("Winter", "Twenty degrees colder, lower stratus and contrails") { p ->
        val d = fresh(p)
        d.copy(
            sunny = d.sunny.warmer(-20.0),
            fair = d.fair.warmer(-20.0),
            poor = d.poor.warmer(-20.0),
            inclement = d.inclement.warmer(-20.0),
            clouds = d.clouds.copy(stratusFairFt = 30000, stratusInclementFt = 22000, contrailsFt = listOf(26000, 22000, 20000, 16000)),
        )
    },
    WxGenPreset("Desert haze", "Hot and clear of cloud, visibility 8 to 15 km") { p ->
        val d = fresh(p)
        d.copy(
            sunny = d.sunny.w(100.0).warmer(14.0).copy(visibility = WxRange(8.0, 15.0)),
            fair = d.fair.w(20.0).warmer(14.0).copy(visibility = WxRange(8.0, 15.0)),
            poor = d.poor.w(3.0).warmer(14.0),
            inclement = d.inclement.w(1.0).warmer(14.0),
        )
    },
)
