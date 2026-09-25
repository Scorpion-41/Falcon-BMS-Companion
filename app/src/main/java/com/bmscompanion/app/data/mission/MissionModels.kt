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
)

/**
 * The kneeboard UOAF's html_brief last exported on the BMS PC: [pages] of it, ready to read through
 * /api/kneeboard/page. [stale] means BMS has printed a newer briefing than the export, so the pages are the
 * previous flight's until the pilot exports again.
 */
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
data class TacviewStatus(val enabled: Boolean = false, val connected: Boolean = false, val state: String = "off", val objects: Int = 0)

@Serializable
data class BriefingStatus(val available: Boolean = false, val modified: Long = 0, val generated: String? = null, val dtcModified: Long = 0)

@Serializable
data class EzStatus(val configured: Boolean = false, val path: String? = null, val autoOnPrint: Boolean = false, val running: Boolean = false, val lastRun: EzRun? = null)

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
    val navPoints: List<NavPoint> = emptyList(),
    val voice: Voice? = null,
    val pilots: List<Pilot> = emptyList(),
) {
    val fuelTotal get() = fuelInternal + fuelExternal
}

@Serializable
data class RwrContact(val sym: Int = 0, val brg: Double = 0.0, val lethality: Double = 0.0, val launch: Boolean = false, val lock: Boolean = false, val selected: Boolean = false, val new: Boolean = false)

@Serializable
data class NavPoint(val i: Int = 0, val type: String = "WP", val x: Double = 0.0, val y: Double = 0.0, val altFt: Double = 0.0, val name: String? = null, val rangeNm: Double? = null)

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
)

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
@Serializable data class WeatherTable(val columns: List<String> = emptyList(), val rows: List<WeatherRow> = emptyList())
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
)

@Serializable data class DtcPoint(val n: Int = 0, val x: Double = 0.0, val y: Double = 0.0, val altFt: Double = 0.0, val action: Int = 0, val isTarget: Boolean = false, val name: String? = null)
@Serializable data class DtcPpt(val n: Int = 0, val x: Double = 0.0, val y: Double = 0.0, val altFt: Double = 0.0, val rangeNm: Double = 0.0, val name: String? = null)
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
)

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
