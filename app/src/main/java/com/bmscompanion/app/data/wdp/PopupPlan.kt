package com.bmscompanion.app.data.wdp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Weapon Delivery Planner's Pop-up page, as a state machine: Falcas's `cntPopUp`, ported.
 *
 * Ten sliders, a waypoint box, an OA2 box, two map check boxes, three rotary switches (reference VIP/VRP, profile
 * Type 1/Type 2, bomb low/high drag), the three tabs, the Campaign/TE buttons and Save to DTC. Every change runs
 * the chain the original runs — `ChangeX()` → `ProgramFlow()` → true airspeed, bomb range, the basic values
 * (ground speed, map, aim-off, climb and angle-off, pull-down, the action point by the law of cosines, the offset
 * angle, the pull heading), the HUD check, the extra point, the offset aim points, the VRP of the chosen profile,
 * the fixed points, the VIP when there is an IP, the DED labels (BMS or Allied Force), the nav offsets, the DTC
 * strings and the map. The methods keep WDP's names and its order, because the order is part of the answer: the
 * release-height slider's range is re-derived **only** from a dive angle that is a multiple of five
 * (`TrackBarReleaseHeight` has no case for 11..14), so which slider moved last decides what the page shows.
 *
 * What the page reads outside itself is [main] — `fclsMain`'s campaign and TE steerpoint tables, its flight table,
 * the DataCard's Precision flag, the terrain, the theater's coordinate data, the map bitmap's size — and
 * `Get_Coords` reads it exactly as WDP does (see [getCoords]).
 *
 * ## WDP's mistakes, fixed (the D-numbers are the plan's and the comparison's, `expected-diffs/popup.txt`)
 *
 * - **D7: the heights stand on the ground under the target.** WDP adds the terrain's height under the target to the
 *   ingress altitude and so to the pull-down, VIP, PUP and OA1 elevations (`GetTerrainHeight`: BMS's own height map).
 *   The app ships no heightmap: the wiring hands in the same cell of BMS's map, read on the PC
 *   ([Main.groundElevation]; 65 ft under steerpoint 3 of the last Korea save, where WDP's Setup.ini kept
 *   `IngressAlt=565` for a 500 ft ingress), or the ground the pilot typed for the target, else the cartridge's target
 *   point, through WDP's own path. And every ELEV can be typed over ([elev]; forum, 2024: "impossible to put manually
 *   the elevations for OA1, OA2, VRP, PUP").
 * - **D64: profile Type 2 lays its VRP and pull-up point out from the height above the target.** `VRP_Type2` takes
 *   `intIngressAlt / tan(climb)` off the pull-up-to-pull-down distance, and `intIngressAlt` carries the ground under
 *   the target: every other height of the profile is measured from the target (the pull-down altitude, the climb from
 *   the ingress altitude to it, where the ground cancels), so only Type 2's run-in moved with the target's elevation —
 *   on the last save's steerpoint 7 (3,396 ft) WDP put the VRP at 46,359 ft and the pull-up at 19,903 ft, where the
 *   same attack on flat ground gives 50,219 and 22,871, and Type 1 does not move at all. Now the ingress height above
 *   the target, as for the rest of the profile.
 * - **D2: `VIP_Type2` measured OA2 from `OA2.Y` and `OA1.X`** — the north of one point and the east of another. Both
 *   profiles now measure OA2 itself.
 * - **D8: the target steerpoint may be 1 to 25** ([WP_MIN]; WDP: 3 to 25, which left out a target on steerpoint 1
 *   or 2). Steerpoint 1 has no IP.
 *
 * Checked and **not** changed: "Place OA2 at the Aim Off Point" puts OA2 on the attack heading, **beyond** the
 * target — which is where the aim-off point is: the bomb leaves the jet the bomb range short of the target, and the
 * dive line from there meets the ground release height ÷ tan(dive) further on, which is `intAimOffDist` past the
 * target.
 *
 * ## Kept exactly, each of which would move the digits if tidied
 *
 * - **The constants are floats** (`NM_TO_FT = 6076.1157f`, `DTR = 0.01745329f`, `RTD = 57.29578f`,
 *   `FEET_PER_METER = 3.27998f`, `KM_TO_FT = 3279.98f`), widened at the point of use.
 * - **Float arithmetic is x87 arithmetic.** WDP is a 32-bit program, and the x86 JIT does float sums and products
 *   on the x87 stack at double precision. A result becomes a float only where something cuts it: a `(float)` cast,
 *   a float field or argument, the boxing of a value handed to `Strings.Format`, or a float local that lives across
 *   a call (the JIT keeps it in memory, as four bytes). Everything else — `(float)a * b` assigned to a double or
 *   handed to `Math.Round(double)`, the turn radius's `(float)TAS * 1.69f`, `Bearing()`'s two locals — is the
 *   double result of float operands ([x87]). Carrying them as floats puts ranges out by a foot on 1 row in 400 and
 *   `Bearing()` out by an ulp one time in five; the test samples `Bearing` 20,000 times on its own to pin this.
 * - **`Math.Sin/Cos/Tan/Atan` are x87 instructions** ([PopupX87]): their answer has a 64-bit mantissa and is used as
 *   it is by the rest of the expression, so `Math.Sin(b) * d` is rounded once, from the extended sine. The float
 *   nearest 3π/2 is where it shows: `NewPos_E` 4,499,437 ft along it lands on a float tie and WDP says -3428948.0
 *   where the double sine gives -3428947.8. `fsin`/`fcos`/`fptan` also reduce the angle with a 66-bit π, which
 *   moves the sine of the float nearest π in its twelfth digit; that is modelled too.
 * - **Three roundings.** `Math.Round` is to-even; a `"##0.0"` label is half-up — on **seven** significant digits
 *   for a float and fifteen for a double, first ([PopupNet]); `Conversions.ToString` prints a float to seven digits,
 *   which is what the VIP bearings and ranges are measured from (the target and IP pass through strings).
 * - **VB reads the strings back**, with VB's rules ([PopupNet.toDouble]): a null string is 0, an empty one throws.
 *   So an OA2 box left *indeterminate* by a Setup.ini holding `OA2atAO=2` gives an empty bearing and every flow
 *   after it stops in `OA_Points`, as it does in WDP.
 * - **The pull-down geometry is a model**: 50 ft of height per degree of dive/climb at 4 G and below, 37.5 above;
 *   the "angle-off" is twice the climb angle; the climb angle is dive + 5 up to 15° and dive + 10 above.
 * - **The table of `num10`** (a pull-up angle by dive and release height) has holes and it keeps them: a dive of
 *   10..14° with a release height from 1,200 to 1,499 ft, or of 25..29° from 3,001 to 3,999 ft, finds no row and
 *   uses 0.
 * - **The speed slider snaps up by one** when it lands off a multiple of five (`trbSpeed.Value++` once), so 453
 *   becomes 454, not 455.
 * - **`Setup()`** reads `[PopUp]` through VB: `IsNumeric` then `ToDouble`/`ToInteger` (hex, "1,000", "(5)" and "5-"
 *   are all numbers); the G is checked against its maximum **unscaled** (`6.5 <= 60`), so "7" passes the check and
 *   then throws on the slider, which ends the Load before `blnLoadedFlag` is set — as does a Turn other than 0 or
 *   1 — and the page is dead until it is made again, as WDP's is. The waypoint is a **Decimal** (`ToDecimal`), so
 *   "4.5" stays 4.5: it prints as "4.5" on the DED, "3.5" is the IP's number, and `Convert.ToInt32` rounds it to
 *   even when a table is read. A Boolean that is not "True"/"False" or a number is `false`.
 * - **`IsNumeric` can throw.** A malformed "&H…" is merely not a number, but "&H-1" (`Convert.ToInt64`'s
 *   ArgumentException) and seventeen hex digits (its OverflowException) escape `IsNumeric` itself, so such a key
 *   ends `Setup()` and the Load with it, and the page stays at its designer text. "∞" (the en-US infinity symbol on
 *   Windows) is a number; the word "Infinity" is not; "-0" and a negative number too small for a double read as +0.
 * - **The page's Load runs once, when its window is made** — when fclsMain first shows it, or, if the page was hidden
 *   then, when it is first made visible ([show], [setVisible]). Until then `ProgramFlow` and `Draw` see a page that
 *   is not Visible and do nothing, and `Setup()` reads Setup.ini as it is at that moment. A Load that throws leaves
 *   the page dead (`blnLoadedFlag` false) for good; showing the form again does not run it again.
 * - **The waypoint box is a NumericUpDown**: a Setup.ini can leave it at 4.5, which it shows as "5"; its arrows add
 *   and take one (4.5 → 5.5), stopping at 1 and 25; typed text is read as `Decimal.Parse` reads it (no exponent, no
 *   parentheses) and ignored when it cannot be, and text the box already shows changes nothing ([numWaypointText]).
 * - **`SaveNavOffsets` ends with `cntDTC.Profiles()`** on every flow, whether or not anything is saved; Save to DTC
 *   names the profile "PopUp" first ([dtcCalls]).
 * - **The ini keeps `intIngressAlt`**, which includes the terrain height, so the next Load reads a height above the
 *   slider's range and falls back to its maximum ([iniValues]).
 * - **`TrackingTimeBox`** caps the tracking time at 5 s from 5,000 ft of release height up, 10 below.
 * - **`Bearing()` compares against `"0.000000"`**, which a float never prints as, so the early return never happens.
 * - **`strVIP_OA2elv` is always `"0"`**, and `VIP_Type1/2` never set the OA2 heading or nm.
 * - **`SaveNavOffsets` turns every empty figure into `"0"`** after the labels are filled, so a VIP figure that was
 *   never computed reads `0` the next time round, not blank.
 * - **`DTC()` hands `lblTargetHUD.Text` to the cartridge page** — the caption "Target visible in the HUD", not the
 *   YES/NO beside it — and the CAS as both ingress and release speed.
 * - **`ChangeVrpToPup` shows its label and "nm" even in VIP mode**, where `Ref()` hides them.
 * - **`FillLabelsAF`** (Allied Force, `blnVersion` false) puts the VRP **heading** in the PUP bearing box; `SetVersion`
 *   takes the Campaign and TE buttons away and renames Save to DTC "Copy to DataCard"; `Get_Coords` then always
 *   reads the campaign table.
 * - **`Get_Coords` decides "no target" from the target's east alone and "no IP" from the IP's east alone**, reads the
 *   IP even when there is no target, parks a missing target at (1,510,000, 1,510,000) for the map, and with
 *   Precision set and a source of "None" (or none at all) returns having **cleared the target** and changed
 *   nothing else, so the flows after it plan against (0, 0) while the page still believes it has a target.
 * - **`FeetToCoords` (old terrain) checks the north against the theater's width**, and the page hides a coordinate
 *   pair only when both halves read zero.
 * - **`Draw` runs only while the Profile tab is up** (or when forced), and it is what writes "IP"/"STPT" and the
 *   map's VIP legend.
 * - **The print document is never printed**: `MakePrintDocument` has no caller in WDP. It is ported ([makePrintDocument])
 *   and checked, as is `WriteTextFile`'s `strDocument`, which nothing ever assigns.
 *
 * ## What is not the program's
 *
 * - **The pictures are named, not loaded**: [profilePicture] is the file `ProfileSwitch` loads from WDP's
 *   `Pictures` folder; WDP's error box for a missing one is the renderer's business.
 * - **The map is a list of what `Draw` draws** ([mapPicture]) over the theater bitmap's crop — every line, box,
 *   circle, pie, triangle and number in WDP's order, with its pen, which the test compares with what the real
 *   `Draw` hands to GDI+; nothing here rasterises it (the page's map view draws it over the app's own theater
 *   map, `ui/screens/wdp/WdpAttackMap.kt`), and saving the picture is the platform's.
 *   WDP also runs `Draw` whenever Windows repaints the page ([paint]).
 * - **The old-terrain height calculator** (`TerrainHeightCalculator` over `terrainDB`) is not ported: with no
 *   terrain database it answers 0, which is what [Main.terrainHeight] answers on that path.
 * - **Save to DTC** hands the offsets to whoever is listening ([onSaveDtc]); WDP's `cntDTC.Profiles()` and
 *   `SaveCallsign_DTC`/`SaveTE_DTC` are the DTC page's and the main form's, not this page's — which of them this
 *   page calls, and in what order, is [dtcCalls].
 * - **WDP's message box** for a missing Setup.ini is counted ([messageBoxes]), not shown.
 *
 * Checked against the program itself: `--wdppagetest popup` replays several thousand sequences of user actions and
 * program events (`tools/wdpref page Popup`) through the real `cntPopUp` and through this, and demands the same
 * text on every label, the same visibility of every panel it flips, the same slider ranges, button states,
 * picture, waypoint box, nav offsets, DTC strings and calls, ini values, print table, message boxes, internal points
 * and every mark on the map, plus direct samples of `Bearing`, `Distance`, `NewPos_N/E`, `ScalePointToMap`,
 * `FeetToRad`/`RadToFeet`, the coordinate strings, the terrain lookup and VB's own string conversions.
 */
class PopupPlan {

    companion object {
        /** The lowest target steerpoint: 1, every steerpoint of the flight plan (WDP's box started at 3: D8). */
        const val WP_MIN = 1
        const val NM_TO_FT = 6076.1157f
        const val DTR = 0.01745329f
        const val RTD = 57.29578f
        const val FEET_PER_METER = 3.27998f
        const val KM_TO_FT = 3279.98f

        /** picSatView's size in the designer: the map is drawn at this many pixels. */
        const val MAP_PX = 435

        /** .NET `Math.Round(double)` to an `int` inside `checked`: to even, and an overflow is an exception. */
        internal fun cint(v: Double): Int {
            if (v.isNaN() || v >= 2147483647.5 || v < -2147483648.5) throw PopupOverflow()   // -2147483648.5 rounds to even, in range
            return TossPlan.bankers(v).toInt()
        }

        /** .NET Framework's `Math.Round(double)` (COMDouble::Round), as a double. */
        internal fun bankersD(x: Double): Double {
            if (x.isNaN() || x.isInfinite()) return x
            if (abs(x) < 9.2e18 && x == x.toLong().toDouble()) return x
            val t = x + 0.5
            var f = floor(t)
            if (f == t && t % 2.0 != 0.0) f -= 1.0
            val neg = x < 0.0 || (x == 0.0 && 1.0 / x < 0.0)
            return if (neg) -abs(f) else abs(f)
        }

        /** .NET `Math.Round(double, 1)`: scaled, to even, scaled back. */
        internal fun round1(v: Double): Double = if (abs(v) < 1e16) TossPlan.bankers(v * 10.0).toDouble() / 10.0 else v

        /**
         * A float operand as the x86 JIT holds it: WDP is a 32-bit program, its float arithmetic runs on the x87
         * stack at double precision, and a result is only cut back to a float where the code says so — a `(float)`
         * cast, a float field or argument, a boxed value handed to `Strings.Format`. So `(float)a * b` assigned to a
         * double, or passed to `Math.Round(double)`, is the double product of the two floats.
         */
        internal fun x87(f: Float): Double = f.toDouble()

        /** VB `Conversions.ToInteger(String)`. */
        internal fun toInteger(s: String?): Int = PopupNet.toInteger(s)

        /** The designer's text of every label the page writes, which it shows until something does. */
        val DESIGNER_TEXT: Map<String, String> = linkedMapOf(
            "lblIngressHeightVal" to "300", "lblDiveAngleVal" to "30", "lblCasVal" to "300", "lblReleaseHeightVal" to "10000",
            "lblTrackingTimeVal" to "5", "lblGVal" to "5", "lblTurnVal" to "Right", "lblAttackHdgVal" to "5", "lblVrpToPupVal" to "5",
            "lblTASval" to "400", "lblBombTime" to "1", "lblGsVal" to "400", "lblSpeedExpl" to "(at 2000' and dive angle 15)",
            "lblClimbAngleVal" to "1", "lblAngleOffVal" to "1", "lblPulldownAltVal" to "1", "lblAODval" to "1", "lblOffsetAngleVal" to "1",
            "lblPullHeadingVal" to "1", "lblTargetHUDval" to "YES", "lblVRPtoPUPdist" to "VRP to PUP dist", "lblDEDvip_1" to "VIP-TO-TGT",
            "lblDEDvip_2" to "VIP", "lblDEDpup_1" to "VIP-TO-PUP", "lblDEDpup_2" to "VIP", "lblVIPwp" to "4", "lblVIPbrg" to "1",
            "lblVIPrng" to "1", "lblVIPelv" to "100", "lblVIPnm" to "1", "lblPUPwp" to "4", "lblPUPbrg" to "1", "lblPUPrng" to "1",
            "lblPUPelv" to "1", "lblPUPnm" to "1", "lblOA1wp" to "4", "lblOA1brg" to "1", "lblOA1rng" to "1", "lblOA1elv" to "100",
            "lblOA1nm" to "1", "lblOA2wp" to "4", "lblOA2brg" to "1", "lblOA2rng" to "1", "lblOA2elv" to "0", "lblOA2nm" to "1",
            "lblZoom" to "100%", "lblTGT_N" to "00,00.000", "lblTGT_E" to "000,00.000", "lblTGT_elv" to "0000", "lblIP_N" to "00,00.000",
            "lblIP_E" to "000,00.000", "lblIP_elv" to "0000", "lblWP" to "STPT", "lblVIP" to "VIP",
        )

        /** Every control whose Visible the page flips; all start visible. */
        val FLIPPED = listOf(
            "pnlRefUp", "pnlRefDown", "pnlDED_Ref_Up", "pnlDED_Ref_Down", "pnlProfile_Up", "pnlProfile_Down",
            "pnlProfile2_Up", "pnlProfile2_Down", "pnlBomb_Up", "pnlBomb_Down", "pnlBlocked_1", "pnlBlocked_3",
            "trbVrpToPup", "lblVrpToPupVal", "lblVIPtoPUPnm", "lblTGT_N", "lblTGT_E", "lblTGT_elv", "lblIP_N", "lblIP_E",
            "lblIP_elv", "pnlSelections", "pnlProfile", "pnlDEDData", "pnlSelections_Up", "pnlSelections_Middle",
            "pnlSelections_Down",
        )

        /** The `[PopUp]` keys `Setup()` reads and `fclsMain` writes back. */
        val INI_KEYS = listOf("Ref", "Profile", "Bomb", "IngressAlt", "DiveAngle", "CAS", "ReleaseHeight", "TrackingTime", "PullingGs", "Turn", "AttackHdg", "VIPtoPUP", "OA2atAO", "Waypoint")
    }

    /** Anything the original throws; a handler that meets one stops where it is, as WDP's does. */
    open class PopupError(message: String) : RuntimeException(message)

    /** What a checked conversion throws in the original. */
    class PopupOverflow : PopupError("Arithmetic operation resulted in an overflow.")

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

    /** `cntPopUp.FalconPoint` (and the map's PointFs): X is east, Y is north, Z the steerpoint's altitude — floats. */
    class FalconPoint(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f)

    /** One of the eight offsets `SaveNavOffsets` hands to the DTC page (`Camp_Offset`). */
    data class Offset(val stpt: Int = 0, val bearing: Float = 0f, val range: Int = 0, val elv: Int = 0)

    /** A steerpoint of `tblCampSTPT` / `tblMissionSTPT`: FalconX east, FalconY north, FalconZ altitude, the action. */
    class Stpt(var falconX: Float = 0f, var falconY: Float = 0f, var falconZ: Float = 0f, var action: String? = null)

    /** A pre-planned threat of `tblCampPPT` / `tblMissionPPT`, for the map. */
    class Ppt(var falconX: Float = 0f, var falconY: Float = 0f, var falconRNG: Float = 0f, var code: String? = null)

    /** A waypoint of the campaign's flight table: grid cells (km, x east) and an altitude in tens of feet. */
    class GridWp(var gridX: Short = 0, var gridY: Short = 0, var gridZ: Short = 0)

    /**
     * What the page reads from `fclsMain` (and `cntDataCard`), and what it hands back. The app fills this from its
     * own mission (see `PopupWiring`); the test fills it as the harness fills the real one.
     */
    class Main {
        var blnLoaded = true
        /** cntDataCard.Precision: the DTC's precision steerpoints rather than the mission file's */
        var precision = false
        val tblCampSTPT = Array(25) { Stpt() }
        val tblMissionSTPT = Array(25) { Stpt() }
        var flightNR = 0
        var selFlightNr = -1
        /** FlightTable[i].waypoints; null before a campaign is read */
        var flightTable: Array<Array<GridWp>>? = null
        var terrainLoaded = false
        var newTerrainElvLoaded = false
        /** NewTerrain\Heightmaps\Heightmap.raw under the theater's terrain folder, or null where there is none */
        var heightmap: ByteArray? = null
        var campW = 3358699.5
        var campH = 3358699.5
        var originLat = 0.0
        var originLong = 0.0
        var enableNewTerrain = false
        var tm = PopupCoords.TransverseMercatorMeta()
        var feetPerPixel = 1639.989990234375
        /** bitThrMap's size */
        var mapWidth = 2048
        var mapHeight = 2048
        var whiteMap = false
        val tblCampPPT = Array(15) { Ppt() }
        val tblMissionPPT = Array(16) { Ppt() }
        /** fclsMain.BackColor, by WinForms name */
        var backColor = "ffe0e0e0"
        /** cntDTC.blnMissionDtcLoaded: the DTC page holds a TE mission's cartridge (Save to DTC then saves both) */
        var missionDtcLoaded = false
        /**
         * The ground's elevation at the target (feet above sea level) for steerpoint N at (north, east), or null where
         * it is not known — used where WDP would read its terrain and has none loaded (D7). The app ships no heightmap;
         * the wiring answers with what the pilot typed for the target, else BMS's own height map read on the PC (the
         * cell WDP's reader takes), else the cartridge's target point, which BMS writes at ground level.
         */
        var groundElevation: ((stpt: Int, north: Double, east: Double) -> Double?)? = null

        /** `fclsMain.GetTerrainHeight(feetNorth, feetEast)`. */
        fun terrainHeight(feetNorth: Float, feetEast: Float): Float {
            if (!terrainLoaded) return 0f
            if (!newTerrainElvLoaded) return 0f   // TerrainHeightCalculator without a terrain database: caught, 0
            return PopupCoords.readNewTerrainElvLoc(heightmap, campH, campW, feetNorth, feetEast).toFloat()
        }
    }

    val main = Main()

    // ---------------------------------------------------------------- the controls, with the designer's ranges
    val trbIngressHeight = Bar(1, 5, 3)
    val trbDiveAngle = Bar(10, 45, 10)
    val trbSpeed = Bar(300, 550, 300)
    val trbReleaseHeight = Bar(1, 100, 100)
    val trbTrackingTime = Bar(1, 10, 10)
    val trbG = Bar(20, 60, 20)
    val trbTurn = Bar(0, 1, 1)
    val trbHeading = Bar(0, 360, 6)
    val trbVrpToPup = Bar(1, 5, 5)
    val trbZoom = Bar(0, 100, 5)
    private var intZoomFactor = 40000
    /** numWaypoint: 1..25 (WDP's 3..25: D8), a Decimal */
    var numWaypoint: PopupNet.Dec = PopupNet.Dec.of(3)
        private set

    /** The ELEV figures typed over the page's own (D7), by nav-offset key; a new target forgets them. */
    val elev = AttackGeometry.ElevOverrides()
    /**
     * What the box shows: its value to no places ("F0", half away from zero — 4.5 shows "5"), rewritten whenever the
     * value changes or typed text is checked; typed text that cannot be read, or none, stays as typed only when empty
     * or a lone "-".
     */
    var numWaypointText: String = "3"
        private set
    /** chbOA2.CheckState: 0 unchecked, 1 checked, 2 indeterminate (only a Setup.ini can make it so) */
    var chbOA2State = 0
        private set
    val chbOA2: Boolean get() = chbOA2State != 0
    var chbShowPPT = true
        private set
    var chbShowPPTNr = false
        private set

    // ---------------------------------------------------------------- the switches
    /** true: VIP, false: VRP */
    var blnRef = false
    /** true: Type 1, false: Type 2 (every switch starts false, as a field does in .NET, until Setup reads the ini) */
    var blnProfile = false
    /** true: Low Drag, false: High Drag */
    var blnBomb = false
    var blnTurn = false
        private set
    /** true: BMS; false: Allied Force (`SetVersion`) */
    var blnVersion = true
        private set
    /** where Get_Coords reads: "Camp", "TE", "Both", "None" (the constructor's), or whatever the DTC page set */
    var strDTC: String? = "None"
    var blnCampTE = false
    /** The page's own Visible flag (the one fclsMain flips as its tabs change; [setVisible]). */
    var ownVisible = true
        private set
    /** fclsMain is on screen ([show]). Until it is, nothing on the page is Visible and the page's Load has not run. */
    var hostShown = false
        private set
    /** `Control.Visible`: the page's own flag and its form's. This is what `ProgramFlow` and `Draw` test. */
    val visible: Boolean get() = ownVisible && hostShown
    /** The page has its window, so its Load has run — once, whether or not it finished. */
    private var created = false

    /**
     * Setup.ini's `[PopUp]` section as the file holds it, key and value per line in the file's order (a key written
     * twice: Windows reads the first), or null for no Setup.ini at all. Read when the page's Load runs, not before.
     */
    var setupIni: List<Pair<String, String>>? = emptyList()

    // ---------------------------------------------------------------- the working state, named as WDP names it
    private var loaded = false
    // the designer sets numWaypoint to 3 with its ValueChanged already wired, so STPTChange has run once before Load
    private var strWaypoint: String? = "3"
    private var strIPpoint: String? = "2"
    /**
     * The IP steerpoint the pilot picked in the Planner's IP STPT box, or null for WDP's own rule, the steerpoint
     * before the target (WDP has no such box: D87). STPTChange, Get_Coords and SaveNavOffsets take it wherever WDP
     * takes N − 1; one equal to the target is no IP. Set with [setIpStpt].
     */
    var ipStpt: Int? = null
        private set
    private var decOrig_TGT = FalconPoint()
    private var decOrig_IP = FalconPoint()
    private var targetElv = 0
    /** `TargetElv`: the ground under the target the page plans on, feet above sea level (D7). */
    val targetElevation: Int get() = targetElv
    var blnGotTGT = false
        private set
    var blnDoVIP = false
        private set
    private var strTGT_STPT_deg = ""
    private var strIP_STPT_deg = ""
    private var objC = PopupCoords.CoordData()
    private var strBomb: String? = null
    private var strBombtype = ""
    private var strProfile: String? = null
    private var strSelections: String? = null
    private var blnMouseClick = true
    private var strDocument: String? = null

    private var intIngressAlt = 100
    private var intClimbAngleDeg = 0
    private var intDiveAngleDeg = 30
    private var dblDiveAngleRad = 0.0
    private var intCAS = 500
    private var intTAS = 550
    private var intReleaseHeight = 3000
    private var intTrackingTime = 5
    private var dblPullingGs = 3.0
    private var intAttackHeadingDeg = 0
    private var dblAttackHeadingRad = 0.0
    private var intAttackBrgDeg = 0
    private var dblAttackBrgRad = 0.0
    private var dblApexbrgDeg = 0.0
    private var dblApexbrgRad = 0.0
    private var intBombRange = 0
    private var intAimOffDist = 0
    private var intGroundSpeed = 500
    private var intMAP = 0
    private var intPullDownAlt = 0
    private var dblPullHeading = 0.0
    private var dblPullBrg = 0.0
    private var dblClimbAngleRad = 0.0
    private var dblAngleOffDeg = 0.0
    private var dblAngleOffRad = 0.0
    private var dblOffsetAngleDeg = 0.0
    private var dblOffsetAngleRad = 0.0
    private var intTurnRadius = 0
    private var intAtttoTgtDist = 0
    private var intExtraToTgt = 0
    private var intAtttoExtraDist = 0
    private var intPDPtoExtraDist = 0
    private var intIngressAltDist = 0
    private var intPDPtoTargetDist = 0
    private var intPupToPDP = 0
    private var intActionToTgt = 0
    private var dblOA1toTGTbrg = 0.0
    private var dblOA1toTGTbrgRad = 0.0
    private var dblOA2toTGTbrg = 0.0
    private var dblOA2toTGTbrgRad = 0.0
    private var dblExtraHedDeg = 0.0
    private var dblExtraBrgDeg = 0.0
    private var intVIPtoPUPswitch = 0

    // the map's points, kept because the VIP is measured to OA1 and OA2 through them
    private val map = FalconPoint()
    private val aod = FalconPoint()
    private val apex = FalconPoint()
    private val oa1 = FalconPoint()
    private val oa2 = FalconPoint()
    private val dblVRP = DoubleArray(3)
    private val dblT1_VIP = DoubleArray(2)
    private val dblT1_VIPPUP = DoubleArray(2)
    private val dblT1_VRP = DoubleArray(2)
    private val dblT1_VRPPUP = DoubleArray(2)
    private val dblT2_VIP = DoubleArray(2)
    private val dblT2_VIPPUP = DoubleArray(2)
    private val dblT2_VRP = DoubleArray(2)
    private val dblT2_VRPPUP = DoubleArray(2)

    private var strVIPbrg: String? = null
    private var strVIPrng: String? = null
    private var strVIPelv: String? = null
    private var strVIPhdg: String? = null
    private var strVIPnm: String? = null
    private var strVIP_PUPbrg: String? = null
    private var strVIP_PUPrng: String? = null
    private var strVIP_PUPelv: String? = null
    private var strVIP_PUPhdg: String? = null
    private var strVIP_PUPnm: String? = null
    private var strVIP_OA1rng: String? = null
    private var strVIP_OA1brg: String? = null
    private var strVIP_OA1elv: String? = null
    private var strVIP_OA1nm: String? = null
    private var strVIP_OA1hdg: String? = null
    private var strVIP_OA2rng: String? = null
    private var strVIP_OA2brg: String? = null
    private var strVIP_OA2elv: String? = null
    private var strVRPbrg: String? = null
    private var strVRPrng: String? = null
    private var strVRPelv: String? = null
    private var strVRPhdg: String? = null
    private var strVRPnm: String? = null
    private var strVRP_PUPbrg: String? = null
    private var strVRP_PUPhdg: String? = null
    private var strVRP_PUPrng: String? = null
    private var strVRP_PUPnm: String? = null
    private var strVRP_PUPelv: String? = null
    private var strVRP_OA1rng: String? = null
    private var strVRP_OA1brg: String? = null
    private var strVRP_OA1elv: String? = null
    private var strVRP_OA1hdg: String? = null
    private var strVRP_OA2rng: String? = null
    private var strVRP_OA2brg: String? = null
    private var strVRP_OA2elv: String? = null
    private var strVRP_PullBrg: String? = null

    // ---------------------------------------------------------------- what the page shows

    /** Control name → text, as the original leaves each label (the designer's text until the page writes one). */
    val labels = LinkedHashMap<String, String>(DESIGNER_TEXT)

    /** Control name → its own Visible flag, for the controls the page flips. */
    val shown = LinkedHashMap<String, Boolean>().also { m -> for (n in FLIPPED) m[n] = true }

    /** lblTargetHUDval's BackColor and lblVIP's ForeColor, by WinForms colour name. */
    var hudBack = "Lime"
        private set
    var vipLegendFore = "DodgerBlue"
        private set

    /** The Campaign and TE buttons (`CampTE`), and Save to DTC's caption and size (`SetVersion`). */
    var btnCampEnabled = true
        private set
    var btnCampBack = "Control"
        private set
    var btnTEEnabled = true
        private set
    var btnTEBack = "Control"
        private set
    /** SetVersion(false) disposes the two buttons */
    var buttonsDisposed = false
        private set
    var btnSaveDTCText = "Save to DTC"
        private set
    var btnSaveDTCSize = 108 to 30
        private set

    /** `fclsMain.PopUpNavOffsets.Modesel`: 1 VIP, 2 VRP. */
    var navModesel = 0
        private set

    /** `fclsMain.PopUpNavOffsets`: VIP, VIPPUP, VRP, VRPPUP, OA1_1, OA2_1, OA1_2, OA2_2 — what the DTC page writes. */
    val navOffsets = LinkedHashMap<String, Offset>().also { m -> for (k in listOf("VIP", "VIPPUP", "VRP", "VRPPUP", "OA1_1", "OA2_1", "OA1_2", "OA2_2")) m[k] = Offset() }

    /** What `DTC()` hands to the DTC page: `strIngrHgt` … `strTGTHUD`. */
    val dtcStrings = LinkedHashMap<String, String>()

    /** What Save to DTC called on the DTC page and the main form, in order ("Profiles(PopUp)", "SaveCallsign_DTC(True)" …). */
    val dtcCalls = ArrayList<String>()

    /** One call into the DTC page or the main form; the last 4,096 are kept. */
    private fun dtcCall(s: String) {
        dtcCalls.add(s)
        if (dtcCalls.size > 4096) dtcCalls.removeAt(0)
    }

    /** `cntDTC.strProfile` as Save to DTC leaves it. */
    var dtcProfile: String? = null
        private set

    /** Save to DTC: WDP hands [navOffsets] to the DTC page and saves the cartridge; here, to whoever listens. */
    var onSaveDtc: ((PopupPlan) -> Unit)? = null

    /** The profile picture `ProfileSwitch` loads from WDP's `Pictures` folder, by file name. */
    var profilePicture = ""
        private set

    /** What the last `Draw` drew, or null when it has not run. */
    var mapPicture: MapPicture? = null
        private set

    private fun label(name: String, text: String?) { labels[name] = text ?: "" }

    // ---------------------------------------------------------------- the handlers a user reaches

    /** `SetVersion`: fclsMain calls it once, before the page loads. */
    fun setVersion(version: Boolean) {
        blnVersion = version
        if (!blnVersion) {
            buttonsDisposed = true
            btnSaveDTCText = "Copy to DataCard"
            btnSaveDTCSize = 108 to 40
        }
    }

    /** `SetCoordData`: the theater's coordinate data, from [main], into the page's own `objC`. */
    fun setCoordData() {
        objC = PopupCoords.CoordData(main.originLat, main.originLong, main.campW, main.campH, main.enableNewTerrain, main.tm)
    }

    /**
     * The page's own Visible, as fclsMain sets it. What follows is WinForms': before the form is on screen only the
     * flag moves; hiding runs nothing (`cntPopUp_VisibleChanged` finds the page not Visible); showing a page whose
     * window does not exist yet makes it — which is when its Load runs, the first time and only then — and then
     * `VisibleChanged` runs the flow. A Load that throws on this path backs the flag out again (`SetVisibleCore`),
     * and `VisibleChanged` does not run.
     */
    fun setVisible(v: Boolean) {
        if (v == ownVisible) return
        ownVisible = v
        if (!hostShown || !v) return
        if (!created) {
            created = true
            if (!attempt { loadEvent() }) { ownVisible = false; return }
        }
        guard { programFlow(false) }
    }

    // ---------------------------------------------------------------- load: cntPopUp_Load

    /**
     * fclsMain comes on screen with the page on it (the harness's `load`). If the page itself is visible, that is
     * when its window is made and so when `cntPopUp_Load` runs — once; a hidden page waits for [setVisible] — and
     * then the form's `VisibleChanged` reaches the page, which runs the flow. An exception inside the Load is
     * .NET's error dialog: the rest of the Load is skipped and the page never becomes loaded, but the form still
     * becomes visible and `VisibleChanged` still runs, as a second handler (so a flow that throws counts twice).
     * Showing a form that is already showing does nothing.
     */
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

    /**
     * A new mission (the app's, not WDP's): the page's Load again on Setup()'s own fallbacks — what a page with no
     * saved `[PopUp]` section starts with — without counting WDP's "file does not exist" box, and no typed ELEV.
     */
    fun resetToDefaults() {
        val boxes = messageBoxes
        setupIni = null
        ipStpt = null
        if (created) guard { loadEvent() }
        messageBoxes = boxes
        elev.clear()
    }

    /** `cntPopUp_Load`: Setup (reading [setupIni]), Profile, Ref, Selections, then the flow and the zoom. */
    private fun loadEvent() {
        loaded = false
        setup(setupIni)
        profile()
        ref()
        selections()
        loaded = true
        programFlow(false)
        trbZoom.set(30)
        zoomLevel()
    }

    /** `ZoomLevel`: the zoom label and the map's scale, then the map. */
    fun zoomLevel() {
        var num = trbZoom.value.toDouble() / 10.0
        if (num < 1.0) num = 1.0
        val num2 = 100 - trbZoom.value
        label("lblZoom", when {
            num2 == 100 -> "$num2%"
            num2 in 10..99 -> " $num2%"
            else -> "  $num2%"
        })
        intZoomFactor = cint(50000.0 * num)
        draw(false)
    }

    /**
     * `clsIni.INIRead`: GetPrivateProfileString finds the first line of that key (in any case), trims the value
     * and one pair of quotes; a missing key is "".
     */
    private fun iniRead(ini: List<Pair<String, String>>, key: String): String {
        val raw = ini.firstOrNull { it.first.trim().equals(key, ignoreCase = true) }?.second ?: return ""
        var v = raw.trim()
        if (v.length >= 2 && ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith('\'') && v.endsWith('\'')))) v = v.substring(1, v.length - 1)
        return v
    }

    /** `Setup()`: the page's saved settings, each through its own handler, read as VB reads them. */
    private fun setup(ini: List<Pair<String, String>>?) {
        val value: String; val value2: String; val value3: String; val text2: String; val text3: String
        val text4: String; val text5: String; val text6: String; val text7: String; val text8: String
        val text9: String; val text10: String; val value4: String; val value5: String
        if (ini != null) {
            value = iniRead(ini, "Ref"); value2 = iniRead(ini, "Profile"); value3 = iniRead(ini, "Bomb")
            text2 = iniRead(ini, "IngressAlt"); text3 = iniRead(ini, "DiveAngle"); text4 = iniRead(ini, "CAS")
            text5 = iniRead(ini, "ReleaseHeight"); text6 = iniRead(ini, "TrackingTime"); text7 = iniRead(ini, "PullingGs")
            text8 = iniRead(ini, "Turn"); text9 = iniRead(ini, "AttackHdg"); text10 = iniRead(ini, "VIPtoPUP")
            value4 = iniRead(ini, "OA2atAO"); value5 = iniRead(ini, "Waypoint")
        } else {
            // Interaction.MsgBox("File does not exists: " + text, Critical): the pilot is told, then the fallbacks
            messageBoxes++
            value = "False"; value2 = "True"; value3 = "True"; text2 = "100"; text3 = "30"; text4 = "500"; text5 = "5000"
            text6 = "5"; text7 = "3"; text8 = "True"; text9 = "0"; text10 = "3"; value4 = "0"; value5 = "5"
        }
        blnRef = try { PopupNet.toBoolean(value) } catch (e: PopupError) { false }
        blnProfile = try { PopupNet.toBoolean(value2) } catch (e: PopupError) { false }
        blnBomb = try { PopupNet.toBoolean(value3) } catch (e: PopupError) { false }
        bombtype()
        fun d(s: String) = PopupNet.toDouble(s)
        if (PopupNet.isNumeric(text2)) {
            if (d(text2) / 100.0 >= trbIngressHeight.min && d(text2) / 100.0 <= trbIngressHeight.max) trbIngressHeight.set(cint(d(text2) / 100.0))
            else trbIngressHeight.set(trbIngressHeight.max)
        } else trbIngressHeight.set(trbIngressHeight.max)
        changeIngressHeight()
        if (PopupNet.isNumeric(text3)) {
            if (d(text3) >= trbDiveAngle.min && d(text3) <= trbDiveAngle.max) trbDiveAngle.set(PopupNet.toInteger(text3)) else trbDiveAngle.set(30)
        } else trbDiveAngle.set(30)
        changeDiveAngle()
        trackBarReleaseHeight()
        if (PopupNet.isNumeric(text4)) {
            if (d(text4) >= trbSpeed.min && d(text4) <= trbSpeed.max) trbSpeed.set(PopupNet.toInteger(text4)) else trbSpeed.set(450)
        } else trbSpeed.set(450)
        changeSpeed()
        if (PopupNet.isNumeric(text5)) {
            if (d(text5) / 100.0 >= trbReleaseHeight.min && d(text5) / 100.0 <= trbReleaseHeight.max) trbReleaseHeight.set(cint(d(text5) / 100.0))
            else trbReleaseHeight.set(trbReleaseHeight.min)
        } else trbReleaseHeight.set(trbReleaseHeight.min)
        changeReleaseHeight()
        if (PopupNet.isNumeric(text6)) {
            if (d(text6) >= trbTrackingTime.min && d(text6) <= trbTrackingTime.max) trbTrackingTime.set(PopupNet.toInteger(text6)) else trbTrackingTime.set(5)
        } else trbTrackingTime.set(5)
        changeTrackingTime()
        // the maximum is compared unscaled: anything up to 60 passes, and then the slider throws on it
        if (PopupNet.isNumeric(text7)) {
            if (d(text7) * 10.0 >= trbG.min && d(text7) <= trbG.max) trbG.set(cint(d(text7) * 10.0)) else trbG.set(30)
        } else trbG.set(30)
        changeG()
        if (PopupNet.isNumeric(text8)) trbTurn.set(PopupNet.toInteger(text8)) else { trbTurn.set(1); blnTurn = true }
        changeTurn()
        if (PopupNet.isNumeric(text9)) {
            if (d(text9) >= trbHeading.min && d(text9) <= trbHeading.max) trbHeading.set(PopupNet.toInteger(text9)) else trbHeading.set(1)
        } else trbHeading.set(1)
        changeHeading()
        if (PopupNet.isNumeric(text10)) {
            if (d(text10) >= trbVrpToPup.min && d(text10) <= trbVrpToPup.max) trbVrpToPup.set(PopupNet.toInteger(text10)) else trbVrpToPup.set(3)
        } else trbVrpToPup.set(3)
        changeVrpToPup()
        try {
            val st = PopupNet.toInteger(value4)
            if (st !in 0..2) throw PopupError("The value of argument 'value' ($st) is invalid for Enum type 'CheckState'.")
            setOA2State(st)
        } catch (e: PopupError) {
            setOA2State(0)
        }
        try {
            if (PopupNet.toDouble(value5) > 0.0 && PopupNet.toDouble(value5) < 24.0) setWaypointValue(PopupNet.toDecimal(value5))
            else setWaypointValue(PopupNet.Dec.of(3))
        } catch (e: PopupError) {
            setWaypointValue(PopupNet.Dec.of(3))
        }
    }

    // ---------------------------------------------------------------- the inputs, as the page's events apply them

    /**
     * The waypoint box from the page (the up/down arrows or a typed number): kept in 1..25, every steerpoint of the
     * flight plan (D8: WDP's box stopped at 3, so a target on steerpoint 1 or 2 could not be planned).
     */
    fun setWaypoint(v: Int) = guard { setWaypointValue(PopupNet.Dec.of(v.coerceIn(WP_MIN, 25))) }

    /** `numWaypoint.Value = d`, kept inside 1..25 first (`Constrain`): a fraction stays a fraction (4.5 is 4.5). */
    fun setWaypointDecimal(d: PopupNet.Dec) = guard {
        val v = when {
            d.toDouble() < WP_MIN -> PopupNet.Dec.of(WP_MIN)
            d.toDouble() > 25.0 -> PopupNet.Dec.of(25)
            else -> d
        }
        setWaypointValue(v)
    }

    /**
     * Text typed into the waypoint box (its `Text` set): nothing at all if the box already shows exactly that;
     * otherwise the edit is checked at once (`ValidateEditText`): `Decimal.Parse` in en-US (NumberStyles.Number: no
     * exponent, no currency, no parentheses), kept inside 1..25, and the box then shows its value again — unless it
     * was left empty or a lone "-". Text it cannot read changes nothing, and `ParseEditText` swallows whatever goes
     * wrong, the ValueChanged handler's own exceptions included (no error dialog).
     */
    fun typeWaypoint(text: String) {
        if (text == numWaypointText) return
        numWaypointText = text
        if (text.isNotEmpty() && text != "-") {
            val d = PopupNet.parseNumberStyle(text)
            if (d != null) {
                val v = when {
                    d.toDouble() < WP_MIN -> PopupNet.Dec.of(WP_MIN)
                    d.toDouble() > 25.0 -> PopupNet.Dec.of(25)
                    else -> d
                }
                val before = errors
                guard { setWaypointValue(v) }
                errors = before
            }
            numWaypointText = numWaypoint.f0()
        }
    }

    /** The box's up arrow (`UpButton`): one more, and no more than 25 — from 4.5 that is 5.5. */
    fun waypointUp() = guard {
        var n = numWaypoint.minus(-1)
        if (n.toDouble() > 25.0) n = PopupNet.Dec.of(25)
        setWaypointValue(n)
    }

    /** The box's down arrow (`DownButton`): one less, and no less than 1. */
    fun waypointDown() = guard {
        var n = numWaypoint.minus(1)
        if (n.toDouble() < WP_MIN) n = PopupNet.Dec.of(WP_MIN)
        setWaypointValue(n)
    }

    /** `numWaypoint.Value = v`: ValueChanged, and so `STPTChange`, only when the value moves; outside 1..25 it throws. */
    private fun setWaypointValue(v: PopupNet.Dec) {
        if (v.sameValue(numWaypoint)) return
        if (v.toDouble() < WP_MIN || v.toDouble() > 25.0) throw PopupError("Value of '$v' is not valid for 'Value'.")
        numWaypoint = v
        stptChange()
        // (after ValueChanged: when that throws, the box keeps its old text)
        numWaypointText = numWaypoint.f0()
    }

    /** The OA2 box from the page. */
    fun setOA2(on: Boolean) = guard { setOA2State(if (on) 1 else 0) }

    /** `chbOA2.CheckState = s`: CheckedChanged — and so the flow — only when checked-or-not changes. */
    private fun setOA2State(s: Int) {
        val was = chbOA2State != 0
        chbOA2State = s
        if ((s != 0) != was) programFlow(false)
    }

    /** `chbShowPPT_Click` / `chbShowPPTNr_Click`: the box toggles, and the map is drawn again. */
    fun toggleShowPPT() = guard { chbShowPPT = !chbShowPPT; draw(false) }
    fun toggleShowPPTNr() = guard { chbShowPPTNr = !chbShowPPTNr; draw(false) }

    /** A handler as a click or a scroll runs it: an exception ends that handler, not the page. */
    fun guard(block: () -> Unit) {
        attempt(block)
    }

    /** [guard], saying whether the handler ran to its end. */
    private fun attempt(block: () -> Unit): Boolean {
        try { block(); return true } catch (e: PopupError) { errors++ } catch (e: IllegalArgumentException) { errors++ } catch (e: ArithmeticException) { errors++ } catch (e: IndexOutOfBoundsException) { errors++ }
        return false
    }

    /** Message boxes the page has opened (Setup's, when there is no Setup.ini). */
    var messageBoxes = 0
        private set

    /** How many handlers ended on an exception since this plan was made (the harness counts the same). */
    var errors = 0
        private set

    fun changeIngressHeight() {
        label("lblIngressHeightVal", (trbIngressHeight.value * 100).toString())
        intIngressAlt = trbIngressHeight.value * 100
        intIngressAlt = checkedAdd(intIngressAlt, targetElv)
        programFlow(false)
    }

    fun changeDiveAngle() {
        label("lblDiveAngleVal", trbDiveAngle.value.toString())
        intDiveAngleDeg = trbDiveAngle.value
        trackBarReleaseHeight()
        programFlow(false)
    }

    fun changeSpeed() {
        val num = trbSpeed.value % 10
        if (num != 0 && num != 5) trbSpeed.set(trbSpeed.value + 1)
        intCAS = trbSpeed.value
        label("lblCasVal", trbSpeed.value.toString())
        programFlow(false)
    }

    fun changeReleaseHeight() {
        label("lblReleaseHeightVal", (trbReleaseHeight.value * 100).toString())
        intReleaseHeight = trbReleaseHeight.value * 100
        trackingTimeBox()
        programFlow(false)
    }

    fun changeTrackingTime() {
        label("lblTrackingTimeVal", trbTrackingTime.value.toString())
        intTrackingTime = trbTrackingTime.value
        programFlow(false)
    }

    fun changeG() {
        label("lblGVal", PopupNet.f1NoLead(trbG.value.toDouble() / 10.0))
        dblPullingGs = trbG.value.toDouble() / 10.0
        programFlow(false)
    }

    fun changeTurn() {
        if (trbTurn.value == 1) { label("lblTurnVal", "Right"); blnTurn = true } else { label("lblTurnVal", "Left"); blnTurn = false }
        programFlow(false)
    }

    fun changeHeading() {
        label("lblAttackHdgVal", trbHeading.value.toString())
        intAttackHeadingDeg = trbHeading.value
        programFlow(false)
    }

    fun changeVrpToPup() {
        label("lblVrpToPupVal", trbVrpToPup.value.toString())
        intVIPtoPUPswitch = trbVrpToPup.value
        shown["lblVrpToPupVal"] = true
        shown["lblVIPtoPUPnm"] = true
        programFlow(false)
    }

    private fun checkedAdd(a: Int, b: Int): Int {
        val r = a.toLong() + b.toLong()
        if (r > Int.MAX_VALUE || r < Int.MIN_VALUE) throw PopupOverflow()
        return r.toInt()
    }

    // ---------------------------------------------------------------- the switches

    /** `pnlRefUp/Down_Click`, `pnlDED_Ref_Up/Down_Click`: the reference knob (Up is VRP, Down is VIP). */
    fun chooseRef(vip: Boolean) = guard { blnRef = vip; ref() }

    /** `pnlProfile_Up/Down_Click` and the second pair: Up is Type 2, Down is Type 1. */
    fun chooseProfile(type1: Boolean) = guard { blnProfile = type1; profile() }

    /** `pnlBomb_Up/Down_Click`: Up is high drag, Down is low. */
    fun chooseBomb(low: Boolean) = guard { blnBomb = low; bombtype() }

    /** `pnlSelections_Up/Middle/Down_MouseClick`: which of the three stacked panels is up; [left] is the button. */
    fun chooseSelection(which: String, left: Boolean) = guard { strSelections = which; blnMouseClick = left; selections() }

    /** `btnCamp_Click` / `btnTE_Click`. */
    fun chooseCampTE(camp: Boolean) = guard { blnCampTE = camp; campTE() }

    /**
     * `btnSaveDTC_Click`: the DTC page is told this is a Pop-up profile (`cntDTC.strProfile`, then its `Profiles()`
     * reads [navOffsets]), then the main form saves the cartridge — the callsign's with a message, or, when the DTC
     * page holds a TE mission's cartridge, the callsign's quietly and then the TE one. What this page does is [dtcCalls];
     * the writing is [onSaveDtc]'s.
     */
    fun saveDtc() {
        dtcProfile = "PopUp"
        dtcCall("Profiles($dtcProfile)")
        if (main.missionDtcLoaded) {
            dtcCall("SaveCallsign_DTC(False)")
            dtcCall("SaveTE_DTC(Both)")
        } else {
            dtcCall("SaveCallsign_DTC(True)")
        }
        onSaveDtc?.invoke(this)
    }

    fun ref() {
        if (!blnDoVIP) {
            blnRef = false
            shown["pnlBlocked_1"] = true; shown["pnlBlocked_3"] = true
        } else {
            shown["pnlBlocked_1"] = false; shown["pnlBlocked_3"] = false
        }
        if (blnRef) {
            shown["pnlRefUp"] = true; shown["pnlRefDown"] = false
            shown["pnlDED_Ref_Up"] = true; shown["pnlDED_Ref_Down"] = false
            label("lblVRPtoPUPdist", "")
            shown["trbVrpToPup"] = false; shown["lblVrpToPupVal"] = false; shown["lblVIPtoPUPnm"] = false
            label("lblDEDvip_1", "VIP-TO-TGT"); label("lblDEDvip_2", "VIP")
            label("lblDEDpup_1", "VIP-TO-PUP"); label("lblDEDpup_2", "VIP")
        } else {
            shown["pnlRefDown"] = true; shown["pnlRefUp"] = false
            shown["pnlDED_Ref_Down"] = true; shown["pnlDED_Ref_Up"] = false
            label("lblVRPtoPUPdist", "VRP to PUP dist")
            shown["trbVrpToPup"] = true; shown["lblVrpToPupVal"] = true; shown["lblVIPtoPUPnm"] = true
            label("lblDEDvip_1", "TGT-TO-VRP"); label("lblDEDvip_2", "VRP")
            label("lblDEDpup_1", "TGT-TO-PUP"); label("lblDEDpup_2", "VRP")
        }
        programFlow(false)
    }

    private fun profile() {
        if (blnProfile) {
            shown["pnlProfile_Up"] = true; shown["pnlProfile_Down"] = false
            shown["pnlProfile2_Up"] = true; shown["pnlProfile2_Down"] = false
            strProfile = "Type 1"
        } else {
            shown["pnlProfile_Down"] = true; shown["pnlProfile_Up"] = false
            shown["pnlProfile2_Down"] = true; shown["pnlProfile2_Up"] = false
            strProfile = "Type 2"
        }
        programFlow(false)
    }

    private fun bombtype() {
        if (blnBomb) {
            shown["pnlBomb_Up"] = true; shown["pnlBomb_Down"] = false
            strBomb = "Low Drag"
        } else {
            shown["pnlBomb_Down"] = true; shown["pnlBomb_Up"] = false
            strBomb = "High Drag"
        }
        programFlow(false)
    }

    private fun selections() {
        fun show(up: Boolean, middle: Boolean, down: Boolean, sel: Boolean, prof: Boolean, ded: Boolean) {
            shown["pnlSelections_Up"] = up; shown["pnlSelections_Middle"] = middle; shown["pnlSelections_Down"] = down
            shown["pnlSelections"] = sel; shown["pnlProfile"] = prof; shown["pnlDEDData"] = ded
        }
        when (strSelections) {
            "Selections" -> if (blnMouseClick) { show(false, true, false, false, true, false); draw(false) }
            "Profile" -> if (blnMouseClick) show(false, false, true, false, false, true) else show(true, false, false, true, false, false)
            "DED Data" -> if (!blnMouseClick) show(false, true, false, false, true, false)
            else -> show(true, false, false, true, false, false)
        }
    }

    /** `CampTE`: the two buttons show which table is read, then the target is fetched again. */
    fun campTE() {
        when (strDTC) {
            "Camp" -> { btnCampEnabled = false; btnCampBack = "Green"; btnTEEnabled = true; btnTEBack = main.backColor }
            "TE" -> { btnCampEnabled = true; btnCampBack = main.backColor; btnTEEnabled = false; btnTEBack = "Green" }
            "Both" -> if (blnCampTE) {
                btnCampEnabled = false; btnCampBack = "Green"; btnTEEnabled = true; btnTEBack = main.backColor
            } else {
                btnTEEnabled = false; btnTEBack = "Green"; btnCampEnabled = true; btnCampBack = main.backColor
            }
            "None" -> { btnCampEnabled = true; btnCampBack = main.backColor; btnTEEnabled = true; btnTEBack = main.backColor }
        }
        getCoords()
    }

    // ---------------------------------------------------------------- the flow

    fun programFlow(force: Boolean) {
        if ((!force && !visible) || !loaded) return
        dblDiveAngleRad = x87(intDiveAngleDeg.toFloat()) * x87(DTR)
        dblAttackHeadingRad = x87(intAttackHeadingDeg.toFloat()) * x87(DTR)
        intAttackBrgDeg = intAttackHeadingDeg - 180
        if (intAttackBrgDeg < 0) intAttackBrgDeg += 360
        dblAttackBrgRad = x87(intAttackBrgDeg.toFloat()) * x87(DTR)
        profileSwitch()
        getTAS()
        bombrange()
        basicValues()
        sightDepression()
        extrapoint()
        oaPoints()
        if (blnProfile) vrpType1() else vrpType2()
        fixedPoints()
        if (blnDoVIP) { if (blnProfile) vipType1() else vipType2() }
        typedElevations()
        if (blnVersion) fillLabelsBMS() else fillLabelsAF()
        saveNavOffsets()
        dtc()
        draw(false)
    }

    private fun profileSwitch() {
        val vx = if (blnRef) "VIP" else "VRP"
        val oa = if (chbOA2State != 0) "OA2" else ""
        val type = if (blnProfile) "Type1" else "Type2"
        val side = if (blnTurn) "Right" else "Left"
        profilePicture = "PopUp_$vx${oa}_${type}_$side.jpg"
    }

    private fun trackBarReleaseHeight() {
        when (intDiveAngleDeg) {
            10 -> { trbReleaseHeight.setMinimum(3); trbReleaseHeight.setMaximum(40) }
            15 -> { trbReleaseHeight.setMinimum(3); trbReleaseHeight.setMaximum(50) }
            20 -> { trbReleaseHeight.setMinimum(5); trbReleaseHeight.setMaximum(50) }
            25 -> { trbReleaseHeight.setMinimum(10); trbReleaseHeight.setMaximum(50) }
            30 -> { trbReleaseHeight.setMinimum(20); trbReleaseHeight.setMaximum(80) }
            35 -> { trbReleaseHeight.setMinimum(20); trbReleaseHeight.setMaximum(80) }
            40 -> { trbReleaseHeight.setMinimum(30); trbReleaseHeight.setMaximum(80) }
            45 -> { trbReleaseHeight.setMinimum(40); trbReleaseHeight.setMaximum(100) }
        }
        val b = trbReleaseHeight
        when {
            b.value < b.min -> b.set(b.min)
            b.value > b.max -> b.set(b.max)
            intReleaseHeight.toDouble() / 100.0 >= b.min && intReleaseHeight.toDouble() / 100.0 <= b.max -> b.set(cint(intReleaseHeight.toDouble() / 100.0))
            else -> b.set(b.min)
        }
        changeReleaseHeight()
    }

    private fun trackingTimeBox() {
        val num = intReleaseHeight
        if (num < 5000) trbTrackingTime.setMaximum(10) else if (num >= 5000) trbTrackingTime.setMaximum(5)
        changeTrackingTime()
        val b = trbTrackingTime
        when {
            b.value < b.min -> b.set(b.min)
            b.value > b.max -> b.set(b.max)
            intTrackingTime >= b.min && intTrackingTime <= b.max -> b.set(intTrackingTime)
            else -> b.set(b.min)
        }
        changeTrackingTime()
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

    /**
     * `Get_Coords`: the target is steerpoint N and the IP N − 1 of the table [strDTC] names (with Precision set in
     * the DataCard), else of the campaign's flight table; the target's elevation is the terrain's; the coordinate
     * labels are the theater's lat/lon strings. Every branch is WDP's, in its order, including the returns that
     * leave the page half-updated.
     */
    fun getCoords() {
        if (!loaded) return
        if (!blnVersion) strDTC = "Camp"
        val num = numWaypoint.minus(1).toInt32()
        // the IP: the steerpoint before the target, or the one the pilot picked (D87; the target itself is no IP)
        val num2 = ipStpt?.let { if (it - 1 == num) -1 else it - 1 } ?: checkedAdd(num, -1)
        decOrig_TGT.x = 0f
        decOrig_TGT.y = 0f
        if (main.precision) {
            fun read(t: Array<Stpt>) {
                // steerpoint 1 has no steerpoint before it, so no IP (WDP's box never reached 1: D8)
                val ip = t.getOrNull(num2) ?: Stpt()
                decOrig_IP.x = ip.falconX; decOrig_IP.y = ip.falconY; decOrig_IP.z = ip.falconZ
                decOrig_TGT.x = t[num].falconX; decOrig_TGT.y = t[num].falconY; decOrig_TGT.z = t[num].falconZ
            }
            when (strDTC) {
                "Camp" -> { blnCampTE = true; read(main.tblCampSTPT) }
                "TE" -> { blnCampTE = false; read(main.tblMissionSTPT) }
                "Both" -> read(if (blnCampTE) main.tblCampSTPT else main.tblMissionSTPT)
                else -> return   // "None", and anything else
            }
        }
        if (decOrig_TGT.x == 0f && decOrig_TGT.y == 0f) {
            // the flight's own waypoints; anything missing is an exception WDP catches, and nothing is assigned
            val ft = main.flightTable
            if (main.flightNR != 0 && ft != null && main.selFlightNr in ft.indices) {
                val wps = ft[main.selFlightNr]
                if ((num2 in wps.indices || num2 == -1 || ipStpt != null) && num in wps.indices) {
                    // steerpoint 1 has no IP before it (D8)
                    val ipWp = wps.getOrNull(num2) ?: GridWp()
                    val num3 = (ipWp.gridX.toDouble() + 0.5).toFloat()
                    val num4 = (ipWp.gridY.toDouble() + 0.5).toFloat()
                    val num5 = ipWp.gridZ.toFloat()
                    val num6 = (wps[num].gridX.toDouble() + 0.5).toFloat()
                    val num7 = (wps[num].gridY.toDouble() + 0.5).toFloat()
                    val num8 = wps[num].gridZ.toFloat()
                    decOrig_IP.x = num3 * KM_TO_FT
                    decOrig_IP.y = num4 * KM_TO_FT
                    decOrig_IP.z = num5 * 10f
                    decOrig_TGT.x = num6 * KM_TO_FT
                    decOrig_TGT.y = num7 * KM_TO_FT
                    decOrig_TGT.z = num8 * 10f
                    // a picked IP the flight has no waypoint for is no IP (D87)
                    if (ipStpt != null && wps.getOrNull(num2) == null) { decOrig_IP.x = 0f; decOrig_IP.y = 0f; decOrig_IP.z = 0f }
                }
            }
        }
        if (decOrig_TGT.x == 0f) {
            shown["lblTGT_N"] = false; shown["lblTGT_E"] = false; shown["lblTGT_elv"] = false
            shown["lblIP_N"] = false; shown["lblIP_E"] = false; shown["lblIP_elv"] = false
            blnGotTGT = false
            blnDoVIP = false
            decOrig_TGT.x = 1510000f
            decOrig_TGT.y = 1510000f
            ref()
            if (blnVersion) fillLabelsBMS() else fillLabelsAF()
            return
        }
        if (decOrig_IP.x == 0f) {
            shown["lblTGT_N"] = true; shown["lblTGT_E"] = true; shown["lblTGT_elv"] = true
            shown["lblIP_N"] = false; shown["lblIP_E"] = false; shown["lblIP_elv"] = false
            blnGotTGT = true
            blnDoVIP = false
            ref()
        } else {
            shown["lblTGT_N"] = true; shown["lblTGT_E"] = true; shown["lblTGT_elv"] = true
            shown["lblIP_N"] = true; shown["lblIP_E"] = true; shown["lblIP_elv"] = true
            blnGotTGT = true
            blnDoVIP = true
            ref()
        }
        strTGT_STPT_deg = PopupCoords.feetToCoordsBoth(objC, decOrig_TGT.y.toDouble(), decOrig_TGT.x.toDouble())
        val tgtN = PopupCoords.getNorthDeg(strTGT_STPT_deg)
        val tgtE = PopupCoords.getEastDeg(strTGT_STPT_deg)
        // WDP's terrain under the target (D7): with no heightmap of the page's own, the ground the wiring hands in — typed,
        // BMS's height map read on the PC, or the cartridge's target point. Feet above sea level as the map holds them:
        // ground below the sea keeps its sign, as WDP's (int)Math.Round of the map's value does.
        val ground = if (main.terrainLoaded) null else main.groundElevation?.invoke(numWaypoint.toInt32(), decOrig_TGT.y.toDouble(), decOrig_TGT.x.toDouble())
        targetElv = if (ground != null) cint(ground) else cint(main.terrainHeight(decOrig_TGT.y, decOrig_TGT.x).toDouble())
        elev.target(decOrig_TGT.y.toDouble(), decOrig_TGT.x.toDouble())
        changeIngressHeight()
        val tgtElv = PopupNet.str(abs(PopupCoords.round(decOrig_TGT.z.toDouble(), 0)))
        strIP_STPT_deg = PopupCoords.feetToCoordsBoth(objC, decOrig_IP.y.toDouble(), decOrig_IP.x.toDouble())
        val ipN = PopupCoords.getNorthDeg(strIP_STPT_deg)
        val ipE = PopupCoords.getEastDeg(strIP_STPT_deg)
        val ipElv = PopupNet.str(abs(PopupCoords.round(decOrig_IP.z.toDouble(), 0)))
        label("lblTGT_N", tgtN); label("lblTGT_E", tgtE); label("lblTGT_elv", tgtElv)
        label("lblIP_N", ipN); label("lblIP_E", ipE); label("lblIP_elv", ipElv)
        if (tgtN == "00,00.000") {
            val both = tgtE == "000,00.000"
            shown["lblTGT_N"] = !both; shown["lblTGT_E"] = !both
        }
        if (ipN == "00,00.000") {
            val both = ipE == "000,00.000"
            shown["lblIP_N"] = !both; shown["lblIP_E"] = !both
        }
        programFlow(false)
    }

    /** The target and IP as the page now holds them (north, east feet). */
    val targetNorthEast: Pair<Float, Float>? get() = if (blnGotTGT) decOrig_TGT.y to decOrig_TGT.x else null
    val ipNorthEast: Pair<Float, Float>? get() = if (blnDoVIP) decOrig_IP.y to decOrig_IP.x else null

    /** `FeetToRad`: feet to radians of arc (a nautical mile is a minute). */
    fun feetToRad(distFeet: Int): Double = 0.0002908882086657216 * (x87(distFeet.toFloat()) / x87(NM_TO_FT))

    /** `RadToFeet`. */
    fun radToFeet(distRad: Double): Int = cint(10800.0 / PI * distRad * NM_TO_FT.toDouble())

    private fun getTAS() {
        if (loaded) {
            intTAS = cint(Performance.tas(intCAS, intReleaseHeight))
            label("lblTASval", intTAS.toString())
        }
    }

    private fun bombrange() {
        val diveAngle = intDiveAngleDeg * -1
        val releaseHeight = intReleaseHeight
        if (loaded) {
            strBombtype = when (strBomb) { "Low Drag" -> "Low"; "High Drag" -> "High"; else -> "Low" }
            val shot = Ballistics.bombRange(diveAngle, intTAS, releaseHeight, strBombtype)
            intBombRange = shot.rangeFt
            label("lblBombTime", PopupNet.str(shot.timeOfFallSec))
        }
    }

    private fun basicValues() {
        if (!loaded) return
        intGroundSpeed = cint(PopupX87.mul(PopupX87.cos(dblDiveAngleRad), intTAS.toDouble()))
        label("lblGsVal", intGroundSpeed.toString())
        label("lblSpeedExpl", "(at $intReleaseHeight' and dive angle $intDiveAngleDeg)")
        val num = cint(intGroundSpeed.toDouble() * 1.69 * intTrackingTime.toDouble())
        intMAP = intBombRange + num
        if (dblDiveAngleRad == 0.0) dblDiveAngleRad = 1.0
        intAimOffDist = cint(PopupX87.div(intReleaseHeight.toDouble(), PopupX87.tan(dblDiveAngleRad)) - intBombRange.toDouble())
        val num2 = cint(PopupX87.mul(PopupX87.sin(dblDiveAngleRad), intTAS.toDouble()) * 1.69 * intTrackingTime.toDouble())
        var num3 = intReleaseHeight + num2
        num3 = checkedAdd(num3, targetElv)
        val num4 = intDiveAngleDeg
        if (num4 <= 15) intClimbAngleDeg = abs(intDiveAngleDeg) + 5 else if (num4 > 15) intClimbAngleDeg = abs(intDiveAngleDeg) + 10
        label("lblClimbAngleVal", intClimbAngleDeg.toString())
        dblClimbAngleRad = x87(DTR) * x87(intClimbAngleDeg.toFloat())
        dblAngleOffDeg = (2 * intClimbAngleDeg).toDouble()
        label("lblAngleOffVal", PopupNet.f1(dblAngleOffDeg))
        dblAngleOffRad = DTR.toDouble() * dblAngleOffDeg
        val num5 = dblPullingGs
        var num6 = 0
        if (num5 <= 4.0) {
            num6 = num3 + intDiveAngleDeg * 50
            intPullDownAlt = num6 - intClimbAngleDeg * 50
        } else if (num5 > 4.0) {
            num6 = cint(num3.toDouble() + intDiveAngleDeg.toDouble() * 37.5)
            intPullDownAlt = cint(num6.toDouble() - intClimbAngleDeg.toDouble() * 37.5)
        }
        label("lblPulldownAltVal", intPullDownAlt.toString())
        label("lblAODval", intAimOffDist.toString())
        // the first figure is thrown away by the second, in the original too
        @Suppress("UNUSED_VARIABLE") val unused = cint(((num6 - intIngressAlt) * 60).toDouble() / intClimbAngleDeg.toDouble())
        val num7 = cint(PopupX87.mul(PopupX87.tan(dblClimbAngleRad), (num6 - intIngressAlt).toDouble()))
        if (intDiveAngleDeg == 0) intDiveAngleDeg = 10
        val num8 = intDiveAngleDeg
        var num10 = 0.0
        val rh = intReleaseHeight
        if (num8 in 10..14) {
            if (rh < 1200) num10 = 15.0 else if (rh in 1500..2000) num10 = 14.0 else if (rh > 2000) num10 = 13.0
        } else if (num8 in 15..19) {
            if (rh < 2000) num10 = 17.0 else if (rh in 2000..3000) num10 = 17.0 else if (rh > 3000) num10 = 18.0
        } else if (num8 in 20..24) {
            num10 = 26.0
        } else if (num8 in 25..29) {
            if (rh <= 3000) num10 = 29.0 else if (rh >= 4000) num10 = 29.0
        } else if (num8 in 30..34) {
            num10 = 32.0
        } else if (num8 in 35..39) {
            num10 = 34.0
        } else if (num8 in 40..44) {
            num10 = 36.0
        } else if (num8 >= 45) {
            num10 = 37.0
        }
        val d = DTR.toDouble() * num10
        intTurnRadius = cint((x87(intTAS.toFloat()) * x87(1.69f)).pow(2.0) / (dblPullingGs * 32.20000076293945))
        @Suppress("UNUSED_VARIABLE") val num17 = cint((x87(intCAS.toFloat()) * x87(1.69f)).pow(2.0) / (dblPullingGs * 32.20000076293945))
        val num18 = cint(PopupX87.mul(PopupX87.atan(d), intTurnRadius.toDouble()))
        @Suppress("UNUSED_VARIABLE") val unused2 = cint(PopupX87.mul(PopupX87.atan(d), num17.toDouble()))
        val num19 = if (!(dblAngleOffDeg < 90.0)) intTurnRadius else cint(PopupX87.mul(PopupX87.atan(dblAngleOffRad), intTurnRadius.toDouble()))
        intAtttoTgtDist = intMAP + num19
        val num20 = intAtttoTgtDist
        val num21 = cint(PopupX87.mul(PopupX87.tan(dblClimbAngleRad), intTurnRadius.toDouble()))
        @Suppress("UNUSED_VARIABLE") val unused3 = cint(PopupX87.mul(PopupX87.sin(dblClimbAngleRad), num21.toDouble()))
        val num22 = 0.5
        val num23 = cint(x87(intTAS.toFloat()) * x87(NM_TO_FT) / x87(3600f) * num22)
        intPupToPDP = num18 + num23 + num7
        val num24 = intPupToPDP + num19
        val d2 = PI / 180.0 * (180.0 - dblAngleOffDeg)
        val num25 = cint(x87(num20.toFloat()) / x87(FEET_PER_METER))
        @Suppress("UNUSED_VARIABLE") val unused4 = cint(x87(num18.toFloat()) / x87(FEET_PER_METER))
        @Suppress("UNUSED_VARIABLE") val unused5 = cint(x87(num19.toFloat()) / x87(FEET_PER_METER))
        val num26 = cint(x87(num24.toFloat()) / x87(FEET_PER_METER))
        val num27 = num25.toDouble().pow(2.0) + num26.toDouble().pow(2.0)
        val num28 = PopupX87.mul(PopupX87.cos(d2), checkedMul(checkedMul(2, num25), num26).toDouble())
        val num29 = sqrt(num27 - num28)
        intActionToTgt = cint(num29 * FEET_PER_METER.toDouble())
        val num30 = num29.pow(2.0) + num26.toDouble().pow(2.0) - num25.toDouble().pow(2.0)
        val num31 = num29 * num26.toDouble()
        val num32 = acos(num30 / (2.0 * num31))
        val num33 = RTD.toDouble() * num32
        dblOffsetAngleDeg = num33
        dblOffsetAngleRad = DTR.toDouble() * dblOffsetAngleDeg
        label("lblOffsetAngleVal", PopupNet.f1(dblOffsetAngleDeg))
        if (blnTurn) {
            dblPullHeading = intAttackHeadingDeg.toDouble() - dblAngleOffDeg
        } else {
            dblPullHeading = intAttackHeadingDeg.toDouble() + dblAngleOffDeg
        }
        val num34 = dblPullHeading
        if (num34 < 0.0) dblPullHeading += 360.0 else if (num34 >= 360.0) dblPullHeading -= 360.0
        label("lblPullHeadingVal", PopupNet.f1(dblPullHeading))
        dblPullBrg = dblPullHeading - 180.0
        if (dblPullBrg < 0.0) dblPullBrg += 360.0 else if (dblPullBrg >= 360.0) dblPullBrg -= 360.0
    }

    /**
     * The ELEV figures the pilot typed (D7: WDP gave no way to, forum 2024), over the flow's own, by nav-offset key —
     * so the DED lines and Save to DTC both carry them. The flow's own figures already stand on the ground under the
     * target when it is known (`Get_Coords`), as WDP's do with its terrain loaded.
     */
    private fun typedElevations() {
        fun o(key: String, own: String?): String? = if (elev.isTyped(key)) elev.of(key, 0).toString() else own
        strVRPelv = o("VRP", strVRPelv); strVRP_PUPelv = o("VRPPUP", strVRP_PUPelv)
        strVRP_OA1elv = o("OA1_2", strVRP_OA1elv); strVRP_OA2elv = o("OA2_2", strVRP_OA2elv)
        strVIPelv = o("VIP", strVIPelv); strVIP_PUPelv = o("VIPPUP", strVIP_PUPelv)
        strVIP_OA1elv = o("OA1_1", strVIP_OA1elv); strVIP_OA2elv = o("OA2_1", strVIP_OA2elv)
    }

    private fun checkedMul(a: Int, b: Int): Int {
        val r = a.toLong() * b.toLong()
        if (r > Int.MAX_VALUE || r < Int.MIN_VALUE) throw PopupOverflow()
        return r.toInt()
    }

    private fun sightDepression() {
        try {
            val mils = cint(PopupX87.sub(PopupX87.atan(intReleaseHeight.toDouble() / intBombRange.toDouble()), dblDiveAngleRad) * 1000.0)
            if (mils >= 260) { label("lblTargetHUDval", "NO"); hudBack = "Red" } else { label("lblTargetHUDval", "YES"); hudBack = "Lime" }
        } catch (e: PopupError) {
            // caught and ignored, as the original does
        }
    }

    private fun extrapoint() {
        val num = 90.0 - dblAngleOffDeg
        dblExtraHedDeg = if (blnTurn) intAttackHeadingDeg.toDouble() + num else intAttackHeadingDeg.toDouble() - num
        val num2 = dblExtraHedDeg
        if (num2 >= 360.0) dblExtraHedDeg -= 360.0 else if (num2 < 0.0) dblExtraHedDeg += 360.0
        dblExtraBrgDeg = dblExtraHedDeg - 180.0
        val num3 = dblExtraBrgDeg
        if (num3 < 0.0) dblExtraBrgDeg += 360.0 else if (num3 >= 360.0) dblExtraBrgDeg -= 360.0
        val num4 = dblAngleOffDeg
        if (num4 < 90.0) intExtraToTgt = cint(PopupX87.mul(PopupX87.sin(dblAngleOffRad), intAtttoTgtDist.toDouble()))
        else if (num4 == 90.0) intExtraToTgt = intAtttoTgtDist
        else if (num4 > 90.0) intExtraToTgt = cint(PopupX87.mul(PopupX87.sin(dblAngleOffRad), intAtttoTgtDist.toDouble()))
        intAtttoExtraDist = cint(sqrt(intAtttoTgtDist.toDouble().pow(2.0) - intExtraToTgt.toDouble().pow(2.0)))
    }

    private fun oaPoints() {
        var value = ""
        var text = ""
        var text2 = ""
        val num = dblAngleOffDeg
        var num2 = 0
        if (num < 90.0) num2 = cint(PopupX87.mul(PopupX87.sin(dblAngleOffRad), 0.5 * intTurnRadius.toDouble()))
        else if (num == 90.0) num2 = cint(PopupX87.mul(PopupX87.sin(dblAngleOffRad), 0.75 * intTurnRadius.toDouble()))
        else if (num > 90.0) num2 = cint(PopupX87.mul(PopupX87.sin(dblAngleOffRad), 1.0 * intTurnRadius.toDouble()))
        intPDPtoExtraDist = if (dblAngleOffDeg <= 90.0) intAtttoExtraDist + num2 else num2 - intAtttoExtraDist
        val num3 = round1(x87(PopupX87.atan(intPDPtoExtraDist.toDouble() / intExtraToTgt.toDouble()).toFloat()) * x87(180f) / PI).toFloat()
        // a float local: stored as a float; the sum below it is an x87 temporary and stays double
        var num5 = if (blnTurn) (dblExtraHedDeg - num3.toDouble()).toFloat() else (dblExtraHedDeg + num3.toDouble()).toFloat()
        val num7 = num5
        if (num7 < 0f) num5 += 360f else if (num7 >= 360f) num5 -= 360f
        dblOA1toTGTbrg = x87(num5) + x87(180f)
        val num8 = dblOA1toTGTbrg
        if (num8 < 0.0) dblOA1toTGTbrg += 360.0 else if (num8 >= 360.0) dblOA1toTGTbrg -= 360.0
        dblOA1toTGTbrgRad = dblOA1toTGTbrg * DTR.toDouble()
        intPDPtoTargetDist = cint(sqrt(intExtraToTgt.toDouble().pow(2.0) + intPDPtoExtraDist.toDouble().pow(2.0)))
        val text3 = PopupNet.f1(dblOA1toTGTbrg)
        val text4 = PopupNet.f1(num5)
        val text5 = intPDPtoTargetDist.toString()
        val text6 = intPullDownAlt.toString()
        strVRP_OA1brg = text3
        strVRP_OA1hdg = text4
        strVRP_OA1rng = text5
        strVRP_OA1elv = text6
        if (chbOA2State == 1) {
            text = intAimOffDist.toString()
            value = PopupNet.f1(intAttackHeadingDeg.toDouble())
            text2 = intIngressAlt.toString()
        } else if (chbOA2State == 0) {
            text = text5
            value = text3
            text2 = intIngressAlt.toString()
        }
        strVRP_OA2brg = value
        strVRP_OA2rng = text
        strVRP_OA2elv = text2
        dblOA2toTGTbrg = PopupNet.toDouble(value)
        dblOA2toTGTbrgRad = dblOA2toTGTbrg * DTR.toDouble()
        // the apex, for the map only
        val brg = intAttackBrgDeg.toDouble()
        if (brg > dblOA1toTGTbrg) {
            if (brg - dblOA1toTGTbrg > 50.0) {
                val num9 = (360 - intAttackBrgDeg).toDouble()
                dblApexbrgDeg = brg + abs(num9 + dblOA1toTGTbrg) / 2.0
                if (dblApexbrgDeg >= 360.0) dblApexbrgDeg -= 360.0
            } else {
                dblApexbrgDeg = brg - abs(brg - dblOA1toTGTbrg) / 2.0
            }
        } else if (brg < dblOA1toTGTbrg) {
            if (dblOA1toTGTbrg - brg > 50.0) {
                if (dblOA1toTGTbrg >= 340.0) {
                    if (intAttackBrgDeg < 0) {
                        val num11 = 360.0 - dblPullBrg
                        dblApexbrgDeg = brg - abs(num11 + brg) / 2.0
                    }
                } else if (dblOA1toTGTbrg > 0.0) {
                    if (intAttackBrgDeg > 0) {
                        val num12 = dblOA1toTGTbrg - brg
                        dblApexbrgDeg = brg + abs(num12 / 2.0)
                    }
                }
                if (dblApexbrgDeg < 0.0) dblApexbrgDeg = 360.0 - abs(dblApexbrgDeg)
            } else {
                dblApexbrgDeg = brg + abs(brg - dblOA1toTGTbrg) / 2.0
            }
        }
        dblApexbrgRad = dblApexbrgDeg * DTR.toDouble()
    }

    private fun fixedPoints() {
        val t = decOrig_TGT
        map.y = newPosN(t.y, t.x, dblAttackBrgRad.toFloat(), intMAP)
        map.x = newPosE(t.y, t.x, dblAttackBrgRad.toFloat(), intMAP)
        aod.y = newPosN(t.y, t.x, dblAttackHeadingRad.toFloat(), intAimOffDist)
        aod.x = newPosE(t.y, t.x, dblAttackHeadingRad.toFloat(), intAimOffDist)
        val dist = cint((intMAP + intPDPtoTargetDist).toDouble() / 2.0)
        apex.y = newPosN(t.y, t.x, dblApexbrgRad.toFloat(), dist)
        apex.x = newPosE(t.y, t.x, dblApexbrgRad.toFloat(), dist)
        oa1.y = newPosN(t.y, t.x, dblOA1toTGTbrgRad.toFloat(), intPDPtoTargetDist)
        oa1.x = newPosE(t.y, t.x, dblOA1toTGTbrgRad.toFloat(), intPDPtoTargetDist)
        oa2.y = newPosN(t.y, t.x, dblOA2toTGTbrgRad.toFloat(), toInteger(strVRP_OA2rng))
        oa2.x = newPosE(t.y, t.x, dblOA2toTGTbrgRad.toFloat(), toInteger(strVRP_OA2rng))
    }

    /** `Bearing`: radians, as a float, from strings — the "0.000000" test never matches what a float prints. */
    internal fun bearing(beginN: String, beginE: String, endN: String, endE: String): Float {
        if (beginN == "0.000000" || beginE == "0.000000" || endN == "0.000000" || endE == "0.000000") return 0f
        val num = (PopupNet.toDouble(endN) - PopupNet.toDouble(beginN)).toFloat()
        val num2 = (PopupNet.toDouble(endE) - PopupNet.toDouble(beginE)).toFloat()
        val flag = !(num > 0f)
        val flag2 = !(num2 > 0f)
        // num3 and num4 never live across a call, so the x86 JIT keeps them on the x87 stack at double precision:
        // only the (float) of the arctangent and the float the caller stores the result in cut them to floats.
        // Sampled 20,000 times against the program: carrying them as floats misses one bearing in five by an ulp.
        var num3: Double
        if (flag) {
            if (flag2) {
                num3 = x87(PopupX87.atan(x87(abs(num2)) / x87(abs(num))).toFloat())
                var num4 = num3 * x87(RTD)
                num4 = x87(180f) + num4
                num3 = num4 * x87(DTR)
            } else {
                num3 = x87(PopupX87.atan(abs(x87(num) / x87(num2))).toFloat())
                var num4 = num3 * x87(RTD)
                num4 = x87(90f) + num4
                num3 = num4 * x87(DTR)
            }
        } else if (flag2) {
            num3 = x87(PopupX87.atan(abs(x87(num) / x87(num2))).toFloat())
            var num4 = num3 * x87(RTD)
            num4 = x87(270f) + num4
            num3 = num4 * x87(DTR)
        } else {
            num3 = x87(PopupX87.atan(abs(x87(num2) / x87(num))).toFloat())
        }
        return num3.toFloat()
    }

    /** `Distance`: whole feet between two points given as strings. */
    internal fun distance(beginN: String, beginE: String, endN: String, endE: String): Int {
        val num = abs(PopupNet.toDouble(endN) - PopupNet.toDouble(beginN))
        val num2 = abs(PopupNet.toDouble(endE) - PopupNet.toDouble(beginE))
        return cint(sqrt(num.pow(2.0) + num2.pow(2.0)))
    }

    internal fun newPosN(beginN: Float, beginE: Float, brg: Float, dist: Int): Float {
        if (beginN == 0f) return 0f
        if (beginE == 0f) return 0f
        if (dist == 0) return beginN
        val num = PopupX87.mul(PopupX87.cos(brg.toDouble()), dist.toDouble())
        return (beginN.toDouble() + num).toFloat()
    }

    internal fun newPosE(beginN: Float, beginE: Float, brg: Float, dist: Int): Float {
        if (beginN == 0f) return 0f
        if (beginE == 0f) return 0f
        if (dist == 0) return beginE
        val num = PopupX87.mul(PopupX87.sin(brg.toDouble()), dist.toDouble())
        return (beginE.toDouble() + num).toFloat()
    }

    private fun wrapF(v: Float): Float = if (v < 0f) v + 360f else if (v >= 360f) v - 360f else v

    private fun vrpType1() {
        var num: Float
        val num3: Float
        var num5 = 0
        num = if (!blnTurn) {
            (intAttackHeadingDeg.toDouble() + dblAngleOffDeg - dblOffsetAngleDeg).toFloat()
        } else {
            (intAttackHeadingDeg.toDouble() - dblAngleOffDeg + dblOffsetAngleDeg).toFloat()
        }
        num = wrapF(num)
        num3 = wrapF(num + 180f)
        if (dblPullHeading > num.toDouble()) num5 = cint(dblPullHeading - num.toDouble())
        else if (dblPullHeading < num.toDouble()) num5 = cint(num.toDouble() - dblPullHeading)
        @Suppress("UNUSED_VARIABLE") val turned = (PI / 180.0 * num5.toDouble()).toFloat()
        val num8 = intActionToTgt.toFloat() / NM_TO_FT
        var num9 = 0
        var num10 = 0f
        when (intVIPtoPUPswitch) {
            0 -> { num9 = checkedMul(intActionToTgt, 2); num10 = num9.toFloat() / NM_TO_FT }
            1, 2, 3, 4, 5 -> {
                // handed straight to Math.Round(double): an x87 temporary, never cut to a float
                num9 = cint(x87(intActionToTgt.toFloat()) + x87(intVIPtoPUPswitch.toFloat()) * x87(NM_TO_FT))
                num10 = num9.toFloat() / NM_TO_FT
            }
        }
        strVRPbrg = PopupNet.f1(num3)
        strVRPhdg = PopupNet.f1(num)
        strVRPrng = num9.toString()
        strVRPnm = PopupNet.f1(num10)
        strVRPelv = intIngressAlt.toString()
        strVRP_PUPbrg = PopupNet.f1(num3)
        strVRP_PUPhdg = PopupNet.f1(num)
        strVRP_PUPrng = intActionToTgt.toString()
        strVRP_PUPnm = PopupNet.f1(num8)
        strVRP_PUPelv = intIngressAlt.toString()
        val brg = num3 * DTR
        dblT1_VRP[0] = newPosN(decOrig_TGT.y, decOrig_TGT.x, brg, num9).toDouble()
        dblT1_VRP[1] = newPosE(decOrig_TGT.y, decOrig_TGT.x, brg, num9).toDouble()
        dblVRP[0] = dblT1_VRP[0]
        dblVRP[1] = dblT1_VRP[1]
        val brg2 = num3 * DTR
        dblT1_VRPPUP[0] = newPosN(decOrig_TGT.y, decOrig_TGT.x, brg2, intActionToTgt).toDouble()
        dblT1_VRPPUP[1] = newPosE(decOrig_TGT.y, decOrig_TGT.x, brg2, intActionToTgt).toDouble()
    }

    private fun vrpType2() {
        // D64: the ingress height above the target (WDP: intIngressAlt, which carries the ground under it)
        intIngressAltDist = cint(PopupX87.div((intIngressAlt - targetElv).toDouble(), PopupX87.tan(dblClimbAngleRad)))
        val num = intPupToPDP - intIngressAltDist
        val num3 = num + intPDPtoExtraDist
        val num4 = cint(sqrt(num3.toDouble().pow(2.0) + intExtraToTgt.toDouble().pow(2.0)))
        val num5 = (x87(PopupX87.atan(intExtraToTgt.toDouble() / num3.toDouble()).toFloat()) * x87(180f) / PI).toFloat()
        var num6 = if (!blnTurn) (dblPullHeading - num5.toDouble()).toFloat() else (dblPullHeading + num5.toDouble()).toFloat()
        val n6 = num6
        if (n6 < 0f) num6 = 360f + num6 else if (n6 >= 360f) num6 -= 360f
        val num9 = wrapF(num6 - 180f)
        var num11 = 0
        when (intVIPtoPUPswitch) {
            0 -> num11 = 2 * num + intPDPtoExtraDist
            1, 2, 3, 4, 5 -> num11 = cint(x87(num.toFloat()) + x87(intVIPtoPUPswitch.toFloat()) * x87(NM_TO_FT) + x87(intPDPtoExtraDist.toFloat()))
        }
        val num12 = cint(sqrt(num11.toDouble().pow(2.0) + intExtraToTgt.toDouble().pow(2.0)))
        val num13 = (x87(PopupX87.atan(intExtraToTgt.toDouble() / num11.toDouble()).toFloat()) * x87(180f) / PI).toFloat()
        val num14: Float
        val num15: Float
        if (!blnTurn) {
            num14 = (dblPullHeading - num13.toDouble()).toFloat()
            num15 = wrapF(num14 - 180f)
        } else {
            num14 = (dblPullHeading + num13.toDouble()).toFloat()
            num15 = wrapF(num14 - 180f)
        }
        val num18 = num4.toFloat() / NM_TO_FT
        val num19 = num12.toFloat() / NM_TO_FT
        strVRP_PullBrg = PopupNet.f1(dblPullBrg)
        strVRPbrg = PopupNet.f1(num15)
        strVRPhdg = PopupNet.f1(num14)
        strVRPrng = num12.toString()
        strVRPnm = PopupNet.f1(num19)
        strVRPelv = intIngressAlt.toString()
        strVRP_PUPbrg = PopupNet.f1(num9)
        strVRP_PUPhdg = PopupNet.f1(num6)
        strVRP_PUPrng = num4.toString()
        strVRP_PUPnm = PopupNet.f1(num18)
        strVRP_PUPelv = intIngressAlt.toString()
        val brg = num15 * DTR
        dblT2_VRP[0] = newPosN(decOrig_TGT.y, decOrig_TGT.x, brg, num12).toDouble()
        dblT2_VRP[1] = newPosE(decOrig_TGT.y, decOrig_TGT.x, brg, num12).toDouble()
        dblVRP[0] = dblT2_VRP[0]
        dblVRP[1] = dblT2_VRP[1]
        val brg2 = num9 * DTR
        dblT2_VRPPUP[0] = newPosN(decOrig_TGT.y, decOrig_TGT.x, brg2, num4).toDouble()
        dblT2_VRPPUP[1] = newPosE(decOrig_TGT.y, decOrig_TGT.x, brg2, num4).toDouble()
    }

    /** `VIP_Type1` and `VIP_Type2`, which differ only in the arrays they read (WDP's Type 2 also read OA2 wrongly: D2). */
    private fun vip(type1: Boolean) {
        val pup = if (type1) dblT1_VIPPUP else dblT2_VIPPUP
        val src = if (type1) dblT1_VRPPUP else dblT2_VRPPUP
        pup[0] = src[0]; pup[1] = src[1]
        val ipN = PopupNet.str(decOrig_IP.y)
        val ipE = PopupNet.str(decOrig_IP.x)
        val num = bearing(ipN, ipE, PopupNet.str(decOrig_TGT.y), PopupNet.str(decOrig_TGT.x))
        strVIPbrg = PopupNet.f1(num * RTD)
        val num2 = distance(ipN, ipE, PopupNet.str(decOrig_TGT.y), PopupNet.str(decOrig_TGT.x))
        strVIPrng = num2.toString()
        strVIPnm = PopupNet.f1(num2.toFloat() / NM_TO_FT)
        strVIPelv = intIngressAlt.toString()
        var num3 = (PopupNet.toDouble(strVIPbrg) - 180.0).toFloat()
        if (num3 < 0f) num3 += 360f
        strVIPhdg = PopupNet.f1(num3)
        val num4 = bearing(ipN, ipE, PopupNet.str(pup[0]), PopupNet.str(pup[1]))
        strVIP_PUPbrg = PopupNet.f1(num4 * RTD)
        val num5 = distance(ipN, ipE, PopupNet.str(pup[0]), PopupNet.str(pup[1]))
        strVIP_PUPrng = num5.toString()
        strVIP_PUPnm = PopupNet.f1(num5.toFloat() / NM_TO_FT)
        strVIP_PUPelv = intIngressAlt.toString()
        var num6 = (PopupNet.toDouble(strVIP_PUPbrg) - 180.0).toFloat()
        if (num6 < 0f) num6 += 360f
        strVIP_PUPhdg = PopupNet.f1(num6)
        val num7 = bearing(ipN, ipE, PopupNet.str(oa1.y), PopupNet.str(oa1.x))
        strVIP_OA1brg = PopupNet.f1(num7 * RTD)
        val num8 = distance(ipN, ipE, PopupNet.str(oa1.y), PopupNet.str(oa1.x))
        strVIP_OA1rng = num8.toString()
        strVIP_OA1nm = PopupNet.f1(num8.toFloat() / NM_TO_FT)
        strVIP_OA1elv = intPullDownAlt.toString()
        var num9 = (PopupNet.toDouble(strVIP_OA1brg) - 180.0).toFloat()
        if (num9 < 0f) num9 += 360f
        strVIP_OA1hdg = PopupNet.f1(num9)
        // WDP's Type 2 read OA2's north with OA1's east, measuring a point that is neither (D2)
        val oa2E = oa2.x
        val num10 = bearing(ipN, ipE, PopupNet.str(oa2.y), PopupNet.str(oa2E))
        strVIP_OA2brg = PopupNet.f1(num10 * RTD)
        val num11 = distance(ipN, ipE, PopupNet.str(oa2.y), PopupNet.str(oa2E))
        strVIP_OA2rng = num11.toString()
        strVIP_OA2elv = "0"
        val vipArr = if (type1) dblT1_VIP else dblT2_VIP
        vipArr[0] = newPosN(decOrig_IP.y, decOrig_IP.x, num, num2).toDouble()
        vipArr[1] = newPosE(decOrig_IP.y, decOrig_IP.x, num, num2).toDouble()
    }

    private fun vipType1() = vip(true)
    private fun vipType2() = vip(false)


    private fun nmOf(s: String?): String = PopupNet.f1(PopupNet.toDouble(s) / NM_TO_FT.toDouble())

    private fun fillLabelsBMS() {
        if (blnRef) {
            label("lblVIPwp", strIPpoint)
            label("lblVIPbrg", strVIPbrg); label("lblVIPrng", strVIPrng); label("lblVIPelv", strVIPelv)
            label("lblVIPnm", nmOf(strVIPrng))
            label("lblPUPwp", strIPpoint)
            label("lblPUPrng", strVIP_PUPrng); label("lblPUPbrg", strVIP_PUPbrg); label("lblPUPelv", strVIP_PUPelv)
            label("lblPUPnm", nmOf(strVIP_PUPrng))
            label("lblOA1wp", strIPpoint)
            label("lblOA1rng", strVIP_OA1rng); label("lblOA1brg", strVIP_OA1brg); label("lblOA1elv", strVIP_OA1elv)
            label("lblOA1nm", nmOf(strVIP_OA1rng))
            label("lblOA2wp", strIPpoint)
            label("lblOA2rng", strVIP_OA2rng); label("lblOA2brg", strVIP_OA2brg); label("lblOA2elv", strVIP_OA2elv)
            label("lblOA2nm", nmOf(strVIP_OA2rng))
        } else {
            label("lblVIPwp", strWaypoint)
            label("lblVIPbrg", strVRPbrg); label("lblVIPrng", strVRPrng); label("lblVIPelv", strVRPelv)
            label("lblVIPnm", nmOf(strVRPrng))
            label("lblPUPwp", strWaypoint)
            label("lblPUPbrg", strVRP_PUPbrg); label("lblPUPrng", strVRP_PUPrng); label("lblPUPelv", strVRP_PUPelv)
            label("lblPUPnm", nmOf(strVRP_PUPrng))
            label("lblOA1wp", strWaypoint)
            label("lblOA1rng", strVRP_OA1rng); label("lblOA1brg", strVRP_OA1brg); label("lblOA1elv", strVRP_OA1elv)
            label("lblOA1nm", nmOf(strVRP_OA1rng))
            label("lblOA2wp", strWaypoint)
            label("lblOA2rng", strVRP_OA2rng); label("lblOA2brg", strVRP_OA2brg); label("lblOA2elv", strVRP_OA2elv)
            label("lblOA2nm", nmOf(strVRP_OA2rng))
        }
    }

    /** `FillLabelsAF`: the same, except that in VRP mode the PUP bearing box shows the VRP-to-PUP **heading**. */
    private fun fillLabelsAF() {
        if (blnRef) {
            fillLabelsBMS()
        } else {
            label("lblVIPwp", strWaypoint)
            label("lblVIPbrg", strVRPbrg); label("lblVIPrng", strVRPrng); label("lblVIPelv", strVRPelv)
            label("lblVIPnm", nmOf(strVRPrng))
            label("lblPUPwp", strWaypoint)
            label("lblPUPbrg", strVRP_PUPhdg); label("lblPUPrng", strVRP_PUPrng); label("lblPUPelv", strVRP_PUPelv)
            label("lblPUPnm", nmOf(strVRP_PUPrng))
            label("lblOA1wp", strWaypoint)
            label("lblOA1rng", strVRP_OA1rng); label("lblOA1brg", strVRP_OA1brg); label("lblOA1elv", strVRP_OA1elv)
            label("lblOA1nm", nmOf(strVRP_OA1rng))
            label("lblOA2wp", strWaypoint)
            label("lblOA2rng", strVRP_OA2rng); label("lblOA2brg", strVRP_OA2brg); label("lblOA2elv", strVRP_OA2elv)
            label("lblOA2nm", nmOf(strVRP_OA2rng))
        }
    }

    private fun saveNavOffsets() {
        if (!loaded) return
        val modesel = if (blnRef) 1 else 2
        fun z(s: String?) = if (s.isNullOrEmpty()) "0" else s
        strVIPbrg = z(strVIPbrg); strVIPrng = z(strVIPrng); strVIPelv = z(strVIPelv)
        strVIP_PUPbrg = z(strVIP_PUPbrg); strVIP_PUPrng = z(strVIP_PUPrng); strVIP_PUPelv = z(strVIP_PUPelv)
        strVIP_OA1brg = z(strVIP_OA1brg); strVIP_OA1rng = z(strVIP_OA1rng); strVIP_OA1elv = z(strVIP_OA1elv)
        strVIP_OA2brg = z(strVIP_OA2brg); strVIP_OA2rng = z(strVIP_OA2rng); strVIP_OA2elv = z(strVIP_OA2elv)
        strVRPbrg = z(strVRPbrg); strVRPrng = z(strVRPrng); strVRPelv = z(strVRPelv)
        strVRP_PUPbrg = z(strVRP_PUPbrg); strVRP_PUPrng = z(strVRP_PUPrng); strVRP_PUPelv = z(strVRP_PUPelv)
        strVRP_OA1brg = z(strVRP_OA1brg); strVRP_OA1rng = z(strVRP_OA1rng); strVRP_OA1elv = z(strVRP_OA1elv)
        strVRP_OA2brg = z(strVRP_OA2brg); strVRP_OA2rng = z(strVRP_OA2rng); strVRP_OA2elv = z(strVRP_OA2elv)
        val wp = numWaypoint.toInt32()
        // the VIP lines are measured from the IP: the steerpoint before the target, or the IP STPT picked (D87)
        val wp1 = ipStpt ?: numWaypoint.minus(1).toInt32()
        navModesel = modesel
        // one field at a time, as the original assigns them: an unreadable figure stops the rest
        fun put(key: String, stpt: Int, brg: String?, rng: String?, elv: String?) {
            var o = navOffsets[key] ?: Offset()
            o = o.copy(stpt = stpt); navOffsets[key] = o
            o = o.copy(bearing = PopupNet.toSingle(brg)); navOffsets[key] = o
            o = o.copy(range = toInteger(rng)); navOffsets[key] = o
            o = o.copy(elv = toInteger(elv)); navOffsets[key] = o
        }
        put("VIP", wp1, strVIPbrg, strVIPrng, strVIPelv)
        put("VIPPUP", wp1, strVIP_PUPbrg, strVIP_PUPrng, strVIP_PUPelv)
        put("VRP", wp, strVRPbrg, strVRPrng, strVRPelv)
        put("VRPPUP", wp, strVRP_PUPbrg, strVRP_PUPrng, strVRP_PUPelv)
        put("OA1_1", wp1, strVIP_OA1brg, strVIP_OA1rng, strVIP_OA1elv)
        put("OA1_2", wp, strVRP_OA1brg, strVRP_OA1rng, strVRP_OA1elv)
        put("OA2_1", wp1, strVIP_OA2brg, strVIP_OA2rng, strVIP_OA2elv)
        put("OA2_2", wp, strVRP_OA2brg, strVRP_OA2rng, strVRP_OA2elv)
        // then the DTC page refreshes its Pop-up profile from them
        dtcCall("Profiles(" + (dtcProfile ?: "") + ")")
    }

    /**
     * What the DataCard prints of this page when its profile is Pop-up (`FillAttackType` reads the DTC page's copies,
     * which `DTC()` sets, and the page's own pull heading, pull-down altitude, climb, turn and HUD check), and the
     * offsets `SaveNavOffsets` left in `PopUpNavOffsets` for the DTC page's `Profiles()`.
     */
    fun toCard(a: DataCardPlan.Attack, into: DataCardPlan.NavOffsets) {
        a.popRef = blnRef; a.popPullHeading = dblPullHeading; a.popPullDownAlt = intPullDownAlt; a.popClimbAngleDeg = intClimbAngleDeg
        a.popTurnVal = labels["lblTurnVal"] ?: ""; a.popTargetHud = labels["lblTargetHUDval"] ?: ""
        a.strIngrHgt = intIngressAlt.toString(); a.strIngrSpd = intCAS.toString()
        a.strRelHgt = intReleaseHeight.toString(); a.strRelSpd = intCAS.toString()
        a.strAttHed = intAttackHeadingDeg.toString(); a.strDA = intDiveAngleDeg.toString(); a.strPullingG = PopupNet.str(dblPullingGs)
        into.modesel = navModesel
        navOffsets.values.forEachIndexed { i, o -> into.points[i].let { it.stpt = o.stpt; it.bearing = o.bearing; it.range = o.range; it.elv = o.elv } }
    }

    private fun dtc() {
        dtcStrings["strIngrHgt"] = intIngressAlt.toString()
        dtcStrings["strIngrSpd"] = intCAS.toString()
        dtcStrings["strRelHgt"] = intReleaseHeight.toString()
        dtcStrings["strRelSpd"] = intCAS.toString()
        dtcStrings["strAttHed"] = intAttackHeadingDeg.toString()
        dtcStrings["strDA"] = intDiveAngleDeg.toString()
        dtcStrings["strPullingG"] = PopupNet.str(dblPullingGs)
        dtcStrings["strTurn"] = labels["lblTurnVal"] ?: ""
        // D89: WDP handed on the label's caption ("Target visible in the HUD", cntPopUp l.7457), the same words whatever
        // the check said; the answer itself is what HADB and TOSS hand on
        dtcStrings["strTGTHUD"] = labels["lblTargetHUDval"] ?: ""
    }

    // ---------------------------------------------------------------- the map

    /** One thing `Draw` draws on the map, in picSatView's pixels; colours are WinForms names. */
    sealed class MapItem {
        data class Line(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val color: String, val dash: String = "Solid") : MapItem()
        data class Rect(val x: Int, val y: Int, val w: Int, val h: Int, val color: String) : MapItem()
        data class Ellipse(val x: Int, val y: Int, val w: Int, val h: Int, val color: String) : MapItem()
        data class Pie(val x: Int, val y: Int, val w: Int, val h: Int, val start: Int, val sweep: Int, val color: String) : MapItem()
        data class Polygon(val points: List<Pair<Int, Int>>, val color: String) : MapItem()
        data class Text(val text: String, val x: Int, val y: Int, val color: String, val font: String, val size: Int) : MapItem()
    }

    /**
     * What `Draw` makes: [crop] (x, y, w, h) of the theater map, stretched to [size] square, and the marks on it.
     * The same crop in sim feet is [upperN]/[upperE] (its upper-left corner) and [spanFt] across, which is what the
     * app's own theater map is cut to: the app's map is not WDP's bitmap, so the pixel crop means nothing to it.
     */
    class MapPicture(
        val crop: IntArray, val size: Int, val items: List<MapItem>,
        val upperN: Double = 0.0, val upperE: Double = 0.0, val spanFt: Double = 0.0,
        /**
         * How many of [items] come before `Draw`'s own attack marks (the route and the threats); -1 = all of them. The
         * app's maps draw those, then the one attack drawing ([attack], `ui/components/AttackDraw.kt`) in place of
         * WDP's marks; [items] stay WDP's whole picture, mark for mark, for the page tests.
         */
        val underlay: Int = -1,
        /** The attack drawn over [items]' first [underlay]: set by the Planner ([withAttack]); null draws [items] whole. */
        val attack: com.bmscompanion.app.data.mission.AttackOverlay? = null,
        /**
         * Which of [items] is the route's "IP" square (WDP's rule: the steerpoint before the first strike action), or
         * -1. Under the one attack drawing it is drawn as a plain steerpoint: the VIP cue is the only IP mark (B5).
         */
        val ipSquare: Int = -1,
    ) {
        fun withAttack(a: com.bmscompanion.app.data.mission.AttackOverlay?): MapPicture =
            MapPicture(crop, size, items, upperN, upperE, spanFt, underlay, a, ipSquare)
    }

    /** `ScalePointToMap`: theater feet to the map's pixels, from its upper-left corner. */
    internal fun scalePointToMap(upperN: Double, upperE: Double, north: Double, east: Double, scale: Double): Pair<Int, Int> {
        if (north == 0.0 || east == 0.0) return 0 to 0
        val y = cint((upperN - north) * scale)
        val x = cint((east - upperE) * scale)
        return x to y
    }

    /** `cntPopUp_Paint`: Windows repainting the page runs `Draw` (unforced) — whenever it repaints, which is its business. */
    fun paint() = guard { draw(false) }

    /** `Draw(ref Force)`: nothing unless the main form is loaded and the Profile tab is up (or [force]). */
    fun draw(force: Boolean) {
        if (!main.blnLoaded) return
        val pnlProfileVisible = (shown["pnlProfile"] ?: true) && visible
        if (!pnlProfileVisible && !force) return
        label("lblWP", if (blnRef) "IP" else "STPT")
        mapPicture = picture(main.whiteMap)
        if (blnRef) {
            label("lblVIP", "")
        } else {
            label("lblVIP", "VRP")
            vipLegendFore = "DodgerBlue"
        }
    }

    /**
     * `Draw`'s picture, made whenever it is asked for (B1: the DataCard and the kneeboard read the plan as it is now,
     * where [mapPicture] is only made while the Profile panel is up), in WDP's white-map inks when [whiteMap] (B2: the
     * card's white map, which WDP's Pop-up never read). Null before the page is loaded.
     */
    fun picture(whiteMap: Boolean): MapPicture? = if (!main.blnLoaded) null else buildPicture(whiteMap)

    private fun buildPicture(whiteMap: Boolean): MapPicture {
        val items = ArrayList<MapItem>()
        val w = MAP_PX
        val num = cint(w.toDouble() / 2.0)
        val num2 = (w.toDouble() / intZoomFactor.toDouble()).toFloat()
        // float locals kept across calls: stored as floats, the sums before them on the x87 stack
        val num3 = (decOrig_TGT.y.toDouble() + x87(num.toFloat()) / x87(num2)).toFloat()
        val num4 = (decOrig_TGT.x.toDouble() - x87(num.toFloat()) / x87(num2)).toFloat()
        val pt = cint(decOrig_TGT.x.toDouble()) to cint(decOrig_TGT.y.toDouble())
        val pt2 = cint(decOrig_IP.x.toDouble()) to cint(decOrig_IP.y.toDouble())
        var pt3 = 0 to 0
        var pt4 = 0 to 0
        var pt5 = 0 to 0
        if (blnProfile) {
            if (blnRef) {
                pt3 = cint(dblT1_VIP[1]) to cint(dblT1_VIP[0]); pt4 = cint(dblT1_VIPPUP[1]) to cint(dblT1_VIPPUP[0])
            } else {
                pt5 = cint(dblT1_VRP[1]) to cint(dblT1_VRP[0]); pt4 = cint(dblT1_VRPPUP[1]) to cint(dblT1_VRPPUP[0])
            }
        } else if (blnRef) {
            pt3 = cint(dblT2_VIP[1]) to cint(dblT2_VIP[0]); pt4 = cint(dblT2_VIPPUP[1]) to cint(dblT2_VIPPUP[0])
        } else {
            pt5 = cint(dblT2_VRP[1]) to cint(dblT2_VRP[0]); pt4 = cint(dblT2_VRPPUP[1]) to cint(dblT2_VRPPUP[0])
        }
        val n3 = num3.toDouble(); val n4 = num4.toDouble(); val sc = num2.toDouble()
        fun scale(n: Number, e: Number) = scalePointToMap(n3, n4, n.toDouble(), e.toDouble(), sc)
        if (pt.first != 0) scale(pt.second, pt.first)
        val p6 = if (pt2.first != 0) scale(pt2.second, pt2.first) else 0 to 0
        val p7 = scale(pt3.second, pt3.first)
        val p8 = scale(pt5.second, pt5.first)
        val p9 = scale(pt4.second, pt4.first)
        val p10 = scale(oa1.y, oa1.x)
        val p11 = scale(oa2.y, oa2.x)
        val p12 = scale(map.y, map.x)
        val p13 = scale(apex.y, apex.x)
        var num5 = cint(n4 / main.feetPerPixel)
        var num6 = cint((main.campH - n3) / main.feetPerPixel)
        if (num5 <= 0) num5 = 0
        if (num6 <= 0) num6 = 0
        var num7 = cint((x87(w.toFloat()) / x87(num2)) / main.feetPerPixel)
        var num8 = cint((x87(w.toFloat()) / x87(num2)) / main.feetPerPixel)
        if (num7 >= main.mapWidth) num7 = main.mapWidth
        if (num8 >= main.mapHeight) num8 = main.mapHeight
        if (num5 + num7 >= main.mapWidth) num5 = main.mapWidth - num7
        if (num6 + num8 >= main.mapHeight) num6 = main.mapHeight - num8
        // (two ranges in nm, computed and thrown away)
        distance(PopupNet.str(decOrig_IP.x), PopupNet.str(decOrig_IP.y), PopupNet.str(decOrig_TGT.x), PopupNet.str(decOrig_TGT.y))
        distance(PopupNet.str(decOrig_TGT.x), PopupNet.str(decOrig_TGT.y), PopupNet.str(dblT1_VRP[0]), PopupNet.str(dblT1_VRP[1]))
        val ink = if (!whiteMap) "White" else "Black"
        // the flight plan, from steerpoint 2 on
        var ipSquare = -1
        var prev = 0 to 0
        var num9 = 0
        var flag = false
        var i = 1
        while (i != 24) {
            val point15 = prev
            val num10 = num9
            val num11: Int
            val num12: Int
            val num13: Int
            if (blnCampTE) {
                num11 = cint(main.tblCampSTPT[i].falconX.toDouble()); num12 = cint(main.tblCampSTPT[i].falconY.toDouble())
                num9 = PopupNet.toInteger(main.tblCampSTPT[i].action ?: "0"); num13 = PopupNet.toInteger(main.tblCampSTPT[i + 1].action ?: "0")
            } else {
                num11 = cint(main.tblMissionSTPT[i].falconX.toDouble()); num12 = cint(main.tblMissionSTPT[i].falconY.toDouble())
                num9 = PopupNet.toInteger(main.tblMissionSTPT[i].action); num13 = PopupNet.toInteger(main.tblMissionSTPT[i + 1].action)
            }
            if (num11 == 0 && num12 == 0) break
            val p14 = scale(num12, num11)
            prev = p14
            if (i == 1) {
                items += MapItem.Rect(p14.first - 8, p14.second - 8, 16, 16, ink)
                items += MapItem.Rect(p14.first - 10, p14.second - 10, 20, 20, "Blue")
            } else if (!flag) {
                if (num13 == 14 || num13 == 15 || num13 == 17 || num13 == 18) {
                    ipSquare = items.size
                    items += MapItem.Rect(p14.first - 8, p14.second - 8, 16, 16, ink); flag = true
                } else items += MapItem.Ellipse(p14.first - 8, p14.second - 8, 16, 16, ink)
            } else items += MapItem.Ellipse(p14.first - 8, p14.second - 8, 16, 16, ink)
            val textInk = if (whiteMap) "Black" else "White"
            items += if (num9 == 7) MapItem.Text((i + 1).toString(), p14.first - 10, p14.second + 8, textInk, "GenericSansSerif", 10)
            else MapItem.Text((i + 1).toString(), p14.first + 5, p14.second + 5, textInk, "GenericSansSerif", 10)
            if (point15.first != 0 && point15.second != 0 && !(num9 == 7 && num10 == 7)) items += MapItem.Line(point15.first, point15.second, p14.first, p14.second, ink)
            i++
        }
        if (chbShowPPT) {
            for (k in 0 until 15) {
                val t = if (blnCampTE) main.tblCampPPT[k] else main.tblMissionPPT[k]
                val num14 = cint(t.falconX.toDouble())
                val num15 = cint(t.falconY.toDouble())
                if (num14 == 0 && num15 == 0) continue
                val p16 = scale(num15, num14)
                val num17 = cint(t.falconRNG.toDouble() * sc)
                items += MapItem.Rect(p16.first, p16.second, 1, 1, "Red")
                items += MapItem.Rect(p16.first - 1, p16.second - 1, 2, 2, "Black")
                items += MapItem.Ellipse(p16.first - num17, p16.second - num17, num17 * 2, num17 * 2, "Red")
                val text = if (chbShowPPTNr) (t.code ?: "") + " / " + (k + 56) else (t.code ?: "")
                val num19 = if (chbShowPPTNr) cint(text.length.toDouble() / 2.0 * 10.0 - 8.0) else cint(text.length.toDouble() / 2.0 * 10.0)
                items += MapItem.Text(text, p16.first - num19, p16.second + 5, "Red", "Arial", 10)
            }
        }
        // the route and the threats end here: the app's maps draw the one attack drawing over them (MapPicture.underlay)
        val underlay = items.size
        if (blnRef) {
            items += MapItem.Line(p6.first, p6.second, p9.first, p9.second, ink)
            items += MapItem.Line(p9.first, p9.second, p10.first, p10.second, ink)
            items += MapItem.Line(p10.first, p10.second, p13.first, p13.second, ink)
            items += MapItem.Line(p13.first, p13.second, p12.first, p12.second, ink)
            items += MapItem.Line(p12.first, p12.second, p7.first, p7.second, ink)
            items += MapItem.Rect(p6.first - 7, p6.second - 7, 14, 14, "LightBlue")
            items += MapItem.Rect(p7.first - 8, p7.second - 8, 16, 16, "Red")
            items += MapItem.Ellipse(p7.first - 8, p7.second - 8, 16, 16, ink)
            items += MapItem.Ellipse(p9.first - 10, p9.second - 10, 20, 20, "Magenta")
            items += MapItem.Polygon(listOf(p10.first to p10.second - 10, p10.first - 10 to p10.second + 10, p10.first + 10 to p10.second + 10), "Lime")
            if (chbOA2State == 1) {
                items += MapItem.Line(p11.first, p11.second, p7.first, p7.second, ink)
                items += MapItem.Pie(p11.first - 22, p11.second - 38, 45, 45, 67, 45, "Lime")
            }
        } else {
            items += MapItem.Line(p12.first, p12.second, num, num, ink)
            items += MapItem.Line(p8.first, p8.second, p9.first, p9.second, ink)
            items += MapItem.Line(p9.first, p9.second, p10.first, p10.second, ink)
            items += MapItem.Line(p10.first, p10.second, p13.first, p13.second, ink)
            items += MapItem.Line(p13.first, p13.second, p12.first, p12.second, ink)
            items += MapItem.Rect(num - 7, num - 7, 14, 14, "LightBlue")
            items += MapItem.Rect(num - 9, num - 9, 18, 18, "Red")
            items += MapItem.Ellipse(p8.first - 10, p8.second - 10, 20, 20, "DodgerBlue")
            items += MapItem.Ellipse(p9.first - 10, p9.second - 10, 20, 20, "Magenta")
            items += MapItem.Pie(p10.first - 22, p10.second - 38, 45, 45, 67, 45, "Lime")
            if (chbOA2State == 1) {
                items += MapItem.Line(p11.first, p11.second, num, num, ink)
                items += MapItem.Pie(p11.first - 22, p11.second - 38, 45, 45, 67, 45, "Lime")
            }
        }
        return MapPicture(intArrayOf(num5, num6, num7, num8), w, items, n3, n4, w.toDouble() / sc, underlay, ipSquare = ipSquare)
    }

    /**
     * The points [draw] marks, in feet rather than the map's pixels (see [AttackMap.Plot]), from the same figures:
     * the run from the VRP — or from the IP in VIP mode — through the pull-up point, OA1, the apex and the
     * pull-down to the target, and OA2 when the page places it. Read straight from the flow's figures, so it does not
     * wait for the Profile tab to be up as [draw] does. Null with no target.
     */
    fun attackPlot(): AttackMap.Plot? {
        if (!blnGotTGT) return null
        val vip = if (blnProfile) dblT1_VIP else dblT2_VIP
        val vipPup = if (blnProfile) dblT1_VIPPUP else dblT2_VIPPUP
        val vrp = if (blnProfile) dblT1_VRP else dblT2_VRP
        val vrpPup = if (blnProfile) dblT1_VRPPUP else dblT2_VRPPUP
        fun rounded(a: DoubleArray) = cint(a[0]).toDouble() to cint(a[1]).toDouble()
        fun point(p: FalconPoint) = p.y.toDouble() to p.x.toDouble()
        val tgt = cint(decOrig_TGT.y.toDouble()).toDouble() to cint(decOrig_TGT.x.toDouble()).toDouble()
        // the IP only in VIP mode (B4): a VRP attack has no IP, and the maps drew it loose beside the run
        val ip = if (blnRef && cint(decOrig_IP.x.toDouble()) != 0) cint(decOrig_IP.y.toDouble()).toDouble() to cint(decOrig_IP.x.toDouble()).toDouble() else null
        val pup = rounded(if (blnRef) vipPup else vrpPup)
        val oa1 = point(this.oa1)
        val oa2 = if (chbOA2State == 1) point(this.oa2) else null
        // VIP: from the IP, and the line ends at the VIP solution's target; VRP: from the VRP to the target itself
        val start = if (blnRef) ip else rounded(vrp)
        val end = if (blnRef) rounded(vip) else tgt
        return AttackMap.Plot(tgt, start, blnRef, pup, oa1, oa2, ip, listOfNotNull(start, pup, oa1, point(apex), point(map), end))
    }

    /**
     * The VRP these inputs lay out, in feet north and east (rounded as [attackPlot] rounds it), in VIP mode too — the
     * flow lays the VRP out whatever the reference — or null with no target. "IP STPT at the VRP" puts its steerpoint
     * here, and says when the steerpoint it put is no longer here.
     */
    fun vrpPoint(): Pair<Double, Double>? {
        if (!blnGotTGT) return null
        val vrp = if (blnProfile) dblT1_VRP else dblT2_VRP
        return cint(vrp[0]).toDouble() to cint(vrp[1]).toDouble()
    }

    // ---------------------------------------------------------------- what the program keeps, prints and saves

    /**
     * `MakePrintDocument`'s table, `strPopUpPrint[27, 2]` (row 0 and the right column below 16 stay empty; null is a
     * cell never written). WDP never calls it; it is kept because it is the page's own summary of a plan.
     */
    val print: Array<Array<String?>> = Array(27) { arrayOfNulls<String>(2) }

    fun makePrintDocument() {
        val text4 = if (blnRef) "VIP" else "VRP"
        strBomb = if (blnBomb) "Low Drag" else "High Drag"
        val num2 = intAttackHeadingDeg
        val text = when {
            num2 < 10 -> "  $intAttackHeadingDeg"
            num2 >= 100 -> intAttackHeadingDeg.toString()
            else -> " $intAttackHeadingDeg"
        }
        val num = PopupCoords.round(dblOffsetAngleDeg, 1)
        var text2 = ""
        val ph = labels["lblPullHeadingVal"] ?: ""
        if (ph != "") text2 = if (PopupNet.toDouble(ph) < 10.0) "  $ph" else if (!(PopupNet.toDouble(ph) >= 100.0)) " $ph" else ph
        val text3 = if (blnTurn) "Right" else " Left"
        val p = print
        p[1][0] = "Reference: $text4"
        p[2][0] = "Profile: " + (strProfile ?: "")
        p[3][0] = "Bomb: " + (strBomb ?: "")
        p[4][0] = " INPUT"
        p[5][0] = "Ingress Alt       $intIngressAlt ft"
        p[6][0] = "Dive Angle         $intDiveAngleDeg deg"
        p[7][0] = "Speed             $intCAS kts"
        p[8][0] = "Release Height   $intReleaseHeight ft"
        p[9][0] = "Tracking Time       $intTrackingTime sec"
        p[10][0] = "Pulling G's         " + PopupNet.str(dblPullingGs) + " g's"
        p[11][0] = "Turn to Target  $text3 turn"
        p[12][0] = "Attack Heading    $text deg"
        p[13][0] = " Turns, Climbs and Dives"
        p[14][0] = "Offset Angle     " + PopupNet.str(num) + " deg"
        p[15][0] = "Pull Heading    $text2 deg"
        p[16][0] = "Climb Angle        " + labels["lblClimbAngleVal"] + " deg"
        p[17][0] = "Pulldown Alt     " + labels["lblPulldownAltVal"] + " ft"
        p[18][0] = " Extra info"
        p[19][0] = "MAP              $intMAP ft"
        p[20][0] = "Angle Off        " + labels["lblAngleOffVal"] + " deg"
        p[21][0] = "Bombrange        $intBombRange ft"
        p[22][0] = "Aim of Dist      " + labels["lblAODval"] + " ft"
        p[23][0] = "Target in the HUD... " + labels["lblTargetHUDval"]
        p[24][0] = "IP : STPT " + (strIPpoint ?: "") + "  " + strIP_STPT_deg
        p[25][0] = "TGT: STPT " + (strWaypoint ?: "") + "  " + strTGT_STPT_deg
        p[26][0] = "Target area sketch"
        val l = { n: String -> labels[n] ?: "" }
        if (blnRef) {
            p[1][1] = " VIP-TO-TGT"; p[5][1] = " VIP-TO-PUP"; p[9][1] = " OA1"; p[13][1] = " OA2"
        } else {
            p[1][1] = " TGT-TO-VRP"; p[5][1] = " TGT-TO-PUP"; p[9][1] = "  OA1"; p[13][1] = "  OA2"
        }
        p[2][1] = " TBRG " + l("lblVIPbrg") + " deg"
        p[3][1] = "  RNG " + l("lblVIPrng") + " ft"
        p[4][1] = " ELEV   " + l("lblVIPelv") + " ft"
        p[6][1] = " TBRG " + l("lblPUPbrg") + " deg"
        p[7][1] = "  RNG " + l("lblPUPrng") + " ft"
        p[8][1] = " ELEV   " + l("lblPUPelv") + " ft"
        p[10][1] = "  RNG " + l("lblOA1rng") + " ft"
        p[11][1] = " TBRG " + l("lblOA1brg") + " deg"
        p[12][1] = " ELEV  " + l("lblOA1elv") + " ft"
        p[14][1] = "  RNG " + l("lblOA2rng") + " ft"
        p[15][1] = " TBRG " + l("lblOA2brg") + " deg"
        p[16][1] = " ELEV     " + l("lblOA2elv") + " ft"
    }

    /** `WriteTextFile`'s content: `strDocument`, which nothing in WDP ever assigns. */
    fun textFile(): String = strDocument ?: ""

    /** What fclsMain writes to Setup.ini's `[PopUp]` on the way out, which [load] reads back next time. */
    fun iniValues(): LinkedHashMap<String, String> = linkedMapOf(
        "Ref" to if (blnRef) "True" else "False",
        "Profile" to if (blnProfile) "True" else "False",
        "Bomb" to if (blnBomb) "True" else "False",
        "IngressAlt" to intIngressAlt.toString(),
        "DiveAngle" to intDiveAngleDeg.toString(),
        "CAS" to intCAS.toString(),
        "ReleaseHeight" to intReleaseHeight.toString(),
        "TrackingTime" to intTrackingTime.toString(),
        "PullingGs" to PopupNet.str(dblPullingGs),
        "Turn" to trbTurn.value.toString(),
        "AttackHdg" to intAttackHeadingDeg.toString(),
        "VIPtoPUP" to intVIPtoPUPswitch.toString(),
        "OA2atAO" to chbOA2State.toString(),
        "Waypoint" to numWaypoint.toString(),
    )

    /** The internal state the harness also reads out of the real control, by WDP's field names (floats and doubles as numbers). */
    internal fun state(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["strWaypoint"] = strWaypoint; m["strIPpoint"] = strIPpoint; m["strTGT_STPT_deg"] = strTGT_STPT_deg
        m["strIP_STPT_deg"] = strIP_STPT_deg; m["strDTC"] = strDTC; m["strProfile"] = strProfile; m["strBomb"] = strBomb
        m["blnLoadedFlag"] = loaded; m["blnVersion"] = blnVersion; m["blnCampTE"] = blnCampTE; m["blnGotTGT"] = blnGotTGT
        m["blnDoVIP"] = blnDoVIP; m["blnRef"] = blnRef; m["blnProfile"] = blnProfile; m["blnBomb"] = blnBomb; m["blnTurn"] = blnTurn
        m["TargetElv"] = targetElv; m["intZoomFactor"] = intZoomFactor; m["intIngressAlt"] = intIngressAlt
        m["decOrig_TGT.X"] = decOrig_TGT.x; m["decOrig_TGT.Y"] = decOrig_TGT.y; m["decOrig_TGT.Z"] = decOrig_TGT.z
        m["decOrig_IP.X"] = decOrig_IP.x; m["decOrig_IP.Y"] = decOrig_IP.y; m["decOrig_IP.Z"] = decOrig_IP.z
        for ((n, p) in listOf("Map" to map, "AOD" to aod, "Apex" to apex, "OA1" to oa1, "OA2" to oa2)) { m["$n.X"] = p.x; m["$n.Y"] = p.y }
        for ((n, a) in listOf("dblT1_VIP" to dblT1_VIP, "dblT1_VIPPUP" to dblT1_VIPPUP, "dblT1_VRP" to dblT1_VRP, "dblT1_VRPPUP" to dblT1_VRPPUP,
            "dblT2_VIP" to dblT2_VIP, "dblT2_VIPPUP" to dblT2_VIPPUP, "dblT2_VRP" to dblT2_VRP, "dblT2_VRPPUP" to dblT2_VRPPUP)) { m["$n[0]"] = a[0]; m["$n[1]"] = a[1] }
        return m
    }
}
