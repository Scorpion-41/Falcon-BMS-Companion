package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.weather.WxCover
import com.bmscompanion.app.data.weather.WxGenParams
import com.bmscompanion.app.data.weather.WxGrid
import com.bmscompanion.app.data.weather.WxModel
import com.bmscompanion.app.data.weather.WxType
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Falcon BMS weather map (`.fmap`).
 *
 * BMS keeps the weather for a theater as a grid — 59 x 59 cells in every theater in 4.38.1 — with a small header
 * and then **one array per property**, laid out field-major rather than cell-major. That is why a hex dump of
 * `SUNNY.fmap` opens with thousands of `01 00 00 00`: that is the whole weather-type array, not the first cell.
 *
 * ```
 * 0   int    version            5 (the stock and campaign maps; BMS's own saves and training maps are 8)
 * 4   int    cells across       59
 * 8   int    cells down         59
 * 12  int    map move heading   degrees: which way the whole map drifts (45 in the stock maps)
 * 16  float  map move speed     knots, as WeatherGen writes it (20.0 stock) — NOT TESTED in the sim
 * 20  int    high stratus, sunny/fair        ft
 * 24  int    high stratus, poor/inclement    ft
 * 28  int[4] contrails, sunny/fair/poor/inclement  ft
 * 44  the body: 28 arrays of [cells] four-byte values (30 in a version 8 map)
 * ```
 *
 * The header and every array are named by WeatherGen's own writer (`vmt/fmap.cljc` in Tyrant's Virtual Mission
 * Tools 0.63), which BMS has loaded for years; the stock values agree with it (stratus 35,000 / 30,000, contrails
 * 34,000 / 28,000 / 25,000 / 20,000, highest for sunny). See [Field] for the body.
 *
 * **Version 8** (`Save0-5.fmap`, the `TR_BMS_*` training maps, the copy BMS keeps beside a save as `<save>.fmap`)
 * has 30 arrays, not 28, and they are **not** all at version 5's offsets: arrays 0-26 (type, pressure, temperature,
 * the wind, cloud base, cover, size, towering) are where version 5 has them, but array 27 is a shower flag (0/1), the
 * visibility moves to array 28, and array 29 is a fog layer height (WDP's `ReadFMapDataNew`: HasShowerCumulus from
 * version 7, FogEndBelowLayerMapData after it, FogLayerZ from version 8; D57). [visibilityKm] reads it from the right
 * array. Nothing is ever written from one — every write here starts from a version 5 map.
 *
 * Sunny, Fair, Poor and Inclement `.fmap` are four **ready-made maps** in a theater's campaign folder, one weather
 * type in every cell, which BMS lists under Weather → Map Model beside any other map there. They are not BMS's
 * weather models: those are Probabilistic, Deterministic and Map Model, chosen per save and kept in its `.twx`.
 */
class Fmap private constructor(private val bytes: ByteArray, val cols: Int, val rows: Int, val fields: Int, val version: Int) {

    val cells get() = cols * rows

    /** Only the format this program writes is written; see the class notes on version 8. */
    val writable: Boolean get() = version == VERSION && fields == Field.COUNT

    /** A four-byte array in the body, one value per cell. */
    object Field {
        /** 1 sunny, 2 fair, 3 poor, 4 inclement — BMS's four weather types, counted from 1 as maps count them. */
        const val TYPE = 0
        /** millibars */
        const val PRESSURE = 1
        /** degrees Celsius */
        const val TEMPERATURE = 2
        /**
         * Where the wind starts, in whole-array units.
         *
         * **The wind is not ten arrays.** Everything else in the body is one value per cell, laid out field by
         * field; the wind is a block of **ten levels per cell, cell by cell**, and it occupies the span of ten
         * fields. Reading it as ten separate grids gives a value that cycles through the ten levels every ten
         * cells, which looks plausible and is wrong — the stock maps hide it completely, because every cell of a
         * stock map holds the same ramp. A campaign map gives it away at once: each cell has its own profile,
         * veering with altitude the way real wind does. The levels are at 0, 3,000, 6,000, 9,000, 12,000, 18,000,
         * 24,000, 30,000, 40,000 and 50,000 ft (WeatherGen's `wind-data`). Speed is km/h in the file.
         */
        const val WIND_SPEED = 3
        const val WIND_DIR = 13
        const val LEVELS = 10
        /** feet MSL */
        const val CLOUD_BASE = 23
        /**
         * Low cloud coverage as BMS's code, **not oktas**: 0 none, 1 FEW, 5 SCT, 9 BKN, 13 OVC ([WxCover.code]).
         * Korea's 741 hourly update maps hold only 1, 5 and 9; BMS's training maps add 13 on inclement cells.
         */
        const val COVER = 24
        /** cumulus size, 0 congestus to 5 humilis, as a float (3.0 in each of the four ready-made maps) */
        const val CLOUD_SIZE = 25
        /**
         * Towering cumulus, 0 or 1. Earlier versions of this program called it "shower"; WeatherGen names it
         * towering cumulus, and in the campaign maps 18,416 of its 20,228 set cells are poor weather with broken
         * cloud — towering-cumulus weather, not fair-weather showers.
         */
        const val TOWERING = 26
        /** kilometres, in a version 5 map; a version 8 map has it one array later ([visibilityKm]) */
        const val VISIBILITY = 27
        const val COUNT = 28
    }

    private fun at(field: Int, cell: Int) = HEADER + (field * cells + cell) * 4

    /** Where one cell's wind at one level sits: ten levels per cell, cell after cell. See [Field.WIND_SPEED]. */
    private fun windAt(block: Int, cell: Int, level: Int) =
        HEADER + (block * cells + cell * Field.LEVELS + level) * 4

    fun windSpeed(cell: Int, level: Int): Float = buf().getFloat(windAt(Field.WIND_SPEED, cell, level))
    fun windDir(cell: Int, level: Int): Float = buf().getFloat(windAt(Field.WIND_DIR, cell, level))

    fun setWindSpeed(cell: Int, level: Int, v: Float) { buf().putFloat(windAt(Field.WIND_SPEED, cell, level), v) }
    fun setWindDir(cell: Int, level: Int, v: Float) { buf().putFloat(windAt(Field.WIND_DIR, cell, level), v) }

    fun int(field: Int, cell: Int = 0): Int = buf().getInt(at(field, cell))
    fun float(field: Int, cell: Int = 0): Float = buf().getFloat(at(field, cell))

    fun setInt(field: Int, cell: Int, value: Int) { buf().putInt(at(field, cell), value) }
    fun setFloat(field: Int, cell: Int, value: Float) { buf().putFloat(at(field, cell), value) }

    /** The low cloud coverage of one cell. */
    fun cover(cell: Int): WxCover = WxCover.ofCode(int(Field.COVER, cell))

    /**
     * One cell's visibility in kilometres, whichever version the map is. A version 8 map (BMS's saves and training
     * maps) puts the shower flag where a version 5 map has the visibility, and the visibility one array later: WDP
     * reads it so (`ReadFMapDataNew`: HasShowerCumulus from version 7, FogEndBelowLayerMapData after it, FogLayerZ from
     * version 8), and the files agree — array 27 of `Save0.fmap` holds only 1, array 28 kilometres up to 59.98, array
     * 29 the cloud base again. Reading array 27 as the visibility gave every version 8 cell 0 km.
     */
    fun visibilityKm(cell: Int): Float = float(if (version == VERSION_8) Field.VISIBILITY + 1 else Field.VISIBILITY, cell)
    fun setCover(cell: Int, c: WxCover) = setInt(Field.COVER, cell, c.code)

    /** The same value in every cell: the shape each of the four ready-made maps has, and the shape a chosen weather has. */
    fun fillInt(field: Int, value: Int) {
        val b = buf()
        for (c in 0 until cells) b.putInt(at(field, c), value)
    }

    fun fillFloat(field: Int, value: Float) {
        val b = buf()
        for (c in 0 until cells) b.putFloat(at(field, c), value)
    }

    /** True when every cell of [field] holds the same value — which says the map is one weather, not a pattern. */
    fun uniform(field: Int): Boolean {
        val b = buf()
        val first = b.getInt(at(field, 0))
        for (c in 1 until cells) if (b.getInt(at(field, c)) != first) return false
        return true
    }

    /** Which way the whole map drifts, in degrees. */
    var moveHeading: Int
        get() = buf().getInt(H_MOVE_HEADING)
        set(v) { buf().putInt(H_MOVE_HEADING, v) }

    /** How fast the whole map drifts: knots as WeatherGen writes it. */
    var moveSpeed: Float
        get() = buf().getFloat(H_MOVE_SPEED)
        set(v) { buf().putFloat(H_MOVE_SPEED, v) }

    /** The high stratus layer: sunny/fair first, then poor/inclement. */
    var stratus: List<Int>
        get() = (0 until 2).map { buf().getInt(H_STRATUS + it * 4) }
        set(v) { val b = buf(); v.take(2).forEachIndexed { i, ft -> b.putInt(H_STRATUS + i * 4, ft) } }

    /** The altitudes contrails form at: sunny, fair, poor, inclement. */
    var contrails: List<Int>
        get() = (0 until CONTRAIL_LAYERS).map { buf().getInt(H_CONTRAIL + it * 4) }
        set(v) { val b = buf(); v.take(CONTRAIL_LAYERS).forEachIndexed { i, ft -> b.putInt(H_CONTRAIL + i * 4, ft) } }

    fun toBytes(): ByteArray = bytes.copyOf()

    private fun buf() = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    companion object {
        const val HEADER = 44
        const val VERSION = 5
        /**
         * BMS's own saves and training maps: 30 arrays, the visibility one array later than version 5 (see the class
         * notes). Read, never written.
         */
        const val VERSION_8 = 8
        private const val FIELDS_8 = 30
        /** The weather grid's side in every 4.38.1 theater. */
        const val GRID = 59

        /** Header slots, in bytes from the start. */
        const val H_MOVE_HEADING = 12
        const val H_MOVE_SPEED = 16
        const val H_STRATUS = 20
        const val H_CONTRAIL = 28
        const val CONTRAIL_LAYERS = 4

        /** BMS stores wind in km/h; the model and a pilot work in knots. (See docs/WEATHER.md on this choice.) */
        const val KMH_PER_KT = 1.852

        /**
         * Reads a map, or returns null if it is not one we understand.
         *
         * Every check here is a reason to leave a file alone rather than a thing to work around: a version we have
         * not seen, a grid that does not divide the body evenly, or a field count other than the version's own all
         * mean the format has moved, and reading it would be guessing.
         */
        fun read(file: File): Fmap? = runCatching {
            if (!file.isFile || file.length() < HEADER + 4) return null
            of(file.readBytes())
        }.getOrNull()

        fun of(bytes: ByteArray): Fmap? = runCatching {
            if (bytes.size < HEADER + 4) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = b.getInt(0)
            if (version != VERSION && version != VERSION_8) return null
            val cols = b.getInt(4)
            val rows = b.getInt(8)
            if (cols !in 1..1024 || rows !in 1..1024) return null
            val cells = cols * rows
            val body = bytes.size - HEADER
            if (body <= 0 || body % (cells * 4) != 0) return null
            val fields = body / (cells * 4)
            if (fields != (if (version == VERSION) Field.COUNT else FIELDS_8)) return null
            Fmap(bytes, cols, rows, fields, version)
        }.getOrNull()

        /**
         * An empty version 5 map of [cols] x [rows]: the template a generated map is built on in a theater whose
         * campaign folder holds no version 5 map at all (Hellas ships none). [generated] writes every array and every
         * header field, and takes only the version and the grid from its template, so a map built on this is the same
         * file a map built on `SUNNY.fmap` would be.
         */
        fun blank(cols: Int = GRID, rows: Int = GRID): Fmap {
            val bytes = ByteArray(HEADER + Field.COUNT * cols * rows * 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0, VERSION).putInt(4, cols).putInt(8, rows)
            return Fmap(bytes, cols, rows, Field.COUNT, VERSION)
        }

        /**
         * A whole generated map, as WeatherGen's `encode` builds one (`vmt/fmap.cljc:320`).
         *
         * The format is fully named for version 5, so every array and every header field is written. Only three
         * numbers come from [template] — the version and the grid's two sides — so a theater whose map were ever a
         * different size would be refused rather than assumed (every 4.38.1 theater's is 59 x 59). Returns null,
         * with nothing written, when the template is not a version 5 map or the grid does not match it.
         *
         * Two things differ from WeatherGen, both on purpose:
         * - Wind speed is written in **km/h** (knots x 1.852). WeatherGen writes its knots unchanged; BMS's four stock
         *   maps divided by 1.852 give round knots on every level, which is the evidence this program has followed
         *   since it first wrote weather. One constant, [KMH_PER_KT], flips it if a flight test says otherwise.
         * - A poor or inclement cell with scattered cloud is written as **broken**. WeatherGen's model lets those
         *   two types range from SCT; BMS's own map editor refuses to paint FEW or SCT on them (Technical Manual,
         *   "Cloud Coverage": "cells with poor and inclement type will be BKN (broken) at minimum"), and none of
         *   BMS's own training maps has one. The model's answer is kept as it is (it is checked against WeatherGen);
         *   only the byte BMS reads is lifted.
         */
        fun generated(template: Fmap, grid: WxGrid, p: WxGenParams): Fmap? {
            if (!template.writable || template.cols != grid.cols || template.rows != grid.rows) return null
            val map = Fmap(template.toBytes(), template.cols, template.rows, template.fields, template.version)
            for (c in 0 until map.cells) {
                val w = grid.cells[c]
                map.setInt(Field.TYPE, c, w.type.code)
                map.setFloat(Field.PRESSURE, c, WxModel.inHgToMb(w.pressureInHg).toFloat())
                map.setFloat(Field.TEMPERATURE, c, w.tempC.toFloat())
                for (k in 0 until Field.LEVELS) {
                    map.setWindSpeed(c, k, (w.windKt[k].coerceAtLeast(0.0) * KMH_PER_KT).toFloat())
                    map.setWindDir(c, k, w.windDeg[k].mod(360.0).toFloat())
                }
                map.setFloat(Field.CLOUD_BASE, c, w.baseFt.toFloat())
                map.setCover(c, legalCover(w.type, w.cover))
                map.setFloat(Field.CLOUD_SIZE, c, w.size.toFloat())
                map.setInt(Field.TOWERING, c, if (w.towering) 1 else 0)
                map.setFloat(Field.VISIBILITY, c, w.visKm.toFloat())
            }
            map.moveHeading = Math.round(p.movement.headingDeg).toInt().mod(360)
            map.moveSpeed = p.movement.speedKt.toFloat()
            map.stratus = listOf(p.clouds.stratusFairFt, p.clouds.stratusInclementFt)
            map.contrails = p.clouds.contrailsFt.take(CONTRAIL_LAYERS)
            return map
        }

        /** BMS's own rule: sunny is clear, and poor or inclement is broken at least. */
        fun legalCover(type: WxType, c: WxCover): WxCover = when {
            type == WxType.SUNNY -> WxCover.NONE
            (type == WxType.POOR || type == WxType.INCLEMENT) && c.value < WxCover.BROKEN.value -> WxCover.BROKEN
            else -> c
        }
    }
}
