@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bmscompanion.desktop.bridge

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampFlight
import com.bmscompanion.app.data.mission.MissionData
import com.bmscompanion.app.data.wdp.DtcFromMission
import com.bmscompanion.app.data.wdp.DtcFromMission.Pt
import com.bmscompanion.app.ui.components.MapProjection
import com.bmscompanion.app.ui.screens.wdp.DtcCoords
import com.bmscompanion.app.ui.screens.wdp.DtcMissionFacts
import com.bmscompanion.app.ui.screens.wdp.DtcWiring
import com.bmscompanion.app.ui.screens.wdp.MapActions
import com.bmscompanion.app.ui.screens.wdp.MapLayersNow
import com.bmscompanion.app.ui.screens.wdp.MapPick
import com.bmscompanion.app.ui.screens.wdp.MapUi
import com.bmscompanion.app.ui.screens.wdp.PlannerMissionState
import com.bmscompanion.app.ui.screens.wdp.WdpDialogs
import com.bmscompanion.app.ui.screens.wdp.WdpMapPage
import com.bmscompanion.app.ui.screens.wdp.WdpMapView
import com.bmscompanion.app.ui.screens.wdp.WdpMapIntel
import com.bmscompanion.app.ui.screens.wdp.WdpMapPrefs
import com.bmscompanion.app.ui.screens.wdp.WdpMessage
import com.bmscompanion.app.ui.screens.wdp.MapCursor
import com.bmscompanion.app.ui.screens.wdp.INTEL_SITES
import com.bmscompanion.app.ui.screens.wdp.mapBullseye
import com.bmscompanion.app.ui.screens.wdp.mapExtras
import com.bmscompanion.app.ui.screens.wdp.withIntel
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.ui.screens.wdp.WdpSession
import com.bmscompanion.app.ui.screens.wdp.mapData
import com.bmscompanion.app.ui.screens.wdp.pickAt
import com.bmscompanion.app.ui.screens.wdp.plannerMission
import com.bmscompanion.app.ui.screens.wdp.plannerTheater
import com.bmscompanion.app.ui.theme.BmsTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--wdpmaptest <a copy of the BMS folder> <out folder>` — the Planner's **Map** page on the copy's briefed flight, as
 * Open mission… opens it, with the pilot's cartridge held in memory ([WdpDtcFixture]: never written).
 *
 * The save's own known air-defence sites stand in for what the "brief" lane is asked to serve with the flight
 * (`CampFlight.airDefences`, fin-fill notes), read here as `DtcFromMissionTest` reads them. Checks, in process: a tap on
 * a threat picks it; its card's **Add as PPT** changes the cartridge's model (the PPT at the site, with the table's
 * type and range) and counts as an unsaved edit; a tanker track's **Add as line** lays the line; Move and Take out
 * change a PPT on a page of their own. Then the page is drawn with the track as a line and two threats as PPTs — map
 * view and HSD preview, at a tablet's and a phone's size — into `<out>/wdpmap-*.png`, and a real tap is sent to the
 * phone-size map to check it selects the site under it.
 */
internal object WdpMapTest {
    fun run(root: File, out: File): String = buildString {
        out.mkdirs()
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
        val mission = plannerMission(data, theater, flight)
        WdpDtcFixture.file = cartridge
        val reference = runBlocking { Repo.threats() }
        val airports = runBlocking { Repo.airportSet(theater.airportSet) }
        val sites = saveSites(set, th.name, file.name, flight.row.id, reference)
        appendLine("Map page on ${flight.row.callsign} (${th.name}), ${flight.route.size} waypoints; the save's known air-defence sites: ${sites.size}")

        /** A DTC page with the mission on it and what From mission… reads, read. */
        fun page(w: DtcWiring): DtcWiring {
            WdpDialogs.stack.clear()
            w.served = { MissionData(briefing = printed) }
            w.onMission(mission)
            runBlocking { w.prepareFacts() }
            repeat(100) { if (w.values(emptyList()).values["dgvPPT.rows"].orEmpty().isNotBlank() && w.fileName != null) return w; Thread.sleep(50) }
            return w
        }
        // the facts as the page gathers them, with the save's sites the hook will serve
        WdpMapView.facts = { w -> w.missionFacts().let { f -> if (f.sites.isEmpty()) f.copy(sites = sites) else f } }
        val ui = object : MapUi() {
            var said: String? = null
            override fun status(s: String?) { said = s; WdpMapView.status = s }
        }
        fun build(w: DtcWiring) = mapData(w, WdpMapView.facts(w), mission, airports, reference, null)

        // ---------------------------------------------------------------- the page's own DTC wiring, as the Planner's
        WdpSession.dtcSource = WdpDtcFixture.source()
        val w = page(WdpSession.dtc)
        WdpSession.appliedMission = mission
        check("a cartridge on the DTC page", w.model != null)
        var d = build(w)
        appendLine("what the HSD will show: ${d.shown.route.size} flight-plan STPTs (${d.shown.route.count { it.inDtc }} in the DTC), " +
            "${d.shown.stpts.count { !it.onRoute }} others, ${d.shown.ppts.size} PPTs, ${d.shown.lines.size} lines, ${d.shown.offsets.size} nav offsets")
        appendLine("what the mission knows: ${d.known.tracks.size} tracks (${d.known.tracks.joinToString { it.support.callsign }}), " +
            "${d.known.stations.size} stations, ${d.known.sites.size} sites (${d.known.sites.count { it.option != null }} typed, ${d.known.ringsOnRoute.size} on the route), " +
            "${d.fields.size} fields (${d.fields.joinToString { "${it.airport.name} ${it.role}" }})")
        check("the flight plan is drawn", d.shown.route.size >= 2, "${d.shown.route.size}")
        check("the flight's fields are known", d.fields.isNotEmpty())

        // ---------------------------------------------------------------- a tanker track as a line
        val track = d.known.tracks.firstOrNull { it.line != null && it.support.role.contains("tank", true) } ?: d.known.tracks.firstOrNull { it.line != null }
        check("a support track with a line option", track != null, d.known.tracks.joinToString { "${it.support.callsign} ${it.points.size} pts" })
        if (track != null) {
            val card = MapActions(w, d, DtcCoords(mission.coords), ui).card(MapPick.Track(track.support.callsign))
            appendLine("  card: ${card?.title} [${card?.tag}] " + card?.actions?.joinToString(" | ") { it.label })
            val add = card?.actions?.firstOrNull { it.label.startsWith("Add as line") }
            check("the track's card offers Add as line", add != null)
            if (add != null) {
                val k = Regex("line (\\d)").find(add.label)!!.groupValues[1].toInt()
                val r = add.run()
                appendLine("  ${add.label}: $r")
                val pts = DtcFromMission.linePoints(w.model!!, k)
                check("line $k holds the track", pts.size == track.line!!.points.size && pts.indices.all { pts[it].dist(track.line!!.points[it]) < 2.0 }, "${pts.size} points")
                // WDP lays a tanker track as its station box, closed: the box the map draws about the station leg
                if (track.legs.size >= 2) {
                    val box = com.bmscompanion.app.data.wdp.PlannerIntel.orbitBox(track.legs.first(), track.legs.last())
                    check("line $k is the station box the map draws (5 points, the first again)",
                        pts.size == 5 && pts.indices.all { pts[it].dist(box[it]) < 2.0 }, pts.joinToString(" | ") { "N ${it.north.toInt()} E ${it.east.toInt()}" })
                    check("the track's card offers no second box", card?.actions.orEmpty().none { it.label.startsWith("Orbit box") })
                }
                val again = build(w).known.tracks.firstOrNull { it.support.callsign == track.support.callsign }
                check("the map knows the track is in line $k", again?.inLine == k, "${again?.inLine}")
            }
        }

        // ---------------------------------------------------------------- two threats as PPTs, the first by a tap
        d = build(w)
        val typed = d.known.sites.withIndex().filter { it.value.option != null && it.value.inPpt == null }
            .sortedBy { it.value.edgeFt ?: Double.MAX_VALUE }
        check("typed threats to add", typed.size >= 2, "${typed.size}")
        val m = w.model!!
        for ((n, iv) in typed.take(2).withIndex()) {
            val (i, site) = iv
            // the tap: a projection with the site in the middle of a 1000 px square view, threats only
            val pr = MapProjection(500f - (site.site.at.east / theater.sizeFt * 60000).toFloat(), 500f - ((1 - site.site.at.north / theater.sizeFt) * 60000).toFloat(), 60000f, theater.sizeFt, 60000f / 1000f)
            val tap = pr.toScreen(site.site.at.north, site.site.at.east) + Offset(6f, -4f)
            val picked = pickAt(d, pr, tap, 45f, MapLayersNow.ALL.copy(route = false, ppts = false, lines = false, offsets = false, support = false, fields = false))
            if (n == 0) check("a tap on ${site.site.name} picks it", picked == MapPick.Site(i), "$picked")
            val card = MapActions(w, d, DtcCoords(mission.coords), ui).card(MapPick.Site(i))
            appendLine("  card: ${card?.title} [${card?.tag}] " + card?.actions?.joinToString(" | ") { it.label } + " — " + card?.lines?.take(2)?.joinToString(" / "))
            val add = card?.actions?.firstOrNull { it.label.startsWith("Add as PPT") }
            check("${site.site.name}: the card offers Add as PPT", add != null)
            if (add == null) continue
            val slot = Regex("PPT (\\d+)").find(add.label)!!.groupValues[1].toInt()
            val before = m.ppt[slot - 56].let { Triple(it.falconY, it.falconX, it.code) }
            val unsaved = w.unsaved
            val r = add.run()
            appendLine("  ${add.label}: $r")
            val p = m.ppt[slot - 56]
            val o = site.option!!
            check(
                "Add as PPT changed the cartridge's model: PPT $slot at the site, type ${o.code} ${DtcFromMission.nm(o.rangeFt)}",
                Pt(p.falconY.toDouble(), p.falconX.toDouble()).dist(site.site.at) < 2.0 && p.code == o.code && kotlin.math.abs(p.falconRng - o.rangeFt) < 1.0,
                "before $before, after ${p.falconY},${p.falconX} ${p.code} ${p.falconRng}",
            )
            check("…and counts as an unsaved edit", w.unsaved > unsaved, "$unsaved → ${w.unsaved}")
            d = build(w)
            check("…and the page now draws it as PPT $slot (no longer 'not in DTC')", d.known.sites[i].inPpt == slot && d.shown.ppt(slot) != null)
        }
        val text = w.cartridgeText().orEmpty()
        check("the cartridge text Save to DTC would write has the PPTs and the line", DtcParser.parseText(text).let { t -> t.ppts.size >= 2 && t.lines.isNotEmpty() },
            DtcParser.parseText(text).let { "${it.ppts.size} PPTs, ${it.lines.size} line points" })

        // ---------------------------------------------------------------- move and take out, on a page of their own
        run {
            val w2 = page(DtcWiring(WdpDtcFixture.source()))
            val d2 = build(w2)
            val s = d2.known.sites.firstOrNull { it.option != null } ?: return@run check("a typed site for Move", false)
            val slot = w2.freeSlots()?.ppts?.firstOrNull() ?: return@run check("a free PPT", false)
            w2.placePpt(slot, s.option!!, ask = false)
            val d3 = build(w2)
            val a = MapActions(w2, d3, DtcCoords(mission.coords), ui)
            val to = Pt(s.site.at.north + 3 * DtcFromMission.NM, s.site.at.east)
            a.card(MapPick.Ppt(slot))?.actions?.firstOrNull { it.label == "Move" }?.run?.invoke()
            check("Move waits for the tap", WdpMapView.moving == MapPick.Ppt(slot))
            a.moveTo(MapPick.Ppt(slot), to)
            val p = w2.model!!.ppt[slot - 56]
            check("the tap moved PPT $slot, keeping its type", Pt(p.falconY.toDouble(), p.falconX.toDouble()).dist(to) < 2.0 && p.code == s.option!!.code && WdpMapView.moving == null)
            MapActions(w2, build(w2), DtcCoords(mission.coords), ui).card(MapPick.Ppt(slot))?.actions?.firstOrNull { it.label.startsWith("Take out") }?.run?.invoke()
            check("Take out of the DTC empties PPT $slot", DtcFromMission.pptEmpty(w2.model!!, slot) && w2.model!!.ppt[slot - 56].code.orEmpty().isEmpty(), ui.said.orEmpty())
            // the nav offsets, laid out from their steerpoint as the jet lays them (TGT-TO-VRP from the target)
            val tgt = build(w2).shown.stpts.firstOrNull { it.inDtc }
            if (tgt == null) check("a cartridge steerpoint for the nav offsets", false) else {
                val nv = w2.model!!.nav
                nv.modesel = 2
                nv.vrp.stpt = tgt.n; nv.vrp.bearing = 180f; nv.vrp.range = 6076
                nv.vrpPup.stpt = tgt.n; nv.vrpPup.bearing = 200f; nv.vrpPup.range = 18000
                nv.oa1_2.stpt = tgt.n; nv.oa1_2.bearing = 90f; nv.oa1_2.range = 1000
                val sh = build(w2).shown
                val vrp = sh.offsets.firstOrNull { it.label == "VRP" }
                val oa = sh.offsets.firstOrNull { it.label == "OA1" }
                check(
                    "nav offsets on the map: VRP 1 nm south of STPT ${tgt.n}, OA1 1,000 ft east of it, the PUP between",
                    vrp != null && vrp.at.dist(Pt(tgt.at.north - 6076, tgt.at.east)) < 1.0 && oa != null && oa.at.dist(Pt(tgt.at.north, tgt.at.east + 1000)) < 1.0 &&
                        sh.offsetMode == "VRP" && sh.runIn.size == 3,
                    sh.offsets.joinToString { "${it.label} ${it.note}" },
                )
            }
            WdpMapView.sel = null
        }

        // ---------------------------------------------------------------- WDP's MAP tab: the save's intel through the PC's own routes
        Bridge.startForCheck()
        MissionLink.useThisPc()
        com.bmscompanion.app.data.Platform.encodeJpeg = { img, q -> com.bmscompanion.desktop.skiaJpeg(img, q) }
        WdpMapIntel.clear()
        val loaded = runBlocking { WdpMapIntel.load(mission, WdpMapIntel.key(mission), force = true) }
        val magVar = runBlocking { WdpMapIntel.magVar(mission) }
        val intel = loaded.intel
        check("the save's intel through /api/campaign/mapintel", intel != null && intel.units.isNotEmpty(), loaded.note ?: "${intel?.units?.size} units, ${intel?.radars?.size} radars, ${intel?.fields?.size} fields, ${intel?.jstars?.size} JSTARS")
        check("the theater's variation through /api/campaign/magvar", magVar?.at(theater.sizeFt / 2, theater.sizeFt / 2) != null, magVar?.file ?: "none")
        val x = mapExtras(w, mission, loaded, magVar, airports, reference, emptyList())
        appendLine("the MAP tab's layers: ${x.airports.size} airfields (${x.fieldOf.size} with an owner), ${x.navaids.size} navaids, ${x.stations.size} side stations, " +
            "${x.typesHostile.size} hostile systems (${x.typesHostile.take(6).joinToString { "${it.system} ${it.count}" }}), ${x.typesOwn.size} own, " +
            "${x.packageRoutes.size} package routes")
        check("the airfields are coloured by who holds them", x.fieldOf.values.any { it.side == "friendly" } && x.fieldOf.values.any { it.side == "hostile" })
        // by default only the mission's threats (no printed briefing of this flight here: the spotted sites ringing the
        // route); WDP's own Threats layer — every site the side has seen — with All known SAMs
        val mine = withIntel(WdpMapView.facts(w), x, emptySet(), reference).sites
        d = mapData(w, withIntel(WdpMapView.facts(w), x, emptySet(), reference, allThreats = true), mission, airports, reference, mapBullseye(mission), x)
        check("the threats come from the intel (WDP's rules) once it is there (All known SAMs)", d.known.sites.any { it.site.source == INTEL_SITES }, "${d.known.sites.size} sites")
        val routeFt = WdpMapView.facts(w).route.map { it.at.north to it.at.east }
        val far = mine.filter { s ->
            val ring = s.names.firstNotNullOfOrNull { x.ringOf(it) } ?: (com.bmscompanion.app.data.mission.MissionPicture.UNKNOWN_RING_NM * DtcFromMission.NM)
            com.bmscompanion.app.data.mission.MissionPicture.distanceToRoute(s.at.north, s.at.east, routeFt) - ring > com.bmscompanion.app.data.mission.MissionPicture.ROUTE_MARGIN_NM * DtcFromMission.NM + 1
        }
        check("by default only the mission's threats: ${mine.size} of ${d.known.sites.size}, each ringing the route", mine.size <= d.known.sites.size && far.isEmpty(),
            far.take(3).joinToString { it.name })
        // the theater's other fields are off unless the pilot turns them on: the flight's own are drawn regardless
        check("by default only the flight's own airfields (Airports and Airstrips off)", !WdpMapPrefs.airports.on && !WdpMapPrefs.airstrips.on,
            "airports ${WdpMapPrefs.airports.on}, airstrips ${WdpMapPrefs.airstrips.on}, flight's fields ${d.fields.size}")
        WdpMapPrefs.allThreats.set(true)
        run {
            val a = MapActions(w, d, DtcCoords(mission.coords), ui, mission)
            val picks = listOfNotNull(
                d.shown.route.firstOrNull()?.let { MapPick.Stpt(it.n) },
                d.shown.ppts.firstOrNull()?.let { MapPick.Ppt(it.slot) },
                x.airports.firstOrNull { f -> d.fields.none { it.airport.id == f.id } }?.let { MapPick.Field(it.id) },
                x.navaids.indices.firstOrNull()?.let { MapPick.Navaid(it) },
                x.units.values.firstOrNull { it.side == "hostile" }?.let { MapPick.GroundUnit(it.id) },
                x.radars.values.firstOrNull()?.let { MapPick.Radar(it.id) },
                x.packageRoutes.keys.firstOrNull()?.let { MapPick.PackageWp(it, 0) },
                x.stations.firstOrNull()?.let { MapPick.SideStation(it.support.callsign) },
                x.intel?.jstars?.firstOrNull()?.let { MapPick.Jstar(it.callsign) },
            )
            for (p in picks) {
                val f = a.hoverFacts(p)
                val c = a.card(p)
                check("hover and card for ${p::class.simpleName}", f.isNotEmpty() && c != null, f.joinToString(" / ").take(170))
            }
            val field = picks.filterIsInstance<MapPick.Field>().firstOrNull()?.let { a.card(it) }
            check("an airfield's card offers its charts", field?.actions?.any { it.label == "Charts…" } == true, field?.actions?.joinToString(" | ") { it.label }.orEmpty())
            // Measure: two taps, a track and a distance
            WdpMapView.measureOn = false
            WdpMapView.toggleMeasure()
            val m1 = d.shown.route.first().at
            val m2 = d.shown.route.last { it.at.dist(m1) > 10_000 }.at
            WdpMapView.measureTap(m1); WdpMapView.measureTap(m2)
            val (trk, mag, nm) = com.bmscompanion.app.data.wdp.PlannerIntel.measure(m1, m2, magVar?.at(m1.north, m1.east))
            check("Measure: two taps give a track and a distance", WdpMapView.m1 == m1 && WdpMapView.m2 == m2 && kotlin.math.abs(nm - m1.dist(m2) / DtcFromMission.NM) < 0.05,
                "trk %.1f°T (%s°M) %.1f nm".format(trk, mag?.let { "%.1f".format(it) } ?: "-", nm))
            WdpMapView.measureTap(m1)
            check("…a third tap starts again", WdpMapView.m1 == m1 && WdpMapView.m2 == null)
            WdpMapView.toggleMeasure()
            // Auto PPT, Clear PPT, Clear Lines: each asks first while something is placed
            fun answer(what: String, button: String): String? {
                val q = WdpDialogs.stack.lastOrNull() as? WdpMessage ?: return null
                WdpDialogs.stack.remove(q); q.onAnswer?.invoke(button)
                return q.text.replace('\n', ' ').take(140)
            }
            val auto = a.autoPpt()
            val opts = a.autoPptOptions()
            check("Auto PPT is offered (threats and a cartridge)", auto != null, "${opts.size} rings near the route: " + opts.take(5).joinToString { it.label })
            if (auto != null) {
                WdpDialogs.stack.clear()
                val held = (56..70).count { !DtcFromMission.pptEmpty(w.model!!, it) }
                val unsaved0 = w.unsaved
                val r = auto.run()
                val q = answer("Auto PPT", "Replace")
                if (opts.isEmpty()) check("Auto PPT with no ring near the route says so and leaves the PPTs", q == null && r.orEmpty().contains("left as they are") &&
                    (56..70).count { !DtcFromMission.pptEmpty(w.model!!, it) } == held, r.orEmpty())
                else {
                    check("Auto PPT asks before replacing placed PPTs", held == 0 || q != null, q ?: r.orEmpty())
                    val now = (56..70).filter { !DtcFromMission.pptEmpty(w.model!!, it) }
                    check("…and Replace puts the rings in PPT 56-${55 + opts.size.coerceAtMost(15)}", now == (56 until 56 + opts.size.coerceAtMost(15)).toList() &&
                        w.model!!.ppt[0].code == opts[0].code, now.joinToString())
                }
                // the replacing itself (the DTC page's call Auto PPT makes), with up to three of the rings the map draws
                // (only the mission's threats are drawn by default, so a mission may have fewer than three)
                val three = d.known.sites.mapNotNull { it.option }.take(3)
                w.replacePpts(three, ask = true)
                val q3 = answer("Auto PPT", "Replace")
                val now = (56..70).filter { !DtcFromMission.pptEmpty(w.model!!, it) }
                check("replacing PPT 56-70 asks while any holds a point, then lays the ${three.size} rings in 56-${55 + three.size}",
                    three.isNotEmpty() && (held == 0 || q3 != null) && now == (56 until 56 + three.size).toList() &&
                    (0..2).all { w.model!!.ppt[it].code == three[it].code }, (q3 ?: "") + " → " + now.joinToString())
                check("…and counts in Save to DTC", w.unsaved > 0, "$unsaved0 → ${w.unsaved} edits")
            }
            val d2 = build(w)
            val a2 = MapActions(w, d2, DtcCoords(mission.coords), ui, mission)
            WdpDialogs.stack.clear()
            a2.clearPpts()?.run?.invoke()
            val q1 = answer("Clear PPT", "Clear")
            check("Clear PPT asks, naming them, then empties all fifteen", q1 != null && (56..70).all { DtcFromMission.pptEmpty(w.model!!, it) }, q1.orEmpty())
            a2.clearLines()?.run?.invoke()
            val q2 = answer("Clear Lines", "Clear")
            check("Clear Lines asks, then empties the four lines", q2 != null && (1..4).all { DtcFromMission.lineEmpty(w.model!!, it) }, q2.orEmpty())
            WdpDialogs.stack.clear()
        }

        // ---------------------------------------------------------------- Add to Open bank… (STPT 81-99), on a page of its own
        openBank(this, ::check, page(DtcWiring(WdpDtcFixture.source())), mission, x, airports, reference, out, ui)

        // ---------------------------------------------------------------- the pictures
        d = build(w)
        val next = d.known.sites.withIndex().firstOrNull { it.value.inPpt == null && it.value.option != null }?.index
        val shots = listOf(
            Shot("tablet-map", 1180, 820, 1.5f, hsd = false, sel = next?.let { MapPick.Site(it) }),
            Shot("tablet-hsd", 1180, 820, 1.5f, hsd = true, sel = null),
            Shot("phone-map", 412, 892, 2.0f, hsd = false, sel = next?.let { MapPick.Site(it) }),
            Shot("phone-hsd", 412, 892, 2.0f, hsd = true, sel = d.shown.ppts.firstOrNull()?.let { MapPick.Ppt(it.slot) }),
        )
        for (s in shots) {
            WdpMapView.hsd = s.hsd
            WdpMapView.hsdRange = 60
            WdpMapView.hsdCentre = null
            WdpMapView.sel = s.sel
            WdpMapView.status = null
            WdpMapView.listOpen = false
            WdpMapView.framed = null
            val scene = ImageComposeScene((s.w * s.density).toInt(), (s.h * s.density).toInt(), Density(s.density)) {
                BmsTheme { WdpMapPage(mission, Modifier.fillMaxSize()) }
            }
            try {
                var t = 0L
                repeat(70) { scene.render(t); t += 50_000_000; Thread.sleep(50) }
                File(out, "wdpmap-${s.name}.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                appendLine("ok    drawn: wdpmap-${s.name}.png (${s.w} x ${s.h} dp at ${s.density})")
                // a real tap on the phone's map: the next site, where the map shows it
                if (s.name == "phone-map") {
                    WdpMapView.sel = null
                    repeat(10) { scene.render(t); t += 50_000_000; Thread.sleep(20) }
                    val st = WdpMapView.map
                    val wPx = s.w * s.density
                    val hPx = s.h * s.density
                    // the map covers its box (TheaterMap's fillBox): the square is the longer side, the pan held inside it
                    val cover = maxOf(wPx, hPx)
                    val side = cover * st.scale
                    val px = st.panX.coerceIn(-((side - wPx) / 2f).coerceAtLeast(0f), ((side - wPx) / 2f).coerceAtLeast(0f))
                    val py = st.panY.coerceIn(-((side - hPx) / 2f).coerceAtLeast(0f), ((side - hPx) / 2f).coerceAtLeast(0f))
                    val pr = MapProjection((wPx - side) / 2 + px, (hPx - side) / 2 + py, side, theater.sizeFt, st.scale)
                    // a site in the open part of the map: not under the chips, the zoom buttons or the card
                    val target = d.known.sites.withIndex().firstOrNull { (i, v) ->
                        val q = pr.toScreen(v.site.at.north, v.site.at.east)
                        v.inPpt == null && q.x in 60f..(wPx - 160f) && q.y in 220f..(hPx - 700f) && pickAt(d, pr, q, 90f, MapLayersNow.now()) == MapPick.Site(i)
                    }?.index
                    check("a site alone in the open part of the phone's map, for the tap", target != null)
                    if (target == null) continue
                    val next = target
                    val at = d.known.sites[next].site.at
                    val p = pr.toScreen(at.north, at.east)
                    var ms = 10_000L
                    scene.sendPointerEvent(PointerEventType.Move, p, timeMillis = ms)
                    scene.sendPointerEvent(PointerEventType.Press, p, timeMillis = ms, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    repeat(3) { scene.render(t); t += 16_000_000; Thread.sleep(16) }
                    ms += 60
                    scene.sendPointerEvent(PointerEventType.Release, p, timeMillis = ms, buttons = PointerButtons(), button = PointerButton.Primary)
                    repeat(40) { scene.render(t); t += 50_000_000; Thread.sleep(25) }
                    check("a real tap on the phone's map selects ${d.known.sites[next].site.name}", WdpMapView.sel == MapPick.Site(next), "${WdpMapView.sel} at ${p.x.toInt()},${p.y.toInt()}")
                }
            } catch (e: Throwable) {
                check("drawing ${s.name}", false, "${e::class.simpleName}: ${e.message}")
                e.stackTrace.filter { it.className.startsWith("com.bmscompanion") }.take(8).forEach { appendLine("       at $it") }
            } finally { scene.close() }
        }

        // ---------------------------------------------------------------- WDP's MAP tab's layers on, the options open
        val p = WdpMapPrefs
        val switches = listOf(p.grid, p.airports, p.airstrips, p.vortac, p.maxRange, p.trkDist, p.search, p.noRadar, p.ownSams, p.allThreats, p.ships, p.jstarArea, p.extraLines, p.cursorBulls)
        val before = switches.map { it.on }
        val labelsBefore = p.airportLabels
        switches.forEach { it.set(true) }
        p.chooseAirportLabels(0)
        WdpMapView.packageOn.clear()
        x.packageRoutes.keys.firstOrNull { it != flight.row.id }?.let { WdpMapView.packageOn.add(it) }
        for (s in listOf(Shot("pc-options", 1600, 1000, 1f, false, null), Shot("tablet-options", 1000, 800, 1.5f, false, null), Shot("phone-options", 400, 800, 2f, false, null))) {
            WdpMapView.hsd = false
            WdpMapView.sel = null
            WdpMapView.status = null
            WdpMapView.listOpen = false
            WdpMapView.framed = null
            WdpMapView.panelTab = 0
            WdpMapView.optionsOpen = s.name != "pc-options"
            MapCursor.at = null; MapCursor.hover = null; MapCursor.ready = false
            val pc = s.name == "pc-options"
            if (pc) {
                WdpMapView.measureOn = false; WdpMapView.toggleMeasure()
                d.shown.route.firstOrNull()?.let { WdpMapView.measureTap(it.at) }
                d.known.tracks.firstOrNull()?.legs?.firstOrNull()?.let { WdpMapView.measureTap(it) }
            } else if (WdpMapView.measureOn) WdpMapView.toggleMeasure()
            val scene = ImageComposeScene((s.w * s.density).toInt(), (s.h * s.density).toInt(), Density(s.density)) {
                BmsTheme { WdpMapPage(mission, Modifier.fillMaxSize()) }
            }
            try {
                var t = 0L
                repeat(90) { scene.render(t); t += 50_000_000; Thread.sleep(50) }
                if (pc) {
                    // the mouse rests on an airfield or a VORTAC: WDP's info label beside it
                    val pr = MapCursor.proj
                    val dd = build(w).let { mapData(w, withIntel(WdpMapView.facts(w), x, emptySet(), reference, allThreats = true), mission, airports, reference, mapBullseye(mission), x) }
                    val layers = MapLayersNow.now(x.theaterKey)
                    val mapW = s.w - 421f
                    val spot = pr?.let { q ->
                        (x.navaids.map { Pt(it.x, it.y) } + x.airports.map { Pt(it.x, it.y) }).map { q.toScreen(it.north, it.east) }
                            .firstOrNull { o -> o.x in 120f..(mapW - 120f) && o.y in 160f..(s.h - 200f) && pickAt(dd, q, o, 45f, layers) !is MapPick.Point }
                    }
                    check("an airfield or VORTAC in the open part of the PC's map, for the mouse", spot != null)
                    if (spot != null) {
                        // (Measure takes the pointer: it is off while the mouse rests)
                        val m = WdpMapView.measureOn
                        if (m) WdpMapView.toggleMeasure()
                        var ms = 20_000L
                        scene.sendPointerEvent(PointerEventType.Enter, spot, timeMillis = ms)
                        scene.sendPointerEvent(PointerEventType.Move, spot + Offset(1f, 1f), timeMillis = ms + 10)
                        repeat(24) { scene.render(t); t += 50_000_000; Thread.sleep(40) }
                        val hover = MapCursor.hover
                        check("the mouse resting on it shows WDP's info label", hover != null && hover !is MapPick.Point, "$hover")
                        File(out, "wdpmap-pc-hover.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        appendLine("ok    drawn: wdpmap-pc-hover.png")
                        // a right-click: the point card there, even over the airfield (WDP's add menu)
                        ms += 1000
                        scene.sendPointerEvent(PointerEventType.Press, spot, timeMillis = ms, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
                        scene.sendPointerEvent(PointerEventType.Release, spot, timeMillis = ms + 60, buttons = PointerButtons(), button = PointerButton.Secondary)
                        repeat(10) { scene.render(t); t += 50_000_000; Thread.sleep(20) }
                        val card = WdpMapView.sel?.let { MapActions(w, dd, DtcCoords(mission.coords), ui, mission).card(it) }
                        check("a right-click opens the point card there, with PPT here…", WdpMapView.sel is MapPick.Point && card?.actions?.any { it.label == "PPT here…" } == true,
                            "${WdpMapView.sel} " + card?.actions?.joinToString(" | ") { it.label })
                        WdpMapView.sel = null
                        scene.sendPointerEvent(PointerEventType.Exit, Offset(-5f, -5f), timeMillis = ms + 200)
                        if (m) { WdpMapView.toggleMeasure(); d.shown.route.firstOrNull()?.let { WdpMapView.measureTap(it.at) }; d.known.tracks.firstOrNull()?.legs?.firstOrNull()?.let { WdpMapView.measureTap(it) } }
                        repeat(10) { scene.render(t); t += 50_000_000; Thread.sleep(20) }
                    }
                    // Save Map: the map as shown, as a JPEG where the file window says
                    val saved = File(out, "wdpmap-saved.jpg").also { it.delete() }
                    com.bmscompanion.app.data.PcFiles.testAnswer = { saved.path }
                    try {
                        WdpMapView.tools?.saveMap?.invoke()
                        repeat(100) { if (WdpMapView.status?.startsWith("Map saved") == true || saved.length() > 0) return@repeat; scene.render(t); t += 50_000_000; Thread.sleep(50) }
                        repeat(20) { if (WdpMapView.status != null) return@repeat; scene.render(t); t += 50_000_000; Thread.sleep(50) }
                    } finally { com.bmscompanion.app.data.PcFiles.testAnswer = null }
                    val bytes = if (saved.isFile) saved.readBytes() else ByteArray(0)
                    check("Save Map writes the map as a JPEG", bytes.size > 20_000 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte(),
                        "${bytes.size} bytes; ${WdpMapView.status}")
                    WdpMapView.status = null
                }
                File(out, "wdpmap-${s.name}.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                appendLine("ok    drawn: wdpmap-${s.name}.png (${s.w} x ${s.h} dp at ${s.density})")
            } catch (e: Throwable) {
                check("drawing ${s.name}", false, "${e::class.simpleName}: ${e.message}")
                e.stackTrace.filter { it.className.startsWith("com.bmscompanion") }.take(8).forEach { appendLine("       at $it") }
            } finally { scene.close() }
        }
        switches.forEachIndexed { i, sw -> sw.set(before[i]) }
        p.chooseAirportLabels(labelsBefore)
        // back to the start: the mission's threats only
        p.allThreats.set(false)
        WdpMapView.packageOn.clear()
        WdpMapView.optionsOpen = false
        if (WdpMapView.measureOn) WdpMapView.toggleMeasure()

        PlannerMissionState.source = PlannerMissionState.BRIEFING
        PlannerMissionState.flight = null
        WdpDialogs.stack.clear()
        appendLine()
        appendLine(if (fails == 0) "PASS" else "FAIL: $fails checks")
    }

    private class Shot(val name: String, val w: Int, val h: Int, val density: Float, val hsd: Boolean, val sel: MapPick?)

    /**
     * **Add to Open bank…** (1.3.9): a right-click on Incheon (else a field off the flight's) → the chooser on the field,
     * Land, the first free slot from 81 → the DTC page's Open 1 tab shows it, it counts in Save to DTC and is written as
     * `target_<n-1>` with Land's code 7 and the field's name; a second point into the same slot asks first (Cancel
     * keeps it, Replace takes it); Move keeps its type; Take out empties it. The chooser drawn at a PC's and a phone's
     * size into `<out>/openbank-*.png`.
     */
    private fun openBank(
        sb: StringBuilder, check: (String, Boolean, String) -> Unit, w: DtcWiring, mission: com.bmscompanion.app.ui.screens.wdp.WdpMission,
        x: com.bmscompanion.app.ui.screens.wdp.MapExtras, airports: com.bmscompanion.app.data.AirportSet?, reference: List<com.bmscompanion.app.data.Threat>,
        out: File, ui: MapUi,
    ) {
        val coords = DtcCoords(mission.coords)
        fun data() = mapData(w, WdpMapView.facts(w), mission, airports, reference, mapBullseye(mission), x)
        var d = data()
        val ours = d.fields.map { it.airport.id }.toSet()
        val field = x.airports.firstOrNull { it.name.contains("Incheon", true) } ?: x.airports.firstOrNull { it.id !in ours && (it.elevationFt ?: 0) > 0 }
        if (field == null || w.model == null) { check("Open bank: an airfield and a cartridge", false, "${x.airports.size} airfields"); return }
        val m = w.model!!
        val free = (81..99).firstOrNull { DtcFromMission.stptEmpty(if (it <= 89) m.open[it - 81] else m.hpn[it - 90]) }
        sb.appendLine("Open bank: ${field.name} (${field.icao}), first free slot STPT $free")
        // the phone's list first (its long press), while no window is open
        listLongPress(sb, check, mission, out)
        // the right-click: the point card a little off the field's middle
        val near = Pt(field.x + 900.0, field.y - 600.0)
        val card = MapActions(w, d, coords, ui, mission).card(MapPick.Point(near))
        val act = card?.actions?.firstOrNull { it.label == com.bmscompanion.app.ui.screens.wdp.OpenBankChooser.LABEL }
        check("a right-click near ${field.name} offers Add to Open bank…", act != null, card?.actions?.joinToString(" | ") { it.label }.orEmpty())
        if (act == null || free == null) return
        WdpDialogs.stack.clear()
        act.run()
        val c = com.bmscompanion.app.ui.screens.wdp.OpenBankChooser.current
        check("…which opens the chooser on ${field.name} first, as Land, on STPT $free", c != null && c.isOpen && c.choices.first().field && c.action == 7 && c.slot == free &&
            c.choices.size == 2, c?.let { "${it.choices.map { ch -> ch.label }} action ${it.action} slot ${it.slot}" }.orEmpty())
        if (c == null) return
        check("…listing STPT 81-99 with what each holds", c.slots().map { it.n } == (81..99).toList(), c.slots().filter { it.holds != null }.joinToString { "${it.n} ${it.holds}" })
        check("…and every type the Open tabs' Change window offers", com.bmscompanion.app.ui.screens.wdp.OpenBankChooser.TYPES.size == 28 &&
            com.bmscompanion.app.ui.screens.wdp.OpenBankChooser.TYPES.containsAll(listOf(-1, 0, 7, 8)), com.bmscompanion.app.ui.screens.wdp.OpenBankChooser.TYPES.joinToString())
        // the pictures of the chooser over the Map page, PC and phone, before it is applied
        shots(sb, check, c, mission, out)
        c.pick(0); c.slot = free; c.more = false
        val unsaved = w.unsaved
        val r = c.apply()
        val s = if (free <= 89) m.open[free - 81] else m.hpn[free - 90]
        check("Add as STPT $free puts ${field.name} there as Land, at its elevation", r != null && Pt(s.falconY.toDouble(), s.falconX.toDouble()).dist(Pt(field.x, field.y)) < 2.0 &&
            s.action == 7 && s.target.orEmpty().startsWith(field.name) && kotlin.math.abs(kotlin.math.abs(s.falconZ) - (field.elevationFt ?: 0)) < 1.0 && !c.isOpen,
            "$r — ${s.falconY},${s.falconX} z ${s.falconZ} action ${s.action} '${s.target}'")
        check("…counts as an unsaved edit", w.unsaved > unsaved, "$unsaved → ${w.unsaved}")
        val v = w.values(emptyList()).values
        val i = if (free <= 89) free - 80 else free - 89
        val tab = if (free <= 89) "Open" else "Hpn"
        check("…and the DTC page's ${if (free <= 89) "Open 1" else "Open 2"} tab shows it (Land, the name)", v["lbl${tab}Action$i.text"] == "Land" && (free > 89 || i > 6 || v["lbl${tab}Target_$i.text"].orEmpty().startsWith(field.name)),
            "action '${v["lbl${tab}Action$i.text"]}', name '${v["lbl${tab}Target_$i.text"]}'")
        d = data()
        val shown = d.shown.stpt(free)
        check("…and the map draws STPT $free (open, Land, not a target)", shown != null && shown.open && shown.inDtc && shown.action == 7 && !shown.target, "$shown")
        val text = w.cartridgeText().orEmpty()
        val line = text.lineSequence().firstOrNull { it.trim().startsWith("target_${free - 1}=") || it.trim().startsWith("target_${free - 1} ") }?.trim()
        check("Save to DTC would write target_${free - 1} with Land's code 7 and the name", line != null && Regex("^target_${free - 1}\\s*=\\s*[^,]+,[^,]+,[^,]+,\\s*7,\\s*${Regex.escape(field.name)}").containsMatchIn(line), line.orEmpty())
        // the same slot again from another point: WDP's question first
        val other = Pt(field.x + 5 * DtcFromMission.NM, field.y)
        fun answer(button: String): String? {
            val q = WdpDialogs.stack.lastOrNull() as? WdpMessage ?: return null
            WdpDialogs.stack.remove(q); q.onAnswer?.invoke(button)
            return q.text.replace('\n', ' ')
        }
        fun again(): com.bmscompanion.app.ui.screens.wdp.OpenBankChooser? {
            WdpDialogs.stack.clear()
            MapActions(w, data(), coords, ui, mission).card(MapPick.Point(other))?.actions?.firstOrNull { it.label == com.bmscompanion.app.ui.screens.wdp.OpenBankChooser.LABEL }?.run?.invoke()
            return com.bmscompanion.app.ui.screens.wdp.OpenBankChooser.current?.also { it.slot = free }
        }
        val c2 = again()
        check("a point near nothing opens the chooser on the point alone, as Nav", c2 != null && c2.choices.size == 1 && c2.action == 0 && c2.buttonText() == "Replace STPT $free…",
            c2?.let { "${it.choices.map { ch -> ch.label }} ${it.action} '${it.buttonText()}'" }.orEmpty())
        val r2 = c2?.apply()
        val q = answer("Cancel")
        check("…and replacing STPT $free asks first; Cancel keeps ${field.name}", r2 == null && q != null && q.contains(field.name) && s.action == 7 && s.target.orEmpty().startsWith(field.name), q.orEmpty())
        val c3 = again()
        c3?.apply()
        val q2 = answer("Replace")
        check("…Replace puts the point there, as Nav", q2 != null && Pt(s.falconY.toDouble(), s.falconX.toDouble()).dist(other) < 2.0 && s.action == 0, "${s.falconY},${s.falconX} ${s.action}")
        WdpDialogs.stack.clear()
        // back to the field, then Move and Change type… and Take out, as on any placed steerpoint
        w.placeSteerpoint(free, DtcFromMission.Place(field.name, Pt(field.x, field.y), (field.elevationFt ?: 0).toDouble(), "Airbases"), ask = false, action = 7)
        val a = MapActions(w, data(), coords, ui, mission)
        a.card(MapPick.Stpt(free))?.actions?.firstOrNull { it.label == "Move" }?.run?.invoke()
        val to = Pt(field.x + 2000.0, field.y + 2000.0)
        a.moveTo(MapPick.Stpt(free), to)
        check("Move puts STPT $free where the tap was, keeping Land", Pt(s.falconY.toDouble(), s.falconX.toDouble()).dist(to) < 2.0 && s.action == 7, "${s.action}")
        val stCard = MapActions(w, data(), coords, ui, mission).card(MapPick.Stpt(free))
        val change = stCard?.actions?.firstOrNull { it.label == "Change type…" }
        change?.run?.invoke()
        val c4 = com.bmscompanion.app.ui.screens.wdp.OpenBankChooser.current
        c4?.action = 8
        val r4 = c4?.apply()
        check("Change type… sets it to Holding without a question, in place", change != null && r4 != null && s.action == 8 && Pt(s.falconY.toDouble(), s.falconX.toDouble()).dist(to) < 2.0 && WdpDialogs.stack.isEmpty(), r4.orEmpty())
        MapActions(w, data(), coords, ui, mission).card(MapPick.Stpt(free))?.actions?.firstOrNull { it.label.startsWith("Take out") }?.run?.invoke()
        check("Take out of the DTC empties STPT $free", DtcFromMission.stptEmpty(s) && data().shown.stpt(free) == null, "")
        WdpDialogs.stack.clear()
        WdpMapView.sel = null
    }

    /** The chooser [c] over the Map page with the point card under it: a PC's window and a phone (by finger). */
    private fun shots(sb: StringBuilder, check: (String, Boolean, String) -> Unit, c: com.bmscompanion.app.ui.screens.wdp.OpenBankChooser, mission: com.bmscompanion.app.ui.screens.wdp.WdpMission, out: File) {
        val sel = WdpMapView.sel
        for ((name, size) in listOf("pc" to Triple(1600, 1000, 1f), "phone" to Triple(412, 892, 2f))) {
            com.bmscompanion.app.ui.screens.wdp.WdpTouch.seen = name == "phone"
            WdpMapView.hsd = false; WdpMapView.listOpen = false; WdpMapView.status = null; WdpMapView.framed = null; WdpMapView.optionsOpen = false
            WdpMapView.sel = MapPick.Point(c.choices.last().at)
            val (wd, ht, den) = size
            val scene = ImageComposeScene((wd * den).toInt(), (ht * den).toInt(), Density(den)) {
                BmsTheme {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                        WdpMapPage(mission, Modifier.fillMaxSize())
                        com.bmscompanion.app.ui.screens.wdp.WdpDialogHost()
                    }
                }
            }
            try {
                com.bmscompanion.app.ui.screens.wdp.WdpProbe.on = true
                // each size starts from the chooser as it opened
                c.pick(0); c.more = false
                var t = 0L
                repeat(70) { scene.render(t); t += 50_000_000; Thread.sleep(40) }
                File(out, "openbank-$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                sb.appendLine("ok    drawn: openbank-$name.png ($wd x $ht dp at $den)")
                // every kind of control of the chooser pressed with the pointer (a mouse on the PC, a finger on the phone)
                var ms = 50_000L
                fun press(probe: String): Boolean {
                    val r = com.bmscompanion.app.ui.screens.wdp.WdpProbe.rects["planner/Map/$probe"] ?: return false
                    val p = r.center
                    // a mouse's press on both (the phone laid out for fingers): this headless scene, driven off the Swing
                    // thread, takes only every other finger tap of a row of them; the long press above is a real finger
                    val touch = false
                    val type = if (touch) androidx.compose.ui.input.pointer.PointerType.Touch else androidx.compose.ui.input.pointer.PointerType.Mouse
                    if (!touch) scene.sendPointerEvent(PointerEventType.Move, p, timeMillis = ms, type = type)
                    scene.sendPointerEvent(PointerEventType.Press, p, timeMillis = ms, type = type, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    repeat(2) { scene.render(t); t += 16_000_000; Thread.sleep(16) }
                    ms += 80
                    scene.sendPointerEvent(PointerEventType.Release, p, timeMillis = ms, type = type, buttons = PointerButtons(), button = PointerButton.Primary)
                    // a finger's tap settles (the tap's own timeout, the scroll's) before the next one
                    repeat(if (touch) 20 else 6) { scene.render(t); t += 50_000_000; Thread.sleep(if (touch) 40 else 20) }
                    ms += if (touch) 1500 else 400
                    return true
                }
                val slot0 = c.slot
                // (the slots first: with every type listed they move down the scrolling window)
                val steps = listOf(
                    Triple("OpenBankSlot/83", "a slot picked: STPT 83") { c.slot == 83 },
                    Triple("OpenBankSlot/$slot0", "back on STPT $slot0") { c.slot == slot0 },
                    Triple("OpenBankWhat/1", "the point itself picked: Nav") { c.choice == 1 && c.action == 0 },
                    Triple("OpenBankWhat/0", "the field again: Land") { c.choice == 0 && c.action == 7 },
                    Triple("OpenBankMore", "More types… lists every type") { c.more },
                    Triple("OpenBankType/Holding", "a type picked: Holding") { c.action == 8 },
                    Triple("OpenBankType/Land", "Land again") { c.action == 7 },
                )
                val fails = ArrayList<String>()
                for ((probe, what, ok) in steps) {
                    val pressed = try { press(probe) } catch (e: Throwable) { fails += "$probe threw ${e::class.simpleName}: ${e.message}"; continue }
                    if (!pressed) fails += "$probe not on screen" else if (!ok()) fails += "$probe: not $what"
                    if (System.getenv("BMSC_OPENBANK_STEPS") != null) {
                        File(out, "openbank-$name-step-${probe.replace('/', '-')}.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        sb.appendLine("      step $probe at ${com.bmscompanion.app.ui.screens.wdp.WdpProbe.rects["planner/Map/$probe"]}: choice ${c.choice} action ${c.action} slot ${c.slot} more ${c.more}")
                    }
                    if (probe == "OpenBankType/Holding" && name == "pc") {
                        File(out, "openbank-pc-types.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        sb.appendLine("ok    drawn: openbank-pc-types.png (every type listed)")
                    }
                }
                check("the chooser's controls pressed ($name${if (name == "phone") ", laid out for fingers" else ""}): a slot and back, the point, the field, More types…, two types", fails.isEmpty() && c.isOpen,
                    fails.joinToString("; "))
            } catch (e: Throwable) {
                check("drawing the chooser ($name)", false, "${e::class.simpleName}: ${e.message}")
            } finally { scene.close() }
        }
        com.bmscompanion.app.ui.screens.wdp.WdpTouch.seen = false
        com.bmscompanion.app.ui.screens.wdp.WdpProbe.on = false
        WdpMapView.sel = sel
    }

    /**
     * The phone's list (a sheet over the map, by finger): a long press on one of the flight's fields opens the Open bank
     * chooser on it straight away, as Land. Drawn into `<out>/openbank-phone-list.png` (the list) and
     * `openbank-phone-longpress.png` (the chooser it opened). Leaves no window open.
     */
    private fun listLongPress(sb: StringBuilder, check: (String, Boolean, String) -> Unit, mission: com.bmscompanion.app.ui.screens.wdp.WdpMission, out: File) {
        val probe = com.bmscompanion.app.ui.screens.wdp.WdpProbe
        com.bmscompanion.app.ui.screens.wdp.WdpTouch.seen = true
        probe.on = true
        WdpDialogs.stack.clear()
        com.bmscompanion.app.ui.screens.wdp.OpenBankChooser.current = null
        WdpMapView.hsd = false; WdpMapView.sel = null; WdpMapView.status = null; WdpMapView.framed = null; WdpMapView.optionsOpen = false
        WdpMapView.listOpen = true
        val (wd, ht, den) = Triple(412, 892, 2f)
        val scene = ImageComposeScene((wd * den).toInt(), (ht * den).toInt(), Density(den)) {
            BmsTheme {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    WdpMapPage(mission, Modifier.fillMaxSize())
                    com.bmscompanion.app.ui.screens.wdp.WdpDialogHost()
                }
            }
        }
        try {
            var t = 0L
            repeat(70) { scene.render(t); t += 50_000_000; Thread.sleep(40) }
            File(out, "openbank-phone-list.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            sb.appendLine("ok    drawn: openbank-phone-list.png")
            // a row on screen, a field's first (the list scrolls; a row below the sheet is laid out but not under the finger)
            val shown = probe.rects.filter { (k, r) -> k.startsWith("planner/Map/ListBank/") && r.top > 0f && r.bottom < ht * den }
            val key = shown.keys.firstOrNull { it.contains("(departure") || it.contains("(alternate") || it.contains("(arrival") } ?: shown.keys.firstOrNull()
            check("the phone's list offers a long press on a field or threat", key != null, probe.rects.keys.filter { it.startsWith("planner/Map/List") }.joinToString())
            if (key == null) return
            val p = probe.rects[key]!!.center
            val type = androidx.compose.ui.input.pointer.PointerType.Touch
            scene.sendPointerEvent(PointerEventType.Press, p, timeMillis = 90_000L, type = type, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            repeat(20) { scene.render(t); t += 50_000_000; Thread.sleep(50) }
            scene.sendPointerEvent(PointerEventType.Release, p, timeMillis = 91_200L, type = type, buttons = PointerButtons(), button = PointerButton.Primary)
            repeat(20) { scene.render(t); t += 50_000_000; Thread.sleep(30) }
            val c = com.bmscompanion.app.ui.screens.wdp.OpenBankChooser.current
            File(out, "openbank-phone-longpress.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            sb.appendLine("ok    drawn: openbank-phone-longpress.png")
            check("…a long press on \"${key.substringAfterLast('/')}\" opens the chooser on it (the list closed)", c != null && c.isOpen && !WdpMapView.listOpen &&
                c.choices.isNotEmpty() && (c.choices.first().action == 7 || !c.choices.first().field),
                c?.let { "${it.choices.map { ch -> ch.label }} action ${it.action} slot ${it.slot}" } ?: "no chooser")
        } catch (e: Throwable) {
            check("the phone's list long press", false, "${e::class.simpleName}: ${e.message}")
        } finally {
            scene.close()
            WdpDialogs.stack.clear()
            com.bmscompanion.app.ui.screens.wdp.OpenBankChooser.current = null
            WdpMapView.listOpen = false
            com.bmscompanion.app.ui.screens.wdp.WdpTouch.seen = false
            probe.on = false
        }
    }

    /**
     * The save's known enemy air-defence sites for [flightId], spotted by the side controlling the flight's team: what
     * the "brief" lane is asked to serve as `CampFlight.airDefences` (as `DtcFromMissionTest.saveSites` reads them).
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
            (s == TeamRelations.HOSTILE || s == TeamRelations.WAR) && u.core.unitFlags and 0x20000 == 0 && names.ct(u.type)?.subType == 1 &&
                (u.core.spotted and (1 shl team.controller)) != 0
        }.map { u -> DtcMissionFacts.siteOf(names.aircraft(u.type).orEmpty(), u.north, u.east, reference, DtcMissionFacts.SAVE_SITES) }
    }.getOrElse { emptyList() }
}
