package com.bmscompanion.app.data.wdp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Weapon Delivery Planner's TOSS page, as a state machine: Falcas's `cntTOSS`, ported.
 *
 * The page is ten sliders and a pair of switches, and every change runs the same chain the original runs —
 * `ChangeX()` → `ProgramFlow()` → true airspeed, turn radius, bomb range, the pull-up, the offset aim points, the
 * visual reference point, the labels. The methods here keep WDP's names and its order, because the order is part
 * of the answer: `PullUp()` is run from the height and G sliders and **not** from `ProgramFlow`, so its result
 * persists between changes, and the release-height slider's range is re-derived from it only when those two move.
 * A "cleaner" recompute-everything design gives different numbers on the second slider you touch.
 *
 * Two things about the arithmetic are kept exactly, and would each shift the digits if tidied:
 *
 * - **The constants are floats.** `NM_TO_FT` is `6076.1157f`, `DTR` is `0.01745329f`, `RTD` is `57.29578f`, and
 *   the original widens each to double at the point of use. `(float)feet / NM_TO_FT` is a single-precision
 *   division before anything else happens to it. The port does the same.
 * - **Two roundings.** `Math.Round` in .NET breaks a tie to the even digit; a label formatted `"##0.0"` breaks it
 *   away from zero. Both are used, on different numbers, and both are here.
 *
 * ## WDP's mistakes, fixed (the D-numbers are the plan's and the comparison's, `expected-diffs/toss.txt`)
 *
 * - **D1, D2: VIP mode is measured on true north and east.** WDP's `CoordFlow` read the target's and the IP's feet
 *   back from `ConvertLatLonToFeet`, whose text runs **east first**, into slots it treated as north first, so every
 *   VIP bearing was mirrored about the north-east diagonal: with the IP 8 nm from the target on a course of 020°,
 *   VIP-TO-TGT read 070.0° where Pop-up reads 020.0°. And `VIP()` measured OA1/OA2 from (0,0), since
 *   `intOA1_X/Y`/`intOA2_X/Y` were never assigned. Now each point is laid out from the target in feet and measured
 *   from the IP with [AttackGeometry.bearingRange], with no lat/lon round trip (which also cost about 5 ft).
 * - **D3: the DED shows what is saved.** WDP's `FillLabelsBMS` filled DEST OA1 with the pull-up point's figures in
 *   VRP mode while `SaveNavOffsets` wrote the real OA1, and its VRP line read "VPP". The DED lines and [navOffsets]
 *   are now filled from the same strings, and Save to DTC hands on only the lines of the reference in use
 *   ([savedOffsets]) — WDP wrote VIP lines in VRP mode too, measured from an IP at (0,0) when there was none.
 * - **D4: `OA1()` takes the arctangent.** The angle a turn radius R subtends at distance D is atan(R/D); WDP took
 *   tan(R/D). It then added `cos(sin(angleOff))·R` — the cosine of a sine — where `cos(angleOff)·R` is meant. And
 *   `VRP()` ran before `OA1()` in the flow while reading OA1's range and the angle off's radians, so the VRP was one
 *   change behind whenever the angle off or the G moved; the flow now places OA1 first.
 * - **D7: elevations for the jet** are the target's elevation plus the profile's height ([AttackGeometry.elevation];
 *   0 stays 0, ground level in BMS), and each can be typed over ([elev]).
 *
 * The campaign side is [setTargets] (`Get_Coords`): the target and IP in sim feet, printed as lat/lon labels once the
 * theater's projection is given ([objC]). In VRP mode the figures come from the sliders alone.
 *
 * The rest of the page is what its buttons say: the map (`Draw`, [mapPicture]) over the flight plan and threats the
 * wiring hands in ([route], [threats]), its zoom ([zoomLevel]) and PPT numbers ([showPPTnr]), and the nav offsets
 * Save to DTC hands the cartridge ([navOffsets], `SaveNavOffsets`).
 *
 * Checked against the program itself: `--wdptosstest` drives the real `cntTOSS` by reflection over a grid of
 * slider settings and demands the same label text for every one (`tools/wdpref`, `toss` mode).
 */
class TossPlan {

    // ---------------------------------------------------------------- Falcas's constants, as floats
    companion object {
        /** `Get_Coords`' four lat/lon labels. */
        val COORD_LABELS = listOf("lblTGT_N", "lblTGT_E", "lblIP_N", "lblIP_E")

        const val NM_TO_FT_F = 6076.1157f
        const val DTR_F = 0.01745329f
        const val RTD_F = 57.29578f
        private val NM_TO_FT = NM_TO_FT_F.toDouble()
        private val DTR = DTR_F.toDouble()
        private val RTD = RTD_F.toDouble()

        /** .NET `Math.Round(double)`: to the even digit on a tie. */
        fun bankers(v: Double): Long {
            val floor = kotlin.math.floor(v)
            val diff = v - floor
            val r = when {
                diff > 0.5 -> floor + 1.0
                diff < 0.5 -> floor
                floor.toLong() % 2L == 0L -> floor
                else -> floor + 1.0
            }
            return r.toLong()
        }

        /** VB `Strings.Format(x, "##0.0")`: one decimal, a tie away from zero. */
        fun oneDecimal(v: Double): String {
            val scaled = abs(v) * 10.0
            var n = kotlin.math.floor(scaled).toLong()
            if (scaled - n >= 0.5) n++
            val sign = if (v < 0 && n != 0L) "-" else ""
            return sign + (n / 10).toString() + "." + (n % 10).toString()
        }

        private fun wrap360(v: Double): Double = when {
            v < 0.0 -> v + 360.0
            v >= 360.0 -> v - 360.0
            else -> v
        }
    }

    // ---------------------------------------------------------------- the sliders, in WDP's units
    /** trbIngrSpd × 10: knots CAS */
    var ingressCas = 500
    /** trbIngrHeight × 100: feet */
    var ingressHeight = 300
    /** trbG: 2..6 */
    var pullingGs = 3
    /** "Left" or "Right" */
    var turnDirection = "Right"
    /** trbOa2ToPup: nm, 1..5 */
    var oa2ToPupNm = 3
    /** trbReleaseAngle: degrees, 0..45 */
    var releaseAngleDeg = 30
    /** trbReleaseSpd × 10: knots CAS */
    var releaseCas = 450
    /** trbReleaseHeight × 100: feet */
    var releaseHeight = 3000
    /** trbAngleOff: degrees, 0..90 */
    var angleOff = 0
    /** trbHeading: degrees, 0..360 */
    var attackHeadingDeg = 0
    /** numWaypoint: the target steerpoint */
    var waypoint = 4
    /**
     * The IP steerpoint the pilot picked in the Planner's IP STPT box, or null for WDP's own rule, the steerpoint before
     * the target (WDP has no such box: D87). The wiring hands the plan that steerpoint's position ([setTargets]).
     */
    var ipStpt: Int? = null
    /** The IP's steerpoint number as the page uses it: [ipStpt], else the one before the target (WDP's `strIPpoint`). */
    val ipWaypoint: Int get() = ipStpt ?: (waypoint - 1)
    /** blnRef: true is VIP, false is VRP (WDP inverts the ini value; the fallback lands on VIP) */
    var ref = true
    /** blnLAT: the low-altitude toss, which is what the page always sets on load */
    var lat = true

    /** the release-height slider's range, which WDP re-derives from the pull-up (feet ÷ 100) */
    var releaseSliderMin = 1
        private set
    var releaseSliderMax = 100
        private set
    /** the ingress-height slider's range (feet ÷ 100) */
    var ingressSliderMin = 1
        private set
    var ingressSliderMax = 20
        private set

    // ---------------------------------------------------------------- the working state, named as WDP names it
    private var intDiveAngleDeg = 0
    private var intIngressTAS = 0
    private var intReleaseTAS = 0
    private var intTurnRadius = 0
    private var intPulldeg = 0
    private var intPullX = 0
    private var intPullZ = 0
    private var intPosReleaseHeight = 0
    private var intBombRange = 0
    private var intVertTrackDist = 0
    private var intHorzTrackDist = 0
    private var intMAP = 0
    private var intVRPPUPToTgt = 0
    private var intVRPPUPelv = 0
    private var dblVRPPUPHeadingDeg = 0.0
    private var dblVRPPUPBearingDeg = 0.0
    private var intOA2toVRPPUPfeet = 0
    private var intOA2ToTgt = 0
    private var intLineupToOA2 = 0
    private var dblOA2BearingDeg = 0.0
    private var intOA2elv = 0
    private var intOA1ToTgt = 0
    private var dblOA1BearingDeg = 0.0
    private var dblOA1HeadingDeg = 0.0
    private var intOA1elv = 0
    private var dblDiffrad = 0.0
    private var dblDiffDeg = 0.0
    private var dblTurnRad = 0.0
    private var dblCorr = 0.0
    private var intOA1toVRPfeet = 0
    private var intVrpToTgt = 0
    private var dblVRPBearingDeg = 0.0
    private var dblVRPHeadingDeg = 0.0
    private var intVRPelv = 0
    private var intAttackBearingDeg = 0
    private var intApproachHeadingDeg = 0
    private var dblReleaseAngleRad = 0.0

    /** The ELEV figures typed over the page's own (D7); a new target forgets them. */
    val elev = AttackGeometry.ElevOverrides()

    // ---------------------------------------------------------------- what the page shows
    /** Control name → text, exactly as WDP's `FillLabelsBMS` writes them. */
    val labels = LinkedHashMap<String, String>()

    private var strVRPbrg = ""
    private var strVRPrng = ""
    private var strVRPelv = ""
    private var strVRP_PUPbrg = ""
    private var strVRP_PUPrng = ""
    private var strVRP_PUPelv = ""
    private var strVRP_OA1brg = ""
    private var strVRP_OA1rng = ""
    private var strVRP_OA1elv = ""
    private var strVRP_OA2brg = ""
    private var strVRP_OA2rng = ""
    private var strVRP_OA2elv = ""
    private var strVIPelv = ""
    private var strVIP_PUPelv = ""
    private var strVIP_OA1elv = ""
    private var strVIP_OA2elv = ""

    // ---------------------------------------------------------------- the load, in Setup()'s order

    /**
     * What `cntTOSS_Load` → `Setup()` does with the defaults: each slider is applied through its own `Change`,
     * with `ProgramFlow` silenced (WDP's `blnLoadedFlag` is false until the end), then the three geometry steps
     * run once, and only then does the flow run. The release-height range is therefore first derived with a turn
     * radius of zero — which is what the original does, and why the port does it too.
     */
    fun load() {
        loaded = false
        intDiveAngleDeg = 0
        changeIngressSpeed()
        trackBarIngrHeight()
        changeIngressHeight()
        changePullingG()
        changeTurn()
        changeOa2ToPup()
        changeRelAngle()
        changeRelSpd()
        trackBarReleaseHeight()
        changeRelHeight()
        changeAngleOff()
        changeHeading()
        getTAS()
        getTurnRadius()
        pullUp()
        refCaptions()      // Setup runs Ref() here; its flow is silent until loaded, its captions are not
        delivery()
        loaded = true
        programFlow()
        // cntTOSS_Load: the zoom starts at 30 once the flow has run
        zoom = 30
        zoomLevel()
    }

    private var loaded = false

    // ---------------------------------------------------------------- the slider handlers

    fun changeIngressSpeed() { labels["lblIngrCasVal"] = ingressCas.toString(); programFlow() }

    fun changeIngressHeight() {
        labels["lblIngrHeightVal"] = ingressHeight.toString()
        pullUp()
        intVRPPUPelv = ingressHeight
        trackBarReleaseHeight()
    }

    fun changePullingG() {
        labels["lblGVal"] = pullingGs.toString()
        pullUp()
        trackBarReleaseHeight()
        programFlow()
    }

    fun changeTurn() {
        labels["lblTurnVal"] = turnDirection
        approachHed()
        programFlow()
    }

    fun changeOa2ToPup() {
        labels["lblOa2ToPupVal"] = oa2ToPupNm.toString()
        intOA2toVRPPUPfeet = bankers((oa2ToPupNm.toFloat() * NM_TO_FT_F).toDouble()).toInt()
        programFlow()
    }

    fun changeRelAngle() { labels["lblRelAngleVal"] = releaseAngleDeg.toString(); programFlow() }

    fun changeRelSpd() { labels["lblRelCasVal"] = releaseCas.toString(); programFlow() }

    fun changeRelHeight() { labels["lblReleaseHeightVal"] = releaseHeight.toString(); programFlow() }

    fun changeAngleOff() {
        labels["lblAngleOff"] = angleOff.toString()
        approachHed()
        programFlow()
    }

    fun changeHeading() {
        labels["lblAttackHdgVal"] = attackHeadingDeg.toString()
        intAttackBearingDeg = attackHeadingDeg - 180
        if (intAttackBearingDeg < 0) intAttackBearingDeg += 360
        programFlow()
    }

    /**
     * The Reference switch (VIP / VRP), as `Ref()` leaves it: without an IP there is no VIP to plan from, so the switch
     * stays on VRP (the "blocked" flags say why), and the DED panel captions follow.
     */
    fun chooseRef(vip: Boolean) {
        ref = vip && doVip
        refCaptions()
        programFlow()
    }

    /** The DED panel's captions for the reference in use — `Ref()`'s label writes, which Setup runs too. */
    private fun refCaptions() {
        if (ref) {
            labels["lblDEDvip_1"] = "VIP-TO-TGT"; labels["lblDEDvip_2"] = "VIP"
            labels["lblDEDpup_1"] = "VIP-TO-PUP"; labels["lblDEDpup_2"] = "VIP"
        } else {
            // WDP printed "VPP" here (D3): the jet's line is TGT-TO-VRP, and the point a VRP
            labels["lblDEDvip_1"] = "TGT-TO-VRP"; labels["lblDEDvip_2"] = "VRP"
            labels["lblDEDpup_1"] = "TGT-TO-PUP"; labels["lblDEDpup_2"] = "VRP"
        }
    }

    // ---------------------------------------------------------------- the campaign side: target and IP

    /**
     * The theater's coordinate data, as `SetCoordData` gives it the page (`WdpCoords.coordData`); null while the
     * app does not know the theater, and then the coordinate labels are not filled and the VIP figures are measured
     * from the feet themselves.
     */
    var objC: PopupCoords.CoordData? = null

    /** Which of `Get_Coords`'s six labels it shows (`lblTGT_N` … `lblIP_elv`); their text is in [labels]. */
    val coordShown = LinkedHashMap<String, Boolean>()

    /** Sim feet of the target and the IP — WDP's `decOrig_TGT`/`decOrig_IP`, with `.Y` north and `.X` east. */
    private var tgtNorth = 0.0
    private var tgtEast = 0.0
    private var tgtElev = 0.0
    /** The ground's elevation at the target (feet), which the ELEV figures stand on (D7); 0 while it is not known. */
    private var tgtGround = 0.0
    /** The ground under the target the ELEVs stand on, feet above sea level (D7); 0 without a target. */
    val targetElevation: Int get() = if (gotTgt) kotlin.math.round(tgtGround).toInt() else 0
    private var ipNorth = 0.0
    private var ipEast = 0.0
    private var ipElev = 0.0

    /** `blnGotTGT` / `blnDoVIP`: whether a target is known, and whether an IP is too (VIP mode needs both). */
    var gotTgt = false
        private set
    var doVip = false
        private set

    private var strVIPrng = ""
    private var strVIPnm = ""
    private var strVIPhdg = ""
    private var strVIPbrg = ""
    private var strVIP_PUPrng = ""
    private var strVIP_PUPnm = ""
    private var strVIP_PUPhdg = ""
    private var strVIP_PUPbrg = ""
    private var strVIP_OA1rng = ""
    private var strVIP_OA1nm = ""
    private var strVIP_OA1hdg = ""
    private var strVIP_OA1brg = ""
    private var strVIP_OA2rng = ""
    private var strVIP_OA2nm = ""
    private var strVIP_OA2hdg = ""
    private var strVIP_OA2brg = ""

    /**
     * `Get_Coords`, fed from the app's own steerpoints instead of WDP's campaign table.
     *
     * WDP takes the target as steerpoint *waypoint* and the IP as the one before it, in sim feet, with the
     * steerpoint's own altitude as the elevation (`GridZ × 10` on its DTC path). That is exactly what the app's
     * cartridge holds, so it is handed straight in: [tgt] and [ip] are (north ft, east ft, altitude ft), or null
     * when that steerpoint does not exist. WDP then decides, as here: no target → no VIP and the reference is forced
     * to VRP with the "blocked" flags up; a target but no IP → still no VIP; both → VIP available.
     *
     * WDP tests the **east** figure (`.X`) alone: a target or an IP whose east is zero is not there. With the
     * theater's projection ([objC]) the labels get WDP's lat/lon strings; the VIP figures are measured on the feet
     * themselves (WDP measured them on those strings read back, east first: D1). A new target forgets any ELEV the
     * pilot typed for the old one.
     */
    fun setTargets(tgt: Triple<Double, Double, Double>?, ip: Triple<Double, Double, Double>?, groundFt: Double? = null) {
        tgtNorth = 0.0; tgtEast = 0.0; tgtElev = 0.0
        // the ground under the target (D7), feet above sea level with its sign: typed, BMS's height map, or the cartridge's
        tgtGround = if (tgt != null) groundFt ?: 0.0 else 0.0
        ipNorth = 0.0; ipEast = 0.0; ipElev = 0.0
        if (tgt != null && !(tgt.first == 0.0 && tgt.second == 0.0)) {
            tgtNorth = tgt.first; tgtEast = tgt.second; tgtElev = tgt.third
            if (ip != null && !(ip.first == 0.0 && ip.second == 0.0)) {
                ipNorth = ip.first; ipEast = ip.second; ipElev = ip.third
            }
        }
        if (tgtEast == 0.0) {
            gotTgt = false; doVip = false
        } else if (ipEast == 0.0) {
            gotTgt = true; doVip = false
        } else {
            gotTgt = true; doVip = true
        }
        // the elevation labels, as Get_Coords writes them: whole feet, unsigned
        labels["lblTGT_elv"] = if (gotTgt) abs(bankers(tgtElev)).toString() else ""
        labels["lblIP_elv"] = if (doVip) abs(bankers(ipElev)).toString() else ""
        elev.target(tgtNorth, tgtEast)
        coordLabels()
        // Ref(): without an IP the reference is VRP and the VIP switch is blocked
        if (!doVip) ref = false
        refCaptions()
        programFlow()
    }

    /**
     * `Get_Coords`' labels: shown for a target (the IP's only with VIP), then each pair hidden again if it reads
     * `00,00.000` / `000,00.000`. Without a target the labels keep their last text, hidden.
     */
    private fun coordLabels() {
        val c = objC ?: return
        for (n in COORD_LABELS) if (n !in labels) labels[n] = if (n.endsWith("_E")) "000,00.000" else "00,00.000"
        if (!gotTgt) {
            for (n in COORD_LABELS + listOf("lblTGT_elv", "lblIP_elv")) coordShown[n] = false
            return
        }
        for (n in listOf("lblTGT_N", "lblTGT_E", "lblTGT_elv")) coordShown[n] = true
        for (n in listOf("lblIP_N", "lblIP_E", "lblIP_elv")) coordShown[n] = doVip
        // WDP fills the IP's elevation whenever there is a target, VIP or not
        labels["lblIP_elv"] = abs(bankers(ipElev)).toString()
        try {
            val text = PopupCoords.feetToCoordsBoth(c, tgtNorth, tgtEast)
            val tgtN = PopupCoords.getNorthDeg(text)
            val tgtE = PopupCoords.getEastDeg(text)
            val text2 = PopupCoords.feetToCoordsBoth(c, ipNorth, ipEast)
            val ipN = PopupCoords.getNorthDeg(text2)
            val ipE = PopupCoords.getEastDeg(text2)
            labels["lblTGT_N"] = tgtN; labels["lblTGT_E"] = tgtE
            labels["lblIP_N"] = ipN; labels["lblIP_E"] = ipE
        } catch (e: PopupPlan.PopupError) {
            return   // WDP's error box; the labels stay as far as they got
        }
        if (labels["lblTGT_N"] == "00,00.000") {
            val on = labels["lblTGT_E"] != "000,00.000"
            coordShown["lblTGT_N"] = on; coordShown["lblTGT_E"] = on
        }
        if (labels["lblIP_N"] == "00,00.000") {
            val on = labels["lblIP_E"] != "000,00.000"
            coordShown["lblIP_N"] = on; coordShown["lblIP_E"] = on
        }
    }

    /**
     * `CoordFlow`: the VIP-mode figures — the target, the pull-up point and the two offset aim points, each measured
     * from the IP steerpoint (D1, D2).
     *
     * The three points are laid out from the target in feet with the bearings and ranges the VRP flow has just
     * worked out — the same positions the map draws — and each is measured from the IP by true bearing and range.
     * WDP read the two positions back from lat/lon text east first and measured in that mirrored frame, from an origin
     * of (0,0) for the offsets; see the class notes. Without an IP there is nothing to measure from, and the VIP
     * figures are blank (Save to DTC then has no VIP lines to write).
     */
    private fun coordFlow() {
        if (!loaded) return
        if (!gotTgt || !doVip) {
            strVIPrng = ""; strVIPnm = ""; strVIPhdg = ""; strVIPbrg = ""
            strVIP_PUPrng = ""; strVIP_PUPnm = ""; strVIP_PUPhdg = ""; strVIP_PUPbrg = ""
            strVIP_OA1rng = ""; strVIP_OA1nm = ""; strVIP_OA1hdg = ""; strVIP_OA1brg = ""
            strVIP_OA2rng = ""; strVIP_OA2nm = ""; strVIP_OA2hdg = ""; strVIP_OA2brg = ""
            return
        }
        val pup = AttackGeometry.lay(tgtNorth, tgtEast, dblVRPPUPBearingDeg, intVRPPUPToTgt.toDouble())
        val oa1 = AttackGeometry.lay(tgtNorth, tgtEast, dblOA1BearingDeg, intOA1ToTgt.toDouble())
        val oa2 = AttackGeometry.lay(tgtNorth, tgtEast, dblOA2BearingDeg, intOA2ToTgt.toDouble())
        fun fromIp(n: Double, e: Double, set: (rng: String, nm: String, hdg: String, brg: String) -> Unit) {
            val m = AttackGeometry.bearingRange(ipNorth, ipEast, n, e)
            val rng = bankers(m.rangeFt).toString()
            // the bearing from the IP to the point is what the DED's TBRG takes; the heading is its reciprocal
            set(rng, oneDecimal(m.rangeFt / NM_TO_FT), AttackGeometry.bearingText(m.bearingDeg + 180.0, ::oneDecimal), AttackGeometry.bearingText(m.bearingDeg, ::oneDecimal))
        }
        fromIp(tgtNorth, tgtEast) { r, n, h, b -> strVIPrng = r; strVIPnm = n; strVIPhdg = h; strVIPbrg = b }
        fromIp(pup.first, pup.second) { r, n, h, b -> strVIP_PUPrng = r; strVIP_PUPnm = n; strVIP_PUPhdg = h; strVIP_PUPbrg = b }
        fromIp(oa1.first, oa1.second) { r, n, h, b -> strVIP_OA1rng = r; strVIP_OA1nm = n; strVIP_OA1hdg = h; strVIP_OA1brg = b }
        fromIp(oa2.first, oa2.second) { r, n, h, b -> strVIP_OA2rng = r; strVIP_OA2nm = n; strVIP_OA2hdg = h; strVIP_OA2brg = b }
    }

    // ---------------------------------------------------------------- the flow

    /**
     * `ProgramFlow`, minus the map picture and the file write; the campaign side comes through [setTargets]. OA1 is
     * placed before the VRP, which is laid out from it (WDP ran `VRP()` first, on the previous flow's OA1: D4).
     */
    fun programFlow() {
        if (!loaded) return
        getTAS()
        getTurnRadius()
        bombrange()
        calcLAT()
        oa2()
        oa1()
        vrp()
        vip()
        coordFlow()
        fillLabelsBMS()
        sightDepression()
    }

    private fun trackBarIngrHeight() {
        if (lat) { ingressSliderMin = 1; ingressSliderMax = 20 } else { ingressSliderMin = 10; ingressSliderMax = 120 }
        val v = ingressHeight / 100
        ingressHeight = when {
            v < ingressSliderMin -> ingressSliderMin
            v > ingressSliderMax -> ingressSliderMax
            ingressHeight / 100.0 >= ingressSliderMin && ingressHeight / 100.0 <= ingressSliderMax -> bankers(ingressHeight / 100.0).toInt()
            else -> ingressSliderMin
        } * 100
        changeIngressHeight()
    }

    private fun trackBarReleaseHeight() {
        val p = intPosReleaseHeight
        when {
            p > 10000 -> { releaseSliderMin = 105; releaseSliderMax = 130 }
            p > 8000 -> { releaseSliderMin = 85; releaseSliderMax = 110 }
            p > 6000 -> { releaseSliderMin = 65; releaseSliderMax = 90 }
            p > 5000 -> { releaseSliderMin = 54; releaseSliderMax = 80 }
            p >= 4000 -> { releaseSliderMin = 40; releaseSliderMax = 70 }
            p > 3000 -> { releaseSliderMin = 35; releaseSliderMax = 60 }
            p >= 2000 -> { releaseSliderMin = 25; releaseSliderMax = 50 }
            p > 1000 -> { releaseSliderMin = 15; releaseSliderMax = 30 }
            else -> { releaseSliderMin = 10; releaseSliderMax = 30 }
        }
        val v = releaseHeight / 100
        releaseHeight = when {
            v < releaseSliderMin -> releaseSliderMin
            v > releaseSliderMax -> releaseSliderMax
            releaseHeight / 100.0 >= releaseSliderMin && releaseHeight / 100.0 <= releaseSliderMax -> bankers(releaseHeight / 100.0).toInt()
            else -> releaseSliderMin
        } * 100
        changeRelHeight()
    }

    private fun approachHed() {
        intApproachHeadingDeg = when (turnDirection) {
            "Left" -> attackHeadingDeg + angleOff
            "Right" -> attackHeadingDeg - angleOff
            else -> attackHeadingDeg - angleOff
        }
        if (intApproachHeadingDeg >= 360) intApproachHeadingDeg -= 360
        else if (intApproachHeadingDeg < 0) intApproachHeadingDeg += 360
        labels["lblApproachHedVal"] = intApproachHeadingDeg.toString()
    }

    private fun getTAS() {
        if (ingressCas != 0 && ingressHeight != 0) intIngressTAS = bankers(Performance.tas(ingressCas, ingressHeight)).toInt()
        if (releaseCas != 0 && releaseHeight != 0) {
            intReleaseTAS = bankers(Performance.tas(releaseCas, releaseHeight)).toInt()
            labels["lblTASval"] = intReleaseTAS.toString()
        }
    }

    private fun getTurnRadius() {
        if (pullingGs != 0) intTurnRadius = bankers(Performance.turnRadius(pullingGs, intIngressTAS)).toInt()
    }

    private fun pullUp() {
        intPulldeg = releaseAngleDeg + intDiveAngleDeg
        val rad = PI / 180.0 * intPulldeg
        intPullX = bankers(cos(rad) * intTurnRadius).toInt()
        intPullZ = bankers(sin(rad) * intTurnRadius).toInt()
        intPosReleaseHeight = ingressHeight + intPullZ
    }

    private fun bombrange() {
        if (releaseCas != 0 && releaseHeight != 0) {
            val shot = Ballistics.bombRange(releaseAngleDeg, intReleaseTAS, releaseHeight, Ballistics.Drag.LOW)
            intBombRange = shot.rangeFt
            labels["lblBombrangeVal"] = intBombRange.toString()
            labels["lblBombrangeNmVal"] = oneDecimal((intBombRange.toFloat() / NM_TO_FT_F).toDouble())
        }
    }

    private fun calcLAT() {
        intVertTrackDist = if (releaseHeight > intPosReleaseHeight) releaseHeight - intPosReleaseHeight else 0
        if (intVertTrackDist > 0) {
            if (dblReleaseAngleRad > 0.0) intHorzTrackDist = bankers(intVertTrackDist / tan(dblReleaseAngleRad)).toInt()
        } else {
            intHorzTrackDist = 0
        }
        intMAP = intBombRange + intPullX + intHorzTrackDist
        intVRPPUPToTgt = intMAP
        dblVRPPUPHeadingDeg = wrap360(attackHeadingDeg.toDouble())
        dblVRPPUPBearingDeg = wrap360(dblVRPPUPHeadingDeg - 180.0)
        strVRP_PUPbrg = brgText(dblVRPPUPBearingDeg)
        strVRP_PUPrng = intVRPPUPToTgt.toString()
    }

    /** A bearing for a DED line: one decimal, and never "360.0" (north is 0.0; WDP printed 360.0: D5). */
    private fun brgText(deg: Double): String = AttackGeometry.bearingText(deg, ::oneDecimal)

    private fun oa1() {
        val num = intOA2ToTgt + intLineupToOA2
        // the angle the turn radius subtends at the lineup distance: an arctangent (WDP took the tangent: D4)
        dblDiffrad = atan(intTurnRadius.toDouble() / num.toDouble())
        intOA1ToTgt = bankers(sqrt(num.toDouble().pow(2.0) + intTurnRadius.toDouble().pow(2.0))).toInt()
        dblTurnRad = PI / 180.0 * angleOff
        dblCorr = sin(dblTurnRad)
        dblDiffrad = dblCorr * dblDiffrad
        dblDiffDeg = abs(dblDiffrad * 180.0 / PI)
        // the radius along the run-in, by the cosine of the angle off (WDP: the cosine of its sine, D4)
        intOA1ToTgt = bankers(intOA1ToTgt + cos(dblTurnRad) * intTurnRadius).toInt()
        dblOA1BearingDeg = when (turnDirection) {
            "Right" -> intAttackBearingDeg - dblDiffDeg
            "Left" -> intAttackBearingDeg + dblDiffDeg
            else -> dblOA1BearingDeg
        }
        dblOA1BearingDeg = wrap360(dblOA1BearingDeg)
        dblOA1HeadingDeg = wrap360(dblOA1BearingDeg - 180.0)
        intOA1elv = intVRPPUPelv
        strVRP_OA1brg = brgText(dblOA1BearingDeg)
        strVRP_OA1rng = intOA1ToTgt.toString()
    }

    private fun oa2() {
        intOA2ToTgt = intVRPPUPToTgt + intOA2toVRPPUPfeet
        intLineupToOA2 = 6000
        dblOA2BearingDeg = dblVRPPUPBearingDeg
        strVRP_OA2brg = brgText(dblOA2BearingDeg)
        strVRP_OA2rng = intOA2ToTgt.toString()
    }

    private fun vrp() {
        val num = (90 - angleOff).toDouble()
        val num2 = PI / 180.0 * num
        val num4 = bankers(abs(cos(num2) * intOA1ToTgt)).toInt()
        val num5 = bankers(abs(sin(num2) * intOA1ToTgt) + sin(dblTurnRad) * intTurnRadius).toInt()
        intOA1toVRPfeet = intOA2toVRPPUPfeet
        val num6 = num5 + intOA1toVRPfeet
        if (turnDirection != "None") {
            intVrpToTgt = bankers(sqrt(num4.toDouble().pow(2.0) + num6.toDouble().pow(2.0))).toInt()
        }
        val num7 = if (num4 != num6) atan(num4.toDouble() / num6.toDouble()) else 0.0
        val num8 = abs(num7 * 180.0 / PI)
        val num9 = 90.0 - num8 - num
        dblVRPBearingDeg = when (turnDirection) {
            "Right" -> intAttackBearingDeg - num9
            "Left" -> intAttackBearingDeg + num9
            else -> intAttackBearingDeg - num9
        }
        dblVRPBearingDeg = wrap360(dblVRPBearingDeg)
        dblVRPHeadingDeg = wrap360(dblVRPBearingDeg - 180.0)
        intVRPelv = intVRPPUPelv
        strVRPbrg = brgText(dblVRPBearingDeg)
        strVRPrng = intVrpToTgt.toString()
    }

    /**
     * The ELEV of each line, as the jet takes it (D7): WDP's own figure — the ingress height for the VRP, the pull-up
     * point and OA1, 0 for OA2 and for the VIP-mode offsets — made a height above sea level by the target's elevation
     * ([AttackGeometry.elevation]: 0, ground level in BMS, stays 0), unless the pilot typed one ([elev]). The keys
     * are the nav-offset keys, so a figure typed on a line in VRP mode is not the one shown in VIP mode.
     */
    private fun vip() {
        val t = tgtGround
        fun e(key: String, height: Int) = elev.of(key, AttackGeometry.elevation(height, t)).toString()
        strVRPelv = e("VRP", intVRPelv)
        strVRP_PUPelv = e("VRPPUP", intVRPPUPelv)
        strVRP_OA1elv = e("OA1_2", intOA1elv)
        strVRP_OA2elv = e("OA2_2", intOA2elv)
        strVIPelv = e("VIP", ingressHeight)
        strVIP_PUPelv = e("VIPPUP", ingressHeight)
        strVIP_OA1elv = e("OA1_1", intOA2elv)
        strVIP_OA2elv = e("OA2_1", intOA2elv)
    }

    private fun nm(feet: String): String = oneDecimal((feet.toDoubleOrNull() ?: 0.0) / NM_TO_FT)

    /**
     * `FillLabelsBMS`: the four DED lines, from the same strings [navOffsets] hands the cartridge — so what the pilot
     * reads is what is saved. WDP filled DEST OA1 with the pull-up point's figures in VRP mode (D3).
     */
    private fun fillLabelsBMS() {
        labels["lblMAPVal"] = intVRPPUPToTgt.toString()
        fun line(prefix: String, wp: String, brg: String, rng: String, elv: String) {
            labels["lbl${prefix}wp"] = wp
            labels["lbl${prefix}brg"] = brg
            labels["lbl${prefix}rng"] = rng
            labels["lbl${prefix}elv"] = elv
            labels["lbl${prefix}nm"] = nm(rng)
        }
        if (ref) {
            // VIP mode: the figures are measured from the IP, and the steerpoint shown is the IP's
            val wp = ipWaypoint.toString()
            line("VIP", wp, strVIPbrg, strVIPrng, strVIPelv)
            line("PUP", wp, strVIP_PUPbrg, strVIP_PUPrng, strVIP_PUPelv)
            line("OA1", wp, strVIP_OA1brg, strVIP_OA1rng, strVIP_OA1elv)
            line("OA2", wp, strVIP_OA2brg, strVIP_OA2rng, strVIP_OA2elv)
        } else {
            val wp = waypoint.toString()
            line("VIP", wp, strVRPbrg, strVRPrng, strVRPelv)
            line("PUP", wp, strVRP_PUPbrg, strVRP_PUPrng, strVRP_PUPelv)
            line("OA1", wp, strVRP_OA1brg, strVRP_OA1rng, strVRP_OA1elv)
            line("OA2", wp, strVRP_OA2brg, strVRP_OA2rng, strVRP_OA2elv)
        }
    }

    private fun sightDepression() {
        val num = (releaseAngleDeg.toFloat() * DTR_F).toDouble()
        val mils = bankers((atan(releaseHeight.toDouble() / intBombRange.toDouble()) + num) * 1000.0)
        labels["lblTargetHUDval"] = if (mils >= 260) "NO" else "YES"
    }

    /**
     * `Delivery()`, which `pnlDelivery_Up`/`_Down` run: WDP's knob has one position that does anything, the
     * low-altitude toss, so either click lands there and re-derives the ingress-height range.
     */
    fun delivery() {
        lat = true
        trackBarIngrHeight()
    }

    // ---------------------------------------------------------------- the map, and Save to DTC

    /** trbZoom: 0..100 (the designer's 5, and 30 once the page has loaded). */
    var zoom = 5
    /** `intZoomFactor`: feet across the map. */
    var zoomFactor = 40000
        private set

    /** `ZoomLevel`: the zoom label and the map's scale. */
    fun zoomLevel() {
        var num = zoom / 10.0
        if (num < 1.0) num = 1.0
        val num2 = 100 - zoom
        labels["lblZoom"] = when {
            num2 == 100 -> "$num2%"
            num2 in 10..99 -> " $num2%"
            else -> "  $num2%"
        }
        zoomFactor = bankers(50000.0 * num).toInt()
    }

    /** btnPPTnr's caption, which is also whether the map numbers the threats (`blnShowPPTnr`). */
    var btnPPTnrText = "Show PPT Nr"
        private set

    /** `ShowPPTnr`: the caption flips, and the map numbers its threats or stops. */
    fun showPPTnr() {
        btnPPTnrText = if (btnPPTnrText == "Show PPT Nr") "Hide PPT Nr" else "Show PPT Nr"
    }

    /** The flight plan `Draw` reads, by steerpoint number − 1 (the campaign table); empty draws no route. */
    var route: List<AttackMap.Route> = emptyList()
    /** The pre-planned threats `Draw` rings. */
    var threats: List<AttackMap.Threat> = emptyList()

    /**
     * `Draw`: the theater map cropped around the target, the route and threats on it, then the attack. With a
     * reference of VIP the run starts at the IP, with VRP at the VRP; either way it runs through OA1, OA2 and the
     * pull-up point to the target, which is the square in the middle. Every point is laid out from the target the
     * way the flow lays it out (`NewPos`), with the same bearings and ranges the DED shows. A missing target is
     * parked at (1,510,000, 1,510,000) as `Calc_LAT` parks it.
     */
    fun mapPicture(whiteMap: Boolean = false): PopupPlan.MapPicture {
        val tn = if (tgtNorth == 0.0) 1510000.0 else tgtNorth
        val te = if (tgtEast == 0.0) 1510000.0 else tgtEast
        val f = AttackMap.frame(tn, te, zoomFactor)
        fun at(bearingDeg: Double, dist: Int): Pair<Int, Int> {
            val p = AttackGeometry.lay(tn, te, bearingDeg, dist.toDouble())
            return f.point(PopupPlan.cint(p.first).toDouble(), PopupPlan.cint(p.second).toDouble())
        }
        val items = ArrayList<PopupPlan.MapItem>()
        val ipSquare = AttackMap.routeAndThreats(f, route, threats, btnPPTnrText == "Hide PPT Nr", whiteMap, items)
        val underlay = items.size
        val ink = if (!whiteMap) "White" else "Black"
        val tgt = f.point(PopupPlan.cint(tn).toDouble(), PopupPlan.cint(te).toDouble())
        val ip = f.point(PopupPlan.cint(ipNorth).toDouble(), PopupPlan.cint(ipEast).toDouble())
        val vrp = at(dblVRPBearingDeg, intVrpToTgt)
        val pup = at(dblVRPPUPBearingDeg, intVRPPUPToTgt)
        val oa1 = at(dblOA1BearingDeg, intOA1ToTgt)
        val oa2 = at(dblOA2BearingDeg, intOA2ToTgt)
        val start = if (ref) ip else vrp
        val c = f.centre
        items += PopupPlan.MapItem.Line(start.first, start.second, oa1.first, oa1.second, ink)
        items += PopupPlan.MapItem.Line(oa1.first, oa1.second, oa2.first, oa2.second, ink)
        items += PopupPlan.MapItem.Line(oa2.first, oa2.second, pup.first, pup.second, ink)
        items += PopupPlan.MapItem.Line(pup.first, pup.second, tgt.first, tgt.second, ink)
        items += PopupPlan.MapItem.Rect(c - 7, c - 7, 14, 14, "LightBlue")
        items += PopupPlan.MapItem.Rect(c - 9, c - 9, 18, 18, "Red")
        items += PopupPlan.MapItem.Ellipse(start.first - 10, start.second - 10, 20, 20, "DodgerBlue")
        items += PopupPlan.MapItem.Ellipse(pup.first - 10, pup.second - 10, 20, 20, "Magenta")
        items += PopupPlan.MapItem.Pie(oa1.first - 22, oa1.second - 38, 45, 45, 67, 45, "Lime")
        items += PopupPlan.MapItem.Pie(oa2.first - 22, oa2.second - 38, 45, 45, 67, 45, "Lime")
        return AttackMap.picture(f, items, underlay, ipSquare)
    }

    /**
     * The points [mapPicture] marks, in feet rather than the map's pixels (see [AttackMap.Plot]): the run from the
     * VRP — or from the IP with VIP as the reference — through OA1, OA2 and the pull-up point to the target. Every
     * point is laid out from the target, VIP or not, because that is how `Draw` lays them out — and the DED's VIP
     * figures are measured from the IP to these same points, so the two agree (`--wdppagetest toss geometry`). Null
     * with no target.
     */
    fun attackPlot(): AttackMap.Plot? {
        if (!gotTgt) return null
        val tn = tgtNorth
        val te = tgtEast
        // the points in feet as laid out, not rounded to whole feet: another map draws them, and the check that the
        // DED's figures land on them should see only the DED's own rounding
        fun at(bearingDeg: Double, dist: Int): Pair<Double, Double> = AttackGeometry.lay(tn, te, bearingDeg, dist.toDouble())
        val tgt = tn to te
        val ip = if (doVip) ipNorth to ipEast else null
        val vrp = at(dblVRPBearingDeg, intVrpToTgt)
        val pup = at(dblVRPPUPBearingDeg, intVRPPUPToTgt)
        val oa1 = at(dblOA1BearingDeg, intOA1ToTgt)
        val oa2 = at(dblOA2BearingDeg, intOA2ToTgt)
        val start = if (ref) ip else vrp
        return AttackMap.Plot(tgt, start, ref, pup, oa1, oa2, ip, listOfNotNull(start, oa1, oa2, pup, tgt))
    }

    /**
     * The VRP these inputs lay out, in feet north and east, in VIP mode too (the flow lays it out whatever the
     * reference), or null with no target: where "IP STPT at the VRP" puts its steerpoint.
     */
    fun vrpPoint(): Pair<Double, Double>? =
        if (!gotTgt) null else AttackGeometry.lay(tgtNorth, tgtEast, dblVRPBearingDeg, intVrpToTgt.toDouble())

    /** `TOSSNavOffsets.Modesel`: 1 with VIP as the reference, 2 with VRP. */
    val navModesel: Int get() = if (ref) 1 else 2

    /**
     * `SaveNavOffsets`: the eight lines — the VIP figures against the IP (steerpoint − 1, or the IP STPT picked: D87),
     * the VRP figures against the target, each blank figure as 0 — from the very strings the DED lines show (D3).
     * Without an IP the VIP lines are zeros, where WDP measured them from (0,0).
     */
    fun navOffsets(): Map<String, PopupPlan.Offset> {
        fun f(s: String) = s.toFloatOrNull() ?: 0f
        fun i(s: String) = s.toDoubleOrNull()?.let { bankers(it).toInt() } ?: 0
        fun o(stpt: Int, brg: String, rng: String, elv: String) = PopupPlan.Offset(stpt, f(brg), i(rng), i(elv))
        val ipWp = ipWaypoint
        return linkedMapOf(
            "VIP" to o(ipWp, strVIPbrg, strVIPrng, strVIPelv),
            "VIPPUP" to o(ipWp, strVIP_PUPbrg, strVIP_PUPrng, strVIP_PUPelv),
            "VRP" to o(waypoint, strVRPbrg, strVRPrng, strVRPelv),
            "VRPPUP" to o(waypoint, strVRP_PUPbrg, strVRP_PUPrng, strVRP_PUPelv),
            "OA1_1" to o(ipWp, strVIP_OA1brg, strVIP_OA1rng, strVIP_OA1elv),
            "OA2_1" to o(ipWp, strVIP_OA2brg, strVIP_OA2rng, strVIP_OA2elv),
            "OA1_2" to o(waypoint, strVRP_OA1brg, strVRP_OA1rng, strVRP_OA1elv),
            "OA2_2" to o(waypoint, strVRP_OA2brg, strVRP_OA2rng, strVRP_OA2elv),
        )
    }

    /**
     * What Save to DTC writes: the four lines of the reference in use (D3) — VIP, VIP pull-up and the IP's two
     * offsets with VIP, the VRP ones with VRP. WDP wrote all eight, so a VRP plan carried VIP lines measured from
     * nowhere; the lines left out keep whatever the cartridge already holds.
     */
    fun savedOffsets(): Map<String, PopupPlan.Offset> = AttackGeometry.selectedOffsets(navOffsets(), ref)
}

/**
 * The two performance helpers the TOSS page calls into `cntPerformance` for: calibrated to true airspeed through
 * a standard atmosphere, and a level turn's radius. Falcas's arithmetic, constants and all.
 */
object Performance {
    /** `cntPerformance.TAS` and `IngressTAS`, which are the same function. */
    fun tas(cas: Int, altitudeFt: Int): Double {
        val altDiffTemp = TossPlan.bankers(altitudeFt / 1000.0).toInt()
        val temperature = 15 - altDiffTemp * 2
        val p0 = 29.92126
        val cs0 = 38.967854 * sqrt(288.15)
        val cs = 38.967854 * sqrt(temperature + 273.15)
        val p = p0 * (1.0 - 6.875585599999999E-06 * altitudeFt).pow(5.2558797)
        val dp = p0 * ((1.0 + 0.2 * (cas / cs0).pow(2.0)).pow(3.5) - 1.0)
        val mach = (5.0 * ((dp / p + 1.0).pow(0.2857142857142857) - 1.0)).pow(0.5)
        return mach * cs
    }

    /** `cntPerformance.TurnRadius`: feet, from G and true airspeed in knots. */
    fun turnRadius(gs: Int, tasKts: Int): Double = when {
        gs > 0 -> (tasKts * 1.69).pow(2.0) / (gs * 32.2)
        else -> 0.0
    }
}
