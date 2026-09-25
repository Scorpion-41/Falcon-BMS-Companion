package com.bmscompanion.app.data.airfield

import kotlinx.serialization.Serializable

/**
 * A field's ground chart, as Falcon BMS itself authored it.
 *
 * The extractor (`tools/extractor/src/airfields.mjs`) reads the objective's own point data — the runway rectangles,
 * one taxi network per runway end, every ramp spot and the buildings worth drawing — and writes one file per field.
 * The sim's own ground AI taxis on exactly these points, so a route drawn from them is the way BMS drives it.
 *
 * Positions are **feet about the field's own origin**, [e] east and [n] north. The origin itself is at ([Airfield.n],
 * [Airfield.e]) in theater feet, the same frame the map and the jet's own position use, so putting the aircraft on
 * the chart is a subtraction: `localEast = live.y - field.e`, `localNorth = live.x - field.n`.
 */
@Serializable
data class Airfield(
    val id: Int = 0,
    val name: String = "",
    val icao: String? = null,
    /** the field origin in theater feet: north */
    val n: Double = 0.0,
    /** the field origin in theater feet: east */
    val e: Double = 0.0,
    val elevationFt: Int? = null,
    val runways: List<AfRunway> = emptyList(),
    /** one per runway end; BMS builds a separate network, and a separate order of ramp spots, for each */
    val routes: List<AfRoute> = emptyList(),
    val features: List<AfFeature> = emptyList(),
    /** the taxiway signs BMS itself puts on the field */
    val signs: List<AfSign> = emptyList(),
    /** the asphalt itself, out of the field's 3D models; empty where BMS ships none */
    val paved: List<AfRing> = emptyList(),
    /** set when the field is a carrier rather than a field */
    val ship: AfShip? = null,
) {
    val hasTaxiways: Boolean get() = routes.any { it.parking.isNotEmpty() }
}

@Serializable
data class AfPt(val e: Double = 0.0, val n: Double = 0.0)

@Serializable
data class AfRunway(
    val rwy: Int = 0,
    val lengthFt: Int = 0,
    val widthFt: Int = 0,
    /** four corners, in order around the strip */
    val corners: List<AfPt> = emptyList(),
    val ends: List<AfEnd> = emptyList(),
    val crossings: List<AfPt> = emptyList(),
) {
    val name: String get() = ends.joinToString("/") { it.designator }
}

@Serializable
data class AfEnd(val designator: String = "", val course: Double = 0.0, val at: AfPt = AfPt())

/** A taxi point. [t] is BMS's own point type: 2 lines up on the runway, 3 taxiway, 15 on a runway, 21 hold short. */
@Serializable
data class AfNode(val e: Double = 0.0, val n: Double = 0.0, val t: Int = 0, val l: String? = null)

/**
 * A ramp spot. [n] is its number on this route, [k] its point, [l] the lane point it stands off.
 *
 * [s] is BMS's own point type — 11, meaning the spot records what size of aircraft fits. That is true of most
 * spots at most fields and says nothing about shelter, so it is [c] that says whether the spot has a roof: the
 * extractor sets it where the field stands a hardened shelter or a hangar over the spot.
 */
@Serializable
data class AfSpot(val n: Int = 0, val k: Int = 0, val l: Int = 0, val w: Int = 0, val s: Int = 0, val c: Int = 0) {
    /** under a hardened shelter or a hangar, rather than out on open ramp */
    val covered: Boolean get() = c != 0
    /** BMS records a maximum aircraft size for this spot */
    val sized: Boolean get() = s != 0
}

@Serializable
data class AfRoute(
    val designator: String = "",
    val course: Double = 0.0,
    val rwy: Int = 0,
    /** the point where this route meets its runway: where you line up, and what a departure taxis to */
    val start: Int? = null,
    val nodes: List<AfNode> = emptyList(),
    /** edges as pairs, flattened */
    val ed: List<Int> = emptyList(),
    val parking: List<AfSpot> = emptyList(),
    val spurs: List<Int> = emptyList(),
)

/**
 * One object the sim places at the field: a shelter, a hangar, a wall, a patch of apron. [k] is what it is, [h] its
 * heading, and [w] by [l] how large to draw it in plan — across by along, in feet.
 *
 * Position and heading come straight from the field's own data. The footprint does not: BMS keeps that inside the
 * 3D model, so the extractor gives each kind a representative size (see FEATURE_KINDS in airfields.mjs).
 */
@Serializable
data class AfFeature(
    val k: String = "",
    val e: Double = 0.0,
    val n: Double = 0.0,
    val h: Double = 0.0,
    val w: Double = 60.0,
    val l: Double = 60.0,
)

/**
 * The outline of the field's asphalt: a closed ring of east,north pairs in the field's own feet.
 *
 * These come from the triangles in BMS's own 3D models, so the edges are the real ones — straight where the
 * pavement is straight, square at the corners. They cover what no taxi route describes: the aprons, the
 * dispersals, the turnarounds that join the runway ends, every yard the pilot sees out of the canopy.
 *
 * Rings are filled **even-odd**, together, so a ring lying inside another reads as a hole in it.
 */
typealias AfRing = List<Int>

/**
 * A ship, when the field is one: its flight deck, drawn from the real ship rather than traced from BMS.
 *
 * BMS gives a carrier no feature list, no model and no outline; its deck points run far past the bow because they
 * carry the approach path, and its "runways" include that path, a catapult and, on some ships, a rectangle of no
 * width. So all of it is **built** from the published dimensions of the class, laid on the axis and centre BMS's
 * own deck gives. [cls] names the class it was drawn at.
 *
 * There is no ramp here. A carrier steams into wind, so the runway BMS names and the numbers it gives its deck
 * spots turn with the ship — a chart that drew them would be drawing a number that is right for one minute.
 *
 * Rings are closed east,north pairs in field feet. See `tools/extractor/src/ships.mjs`.
 */
@Serializable
data class AfShip(
    val cls: String = "",
    /** the flight deck: narrowing to the bow, with the angled-deck sponson out to port */
    val hull: List<Int> = emptyList(),
    /** the landing area, angled off the ship's axis on a CATOBAR deck */
    val strip: List<Int> = emptyList(),
    /** its centre line, as two points, for the dashes down the middle of it */
    val stripLine: List<Int> = emptyList(),
    /** the catapults, each a pair of points */
    val cats: List<List<Int>> = emptyList(),
    /** the ski jump at the bow, where the class has one */
    val ski: List<Int> = emptyList(),
    val islands: List<List<Int>> = emptyList(),
)

/** Where BMS signs a taxiway, and with which letter. */
@Serializable
data class AfSign(val l: String = "", val e: Double = 0.0, val n: Double = 0.0)

object AfType {
    const val RUNWAY_END = 1
    const val TAXI_START = 2
    const val TAXI = 3
    const val ON_RUNWAY = 15
    const val HOLD_SHORT = 21
}
