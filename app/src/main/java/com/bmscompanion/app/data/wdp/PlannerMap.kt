package com.bmscompanion.app.data.wdp

import com.bmscompanion.app.data.mission.AttackCue
import com.bmscompanion.app.data.mission.AttackDrawing
import com.bmscompanion.app.data.mission.AttackOverlay
import com.bmscompanion.app.data.mission.NavOffset
import com.bmscompanion.app.data.mission.NavOffsets
import com.bmscompanion.app.data.wdp.DtcFromMission.LineOption
import com.bmscompanion.app.data.wdp.DtcFromMission.PptOption
import com.bmscompanion.app.data.wdp.DtcFromMission.PptShown
import com.bmscompanion.app.data.wdp.DtcFromMission.Pt
import kotlin.math.hypot

/**
 * What the Planner's **Map** page draws (`ui/screens/wdp/WdpMapPage.kt`), worked out from the DTC page's cartridge as
 * it stands — unsaved edits included — and from what the app knows of the mission ([DtcFromMission.Facts]).
 *
 * Two kinds of thing, drawn two ways:
 *
 * - **What the HSD will show** ([Shown]): the steerpoints (the flight plan joined, the cartridge's own precision points
 *   unjoined, the open steerpoints 81-99), the four lines, the PPTs with their type and ring, and the attack's nav offsets
 *   (VIP or VRP, the pull-up point, OA1 and OA2) laid out from their steerpoints as the jet lays them. Falcon BMS 4.38.1
 *   keeps the flight plan in the mission file and takes a cartridge steerpoint over its own, so a slot shows the
 *   cartridge's point where it has one and BMS's route (or the save's) where it does not.
 * - **What the mission knows and the cartridge does not carry** ([Known]): the tankers' and AWACS's planned tracks and
 *   stations, the known air-defence sites with their rings. Each carries what it would become in the cartridge (a
 *   [LineOption], a [PptOption]) or, where it is there already, which line or PPT holds it.
 *
 * Pure: the page hands in the cartridge's model, the facts and the options the DTC page offers.
 */
object PlannerMap {

    // ---------------------------------------------------------------- what the HSD will show

    /**
     * One steerpoint the HSD shows: [n] 1-24 or an open one (81-99). [inDtc]: the cartridge places it (else it is BMS's
     * route or the save's); [onRoute]: the flight plan, joined to its neighbours; [target]: a target of the mission.
     * [elevFt]: the third figure as the cartridge or the route holds it (feet, positive).
     */
    data class Stpt(
        val n: Int,
        val at: Pt,
        val name: String?,
        val inDtc: Boolean,
        val onRoute: Boolean,
        val target: Boolean,
        val action: Int,
        val elevFt: Double,
        val from: String,
    ) {
        val open: Boolean get() = n >= 81
    }

    /** Line [n] (1-4) as the cartridge holds it, and what it was laid from when it is one of the mission's ([name]). */
    data class Line(val n: Int, val points: List<Pt>, val name: String?)

    /** A nav offset laid out: its label ("VIP", "PUP", "OA1"), what kind of point, where, and the DED's words. */
    data class Offset(val label: String, val kind: AttackCue.Kind, val at: Pt, val note: String, val stpt: Int)

    data class Shown(
        val stpts: List<Stpt> = emptyList(),
        val ppts: List<PptShown> = emptyList(),
        val lines: List<Line> = emptyList(),
        val offsets: List<Offset> = emptyList(),
        /** the attack's line through the offsets: VIP → PUP → target, or VRP → PUP → target */
        val runIn: List<Pt> = emptyList(),
        /** "VIP", "VRP" or "" (NAV OFFSETS' Modesel) */
        val offsetMode: String = "",
        /** the cartridge's attack as every map draws it ([AttackDrawing.fromCartridge]); null when it lays out none */
        val attack: AttackOverlay? = null,
    ) {
        val route: List<Stpt> get() = stpts.filter { it.onRoute }.sortedBy { it.n }
        fun stpt(n: Int): Stpt? = stpts.firstOrNull { it.n == n }
        fun ppt(slot: Int): PptShown? = ppts.firstOrNull { it.slot == slot }
        fun line(n: Int): Line? = lines.firstOrNull { it.n == n }
    }

    /** The actions of a steerpoint that is a target: strike, bomb, SEAD, recon and the rest (`DataCardPlan.actionString`). */
    private val ATTACK_ACTIONS = setOf(14, 15, 16, 17, 18, 19, 20, 21, 23, 25, 30)

    private fun notSet(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Not set", true) }

    /**
     * What the HSD will show from [m] (the cartridge on the DTC page; null when none is loaded) and the flight plan
     * [route] ([DtcFromMission.Facts.route]). [targets] are the steerpoints the mission calls targets
     * (`WdpMission.choices`); [lineOptions] name the lines laid from the mission.
     */
    fun shown(
        m: DtcModel?, route: List<DtcFromMission.RoutePoint>, targets: Set<Int>, lineOptions: List<LineOption>,
        /** the attack page that made the cartridge's offsets, when known ("Pop-up", "HADB", "TOSS"): the path's shape */
        profile: String? = null,
        /** the target's steerpoint, when known (a VIP line does not carry it) */
        tgtStpt: Int? = null,
    ): Shown {
        val pic = m?.let { DtcFromMission.picture(it) }
        val cart = pic?.stpts.orEmpty().toMap()
        val plan = route.associateBy { it.n }
        val planned = plan.keys
        val stpts = ArrayList<Stpt>()
        for (n in 1..24) {
            val c = cart[n]
            val r = plan[n]
            val at = c ?: r?.at ?: continue
            val s = m?.stpt?.get(n - 1)
            val action = if (c != null) s?.action ?: 0 else r?.action ?: 0
            // the flight plan is joined; with none known, the cartridge's own points are, except its precision targets
            val onRoute = if (planned.isNotEmpty()) n in planned else c != null && action != -1
            stpts += Stpt(
                n = n, at = at,
                name = r?.name?.takeIf { it.isNotBlank() } ?: notSet(s?.target),
                inDtc = c != null, onRoute = onRoute,
                target = n in targets || action in ATTACK_ACTIONS,
                action = action,
                elevFt = if (c != null) kotlin.math.abs((s?.falconZ ?: 0f).toDouble()) else r?.altFt ?: 0.0,
                from = if (c != null) "your DTC" else r?.from ?: "",
            )
        }
        for ((n, at) in pic?.stpts.orEmpty()) {
            if (n < 81) continue
            val s = if (n <= 89) m?.open?.get(n - 81) else m?.hpn?.get(n - 90)
            // a target unless its type says otherwise (an alternate the Map page put in as Land, a Nav point)
            val act = s?.action ?: -1
            stpts += Stpt(n, at, notSet(s?.target), true, false, act == -1 || act in ATTACK_ACTIONS, act, kotlin.math.abs((s?.falconZ ?: 0f).toDouble()), "your DTC")
        }
        val lines = if (m == null) emptyList() else (1..4).map { k ->
            Line(k, DtcFromMission.linePoints(m, k), DtcFromMission.recognise(m, k, lineOptions)?.label)
        }.filter { it.points.isNotEmpty() }
        val offsets = ArrayList<Offset>()
        var runIn: List<Pt> = emptyList()
        var mode = ""
        var attack: AttackOverlay? = null
        if (m != null) {
            val nv = m.nav
            fun at(n: Int): Pt? = stpts.firstOrNull { it.n == n }?.at
            fun brg(o: DtcOffset) = DataCardNet.fmt(o.bearing.toDouble(), "0.0") + "° " + DtcFromMission.nm(o.range.toDouble())
            // the one attack drawing's model (AttackDrawing.fromCartridge, as the PC lays it): VIP-TO-TGT from the VIP
            // (Dash-34 p.424), TGT-TO-VRP from the target (p.425), the pull-up point from its own steerpoint, and only
            // the offset aimpoints hung on the mode's steerpoint (B8; p.426: "they are always off the steerpoint")
            val a = AttackDrawing.fromCartridge(navOffsetsOf(nv), { n -> at(n)?.let { it.north to it.east } }, profile, tgtStpt = tgtStpt)
            if (a != null) {
                attack = a
                mode = a.mode
                runIn = a.runIn.map { Pt(it.first, it.second) }
                val vip = a.mode == "VIP"
                val ref = a.refStpt
                for (c in a.cues) {
                    val (note, stpt) = when (c.kind) {
                        AttackCue.Kind.VIP -> "STPT ${nv.vip.stpt}" to nv.vip.stpt
                        AttackCue.Kind.VRP -> "TGT-TO-VRP ${brg(nv.vrp)}" to nv.vrp.stpt
                        AttackCue.Kind.PUP -> (if (vip) nv.vipPup else nv.vrpPup).let { "from STPT ${it.stpt}: ${brg(it)}" to it.stpt }
                        AttackCue.Kind.TARGET -> if (vip) "VIP-TO-TGT ${brg(nv.vip)}" to nv.vip.stpt else "STPT ${nv.vrp.stpt}" to nv.vrp.stpt
                        else -> {
                            val o = (if (c.label == "OA1") listOf(nv.oa1_1, nv.oa1_2) else listOf(nv.oa2_1, nv.oa2_2)).firstOrNull { it.stpt == ref }
                            (o?.let { "from STPT ${it.stpt}: ${brg(it)}" } ?: "") to ref
                        }
                    }
                    offsets += Offset(c.label.substringBefore(' '), c.kind, Pt(c.north, c.east), note, stpt)
                }
            }
        }
        return Shown(stpts, pic?.ppts.orEmpty(), lines, offsets, runIn, mode, attack)
    }

    /**
     * The DTC page's `[NAV OFFSETS]` as the app's cartridge model has them ([NavOffsets]), for [AttackDrawing]: each OA
     * keyed by its steerpoint as the file keys it (`OA1-6`), an offset on no steerpoint left out.
     */
    fun navOffsetsOf(nv: DtcNavOffsets): NavOffsets {
        fun o(key: String, d: DtcOffset) = NavOffset(key, d.stpt, d.bearing.toDouble(), d.range.toDouble(), d.elv.toDouble())
        val oa = listOf("OA1" to nv.oa1_1, "OA2" to nv.oa2_1, "OA1" to nv.oa1_2, "OA2" to nv.oa2_2)
            .filter { it.second.stpt >= 1 }.map { (k, d) -> o("$k-${d.stpt}", d) }.distinctBy { it.key }
        return NavOffsets(
            mode = when (nv.modesel) { 1 -> "vip"; 2 -> "vrp"; else -> "none" },
            vip = o("VIP", nv.vip), vipPup = o("VIPPUP", nv.vipPup), vrp = o("VRP", nv.vrp), vrpPup = o("VRPPUP", nv.vrpPup),
            oa = oa,
        )
    }

    // ---------------------------------------------------------------- what the mission knows

    /**
     * A tanker's, an AWACS's or a JSTARS's planned track: its whole route ([points]), the legs it holds on ([legs]),
     * what line it would be ([line], null where there is no line of it) and the line that holds it already ([inLine]).
     */
    data class Track(
        val support: DtcFromMission.Support,
        val points: List<Pt>,
        val legs: List<Pt>,
        val line: LineOption?,
        val inLine: Int?,
    )

    /** Where a support aircraft will be, the PPT marker it would be ([option]) and the PPT holding it already ([inPpt]). */
    data class Station(val support: DtcFromMission.Support, val at: Pt, val option: PptOption?, val inPpt: Int?)

    /**
     * A known air-defence site: its ring ([ringFt]: the PPT table's range where the table has its type, else the threat
     * reference's), the PPT it would be ([option], null where the table has no type for it), the PPT near it already
     * ([inPpt]) and how far the ring's edge is from the flight plan ([edgeFt], 0 or less where the route crosses it).
     */
    data class Site(
        val site: DtcFromMission.Site,
        val ringFt: Double?,
        val option: PptOption?,
        val inPpt: Int?,
        val edgeFt: Double?,
    ) {
        val crossesRoute: Boolean get() = (edgeFt ?: Double.MAX_VALUE) <= 0.0
    }

    data class Known(
        val tracks: List<Track> = emptyList(),
        val stations: List<Station> = emptyList(),
        val sites: List<Site> = emptyList(),
    ) {
        /** Sites whose ring the flight plan crosses, the table has a type for, and no PPT holds yet. */
        val ringsOnRoute: List<Site> get() = sites.filter { it.crossesRoute && it.option != null && it.inPpt == null }
    }

    /**
     * What the mission knows beside the cartridge. [options] are the PPT options the DTC page offers now (those already
     * in the cartridge left out), [lineOptions] its line options, [shown] what the HSD will show; [ringOf] the threat
     * reference's reach of a site in feet, for a site the PPT table has no type for.
     */
    fun known(
        f: DtcFromMission.Facts,
        options: List<PptOption>,
        lineOptions: List<LineOption>,
        shown: Shown,
        ringOf: (DtcFromMission.Site) -> Double?,
    ): Known {
        val route = shown.route.map { it.at }.ifEmpty { f.route.sortedBy { it.n }.map { it.at } }
        val tracks = ArrayList<Track>()
        val stations = ArrayList<Station>()
        for (s in f.support) {
            val pts = s.track.map { it.at }.filter { it.placed }
            val legs = s.track.filter { it.station && it.at.placed }.map { it.at }
            if (pts.size >= 2) {
                val opt = lineOptions.firstOrNull { it.kind != DtcFromMission.LineKind.ROUTE && it.kind != DtcFromMission.LineKind.CAP && it.label.startsWith(s.callsign + " ") }
                val inLine = opt?.let { o -> shown.lines.firstOrNull { l -> same(l.points, o.points) }?.n }
                tracks += Track(s, pts, legs, opt, inLine)
            }
            val at = s.station ?: continue
            val opt = options.firstOrNull { it.kind != DtcFromMission.PptKind.THREAT && it.at.dist(at) < 1.0 }
            val inPpt = shown.ppts.firstOrNull { it.marker && it.at.dist(at) < 3000.0 }?.slot
            stations += Station(s, at, opt, inPpt)
        }
        val sites = f.sites.map { site ->
            val opt = options.firstOrNull { it.kind == DtcFromMission.PptKind.THREAT && it.at.dist(site.at) < 1.0 }
            val inPpt = shown.ppts.firstOrNull { !it.marker && it.at.dist(site.at) < 3000.0 }?.slot
            val ring = opt?.rangeFt ?: shown.ppt(inPpt ?: -1)?.rangeFt?.takeIf { it >= 100.0 } ?: ringOf(site)
            val d = DtcFromMission.distanceToRoute(site.at, route)
            Site(site, ring, opt, inPpt, if (d != null && ring != null) d - ring else null)
        }
        return Known(tracks, stations, sites)
    }

    private fun same(a: List<Pt>, b: List<Pt>) = a.size == b.size && a.indices.all { a[it].dist(b[it]) < 50.0 }

    // ---------------------------------------------------------------- the HSD's frame

    /** The HSD's ranges in centred mode, nautical miles from the centre to the display's edge. */
    val HSD_RANGES = listOf(15, 30, 60, 120, 240)

    /** The steerpoint the HSD preview centres on first: the first target of the flight plan, else the first steerpoint. */
    fun defaultCentre(s: Shown): Int? =
        s.route.firstOrNull { it.target }?.n ?: s.route.firstOrNull()?.n ?: s.stpts.minByOrNull { it.n }?.n

    /** The smallest HSD range that holds every point of [pts] around [centre], or the largest. */
    fun rangeFor(centre: Pt, pts: List<Pt>): Int {
        val far = pts.maxOfOrNull { it.dist(centre) } ?: 0.0
        return HSD_RANGES.firstOrNull { it * DtcFromMission.NM >= far * 1.05 } ?: HSD_RANGES.last()
    }

    // ---------------------------------------------------------------- geometry for picking

    /** Distance from (px, py) to the segment (ax, ay)-(bx, by), in the same units. */
    fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** The first free steerpoint after the flight plan (1-24), else any free one; null when all are used. */
    fun freeStpt(shown: Shown, free: List<Int>): Int? {
        val last = shown.route.maxOfOrNull { it.n } ?: 0
        val taken = shown.stpts.map { it.n }.toSet()
        return free.firstOrNull { it > last && it !in taken } ?: free.firstOrNull { it !in taken }
    }

    /** "12 nm", a whole number of miles (a tenth under ten). */
    fun miles(ft: Double): String {
        val nm = ft / DtcFromMission.NM
        return if (nm < 10.0) DataCardNet.fmt(nm, "0.0") + " nm" else DataCardNet.fmt(nm, "0") + " nm"
    }

    /** "045°/32 nm" from [from] to [to], true. */
    fun braText(from: Pt, to: Pt): String {
        val br = AttackGeometry.bearingRange(from.north, from.east, to.north, to.east)
        val deg = (kotlin.math.round(br.bearingDeg).toInt() % 360 + 360) % 360
        return deg.toString().padStart(3, '0') + "°/" + miles(br.rangeFt).removeSuffix(" nm") + " nm"
    }
}
