package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.wdp.AttackGeometry
import com.bmscompanion.app.data.wdp.AttackMap
import com.bmscompanion.app.data.wdp.HadbPlan
import com.bmscompanion.app.data.wdp.PopupPlan
import com.bmscompanion.app.data.wdp.PopupCoords
import com.bmscompanion.app.data.wdp.WdpValues

/**
 * The High Altitude Dive Bomb page's behaviour, joined to its layout (`cntHADB.json`).
 *
 * [HadbPlan] is Falcas's `cntHADB` and knows nothing about controls; this turns a slider's position into the plan's
 * slider (WDP's own units: `trbReleaseSpd` and `trbIngrSpd` tens of knots, `trbReleaseHeight` hundreds of feet,
 * `trbVrpToPup` nautical miles, `trbTurn` 0 Left / 1 Right) and runs the handler its Scroll event runs, and hands the
 * labels, the slider ranges, the panels WDP flips, the buttons and the profile picture back to the renderer.
 *
 * Clicks do what their handlers do in WDP: the compass letters set the attack heading and 0/30/60/90 the angle off;
 * the bomb knob flips low/high drag; the Extra Info knob swaps the extra-info panel and the wheel; Show PPT Nr numbers
 * the threats on the map; Save to DTC hands the nav offsets to the cartridge ([WdpAttackMap.saveNavOffsets], or
 * [onSaveDtc] when one is given) and Save Map saves the map. WDP's Campaign and TE buttons are off the page; IP STPT
 * stands beside TGT STPT, and Save to DTC also fills the card's Delivery block from this page once it has saved ([AttackSelection], D87). The map is [HadbPlan.mapPicture] over the app's own theater map inside picSatView
 * ([controlContent]).
 *
 * The selections knob (`pnlSelections_Up/Middle/Down`) moves forward on WDP's left button and back on its right; a
 * renderer that can tell the buttons sends `"<name>:left"` / `"<name>:right"`. A finger has one button, so a plain
 * tap steps forward and wraps from DED Data round to Selections (WDP's left button does nothing there), and a tap on a
 * position's caption — Selections, Profile, DED Data — turns the knob straight to it, as on the Pop-up and TOSS pages.
 *
 * **The mission is WDP's campaign table**, as on the Pop-up page: the cartridge's steerpoints fill `tblCampSTPT` (sim
 * feet, x north and y east — WDP's FalconY and FalconX), the DataCard's Precision is set and the source is "Camp", and
 * `CampTE` runs as it does when WDP's DTC page loads a campaign; the target is steerpoint N and the IP N − 1. The table
 * is the Planner's, slot by slot ([WdpMission.slots]: the cartridge's slot, else BMS's route, else the save's cell),
 * and a new mission or flight sets the TGT STPT box once to [WdpMission.defaultStpt]; after that it is the pilot's
 * (A3). The IP is the one before it unless the pilot picks another in IP STPT ([HadbPlan.ipStpt], D87). The HADB figures do not depend on where the target is — only the coordinate labels do,
 * and those read `00,00.000` and stay hidden until [coordData] gives the theater's projection, as WDP's do before a
 * theater's coordinate data is set.
 *
 * **Setup.ini's `[HADB]`** is kept in the app's preferences: read by `Setup()` when the page's Load runs (the first
 * time the section is empty, so WDP's own fallbacks apply) and written back with what `fclsMain` writes on exit.
 *
 * **Every ELEV can be typed**, and the page says so: the DED's ELEV figures and the Coordinates box's target Elv carry
 * a pencil and a frame (solid cyan once typed), the mouse turns to a hand over them and the foot of the DED panel says
 * what a tap does ([ElevMark], [DedElevHint]); a tap opens a small entry window or a finger's editor ([ElevEntry]).
 * What is typed is shown and saved until the target changes (D7). HADB's ELEVs are WDP's heights above the target
 * plus the ground under it (D7): BMS's height map read on the PC ([WdpGround]), else the cartridge's own target
 * points ([groundAt]), or what the pilot typed for the target — which the target Elv shows (D65), and which moves
 * every ELEV but OA2's 0 and the Ingress Alt readout, nothing else (WDP works nothing out from an elevation here).
 * The legend's VRP and PUP are in the map's colours, blue and magenta
 * (WDP printed them green and orange beside a map drawing them blue and magenta: D5).
 */
class HadbWiring(
    /** Save to DTC in place of the cartridge's own ([WdpCartridge]), for a caller that writes the offsets itself. */
    var onSaveDtc: ((HadbPlan) -> Unit)? = null,
) : WdpWiring, WdpControlContent {
    /** The map in picSatView, and the page's Save Map and Save to DTC. */
    val map = WdpAttackMap("HADB")
    private var version by mutableIntStateOf(0)
    /** The mission ([WdpMission.key]) whose default TGT STPT the box was last set to: set once, then the pilot's. */
    private var defaultFor: String? = null
    private var tableKey: String? = null
    private var savedIni: String? = null
    /** The mission last handed in, which the plan asks for the ground under its target. */
    private var mission = WdpMission()
    var plan = newPlan(readIni())
        private set

    init {
        // a saved section WDP's Setup cannot take (only a hand-edited one) kills WDP's page until it is made again;
        // here the page is made again at once, from the empty section
        if (!plan.isLoaded) plan = newPlan(null)
        saveIni()
    }

    private fun newPlan(ini: List<Pair<String, String>>?): HadbPlan {
        val p = HadbPlan()
        // the ground under the target (D7): what the pilot typed for this target, else BMS's height map, else the DTC's
        p.main.groundElevation = { n, north, east ->
            p.elev.typedFor(AttackGeometry.TGT, north, east)?.toDouble() ?: mission.groundAt(n, north, east)
        }
        p.onSaveDtc = { x ->
            val own = onSaveDtc
            if (own != null) own(x)
            else map.saveDtc(x.navOffsets.mapValues { (_, o) -> PopupPlan.Offset(o.stpt, o.bearing, o.range, o.elv) }, x.navModesel)
        }
        p.load(ini ?: emptyList())
        return p
    }

    /** The theater's coordinate data (`fclsMain` → `SetCoordData`), for the lat/lon labels. */
    fun coordData(originLat: Double, originLong: Double, campW: Double, campH: Double, newTerrain: Boolean, tm: PopupCoords.TransverseMercatorMeta) {
        try {
            val m = plan.main
            m.originLat = originLat; m.originLong = originLong; m.campW = campW; m.campH = campH; m.enableNewTerrain = newTerrain; m.tm = tm
            plan.setCoordData()
            plan.guard { plan.getCoords() }
        } catch (e: Exception) {
        }
        changed()
    }

    override fun values(hidden: List<String>): WdpValues {
        @Suppress("UNUSED_VARIABLE") val v = version   // read so a change re-composes the page
        val p = plan
        val m = LinkedHashMap<String, String>()
        for (h in hidden) m[h] = "hidden"
        m.putAll(p.labels)
        m["lblTargetHUDval.back"] = p.hudBack
        fun slider(name: String, b: HadbPlan.Bar) { m[name] = b.value.toString(); m["$name.min"] = b.min.toString(); m["$name.max"] = b.max.toString() }
        slider("trbDiveAngle", p.trbDiveAngle)
        slider("trbReleaseSpd", p.trbReleaseSpd)
        slider("trbReleaseHeight", p.trbReleaseHeight)
        slider("trbTrackingTime", p.trbTrackingTime)
        slider("trbIngrSpd", p.trbIngrSpd)
        slider("trbG", p.trbG)
        slider("trbTurn", p.trbTurn)
        slider("trbVrpToPup", p.trbVrpToPup)
        slider("trbAngleOff", p.trbAngleOff)
        slider("trbHeading", p.trbHeading)
        slider("trbZoom", p.trbZoom)
        // every control WDP shows and hides: the stacked tabs, the knob pictures, the two info panels, the coordinates
        for ((name, on) in p.shown) {
            if (!on) m[name] = "hidden"
            else if (!name.startsWith("lbl")) m[name] = "shown"
        }
        m["numWaypoint"] = p.numWaypoint.toString()
        m["btnPPTnr.text"] = p.btnPPTnrText
        // WDP's Campaign and TE buttons are off the page (AttackSelection.REMOVED, D87); IP STPT stands beside TGT STPT,
        // each box's tip saying where its steerpoint's position came from; Save to DTC also fills the DataCard
        val ip = AttackSelection.ipOf(p.numWaypoint, p.ipStpt)
        m[AttackSelection.IP_BOX] = if (ip >= 1) ip.toString() else ""
        AttackSelection.ipBoxValues(m)
        AttackSelection.targetTip(mission, p.numWaypoint).let { m["numWaypoint.tip"] = it; m["lblWaypoint.tip"] = it }
        AttackSelection.ipTip(mission, p.numWaypoint, p.ipStpt, p.blnDoVIP).let { m["${AttackSelection.IP_BOX}.tip"] = it; m["${AttackSelection.IP_LABEL}.tip"] = it }
        AttackSelection.saveValues(m, "HADB", "HADB", vipLines = false)
        m["btnSaveDTC.text"] = p.btnSaveDTCText
        // the profile picture Profile() loads from WDP's Pictures folder
        if (p.profilePicture.isNotEmpty()) m["picProfile"] = p.profilePicture
        // the legend in the colours the map draws the two points in (WDP's designer: green and orange, D5)
        m["lblVIP.fore"] = "DodgerBlue"
        m["lblPUP.fore"] = "Magenta"
        // the DED's units after their figures, whatever the font's spaces (see dedUnits)
        m.dedUnits(DED_LINES_HADB)
        // the target's Elv is the ground the ELEVs stand on (D65), and a typed ELEV is drawn in its own ink
        if (p.blnGotTGT) m["lblTGT_elv"] = p.targetElevation.toString()
        if (p.elev.isTyped(AttackGeometry.TGT)) m["lblTGT_elv.fore"] = TYPED_INK_NAME
        for (l in HADB_ELEV_LABELS) elevKey(l, false)?.let { if (p.elev.isTyped(it)) m["$l.fore"] = TYPED_INK_NAME }
        // HADB plans from a VRP only (WDP's modesel is always 2): no IP STPT, no IP lines, no IP STPT at the VRP (D94)
        AttackSelection.selectors(m, vip = false, vipAvailable = true, gotTarget = p.blnGotTGT, hadb = true, ip = ip)
        // the headings flown on the HUD and HSD are magnetic: the tips give the magnetic figure at the target (D93)
        val at = p.targetNorthEast
        for (n in listOf("lblAttackHdgVal", "lblApproachHedVal")) {
            AttackSelection.magneticTip(WdpTips.text("cntHADB", n), m[n]?.trim()?.toDoubleOrNull(), mission, at)?.let { m["$n.tip"] = it }
        }
        return WdpValues(m)
    }

    override fun controlContent(): Map<String, @Composable () -> Unit> = map.content({ mission.attackCaption(plan.numWaypoint, plan.blnGotTGT) }, { plan.blnGotTGT }) {
        @Suppress("UNUSED_VARIABLE") val v = version   // the map is drawn again after every change
        // the page's own drawing of its route and threats, the attack drawn over it as every map draws it
        try { plan.mapPicture().withAttack(WdpAttackOverlay.ofPlan(plan)) } catch (e: Exception) { null }
    } + elevContent()

    /** The ELEVs' pencils and the DED panel's line about them, drawn again after every change. */
    private fun elevContent(): Map<String, @Composable () -> Unit> {
        val out = LinkedHashMap<String, @Composable () -> Unit>()
        for (l in HADB_ELEV_LABELS) out[l] = {
            @Suppress("UNUSED_VARIABLE") val v = version
            ElevMark(typed = elevKey(l, false)?.let { plan.elev.isTyped(it) } == true)
        }
        out["lblTGT_elv"] = {
            @Suppress("UNUSED_VARIABLE") val v = version
            ElevMark(typed = plan.elev.isTyped(AttackGeometry.TGT))
        }
        out["pnlDEDData"] = {
            @Suppress("UNUSED_VARIABLE") val v = version
            @Suppress("UNUSED_VARIABLE") val g = WdpGround.version
            DedElevHint(typedLines(), groundLine()) { editGround() }
        }
        return out
    }

    /** The lines the pilot has typed an ELEV for, by the DED's names. */
    private fun typedLines(): List<String> =
        listOf(AttackGeometry.TGT, "VRP", "VRPPUP", "OA1_2", "OA2_2").filter { plan.elev.isTyped(it) }.map { elevLineName(it) }

    /** The ground under the target and where it came from, as the page plans on it now. */
    private fun ground(): Pair<Int, GroundSource> {
        val p = plan
        val t = p.targetNorthEast ?: return 0 to GroundSource.NONE
        if (p.elev.isTyped(AttackGeometry.TGT)) return p.targetElevation to GroundSource.TYPED
        return mission.groundSourced(p.numWaypoint, t.first, t.second)?.let { p.targetElevation to it.second } ?: (0 to GroundSource.NONE)
    }

    /** A picked IP the target has moved onto is no IP: the IP follows the target again (D87). A picked IP otherwise stays. */
    private fun ipClash() {
        if (plan.ipStpt != null && plan.ipStpt == plan.numWaypoint) plan.setIpStpt(null)
    }

    private fun groundLine(): String = if (!plan.blnGotTGT) "Target ground: no target" else ground().let { (g, src) -> "Target ground $g ft, ${src.words} · tap to change" }

    private fun handleMission(mission: WdpMission) {
        this.mission = mission
        map.theater = mission.theater
        askGround()
        AttackSelection.askVariation(mission) { version++ }
        val p = plan
        val pts = mission.steerpoints
        val ppts = mission.dtc?.ppts.orEmpty()
        // the key of what the tables would hold: only a change is applied, so a refresh is cheap and idempotent
        val key = buildString {
            for (pt in pts) append(pt.n).append(',').append(pt.x).append(',').append(pt.y).append(',').append(pt.altFt).append(',').append(pt.action).append(';')
            append('|')
            for (t in ppts) append(t.x).append(',').append(t.y).append(',').append(t.rangeNm).append(';')
        }
        val tables = key != tableKey
        // CreateFlightplan's numWaypoint + STPTChange, once per mission or flight; after that the box is the pilot's
        val d = mission.defaultStpt
        val newDefault = d != null && mission.key != defaultFor
        if (!tables && !newDefault) return
        tableKey = key
        if (tables) fillTables(pts, ppts)
        // a new mission's IP follows its target again (D87)
        if (newDefault) { defaultFor = mission.key; if (p.ipStpt != null) p.setIpStpt(null); p.setWaypoint(d!!) }
        changed()
    }

    /** The Planner's steerpoint table and the cartridge's threats into WDP's campaign tables, then `CampTE`. */
    private fun fillTables(pts: List<com.bmscompanion.app.data.mission.DtcPoint>, ppts: List<com.bmscompanion.app.data.mission.DtcPpt>) {
        val p = plan
        val m = p.main
        for (t in m.tblCampSTPT) { t.falconX = 0f; t.falconY = 0f; t.falconZ = 0f; t.action = 0 }
        for (pt in pts) {
            val i = pt.n - 1
            if (i !in m.tblCampSTPT.indices) continue
            val t = m.tblCampSTPT[i]
            t.falconX = pt.y.toFloat(); t.falconY = pt.x.toFloat(); t.falconZ = pt.altFt.toFloat(); t.action = pt.action
        }
        // the cartridge's pre-planned threats, which the map rings (tblCampPPT)
        m.threats = ppts.take(15).map { AttackMap.Threat(it.y.toFloat(), it.x.toFloat(), (it.rangeNm * PopupPlan.NM_TO_FT.toDouble()).toFloat(), it.name ?: "") }
        // the DTC page loading a campaign cartridge: Precision (the table's own steerpoints), source "Camp", CampTE
        m.precision = pts.isNotEmpty()
        p.strDTC = if (m.precision) "Camp" else "None"
        p.guard { p.campTE() }
    }

    private fun handleValue(name: String, value: String) {
        val p = plan
        val v = value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { it.coerceIn(-1e6, 1e6).toInt() } ?: return
        when (name) {
            // the NumericUpDown keeps its value inside 1..25 (D8); its ValueChanged runs STPTChange
            "numWaypoint" -> { p.setWaypoint(v); ipClash() }
            // the IP STPT box (D87): a number typed, taken as it is; TGT STPT − 1 follows again (the arrows: handleClick)
            AttackSelection.IP_BOX -> {
                val pick = AttackSelection.typed(value, p.numWaypoint, p.ipStpt)
                if (pick != p.ipStpt) p.setIpStpt(pick)
            }
            "trbDiveAngle", "trbReleaseSpd", "trbReleaseHeight", "trbTrackingTime", "trbIngrSpd", "trbG", "trbTurn",
            "trbVrpToPup", "trbAngleOff", "trbHeading", "trbZoom" -> p.slide(name, v)
            else -> return
        }
        changed()
    }

    private fun handleClick(name: String) {
        val p = plan
        val button = name.substringAfter(':', "")
        when (name.substringBefore(':')) {
            "lblN" -> p.headingShortcut(0)
            "lblE" -> p.headingShortcut(90)
            "lblS" -> p.headingShortcut(180)
            "lblW" -> p.headingShortcut(270)
            "lblN360" -> p.headingShortcut(360)
            "lbl0" -> p.angleOffShortcut(0)
            "lbl30" -> p.angleOffShortcut(30)
            "lbl60" -> p.angleOffShortcut(60)
            "lbl90" -> p.angleOffShortcut(90)
            "pnlBomb_Up" -> p.chooseBomb(false)
            "pnlBomb_Down" -> p.chooseBomb(true)
            "pnlExtraInfo_Up" -> p.chooseExtraInfo(true)
            "pnlExtraInfo_Down" -> p.chooseExtraInfo(false)
            // WDP's knob steps forward on a left click and back on a right one. A finger has one button, so a plain
            // tap steps forward and wraps from DED Data round to Selections; ":left"/":right" are the mouse's own.
            "pnlSelections_Up" -> p.chooseSelection("Selections", button != "right")
            "pnlSelections_Middle" -> p.chooseSelection("Profile", button != "right")
            "pnlSelections_Down" -> if (button == "") selectPanel("Selections") else p.chooseSelection("DED Data", button == "left")
            // and a tap on a position's caption turns the knob straight to it
            "lblHideSelections" -> selectPanel("Selections")
            "lblShowProfile" -> selectPanel("Profile")
            "lblShowSelections" -> selectPanel("DED Data")
            "btnPPTnr" -> p.togglePPTnr()
            // WDP's Campaign and TE are off the page (D87): one steerpoint table, nothing to switch
            // the cartridge's nav offsets, then (once saved) the card's Delivery block from this page (D87)
            "btnSaveDTC" -> p.saveDtc()
            "btnSaveMap" -> { map.saveMap(); return }
            "numWaypoint" -> { when (button) { "up" -> p.setWaypoint(p.numWaypoint + 1); "down" -> p.setWaypoint(p.numWaypoint - 1); else -> return }; ipClash() }
            // the IP STPT box's arrows by name
            AttackSelection.IP_BOX -> {
                val now = AttackSelection.ipOf(p.numWaypoint, p.ipStpt)
                val by = when (button) { "up" -> 1; "down" -> -1; else -> return }
                val pick = AttackSelection.arrow(mission, by, now, p.numWaypoint, p.ipStpt)
                if (pick != p.ipStpt) p.setIpStpt(pick)
            }
            // an ELEV on the DED panel: typed by the pilot (D7); HADB plans from a VRP only
            "lblVRPelv" -> { editElev("TGT-TO-VRP", "VRP", "lblVRPelv"); return }
            "lblVRPPUPelv" -> { editElev("TGT-TO-PUP", "VRPPUP", "lblVRPPUPelv"); return }
            "lblOA1elv" -> { editElev("OA1", "OA1_2", "lblOA1elv"); return }
            "lblOA2elv" -> { editElev("OA2", "OA2_2", "lblOA2elv"); return }
            "lblTGT_elv" -> { editGround(); return }
            else -> return
        }
        changed()
    }

    /** The entry window for one DED line's ELEV; what is typed replaces the page's figure until the target changes. */
    private fun editElev(line: String, key: String, label: String) {
        val g = plan.targetElevation
        val how = if (g != 0) "the page's ${plan.labels[label]} ft stands on $g ft of ground" else null
        ElevEntry(line, plan.labels[label] ?: "", how) { feet ->
            val p = plan
            p.elev.set(key, feet)
            p.guard { p.programFlow(true) }
            AttackFocus.touch(WdpPage.HADB)
            changed()
        }.open()
    }

    /** The ground under the target, typed (D7): Get_Coords again, and every ELEV but OA2's 0 stands on it. */
    private fun editGround() {
        val p = plan
        if (!p.blnGotTGT) return
        val (g, src) = ground()
        ElevEntry("TGT", g.toString(), "now $g ft, ${src.words}", caption1 = "Ground under the target", caption2 = "Feet above sea level") { feet ->
            val q = plan
            q.elev.set(AttackGeometry.TGT, feet)
            q.guard { q.getCoords() }
            AttackFocus.touch(WdpPage.HADB)
            changed()
        }.open()
    }

    /** BMS's height map under every placed steerpoint, asked of the PC once ([WdpGround]); Get_Coords again on the answer. */
    private fun askGround() {
        WdpGround.fetch(mission.groundTheater(), mission.groundPoints()) {
            val p = plan
            if (p.blnGotTGT) { p.guard { p.getCoords() }; changed() }
        }
    }

    /** Turns the selections knob to [target] through WDP's own one-step handler, a step at a time. */
    private fun selectPanel(target: String) {
        val p = plan
        val order = listOf("Selections", "Profile", "DED Data")
        val want = order.indexOf(target)
        repeat(3) {
            val now = when { p.shown["pnlDEDData"] == true -> 2; p.shown["pnlProfile"] == true -> 1; else -> 0 }
            if (now == want) return
            p.chooseSelection(order[now], now < want)
        }
    }

    private fun changed() {
        saveIni()
        version++
    }

    // ---------------------------------------------------------------- Setup.ini's [HADB], in the app's preferences

    /** The saved section as Setup.ini's lines, or null before anything is saved (WDP's own fallbacks then apply). */
    private fun readIni(): List<Pair<String, String>>? = runCatching {
        val s = Repo.getString(INI_KEY) ?: return@runCatching null
        s.split('\n').filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
    }.getOrNull()

    private fun saveIni() {
        val s = plan.iniValues().entries.filter { it.value != null }.joinToString("\n") { "${it.key}=${it.value}" }
        if (s == savedIni) return
        savedIni = s
        runCatching { Repo.putString(INI_KEY, s) }
    }

    /**
     * A new mission or a switch of mode ([WdpSession.resetAttack]): the page made again on WDP's defaults (the saved
     * section is gone), so no typed ELEV or target ground either, and TGT STPT set again from the mission last handed
     * in — its first strike steerpoint, 1 when it has none.
     */
    fun resetForMission() {
        plan = newPlan(null)
        savedIni = null
        defaultFor = null
        tableKey = null
        handleMission(mission)
        if (mission.defaultStpt == null) { plan.setWaypoint(1); changed() }
    }

    internal companion object {
        const val INI_KEY = "wdp_hadb_ini"
    }

    // The plan throws wherever WDP throws (an overflow, a saved setting WDP cannot take), and WDP answers with an
    // error box and carries on with the page as far as the handler got. The plan's own handlers already do that
    // (`guard`); anything that still gets out is caught here and shown, which leaves the page where WDP leaves it.
    // A change of the attack's inputs, or its Save to DTC, makes this the Planner's current attack (AttackFocus).
    override fun onValue(name: String, value: String) {
        try { handleValue(name, value); if (AttackSelection.countsValue(name)) AttackFocus.touch(WdpPage.HADB) } catch (e: Exception) { wdpError("HADB", e) }
    }
    override fun onClick(name: String) {
        try { handleClick(name); AttackSelection.clicked(WdpPage.HADB, name) } catch (e: Exception) { wdpError("HADB", e) }
    }
    override fun onMission(mission: WdpMission) { try { handleMission(mission) } catch (e: Exception) { wdpError("HADB", e) } }
}

/** The ELEV figures of the HADB DED panel. */
internal val HADB_ELEV_LABELS = listOf("lblVRPelv", "lblVRPPUPelv", "lblOA1elv", "lblOA2elv")

@Composable
fun rememberHadbWiring(): HadbWiring = remember { HadbWiring() }
