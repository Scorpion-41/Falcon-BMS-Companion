package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The fuel plan (the Mission section's **Fuel plan** card, the Dashboard card of the same name, the Noteboard's Fuel
 * block): a pilot's rule-of-thumb method, worked along the flight plan the Mission section shows. Pure, `kotlin.*`
 * only, so the PC, the browser and Android compute the same figures.
 *
 * **The method** (an ex-F-4 pilot's, proposed for the app):
 * - **Secu Carb** (fuel safety): what must remain with the engine shut down at parking — 1,000 lb for the F-16.
 * - **Minimum fuel at the recovery**: VMC (at the Initial of the break) = Secu Carb + divert + a go-around + 10 min at
 *   1,500 ft; IMC (at the start of the penetration / the FAF) = Secu Carb + approach + go-around + GCA racetrack +
 *   second approach + second go-around + divert + a go-around in view of an ejection. Referred back to every
 *   steerpoint by adding the burns in between ([FuelRow.minLb]), **along the route**, never the straight line.
 * - **Bingo** = the minimum at the farthest point of the route + what the mission needs there (one minute of
 *   afterburner by default); **Joker** = Bingo + an extra. Not low-fuel values: the fuel at which a decision is due.
 * - Burns: 15 lb/nm at medium altitude and normal cruise (M0.7–0.8), 20 lb/nm for a leg planned low, 10 lb/nm for the
 *   divert (economy). His example: target → recovery 230 nm = 3,450 lb, divert 75 nm = 750 lb, + Secu Carb 1,000 →
 *   Bingo 5,200 lb, Joker 6,200 lb (checked by `--fuelplantest`).
 *
 * **What this adds to it**: the expected fuel at each steerpoint going forward from the take-off fuel (less a taxi
 * and take-off allowance, and the planned holds at the cruise rate), the margin at each steerpoint, the playtime over
 * the target area, and a tanker on the route ([Settings.tankerStpt]).
 *
 * **The tanker rule** (conservative, and why): before a refuel steerpoint the minimum is what it takes to reach the
 * tanker **and still get home from there if the tanker is not there** — back the way the flight came, the legs flown
 * to it reversed at their own burns, plus the recovery minimum. The way back is the only path from the tanker known
 * to be clear (a straight line home may cross the very threats the route goes round), and a missed join-up is
 * exactly when there is no fuel to spare for a new route. After the refuel the jet is full again
 * ([Input.capacityLb]) and the minimum is the ordinary one, referred back from the landing.
 *
 * It is an estimate: per-nm rules of thumb along the route, no winds, no climb. The Planner's Performance page gives
 * the engine-table figures ([ESTIMATE_LINE]).
 */
object FuelPlan {
    const val FT_PER_NM = 6076.12

    /** The card's one honest line about what these figures are. */
    const val ESTIMATE_LINE = "Rule-of-thumb estimate (lb per nm along the route, no wind or climb); the Planner's Performance page has the engine-table figures."

    /**
     * Everything the pilot can change (per device, on the card's folded Settings). The F-16C Block 40/50 figures:
     * - [secuCarb] 1,000 lb: the method's own figure for the F-16 (it covers the most limiting fuel block).
     * - [cruisePerNm] 15, [lowPerNm] 20, [divertPerNm] 10 lb/nm: the method's own rules of thumb.
     * - [lowAltFt] 5,000 ft: a leg planned at or below it burns [lowPerNm]. BMS plans low-level legs in hundreds of
     *   feet and medium-altitude ones from 15,000 ft up; 5,000 ft sits between and still catches a low leg over hills.
     * - VMC: [vmcGoAround] 300 lb (a go-around in MIL to the downwind, about 2 min at 7,000-9,000 pph) and
     *   [vmcPattern] 600 lb (10 min at 1,500 ft, about 3,600 pph at 250-300 KIAS with stores).
     * - IMC: [imcApproach] 250 lb (penetration/FAF to the minimums, about 4 min at 3,500 pph), [imcGoAround] 300,
     *   [imcRacetrack] 400 lb (a GCA radar pattern, about 25 nm, 6-7 min), [imcApproach2] 250, [imcGoAround2] 300,
     *   [imcEjectGoAround] 300 lb (the last go-around, in view of an ejection).
     * - [missionAllowance] 1,000 lb (one minute of afterburner: finish the attack, an evasive manoeuvre) and
     *   [jokerExtra] 1,000 lb: the method's own.
     * - [taxiTakeoff] 500 lb: about 10 min of taxi at idle (~1,000-1,200 pph) and the take-off roll in AB.
     * - [abPerMin] 1,000 lb/min and [cruisePerMin] 100 lb/min (10 min at 350 kt = 1,000 lb): the method's references.
     * - [takeoffLb] 0 = from the jet (in 3D, on the ground) or the aircraft's internal fuel and its tanks; [divertNm]
     *   0 = from the recovery field to the alternate, straight; [bingoStpt] 0 = the steerpoint farthest from the
     *   recovery; [tankerStpt] 0 = the route's refuel steerpoints, −1 = none, n = steerpoint n.
     */
    @Serializable
    data class Settings(
        val secuCarb: Int = 1000,
        val cruisePerNm: Double = 15.0,
        val lowPerNm: Double = 20.0,
        val divertPerNm: Double = 10.0,
        val lowAltFt: Int = 5000,
        val imc: Boolean = false,
        val vmcGoAround: Int = 300,
        val vmcPattern: Int = 600,
        val imcApproach: Int = 250,
        val imcGoAround: Int = 300,
        val imcRacetrack: Int = 400,
        val imcApproach2: Int = 250,
        val imcGoAround2: Int = 300,
        val imcEjectGoAround: Int = 300,
        val missionAllowance: Int = 1000,
        val jokerExtra: Int = 1000,
        val taxiTakeoff: Int = 500,
        val abPerMin: Int = 1000,
        val cruisePerMin: Int = 100,
        /** planned holds (a holding point, time on station) burn [cruisePerMin] in the expected fuel */
        val holdsBurn: Boolean = true,
        val takeoffLb: Int = 0,
        val divertNm: Double = 0.0,
        val bingoStpt: Int = 0,
        val tankerStpt: Int = 0,
    ) {
        /** The recovery allowance's parts, as the card lists them (Secu Carb and the divert apart). */
        fun recoveryParts(): List<Pair<String, Int>> = if (imc) listOf(
            "Approach" to imcApproach, "Go-around" to imcGoAround, "GCA racetrack" to imcRacetrack,
            "2nd approach" to imcApproach2, "2nd go-around" to imcGoAround2, "Go-around (ejection)" to imcEjectGoAround,
        ) else listOf("Go-around" to vmcGoAround, "10 min at 1,500 ft" to vmcPattern)
    }

    /** What a steerpoint is to the plan. */
    enum class Kind { TAKEOFF, NAV, PUSH, IP, TARGET, HOLD, REFUEL, LAND }

    /**
     * One steerpoint of the flight plan, take-off to the first landing. [x]/[y] theater feet (null: no position), [legNm]
     * the briefing's own distance from the previous point (used when either end has no position), [altFt] the planned
     * altitude (the save's waypoint, else the briefing's ALT column; never a steerpoint's z), [change] the briefing's
     * height change at this point ("Climb immediate", "Descend delayed"), [holdMin] the planned time spent here.
     */
    data class Point(
        val n: Int,
        val label: String,
        val kind: Kind,
        val x: Double? = null,
        val y: Double? = null,
        val legNm: Double? = null,
        val altFt: Double? = null,
        val change: String? = null,
        val holdMin: Double = 0.0,
    ) {
        val hasPos: Boolean get() = x != null && y != null && (x != 0.0 || y != 0.0)
    }

    /**
     * The plan's input: [points] take-off first, the landing last; [alternate] the alternate field's position;
     * [takeoffLb] the fuel at engine start and [takeoffFrom] where that came from ("jet", "aircraft", "set");
     * [capacityLb] a full jet (internal + tanks), what a tanker fills it to.
     */
    data class Input(
        val points: List<Point>,
        val alternate: Pair<Double, Double>? = null,
        val alternateName: String? = null,
        val takeoffLb: Double? = null,
        val takeoffFrom: String? = null,
        val capacityLb: Double? = null,
        /** the recovery field's position, for the divert when the landing steerpoint has none */
        val recovery: Pair<Double, Double>? = null,
    )

    /**
     * One steerpoint's figures. [legNm]/[legBurn] are the leg **into** it ([legLow] planned low, [legKnown] false when
     * neither positions nor the briefing gave its length); [toGoNm] the distance along the route to the landing;
     * [minLb] the minimum on arrival; [expectedLb] the fuel expected on arrival (null without a take-off fuel);
     * [holdBurn] what the planned hold here burns; [afterLb] the fuel on leaving (after the hold, or full after a refuel).
     */
    data class Row(
        val n: Int,
        val label: String,
        val kind: Kind,
        val x: Double?,
        val y: Double?,
        val legNm: Double,
        val legKnown: Boolean,
        val legLow: Boolean,
        val legBurn: Double,
        val toGoNm: Double,
        val minLb: Double,
        val expectedLb: Double?,
        val holdMin: Double,
        val holdBurn: Double,
        val afterLb: Double?,
        /** the minimum is the tanker rule's (reach the tanker, still able to go home from it) */
        val beforeTanker: Boolean = false,
    ) {
        val marginLb: Double? get() = expectedLb?.minus(minLb)
        val hasPos: Boolean get() = x != null && y != null && (x != 0.0 || y != 0.0)
    }

    data class Result(
        val rows: List<Row>,
        val settings: Settings,
        /** the minimum on arrival at the recovery field: Secu Carb + divert + the VMC or IMC allowance */
        val recoveryLb: Double,
        val divertNm: Double,
        /** the divert's distance came from the two fields' positions (false: typed, or unknown = 0) */
        val divertFrom: String,
        val divertBurn: Double,
        val allowanceLb: Int,
        /** Bingo and Joker exactly, and rounded up to the next 100 lb (what the ICP is set to) */
        val bingoExact: Double,
        val bingoLb: Int,
        val bingoAt: Int,
        val jokerLb: Int,
        /** the target area's first steerpoint, its playtime in lb (null without a take-off fuel) */
        val targetAt: Int?,
        val playLb: Double?,
        val stationMin: Double,
        val takeoffLb: Double?,
        val takeoffFrom: String?,
        val tankers: List<Int>,
        val notes: List<String>,
    ) {
        val playAbMin: Double? get() = playLb?.let { it / max(1, settings.abPerMin) }
        val playCruiseMin: Double? get() = playLb?.let { it / max(1, settings.cruisePerMin) }
        fun rowAt(n: Int): Row? = rows.firstOrNull { it.n == n }
        /** the leg along which a margin first goes negative, if the plan does not close */
        val shortAt: Row? get() = rows.firstOrNull { (it.marginLb ?: 0.0) < 0 }
    }

    /** The plan for [input] with [s]; null with fewer than two steerpoints. */
    fun plan(input: Input, s: Settings = Settings()): Result? {
        val pts = input.points
        if (pts.size < 2) return null
        val notes = ArrayList<String>()

        // ---- the legs: length, low or not, burn
        val legNm = DoubleArray(pts.size)
        val legKnown = BooleanArray(pts.size) { it == 0 }
        val legLow = BooleanArray(pts.size)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val nm = if (a.hasPos && b.hasPos) hypot(b.x!! - a.x!!, b.y!! - a.y!!) / FT_PER_NM else b.legNm
            legKnown[i] = nm != null
            legNm[i] = nm ?: 0.0
            legLow[i] = legAltitude(a, b)?.let { it <= s.lowAltFt } == true
        }
        if (legKnown.any { !it }) notes += "Leg " + (1 until pts.size).filter { !legKnown[it] }.joinToString(", ") { "${pts[it - 1].n}-${pts[it].n}" } +
            " has no length (no position and no distance in the briefing): counted as 0 nm."
        val legBurn = DoubleArray(pts.size) { i -> if (i == 0) 0.0 else legNm[i] * (if (legLow[i]) s.lowPerNm else s.cruisePerNm) }
        val toGo = DoubleArray(pts.size)
        for (i in pts.size - 2 downTo 0) toGo[i] = toGo[i + 1] + legNm[i + 1]

        // ---- the recovery minimum
        val last = pts.last()
        val home = if (last.hasPos) last.x!! to last.y!! else input.recovery
        val alt = input.alternate
        val (divertNm, divertFrom) = when {
            s.divertNm > 0 -> s.divertNm to "set"
            alt != null && home != null -> hypot(alt.first - home.first, alt.second - home.second) / FT_PER_NM to "fields"
            else -> 0.0 to "unknown"
        }
        if (divertFrom == "unknown") notes += "No alternate position: the divert is counted as 0 nm (set its distance in the settings)."
        val divertBurn = divertNm * s.divertPerNm
        val allowance = s.recoveryParts().sumOf { it.second }
        val recovery = s.secuCarb + divertBurn + allowance

        // ---- the tankers: the route's refuel steerpoints, one the pilot names, or none
        val tankers: List<Int> = when {
            s.tankerStpt < 0 -> emptyList()
            s.tankerStpt > 0 -> pts.indices.filter { pts[it].n == s.tankerStpt && it in 1 until pts.size - 1 }
            else -> pts.indices.filter { pts[it].kind == Kind.REFUEL && it in 1 until pts.size - 1 }
        }

        // ---- the minimum on arrival, from the landing backwards; before a tanker the tanker rule
        val min = DoubleArray(pts.size)
        min[pts.size - 1] = recovery
        for (i in pts.size - 2 downTo 0) min[i] = min[i + 1] + legBurn[i + 1]
        val beforeTanker = BooleanArray(pts.size)
        // "back(t)": from tanker t home the way the flight came, plus the recovery minimum
        fun back(t: Int): Double = (1..t).sumOf { legBurn[it] } + recovery
        for (k in tankers.indices.reversed()) {
            val t = tankers[k]
            val from = if (k == 0) 0 else tankers[k - 1] + 1
            // the arrival minimum at the tanker: home from it if it is not there
            var need = back(t)
            min[t] = need
            beforeTanker[t] = true
            for (i in t - 1 downTo from) {
                need += legBurn[i + 1]
                min[i] = need
                beforeTanker[i] = true
            }
        }

        // ---- the expected fuel, going forward
        val start = if (s.takeoffLb > 0) s.takeoffLb.toDouble() else input.takeoffLb
        val from = if (s.takeoffLb > 0) "set" else input.takeoffFrom
        val full = input.capacityLb ?: start
        val expected = arrayOfNulls<Double>(pts.size)
        val after = arrayOfNulls<Double>(pts.size)
        val holdBurn = DoubleArray(pts.size) { if (s.holdsBurn) pts[it].holdMin * s.cruisePerMin else 0.0 }
        if (start != null) {
            var f = start - s.taxiTakeoff
            for (i in pts.indices) {
                if (i > 0) f -= legBurn[i]
                expected[i] = f
                f -= holdBurn[i]
                if (i in tankers && full != null) f = max(f, full)
                after[i] = f
            }
        }

        // ---- Bingo at the farthest point from the recovery (straight), else the last of the target area
        val inner = (1 until pts.size - 1).toList()
        val bingoIdx = pts.indices.firstOrNull { s.bingoStpt > 0 && pts[it].n == s.bingoStpt && it < pts.size - 1 }
            ?: inner.filter { pts[it].hasPos && last.hasPos }.maxByOrNull { hypot(pts[it].x!! - last.x!!, pts[it].y!! - last.y!!) * 1.0 + it * 1e-6 }
            ?: inner.lastOrNull { pts[it].kind == Kind.TARGET }
            ?: inner.maxByOrNull { toGo[it] } ?: 0
        val bingoExact = min[bingoIdx] + s.missionAllowance
        val bingo = roundUp100(bingoExact)
        val joker = roundUp100(bingoExact + s.jokerExtra)

        // ---- the target area and its playtime: the fuel above the minimum on arrival there
        val tIdx = pts.indices.firstOrNull { pts[it].kind == Kind.TARGET }
        val play = tIdx?.let { i -> expected[i]?.let { it - min[i] } }
        // the plan's own time on station: the holds at the target area's steerpoints (a CAS, a CAP)
        val station = if (tIdx == null) 0.0 else (tIdx until pts.size - 1).takeWhile { pts[it].kind == Kind.TARGET }.sumOf { pts[it].holdMin }

        if (tankers.isNotEmpty()) notes += "Before the tanker (STPT ${tankers.joinToString(", ") { pts[it].n.toString() }}) the minimum is to reach it and still get home the way you came if it is not there; after it, the jet is full again" +
            (full?.let { " (" + lb(it) + " lb)" } ?: "") + "."
        if (start == null) notes += "No take-off fuel: set it in the settings for the expected fuel and the playtime."

        val rows = pts.indices.map { i ->
            val p = pts[i]
            Row(
                n = p.n, label = p.label, kind = p.kind, x = p.x, y = p.y,
                legNm = legNm[i], legKnown = legKnown[i], legLow = legLow[i], legBurn = legBurn[i], toGoNm = toGo[i],
                minLb = min[i], expectedLb = expected[i], holdMin = p.holdMin, holdBurn = holdBurn[i], afterLb = after[i],
                beforeTanker = beforeTanker[i],
            )
        }
        return Result(
            rows = rows, settings = s, recoveryLb = recovery, divertNm = divertNm, divertFrom = divertFrom, divertBurn = divertBurn,
            allowanceLb = allowance, bingoExact = bingoExact, bingoLb = bingo, bingoAt = pts[bingoIdx].n, jokerLb = joker,
            targetAt = tIdx?.let { pts[it].n }, playLb = play, stationMin = station, takeoffLb = start, takeoffFrom = from,
            tankers = tankers.map { pts[it].n }, notes = notes,
        )
    }

    /**
     * The altitude a leg from [a] to [b] is flown at: BMS's height change at [a] says when the jet goes to [b]'s altitude
     * ("… immediate": straight after [a], so the leg is at [b]'s; "… delayed": late, so at [a]'s); with no word, [b]'s.
     * A point with no altitude (the take-off, the landing) takes the other's.
     */
    fun legAltitude(a: Point, b: Point): Double? {
        val av = a.altFt?.takeIf { it > 0 }
        val bv = b.altFt?.takeIf { it > 0 }
        val delayed = a.change?.contains("delay", ignoreCase = true) == true
        return if (delayed) av ?: bv else bv ?: av
    }

    // ---------------------------------------------------------------- live

    enum class LiveState { OK, JOKER, BINGO, BELOW_MIN }

    /**
     * The jet against the plan: [nextN] the steerpoint it is flying to (the leg whose line it is nearest), [minHereLb]
     * the minimum where it is (the next steerpoint's plus the leg's burn for the distance left to it), [marginLb], and
     * [state]: below that minimum; else, until the Bingo steerpoint is passed, at Bingo or Joker; else OK.
     */
    data class Live(val nextN: Int?, val minHereLb: Double?, val marginLb: Double?, val fuelLb: Double, val state: LiveState, val pastBingo: Boolean)

    fun live(r: Result, x: Double, y: Double, fuelLb: Double, hdgDeg: Double? = null): Live {
        val rows = r.rows
        // the leg whose line the jet is nearest (both ends placed); with the jet's heading, a leg flown the other way
        // counts 10 nm further off — a route that comes back the way it went (a CAS orbit, an out-and-back) has two
        // legs on the same line
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in 1 until rows.size) {
            val a = rows[i - 1]; val b = rows[i]
            if (!a.hasPos || !b.hasPos) continue
            val dx = b.x!! - a.x!!; val dy = b.y!! - a.y!!
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0 else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
            val course = kotlin.math.atan2(dy, dx) * 180.0 / kotlin.math.PI
            val off = hdgDeg?.let { h -> abs(((h - course) % 360.0 + 540.0) % 360.0 - 180.0) } ?: 0.0
            val d = hypot(x - (a.x + t * dx), y - (a.y + t * dy)) + if (off > 90.0) 10 * FT_PER_NM else 0.0
            // a later leg wins a tie (the CAS orbit's way back, a route that doubles back on itself)
            if (d <= bestD + 1.0) { best = i; bestD = d }
        }
        val bingoIdx = rows.indexOfFirst { it.n == r.bingoAt }
        if (best < 0) {
            val state = when {
                fuelLb <= r.bingoLb -> LiveState.BINGO
                fuelLb <= r.jokerLb -> LiveState.JOKER
                else -> LiveState.OK
            }
            return Live(null, null, null, fuelLb, state, pastBingo = false)
        }
        val b = rows[best]
        val left = hypot(b.x!! - x, b.y!! - y) / FT_PER_NM
        val rate = if (b.legLow) r.settings.lowPerNm else r.settings.cruisePerNm
        // pro-rated along the leg: the distance left to the next steerpoint, never more than the leg itself (a jet off
        // the line is not further from home than the steerpoint behind it)
        val minHere = b.minLb + kotlin.math.min(left, b.legNm) * rate
        val margin = fuelLb - minHere
        // the Bingo steerpoint is behind the jet once it flies a leg after it
        val past = bingoIdx >= 0 && best > bingoIdx
        val state = when {
            margin < 0 -> LiveState.BELOW_MIN
            !past && fuelLb <= r.bingoLb -> LiveState.BINGO
            !past && fuelLb <= r.jokerLb -> LiveState.JOKER
            else -> LiveState.OK
        }
        return Live(b.n, minHere, margin, fuelLb, state, past)
    }

    // ---------------------------------------------------------------- from the mission

    /**
     * The plan's points from the merged mission (the steerpoints the Mission section shows): the briefing's rows from
     * the take-off to the first landing, each placed where the merge placed it, its altitude the save's waypoint
     * ([saveAlt], by steerpoint) or else the briefing's ALT column, its leg the briefing's Dist where a position is
     * missing, its hold the briefing's departure time less its arrival. The alternate is the "Land (Alternate)" row's
     * position, else [alternatePos].
     */
    fun inputOf(
        m: MergedMission,
        saveAlt: Map<Int, Double> = emptyMap(),
        alternatePos: Pair<Double, Double>? = null,
        alternateName: String? = null,
        takeoffLb: Double? = null,
        takeoffFrom: String? = null,
        capacityLb: Double? = null,
        recoveryPos: Pair<Double, Double>? = null,
    ): Input {
        val rows = m.briefing?.steerpoints.orEmpty().associateBy { it.n }
        val all = m.allSteerpoints.filter { it.n in 1..24 && (it.onRoute || rows[it.n] != null) }.sortedBy { it.n }
        val out = ArrayList<Point>()
        var alternate: Pair<Double, Double>? = null
        var landed = false
        for (p in all) {
            val row = rows[p.n]
            val kind = kindOf(p.desc ?: row?.desc, p.action, p.comments)
            if (landed) {
                if (alternate == null && p.isAlternate && p.hasPos) alternate = p.x to p.y
                continue
            }
            if (p.isAlternate) { if (p.hasPos) alternate = p.x to p.y; continue }
            val alt = saveAlt[p.n]?.takeIf { it > 0 } ?: briefAlt(p.altText ?: row?.alt)
            out += Point(
                n = p.n, label = labelOf(p, kind), kind = kind,
                x = if (p.hasPos) p.x else null, y = if (p.hasPos) p.y else null,
                legNm = row?.dist?.trim()?.toDoubleOrNull(), altFt = alt, change = p.actionText ?: row?.action,
                holdMin = holdMinutes(p.time ?: row?.time, p.comments ?: row?.comments),
            )
            if (kind == Kind.LAND) landed = true
        }
        return Input(out, alternate ?: alternatePos, alternateName, takeoffLb, takeoffFrom, capacityLb, recoveryPos)
    }

    /** What a steerpoint is, from the briefing's word, else the cartridge action code. */
    fun kindOf(desc: String?, action: Int, comments: String? = null): Kind {
        val d = desc?.trim()?.takeUnless { it == "--" }.orEmpty()
        val low = d.lowercase()
        val words = low.split(SPLIT).filter { it.isNotEmpty() }.toSet()
        fun has(vararg w: String) = w.any { val k = it.lowercase(); if (' ' in k || '-' in k) low.contains(k) else k in words }
        return when {
            has("Takeoff", "Take off", "Take-off") || action == 1 -> Kind.TAKEOFF
            has("Land", "Landing") || action == 7 -> Kind.LAND
            has("Refuel", "Tanker", "AAR") || action == 4 || action == 24 -> Kind.REFUEL
            has("Push") || action == 2 -> Kind.PUSH
            has("IP") -> Kind.IP
            d.contains("S&D", ignoreCase = true) || has(
                "Strike", "Bomb", "Attack", "Target", "SEAD", "DEAD", "CAS", "BAI", "Recon", "CAP", "BARCAP", "TARCAP", "Sweep",
                "Escort", "Intercept", "Interdict", "OCA", "ASW", "ASuW", "ELINT", "Jam", "SAD", "GndStrike", "NavStrike",
            ) || action == -1 || action in 9..23 -> Kind.TARGET
            has("Holding", "Hold") || comments?.contains("Hold", ignoreCase = true) == true || action == 8 -> Kind.HOLD
            else -> Kind.NAV
        }
    }

    private fun labelOf(p: MergedPoint, kind: Kind): String {
        val d = (p.desc ?: "").trim().takeUnless { it.isEmpty() || it == "--" }
        return d ?: when (kind) {
            Kind.TAKEOFF -> "Takeoff"; Kind.LAND -> "Land"; Kind.REFUEL -> "Refuel"; Kind.PUSH -> "Push"; Kind.IP -> "IP"
            Kind.TARGET -> "Target"; Kind.HOLD -> "Hold"; Kind.NAV -> "Nav"
        }
    }

    /** The briefing's ALT column: "21.0M" is 21,000 ft, "500" 500 ft, "--" nothing. */
    fun briefAlt(s: String?): Double? {
        val v = NUMBER.find(s ?: return null)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return if (v < 100.0) v * 1000.0 else v
    }

    /** A planned hold in minutes: the comment's "(Departure: 07:30:44z)" less the arrival time; 0 without both. */
    fun holdMinutes(time: String?, comments: String?): Double {
        val arrive = seconds(time) ?: return 0.0
        val dep = DEPARTURE.find(comments ?: return 0.0)?.groupValues?.get(1)?.let(::seconds) ?: return 0.0
        var d = dep - arrive
        if (d < 0) d += 86_400
        return if (d in 1..43_200) d / 60.0 else 0.0
    }

    private val SPLIT = Regex("[^a-z0-9&]+")
    private val NUMBER = Regex("(\\d+(?:\\.\\d+)?)")
    private val DEPARTURE = Regex("Departure:\\s*([0-9:]+)")
    private val CLOCK = Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")

    private fun seconds(s: String?): Int? {
        val m = CLOCK.find(s ?: return null) ?: return null
        return m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60 + (m.groupValues[3].toIntOrNull() ?: 0)
    }

    // ---------------------------------------------------------------- words

    fun roundUp100(v: Double): Int = (ceil(v / 100.0 - 1e-9) * 100.0).toInt()

    /** "5,200": a whole number of pounds, grouped (no String.format: the browser has only stand-ins for it). */
    fun lb(v: Double): String {
        val n = v.roundToInt()
        val digits = abs(n).toString().reversed().chunked(3).joinToString(",").reversed()
        return (if (n < 0) "-" else "") + digits
    }

    /** "+1,200" / "-300". */
    fun signedLb(v: Double): String = (if (v.roundToInt() >= 0) "+" else "") + lb(v)

    /** "2.4": one decimal. */
    fun oneDecimal(v: Double): String {
        val t = (v * 10).roundToInt()
        return (if (t < 0) "-" else "") + (abs(t) / 10).toString() + "." + (abs(t) % 10).toString()
    }

    /** The plan in a few lines, for the checks and the Noteboard's foot. */
    fun summary(r: Result): String = buildString {
        append("Bingo ${lb(r.bingoLb.toDouble())} lb (STPT ${r.bingoAt}), Joker ${lb(r.jokerLb.toDouble())} lb")
        r.playLb?.let { append(", playtime ${lb(it)} lb = ${oneDecimal(r.playAbMin!!)} min AB / ${r.playCruiseMin!!.roundToInt()} min at 350 kt") }
    }
}
