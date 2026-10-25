package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.FuelPlan
import com.bmscompanion.app.data.mission.FuelPlan.Kind
import com.bmscompanion.app.data.mission.FuelPlan.Point
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.PlanMerge
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * `--fuelplantest out.txt [briefing.txt]` — the fuel plan's arithmetic ([FuelPlan]), pure, no files but the optional
 * briefing:
 * 1. **The method's own example**: target → recovery 230 nm along the route (200 nm straight, so the straight line
 *    would give the wrong answer), divert 75 nm, Secu Carb 1,000, nothing else → Bingo 5,200 lb, Joker 6,200 lb; and the
 *    same route with the default allowances (go-around, 10 min at 1,500 ft, one minute of AB) and IMC.
 * 2. **A low leg**: 20 lb/nm where the leg is flown low, by BMS's "immediate"/"delayed" height changes.
 * 3. **A refuel steerpoint**: full again after it; before it, reach it and still get home the way the flight came;
 *    the same route with the tanker switched off.
 * 4. **The briefing's words**: kinds, holds, altitudes.
 * 5. **The jet against the plan**: the state words along the route.
 * 6. With a briefing: BMS's printed briefing through the merge (no positions: the Dist column), its holds, target area
 *    and Bingo steerpoint.
 */
object FuelPlanTest {
    private const val NM = FuelPlan.FT_PER_NM

    fun run(briefing: File?): String = buildString {
        var fails = 0
        fun check(what: String, ok: Boolean, detail: String = "") {
            appendLine((if (ok) "ok   " else "FAIL ") + what + if (detail.isNotEmpty()) " — $detail" else "")
            if (!ok) fails++
        }
        fun near(a: Double?, b: Double, tol: Double = 0.5) = a != null && abs(a - b) <= tol
        fun table(r: FuelPlan.Result) {
            appendLine("     STPT  kind     leg nm  low  burn    to go   min      expected  margin")
            for (w in r.rows) appendLine(
                "     %-4d  %-7s  %6.1f  %-3s  %6.0f  %6.1f  %7.0f  %8s  %7s".format(
                    w.n, w.kind.name.take(7), w.legNm, if (w.legLow) "low" else "", w.legBurn, w.toGoNm, w.minLb,
                    w.expectedLb?.let { "%.0f".format(it) } ?: "-", w.marginLb?.let { "%+.0f".format(it) } ?: "-",
                ),
            )
            appendLine("     " + FuelPlan.summary(r) + "; recovery ${"%.0f".format(r.recoveryLb)} lb, divert ${"%.1f".format(r.divertNm)} nm (${r.divertFrom})")
            r.notes.forEach { appendLine("     note: $it") }
        }

        // ---------------------------------------------------------------- 1. the method's example
        appendLine("1. The method's example: 230 nm egress along the route, 75 nm divert, Secu Carb 1,000")
        val e = sqrt(115.0 * 115.0 - 100.0 * 100.0) // the egress point that makes target → home 230 nm, 200 nm straight
        val example = FuelPlan.Input(
            points = listOf(
                Point(1, "Takeoff", Kind.TAKEOFF, 0.0 + 1.0, 0.0, altFt = null),
                Point(2, "Strike", Kind.TARGET, 200 * NM, 0.0, altFt = 20_000.0),
                Point(3, "Egress", Kind.NAV, 100 * NM, e * NM, altFt = 20_000.0),
                Point(4, "Land", Kind.LAND, 1.0, 0.0),
            ),
            alternate = 1.0 to 75 * NM,
        )
        val bare = FuelPlan.Settings(vmcGoAround = 0, vmcPattern = 0, missionAllowance = 0)
        val r1 = FuelPlan.plan(example, bare)!!
        table(r1)
        check("target → recovery is 230 nm along the route", near(r1.rowAt(2)?.toGoNm, 230.0, 0.01), "%.2f nm".format(r1.rowAt(2)?.toGoNm))
        check("the egress burn is 3,450 lb (230 + 115 = 345)", near(r1.rowAt(2)!!.minLb - r1.recoveryLb, 3450.0, 0.5))
        check("the divert is 75 nm × 10 = 750 lb", near(r1.divertBurn, 750.0, 0.5) && r1.divertFrom == "fields")
        check("Bingo 5,200 lb at the target", r1.bingoLb == 5200 && r1.bingoAt == 2, "${r1.bingoLb} at STPT ${r1.bingoAt}")
        check("Joker 6,200 lb", r1.jokerLb == 6200, "${r1.jokerLb}")
        check("his roundings (3,500 + 700 + 1,000) give the same Bingo", 3500 + 700 + 1000 == r1.bingoLb)
        val straight = 1000 + 750 + 200 * 15
        check("the straight line would have said ${straight} (not used)", r1.bingoLb != straight)
        val r1d = FuelPlan.plan(example, FuelPlan.Settings())!!
        check("defaults: + go-around 300 + 10 min at 1,500 ft 600 + one minute of AB 1,000 → 7,100 / 8,100", r1d.bingoLb == 7100 && r1d.jokerLb == 8100, "${r1d.bingoLb} / ${r1d.jokerLb}")
        val r1i = FuelPlan.plan(example, FuelPlan.Settings(imc = true))!!
        check("IMC: approach, go-around, GCA racetrack, 2nd approach, 2nd go-around, ejection go-around = 1,800 → Bingo 8,000", r1i.allowanceLb == 1800 && r1i.bingoLb == 8000, "${r1i.allowanceLb}, ${r1i.bingoLb}")
        val r1t = FuelPlan.plan(example, bare.copy(divertNm = 40.0))!!
        check("a typed divert distance wins (40 nm → Bingo 4,850)", r1t.bingoLb == 4900 && near(r1t.bingoExact, 4850.0) && r1t.divertFrom == "set", "${r1t.bingoExact}")
        check("no take-off fuel: no expected fuel, no playtime, and a note says so", r1.rows.all { it.expectedLb == null } && r1.playLb == null && r1.notes.any { "take-off fuel" in it })

        // the Fuel plan card's "Fill Bingo/Joker on the DataCard": the Planner's card takes the figures as typed boxes
        runCatching {
            val card = com.bmscompanion.app.ui.screens.wdp.DataCardWiring()
            check("an empty card: nothing of the pilot's to replace", card.fuelTypedOver(r1.bingoLb, r1.jokerLb).isEmpty())
            card.fillFuelStates(r1.bingoLb, r1.jokerLb)
            check("the DataCard's BINGO and JOKER boxes hold the plan's 5,200 / 6,200", card.fuelBoxes() == ("5200" to "6200"), "${card.fuelBoxes()}")
            check("filled again: still nothing to ask", card.fuelTypedOver(r1.bingoLb, r1.jokerLb).isEmpty())
            card.onValue("txtJoker", "7000")
            val over = card.fuelTypedOver(r1.bingoLb, r1.jokerLb)
            check("a Joker the pilot typed is named before it is replaced", over == listOf("Joker 7000"), "$over")
        }.onFailure { check("the DataCard fill ran", false, "${it::class.simpleName}: ${it.message}") }

        // ---------------------------------------------------------------- 2. a low leg
        appendLine("2. A low leg")
        val lowRoute = FuelPlan.Input(
            points = listOf(
                Point(1, "Takeoff", Kind.TAKEOFF, 1.0, 0.0, change = "Climb immediate"),
                Point(2, "--", Kind.NAV, 50 * NM, 0.0, altFt = 22_000.0, change = "Descend immediate"),
                Point(3, "IP", Kind.IP, 100 * NM, 0.0, altFt = 500.0),
                Point(4, "Strike", Kind.TARGET, 120 * NM, 0.0, altFt = 500.0, change = "Climb delayed"),
                Point(5, "--", Kind.NAV, 150 * NM, 0.0, altFt = 20_000.0, change = "Descend delayed"),
                Point(6, "Land", Kind.LAND, 150 * NM, 40 * NM),
            ),
            takeoffLb = 12_000.0, takeoffFrom = "aircraft",
        )
        val r2 = FuelPlan.plan(lowRoute, bare)!!
        table(r2)
        val lows = r2.rows.map { it.legLow }
        check("legs 2-3 (descend immediate to 500 ft) and 3-4 are low", lows[2] && lows[3], lows.toString())
        check("leg 4-5 is low too: the climb at the target is delayed", lows[4])
        check("legs 1-2 and 5-6 are at medium altitude", !lows[1] && !lows[5])
        check("leg 2-3 burns 50 nm × 20 = 1,000 lb", near(r2.rows[2].legBurn, 1000.0))
        check("leg 5-6 burns 40 nm × 15 = 600 lb", near(r2.rows[5].legBurn, 600.0))
        check("expected fuel: 12,000 − 500 taxi − the burns", near(r2.rows.last().expectedLb, 12000.0 - 500 - r2.rows.sumOf { it.legBurn }))
        val r2b = FuelPlan.plan(lowRoute.copy(points = lowRoute.points.map { if (it.n == 4) it.copy(change = "Climb immediate") else it }), bare)!!
        check("with the climb immediate, leg 4-5 is not low", !r2b.rows[4].legLow)
        val r2c = FuelPlan.plan(lowRoute, bare.copy(lowAltFt = 25_000))!!
        check("a higher low threshold makes every placed leg low", r2c.rows.drop(1).all { it.legLow })

        // ---------------------------------------------------------------- 3. a tanker
        appendLine("3. A refuel steerpoint")
        val aar = FuelPlan.Input(
            points = listOf(
                Point(1, "Takeoff", Kind.TAKEOFF, 1.0, 0.0),
                Point(2, "Refuel", Kind.REFUEL, 100 * NM, 0.0, altFt = 22_000.0),
                Point(3, "Strike", Kind.TARGET, 300 * NM, 0.0, altFt = 22_000.0),
                Point(4, "Land", Kind.LAND, 1.0, 0.0),
            ),
            takeoffLb = 12_000.0, takeoffFrom = "aircraft", capacityLb = 12_000.0,
        )
        val s3 = FuelPlan.Settings(divertNm = 50.0)
        val r3 = FuelPlan.plan(aar, s3)!!
        table(r3)
        check("the recovery minimum is 1,000 + 500 + 900 = 2,400", near(r3.recoveryLb, 2400.0))
        check("STPT 2 is the tanker", r3.tankers == listOf(2))
        check("arriving at the tanker: 12,000 − 500 − 1,500 = 10,000 expected", near(r3.rowAt(2)?.expectedLb, 10000.0))
        check("full again after it (12,000)", near(r3.rowAt(2)?.afterLb, 12000.0))
        check("at the target: 12,000 − 3,000 = 9,000 expected", near(r3.rowAt(3)?.expectedLb, 9000.0))
        check("minimum at the target: 2,400 + 4,500 = 6,900", near(r3.rowAt(3)?.minLb, 6900.0))
        check("minimum at the tanker: home the way you came, 1,500 + 2,400 = 3,900", near(r3.rowAt(2)?.minLb, 3900.0) && r3.rowAt(2)!!.beforeTanker)
        check("minimum at take-off: 1,500 more = 5,400 (not 11,400 as without a tanker)", near(r3.rowAt(1)?.minLb, 5400.0) && r3.rowAt(1)!!.beforeTanker)
        check("Bingo at the target: 6,900 + 1,000 = 7,900", r3.bingoLb == 7900 && r3.bingoAt == 3, "${r3.bingoLb} at ${r3.bingoAt}")
        check("playtime at the target: 9,000 − 6,900 = 2,100 lb = 2.1 min AB = 21 min at 350 kt",
            near(r3.playLb, 2100.0) && near(r3.playAbMin, 2.1, 0.001) && near(r3.playCruiseMin, 21.0, 0.001))
        check("every margin is positive", r3.rows.all { (it.marginLb ?: -1.0) >= 0 } && r3.shortAt == null)
        val r3n = FuelPlan.plan(aar, s3.copy(tankerStpt = -1))!!
        check("tanker switched off: no fill (7,000 at the target) and the take-off minimum is 11,400",
            near(r3n.rowAt(3)?.expectedLb, 7000.0) && near(r3n.rowAt(1)?.minLb, 11400.0) && r3n.tankers.isEmpty())
        val r3s = FuelPlan.plan(aar.copy(points = aar.points.map { if (it.n == 2) it.copy(kind = Kind.NAV) else it }), s3.copy(tankerStpt = 2))!!
        check("a tanker named in the settings works the same", r3s.tankers == listOf(2) && near(r3s.rowAt(1)?.minLb, 5400.0))
        val r3h = FuelPlan.plan(aar.copy(takeoffLb = 9000.0), s3.copy(tankerStpt = -1))!!
        check("a plan that does not close on fuel says where", r3h.shortAt != null, "short at STPT ${r3h.shortAt?.n}")

        // ---------------------------------------------------------------- 4. the briefing's words
        appendLine("4. The briefing's words")
        check("Takeoff / Holding Pt / S&D / Land / -- / Refuel / Push / IP / Strike",
            FuelPlan.kindOf("Takeoff", 0) == Kind.TAKEOFF && FuelPlan.kindOf("Holding Pt", 0) == Kind.HOLD && FuelPlan.kindOf("S&D", 0) == Kind.TARGET &&
                FuelPlan.kindOf("Land", 0) == Kind.LAND && FuelPlan.kindOf("--", 0) == Kind.NAV && FuelPlan.kindOf("Refuel", 0) == Kind.REFUEL &&
                FuelPlan.kindOf("Push", 0) == Kind.PUSH && FuelPlan.kindOf("IP", 0) == Kind.IP && FuelPlan.kindOf("Strike", 0) == Kind.TARGET,
        )
        check("a cartridge action code stands in for a missing word (7 = land, 4 = refuel, -1 = target)",
            FuelPlan.kindOf(null, 7) == Kind.LAND && FuelPlan.kindOf(null, 4) == Kind.REFUEL && FuelPlan.kindOf("--", -1) == Kind.TARGET)
        check("\"Hold  (Departure: 07:30:44z)\" at 07:24:44z is 6 min", near(FuelPlan.holdMinutes("07:24:44z", "Hold  (Departure: 07:30:44z)"), 6.0, 0.001))
        check("a hold over midnight (23:50 → 00:10) is 20 min", near(FuelPlan.holdMinutes("23:50:00z", "Hold (Departure: 00:10:00z)"), 20.0, 0.001))
        check("\"21.0M\" is 21,000 ft, \"--\" nothing", FuelPlan.briefAlt("21.0M") == 21000.0 && FuelPlan.briefAlt("--") == null && FuelPlan.briefAlt("500") == 500.0)
        check("lb words: 5,200 / -300 / +1,250", FuelPlan.lb(5200.0) == "5,200" && FuelPlan.lb(-300.0) == "-300" && FuelPlan.signedLb(1250.0) == "+1,250")

        // ---------------------------------------------------------------- 5. the jet against the plan
        appendLine("5. The jet against the plan (the tanker route)")
        // (an out-and-back on one line: the heading tells the way out from the way home)
        val onEgress = FuelPlan.live(r3, 150 * NM, 0.0, 4000.0, hdgDeg = 180.0)
        check("on the egress, 150 nm out with 4,000 lb: min here 2,400 + 150 × 15 = 4,650 → BELOW MINIMUM",
            onEgress.nextN == 4 && near(onEgress.minHereLb, 4650.0, 1.0) && onEgress.state == FuelPlan.LiveState.BELOW_MIN, "${onEgress.nextN} ${onEgress.minHereLb} ${onEgress.state}")
        val onWay = FuelPlan.live(r3, 250 * NM, 0.0, 7800.0, hdgDeg = 0.0)
        check("50 nm short of the target with 7,800 lb (min here 7,650, Bingo 7,900): AT BINGO",
            onWay.nextN == 3 && near(onWay.minHereLb, 7650.0, 1.0) && onWay.state == FuelPlan.LiveState.BINGO, "${onWay.nextN} ${onWay.state} ${onWay.minHereLb}")
        val joker = FuelPlan.live(r3, 250 * NM, 0.0, 8500.0, hdgDeg = 0.0)
        check("with 8,500 lb (Joker 8,900): JOKER", joker.state == FuelPlan.LiveState.JOKER, "${joker.state}")
        val plenty = FuelPlan.live(r3, 250 * NM, 0.0, 11_000.0, hdgDeg = 0.0)
        check("with 11,000 lb: ON PLAN", plenty.state == FuelPlan.LiveState.OK && near(plenty.marginLb, 11000.0 - 7650.0, 1.0))
        val home = FuelPlan.live(r3, 100 * NM, 0.0, 5000.0, hdgDeg = 180.0)
        check("past the Bingo steerpoint, under Bingo but above the minimum (3,900): ON PLAN, no alarm", home.pastBingo && home.state == FuelPlan.LiveState.OK, "${home.nextN} ${home.state} ${home.minHereLb}")
        val noPos = FuelPlan.plan(example.copy(points = example.points.map { it.copy(x = null, y = null, legNm = 100.0) }), bare)!!
        val blind = FuelPlan.live(noPos, 0.0, 0.0, noPos.bingoLb - 10.0)
        check("with no positions the jet is held against Bingo only", blind.nextN == null && blind.state == FuelPlan.LiveState.BINGO)

        // ---------------------------------------------------------------- 6. a printed briefing
        if (briefing != null) {
            appendLine("6. The printed briefing ${briefing.name}")
            val b = runCatching { BriefingParser.parse(briefing.readText(Charsets.UTF_8)) }.getOrNull()
            check("the briefing reads", b != null && b.steerpoints.isNotEmpty())
            if (b != null) {
                val merged = PlanMerge.merge(MissionData(briefing = b))
                val input = FuelPlan.inputOf(merged, takeoffLb = 12_194.0, takeoffFrom = "F-16CJ-50 + 2 tanks", capacityLb = 12_194.0)
                val r6 = FuelPlan.plan(input, FuelPlan.Settings(divertNm = 75.0))
                check("a plan from the briefing", r6 != null)
                if (r6 != null) {
                    table(r6)
                    val rows = b.steerpoints.filter { it.n > 0 }
                    val firstLand = rows.indexOfFirst { FuelPlan.kindOf(it.desc, 0) == Kind.LAND && it.comments?.contains("Alternate", true) != true }
                    check("take-off to the first landing, the alternate left out", r6.rows.size == firstLand + 1 && r6.rows.last().kind == Kind.LAND, "${r6.rows.size} rows")
                    val dist = rows.take(firstLand + 1).drop(1).sumOf { it.dist?.toDoubleOrNull() ?: 0.0 }
                    check("with no positions the legs are the briefing's Dist column (%.1f nm)".format(dist), near(r6.rows.first().toGoNm, dist, 0.05), "%.1f".format(r6.rows.first().toGoNm))
                    val holds = r6.rows.filter { it.holdMin > 0 }.map { "STPT ${it.n} ${"%.0f".format(it.holdMin)} min" }
                    check("its holds are read from the departure times", holds.isNotEmpty(), holds.joinToString(", "))
                    check("a target area and a Bingo steerpoint", r6.targetAt != null && r6.rows.any { it.n == r6.bingoAt }, "target ${r6.targetAt}, Bingo at ${r6.bingoAt}")
                    check("no leg flown low at 15,000-22,000 ft", r6.rows.none { it.legLow })
                }
            }
        }
        appendLine(if (fails == 0) "all checks passed" else "$fails check(s) failed")
    }

    @Suppress("unused") private fun nm(ax: Double, ay: Double, bx: Double, by: Double) = hypot(bx - ax, by - ay) / NM
}
