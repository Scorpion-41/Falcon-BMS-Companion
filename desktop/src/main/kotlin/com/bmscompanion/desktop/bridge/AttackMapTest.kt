package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.AttackCue
import com.bmscompanion.app.data.mission.AttackDrawing
import com.bmscompanion.app.data.mission.AttackOverlay
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.PlanMerge
import com.bmscompanion.app.data.wdp.AttackGeometry
import com.bmscompanion.app.ui.screens.wdp.AttackFocus
import com.bmscompanion.app.ui.screens.wdp.AttackSelection
import com.bmscompanion.app.ui.screens.wdp.DtcSource
import com.bmscompanion.app.ui.screens.wdp.KneeboardExtraPages
import com.bmscompanion.app.ui.screens.wdp.PlannerMissionState
import com.bmscompanion.app.ui.screens.wdp.WdpAttackOverlay
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpMessage
import com.bmscompanion.app.ui.screens.wdp.WdpMission
import com.bmscompanion.app.ui.screens.wdp.WdpPage
import com.bmscompanion.app.ui.screens.wdp.WdpSession
import com.bmscompanion.app.ui.screens.wdp.WdpWiring
import com.bmscompanion.app.ui.screens.wdp.mapData
import com.bmscompanion.app.ui.screens.wdp.plannerMission
import com.bmscompanion.app.ui.screens.wdp.plannerTheater
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.hypot

/**
 * `--attackmaptest <a copy of the BMS folder> out.txt` — **one attack, drawn one way, on every map** (1.3.8; the
 * attack study's SPEC c-f), on the copy's briefed flight as Open mission… opens it, with the pilot's cartridge held in
 * memory ([WdpDtcFixture]: never written; DevGuard is on as for every check).
 *
 * For Pop-up, HADB and TOSS in each reference they have (HADB VRP only):
 *
 * - a change of the page's inputs makes it the current attack ([AttackFocus]); the attack page's own map, the
 *   DataCard's (after the page's Save to DTC filled it), the Upd Kneeboard attack page, the Planner's Map page and what
 *   Populate sends are the **same model** — every point to a foot, the path in the same order — one attack only, no IP
 *   cue, no VIP in VRP mode or on HADB, OA2 left out where it is on OA1;
 * - the page's **Save to DTC** writes the cartridge and then fills the DataCard's Delivery block (profile and fields,
 *   nothing left waiting; there is no Send to DataCard), and the card's **Save DTC** after it writes byte-identical
 *   `[NAV OFFSETS]`, the other reference's lines cleared (D88), and the cartridge as saved then lays out the same attack
 *   (150 ft) with only the mode's OA pair; a save that fails leaves the card as it was, and says so;
 * - EZBoards mode's cartridge attack ([PlanMerge.cartridgeAttack]) is that same attack.
 *
 * Then **IP STPT at the VRP** on Pop-up and TOSS: one question with both points (finish the Input Panel first; how to
 * delete it on DTC → STPT) and each slot's overwrite words; Cancel changes nothing; Create places it, makes VIP-TO-TGT
 * the reverse of TGT-TO-VRP (0.1°, 2 ft) and puts the page's "created" line up; an Input Panel change turns it into the
 * stale warning, pressing again moves the same steerpoint and the warning goes; Save to DTC writes it with
 * `Modesel=vip`, `VIP=n` and `OA1-n`; another IP STPT picked silences the line.
 */
internal object AttackMapTest {
    private class Recording(private val inner: DtcSource) : DtcSource by inner {
        @Volatile var saves = 0
        @Volatile var text: String? = null
        /** The PC "cannot be reached": a save answers nothing and writes nothing. */
        @Volatile var refuse = false
        override suspend fun save(callsign: String?, edits: List<CartridgeEdit>): CartridgeState? {
            if (refuse) return null
            val st = inner.save(callsign, edits)
            text = st?.text ?: text
            saves++
            return st
        }
    }

    fun run(root: File): String = buildString {
        var fails = 0
        fun check(what: String, ok: Boolean, detail: String = "") {
            if (!ok) fails++
            appendLine((if (ok) "ok    " else "FAIL  ") + what + if (detail.isNotEmpty()) " — $detail" else "")
        }

        // ---------------------------------------------------------------- the mission, as the Planner opens it
        val set = Theaters.at(root)
        val bf = Theaters.resolveFile(root, "User\\Briefings\\briefing.txt")
        val printed = bf?.let { f -> runCatching { BriefingParser.parse(f.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")) }.getOrNull() }
        val ctx = CampaignFiles.Context(set, null, printed, bf?.lastModified() ?: 0L)
        val listing = CampaignFiles.list(ctx, all = false)
        val briefed = listing.theaters.flatMap { th -> th.files.filter { it.briefed }.map { th to it } }.firstOrNull()
        val cartridge = File(root, "User/Config").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".ini", true) && runCatching { it.readText(Charsets.ISO_8859_1).contains("[STPT]") }.getOrDefault(false) }
            .maxByOrNull { it.lastModified() }
        if (briefed == null || cartridge == null || printed == null) {
            check("a briefing, the save holding its flight and a cartridge", false)
            return@buildString
        }
        val (th, file) = briefed
        val ato = (CampaignFiles.ato(ctx, th.name, file.name) as? CampaignFiles.Answer.Ok)?.value
        val row = ato?.packages?.flatMap { it.flights }?.firstOrNull { it.briefed }
        val flight: CampFlight? = row?.let { (CampaignFiles.flight(ctx, th.name, file.name, it.id) as? CampaignFiles.Answer.Ok)?.value }
        check("the briefed flight read from the save", flight != null, row?.let { "${it.callsign} #${it.number}" } ?: "no row")
        if (flight == null) return@buildString
        val theater = runBlocking { plannerTheater(Repo.index().theaters, th.name) }
        check("the theater is known", theater != null, th.name)
        if (theater == null) return@buildString
        val data = MissionData(briefing = printed, dtc = DtcParser.parse(cartridge))
        PlannerMissionState.source = PlannerMissionState.SAVE
        PlannerMissionState.flight = flight
        PlannerMissionState.theater = th.name
        PlannerMissionState.ref = null
        PlannerMissionState.seat = 0
        // the flight picker's default answer to WDP's Precision question (D45): the save's waypoints only — so a
        // steerpoint placed on the DTC page wins over them only by the "placed this session" rule
        val mission = plannerMission(data, theater, flight).copy(precision = false)
        WdpDtcFixture.file = cartridge
        val src = Recording(WdpDtcFixture.source())
        WdpSession.dtcSource = src
        WdpSession.started = true
        WdpSession.theater = theater
        val reference = runBlocking { Repo.threats() }
        val airports = runBlocking { Repo.airportSet(theater.airportSet) }
        appendLine("Attack maps on ${flight.row.callsign} (${th.name}, ${file.name}), ${flight.route.size} waypoints, ${mission.sourceLine}")

        // ---------------------------------------------------------------- the Planner's pages, as WdpPlanner joins them
        val dtc = WdpSession.dtc
        val popup = WdpSession.popup
        val hadb = WdpSession.hadb
        val toss = WdpSession.toss
        val card = WdpSession.dataCard
        edt {
            WdpDialogs.stack.clear()
            dtc.served = { MissionData(briefing = printed) }
            dtc.onMission(mission)
        }
        runBlocking { dtc.prepareFacts() }
        repeat(200) { if (edt { dtc.model } != null) return@repeat; Thread.sleep(50) }
        check("a cartridge on the DTC page", edt { dtc.model } != null, edt { dtc.fileName } ?: "none")
        edt {
            card.attackPages = { Triple(popup.plan, hadb.plan, toss.plan) }
            mission.coords?.let { c ->
                popup.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
                hadb.coordData(c.originLat, c.originLong, c.campW, c.campH, c.enableNewTerrain, c.tm)
            }
            popup.onMission(mission); hadb.onMission(mission); toss.onMission(mission)
        }
        runBlocking { card.prepare(mission) { cartridge.readText(Charsets.ISO_8859_1) } }
        AttackSelection.card = { card }
        WdpSession.appliedMission = mission
        WdpSession.appliedAttackMission = mission
        // BMS's height map under the steerpoints arrives later (the PC in-process: WdpGround) and plans the pages again:
        // wait until it has, so no answer moves a page in the middle of a check
        run {
            var last = -1
            var still = 0
            repeat(150) {
                val v = edt { com.bmscompanion.app.ui.screens.wdp.WdpGround.version }
                if (v == last) still++ else { still = 0; last = v }
                if (still >= 20) return@run
                Thread.sleep(100)
            }
        }
        AttackFocus.clear()

        // a target with a placed steerpoint before it, so VIP has an IP: the default first, else the first such
        fun placed(m: WdpMission, n: Int) = m.steerpoint(n)?.let { it.x != 0.0 || it.y != 0.0 } == true
        val tgt = listOfNotNull(mission.defaultStpt).plus(2..24).firstOrNull { placed(mission, it) && placed(mission, it - 1) }
        check("a target steerpoint with an IP before it", tgt != null, "default ${mission.defaultStpt}")
        if (tgt == null) return@buildString
        appendLine("TGT STPT $tgt, IP STPT ${tgt - 1}")

        fun messages(): List<WdpMessage> = edt { WdpDialogs.stack.filterIsInstance<WdpMessage>() }
        fun answer(button: String): Boolean = edt {
            val m = WdpDialogs.stack.filterIsInstance<WdpMessage>().lastOrNull { button in it.buttons } ?: return@edt false
            WdpDialogs.stack.remove(m)
            m.onAnswer?.invoke(button)
            true
        }
        fun clearDialogs() = edt { WdpDialogs.stack.clear() }
        fun waitSave(before: Int): Boolean {
            repeat(200) { if (src.saves > before) return true; Thread.sleep(50) }
            return false
        }
        fun navOf(text: String?): String {
            val lines = text.orEmpty().lines()
            val s = lines.indexOfFirst { it.trim().equals("[NAV OFFSETS]", true) }
            if (s < 0) return ""
            val e = (s + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
            return lines.subList(s, e).joinToString("\n") { it.trimEnd() }
        }
        fun near(a: Pair<Double, Double>, b: Pair<Double, Double>, tol: Double) = hypot(a.first - b.first, a.second - b.second) <= tol
        /** Null when [a] and [b] are the same model to [tol] feet, else what differs. */
        fun differs(a: AttackOverlay?, b: AttackOverlay?, tol: Double): String? {
            if (a == null || b == null) return if (a == null && b == null) null else "one is missing (${a != null} / ${b != null})"
            if (a.mode != b.mode) return "mode ${a.mode} / ${b.mode}"
            if (a.profile.isNotEmpty() && b.profile.isNotEmpty() && a.profile != b.profile) return "profile ${a.profile} / ${b.profile}"
            fun key(c: AttackCue) = c.kind.name + ":" + c.label.substringBefore(' ')
            val ka = a.cues.associateBy(::key)
            val kb = b.cues.associateBy(::key)
            if (ka.keys != kb.keys) return "cues ${ka.keys} / ${kb.keys}"
            for ((k, c) in ka) { val d = kb.getValue(k); if (!near(c.north to c.east, d.north to d.east, tol)) return "$k ${hypot(c.north - d.north, c.east - d.east).toInt()} ft apart" }
            if (a.runIn.size != b.runIn.size) return "path ${a.runIn.size} / ${b.runIn.size} points"
            a.runIn.indices.firstOrNull { !near(a.runIn[it], b.runIn[it], tol) }?.let { return "path point $it" }
            if (a.beyond.size != b.beyond.size) return "leg past the target ${a.beyond.size} / ${b.beyond.size}"
            return null
        }
        /**
         * A cartridge's attack against the page's: the same points as [AttackDrawing.same] takes them (150 ft and the
         * 0.1° bearings), and its path the page's with what a cartridge does not carry left out (the apex and MAP; with
         * no profile known, start → PUP → TGT), in the page's order.
         */
        fun differsCartridge(cart: AttackOverlay?, page: AttackOverlay): String? {
            if (cart == null) return "no attack laid out from the cartridge"
            if (!AttackDrawing.same(cart, page)) return "points: " + cart.cues.joinToString { c ->
                val p = page.cues.firstOrNull { it.kind == c.kind && it.label.substringBefore(' ') == c.label.substringBefore(' ') }
                c.label + (p?.let { " ${hypot(it.north - c.north, it.east - c.east).toInt()} ft" } ?: " (none on the page)")
            }
            var i = 0
            for (pt in cart.runIn) {
                while (i < page.runIn.size && !near(page.runIn[i], pt, 600.0)) i++
                if (i == page.runIn.size) return "path: a cartridge point is not on the page's path in order (${cart.runIn.size} / ${page.runIn.size})"
                i++
            }
            return null
        }
        fun mapNow() = edt { mapData(dtc, dtc.missionFacts(), mission, airports, reference, null) }

        // ---------------------------------------------------------------- each profile in each reference
        data class Case(val page: WdpPage, val vip: Boolean)
        val cases = listOf(Case(WdpPage.POPUP, false), Case(WdpPage.POPUP, true), Case(WdpPage.HADB, false), Case(WdpPage.TOSS, false), Case(WdpPage.TOSS, true))
        // as the Planner joins them (WdpPlanner): the card measures its entries against the cartridge as saved
        val savedHook = edt { dtc.onSaved }
        edt { dtc.onSaved = { t -> card.takeCartridge(t) } }
        for ((page, vip) in cases) {
            appendLine()
            appendLine("== ${page.label} ${if (vip) "VIP" else "VRP"}")
            clearDialogs()
            val w: WdpWiring = when (page) { WdpPage.POPUP -> popup; WdpPage.HADB -> hadb; else -> toss }
            val cardProfile = AttackDrawing.cardOf(page.label)!!
            edt {
                w.onValue("numWaypoint", tgt.toString())
                when (page) {
                    WdpPage.POPUP -> { w.onClick(if (vip) "pnlRefDown" else "pnlRefUp"); if (popup.plan.chbOA2) w.onClick("chbOA2") }
                    WdpPage.TOSS -> if (toss.plan.ref != vip) w.onClick("pnlRefUp")
                    else -> {}
                }
                // a change of the attack's inputs: the attack heading
                w.onValue("trbHeading", if (vip) "200" else "160")
            }
            val pageModel = edt { WdpAttackOverlay.of(page, w) }
            check("the page has an attack in ${if (vip) "VIP" else "VRP"} mode", pageModel?.mode == (if (vip) "VIP" else "VRP"), "${pageModel?.mode}, ${pageModel?.cues?.size} cues")
            if (pageModel == null) continue
            check("a change of its inputs makes it the current attack", AttackFocus.profile == page, "${AttackFocus.profile}")
            check("no IP cue; ${if (vip) "the VIP at the IP STPT" else "no VIP in VRP mode"}",
                pageModel.cues.none { it.kind == AttackCue.Kind.IP } &&
                    (if (vip) pageModel.cues.count { it.kind == AttackCue.Kind.VIP } == 1 && pageModel.cues.none { it.kind == AttackCue.Kind.VRP } && pageModel.refStpt == tgt - 1
                    else pageModel.cues.none { it.kind == AttackCue.Kind.VIP } && pageModel.cues.count { it.kind == AttackCue.Kind.VRP } == 1),
                pageModel.cues.joinToString { it.label })
            check("the caption", AttackDrawing.caption(pageModel).let { c -> c.startsWith(page.label) && "TGT STPT $tgt" in c && (vip == ("VIP from STPT ${tgt - 1}" in c)) },
                AttackDrawing.caption(pageModel))
            if (page == WdpPage.POPUP) check("Pop-up with Place OA2 unchecked: OA2 (on OA1) is left out, OA1 drawn",
                pageModel.cues.any { it.label == "OA1" } && pageModel.cues.none { it.label == "OA2" }, pageModel.cues.joinToString { it.label })
            val path = when (page) {
                WdpPage.POPUP -> 6; WdpPage.HADB -> 4; else -> 5
            }
            check("the path in the profile's order (${pageModel.runIn.size} points)", pageModel.runIn.size == path &&
                near(pageModel.runIn.last(), pageModel.target!!.north to pageModel.target!!.east, 1.0) &&
                pageModel.cues.first { it.kind == AttackCue.Kind.VIP || it.kind == AttackCue.Kind.VRP }.let { s -> near(pageModel.runIn.first(), s.north to s.east, 1.0) },
                "${pageModel.runIn.size} points, want $path, beyond ${pageModel.beyond.size}")
            // the page's own map and the kneeboard's attack page draw this attack
            val own = edt { WdpAttackOverlay.picture(page, false)?.attack }
            check("the attack page's map draws it", differs(own, pageModel, 1.0) == null, differs(own, pageModel, 1.0) ?: "")
            val kbPage = edt { KneeboardExtraPages.attackPage() }
            val kb = kbPage?.let { edt { KneeboardExtraPages.attackPicture(it)?.attack } }
            check("the Upd Kneeboard attack page draws it", kbPage == page && differs(kb, pageModel, 1.0) == null, "page $kbPage; " + (differs(kb, pageModel, 1.0) ?: ""))
            val sent = edt { WdpAttackOverlay.toSend() }
            check("Populate from Planner sends it", differs(sent, pageModel, 1.0) == null, differs(sent, pageModel, 1.0) ?: "")
            val d0 = mapNow()
            check("the Planner's Map page draws one attack, this one", differs(d0.drawn, pageModel, 1.0) == null && d0.drawn?.profile == page.label,
                differs(d0.drawn, pageModel, 1.0) ?: "${d0.drawn?.profile}")
            // the card keeps its own profile and says so while another is the latest
            val noticeBefore = edt { AttackFocus.cardNotice(card.plan.attack.strProfile) }
            val cardWas = edt { card.plan.attack.strProfile }
            check("the DataCard says when the latest attack is not its own", (cardWas == cardProfile) == (noticeBefore == null), "card $cardWas, notice ${noticeBefore ?: "none"}")

            // ---- the page's Save to DTC
            clearDialogs()
            val waitingBefore = edt { card.unsavedEntries }
            val s0 = src.saves
            edt { w.onClick("btnSaveDTC") }
            check("the page's Save to DTC saves the cartridge", waitSave(s0), "${src.saves - s0} saves")
            Thread.sleep(150)
            val pageText = src.text
            val pageNav = navOf(pageText)
            val parsed = pageText?.let { DtcParser.parseText(it) }
            val nav = parsed?.navOffsets
            val ref = if (vip) tgt - 1 else tgt
            check("…Modesel ${if (vip) "vip" else "vrp"}, the other reference's lines cleared, only this mode's OA pair",
                nav?.mode == (if (vip) "vip" else "vrp") &&
                    (if (vip) (nav.vrp?.stpt ?: 0) == 0 && (nav.vrpPup?.stpt ?: 0) == 0 else (nav.vip?.stpt ?: 0) == 0 && (nav.vipPup?.stpt ?: 0) == 0) &&
                    nav.oa.all { it.stpt == ref },
                pageNav.lines().filter { it.startsWith("Modesel") || it.startsWith("VIP") || it.startsWith("VRP") || it.startsWith("OA") }.joinToString(" | "))
            val savedModel = edt { dtc.savedAttack(page.label) }
            check("the cartridge as saved lays out the same attack (150 ft and its 0.1° bearings; its path without the apex and MAP)",
                differsCartridge(savedModel, pageModel) == null, differsCartridge(savedModel, pageModel) ?: "${savedModel?.runIn?.size} path points")
            val d1 = mapNow()
            check("the Map page now captions it as in the cartridge", d1.drawn?.saved == true && d1.attack == null, "saved ${d1.drawn?.saved}, extra ${d1.attack != null}")
            // EZBoards mode lays the cartridge out on BMS's route: here the flight's own waypoints stand in for it
            val route = com.bmscompanion.app.data.mission.MissionRoute(
                file = "", save = file.name, kind = "", modified = 0L,
                steerpoints = flight.route.mapIndexed { i, wp -> com.bmscompanion.app.data.mission.DtcPoint(n = if (wp.n > 0) wp.n else i + 1, x = wp.x, y = wp.y, altFt = wp.altFt, action = wp.action) },
            )
            val ez = parsed?.let { PlanMerge.cartridgeAttack(MissionData(dtc = it, route = route)) }
            check("EZBoards mode's cartridge attack is the same attack (start → PUP → TGT: no profile known there)",
                differsCartridge(ez, pageModel) == null && ez?.runIn?.size == 3, differsCartridge(ez, pageModel) ?: "${ez?.runIn?.size} path points")

            // ---- the same Save to DTC filled the DataCard (1.3.8: no Send to DataCard), then the card's Save DTC: byte for byte the page's
            val layout = runCatching { runBlocking { Repo.wdpForm(page.form) } }.getOrNull()
            check("no Send to DataCard on the page", layout != null && layout.find("btnSendToCard") == null &&
                (page == WdpPage.HADB) == (layout.find(AttackSelection.IP_AT_VRP) == null), "layout ${layout != null}")
            val said = messages().lastOrNull()?.text.orEmpty()
            check("the save's answer says the DataCard holds the attack now", "The DataCard's Delivery section now holds this attack." in said, said)
            check("Save to DTC switches the card's Delivery profile", edt { card.plan.attack.strProfile } == cardProfile &&
                edt { card.plan.text("txtWpn_AttackType") } == cardProfile && AttackFocus.profile == page,
                "card ${edt { card.plan.attack.strProfile }}, txtWpn_AttackType ${edt { card.plan.text("txtWpn_AttackType") }}")
            check("…and fills its Delivery fields", edt { listOf("lblVIPbrg", "lblVIPrng", "lblPUPbrg", "lblAttHed", "lblRelHgt").all { card.plan.text(it).isNotBlank() } } &&
                edt { card.plan.text("lblTGTHUD") } in listOf("Target visible", "TGT NOT visible"),
                edt { listOf("lblVipVrp", "lblVIPbrg", "lblVIPrng", "lblPUPbrg", "lblAttHed", "lblTurn", "lblTGTHUD").joinToString { "$it=${card.plan.text(it).trim()}" } })
            check("…and the card's notice is gone", edt { AttackFocus.cardNotice(card.plan.attack.strProfile) } == null)
            check("…and nothing waits for the cartridge (the card and the cartridge agree)", edt { card.unsavedEntries } <= waitingBefore && edt { dtc.unsaved } == 0,
                "card ${edt { card.unsavedEntries }} (before the save $waitingBefore), DTC page ${edt { dtc.unsaved }}")
            val cardModel = edt { AttackFocus.pageOf(card.plan.attack.strProfile)?.let { WdpAttackOverlay.ofPlan(when (it) { WdpPage.POPUP -> popup.plan; WdpPage.HADB -> hadb.plan; else -> toss.plan }) } }
            check("the DataCard's map draws it", differs(cardModel, pageModel, 1.0) == null, differs(cardModel, pageModel, 1.0) ?: "")
            clearDialogs()
            val s1 = src.saves
            edt { card.onClick("btnSaveDTC") }
            // the page's save already wrote what the card holds: the card's save writes it again or finds nothing to change
            var answered = false
            repeat(200) { if (src.saves > s1 || messages().any { it.title == "Save to DTC" }) { answered = true; return@repeat }; Thread.sleep(50) }
            check("the card's Save DTC saves the cartridge (or finds it already holds the card)", answered,
                "${src.saves - s1} saves; " + messages().joinToString { it.text })
            Thread.sleep(150)
            val cardNav = navOf(edt { dtc.cartridgeText() } ?: src.text)
            check("the card's Save DTC writes byte for byte the page's [NAV OFFSETS]", cardNav == pageNav && cardNav.isNotEmpty(),
                if (cardNav == pageNav) "${cardNav.lines().size} lines" else "page:\n$pageNav\ncard:\n$cardNav")
            clearDialogs()
        }
        edt { dtc.onSaved = savedHook }

        // ---------------------------------------------------------------- a save that fails leaves the card alone
        run {
            appendLine()
            appendLine("== Save to DTC that fails (TOSS, VRP): the DataCard is left as it was")
            clearDialogs()
            edt {
                toss.onValue("numWaypoint", tgt.toString())
                if (toss.plan.ref) toss.onClick("pnlRefUp")
                toss.onValue("trbHeading", "140")
                card.onClick("btnPopUp")
            }
            clearDialogs()
            val before = edt { card.plan.attack.strProfile }
            src.refuse = true
            edt { toss.onClick("btnSaveDTC") }
            var said: String? = null
            repeat(100) { said = messages().lastOrNull { "could not be reached" in it.text }?.text; if (said != null) return@repeat; Thread.sleep(50) }
            src.refuse = false
            check("the save fails and says the DataCard is unchanged", said?.contains("The DataCard is unchanged") == true, said ?: "no answer")
            check("…and the card keeps its profile", edt { card.plan.attack.strProfile } == before && before != "TOSS", "card ${edt { card.plan.attack.strProfile }}, was $before")
            clearDialogs()
        }

        // ---------------------------------------------------------------- the card's saves after the attack page changed
        run {
            appendLine()
            appendLine("== The card's saves after the attack page changed (TOSS, VRP)")
            clearDialogs()
            val hook = edt { dtc.onSaved }
            // as the Planner joins them (WdpPlanner): the card measures its entries against the cartridge as saved
            edt { dtc.onSaved = { t -> card.takeCartridge(t) } }
            fun saved(before: Int): String { waitSave(before); Thread.sleep(150); return navOf(src.text) }
            edt {
                toss.onValue("numWaypoint", tgt.toString())
                if (toss.plan.ref) toss.onClick("pnlRefUp")
                toss.onValue("trbHeading", "150")
            }
            // the page's Save to DTC fills the card (its copy is this save's) …
            var s = src.saves
            edt { toss.onClick("btnSaveDTC") }
            saved(s)
            // … then changed, with the card not on show, and saved again
            edt { toss.onValue("trbHeading", "165") }
            s = src.saves
            edt { toss.onClick("btnSaveDTC") }
            val pageNav = saved(s)
            s = src.saves
            edt { dtc.saveToDtc(card.entriesToHand()) }
            val toolbarNav = saved(s)
            check("the toolbar's Save to DTC after the page's own keeps the page's attack, not the one sent before it changed",
                toolbarNav == pageNav && pageNav.isNotEmpty(), if (toolbarNav == pageNav) "" else "page:\n$pageNav\ntoolbar:\n$toolbarNav")
            // changed again after that save: the card follows its page, so its Save DTC writes it, and counts it until then
            edt { toss.onValue("trbHeading", "180") }
            val waiting = edt { card.unsavedEntries }
            check("…a change after the save: the card counts its attack as not in the cartridge", waiting >= 1, "$waiting waiting")
            s = src.saves
            edt { card.onClick("btnSaveDTC") }
            val cardNav = saved(s)
            s = src.saves
            edt { toss.onClick("btnSaveDTC") }
            val pageNav2 = saved(s)
            check("…the card's Save DTC writes the attack as the page has it now (what the page's own save writes)",
                cardNav == pageNav2 && cardNav != pageNav, if (cardNav != pageNav2) "card:\n$cardNav\npage:\n$pageNav2" else if (cardNav == pageNav) "the same as before the change" else "")
            check("…and then counts nothing", edt { card.unsavedEntries } == 0, "${edt { card.unsavedEntries }}")
            // TOSS changed, then Pop-up made the current attack: the card's TOSS is not handed over it
            edt { toss.onValue("trbHeading", "195"); popup.onValue("trbHeading", "90") }
            check("…another page the current attack: the card hands no attack of its own", AttackFocus.profile == WdpPage.POPUP && edt { card.unsavedEntries } == 0,
                "current ${AttackFocus.profile}, ${edt { card.unsavedEntries }} waiting")
            edt { dtc.onSaved = hook }
            clearDialogs()
        }

        // ---------------------------------------------------------------- IP STPT at the VRP
        for (page in listOf(WdpPage.POPUP, WdpPage.TOSS)) {
            appendLine()
            appendLine("== IP STPT at the VRP on ${page.label}")
            clearDialogs()
            val w: WdpWiring = if (page == WdpPage.POPUP) popup else toss
            fun isVip() = edt { if (page == WdpPage.POPUP) popup.plan.blnRef else toss.plan.ref }
            fun labels() = edt { if (page == WdpPage.POPUP) popup.plan.labels.toMap() else toss.plan.labels.toMap() }
            edt {
                w.onValue("numWaypoint", tgt.toString())
                if (page == WdpPage.POPUP) w.onClick("pnlRefUp") else if (toss.plan.ref) w.onClick("pnlRefUp")
            }
            check("VRP mode", !isVip())
            val v = edt { w.values(page.hidden) }
            check("VRP mode: IP STPT at the VRP shown, the IP STPT box and its lines hidden",
                v[AttackSelection.IP_AT_VRP] != "hidden" && v["${AttackSelection.IP_AT_VRP}.enabled"] == "true" &&
                    v[AttackSelection.IP_BOX] == "hidden" && AttackSelection.IP_LINES.all { v[it] == "hidden" },
                "button ${v[AttackSelection.IP_AT_VRP]}, box ${v[AttackSelection.IP_BOX]}")
            val cartBefore = edt { dtc.cartridgeText() }
            fun unchanged(what: String) = check("$what changes nothing", edt { dtc.cartridgeText() } == cartBefore && !isVip() && messages().isEmpty(), "")
            fun notice() = edt { if (page == WdpPage.POPUP) popup.ipNotice() else toss.ipNotice() }
            fun vrpNow() = edt { if (page == WdpPage.POPUP) popup.plan.vrpPoint() else toss.plan.vrpPoint() }
            val create = AttackSelection.CREATE
            // 1. ONE question, with both points (finish the Input Panel first; how to delete it), the slots and their
            // overwrite words in it — then Cancel: nothing changes, no second box
            val here = tgt - 1
            edt { w.onClick(AttackSelection.IP_AT_VRP) }
            val q = messages().lastOrNull()
            val qt = q?.text.orEmpty()
            check("one question: the VRP's place, finish the Input Panel and check the map first, the steerpoint stays where it is placed",
                messages().size == 1 && q?.title == AttackSelection.IP_AT_VRP_TITLE && "true" in qt && "Finish the Input Panel" in qt &&
                    "check the attack on the map (Profile)" in qt && "The steerpoint stays where it is placed" in qt && "Plan in VRP freely" in qt,
                qt)
            check("…how to delete it: DTC page, STPT tab, Change n on its row, Clear and Apply",
                "To delete it later: DTC page, STPT tab, Change " in qt && "then Clear and Apply." in qt, qt.substringAfterLast("\n"))
            check("…its buttons: Create IP STPT (one per slot where there are two) and Cancel",
                q != null && q.buttons.last() == "Cancel" && q.buttons.dropLast(1).let { b -> b.isNotEmpty() && (b == listOf(create) || b.all { it.startsWith("$create ") }) },
                q?.buttons?.joinToString().orEmpty())
            val inCart = edt { dtc.freeSlots()?.stpts?.contains(here) } == false
            val hereOffered = q != null && (q.buttons.contains("$create $here") || (q.buttons.first() == create && "It goes into STPT $here," in qt))
            if (hereOffered) check("…STPT $here's overwrite words are in it (${if (inCart) "your cartridge's point" else "your route's point"})",
                if (inCart) "STPT $here, which holds" in qt && "in your cartridge (replaced)" in qt else "STPT $here, your route's" in qt, qt)
            answer("Cancel")
            unchanged("Cancel")
            check("…and no line on the page", notice() == null)
            clearDialogs()
            // 2. create: the free slot where there is one (listed first), else the one slot offered
            // TGT-TO-VRP as the page shows it at the press
            val before = labels()
            val vrpBrg = before["lblVIPbrg"]?.toDoubleOrNull()
            val vrpRng = before["lblVIPrng"]?.toDoubleOrNull()
            val vrpAt = edt { (if (page == WdpPage.POPUP) popup.plan.attackPlot() else toss.plan.attackPlot())?.start }
            check("the VRP the button uses is the one the page draws", vrpAt != null && vrpNow()?.let { near(it, vrpAt, 1.0) } == true, "${vrpNow()} / $vrpAt")
            edt { w.onClick(AttackSelection.IP_AT_VRP) }
            val q1 = messages().lastOrNull()
            val n = when {
                q1 == null -> -1
                q1.buttons.first() == create -> Regex("It goes into STPT (\\d+),").find(q1.text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                else -> q1.buttons.first().removePrefix("$create ").toIntOrNull() ?: -1
            }
            answer(q1?.buttons?.first() ?: create)
            check("STPT $n placed on the VRP and the page planned as VIP from it, no box after it", n > 0 && isVip() && messages().isEmpty(),
                messages().joinToString { it.text })
            val made = notice()
            check("the page's line: \"IP STPT $n created at the VRP — delete it on DTC → STPT if no longer needed.\"",
                made?.stale == false && made.text == "IP STPT $n created at the VRP — delete it on DTC → STPT if no longer needed.", made?.text ?: "none")
            val after = labels()
            val vipBrg = after["lblVIPbrg"]?.toDoubleOrNull()
            val vipRng = after["lblVIPrng"]?.toDoubleOrNull()
            val dBrg = if (vrpBrg != null && vipBrg != null) abs(AttackGeometry.wrap360(vipBrg - vrpBrg + 180.0 + 180.0) - 180.0) else 999.0
            check("VIP-TO-TGT is TGT-TO-VRP reversed: bearing ± 180° (0.1°), range equal (2 ft)",
                dBrg <= 0.1 + 1e-9 && vrpRng != null && vipRng != null && abs(vipRng - vrpRng) <= 2.0,
                "TGT-TO-VRP $vrpBrg° $vrpRng ft, VIP-TO-TGT $vipBrg° $vipRng ft")
            val placedNow = edt { dtc.placedThisSession() }
            check("the DTC page holds it as placed this session", n in placedNow, placedNow.keys.joinToString())
            check("the IP STPT is it, the TGT STPT unchanged", edt { if (page == WdpPage.POPUP) popup.plan.ipStpt else toss.plan.ipStpt } == n &&
                edt { w.values(page.hidden)["numWaypoint"] }?.trim() == tgt.toString(), "TGT STPT ${edt { w.values(page.hidden)["numWaypoint"] }}")
            val vv = edt { w.values(page.hidden) }
            check("VIP mode with the IP STPT it created: the button stays (to move it), enabled",
                vv[AttackSelection.IP_AT_VRP] != "hidden" && vv["${AttackSelection.IP_AT_VRP}.enabled"] == "true", "button ${vv[AttackSelection.IP_AT_VRP]}")
            // 3. an Input Panel change moves the VRP away from it: the warning, and only for this steerpoint
            edt { w.onValue("trbHeading", if (page == WdpPage.POPUP) "230" else "235") }
            val stale = notice()
            val moved = vrpNow()?.let { v -> edt { dtc.stptAt(n) }?.let { hypot(v.first - it.first, v.second - it.second) } }
            check("an Input Panel change (attack heading): \"IP STPT $n is no longer at the VRP for these inputs — press IP STPT at the VRP again, or delete it on DTC → STPT.\"",
                stale?.stale == true && stale.text == "IP STPT $n is no longer at the VRP for these inputs — press IP STPT at the VRP again, or delete it on DTC → STPT.",
                "${stale?.text ?: "none"} (the VRP ${moved?.toInt()} ft from it)")
            // 4. pressed again (VIP mode): the same steerpoint moves to the new VRP, no second one, and the warning goes
            edt { w.onClick(AttackSelection.IP_AT_VRP) }
            val q2 = messages().lastOrNull()
            check("pressed again: one question, the same STPT $n moved (no new steerpoint)",
                q2 != null && q2.buttons == listOf(create, "Cancel") && "STPT $n is the IP STPT created earlier" in q2.text, "${q2?.text} [${q2?.buttons?.joinToString()}]")
            val vrp2 = vrpNow()
            answer(create)
            check("…it is at the VRP these inputs lay out, and the warning is gone",
                messages().isEmpty() && isVip() && notice()?.stale == false && vrp2 != null && edt { dtc.stptAt(n) }?.let { near(it, vrp2, 2.0) } == true,
                "${notice()?.text ?: "none"}, STPT $n ${edt { dtc.stptAt(n) }}, the VRP $vrp2")
            clearDialogs()
            val s0 = src.saves
            edt { w.onClick("btnSaveDTC") }
            check("Save to DTC saves it", waitSave(s0))
            Thread.sleep(150)
            val parsed = src.text?.let { DtcParser.parseText(it) }
            val pt = parsed?.steerpoints?.firstOrNull { it.n == n }
            check("the cartridge holds STPT $n at the VRP, Modesel=vip, VIP=$n and OA1-$n",
                pt != null && vrp2 != null && near(pt.x to pt.y, vrp2, 2.0) && parsed.navOffsets?.mode == "vip" &&
                    parsed.navOffsets?.vip?.stpt == n && parsed.navOffsets?.oa?.any { it.key.equals("OA1-$n", true) } == true,
                "STPT $n ${pt?.let { "%.0f, %.0f".format(it.x, it.y) }}, the VRP ${vrp2?.let { "%.0f, %.0f".format(it.first, it.second) }}, " +
                    "Modesel ${parsed?.navOffsets?.mode}, VIP ${parsed?.navOffsets?.vip?.stpt}, OA ${parsed?.navOffsets?.oa?.joinToString { it.key }}")
            check("…and the line still says it was created (after the save too)", notice()?.stale == false, notice()?.text ?: "none")
            clearDialogs()
            // back to VRP: pressing again offers that same steerpoint only; Cancel changes nothing
            edt { w.onClick("pnlRefUp") }
            if (!isVip()) {
                val cart2 = edt { dtc.cartridgeText() }
                edt { w.onClick(AttackSelection.IP_AT_VRP) }
                val q3 = messages().lastOrNull()
                check("back in VRP mode: pressing again moves STPT $n, no second steerpoint", q3 != null && q3.buttons == listOf(create, "Cancel") &&
                    "STPT $n is the IP STPT created earlier" in q3.text, "${q3?.text} [${q3?.buttons?.joinToString()}]")
                answer("Cancel")
                check("…and Cancel changes nothing", edt { dtc.cartridgeText() } == cart2 && !isVip())
            } else check("back to VRP", false)
            clearDialogs()
            // another IP STPT picked: the page says nothing about STPT n (it is the pilot's now)
            val other = (1..24).firstOrNull { it != n && it != tgt && placed(mission, it) }
            if (other != null) {
                edt { w.onValue(AttackSelection.IP_BOX, other.toString()) }
                check("another IP STPT ($other) picked: no line about STPT $n", notice() == null, notice()?.text ?: "")
            }
            clearDialogs()
        }

        // ---------------------------------------------------------------- HADB never offers an IP
        run {
            val v = edt { hadb.values(WdpPage.HADB.hidden) }
            check("HADB: no IP STPT, no IP lines, no IP STPT at the VRP", v[AttackSelection.IP_BOX] == "hidden" && v[AttackSelection.IP_LABEL] == "hidden" &&
                v[AttackSelection.IP_AT_VRP] == "hidden")
        }

        // ---------------------------------------------------------------- the current attack across a restart
        run {
            appendLine()
            appendLine("== The current attack across a restart (kept with its mission)")
            val epochKey = "mission_epoch"
            val epochWas = runCatching { Repo.getString(epochKey) }.getOrNull()
            fun seen(callsign: String) = "{\"mode\":\"${com.bmscompanion.app.data.mission.MissionMode.WDP}\",\"flight\":" +
                Repo.json.encodeToString(com.bmscompanion.app.data.mission.LedgerMission.serializer(),
                    com.bmscompanion.app.data.mission.LedgerMission(callsign = callsign, theater = th.name, save = file.name)) + "}"
            try {
                Repo.putString(epochKey, seen(flight.row.callsign))
                edt { AttackFocus.clear(); AttackFocus.touch(WdpPage.TOSS) }
                val kept = runCatching { Repo.getString(AttackFocus.KEY) }.getOrNull()
                check("the current attack is kept in the device's preferences with its mission", kept?.startsWith("TOSS\n") == true && flight.row.callsign in kept,
                    kept?.replace('\n', ' ') ?: "nothing kept")
                // a restart: the object's state is what the preferences give back
                edt { AttackFocus.restore() }
                check("a restart on the same mission takes it back", AttackFocus.profile == WdpPage.TOSS, "${AttackFocus.profile}")
                val kb = edt { KneeboardExtraPages.attackPage() }
                check("…so the Upd Kneeboard attack page prints it (not \"No attack planned\")", kb == WdpPage.TOSS &&
                    edt { KneeboardExtraPages.attackPicture(WdpPage.TOSS)?.attack } != null, "page $kb")
                // another mission seen at the next start: nothing is taken back, and what was kept is dropped
                Repo.putString(epochKey, seen("Other9"))
                edt { AttackFocus.restore() }
                check("a restart on another mission takes nothing back, and drops what was kept",
                    AttackFocus.profile == null && runCatching { Repo.getString(AttackFocus.KEY) }.getOrNull() == null,
                    "${AttackFocus.profile}, kept ${runCatching { Repo.getString(AttackFocus.KEY) }.getOrNull() != null}")
                // the same flight in the other mode is another mission too
                Repo.putString(epochKey, seen(flight.row.callsign))
                edt { AttackFocus.touch(WdpPage.HADB) }
                Repo.putString(epochKey, seen(flight.row.callsign).replace("\"${com.bmscompanion.app.data.mission.MissionMode.WDP}\"", "\"${com.bmscompanion.app.data.mission.MissionMode.EZBOARDS}\""))
                edt { AttackFocus.restore() }
                check("…nor on the same flight in the other mode", AttackFocus.profile == null, "${AttackFocus.profile}")
                // where AttackFocus.clear runs (a new mission, Re-read DTC, the delivery cleared) the kept one goes too
                Repo.putString(epochKey, seen(flight.row.callsign))
                edt { AttackFocus.touch(WdpPage.POPUP); AttackFocus.clear() }
                edt { AttackFocus.restore() }
                check("a clear drops what was kept: a restart takes nothing back", AttackFocus.profile == null &&
                    runCatching { Repo.getString(AttackFocus.KEY) }.getOrNull() == null, "${AttackFocus.profile}")
                // WdpSession.resetAttack (MissionEpoch's reset) clears it as well
                edt { AttackFocus.touch(WdpPage.POPUP) }
                edt { WdpSession.resetAttack() }
                edt { AttackFocus.restore() }
                check("a new mission (WdpSession.resetAttack) drops it: a restart takes nothing back", AttackFocus.profile == null, "${AttackFocus.profile}")
            } finally {
                runCatching { Repo.putString(epochKey, epochWas) }
                edt { AttackFocus.clear() }
            }
        }

        // ---------------------------------------------------------------- no current attack: the Map page draws the Mission map's
        run {
            appendLine()
            appendLine("== No current attack: the Map page draws what the Mission map draws")
            clearDialogs()
            // a cartridge with an attack in it, and a DataCard profile that would shape it another way
            edt {
                toss.onValue("numWaypoint", tgt.toString())
                if (toss.plan.ref) toss.onClick("pnlRefUp")
                toss.onValue("trbHeading", "170")
            }
            val s0 = src.saves
            edt { toss.onClick("btnSaveDTC") }
            waitSave(s0); Thread.sleep(150)
            edt { card.onClick("btnPopUp") }
            clearDialogs()
            edt { AttackFocus.clear() }
            val cardProfile = edt { card.plan.attack.strProfile }
            val text = edt { dtc.cartridgeText() }
            val parsed = text?.let { DtcParser.parseText(it) }
            val route = com.bmscompanion.app.data.mission.MissionRoute(
                file = "", save = file.name, kind = "", modified = 0L,
                steerpoints = flight.route.mapIndexed { i, wp -> com.bmscompanion.app.data.mission.DtcPoint(n = if (wp.n > 0) wp.n else i + 1, x = wp.x, y = wp.y, altFt = wp.altFt, action = wp.action) },
            )
            val missionMap = parsed?.let { PlanMerge.cartridgeAttack(MissionData(dtc = it, route = route)) }
            val d = mapNow()
            appendLine("DataCard profile $cardProfile; the cartridge's Modesel ${parsed?.navOffsets?.mode}")
            check("no current attack, a cartridge attack to draw", AttackFocus.profile == null && missionMap != null, "${AttackFocus.profile}, ${missionMap != null}")
            check("the Map page draws the Mission map's attack (start → PUP → TGT, no profile), whatever the DataCard's profile",
                differs(d.drawn, missionMap, 2.0) == null && d.drawn?.profile.isNullOrEmpty() && d.drawn?.runIn?.size == missionMap?.runIn?.size,
                (differs(d.drawn, missionMap, 2.0) ?: "") + " profile '${d.drawn?.profile}', path ${d.drawn?.runIn?.size} / ${missionMap?.runIn?.size}")
            check("…with the same generic caption", d.drawn != null && missionMap != null && AttackDrawing.caption(d.drawn!!) == AttackDrawing.caption(missionMap),
                "${d.drawn?.let { AttackDrawing.caption(it) }} / ${missionMap?.let { AttackDrawing.caption(it) }}")
            clearDialogs()
        }
        AttackSelection.card = null
        appendLine()
        appendLine(if (fails == 0) "ALL PASS" else "FAIL: $fails checks failed")
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var r: Result<T>? = null
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r!!.getOrThrow()
    }
}
