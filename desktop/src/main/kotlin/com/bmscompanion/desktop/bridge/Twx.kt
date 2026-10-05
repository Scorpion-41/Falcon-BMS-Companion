package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.PcTwx
import com.bmscompanion.app.data.mission.PcTwxType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A campaign's, TE's or training mission's weather settings file (`<save>.twx`), read for the Planner: the save's own
 * file whenever a flight of it is planned (Open mission…, Pick a flight), and any file Reload WX picks. Read only:
 * nothing here writes.
 *
 * A `.twx` is the weather page of BMS's campaign screen: which model the campaign flies (1 probabilistic, 2
 * deterministic — BMS's four weather types, Sunny, Fair, Poor and Inclement — or 3, a weather map), the type it is in
 * now, whether the maps update through the day, the deterministic model's schedule of type changes, and a table per
 * type — wind, gusts, turbulence, visibility, the cloud layers, temperature and pressure. BMS rewrites it with every
 * campaign save (in the same millisecond as the `.cam`), and a TE's with SAVE WTH. Weapon Delivery Planner reads it in
 * `fclsMain.ReadWeather` and builds its ATIS from it when the model is not a map (`CreateAtis`).
 *
 * **Two layouts.** Up to version 7 the file is what WDP 3.7.24 reads: the model at byte 216 (version below 4), 228
 * (4 to 6) or 236 (7), then the type-change schedule (five time and type pairs), and every speed, temperature and
 * pressure three times, for night, dawn/dusk and day. **Falcon BMS 4.38 writes version 8**, which WDP does not know:
 * it reads it at version 7's offsets, which land in the middle of the probabilities (where the model should be it
 * finds a probability, stored as 0.25 or as 25.0), and runs past the end of the 728-byte file, so its read gives up
 * and WDP has no weather for a 4.38 save. The version 8 layout below is read from the files 4.38.1 writes
 * (`Auto Save.twx` and the other saves, 728 bytes each), laid against WDP's field order — every field falls where its
 * value makes sense, the four temperatures and four pressures end the file exactly:
 *
 * ```
 *   0 version 8, year, month, day, last check (the campaign time the file was written at, ms: the save's clock),
 *     old condition, condition (the type now, 0-3), counter, current, interval
 * 212 model (3 = a map), map updates, min interval, max interval, P(sunny), P(fair), P(poor), P(inclement)
 * 244 the deterministic model's type changes: six pairs of (campaign time, type counted from 0)
 * 292 wind heading model (2 = the heading that follows, 1 = none: the sim picks it), wind heading
 * 300 per type: max wind (kt), gust interval, gust duration, gust speed, gust direction      (one speed, not three)
 * 380 seven turbulence entries of seven ints
 * 576 mechanical layer, heat layer, occurrence, duration
 * 592 fog start x4, fog end x4 (the visibility, ft), stratus x4, cumulus base x4, contrails x4, fog layer x4 (ft)
 * 688 cumulus density, cumulus size, temperature x4 (°C), pressure x4 (mb)                  (one each, not three)
 * ```
 *
 * Anything that does not read sensibly (a model outside 1..3, a type outside 0..4, a pressure outside 850..1100) is
 * refused in a sentence rather than guessed.
 *
 * **Version 8 counts the weather type from 0** (0 sunny … 3 inclement), where WDP's rule for the older files takes it
 * as 1-4 and reads both 0 and 1 as sunny. Evidence: a Korea save with 1 there, whose briefing BMS printed from it says
 * Fair, 080° at 15 kt, 115 km, 23 °C — table 1 exactly (table 0 is 5 kt, 29 °C); TR_BMS_01's file has 0 and table 0
 * is the Training Manual's 320/5KT, 9 °C, Q1010; and across the 347 version 8 files of a 4.38.1 install the value is
 * 0 to 3, never 4. It is handed on as WDP's 1-4. A version 8 file with heading model 1 holds no direction
 * ([PcTwx.windHeld] false): all 275 such files have 0 there, and BMS briefs a direction of its own.
 */
object Twx {
    /** The file read, or the sentence saying why it was not. */
    sealed interface Read {
        class Ok(val twx: PcTwx) : Read
        class Bad(val why: String) : Read
    }

    fun read(bytes: ByteArray): Read = try {
        if (bytes.size < 48) Read.Bad("It is ${bytes.size} bytes: too short for a weather settings file.")
        else {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = b.getInt(0)
            val t = if (version >= 8) v8(b, version) else old(b, version)
            when {
                t == null -> Read.Bad("It is a version $version weather file, and too short for one (${bytes.size} bytes).")
                t.model !in 1..3 -> Read.Bad("Its weather model reads as ${t.model}: this is not a layout the Planner knows (version $version).")
                // handed on as WDP's 1 (sunny) to 4 (inclement): version 8's own 0-3 plus one; an older file's 1-4 as it
                // stands, where 0 also occurs and WDP's CurrentWthType takes it as sunny
                t.condition !in 0..4 -> Read.Bad("Its current weather type reads as ${t.condition}: this is not a layout the Planner knows (version $version).")
                t.types.any { ty -> ty.qnhMb.any { it !in 850..1100 } } ->
                    Read.Bad("Its pressures read as ${t.types.flatMap { it.qnhMb }.distinct()}: this is not a layout the Planner knows (version $version).")
                else -> Read.Ok(t)
            }
        }
    } catch (e: Throwable) {
        Read.Bad("It could not be read: ${e.message ?: e::class.java.simpleName}.")
    }

    /** Falcon BMS 4.38's version 8 (see the class notes). */
    private fun v8(b: ByteBuffer, version: Int): PcTwx? {
        if (b.capacity() < 728) return null
        val condition = b.getInt(24) + 1
        val clock = b.getInt(16).toLong() and 0xFFFFFFFFL
        val model = b.getInt(212)
        val mapUpdates = b.getInt(216)
        val headingModel = b.getInt(292)
        val heading = b.getInt(296)
        val types = (0 until 4).map { k ->
            val wind = b.getInt(300 + k * 20)
            PcTwxType(
                windKt = List(3) { wind },
                fogEndFt = b.getFloat(608 + k * 4),
                stratusFt = b.getInt(624 + k * 4),
                cumulusFt = b.getInt(640 + k * 4),
                tempC = List(3) { b.getInt(696 + k * 4) },
                qnhMb = List(3) { b.getInt(712 + k * 4) },
            )
        }
        return PcTwx(version, model, condition, mapUpdates, heading, types, windHeld = !(headingModel == 1 && heading == 0), clock = clock)
    }

    /** Versions up to 7, as WDP 3.7.24 reads them for BMS 4.35 and later (`fclsMain.ReadWeather`). */
    private fun old(b: ByteBuffer, version: Int): PcTwx? {
        val size = b.capacity()
        var at = 4
        if (version >= 4) at += 12                    // year, month, day
        val clock = b.getInt(at).toLong() and 0xFFFFFFFFL
        at += 4                                       // last check: the campaign time the file was written at
        val condition = b.getInt(at)
        at = when {
            version < 4 -> 216
            version >= 7 -> 236
            else -> 228
        }
        fun int(): Int { val v = b.getInt(at); at += 4; return v }
        fun float(): Float { val v = b.getFloat(at); at += 4; return v }
        if (size < at + 400) return null
        val model = int()
        val mapUpdates = int()
        at += 8                                       // min and max interval
        at += 16                                      // the four probabilities
        at += 5 * 8                                   // MAXUSERSHIFT - 1 shift pairs
        int()                                         // wind heading model
        val heading = int()
        val wind = (0 until 4).map { val night = int(); val dawn = int(); val day = int(); at += 16; listOf(night, dawn, day) }
        at += 6 * (if (version >= 5) 28 else 24)     // turbulence
        at += 16                                      // mechanical, heat, occurrence, duration
        at += 16                                      // fog start
        val fogEnd = List(4) { float() }
        val stratus = List(4) { int() }
        val cumulus = List(4) { int() }
        at += 16                                      // contrails
        if (version >= 7) at += 16                    // fog layer
        at += 8                                       // cumulus density and size
        if (size < at + 96) return null
        val temps = (0 until 4).map { listOf(int(), int(), int()) }
        val qnh = (0 until 4).map { listOf(int(), int(), int()) }
        val types = (0 until 4).map { k -> PcTwxType(wind[k], fogEnd[k], stratus[k], cumulus[k], temps[k], qnh[k]) }
        return PcTwx(version, model, condition, mapUpdates, heading, types, clock = clock)
    }
}
