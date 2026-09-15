package com.bmscompanion.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------- index ----------
@Serializable
data class DataIndex(
    val bmsVersion: String = "",
    val generated: String = "",
    val theaters: List<Theater> = emptyList(),
    val curated: List<String> = emptyList(),
    val tacrefCategories: Map<String, String> = emptyMap(),
    val tacrefSubcategories: Map<String, String> = emptyMap(),
)

@Serializable
data class Theater(
    val id: String,
    val name: String,
    val desc: String = "",
    val addon: Boolean = false,
    val sizeFt: Double = 3358700.0,
    val map: String? = null,
    /** map folder id under assets/maps and data/geo, e.g. "korea" (shared by add-ons on the same terrain) */
    val mapId: String? = null,
    val airportSet: String = "",
    val radioSet: String = "",
    val airportCount: Int = 0,
    val aircraftCount: Int = 0,
    /** true for theaters that ship their own terrain (KTO, Balkans, Hellas, Israel, Falklands) */
    val primary: Boolean = true,
    /** main theater whose map/airfields this theater uses */
    val mainTheater: String? = null,
    /** add-on theaters grouped under this main theater */
    val includes: List<String> = emptyList(),
)

// ---------- aircraft ----------
@Serializable
data class Aircraft(
    val key: String,
    val name: String,
    val family: String? = null,
    val familyTitle: String = "",
    val role: String = "",
    val pic: String? = null,
    val tacref: String? = null,
    val variants: List<AircraftVariant> = emptyList(),
)

@Serializable
data class AircraftVariant(
    val spec: AircraftSpec = AircraftSpec(),
    val gun: Gun? = null,
    val stations: List<Station> = emptyList(),
    val theaters: List<String> = emptyList(),
)

@Serializable
data class AircraftSpec(
    val datFile: String? = null,
    val crew: Int? = null,
    val inService: Int? = null,
    val maxSpeedKts: Double? = null,
    val ceilingFt: Double? = null,
    val cruiseAltFt: Double? = null,
    val maxWeightLbs: Double? = null,
    val emptyWeightLbs: Double? = null,
    val internalFuelLbs: Double? = null,
    val rcs: Double? = null,
    val radar: RadarSpec? = null,
    val fm: FlightModel? = null,
)

@Serializable
data class RadarSpec(val name: String = "", val detectionNm: Double? = null, val scanWidthDeg: Double? = null)

@Serializable
data class FlightModel(
    val type: String? = null,
    val maxG: Double? = null,
    val aoaMax: Double? = null,
    val maxVcasKts: Double? = null,
    val cornerKts: Double? = null,
    val lengthFt: Double? = null,
    val spanFt: Double? = null,
    val wingAreaSqft: Double? = null,
    val chaff: Int? = null,
    val flares: Int? = null,
)

@Serializable
data class Gun(val weapon: String, val name: String, val rounds: Int = 0)

@Serializable
data class Station(
    val n: Int,
    val label: String = "",
    val list: String? = null,
    val fixed: Boolean = false,
    val weapons: List<StationWeapon> = emptyList(),
)

@Serializable
data class StationWeapon(val key: String, val max: Int = 1, val rack: String? = null)

// ---------- weapons ----------
@Serializable
data class Weapon(
    val key: String,
    val name: String,
    val category: String = "OTHER",
    val weightLbs: Double? = null,
    val drag: Double? = null,
    val rangeKm: Double? = null,
    val blastRadiusFt: Double? = null,
    val guidance: List<String> = emptyList(),
    val simClass: String? = null,
    val simName: String? = null,
    val hits: Hits? = null,
    val strength: Double? = null,
    val tacref: String? = null,
    val pic: String? = null,
    val theaters: List<String> = emptyList(),
    val carriedBy: List<String> = emptyList(),
)

@Serializable
data class Hits(val air: Int = 0, val lowAir: Int = 0, val ground: Int = 0, val naval: Int = 0)

// ---------- encyclopedia (TacRef) ----------
@Serializable
data class EncyEntry(
    val key: String,
    val name: String,
    val cat: Int = 0,
    val sub: Int = 0,
    val catName: String = "",
    val subName: String? = null,
    val pic: String? = null,
    val sections: List<EncySection> = emptyList(),
    val description: String = "",
    val rwr: String? = null,
    val theaters: List<String> = emptyList(),
)

@Serializable
data class EncySection(val title: String? = null, val lines: List<String> = emptyList())

// ---------- airports ----------
@Serializable
data class AirportSet(val airports: List<Airport> = emptyList(), val navaids: List<Navaid> = emptyList(), val places: List<Place> = emptyList())

/** City / town / village from the campaign objectives (t = "city" | "town" | "village"). */
@Serializable
data class Place(val n: String = "", val t: String = "village", val x: Double = 0.0, val y: Double = 0.0)

// ---------- map landmarks (data/geo/<mapId>.json, from Natural Earth, projected onto the theater grid) ----------
/** A border line as flat theater-feet pairs [x, y, x, y, …]: [lo] simplified for zoomed-out views, [hi] detailed; d = 1 disputed. */
@Serializable data class GeoLine(val d: Int = 0, val lo: List<Int> = emptyList(), val hi: List<Int> = emptyList())
/** A label in theater feet; r = size of its area in nautical miles. */
@Serializable data class GeoLabel(val n: String = "", val x: Double = 0.0, val y: Double = 0.0, val r: Int = 0)
@Serializable
data class GeoLayers(
    val borders: List<GeoLine> = emptyList(),
    val provinces: List<GeoLine> = emptyList(),
    val countries: List<GeoLabel> = emptyList(),
    val regions: List<GeoLabel> = emptyList(),
    val places: List<Place> = emptyList(),
)

@Serializable
data class Airport(
    val id: Int,
    val name: String,
    val fullName: String = "",
    val icao: String? = null,
    val type: String = "Airbase",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val elevationFt: Int? = null,
    val tacan: Tacan? = null,
    val freqs: Freqs? = null,
    val atc: AtcInfo? = null,
    val runways: List<Runway> = emptyList(),
)

@Serializable
data class Tacan(val channel: Int, val band: String = "X", val rangeNm: Int? = null, val station: String? = null) {
    val label get() = "$channel$band"
}

@Serializable
data class Freqs(
    val towerUhf: String? = null,
    val towerVhf: String? = null,
    val groundUhf: String? = null,
    val approachUhf: String? = null,
    val opsUhf: String? = null,
    val lsoUhf: String? = null,
    val atisVhf: String? = null,
)

@Serializable
data class AtcInfo(
    val activeRunways: Int? = null,
    val shortPattern: Boolean = false,
    val ifrMinVisM: Int? = null,
    val ifrMinCloudFt: Int? = null,
    val vfrMinVisM: Int? = null,
    val vfrMinCloudFt: Int? = null,
)

@Serializable
data class Runway(val dbRunway: Int = 0, val name: String = "", val lengthFt: Int? = null, val ends: List<RunwayEnd> = emptyList())

@Serializable
data class RunwayEnd(val designator: String = "", val headingTrue: Double = 0.0, val ils: String? = null, val pattern: Pattern? = null)

@Serializable
data class Pattern(
    val overheadSide: String? = null,
    val hasBase: Boolean = false,
    val hasLongFinal: Boolean = false,
    val final: PatternPoint? = null,
    val base: PatternPoint? = null,
    val entry: PatternPoint? = null,
    val holding: PatternPoint? = null,
    val loiter: String? = null,
    val longEntry: PatternPoint? = null,
    val longHolding: PatternPoint? = null,
)

@Serializable
data class PatternPoint(val a: Double = 0.0, val b: Double = 0.0, val altFt: Int = 0)

@Serializable
data class Navaid(
    val campId: Int = 0,
    val name: String = "",
    val objective: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val tacan: Tacan? = null,
    val nearAirport: Int? = null,
)

@Serializable
data class RadioEntry(val agency: String, val uhf1: String? = null, val vhf: String? = null, val uhf2: String? = null)

// ---------- threat guide ----------
@Serializable
data class ThreatFile(
    val source: String = "",
    val generalNotes: List<String> = emptyList(),
    val notes: Map<String, List<String>> = emptyMap(),
    val abbreviations: List<Abbreviation> = emptyList(),
    val cmEffect: List<CmEffect> = emptyList(),
    val threats: List<Threat> = emptyList(),
    val agWeapons: List<NamedStats> = emptyList(),
    val sensors: List<NamedStats> = emptyList(),
    val formations: List<Formation> = emptyList(),
)

@Serializable
data class Abbreviation(val abbr: String, val meaning: String)

@Serializable
data class CmEffect(val effect: String, val decoys: String = "")

@Serializable
data class Threat(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val side: String = "OPFOR",
    val category: String = "OTHER",
    val type: String? = null,
    val year: Int? = null,
    val rwr: Map<String, String?> = emptyMap(),
    val harmAlic: String? = null,
    val harm: String? = null,
    val stats: List<LabelValue> = emptyList(),
    val numbers: Map<String, Double?> = emptyMap(),
    val weapons: List<String> = emptyList(),
    val notes: String? = null,
    val tactics: String? = null,
    val impossibleToEvade: Boolean = false,
)

@Serializable
data class LabelValue(val label: String = "", val value: String = "")

@Serializable
data class NamedStats(val name: String, val stats: List<LabelValue> = emptyList(), val notes: String? = null)

@Serializable
data class Formation(val name: String, val description: String = "")

// ---------- HOTAS ----------
@Serializable
data class HotasFile(
    val aircraft: String = "",
    val sourceDocs: List<String> = emptyList(),
    val overview: String = "",
    val controls: List<HotasControl> = emptyList(),
)

@Serializable
data class HotasControl(val id: String, val name: String, val buttons: List<HotasButton> = emptyList())

@Serializable
data class HotasButton(
    val id: String,
    val name: String,
    val kind: String = "",
    val summary: String = "",
    val actions: List<HotasAction> = emptyList(),
    val bmsNotes: String? = null,
    val source: String? = null,
)

@Serializable
data class HotasAction(val input: String = "", val general: String = "", val byMode: List<ModeEffect> = emptyList())

@Serializable
data class ModeEffect(val mode: String = "", val effect: String = "")

// ---------- checklists ----------
@Serializable
data class Checklist(val id: String, val title: String, val document: String = "", val sections: List<ChecklistSection> = emptyList())

@Serializable
data class ChecklistSection(val id: String, val group: String = "", val title: String, val items: List<ChecklistItem> = emptyList())

@Serializable
data class ChecklistItem(
    val type: String = "step",
    val n: String? = null,
    val text: String? = null,
    val action: String? = null,
    val critical: Boolean = false,
    val note: String? = null,
    val title: String? = null,
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
)

// ---------- HARM / RWR ----------
@Serializable
data class HarmFile(
    val overview: String = "",
    val modes: List<HarmMode> = emptyList(),
    val alicCodes: List<AlicCode> = emptyList(),
    val htsClasses: List<JsonElement> = emptyList(),
    val defaultTables: List<JsonElement> = emptyList(),
    val tips: List<String> = emptyList(),
    val optionsAndSettings: List<JsonElement> = emptyList(),
)

@Serializable
data class HarmMode(val id: String = "", val name: String = "", val description: String = "", val hotas: List<InputEffect> = emptyList(), val steps: List<String> = emptyList())

@Serializable
data class InputEffect(val input: String = "", val effect: String = "")

@Serializable
data class AlicCode(val system: String = "", val category: String = "", val radar: String? = null, val alic: String = "", val symbol: String? = null, val notes: String? = null)

@Serializable
data class RwrFile(
    val overview: String = "",
    val symbols: List<RwrSymbol> = emptyList(),
    val emitters: List<RwrEmitter> = emptyList(),
    val tones: List<NameDesc> = emptyList(),
    val controls: List<NameDesc> = emptyList(),
    val preflight: List<String> = emptyList(),
)

@Serializable
data class RwrSymbol(val symbol: String = "", val meaning: String = "", val details: String? = null)

@Serializable
data class RwrEmitter(val symbol: String = "", val system: String = "", val type: String? = null, val alr56m: String? = null, val alr93: String? = null, val alr56mSearch: String? = null, val alr93Search: String? = null, val radars: String? = null, val notes: String? = null)

@Serializable
data class NameDesc(val name: String = "", val description: String = "", val panel: String? = null)

// ---------- comms ----------
@Serializable
data class CommsFile(
    val natoAlphabet: List<NatoLetter> = emptyList(),
    val brevity: List<Brevity> = emptyList(),
    val casCheckIn: LineBrief? = null,
    val nineLine: LineBrief? = null,
    val sitrep: LineBrief? = null,
    val authentication: JsonElement? = null,
    val atcProcedures: List<Procedure> = emptyList(),
    val tankerProcedures: List<Procedure> = emptyList(),
    val awacsCalls: List<CallMeaning> = emptyList(),
    val radioCallFormat: List<CallFormat> = emptyList(),
    val iffModes: List<IffMode> = emptyList(),
    val glossary: List<Term> = emptyList(),
    val casProcedure: JsonElement? = null,
    val aiCommands: List<JsonElement> = emptyList(),
)

@Serializable
data class NatoLetter(val letter: String = "", val word: String = "", val pronunciation: String? = null)

@Serializable
data class Brevity(val word: String = "", val meaning: String = "", val bmsUsage: String? = null)

@Serializable
data class LineBrief(val title: String = "", val lines: List<BriefLine> = emptyList(), val remarks: List<JsonElement> = emptyList(), val routing: JsonElement? = null)

@Serializable
data class BriefLine(val n: String? = null, val label: String = "", val description: String? = null, val example: String? = null)

@Serializable
data class Procedure(val id: String? = null, val title: String = "", val steps: List<String> = emptyList())

@Serializable
data class CallMeaning(val call: String = "", val meaning: String = "")

@Serializable
data class CallFormat(val title: String = "", val example: String? = null, val description: String? = null)

@Serializable
data class IffMode(val mode: String = "", val description: String = "")

@Serializable
data class Term(val term: String = "", val meaning: String = "")

@Serializable
data class ChartRef(val title: String = "", val file: String = "")
