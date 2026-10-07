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
    /** the arresting cables laid across it, in order from [ends] 0 (see [AfCable]) */
    val cables: List<AfCable> = emptyList(),
) {
    val name: String get() = ends.joinToString("/") { it.designator }

    /**
     * How far each cable lies from the end named [designator], nearest first, in feet along the centre line. A
     * negative distance is a barrier in the overrun before that end.
     */
    fun cablesFrom(designator: String): List<Int> {
        val i = ends.indexOfFirst { it.designator == designator }
        if (i < 0) return emptyList()
        return cables.mapNotNull { it.d.getOrNull(i) }.sorted()
    }
}

/**
 * An arresting cable: where it crosses the runway's centre line ([e], [n], field feet), and [d] how far that is from
 * each end of the runway, in the order of [AfRunway.ends].
 *
 * From the "Arrestor System" objects BMS lays across the runway in the field's own feature list — the cable the sim's
 * hook catches — so the position is exact. BMS does not say which kind of gear it is (BAK-12, BAK-14, MA-1A …).
 */
@Serializable
data class AfCable(val e: Double = 0.0, val n: Double = 0.0, val d: List<Int> = emptyList())

/**
 * Distances to cables in the words a runway card uses: "1,500 ft", "at the end" (within 100 ft of it), "300 ft before"
 * (a barrier in the overrun).
 */
fun cableDistances(feet: List<Int>): String = feet.joinToString(" · ") { d ->
    when {
        d < -100 -> "${thousands(-d)} ft before"
        d <= 100 -> "at the end"
        else -> "${thousands(d)} ft"
    }
}

private fun thousands(v: Int): String {
    val s = v.toString()
    val out = StringBuilder()
    s.forEachIndexed { i, ch -> if (i > 0 && (s.length - i) % 3 == 0) out.append(','); out.append(ch) }
    return out.toString()
}

@Serializable
data class AfEnd(val designator: String = "", val course: Double = 0.0, val at: AfPt = AfPt())

/** A taxi point. [t] is BMS's own point type: 2 lines up on the runway, 3 taxiway, 15 on a runway, 21 hold short. */
@Serializable
data class AfNode(val e: Double = 0.0, val n: Double = 0.0, val t: Int = 0, val l: String? = null)

/**
 * A ramp spot. [n] is its number on this route, [k] its point, [l] the lane point it stands off.
 *
 * [n] is BMS's own number, the one Ground says ("park zero four"): the count of this network's parking points in
 * storage order, from 0, read from the sim's code. After landing Ground counts in the **taxi-in** network — the
 * route of the reciprocal end ([taxiInRoute]) — so a page showing the way in shows that route's numbers.
 *
 * Three things about the stand itself, and they are three different questions.
 *
 * [s] is BMS's own point type: 11 is a stand for a **small** aircraft, 12 one with no size limit — **large**. Those
 * are exactly the encircled and the boxed numbers on the parking charts the theaters ship, which agree with the point
 * types on 101 of the 103 spots the four transcribed charts carry (tools/extractor/src/apcverify.mjs checks it).
 *
 * [q] is the field's **alert cell** — the red numbers on those same charts, where jets sit cocked for a scramble. BMS
 * marks it by giving those points ParkingPointGroup -1 (Ground never gives one after landing); all 8 of the Q spots
 * printed across the four charts carry it and no other spot does.
 *
 * [c] is whether the stand has a **roof**, which the point data does not say at all: the extractor sets it where the
 * field stands a hardened shelter or a hangar over the spot. Size and shelter are unrelated — a small stand is
 * usually open ramp — and reading the point type as "sheltered" once made 302 of Osan's 334 spots hardened.
 */
@Serializable
data class AfSpot(
    val n: Int = 0,
    val k: Int = 0,
    val l: Int = 0,
    val w: Int = 0,
    val s: Int = 0,
    val c: Int = 0,
    val q: Int = 0,
) {
    /** under a hardened shelter or a hangar, rather than out on open ramp */
    val covered: Boolean get() = c != 0
    /** a stand BMS restricts to a small aircraft: an encircled number on BMS's own parking chart */
    val small: Boolean get() = s != 0
    /** a stand with no size limit: a boxed number on BMS's own parking chart */
    val large: Boolean get() = s == 0
    /** the alert cell, kept ready for a scramble: the red numbers on BMS's own parking chart */
    val alert: Boolean get() = q != 0

    /** The stand in the words a parking chart's key uses. */
    val sizeWord: String get() = if (small) "small aircraft" else "no size limit"

    /** The number as BMS's Ground controller says it: two digits, "04", three from 100. */
    val label: String get() = spotLabel(n)
}

/**
 * A spot number as BMS's Ground controller says it ("park zero four"): two digits with a leading zero, three from 100.
 * [n] is BMS's own count of the network's parking points in storage order, from 0 (see numberParking in
 * tools/extractor/src/airfields.mjs).
 */
fun spotLabel(n: Int): String = n.toString().padStart(2, '0')

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
 * Position and heading come straight from the field's own data. The footprint does not, except for a control tower:
 * BMS keeps it inside the 3D model, so the extractor gives each kind a representative size (see FEATURE_KINDS in
 * airfields.mjs).
 *
 * A **control tower** ([k] `tower`) is drawn as the shape it has from above, because a pilot finds his way round a
 * field by it: [p] is its outline, rings of east,north pairs in field feet, placed at its own position and heading;
 * [top] the part that reaches the top of it (the shaft and the cab, where it stands over a building); [ht] its height
 * in feet. [src] says where the outline came from — `model`: the tower's own BMS 3D model, exact; `box`: the bounding
 * box the model's Parent.dat states, the right size and heading but a rectangle (tools/extractor/src/footprint.mjs).
 */
@Serializable
data class AfFeature(
    val k: String = "",
    val e: Double = 0.0,
    val n: Double = 0.0,
    val h: Double = 0.0,
    val w: Double = 60.0,
    val l: Double = 60.0,
    val p: List<List<Int>> = emptyList(),
    val top: List<List<Int>> = emptyList(),
    val ht: Int? = null,
    val src: String? = null,
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
 * A ship, when the field is one: its flight deck, drawn from the ship's own BMS 3D model.
 *
 * BMS gives a carrier objective no feature list, and its "runways" are the approach path, a catapult and sometimes a
 * rectangle of no width. But the ship itself has a model like any vehicle, and the objective's deck points are in
 * that model's frame — east is the ship's starboard, north its bow, the origin the model's own. So the deck is laid
 * down exactly there, with no fitting: the outline with every step and sponson, what stands below the deck edge,
 * the paint, the lifts and the islands, each measured from the model or placed from a published figure
 * (`tools/curated/carriers.json`, `tools/extractor/src/ships.mjs`). Every spot BMS parks an aircraft on then falls on
 * the deck, and the jet, put into the ship's frame, lands where it really is ([com.bmscompanion.app.data.airfield.deckPosition]).
 *
 * [cls] names the ship as drawn. [marks] is the detailed deck; a chart written before there was one has only the
 * older, simpler fields ([hull], [strip], [stripLine], [cats], [ski], [islands]), which are still filled in.
 *
 * There is no ramp here. A carrier steams into wind, so the runway BMS names and the numbers it gives its deck
 * spots turn with the ship — a chart that drew them would be drawing a number that is right for one minute.
 *
 * Rings are closed east,north pairs in field feet. See `tools/extractor/src/ships.mjs`.
 */
@Serializable
data class AfShip(
    val cls: String = "",
    /** the flight deck's outline, every step and sponson of it */
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
    /** which BMS model the deck was measured from */
    val model: String = "",
    /** the deck's paint: -1 darker than usual (the Liaoning), 1 lighter (the Kuznetsov), 0 as it comes */
    val tone: Int = 0,
    /** the ski jump's published angle, where there is one */
    val skiDeg: Double? = null,
    /** the whole deck, in drawing order: see [AfDeckMark] */
    val marks: List<AfDeckMark> = emptyList(),
)

/**
 * One thing on a carrier's deck. [p] is east,north pairs in field feet; for a circle, a spot or a number it is the
 * middle, with [r] the radius or the height in feet and [t] the text.
 *
 * What [k] draws:
 * - `edge` what stands below the deck edge and is seen from above — sponsons, galleries, catwalks — drawn lighter,
 *   under the deck
 * - `lane` the landing area, a shade darker than the rest of the deck
 * - `lift` a lift (elevator), outlined in yellow; `lift?` one whose place is estimated, outlined dashed
 * - `hatch` a flush hatch, outlined only
 * - `ski` the ski jump, lighter, with `step` lines across it where it rises
 * - `line` a white painted line, `ladder` one with the boxes of a landing-area edge, `dash` a dashed white centre
 *   line, `ydash` a dashed yellow one, `foul` a red and white dashed foul line, `foulk` a red and black one,
 *   `faint` an old, faded line, `lights` a row of deck lights
 * - `wire` an arresting wire, `cat` a catapult track, `jbd` a jet-blast deflector
 * - `spot` a painted landing or take-off spot (a yellow ring, numbered when [t] is set), `tee` a spot with its
 *   lineup line
 * - `text` the hull number, painted on the deck
 * - `island` an island, standing on the deck; `part` a structure on top of it
 */
@Serializable
data class AfDeckMark(
    val k: String = "",
    val p: List<Int> = emptyList(),
    val r: Int = 0,
    val t: String? = null,
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
