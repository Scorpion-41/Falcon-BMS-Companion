package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.wdp.DtcEdits
import com.bmscompanion.app.data.wdp.DtcFromMission
import com.bmscompanion.app.data.wdp.DtcFromMission.Pt
import com.bmscompanion.app.ui.screens.wdp.DtcWiring
import com.bmscompanion.app.ui.screens.wdp.PlannerMissionState
import com.bmscompanion.app.ui.screens.wdp.WdpDialog
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpMessage
import com.bmscompanion.app.ui.screens.wdp.plannerMission
import com.bmscompanion.app.ui.screens.wdp.plannerTheater
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `--dtcfromtest <a copy of the BMS folder> out.txt` — the DTC page's **From mission…** on a real mission, text only.
 *
 * The copy's printed briefing and the flight it names, read from its save as Open mission… reads it (`CampaignFiles`),
 * with the pilot's cartridge held in memory ([WdpDtcFixture]: never written). Each tab's From mission… is pressed on a
 * page of its own and its questions answered as a pilot would; the report says what the mission offered, which keys
 * of the cartridge each action changed (what Save to DTC would write), and checks that they read back as the mission's
 * (`DtcParser` over the page's cartridge text). Also: the place… calls the Map page will use, including the question
 * before a placed point is replaced, and the PPT typing over the save's own known air-defence sites (read here the
 * way the "brief" lane is asked to serve them, fin-fill notes, Needs) and a handful of names that must and must not
 * type.
 */
internal object DtcFromMissionTest {
    fun run(root: File): String = buildString {
        var fails = 0
        fun check(what: String, ok: Boolean, detail: String = "") {
            if (!ok) fails++
            appendLine((if (ok) "ok    " else "FAIL  ") + what + if (detail.isNotEmpty()) " — $detail" else "")
        }

        // ---------------------------------------------------------------- the mission, as the Planner opens it
        val set = Theaters.at(root)
        val bf = Theaters.resolveFile(root, "User\\Briefings\\briefing.txt")
        val printed = bf?.let { f -> runCatching { BriefingParser.parse(f.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")) }.getOrNull() }
        val ctx = CampaignFiles.Context(set, null, printed, bf?.lastModified() ?: 0L)
        val listing = CampaignFiles.list(ctx, all = false)
        val briefed = listing.theaters.flatMap { th -> th.files.filter { it.briefed }.map { th to it } }.firstOrNull()
        val cartridge = File(root, "User/Config").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".ini", true) && runCatching { it.readText(Charsets.ISO_8859_1).contains("[STPT]") }.getOrDefault(false) }
            .maxByOrNull { it.lastModified() }
        appendLine("DTC From mission… on the copy's briefed flight")
        appendLine("briefing: ${if (printed != null) "read" else "none"}; save: ${briefed?.let { "${it.first.name}/${it.second.name}" } ?: "none"}; cartridge: ${if (cartridge != null) "found" else "none"}")
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
        appendLine("flight: ${flight.row.callsign}, ${flight.route.size} waypoints, laser ${flight.laser}, support " +
            flight.support.joinToString { "${it.callsign} (${it.role}) ${it.track.size} pts, ${it.track.count { p -> p.station }} on station" })

        val theater = runBlocking { plannerTheater(Repo.index().theaters, th.name) }
        val data = MissionData(briefing = printed, dtc = DtcParser.parse(cartridge))
        PlannerMissionState.source = PlannerMissionState.SAVE
        PlannerMissionState.flight = flight
        PlannerMissionState.theater = th.name
        PlannerMissionState.ref = null
        PlannerMissionState.seat = 0
        val mission = plannerMission(data, theater, flight)
        appendLine("theater: ${theater?.name ?: "none"}; ${mission.sourceLine}")
        WdpDtcFixture.file = cartridge

        /** A fresh page with the mission on it and what From mission… reads, read. */
        fun page(): DtcWiring {
            WdpDialogs.stack.clear()
            val w = DtcWiring(WdpDtcFixture.source())
            // what the PC would serve for the mission BMS printed: its briefing (the comm ladder, the threat section)
            w.served = { MissionData(briefing = printed) }
            w.onMission(mission)
            runBlocking { w.prepareFacts() }
            // what the page read on the main thread (the theater's PPT table) comes back there: wait for it
            repeat(100) { if (w.values(emptyList()).values["dgvPPT.rows"].orEmpty().isNotBlank() && w.fileName != null) return w; Thread.sleep(50) }
            return w
        }

        fun waitTop(n: Int): Any? {
            repeat(100) { if (WdpDialogs.stack.size > n) return WdpDialogs.stack.last(); Thread.sleep(50) }
            return null
        }

        /** Presses [button] and answers each question in turn with the first of [answers] it offers ("*" = its first button). */
        fun press(w: DtcWiring, button: String, vararg answers: String): List<String> {
            val said = ArrayList<String>()
            WdpDialogs.stack.clear()
            w.onClick(button)
            for (a in answers) {
                val top = waitTop(0) ?: break
                when (top) {
                    is WdpMessage -> {
                        said += "${top.title}: ${top.text.replace('\n', ' ').take(160)} [${top.buttons.joinToString(" | ")}]"
                        val pick = if (a == "*") top.buttons.first() else top.buttons.firstOrNull { it == a || it.startsWith(a) } ?: "(not offered: $a)"
                        WdpDialogs.stack.remove(top)
                        if (!pick.startsWith("(not")) top.onAnswer?.invoke(pick) else said += "  answer $a not offered"
                    }
                    is WdpDialog -> {
                        said += "window ${top.form} \"${top.title}\""
                        top.wiring?.onClick(a)
                    }
                }
            }
            // whatever is still up (a note after the change) is read and closed
            (WdpDialogs.stack.lastOrNull() as? WdpMessage)?.let { said += "then ${it.title}: ${it.text.replace('\n', ' ').take(160)}" }
            WdpDialogs.stack.clear()
            return said
        }

        fun edits(before: String?, after: String?): String {
            if (before == null || after == null) return "no cartridge text"
            val e = DtcEdits.between(before, after)
            return "${e.size} keys" + if (e.isEmpty()) "" else ": " + e.take(10).joinToString("; ") { "[${it.section}] ${it.key}=${it.value}" } + if (e.size > 10) "; …" else ""
        }

        // ---------------------------------------------------------------- what the mission offers
        val w0 = page()
        val f = w0.missionFacts()
        appendLine()
        appendLine("== what the mission offers (DtcMissionFacts)")
        appendLine("route: " + f.route.joinToString { "${it.n} a${it.action} ${it.name ?: ""} (${it.from})" })
        for (s in f.support) appendLine("support: ${s.callsign} ${s.role} track ${s.track.size} pts, station ${s.station?.let { "N ${it.north.toInt()} E ${it.east.toInt()}" } ?: "none"} (${s.stationFrom ?: "-"}), TACAN ${s.tacan ?: "-"}, tie-on ${s.tieOn ?: "-"}, yours ${s.yours}")
        appendLine("sites: ${f.sites.size}; places: " + f.places.groupBy { it.group }.entries.joinToString { "${it.key} ${it.value.size}" })
        appendLine("targets: " + f.targets.joinToString { it.name })
        appendLine("TACANs: " + f.tacans.joinToString { it.label })
        appendLine("ILS: " + f.ils.joinToString { it.label })
        appendLine("laser ${f.laser}, seat ${f.seat}, briefed systems ${f.briefedSystems}")
        f.notes.forEach { appendLine("note: $it") }
        check("the flight plan is offered", f.route.isNotEmpty(), "${f.route.size} points")
        check("the airbases are places", f.places.any { it.group == "Airbases" || it.group == "Your airbases" })
        val lineOpts = w0.lineOptions(f)
        appendLine("line options: " + lineOpts.joinToString { "${it.short} (${it.kind}, ${it.points.size} pts)" })
        check("every line option has 2 to 6 points", lineOpts.all { it.points.size in 2..6 })
        check("a support track is a line option wherever the save gives one with two points or more",
            flight.support.none { s -> s.track.size >= 2 } || lineOpts.any { it.kind != DtcFromMission.LineKind.ROUTE && it.kind != DtcFromMission.LineKind.CAP })
        // A support track becomes the line WDP lays for a tanker (fclsMain.lsbTanker_Click, fclsChangeLine.FillTanker):
        // five points, the corners 30,000 ft either side of the station leg (the first tanker or ELINT waypoint and the
        // next) in WDP's order and the first again. Worked out here with WDP's own formula, in its frame (X east,
        // Y north, the track angle from east towards north).
        for (s in flight.support) {
            val longest = s.track.withIndex().filter { it.value.station }.map { it.index }
            appendLine("  ${s.callsign}: WDP's leg ${s.leg}-${s.leg + 1}, the longest hold marks ${longest.joinToString("-")}")
            if (s.leg < 0 || s.leg + 1 >= s.track.size) continue
            val a = s.track[s.leg]
            val b = s.track[s.leg + 1]
            val th = kotlin.math.atan2(b.x - a.x, b.y - a.y)
            val h = 30000.0
            fun c(p: com.bmscompanion.app.data.mission.TrackPoint, sign: Double) = Pt(p.x - sign * kotlin.math.cos(th) * h, p.y + sign * kotlin.math.sin(th) * h)
            val want = listOf(c(a, 1.0), c(a, -1.0), c(b, -1.0), c(b, 1.0), c(a, 1.0))
            val o = lineOpts.firstOrNull { it.kind != DtcFromMission.LineKind.ROUTE && it.kind != DtcFromMission.LineKind.CAP && it.label.startsWith(s.callsign + " ") }
            check("${s.callsign}'s track is the box WDP lays: 5 points, its corners in WDP's order, closed",
                o != null && o.points.size == 5 && o.points.indices.all { o.points[it].dist(want[it]) < 1.0 } && o.points.first() == o.points.last(),
                o?.points?.joinToString(" | ") { "N ${it.north.toInt()} E ${it.east.toInt()}" } ?: "no option")
        }
        val routeRuns = lineOpts.filter { it.kind == DtcFromMission.LineKind.ROUTE }
        check("route stretches join (each starts where the one before ends)", routeRuns.zipWithNext().all { (a, b) -> a.points.last() == b.points.first() })

        // ---------------------------------------------------------------- Lines
        appendLine()
        appendLine("== Lines: From mission… (Change Area, the free lines started on the tracks), Apply")
        run {
            val w = page()
            val m = w.model ?: return@run check("a cartridge on the page", false)
            val before = w.cartridgeText()
            val free = DtcFromMission.freeLines(m)
            val suggested = DtcFromMission.planLines(m, lineOpts.filter { it.kind != DtcFromMission.LineKind.ROUTE })
            appendLine("  free lines before: $free; suggested: " + suggested.joinToString { "L${it.first} ${it.second.short}" })
            val said = press(w, "btnSaveDtcLine", "btnApply", "Replace")
            said.forEach { appendLine("  $it") }
            val after = w.cartridgeText()
            appendLine("  edits: " + edits(before, after))
            val lines = DtcParser.parseText(after.orEmpty()).lines
            for ((k, o) in suggested) {
                val pts = lines.filter { it.line == k }.sortedBy { it.n }.map { Pt(it.x, it.y) }
                check("line $k reads back as ${o.label}", pts.size == o.points.size && pts.indices.all { pts[it].dist(o.points[it]) < 2.0 }, "${pts.size} points")
            }
            check("the page names what a line holds", suggested.all { (k, o) -> w.values(emptyList()).values["lblLine_$k.text"] == "Line $k: ${o.label}" },
                suggested.joinToString { (k, _) -> w.values(emptyList()).values["lblLine_$k.text"].orEmpty() })
            check("the lines count as unsaved edits", suggested.isEmpty() || w.unsaved > 0, "${w.unsaved}")
            // a placed line is replaced only after a question (the Map page's call)
            val route = routeRuns.firstOrNull()
            if (route != null) {
                val first = w.placeLine(1, route, ask = true)
                val occupied = DtcFromMission.linePoints(m, 1).isNotEmpty()
                appendLine("  placeLine(1, ${route.short}): $first")
                val q = WdpDialogs.stack.lastOrNull() as? WdpMessage
                check("placing over a line that holds points asks first", !occupied || (w.asked(first) && q != null), q?.text?.take(100) ?: "")
                if (q != null) { WdpDialogs.stack.remove(q); q.onAnswer?.invoke("Replace") }
                val l1 = DtcFromMission.linePoints(m, 1)
                check("…and Replace lays it", l1.size == route.points.size && l1.indices.all { l1[it].dist(route.points[it]) < 2.0 })
            }
        }

        // ---------------------------------------------------------------- PPTs
        appendLine()
        appendLine("== PPT: From mission…, Place")
        run {
            val w = page()
            val before = w.cartridgeText()
            val opts = w.pptOptions(f)
            appendLine("  options: " + opts.options.joinToString { "${it.label} ${it.code} ${if (it.marker) "marker" else DtcFromMission.nm(it.rangeFt)}" } +
                "; untyped ${opts.untyped}; no marker ${opts.noMarker}; already ${opts.already}")
            val plan = DtcFromMission.planPpts(w.model!!, opts.options)
            val said = press(w, "btnSaveDtcPpt", "Place")
            said.forEach { appendLine("  $it") }
            val after = w.cartridgeText()
            appendLine("  edits: " + edits(before, after))
            val ppts = DtcParser.parseText(after.orEmpty()).ppts
            for ((slot, o) in plan.placements) {
                val p = ppts.firstOrNull { it.n == slot }
                check("PPT $slot reads back as ${o.label} (${o.code})",
                    p != null && Pt(p.x, p.y).dist(o.at) < 2.0 && p.code.equals(o.code, true) && p.marker == o.marker,
                    p?.let { "code ${it.code}, ${if (it.marker) "marker" else "${it.rangeFt.toInt()} ft"}" } ?: "missing")
            }
            // pressed again, nothing is placed twice
            val again = w.pptOptions(f)
            check("pressed again, what is placed is not offered again", again.options.none { a -> plan.placements.any { it.second.code == a.code && it.second.at.dist(a.at) < 1.0 } },
                "already ${again.already}")
        }

        // ---------------------------------------------------------------- PPT typing over the save's own sites
        appendLine()
        appendLine("== PPT typing: the save's known enemy air-defence sites (what the Needs ask the save reader to serve)")
        run {
            val reference = runBlocking { Repo.threats() }
            val all = saveSites(set, th.name, file.name, row!!.id, reference)
            val known = all.filter { it.source == SPOTTED }
            appendLine("  hostile air-defence battalions in the save: ${all.size}, spotted by the flight's side: ${known.size}; raw spotted fields (value: count) $spottedSeen; $sideSeen")
            appendLine("  systems: " + all.groupBy { it.name }.entries.sortedByDescending { it.value.size }.joinToString { "${it.key} ×${it.value.size} (${it.value.count { s -> s.source == SPOTTED }} spotted)" })
            // typed over the spotted ones when there are any; else over them all, to show the typing
            val sites = known.ifEmpty { all }
            appendLine("  typed over: " + if (known.isNotEmpty()) "the spotted ones" else "all of them (none is spotted)")
            val w = page()
            val types = w.pptOptions(f.copy(sites = sites))
            val threats = types.options.filter { it.kind == DtcFromMission.PptKind.THREAT }
            appendLine("  typed: ${threats.size} rings, those reaching the route first (site off the route / ring edge off the route): " +
                threats.take(15).joinToString { "${it.label} ${it.fromRouteFt?.let(DtcFromMission::nm) ?: "?"} / ${it.edgeFt?.let(DtcFromMission::nm) ?: "?"}" })
            appendLine("  by type: " + threats.groupBy { it.code }.entries.joinToString { "${it.key} ×${it.value.size}" })
            appendLine("  untyped: ${types.untyped}")
            // the HARM tables over the same sites: each system once, the nearest the route first, as the HARM list names it
            val codes = DtcWiring.harmCodesOf(runBlocking { Repo.harm() }?.alicCodes.orEmpty())
            val harm = DtcFromMission.harmPicks(f.copy(sites = sites), codes)
            appendLine("  HARM picks over them: " + harm.joinToString { "${it.label} (${it.code})" })
            check("HARM picks name each system once, at most five", harm.size in 1..5 && harm.distinctBy { it.code }.size == harm.size, "${harm.size}")
            check("the rings that reach the route come first (ring edge nearest the route first)", threats.zipWithNext().all { (a, b) -> (a.edgeFt ?: 0.0) <= (b.edgeFt ?: 0.0) })
            check("the save's AAA types as the table's AAA (through the threat reference's category)",
                all.none { it.category == "AAA" } || threats.any { it.code.equals("AAA", true) }, "categories " + all.groupBy { it.category }.mapValues { it.value.size })
            val table = w.pptTypesInUse
            fun type(vararg names: String, cat: String? = null) = DtcFromMission.threatType(table, DtcFromMission.Site(names.toList(), Pt(1.0, 1.0), "test", cat))?.second
            val cases = listOf(
                Triple(arrayOf("SA-6 Gainful TEL"), null, "SA-6"),
                Triple(arrayOf("2S6 Tunguska"), null, "SA-19 (2S6)"), Triple(arrayOf("ZSU-23-4 Shilka"), "AAA", "AAA"),
                Triple(arrayOf("SA-10 Grumble"), null, "SA-10"), Triple(arrayOf("Patriot PAC-2"), null, "Patriot"),
                Triple(arrayOf("SA-2 Guideline"), null, "SA-2"),
            )
            for ((n, cat, want) in cases) {
                val got = type(*n, cat = cat)
                check("\"${n.first()}\"${cat?.let { " ($it)" } ?: ""} types as $want", got == want, "got ${got ?: "nothing"}")
            }
            // never a shorter system for a longer name, nor a type the table does not have
            val sa20 = type("SA-20 Gargoyle")
            check("\"SA-20 Gargoyle\" is never SA-2", sa20 != "SA-2", "got ${sa20 ?: "nothing"} (the table ${if (table.any { it.second == "SA-20" }) "has" else "has no"} SA-20)")
            check("\"Invented System\" types as nothing", type("Invented System") == null)
        }

        // ---------------------------------------------------------------- STPT
        appendLine()
        appendLine("== STPT: From mission…, Fill from the route; then a place into a slot that holds a point")
        run {
            val w = page()
            val before = w.cartridgeText()
            val fill = DtcFromMission.routeFill(w.model!!, f.route)
            val said = press(w, "btnSaveDtcStpt", "Fill from the route")
            said.forEach { appendLine("  $it") }
            val after = w.cartridgeText()
            appendLine("  edits: " + edits(before, after))
            val st = DtcParser.parseText(after.orEmpty()).steerpoints.associateBy { it.n }
            check("the empty steerpoints take the flight plan (${fill.size})", fill.all { p -> st[p.n]?.let { Pt(it.x, it.y).dist(p.at) < 2.0 && it.action == p.action } == true },
                fill.filter { p -> st[p.n]?.let { Pt(it.x, it.y).dist(p.at) < 2.0 } != true }.joinToString { "STPT ${it.n}" })
            val place = f.places.firstOrNull { it.group == "Your airbases" } ?: f.places.firstOrNull()
            val slot = fill.firstOrNull()?.n ?: 1
            if (place != null) {
                val msg = w.placeSteerpoint(slot, place)
                appendLine("  placeSteerpoint($slot, ${place.name}): $msg")
                val q = WdpDialogs.stack.lastOrNull() as? WdpMessage
                if (q != null) { WdpDialogs.stack.remove(q); q.onAnswer?.invoke("Replace") }
                val s = DtcParser.parseText(w.cartridgeText().orEmpty()).steerpoints.firstOrNull { it.n == slot }
                check("STPT $slot is ${place.name}, Precision", s != null && Pt(s.x, s.y).dist(place.at) < 2.0 && s.action == -1, s?.let { "action ${it.action}" } ?: "missing")
            }
        }

        // ---------------------------------------------------------------- Open 1, Targets
        appendLine()
        appendLine("== Open 1: From mission…, Place; Targets: From mission…, Add")
        run {
            val w = page()
            val before = w.cartridgeText()
            val fill = DtcFromMission.openFill(w.model!!.open, f.targets)
            press(w, "btnSaveDtcOpen", "Place").forEach { appendLine("  $it") }
            val mid = w.cartridgeText()
            appendLine("  edits: " + edits(before, mid))
            val open = DtcParser.parseText(mid.orEmpty()).open.associateBy { it.n }
            check("STPT 81-89 take the flight's targets (${fill.size})", fill.all { (i, p) -> open[81 + i]?.let { Pt(it.x, it.y).dist(p.at) < 2.0 } == true })
            val tf = DtcFromMission.targetFill(w.model!!, f.places.filter { it.group != "Steerpoints" && it.group != "Airbases" })
            press(w, "btnSaveDtcTgt", "Add").forEach { appendLine("  $it") }
            val after = w.cartridgeText()
            appendLine("  edits: " + edits(mid, after))
            check("the target list takes ${tf.size} places", tf.isEmpty() || DtcEdits.between(mid.orEmpty(), after.orEmpty()).isNotEmpty())
        }

        // ---------------------------------------------------------------- RADIO/NAV
        appendLine()
        appendLine("== RADIO/NAV: From mission… → TACAN…, ILS…, Comm plan")
        run {
            val w = page()
            val before = w.cartridgeText()
            // the tanker's air-to-air channel first (the pick a pilot most often wants), then the departure field's
            val picks = listOfNotNull(f.tacans.firstOrNull { it.domain == 1 }, f.tacans.firstOrNull { it.domain == 0 })
            if (picks.isEmpty()) appendLine("  no TACAN offered")
            for (t in picks) {
                press(w, "btnSaveDtcRad", "TACAN…", t.short).forEach { appendLine("  $it") }
                val c = w.model!!.comm
                check("TACAN is ${t.channel}${if (t.band == 1) "Y" else "X"} ${if (t.domain == 1) "A/A TR" else "T/R"} on the page",
                    c.tacanChannel == t.channel && c.tacanBand == t.band && c.tacanDomain == t.domain, "${c.tacanChannel} band ${c.tacanBand} domain ${c.tacanDomain}")
            }
            f.tacans.firstOrNull { it.domain == 1 }?.let { t ->
                val tanker = f.support.firstOrNull { it.tacan?.startsWith(DtcFromMission.tieOn(t.channel).toString()) == true }
                check("the tanker's tie-on channel is 63 from its own (${tanker?.tacan} → ${t.channel})", tanker != null && kotlin.math.abs(DtcFromMission.tieOn(t.channel) - t.channel) == 63)
            }
            val i = f.ils.firstOrNull()
            if (i != null) {
                press(w, "btnSaveDtcRad", "ILS…", i.short).forEach { appendLine("  $it") }
                val c = w.model!!.comm
                check("ILS is ${i.freq100 / 100.0}, course ${i.course} on the page", c.ilsFrequency == i.freq100 && c.ilsCrs == i.course, "${c.ilsFrequency} / ${c.ilsCrs}")
            } else appendLine("  no ILS offered")
            val mid = w.cartridgeText()
            val cp = press(w, "btnSaveDtcRad", "Comm plan", "*")
            cp.forEach { appendLine("  $it") }
            check("the comm plan is offered for the flight BMS printed", cp.none { it.contains("not offered") })
            val after = w.cartridgeText()
            appendLine("  edits (TACAN, ILS): " + edits(before, mid))
            appendLine("  edits (comm plan): " + edits(mid, after))
            val ladder = printed.comms.filter { (it.uhfCh ?: 0) in 1..20 }.distinctBy { it.uhfCh }
            val uhf = w.model!!.radio.uhf
            fun khz(s: String?) = s?.let { Regex("(\\d{2,3}\\.\\d{1,3})").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }?.let { kotlin.math.round(it * 1000).toInt() }
            check("the printed comm ladder's UHF presets are on the page (${ladder.size})",
                ladder.all { e -> uhf?.getOrNull(e.uhfCh!!) == khz(e.uhf) },
                ladder.filter { e -> uhf?.getOrNull(e.uhfCh!!) != khz(e.uhf) }.joinToString { "${it.agency} ${it.uhf} [${it.uhfCh}] vs ${uhf?.getOrNull(it.uhfCh!!)}" })
        }

        // ---------------------------------------------------------------- SYSTEMS: laser codes
        appendLine()
        appendLine("== SYSTEMS: From mission…, Set (the save's laser codes)")
        run {
            val w = page()
            val before = w.cartridgeText()
            val (own, mate) = DtcFromMission.laserPair(f)
            press(w, "btnSaveDtcSys", "Set").forEach { appendLine("  $it") }
            val after = w.cartridgeText()
            appendLine("  edits: " + edits(before, after))
            val d = DtcParser.parseText(after.orEmpty())
            val l = w.model!!.laser
            if (own == null && mate == null) check("no laser code in the save: nothing set", before == after)
            else check("TGP $own, LST $mate on the page (and in the saved text where they differ from what it had)",
                (own == null || l.laserCode.toInt() == own) && (mate == null || l.lstCode.toInt() == mate) &&
                    (before == after || ((own == null || d.laserTgp == own) && (mate == null || d.laserLst == mate))),
                "page ${l.laserCode}/${l.lstCode}, text ${d.laserTgp}/${d.laserLst}")
        }

        // ---------------------------------------------------------------- HARM
        appendLine()
        appendLine("== HARM: From mission…, Table 1")
        run {
            val w = page()
            val before = w.cartridgeText()
            val said = press(w, "btnSaveDtcHarm", "Table 1")
            said.forEach { appendLine("  $it") }
            val after = w.cartridgeText()
            appendLine("  edits: " + edits(before, after))
            val offered = said.firstOrNull()?.contains("Systems:") == true
            check("HARM: a table filled where the mission names systems the lists know, else it says why", !offered || before != after)
        }

        // ---------------------------------------------------------------- the rest of the tabs are untouched
        appendLine()
        appendLine("== The nine From mission… are on the page, and WDP's Save DTC on the other five tabs")
        run {
            val v = w0.values(emptyList()).values
            check("From mission… shown on nine tabs", DtcWiring.FROM_MISSION.all { v[it] == "shown" && v["$it.text"] == DtcWiring.FROM_MISSION_TEXT })
            check("the other five tabs' Save DTC on the page, as WDP's", DtcWiring.SAVE_DTC.size == 5 && DtcWiring.SAVE_DTC.none { v[it] == "hidden" || v["$it.text"] == DtcWiring.FROM_MISSION_TEXT })
        }

        PlannerMissionState.source = PlannerMissionState.BRIEFING
        PlannerMissionState.flight = null
        WdpDialogs.stack.clear()
        appendLine()
        appendLine(if (fails == 0) "PASS" else "FAIL: $fails checks")
    }

    /**
     * The save's known enemy air-defence sites for the flight [flightId]: the battalions of sub-type 1 hostile to (or
     * at war with) the flight's team, not destroyed, spotted by that team — the reading the Needs ask the save reader
     * to serve with the flight (`CampFlight.airDefences`), done here to show what From mission… would make of it.
     */
    private fun saveSites(
        set: Theaters.TheaterSet, theater: String, file: String, flightId: String, reference: List<com.bmscompanion.app.data.Threat>,
    ): List<DtcFromMission.Site> = runCatching {
        val t = set.all.first { it.name.equals(theater, true) }
        val names = CampaignArchive.names(set, t)
        val f = set.saves(t).first { it.name.equals(file, true) }
        val save = CampaignArchive.cached(f, names)
        val fl = save.flight(CampaignArchive.VuId.parse(flightId)!!)!!
        val team = save.teams?.firstOrNull { it.n == fl.owner } ?: return@runCatching emptyList()
        save.units.filterIsInstance<CampaignArchive.Battalion>().filter { u ->
            val s = team.stance.getOrNull(u.owner)
            (s == TeamRelations.HOSTILE || s == TeamRelations.WAR) && u.core.unitFlags and 0x20000 == 0 && names.ct(u.type)?.subType == 1
        }.map { u ->
            val sys = names.aircraft(u.type).orEmpty()
            // spotting is kept per side: the team that controls the flight's (in Korea ROK controls the U.S. team)
            val spotted = (u.core.spotted and ((1 shl team.controller) or (1 shl fl.owner))) != 0
            sideSeen = "flight's team ${fl.owner}, controlled by ${team.controller}"
            spottedSeen[u.core.spotted] = (spottedSeen[u.core.spotted] ?: 0) + 1
            com.bmscompanion.app.ui.screens.wdp.DtcMissionFacts.siteOf(sys, u.north, u.east, reference, if (spotted) SPOTTED else NOT_SPOTTED)
        }
    }.getOrElse { emptyList() }

    /** The raw `spotted` fields of the sites read, and how many had each: whether the flag reads as a team bit field. */
    private val spottedSeen = java.util.TreeMap<Int, Int>()
    private var sideSeen = ""

    private const val SPOTTED = "the save's known sites"
    private const val NOT_SPOTTED = "the save (not spotted by your side)"
}
