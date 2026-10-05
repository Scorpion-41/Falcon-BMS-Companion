package com.bmscompanion.app.data.wdp

import com.bmscompanion.app.data.wdp.PopupPlan.MapItem
import kotlin.math.cos
import kotlin.math.sin

/**
 * The map the TOSS and HADB pages draw beside their profile — `Draw` on each of those pages, which crops the theater
 * map around the target and draws the flight plan, the pre-planned threats and the attack's own points on it.
 *
 * The Pop-up page's `Draw` is ported inside [PopupPlan] and compared mark for mark with the real program's; these two
 * are the same routine with a different last block (which points are joined, which are ringed), so the shared parts
 * live here and each plan adds its own geometry. The output is the same [PopupPlan.MapPicture] — marks in
 * picSatView's 435 pixels, WinForms colour names — so one view draws all three.
 *
 * Two things differ from WDP on purpose, both in what is drawn where rather than in any figure the page prints:
 *
 * - **Threat rings go where the threat is.** TOSS and HADB hand `ScalePointToMap` a threat's east as its north (the
 *   Pop-up page does not), which puts every ring at the mirror of its real position about the map's diagonal. A ring
 *   in the wrong place is worse than none, so the rings here are drawn where the Pop-up page draws them.
 * - **The IP is a square on every drawing.** WDP marks it with a flag that only the target lookup clears, so the
 *   second repaint after a change draws it as a circle like every other steerpoint. Here each drawing starts afresh.
 */
object AttackMap {
    /** A steerpoint as `Draw` reads it from the campaign table: FalconX east, FalconY north, and its action. */
    class Route(val east: Float = 0f, val north: Float = 0f, val action: Int = 0)

    /** A pre-planned threat: FalconX east, FalconY north, its range in feet and its code. */
    class Threat(val east: Float, val north: Float, val rangeFt: Float, val code: String)

    /** The crop: [centre] is the target's pixel, [scale] pixels per foot, and the upper-left corner in feet. */
    class Frame(val centre: Int, val scale: Double, val upperN: Double, val upperE: Double) {
        /** `ScalePointToMap`: feet to the map's pixels; either figure zero is "no point", drawn at the corner. */
        fun point(north: Double, east: Double): Pair<Int, Int> {
            if (north == 0.0 || east == 0.0) return 0 to 0
            return PopupPlan.cint((east - upperE) * scale) to PopupPlan.cint((upperN - north) * scale)
        }
    }

    /** The start of `Draw`: the target in the middle of picSatView, the zoom factor feet across it. */
    fun frame(tgtNorth: Double, tgtEast: Double, zoomFactor: Int): Frame {
        val w = PopupPlan.MAP_PX
        val num = PopupPlan.cint(w / 2.0)
        val num2 = w.toDouble() / zoomFactor.toDouble()
        return Frame(num, num2, tgtNorth + num / num2, tgtEast - num / num2)
    }

    /** `NewPos_N` / `NewPos_E`: [dist] feet from a point along [brgRad]; a point with a zero figure stays nowhere. */
    fun newPos(north: Double, east: Double, brgRad: Double, dist: Int): Pair<Double, Double> {
        if (north == 0.0 || east == 0.0) return 0.0 to 0.0
        if (dist == 0) return north to east
        return (north + cos(brgRad) * dist) to (east + sin(brgRad) * dist)
    }

    /**
     * `Draw`'s two loops: the flight plan from steerpoint 2 (a square and a blue square on the first, a square on the
     * IP — the point before one whose action is 14, 15, 17 or 18 — and a circle on the rest, each numbered and joined
     * to the one before it, except between two steerpoints whose action is 7), then the threats.
     */
    fun routeAndThreats(f: Frame, route: List<Route>, threats: List<Threat>, showNr: Boolean, whiteMap: Boolean, items: MutableList<MapItem>): Int {
        val ink = if (!whiteMap) "White" else "Black"
        val textInk = if (whiteMap) "Black" else "White"
        var prev = 0 to 0
        var action = 0
        var ipDrawn = false
        var ipSquare = -1
        var i = 1
        while (i != 24) {
            val before = prev
            val prevAction = action
            val r = route.getOrNull(i) ?: Route()
            val east = PopupPlan.cint(r.east.toDouble())
            val north = PopupPlan.cint(r.north.toDouble())
            action = r.action
            val next = route.getOrNull(i + 1)?.action ?: 0
            if (east == 0 && north == 0) break
            val p = f.point(north.toDouble(), east.toDouble())
            prev = p
            if (i == 1) {
                items += MapItem.Rect(p.first - 8, p.second - 8, 16, 16, ink)
                items += MapItem.Rect(p.first - 10, p.second - 10, 20, 20, "Blue")
            } else if (!ipDrawn && (next == 14 || next == 15 || next == 17 || next == 18)) {
                ipSquare = items.size
                items += MapItem.Rect(p.first - 8, p.second - 8, 16, 16, ink); ipDrawn = true
            } else {
                items += MapItem.Ellipse(p.first - 8, p.second - 8, 16, 16, ink)
            }
            items += if (action == 7) MapItem.Text((i + 1).toString(), p.first - 10, p.second + 8, textInk, "GenericSansSerif", 10)
            else MapItem.Text((i + 1).toString(), p.first + 5, p.second + 5, textInk, "GenericSansSerif", 10)
            if (before.first != 0 && before.second != 0 && !(action == 7 && prevAction == 7)) {
                items += MapItem.Line(before.first, before.second, p.first, p.second, ink)
            }
            i++
        }
        threats.take(15).forEachIndexed { k, t ->
            val east = PopupPlan.cint(t.east.toDouble())
            val north = PopupPlan.cint(t.north.toDouble())
            if (east == 0 && north == 0) return@forEachIndexed
            val p = f.point(north.toDouble(), east.toDouble())
            val r = PopupPlan.cint(t.rangeFt.toDouble() * f.scale)
            items += MapItem.Rect(p.first, p.second, 1, 1, "Red")
            items += MapItem.Rect(p.first - 1, p.second - 1, 2, 2, "Black")
            items += MapItem.Ellipse(p.first - r, p.second - r, r * 2, r * 2, "Red")
            val text = if (showNr) t.code + " / " + (k + 56) else t.code
            val shift = if (showNr) PopupPlan.cint(text.length / 2.0 * 10.0 - 8.0) else PopupPlan.cint(text.length / 2.0 * 10.0)
            items += MapItem.Text(text, p.first - shift, p.second + 5, "Red", "Arial", 10)
        }
        return ipSquare
    }

    /**
     * Where a page's own map puts the attack, in sim feet (north, east): the points its `Draw` marks, from the same
     * figures, so another map drawing them lands where the page's does. The DED's lines, laid out again from their
     * steerpoint (the target, or the IP in VIP mode), land on these points too — the attack pages' geometry check.
     *
     * [start] is where the run begins — the VRP, or the IP in VIP mode ([startIsVip]) — and [path] the line `Draw`
     * joins, point by point in its order. [oa2] is null where the page does not place one; [ip] is the IP steerpoint
     * when the page has one.
     */
    class Plot(
        val target: Pair<Double, Double>,
        val start: Pair<Double, Double>?,
        val startIsVip: Boolean,
        val pup: Pair<Double, Double>?,
        val oa1: Pair<Double, Double>?,
        val oa2: Pair<Double, Double>?,
        val ip: Pair<Double, Double>?,
        val path: List<Pair<Double, Double>>,
    )

    /** The picture, with its crop in feet for the app's own map; [underlay] items come before the attack's own marks. */
    fun picture(f: Frame, items: List<MapItem>, underlay: Int = -1, ipSquare: Int = -1): PopupPlan.MapPicture {
        val w = PopupPlan.MAP_PX
        return PopupPlan.MapPicture(IntArray(4), w, items, f.upperN, f.upperE, w / f.scale, underlay, ipSquare = ipSquare)
    }
}
