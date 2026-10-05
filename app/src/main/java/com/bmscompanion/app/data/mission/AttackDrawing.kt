package com.bmscompanion.app.data.mission

import com.bmscompanion.app.data.wdp.AttackMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **One attack, built one way, for every map** (1.3.8; the attack pages' own map, the DataCard's picMap, the Upd
 * Kneeboard attack page, the Mission map, a VR map board, the Planner's Map page). Two builders make the same
 * [AttackOverlay]:
 *
 * - [fromPlot]: an attack page's own figures ([AttackMap.Plot], the points its map marks).
 * - [fromCartridge]: a cartridge's `[NAV OFFSETS]`, each line laid out from its own steerpoint by true bearing and range
 *   as the jet lays it (Dash-34 p.423-425). The PC (`PlanStore.attackFrom`) and the Planner's Map page
 *   (`PlannerMap.shown`) both call it.
 *
 * What the model holds, whichever builder made it:
 *
 * - the **target** (always), and the start of the run: the **VIP** at the IP steerpoint in VIP mode, the **VRP** in VRP
 *   mode — never both, and no separate IP cue (the IP *is* the VIP);
 * - the **pull-up point**, and the offset aimpoints of the mode in use only (the pair hung on the IP in VIP mode, on the
 *   target in VRP mode: "they are always off the steerpoint", Dash-34 p.426). An OA with no range is not drawn, and an
 *   OA2 on OA1 (Pop-up with Place OA2 unchecked, which WDP writes as a copy of OA1) is drawn once, as OA1;
 * - the **path** the jet flies, in the profile's own order — Pop-up start → PUP → OA1 → apex → MAP → TGT, HADB VRP →
 *   PUP → OA1 → TGT, TOSS start → OA1 → OA2 → PUP → TGT (a cartridge leaves out the apex and MAP, which it does not
 *   carry; with no known profile it is start → PUP → TGT) — and the dotted leg **past the target** to OA2 on Pop-up and
 *   HADB.
 *
 * Positions are sim feet, north and east.
 */
object AttackDrawing {
    const val POPUP = "Pop-up"
    const val HADB = "HADB"
    const val TOSS = "TOSS"

    /** Feet in a nautical mile, for the maps' notes (WDP's `NM_TO_FT`). HADB's own page figures keep WDP's 6076.21. */
    const val NM_FT = 6076.1157

    /** Two OAs closer than this are one point: OA2 on OA1 is drawn once. */
    const val SAME_POINT_FT = 50.0

    /** An attack and the cartridge's are the same attack when every point agrees to this. */
    const val SAVED_FT = 150.0

    /** The caption's warning when the cartridge does not hold the attack drawn. */
    const val NOT_SAVED = "not in the cartridge — Save to DTC"

    /** The card's and WDP's name for a profile ("PopUp", "HADB", "TOSS") as the maps name it, or null. */
    fun profileOfCard(card: String?): String? = when (card) { "PopUp" -> POPUP; "HADB" -> HADB; "TOSS" -> TOSS; else -> null }

    /** The other way round: "Pop-up" → "PopUp". */
    fun cardOf(profile: String?): String? = when (profile) { POPUP -> "PopUp"; HADB -> "HADB"; TOSS -> "TOSS"; else -> null }

    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dn = a.first - b.first
        val de = a.second - b.second
        return sqrt(dn * dn + de * de)
    }

    private fun placed(p: Pair<Double, Double>?): Pair<Double, Double>? = p?.takeIf { it.first != 0.0 || it.second != 0.0 }

    /** "4.2 nm to TGT" (one decimal, without a platform formatter). */
    fun nmText(ft: Double): String {
        val t = (ft / NM_FT * 10.0).roundToInt()
        return "${t / 10}.${abs(t % 10)} nm"
    }

    /**
     * An attack page's attack: [profile] ("Pop-up", "HADB", "TOSS"), the page's [plot], its TGT STPT [tgtStpt] and the
     * IP [ipStpt] (used only in VIP mode). The page's own "VIP solution" point is the target: it is not drawn apart.
     */
    fun fromPlot(profile: String, plot: AttackMap.Plot, tgtStpt: Int, ipStpt: Int?, theater: String = ""): AttackOverlay {
        val tgt = plot.target
        val vip = plot.startIsVip && profile != HADB
        val start = placed(plot.start)
        val pup = placed(plot.pup)
        val oa1 = placed(plot.oa1)
        val oa2 = placed(plot.oa2)?.takeIf { oa1 == null || dist(it, oa1) > SAME_POINT_FT }
        val path: List<Pair<Double, Double>> = when (profile) {
            // the page's own order; its last point (the VIP solution in VIP mode) is the target
            POPUP -> plot.path.dropLast(1).filter { placed(it) != null } + tgt
            // VRP → PUP → OA1 → TGT, then OA2 past it (the leg past the target is [beyond])
            HADB -> listOfNotNull(start, pup, oa1, tgt)
            TOSS -> listOfNotNull(start, oa1, oa2, pup, tgt)
            else -> listOfNotNull(start, pup, tgt)
        }
        val beyond = if (oa2 != null && (profile == POPUP || profile == HADB)) listOf(tgt, oa2) else emptyList()
        val ref = if (vip) (ipStpt ?: 0) else tgtStpt
        return build(profile, if (vip) "VIP" else "VRP", tgt, start, pup, oa1, oa2, path, beyond, tgtStpt, ref, theater, page = profile)
    }

    /**
     * A cartridge's `[NAV OFFSETS]` ([nav]) laid out where the jet will put it: VIP-TO-TGT from the VIP (p.424),
     * TGT-TO-VRP from the target (p.425), the pull-up point from its own steerpoint, and only the OA pair hung on the
     * mode's steerpoint. [at] gives a steerpoint's position (the cartridge's, else the route's). [profile] is the
     * attack page that made it when that is known (the DataCard's profile, or the Planner's current attack when the
     * cartridge's lines are that page's); [tgtStpt] the target's steerpoint where it is known (in VRP mode it is the
     * VRP line's own). Null when nothing can be laid out.
     */
    fun fromCartridge(
        nav: NavOffsets,
        at: (Int) -> Pair<Double, Double>?,
        profile: String? = null,
        theater: String = "",
        tgtStpt: Int? = null,
        page: String = "NAV OFFSETS",
    ): AttackOverlay? {
        fun lay(o: NavOffset?): Pair<Double, Double>? {
            if (o == null || o.stpt <= 0 || o.rangeFt <= 0.0) return null
            val from = placed(at(o.stpt)) ?: return null
            val b = o.bearing * PI / 180.0
            return (from.first + cos(b) * o.rangeFt) to (from.second + sin(b) * o.rangeFt)
        }
        fun oaOf(stpt: Int, which: String): Pair<Double, Double>? =
            nav.oa.firstOrNull { it.stpt == stpt && it.key.startsWith(which, ignoreCase = true) }?.let(::lay)
        val vip = nav.mode.equals("vip", ignoreCase = true)
        val vrpMode = nav.mode.equals("vrp", ignoreCase = true)
        val prof = profile?.takeIf { it == POPUP || it == HADB || it == TOSS }.orEmpty()
        return when {
            vip -> {
                val v = nav.vip ?: return null
                val ip = placed(at(v.stpt)) ?: return null
                val tgt = lay(v) ?: return null
                val pup = lay(nav.vipPup)
                val oa1 = oaOf(v.stpt, "OA1")
                val oa2 = oaOf(v.stpt, "OA2")?.takeIf { oa1 == null || dist(it, oa1) > SAME_POINT_FT }
                val (path, beyond) = cartridgePath(prof, ip, pup, oa1, oa2, tgt)
                build(prof, "VIP", tgt, ip, pup, oa1, oa2, path, beyond, tgtStpt ?: 0, v.stpt, theater, page)
            }
            vrpMode -> {
                val v = nav.vrp ?: return null
                val tgt = placed(at(v.stpt)) ?: return null
                val vrp = lay(v) ?: return null
                val pup = lay(nav.vrpPup)
                val oa1 = oaOf(v.stpt, "OA1")
                val oa2 = oaOf(v.stpt, "OA2")?.takeIf { oa1 == null || dist(it, oa1) > SAME_POINT_FT }
                val (path, beyond) = cartridgePath(prof, vrp, pup, oa1, oa2, tgt)
                build(prof, "VRP", tgt, vrp, pup, oa1, oa2, path, beyond, tgtStpt ?: v.stpt, v.stpt, theater, page)
            }
            else -> null
        }
    }

    private fun cartridgePath(
        profile: String, start: Pair<Double, Double>, pup: Pair<Double, Double>?, oa1: Pair<Double, Double>?,
        oa2: Pair<Double, Double>?, tgt: Pair<Double, Double>,
    ): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> = when (profile) {
        POPUP -> listOfNotNull(start, pup, oa1, tgt) to (if (oa2 != null) listOf(tgt, oa2) else emptyList())
        HADB -> listOfNotNull(start, pup, oa1, tgt) to (if (oa2 != null) listOf(tgt, oa2) else emptyList())
        TOSS -> listOfNotNull(start, oa1, oa2, pup, tgt) to emptyList()
        else -> listOfNotNull(start, pup, tgt) to emptyList()
    }

    private fun build(
        profile: String, mode: String, tgt: Pair<Double, Double>, start: Pair<Double, Double>?, pup: Pair<Double, Double>?,
        oa1: Pair<Double, Double>?, oa2: Pair<Double, Double>?, path: List<Pair<Double, Double>>, beyond: List<Pair<Double, Double>>,
        tgtStpt: Int, refStpt: Int, theater: String, page: String,
    ): AttackOverlay {
        val cues = ArrayList<AttackCue>()
        cues += AttackCue(if (tgtStpt > 0) "TGT $tgtStpt" else "TGT", tgt.first, tgt.second, AttackCue.Kind.TARGET)
        start?.let {
            val note = nmText(dist(it, tgt)) + " to TGT"
            cues += if (mode == "VIP") AttackCue(if (refStpt > 0) "VIP $refStpt" else "VIP", it.first, it.second, AttackCue.Kind.VIP, note)
            else AttackCue("VRP", it.first, it.second, AttackCue.Kind.VRP, note)
        }
        pup?.let { cues += AttackCue("PUP", it.first, it.second, AttackCue.Kind.PUP) }
        oa1?.let { cues += AttackCue("OA1", it.first, it.second, AttackCue.Kind.OA) }
        oa2?.let { cues += AttackCue("OA2", it.first, it.second, AttackCue.Kind.OA) }
        return AttackOverlay(
            page = page, cues = cues, runIn = path, theater = theater,
            profile = profile, mode = mode, tgtStpt = tgtStpt, refStpt = refStpt, beyond = beyond,
        )
    }

    /**
     * Whether [a] and [b] are the same attack: the same mode, and every point of one within [SAVED_FT] of the
     * other's point of the same kind (OAs by their label) — plus what the cartridge's 0.1° bearings allow at that
     * point's range from the steerpoint it is laid from (the IP in VIP mode, the target in VRP mode): a VIP line 44 nm
     * long is 230 ft across at a twentieth of a degree.
     */
    fun same(a: AttackOverlay?, b: AttackOverlay?): Boolean {
        if (a == null || b == null) return false
        if (a.mode.isNotEmpty() && b.mode.isNotEmpty() && a.mode != b.mode) return false
        fun key(c: AttackCue) = c.kind.name + (if (c.kind == AttackCue.Kind.OA) c.label else "")
        val ka = a.cues.filter { it.kind != AttackCue.Kind.IP }.associateBy(::key)
        val kb = b.cues.filter { it.kind != AttackCue.Kind.IP }.associateBy(::key)
        if (ka.keys != kb.keys) return false
        val from = (if (a.mode == "VIP") a.cues.firstOrNull { it.kind == AttackCue.Kind.VIP } else a.target)?.let { it.north to it.east }
        return ka.all { (k, c) ->
            val tol = SAVED_FT + (from?.let { dist(it, c.north to c.east) } ?: 0.0) * BEARING_SLACK
            kb[k]?.let { d -> dist(c.north to c.east, d.north to d.east) <= tol } == true
        }
    }

    /** A cartridge bearing is whole tenths of a degree: half of one, as a fraction of the range (sin 0.05°, and a little). */
    private const val BEARING_SLACK = 0.0009

    /**
     * What every map says under the attack: "Pop-up · VIP from STPT 6 · TGT STPT 7", "HADB · VRP · TGT STPT 7"; a
     * cartridge's attack with no known page starts at the mode.
     */
    fun caption(a: AttackOverlay): String {
        val parts = ArrayList<String>()
        if (a.profile.isNotBlank()) parts += a.profile
        when (a.mode) {
            "VIP" -> parts += if (a.refStpt > 0) "VIP from STPT ${a.refStpt}" else "VIP"
            "VRP" -> parts += "VRP"
        }
        if (a.tgtStpt > 0) parts += "TGT STPT ${a.tgtStpt}"
        return parts.joinToString(" · ")
    }

    /** [caption] and, when the cartridge does not hold it, [NOT_SAVED] after it. */
    fun captionWithSaved(a: AttackOverlay): Pair<String, String?> = caption(a) to (if (a.saved) null else NOT_SAVED)

    /**
     * The route's IP when no attack is drawn (the DataCard's route picture, WDP's square): the steerpoint before the
     * first strike action (14, 15, 17 or 18), or null. [route] is (steerpoint, action) in order.
     */
    fun routeIp(route: List<Pair<Int, Int>>): Int? {
        val sorted = route.sortedBy { it.first }
        for (i in 0 until sorted.size - 1) if (sorted[i + 1].second in STRIKE_ACTIONS) return sorted[i].first
        return null
    }

    /** The actions WDP's map squares the steerpoint before: strike, bomb, SEAD and deep strike (14, 15, 17, 18). */
    val STRIKE_ACTIONS = setOf(14, 15, 17, 18)
}
