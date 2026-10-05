package com.bmscompanion.app.data.wdp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Weapon Delivery Planner's High Altitude Dive Bomb page, as a state machine: Falcas's `cntHADB`, ported.
 *
 * Ten sliders (dive angle, release speed, release height, tracking time, ingress speed, G, turn direction, VRP to PUP
 * distance, angle off, attack heading), the compass and angle-off shortcuts, the bomb knob (low/high drag), the three
 * tabs, the Extra Info / Wheel switch, the waypoint box, the Campaign/TE buttons and Save to DTC. Every change runs
 * the chain the original runs — `ChangeX()` → `ProgramFlow()` → the profile picture, the approach heading, true
 * airspeed (release and ingress), the bomb range (a **negative** dive angle into `Bombrange`), the basic values
 * (ground speed, MAP, aim-off distance, the tracking point), the turn radius, the pull-up point, the VRP, the wheel,
 * the DED labels, the HUD check and the nav offsets. The methods keep WDP's names and its order, because the order is
 * part of the answer (see below).
 *
 * What the page reads outside itself is [main] — `fclsMain`'s campaign and TE steerpoint tables, its flight table, the
 * DataCard's Precision flag and the theater's coordinate data — and `Get_Coords` reads it exactly as WDP does.
 *
 * ## WDP's mistakes, fixed (the D-numbers are the plan's and the comparison's, `expected-diffs/hadb.txt`)
 *
 * - **D5: the ingress TAS is worked out after the tracking-point altitude it depends on.** WDP's `GetTAS` read
 *   `intTrackPointAlt` before `BasicValues` set it in the same flow, so the ingress TAS, the turn radius, the pull-up
 *   point, the VRP and the wheel were one change behind, and the same slider positions reached in a different order
 *   gave different figures.
 * - **D4: `VRPPUPCalculation` takes the arctangent** of the turn radius over the distance (the angle it subtends);
 *   WDP took the tangent, a ratio used as an angle — 3.3° of bearing at R/D = 0.5 and 45° off.
 * - **D5: a bearing is never "360.0"**: the right-hand N of the compass printed OA2's bearing as 360.0; it is 0.0.
 * - **D7: elevations for the jet.** WDP gave the VRP, the pull-up point and OA1 their heights above the target as if
 *   it stood at sea level; they are now those heights plus the target steerpoint's elevation, as is the Ingress Alt
 *   readout, and each ELEV can be typed over ([elev]). OA2's 0 is kept: BMS reads 0 as ground level wherever the
 *   point is ([AttackGeometry.elevation]), which is exactly where the aim-off point is.
 * - **D8: the target steerpoint may be 1 to 25** (WDP: 3 to 25, which left out a target on steerpoint 1 or 2).
 *
 * ## Kept exactly, each of which would move the digits if tidied
 *
 * - **The release-height slider's range is re-derived only for a dive angle that is a multiple of five**
 *   (`TrackBarReleaseHeight` has no case for 11..14 and so on): which slider moved last decides the range.
 * - **`dblNMtoFeet` is 6076.21** (the VRP-to-PUP distance and the wheel's nautical miles), while the DED's nm labels use
 *   `clsPhyconst.FT_TO_NM = 0.0001646f`, a float, multiplied as floats.
 * - **The x87.** WDP is a 32-bit program: `Math.Sin/Cos/Tan/Atan` are x87 instructions whose 64-bit-mantissa answer is
 *   used as it is by the next multiplication or division, which rounds once ([PopupX87]).
 * - **Two roundings.** `Math.Round` (and so every `(int)` of a double here, in a `checked` block) is to-even and throws
 *   on an overflow; a `"##0.0"` label is half away from zero on 15 significant digits ([PopupNet.f1]).
 * - **A turn of neither Left nor Right** (only before the page has read its settings) plans as Right, and its picture
 *   is chosen from the turn label's **text**, not the setting.
 * - **The DED's OA1 bearing is the attack bearing and OA2's the attack heading**, both printed from an integer: OA1 is
 *   the release point's ground position, short of the target, and OA2 the aim-off point beyond it, where the dive
 *   line meets the ground (release height ÷ tan(dive) past the release point, the bomb range short of that).
 * - **`Get_Coords` decides "no target" from the target's east alone and "no IP" from the IP's east alone**, keeps the
 *   last IP when the tables give none, parks a missing target at (1,510,000, 1,510,000) and fills the DED **without**
 *   a flow; with Precision set and a source of "None" (or none at all) it returns having cleared the target and
 *   changed nothing else, not even the labels.
 * - **`Setup()`** reads `[HADB]` through VB's conversions. With no Setup.ini at all it reads nothing, and the page plans
 *   from the designer's slider positions and zero-valued fields until something is moved (a first flow full of NaN
 *   bearings, as WDP's is). A saved release height below the range the saved dive angle gives, or a saved waypoint
 *   outside 1..25, throws inside the Load, as it does in WDP, and the page is dead until it is made again.
 * - **`SaveNavOffsets` reads its figures back out of the labels** (`Conversions.ToSingle/ToInteger`), field by field,
 *   and ends with `cntDTC.Profiles()` on every flow, which does nothing unless the DTC page's profile is HADB.
 *
 * ## What is not the program's
 *
 * - **The map** (`Draw`, `picSatView`) is [mapPicture]: the last thing each of its callers does, so nothing on the
 *   page depends on it, and it is drawn when the view asks rather than on every flow. The points WDP keeps for it
 *   (`dblVRP`, `dblPUP`, `dblOA1`, `dblOA2`) are laid out there from the same bearings and ranges.
 * - **`CoordFlow`** turns the coordinate labels back into feet and throws the answer away; for any label the page can
 *   write it neither throws nor opens a message box, so it is a no-op here.
 * - **The pictures are named, not loaded**: [profilePicture] is the file `Profile()` loads from WDP's `Pictures`.
 * - **Save to DTC** hands the offsets to whoever is listening ([onSaveDtc]); WDP's `cntDTC.Profiles()` and
 *   `SaveCallsign_DTC`/`SaveTE_DTC` are the DTC page's and the main form's.
 *
 * Checked against the program itself: `--wdppagetest hadb` replays several thousand sequences of user actions and
 * program events (`tools/wdpref page Hadb`) through the real `cntHADB` and through this, and demands the same text on
 * every label, the same visibility of every control it flips, the same slider ranges, picture, buttons and nav
 * offsets, and the same internal figures.
 */
class HadbPlan {

    companion object {
        /** `dblNMtoFeet`, the page's own (a double, and not `clsPhyconst.NM_TO_FT`). */
        const val NM_TO_FEET = 6076.21
        const val DTR = 0.01745329f
        const val FT_TO_NM = 0.0001646f
        const val KM_TO_FT = 3279.98f

        /** .NET `Math.Round(double)` to an `int` inside `checked`: to even, and an overflow is an exception. */
        internal fun cint(v: Double): Int = PopupPlan.cint(v)

        /** The designer's text of every label the page writes, which it shows until something does. */
        val DESIGNER_TEXT: Map<String, String> = linkedMapOf(
            "lblDiveAngleVal" to "30", "lblRelCasVal" to "300", "lblReleaseHeightVal" to "10000", "lblTrackingTimeVal" to "5",
            "lblIngrCasVal" to "300", "lblGVal" to "5", "lblTurnVal" to "Right", "lblVrpToPupVal" to "5", "lblAngleOffVal" to "30",
            "lblAttackHdgVal" to "5", "lblApproachHedVal" to "120", "lblTASval" to "400", "lblBombrangeVAl" to "1", "lblMAPVal" to "1",
            "lblAODval" to "1", "lblWheelRad" to "120", "lblSlantRangeFeet" to "120", "lblSlantRangeNm" to "120", "lblWheelElv" to "120",
            "lblVRPwp" to "5", "lblVRPbrg" to "1", "lblVRPrng" to "1", "lblVRPelv" to "100", "lblVRPnm" to "1",
            "lblVRPPUPwp" to "5", "lblVRPPUPbrg" to "1", "lblVRPPUPrng" to "1", "lblVRPPUPelv" to "100", "lblPUPnm" to "1",
            "lblVRPOA1wp" to "5", "lblOA1brg" to "1", "lblOA1rng" to "1", "lblOA1elv" to "1", "lblOA1nm" to "1",
            "lblVRPOA2wp" to "5", "lblOA2brg" to "1", "lblOA2rng" to "1", "lblOA2elv" to "0", "lblOA2nm" to "1",
            "lblAdvIngAltVal" to "20000", "lblTargetHUDval" to "YES", "lblZoom" to "100%",
            "lblTGT_N" to "00,00.000", "lblTGT_E" to "000,00.000", "lblTGT_elv" to "0000",
            "lblIP_N" to "00,00.000", "lblIP_E" to "000,00.000", "lblIP_elv" to "0000",
        )

        /** Every control whose Visible the page flips; all start visible. */
        val FLIPPED = listOf(
            "pnlSelections_Up", "pnlSelections_Middle", "pnlSelections_Down", "pnlSelections", "pnlProfile", "pnlDEDData",
            "pnlBomb_Up", "pnlBomb_Down", "pnlExtraInfo_Up", "pnlExtraInfo_Down", "pnlExtraInfo", "pnlTheWheel",
            "lblTGT_N", "lblTGT_E", "lblTGT_elv", "lblIP_N", "lblIP_E", "lblIP_elv",
        )

        /** The `[HADB]` keys `Setup()` reads and `fclsMain` writes back. */
        val INI_KEYS = listOf("Bomb", "DiveAngle", "CAS", "ReleaseHeight", "TrackingTime", "IngressCAS", "PullingGs", "Turn", "VRPtoPUP", "AngleOff", "AttackHdg", "HighLow", "Waypoint")
    }

    /** A WinForms TrackBar's range and value, with its own clamping: the range moves, the value follows. */
    class Bar(min: Int, max: Int, value: Int) {
        var min = min; private set
        var max = max; private set
        var value = value; private set

        fun setMinimum(v: Int) { if (min != v) { var mx = max; if (v > mx) mx = v; setRange(v, mx) } }
        fun setMaximum(v: Int) { if (max != v) { var mn = min; if (v < mn) mn = v; setRange(mn, v) } }
        private fun setRange(a: Int, b0: Int) {
            if (min != a || max != b0) {
                val b = if (a > b0) a else b0
                min = a; max = b
                if (value < min) value = min
                if (value > max) value = max
            }
        }
        /** The Value setter. WinForms throws outside the range; so does this, and the handler ends there. */
        fun set(v: Int) { if (v < min || v > max) throw IllegalArgumentException("Value of '$v' is not valid for 'Value'."); value = v }
        /** Where a user's drag lands: inside the range. */
        fun slide(v: Int) { value = v.coerceIn(min, max) }
    }

    /** `cntHADB.STPT`: X east, Y north, Z the steerpoint's altitude — doubles. */
    class Point3(var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0)

    /** A steerpoint of `tblCampSTPT` / `tblMissionSTPT`: FalconX east, FalconY north, FalconZ altitude (floats), and its action. */
    class Stpt(var falconX: Float = 0f, var falconY: Float = 0f, var falconZ: Float = 0f, var action: Int = 0)

    /** A waypoint of the campaign's flight table: grid cells (km, x east) and an altitude in tens of feet. */
    class GridWp(var gridX: Short = 0, var gridY: Short = 0, var gridZ: Short = 0)

    /** One of the eight offsets `SaveNavOffsets` hands to the DTC page (`Camp_Offset`), written field by field. */
    class Offset(var stpt: Int = 0, var bearing: Float = 0f, var range: Int = 0, var elv: Int = 0)

    /** What the page reads from `fclsMain` (and `cntDataCard`). */
    class Main {
        /** cntDataCard.Precision: the DTC's precision steerpoints rather than the mission file's */
        var precision = false
        val tblCampSTPT = Array(25) { Stpt() }
        val tblMissionSTPT = Array(25) { Stpt() }
        var flightNR = 0
        var selFlightNr = -1
        /** FlightTable[i].waypoints; null before a campaign is read */
        var flightTable: Array<Array<GridWp>>? = null
        var campW = 3358699.5
        var campH = 3358699.5
        var originLat = 0.0
        var originLong = 0.0
        var enableNewTerrain = false
        var tm = PopupCoords.TransverseMercatorMeta()
        /** `tblCampPPT`: the pre-planned threats the map rings */
        var threats: List<AttackMap.Threat> = emptyList()
        /**
         * The ground's elevation at the target (feet) for steerpoint N at (north, east), or null where it is not
         * known: the ELEV figures stand on it (D7). WDP had nothing of the kind on this page; the wiring answers from
         * the cartridge, whose target points BMS writes at ground level.
         */
        var groundElevation: ((stpt: Int, north: Double, east: Double) -> Double?)? = null
    }

    val main = Main()

    // ---------------------------------------------------------------- the controls, with the designer's ranges
    val trbDiveAngle = Bar(10, 45, 10)
    val trbReleaseSpd = Bar(30, 55, 45)
    val trbReleaseHeight = Bar(1, 100, 100)
    val trbTrackingTime = Bar(1, 10, 10)
    val trbIngrSpd = Bar(30, 55, 45)
    val trbG = Bar(2, 6, 6)
    val trbTurn = Bar(0, 1, 1)
    val trbVrpToPup = Bar(1, 5, 5)
    val trbAngleOff = Bar(0, 90, 10)
    val trbHeading = Bar(0, 360, 6)
    val trbZoom = Bar(0, 100, 5)

    /** numWaypoint: 1..25 (WDP's designer range was 3..25: D8) */
    var numWaypoint = 3
        private set

    /** The ELEV figures typed over the page's own (D7), by nav-offset key; a new target forgets them. */
    val elev = AttackGeometry.ElevOverrides()

    /** true: BMS; false: Allied Force (`SetVersion`) */
    var blnVersion = true
        private set
    /** where Get_Coords reads: "Camp", "TE", "Both", "None" (the constructor's), or whatever the DTC page set */
    var strDTC: String? = "None"
    var blnCampTE = false
    /** true: Low Drag, false: High Drag */
    var blnBomb = false
        private set

    var ownVisible = true
        private set
    var hostShown = false
        private set
    val visible: Boolean get() = ownVisible && hostShown
    private var created = false

    /** Setup.ini's `[HADB]` section, key and value per line in the file's order, or null for no Setup.ini at all. */
    var setupIni: List<Pair<String, String>>? = emptyList()

    // ---------------------------------------------------------------- the working state, named as WDP names it
    private var loaded = false
    /** `blnLoadedFlag`: the page's Load ran to its end (a Load that throws leaves the page dead, as in WDP). */
    val isLoaded: Boolean get() = loaded
    private var objC = PopupCoords.CoordData()
    private var strWaypoint: String? = "3"
    private var strIPpoint: String? = "2"
    /**
     * The IP steerpoint the pilot picked in the Planner's IP STPT box, or null for WDP's own rule, the steerpoint
     * before the target (D87). HADB plans from a VRP: the IP is the Coordinates box's, the map's and the zeroed VIP
     * lines' steerpoint. One equal to the target is no IP. Set with [setIpStpt].
     */
    var ipStpt: Int? = null
        private set
    private var strSelections: String? = null
    private var blnMouseClick = true
    private var strBombtype: String? = null
    var strHighLow: String? = null
        private set
    var strTurnDirection: String? = null
        private set
    var blnGotTGT = false
        private set
    var blnDoVIP = false
        private set
    private val decOrig_TGT = Point3()
    private val decOrig_IP = Point3()

    private var intDiveAngleDeg = 0
    private var dblDiveAngleRad = 0.0
    private var intIngressCAS = 0
    private var intIngressTAS = 0
    private var intIngressAlt = 0
    private var intCAS = 0
    private var intTAS = 0
    private var intReleaseHeight = 0
    private var intTrackingTime = 0
    private var intPullingGs = 3
    private var intAttackHeadingDeg = 0
    private var intAttackBearingDeg = 0
    private var intApproachHeadingDeg = 0
    private var intAngleOff = 0
    private var intBombRange = 0
    private var intVRPtoVRPPUPnm = 0
    private var intVRPtoVRPPUPfeet = 0
    private var intGroundSpeed = 0
    private var intHorTrackingDist = 0
    private var intMAP = 0
    private var intAimOffDist = 0
    private var intVertTrackingDist = 0
    private var intTrackPointAlt = 0
    private var intVRPPUPelv = 0
    private var dblTurnRad = 0.0
    private var intTurnRadius = 0
    private var dblDiffrad = 0.0
    private var dblDiffDeg = 0.0
    private var intVRPPUPToTgt = 0
    private var dblVRPPUPnm = 0.0
    private var intVRPToTgt = 0
    private var dblVRPnm = 0.0
    private var intSlantRangeFeet = 0
    private var dblSlantRangeNm = 0.0
    private var dblVRPBearingDeg = 0.0
    private var dblVRPHeadingDeg = 0.0
    private var dblVRPPUPBearingDeg = 0.0
    private var dblVRPPUPHeadingDeg = 0.0
    private var intZoomFactor = 40000
    /** The ground's elevation at the target, from [Main.groundElevation] at the last `Get_Coords` (D7). */
    private var targetGround = 0.0
    /** The ground under the target the ELEVs stand on, feet above sea level (D7); 0 without a target. */
    val targetElevation: Int get() = if (blnGotTGT) kotlin.math.round(targetGround).toInt() else 0

    // ---------------------------------------------------------------- what the page shows

    /** Control name → text, as the original leaves each label (the designer's text until the page writes one). */
    val labels = LinkedHashMap<String, String>(DESIGNER_TEXT)

    /** Control name → its own Visible flag, for the controls the page flips. */
    val shown = LinkedHashMap<String, Boolean>().also { m -> for (n in FLIPPED) m[n] = true }

    /** lblTargetHUDval's BackColor, by WinForms colour name. */
    var hudBack = "Lime"
        private set

    /** The picture `Profile()` loads from WDP's `Pictures` folder, by file name ("" until it has run). */
    var profilePicture = ""
        private set

    var btnPPTnrText = "Show PPT Nr"
        private set
    var btnCampEnabled = true
        private set
    var btnCampBack = "Control"
        private set
    var btnTEEnabled = true
        private set
    var btnTEBack = "Control"
        private set
    var buttonsDisposed = false
        private set
    var btnSaveDTCText = "Save to DTC"
        private set

    /** `fclsMain.HADBNavOffsets.Modesel`: 2, VRP (the page has no VIP mode). */
    var navModesel = 0
        private set

    /** `fclsMain.HADBNavOffsets`: VIP, VIPPUP, VRP, VRPPUP, OA1_1, OA2_1, OA1_2, OA2_2 — what the DTC page writes. */
    val navOffsets = LinkedHashMap<String, Offset>().also { m -> for (k in listOf("VIP", "VIPPUP", "VRP", "VRPPUP", "OA1_1", "OA2_1", "OA1_2", "OA2_2")) m[k] = Offset() }

    /** What Save to DTC called on the DTC page and the main form, in order. */
    val dtcCalls = ArrayList<String>()

    /** Save to DTC: WDP hands [navOffsets] to the DTC page and saves the cartridge; here, to whoever listens. */
    var onSaveDtc: ((HadbPlan) -> Unit)? = null

    /** How many handlers ended on an exception since this plan was made. */
    var errors = 0
        private set

    private fun label(name: String, text: String?) { labels[name] = text ?: "" }

    /** A handler as a click or a scroll runs it: an exception ends that handler, not the page. */
    fun guard(block: () -> Unit) {
        try { block() } catch (e: Exception) { errors++ }
    }

    // ---------------------------------------------------------------- set by the main form

    /** `SetVersion`: fclsMain calls it once, before the page loads. */
    fun setVersion(version: Boolean) {
        blnVersion = version
        if (!blnVersion) {
            buttonsDisposed = true
            btnSaveDTCText = "Copy to DataCard"
        }
    }

    /** `SetCoordData`: the theater's coordinate data, from [main], into the page's own `objC`. */
    fun setCoordData() {
        objC = PopupCoords.CoordData(main.originLat, main.originLong, main.campW, main.campH, main.enableNewTerrain, main.tm)
    }

    /** The page's own Visible, as fclsMain sets it (see `PopupPlan.setVisible`: the same WinForms behaviour). */
    fun setVisible(v: Boolean) {
        if (v == ownVisible) return
        ownVisible = v
        if (!hostShown || !v) return
        if (!created) {
            created = true
            var ok = true
            try { loadEvent() } catch (e: Exception) { errors++; ok = false }
            if (!ok) { ownVisible = false; return }
        }
        guard { programFlow(false) }
    }

    /** fclsMain on screen with the page on it: the page's Load runs (once), then its VisibleChanged runs the flow. */
    fun show() {
        if (hostShown) return
        hostShown = true
        if (!ownVisible) return
        created = true
        guard { loadEvent() }
        guard { if (visible) programFlow(false) }
    }

    /** [setupIni] = [ini], then [show]. */
    fun load(ini: List<Pair<String, String>>?) {
        setupIni = ini
        show()
    }

    // ---------------------------------------------------------------- load: cntHADB_Load

    private fun loadEvent() {
        loaded = false
        setup(setupIni)
        selections()
        bombtype()
        decOrig_TGT.x = 1510000.0
        decOrig_TGT.y = 1510000.0
        loaded = true
        programFlow(false)
        trbZoom.set(30)
        zoomLevel()
    }

    /** `ZoomLevel`: the zoom label and the map's scale. */
    fun zoomLevel() {
        var num = trbZoom.value.toDouble() / 10.0
        if (num < 1.0) num = 1.0
        val num2 = 100 - trbZoom.value
        label("lblZoom", when {
            num2 == 100 -> "$num2%"
            num2 < 100 && num2 > 9 -> " $num2%"
            else -> "  $num2%"
        })
        intZoomFactor = cint(50000.0 * num)
    }

    /** `clsIni.INIRead`: the first line of that key (in any case), trimmed, one pair of quotes off; missing is "". */
    private fun iniRead(ini: List<Pair<String, String>>, key: String): String {
        val raw = ini.firstOrNull { it.first.trim().equals(key, ignoreCase = true) }?.second ?: return ""
        var v = raw.trim()
        if (v.length >= 2 && ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith('\'') && v.endsWith('\'')))) v = v.substring(1, v.length - 1)
        return v
    }

    /** `Setup()`: the page's saved settings, each through its own handler, read as VB reads them. */
    private fun setup(ini: List<Pair<String, String>>?) {
        if (ini == null) return
        val text2 = iniRead(ini, "Bomb")
        val text3 = iniRead(ini, "DiveAngle")
        val text4 = iniRead(ini, "CAS")
        val text5 = iniRead(ini, "ReleaseHeight")
        val text6 = iniRead(ini, "TrackingTime")
        val text7 = iniRead(ini, "IngressCAS")
        val text8 = iniRead(ini, "PullingGs")
        val left = iniRead(ini, "Turn")
        val text9 = iniRead(ini, "VRPtoPUP")
        val text10 = iniRead(ini, "AngleOff")
        val text11 = iniRead(ini, "AttackHdg")
        strHighLow = iniRead(ini, "HighLow")
        val text12 = iniRead(ini, "Waypoint")
        if (text2 != "") {
            blnBomb = try { PopupNet.toBoolean(text2) } catch (e: Exception) { false }
        }
        fun d(s: String) = PopupNet.toDouble(s)
        fun within(v: Double, b: Bar) = v >= b.min.toDouble() && v <= b.max.toDouble()
        if (PopupNet.isNumeric(text3)) {
            if (within(d(text3), trbDiveAngle)) trbDiveAngle.set(PopupNet.toInteger(text3)) else trbDiveAngle.set(30)
        } else trbDiveAngle.set(30)
        changeDiveAngle()
        if (PopupNet.isNumeric(text4)) {
            if (within(d(text4) / 10.0, trbReleaseSpd)) trbReleaseSpd.set(cint(d(text4) / 10.0)) else trbReleaseSpd.set(50)
        } else trbReleaseSpd.set(50)
        changeReleaseSpd()
        if (PopupNet.isNumeric(text5)) {
            if (within(d(text5) / 100.0, trbReleaseHeight)) trbReleaseHeight.set(cint(d(text5) / 100.0)) else trbReleaseHeight.set(30)
        } else trbReleaseHeight.set(30)
        changeReleaseHeight()
        if (PopupNet.isNumeric(text6)) {
            if (within(d(text6), trbTrackingTime)) trbTrackingTime.set(PopupNet.toInteger(text6)) else trbTrackingTime.set(3)
        } else trbTrackingTime.set(3)
        changeTrackingTime()
        if (PopupNet.isNumeric(text7)) {
            if (within(d(text7) / 10.0, trbIngrSpd)) trbIngrSpd.set(cint(d(text7) / 10.0)) else trbIngrSpd.set(50)
        } else trbIngrSpd.set(50)
        changeIngressSpeed()
        if (PopupNet.isNumeric(text8)) {
            if (within(d(text8), trbG)) trbG.set(PopupNet.toInteger(text8)) else trbG.set(3)
        } else trbG.set(3)
        changePullingG()
        trbTurn.set(if (left == "Right") 1 else 0)
        changeTurn()
        if (PopupNet.isNumeric(text9)) {
            if (within(d(text9), trbVrpToPup)) trbVrpToPup.set(PopupNet.toInteger(text9)) else trbVrpToPup.set(3)
        } else trbVrpToPup.set(3)
        changeVrpToPup()
        if (PopupNet.isNumeric(text10)) {
            if (within(d(text10), trbAngleOff)) trbAngleOff.set(PopupNet.toInteger(text10)) else trbAngleOff.set(60)
        } else trbAngleOff.set(60)
        changeAngleOff()
        if (PopupNet.isNumeric(text11)) {
            if (within(d(text11), trbHeading)) trbHeading.set(PopupNet.toInteger(text11)) else trbHeading.set(0)
        } else trbHeading.set(0)
        changeHeading()
        if (strHighLow == "") strHighLow = "Low"
        if (PopupNet.isNumeric(text12)) {
            // numWaypoint.Value = ToDecimal(text): outside 1..25 the box throws, and the Load ends there. A fraction
            // (which only a hand-edited file can hold) is out of the port's scope and is cut to its whole part.
            val v = PopupNet.toDouble(text12)
            if (v < 1.0 || v > 25.0) throw IllegalArgumentException("Value of '$text12' is not valid for 'Value'.")
            setWaypointValue(v.toInt())
        }
    }

    // ---------------------------------------------------------------- the inputs, as the page's events apply them

    /**
     * The waypoint box from the page (its arrows or a typed number): kept in 1..25, every steerpoint of the flight plan
     * (D8; WDP's box stopped at 3, so a target on steerpoint 1 or 2 could not be planned).
     */
    fun setWaypoint(v: Int) = guard { setWaypointValue(v.coerceIn(1, 25)) }

    /** `numWaypoint.Value = v`: ValueChanged, and so `STPTChange`, only when the value moves. */
    private fun setWaypointValue(v: Int) {
        if (v < 1 || v > 25) throw IllegalArgumentException("Value of '$v' is not valid for 'Value'.")
        if (v == numWaypoint) return
        numWaypoint = v
        stptChange()
    }

    /** A slider dragged to [pos] (kept inside its range), then its Scroll handler. */
    fun slide(name: String, pos: Int) {
        val (bar, change) = when (name) {
            "trbDiveAngle" -> trbDiveAngle to ::changeDiveAngle
            "trbReleaseSpd" -> trbReleaseSpd to ::changeReleaseSpd
            "trbReleaseHeight" -> trbReleaseHeight to ::changeReleaseHeight
            "trbTrackingTime" -> trbTrackingTime to ::changeTrackingTime
            "trbIngrSpd" -> trbIngrSpd to ::changeIngressSpeed
            "trbG" -> trbG to ::changePullingG
            "trbTurn" -> trbTurn to ::changeTurn
            "trbVrpToPup" -> trbVrpToPup to ::changeVrpToPup
            "trbAngleOff" -> trbAngleOff to ::changeAngleOff
            "trbHeading" -> trbHeading to ::changeHeading
            "trbZoom" -> trbZoom to ::zoomLevel
            else -> return
        }
        bar.slide(pos)
        guard(change)
    }

    /** `lbl0/30/60/90_Click`: the angle off. */
    fun angleOffShortcut(deg: Int) = guard { trbAngleOff.set(deg); changeAngleOff() }

    /** `lblN/E/S/W/N360_Click`: the attack heading. */
    fun headingShortcut(deg: Int) = guard { trbHeading.set(deg); changeHeading() }

    /** `pnlBomb_Up_Click` (high drag) / `pnlBomb_Down_Click` (low drag). */
    fun chooseBomb(low: Boolean) = guard { blnBomb = low; bombtype() }

    /** `pnlSelections_Up/Middle/Down_MouseClick`: which of the three stacked panels is up; [left] is the button. */
    fun chooseSelection(which: String, left: Boolean) = guard { strSelections = which; blnMouseClick = left; selections() }

    /** `pnlExtraInfo_Up_Click` (show the extra info) / `pnlExtraInfo_Down_Click` (show the wheel). */
    fun chooseExtraInfo(extra: Boolean) {
        if (extra) {
            shown["pnlExtraInfo_Down"] = true; shown["pnlExtraInfo_Up"] = false; shown["pnlExtraInfo"] = true; shown["pnlTheWheel"] = false
        } else {
            shown["pnlExtraInfo_Up"] = true; shown["pnlExtraInfo_Down"] = false; shown["pnlTheWheel"] = true; shown["pnlExtraInfo"] = false
        }
    }

    /** `btnPPTnr_Click`: `ShowPPTnr`, then the map. */
    fun togglePPTnr() {
        btnPPTnrText = if (btnPPTnrText == "Show PPT Nr") "Hide PPT Nr" else "Show PPT Nr"
    }

    /** `btnCamp_Click` / `btnTE_Click`. */
    fun chooseCampTE(camp: Boolean) = guard { blnCampTE = camp; campTE() }

    /**
     * `btnSaveDTC_Click`: the DTC page is told this is the HADB profile, then the main form saves the cartridge. What
     * this page does is [dtcCalls]; the writing is [onSaveDtc]'s.
     */
    fun saveDtc() {
        dtcCalls.add("Profiles(HADB)")
        dtcCalls.add("SaveCallsign_DTC(True)")
        if (dtcCalls.size > 4096) dtcCalls.subList(0, dtcCalls.size - 4096).clear()
        onSaveDtc?.invoke(this)
    }

    fun changeDiveAngle() {
        label("lblDiveAngleVal", trbDiveAngle.value.toString())
        intDiveAngleDeg = trbDiveAngle.value
        trackBarReleaseHeight()
        programFlow(false)
    }

    fun changeReleaseSpd() {
        label("lblRelCasVal", (trbReleaseSpd.value * 10).toString())
        intCAS = trbReleaseSpd.value * 10
        programFlow(false)
    }

    fun changeReleaseHeight() {
        label("lblReleaseHeightVal", (trbReleaseHeight.value * 100).toString())
        intReleaseHeight = trbReleaseHeight.value * 100
        programFlow(false)
    }

    fun changeTrackingTime() {
        label("lblTrackingTimeVal", trbTrackingTime.value.toString())
        intTrackingTime = trbTrackingTime.value
        programFlow(false)
    }

    fun changeIngressSpeed() {
        label("lblIngrCasVal", (trbIngrSpd.value * 10).toString())
        intIngressCAS = trbIngrSpd.value * 10
        programFlow(false)
    }

    fun changePullingG() {
        label("lblGVal", trbG.value.toString())
        intPullingGs = trbG.value
        programFlow(false)
    }

    fun changeTurn() {
        if (trbTurn.value == 0) {
            label("lblTurnVal", "Left"); strTurnDirection = "Left"
        } else {
            label("lblTurnVal", "Right"); strTurnDirection = "Right"
        }
        approachHed()
        programFlow(false)
    }

    fun changeVrpToPup() {
        label("lblVrpToPupVal", trbVrpToPup.value.toString())
        intVRPtoVRPPUPnm = trbVrpToPup.value
        intVRPtoVRPPUPfeet = cint(intVRPtoVRPPUPnm.toDouble() * NM_TO_FEET)
        programFlow(false)
    }

    fun changeAngleOff() {
        label("lblAngleOffVal", trbAngleOff.value.toString())
        intAngleOff = trbAngleOff.value
        programFlow(false)
    }

    fun changeHeading() {
        label("lblAttackHdgVal", trbHeading.value.toString())
        intAttackHeadingDeg = trbHeading.value
        intAttackBearingDeg = intAttackHeadingDeg - 180
        if (intAttackBearingDeg < 0) intAttackBearingDeg += 360
        programFlow(false)
    }

    /** `STPTChange`: the steerpoint in the box is the target, the one before it the IP (or the IP STPT picked, D87). */
    fun stptChange() {
        strWaypoint = numWaypoint.toString()
        strIPpoint = ipStpt?.toString() ?: PopupNet.str(PopupNet.toDouble(strWaypoint) - 1.0)
        getCoords()
    }

    /** The IP STPT box (D87): [n] is the IP from now on, null back to WDP's steerpoint before the target; then STPTChange. */
    fun setIpStpt(n: Int?) = guard {
        ipStpt = n
        stptChange()
    }

    // ---------------------------------------------------------------- the switches

    private fun selections() {
        fun show(up: Boolean, middle: Boolean, down: Boolean, sel: Boolean, prof: Boolean, ded: Boolean) {
            shown["pnlSelections_Up"] = up; shown["pnlSelections_Middle"] = middle; shown["pnlSelections_Down"] = down
            shown["pnlSelections"] = sel; shown["pnlProfile"] = prof; shown["pnlDEDData"] = ded
        }
        when (strSelections) {
            "Selections" -> if (blnMouseClick) { profile(); show(false, true, false, false, true, false) }
            "Profile" -> if (blnMouseClick) show(false, false, true, false, false, true) else show(true, false, false, true, false, false)
            "DED Data" -> if (!blnMouseClick) { profile(); show(false, true, false, false, true, false) }
            else -> show(true, false, false, true, false, false)
        }
    }

    private fun bombtype() {
        if (!blnBomb) {
            shown["pnlBomb_Down"] = true; shown["pnlBomb_Up"] = false
        } else {
            shown["pnlBomb_Up"] = true; shown["pnlBomb_Down"] = false
        }
        programFlow(false)
    }

    private fun trackBarReleaseHeight() {
        when (intDiveAngleDeg) {
            10, 15, 20 -> { trbReleaseHeight.setMinimum(15); trbReleaseHeight.setMaximum(150) }
            25 -> { trbReleaseHeight.setMinimum(20); trbReleaseHeight.setMaximum(150) }
            30, 35, 40 -> { trbReleaseHeight.setMinimum(30); trbReleaseHeight.setMaximum(150) }
            45 -> { trbReleaseHeight.setMinimum(40); trbReleaseHeight.setMaximum(150) }
        }
        val b = trbReleaseHeight
        if (b.value < b.min) b.set(b.min)
        else if (b.value > b.max) b.set(b.max)
        else if (intReleaseHeight.toDouble() / 100.0 >= b.min.toDouble() && intReleaseHeight.toDouble() / 100.0 <= b.max.toDouble()) b.set(cint(intReleaseHeight.toDouble() / 100.0))
        else b.set(b.min)
        changeReleaseHeight()
    }

    /** `CampTE`: the two buttons show which table is read, then the target is fetched again. */
    fun campTE() {
        when (strDTC) {
            "Camp" -> { btnCampEnabled = false; btnCampBack = "Green"; btnTEEnabled = true; btnTEBack = "Silver" }
            "TE" -> { btnCampEnabled = true; btnCampBack = "Silver"; btnTEEnabled = false; btnTEBack = "Green" }
            "Both" -> if (blnCampTE) {
                btnCampEnabled = false; btnCampBack = "Green"; btnTEEnabled = true; btnTEBack = "Silver"
            } else {
                btnTEEnabled = false; btnTEBack = "Green"; btnCampEnabled = true; btnCampBack = "Silver"
            }
            "None" -> { btnCampEnabled = true; btnCampBack = "Silver"; btnTEEnabled = true; btnTEBack = "Silver" }
        }
        getCoords()
    }

    // ---------------------------------------------------------------- the target

    /**
     * `Get_Coords`: the target is steerpoint N and the IP N − 1 — of the DTC's precision steerpoints (the campaign's or
     * the TE's table, by [strDTC]) when the DataCard says Precision, else of the campaign's flight table.
     */
    fun getCoords() {
        if (!loaded) return
        if (!blnVersion) strDTC = "Camp"
        val num2 = numWaypoint - 1
        // the IP: the steerpoint before the target, or the one the pilot picked (D87; the target itself is no IP)
        val num3 = ipStpt?.let { if (it - 1 == num2) -1 else it - 1 } ?: (num2 - 1)
        decOrig_TGT.x = 0.0
        decOrig_TGT.y = 0.0
        if (main.precision) {
            fun read(t: Array<Stpt>) {
                // steerpoint 1 has no steerpoint before it, so no IP (WDP's box never reached 1: D8)
                val ip = t.getOrNull(num3) ?: Stpt()
                decOrig_IP.x = ip.falconX.toDouble(); decOrig_IP.y = ip.falconY.toDouble(); decOrig_IP.z = ip.falconZ.toDouble()
                decOrig_TGT.x = t[num2].falconX.toDouble(); decOrig_TGT.y = t[num2].falconY.toDouble(); decOrig_TGT.z = t[num2].falconZ.toDouble()
            }
            when (strDTC) {
                "Camp" -> { blnCampTE = true; read(main.tblCampSTPT) }
                "TE" -> { blnCampTE = false; read(main.tblMissionSTPT) }
                "Both" -> if (blnCampTE) read(main.tblCampSTPT) else read(main.tblMissionSTPT)
                else -> return   // "None", null or anything else
            }
        }
        if (decOrig_TGT.x == 0.0 && decOrig_TGT.y == 0.0) {
            try {
                if (main.flightNR != 0) {
                    val wps = main.flightTable!![main.selFlightNr]
                    // floats: (float)(GridX + 0.5), then float × float held at double precision into a double field;
                    // steerpoint 1 has no IP before it (D8)
                    val ipWp = wps.getOrNull(num3) ?: GridWp()
                    val num4 = (ipWp.gridX.toDouble() + 0.5).toFloat()
                    val num5 = (ipWp.gridY.toDouble() + 0.5).toFloat()
                    val num6 = ipWp.gridZ.toFloat()
                    val num7 = (wps[num2].gridX.toDouble() + 0.5).toFloat()
                    val num8 = (wps[num2].gridY.toDouble() + 0.5).toFloat()
                    val num9 = wps[num2].gridZ.toFloat()
                    decOrig_IP.x = num4.toDouble() * KM_TO_FT.toDouble()
                    decOrig_IP.y = num5.toDouble() * KM_TO_FT.toDouble()
                    decOrig_IP.z = num6.toDouble() * 10.0
                    decOrig_TGT.x = num7.toDouble() * KM_TO_FT.toDouble()
                    decOrig_TGT.y = num8.toDouble() * KM_TO_FT.toDouble()
                    decOrig_TGT.z = num9.toDouble() * 10.0
                    // a picked IP the flight has no waypoint for is no IP (D87)
                    if (ipStpt != null && wps.getOrNull(num3) == null) { decOrig_IP.x = 0.0; decOrig_IP.y = 0.0; decOrig_IP.z = 0.0 }
                }
            } catch (e: Exception) {
                // caught and ignored, as the original does
            }
        }
        val tgt = listOf("lblTGT_N", "lblTGT_E", "lblTGT_elv")
        val ip = listOf("lblIP_N", "lblIP_E", "lblIP_elv")
        if (decOrig_TGT.x == 0.0) {
            for (n in tgt + ip) shown[n] = false
            blnGotTGT = false
            blnDoVIP = false
            decOrig_TGT.x = 1510000.0
            decOrig_TGT.y = 1510000.0
            if (blnVersion) fillLabelsBMS() else fillLabelsAF()
            return
        }
        if (decOrig_IP.x == 0.0) {
            for (n in tgt) shown[n] = true
            for (n in ip) shown[n] = false
            blnGotTGT = true
            blnDoVIP = false
        } else {
            for (n in tgt + ip) shown[n] = true
            blnGotTGT = true
            blnDoVIP = true
        }
        val coords = PopupCoords.feetToCoordsBoth(objC, decOrig_TGT.y, decOrig_TGT.x)
        val tgtN = PopupCoords.getNorthDeg(coords)
        val tgtE = PopupCoords.getEastDeg(coords)
        val tgtElv = PopupNet.str(abs(PopupCoords.round(decOrig_TGT.z, 0)))
        val coords2 = PopupCoords.feetToCoordsBoth(objC, decOrig_IP.y, decOrig_IP.x)
        val ipN = PopupCoords.getNorthDeg(coords2)
        val ipE = PopupCoords.getEastDeg(coords2)
        val ipElv = PopupNet.str(abs(PopupCoords.round(decOrig_IP.z, 0)))
        elev.target(decOrig_TGT.y, decOrig_TGT.x)
        // the ground under the target (D7), feet above sea level with its sign: typed, BMS's height map, or the cartridge's
        targetGround = main.groundElevation?.invoke(numWaypoint, decOrig_TGT.y, decOrig_TGT.x) ?: 0.0
        label("lblTGT_N", tgtN); label("lblTGT_E", tgtE); label("lblTGT_elv", tgtElv)
        label("lblIP_N", ipN); label("lblIP_E", ipE); label("lblIP_elv", ipElv)
        if (labels["lblTGT_N"] == "00,00.000") {
            val on = labels["lblTGT_E"] != "000,00.000"
            shown["lblTGT_N"] = on; shown["lblTGT_E"] = on
        }
        if (labels["lblIP_N"] == "00,00.000") {
            val on = labels["lblIP_E"] != "000,00.000"
            shown["lblIP_N"] = on; shown["lblIP_E"] = on
        }
        programFlow(false)
    }

    /** The target and IP as the page now holds them (north, east feet), or null. */
    val targetNorthEast: Pair<Double, Double>? get() = if (blnGotTGT) decOrig_TGT.y to decOrig_TGT.x else null
    val ipNorthEast: Pair<Double, Double>? get() = if (blnDoVIP) decOrig_IP.y to decOrig_IP.x else null

    // ---------------------------------------------------------------- the flow

    fun programFlow(force: Boolean) {
        if ((!force && !visible) || !loaded) return
        dblDiveAngleRad = PI / 180.0 * intDiveAngleDeg.toDouble()
        profile()
        approachHed()
        getTAS()
        bombrange()
        basicValues()
        // the ingress TAS from this flow's tracking-point altitude, which BasicValues has just set (D5)
        ingressTAS()
        getTurnRadius()
        vrppupCalculation()
        vrpCalculation()
        theWheel()
        // CoordFlow: the coordinate labels back to feet, and the answer thrown away (see the KDoc)
        if (blnVersion) fillLabelsBMS() else fillLabelsAF()
        sightDepression()
        saveNavOffsets()
    }

    /** `Profile()`: the picture, by the turn label's text. */
    private fun profile() {
        profilePicture = when (labels["lblTurnVal"]) {
            "Left" -> "HADB_Left.jpg"
            "Right" -> "HADB_Right.jpg"
            else -> "HADB_None.jpg"
        }
    }

    private fun approachHed() {
        intApproachHeadingDeg = when (strTurnDirection) {
            "Left" -> intAttackHeadingDeg + intAngleOff
            "Right" -> intAttackHeadingDeg - intAngleOff
            "None" -> intAttackHeadingDeg
            else -> intAttackHeadingDeg - intAngleOff
        }
        if (intApproachHeadingDeg >= 360) intApproachHeadingDeg -= 360
        else if (intApproachHeadingDeg < 0) intApproachHeadingDeg += 360
        label("lblApproachHedVal", intApproachHeadingDeg.toString())
    }

    private fun getTAS() {
        if (intCAS != 0 && intReleaseHeight != 0) {
            intTAS = cint(Performance.tas(intCAS, intReleaseHeight))
            label("lblTASval", intTAS.toString())
        }
    }

    /**
     * The ingress true airspeed, at the tracking-point altitude. WDP worked it out inside `GetTAS`, before
     * `BasicValues` had set that altitude, so it used the previous flow's (D5); it runs after it now.
     */
    private fun ingressTAS() {
        if (intCAS != 0 && intReleaseHeight != 0) intIngressTAS = cint(Performance.tas(intIngressCAS, intTrackPointAlt))
    }

    private fun getTurnRadius() {
        if (intPullingGs != 0) intTurnRadius = cint(Performance.turnRadius(intPullingGs, intIngressTAS))
    }

    private fun bombrange() {
        if (intCAS != 0 && intReleaseHeight != 0) {
            strBombtype = if (blnBomb) "Low" else "High"
            intBombRange = Ballistics.bombRange(-intDiveAngleDeg, intTAS, intReleaseHeight, strBombtype).rangeFt
            label("lblBombrangeVAl", intBombRange.toString())
        }
    }

    private fun checkedAdd(a: Int, b: Int): Int {
        val r = a.toLong() + b.toLong()
        if (r > Int.MAX_VALUE || r < Int.MIN_VALUE) throw PopupPlan.PopupOverflow()
        return r.toInt()
    }

    private fun basicValues() {
        intGroundSpeed = cint(PopupX87.mul(PopupX87.cos(dblDiveAngleRad), intTAS.toDouble()))
        intHorTrackingDist = cint(intGroundSpeed.toDouble() * 1.69 * intTrackingTime.toDouble())
        intMAP = checkedAdd(intBombRange, intHorTrackingDist)
        label("lblMAPVal", intMAP.toString())
        if (dblDiveAngleRad == 0.0) dblDiveAngleRad = 1.0
        intAimOffDist = cint(PopupX87.div(intReleaseHeight.toDouble(), PopupX87.tan(dblDiveAngleRad)) - intBombRange.toDouble())
        label("lblAODval", intAimOffDist.toString())
        intVertTrackingDist = cint(PopupX87.mul(PopupX87.sin(dblDiveAngleRad), intTAS.toDouble() * 1.69 * intTrackingTime.toDouble()))
        intTrackPointAlt = checkedAdd(intReleaseHeight, intVertTrackingDist)
        intAttackBearingDeg = intAttackHeadingDeg - 180
        if (intAttackBearingDeg < 0) intAttackBearingDeg += 360
        else if (intAttackBearingDeg >= 360) intAttackBearingDeg -= 360
    }

    private fun vrppupCalculation() {
        val num = cint(PopupX87.mul(PopupX87.tan(dblDiveAngleRad), intTurnRadius.toDouble()))
        intVRPPUPelv = checkedAdd(intTrackPointAlt, num)
        intVRPPUPToTgt = checkedAdd(intMAP, intTurnRadius)
        dblVRPPUPnm = intVRPPUPToTgt.toDouble() / NM_TO_FEET
        // the angle the turn radius subtends at the pull-up distance: an arctangent (WDP took the tangent: D4)
        dblDiffrad = PopupX87.atan(intTurnRadius.toDouble() / intVRPPUPToTgt.toDouble()).toDouble()
        dblTurnRad = PI / 180.0 * intAngleOff.toDouble()
        dblDiffrad = PopupX87.mul(PopupX87.sin(dblTurnRad), dblDiffrad)
        dblDiffDeg = abs(dblDiffrad * 180.0 / PI)
        dblVRPPUPBearingDeg = when (strTurnDirection) {
            "Right" -> intAttackBearingDeg.toDouble() - dblDiffDeg
            "Left" -> intAttackBearingDeg.toDouble() + dblDiffDeg
            "None" -> intAttackBearingDeg.toDouble()
            else -> intAttackBearingDeg.toDouble() - dblDiffDeg
        }
        if (dblVRPPUPBearingDeg < 0.0) dblVRPPUPBearingDeg += 360.0
        else if (dblVRPPUPBearingDeg >= 360.0) dblVRPPUPBearingDeg -= 360.0
        dblVRPPUPHeadingDeg = dblVRPPUPBearingDeg - 180.0
        if (dblVRPPUPHeadingDeg < 0.0) dblVRPPUPHeadingDeg += 360.0
        else if (dblVRPPUPHeadingDeg >= 360.0) dblVRPPUPHeadingDeg -= 360.0
    }

    private fun vrpCalculation() {
        val num = (90 - intAngleOff).toDouble()
        val num2 = PI / 180.0 * num
        val num4 = cint(abs(PopupX87.mul(PopupX87.cos(num2), intVRPPUPToTgt.toDouble())))
        val num5 = checkedAdd(
            cint(abs(PopupX87.mul(PopupX87.sin(num2), intVRPPUPToTgt.toDouble())) + PopupX87.mul(PopupX87.sin(dblTurnRad), intTurnRadius.toDouble())),
            intVRPtoVRPPUPfeet,
        )
        intVRPToTgt = cint(sqrt(num4.toDouble().pow(2.0) + num5.toDouble().pow(2.0)))
        dblVRPnm = intVRPToTgt.toDouble() / NM_TO_FEET
        val num6 = abs(PopupX87.mul(PopupX87.atan(num4.toDouble() / num5.toDouble()), 180.0) / PI)
        val num7 = 90.0 - num6 - num
        dblVRPBearingDeg = when (strTurnDirection) {
            "Right" -> intAttackBearingDeg.toDouble() - num7
            "Left" -> intAttackBearingDeg.toDouble() + num7
            "None" -> intAttackBearingDeg.toDouble()
            else -> intAttackBearingDeg.toDouble() - num7
        }
        if (dblVRPBearingDeg < 0.0) dblVRPBearingDeg += 360.0
        else if (dblVRPBearingDeg >= 360.0) dblVRPBearingDeg -= 360.0
        dblVRPHeadingDeg = dblVRPBearingDeg - 180.0
        if (dblVRPHeadingDeg < 0.0) dblVRPHeadingDeg += 360.0
        else if (dblVRPHeadingDeg >= 360.0) dblVRPHeadingDeg -= 360.0
    }

    private fun theWheel() {
        intSlantRangeFeet = cint(sqrt(intVRPPUPToTgt.toDouble().pow(2.0) + intVRPPUPelv.toDouble().pow(2.0)))
        dblSlantRangeNm = intSlantRangeFeet.toDouble() / NM_TO_FEET
        label("lblWheelRad", intVRPPUPToTgt.toString())
        label("lblSlantRangeFeet", intSlantRangeFeet.toString())
        label("lblSlantRangeNm", PopupNet.f1(dblSlantRangeNm))
        label("lblWheelElv", intVRPPUPelv.toString())
    }

    /**
     * `FillLabelsBMS`: the DED lines. Bearings are never "360.0" (D5). ELEVs are the heights above the target plus the
     * target steerpoint's elevation, 0 staying ground level, or what the pilot typed (D7); the Ingress Alt readout is
     * the VRP's height the same way, since it is the altitude flown there.
     */
    private fun fillLabels(pupBearing: Double) {
        val wp = numWaypoint.toString()
        val t = if (blnGotTGT) targetGround else 0.0
        fun brg(d: Double) = AttackGeometry.bearingText(d, PopupNet::f1)
        fun e(key: String, height: Int) = elev.of(key, AttackGeometry.elevation(height, t)).toString()
        label("lblVRPwp", wp)
        label("lblVRPbrg", brg(dblVRPBearingDeg))
        label("lblVRPrng", intVRPToTgt.toString())
        label("lblVRPelv", e("VRP", intVRPPUPelv))
        label("lblVRPnm", PopupNet.f1(intVRPToTgt.toFloat() * FT_TO_NM))
        label("lblVRPPUPwp", wp)
        label("lblVRPPUPbrg", brg(pupBearing))
        label("lblVRPPUPrng", intVRPPUPToTgt.toString())
        label("lblVRPPUPelv", e("VRPPUP", intVRPPUPelv))
        label("lblPUPnm", PopupNet.f1(intVRPPUPToTgt.toFloat() * FT_TO_NM))
        label("lblVRPOA1wp", wp)
        label("lblOA1brg", brg(intAttackBearingDeg.toDouble()))
        label("lblOA1rng", intBombRange.toString())
        label("lblOA1elv", e("OA1_2", intReleaseHeight))
        label("lblOA1nm", PopupNet.f1(intBombRange.toFloat() * FT_TO_NM))
        label("lblVRPOA2wp", wp)
        label("lblOA2brg", brg(intAttackHeadingDeg.toDouble()))
        label("lblOA2rng", intAimOffDist.toString())
        label("lblOA2elv", e("OA2_2", 0))
        label("lblOA2nm", PopupNet.f1(intAimOffDist.toFloat() * FT_TO_NM))
        intIngressAlt = AttackGeometry.elevation(intVRPPUPelv, t)
        label("lblAdvIngAltVal", intIngressAlt.toString())
    }

    private fun fillLabelsBMS() = fillLabels(dblVRPPUPBearingDeg)

    /** `FillLabelsAF` (Allied Force): the same, except that the PUP bearing box shows the PUP **heading**. */
    private fun fillLabelsAF() = fillLabels(dblVRPPUPHeadingDeg)

    private fun sightDepression() {
        try {
            val mils = cint(PopupX87.sub(PopupX87.atan(intReleaseHeight.toDouble() / intBombRange.toDouble()), dblDiveAngleRad) * 1000.0)
            if (mils >= 260) { label("lblTargetHUDval", "NO"); hudBack = "Red" } else { label("lblTargetHUDval", "YES"); hudBack = "Lime" }
        } catch (e: Exception) {
            // caught and ignored, as the original does
        }
    }

    /**
     * What the DataCard prints of this page when its profile is HADB (`FillAttackType` reads cntHADB's own fields),
     * and the offsets `SaveNavOffsets` left in `HADBNavOffsets` for the DTC page's `Profiles()`.
     */
    fun toCard(a: DataCardPlan.Attack, into: DataCardPlan.NavOffsets) {
        a.hadbIngressAlt = intIngressAlt; a.hadbIngressCAS = intIngressCAS; a.hadbReleaseHeight = intReleaseHeight; a.hadbCAS = intCAS
        a.hadbAttackHeadingDeg = intAttackHeadingDeg; a.hadbDiveAngleDeg = intDiveAngleDeg; a.hadbPullingGs = intPullingGs
        a.hadbTurnDirection = strTurnDirection ?: ""; a.hadbTargetHud = labels["lblTargetHUDval"] ?: ""
        into.modesel = navModesel
        navOffsets.values.forEachIndexed { i, o -> into.points[i].let { it.stpt = o.stpt; it.bearing = o.bearing; it.range = o.range; it.elv = o.elv } }
    }

    /** `SaveNavOffsets`: the DED's figures read back out of the labels, one field at a time, into `HADBNavOffsets`. */
    private fun saveNavOffsets() {
        val n = navOffsets
        val wp = numWaypoint
        navModesel = 2
        fun zero(o: Offset, stpt: Int) { o.stpt = stpt; o.bearing = 0f; o.range = 0; o.elv = 0 }
        fun fill(o: Offset, brg: String, rng: String, elv: String) {
            o.stpt = wp
            o.bearing = PopupNet.toSingle(labels[brg])
            o.range = PopupNet.toInteger(labels[rng])
            o.elv = PopupNet.toInteger(labels[elv])
        }
        // the VIP lines' steerpoint is the IP: the one before the target, or the IP STPT picked (D87)
        val ip = ipStpt ?: (wp - 1)
        zero(n.getValue("VIP"), ip)
        zero(n.getValue("VIPPUP"), ip)
        fill(n.getValue("VRP"), "lblVRPbrg", "lblVRPrng", "lblVRPelv")
        fill(n.getValue("VRPPUP"), "lblVRPPUPbrg", "lblVRPPUPrng", "lblVRPPUPelv")
        zero(n.getValue("OA1_1"), ip)
        zero(n.getValue("OA2_1"), ip)
        fill(n.getValue("OA1_2"), "lblOA1brg", "lblOA1rng", "lblOA1elv")
        fill(n.getValue("OA2_2"), "lblOA2brg", "lblOA2rng", "lblOA2elv")
        // cntDTC.Profiles(): copies these into the cartridge's offsets only when the DTC page's profile is HADB
    }

    // ---------------------------------------------------------------- the map

    /**
     * `Draw`: the theater map cropped around the target, the route and threats of the table `Get_Coords` reads, then
     * the attack — VRP to the pull-up point, to OA1 (the bomb range back along the attack bearing), to the target,
     * to OA2 (the aim-off distance along the attack heading), the VRP ringed blue, the pull-up point magenta and the
     * two offset aim points marked, the target the square in the middle. The points are laid out from the target
     * with the bearings and ranges the flow left (`NewPos`), as `VRPPUPCalculation`, `VRPCalculation`, `OA1` and
     * `OA2` lay them out.
     */
    fun mapPicture(whiteMap: Boolean = false): PopupPlan.MapPicture {
        val tn = decOrig_TGT.y
        val te = decOrig_TGT.x
        val f = AttackMap.frame(tn, te, intZoomFactor)
        // laid out on true bearings, as the DED lines give them (WDP: NewPos with its float degree-to-radian)
        fun at(brgDeg: Double, dist: Int): Pair<Int, Int> {
            val p = if (tn == 0.0 || te == 0.0) 0.0 to 0.0 else AttackGeometry.lay(tn, te, brgDeg, dist.toDouble())
            return f.point(cint(p.first).toDouble(), cint(p.second).toDouble())
        }
        val table = if (blnCampTE) main.tblCampSTPT else main.tblMissionSTPT
        val route = table.map { AttackMap.Route(it.falconX, it.falconY, it.action) }
        val items = ArrayList<PopupPlan.MapItem>()
        // the threats always (B17): WDP ringed them only with its Campaign table chosen, a choice the Planner no longer has (D87)
        val ipSquare = AttackMap.routeAndThreats(f, route, main.threats, btnPPTnrText == "Hide PPT Nr", whiteMap, items)
        val underlay = items.size
        val ink = if (!whiteMap) "White" else "Black"
        val tgt = if (cint(te) != 0) f.point(cint(tn).toDouble(), cint(te).toDouble()) else 0 to 0
        val vrp = at(dblVRPBearingDeg, intVRPToTgt)
        val pup = at(dblVRPPUPBearingDeg, intVRPPUPToTgt)
        val oa1 = at(intAttackBearingDeg.toDouble(), intBombRange)
        val oa2 = at(intAttackHeadingDeg.toDouble(), intAimOffDist)
        val c = f.centre
        items += PopupPlan.MapItem.Line(vrp.first, vrp.second, pup.first, pup.second, ink)
        items += PopupPlan.MapItem.Line(pup.first, pup.second, oa1.first, oa1.second, ink)
        items += PopupPlan.MapItem.Line(oa1.first, oa1.second, tgt.first, tgt.second, ink)
        items += PopupPlan.MapItem.Line(tgt.first, tgt.second, oa2.first, oa2.second, ink)
        items += PopupPlan.MapItem.Rect(c - 7, c - 7, 14, 14, "LightBlue")
        items += PopupPlan.MapItem.Rect(c - 9, c - 9, 18, 18, "Red")
        items += PopupPlan.MapItem.Ellipse(vrp.first - 10, vrp.second - 10, 20, 20, "DodgerBlue")
        items += PopupPlan.MapItem.Ellipse(pup.first - 10, pup.second - 10, 20, 20, "Magenta")
        items += PopupPlan.MapItem.Pie(oa1.first - 22, oa1.second - 38, 45, 45, 67, 45, "Lime")
        items += PopupPlan.MapItem.Pie(oa2.first - 22, oa2.second - 38, 45, 45, 67, 45, "Lime")
        return AttackMap.picture(f, items, underlay, ipSquare)
    }

    /**
     * The points [mapPicture] marks, in feet rather than the map's pixels (see [AttackMap.Plot]): VRP, pull-up
     * point, OA1, the target and OA2 beyond it, in the order `Draw` joins them. Null with no target.
     */
    fun attackPlot(): AttackMap.Plot? {
        // no target: `Get_Coords` parks the plan on WDP's stand-in point (1510000, 1510000) so the page still draws;
        // that is no attack to hand on — Populate from Planner and the kneeboard's attack page would put it mid-theater
        if (!blnGotTGT) return null
        val tn = decOrig_TGT.y
        val te = decOrig_TGT.x
        if (cint(te) == 0) return null
        // the points in feet as laid out, not rounded to whole feet: another map draws them, and the check that the
        // DED's figures land on them should see only the DED's own rounding
        fun at(brgDeg: Double, dist: Int): Pair<Double, Double> = AttackGeometry.lay(tn, te, brgDeg, dist.toDouble())
        val tgt = tn to te
        val vrp = at(dblVRPBearingDeg, intVRPToTgt)
        val pup = at(dblVRPPUPBearingDeg, intVRPPUPToTgt)
        val oa1 = at(intAttackBearingDeg.toDouble(), intBombRange)
        val oa2 = at(intAttackHeadingDeg.toDouble(), intAimOffDist)
        // HADB has no VIP (WDP's modesel is always VRP), so no IP (B4)
        return AttackMap.Plot(tgt, vrp, false, pup, oa1, oa2, null, listOf(vrp, pup, oa1, tgt, oa2))
    }

    // ---------------------------------------------------------------- what the program keeps

    /** What fclsMain writes to Setup.ini's `[HADB]` on the way out, which [load] reads back next time. */
    fun iniValues(): LinkedHashMap<String, String?> = linkedMapOf(
        "Bomb" to if (blnBomb) "True" else "False",
        "DiveAngle" to intDiveAngleDeg.toString(),
        "CAS" to intCAS.toString(),
        "ReleaseHeight" to intReleaseHeight.toString(),
        "TrackingTime" to intTrackingTime.toString(),
        "IngressCAS" to intIngressCAS.toString(),
        "PullingGs" to intPullingGs.toString(),
        "Turn" to strTurnDirection,
        "VRPtoPUP" to intVRPtoVRPPUPnm.toString(),
        "AngleOff" to intAngleOff.toString(),
        "AttackHdg" to intAttackHeadingDeg.toString(),
        "HighLow" to strHighLow,
        "Waypoint" to numWaypoint.toString(),
    )

    /** The internal state the harness also reads out of the real control, by WDP's field names. */
    internal fun state(): Map<String, Any?> = linkedMapOf(
        "strTurnDirection" to strTurnDirection, "strHighLow" to strHighLow, "strDTC" to strDTC, "strWaypoint" to strWaypoint,
        "blnLoadedFlag" to loaded, "blnBomb" to blnBomb, "blnGotTGT" to blnGotTGT, "blnDoVIP" to blnDoVIP, "blnCampTE" to blnCampTE,
        "intTAS" to intTAS, "intIngressTAS" to intIngressTAS, "intTurnRadius" to intTurnRadius, "intTrackPointAlt" to intTrackPointAlt,
        "intBombRange" to intBombRange, "intZoomFactor" to intZoomFactor,
    )
}
