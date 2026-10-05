package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.CampFlightRow
import com.bmscompanion.app.data.mission.CampKind
import com.bmscompanion.app.data.mission.CampWaypoint
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.mission.MissionRoute
import com.bmscompanion.app.data.wdp.WdpControl
import com.bmscompanion.app.data.wdp.WdpForm
import com.bmscompanion.app.data.wdp.WdpValues
import com.bmscompanion.app.ui.screens.wdp.DataCardWiring
import com.bmscompanion.app.ui.screens.wdp.HadbWiring
import com.bmscompanion.app.ui.screens.wdp.PopupWiring
import com.bmscompanion.app.ui.screens.wdp.TossWiring
import com.bmscompanion.app.ui.screens.wdp.WdpDialog
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpMessage
import com.bmscompanion.app.ui.screens.wdp.WdpMission
import com.bmscompanion.app.ui.screens.wdp.WdpPage
import com.bmscompanion.app.ui.screens.wdp.WdpWiring
import com.bmscompanion.app.ui.screens.wdp.PlannerMissionState
import com.bmscompanion.app.ui.screens.wdp.plannerMission
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The mission the Planner's headless checks give the pages, built the way the Planner builds it ([plannerMission]):
 * the briefing and the cartridge from disk (copies, read, never written), and for the steerpoint sources and the
 * default TGT STPT (R3-PLAN A3, A4):
 *
 * - `BMSC_WDP_ROUTE=<a copy of a mission file, <save>.ini>`: its `[STPT]` route as BMS's own (`MissionData.route`,
 *   which the PC serves only for the briefed flight);
 * - `BMSC_WDP_SAVEFLIGHT=<n>`: the briefed flight as a save's flight made from that route, with steerpoint n a strike
 *   (action 17), as Open mission… hands one to the Planner — a stand-in until the campaign reader serves real flights.
 *   It is put in [PlannerMissionState] too, so the composed Planner plans the same mission.
 */
internal object WdpFixtureMission {
    fun data(briefing: File?, cartridge: File?): MissionData {
        val routeFile = System.getenv("BMSC_WDP_ROUTE")?.let(::File)?.takeIf { it.isFile }
        val route = routeFile?.let { f ->
            runCatching {
                val d = DtcParser.parse(f)
                MissionRoute(
                    file = f.name, save = f.nameWithoutExtension, kind = CampKind.CAMPAIGN, modified = f.lastModified(),
                    steerpoints = d.steerpoints, ppts = d.ppts, lines = d.lines, weaponTargets = d.weaponTargets,
                )
            }.getOrNull()
        }
        return MissionData(
            briefing = briefing?.takeIf { it.isFile }?.let { runCatching { BriefingParser.parse(it.readText()) }.getOrNull() },
            dtc = cartridge?.takeIf { it.isFile }?.let { runCatching { DtcParser.parse(it) }.getOrNull() },
            route = route,
        )
    }

    /** The save's flight `BMSC_WDP_SAVEFLIGHT` asks for, made from the route's flight-plan points; null without both. */
    fun flight(data: MissionData): CampFlight? {
        val strike = System.getenv("BMSC_WDP_SAVEFLIGHT")?.trim()?.toIntOrNull() ?: return null
        val rows = data.briefing?.steerpoints.orEmpty().associateBy { it.n }
        // the route's own points: flight-plan actions only (the precision targets BMS copies in carry -1), in 1..24
        val pts = data.route?.steerpoints.orEmpty().filter { it.n in 1..24 && it.action != -1 && (it.x != 0.0 || it.y != 0.0) }
        if (pts.isEmpty()) return null
        val own = data.briefing?.overview?.flight ?: "Fixture1"
        return CampFlight(
            row = CampFlightRow(id = "fixture", number = 1, callsign = own, mission = data.briefing?.overview?.mission ?: "", briefed = true, f16 = true),
            route = pts.map { p ->
                CampWaypoint(n = p.n, x = p.x, y = p.y, altFt = p.altFt, action = if (p.n == strike) WdpMission.STRIKE else p.action,
                    desc = if (p.n == strike) "Strike" else rows[p.n]?.desc)
            },
            briefing = data.briefing,
        )
    }

    /** The mission for [data] in [theater]; with a fixture save flight, the Planner's state is set to plan it too. */
    fun mission(data: MissionData, theater: com.bmscompanion.app.data.Theater?): WdpMission {
        val f = flight(data)
        if (f != null) {
            PlannerMissionState.source = PlannerMissionState.SAVE
            PlannerMissionState.flight = f
            PlannerMissionState.ref = null
            PlannerMissionState.seat = 0
        }
        return plannerMission(data, theater, f)
    }

    /** One line for a report: where the steerpoints came from, the default TGT STPT and the card's targets. */
    fun describe(m: WdpMission): String {
        val (pri, sec) = m.cardTargets()
        return "steerpoints: " + m.sourceLine.ifEmpty { "none placed" } +
            (if (m.flight != null) " (a save's flight: ${m.flight.row.callsign})" else "") +
            "; default TGT STPT: " + (m.defaultStpt?.toString() ?: "none") +
            "; card targets: " + (pri?.label ?: "none") + " / " + (sec?.label ?: "none")
    }
}

/**
 * Every page's wiring, made fresh — the one place the headless checks build them, so a newly wired page is one
 * line here. A page that returns null is drawn but not wired.
 */
internal object WdpWirings {
    fun create(page: WdpPage): WdpWiring? = when (page) {
        WdpPage.TOSS -> TossWiring()
        WdpPage.POPUP -> PopupWiring()
        WdpPage.HADB -> HadbWiring()
        WdpPage.PERFORMANCE -> {
            // the page writes its Setup.ini settings on every change, as WDP does on exit; each fresh page starts
            // from none (WDP's first run), so one press cannot change what the next one starts from
            performanceIni
            Repo.putString(com.bmscompanion.app.ui.screens.wdp.PerformanceWiring.INI_KEY, null)
            com.bmscompanion.app.ui.screens.wdp.PerformanceWiring().also { kotlinx.coroutines.runBlocking { it.prepare(null) } }
        }
        WdpPage.BRIEFING, WdpPage.DATACARD, WdpPage.COORDINATION -> DataCardWiring()
        WdpPage.DTC -> com.bmscompanion.app.ui.screens.wdp.DtcWiring(WdpDtcFixture.source())
        else -> null
    }

    /**
     * The mission into a fresh wiring, as the Planner gives it. The DataCard also reads the app's airports and
     * charts and the cartridge ([DataCardWiring.prepare]); here the cartridge is the fixture's copy, read only.
     */
    /** The Performance page's settings as the check found them, put back by [restoreSettings]. */
    private val performanceIni: String? by lazy { Repo.getString(com.bmscompanion.app.ui.screens.wdp.PerformanceWiring.INI_KEY) }

    fun restoreSettings() = Repo.putString(com.bmscompanion.app.ui.screens.wdp.PerformanceWiring.INI_KEY, performanceIni)

    fun feed(w: WdpWiring, mission: WdpMission, cartridge: File?) {
        if (w is DataCardWiring) runBlocking { w.prepare(mission) { cartridge?.takeIf { it.isFile }?.readText() } }
        // Performance reads the departure field's own data (runway widths, its charts) before the page is filled
        else if (w is com.bmscompanion.app.ui.screens.wdp.PerformanceWiring) runBlocking { w.prepare(mission) }
        else w.onMission(mission)
    }
}

/**
 * `--wdpclicktest <out.txt> [briefing.txt] [cartridge.ini] [page]` — presses everything on every Planner page and
 * says what each press did.
 *
 * For each interactive control of a page (and, one level down, of every child window a press opens) a fresh wiring
 * is given the mission, the control is operated the way a pilot would — a button pressed, a box ticked, a list
 * opened and its first item picked, a field typed into and left, an up/down stepped, a slider moved, a tab chosen, a
 * grid row tapped and double-tapped — and the outcome is recorded: how many values changed, which window or
 * message it opened, or that it **did nothing** or **threw**. "Did nothing" is the line to read: in WDP every one
 * of those controls does something, so each must either do it here or be named in the page's notes with the
 * reason it cannot (a file dialog on WDP's own disk, say).
 *
 * Run it with APPDATA pointed at a scratch folder: some pages remember their settings, as WDP does in Setup.ini.
 *
 * **The removed controls.** WDP's buttons for what the app does not do (Print, Print Preview, the Tactical Engagement
 * buttons, Save PPT.ini and their kin: [removedOn]) are taken off the page, not answered with a message. Each must
 * come out as "hidden on the page" wherever the test reaches it; one that is pressed and does anything, or merely
 * shows, fails the run. So does a press that throws.
 */
internal object WdpClickTest {
    private class Outcome(val control: String, val action: String, val effect: String, val dead: Boolean, val threw: Boolean, val off: Boolean = false)

    /**
     * The controls the Planner removes from [page] (plan section B), and those of the child windows it opens, as
     * `"<window>.<control>"`: what WDP does that the app does not. (WDP's own DTC buttons — the per-tab Save DTC, Open/
     * Save Callsign.ini File, the card's Get DTC File, Save DTC and lamp — are on their pages and pressed like the rest.)
     * The list is the pages' own ([WdpPage.hidden] less the stacked views), so the test checks what the Planner hides
     * rather than a copy of it.
     */
    fun removedOn(page: WdpPage): List<String> = when (page) {
        WdpPage.BRIEFING, WdpPage.DATACARD, WdpPage.COORDINATION -> DataCardWiring.REMOVED
        WdpPage.DTC -> com.bmscompanion.app.ui.screens.wdp.DtcWiring.REMOVED
        WdpPage.POPUP, WdpPage.HADB, WdpPage.TOSS -> listOf("btnTE")
        else -> emptyList()
    }

    /**
     * The file window a press asked for, if any: WDP's file buttons open Windows' Open and Save windows, which the
     * Planner opens on the BMS PC ([com.bmscompanion.app.data.PcFiles]). Here no window is drawn: every request is
     * answered Cancel and recorded, so a file button counts as doing what it does — asking for a file.
     */
    @Volatile private var fileAsked: com.bmscompanion.app.data.FileRequest? = null

    fun run(out: File, briefing: File?, cartridge: File?, only: String?): String = buildString {
        com.bmscompanion.app.data.PcFiles.testAnswer = { req -> fileAsked = req; null }
        val data = WdpFixtureMission.data(briefing, cartridge)
        // BMSC_WDP_THEATER, as for --wdprender: with a theater the DTC page's airport list and runway points open too
        val theater = System.getenv("BMSC_WDP_THEATER")?.let { name ->
            runBlocking { com.bmscompanion.app.ui.screens.wdp.plannerTheater(Repo.index().theaters, name) }
        }
        // the mission as the Planner builds it: each attack page's TGT STPT set once to the default, no target bar
        val mission = WdpFixtureMission.mission(data, theater)
        WdpDtcFixture.file = cartridge
        // the Planner's own DTC page (IP STPT at the VRP reaches it) reads the check's copy, in memory, never the PC's
        com.bmscompanion.app.ui.screens.wdp.WdpSession.dtcSource = WdpDtcFixture.source()
        appendLine("Weapon Delivery Planner — every control pressed")
        appendLine("briefing: ${if (data.briefing != null) "read" else "none"}, cartridge: ${if (data.dtc != null) "read" else "none"}, BMS route: ${if (data.route != null) "read" else "none"}")
        appendLine(WdpFixtureMission.describe(mission))
        var dead = 0
        var threw = 0
        var off = 0
        var total = 0
        var removedHidden = 0
        val removedShown = ArrayList<String>()
        // the app's own pages (the Map page) have no WDP layout to press through
        for (page in WdpPage.entries.filter { !it.native }) {
            if (only != null && !page.name.equals(only, true) && !page.form.equals(only, true)) continue
            val form = runBlocking { Repo.wdpForm(page.form) }
            if (form == null) { appendLine("FAIL ${page.label}: no layout"); continue }
            appendLine()
            appendLine("== ${page.label} (${page.form})")
            if (WdpWirings.create(page) == null) { appendLine("   not wired: every control on it is inert"); continue }
            val results = pressAll(form, page.hidden) { WdpWirings.create(page)!!.also { w -> safely { WdpWirings.feed(w, mission, cartridge) } } }
            for (r in results) {
                total++
                if (r.dead) dead++
                if (r.threw) threw++
                if (r.off) off++
                appendLine((if (r.threw) "THREW " else if (r.dead) "DEAD  " else if (r.off) "off   " else "ok    ") + r.control.padEnd(28) + r.action.padEnd(16) + r.effect)
            }
            // the controls this page removes: each one the test reached must be off the page
            val names = form.all().map { it.name }.toSet()
            for (n in removedOn(page)) {
                val window = n.contains('.')
                val hits = results.filter { it.control.trim() == n }
                if (!window && n !in names) { appendLine("note  removed $n is not in the layout (nothing to hide)"); continue }
                if (hits.isEmpty()) continue   // furniture (a lamp, a caption) or a window the run did not open: nothing to press
                if (hits.all { it.off && it.effect == "hidden on the page" }) removedHidden++
                else { removedShown += "${page.label}: $n"; appendLine("FAIL  removed $n is on the page: " + hits.joinToString { it.effect }) }
            }
        }
        WdpWirings.restoreSettings()
        com.bmscompanion.app.data.PcFiles.testAnswer = null
        appendLine()
        appendLine("controls pressed: $total, did nothing: $dead, threw: $threw, disabled, hidden or already selected: $off")
        appendLine("removed controls (plan B) reached: ${removedHidden + removedShown.size}, hidden: $removedHidden, on the page: ${removedShown.size}")
        appendLine(if (threw == 0 && removedShown.isEmpty()) "PASS" else "FAIL" + (if (removedShown.isNotEmpty()) " — on the page: " + removedShown.joinToString() else "") +
            (if (threw > 0) " — $threw presses threw" else ""))
        out.writeText(toString())
    }

    private fun safely(block: () -> Unit): Throwable? = try { block(); null } catch (t: Throwable) { t }

    /** Operates every interactive control of [form] on a fresh wiring from [make], then the dialogs it opens. */
    private fun pressAll(form: WdpForm, hidden: List<String>, depth: Int = 0, make: () -> WdpWiring): List<Outcome> {
        val out = ArrayList<Outcome>()
        // a control the page shows disabled (or inside a disabled one) takes no input from the renderer, so it is not
        // pressed: it is reported as disabled, which is not the same as doing nothing
        val parentOf = HashMap<String, String>()
        fun link(c: WdpControl) { for (k in c.children) { parentOf[k.name] = c.name; link(k) } }
        form.roots.forEach(::link)
        fun disabled(v: Map<String, String>, n: String?): Boolean = n != null && (v["$n.enabled"] == "false" || disabled(v, parentOf[n]))
        // a control the page does not show (hidden by the wiring or by the designer, or inside one that is) cannot be
        // pressed by a pilot either: when pressing it does nothing, that is not a finding
        val byName = form.all().associateBy { it.name }
        fun hiddenOn(v: Map<String, String>, n: String?): Boolean = n != null &&
            (v[n] == "hidden" || (byName[n]?.hidden == true && v[n] != "shown") || hiddenOn(v, parentOf[n]))
        for (c in form.all()) {
            for ((action, op) in operations(c)) {
                WdpDialogs.stack.clear()
                val w = make()
                val before = runCatching { w.values(hidden).values }.getOrDefault(emptyMap())
                // a control the page hides is not there to press: WDP hides the Loadout window's TGP and HTS boxes
                // from BMS 4.34 on, for instance
                if (before[c.name] == "hidden") {
                    out += Outcome((if (depth > 0) "  ${form.form}." else "") + c.name, action, "hidden on the page", dead = false, threw = false, off = true)
                    continue
                }
                if (disabled(before, c.name)) {
                    out += Outcome((if (depth > 0) "  ${form.form}." else "") + c.name, action, "disabled on the page", dead = false, threw = false, off = true)
                    continue
                }
                // a window's OK or Cancel closes it: that is what the press does, even when nothing in it changed
                val openBefore = WdpDialogs.stack.toList()
                // one of the Planner's own windows (the card's Upd Kneeboard opens the toolbar's): opened, not a WDP form
                com.bmscompanion.app.ui.screens.wdp.PlannerWindows.close()
                fileAsked = null
                val err = safely { op(w) }
                var after = runCatching { w.values(hidden).values }.getOrDefault(before)
                var changed = (before.keys + after.keys).count { before[it] != after[it] }
                var top = WdpDialogs.stack.lastOrNull()
                val closed = openBefore.any { it !in WdpDialogs.stack }
                val planner = com.bmscompanion.app.ui.screens.wdp.PlannerWindows.open
                com.bmscompanion.app.ui.screens.wdp.PlannerWindows.close()
                // a file button asks for its window after the PC has made the folder: give it a moment
                if (err == null && !(top != null && top !in openBefore || closed || changed > 0 || planner != null)) {
                    val until = System.currentTimeMillis() + 800
                    while (fileAsked == null && System.currentTimeMillis() < until) Thread.sleep(20)
                    after = runCatching { w.values(hidden).values }.getOrDefault(before)
                    changed = (before.keys + after.keys).count { before[it] != after[it] }
                    top = WdpDialogs.stack.lastOrNull()
                }
                val asked = fileAsked
                val effect = when {
                    err != null -> "${err::class.simpleName}: ${err.message}"
                    planner != null -> "opened the Planner's \"${planner.title}\" window"
                    top is WdpDialog && top !in openBefore -> "opened ${top.form} \"${top.title}\"" + if (changed > 0) " (+$changed values)" else ""
                    top is WdpMessage -> "message \"${top.title}\": ${top.text.take(70).replace('\n', ' ')}"
                    asked != null -> "asked for a file: \"${asked.title}\" in ${asked.startDir}"
                    closed -> "closed the window" + if (changed > 0) " (+$changed values)" else ""
                    changed > 0 -> "$changed values changed"
                    else -> "nothing"
                }
                val acted = top != null && top !in openBefore || closed || changed > 0 || planner != null || asked != null
                // a radio button already selected changes nothing in Windows either
                val why = if (err != null || acted) null else if (hiddenOn(before, c.name)) "hidden on the page"
                    else if (c.kind == "radio" && before[c.name] == "checked") "already selected" else null
                if (why != null) {
                    out += Outcome((if (depth > 0) "  ${form.form}." else "") + c.name, action, why, dead = false, threw = false, off = true)
                    continue
                }
                out += Outcome((if (depth > 0) "  ${form.form}." else "") + c.name, action, effect, dead = err == null && !acted, threw = err != null)
                // one level down: what the window it opened does
                if (depth == 0 && top is WdpDialog) {
                    val dForm = runBlocking { Repo.wdpForm(top.form) }
                    val dw = top.wiring
                    if (dForm != null && dw != null) {
                        out += pressAll(dForm, top.hidden, depth + 1) {
                            // re-open it on a fresh page each time, so every press starts from the same window
                            WdpDialogs.stack.clear()
                            val pw = make()
                            safely { op(pw) }
                            (WdpDialogs.stack.lastOrNull() as? WdpDialog)?.wiring ?: dw
                        }
                    }
                }
            }
        }
        WdpDialogs.stack.clear()
        return out
    }

    /** How a pilot operates each kind of control; nothing for furniture. */
    private fun operations(c: WdpControl): List<Pair<String, (WdpWiring) -> Unit>> = when (c.kind) {
        "button", "check", "radio" -> listOf("press" to { w -> w.onClick(c.name) })
        // a list with items is opened by the renderer itself; what the page hears is the pick — of an item other
        // than the one showing, so that a pick is a change. A list without items reports the tap, as WDP's own do.
        "combo" -> listOf("choose" to { w: WdpWiring ->
            val v = w.values(emptyList())
            val items = v["${c.name}.items"]?.split('\n')?.filter { it.isNotEmpty() } ?: c.items
            if (items.isEmpty()) w.onClick(c.name)
            else w.onValue(c.name, items.firstOrNull { it != v[c.name] } ?: items[0])
        })
        // typed: "1", or "2" where the box already says 1 (the briefing's wind from 001°, say), so that it is a change
        "text" -> if (c.readOnly) emptyList() else listOf("type + leave" to { w ->
            val typed = if (w.values(emptyList())[c.name]?.trim() == "1") "2" else "1"
            w.onValue(c.name, typed); w.onValue("${c.name}.leave", "")
        })
        // the up arrow as the renderer reports it: the stepped value, or "<name>:up" for a box whose arrows come by name
        "number" -> listOf("step up" to { w ->
            com.bmscompanion.app.ui.screens.wdp.upDownArrow(c.name, w.values(emptyList()), w.values(emptyList())[c.name] ?: "", null, 1, w::onValue, w::onClick)
        })
        "slider" -> listOf("move" to { w ->
            val v = w.values(emptyList())
            val max = v["${c.name}.max"]?.toIntOrNull() ?: 10
            val min = v["${c.name}.min"]?.toIntOrNull() ?: 0
            val now = v[c.name]?.toIntOrNull() ?: min
            w.onValue(c.name, (if (now < max) now + 1 else now - 1).toString())
        })
        // a tab strip switches pages in the renderer; a wiring need not hear it, so it is not a press to judge
        "grid", "list" -> listOf(
            "tap row 0" to { w: WdpWiring -> w.onClick("${c.name}:row:0") },
            "double-tap 0" to { w: WdpWiring -> w.onClick("${c.name}:open:0") },
        )
        else -> emptyList()
    }
}
