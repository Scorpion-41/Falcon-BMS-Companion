package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable

// Mirrors pc/BmsCompanionBridge/Server/ApiModels.cs, Bms/BriefingParser.cs, Bms/DtcParser.cs and EzBoards/EzBoardsRunner.cs.
// Everything has defaults so older/newer bridges never break decoding. Protocol: docs/PROTOCOL.md.

@Serializable
data class BridgeInfo(
    val app: String = "",
    val version: String = "",
    val api: Int = 1,
    val host: String = "",
    val bms: BmsStatus = BmsStatus(),
    val tacview: TacviewStatus = TacviewStatus(),
    val briefing: BriefingStatus = BriefingStatus(),
    val ezBoards: EzStatus = EzStatus(),
    val media: MediaInfo = MediaInfo(),
    val acmi: AcmiInfo = AcmiInfo(),
    val kneeboard: KneeboardInfo = KneeboardInfo(),
    /** where the Mission section's data comes from: EZBoards mode or WDP mode, and WDP mode's snapshot (1.3.8) */
    val mission: MissionSourceInfo = MissionSourceInfo(),
    /** BMS's debug log, read for the Radio page and the Taxi page's automation (1.3.8); [RadioLogStatus.OFF] without one */
    val radio: RadioLogStatus = RadioLogStatus(),
)

/**
 * The two ways the Mission section is filled, and the words the pages use for them. The mode lives on the PC
 * (`BridgeInfo.mission`, `POST /api/mission/source?mode=`), so every device shows the same one; docs/PROTOCOL.md,
 * "Mission source".
 *
 * - [EZBOARDS] (the default): Falcon BMS's printed briefing and the pilot's cartridge, read again whenever BMS prints
 *   or saves them — the app as it always was. The Planner is not used in this mode.
 * - [WDP]: the Planner. Nothing comes in by itself: **Populate from Planner** (`POST /api/mission/populate`) takes the
 *   flight the Planner has open in a save, the cartridge as saved and the Planner's attack, and the PC keeps that
 *   snapshot until the next Populate. EZBoards' own generation is suspended meanwhile (the cockpit kneeboards come from
 *   the Planner's Upd Kneeboard), without changing its setting.
 *
 * Switching asks nothing. It resets what the other mode left from an **earlier flight** than the current one (the
 * Planner's leftovers in the cartridge, with an Undo; the other mode's cockpit pages; an earlier flight's snapshot) and
 * never the current flight's ([SwitchReset]); the snapshot of the same flight is still there on return.
 */
object MissionMode {
    const val EZBOARDS = "ezboards"
    const val WDP = "wdp"

    /** [EZBOARDS] or [WDP] for a word a request or a settings file carries ("EZBoards", "wdp", "planner"); null for anything else. */
    fun of(s: String?): String? = when (s?.trim()?.lowercase()) {
        "ezboards", "ez", "bms", "briefing", "print" -> EZBOARDS
        "wdp", "planner" -> WDP
        else -> null
    }

    /** the two positions of the switch */
    const val EZBOARDS_LABEL = "EZBoards"
    const val WDP_LABEL = "WDP"
    /** the mode's name in a sentence */
    const val EZBOARDS_NAME = "EZBoards mode"
    const val WDP_NAME = "WDP mode"
    /** what each mode fills the Mission section from, in one line */
    const val EZBOARDS_SAYS = "BMS briefing — filled when you press PRINT in BMS"
    const val WDP_SAYS = "Planner — filled from a save file and your cartridge"
    /** what WDP mode changes, for the card a tap on the greyed Planner shows in EZBoards mode */
    const val WDP_CHANGES = "In WDP mode the Mission section is filled from the flight you open in the Planner and your saved cartridge, when you press Populate from Planner; EZBoards' own kneeboards are paused."
    /**
     * Why GENERATE NOW is greyed out (and EZBoards on PRINT suspended) in WDP mode, as the greyed button says it and as
     * the PC refuses a press (`POST /api/ezboards/generate`, 409): EZBoards writes cockpit page 1, the Planner's page.
     */
    const val EZ_SUSPENDED = "WDP mode: EZBoards is paused so it cannot overwrite the Planner's pages. Make the cockpit boards with Planner → Upd Kneeboard."
    /**
     * Why Run HTML Briefing is greyed out in WDP mode, as the PC refuses it (`POST /api/kneeboard/open`, 409): its
     * export writes cockpit pages 1-3 over the Planner's. Reading an export it already made stays allowed.
     */
    const val HTML_BRIEF_SUSPENDED = "WDP mode: HTML Briefing is not started from here, because its export writes cockpit pages 1-3 over the Planner's. Make the cockpit boards with Planner → Upd Kneeboard."
    /** the button beside a greyed EZBoards or HTML Briefing button in WDP mode */
    const val OPEN_PLANNER = "Open the Planner"
    /** WDP mode before the first Populate: the one empty state every Mission view shows */
    const val NOT_POPULATED = "Not populated yet — open your flight in the Planner and press Populate from Planner"
    /** the button, wherever it is */
    const val POPULATE = "Populate from Planner"
    /**
     * The hint once shown when the save or the cartridge changed on disk after the snapshot was taken. No longer shown
     * anywhere (the pilot asked not to be told); `Populated.changed` is still served for older clients.
     */
    const val CHANGED_SINCE = "changed since — Populate again"
    /** how the PC's note begins when a Populate brought no weather (`Populated.notes`): the reason follows */
    const val NO_WEATHER = "No weather came with it: "
}

/**
 * Which mode the Mission section is in ([mode], [MissionMode]), since when ([switched], ms; 0 = never switched on this
 * PC), WDP mode's snapshot as it was taken ([populated]; null until the first Populate from Planner, and kept across a
 * switch to EZBoards mode and a restart of the PC program), and [line]: the one line under the switch, worded on the PC
 * ("From BMS briefing · printed 22:40", "From the Planner · populated 22:51 · Auto Save.cam · Cyborg6", "From the
 * Planner · not populated yet").
 */
@Serializable
data class MissionSourceInfo(
    val mode: String = MissionMode.EZBOARDS,
    val switched: Long = 0,
    val populated: Populated? = null,
    val line: String? = null,
    /** what the last switch reset by itself (1.3.8), served for a while after it so every device shows it; null = none */
    val reset: SwitchReset? = null,
) {
    val wdp: Boolean get() = mode == MissionMode.WDP
    val ezBoards: Boolean get() = mode != MissionMode.WDP
}

/**
 * What WDP mode's snapshot was made of (`MissionSourceInfo.populated`, `MissionData.populated`). [at] is when (ms), [from]
 * the kind of device that pressed the button ("PC", "Android", "Browser" — never a name). The flight: [theater] (the
 * theater definition's name, "Korea KTO"), [save] ("Auto Save.cam"), [flight] (its "num/creator" id), [callsign],
 * [seat] (0-3), [packageId] and [kind] (`CampKind`). The files as they were then: the save's time [saveModified], the
 * cartridge [cartridge] ("Viper.ini", null when the pilot had none saved) and its time, and the mission file beside the
 * save [missionFile] and its time. [attack] says an attack came with it. [notes] are sentences about what was left
 * out. The save's own weather file [weatherFile] ("Auto Save.twx", null when none was read) and its time
 * [weatherFileModified] (added in 1.3.8, after the first WDP mode builds): the snapshot's briefing carries its weather
 * (`Briefing.weather`). [changed] names what changed on disk since ("save", "cartridge", "mission file", "weather file",
 * and "printed briefing" when BMS printed one after the Populate: the next mission being set up) — served for older clients, no longer shown; nothing is re-read until the pilot presses Populate again.
 * [briefingFrom] says whose briefing the snapshot carries: "printed" — BMS's printed `briefing.txt`, because it is the
 * populated flight's, exactly as EZBoards mode shows it, printed at [briefingPrinted] (its file time) — or "save", the
 * briefing the PC words out of the save when no printed briefing is that flight's (both added later in 1.3.8; null in
 * an older snapshot, which is always the save's).
 */
@Serializable
data class Populated(
    val at: Long = 0,
    val from: String? = null,
    val theater: String = "",
    val save: String = "",
    val flight: String = "",
    val callsign: String? = null,
    val seat: Int = 0,
    val packageId: String? = null,
    val kind: String? = null,
    val saveModified: Long = 0,
    val cartridge: String? = null,
    val cartridgeModified: Long = 0,
    val missionFile: String? = null,
    val missionFileModified: Long = 0,
    val attack: Boolean = false,
    val notes: List<String> = emptyList(),
    val changed: List<String> = emptyList(),
    val weatherFile: String? = null,
    val weatherFileModified: Long = 0,
    val briefingFrom: String? = null,
    val briefingPrinted: Long = 0,
) {
    /** the save, the cartridge, the mission file or the weather file changed on disk after the snapshot: Populate again */
    val stale: Boolean get() = changed.isNotEmpty()
    /** the flight as [CampRef], for a page that opens it in the Planner again */
    val ref: CampRef get() = CampRef(theater = theater, file = save, flight = flight)
}

/**
 * The body of `POST /api/mission/populate`: the flight the Planner has open ([ref], [seat] 0-3), the attack its attack
 * page worked out (null: the PC lays one out from the cartridge's `[NAV OFFSETS]`), which kind of device sends it, and
 * whose cartridge ([callsign]; null = the pilot Falcon BMS has selected). The cartridge is always the one **saved** on
 * the PC: a page holding unsaved edits asks the pilot first (save them, leave them out, or cancel).
 */
@Serializable
data class PopulateSend(
    val ref: CampRef? = null,
    val seat: Int? = null,
    val attack: AttackOverlay? = null,
    val from: String? = null,
    val callsign: String? = null,
)

/**
 * The kneeboard UOAF's html_brief last exported on the BMS PC: [pages] of it, ready to read through
 * /api/kneeboard/page. [stale] means BMS has printed a newer briefing than the export, so the pages are the
 * previous flight's until the pilot exports again.
 */
/**
 * Weapon Delivery Planner as the app sees it: the real tool, running on the BMS PC, drawn on this device.
 *
 * WDP is a Windows program, so it can only ever run on the PC. What travels is its window — a picture out,
 * taps and keys back — which is why a tablet and a browser can both work it. [hidden] says the window has been
 * parked off the PC's screen so only this page shows it.
 */
@Serializable
data class WdpState(
    val available: Boolean = false,
    val path: String? = null,
    /** the folder was set in Settings rather than guessed */
    val configured: Boolean = false,
    val running: Boolean = false,
    val hidden: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val message: String? = null,
)

@Serializable
data class KneeboardInfo(
    val configured: Boolean = false,
    val path: String? = null,
    val detected: String? = null,
    val available: Boolean = false,
    val pages: Int = 0,
    val exported: Long = 0,
    val stale: Boolean = false,
    val message: String? = null,
    /**
     * WDP mode (1.3.8): Run HTML Briefing (`POST /api/kneeboard/open`) is refused, because its export writes cockpit
     * pages 1-3 over the Planner's ([MissionMode.HTML_BRIEF_SUSPENDED]); the pages it already exported are still served
     */
    val runSuspended: Boolean = false,
)

/**
 * The ACMI recordings BMS leaves in User\Acmi. Flying with the AWACS picture on means recording, and the folder
 * grows by a file per flight until somebody clears it — which is what [bytes] is there to make obvious.
 */
@Serializable
data class AcmiInfo(
    val available: Boolean = false,
    val path: String? = null,
    val count: Int = 0,
    val bytes: Long = 0,
    val latest: Long = 0,
)

/** Screenshots on the BMS PC (User\Pictures): summary in /api/info, list from /api/media. */
@Serializable data class MediaInfo(val available: Boolean = false, val count: Int = 0, val latest: Long = 0)
@Serializable data class MediaList(val dir: String? = null, val available: Boolean = false, val shots: List<Shot> = emptyList())
@Serializable data class Shot(val name: String = "", val time: Long = 0, val size: Long = 0, val w: Int? = null, val h: Int? = null)

@Serializable
data class BmsStatus(
    val installed: Boolean = false,
    val baseDir: String? = null,
    val registryVersion: String? = null,
    val version: String? = null,
    val running: Boolean = false,
    val flying: Boolean = false,
    val theater: String? = null,
    val callsign: String? = null,
    val aircraft: String? = null,
)

@Serializable
data class TacviewStatus(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val state: String = "off",
    val objects: Int = 0,
    /** whether `/api/contacts` carries hostile contacts (`BridgeSettings.ShowHostiles`, off by default; [HostileContacts]) */
    val hostiles: Boolean = false,
)

@Serializable
data class BriefingStatus(
    val available: Boolean = false,
    val modified: Long = 0,
    val generated: String? = null,
    val dtcModified: Long = 0,
    /** changes whenever the sent plan (`MissionData.plan`) does: sent, cleared, undone, parked; 0 = never */
    val planModified: Long = 0,
    /** changes whenever BMS's mission file route (`MissionData.route`) does; 0 = none */
    val routeModified: Long = 0,
)

@Serializable
data class EzStatus(
    val configured: Boolean = false,
    val path: String? = null,
    val autoOnPrint: Boolean = false,
    val running: Boolean = false,
    val lastRun: EzRun? = null,
    /**
     * WDP mode: GENERATE NOW is refused and [autoOnPrint] is not acted on ([MissionMode.EZ_SUSPENDED]); the setting itself
     * is kept as the pilot left it and applies again in EZBoards mode (1.3.8)
     */
    val suspended: Boolean = false,
)

@Serializable
data class EzRun(val time: Long = 0, val ok: Boolean = false, val durationMs: Long = 0, val message: String = "", val log: List<String> = emptyList(), val auto: Boolean = false)

@Serializable
data class Live(
    val t: Long = 0,
    val flying: Boolean = false,
    val theater: String? = null,
    val aircraft: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val altFt: Double = 0.0,
    val hdgTrue: Double = 0.0,
    val hdgMag: Double = 0.0,
    val kias: Double = 0.0,
    val mach: Double = 0.0,
    val gsKts: Double = 0.0,
    val vviFpm: Double = 0.0,
    val gLoad: Double = 0.0,
    val aoa: Double = 0.0,
    val radarAltFt: Double = 0.0,
    val fuelInternal: Double = 0.0,
    val fuelExternal: Double = 0.0,
    val fuelFlow: Double = 0.0,
    val bingo: Double = 0.0,
    val chaff: Int = 0,
    val flares: Int = 0,
    val gear: Double = 0.0,
    val speedBrake: Double = 0.0,
    val bullX: Double? = null,
    val bullY: Double? = null,
    val timeSec: Int = 0,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val tacan: String? = null,
    /** UFC (DED) and AUX COMM panel channels; [tacan] is the one the bridge picked (A/A or Y band first). */
    val tacanUfc: String? = null,
    val tacanAux: String? = null,
    val beaconBrg: Double? = null,
    val beaconNm: Double? = null,
    val desiredCourse: Double = 0.0,
    val navMode: Int = 0,
    val ilsFreq: Int = 0,
    val uhfPreset: Int = 0,
    val uhfFreq: Int = 0,
    val ded: List<String> = emptyList(),
    val rwr: List<RwrContact> = emptyList(),
    /** The left MFD's 20 button legends, OSB 1 first (see [MfdKey]). Empty when BMS is not publishing them. */
    val mfdLeft: List<MfdKey> = emptyList(),
    /** The right MFD's 20 button legends. */
    val mfdRight: List<MfdKey> = emptyList(),
    val navPoints: List<NavPoint> = emptyList(),
    val voice: Voice? = null,
    val pilots: List<Pilot> = emptyList(),
) {
    val fuelTotal get() = fuelInternal + fuelExternal
}

// ---------------------------------------------------------------- Falcon BMS's own weather

/**
 * The weather pages' whole world: which theaters have weather this program can read, and what it has changed.
 *
 * [changed] is the honest answer to "what has this app done to my install": it lists every theater with a ready-made
 * map this app wrote and that is not the file BMS shipped, and it is read from the BMS folder itself (the backup's
 * checksums and the record kept beside them) rather than from anything the app keeps — so a reinstalled app, a new PC
 * or a version two years from now finds the same list and can put it back.
 */
@Serializable
data class WeatherState(
    val available: Boolean = false,
    val theaters: List<WxTheater> = emptyList(),
    /** the theater Falcon BMS is running, when it is running */
    val current: String? = null,
    val error: String? = null,
) {
    val changed: List<WxTheater> get() = theaters.filter { t -> t.models.any { it.edited } }
}

@Serializable
data class WxTheater(
    val id: String = "",
    val name: String = "",
    val dir: String = "",
    /** whether the untouched originals have been put safely aside; nothing may be written until they are */
    val backedUp: Boolean = false,
    val models: List<WxModel> = emptyList(),
    /**
     * Files this program added to the theater's Campaign folder, relative to it — generated maps
     * ("BMSC <name>.fmap") and update maps ("WeatherMapsUpdates/10500.fmap") that had no original. Deleting them
     * undoes them.
     */
    val added: List<String> = emptyList(),
    /** BMS's own update maps this program wrote over; each has its original in the backup folder. */
    val replaced: List<String> = emptyList(),
    /**
     * Why nothing can be written for this theater, in a sentence (its campaign folder is not there, or it shares
     * another theater's); null when it can be. Such a theater is listed so the pilot sees it and why. Added in 1.3.8.
     */
    val blocked: String? = null,
    /**
     * Whether the campaign folder holds BMS's four ready-made maps (Sunny, Fair, Poor, Inclement). Hellas, Hellas WCP
     * and LHTO do not: there is nothing to copy before the first write, and the grid comes from another map
     * ([gridFrom]). True from an older PC, which listed no other theater. Added in 1.3.8.
     */
    val stockMaps: Boolean = true,
    /**
     * Where a generated map's grid comes from, for the theater asked about: a map's path relative to the campaign
     * folder ("SUNNY.fmap", "SAT 001.fmap", "WeatherMapsUpdates/100000.fmap"), or "built-in" for BMS's usual 59 x 59
     * when the folder holds no version 5 map. Null for the other theaters and from an older PC. Added in 1.3.8.
     */
    val gridFrom: String? = null,
    /**
     * For the theater asked about: how many of BMS's own update maps (`WeatherMapsUpdates/<DHHMM>.fmap`, not the ones
     * this program added) are there, and the first and last of their times. They go on loading, before, after and
     * between a series' times, in every mission of the theater with MAPS AUTO UPDATE on (Korea: 741, Day 1 01:00 to
     * Day 31 21:00). Null for the other theaters and from an older PC. Added in 1.3.8.
     */
    val bmsUpdates: Int? = null,
    val bmsUpdatesFirst: com.bmscompanion.app.data.weather.CampaignTime? = null,
    val bmsUpdatesLast: com.bmscompanion.app.data.weather.CampaignTime? = null,
)

/**
 * One of the four ready-made maps BMS ships in a theater's campaign folder and lists under Weather → Map Model
 * (Sunny, Fair, Poor, Inclement `.fmap`: one weather type in every cell), as the file currently stands. They are not
 * BMS's weather models — those are Probabilistic, Deterministic and Map Model, chosen per save — and not where a
 * save's four types are set: that is each save's own `.twx`.
 */
@Serializable
data class WxModel(
    val id: String = "",
    val name: String = "",
    /** false when the file is missing or is not a format this program reads */
    val readable: Boolean = false,
    /**
     * True when this file is not the copy taken of BMS's own **and this program wrote it**: the record kept beside the
     * copy says so (`bms-companion-weather.txt`, a "wrote" line not followed by a "restored" one, and where that line
     * keeps a checksum, still those bytes). Before 1.3.8's second test build it was true for any difference, which
     * blamed the app for a BMS update. Restore is the answer.
     */
    val edited: Boolean = false,
    /**
     * True when this file differs from the copy taken of it, for whatever reason — this program ([edited]), a BMS
     * update or another tool. A difference that is not [edited] is answered by taking a new copy
     * (`/api/weather/refreshbackup`), not by Restore, which would put the older map back over BMS's. Added in 1.3.8.
     */
    val differs: Boolean = false,
    /** When the copy of this map was taken, ms since 1970 (the copy's file time); null with no copy. Added in 1.3.8. */
    val copiedAt: Long? = null,
    /** false when the map holds a pattern across the theater rather than one weather everywhere */
    val uniform: Boolean = true,
    /** the weather of the largest area, which for a map that is one weather everywhere is the whole of it */
    val weather: Wx = Wx(),
    /** the whole map, when one was asked for */
    val map: WxMap? = null,
)

/**
 * A weather map as the app edits it: a short palette of weathers, and which one each cell of the theater holds.
 *
 * The file itself keeps every property separately for every one of the 3,481 cells, which is both far more than a
 * pilot wants to set and far more than is worth sending over a network four times a page. What a pilot actually
 * does is paint areas — clear here, a front there — so the map is carried as a palette and a cell index, which is
 * a few kilobytes and is also exactly the shape the painting tool needs.
 *
 * [cells] runs **row-major from the theater's north-west corner**, the way BMS's own heightmap does.
 */
@Serializable
data class WxMap(
    val cols: Int = 0,
    val rows: Int = 0,
    val zones: List<Wx> = emptyList(),
    val cells: List<Int> = emptyList(),
    /** which way the whole weather system is travelling, and how fast: what makes a map move over the theater */
    val airmassDirDeg: Int = 0,
    val airmassSpeed: Double = 0.0,
    /**
     * the altitudes contrails form at, per map: sunny, fair, poor, inclement. (Before 1.3.8 this carried six
     * altitudes, the first two of which are really [stratusFt]; the PC still accepts six from an older client.)
     */
    val contrailFt: List<Int> = emptyList(),
    /** the high stratus layer: sunny/fair, then poor/inclement */
    val stratusFt: List<Int> = emptyList(),
) {
    fun zoneAt(col: Int, row: Int): Int =
        if (col !in 0 until cols || row !in 0 until rows) 0 else cells.getOrElse(row * cols + col) { 0 }

    companion object {
        /** As many distinct weathers as one map may hold. More than this is a texture, not a forecast. */
        const val MAX_ZONES = 8
    }
}

/**
 * One weather cell, in the units a pilot reads and the terms Falcon BMS's own weather editor uses.
 *
 * The fields are the ones the BMS technical manual lists for an fmap cell (chapter 13): weather type, pressure,
 * temperature, wind and winds aloft, cloud base, cumulus coverage and size, shower, and visibility. Units are the
 * ones BMS's briefing prints — °C, millibars, feet MSL, kilometres — except the wind, which is the one place the
 * file disagrees with the pilot: it stores km/h, and this is knots.
 */
@Serializable
data class Wx(
    /** 1 sunny, 2 fair, 3 poor, 4 inclement — BMS's four weather types, and what everything else follows from */
    val type: Int = 2,
    val tempC: Double = 20.0,
    /** 950 to 1060, the range BMS's own editor offers */
    val pressureMb: Double = 1013.0,
    /** 0 to 60 km, as the BMS weather slider */
    val visibilityKm: Double = 40.0,
    /** the low cloud base, feet MSL. BMS's own editor allows 0 to 10,000 */
    val cloudBaseFt: Double = 5000.0,
    /**
     * Cumulus coverage in oktas, as the Technical Manual counts them: 1-2 FEW, 3-4 SCT, 5-7 BKN, 8 OVC. A sunny
     * cell is clear whatever this says. The file does not hold oktas — it holds BMS's code 0/1/5/9/13 for
     * none/FEW/SCT/BKN/OVC — and the PC converts both ways (`WxCover`).
     */
    val cover: Int = 5,
    /** cumulus size, 0 congestus to 5 humilis — the second slider on BMS's own cloud tab */
    /**
     * Cumulus size, 0 congestus to 5 humilis — the second slider on BMS’s own cloud tab.
     *
     * A **float**, not a whole number: the campaign maps carry values like 3.34 and 4.94, and reading it as an
     * integer gives 1077936128, which is the bit pattern of 3.0 and an obvious tell once seen.
     */
    val cloudSize: Double = 3.0,
    /**
     * The older name of [towering], kept so an older client still reads and writes the flag: the file's array 26 is
     * towering cumulus, not showers. The PC reads it into both and writes `towering || shower`, so a client that
     * clears the flag clears both.
     */
    val shower: Boolean = false,
    /** towering cumulus: clouds with strong vertical development, the stage before a storm (array 26) */
    val towering: Boolean = false,
    val windDirDeg: Double = 180.0,
    /** ground-level wind. BMS's own editor allows 0 to 150 kt at each level */
    val windKts: Double = 5.0,
    /** the wind at the highest of BMS's ten levels: stock maps ramp up to roughly four times the surface wind */
    val windAloftKts: Double = 18.0,
    /** how far the wind veers between one level and the next; BMS's stock maps use 4 */
    val veerDeg: Double = 4.0,
) {
    /** What the coverage reads as on a metar, and what BMS's own editor calls it. */
    val coverWord: String get() = when {
        type == 1 -> "CLR"
        cover <= 2 -> "FEW"
        cover <= 4 -> "SCT"
        cover <= 7 -> "BKN"
        else -> "OVC"
    }

    /**
     * The same weather with BMS's own rules applied.
     *
     * Two of them, both from the technical manual: a **sunny** cell has clear skies whatever the coverage says, and
     * a **poor or inclement** cell is broken at minimum — BMS's own editor simply refuses to paint FEW or SCT onto
     * one. Applying them here means the app cannot write a map BMS would not have let you draw.
     */
    fun legal(): Wx = when {
        type >= 3 && cover < 5 -> copy(cover = 5)
        else -> this
    }
}

/**
 * Whether Falcon BMS is exporting the cockpit displays themselves, and what there is to be had.
 *
 * [reason] is the useful field when [available] is false: "BMS is not running" is a thing to wait for, and "the
 * export is switched off" is a thing the pilot can fix — so the page that shows it offers the button that fixes it.
 */
@Serializable
data class RttState(
    val available: Boolean = false,
    /** whether `g_bExportRTTTextures` is on, as far as can be told from the shared area existing */
    val settingOn: Boolean = false,
    val reason: String? = null,
    /** the whole export texture */
    val width: Int = 0,
    val height: Int = 0,
    /** only the displays this cockpit actually publishes: an aircraft without an HMS has no HMS rectangle */
    val areas: List<RttArea> = emptyList(),
    /**
     * Which of the states the MFD glass explains, worked out on the PC so every device says the same thing (see
     * [RttPhase]). Null from a PC older than 1.3.8, which the card then judges by [available] alone.
     */
    val phase: String? = null,
    /** whether Falcon BMS.exe is running on the PC */
    val bmsRunning: Boolean = false,
    /** whether the pilot is in the cockpit (3D), from the same flags the rest of the app uses */
    val inCockpit: Boolean = false,
    /** what Falcon BMS's config files say about the export, read without writing anything */
    val config: RttConfig? = null,
) {
    fun has(id: String) = areas.any { it.id == id }
}

/**
 * The states the MFD glass tells apart, in the order a pilot meets them.
 *
 * Strings in the JSON rather than an enum, so that a PC a version ahead can add one without a phone a version behind
 * failing to read the whole answer: an id the app does not know is shown through [RttState.reason] alone.
 */
object RttPhase {
    /** Falcon BMS is not running on the PC */
    const val NO_BMS = "nobms"
    /** BMS is running, but the pilot is in the menus rather than the cockpit */
    const val NO_3D = "no3d"
    /** in the cockpit, and the export is switched off (in the Launcher or in BMS's config) */
    const val EXPORT_OFF = "exportoff"
    /** the config says on, but this BMS session started before it did */
    const val RESTART = "restart"
    /** the export is running but this cockpit publishes no MFD rectangles */
    const val NO_MFDS = "nomfds"
    /** the export is running and both MFD pictures are black: the displays are not powered yet */
    const val DARK = "dark"
    /** the texture's rows cannot be laid out safely, so nothing is drawn rather than something sheared */
    const val PITCH = "pitch"
    /** pictures are coming */
    const val LIVE = "live"
}

/**
 * `g_bExportRTTTextures` as Falcon BMS will read it, from its own files, and who decides it.
 *
 * The Launcher matters more than anything this program can write. Its main page has an **Export RTT Textures**
 * choice, and every launch writes that choice into the Launcher's own block at the foot of `Falcon BMS User.cfg` —
 * the last lines BMS reads, so it wins over any line above it, including one the Config page adds.
 */
@Serializable
data class RttConfig(
    /** on or off as BMS will read it at its next start: the stock file, then the user file, the last line winning */
    val on: Boolean? = null,
    /** the value comes from the Launcher's own block, so the Launcher's Export RTT Textures choice decides it */
    val launcher: Boolean = false,
    /** `Falcon BMS VR.cfg`'s own line, when it has one: a VR flight reads that file last */
    val vrOn: Boolean? = null,
    /** `g_nRTTExport_FPS`, BMS's own ceiling on how often it copies the displays out (default 30) */
    val fps: Int? = null,
)

@Serializable
data class RttArea(val id: String = "", val label: String = "", val width: Int = 0, val height: Int = 0)

/**
 * One MFD option-select button, as BMS labels it.
 *
 * BMS publishes the legends around each MFD whatever else is switched on, so the bezel is always available — the
 * picture inside it needs the display export (see [RttState]) and is not always there.
 */
@Serializable
data class MfdKey(val a: String = "", val b: String = "", val inverted: Boolean = false) {
    val blank: Boolean get() = a.isBlank() && b.isBlank()
    /** The two lines run together, which is how a legend like SMS / OVRD reads aloud. */
    val text: String get() = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * One half of an MFD corner rocker and what it is bound to in the pilot's key file (`/api/mfd/keys`, `rockers`).
 *
 * [which] is `gain`, `sym`, `con` or `brt`; [callback] the Falcon BMS callback that half works (`SimCBEOSB_BRTDOWN_L`,
 * `SimRadarGainUp`), empty for SYM and CON, which BMS has none for; [key] the binding as the card prints it
 * ("Ctrl+Alt+-"), empty when the callback is not bound — then the card dims that half and names [callback] to bind.
 */
@Serializable
data class MfdRockerKey(
    val side: String = "",
    val which: String = "",
    val up: Boolean = true,
    val callback: String = "",
    val key: String = "",
)

/** What `/api/mfd/keys` answers that the card reads: the rockers' bindings (the OSBs' are in the same answer). */
@Serializable
data class MfdBindings(
    val inFront: Boolean = false,
    val fromKeyFile: Boolean = false,
    val rockers: List<MfdRockerKey> = emptyList(),
    /**
     * Why a press cannot reach Falcon BMS although it is bound, in the card's words, or "": Falcon BMS runs as
     * administrator and the PC program does not, so Windows drops the keys it sends (UIPI). Added in 1.3.8.
     */
    val blocked: String = "",
)

@Serializable
data class RwrContact(val sym: Int = 0, val brg: Double = 0.0, val lethality: Double = 0.0, val launch: Boolean = false, val lock: Boolean = false, val selected: Boolean = false, val new: Boolean = false)

/**
 * One of the jet's navigation points from shared memory. [type] is BMS's own: `WP` steerpoint, `PT` pre-planned
 * threat, `L1`-`L4` a point of line 1-4, `CB` the bullseye, and the others BMS lists. A steerpoint with offset
 * aimpoints carries them: OA1 and OA2 as true bearing (degrees), range and elevation (feet), null when it has none.
 */
@Serializable
data class NavPoint(
    val i: Int = 0,
    val type: String = "WP",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val altFt: Double = 0.0,
    val name: String? = null,
    val rangeNm: Double? = null,
    val oa1Brg: Double? = null,
    val oa1RngFt: Double? = null,
    val oa1ElevFt: Double? = null,
    val oa2Brg: Double? = null,
    val oa2RngFt: Double? = null,
    val oa2ElevFt: Double? = null,
)

@Serializable
data class Voice(val flight: String? = null, val seats: String? = null, val tanker: String? = null, val awacs: String? = null, val departure: String? = null, val arrival: String? = null, val alternate: String? = null)

@Serializable
data class Pilot(val callsign: String = "", val status: Int = 0)

@Serializable
data class Contact(
    val id: String = "",
    val kind: String = "air",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val altFt: Double = 0.0,
    val hdg: Double = 0.0,
    val gsKts: Double = 0.0,
    val name: String? = null,
    val pilot: String? = null,
    val group: String? = null,
    val coalition: String? = null,
    val color: String? = null,
    val own: Boolean = false,
    /**
     * On your side. Not the same thing as the same [coalition]: BMS writes the *country* there ("Hellas", "U.S."), so
     * the server decides this from the campaign's own table of which teams are allied or friendly.
     */
    val friendly: Boolean = false,
    /** Tacview extras when BMS sends them: indicated airspeed (kt), Mach, fuel (lb), id of the contact this one has locked. */
    val ias: Double? = null,
    val mach: Double? = null,
    val fuelLb: Double? = null,
    val locked: String? = null,
    /** In your own flight: the same callsign with another number — "Tiger12" when you are "Tiger11". */
    val wingman: Boolean = false,
    /** A team the campaign says your side is neutral toward, or has no relations with: neither friend nor foe. */
    val neutral: Boolean = false,
) {
    /**
     * On the other side: not you, not an ally, not a neutral. Everything that counts threats uses this rather than
     * "not friendly", which used to sweep neutrals — and, before the server read the campaign's alliances, every
     * allied nation's aircraft — into the enemy.
     */
    val hostile: Boolean get() = !own && !friendly && !neutral
}

@Serializable
data class Contacts(
    val t: Long = 0,
    val connected: Boolean = false,
    val state: String = "off",
    val contacts: List<Contact> = emptyList(),
    /** recent events from the feed (weapon hits and misses); newest last */
    val events: List<TacEvent> = emptyList(),
)

/** An event the AWACS feed reported, e.g. "AIM-120C AMRAAM hit". [who] and [target] are resolved names when known. */
@Serializable
data class TacEvent(val id: Long = 0, val kind: String = "", val text: String = "", val who: String? = null, val target: String? = null, val mine: Boolean = false)

@Serializable
data class MissionData(
    val version: String = "",
    val briefingModified: Long = 0,
    val briefing: Briefing? = null,
    val dtc: Dtc? = null,
    val board: Board? = null,
    /** What the campaign planned for the tankers and the AWACS, when the mission file gives it. */
    val tracks: List<SupportTrack> = emptyList(),
    /**
     * Falcon BMS's own mission file beside the save being flown, only when it is provably the briefed flight's (its
     * route matches the printed briefing and the save): the steerpoint positions before 3D. See [MissionRoute].
     */
    val route: MissionRoute? = null,
    /**
     * WDP mode's snapshot carries the Planner's flight here (its route, briefing, seat and attack; source
     * [PlanSource.POPULATED], no cartridge of its own), so every view merges it as it always has. Null in EZBoards mode:
     * since 1.3.8 nothing is laid over a printed briefing (the Planner's Send to Mission is gone).
     */
    val plan: PlanOverlay? = null,
    /** which mode this is the data of ([MissionMode]); an older PC leaves it out, which reads as EZBoards mode */
    val mode: String = MissionMode.EZBOARDS,
    /** WDP mode: what the snapshot was made of; null in EZBoards mode, and in WDP mode until Populate from Planner */
    val populated: Populated? = null,
    /**
     * Kept for older devices only, and always null since the clearing became automatic (1.3.8): what the Planner saved
     * into the cartridge for an earlier flight is cleared by the PC as soon as a new mission begins
     * ([SwitchReset.kind] [SwitchReset.MISSION]), so nothing is ever drawn as "left from …".
     */
    val leftovers: CartridgeLeftovers? = null,
    /**
     * The mission's threat picture ([MissionGround]: the air defences the printed briefing names, else the spotted ones
     * along the route, and the spotted ships near it), which every map rings by default ([MissionPicture]). EZBoards
     * mode: placed in the save BMS is flying; WDP mode: the populated flight's save, taken with the snapshot. Added in
     * 1.3.8; null when there is none.
     */
    val ground: MissionGround? = null,
) {
    /** WDP mode before the first Populate: every Mission view shows [MissionMode.NOT_POPULATED] and nothing else */
    val awaitingPopulate: Boolean get() = mode == MissionMode.WDP && populated == null

    /**
     * Which mission this is, for choices that belong to one mission and must not carry into the next (the Taxi page's
     * field, runway and spot): WDP mode's snapshot by when it was populated, EZBoards mode's by when BMS printed the
     * briefing. A cartridge saved since is the same mission. Not sent; worked out on each side.
     */
    val missionKey: String get() = if (mode == MissionMode.WDP) "wdp-${populated?.at ?: 0}" else "ez-$briefingModified"
}

/**
 * The track a tanker or an AWACS is planned to fly.
 *
 * Read from the mission file Falcon BMS is flying, which is the only place the plan for somebody else's flight
 * exists — the live feeds carry where an aircraft is, never where it is going. [points] is the whole route in
 * theater feet; the ones the flight is on station for are marked, because that pair is what a tanker track is
 * drawn between.
 */
@Serializable
data class SupportTrack(
    val role: String = "",
    /** what the campaign calls the job: "AIR REFUEL", "AEW/ABCCC" */
    val mission: String? = null,
    /** "Texaco1", so the app can tie the track to the tanker the briefing gave you */
    val callsign: String? = null,
    /** true for the tanker or AWACS your own flight is assigned to */
    val yours: Boolean = false,
    val points: List<TrackPoint> = emptyList(),
)

/** One point of a planned track, with the clock times the campaign gave it. */
@Serializable
data class TrackPoint(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val altFt: Double = 0.0,
    /** true where the flight holds — the legs of a tanker's racetrack */
    val station: Boolean = false,
    val arriveMs: Long = 0,
    val departMs: Long = 0,
)

@Serializable
data class Briefing(
    val generated: String? = null,
    val overview: BriefOverview = BriefOverview(),
    val situation: String? = null,
    val roster: List<RosterFlight> = emptyList(),
    val `package`: List<PackageFlight> = emptyList(),
    val threats: List<TextBlock> = emptyList(),
    val steerpoints: List<BriefSteerpoint> = emptyList(),
    val comms: List<CommEntry> = emptyList(),
    val ordnance: List<OrdnanceFlight> = emptyList(),
    val weather: WeatherTable? = null,
    val support: List<SupportEntry> = emptyList(),
    val roe: List<String> = emptyList(),
    val emergency: List<TextBlock> = emptyList(),
    val alternate: String? = null,
    val sections: List<RawSection> = emptyList(),
    /**
     * Where this briefing came from: null for BMS's printed `briefing.txt`, "save" for one the PC built from a flight
     * of a campaign or TE save (`CampFlight.briefing`), which has no situation, weather text, comm ladder, ROE or
     * emergency — only a printed briefing has those. "printed" (later in 1.3.8) is BMS's printed briefing carried in WDP
     * mode's snapshot because it is the populated flight's (`Populated.briefingFrom`): every view treats it as the
     * printed briefing it is, so both modes show the same mission.
     */
    val origin: String? = null,
)

@Serializable
data class BriefOverview(
    val flight: String? = null, val mission: String? = null, val packageId: String? = null, val packageType: String? = null,
    val packageMission: String? = null, val targetArea: String? = null, val tot: String? = null, val sunrise: String? = null, val sunset: String? = null,
)

@Serializable data class RosterFlight(val callsign: String = "", val pilots: List<String> = emptyList())

@Serializable
data class PackageFlight(
    val callsign: String = "", val flightId: String? = null, val primary: Boolean = false, val role: String? = null, val aircraft: String? = null,
    val count: Int? = null, val task: String? = null, val takeoff: String? = null, val push: String? = null, val target: String? = null, val iff: String? = null,
)

/**
 * The pilot's own flight among the package's. BMS's "x = Primary Flight" mark in the briefing's Package Elements is the
 * package's lead tasking (the strike a SEAD or escort flight supports), not the pilot's flight, so it is never used for
 * this: the flight is found by [callsign], else the briefing's own flight (the overview's "Cajun1 (…)"), else the roster
 * row with a pilot assigned (the human flight).
 */
fun Briefing.ownFlight(callsign: String? = null): PackageFlight? {
    fun n(s: String?) = s?.replace(" ", "")?.lowercase()?.takeIf { it.isNotEmpty() }
    val cs = n(callsign) ?: n(overview.flight)
    if (cs != null) `package`.firstOrNull { n(it.callsign) == cs }?.let { return it }
    val manned = roster.firstOrNull { r -> r.pilots.any { it.isNotBlank() && !it.equals("Unassigned", ignoreCase = true) } }?.callsign
    return n(manned)?.let { m -> `package`.firstOrNull { n(it.callsign) == m } }
}

@Serializable data class TextBlock(val title: String? = null, val lines: List<String> = emptyList())

@Serializable
data class BriefSteerpoint(
    val n: Int = 0, val desc: String? = null, val time: String? = null, val dist: String? = null, val heading: String? = null,
    val cas: String? = null, val alt: String? = null, val action: String? = null, val formation: String? = null, val comments: String? = null,
)

@Serializable
data class CommEntry(
    val agency: String = "", val callsign: String? = null, val uhf: String? = null, val uhfCh: Int? = null,
    val vhf: String? = null, val vhfCh: Int? = null, val notes: String? = null, val group: String? = null,
)

@Serializable data class OrdnanceFlight(val flight: String = "", val aircraft: List<OrdnanceAircraft> = emptyList())
@Serializable data class OrdnanceAircraft(val name: String = "", val stores: List<Store> = emptyList())
@Serializable data class Store(val qty: Int = 1, val name: String = "")
/**
 * A briefing's weather block: [columns] (BMS's "Take Off", "Target Area", "Landing") and a row per figure, each with a
 * value per column. [source] is null for BMS's printed forecast; WDP mode's snapshot, which reads the save's own
 * weather file at Populate, says there which file and as of when ("Auto Save.twx, as saved at D1 01:02") — added in 1.3.8.
 */
@Serializable data class WeatherTable(val columns: List<String> = emptyList(), val rows: List<WeatherRow> = emptyList(), val source: String? = null)
@Serializable data class WeatherRow(val label: String = "", val values: List<String> = emptyList())
@Serializable data class SupportEntry(val callsign: String = "", val role: String? = null, val aircraft: String? = null, val notes: String? = null)
@Serializable data class RawSection(val title: String = "", val rows: List<List<String>> = emptyList())

@Serializable
data class Dtc(
    val modified: Long = 0,
    val steerpoints: List<DtcPoint> = emptyList(),
    val weaponTargets: List<DtcPoint> = emptyList(),
    val ppts: List<DtcPpt> = emptyList(),
    val lines: List<DtcPoint> = emptyList(),
    val uhf: List<Preset> = emptyList(),
    val vhf: List<Preset> = emptyList(),
    val iff: Map<String, String> = emptyMap(),
    // Added in 1.3.8 for the Planner's plan; every one is defaulted, and the old fields keep their meaning.
    /** STPT 81-99, the second precision-target bank. They also stay in [steerpoints], so an older reader still finds them. */
    val open: List<DtcPoint> = emptyList(),
    /** `[NAV OFFSETS]`: VIP/VRP, their pull-up points and the offset aimpoints (written by WDP and the Planner) */
    val navOffsets: NavOffsets? = null,
    /** `[COMMS]`: the presets tuned after a DTC load, and the TACAN and ILS WDP writes there */
    val comm: DtcComm? = null,
    /** `[Laser]` LaserST: when the laser starts firing, seconds before impact */
    val laserSt: Int? = null,
    /** `[Laser]` LaserTGP and LaserLST: the targeting pod's laser code and the laser spot tracker's */
    val laserTgp: Int? = null,
    val laserLst: Int? = null,
    /** `[ICP]` bingo fuel, lb */
    val bingoLbs: Int? = null,
    /** `[ICP]` ALOW (the CARA low-altitude warning), feet AGL, and the MSL floor, feet MSL */
    val alowFt: Int? = null,
    val mslFloorFt: Int? = null,
    /** `[EWS]` the names of the six countermeasure programs, in order; empty when the file names none */
    val ewsNames: List<String> = emptyList(),
)

/**
 * One cartridge point: a steerpoint, a weapon target or a line point. [line] (1.3.8) is the line (1-4) a line point
 * belongs to; each line joins its own points in order, and lines are never joined to each other.
 */
@Serializable data class DtcPoint(
    val n: Int = 0, val x: Double = 0.0, val y: Double = 0.0, val altFt: Double = 0.0, val action: Int = 0, val isTarget: Boolean = false, val name: String? = null,
    val line: Int? = null,
)

/**
 * One pre-planned threat (STPT 56-70). [rangeNm] and [name] keep their old meaning for older clients. From 1.3.8:
 * [code] is the key in the theater's `Ppt.ini` ("SA3", "10", "AWC"), [rangeFt] the range as the file holds it, and
 * [marker] true for a range under 100 ft — a point with no ring (AWACS, tanker, a friendly), whose [rangeNm] is 0.
 */
@Serializable data class DtcPpt(
    val n: Int = 0, val x: Double = 0.0, val y: Double = 0.0, val altFt: Double = 0.0, val rangeNm: Double = 0.0, val name: String? = null,
    val code: String? = null,
    val rangeFt: Double = 0.0,
    val marker: Boolean = false,
)
@Serializable data class Preset(val ch: Int = 0, val freq: String = "", val comment: String? = null)

@Serializable data class Board(val time: Long = 0, val format: String = "", val tables: List<BoardTable> = emptyList())
@Serializable data class BoardTable(val title: String = "", val header: List<String> = emptyList(), val rows: List<BoardRow> = emptyList())
@Serializable data class BoardRow(val kind: String? = null, val cells: List<String> = emptyList())

/**
 * The VR boards.
 *
 * A board is one OpenKneeboard tab pointed at `/kneeboard/<n>`, showing one kind of page and nothing else — there is
 * no pointer in a headset, so there is nothing on a board to press. The configuration lives on the PC (in
 * `bridge-settings.json`) rather than in each browser, because the headset's browser is not somewhere a pilot can
 * conveniently set anything up, and because every board should agree about the print size and the light.
 */
@Serializable
data class BoardConfig(
    /** Ink on dark paper, for a night flight. Pages that are pictures — the map, the plates — ignore it. */
    val night: Boolean = false,
    /** How large the print is: an index into the sizes the board offers. */
    val print: Int = 1,
    /**
     * Bumped by **Rebuild pages** on the PC. A board builds its pages from the mission and rebuilds them when the
     * mission changes, but a briefing that was printed while a board sat on a chart is the kind of thing that leaves
     * a pilot looking at last night's field. This is the button that says "do it again now".
     */
    val rev: Int = 0,
    val slots: List<BoardSlot> = emptyList(),
)

/** One board: its number (the address it answers on), what it shows, and anything that kind of page can be told. */
@Serializable
data class BoardSlot(val n: Int = 1, val kind: String = "map", val options: Map<String, String> = emptyMap())

@Serializable data class DiscoveryReply(val service: String = "", val name: String = "", val port: Int = 47474, val version: String = "", val api: Int = 1)

/**
 * What the Taxi page is showing, so a VR board can show the same thing.
 *
 * The page and the board are different programs — the board is a browser tab the PC serves — so the choice travels
 * through the PC as a small piece of state rather than being shared in memory.
 */
@Serializable
data class TaxiSelection(
    val airportId: Int = 0,
    val runway: String = "",
    val outbound: Boolean = true,
    val spot: Int? = null,
    val at: Long = 0,
    /**
     * Served by the PC only (`GET /api/taxi`, 1.3.8): the last controller's call to the pilot's own flight that moves
     * the Taxi page, from BMS's debug log; null without one. The live taxi board applies it when it is newer than [at].
     */
    val radio: RadioTaxi? = null,
)

/** One point of an attack as the jet will have it: a steerpoint, an offset or a reference, in sim feet. */
@Serializable
data class AttackCue(val label: String = "", val north: Double = 0.0, val east: Double = 0.0, val kind: Kind = Kind.TARGET, val note: String = "") {
    enum class Kind { TARGET, IP, VIP, VRP, PUP, OA }
}

/**
 * The attack the Planner last worked out (Pop-up, HADB or TOSS): its cues, and the line the page's own map draws
 * through them. The same picture goes to the VR boards, which are browser tabs of their own and cannot see the
 * Planner, so it travels through the PC as the Taxi page's choice does (`/api/attack`). An empty [cues] is "none".
 * [theater] is the id of the theater it was planned in (empty when that was not known): the attack is kept across
 * a restart, and sim feet from one theater are a place in every other, so a map of another theater does not draw it.
 *
 * Since 1.3.8 every map draws it one way ([AttackDrawing], `ui/components/AttackDraw.kt`), and it says what it is
 * (all additive): [profile] "Pop-up", "HADB", "TOSS" or "" (a cartridge whose attack page is not known), [mode] "VIP"
 * or "VRP", [tgtStpt] the target steerpoint (0 when not known), [refStpt] the steerpoint the lines hang on (the IP in
 * VIP mode, the target in VRP mode), [saved] whether the cartridge's `[NAV OFFSETS]` hold it (within 150 ft; true for
 * an overlay that does not say), and [beyond] the dotted leg past the target (TGT → OA2 on Pop-up and HADB). [runIn] is
 * the path the jet flies, profile-shaped, from the VIP or VRP to the target.
 */
@Serializable
data class AttackOverlay(
    val page: String = "", val cues: List<AttackCue> = emptyList(), val runIn: List<Pair<Double, Double>> = emptyList(),
    val theater: String = "",
    val profile: String = "",
    val mode: String = "",
    val tgtStpt: Int = 0,
    val refStpt: Int = 0,
    val saved: Boolean = true,
    val beyond: List<Pair<Double, Double>> = emptyList(),
) {
    val target: AttackCue? get() = cues.firstOrNull { it.kind == AttackCue.Kind.TARGET }
}

/**
 * Falcon BMS's own config files, as the Config page sees them.
 *
 * BMS keeps its settings in `User/Config`: `Falcon BMS User.cfg` for a flat screen, `Falcon BMS VR.cfg` when it
 * starts in a headset. Editing them is the one thing BMS Companion writes into the BMS folder, so nothing is
 * offered until the pilot has pressed the button that takes a copy — [userBackedUp] is what that button leaves
 * behind, and the page stays locked until it is true.
 */
@Serializable
data class CfgState(
    /** False when no BMS install is known, which is the only reason the page cannot work at all. */
    val available: Boolean = false,
    val configDir: String? = null,
    val backupDir: String? = null,
    /** The copy of the file as it was before any of this: the way back, taken once and never replaced. */
    val userBackedUp: Boolean = false,
    /** A VR config only exists once BMS has been run in a headset, so it may appear long after the first backup. */
    val vrPresent: Boolean = false,
    val vrBackedUp: Boolean = false,
    val user: CfgProfiles = CfgProfiles(),
    val vr: CfgProfiles = CfgProfiles(),
    /**
     * Why the last thing asked for did not happen, in words a pilot can act on — usually Windows refusing to write
     * where Falcon BMS is installed. Nothing here ever throws: a folder that cannot be written is an answer, not a
     * crash, and the page says so instead of going quiet.
     */
    val error: String? = null,
)

/** The three sets of settings a pilot switches between, and which one the live file is a copy of. */
@Serializable data class CfgProfiles(val selected: Int = 1, val profiles: List<Boolean> = emptyList())

/** One profile's contents: every `set` line the file actually holds, in the order it holds them. */
@Serializable data class CfgFile(
    val kind: String = "user",
    val profile: Int = 1,
    val lines: List<CfgLine> = emptyList(),
    /** Why a change did not take, when it did not. See [CfgState.error]. */
    val error: String? = null,
)

/**
 * One line of a config file.
 *
 * [launcher] marks a line below "LAUNCHER OVERRIDES BEGIN HERE", which the BMS launcher writes and owns. Those are
 * shown but never touched: they are carried across unchanged when a profile is applied.
 */
@Serializable data class CfgLine(val key: String = "", val value: String = "", val launcher: Boolean = false)


/**
 * The pilot's data cartridge (`User/Config/<callsign>.ini`), as the Planner's DTC page reads and writes it.
 *
 * Save to DTC writes it directly, as WDP does: no copy first and nothing to switch on (1.3.8 test builds had
 * `enabled`, `backedUp` and `backupDir` here and a `POST /api/cartridge/backup`; all four are gone). [text] is the file
 * as it is on disk now, whole, because the page shows every section of it and writes back only the keys that were edited.
 */
@Serializable
data class CartridgeState(
    /** a cartridge file was found for [callsign] */
    val available: Boolean = false,
    val callsign: String? = null,
    /** the file's name, as WDP shows it ("Viper.ini") */
    val file: String? = null,
    val text: String? = null,
    /** the file's own timestamp, so a page can tell BMS has rewritten it since */
    val modified: Long = 0,
    /** the file's full path on the PC, as WDP names it on the DTC page ("…\User\Config\Viper.ini") */
    val path: String? = null,
    /** the theater's own `ppt.ini` (its PPT types: code, range in feet, name), read-only; null where none was found */
    val pptIni: String? = null,
    /** why the last step failed, in a sentence for the page to show */
    val error: String? = null,
    /** what the last step did, when it did something ("Viper.ini saved.") */
    val message: String? = null,
    /**
     * When the save also named a Tactical Engagement (`/api/cartridge/save?te=`): what happened to that TE's own
     * mission file, which BMS loads over the cartridge in a TE. Null when no TE was named.
     */
    val mission: MissionIniResult? = null,
    /**
     * What the Planner's Save to DTC has written into this cartridge and for which mission ([CartridgeLedger], kept on
     * the PC in `<BMS>\User\BMS Companion Planner\Ledger\`), so a device can tell what is left from an earlier mission
     * ([Leftovers.find]). Added in 1.3.8; null from an older PC or before the Planner's first save.
     */
    val ledger: CartridgeLedger? = null,
)

/** One key of the cartridge to set to [value], or to remove when [value] is null. */
@Serializable
data class CartridgeEdit(val section: String = "", val key: String = "", val value: String? = null)

// ---------------------------------------------------------------- the radio, from BMS's debug log (1.3.8)

/**
 * Who a radio call is from or for, in the six groups the Radio page files it under ([RadioMessage.category]). BMS
 * colours its own subtitles three ways (`g_sRadioStandardCol` blue, `g_sRadioTowerCol` green, `g_sRadioflightCol` red);
 * the page keeps that idea: ATC green, the pilot's own flight red, AWACS blue.
 */
object RadioCategory {
    const val ATC = "atc"
    const val AWACS = "awacs"
    const val TANKER = "tanker"
    const val MINE = "mine"
    const val FLIGHTS = "flights"
    const val OTHER = "other"
    val ALL = listOf(ATC, AWACS, TANKER, MINE, FLIGHTS, OTHER)

    fun label(c: String): String = when (c) {
        ATC -> "ATC"; AWACS -> "AWACS"; TANKER -> "Tanker"; MINE -> "My flight"; FLIGHTS -> "Flights"; else -> "Other"
    }
}

/**
 * One radio call Falcon BMS put on screen as a subtitle, read by the PC from BMS's debug log (`User\Logs\…_xlog.txt`,
 * the lines `[hh:mm:ss.mmm] <thread> Subtitle: <text>`). `GET /api/radio` (docs/PROTOCOL.md, "The radio").
 */
@Serializable
data class RadioMessage(
    /** this session's running number, from 1 */
    val seq: Long = 0,
    /** the sim clock the log prints, "04:12:30" */
    val time: String = "",
    /** the call as BMS subtitled it; the lines BMS printed under it at the same moment (an AWACS picture's groups) after a newline */
    val text: String = "",
    /** [RadioCategory] */
    val category: String = RadioCategory.OTHER,
    /** who speaks, as the call names them ("magic 5", "Gunsan Tower", "Jaguar 2-1"); null when it names no one */
    val from: String? = null,
    /** who is spoken to ("Jaguar 2-1", "All flights") */
    val to: String? = null,
    /** what a controller's call says, when it is one the Taxi page can use */
    val atc: RadioAtc? = null,
    /** the call is to or from the pilot's own flight (whatever its [category]): what the page's "My flight" shows */
    val mine: Boolean = false,
)

/** The parts of a controller's call (BMS's `CommFile.xml` calls 38, 39, 284, 305, 306, 361, 387, 391, 504, 517). */
@Serializable
data class RadioAtc(
    /** one of the [RadioTaxi] kinds, or "atc" for any other controller's call */
    val kind: String = "atc",
    /** "Gunsan Tower" */
    val station: String? = null,
    /** "Gunsan" */
    val field: String? = null,
    /** the runway as the app's charts name it ("36", "09L") */
    val runway: String? = null,
    /** the parking spot Ground gave ("park 0 0 4" → 4) */
    val spot: Int? = null,
    /** the taxiway letters in the order said ("Alpha Charlie" → A, C) */
    val letters: List<String> = emptyList(),
)

/**
 * The last controller's call to the pilot's own flight that moves the Taxi page: the runway to taxi to, the spot Ground
 * gave after landing, or that it gave none. The Taxi page and the live taxi VR board apply each one once ([seq]); a
 * runway or spot the pilot picks afterwards wins.
 */
@Serializable
data class RadioTaxi(
    val seq: Long = 0,
    /** when the PC read it (ms since 1970, the PC's clock) */
    val at: Long = 0,
    /** the sim clock of the call */
    val time: String = "",
    /** [OUT], [LINEUP], [TAKEOFF], [VACATE], [BACK], [PARK] or [NOSPOT] */
    val kind: String = "",
    /** taxi out: the runway to taxi to; the way in: the runway landed on, when a landing clearance named it */
    val runway: String? = null,
    val spot: Int? = null,
    val letters: List<String> = emptyList(),
    val field: String? = null,
    val text: String = "",
) {
    val outbound: Boolean get() = kind == OUT || kind == LINEUP || kind == TAKEOFF

    companion object {
        /** Ground: "taxi Alpha Charlie and hold short runway 3-6" (calls 284, 387) */
        const val OUT = "out"
        /** Tower: "position and hold runway 3-6" (306) */
        const val LINEUP = "lineup"
        /** Tower: "runway 3-6 cleared for takeoff" (39, 361) */
        const val TAKEOFF = "takeoff"
        /** Tower: "cleared for landing runway 3-6" (38, 361), Approach: "continue inbound for runway 3-6" — remembered, not applied */
        const val LANDING = "landing"
        /** Tower after landing: "taxi clear of the runway" (305) */
        const val VACATE = "vacate"
        /** Ground: "Taxi back Alpha and hold short runway 3-6" (504) */
        const val BACK = "back"
        /** Ground: "Taxi back to the ramp Alpha Charlie park 0 0 4" (517) */
        const val PARK = "park"
        /** Ground: "Taxi to the ramp, welcome back" — no spot given (391) */
        const val NOSPOT = "nospot"
    }
}

/** Deleting BMS's old debug logs (off unless the pilot turns it on), and what it would free now. */
@Serializable
data class RadioLogCleanup(
    val on: Boolean = false,
    /** how many to keep: sessions, or days when [byDays] */
    val keep: Int = 5,
    val byDays: Boolean = false,
    /** what "Delete old logs now" would move to the Recycle Bin now */
    val files: Int = 0,
    val bytes: Long = 0,
    /** the last clean-up, by hand or by itself: when, and how many files and bytes went */
    val lastRun: Long = 0,
    val lastDeleted: Int = 0,
    val lastBytes: Long = 0,
    /** why the last clean-up could not run, in a sentence */
    val error: String? = null,
)

/**
 * Whether BMS's debug log is there to read: [OFF] (no log for this run of BMS — debug mode is off), [WAITING] (a log,
 * no radio subtitle in it yet) or [LIVE]. In `BridgeInfo.radio` and `GET /api/radio`.
 */
@Serializable
data class RadioLogStatus(
    val state: String = OFF,
    /** the log file's name ("2026-10-05_161445_xlog.txt") */
    val file: String? = null,
    /** subtitle lines read from it */
    val lines: Int = 0,
    /** when the PC last read a subtitle line (ms, the PC's clock); 0 = none yet */
    val lastLineAt: Long = 0,
    val bmsRunning: Boolean = false,
    /** one sentence for the page: why it is off, or what to check */
    val note: String? = null,
    val cleanup: RadioLogCleanup = RadioLogCleanup(),
) {
    companion object {
        const val OFF = "off"
        const val WAITING = "waiting"
        const val LIVE = "live"
    }
}

/** `GET /api/radio?since=<seq>`: this session's calls after [RadioMessage.seq] `since` (all of them for 0). */
@Serializable
data class RadioLog(
    val status: RadioLogStatus = RadioLogStatus(),
    /** the log the calls come from; another name means BMS was restarted: start the list afresh */
    val session: String = "",
    val messages: List<RadioMessage> = emptyList(),
    /** the highest [RadioMessage.seq] of the session, to ask `since` next time */
    val last: Long = 0,
    /** the pilot's own flight as the PC knows it ("Jaguar 2") */
    val own: String? = null,
    val taxi: RadioTaxi? = null,
)
