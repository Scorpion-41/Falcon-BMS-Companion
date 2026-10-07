package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.RunwayEnd
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.BriefSteerpoint
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampIntel
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampWaypoint
import com.bmscompanion.app.data.mission.CommEntry
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.mission.PackageFlight
import com.bmscompanion.app.data.mission.SupportEntry
import com.bmscompanion.app.data.mission.SupportTrack
import com.bmscompanion.app.data.wdp.DataCardNet
import com.bmscompanion.app.data.wdp.DataCardPlan
import com.bmscompanion.app.data.wdp.DtcModel
import com.bmscompanion.app.data.wdp.HadbPlan
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.data.wdp.PopupPlan
import com.bmscompanion.app.data.wdp.TossPlan
import com.bmscompanion.app.data.wdp.WdpValues
import com.bmscompanion.app.ui.screens.mission.bra
import com.bmscompanion.app.ui.screens.mission.bullseye
import com.bmscompanion.app.ui.screens.mission.stationFromNotes
import com.bmscompanion.app.ui.screens.mission.supportAssets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The DataCard control's behaviour, joined to its layout — one wiring for the three pages the Planner shows from
 * `cntDataCard`: the Briefing (`pnlBrief`), the DataCard (`pnlPage_1`) and the Coordination Card (`pnlPage_2`).
 * The page's `hidden` list picks the view; everything else is the same card.
 *
 * [DataCardPlan] is WDP's card as a state machine and knows nothing about the app; this is the seam. It builds the
 * plan's campaign inputs from the app's own briefing and cartridge ([onMission]; the mapping table is on
 * [DataCardPlan]), runs the same sequence WDP runs when a flight is selected, and turns the page's clicks and typing
 * into the card's own handlers: Form/Swing, the fuel boxes, Mil/Civ, M/SM, the range unit, the route box and its
 * Lat/Brg toggle, the taxi time, the tanker lists, the attack-profile buttons, the flight's radio buttons (WDP's
 * `SelectCallsign`, whose campaign lookups this class answers from the briefing through [DataCardPlan.Campaign])
 * and the weapon/laser/EWS/ALOW/bingo boxes with their Leave checks.
 *
 * The briefing is not all the card prints. The departure, arrival and alternate are the fields every Mission page
 * finds for the briefing ([DataCardSources.bases]), and their TACAN (or the nearest one within 6 nm), elevation,
 * runways and ILS come from the app's own airport database; the tankers', AWACS's and JSTARS's TACAN, UHF and
 * location from the app's support data — the same the Briefing page shows ([supportAssets]); the ATIS and the
 * Briefing page's weather list from the briefing's weather; the flight's Link 16 STNs from the briefing or the
 * cartridge; and the EWS, weapon, laser, ALOW and bingo boxes from the pilot's cartridge read the way WDP reads it —
 * all gathered by [prepare] into [sources]. What none of them carries — the other flights' push altitudes, holds
 * and frequencies — is shown **blank** rather than as the zero WDP would print from an empty campaign field, until
 * the pilot types into the box. That is done here, on the way out ([blankedBoxes]), so the plan stays WDP's.
 *
 * The buttons down the side and the card's child windows (`DataCardWindows.kt`) do what WDP's do. Its file buttons
 * open WDP's own windows on the BMS PC ([com.bmscompanion.app.data.PcFiles]: Windows' dialog on the PC's own window,
 * the PC's folders anywhere else) and read and write WDP's own files there ([WdpFiles]): Load and Backup DataCard a
 * `.bdc` ([CardFile]), Save and Load Codewords and Package Timing an `.ini`, **Reload WX** an `.fmap` or `.twx`
 * whose weather then makes the ATIS, the weather list and Force QNH ([reloadWx]), a plan box a picture file, Cards
 * Directory the DataCards folder. Copies an earlier version kept in the app's settings ([CardCopies]) stay reachable
 * from Cards Directory and from a Load that picks no file. Those files are in the Planner's own folder in the BMS
 * install (`User\BMS Companion Planner\DataCards`, or the DataCards folder the pilot chose). The chart buttons show the
 * airport's charts. The card's DTC buttons are WDP's, in WDP's place: **Get DTC File** opens the pilot's cartridge in
 * the game's `User\Config` through the DTC page ([getDtcFile]), **Save DTC** hands the card's entries (ALOW, MSL floor,
 * bingo, EWS names, laser codes, bomb profiles, the attack profile chosen here) to the DTC page and saves the cartridge
 * ([saveDtc], [WdpCartridge]), and the **"Callsign.ini saved"** lamp is green while nothing waits for the cartridge
 * and red while something does — the card's entries ([unsavedEntries]) or the DTC page's edits. The Planner's toolbar
 * does the same for every page: Save to DTC ([entriesToHand]; its count is the lamp's), and Re-read DTC from BMS in
 * its menu ([rereadDtc]); the identity strip is Different Flight ([differentFlight]). WDP's buttons for what the app
 * does not do on this page — Print, Print Preview, the save timer, the Mission.ini lamp of a Tactical Engagement's
 * second cartridge — are not on the page, and neither is WDP's **Upd Kneeboard**: it is the toolbar's, one place for it.
 *
 * Besides each control's text, [values] carries `"<name>.enabled"` for the nine radio buttons (WDP greys out a
 * callsign with no flight and the seats the flight does not have; a click on a disabled one is ignored here) and
 * `"<name>.color"` for the three elevation labels (blue when the elevation came from the terrain).
 * Every handler the plan runs can throw where WDP throws; [onMission], [onValue] and [onClick] catch it, as WDP's
 * unhandled-exception box lets the program carry on, and the card stays as far as the handler got.
 */
class DataCardWiring : WdpWiring, WdpControlContent {
    val plan = DataCardPlan().also {
        // the Mil/Civ and M/SM choices as WDP keeps them (Setup.ini [Main] ATIS and KmSm, read at start: a key that is
        // missing is True, so a first start is Civil and metric, 1013 hPa)
        it.blnAtis = setting(ATIS_KEY) ?: true
        it.blnKmSm = setting(KMSM_KEY) ?: true
        // and the ATIS box says which, as WDP's start does (`FillAtisLabel` after `ReadSetup`)
        it.fillAtisLabel()
    }

    private fun setting(key: String): Boolean? = runCatching { com.bmscompanion.app.data.Repo.getString(key) }.getOrNull()?.toBooleanStrictOrNull()
    private var version by mutableIntStateOf(0)

    init {
        // the Planner's Settings window: its DataCards folder Browse is WDP's FolderBrowserDialog on the BMS PC
        PlannerSettings.chooseDataCards = { WdpFiles.chooseDataCards() }
        // a DTC tab's Save DTC writes the card's entries too, as the toolbar's Save to DTC does
        WdpCartridge.cardEntries = { entriesToHand() }
    }

    /**
     * The Pop-up, HADB and TOSS pages' plans. With one of those profiles chosen, WDP's card prints that page's offsets
     * and figures (`cntDTC.Profiles`, `FillAttackType`) and FillMap places its map in picMap. The Planner sets it,
     * since the pages are separate wirings; without it the card keeps the cartridge's own offsets.
     */
    var attackPages: (() -> Triple<PopupPlan, HadbPlan, TossPlan>)? = null
    /**
     * WDP's "white map" (chbWhiteMap), which a click on picMap turns on and off; kept between sessions as WDP keeps it
     * (Setup.ini [Map] WhiteMap, off when the key is missing)
     */
    private var whiteMap = setting(WHITE_MAP_KEY) ?: false
    /** the Coordination Card's four plan pictures (picReformPlan …), each an attack page's map as it was when chosen */
    private val planPictures = HashMap<String, PopupPlan.MapPicture>()
    /** the plan boxes showing a picture file the pilot opened on the BMS PC (WDP's `LoadReformPlan` …), by box */
    private val planImages = HashMap<String, androidx.compose.ui.graphics.ImageBitmap>()
    /**
     * The weather file Reload WX read last ([reloadWx]): the card's weather comes from it — the ATIS, the weather list,
     * Force QNH, the Performance page's take-off weather — until another mission (a new briefing). It is the pilot's
     * pick, so it stands over the save's own file ([saveWx]) and the printed briefing alike.
     */
    private var fileWx: com.bmscompanion.app.data.mission.PcWeather? = null
    /** "Fair.fmap", or "Auto Save.twx (map 10100.fmap)": what the card says its weather comes from */
    private var fileWxName: String? = null
    /**
     * The weather file the card's weather comes from — Reload WX's pick ([fileWxName]) or the save's own ([saveWxName]),
     * as [weather] last chose — for the Planner's Settings window (WDP's Weather File lines); null = the briefing's own
     * weather
     */
    internal val weatherSource: String? get() = wxFileInUse
    /** set by [weather]: the name of the file whose weather is on the card, null for the printed briefing's */
    private var wxFileInUse: String? = null
    /**
     * The take-off weather the ATIS was made from, where it is not simply the briefing's take-off column: a weather
     * file's departure cell or table, or the printed briefing's column with the save's QNH ([weather]). What the
     * Performance page takes ([WdpHandOff.cardToPerformance]); null: the briefing's own column.
     */
    internal var fileTakeOff: CardWeather.Column? = null
        private set
    /** Told when Reload WX has put a file's weather on the card, so the Performance page takes it ([WdpHandOff.join]). */
    var weatherChanged: (() -> Unit)? = null
    /**
     * Opens the Performance page's Loadout window for the card's flight ([WdpHandOff.join]): what a press on our own
     * flight's callsign in the package rows does (WDP's `rbnCallsignN_Click` → `fclsLoadout`). Null on a card with no
     * Performance page beside it (a headless check's): the briefing's stores are shown read-only then ([LoadoutWindow]).
     */
    var openLoadout: (() -> Unit)? = null
    /**
     * The save's own weather file (`<save>.twx` in its theater's campaign folder, and the map it flies with), as last
     * read for the flight on the card ([saveWeather]). Read again at every Open mission and Pick a flight — the same
     * flight picked again too — so weather BMS saved since (a campaign save, a TE's SAVE WTH) reaches the card: a file
     * with another time or clock is a change. Null while it is read, and when it cannot be used ([saveWxWhy]).
     */
    private var saveWx: com.bmscompanion.app.data.mission.PcWeather? = null
    /** "Auto Save.twx", or "Auto Save.twx (map 10100.fmap)" */
    private var saveWxName: String? = null
    /** Why the save's own weather is not on the card ("Auto Save.twx is not in …", another save's file, unreadable) */
    private var saveWxWhy: String? = null
    /** set while the save's weather file is being read for a flight the card has no weather of its own for yet */
    private var saveWxPending = false
    /**
     * Whether the save's weather file is still being read for the flight on the card ([saveWeather]). For the headless
     * checks: they press Reload WX on a thread of their own, while the read lands on the window's.
     */
    internal val readingSaveWeather: Boolean get() = saveWxPending
    /** which Open mission / Pick a flight ([PlannerMissionState.changes]) the save's weather was last read for */
    private var saveWxPick = -1
    /** the Briefing page's weather list opens with these: where the card's weather comes from, and as of when */
    private var wxSource: List<String> = emptyList()
    /**
     * What the Planner's header says about the card's weather when it is not what a pilot would take for granted: the
     * save's weather file could not be used, or it was saved after the printed briefing with other weather and is
     * planned instead. (label, the sentence behind it); null otherwise.
     */
    var weatherNotice: Pair<String, String>? by androidx.compose.runtime.mutableStateOf(null)
        private set
    /** Whether the Briefing page's weather list is weather, to be printed on a kneeboard (not a sentence saying why there is none). */
    internal var weatherOnPaper = false
        private set
    /** the departure runway was picked on the card's own list: a weather file's wind does not pick another */
    private var depRwyChosen = false
    /** the departure runway the pilot picked (the field's id, the runway), kept through the refills of the same briefing */
    private var depRwyPick: Pair<Int, String>? = null
    /** Mission / Theater as a WDP file gave it, written back into the next Backup DataCard (the app has no number of its own for it) */
    private var bdcTheater: String? = null
    /**
     * The plan boxes showing one of WDP's own plan templates (its `Datacards\PlanPic` pictures, under
     * `data/wdp/pictures/`), by box; "" for a box the pilot cleared. A box not named here shows [defaultTemplate].
     */
    private val planTemplates = HashMap<String, String>()

    // what the page's own Visible flags say (WDP hides these on Load and shows them on a click)
    private var routeSpinner = 0          // the numRouteStpt shown, 0 = none
    private var taxiSpinner = 0           // the numTaxi shown, 0 = none
    private var profileButtons = false    // btnPopUp/HADB/TOSS/None
    private var tankerList = 0            // cboTanker1/2 with btnTanker1/2, 0 = none
    private var tankerDrop = 0            // counts the Set presses that show a list: each drops it open ("<combo>.drop")
    private val modeVisible = BooleanArray(6) // cboSubMode1, cboFuze1, cboSGL_PAIR1, …2 with their Select buttons
    private val checks = linkedMapOf(
        "chbAction" to true, "chbTos" to true, "chbHdg" to true, "chbDist" to true, "chbKias" to true,
        "chbAlt" to true, "chbFuel" to true, "chbFormation" to true,
    )

    // what the app does not know, blanked on the way out
    /** Set when the airports' TACAN, elevation, runway and ILS are filled from the app's theater data. */
    var airportsKnown = false
    /** Set when the other package flights' routes (holds, push altitudes) are known. */
    var otherRoutesKnown = false
    /** Set when the cartridge's laser codes are filled. */
    var lasersKnown = false
    /** Set when a save's flight gives each jet's laser code (the CONF-LSR column). */
    private var jetLasersKnown = false
    /** flight-plan rows (1-based) whose leg the briefing does not give and the cartridge does not place */
    private val unknownLegs = HashSet<Int>()
    /** our flight's place in the package (1..5): the only one whose route the app knows */
    private var ownPackage = 1
    /** package slots (1..5) whose route the save gives ([CampFlight.packageRoutes]): their holds and push altitudes are known */
    private val routedSlots = HashSet<Int>()
    /**
     * With the printed briefing, the save's flight BMS printed it for ([BriefedSave], by the briefing's key): what
     * [saveFlight] gives the card — the save's name, the package's routes and the bullseye — as for an opened save.
     */
    private var briefedFlight: Pair<String, CampFlight>? = null
    /** The seat whose designated targets the target boxes name (`SelPilotSeat`): the flight picker's, then the card's. */
    private var cardSeat = 0
    /** package slots (1..5) whose flight the briefing's comm ladder does not name: their frequencies are unknown */
    private val unknownRadio = HashSet<Int>()

    /** what the app's own data give the card: airports and charts, the cartridge, the card's page map ([prepare]) */
    var sources = DataCardSources()
        private set
    /** the mission as last given, for the windows that need the briefing */
    private var mission = WdpMission()
    /** boxes the pilot has typed into: shown as typed, even where the app has nothing of its own for them */
    private val typed = HashSet<String>()
    /** the page on screen, from the panels the Planner hides: pnlPage_1, pnlPage_2 or pnlBrief */
    private var page = "pnlPage_1"
    /** the three airports as the app's database has them, and each one's runway ends */
    private val apt = arrayOfNulls<Airport>(3)
    private val ends = Array(3) { emptyList<RunwayEnd>() }
    /** which runway list is open (0 departure, 1 arrival, 2 alternate; -1 none) */
    private var runwayList = -1
    /** the take-off spec's pitch box, power list and Select button (lblTOSpec's click shows them) */
    private var toSpec = false
    private var pitch = 13
    private var power = "Full AB"
    /** the Briefing page's weather list, from the briefing (Reload WX) */
    private var wxLines = ""
    /** how [prepare] was told to read the cartridge, for Get DTC File */
    private var cartridgeRead: (suspend () -> String?)? = null
    /** the attack profile the pilot chose with the card's own buttons, which Save DTC hands to the cartridge */
    private var profileChosen: String? = null
    /** The attack page the card's PopUp / HADB / TOSS buttons chose ("PopUp", "HADB", "TOSS", "None"), or null: Populate from Planner sends that page's attack when the card is on show ([WdpAttackOverlay.toSend]). */
    internal val attackProfile: String? get() = profileChosen

    /**
     * A new mission or a switch of mode ([WdpSession.resetAttack]): no attack profile chosen, the Delivery block back to
     * WDP's "None" (empty), and the attack maps and templates put in the plan boxes gone.
     */
    internal fun resetAttack() {
        profileChosen = null; planPictures.clear(); planTemplates.clear(); planImages.clear()
        plan.attack.strProfile = "None"
        runCatching { plan.fillAttackType() }
        version++
    }
    // on the main thread, as the DTC page's: Get DTC File and Save DTC change both pages, which the screen draws from
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun values(hidden: List<String>): WdpValues {
        @Suppress("UNUSED_VARIABLE") val v = version   // read so a change re-composes the page
        val p = plan
        if (hidden.isNotEmpty()) DataCardSources.PAGES.firstOrNull { it !in hidden }?.let { page = it }
        val m = LinkedHashMap<String, String>()
        for (h in hidden) m[h] = "hidden"
        m.putAll(p.labels)
        // the radio buttons and check boxes: their state, and whether WDP has them enabled
        for (k in 1..5) {
            m["rbnCallsign$k"] = if (p.rbnCallsign[k]) "checked" else "unchecked"
            // D86: the card stays on our own flight. Another flight of the package is shown for reference, greyed and
            // not selectable (WDP let the card switch to it under our flight's briefing); our own opens its loadout
            val hasFlight = p.text("rbnCallsign$k.text").isNotEmpty()
            val other = hasFlight && k != ownPackage
            m["rbnCallsign$k.enabled"] = (p.rbnEnabled[k] && !other).toString()
            if (other) m["rbnCallsign$k.tip"] = OTHER_FLIGHT_TIP
            else if (hasFlight) m["rbnCallsign$k.tip"] = OWN_FLIGHT_TIP
        }
        SEATS.forEachIndexed { i, n ->
            m[n] = if (p.seatChecked[i]) "checked" else "unchecked"
            m["$n.enabled"] = p.seatEnabled[i].toString()
        }
        for ((n, on) in checks) m[n] = if (on) "checked" else "unchecked"
        // an elevation taken from the terrain is printed in blue (FillAptLabels)
        for (n in listOf("lblDepElv", "lblArrElv", "lblAltnElv")) m["$n.color"] = if (p.labels["$n.fore"] == "Blue") "#0000FF" else "#000000"
        // the spinners WDP shows one at a time
        for (k in 1..9) m["numRouteStpt$k"] = if (routeSpinner == k) p.numRouteStpt[k].toString() else "hidden"
        m["numTaxi1"] = if (taxiSpinner == 1) p.numTaxi1.toString() else "hidden"
        m["numTaxi2"] = if (taxiSpinner == 2) p.numTaxi2.toString() else "hidden"
        // what Load hides and a click shows
        for (n in listOf("btnPopUp", "btnHADB", "btnTOSS", "btnNone")) m[n] = if (profileButtons) "shown" else "hidden"
        // the map, which the designer hides and fclsMain shows with the DataCard (FillMap draws it: controlContent)
        m["picMap"] = if (page == "pnlPage_1") "shown" else "hidden"
        // WDP's white map (chbWhiteMap), which a tap on the map turns on and off: the picture is drawn on paper
        m["picMap.paper"] = whiteMap.toString()
        for (k in 1..2) {
            val on = tankerList == k
            val items = if (k == 1) p.cboTanker1 else p.cboTanker2
            // the designer hides both lists (WDP's btnSetTanker shows them), and a control the designer hides is drawn
            // only for "shown": the choice is the list's text, not its value, or the list never came on screen
            m["cboTanker$k"] = if (on) "shown" else "hidden"
            m["cboTanker$k.text"] = items.getOrElse(p.cboTankerSel[k]) { "" }
            m["cboTanker$k.drop"] = if (on) tankerDrop.toString() else ""
            m["btnTanker$k"] = if (on) "shown" else "hidden"
            m["cboTanker$k.items"] = items.joinToString("\n")
        }
        // the weapon-mode lists over the weapon boxes, one open at a time (the Select button beside it)
        for (i in 0..5) {
            m[MODE_LISTS[i]] = if (modeVisible[i]) p.modeItems(i).getOrElse(p.modeSel[i]) { "" } else "hidden"
            m[MODE_BUTTONS[i]] = if (modeVisible[i]) "shown" else "hidden"
        }
        // the runway lists, one open at a time (the runway label's click), with the airport's runway ends
        RUNWAY_LISTS.forEachIndexed { i, n ->
            m[n] = if (runwayList == i) p.tblApt[i].rwy ?: "" else "hidden"
            m["$n.items"] = ends[i].joinToString("\n") { it.designator }
        }
        // the take-off spec: the pitch and power boxes and their Select button, shown by a click on the spec
        m["numPitch"] = if (toSpec) pitch.toString() else "hidden"
        m["cboAB"] = if (toSpec) power else "hidden"
        m["btnToSpecSel"] = if (toSpec) "shown" else "hidden"
        // a chart button where the app has charts for that airport (WDP's CheckChartButtons)
        // (the app draws the airport diagram wherever BMS's data has the field, and has the instrument charts besides)
        CHART_BUTTONS.forEachIndexed { i, n -> m[n] = if (sources.hasCharts(apt[i])) "shown" else "hidden" }
        // WDP shows the copy buttons of the Coordination Card only on that page
        if (page != "pnlPage_2") for (n in listOf("btnSaveCodewords", "btnLoadCodewords", "btnSavePackageTiming", "btnLoadPackageTiming")) m[n] = "hidden"
        m["pnlWx.lines"] = wxLines
        // WDP's buttons for what the app does not do: printing, the save timer, a
        // Tactical Engagement's second cartridge (the DTC page does not open one)
        for (n in REMOVED) m[n] = "hidden"
        // the Coordination Card's plan boxes: an attack map the pilot put there is drawn over it (controlContent);
        // otherwise WDP's own template, as its card opens with HoldingPlan.jpg and TargetPlan.jpg
        for (n in PLAN_PICTURES) m[n] = if (planPictures[n] != null || planImages[n] != null) "" else planTemplates[n] ?: defaultTemplate(n)
        // WDP's "Callsign.ini saved" lamp (CheckCallsignSaved): off with no cartridge loaded, green while nothing waits
        // for the cartridge, red while the card's entries or the DTC page's edits do (the toolbar's Save to DTC count)
        val dtcPending = try { WdpCartridge.pending?.invoke() } catch (e: Exception) { null }
        // WDP's CheckCallsignSaved: off until the cartridge is loaded where Save DTC writes it (the DTC page)
        val lampOn = dtcPending != null
        val waiting = unsavedEntries + (dtcPending ?: 0)
        m["pnlCallsignGreen"] = if (lampOn && waiting == 0) "shown" else "hidden"
        m["pnlCallsignRed"] = if (lampOn && waiting > 0) "shown" else "hidden"
        // WDP's "Current Time" is the clock of the campaign file it has loaded ([currentTime])
        m["lblTime"] = currentTime()
        // FillFuel: a flight whose take-off is already behind that clock, and which is under way, has no take-off fuel
        mission.flight?.let { f ->
            val clock = f.clock
            val dep = f.route.firstOrNull()?.departMs
            if (clock != null && dep != null && clock > dep && (f.currentWp ?: 0) > 0) m["lblTOFuel"] = "----"
        }
        // what the app does not know
        for (n in blankedBoxes()) m[n] = ""
        // a support row with no planned window says where the briefing puts it, as the Briefing page's notes do
        for ((box, t) in supportRows()) if (!windowed(t)) m[box + "_Notes"] = t?.name?.let { supportPhrases[it] } ?: ""
        // what the pilot typed is theirs, whatever the app knows
        for (n in typed) m[n] = p.text(n)
        return WdpValues(m)
    }

    /**
     * The boxes shown blank because the app has nothing of its own for them: a leg neither the briefing nor the
     * cartridge gives, the airports' figures when the theater's airports are unknown, the package flights the comm
     * ladder does not name, the other flights' holds and push altitudes, laser codes nobody set, and a support
     * flight's on-station window unless a planned track gives it. Load DataCard leaves these alone too.
     */
    private fun blankedBoxes(): Set<String> {
        val p = plan
        val out = HashSet<String>()
        for (r in unknownLegs) { out += "lblHdg$r"; out += "lblDist$r"; out += "txtKias$r" }
        if (!airportsKnown) {
            for (a in listOf("Dep", "Arr", "Altn")) for (f in listOf("TCN", "Elv", "RWY", "ILS")) out += "lbl$a$f"
            out += "txtRwy1"
            // the flight plan's altitude column copies the airports' elevations into the take-off and landing rows
            val f = p.flightTable?.getOrNull(p.selFlightNr)
            if (f != null) {
                if (f.numWaypoints > 0) out += "lblAlt1"
                for (r in 2..minOf(24, f.numWaypoints)) if (p.flightPlan[r - 1].action == 7) out += "lblAlt$r"
            }
        }
        for (k in unknownRadio) { out += "txtC${k}_UHF"; out += "txtC${k}_VHF"; out += "lblCommFreq$k" }
        // (a flight whose route the save gives has them: routedSlots)
        if (!otherRoutesKnown) for (k in 1..5) if (k != ownPackage && k !in routedSlots) { out += "txtCommHoldPt$k"; out += "txtCommHoldAlt$k"; out += "lblCommPushAlt$k" }
        if (!lasersKnown) out += listOf("txtLaserLST1", "txtLaserLST2", "txtLaserCode1", "txtLaserCode2")
        // each jet's own laser code is the campaign's, which the briefing does not print: 0 is "not known"
        // (a save's flight gives them: CampFlight.laser)
        for (n in listOf("txtLead_Lsr", "txtWing1_Lsr", "txtElement_lsr", "txtWing4_Lsr")) if ((!lasersKnown && !jetLasersKnown) || p.text(n) == "0") out += n
        // a support row's on-station window, where a planned track gives one; WDP's zero window otherwise
        for ((box, t) in supportRows()) if (!windowed(t)) out += box + "_Notes"
        return out
    }

    /** The four support rows and the track each shows: WDP's first and second tanker, its AWACS and JSTARS. */
    private fun supportRows(): List<Pair<String, DataCardPlan.Track?>> {
        val p = plan
        return listOf(
            "txtTanker1" to p.tblTankerTrack?.getOrNull(p.firstTanker), "txtTanker2" to p.tblTankerTrack?.getOrNull(p.secondTanker),
            "txtAWACS" to p.tblAwacsTrack?.getOrNull(0), "txtJSTAR" to p.tblJSTARTrack?.getOrNull(0),
        )
    }

    /** A support track with an on-station window of its own (a planned track's); WDP's all-day window is none. */
    private fun windowed(t: DataCardPlan.Track?) = t != null && !(t.time1 == 0L && t.time2 == ALWAYS)

    /**
     * Gathers what the card needs beyond the briefing — the airports and charts of the mission's theater, the
     * cartridge — then loads the mission as [onMission] does. [cartridge] gives the cartridge's text; left out, it
     * is the cartridge of the pilot BMS has selected, read (never written) through the PC.
     */
    suspend fun prepare(mission: WdpMission, cartridge: (suspend () -> String?)? = null) {
        cartridgeRead = cartridge
        val text = readCartridge()
        sources = try { DataCardSources.load(mission, text) } catch (e: Exception) { DataCardSources() }
        // the same mission with new sources is filled again
        sourcesChanged = true
        onMission(mission)
    }

    /** The cartridge's text, the way [prepare] was told to read it: the pilot BMS has selected, through the PC. */
    private suspend fun readCartridge(): String? =
        try { cartridgeRead?.invoke() ?: MissionLink.cartridge()?.text } catch (e: Exception) { null }

    // ================================================================ the mission

    private var lastMission: WdpMission? = null
    /** [prepare] read the airports, charts and cartridge again: the card is filled again even for the same mission */
    private var sourcesChanged = false

    override fun onMission(mission: WdpMission) {
        if (mission == lastMission && !sourcesChanged) return
        sourcesChanged = false
        // The same briefing again — the cartridge saved or rewritten, BMS's route arrived — keeps what the pilot typed
        // on the card (the objectives, notes, fuel figures, codewords); only a new briefing is a new card. The target
        // boxes keep what was typed in them too, unless the mission's own Primary or Secondary changed (WDP's rule,
        // cardTargets). (A cartridge box typed and not saved stays typed: the lamp stays red.)
        val sameTargets = lastMission?.let { it.cardTargets(cardSeat) == mission.cardTargets(cardSeat) } == true
        val keep = if (mission.briefing != null && mission.briefing == lastMission?.briefing)
            typed.filter { n -> sameTargets || TARGET_BOXES.none { n.startsWith(it) } }.associateWith { plan.text(it) }
        else emptyMap()
        // a new briefing is a new card: the attack maps and templates put in the plan boxes were the last mission's
        if (mission.briefing != lastMission?.briefing) {
            profileChosen = null; planPictures.clear(); planTemplates.clear(); planImages.clear()
            // and the map is seen whole again
            mapView.fit()
            // and a weather file Reload WX read was for the last mission, as was the save's own
            fileWx = null; fileWxName = null; fileTakeOff = null
            saveWx = null; saveWxName = null; saveWxWhy = null
            // and the departure runway is the wind's again
            depRwyPick = null
        }
        lastMission = mission
        this.mission = cardMission(mission)
        lookUpBriefedSave(mission)
        typed.clear()
        try { load(this.mission) } catch (e: Exception) { /* a briefing the card cannot read leaves the card as it was */ }
        // what the cartridge and the weather add, each on its own: one failing leaves the other
        try { fromCartridge(sources.cartridge) } catch (e: Exception) { /* a cartridge the card cannot read leaves its boxes */ }
        // a save's flight: its own weather file is read again (quietly, [saveWeather]); until it is in, the card shows
        // the weather it had for this flight, or the printed briefing's
        saveWxPending = this.mission.ref != null && this.mission.flight != null && saveWx == null && saveWxWhy == null
        try { weather(quiet = true) } catch (e: Exception) { /* no weather: the ATIS lines stay empty, as in WDP */ }
        saveWeather(this.mission)
        // typed again as the pilot typed it, through the same handlers (a fuel figure works the ladder out again)
        for ((n, v) in keep) try { handleValue(n, v); plan.leave(n) } catch (e: Exception) { /* a box that no longer takes it */ }
        version++
    }

    /** The briefing the card reads: the mission's, with what [cardMission] added. The Performance page takes its weather from it too. */
    internal val cardBriefing: Briefing? get() = mission.briefing

    /**
     * The mission as the card reads it. A save's flight (Open mission…) comes with the briefing the PC made of it
     * ([Briefing.origin] "save"): the situation, the station or target area, the ROE and the emergency procedures are
     * worded from the save the way BMS's briefing scripts word them, but it has no weather, comm ladder, support list,
     * roster or threat analysis — only a printed briefing has those. When BMS's printed briefing is for that same flight
     * (the PC marks the flight briefed, or the callsign and package agree), the card takes them from it, and its texts
     * win over the ones made of the save (they are what BMS printed), so the ATIS, the frequencies, the tankers and the
     * pilots' names are there as they are for the printed briefing. Where no briefing names a tanker or an AWACS, the
     * ones the save ties to the flight's package fill the support rows, with their planned tracks.
     */
    private fun cardMission(m: WdpMission): WdpMission {
        joined = false
        val b = m.briefing ?: return m
        val f = m.flight
        if (b.origin != "save" || f == null) return m
        var out = b
        val printed = MissionLink.bmsFiles.value?.briefing?.takeIf { it.origin == null && (f.row.briefed || sameFlight(it, b)) }
        if (printed != null) {
            joined = true
            out = out.copy(
                situation = printed.situation ?: out.situation,
                roster = out.roster.ifEmpty { printed.roster },
                threats = out.threats.ifEmpty { printed.threats },
                comms = out.comms.ifEmpty { printed.comms },
                weather = out.weather ?: printed.weather,
                support = out.support.ifEmpty { printed.support },
                roe = printed.roe.ifEmpty { out.roe },
                emergency = printed.emergency.ifEmpty { out.emergency },
                sections = printed.sections.ifEmpty { out.sections },
                overview = out.overview.copy(targetArea = printed.overview.targetArea ?: out.overview.targetArea),
            )
        }
        if (out.support.isEmpty() && f.support.isNotEmpty())
            out = out.copy(support = f.support.map { SupportEntry(callsign = it.callsign, role = it.role) })
        return m.copy(briefing = out)
    }

    /** Set when the save's flight took the printed briefing's situation, weather and comm ladder ([cardMission]). */
    private var joined = false

    /** The same flight in two briefings: the same callsign in the same package. */
    private fun sameFlight(a: Briefing, b: Briefing): Boolean {
        fun key(s: String?) = s?.replace(" ", "")?.lowercase()?.takeIf { it.isNotEmpty() }
        fun pkg(s: String?) = s?.filter { it.isDigit() }?.trimStart('0')?.takeIf { it.isNotEmpty() }
        val c = key(a.overview.flight)
        val p = pkg(a.overview.packageId)
        return c != null && c == key(b.overview.flight) && p != null && p == pkg(b.overview.packageId)
    }

    private fun load(mission: WdpMission) {
        val p = plan
        val b = mission.briefing
        val own = b?.overview?.flight
        val pkg = b?.`package`.orEmpty().take(5)
        // our flight's row, spaces aside ("Cyborg 6" in one table, "Cyborg6" in another): the one row the card keeps (D86)
        val ownIdx = pkg.indexOfFirst { own != null && it.callsign.replace(" ", "").equals(own.replace(" ", ""), ignoreCase = true) }
            .let { if (it < 0) 0 else it }

        // ---- the main form: our flight's route, then the package's other flights (take-off, push and target only)
        val flights = ArrayList<DataCardPlan.Flight>()
        val route = ownRoute(b, mission)
        val ownCount = pkg.getOrNull(ownIdx)?.count ?: b?.roster?.firstOrNull { it.callsign.equals(own, true) }?.pilots?.size ?: 1
        // each jet's laser code where a save's flight gives them (WDP's CONF-LSR column: 1688 …)
        val jetLaser = mission.flight?.laser.orEmpty()
        jetLasersKnown = jetLaser.any { it > 0 }
        flights += DataCardPlan.Flight(route, IntArray(4) { if (it < ownCount) 1 else 0 }, IntArray(4) { jetLaser.getOrElse(it) { 0 } })
        // the other flights' own routes where the save gives them (WDP's FlightTable): their holds, pushes and targets
        val save = saveFlight(mission)
        routedSlots.clear()
        pkg.forEachIndexed { i, f ->
            if (i == ownIdx) return@forEachIndexed
            packageRoute(save, f)?.let { r ->
                routedSlots += i + 1
                flights += DataCardPlan.Flight(r.map { w -> saveWaypoint(w) }, IntArray(4) { if (it < (f.count ?: 1)) 1 else 0 }, IntArray(4))
                return@forEachIndexed
            }
            val to = clock(f.takeoff)?.plus(DAY) ?: 0L
            val push = clock(f.push)?.let { later(it + DAY, to) } ?: 0L
            val tgt = clock(f.target)?.let { later(it + DAY, push.takeIf { v -> v > 0 } ?: to) } ?: 0L
            flights += DataCardPlan.Flight(
                listOf(
                    DataCardPlan.Waypoint(0, 0, 0, to, to, 1, 0),
                    DataCardPlan.Waypoint(0, 0, 0, push, push, 2, 0),
                    DataCardPlan.Waypoint(0, 0, 0, tgt, tgt, 14, 0),
                ),
                IntArray(4) { if (it < (f.count ?: 1)) 1 else 0 }, IntArray(4),
            )
        }
        p.flightTable = flights
        p.selFlightNr = 0
        // the route's clock starts a day on (a take-off at 00:03 is not before the taxi time as a number): the
        // mission's first midnight is there, and a taxi time before it is blank
        p.dayStart = DAY
        p.fixesUsed.clear()
        // E3: the flight's Link 16 STNs and A/A TACAN, where the jet has them — the briefing's (this mission's
        // plan), else the cartridge's
        val l16 = runCatching { Link16Plan.fromBriefing(b) ?: Link16Plan.fromCartridge(sources.cartridgeText) }.getOrNull()
        p.link16Slot = if (l16 != null) ownIdx else -1
        p.link16Stn = l16?.stn.orEmpty()
        p.link16Tcn = l16?.tacan.orEmpty()
        // the support flights as the Briefing page lists them, before the radio map, which takes their frequencies
        supportFlights = supportAssetsOf(b)
        p.blnLoaded = true
        p.blnMissionLoaded = route.isNotEmpty()
        p.blnMissionDtcLoaded = mission.steerpoints.isNotEmpty()
        p.fltRadio = radioMap(b, pkg)
        // FillCommCard's transition level: WDP takes it from its airport database (Korea: 140 for every field) and falls
        // back on 180; BMS's own KTO AIP (2.1.1) gives 14,000 ft / FL140 for the whole theater (D61)
        p.transitionLevel = if (mission.theater?.mapId == "korea") KTO_TRANSITION_LEVEL else 0
        p.milP = ""
        // the cartridge's steerpoints are our flight's; the other flights have none
        val coords = mission.coords
        p.latLon = { i ->
            val pt = if (p.selFlightNr == 0) mission.steerpoints.firstOrNull { it.n == i + 1 } else null
            val w = p.flightTable?.getOrNull(p.selFlightNr)?.waypoints?.getOrNull(i)
            when {
                pt == null || (pt.x == 0.0 && pt.y == 0.0) -> null to null
                // the theater is not known: no projection to print lat/lon with, so the feet stand in for them
                coords == null || w == null -> "N " + kft(pt.x) to "E " + kft(pt.y)
                else -> {
                    // CreateFlightplan: the waypoint's campaign cell, its middle in feet as a float
                    // ((grid + 0.5) × KM_TO_FT), through FeetToCoordsBoth(north, east) — WDP prints the cell, not the
                    // cartridge's own point, and so does the port
                    val north = ((w.gridY.toDouble() + 0.5) * DataCardPlan.KM_TO_FT.toDouble()).toFloat()
                    val east = ((w.gridX.toDouble() + 0.5) * DataCardPlan.KM_TO_FT.toDouble()).toFloat()
                    val s = PopupCoords.feetToCoordsBoth(coords, north.toDouble(), east.toDouble())
                    PopupCoords.getNorthDeg(s) to PopupCoords.getEastDeg(s)
                }
            }
        }
        ownPackage = ownIdx + 1
        // what SelectCallsign asks the campaign for, answered from this briefing
        p.campaign = object : DataCardPlan.Campaign {
            override fun airports(p: DataCardPlan) = airports(b)
            override fun packages(p: DataCardPlan) = fillPackages(pkg, ownIdx, flights)
            override fun support(p: DataCardPlan) = supportTables(b, p.flightTable?.getOrNull(p.selFlightNr)?.waypoints.orEmpty())
        }

        // ---- SelectNewFlight
        p.blnSelectingFlight = true
        p.clearDatacard()
        p.enableCallsigns()
        p.createFlightplan(false)
        p.selFltInPack = ownIdx + 1
        fillPackages(pkg, ownIdx, flights)
        p.fillAutoPackages()
        // the flight's pilots, as the roster names them
        val names = b?.roster?.firstOrNull { it.callsign.equals(own, true) }?.pilots.orEmpty()
        for (i in 0..3) p.tblFlight[ownIdx.coerceIn(0, 4)].names[i] = names.getOrNull(i) ?: ""
        p.fillAutoFlight()
        p.fillDataCard()
        p.fillCommCardPackages()
        p.fillCommCard()
        airports(b)
        p.blnLoaded = false          // no chart buttons: the app has its own charts
        p.fillAptLabels()
        p.blnLoaded = true
        p.fillFlightplan()
        support(b, route)
        // SelectNewFlight ends on the lead's seat: rbnLead.Checked = True, SelectPilotSeat — here on the seat picked
        // with the flight (Open mission…), which is the lead's unless the pilot chose another
        cardSeat = mission.seat.takeIf { it in 0..3 && p.seatEnabled[it] } ?: 0
        p.selectPilotSeat(cardSeat)
        p.blnSelectingFlight = false

        // ---- OpenFile's tail
        for (i in 0..3) p.strNames[i + 1] = names.getOrNull(i) ?: ""
        // OpenFile: WDP's mission is the save it opened, by its file name ("Auto Save"), which the Coordination Card
        // prints after the package number and the DataCard as its Mission; the briefing's mission only without a save
        p.strMission = saveName(mission) ?: b?.overview?.mission ?: ""
        p.selPackageName = b?.overview?.packageId ?: ""
        p.fillDataCard()
        p.fillCommCardPackages()
        p.fillCommCard()
        targets(mission)
        briefingPage(b, pkg.getOrNull(ownIdx)?.let { it.aircraft ?: "" } ?: "", route)
        ordnance()
        roe(b)
        bullseyeBox()
        routeSpinner = 0; taxiSpinner = 0; profileButtons = false; tankerList = 0
        modeVisible.fill(false)
    }

    /**
     * Our flight's waypoints as the campaign would hold them, from the briefing's rows and the cartridge's feet.
     *
     * The cartridge only holds the steerpoints once the pilot has saved it (or BMS has, entering 3D), and WDP's
     * `CreateFlightplan` takes a waypoint with no position as the end of the route — so a briefing printed before
     * the cartridge was saved came out as two rows and twenty-two "Precision"s. Where the cartridge has no position
     * for a row, it is laid from the one before along the briefing's own heading and distance, which is what BMS
     * computed those two columns from; the card then prints the briefing's figures back. Only the latitude and
     * longitude stay blank, and the rows the briefing gives no leg for (an alternate after the landing) have their
     * heading and distance blanked rather than shown as zero ([unknownLegs]).
     */
    private fun ownRoute(b: Briefing?, mission: WdpMission): List<DataCardPlan.Waypoint> {
        val rows = b?.steerpoints.orEmpty().sortedBy { it.n }.take(24)
        val out = ArrayList<DataCardPlan.Waypoint>()
        unknownLegs.clear()
        // where the last row was, in feet east and north; an arbitrary origin when the cartridge has none at all
        var east = ORIGIN_FT
        var north = ORIGIN_FT
        var last = -1L
        // campaign time runs from the first day: a take-off at 00:03 is not "before" the taxi time (FillCommCard)
        var day = DAY
        // the save's own waypoints of this flight, by steerpoint number: their planned altitudes
        val saveAlt = saveFlight(mission)?.route.orEmpty().withIndex().associate { (i, w) -> (if (w.n > 0) w.n else i + 1) to w.altFt }
        for ((i, r) in rows.withIndex()) {
            val known = mission.steerpoints.firstOrNull { it.n == r.n }?.takeIf { it.x != 0.0 || it.y != 0.0 }
            if (known != null) {
                east = known.y; north = known.x
            } else if (i > 0) {
                val nm = r.dist?.trim()?.toDoubleOrNull()
                val hdg = r.heading?.trim()?.toDoubleOrNull()
                if (nm != null && hdg != null) {
                    val a = hdg * kotlin.math.PI / 180.0
                    east += nm * 6076.12 * kotlin.math.sin(a)
                    north += nm * 6076.12 * kotlin.math.cos(a)
                } else {
                    // no leg in the briefing: a cell further on, so the route does not end here, and no figures
                    east += DataCardPlan.KM_TO_FT
                    unknownLegs += i + 1
                }
            }
            // the altitude to fly, as WDP's CreateFlightplan takes it: the save's waypoint (GridZ × 10 ft), else the
            // briefing's own ALT column. Never the steerpoint's z: BMS 4.38.1 writes a route point's ground elevation
            // there, in the cartridge and in the mission file alike (a Nav point briefed at 24.0M reads -891.7), so
            // the 1.3.8 card, which took it from a cartridge slot, showed ground elevations in the Alt column
            val altFt = saveAlt[r.n] ?: briefAlt(r.alt)
            var arrive = clock(r.time)?.let { it + day } ?: 0L
            if (arrive in 1 until last) { day += DAY; arrive += DAY }
            if (arrive > 0) last = arrive
            var depart = departure(r)?.let { it + day } ?: arrive
            if (depart in 1 until arrive) depart += DAY
            out += DataCardPlan.Waypoint(
                gridX = cell(east), gridY = cell(north),
                gridZ = (altFt / 10.0).toInt(),
                arrive = arrive, depart = depart,
                action = actionOf(r), formation = formationOf(r.formation),
            )
        }
        return out
    }

    /** The briefing's altitude column: "21.0M" is 21,000 ft, "--" is nothing. */
    private fun briefAlt(s: String?): Double {
        val v = Regex("(\\d+(?:\\.\\d+)?)").find(s ?: return 0.0)?.groupValues?.get(1)?.toDoubleOrNull() ?: return 0.0
        return if (v < 100.0) v * 1000.0 else v
    }

    private fun cell(feet: Double?): Int = if (feet == null) 0 else floor(feet / DataCardPlan.KM_TO_FT.toDouble()).toInt().coerceIn(0, Short.MAX_VALUE.toInt())

    /**
     * The save the card is filled from, as WDP always has one open: the flight Open mission… picked, else — with the
     * printed briefing — the save's flight BMS printed it for, once [lookUpBriefedSave] has found it. Null without either.
     */
    private fun saveFlight(m: WdpMission): CampFlight? =
        m.flight ?: briefedFlight?.takeIf { (k, _) -> m.briefing?.let { it.origin != "save" && BriefedSave.key(it) == k } == true }?.second

    /** WDP's strMission, `Path.GetFileNameWithoutExtension(TeFile)`: the save's file name ("Auto Save"), or null without a save. */
    private fun saveName(m: WdpMission): String? {
        val file = if (m.flight != null) m.ref?.file
        else m.briefing?.takeIf { it.origin != "save" }?.let { BriefedSave.known(BriefedSave.key(it))?.file }
        return file?.substringAfterLast('\\')?.substringAfterLast('/')?.let { if ('.' in it) it.substringBeforeLast('.') else it }?.takeIf { it.isNotBlank() }
    }

    /**
     * Package flight [f]'s own route in the save ([CampFlight.packageRoutes]), found by its flight number, else its
     * callsign; null when the save does not give it (an older PC, a printed briefing whose save was not found).
     */
    private fun packageRoute(save: CampFlight?, f: PackageFlight): List<CampWaypoint>? {
        if (save == null) return null
        fun key(s: String?) = s?.replace(" ", "")?.lowercase()
        val num = f.flightId?.trim()?.toIntOrNull()
        val row = save.packageFlights.firstOrNull { num != null && it.number == num }
            ?: save.packageFlights.firstOrNull { key(it.callsign) == key(f.callsign) } ?: return null
        return save.packageRoutes.firstOrNull { it.id == row.id }?.route?.takeIf { it.isNotEmpty() }
    }

    /**
     * A save's waypoint as WDP's flight table holds it: its cell, its altitude in tens of feet (GridZ), its times —
     * a day on, as the briefing's own route is laid ([ownRoute]), so the taxi times work out the same way; a time the
     * save leaves at 0 stays 0 (WDP prints nothing for it). A save keeps milliseconds (Beast7 takes off at
     * 02:00:32.561); WDP and BMS's own briefing print the second they are in (02:00:32), so the time is cut to its
     * second here, where [DataCardPlan.getTime] would round it up.
     */
    private fun saveWaypoint(w: CampWaypoint): DataCardPlan.Waypoint {
        fun t(ms: Long) = if (ms == 0L) 0L else ms / 1000L * 1000L + DAY
        return DataCardPlan.Waypoint(
            gridX = cell(w.y), gridY = cell(w.x), gridZ = (w.altFt / 10.0).roundToInt(),
            arrive = t(w.arriveMs), depart = t(w.departMs), action = w.action, formation = w.formation,
        )
    }

    /**
     * The theater's radio map, as far as the briefing's comm ladder names flights, and the support flights with the
     * frequencies the app has for them. WDP's own map (the theater's RadioMap) has every flight, and
     * `FillAutoPackages` stops (FltRadio(-1)) on a package flight it lacks; the ladder only names our own flight and
     * the support, so the package's other flights get an entry of 0 kHz, which the card shows blank
     * ([unknownRadio]). A frequency the ladder leaves out (`--`) is 0 as well. BMS writes a tanker's ladder row as
     * "Copper2 (TCN: 059Y)": the callsign is the name before the TACAN, or the card would never find it.
     *
     * The app has the theater's radio map too ([DataCardSources.radio], BMS's `RadioMap.dat`, the file WDP's `ReadRadio`
     * reads: its first frequency WDP's UHF, its second the VHF). What the ladder leaves out comes from there, as WDP
     * has it — a frequency the ladder prints as `--` (a tanker's VHF) and every package flight the ladder does not
     * name — so the Coordination Card's In-flight Freq is filled for the whole package, as WDP's is. Only a flight
     * the theater's map does not have either stays unknown.
     */
    private fun radioMap(b: Briefing?, pkg: List<PackageFlight>): List<DataCardPlan.FltRad> {
        fun key(s: String?) = DataCardNet.lcase((s ?: "").replace(" ", ""))
        val theater = sources.radio.associateBy { key(it.agency) }
        fun or0(v: String?, map: String?) = v?.takeIf { it != "0" } ?: khz(map) ?: "0"
        val out = b?.comms.orEmpty().filter { !it.callsign.isNullOrBlank() }
            .map { DataCardPlan.FltRad(bareCallsign(it.callsign), khz(it.uhf) ?: "0", khz(it.vhf) ?: "0") }.toMutableList()
        for (s in supportFlights) if (out.none { key(it.callsign) == key(s.callsign) }) {
            out += DataCardPlan.FltRad(s.callsign, khz(s.uhf) ?: "0", khz(s.vhf) ?: "0")
        }
        // a save's support flights of the whole side (WDP's tanker, AWACS and JSTARS tables): their frequencies are the
        // theater's radio map's, below, as WDP's GetFltComm finds them
        for (s in mission.flight?.sideSupport.orEmpty()) if (out.none { key(it.callsign) == key(s.callsign) }) out += DataCardPlan.FltRad(s.callsign, "0", "0")
        for ((i, r) in out.withIndex()) theater[key(r.callsign)]?.let { t -> out[i] = DataCardPlan.FltRad(r.callsign, or0(r.uhf, t.uhf1), or0(r.vhf, t.vhf)) }
        unknownRadio.clear()
        pkg.forEachIndexed { i, f ->
            if (out.none { key(it.callsign) == key(f.callsign) }) {
                val t = theater[key(f.callsign)]
                out += DataCardPlan.FltRad(f.callsign, khz(t?.uhf1) ?: "0", khz(t?.vhf) ?: "0")
                if (t == null) unknownRadio += i + 1
            }
        }
        return out
    }

    /**
     * Departure, arrival and alternate: the frequencies from the comm ladder's Dep/Arr/Alt entries, and the rest —
     * TACAN, elevation, runway, ILS, the frequencies the ladder leaves out — from the app's airport database, as
     * WDP's `cntDTC.Departure`/`Arrival`/`Alternate` fill them from its own. The runway is the end most nearly into
     * the briefing's take-off wind, the one the ATIS names (WDP prints its database's first runway there and picks
     * the one into the wind for the ATIS); with no weather it is the first.
     */
    private fun airports(b: Briefing?) {
        val comms = b?.comms.orEmpty()
        fun pick(prefix: String, what: String): CommEntry? = comms.firstOrNull { it.agency.startsWith(prefix, true) && it.agency.contains(what, true) }
        fun mhz(s: String?): Float = s?.toFloatOrNull() ?: 0f
        var known = false
        // the three fields as every Mission page finds them: a name that fits several (BMS's "USS") stays unknown
        val bases = sources.bases(b, mission.flight)
        for ((i, prefix) in listOf("Dep", "Arr", "Alt").withIndex()) {
            val a = plan.tblApt[i]
            val tower = pick(prefix, "Tower")
            val any = comms.firstOrNull { it.agency.startsWith(prefix, true) && !it.callsign.isNullOrBlank() }
            val named = (tower?.callsign ?: any?.callsign)?.let { stripAgency(it) } ?: ""
            val db = bases[i]
            apt[i] = db
            ends[i] = db?.runways.orEmpty().flatMap { it.ends }
            a.name = db?.name ?: named
            a.uhf = mhz(tower?.uhf).takeIf { it != 0f } ?: mhz(db?.freqs?.towerUhf)
            a.vhf = mhz(tower?.vhf).takeIf { it != 0f } ?: mhz(db?.freqs?.towerVhf)
            a.gndUHF = mhz(pick(prefix, "Ground")?.uhf).takeIf { it != 0f } ?: mhz(db?.freqs?.groundUhf)
            a.appUHF = mhz((pick(prefix, "Approach") ?: pick(prefix, "Departure"))?.uhf).takeIf { it != 0f } ?: mhz(db?.freqs?.approachUhf)
            a.atisVHF = mhz(pick(prefix, "Atis")?.vhf).takeIf { it != 0f } ?: mhz(db?.freqs?.atisVhf)
            a.opsUHF = (if (i == 0) mhz(comms.firstOrNull { it.agency.startsWith("Base Ops", true) }?.uhf) else 0f)
                .takeIf { it != 0f } ?: mhz(db?.freqs?.opsUhf)
            a.lsoUHF = mhz(db?.freqs?.lsoUhf)
            if (db == null) { a.tcn = ""; a.elv = 0f; a.rwy = ""; a.ils = 0f; continue }
            known = true
            a.tcn = sources.tacanOf(db)
            a.elv = (db.elevationFt ?: 0).toFloat()
            a.elvByTerrain = false
            val end = ends[i].getOrNull(CardWeather.intoWind(ends[i].map { it.designator to it.headingTrue }, CardWeather.column(b, if (i == 0) 0 else 2)?.windDir))
            a.rwy = end?.designator ?: ""
            a.ils = end?.ils?.toFloatOrNull() ?: 0f
        }
        airportsKnown = known
        // the runway is the wind's until the pilot picks one (a weather file's wind may pick another); a pick stays
        // through the refills of the same briefing (a Save to DTC, a PRINT of the same flight) while the field is the same
        val kept = depRwyPick?.takeIf { it.first == apt[0]?.id }?.let { k -> ends[0].firstOrNull { it.designator == k.second } }
        if (kept != null) {
            plan.tblApt[0].rwy = kept.designator
            plan.tblApt[0].ils = kept.ils?.toFloatOrNull() ?: 0f
        } else depRwyPick = null
        depRwyChosen = kept != null
        // the departure's runway list, which the card's second taxi row reads
        plan.depRunways = ends[0].map { it.designator }
        plan.depRwyIndex = plan.depRunways.indexOf(plan.tblApt[0].rwy ?: "")
    }

    /** The transition level the ATIS names: the Coordination Card's (FL140 on KTO's terrain, D61), else WDP's 180. */
    private fun atisTrl(): Int = plan.transitionLevel.takeIf { it > 0 } ?: 180

    /** A runway end picked from an airport's runway list (`SetDepApt` and its kin): the runway and its ILS. */
    private fun runwayPicked(i: Int, designator: String) {
        val end = ends[i].firstOrNull { it.designator == designator } ?: return
        plan.tblApt[i].rwy = end.designator
        plan.tblApt[i].ils = end.ils?.toFloatOrNull() ?: 0f
        if (i == 0) { plan.depRwyIndex = plan.depRunways.indexOf(end.designator); depRwyChosen = true; depRwyPick = apt[0]?.id?.let { it to end.designator } }
        plan.fillAptLabels()
        plan.fillFlightplan()
        runwayList = -1
        weather(quiet = true)
    }

    /** Another airfield for row [i] from WDP's airport window (`lblDepName_Click` …). */
    private fun airportPicked(i: Int, a: Airport?) {
        if (a == null) return
        val t = plan.tblApt[i]
        apt[i] = a
        ends[i] = a.runways.flatMap { it.ends }
        t.name = a.name
        t.tcn = sources.tacanOf(a)
        t.elv = (a.elevationFt ?: 0).toFloat()
        t.elvByTerrain = false
        fun mhz(s: String?): Float = s?.toFloatOrNull() ?: 0f
        t.uhf = mhz(a.freqs?.towerUhf); t.vhf = mhz(a.freqs?.towerVhf); t.gndUHF = mhz(a.freqs?.groundUhf)
        t.appUHF = mhz(a.freqs?.approachUhf); t.atisVHF = mhz(a.freqs?.atisVhf); t.opsUHF = mhz(a.freqs?.opsUhf); t.lsoUHF = mhz(a.freqs?.lsoUhf)
        val end = ends[i].getOrNull(CardWeather.intoWind(ends[i].map { it.designator to it.headingTrue }, CardWeather.column(mission.briefing, if (i == 0) 0 else 2)?.windDir))
        t.rwy = end?.designator ?: ""
        t.ils = end?.ils?.toFloatOrNull() ?: 0f
        if (i == 0) { plan.depRunways = ends[0].map { it.designator }; plan.depRwyIndex = plan.depRunways.indexOf(t.rwy ?: ""); depRwyChosen = false; depRwyPick = null }
        airportsKnown = true
        plan.fillAptLabels()
        plan.fillFlightplan()
        weather(quiet = true)
    }

    /**
     * The cartridge's boxes on the card, as the DTC page fills them in WDP (`cntDTC`'s EWS, weapon, laser and ICP
     * fills): the six EWS program names, both bomb profiles with the sub-mode, fuze and single/pair lists on them,
     * the laser codes, ALOW, MSL floor and bingo, and the offset aimpoints the cartridge holds.
     */
    private fun fromCartridge(m: DtcModel?) {
        if (m == null) return
        val p = plan
        m.ews.program?.let { pr -> for (i in 1..6) p.strEws[i] = pr.getOrNull(i - 1)?.comment ?: "" }
        // cntDTC.FillWeapondata, whose boxes the card copies (DoDataCard): a sub-mode the lists do not have is CCIP, a
        // fuze NSTL and a release anything but PAIR is SGL; the arming delay is the masked "00.00" box ("0#.00" under
        // 10 s) and the height of burst the masked "0000" box, so 500 ft reads "0500" as on WDP's card
        fun profile(b: com.bmscompanion.app.data.wdp.DtcBombProfile, base: Int, list: Int) {
            val sub = when (b.submode) { 8 -> 0; 7 -> 1; 9 -> 2; 10 -> 3; else -> 1 }
            val fuze = if (b.fuze in 0..2) b.fuze else 0
            val pair = b.sglPair == 1
            p.dtcModeSel[list] = sub
            p.dtcModeSel[list + 1] = fuze
            p.dtcModeSel[list + 2] = if (pair) 1 else 0
            p.wpn[base] = DataCardPlan.SUBMODES.getOrNull(sub) ?: ""
            p.wpn[base + 1] = DataCardPlan.FUZES.getOrNull(fuze) ?: ""
            // the DTC page's boxes hold the arming delay in seconds (the cartridge in hundredths) and the rest as numbers
            p.wpn[base + 2] = DataCardNet.fmt(b.c1Ad1 / 100f, "00.00")
            p.wpn[base + 3] = b.c2Ba.let { if (it in 0..999) it.toString().padStart(4, '0') else it.toString() }
            p.wpn[base + 4] = b.releaseAngle.toString()
            p.wpn[base + 5] = if (pair) "PAIR" else "SGL"
            p.wpn[base + 6] = b.releasePulse.toString()
            p.wpn[base + 7] = b.releaseSpacing.toString()
        }
        profile(m.agb1, 0, 0)
        profile(m.agb2, 8, 3)
        p.laserCode = m.laser.laserCode.toInt()
        p.laserLST = m.laser.lstCode.toInt()
        lasersKnown = true
        // the card shows the cartridge's codes (fillDataCard below prints them)
        // the offset aimpoints the cartridge carries. The card names no delivery for them: WDP's card says "None" and
        // leaves the Delivery block empty until a profile is chosen with its PopUp, HADB or TOSS button (its only
        // profiles; "Else" is the DTC page's word, never the card's), and heads the block VIP or VRP by the cartridge
        val n = m.nav
        val src = listOf(n.vip, n.vipPup, n.vrp, n.vrpPup, n.oa1_1, n.oa2_1, n.oa1_2, n.oa2_2)
        p.campNavOffsets.modesel = n.modesel
        src.forEachIndexed { i, o -> val d = p.campNavOffsets.points[i]; d.stpt = o.stpt; d.bearing = o.bearing; d.range = o.range; d.elv = o.elv }
        if (n.modesel != 0) p.attack.modesel = n.modesel
        p.fillNavOffsetsData()
        p.fillDataCard()
        p.type("txtALOW", DataCardNet.str(m.icp.alowAgl))
        p.type("txtMSL", m.icp.alowMsl.toString())
        p.type("txtBingo", DataCardNet.str(m.icp.bingoFuel))
    }

    /**
     * Reload WX (`btnWeather_Click`): the card's weather into the ATIS lines (`CreateAtis`), the Briefing page's weather
     * list (`CreateWeatherList`) and Force QNH. It comes from the first of:
     *
     * 1. a weather file Reload WX picked ([fileWx]), until another mission;
     * 2. the save's own weather file ([saveWx], a save's flight), when no printed briefing of the same flight gives
     *    weather, or when the file was saved after the briefing was printed and says other weather: the newer wins;
     * 3. the printed briefing's weather — with the QNH of the save's own file where the briefing prints none, as BMS
     *    4.38.1's never does, and the list says so.
     *
     * WDP reads only the save's file (`OpenFile` → `ReadWeather`), never BMS's printed briefing (D68). The weather list
     * opens with where the weather comes from and as of when. [quiet] is the reload that comes with a new briefing,
     * which says nothing when there is no weather; the button says why.
     */
    private fun weather(quiet: Boolean) {
        val p = plan
        weatherNotice = null
        wxSource = emptyList()
        wxFileInUse = null
        // the take-off weather the Performance page takes is set again below, or stays unset: never the last file's
        fileTakeOff = null
        // a weather file Reload WX read is the card's weather until another mission
        fileWx?.let { w ->
            val name = fileWxName ?: WdpFiles.nameOf(w.path)
            if (runCatching { weatherFromFile(w, "$name (Reload WX)", emptyList()) }.getOrDefault(false)) { wxFileInUse = name; return }
        }
        val b = mission.briefing
        val printed = CardWeather.column(b, 0)
        val printedAt = if (printed != null) printedTime() else 0L
        // the save's own weather file: when there is no printed weather, or it was saved after the briefing was
        // printed and says other weather (a save of the same weather keeps BMS's own forecast, with its wind direction)
        var sameLater = false
        saveWx?.let { w ->
            val dep = runCatching { fileDeparture(w) }.getOrNull() ?: return@let
            val newer = w.modified > 0 && printedAt > 0 && w.modified > printedAt
            if (printed != null && !newer) return@let
            if (printed != null && CardWeather.sameWeather(dep, printed)) { sameLater = true; return@let }
            val name = saveWxName ?: WdpFiles.nameOf(w.path)
            val why = if (printed == null) null else "$name was saved at ${fileTime(w.modified)}, after the briefing was printed " +
                "(${fileTime(printedAt)}), with other weather: the card plans with the save's. PRINT again in BMS for its forecast."
            if (runCatching { weatherFromFile(w, name + ", as saved at " + dayClock(w.twx?.clock ?: mission.flight?.clock ?: 0L), listOfNotNull(why, LATER)) }.getOrDefault(false)) {
                if (why != null) weatherNotice = "Weather from the save, newer than PRINT" to why
                wxFileInUse = name
                return
            }
        }
        val a = apt[0]
        val end = ends[0].firstOrNull { it.designator == p.tblApt[0].rwy }
        val take = p.flightTable?.getOrNull(p.selFlightNr)?.waypoints?.firstOrNull()?.depart ?: 0L
        // BMS 4.38.1's briefing prints no pressure: the save's own file holds the one BMS flies with
        val qnh = if (printed != null && printed.qnhHpa == null) saveWx?.let { runCatching { fileDeparture(it) }.getOrNull() }?.qnhHpa else null
        val c = if (qnh != null) printed?.withQnh(qnh) else printed
        fileTakeOff = if (qnh != null) c else null
        val atis = c?.let { CardWeather.atis(it, CardWeather.column(b, 2), a?.icao, p.tblApt[0].rwy, end?.ils != null, take, civil = p.blnAtis, metric = p.blnKmSm, trl = atisTrl()) }
        if (c == null || atis == null) {
            p.blnWth = false
            weatherOnPaper = false
            // no weather: Force QNH is not left at the last weather's pressure (a pilot's own stays)
            if ("txtForceQnh" !in typed) p.type("txtForceQnh", "")
            // a save's flight without a printed briefing of its own
            val fromSave = b?.origin == "save" && !joined
            val why = saveWxWhy ?: saveWx?.let { "${saveWxName ?: WdpFiles.nameOf(it.path)} gives no weather at the departure: the card could not place the field on the theater." }
            when {
                // the save's own weather could not be used: the card says why, where WDP's would be blank
                fromSave && why != null -> {
                    wxLines = "WEATHER\n\n" + wrap(why, WX_WIDTH).joinToString("\n")
                    p.type("lblAtis1", "No weather from the save: " + why.substringBefore(": ").trimEnd('.')); p.type("lblAtis2", "")
                    weatherNotice = "No weather from the save" to why
                }
                // being read: nothing to say yet
                fromSave && saveWxPending -> { wxLines = ""; p.type("lblAtis1", ""); p.type("lblAtis2", "") }
                // a flight with no save file to read (none named): the Briefing page says where the rest comes from,
                // and the DataCard's ATIS line the same
                fromSave -> { wxLines = SAVE_ONLY; p.type("lblAtis1", PRINT_ATIS); p.type("lblAtis2", "") }
                else -> wxLines = ""
            }
            if (!quiet) WdpDialogs.message("Reload WX", when {
                b == null -> "There is no briefing yet: press PRINT on the BMS briefing screen."
                fromSave && why != null -> "$why\n\nPick a weather file with Reload WX, or PRINT the briefing in BMS with this flight selected."
                fromSave -> "This flight names no save file to read its weather (.twx) from. Press PRINT on BMS's briefing " +
                    "screen with this flight selected: the briefing's weather then comes onto the card by itself."
                else -> "The briefing has no weather block."
            })
            return
        }
        p.blnWth = true
        weatherOnPaper = true
        p.atis1 = atis.first
        p.atis2 = atis.second
        p.fillAtisLabel()
        // WDP's ATIS fills the Coordination Card's Force QNH too — with a pressure the briefing or the save gives, never
        // with the standard one (a pilot's forced QNH is not 29.92 because the briefing is silent)
        if ("txtForceQnh" !in typed) p.type("txtForceQnh", CardWeather.forceQnh(c.qnhHpa, p.blnKmSm))
        val src = ArrayList<String>()
        src += "from BMS's printed briefing" + (if (printedAt > 0) ", printed ${fileTime(printedAt)}" else "")
        val saveName = saveWxName ?: saveWx?.path?.let { WdpFiles.nameOf(it) }
        if (sameLater && saveName != null) src += "$saveName, saved later, gives the same weather."
        if (qnh != null && saveName != null) src += "QNH ${qnh.roundToInt()} hPa from $saveName: the briefing prints no pressure."
        if (b?.origin == "save") saveWxWhy?.let { src += "The save's own weather file is not used: $it" }
        src += LATER
        wxSource = src
        wxLines = withLines(CardWeather.list(b, atis), 1, src)
    }

    /**
     * A weather file's weather at the departure (the field's own cell of a map, or the table of the type the file is
     * in) when the ATIS is issued, 31 minutes before the take-off; the first placed steerpoint's when the field is not
     * known. Null when the file gives none there (no theater, no field and no steerpoint to place it by).
     *
     * WDP samples a map at the take-off waypoint's cell (`CreateAtisFmap`: `waypoints[0].GridX/Y`); the card at the
     * field's own position, as it does every other field of the list (D70).
     */
    private fun fileDeparture(w: com.bmscompanion.app.data.mission.PcWeather): CardWeather.Column? {
        val take = plan.flightTable?.getOrNull(plan.selFlightNr)?.waypoints?.firstOrNull()?.depart ?: 0L
        val issued = take - 31 * 60_000L
        // a .twx flown on BMS's four types has one table for the whole theater: it needs no field, and no theater the
        // app knows (as the Mission section's weather reads it, SaveWeather)
        if (w.cols <= 0 || w.rows <= 0) return CardWeather.column(w, 0.0, 0.0, 0.0, issued)
        val size = mission.theater?.sizeFt ?: return null
        val placed = mission.steerpoints.firstOrNull { it.x != 0.0 || it.y != 0.0 }
        return apt[0]?.let { CardWeather.column(w, it.x, it.y, size, issued) }
            ?: placed?.let { CardWeather.column(w, it.x, it.y, size, issued) }
    }

    /**
     * The card's weather out of a weather file [w] — Reload WX's, or the save's own (WDP's `CreateAtisFmap` /
     * `CreateAtis` and `CreateWeatherList`, after `btnWeather_Click` or `OpenFile` has read it): the ATIS from the
     * departure field's cell of a map (or from the table of the type the campaign is in, for a `.twx` flown on BMS's
     * four types) at the time the ATIS is issued, the arrival's weather as its trend, Force QNH, and the weather list —
     * the target, the departure, the arrival, the alternate and the fields around the departure, each at its own cell.
     * [from] names the file on the list ("from …"), [extra] the lines under it. False when the file gives no weather
     * at the departure (no theater, no field and no steerpoint to place it by).
     *
     * As WDP's `CreateAtis` does (`PreferedRwy`), the departure runway becomes the one most nearly into the file's
     * take-off wind — the ATIS names it and the Performance page takes it — unless the pilot picked one on the card; a
     * variable wind (a `.twx` whose direction BMS picks) keeps the runway there is.
     */
    private fun weatherFromFile(w: com.bmscompanion.app.data.mission.PcWeather, from: String, extra: List<String>): Boolean {
        val p = plan
        // no theater the app knows: only a file with no map reads ([fileDeparture]), and it is the same table everywhere
        val size = mission.theater?.sizeFt ?: 0.0
        val take = p.flightTable?.getOrNull(p.selFlightNr)?.waypoints?.firstOrNull()?.depart ?: 0L
        val issued = take - 31 * 60_000L
        fun at(north: Double, east: Double) = CardWeather.column(w, north, east, size, issued)
        fun atField(a: Airport?) = a?.let { at(it.x, it.y) }
        val placed = mission.steerpoints.filter { it.x != 0.0 || it.y != 0.0 }
        val dep = fileDeparture(w) ?: return false
        val dir = dep.windDir
        if (!depRwyChosen && dir != null && ends[0].isNotEmpty()) {
            val into = ends[0].getOrNull(CardWeather.intoWind(ends[0].map { it.designator to it.headingTrue }, dir))
            if (into != null && into.designator != p.tblApt[0].rwy) {
                p.tblApt[0].rwy = into.designator
                p.tblApt[0].ils = into.ils?.toFloatOrNull() ?: 0f
                p.depRwyIndex = p.depRunways.indexOf(into.designator)
                p.fillAptLabels()
                p.fillFlightplan()
            }
        }
        val end = ends[0].firstOrNull { it.designator == p.tblApt[0].rwy }
        val atis = CardWeather.atis(dep, atField(apt[1]), apt[0]?.icao, p.tblApt[0].rwy, end?.ils != null, take, civil = p.blnAtis, metric = p.blnKmSm, trl = atisTrl())
        p.blnWth = true
        weatherOnPaper = true
        p.atis1 = atis.first
        p.atis2 = atis.second
        p.fillAtisLabel()
        fileTakeOff = dep
        if ("txtForceQnh" !in typed) p.type("txtForceQnh", CardWeather.forceQnh(dep.qnhHpa, p.blnKmSm))
        fun named(a: Airport?) = a?.let { listOfNotNull(it.icao?.takeIf { i -> i.isNotBlank() }, it.name).joinToString(" ") } ?: ""
        val tgt = mission.defaultStpt?.let { n -> placed.firstOrNull { it.n == n } }
        val places = listOf(
            Triple("Target", tgt?.let { "STPT ${it.n}" } ?: "", tgt?.let { at(it.x, it.y) }),
            Triple("Departure", named(apt[0]), dep),
            Triple("Arrival", named(apt[1]), atField(apt[1])),
            Triple("Alternate", named(apt[2]), atField(apt[2])),
        )
        // WDP's "Extended around departure": up to five of the side's airbases within 200 km of the departure. The
        // app does not know whose field is whose, so it is the five nearest airbases within that distance.
        val depAt = apt[0]?.let { it.x to it.y } ?: placed.firstOrNull()?.let { it.x to it.y }
        val shownIds = apt.mapNotNull { it?.id }.toSet()
        val around = if (depAt == null) emptyList() else sources.airports?.airports.orEmpty()
            .asSequence()
            .filter { it.id !in shownIds && it.type.contains("Airbase", ignoreCase = true) && (it.x != 0.0 || it.y != 0.0) }
            .map { it to hypot(it.x - depAt.first, it.y - depAt.second) }
            .filter { it.second < 200_000 * 3.28084 }
            .sortedBy { it.second }
            .take(5)
            .mapNotNull { (a, _) -> atField(a)?.let { named(a) to it } }
            .toList()
        // what the file itself says of where its weather is from (a map it flies with that is not there: its tables)
        val lines = listOfNotNull(w.note) + extra
        wxSource = listOf("from $from") + lines
        wxLines = withLines(CardWeather.fileList(from, places, around, atis, take, civil = p.blnAtis, metric = p.blnKmSm), 2, lines)
        return true
    }

    /** The time BMS printed the briefing on the card (its `briefing.txt`), ms since 1970; 0 when unknown. */
    private fun printedTime(): Long = MissionLink.bmsFiles.value?.briefingModified ?: 0L

    /** A file time as this device's clock shows it: "30 Sep 00:50". */
    private fun fileTime(ms: Long): String = if (ms <= 0) "an unknown time" else runCatching {
        java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(java.util.Date(ms))
    }.getOrDefault("?")

    /** A campaign time as BMS's clock shows it: "D1 01:02". */
    private fun dayClock(ms: Long): String {
        val m = ms / 60_000L
        return "D" + (m / 1440 + 1) + " " + (m % 1440 / 60).toString().padStart(2, '0') + ":" + (m % 60).toString().padStart(2, '0')
    }

    /** [text] in lines of at most [width] characters, broken between words. */
    private fun wrap(text: String, width: Int): List<String> {
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            if (line.isNotEmpty() && line.length + 1 + word.length > width) { out += line.toString(); line = StringBuilder() }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    /** The weather list [list] with [extra] under its first [at] lines, each wrapped to the panel's width. */
    private fun withLines(list: String, at: Int, extra: List<String>): String {
        if (list.isEmpty() || extra.isEmpty()) return list
        val lines = list.lines().toMutableList()
        lines.addAll(at.coerceIn(0, lines.size), extra.flatMap { wrap(it, WX_WIDTH) })
        return lines.joinToString("\n")
    }

    /** How many characters a line of the Briefing page's weather list holds (its table: 13 + 3 x 21). */
    private val WX_WIDTH = 76
    /** What the weather list says under where its weather comes from. */
    private val LATER = "BMS may change the weather before take-off."

    /**
     * Reload WX (`btnWeather_Click`): WDP's "Load WX FMAP File" window on the BMS PC — in the folder of the campaign's
     * own weather file, `FMAP(*.fmap)` and `TWX(*.twx)` — and the weather of the file picked onto the card, through
     * the PC ([com.bmscompanion.app.data.mission.MissionLink.filesWeather]):
     *
     * - an **`.fmap`** is read at every field's own cell (any version: BMS's saves and training maps are version 8);
     * - a **`.twx`** is read as WDP's `ReadWeather` does and, when its model is a weather map, with the map BMS flies
     *   with it at the save's clock (the update map in force, or the `.fmap` beside it — `GetFmap`), or its own tables
     *   when that map is not there (WDP's fallback); otherwise the table of the weather type the campaign is in.
     *
     * The ATIS, the weather list and Force QNH are then made again from it ([weatherFromFile]) and the Performance page
     * takes the take-off weather. **Fixed:** WDP's own button did nothing visible with an `.fmap`. Its read returns -1
     * when it succeeds (and 2, 4 or 5 when it does not), so its `if (num < 0)` does run `CreateAtis` — but `CreateAtis`
     * reads the map only when the campaign's weather model is already 3, and the button never sets it: with a campaign
     * on BMS's four types the ATIS was made again from the same tables. Its `.twx` branch, on the other hand, reads
     * Falcon BMS 4.38's version 8 file at the offsets of version 7 and took its model from the middle of the
     * probabilities. A file that cannot be read says why here, where WDP stayed silent.
     */
    /**
     * A save's flight (WDP's `OpenFile` → `ReadWeather` → `CreateAtis`): the save's own weather file (`<save>.twx` in the
     * campaign folder of the save's own theater — `@campaign:<theater>`, never the one BMS is set to — and the map it
     * flies with at the save's clock) is read as Reload WX reads a file, but quietly, at every Open mission and Pick a
     * flight. The PC checks the file is this save's (`save=`: `Auto Save.cam` and `Auto Save.tac` share one `.twx`);
     * one that is not, or that is not there, is not used, and the card says why. The ATIS, the weather list, Force QNH
     * and the Performance page's take-off weather then come from it where the card has no newer printed briefing of
     * the same flight ([weather]). A file read again with the same time, clock and map changes nothing.
     */
    private fun saveWeather(m: WdpMission) {
        saveWxPick = PlannerMissionState.changes
        val ref = m.ref
        val f = m.flight
        if (ref == null || f == null || m.briefing?.origin != "save" || ref.file.isBlank()) { saveWxPending = false; return }
        val file = ref.file.substringBeforeLast('.') + ".twx"
        val folder = campaignPlace(ref.theater)
        val token = ++saveWxSeq
        scope.launch {
            val a = runCatching { MissionLink.filesWeather("$folder\\$file", f.clock, ref.file) }
                .getOrElse { com.bmscompanion.app.data.mission.PcAnswer.unreachable(it.message ?: "error") }
            // the card may have moved on to another mission while the PC read the file, or asked again since (two reads
            // of the same flight close together on a slow link: the earlier answer must not land last)
            if (token != saveWxSeq) return@launch
            if (mission.ref != ref || mission.flight?.row?.id != f.row.id) return@launch
            val w = a.value?.takeIf { a.error == null }
            val was = saveKey()
            saveWxPending = false
            val other = w?.otherSave
            when {
                w == null -> { saveWx = null; saveWxName = null; saveWxWhy = a.error ?: "$file could not be read: the PC sent nothing." }
                other != null -> { saveWx = null; saveWxName = null; saveWxWhy = other }
                else -> {
                    saveWx = w
                    saveWxName = file + (w.map?.let { WdpFiles.nameOf(it) }?.takeIf { !it.equals(file, ignoreCase = true) }?.let { " (map $it)" } ?: "")
                    saveWxWhy = null
                }
            }
            // the same file as the card has (the same time, clock and map, or the same reason): nothing changes
            if (saveKey() == was) { version++; return@launch }
            weather(quiet = true)
            version++
            runCatching { weatherChanged?.invoke() }
        }
    }

    /** The last save's weather read asked for ([saveWeather]): only its answer is taken. */
    private var saveWxSeq = 0

    /** What the save's weather on the card is, to tell a file saved again (another time, clock or map) from the one read. */
    private fun saveKey(): String =
        saveWx?.let { "${it.path}|${it.modified}|${it.twx?.clock}|${it.twx?.condition}|${it.map}|${it.note}" } ?: "why:$saveWxWhy"

    /**
     * An Open mission or Pick a flight that gave the card the same mission again (the same flight, nothing the pages
     * read changed — a TE whose weather alone was saved with SAVE WTH is one): the save's own weather file is read
     * again all the same, so weather saved in BMS since reaches the card. [pick] is [PlannerMissionState.changes].
     */
    fun pickedAgain(pick: Int) {
        if (pick == saveWxPick || lastMission == null) return
        saveWeather(mission)
    }

    /** [briefingPrinted] has seen the printed briefing of this time (`briefingModified`) */
    private var printedSeen = -1L

    /**
     * BMS printed a briefing while a save's flight is on the card: the card joins it again ([cardMission]) — the same
     * flight's printed briefing brings its situation, comm ladder and weather, and the newer of it and the save's weather
     * file is the card's ([weather]). Filled again as for the same mission with new sources, so what the pilot typed
     * stays. [printed] is the briefing's time (`briefingModified`).
     */
    fun briefingPrinted(printed: Long) {
        if (printed == printedSeen) return
        val first = printedSeen < 0
        printedSeen = printed
        val m = lastMission ?: return
        if (first || m.flight == null || m.briefing?.origin != "save") return
        sourcesChanged = true
        onMission(m)
        runCatching { weatherChanged?.invoke() }
    }

    internal suspend fun reloadWx(): String? {
        val (dir, name) = weatherStart()
        val f = com.bmscompanion.app.data.PcFiles.open(
            "Load WX FMAP File", dir, com.bmscompanion.app.data.FileFilter.parse("FMAP(*.fmap)|*.fmap|TWX(*.twx)|*.twx"), name,
        ) ?: return null
        WdpFiles.remember("weather", f)
        val clock = mission.flight?.clock ?: mission.briefing?.takeIf { mission.flight == null }?.let { BriefedSave.known(BriefedSave.key(it))?.clock }
        val a = runCatching { MissionLink.filesWeather(f.path, clock) }
            .getOrElse { com.bmscompanion.app.data.mission.PcAnswer.unreachable(it.message ?: "error") }
        val w = a.value
        if (w == null || a.error != null) {
            val why = "${f.name} could not be read: ${a.error ?: "the PC sent nothing"}"
            WdpDialogs.message("Reload WX", why)
            return why
        }
        val mapName = w.map?.let { WdpFiles.nameOf(it) }?.takeIf { w.kind == "twx" }
        if (mission.theater == null || (mission.briefing == null && mission.flight == null)) {
            val why = "There is no mission on the card yet to place ${f.name}'s weather at: open one with Open mission…, or press PRINT on BMS's briefing screen."
            WdpDialogs.message("Reload WX", why)
            return why
        }
        fileWx = w
        fileWxName = f.name + (mapName?.let { " (map $it)" } ?: "")
        weather(quiet = true)
        if (fileWx == null || !plan.blnWth) {
            val why = "${f.name} gives no weather at the departure: the card could not place its field on the theater."
            fileWx = null; fileWxName = null
            WdpDialogs.message("Reload WX", why)
            return why
        }
        version++
        runCatching { weatherChanged?.invoke() }
        // a .twx whose map is not beside it: its own tables stand in, as WDP's do (the PC's note says which)
        val said = "The card's weather now comes from $fileWxName." + (w.note?.let { "\n$it" } ?: "") +
            "\n\n" + plan.text("lblAtis1") + "\n" + plan.text("lblAtis2")
        WdpDialogs.message("Reload WX", said)
        return said
    }

    /**
     * Where Reload WX's window opens (WDP: the folder of the campaign's weather file, `WeatherFile`, and its name): the
     * `.twx` of the save opened with Open mission… in its own theater's campaign folder, else the last weather file
     * opened, else the campaign folder of the theater BMS is set to.
     */
    private fun weatherStart(): Pair<String, String?> {
        mission.ref?.takeIf { it.file.isNotBlank() }?.let { r -> return campaignPlace(r.theater) to r.file.substringBeforeLast('.') + ".twx" }
        WdpFiles.last("weather")?.let { last -> return last.dropLast(WdpFiles.nameOf(last).length).trimEnd('\\', '/') to WdpFiles.nameOf(last) }
        return "@campaign" to null
    }

    /**
     * The campaign folder of [theater] (a save's, as its theater definition names it) as a place the PC resolves through
     * BMS's theater definitions (`@campaign:<theater>`), or the one BMS is set to (`@campaign`) when none is named.
     */
    private fun campaignPlace(theater: String?): String =
        theater?.trim()?.takeIf { it.isNotEmpty() }?.let { "@campaign:$it" } ?: "@campaign"

    private fun stripAgency(s: String): String =
        s.replace(Regex("\\s+(Tower|Ground|Approach|Departure|ATIS|Atis)$"), "").trim()

    /** "Copper2 (TCN: 059Y)" → "Copper2": the name BMS gives a ladder row, without the TACAN it prints after it. */
    private fun bareCallsign(s: String?): String? = s?.replace(Regex("\\s*\\((?:TCN|TACAN)[^)]*\\)"), "")?.trim()

    /**
     * `FillPackages`: the package's flights from the briefing's package table, in its order — our flight is
     * flight 0 of [flights] with its whole route, the others 1… with take-off, push and target only — and
     * `SelFltInPack` as the selected flight's place in it. Like WDP it sets the callsign, task, type, size, take-off
     * and the push/target steerpoints, and leaves the frequencies, IDM and TACAN a package entry already has.
     */
    private fun fillPackages(pkg: List<PackageFlight>, ownIdx: Int, flights: List<DataCardPlan.Flight>) {
        val p = plan
        var other = 1
        pkg.forEachIndexed { i, f ->
            val pk = p.packages.getOrNull(i) ?: return@forEachIndexed
            // WDP's `CallsignTable[callsign_id].Name + " " + callsign_num`: "Texaco 4", where the briefing prints "Texaco4"
            pk.callsign = wdpCallsign(f.callsign)
            pk.acNr = f.count ?: 1
            pk.acType = f.aircraft
            pk.task = f.role ?: f.task
            pk.f16 = false
            pk.fltNr = if (i == ownIdx) 0 else other++
            val route = flights.getOrNull(pk.fltNr)?.waypoints.orEmpty()
            pk.takeOffTime = route.firstOrNull()?.depart ?: 0L
            // the push is the last Push waypoint, the target the first action ≥ 9 (or a pick-up)
            for ((n, w) in route.withIndex()) {
                if (w.action == 2) pk.pushStpt = n
                if (w.action == 4) pk.targetStpt = n
                if (w.action == 6 || w.action >= 9) { pk.targetStpt = n; break }
            }
            if (pk.pushStpt == 0 && pk.targetStpt > 3) pk.pushStpt = pk.targetStpt - 1
            if (pk.fltNr == p.selFlightNr) p.selFltInPack = i + 1
        }
    }

    /** Tankers, AWACS and JSTARS from the app's support data, then WDP's fills. */
    private fun support(b: Briefing?, route: List<DataCardPlan.Waypoint>) {
        val p = plan
        supportTables(b, route)
        p.firstTanker = -1; p.secondTanker = -1
        if (p.tankerNR != 0) p.fillTanker()
        if (p.awacsNR != 0) p.fillAwacs()
        if (p.jstarNR != 0) p.fillJSTAR()
    }

    /**
     * The mission's support flights as the Briefing page lists them ([supportAssets]): the TACAN from the briefing
     * ("Copper2 (TCN: 059Y)"), or BMS's own order for tankers (92Y, 126Y, 125Y…, marked `*` as the Briefing page
     * marks it); the UHF from the comm ladder or the theater's radio plan by callsign; the location read from the
     * briefing's station sentence or the campaign's planned track. Worked out once per mission, in [load].
     */
    private var supportFlights: List<com.bmscompanion.app.ui.screens.mission.SupportAsset> = emptyList()
    /** each support flight's station in the briefing's words, by callsign ("21 nm SW of Yongin-si") */
    private val supportPhrases = HashMap<String, String>()

    private fun supportAssetsOf(b: Briefing?): List<com.bmscompanion.app.ui.screens.mission.SupportAsset> {
        val live = MissionLink.live.value
        val contacts = MissionLink.contacts.value?.contacts.orEmpty()
        // the ladder's "Copper2 (TCN: 059Y)" is Copper2, with its TACAN kept in the row's notes
        val clean = b?.copy(comms = b.comms.map { c ->
            val bare = bareCallsign(c.callsign)
            if (bare == c.callsign) c else c.copy(callsign = bare, notes = listOfNotNull(c.notes, c.callsign?.removePrefix(bare ?: "")?.trim()).joinToString(" "))
        })
        return runCatching { supportAssets(clean, live, contacts, sources.radio, bullseye(live, contacts), mission.dtc?.uhf.orEmpty()) }.getOrDefault(emptyList())
    }

    /**
     * `Tankers()`/`Awacs()`/`JSTAR()`: the tables. A support flight's LOC is what WDP put there, bullseye-relative
     * ("355/131"): the airborne aircraft's own position while the AWACS feed has it, else the station the campaign
     * planned or the briefing names, measured from the bullseye the sim gives — and blank while there is no bullseye
     * to measure from, as on the Briefing page. The Notes box carries the on-station window a planned track gives;
     * without one every flight counts as on station and Notes says where the briefing puts it ("21 nm southwest of
     * Yongin-si City"), as the Briefing page's notes do.
     */
    private fun supportTables(b: Briefing?, route: List<DataCardPlan.Waypoint>) {
        val p = plan
        val live = MissionLink.live.value
        // the sim's bullseye while BMS runs, else the campaign's own from the save header (WDP's: CampaignTable BullseyeX/Y)
        val bull = bullseye(live, MissionLink.contacts.value?.contacts) ?: saveBullseye()
        // the campaign's planned tracks the PC reads, then the tracks the save ties to this flight's package
        val tracks = MissionLink.bmsFiles.value?.tracks.orEmpty() +
            mission.flight?.support.orEmpty().map { SupportTrack(role = it.role, callsign = it.callsign, points = it.track) }
        supportPhrases.clear()
        cardTracks.clear()
        // a save's flight: WDP's own tables, every support flight of the flight's side in the save ([sideTables])
        mission.flight?.sideSupport?.takeIf { it.isNotEmpty() }?.let { side -> sideTables(side, bull, route); return }
        fun track(a: com.bmscompanion.app.ui.screens.mission.SupportAsset): DataCardPlan.Track {
            val entry = b?.support?.firstOrNull { it.callsign.equals(a.callsign, true) }
            val planned = tracks.firstOrNull { it.callsign?.replace(" ", "").equals(a.callsign.replace(" ", ""), true) }
            val onStation = planned?.points.orEmpty().filter { it.station }
            val at = onStation.takeIf { it.isNotEmpty() }?.let { s -> s.sumOf { it.x } / s.size to s.sumOf { it.y } / s.size }
                ?: stationFromNotes(entry?.notes ?: a.notes, sources::place)?.first
            stationPhrase(entry?.notes ?: a.notes).takeIf { it.isNotEmpty() }?.let { supportPhrases[a.callsign] = it }
            val loc = a.loc ?: if (bull != null && at != null) bra(bull.first, bull.second, at.first, at.second) else ""
            // the planned window, on the route's clock (a day on, and a window across midnight into the next day)
            var t1 = 0L
            var t2 = ALWAYS
            if (onStation.isNotEmpty()) {
                t1 = onStation.minOf { it.arriveMs }.mod(DAY) + DAY
                t2 = onStation.maxOf { it.departMs }.mod(DAY) + DAY
                if (t2 < t1) t2 += DAY
            }
            val tcn = a.tacan?.let { it + if (a.tacanDefault) "*" else "" }
            // the row reads "callsign - type"; a support flight a save names has no type there, and its role reads
            // better than a dangling dash ("Texaco4 - Tanker", not "Texaco4 - ")
            val type = a.aircraft?.replace(Regex("^\\d+\\s+"), "")?.takeIf { it.isNotBlank() } ?: a.role.takeIf { it.isNotBlank() }
            return DataCardPlan.Track(a.callsign, type, tcn, loc, t1, t2, tcnFinal = true)
        }
        val tankers = supportFlights.filter { it.role == "Tanker" }.map(::track)
        val awacs = supportFlights.filter { it.role == "AWACS" }.map(::track)
        val jstar = supportFlights.filter { it.role == "JSTARS" }.map(::track)
        p.tblTankerTrack = tankers; p.tankerNR = tankers.size
        p.tblAwacsTrack = awacs; p.awacsNR = awacs.size
        p.tblJSTARTrack = jstar; p.jstarNR = jstar.size
        p.selFlightArrStpt = (route.indexOfFirst { it.action == 7 }).let { if (it < 0) route.size - 1 else it }.coerceAtLeast(0)
    }

    /**
     * WDP's support tables for a save's flight ([com.bmscompanion.app.data.mission.CampFlight.sideSupport];
     * `fclsMain.Tankers`, `Awacs`, `JSTAR`): every tanker, AWACS and JSTARS of the flight's side in the save, in the
     * save's order, so the card's two tanker rows offer the choice WDP's lists offer (`FillTanker`: the tankers on
     * station while the flight is up, the first two of the table in the rows). Each has WDP's station — the leg from
     * its first tanker (24) or ELINT (20) waypoint to the next — its window (arriving at the one, leaving the other),
     * its vehicle, and the TACAN the save gives it: the tanker's own, which the card turns into the receiver's as WDP
     * does (123Y → 60Y, the channel BMS's briefing prints). A tanker on station is drawn on the map as WDP's main map
     * draws it, and a JSTARS on station as its mark ([cardTracks]).
     *
     * **Fixed (D63):** the LOC is the bearing and range from the save's bullseye to the middle of the station leg.
     * WDP measures a tanker's with north and east exchanged at the station's end (`Tankers`: the bullseye as north,
     * east and the station as east, north) and an AWACS's or JSTARS's to a corner of its box, 30,000 ft off the leg in
     * a direction taken from the sine of an angle in degrees (`Awacs`, `JSTAR`: `Math.Sin(num4)` of `P.Track`'s
     * degrees). The figures on the last save are in docs/WDP-PORT.md.
     */
    private fun sideTables(side: List<com.bmscompanion.app.data.mission.CampSupport>, bull: Pair<Double, Double>?, route: List<DataCardPlan.Waypoint>) {
        val p = plan
        val tankers = ArrayList<DataCardPlan.Track>()
        val awacs = ArrayList<DataCardPlan.Track>()
        val jstar = ArrayList<DataCardPlan.Track>()
        val arrIdx = (route.indexOfFirst { it.action == 7 }).let { if (it < 0) route.size - 1 else it }.coerceAtLeast(0)
        val depart = route.firstOrNull()?.depart ?: 0L
        val arrive = route.getOrNull(arrIdx)?.arrive ?: 0L
        // the save's own clock, a day on as the route is laid ([saveWaypoint]): WDP compares the save's times as they
        // are, so on a campaign's third day a first-day tanker is not taken for one on station
        val clock = mission.flight?.clock?.let { it + DAY }
        for (s in side) {
            val pts = s.track
            val i = s.leg.takeIf { it in pts.indices } ?: pts.indexOfFirst { it.station }.takeIf { it >= 0 } ?: continue
            val a = pts[i]
            val b = pts.getOrNull(i + 1) ?: a
            // the window on the route's clock: the save's own times, a day on as the route's are ([saveWaypoint])
            val t1 = a.arriveMs + DAY
            val t2 = b.departMs + DAY
            val mid = (a.x + b.x) / 2 to (a.y + b.y) / 2
            val t = DataCardPlan.Track(s.callsign, s.aircraft ?: s.role, s.tacan, bull?.let { locOf(it, mid) } ?: "", t1, t2, tcnFinal = false)
            when (s.role) {
                "Tanker" -> {
                    tankers += t
                    // DrawMapTankerTracks: a tanker on station while the flight is up (Time1 < arrival, Time2 > departure)
                    if (t1 < arrive && t2 > depart) cardTracks += stationBox(a.x to a.y, b.x to b.y, s.callsign + "/" + tacanOf(s.tacan) + "\n" + hhmm(t1) + " - " + hhmm(t2))
                }
                "AWACS" -> awacs += t
                "JSTARS" -> {
                    jstar += t
                    // DrawMapJstarTracks: on station at the campaign's clock
                    if (clock != null && t1 < clock && t2 > clock) cardTracks += CardTrack(emptyList(), mid, "", jstars = true)
                }
            }
        }
        p.tblTankerTrack = tankers; p.tankerNR = tankers.size
        p.tblAwacsTrack = awacs; p.awacsNR = awacs.size
        p.tblJSTARTrack = jstar; p.jstarNR = jstar.size
        p.selFlightArrStpt = arrIdx
    }

    /** The support stations drawn on the card's map ([sideTables]), in theater feet. */
    private val cardTracks = ArrayList<CardTrack>()

    /** How the pilot has zoomed and panned the card's map ([CardMap]); a new mission fits it again. */
    private val mapView = CardMapView()

    /** WDP's tanker box: 30,000 ft either side of the leg from [a] to [b] (north, east feet), its middle and its label. */
    private fun stationBox(a: Pair<Double, Double>, b: Pair<Double, Double>, label: String): CardTrack {
        val dn = b.first - a.first
        val de = b.second - a.second
        val len = hypot(dn, de).takeIf { it > 0.0 }
        // the leg's perpendicular; a leg of no length (one point) stands north-south, as WDP's Track of 0 does
        val pn = if (len == null) 0.0 else -de / len * TRACK_HALF_FT
        val pe = if (len == null) TRACK_HALF_FT else dn / len * TRACK_HALF_FT
        val corners = listOf(
            a.first + pn to a.second + pe, a.first - pn to a.second - pe, b.first - pn to b.second - pe, b.first + pn to b.second + pe,
        )
        return CardTrack(corners, (a.first + b.first) / 2 to (a.second + b.second) / 2, label)
    }

    /** A tanker's own TACAN as the receiver dials it, WDP's Y-band shift ("123Y" to "60Y"); "" for none. */
    private fun tacanOf(tcn: String?): String {
        val n = tcn?.dropLast(1)?.toIntOrNull() ?: return ""
        return (if (n < 64) n + 63 else n - 63).toString() + "Y"
    }

    /** "06:49" of a time on the route's clock. */
    private fun hhmm(t: Long): String {
        val m = (t.mod(DAY) / 60_000L).toInt()
        return (m / 60).toString().padStart(2, '0') + ":" + (m % 60).toString().padStart(2, '0')
    }

    /** WDP's LOC: "108 / 23", the bearing (1-360) and the range in whole nautical miles from [from] to [to] (north, east feet). */
    private fun locOf(from: Pair<Double, Double>, to: Pair<Double, Double>): String {
        val dn = to.first - from.first
        val de = to.second - from.second
        if (dn == 0.0 && de == 0.0) return ""
        var brg = (kotlin.math.atan2(de, dn) * 180.0 / kotlin.math.PI).roundToInt()
        if (brg <= 0) brg += 360
        val nm = (kotlin.math.round(hypot(dn, de) / NM_FT * 10.0) / 10.0).roundToInt()
        return "$brg / $nm"
    }

    /**
     * "… will be orbiting 20 nm northeast of Larissa. Available …" → "20 nm NE of Larissa": the station as a pilot
     * writes it on a card, so it fits the Notes box (142 px, some 26 characters) — "21 nm southwest of Yongin-si City"
     * was cut off at "Yongin-". The compass word becomes its letters and a trailing " City" goes.
     */
    private fun stationPhrase(notes: String?): String {
        val n = notes ?: return ""
        val m = Regex("(\\d+)\\s*nm\\s+(\\w+)\\s+of\\s+([^.]+)", RegexOption.IGNORE_CASE).find(n) ?: return ""
        val dir = COMPASS[m.groupValues[2].lowercase()] ?: m.groupValues[2]
        val place = m.groupValues[3].trim().replace(Regex("\\s+City$", RegexOption.IGNORE_CASE), "")
        return "${m.groupValues[1]} nm $dir of $place"
    }

    /**
     * The chosen attack page's offsets and figures into the card, as WDP's pages hand them to `fclsMain.*NavOffsets`
     * and `cntDTC` on every change (`SaveNavOffsets`, `DTC()`), where `Profiles()` and `FillAttackType` read them.
     */
    private fun takeAttack(profile: String) {
        val (popup, hadb, toss) = attackPages?.invoke() ?: return
        val p = plan
        val a = p.attack
        when (profile) {
            "PopUp" -> popup.toCard(a, p.popUpNavOffsets)
            "HADB" -> hadb.toCard(a, p.hadbNavOffsets)
            "TOSS" -> {
                // cntTOSS's own fields, which the card reads directly
                a.tossRef = toss.ref; a.tossAttackHeadingDeg = toss.attackHeadingDeg
                a.tossIngressHeight = toss.ingressHeight; a.tossIngressCAS = toss.ingressCas
                a.tossReleaseHeight = toss.releaseHeight; a.tossReleaseCAS = toss.releaseCas; a.tossReleaseAngleDeg = toss.releaseAngleDeg
                a.tossPullingGs = toss.pullingGs; a.tossTurn = toss.turnDirection; a.tossTargetHud = toss.labels["lblTargetHUDval"] ?: ""
                p.tossNavOffsets.modesel = toss.navModesel
                toss.navOffsets().values.forEachIndexed { i, o ->
                    p.tossNavOffsets.points.getOrNull(i)?.let { it.stpt = o.stpt; it.bearing = o.bearing; it.range = o.range; it.elv = o.elv }
                }
            }
        }
    }

    /**
     * The card shown again after the pilot worked on an attack page: WDP's pages update the card as they go, so the
     * profile it has chosen is read afresh.
     */
    fun refreshAttack() {
        val profile = plan.attack.strProfile
        if (profile != "PopUp" && profile != "HADB" && profile != "TOSS") return
        try { takeAttack(profile); plan.profiles() } catch (e: Exception) { /* the card keeps what it had, as WDP's error box lets it */ }
        version++
    }

    /**
     * An attack page's **Save to DTC** has written the cartridge ("PopUp", "HADB" or "TOSS"; D87, 1.3.8 — it took over
     * the test builds' Send to DataCard): the Delivery block becomes that page's attack, every field, exactly as
     * [useAttack] fills it, but nothing is left waiting for the cartridge — it holds these offsets already, so the card's
     * count and lamp stay clear and the card and the cartridge agree. From then on the card follows that page
     * ([refreshAttack]), and a later change there counts again for its Save DTC (`cardEntries`' following).
     */
    fun attackSaved(profile: String) {
        takeAttack(profile)
        plan.profileButton(profile)
        profileButtons = false
        profileChosen = null
        AttackFocus.pageOf(profile)?.let { AttackFocus.apply(it) }
        runCatching { plan.fillAttackType() }
        version++
    }

    /**
     * The card's own PopUp / HADB / TOSS button pressed for [profile] (`btnPopUp_Click` …; D87): the page's figures and
     * offsets into the Delivery block, the profile chosen for Save DTC, the buttons put away. From then on the card
     * follows that page ([refreshAttack]), as WDP's does.
     */
    fun useAttack(profile: String) {
        takeAttack(profile)
        // the Delivery profile becomes that page's (txtWpn_AttackType), every Delivery field filled (FillAttackType)
        plan.profileButton(profile)
        profileButtons = false
        profileChosen = profile
        // the page's offsets onto the DTC page now, not saved (1.3.8): the same path its own Save to DTC takes, so the
        // card's Save DTC writes byte for byte what the page's would ([cardOffsets], DtcWiring.stageNavOffsets)
        cardOffsets(profile)?.let { (offsets, sel) -> runCatching { WdpCartridge.stageNavOffsets?.invoke(profile, offsets, sel) } }
        AttackFocus.pageOf(profile)?.let { AttackFocus.apply(it) }
        runCatching { plan.fillAttackType() }
        version++
    }

    /**
     * The card's attack for [profile] as the cartridge would take it: the four nav-offset lines of its mode (VIP: VIP,
     * VIPPUP, OA1_1, OA2_1; VRP: the VRP four) as that page has them now — what the card shows, since it follows its
     * profile's page ([refreshAttack], as WDP's pages update the card as they go) — else the card's own copy
     * (`popUpNavOffsets` …, which [takeAttack] fills), and the mode. Null for "None" or a profile the card has no
     * offsets for. (The copy alone is refreshed only while the card shows: the toolbar's Save to DTC pressed on the
     * attack page after its own Save to DTC handed on the copy taken when the card took the attack, and put the older attack back.)
     */
    private fun cardOffsets(profile: String?): Pair<Map<String, WdpCartridge.NavOffset>, Int>? {
        val src = when (profile) { "PopUp" -> plan.popUpNavOffsets; "HADB" -> plan.hadbNavOffsets; "TOSS" -> plan.tossNavOffsets; else -> return null }
        val keys = listOf("VIP", "VIPPUP", "VRP", "VRPPUP", "OA1_1", "OA2_1", "OA1_2", "OA2_2")
        val live: Pair<Map<String, WdpCartridge.NavOffset>, Int>? = runCatching {
            attackPages?.invoke()?.let { (popup, hadb, toss) ->
                when (profile) {
                    "PopUp" -> popup.navOffsets.mapValues { (_, o) -> WdpCartridge.NavOffset(o.stpt, o.bearing, o.range, o.elv) } to popup.navModesel
                    "HADB" -> hadb.navOffsets.mapValues { (_, o) -> WdpCartridge.NavOffset(o.stpt, o.bearing, o.range, o.elv) } to hadb.navModesel
                    else -> toss.navOffsets().mapValues { (_, o) -> WdpCartridge.NavOffset(o.stpt, o.bearing, o.range, o.elv) } to toss.navModesel
                }
            }
        }.getOrNull()?.takeIf { (o, sel) -> (sel == 1 || sel == 2) && keys.all { it in o } }
        val all = LinkedHashMap<String, WdpCartridge.NavOffset>()
        val sel = if (live != null) {
            for (k in keys) all[k] = live.first.getValue(k)
            live.second
        } else {
            keys.forEachIndexed { i, k -> src.points[i].let { o -> all[k] = WdpCartridge.NavOffset(o.stpt, o.bearing, o.range, o.elv) } }
            src.modesel.takeIf { it == 1 || it == 2 } ?: return null
        }
        return com.bmscompanion.app.data.wdp.AttackGeometry.selectedOffsets(all, sel == 1) to sel
    }

    /**
     * Whether the cartridge as last read or saved ([m]) holds [offsets] in mode [sel] (1 VIP, 2 VRP): the mode and its
     * four lines, each by steerpoint, bearing (0.1°), range and ELEV.
     */
    private fun cartridgeHolds(m: com.bmscompanion.app.data.wdp.DtcModel, offsets: Map<String, WdpCartridge.NavOffset>, sel: Int): Boolean {
        val nv = m.nav
        if (nv.modesel != sel) return false
        val lines = if (sel == 1) listOf("VIP" to nv.vip, "VIPPUP" to nv.vipPup, "OA1_1" to nv.oa1_1, "OA2_1" to nv.oa2_1)
        else listOf("VRP" to nv.vrp, "VRPPUP" to nv.vrpPup, "OA1_2" to nv.oa1_2, "OA2_2" to nv.oa2_2)
        return lines.all { (k, d) ->
            val o = offsets[k] ?: return@all d.stpt < 1 || d.range == 0
            o.stpt == d.stpt && kotlin.math.abs(o.bearing - d.bearing) < 0.05f && o.rangeFt == d.range && o.elevFt == d.elv
        }
    }

    /**
     * picReformPlan_Click …: WDP opens a picture file here, in its `DataCards\PlanPic` folder on `ReformPlan.jpg`
     * (`HoldingPlan.jpg`, `IngressPlan.jpg`, `TargetPlan.jpg`) — its own plan templates or one of the pilot's. **Picture
     * file…** is that window, on the BMS PC; the app also offers WDP's templates for this box (the holding pattern
     * either way round, the target area flow air-to-air or air-to-ground) and the Planner's attack maps as they are now
     * (what WDP's Save Map writes for this). Clear empties the box.
     *
     * A click on the box opens that window straight away, as WDP's does ([planPictureFile]); this choice is the box's
     * right button (a long press on a touch screen).
     */
    private fun planPicture(name: String) {
        val templates = when (name) {
            "picHoldingPlan" -> listOf("CW" to TEMPLATE_HOLD_CW, "CCW" to TEMPLATE_HOLD_CCW)
            "picTargetPlan" -> listOf("Air-to-air" to TEMPLATE_TARGET_AA, "Air-to-ground" to TEMPLATE_TARGET_AG)
            else -> emptyList()
        }
        fun attackMap(answer: String) {
            val pages = attackPages?.invoke()
            val pic = runCatching {
                when (answer) {
                    "Pop-up" -> pages?.first?.let { p -> p.picture(false)?.withAttack(WdpAttackOverlay.ofPlan(p)) }
                    "HADB" -> pages?.second?.let { p -> p.mapPicture().withAttack(WdpAttackOverlay.ofPlan(p)) }
                    "TOSS" -> pages?.third?.let { p -> p.mapPicture().withAttack(WdpAttackOverlay.ofPlan(p)) }
                    else -> null
                }
            }.getOrNull()
            if (pic == null) WdpDialogs.message(
                "Load a plan picture",
                "The $answer page has no attack map yet: set that page's TGT STPT to a steerpoint with a position and look at the " +
                    "page once, then pick it here again.",
            ) else planPictures[name] = pic
            version++
        }
        val attack = listOf("Pop-up", "HADB", "TOSS")
        val text = if (templates.isEmpty())
            "A click on the box opens a picture file, as in WDP. The app can also put one of the Planner's attack maps here, as it is now: the Pop-up, HADB or TOSS page's. Clear empties the box."
        else "A click on the box opens a picture file, as in WDP: its own plan templates, or one of yours. The app also offers WDP's " +
            (if (name == "picHoldingPlan") "holding pattern, clockwise or counter-clockwise" else "target area flow, air-to-air or air-to-ground") +
            ", or one of the Planner's attack maps as it is now (Attack map). Clear empties the box."
        WdpDialogs.message("Load a plan picture", text, listOf(PICTURE_FILE) + (if (templates.isEmpty()) attack else templates.map { it.first } + "Attack map") + listOf("Clear", "Cancel")) { answer ->
            when {
                answer == "Cancel" -> {}
                answer == PICTURE_FILE -> scope.launch { planPictureFile(name); version++ }
                answer == "Clear" -> { planPictures.remove(name); planImages.remove(name); planTemplates[name] = "" }
                answer == "Attack map" -> WdpDialogs.message("Load a plan picture", "Which page's attack map?", attack + "Cancel") { a -> if (a != "Cancel") attackMap(a) }
                templates.any { it.first == answer } -> {
                    planPictures.remove(name); planImages.remove(name); planTemplates[name] = templates.first { it.first == answer }.second
                }
                else -> attackMap(answer)
            }
            version++
        }
    }

    /**
     * WDP's `OpenFileDialog` for a plan box (`picReformPlan_Click` …), which a click on the box opens straight away:
     * no title of its own (Windows' "Open"), no file types (every file listed), `DataCards\PlanPic`, on the box's own
     * picture (`ReformPlan.jpg` …; the reform and holding plans only when that file is there, as WDP checks), and the
     * picture picked drawn in the box, fitted whole as WDP's `LoadReformPlan` scales it.
     */
    internal suspend fun planPictureFile(name: String): String? {
        val file = name.removePrefix("pic") + ".jpg"
        val named = if (name != "picReformPlan" && name != "picHoldingPlan") file
        else file.takeIf { runCatching { MissionLink.filesStat(WdpFiles.PLAN_PICS + "\\" + it).value?.let { e -> e.exists && !e.dir } }.getOrNull() == true }
        val f = WdpFiles.open("Open", WdpFiles.PLAN_PICS, "", named) ?: return null
        val a = f.read()
        val bytes = a.value
        if (bytes == null || a.error != null) {
            WdpDialogs.message("Load a plan picture", "${f.name} could not be read: ${a.error ?: "the PC sent nothing"}")
            return null
        }
        val img = com.bmscompanion.app.data.Platform.decodeImage?.invoke(bytes)
        if (img == null) {
            WdpDialogs.message("Load a plan picture", "${f.name} is not a picture this device can show.")
            return null
        }
        planPictures.remove(name)
        planImages[name] = img
        version++
        return f.path
    }

    /**
     * A finger held still on plan box [n] (a touch screen or a pen, not a mouse): the app's own pictures for it
     * ([planPicture]), as the right button gives them; the rest of that touch is taken, so its lift is not also the
     * box's click. A tap, a drag and anything a mouse does pass through untouched.
     */
    private fun longPressMenu(n: String): androidx.compose.ui.Modifier = androidx.compose.ui.Modifier.pointerInput(n) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Touch && down.type != PointerType.Stylus) return@awaitEachGesture
            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
            }
            if (ended != null) return@awaitEachGesture
            planPicture(n)
            version++
            do {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                e.changes.forEach { it.consume() }
            } while (e.changes.any { it.pressed })
        }
    }

    /** The template a plan box opens with, as WDP's card loads HoldingPlan.jpg and TargetPlan.jpg: the target area flow air-to-air for a fighter sweep or CAP. */
    private fun defaultTemplate(box: String): String {
        val role = (mission.briefing?.let { b -> b.`package`.firstOrNull { it.callsign.equals(b.overview.flight, true) }?.role } ?: mission.briefing?.overview?.mission).orEmpty()
        val airToAir = !Regex("SEAD|DEAD", RegexOption.IGNORE_CASE).containsMatchIn(role) &&
            Regex("CAP|SWEEP|ESCORT|INTERCEPT|ALERT", RegexOption.IGNORE_CASE).containsMatchIn(role)
        return when (box) {
            "picHoldingPlan" -> TEMPLATE_HOLD_CW
            "picTargetPlan" -> if (airToAir) TEMPLATE_TARGET_AA else TEMPLATE_TARGET_AG
            else -> ""
        }
    }

    /** picMap: what FillMap places there — the chosen attack profile's drawing, or the flight's stretch of the map. */
    override fun controlContent(): Map<String, @Composable () -> Unit> = PLAN_PICTURES.associateWith<String, @Composable () -> Unit> { n ->
        {
            @Suppress("UNUSED_VARIABLE") val v = version
            // a long press on a touch screen is the box's right button (a mouse's reaches onClick as "<box>:right"):
            // the app's own pictures for the box ([planPicture]); a tap stays the box's click, the picture file
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().then(longPressMenu(n))) {
                // a picture file the pilot opened: fitted into the box whole, as LoadTargetPlan scales it keeping its shape
                planImages[n]?.let { img ->
                    androidx.compose.foundation.Image(
                        img, null, androidx.compose.ui.Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
                // fitted into the box whole, as LoadTargetPlan scales the picture to the box keeping its shape
                if (planImages[n] == null) planPictures[n]?.let { pic ->
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxHeight().aspectRatio(1f)) { WdpMapPicture(mission.theater, pic) }
                    }
                }
            }
        }
    } + mapOf("picMap" to {
        @Suppress("UNUSED_VARIABLE") val v = version   // drawn again after every change
        @Suppress("UNUSED_VARIABLE") val f = AttackFocus.stamp
        // the card's own profile (its map and Delivery block are one printed record), drawn as every map draws an
        // attack, in the card's white map when it is on (B2: Pop-up's too)
        val profile = plan.attack.strProfile
        val page = AttackFocus.pageOf(profile)
        val attack = page?.let { p ->
            runCatching {
                attackPages?.invoke()?.let { (popup, hadb, toss) ->
                    val w = when (p) { WdpPage.POPUP -> popup; WdpPage.HADB -> hadb; else -> toss }
                    // the plans the card was given (a check may hand its own), each as its page draws it
                    when (w) {
                        is PopupPlan -> w.picture(whiteMap)?.withAttack(WdpAttackOverlay.ofPlan(w))
                        is HadbPlan -> w.mapPicture(whiteMap).withAttack(WdpAttackOverlay.ofPlan(w))
                        is TossPlan -> w.mapPicture(whiteMap).withAttack(WdpAttackOverlay.ofPlan(w))
                        else -> null
                    }
                }
            }.getOrNull()
        }
        // with nothing to draw the box stays as WDP leaves an empty picture box; the map can be zoomed and panned, and
        // the support stations are on it with the route, as on WDP's main map
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            CardMap(mission.theater, attack ?: routePicture(), whiteMap, if (attack == null) cardTracks.toList() else emptyList(), mapView)
            // the Planner's latest attack is another page's: one line says so (the card keeps its own, AttackFocus) — on
            // the screen only, never on the kneeboard page Upd Kneeboard prints of the card (its window says it there)
            if (!wdpOnPaper) AttackFocus.cardNotice(profile)?.let { notice ->
                androidx.compose.material3.Text(
                    notice,
                    androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopStart).fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Color(0xE6FFF3CD)).padding(horizontal = 3.dp, vertical = 1.dp),
                    color = androidx.compose.ui.graphics.Color(0xFF6B4E00), fontSize = 7.5.sp, lineHeight = 9.sp, maxLines = 2,
                )
            }
        }
    })

    /**
     * What FillMap takes from WDP's main map when no attack profile is chosen (`CreateSaveMap`): the stretch of the
     * theater the flight flies over, with its route, targets and threats. WDP crops its own map view there; here the
     * crop is the route's extent, square, with a margin, and the marks are placed in picMap's own 250 pixels. Only our
     * flight's steerpoints are known (the cartridge's), and the card never shows another flight (D86).
     */
    private fun routePicture(focus: com.bmscompanion.app.data.mission.DtcPoint? = null): PopupPlan.MapPicture? {
        val pts = mission.steerpoints.filter { it.x != 0.0 || it.y != 0.0 }.sortedBy { it.n }
        if (pts.isEmpty()) return null
        val targets = mission.choices
        // the tankers on station belong in the picture too, as WDP's main map shows them round the route
        val boxes = if (focus == null) cardTracks.flatMap { it.corners } else emptyList()
        val ns = pts.map { it.x } + targets.map { it.target.first } + boxes.map { it.first }
        val es = pts.map { it.y } + targets.map { it.target.second } + boxes.map { it.second }
        val nm = 6076.12
        // a steerpoint looked at on its own (a tap on its row): 40 nm around it, the rest of the route running off the edges
        val span = if (focus != null) 40 * nm else maxOf(ns.max() - ns.min(), es.max() - es.min(), 20 * nm) * 1.2
        val upperN = (focus?.x ?: ((ns.max() + ns.min()) / 2)) + span / 2
        val upperE = (focus?.y ?: ((es.max() + es.min()) / 2)) - span / 2
        val size = 250
        val k = size / span
        fun at(n: Double, e: Double) = ((e - upperE) * k).roundToInt() to ((upperN - n) * k).roundToInt()
        val items = ArrayList<PopupPlan.MapItem>()
        // the threats under the route, each ring at its own radius
        for (t in mission.dtc?.ppts.orEmpty()) {
            if ((t.x == 0.0 && t.y == 0.0) || t.rangeNm <= 0.0) continue
            val (x, y) = at(t.x, t.y)
            val r = (t.rangeNm * nm * k).roundToInt()
            items += PopupPlan.MapItem.Ellipse(x - r, y - r, 2 * r, 2 * r, "Red")
        }
        // the route: the flight plan's own points, a leg joining consecutive ones only. The cartridge's target
        // steerpoints (BMS's Recon, the pilot's marks: STPT 15-22 and on) are not legs of it: they stand alone.
        // WDP's DrawMapStpt: landing to alternate (Land to Land) is dashed, and no leg joins a Land and a Refuel —
        // the tanker's steerpoint is where to find it, not where the flight goes after landing
        val route = pts.filter { !it.isTarget }
        for (i in 1 until route.size) if (route[i].n == route[i - 1].n + 1) {
            val a0 = route[i - 1].action
            val a1 = route[i].action
            if ((a0 == LAND && a1 == REFUEL) || (a0 == REFUEL && a1 == LAND)) continue
            val (x1, y1) = at(route[i - 1].x, route[i - 1].y)
            val (x2, y2) = at(route[i].x, route[i].y)
            items += PopupPlan.MapItem.Line(x1, y1, x2, y2, "White", if (a0 == LAND && a1 == LAND) "Dash" else "Solid")
        }
        // steerpoints at one place (take-off, landing and alternate at the same field) share one label, "1/6/8",
        // where their numbers written one over another read as "28"
        val labels = ArrayList<Pair<Pair<Int, Int>, MutableList<String>>>()
        fun label(x: Int, y: Int, s: String) {
            val near = labels.firstOrNull { (at0, _) -> kotlin.math.abs(at0.first - x) <= 6 && kotlin.math.abs(at0.second - y) <= 6 }
            if (near != null) { if (s !in near.second) near.second += s } else labels += (x to y) to mutableListOf(s)
        }
        // WDP's marks: a square at take-off (STPT 1) and at the IP, a triangle at each task point after it, a circle
        // elsewhere. The IP is the attack maps' one rule (B5, AttackDrawing.routeIp): the point before the first strike
        // action (14, 15, 17, 18) — the card's own picture once said any task action, 9-25, and so squared a CAP's
        val routeIp = com.bmscompanion.app.data.mission.AttackDrawing.routeIp(mission.steerpoints.map { it.n to it.action })
        var ipDone = false
        for (p in route) {
            val (x, y) = at(p.x, p.y)
            when {
                p.n == 1 -> items += PopupPlan.MapItem.Rect(x - 3, y - 3, 6, 6, "White")
                !ipDone && p.n == routeIp -> { items += PopupPlan.MapItem.Rect(x - 3, y - 3, 6, 6, "White"); ipDone = true }
                ipDone && p.action in TASK_ACTIONS -> items += PopupPlan.MapItem.Polygon(listOf(x to y - 5, x + 4 to y + 3, x - 4 to y + 3), "White")
                else -> items += PopupPlan.MapItem.Ellipse(x - 3, y - 3, 6, 6, "White")
            }
            label(x, y, p.n.toString())
        }
        for (t in targets) {
            val (x, y) = at(t.target.first, t.target.second)
            items += PopupPlan.MapItem.Rect(x - 4, y - 4, 8, 8, "Yellow")
            if (t.key.startsWith("STPT ")) label(x, y, t.waypoint.toString())
        }
        focus?.let { f -> val (x, y) = at(f.x, f.y); items += PopupPlan.MapItem.Ellipse(x - 12, y - 12, 24, 24, "Yellow") }
        // a label kept inside the picture: to the left of a point near the right edge, under one near the top
        for ((xy, ns) in labels) {
            val text = ns.joinToString("/")
            val w = text.length * 5
            val tx = if (xy.first + 4 + w > size) xy.first - 4 - w else xy.first + 4
            val ty = if (xy.second - 15 < 0) xy.second + 4 else xy.second - 15
            items += PopupPlan.MapItem.Text(text, tx, ty, "White", "Arial", 8)
        }
        return PopupPlan.MapPicture(intArrayOf(0, 0, size, size), size, items, upperN, upperE, span)
    }

    /**
     * A flight plan row tapped (`lblActionN_DoubleClick` → `ShowTarget`). WDP shows the steerpoint's target objective
     * and its buildings from the campaign; the app shows the steerpoint itself: where it is on the theater map (40 nm
     * around it, the route and the threat rings on the app's own map), its time, altitude, heading and distance, its
     * position, and the target the save or the cartridge ties to it. An empty row does nothing, as in WDP.
     */
    private fun steerpointInfo(k: Int) {
        val p = plan
        val action = p.text("lblAction$k").trim()
        if (action.isEmpty()) return
        val own = p.selFlightNr == 0
        val pt = if (own) mission.steerpoints.firstOrNull { it.n == k && (it.x != 0.0 || it.y != 0.0) } else null
        val camp = if (own) mission.flight?.route?.firstOrNull { it.n == k } else null
        val row = if (own) mission.briefing?.steerpoints?.firstOrNull { it.n == k } else null
        fun v(n: String) = p.text(n).trim().takeIf { it.isNotEmpty() && it != "---" && !it.all { c -> c == '-' } }
        val lines = ArrayList<String>()
        lines += "STPT $k  $action" + (row?.desc?.takeIf { it.isNotBlank() && !it.equals(action, true) }?.let { " ($it)" } ?: "")
        // one fact a line, in the window's fixed-width lines (some 52 characters across its 450 px)
        fun add(label: String, value: String?) { if (value != null) lines += label.padEnd(10) + value }
        add("Time", v("lblTOS$k"))
        add("Altitude", v("lblAlt$k")?.let { "$it ft" })
        add("Heading", v("lblHdg$k")?.let { "$it°" + (v("lblDist$k")?.let { d -> "   $d nm from STPT ${k - 1}" } ?: "") })
        add("Mach/GS", v("txtKias$k"))
        runCatching { p.latLon(k - 1) }.getOrNull()?.let { (lat, lon) -> if (lat != null) add("Position", "$lat  ${lon ?: ""}") }
        val target = camp?.designated?.getOrNull(cardSeat)?.name ?: camp?.target?.name ?: pt?.name?.takeIf { it.isNotBlank() && !it.equals(action, true) }
        target?.let { add("Target", it + (camp?.designated?.getOrNull(cardSeat)?.building?.let { b -> " (building $b)" } ?: "")) }
        add("Formation", row?.formation?.takeIf { it.isNotBlank() })
        row?.comments?.takeIf { it.isNotBlank() && it != "--" }?.let { lines += ""; lines += wrapText("BMS: $it", 52) }
        if (pt == null) { lines += ""; lines += wrapText(if (own) "The mission gives no position for this steerpoint, so it is not on the map." else
            "The briefing gives another flight's times only, not its route: its steerpoints are not on the map.", 52) }
        val pic = pt?.let { runCatching { routePicture(it) }.getOrNull() }
        SteerpointWindow("STPT $k · $action", lines.joinToString("\n"), pic, mission.theater).open()
    }

    /**
     * The target boxes, by WDP's rule (`FillPriTarget`/`FillSecTarget` from `CreateFlightplan`): the first and second
     * strike steerpoints, named by the target designated to the card's seat when a save's flight is planned, else the
     * mission's first two flight-plan targets ([WdpMission.cardTargets]). They no longer follow a pick.
     */
    private fun targets(mission: WdpMission) {
        val (pri, sec) = mission.cardTargets(cardSeat)
        fun put(suffix: String, t: WdpTarget?) {
            plan.type("txtTGT_$suffix", t?.label ?: "")
            plan.type("txtDMPI_$suffix", "")
            // WDP prints the target's latitude and longitude ("N" + GetNorthDeg + " E" + GetEastDeg, cntDTC's target
            // search), with the hemisphere as a letter, so the Falklands read "S51,49.468 W058,25.939" rather than
            // WDP's "N-51,49.468 E-058,25.939"; without a theater there is no projection, so the feet stand in for them
            val c = mission.coords
            plan.type("txtLatLong_$suffix", t?.let {
                if (c != null) PopupCoords.hemispheres(PopupCoords.feetToCoordsBoth(c, it.target.first, it.target.second)).let { (n, e) -> "$n $e" }
                else "N " + kft(it.target.first) + " / E " + kft(it.target.second) + " - " + it.target.third.toInt() + "'"
            } ?: "")
        }
        put("Pri", pri)
        put("Sec", sec)
    }

    /**
     * The Briefing page, laid out by WDP's own logic from the briefing: the printed one, or the one the PC made of a
     * save's flight — joined with the printed one where that is the same flight ([cardMission]). For a save's flight
     * BMS has printed no briefing for, the situation is the one the PC worded from the save (a campaign's as BMS's
     * scripts word it, a TE's from its team's motto, as WDP shows it) and the Intel lines are WDP's own lists of the
     * save's hostile units ([CampIntel]); only what the save cannot give is said to be in the printed briefing, in its
     * own box, rather than left blank (R3-PLAN A9) — and never for a TE, whose printed briefing has no situation.
     */
    private fun briefingPage(b: Briefing?, aircraft: String, route: List<DataCardPlan.Waypoint>) {
        if (b == null) return
        val saveOnly = b.origin == "save" && !joined
        // WDP's intelligence from the save (GndThreads, AirThreads …), for a flight no printed briefing speaks for
        val intel: CampIntel? = if (saveOnly) mission.flight?.intel else null
        fun names(l: List<String>?) = l.orEmpty().joinToString(", ")
        /** the threats under a heading, and whether the briefing has that heading at all */
        fun block(word: String): Pair<String, Boolean> {
            val blocks = b.threats.filter { it.title?.contains(word, true) == true }
            // the threats themselves, not the sentences around them ("Known or suspected … include:", "There are no
            // known enemy air defense assets …", "No enemy air response is anticipated.")
            val list = blocks.flatMap { it.lines }.filter { it.isNotBlank() && !NOT_A_THREAT.containsMatchIn(it) }.joinToString(", ")
            return list to blocks.any { k -> k.lines.any { NONE_KNOWN.containsMatchIn(it) } }
        }
        val own = b.`package`.firstOrNull { it.callsign.equals(b.overview.flight, true) }
        // WDP's words for the flight (MissionTask, MissionYourDescp): the task is the flight's role ("BARCAP", "OCA
        // Strike"); "your description" is the target, or the role where there is none (a CAP)
        val role = own?.role?.takeIf { it.isNotBlank() } ?: b.overview.mission ?: ""
        val (ground, noGround) = block("Surface")
        val (air, noAir) = block("Air-to-Air")
        plan.fillMissionBriefing(
            airbase = plan.tblApt[0].name ?: "",
            aircraft = aircraft,
            // a save's flight names its squadron; the printed briefing does not
            squad = mission.flight?.row?.squadron ?: "",
            description = b.overview.packageMission ?: own?.task ?: "",
            yourTask = role,
            takeOff = route.firstOrNull()?.let { DataCardPlan.getTime(it.depart) } ?: "",
            // WDP's TimeOnTarget is the flight's time on target, which is a CAP's time on station: the briefing prints
            // that as "Time on Station" (which the parser does not keep) and as the package row's "Tgt" time
            tot = (b.overview.tot ?: overviewRow(b, "Time on Station") ?: own?.target)?.trim()?.removeSuffix("z") ?: "",
            // (a save's flight: its target by name; the briefing's Target Area is where it is, "4 nm south of Tirana")
            yourDescription = mission.flight?.row?.target?.takeIf { it.isNotBlank() } ?: b.overview.targetArea?.takeIf { it.isNotBlank() } ?: role,
            // WDP cuts its team motto, one paragraph, into five lines of 80: the briefing's situation has paragraph
            // breaks, and a line cut across one showed only what came before the break ("…take target." and then the
            // next paragraph's second half), so the text is one paragraph here too
            motto = b.situation?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (saveOnly && !tactical()) PRINT_SITUATION else "",
            gndThreats = if (intel != null) names(intel.ground) else ground.ifEmpty { if (noGround) "none known along your flight path" else "" },
            airThreats = if (intel != null) names(intel.fighters) else air.ifEmpty { if (noAir) "none expected" else "" },
            fighterBomber = names(intel?.fighterBombers), bombers = names(intel?.bombers), support = names(intel?.support), helo = names(intel?.helos),
        )
        situationLines(b)
        // a save's flight from a PC that sends no intelligence: its threats are in the briefing BMS prints
        if (saveOnly && b.threats.isEmpty() && intel == null) plan.type("txtBrfIntel1", PRINT_INTEL)
        objectives(b, own, role)
    }

    /** The mission is a flight of a TE or a training mission, whose printed briefing has no situation and no ROE. */
    private fun tactical(): Boolean = mission.flight?.kind.let { it == CampKind.TE || it == CampKind.TRAINING }

    /**
     * The Situation box where WDP's layout would lose text. WDP cuts its team motto (at most 200 characters) into five
     * lines of 80; BMS's printed situation runs to 400 and more, and the fifth line then ended mid-sentence ("…during
     * its"). So: a list heading BMS leaves with nothing after it ("Potential targets in the area include:") is dropped,
     * and a text that does not fit five lines of 80 is laid out over the box's real width (446 px holds 84 characters
     * of its Arial 8.25), the last line marked "…" only if even that is not enough. Text that fits WDP's lines is left
     * exactly as WDP lays it out.
     */
    private fun situationLines(b: Briefing) {
        val text = (b.situation ?: return).replace(Regex("\\s+"), " ").trim()
            .replace(Regex("\\s*[^.:!?]*\\binclude:\\s*$", RegexOption.IGNORE_CASE), "").trim()
        fun wrap(width: Int): List<String> {
            val out = ArrayList<String>()
            var rest = text
            while (rest.isNotEmpty()) {
                if (rest.length <= width) { out += rest; break }
                val cut = rest.substring(0, width).lastIndexOf(' ').let { if (it <= 0) width else it }
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            return out
        }
        if (wrap(80).size <= 5) {
            // WDP's own lines, unless dropping the empty heading is all that changed them
            if (text != (b.situation ?: "").replace(Regex("\\s+"), " ").trim()) wrap(80).let { l -> for (i in 1..5) plan.type("txtBrfSituation$i", l.getOrElse(i - 1) { "" }) }
            return
        }
        val lines = wrap(SITUATION_WIDTH)
        for (i in 1..5) {
            val l = lines.getOrElse(i - 1) { "" }
            plan.type("txtBrfSituation$i", if (i == 5 && lines.size > 5) l.take(SITUATION_WIDTH - 1) + "…" else l)
        }
    }

    /** [text] word-wrapped into lines of at most [width] characters. */
    private fun wrapText(text: String, width: Int): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (w in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            cur = when {
                cur.isEmpty() -> w
                cur.length + 1 + w.length <= width -> "$cur $w"
                else -> { out += cur; w }
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** A row of the briefing's Mission Overview the parser does not keep ("Station Area", "Time on Station"), or null. */
    private fun overviewRow(b: Briefing, key: String): String? =
        b.sections.firstOrNull { it.title.contains("Overview", true) }?.rows
            ?.firstOrNull { r -> r.firstOrNull()?.trim()?.trimEnd(':')?.trim().equals(key, true) }
            ?.getOrNull(1)?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }

    /**
     * The Mission Objective box. WDP leaves its ten lines to the pilot; the briefing says what the flight is to do,
     * so the card starts with that — the flight's task and where and when, then the package's flights with their
     * times, ours marked — and a pilot who types over it keeps what was typed ([typed]).
     */
    private fun objectives(b: Briefing, own: PackageFlight?, role: String) {
        val lines = ArrayList<String>()
        val cs = b.overview.flight ?: own?.callsign ?: ""
        val task = own?.task?.takeIf { it.isNotBlank() } ?: b.overview.packageMission ?: ""
        if (cs.isNotEmpty() || role.isNotEmpty()) lines += listOf(cs, role).filter { it.isNotEmpty() }.joinToString(" ") + (if (task.isNotEmpty()) ": $task" else "")
        val station = overviewRow(b, "Station Area")
        val area = b.overview.targetArea?.takeIf { it.isNotBlank() }
        when {
            area != null -> lines += "Target area: $area"
            station != null -> lines += "Station area: $station"
        }
        val onStation = overviewRow(b, "Time on Station")
        // a CAP's window: from the time on station to the hold's departure ("Return to previous steerpoint (Departure: 05:06:13z)")
        val leave = b.steerpoints.firstNotNullOfOrNull { s -> s.comments?.let { Regex("Departure:\\s*([0-9:]+z?)").find(it)?.groupValues?.get(1) } }
        // (a printed briefing has one or the other; a save's flight has both, and a CAP's is the time on station)
        when {
            onStation != null -> lines += "Time on station: $onStation" + (leave?.let { " - $it" } ?: "")
            b.overview.tot != null -> lines += "Time on target: " + b.overview.tot
            own?.target != null -> lines += "Time on target: " + own.target
        }
        val pkg = b.`package`
        if (pkg.isNotEmpty()) {
            lines += ""
            lines += "Package " + (b.overview.packageId ?: "") + (b.overview.packageType?.let { " ($it)" } ?: "") + ":"
            for (f in pkg) {
                val parts = listOfNotNull(
                    f.callsign.padEnd(10),
                    listOfNotNull(f.count?.toString(), f.aircraft).joinToString(" ").takeIf { it.isNotBlank() },
                    f.role,
                    f.takeoff?.let { "T/O $it" },
                    f.target?.let { "TGT $it" },
                )
                lines += parts.joinToString("  ") + if (f.callsign.replace(" ", "").equals(cs?.replace(" ", ""), true)) "  (you)" else ""
            }
        }
        for (i in 1..10) {
            val n = "txtBrfObjective$i"
            if (n in typed) continue
            plan.type(n, lines.getOrNull(i - 1)?.take(OBJECTIVE_WIDTH) ?: "")
        }
    }

    /**
     * The Config rows (A-A, A-G, ECM, Tanks): the stores of the seat the card shows, in the flight it shows, as the
     * briefing lists them (the printed briefing's ordnance, or the save's). WDP writes them from the Performance page's
     * loadout window, opened hidden whenever a flight is loaded (`fclsLoadout.cs` l.5060-5066; D38): counted, each by
     * the name BMS's SMS page gives it ("2x A120B 4x 120C5", "1x AL184", "2x TK370"), the gun left out as WDP's
     * OrdnanceLead leaves out the 20mm. A store the Arsenal does not know keeps its designation ("GBU-12").
     */
    private fun ordnance() {
        val p = plan
        val b = mission.briefing
        val callsign = (b?.`package`?.getOrNull(p.selFltInPack - 1)?.callsign ?: b?.overview?.flight)?.replace(" ", "")
        val jets = b?.ordnance.orEmpty().firstOrNull { callsign != null && it.flight.replace(" ", "").equals(callsign, true) }?.aircraft.orEmpty()
        // a save lists the racks it leaves bare as "Empty" (the briefing BMS prints does not): not a store
        val stores = (jets.getOrNull(p.selPilotSeat) ?: jets.firstOrNull())?.stores.orEmpty().filter { !EMPTY_RACK.containsMatchIn(it.name) }
        // each store by the name BMS's SMS page gives it, as WDP's loadout window writes these rows ("2x A120B 4x 120C5")
        fun row(kind: String) = stores.filter { LoadoutWindow.kindOf(it.name) == kind }
            .joinToString(" ") { "${it.qty}x " + (sources.smsName(it.name) ?: LoadoutWindow.shortName(it.name)) }
        p.type("lblAA", row("A-A"))
        p.type("lblAG", row("A-G"))
        p.type("lblECM", row("ECM"))
        p.type("lblTanks", row("Fuel"))
    }

    /**
     * `fclsLoadout.DatacardLabels`: the Loadout window's OK writes the stores it hands the Performance page into the
     * card's Config rows too, as WDP's window does — [stores] by name and count, in the window's order. The flight's
     * own loadout in BMS is not changed (WDP's question says so); the briefing's stores come back with the next flight
     * or seat picked ([ordnance]).
     */
    internal fun loadoutRows(stores: List<Pair<String, Int>>) {
        val p = plan
        fun row(kind: String) = stores.filter { LoadoutWindow.kindOf(it.first) == kind }
            .joinToString(" ") { "${it.second}x " + (sources.smsName(it.first) ?: LoadoutWindow.shortName(it.first)) }
        p.type("lblAA", row("A-A"))
        p.type("lblAG", row("A-G"))
        p.type("lblECM", row("ECM"))
        p.type("lblTanks", row("Fuel"))
        version++
    }

    /**
     * The ROE box from the briefing's Rules of Engagement, each rule starting a line and word-wrapped over the box's
     * five. WDP leaves the box to the pilot (and to a saved card); the briefing BMS prints carries the mission's own
     * rules, so the card starts with them. A box the pilot types into keeps what was typed.
     */
    private fun roe(b: Briefing?) {
        val lines = ArrayList<String>()
        for (rule in b?.roe.orEmpty()) {
            var cur = ""
            for (w in rule.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                cur = when {
                    cur.isEmpty() -> w
                    cur.length + 1 + w.length <= ROE_WIDTH -> "$cur $w"
                    else -> { lines += cur; w }
                }
            }
            if (cur.isNotEmpty()) lines += cur
        }
        // a save's flight BMS has printed no briefing for, from a PC that did not word them (a TE's briefing has none)
        if (lines.isEmpty() && b?.origin == "save" && !joined && !tactical()) lines += PRINT_ROE
        for (i in 1..5) {
            val l = lines.getOrNull(i - 1) ?: ""
            plan.type("txtRoe$i", if (i == 5 && lines.size > 5) l.take(ROE_WIDTH - 1) + "…" else l)
        }
    }

    /**
     * The bullseye in a save's header, north/east feet: the opened save's ([CampFlight.bullseyeX]), else the one beside
     * the briefed flight's mission file (`MissionData.route`), else the save the printed briefing was found in ([saveFlight]).
     */
    private fun saveBullseye(): Pair<Double, Double>? {
        fun of(x: Double?, y: Double?) = if (x != null && y != null) x to y else null
        mission.flight?.let { f -> of(f.bullseyeX, f.bullseyeY)?.let { return it } }
        mission.route?.let { r -> of(r.bullseyeX, r.bullseyeY)?.let { return it } }
        return saveFlight(mission)?.let { of(it.bullseyeX, it.bullseyeY) }
    }

    /**
     * The Coordination Card's bullseye row (`FillBullseye`): WDP reads the bullseye of the campaign it opened, and its
     * name (`Bullseye()`: "Bullseye", or "Rose"). An opened save's is that save's header (WDP's own); with the
     * printed briefing it is the one the sim gives while BMS runs, else the one in the save's header beside the briefed
     * flight's mission file (`MissionData.route`) or the save the briefing was found in, as the Mission pages show it
     * before 3D. Blank when none is known. A header's bullseye is the middle of its cell, where BMS puts it (its ACMI
     * says so); WDP prints the cell's corner, half a cell south-west (D62).
     */
    private fun bullseyeBox() {
        val c = mission.coords
        val opened = mission.flight?.let { f -> f.bullseyeX?.let { x -> f.bullseyeY?.let { y -> x to y } } }
        val bull = opened ?: bullseye(MissionLink.live.value, MissionLink.contacts.value?.contacts) ?: saveBullseye()
        if (c == null || bull == null) { plan.type("lblCommBullLat", ""); plan.type("lblCommBullLon", ""); return }
        val s = PopupCoords.feetToCoordsBoth(c, bull.first, bull.second)
        plan.type("lblCommBullLat", PopupCoords.getNorthDeg(s))
        plan.type("lblCommBullLon", PopupCoords.getEastDeg(s))
        // FillBullseye writes the name every time; what the pilot typed over it comes back with the typed boxes
        plan.type("txtBullseye", saveFlight(mission)?.bullseyeName ?: plan.text("txtBullseye").ifBlank { "Bullseye" })
    }

    // ================================================================ the page's controls

    /** A typed value, a spinner step, or `<name>.leave` when a text box loses focus (its Leave handler). Never throws. */
    override fun onValue(name: String, value: String) {
        try { handleValue(name, value) } catch (e: Exception) { /* WDP would stop here; the phone must not */ }
        version++
    }

    private fun handleValue(name: String, value: String) {
        val p = plan
        if (name.endsWith(".leave")) { p.leave(name.removeSuffix(".leave")); return }
        // a box the pilot has typed into shows what was typed from now on, whatever the app knows of it
        if (name.startsWith("txt")) typed += name
        when {
            name.startsWith("numRouteStpt") -> {
                val k = name.removePrefix("numRouteStpt").toIntOrNull() ?: return
                val v = value.trim().toIntOrNull() ?: return
                // a spinner being turned is a spinner on show
                if (k in 1..9) { routeSpinner = k; p.routeStpt(k, v.coerceIn(0, 24)) }
            }
            // the lists WDP opens over the card: a pick is the item picked, as SelectedItem
            name in MODE_LISTS -> {
                val i = MODE_LISTS.indexOf(name)
                p.weaponModeSelect(i, p.modeItems(i).indexOf(value.trim()))
                modeVisible[i] = true
            }
            name == "cboTanker1" || name == "cboTanker2" -> {
                val k = name.last() - '0'
                p.cboTankerSelectItem(k, value)
                tankerList = k
            }
            name in RUNWAY_LISTS -> runwayPicked(RUNWAY_LISTS.indexOf(name), value.trim())
            name == "cboAB" -> { power = value; toSpec = true }
            name == "numPitch" -> { pitch = (value.trim().toIntOrNull() ?: pitch).coerceIn(0, 20); toSpec = true }
            name == "numTaxi1" || name == "numTaxi2" -> {
                val v = (value.trim().toIntOrNull() ?: return).coerceIn(4, 10)
                if (name == "numTaxi1") p.numTaxi1 = v else p.numTaxi2 = v
                p.fillTaxi()
            }
            name.startsWith("txtFuel") -> {
                val k = name.removePrefix("txtFuel").toIntOrNull() ?: return
                p.type(name, value)
                // a row past the route has no leg: typing there moves no other row (the box empties when left, as WDP's Leave does)
                if (k in 2..24 && k <= (p.flightTable?.getOrNull(p.selFlightNr)?.numWaypoints ?: 0)) {
                    p.txtFuelKeyUp(k)
                    // WDP's KeyUp works the fuel ladder back to take-off, and its FillFlightplan then rewrote every row
                    // rounded up to 100 lb, the one being typed in too: the first key of "4520" became "100", and the
                    // rest went in front of it. The rows above take the new figure as it is typed; the box keeps what
                    // the pilot typed until it is left, where txtFuelLeave rounds it as WDP does.
                    p.type(name, value)
                }
            }
            else -> p.type(name, value)
        }
    }

    /** A press: a button, a label WDP makes clickable, a radio button or check box, a list. Never throws. */
    override fun onClick(name: String) {
        try { handleClick(name) } catch (e: Exception) {
            // WDP's own card carries on after an error box; a button of the app's that failed says why
            if (name in SIDE_BUTTONS) WdpDialogs.message("Weapon Delivery Planner", "This did not work: " + (e.message ?: e::class.simpleName) + ".")
        }
        version++
    }

    private fun handleClick(name: String) {
        val p = plan
        when {
            // lblActionN_DoubleClick → ShowTarget: WDP opens the steerpoint's target (its buildings); here a tap opens
            // the steerpoint on the app's theater map with what the mission says of it
            name.startsWith("lblAction") && name.removePrefix("lblAction").toIntOrNull() != null -> steerpointInfo(name.removePrefix("lblAction").toInt())
            // Mil/Civ and M/SM: WDP remakes the ATIS when it has the weather (CreateAtis)
            name == "btnATIS" -> {
                p.btnATIS()
                runCatching { com.bmscompanion.app.data.Repo.putString(ATIS_KEY, p.blnAtis.toString()) }
                weather(quiet = true)
            }
            // picReformPlan_Click …: WDP opens a picture file of the pilot's own (DataCards\PlanPic\ReformPlan.jpg …) straight
            // away, and so does the app, on the BMS PC's disks ([planPictureFile]); the app's own pictures for the box — WDP's
            // templates, the Planner's attack maps, Clear — are the right button's, or a long press on a touch screen
            name in PLAN_PICTURES -> scope.launch { planPictureFile(name); version++ }
            name.endsWith(":right") && name.removeSuffix(":right") in PLAN_PICTURES -> planPicture(name.removeSuffix(":right"))
            // picMap_Click: the white map on or off, and the map drawn again
            name == "picMap" -> {
                whiteMap = !whiteMap
                runCatching { com.bmscompanion.app.data.Repo.putString(WHITE_MAP_KEY, whiteMap.toString()) }
            }
            name == "btnKmSm" -> {
                p.btnKmSm()
                runCatching { com.bmscompanion.app.data.Repo.putString(KMSM_KEY, p.blnKmSm.toString()) }
                weather(quiet = true)
            }
            name == "btnToggleRange" -> {
                p.setRangeType()
                p.fillAttackType()
                // with no attack profile and no offsets on the card there is no range to show in the new unit
                if (p.attack.strProfile == "None" || p.text("lblVIPrng").isEmpty()) WdpDialogs.message(
                    "Range", "The offset ranges are now in " + listOf("feet", "nautical miles", "kilometres")[p.intSetRange.coerceIn(0, 2)] +
                        ". There are none on the card yet: pick an attack profile in the Type box, or save one from the Pop-up, HADB or TOSS page.",
                )
            }
            name == "lblFormation" -> p.setSwingFltpln()
            name == "lblLat" || name == "lblLon" -> p.setSwing()
            name.startsWith("lblCommName") -> {
                val k = name.removePrefix("lblCommName").toIntOrNull() ?: return
                // HideRouteStpts, HideTaxi, then the spinner when a mission or a mission cartridge is loaded
                routeSpinner = 0; taxiSpinner = 0
                if (p.blnMissionLoaded || p.blnMissionDtcLoaded) routeSpinner = k
                // no route yet: say where one comes from rather than a tap that does nothing
                else WdpDialogs.message("Route", NO_MISSION)
            }
            name == "lblRwyTaxiTime1" || name == "lblRwyTaxiTime2" -> { routeSpinner = 0; taxiSpinner = name.last() - '0' }
            name == "txtRwyTaxiTime1" || name == "txtRwyTaxiTime2" -> taxiSpinner = name.last() - '0'
            name.startsWith("rbnCallsign") -> {
                val k = name.removePrefix("rbnCallsign").toIntOrNull() ?: return
                // a disabled radio button (no flight in that slot) takes no click
                if (k !in 1..5 || !p.rbnEnabled[k]) return
                // D86: another flight of the package is shown for reference only (greyed in values()); WDP's
                // rbnCallsignN_Click switched the card to it under our flight's briefing and route
                if (k != ownPackage) { WdpDialogs.message("Package", OTHER_FLIGHT_TIP); return }
                // our own flight: the card is on it already (it never leaves it), so its edits stay; only a card that is
                // somehow elsewhere is brought back (SelectCallsign, the targets from the cartridge, the stores)
                if (p.selFltInPack != k) {
                    p.rbnCallsignClick(k)
                    targets(mission)
                    ordnance()
                }
                // then, as in WDP, the flight's loadout window: the Performance page's, where stores are hung
                openLoadout?.invoke() ?: loadout(k)
            }
            name in SEATS -> {
                val i = SEATS.indexOf(name)
                if (!p.seatEnabled[i]) return
                p.selectPilotSeat(i)
                // CreateFlightplan fills the target boxes again with this seat's designated targets (file version 104
                // on), for our own flight: the app has another flight's strike steerpoints only as its briefing times
                cardSeat = i
                ordnance()
                if (mission.flight != null && p.selFltInPack == ownPackage) {
                    targets(mission)
                    // a save's flight: the seat is the Planner's own (R3-UI §3.4), so the identity strip, the
                    // Performance page's loadout and Populate from Planner follow the card's radio buttons
                    if (PlannerMissionState.fromSave && PlannerMissionState.flight != null) PlannerMissionState.seat = i
                }
            }
            name in checks -> checks[name] = !(checks[name] ?: false)
            name == "txtWpn_AttackType" -> profileButtons = !profileButtons
            name in MODE_BOXES -> {
                // txtWpn_Mode1_Click …: this list opens on the DTC page's choice; the other two kinds close, in both
                // profiles (the same kind in the other profile is left as it is, as in WDP)
                val i = MODE_BOXES.indexOf(name)
                p.weaponModeOpen(i)
                for (j in 0..5) if (j % 3 != i % 3) modeVisible[j] = false
                modeVisible[i] = true
            }
            name in MODE_LISTS -> {
                val i = MODE_LISTS.indexOf(name)
                p.weaponModeSelect(i, (p.modeSel[i] + 1).mod(p.modeItems(i).size))
            }
            name in MODE_BUTTONS -> {
                val i = MODE_BUTTONS.indexOf(name)
                p.weaponModeTake(i)
                modeVisible[i] = false
            }
            name in listOf("btnPopUp", "btnHADB", "btnTOSS", "btnNone") -> {
                // btnX_Click: cntDTC.strProfile, cntDTC.Profiles() (the page's offsets into the cartridge), the buttons hidden
                val profile = when (name) { "btnPopUp" -> "PopUp"; "btnHADB" -> "HADB"; "btnTOSS" -> "TOSS"; else -> "None" }
                takeAttack(profile)
                p.profileButton(profile)
                profileButtons = false
                // WDP's choice goes to the DTC page (cntDTC.Profiles); here Save DTC hands it on
                profileChosen = profile
                // a profile chosen on the card is the Planner's current attack (AttackFocus)
                AttackFocus.pageOf(profile)?.let { AttackFocus.apply(it) }
            }
            name == "btnSetTanker1" || name == "btnSetTanker2" -> {
                val k = name.last() - '0'
                // the other list closes, this one gets its selection and opens or closes
                if (tankerList != k) tankerList = 0
                p.btnSetTanker(k)
                tankerList = if (tankerList == k) 0 else k
                // the list comes up dropped open, the tankers on station to pick from (WDP shows it closed, a
                // second press away; pilots took the closed box for the choice and never found the others)
                if (tankerList == k) tankerDrop++
            }
            name == "cboTanker1" || name == "cboTanker2" -> {
                val k = name.last() - '0'
                val items = if (k == 1) p.cboTanker1 else p.cboTanker2
                // an empty list opened is the Set button's work: it gets its "No Tanker" and the choice so far
                if (items.isEmpty()) { p.btnSetTanker(k); tankerList = k }
                else p.cboTankerSelectIndex(k, (p.cboTankerSel[k] + 1).mod(items.size))
            }
            name == "btnTanker1" || name == "btnTanker2" -> {
                val k = name.last() - '0'
                val items = if (k == 1) p.cboTanker1 else p.cboTanker2
                if (items.getOrNull(p.cboTankerSel[k]) == null) {
                    WdpDialogs.message("Tanker", "Pick a tanker in the list first: the button beside the tanker row opens it, with the tankers on station during the flight.")
                    return
                }
                p.btnTanker(k)
                tankerList = 0
            }
            // txtDMPI_Pri/Sec_MouseDoubleClick: WDP's Target Selection window, every objective of the save; a tap here
            name == "txtDMPI_Pri" || name == "txtDMPI_Sec" -> dmpi(name.endsWith("Pri"))
            name.startsWith("txtFormation") -> {
                // txtFormationN_Click: WDP's formation window, its answer back into the row (and "set all")
                val k = name.removePrefix("txtFormation").toIntOrNull() ?: return
                FormationWindow(p.text(name)) { f, all -> p.formationChosen(k, f, all); version++ }.open()
            }
            // the airports' rows: another airfield (fclsSelAPT), the runway list, the chart window
            name == "lblDepName" || name == "lblArrName" || name == "lblAltnName" -> {
                val i = listOf("lblDepName", "lblArrName", "lblAltnName").indexOf(name)
                val all = sources.airports?.airports.orEmpty()
                if (all.isEmpty()) { WdpDialogs.message("Select an airport", "The app has no airport list for this mission's theater."); return }
                SelAptWindow(all, apt[i]?.name) { a -> airportPicked(i, a); version++ }.open()
            }
            name == "lblDepRWY" || name == "lblArrRWY" || name == "lblAltnRWY" -> {
                // DepRWY(): the list opens where the row has an airport, and the other two close
                val i = listOf("lblDepRWY", "lblArrRWY", "lblAltnRWY").indexOf(name)
                if (p.text(listOf("lblDepName", "lblArrName", "lblAltnName")[i]).isNotEmpty()) runwayList = i
                else WdpDialogs.message("Runway", "Pick the airport first: tap the empty airport box on this row and choose it in the list; its runways then open here.")
            }
            name in RUNWAY_LISTS -> {
                // a tap on an open list without a pick steps to its next runway, as the arrow keys do
                val i = RUNWAY_LISTS.indexOf(name)
                val list = ends[i]
                if (list.isNotEmpty()) runwayPicked(i, list[(list.indexOfFirst { it.designator == p.tblApt[i].rwy } + 1).mod(list.size)].designator)
            }
            name in CHART_BUTTONS -> charts(CHART_BUTTONS.indexOf(name))
            // the take-off spec: the click shows its boxes (lblTOSpec_Click), Select puts them on the card
            name == "lblTOSpec" -> {
                // lblTOSpec_Click: the boxes open on the Performance page's pitch and power, which the spec prints
                // ("13°/ AB", "10°/ Mil"), either way round
                Regex("(\\d+)").find(p.text("lblTOSpec"))?.groupValues?.get(1)?.toIntOrNull()?.let { pitch = it }
                power = if (p.text("lblTOSpec").contains("MIL", true)) "MIL" else "Full AB"
                toSpec = true
            }
            name == "btnToSpecSel" -> {
                // WDP hands these to the performance page, which prints them back here as "13°/ AB"
                toSpec = false
                p.type("lblTOSpec", pitch.toString() + "°/ " + (if (power == "MIL") "MIL" else "AB"))
            }
            name == "cboAB" -> { power = if (power == "MIL") "Full AB" else "MIL"; toSpec = true }
            name == "lblLead" || name == "lblWing1" || name == "lblElement" || name == "lblWing4" -> names()
            else -> sideButton(name)
        }
    }

    // ================================================================ the buttons down the side and the windows

    /** The buttons beside the card, which reach outside it: files, the cartridge, the weather, the windows. */
    private fun sideButton(name: String) {
        val p = plan
        val b = mission.briefing
        when (name) {
            // the briefing's weather read again into the ATIS and the weather list; it says something only when
            // there is nothing to read
            // WDP's "Load WX FMAP File" window: an .fmap or a .twx on the BMS PC, whose weather the card then shows
            "btnWeather" -> scope.launch { reloadWx(); version++ }
            "btnGetDTC" -> getDtcFile()
            "btnSaveDTC" -> saveDtc()
            // WDP's btnChangeToFlight (enable the other callsigns, "flightplan might not match!") is REMOVED (D86)
            "btnSelDiffFlight" -> differentFlightButton()
            // WDP's own files on the BMS PC, in WDP's own formats (WdpFiles, CardFile)
            "btnLoadDataCard" -> scope.launch { loadDataCardFile(); version++ }
            "btnSaveDataCard" -> PackageNrWindow(p.strMission ?: "", p.selPackageName ?: "") { mis, pkg ->
                scope.launch { saveDataCardFile(mis, pkg); version++ }
            }.open()
            "btnDatacardLocation" -> scope.launch { cardsDirectory(); version++ }
            "btnSaveCodewords" -> scope.launch { saveIniFile(timing = false); version++ }
            "btnLoadCodewords" -> scope.launch { loadIniFile(timing = false); version++ }
            "btnSavePackageTiming" -> scope.launch { saveIniFile(timing = true); version++ }
            "btnLoadPackageTiming" -> scope.launch { loadIniFile(timing = true); version++ }
            "btnViewMilCodes" -> MilCodesWindow().open()
            "btnAirportSchedule" -> {
                if (b == null) { WdpDialogs.message("Information", NO_MISSION); return }
                openSchedule()
            }
            // WDP's Upd Kneeboard is not on the page (REMOVED): the toolbar's Upd Kneeboard is the one place for it
        }
    }

    /**
     * WDP's **Different Flight** (now the Planner's identity strip, R3-PLAN A2) with the printed briefing: Pick a flight
     * on the save BMS printed it from when that is known; else WDP's selection window over the briefing's package, where
     * another flight says how to plan it (D86: the card never shows another flight under our own briefing).
     */
    fun differentFlight() {
        val b = mission.briefing
        if (b == null) { WdpDialogs.message("Message", NO_MISSION); return }
        // the save the briefing was printed from, when it is known: Pick a flight, which changes the whole Planner
        // (briefing, route, cartridge and card) through PlannerMissionState
        if (saveRef() != null) { differentFlightButton(); return }
        // D86: WDP's window switched only the card to the flight picked, under our flight's briefing and route. BMS
        // printed this flight's briefing alone and its save is not known, so another flight is planned by opening its
        // save (Open mission…, then Pick a flight); the window shows the package, and our own flight is the one it keeps
        SelectionWindow(b, mission.choices.map { it.label }) { k ->
            if (k == ownPackage) return@SelectionWindow
            WdpDialogs.message(
                "Different Flight",
                "BMS printed the briefing of your own flight only, and the save it came from is not known here, so the card " +
                    "cannot change to another flight without mixing it with yours.\n\n" + OTHER_FLIGHT_TIP,
                listOf("Open mission…", "Cancel"),
            ) { if (it == "Open mission…") PlannerWindows.show(PlannerWindow.OPEN_MISSION) }
        }.open()
    }

    /**
     * The card's own **Different Flight** (`btnSelDiffFlight_Click`: WDP's flight selection over the campaign it has
     * open): **Pick a flight** on the save the card is planned from — the save opened with Open mission…, else the
     * save BMS printed the briefing from. With a printed briefing whose save is not known, the briefing's own package
     * ([differentFlight]); with nothing yet, what WDP says.
     */
    fun differentFlightButton() {
        val ref = saveRef()
        when {
            ref != null -> FlightPicker.show(ref, PlannerMissionState.file?.takeIf { it.name.equals(ref.file, ignoreCase = true) })
            mission.briefing != null -> differentFlight()
            else -> WdpDialogs.message("Message", "First load a mission.\n\n$NO_MISSION")
        }
    }

    /** The save the card's flight comes from: the one opened with Open mission…, else the one BMS printed the briefing from. */
    private fun saveRef(): com.bmscompanion.app.data.mission.CampRef? =
        mission.ref?.takeIf { mission.flight != null }?.let { com.bmscompanion.app.data.mission.CampRef(it.theater, it.file) }
            ?: mission.briefing?.takeIf { it.origin == null }?.let { BriefedSave.known(BriefedSave.key(it)) }
                ?.let { com.bmscompanion.app.data.mission.CampRef(it.theater, it.file) }

    /**
     * `txtDMPI_Pri_MouseDoubleClick` / `txtDMPI_Sec_MouseDoubleClick`: WDP's Target Selection window
     * ([DataCardTargetWindow]) — every objective of the save and the selected one's buildings — and the pick onto the
     * card as WDP's `FillPriTarget` / `FillSecTarget` put it: the objective in the target box, the building in the
     * DMPI box and its position as "N42,03.848 / E127,36.109 - 2877'" (the ground's height from BMS's height map).
     * When the box's strike steerpoint has a target (a save's flight), the window opens on it and on its building, as
     * WDP's `fclsFed` opens on the steerpoint's objective; WDP then also moved the steerpoint onto the building in its
     * cartridge (`SetTargetSTPTs`), which the card leaves to the DTC page. What is picked stays on the card as typed.
     */
    private fun dmpi(pri: Boolean) {
        val ref = saveRef()
        if (ref == null) {
            WdpDialogs.message(
                "Target Selection",
                "The list of targets is the save's: open the flight with Open mission…, or press PRINT on BMS's briefing screen " +
                    "with the PC linked, so the card knows which save it is planned from.",
            )
            return
        }
        val suffix = if (pri) "Pri" else "Sec"
        val strike = mission.flight?.route.orEmpty().filter { it.action == WdpMission.STRIKE }.getOrNull(if (pri) 0 else 1)
        val place = strike?.let { it.designated.getOrNull(cardSeat) ?: it.target }
        DataCardTargetWindow(ref, mission.coords, mission.theater, place?.campId, place?.building) { o, f ->
            val north = f?.x ?: o.x
            val east = f?.y ?: o.y
            val elev = f?.elevFt ?: o.elevFt.takeIf { f == null }
            val c = mission.coords
            val at = if (c != null) PopupCoords.hemispheres(PopupCoords.feetToCoordsBoth(c, north, east)).let { (n, e) -> "$n / $e" }
                else "N " + kft(north) + " / E " + kft(east)
            val boxes = mapOf("txtTGT_$suffix" to o.name, "txtDMPI_$suffix" to (f?.name ?: ""), "txtLatLong_$suffix" to at + (elev?.let { " - $it'" } ?: ""))
            for ((n, v) in boxes) { plan.type(n, v); typed += n }
            version++
        }.open()
    }

    /** The toolbar's Re-read DTC from BMS: the DTC page reads the cartridge again (asking first over edits), and the card takes its boxes afresh. */
    fun rereadDtc() = getDtc()

    /** The entries last handed to the DTC page by the toolbar's save, so the count does not name them twice while that page holds them. */
    private var handed: WdpCartridge.CardEntries? = null

    /**
     * What the card would write into the cartridge (ALOW, MSL floor, bingo, EWS names, laser codes, bomb profiles, the
     * attack profile chosen here), or null: the toolbar's Save to DTC hands it to the DTC page and saves. The attack
     * profile is the DTC page's from then on, as after WDP's Save DTC.
     */
    fun entriesToHand(): WdpCartridge.CardEntries? {
        val e = cardEntries() ?: return null
        handed = e
        if (e.attackProfile != null) profileChosen = null
        version++
        return e
    }

    /**
     * How many of the card's entries wait for the cartridge: part of the number on the Planner's Save to DTC (R3-PLAN
     * A2), as the "Callsign.ini saved" lamp was red for them. Read in composition, it follows the card.
     */
    val unsavedEntries: Int
        get() {
            @Suppress("UNUSED_VARIABLE") val v = version
            val e = try { cardEntries() } catch (x: Exception) { null } ?: return 0
            if (e == handed) return 0
            fun bp(b: WdpCartridge.BombProfile?) = if (b == null) 0 else listOfNotNull(
                b.submode, b.fuze, b.sglPair, b.armDelaySec, b.burstAltFt, b.releaseAngleDeg, b.pulses, b.spacingFt,
            ).size
            return listOfNotNull(e.alowAglFt, e.mslFloorFt, e.bingoLbs, e.laserTgp, e.laserLst, e.attackProfile).size +
                (e.ewsProgramNames?.count { it != null } ?: 0) + bp(e.profile1) + bp(e.profile2)
        }

    /**
     * The cartridge as a save has just written it ([DtcWiring.onSaved]): the card measures its entries against that
     * from now on, so what was saved is no longer waiting and the count falls to 0; the boxes show the file's values
     * (a laser code the DTC page kept to 1111-2888 shows as kept).
     */
    fun takeCartridge(text: String) {
        try {
            val model = com.bmscompanion.app.data.wdp.DtcModel().also { com.bmscompanion.app.data.wdp.DtcLoad.loadCallsign(text, it) }
            sources = DataCardSources(
                sources.theater, sources.airports, sources.charts, model, sources.panels, sources.radio, sources.geo, text,
            )
            typed.removeAll(CARTRIDGE_BOXES)
            fromCartridge(model)
        } catch (e: Exception) { /* a cartridge the card cannot read leaves its boxes */ }
        handed = null
        version++
    }

    /** Why the card cannot reach the cartridge: the DTC page, which holds it, is made with the Planner. */
    private fun noCartridge(title: String) =
        WdpDialogs.message(title, "The cartridge is not available yet: the DTC page holds it. Open the DTC page once, then try again.")

    /**
     * Get DTC File (`btnGetDTC_Click`): WDP's window on the game's `User\Config` — the DTC page's Open Callsign.ini
     * File, on the pilot's own file — and the cartridge picked is the DTC page's and the card's: the card takes its
     * boxes afresh (EWS names, bomb profiles, laser codes, ALOW, MSL floor, bingo, the offsets), as WDP's card shows
     * what its DTC page has just loaded. WDP's own Get DTC File opened a Tactical Engagement's `Mission.ini` as a second
     * cartridge; the Planner reads the mission's route by itself (BMS's route beside the save; a TE's own file is
     * written by Save DTC once that TE is opened with Open mission…), so the file a pilot gets here is the one BMS loads
     * into the jet. A window closed with no file changes nothing.
     */
    private fun getDtcFile() {
        val hook = WdpCartridge.open ?: return noCartridge("Get DTC File")
        // what the card and the DTC page hold for the cartridge is dropped by another file: asked first, as Re-read
        // DTC from BMS asks
        val waiting = unsavedEntries + (runCatching { WdpCartridge.pending?.invoke() }.getOrNull() ?: 0)
        if (waiting > 0) {
            WdpDialogs.message(
                "Get DTC File",
                "$waiting " + (if (waiting == 1) "change is" else "changes are") + " not saved in your cartridge yet (the card's " +
                    "entries and the DTC page's edits). Opening a file drops " + (if (waiting == 1) "it" else "them") +
                    ": press Save DTC first to keep " + (if (waiting == 1) "it" else "them") + ".",
                listOf("Open anyway", "Cancel"),
            ) { answer -> if (answer == "Open anyway") openDtcFile(hook) }
            return
        }
        openDtcFile(hook)
    }

    /** Get DTC File's window and the cartridge picked onto the card ([getDtcFile]). */
    private fun openDtcFile(hook: suspend () -> String?) {
        scope.launch {
            val text = try { hook() } catch (e: Exception) {
                WdpDialogs.message("Get DTC File", "It did not work: " + (e.message ?: e::class.simpleName) + ".")
                null
            } ?: return@launch
            takeCartridge(text)
        }
    }

    /**
     * The toolbar's Re-read DTC from BMS: the DTC page reads the cartridge again (asking first when it holds edits not
     * saved), and the card takes the cartridge's boxes afresh — EWS names, bomb profiles, laser codes, ALOW, MSL floor,
     * bingo and the offsets — as WDP's card shows whatever the DTC page has just loaded.
     */
    private fun getDtc() {
        val hook = WdpCartridge.reload ?: return noCartridge("Re-read DTC from BMS")
        scope.launch {
            val said = try { hook() } catch (e: Exception) { "It did not work: " + (e.message ?: e::class.simpleName) + "." }
            val text = readCartridge()
            if (text != null) try {
                val model = com.bmscompanion.app.data.wdp.DtcModel().also { com.bmscompanion.app.data.wdp.DtcLoad.loadCallsign(text, it) }
                sources = DataCardSources(
                    sources.theater, sources.airports, sources.charts, model, sources.panels, sources.radio, sources.geo, text,
                )
                typed.removeAll(CARTRIDGE_BOXES)
                fromCartridge(model)
            } catch (e: Exception) { /* a cartridge the card cannot read leaves its boxes */ }
            // "" = the DTC page asked a question instead (edits not saved): its answer is the question
            if (said.isNotEmpty()) WdpCartridge.answer("Re-read DTC from BMS", said)
            version++
        }
    }

    /**
     * Save DTC: what the pilot changed on the card goes into the DTC page ([WdpCartridge.writeCardEntries]), through
     * that page's own boxes and checks, and the cartridge is saved — only the keys that changed, written at once as
     * WDP writes it. With nothing changed on the card it is the DTC page's own save.
     */
    private fun saveDtc() {
        val entries = cardEntries()
        val write = WdpCartridge.writeCardEntries
        val save = WdpCartridge.save
        if (entries != null && write == null || entries == null && save == null) return noCartridge("Save to DTC")
        // handed to the DTC page now: counted there (its edits) until the save, not twice on the toolbar's number
        if (entries != null) handed = entries
        scope.launch {
            val said = try { if (entries != null) write!!(entries, true) else save!!() } catch (e: Exception) {
                "It did not work: " + (e.message ?: e::class.simpleName) + "."
            }
            // the attack profile is the DTC page's now (its edits, saved or not); the other entries show on the lamp
            // until the cartridge read back holds them
            if (entries?.attackProfile != null) profileChosen = null
            // "" = the save asked first (it would zero placed points): the question is the answer
            if (said.isNotEmpty()) WdpCartridge.answer("Save to DTC", said)
            version++
        }
    }

    /**
     * What the card would put into the cartridge: every entry the pilot changed from what the cartridge holds (as
     * last read), or null when there is none — which is also what keeps the "Callsign.ini saved" lamp green. The
     * attack profile counts once the pilot has chosen one with the card's buttons.
     */
    private fun cardEntries(): WdpCartridge.CardEntries? {
        val m = sources.cartridge ?: return null
        val p = plan
        fun num(n: String): Double? = p.text(n).takeIf { DataCardNet.isNumeric(it) }?.let { DataCardNet.toDouble(it) }
        fun changed(now: Double?, was: Double): Int? = now?.takeIf { kotlin.math.abs(it - was) > 1e-6 }?.roundToInt()
        val alow = changed(num("txtALOW"), m.icp.alowAgl.toDouble())
        val msl = changed(num("txtMSL"), m.icp.alowMsl.toDouble())
        val bingo = changed(num("txtBingo"), m.icp.bingoFuel.toDouble())
        val ews = (1..6).map { i -> p.strEws[i]?.takeIf { it != (m.ews.program?.getOrNull(i - 1)?.comment ?: "") } }
        val tgp = p.laserCode.takeIf { it != m.laser.laserCode.toInt() && lasersKnown }
        val lst = p.laserLST.takeIf { it != m.laser.lstCode.toInt() && lasersKnown }
        fun profile(b: com.bmscompanion.app.data.wdp.DtcBombProfile, base: Int): WdpCartridge.BombProfile? {
            fun w(i: Int) = p.wpn[base + i]?.trim().orEmpty()
            fun n(i: Int): Double? = w(i).takeIf { DataCardNet.isNumeric(it) }?.let { DataCardNet.toDouble(it) }
            val sub = when (b.submode) { 8 -> 0; 7 -> 1; 9 -> 2; 10 -> 3; else -> -1 }
            val bp = WdpCartridge.BombProfile(
                submode = w(0).takeIf { it.isNotEmpty() && it != DataCardPlan.SUBMODES.getOrNull(sub) },
                fuze = w(1).takeIf { it.isNotEmpty() && it != DataCardPlan.FUZES.getOrNull(b.fuze) },
                sglPair = w(5).takeIf { it.isNotEmpty() && it != (if (b.sglPair == 0) "SGL" else "PAIR") },
                armDelaySec = n(2)?.takeIf { kotlin.math.abs(it - b.c1Ad1 / 100.0) > 1e-6 },
                burstAltFt = n(3)?.roundToInt()?.takeIf { it != b.c2Ba },
                releaseAngleDeg = n(4)?.roundToInt()?.takeIf { it != b.releaseAngle },
                pulses = n(6)?.roundToInt()?.takeIf { it != b.releasePulse },
                spacingFt = n(7)?.roundToInt()?.takeIf { it != b.releaseSpacing },
            )
            return bp.takeIf { it != WdpCartridge.BombProfile() }
        }
        // the card's own attack: what it shows, by the attack page's own Save to DTC path (B11) — the profile chosen on
        // the card, and, once that has been saved (or an attack page's Save to DTC filled the card), the card's profile again while it is the
        // Planner's current attack (AttackFocus) and the cartridge does not hold what the card shows: a change on the
        // attack page after the save reaches the card, and its Save DTC must write it (never another page's attack over it)
        val focus = AttackFocus.profile
        val following = plan.attack.strProfile.takeIf { pf ->
            profileChosen == null && focus != null && AttackFocus.pageOf(pf) == focus &&
                runCatching {
                    attackPages?.invoke()?.let { (a, b, c) -> WdpAttackOverlay.ofPlan(when (focus) { WdpPage.POPUP -> a; WdpPage.HADB -> b; else -> c }) }
                }.getOrNull() != null &&
                cardOffsets(pf)?.let { (o, sel) -> !cartridgeHolds(m, o, sel) } == true
        }
        val attackChosen = profileChosen ?: following
        val attackOffsets = cardOffsets(attackChosen)
        val e = WdpCartridge.CardEntries(
            alowAglFt = alow, mslFloorFt = msl, bingoLbs = bingo,
            ewsProgramNames = ews.takeIf { l -> l.any { it != null } },
            laserTgp = tgp, laserLst = lst,
            profile1 = profile(m.agb1, 0), profile2 = profile(m.agb2, 8),
            attackProfile = attackChosen,
            attackOffsets = attackOffsets?.first, attackModesel = attackOffsets?.second,
        )
        return e.takeIf { it != WdpCartridge.CardEntries() }
    }

    /** The loadout window for package slot [k], from the briefing's ordnance for that flight. */
    private fun loadout(k: Int) {
        val b = mission.briefing
        val f = b?.`package`?.getOrNull(k - 1)
        val callsign = f?.callsign ?: b?.overview?.flight ?: ""
        val jets = b?.ordnance.orEmpty().firstOrNull { it.flight.replace(" ", "").equals(callsign.replace(" ", ""), true) }?.aircraft.orEmpty()
        LoadoutWindow((f?.count?.let { "$it " } ?: "") + (f?.aircraft ?: ""), jets, plan.selPilotSeat).open()
    }

    /** The airport's chart window (`btnDepChart_Click` …): its diagram as the Taxi page draws it, its parking charts and its instrument charts. */
    private fun charts(i: Int) {
        val a = apt[i]
        if (a == null) {
            // the row names a field the theater's airports do not have (a carrier BMS names only "USS"): the pilot picks it
            WdpDialogs.message("Charts", "Pick the airport first: tap its name on the card and choose it in the list, then its charts open here.")
            return
        }
        ChartWindow(a, sources.chartsOf(a), sources.theater?.airfieldSet).open()
    }

    /** `Names()` (a click on a pilot's name): WDP's names window, its answer into the flight block. */
    private fun names() {
        val p = plan
        val roster = mission.briefing?.roster.orEmpty().flatMap { it.pilots }.filter { !it.equals("Unassigned", true) }
        NamesWindow(roster, (1..4).map { p.strNames[it] ?: "" }) { picked ->
            for (i in 0..3) p.strNames[i + 1] = picked.getOrNull(i) ?: ""
            p.fillDataCard()
            version++
        }.open()
    }

    /** Which of the three pages is on screen, by its name. */
    private fun pageName() = when (page) { "pnlPage_2" -> "Coordination Card"; "pnlBrief" -> "Briefing"; else -> "DataCard" }

    // ---------------------------------------------------------------- WDP's own files (WdpFiles, CardFile)

    /** DataCards\<mission>\<package>\<callsign>, leaving out what is blank, as WDP's MissionDir, PackageDir and FlightDir. */
    private fun cardFolders(mis: String, pkg: String, callsign: String): Triple<String, String, String> {
        fun sub(base: String, name: String) = if (name.isBlank()) base else base + "\\" + name.trim()
        val missionDir = sub(WdpFiles.DATACARDS, mis)
        val packageDir = sub(missionDir, pkg)
        return Triple(missionDir, packageDir, sub(packageDir, callsign))
    }

    /**
     * Load DataCard (`btnLoadDataCard_Click`): WDP's "Load backup DataCard File" window — in the flight's own DataCards
     * folder when it is there, else DataCards, `Backup DataCard (*.bdc)` — and the file read onto the page on screen as
     * WDP's `LoadDataCard`, `LoadCommCard` or `LoadBriefingCard` reads it ([CardFile]). WDP looks in its program folder's
     * DataCards here even when its settings name another DataCards folder, which is where Backup DataCard writes; here
     * both use that folder. A Load that picks no file offers the copies an earlier version kept in the app's settings.
     */
    internal suspend fun loadDataCardFile(): String? {
        val p = plan
        val (_, _, flightDir) = cardFolders(p.strMission.orEmpty(), p.selPackageName.orEmpty(), p.text("lblCallsign1"))
        val start = if (WdpFiles.isFolder(flightDir)) flightDir else WdpFiles.DATACARDS
        val f = WdpFiles.open("Load backup DataCard File", start, "Backup DataCard (*.bdc)|*.bdc", WdpFiles.nameOf(WdpFiles.last("datacard")).ifEmpty { null })
        if (f == null) { offerKept(CardCopies.Kind.DATACARD) { loadCard(it) }; return null }
        val text = WdpFiles.readText(f, "Load DataCard") ?: return null
        WdpFiles.remember("datacard", f)
        CardFile.value(text, "Mission", "Theater").takeIf { it.isNotBlank() }?.let { bdcTheater = it }
        return loadCardFields(CardFile.boxes(text), f.name)
    }

    /**
     * Backup DataCard (`btnSaveDataCard_Click`): after WDP's mission-and-package window, the folders
     * DataCards\<mission>\<package>\<callsign> are made and WDP's "Backup DataCard" window opens there on
     * `<callsign>.bdc`; the card is written into the file picked as WDP's `SaveDataCard` writes it ([CardFile]).
     * WDP also saves pictures of the card beside it when its settings ask for them; the app does not.
     */
    internal suspend fun saveDataCardFile(mis: String, pkg: String): String? {
        val p = plan
        p.strMission = mis
        p.selPackageName = pkg
        val callsign = p.text("lblCallsign1").trim()
        val (_, _, flightDir) = cardFolders(mis, pkg, callsign)
        WdpFiles.folder(flightDir)
        val f = WdpFiles.save("Backup DataCard", flightDir, "Backup DataCard (*.bdc)|*.bdc", callsign, defaultExt = "bdc", makeFolder = false) ?: return null
        val existing = WdpFiles.existingText(f, "Backup DataCard") ?: return null
        val shown = values(emptyList())
        val text = CardFile.write(existing, box = { shown[it]?.takeIf { v -> v != "hidden" && v != "shown" } ?: p.text(it) }, value = ::bdcValue)
        if (!WdpFiles.writeText(f, text, "Backup DataCard")) return null
        WdpFiles.remember("datacard", f)
        val said = "Directory: ${f.folder}\n${f.name} Saved"
        WdpDialogs.message("Message", said)
        return said
    }

    /**
     * WDP's own values in a `.bdc` (`SaveDataCard`), or null for one the app does not know — left out, so WDP's load
     * keeps its own: the theater's number in WDP's database (unless a WDP file gave one), the loadout's weapon ids,
     * the Pop-up page's attack and the main map's place.
     */
    private fun bdcValue(token: String): String? {
        val p = plan
        fun bool(b: Boolean) = if (b) "True" else "False"
        fun pk(n: Int) = p.packages.getOrNull(n - 1)
        val num = token.dropWhile { !it.isDigit() }.toIntOrNull()
        return when {
            token == "=theater" -> bdcTheater
            token == "=campaign" -> bool(mission.flight != null || mission.briefing?.origin == "save")
            token == "=package" -> p.selPackageName.orEmpty()
            token == "=selflight" -> ((1..5).firstOrNull { p.rbnCallsign[it] } ?: 1).toString()
            token.startsWith("=takeofftime") -> pk(num ?: 0)?.takeOffTime?.toString() ?: "0"
            token.startsWith("=pushtime") -> pk(num ?: 0)?.pushTime?.toString() ?: "0"
            token.startsWith("=pushalt") -> pk(num ?: 0)?.pushAlt?.toString() ?: "0"
            token.startsWith("=targettime") -> pk(num ?: 0)?.targetTime?.toString() ?: "0"
            token == "=swingflpn" -> bool(p.blnSwingFlpn)
            token == "=pitch" -> pitch.toString()
            token == "=power" -> power
            token == "=swing" -> bool(p.blnSwing)
            token.startsWith("=route") && num != null ->
                p.text("lblCommFunc$num") + " / " + p.numRouteStpt.getOrElse(num) { 0 } + " / " + p.text("lblCommLat$num") + " / " + p.text("lblCommLon$num")
            else -> null
        }
    }

    /**
     * Save Codewords / Save Package Timing (`SaveCodeWords`, `SavePackageTiming`): WDP's window in the DataCards
     * folder on `Codewords.ini` / `PackageTiming.ini`, `(*.ini)`, and the boxes written into it under `[Codewords]` /
     * `[PackageTiming]` with WDP's own key names ([WdpFiles.CODEWORD_KEYS], [WdpFiles.TIMING_KEYS]).
     */
    internal suspend fun saveIniFile(timing: Boolean): String? {
        val p = plan
        val title = if (timing) "Save Package Timing" else "Save Codewords"
        val section = if (timing) "PackageTiming" else "Codewords"
        val keys = if (timing) WdpFiles.TIMING_KEYS else WdpFiles.CODEWORD_KEYS
        val f = WdpFiles.save(title, WdpFiles.DATACARDS, "(*.ini)|*.ini", "$section.ini", defaultExt = "ini") ?: return null
        val existing = WdpFiles.existingText(f, title) ?: return null
        val shown = values(emptyList())
        val text = WdpFiles.iniWrite(existing, keys.map { (k, box) -> Triple(section, k, shown[box] ?: p.text(box)) })
        if (!WdpFiles.writeText(f, text, title)) return null
        WdpFiles.remember(section.lowercase(), f)
        return f.path
    }

    /** Load Codewords / Load Package Timing (`LoadCodeWords`, `LoadPackageTiming`): the same window, the file's keys into the boxes. */
    internal suspend fun loadIniFile(timing: Boolean): String? {
        val p = plan
        val title = if (timing) "Load Package Timing" else "Load Codewords"
        val section = if (timing) "PackageTiming" else "Codewords"
        val keys = if (timing) WdpFiles.TIMING_KEYS else WdpFiles.CODEWORD_KEYS
        val kind = if (timing) CardCopies.Kind.TIMING else CardCopies.Kind.CODEWORDS
        val f = WdpFiles.open(title, WdpFiles.DATACARDS, "(*.ini)|*.ini", "$section.ini")
        if (f == null) {
            offerKept(kind) { n ->
                CardCopies.load(kind, n)?.let { m -> for ((_, box) in keys) { p.type(box, m[box] ?: ""); typed += box } }
                version++
            }
            return null
        }
        val text = WdpFiles.readText(f, title) ?: return null
        for ((k, box) in keys) { p.type(box, com.bmscompanion.app.data.wdp.DtcIni.read(text, section, k)); typed += box }
        WdpFiles.remember(section.lowercase(), f)
        version++
        return f.path
    }

    /**
     * Cards Directory (`btnDatacardLocation_Click`): WDP opens File Explorer on the flight's DataCards folder (or the
     * package's, the mission's, DataCards). On the BMS PC's own window that is File Explorer; anywhere else the folder
     * opens in the Planner's own window, where a `.bdc` or an `.ini` picked is loaded onto the card. Copies an earlier
     * version kept in the app's settings are offered first, while there are any.
     */
    internal suspend fun cardsDirectory() {
        val p = plan
        val (missionDir, packageDir, flightDir) = cardFolders(p.strMission.orEmpty(), p.selPackageName.orEmpty(), p.text("lblCallsign1"))
        val dir = listOf(flightDir, packageDir, missionDir).firstOrNull { it != WdpFiles.DATACARDS && WdpFiles.isFolder(it) } ?: WdpFiles.DATACARDS
        val kept = CardCopies.Kind.entries.filter { CardCopies.names(it).isNotEmpty() }
        if (kept.isNotEmpty()) {
            WdpDialogs.message(
                "Cards Directory",
                "The DataCards folder is on the BMS PC.\n\nAn earlier version kept these in BMS Companion's own settings on this device:\n" +
                    kept.joinToString("\n") { k -> k.label + ": " + CardCopies.names(k).joinToString(", ") } +
                    "\n\nLoad one onto the card, then Backup DataCard, Save Codewords or Save Package Timing puts it in a file.",
                listOf("Open the folder") + kept.map { "Load a kept ${it.label}" } + "Close",
            ) { answer ->
                when {
                    answer == "Open the folder" -> scope.launch { openCardsFolder(dir); version++ }
                    answer.startsWith("Load a kept ") -> {
                        val k = kept.firstOrNull { answer == "Load a kept ${it.label}" } ?: return@message
                        CopyWindow(k, saving = false, suggested = "") { n -> loadKept(k, n) }.open()
                    }
                }
            }
            return
        }
        openCardsFolder(dir)
    }

    private suspend fun openCardsFolder(dir: String) {
        WdpFiles.folder(dir)
        if (com.bmscompanion.app.data.PcFiles.explore(dir)) return
        val f = WdpFiles.open(
            "Cards Directory", dir, "Backup DataCard (*.bdc)|*.bdc|Codewords, package timing (*.ini)|*.ini|All files (*.*)|*.*", null, makeFolder = false,
        ) ?: return
        when (f.extension) {
            "bdc" -> {
                val text = WdpFiles.readText(f, "Load DataCard") ?: return
                WdpFiles.remember("datacard", f)
                loadCardFields(CardFile.boxes(text), f.name)
            }
            "ini" -> {
                val text = WdpFiles.readText(f, "Cards Directory") ?: return
                val timing = com.bmscompanion.app.data.wdp.DtcIni.read(text, "PackageTiming", "Ramrod_0").isNotEmpty() ||
                    text.contains("[PackageTiming]", ignoreCase = true)
                val section = if (timing) "PackageTiming" else "Codewords"
                for ((k, box) in if (timing) WdpFiles.TIMING_KEYS else WdpFiles.CODEWORD_KEYS) {
                    plan.type(box, com.bmscompanion.app.data.wdp.DtcIni.read(text, section, k)); typed += box
                }
                version++
            }
            else -> WdpDialogs.message("Cards Directory", "${f.name} is not a DataCard, codewords or package timing file.")
        }
    }

    private fun loadKept(k: CardCopies.Kind, n: String) {
        val p = plan
        when (k) {
            CardCopies.Kind.DATACARD -> loadCard(n)
            CardCopies.Kind.CODEWORDS, CardCopies.Kind.TIMING -> {
                val keys = if (k == CardCopies.Kind.TIMING) WdpFiles.TIMING_KEYS else WdpFiles.CODEWORD_KEYS
                CardCopies.load(k, n)?.let { m -> for ((_, box) in keys) { p.type(box, m[box] ?: ""); typed += box } }
                version++
            }
        }
    }

    /** After a Load that picked no file: the copies an earlier version kept in the app's settings, when there are any. */
    private fun offerKept(kind: CardCopies.Kind, load: (String) -> Unit) {
        val names = CardCopies.names(kind)
        if (names.isEmpty()) return
        WdpDialogs.message(
            "Load ${kind.label}",
            "No file was picked. An earlier version kept ${kind.label} copies in BMS Companion's own settings on this device: " +
                names.joinToString(", ") + ".\n\nLoad one of those instead?",
            listOf("Load a kept copy", "Cancel"),
        ) { answer -> if (answer == "Load a kept copy") CopyWindow(kind, saving = false, suggested = "") { n -> load(n) }.open() }
    }

    /**
     * Load DataCard: a kept copy onto the page on screen (`LoadDataCard`, `LoadCommCard`, `LoadBriefingCard`). On the
     * DataCard the flight plan's columns come back only where their box is ticked (Action, Time On Station …), as
     * WDP's load options say.
     */
    private fun loadCard(name: String) {
        val f = CardCopies.load(CardCopies.Kind.DATACARD, name) ?: return
        loadCardFields(f, name)
    }

    /** Boxes onto the page on screen, as WDP's three loads put them back; [name] is what they came from. */
    private fun loadCardFields(f: Map<String, String>, name: String): String {
        val columns = mapOf(
            "chbAction" to "lblAction", "chbTos" to "lblTOS", "chbHdg" to "lblHdg", "chbDist" to "lblDist", "chbKias" to "txtKias",
            "chbAlt" to "lblAlt", "chbFuel" to "txtFuel", "chbFormation" to "txtFormation",
        )
        var n = 0
        // what the card shows blank because the app does not know it stays blank: a copy's figure for it was WDP's
        // zero or a leftover (guide §15-4: the support Notes came back as "00:00 - 17:02", an unknown leg's GS as "1.50 / 995")
        val blank = blankedBoxes()
        val loaded = when (page) { "pnlPage_2" -> COMM_LOADED; "pnlBrief" -> BRIEF_LOADED; else -> CARD_LOADED }
        for ((box, v) in f) {
            if (sources.panels[box] != page) continue
            if (box in blank) continue
            val column = columns.entries.firstOrNull { (_, prefix) -> box.startsWith(prefix) && box.removePrefix(prefix).toIntOrNull() != null }
            // the flight plan's columns where their box is ticked; everything else only where WDP's load reads it back
            if (column != null) { if (checks[column.key] != true) continue } else if (box !in loaded) continue
            plan.type(box, v)
            if (box.startsWith("txt")) typed += box
            n++
        }
        val said = "$name loaded onto the ${pageName()}: $n boxes."
        WdpDialogs.message("Load DataCard", said)
        version++
        return said
    }

    /**
     * WDP's Airport Schedule (`fclsAptSchedule`): every flight of the campaign departing from the field the flight
     * departs from, by departure time. A save's flight carries it ([CampFlight.departures], read by the PC as WDP
     * reads it); with the printed briefing the window opens on the briefing's own package and turns into the save's
     * schedule when the save that holds the briefed flight is found ([BriefedSave]).
     */
    private fun openSchedule() {
        val b = mission.briefing ?: return
        val field = plan.tblApt[0].name ?: ""
        // Save JPG's mission, package and callsign are the card's, and its answer sets them on the card as WDP's does
        val p = plan
        val card = AptScheduleWindow.ScheduleCard(p.strMission.orEmpty(), p.selPackageName.orEmpty(), p.text("lblCallsign1")) { mis, pkg ->
            p.strMission = mis; p.selPackageName = pkg
        }
        mission.flight?.departures?.let { AptScheduleWindow(AptSchedule.of(it), card = card).open(); return }
        val first = AptSchedule.ofBriefing(b, field)
        if (mission.flight != null || b.origin == "save") { AptScheduleWindow(first, card = card).open(); return }
        val key = BriefedSave.key(b)
        AptScheduleWindow(first, { BriefedSave.flight(key)?.departures?.let { AptSchedule.of(it) } }, card).open()
    }

    /**
     * WDP's Current Time (`lblTime = GetTimeDay(CampaignTable[0].CurrentTime)`): the clock of the campaign file it has
     * loaded, "1, 01:02:16". Here that is the opened save's clock, or, with the printed briefing, the clock of the save
     * that holds the briefed flight ([BriefedSave]); without either, the sim's time of day while BMS runs (it gives no
     * day), and blank otherwise.
     */
    private fun currentTime(): String {
        val clock = mission.flight?.clock ?: mission.briefing?.takeIf { mission.flight == null }?.let { BriefedSave.known(BriefedSave.key(it))?.clock }
        if (clock != null) return DataCardPlan.getTimeDay(clock)
        return MissionLink.live.value?.timeSec?.takeIf { it > 0 }?.let { DataCardPlan.getTime(it * 1000L) } ?: ""
    }

    /**
     * With the printed briefing, the save that holds its flight is looked up once, for the Current Time; then that
     * save's flight ([briefedFlight]), and the card is filled again from it as WDP fills it from the save it opened —
     * its name after the package number, the package's own holds, pushes and targets, the bullseye. What the pilot
     * typed stays (the same briefing: [onMission]).
     */
    private fun lookUpBriefedSave(m: WdpMission) {
        val b = m.briefing ?: return
        if (m.flight != null || b.origin == "save") return
        val key = BriefedSave.key(b)
        if (briefedFlight?.first == key) return
        scope.launch {
            val known = BriefedSave.known(key) != null
            if (runCatching { BriefedSave.find(key) }.getOrNull() == null) return@launch
            if (!known) version++
            val f = runCatching { BriefedSave.flight(key) }.getOrNull() ?: return@launch
            val now = lastMission ?: return@launch
            if (now.flight != null || now.briefing?.let { BriefedSave.key(it) } != key || briefedFlight?.first == key) return@launch
            briefedFlight = key to f
            sourcesChanged = true
            onMission(now)
        }
    }

    companion object {
        private const val DAY = 86_400_000L
        /** half the width of WDP's tanker box on the map (`Tankers`: 30,000 ft either side of the station leg) */
        private const val TRACK_HALF_FT = 30_000.0
        /** WDP's `P.NM_TO_FT` */
        private const val NM_FT = 6076.1154
        /** a support track with no window of its own: on station the whole campaign (WDP's uint range) */
        private const val ALWAYS = 0xFFFFFFFFL
        /**
         * The transition level of every theater on KTO's terrain, in hundreds of feet: "In KTO the transition altitude
         * is 14000 feet and the transition level is FL 140" (BMS's KTO AIP, 2.1.1; the Comms & Nav book, 3.1.9) — and
         * WDP's own Korea airport database, whose every field says 140. Other theaters keep WDP's fallback, 180.
         */
        private const val KTO_TRANSITION_LEVEL = 140
        /** the app settings that keep the card's Mil/Civ and M/SM choices (WDP's Setup.ini [Main] ATIS and KmSm) */
        private const val ATIS_KEY = "wdp_card_atis_civil"
        private const val KMSM_KEY = "wdp_card_kmsm"
        /** and the card map's white map (WDP's Setup.ini [Map] WhiteMap) */
        private const val WHITE_MAP_KEY = "wdp_card_white_map"
        /** A callsign as WDP writes a flight's (`FillPackages`): the name, a space, the number — "Texaco 4" for BMS's "Texaco4". */
        internal fun wdpCallsign(s: String?): String? = s?.trim()?.let { Regex("^(.*?\\D)\\s*(\\d+)$").matchEntire(it)?.let { m -> m.groupValues[1].trimEnd() + " " + m.groupValues[2] } ?: it }
        /** where a route with no cartridge positions starts: any point well inside the theater does */
        private const val ORIGIN_FT = 500 * 3279.98
        private val SEATS = listOf("rbnLead", "rbnWing", "rbnElmLead", "rbnElmWing")
        /** how many characters a line of the ROE box holds (186 px of Arial 8.25) */
        private const val ROE_WIDTH = 34
        /** what a press that needs a mission says when there is none */
        private const val NO_MISSION = "There is no mission yet: press PRINT on BMS's briefing screen, or pick a flight of a save with Open mission…."
        /**
         * The Briefing page's weather panel for a save's flight BMS has printed no briefing for, when no save file is
         * named to read its weather file from (R3-PLAN A9); a save's own `.twx` gives the weather otherwise.
         */
        private const val SAVE_ONLY = "WEATHER\n\nThis flight names no save file to read its weather\n" +
            "(.twx) from. The weather comes with the briefing BMS\n" +
            "prints: press PRINT on its briefing screen with this flight\n" +
            "selected, or pick a weather file with Reload WX."
        /** how many characters a line of the Mission Objective box holds (446 px of Arial 8.25) */
        private const val OBJECTIVE_WIDTH = 84
        /** how many characters a line of the Situation box holds where WDP's 80 would lose text (446 px of Arial 8.25) */
        private const val SITUATION_WIDTH = 84
        /** BMS's compass words as a card writes them */
        private val COMPASS = mapOf(
            "north" to "N", "northeast" to "NE", "east" to "E", "southeast" to "SE", "south" to "S", "southwest" to "SW", "west" to "W", "northwest" to "NW",
        )
        /**
         * The Situation box for a save's flight BMS has printed no briefing for, when the PC could not word one from the
         * save (a PC before 1.3.8, or a campaign whose save gives no context the scripts know).
         */
        private const val PRINT_SITUATION = "This flight comes from a save. BMS writes the situation into the briefing it prints: " +
            "press PRINT on BMS's briefing screen with this flight selected, and the situation, the threats, the weather, " +
            "the comm ladder and the ROE come onto the card by themselves."
        /** The first Intel line for such a flight, from a PC that sends no intelligence ([CampIntel]). */
        private const val PRINT_INTEL = "Threats: in the briefing BMS prints (PRINT on its briefing screen, this flight selected)."
        /** The ROE box for such a flight, a line each. */
        private val PRINT_ROE = listOf("The ROE are in the briefing BMS", "prints: press PRINT on its", "briefing screen with this", "flight selected.")
        /** The DataCard's ATIS line for such a flight. */
        private const val PRINT_ATIS = "No weather file for this flight: PRINT the briefing in BMS for the ATIS."
        /** "There are no known enemy air defense assets along your flight path", "No enemy air response is anticipated." */
        private val NONE_KNOWN = Regex("^\\s*(No enemy|No known|There (are|is) no)", RegexOption.IGNORE_CASE)

        /**
         * What WDP reads back from a card file (`LoadDataCard`, `LoadCommCard`, `LoadBriefingCard`, cntDataCard.cs
         * l.25617-27660), page by page: what the pilot planned and wrote — never the mission's own figures (the airports,
         * the take-off performance, the flight's names, frequencies and stores), which stay as the mission gives them
         * now. The DataCard's flight plan columns load where their box is ticked.
         */
        private val CARD_LOADED: Set<String> by lazy {
            (listOf("lblAtis1", "lblAtis2", "txtALOW", "txtMSL", "txtSetFuel", "txtJoker", "txtBingo", "txtTGT_Pri", "txtDMPI_Pri", "txtTGT_Sec", "txtDMPI_Sec") +
                (1..6).map { "txtEws$it" } +
                listOf("txtTanker1", "txtTanker2", "txtAWACS", "txtJSTAR", "txtFAC").flatMap { t -> listOf("", "_TCN", "_UHF", "_Loc", "_Notes").map { t + it } } +
                listOf("Class", "Mtr", "Fr", "Mar", "Dr", "Dor").flatMap { t -> (1..4).map { "txt$t$it" } } +
                (1..5).map { "txtRoe$it" } + (1..5).map { "txtExtra$it" }).toSet()
        }
        private val COMM_LOADED: Set<String> by lazy {
            (listOf("txtRwy1", "txtRwy2", "lblRwyTaxiTime1", "lblRwyTaxiTime2", "txtStdQnh", "txtForceQnh", "txtTransitLvl", "txtMSAA",
                "lblCommBullLat", "lblCommBullLon", "txtSpdMedLvl", "txtSpdLowLvl") + CODEWORDS + TIMING).toSet()
        }
        private val BRIEF_LOADED: Set<String> by lazy {
            (listOf("lblBrfMission", "lblBrfPackage", "lblBrfCallsign", "lblBrfAirbase", "lblBrfAircraft", "lblBrfSquad", "txtBrfDescription",
                "txtBrfYourTask", "lblBrfTakeOff", "lblBrfTot", "txtBrfYourDescription") +
                (1..5).map { "txtBrfSituation$it" } + (1..9).map { "txtBrfIntel$it" } + (1..10).map { "txtBrfObjective$it" } +
                (1..12).map { "txtBrfNotes$it" }).toSet()
        }
        private val MODE_BOXES = listOf("txtWpn_SubMode1", "txtWpn_Fuse1", "txtWpn_SGLPAIR1", "txtWpn_SubMode2", "txtWpn_Fuse2", "txtWpn_SGLPAIR2")
        private val MODE_LISTS = listOf("cboSubMode1", "cboFuze1", "cboSGL_PAIR1", "cboSubMode2", "cboFuze2", "cboSGL_PAIR2")
        private val MODE_BUTTONS = listOf("btnSubMode1", "btnFuse1", "btnSGL_PAIR1", "btnSubMode2", "btnFuse2", "btnSGL_PAIR2")
        private val RUNWAY_LISTS = listOf("cboDepRWY", "cboArrRWY", "cboAltnRWY")
        private val PLAN_PICTURES = listOf("picReformPlan", "picHoldingPlan", "picIngressPlan", "picTargetPlan")
        /** the plan box's first choice: WDP's own window, a picture file on the BMS PC */
        private const val PICTURE_FILE = "Picture file…"
        /** WDP's own plan templates (`Datacards\PlanPic`: HoldingPlan, HoldingPlan-CCW, TargetPlan-AirtoAir-BVR, TargetPlan-AirtoGround-strike) */
        private const val TEMPLATE_HOLD_CW = "planpic_holding_cw.jpg"
        private const val TEMPLATE_HOLD_CCW = "planpic_holding_ccw.jpg"
        private const val TEMPLATE_TARGET_AA = "planpic_target_aa.jpg"
        private const val TEMPLATE_TARGET_AG = "planpic_target_ag.jpg"
        private val CHART_BUTTONS = listOf("btnDepChart", "btnArrChart", "btnAltnChart")
        private val SIDE_BUTTONS = setOf(
            "btnWeather", "btnGetDTC", "btnChangeToFlight", "btnSaveDTC", "btnLoadDataCard",
            "btnSaveDataCard", "btnDatacardLocation", "btnSaveCodewords", "btnLoadCodewords", "btnLoadPackageTiming",
            "btnSavePackageTiming", "btnSelDiffFlight", "btnViewMilCodes", "btnAirportSchedule",
        )

        /**
         * WDP's controls for what the card page does not do, taken off it: Print and Print Preview, the save timer (a
         * save is a press of Save to DTC), and the Mission.ini lamp (a Tactical Engagement's second cartridge, which the
         * DTC page does not open; Save to DTC writes it with the cartridge when that TE is open). WDP's **Upd Kneeboard**
         * is taken off too: it did exactly what the toolbar's Upd Kneeboard does, and the Planner has one place per
         * action (1.3.8). And WDP's **Enable Change** (`btnChangeToFlight`, hidden until shown), whose only work was to enable the other
         * flights' callsigns so the card could be switched to one under our own briefing (D86).
         */
        val REMOVED = listOf(
            "btnPrint", "btnPrintPreview", "btnTimer", "lblMissionIni", "pnlMissionGreen", "pnlMissionRed", "btnKneeboard",
            "btnChangeToFlight",
        )

        /**
         * WDP's card controls for the cartridge, in WDP's place: **Get DTC File**, **Save DTC** and the "Callsign.ini
         * saved" lamp (its label, its green and its red lamp; one of the two lamps shows at a time, none without a
         * cartridge). They were off the page for a while in 1.3.8 test builds, as the toolbar's Save to DTC; pilots
         * looked for them on the card, so they are back ([getDtcFile], [saveDtc]), beside the toolbar's. **Different
         * Flight** is on the page too, as well as being the identity strip ([differentFlightButton]).
         */
        val DTC_CONTROLS = listOf("btnGetDTC", "btnSaveDTC", "lblCallsignIni", "pnlCallsignGreen", "pnlCallsignRed")

        /**
         * The tooltip of another flight's callsign in the package rows (D86), word for word as the Planner guide says
         * it: the card stays on our own flight, and another is planned by flying it.
         */
        const val OTHER_FLIGHT_TIP = "Another flight of your package, shown for reference.\n" +
            "To fly and plan it: pick it and your seat in BMS's ATO, save the campaign or TE, then reopen it with Open mission…"

        /** Our own flight's callsign in the package rows: the card's flight, and a press opens its loadout. */
        const val OWN_FLIGHT_TIP = "Your flight: the one this card plans.\nPress to open its Loadout (the Performance page's)."

        /** BMS's waypoint actions the card's map treats apart (Strings.txt 350 + action): 4 Refuel, 7 Land. */
        private const val REFUEL = 4
        private const val LAND = 7

        /** The task actions WDP's DrawMapStpt marks (9-25: CAS, escort, sweep, CAP, intercept, the strikes, SEAD…). */
        private val TASK_ACTIONS = 9..25

        /** Boxes the cartridge fills: Get DTC File and a new cartridge put the cartridge's figures back in them. */
        private val CARTRIDGE_BOXES = setOf(
            "txtALOW", "txtMSL", "txtBingo", "txtEws1", "txtEws2", "txtEws3", "txtEws4", "txtEws5", "txtEws6",
            "txtLaserCode1", "txtLaserCode2", "txtLaserLST1", "txtLaserLST2",
        ) + listOf("SubMode", "Fuse", "ArmDly", "BA", "RelAngle", "SGLPAIR", "Ripple", "Space").flatMap { listOf("txtWpn_${it}1", "txtWpn_${it}2") }

        /** A threat analysis sentence that names no threat. */
        private val NOT_A_THREAT = Regex("^\\s*(Known or suspected|No enemy|No known|There (are|is) no)", RegexOption.IGNORE_CASE)

        /** The target boxes, which follow the mission's strike steerpoints and the card's seat (WDP's rule). */
        private val TARGET_BOXES = listOf("txtTGT_", "txtDMPI_", "txtLatLong_")

        /** The Coordination Card's codeword boxes, as `SaveCodeWords` writes them. */
        private val CODEWORDS = listOf(
            "txtAsFragged", "txtOnStation", "txtOffStation", "txtPushing", "txtReqRolex", "txtRolex", "txtMissionAbort", "txtPackageAbort",
            "txtMissionSucc", "txtMissionUnSucc", "txtBombJett", "txtAMR", "txtWfZoneActive", "txtWfZoneCancelled", "txtAttPriTgt", "txtAttSecTgt",
            "txtAttSucc", "txtAttUnSucc", "txtTgtObs", "txtReattack", "txtLastOfTgt", "txtRtb", "txtSpoofing", "txtChatterMarkPri",
            "txtChatterMarkSec", "txtChatterMarkTer", "txtChatterMarkHq", "txtLameDuck", "txtWoundedBird", "txtBailout", "txtEscortRoll",
            "txtExtra1_1", "txtExtra1_2", "txtExtra2_1", "txtExtra2_2",
        )

        /** The Coordination Card's package-timing boxes, as `SavePackageTiming` writes them. */
        private val TIMING = (0..9).map { "txtRamrod_$it" } + listOf(
            "txtBalt", "txtBhead", "txtBnum", "txtBfuel", "txtRollCall", "txtEobUpdate",
        ) + listOf("TakeOffToPush", "TotWindow", "ReAttackWindow", "LastOut").flatMap { n ->
            listOf("", "Callsign", "Pri", "Sec", "Ter", "Hq").map { "txt$n$it" }
        }

        /**
         * A briefing time ("03:43:00z") in campaign milliseconds of the day, or null. The card's `GetTime` rounds to
         * the nearest second (D20), so the whole second the briefing names prints as the briefing prints it.
         */
        fun clock(s: String?): Long? {
            val m = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})").find(s ?: return null) ?: return null
            val (h, mi, se) = m.destructured
            return ((h.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000L
        }

        private fun later(t: Long, after: Long): Long = if (t < after) t + DAY else t

        /** A hold's departure, which the briefing prints in the comments: "Hold (Departure: 03:56:49z)". */
        private fun departure(r: BriefSteerpoint): Long? =
            Regex("Departure:\\s*(\\d{1,2}:\\d{2}:\\d{2})").find(r.comments ?: "")?.let { clock(it.groupValues[1]) }

        /** "345.950" (MHz) → "345950" (kHz, as the radio map holds it). */
        private fun khz(mhz: String?): String? = mhz?.toDoubleOrNull()?.let { kotlin.math.round(it * 1000).toLong().toString() }

        private fun kft(v: Double): String = ((v / 1000.0 * 10).toInt() / 10.0).toString() + " kft"

        /** The briefing's steerpoint description as BMS's waypoint action (`WP_*`); navigation points are 0. */
        fun actionOf(r: BriefSteerpoint): Int {
            val d = (r.desc ?: "").trim().lowercase()
            return when {
                d.startsWith("takeoff") || d.startsWith("take off") -> 1
                d.startsWith("push") || d.startsWith("assemble") -> 2
                d.startsWith("split") -> 3
                d.startsWith("refuel") -> 4
                d.startsWith("rearm") -> 5
                d.startsWith("pickup") || d.startsWith("pick up") -> 6
                d.startsWith("land") -> 7
                d.startsWith("holding") || d.startsWith("timing") -> 8
                d.startsWith("cas") -> 9
                d.startsWith("escort") -> 10
                d.startsWith("sweep") -> 11
                d.startsWith("cap") || d.startsWith("barcap") -> 12
                d.startsWith("intercept") -> 13
                d.startsWith("grnd attack") || d.startsWith("ground attack") -> 14
                d.startsWith("naval") -> 15
                d.startsWith("s & d") || d.startsWith("s&d") || d.startsWith("search") -> 16
                d.startsWith("strike") -> 17
                d.startsWith("bomb") -> 18
                d.startsWith("sead") -> 19
                d.startsWith("elint") -> 20
                d.startsWith("recon") -> 21
                d.startsWith("rescue") -> 22
                d.startsWith("asw") -> 23
                d.startsWith("tanker") -> 24
                d.startsWith("airdrop") -> 25
                d.startsWith("jam") -> 26
                d.startsWith("fac") -> 30
                else -> 0
            }
        }

        /** The briefing's formation name as BMS's formation number, or -1 (blank). */
        fun formationOf(s: String?): Int {
            val f = (s ?: "").trim().lowercase().replace(" ", "")
            if (f.isEmpty() || f == "--") return -1
            return (0..14).firstOrNull { DataCardPlan.formationString(it).lowercase().replace(" ", "") == f } ?: -1
        }
    }
}

@Composable
fun rememberDataCardWiring(): DataCardWiring = remember { DataCardWiring() }
