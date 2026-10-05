package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.wdp.WdpValues
import com.bmscompanion.app.ui.screens.wdp.DataCardWiring
import com.bmscompanion.app.ui.screens.wdp.HadbWiring
import com.bmscompanion.app.ui.screens.wdp.PopupWiring
import com.bmscompanion.app.ui.screens.wdp.TossWiring
import com.bmscompanion.app.ui.screens.wdp.WdpFormView
import com.bmscompanion.app.ui.screens.wdp.WdpMission
import com.bmscompanion.app.ui.screens.wdp.WdpPage
import com.bmscompanion.app.ui.screens.wdp.WdpWiring
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--wdprender <out folder> [briefing.txt] [cartridge.ini]` — every Planner page drawn to a PNG, headless.
 *
 * Each page is drawn as the designer left it, then — given a briefing and a cartridge (read, never written) — with
 * the mission loaded and each attack page on its default TGT STPT, as PRINT in BMS would leave it (with
 * `BMSC_WDP_ROUTE` and `BMSC_WDP_SAVEFLIGHT`, [WdpFixtureMission], BMS's route and a save's flight), then after the clicks a pilot
 * makes first on each wired page. A report beside the pictures lists, per page, how many controls the wiring
 * fills and anything that threw. It is the check that the pages *draw*: that a wiring's values reach the layout,
 * that a stacked panel flips, that nothing on the page is left blank that should not be.
 *
 * Given a briefing, it also checks the DataCard ↔ Performance hand-off ([WdpHandOff]: the card's take-off figures
 * equal the page's, the page's cruise altitude and weather are the card's, a take-off spec chosen on the card reaches
 * the page, and draws the whole Planner — header, page tabs, page; there is no target bar — for every page on a phone
 * (400 x 860 dp), a tablet (840 x 1180 dp), and in PC windows of 400 x 800, 840 x 1000, 1280 x 800, 1920 x 1080 and
 * 2560 x 1440 pixels: `planner-<size>-<page>.png`, each page fitted whole (never a scroll) and larger the larger the
 * window. A few child windows are drawn over their page at every size too (`planner-<size>-win-<window>.png`), at
 * the page's scale. The report gives the scale each page was drawn at. BMSC_WDP_ONLY=widths draws only those.
 */
object WdpRender {
    fun run(out: File, briefing: File?, cartridge: File?): String = buildString {
        out.mkdirs()
        val data = WdpFixtureMission.data(briefing, cartridge)
        // BMSC_WDP_THEATER names the theater (as BMS reports it, e.g. "Korea KTO"), for the attack pages' map and
        // lat/lon: a briefing and a cartridge do not say which theater they are from
        val theater = System.getenv("BMSC_WDP_THEATER")?.let { name ->
            runBlocking { com.bmscompanion.app.ui.screens.wdp.plannerTheater(Repo.index().theaters, name) }
        }
        // BMSC_WDP_ONLY=<page> (e.g. performance) draws that page's pictures and no others: a quick look at one page
        val only = System.getenv("BMSC_WDP_ONLY")
        val mission = WdpFixtureMission.mission(data, theater)
        val base = mission
        appendLine("Weapon Delivery Planner pages, headless")
        appendLine("briefing: ${if (data.briefing != null) "read" else "none"}, cartridge: ${if (data.dtc != null) "read (${data.dtc!!.steerpoints.size} steerpoints)" else "none"}, BMS route: ${if (data.route != null) "read" else "none"}")
        appendLine("theater: ${theater?.name ?: "none (set BMSC_WDP_THEATER)"}")
        appendLine(WdpFixtureMission.describe(mission))
        appendLine("targets in the mission (DTC page's Select target): ${mission.choices.joinToString { it.label }.ifEmpty { "none" }}")
        appendLine()

        val toss = TossWiring()
        val popup = PopupWiring()
        val dataCard = DataCardWiring()
        val hadb = HadbWiring()
        // the card prints the chosen attack page's offsets and figures and shows its map, as the Planner wires it
        dataCard.attackPages = { Triple(popup.plan, hadb.plan, toss.plan) }
        // the Performance page as on WDP's first run (no Setup.ini), whatever an earlier check left in the settings;
        // they are put back at the end
        val performanceIni = Repo.getString(com.bmscompanion.app.ui.screens.wdp.PerformanceWiring.INI_KEY)
        Repo.putString(com.bmscompanion.app.ui.screens.wdp.PerformanceWiring.INI_KEY, null)
        val performance = com.bmscompanion.app.ui.screens.wdp.PerformanceWiring().also { runBlocking { it.prepare(mission) } }
        WdpDtcFixture.file = cartridge
        val dtc = com.bmscompanion.app.ui.screens.wdp.DtcWiring(WdpDtcFixture.source())
        fun wiringOf(p: WdpPage): WdpWiring? = when (p) {
            WdpPage.DTC -> dtc
            WdpPage.TOSS -> toss
            WdpPage.POPUP -> popup
            WdpPage.HADB -> hadb
            WdpPage.PERFORMANCE -> performance
            WdpPage.BRIEFING, WdpPage.DATACARD, WdpPage.COORDINATION -> dataCard
            else -> null
        }

        fun shot(page: WdpPage, name: String) {
            if (only != null && !page.name.equals(only, true)) return
            val form = runBlocking { Repo.wdpForm(page.form) }
            if (form == null) { appendLine("FAIL ${page.label}: no layout"); return }
            val w = wiringOf(page)
            val values = try {
                w?.values(page.hidden) ?: WdpValues(page.hidden.associateWith { "hidden" })
            } catch (e: Exception) {
                appendLine("FAIL ${page.label} ($name): values() threw ${e::class.simpleName}: ${e.message}")
                return
            }
            val filled = values.values.count { (k, v) -> !k.contains('.') && v.isNotBlank() && v != "hidden" && v != "shown" }
            val scene = ImageComposeScene(1400, 1500, Density(1f)) {
                // the page, and over it whatever child window it has open, as the Planner draws them
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().background(Color(0xFF15181C))) {
                        WdpFormView(form, values, Modifier.fillMaxWidth().padding(8.dp), fitWidth = true, onValue = w?.let { it::onValue }, onClick = w?.let { it::onClick },
                            content = (w as? com.bmscompanion.app.ui.screens.wdp.WdpControlContent)?.controlContent() ?: emptyMap())
                    }
                    com.bmscompanion.app.ui.screens.wdp.WdpDialogHost()
                }
            }
            var t = 0L
            repeat(30) { scene.render(t); t += 50_000_000; Thread.sleep(40) }   // pictures load asynchronously
            File(out, "wdp-$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
            appendLine("ok   ${page.label} ($name): ${values.values.size} values, $filled filled")
        }

        // WDP's own pages; the app's own (the Map page) has no designer layout and is drawn in the shell shots below
        for (p in WdpPage.entries.filter { !it.native }) shot(p, "${p.name.lowercase()}-designer")
        if (data.briefing != null || data.dtc != null) {
            for (w in listOf(toss, popup, hadb, dataCard, dtc)) {
                try { WdpWirings.feed(w, mission, cartridge) } catch (e: Exception) { appendLine("FAIL onMission threw in ${w::class.simpleName}: ${e.message}") }
            }
            // SetCoordData, as the Planner gives it to the Pop-up and HADB pages when the theater is known
            base.coords?.let { c ->
                popup.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
                hadb.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
            }
            // each attack page on its own TGT STPT (A3): the target that steerpoint, the IP the one before
            for ((w, p) in listOf(toss as WdpWiring to WdpPage.TOSS, popup to WdpPage.POPUP, hadb to WdpPage.HADB)) {
                val plot = com.bmscompanion.app.ui.screens.wdp.WdpAttackOverlay.of(p, w)
                val n = w.values(p.hidden)["numWaypoint"]?.trim()?.toIntOrNull()
                val ip = w.values(p.hidden)["numIPpoint"]?.trim()?.toIntOrNull()
                fun at(k: Int?) = k?.let { mission.steerpoint(it) }?.takeIf { it.x != 0.0 || it.y != 0.0 }?.let { "%.0f, %.0f (%s)".format(it.x, it.y, mission.source(it.n)?.label) } ?: "none"
                appendLine("ok   ${p.label}: TGT STPT $n — target STPT $n at ${at(n)}, IP STPT $ip at ${at(ip)}; " +
                    "attack cues ${plot?.cues?.joinToString { it.label } ?: "none"}")
            }
            for (p in WdpPage.entries.filter { it.wired && !it.native }) shot(p, "${p.name.lowercase()}-mission")
        }
        // the DTC page, one tab at a time, and one of its child windows open over it
        if (cartridge != null) {
            for (t in listOf("tabMain", "tabSTPT", "tabTargets", "tabLines", "tabThreats", "tabPPTman", "tabIFF", "tabOpen", "tabHarpoon",
                "tabEWS", "tabMFD", "tabRadio", "tabNavOffsets", "tabSystems", "tabWeapons", "tabHarm")) {
                dtc.onValue("tabDTC", t); shot(WdpPage.DTC, "dtc-" + t.removePrefix("tab").lowercase())
            }
            dtc.onValue("tabDTC", "tabSTPT"); dtc.onClick("btnChange_2"); shot(WdpPage.DTC, "dtc-change-stpt")
            com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
            dtc.onValue("tabDTC", "tabRadio"); dtc.onClick("btnUHF_3"); shot(WdpPage.DTC, "dtc-change-comm")
            com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
            dtc.onValue("tabDTC", "tabThreats"); dtc.onClick("btnPPT_1"); shot(WdpPage.DTC, "dtc-change-ppt")
            com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
            // a double tap on a target asks which of WDP's two windows; answered "Select target" here
            dtc.onValue("tabDTC", "tabTargets"); dtc.onClick("dgvTargets:open:0")
            (com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.lastOrNull() as? com.bmscompanion.app.ui.screens.wdp.WdpMessage)?.let { m ->
                com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.remove(m); m.onAnswer?.invoke("Select target")
            }
            (com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.lastOrNull() as? com.bmscompanion.app.ui.screens.wdp.WdpDialog)?.wiring?.onClick("dgvObjectives:row:1")
            shot(WdpPage.DTC, "dtc-target-selection")
            com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
            // the same question answered "Change": the typed-in window
            dtc.onClick("dgvTargets:open:1")
            (com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.lastOrNull() as? com.bmscompanion.app.ui.screens.wdp.WdpMessage)?.let { m ->
                com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.remove(m); m.onAnswer?.invoke("Change")
            }
            shot(WdpPage.DTC, "dtc-change-target")
            // every other window the DTC page opens, one at a time over the tab its button is on
            for ((t, press) in listOf(
                "tabSTPT" to "btnStptInfo", "tabOpen" to "btnOpenChange_1", "tabHarpoon" to "btnHpnChange_1",
                "tabLines" to "btnChangeLine31", "tabLines" to "btnChangeArea", "tabRadio" to "btnVHF_2",
                "tabRadio" to "btnSelect", "tabRadio" to "lblN_CoordsRwy1", "tabRadio" to "lblCntNorthRwy1",
            )) {
                com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
                dtc.onValue("tabDTC", t); dtc.onClick(press)
                Thread.sleep(400)   // Select APT reads the theater's airports before its window opens
                shot(WdpPage.DTC, "dtc-" + press.lowercase())
            }
            com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
        }
        // the card's child windows, each open over its page
        if (data.briefing != null) {
            for ((pg, press) in listOf(
                WdpPage.DATACARD to "btnDepChart", WdpPage.DATACARD to "btnViewMilCodes",
                WdpPage.DATACARD to "rbnCallsign1", WdpPage.DATACARD to "btnSelDiffFlight", WdpPage.DATACARD to "lblDepName",
                WdpPage.DATACARD to "btnAirportSchedule", WdpPage.DATACARD to "txtFormation2", WdpPage.DATACARD to "lblLead",
                WdpPage.DATACARD to "btnSaveDataCard", WdpPage.COORDINATION to "btnLoadCodewords", WdpPage.DATACARD to "btnTimer",
            )) {
                com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
                dataCard.onClick(press)
                shot(pg, "card-" + press.lowercase())
                // Upd Kneeboard opens the Planner's own window, which the Planner's shots further on must not find open
                com.bmscompanion.app.ui.screens.wdp.PlannerWindows.close()
            }
            com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
        }
        // the first clicks: TOSS's right-hand knob through its three panels, and back
        toss.onClick("pnlSelections_Up"); shot(WdpPage.TOSS, "toss-profile")
        toss.onClick("pnlSelections_Middle"); shot(WdpPage.TOSS, "toss-ded")
        toss.onClick("pnlSelections_Down")
        toss.onClick("lblE"); shot(WdpPage.TOSS, "toss-heading-east")
        popup.onClick("pnlSelections_Up"); shot(WdpPage.POPUP, "popup-profile")
        popup.onClick("pnlSelections_Middle"); shot(WdpPage.POPUP, "popup-ded")
        popup.onClick("pnlSelections_Down")
        val back = popup.values(WdpPage.POPUP.hidden)["pnlSelections"]
        appendLine((if (back == "shown" || back == null) "ok   " else "FAIL ") + "Pop-up: a tap on DED Data wraps round to Selections (pnlSelections=$back)")
        popup.onClick("lblShowSelections")
        val ded = popup.values(WdpPage.POPUP.hidden)["pnlDEDData"]
        appendLine((if (ded == "shown") "ok   " else "FAIL ") + "Pop-up: its DED Data caption turns the knob straight there (pnlDEDData=$ded)")
        // HADB: the same knob and captions as the Pop-up page, and its map on the Profile panel
        hadb.onClick("pnlSelections_Up"); shot(WdpPage.HADB, "hadb-profile")
        hadb.onClick("pnlSelections_Middle"); shot(WdpPage.HADB, "hadb-ded")
        hadb.onClick("pnlSelections_Down")
        val hBack = hadb.values(WdpPage.HADB.hidden)["pnlSelections"]
        appendLine((if (hBack == "shown" || hBack == null) "ok   " else "FAIL ") + "HADB: a tap on DED Data wraps round to Selections (pnlSelections=$hBack)")
        hadb.onClick("lblShowSelections")
        val hDed = hadb.values(WdpPage.HADB.hidden)["pnlDEDData"]
        appendLine((if (hDed == "shown") "ok   " else "FAIL ") + "HADB: its DED Data caption turns the knob straight there (pnlDEDData=$hDed)")
        hadb.onClick("lblShowProfile")
        val hProf = hadb.values(WdpPage.HADB.hidden)["pnlProfile"]
        appendLine((if (hProf == "shown") "ok   " else "FAIL ") + "HADB: its Profile caption turns the knob straight there (pnlProfile=$hProf)")
        hadb.onClick("lblHideSelections")
        val hSel = hadb.values(WdpPage.HADB.hidden)["pnlSelections"]
        appendLine((if (hSel == "shown" || hSel == null) "ok   " else "FAIL ") + "HADB: its Selections caption turns the knob straight there (pnlSelections=$hSel)")
        // the maps zoomed in, with the threats numbered
        popup.onClick("lblShowProfile"); popup.onValue("trbZoom", "0"); popup.onClick("chbShowPPTNr"); shot(WdpPage.POPUP, "popup-zoomed")
        toss.onClick("lblProfileSelect"); toss.onValue("trbZoom", "0"); toss.onClick("btnPPTnr"); shot(WdpPage.TOSS, "toss-zoomed")
        hadb.onClick("lblShowProfile"); hadb.onValue("trbZoom", "0"); hadb.onClick("btnPPTnr"); shot(WdpPage.HADB, "hadb-zoomed")
        // the attack pages' buttons that answer in a message box, and the DataCard's map with each profile, then as WDP's white map
        popup.onClick("btnSaveMap"); shot(WdpPage.POPUP, "popup-savemap"); com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
        popup.onClick("btnSaveDTC"); shot(WdpPage.POPUP, "popup-savedtc"); com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
        if (data.briefing != null) {
            dataCard.onClick("btnPrint"); shot(WdpPage.DATACARD, "card-btnprint"); com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
            for (b in listOf("btnPopUp", "btnHADB", "btnTOSS")) {
                dataCard.onClick("txtWpn_AttackType"); dataCard.onClick(b); shot(WdpPage.DATACARD, "card-map-" + b.removePrefix("btn").lowercase())
            }
            dataCard.onClick("txtWpn_AttackType"); dataCard.onClick("btnNone"); dataCard.onClick("picMap"); shot(WdpPage.DATACARD, "card-map-white")
            dataCard.onClick("picMap")
            com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
            // the Coordination Card's plan pictures: the question a click asks, then two of them filled from the attack pages
            dataCard.onClick("picIngressPlan"); shot(WdpPage.COORDINATION, "card-planpicture-ask")
            for ((pic, answer) in listOf("picIngressPlan" to "Pop-up", "picTargetPlan" to "TOSS")) {
                com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
                dataCard.onClick(pic)
                (com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.lastOrNull() as? com.bmscompanion.app.ui.screens.wdp.WdpMessage)?.let { m ->
                    com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.remove(m); m.onAnswer?.invoke(answer)
                }
            }
            shot(WdpPage.COORDINATION, "card-planpictures")
            com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
        }
        for (b in listOf("btnLoadout", "btnSelect", "btnChart")) { performance.onClick(b); shot(WdpPage.PERFORMANCE, "performance-" + b.removePrefix("btn").lowercase()); com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear() }
        // the chart window on its first parking chart, and the Loadout window on the wingman
        performance.onClick("btnChart")
        (com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.lastOrNull() as? com.bmscompanion.app.ui.screens.wdp.WdpDialog)?.wiring?.onClick("btnApc1")
        shot(WdpPage.PERFORMANCE, "performance-chart-apc1")
        com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
        performance.onClick("btnLoadout")
        (com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.lastOrNull() as? com.bmscompanion.app.ui.screens.wdp.WdpDialog)?.wiring?.onClick("rbnWingman")
        shot(WdpPage.PERFORMANCE, "performance-loadout-wingman")
        com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
        if (data.briefing != null) handOff(this, dataCard, performance, mission) { shot(WdpPage.DATACARD, "card-handoff") }
        if (only == null || only.equals("widths", true)) widths(this, out, data, theater, mission, cartridge)
        Repo.putString(com.bmscompanion.app.ui.screens.wdp.PerformanceWiring.INI_KEY, performanceIni)
    }

    /**
     * The DataCard ↔ Performance hand-off, as the Planner joins the two pages: the card's take-off figures must be
     * the page's, the page's cruise altitude row 2's, its wind and temperature the card's take-off weather; then a
     * take-off spec chosen on the card (MIL) must reach the page and come back as the card's figures.
     */
    private fun handOff(r: StringBuilder, card: DataCardWiring, perf: com.bmscompanion.app.ui.screens.wdp.PerformanceWiring, mission: WdpMission, shot: () -> Unit) {
        val hand = com.bmscompanion.app.ui.screens.wdp.WdpHandOff
        hand.join(card, perf)
        hand.cardToPerformance(card, perf, mission.briefing)
        fun check(what: String, a: String, b: String) =
            r.appendLine((if (a.trim() == b.trim() && a.isNotBlank()) "ok   " else "FAIL ") + "hand-off: $what: '${a.trim()}' / '${b.trim()}'")
        fun figures(tag: String) {
            for ((c, p) in listOf("lblGrossWgt" to "lblGross_Val", "lblDrag" to "lblDrag_Val", "lblRotation" to "lblRotate_Val",
                "lblRefusal" to "lblRefusal_Val", "lblMilP" to "lblMilP_Val")) check("$tag card $c = Performance $p", card.plan.text(c), perf.plan.labels[p].orEmpty())
            check("$tag card lblMilClimb = Performance lblScheduleMil_Val (no spaces)", card.plan.text("lblMilClimb"), perf.plan.labels["lblScheduleMil_Val"].orEmpty().replace(" ", ""))
            check("$tag card lblTOFuel = Performance take-off fuel", card.plan.text("lblTOFuel"), perf.plan.intTakeoffFuel.toString())
        }
        figures("mission:")
        card.plan.text("lblAlt2").trim().toIntOrNull()?.let { check("Performance cruise altitude = card row 2 altitude", perf.plan.txtCruiseAlt.text, it.toString()) }
        com.bmscompanion.app.ui.screens.wdp.CardWeather.column(mission.briefing, 0)?.let { w ->
            w.windDir?.let { check("Performance wind direction = card take-off wind", perf.plan.txtWindDir.text, it.toString()) }
            w.windKts?.let { check("Performance wind speed = card take-off wind", perf.plan.txtWindSpd.text, it.toString()) }
            w.tempC?.let { check("Performance temperature = card take-off temperature", perf.plan.txtTemp.text, it.toString()) }
            r.appendLine("note hand-off: the briefing's QNH " + (w.qnhHpa?.let { "$it hPa -> Performance ${perf.plan.txtQNH_Hpa.text}" } ?: "is not printed: Performance keeps ${perf.plan.txtQNH_Hpa.text}"))
        }
        if (perf.plan.airport?.name.equals(card.plan.tblApt[0].name, true))
            check("Performance runway = card departure runway", perf.plan.cboRWY.selectedItem.orEmpty(), card.plan.tblApt[0].rwy.orEmpty())
        else r.appendLine("note hand-off: the pages show different fields (card ${card.plan.tblApt[0].name}, Performance ${perf.plan.airport?.name}): runway not handed")
        // a take-off spec chosen on the card: 10°, MIL
        val rotateAb = perf.plan.labels["lblRotate_Val"].orEmpty()
        card.onClick("lblTOSpec"); card.onValue("numPitch", "10"); card.onValue("cboAB", "MIL"); card.onClick("btnToSpecSel")
        hand.afterCard("btnToSpecSel", card, perf, mission.briefing)
        check("card take-off spec MIL reaches Performance power", perf.plan.cboPower.text, "MIL")
        check("card take-off spec 10° reaches Performance pitch", perf.plan.cboPitch.selectedItem.orEmpty(), "10")
        check("card take-off spec comes back as WDP prints it", card.plan.text("lblTOSpec"), "10°/ Mil")
        figures("after MIL:")
        r.appendLine("note hand-off: rotation at Full AB $rotateAb kt, at MIL ${perf.plan.labels["lblRotate_Val"]} kt")
        shot()
        // back to the page's own setting, so the settings the check leaves behind are WDP's first-run ones
        card.onClick("lblTOSpec"); card.onValue("numPitch", "13"); card.onValue("cboAB", "Full AB"); card.onClick("btnToSpecSel")
        hand.afterCard("btnToSpecSel", card, perf, mission.briefing)
    }

    /** The Planner, header and all, for every page at a phone's, a tablet's and a PC window's width. */
    private fun widths(r: StringBuilder, out: File, data: MissionData, theater: com.bmscompanion.app.data.Theater?, mission: WdpMission, cartridge: File?) {
        val s = com.bmscompanion.app.ui.screens.wdp.WdpSession
        // the Planner's own pages, given the mission as the tab gives it; the DTC page reads the check's copy of the
        // cartridge, never the PC's
        s.dtcSource = WdpDtcFixture.source()
        s.theaterName = theater?.name; s.theater = theater
        if (data.briefing != null || data.dtc != null) {
            for (w in listOf(s.toss, s.popup, s.hadb)) w.onMission(mission)
            runBlocking { s.dataCard.prepare(mission) { cartridge?.takeIf { it.isFile }?.readText() } }
            s.dtc.onMission(mission)
            runBlocking { s.performance.prepare(mission) }
            com.bmscompanion.app.ui.screens.wdp.WdpHandOff.cardToPerformance(s.dataCard, s.performance, mission.briefing)
            s.appliedMission = mission
            Thread.sleep(1500)   // the DTC page reads its cartridge on its own scope
        }
        val probe = com.bmscompanion.app.ui.screens.wdp.WdpProbe
        probe.on = true
        val sizes = listOf(
            Triple("phone", 400 to 860, 2.625f), Triple("tablet", 840 to 1180, 2f),
            Triple("w400x800", 400 to 800, 1f), Triple("w840x1000", 840 to 1000, 1f), Triple("w1280x800", 1280 to 800, 1f),
            Triple("w1920x1080", 1920 to 1080, 1f), Triple("w2560x1440", 2560 to 1440, 1f),
            // a phone held sideways (R3-PLAN U2)
            Triple("w860x393", 860 to 393, 2f),
        )
        // the child windows drawn over their page at every size: a small one, a middling one, one larger than the page
        val windows: List<Triple<WdpPage, String, () -> Unit>> = listOf(
            Triple(WdpPage.DTC, "fclsChangeSTPT") { s.dtc.onValue("tabDTC", "tabSTPT"); s.dtc.onClick("btnChange_2") },
            Triple(WdpPage.DATACARD, "fclsPackageNr") { s.dataCard.onClick("btnSaveDataCard") },
            Triple(WdpPage.PERFORMANCE, "fclsLoadout") { s.performance.onClick("btnLoadout") },
            Triple(WdpPage.DTC, "message") { com.bmscompanion.app.ui.screens.wdp.WdpDialogs.message("Caution", "Callsign.ini: VIPER 21 not found\n\nThe PC has no cartridge for this pilot; the page keeps the one it had.") },
        )
        for ((label, size, density) in sizes) {
            val shots = WdpPage.entries.map { p -> Triple(p, p.name.lowercase(), null as (() -> Unit)?) } +
                windows.map { (p, n, open) -> Triple(p, "win-" + n.removePrefix("fcls").lowercase(), open) }
            for ((p, shotName, open) in shots) {
                s.page = p.name
                com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
                if (open != null) runCatching { open() }
                if (p == WdpPage.DTC && open == null) s.dtc.onValue("tabDTC", "tabMain")
                probe.scales.clear()
                val scene = ImageComposeScene((size.first * density).toInt(), (size.second * density).toInt(), Density(density)) {
                    com.bmscompanion.app.ui.screens.wdp.WdpPlanner(data, theater?.name, Modifier.fillMaxSize())
                }
                try {
                    var t = 0L
                    repeat(30) { scene.render(t); t += 50_000_000; Thread.sleep(40) }
                    File(out, "planner-$label-$shotName.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    val pageScale = probe.scales[p.form]
                    val winScale = probe.scales.filterKeys { it != p.form }.values.firstOrNull()
                    r.appendLine("ok   Planner at $label (${size.first} x ${size.second} dp at ${density}): ${if (open != null) shotName else p.label}, page drawn at " +
                        (pageScale?.let { "%.3f".format(it / density) } ?: "?") + " dp a designer pixel" +
                        (winScale?.let { ", window at %.3f".format(it / density) } ?: ""))
                } catch (e: Exception) {
                    r.appendLine("FAIL Planner at $label width: ${p.label} threw ${e::class.simpleName}: ${e.message}")
                    // where in the layout, so a phone-width failure can be found without a debugger
                    e.stackTrace.filter { it.className.startsWith("com.bmscompanion") }.take(8).forEach { r.appendLine("       at $it") }
                    e.stackTrace.take(6).forEach { r.appendLine("       at $it") }
                } finally { scene.close() }
                com.bmscompanion.app.ui.screens.wdp.WdpDialogs.stack.clear()
            }
        }
        probe.on = false
        // A10: the attack pages worked out their attacks above, and none reached the Mission map or the PC by itself
        val shown = com.bmscompanion.app.ui.screens.wdp.WdpAttackOverlay.shown
        r.appendLine(if (shown == null) "ok   no attack published by itself (Populate from Planner sends it; the page's own map keeps its preview)"
            else "FAIL an attack reached the Mission map by itself: ${shown.page}")
        s.dtc.onValue("tabDTC", "tabMain")
        s.page = WdpPage.DATACARD.name
    }
}
