package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.BmsStatus
import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.Leftovers
import com.bmscompanion.app.data.wdp.DtcEdits
import com.bmscompanion.app.ui.screens.wdp.DtcWiring
import com.bmscompanion.app.ui.screens.wdp.WdpMission
import java.io.File
import com.bmscompanion.app.data.mission.BriefOverview
import com.bmscompanion.app.data.mission.Briefing
import com.bmscompanion.app.data.mission.BridgeInfo
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionMode
import com.bmscompanion.app.data.mission.MissionSourceInfo
import com.bmscompanion.app.data.wdp.AttackGeometry
import com.bmscompanion.app.data.wdp.TossPlan
import com.bmscompanion.app.ui.screens.mission.MissionEpoch
import com.bmscompanion.app.ui.screens.mission.MissionTab
import com.bmscompanion.app.ui.screens.mission.MissionTabRequest
import com.bmscompanion.app.data.wdp.DtcFromMission
import com.bmscompanion.app.ui.screens.wdp.MapPick
import com.bmscompanion.app.ui.screens.wdp.PopupWiring
import com.bmscompanion.app.ui.screens.wdp.TossWiring
import com.bmscompanion.app.ui.screens.wdp.WdpMapView
import com.bmscompanion.app.ui.screens.wdp.WdpSession

/**
 * Part 11 of `--missiontest`: what each device starts afresh at a new mission and at a switch of mode ([MissionEpoch];
 * docs/DATA-STORES.md, "What each device starts afresh"). In process, on the app's own objects, with the device's
 * preferences in the check's scratch APPDATA:
 *
 * 1. The first mission seen is taken quietly.
 * 2. The Planner's Map page has a selection, a move, Measure and an HSD centre; Pop-up and TOSS have a typed ELEV and a
 *    target ground; TOSS's ingress speed is not its default; the DataCard has a profile chosen.
 * 3. **A PRINT of another flight** (EZBoards mode): all of it starts afresh — the map's view cleared, the attack pages
 *    on WDP's defaults with no typed ELEV, the card with no profile; the map layers (a pilot's preference) kept.
 * 4. The same flight printed again: nothing.
 * 5. **A switch of mode**: started afresh again; into EZBoards mode the Briefing tab is asked for ([MissionTabRequest]).
 */
internal object MissionEpochTest {
    fun run(ok: (Boolean, String) -> Unit, line: (String) -> Unit) {
        line("")
        line("== 11. What each device starts afresh at a new mission and a switch of mode")
        val tabWas = Repo.getString("mission_tab")
        try {
            MissionTabRequest.take()
            Repo.putString("mission_epoch", null)
            fun brief(cs: String, pkg: String) = MissionData(briefing = Briefing(overview = BriefOverview(flight = cs, packageId = pkg)), briefingModified = 1)
            fun info(mode: String) = BridgeInfo(bms = BmsStatus(theater = "Korea KTO"), mission = MissionSourceInfo(mode = mode))
            val n0 = MissionEpoch.n
            MissionEpoch.observe(info(MissionMode.EZBOARDS), brief("Cobra1", "1234"))
            ok(MissionEpoch.n == n0 && Repo.getString("mission_epoch")?.contains("Cobra1") == true, "the first mission seen is taken quietly")

            // ---- the last mission's state on this device
            fun dirty() {
                WdpMapView.sel = MapPick.Stpt(3)
                WdpMapView.moving = MapPick.Ppt(56)
                WdpMapView.measureOn = true; WdpMapView.m1 = DtcFromMission.Pt(1.0, 2.0)
                WdpMapView.hsdCentre = 4
                WdpMapView.packageOn.add("1/2")
                WdpSession.popup.plan.elev.set(AttackGeometry.TGT, 777)
                WdpSession.toss.plan.elev.set("lblVIPelv", 1234)
                WdpSession.toss.plan.ingressCas = 350
                runCatching { WdpSession.dataCard.onClick("btnHADB") }
                com.bmscompanion.app.ui.screens.wdp.AttackFocus.touch(com.bmscompanion.app.ui.screens.wdp.WdpPage.HADB)
            }
            dirty()
            val layer = com.bmscompanion.app.ui.screens.wdp.WdpMapPrefs.threats.on
            ok(WdpSession.popup.plan.elev.isTyped(AttackGeometry.TGT) && WdpSession.toss.plan.ingressCas == 350,
                "the last mission's state: map selection, move, Measure, HSD centre, typed ELEVs, TOSS ingress 350, card profile ${WdpSession.dataCard.attackProfile}")

            // ---- a PRINT of another flight
            MissionEpoch.observe(info(MissionMode.EZBOARDS), brief("Viper2", "2001"))
            val fresh = TossPlan()
            ok(MissionEpoch.n == n0 + 1, "a PRINT of another flight is a new mission (epoch ${MissionEpoch.n})")
            ok(WdpMapView.sel == null && WdpMapView.moving == null && !WdpMapView.measureOn && WdpMapView.m1 == null &&
                WdpMapView.hsdCentre == null && WdpMapView.packageOn.isEmpty(),
                "the Planner's Map page: selection, move, Measure, HSD centre and package flights cleared")
            ok(!WdpSession.popup.plan.elev.isTyped(AttackGeometry.TGT) && !WdpSession.toss.plan.elev.isTyped("lblVIPelv"),
                "Pop-up's typed target ground and TOSS's typed ELEV gone")
            ok(WdpSession.toss.plan.ingressCas == fresh.ingressCas && WdpSession.toss.plan.releaseCas == fresh.releaseCas,
                "TOSS back on its defaults (ingress ${WdpSession.toss.plan.ingressCas} kt)")
            val popupDefault = com.bmscompanion.app.data.wdp.PopupPlan().apply { load(null) }.iniValues().filterKeys { it != "Waypoint" }
            ok(WdpSession.popup.plan.iniValues().filterKeys { it != "Waypoint" } == popupDefault,
                "Pop-up back on WDP's defaults (${WdpSession.popup.plan.iniValues().entries.take(5).joinToString { "${it.key}=${it.value}" }})")
            ok(WdpSession.dataCard.attackProfile == null && com.bmscompanion.app.ui.screens.wdp.AttackFocus.profile == null, "the DataCard's profile choice and the current attack forgotten")
            ok(com.bmscompanion.app.ui.screens.wdp.WdpMapPrefs.threats.on == layer, "the map layers (the pilot's) kept")
            ok(Repo.getString(PopupWiring.INI_KEY)?.contains("IngressAlt") != false && Repo.getString(TossWiring.INI_KEY)?.contains("IngressCAS=${fresh.ingressCas}") != false,
                "the saved sections hold the defaults")

            // ---- the same flight again; a switch of mode
            dirty()
            MissionEpoch.observe(info(MissionMode.EZBOARDS), brief("Viper2", "2001").copy(briefingModified = 2))
            ok(MissionEpoch.n == n0 + 1 && WdpMapView.hsdCentre == 4, "the same flight printed again: nothing started afresh")
            MissionEpoch.observe(info(MissionMode.WDP), brief("Viper2", "2001"))
            ok(MissionEpoch.n == n0 + 2 && WdpMapView.hsdCentre == null && !WdpSession.popup.plan.elev.isTyped(AttackGeometry.TGT),
                "a switch of mode starts it afresh too")
            ok(MissionTabRequest.pending == null, "into WDP mode: no tab is asked for")

            // ---- back to EZBoards mode: the Mission section opens on the Briefing (the Planner is greyed out there)
            MissionEpoch.observe(info(MissionMode.EZBOARDS), brief("Viper2", "2001"))
            ok(MissionEpoch.n == n0 + 3 && MissionTabRequest.pending == MissionTab.BRIEF && Repo.getString("mission_tab") == MissionTab.BRIEF.name,
                "into EZBoards mode: started afresh, and the Briefing tab asked for (and kept for the next time the section opens)")
            ok(MissionTabRequest.take() == MissionTab.BRIEF && MissionTabRequest.pending == null, "…taken once")

            // ---- the DTC page's delivery data: an earlier mission's [NAV OFFSETS] (no ledger), and a VIP typed here
            val cartText = Bridge.callsignIni?.let(::File)?.takeIf { it.isFile }?.readText(Charsets.ISO_8859_1)
            if (cartText == null) line("   (the copy has no cartridge: the DTC page's delivery data is not checked)")
            else {
                val fx = File.createTempFile("bmsc-epoch-", ".ini")
                val fileWas = WdpDtcFixture.file
                try {
                    fx.writeText(DtcEdits.apply(cartText, listOf(
                        CartridgeEdit("NAV OFFSETS", "Modesel", "vrp"),
                        CartridgeEdit("NAV OFFSETS", "VRP", "8,137.7,46870,500"),
                        CartridgeEdit("NAV OFFSETS", "VRPPUP", "8,137.7,28642,500"),
                        CartridgeEdit("NAV OFFSETS", "OA1-8", "167.6,18939,6748"),
                        CartridgeEdit("NAV OFFSETS", "OA2-8", "167.6,18939,500"),
                    )), Charsets.ISO_8859_1)
                    WdpDtcFixture.file = fx
                    val w = DtcWiring(WdpDtcFixture.source())
                    w.onMission(WdpMission(dtc = Dtc(modified = 1)))
                    val m0 = w.model
                    ok(m0 != null && m0.nav.modesel == 2 && m0.nav.vrp.range > 0 && w.unsaved == 0,
                        "the DTC page opens on a cartridge with an earlier mission's VRP attack (Modesel vrp, VRP ${m0?.nav?.vrp?.range} ft)")
                    m0?.popUpNav?.vip?.range = 20000
                    w.clearDeliveryForMission()
                    val m1 = w.model
                    val nv = m1?.nav
                    // (an aim point's steerpoint reads as WDP's default, 3, with nothing on it: only its bearing and range say)
                    ok(nv != null && nv.modesel == 0 && listOf(nv.vip, nv.vipPup, nv.vrp, nv.vrpPup).all { it.stpt == 0 && it.range == 0 && it.bearing == 0f } &&
                        listOf(nv.oa1_1, nv.oa2_1, nv.oa1_2, nv.oa2_2).all { it.range == 0 && it.bearing == 0f } && m1.popUpNav.vip.range == 0,
                        "a new mission: the page's nav offsets all zero, Modesel none, the attack pages' hand-over gone — the Map page lays out no VIP/VRP " +
                            "(Modesel ${nv?.modesel}, VRP ${nv?.vrp?.stpt}/${nv?.vrp?.range}, OA1 ${nv?.oa1_1?.stpt}/${nv?.oa1_1?.range}, hand-over ${m1?.popUpNav?.vip?.range})")
                    ok(Leftovers.navClearEdits(w.cartridgeText()).isEmpty() && w.unsaved == 0,
                        "…and the cartridge as the page would save it holds none, with nothing to save (the PC clears the file itself)")
                } finally {
                    WdpDtcFixture.file = fileWas
                    fx.delete()
                }
            }
        } catch (e: Throwable) {
            ok(false, "part 11 threw: $e")
        } finally {
            runCatching { Repo.putString("mission_epoch", null) }
            runCatching { MissionTabRequest.take(); Repo.putString("mission_tab", tabWas) }
        }
    }
}
