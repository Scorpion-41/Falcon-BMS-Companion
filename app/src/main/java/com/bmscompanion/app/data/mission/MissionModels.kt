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
    val demo: Boolean = false,
    val bms: BmsStatus = BmsStatus(),
    val tacview: TacviewStatus = TacviewStatus(),
    val briefing: BriefingStatus = BriefingStatus(),
    val ezBoards: EzStatus = EzStatus(),
)

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
    val friendly: Boolean = false,
)

@Serializable
data class Contacts(val t: Long = 0, val connected: Boolean = false, val state: String = "off", val contacts: List<Contact> = emptyList())

@Serializable
data class MissionData(
    val version: String = "",
    val briefingModified: Long = 0,
    val briefing: Briefing? = null,
    val dtc: Dtc? = null,
    val board: Board? = null,
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

@Serializable data class DiscoveryReply(val service: String = "", val name: String = "", val port: Int = 47474, val version: String = "", val api: Int = 1)
