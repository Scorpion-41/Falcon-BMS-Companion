package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.wdp.AttackGeometry
import com.bmscompanion.app.data.wdp.PopupPlan
import com.bmscompanion.app.data.wdp.WdpValues

/**
 * The Pop-up page's behaviour, joined to its layout (`cntPopUp.json`).
 *
 * [PopupPlan] is Falcas's `cntPopUp` and knows nothing about controls; this turns a slider's position into the
 * plan's slider (the positions are WDP's own units: `trbIngressHeight` and `trbReleaseHeight` hundreds of feet,
 * `trbG` tenths of a G, `trbSpeed` knots) and runs the handler its Scroll event runs, and hands the labels, the
 * slider ranges, the panels WDP flips, the button states and the profile picture back to the renderer.
 *
 * Clicks do what their handlers do in WDP: the compass letters set the attack heading; the rotary pictures flip the
 * reference (VIP/VRP), the profile (Type 1/2) and the bomb (low/high drag); the OA2 and map boxes toggle; Save to DTC
 * hands the nav offsets to the cartridge ([WdpAttackMap.saveNavOffsets], or [onSaveDtc] when one is given) and Save
 * Map saves the map ([WdpAttackMap.saveMap]). WDP's Campaign and TE buttons are off the page; IP STPT stands beside TGT
 * STPT, and Save to DTC also fills the card's Delivery block from this page once it has saved ([AttackSelection], D87). The map itself is [PopupPlan.mapPicture] drawn over the app's own
 * theater map inside picSatView ([controlContent]). The three tabs at the top
 * (`pnlSelections_Up/Middle/Down`) move forward on the left button and back on the right, as in WDP; a renderer
 * that can tell the buttons sends `"<name>:left"` / `"<name>:right"`. A plain tap is the left button — except on
 * the DED Data tab, where WDP's left button does nothing and the only way off is the right one, which a touch
 * screen does not have: there a plain tap is taken as the right button. That is the one place the input is mapped
 * rather than passed through, and `":left"` gets WDP's own (inert) behaviour.
 *
 * **The mission is WDP's campaign table.** WDP reads the target as steerpoint N and the IP as N − 1 of the DTC's
 * precision steerpoints (`tblCampSTPT`, with the DataCard's Precision set and the DTC page's source "Camp"). The
 * Planner's steerpoint table fills it slot by slot ([WdpMission.slots], by the pilot's answer to WDP's Precision STPT
 * question, which the flight picker asks (D45): after a Yes the cartridge's slot, else BMS's mission file route, else
 * the save's cell; after a No the save's cell — sim feet, x north and y east, WDP's FalconY and FalconX), and `CampTE`
 * then runs as it does when WDP's DTC page loads a campaign.
 * The target is the TGT STPT box's steerpoint: a new mission or flight sets the box once to
 * [WdpMission.defaultStpt], and after that it is the pilot's (A3). The IP is the one before it unless the pilot picks
 * another in the IP STPT box ([PopupPlan.ipStpt], D87); a new mission follows the target again. The cartridge's
 * pre-planned threats fill the PPT table the map draws.
 *
 * **The ground under the target** (D7): WDP adds the ground's height under the target, from the theater's height map,
 * to the ingress height and to the pull-down, VIP, PUP and OA1 elevations. The app has no heightmap of its own: the
 * PC reads the same cell of BMS's ([WdpGround], asked for every steerpoint of the mission when it arrives, the page
 * planned again when it answers), else the cartridge's target points stand in ([groundAt]). The Coordinates box's
 * target Elv shows that ground (D65; WDP printed the steerpoint's altitude there) and takes a typed one.
 *
 * **Every ELEV can be typed**, and the page says so: each ELEV figure on the DED panel and the target Elv carry a
 * pencil and a frame (dotted for the page's own figure, solid in cyan for a typed one), the mouse turns to a hand over
 * them, and the foot of the DED panel says what a tap does ([ElevMark], [DedElevHint]). A tap opens a small entry
 * window, or a finger's editor ([ElevEntry]). A typed DED ELEV is that line's figure — the DED, the nav offsets Save to
 * DTC writes, the card's delivery block — and nothing else, as in WDP, where nothing is worked out from them; a typed
 * target ground is WDP's `TargetElv` and moves what that moves (every ELEV but OA2's 0, the pull-down altitude).
 * The coordinate labels read `00,00.000` and are hidden, as WDP's are before a theater's coordinate data is set — the
 * port has the conversion ([com.bmscompanion.app.data.wdp.PopupCoords]) and needs only the theater's projection
 * figures ([coordData]) to show them.
 *
 * **Setup.ini's `[PopUp]`** is kept in the app's preferences: read by `Setup()` when the page's Load runs (absent
 * the first time, so WDP's own fallbacks apply — without WDP's message box, which says the file is missing) and
 * written back with what `fclsMain` writes on exit, whenever it changes.
 *
 * **The page is on screen from the start**: the plan is made, given its Setup.ini and shown ([PopupPlan.show]),
 * which is when WDP runs the page's Load. WDP also runs `Draw` whenever Windows repaints the page; the app
 * repaints after every change, so every change ends with the plan's [PopupPlan.paint].
 *
 * **The waypoint box** holds a Decimal, as WDP's NumericUpDown does: a Setup.ini can leave it at 4.5 (the box shows
 * "5", the DED says 4.5), and the arrows then step by one from there (5.5, 6.5 …). The renderer's arrows send the
 * shown number plus or minus one; a value exactly one above or below what the box shows is taken as that arrow
 * ([PopupPlan.waypointUp]/[PopupPlan.waypointDown]), anything else as typed text ([PopupPlan.typeWaypoint]), and
 * `onClick("numWaypoint:up")`/`":down"` are the arrows by name.
 */
class PopupWiring(
    /** Save to DTC in place of the cartridge's own ([WdpCartridge]), for a caller that writes the offsets itself. */
    var onSaveDtc: ((PopupPlan) -> Unit)? = null,
) : WdpWiring, WdpControlContent {
    val plan = PopupPlan()
    /** The map in picSatView, and the page's Save Map and Save to DTC. */
    val map = WdpAttackMap("PopUp")
    private var version by mutableIntStateOf(0)
    /** The mission ([WdpMission.key]) whose default TGT STPT the box was last set to: set once, then the pilot's. */
    private var defaultFor: String? = null
    private var tableKey: String? = null
    private var savedIni: String? = null
    /** The mission last handed in, which the plan asks for the ground under its target. */
    private var mission = WdpMission()
    /** The IP STPT that IP STPT at the VRP created on this page this session, for the page's line about it. */
    private var createdIp: AttackSelection.CreatedIp? = null

    init {
        // the ground under the target (D7): what the pilot typed for this target, else BMS's height map, else the DTC's
        plan.main.groundElevation = { n, north, east ->
            plan.elev.typedFor(AttackGeometry.TGT, north, east)?.toDouble() ?: mission.groundAt(n, north, east)
        }
        plan.onSaveDtc = { p ->
            val own = onSaveDtc
            if (own != null) own(p) else map.saveDtc(p.navOffsets, p.navModesel)
        }
        plan.load(readIni())
        plan.paint()
        saveIni()
    }

    /** The theater's coordinate data (`fclsMain` → `SetCoordData`), for the lat/lon labels; null leaves them hidden. */
    fun coordData(originLat: Double, originLong: Double, campW: Double, campH: Double, newTerrain: Boolean, tm: com.bmscompanion.app.data.wdp.PopupCoords.TransverseMercatorMeta) {
        val m = plan.main
        m.originLat = originLat; m.originLong = originLong; m.campW = campW; m.campH = campH; m.enableNewTerrain = newTerrain; m.tm = tm
        plan.setCoordData()
        plan.guard { plan.getCoords() }
        changed()
    }

    override fun values(hidden: List<String>): WdpValues {
        @Suppress("UNUSED_VARIABLE") val v = version   // read so a change re-composes the page
        val p = plan
        val m = LinkedHashMap<String, String>()
        for (h in hidden) m[h] = "hidden"
        m.putAll(p.labels)
        m["lblTargetHUDval.back"] = p.hudBack
        m["lblVIP.fore"] = p.vipLegendFore
        fun slider(name: String, b: PopupPlan.Bar) { m[name] = b.value.toString(); m["$name.min"] = b.min.toString(); m["$name.max"] = b.max.toString() }
        slider("trbIngressHeight", p.trbIngressHeight)
        slider("trbDiveAngle", p.trbDiveAngle)
        slider("trbSpeed", p.trbSpeed)
        slider("trbReleaseHeight", p.trbReleaseHeight)
        slider("trbTrackingTime", p.trbTrackingTime)
        slider("trbG", p.trbG)
        slider("trbTurn", p.trbTurn)
        slider("trbHeading", p.trbHeading)
        slider("trbVrpToPup", p.trbVrpToPup)
        slider("trbZoom", p.trbZoom)
        // every control WDP shows and hides: stacked pictures and panels, and the few labels and sliders it flips
        for ((name, on) in p.shown) {
            if (!on) m[name] = "hidden"
            else if (!name.startsWith("lbl") && !name.startsWith("trb")) m[name] = "shown"
        }
        // the NumericUpDown shows its Decimal to no places, away from zero ("F0"); the value itself may be 4.5
        m["numWaypoint"] = shownWaypoint()
        m["chbOA2"] = if (p.chbOA2) "checked" else "unchecked"
        m["chbShowPPT"] = if (p.chbShowPPT) "checked" else "unchecked"
        m["chbShowPPTNr"] = if (p.chbShowPPTNr) "checked" else "unchecked"
        // WDP's Campaign and TE buttons are off the page (AttackSelection.REMOVED, D87); IP STPT stands beside TGT STPT,
        // each box's tip saying where its steerpoint's position came from; Save to DTC also fills the DataCard
        val tgt = shownWaypoint().trim().toIntOrNull() ?: 0
        val ip = AttackSelection.ipOf(tgt, p.ipStpt)
        m[AttackSelection.IP_BOX] = if (ip >= 1) ip.toString() else ""
        AttackSelection.ipBoxValues(m)
        // TGT STPT's arrows come back by name: WDP's UpButton and a number typed (ValidateEditText) are not the same call
        m["numWaypoint.arrows"] = "click"
        AttackSelection.targetTip(mission, tgt).let { m["numWaypoint.tip"] = it; m["lblWaypoint.tip"] = it }
        AttackSelection.ipTip(mission, tgt, p.ipStpt, p.blnDoVIP).let { m["${AttackSelection.IP_BOX}.tip"] = it; m["${AttackSelection.IP_LABEL}.tip"] = it }
        AttackSelection.saveValues(m, "PopUp", "Pop-up", p.blnRef)
        m["btnSaveDTC.text"] = p.btnSaveDTCText
        // the profile picture ProfileSwitch loads from WDP's Pictures folder (see the integration notes)
        m["picProfile"] = p.profilePicture
        // the DED's units after their figures, whatever the font's spaces (see dedUnits)
        m.dedUnits(DED_LINES_VIP)
        // the target's Elv is the ground the page plans on (D65), and a typed ELEV is drawn in its own ink
        if (p.blnGotTGT) m["lblTGT_elv"] = p.targetElevation.toString()
        if (p.elev.isTyped(AttackGeometry.TGT)) m["lblTGT_elv.fore"] = TYPED_INK_NAME
        for (l in ELEV_LABELS) elevKey(l, p.navModesel == 1)?.let { if (p.elev.isTyped(it)) m["$l.fore"] = TYPED_INK_NAME }
        // which selectors show (D94): IP STPT only in VIP mode or while VIP is blocked, its lines in VIP mode only, and
        // IP STPT at the VRP in VRP mode, and in VIP mode while the IP STPT is the one it created
        AttackSelection.selectors(m, p.blnRef, p.blnDoVIP, p.blnGotTGT, hadb = false, ip = ip, again = createdIp?.n == ip)
        // the headings flown on the HUD and HSD are magnetic: the tips give the magnetic figure at the target (D93)
        val at = p.targetNorthEast?.let { it.first.toDouble() to it.second.toDouble() }
        for (n in listOf("lblAttackHdgVal", "lblPullHeadingVal")) {
            AttackSelection.magneticTip(WdpTips.text("cntPopUp", n), m[n]?.trim()?.toDoubleOrNull(), mission, at)?.let { m["$n.tip"] = it }
        }
        return WdpValues(m)
    }

    override fun controlContent(): Map<String, @Composable () -> Unit> = map.content(
        { mission.attackCaption(shownWaypoint().trim().toIntOrNull(), plan.blnGotTGT) }, { plan.blnGotTGT }, { ipNotice() },
    ) {
        @Suppress("UNUSED_VARIABLE") val v = version   // the map is drawn again after every change
        // the page's own drawing of its route and threats, the attack drawn over it as every map draws it
        plan.mapPicture?.withAttack(WdpAttackOverlay.ofPlan(plan))
    } + elevContent() + mapOf("pnlSelections" to {
        // the IP STPT at the VRP line under the button, too (AttackNotice.kt)
        ipNotice()?.let { AttackNoticeBand(it, SELECTIONS_NOTICE_TOP, SELECTIONS_NOTICE_H) }
    })

    /**
     * The page's line about the IP STPT that IP STPT at the VRP created ([AttackSelection.ipNotice]): created, or no
     * longer at the VRP these inputs lay out. Read in composition (the page's and the DTC page's versions).
     */
    internal fun ipNotice(): AttackSelection.IpNotice? {
        @Suppress("UNUSED_VARIABLE") val v = version
        val c = createdIp ?: return null
        val tgt = shownWaypoint().trim().toIntOrNull() ?: 0
        val slot = runCatching { WdpSession.dtc.stptAt(c.n) }.getOrNull()
        return AttackSelection.ipNotice(c, AttackSelection.ipOf(tgt, plan.ipStpt), plan.vrpPoint(), slot)
    }

    /** The ELEVs' pencils and the DED panel's line about them, drawn again after every change. */
    private fun elevContent(): Map<String, @Composable () -> Unit> {
        val out = LinkedHashMap<String, @Composable () -> Unit>()
        for (l in ELEV_LABELS) out[l] = {
            @Suppress("UNUSED_VARIABLE") val v = version
            ElevMark(typed = elevKey(l, plan.navModesel == 1)?.let { plan.elev.isTyped(it) } == true)
        }
        out["lblTGT_elv"] = {
            @Suppress("UNUSED_VARIABLE") val v = version
            ElevMark(typed = plan.elev.isTyped(AttackGeometry.TGT))
        }
        out["pnlDEDData"] = {
            @Suppress("UNUSED_VARIABLE") val v = version
            @Suppress("UNUSED_VARIABLE") val g = WdpGround.version
            // under it, the IP STPT at the VRP line (AttackNotice.kt)
            WithDedNotice(ipNotice()) { DedElevHint(typedLines(), groundLine()) { editGround() } }
        }
        return out
    }

    /** The lines the pilot has typed an ELEV for, by the DED's names. */
    private fun typedLines(): List<String> =
        (listOf(AttackGeometry.TGT) + listOf("VRP", "VRPPUP", "OA1_2", "OA2_2", "VIP", "VIPPUP", "OA1_1", "OA2_1"))
            .filter { plan.elev.isTyped(it) }.map { elevLineName(it) }.distinct()

    /** The ground under the target and where it came from, as the page plans on it now. */
    private fun ground(): Pair<Int, GroundSource> {
        val p = plan
        val t = p.targetNorthEast ?: return 0 to GroundSource.NONE
        if (p.elev.isTyped(AttackGeometry.TGT)) return p.targetElevation to GroundSource.TYPED
        val n = shownWaypoint().trim().toIntOrNull() ?: 0
        return mission.groundSourced(n, t.first.toDouble(), t.second.toDouble())?.let { p.targetElevation to it.second } ?: (0 to GroundSource.NONE)
    }

    private fun groundLine(): String = if (!plan.blnGotTGT) "Target ground: no target" else ground().let { (g, src) -> "Target ground $g ft, ${src.words} · tap to change" }

    private fun handleMission(mission: WdpMission) {
        this.mission = mission
        map.theater = mission.theater
        askGround()
        AttackSelection.askVariation(mission) { version++ }
        val p = plan
        val pts = mission.steerpoints
        // the key of what the tables would hold: only a change is applied, so a refresh is cheap and idempotent
        val key = buildString {
            for (pt in pts) append(pt.n).append(',').append(pt.x).append(',').append(pt.y).append(',').append(pt.altFt).append(';')
            append('|')
            for (t in mission.dtc?.ppts.orEmpty()) append(t.x).append(',').append(t.y).append(',').append(t.rangeNm).append(';')
        }
        val tables = key != tableKey
        // CreateFlightplan's numWaypoint + STPTChange, once per mission or flight; after that the box is the pilot's
        val d = mission.defaultStpt
        val newDefault = d != null && mission.key != defaultFor
        if (!tables && !newDefault) return
        tableKey = key
        if (tables) fillTables(mission, pts)
        // a new mission's IP follows its target again (D87)
        if (newDefault) { defaultFor = mission.key; if (p.ipStpt != null) p.setIpStpt(null); p.setWaypoint(d!!) }
        changed()
    }

    /** The Planner's steerpoint table and the cartridge's threats into WDP's campaign tables, then `CampTE`. */
    private fun fillTables(mission: WdpMission, pts: List<com.bmscompanion.app.data.mission.DtcPoint>) {
        val p = plan
        val m = p.main
        for (t in m.tblCampSTPT) { t.falconX = 0f; t.falconY = 0f; t.falconZ = 0f; t.action = null }
        for (pt in pts) {
            val i = pt.n - 1
            if (i !in m.tblCampSTPT.indices) continue
            val t = m.tblCampSTPT[i]
            t.falconX = pt.y.toFloat(); t.falconY = pt.x.toFloat(); t.falconZ = pt.altFt.toFloat(); t.action = pt.action.toString()
        }
        for (t in m.tblCampPPT) { t.falconX = 0f; t.falconY = 0f; t.falconRNG = 0f; t.code = null }
        mission.dtc?.ppts.orEmpty().take(m.tblCampPPT.size).forEachIndexed { i, t ->
            val pp = m.tblCampPPT[i]
            pp.falconX = t.y.toFloat(); pp.falconY = t.x.toFloat(); pp.falconRNG = (t.rangeNm * PopupPlan.NM_TO_FT.toDouble()).toFloat(); pp.code = t.name
        }
        // the DTC page loading a campaign cartridge: Precision (the table's own steerpoints), source "Camp", CampTE
        m.precision = pts.isNotEmpty()
        p.strDTC = if (pts.isNotEmpty()) "Camp" else "None"
        p.guard { p.campTE() }
    }

    /** What the waypoint box shows (the plan keeps it as WDP's NumericUpDown does). */
    private fun shownWaypoint(): String = plan.numWaypointText

    /** A picked IP the target has moved onto is no IP: the IP follows the target again (D87). A picked IP otherwise stays. */
    private fun ipClash() {
        val ip = plan.ipStpt ?: return
        if (ip == shownWaypoint().trim().toIntOrNull()) plan.setIpStpt(null)
    }

    private fun handleValue(name: String, value: String) {
        val p = plan
        if (name == "numWaypoint") {
            // text typed into the box ("numWaypoint.arrows": the arrows come by name, to handleClick)
            p.typeWaypoint(value)
            ipClash()
            changed()
            return
        }
        if (name == AttackSelection.IP_BOX) {
            // the IP STPT box (D87): a number typed, taken as it is; TGT STPT − 1 follows again (the arrows: handleClick)
            val tgt = shownWaypoint().trim().toIntOrNull() ?: 0
            val pick = AttackSelection.typed(value, tgt, p.ipStpt)
            if (pick != p.ipStpt) p.setIpStpt(pick)
            changed()
            return
        }
        val v = value.trim().toDoubleOrNull()?.toInt() ?: return
        fun slide(b: PopupPlan.Bar, change: () -> Unit) { b.slide(v); p.guard(change) }
        when (name) {
            "trbIngressHeight" -> slide(p.trbIngressHeight, p::changeIngressHeight)
            "trbDiveAngle" -> slide(p.trbDiveAngle, p::changeDiveAngle)
            "trbSpeed" -> slide(p.trbSpeed, p::changeSpeed)
            "trbReleaseHeight" -> slide(p.trbReleaseHeight, p::changeReleaseHeight)
            "trbTrackingTime" -> slide(p.trbTrackingTime, p::changeTrackingTime)
            "trbG" -> slide(p.trbG, p::changeG)
            "trbTurn" -> slide(p.trbTurn, p::changeTurn)
            "trbHeading" -> slide(p.trbHeading, p::changeHeading)
            "trbVrpToPup" -> slide(p.trbVrpToPup, p::changeVrpToPup)
            "trbZoom" -> slide(p.trbZoom, p::zoomLevel)
            else -> return
        }
        changed()
    }

    private fun handleClick(name: String) {
        val p = plan
        val button = name.substringAfter(':', "")
        when (name.substringBefore(':')) {
            "lblN" -> heading(0)
            "lblE" -> heading(90)
            "lblS" -> heading(180)
            "lblW" -> heading(270)
            "lblN360" -> heading(360)
            "pnlRefUp", "pnlDED_Ref_Up" -> p.chooseRef(false)
            "pnlRefDown", "pnlDED_Ref_Down" -> p.chooseRef(true)
            "pnlProfile_Up", "pnlProfile2_Up" -> p.chooseProfile(false)
            "pnlProfile_Down", "pnlProfile2_Down" -> p.chooseProfile(true)
            "pnlBomb_Up" -> p.chooseBomb(false)
            "pnlBomb_Down" -> p.chooseBomb(true)
            // WDP's knob steps forward on a left click and back on a right one. A finger has one button, so a plain
            // tap steps forward and wraps from DED Data round to Selections; ":left"/":right" are the mouse's own.
            "pnlSelections_Up" -> p.chooseSelection("Selections", button != "right")
            "pnlSelections_Middle" -> p.chooseSelection("Profile", button != "right")
            "pnlSelections_Down" -> if (button == "") selectPanel("Selections") else p.chooseSelection("DED Data", button == "left")
            // and a tap on a position's caption turns the knob straight to it
            "lblHideSelections" -> selectPanel("Selections")
            "lblShowProfile" -> selectPanel("Profile")
            "lblShowSelections" -> selectPanel("DED Data")
            "chbOA2" -> p.setOA2(!p.chbOA2)
            "chbShowPPT" -> p.toggleShowPPT()
            "chbShowPPTNr" -> p.toggleShowPPTNr()
            // WDP's Campaign and TE are off the page (D87): one steerpoint table, nothing to switch
            // the cartridge's nav offsets, then (once saved) the card's Delivery block from this page (D87)
            "btnSaveDTC" -> p.saveDtc()
            "btnSaveMap" -> { map.saveMap(); return }
            // a steerpoint on the VRP becomes the IP, and the attack is planned as VIP from it (D94)
            AttackSelection.IP_AT_VRP -> { ipAtVrp(); return }
            // numWaypoint's arrows by name; its ValueChanged runs STPTChange, which fetches the target and IP again
            "numWaypoint" -> { when (button) { "up" -> p.waypointUp(); "down" -> p.waypointDown(); else -> return }; ipClash() }
            // the IP STPT box's arrows by name
            AttackSelection.IP_BOX -> {
                val tgt = shownWaypoint().trim().toIntOrNull() ?: 0
                val now = AttackSelection.ipOf(tgt, p.ipStpt)
                val by = when (button) { "up" -> 1; "down" -> -1; else -> return }
                val pick = AttackSelection.arrow(mission, by, now, tgt, p.ipStpt)
                if (pick != p.ipStpt) p.setIpStpt(pick)
            }
            // an ELEV on the DED panel, or the ground under the target: typed by the pilot (D7)
            "lblVIPelv", "lblPUPelv", "lblOA1elv", "lblOA2elv" -> { editElev(name.substringBefore(':')); return }
            "lblTGT_elv" -> { editGround(); return }
            else -> return
        }
        changed()
    }

    /**
     * **IP STPT at the VRP** (D94): with the page in VRP mode — or in VIP mode from the IP STPT this button created,
     * which then moves — a steerpoint is placed on the VRP the inputs lay out ([PopupPlan.vrpPoint]; the DTC page's own
     * edit, after the one question) and the page plans VIP from it — the mission given again with the steerpoints
     * placed this session, the IP STPT set to it, the reference turned to VIP — and keeps it for [ipNotice].
     */
    private fun ipAtVrp() {
        val p = plan
        if (!p.blnGotTGT) return
        val tgt = shownWaypoint().trim().toIntOrNull() ?: return
        val ip = AttackSelection.ipOf(tgt, p.ipStpt)
        if (p.blnRef && createdIp?.n != ip) return
        AttackSelection.ipAtVrp(
            WdpPage.POPUP, mission, tgt, ip, p.attackPlot()?.target, p.vrpPoint(), p.navOffsets["VRP"]?.elv ?: 0, createdIp,
        ) { m, n, at ->
            createdIp = at
            handleMission(m)
            p.setIpStpt(n)
            p.chooseRef(true)
            changed()
        }
    }

    /** The entry window for one DED line's ELEV, keyed to the nav-offset line it belongs to in the reference in use. */
    private fun editElev(label: String) {
        val p = plan
        val vip = p.navModesel == 1
        val (line, key) = when (label) {
            "lblVIPelv" -> if (vip) "VIP-TO-TGT" to "VIP" else "TGT-TO-VRP" to "VRP"
            "lblPUPelv" -> (if (vip) "VIP-TO-PUP" else "TGT-TO-PUP") to (if (vip) "VIPPUP" else "VRPPUP")
            "lblOA1elv" -> "OA1" to (if (vip) "OA1_1" else "OA1_2")
            else -> "OA2" to (if (vip) "OA2_1" else "OA2_2")
        }
        val how = if (p.targetElevation != 0) "the page's ${p.labels[label]} ft stands on ${p.targetElevation} ft of ground" else null
        ElevEntry(line, p.labels[label] ?: "", how) { feet ->
            p.elev.set(key, feet)
            p.guard { p.programFlow(true) }
            AttackFocus.touch(WdpPage.POPUP)
            changed()
        }.open()
    }

    /**
     * The ground under the target, typed (WDP's `TargetElv`): Get_Coords again, which adds it to the ingress altitude,
     * and every flow after it carries it — the VIP, VRP and PUP elevations, the pull-down altitude and OA1's, the Type 2
     * run-in (D64). Empty puts BMS's terrain back.
     */
    private fun editGround() {
        val p = plan
        if (!p.blnGotTGT) return
        val (g, src) = ground()
        ElevEntry(
            "TGT", g.toString(), "now $g ft, ${src.words}",
            caption1 = "Ground under the target", caption2 = "Feet above sea level",
        ) { feet ->
            p.elev.set(AttackGeometry.TGT, feet)
            p.guard { p.getCoords() }
            AttackFocus.touch(WdpPage.POPUP)
            changed()
        }.open()
    }

    /**
     * BMS's height map under every placed steerpoint, asked of the PC once ([WdpGround]); when it answers, Get_Coords
     * again, so the target's ground is the map's (WDP runs Get_Coords when its tables fill).
     */
    private fun askGround() {
        WdpGround.fetch(mission.groundTheater(), mission.groundPoints()) {
            if (plan.blnGotTGT) { plan.guard { plan.getCoords() }; changed() }
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

    private fun heading(deg: Int) {
        val p = plan
        p.guard { p.trbHeading.set(deg); p.changeHeading() }
    }

    private fun changed() {
        plan.paint()
        saveIni()
        version++
    }

    // ---------------------------------------------------------------- Setup.ini's [PopUp], in the app's preferences

    /** The saved section as Setup.ini's lines, or null before anything is saved (WDP's own fallbacks then apply). */
    private fun readIni(): List<Pair<String, String>>? = runCatching {
        val s = Repo.getString(INI_KEY) ?: return@runCatching null
        s.split('\n').filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
    }.getOrNull()

    private fun saveIni() {
        val s = plan.iniValues().entries.joinToString("\n") { "${it.key}=${it.value}" }
        if (s == savedIni) return
        savedIni = s
        runCatching { Repo.putString(INI_KEY, s) }
    }

    /**
     * A new mission or a switch of mode ([WdpSession.resetAttack]): WDP's defaults again (the saved section is gone),
     * no typed ELEV or target ground, and TGT STPT set again from the mission last handed in — its first strike
     * steerpoint, 1 when it has none.
     */
    fun resetForMission() {
        plan.resetToDefaults()
        createdIp = null
        savedIni = null
        defaultFor = null
        tableKey = null
        val m = mission
        handleMission(m)
        if (m.defaultStpt == null) { plan.setWaypoint(1); changed() }
    }

    internal companion object {
        const val INI_KEY = "wdp_popup_ini"
    }
    // The plan throws wherever WDP throws (an overflow, a Setup value WDP cannot parse), and WDP answers with an
    // error box and carries on with the page as far as the handler got. The plan's own handlers already do that
    // (`guard`); anything that still gets out is caught here and shown, which leaves the page where WDP leaves it.
    // A change of the attack's inputs, or its Save to DTC, makes this the Planner's current attack (AttackFocus).
    override fun onValue(name: String, value: String) {
        try { handleValue(name, value); if (AttackSelection.countsValue(name)) AttackFocus.touch(WdpPage.POPUP) } catch (e: Exception) { wdpError("Pop-up", e) }
    }
    override fun onClick(name: String) {
        try { handleClick(name); AttackSelection.clicked(WdpPage.POPUP, name) } catch (e: Exception) { wdpError("Pop-up", e) }
    }
    override fun onMission(mission: WdpMission) { try { handleMission(mission) } catch (e: Exception) { wdpError("Pop-up", e) } }

}

@Composable
fun rememberPopupWiring(): PopupWiring = remember { PopupWiring() }

/** The ELEV figures of the Pop-up and TOSS DED panels. */
internal val ELEV_LABELS = listOf("lblVIPelv", "lblPUPelv", "lblOA1elv", "lblOA2elv")
