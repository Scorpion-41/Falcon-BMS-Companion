package com.bmscompanion.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------- index ----------
@Serializable
data class DataIndex(
    val bmsVersion: String = "",
    /** the version of `Falcon BMS.exe` the data was read from, e.g. "4.38.1.3315"; empty in data older than 1.3.8 */
    val bmsBuild: String = "",
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
    /** ground charts for this theater's fields (data/airfields), null where none were authored */
    val airfieldSet: String? = null,
    val airportCount: Int = 0,
    val aircraftCount: Int = 0,
    /** true for theaters that ship their own terrain (KTO, Balkans, Hellas, Israel, Falklands) */
    val primary: Boolean = true,
    /** main theater whose map/airfields this theater uses */
    val mainTheater: String? = null,
    /** add-on theaters grouped under this main theater */
    val includes: List<String> = emptyList(),
    /** what Weapon Delivery Planner reads from this theater's terrain to print lat/lon; null where it finds none */
    val wdpTerrain: WdpTerrain? = null,
    /** the terrain BMS flies this theater on, from which its latitude and longitude come; null where there is none */
    val projection: TheaterProjection? = null,
    /** the theater's PPT type table (`Campaign/Ppt.ini`) in data/ppt, read with [pptTable]; null where it has none */
    val pptSet: String? = null,
    /** whether the extractor could build everything the Planner reads for this theater; null in older data */
    val planner: PlannerReadiness? = null,
)

/**
 * The terrain BMS flies a theater on, as its `NewTerrain` folder describes it (`tools/extractor/src/projection.mjs`):
 * `Theater.txt`'s size, centre and projection string, and the length of `Heightmaps/HeightMap.raw`.
 *
 * **The latitude and longitude BMS itself gives** (its ACMI recordings, its AIPs' "BMS coord") come from [sizeKm],
 * [centerLat]/[centerLon] and [heightmapBytes] alone, as Weapon Delivery Planner's grid computes them
 * (`WdpCoords.coordData`, D26): metres are feet ÷ 3.28084, the false origin is the centre's forward projection less
 * half the theater, and a point's northing is one heightmap sample (31.25 m) more.
 *
 * **The projection string** (`+proj=tmerc +lon_0 +k +x_0 +y_0`, WGS84, metres, [ftPerM] 3.27998) is what the maps,
 * towns and ground charts were drawn on. Theater feet are x north and y east:
 *
 *     easting  = y ft / ftPerM - x0      northing = x ft / ftPerM - y0      (metres about lon0, scale k0)
 *
 * It is not where BMS says a point is: on every theater checked it lies 140-220 m from BMS's own figure on average (up to 290 m), never within 2 m. The Planner
 * prints with it only where BMS's grid is not established (the Falklands' 2,048 km). [proj] is the file's own
 * string, kept so a reader that meets a parameter this class does not carry can refuse rather than print a wrong
 * position.
 */
@Serializable
data class TheaterProjection(
    val type: String = "tmerc",
    val ellps: String? = "WGS84",
    val lat0: Double = 0.0,
    val lon0: Double = 0.0,
    val k0: Double = 0.9996,
    val x0: Double = 0.0,
    val y0: Double = 0.0,
    /** feet per metre the maps lay the projection string out at: 3.27998 (a campaign kilometre); BMS's own lat/lon uses 3.28084 */
    val ftPerM: Double = 3.27998,
    val sizeKm: Double = 1024.0,
    val centerLat: Double? = null,
    val centerLon: Double? = null,
    val proj: String = "",
    /** the length of the terrain's `NewTerrain/Heightmaps/HeightMap.raw` (samples a side = sqrt(bytes / 2)); null in older data */
    val heightmapBytes: Long? = null,
)

/**
 * What the extractor could build of what the Planner reads for one theater. [missing] names each part it could not
 * ("airports", "radio", "airfields", "map", "projection", "ppt"); `tools/extractor/src/plannercheck.mjs` fails
 * while any theater's list is not empty, which is how a new theater is known to need no code change.
 */
@Serializable
data class PlannerReadiness(val ok: Boolean = false, val missing: List<String> = emptyList())

/**
 * One row of a theater's `Campaign/Ppt.ini`, the PPT types BMS's DTC offers: `SA2 164055.12 SA-2`,
 * `AWC 0.1 AWACS`. [radiusFt] is the ring BMS draws when the cartridge gives none (0.1 = a point, not a threat).
 * Rows come in the file's order, repeats and "---" separator rows included, because that is the list the pilot
 * sees in BMS.
 */
@Serializable
data class PptType(val code: String, val radiusFt: Double = 0.0, val name: String = "")

/** A theater's PPT type table ([Theater.pptSet]); empty when it has none. */
suspend fun Repo.pptTable(set: String?): List<PptType> {
    if (set.isNullOrBlank()) return emptyList()
    val text = text("data/ppt/$set.json") ?: return emptyList()
    return runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PptType.serializer()), text) }.getOrDefault(emptyList())
}

/**
 * BMS's own defaults for the cartridge pages, `User/Config/{EWS,HARM,IFF,MFD}_Def.ini`, as the PC reads them
 * (`GET /api/cfg/defaults`, read only: these files belong to BMS and are never written). [available] is false when
 * the PC has no BMS install; a file that is not there is left out of [files] and named in [error].
 */
@Serializable
data class BmsDefaults(
    val available: Boolean = false,
    val files: List<BmsDefaultsFile> = emptyList(),
    val error: String? = null,
) {
    companion object {
        /** Asks the PC; null when it cannot be reached or answers something else. */
        suspend fun fetch(): BmsDefaults? {
            val bytes = com.bmscompanion.app.data.mission.MissionLink.fetchBytes("/api/cfg/defaults") ?: return null
            return runCatching { Repo.json.decodeFromString(serializer(), bytes.decodeToString()) }.getOrNull()
        }
    }
}

/**
 * One `*_Def.ini`: its name ("HARM_Def.ini"), its sections in the file's order ("MFD", "Bullseye": MFD_Def.ini
 * holds two) and every key with the section it sits in, also in the file's order.
 */
@Serializable
data class BmsDefaultsFile(
    val name: String,
    val sections: List<String> = emptyList(),
    val values: List<BmsDefaultsValue> = emptyList(),
) {
    /** The value of [key] (case as BMS ignores it), in [section] when one is named, else in any section. */
    fun value(key: String, section: String? = null): String? = values.firstOrNull {
        it.key.equals(key, ignoreCase = true) && (section == null || it.section.equals(section, ignoreCase = true))
    }?.value
}

@Serializable
data class BmsDefaultsValue(val section: String, val key: String, val value: String)

/**
 * The raw figures Weapon Delivery Planner sets its map projection up from (`fclsMain.InitNewTerrain` and
 * `InitTransverseMercator`), as the files in the theater's `NewTerrain` folder give them; the arithmetic is the
 * port's (`data/wdp/WdpCoords.kt`). Written by `tools/extractor/src/wdpterrain.mjs`.
 */
@Serializable
data class WdpTerrain(
    /** the length of `Heightmaps/HeightMap.raw` (WDP reads nothing else of it); null when there is none */
    val heightmapBytes: Long? = null,
    /** whether `Theater.txt` is there at all */
    val theaterTxt: Boolean = false,
    /** `Theater.txt`'s "Theater size in KM", "Center latitude" and "Center longitude"; null where it has none */
    val sizeKm: Double? = null,
    val centerLat: Double? = null,
    val centerLon: Double? = null,
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

/**
 * BMS's empty class-table slots ("*free" in the F-16 family, "--Free Slot--" in the Eurofighter's, an empty name,
 * "none"). The extractor leaves them out (`isPlaceholderName` in `tools/extractor/src/catalog.mjs`); this is the app's
 * second guard, applied by each `Repo` to the aircraft, the stores (and their `carriedBy` keys), the encyclopedia and
 * the threats. Whole names only: "F-5A Freedom Fighter" is real.
 */
object Placeholders {
    private val edges = Regex("^[-*_.\\s]+|[-*_.\\s]+$")
    private val whole = Regex("^(free([\\s-]*slot)?|none|empty|unused|n/?a|aircraft)$", RegexOption.IGNORE_CASE)

    fun isPlaceholder(name: String?): Boolean {
        val n = (name ?: "").replace(edges, "")
        return n.isEmpty() || whole.matches(n) || n.contains("placeholder", ignoreCase = true)
    }

    fun aircraft(list: List<Aircraft>): List<Aircraft> = list.filterNot { isPlaceholder(it.name) }

    fun weapons(list: List<Weapon>): List<Weapon> = list.filterNot { isPlaceholder(it.name) }.map { w ->
        if (w.carriedBy.none { isPlaceholder(it) }) w else w.copy(carriedBy = w.carriedBy.filterNot { isPlaceholder(it) })
    }

    fun <T> named(list: List<T>, name: (T) -> String): List<T> = list.filterNot { isPlaceholder(name(it)) }
}

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
    /** The name of the TacRef entry that is this threat (its picture and data), reviewed in tools/curated/threat_pictures.json; null when BMS has none. */
    val tacref: String? = null,
    /** A photograph of its own (tools/curated/photos.json, "threat:<id>"), for a threat with no TacRef entry; else null. */
    val pic: String? = null,
)

/**
 * A photograph in the Reference section that is not BMS's own art: a Wikimedia Commons file, public domain, CC0, CC BY
 * or CC BY-SA (`tools/extractor/src/photos.mjs`, `data/credits/photos.json`). [id] is the picture's id (`ph-…`).
 */
@Serializable
data class PhotoCredit(
    val id: String,
    val subject: String = "",
    val author: String = "",
    val licence: String = "",
    val licenceUrl: String? = null,
    val source: String = "",
)

@Serializable
data class PhotoCredits(val source: String = "", val photos: List<PhotoCredit> = emptyList())

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
    /**
     * A cockpit light (type "light"): "warning", "caution" or "indicator" — what colour its lamp is drawn in. The
     * light's legend is [title], what it means [text], what to do [action], where the paper checklist has it [note].
     */
    val panel: String? = null,
    /** Sections of the same checklist holding the full procedure, opened with a tap (one per engine where they differ). */
    val refs: List<String> = emptyList(),
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
data class ChartRef(val title: String = "", val file: String = "", val pages: List<String> = emptyList())

/**
 * The catalogue of Falcon BMS config options: what exists, what it does, and what it defaults to.
 *
 * Built by `tools/extractor/src/cfgcatalog.mjs` out of the stock `User/Config/Falcon BMS.cfg` the version ships —
 * that file lists every option at its default and most lines carry BMS's own explanation — so a new BMS version is
 * a re-run rather than an editing job. `tools/curated/cfgnotes.json` adds only what BMS does not say: the grouping,
 * a description for the few silent lines, and the named choices for the ones that take one of a set of values.
 */
@Serializable
data class CfgCatalog(val version: String = "", val options: List<CfgOption> = emptyList())

/**
 * One option. [k] is the key as it appears after `set`, [d] BMS's own default, [g] the group it is filed under,
 * [t] what it does and [vr] 1 for the ones that only matter in a headset.
 *
 * [kind] comes from BMS's own naming — `g_b…` a toggle, `g_n…` a whole number, `g_f…` a decimal, `g_s…` text (or a
 * colour where the default is a hex string) — except where [c] gives named choices.
 */
@Serializable
data class CfgOption(
    val k: String = "",
    val d: String = "",
    val kind: String = "text",
    val g: String = "Other",
    val t: String = "",
    val vr: Int = 0,
    val c: List<CfgChoice> = emptyList(),
)

/** One named value of an option that takes a set of them: [v] as written in the file, [t] as read by a person. */
@Serializable data class CfgChoice(val v: String = "", val t: String = "")
