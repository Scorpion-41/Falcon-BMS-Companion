package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.wdp.AttackGeometry
import com.bmscompanion.app.data.wdp.AttackMap
import com.bmscompanion.app.data.wdp.TossPlan
import com.bmscompanion.app.data.wdp.WdpValues

/**
 * The TOSS page's behaviour, joined to its layout.
 *
 * The layout (`cntTOSS.json`) knows nothing about planning; [TossPlan] knows nothing about controls. This is the
 * seam: it turns a slider's position into the plan's field in WDP's own units — `trbIngrSpd` is tens of knots,
 * `trbReleaseHeight` hundreds of feet — runs the same handler the original's Scroll event would, and hands the
 * plan's labels back to the renderer as the page's values. The compass labels and the reference knob are the
 * page's other inputs, and they do what their click handlers do in WDP.
 *
 * The slider ranges are values too, because two of them move: WDP re-derives the ingress-height and release-height
 * limits from the delivery mode and the pull-up, and the port carries those out of the plan every time.
 *
 * The Profile panel's buttons do what WDP's do: Show PPT Nr numbers the threats on the map, the zoom slider sets the
 * map's scale, Save Map saves it and Save to DTC hands the page's nav offsets to the cartridge
 * ([WdpAttackMap]). The map is [TossPlan.mapPicture] over the app's own theater map inside picSatView, with the
 * mission's steerpoints and the cartridge's pre-planned threats on it.
 *
 * **The target is the TGT STPT box**, as in WDP: the target is that steerpoint and the IP the one before it, from
 * the Planner's steerpoint table ([WdpMission.slots]: the cartridge's slot, else BMS's route, else the save's cell).
 * There is no target bar. A new mission or flight sets the box once to [WdpMission.defaultStpt]; after that the page
 * keeps its own, and the other attack pages keep theirs (A3). The IP is the one before it unless the pilot picks
 * another in IP STPT ([TossPlan.ipStpt]); WDP's Campaign and TE buttons are off the page, IP STPT at the VRP has
 * their row, and Save to DTC also fills the DataCard's Delivery block once it has saved ([AttackSelection], D87).
 *
 * **Setup.ini's `[TOSS]`** is kept in the app's preferences, as Pop-up's and HADB's are: read before the page's
 * Load (`cntTOSS.Setup` reads it there, so each slider goes through its own handler and range) and written whenever
 * it changes, with what `fclsMain` writes on exit (D9). Absent the first time, so the page starts where it always did.
 *
 * **Every ELEV can be typed**, and the page says so: the DED's ELEV figures and the Coordinates box's target Elv carry
 * a pencil and a frame (solid cyan once typed), the mouse turns to a hand over them and the foot of the DED panel says
 * what a tap does ([ElevMark], [DedElevHint]); a tap opens a small entry window or a finger's editor ([ElevEntry]).
 * What is typed is shown and saved until the target changes (D7). TOSS's ELEVs are WDP's heights above the target plus
 * the ground under it (D7): BMS's height map read on the PC ([WdpGround]), else the cartridge's own target points
 * ([groundAt]), or what the pilot typed for the target — which the target Elv shows (D65), and which moves every ELEV
 * but the 0s, nothing else (WDP works nothing out from an elevation here). The target steerpoint may be any of 1 to 25
 * (D8).
 */
class TossWiring : WdpWiring, WdpControlContent {
    val plan = TossPlan().also { it.ref = false; readIni(it); it.load() }
    private var savedIni: String? = null
    /** The map in picSatView, and the page's Save Map and Save to DTC. */
    val map = WdpAttackMap("TOSS")
    private var version by mutableIntStateOf(0)

    /** What the page shows now: every label the plan fills, plus each slider's position and range. */
    override fun values(hidden: List<String>): WdpValues {
        @Suppress("UNUSED_VARIABLE") val v = version   // read so a change re-composes the page
        val p = plan
        val m = LinkedHashMap<String, String>()
        for (h in hidden) m[h] = "hidden"
        m.putAll(p.labels)
        fun slider(name: String, value: Int, min: Int, max: Int) { m[name] = value.toString(); m["$name.min"] = min.toString(); m["$name.max"] = max.toString() }
        slider("trbIngrSpd", p.ingressCas / 10, 30, 55)
        slider("trbIngrHeight", p.ingressHeight / 100, p.ingressSliderMin, p.ingressSliderMax)
        slider("trbG", p.pullingGs, 2, 6)
        slider("trbTurn", if (p.turnDirection == "Right") 1 else 0, 0, 1)
        slider("trbOa2ToPup", p.oa2ToPupNm, 1, 5)
        slider("trbReleaseAngle", p.releaseAngleDeg, 0, 45)
        slider("trbReleaseSpd", p.releaseCas / 10, 30, 55)
        slider("trbReleaseHeight", p.releaseHeight / 100, p.releaseSliderMin, p.releaseSliderMax)
        slider("trbAngleOff", p.angleOff, 0, 90)
        slider("trbHeading", p.attackHeadingDeg, 0, 360)
        // the reference knob: which of the stacked pictures is up, as Ref() flips them
        slider("trbZoom", p.zoom, 0, 100)
        m["btnPPTnr.text"] = p.btnPPTnrText
        // Draw's legend: the run starts at the VRP, in blue, or at the IP, and then the legend is blank
        m["lblVRP"] = if (p.ref) "" else "VRP"
        m["lblVRP.fore"] = "DodgerBlue"
        m["pnlRefUp"] = if (p.ref) "shown" else "hidden"
        m["pnlRefDown"] = if (p.ref) "hidden" else "shown"
        m["pnlRef_Up"] = if (p.ref) "shown" else "hidden"
        m["pnlRef_Down"] = if (p.ref) "hidden" else "shown"
        m["pnlDED_Ref_Up"] = if (p.ref) "shown" else "hidden"
        m["pnlDED_Ref_Down"] = if (p.ref) "hidden" else "shown"
        // Ref(): the "blocked" flags go up when there is no IP to plan a VIP from
        val blocked = if (p.doVip) "hidden" else "shown"
        m["pnlBlocked_1"] = blocked; m["pnlBlocked_2"] = blocked; m["pnlBlocked_3"] = blocked
        // the rotary switch at the top: Selections, Profile or DED Data in the right-hand half of the page
        val sel = listOf("Selections", "Profile", "DED Data").indexOf(selections).coerceAtLeast(0)
        m["pnlSelections_Up"] = if (sel == 0) "shown" else "hidden"
        m["pnlSelections_Middle"] = if (sel == 1) "shown" else "hidden"
        m["pnlSelections_Down"] = if (sel == 2) "shown" else "hidden"
        m["pnlSelections"] = if (sel == 0) "shown" else "hidden"
        m["pnlProfile"] = if (sel == 1) "shown" else "hidden"
        m["pnlDEDData"] = if (sel == 2) "shown" else "hidden"
        // ProfileSwitch(): Falcas's drawing of the profile, for the turn and the reference in use
        m["picProfile"] = "TOSS_" + (if (p.ref) "VIP" else "VRP") + "_" + (if (p.turnDirection == "Left") "Left" else "Right") + ".jpg"
        // SightDepression(): the HUD check is a coloured flag
        m["lblTargetHUDval.back"] = if (p.labels["lblTargetHUDval"] == "NO") "Red" else "Lime"
        // WDP's Campaign and TE buttons are off the page (AttackSelection.REMOVED, D87); IP STPT stands beside TGT STPT,
        // each box's tip saying where its steerpoint's position came from; Save to DTC also fills the DataCard
        m["numWaypoint"] = p.waypoint.toString()
        val ip = p.ipWaypoint
        m[AttackSelection.IP_BOX] = if (ip >= 1) ip.toString() else ""
        AttackSelection.ipBoxValues(m)
        AttackSelection.targetTip(mission, p.waypoint).let { m["numWaypoint.tip"] = it; m["lblTGTwp.tip"] = it }
        AttackSelection.ipTip(mission, p.waypoint, p.ipStpt, p.doVip).let { m["${AttackSelection.IP_BOX}.tip"] = it; m["${AttackSelection.IP_LABEL}.tip"] = it }
        AttackSelection.saveValues(m, "TOSS", "TOSS", p.ref)
        // the designer's MAT (Medium Alt TOSS) is disabled: WDP plans the low-altitude toss only, and its knob stays there
        m["lblMAT.enabled"] = "false"
        // the DED's units after their figures, whatever the font's spaces (see dedUnits)
        m.dedUnits(DED_LINES_VIP)
        // the target's Elv is the ground the ELEVs stand on (D65), and a typed ELEV is drawn in its own ink
        if (p.gotTgt) m["lblTGT_elv"] = p.targetElevation.toString()
        if (p.elev.isTyped(AttackGeometry.TGT)) m["lblTGT_elv.fore"] = TYPED_INK_NAME
        for (l in ELEV_LABELS) elevKey(l, p.ref)?.let { if (p.elev.isTyped(it)) m["$l.fore"] = TYPED_INK_NAME }
        if (p.objC != null) {
            // Get_Coords' lat/lon labels, as WDP prints them for the theater BMS is on, shown and hidden as it does
            for ((name, on) in p.coordShown) if (!on) m[name] = "hidden"
        } else {
            // the theater is not known: no projection to print lat/lon with, so the feet stand in for them
            m["lblTGT_N"] = tgtText.first; m["lblTGT_E"] = tgtText.second
            m["lblIP_N"] = ipText.first; m["lblIP_E"] = ipText.second
        }
        // which selectors show (D94): IP STPT only in VIP mode or while VIP is blocked, its lines in VIP mode only, and
        // IP STPT at the VRP in VRP mode, and in VIP mode while the IP STPT is the one it created
        AttackSelection.selectors(m, p.ref, p.doVip, p.gotTgt, hadb = false, ip = ip, again = createdIp?.n == ip)
        // the headings flown on the HUD and HSD are magnetic: the tips give the magnetic figure at the target (D93)
        val at = p.attackPlot()?.target
        for (n in listOf("lblAttackHdgVal", "lblApproachHedVal")) {
            AttackSelection.magneticTip(WdpTips.text("cntTOSS", n), m[n]?.trim()?.toDoubleOrNull(), mission, at)?.let { m["$n.tip"] = it }
        }
        return WdpValues(m)
    }

    /**
     * **IP STPT at the VRP** (D94): see [AttackSelection.ipAtVrp]; in VRP mode, or in VIP mode from the IP STPT this
     * button created (which then moves to the VRP the inputs lay out now, [TossPlan.vrpPoint]). The page then plans
     * VIP from the steerpoint placed, and keeps it for [ipNotice].
     */
    private fun ipAtVrp() {
        val p = plan
        if (!p.gotTgt) return
        if (p.ref && createdIp?.n != p.ipWaypoint) return
        AttackSelection.ipAtVrp(
            WdpPage.TOSS, mission, p.waypoint, p.ipWaypoint, p.attackPlot()?.target, p.vrpPoint(), p.navOffsets()["VRP"]?.elv ?: 0, createdIp,
        ) { m, n, at ->
            createdIp = at
            applyMission(m)
            p.ipStpt = n
            loadTargets()
            p.chooseRef(true)
            saveIni()
            version++
        }
    }

    /**
     * Which of the three stacked right-hand panels is up. WDP's knob steps forward on a left click and back on a
     * right one; a finger has only the one, so a tap steps forward and wraps round from DED Data to Selections, and
     * a tap on a knob's own caption goes straight to it.
     */
    private var selections = "Selections"

    private var mission = WdpMission()
    private var tgtText = "" to ""
    private var ipText = "" to ""

    /** The mission ([WdpMission.key]) whose default TGT STPT the box was last set to: set once, then the pilot's. */
    private var defaultFor: String? = null
    /** The IP STPT that IP STPT at the VRP created on this page this session, for the page's line about it. */
    private var createdIp: AttackSelection.CreatedIp? = null

    override fun controlContent(): Map<String, @Composable () -> Unit> = map.content(
        { mission.attackCaption(plan.waypoint, plan.gotTgt) }, { plan.gotTgt }, { ipNotice() },
    ) {
        @Suppress("UNUSED_VARIABLE") val v = version   // the map is drawn again after every change
        // the page's own drawing of its route and threats, the attack drawn over it as every map draws it
        try { plan.mapPicture().withAttack(WdpAttackOverlay.ofPlan(plan)) } catch (e: Exception) { null }
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
        val slot = runCatching { WdpSession.dtc.stptAt(c.n) }.getOrNull()
        return AttackSelection.ipNotice(c, plan.ipWaypoint, plan.vrpPoint(), slot)
    }

    /** The ELEVs' pencils and the DED panel's line about them, drawn again after every change. */
    private fun elevContent(): Map<String, @Composable () -> Unit> {
        val out = LinkedHashMap<String, @Composable () -> Unit>()
        for (l in ELEV_LABELS) out[l] = {
            @Suppress("UNUSED_VARIABLE") val v = version
            ElevMark(typed = elevKey(l, plan.ref)?.let { plan.elev.isTyped(it) } == true)
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
        val t = mission.steerpoint(p.waypoint)?.takeIf { p.gotTgt } ?: return 0 to GroundSource.NONE
        if (p.elev.isTyped(AttackGeometry.TGT)) return p.targetElevation to GroundSource.TYPED
        return mission.groundSourced(p.waypoint, t.x, t.y)?.let { p.targetElevation to it.second } ?: (0 to GroundSource.NONE)
    }

    private fun groundLine(): String = if (!plan.gotTgt) "Target ground: no target" else ground().let { (g, src) -> "Target ground $g ft, ${src.words} · tap to change" }

    /** The ground under the target, typed (D7): the target read again, and every ELEV but the 0s stands on it. */
    private fun editGround() {
        val p = plan
        if (!p.gotTgt) return
        val (g, src) = ground()
        ElevEntry("TGT", g.toString(), "now $g ft, ${src.words}", caption1 = "Ground under the target", caption2 = "Feet above sea level") { feet ->
            p.elev.set(AttackGeometry.TGT, feet)
            loadTargets()
            AttackFocus.touch(WdpPage.TOSS)
            saveIni()
            version++
        }.open()
    }

    /** BMS's height map under every placed steerpoint, asked of the PC once ([WdpGround]); the target read again on the answer. */
    private fun askGround() {
        WdpGround.fetch(mission.groundTheater(), mission.groundPoints()) {
            if (plan.gotTgt) { loadTargets(); version++ }
        }
    }

    /**
     * The mission arrived or changed. A new mission or flight moves the steerpoint box to its default target
     * steerpoint, once ([WdpMission.defaultStpt]); the target and IP then follow the box, exactly as `Get_Coords`
     * picks them. With the theater known the coordinate labels are WDP's lat/lon. The map takes the mission's
     * steerpoints and the cartridge's pre-planned threats, as `Draw` reads them from the campaign tables.
     */
    override fun onMission(mission: WdpMission) {
        try { applyMission(mission) } catch (e: Exception) { wdpError("TOSS", e) }
    }

    private fun applyMission(mission: WdpMission) {
        this.mission = mission
        map.theater = mission.theater
        askGround()
        AttackSelection.askVariation(mission) { version++ }
        // what Draw reads from the campaign tables: the flight plan by steerpoint number, and the threats
        val route = Array(25) { AttackMap.Route() }
        for (pt in mission.steerpoints) if (pt.n in 1..25) route[pt.n - 1] = AttackMap.Route(pt.y.toFloat(), pt.x.toFloat(), pt.action)
        plan.route = route.toList()
        plan.threats = mission.dtc?.ppts.orEmpty().take(15)
            .map { AttackMap.Threat(it.y.toFloat(), it.x.toFloat(), (it.rangeNm * TossPlan.NM_TO_FT_F.toDouble()).toFloat(), it.name ?: "") }
        // SetCoordData: the theater's projection, for the lat/lon labels and the VIP figures read back from them
        plan.objC = mission.coords
        // CreateFlightplan's numWaypoint + STPTChange, once per mission or flight; after that the box is the pilot's
        val d = mission.defaultStpt
        if (d != null && mission.key != defaultFor) {
            defaultFor = mission.key
            plan.waypoint = d.coerceIn(1, 25)
            // a new mission's IP follows its target again (D87)
            plan.ipStpt = null
        }
        loadTargets()
        saveIni()
        version++
    }

    /**
     * `STPTChange` + `Get_Coords`: target = steerpoint *waypoint*, IP = the one before it or the IP STPT picked (D87),
     * each from the Planner's steerpoint table ([WdpMission.slots]). Sim feet straight in; with the theater's
     * projection the plan prints them as WDP's lat/lon labels, and without it those labels show the feet.
     */
    private fun loadTargets() {
        val p = plan
        fun feet(pt: com.bmscompanion.app.data.mission.DtcPoint?) =
            pt?.takeIf { it.x != 0.0 || it.y != 0.0 }?.let { Triple(it.x, it.y, it.altFt) }
        // a picked IP the target has moved onto is no IP: the IP follows the target again
        if (p.ipStpt == p.waypoint) p.ipStpt = null
        val tgt = feet(mission.steerpoint(p.waypoint))
        val ip = feet(mission.steerpoint(p.ipWaypoint).takeIf { p.ipWaypoint != p.waypoint })
        // the ground under the target (D7): what the pilot typed for this target, else BMS's height map, else the DTC's
        p.setTargets(tgt, ip, tgt?.let { p.elev.typedFor(AttackGeometry.TGT, it.first, it.second)?.toDouble() ?: mission.groundAt(p.waypoint, it.first, it.second) })
        tgtText = if (p.gotTgt && tgt != null) "N ${fmtFt(tgt.first)}" to "E ${fmtFt(tgt.second)}" else "" to ""
        ipText = if (p.doVip && ip != null) "N ${fmtFt(ip.first)}" to "E ${fmtFt(ip.second)}" else "" to ""
    }

    private fun fmtFt(v: Double): String = (v / 1000.0).let { k -> ((k * 10).toInt() / 10.0).toString() } + " kft"

    /** A slider moved: the control's new position, in the slider's own units. */
    override fun onValue(name: String, value: String) {
        // a change of the attack's inputs makes this the Planner's current attack (AttackFocus)
        try { handleValue(name, value); if (AttackSelection.countsValue(name)) AttackFocus.touch(WdpPage.TOSS) } catch (e: Exception) { wdpError("TOSS", e) }
    }

    private fun handleValue(name: String, value: String) {
        val v = value.trim().toIntOrNull() ?: return
        val p = plan
        when (name) {
            "trbIngrSpd" -> { p.ingressCas = v * 10; p.changeIngressSpeed() }
            "trbIngrHeight" -> { p.ingressHeight = v * 100; p.changeIngressHeight() }
            "trbG" -> { p.pullingGs = v; p.changePullingG() }
            "trbTurn" -> { p.turnDirection = if (v == 0) "Left" else "Right"; p.changeTurn() }
            "trbOa2ToPup" -> { p.oa2ToPupNm = v; p.changeOa2ToPup() }
            "trbReleaseAngle" -> { p.releaseAngleDeg = v; p.changeRelAngle() }
            "trbReleaseSpd" -> { p.releaseCas = v * 10; p.changeRelSpd() }
            "trbReleaseHeight" -> { p.releaseHeight = v * 100; p.changeRelHeight() }
            "trbAngleOff" -> { p.angleOff = v; p.changeAngleOff() }
            "trbHeading" -> { p.attackHeadingDeg = v; p.changeHeading() }
            // numWaypoint: every steerpoint, 1..25 (WDP's box stopped at 3: D8); STPTChange re-fetches the target and IP
            "numWaypoint" -> { p.waypoint = v.coerceIn(1, 25); loadTargets() }
            // the IP STPT box (D87): a number typed, taken as it is; TGT STPT − 1 follows again (the arrows: handleClick)
            AttackSelection.IP_BOX -> {
                val pick = AttackSelection.typed(value, p.waypoint, p.ipStpt)
                if (pick != p.ipStpt) { p.ipStpt = pick; loadTargets() }
            }
            // trbZoom_Scroll: ZoomLevel, and the map follows
            "trbZoom" -> { p.zoom = v.coerceIn(0, 100); p.zoomLevel() }
            else -> return
        }
        saveIni()
        version++
    }

    /** Something pressed: the shortcuts, the knobs and the Profile panel's buttons. */
    override fun onClick(name: String) {
        // a change of the attack's inputs, or its Save to DTC, makes this the Planner's current attack (AttackFocus)
        try { handleClick(name); AttackSelection.clicked(WdpPage.TOSS, name) } catch (e: Exception) { wdpError("TOSS", e) }
    }

    private fun handleClick(name: String) {
        val p = plan
        val button = name.substringAfter(':', "")
        when (name.substringBefore(':')) {
            "lbl0" -> { p.angleOff = 0; p.changeAngleOff() }
            "lbl30" -> { p.angleOff = 30; p.changeAngleOff() }
            "lbl60" -> { p.angleOff = 60; p.changeAngleOff() }
            "lbl90" -> { p.angleOff = 90; p.changeAngleOff() }
            "lblN" -> { p.attackHeadingDeg = 0; p.changeHeading() }
            "lblE" -> { p.attackHeadingDeg = 90; p.changeHeading() }
            "lblS" -> { p.attackHeadingDeg = 180; p.changeHeading() }
            "lblW" -> { p.attackHeadingDeg = 270; p.changeHeading() }
            "lblN360" -> { p.attackHeadingDeg = 360; p.changeHeading() }
            // WDP's knob steps forward on a left click and back on a right one (where a right click on Selections does
            // nothing); a finger has one button, so a plain tap steps forward and wraps round from DED Data
            "pnlSelections_Up" -> if (button != "right") selections = "Profile"
            "pnlSelections_Middle" -> selections = if (button == "right") "Selections" else "DED Data"
            "pnlSelections_Down" -> selections = if (button == "right") "Profile" else if (button == "left") return else "Selections"
            "lblShowSelections" -> selections = "Selections"
            "lblProfileSelect" -> selections = "Profile"
            "lblDEDDataSelec" -> selections = "DED Data"
            "pnlRefUp", "pnlRefDown", "pnlRef_Up", "pnlRef_Down", "pnlDED_Ref_Up", "pnlDED_Ref_Down" -> p.chooseRef(!p.ref)
            // the delivery knob has the one position, the low-altitude toss (Delivery())
            "pnlDelivery_Up", "pnlDelivery_Down" -> p.delivery()
            "btnPPTnr" -> p.showPPTnr()
            "btnSaveMap" -> { map.saveMap(); return }
            // SaveNavOffsets runs with every flow in WDP; the offsets are the flow's last, handed on at the press,
            // and only the reference in use (D3)
            // and once saved, the card's Delivery block from this page, as the card's own TOSS button fills it (D87)
            "btnSaveDTC" -> { map.saveDtc(p.navOffsets(), p.navModesel); return }
            // a steerpoint on the VRP becomes the IP, and the attack is planned as VIP from it (D94)
            AttackSelection.IP_AT_VRP -> { ipAtVrp(); return }
            // the IP STPT box's arrows by name
            AttackSelection.IP_BOX -> {
                val by = when (button) { "up" -> 1; "down" -> -1; else -> return }
                val pick = AttackSelection.arrow(mission, by, p.ipWaypoint, p.waypoint, p.ipStpt)
                if (pick != p.ipStpt) { p.ipStpt = pick; loadTargets() }
            }
            // an ELEV on the DED panel: typed by the pilot (D7)
            "lblVIPelv", "lblPUPelv", "lblOA1elv", "lblOA2elv" -> { editElev(name.substringBefore(':')); return }
            "lblTGT_elv" -> { editGround(); return }
            else -> return
        }
        saveIni()
        version++
    }

    /** The entry window for one DED line's ELEV, keyed to the nav-offset line it belongs to in the reference in use. */
    private fun editElev(label: String) {
        val p = plan
        val vip = p.ref
        val (line, key) = when (label) {
            "lblVIPelv" -> if (vip) "VIP-TO-TGT" to "VIP" else "TGT-TO-VRP" to "VRP"
            "lblPUPelv" -> (if (vip) "VIP-TO-PUP" else "TGT-TO-PUP") to (if (vip) "VIPPUP" else "VRPPUP")
            "lblOA1elv" -> "OA1" to (if (vip) "OA1_1" else "OA1_2")
            else -> "OA2" to (if (vip) "OA2_1" else "OA2_2")
        }
        val how = if (p.targetElevation != 0) "the page's ${p.labels[label]} ft stands on ${p.targetElevation} ft of ground" else null
        ElevEntry(line, p.labels[label] ?: "", how) { feet ->
            p.elev.set(key, feet)
            p.programFlow()
            AttackFocus.touch(WdpPage.TOSS)
            saveIni()
            version++
        }.open()
    }

    // ---------------------------------------------------------------- Setup.ini's [TOSS], in the app's preferences

    /**
     * What `fclsMain` writes to `[TOSS]` on the way out, under its names. Two places differ, both where WDP's own
     * write loses what the pilot set: `AngleOff` is written from the attack heading there, so the angle off came back
     * as the heading; here it is the angle off. And `Ref` is the reference the page shows, where WDP writes its flag
     * and `Setup` inverts it again on the way in.
     */
    private fun iniText(): String {
        val p = plan
        return listOf(
            "Ref" to (if (p.ref) "True" else "False"),
            "IngressCAS" to p.ingressCas.toString(),
            "IngressHeight" to p.ingressHeight.toString(),
            "PullingGs" to p.pullingGs.toString(),
            "Turn" to (if (p.turnDirection == "Left") "0" else "1"),
            "OA2toPUP" to p.oa2ToPupNm.toString(),
            "ReleaseAngle" to p.releaseAngleDeg.toString(),
            "ReleaseCAS" to p.releaseCas.toString(),
            "ReleaseHeight" to p.releaseHeight.toString(),
            "AngleOff" to p.angleOff.toString(),
            "AttackHdg" to p.attackHeadingDeg.toString(),
            "Waypoint" to p.waypoint.toString(),
        ).joinToString("\n") { "${it.first}=${it.second}" }
    }

    private fun saveIni() {
        val s = iniText()
        if (s == savedIni) return
        savedIni = s
        runCatching { Repo.putString(INI_KEY, s) }
    }

    /**
     * `Setup()`'s reading, before the Load runs the handlers: a slider whose saved value is outside its range goes where
     * `cntTOSS.Setup` puts it — the middle for the ingress speed, 3 for the G and OA2-to-PUP, 0 for the release angle,
     * the angle off and the heading, 500 kt for the release CAS; the two heights are left to the plan, whose ranges move
     * and which clamps them itself. Nothing saved (the first run) leaves the page's own starting values. What the port
     * writes it reads back as written (D9): WDP writes the attack heading as `AngleOff`, the turn as 0/1 and reads back
     * only "Right", and compares the release CAS in knots with a slider in tens of knots, so its angle off, a right turn
     * and its release CAS never came back.
     */
    private fun readIni(p: TossPlan) {
        val saved = runCatching { Repo.getString(INI_KEY) }.getOrNull() ?: return
        val ini = saved.split('\n').filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        fun bar(key: String, unit: Int, min: Int, max: Int, outside: Int? = null): Int? {
            val v = ini[key]?.toDoubleOrNull() ?: return null
            val pos = v / unit
            return (if (pos >= min && pos <= max) TossPlan.bankers(pos) else outside?.toLong() ?: TossPlan.bankers((max - min) / 2.0 + min)).toInt() * unit
        }
        ini["Ref"]?.let { p.ref = it.equals("True", ignoreCase = true) }
        bar("IngressCAS", 10, 30, 55)?.let { p.ingressCas = it }
        ini["IngressHeight"]?.toIntOrNull()?.let { p.ingressHeight = it }
        bar("PullingGs", 1, 2, 6, outside = 3)?.let { p.pullingGs = it }
        ini["Turn"]?.let { p.turnDirection = if (it == "0" || it.equals("Left", true)) "Left" else "Right" }
        bar("OA2toPUP", 1, 1, 5, outside = 3)?.let { p.oa2ToPupNm = it }
        bar("ReleaseAngle", 1, 0, 45, outside = 0)?.let { p.releaseAngleDeg = it }
        bar("ReleaseCAS", 10, 30, 55, outside = 50)?.let { p.releaseCas = it }
        ini["ReleaseHeight"]?.toIntOrNull()?.let { p.releaseHeight = it }
        bar("AngleOff", 1, 0, 90, outside = 0)?.let { p.angleOff = it }
        bar("AttackHdg", 1, 0, 360, outside = 0)?.let { p.attackHeadingDeg = it }
        ini["Waypoint"]?.toIntOrNull()?.let { p.waypoint = it.coerceIn(1, 25) }
    }

    /**
     * A new mission or a switch of mode ([WdpSession.resetAttack]): the page's own starting values again (what it starts
     * with when nothing is saved, as WDP's first run), no typed ELEV or target ground, the Load run again, and TGT STPT
     * set again from the mission last handed in — its first strike steerpoint, 1 when it has none.
     */
    fun resetForMission() {
        val p = plan
        val d = TossPlan()
        p.ref = false
        p.ingressCas = d.ingressCas; p.ingressHeight = d.ingressHeight; p.pullingGs = d.pullingGs; p.turnDirection = d.turnDirection
        p.oa2ToPupNm = d.oa2ToPupNm; p.releaseAngleDeg = d.releaseAngleDeg; p.releaseCas = d.releaseCas; p.releaseHeight = d.releaseHeight
        p.angleOff = d.angleOff; p.attackHeadingDeg = d.attackHeadingDeg; p.waypoint = d.waypoint; p.ipStpt = null
        p.elev.clear()
        p.load()
        createdIp = null
        savedIni = null
        defaultFor = null
        val m = mission
        applyMission(m)
        if (m.defaultStpt == null) { p.waypoint = 1; loadTargets(); saveIni(); version++ }
    }

    internal companion object {
        const val INI_KEY = "wdp_toss_ini"
    }
}

@Composable
fun rememberTossWiring(): TossWiring = remember { TossWiring() }
