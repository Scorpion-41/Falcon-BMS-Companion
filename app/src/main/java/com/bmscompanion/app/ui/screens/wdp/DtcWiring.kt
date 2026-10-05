package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Airport
import com.bmscompanion.app.data.BmsDefaults
import com.bmscompanion.app.data.GeoLayers
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Threat
import com.bmscompanion.app.data.pptTable
import com.bmscompanion.app.data.airfield.Airfield
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampRef
import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.wdp.DataCardNet
import com.bmscompanion.app.data.wdp.DataCardPlan
import com.bmscompanion.app.data.wdp.DtcDec
import com.bmscompanion.app.data.wdp.DtcDesigner
import com.bmscompanion.app.data.wdp.DtcEdits
import com.bmscompanion.app.data.wdp.DtcFromMission
import com.bmscompanion.app.data.wdp.DtcHarmCode
import com.bmscompanion.app.data.wdp.DtcLoad
import com.bmscompanion.app.data.wdp.DtcNavOffsets
import com.bmscompanion.app.data.wdp.DtcPage
import com.bmscompanion.app.data.wdp.DtcSave
import com.bmscompanion.app.data.wdp.DtcStpt
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.data.wdp.WdpValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Where the DTC page's cartridge comes from and goes to: the PC, through [MissionLink], or a fixture in the checks. */
interface DtcSource {
    suspend fun load(callsign: String?): CartridgeState?
    suspend fun save(callsign: String?, edits: List<CartridgeEdit>): CartridgeState?
    /**
     * A save while a pilot's own Tactical Engagement is the Planner's source (R3-PLAN A13): the cartridge, and the TE's
     * `[STPT]` keys also into [te]'s mission file (`<campaigndir>/<TE>.ini`), which BMS loads over the cartridge in a
     * TE. The answer's `mission` says whether that second file was written, and why not. A source with no PC saves the
     * cartridge only.
     */
    suspend fun saveTe(callsign: String?, edits: List<CartridgeEdit>, te: CampRef): CartridgeState? = save(callsign, edits)
    /** BMS's own `*_Def.ini` files in `User/Config` (read only), for the Load buttons' "BMS default"; null where there is no PC. */
    suspend fun bmsDefaults(): BmsDefaults? = BmsDefaults.fetch()
    /** The HARM tab's systems: the app's ALIC codes (`curated/harm.json`), as the lists show them. */
    suspend fun harmCodes(): List<DtcHarmCode> = DtcWiring.harmCodesOf(Repo.harm()?.alicCodes.orEmpty())
    /** Runs EZBoards on the PC as the Kneeboards page's GENERATE NOW does; null where EZBoards is not set up. */
    fun generateBoards(): (() -> Unit)? = null
}

/** The cartridge on the BMS PC — this one, or the one this device is linked to. */
object LinkDtcSource : DtcSource {
    // every save says which mission it is for (the PC's ledger), and every answer tells PlannerLeftovers what the
    // cartridge now holds (docs/DATA-STORES.md, "Starting the next mission")
    override suspend fun load(callsign: String?) = PlannerLeftovers.saw(MissionLink.cartridge(callsign))
    override suspend fun save(callsign: String?, edits: List<CartridgeEdit>) =
        PlannerLeftovers.saw(MissionLink.cartridgeSave(callsign, edits, mission = PlannerLeftovers.missionNow()))
    override suspend fun saveTe(callsign: String?, edits: List<CartridgeEdit>, te: CampRef) =
        PlannerLeftovers.saw(MissionLink.cartridgeSave(callsign, edits, te, PlannerLeftovers.missionNow()))
    // WDP mode suspends EZBoards on the PC (the cockpit kneeboards come from Upd Kneeboard), so a save offers no run
    override fun generateBoards(): (() -> Unit)? =
        MissionLink.info.value?.ezBoards?.takeIf { it.configured && !it.suspended }?.let { { MissionLink.generateBoards() } }
}

/**
 * WDP's DTC page, working: the pilot's own cartridge on every tab, editable where WDP's is, and saved back into
 * Falcon BMS.
 *
 * The page's controls and what WDP's page code does with them are [DtcPage] (Falcas's handlers, ported); this is
 * everything around it — the buttons that open WDP's child windows, the airport block, the files, loading and
 * saving — written for what each does rather than line by line:
 *
 * - **Loading**: the cartridge comes from the PC ([DtcSource], `GET /api/cartridge`) as it is on disk, and is
 *   loaded twice — into the page, and into a copy that is never touched, which is what a save compares against.
 * - **Saving** writes only the keys the pilot changed ([DtcEdits]), straight into the file as WDP's Save DTC does:
 *   no copy first, no question, nothing to switch on (the PC writes it through a temporary file and one move).
 * - **The child windows** are WDP's own forms ([DtcWindow]), over the app's data where WDP read its own database:
 *   the mission's targets, the theater's airbases, the planned tanker tracks.
 * - **WDP's own files** — MAIN's Open and Save Callsign.ini File, every tab's Load and Backup, the personal PPT table
 *   and Browse PPT.ini — open WDP's own windows on the BMS PC ([com.bmscompanion.app.data.PcFiles]) and read and write
 *   WDP's own files there ([DtcFiles], [WdpFiles]). A Callsign.ini opened from outside `User\Config` is the page's
 *   cartridge from then on: Save to DTC writes it, directly, as WDP writes the file it has open.
 *
 * "WDP for BMS 4.38.1": the page looks like WDP's, but wherever the app has the data the page takes the app's —
 * the HARM codes (`curated/harm.json`), the PPT types (the theater's own `Campaign/Ppt.ini`, with the threat
 * reference's radar and engagement ranges for the Radar Zone / Engage Zone tables), BMS's own `*_Def.ini` defaults,
 * the briefing's comm plan, the departure airfield's TACAN, ILS and charts. What WDP did that the app does not —
 * its TE `Mission.ini` as a second cartridge on the page (a TE opened with Open mission… is saved to with the cartridge),
 * a JPG of the target list, rewriting the theater's `ppt.ini` — is removed from the page rather than answered with
 * a message ([REMOVED]).
 *
 * It also sets [WdpCartridge]'s hooks, so the attack pages' Save to DTC and the DataCard's entries reach the
 * cartridge through this page, as they reach it through `cntDTC` in WDP. The Planner's toolbar saves through it too
 * ([saveToDtc], [unsaved]); the Save DTC buttons WDP has on the tabs that are not From mission… ([SAVE_DTC]) are on
 * the page again and are that same save, as MAIN's Open and Save Callsign.ini File are WDP's own windows in `User\Config`.
 *
 * **From mission…** (the button on nine tabs where WDP had a Save DTC of its own): what the app knows of the mission,
 * into that tab, as the tab's own editing puts it there ([DtcFromMission], gathered by [DtcMissionFacts]) — the tanker's
 * and AWACS's planned tracks and the CAP station as lines, the known air defences as PPT rings of the theater's own
 * types with the support stations as markers, the flight plan into the empty steerpoints, the flight's targets into the
 * open steerpoints and the target list, the comm plan, a TACAN (a field's, the tanker's air to air) and an ILS, the
 * save's laser codes, the briefed systems into a HARM table. Replacing what the pilot placed always asks first. The
 * Planner's Map page reaches the same through [missionFacts], [dtcPicture] and the place… calls.
 *
 * **Nothing placed is lost without a question** (R3-PLAN A14): the six Clear All buttons ask before wiping points
 * that have a position ([DtcPage.confirmClear]), and any save that would set a placed point to 0,0,0 names the points
 * and asks once ([zeroed]).
 */
class DtcWiring(private val source: DtcSource = LinkDtcSource) : WdpWiring {
    private var version by mutableIntStateOf(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var page = DtcPage()
    /** The same cartridge loaded and never touched: what the page's edits are measured against. */
    private var baseline: DtcPage? = null
    /** The file's text as it was loaded, which both saves are run over. */
    private var loadedText: String? = null
    private var state: CartridgeState? = null
    private var loading = false
    private var status: String? = null
    private var justSaved = false

    private var mission = WdpMission()
    private var started = false
    /**
     * A Callsign.ini MAIN's Open Callsign.ini File (or Save Callsign.ini File) opened from outside `User\Config`: the
     * page's cartridge while it is set, read and written through the PC's file routes. Null: the pilot's own cartridge
     * in `User\Config`, through [source].
     */
    private var cartridgeFile: com.bmscompanion.app.data.PickedFile? = null
    private var coords = DtcCoords(null)
    /** The HARM tab's systems: the app's ALIC codes, read once. */
    private var harmCodes: List<DtcHarmCode>? = null
    /** The PPT types (code, name, range in feet): the theater's own `Ppt.ini`, a zone table built on it, or the pilot's. */
    private var pptTable: List<Triple<String, String, Double>> = emptyList()
    /** Where [pptTable] came from. */
    private var pptSource = NO_PPT
    /** The theater's own PPT types (BMS's `Campaign/Ppt.ini` as the app ships it), for the zone tables. */
    private var theaterPpt: List<Triple<String, String, Double>> = emptyList()

    private var tab = "tabMain"
    /** Text typed into a field and not yet left: WDP validates on Leave, so the page only hears it then. */
    private val pending = HashMap<String, String>()
    private var pilotEditing = false
    private var pilotText = ""
    private var targetListName = "Targets"
    private var targetRow = -1
    private var pptRow = -1
    private val pptEdit = HashMap<String, String>()

    private var airports: List<Airport> = emptyList()
    /** The theater's airport list as [airports] came from, for finding the departure field by the name the mission gives. */
    private var airportSet: com.bmscompanion.app.data.AirportSet? = null
    private var airport: Airport? = null
    private var airfield: Airfield? = null
    /** The pilot chose the airport block's field with Select APT: a new mission then leaves it alone. */
    private var airportPicked = false

    init {
        // the card's and the attack pages' hooks answer "" while a question of this page is on screen (a save that
        // would zero placed points, a re-read over edits): the question is the answer, and no second box goes over it
        WdpCartridge.saveNavOffsets = { profile, offsets, sel, then -> saveNavOffsets(profile, offsets, sel, then).takeUnless { asking }.orEmpty() }
        WdpCartridge.reload = { reloadFromCard().takeUnless { asking }.orEmpty() }
        WdpCartridge.save = { saveNow().takeUnless { asking }.orEmpty() }
        WdpCartridge.writeCardEntries = { entries, save -> writeCardEntries(entries, save).takeUnless { asking }.orEmpty() }
        WdpCartridge.stageNavOffsets = { profile, offsets, sel -> stageNavOffsets(profile, offsets, sel) }
        // the card's Get DTC File is MAIN's Open Callsign.ini File (the window in User\Config), and its lamp this page's count
        // the cartridge's text only when the file picked is now the page's (one that could not be read, or a pilot file
        // with no cartridge behind it, has said why and leaves the card's boxes alone)
        WdpCartridge.open = { openedOk = false; if (openCallsignFile() == null || !openedOk) null else loadedText }
        WdpCartridge.pending = { unsaved.let { n -> if (loadedText == null) null else n } }
    }

    // ---------------------------------------------------------------- the mission

    override fun onMission(mission: WdpMission) {
        val before = this.mission
        this.mission = mission
        // steerpoints placed for another mission are not this one's (WdpMission.placed)
        if (before.key != mission.key) sessionSlots.clear()
        if (mission.coords != before.coords || mission.theater != before.theater) {
            coords = DtcCoords(mission.coords)
            // relabel every point with the theater's projection; the model's feet are untouched
            for (p in listOfNotNull(page, baseline)) { p.coords = mission.coords ?: PopupCoords.CoordData(); if (p.blnCallsignDtcLoaded) p.getCallsignCoords() }
            airports = emptyList()
            airportSet = null
            if (mission.theater != before.theater) {
                launch { theaterPptTable() }
                // another theater's field is no field of this one, whoever chose it
                airport = null; airfield = null; airportPicked = false
            }
            // the Radar/Engage Zone buttons' threat reference and the Charts button's list, read now so a press does
            // not wait on the disk the first time (both are kept once read)
            // (and the theater's towns, which From mission… turns "21 nm southwest of Yongin-si City" into a place with)
            geo = null
            mission.theater?.let { t ->
                launch {
                    reference = runCatching { Repo.threats() }.getOrDefault(reference)
                    runCatching { Repo.charts(t.airportSet) }
                    t.mapId?.let { id -> geo = runCatching { Repo.geo(id) }.getOrNull() }
                }
            }
            // the airport block is reference data, not the cartridge: it is filled with or without one
            if (mission.theater != null) launch { findDepartureAirport() }
        } else if (mission.briefing != before.briefing || mission.flight != before.flight) {
            // another flight (a new PRINT, a flight picked from a save): the airport block follows its departure field,
            // unless the pilot chose one with Select APT
            if (!airportPicked) { airport = null; airfield = null }
            // the airport block is reference data, not the cartridge: it is filled with or without one
            if (mission.theater != null) launch { findDepartureAirport() }
        }
        if (!started) {
            started = true
            launch { reloadNow(null) }
        } else if (loadedText == null && cartridgeFile == null && !loading) {
            // nothing on the page yet: the first read came before the link to the PC was up (the Planner is drawn at
            // once, and that read is the only one the page made by itself), or BMS had no cartridge for the pilot yet.
            // A mission that arrives later — the link up, a new PRINT, BMS writing the file — is the moment to try again.
            launch { reloadNow(state?.callsign, quiet = true) }
        } else {
            // BMS rewrote the cartridge (a new flight plan, a save in the UI): take it, unless the pilot has edits here
            val m = mission.dtc?.modified ?: 0L
            val have = state?.modified ?: 0L
            if (m > 0 && have > 0 && m > have && !loading && !hasEdits()) launch { reloadNow(state?.callsign, quiet = true) }
            // another flight (Open mission… of another save, another flight picked, a briefing of another flight
            // printed): WDP reads the cartridge again for every flight it is given (SelectNewFlight → LoadCallsign), so
            // changes made here for the flight before never go into this one's cartridge unasked (docs/DATA-STORES.md)
            if (!loading && loadedText != null && before.key != mission.key && !sameFlight(before, mission) && hasEdits()) askAnotherFlight(before)
        }
        version++
    }

    /** The same flight: its briefing first, then the same flight opened from its save (callsign alike), or the same key. */
    private fun sameFlight(a: WdpMission, b: WdpMission): Boolean {
        if (a.key == b.key) return true
        fun who(m: WdpMission) = (m.flight?.row?.callsign ?: m.briefing?.overview?.flight)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return a.flight == null && b.flight != null && who(a) != null && who(a) == who(b)
    }

    /** Changes on the page made for [before]'s flight, not saved, and another flight given: read the file again, or keep them. */
    private fun askAnotherFlight(before: WdpMission) {
        // one question at a time: a second flight change while it is on screen is answered by it
        if (WdpDialogs.stack.any { (it as? WdpMessage)?.title == ANOTHER_FLIGHT }) return
        val was = (before.flight?.row?.callsign ?: before.briefing?.overview?.flight)?.trim()?.takeIf { it.isNotEmpty() }
        WdpDialogs.message(
            ANOTHER_FLIGHT,
            "The DTC page has changes made for ${was ?: "the flight before"} that are not saved to ${state?.file ?: "the cartridge"}. " +
                "Kept, they go into this flight's cartridge at the next Save to DTC. Read the cartridge again for this flight " +
                "(the changes are dropped, as WDP does when a flight is picked), or keep them?",
            listOf("Read the cartridge", "Keep the changes"),
        ) { answer ->
            if (answer == "Read the cartridge") launch { reloadNow(if (cartridgeFile != null) null else state?.callsign) }
        }
    }

    /**
     * Whether a save would change the cartridge: the edits themselves ([edits]), not WDP's "saved" lamp, which a press
     * that changed nothing (Rebuild on an untouched list, Apply on an unchanged point, Clear All on an empty table)
     * turns red too. Only when the edits cannot be worked out does the lamp decide.
     */
    private fun hasEdits(): Boolean {
        if (loadedText == null) return false
        val e = try { edits() } catch (e: Throwable) { null }
        return if (e != null) e.isNotEmpty() else !page.blnCallsignSaved
    }

    // ---------------------------------------------------------------- loading

    /**
     * Opens the cartridge of [callsign] (or of the pilot BMS has selected). Returns what to tell the pilot.
     * [quiet]: BMS rewrote the file and the page takes it by itself, so the status line is left alone rather than
     * set to "Loading…" — nothing on the page would ever clear that again.
     */
    private suspend fun reloadNow(callsign: String?, quiet: Boolean = false): String {
        loading = true
        if (!quiet) status = "Loading the cartridge from the PC…"
        WdpCartridge.generateBoards = null
        version++
        // a pilot named is that pilot's own cartridge; otherwise the file MAIN opened, when it opened one
        if (callsign != null) cartridgeFile = null
        val file = cartridgeFile
        val st = try { if (file != null) fileState(file) else source.load(callsign) } catch (e: Throwable) { null }
        if (harmCodes == null) harmCodes = runCatching { source.harmCodes() }.getOrNull()
        loading = false
        if (st == null) {
            status = "The PC could not be reached, so the cartridge could not be read."
            version++
            return status!!
        }
        if (!st.available || st.text == null) {
            val why = st.error ?: "There is no cartridge to open."
            // another pilot's name with no file behind it: keep the pilot on the page, as WDP's PilotButton does,
            // rather than naming the missing one over the other's steerpoints (and saving them under its name)
            if (loadedText != null && callsign != null && !callsign.equals(state?.callsign, ignoreCase = true)) {
                status = null
                version++
                return why
            }
            state = st
            status = why
            version++
            return status!!
        }
        open(st)
        status = null
        // the PPT types after the page is open, so the page is whole even while the theater's table is still read
        if (pptSource == NO_PPT) theaterPptTable()
        // a theater the app has no table for (one installed after this copy was built): the PC's reading of its file
        if (pptSource == NO_PPT && st.pptIni != null) usePptTable(st.pptIni!!, "ppt.ini of the theater Falcon BMS is on, as the PC read it")
        if (mission.theater != null) findDepartureAirport()
        version++
        return "${st.file} loaded."
    }

    /** The page and its untouched twin, both from [st]'s text. */
    private fun open(st: CartridgeState) {
        val text = st.text ?: return
        state = st
        loadedText = text
        page = freshPage(text, st)
        baseline = freshPage(text, st)
        baseSaved = null
        // the page's six Clear All buttons ask before wiping points that have a position (A14); the untouched twin is
        // never clicked
        page.confirmClear = { title, question, go ->
            WdpDialogs.message(title, question, listOf("Clear", "Cancel")) { answer -> if (answer == "Clear") guard { go() } }
        }
        pending.clear()
        pptEdit.clear()
        pilotText = st.callsign.orEmpty()
        recogniseLines()
    }

    private fun freshPage(text: String, st: CartridgeState): DtcPage = DtcPage().also { p ->
        p.coords = mission.coords ?: PopupCoords.CoordData()
        p.pptTable = pptTable
        p.harmCodes = harmCodes.orEmpty()
        p.load()
        p.getCampFile(text, st.path ?: st.file.orEmpty())
    }

    // ---------------------------------------------------------------- saving

    /**
     * The cartridge as this page would save it now, edits and all, whether or not it has been saved: what the Planner's
     * Upd Kneeboard prints (Populate from Planner takes the file as saved instead). It is the loaded file with only
     * the pilot's edits applied, byte for byte what Save to DTC writes, not WDP's whole-model save: that one adds every setting WDP has a default for
     * (laser 1688, bingo 1500, TACAN 94X…), which the Mission views would then mark NOT IN JET although the pilot never
     * touched them. Null before a cartridge is loaded, or when the save could not be made (the same case in which
     * [edits] gives nothing).
     */
    fun cartridgeText(): String? {
        val text = loadedText ?: return null
        val e = edits() ?: return null
        return DtcEdits.apply(text, e)
    }

    /** The untouched cartridge as a save would write it: the same until the next load, so it is made once. */
    private var baseSaved: com.bmscompanion.app.data.wdp.DtcSaveResult? = null

    /** The keys the pilot changed on this page, as the cartridge spells them. */
    private fun edits(): List<CartridgeEdit>? {
        val text = loadedText ?: return null
        val base = baseline ?: return null
        val a = baseSaved ?: DtcSave.saveCallsign(text, base.m) { base.applyHarm() }.also { baseSaved = it }
        val b = DtcSave.saveCallsign(text, page.m) { page.applyHarm() }
        if (a.threw || b.threw) return null
        return DtcEdits.between(a.text, b.text)
    }

    private var countAt = -1
    private var countCache = 0

    /**
     * How many settings of the cartridge this page would change on the next save: the number on the Planner's Save
     * to DTC (R3-PLAN A2). 0 before a cartridge is loaded and right after a save. Read in composition, it follows every
     * change; it is worked out again only when the page has changed.
     */
    val unsaved: Int
        get() {
            val v = version
            if (v != countAt) {
                countAt = v
                countCache = try { edits()?.size ?: 0 } catch (e: Throwable) { 0 }
            }
            return countCache
        }

    /** The cartridge's file name, as the PC named it ("Viper.ini"), once one is loaded. */
    val fileName: String? get() { @Suppress("UNUSED_VARIABLE") val v = version; return state?.file }

    /** Whose cartridge the page has loaded ("Viper"), as the PC named it: what Populate from Planner names to the PC. */
    internal val callsign: String? get() = state?.callsign

    /** The cartridge's text as last loaded or saved: what the DataCard measures its own entries against after a save. */
    val loaded: String? get() = loadedText

    /**
     * The points this save would set to 0,0,0 although the cartridge as loaded has them placed (R3-PLAN A14), as the
     * page numbers them: "STPT 15-22", "Line 31-36", "PPT 56", "Target 3". Empty when the save zeroes nothing.
     */
    fun zeroed(): List<String> {
        val b = baseline?.m ?: return emptyList()
        val p = page.m
        fun gone(bx: Float, by: Float, px: Float, py: Float) = (bx != 0f || by != 0f) && px == 0f && py == 0f
        val out = ArrayList<String>()
        fun add(label: String, nums: List<Int>) { if (nums.isNotEmpty()) out += "$label ${DtcPage.ranges(nums)}" }
        fun stpts(bt: Array<DtcStpt>, pt: Array<DtcStpt>, count: Int, first: Int) =
            (0 until count).filter { gone(bt[it].falconX, bt[it].falconY, pt[it].falconX, pt[it].falconY) }.map { first + it }
        add("STPT", stpts(b.stpt, p.stpt, 24, 1) + stpts(b.open, p.open, 9, 81) + stpts(b.hpn, p.hpn, 10, 90))
        add("Line", (0 until 24).filter { gone(b.line[it].falconX, b.line[it].falconY, p.line[it].falconX, p.line[it].falconY) }.map { 31 + it })
        add("PPT", (0 until 15).filter { gone(b.ppt[it].falconX, b.ppt[it].falconY, p.ppt[it].falconX, p.ppt[it].falconY) }.map { 56 + it })
        add("Target", (0 until 100).filter { gone(b.tgt[it].falconX, b.tgt[it].falconY, p.tgt[it].falconX, p.tgt[it].falconY) }.map { 1 + it })
        return out
    }

    /**
     * Whether a save has just put a question on screen (the warning that it would zero placed points) and so wrote
     * nothing yet: the question's own answer then saves, and the caller shows no second box over it.
     */
    var asking = false
        private set

    /**
     * The mission file of the Tactical Engagement the Planner is planning from, when a save must also go there (A13):
     * a TE or a training mission opened with Open mission… — the pilot's own or one that ships with BMS, as in WDP. A
     * campaign's mission file is not written (WDP's copy of the cartridge's empty route into it zeroed BMS's route in
     * 4.38.1, D46).
     */
    private fun teRef(): CampRef? {
        val s = PlannerMissionState
        if (!s.fromSave || s.flight == null) return null
        return s.ref?.takeIf { s.kind == CampKind.TE || s.kind == CampKind.TRAINING }
    }

    /**
     * Run once after the next save that has written the cartridge (or found nothing in it to change), whichever way
     * it got there: at once, or through its question (a save that would zero placed points). Populate from Planner's
     * "Save to DTC and populate" sets it ([PlannerPopulate.saveThenPopulate]). A save that fails, a question answered
     * Cancel, or a press that never reached a save drops it.
     */
    internal var afterSave: (() -> Unit)? = null
    private var savedOk = false

    /**
     * WDP's `SaveCallsign_DTC(Message: true)`, the app's way: written straight into the cartridge, as WDP writes it.
     * [zeroOk]: the pilot has already agreed that this save sets placed points to 0 (A14) — otherwise it asks first,
     * whoever pressed Save (the toolbar, an attack page's Save to DTC, the card's entries).
     */
    private suspend fun saveNow(zeroOk: Boolean = false): String {
        savedOk = false
        val msg = saveCartridge(zeroOk)
        val then = afterSave
        // a question is on screen: its answer saves (or cancels), and decides
        if (then != null && !asking) {
            afterSave = null
            if (savedOk) runCatching { then() }
        }
        return msg
    }

    private suspend fun saveCartridge(zeroOk: Boolean): String {
        asking = false
        val st = state
        if (loadedText == null || st == null) return "No callsign.ini is loaded, so there is nothing to save."
        val edits = edits() ?: return "The cartridge could not be written from this page's data."
        if (edits.isEmpty()) {
            page.c("lblCampSaved").visible = true
            savedOk = true
            return "${st.file}: nothing had changed."
        }
        val gone = if (zeroOk) emptyList() else zeroed()
        if (gone.isNotEmpty()) {
            askZeroing(st.file ?: "the cartridge", gone)
            asking = true
            return "Nothing was saved yet: this save would set ${gone.joinToString(", ")} to 0. Answer the question on the page."
        }
        status = "Saving ${st.file}…"
        version++
        val te = teRef()
        val file = cartridgeFile
        val back = try {
            when {
                file != null -> fileSave(file, st, edits)
                te != null -> source.saveTe(st.callsign, edits, te)
                else -> source.save(st.callsign, edits)
            }
        } catch (e: Throwable) { null }
        if (back == null) { status = "The PC could not be reached, so nothing was saved."; version++; return status!! }
        if (back.error != null) { status = back.error; version++; return back.error }
        // the file as it is now becomes the page's: what was saved is no longer an edit
        if (back.text != null) open(back)
        page.c("lblCampSaved").visible = true
        justSaved = true
        savedOk = true
        status = null
        // the file really changed, so kneeboards EZBoards made from it are now behind: the answer offers to redo them
        if (back.modified != st.modified) WdpCartridge.generateBoards = source.generateBoards()
        version++
        loadedText?.let { t -> runCatching { onSaved?.invoke(t) } }
        // the TE's own mission file (A13): written, or the PC's own sentence. A refusal ends "Only your cartridge was
        // saved."; "no mission file of its own", "already held" and "None of the changes…" are notes, not errors.
        val mission = back.mission?.let { r ->
            if (r.written) "${r.file.ifBlank { "The mission's own file" }} was written too (its targets, lines and PPTs), as BMS loads it over the cartridge in a TE."
            else r.reason?.takeIf { it.isNotBlank() } ?: "The mission's own file was not written."
        }
        return listOfNotNull(back.message ?: "${st.file} saved.", mission).joinToString("\n\n")
    }

    // ---------------------------------------------------------------- a Callsign.ini opened from anywhere (MAIN)

    /** [f] as the page's cartridge: its text as it is on the PC now. */
    private suspend fun fileState(f: com.bmscompanion.app.data.PickedFile): CartridgeState {
        val a = f.readText()
        val text = a.value
        if (text == null || a.error != null) {
            return CartridgeState(available = false, file = f.name, path = f.path, pptIni = state?.pptIni, error = "${f.name} could not be read: ${a.error ?: "the PC sent nothing"}")
        }
        return CartridgeState(available = true, callsign = null, file = f.name, path = f.path, text = text, pptIni = state?.pptIni)
    }

    /**
     * Save to DTC into a Callsign.ini opened from outside `User\Config`: the edited keys onto the file as it is now
     * ([DtcEdits.apply], Windows' own rules), written back whole through the PC — directly, as WDP writes it.
     */
    private suspend fun fileSave(f: com.bmscompanion.app.data.PickedFile, st: CartridgeState, edits: List<CartridgeEdit>): CartridgeState {
        val now = f.readText()
        val before = now.value ?: return st.copy(error = "${f.name} could not be read before saving: ${now.error ?: "the PC sent nothing"}")
        val after = DtcEdits.apply(before, edits)
        if (after == before) return st.copy(text = before, message = "${f.name}: nothing had changed.", error = null)
        val w = f.writeText(after)
        if (w.error != null) return st.copy(error = "${f.name} was not saved: ${w.error}")
        return st.copy(text = after, message = "${f.name} saved.", error = null)
    }

    /** The PC's `User\Config`, where a Callsign.ini is the cartridge of the pilot it is named after. */
    private suspend fun configDir(): String? =
        state?.path?.takeIf { cartridgeFile == null }?.let { p -> p.substring(0, p.length - WdpFiles.nameOf(p).length).trimEnd('\\', '/') }
            ?: runCatching { MissionLink.filesStat("@config").value?.takeIf { it.dir }?.path }.getOrNull()

    private suspend fun inConfig(f: com.bmscompanion.app.data.PickedFile): Boolean =
        configDir()?.let { f.folder.trimEnd('\\').equals(it.trimEnd('\\'), ignoreCase = true) } == true

    /**
     * MAIN's **Open Callsign.ini File** (`btnGetCampFile_Click`): WDP's window in `User\Config` on the pilot's own
     * file, `Callsign DTC File|*.ini`, and the file picked becomes the page's cartridge (WDP's `strCallsignFile`). A
     * pilot's file in `User\Config` opens as that pilot's, as Change … Accept does; one from anywhere else is read and
     * saved where it is.
     */
    internal suspend fun openCallsignFile(): String? {
        val f = com.bmscompanion.app.data.PcFiles.open(
            "Open Callsign.ini File", "@config", com.bmscompanion.app.data.FileFilter.parse("Callsign DTC File|*.ini"),
            (state?.callsign ?: pilotText).takeIf { it.isNotBlank() },
        ) ?: return null
        val msg = if (inConfig(f)) {
            cartridgeFile = null
            reloadNow(f.baseName)
        } else {
            // read first: a file that cannot be read leaves the page, and the file it saves to, as they were
            val probe = fileState(f)
            if (!probe.available) {
                val why = probe.error ?: "${f.name} could not be read."
                WdpDialogs.message("Caution", why)
                return why
            }
            cartridgeFile = f
            reloadNow(null)
        }
        openedOk = loadedText != null && (state?.file ?: "").equals(f.name, ignoreCase = true)
        if (!openedOk) WdpDialogs.message("Caution", msg)
        else status = msg
        version++
        return msg
    }

    /** The last [openCallsignFile] made the file picked the page's cartridge. */
    private var openedOk = false

    /**
     * MAIN's **Save Callsign.ini File** (`btnSaveCampFile_Click`): WDP's window in `User\Config` on the pilot's name,
     * `Callsign DTC File|*.ini`, and the page written into the file picked — directly, no copy first, as WDP's
     * `SaveCallsign_DTC` writes it: every key the page holds, key by key onto what that file holds (a new file starts
     * from the cartridge as it was loaded, so it is a whole cartridge rather than WDP's sections alone). The file saved
     * is the page's cartridge from then on, as it is WDP's. The cartridge's own file is saved as Save to DTC saves it.
     */
    internal suspend fun saveCallsignFile(): String? {
        val text = loadedText
        if (text == null) { WdpDialogs.message("Save Callsign.ini File", "No callsign.ini is loaded, so there is nothing to save."); return null }
        val f = com.bmscompanion.app.data.PcFiles.save(
            "Save Callsign.ini File", "@config", state?.callsign ?: state?.file?.substringBeforeLast('.') ?: pilotText,
            com.bmscompanion.app.data.FileFilter.parse("Callsign DTC File|*.ini"), defaultExt = "ini",
        ) ?: return null
        if (f.path.equals(state?.path, ignoreCase = true)) {
            val msg = saveNow()
            if (!asking) WdpCartridge.answer("Message", msg)
            return msg
        }
        val existing = WdpFiles.existingText(f, "Save Callsign.ini File") ?: return null
        val out = DtcSave.saveCallsign(existing.ifEmpty { text }, page.m) { page.applyHarm() }
        if (out.threw) { WdpDialogs.message("Save Callsign.ini File", "The cartridge could not be written from this page's data."); return null }
        if (!WdpFiles.writeText(f, out.text, "Save Callsign.ini File")) return null
        // the file saved is the page's cartridge now, as WDP's strCallsignFile
        val msg = if (inConfig(f)) { cartridgeFile = null; reloadNow(f.baseName) } else { cartridgeFile = f; reloadNow(null) }
        val said = "${f.name} saved." + if (loadedText == null) "\n\n$msg" else ""
        WdpCartridge.answer("Message", said)
        return said
    }

    // ---------------------------------------------------------------- WDP's backup files (Load and Backup on each tab)

    /**
     * A tab's **Backup** (`Save_File`): WDP's window in its `Files\<part>` folder on the last file of that kind, the
     * part's own type (`*.ews`, `*.pth` …), and the part written into the file picked as WDP writes it
     * ([DtcFiles.backupText]).
     */
    internal suspend fun saveBackup(part: DtcFiles.Part): String? {
        val b = DtcFiles.backup(part)
        val f = WdpFiles.save(b.saveTitle, WdpFiles.files(b.folder), b.filter, WdpFiles.nameOf(WdpFiles.last(b.kind)), defaultExt = b.ext) ?: return null
        val existing = WdpFiles.existingText(f, b.saveTitle) ?: return null
        val text = try { DtcFiles.backupText(page, part, existing) } catch (e: Throwable) {
            WdpDialogs.message(b.saveTitle, "The ${part.label} could not be written from this page's data.")
            return null
        }
        if (!WdpFiles.writeText(f, text, b.saveTitle)) return null
        WdpFiles.remember(b.kind, f)
        status = "The ${part.label} ${isAre(part)} saved in ${f.name}."
        version++
        return f.path
    }

    /**
     * A tab's **Load** (`Open_File`): WDP's window in its `Files\<part>` folder, and the file picked read into the page
     * as WDP reads it — a file without its section header read as if it had one (`CheckXHeader`). Save to DTC then
     * writes it to Falcon BMS. A Load that picks no file offers BMS's own defaults (EWS, MFD, HARM) and the copies an
     * earlier version kept in the app's settings, when there are any.
     */
    internal suspend fun openBackup(part: DtcFiles.Part): String? {
        val b = DtcFiles.backup(part)
        val f = WdpFiles.open(b.openTitle, WdpFiles.files(b.folder), b.filter, WdpFiles.nameOf(WdpFiles.last(b.kind)).ifEmpty { null })
        if (f == null) { loadCopy(part, lead = "No file was picked. "); return null }
        val raw = WdpFiles.readText(f, b.openTitle) ?: return null
        WdpFiles.remember(b.kind, f)
        val text = b.header?.let { WdpFiles.withHeader(raw, it) } ?: raw
        guard { DtcFiles.load(page, part, text, pptPairs()) }
        status = "The ${part.label} from ${f.name} ${isAre(part)} on the page. Save to DTC writes them."
        return f.path
    }

    // ---------------------------------------------------------------- the PPT management tab's files

    /** `btnSavePersonal_Click`: WDP's "Save Personal PPT files" in `Files\PPT\Personal`, `*.ppi`, as WDP's `SavePPT` writes it. */
    internal suspend fun savePersonalPpt(): String? {
        val f = WdpFiles.save(
            "Save Personal PPT files", WdpFiles.files("PPT\\Personal"), "ppi files (*.ppi)|*.ppi",
            WdpFiles.nameOf(WdpFiles.last("ppi")), defaultExt = "ppi",
        ) ?: return null
        if (!WdpFiles.writeText(f, DtcFiles.pptText(pptTable), "Save Personal PPT files")) return null
        WdpFiles.remember("ppi", f)
        WdpDialogs.message("Message", f.path + "\nSaved OK")
        return f.path
    }

    /** `btnLoadPersonal_Click`: WDP's "Load Personal PPT files", read as WDP's `ReadPPT` reads a table. */
    internal suspend fun loadPersonalPpt(): String? {
        val f = WdpFiles.open("Load Personal PPT files", WdpFiles.files("PPT\\Personal"), "ppi files (*.ppi)|*.ppi", WdpFiles.nameOf(WdpFiles.last("ppi")).ifEmpty { null })
        if (f == null) {
            // the table an earlier version kept in the app's settings
            Repo.getString(PPT_KEY)?.let { kept ->
                WdpDialogs.message("Load Personal PPT files", "No file was picked. An earlier version kept a PPT table in BMS Companion's own settings.\n\nLoad that instead?", listOf("Load it", "Cancel")) { a ->
                    if (a == "Load it") guard { usePptTable(kept, "Personal PPT table kept in BMS Companion") }
                }
            }
            return null
        }
        val text = WdpFiles.readText(f, "Load Personal PPT files") ?: return null
        if (DtcFiles.pptTable(text).isEmpty()) { WdpDialogs.message("Error", f.path + "\nis not a correct ppt.ini file"); return null }
        WdpFiles.remember("ppi", f)
        guard { usePptTable(text, f.name) }
        return f.path
    }

    /** `btnBrowsePPT_Click`: WDP's "Browse PPT.ini file" in the campaign folder on `ppt`, `ini files (*.ini)`. */
    internal suspend fun browsePptIni(): String? {
        val f = WdpFiles.open("Browse PPT.ini file", "@campaign", "ini files (*.ini)|*.ini", "ppt", makeFolder = false) ?: return null
        val text = WdpFiles.readText(f, "Browse PPT.ini file") ?: return null
        if (DtcFiles.pptTable(text).isEmpty()) { WdpDialogs.message("Error", f.path + "\nis not a correct ppt.ini file"); return null }
        guard { usePptTable(text, f.name) }
        return f.path
    }

    /** The question a save asks when it would set placed points to 0 (A14): once, before anything is written. */
    private fun askZeroing(file: String, gone: List<String>) {
        WdpDialogs.message(
            "Save to DTC",
            "This save sets ${gone.joinToString(", ")} to 0,0,0 in $file. The cartridge as it was loaded has " +
                (if (gone.size == 1 && !gone[0].contains(',') && !gone[0].contains('-')) "that point" else "those points") +
                " placed, and once saved the jet loads nothing there (a target BMS marked comes back only by marking it again).\n\n" +
                "Save anyway?",
            listOf("Save anyway", "Cancel"),
        ) { answer ->
            if (answer == "Save anyway") launch { val msg = saveNow(zeroOk = true); if (!asking) WdpCartridge.answer("Save to DTC", msg) }
            else afterSave = null
        }
    }

    /**
     * Told the cartridge's text each time a save has written it, whichever button saved: the Planner hands it to the
     * DataCard, which then measures its own entries against the file as saved (so the count on Save to DTC falls to 0).
     */
    var onSaved: ((text: String) -> Unit)? = null

    /**
     * The Planner's **Save to DTC** (the toolbar, R3-PLAN A2): the DataCard's entries ([card], when it has any) go
     * into this page's boxes first, then the cartridge is saved (asking first only when the save would zero placed
     * points) and the answer shown.
     */
    fun saveToDtc(card: WdpCartridge.CardEntries?) {
        launch {
            val msg = if (card != null) writeCardEntries(card, save = true) else saveNow()
            // a press that never reached a save (no cartridge loaded) populates nothing later either
            if (!asking) afterSave = null
            if (!asking) WdpCartridge.answer("Save to DTC", msg)
        }
    }

    /** Runs EZBoards on the PC, as the Kneeboards page's GENERATE NOW does; null where EZBoards is not set up there. */
    fun generateBoards(): (() -> Unit)? = source.generateBoards()

    /**
     * An attack page's Save to DTC (`cntDTC.Profiles` then `SaveCallsign_DTC`): its offset aim points become the
     * cartridge's NAV OFFSETS and the cartridge is saved. [then] runs once the save has written the cartridge (at once,
     * or after the question a save that would zero placed points asks — [afterSave]); never when it fails.
     */
    private suspend fun saveNavOffsets(profile: String, offsets: Map<String, WdpCartridge.NavOffset>, sel: Int, then: (() -> Unit)? = null): String {
        if (loadedText == null) reloadNow(null)
        if (loadedText == null) return status ?: "No callsign.ini is loaded."
        stageNavOffsets(profile, offsets, sel)?.let { return it }
        if (then != null) afterSave = then
        return saveNow()
    }

    /**
     * An attack's nav offsets onto the page, without saving (1.3.8): the one path an attack page's Save to DTC, the
     * card's Save DTC and the card's profile buttons take, so the two saves write byte-identical `[NAV OFFSETS]`. [offsets] are the
     * four lines of the reference in use (D3); **the other reference's lines are cleared** (D88): VIP and VRP cannot be
     * used together (Dash-34 p.426: "OA and PUP geometry will change if one mode is selected but the offsets were
     * intended for the other"), so with VIP the VRP and VRP pull-up lines go to steerpoint 0 and the target's OA pair is
     * taken off, with VRP the VIP lines and the IP's OA pair. Then the profile's table is the DTC page's
     * (`cntDTC.Profiles`). Null when it was staged, else why not.
     */
    internal fun stageNavOffsets(profile: String, offsets: Map<String, WdpCartridge.NavOffset>, sel: Int): String? {
        if (loadedText == null) return status ?: "No callsign.ini is loaded."
        val into: DtcNavOffsets = when (profile) {
            "PopUp" -> page.m.popUpNav
            "HADB" -> page.m.hadbNav
            "TOSS" -> page.m.tossNav
            else -> return "Unknown profile: $profile"
        }
        for (o in listOf(into.vip, into.vipPup, into.vrp, into.vrpPup, into.oa1_1, into.oa2_1, into.oa1_2, into.oa2_2)) {
            o.stpt = 0; o.bearing = 0f; o.range = 0; o.elv = 0
        }
        into.modesel = sel
        val keep = if (sel == 1) com.bmscompanion.app.data.wdp.AttackGeometry.VIP_KEYS else com.bmscompanion.app.data.wdp.AttackGeometry.VRP_KEYS
        for ((key, o) in offsets) {
            if (key !in keep) continue
            val t = when (key) {
                "VIP" -> into.vip; "VIPPUP" -> into.vipPup; "VRP" -> into.vrp; "VRPPUP" -> into.vrpPup
                "OA1_1" -> into.oa1_1; "OA2_1" -> into.oa2_1; "OA1_2" -> into.oa1_2; "OA2_2" -> into.oa2_2
                else -> continue
            }
            t.stpt = o.stpt; t.bearing = o.bearing; t.range = o.rangeFt; t.elv = o.elevFt
        }
        page.strProfile = profile
        page.profiles()
        page.changed()
        version++
        return null
    }

    /** The current nav offsets of a staged profile, for the checks: the DTC page's `[NAV OFFSETS]` as a save would write them. */
    internal fun navOffsetsText(): String? {
        val text = cartridgeText() ?: return null
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim().equals("[NAV OFFSETS]", ignoreCase = true) }
        if (start < 0) return ""
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    // ---------------------------------------------------------------- the attack the cartridge holds

    /** Steerpoints the pilot placed or edited in this session (slots 1-24), kept until another mission or a re-read. */
    private val sessionSlots = LinkedHashSet<Int>()

    /**
     * The steerpoints (1-24) placed or edited on this page in this session, at their position now: the attack pages
     * plan from these whatever the Precision answer (`WdpMission.placed`, "IP STPT at the VRP"). Read in composition,
     * it follows the page.
     */
    internal fun placedThisSession(): Map<Int, com.bmscompanion.app.data.mission.DtcPoint> {
        @Suppress("UNUSED_VARIABLE") val v = version
        val b = baseline ?: return emptyMap()
        if (loadedText == null) return emptyMap()
        for (n in 1..24) {
            val s = page.m.stpt[n - 1]
            val o = b.m.stpt[n - 1]
            if (abs(s.falconX - o.falconX) > 0.5f || abs(s.falconY - o.falconY) > 0.5f) sessionSlots += n
        }
        return sessionSlots.sorted().mapNotNull { n ->
            val s = page.m.stpt[n - 1]
            // a position to plan from, not a target of the mission: it must not become a page's default TGT STPT
            // (WdpMission.defaultStpt), which would move every attack page onto the IP just placed
            if (DtcFromMission.stptEmpty(s)) null
            else n to com.bmscompanion.app.data.mission.DtcPoint(
                n = n, x = s.falconY.toDouble(), y = s.falconX.toDouble(), altFt = abs(s.falconZ).toDouble(),
                action = s.action, isTarget = false, name = s.target,
            )
        }.toMap()
    }

    /**
     * Steerpoint [n] (1-24) as the page holds it now, feet north and east, or null while it is empty or no cartridge is
     * loaded (an attack page's "IP STPT at the VRP" line, `AttackSelection.ipNotice`). Read in composition, it follows the page.
     */
    internal fun stptAt(n: Int): Pair<Double, Double>? {
        @Suppress("UNUSED_VARIABLE") val v = version
        if (loadedText == null || n !in 1..24) return null
        val s = page.m.stpt[n - 1]
        return if (DtcFromMission.stptEmpty(s)) null else s.falconY.toDouble() to s.falconX.toDouble()
    }

    /**
     * The attack a cartridge model's `[NAV OFFSETS]` lay out ([AttackDrawing.fromCartridge]): each line from its own
     * steerpoint, placed by the cartridge, else by the mission. [profile] shapes the path when it is known.
     */
    internal fun cartridgeAttack(m: com.bmscompanion.app.data.wdp.DtcModel, profile: String?): com.bmscompanion.app.data.mission.AttackOverlay? {
        val pic = DtcFromMission.picture(m).stpts.toMap()
        fun at(n: Int): Pair<Double, Double>? =
            pic[n]?.let { it.north to it.east } ?: mission.steerpoint(n)?.takeIf { it.placed() }?.let { it.x to it.y }
        return com.bmscompanion.app.data.mission.AttackDrawing.fromCartridge(
            com.bmscompanion.app.data.wdp.PlannerMap.navOffsetsOf(m.nav), ::at, profile, mission.theater?.id.orEmpty(),
        )
    }

    /** The attack the cartridge **as saved** lays out (the file as loaded or last saved), or null with none. */
    internal fun savedAttack(profile: String?): com.bmscompanion.app.data.mission.AttackOverlay? {
        val b = baseline ?: return null
        if (loadedText == null) return null
        return cartridgeAttack(b.m, profile)
    }

    // ---------------------------------------------------------------- what the page shows

    override fun values(hidden: List<String>): WdpValues {
        @Suppress("UNUSED_VARIABLE") val v = version
        val m = LinkedHashMap<String, String>()
        for (h in hidden) m[h] = "hidden"
        for (k in page.controls()) {
            val n = k.name
            if (k.kind == 'X' || k.kind == 'D') continue
            if (!k.visible) { m[n] = "hidden"; continue }
            when (k.kind) {
                'K', 'R' -> m[n] = if (k.checked) "checked" else "unchecked"
                'C' -> { m[n] = k.selectedItem ?: k.text; m["$n.items"] = k.items.joinToString("\n") }
                'U' -> m[n] = numberText(k.value.toString(), k.decimals)
                'L', 'B', 'T', 'M' -> { m[n] = "shown"; m["$n.text"] = k.text }
                else -> m[n] = "shown"
            }
            if (!k.enabled) m["$n.enabled"] = "false"
            k.fore?.let { m["$n.fore"] = "#" + DtcPage.argb(it) }
            k.back?.let { m["$n.back"] = "#" + DtcPage.argb(it) }
        }
        // the tabs' red/green lamps say what the Save to DTC count says: red while a save would change the cartridge.
        // WDP turns them red on any press that touches a table, even one that changed nothing, which here would stand
        // beside a count of 0.
        if (page.blnCallsignDtcLoaded && loadedText != null) {
            val dirty = unsaved > 0
            for (p in DtcPage.CALLSIGN_PANELS) {
                m[p + "Red"] = if (dirty) "shown" else "hidden"
                m[p + "Green"] = if (dirty) "hidden" else "shown"
            }
        }
        for ((n, t) in pending) { m[n] = "shown"; m["$n.text"] = t }
        m["tabDTC"] = tab

        // RADIO/NAV: a preset's number in brackets, "Preset [3]  275.800", so that it is never read as part of the
        // frequency beside it (WDP's "Preset 3:" ran straight into the value: "Preset 3:275.800"); COMM 1/2's active preset
        // says its number the same way ([bracketed], read back in onValue). The airport's SET boxes keep the bare number:
        // at 36 px, "[15]" does not fit beside the drop-down arrow
        // (the no-break space keeps a gap before the value: the label is right-aligned against it)
        for (i in 1..20) { m["lblUHF_$i.text"] = "Preset [$i]$NBSP"; m["lblVHF_$i.text"] = "Preset [$i]$NBSP" }
        for (n in PRESET_BOXES) if (m[n] != "hidden") {
            // a box the page has not touched yet (no cartridge) shows the designer's value, bracketed the same way
            val d = DtcDesigner.all[n]
            m[n] = bracketed(m[n] ?: d?.text)
            m["$n.items"] = (m["$n.items"] ?: d?.items?.joinToString("\n")).orEmpty().split('\n').joinToString("\n") { bracketed(it) }
        }

        // MAIN: the pilot, the file, and what a save does
        val st = state
        m["lblPilotName"] = "shown"; m["lblPilotName.text"] = st?.callsign ?: ""
        // WDP's "DTC ID" is a number from its designer (126534) that nothing ever sets; the pilot's name was put there,
        // and a callsign does not fit its box ("VIPER 2'"). The pilot line above says it whole.
        m["lblDTC_ID"] = "hidden"; m["lblDTC_ID_Val"] = "hidden"
        m["txtPilot"] = if (pilotEditing) "shown" else "hidden"
        m["txtPilot.text"] = pilotText
        // WDP's PilotButton: txtPilot.BringToFront() and Focus(), so the box is over the pilot's name and takes the
        // keys at once (drawn under the name, the name took the press and the box could not be typed in)
        if (pilotEditing) { m["txtPilot.front"] = "true"; m["txtPilot.focus"] = "true" }
        m["btnPilot.text"] = if (pilotEditing) "Accept" else "Change"
        m["lblLoadError"] = "shown"
        m["lblLoadError.text"] = statusLine()
        m["lblLoadError.fore"] = if (st?.error != null || status?.startsWith("The PC") == true) "Orange" else "White"
        m["lblTEfName"] = "hidden"
        if (loading) { m["lblCampLoaded.text"] = "Loading…"; m["lblCampLoaded.back"] = "Orange" }

        // Targets: the list, and the name WDP gives its picture of it (its caption WDP's Camp_TE leaves unset)
        if (page.blnCallsignDtcLoaded) { m["lblTGTCamp_TE"] = "shown"; m["lblTGTCamp_TE.text"] = "Callsign.ini Targets" }
        m["dgvTargets.rows"] = page.targetRows.joinToString("\n") { r -> r.joinToString("\t") { it ?: "" } }
        m["dgvTargets.selected"] = targetRow.toString()
        m["txtTargetList"] = "shown"; m["txtTargetList.text"] = pending["txtTargetList"] ?: targetListName

        // PPT management: the table of types and the edit boxes for the selected one
        m["dgvPPT.rows"] = pptTable.joinToString("\n") { (code, name, range) -> "$code\t${DtcNum.f1(range / NM_TO_FT)}\t$name" }
        m["dgvPPT.selected"] = pptRow.toString()
        val sel = pptTable.getOrNull(pptRow)
        val name = pptEdit["txtName"] ?: sel?.second ?: ""
        val range = pptEdit["txtRange"] ?: sel?.let { DtcNum.f1(it.third / NM_TO_FT) } ?: "0.0"
        val code = pptEdit["txtCode"] ?: sel?.first ?: ""
        m["txtName"] = "shown"; m["txtName.text"] = name
        m["txtRange"] = "shown"; m["txtRange.text"] = range
        m["txtCode"] = "shown"; m["txtCode.text"] = code
        val nm = range.trim().toDoubleOrNull()
        m["lblRangeFeet"] = "shown"; m["lblRangeFeet.text"] = nm?.let { "${(it * NM_TO_FT).roundToInt()}'" } ?: ""
        m["lblRangeKm"] = "shown"; m["lblRangeKm.text"] = nm?.let { DtcNum.f1(it * 1.852) + " km" } ?: ""
        m["lblPPTfile"] = "shown"; m["lblPPTfile.text"] = pptSource

        airportValues(m)

        // no cartridge on the page (no pilot yet, the PC could not read it): what would edit it is drawn disabled, as an
        // edit could never be saved, and each tab's file line says so (MAIN's status line gives the reason)
        if (loadedText == null) {
            // every control of the designer's, not only those the page has touched yet: with no cartridge the RADIO/NAV
            // boxes were never filled, so they were not among the page's controls, and a pick in one edited the page
            for (d in DtcDesigner.all.values) {
                if (d.kind !in "BTMUCKR" || worksWithoutCartridge(d.name) || m[d.name] == "hidden") continue
                m[d.name + ".enabled"] = "false"
            }
            m["dgvTargets.enabled"] = "false"
            for (n in DtcPage.FILE_LABELS) if (m[n] != "hidden") { m[n] = "shown"; m["$n.text"] = if (loading) "Loading…" else "No cartridge loaded" }
        }

        // what WDP does that the app does not is not on the page (see REMOVED); WDP's Save DTC on five tabs is (SAVE_DTC)
        for (n in REMOVED) m[n] = "hidden"
        // From mission… where nine tabs had their own Save DTC (FROM_MISSION); drawn disabled with no cartridge, as above
        for (n in FROM_MISSION) { m[n] = "shown"; m["$n.text"] = FROM_MISSION_TEXT }
        // Lines: a line laid along the mission's track names it, where WDP's FindArea can only say "Random Line"
        if (loadedText != null) for (k in 1..4) lineName(k)?.let { m["lblLine_$k"] = "shown"; m["lblLine_$k.text"] = "Line $k: $it" }
        // the PPT table buttons, captioned for what they now load
        m["btnBrowsePPT"] = "shown"; m["btnBrowsePPT.text"] = "Theater PPT"
        return WdpValues(m)
    }

    private fun statusLine(): String {
        status?.let { return it }
        val st = state ?: return "Loading the cartridge from the PC…"
        st.error?.let { return it }
        val errors = page.c("lblLoadError").text.takeIf { it.isNotBlank() }?.let { "Could not read: $it. " } ?: ""
        return errors + "Save to DTC writes the settings you change straight into ${st.file}, as WDP does."
    }

    private fun numberText(raw: String, decimals: Int): String =
        if (decimals > 0 && !raw.contains('.')) raw + "." + "0".repeat(decimals) else raw

    // ---------------------------------------------------------------- what the pilot does

    override fun onValue(name: String, value: String) = guard {
        when {
            name == "tabDTC" -> {
                tab = value
                page.click("tabDTC")
                if (value == "tabNavOffsets") page.click("tabNavOffsets")
            }
            name.endsWith(".leave") -> left(name.removeSuffix(".leave"))
            name == "txtPilot" -> { pilotText = value }
            name == "txtTargetList" -> { pending[name] = value }
            name == "txtName" || name == "txtRange" || name == "txtCode" -> { pptEdit[name] = value }
            else -> when (DtcDesigner.all[name]?.kind) {
                'T', 'M' -> { pending[name] = value }
                'C' -> {
                    val k = page.c(name)
                    // a preset box shows its numbers bracketed ([PRESET_BOXES]); the page's items are the bare numbers
                    val item = if (name in PRESET_BOXES) value.trim().removePrefix("[").removeSuffix("]") else value
                    val i = k.items.indexOf(item)
                    if (i >= 0) page.choose(name, i)
                }
                'U' -> number(name, value)
                else -> {}
            }
        }
        Unit
    }

    /** A field left: what was typed goes through the box's mask and its Leave handler, as in WDP. */
    private fun left(name: String) {
        when (name) {
            "txtTargetList" -> pending.remove(name)?.let { targetListName = it }
            "txtPilot", "txtName", "txtRange", "txtCode" -> {}
            // WDP's ArrayComms sets a box that is not a number to "0"; under this box's mask ("100.00") that reads
            // "10 .", which is not a number either, so WDP's own Leave ends in an InvalidCast. A partly typed
            // frequency ("1") leaves the box as it was instead, and a whole one goes through WDP's handler.
            "mxtILS_FREQ" -> pending.remove(name)?.let { typed ->
                val k = page.c(name)
                val was = k.text
                k.text = typed
                if (DataCardNet.isNumeric(k.text)) page.type(name, typed) else k.text = was
            }
            else -> pending.remove(name)?.let { page.type(name, it) }
        }
    }

    /** An up/down box, stepped or typed: the renderer sends the value itself, which is clamped as the box would. */
    private fun number(name: String, value: String) {
        val k = page.c(name)
        var d = runCatching { DtcDec.parse(value.trim()) }.getOrNull() ?: return
        if (d < k.minimum) d = k.minimum
        if (d > k.maximum) d = k.maximum
        page.number(name, d.toString())
    }

    override fun onClick(name: String) = guard {
        val base = name.substringBefore(':')
        // only a press that would do something says why it cannot: a tap on a caption or a value label does nothing,
        // with a cartridge or without one
        if (loadedText == null && !worksWithoutCartridge(base) && acts(name, base)) { disabled(base); return@guard }
        when {
            // txtPilot_KeyDown: Return is Accept
            name == "txtPilot:enter" -> if (pilotEditing) pilot()
            name.startsWith("dgvTargets:") -> targetGrid(name)
            name.startsWith("dgvPPT:") -> {
                pptRow = name.substringAfterLast(':').toIntOrNull() ?: 0
                pptEdit.clear()
            }
            else -> {
                val own = buttons[name]
                if (own != null) own() else pageClick(name)
            }
        }
    }

    /** A control WDP's page code handles itself: check boxes, radio buttons, the clear buttons, the reference knob. */
    private fun pageClick(name: String) {
        val kind = DtcDesigner.all[name]?.kind ?: return
        val k = page.c(name)
        when {
            kind == 'K' || kind == 'R' -> page.click(name)
            k.onClick != null -> {
                if (kind == 'B' && !k.enabled) { disabled(name); return }
                page.click(name)
            }
            kind == 'B' -> disabled(name)
        }
    }

    /** Whether a press on [name] does anything on a loaded page: a handler of the wiring's or the page's, a box, a button. */
    private fun acts(name: String, base: String): Boolean =
        buttons[name] != null || buttons[base] != null || name.startsWith("dgvTargets:") ||
            DtcDesigner.all[base]?.kind?.let { it in "BKRCUTM" } == true ||
            (DtcDesigner.all[base] != null && runCatching { page.c(base).onClick != null }.getOrDefault(false))

    private fun disabled(name: String) {
        val named = DtcDesigner.all[name]?.text?.takeIf { it.isNotBlank() }
            ?: if (name.startsWith("pnlRef")) "The Reference knob" else "This control"
        val what = if (RUNWAY_POINT.matches(name)) "A steerpoint from the runway" else named
        val why = (status ?: state?.error)?.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: ""
        WdpDialogs.message(
            "DTC",
            "$what needs your cartridge (User\\Config\\<callsign>.ini) on the page, and none is loaded.$why\n\n" +
                "Pick the pilot on MAIN (Change), or choose Re-read DTC from BMS in the Save to DTC menu.",
        )
    }

    /**
     * What the page can do with no cartridge on it: change tabs, pick the pilot, read the "?" notes, work on the PPT
     * types (the PPT management tab edits the app's table, not the cartridge) and look an airport up. Everything else
     * edits the cartridge, which with none loaded could never be saved: it is drawn disabled, and a press says why.
     */
    private fun worksWithoutCartridge(name: String): Boolean = name in NO_CARTRIDGE_OK || tabOf(name) == "tabPPTman"

    /** The DTC tab a control of the page sits on (its ancestor whose parent is the tab strip), or null. */
    private fun tabOf(name: String): String? {
        var n = name
        repeat(12) {
            val d = DtcDesigner.all[n] ?: return null
            if (d.parent == "tabDTC") return n
            if (d.parent.isEmpty()) return null
            n = d.parent
        }
        return null
    }

    /** Nothing may throw out of the page: the reason is shown as WDP shows its error boxes. */
    private fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            WdpDialogs.message("Error", "An error has occurred: " + (e.message ?: e::class.simpleName ?: "unknown"))
        }
        version++
    }

    private fun launch(block: suspend () -> Unit) {
        // started on the caller's thread: work that never suspends (a fixture, a message) is done by the time the
        // press returns, and what does suspend comes back on the main thread
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                block()
            } catch (e: Throwable) {
                WdpDialogs.message("Error", "An error has occurred: " + (e.message ?: e::class.simpleName ?: "unknown"))
            }
            version++
        }
    }

    private val buttons: Map<String, () -> Unit> by lazy { buildButtons() }

    private fun buildButtons(): Map<String, () -> Unit> {
        val b = HashMap<String, () -> Unit>()

        // ---- MAIN: the files
        // MAIN's two file buttons: WDP's own windows on the BMS PC's User\Config
        b["btnGetCampFile"] = { launch { openCallsignFile() } }
        b["btnSaveCampFile"] = { launch { saveCallsignFile() } }
        b["btnPilot"] = { pilot() }

        // every tab's Save DTC is the same save, the toolbar's Save to DTC (on the page on the five tabs of SAVE_DTC)
        for (s in SAVE_TABS) {
            b["btnSaveDtc$s"] = { saveToDtc(runCatching { WdpCartridge.cardEntries?.invoke() }.getOrNull()) }
        }
        // …of which nine are From mission… now (FROM_MISSION): what the app knows of the mission, into that tab
        b["btnSaveDtcStpt"] = { fromMission("Steerpoints from the mission", ::fromMissionStpt) }
        b["btnSaveDtcTgt"] = { fromMission("Targets from the mission", ::fromMissionTargets) }
        b["btnSaveDtcLine"] = { fromMission("Lines from the mission", ::fromMissionLines) }
        b["btnSaveDtcPpt"] = { fromMission("PPTs from the mission", ::fromMissionPpts) }
        b["btnSaveDtcOpen"] = { fromMission("Open 1 from the mission") { fromMissionOpen(it, hpn = false) } }
        b["btnSaveDtcHpn"] = { fromMission("Open 2 from the mission") { fromMissionOpen(it, hpn = true) } }
        b["btnSaveDtcRad"] = { fromMission("Radio and nav from the mission", ::fromMissionRadio) }
        b["btnSaveDtcSys"] = { fromMission("Laser codes from the mission", ::fromMissionLaser) }
        b["btnSaveDtcHarm"] = { fromMission("HARM from the mission", ::fromMissionHarm) }

        // the "?" buttons: WDP's own words
        b["btnStptInfo"] = { DtcInfoWindow(INFO_STPT).open() }
        b["btnLineInfo"] = { DtcInfoWindow(INFO_LINE).open() }
        b["btnTgtInfo"] = { DtcInfoWindow(INFO_TGT).open() }
        b["btnPptInfo"] = { DtcInfoWindow(INFO_PPT).open() }
        b["btnOpenInfo"] = { DtcInfoWindow("10 STPTs are open for use.").open() }
        b["btnHpnInfo"] = { DtcInfoWindow("10 STPTs for use with the Harpoon.").open() }

        // ---- STPT, Open 1, Open 2: a steerpoint each
        for (i in 1..24) b["btnChange_$i"] = { changePoint(page.m.stpt[i - 1], "STPT: $i", "Change STPT $i") }
        for (i in 1..9) b["btnOpenChange_$i"] = { changePoint(page.m.open[i - 1], "STPT: ${80 + i}", "Change STPT ${80 + i}") }
        for (i in 1..10) b["btnHpnChange_$i"] = { changePoint(page.m.hpn[i - 1], "STPT: ${89 + i}", "Change STPT ${89 + i}") }
        b["btnRebuildStptList"] = { rebuildSteerpoints() }

        // ---- Lines
        for (n in 31..54) b["btnChangeLine$n"] = { changeLine(n) }
        b["btnChangeArea"] = { changeArea() }

        // ---- PPTs
        for (i in 1..15) b["btnPPT_$i"] = { changePpt(i) }

        // ---- the Load and Backup buttons: WDP's own backup files on the BMS PC
        fun files(load: String, keep: String, part: DtcFiles.Part) { b[load] = { launch { openBackup(part) } }; b[keep] = { launch { saveBackup(part) } } }
        files("btnLoad_Target", "btnSave_Target", DtcFiles.Part.TARGET)
        files("btnLoad_Line", "btnSave_Line", DtcFiles.Part.LINE)
        files("btnLoad_PPT", "btnSave_PPT", DtcFiles.Part.PPT)
        files("btnLoad_Open", "btnSave_Open", DtcFiles.Part.OPEN)
        files("btnLoad_Hpn", "btnSave_Hpn", DtcFiles.Part.HARPOON)
        files("btnLoad_EWS", "btnSave_EWS", DtcFiles.Part.EWS)
        files("btnLoad_MFD", "btnSave_MFD", DtcFiles.Part.MFD)
        files("btnRadio_Load", "btnRadio_Save", DtcFiles.Part.RADIO)
        files("btnSystems_Load", "btnSystems_Save", DtcFiles.Part.SYSTEMS)
        files("btnWeapons_Load", "btnWeapons_Save", DtcFiles.Part.WEAPONS)
        files("btnHarm_Load", "btnHarm_Save", DtcFiles.Part.HARM)

        // ---- PPT management
        b["btnNew"] = { pptTable = pptTable + Triple("", "", 0.0); pptRow = pptTable.size - 1; pptEdit.clear() }
        b["btnDel"] = {
            if (pptRow !in pptTable.indices) WdpDialogs.message("PPT", "Select the type to delete in the list first.")
            else {
                pptTable = pptTable.filterIndexed { i, _ -> i != pptRow }
                pptRow = if (pptTable.isEmpty()) -1 else 0
                pptEdit.clear(); pptTableChanged()
            }
        }
        b["btnApply"] = { applyPptEdit() }
        // WDP's two zone tables, made from the app's own data: the theater's PPT types with the threat reference's
        // radar lock range (Radar Zone) or longest engagement range (Engage Zone) where it knows the system
        b["btnPptDefault"] = { launch { zoneTable(radar = true) } }
        b["btnEngageZone"] = { launch { zoneTable(radar = false) } }
        // WDP's three PPT-table files: a ppt.ini anywhere, and the pilot's own tables in Files\PPT\Personal
        b["btnBrowsePPT"] = { launch { browsePptIni() } }
        b["btnSavePersonal"] = { launch { savePersonalPpt() } }
        b["btnLoadPersonal"] = { launch { loadPersonalPpt() } }

        // ---- RADIO/NAV
        for (i in 1..20) {
            b["btnUHF_$i"] = { changeComm(false, i) }
            b["btnVHF_$i"] = { changeComm(true, i) }
        }
        b["btnBlue"] = { presetRadio(DtcPage.BLUE_UHF, DtcPage.BLUE_VHF) }
        b["btnRed"] = { presetRadio(DtcPage.RED_UHF, DtcPage.RED_VHF) }
        b["btnDefault"] = { defaultRadio() }
        b["btnSelect"] = { selectAirport() }
        b["btnChart"] = { openCharts() }
        b["btnSet_UHF"] = { setTowerFreq(false) }
        b["btnSet_VHF"] = { setTowerFreq(true) }
        b["btnTCN"] = { useTacan() }
        for (i in 1..4) b["btnRWY_$i"] = { useIls(i) }
        b["btnCreatePPT"] = { createPptType() }
        for (i in 1..4) {
            b["lblN_CoordsRwy$i"] = { runwayToStpt(i, center = false) }
            b["lblE_CoordsRwy$i"] = { runwayToStpt(i, center = false) }
        }
        for (i in 1..2) {
            b["lblCntNorthRwy$i"] = { runwayToStpt(i, center = true) }
            b["lblCntEastRwy$i"] = { runwayToStpt(i, center = true) }
        }

        // ---- SYSTEMS: WDP's three ready-made states of the jet
        for ((btn, preset) in listOf("btnRamp" to "Ramp", "btnTaxi" to "Taxi", "btnTakeOff" to "TakeOff")) {
            b[btn] = { DtcFiles.load(page, DtcFiles.Part.SYSTEMS, DtcFiles.SYSTEM_PRESETS.getValue(preset), pptPairs()) }
        }
        return b
    }

    // ---------------------------------------------------------------- from the mission (DtcFromMission)

    /** The theater's towns (for "21 nm southwest of Yongin-si City") and the threat reference, read once per theater. */
    private var geo: GeoLayers? = null
    private var reference: List<Threat> = emptyList()

    /**
     * What the app knows of the mission, in the terms the cartridge holds it ([DtcFromMission.Facts], gathered by
     * [DtcMissionFacts]): the flight plan, the tankers' and AWACS's tracks and stations, the known air defences, the
     * places a point can be set to, the TACANs and ILSs. The Planner's Map page draws these beside [dtcPicture] and puts
     * a pick into the cartridge with [placeLine], [placePpt], [placeSteerpoint], [placeTarget] and [addTargets], as the
     * tabs' From mission… buttons do. [prepareFacts] first, where the theater's data may not have been read yet.
     */
    fun missionFacts(): DtcFromMission.Facts = DtcMissionFacts.gather(
        mission,
        served(),
        runCatching { MissionLink.contacts.value?.contacts }.getOrNull().orEmpty(),
        airports, airportSet, geo, reference,
    )

    /**
     * What the PC serves for the mission Falcon BMS is flying: its printed briefing and the campaign's planned tracks.
     * The checks give their own.
     */
    internal var served: () -> com.bmscompanion.app.data.mission.MissionData? = { runCatching { MissionLink.bmsFiles.value }.getOrNull() }

    /**
     * The comm ladder Default and From mission… load: the mission's briefing's; for a flight opened from a save, the
     * printed briefing's when that flight is the one BMS printed — a save's own briefing has no ladder (only PRINT
     * words one), and the frequencies are the same flight's.
     */
    private fun commLadder(): List<com.bmscompanion.app.data.mission.CommEntry> {
        mission.briefing?.comms.orEmpty().takeIf { it.isNotEmpty() }?.let { return it }
        val f = mission.flight ?: return emptyList()
        if (!f.row.briefed) return emptyList()
        return served()?.briefing?.comms.orEmpty()
    }

    /** Reads what [missionFacts] uses and has not been read yet: the airports, the theater's towns, the threat reference. */
    suspend fun prepareFacts() {
        val t = mission.theater
        loadAirports()
        val id = t?.mapId
        if (geo == null && id != null) geo = runCatching { Repo.geo(id) }.getOrNull()
        if (reference.isEmpty()) reference = runCatching { Repo.threats() }.getOrDefault(emptyList())
        factsFor = t
        factsRead = true
    }

    /** The theater [prepareFacts] last read for: its airports, towns and the threat reference have been asked for. */
    private var factsFor: com.bmscompanion.app.data.Theater? = null
    private var factsRead = false

    /** Whether [prepareFacts] has nothing left to read for this mission, so a From mission… answers at once. */
    private fun factsReady(): Boolean = factsRead && factsFor == mission.theater

    /** The cartridge on the page as the HSD will show it (steerpoints, lines, PPTs); null before one is loaded. */
    fun dtcPicture(): DtcFromMission.Picture? {
        @Suppress("UNUSED_VARIABLE") val v = version
        return if (loadedText == null) null else DtcFromMission.picture(page.m)
    }

    /** The PPT types the options are typed from: the PPT tab's table (the theater's own unless the pilot chose another). */
    private fun pptTypes() = pptTable.ifEmpty { theaterPpt }

    /** The cartridge's model on the page, null before one is loaded: what the checks measure an action against. */
    internal val model: com.bmscompanion.app.data.wdp.DtcModel? get() = page.m.takeIf { loadedText != null }

    /**
     * A new mission or a switch of mode (MissionEpoch → [WdpSession.resetAttack]; 1.3.8): the delivery data leaves the
     * page, as the PC clears it from the file at the same moment (`CartridgeStore.resetForSwitch`) — every
     * `[NAV OFFSETS]` key of the cartridge as loaded and as edited here (Modesel none, VIP/VRP zeros, no offset aim
     * point; [com.bmscompanion.app.data.mission.Leftovers.navClearEdits]) and the offsets the attack pages handed over.
     * The pilot's other edits stay on the page, still to be saved. Nothing is saved here.
     */
    internal fun clearDeliveryForMission() {
        fun zero(n: com.bmscompanion.app.data.wdp.DtcNavOffsets) {
            n.modesel = 0
            for (o in listOf(n.vip, n.vipPup, n.vrp, n.vrpPup, n.oa1_1, n.oa2_1, n.oa1_2, n.oa2_2)) { o.stpt = 0; o.bearing = 0f; o.range = 0; o.elv = 0 }
        }
        for (p in listOfNotNull(page, baseline)) { zero(p.m.popUpNav); zero(p.m.hadbNav); zero(p.m.tossNav) }
        val text = loadedText
        val st = state
        if (text == null || st == null || loading) { version++; return }
        val e = try { edits() } catch (_: Throwable) { null }
        val navEdited = e?.any { it.section.trim().equals("NAV OFFSETS", ignoreCase = true) } == true
        val clean = com.bmscompanion.app.data.mission.Leftovers.navClearEdits(text)
        if (clean.isEmpty() && !navEdited) { version++; return }
        val other = e?.filterNot { it.section.trim().equals("NAV OFFSETS", ignoreCase = true) }.orEmpty()
        val newText = if (clean.isEmpty()) text else DtcEdits.apply(text, clean)
        val keepConfirm = page.confirmClear
        state = st.copy(text = newText)
        loadedText = newText
        baseline = freshPage(newText, st)
        baseSaved = null
        page = freshPage(if (other.isEmpty()) newText else DtcEdits.apply(newText, other), st).also { it.confirmClear = keepConfirm }
        recogniseLines()
        version++
    }

    /** The PPT types From mission… types the sites with ([pptTypes]), for the checks. */
    internal val pptTypesInUse: List<Triple<String, String, Double>> get() = pptTypes()

    fun lineOptions(f: DtcFromMission.Facts = missionFacts()): List<DtcFromMission.LineOption> = DtcFromMission.lineOptions(f)

    fun pptOptions(f: DtcFromMission.Facts = missionFacts()): DtcFromMission.PptOptions =
        DtcFromMission.pptOptions(f, pptTypes(), page.m.takeIf { loadedText != null })

    /** What each line was laid along from the mission (its name and points), for the tab's "Line 1: Copper2 tanker track". */
    private val lineNames = HashMap<Int, Pair<String, List<DtcFromMission.Pt>>>()

    /** The name of what line [k] holds, while it is still what was laid there from the mission. */
    private fun lineName(k: Int): String? {
        val (name, pts) = lineNames[k] ?: return null
        return name.takeIf { same(DtcFromMission.linePoints(page.m, k), pts) }
    }

    private fun same(a: List<DtcFromMission.Pt>, b: List<DtcFromMission.Pt>) = a.size == b.size && a.indices.all { a[it].dist(b[it]) < 50.0 }

    /** A cartridge just opened: its lines named where they are the mission's (a track put there and saved before). */
    private fun recogniseLines() {
        lineNames.clear()
        val opts = runCatching { DtcFromMission.lineOptions(missionFacts()) }.getOrDefault(emptyList())
        for (k in 1..4) DtcFromMission.recognise(page.m, k, opts)?.let { lineNames[k] = it.label to it.points }
    }

    private fun noCartridge(): String = status ?: state?.error ?: "No callsign.ini is loaded, so there is nothing to put it into."

    /**
     * Line [line] (1-4) laid along [option]. A line that holds other points is replaced only once the pilot says so:
     * with [ask] the question goes on screen and nothing changes until it is answered, and [done] then hears what was
     * done. Returns what was done, or that the question is up ([asked]).
     */
    fun placeLine(line: Int, option: DtcFromMission.LineOption, ask: Boolean = true, done: ((String) -> Unit)? = null): String {
        if (loadedText == null) return noCartridge()
        if (line !in 1..4) return "There is no line $line: the cartridge holds lines 1 to 4."
        val had = DtcFromMission.linePoints(page.m, line)
        if (ask && had.isNotEmpty() && !same(had, option.points)) {
            val named = lineName(line)?.let { " ($it)" } ?: ""
            WdpDialogs.message(
                "Line $line",
                "Line $line holds ${had.size} points$named. Replace them with ${option.label}?",
                listOf("Replace", "Cancel"),
            ) { a -> if (a == "Replace") guard { val r = placeLine(line, option, ask = false); done?.invoke(r) } }
            return "Line $line holds points: $ASKED to replace them."
        }
        DtcFromMission.writeLine(page.m, line, option.points)
        lineNames[line] = option.label to option.points
        relabel()
        return "Line $line: ${option.label}. Save to DTC writes it."
    }

    /** PPT [slot] (56-70) set to [option]; a PPT that holds a point is replaced only once the pilot says so ([placeLine]). */
    fun placePpt(slot: Int, option: DtcFromMission.PptOption, ask: Boolean = true, done: ((String) -> Unit)? = null): String {
        if (loadedText == null) return noCartridge()
        if (slot !in 56..70) return "There is no PPT $slot: the cartridge holds PPT 56 to 70."
        if (ask && !DtcFromMission.pptEmpty(page.m, slot)) {
            val p = page.m.ppt[slot - 56]
            val what = p.name?.takeIf { it.isNotBlank() } ?: p.code?.takeIf { it.isNotBlank() } ?: "a point"
            WdpDialogs.message(
                "PPT $slot",
                "PPT $slot holds $what. Replace it with ${option.label}?",
                listOf("Replace", "Cancel"),
            ) { a -> if (a == "Replace") guard { val r = placePpt(slot, option, ask = false); done?.invoke(r) } }
            return "PPT $slot holds a point: $ASKED to replace it."
        }
        DtcFromMission.writePpt(page.m, slot, option)
        page.fillLabelsPPT()
        relabel()
        return "PPT $slot: ${option.label}. Save to DTC writes it."
    }

    /** The options as [DtcFromMission.planPpts] placed them, the pilot having seen the plan. */
    private fun writePpts(plan: DtcFromMission.PptPlan) {
        for ((slot, o) in plan.placements) DtcFromMission.writePpt(page.m, slot, o)
        page.fillLabelsPPT()
        relabel()
    }

    /** [options] into the free PPTs, in order, none replaced; what does not fit is named in the answer. */
    fun addPpts(options: List<DtcFromMission.PptOption>): String {
        if (loadedText == null) return noCartridge()
        val plan = DtcFromMission.planPpts(page.m, DtcFromMission.onePerSite(options))
        writePpts(plan)
        val placed = if (plan.placements.isEmpty()) "No PPT is free." else "PPT ${DtcPage.ranges(plan.placements.map { it.first })}: " +
            plan.placements.joinToString(", ") { it.second.label } + ". Save to DTC writes them."
        return placed + if (plan.left.isEmpty()) "" else " No room for " + few(plan.left.map { it.label }) + "."
    }

    /** "SA-2, SA-2, SA-3, … and 72 more": a long list named by its first few (a save knows a hundred sites). */
    private fun few(names: List<String>, n: Int = 6): String =
        if (names.size <= n) names.joinToString(", ") else names.take(n).joinToString(", ") + " and ${names.size - n} more"

    /**
     * The flight plan into the steerpoints the cartridge leaves empty ([DtcFromMission.routeFill]), each with its own
     * action, so the page shows the route BMS flies. Returns what was done.
     */
    fun fillRoute(f: DtcFromMission.Facts = missionFacts()): String {
        if (loadedText == null) return noCartridge()
        val fill = DtcFromMission.routeFill(page.m, f.route)
        if (fill.isEmpty()) return if (f.route.isEmpty()) "The mission gives no flight plan yet." else "Every steerpoint of the flight plan is in your cartridge already."
        for (p in fill) DtcFromMission.writeStpt(page.m.stpt[p.n - 1], p.at, p.altFt, p.action, p.name)
        relabel()
        return "STPT ${DtcPage.ranges(fill.map { it.n })}: the flight plan. Save to DTC writes them."
    }

    /** What is free in the cartridge on the page, for the Map page's "put it in the next free …". */
    data class FreeSlots(val lines: List<Int>, val ppts: List<Int>, val stpts: List<Int>, val open1: List<Int>, val open2: List<Int>, val targets: List<Int>)

    /** The free slots of the cartridge on the page; null before one is loaded. */
    fun freeSlots(): FreeSlots? {
        @Suppress("UNUSED_VARIABLE") val v = version
        if (loadedText == null) return null
        val m = page.m
        return FreeSlots(
            lines = DtcFromMission.freeLines(m),
            ppts = DtcFromMission.freePpts(m),
            stpts = (1..24).filter { DtcFromMission.stptEmpty(m.stpt[it - 1]) },
            open1 = (81..89).filter { DtcFromMission.stptEmpty(m.open[it - 81]) },
            open2 = (90..99).filter { DtcFromMission.stptEmpty(m.hpn[it - 90]) },
            targets = (1..100).filter { DtcFromMission.targetEmpty(m.tgt[it - 1]) },
        )
    }

    /**
     * Steerpoint [n] — 1-24, or an open one: 81-89 (Open 1), 90-99 (Open 2) — set to [place] as a Precision
     * steerpoint, which Falcon BMS takes over its own. One that holds a point is replaced only once the pilot says so
     * ([placeLine]). A place whose elevation is not known goes in at 0 ft, and the answer says so.
     */
    fun placeSteerpoint(n: Int, place: DtcFromMission.Place, ask: Boolean = true, done: ((String) -> Unit)? = null): String {
        if (loadedText == null) return noCartridge()
        val s = stptSlot(n) ?: return "There is no STPT $n to set: the cartridge's are 1-24, 81-89 and 90-99."
        if (ask && !DtcFromMission.stptEmpty(s)) {
            val named = s.target?.trim()?.takeIf { it.isNotBlank() && !it.equals("Not set", true) }?.let { " ($it)" } ?: ""
            WdpDialogs.message(
                "STPT $n",
                "STPT $n holds a point$named. Replace it with ${place.name}?",
                listOf("Replace", "Cancel"),
            ) { a -> if (a == "Replace") guard { val r = placeSteerpoint(n, place, ask = false); done?.invoke(r) } }
            return "STPT $n holds a point: $ASKED to replace it."
        }
        DtcFromMission.writeStpt(s, place.at, place.elevFt ?: 0.0, -1, place.name)
        relabel()
        return "STPT $n: ${place.name}, Precision. Save to DTC writes it." + if (place.elevFt == null) "\n\n$NO_ELEVATION" else ""
    }

    private fun stptSlot(n: Int): DtcStpt? = when (n) {
        in 1..24 -> page.m.stpt[n - 1]
        in 81..89 -> page.m.open[n - 81]
        in 90..99 -> page.m.hpn[n - 90]
        else -> null
    }

    /** Row [row] (1-100) of the target list set to [place]; one that holds a point is replaced only once the pilot says so. */
    fun placeTarget(row: Int, place: DtcFromMission.Place, ask: Boolean = true, done: ((String) -> Unit)? = null): String {
        if (loadedText == null) return noCartridge()
        if (row !in 1..100) return "There is no target $row: the list holds 1 to 100."
        val t = page.m.tgt[row - 1]
        if (ask && !DtcFromMission.targetEmpty(t)) {
            val what = t.target?.takeIf { it.isNotBlank() } ?: "set"
            WdpDialogs.message(
                "Target $row",
                "Target $row is $what. Replace it with ${place.name}?",
                listOf("Replace", "Cancel"),
            ) { a -> if (a == "Replace") guard { val r = placeTarget(row, place, ask = false); done?.invoke(r) } }
            return "Target $row is set: $ASKED to replace it."
        }
        DtcFromMission.writeTarget(t, place)
        relabel()
        return "Target $row: ${place.name}. Save to DTC writes it."
    }

    /** [places] into the target list's empty rows, one each, those already listed left out. Returns what was done. */
    fun addTargets(places: List<DtcFromMission.Place>): String {
        if (loadedText == null) return noCartridge()
        val fill = DtcFromMission.targetFill(page.m, places)
        if (fill.isEmpty()) return "Nothing to add: every place is in the list already, or the list is full."
        for ((row, p) in fill) DtcFromMission.writeTarget(page.m.tgt[row], p)
        relabel()
        return "Targets ${DtcPage.ranges(fill.map { it.first + 1 })}: ${fill.size} places of the mission. Save to DTC writes them."
    }

    /** The cartridge's TACAN set to [t] (channel, band, T/R or A/A TR), as the RADIO/NAV tab's Default sets it. */
    fun setTacan(t: DtcFromMission.TacanChoice): String {
        if (loadedText == null) return noCartridge()
        val cm = page.m.comm
        cm.tacanChannel = t.channel.coerceIn(1, 126); cm.tacanBand = t.band; cm.tacanDomain = t.domain
        page.fillRadiodata()
        page.changed()
        version++
        val band = if (t.band == 1) "Y" else "X"
        val fn = if (t.domain == 1) "A/A TR" else "T/R"
        return "TACAN ${t.channel}$band $fn: ${t.label}. Save to DTC writes it."
    }

    /** The cartridge's ILS set to [i] (frequency and course), as the RADIO/NAV tab's Default sets it. */
    fun setIls(i: DtcFromMission.IlsChoice): String {
        if (loadedText == null) return noCartridge()
        val cm = page.m.comm
        cm.ilsFrequency = DtcLoad.ilsInBand(i.freq100); cm.ilsCrs = ((i.course % 360) + 360) % 360
        page.fillRadiodata()
        page.changed()
        version++
        return "ILS: ${i.label}. Save to DTC writes it."
    }

    /** Whether a place… call answered by putting its question on screen (the answer then comes through its done). */
    fun asked(msg: String): Boolean = msg.contains(ASKED)

    // ---- the Map page's tools (WDP's MAP tab: its PPT window, Auto PPT, Clear PPT, Change Area, Clear Lines)

    /** PPT [slot] (56-70) in the PPT tab's own Change PPT window. */
    fun editPpt(slot: Int) {
        if (loadedText == null || slot !in 56..70) return
        guard { changePpt(slot - 55) }
    }

    /**
     * A new PPT at [north]/[east] in the Change PPT window, typed [code] where the table has it (else [name]), at
     * [elevFt]: the first free PPT, else PPT 70 (the window says which). [done] hears what Apply did.
     */
    fun newPpt(north: Double, east: Double, code: String?, elevFt: Int?, name: String?, done: ((String) -> Unit)? = null): String {
        if (loadedText == null) return noCartridge()
        val table = pptTypes()
        if (table.isEmpty()) return "There is no PPT table to pick a type from: load one on the PPT management tab first."
        val slot = DtcFromMission.freePpts(page.m).firstOrNull() ?: 70
        val typeName = table.firstOrNull { !code.isNullOrBlank() && it.first.equals(code, true) }?.second
            ?: name?.let { n -> table.firstOrNull { it.second.equals(n, true) }?.second } ?: ""
        val (n, e) = coords.label(north.toFloat(), east.toFloat())
        val elev = elevFt ?: 0
        val p = page.m.ppt[slot - 56]
        DtcPptWindow(slot, n, e, elev.toString(), typeName, table, coords, Triple(north.toFloat(), east.toFloat(), -abs(elev.toFloat()))) { y, x, elv, nm, cd, rng ->
            if (y == 0f && x == 0f) return@DtcPptWindow
            p.falconY = y; p.falconX = x; p.falconZ = -abs(elv)
            p.name = nm; p.code = cd; p.falconRng = rng
            page.fillLabelsPPT()
            relabel()
            done?.invoke("PPT $slot: $nm. Save to DTC writes it.")
        }.open()
        return "PPT $slot" + (if (DtcFromMission.pptEmpty(page.m, slot)) "" else " (it holds a point: Apply replaces it)") + ": $ASKED in the window."
    }

    /** An empty PPT as BMS writes one: no position, no type, no ring. */
    private fun emptyPpt(slot: Int) {
        val p = page.m.ppt[slot - 56]
        p.falconY = 0f; p.falconX = 0f; p.falconZ = 0f; p.name = ""; p.code = ""; p.falconRng = 0f
    }

    private fun pptWhat(slot: Int): String = page.m.ppt[slot - 56].let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.code?.takeIf { c -> c.isNotBlank() } ?: "a point" }

    /**
     * WDP's Auto PPT: PPT 56-70 replaced by [options] in order (the first fifteen). WDP cleared them first without a
     * word; here a PPT that holds a point is replaced only once the pilot says so ([placeLine]).
     */
    fun replacePpts(options: List<DtcFromMission.PptOption>, ask: Boolean = true, done: ((String) -> Unit)? = null): String {
        if (loadedText == null) return noCartridge()
        val take = DtcFromMission.onePerSite(options).take(15)
        val placed = (56..70).filter { !DtcFromMission.pptEmpty(page.m, it) }
        if (ask && placed.isNotEmpty()) {
            WdpDialogs.message(
                "Auto PPT",
                "Replace PPT ${DtcPage.ranges(placed)} (${few(placed.map { pptWhat(it) })}) with ${take.size} threat${if (take.size == 1) "" else "s"}?" +
                    "\n\nNothing is written until you press Save to DTC.",
                listOf("Replace", "Cancel"),
            ) { a -> if (a == "Replace") guard { val r = replacePpts(options, ask = false); done?.invoke(r) } }
            return "PPT ${DtcPage.ranges(placed)} hold points: $ASKED to replace them."
        }
        for (s in 56..70) emptyPpt(s)
        take.forEachIndexed { i, o -> DtcFromMission.writePpt(page.m, 56 + i, o) }
        page.fillLabelsPPT()
        relabel()
        return if (take.isEmpty()) "PPT 56-70 emptied: there is no threat to put there."
        else "PPT ${DtcPage.ranges((56 until 56 + take.size).toList())}: ${few(take.map { it.label })}. Save to DTC writes them."
    }

    /** WDP's Clear PPT: all fifteen emptied, after asking when any holds a point (as the Clear All buttons ask). */
    fun clearPpts(ask: Boolean = true, done: ((String) -> Unit)? = null): String {
        if (loadedText == null) return noCartridge()
        val placed = (56..70).filter { !DtcFromMission.pptEmpty(page.m, it) }
        if (placed.isEmpty()) return "Every PPT is empty already."
        if (ask) {
            WdpDialogs.message(
                "Clear PPT",
                "Clear ${placed.size} PPT${if (placed.size == 1) "" else "s"} with a position (PPT ${DtcPage.ranges(placed)}: ${few(placed.map { pptWhat(it) })})?" +
                    "\n\nThe page empties ${if (placed.size == 1) "it" else "them"}. Nothing is written until you press Save to DTC, which then writes that into the cartridge the jet loads.",
                listOf("Clear", "Cancel"),
            ) { a -> if (a == "Clear") guard { val r = clearPpts(ask = false); done?.invoke(r) } }
            return "PPT ${DtcPage.ranges(placed)} hold points: $ASKED to clear them."
        }
        for (s in 56..70) emptyPpt(s)
        page.fillLabelsPPT()
        relabel()
        return "PPT ${DtcPage.ranges(placed)} cleared. Save to DTC writes it."
    }

    /** What line [n] (1-4) holds, as the Map page names it: the mission's name for it, "hand-drawn", or null when empty. */
    fun lineLabel(n: Int): String? {
        if (loadedText == null || n !in 1..4) return null
        if (DtcFromMission.lineEmpty(page.m, n)) return null
        return lineName(n) ?: "hand-drawn"
    }

    /** WDP's Clear Lines: the four lines emptied, after asking when any holds points. */
    fun clearLines(ask: Boolean = true, done: ((String) -> Unit)? = null): String {
        if (loadedText == null) return noCartridge()
        val held = (1..4).filter { !DtcFromMission.lineEmpty(page.m, it) }
        if (held.isEmpty()) return "Every line is empty already."
        if (ask) {
            WdpDialogs.message(
                "Clear Lines",
                "Clear ${held.size} line${if (held.size == 1) "" else "s"} with points (" + held.joinToString(", ") { k -> "Line $k: ${lineLabel(k)}" } + ")?" +
                    "\n\nNothing is written until you press Save to DTC, which then writes that into the cartridge the jet loads.",
                listOf("Clear", "Cancel"),
            ) { a -> if (a == "Clear") guard { val r = clearLines(ask = false); done?.invoke(r) } }
            return "Lines ${held.joinToString(", ")} hold points: $ASKED to clear them."
        }
        for (k in held) { DtcFromMission.writeLine(page.m, k, emptyList()); lineNames.remove(k) }
        relabel()
        return "Line${if (held.size == 1) "" else "s"} ${held.joinToString(", ")} cleared. Save to DTC writes it."
    }

    /** Change Area from the Map page: the mission's lines and [extra] (the orbit boxes the map draws). */
    fun openChangeArea(extra: List<DtcFromMission.LineOption> = emptyList()) {
        fromMission("Change Area") { f -> areaWindow(DtcFromMission.lineOptions(f) + extra, "Change Area", emptyList()) }
    }

    /** The TE's own mission file a save writes too ("TE_Mine.ini"), when a TE or training is open; else null. */
    fun teIni(): String? = teRef()?.file?.substringBeforeLast('.')?.let { "$it.ini" }

    /** A tab's From mission…: what the mission holds read (the airports and towns first, where they are not yet), then [then]. */
    private fun fromMission(title: String, then: (DtcFromMission.Facts) -> Unit) {
        // what the mission holds may still be on its way (the airports, the towns, the threat reference, over the
        // network on a phone): the box says so at once and gives way to the answer, and Cancel stops it
        var cancelled = false
        val wait = WdpMessage(title, "Reading what the mission holds…", listOf("Cancel")) { cancelled = true }
        launch {
            val waiting = !factsReady()
            if (waiting) WdpDialogs.stack += wait
            prepareFacts()
            if (waiting) {
                if (cancelled || wait !in WdpDialogs.stack) return@launch
                WdpDialogs.stack.remove(wait)
            }
            val f = try {
                missionFacts()
            } catch (e: Throwable) {
                WdpDialogs.message(title, "The mission could not be read: " + (e.message ?: e::class.simpleName ?: "unknown"))
                return@launch
            }
            guard { then(f) }
        }
    }

    /** STPT: the flight plan into the empty slots, or one point of the mission into the slot the pilot picks. */
    private fun fromMissionStpt(f: DtcFromMission.Facts) {
        val fill = DtcFromMission.routeFill(page.m, f.route)
        val route = when {
            fill.isNotEmpty() -> {
                val from = fill.groupBy { it.from }.entries.joinToString("; ") { (k, v) -> "${DtcPage.ranges(v.map { it.n })} from $k" }
                "Flight plan: STPT ${DtcPage.ranges(fill.map { it.n })} are empty in your cartridge, and the flight plan places them ($from). " +
                    "They go in with the flight plan's own actions (TakeOff, Nav, CAP…), so this page shows your route while Falcon BMS " +
                    "goes on flying its own: only a Precision steerpoint moves the jet's. To have the jet take one exactly, make it " +
                    "Precision with its Change button."
            }
            f.route.isEmpty() -> "Flight plan: the mission gives none yet (press PRINT on the BMS briefing screen, or open the flight with Open mission…)."
            else -> "Flight plan: every steerpoint of it is in your cartridge already."
        }
        WdpDialogs.message(
            "Steerpoints from the mission", "$route\n\n$PICK_TEXT",
            listOfNotNull(FILL_ROUTE.takeIf { fill.isNotEmpty() }, PICK_POINT, "Cancel"),
        ) { a ->
            when (a) {
                FILL_ROUTE -> guard { fillRoute(f) }
                PICK_POINT -> guard { pickPoint(f.places, 1..24, "STPT") }
            }
        }
    }

    /**
     * A point of the mission picked from a list and a map (WDP's Target Selection, where a tap on the map away from
     * every mark takes the point tapped), then the slot it goes into ([range]: steerpoints, open steerpoints or the
     * target list's rows, [word] "STPT" or "Target"), the first empty one offered.
     */
    private fun pickPoint(places: List<DtcFromMission.Place>, range: IntRange, word: String) {
        val objs = places.map { DtcObjective(it.name, it.at.north, it.at.east, it.elevFt ?: Double.NaN, it.group, it.group) }
        if (objs.isEmpty()) {
            WdpDialogs.message(
                "Pick a point",
                "There is nothing to pick from yet: the mission names no place and the theater's airbases are not known. " +
                    "Press PRINT on the BMS briefing screen, or open a flight with Open mission….",
            )
            return
        }
        val target = word == "Target"
        DtcTargetWindow(objs, coords, mission.theater, title = "Pick a point", allowPoint = true) { o ->
            val place = DtcFromMission.Place(o.name, DtcFromMission.Pt(o.north, o.east), o.elev.takeIf { !it.isNaN() }, o.group)
            val free = range.firstOrNull { n -> if (target) DtcFromMission.targetEmpty(page.m.tgt[n - 1]) else stptSlot(n)?.let(DtcFromMission::stptEmpty) == true }
            val (north, east) = coords.label(o.north.toFloat(), o.east.toFloat())
            DtcAptToStptWindow("position of ${o.name}", north, east, title = "Point to $word", range = range, start = free ?: range.first, word = word) { nr ->
                // a steerpoint with no elevation is worth a word before the jet aims a weapon at it; the rest shows on the page
                val show: (String) -> Unit = { msg -> if (!target && place.elevFt == null) WdpDialogs.message("$word $nr", msg) }
                val msg = if (target) placeTarget(nr, place, done = show) else placeSteerpoint(nr, place, done = show)
                if (!asked(msg)) show(msg)
            }.open()
        }.open()
    }

    /** Lines: the free lines start on the mission's tracks and CAP in Change Area, where the pilot can change each. */
    private fun fromMissionLines(f: DtcFromMission.Facts) {
        val opts = DtcFromMission.lineOptions(f)
        if (opts.isEmpty()) { WdpDialogs.message("Lines from the mission", NO_LINES); return }
        val suggest = DtcFromMission.planLines(page.m, opts.filter { it.kind != DtcFromMission.LineKind.ROUTE })
        areaWindow(opts, "Lines from the mission", (1..4).map { k -> suggest.firstOrNull { it.first == k }?.second?.short })
    }

    /**
     * WDP's Change Area over the mission's lines ([DtcFromMission.lineOptions]): each line kept, cleared or laid along
     * one; a line that holds other points is replaced only once the pilot says so.
     */
    private fun areaWindow(opts: List<DtcFromMission.LineOption>, title: String, initial: List<String?>) {
        DtcAreaWindow(opts.map { o -> o.short to o.points.map { it.north to it.east } }, title, initial) { picks ->
            // (line, the option or null for No Line), for the lines not kept
            val chosen = picks.mapIndexedNotNull { i, p -> p?.let { (short, _) -> (i + 1) to opts.firstOrNull { it.short == short } } }
            val replacing = chosen.filter { (k, o) -> o != null && DtcFromMission.linePoints(page.m, k).let { it.isNotEmpty() && !same(it, o.points) } }
            fun apply() {
                for ((k, o) in chosen) {
                    if (o == null) { DtcFromMission.writeLine(page.m, k, emptyList()); lineNames.remove(k) }
                    else { DtcFromMission.writeLine(page.m, k, o.points); lineNames[k] = o.label to o.points }
                }
                relabel()
            }
            if (replacing.isEmpty()) guard { apply() }
            else WdpDialogs.message(
                title,
                replacing.joinToString("\n") { (k, o) ->
                    val named = lineName(k)?.let { " ($it)" } ?: ""
                    "Line $k holds ${DtcFromMission.linePoints(page.m, k).size} points$named: ${o?.label} replaces them."
                } + "\n\nReplace ${if (replacing.size == 1) "it" else "them"}?",
                listOf("Replace", "Cancel"),
            ) { a -> if (a == "Replace") guard { apply() } }
        }.open()
    }

    /** PPT: the support stations and the CAP as markers, the known air defences as rings, into the free PPTs. */
    private fun fromMissionPpts(f: DtcFromMission.Facts) {
        val types = pptTypes()
        if (types.isEmpty()) { WdpDialogs.message("PPTs from the mission", noTheaterPpt()); return }
        val o = DtcFromMission.pptOptions(f, types, page.m)
        val notes = ArrayList<String>()
        if (o.already.isNotEmpty()) notes += "Already in your cartridge: PPT ${DtcPage.ranges(o.already)}."
        val untyped = o.untyped.distinct()
        if (untyped.isNotEmpty()) notes += "The PPT table has no type for ${untyped.joinToString(", ")}: add one on the PPT management tab, " +
            "and From mission… places ${if (untyped.size == 1) "it" else "them"} too."
        if (o.noMarker.isNotEmpty()) notes += "The PPT table has no marker for: " + o.noMarker.joinToString(", ") { it.lowercase().replaceFirstChar { c -> c.uppercaseChar() } } + "."
        if (f.sites.isEmpty()) notes += f.notes
        if (o.options.isEmpty()) {
            WdpDialogs.message("PPTs from the mission", (listOf("The mission has nothing new for the PPTs.") + notes).joinToString("\n\n"))
            return
        }
        val free = DtcFromMission.planPpts(page.m, o.options)
        val all = DtcFromMission.planPpts(page.m, o.options, useAll = true)
        fun row(slot: Int, p: DtcFromMission.PptOption): String = "PPT $slot  " + if (p.marker) "${p.typeName} marker: ${p.label}, ${p.from}"
            else "${p.typeName}, ${DtcFromMission.nm(p.rangeFt)} ring: " + (p.edgeFt?.let { e ->
                if (e <= 0.0) "your route crosses it, " else "its edge ${DtcFromMission.nm(e)} from your route, "
            } ?: "") + p.from
        val text = StringBuilder(if (free.placements.isNotEmpty()) free.placements.joinToString("\n") { (s, p) -> row(s, p) } else "Every PPT holds a point already.")
        val replaceAll = "Replace PPT ${DtcPage.ranges(all.replaced)}"
        if (free.left.isNotEmpty()) {
            text.append("\n\nNo free PPT for ${free.left.size} more: " + few(free.left.map { it.label }))
            text.append(if (all.replaced.isNotEmpty()) ". \"$replaceAll\" puts ${if (free.left.size == 1) "it" else "them"} there instead of what is there now." else ".")
        }
        for (n in notes) text.append("\n\n").append(n)
        WdpDialogs.message(
            "PPTs from the mission", text.toString(),
            listOfNotNull("Place".takeIf { free.placements.isNotEmpty() }, replaceAll.takeIf { free.left.isNotEmpty() && all.replaced.isNotEmpty() }, "Cancel"),
        ) { a ->
            when (a) {
                "Place" -> guard { writePpts(free) }
                replaceAll -> guard { writePpts(all) }
            }
        }
    }

    /** Open 1 (STPT 81-89): the flight's targets; Open 2 (90-99, Harpoon): the hostile ships the feed reports. */
    private fun fromMissionOpen(f: DtcFromMission.Facts, hpn: Boolean) {
        val table = if (hpn) page.m.hpn else page.m.open
        val first = if (hpn) 90 else 81
        val list = if (hpn) f.ships else f.targets
        val fill = DtcFromMission.openFill(table, list)
        val title = if (hpn) "Open 2 from the mission" else "Open 1 from the mission"
        val text = when {
            fill.isNotEmpty() -> fill.joinToString("\n") { (i, p) -> "STPT ${first + i}  ${p.name}" + if (p.elevFt == null) " (elevation not known: 0 ft)" else "" } +
                "\n\nEach goes in as a Precision steerpoint, which Falcon BMS takes over its own." +
                (if (fill.any { it.second.elevFt == null }) " $NO_ELEVATION" else "")
            hpn -> "The mission reports no hostile ship. While Falcon BMS is flying, the Tacview feed (ACMI recording, F) does."
            list.isEmpty() -> "The mission names no target for your flight: a save's flight gives each jet's designated target " +
                "(Open mission…), a printed briefing its target steerpoints."
            else -> "Every target of the mission is in these steerpoints already, or they are full."
        }
        WdpDialogs.message(title, "$text\n\n$PICK_TEXT", listOfNotNull("Place".takeIf { fill.isNotEmpty() }, PICK_POINT, "Cancel")) { a ->
            when (a) {
                "Place" -> guard {
                    for ((i, p) in fill) DtcFromMission.writeStpt(table[i], p.at, p.elevFt ?: 0.0, -1, p.name)
                    relabel()
                }
                PICK_POINT -> guard { pickPoint(if (hpn) f.ships + f.places else f.places, if (hpn) 90..99 else 81..89, "STPT") }
            }
        }
    }

    /** Targets: the flight's targets, the air defences, the stations and the flight's airbases into the empty rows. */
    private fun fromMissionTargets(f: DtcFromMission.Facts) {
        val list = f.places.filter { it.group != "Steerpoints" && it.group != "Airbases" }
        val fill = DtcFromMission.targetFill(page.m, list)
        val text = if (fill.isEmpty()) {
            "Nothing new for the list: " + if (list.isEmpty()) "the mission names no place yet." else "every place of the mission is in it already, or it is full."
        } else {
            "Targets ${DtcPage.ranges(fill.map { it.first + 1 })}: " +
                fill.groupBy { it.second.group }.entries.joinToString("; ") { (g, v) -> "${v.size} × ${g.lowercase()}" } +
                ".\n\nThe list is yours to take along: Falcon BMS 4.38.1 does nothing with it in the jet."
        }
        WdpDialogs.message("Targets from the mission", "$text\n\n$PICK_TEXT", listOfNotNull("Add".takeIf { fill.isNotEmpty() }, PICK_POINT, "Cancel")) { a ->
            when (a) {
                "Add" -> guard { addTargets(list) }
                PICK_POINT -> guard { pickPoint(f.places, 1..100, "Target") }
            }
        }
    }

    /** RADIO/NAV: the comm plan (Default), a TACAN (a field's, a tanker's air to air), an ILS. */
    private fun fromMissionRadio(f: DtcFromMission.Facts) {
        val ladder = commLadder().any { (it.uhfCh ?: 0) in 1..20 || (it.vhfCh ?: 0) in 1..20 }
        val parts = ArrayList<String>()
        parts += if (ladder) "Comm plan: the briefing's comm ladder into the presets, as Falcon BMS's COMM PLAN puts it there (Default does the same, with the departure's TACAN and ILS)."
            else "Comm plan: the briefing has no comm ladder (a save's flight has none: press PRINT on the BMS briefing screen)."
        parts += if (f.tacans.isNotEmpty()) "TACAN:\n" + f.tacans.joinToString("\n") { "  " + it.label } else "TACAN: the mission names no field or tanker with one."
        parts += if (f.ils.isNotEmpty()) "ILS:\n" + f.ils.joinToString("\n") { "  " + it.label } else "ILS: none of your airbases has one."
        WdpDialogs.message(
            "Radio and nav from the mission", parts.joinToString("\n\n"),
            listOfNotNull(COMM_PLAN.takeIf { ladder }, TACAN_MENU.takeIf { f.tacans.isNotEmpty() }, ILS_MENU.takeIf { f.ils.isNotEmpty() }, "Cancel"),
        ) { a ->
            when (a) {
                COMM_PLAN -> guard { defaultRadio() }
                TACAN_MENU -> WdpDialogs.message("TACAN", f.tacans.joinToString("\n") { it.label }, f.tacans.take(5).map { it.short } + "Cancel") { b ->
                    f.tacans.firstOrNull { it.short == b }?.let { t -> guard { setTacan(t) } }
                }
                ILS_MENU -> WdpDialogs.message("ILS", f.ils.joinToString("\n") { it.label }, f.ils.take(5).map { it.short } + "Cancel") { b ->
                    f.ils.firstOrNull { it.short == b }?.let { i -> guard { setIls(i) } }
                }
            }
        }
    }

    /** SYSTEMS: the laser codes, the save's, one per jet. */
    private fun fromMissionLaser(f: DtcFromMission.Facts) {
        val (own, mate) = DtcFromMission.laserPair(f)
        if (own == null && mate == null) { WdpDialogs.message("Laser codes from the mission", NO_LASER); return }
        val text = listOfNotNull(
            own?.let { "TGP laser code: $it, your jet's (#${f.seat + 1})." },
            mate?.let { "LST laser code: $it, #${(f.seat xor 1) + 1}'s: the code your spot tracker looks for in a buddy lase." },
        ).joinToString("\n") + "\n\nThe codes are the save's, one for each jet of your flight."
        val l = page.m.laser
        if ((own == null || l.laserCode.toInt() == own) && (mate == null || l.lstCode.toInt() == mate)) {
            WdpDialogs.message("Laser codes from the mission", "$text\n\nYour cartridge has them already.")
            return
        }
        WdpDialogs.message("Laser codes from the mission", text, listOf("Set", "Cancel")) { a ->
            if (a == "Set") guard {
                own?.let { page.type("mxtLaserCode", it.toString()) }
                mate?.let { page.type("mxtLaserLST", it.toString()) }
                page.changed()
            }
        }
    }

    /** HARM: the systems the briefing names and the known sites have, into the table the pilot picks. */
    private fun fromMissionHarm(f: DtcFromMission.Facts) {
        val picks = DtcFromMission.harmPicks(f, harmCodes.orEmpty())
        if (picks.isEmpty()) {
            WdpDialogs.message("HARM from the mission", (listOf(NO_HARM) + (if (f.sites.isEmpty()) f.notes else emptyList())).joinToString("\n\n"))
            return
        }
        fun held(t: Int) = (1..5).mapNotNull { h -> page.c("cboTbl${t}_Thr$h").selectedItem?.takeIf { it.isNotBlank() && it != DtcPage.NOT_IN_LIST } }
        val text = "Systems: " + picks.joinToString(", ") { it.label } + " (what your briefing names, then the known sites' nearest your route).\n\n" +
            (1..3).joinToString("\n") { t -> "Table $t now: " + held(t).ifEmpty { listOf("empty") }.joinToString(", ") } +
            "\n\nPut them into a table (what it holds is replaced):"
        WdpDialogs.message("HARM from the mission", text, listOf("Table 1", "Table 2", "Table 3", "Cancel")) { a ->
            val t = a.removePrefix("Table ").toIntOrNull() ?: return@message
            guard {
                for (h in 1..5) {
                    val box = page.c("cboTbl${t}_Thr$h")
                    val i = box.items.indexOf(picks.getOrNull(h - 1)?.label ?: DtcPage.NOT_IN_LIST)
                    if (i >= 0) page.choose(box.name, i)
                }
                page.changed()
            }
        }
    }

    // ---------------------------------------------------------------- MAIN

    /**
     * The DataCard's Get DTC File. WDP re-read the file over whatever the DTC page held; with edits on the page that
     * would drop them silently (guide §15-14), so the pilot is asked first and the page is left alone until they say.
     * The Planner's Re-read DTC from BMS (the toolbar's Save to DTC menu) is this.
     */
    private suspend fun reloadFromCard(): String {
        asking = false
        // the file read again is the attack from now on: no attack page's is current, nothing placed this session is kept
        if (!hasEdits()) { AttackFocus.clear(); sessionSlots.clear(); return reloadNow(null) }
        asking = true
        WdpDialogs.message(
            "Re-read DTC from BMS",
            "The DTC page has changes that are not saved to ${state?.file ?: "the cartridge"} yet. Reading the file " +
                "again drops them. Save them first, or read the file anyway?",
            listOf("Save first", "Read anyway", "Cancel"),
        ) { answer ->
            when (answer) {
                "Save first" -> launch { val msg = saveNow(); if (!asking) WdpCartridge.answer("Save to DTC", msg) }
                "Read anyway" -> launch { AttackFocus.clear(); sessionSlots.clear(); WdpCartridge.answer("Re-read DTC from BMS", reloadNow(state?.callsign)) }
            }
        }
        return "The DTC page has changes that are not saved yet, so nothing was read: answer its question to save them first or read the file anyway."
    }

    /**
     * What the DataCard puts into the cartridge through this page, as WDP's card does through `cntDTC`: each entry
     * goes into its box and through the box's own handler, so the page shows it, its limits apply (a laser code
     * 1111–2888) and a save writes it like an edit made here. [save] then saves.
     */
    private suspend fun writeCardEntries(e: WdpCartridge.CardEntries, save: Boolean): String {
        asking = false
        if (loadedText == null) reloadNow(null)
        if (loadedText == null) return status ?: "No callsign.ini is loaded."
        val done = ArrayList<String>()
        fun typed(box: String, v: Any?, what: String) { if (v != null) { page.type(box, v.toString()); done += what } }
        typed("mxtALOW_AGL", e.alowAglFt, "ALOW")
        typed("mxtALOW_MSL", e.mslFloorFt, "MSL floor")
        typed("mxtBingo", e.bingoLbs, "bingo")
        e.ewsProgramNames?.forEachIndexed { i, name ->
            if (i in 0..5 && name != null) { page.type("txtComment${i + 1}", name); done += "EWS program ${i + 1} name" }
        }
        typed("mxtLaserCode", e.laserTgp, "TGP laser code")
        typed("mxtLaserLST", e.laserLst, "LST laser code")
        for ((n, p) in listOf(1 to e.profile1, 2 to e.profile2)) {
            if (p == null) continue
            fun pick(box: String, item: String?) {
                if (item == null) return
                val k = page.c(box)
                val i = k.items.indexOfFirst { it.equals(item, ignoreCase = true) }
                if (i >= 0) page.choose(box, i)
            }
            pick("cboP${n}_SubMode", p.submode)
            pick("cboP${n}_Fuze", p.fuze)
            pick("cboP${n}_SGL_PAIR", p.sglPair)
            // WDP's card wrote profile 2's arm delay into profile 2's second arm-delay box (cntDataCard: mxtP2_C1_AD2);
            // the card's arm delay is the first one on both profiles
            p.armDelaySec?.let { page.type("mxtP${n}_C1_AD1", DataCardNet.str(it)) }
            p.burstAltFt?.let { page.type("mxtP${n}_C2_BA", it.toString()) }
            p.releaseAngleDeg?.let { page.type("mxtP${n}_Angle", it.toString()) }
            p.pulses?.let { page.type("mxtP${n}_Pulse", it.toString()) }
            p.spacingFt?.let { page.type("mxtP${n}_Spacing", it.toString()) }
            done += "bomb profile $n"
        }
        e.attackProfile?.let { profile ->
            val offsets = e.attackOffsets
            val sel = e.attackModesel
            when {
                // the card's own attack (1.3.8): its offsets through the same path as the attack page's Save to DTC, so
                // the cartridge gets what the card shows, byte for byte what the page's own save writes (B11)
                profile in listOf("PopUp", "HADB", "TOSS") && offsets != null && sel != null -> {
                    stageNavOffsets(profile, offsets, sel)?.let { return it }
                    AttackFocus.pageOf(profile)?.let { AttackFocus.apply(it) }
                    done += "attack profile ($profile)"
                }
                profile in listOf("None", "PopUp", "HADB", "TOSS") -> { page.strProfile = profile; page.profiles(); done += "attack profile ($profile)" }
            }
        }
        if (done.isNotEmpty()) page.changed()
        version++
        if (save) return saveNow()
        return if (done.isEmpty()) "The card gave the DTC page nothing to change." else "On the DTC page: " + done.joinToString(", ") + ". Save to DTC writes them."
    }

    /** WDP's `PilotButton`: Change shows a box for the pilot's name, Accept opens that pilot's cartridge. */
    private fun pilot() {
        if (!pilotEditing) { pilotEditing = true; pilotText = state?.callsign.orEmpty(); return }
        pilotEditing = false
        val name = pilotText.trim()
        if (name.isEmpty() || name == state?.callsign) return
        launch {
            val msg = reloadNow(name)
            // the PC names the requested pilot back even when there is no such file, so the reply's own
            // `available` is what says it was found; reloadNow has kept the previous pilot on the page
            if (!name.equals(state?.callsign, ignoreCase = true) || state?.available != true || loadedText == null) {
                pilotText = state?.callsign.orEmpty()
                WdpDialogs.message("Caution", "Pilot $name not found.\n\n$msg")
            }
        }
    }

    // ---------------------------------------------------------------- points

    private fun pointLabel(s: DtcStpt): Pair<String, String> = coords.label(s.falconY, s.falconX)

    /** WDP's `ChangeSTPT` for a steerpoint of any of the three tables. */
    private fun changePoint(s: DtcStpt, label: String, title: String) {
        val (n, e) = pointLabel(s)
        DtcPointWindow(
            "fclsChangeSTPT", title, "lblSTPT", label, n, e, abs(s.falconZ).roundToInt().toString(),
            DataCardPlan.actionString(s.action), coords, Triple(s.falconY, s.falconX, s.falconZ),
        ) { north, east, elv, action ->
            s.falconY = north; s.falconX = east; s.falconZ = elv
            s.action = DtcActions.toInt(action)
            relabel()
        }.open()
    }

    /** The point labels, grids and lamps redrawn after a change the page's own handlers did not make. */
    private fun relabel() {
        page.getCallsignCoords()
        page.changed()
        version++
    }

    /**
     * `btnRebuildStptList_Click`: WDP clears the 24 steerpoints and lays the selected flight's waypoints into them
     * (`FlightTable(SelFlightNr).waypoints`), leaving the actions as they are. The flight plan here is the flight the
     * Planner opened from a save (each waypoint at its cell's middle, D62), else BMS's own route beside the save;
     * each point takes its waypoint's action and altitude (as BMS writes a route slot: 21000 ft is -21000). With no
     * flight plan (a printed briefing without BMS's route) the steerpoints go back to what the cartridge held when it
     * was loaded. WDP cleared everything else as well without a word; here the placed points past the flight plan (a
     * Recon target, the pilot's own) are named and the pilot asked first, as the Clear buttons ask (D50).
     */
    private fun rebuildSteerpoints() {
        val text = loadedText ?: return disabled("btnRebuildStptList")
        val plan = flightPlan()
        if (plan.isEmpty()) {
            DtcLoad.stpt(text, page.m, page.coords)
            relabel()
            WdpDialogs.message(
                "Rebuild STPT List",
                "The mission gives no flight plan to rebuild from (open the flight with Open mission…, or press PRINT on " +
                    "the BMS briefing screen), so the steerpoints are back as your cartridge held them when it was loaded.",
            )
            return
        }
        val planned = plan.map { it.n }.toSet()
        val lost = (1..24).filter { it !in planned && !DtcFromMission.stptEmpty(page.m.stpt[it - 1]) }
        fun rebuild() {
            // where the slot already holds the waypoint (BMS wrote the route into the cartridge: its figures differ
            // from the cell's middle worked out here by a fraction of a foot), its own figures stay, so a Rebuild of an
            // untouched route changes nothing and Save to DTC has nothing to write
            val was = (0 until 24).map { page.m.stpt[it].let { s -> s.falconY to s.falconX } }
            for ((i, s) in page.m.stpt.withIndex()) if (i < 24) { s.falconX = 0f; s.falconY = 0f; s.falconZ = 0f }
            for (p in plan) {
                val s = page.m.stpt[p.n - 1]
                val (wy, wx) = was[p.n - 1]
                val same = abs(wy - p.x) < 1.0 && abs(wx - p.y) < 1.0
                DtcFromMission.writeStpt(s, if (same) DtcFromMission.Pt(wy.toDouble(), wx.toDouble()) else DtcFromMission.Pt(p.x, p.y), p.altFt, p.action, null)
            }
            relabel()
        }
        if (lost.isEmpty()) { rebuild(); return }
        WdpDialogs.message(
            "Rebuild STPT List",
            "Rebuilding lays the flight plan (STPT ${DtcPage.ranges(plan.map { it.n })}) into the steerpoints and clears " +
                "the rest. STPT ${DtcPage.ranges(lost)} hold points that are not in the flight plan, and would be cleared.\n\n" +
                "Rebuild anyway?",
            listOf("Rebuild", "Cancel"),
        ) { answer -> if (answer == "Rebuild") guard { rebuild() } }
    }

    /**
     * The flight plan Rebuild lays into the steerpoints: the waypoints of the flight opened from a save (1-24), else
     * BMS's route beside the save; empty when the mission has neither.
     */
    private fun flightPlan(): List<com.bmscompanion.app.data.mission.DtcPoint> {
        val f = mission.flight
        val fromSave = f?.route.orEmpty().withIndex().map { (i, w) ->
            com.bmscompanion.app.data.mission.DtcPoint(n = if (w.n > 0) w.n else i + 1, x = w.x, y = w.y, altFt = w.altFt, action = w.action)
        }
        val plan = fromSave.ifEmpty { mission.route?.steerpoints.orEmpty() }
        return plan.filter { it.n in 1..24 && (it.x != 0.0 || it.y != 0.0) }.distinctBy { it.n }.sortedBy { it.n }
    }

    private fun changeLine(n: Int) {
        val l = page.m.line[n - 31]
        val (north, east) = coords.label(l.falconY, l.falconX)
        DtcPointWindow("fclsChangeLineSTPT", "Change Line STPT $n", "lblSTPT", "STPT: $n", north, east, "0", null, coords, Triple(l.falconY, l.falconX, 0f)) { y, x, _, _ ->
            l.falconY = y; l.falconX = x; l.falconZ = 0f
            relabel()
            page.fillLines()
        }.open()
    }

    /**
     * Change Area: each of the four lines kept, cleared, or laid along what the mission offers
     * ([DtcFromMission.lineOptions]) — the tanker and AWACS tracks the PC read out of the campaign for the mission BMS
     * is flying and for the flight the Planner opened from a save (its package's support), the flight's CAP station, a
     * stretch of the flight plan. The lists start on "(as it is)"; the tab's From mission… starts the free lines on
     * what it suggests.
     */
    private fun changeArea() {
        fromMission("Change Area") { f -> areaWindow(DtcFromMission.lineOptions(f), "Change Area", emptyList()) }
    }

    private fun changePpt(i: Int) {
        val p = page.m.ppt[i - 1]
        val (n, e) = coords.label(p.falconY, p.falconX)
        if (pptTable.isEmpty()) {
            WdpDialogs.message("Change PPT", "There is no PPT table to pick a type from: load one on the PPT management tab first.")
            return
        }
        DtcPptWindow(55 + i, n, e, abs(p.falconZ).roundToInt().toString(), p.name ?: "", pptTable, coords, Triple(p.falconY, p.falconX, p.falconZ)) { y, x, elv, name, code, rng ->
            if (y == 0f && x == 0f) {
                // "use 00,00.000 to reset the coordinate": a PPT with no position is an empty slot, as BMS writes one
                // (no type, no ring) — not a ring of the chosen type drawn at the corner of the theater
                p.falconY = 0f; p.falconX = 0f; p.falconZ = 0f; p.name = ""; p.code = ""; p.falconRng = 0f
            } else {
                p.falconY = y; p.falconX = x; p.falconZ = -abs(elv)
                p.name = name; p.code = code; p.falconRng = rng
            }
            page.fillLabelsPPT()
            relabel()
        }.open()
    }

    /** A double tap on a target: WDP's left double-click picks one from a list, its right one types one in. */
    private fun targetGrid(name: String) {
        val row = name.substringAfterLast(':').toIntOrNull() ?: 0
        targetRow = row
        if (!name.contains(":open:")) return
        WdpDialogs.message(
            "Target ${row + 1}",
            "Set target ${row + 1} from the mission's targets and the theater's airbases, or type its position in?",
            listOf("Select target", "Change", "Cancel"),
        ) { answer ->
            when (answer) {
                "Select target" -> selectTarget(row)
                "Change" -> changeTarget(row)
            }
        }
    }

    private fun changeTarget(row: Int) {
        val t = page.m.tgt[row]
        val (n, e) = coords.label(t.falconY, t.falconX)
        DtcPointWindow("fclsChangeTgt", "Change Target ${row + 1}", "lblTarget", "Target: ${row + 1}", n, e,
            abs(t.falconZ).roundToInt().toString(), null, coords, Triple(t.falconY, t.falconX, t.falconZ)) { y, x, elv, _ ->
            t.falconY = y; t.falconX = x; t.falconZ = elv
            relabel()
        }.open()
    }

    /**
     * Select target: the places of the mission ([DtcMissionFacts]) — the flight's targets, the steerpoints, the air
     * defences, the support stations, the airbases (the flight's own first; a field with no position, a carrier, is
     * left out rather than set at the corner of the map). A flight-plan steerpoint's altitude is not its ground's, so
     * only a place whose elevation is known gives one; the rest go in at 0 ft, as WDP's did for a point it had none for.
     */
    private fun selectTarget(row: Int) {
        val t = page.m.tgt[row]
        fromMission("Target Selection") { f ->
            val places = f.places
            if (places.isEmpty()) {
                WdpDialogs.message("Target Selection", "There is nothing to pick from yet: press PRINT on the BMS briefing screen, or type the position in with Change.")
                return@fromMission
            }
            val list = places.map { DtcObjective(it.name, it.at.north, it.at.east, it.elevFt ?: 0.0, it.group, it.group) }
            DtcTargetWindow(list, coords, mission.theater) { o ->
                t.falconY = o.north.toFloat(); t.falconX = o.east.toFloat(); t.falconZ = o.elev.toFloat()
                t.target = o.name.take(60)
                relabel()
            }.open()
        }
    }

    // ---------------------------------------------------------------- PPT types

    private fun pptPairs() = pptTable.map { it.first to it.second }

    private fun usePptTable(text: String, from: String) = usePptRows(DtcFiles.pptTable(text), from)

    private fun usePptRows(rows: List<Triple<String, String, Double>>, from: String) {
        pptTable = rows
        pptSource = from
        pptRow = 0
        pptEdit.clear()
        pptTableChanged()
    }

    /** The PPT names and ranges on the PPT tab follow the table, as WDP's `ForcePptRange` makes them. */
    private fun pptTableChanged() {
        page.pptTable = pptTable
        baseline?.pptTable = pptTable
        for (p in page.m.ppt) p.name = DtcLoad.samName(p.code, pptPairs())
        page.fillLabelsPPT()
    }

    /** WDP's `CheckPPT`: the edit boxes into the selected row, with its length rules. */
    private fun applyPptEdit() {
        if (pptTable.isEmpty()) { WdpDialogs.message("PPT", "The table is empty: press New to add a type."); return }
        if (pptRow !in pptTable.indices) { WdpDialogs.message("PPT", "Select the type to change in the list first, or press New."); return }
        val name = pptEdit["txtName"] ?: pptTable[pptRow].second
        val code = pptEdit["txtCode"] ?: pptTable[pptRow].first
        val range = pptEdit["txtRange"]?.trim()?.toDoubleOrNull()?.times(NM_TO_FT) ?: pptTable[pptRow].third
        if (pptEdit["txtRange"] != null && pptEdit["txtRange"]?.trim()?.toDoubleOrNull() == null) { WdpDialogs.message("PPT", "Numbers only"); return }
        if (name.length > 16) { WdpDialogs.message("PPT", "Max lenght is 16 characters"); pptEdit["txtName"] = name.take(16); return }
        if (code.length > 3) { WdpDialogs.message("PPT", "Max lenght is 3 characters"); pptEdit["txtCode"] = code.take(3); return }
        pptTable = pptTable.mapIndexed { i, t -> if (i == pptRow) Triple(code, name, if (range <= 0.1 * NM_TO_FT) 0.1 else range) else t }
        pptEdit.clear()
        pptTableChanged()
    }

    // ---------------------------------------------------------------- files

    /** "The target list is", "The lines are". */
    private fun isAre(part: DtcFiles.Part) = if (part == DtcFiles.Part.TARGET) "is" else "are"


    /**
     * After a Load that picked no file: a copy an earlier version kept in the app's settings, or — for the parts Falcon
     * BMS ships defaults of (`User/Config/EWS_Def.ini`, `MFD_Def.ini`, `HARM_Def.ini`) — BMS's own defaults, read from
     * the PC and never written. WDP's page had no way back to BMS's defaults; its "Clear All" zeroed everything, which
     * is not a state BMS itself ever writes (D15). Nothing is asked when there is neither.
     */
    private fun loadCopy(part: DtcFiles.Part, lead: String = "") {
        val slots = (1..DtcFiles.SLOTS).mapNotNull { s -> DtcFiles.kept(part, s)?.let { s to it } }
        val def = DtcFiles.defaultsFile(part)
        if (slots.isEmpty() && def == null) return
        WdpDialogs.message(
            "Load",
            lead + "Load into the page instead (Save to DTC then writes it to Falcon BMS):\n\n" +
                (if (def != null) "BMS default: Falcon BMS's own $def\n" else "") +
                slots.joinToString("\n") { (s, k) -> "Slot $s: ${k.first}" },
            (if (def != null) listOf(BMS_DEFAULT) else emptyList()) + slots.map { "Slot ${it.first}" } + "Cancel",
        ) { answer ->
            if (answer == BMS_DEFAULT && def != null) { launch { loadBmsDefault(part, def) }; return@message }
            val s = answer.removePrefix("Slot ").toIntOrNull() ?: return@message
            val text = DtcFiles.kept(part, s)?.second ?: return@message
            guard { DtcFiles.load(page, part, text, pptPairs()) }
        }
    }

    private suspend fun loadBmsDefault(part: DtcFiles.Part, file: String) {
        if (!page.blnCallsignDtcLoaded) return disabled("btnLoad_EWS")
        val d = try { source.bmsDefaults() } catch (e: Throwable) { null }
        val f = d?.files?.firstOrNull { it.name.equals(file, ignoreCase = true) }
        if (f == null) {
            WdpDialogs.message("BMS default", d?.error ?: if (d == null) "The PC could not be reached, so BMS's $file could not be read."
                else "Falcon BMS's $file was not found in User\\Config on the PC.")
            return
        }
        guard { DtcFiles.load(page, part, DtcFiles.iniOf(f), pptPairs()) }
        status = "The ${part.label} ${isAre(part)} Falcon BMS's defaults ($file). Save to DTC writes them."
    }

    // ---------------------------------------------------------------- radio

    /** `btnUHF_n_Click` / `btnVHF_n_Click`: the frequency window, then the page's `ChangeCOMM`. */
    private fun changeComm(vhf: Boolean, i: Int) {
        if (!page.blnCallsignDtcLoaded) return disabled(if (vhf) "btnVHF_$i" else "btnUHF_$i")
        val now = page.c(if (vhf) "lblVHF_Val_$i" else "lblUHF_Val_$i").text
        DtcCommWindow(vhf, i, now) { typed ->
            page.comm(if (vhf) "btnVHF_$i" else "btnUHF_$i", typed)
            version++
        }.open()
    }

    /** Blue / Red: the side's standard presets (WDP's `Blue.rad` / `Red.rad`), comments kept. */
    private fun presetRadio(uhf: List<String>, vhf: List<String>) {
        if (!page.blnCallsignDtcLoaded) return disabled("btnBlue")
        val r = page.m.radio
        val u = r.uhf ?: return
        val v = r.vhf ?: return
        for (i in 1..20) {
            u[i] = (uhf[i - 1].toDouble() * 1000).roundToInt()
            v[i] = (vhf[i - 1].toDouble() * 1000).roundToInt()
        }
        page.fillRadiodata()
        page.changed()
    }

    /**
     * `btnDefault_Click`, as Falcon BMS 4.38.1 defines the default: its DTE page's **COMM PLAN** (User Manual 4.38.1
     * §5.1.5, "load the full briefing COMM plan into your DTC comms section"), i.e. every preset the briefing's Comm
     * Ladder numbers ("Base Ops … 382.500 MHz [1]") with the agency as its comment, and the departure airfield's
     * TACAN and ILS (the runway into the take-off wind that has one). WDP put its own Blue list, TACAN 94X and ILS
     * 109.00 there (D15) — none of it BMS's. Presets the comm plan does not name are left as they are.
     */
    private fun defaultRadio() {
        if (!page.blnCallsignDtcLoaded) return disabled("btnDefault")
        val r = page.m.radio
        val u = r.uhf ?: return
        val v = r.vhf ?: return
        val uc = r.uhfComment ?: return
        val vc = r.vhfComment ?: return
        // A channel the ladder names twice (the recovery field is the departure field: "Arr Ground … [2]" after "Dep
        // Ground … [2]") keeps the first row's name, as BMS's own COMM PLAN labels it ("DEP Ground").
        val uhfSet = ArrayList<Int>()
        val vhfSet = ArrayList<Int>()
        for (e in commLadder()) {
            e.uhfCh?.takeIf { it in 1..20 && it !in uhfSet }?.let { ch -> khz(e.uhf)?.let { u[ch] = it; uc[ch] = presetName(e.agency, vhf = false); uhfSet += ch } }
            e.vhfCh?.takeIf { it in 1..20 && it !in vhfSet }?.let { ch -> khz(e.vhf)?.let { v[ch] = it; vc[ch] = presetName(e.agency, vhf = true); vhfSet += ch } }
        }
        val set = listOfNotNull(
            uhfSet.takeIf { it.isNotEmpty() }?.let { "UHF ${DtcPage.ranges(it)}" },
            vhfSet.takeIf { it.isNotEmpty() }?.let { "VHF ${DtcPage.ranges(it)}" },
        )
        val a = airport
        val cm = page.m.comm
        var nav = ""
        a?.tacan?.let { t ->
            cm.tacanChannel = t.channel; cm.tacanBand = if (t.band.equals("Y", true)) 1 else 0; cm.tacanDomain = 0
            nav += " TACAN ${t.channel}${t.band.uppercase()} (${a.name})."
        }
        departureIls()?.let { (row, freq) ->
            cm.ilsFrequency = DtcLoad.ilsInBand(freq); cm.ilsCrs = ((row.course.roundToInt() % 360) + 360) % 360
            nav += " ILS ${row.ils} RWY ${row.name}."
        }
        page.fillRadiodata()
        page.changed()
        WdpDialogs.message(
            "Default",
            if (set.isEmpty()) "There is no comm plan to load: the briefing has no Comm Ladder yet (press PRINT on the BMS briefing screen).$nav"
            else "The briefing's comm plan is in the presets (${set.joinToString("; ")}), as BMS's COMM PLAN button puts it there.$nav",
        )
    }

    /**
     * A ladder row's agency as BMS's own COMM PLAN names the preset in the cartridge it writes: "Dep Tower" →
     * "DEP Tower", "Dep Atis" → "DEP ATIS", "Dep Departure" → "DEP Approach", "Base Ops" → "Base ops", "Check-In" →
     * "AWACS Check-in", "Tanker / Aar" → "Tanker/AAR", "Common" → "Advisory" (UHF) / "UNICOM" (VHF), "Intra-Flight" →
     * "Intra Flight 1" / "Flight-1". Read off a cartridge BMS 4.38.1 filled from its own comm plan.
     */
    private fun presetName(agency: String, vhf: Boolean): String {
        val a = agency.trim().trimEnd(':').trim()
        val key = a.lowercase().replace(" ", "").replace("-", "").replace("/", "")
        when (key) {
            "intraflight" -> return if (vhf) "Flight-1" else "Intra Flight 1"
            "common" -> return if (vhf) "UNICOM" else "Advisory"
            "baseops" -> return "Base ops"
            "checkin" -> return "AWACS Check-in"
            "tactical" -> return "Tactical"
            "tankeraar" -> return "Tanker/AAR"
        }
        val first = a.substringBefore(' ')
        if (!(first.equals("Dep", true) || first.equals("Arr", true) || first.equals("Alt", true))) return a
        val rest = a.substring(first.length).trim().let { r ->
            when {
                r.equals("Atis", true) -> "ATIS"
                r.equals("Departure", true) -> "Approach"
                else -> r
            }
        }
        return first.uppercase() + " " + rest
    }

    /** "345.950 MHz" → 345950, the cartridge's kHz. */
    private fun khz(freq: String?): Int? = freq?.let { Regex("(\\d{2,3}\\.\\d{1,3})").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        ?.let { (it * 1000).roundToInt() }

    /** The departure's ILS runway end: the one into the take-off wind if it has an ILS, else the first that has one. */
    private fun departureIls(): Pair<RunwayRow, Int>? {
        val rows = runwayRows().filter { !it.ils.isNullOrBlank() && it.ils.toDoubleOrNull() != null }
        if (rows.isEmpty()) return null
        // a save's own briefing has no weather: for the flight BMS printed, the printed briefing's is this flight's
        // (as From mission… reads it, so both pick the same runway)
        val wx = mission.briefing?.takeIf { CardWeather.column(it, 0) != null }
            ?: served()?.briefing?.takeIf { mission.flight?.row?.briefed == true } ?: mission.briefing
        val wind = CardWeather.column(wx, 0)?.windDir
        val all = runwayRows()
        val into = all.getOrNull(CardWeather.intoWind(all.map { it.name to it.course }, wind))
        val row = rows.firstOrNull { it == into } ?: rows[0]
        return row to (row.ils!!.toDouble() * 100).roundToInt()
    }

    // ---------------------------------------------------------------- the PPT tables

    /**
     * The theater's own PPT types — Falcon BMS's `Campaign/Ppt.ini` for the theater it is on, as the app ships it
     * ([Theater.pptSet]) — as the page's table. Taken at start when nothing else is loaded, and by Theater PPT. False
     * when the theater is not known or has no table.
     */
    private suspend fun theaterPptTable(force: Boolean = false): Boolean {
        val t = mission.theater ?: return false
        val rows = runCatching { Repo.pptTable(t.pptSet) }.getOrDefault(emptyList())
            .filter { it.code.isNotBlank() && !it.code.startsWith("-") && it.name.isNotBlank() }
            .map { Triple(it.code, it.name, it.radiusFt) }
        theaterPpt = rows
        if (rows.isEmpty()) return false
        if (force || pptSource == NO_PPT || pptSource.startsWith(THEATER_PPT)) usePptRows(rows, "$THEATER_PPT (${t.name})")
        return true
    }

    private fun noTheaterPpt() = "The app has no PPT table for " + (mission.theater?.name?.let { "$it: its data was built before this theater was installed." }
        ?: "this theater: Falcon BMS's theater is not known yet (start BMS, or link this device to the PC).")

    /** Radar Zone / Engage Zone: the theater's types, each with the threat reference's range where it lists the system. */
    private suspend fun zoneTable(radar: Boolean) {
        if (theaterPpt.isEmpty()) theaterPptTable()
        if (theaterPpt.isEmpty()) { WdpDialogs.message(if (radar) "Radar Zone" else "Engage Zone", noTheaterPpt()); return }
        val threats = runCatching { Repo.threats() }.getOrDefault(emptyList())
        val rows = DtcFiles.zoneTable(theaterPpt, threats, radar)
        usePptRows(rows, if (radar) "Radar Zone: the theater's PPT types, each as far as the threat reference's radar lock range"
            else "Engage Zone: the theater's PPT types, each as far as the threat reference's longest engagement range")
    }


    // ---------------------------------------------------------------- the airport block

    private suspend fun loadAirports(): List<Airport> {
        if (airports.isNotEmpty()) return airports
        val t = mission.theater ?: return emptyList()
        val set = runCatching { Repo.airportSet(t.airportSet) }.getOrNull()
        airportSet = set
        airports = set?.airports.orEmpty()
        return airports
    }

    /**
     * The airport block opens on the flight's departure field: the one the briefing's comm ladder names (or, for a
     * flight from a save, its home base), found as the DataCard and the Mission views find it ([missionBases]). Only
     * where the mission names none does WDP's own rule apply — the airbase nearest the flight plan's first steerpoint,
     * the Planner's flight plan ([WdpMission.steerpoints]: the cartridge, BMS's route, the save) before the cartridge's
     * own list. WDP's rule alone put the block on an enemy airbase: in Falcon BMS 4.38.1 the cartridge's route slots
     * are empty (the route is in the mission file), so its first placed steerpoint is often a target BMS's Recon marked.
     * A field with no position (a carrier, which moves) is never chosen by distance.
     */
    private suspend fun findDepartureAirport() {
        if (airport != null) return
        val list = loadAirports()
        if (list.isEmpty()) return
        val named = runCatching { missionBases(mission.briefing, mission.flight).departureIn(airportSet) }.getOrNull()
        if (named != null) { setAirport(named); return }
        val first = mission.steerpoints.firstOrNull { it.placed() }?.let { it.x to it.y }
            ?: page.m.stpt.firstOrNull { it.falconX != 0f || it.falconY != 0f }?.let { it.falconY.toDouble() to it.falconX.toDouble() }
            ?: return
        val a = list.filter { it.x != 0.0 || it.y != 0.0 }.minByOrNull { hypot(it.x - first.first, it.y - first.second) } ?: return
        setAirport(a)
    }

    private fun selectAirport() {
        val t = mission.theater
        if (t == null) {
            WdpDialogs.message("Select APT", "The theater Falcon BMS is on is not known yet, so there is no airport list: start BMS, or link this device to the PC.")
            return
        }
        launch {
            val list = loadAirports()
            if (list.isEmpty()) WdpDialogs.message("Select APT", "No airports were found for ${t.name}.")
            else DtcSelAptWindow(t.name, list, airport?.id) { a -> airportPicked = true; launch { setAirport(a) } }.open()
        }
    }

    private suspend fun setAirport(a: Airport) {
        airport = a
        airfield = mission.theater?.airfieldSet?.let { set -> runCatching { Repo.airfield(set, a.id) }.getOrNull() }
        version++
    }

    /** The runway ends as WDP's four rows list them: both ends of the first runway, then of the second. */
    private fun runwayRows(): List<RunwayRow> {
        val a = airport ?: return emptyList()
        val rows = ArrayList<RunwayRow>()
        val af = airfield
        if (af != null && af.runways.isNotEmpty()) {
            for (r in af.runways.take(2)) for (e in r.ends.take(2)) {
                val db = a.runways.flatMap { it.ends }.firstOrNull { it.designator == e.designator }
                rows += RunwayRow(e.designator, e.course, db?.ils, r.lengthFt, r.widthFt, af.n + e.at.n, af.e + e.at.e)
            }
        } else {
            for (r in a.runways.take(2)) for (e in r.ends.take(2)) rows += RunwayRow(e.designator, e.headingTrue, e.ils, r.lengthFt ?: 0, 0, null, null)
        }
        return rows
    }

    private data class RunwayRow(val name: String, val course: Double, val ils: String?, val lengthFt: Int, val widthFt: Int, val north: Double?, val east: Double?)

    private fun airportValues(m: MutableMap<String, String>) {
        val a = airport
        fun put(name: String, text: String) { m[name] = "shown"; m["$name.text"] = text }
        put("lblAPT_VAL", a?.name ?: "")
        put("lblAPT_UHF_Val", a?.freqs?.towerUhf ?: "")
        put("lblAPT_VHF_Val", a?.freqs?.towerVhf ?: "")
        put("lblAPT_GndUHF_Val", a?.freqs?.groundUhf ?: "")
        put("lblAPT_AppUHF_Val", a?.freqs?.approachUhf ?: "")
        put("lblAPT_OpsUHF_Val", a?.freqs?.opsUhf ?: "")
        put("lblAPT_LsoUHF_Val", a?.freqs?.lsoUhf ?: "")
        put("lblAPT_AtisVHF_Val", a?.freqs?.atisVhf ?: "")
        put("lblIcao_Val", a?.icao ?: "")
        put("lblIata_Val", "")
        put("lblTCN_Val", a?.tacan?.label ?: "")
        put("lblTCN_RNG_Val", a?.tacan?.rangeNm?.toString() ?: "")
        put("lblELV_Val", a?.elevationFt?.let { "$it'" } ?: "")
        put("lblMagVar", "")
        put("lblTrl_Val", "")
        val (lat, lon) = if (a != null) coords.label(a.x.toFloat(), a.y.toFloat()) else "" to ""
        put("lblLAT_Val", lat)
        put("lblLON_Val", lon)
        val rows = runwayRows()
        for (i in 1..4) {
            val r = rows.getOrNull(i - 1)
            put("lblRWY_Val_$i", r?.name ?: "")
            put("lblCRS_Val_$i", r?.let { ((it.course.roundToInt() % 360 + 360) % 360).toString().padStart(3, '0') } ?: "")
            put("lblILS_Val_$i", r?.ils ?: "")
            put("lblTodaRwy$i", "TODA: " + (r?.lengthFt?.takeIf { it > 0 }?.toString() ?: ""))
            put("lblLdaRwy$i", "LDA: " + (r?.lengthFt?.takeIf { it > 0 }?.toString() ?: ""))
            put("lblWidthRwy$i", "Width: " + (r?.widthFt?.takeIf { it > 0 }?.toString() ?: ""))
            val (n, e) = if (r?.north != null && r.east != null) coords.label(r.north.toFloat(), r.east.toFloat()) else "North" to "East"
            put("lblN_CoordsRwy$i", n)
            put("lblE_CoordsRwy$i", e)
        }
        for (k in 1..2) {
            val c = runwayCenter(k)
            val (n, e) = if (c != null) coords.label(c.first.toFloat(), c.second.toFloat()) else "North" to "East"
            put("lblCntNorthRwy$k", n)
            put("lblCntEastRwy$k", e)
        }
    }

    private fun runwayCenter(k: Int): Pair<Double, Double>? {
        // the chart's positions are feet about the field's own origin, which is itself in theater feet
        val af = airfield ?: return null
        val r = af.runways.getOrNull(k - 1) ?: return null
        if (r.ends.size < 2) return null
        return af.n + (r.ends[0].at.n + r.ends[1].at.n) / 2 to af.e + (r.ends[0].at.e + r.ends[1].at.e) / 2
    }

    private fun noAirport(): Boolean {
        if (airport != null) return false
        WdpDialogs.message("Airport Database", "Select an airport first (Select APT).")
        return true
    }

    /** `btnSet_UHF` / `btnSet_VHF`: the airport's tower frequency into the preset the box beside it names. */
    private fun setTowerFreq(vhf: Boolean) {
        if (noAirport()) return
        if (!page.blnCallsignDtcLoaded) return disabled("btnSet_UHF")
        val a = airport!!
        val f = (if (vhf) a.freqs?.towerVhf else a.freqs?.towerUhf)?.toDoubleOrNull()
        if (f == null) { WdpDialogs.message("Airport Database", "${a.name} has no ${if (vhf) "VHF" else "UHF"} tower frequency."); return }
        val n = page.c(if (vhf) "cboCOMM_2" else "cboCOMM_1").selectedItem?.toIntOrNull() ?: 15
        val r = page.m.radio
        if (vhf) { r.vhf?.set(n, (f * 1000).roundToInt()); r.vhfComment?.set(n, a.name + " tower") }
        else { r.uhf?.set(n, (f * 1000).roundToInt()); r.uhfComment?.set(n, a.name + " tower") }
        page.fillRadiodata()
        page.changed()
    }

    /**
     * `btnChart_Click` → `fclsChart`: the selected airport's charts ([DtcChartWindow]) — the field as the Taxi page draws
     * it from BMS's own data, its ramp for each runway end, and the app's instrument charts of it where there are any.
     * Every field of every theater has the first (1,720 of them), so the window always has something to show.
     */
    private fun openCharts() {
        if (noAirport()) return
        val a = airport!!
        val t = mission.theater
        launch {
            val list = t?.let { runCatching { Repo.charts(it.airportSet)[a.id.toString()] }.getOrNull() }.orEmpty()
            val field = airfield
                ?: t?.airfieldSet?.let { set -> runCatching { Repo.airfield(set, a.id) }.getOrNull() }
            val w = DtcChartWindow(a, field, list)
            if (w.isEmpty) WdpDialogs.message("Charts", "Falcon BMS's data has no layout of ${a.name} to draw, and there are no instrument charts of it.")
            else w.open()
        }
    }

    /** `btnTCN_Click`: the airport's TACAN into the cartridge's. */
    private fun useTacan() {
        if (noAirport()) return
        val t = airport!!.tacan ?: run { WdpDialogs.message("Airport Database", "${airport!!.name} has no TACAN."); return }
        page.choose("cboTACAN", if (t.band.equals("Y", true)) 1 else 0)
        page.type("mxtTACAN", t.channel.toString())
        page.changed()
    }

    /** `btnRWY_n_Click`: the runway's ILS frequency and course into the cartridge's. */
    private fun useIls(i: Int) {
        if (noAirport()) return
        val r = runwayRows().getOrNull(i - 1)
        if (r?.ils.isNullOrBlank()) { WdpDialogs.message("Airport Database", "RWY ${r?.name ?: i} has no ILS."); return }
        page.type("mxtILS_FREQ", r!!.ils!!)
        page.type("mxtILS_CRS", ((r.course.roundToInt() % 360 + 360) % 360).toString().padStart(3, '0'))
        page.changed()
    }

    /** A tap on a threshold's or a centre's coordinates: WDP's `SetRwyThr` / `SetRwyCnt`, a steerpoint there. */
    private fun runwayToStpt(i: Int, center: Boolean) {
        if (noAirport()) return
        val pos = if (center) runwayCenter(i) else runwayRows().getOrNull(i - 1)?.let { r -> r.north?.let { n -> r.east?.let { n to it } } }
        if (pos == null) { WdpDialogs.message("Airport Database", "The app has no chart of ${airport!!.name}'s runways to take that point from."); return }
        val (n, e) = coords.label(pos.first.toFloat(), pos.second.toFloat())
        val what = if (center) "centre of runway $i" else "threshold of RWY ${runwayRows().getOrNull(i - 1)?.name ?: i}"
        DtcAptToStptWindow(what, n, e) { nr ->
            val s = page.m.stpt[nr - 1]
            s.falconY = pos.first.toFloat(); s.falconX = pos.second.toFloat()
            // WDP's SetRwyThr: FalconZ = -1 * the field's elevation, as BMS writes a point's z (the save writes -|z|)
            s.falconZ = -(airport?.elevationFt ?: 0).toFloat()
            s.action = -1
            relabel()
        }.open()
    }

    /** `CreatePpt`: the airport as a PPT type of its own ("AFB"-style), after WDP's question. */
    private fun createPptType() {
        if (noAirport()) return
        val a = airport!!
        val code = (a.icao ?: a.name).filter { it.isLetterOrDigit() }.take(3).uppercase()
        WdpDialogs.message(
            "Question",
            "Are you sure you want to create a new PPT with: \nName: ${a.name}\nCode: $code",
            listOf("Yes", "No"),
        ) { answer ->
            if (answer != "Yes") return@message
            pptTable = pptTable + Triple(code, a.name.take(16), 0.1 * NM_TO_FT)
            pptRow = pptTable.size - 1
            pptTableChanged()
            version++
        }
    }

    companion object {
        const val NM_TO_FT = 6076.1157
        /** the title of the question when another flight comes with changes not saved (askAnotherFlight) */
        const val ANOTHER_FLIGHT = "Another flight"
        private const val PPT_KEY = "wdp_dtc_ppt_personal"
        private const val NO_PPT = "No PPT table yet: the theater Falcon BMS is on is not known"
        private const val THEATER_PPT = "The theater's own PPT types (Falcon BMS's Campaign\\Ppt.ini)"
        private const val BMS_DEFAULT = "BMS default"

        /** The RADIO/NAV coordinates a tap makes a steerpoint of: a runway threshold's, or the middle of a runway. */
        private val RUNWAY_POINT = Regex("lbl[NE]_CoordsRwy[0-9]|lblCnt(North|East)Rwy[0-9]")

        /** The RADIO/NAV boxes that show a preset by its number, bracketed: COMM 1/2's active preset. */
        private val PRESET_BOXES = setOf("cboPreset_1", "cboPreset_2")

        /** A no-break space: a trailing one is not trimmed off a right-aligned label, as a plain space is. */
        private const val NBSP = '\u00A0'

        /** A preset's number as the RADIO/NAV tab shows it: "[3]"; an empty box stays empty. */
        private fun bracketed(s: String?): String = if (s.isNullOrBlank()) "" else "[${s.trim()}]"

        /** The page's controls that do not need a cartridge ([worksWithoutCartridge]; the PPT management tab besides). */
        private val NO_CARTRIDGE_OK = setOf(
            "tabDTC", "tabNavOffsets", "btnPilot", "txtPilot", "btnStptInfo", "btnLineInfo", "btnTgtInfo", "btnPptInfo",
            "btnOpenInfo", "btnHpnInfo", "btnSelect", "btnChart", "btnCreatePPT", "btnGetCampFile",
        )

        /**
         * WDP's controls for what the app does not do, taken off the page (hidden, so the rest keeps WDP's layout):
         * - the **TE side** — WDP's `Mission.ini`, the Callsign/Mission switches on the point tabs, the copies between
         *   the two files and their lamps. Falcon BMS 4.38.1 does keep a TE's points in `Data\Campaign\<TE name>.ini`
         *   (User Manual 4.38.1 §5.1), but the page itself cannot tell which engagement BMS has loaded, so it offers no
         *   second cartridge. The engagement is known once the pilot opens it with Open mission…: then Save to DTC
         *   writes its targets, lines and PPTs into that file too (R3-PLAN A13, [DtcSource.saveTe]). The page shows
         *   the callsign's cartridge, which BMS loads for both.
         * - **Save List as jpg** (a picture file in WDP's folder) with the box that names that file (`txtTargetList`,
         *   which nothing else reads), and **Save PPT.ini** (WDP rewrote the theater's own `ppt.ini` inside Falcon BMS,
         *   which is not one of the writes the app allows itself).
         */
        val REMOVED: List<String> = listOf("grpTE", "btnSaveList", "txtTargetList", "btnSave") +
            listOf("", "Tgt", "Line", "Ppt", "Open", "Hpn").flatMap { t -> listOf("btn${t}TE", "lbl${t}MissionName") } +
            listOf("btnCampaign", "btnTgtCampaign", "btnLineCampaign", "btnPPTCampaign", "btnOpenCampaign", "btnHpnCampaign") +
            listOf("Stpt", "Tgt", "Line", "Ppt", "Open", "Hpn").flatMap { t ->
                listOf("btnCopy${t}MtoC", "btnCopy${t}CtoM", "pnl${t}MissionRed", "pnl${t}MissionGreen")
            }

        /** The tabs with a Save DTC button of their own (`btnSaveDtc<tab>`). */
        private val SAVE_TABS = listOf("Stpt", "Tgt", "Line", "Ppt", "Iff", "Open", "Hpn", "Ews", "Mfd", "Rad", "NavOffset", "Sys", "Wpn", "Harm")

        /**
         * The tabs whose own Save DTC button is **From mission…** now ([FROM_MISSION]): STPT, Targets, Lines, PPT, Open 1,
         * Open 2, RADIO/NAV, SYSTEMS (the laser codes) and HARM. The button is where WDP put it on each tab, so the page
         * keeps WDP's layout; what it opens is what the app knows of the mission, for that tab ([DtcFromMission]).
         */
        private val FROM_TABS = listOf("Stpt", "Tgt", "Line", "Ppt", "Open", "Hpn", "Rad", "Sys", "Harm")

        /** The From mission… buttons, one per tab of [FROM_TABS]. */
        val FROM_MISSION: List<String> = FROM_TABS.map { "btnSaveDtc$it" }

        const val FROM_MISSION_TEXT = "From mission…"

        /**
         * WDP's **Save DTC** on the tabs that are not From mission… ([FROM_TABS]): IFF, EWS, MFD, NAV OFFSETS and WEAPONS.
         * They are where WDP put them and do what the Planner's toolbar Save to DTC does (the cartridge in `User\Config`,
         * written as WDP writes it). They were off the page for a while in 1.3.8 test builds, as the toolbar's button;
         * pilots looked for them on the page, so they are back, beside the toolbar's.
         */
        val SAVE_DTC: List<String> = SAVE_TABS.filter { it !in FROM_TABS }.map { "btnSaveDtc$it" }

        /** What a place… call answers when it has put its question on screen instead ([asked]). */
        private const val ASKED = "answer the question"
        private const val FILL_ROUTE = "Fill from the route"
        private const val PICK_POINT = "Pick a point…"
        private const val COMM_PLAN = "Comm plan"
        private const val TACAN_MENU = "TACAN…"
        private const val ILS_MENU = "ILS…"
        private const val PICK_TEXT = "Pick a point: an airbase, a target, an air-defence site, a station or a steerpoint of the mission, " +
            "or a point on the map, into the slot you choose."
        private const val NO_ELEVATION = "Where the elevation is not known here it goes in at 0 ft: give it the ground's with Change " +
            "before the jet aims a weapon at it."
        private const val NO_LINES = "The mission has nothing to draw as a line yet: no tanker or AWACS track (the PC reads them out of " +
            "the campaign for the mission BMS is flying, and a flight opened with Open mission… brings its package's) and no flight plan."
        private const val NO_LASER = "The mission gives no laser codes: a save's flight carries one for each jet (open it with Open " +
            "mission…); a printed briefing has none."
        private const val NO_HARM = "The mission names no air-defence system the HARM tables know: neither the briefing's threat section " +
            "nor a known site."

        /** The app's ALIC codes as the HARM lists show them: "SA-2" for a whole system, "SA-2 Fan Song" for a radar. */
        fun harmCodesOf(codes: List<com.bmscompanion.app.data.AlicCode>): List<DtcHarmCode> = codes.mapNotNull { a ->
            val code = a.alic.trim().toIntOrNull() ?: return@mapNotNull null
            DtcHarmCode(listOfNotNull(a.system.trim(), a.radar?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" "), code, a.symbol.orEmpty())
        }.distinctBy { it.label }

        private const val INFO_STPT = "A flightplan is build up by steerpoints (STPT).\r\nThe flightplan can contain a maximum of 24 STPTs.\r\n" +
            "STPTs are the turning or timing point of your flightplan.\r\nIn Falcon these STPTs have a precision of 1km.\r\n" +
            "This can be improved by making precision STPTs of them.\r\nThe precision will than be less than 1 feet."
        private const val INFO_LINE = "Lines can be place anywhere you like.\r\nThey are shown in the MFD as white dotted lines.\r\n" +
            "The lines are used for many different functions, like\r\nAreas, Kill boxes, Tanker tracks, Save entry/exit routings, ect."
        private const val INFO_TGT = "You can create a list with a maximum of 100 targets.\r\nThe targets are the location of an object of your choice.\r\n" +
            "The list can be saved and taken on a flight.\r\nAlthough the Target list is saved in your DTC, this has no effect in BMS. (for now)\r\n" +
            "Use the list as you like."
        private const val INFO_PPT = "Pre-Planned threats (PPT) are circles that you can place at any point you like.\r\n" +
            "They are not linked to any threat, but placed by the user.\r\n" +
            "PPTs can be used for anything you like and you can create your own list with ranges and names.\r\n" +
            "The PPT is shown as a yellow circle on the MFD when you are outside of the circle.\r\nIf you are inside the circle, it will turn red."
    }
}

@Composable
fun rememberDtcWiring(): DtcWiring = remember { DtcWiring() }
