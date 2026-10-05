package com.bmscompanion.app.data.wdp

import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Weapon Delivery Planner's DataCard page, as a state machine: Falcas's `cntDataCard`, ported.
 *
 * One control, three of WDP's pages: the **DataCard** (`pnlPage_1`: airports, flight, package, weapons, attack
 * profile, the 24-row flight plan), the **Coordination Card** (`pnlPage_2`: every package flight's times, a nine-row
 * route, the transition level) and the **Briefing** (`pnlBrief`). WDP fills them from the campaign through its
 * `Fill*` methods and a web of `TextChanged` handlers; this class keeps those methods, their names and their
 * **order**, and the handlers, because the order is part of the answer — `FillDataCard` writing a VHF box fires
 * `FillCommCard`, which resets the route spinners, which is why a card shows what it shows.
 *
 * ## Where WDP's inputs come from in the app
 *
 * WDP reads the campaign file; the app has Falcon BMS's printed briefing and the data cartridge, parsed
 * (`MissionModels.kt`). `DataCardWiring.onMission` builds this class's inputs from them:
 *
 * | WDP source (campaign / main form) | the card's fields | the app's source |
 * |---|---|---|
 * | `FlightTable[Sel].waypoints[i]` `.GridX/.GridY` (km cells, x east) | flight plan heading, distance, speed | `WdpMission.steerpoints[i]` feet (per slot: the cartridge's, else BMS's mission file route, else the save's cell), laid along the briefing's own legs where none places it: `GridX = ⌊y/3279.98⌋`, `GridY = ⌊x/3279.98⌋` (a campaign waypoint sits mid-cell, so this recovers the cell) |
 * | `.GridZ` (tens of feet) | `lblAltN`, the fuel ladder | `Dtc.steerpoints[i].altFt / 10` |
 * | `.Arrive/.Depart` (campaign ms) | `lblTOSN`, speed | `BriefSteerpoint.time` (and the "Departure:" time a hold's comment carries), as ms from the start of a first campaign day ([dayStart]: a taxi time before it is blank) |
 * | `.Action` | `lblActionN`, landing and refuel rules | `BriefSteerpoint.desc`/`action` mapped to BMS's waypoint actions (`actionOf`) |
 * | `.Formation` | `txtFormationN` | `BriefSteerpoint.formation` (`formationOf`) |
 * | `FlightTable[Sel].plane_stats`, `laserCode` | NrAc, the seat buttons, laser codes | `PackageFlight.count` of our flight; each jet's laser code is not in the briefing (shown blank) |
 * | the flight's IDM addresses and A/A TACAN pairs (`FillBasicFlight`) | the flight block's IDM and TCN boxes | E3: the Link 16 STNs and A/A TACAN of FILE A (the briefing's "Link 16" table, else the cartridge's `[LINK16]`) where the jet has them ([link16Slot]); WDP's defaults otherwise |
 * | the package's flights (`FillPackages`) | `Packages[]`: callsign, count, type, task, T/O, push, TOT | `Briefing.package` rows |
 * | `strNames[1..4]` | `lblLead` … `lblWing4` | `Briefing.roster` of our flight |
 * | `strMission`, `SelPackageName` | `lblMission`, `lblPackage1/2` | `BriefOverview.mission`, `.packageId` |
 * | `FltRadio` (the theater's radio map) | package UHF/VHF, support UHF | the briefing's comm ladder, per callsign (`CommEntry`, "Copper2 (TCN: 059Y)" read as Copper2), and the support flights' frequencies from the app's support data; a package flight the ladder does not name gets an entry of 0 kHz (WDP's map has every flight, and stops on one it lacks) and is shown blank |
 * | the other package flights' waypoints (`FillCommCardPackages`) | Coordination Card push/TOT, hold | `PackageFlight.takeoff/push/target` as a three-point route; their holds and push altitudes are not in the briefing (shown blank) |
 * | the strike steerpoints' objectives (`FillPriTarget`/`FillSecTarget`) | `txtTGT_*`, `txtLatLong_*` | WDP's rule (`WdpMission.cardTargets`): a save's first and second strike steerpoints (action 17), named by the target designated to the card's seat; without a save, the mission's first two flight-plan targets (`WdpMission.choices`), position in feet as the TOSS page shows it |
 * | the campaign's team motto, threat tables (`FillMissionBriefing`) | the Briefing page | `Briefing.situation`, `overview`, the threat analysis |
 * | `tblApt[0..2]` (theater database) | departure / arrival / alternate rows | the fields every Mission page finds for the briefing (`airbases`), from the app's airport data (TACAN — or the nearest within 6 nm, starred — elevation, runways, ILS, frequencies); the comm ladder's Dep/Arr/Alt rows where they give a frequency |
 * | `tblTankerTrack`, `tblAwacsTrack`, `tblJSTARTrack` | support rows | the app's support data, as the Briefing page shows it (`supportAssets`: TACAN, UHF, type) and the station — a planned track's, or the briefing's sentence — bullseye-relative when the sim gives a bullseye; the on-station window from a planned track, else every track counts as on station and the window is shown blank |
 * | `cntDTC` (EWS comments, weapon profiles and their mode lists, nav offsets, ALOW, bingo) and the attack pages' `*NavOffsets` | EWS, weapons, attack block | the pilot's cartridge, read as the DTC page reads it; what the pilot changes here reaches the cartridge through the DTC page on Save DTC (`WdpCartridge.writeCardEntries`) |
 * | `cntDTC.blnMissionDtcLoaded` | the route box's spinners | a cartridge with steerpoints |
 * | the transition level of the selected airport | `txtTransitLvl`, hold altitudes | not in the briefing: 0, which WDP reads as its own default, FL180 |
 *
 * ## Checked against the program
 *
 * `--wdppagetest datacard <reference>`, against two independent harnesses that each build WDP's real main form
 * (unshown), put a synthetic campaign into the fields these methods read, run the card through `SelectNewFlight` and
 * `OpenFile`'s tail, then press its buttons and edit its fields through their own handlers, one card for the whole
 * run, and copy out every control the card fills:
 *
 * - `tools/wdpref page DataCard`: 2,400 missions × 539 controls — the 24-row flight plan, package, flight, airports
 *   (and their elevation's colour), EWS, weapons (and the ten cartridge boxes and six mode lists on the DTC page
 *   the weapon boxes write back to), attack block, ATIS, tankers/AWACS/JSTARS and the tanker lists' selection, the
 *   Coordination Card and its route box, the radio buttons' Enabled — plus 6,500 `CutSituation` calls, 43,000
 *   answers of VB's own `IsNumeric`/`ToDouble`/`ToInteger`/`ToSingle`/`ToShort`, and 13,000 direct calls to the
 *   card's `Distance` and `Heading` over the whole short range, bit for bit. Besides the page's buttons and boxes it
 *   presses the attack-profile buttons (`cntDTC.Profiles` with the Pop-up/HADB/TOSS pages' offsets, NaN bearings
 *   included), the pilot seats, the tanker lists' own buttons and rows, the weapon-mode lists, a route spinner moved
 *   without its Click, boxes typed into and never left, and the formation dialog's answer (`SetAllFormationsSame`);
 *   with `blnKeepNames` on and off, F-16 flights, non-ASCII callsigns, empty tanker names and TACANs.
 * - `tools/wdpref page DataCardVerify` (the independent verifier's, 1,800 adversarial rows: grid and clock extremes,
 *   24–30 steerpoints, odd builds and formations, VB numbers in every box, five tanker picks, radio-map values VB
 *   reads as numbers, `-1e300`, `NaN`) and its `DCV_CLEAN=1` variant.
 *
 * **Where WDP stops, the port stops — unless the stop was a bug (D19).** A row on which WDP threw an unhandled
 * exception is written as it stood, with the stage it stopped in; the port has to throw in the same stage and leave
 * the same card behind, which the next row starts from: a package flight missing from the radio map
 * (`FltRadio(-1)`), a null callsign in `GetFltComm`, and the VB conversions that throw on what was typed. The wiring
 * catches it, as Windows Forms' unhandled-exception box lets WDP carry on, because nothing may throw on a phone.
 * The stops that cost a pilot the whole card are fixed instead (see "Fixed" below), and each fix that changed a card
 * is named in [fixesUsed], so the comparison knows which rows WDP could not finish.
 *
 * **Not verified through the program**, because WDP only reaches them through campaign tables no harness builds:
 * `rbnCallsignClick`/`selectCallsign` (WDP's `SelectCallsign` reads the campaign's airports, package and support
 * tables and drives the performance, DTC and map pages; the port keeps its order and the card's own calls, and
 * takes the campaign's answers through [Campaign]); `FillPriTarget`/`FillSecTarget` (the campaign's objectives: the
 * wiring fills the target boxes from the app's own targets); `fillMissionBriefing`'s placement of the threat lists
 * (`CutSituation`, which it uses, is verified); the runway lists (`SetDepApt` needs the theater database); and the
 * wiring's mapping from the app's briefing (a mapping, not arithmetic: the table above).
 *
 * ## Fixed (Falcon BMS 4.38.1; the D-numbers are the plan's, and the card comparison lists each as expected)
 *
 * - **D19 — nothing stops the card.** WDP threw, and left the card half filled, on: a take-off less than the taxi
 *   time after the mission's first midnight (`checked((uint)(TakeOffTime - TaxiTime))` in `FillCommCard`: the
 *   training missions of forum #2301/#2343, which start minutes before take-off) — the taxi time is **blank**
 *   instead; a row above ~93,195 ft (`TasToIas`, whose answer the card threw away, took the square root of a
 *   temperature below absolute zero); a leg flown faster than 32,767 kt (`checked((short)…)`) — that row's speed
 *   is `---`; a NaN bearing (`new decimal(NaN)`) — that spinner is blank; a strike at steerpoint 1 or 2 (the attack
 *   pages' old minimum of 3); more than 255 waypoints (a checked byte); a fuel figure past an Int — that cell is
 *   blank and the ladder above it stays as it was.
 * - **D20 — `GetTime` rounds to the nearest second** and carries into the hour: WDP worked the clock out in
 *   floating hours and truncated, so 03:58:13 printed `03:58:12` and 00:59:59.99995 printed `00:60:00` (now
 *   `01:00:00`).
 * - **D21 — the flight plan's slips**: rows 9 and 11 printed an `m` before a hold's window; row 24 tested its own
 *   action for a landing where every other row tests the row before; the row after a landing printed the
 *   alternate's elevation only on row 9 — now a landing after the first is the alternate's field on whatever row it
 *   is, and the arrival's elevation is printed only on the first; an untimed row (the alternate, which the briefing
 *   gives no time) printed `00:00:00` — now `---`; a window printed the departure's `mm:ss` even when the hour had
 *   changed (`05:58:00-02:00`) — now the whole time then; `lblARR_VHF` was never cleared, so an arrival with no
 *   frequency kept the last one; "Else" + VRP + nm put OA2's range in OA1's box and left OA2 in feet;
 *   `FillBasicFlight` gave the fourth flight TACANs for the **second** flight's size; `txtLaserCode2`'s check read
 *   `txtLaserCode1`; the second taxi row named the third and fourth runway ends as their own opposite;
 *   `CreateFlightplan` cleared 24 of the plan's 25 entries, so a flight of 25 waypoints worked its fuel ladder from
 *   whatever the flight planned before had left in the last one.
 * - **D23 — the airports' UHF and VHF columns are the tower's.** WDP put Ground's UHF under "UHF" and
 *   Approach's under "VHF" for BMS 4.34 and later; the lines under the ROE box carry ATIS, Ground, Approach and
 *   Ops instead of repeating the tower.
 * - **Falcon BMS 4.38.1 only**: WDP's version branches (`MinorPart`/`Build`: the radio columns, the flight's laser
 *   codes, the AWACS UHF, the formation's high bits) always take 4.38.1's side.
 *
 * ## Quirks kept (each is the program's behaviour, and each is visible on a card)
 *
 * - The AWACS/tanker UHF: `FillTanker` clears **tanker 2's** UHF when tanker 1 has none, and the second tanker's
 *   UHF is only looked up when its TACAN is known. `FillTanker` always takes table row 0 as the first tanker, in
 *   the time window or not, and stops at once when that row has no name. (In the app every support flight's UHF
 *   and TACAN are known, so neither bites there.)
 * - A campaign TACAN string is shifted by 63 channels into the Y band (`29X` → `92Y`), and a value that does not
 *   parse keeps the previous tanker's channel. The app's own channels (the briefing's `059Y`, BMS's default 92Y)
 *   are already the ones to dial and are printed as they are ([Track.tcnFinal]).
 * - **VB's `Nothing` is `""`**: a tanker named `""` counts as no tanker, a TACAN of `""` as no TACAN
 *   (`Operators.CompareString(x, Nothing)`).
 * - **Callsigns are matched in VB's lower case** (`LCase`, en-US, NLS): one character at a time, so `İzmir 2`
 *   finds `izmir2`, while `ΟΥΡΑΝΟΣ 1` does not find `ουρανος1` (no final sigma) — and a package flight that is not
 *   found stops `FillAutoPackages`.
 * - `FillAutoFlight` sets the laser codes of the package slot on show from the **selected flight's** campaign
 *   record; the two only part in WDP's harness (the app's wiring always moves them together).
 * - A flight plan row's speed is `Dist / Δt` rounded to a multiple of 5 kt the way `RoundSpeed` does it, `999`
 *   when the times do not advance.
 * - Arithmetic is WDP's as it runs: the program is 32-bit only (`Required32Bit`), so its float expressions are
 *   evaluated on the x87 and rounded to float only where the JIT stores them. `Heading` returns unrounded;
 *   `Distance` rounds its north difference and not its east one — found from, and checked against, direct calls.
 * - `Math.Round` is to-even; `Strings.Format` rounds half up on 7 (float) or 15 (double) significant digits;
 *   `Conversions.ToString` of an elevation switches to `1E-05` below 0.0001, as .NET's "G" does.
 * - **What counts as a number is VB's** (`DataCardNet.isNumeric`): `&H1F400` is 128,000 (a radio-map VHF of
 *   `&H1F400` prints `128.000`), `$250`, `(300)` (-300) and `7-` are numbers, `,5`, `- 7` and a no-break
 *   space are not, `NaN` is but `Infinity` is not (Windows 10's en-US writes infinity as `∞`). So a tanker TACAN
 *   `(7)Y` is channel -7 and prints `56Y`, a bingo of `(300)` stays, and an ALOW of `- 7` becomes `0`.
 * - **The weapon boxes write the cartridge back through its masks**: the burst altitude and the arm delay are
 *   `MaskedTextBox`es with `0` as the prompt, read with the prompt included, so a burst altitude of 25 is
 *   stored as `2500` and an arm delay of 5 as `50.00` — and that is what a later non-number typed into the box
 *   puts back (`maskedText`).
 * - **The weapon-mode lists**: the chosen sub-mode is written to `strMode`, which nothing reads, so the box shows
 *   `" CCRP"` until the next `FillDataCard` puts the DTC page's `"CCRP"` back; fuse and single/pair keep their
 *   leading space. Setting the DTC page's list runs its change handler (and `FillDataCard`) only when the row
 *   changes, and single/pair's handler makes anything but row 0 — no selection too — `"PAIR"`. Opening one list
 *   closes the other two kinds in both profiles but not the same kind in the other profile.
 * - `FillAutoFlight` hands an F-16 flight's type to the performance page, but runs its `TypeChange` for the first
 *   package slot only ([Pages.aircraftType]).
 * - The attack-profile buttons copy the Pop-up/HADB/TOSS page's offsets **and its VIP/VRP mode**; None clears the
 *   offsets and keeps the mode. The DTC spinners print a bearing as `new decimal(float)` → `"F1"`: seven
 *   significant digits first (`VarDecFromR4`, half to even), then half away from zero.
 *
 * ## Not ported
 *
 * The JPG, print, kneeboard and airport-schedule exports (the app has its own kneeboards), the ATIS/METAR/TAF
 * generator and weather list (`blnWth`: they read the campaign's weather file; without one WDP prints nothing,
 * which is what the port does), the chart buttons (the app has its own charts), the timer, loading/saving `.bdc`
 * datacards and codewords, package timing, the dialogs behind `btnChangeToFlight`, `btnSelDiffFlight`,
 * `btnViewMilCodes`, the airport and pilot-name labels and the formation boxes (the formation dialog's *answer* is
 * ported: [formationChosen]), `btnGetDTC`/`btnSaveDTC` (the DTC page's files), `lblTOSpec` (the performance page's
 * pitch and power), the map (`FillMap`, `ShowTarget`, the plan pictures), and hiding the route spinners on mouse
 * hover (there is no hover on a touch screen). The other pages' side of the calls the card makes — the attack
 * pages' `STPTChange`, the performance page's cruise altitude and `ProgramFlow`, `SetLoadout` — are handed out
 * through [Pages]. `FillMissionBriefing`'s campaign lookups (mission descriptions, threat lists from the order of
 * battle) are not in the briefing text; the page's layout logic is ported (`fillMissionBriefing`) and fed from the
 * briefing.
 */
class DataCardPlan {

    companion object {
        // clsPhyconst, as floats
        const val FT_TO_NM = 0.0001646f
        const val NM_TO_FT = 6076.1157f
        const val KM_TO_FT = 3279.98f
        const val RTD = 57.29578f
        // CampLib's own, which differ
        const val L_NM_TO_FT = 6076.211f
        const val L_FEET_PER_KM = 3279.98f
        const val CAMPAIGN_HOURS = 3600000
        const val CAMPAIGN_MINUTES = 60000
        /** the weapon-mode lists' items (the card's and the DTC page's are the same) */
        val SUBMODES = listOf("CCRP", "CCIP", "DTOS", "LADD")
        val FUZES = listOf("NSTL", "NOSE", "TAIL")
        val SGL_PAIR = listOf("SGL", "PAIR")

        /** `CampLib.ActionString`. */
        fun actionString(action: Int): String = when (action) {
            -1 -> "Precision"; 0 -> "Nav"; 1 -> "TakeOff"; 2 -> "Push"; 3 -> "Split"; 4 -> "Refuel"; 5 -> "Rearm"
            6 -> "PickUp"; 7 -> "Land"; 8 -> "Holding"; 9 -> "CASCAP"; 10 -> "Escort"; 11 -> "Sweep"; 12 -> "CAP"
            13 -> "Intrcpt"; 14 -> "GNDStrk"; 15 -> "NAVStrk"; 16 -> "S&D"; 17 -> "Strike"; 18 -> "Bomb"; 19 -> "SEAD"
            20 -> "ELINT"; 21 -> "RECON"; 22 -> "Rescue"; 23 -> "ASW"; 24 -> "Tanker"; 25 -> "Airdrop"; 26 -> "JAM"
            27 -> "Land 2"; 28 -> "B5"; 29 -> "B6"; 30 -> "FAC"
            else -> action.toString()
        }

        /** `CampLib.FormationString`. */
        fun formationString(f: Int): String = when (f) {
            0 -> "Spread"; 1 -> "Wedge"; 2 -> "Trail"; 3 -> "Ladder"; 4 -> "Stack"; 5 -> "ResCell"; 6 -> "Box"
            7 -> "Arrowhead"; 8 -> "Fluid"; 9 -> "Vic"; 10 -> "EchelonRight"; 11 -> "Finger 4"; 12 -> "LineAbreast"
            13 -> "EchelonLeft"; 14 -> "Diamond"
            else -> ""
        }

        /**
         * `CampLib.GetTime(uint)`: the time of day of a campaign time in ms, **to the nearest second** (D20). WDP
         * worked it out in floating hours and truncated, so an exact 03:58:13 printed `03:58:12`, and 59 min
         * 59.99995 s bumped the minute to 60 without carrying it into the hour (`00:60:00`, where this gives
         * `01:00:00`).
         */
        fun getTime(t: Long): String {
            if (t < 0L) return ""
            val s = (t + 500L) / 1000L
            fun two(n: Long) = if (n >= 10) n.toString() else "0$n"
            return two(s / 3600L % 24L) + ":" + two(s % 3600L / 60L) + ":" + two(s % 60L)
        }

        /**
         * `CampLib.GetTimeDay(uint)`: "1, 04:21:00" — the campaign day (the first is 1) and the time of day. The
         * DataCard's Current Time and the Airport Schedule print it, from a save's own clock, which runs in
         * milliseconds (06:46:32.561): the second is **truncated**, as WDP and BMS's own briefing print it, but in whole
         * milliseconds, so an exact second is never the one before (WDP's floating hours printed 03:58:13 as 03:58:12,
         * D20) and minute 60 cannot happen.
         */
        fun getTimeDay(t: Long): String {
            if (t < 0L) return ""
            val s = t / 1000L
            fun two(n: Long) = if (n >= 10) n.toString() else "0$n"
            return (s / 86_400L + 1L).toString() + ", " + two(s / 3600L % 24L) + ":" + two(s % 3600L / 60L) + ":" + two(s % 60L)
        }

        /** VB `Strings.Mid(s, start, length)`, 1-based. */
        fun mid(s: String?, start: Int, length: Int = Int.MAX_VALUE): String {
            val t = s ?: return ""
            if (start > t.length) return ""
            val from = start - 1
            val to = if (length >= t.length - from) t.length else from + length
            return t.substring(from, to)
        }

        /**
         * `fclsMain.RoundUp(num, multiple)`: `checked(num + multiple - num2)`, so a fuel figure within a multiple of
         * `Int.MAX_VALUE` throws (OverflowException) where it would wrap; the division and product are unchecked.
         */
        fun roundUp(num: Int, multiple: Int): Int {
            if (multiple == 0) return 0
            val num2 = DataCardNet.roundInt(multiple.toDouble() / kotlin.math.abs(multiple).toDouble())
            val sum = num.toLong() + multiple.toLong() - num2.toLong()
            if (sum > Int.MAX_VALUE || sum < Int.MIN_VALUE) throw ArithmeticException("Arithmetic operation resulted in an overflow.")
            return sum.toInt() / multiple * multiple
        }

        private fun s(x: String?): String = x ?: ""

        /**
         * What a Windows Forms `MaskedTextBox` with a digits-and-literals mask (`"0000"`, `"00.00"`) reads after
         * its Text is set — the cartridge's boxes on WDP's DTC page, which the weapon boxes' Leave handlers write
         * back to and read from. Each input character goes to the next position: a digit fills it, a space empties
         * it, the literal is taken where it stands (or skipped when something else comes), anything else is
         * dropped, and what does not fit is ignored. [prompt] non-null is `IncludePromptAndLiterals` with that
         * prompt (every position, an empty one as the prompt); null is the default `IncludeLiterals` (empty
         * positions as spaces, up to the last filled position or literal).
         */
        fun maskedText(mask: String, input: String?, prompt: Char?): String {
            val n = mask.length
            val cells = arrayOfNulls<Char>(n)
            var pos = 0
            for (c in input ?: "") {
                while (pos < n && mask[pos] != '0') {
                    if (c == mask[pos]) break
                    pos++
                }
                if (pos >= n) break
                if (mask[pos] != '0') { pos++; continue }         // the literal itself
                when {
                    c in '0'..'9' -> { cells[pos] = c; pos++ }
                    c == ' ' || (prompt != null && c == prompt) -> { cells[pos] = null; pos++ }
                    else -> {}
                }
            }
            if (prompt != null) return (0 until n).map { i -> if (mask[i] != '0') mask[i] else cells[i] ?: prompt }.joinToString("")
            var last = -1
            for (i in 0 until n) if (mask[i] != '0' || cells[i] != null) last = i
            return (0..last).map { i -> if (mask[i] != '0') mask[i] else cells[i] ?: ' ' }.joinToString("")
        }
    }

    // ================================================================ the main form: what the card reads from it

    /** A campaign waypoint as BMSUtils holds it. */
    class Waypoint(
        val gridX: Int, val gridY: Int, val gridZ: Int,
        /** campaign time in ms (uint) */
        val arrive: Long, val depart: Long,
        val action: Int, val formation: Int,
    )

    /**
     * A campaign flight: its waypoints, which of its four aircraft exist, their laser codes. `numWaypoints` is the
     * flight's own count (a ushort in BMSUtils), which WDP reads beside the array's length.
     */
    class Flight(
        val waypoints: List<Waypoint>,
        val planeStats: IntArray = IntArray(4),
        val laserCode: IntArray = IntArray(4),
        val numWaypoints: Int = waypoints.size,
    )

    /** `fclsMain.FltRadio`: a flight's frequencies in the theater's radio map, kHz as text. */
    class FltRad(val callsign: String?, val uhf: String?, val vhf: String?)

    /**
     * `fclsMain.Tanker`: a support track the main form works out from the campaign. [tcnFinal] is a channel the app
     * already has as the pilot dials it (the briefing's `059Y`, BMS's default 92Y), printed as it is; a campaign's
     * channel (false) goes through WDP's Y-band shift.
     */
    class Track(
        val name: String?, val vehType: String?, val tcn: String?, val loc: String?, val time1: Long, val time2: Long,
        val tcnFinal: Boolean = false,
    )

    var flightTable: List<Flight>? = null
    var selFlightNr = -1
    /**
     * The campaign time of the mission's first midnight. A taxi time before it is not one the flight has (D19): the
     * wiring's clock starts a day on, so a take-off at 00:03 is not a negative time; WDP's campaign started at 0.
     */
    var dayStart = 0L
    /**
     * Which of the fixes to WDP changed this card since the set was last cleared ("D19-taxi", "D19-tas", …). Nothing
     * on the page reads it: the comparison with WDP does, to tell a row WDP could not finish from a row that differs.
     */
    val fixesUsed = HashSet<String>()
    var blnLoaded = false
    var blnMissionLoaded = false
    var blnSelectingFlight = false
    var fltRadio: List<FltRad>? = emptyList()
    /** `AirportTable[GetAirportRow(intSelApt)].TL`: the selected airport's transition level (0 = WDP's FL180). */
    var transitionLevel = 0
    var laserLST = 0
    var laserCode = 0
    var selPackageName: String? = ""
    /** `cntPerformance.lblMilP_Val.Text` */
    var milP: String = ""
    var selFlightArrStpt = 0
    var tblTankerTrack: List<Track>? = null
    var tankerNR = 0
    var tblAwacsTrack: List<Track>? = null
    var awacsNR = 0
    var tblJSTARTrack: List<Track>? = null
    var jstarNR = 0
    /** The flight plan's coordinate strings, which WDP gets from its map projection: given per waypoint. */
    var latLon: (Int) -> Pair<String?, String?> = { null to null }

    /** The attack profile, as the DTC page (`cntDTC`) and the Pop-up, TOSS and HADB pages hold it. */
    class Attack {
        var strProfile = "None"
        var modesel = 0
        /** the DTC page's offset spinners as they read: "VIP_BRG", "VIPPUP_RNG", … "VRPOA2_ELV" */
        val nums = HashMap<String, String>()
        var strIngrHgt = ""; var strIngrSpd = ""; var strRelHgt = ""; var strRelSpd = ""
        var strAttHed = ""; var strDA = ""; var strPullingG = ""
        var popRef = false; var popPullHeading = 0.0; var popPullDownAlt = 0; var popClimbAngleDeg = 0
        var popTurnVal = ""; var popTargetHud = ""
        var tossRef = false; var tossAttackHeadingDeg = 0; var tossIngressHeight = 0; var tossIngressCAS = 0
        var tossReleaseHeight = 0; var tossReleaseCAS = 0; var tossReleaseAngleDeg = 0; var tossPullingGs = 0
        var tossTurn = ""; var tossTargetHud = ""
        var hadbIngressAlt = 0; var hadbIngressCAS = 0; var hadbReleaseHeight = 0; var hadbCAS = 0
        var hadbAttackHeadingDeg = 0; var hadbDiveAngleDeg = 0; var hadbPullingGs = 0; var hadbTurnDirection = ""
        var hadbTargetHud = ""
        fun num(k: String): String = nums[k] ?: "0"
    }
    val attack = Attack()

    // ================================================================ the card's own state, named as WDP names it

    class FlightRec {
        var nrAc = 0
        val names = arrayOfNulls<String>(4)
        val idm = arrayOfNulls<String>(4)
        val tcn = arrayOfNulls<String>(4)
        val to = arrayOfNulls<String>(4)
        val lnd = arrayOfNulls<String>(4)
        val lsr = arrayOfNulls<String>(4)
        val mode23 = arrayOfNulls<String>(4)
    }

    class Package {
        var fltNr = 0
        var callsign: String? = null
        var task: String? = null
        var acNr = 0
        var acType: String? = null
        var vhf: String? = null
        var uhf: String? = null
        var idm: String? = null
        var tcn: String? = null
        var f16 = false
        var takeOffTime = 0L
        var pushStpt = 0
        var pushTime = 0L
        var pushAlt = 0
        var targetStpt = 0
        var targetTime = 0L
        var holdStpt = 0
        var holdStptStr: String? = null
        var holdAlt = 0
    }

    class Airport {
        var name: String? = null
        var elv = 0f
        /** the elevation came from the terrain, not the database: WDP prints it in blue */
        var elvByTerrain = false
        var tcn: String? = null
        var uhf = 0f; var vhf = 0f; var atisVHF = 0f; var gndUHF = 0f; var appUHF = 0f; var opsUHF = 0f; var lsoUHF = 0f
        var rwy: String? = null
        var ils = 0f
    }

    class FlightPlanEntry {
        var arrive = 0L
        var action = 0
        var depart = 0L
        var heading = 0f
        var distance = 0f
        var alt = 0L
        var speed: Short = 0
        /** the leg's speed did not fit WDP's short (D19): printed `---` */
        var speedUnknown = false
        var fuel = 0
        var formation = 0
        var latitude: String? = null
        var longtitude: String? = null
    }

    val flightPlan = Array(25) { FlightPlanEntry() }
    var packages: Array<Package> = Array(6) { Package() }
    val tblFlight = Array(6) { FlightRec() }
    val tblApt = Array(4) { Airport() }
    val strNames = arrayOfNulls<String>(5)
    var strMission: String? = ""
    var selFltInPack = 0
    var altnFuel = 1000
    var taxiTime = 360000
    var blnSwingFlpn = false
    var blnSwing = false
    var blnAtis = false
    var blnKmSm = false
    var blnWth = false
    var blnFillFlight = false
    var blnKeepNames = false
    var intSetRange = 0
    var atis1 = ""
    var atis2 = ""
    var firstTanker = -1
    var secondTanker = -1
    val strEws = arrayOfNulls<String>(7)
    /** strSubMode1, strFuse1, strArmDelay1, strBurstAlt1, strRelAngle1, strSglPair1, strRipple1, strSpacing1, then …2 */
    val wpn = arrayOfNulls<String>(16)
    val numRouteStpt = IntArray(10)
    var numTaxi1 = 6
    var numTaxi2 = 6
    /** the departure combo's runways and its selection (only the harness's empty list is checked) */
    var depRunways: List<String> = emptyList()
    var depRwyIndex = -1
    val cboTanker1 = ArrayList<String>()
    val cboTanker2 = ArrayList<String>()
    /**
     * E3: our flight's own Link 16 figures where the jet has them — its package slot (0-based, -1 none), the four
     * seats' STNs, shown where WDP printed its IDM addresses (4.38.1's F-16s with MIDS use STNs; the IDM is the data
     * modem before it), and the seats' A/A TACANs as BMS planned them. A null entry keeps WDP's default for that seat.
     */
    var link16Slot = -1
    var link16Stn: List<String?> = emptyList()
    var link16Tcn: List<String?> = emptyList()
    var priStrikeStpt = -1
    var secStrikeStpt = -1
    var terStrikeStpt = -1
    var quaStrikeStpt = -1
    /** the DTC page's boxes the weapon/laser Leave handlers put back when what was typed is not a number */
    val dtcBoxes = HashMap<String, String>()

    /** radio buttons rbnCallsign1..5 (index 1..5) */
    val rbnCallsign = BooleanArray(6)
    /**
     * Their `Enabled`: `FillPackages` enables all five, `FillDataCard` disables each whose caption is empty. A
     * disabled button takes no click. Shown to the renderer as `"rbnCallsignN.enabled"`.
     */
    val rbnEnabled = BooleanArray(6) { true }

    /** The pilot-seat radio buttons rbnLead, rbnWing, rbnElmLead, rbnElmWing: checked, and enabled by the flight's size. */
    val seatChecked = booleanArrayOf(true, false, false, false)
    val seatEnabled = booleanArrayOf(true, true, true, true)
    var selPilotSeat = 0

    /** `cntDTC.blnMissionDtcLoaded`: a mission cartridge is loaded (the route box's spinners also open then). */
    var blnMissionDtcLoaded = false

    /** The tanker lists' `SelectedIndex` (index 1, 2), -1 for none. */
    val cboTankerSel = intArrayOf(-1, -1, -1)

    /** `fclsMain.Camp_Offset`: one offset aimpoint as the cartridge holds it. */
    class Offset(var stpt: Int = 0, var bearing: Float = 0f, var range: Int = 0, var elv: Int = 0) {
        fun copy() = Offset(stpt, bearing, range, elv)
    }

    /**
     * `fclsMain.Camp_NavOffsets`: the mode and the eight offset aimpoints, in WDP's order — VIP, VIP pull-up, VRP,
     * VRP pull-up, then the VIP's two offset aimpoints (`OA1_1`, `OA2_1`) and the VRP's (`OA1_2`, `OA2_2`).
     */
    class NavOffsets {
        var modesel = 0
        val points = Array(8) { Offset() }
    }

    /** `fclsMain.CampNavOffsets` (the cartridge's own) and what the Pop-up, HADB and TOSS pages last worked out. */
    val campNavOffsets = NavOffsets()
    val popUpNavOffsets = NavOffsets()
    val hadbNavOffsets = NavOffsets()
    val tossNavOffsets = NavOffsets()
    /** `cntDTC.blnLoaded`: the DTC page has been loaded (its spinners are only refilled then). */
    var dtcLoaded = true

    /** Every label and text box the card fills, by control name. Radio captions are `"<name>.text"`. */
    val labels = LinkedHashMap<String, String>()

    fun text(name: String): String = labels[name] ?: ""

    init { load() }

    // ================================================================ the controls and their TextChanged handlers

    /** A control's Text is set: a changed text box fires its TextChanged handler, as Windows Forms does. */
    private fun set(name: String, value: String?) {
        val v = value ?: ""
        val old = labels[name] ?: ""
        labels[name] = v
        if (old != v) textChanged(name, v)
    }

    private val memberBoxes = mapOf(
        "Lead" to 0, "Wing1" to 1, "Element" to 2, "Wing4" to 3,
    )

    private fun flightIdx(): Int = if (selFltInPack in 1..5) selFltInPack - 1 else 0

    private fun textChanged(name: String, v: String) {
        // txtC{k}_UHF / _VHF / txtTCN_{k}
        if (name.length == 9 && name.startsWith("txtC") && name.endsWith("_UHF")) { val k = name[4] - '1'; packages.getOrNull(k)?.uhf = v; return }
        if (name.length == 9 && name.startsWith("txtC") && name.endsWith("_VHF")) { val k = name[4] - '1'; packages.getOrNull(k)?.vhf = v; fillCommCard(); return }
        if (name.startsWith("txtTCN_") && name.length == 8) {
            if (!blnFillFlight) { val k = name[7] - '1'; packages.getOrNull(k)?.tcn = v; tblFlight[k].tcn[0] = v; fillCommCard() }
            return
        }
        // the flight members' boxes: txtLead_IDM … txtWing4_Mode23 (txtElement_lsr is spelt so in WDP)
        val us = name.indexOf('_')
        if (!name.startsWith("txt") || us < 0) return
        val m = memberBoxes[name.substring(3, us)] ?: return
        val f = name.substring(us + 1)
        if (blnFillFlight) return
        val r = tblFlight[flightIdx()]
        when (f) {
            "IDM" -> r.idm[m] = v
            "TCN" -> { r.tcn[m] = v; if (m == 0) packages.getOrNull(flightIdx())?.tcn = v }
            "TO" -> r.to[m] = v
            "Lnd" -> r.lnd[m] = v
            "Lsr", "lsr" -> r.lsr[m] = v
            "Mode23" -> r.mode23[m] = v
        }
    }

    private fun checkCallsign(k: Int) {
        for (i in 1..5) rbnCallsign[i] = i == k
    }

    // ================================================================ picking another flight of the package

    /**
     * What `SelectCallsign` asks the main form for, which the card itself does not hold. WDP reads it out of the
     * campaign; the app's wiring answers from the briefing. Each default does nothing (the card keeps what it has).
     */
    interface Campaign {
        /** `DepAirport`/`ArrAirport`/`AltnAirport` + `SetDepApt`…: the selected flight's three airports into [tblApt]. */
        fun airports(p: DataCardPlan) {}
        /** `FillPackages`: [packages] and [selFltInPack] from the campaign's package of the selected flight. */
        fun packages(p: DataCardPlan) {}
        /** `fclsMain.Tankers()`/`Awacs()`/`JSTAR()`: the support tables and their counts. */
        fun support(p: DataCardPlan) {}
    }

    var campaign: Campaign? = null

    /**
     * What the card hands to the Planner's other pages, which WDP reaches through the main form. None of it changes
     * the card; each default does nothing, and the Planner can join the pages here.
     */
    interface Pages {
        /** `CreateFlightplan`: a strike steerpoint (1-based) for the Pop-up, HADB and TOSS pages (`numWaypoint`, `STPTChange`). */
        fun strikeSteerpoint(stpt: Int) {}
        /** `CreateFlightplan`'s end: those pages' `strDTC = "Both"`. */
        fun flightplanCreated() {}
        /** `FillFlightplan`: row 2's altitude as the performance page's cruise altitude (`txtCruiseAlt`, `ProgramFlow`). */
        fun cruiseAlt(text: String) {}
        /** `FillAutoFlight`: an F-16 flight's type for the performance page (`cboType.Text`, and `TypeChange` for slot 1 only). */
        fun aircraftType(type: String?, typeChange: Boolean) {}
        /** `SelectPilotSeat`'s tail: the seat's loadout and the performance page again (`SetLoadout`, `ProgramFlow`). */
        fun pilotSeat(seat: Int) {}
    }

    var pages: Pages? = null

    /**
     * `rbnCallsignN_Click`: the radio button is checked first (Windows Forms checks it before Click), a flight that
     * has a callsign becomes the selected one (`SelFlightNr = Packages[N-1].FltNr`, `SelFltInPack = N`), then
     * `SelectCallsign` and `FillAutoFlight`. The loadout dialog WDP opens after that is the wiring's (the Performance page's Loadout).
     */
    fun rbnCallsignClick(k: Int) {
        if (k !in 1..5) return
        checkCallsign(k)
        if (text("rbnCallsign$k.text") != "") {
            selFlightNr = packages.getOrNull(k - 1)?.fltNr ?: selFlightNr
            selFltInPack = k
        }
        selectCallsign()
        fillAutoFlight()
    }

    /**
     * `SelectCallsign`, as far as it touches the card: clear the airports and the plan, the new flight's airports,
     * the plan (its failure caught, as WDP catches it), the package, then the card, the Coordination Card and the
     * plan's rows, and the support tracks. WDP's calls into the performance, DTC and map pages in between are
     * those pages' business.
     */
    fun selectCallsign() {
        if (!blnLoaded) return
        clearAirports()
        clearFlightplan()
        clearTargets()
        clearFuel()
        clearFormation()
        campaign?.airports(this)
        fillAptLabels()
        try { createFlightplan(false) } catch (e: Exception) { /* WDP: "Failed to create flightplan." */ }
        enableCallsigns()
        campaign?.packages(this)
        fillDataCard()
        fillCommCard()
        fillFlightplan()
        campaign?.support(this)
        if (tankerNR != 0) fillTanker()
        if (awacsNR != 0) fillAwacs()
        if (jstarNR != 0) fillJSTAR()
    }

    /** What `FillPackages` does to the five radio buttons before it fills the package. */
    fun enableCallsigns() { for (k in 1..5) rbnEnabled[k] = true }

    /**
     * `rbnLead_Click` … `rbnElmWing_Click` → `SelectPilotSeat`: the seat, then the plan again. `Precision` only
     * decides what `CreateFlightplan` writes into the main form's steerpoint table, which the card does not show.
     * WDP then points the performance page at the flight's aircraft and its loadout (`SetLoadout`, `ProgramFlow`) —
     * that page's business, not ported here.
     */
    fun selectPilotSeat(seat: Int, precision: Boolean = false) {
        if (seat !in 0..3) return
        for (i in 0..3) seatChecked[i] = i == seat
        selPilotSeat = seat
        createFlightplan(precision)
        fillFlightplan()
        pages?.pilotSeat(seat)
    }

    /** `txtFormationN_Click`: the formation dialog answered OK with [formation], and its "set all" box. */
    fun formationChosen(k: Int, formation: String, setAll: Boolean) {
        if (k !in 1..24) return
        set("txtFormation$k", formation)
        if (setAll) setAllFormationsSame(formation)
    }

    /** `SetAllFormationsSame`: every formation box that is not empty gets the same formation. */
    fun setAllFormationsSame(formName: String) {
        for (i in 1..24) if (text("txtFormation$i") != "") set("txtFormation$i", formName)
    }

    /**
     * `btnPopUp_Click` … `btnNone_Click`: `cntDTC.strProfile`, then `cntDTC.Profiles()` — the cartridge's offset
     * aimpoints copied from the page that worked the attack out (the Pop-up, HADB or TOSS page's own
     * `*NavOffsets`, with its VIP/VRP mode), or cleared for None; "Else" keeps the cartridge's. Then
     * `FillNAVOFFSETSdata` puts them in the DTC page's spinners and fills the card and the Coordination Card again.
     */
    fun profileButton(profile: String) {
        attack.strProfile = profile
        profiles()
    }

    /** `cntDTC.Profiles()`. */
    fun profiles() {
        val c = campNavOffsets
        when (attack.strProfile) {
            "None" -> {
                for (i in c.points.indices) { val o = c.points[i]; o.stpt = 0; o.bearing = 0f; o.range = 0; o.elv = 0 }
                c.points[4].stpt = 3; c.points[5].stpt = 3; c.points[6].stpt = 4; c.points[7].stpt = 4
            }
            "PopUp" -> copyOffsets(popUpNavOffsets)
            "HADB" -> copyOffsets(hadbNavOffsets)
            "TOSS" -> copyOffsets(tossNavOffsets)
        }
        fillNavOffsetsData()
    }

    private fun copyOffsets(src: NavOffsets) {
        campNavOffsets.modesel = src.modesel
        for (i in 0..7) {
            val d = campNavOffsets.points[i]; val s = src.points[i]
            d.stpt = s.stpt; d.bearing = s.bearing; d.range = s.range; d.elv = s.elv
        }
        attack.modesel = src.modesel
    }

    /**
     * `cntDTC.FillNAVOFFSETSdata`: each offset into its spinner, clamped to the spinner's range (bearing 0–360 for
     * the aimpoints and pull-ups, 0–999,999 for the offset aimpoints; range and elevation 0–999,999), and the
     * spinner's text is what the card reads: `new decimal(float)` (seven significant digits, `VarDecFromR4`) printed
     * `"F1"` for a bearing, the whole number for the others. A NaN bearing is neither below nor above the range, and
     * `new decimal(NaN)` throws (kept). Then `FillDataCard` and `FillCommCard`.
     */
    fun fillNavOffsetsData() {
        if (!dtcLoaded || !blnLoaded) return
        val names = listOf("VIP", "VIPPUP", "VRP", "VRPPUP", "VIPOA1", "VIPOA2", "VRPOA1", "VRPOA2")
        // FillNAVOFFSETSdata's own order: VIP, VIPPUP, OA1_1, OA2_1, VRP, VRPPUP, OA1_2, OA2_2
        for (i in listOf(0, 1, 4, 5, 2, 3, 6, 7)) {
            val o = campNavOffsets.points[i]
            val n = names[i]
            val brgMax = if (i < 4) 360f else 999999f
            attack.nums["${n}_BRG"] = when {
                // D19: WDP's new decimal(NaN) threw here and stopped the card; a bearing that is not a number is blank
                o.bearing.isNaN() -> { fixesUsed += "D19-nan"; "" }
                o.bearing < 0f -> "0.0"
                o.bearing > brgMax -> DataCardNet.decimalF1(brgMax)
                else -> DataCardNet.decimalF1(o.bearing)
            }
            attack.nums["${n}_RNG"] = o.range.coerceIn(0, 999999).toString()
            attack.nums["${n}_ELV"] = o.elv.coerceIn(0, 999999).toString()
        }
        fillDataCard()
        fillCommCard()
    }

    // ---------------------------------------------------------------- the weapon-mode lists

    /**
     * The DTC page's weapon-mode lists as their `SelectedIndex` — `cboP1_SubMode`, `cboP1_Fuze`, `cboP1_SGL_PAIR`,
     * then the same for profile 2 — and the card's own lists over the weapon boxes (`cboSubMode1`, `cboFuze1`,
     * `cboSGL_PAIR1`, …2), in the same order.
     */
    val dtcModeSel = IntArray(6) { -1 }
    val modeSel = IntArray(6) { -1 }
    /** `strMode`: where `btnMode1_Click`/`btnMode2_Click` put the chosen sub-mode (not `strSubMode1/2`, kept). */
    var strMode = ""

    /** `txtWpn_Mode1_Click`, `txtWpn_Fuse1_Click`, `txtWpn_SGLPAIR1_Click` (…2): the card's list opens on the DTC page's choice. */
    fun weaponModeOpen(i: Int) { if (i in 0..5) modeSel[i] = dtcModeSel[i] }

    /** The pilot picks row [idx] of the open list (`SelectedIndex`, -1 for none). */
    fun weaponModeSelect(i: Int, idx: Int) { if (i in 0..5 && idx in -1 until modeItems(i).size) modeSel[i] = idx }

    /**
     * `btnMode1_Click`, `btnFuse1_Click`, `btnSGL_PAIR1_Click` (…2): the choice goes to the DTC page's list — whose
     * `SelectedIndexChanged`, when it did change, sets the card's weapon string and runs `FillDataCard` — and then
     * `" " + item` into the box. The sub-mode lands in `strMode`, which nothing reads back, so the next `FillDataCard`
     * shows the sub-mode without the space (the DTC page's `strSubMode`); fuse and single/pair keep the space.
     */
    fun weaponModeTake(i: Int) {
        if (i !in 0..5) return
        setDtcModeSel(i, modeSel[i])
        val s = " " + (modeItems(i).getOrNull(modeSel[i]) ?: "")
        when (i) {
            0 -> { strMode = s; set("txtWpn_SubMode1", s) }
            1 -> { wpn[1] = s; set("txtWpn_Fuse1", s) }
            2 -> { wpn[5] = s; set("txtWpn_SGLPAIR1", s) }
            3 -> { strMode = s; set("txtWpn_SubMode2", s) }
            4 -> { wpn[9] = s; set("txtWpn_Fuse2", s) }
            else -> { wpn[13] = s; set("txtWpn_SGLPAIR2", s) }
        }
    }

    /**
     * The DTC page's list set (`SelectedIndex = idx`); its `cboPn_*_SelectedIndexChanged` runs only on a change:
     * the sub-mode or fuse name for a known row, single/pair as "SGL" for row 0 and "PAIR" for anything else (-1
     * too, kept), then `FillDataCard`.
     */
    fun setDtcModeSel(i: Int, idx: Int) {
        if (dtcModeSel[i] == idx) return
        dtcModeSel[i] = idx
        val base = if (i < 3) 0 else 8
        when (i % 3) {
            0 -> if (idx in 0..3) wpn[base] = SUBMODES[idx]
            1 -> if (idx in 0..2) wpn[base + 1] = FUZES[idx]
            else -> wpn[base + 5] = if (idx == 0) "SGL" else "PAIR"
        }
        fillDataCard()
    }

    fun modeItems(i: Int): List<String> = when (i % 3) { 0 -> SUBMODES; 1 -> FUZES; else -> SGL_PAIR }

    // ---------------------------------------------------------------- the tanker lists

    /** `btnSetTanker1_Click`/`btnSetTanker2_Click`: the list gets an item if it has none, and shows the tanker chosen. */
    fun btnSetTanker(k: Int) {
        val items = if (k == 1) cboTanker1 else cboTanker2
        if (items.isEmpty()) { items.add("No Tanker"); cboTankerSel[k] = 0 }
        val chosen = if (k == 1) firstTanker else secondTanker
        if (chosen == -1) cboTankerSel[k] = 0
        else if (tankerNR > 0) cboTankerSelectItem(k, tblTankerTrack!![chosen].name)
    }

    /**
     * `ComboBox.SelectedItem = item`: the first item equal to it; an item not in the list leaves the selection as it
     * was, and Nothing clears it.
     */
    fun cboTankerSelectItem(k: Int, item: String?) {
        val items = if (k == 1) cboTanker1 else cboTanker2
        if (item == null) { cboTankerSel[k] = -1; return }
        val i = items.indexOf(item)
        if (i != -1) cboTankerSel[k] = i
    }

    /** The pilot picks row [i] of the open list. */
    fun cboTankerSelectIndex(k: Int, i: Int) {
        val items = if (k == 1) cboTanker1 else cboTanker2
        if (i in -1 until items.size) cboTankerSel[k] = i
    }

    /** `btnTanker1_Click`/`btnTanker2_Click`: the tanker whose name is selected (the first of that name), then `TankerText`. */
    fun btnTanker(k: Int) {
        val items = if (k == 1) cboTanker1 else cboTanker2
        val item = items.getOrNull(cboTankerSel[k])
        if (k == 1) firstTanker = tankerNmbr(item) else secondTanker = tankerNmbr(item)
        tankerText()
    }

    // ================================================================ cntDataCard_Load

    /** What the page's Load does to a fresh card. */
    fun load() {
        labels["lblRwyTaxiTime1"] = numTaxi1.toString()
        labels["lblRwyTaxiTime2"] = numTaxi2.toString()
        labels["lblAtisType"] = "Military"
        labels["lblFormation"] = "Form"
        labels["lblLat"] = "Latitude"
        labels["lblLon"] = "Longitude"
        for (k in 1..5) labels["rbnCallsign$k.text"] = "C$k"
        clearBriefingLabels()
        clearFlightplan()
        clearTargets()
        clearFuel()
        clearFormation()
        clearDTCLabels()
        clearPackage()
    }

    // ================================================================ Clear*

    fun clearDatacard() {
        cboTanker1.clear(); cboTanker2.clear()
        cboTankerSel[1] = -1; cboTankerSel[2] = -1
        clearBriefingLabels()
        clearAirports()
        clearFlight()
        clearPackages()
        clearFlightplan()
        clearTargets()
        clearFuel()
        clearFormation()
        clearDTCLabels()
        clearMissionBriefing()
    }

    fun clearAirports() {
        for (a in tblApt) { a.elv = 0f; a.ils = 0f; a.name = ""; a.rwy = ""; a.tcn = ""; a.uhf = 0f; a.vhf = 0f }
        for (p in listOf("Dep", "Arr", "Altn")) {
            set("lbl${p}Name", ""); set("lbl${p}TCN", "")
            set("lbl${p.uppercase()}_UHF", ""); set("lbl${p.uppercase()}_VHF", "")
            set("lbl${p}Elv", ""); set("lbl${p}RWY", ""); set("lbl${p}ILS", "")
        }
    }

    fun clearFlight() {
        for (num in 0..4) {
            val r = tblFlight[num]
            for (i in 0..3) { r.names[i] = ""; r.to[i] = ""; r.lnd[i] = ""; r.lsr[i] = laserLST.toString(); r.mode23[i] = "" }
        }
        val tcns = arrayOf(arrayOf("12Y", "22Y", "75Y", "85Y"), arrayOf("13Y", "23Y", "76Y", "86Y"), arrayOf("14Y", "24Y", "77Y", "87Y"),
            arrayOf("15Y", "25Y", "78Y", "88Y"), arrayOf("16Y", "26Y", "79Y", "89Y"))
        for (num in 0..4) for (i in 0..3) { tblFlight[num].idm[i] = "${num + 1}-${i + 1}"; tblFlight[num].tcn[i] = tcns[num][i] }
    }

    fun clearPackages() {
        packages = Array(5) { Package() }
    }

    fun clearBriefingLabels() {
        set("lblCallsign1", ""); set("lblPackage1", ""); set("lblPackage2", "")
        for (m in listOf("Lead", "Wing1", "Element", "Wing4")) {
            set("lbl$m", "")
            set("txt${m}_IDM", ""); set("txt${m}_TCN", ""); set("txt${m}_TO", ""); set("txt${m}_Lnd", "")
            set(if (m == "Element") "txtElement_lsr" else "txt${m}_Lsr", "")
        }
        for (k in 1..5) labels["rbnCallsign$k.text"] = ""
        for (k in 1..5) set("lblAC$k", "")
        for (k in 1..5) set("txtIDM_$k", "")
        if (!blnKeepNames) {
            for (k in 1..5) set("txtC${k}_UHF", "")
            for (k in 1..5) set("txtC${k}_VHF", "")
            for (k in 1..5) set("txtTCN_$k", "")
        }
        for (k in 1..5) set("lblP_Task$k", "")
        for (n in listOf("lblAtis1", "lblAtis2", "lblAA", "lblAG", "lblECM", "lblTanks", "lblGrossWgt", "lblDrag", "lblRotation", "lblRefusal",
            "lblMilClimb", "lblTOFuel", "lblIntFuel", "lblMilP", "txtTGT_Pri", "txtDMPI_Pri", "txtLatLong_Pri", "txtTGT_Sec", "txtDMPI_Sec",
            "txtLatLong_Sec", "txtWpn_AttackType")) set(n, "")
        attack.strProfile = "None"
        if (!blnKeepNames) {
            for (t in listOf("txtTanker1", "txtTanker2")) { set(t, "") }
            for (t in listOf("txtTanker1_TCN", "txtTanker2_TCN", "txtTanker1_UHF", "txtTanker2_UHF", "txtTanker1_Loc", "txtTanker2_Loc",
                "txtTanker1_Notes", "txtTanker2_Notes")) set(t, "")
            for (t in listOf("txtAWACS", "txtJSTAR", "txtFAC")) for (x in listOf("", "_TCN", "_UHF", "_Loc", "_Notes")) set(t + x, "")
        }
        if (!blnKeepNames) for (k in 1..5) set("txtExtra$k", "")
    }

    fun clearFlightplan() {
        for (p in listOf("lblAction", "lblHdg", "lblTOS", "lblAlt", "lblDist", "txtKias")) for (i in 1..24) set("$p$i", "")
    }

    fun clearTargets() {
        for (n in listOf("txtTGT_Pri", "txtDMPI_Pri", "txtLatLong_Pri", "txtTGT_Sec", "txtDMPI_Sec", "txtLatLong_Sec")) set(n, "")
    }

    fun clearFuel() { for (i in 1..24) set("txtFuel$i", "") }

    fun clearFormation() { for (i in 1..24) set("txtFormation$i", "") }

    fun clearDTCLabels() {
        for (n in listOf("lblMission", "lblDepName", "lblArrName", "lblAltnName", "lblDepTCN", "lblArrTCN", "lblAltnTCN", "lblDepElv", "lblArrElv",
            "lblAltnElv", "lblDEP_UHF", "lblARR_UHF", "lblALTN_UHF", "lblDEP_VHF", "lblARR_VHF", "lblALTN_VHF", "lblDepRWY", "lblArrRWY",
            "lblAltnRWY", "lblDepILS", "lblArrILS", "lblAltnILS", "lblIngrHgt", "lblIngrSpd", "lblRelHgt", "lblRelSpd", "lblAttHed", "lblDA",
            "lblPullHdg", "lblClimb", "lblTurn", "lblTGTHUD", "lblVIPbrg", "lblVIPrng", "lblVIPelv", "lblOA1brg", "lblOA1rng", "lblOA1elv",
            "lblOA2brg", "lblOA2rng", "lblOA2elv", "lblPUPbrg", "lblPUPrng", "lblPUPelv")) set(n, "")
    }

    fun clearPackage() {
        for (p in packages) {
            p.acNr = 0; p.acType = ""; p.callsign = ""; p.f16 = false; p.fltNr = -1; p.holdAlt = 0; p.holdStpt = 0; p.holdStptStr = ""
            p.idm = ""; p.pushAlt = 0; p.pushStpt = 0; p.pushTime = 0; p.targetStpt = 0; p.targetTime = 0; p.task = ""; p.tcn = ""
            p.uhf = ""; p.vhf = ""
        }
    }

    fun clearMissionBriefing() {
        for (n in listOf("lblBrfMission", "lblBrfPackage", "lblBrfCallsign", "lblBrfAirbase", "lblBrfAircraft", "txtBrfDescription", "txtBrfYourTask",
            "lblBrfTakeOff", "lblBrfTot", "txtBrfYourDescription")) set(n, "")
        for (i in 1..5) set("txtBrfSituation$i", "")
        for (i in 1..9) set("txtBrfIntel$i", "")
        for (i in 1..10) set("txtBrfObjective$i", "")
        for (i in 1..9) set("txtBrfNotes$i", "")
    }

    // ================================================================ the flight plan

    private fun flight(): Flight? = flightTable?.getOrNull(selFlightNr)

    /**
     * `Heading(StartPoint, EndPoint)`: X is east. As the 32-bit JIT runs it — checked against the program's own
     * `Heading`, called directly over the whole short range (13,000 cell pairs, bit for bit): the quotient goes into
     * `Atan` unrounded, the explicit `(float)Math.Atan` is the only narrowing, and `num2 = atan * RTD` stays on the
     * x87 stack. So the result is returned unrounded; a caller that stores it in a float rounds it there.
     */
    fun heading(sx: Int, sy: Int, ex: Int, ey: Int): Double {
        val rtd = RTD.toDouble()
        if (ex > sx) {
            val num = (sx - ex).toFloat().toDouble()
            return if (ey > sy) 90.0 + atan((ey - sy).toFloat().toDouble() / num).toFloat().toDouble() * rtd
            else 90.0 - atan((sy - ey).toFloat().toDouble() / num).toFloat().toDouble() * rtd
        }
        if (ex == sx) return if (ey > sy) 360.0 else 180.0
        val num = (ex - sx).toFloat().toDouble()
        return if (ey > sy) 270.0 - atan((ey - sy).toFloat().toDouble() / num).toFloat().toDouble() * rtd
        else 270.0 + atan((sy - ey).toFloat().toDouble() / num).toFloat().toDouble() * rtd
    }

    /**
     * `Distance(StartPoint, EndPoint)`: feet between two cells' corners, as the 32-bit JIT runs it. The four corners
     * are `PointF` fields (floats in memory). Of the two float differences, the east one (`num`) stays on the x87
     * stack and goes into `Math.Pow` **unrounded**, the north one (`num2`) is stored as a float first. Found from the
     * program's own answers, not by reading: of the eight ways to round the corners and the differences, only this
     * one gives both a 22,136.9 nm leg (which needs the north difference rounded) and an 18,152.7 nm route distance
     * (which needs the east one not), and it matches 13,000 direct calls to the program's `Distance` over the whole
     * short range, bit for bit. It only shows on legs of thousands of cells.
     */
    fun distance(sx: Int, sy: Int, ex: Int, ey: Int): Float {
        val p1x = (sx.toFloat().toDouble() * KM_TO_FT.toDouble()).toFloat()
        val p1y = (sy.toFloat().toDouble() * KM_TO_FT.toDouble()).toFloat()
        val p2x = (ex.toFloat().toDouble() * KM_TO_FT.toDouble()).toFloat()
        val p2y = (ey.toFloat().toDouble() * KM_TO_FT.toDouble()).toFloat()
        val num = p2x.toDouble() - p1x.toDouble()
        val num2 = (p2y.toDouble() - p1y.toDouble()).toFloat().toDouble()
        return sqrt(num.pow(2.0) + num2.pow(2.0)).toFloat()
    }

    /** `Speed(Dist, StartTime, EndTime)`: knots from feet and campaign ms. */
    fun speed(dist: Float, startTime: Long, endTime: Long): Int {
        if (endTime != 0L) {
            var num = endTime - startTime
            if (num < 0L) throw ArithmeticException("uint overflow")
            num = DataCardNet.bankers(num / 1000.0).toLong()
            if (num == 0L) return 999
            val g = (dist.toDouble() / num.toFloat().toDouble() / 6076.21).toFloat()
            return DataCardNet.roundInt(g.toDouble() * 3600.0)
        }
        return 0
    }

    /** `RoundSpeed`: to a multiple of 5 knots, WDP's way. */
    fun roundSpeed(speed: Int): Int {
        val num = speed / 10.0
        var num2 = num % 2.0
        if (num2 >= 1.0) num2 -= 1.0
        val num3 = DataCardNet.roundInt(num - num2)
        if (num2 < 0.5) num2 = 0.0 else if (num2 >= 0.5 && num2 < 1.0) num2 = 0.5
        return DataCardNet.roundInt((num3 + num2) * 10.0)
    }

    /** `cntPerformance.TasToMach` with its `GetIsa`. */
    fun tasToMach(tas: Int, alt: Int): Float {
        val n = (alt / 1000.0).toFloat()
        val isa = (15.0 - n.toDouble() * 2.0).toFloat()
        val dblCS = 38.967854 * sqrt(isa.toDouble() + 273.15)
        return (tas.toDouble() / dblCS).toFloat()
    }

    /** `cntPerformance.IsaTemp`: the ISA temperature in °F, at 1.98 °C per thousand feet. */
    fun isaTemp(alt: Int): Float = ((15.0 - alt.toDouble() / 1000.0 * 1.98).toFloat().toDouble() * 1.8 + 32.0).toFloat()

    /**
     * `cntPerformance.TasToIas(TAS, Alt)`, as WDP's `CreateFlightplan` called it: indicated from true airspeed, with
     * its `checked((int)Math.Round(…))`, which throws on NaN or past an int. It adds the °F temperature to 273.15.
     * The card never used the answer, and above ~93,195 ft the throw left the rest of the plan unbuilt (D19): the
     * port only asks whether it would have thrown, for [fixesUsed].
     */
    fun tasToIas(tas: Int, alt: Int): Int {
        val num = isaTemp(alt)
        val num2 = (38.967854 * sqrt(num.toDouble() + 273.15)).toFloat()
        val num3 = (tas.toFloat().toDouble() / num2.toDouble()).toFloat()
        val num4 = (1.0 - 6.8755856E-06 * alt.toDouble()).pow(5.2558797).toFloat()
        val inner = (5.0 * ((1.0 + num4.toDouble() * ((1.0 + num3.toDouble().pow(2.0) / 5.0).pow(3.5) - 1.0)).pow(0.2857142857142857) - 1.0)).pow(0.5)
        val v = ((num2.toDouble() * inner).toFloat() + 20f).toDouble()
        return DataCardNet.roundInt(v)
    }

    /** `RoundMach`: the Mach number the speed column shows in front of the ground speed. */
    fun roundMach(speed: Int, alt: Int): String {
        var num = tasToMach(speed, alt)
        num = DataCardNet.round(num.toDouble(), 2).toFloat()
        if (num.toDouble() < 0.6) return ".xx / "
        return DataCardNet.fmt(num, ".#0") + " / "
    }

    /**
     * `CreateFlightplan(Precision)`: the flight's waypoints into the plan — heading, distance, the speed the
     * times imply, altitude, formation — then fuel, worked backwards from the alternate's reserve.
     */
    fun createFlightplan(@Suppress("UNUSED_PARAMETER") precision: Boolean = false) {
        // D21: all 25 entries (WDP reset 24 and its fuel ladder then read the 25th as the flight planned before left it)
        for (k in flightPlan.indices) flightPlan[k] = FlightPlanEntry()
        for (num in 0..23) { flightPlan[num].action = -1; flightPlan[num].arrive = 0; flightPlan[num].depart = 0; flightPlan[num].formation = -1 }
        priStrikeStpt = -1; secStrikeStpt = -1; terStrikeStpt = -1; quaStrikeStpt = -1
        try {
            val f = flight() ?: throw IllegalStateException("no flight")
            val wps = f.waypoints
            var flag = false
            for (i in wps.indices) {
                val fp = flightPlan[i]
                val w = wps[i]
                fp.action = w.action
                if (fp.action == 17) {
                    // the strike steerpoints: WDP fills the target boxes and points the Pop-up/HADB/TOSS pages
                    // there from the campaign's objectives, which the app does not have
                    when {
                        priStrikeStpt == -1 -> priStrikeStpt = i
                        secStrikeStpt == -1 -> secStrikeStpt = i
                        terStrikeStpt == -1 -> terStrikeStpt = i
                        else -> quaStrikeStpt = i
                    }
                    // then it sets those pages' steerpoint spinners to i + 1. Their minimum was 3, so a strike at
                    // the first or second steerpoint threw and left the plan unfinished (D19); the pages take 1 and 2
                    if (i + 1 < 3) fixesUsed += "D19-strike"
                    pages?.strikeSteerpoint(i + 1)
                }
                fp.arrive = w.arrive
                fp.depart = w.depart
                if (i != 0) {
                    val s = wps[i - 1]
                    if (w.gridX == 0 && w.gridY == 0) break
                    fp.heading = heading(s.gridX, s.gridY, w.gridX, w.gridY).toFloat()
                    val num3 = distance(s.gridX, s.gridY, w.gridX, w.gridY)
                    fp.distance = DataCardNet.round(num3.toDouble() * FT_TO_NM.toDouble(), 1).toFloat()
                    val num4 = if (!flag) flightPlan[i - 1].arrive else flightPlan[i - 1].depart
                    flag = fp.depart > fp.arrive
                    val arrive = fp.arrive
                    val speed = if (arrive <= num4) 999 else speed(num3, num4, arrive)
                    val rounded = roundSpeed(speed)
                    // D19: past a short WDP's checked cast threw and the rest of the plan was never built; that one
                    // leg's speed is not printed instead
                    if (rounded > Short.MAX_VALUE || rounded < Short.MIN_VALUE) { fp.speedUnknown = true; fixesUsed += "D19-speed" }
                    else fp.speed = rounded.toShort()
                }
                fp.alt = (w.gridZ * 10).toLong()
                // 4.38.1's formation is the low four bits (WDP kept all of them for a campaign before build 13261)
                fp.formation = w.formation and 0xF
                // D19: WDP called cntPerformance.TasToIas here and threw its answer away; above ~93,195 ft that call
                // threw and left the rest of the plan and the whole fuel ladder unbuilt. The card no longer calls it.
                if (runCatching { tasToIas(fp.speed.toInt(), fp.alt.toInt()) }.isFailure) fixesUsed += "D19-tas"
                val ll = latLon(i)
                fp.latitude = ll.first
                fp.longtitude = ll.second
            }
            var num7 = f.numWaypoints - 1
            var num8 = altnFuel
            while (num7 != -1) {
                if (wps[num7].action == 4) num8 = altnFuel
                val fp = flightPlan[num7]
                fp.fuel = num8
                // D19: a ladder past an Int threw and stopped the plan; the rows above it keep no figure (0)
                try { num8 = ladderStep(num8, fp) } catch (e: ArithmeticException) { fixesUsed += "D19-fuel"; break }
                num7--
            }
            pages?.flightplanCreated()
        } catch (e: Exception) {
            // WDP: MessageBox.Show("Create Flightplan", "Error"), and the plan stays as far as it got
        }
    }

    /** One step of the fuel ladder back from a row: 20, 15 or 10 lb a mile by the row's altitude. */
    private fun ladderStep(fuel: Int, fp: FlightPlanEntry): Int = when {
        fp.alt <= 5000 -> DataCardNet.roundInt(fuel.toFloat().toDouble() + 20.0 * fp.distance.toDouble())
        fp.alt <= 25000 -> DataCardNet.roundInt(fuel.toFloat().toDouble() + 15.0 * fp.distance.toDouble())
        else -> DataCardNet.roundInt(fuel.toFloat().toDouble() + 10.0 * fp.distance.toDouble())
    }

    /** A fuel box: rounded up to the hundred, or blank where that overflows (D19: WDP's `RoundUp` threw). */
    private fun fuelText(fuel: Int): String =
        try { roundUp(fuel, 100).toString() } catch (e: ArithmeticException) { fixesUsed += "D19-fuel"; "" }

    /** `FillFlightplan`: the plan into the 24 rows. */
    fun fillFlightplan() {
        val ft = flightTable ?: return
        if (selFlightNr == -1) return
        val f = ft.getOrNull(selFlightNr) ?: return
        // D19: WDP's `(byte)numWaypoints` threw past 255 waypoints, before any row was written; only 24 are shown
        if (f.numWaypoints > 255) fixesUsed += "D19-255"
        val num = f.numWaypoints
        val wps = f.waypoints
        val s0x: Int
        val s0y: Int
        if (num > 0) {
            s0x = wps[0].gridX; s0y = wps[0].gridY
            val fp = flightPlan[0]
            set("lblAction1", actionString(fp.action))
            set("lblTOS1", "  " + getTime(fp.depart))
            set("lblHdg1", "---")
            set("lblAlt1", text("lblDepElv"))
            set("txtKias1", "     ---")
            set("lblDist1", "---")
            set("txtFuel1", fuelText(fp.fuel))
            set("txtFormation1", if (blnSwingFlpn) swingText(s0x, s0y, wps[0]) else formationString(fp.formation))
        } else return
        var landed = flightPlan[0].action == 7
        for (r in 2..24) {
            if (num <= r - 1) continue
            val j = r - 1
            val fp = flightPlan[j]
            // D21: every row looks at the row before it for a landing (WDP's row 24 looked at itself)
            val prev = flightPlan[j - 1]
            // D21: a landing after the first is the alternate's field, on whatever row it falls (WDP printed the
            // alternate's elevation on row 9 only, and the arrival's on every other landing)
            val elevation = if (landed) text("lblAltnElv") else text("lblArrElv")
            if (prev.action == 7) {
                set("lblTOS$r", "---")
                set("txtKias$r", "     ---")
                set("lblAlt$r", if (fp.action == 7) elevation else "---")
                set("txtFormation$r", "     ---")
            } else {
                // D21: a row the campaign gives no time (the alternate) printed 00:00:00 and a speed from nothing
                val untimed = fp.arrive == 0L && fp.depart == 0L
                if (untimed) {
                    set("lblTOS$r", "---")
                } else if (fp.arrive != fp.depart) {
                    val time = getTime(fp.arrive)
                    val dep = getTime(fp.depart)
                    // D21: the departure as mm:ss while the hour is the same (WDP dropped the hour even when it had
                    // changed, and printed an "m" in front on rows 9 and 11)
                    val time2 = if (mid(dep, 1, 2) == mid(time, 1, 2)) mid(dep, 4, time.length) else dep
                    set("lblTOS$r", "$time-$time2")
                } else {
                    set("lblTOS$r", "  " + getTime(fp.depart))
                }
                if (untimed || fp.speedUnknown) set("txtKias$r", "     ---")
                else set("txtKias$r", roundMach(fp.speed.toInt(), fp.alt.toInt()) + fp.speed.toInt().toString())
                set("lblAlt$r", if (fp.action == 7) elevation else fp.alt.toString())
                // row 2's altitude is the performance page's cruise altitude (txtCruiseAlt, then its ProgramFlow)
                if (r == 2) pages?.cruiseAlt(text("lblAlt2"))
                set("txtFormation$r", if (blnSwingFlpn) swingText(s0x, s0y, wps[j]) else formationString(fp.formation))
            }
            val text4 = if (fp.action == 8 && fp.depart > fp.arrive) "Holding"
                else if (fp.action == 8 && fp.depart <= fp.arrive) "Timing"
                else actionString(fp.action)
            set("lblAction$r", text4)
            val num2 = DataCardNet.roundInt(fp.heading.toDouble())
            set("lblHdg$r", if (num2 < 10) "00$num2" else if (num2 < 100 && num2 > 9) "0$num2" else num2.toString())
            val d = fp.distance
            set("lblDist$r", if (d < 10f) "  " + DataCardNet.fmt(d, "#.0") else if (d >= 10f && d < 100f) " " + DataCardNet.fmt(d, "#.0") else DataCardNet.fmt(d, "#.0"))
            set("txtFuel$r", fuelText(fp.fuel))
            if (fp.action == 7) landed = true
        }
    }

    /** The formation column in "Swing" mode: bearing and range from the first steerpoint. */
    private fun swingText(sx: Int, sy: Int, e: Waypoint): String {
        val num2 = DataCardNet.roundInt(heading(sx, sy, e.gridX, e.gridY))
        val text = DataCardNet.fmt(num2, "#000") + "º"
        val num3 = DataCardNet.round(distance(sx, sy, e.gridX, e.gridY).toDouble() / NM_TO_FT.toDouble(), 0).toFloat()
        val text2 = DataCardNet.fmt(num3, "#0")
        return if (num3 == 0f) "     ---" else "$text/$text2"
    }

    /** `SetSwingFltpln` (`lblFormation_Click`): the formation column flips between formations and bearing/range. */
    fun setSwingFltpln() {
        if (blnSwingFlpn) { set("lblFormation", "Form"); blnSwingFlpn = false } else { set("lblFormation", "Swing"); blnSwingFlpn = true }
        fillFlightplan()
    }

    /** `CalculateFuel(Fuel, Stpt)`: a typed fuel figure, worked back to the take-off. */
    fun calculateFuel(fuel0: Int, stpt: Int) {
        var fuel = fuel0
        flightPlan[stpt - 1].fuel = fuel
        if (altnFuel == 0) altnFuel = 1000
        val wps = flight()?.waypoints ?: return
        var num = stpt - 1
        while (num != -1) {
            if (num > 0) {
                val w = wps[num - 1]
                val fp = flightPlan[num]
                if (w.action == 4) fuel = altnFuel
                // D19: a figure past an Int threw and stopped the card; the rows above keep what they had
                else fuel = try { ladderStep(fuel, fp) } catch (e: ArithmeticException) { fixesUsed += "D19-fuel"; break }
                flightPlan[num - 1].fuel = fuel
            }
            num--
        }
        fillFlightplan()
    }

    /** A typed fuel figure as a whole number, or null — and D19 noted — where VB's `CInt` would have overflowed. */
    private fun fuelFigure(t: String): Int? =
        try { DataCardNet.toInteger(t) } catch (e: ArithmeticException) { fixesUsed += "D19-fuel"; null }

    /** `txtFuelN_KeyUp`: a fuel figure typed at steerpoint [k] (2..24). */
    fun txtFuelKeyUp(k: Int) {
        val t = text("txtFuel$k")
        if (!blnMissionLoaded || t == "") return
        val f = flightTable!![selFlightNr]
        val v = fuelFigure(t) ?: return
        if (f.numWaypoints == k) altnFuel = v
        calculateFuel(v, k)
    }

    /** `txtFuelN_Leave`: the typed figure checked, and the ladder worked again. */
    fun txtFuelLeave(k: Int) {
        if (k == 24) {
            if (flightTable.isNullOrEmpty()) { set("txtFuel24", ""); return }
        } else if (!blnMissionLoaded) return
        val t = text("txtFuel$k")
        if (t == "") return
        if (!DataCardNet.isNumeric(t)) { set("txtFuel$k", "0"); return }
        val f = flightTable!![selFlightNr]
        if (f.numWaypoints >= k) {
            if (k == 2 || f.waypoints[k - 2].action != 7) calculateFuel(fuelFigure(t) ?: return, k)
        } else set("txtFuel$k", "")
    }

    // ================================================================ airports

    /** `FillAptLabels`: departure, arrival and alternate rows, and the frequency lines under the ROE box. */
    fun fillAptLabels() {
        val a = tblApt
        set("lblDepName", a[0].name); set("lblArrName", a[1].name); set("lblAltnName", a[2].name)
        set("lblDepTCN", a[0].tcn); set("lblArrTCN", a[1].tcn); set("lblAltnTCN", a[2].tcn)
        set("lblDepElv", DataCardNet.str(a[0].elv)); set("lblArrElv", DataCardNet.str(a[1].elv)); set("lblAltnElv", DataCardNet.str(a[2].elv))
        // ElvByTerrain: blue, else black (the label's ForeColor)
        labels["lblDepElv.fore"] = if (a[0].elvByTerrain) "Blue" else "Black"
        labels["lblArrElv.fore"] = if (a[1].elvByTerrain) "Blue" else "Black"
        labels["lblAltnElv.fore"] = if (a[2].elvByTerrain) "Blue" else "Black"
        fun f3(v: Float) = DataCardNet.fmt(v, "#0.000")
        // D23: the UHF and VHF columns are the tower's, the frequencies a pilot reads beside a field (WDP showed
        // Ground's UHF and Approach's for BMS 4.34 and later); D21: all six are cleared first (WDP left lblARR_VHF)
        set("lblDEP_UHF", if (a[0].uhf != 0f) f3(a[0].uhf) else "")
        set("lblARR_UHF", if (a[1].uhf != 0f) f3(a[1].uhf) else "")
        set("lblALTN_UHF", if (a[2].uhf != 0f) f3(a[2].uhf) else "")
        set("lblDEP_VHF", if (a[0].vhf != 0f) f3(a[0].vhf) else "")
        set("lblARR_VHF", if (a[1].vhf != 0f) f3(a[1].vhf) else "")
        set("lblALTN_VHF", if (a[2].vhf != 0f) f3(a[2].vhf) else "")
        // cboDepRWY.SelectedItem = RWY: an item not in the list leaves the selection as it was
        val idx = depRunways.indexOf(s(a[0].rwy))
        if (idx >= 0) depRwyIndex = idx
        set("lblDepRWY", a[0].rwy); set("lblArrRWY", a[1].rwy); set("lblAltnRWY", a[2].rwy)
        set("txtRwy1", text("lblDepRWY"))
        // the second taxi row is the other end of the runway in use: the runway list holds each runway's two ends
        // side by side (D21: WDP named the third and fourth ends as their own opposite)
        if (depRwyIndex in 0..3) depRunways.getOrNull(depRwyIndex xor 1)?.let { set("txtRwy2", it) }
        set("lblDepILS", if (a[0].ils == 0f) "---" else DataCardNet.fmt(a[0].ils, "#0.00"))
        set("lblArrILS", if (a[1].ils == 0f) "---" else DataCardNet.fmt(a[1].ils, "#0.00"))
        set("lblAltnILS", if (a[2].ils == 0f) "---" else DataCardNet.fmt(a[2].ils, "#0.00"))
        // the three fields' other frequencies (D23: the tower's are in the columns now, so this line is Ground's;
        // a carrier's LSO is its tower)
        set("txtExtra1", "Departure, Destination, Alternate")
        set("txtExtra2", extraLine("ATIS:", a[0].atisVHF, a[1].atisVHF, a[2].atisVHF))
        set("txtExtra3", extraLine("GND:", a[0].gndUHF, a[1].gndUHF, a[2].gndUHF))
        set("txtExtra4", extraLine("APP:", a[0].appUHF, a[1].appUHF, a[2].appUHF))
        set("txtExtra5", extraLine("OPS:", a[0].opsUHF, a[1].opsUHF, a[2].opsUHF))
    }

    /** One of the four frequency lines: the three airports' figures, `---.---` where there is none. */
    private fun extraLine(p: String, a: Float, b: Float, c: Float): String {
        fun f(v: Float) = DataCardNet.fmt(v, "#0.000")
        return if (a == 0f) {
            if (b == 0f) { if (c == 0f) "" else "$p  ---.---     ---.---   " + f(c) }
            else if (c == 0f) "$p  ---.---   " + f(b) + "  ---.---"
            else "$p  ---.---   " + f(b) + "  " + f(c)
        } else if (b == 0f) {
            if (c == 0f) "$p " + f(a) + "   ---.---   ---.---" else "$p " + f(a) + "   ---.---   " + f(c)
        } else if (c == 0f) "$p " + f(a) + "  " + f(b) + "  ---.---"
        else "$p " + f(a) + "  " + f(b) + "  " + f(c)
    }

    // ================================================================ the package and the flight

    /**
     * `GetFltComm`: a callsign in the radio map, spaces and case ignored — case as VB's `LCase` folds it (en-US,
     * NLS): one character at a time, so `İ` is `i` and a final `Σ` is `σ`, never `ς` ([DataCardNet.lcase]).
     * A null callsign on either side is WDP's NullReferenceException.
     */
    fun getFltComm(callsign: String?): Int {
        val fr = fltRadio ?: return -1
        if (fr.isEmpty()) return -1
        for (i in fr.indices) {
            val c = fr[i].callsign!!.replace(" ", "")
            val cs = callsign!!.replace(" ", "")
            if (DataCardNet.lcase(cs) == DataCardNet.lcase(c)) return i
        }
        return -1
    }

    /** `FillAutoPackages`: each flight's frequencies from the radio map, its IDM and TACAN defaults. */
    fun fillAutoPackages() {
        val fr = fltRadio!!
        if (fr.isNotEmpty()) {
            for (k in 0..4) {
                val p = packages[k]
                if (p.acNr != 0) {
                    // a flight missing from the radio map is FltRadio[-1]: IndexOutOfRangeException, as in WDP
                    val c = getFltComm(p.callsign)
                    p.vhf = DataCardNet.fmt(DataCardNet.toDouble(fr[c].vhf) / 1000.0, "#.000")
                    p.uhf = DataCardNet.fmt(DataCardNet.toDouble(fr[c].uhf) / 1000.0, "#.000")
                }
            }
        } else {
            for (k in 0..4) { val p = packages.getOrNull(k) ?: continue; if (p.acNr != 0) { p.vhf = "#${k + 1}"; p.uhf = "6" } }
        }
        for (k in 0..4) {
            val p = packages.getOrNull(k) ?: continue
            if (p.acNr != 0) { p.idm = "XMT ${k + 1}0"; p.tcn = "${k + 12}Y" }
            // E3: our flight's lead A/A TACAN as BMS planned it, where the jet has one
            if (k == link16Slot) link16Tcn.getOrNull(0)?.let { p.tcn = it }
        }
    }

    /** `FillBasicFlight`: each flight's default IDM and TACAN pairs, by its size. */
    fun fillBasicFlight() {
        for (num in 0..4) tblFlight[num].nrAc = packages.getOrNull(num)?.acNr ?: 0
        val lead = arrayOf("12Y", "13Y", "14Y", "15Y", "16Y")
        val wing = arrayOf("22Y", "23Y", "24Y", "25Y", "26Y")
        val elem = arrayOf("75Y", "76Y", "77Y", "78Y", "79Y")
        val w4 = arrayOf("85Y", "86Y", "87Y", "88Y", "89Y")
        for (num in 0..4) {
            val r = tblFlight[num]
            for (i in 0..3) r.idm[i] = "${num + 1}-${i + 1}"
            // D21: each flight's pairs by its own size (WDP gave the fourth flight the second flight's)
            val size = r.nrAc
            if (size <= 2) { r.tcn[0] = lead[num]; r.tcn[1] = elem[num] }
            else { r.tcn[0] = lead[num]; r.tcn[1] = wing[num]; r.tcn[2] = elem[num]; r.tcn[3] = w4[num] }
            // E3: our flight's STNs where WDP printed IDM addresses, and the A/A TACANs BMS planned
            if (num == link16Slot) for (i in 0..3) {
                link16Stn.getOrNull(i)?.let { r.idm[i] = it }
                link16Tcn.getOrNull(i)?.let { r.tcn[i] = it }
            }
        }
    }

    /** `FillAutoFlight`: the selected flight's four aircraft into the flight block. */
    fun fillAutoFlight() {
        fillBasicFlight()
        blnFillFlight = true
        val k = selFltInPack
        if (k in 1..5) {
            val idx = k - 1
            val r = tblFlight[idx]
            checkCallsign(k)
            set("lblCallsign1", text("rbnCallsign$k.text"))
            // an F-16 flight's type goes to the performance page; only the first slot also runs its TypeChange (kept)
            val pk = packages[idx]
            if (pk.f16) pages?.aircraftType(pk.acType, typeChange = k == 1)
            set("lblLead", r.names[0]); set("lblWing1", r.names[1]); set("lblElement", r.names[2]); set("lblWing4", r.names[3])
            set("txtLead_IDM", r.idm[0]); set("txtWing1_IDM", r.idm[1]); set("txtElement_IDM", r.idm[2]); set("txtWing4_IDM", r.idm[3])
            set("txtLead_TCN", r.tcn[0]); set("txtWing1_TCN", r.tcn[1]); set("txtElement_TCN", r.tcn[2]); set("txtWing4_TCN", r.tcn[3])
            set("txtLead_TO", r.to[0]); set("txtWing1_TO", r.to[1]); set("txtElement_TO", r.to[2]); set("txtWing4_TO", r.to[3])
            set("txtLead_Lnd", r.lnd[0]); set("txtWing1_Lnd", r.lnd[1]); set("txtElement_Lnd", r.lnd[2]); set("txtWing4_Lnd", r.lnd[3])
            flight()?.laserCode?.let { lc -> for (i in 0..3) r.lsr[i] = lc.getOrElse(i) { 0 }.toString() }
            set("txtLead_Lsr", r.lsr[0]); set("txtWing1_Lsr", r.lsr[1]); set("txtElement_lsr", r.lsr[2]); set("txtWing4_Lsr", r.lsr[3])
            set("txtLead_Mode23", r.mode23[0]); set("txtWing1_Mode23", r.mode23[1]); set("txtElement_Mode23", r.mode23[2]); set("txtWing4_Mode23", r.mode23[3])
            if (r.nrAc == 1) {
                for (b in listOf("txtWing1_IDM", "txtWing1_TCN", "txtWing1_Lsr", "txtElement_IDM", "txtElement_TCN", "txtElement_lsr",
                    "txtWing4_IDM", "txtWing4_TCN", "txtWing4_Lsr")) set(b, "")
            } else if (r.nrAc == 2) {
                for (b in listOf("txtElement_IDM", "txtElement_TCN", "txtElement_lsr", "txtWing4_IDM", "txtWing4_TCN", "txtWing4_Lsr")) set(b, "")
            } else if (r.nrAc == 3) {
                for (b in listOf("txtWing4_IDM", "txtWing4_TCN", "txtWing4_Lsr")) set(b, "")
            }
        }
        blnFillFlight = false
    }

    /** `FillDataCard`: the mission, the package's five flights, the callsign, then EWS, attack and weapons. */
    fun fillDataCard() {
        if (!blnLoaded || blnSelectingFlight) return
        set("lblMission", strMission)
        set("lblBrfMission", strMission)
        set("lblLead", strNames[1]); set("lblWing1", strNames[2]); set("lblElement", strNames[3]); set("lblWing4", strNames[4])
        set("lblPackage1", selPackageName); set("lblPackage2", selPackageName); set("lblBrfPackage", selPackageName)
        for (k in 1..5) {
            val p = packages.getOrNull(k - 1) ?: continue
            if (p.acNr != 0) {
                labels["rbnCallsign$k.text"] = s(p.callsign)
                set("lblAC$k", p.acNr.toString() + " " + s(p.acType))
                set("txtC${k}_UHF", p.uhf)
                set("txtC${k}_VHF", p.vhf)
                set("txtIDM_$k", p.idm)
                set("txtTCN_$k", p.tcn)
                set("lblP_Task$k", p.task)
            }
        }
        // GetNrOfAc(SelFlightNr): which seats the flight has
        val nrAc = getNrOfAc(selFlightNr)
        for (i in 0..3) seatEnabled[i] = i < nrAc
        for (k in 5 downTo 1) if (rbnCallsign[k]) set("lblCallsign1", text("rbnCallsign$k.text"))
        set("lblBrfCallsign", text("lblCallsign1"))
        for (k in 1..5) if (text("rbnCallsign$k.text") == "") { rbnEnabled[k] = false; set("txtC${k}_VHF", ""); set("txtTCN_$k", "") }
        set("lblMilP", milP)
        fillEws()
        fillAttackType()
        fillWeaponProfile()
    }

    /** The card's own `GetNrOfAc`: 4 with no flight selected, else the last aircraft slot in use (at least 1). */
    fun getNrOfAc(fltNr: Int): Int {
        if (fltNr == -1) return 4
        val ps = flightTable!![fltNr].planeStats
        return if (ps[3] != 0) 4 else if (ps[2] != 0) 3 else if (ps[1] != 0) 2 else 1
    }

    fun fillEws() { for (i in 1..6) set("txtEws$i", strEws[i]) }

    fun fillWeaponProfile() {
        val names1 = listOf("txtWpn_SubMode1", "txtWpn_Fuse1", "txtWpn_ArmDly1", "txtWpn_BA1", "txtWpn_RelAngle1", "txtWpn_SGLPAIR1", "txtWpn_Ripple1", "txtWpn_Space1")
        for (i in 0..7) set(names1[i], wpn[i])
        set("txtLaserLST1", laserLST.toString())
        val names2 = listOf("txtWpn_SubMode2", "txtWpn_Fuse2", "txtWpn_ArmDly2", "txtWpn_BA2", "txtWpn_RelAngle2", "txtWpn_SGLPAIR2", "txtWpn_Ripple2", "txtWpn_Space2")
        for (i in 0..7) set(names2[i], wpn[8 + i])
        set("txtLaserLST2", laserLST.toString())
        set("txtLaserCode1", laserCode.toString())
        set("txtLaserCode2", laserCode.toString())
    }

    // ================================================================ the attack block

    /** `SetRangeType` (`btnToggleRange`): the offset ranges in feet, nautical miles, kilometres. */
    fun setRangeType() {
        intSetRange = when (intSetRange) { 0 -> 1; 1 -> 2; 2 -> 0; else -> 0 }
    }

    /** `FillAttackType`: the cartridge's offset aimpoints and the chosen attack's figures. */
    fun fillAttackType() {
        val a = attack
        when (a.modesel) {
            1 -> set("lblVipVrp", "           VIP")
            2 -> set("lblVipVrp", "           VRP")
            else -> set("lblVipVrp", "      VIP / VRP")
        }
        set("txtWpn_AttackType", a.strProfile)
        fun conv(v: Float): Float = when (intSetRange) {
            1 -> DataCardNet.round(v.toDouble() / L_NM_TO_FT.toDouble(), 1).toFloat()
            2 -> DataCardNet.round(v.toDouble() / L_FEET_PER_KM.toDouble(), 1).toFloat()
            else -> v
        }
        val unit = when (intSetRange) { 1 -> " nm"; 2 -> " km"; else -> "" }
        // D21: each range in its own box and unit ("Else" + VRP in nm put OA2's range in OA1's box, OA2 in feet)
        fun offsets(mode: String) {
            val rng = conv(DataCardNet.toSingle(a.num("${mode}_RNG")))
            val pup = conv(DataCardNet.toSingle(a.num("${mode}PUP_RNG")))
            val oa1 = conv(DataCardNet.toSingle(a.num("${mode}OA1_RNG")))
            val oa2 = conv(DataCardNet.toSingle(a.num("${mode}OA2_RNG")))
            set("lblVIPbrg", a.num("${mode}_BRG")); set("lblVIPrng", DataCardNet.str(rng) + unit); set("lblVIPelv", a.num("${mode}_ELV"))
            set("lblPUPbrg", a.num("${mode}PUP_BRG")); set("lblPUPrng", DataCardNet.str(pup) + unit); set("lblPUPelv", a.num("${mode}PUP_ELV"))
            set("lblOA1brg", a.num("${mode}OA1_BRG")); set("lblOA1rng", DataCardNet.str(oa1) + unit); set("lblOA1elv", a.num("${mode}OA1_ELV"))
            set("lblOA2brg", a.num("${mode}OA2_BRG")); set("lblOA2rng", DataCardNet.str(oa2) + unit); set("lblOA2elv", a.num("${mode}OA2_ELV"))
        }
        fun clearFigures() {
            for (n in listOf("lblPullHdg", "lblIngrHgt", "lblIngrSpd", "lblRelHgt", "lblRelSpd", "lblAttHed", "lblDA", "lblClimb", "lblTurn", "lblTGTHUD")) set(n, "")
        }
        fun clearOffsets() {
            for (n in listOf("lblVIPbrg", "lblVIPrng", "lblVIPelv", "lblPUPbrg", "lblPUPrng", "lblPUPelv", "lblOA1brg", "lblOA1rng", "lblOA1elv",
                "lblOA2brg", "lblOA2rng", "lblOA2elv")) set(n, "")
        }
        fun hud(v: String) = if (v == "YES") "Target visible" else "TGT NOT visible"
        when (a.strProfile) {
            "PopUp" -> {
                offsets(if (a.popRef) "VIP" else "VRP")
                set("lblPullHdg", DataCardNet.fmt(a.popPullHeading, "##0.0"))
                set("lblIngrHgt", a.strIngrHgt)
                set("lblIngrSpd", a.strIngrSpd + " kias")
                set("lblRelHgt", a.strRelHgt)
                set("lblRelSpd", a.strRelSpd + " kias")
                set("lblAttHed", a.strAttHed)
                set("lblDA", a.popPullDownAlt.toString() + "' / DA " + a.strDA)
                set("lblClimb", a.popClimbAngleDeg.toString() + " deg / " + a.strPullingG + " G")
                set("lblTurn", a.popTurnVal + " / " + a.strPullingG + " G")
                set("lblTGTHUD", hud(a.popTargetHud))
            }
            "TOSS" -> {
                offsets(if (a.tossRef) "VIP" else "VRP")
                set("lblPullHdg", DataCardNet.fmt(a.tossAttackHeadingDeg, "##0.0"))
                set("lblIngrHgt", a.tossIngressHeight.toString())
                set("lblIngrSpd", a.tossIngressCAS.toString() + " kias")
                set("lblRelHgt", a.tossReleaseHeight.toString())
                set("lblRelSpd", a.tossReleaseCAS.toString() + " kias")
                set("lblAttHed", a.tossAttackHeadingDeg.toString())
                set("lblDA", "up " + a.tossReleaseAngleDeg + " deg")
                set("lblClimb", a.tossPullingGs.toString() + " G")
                set("lblTurn", a.tossTurn)
                set("lblTGTHUD", hud(a.tossTargetHud))
            }
            "HADB" -> {
                offsets("VRP")
                set("lblPullHdg", "")
                set("lblIngrHgt", a.hadbIngressAlt.toString())
                set("lblIngrSpd", a.hadbIngressCAS.toString() + " kias")
                set("lblRelHgt", a.hadbReleaseHeight.toString())
                set("lblRelSpd", a.hadbCAS.toString() + " kias")
                set("lblAttHed", a.hadbAttackHeadingDeg.toString())
                set("lblDA", a.hadbDiveAngleDeg.toString() + " deg")
                set("lblClimb", a.hadbPullingGs.toString() + " G")
                set("lblTurn", a.hadbTurnDirection)
                set("lblTGTHUD", hud(a.hadbTargetHud))
            }
            "None" -> { clearOffsets(); clearFigures() }
            "Else" -> {
                when (a.modesel) {
                    1 -> offsets("VIP")
                    2 -> offsets("VRP")
                    else -> clearOffsets()
                }
                clearFigures()
            }
        }
    }

    // ================================================================ ATIS

    /** `btnATIS_Click` (Mil/Civ). With no campaign weather the ATIS lines are emptied. */
    fun btnATIS() {
        blnAtis = !blnAtis
        if (!blnWth) { atis1 = ""; atis2 = ""; fillAtisLabel() }
    }

    fun fillAtisLabel() {
        set("lblAtis1", atis1); set("lblAtis2", atis2)
        set("lblAtisType", if (blnAtis) "Civil" else "Military")
    }

    // ================================================================ the Coordination Card

    /** `btnKmSm_Click` (M / SM): the standard QNH in hPa or inches. */
    fun btnKmSm() {
        blnKmSm = !blnKmSm
        fillCommCard()
    }

    private fun getFlightHoldStpt(fltNr: Int): Int {
        val wps = flightTable!![fltNr].waypoints
        for (i in wps.indices) if (wps[i].action == 8 && wps[i].depart > wps[i].arrive) return i and 0xFF
        return 0
    }

    /** `FillCommCardPackages`: each package flight's push, target and hold from its own route. */
    fun fillCommCardPackages() {
        if (blnSelectingFlight) return
        for (k in 0..4) {
            val p = packages.getOrNull(k) ?: continue
            if (p.callsign.isNullOrEmpty()) { p.pushTime = 0; p.pushAlt = 0; p.targetTime = 0; continue }
            try {
                val wps = flightTable!![p.fltNr].waypoints
                p.pushTime = wps[p.pushStpt].arrive
                p.pushAlt = wps[p.pushStpt].gridZ * 10
                p.targetTime = wps[p.targetStpt].arrive
                p.holdStpt = getFlightHoldStpt(p.fltNr)
                if (p.holdStpt < 1) { p.holdStptStr = " No Hold "; p.holdAlt = 0 }
                else { p.holdStptStr = " STPT " + (p.holdStpt + 1); p.holdAlt = wps[p.holdStpt].gridZ * 10 }
            } catch (e: Exception) {
                p.pushTime = 0; p.pushAlt = 0; p.targetTime = 0
            }
        }
    }

    /** `FillCommCard`: the package table, the route, the QNH and the transition level. */
    fun fillCommCard() {
        if (!blnLoaded || blnSelectingFlight) return
        val num = if (transitionLevel != 0) transitionLevel else 180
        val num2 = num * 100
        set("lblCommPackage", text("lblPackage1") + " " + text("lblMission"))
        for (k in 1..5) set("lblCommCallsign$k", (if (rbnCallsign[k]) "* " else "  ") + s(packages.getOrNull(k - 1)?.callsign))
        for (k in 1..5) {
            val p = packages.getOrNull(k - 1) ?: Package()
            if (p.callsign.isNullOrEmpty()) {
                for (n in listOf("lblCommCallsign", "lblCommAc", "lblCommTask", "lblCommTO", "lblCommTaxi", "txtCommHoldPt", "txtCommHoldAlt",
                    "lblCommCalls", "lblCommFreq", "lblCommTcn", "txtCommTransAlt", "lblCommPushTime", "lblCommPushAlt", "lblCommTot")) set("$n$k", "")
                continue
            }
            if (p.takeOffTime == 0L) {
                set("lblCommTO$k", ""); set("lblCommTaxi$k", "")
            } else {
                set("lblCommTO$k", getTime(p.takeOffTime))
                val t = p.takeOffTime - taxiTime
                // D19: WDP's checked((uint)(TakeOffTime - TaxiTime)) threw on a take-off less than the taxi time after
                // the mission's first midnight — training missions that start minutes before take-off (forum #2301,
                // #2343) — and the card stopped half filled. The flight has no taxi time that early: it is blank.
                if (t < dayStart) { set("lblCommTaxi$k", ""); fixesUsed += "D19-taxi" } else set("lblCommTaxi$k", getTime(t))
                set("txtCommHoldPt$k", p.holdStptStr)
                set("txtCommHoldAlt$k", if (p.holdAlt >= num * 100) "FL" + DataCardNet.str(p.holdAlt / 100.0) else p.holdAlt.toString() + "'")
            }
            if (p.pushTime == 0L) {
                set("txtCommTransAlt$k", ""); set("lblCommPushTime$k", ""); set("lblCommPushAlt$k", "")
            } else {
                set("txtCommTransAlt$k", "$num2'")
                set("lblCommPushTime$k", getTime(p.pushTime))
                set("lblCommPushAlt$k", p.pushAlt.toString() + "'")
            }
            set("lblCommTot$k", if (p.targetTime == 0L) "" else getTime(p.targetTime))
            set("lblCommAc$k", p.acNr.toString() + " " + s(p.acType))
            set("lblCommCalls$k", text("lblCommCallsign$k"))
            set("lblCommTask$k", p.task)
            set("lblCommFreq$k", text("txtC${k}_VHF"))
            set("lblCommTcn$k", text("txtTCN_$k"))
        }
        setRouteStpts()
        fillFltPlan()
        set("txtStdQnh", if (blnKmSm) "1013" else "29.92")
        set("txtTransitLvl", "FL$num")
    }

    /** `SetRouteStpts`: which nine steerpoints the route box starts on. */
    fun setRouteStpts() {
        val first = try {
            if (selFlightNr != -1) {
                val n = flightTable!![selFlightNr].numWaypoints
                when (n) {
                    in 1..9 -> 1
                    10 -> 2
                    in 11..24 -> 3
                    else -> return
                }
            } else 3
        } catch (e: Exception) { 3 }
        val n = if (selFlightNr != -1) flight()?.numWaypoints ?: 0 else 0
        for (k in 1..9) {
            numRouteStpt[k] = if (first == 1) { if (k <= n) k else 0 } else first + k - 1
        }
    }

    /** `FillFltPlan`: the route box — action and name, and lat/lon or bearing/range from the first steerpoint. */
    fun fillFltPlan() {
        if (!blnLoaded || !blnMissionLoaded || selFlightNr == -1) return
        val f = flightTable?.getOrNull(selFlightNr) ?: return
        if (f.numWaypoints == 0) return
        val value = f.numWaypoints and 0xFF
        var sx = 0
        var sy = 0
        for (k in 1..9) {
            val v = numRouteStpt[k]
            if (v >= 1) {
                if (k == 1) { sx = f.waypoints[0].gridX; sy = f.waypoints[0].gridY }
                if (v > value) {
                    set("lblCommFunc$k", ""); set("lblCommName$k", "STPT $v"); set("lblCommLat$k", ""); set("lblCommLon$k", "")
                    if (k == 9) return
                } else {
                    val fp = flightPlan[v - 1]
                    set("lblCommFunc$k", actionString(fp.action))
                    set("lblCommName$k", "STPT $v")
                    if (blnSwing) {
                        val e = f.waypoints[v - 1]
                        val num = heading(sx, sy, e.gridX, e.gridY).toFloat()
                        set("lblCommLat$k", DataCardNet.fmt(num, "#0") + " º")
                        val num2 = DataCardNet.round(distance(sx, sy, e.gridX, e.gridY).toDouble() / NM_TO_FT.toDouble(), 1).toFloat()
                        set("lblCommLon$k", DataCardNet.fmt(num2, "#0.0") + " nm")
                    } else {
                        set("lblCommLat$k", fp.latitude)
                        set("lblCommLon$k", fp.longtitude)
                    }
                }
            } else {
                set("lblCommFunc$k", ""); set("lblCommName$k", ""); set("lblCommLat$k", ""); set("lblCommLon$k", "")
            }
        }
    }

    /** `SetSwing` (`lblLat_Click`/`lblLon_Click`): the route box between lat/lon and bearing/range. */
    fun setSwing() {
        if (blnSwing) { blnSwing = false; set("lblLat", "Latitude"); set("lblLon", "Longitude") }
        else { blnSwing = true; set("lblLat", "Brg"); set("lblLon", "Dist") }
        fillFltPlan()
    }

    /** `numRouteStptN_Click`: a route spinner moved. */
    fun routeStpt(k: Int, v: Int) {
        numRouteStpt[k] = v.coerceIn(0, 24)
        fillFltPlan()
    }

    /** A route spinner's value changed without its Click (it has no ValueChanged handler): the box is not redrawn. */
    fun routeValue(k: Int, v: Int) {
        if (k in 1..9) numRouteStpt[k] = v.coerceIn(0, 24)
    }

    /** `FillTaxi` (`numTaxi1_Click`/`numTaxi2_Click`): the taxi time in minutes. */
    fun fillTaxi() {
        if (!blnLoaded) return
        taxiTime = numTaxi1 * CAMPAIGN_MINUTES
        set("lblRwyTaxiTime1", numTaxi1.toString())
        set("lblRwyTaxiTime2", numTaxi2.toString())
        fillCommCard()
    }

    // ================================================================ support

    /** A TACAN into the Y band. VB's `text <> Nothing` is false for `""` too, so an empty channel is left as it is. */
    private fun tacanY(tcn: String?, carry: Int): Pair<String?, Int> {
        var text = tcn
        var num = carry
        if (!text.isNullOrEmpty()) {
            text = mid(text, 1, text.length - 1)
            if (DataCardNet.isNumeric(text)) num = DataCardNet.toInteger(text)
            num = if (num < 64) num + 63 else num - 63
            text = "${num}Y"
        }
        return text to num
    }

    private fun window(t: Track): String {
        val time = getTime(t.time1).let { mid(it, 1, it.length - 3) }
        val time2 = getTime(t.time2).let { mid(it, 1, it.length - 3) }
        return "$time - $time2"
    }

    private fun radioUhf(name: String?): String? {
        val c = getFltComm(name)
        return if (c != -1) DataCardNet.fmt(DataCardNet.toDouble(fltRadio!![c].uhf) / 1000.0, "#.000") else null
    }

    /** `FillTanker`: the tankers on station during the flight, and the first two of the table. */
    fun fillTanker() {
        cboTanker1.clear(); cboTanker2.clear()
        cboTankerSel[1] = -1; cboTankerSel[2] = -1
        cboTanker1.add("No Tanker"); cboTanker2.add("No Tanker")
        val tt = tblTankerTrack!!
        // VB's `Name = Nothing` is true for "" as well: an unnamed first tanker ends it here
        if (tt[0].name.isNullOrEmpty()) return
        val wps = flightTable!![selFlightNr].waypoints
        val depart = wps[0].depart
        val arrive = wps[selFlightArrStpt].arrive
        for (i in 0 until tankerNR) {
            val t = tt[i]
            // ComboBox.Items.Add(Nothing) is an ArgumentNullException
            if (t.time1 <= arrive && t.time2 >= depart) { cboTanker1.add(t.name!!); cboTanker2.add(t.name) }
        }
        if (cboTanker1.size > 1) {
            firstTanker = 0
            val t1 = tt[firstTanker]
            val (text, num2) = if (t1.tcnFinal) t1.tcn to 0 else tacanY(t1.tcn, 0)
            set("txtTanker1", s(t1.name) + " - " + s(t1.vehType))
            set("txtTanker1_Notes", window(t1))
            set("txtTanker1_TCN", text)
            set("txtTanker1_Loc", t1.loc)
            val u = radioUhf(t1.name)
            if (u != null) set("txtTanker1_UHF", u) else set("txtTanker2_UHF", "")   // kept: clears tanker 2's
            if (cboTanker2.size > 2) {
                secondTanker = 1
                val t2 = tt[secondTanker]
                if (t2.name.isNullOrEmpty()) return
                val text2 = if (t2.tcnFinal) t2.tcn else tacanY(t2.tcn, num2).first
                set("txtTanker2", s(t2.name) + " - " + s(t2.vehType))
                set("txtTanker2_Notes", window(t2))
                set("txtTanker2_TCN", text2)
                set("txtTanker2_Loc", t2.loc)
                val u2 = radioUhf(t2.name)
                set("txtTanker2_UHF", u2 ?: "")
            } else secondTanker = -1
        } else firstTanker = -1
    }

    /** `TankerText`: the two chosen tankers into their rows. */
    fun tankerText() {
        val tt = tblTankerTrack
        var num = 0
        if (firstTanker == -1) {
            set("txtTanker1", ""); set("txtTanker1_Notes", ""); set("txtTanker1_TCN", ""); set("txtTanker1_Loc", "")
        } else {
            val t = tt!![firstTanker]
            val r = if (t.tcnFinal) t.tcn to num else tacanY(t.tcn, num)
            num = r.second
            set("txtTanker1", s(t.name) + " - " + s(t.vehType))
            set("txtTanker1_Notes", window(t))
            set("txtTanker1_TCN", r.first)
            set("txtTanker1_Loc", t.loc)
            set("txtTanker1_UHF", radioUhf(t.name) ?: "")
        }
        if (secondTanker == -1) {
            set("txtTanker2", ""); set("txtTanker2_Notes", ""); set("txtTanker2_TCN", ""); set("txtTanker2_Loc", ""); set("txtTanker2_UHF", "")
            return
        }
        val t = tt!![secondTanker]
        var text = t.tcn
        if (!text.isNullOrEmpty()) {
            if (!t.tcnFinal) text = tacanY(text, num).first
            // the second tanker's UHF is only looked up when its TACAN is known (kept)
            set("txtTanker2_UHF", radioUhf(t.name) ?: "")
        }
        set("txtTanker2", s(t.name) + " - " + s(t.vehType))
        set("txtTanker2_Notes", window(t))
        set("txtTanker2_TCN", text)
        set("txtTanker2_Loc", t.loc)
    }

    /** `TankerNmbr`: a tanker's row in the table by name. */
    fun tankerNmbr(name: String?): Int {
        val tt = tblTankerTrack
        for (i in 0 until tankerNR) if (s(name) == s(tt!![i].name)) return i
        return -1
    }

    /** The pilot picks [item] in tanker list [k] (`SelectedItem = item`) and presses its button. */
    fun pickTanker(k: Int, item: String?) {
        cboTankerSelectItem(k, item)
        btnTanker(k)
    }

    /** `FillAwacs`. */
    fun fillAwacs() {
        set("txtAWACS_UHF", "")
        val t = tblAwacsTrack?.getOrNull(0) ?: return
        set("txtAWACS", s(t.name) + " - " + s(t.vehType))
        set("txtAWACS_Notes", window(t))
        set("txtAWACS_Loc", t.loc)
        val c = getFltComm(t.name)
        if (c != -1) set("txtAWACS_UHF", DataCardNet.fmt(DataCardNet.toDouble(fltRadio!![c].uhf) / 1000.0, "#.000"))
    }

    /** `FillJSTAR`. */
    fun fillJSTAR() {
        val t = tblJSTARTrack?.getOrNull(0) ?: return
        set("txtJSTAR", s(t.name) + " - " + s(t.vehType))
        set("txtJSTAR_Notes", window(t))
        set("txtJSTAR_Loc", t.loc)
        val c = getFltComm(t.name)
        if (c != -1) set("txtJSTAR_UHF", DataCardNet.fmt(DataCardNet.toDouble(fltRadio!![c].uhf) / 1000.0, "#.000"))
    }

    // ================================================================ the card's typed fields (Leave handlers)

    /** A text box's Text typed by the pilot (no handler of its own beyond TextChanged). */
    fun type(name: String, value: String) = set(name, value)

    /** A text box left: its Leave handler, as WDP has it. Unknown names do nothing. */
    fun leave(name: String) {
        val t = text(name)
        when {
            name.startsWith("txtFuel") -> name.removePrefix("txtFuel").toIntOrNull()?.let { if (it in 2..24) txtFuelLeave(it) }
            name.startsWith("txtEws") -> name.removePrefix("txtEws").toIntOrNull()?.let { if (it in 1..6) strEws[it] = t }
            name == "txtALOW" -> if (!DataCardNet.isNumeric(t)) set(name, "0")
            name == "txtMSL" -> if (!DataCardNet.isNumeric(t)) set(name, "0")
            name == "txtBingo" -> if (!DataCardNet.isNumeric(t)) set(name, "1500")
            name == "txtLead_TCN" -> {
                val i = flightIdx()
                tblFlight[i].tcn[0] = t; packages.getOrNull(i)?.tcn = t
                set("txtTCN_${i + 1}", packages.getOrNull(i)?.tcn)
            }
            name.startsWith("txtTCN_") -> name.removePrefix("txtTCN_").toIntOrNull()?.let { k ->
                if (k in 1..5) {
                    packages.getOrNull(k - 1)?.tcn = t; tblFlight[k - 1].tcn[0] = t
                    if (selFltInPack == k) set("txtLead_TCN", tblFlight[k - 1].tcn[0])
                    fillCommCard()
                }
            }
            name.startsWith("txtWpn_ArmDly") -> {
                if (DataCardNet.isNumeric(t)) {
                    val num = DataCardNet.toDouble(t)
                    if (num < 0.0) set(name, "00.00") else if (num > 10.0) set(name, "10.00")
                    // cntDTC.mxtP_C1_AD1.Text = the box's text (mask "00.00", prompt '0', prompt and literals kept)
                    dtcBoxes[name] = maskedText("00.00", text(name), '0')
                } else set(name, dtcBoxes[name])
            }
            // the burst altitude and the spacing write the clamped number back, the release angle the box's text
            name.startsWith("txtWpn_BA") -> clampInt(name, t, 0, 5000) { n -> maskedText("0000", n.toString(), '0') }
            name.startsWith("txtWpn_RelAngle") -> clampInt(name, t, 0, 90) { _ -> maskedText("00", text(name), null) }
            name.startsWith("txtWpn_Space") -> clampInt(name, t, 0, 500) { n -> maskedText("000", n.toString(), null) }
            name.startsWith("txtWpn_Ripple") -> {
                if (DataCardNet.isNumeric(t)) {
                    DataCardNet.toInteger(t)
                    dtcBoxes[name] = maskedText("00", t, null)
                } else set(name, dtcBoxes[name])
            }
            name == "txtLaserLST1" || name == "txtLaserCode1" || name == "txtLaserLST2" || name == "txtLaserCode2" -> {
                // D21: each box checks its own figure (WDP's box 2 of the TGP code checked box 1's, and then threw
                // converting its own when that was past a Short)
                var num = if (!DataCardNet.isNumeric(t)) 1688
                    else if (!(DataCardNet.toDouble(t) > 0.0 && DataCardNet.toDouble(t) <= 9999.0)) 1688
                    else DataCardNet.toShort(t).toInt()
                if (num < 1111) { num = 1111; set(name, num.toString()) }
                if (num > 2888) { num = 2888; set(name, num.toString()) }
                if (name == "txtLaserLST1") laserLST = num
                if (name == "txtLaserCode1") laserCode = num
            }
        }
    }

    /** A whole-number box's Leave: clamped into the box, the cartridge's box written back ([back]), or put back from it. */
    private fun clampInt(name: String, t: String, lo: Int, hi: Int, back: (Int) -> String) {
        if (DataCardNet.isNumeric(t)) {
            var num = DataCardNet.toInteger(t)
            if (num < lo) { num = lo; set(name, num.toString()) } else if (num > hi) { num = hi; set(name, num.toString()) }
            dtcBoxes[name] = back(num)
        } else set(name, dtcBoxes[name])
    }

    // ================================================================ the Briefing page

    /** `CutSituation`: where to break a line of the situation text — the last space in the first 80 characters. */
    fun cutSituation(line: String): Int {
        val num = 80
        var text = line
        var num2 = line.length
        if (line.length > num) {
            text = mid(line, 1, num)
            num2 = text.lastIndexOf(" ")
            if (text.isNotEmpty() && num2 == -1) num2 = text.length
        }
        return num2
    }

    /**
     * The Briefing page's text layout (`FillMissionBriefing`), fed with what WDP looks up in the campaign: the
     * situation motto cut into five lines of at most 80 characters, and the threat lists placed in the intel lines
     * in WDP's order. D21: an air-threat list of seven or fewer entries is shown too, on one line (WDP split a
     * longer list over two lines and, in the branch for a short one, printed nothing at all).
     */
    fun fillMissionBriefing(
        airbase: String, aircraft: String, squad: String, description: String, yourTask: String, takeOff: String, tot: String,
        yourDescription: String, motto: String, gndThreats: String, airThreats: String, fighterBomber: String, bombers: String,
        support: String, helo: String,
    ) {
        set("lblBrfAirbase", airbase); set("lblBrfAircraft", aircraft); set("lblBrfSquad", squad)
        set("txtBrfDescription", description); set("txtBrfYourTask", yourTask)
        set("lblBrfTakeOff", takeOff); set("lblBrfTot", tot); set("txtBrfYourDescription", yourDescription)
        var teamMotto = motto.trim()
        val strs = Array(5) { "" }
        for (i in 0..4) {
            val num = cutSituation(teamMotto)
            if (num > 0) {
                strs[i] = mid(teamMotto, 1, num)
                if (i < 4) teamMotto = mid(teamMotto, num + 1, teamMotto.length).trim()
            }
        }
        for (i in 0..4) set("txtBrfSituation${i + 1}", strs[i].trim())
        for (i in 1..9) set("txtBrfIntel$i", "")
        set("txtBrfIntel1", if (gndThreats != "") "Ground threats: $gndThreats" else "")
        val array = airThreats.split(",")
        if (array.size > 7) {
            var text = ""
            var text2 = ""
            val num2 = DataCardNet.roundInt(array.size / 2.0 - 1.0)
            for (i in 0..num2) text = text + array[i] + ", "
            val num3 = DataCardNet.roundInt(array.size / 2.0)
            for (j in num3 until array.size) text2 = text2 + array[j] + ", "
            if (text != "") {
                if (text("txtBrfIntel1") == "") { set("txtBrfIntel1", "Air threats: $text"); set("txtBrfIntel2", "Air threats: $text2") }
                else { set("txtBrfIntel2", "Air threats: $text"); set("txtBrfIntel3", "Air threats: $text2") }
            }
        } else if (airThreats.isNotBlank()) {
            set(if (text("txtBrfIntel1") == "") "txtBrfIntel1" else "txtBrfIntel2", "Air threats: $airThreats")
        }
        fun place(prefix: String, v: String, upTo: Int) {
            if (v == "") return
            for (i in 1 until upTo) if (text("txtBrfIntel$i") == "") { set("txtBrfIntel$i", prefix + v); return }
            set("txtBrfIntel$upTo", prefix + v)
        }
        place("Fighter Bomber: ", fighterBomber, 4)
        place("Bombers: ", bombers, 5)
        place("Support: ", support, 6)
        place("Heli: ", helo, 7)
    }
}
