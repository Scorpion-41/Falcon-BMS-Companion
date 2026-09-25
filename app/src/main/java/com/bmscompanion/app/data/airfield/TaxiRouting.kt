package com.bmscompanion.app.data.airfield

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Taxi routing over a field's authored network, and the clearance that comes out of it.
 *
 * BMS never reads a taxi route out to you: its ground controller can say "taxi", "hold short" and "taxi in
 * sequence", and the words "taxiway" and "Alpha" exist in its vocabulary, but no comm sentence ever puts them
 * together. The *route* here is the sim's own — these are the points its ground AI drives — while the wording is
 * ours, written the way a ground controller would give it.
 */
class TaxiNet(val field: Airfield, val route: AfRoute) {

    private val neighbours: Map<Int, MutableList<Int>> = buildMap {
        var i = 0
        while (i + 1 < route.ed.size) {
            val a = route.ed[i]
            val b = route.ed[i + 1]
            getOrPut(a) { mutableListOf() }.add(b)
            getOrPut(b) { mutableListOf() }.add(a)
            i += 2
        }
    }

    private fun gap(a: Int, b: Int): Double {
        val p = route.nodes[a]
        val q = route.nodes[b]
        return hypot(p.e - q.e, p.n - q.n)
    }

    /**
     * The shortest way to roll from one point to another. A field has a couple of hundred points, so the plain
     * O(n²) search is instant and needs no priority queue (which the browser build has no stand-in for).
     */
    fun path(from: Int, to: Int): TaxiPath? {
        val count = route.nodes.size
        if (from !in 0 until count || to !in 0 until count) return null
        val best = DoubleArray(count) { Double.MAX_VALUE }
        val prev = IntArray(count) { -1 }
        val done = BooleanArray(count)
        best[from] = 0.0
        while (true) {
            var at = -1
            var cheapest = Double.MAX_VALUE
            for (k in 0 until count) if (!done[k] && best[k] < cheapest) { cheapest = best[k]; at = k }
            if (at < 0 || at == to) break
            done[at] = true
            for (v in neighbours[at].orEmpty()) {
                val step = cheapest + gap(at, v)
                if (step < best[v]) { best[v] = step; prev[v] = at }
            }
        }
        if (best[to] == Double.MAX_VALUE) return null
        val seq = ArrayList<Int>()
        var at = to
        while (at != -1) { seq.add(at); at = prev[at] }
        seq.reverse()
        return TaxiPath(seq, best[to])
    }

    /** The ramp spot nearest a position in field feet, and how far off it is. */
    fun nearestSpot(e: Double, n: Double): Pair<AfSpot, Double>? {
        var best: AfSpot? = null
        var away = Double.MAX_VALUE
        for (spot in route.parking) {
            val p = route.nodes.getOrNull(spot.k) ?: continue
            val d = hypot(p.e - e, p.n - n)
            if (d < away) { away = d; best = spot }
        }
        return best?.let { it to away }
    }

    /** The point you would be rolling over, for a jet already moving. */
    fun nearestNode(e: Double, n: Double): Int? {
        var best = -1
        var away = Double.MAX_VALUE
        route.nodes.forEachIndexed { k, p ->
            if (p.t == AfType.RUNWAY_END) return@forEachIndexed
            val d = hypot(p.e - e, p.n - n)
            if (d < away) { away = d; best = k }
        }
        return best.takeIf { it >= 0 }
    }

    fun spot(number: Int): AfSpot? = route.parking.firstOrNull { it.n == number }
}

data class TaxiPath(val nodes: List<Int>, val ft: Double)

/** One run along a single taxiway, which is what earns one line of a clearance. */
private data class Leg(val name: String?, val from: Int, val to: Int, val ft: Double, var enter: Double?, var leave: Double?)

enum class TaxiChipKind { TAXI, TURN, WAY, END }

data class TaxiChip(val kind: TaxiChipKind, val text: String)

data class TaxiStep(
    val text: String,
    /** the part of the text to set apart: a taxiway letter, a runway, a spot number */
    val emphasis: String? = null,
    val ft: Double? = null,
    /** the stretch of the path this step covers, for lighting it on the chart */
    val fromNode: Int? = null,
    val atNode: Int = 0,
)

data class TaxiClearance(
    val line: String,
    val steps: List<TaxiStep>,
    val chips: List<TaxiChip>,
    val ft: Double,
    val taxiways: List<String>,
)

private const val FT_PER_NM = 6076.12

private fun bearing(a: AfNode, b: AfNode): Double =
    ((atan2(b.e - a.e, b.n - a.n) * 180.0 / kotlin.math.PI) + 360.0) % 360.0

private fun wrap180(d: Double): Double = ((d % 360.0) + 540.0) % 360.0 - 180.0

/**
 * The turn taken between two legs. Measured over 250 ft either side of the junction rather than between the two
 * points at it, so a taxiway that curves gently reads as one instruction instead of a dozen little turns.
 */
private fun turnWord(from: Double?, to: Double?): Pair<String, String> {
    if (from == null || to == null) return "Continue" to "AHEAD"
    val d = wrap180(to - from)
    return when {
        abs(d) < 22 -> "Continue" to "AHEAD"
        abs(d) > 150 -> (if (d > 0) "Turn back right" else "Turn back left") to (if (d > 0) "BACK R" else "BACK L")
        abs(d) < 60 -> (if (d > 0) "Bear right" else "Bear left") to (if (d > 0) "RIGHT" else "LEFT")
        else -> (if (d > 0) "Turn right" else "Turn left") to (if (d > 0) "RIGHT" else "LEFT")
    }
}

/** What an aircraft rolls along; anything else on a route is a parking spot hanging off it. */
private val WAY = setOf(AfType.TAXI_START, AfType.TAXI, AfType.ON_RUNWAY, AfType.HOLD_SHORT)

/**
 * Breaks a path into the legs a clearance is read from.
 *
 * A leg ends where the taxiway letter changes, or where the lane turns **at a junction**. That distinction is the
 * whole trick: a pilot needs telling which way to go where the pavement offers a choice, and nowhere else. BMS's
 * lanes are sampled every 150 ft or so and wander, and calling every bend produced a dozen lines of "bear right"
 * down one straight taxiway. A bend with no turning off it is just pavement — it leads you round by itself.
 *
 * The turn itself is measured over 500 ft either side of the junction rather than between the two points at it,
 * because the letter usually changes a little before or after the corner it belongs to.
 */
private fun legsOf(route: AfRoute, seq: List<Int>): List<Leg> {
    val nodes = route.nodes
    val label = arrayOfNulls<String>(seq.size)
    var carry: String? = null
    for (i in seq.indices) {
        nodes[seq[i]].l?.let { carry = it }
        label[i] = carry
    }
    for (i in seq.indices.reversed()) if (label[i] == null) label[i] = if (i + 1 < seq.size) label[i + 1] else null

    fun step(i: Int, j: Int) = hypot(nodes[seq[j]].e - nodes[seq[i]].e, nodes[seq[j]].n - nodes[seq[i]].n)

    fun before(i: Int, want: Double): Double? {
        var gone = 0.0
        var k = i
        while (k > 0 && gone < want) { gone += step(k - 1, k); k-- }
        return if (k == i) null else bearing(nodes[seq[k]], nodes[seq[i]])
    }
    fun after(i: Int, want: Double): Double? {
        var gone = 0.0
        var k = i
        while (k + 1 < seq.size && gone < want) { gone += step(k, k + 1); k++ }
        return if (k == i) null else bearing(nodes[seq[i]], nodes[seq[k]])
    }
    fun turnAt(i: Int, want: Double): Double? {
        val a = before(i, want) ?: return null
        val b = after(i, want) ?: return null
        return wrap180(b - a)
    }

    // How many taxiways meet at each point: three or more is a junction, where a pilot has a choice to make.
    val ways = IntArray(nodes.size)
    var e = 0
    while (e + 1 < route.ed.size) {
        val x = route.ed[e]
        val y = route.ed[e + 1]
        e += 2
        if (nodes.getOrNull(x)?.t in WAY && nodes.getOrNull(y)?.t in WAY) { ways[x]++; ways[y]++ }
    }

    val corners = HashSet<Int>()
    for (i in 1 until seq.size - 1) {
        if (ways.getOrNull(seq[i]) ?: 0 < 3) continue
        val d = turnAt(i, 400.0) ?: continue
        if (abs(d) > 45.0) corners.add(i)
    }

    val raw = ArrayList<Leg>()
    var cur = Leg(label[0], 0, 0, 0.0, null, null)
    for (i in 1 until seq.size) {
        if (label[i] != cur.name || (i - 1) in corners) {
            raw.add(cur)
            cur = Leg(label[i], i - 1, i, step(i - 1, i), null, null)
        } else {
            cur = cur.copy(to = i, ft = cur.ft + step(i - 1, i))
        }
    }
    raw.add(cur)

    for (leg in raw) {
        val straight = if (leg.to > leg.from) bearing(nodes[seq[leg.from]], nodes[seq[leg.to]]) else null
        leg.enter = after(leg.from, 500.0) ?: straight
        leg.leave = before(leg.to, 500.0) ?: straight
    }

    // Fold away what is not worth saying: a few feet of junction, and a leg that carries on the same way it came.
    val merged = ArrayList<Leg>()
    for (leg in raw) {
        val last = merged.lastOrNull()
        val turn = if (last?.leave != null && leg.enter != null) abs(wrap180(leg.enter!! - last.leave!!)) else 0.0
        if (last != null && ((leg.name == last.name && turn < 45.0) || (leg.ft < 250.0 && turn < 45.0))) {
            merged[merged.size - 1] = last.copy(to = leg.to, ft = last.ft + leg.ft).also { it.enter = last.enter; it.leave = leg.leave }
        } else merged.add(leg)
    }
    // The turn onto each leg is measured across its own junction, so a letter that changes just before or after the
    // corner still reads as the turn the pilot makes.
    for (k in 1 until merged.size) merged[k].enter = turnAt(merged[k].from, 500.0)?.let { d -> (merged[k - 1].leave ?: 0.0) + d } ?: merged[k].enter
    return merged
}

private fun feet(ft: Double): String {
    val n = ft.roundToInt()
    return if (n >= 1000) "${n / 1000}," + (n % 1000).toString().padStart(3, '0') + " ft" else "$n ft"
}

/**
 * Turns a path into a clearance a pilot can follow: one line to read, the turn-by-turn under it, and the short
 * chips that say the same thing at a glance — the way a game tells you "right, then B, then left, then D".
 */
fun clearanceFor(net: TaxiNet, path: TaxiPath, outbound: Boolean, spot: AfSpot?): TaxiClearance {
    val route = net.route
    val seq = path.nodes
    val legs = legsOf(route, seq)
    val runway = route.designator
    val steps = ArrayList<TaxiStep>()
    val chips = ArrayList<TaxiChip>()
    val named = ArrayList<String>()
    for (leg in legs) leg.name?.let { if (named.lastOrNull() != it) named.add(it) }

    if (outbound) {
        val where = if (spot?.covered == true) "hardened shelter" else "open ramp"
        steps.add(TaxiStep("Start on spot ${spot?.n ?: "?"} — $where", "${spot?.n ?: ""}", null, null, seq.first()))
    } else {
        val at = legs.firstOrNull()?.name
        steps.add(TaxiStep("Vacate runway $runway" + (at?.let { " at $it" } ?: ""), at ?: runway, null, null, seq.first()))
    }
    chips.add(TaxiChip(TaxiChipKind.TAXI, "TAXI"))

    legs.forEachIndexed { i, leg ->
        val turn = if (i == 0) "Taxi" to "" else turnWord(legs[i - 1].leave, leg.enter)
        val same = i > 0 && leg.name != null && leg.name == legs[i - 1].name
        val onto = when {
            same -> ", still on ${leg.name}"
            leg.name != null -> " onto ${leg.name}"
            else -> " along the ramp lane"
        }
        steps.add(TaxiStep(turn.first + onto, leg.name, leg.ft, seq[leg.from], seq[leg.to]))
        if (i > 0 && turn.second.isNotEmpty()) chips.add(TaxiChip(TaxiChipKind.TURN, turn.second))
        if (!same) leg.name?.let { chips.add(TaxiChip(TaxiChipKind.WAY, it)) }
    }

    if (outbound) {
        steps.add(TaxiStep("Hold short of runway $runway", runway, null, null, seq.last()))
        chips.add(TaxiChip(TaxiChipKind.END, "HOLD $runway"))
    } else {
        val nose = if (spot?.covered == true) ", nose into the shelter" else ""
        steps.add(TaxiStep("Park on spot ${spot?.n ?: "?"}$nose", "${spot?.n ?: ""}", null, null, seq.last()))
        chips.add(TaxiChip(TaxiChipKind.END, "SPOT ${spot?.n ?: "?"}"))
    }

    val line = if (outbound) {
        "Taxi to runway $runway" + (if (named.isEmpty()) " along the ramp" else " via " + named.joinToString(", ")) + ". Hold short runway $runway."
    } else {
        "Runway $runway, vacate" + (if (named.isEmpty()) "" else " via " + named.joinToString(", ")) + " to spot ${spot?.n ?: "?"}."
    }
    return TaxiClearance(line, steps, chips, path.ft, named)
}

fun distanceLabel(ft: Double): String {
    val nm = ft / FT_PER_NM
    return feet(ft) + " · " + ((nm * 100).roundToInt() / 100.0).toString().take(4) + " nm"
}

/**
 * Which runway the field is working, judged by where the jet actually is.
 *
 * BMS parks a flight on the half of the ramp nearest the runway in use, and authors a separate set of spots for each
 * end — at Gunsan only 12 of 69 spots are shared between the two, over 1000 ft apart otherwise. So the spot you are
 * sitting on names the runway, with no need to model the sim's own choice.
 */
fun routeForPosition(field: Airfield, e: Double, n: Double): AfRoute? {
    var best: AfRoute? = null
    var away = Double.MAX_VALUE
    for (route in field.routes) {
        val near = TaxiNet(field, route).nearestSpot(e, n) ?: continue
        if (near.second < away) { away = near.second; best = route }
    }
    return best
}

/**
 * Where a position in theater feet (the frame the jet and the map use) falls on a field's own chart, as east and
 * north about the field origin. Null when it is nowhere near the field.
 */
fun fieldOffset(field: Airfield, northFt: Double, eastFt: Double, withinFt: Double = 60000.0): Pair<Double, Double>? {
    if (northFt == 0.0 && eastFt == 0.0) return null
    val e = eastFt - field.e
    val n = northFt - field.n
    return if (hypot(e, n) < withinFt) e to n else null
}
