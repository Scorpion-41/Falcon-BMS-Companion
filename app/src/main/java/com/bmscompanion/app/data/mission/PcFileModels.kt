package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable

// The BMS PC's own disks, for the Planner's file windows on any device (GET /api/files/places, /api/files/list,
// /api/files/stat, /api/files/read; POST /api/files/write; docs/PROTOCOL.md, "Files on the BMS PC"). Weapon Delivery
// Planner opens and saves its files through Windows' file dialogs (Reload WX's .fmap/.twx, the DataCard's .bdc, the
// codewords and package timing .ini, the DTC's Callsign.ini and its backup files, the pictures it saves), and those
// files live on the PC that runs Falcon BMS. On that PC's own window the Planner shows Windows' real dialog; anywhere
// else (a phone, a tablet, a browser, a PC linked to another) it shows a window listing that PC's folders
// (`PcFileWindow`), and the file is read and written by the PC. Every field has a default, so a PC a version ahead or
// behind never breaks decoding.

/**
 * One file or folder on the BMS PC.
 *
 * [path] is the full Windows path ("G:\\Falcon BMS 4.38\\User\\Config\\Viper.ini"). [size] is in bytes (0 for a
 * folder), [modified] in ms since 1970. [read] and [write] say whether the PC reads or writes a file of this type
 * through `/api/files` at all (only the types the Planner uses, never a program): a folder is neither. [exists] is
 * false only in a `stat` answer for a name that is not there yet (a Save's new file).
 */
@Serializable
data class PcFileEntry(
    val name: String = "",
    val path: String = "",
    val dir: Boolean = false,
    val size: Long = 0,
    val modified: Long = 0,
    val read: Boolean = false,
    val write: Boolean = false,
    val exists: Boolean = true,
)

/**
 * One folder's listing: its folders and the files that match the asked types, hidden and system items left out.
 *
 * [path] is the folder actually listed: the one asked for, or — when that does not exist — the nearest folder above it
 * that does, with [note] saying so. [parent] is null at a drive's root. [skipped] counts the hidden and system items
 * left out; [truncated] is true when the folder held more than the PC lists in one answer ([PcFolder.MAX]).
 */
@Serializable
data class PcFolder(
    val path: String = "",
    val parent: String? = null,
    val entries: List<PcFileEntry> = emptyList(),
    val skipped: Int = 0,
    val truncated: Boolean = false,
    val note: String? = null,
) {
    companion object {
        /** The most entries one listing answers with. */
        const val MAX = 5000
    }
}

/**
 * A place a file window offers in one tap: a drive, or a folder the Planner works in. [kind] is `drive`, `network`,
 * `removable`, `cd`, `bms`, `config`, `campaign`, `datacampaign`, `wdp`, `documents`, `desktop` or `downloads`;
 * [detail] a short second line ("Korea KTO", "Network drive").
 */
@Serializable
data class PcPlace(
    val name: String = "",
    val path: String = "",
    val kind: String = "",
    val detail: String? = null,
)

/**
 * The places a file window starts from: the Planner's folders that exist on the PC ([places]) and its drives
 * ([drives]). [readTypes] and [writeTypes] are the extensions (lower case, no dot) the PC reads and writes through
 * `/api/files`, so a window can say a type is not one before it asks.
 */
@Serializable
data class PcPlaces(
    val places: List<PcPlace> = emptyList(),
    val drives: List<PcPlace> = emptyList(),
    val readTypes: List<String> = emptyList(),
    val writeTypes: List<String> = emptyList(),
)

/** One file's content as the PC read it: [data] is the bytes in base64 (RFC 4648, with padding). */
@Serializable
data class PcFileData(
    val path: String = "",
    val name: String = "",
    val size: Long = 0,
    val modified: Long = 0,
    val data: String = "",
)

/**
 * A picture file on the BMS PC as a device can draw it (`GET /api/files/picture?path=&max=`): Upd Kneeboard's
 * **Browse picture…**, WDP's Browse Picture. The PC reads a `.jpg`, `.png`, `.bmp` or `.dds` (DXT1, DXT3, DXT5,
 * 32-bit BGRA or 24-bit RGB; its first mip level) and sends it as a PNG, scaled down to fit `max` pixels a side
 * ([PcPicture.MAX] when not asked) when it is larger, so every device decodes it the same way.
 *
 * [format] is what the file is ("JPEG", "PNG", "BMP", "DDS DXT5"), [width] x [height] its own size, [sentWidth] x
 * [sentHeight] the size of the PNG in [data] (base64). [size] and [modified] are the file's.
 */
@Serializable
data class PcPicture(
    val path: String = "",
    val name: String = "",
    val size: Long = 0,
    val modified: Long = 0,
    val format: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val sentWidth: Int = 0,
    val sentHeight: Int = 0,
    val data: String = "",
) {
    companion object {
        /** The largest side the PC sends when not asked for less (a half page is 1024 x 2048 in the file). */
        const val MAX = 2048
        /** The file types the route reads: WDP's Open picture filter, and `.jpeg`. */
        val TYPES = listOf("jpg", "jpeg", "png", "bmp", "dds")
    }
}

/** The body of `POST /api/files/write`: the new content, in base64 (RFC 4648, with padding). */
@Serializable
data class PcFileWrite(val data: String = "")

/**
 * The Planner's own folders on the BMS PC (`GET /api/files/planner`; `POST /api/files/planner?datacards=` sets the
 * DataCards folder). What Weapon Delivery Planner keeps in its program folder the Planner keeps in [planner],
 * `<BMS>\User\BMS Companion Planner`, laid out as WDP's (`Files\EWS`, `Files\PPT\Personal` …, `SavedMaps`, `DataCards`),
 * made the first time a file window needs it. [dataCards] is the DataCards folder in use — [dataCardsDefault]
 * (`<planner>\DataCards`) unless the pilot chose another ([dataCardsSet], WDP's Settings → DataCard directory).
 * [plannerExists] and [dataCardsExists] say whether the folders are there yet. [planner] is "" on a PC with no
 * Falcon BMS folder.
 */
@Serializable
data class PcPlannerFolders(
    val planner: String = "",
    val plannerExists: Boolean = false,
    val dataCards: String = "",
    val dataCardsDefault: String = "",
    val dataCardsSet: Boolean = false,
    val dataCardsExists: Boolean = false,
)

/**
 * A Falcon BMS weather file as the Planner's **Reload WX** reads it (`GET /api/files/weather?path=&clock=`): what WDP
 * builds its ATIS and its weather list from (`btnWeather_Click`).
 *
 * - An `.fmap` (a weather map) comes back as its cells, row-major from the theater's north-west corner
 *   (`index = row * cols + col`, col east, row south), one list per property: [type] 1 sunny to 4 inclement,
 *   [pressureMb], [tempC], the surface wind ([windKt] in knots — the file keeps km/h — and [windDeg] true), [cloudBaseFt],
 *   [cover] as BMS's code (0 none, 1 FEW, 5 SCT, 9 BKN, 13 OVC), [towering] 0/1 and [visKm]. [map] is the map's path.
 * - A `.twx` (a campaign's weather settings) comes back as [twx]. When its model is a map (3), the map BMS flies with
 *   it is read too — the update map in force at `clock` (campaign milliseconds) when the campaign updates its maps,
 *   else the `.fmap` of the same name beside it — and its cells fill the lists as above, [map] naming it; [note] says
 *   when there is none (the file's own tables then stand in for the map, as WDP's do).
 *
 * [modified] is the file's own time (ms since 1970), so a client can tell a file saved again from the one it read.
 * With `save=` (the name of the save beside the `.twx`, as the Planner asks for a save's own weather), [otherSave] is
 * the sentence saying the file was written with another save and is not this one's weather — a campaign and a TE saved
 * under one name (`Auto Save.cam`, `Auto Save.tac`) share one `.twx` — and null when it is the save's own.
 *
 * Every field has a default, so a PC a version ahead or behind never breaks decoding.
 */
@Serializable
data class PcWeather(
    val path: String = "",
    /** "fmap" or "twx" */
    val kind: String = "",
    /** the map the cells come from, or null when there are none */
    val map: String? = null,
    /** the map's own version (5 stock and campaign maps, 8 BMS's saves and training maps) */
    val version: Int = 0,
    val cols: Int = 0,
    val rows: Int = 0,
    val type: List<Int> = emptyList(),
    val pressureMb: List<Float> = emptyList(),
    val tempC: List<Float> = emptyList(),
    val windKt: List<Float> = emptyList(),
    val windDeg: List<Float> = emptyList(),
    val cloudBaseFt: List<Float> = emptyList(),
    val cover: List<Int> = emptyList(),
    val towering: List<Int> = emptyList(),
    val visKm: List<Float> = emptyList(),
    val twx: PcTwx? = null,
    val note: String? = null,
    /** the file's own time, ms since 1970 (0 from an older PC) */
    val modified: Long = 0,
    /** why the `.twx` is not the weather of the save named with `save=`; null when it is (or none was named) */
    val otherSave: String? = null,
)

/**
 * A `.twx` (see [PcWeather]): its [version], weather [model] (3 = a map, anything else BMS's four types with a chance
 * of each), the type the campaign is in now ([condition], 1 sunny to 4 inclement), whether the maps update through
 * the day ([mapUpdates] > 0), the wind's heading ([windDeg], true; 0 means north) and a table per type ([types],
 * sunny first). [windHeld] is false when the file holds no wind direction: BMS's heading model 1, where the sim picks
 * the direction itself (every such file on a 4.38.1 install has 0 there, while BMS's briefing prints its own). [clock] is
 * the campaign time the file was written at (campaign ms, its "last check" field: BMS's last weather check, a campaign's up to a few minutes before the save's clock),
 * null from an older PC.
 */
@Serializable
data class PcTwx(
    val version: Int = 0,
    val model: Int = 0,
    val condition: Int = 0,
    val mapUpdates: Int = 0,
    val windDeg: Int = 0,
    val types: List<PcTwxType> = emptyList(),
    val windHeld: Boolean = true,
    val clock: Long? = null,
)

/**
 * One weather type of a `.twx`. The wind, temperature and pressure are night, dawn/dusk and day, in that order: up to
 * version 7 BMS gives each its own, and version 8 (BMS 4.38) one for all three, repeated here. [fogEndFt] is the
 * visibility, [cumulusFt] the cumulus base and [stratusFt] the high stratus layer, all in feet.
 */
@Serializable
data class PcTwxType(
    val windKt: List<Int> = emptyList(),
    val fogEndFt: Float = 0f,
    val stratusFt: Int = 0,
    val cumulusFt: Int = 0,
    val tempC: List<Int> = emptyList(),
    val qnhMb: List<Int> = emptyList(),
)
