package com.bmscompanion.app.data.wdp

import kotlin.math.abs

/**
 * Weapon Delivery Planner's DTC page (`cntDTC`), Falcas's code, ported: the pilot's cartridge read into the
 * page's controls exactly as the page fills them, the page's own recomputations, and its handlers, over one
 * [DtcModel] (WDP's `fclsMain` tables). Saving goes through [DtcSave]; reading through [DtcLoad]. Pure: text in,
 * text out — where the file lives and how it is written safely is the caller's business.
 *
 * The page is a set of [DtcCtl]s named as WDP names them, created as the code first touches them with the designer's
 * values ([DtcDesigner]), and every handler is raised when Windows would raise it: filling a list from the cartridge
 * fires its handler, which writes the model back — so a value the page cannot show is replaced by what it shows, as
 * in WDP. [dump] lists every property the port holds, for the comparison with the real page and for the renderer.
 *
 * ### What is the wiring's, not the page's
 * The buttons that open WDP's child windows, the airport block, the line areas, the personal files and the TE
 * (`Mission.ini`) half of the page are `ui/screens/wdp/DtcWiring.kt`'s: it drives this page for what WDP's page
 * code does, and does the rest itself, the app's way (the dialogs over the app's own data, named copies in the
 * app's preferences instead of files on WDP's disk).
 *
 * ### Quirks kept
 * - Open steerpoints 7–9 never show their target name (their label is hidden either way).
 * - A page number the MFD lists do not hold leaves the box where it was.
 *
 * ### Falcon BMS 4.38.1's data, not WDP's
 * - **The HARM tab's systems are the app's** ([harmCodes], from `curated/harm.json`: the Dash-34 4.38's ALIC table,
 *   the Training Manual 4.38.1 and the Threat Guide), not WDP's `Database/HarmList.ini`, which listed SA-8, SA-9,
 *   SA-13, SA-19, Patriot, Nike and Skyguard with code -1 (picked, they wrote 0000 and showed "-1"), gave Patriot and
 *   Hawk one code (230, so a Patriot entry read back as Hawk), and had no SA-20 (D12). Both the whole-system code and
 *   the tracking radar's are offered (1xx and 2xx, "17" and "17T"): each is valid, they load different things.
 * - **A code the list does not hold is kept and shown as its number** ("Code 0620"), where WDP showed "Not in List"
 *   and an empty symbol — and wrote 0000 the moment the box was touched (D12).
 * - **One rule for the MFD selectors** (D14): when a box of a master mode takes a page, any other box of that mode
 *   showing the same page is blanked. WDP's 72 handlers were written out one by one and eleven of them were wrong —
 *   the centre selectors of the first two MFDs compared the fourth MFD's boxes with their LEFT box, and the third A-A
 *   centre selector cleared the A-G page's left box — so choosing a page could blank an unrelated MFD's page, or leave
 *   a duplicate. [DtcTables] keeps WDP's table, for the comparison with the program only.
 * - **No version branches**: the MFD lists are 4.38.1's (TCN included), the IFF and HARM tabs always fill, and the
 *   S-J MFD page always shows.
 * - **The ILS box keeps 108.10–111.95** (D11): WDP raised anything under 109.00 to 109.00.
 *
 * ### Quirks put right, because the page now writes what it shows
 * - The Open 2 (Harpoon) points print their own position, all ten of them (WDP took the Open table's east, and nine).
 * - Profile 2's burst altitude shows Profile 2's value; a Profile 1 pulse that is not a number takes Profile 1's.
 * - A nav-offset box left takes what is in the boxes, and keeps the reference (WDP took the Pop-up page's
 *   elevations and mode, so an edited elevation was thrown away).
 * - The IFF code digits are read from the right, padded to the code's width (WDP read them from the left, so a
 *   Mode 3 code of 0012 showed as 0022 — and was saved as that on the next change).
 * - A take-off steerpoint at the theater's origin no longer stops the page (WDP looked it up in its own airport
 *   table, which the app does not have).
 */
class DtcPage(val m: DtcModel = DtcModel()) {

    // ---------------------------------------------------------------- what the page is given

    /** The theater, for the lat/lon labels (`fclsMain.SetCoordData`). */
    var coords = PopupCoords.CoordData()
    /** The theater's `ppt.ini`: code, name, range in feet (`fclsMain.PPT`). */
    var pptTable: List<Triple<String, String, Double>> = emptyList()
    /** WDP's `VehToPpt.ini` (vehicle name → PPT name), empty unless given. */
    var vehToPpt: List<Pair<String, String>> = emptyList()
    /** The HARM tab's systems: the app's ALIC codes (`curated/harm.json`), set before [load]. */
    var harmCodes: List<DtcHarmCode> = emptyList()

    // ---------------------------------------------------------------- the page's own state (cntDTC's fields)

    var blnCallsignDtcLoaded = false; private set
    var blnMissionDtcLoaded = false; private set
    var blnCallsignSaved = false; private set
    var blnMissionSaved = false; private set
    private var blnCampTE = true
    private var blnReset = false
    /** Where the NAV OFFSETS came from: "None", "PopUp", "HADB", "TOSS", or "Else" (the cartridge's or typed). */
    internal var strProfile = "None"
    private var strSide = "True"
    private var harmLocked = false
    private var intCommNr = 0
    private var strUHF: String? = null
    private var strVHF: String? = null
    private var dblP1C1AD1 = 0.0
    private var dblP1C1AD2 = 0.0
    private var dblP1C2AD1 = 0.0
    private var dblP2C1AD1 = 0.0
    private var dblP2C1AD2 = 0.0
    private var dblP2C2AD1 = 0.0
    /** `fclsMain.strCallsignFile`. */
    var callsignFile = ""; private set

    private var harmSystems: List<DtcHarmCode> = emptyList()

    private val ctls = LinkedHashMap<String, DtcCtl>()

    /** The control of that name, with the designer's values the first time it is touched. */
    internal fun c(name: String): DtcCtl = ctls[name] ?: DtcCtl(name, DtcDesigner.all[name]).also { ctls[name] = it; wire(it) }

    internal fun controls(): Collection<DtcCtl> = ctls.values

    init {
        m.onIncl = { k, v -> c(INCL[k]).setCheckState(if (v) 1 else 0) }
    }

    // ---------------------------------------------------------------- .NET/VB

    private val vb = DtcVb

    private fun str(v: Double) = DataCardNet.str(v)
    private fun str(v: Float) = DataCardNet.str(v)
    private fun fmt(v: Double, p: String) = DataCardNet.fmt(v, p)
    private fun fmt(v: Float, p: String) = DataCardNet.fmt(v, p)
    private fun fmt(v: Int, p: String) = DataCardNet.fmt(v, p)
    private fun bankers(v: Double) = DataCardNet.bankers(v)

    /** `checked((int)Math.Round(v))`. */
    private fun roundChecked(v: Double): Int {
        val r = bankers(v)
        if (r.isNaN() || r > Int.MAX_VALUE.toDouble() || r < Int.MIN_VALUE.toDouble()) throw ArithmeticException("Arithmetic operation resulted in an overflow.")
        return r.toInt()
    }

    /** `Conversions.ToString(Math.Abs(Math.Round(z)))` — the elevation labels. */
    private fun elv(z: Float) = str(abs(bankers(z.toDouble())))

    private fun actionString(a: Int) = DataCardPlan.actionString(a)

    // ---------------------------------------------------------------- Load (cntDTC_Load)

    fun load() {
        for (n in listOf("btnCopyStptMtoC", "btnCopyStptCtoM", "btnCopyTgtMtoC", "btnCopyTgtCtoM", "btnCopyLineMtoC", "btnCopyLineCtoM",
            "btnCopyPptMtoC", "btnCopyPptCtoM", "btnClearStpt", "btnRebuildStptList")) c(n).enabled = false
        fillMfdBoxes()
        c("cboMode").select(0)
        c("cboProgram").select(0)
        c("cboCOMM_1").select(14)
        c("cboCOMM_2").select(14)
        c("cboTACAN").select(0)
        c("cboTACANFunc").select(0)
        fillHudColor()
        c("cboColor").select(0)
        c("cboSymWheelPos").select(0)
        c("cboScales").select(1)
        c("cboFPM").select(1)
        c("cboDED").select(2)
        c("cboVelocity").select(0)
        c("cboAlt").select(2)
        c("cboMasterMode").select(0)
        c("cboWideView").select(0)
        c("cboStartView").select(1)
        c("cboMasterArm").select(1)
        c("cboAIM9_Spot_Scan").select(0)
        c("cboAIM9_TD_BP").select(0)
        c("cboAIM120").select(0)
        c("cboPower").select(0)
        c("cboPowerDir").select(0)
        c("cboPowerWpt").select(0)
        c("cboP1_SubMode").select(0)
        c("cboP1_Fuze").select(0)
        c("cboP1_SGL_PAIR").select(0)
        c("cboP2_SubMode").select(0)
        c("cboP2_Fuze").select(0)
        c("cboP2_SGL_PAIR").select(0)
        harmSystems = harmCodes
        fillHarmCombo()
        // no TE mission is ever loaded in the port, so the page shows the cartridge's (campaign) side
        blnCampTE = !blnMissionDtcLoaded
        campTE()
        checkLabelColor()
    }

    // ---------------------------------------------------------------- the cartridge (GetCampFile)

    /**
     * `GetCampFile`: the cartridge's [text] (null when there is no such file: nothing happens) read, the page filled
     * from it. [path] is the file's path as WDP shows it (`strCallsignFile`). Exceptions WDP does not catch leave
     * here as they leave WDP's (the page stays as far as it got).
     */
    fun getCampFile(text: String?, path: String) {
        if (text == null) return
        callsignFile = path
        blnCallsignDtcLoaded = false
        c("lblCampSaved").visible = false
        val res = DtcLoad.loadCallsign(text, m, pptTable.map { it.first to it.second }, coords)
        checkLabelColor()   // LoadCallsign's last statement
        lastLoad = res
        afterLoad?.invoke()
        blnCallsignDtcLoaded = res.loaded
        if (blnCallsignDtcLoaded) setLoadError(res.loadError)
        val fileName = path.substringAfterLast('\\').substringAfterLast('/')
        for (n in FILE_LABELS) c(n).text = fileName
        // The offsets on the page are the cartridge's own until an attack page hands some over. WDP started from
        // "None", which zeroed them the first time the NAV OFFSETS tab was opened — and the page now saves what it
        // shows, so that would have wiped the pilot's offsets.
        strProfile = "Else"
        campTE()
        DtcLoad.navOffsets(text, m)
        fillNAVOFFSETSdata()
        blnReset = false
        if (blnCallsignDtcLoaded) blnCallsignSaved = true
        checkSavedStatus()
    }

    /** `fclsMain.SaveCallsign_DTC`: the cartridge written, and the page told it is saved. */
    fun saveDtc(text: String?): DtcSaveResult {
        val r = DtcSave.saveCallsign(text, m) { applyHarm() }
        if (!r.threw) {
            blnCallsignSaved = true
            blnCallsignDtcLoaded = true
            checkDTC()
        }
        return r
    }

    fun setLoadError(loadError: Int) {
        if (loadError == 0) { c("lblLoadError").text = ""; return }
        val names = listOf("Stpt ", "Ppt ", "Line ", "WpnTgt ", "Open ", "Harpoon ", "Ews ", "Mfd ", "Bullseye ", "Radio ", "Comm ",
            "NavOffset ", "Hud ", "Icp ", "Iff ", "View ", "Otw ", "Weapons ", "Harm ", "Laser ", "Aim ", "Agm ", "Agb ", "Snsr ", "light ")
        val sb = StringBuilder()
        for (i in names.indices) if ((loadError and (1 shl i)) != 0) sb.append(names[i])
        c("lblLoadError").text = sb.toString()
    }

    // ---------------------------------------------------------------- status (Check_DTC, Check_Saved_Status, Camp_TE)

    fun checkDTC() {
        if (blnCallsignDtcLoaded && !blnReset) {
            c("lblCampLoaded").text = "Data Loaded"; c("lblCampLoaded").back = GREEN
            c("lblCallsignfName").text = callsignFile; c("lblCallsignfName").visible = true
        } else {
            c("lblCampLoaded").text = "Data NOT Loaded"; c("lblCampLoaded").back = RED
            c("lblCallsignfName").visible = false
        }
        if (blnMissionDtcLoaded) {
            c("lblTELoaded").text = "Data Loaded"; c("lblTELoaded").back = GREEN
            c("lblTEfName").visible = true
        } else {
            c("lblTELoaded").text = "Data NOT Loaded"; c("lblTELoaded").back = RED
            c("lblTEfName").visible = false
        }
        val on = if (blnCampTE) blnCallsignDtcLoaded else blnMissionDtcLoaded
        c("btnClearStpt").enabled = on
        c("btnRebuildStptList").enabled = on
        checkSavedStatus()
    }

    private val callsignPanels = CALLSIGN_PANELS
    private val missionPanels = listOf("pnlStptMission", "pnlTgtMission", "pnlLineMission", "pnlPptMission", "pnlOpenMission", "pnlHpnMission")

    fun checkSavedStatus() {
        if (blnCallsignDtcLoaded) {
            if (!blnCallsignSaved) c("lblCampSaved").visible = false
            for (p in callsignPanels) { c(p + "Red").visible = !blnCallsignSaved; c(p + "Green").visible = blnCallsignSaved }
        } else {
            for (p in callsignPanels) { c(p + "Red").visible = true; c(p + "Green").visible = false }
        }
        if (!blnMissionSaved) c("lblTESaved").visible = false
        for (p in missionPanels) { c(p + "Red").visible = !blnMissionSaved; c(p + "Green").visible = blnMissionSaved }
    }

    fun campTE() {
        val camp = listOf("btnCampaign", "btnTgtCampaign", "btnLineCampaign", "btnPPTCampaign", "btnOpenCampaign", "btnHpnCampaign")
        val te = listOf("btnTE", "btnTgtTE", "btnLineTE", "btnPptTE", "btnOpenTE", "btnHpnTE")
        val labels = listOf("lblCamp_TE", "lblLineCamp_Te", "lblPptCamp_TE", "lblOpenCamp_TE", "lblHpnCamp_TE")
        if (blnCallsignDtcLoaded) {
            for (n in camp) c(n).enabled = false
            for (n in te) c(n).enabled = true
            c("lblCamp_TE").text = "Callsign.ini STPTs"
            c("lblLineCamp_Te").text = "Callsign.ini Lines"
            c("lblPptCamp_TE").text = "Callsign.ini PPTs"
            c("lblOpenCamp_TE").text = "Callsign.ini Open"
            c("lblHpnCamp_TE").text = "Callsign.ini Hpn"
            c("lblNavOffsets").text = "Callsign.ini Offsets"
            getCallsignCoords()
            profiles()
            fillEWS()
            fillMFD()
            fillRadiodata()
            fillNAVOFFSETSdata()
            fillSystemdata()
            fillWeapondata()
            fillIFFdata()
            fillHarmData()
        } else {
            val campNoTgt = camp - "btnTgtCampaign"
            val teNoTgt = te - "btnTgtTE"
            for (n in campNoTgt) c(n).enabled = !blnCampTE
            for (n in teNoTgt) c(n).enabled = blnCampTE
            val t = if (blnCampTE) "Campaign Coordinates" else "TE Coordinates"
            for (n in labels) c(n).text = t
            c("lblNavOffsets").text = if (blnCampTE) "Campaign Offsets" else "TE Offsets"
        }
        checkDTC()
    }

    fun checkLabelColor() {
        fun one(box: String, labels: List<String>, combos: List<String>) {
            val on = c(box).checkState == 1
            for (l in labels) c(l).fore = if (on) WHITE else GRAY
            for (k in combos) c(k).enabled = on
        }
        one("chbCmdsIncl", listOf("lblMode", "lblProgram"), listOf("cboMode", "cboProgram"))
        val hudOn = c("chbHudIncl").checkState == 1
        for (l in listOf("lblColor", "lblBrightness", "lblSymWheelPos", "lblScales", "lblFPM", "lblDED", "lblVelocity", "lblAlt")) c(l).fore = if (hudOn) WHITE else GRAY
        c("cboColor").enabled = hudOn
        c("txtBrightness").fore = GRAY
        for (k in listOf("cboSymWheelPos", "cboScales", "cboFPM", "cboDED", "cboVelocity", "cboAlt")) c(k).enabled = hudOn
        one("chbViewsIncl", listOf("lblWideView", "lblStartView"), listOf("cboWideView", "cboStartView"))
        one("chbMasterArmIncl", listOf("lblMasterArm"), listOf("cboMasterArm"))
        one("chbSnsrPowerIncl", listOf("lblRalt"), listOf("cboRalt"))
        one("chbIntLghtIncl", listOf("lblDedBrt"), listOf("cboDedBrt"))
    }

    // ---------------------------------------------------------------- steerpoints, PPTs, lines, targets, Open, Harpoon

    /**
     * A position as the page prints it: WDP's latitude and longitude for the theater BMS is on, or — where the
     * theater is not known, and there is no projection to print them with — the sim's own feet, north and east,
     * so a pilot still sees where every point is and can type one in. "00,00.000" stays the mark of an empty point.
     */
    private fun ne(y: Float, x: Float): Pair<String, String> =
        if (hasProjection) DtcLoad.northEast(coords, y, x) else feetLabels(y, x)

    /** Whether [coords] is a theater's projection rather than the all-zero one a page starts with. */
    val hasProjection: Boolean get() = coords.enableNewTerrain || coords.campW > 0.0

    /** The page told that something was edited outside its own handlers (a dialog, a preset): the red lamps. */
    fun changed() { blnCallsignSaved = false; checkSavedStatus() }

    fun getCallsignCoords() {
        for (i in 0 until 25) { val s = m.stpt[i]; val n = ne(s.falconY, s.falconX); s.north = n.first; s.east = n.second }
        for (i in 0 until 15) { val s = m.ppt[i]; val n = ne(s.falconY, s.falconX); s.north = n.first; s.east = n.second }
        for (i in 0 until 24) { val s = m.line[i]; val n = ne(s.falconY, s.falconX); s.north = n.first; s.east = n.second }
        for (i in 0 until 100) { val s = m.tgt[i]; val n = ne(s.falconY, s.falconX); s.north = n.first; s.east = n.second }
        for (i in 0 until 9) { val s = m.open[i]; val n = ne(s.falconY, s.falconX); s.north = n.first; s.east = n.second }
        for (i in 0 until 10) { val s = m.hpn[i]; val n = ne(s.falconY, s.falconX); s.north = n.first; s.east = n.second }
        fillLabelsSTPT()
        fillLabelsPPT()
        fillLabelsLines()
        fillLines()
        fillTargets()
        fillLabelsOpen()
        fillLabelsHpn()
    }

    private fun hidden(t: String) = t == "00,00.000" || t == ""

    fun fillLabelsSTPT() {
        for (i in 1..24) {
            val s = m.stpt[i - 1]
            c("lblSTPT_N_$i").text = s.north ?: ""
            c("lblSTPT_E_$i").text = s.east ?: ""
            c("lblElv_$i").text = elv(s.falconZ)
            c("lblAction$i").text = actionString(s.action)
        }
        labelVisCheckSTPT()
    }

    private fun labelVisCheckSTPT() {
        for (i in 1..24) {
            val show = !hidden(c("lblSTPT_N_$i").text)
            c("lblSTPT_$i").text = (if (i < 10) "STPT   $i: " else "STPT $i: ") + (if (show) "N" else "")
            for (n in listOf("lblSTPT_N_$i", "lblSTPT_E_$i", "lblElv_$i", "lblAction$i", "lblE_$i", "lblElvName_$i")) c(n).visible = show
        }
    }

    fun clearSTPT() {
        for (s in m.stpt) { s.falconX = 0f; s.falconY = 0f; s.falconZ = 0f; s.north = ""; s.east = "" }
        fillLabelsSTPT()
    }

    private fun samRng(vehName: String?): Double {
        val v = vehName ?: ""
        if (v == "") return 0.0
        var text = v
        for ((veh, ppt) in vehToPpt) if (veh == v) { text = ppt; break }
        if (text == "Search") return 151905.0
        var range = 0.0
        for ((_, name, rng) in pptTable) if (vb.lcase(name).contains(vb.lcase(text))) { range = rng; break }
        return range
    }

    fun fillLabelsPPT() {
        var flag = false
        var flag2 = false
        for (i in 1..15) {
            val p = m.ppt[i - 1]
            c("lblPPT_N_$i").text = p.north ?: ""
            c("lblPPT_E_$i").text = p.east ?: ""
            c("lblPPT_Elv_$i").text = str(abs(bankers(p.falconZ.toDouble()))) + "'"
            val num = (samRng(p.name) * FT_TO_NM.toDouble()).toFloat()
            val num2 = p.falconRng * FT_TO_NM
            val num3 = abs(num2 - num)
            c("lblPPT_RNG_$i").text = fmt(num2, "0.0") + "nm"
            if (num3 > 0.5f) { flag = true; c("lblPPT_RNG_$i").fore = ORANGE } else c("lblPPT_RNG_$i").fore = WHITE
            if ((p.name ?: "") != "") {
                c("lblPPT_Name_$i").text = p.name ?: ""
                c("lblPPT_Name_$i").fore = WHITE
            } else {
                flag2 = true
                c("lblPPT_Name_$i").text = p.code ?: ""
                c("lblPPT_Name_$i").fore = RED
            }
        }
        labelVisCheckPpt()
        c("lblPPT_RangeInfo").visible = flag
        c("lblPPT_TypeInfo").visible = flag2
    }

    private fun labelVisCheckPpt() {
        for (i in 1..15) {
            val show = !hidden(c("lblPPT_N_$i").text)
            c("lblPPT_$i").text = "PPT ${55 + i}:" + (if (show) " N" else "")
            for (n in listOf("lblP_E_$i", "lblPPT_T_$i", "lblPPT_N_$i", "lblPPT_E_$i", "lblPPT_Elv_$i", "lblPPT_Name_$i", "lblPPT_RNG_$i")) c(n).visible = show
        }
    }

    fun clearPPT() {
        for (p in m.ppt) { p.name = ""; p.falconX = 0f; p.falconY = 0f; p.falconZ = 0f; p.falconRng = 0f; p.code = "0"; p.north = ""; p.east = "" }
        fillLabelsPPT()
        labelVisCheckPpt()
    }

    fun fillLabelsLines() {
        for (i in 1..24) {
            val l = m.line[i - 1]
            c("lblLine_N_$i").text = l.north ?: ""
            c("lblLine_E_$i").text = l.east ?: ""
            c("lblL_Elv_$i").text = elv(l.falconZ)
        }
        for (i in 1..24) {
            val show = !hidden(c("lblLine_N_$i").text)
            c("lblL_$i").text = "Line Point ${30 + i}:" + (if (show) " N" else "")
            for (n in listOf("lblLine_N_$i", "lblLine_E_$i", "lblL_Elv_$i", "lblLe_$i", "lblLelv_$i")) c(n).visible = show
        }
    }

    /** `FindArea`, without the theater's area table: "No Line", "Random Line", or the overflow WDP's rounding throws. */
    fun findArea(lineNumber: Int): String {
        val first = when (lineNumber) { 1 -> 0; 2 -> 6; 3 -> 12; 4 -> 18; else -> 0 }
        val xs = IntArray(6); val ys = IntArray(6)
        for (k in 0 until 6) { xs[k] = roundChecked(m.line[first + k].falconX.toDouble()); ys[k] = roundChecked(m.line[first + k].falconY.toDouble()) }
        if ((xs[0] == 0) and (ys[0] == 0)) return "No Line"
        return "Random Line"
    }

    fun fillLines() {
        val t = (1..4).map { findArea(it) }
        for (k in 1..4) c("lblLine_$k").text = "Line $k: " + t[k - 1]
    }

    fun clearLines() {
        for (l in m.line) { l.falconX = 0f; l.falconY = 0f; l.falconZ = 0f; l.north = ""; l.east = "" }
        blnCallsignSaved = false
        checkSavedStatus()
        fillLabelsLines()
        fillLines()
    }

    /** The target list (dgvTargets): number, north, east, elevation, action, target. */
    var targetRows: List<List<String?>> = emptyList(); private set

    fun fillTargets() {
        val rows = ArrayList<List<String?>>()
        for (n in 0..99) {
            val t = m.tgt[n]
            rows += listOf((n + 1).toString(), t.north, t.east, str(abs(t.falconZ)), actionString(t.action), t.target)
        }
        targetRows = rows
    }

    fun clearTargets() {
        for (t in m.tgt) { t.falconX = 0f; t.falconY = 0f; t.falconZ = 0f; t.north = "00,00.000"; t.east = "000,00.000"; t.action = -1; t.target = "Not set" }
        fillTargets()
    }

    fun fillLabelsOpen() {
        for (i in 1..9) {
            val s = m.open[i - 1]
            c("lblOpen_N_$i").text = s.north ?: ""
            c("lblOpen_E_$i").text = s.east ?: ""
            c("lblOpen_Elv_$i").text = elv(s.falconZ)
            c("lblOpenAction$i").text = actionString(s.action)
            c("lblOpenTarget_$i").text = s.target ?: ""
        }
        for (i in 1..9) {
            val show = !hidden(c("lblOpen_N_$i").text)
            c("lblOpen_$i").text = "STPT ${80 + i}: " + (if (show) "N" else "")
            for (n in listOf("lblOpen_N_$i", "lblOpen_E_$i", "lblOpen_Elv_$i", "lblOpenAction$i", "lblOe_$i", "lblOpenElvName_$i")) c(n).visible = show
            c("lblOpenTarget_$i").visible = show && i <= 6
        }
    }

    fun clearOpen() {
        for (s in m.open) { s.falconX = 0f; s.falconY = 0f; s.falconZ = 0f; s.north = ""; s.east = "" }
        fillLabelsOpen()
    }

    fun fillLabelsHpn() {
        for (i in 1..10) {
            val s = m.hpn[i - 1]
            c("lblHpn_N_$i").text = s.north ?: ""
            c("lblHpn_E_$i").text = s.east ?: ""
            c("lblHpn_Elv_$i").text = elv(s.falconZ)
            c("lblHpnAction$i").text = actionString(s.action)
            c("lblHpnTarget_$i").text = s.target ?: ""
        }
        for (i in 1..10) {
            val show = !hidden(c("lblHpn_N_$i").text)
            c("lblHpn_$i").text = "STPT ${89 + i}: " + (if (show) "N" else "")
            for (n in listOf("lblHpn_N_$i", "lblHpn_E_$i", "lblHpn_Elv_$i", "lblHpnAction$i", "lblHe_$i", "lblHpnElvName_$i")) c(n).visible = show
        }
    }

    fun clearHpn() {
        for (s in m.hpn) { s.falconX = 0f; s.falconY = 0f; s.falconZ = 0f; s.north = ""; s.east = "" }
        fillLabelsHpn()
    }

    // ---------------------------------------------------------------- EWS

    private fun ewsBox(q: String, cf: Char, p: Int) = "mxt${q}_$cf$p"

    fun fillEWS() {
        val e = m.ews
        c("chbREQJAM").setCheckState(if (e.reqjam) 1 else 0)
        c("chbREQCTR").setCheckState(if (e.reqctr) 1 else 0)
        c("chbBingo").setCheckState(if (e.bingo) 1 else 0)
        c("chbFeedback").setCheckState(if (e.fdbk) 1 else 0)
        c("mxtBingo_Flare").text = e.flareBingo.toString()
        c("mxtBingo_Chaff").text = e.chaffBingo.toString()
        for (p in 1..6) {
            try {
                if (p == 1) {
                    val pr = e.program ?: throw NullPointerException("Program")
                    if (pr.size < 6) e.program = Array(6) { i -> if (i < pr.size) pr[i] else DtcEwsProgram() }
                }
                val g = (e.program ?: throw NullPointerException("Program"))[p - 1]
                c(ewsBox("BQ", 'C', p)).text = g.chaffBQ.toString()
                c(ewsBox("BI", 'C', p)).text = fmt(g.chaffBI.toDouble() / 1000.0, "#0.000")
                c(ewsBox("SQ", 'C', p)).text = g.chaffSQ.toString()
                c(ewsBox("SI", 'C', p)).text = fmt(g.chaffSI.toDouble() / 1000.0, "#0.000")
                c(ewsBox("BQ", 'F', p)).text = g.flareBQ.toString()
                c(ewsBox("BI", 'F', p)).text = fmt(g.flareBI.toDouble() / 1000.0, "#0.000")
                c(ewsBox("SQ", 'F', p)).text = g.flareSQ.toString()
                c(ewsBox("SI", 'F', p)).text = fmt(g.flareSI.toDouble() / 1000.0, "#0.000")
                c("txtComment$p").text = g.comment ?: ""
            } catch (ex: Exception) {
                for (q in listOf("BQ", "BI", "SQ", "SI")) { c(ewsBox(q, 'C', p)).text = "0"; c(ewsBox(q, 'F', p)).text = "0" }
                c("txtComment$p").text = ""
            }
        }
        val mode = c("cboMode")
        mode.select(if (e.modeSelection < mode.items.size) e.modeSelection else 0)
        val prog = c("cboProgram")
        prog.select(if (e.numberSelection < prog.items.size) e.numberSelection else 0)
    }

    fun ewsChange() {
        val e = m.ews
        e.reqjam = c("chbREQJAM").checkState != 0
        e.reqctr = c("chbREQCTR").checkState != 0
        e.bingo = c("chbBingo").checkState != 0
        e.fdbk = c("chbFeedback").checkState != 0
        e.flareBingo = vb.toByte(c("mxtBingo_Flare").text)
        e.chaffBingo = vb.toByte(c("mxtBingo_Chaff").text)
        for (p in 1..6) {
            val pr = (e.program ?: throw NullPointerException("Program"))[p - 1]
            fun q(box: String): Int { val t = c(box).text; return vb.toInteger(if (vb.isNumeric(t)) t else "0") }
            fun i(box: String): Int { val t = c(box).text; return vb.toInteger(if (vb.isNumeric(t)) str(vb.toDouble(t) * 1000.0) else "0") }
            pr.chaffBQ = q(ewsBox("BQ", 'C', p)); pr.chaffBI = i(ewsBox("BI", 'C', p))
            pr.chaffSQ = q(ewsBox("SQ", 'C', p)); pr.chaffSI = i(ewsBox("SI", 'C', p))
            pr.flareBQ = q(ewsBox("BQ", 'F', p)); pr.flareBI = i(ewsBox("BI", 'F', p))
            pr.flareSQ = q(ewsBox("SQ", 'F', p)); pr.flareSI = i(ewsBox("SI", 'F', p))
        }
        e.modeSelection = c("cboMode").selectedIndex
        e.numberSelection = c("cboProgram").selectedIndex
        blnCallsignSaved = false
        checkSavedStatus()
    }

    private fun clearEws() {
        val e = m.ews
        e.reqjam = false; e.reqctr = false; e.bingo = false; e.fdbk = false; e.flareBingo = 0; e.chaffBingo = 0
        for (n in 0..5) {
            val p = (e.program ?: throw NullPointerException("Program"))[n]
            p.chaffBQ = 0; p.chaffBI = 0; p.chaffSQ = 0; p.chaffSI = 0; p.flareBQ = 0; p.flareBI = 0; p.flareSQ = 0; p.flareSI = 0
        }
        e.modeSelection = 0; e.numberSelection = 0
        fillEWS()
        blnCallsignSaved = false
        checkSavedStatus()
    }

    // ---------------------------------------------------------------- MFD

    /** 4.38.1's MFD page numbers (User Manual 4.38.1 §5.1.4; BMS's own `MFD_Def.ini`: FCR 11, FLCS 6, TEST 4 …). */
    private fun mfdToInteger(name: String?): Int = when (name) {
        "TFR" -> 2; "FLIR" -> 3; "TEST" -> 4; "DTE" -> 5; "FLCS" -> 6; "WPN" -> 7; "TGP" -> 8; "TCN" -> 9; "HSD" -> 10; "FCR" -> 11
        "SMS" -> 12; "RWR" -> 13; "HUD" -> 14; "HAD" -> 16; else -> 0
    }

    private fun mfdToString(n: Int): String = when (n) {
        2 -> "TFR"; 3 -> "FLIR"; 4 -> "TEST"; 5 -> "DTE"; 6 -> "FLCS"; 7 -> "WPN"; 8 -> "TGP"; 9 -> "TCN"; 10 -> "HSD"; 11 -> "FCR"
        12 -> "SMS"; 13 -> "RWR"; 14 -> "HUD"; 16 -> "HAD"; else -> ""
    }

    fun fillMfdBoxes() {
        val list = listOf(" ", "TFR", "FLIR", "TEST", "DTE", "FLCS", "WPN", "TGP", "TCN", "HSD", "FCR", "SMS", "RWR", "HUD", "HAD")
        for (md in MFD_PAGES) {
            val names = (1..4).flatMap { i -> POS.map { "cbo$md${i}_$it" } }
            for (n in names) c(n).clearItems()
            for (item in list) for (n in names) c(n).items.add(item)
            for (n in names) c(n).sortItems()
        }
    }

    private fun mfdArray(mode: Int): Array<DtcMfd> = when (mode) {
        0 -> m.mfd.aG; 1 -> m.mfd.aA; 2 -> m.mfd.nav; 3 -> m.mfd.msl; 4 -> m.mfd.dgf; else -> m.mfd.sJ
    } ?: throw NullPointerException("CampMFD")

    fun fillMFD() {
        for ((mode, md) in MFD_PAGES.withIndex()) {
            for (i in 1..4) {
                val a = mfdArray(mode)[i - 1]
                val texts = listOf(mfdToString(a.left), mfdToString(a.center), mfdToString(a.right))
                val csel = a.csel
                for ((k, pos) in POS.withIndex()) {
                    val box = c("cbo$md${i}_$pos")
                    if (texts[k] == "") box.select(0) else box.selectItem(texts[k])
                }
                when (csel) {
                    0 -> c("chb${md}_MFD${i}_Right").setCheckState(1)
                    1 -> c("chb${md}_MFD${i}_Center").setCheckState(1)
                    2 -> c("chb${md}_MFD${i}_Left").setCheckState(1)
                    else -> for (pos in POS) c("chb${md}_MFD${i}_$pos").setCheckState(0)
                }
            }
        }
        c("chbBullseye").setCheckState(if (m.bullseyeInfoOnMfd) 1 else 0)
    }

    private fun clearAllSettings() {
        for (md in MFD_PAGES.take(5)) {
            for (v in listOf(1, 0)) for (i in 1..4) for (pos in POS) c("cbo$md${i}_$pos").select(v)
        }
        for (md in MFD_PAGES.take(5)) {
            for (i in 1..4) for (pos in POS) c("chb${md}_MFD${i}_$pos").setChecked(false)
            for (i in 1..4) c("chb${md}_MFD${i}_Center").setChecked(true)
        }
    }

    // ---------------------------------------------------------------- radio

    fun fillRadiodata() {
        if (!blnCallsignDtcLoaded) return
        val r = m.radio
        for (i in 1..20) c("lblUHF_Val_$i").text = fmt((r.uhf ?: throw NullPointerException("UHF"))[i].toDouble() / 1000.0, "#0.000")
        for (i in 1..20) c("lblVHF_Val_$i").text = fmt((r.vhf ?: throw NullPointerException("VHF"))[i].toDouble() / 1000.0, "#0.000")
        for (i in 1..20) c("txtUHF_$i").text = (r.uhfComment ?: throw NullPointerException("UHFcomment"))[i] ?: ""
        for (i in 1..20) c("txtVHF_$i").text = (r.vhfComment ?: throw NullPointerException("VHFcomment"))[i] ?: ""
        val cm = m.comm
        c("cboPreset_1").select(cm.comm1)
        c("cboPreset_2").select(cm.comm2)
        c("txtComm1_Comment").text = cm.comm1Comment ?: ""
        c("txtComm2_Comment").text = cm.comm2Comment ?: ""
        c("mxtTACAN").text = cm.tacanChannel.toString()
        c("cboTACAN").select(if (cm.tacanBand == 1) 1 else 0)
        c("cboTACANFunc").select(if (cm.tacanDomain == 1) 1 else 0)
        cm.ilsFrequency = DtcLoad.ilsInBand(cm.ilsFrequency)
        c("mxtILS_FREQ").text = fmt(cm.ilsFrequency.toDouble() / 100.0, "#0.00")
        c("mxtILS_CRS").text = cm.ilsCrs.toString()
        checkFreq()
    }

    private fun resetUhfVhfBackcolor() {
        for (i in 1..20) c("lblUHF_Val_$i").back = TRANSPARENT
        for (i in 1..20) c("lblVHF_Val_$i").back = TRANSPARENT
    }

    fun checkFreq() {
        resetUhfVhfBackcolor()
        if (strSide != "Blue") {
            if (strSide == "Blue Manual") strSide = "Blue"
            else if (strSide != "Red") strSide = if (strSide == "Red Manual") "Red" else "Blue"
        }
        var num = 0
        var num2 = 0
        fun pass(uhf: List<String>, vhf: List<String>) {
            for (i in 1..20) if (c("lblUHF_Val_$i").text != uhf[i - 1]) { num++; c("lblUHF_Val_$i").back = ORANGE; strSide = "Manual" }
            for (i in 1..20) if (c("lblVHF_Val_$i").text != vhf[i - 1]) { num2++; c("lblVHF_Val_$i").back = ORANGE; strSide = "Manual" }
        }
        while (true) {
            if (strSide == "Blue") {
                pass(BLUE_UHF, BLUE_VHF)
                if (strSide == "Manual") strSide = "Blue Manual"
                if (!(num > 6 && num2 > 6)) break
                strSide = "Red"
                num = 0; num2 = 0
                resetUhfVhfBackcolor()
                continue
            }
            if (strSide == "Red") {
                pass(RED_UHF, RED_VHF)
                if (strSide == "Manual") strSide = "Red Manual"
                if (num > 6 && num2 > 6) { resetUhfVhfBackcolor(); strSide = "Manual" }
            }
            break
        }
        setSide()
    }

    private fun setSide() {
        val s = c("lblSide")
        when (strSide) {
            "Blue" -> { s.text = "Blue side"; s.back = LIGHTBLUE }
            "Blue Manual" -> { s.text = "Blue Manual"; s.back = ORANGE }
            "Red" -> { s.text = "Red side"; s.back = RED }
            "Red Manual" -> { s.text = "Red Manual"; s.back = ORANGE }
            "Manual" -> { s.text = "Manual"; s.back = ORANGE }
        }
    }

    /** `ChangeCOMM` after the frequency dialog was accepted with [typed] (as its "000.000" box reads it back). */
    private fun changeComm(typed: String) {
        val box = DtcMaskedText("000.000", '_', false, true)
        box.set(typed)
        if (intCommNr < 25) strUHF = box.text() else if (intCommNr > 25) strVHF = box.text()
        val r = m.radio
        when (intCommNr) {
            in 1..20 -> {
                val l = c("lblUHF_Val_$intCommNr"); l.text = strUHF ?: ""
                (r.uhf ?: throw NullPointerException("UHF"))[intCommNr] = roundChecked(vb.toDouble(l.text) * 1000.0)
            }
            in 31..50 -> {
                val k = intCommNr - 30
                val l = c("lblVHF_Val_$k"); l.text = strVHF ?: ""
                (r.vhf ?: throw NullPointerException("VHF"))[k] = roundChecked(vb.toDouble(l.text) * 1000.0)
            }
        }
        blnCallsignSaved = false
        checkFreq()
        checkSavedStatus()
    }

    fun arrayComms() {
        val cm = m.comm
        cm.comm1 = c("cboPreset_1").selectedIndex
        cm.comm2 = c("cboPreset_2").selectedIndex
        if (!vb.isNumeric(c("mxtTACAN").text)) c("mxtTACAN").text = "0"
        cm.tacanChannel = vb.toInteger(c("mxtTACAN").text)
        if (!vb.isNumeric(c("mxtILS_FREQ").text)) c("mxtILS_FREQ").text = "0"
        // kept inside 4.38.1's ILS band (BMS answers anything else with "Frequency out of range detected!"), and the
        // box shows what will be written
        val ils = DtcLoad.ilsInBand(roundChecked(vb.toDouble(c("mxtILS_FREQ").text) * 100.0))
        if (ils != roundChecked(vb.toDouble(c("mxtILS_FREQ").text) * 100.0)) c("mxtILS_FREQ").text = fmt(ils.toDouble() / 100.0, "#0.00")
        cm.ilsFrequency = ils
        if (!vb.isNumeric(c("mxtILS_CRS").text)) c("mxtILS_CRS").text = "0"
        cm.ilsCrs = vb.toInteger(c("mxtILS_CRS").text)
        blnCallsignSaved = false
    }

    // ---------------------------------------------------------------- nav offsets

    fun mode() {
        when (m.nav.modesel) {
            0 -> { c("pnlRefCenter").visible = true; c("pnlRefLeft").visible = false; c("pnlRefRight").visible = false }
            1 -> { c("pnlRefLeft").visible = true; c("pnlRefCenter").visible = false; c("pnlRefRight").visible = false }
            2 -> { c("pnlRefRight").visible = true; c("pnlRefLeft").visible = false; c("pnlRefCenter").visible = false }
        }
    }

    private fun copyOffsets(from: DtcNavOffsets) {
        val nv = m.nav
        nv.modesel = from.modesel
        for ((a, b) in listOf(nv.vip to from.vip, nv.vipPup to from.vipPup, nv.vrp to from.vrp, nv.vrpPup to from.vrpPup,
            nv.oa1_1 to from.oa1_1, nv.oa2_1 to from.oa2_1, nv.oa1_2 to from.oa1_2, nv.oa2_2 to from.oa2_2)) {
            a.stpt = b.stpt; a.bearing = b.bearing; a.range = b.range; a.elv = b.elv
        }
    }

    private fun profileButtons(disabled: String?, notice: String) {
        for (b in listOf("btnNone", "btnPopUp", "btnHADB", "btnTOSS")) c(b).enabled = b != disabled
        c("lblNotice").text = notice
    }

    fun profiles() {
        when (strProfile) {
            "None" -> {
                val nv = m.nav
                for (o in listOf(nv.vip, nv.vipPup, nv.vrp, nv.vrpPup)) { o.stpt = 0; o.bearing = 0f; o.range = 0; o.elv = 0 }
                for ((o, s) in listOf(nv.oa1_1 to 3, nv.oa2_1 to 3, nv.oa1_2 to 4, nv.oa2_2 to 4)) { o.stpt = s; o.bearing = 0f; o.range = 0; o.elv = 0 }
                profileButtons(null, "No Data")
            }
            "PopUp" -> { copyOffsets(m.popUpNav); profileButtons("btnPopUp", "Pop Up Data") }
            "HADB" -> { copyOffsets(m.hadbNav); profileButtons("btnHADB", "HADB Data") }
            "TOSS" -> { copyOffsets(m.tossNav); profileButtons("btnTOSS", "TOSS Data") }
            "Else" -> profileButtons(null, "Data from DTC")
        }
        fillNAVOFFSETSdata()
    }

    private val offsetBoxes = listOf("VIP", "VIPPUP", "VIPOA1", "VIPOA2", "VRP", "VRPPUP", "VRPOA1", "VRPOA2")
    private fun offsetOf(box: String): DtcOffset = when (box) {
        "VIP" -> m.nav.vip; "VIPPUP" -> m.nav.vipPup; "VIPOA1" -> m.nav.oa1_1; "VIPOA2" -> m.nav.oa2_1
        "VRP" -> m.nav.vrp; "VRPPUP" -> m.nav.vrpPup; "VRPOA1" -> m.nav.oa1_2; else -> m.nav.oa2_2
    }

    fun fillNAVOFFSETSdata() {
        mode()
        fun intBox(n: String, v: Int) {
            val b = c(n); val d = DtcDec.of(v)
            b.setValue(if (d < b.minimum) b.minimum else if (d > b.maximum) b.maximum else d)
        }
        for (box in offsetBoxes) {
            val o = offsetOf(box)
            intBox("num${box}_STPTnr", o.stpt)
            val brg = c("num${box}_BRG_VAL")
            brg.setValue(if (o.bearing < brg.minimum.toSingle()) brg.minimum else if (o.bearing > brg.maximum.toSingle()) brg.maximum else DtcDec.ofFloat(o.bearing))
            intBox("num${box}_RNG_VAL", o.range)
            intBox("num${box}_ELV_VAL", o.elv)
        }
    }

    /** A nav-offset box was left: the boxes are the offsets now, and the page says they are the pilot's own. */
    fun manualData() {
        fun v(n: String) = c(n).value
        for (box in offsetBoxes) {
            val o = offsetOf(box)
            o.stpt = v("num${box}_STPTnr").toInt32()
            o.bearing = v("num${box}_BRG_VAL").toSingle()
            o.range = v("num${box}_RNG_VAL").toInt32()
            o.elv = v("num${box}_ELV_VAL").toInt32()
        }
        strProfile = "Else"
        profileButtons(null, "Data from DTC")
    }

    // ---------------------------------------------------------------- systems

    private fun fillHudColor() {
        val k = c("cboColor")
        k.clearItems()
        k.items.add("Green")
    }

    fun fillSystemdata() {
        val h = m.hud
        c("cboScales").select(if (h.scales in 0..2) h.scales else 1)
        c("txtBrightness").text = h.brightness.toString()
        c("cboFPM").select(if (h.fpm in 0..2) h.fpm else 1)
        c("cboDED").select(when (h.ded) { 0 -> 0; 1 -> 2; 2 -> 1; else -> 2 })
        c("cboVelocity").select(if (h.velocity in 0..2) h.velocity else 1)
        c("cboAlt").select(if (h.alt in 0..2) h.alt else 1)
        c("cboSymWheelPos").select(if (h.symWheelPos >= 750) 0 else 1)
        c("cboMasterMode").select(if (m.icp.masterMode in 0..2) m.icp.masterMode else 0)
        c("mxtALOW_AGL").text = str(m.icp.alowAgl)
        c("mxtALOW_MSL").text = m.icp.alowMsl.toString()
        c("mxtALOW_TF").text = m.icp.alowTfAdv.toString()
        c("mxtWingSpan").text = str(m.icp.manualWingspan)
        c("mxtBingo").text = str(m.icp.bingoFuel)
        c("cboWideView").select(if (m.wideView == 1) 1 else 0)
        c("cboStartView").select(if (m.otwMode > 0) checkedMinus1(m.otwMode) else 0)
        c("cboMasterArm").select(when (m.masterArm) { 0 -> 1; 1 -> 2; 2 -> 0; else -> 1 })
        c("mxtLaser").text = m.laser.laserSt.toString()
        c("mxtLaserCode").text = m.laser.laserCode.toInt().toString()
        c("mxtLaserLST").text = m.laser.lstCode.toInt().toString()
        c("cboRalt").select(when (m.ralt) { 0 -> 2; 1 -> 1; 2 -> 0; else -> 2 })
        c("cboDedBrt").select(when (m.dedLight) { 0 -> 2; 1 -> 1; 2 -> 0; else -> 0 })
        checkLabelColor()
    }

    private fun checkedMinus1(v: Int): Int { if (v == Int.MIN_VALUE) throw ArithmeticException("Arithmetic operation resulted in an overflow."); return v - 1 }
    private fun checkedPlus1(v: Int): Int { if (v == Int.MAX_VALUE) throw ArithmeticException("Arithmetic operation resulted in an overflow."); return v + 1 }

    // ---------------------------------------------------------------- weapons

    private fun adText(d: Double) = if (!(d < 10.0)) fmt(d, "#.00") else fmt(d, "0#.00")

    private fun baText(ba: Int, other: Int): String = when {
        ba < 10 -> "000$ba"
        ba < 100 && ba >= 10 -> "00$other"
        !(ba < 1000 && ba >= 100) -> ba.toString()
        else -> "0$ba"
    }

    fun fillWeapondata() {
        c("cboAIM9_Spot_Scan").select(m.aim.spotScan)
        c("cboAIM9_TD_BP").select(m.aim.tdBp)
        c("cboAIM120").select(m.aim.targetSize)
        c("cboPower").select(if (m.agm.mavAutoPwr == 1) 1 else 0)
        c("cboPowerDir").select(if (m.agm.mavAutoPwrDir > 0) checkedMinus1(m.agm.mavAutoPwrDir) else 0)
        c("cboPowerWpt").select(if (m.agm.mavAutoPwrWpt < 20) m.agm.mavAutoPwrWpt else 0)
        val p1 = m.agb1
        c("cboP1_SubMode").select(when (p1.submode) { 7 -> 1; 8 -> 0; 9 -> 2; 10 -> 3; else -> 1 })
        c("cboP1_Fuze").select(if (p1.fuze in 0..2) p1.fuze else 0)
        c("cboP1_SGL_PAIR").select(if (p1.sglPair == 1) 1 else 0)
        c("mxtP1_Spacing").text = p1.releaseSpacing.toString()
        c("mxtP1_Pulse").text = p1.releasePulse.toString()
        c("mxtP1_Angle").text = p1.releaseAngle.toString()
        dblP1C1AD1 = (p1.c1Ad1 / 100f).toDouble()
        c("mxtP1_C1_AD1").text = adText(dblP1C1AD1)
        dblP1C1AD2 = (p1.c1Ad2 / 100f).toDouble()
        c("mxtP1_C1_AD2").text = adText(dblP1C1AD2)
        dblP1C2AD1 = (p1.c2Ad / 100f).toDouble()
        c("mxtP1_C2_AD").text = adText(dblP1C2AD1)
        c("mxtP1_C2_BA").text = baText(p1.c2Ba, p1.c2Ba)
        val p2 = m.agb2
        c("cboP2_SubMode").select(when (p2.submode) { 7 -> 1; 8 -> 0; 9 -> 2; 10 -> 3; else -> 1 })
        c("cboP2_Fuze").select(if (p2.fuze in 0..2) p2.fuze else 0)
        c("cboP2_SGL_PAIR").select(if (p2.sglPair == 1) 1 else 0)
        c("mxtP2_Spacing").text = p2.releaseSpacing.toString()
        c("mxtP2_Pulse").text = p2.releasePulse.toString()
        c("mxtP2_Angle").text = p2.releaseAngle.toString()
        dblP2C1AD1 = (p2.c1Ad1 / 100f).toDouble()
        c("mxtP2_C1_AD1").text = adText(dblP2C1AD1)
        dblP2C1AD2 = (p2.c1Ad2 / 100f).toDouble()
        c("mxtP2_C1_AD2").text = adText(dblP2C1AD2)
        dblP2C2AD1 = (p2.c2Ad / 100f).toDouble()
        c("mxtP2_C2_AD").text = adText(dblP2C2AD1)
        c("mxtP2_C2_BA").text = baText(p2.c2Ba, p2.c2Ba)
    }

    // ---------------------------------------------------------------- IFF

    fun fillIFFdata() {
        val x = m.iff
        c("chbMode1").setChecked(x.mode1On > 0)
        c("chbMode2").setChecked(x.mode2On > 0)
        c("chbMode3A").setChecked(x.mode3aOn > 0)
        c("chbMode4").setChecked(x.mode4On > 0)
        c("chbModeC").setChecked(x.modeCOn > 0)
        c("chbModeS").setChecked(x.modeSOn > 0)
        // the code's digits counted from the right, as a code of 0012 is written 12: padded to the code's width
        fun digits(prefix: String, code: Int, width: Int) {
            val t = abs(code).toString().padStart(width, '0').takeLast(width)
            for (k in 1..width) {
                val b = c("$prefix$k")
                val d = t[k - 1] - '0'
                b.setValue(if (d in 0..9 && DtcDec.of(d) <= b.maximum) DtcDec.of(d) else DtcDec.ZERO)
            }
        }
        digits("numMode1_D", x.mode1Code.toInt(), 2)
        digits("numMode2_D", x.mode2Code.toInt(), 4)
        digits("numMode3A_D", x.mode3aCode.toInt(), 4)
    }

    // ---------------------------------------------------------------- HARM

    private fun tbl(t: Int, h: Int) = "Tbl${t}_Thr$h"

    fun fillHarmCombo() {
        for (t in 1..3) for (h in 1..5) c("cbo" + tbl(t, h)).clearItems()
        for (t in 1..3) for (h in 1..5) c("cbo" + tbl(t, h)).items.add(NOT_IN_LIST)
        for (s in harmSystems) for (t in 1..3) for (h in 1..5) c("cbo" + tbl(t, h)).items.add(s.label)
    }

    /** The first system with [code] (SA-9 and SA-13 share Dog Ear's 609; WDP took the last, so Patriot read as Hawk). */
    private fun getHarmSys(code: Int): Int = harmSystems.indexOfFirst { it.code == code }
    private fun getHarmSysByName(name: String?): Int = harmSystems.indexOfFirst { it.label == (name ?: "") }

    private fun threat(t: DtcHarmThreats, h: Int) = when (h) { 1 -> t.threat0; 2 -> t.threat1; 3 -> t.threat2; 4 -> t.threat3; else -> t.threat4 }

    fun fillHarmData() {
        harmLocked = true
        val tb = m.harm.table ?: throw NullPointerException("CampHarm.Table")
        for (t in 1..3) for (h in 1..5) c("num" + tbl(t, h)).setValue(DtcDec.of(threat(tb[t - 1], h)))
        for (t in 1..3) for (h in 1..5) {
            val code = c("num" + tbl(t, h)).value.toInt32()
            val i = getHarmSys(code)
            val box = c("cbo" + tbl(t, h))
            if (i != -1) { box.selectItem(harmSystems[i].label); c("lbl" + tbl(t, h)).text = harmSystems[i].symbol }
            else if (code == 0) { box.select(0); c("lbl" + tbl(t, h)).text = "" }
            else {
                // a code in no list (a later BMS's, or one typed in BMS's own DTE page) is shown as its number and kept
                val item = unknownCode(code)
                if (item !in box.items) box.items.add(item)
                box.selectItem(item)
                c("lbl" + tbl(t, h)).text = ""
            }
        }
        val hm = m.harm
        if (hm.mode == 1) { c("rbnPOS").setChecked(false); c("rbnHAS").setChecked(true) }
        else { c("rbnPOS").setChecked(true); c("rbnHAS").setChecked(false) }
        when (hm.subMode) {
            1 -> { c("rbnPB").setChecked(false); c("rbnEom").setChecked(true); c("rbnRuk").setChecked(false) }
            2 -> { c("rbnPB").setChecked(false); c("rbnEom").setChecked(false); c("rbnRuk").setChecked(true) }
            else -> { c("rbnPB").setChecked(true); c("rbnEom").setChecked(false); c("rbnRuk").setChecked(false) }
        }
        val on = when (hm.ter) { 0 -> "rbnTbl1"; 1 -> "rbnTbl2"; 2 -> "rbnTbl3"; else -> "rbnTbl0" }
        for (n in listOf("rbnTbl1", "rbnTbl2", "rbnTbl3", "rbnTbl0")) c(n).setChecked(n == on)
        harmLocked = false
    }

    fun applyHarm() {
        val hm = m.harm
        hm.mode = if (c("rbnHAS").checked) 1 else if (c("rbnPOS").checked) 0 else 1
        hm.subMode = if (c("rbnPB").checked) 0 else if (c("rbnEom").checked) 1 else if (c("rbnRuk").checked) 2 else 0
        hm.ter = if (c("rbnTbl1").checked) 0 else if (c("rbnTbl2").checked) 1 else if (c("rbnTbl3").checked) 2 else -1
    }

    // ---------------------------------------------------------------- what the pilot does

    /** A click on [name] (button, check box, radio button, clickable panel, the tab strip). */
    fun click(name: String) { c(name).click() }

    /** Text typed into [name] (a text box or a masked box, which takes it through its mask), then focus leaves it. */
    fun type(name: String, text: String) { val k = c(name); k.text = text; k.onLeave?.invoke() }

    /** An item picked in the list [name], then focus leaves it. */
    fun choose(name: String, index: Int) { val k = c(name); k.select(index); k.onLeave?.invoke() }

    /** An up/down box set to [value], then focus leaves it. */
    fun number(name: String, value: String) { val k = c(name); k.setValue(DtcDec.parse(value)); k.onLeave?.invoke() }

    /** A preset button (btnUHF_n / btnVHF_n) and its frequency dialog accepted with [typed]. */
    fun comm(name: String, typed: String) {
        pendingFreq = typed
        c(name).click()
    }

    private var pendingFreq = ""

    /** The six include boxes set as WDP's reader and writer see them (`CheckState`), raising what that raises. */
    fun setIncl(k: Int, on: Boolean) = c(INCL[k]).setCheckState(if (on) 1 else 0)

    /** A radio button set as code sets it (`Checked = …`, clearing its neighbours when set). */
    fun setRadio(name: String, on: Boolean) = c(name).setChecked(on)

    /** What the last read of the cartridge returned (WDP's `LoadCallsign` result and error bits). */
    var lastLoad: DtcLoadResult? = null; private set
    /** Called straight after the cartridge is read, before the page fills itself from it (the test's look at the model). */
    var afterLoad: (() -> Unit)? = null

    // ---------------------------------------------------------------- the handlers, attached as a control is made

    private fun saved() { blnCallsignSaved = false; checkSavedStatus() }

    private fun wire(k: DtcCtl) {
        val n = k.name
        if (k.kind == 'R') k.radioSiblings = { DtcDesigner.all.values.filter { it.parent == k.parent && it.kind == 'R' }.map { c(it.name) } }
        val incl = INCL.indexOf(n)
        if (incl >= 0) {
            // the reader and the writer read the box; keep the model's copy with it
            val sync: () -> Unit = {
                when (incl) {
                    0 -> m.cmdsIncl = k.checkState == 1; 1 -> m.hudIncl = k.checkState == 1; 2 -> m.viewsIncl = k.checkState == 1
                    3 -> m.masterArmIncl = k.checkState == 1; 4 -> m.snsrPowerIncl = k.checkState == 1; else -> m.intLghtIncl = k.checkState == 1
                }
            }
            if (incl == 0) k.onCheckedChanged = { sync(); checkLabelColor(); saved() }
            else { k.onCheckedChanged = sync; k.onClick = { checkLabelColor(); saved() } }
            return
        }
        when {
            n in listOf("chbREQJAM", "chbREQCTR", "chbFeedback", "chbBingo") -> k.onClick = { ewsChange() }
            n.startsWith("txtComment") -> {
                val p = n.removePrefix("txtComment").toInt()
                k.onLeave = { (m.ews.program ?: throw NullPointerException("Program"))[p - 1].comment = k.text }
            }
            n == "mxtBingo_Flare" || n == "mxtBingo_Chaff" || Regex("mxt(BQ|BI|SQ|SI)_[CF][1-6]").matches(n) -> k.onLeave = { ewsChange() }
            n == "cboMode" || n == "cboProgram" -> k.onLeave = { ewsChange() }
            n == "btnClear_EWS" -> k.onClick = { clearEws() }
            n.startsWith("cbo") && DtcTables.MFD_SELECTORS.any { it.name == n } -> {
                val s = DtcTables.MFD_SELECTORS.first { it.name == n }
                k.onSelectedIndexChanged = {
                    val a = mfdArray(s.mode)[s.mfd]
                    val v = mfdToInteger(k.selectedItem)
                    when (s.pos) { 0 -> a.left = v; 1 -> a.center = v; else -> a.right = v }
                    // D14: a page appears once per master mode, so the other boxes of this mode showing it are blanked
                    if (v != 0) for (o in mfdBoxesOfMode(s.mode)) if (o != n && c(o).selectedItem == k.selectedItem) c(o).select(0)
                    saved()
                }
            }
            Regex("chb(AG|AA|NAV|MSL|DGFT|SJ)_MFD[1-4]_(Left|Center|Right)").matches(n) -> {
                val md = n.substring(3, n.indexOf("_MFD"))
                val i = n[n.indexOf("_MFD") + 4] - '0'
                val pos = n.substringAfterLast('_')
                val mode = MFD_PAGES.indexOf(md)
                k.onCheckedChanged = {
                    if (k.checkState == 1) {
                        for (o in POS) if (o != pos) c("chb${md}_MFD${i}_$o").setCheckState(0)
                        mfdArray(mode)[i - 1].csel = when (pos) { "Left" -> 2; "Center" -> 1; else -> 0 }
                        saved()
                    }
                }
            }
            n == "chbBullseye" -> k.onCheckedChanged = { m.bullseyeInfoOnMfd = k.checkState == 1; saved() }
            n == "btnClear" -> k.onClick = { clearAllSettings() }
            n in listOf("chbMode1", "chbMode2", "chbMode3A", "chbMode4", "chbModeC", "chbModeS") -> k.onClick = {
                val v = k.checkState
                when (n) {
                    "chbMode1" -> m.iff.mode1On = v; "chbMode2" -> m.iff.mode2On = v; "chbMode3A" -> m.iff.mode3aOn = v
                    "chbMode4" -> m.iff.mode4On = v; "chbModeC" -> m.iff.modeCOn = v; else -> m.iff.modeSOn = v
                }
                saved()
            }
            n.startsWith("numMode1_") -> k.onLeave = { m.iff.mode1Code = (c("numMode1_D1").value.times(10) + c("numMode1_D2").value).toInt16(); saved() }
            n.startsWith("numMode2_") -> k.onLeave = { m.iff.mode2Code = fourDigits("numMode2_").toInt16(); saved() }
            n.startsWith("numMode3A_") -> k.onLeave = { m.iff.mode3aCode = fourDigits("numMode3A_").toInt16(); saved() }
            Regex("btnUHF_\\d+").matches(n) -> k.onClick = {
                val i = n.removePrefix("btnUHF_").toInt()
                strUHF = c("lblUHF_Val_$i").text; intCommNr = i; changeComm(pendingFreq)
            }
            Regex("btnVHF_\\d+").matches(n) -> k.onClick = {
                val i = n.removePrefix("btnVHF_").toInt()
                strVHF = c("lblVHF_Val_$i").text; intCommNr = 30 + i; changeComm(pendingFreq)
            }
            n == "cboPreset_1" || n == "cboPreset_2" -> { k.onLeave = { arrayComms() }; k.onSelectedIndexChanged = { saved() } }
            n == "cboTACAN" -> k.onSelectedIndexChanged = { m.comm.tacanBand = k.selectedIndex; arrayComms(); saved() }
            n == "cboTACANFunc" -> k.onSelectedIndexChanged = { m.comm.tacanDomain = k.selectedIndex; arrayComms(); saved() }
            n == "mxtTACAN" || n == "mxtILS_FREQ" || n == "mxtILS_CRS" -> k.onLeave = { arrayComms() }
            n == "txtComm1_Comment" -> k.onLeave = { m.comm.comm1Comment = k.text }
            n == "txtComm2_Comment" -> k.onLeave = { m.comm.comm2Comment = k.text }
            Regex("txtUHF_\\d+").matches(n) -> k.onLeave = { (m.radio.uhfComment ?: throw NullPointerException("UHFcomment"))[n.removePrefix("txtUHF_").toInt()] = k.text }
            Regex("txtVHF_\\d+").matches(n) -> k.onLeave = { (m.radio.vhfComment ?: throw NullPointerException("VHFcomment"))[n.removePrefix("txtVHF_").toInt()] = k.text }
            n == "btnNone" -> k.onClick = { strProfile = "None"; m.nav.modesel = 0; profiles(); saved() }
            n == "btnPopUp" -> k.onClick = { strProfile = "PopUp"; profiles(); saved() }
            n == "btnHADB" -> k.onClick = { strProfile = "HADB"; profiles(); saved() }
            n == "btnTOSS" -> k.onClick = { strProfile = "TOSS"; profiles(); saved() }
            Regex("num(VIP|VIPPUP|VIPOA1|VIPOA2|VRP|VRPPUP|VRPOA1|VRPOA2)_(STPTnr|BRG_VAL|RNG_VAL|ELV_VAL)").matches(n) -> k.onLeave = { manualData() }
            n == "pnlRefLeft" -> k.onClick = { m.nav.modesel = 0; mode(); saved() }
            n == "pnlRefCenter" -> k.onClick = { m.nav.modesel = 2; mode(); saved() }
            n == "pnlRefRight" -> k.onClick = { m.nav.modesel = 1; mode(); saved() }
            n == "cboColor" -> k.onSelectedIndexChanged = {
                val colors = intArrayOf(-16711936, -16744704, -16711681, -16744577, -2, -8421505, -16776961, -16777089, -65536, -8454144,
                    -256, -8421632, -65281, -8454017, -14540254)
                val i = k.selectedIndex
                if (i in 0..14) m.hud.color = colors[i] else { k.select(0); m.hud.color = -16711936 }
                saved()
            }
            n == "cboSymWheelPos" -> k.onSelectedIndexChanged = {
                if (k.selectedIndex == 0) m.hud.symWheelPos = 1000 else if (k.selectedIndex == 1) m.hud.symWheelPos = 500
                saved()
            }
            n == "cboScales" -> k.onSelectedIndexChanged = { m.hud.scales = k.selectedIndex; saved() }
            n == "cboFPM" -> k.onSelectedIndexChanged = { m.hud.fpm = k.selectedIndex; saved() }
            n == "cboDED" -> k.onSelectedIndexChanged = {
                when (k.selectedIndex) { 0 -> m.hud.ded = 0; 1 -> m.hud.ded = 2; 2 -> m.hud.ded = 1 }
                saved()
            }
            n == "cboVelocity" -> k.onSelectedIndexChanged = { m.hud.velocity = k.selectedIndex; saved() }
            n == "cboAlt" -> k.onSelectedIndexChanged = { m.hud.alt = k.selectedIndex; saved() }
            n == "cboMasterMode" -> k.onSelectedIndexChanged = { m.icp.masterMode = k.selectedIndex; saved() }
            n == "cboMasterArm" -> k.onSelectedIndexChanged = {
                when (k.selectedIndex) { 0 -> m.masterArm = 2; 1 -> m.masterArm = 0; 2 -> m.masterArm = 1 }
                saved()
            }
            n == "cboWideView" -> k.onSelectedIndexChanged = { m.wideView = k.selectedIndex; saved() }
            n == "cboStartView" -> k.onSelectedIndexChanged = { m.otwMode = checkedPlus1(k.selectedIndex); saved() }
            n == "cboRalt" -> k.onSelectedIndexChanged = {
                m.ralt = when (k.text) { "ON" -> 2; "STBY" -> 1; else -> 0 }
                saved()
            }
            n == "cboDedBrt" -> k.onSelectedIndexChanged = {
                when (k.text) { "BRT" -> m.dedLight = 2; "DIM" -> m.dedLight = 1; "OFF" -> m.dedLight = 0 }
                saved()
            }
            n == "txtBrightness" -> k.onLeave = {
                if (!vb.isNumeric(k.text)) k.text = "0"
                m.hud.brightness = vb.toInteger(k.text); saved()
            }
            n == "mxtALOW_AGL" -> k.onLeave = { m.icp.alowAgl = floatBox(k, "####0.000000"); saved() }
            n == "mxtALOW_MSL" -> k.onLeave = { if (!vb.isNumeric(k.text)) k.text = "0"; m.icp.alowMsl = vb.toInteger(k.text); saved() }
            n == "mxtALOW_TF" -> k.onLeave = { if (!vb.isNumeric(k.text)) k.text = "0"; m.icp.alowTfAdv = vb.toInteger(k.text); saved() }
            n == "mxtWingSpan" -> k.onLeave = { m.icp.manualWingspan = floatBox(k, "#0.000000"); saved() }
            n == "mxtBingo" -> k.onLeave = { m.icp.bingoFuel = floatBox(k, "#0.000000"); saved() }
            n == "mxtLaser" -> k.onLeave = { if (!vb.isNumeric(k.text)) k.text = "0"; m.laser.laserSt = vb.toInteger(k.text); saved() }
            n == "mxtLaserCode" -> k.onLeave = { m.laser.laserCode = laserBox(k); saved() }
            n == "mxtLaserLST" -> k.onLeave = { m.laser.lstCode = laserBox(k); saved() }
            n == "cboAIM9_Spot_Scan" -> k.onSelectedIndexChanged = { m.aim.spotScan = k.selectedIndex; saved() }
            n == "cboAIM9_TD_BP" -> k.onSelectedIndexChanged = { m.aim.tdBp = k.selectedIndex; saved() }
            n == "cboAIM120" -> k.onSelectedIndexChanged = { m.aim.targetSize = k.selectedIndex; saved() }
            n == "cboPower" -> k.onSelectedIndexChanged = { m.agm.mavAutoPwr = k.selectedIndex; saved() }
            n == "cboPowerDir" -> k.onSelectedIndexChanged = { m.agm.mavAutoPwrDir = checkedPlus1(k.selectedIndex); saved() }
            n == "cboPowerWpt" -> k.onSelectedIndexChanged = { m.agm.mavAutoPwrWpt = k.selectedIndex; saved() }
            n == "cboP1_SubMode" || n == "cboP2_SubMode" -> k.onSelectedIndexChanged = {
                val p = if (n == "cboP1_SubMode") m.agb1 else m.agb2
                when (k.selectedIndex) { 0 -> p.submode = 8; 1 -> p.submode = 7; 2 -> p.submode = 9; 3 -> p.submode = 10 }
                saved()
            }
            n == "cboP1_Fuze" || n == "cboP2_Fuze" -> k.onSelectedIndexChanged = { (if (n == "cboP1_Fuze") m.agb1 else m.agb2).fuze = k.selectedIndex; saved() }
            n == "cboP1_SGL_PAIR" || n == "cboP2_SGL_PAIR" -> k.onSelectedIndexChanged = { (if (n == "cboP1_SGL_PAIR") m.agb1 else m.agb2).sglPair = k.selectedIndex; saved() }
            Regex("mxtP[12]_(Spacing|Pulse|Angle|C1_AD1|C1_AD2|C2_AD|C2_BA)").matches(n) -> {
                val one = n[4] == '1'
                val p = { if (one) m.agb1 else m.agb2 }
                k.onLeave = when (n.substring(6)) {
                    "Spacing" -> { { if (!vb.isNumeric(k.text)) k.text = "0"; p().releaseSpacing = vb.toInteger(k.text); saved() } }
                    "Pulse" -> { {
                        val v: Int
                        if (vb.isNumeric(k.text)) v = vb.toInteger(k.text) else { k.text = "1"; v = 1 }
                        p().releasePulse = v; saved()
                    } }
                    "Angle" -> { { if (!vb.isNumeric(k.text)) k.text = "0"; p().releaseAngle = vb.toInteger(k.text); saved() } }
                    "C1_AD1" -> { { p().c1Ad1 = adBox(k) { if (one) dblP1C1AD1 = it else dblP2C1AD1 = it }; saved() } }
                    "C1_AD2" -> { { p().c1Ad2 = adBox(k) { if (one) dblP1C1AD2 = it else dblP2C1AD2 = it }; saved() } }
                    "C2_AD" -> { { p().c2Ad = adBox(k) { if (one) dblP1C2AD1 = it else dblP2C2AD1 = it }; saved() } }
                    else -> { { if (!vb.isNumeric(k.text)) k.text = "0"; p().c2Ba = vb.toInteger(k.text); saved() } }
                }
            }
            Regex("cboTbl[1-3]_Thr[1-5]").matches(n) -> k.onSelectedIndexChanged = {
                if (!harmLocked) {
                    val i = getHarmSysByName(k.selectedItem)
                    val value: Int
                    val text: String
                    if (i != -1) { value = harmSystems[i].code; text = harmSystems[i].symbol }
                    else { value = codeOfUnknown(k.selectedItem) ?: 0; text = "" }
                    c("num" + n.removePrefix("cbo")).setValue(DtcDec.of(value))
                    c("lbl" + n.removePrefix("cbo")).text = text
                }
            }
            Regex("numTbl[1-3]_Thr[1-5]").matches(n) -> k.onValueChanged = {
                val t = n[6] - '0'; val h = n.last() - '0'
                val row = (m.harm.table ?: throw NullPointerException("CampHarm.Table"))[t - 1]
                val v = k.value.toInt32()
                when (h) { 1 -> row.threat0 = v; 2 -> row.threat1 = v; 3 -> row.threat2 = v; 4 -> row.threat3 = v; else -> row.threat4 = v }
            }
            // the six Clear All buttons ask first when the table holds a point with a position (askClear)
            n == "btnClearStpt" -> k.onClick = { askClear("steerpoint", "STPT", 1, points(m.stpt.take(24))) { clearSTPT(); clearedTable() } }
            n == "btnClearLines" -> k.onClick = { askClear("line point", "Line", 31, m.line.map { Triple(it.falconX, it.falconY, 0) }) { clearLines() } }
            n == "btnClearTgt" -> k.onClick = { askClear("weapon target", "Target", 1, m.tgt.take(100).map { Triple(it.falconX, it.falconY, 0) }) { clearTargets() } }
            n == "btnClearPPT" -> k.onClick = { askClear("PPT", "PPT", 56, m.ppt.map { Triple(it.falconX, it.falconY, 0) }) { clearPPT(); clearedTable() } }
            n == "btnClearOpen" -> k.onClick = { askClear("steerpoint", "STPT", 81, points(m.open.toList())) { clearOpen(); clearedTable() } }
            n == "btnClearHpn" -> k.onClick = { askClear("steerpoint", "STPT", 90, points(m.hpn.toList())) { clearHpn(); clearedTable() } }
            n == "tabDTC" -> k.onClick = { checkSavedStatus() }
            n == "tabNavOffsets" -> k.onClick = { mode(); profiles() }
        }
    }

    /** The twelve selector boxes of one master mode (MFD 1–4 × left, centre, right). */
    private fun mfdBoxesOfMode(mode: Int): List<String> = DtcTables.MFD_SELECTORS.filter { it.mode == mode }.map { it.name }

    /**
     * Asked before a Clear All wipes points that have a position (R3-PLAN A14): the title and the question for the
     * pilot, and what clearing does. The Planner's DTC page (`DtcWiring`) sets it and puts the question in WDP's
     * message box; left null, a Clear clears at once, as WDP's page does (the page checks compare this page with WDP's
     * own). A table with no point placed is cleared without a word: there is nothing to lose.
     *
     * Why it asks: a Clear followed by a save is how a pilot's cartridge lost the eight targets BMS's Recon had put in
     * STPT 15-22 — Clear All STPTs zeroes every position and keeps the names, and the next save writes that.
     */
    var confirmClear: ((title: String, question: String, go: () -> Unit) -> Unit)? = null

    /** A steerpoint table as [askClear] reads it: north, east and the action of each point. */
    private fun points(t: List<DtcStpt>): List<Triple<Float, Float, Int>> = t.map { Triple(it.falconX, it.falconY, it.action) }

    /**
     * A Clear All of a table whose first entry the page numbers [first]: [go] at once when no point in [table] (north,
     * east, action) has a position, or when no one asks; otherwise through [confirmClear]. A steerpoint's action -1 is
     * a target BMS marked (Recon, the ICP's MARK), which is worth saying: those come back only by marking them again.
     */
    private fun askClear(what: String, label: String, first: Int, table: List<Triple<Float, Float, Int>>, clear: () -> Unit) {
        val hook = confirmClear
        if (hook == null) { clear(); return }
        val go = { clear(); tidyCleared(label, first) }
        val placed = table.withIndex().filter { (_, p) -> p.first != 0f || p.second != 0f }
        if (placed.isEmpty()) { go(); return }
        val nums = placed.map { first + it.index }
        val marked = label == "STPT" && placed.all { it.value.third == -1 }
        val count = nums.size
        val plural = if (count == 1) what else what + "s"
        hook(
            "Clear All",
            "Clear $count $plural with a position ($label ${ranges(nums)})?" +
                (if (marked) "\n\n${if (count == 1) "It is a target" else "They are targets"} marked in Falcon BMS (Recon or the ICP's MARK): " +
                    "they come back only by marking them again." else "") +
                "\n\nThe page sets ${if (count == 1) "its position" else "their positions"} to 0. Nothing is written until you " +
                "press Save to DTC, which then writes that into the cartridge the jet loads.",
            go,
        )
    }

    private fun clearedTable() {
        if (!c("btnCampaign").enabled) blnCallsignSaved = false else blnMissionSaved = false
        checkSavedStatus()
    }

    /**
     * After a Clear All in the Planner ([confirmClear] set): the emptied slots as Falcon BMS itself writes an empty
     * one, so that a save writes only what the Clear really changed. WDP's Clear leaves every PPT with the code "0",
     * where BMS writes nothing (`ppt_n=0.000000, 0.000000, 0.000000, 0.000000,`), so clearing a PPT table that held
     * nothing gave fifteen edits; and it leaves a cleared steerpoint its old name, so a cleared Recon target was
     * saved as "EW Site …" at 0,0 where BMS's own empty slot says "Not set". The page tests (no hook) keep WDP's.
     */
    private fun tidyCleared(label: String, first: Int) {
        when (label) {
            "PPT" -> {
                for (p in m.ppt) if (p.falconX == 0f && p.falconY == 0f && (p.name ?: "") == "" && p.code == "0") p.code = ""
                fillLabelsPPT()
            }
            "STPT" -> {
                val table = when (first) { 1 -> m.stpt.take(24); 81 -> m.open.toList(); else -> m.hpn.toList() }
                for (s in table) if (s.falconX == 0f && s.falconY == 0f && (s.target ?: "").isNotBlank()) s.target = "Not set"
            }
        }
    }

    private fun fourDigits(p: String): DtcDec =
        c(p + "D1").value.times(1000) + c(p + "D2").value.times(100) + c(p + "D3").value.times(10) + c(p + "D4").value

    private fun floatBox(k: DtcCtl, pattern: String): Float {
        val value: String
        if (vb.isNumeric(k.text)) value = fmt(vb.toInteger(k.text), pattern) else { k.text = "0"; value = "0.000000" }
        return vb.toSingle(value)
    }

    private fun laserBox(k: DtcCtl): Short {
        if (!vb.isNumeric(k.text)) k.text = "1688"
        if (vb.toDouble(k.text) < 1111.0) k.text = "1111"
        if (vb.toDouble(k.text) > 2888.0) k.text = "2888"
        return vb.toShort(k.text)
    }

    private fun adBox(k: DtcCtl, keep: (Double) -> Unit): Float {
        val value: String
        if (vb.isNumeric(k.text)) { val d = vb.toDouble(k.text) * 100.0; keep(d); value = fmt(d, "###.000000") }
        else { k.text = "0"; value = "0.000000" }
        return vb.toSingle(value)
    }

    // ---------------------------------------------------------------- what the page shows

    /**
     * Every property the port holds, as `name.P` → value: T text, V shown, E enabled, F/B fore/back colour (ARGB,
     * only once the code set one), C check state, I selected index, L the list's items (joined by U+0002), N an
     * up/down box's value, G the target list (rows by U+0002, cells by U+0003, a missing cell U+0004).
     */
    fun dump(): Map<String, String> {
        val r = LinkedHashMap<String, String>()
        for (k in ctls.values) {
            val n = k.name
            when (k.kind) {
                'U' -> r["$n.N"] = k.value.toString()
                'D', 'X' -> {}
                else -> r["$n.T"] = k.text
            }
            r["$n.V"] = if (k.visible) "1" else "0"
            r["$n.E"] = if (k.enabled) "1" else "0"
            k.fore?.let { r["$n.F"] = argb(it) }
            k.back?.let { r["$n.B"] = argb(it) }
            if (k.kind == 'K' || k.kind == 'R') r["$n.C"] = k.checkState.toString()
            if (k.kind == 'C') { r["$n.I"] = k.selectedIndex.toString(); r["$n.L"] = k.items.joinToString("\u0002") }
        }
        r["dgvTargets.G"] = targetRows.joinToString("\u0002") { row -> row.joinToString("\u0003") { it ?: "\u0004" } }
        return r
    }

    companion object {
        /** The fourteen tabs' "saved" lamps (`<name>Red` / `<name>Green`), which WDP turns red on any edit. */
        val CALLSIGN_PANELS = listOf("pnlStptCallsign", "pnlTgtCallsign", "pnlLineCallsign", "pnlPptCallsign", "pnlIff", "pnlOpenCallsign",
            "pnlHpnCallsign", "pnlEws", "pnlMfd", "pnlRad", "pnlOffset", "pnlSystem", "pnlWeapon", "pnlHarm")

        /** The label on each tab that names the cartridge's file. */
        val FILE_LABELS = listOf("lblCallsignName", "lblTgtCallsignName", "lblLineCallsignName", "lblPptCallsignName", "lblOpenCallsignName",
            "lblHpnCallsignName", "lblCallsignEWS", "lblCallsignMFD", "lblCallsignRad", "lblCallsignNav", "lblCallsignSys",
            "lblCallsignWPN", "lblCallsignHARM")

        /** Numbers as runs, the way a pilot reads them: [3, 5, 15, 16, 17] → "3, 5, 15-17". */
        fun ranges(nums: List<Int>): String {
            val s = nums.distinct().sorted()
            if (s.isEmpty()) return ""
            val out = ArrayList<String>()
            var a = s[0]
            var b = s[0]
            for (n in s.drop(1)) {
                if (n == b + 1) { b = n; continue }
                out += if (a == b) "$a" else "$a-$b"
                a = n; b = n
            }
            out += if (a == b) "$a" else "$a-$b"
            return out.joinToString(", ")
        }

        /** A position in feet as the labels print it without a projection: `1234567'`, or the empty mark. */
        fun feetLabels(north: Float, east: Float): Pair<String, String> =
            if (north == 0f && east == 0f) "00,00.000" to "000,00.000"
            else "${kotlin.math.round(north).toLong()}'" to "${kotlin.math.round(east).toLong()}'"

        const val FT_TO_NM = 0.0001646f
        /** The HARM lists' first item: an empty table slot (code 0000). */
        const val NOT_IN_LIST = "Not in List"

        /** How a HARM list shows a code no system of the app's list has: "Code 0620". */
        fun unknownCode(code: Int): String = "Code " + code.toString().padStart(4, '0')

        /** The code an [unknownCode] item stands for, or null for any other item. */
        fun codeOfUnknown(item: String?): Int? = item?.takeIf { it.startsWith("Code ") }?.removePrefix("Code ")?.trim()?.toIntOrNull()

        val INCL = listOf("chbCmdsIncl", "chbHudIncl", "chbViewsIncl", "chbMasterArmIncl", "chbSnsrPowerIncl", "chbIntLghtIncl")
        val MFD_PAGES = listOf("AG", "AA", "NAV", "MSL", "DGFT", "SJ")
        val POS = listOf("Left", "Center", "Right")

        const val WHITE = 0xFFFFFFFF.toInt()
        const val GRAY = 0xFF808080.toInt()
        const val ORANGE = 0xFFFFA500.toInt()
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF008000.toInt()
        const val LIGHTBLUE = 0xFFADD8E6.toInt()
        const val TRANSPARENT = 0x00FFFFFF

        fun argb(v: Int): String = v.toUInt().toString(16).padStart(8, '0')

        /** The order a sorted list keeps its items in (en-US, as Windows Forms sorts them); ordinal for these lists. */
        internal fun cultureCompare(a: String, b: String): Int = a.compareTo(b)

        val BLUE_UHF = listOf("297.500", "381.300", "275.800", "294.700", "279.600", "349.000", "377.100", "292.200", "264.600", "286.400",
            "354.400", "269.100", "307.300", "377.200", "354.000", "318.100", "359.300", "324.500", "339.100", "280.500")
        val BLUE_VHF = listOf("138.050", "138.100", "138.200", "126.200", "134.250", "133.150", "132.350", "126.150", "132.875", "132.325",
            "132.575", "121.200", "119.500", "120.100", "134.100", "126.800", "120.000", "141.800", "123.700", "121.700")
        val RED_UHF = listOf("297.575", "381.325", "275.825", "294.775", "279.625", "349.025", "377.125", "292.225", "264.625", "286.425",
            "354.425", "269.125", "307.300", "377.225", "354.025", "318.125", "359.325", "324.525", "243.000", "280.525")
        val RED_VHF = listOf("138.075", "138.175", "138.275", "126.275", "134.275", "133.175", "132.375", "126.175", "132.825", "132.375",
            "132.525", "121.225", "119.525", "120.125", "134.125", "126.825", "120.025", "141.825", "123.725", "121.725")
    }
}

/**
 * One entry of the HARM tab's lists: what the list shows ("SA-2 Fan Song"), the ALIC code BMS keeps in the table
 * (`THREAT 0 0=0202` is 202) and the symbol the HAD page draws ("2T"). Built by the wiring from the app's
 * `curated/harm.json`.
 */
data class DtcHarmCode(val label: String, val code: Int, val symbol: String)
