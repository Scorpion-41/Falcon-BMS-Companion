package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable

/**
 * The weather the mission flies, for the Weather tab's **Mission weather** page (`GET /api/weather/mission`, 1.4.0).
 * Read only: the PC reads the save BMS flies — in EZBoards mode the newest save of the theater, the one holding the
 * printed flight when it is that save's ([flight] then names it); in WDP mode the save Populate took — its own weather
 * file `<save>.twx`, and, under Map Model, the maps it flies.
 *
 * [model] is the `.twx`'s weather model: 1 Probabilistic, 2 Deterministic (one of BMS's four types over the whole
 * theater, the type's table in [twx]; [condition] the type now, 1 Sunny to 4 Inclement), 3 Map Model (a 59 x 59 map,
 * [frames]). [frames] are the maps of a Map Model mission in time order: the save's own `<save>.fmap` (`kind` "save")
 * and, with MAPS AUTO UPDATE on ([mapUpdates]), the theater's update maps (`kind` "update", `WeatherMapsUpdates/DHHMM.fmap`)
 * from the one in force at the save's clock to a few hours past the flight's landing; [start] is the frame in force at
 * the save's [clock]. Each frame's cells come from `GET /api/weather/mission/map?frame=<id>` ([MissionWxMap]).
 * [places] are the flight's take-off, target and landing with their campaign times. Times are campaign ms (since 00:00
 * on day 1); positions theater feet, x north, y east.
 *
 * [ok] false: [why] says, in a sentence, why there is nothing to show (no save, no weather file, another save's).
 */
@Serializable
data class MissionWeather(
    val ok: Boolean = false,
    val why: String? = null,
    /** "ezboards" or "wdp": whose mission it is */
    val mode: String = "",
    /** the save's theater as BMS names it, and the app's id for it (null when the app does not know it) */
    val theater: String = "",
    val appTheater: String? = null,
    /** the save's file name ("Save-Day  1 04 11 35.cam") */
    val save: String = "",
    /** the flight's callsign ("Jaguar 2"), null when the save does not provably hold the briefed flight */
    val flight: String? = null,
    val twxFile: String? = null,
    val twxModified: Long = 0,
    val model: Int = 0,
    val condition: Int = 0,
    val clock: Long? = null,
    val mapUpdates: Boolean = false,
    val twx: PcTwx? = null,
    val frames: List<MissionWxFrame> = emptyList(),
    val start: Int = 0,
    val places: List<MissionWxPlace> = emptyList(),
    /** what the page should say beside the weather, each a sentence */
    val notes: List<String> = emptyList(),
    /** 1.4.1: the theater's sky as BMS sets it up (`weather.ini`), for the page's Sun & Moon; null when not read */
    val sky: BmsSky? = null,
) {
    companion object {
        const val PROBABILISTIC = 1
        const val DETERMINISTIC = 2
        const val MAP_MODEL = 3
        val TYPE_NAMES = listOf("Sunny", "Fair", "Poor", "Inclement")
    }
}

/**
 * One map a Map Model mission flies: [id] is what `/api/weather/mission/map?frame=` takes, [file] its file name, [minute]
 * the campaign minute it takes over (an update map's name, the save's clock for the save's own map), [kind] "save" or
 * "update".
 */
@Serializable
data class MissionWxFrame(
    val id: String = "",
    val file: String = "",
    val minute: Long? = null,
    val kind: String = "save",
)

/** The flight's take-off, target or landing ([label] "Take Off", "Target Area", "Landing"), at campaign time [ms]. */
@Serializable
data class MissionWxPlace(
    val label: String = "",
    val name: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val ms: Long = 0,
)

/**
 * One weather map's cells, row-major from the north-west corner (`index = row * cols + col`), in the units a pilot
 * reads: millibars, °C, feet MSL, km, and the wind in knots (the file's km/h ÷ 1.852) and degrees **from**, ten levels
 * per cell (`cell * 10 + level`, levels at 0, 3,000 … 50,000 ft). [cover] is BMS's code (0 none, 1 FEW, 5 SCT, 9 BKN,
 * 13 OVC); [towering] and [shower] are 0/1 ([shower] only in a version 8 map, empty otherwise). [moveHeading] and
 * [moveKt] are the header's drift (the direction the pattern moves toward, as WeatherGen writes it; not tested in the
 * sim), [stratusFt] the high stratus (sunny/fair, poor/inclement), [contrailFt] the contrail layers by type.
 */
@Serializable
data class MissionWxMap(
    val id: String = "",
    val file: String = "",
    val version: Int = 0,
    val cols: Int = 0,
    val rows: Int = 0,
    val moveHeading: Int = 0,
    val moveKt: Float = 0f,
    val stratusFt: List<Int> = emptyList(),
    val contrailFt: List<Int> = emptyList(),
    val type: List<Int> = emptyList(),
    val pressureMb: List<Float> = emptyList(),
    val tempC: List<Float> = emptyList(),
    val cloudBaseFt: List<Int> = emptyList(),
    val cover: List<Int> = emptyList(),
    val towering: List<Int> = emptyList(),
    val shower: List<Int> = emptyList(),
    val visKm: List<Float> = emptyList(),
    val windKt: List<Int> = emptyList(),
    val windDeg: List<Int> = emptyList(),
) {
    val cells: Int get() = cols * rows
    /** every list the size the grid says (an answer that is not is not drawn) */
    val whole: Boolean
        get() = cols > 0 && rows > 0 && listOf(type.size, pressureMb.size, tempC.size, cloudBaseFt.size, cover.size, visKm.size).all { it == cells } &&
            windKt.size == cells * LEVELS && windDeg.size == cells * LEVELS

    companion object {
        const val LEVELS = 10
    }
}

/**
 * A theater's `<terraindir>\Weather\weather.ini` (1.4.1): the zone BMS prints local times in ([timeZone] hours east of
 * zulu) and the one point, in whole degrees, its briefing's sunrise and sunset are worked out for ([lat], [lon], east
 * positive). Korea: 9, 37, 126.
 */
@Serializable
data class BmsSky(val timeZone: Int = 0, val lat: Int = 0, val lon: Int = 0)

/** One change of the Deterministic model's schedule: at campaign time [ms] the theater turns [type] (1 Sunny to 4 Inclement). */
@Serializable
data class PcTwxChange(val ms: Long = 0, val type: Int = 0)
